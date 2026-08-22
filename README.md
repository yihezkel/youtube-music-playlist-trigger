# YTM Trigger

Android app that wakes a dedicated phone (alarm-clock / kitchen-radio style) at a scheduled time, opens YouTube Music, and plays a randomly chosen playlist — over Bluetooth or the built-in speaker.

**Current version: 0.6.0** — see [Releases](../../releases) for the APK.

## What it does

- **Scheduled triggers**: per-schedule day-of-week + time picker. Multiple schedules supported.
- **Plays playlists, songs and podcasts.** An entry can be:
  - a YouTube Music **playlist**, album or radio mix (`…/playlist?list=…`, or a `watch` link
    that carries a `list=`);
  - a single YouTube Music **song** (`…/watch?v=…`) — these deep-link straight into playback,
    so they never need the Play button pressed;
  - a **podcast**, given either an RSS feed URL or a Spotify show link.
  `www.youtube.com` links are rewritten to `music.youtube.com` automatically — as-is they
  open the YouTube app, not YouTube Music.
- **Podcasts play from the show's RSS feed**, which the app plays itself in a small
  foreground service with its own media session (so the stop time, the pre-Shabat mute and
  the console's Stop button all work on it unchanged). A Spotify show link is resolved to
  that feed automatically via the show's title. Feeds are cached for 12 hours, and a stale
  cache is preferred to failing a trigger.
  - **Random episode by default**, or newest — a per-schedule switch, shown only when the
    schedule actually contains a podcast.
  - Spotify's own API is deliberately not used: it requires a **Premium** account and its
    public page exposes only 12 episodes, whereas the feed for the same show carries the
    full back catalogue (384 episodes, in the case this was built against).
  - Spotify **originals and exclusives** have no public feed and cannot be played.
- **Optional stop time** per schedule — leave it blank (the default) to play until stopped,
  or set a clock time to pause automatically. A stop time at or before the start time means
  the next day, so an overnight schedule stops in the morning. The console has a **Stop**
  button for the same thing on demand.
- **Random playlist pick** with a rolling "don't repeat last 3" history.
- **Named playlists** — anywhere a playlist URL is accepted (app and web console) you can
  add a name in brackets after it:
  `https://music.youtube.com/playlist?list=PLKNLlLCOCLas&si=txZZ [Quora]`.
  Lists then show the name with the URL underneath. The name is cosmetic — playback always
  uses the playlist ID — so labelled and bare URLs are interchangeable.
- **Never plays on Shabat or Yom Tov.** Every path that can start playback — scheduled
  alarm, home-screen widget, in-app **Play now**, and the remote console — is blocked.
  A scheduled trigger can never override this; a manual one can, but only after a
  confirmation dialog. Blocked triggers aren't counted as failures, and the schedule is
  still re-armed for next time.
  - Shabat = from 40 minutes before sunset on Friday to 42 minutes after sunset on Saturday;
    Yom Tov comes from a built-in table (2026-2030), **Israel** single-day by default
    ("Use Diaspora dates" in Default settings switches it). Sunset is computed locally
    from your coordinates — no network, no location permission — so the window tracks the
    seasons. Coordinates and both offsets are editable in **Default settings**, which also
    shows the resulting window ("Next Shabat: Fri 21 Aug, 18:38 → Sat 22 Aug, 19:59").
  - A schedule that *would* have fired inside one of those windows in the coming week is
    flagged on its card in the Schedules screen.
  - **15 minutes before each window opens** the app stops anything playing and sets the
    media volume to 0, so nothing can make noise once it begins — including playback it
    didn't start, or YouTube Music autoplaying on from a queue. The volume is not restored
    afterwards; the next scheduled trigger sets it from its own schedule. (If a schedule
    has no volume configured and there is no default, it will stay muted — the level it
    muted from is written to the log.)
- **End-to-end launch flow** via deep-link intent + AccessibilityService:
  - Wakes the screen and dismisses the keyguard.
  - Presses Play on the playlist page.
  - Enables shuffle and skips one track so the first song is random.
  - Dismisses Premium upsells / "Try X" / "Maybe later" dialogs automatically.
  - Skips if a phone call is active.
  - Retries up to 3 times with backoff on verification failure.
  - Falls back through three independent launch strategies — the `https` deep
    link, launcher-then-deep-link, and the `vnd.youtube.music://` scheme, which
    enters through a different activity than the other two.
  - Dumps the YouTube Music window tree to logs on failure.
- **Automatic ad skipping** — presses YouTube Music's skip control as soon as a skippable
  ad allows it, anywhere in the queue. Matters once playlists contain tracks you didn't
  upload yourself. Matching is deliberately narrow so the next-track button can never be
  mistaken for it; unmatched ads log their on-screen candidates so the matcher can be
  improved from real data. Toggle in **Default settings**.
- **6-hourly background self-test** that silently confirms the whole flow still works.
  Plays an audible TTS + alarm tone ("YouTube Music Bluetooth phone isn't working and needs attention") if the test fails three different ways in a row.
  - Volume is forced to 0 during the test.
  - Skipped automatically on Shabat and Yom Tov. A manual **Run now** asks for
    confirmation first rather than being silently blocked.  - Every run is persisted as a **structured forensic record** (`filesDir/selftest-history/YYYY-MM.jsonl`): per-strategy attempt, real intent dispatch result, accessibility step trace with latencies, and MediaSession / audio-active timelines. Tap **Export last 20 runs (JSON)** on the Self-test screen to share them.
- **Failures in the last week** — a 7-day bar chart plus one sentence per failure, at the
  bottom of the Self-test screen and of the web console, or "No failures in the last week!"
  when there were none.
- **Accessibility resilience** — if Android disables the accessibility service, the app re-enables it automatically (on launch, on boot, before every trigger and self-test, plus a live settings watcher). If a self-test fails with the service having done nothing at all, the app restarts its own process, which is the only recovery observed for that state. Requires a one-time `WRITE_SECURE_SETTINGS` grant; see setup below.
- **Remote control** — change playlists and schedules, trigger playback, and read the phone's logs from a browser. Optional; see below.
- **Manual trigger** from a **Play now** button on each schedule, or from a home-screen widget.
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
3. **YTM Trigger Helper Accessibility service** — required to press Play, enable shuffle, skip the first track, skip ads, and dismiss upsells.
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

- **Default settings** screen sets the playlists, volume, shuffle, skip-first-track, ad-skipping, and self-test options that new schedules inherit.
- **Playlist URLs** may carry an optional name in brackets, e.g.
  `…?list=PLKNLlLCOCLas [Quora]`. Accepted everywhere a URL is — both here and in the web
  console — and shown instead of the raw URL.
- **Schedules** screen lists every schedule with its next fire time, an enable switch, and a **Play now** button.
- **Days of week** run Sunday-first (`Su M Tu W Th F ש`), as the week is counted in Israel.
  The stored values remain ISO (Monday = 1 … Sunday = 7); only the display changes.
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

- Edit default playlists, volume, shuffle/skip, ad-skipping, self-test options and full schedules
- **Play now**, **Stop**, **Run self-test**, **Request logs**
- Read uploaded logs in the browser
- See device health: accessibility, MediaSession probe, battery exemption, last self-test result
- See **failures in the last week** (chart + one sentence each) at the bottom of the page

Logs are also **uploaded automatically whenever a self-test fails**, so a breakage shows up in the console without you touching the phone.

### Latency

The phone applies remote changes on its next check-in: at app start, after every trigger and self-test, and on a 15-minute background poll. Commands are therefore not instant. True push would need FCM plus a server component to send it (Cloud Functions requires the paid Blaze plan), which isn't worth it for a personal device — so this deliberately trades a few minutes of latency for zero cost and no backend.

### Pulling diagnostics without the phone

`tools/fetch-remote-logs.mjs` downloads device state, config and uploaded logs
(including the structured self-test run records) from Firestore to
`.remote-logs/`, so the phone's health can be reviewed when it isn't present.

The Firebase CLI has no document-read command, and its stored OAuth token
belongs to a human login that `firebase logout` revokes, so this uses a
service account — the supported mechanism for unattended reads.

1. Firebase console → **Project settings → Service accounts → Generate new private key**.
2. Save it as `tools/service-account.json` (gitignored — it grants admin access
   to the project, so never commit it), or point `YTM_SERVICE_ACCOUNT` at it.
3. Run it:

   ```sh
   cd tools && npm install
   node fetch-remote-logs.mjs            # add --days N to widen the window
   ```

It prints a per-device summary — last check-in, last self-test success/failure,
permission health — and writes `.remote-logs/<deviceId>/` plus a machine-readable
`summary.json`.

### Privacy

Everything lives under `users/{your-uid}/` in your own Firebase project, and the security rules reject any request whose UID doesn't match. Uploaded logs can contain playlist IDs and device diagnostics; nothing is public.

## Privacy / data

Playback, logs and schedules are entirely local: nothing leaves the device, and logs stay
there until you share them (14-day retention).

The **remote control feature is the one exception**, and only if you set it up. It syncs
your settings, schedules, health and logs to *your own* Firebase project under
`users/{your-uid}/`, where the security rules reject any request whose UID doesn't match.
Without `app/google-services.json` none of that code has anywhere to talk to.

## License

MIT
