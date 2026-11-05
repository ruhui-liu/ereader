package com.ruhuiliu.ereader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.ruhuiliu.ereader.book.BookContent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

const val READER_HOST = "https://appassets.androidplatform.net"
const val READER_URL = "$READER_HOST/assets/reader/reader.html"
const val BOOK_BASE = "$READER_HOST/book/"
const val FONT_BASE = "$READER_HOST/fonts/"

/** Receives messages posted by reader.js and forwards them to the main thread. */
class ReaderBridge(private val onMessage: (JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        main.post { onMessage(obj) }
    }
}

/** Serves the files of the opened book under /book/. */
class BookPathHandler(private val content: () -> BookContent?) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        val book = content()
        val bytes = runCatching { book?.read(path) }.getOrNull()
            ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val mime = book!!.mimeType(path)
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(bytes))
    }
}

/** Serves user-imported fonts under /fonts/. */
class FontPathHandler(private val dir: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val f = File(dir, path.substringAfterLast('/'))
        if (!f.isFile) return null
        val mime = if (f.extension.equals("otf", true)) "font/otf" else "font/ttf"
        return WebResourceResponse(mime, null, f.inputStream())
    }
}

/** WebView with a "翻译" entry in the text selection menu. */
@SuppressLint("ViewConstructor")
class ReaderWebView(context: Context) : WebView(context) {
    var onTranslateSelection: ((String) -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(callback?.let { wrap(it) }, type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(callback?.let { wrap(it) })

    private fun wrap(cb: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val r = cb.onCreateActionMode(mode, menu)
            if (onTranslateSelection != null) menu.add(Menu.NONE, MENU_TRANSLATE, 0, "翻译")
            return r
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = cb.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == MENU_TRANSLATE) {
                evaluateJavascript("window.getSelection().toString()") { value ->
                    val text = runCatching { JSONArray("[$value]").getString(0) }.getOrNull()
                    if (!text.isNullOrBlank()) onTranslateSelection?.invoke(text.trim())
                }
                mode.finish()
                return true
            }
            return cb.onActionItemClicked(mode, item)
        }

        override fun onDestroyActionMode(mode: ActionMode) = cb.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (cb is ActionMode.Callback2) cb.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    companion object {
        private const val MENU_TRANSLATE = 0x7E1D

        @SuppressLint("SetJavaScriptEnabled")
        fun create(
            context: Context,
            content: () -> BookContent?,
            fontsDir: File,
            bridge: ReaderBridge,
            onExternalUrl: (String) -> Unit,
        ): ReaderWebView {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .addPathHandler("/book/", BookPathHandler(content))
                .addPathHandler("/fonts/", FontPathHandler(fontsDir))
                .build()
            return ReaderWebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.textZoom = 100
                // Pinch-zoom is only enabled by the page (viewport meta) for original PDF pages.
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.mediaPlaybackRequiresUserGesture = true
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                }
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                isHapticFeedbackEnabled = true
                addJavascriptInterface(bridge, "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith(READER_HOST)) return false
                        onExternalUrl(url)
                        return true
                    }
                }
            }
        }
    }
}
