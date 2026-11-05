package com.ruhuiliu.ereader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books", indices = [Index("fileHash", unique = true)])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    /** [com.ruhuiliu.ereader.book.BookFormat] name. */
    val format: String,
    /** Directory (under files/books) holding the book file, index and cover. */
    val dirName: String,
    val fileName: String,
    val coverFile: String? = null,
    val language: String? = null,
    /** The original book uses vertical writing (tategaki). */
    val vertical: Boolean = false,
    /** The original book turns pages right-to-left. */
    val rtl: Boolean = false,
    val fileSize: Long = 0,
    val fileHash: String,
    /** Where the book was imported from: local / gdrive / dropbox. */
    val source: String = "local",
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0,
    // ---- reading position
    val chapterIndex: Int = 0,
    val chapterProgress: Float = 0f,
    /** "block:offset" anchor inside the chapter, precise across font/layout changes. */
    val anchor: String? = null,
    val totalProgress: Float = 0f,
    // ---- per-book preferences
    /** 0 = off, 1 = bilingual (original + translation), 2 = translation only. */
    val translateMode: Int = 0,
    /** Source language override for translation; null = auto. */
    val sourceLang: String? = null,
    /** For vertical books: true = convert to horizontal left-to-right (default), false = keep original. */
    val convertToHorizontal: Boolean = true,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val chapterProgress: Float,
    val anchor: String?,
    val chapterTitle: String,
    val excerpt: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "translation_cache")
data class TranslationCacheEntity(
    @PrimaryKey val key: String,
    val translated: String,
    val createdAt: Long = System.currentTimeMillis(),
)
