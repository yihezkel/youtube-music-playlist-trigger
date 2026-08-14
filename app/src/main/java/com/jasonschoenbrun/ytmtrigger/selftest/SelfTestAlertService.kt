package com.jasonschoenbrun.ytmtrigger.selftest

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.jasonschoenbrun.ytmtrigger.R
import com.jasonschoenbrun.ytmtrigger.YtmApp
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.ui.MainActivity
import java.util.Locale

/**
 * Foreground service that audibly alerts the user when the self-test failed:
 * speaks [R.string.self_test_alert_text] every [SPEAK_INTERVAL_MS] and plays
 * the default alarm ringtone in a loop for up to [MAX_RUN_MS], whichever
 * comes first. Stoppable from the notification action "Dismiss" or from the
 * UI.
 *
 * The service intentionally bumps STREAM_ALARM volume to a fixed "medium"
 * level so the alert is audible without being eardrum-piercing, and restores
 * the previous level on stop.
 */
class SelfTestAlertService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ringtone: Ringtone? = null
    private var savedAlarmVolume: Int = -1
    private var savedRingerMode: Int = AudioManager.RINGER_MODE_NORMAL
    private var stopRunnable: Runnable? = null
    private var speakRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logger.i("SelfTestAlert", "Stop action received")
            stopSelfAndCleanup()
            return START_NOT_STICKY
        }
        Logger.i("SelfTestAlert", "Starting alert service")
        startInForeground()
        bumpAlarmVolume()
        startRingtone()
        initTtsAndSpeak()
        // Auto-stop after MAX_RUN_MS so the alert never plays forever.
        stopRunnable = Runnable {
            Logger.i("SelfTestAlert", "Auto-stop after ${MAX_RUN_MS / 1000}s")
            stopSelfAndCleanup()
        }.also { handler.postDelayed(it, MAX_RUN_MS) }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SelfTestAlertService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = getString(R.string.self_test_alert_text)
        val n: Notification = NotificationCompat.Builder(this, YtmApp.CH_SELFTEST_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("YTM Trigger self-test FAILED")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun bumpAlarmVolume() {
        val am = getSystemService(AudioManager::class.java) ?: return
        savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        savedRingerMode = am.ringerMode
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = (max * ALARM_VOLUME_PERCENT / 100).coerceAtLeast(1)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            Logger.i("SelfTestAlert", "Alarm volume set", mapOf("target" to target.toString(), "max" to max.toString()))
        } catch (t: Throwable) {
            Logger.w("SelfTestAlert", "Could not set alarm volume", t = t)
        }
    }

    private fun restoreAlarmVolume() {
        if (savedAlarmVolume < 0) return
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            Logger.i("SelfTestAlert", "Alarm volume restored", mapOf("restored" to savedAlarmVolume.toString()))
        } catch (t: Throwable) {
            Logger.w("SelfTestAlert", "Could not restore alarm volume", t = t)
        }
    }

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            ringtone = RingtoneManager.getRingtone(this, uri).apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
            Logger.i("SelfTestAlert", "Ringtone started")
        } catch (t: Throwable) {
            Logger.w("SelfTestAlert", "Could not start ringtone", t = t)
        }
    }

    private fun initTtsAndSpeak() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Logger.w("SelfTestAlert", "TTS init failed", mapOf("status" to status.toString()))
                return@TextToSpeech
            }
            try { tts?.language = Locale.US } catch (_: Throwable) {}
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                @Deprecated("API < 21") override fun onError(utteranceId: String?) {
                    Logger.w("SelfTestAlert", "TTS error", mapOf("utt" to (utteranceId ?: "")))
                }
            })
            speakLoop()
        }
    }

    private fun speakLoop() {
        val text = getString(R.string.self_test_alert_text)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        tts?.setAudioAttributes(attrs)
        speakRunnable = object : Runnable {
            override fun run() {
                val params = android.os.Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "selftest-utt")
                handler.postDelayed(this, SPEAK_INTERVAL_MS)
            }
        }.also { handler.post(it) }
    }

    private fun stopSelfAndCleanup() {
        speakRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable?.let { handler.removeCallbacks(it) }
        speakRunnable = null
        stopRunnable = null
        try { ringtone?.stop() } catch (_: Throwable) {}
        ringtone = null
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        restoreAlarmVolume()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {}
        stopSelf()
    }

    override fun onDestroy() {
        Logger.i("SelfTestAlert", "onDestroy")
        stopSelfAndCleanup()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.jasonschoenbrun.ytmtrigger.SELFTEST_ALERT_STOP"
        /** Maximum time the alert plays; the user can dismiss earlier. */
        const val MAX_RUN_MS: Long = 2 * 60 * 1000
        /** TTS speak interval (between sentences). */
        const val SPEAK_INTERVAL_MS: Long = 8_000
        /** Percentage of STREAM_ALARM max — "medium" per the spec. */
        const val ALARM_VOLUME_PERCENT: Int = 40

        fun start(context: Context) {
            val intent = Intent(context, SelfTestAlertService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SelfTestAlertService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
