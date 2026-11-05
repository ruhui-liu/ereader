package com.ruhuiliu.ereader.ui.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ApiException
import com.ruhuiliu.ereader.AppContainer
import com.ruhuiliu.ereader.cloud.CloudAuthException
import com.ruhuiliu.ereader.cloud.CloudEntry
import com.ruhuiliu.ereader.cloud.GoogleDriveSource
import com.ruhuiliu.ereader.data.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class Crumb(val id: String, val name: String)

data class CloudUiState(
    val provider: String,
    val title: String,
    /** null = not decided yet, false = needs login / setup */
    val signedIn: Boolean? = null,
    val notConfigured: Boolean = false,
    val account: String? = null,
    val path: List<Crumb> = emptyList(),
    val entries: List<CloudEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val download: String? = null,
    val downloadProgress: Float? = null,
    val query: String = "",
    val driveTab: Int = 0, // 0 my drive, 1 shared with me
    val onlyBooks: Boolean = true,
)

sealed interface CloudEvent {
    data class Consent(val intent: PendingIntent) : CloudEvent
    data class Message(val text: String) : CloudEvent
    data class Opened(val bookId: Long) : CloudEvent
}

class CloudViewModel(private val app: AppContainer, val provider: String) : ViewModel() {
    private val drive = app.googleDrive
    private val dropbox = app.dropbox

    private val _state = MutableStateFlow(
        CloudUiState(
            provider = provider,
            title = if (provider == "dropbox") "Dropbox" else "Google Drive",
            path = listOf(Crumb(if (provider == "dropbox") "" else "root", if (provider == "dropbox") "Dropbox" else "我的云端硬盘")),
        )
    )
    val state: StateFlow<CloudUiState> = _state.asStateFlow()

    private val events = Channel<CloudEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var loadJob: Job? = null
    private var awaitingDropboxLogin = false

    /** Called when the screen is shown / resumed. */
    fun onResume(activity: Activity) {
        if (provider == "dropbox") {
            viewModelScope.launch {
                if (!dropbox.isConfigured) {
                    _state.update { it.copy(signedIn = false, notConfigured = true) }
                    return@launch
                }
                if (awaitingDropboxLogin) {
                    awaitingDropboxLogin = false
                    dropbox.finishLogin()
                }
                val loggedIn = dropbox.isLoggedIn()
                _state.update { it.copy(signedIn = loggedIn) }
                if (loggedIn && _state.value.entries.isEmpty() && !_state.value.loading) {
                    refresh()
                    _state.update { it.copy(account = dropbox.accountName()) }
                }
            }
        } else if (_state.value.signedIn == null) {
            authorizeDrive(activity)
        }
    }

    // ------------------------------------------------------------------ Google Drive auth

    fun authorizeDrive(activity: Activity) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                when (val r = drive.authorize(activity)) {
                    is GoogleDriveSource.AuthState.Authorized -> {
                        _state.update { it.copy(signedIn = true) }
                        refresh()
                    }
                    is GoogleDriveSource.AuthState.NeedsConsent -> {
                        _state.update { it.copy(loading = false) }
                        events.send(CloudEvent.Consent(r.intent))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, signedIn = false, error = driveError(e)) }
            }
        }
    }

    fun onConsentResult(context: Context, resultOk: Boolean, data: Intent?) {
        if (!resultOk) {
            _state.update { it.copy(signedIn = false, error = "已取消 Google Drive 授权") }
            return
        }
        try {
            drive.handleConsentResult(context, data)
            _state.update { it.copy(signedIn = true) }
            refresh()
        } catch (e: Exception) {
            _state.update { it.copy(signedIn = false, error = driveError(e)) }
        }
    }

    private fun driveError(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            10 -> "Google 登录配置错误（DEVELOPER_ERROR）。需要在 Google Cloud Console 为本应用的包名和签名 SHA-1 创建 Android OAuth 客户端，并启用 Drive API，详见 README。"
            7 -> "网络错误，无法连接 Google 服务"
            12501, 16 -> "已取消授权"
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------ Dropbox auth

    fun loginDropbox(activity: Activity) {
        awaitingDropboxLogin = true
        dropbox.startLogin(activity)
    }

    fun logoutDropbox() {
        viewModelScope.launch {
            dropbox.logout()
            _state.update { it.copy(signedIn = false, entries = emptyList(), account = null) }
        }
    }

    // ------------------------------------------------------------------ browsing

    fun setDriveTab(tab: Int) {
        _state.update {
            it.copy(
                driveTab = tab,
                query = "",
                path = listOf(if (tab == 0) Crumb("root", "我的云端硬盘") else Crumb("shared", "与我共享")),
            )
        }
        refresh()
    }

    fun setOnlyBooks(value: Boolean) = _state.update { it.copy(onlyBooks = value) }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        refresh()
    }

    fun open(entry: CloudEntry) {
        if (entry.isFolder) {
            _state.update { it.copy(path = it.path + Crumb(entry.id, entry.name), query = "") }
            refresh()
        } else if (entry.importable) {
            download(entry)
        } else {
            events.trySend(CloudEvent.Message("不支持这种文件格式（支持 EPUB / TXT / HTML）"))
        }
    }

    fun goTo(index: Int) {
        _state.update { it.copy(path = it.path.take(index + 1), query = "") }
        refresh()
    }

    /** Returns false if already at the root. */
    fun up(): Boolean {
        val s = _state.value
        if (s.query.isNotEmpty()) {
            search("")
            return true
        }
        if (s.path.size <= 1) return false
        goTo(s.path.size - 2)
        return true
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val s = _state.value
                val current = s.path.last()
                val entries = if (provider == "dropbox") {
                    val all = dropbox.list(current.id)
                    if (s.query.isBlank()) all else all.filter { it.name.contains(s.query, ignoreCase = true) }
                } else {
                    when {
                        s.query.isNotBlank() -> drive.list(null, query = s.query)
                        current.id == "shared" -> drive.list(null, shared = true)
                        else -> drive.list(current.id)
                    }
                }
                _state.update { it.copy(entries = entries, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudAuthException) {
                _state.update { it.copy(loading = false, error = e.message) }
                if (provider != "dropbox") _state.update { it.copy(signedIn = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun download(entry: CloudEntry) {
        if (_state.value.download != null) return
        viewModelScope.launch {
            _state.update { it.copy(download = entry.name, downloadProgress = null) }
            val temp = File(app.storage.tempDir, "dl-" + UUID.randomUUID())
            try {
                val progress: (Long, Long?) -> Unit = { done, total ->
                    val p = if (total != null && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    _state.update { it.copy(downloadProgress = p) }
                }
                val name = if (provider == "dropbox") dropbox.download(entry, temp, progress) else drive.download(entry, temp, progress)
                val source = if (provider == "dropbox") "dropbox" else "gdrive"
                when (val r = app.importer.importFile(temp, name, source)) {
                    is ImportResult.Added -> {
                        events.send(CloudEvent.Message("已导入《${r.book.title}》"))
                        events.send(CloudEvent.Opened(r.book.id))
                    }
                    is ImportResult.Duplicate -> {
                        events.send(CloudEvent.Message("《${r.book.title}》已在书架中"))
                        events.send(CloudEvent.Opened(r.book.id))
                    }
                    is ImportResult.Failed -> events.send(CloudEvent.Message("导入失败：${r.message}"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.send(CloudEvent.Message("下载失败：${e.message}"))
            } finally {
                temp.delete()
                _state.update { it.copy(download = null, downloadProgress = null) }
            }
        }
    }
}
