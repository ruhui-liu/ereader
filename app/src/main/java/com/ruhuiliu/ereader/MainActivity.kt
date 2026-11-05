package com.ruhuiliu.ereader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ruhuiliu.ereader.ui.AppNavHost
import com.ruhuiliu.ereader.ui.theme.EReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Lets the reader intercept hardware keys (volume keys for page turning). */
object KeyEvents {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null
}

/** Files shared to / opened with the app, waiting to be imported. */
object IncomingFiles {
    val pending = MutableStateFlow<List<Uri>>(emptyList())
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val container = (application as EReaderApp).container
        setContent {
            EReaderTheme {
                AppNavHost(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(uris::add)
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                uri?.let(uris::add)
            }
        }
        if (uris.isNotEmpty()) IncomingFiles.pending.value = IncomingFiles.pending.value + uris
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyEvents.handler?.invoke(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow the matching key-up so the volume doesn't change while turning pages.
        if (KeyEvents.handler?.invoke(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }
}
