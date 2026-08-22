package com.jasonschoenbrun.ytmtrigger.podcast

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jasonschoenbrun.ytmtrigger.YtmApp
import com.jasonschoenbrun.ytmtrigger.log.Logger
import com.jasonschoenbrun.ytmtrigger.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a podcast episode straight from its audio URL.
 *
 * Podcasts are played here rather than handed to Spotify because Spotify
 * ignores both media keys and `playFromUri` from unapproved callers (verified
 * on device: the session stays in `STATE_NONE`), leaving only UI automation,
 * and because its Web API needs a Premium account while the show's RSS feed
 * needs nothing at all.
 *
 * A [MediaSession] is published so everything already built keeps working
 * unchanged: the per-schedule stop time, the pre-Shabat mute and the console's
 * Stop button all act through media sessions.
 */
class PodcastPlayerService : Service() {

    private var player: MediaPlayer? = null
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPlayback(); return START_NOT_STICKY }
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Podcast"
        if (url.isNullOrBlank()) {
            Logger.e("PodcastPlayer", "No audio URL; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(title))
        play(url, title)
        return START_NOT_STICKY
    }

    private fun play(url: String, title: String) {
        stopPlayback(keepService = true)
        val ms = MediaSession(this, "YTMTriggerPodcast").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPause() { pause() }
                override fun onStop() { stopPlayback() }
                override fun onPlay() { resume() }
            })
            isActive = true
        }
        session = ms
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnPreparedListener {
                it.start()
                playing.set(true)
                publishState(PlaybackState.STATE_PLAYING)
                Logger.i("PodcastPlayer", "Playing", mapOf(
                    "title" to title,
                    "durationMs" to it.duration.toString(),
                ))
            }
            setOnCompletionListener {
                Logger.i("PodcastPlayer", "Episode finished", mapOf("title" to title))
                stopPlayback()
            }
            setOnErrorListener { _, what, extra ->
                Logger.e("PodcastPlayer", "Playback error", mapOf(
                    "what" to what.toString(), "extra" to extra.toString(), "title" to title,
                ))
                stopPlayback()
                true
            }
            setDataSource(url)
            prepareAsync()
        }
        Logger.i("PodcastPlayer", "Preparing", mapOf("title" to title, "url" to url))
    }

    private fun publishState(state: Int) {
        val pos = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE
                )
                .setState(state, pos, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build()
        )
    }

    private fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        playing.set(false)
        publishState(PlaybackState.STATE_PAUSED)
        Logger.i("PodcastPlayer", "Paused")
    }

    private fun resume() {
        runCatching { player?.start() }
        playing.set(true)
        publishState(PlaybackState.STATE_PLAYING)
    }

    private fun stopPlayback(keepService: Boolean = false) {
        playing.set(false)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        publishState(PlaybackState.STATE_STOPPED)
        session?.isActive = false
        runCatching { session?.release() }
        session = null
        if (!keepService) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopPlayback(keepService = true)
        super.onDestroy()
    }

    private fun notification(title: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, YtmApp.CH_TRIGGER)
            .setContentTitle("Playing podcast")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1004
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val ACTION_STOP = "com.jasonschoenbrun.ytmtrigger.PODCAST_STOP"

        private val playing = AtomicBoolean(false)

        /** True while an episode is actually playing. */
        fun isPlaying(): Boolean = playing.get()

        fun start(context: Context, audioUrl: String, title: String) {
            val i = Intent(context, PodcastPlayerService::class.java).apply {
                putExtra(EXTRA_URL, audioUrl)
                putExtra(EXTRA_TITLE, title)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            if (!playing.get()) return
            val i = Intent(context, PodcastPlayerService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }
}

/** Foreground service type reference, mirroring PlaybackTriggerService. */
@Suppress("unused")
private val PODCAST_FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
    if (Build.VERSION.SDK_INT >= 34) 0 else 0
