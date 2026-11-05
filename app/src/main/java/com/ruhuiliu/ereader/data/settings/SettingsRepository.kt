package com.ruhuiliu.ereader.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val store get() = context.dataStore

    private object K {
        val fontSize = intPreferencesKey("r_font_size")
        val lineHeight = floatPreferencesKey("r_line_height")
        val paragraphSpacing = floatPreferencesKey("r_paragraph_spacing")
        val marginH = intPreferencesKey("r_margin_h")
        val marginV = intPreferencesKey("r_margin_v")
        val fontFamily = stringPreferencesKey("r_font_family")
        val theme = stringPreferencesKey("r_theme")
        val pageMode = stringPreferencesKey("r_page_mode")
        val textAlign = stringPreferencesKey("r_text_align")
        val indent = booleanPreferencesKey("r_indent")
        val overrideStyles = booleanPreferencesKey("r_override_styles")
        val showRuby = booleanPreferencesKey("r_show_ruby")
        val volumeKeys = booleanPreferencesKey("r_volume_keys")
        val keepScreenOn = booleanPreferencesKey("r_keep_screen_on")
        val pageAnimation = booleanPreferencesKey("r_page_animation")
        val translationScale = floatPreferencesKey("r_translation_scale")

        val engine = stringPreferencesKey("t_engine")
        val targetLang = stringPreferencesKey("t_target")
        val defaultMode = intPreferencesKey("t_default_mode")
        val googleKey = stringPreferencesKey("t_google_key")
        val deeplKey = stringPreferencesKey("t_deepl_key")
        val azureKey = stringPreferencesKey("t_azure_key")
        val azureRegion = stringPreferencesKey("t_azure_region")
        val openaiBaseUrl = stringPreferencesKey("t_openai_base")
        val openaiKey = stringPreferencesKey("t_openai_key")
        val openaiModel = stringPreferencesKey("t_openai_model")
        val claudeKey = stringPreferencesKey("t_claude_key")
        val claudeModel = stringPreferencesKey("t_claude_model")
        val llmExtraPrompt = stringPreferencesKey("t_llm_extra")

        val dropboxCredential = stringPreferencesKey("c_dropbox_credential")
        val libraryGrid = booleanPreferencesKey("l_grid")
        val librarySort = stringPreferencesKey("l_sort")
    }

    val readerSettings: Flow<ReaderSettings> = store.data.map { p ->
        val d = ReaderSettings()
        ReaderSettings(
            fontSize = p[K.fontSize] ?: d.fontSize,
            lineHeight = p[K.lineHeight] ?: d.lineHeight,
            paragraphSpacing = p[K.paragraphSpacing] ?: d.paragraphSpacing,
            marginH = p[K.marginH] ?: d.marginH,
            marginV = p[K.marginV] ?: d.marginV,
            fontFamily = p[K.fontFamily] ?: d.fontFamily,
            theme = p[K.theme] ?: d.theme,
            pageMode = p[K.pageMode] ?: d.pageMode,
            textAlign = p[K.textAlign] ?: d.textAlign,
            indent = p[K.indent] ?: d.indent,
            overrideStyles = p[K.overrideStyles] ?: d.overrideStyles,
            showRuby = p[K.showRuby] ?: d.showRuby,
            volumeKeys = p[K.volumeKeys] ?: d.volumeKeys,
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn,
            pageAnimation = p[K.pageAnimation] ?: d.pageAnimation,
            translationScale = p[K.translationScale] ?: d.translationScale,
        )
    }

    suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings) {
        val s = transform(readerSettings.first())
        store.edit { p ->
            p[K.fontSize] = s.fontSize
            p[K.lineHeight] = s.lineHeight
            p[K.paragraphSpacing] = s.paragraphSpacing
            p[K.marginH] = s.marginH
            p[K.marginV] = s.marginV
            p[K.fontFamily] = s.fontFamily
            p[K.theme] = s.theme
            p[K.pageMode] = s.pageMode
            p[K.textAlign] = s.textAlign
            p[K.indent] = s.indent
            p[K.overrideStyles] = s.overrideStyles
            p[K.showRuby] = s.showRuby
            p[K.volumeKeys] = s.volumeKeys
            p[K.keepScreenOn] = s.keepScreenOn
            p[K.pageAnimation] = s.pageAnimation
            p[K.translationScale] = s.translationScale
        }
    }

    val translationSettings: Flow<TranslationSettings> = store.data.map { p ->
        val d = TranslationSettings()
        TranslationSettings(
            engine = p[K.engine] ?: d.engine,
            targetLang = p[K.targetLang] ?: d.targetLang,
            defaultMode = p[K.defaultMode] ?: d.defaultMode,
            googleKey = p[K.googleKey] ?: d.googleKey,
            deeplKey = p[K.deeplKey] ?: d.deeplKey,
            azureKey = p[K.azureKey] ?: d.azureKey,
            azureRegion = p[K.azureRegion] ?: d.azureRegion,
            openaiBaseUrl = p[K.openaiBaseUrl] ?: d.openaiBaseUrl,
            openaiKey = p[K.openaiKey] ?: d.openaiKey,
            openaiModel = p[K.openaiModel] ?: d.openaiModel,
            claudeKey = p[K.claudeKey] ?: d.claudeKey,
            claudeModel = p[K.claudeModel] ?: d.claudeModel,
            llmExtraPrompt = p[K.llmExtraPrompt] ?: d.llmExtraPrompt,
        )
    }

    suspend fun updateTranslation(transform: (TranslationSettings) -> TranslationSettings) {
        val s = transform(translationSettings.first())
        store.edit { p ->
            p[K.engine] = s.engine
            p[K.targetLang] = s.targetLang
            p[K.defaultMode] = s.defaultMode
            p[K.googleKey] = s.googleKey.trim()
            p[K.deeplKey] = s.deeplKey.trim()
            p[K.azureKey] = s.azureKey.trim()
            p[K.azureRegion] = s.azureRegion.trim()
            p[K.openaiBaseUrl] = s.openaiBaseUrl.trim()
            p[K.openaiKey] = s.openaiKey.trim()
            p[K.openaiModel] = s.openaiModel.trim()
            p[K.claudeKey] = s.claudeKey.trim()
            p[K.claudeModel] = s.claudeModel.trim()
            p[K.llmExtraPrompt] = s.llmExtraPrompt
        }
    }

    val dropboxCredential: Flow<String?> = store.data.map { it[K.dropboxCredential] }

    suspend fun setDropboxCredential(value: String?) {
        store.edit { p -> if (value == null) p.remove(K.dropboxCredential) else p[K.dropboxCredential] = value }
    }

    val libraryGrid: Flow<Boolean> = store.data.map { it[K.libraryGrid] ?: true }
    suspend fun setLibraryGrid(value: Boolean) = store.edit { it[K.libraryGrid] = value }

    val librarySort: Flow<String> = store.data.map { it[K.librarySort] ?: "recent" }
    suspend fun setLibrarySort(value: String) = store.edit { it[K.librarySort] = value }
}
