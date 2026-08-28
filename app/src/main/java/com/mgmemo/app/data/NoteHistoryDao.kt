package com.mgmemo.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteHistoryDao {

    @Query("SELECT * FROM note_history WHERE noteId = :noteId ORDER BY timestamp DESC")
    fun observeForNote(noteId: Long): Flow<List<NoteHistory>>

    @Insert
    suspend fun insert(history: NoteHistory)

    @Query("SELECT COUNT(*) FROM note_history WHERE noteId = :noteId")
    suspend fun countForNote(noteId: Long): Int

    @Query("DELETE FROM note_history WHERE noteId = :noteId")
    suspend fun clearForNote(noteId: Long)
}
