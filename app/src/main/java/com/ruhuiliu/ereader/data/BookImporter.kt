package com.ruhuiliu.ereader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.ruhuiliu.ereader.book.BookFormat
import com.ruhuiliu.ereader.book.HtmlText
import com.ruhuiliu.ereader.book.LanguageGuess
import com.ruhuiliu.ereader.book.epub.EpubBook
import com.ruhuiliu.ereader.book.txt.TextDecoding
import com.ruhuiliu.ereader.book.txt.TxtBook
import com.ruhuiliu.ereader.data.db.BookDao
import com.ruhuiliu.ereader.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

sealed interface ImportResult {
    data class Added(val book: BookEntity) : ImportResult
    data class Duplicate(val book: BookEntity) : ImportResult
    data class Failed(val name: String, val message: String) : ImportResult
}

class BookImporter(
    private val context: Context,
    private val storage: BookStorage,
    private val dao: BookDao,
) {
    companion object {
        val SUPPORTED_EXTENSIONS = setOf("epub", "txt", "text", "md", "html", "htm", "xhtml")

        fun isSupportedName(name: String) = name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
    }

    /** Imports a document picked through the Storage Access Framework (or shared to the app). */
    suspend fun importUri(uri: Uri, source: String = "local"): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext ImportResult.Failed(name, "无法读取文件")
            importStream(input, name, source)
        } catch (e: Exception) {
            ImportResult.Failed(name, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Imports every supported file found (recursively) in a folder picked with OpenDocumentTree. */
    suspend fun importTree(treeUri: Uri, onEach: (ImportResult) -> Unit) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        fun walk(dir: DocumentFile, depth: Int, out: MutableList<DocumentFile>) {
            if (depth > 8) return
            for (f in dir.listFiles()) {
                if (f.isDirectory) walk(f, depth + 1, out)
                else if (f.name?.let { isSupportedName(it) } == true) out += f
            }
        }
        val files = mutableListOf<DocumentFile>().also { walk(root, 0, it) }
        for (f in files) onEach(importUri(f.uri))
    }

    /** Imports a downloaded file (cloud sources). The file is consumed. */
    suspend fun importFile(file: File, name: String, source: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            file.inputStream().use { importStream(it, name, source) }
        } catch (e: Exception) {
            ImportResult.Failed(name, e.message ?: e.javaClass.simpleName)
        } finally {
            file.delete()
        }
    }

    private suspend fun importStream(input: InputStream, displayName: String, source: String): ImportResult {
        val temp = File(storage.tempDir, UUID.randomUUID().toString())
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { ins ->
            temp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        try {
            dao.findByHash(hash)?.let { return ImportResult.Duplicate(it) }
            val format = detectFormat(temp, displayName)
                ?: return ImportResult.Failed(displayName, "不支持的文件格式（支持 EPUB、TXT、HTML）")
            val dirName = UUID.randomUUID().toString()
            val dir = File(storage.booksRoot, dirName).apply { mkdirs() }
            try {
                val entity = when (format) {
                    BookFormat.EPUB -> importEpub(temp, dir, dirName, displayName, hash, source)
                    BookFormat.TXT -> importTxt(temp, dir, dirName, displayName, hash, source)
                    BookFormat.HTML -> importHtml(temp, dir, dirName, displayName, hash, source)
                }
                val id = dao.insert(entity)
                return ImportResult.Added(entity.copy(id = id))
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        } finally {
            temp.delete()
        }
    }

    private fun detectFormat(file: File, name: String): BookFormat? {
        when (name.substringAfterLast('.', "").lowercase()) {
            "epub" -> return BookFormat.EPUB
            "txt", "text", "md" -> return BookFormat.TXT
            "html", "htm", "xhtml" -> return BookFormat.HTML
        }
        // Unknown extension: sniff the content.
        val head = file.inputStream().use { s -> ByteArray(4096).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            val isEpub = runCatching { ZipFile(file).use { it.getEntry("META-INF/container.xml") != null } }.getOrDefault(false)
            return if (isEpub) BookFormat.EPUB else null
        }
        val text = runCatching { TextDecoding.decode(head) }.getOrNull() ?: return null
        if (text.trimStart().startsWith("<") && Regex("<(html|body|p)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return BookFormat.HTML
        val printable = text.count { !it.isISOControl() || it == '\n' || it == '\t' }
        return if (text.isNotEmpty() && printable >= text.length * 0.95) BookFormat.TXT else null
    }

    private fun importEpub(temp: File, dir: File, dirName: String, displayName: String, hash: String, source: String): BookEntity {
        val target = File(dir, "book.epub")
        temp.copyTo(target, overwrite = true)
        EpubBook(target).use { book ->
            val pkg = book.pkg
            var coverName: String? = null
            pkg.coverPath?.let { path ->
                book.read(path)?.let { bytes ->
                    val ext = path.substringAfterLast('.', "img").lowercase().take(5)
                    coverName = "cover.$ext"
                    File(dir, coverName!!).writeBytes(bytes)
                }
            }
            val language = pkg.language ?: runCatching {
                LanguageGuess.guess(book.chapterText(minOf(1, book.chapters.size - 1)).take(4000))
            }.getOrNull()
            return BookEntity(
                title = pkg.title.ifBlank { displayName.substringBeforeLast('.') },
                author = pkg.author,
                format = BookFormat.EPUB.name,
                dirName = dirName,
                fileName = target.name,
                coverFile = coverName,
                language = language,
                vertical = pkg.vertical,
                rtl = pkg.rtl,
                fileSize = target.length(),
                fileHash = hash,
                source = source,
            )
        }
    }

    private fun importTxt(temp: File, dir: File, dirName: String, displayName: String, hash: String, source: String): BookEntity {
        val text = TextDecoding.decode(temp.readBytes())
        if (text.isBlank()) throw IllegalArgumentException("文件内容为空")
        val textFile = File(dir, "book.txt")
        TxtBook.write(text, textFile, File(dir, BookStorage.TXT_INDEX))
        val base = displayName.substringBeforeLast('.')
        // Common naming: 《书名》作者：xxx / 书名 - 作者
        val title = Regex("《(.+?)》").find(base)?.groupValues?.get(1) ?: base
        val author = Regex("作者[:：]\\s*(.+)$").find(base)?.groupValues?.get(1)?.trim()
            ?: Regex("作者[:：]\\s*(\\S+)").find(text.take(2000))?.groupValues?.get(1)?.trim().orEmpty()
        return BookEntity(
            title = title.trim().ifEmpty { "未命名" },
            author = author,
            format = BookFormat.TXT.name,
            dirName = dirName,
            fileName = textFile.name,
            language = LanguageGuess.guess(text.take(20_000)),
            fileSize = temp.length(),
            fileHash = hash,
            source = source,
        )
    }

    private fun importHtml(temp: File, dir: File, dirName: String, displayName: String, hash: String, source: String): BookEntity {
        val bytes = temp.readBytes()
        val declared = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val html = declared?.let { runCatching { String(bytes, charset(it)) }.getOrNull() } ?: TextDecoding.decode(bytes)
        val ext = if (displayName.endsWith(".xhtml", true)) "xhtml" else "html"
        val target = File(dir, "book.$ext")
        // Re-encoded as UTF-8: neutralize the old charset declaration.
        target.writeText(html.replace(Regex("(charset\\s*=\\s*[\"']?)[A-Za-z0-9_\\-]+", RegexOption.IGNORE_CASE), "$1utf-8"))
        val plain = HtmlText.toPlainText(html)
        return BookEntity(
            title = HtmlText.title(html) ?: displayName.substringBeforeLast('.'),
            author = "",
            format = BookFormat.HTML.name,
            dirName = dirName,
            fileName = target.name,
            language = LanguageGuess.guess(plain.take(20_000)),
            fileSize = temp.length(),
            fileHash = hash,
            source = source,
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
