package app.murmur

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** A home-screen button that starts and stops a meeting recording. */
class MeetingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, MeetingWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context) = RemoteViews(context.packageName, R.layout.widget_meeting).apply {
            val recording = Meetings.recording != null
            setTextViewText(R.id.widget_label, context.getString(if (recording) R.string.widget_stop else R.string.widget_record))
            setImageViewResource(R.id.widget_icon, if (recording) R.drawable.ic_stop else R.drawable.ic_mic)
            setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 1, RecordActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
    }
}
