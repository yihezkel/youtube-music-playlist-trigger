package com.jasonschoenbrun.ytmtrigger.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jasonschoenbrun.ytmtrigger.diag.A11yActionResult
import com.jasonschoenbrun.ytmtrigger.diag.A11yStep
import com.jasonschoenbrun.ytmtrigger.log.EvalFix
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Description of a post-launch action to perform once YT Music has started
 * playing the queued playlist. Set by [PlaybackTriggerService] before issuing
 * the launch intent; consumed by the [YtmAccessibilityService] when YT Music's
 * window appears.
 */
data class PostLaunchAction(
    val enableShuffle: Boolean,
    val skipFirstTrack: Boolean,
    val expectedPlaylistId: String,
    val queuedAtMs: Long,
    /** Caller-supplied id for cross-referencing with a SelfTestRunRecord. */
    val runId: String? = null,
)

class YtmAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null
    /** C-fix-1: serialise runAction so a debounced event can never start a
     *  second worker while the first is still pressing buttons. */
    private val actionMutex = Mutex()
    /** C-fix-2: timestamp of the last window-state event we acted on. */
    @Volatile private var lastEventActedMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running.set(true)
        instance.set(this)
        sawEventSinceConnect.set(false)
        connectedAtMs.set(System.currentTimeMillis())
        Logger.i("A11y", "Service connected")
    }

    override fun onDestroy() {
        running.set(false)
        instance.compareAndSet(this, null)
        scope.cancel()
        Logger.i("A11y", "Service destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() {
        Logger.w("A11y", "onInterrupt")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Liveness marker: proves AccessibilityManagerService is actually
        // delivering to us. See isResponsive().
        sawEventSinceConnect.set(true)
        // D-fix-3: log every window-state change at debug, even when there's
        // no pending action, so we can correlate logs with what the user saw.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Logger.d("A11y", "WindowStateChanged", mapOf(
                "pkg" to (event.packageName?.toString() ?: ""),
                "cls" to (event.className?.toString() ?: ""),
                "text" to event.text.joinToString("|").take(120),
            ))
        }
        if (pending.get() == null) return
        // B-fix-1: only events from YT Music ever start the worker.
        if (event.packageName?.toString() != YT_MUSIC_PKG) return
        // C-fix-2: debounce - ignore events that arrive within 250ms of the
        // last one we acted on. YT Music emits a flurry of state changes as it
        // navigates into a playlist.
        val now = System.currentTimeMillis()
        if (now - lastEventActedMs < EVENT_DEBOUNCE_MS) {
            Logger.d("A11y", "Event debounced", mapOf("sinceLastMs" to (now - lastEventActedMs).toString()))
            return
        }
        // Ensure exactly one worker is running.
        if (workerJob?.isActive == true) return
        val action = pending.getAndSet(null) ?: return
        lastEventActedMs = now
        Logger.i("A11y", "Triggered by event", mapOf(
            "type" to AccessibilityEvent.eventTypeToString(event.eventType),
            "shuffle" to action.enableShuffle.toString(),
            "skip" to action.skipFirstTrack.toString(),
        ))
        workerJob = scope.launch { actionMutex.withLock { runAction(action) } }
    }

    private suspend fun runAction(action: PostLaunchAction) {
        val trace = ActionTrace(
            runId = action.runId,
            startMs = System.currentTimeMillis(),
        )
        trace.started = true
        trace.foregroundPkgAtStart = foregroundPackage()
        var completedCleanly = false
        try {
            // Give YT Music a moment to render the playlist page
            delay(1500)

            // STEP 0: dismiss any blocking dialog (Premium upsell, "Try X",
            // restore-playback prompt, system permission dialog, etc.)
            trace.step("DismissDialog:pre-play", ok = true) {
                dismissUnwantedDialogs(label = "pre-play")
                true
            }

            // B-fix-2: every step entry verifies that YT Music is still the
            // foreground window. If it isn't, try to restore it (B-fix-4).
            val foregroundOk = trace.step("EnsureForeground:pre-press-play") {
                ensureForegroundYtm(stepLabel = "pre-press-play")
            }
            if (!foregroundOk) {
                Logger.w("A11y", "Aborting action: YT Music not foreground after recovery")
                return
            }

            // STEP 1: tap the playlist's Play button. The deep-link intent loads
            // the playlist page but does not auto-play, so we must press Play.
            var played = trace.step("PressPlay") { pressPlaylistPlay() }
            Logger.i("A11y", "Press play step", mapOf("ok" to played.toString()))
            if (!played) {
                // Maybe a dialog appeared between page-load and now. Try again.
                delay(2000)
                trace.step("DismissDialog:play-retry", ok = true) {
                    dismissUnwantedDialogs(label = "play-retry")
                    true
                }
                trace.step("EnsureForeground:play-retry") { ensureForegroundYtm(stepLabel = "play-retry") }
                played = trace.step("PressPlayRetry") { pressPlaylistPlay() }
                Logger.i("A11y", "Press play retry", mapOf("ok" to played.toString()))
            }
            if (!played) dumpWindow("press-play-failed")

            // STEP 2: wait for actual playback to begin. We need this before
            // enabling shuffle / skipping, otherwise the next-track button is
            // disabled and the shuffle toggle applies to a stale mini-player.
            val playing = trace.step("WaitForActivePlayback") { waitForActivePlayback(timeoutMs = 12_000) }
            Logger.i("A11y", "Active playback wait", mapOf("playing" to playing.toString()))
            if (!playing) {
                // A dialog may be silently blocking playback. Sweep again.
                trace.step("DismissDialog:post-play-wait", ok = true) {
                    dismissUnwantedDialogs(label = "post-play-wait")
                    true
                }
                val playing2 = trace.step("WaitForActivePlaybackRetry") { waitForActivePlayback(timeoutMs = 4_000) }
                Logger.i("A11y", "Active playback wait retry", mapOf("playing" to playing2.toString()))
                if (!playing2) dumpWindow("playback-not-started")
            }

            if (action.enableShuffle) {
                delay(800)
                val fgOk = trace.step("EnsureForeground:pre-shuffle") { ensureForegroundYtm(stepLabel = "pre-shuffle") }
                if (!fgOk) {
                    Logger.w("A11y", "Skipping shuffle: YT Music not foreground")
                } else {
                    val ok = trace.step("Shuffle") { enableShuffle() }
                    Logger.i("A11y", "Shuffle step done", mapOf("ok" to ok.toString()))
                    if (!ok) {
                        delay(1500)
                        trace.step("EnsureForeground:shuffle-retry") { ensureForegroundYtm(stepLabel = "shuffle-retry") }
                        val ok2 = trace.step("ShuffleRetry") { enableShuffle() }
                        Logger.i("A11y", "Shuffle retry", mapOf("ok" to ok2.toString()))
                        if (!ok2) dumpWindow("shuffle-failed")
                    }
                }
            }
            if (action.skipFirstTrack) {
                delay(800)
                val fgOk = trace.step("EnsureForeground:pre-skip") { ensureForegroundYtm(stepLabel = "pre-skip") }
                if (!fgOk) {
                    Logger.w("A11y", "Skipping skip: YT Music not foreground")
                } else {
                    val ok = trace.step("Skip") { skipNext() }
                    Logger.i("A11y", "Skip step done", mapOf("ok" to ok.toString()))
                    if (!ok) {
                        delay(1200)
                        trace.step("EnsureForeground:skip-retry") { ensureForegroundYtm(stepLabel = "skip-retry") }
                        val ok2 = trace.step("SkipRetry") { skipNext() }
                        Logger.i("A11y", "Skip retry", mapOf("ok" to ok2.toString()))
                        if (!ok2) dumpWindow("skip-failed")
                    }
                }
            }
            completedCleanly = true
        } catch (t: Throwable) {
            trace.errorMessage = t.message ?: t.javaClass.simpleName
            Logger.e("A11y", "Action error", t = t)
        } finally {
            lastActionResult.set(trace.toResult(completed = completedCleanly))
            actionDone.complete()
        }
    }

    /**
     * Find and tap the playlist's "Play" button. The playlist detail page has
     * two relevant elements (per layout dumps): a header Play button with
     * contentDescription="Play", and a floating_action_button. We try both.
     */
    private suspend fun pressPlaylistPlay(): Boolean {
        val root = rootOrNull() ?: return logFalse("Play: no root")
        // Prefer the floating action button by id (least ambiguous)
        val fab = findById(root, "floating_action_button")
        if (fab != null) {
            Logger.d("A11y", "Play: using FAB", mapOf("desc" to (fab.contentDescription?.toString() ?: "")))
            return performClickOrTap(fab)
        }
        // Fallback: a Button node whose contentDescription equals exactly "Play"
        // (avoid "Play video" used by mini-player or expanded player).
        val byExactDesc = findFirst(root) { n ->
            val d = n.contentDescription?.toString()
            d != null && d.equals("Play", ignoreCase = true) && n.isVisibleToUser
        }
        if (byExactDesc != null) {
            Logger.d("A11y", "Play: using exact-desc button")
            return performClickOrTap(byExactDesc)
        }
        return logFalse("Play: no Play button found")
    }

    private suspend fun waitForActivePlayback(timeoutMs: Long): Boolean {
        val am = getSystemService(android.media.AudioManager::class.java)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (am?.isMusicActive == true) return true
            delay(400)
        }
        return false
    }

    /** Try to enable shuffle. Returns true on success. */
    private suspend fun enableShuffle(): Boolean {
        // Strategy A: from playlist detail page header — header has a node with
        // contentDescription "Shuffle play". Tapping it begins shuffled playback.
        // (Risk: this may restart playback. Prefer Strategy B if mini-player exists.)
        // Strategy B: in mini-player or expanded player, find shuffle toggle by
        // resource-id and content description state.

        // First try: expand mini-player so player controls (incl. shuffle) are visible.
        val rootBefore = rootOrNull() ?: return logFalse("Shuffle: no root")
        val miniPlayer = findById(rootBefore, "mini_player")
        val expandedAlready = findById(rootBefore, "playback_queue_shuffle_button_view") != null
        if (!expandedAlready && miniPlayer != null) {
            Logger.d("A11y", "Tapping mini player to expand")
            performClickOrTap(miniPlayer)
            // Wait for player view
            waitFor("playback_queue_shuffle_button_view", 5000)
        }

        val shuffleNode = findShuffleNode() ?: run {
            // Strategy A fallback: tap the playlist header "Shuffle play" / "Shuffle"
            val root = rootOrNull() ?: return logFalse("Shuffle: no root after expand")
            val byDesc = findByContentDescContains(root, "shuffle")
            if (byDesc != null) {
                Logger.d("A11y", "Shuffle: using contentDesc fallback", mapOf("desc" to (byDesc.contentDescription?.toString() ?: "")))
                val ok = performClickOrTap(byDesc)
                return ok
            }
            return logFalse("Shuffle: no shuffle node found")
        }

        val desc = shuffleNode.contentDescription?.toString().orEmpty()
        Logger.d("A11y", "Shuffle node found", mapOf("desc" to desc))
        // If it already says "on", we're done.
        if (desc.contains("on", ignoreCase = true) && !desc.contains("off", ignoreCase = true)) {
            Logger.i("A11y", "Shuffle already on")
            return true
        }
        val tapped = performClickOrTap(shuffleNode)
        if (!tapped) return logFalse("Shuffle: tap failed")
        // Verify
        delay(700)
        val after = findShuffleNode()?.contentDescription?.toString().orEmpty()
        Logger.d("A11y", "Shuffle verify", mapOf("descAfter" to after))
        return after.contains("on", ignoreCase = true) && !after.contains("off", ignoreCase = true)
    }

    private suspend fun skipNext(): Boolean {
        val root = rootOrNull() ?: return logFalse("Skip: no root")
        // Capture current title for optional verification.
        val titleBefore = currentTitle(root)
        Logger.d("A11y", "Skip: title before", mapOf("title" to (titleBefore ?: "")))
        val nextBtn = findById(root, "player_control_next_button")
            ?: findByContentDescContains(root, "next track")
        if (nextBtn == null) return logFalse("Skip: next button not found")
        val ok = performClickOrTap(nextBtn)
        if (!ok) return logFalse("Skip: tap failed")
        // Only verify when we actually have a baseline title to compare against.
        // Without a baseline we'd just be polling empty-string vs empty-string,
        // wasting time and producing a misleading "title did not change" warning.
        if (titleBefore.isNullOrEmpty()) {
            return true
        }
        // Verify by polling for title change.
        repeat(6) {
            delay(400)
            val now = currentTitle(rootOrNull() ?: return@repeat)
            if (!now.isNullOrEmpty() && now != titleBefore) {
                Logger.i("A11y", "Skip verified", mapOf("from" to titleBefore, "to" to now))
                return true
            }
        }
        Logger.w("A11y", "Skip: title did not change")
        return true // Tap dispatched; treat as best-effort success
    }

    private fun currentTitle(root: AccessibilityNodeInfo): String? {
        // Try only known now-playing title ids. Avoid the generic "title" id
        // because many unrelated nodes (playlist header, suggestions, etc.) end
        // in /title and produce false positives like "Add a song".
        return findById(root, "mini_player_title")?.text?.toString()
            ?: findById(root, "swipeable_mini_player_title")?.text?.toString()
            ?: findById(root, "player_title")?.text?.toString()
            ?: findById(root, "track_title")?.text?.toString()
            ?: findById(root, "now_playing_title")?.text?.toString()
    }

    private fun findShuffleNode(): AccessibilityNodeInfo? {
        val root = rootOrNull() ?: return null
        return findById(root, "playback_queue_shuffle_button_view")
    }

    private suspend fun waitFor(idShortName: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        // G-fix-1: exponential backoff 150ms -> 300ms -> 600ms -> 1s, so we
        // poll aggressively early on but don't burn the CPU late in the wait.
        var sleep = 150L
        while (System.currentTimeMillis() < deadline) {
            val n = rootOrNull()?.let { findById(it, idShortName) }
            if (n != null) return n
            delay(sleep)
            sleep = (sleep * 2).coerceAtMost(1_000L)
        }
        return null
    }

    /**
     * Sweep visible UI for known "dismiss this overlay" buttons and tap the
     * first match. Handles Premium upsell, "Try YouTube Music Premium", "No
     * thanks", "Skip", "Maybe later", "Not now", "Dismiss", "Close" close-X
     * buttons, etc.
     *
     * Patterns are tried as exact contentDescription/text match first, then
     * substring. We intentionally do NOT match generic "Cancel" or "OK" since
     * those can fire on legitimate dialogs we don't want to dismiss.
     */
    private suspend fun dismissUnwantedDialogs(label: String) {
        val patterns = listOf(
            "no thanks", "not now", "maybe later", "skip trial", "skip",
            "dismiss", "no, thanks", "remind me later", "later",
        )
        // Close-X buttons usually have contentDescription "Close" exactly.
        val closeButtonDescs = listOf("close", "close dialog")

        var dismissed = 0
        repeat(3) { iteration ->
            val root = rootOrNull() ?: return
            val matchOrNull: AccessibilityNodeInfo? = run {
                // Highest priority: exact contentDescription match for our patterns
                for (p in patterns) {
                    val n = findFirst(root) { node ->
                        val cd = node.contentDescription?.toString()?.lowercase()?.trim()
                        val tx = node.text?.toString()?.lowercase()?.trim()
                        node.isVisibleToUser && (cd == p || tx == p)
                    }
                    if (n != null) return@run n
                }
                // Then: contentDescription/text contains
                for (p in patterns) {
                    val n = findFirst(root) { node ->
                        val cd = node.contentDescription?.toString()?.lowercase()
                        val tx = node.text?.toString()?.lowercase()
                        node.isVisibleToUser && ((cd != null && cd.contains(p)) || (tx != null && tx.contains(p)))
                    }
                    if (n != null) return@run n
                }
                // Then: close-X button (only when there is a clear modal)
                for (p in closeButtonDescs) {
                    val n = findFirst(root) { node ->
                        val cd = node.contentDescription?.toString()?.lowercase()?.trim()
                        node.isVisibleToUser && cd == p && node.isClickable
                    }
                    if (n != null) return@run n
                }
                null
            }
            val match = matchOrNull ?: return@repeat
            Logger.i("A11y", "Dismissing dialog", mapOf(
                "label" to label,
                "iter" to iteration.toString(),
                "desc" to (match.contentDescription?.toString() ?: ""),
                "text" to (match.text?.toString() ?: ""),
            ))
            performClickOrTap(match)
            dismissed++
            delay(700)
        }
        if (dismissed > 0) {
            Logger.i("A11y", "Dialog sweep done", mapOf("label" to label, "dismissed" to dismissed.toString()))
        }
    }

    /**
     * Walk the active window and emit a compact representation of every node
     * with id/text/contentDesc/bounds/clickable. Used after a step fails so
     * Copilot has the layout to fix selectors.
     */
    private fun dumpWindow(reason: String) {
        try {
            val root = rootOrNull() ?: run {
                Logger.w("A11y", "Window dump: no root", mapOf("reason" to reason))
                return
            }
            // D-fix-2: include package, window class, and window id so we know
            // which surface the dump came from. Window id is stable per window
            // session, useful for diff'ing dumps across two failures.
            val pkg = root.packageName?.toString() ?: ""
            val cls = root.className?.toString() ?: ""
            val winId = try { root.windowId } catch (_: Throwable) { -1 }
            Logger.w("A11y", "==== BEGIN WINDOW DUMP ====", mapOf(
                "reason" to reason,
                "pkg" to pkg,
                "cls" to cls,
                "windowId" to winId.toString(),
            ))
            val sb = StringBuilder()
            dumpNode(root, depth = 0, out = sb)
            // Logger entries are line-oriented; chunk to avoid logcat truncation.
            val full = sb.toString()
            full.lineSequence().chunked(40).forEachIndexed { i, group ->
                Logger.w("A11y", "WindowDump#${reason}-$i", mapOf("lines" to group.joinToString("\n")))
            }
            Logger.w("A11y", "==== END WINDOW DUMP ====", mapOf("reason" to reason))
        } catch (t: Throwable) {
            Logger.e("A11y", "dumpWindow failed", t = t)
        }
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, depth: Int, out: StringBuilder) {
        n ?: return
        val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        val parts = mutableListOf<String>()
        n.viewIdResourceName?.let { parts += "id=${it.substringAfterLast(':')}" }
        n.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { parts += "desc=\"${it.take(80)}\"" }
        n.text?.toString()?.takeIf { it.isNotEmpty() }?.let { parts += "text=\"${it.take(80)}\"" }
        n.className?.toString()?.let { parts += "cls=${it.substringAfterLast('.')}" }
        if (n.isClickable) parts += "click"
        if (!n.isVisibleToUser) parts += "hidden"
        parts += "b=${r.left},${r.top},${r.right},${r.bottom}"
        out.append("  ".repeat(depth.coerceAtMost(20)))
        out.append(parts.joinToString(" "))
        out.append('\n')
        for (i in 0 until n.childCount) dumpNode(n.getChild(i), depth + 1, out)
    }

    private fun rootOrNull(): AccessibilityNodeInfo? = try { rootInActiveWindow } catch (_: Throwable) { null }

    /**
     * Identify what package owns the current foreground window.
     * Used by [ensureForegroundYtm] (B-fix-2/3/4) and the L-fix-1
     * systemui-bouncer fail-fast.
     */
    private fun foregroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_: Throwable) { null }

    /**
     * B-fix-2 / B-fix-3 / B-fix-4: verify YT Music is foreground, wait briefly
     * with a 1s grace period in case the window is still transitioning, and
     * if it still isn't, try once to bring YT Music back via its launcher
     * intent. Logs an [EvalFix] entry every time a recovery is attempted.
     *
     * Also L-fix-1: if the foreground is the SystemUI bouncer (lock screen
     * password prompt), fail fast and do not try to recover — the user is
     * actively interacting with the device.
     */
    private suspend fun ensureForegroundYtm(stepLabel: String): Boolean {
        val initial = foregroundPackage()
        Logger.d("A11y", "Foreground at step", mapOf("step" to stepLabel, "pkg" to (initial ?: "")))
        if (initial == YT_MUSIC_PKG) return true
        // L-fix-1: SystemUI bouncer means user is unlocking. Don't fight it.
        if (initial == SYSTEMUI_PKG) {
            val root = rootOrNull()
            val hasBouncer = root?.let { findFirst(it) { n ->
                val id = n.viewIdResourceName ?: return@findFirst false
                id.contains("alternate_bouncer", ignoreCase = true) ||
                    id.contains("bouncer", ignoreCase = true) ||
                    id.contains("password", ignoreCase = true)
            } } != null
            EvalFix.once("L-fix-1-systemuiBouncer", success = !hasBouncer, mapOf(
                "step" to stepLabel,
                "hasBouncerId" to hasBouncer.toString(),
            ))
            if (hasBouncer) {
                Logger.w("A11y", "Aborting: systemui bouncer is foreground")
                return false
            }
        }
        // B-fix-3: 1s grace period — the window may just be transitioning.
        delay(FOREGROUND_GRACE_MS)
        val afterGrace = foregroundPackage()
        if (afterGrace == YT_MUSIC_PKG) {
            EvalFix.once("B-fix-3-graceRecover", success = true, mapOf(
                "step" to stepLabel, "wasPkg" to (initial ?: ""),
            ))
            return true
        }
        // B-fix-4: try to bring YT Music back to foreground via its launcher.
        EvalFix.start("B-fix-4-foregroundRestore", mapOf(
            "step" to stepLabel, "displacingPkg" to (afterGrace ?: ""),
        ))
        Logger.w("A11y", "YT Music displaced; attempting restore", mapOf(
            "step" to stepLabel, "displacingPkg" to (afterGrace ?: ""),
        ))
        val pm = packageManager
        val launch = pm.getLaunchIntentForPackage(YT_MUSIC_PKG)
        if (launch == null) {
            EvalFix.end("B-fix-4-foregroundRestore", success = false, mapOf("reason" to "noLauncher"))
            return false
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(launch)
        } catch (t: Throwable) {
            EvalFix.end("B-fix-4-foregroundRestore", success = false, mapOf("err" to (t.message ?: "")))
            return false
        }
        // Wait up to 2.5s for YT Music to claim foreground again.
        val deadline = System.currentTimeMillis() + FOREGROUND_RESTORE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            if (foregroundPackage() == YT_MUSIC_PKG) {
                EvalFix.end("B-fix-4-foregroundRestore", success = true)
                return true
            }
        }
        EvalFix.end("B-fix-4-foregroundRestore", success = false, mapOf("reason" to "timeout"))
        return false
    }

    private fun findById(root: AccessibilityNodeInfo, shortId: String): AccessibilityNodeInfo? {
        val full = "$YT_MUSIC_PKG:id/$shortId"
        val matches = try { root.findAccessibilityNodeInfosByViewId(full) } catch (_: Throwable) { null }
        return matches?.firstOrNull { it.isVisibleToUser } ?: matches?.firstOrNull()
    }

    private fun findByContentDescContains(root: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val lower = needle.lowercase()
        val out = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { n ->
            val cd = n.contentDescription?.toString()?.lowercase()
            if (cd != null && cd.contains(lower)) out.add(n)
        }
        // Prefer visible & clickable
        return out.firstOrNull { it.isVisibleToUser && it.isClickable }
            ?: out.firstOrNull { it.isVisibleToUser }
            ?: out.firstOrNull()
    }

    private fun walk(node: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Unit) {
        node ?: return
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), visit)
    }

    private fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { n -> if (found == null && predicate(n)) found = n }
        return found
    }

    private suspend fun performClickOrTap(node: AccessibilityNodeInfo): Boolean {
        // Walk to nearest clickable ancestor
        var clickable: AccessibilityNodeInfo? = node
        var hops = 0
        while (clickable != null && !clickable.isClickable && hops < 6) {
            clickable = clickable.parent
            hops++
        }
        val target = clickable ?: node
        val rect = Rect().also { target.getBoundsInScreen(it) }
        Logger.d("A11y", "Click target", mapOf(
            "id" to (target.viewIdResourceName ?: ""),
            "desc" to (target.contentDescription?.toString() ?: ""),
            "bounds" to "${rect.left},${rect.top},${rect.right},${rect.bottom}",
            "clickable" to target.isClickable.toString(),
        ))
        if (target.isClickable) {
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) return true
        }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return dispatchTap(rect.exactCenterX(), rect.exactCenterY())
    }

    private suspend fun dispatchTap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val gd = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gd, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Logger.d("A11y", "Tap completed", mapOf("x" to x.toInt().toString(), "y" to y.toInt().toString()))
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(g: GestureDescription?) {
                Logger.w("A11y", "Tap cancelled", mapOf("x" to x.toInt().toString(), "y" to y.toInt().toString()))
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    private fun logFalse(msg: String): Boolean {
        Logger.w("A11y", msg)
        return false
    }

    companion object {
        const val YT_MUSIC_PKG = "com.google.android.apps.youtube.music"
        const val SYSTEMUI_PKG = "com.android.systemui"
        /** C-fix-2: ignore window events within 250ms of the last one acted on. */
        const val EVENT_DEBOUNCE_MS = 250L
        /** B-fix-3: wait this long before declaring YT Music actually displaced. */
        const val FOREGROUND_GRACE_MS = 1_000L
        /** B-fix-4: wait this long for YT Music to come back after restore. */
        const val FOREGROUND_RESTORE_TIMEOUT_MS = 2_500L

        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
        private val instance = AtomicReference<YtmAccessibilityService?>(null)
        private val sawEventSinceConnect = java.util.concurrent.atomic.AtomicBoolean(false)
        private val connectedAtMs = java.util.concurrent.atomic.AtomicLong(0L)
        private val pending = AtomicReference<PostLaunchAction?>(null)
        private val actionDone = OneShot()
        private val lastActionResult = AtomicReference<A11yActionResult?>(null)
        private val lastQueuedAction = AtomicReference<PostLaunchAction?>(null)

        /** Grace window after binding, before "no events yet" means anything. */
        const val RESPONSIVE_GRACE_MS = 15_000L

        fun isRunning(): Boolean = running.get()

        /**
         * Whether the service is not just bound but actually *usable*.
         *
         * [isRunning] only reports that `onServiceConnected` fired. That has
         * been observed to be true while the service received no events at
         * all — seen once after the installer dropped the service and
         * [A11yPermissionEnforcer] re-enabled it by writing secure settings:
         * `dumpsys accessibility` listed the service as bound with the right
         * `eventTypes`, yet no `TYPE_WINDOW_STATE_CHANGED` arrived for 90s
         * and every self-test strategy timed out with `a11yStarted=false`.
         * Reporting [isRunning] alone therefore shows a green health check
         * for a service that cannot do its job.
         *
         * Having received at least one event since connecting is the cheap,
         * decisive proof that delivery works. A healthy service sees events
         * as soon as any window changes — including this app's own screens
         * opening — so by the time a checklist is rendered it has fired.
         * Within [RESPONSIVE_GRACE_MS] of binding we report healthy, because
         * "no window has changed yet" is not evidence of failure.
         *
         * Deliberately does no binder work, so it is safe to call from the UI
         * thread; [canReadActiveWindow] is the stronger, blocking variant.
         */
        fun isResponsive(): Boolean {
            if (!isRunning()) return false
            if (sawEventSinceConnect.get()) return true
            return System.currentTimeMillis() - connectedAtMs.get() < RESPONSIVE_GRACE_MS
        }

        /**
         * Stronger liveness probe: can we actually read the active window?
         *
         * Performs a blocking binder round-trip. **Never call this from the
         * main thread** — when one of this app's own windows is foreground,
         * the interrogation is served by this process's main thread, so
         * calling it there blocks until the ~5s timeout and returns null.
         */
        fun canReadActiveWindow(): Boolean = currentForegroundPackage() != null

        /**
         * The package that owns the current foreground window, as seen by the
         * accessibility service.
         *
         * This is the authoritative source and needs no extra permission: the
         * service is already bound and `rootInActiveWindow` reflects whatever
         * the user is looking at. The UsageStats-based alternative silently
         * returns nothing unless PACKAGE_USAGE_STATS is granted, and that is
         * an appop no ordinary app can grant itself (`AppOpsManager.setMode`
         * requires signature-level MANAGE_APP_OPS_MODES).
         *
         * Returns null when the service isn't bound, and also — briefly —
         * during window transitions, so a single null is not proof of death.
         */
        fun currentForegroundPackage(): String? = try {
            instance.get()?.rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) { null }

        fun queueAction(action: PostLaunchAction) {
            pending.set(action)
            actionDone.reset()
            lastActionResult.set(null)
            lastQueuedAction.set(action)
            Logger.i("A11y", "Action queued (static)", mapOf(
                "playlistId" to action.expectedPlaylistId,
                "runId" to (action.runId ?: ""),
            ))
        }

        /**
         * Legacy boolean API — returns true if the action coroutine ran to
         * completion within [timeoutMs]. Kept for callers that don't need the
         * structured trace; new callers should prefer [awaitActionResult].
         */
        suspend fun awaitActionComplete(timeoutMs: Long): Boolean {
            return withTimeoutOrNull(timeoutMs) {
                actionDone.await()
                true
            } ?: false
        }

        /**
         * Wait up to [timeoutMs] for the queued [PostLaunchAction] to run.
         * On timeout (the A11y service never fired, e.g. YT Music never
         * came to foreground) returns a synthetic [A11yActionResult] with
         * `started=false` so the caller can attach SOMETHING to the
         * SelfTestRunRecord.
         */
        suspend fun awaitActionResult(timeoutMs: Long): A11yActionResult {
            val ok = awaitActionComplete(timeoutMs)
            val result = lastActionResult.get()
            if (result != null) return result
            val queued = lastQueuedAction.get()
            val startMs = queued?.queuedAtMs ?: System.currentTimeMillis()
            return A11yActionResult(
                completed = ok,
                started = false,
                totalDurationMs = System.currentTimeMillis() - startMs,
                steps = emptyList(),
                foregroundPkgAtStart = null,
                errorMessage = if (ok) null else "Action coroutine never started (no YT Music window event within ${timeoutMs}ms)",
            )
        }
    }
}

/**
 * Mutable per-action trace collected by [YtmAccessibilityService.runAction].
 * Each call to [step] records a single entry. The final result is published
 * via [YtmAccessibilityService.Companion.lastActionResult] before
 * [YtmAccessibilityService.Companion.actionDone] is signalled.
 */
private class ActionTrace(
    val runId: String?,
    val startMs: Long,
) {
    private val steps = ArrayList<A11yStep>(16)
    @Volatile var foregroundPkgAtStart: String? = null
    @Volatile var errorMessage: String? = null
    @Volatile var started: Boolean = false

    /**
     * Time [body], record its outcome, and return whatever the body returned.
     * If [body] throws, the step is recorded as `ok=false` and the throwable
     * propagates so the caller's outer try/catch can handle it.
     */
    suspend inline fun step(name: String, crossinline body: suspend () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        var ok = false
        try {
            ok = body()
            return ok
        } finally {
            steps += A11yStep(
                name = name,
                startedAtMs = start,
                endedAtMs = System.currentTimeMillis(),
                ok = ok,
            )
        }
    }

    /**
     * Variant for steps that don't naturally return a Boolean (e.g. fire-and-
     * forget dismiss-dialog sweeps). [ok] is recorded as-is.
     */
    suspend inline fun step(name: String, ok: Boolean, crossinline body: suspend () -> Unit) {
        val start = System.currentTimeMillis()
        try {
            body()
        } finally {
            steps += A11yStep(
                name = name,
                startedAtMs = start,
                endedAtMs = System.currentTimeMillis(),
                ok = ok,
            )
        }
    }

    fun toResult(completed: Boolean): A11yActionResult = A11yActionResult(
        completed = completed,
        started = started,
        totalDurationMs = System.currentTimeMillis() - startMs,
        steps = steps.toList(),
        foregroundPkgAtStart = foregroundPkgAtStart,
        errorMessage = errorMessage,
    )
}

/** Single-shot completion latch that can be reset between uses. */
private class OneShot {
    private var deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Synchronized fun reset() {
        if (deferred.isCompleted) deferred = kotlinx.coroutines.CompletableDeferred()
    }
    @Synchronized fun complete() { deferred.complete(Unit) }
    suspend fun await() { deferred.await() }
}
