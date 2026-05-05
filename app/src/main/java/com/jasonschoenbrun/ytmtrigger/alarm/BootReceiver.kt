package com.jasonschoenbrun.ytmtrigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jasonschoenbrun.ytmtrigger.data.ScheduleRepository
import com.jasonschoenbrun.ytmtrigger.log.Logger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logger.i("BootReceiver", "Received", mapOf("action" to (intent.action ?: "?")))
        val repo = ScheduleRepository.get(context)
        AlarmScheduler.rescheduleAll(context, repo.all())
    }
}
