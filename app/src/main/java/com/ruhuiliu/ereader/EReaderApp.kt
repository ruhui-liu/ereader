package com.ruhuiliu.ereader

import android.app.Application
import com.ruhuiliu.ereader.cloud.DropboxSource
import com.ruhuiliu.ereader.cloud.GoogleDriveSource
import com.ruhuiliu.ereader.data.BookImporter
import com.ruhuiliu.ereader.data.BookRepository
import com.ruhuiliu.ereader.data.BookStorage
import com.ruhuiliu.ereader.data.db.AppDatabase
import com.ruhuiliu.ereader.data.settings.SettingsRepository
import com.ruhuiliu.ereader.translate.TranslationService
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency container. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(app)
    val storage = BookStorage(app)
    val settings = SettingsRepository(app)
    val books = BookRepository(db, storage)
    val importer = BookImporter(app, storage, db.books())
    val translation = TranslationService(settings, db.translationCache())
    val googleDrive = GoogleDriveSource()
    val dropbox = DropboxSource(app, settings)
}

class EReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
    }
}
