package com.mgmemo.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Query(
        "SELECT * FROM notes WHERE title LIKE '%' || :query || '%' " +
            "OR content LIKE '%' || :query || '%' ORDER BY updatedAt DESC"
    )
    fun search(query: String): Flow<List<Note>>

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Note>
}
