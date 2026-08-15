package com.jasonschoenbrun.ytmtrigger.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.jasonschoenbrun.ytmtrigger.BuildConfig
import com.jasonschoenbrun.ytmtrigger.log.Logger

/**
 * Single place that decides whether remote control is usable, so no other
 * code has to guess.
 *
 * Remote control is entirely optional. `app/google-services.json` is not in
 * the repository, so a clean checkout — and every CI release build — compiles
 * without it and must keep working. The Gradle script skips the
 * google-services plugin when the file is absent and sets
 * `BuildConfig.HAS_FIREBASE` accordingly; everything here degrades to "not
 * configured" rather than crashing.
 */
object RemoteGate {

    /** Built with a Firebase config at all. */
    fun isCompiledIn(): Boolean = BuildConfig.HAS_FIREBASE

    /** Firebase actually initialised in this process. */
    fun isInitialised(context: Context): Boolean = try {
        isCompiledIn() && FirebaseApp.getApps(context).isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /** Signed in, so Firestore rules will accept our reads and writes. */
    fun signedInUid(context: Context): String? {
        if (!isInitialised(context)) return null
        return try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (t: Throwable) {
            Logger.w("Remote", "Auth lookup failed", t = t)
            null
        }
    }

    fun isReady(context: Context): Boolean = signedInUid(context) != null

    /**
     * Stable per-device id used as the Firestore document key.
     *
     * ANDROID_ID is scoped to this app's signing key and the device, so it is
     * stable across app updates and unique per phone, which is exactly the
     * lifetime we want for "this is the kitchen phone".
     */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
    } catch (_: Throwable) {
        "unknown-device"
    }

    /** Human-readable reason remote control is off, for the UI. */
    fun statusText(context: Context): String = when {
        !isCompiledIn() ->
            "Not configured. Add app/google-services.json from your Firebase project and rebuild."
        !isInitialised(context) ->
            "Firebase config present but failed to initialise. Check google-services.json."
        signedInUid(context) == null ->
            "Signed out. Sign in with the Google account that owns the Firebase project."
        else -> "Connected."
    }

    fun signOut(context: Context) {
        if (!isInitialised(context)) return
        runCatching { FirebaseAuth.getInstance().signOut() }
            .onFailure { Logger.w("Remote", "Sign-out failed", t = it) }
        Logger.i("Remote", "Signed out of remote control")
    }
}
