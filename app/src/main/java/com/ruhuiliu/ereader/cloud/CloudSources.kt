package com.ruhuiliu.ereader.cloud

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import com.dropbox.core.v2.files.Metadata
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.ruhuiliu.ereader.BuildConfig
import com.ruhuiliu.ereader.data.BookImporter
import com.ruhuiliu.ereader.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class CloudEntry(
    /** Drive file id / Dropbox path. */
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long? = null,
    val mimeType: String? = null,
) {
    val isGoogleDoc get() = mimeType == GoogleDriveSource.GOOGLE_DOC
    val importable get() = !isFolder && (BookImporter.isSupportedName(name) || isGoogleDoc)
}

class CloudAuthException(message: String) : IOException(message)

/** Google Drive via the Drive REST API v3 with an OAuth token from Google Identity Services. */
class GoogleDriveSource {
    @Volatile
    var accessToken: String? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    sealed interface AuthState {
        data class Authorized(val token: String) : AuthState
        data class NeedsConsent(val intent: PendingIntent) : AuthState
    }

    /** Requests read-only Drive access. May require a consent screen (returned as a PendingIntent). */
    suspend fun authorize(activity: Activity): AuthState {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_READONLY)))
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        val pending = result.pendingIntent
        return if (result.hasResolution() && pending != null) {
            AuthState.NeedsConsent(pending)
        } else {
            val token = result.accessToken ?: throw CloudAuthException("未获得 Google Drive 授权")
            accessToken = token
            AuthState.Authorized(token)
        }
    }

    fun handleConsentResult(context: Context, data: Intent?): String {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        val token = result.accessToken ?: throw CloudAuthException("未获得 Google Drive 授权")
        accessToken = token
        return token
    }

    private fun get(url: String): String {
        val token = accessToken ?: throw CloudAuthException("未登录 Google Drive")
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) {
                accessToken = null
                throw CloudAuthException("Google Drive 授权已过期，请重试")
            }
            if (!resp.isSuccessful) throw IOException("Google Drive 错误 ${resp.code}: ${body.take(200)}")
            return body
        }
    }

    /** Lists a folder ("root" for My Drive), or runs [query] as a name search, or lists shared files. */
    suspend fun list(folderId: String?, query: String? = null, shared: Boolean = false): List<CloudEntry> =
        withContext(Dispatchers.IO) {
            val q = when {
                !query.isNullOrBlank() -> "name contains '${query.replace("\\", "\\\\").replace("'", "\\'")}' and trashed = false"
                shared -> "sharedWithMe = true and trashed = false"
                else -> "'${folderId ?: "root"}' in parents and trashed = false"
            }
            val out = mutableListOf<CloudEntry>()
            var pageToken: String? = null
            do {
                val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                    .addQueryParameter("q", q)
                    .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size)")
                    .addQueryParameter("pageSize", "200")
                    .addQueryParameter("orderBy", "folder,name_natural")
                    .addQueryParameter("supportsAllDrives", "true")
                    .addQueryParameter("includeItemsFromAllDrives", "true")
                    .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
                    .build().toString()
                val json = JSONObject(get(url))
                val files = json.optJSONArray("files")
                for (i in 0 until (files?.length() ?: 0)) {
                    val f = files!!.getJSONObject(i)
                    val mime = f.optString("mimeType")
                    out += CloudEntry(
                        id = f.getString("id"),
                        name = f.getString("name"),
                        isFolder = mime == FOLDER,
                        size = f.optString("size").toLongOrNull(),
                        mimeType = mime,
                    )
                }
                pageToken = json.optString("nextPageToken").ifEmpty { null }
            } while (pageToken != null && out.size < 2000)
            out
        }

    /** Downloads a file; Google Docs documents are exported as plain text. Returns the import name. */
    suspend fun download(entry: CloudEntry, dest: File, onProgress: (Long, Long?) -> Unit): String =
        withContext(Dispatchers.IO) {
            val token = accessToken ?: throw CloudAuthException("未登录 Google Drive")
            val url = if (entry.isGoogleDoc) {
                "https://www.googleapis.com/drive/v3/files/${entry.id}/export?mimeType=text/plain"
            } else {
                "https://www.googleapis.com/drive/v3/files/${entry.id}?alt=media&supportsAllDrives=true"
            }
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) {
                    accessToken = null
                    throw CloudAuthException("Google Drive 授权已过期，请重试")
                }
                if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("下载失败：空响应")
                val total = body.contentLength().takeIf { it > 0 } ?: entry.size
                body.byteStream().use { input -> dest.outputStream().use { out -> copy(input, out, total, onProgress) } }
            }
            if (entry.isGoogleDoc) "${entry.name}.txt" else entry.name
        }

    companion object {
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        const val FOLDER = "application/vnd.google-apps.folder"
        const val GOOGLE_DOC = "application/vnd.google-apps.document"
    }
}

/** Dropbox via the official SDK (OAuth 2 PKCE, short-lived tokens with refresh). */
class DropboxSource(private val context: Context, private val settings: SettingsRepository) {
    val appKey: String = BuildConfig.DROPBOX_APP_KEY
    val isConfigured: Boolean get() = appKey.isNotBlank()

    private val config = DbxRequestConfig.newBuilder("YiduReader/1.0").build()
    private var client: DbxClientV2? = null

    fun startLogin(activity: Activity) {
        Auth.startOAuth2PKCE(
            activity,
            appKey,
            config,
            listOf("account_info.read", "files.metadata.read", "files.content.read"),
        )
    }

    /** Call from onResume after [startLogin]; stores the credential if the user approved. */
    suspend fun finishLogin(): Boolean {
        val credential = Auth.getDbxCredential() ?: return false
        settings.setDropboxCredential(DbxCredential.Writer.writeToString(credential))
        client = DbxClientV2(config, credential)
        return true
    }

    suspend fun isLoggedIn(): Boolean = settings.dropboxCredential.first() != null

    private suspend fun client(): DbxClientV2 {
        client?.let { return it }
        val json = settings.dropboxCredential.first() ?: throw CloudAuthException("未登录 Dropbox")
        val credential = DbxCredential.Reader.readFully(json)
        return DbxClientV2(config, credential).also { client = it }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { client?.auth()?.tokenRevoke() }
        client = null
        settings.setDropboxCredential(null)
    }

    suspend fun accountName(): String? = withContext(Dispatchers.IO) {
        runCatching { client().users().currentAccount.name.displayName }.getOrNull()
    }

    suspend fun list(path: String): List<CloudEntry> = withContext(Dispatchers.IO) {
        val c = client()
        val out = mutableListOf<CloudEntry>()
        var result = c.files().listFolder(path)
        while (true) {
            result.entries.forEach { m -> toEntry(m)?.let(out::add) }
            if (!result.hasMore) break
            result = c.files().listFolderContinue(result.cursor)
        }
        out.sortedWith(compareBy<CloudEntry>({ !it.isFolder }, { it.name.lowercase() }))
    }

    private fun toEntry(m: Metadata): CloudEntry? = when (m) {
        is FolderMetadata -> CloudEntry(m.pathLower ?: m.pathDisplay ?: m.name, m.name, true)
        is FileMetadata -> CloudEntry(m.pathLower ?: m.pathDisplay ?: m.name, m.name, false, m.size)
        else -> null
    }

    suspend fun download(entry: CloudEntry, dest: File, onProgress: (Long, Long?) -> Unit): String =
        withContext(Dispatchers.IO) {
            val downloader = client().files().download(entry.id)
            dest.outputStream().use { out ->
                downloader.download(out) { bytes -> onProgress(bytes, entry.size) }
            }
            entry.name
        }
}

private fun copy(input: java.io.InputStream, out: OutputStream, total: Long?, onProgress: (Long, Long?) -> Unit) {
    val buf = ByteArray(64 * 1024)
    var done = 0L
    var lastReport = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        done += n
        if (done - lastReport > 128 * 1024) {
            onProgress(done, total)
            lastReport = done
        }
    }
    onProgress(done, total)
}
