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

    /** 读取某篇笔记的全部历史（备份用） */
    @Query("SELECT * FROM note_history WHERE noteId = :noteId ORDER BY timestamp ASC")
    suspend fun listForNote(noteId: Long): List<NoteHistory>

    /** 批量写入历史（导入用） */
    @Insert
    suspend fun insertAll(items: List<NoteHistory>)

    /** 清空全部历史（覆盖导入时使用） */
    @Query("DELETE FROM note_history")
    suspend fun deleteAll()
}
