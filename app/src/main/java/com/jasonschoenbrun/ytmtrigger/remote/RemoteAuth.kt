package com.jasonschoenbrun.ytmtrigger.remote

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.jasonschoenbrun.ytmtrigger.log.Logger
import kotlinx.coroutines.tasks.await

/**
 * One-time Google sign-in on the YT Music phone, so Firestore rules can pin
 * every document to the owner's UID.
 *
 * Uses Credential Manager rather than the long-deprecated `GoogleSignIn` API.
 * The OAuth web client id is read from the resource the google-services
 * plugin generates (`default_web_client_id`) and is looked up **by name at
 * runtime**: referencing `R.string.default_web_client_id` directly would not
 * compile in a checkout without google-services.json, which is precisely the
 * configuration CI builds.
 */
object RemoteAuth {

    /**
     * Launch the Google account picker and sign in to Firebase.
     *
     * [activityContext] must be an Activity — Credential Manager needs to show
     * UI. Returns the signed-in UID, or null with the reason logged.
     */
    suspend fun signIn(activityContext: Context): String? {
        if (!RemoteGate.isInitialised(activityContext)) {
            Logger.w("Remote", "Sign-in requested but Firebase is not configured")
            return null
        }
        val webClientId = webClientId(activityContext)
        if (webClientId == null) {
            Logger.e("Remote", "No default_web_client_id resource; enable Google sign-in in Firebase and re-download google-services.json")
            return null
        }
        return try {
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(activityContext)
                .getCredential(activityContext, request)
            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                Logger.e("Remote", "Unexpected credential type", mapOf("type" to credential.type))
                return null
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val firebaseCred = GoogleAuthProvider.getCredential(idToken, null)
            val result = FirebaseAuth.getInstance().signInWithCredential(firebaseCred).await()
            val uid = result.user?.uid
            Logger.i("Remote", "Signed in for remote control", mapOf(
                "uid" to (uid ?: ""),
                "email" to (result.user?.email ?: ""),
            ))
            uid
        } catch (t: Throwable) {
            Logger.e("Remote", "Google sign-in failed", t = t)
            null
        }
    }

    private fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier(
            "default_web_client_id", "string", context.packageName,
        )
        return if (id == 0) null else runCatching { context.getString(id) }.getOrNull()
    }
}
