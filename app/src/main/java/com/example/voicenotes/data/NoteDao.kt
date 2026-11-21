package com.example.voicenotes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY pinned DESC, position ASC, updatedAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Upsert
    suspend fun upsert(note: Note): Long

    @Delete
    suspend fun delete(note: Note)

    @Query("UPDATE notes SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE notes SET tileSize = :size WHERE id = :id")
    suspend fun setTileSize(id: Long, size: Int)

    @Query("UPDATE notes SET tileColor = :color WHERE id = :id")
    suspend fun setTileColor(id: Long, color: Long)

    @Query("UPDATE notes SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("UPDATE notes SET tileHeight = :height WHERE id = :id")
    suspend fun setTileHeight(id: Long, height: Int)

    @Query("UPDATE notes SET position = :position, newRow = :newRow, tileSize = :tileSize, tileHeight = :tileHeight WHERE id = :id")
    suspend fun setLayout(id: Long, position: Int, newRow: Boolean, tileSize: Int, tileHeight: Int)

    @Query("UPDATE notes SET parentId = :parentId, position = :position, newRow = :newRow WHERE id = :id")
    suspend fun setParent(id: Long, parentId: Long, position: Int, newRow: Boolean)

    @Query("UPDATE notes SET parentId = :parentId WHERE id = :id")
    suspend fun setParentId(id: Long, parentId: Long)

    @Query("UPDATE notes SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}
