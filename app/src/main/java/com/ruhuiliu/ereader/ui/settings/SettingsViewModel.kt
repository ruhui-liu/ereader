package com.ruhuiliu.ereader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.nl.translate.TranslateLanguage
import com.ruhuiliu.ereader.AppContainer
import com.ruhuiliu.ereader.data.settings.ReaderSettings
import com.ruhuiliu.ereader.data.settings.TranslationSettings
import com.ruhuiliu.ereader.translate.MlKitEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: AppContainer) : ViewModel() {
    val translation: StateFlow<TranslationSettings?> =
        app.settings.translationSettings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val reader: StateFlow<ReaderSettings> =
        app.settings.readerSettings.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val dropboxLoggedIn: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        viewModelScope.launch { app.settings.dropboxCredential.collect { flow.value = it != null } }
    }
    val dropboxConfigured get() = app.dropbox.isConfigured

    private val _test = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _test.asStateFlow()

    private val _models = MutableStateFlow<Set<String>>(emptySet())
    val models: StateFlow<Set<String>> = _models.asStateFlow()

    private val _modelBusy = MutableStateFlow<String?>(null)
    val modelBusy: StateFlow<String?> = _modelBusy.asStateFlow()

    private val _cacheCount = MutableStateFlow(0)
    val cacheCount: StateFlow<Int> = _cacheCount.asStateFlow()

    private val _fonts = MutableStateFlow(app.storage.fonts().map { it.name })
    val fonts: StateFlow<List<String>> = _fonts.asStateFlow()

    init {
        refreshModels()
        viewModelScope.launch { _cacheCount.value = app.translation.cacheCount() }
    }

    fun update(transform: (TranslationSettings) -> TranslationSettings) {
        viewModelScope.launch { app.settings.updateTranslation(transform) }
    }

    fun updateReader(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { app.settings.updateReader(transform) }
    }

    fun test() {
        _test.value = "正在测试…"
        viewModelScope.launch {
            _test.value = try {
                val samples = listOf("It was the best of times, it was the worst of times.", "吾輩は猫である。名前はまだ無い。")
                val engine = app.translation.engine()
                val target = app.translation.targetLang()
                val out = samples.map { s -> engine.translate(listOf(s), null, target).first() }
                "✓ 翻译成功\n" + samples.zip(out).joinToString("\n") { (a, b) -> "$a\n→ $b" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "✗ ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch { _models.value = runCatching { MlKitEngine.downloadedModels() }.getOrDefault(emptySet()) }
    }

    fun downloadModel(lang: String) {
        viewModelScope.launch {
            _modelBusy.value = lang
            try {
                MlKitEngine.downloadModel(lang)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _test.value = "✗ 模型下载失败：${e.message}"
            }
            _modelBusy.value = null
            refreshModels()
        }
    }

    fun deleteModel(lang: String) {
        viewModelScope.launch {
            runCatching { MlKitEngine.deleteModel(lang) }
            refreshModels()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            app.translation.clearCache()
            _cacheCount.value = 0
        }
    }

    fun logoutDropbox() {
        viewModelScope.launch { app.dropbox.logout() }
    }

    fun deleteFont(name: String) {
        app.storage.fonts().firstOrNull { it.name == name }?.delete()
        _fonts.value = app.storage.fonts().map { it.name }
        updateReader { if (it.fontFamily == "custom:$name") it.copy(fontFamily = "serif") else it }
    }

    companion object {
        val MLKIT_MODELS = listOf(
            TranslateLanguage.ENGLISH to "英语",
            TranslateLanguage.JAPANESE to "日语",
            TranslateLanguage.CHINESE to "中文",
            TranslateLanguage.KOREAN to "韩语",
            TranslateLanguage.FRENCH to "法语",
            TranslateLanguage.GERMAN to "德语",
        )
    }
}
