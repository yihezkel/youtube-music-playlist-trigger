package com.jasonschoenbrun.ytmtrigger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jasonschoenbrun.ytmtrigger.R
import com.jasonschoenbrun.ytmtrigger.alarm.AlarmScheduler
import com.jasonschoenbrun.ytmtrigger.alarm.TriggerReceiver
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackTriggerService

class TriggerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_trigger)
            val intent = Intent(context, TriggerReceiver::class.java).apply {
                action = ACTION_WIDGET_TRIGGER
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, PlaybackTriggerService.MANUAL_DEFAULT_ID)
                putExtra(AlarmScheduler.EXTRA_MANUAL, true)
            }
            val pi = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_trigger_button, pi)
            mgr.updateAppWidget(id, views)
        }
        Logger.d("Widget", "onUpdate", mapOf("ids" to ids.joinToString(",")))
    }

    companion object {
        const val ACTION_WIDGET_TRIGGER = "com.jasonschoenbrun.ytmtrigger.WIDGET_TRIGGER"

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val cn = ComponentName(context, TriggerWidgetProvider::class.java)
            val ids = mgr.getAppWidgetIds(cn)
            if (ids.isNotEmpty()) {
                val intent = Intent(context, TriggerWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
