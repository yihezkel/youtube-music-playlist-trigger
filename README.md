# YTM Trigger

Android app that wakes a dedicated phone (alarm-clock / kitchen-radio style) at a scheduled time, opens YouTube Music, and plays a randomly chosen playlist — over Bluetooth or the built-in speaker.

**Current version: 0.5.0** — see [Releases](../../releases) for the APK.

## What it does

- **Scheduled triggers**: per-schedule day-of-week + time picker. Multiple schedules supported.
- **Random playlist pick** with a rolling "don't repeat last 3" history.
- **End-to-end launch flow** via deep-link intent + AccessibilityService:
  - Wakes the screen and dismisses the keyguard.
  - Presses Play on the playlist page.
  - Enables shuffle and skips one track so the first song is random.
  - Dismisses Premium upsells / "Try X" / "Maybe later" dialogs automatically.
  - Skips if a phone call is active.
  - Retries up to 3 times with backoff on verification failure.
  - Dumps the YouTube Music window tree to logs on failure.
- **6-hourly background self-test** that silently confirms the whole flow still works.
  Plays an audible TTS + alarm tone ("YouTube Music Bluetooth phone isn't working and needs attention") if the test fails three different ways in a row.
  - Volume is forced to 0 during the test.
  - Skips on Shabat (Friday 17:30 → Saturday 21:30 local) and Yom Tov.
  - Yom Tov dates default to **Israel** (single-day). Toggle "Use Diaspora dates" in Default settings for two-day observance.
  - Every run is persisted as a **structured forensic record** (`filesDir/selftest-history/YYYY-MM.jsonl`): per-strategy attempt, intent dispatch result, accessibility step trace with latencies, and MediaSession / audio-active timelines. Tap **Export last 20 runs (JSON)** on the Self-test screen to share them.
- **Accessibility auto-heal** — if Android ever disables the accessibility service, the app re-enables it automatically (on launch, on boot, before every trigger and self-test, plus a live settings watcher). Requires a one-time `WRITE_SECURE_SETTINGS` grant; see setup below.
- **Manual trigger** from the home screen or a home-screen widget.
- **Setup checklist + diagnostics** with vendor-specific advice (Samsung, Xiaomi, Huawei, Oppo, Vivo, OnePlus, Pixel) for "Sleeping apps" / "Auto-launch" / "Protected apps" systems.
- **Persistent logs** with in-app viewer, level filter, search, copy/share. 14-day retention. Diagnostic "EvalFix" markers let speculative fixes be evaluated and pruned over time.

## Install

Download the APK from the [Releases page](../../releases) and sideload it. The app needs Android 14 (API 34) or newer.

## Build from source

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open in Android Studio (Hedgehog or newer) for editing. Min SDK 34, target SDK 35, Kotlin 2.0 + Jetpack Compose.

Tagged commits matching `v*` automatically build a release APK via GitHub Actions and attach it to the corresponding GitHub Release.

## First-time setup

The home screen shows a red "Setup needed" card whenever a required permission is missing. You'll need to:

1. **Exact alarms** — required for schedules to fire on time.
2. **Notifications** — required for the foreground service that drives playback.
3. **YTM Trigger Helper Accessibility service** — required to press Play, enable shuffle, skip the first track, and dismiss upsells.
4. **Disable battery optimization** for this app.
5. **Vendor-specific** — open **Self-test** for steps to disable your phone's vendor restriction system (Samsung "Sleeping apps", Xiaomi "Auto-start", Huawei "Protected apps", etc.). These cannot be detected from inside the app.
6. **Accessibility auto-heal (recommended)** — grant once over adb so the app can re-enable its own accessibility service if Android turns it off:

   ```sh
   adb shell pm grant com.jasonschoenbrun.ytmtrigger android.permission.WRITE_SECURE_SETTINGS
   ```

   The Self-test screen shows whether this is active and offers a "Copy adb command" button.
7. **MediaSession probe (recommended)** — grant notification-listener access once so the app can read YouTube Music's actual playback state instead of inferring it from `AudioManager` (which reports *any* audio as playing):

   ```sh
   adb shell cmd notification allow_listener com.jasonschoenbrun.ytmtrigger/com.jasonschoenbrun.ytmtrigger.playback.MediaSessionListenerService
   ```

   Unlike accessibility, this one cannot be self-healed: since Android 8 the `enabled_notification_listeners` secure setting is only a compatibility write-back, and the API that really grants access is `@SystemApi`. You can also enable it manually under Settings → Notifications → Device & app notifications.

Then add at least one schedule from the **Schedules** screen.

## Configuration

- **Default settings** screen sets the playlists, volume, shuffle, skip-first-track, and self-test options that new schedules inherit.
- **Self-test** screen shows the live setup checklist, the last self-test success / failure / skip timestamps, a "Run now" button, and "Export last 20 runs (JSON)" for full per-run forensics. Tap "Stop alert" if the failure alarm is sounding.
- **Logs** screen shows everything the app has done; level filter and free-text search are at the top, with copy-to-clipboard and share-as-file in the toolbar.

## Remote control (optional)

Lets you change playlists and schedules, read the phone's logs, and trigger playback from any browser — useful when the YT Music phone is a dedicated device you don't want to pick up.

**It is entirely optional.** Without `app/google-services.json` the project builds and the app behaves exactly as before; the home screen just shows "Remote control — not configured".

### Setup

1. Create a Firebase project, then **Add app → Android** with package `com.jasonschoenbrun.ytmtrigger`.
2. **Register your signing certificate's SHA-1.** Google sign-in on Android is bound to the app's signing key, and without this the `oauth_client` array in `google-services.json` stays empty however many times you re-download it. Get the fingerprint:

   ```sh
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
   ```

   On Windows, `keytool` ships with Android Studio's JDK:

   ```powershell
   & "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v `
       -keystore "$env:USERPROFILE\.android\debug.keystore" `
       -alias androiddebugkey -storepass android
   ```

   Copy the `SHA1:` value into **Project settings → Your apps → Add fingerprint**. The debug key covers release builds too, because `app/build.gradle.kts` signs release with the debug config.
3. **Authentication → Get started → Sign-in method → enable Google**, and set a support email.
4. **Firestore Database → Create database** (production mode).
5. **Re-download `google-services.json`** into `app/` — only now does it contain the OAuth clients. Verify before rebuilding:

   ```sh
   grep -c client_id app/google-services.json     # must be > 0
   ```

   If it is still empty, steps 2 and 3 did not both take effect. The file is gitignored on purpose.
6. Deploy rules and the console:

   ```sh
   npm i -g firebase-tools
   firebase login
   firebase use --add            # pick your project
   firebase deploy --only firestore,hosting
   ```

   This deploys the security rules, the composite index the command queue needs, and the console. `firebase deploy` prints the Hosting URL, e.g. `https://<project-id>.web.app`.

7. Rebuild the app, open it, and tap **Sign in with Google** on the Remote control card using the account that owns the project.
8. Open the Hosting URL on any phone, sign in with the same account, and your device appears.

### Troubleshooting

| Symptom | Cause |
|---|---|
| App says "Not configured" | No `google-services.json` at build time, or you rebuilt before adding it. Rebuild after copying the file in. |
| Sign-in fails, log shows `No default_web_client_id` | `oauth_client` is empty — SHA-1 not registered (step 2) or Google provider not enabled (step 3). Re-download after doing both. |
| Sign-in dialog opens then immediately cancels | SHA-1 mismatch: the installed APK was signed with a different key than the fingerprint you registered. |
| Console shows "No devices yet" | The phone hasn't checked in. Open the app and tap **Sync now**. |
| `PERMISSION_DENIED` in the console | Firestore rules not deployed, or you signed into the web page with a different Google account than the phone. |

For tagged releases, add the file as a repository secret so CI keeps remote support:

```sh
base64 -w0 app/google-services.json      # paste into secret GOOGLE_SERVICES_JSON
```

### What you can do remotely

- Edit default playlists, volume, shuffle/skip, self-test options and full schedules
- **Play now**, **Run self-test**, **Request logs**
- Read uploaded logs in the browser
- See device health: accessibility, MediaSession probe, battery exemption, last self-test result

Logs are also **uploaded automatically whenever a self-test fails**, so a breakage shows up in the console without you touching the phone.

### Latency

The phone applies remote changes on its next check-in: at app start, after every trigger and self-test, and on a 15-minute background poll. Commands are therefore not instant. True push would need FCM plus a server component to send it (Cloud Functions requires the paid Blaze plan), which isn't worth it for a personal device — so this deliberately trades a few minutes of latency for zero cost and no backend.

### Privacy

Everything lives under `users/{your-uid}/` in your own Firebase project, and the security rules reject any request whose UID doesn't match. Uploaded logs can contain playlist IDs and device diagnostics; nothing is public.

## Privacy / data

Everything is local. No network calls beyond what YouTube Music itself makes. Logs stay on the device until you share them.

## License

MIT
