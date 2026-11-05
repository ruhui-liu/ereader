package com.ruhuiliu.ereader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY CASE WHEN lastReadAt > 0 THEN lastReadAt ELSE addedAt END DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE fileHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "UPDATE books SET chapterIndex = :chapter, chapterProgress = :progress, anchor = :anchor, " +
            "totalProgress = :total, lastReadAt = :time WHERE id = :id"
    )
    suspend fun updatePosition(id: Long, chapter: Int, progress: Float, anchor: String?, total: Float, time: Long)

    @Query("UPDATE books SET translateMode = :mode WHERE id = :id")
    suspend fun setTranslateMode(id: Long, mode: Int)

    @Query("UPDATE books SET sourceLang = :lang WHERE id = :id")
    suspend fun setSourceLang(id: Long, lang: String?)

    @Query("UPDATE books SET convertToHorizontal = :value WHERE id = :id")
    suspend fun setConvertToHorizontal(id: Long, value: Boolean)

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    suspend fun rename(id: Long, title: String, author: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, chapterProgress")
    fun observe(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TranslationCacheDao {
    @Query("SELECT * FROM translation_cache WHERE `key` IN (:keys)")
    suspend fun getAll(keys: List<String>): List<TranslationCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(items: List<TranslationCacheEntity>)

    @Query("DELETE FROM translation_cache WHERE `key` IN (:keys)")
    suspend fun deleteAll(keys: List<String>)

    @Query("DELETE FROM translation_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun count(): Int
}
