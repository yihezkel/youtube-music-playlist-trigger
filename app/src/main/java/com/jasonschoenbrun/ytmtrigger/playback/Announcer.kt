package com.jasonschoenbrun.ytmtrigger.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import com.jasonschoenbrun.ytmtrigger.log.Logger
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Says something out loud, once.
 *
 * A phone in a kitchen has no one watching it, so a notification explaining why
 * the music did not play is a notification nobody reads. Saying it is the only
 * way the room finds out - and the room is the audience for everything else
 * this app does.
 *
 * Deliberately spoken on the music stream rather than the alarm stream: this is
 * an explanation, not an emergency, and it should sit at whatever volume the
 * block was going to play at.
 */
object Announcer {

    private const val INIT_TIMEOUT_MS = 5_000L
    private const val SPEAK_TIMEOUT_MS = 15_000L

    /**
     * Speak [text] and wait until it has finished.
     *
     * Waiting matters: the caller starts playing immediately afterwards, and an
     * announcement talked over by the thing it is announcing is worse than none.
     * Never throws and never blocks forever - a missing or broken speech engine
     * costs the announcement, not the playback.
     */
    suspend fun say(context: Context, text: String) {
        val engine = withTimeoutOrNull(INIT_TIMEOUT_MS) { init(context) }
        if (engine == null) {
            Logger.w("Announcer", "No speech engine; skipping the announcement", mapOf("text" to text))
            return
        }
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            Logger.i("Announcer", "Announcing", mapOf("text" to text))
            withTimeoutOrNull(SPEAK_TIMEOUT_MS) { speak(engine, text) }
        } catch (t: Throwable) {
            Logger.w("Announcer", "Announcement failed", t = t)
        } finally {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

    private suspend fun init(context: Context): TextToSpeech? =
        suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (ok) runCatching { tts?.language = Locale.US }
                if (cont.isActive) cont.resume(if (ok) tts else null)
            }
            cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
        }

    private suspend fun speak(tts: TextToSpeech, text: String) =
        suspendCancellableCoroutine { cont ->
            val id = "announce-" + System.nanoTime()
            tts.setOnUtteranceProgressListener(
                object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    @Deprecated("API < 21")
                    override fun onError(utteranceId: String?) {
                        Logger.w("Announcer", "Speech error")
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            )
            val params = android.os.Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
            if (queued != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
}
