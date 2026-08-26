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
import com.jasonschoenbrun.ytmtrigger.playback.PlaybackTriggerService
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

    // Set only for a continuous schedule. Null means "play this episode and
    // stop", which is what every non-continuous schedule relies on.
    private var queueScheduleId: String? = null
    private var queueIndex: Int = 0

    // Identify what is playing so an interrupted episode can be resumed. Feed
    // and title come from the caller; the position is read off the player.
    private var currentFeedUrl: String? = null
    private var currentAudioUrl: String? = null
    private var currentTitle: String = ""
    // Set when an episode ends of its own accord, so the stop that follows is
    // not mistaken for an interruption worth resuming.
    private var finishedNaturally = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPlayback(); return START_NOT_STICKY }
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Podcast"
        queueScheduleId = intent?.getStringExtra(EXTRA_QUEUE_SCHEDULE)
        queueIndex = intent?.getIntExtra(EXTRA_QUEUE_INDEX, 0) ?: 0
        currentFeedUrl = intent?.getStringExtra(EXTRA_FEED_URL)
        val startAtSec = intent?.getLongExtra(EXTRA_START_AT_SEC, 0L) ?: 0L
        if (url.isNullOrBlank()) {
            Logger.e("PodcastPlayer", "No audio URL; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(title))
        play(url, title, startAtSec)
        return START_NOT_STICKY
    }

    private fun play(url: String, title: String, startAtSec: Long = 0L) {
        stopPlayback(keepService = true)
        currentAudioUrl = url
        currentTitle = title
        finishedNaturally = false
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
                // Seek before starting so a resumed episode does not blurt out
                // its opening seconds before jumping.
                if (startAtSec > 0) {
                    runCatching { it.seekTo((startAtSec * 1000L).toInt()) }
                    Logger.i("PodcastPlayer", "Resuming", mapOf(
                        "title" to title, "atSec" to startAtSec.toString(),
                    ))
                }
                it.start()
                playing.set(true)
                publishState(PlaybackState.STATE_PLAYING)
                Logger.i("PodcastPlayer", "Playing", mapOf(
                    "title" to title,
                    "durationMs" to it.duration.toString(),
                    "startAtSec" to startAtSec.toString(),
                ))
            }
            setOnCompletionListener {
                Logger.i("PodcastPlayer", "Episode finished", mapOf("title" to title))
                // Heard to the end, so there is nothing to come back to, and a
                // serialised show can move on to the next part. Both only on a
                // real finish: an episode cut off by a block's stop must be
                // resumed, not skipped past.
                finishedNaturally = true
                currentFeedUrl?.let { feed ->
                    PodcastResume.clear(this@PodcastPlayerService, feed)
                    currentAudioUrl?.let { audio ->
                        PodcastSequence.markPlayed(this@PodcastPlayerService, feed, audio)
                    }
                }
                // A continuous schedule carries on to its next entry. The
                // follow-on goes back through PlaybackTriggerService rather
                // than being played here, so it re-runs the Shabat gate, the
                // in-call check and the failure reporting - a queue must not
                // become a way to bypass any of them.
                val next = queueScheduleId
                if (next != null) {
                    Logger.i("PodcastPlayer", "Advancing queue", mapOf(
                        "scheduleId" to next, "nextIndex" to (queueIndex + 1).toString(),
                    ))
                    stopPlayback(keepService = true)
                    PlaybackTriggerService.startQueued(this@PodcastPlayerService, next, queueIndex + 1)
                    stopSelf()
                } else {
                    stopPlayback()
                }
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
        val was = playing.getAndSet(false)
        // Capture the position before releasing the player: an episode cut off
        // by a block's stop time should be resumable next time.
        if (was && !finishedNaturally) {
            val feed = currentFeedUrl
            val audio = currentAudioUrl
            val posSec = runCatching { (player?.currentPosition ?: 0) / 1000L }.getOrDefault(0L)
            val durSec = runCatching { (player?.duration ?: 0) / 1000L }.getOrDefault(0L)
            if (feed != null && audio != null) {
                PodcastResume.save(this, feed, audio, currentTitle, posSec, durSec)
            }
        }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        publishState(PlaybackState.STATE_STOPPED)
        session?.isActive = false
        runCatching { session?.release() }
        session = null
        if (was) Logger.i("PodcastPlayer", "Stopped")
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
        const val EXTRA_QUEUE_SCHEDULE = "queueScheduleId"
        const val EXTRA_QUEUE_INDEX = "queueIndex"
        const val EXTRA_FEED_URL = "feedUrl"
        const val EXTRA_START_AT_SEC = "startAtSec"
        const val ACTION_STOP = "com.jasonschoenbrun.ytmtrigger.PODCAST_STOP"

        private val playing = AtomicBoolean(false)

        /** True while an episode is actually playing. */
        fun isPlaying(): Boolean = playing.get()

        /**
         * @param queueScheduleId set only for a continuous schedule, so the end
         *   of this episode starts that schedule's next entry.
         */
        fun start(
            context: Context,
            audioUrl: String,
            title: String,
            queueScheduleId: String? = null,
            queueIndex: Int = 0,
            feedUrl: String? = null,
            startAtSec: Long = 0L,
        ) {
            val i = Intent(context, PodcastPlayerService::class.java).apply {
                putExtra(EXTRA_URL, audioUrl)
                putExtra(EXTRA_TITLE, title)
                if (feedUrl != null) putExtra(EXTRA_FEED_URL, feedUrl)
                if (startAtSec > 0) putExtra(EXTRA_START_AT_SEC, startAtSec)
                if (queueScheduleId != null) {
                    putExtra(EXTRA_QUEUE_SCHEDULE, queueScheduleId)
                    putExtra(EXTRA_QUEUE_INDEX, queueIndex)
                }
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            if (!playing.get()) return
            Logger.i("PodcastPlayer", "Stop requested")
            val i = Intent(context, PodcastPlayerService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }
    }
}

/** Foreground service type reference, mirroring PlaybackTriggerService. */
@Suppress("unused")
private val PODCAST_FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
    if (Build.VERSION.SDK_INT >= 34) 0 else 0
