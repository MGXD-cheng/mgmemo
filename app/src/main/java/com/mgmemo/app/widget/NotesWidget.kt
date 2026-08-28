package com.mgmemo.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mgmemo.app.MainActivity
import com.mgmemo.app.R
import com.mgmemo.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 主屏小组件：显示最近 3 条笔记，点击进入编辑，底部快速新建。
 */
class NotesWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val notes = AppDatabase.getInstance(context).noteDao().getRecent(3)
                val views = RemoteViews(context.packageName, R.layout.widget_layout)

                for (i in 0 until 3) {
                    val textId = noteTextId(i)
                    if (i < notes.size) {
                        val note = notes[i]
                        views.setTextViewText(textId, note.title.ifBlank { "无标题" })
                        views.setOnClickPendingIntent(textId, noteIntent(context, note.id))
                    } else {
                        views.setTextViewText(textId, if (notes.isEmpty()) "暂无笔记" else " ")
                        views.setOnClickPendingIntent(textId, newNoteIntent(context))
                    }
                }

                views.setOnClickPendingIntent(
                    R.id.widget_new_note,
                    newNoteIntent(context)
                )

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                // 静默失败，避免小组件崩溃
            } finally {
                pending.finish()
            }
        }
    }

    private fun noteTextId(index: Int): Int = when (index) {
        0 -> R.id.widget_note1
        1 -> R.id.widget_note2
        else -> R.id.widget_note3
    }

    private fun noteIntent(context: Context, noteId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("note_id", noteId)
        }
        return PendingIntent.getActivity(
            context,
            (noteId % 100000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun newNoteIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("new_note", true)
        }
        return PendingIntent.getActivity(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        fun updateWidget(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, NotesWidget::class.java)
            )
            if (ids.isNotEmpty()) {
                NotesWidget().onUpdate(context, manager, ids)
            }
        }
    }
}