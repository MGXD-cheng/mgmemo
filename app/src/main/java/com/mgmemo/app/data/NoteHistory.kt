package com.mgmemo.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "note_history")
data class NoteHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
