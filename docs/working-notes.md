# Working notes

Context for anyone — human or agent — picking this project up. Written after a
long session that touched nearly every part of it, and kept in the repo because
most of it is expensive to rediscover.

Read `README.md` first for what the app does. This file is about *working on*
it: how to build and test, which facts were established by measurement rather
than assumption, and what is deliberately left undone.

---

## 1. What this is, physically

A dedicated Android phone (Motorola `moto g power 5G (2024)`, `ZY22KJFNJM`,
Android 15 — it was 14 when these notes were first written) sits in the house
and plays podcasts and music on a schedule, over Bluetooth or its own speaker.
It is not the owner's personal phone. It is normally plugged in and reachable
over adb, and it has **no lock screen set** — that matters, see §6.

The owner is Jason (`@yischoen`), in Israel. Schedules are built around a
household: kids 07:30–08:00 and 16:00–19:30, his wife Sarah 08:00–15:50, a
15-year-old until 21:30, and Shabat/Yom Tov handled properly. Eight blocks,
twenty block/day queues, defined in `tools/schedule-blocks.mjs`.

---

## 2. Build, install, test

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --max-workers=1 --no-daemon --console=plain

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

A build takes about a minute. **Confirm `BUILD SUCCESSFUL` before installing** —
a build still running will let you install a stale APK and then puzzle over why
a change did nothing. That happened.

### Testing a schedule on the device

`tools/test-schedule.mjs` pushes a temporary schedule called `ZZ QueueTest`,
restarts the app twice so it syncs, and reports what got armed:

```powershell
cd tools
node test-schedule.mjs <mode> <minutesUntilStart> <blockLengthMinutes>
node test-schedule.mjs clean     # ALWAYS finish with this
```

Modes: `guard`, `resume`, `modes`, `wrap`, `chain`, `autostop`, `musicend`,
`trailer`, `alephbeta`, `abfeed`, `locksub`, `locksub2`, `clean`.

**The double restart is deliberate.** A single restart can re-arm alarms from
the *pre-sync* schedules — a real race, not superstition.

**Always run `clean`, then restart the app twice and confirm no
`id=zz-queue-test` remains armed.** A test schedule left behind fires daily.
This was got wrong once and left an alarm set for 01:14 the next morning.

**Installing the APK reliably kills the accessibility service** — see §11. It
comes back bound and never delivers another event, so YouTube Music will not
start until the phone is rebooted. Podcasts are unaffected. After any install,
verify with a cold start:

```powershell
& $adb shell "input keyevent KEYCODE_WAKEUP"   # REQUIRED - see below
& $adb shell "am force-stop com.google.android.apps.youtube.music"
& $adb shell "am start -n com.google.android.apps.youtube.music/com.google.android.apps.youtube.music.activities.MusicActivity"
# then count A11y: WindowStateChanged in today's app log - it must increase
```

**Check that in the app's own log, not in `dumpsys alarm`.** An alarm entry
there is tagged with the *action* — `tag=*walarm*:…ytmtrigger.SELFTEST_FIRE` —
and never carries the schedule id, so `dumpsys alarm | grep zz-queue-test`
matches nothing whether or not the test schedule exists. It reads like a clean
result and is worthless. The app's `Alarm: Scheduled id=… name=…` lines are the
real evidence; failing that, read the schedules out of the device config
(`files/datastore/ytmtrigger.preferences_pb`, see §9).

Reading the app's own log is usually more useful than logcat:

```powershell
& $adb shell "run-as com.jasonschoenbrun.ytmtrigger tail -200 files/logs/`$(date +%Y-%m-%d).log"
```

---

## 3. Screenshots — read before touching the device UI

Viewing an image inlines it into the session transcript as base64
**permanently**. Deleting the PNG afterwards does not remove it, and every later
request carries it again until the attachment cap is hit and the session starts
compacting. This is what forced a session restart.

Use the helper, which downscales to JPEG:

```powershell
powershell -NoProfile -File "$env:USERPROFILE\.copilot\Get-Screen.ps1" -Path shot.jpg
powershell -NoProfile -File "$env:USERPROFILE\.copilot\Get-Screen.ps1" -Path tile.jpg -Crop "0,1600,1080,420" -Scale 0.7
```

**Mute the phone before driving the UI, and look before you tap.** Every card in
the Schedules list carries a large **Play now** button, and a blind
`input tap` aimed at "the first schedule" lands on it. That happened at 02:42:
YouTube Music started at full volume, in the house, in the middle of the night.
`cmd audio set-volume 3 0` first, screenshot to find the target, tap the card's
*title*, and restore the volume afterwards. Note also that the screen sleeps
after two minutes, so a tap sequence built from an older screenshot can land
somewhere else entirely — re-capture after any pause.

Measured: raw `adb exec-out screencap` **1062 KB**, helper **7 KB**, cropped
**3 KB** — still perfectly legible for reading a UI.

If a session is already bloated, `~/.copilot/Strip-SessionImages.ps1` replaces
inlined payloads with 1×1 PNGs; then `/resume`. Retrying alone cannot shrink an
oversized request, despite the "transient" wording.

Two adb traps, both hit for real:

- PowerShell's `>` mangles binary. `adb exec-out screencap -p > file.png` can
  produce a PNG that `System.Drawing` rejects with a misleading "Out of memory".
  Capture to the device and `adb pull` instead.
- `adb shell "... | tail"` on a multi-megabyte log throws `ENOBUFS` through
  Node's `execSync`. Use `tail -N` device-side and pass `maxBuffer`.
- **PowerShell hands unrecognised operators to the command as arguments, and
  curl bundles short flags.** `curl.exe -s $url -join "\`n" | Select-String …`
  does not join anything: curl reads `-join` as `-j -o in` and writes the body
  to a file called **`in`**, leaving stdout empty. The visible symptom was a
  match count of 0, which reads like "the feed is broken" rather than "the
  output went somewhere else" — and the stray file then got committed. Put the
  output in a variable first, then operate on it.

Also: **PowerShell `.Replace()` on multi-line strings fails silently** because
of CRLF. It caused at least four phantom "bugs" in one day. Use the `edit` tool.

---

## 4. Restoring the device after testing

Anything changed for a test must be put back, and verified rather than assumed:

```powershell
& $adb shell "locksettings clear --old 2580"          # if a PIN was set
& $adb shell "locksettings set-disabled true"
& $adb shell "settings put secure lock_screen_lock_after_timeout 120000"
& $adb shell "settings put system screen_off_timeout 120000"
& $adb shell "cmd audio set-volume 3 15"              # media volume; see below
```

**Restoring the volume is not what §4 used to say.** Sending `keyevent 24` back
does nothing when the screen is off: with no active media session the key is
absorbed rather than applied to `STREAM_MUSIC`, so fifteen presses changed
nothing (they did not leak into the ring stream either — that was checked).
Worse, a test schedule sets the volume to **0**, and index 0 on `STREAM_MUSIC`
also sets the stream's **mute flag**, which `cmd media_session volume --set`
will not clear — it reports "will set volume" and silently leaves
`Muted: true`. The command that works is `cmd audio set-volume 3 15`.

Note also that Android tracks media volume **per output device**: `dumpsys
audio` showed `STREAM_MUSIC ... speaker: 0, bt_a2dp: 10` at the same moment.
"The volume" always means the volume for the device currently in use.

The phone's ring and notification streams are normally muted at 0 — that is its
resting state, not something a test broke. Leave them alone.

Check: `locksettings get-disabled` → `true`,
`dumpsys trust | grep deviceLocked` → `0`, `Password quality: {0=0}`,
`dumpsys audio | grep -A3 '^- STREAM_MUSIC:'` → `Muted: false`.

The phone disconnected once mid-test with a PIN still set, which needed the
owner to unlock it by hand. Restore early rather than late.

---

## 5. Architecture, in the order things happen

| Stage | File | Notes |
|---|---|---|
| Alarm fires | `alarm/AlarmScheduler.kt` | exact alarms; `startsAfter` schedules are never clock-armed |
| Anchor maths | `alarm/ScheduleTimes.kt` | clock / sunset / Shabat-Yom-Tov-end |
| Calendar | `calendar/HebrewCalendarChecker.kt` | `endOf(day) = sunset + endOffsetMin`; also the Shabat gate |
| Trigger | `playback/PlaybackTriggerService.kt` | the spine — read this first |
| Entry choice | `playback/PlaylistPicker.kt` | `at()` walks a queue; wraps unless the block ends with its queue |
| Podcasts | `podcast/PodcastPlayerService.kt`, `PodcastCatalog.kt` | we play these ourselves |
| Music | `playback/YtmLauncher.kt`, `accessibility/YtmAccessibilityService.kt` | deep link, then press Play |
| End of music | `playback/MusicEndWatcher.kt` | polls every 5 min; YT Music reports no end |
| Stopping | `playback/PlaybackStopper.kt` | pauses **whatever is playing**, not one named app |
| Pause / resume | `playback/PlaybackPauser.kt` | holds a block where it is; see §5 notes |
| Lock handling | `playback/LockScreenGuard.kt`, `LockSafeFallback.kt`, `Announcer.kt` | see §6 |
| Health | `health/HealthChecks.kt` | 14 checks, on the merged Health & self-test screen |

Facts that are easy to get wrong:

- **`PlaylistPicker.Choice.url` is the bare URL.** Brackets are stripped, so a
  label or per-entry episode mode must be carried on `Choice` explicitly. A
  first attempt at per-entry modes silently ignored every one of them.
  **Adding a new per-entry qualifier means four edits, not one:** the field on
  `MediaEntry`, the field on `Choice`, and *both* places in
  `PlaybackTriggerService` that re-parse the bare URL and re-attach fields by
  hand (`.copy(label = …, episodeMode = …)`). Miss one and the qualifier is
  quietly dropped exactly as before.
- **`min N` filters a feed by episode length**, for feeds carrying two formats
  under one name — `[Jews You Should Know | min 20]`. Qualifiers combine and
  may be reordered: `[Name | newest | min 20]`. It filters the candidate pool
  only, so a part-heard episode is still resumed; episodes with no duration are
  kept, matching the half-episode rule's "unknown must not mean skip"; and a
  floor that would exclude everything is ignored rather than silencing the
  show. See §11 for which shows use it and why.
- **The bracket grammar is now an internal storage format, not something anyone
  types.** Both the app and the console edit entries as fields — URL, name,
  episode mode, minimum length — and rebuild the string through
  `MediaEntries.format`. Two consequences worth keeping in mind: the console
  carries a **hand-written copy of the parser** in `web/index.html`
  (`entryUrl`/`entryLabel`/`entryMode`/`entryMin`/`entryOther`/`entryFormat`),
  which must be kept in step with `MediaEntry.kt`; and because a structured
  editor rewrites the whole string, `MediaEntries.otherQualifiers` exists so a
  qualifier neither side understands survives a save instead of vanishing.
- **Queue chaining deliberately re-enters `PlaybackTriggerService`**, so the
  Shabat gate and in-call check apply to every item, not just the first.
- **A block with no stop time does not wrap.** It plays its queue once and ends
  with the last episode. Without that, "no stop" would mean looping till morning.
- **`playPodcast` returns `Started` / `TooLittleTimeLeft` / `Failed`.** The
  middle is a deliberate outcome, not a failure — but treating it as success
  once made a lock-substitute report success while producing silence.
- **Music used to have to be last in a queue** because YT Music never reports an
  end. `MusicEndWatcher` lifted that; the schedule still puts it last by taste.
- **Verification must not accept another app's audio.** `AudioManager.isMusicActive`
  is true for anything, so a launch once "verified" 133 ms after the intent —
  it was hearing the previous podcast. Require a YT Music session.
- **The `Sunset` and `ShabatYomTovEnd` anchors measure their offset from
  different instants.** Sunset's is from sunset; Shabat-ends' is from
  `endOf(day)` = sunset + `shabatEndOffsetMin` (default 42). Block G's `+30` is
  therefore sunset + 72, and it follows that setting if it is ever changed. See
  §11 for why the two anchors are not duplicates.
- **A podcast that dies mid-episode is retried once, then skipped.**
  `PodcastPlayerService`'s error listener used to call `stopPlayback()` and
  nothing else, so one stream error ended the whole block silently — it did not
  advance the queue the way the completion listener does, and recorded no
  failure. It now retries once from the last sampled position and, failing
  that, records a failure and moves to the next entry.
- **`MediaPlayer.getCurrentPosition()` returns 0 once the player has errored**,
  and `PodcastResume.save` discards anything under 60 s, so an errored episode
  used to lose its progress as well as its block. The position is now sampled
  every 10 s while healthy and used as the fallback.
- **`rescheduleAll` runs three times, concurrently, on every app start** — see
  §11. It is now serialised; do not remove that lock.
- **One bad schedule must never abort the re-arm pass.** `rescheduleAll` cancels
  every alarm *before* arming any, so a throw part way through leaves the rest
  cancelled and never re-armed — every later block silently missed. Each
  `scheduleNext` is therefore wrapped individually. This is not theoretical: a
  `timeMinutes` of 1440 reached `Schedule.localTime()` and threw
  `DateTimeException`, and only a remote config arriving a second later
  re-armed the phone.
- **`Schedule.localTime()` wraps `timeMinutes` into the day** rather than
  trusting it, because the value arrives from the console and from tooling as
  well as the picker. 24:04 is taken to mean 00:04.
- **`tools/test-schedule.mjs` wraps its times too.** Run at 23:58 with a
  2-minute lead it used to emit `timeMinutes: 1440`, which is what produced the
  above. **Be careful running device tests near midnight**; the arithmetic is
  fixed, but the block itself still straddles the date boundary.
- **Pause is not stop.** `PlaybackStopper` ends a block and releases the podcast
  player; `PlaybackPauser` keeps the player, its position and its queue index.
  Because a paused episode is not "playing", `PodcastPlayerService.stop` had to
  learn about the paused state too, or Stop became a no-op on a paused block.
  `MusicEndWatcher` also has to check the pause flag: paused and "the playlist
  ran out" are indistinguishable from outside, and without the check pausing
  music would skip to the next entry within five minutes.

---

## 6. The screen lock, the biggest constraint

**Podcasts play locked. YouTube Music does not.** Established by measurement.

We play podcasts ourselves — a service, no activity, no window, so a keyguard
has nothing to refuse. Starting YouTube Music means opening *its* screen and
pressing Play through accessibility, and Android will not open a screen over a
secure keyguard. The log line is `Aborting: systemui bouncer is foreground`.

| Lock | Podcasts, queueing, volume, stop, Shabat mute | YouTube Music |
|---|---|---|
| None | works | works |
| Swipe | works | works |
| PIN / pattern / password | works | **does not start** |

Two escape routes were tried on the device and **both are closed** — do not
re-attempt from scratch:

- **MediaBrowserService** (how Android Auto plays YT Music, no activity at all).
  YT Music declares `.mediabrowser.MusicBrowserService` and **refuses us**:
  `Connection failed`. Allowlisted by caller signature. A deliberate boundary;
  do not try to spoof it.
- **Media session transport controls.** YT Music advertises `PLAY_FROM_URI`,
  `PLAY_FROM_MEDIA_ID`, `PLAY_FROM_SEARCH` (decoded from `actions=241335`). Both
  `playFromUri` and `playFromSearch` are **accepted and do nothing** — it stays
  `STOPPED(1)`. Advertising is not honouring. The Aleph Beta app behaves
  identically. The code still tries this first because it costs seconds.

The YouTube Data API can list a public playlist's contents but returns **no
audio**. Getting audio would mean extracting stream URLs, which their terms
forbid. Not a route this project takes.

**What happens instead:** the app says out loud why, and plays something that
works — preferring the block's own queue, falling back to the Settings defaults
(where Rabbi Breitowitz Q&A and Rabbi Sacks now sit for that purpose; two,
because a substitute arrives late in a block and a 71-minute shiur is often
declined by the half-episode rule while a 10-minute one fits).

Every block containing music currently also contains a podcast, so the Settings
fallback is insurance rather than a live path.

**The only arrangement giving both a PIN and music** is music the app plays
itself: local files or a self-hosted feed. The owner does not have the audio in
that form, so this is parked.

---

## 7. The Google Sheet

<https://docs.google.com/spreadsheets/d/<SHEET_ID>>

Three tabs: **Catalog**, **Schedule**, **Schedule change log**. The
schedule tab was called "Recommended Schedule" while it was still a proposal;
`schedule-sheet.mjs` renames it in place on its next run, because re-creating it
under the new name would strand the guidance columns and column widths Jason set
by hand.

The original **Weekly**, **Daily** and **News** tabs are gone. They held the
Google Home routine the app replaced. Everything on them lives in
`tools/sheet-legacy.json`: per-show notes and TODOs in `notes`, the lengths
Jason had recorded in `durations`, where each show sat in `slots`, and the old
weekly timetable verbatim in `weeklyGrid`. Nothing generates from any of it —
it is history, and the catalog's read-only "Your notes" column is the only
place it surfaces.

The **Catalog** lists everything the app can play, not only podcasts: the 103
shows, and the 9 YouTube Music playlists at the bottom, generated into
`tools/playlist-list.mjs` from the live device config by `regen-playlists.mjs`
so they cannot drift from what the phone holds. Most columns describe an RSS
feed and are blank for a playlist.

Its **Plays via** column says which of the two ways of playing something work.
It is derived, not typed: Google Home is credited only where `sheet-legacy.json`
records the show in one of the old Assistant routines, so it demonstrably played
there. "YTM Trigger app" therefore means the app can fetch it and nothing shows
it ever ran on a speaker — not that it cannot. As of the last rebuild: 61 app
only, 30 both, 9 playlists both, 10 Google Home only for want of a public feed,
one Google Home only because its feed carries no episodes (The Economist Asks),
and one neither (Saturday to Shabbos, HTTP 502).

The config still holds three pre-rebuild schedules, all disabled: **Morning**,
**Afternoon** and **Temp**. They are not armed, so they are harmless, but four
playlists — Google Home, Old Sayings New Ears, Quora and Science
Misunderstandings — are referenced only by the disabled Afternoon, so they never
play. The catalog marks a disabled schedule as such rather than implying the
playlist is in use.

| Tool | Builds |
|---|---|
| `tools/podcast-sheet.mjs` | Catalog (103 shows + 9 playlists) |
| `tools/schedule-sheet.mjs` | Schedule |
| `tools/build-changelog.mjs` | Schedule change log |
| `tools/build-schedules.mjs` | the device config — pass `push` to write |
| `tools/schedule-blocks.mjs` | **the single source for the schedule** |
| `tools/guidance.mjs` | reads the yellow guidance columns; no writes |
| `tools/archive-guidance.mjs` | files applied guidance into the notes column |

### Change guidance from us

Jason writes what he wants changed into the yellow **"Change guidance from us"**
columns, and asks in a Copilot CLI session for the schedule to be reworked with
it in mind. Four such columns exist: `Catalog!E`, and one per section on
the schedule tab at `F5`, `I16` and `G25`.

The loop, in order:

1. `node tools/guidance.mjs` lists anything pending. Exit code 10 means there
   is something, 0 means there is not, so a script can branch without parsing.
2. Make the actual change, in `schedule-blocks.mjs` and wherever else it belongs.
3. Add the row to the list in `build-changelog.mjs`, then run it.
4. `node tools/archive-guidance.mjs --all` moves each piece of guidance into the
   notes cell beside it, stamped `Applied <date>`, and empties the yellow cell.
   Use `--dry-run` first. It writes the note before clearing the guidance, so an
   interruption leaves the text somewhere rather than nowhere.

Things that will catch you out:

- **Catalog guidance is keyed to the show, schedule guidance is keyed to the
  row.** `podcast-sheet.mjs` reads column E back and re-emits it against the
  show name, so it follows a row that moves when statuses re-sort. The schedule
  tab has no such key: its guidance is positional, which is why a section's row
  count must never change. Verified by writing a marker into all four columns
  and rebuilding both tabs - all four survived.
- **Applied guidance always moves one column right.** That is true everywhere:
  the catalog's column E is followed by "Your notes" in F, and each of the
  schedule tab's three guidance columns is followed by a "Notes from us" column
  added beside it. Those three sit at different letters — F, I and G — because
  each hugs the right edge of its own section and the sections are different
  widths, so the tools work from the header rather than fixed positions.
  Sections 4 to 6 have no guidance column; they are explanation and history.
  Adding the third one needed the tab widened from 9 columns to 10, since
  section 2's guidance is in column I and there was no J.
- **A header is only a header if something sits to its left.** The catalog's own
  legend contains a row reading "Change guidance from us" in column A, and
  matching on the words alone turned that into a phantom header with fifteen
  phantom items beneath it.

### The fortnightly check

A Windows scheduled task, **"YTM Trigger - check schedule guidance"**, runs
`tools/check-guidance.ps1` at 09:00 every second Sunday. It runs
`guidance.mjs --notify`, which opens a GitHub issue titled *"Change guidance
pending on the schedule sheet"* when anything is waiting, and does nothing at
all when nothing is. It logs to `%LOCALAPPDATA%\ytm-trigger-guidance.log`.

A second task, **"YTM Trigger - check phone is alive"**, runs
`tools/check-phone.ps1` every hour. It reads the device document's
`updatedAtMs` and raises an issue when the phone has not checked in for 90
minutes — six of the app's fifteen-minute polls — then closes that issue itself
once the phone reappears. Logs to `%LOCALAPPDATA%\ytm-trigger-checkin.log`.

That one exists because on 30 Aug the phone was off from 08:00 to 12:25 and
nothing said so: every other safeguard in this project runs *on the phone*, so a
phone that is off is a phone that cannot report. Its blind spot is that it runs
on this PC, so it is silent when this PC is.

Both are deliberately read-only and involve no AI: they never edit the schedule,
cost no credits, and cannot act on anything by themselves.

- They only run while he is logged on, because `gh` reads its token from the
  Windows credential store, which a task running as SYSTEM cannot reach.
- Duplicate issues are avoided by listing open issues and matching the title in
  code, **not** by `gh issue list --search`: search is a separate index that lags
  creation by seconds to minutes, so a second run soon after the first did not
  see the issue it had just opened.
- **`gh` writes its confirmations to stderr.** With
  `$ErrorActionPreference = 'Stop'` PowerShell turns native stderr into a
  terminating error, so a run that had just successfully closed an issue logged
  `FAILED` and exited 1. Both wrappers now drop to `Continue` around the node
  call and judge by exit code alone.
- Remove either with
  `Unregister-ScheduledTask -TaskName "YTM Trigger - check phone is alive"`.

**`schedule-blocks.mjs` is the only place the schedule is defined.** The device
builder and the sheet renderer both derive from it. They used to hold separate
hand-maintained copies and had already drifted — the sheet showed music opening
the Landing block when the app plays it last, and called four sequential shows
"Random".

### Rules for the sheet tools, learned the hard way

- **Never delete and recreate a tab.** They used to, which threw away everything
  Jason had changed by hand. Reuse the sheet; apply widths and wrap **only when
  creating it from nothing**.
- **Never format a row wider than its own content.** He has added yellow
  "Change guidance from us" columns immediately right of each section.
- **His column is found by name *or* position.** He renamed catalog column E to
  "Change guidance from us"; a name-only lookup returned -1 and would have
  silently dropped every note in it.
- **Pad rows with blanks.** A zero-length array means "no cells supplied", not
  "make these blank", so shrinking content leaves stale text behind.
- **Clear conditional formats before re-adding**, or a duplicate set accumulates
  every run.
- He keeps Overflow wrapping on the rightmost text column and narrow C and F
  columns. **Do not revert his formatting; ask first.**

---

## 8. Aleph Beta

Their public RSS carries 52 episodes across three shows; their own site lists
**136**. The audio was never paywalled — every episode page publishes a
schema.org `PodcastEpisode` whose `associatedMedia.contentUrl` is an ordinary
unauthenticated Buzzsprout MP3. Only the feed was pruned.

`tools/alephbeta-feed.mjs` rebuilds a feed from that published metadata:
**136 episodes, 80h 36m**, ten series, deployed to Firebase Hosting under a
token path. It **fetches and enforces `robots.txt`** before anything else, reads
only sitemap-listed pages, and caches for a week.

Coverage was audited against Aleph Beta's own member search: 55 of 56 audio
items were already in the feed; the one gap is absent from their sitemap and
publishes no episode metadata, so there was nothing to find.

They also run an **official MCP server for members** at
`member-mcp.alephbeta.org/mcp` (OAuth 2.1, scope `library:read`), configured in
the gitignored `.mcp.json`. It is a research tool — `search`, `cite`,
`fetch-transcript`, `whoami` — and returns **no audio URLs**, so it cannot feed
the app.

The app can also drive their Android app (`org.alephbeta.android`, verified App
Links, Media3 session) but **cannot start playback** there, same as YT Music.

---

## 9. Local files not in git but needed

| Path | What |
|---|---|
| `tools/service-account.json` | Firebase admin key — **never commit** |
| `tools/podcast-stats.json` | feed statistics cache; rebuild with `node podcast-stats.mjs` (~5 min) |
| `tools/private-feeds.json` | the Aleph Beta feed URL, which carries a secret token |
| `tools/.ab-feed-token`, `tools/.ab-cache/` | crawl token and page cache |
| `web/private-feeds/<token>/` | the generated feeds |
| `.mcp.json` | the Aleph Beta MCP server |

Firestore config doc:
`users/<USER_ID>/devices/<DEVICE_ID>/data/config`,
shaped `{revision, json}` — the app applies it only when `revision > applied`.

**Check the token never reaches a tracked file:**
`git grep -l $(cat tools/.ab-feed-token) HEAD` should find nothing.

**Reading the device's own config** (23 schedules currently: the 20 production
blocks plus three long-disabled leftovers named "Afternoon", "Temp" and one
unnamed) means pulling `files/datastore/ytmtrigger.preferences_pb`. Note that
`adb shell "run-as … cat file > /sdcard/x"` silently produces a **0-byte** file;
go through base64 instead, which is text-safe both ways:

```powershell
$b64 = & $adb exec-out "run-as com.jasonschoenbrun.ytmtrigger base64 files/datastore/ytmtrigger.preferences_pb"
[IO.File]::WriteAllBytes("prefs.pb", [Convert]::FromBase64String(($b64 -join '').Trim()))
```

The blob is a DataStore protobuf with the schedules as a JSON string inside it.
**It also carries the Spotify client secret — parse out the fields you need and
delete the pulled copy; never dump it wholesale.**

### Deploying the web console

`firebase deploy --only hosting --project music-trigger` (there is no
`.firebaserc`, so `--project` is required). It publishes the whole `web/`
directory — **12 files: `index.html` plus the 11 Aleph Beta feed files.** If
`web/private-feeds/<token>/` is missing locally, deploying would remove the
feeds from Hosting and break the app's podcast source. Check before deploying.
The console cannot be tested from a `file://` copy: it fetches
`/__/firebase/init.json`, which only Hosting serves.

---

## 10. How Jason likes to work

- **Verify on the real device before claiming anything works.** He has caught
  several claims that rested on a false positive.
- **Find the actual cause before proposing a fix.** He pushes back on band-aids
  and has been right every time he has pushed back.
- **Say plainly what was *not* verified.** He values that over confidence.
- **Own mistakes directly.** Several fixes here exist because he questioned
  something that looked fine.
- **He asks "isn't X the same as Y?"** and expects the code checked, not an
  opinion. Sometimes he is right and something should go; sometimes the answer
  is a distinction worth explaining.
- American English. Never rewrite git history.

Commit messages here are prose explaining *why*, including what was measured and
what was got wrong. Match that.

---

## 11. Open items

**Genuinely open:**

- **The self-test alert has fired five times in the retained logs, not once.**
  14 Aug 18:36, 19 Aug 22:49, 20 Aug 12:00, 20 Aug 18:00 and 28 Aug 12:44 — each
  time all three launch strategies timed out and the phone played its audible
  alarm in the house. This used to be filed below as "could not be reproduced…
  probably an artefact". That was wrong, and it is the most important open fault
  here.

  On 28 Aug it was caught live and confirmed independently of the app:
  `dumpsys window` showed `mCurrentFocus` on YouTube Music's `MusicActivity`
  while the accessibility service received **zero** events — its own counters
  read `sawEventSinceConnect=false lastEventMinAgo=never`, and no
  `WindowStateChanged` line appeared. Android meanwhile listed the service under
  `Enabled services` and not under `Crashed services`. The binding is live as
  far as the platform is concerned and simply never delivers.

  **The app's own recovery did not clear it.** The documented remedy — restart
  our own process — ran at 12:44:38 and the service was still inert afterwards.

  **Reproduced deterministically on 28 Aug, and the cure narrowed down.**
  Installing the debug APK over adb left the binding dead **both** times it was
  tried (12:12 and 14:18). Unbinding and rebinding through secure settings —
  confirmed by `A11y: Service destroyed` followed by `Service connected` — did
  **not** fix it, tested twice. A **reboot did**, tested twice. So the earlier
  uncertainty about which step cured it is resolved: it is the reboot.

  **An install is not the only trigger.** Both alerts on 20 Aug (12:00 and
  18:00) happened with no `MY_PACKAGE_REPLACED` that day until 21:58, hours
  afterwards. The 14 and 19 Aug alerts did each follow an install by about an
  hour. So installing reliably causes it, and something else can too.

  **Test it with a cold start, and wake the screen first.** `monkey`, and
  `am start -n` against an already-running task, both resume YouTube Music
  without producing a window change, so the probe reports "dead" for a
  perfectly live service. Always
  `am force-stop com.google.android.apps.youtube.music` first, then
  `am start -n …/.activities.MusicActivity`, then count
  `A11y: WindowStateChanged` lines in the app's log.

  The wake matters just as much as the force-stop. An activity started while
  the display is asleep never becomes visible, so it fires no window event at
  all, and the test reads zero events against a healthy binding. This produced
  a false "the reboot did not cure it" on 28 Aug; waking the screen and
  repeating the identical test gave two events immediately. Send
  `input keyevent KEYCODE_WAKEUP` and confirm `mWakefulness=Awake` before
  drawing any conclusion. Real triggers are unaffected, because they go
  through `KeyguardDismissActivity`, which wakes the device itself.

- **`ytmCameToForeground` cannot be trusted when the binding is dead.**
  `SelfTestRunner.currentForegroundApp` prefers the accessibility service and
  falls back to UsageStats, which is not granted. The forensic record therefore
  says `ytmCameToForeground=false` whether YouTube Music failed to open *or*
  opened and could not be seen — the two faults that most need telling apart.
  On 28 Aug it recorded `false` while the window really was foreground. The
  20 Aug runs recorded `true` with `a11yStarted=n/a`, which is a genuinely
  different signature. Worth recording it as unknown rather than false when the
  service is unresponsive, so the record stops confirming itself.

- **A mid-stream network drop may stall silently rather than raise an error.**
  Trying to reproduce the 08:54 fault by dropping wifi failed repeatedly: a
  28-minute episode kept playing for **five minutes offline** with
  `AudioPlaybackConfiguration ... state:started`, because MediaPlayer prefetches
  most of the file. Cutting the network 0.06 s after playback began did not
  raise an error either. So the retry/advance fix covers the *error* path, which
  is what the production fault took, but a buffer that simply runs dry without
  an `onError` would still leave a block quiet. Nothing has been seen doing
  that; it is an untested gap rather than a known fault.
  Practical consequence for testing: **you cannot induce a mid-stream error by
  toggling wifi.** Test the never-started path instead, by going offline before
  the block fires.
- **The Weekly tab** still duplicates Google Home state. Left alone while the
  Google Home automations run in parallel.
- **31 Google Home podcast automations** are still live alongside the app. He
  intended to delete them once satisfied.

**Closed — do not reopen without new information:**

- **"Jews You Should Know needs length-aware picking" — investigated and
  settled.** The short episodes are not exceptions and not noise: they are a
  named sub-series, *Torah You Should Know*, 73 numbered divrei Torah of 3-7
  minutes published on Fridays between Nov 2020 and Jun 2022. That is **73 of
  288 episodes, 25.3%** — one random draw in four. The two formats separate
  cleanly: **no episode at all falls between 8 and 28 minutes**. They are also
  parsha-specific and years old, so a random Monday draw was usually the wrong
  week as well as four minutes long. The only other short items are three
  summer PSAs and the May 2024 sign-off.

  Resolved with a per-entry `min 20` (see §5). Two other scheduled shows have
  the same shape and got the same treatment: **Smash Boom Best** (45 of 237
  under 10 minutes against a 30-45 minute debate; empty from 15 to 25) and
  **The School of Greatness** — which is worth distinguishing, because it is
  *not* bimodal: 247 of its 1,978 episodes sit in the 5-10 minute band with no
  gap anywhere, so that is a deliberate short format and the floor there is a
  preference, not a format separation. Across the catalogue only 4 of 82 shows
  with enough samples have `durP10 / durMedian < 0.25`.

  The practical damage had been overstated: every block is a continuous queue,
  so a four-minute draw never caused silence — it just advanced early. What was
  lost was the intended slot, about one Monday in four.
- **Sunset vs Shabat-ends anchors are not duplicates.** Asked and answered
  against the code, not from memory. Three separate differences:
  1. **Day selection.** `Sunset` yields a time on every ticked day.
     `ShabatYomTovEnd` goes through `windowEndOn`, which returns null unless the
     day is a Saturday or a Yom Tov end-day (`HebrewCalendarChecker.kt:145`) and
     null *again* if the block is still in force a minute past the candidate
     (`:148`), so Shabat running into Yom Tov correctly yields nothing. Ticked
     days filter; they do not select.
  2. **Different offset origin** — see §5. `+30` means two different clock
     times under the two anchors.
  3. **Substituting one for the other yields silence, not a wrong time.**
     Sunset+30 on a Saturday is not after nightfall (sunset+42), so `evaluate()`
     (`:88`) returns skip and the Shabat gate blocks it. The block simply never
     plays.

  Resolution: keep both. Jason chose to leave the **app** editor exactly as it
  is — chips still read "Clock" / "Sunset" / "Shabat ends", and he declined
  renaming the last to "Shabat/Yom Tov ends". The **web console** was the real
  gap: it offered the same choice with no explanation at all, and now shows the
  app's existing per-anchor wording under the dropdown (`ANCHOR_HINT` in
  `web/index.html`). The console's dropdown still says "Shabat/Yom Tov ends"
  while the app chip says "Shabat ends"; that mismatch was left deliberately.

  Confirmed on the device afterwards, from two independent log lines:
  `Shabat prep … windowStart=2026-08-28 18:30:23` gives sunset(Fri 28) =
  19:10:23 (startOffset 40), and block G armed at `2026-08-29 20:21:11` gives
  endOf(Sat 29) = 19:51:11, so sunset(Sat 29) = 19:09:11 — 72 s earlier a day
  later, which is right for late August. The `+30` therefore produced
  sunset + 72 min. A `Sunset +30` schedule would have armed 19:39:11, inside
  Shabat, and been silently gated. Also worth knowing: **only block G uses a
  non-clock anchor. Nothing in production uses `Sunset` at all** — the option
  exists in the editor and is currently unused, which is why the confusion was
  purely at the editor level.
- Starting YouTube Music or the Aleph Beta app behind a secure lock (§6).
- Implementing the schedule on Google Home — its automations play one podcast
  each and cannot queue.
- Reproducing the "zombie accessibility service" state — **reopened; see the
  open items above.** It has now happened five times and was confirmed live on
  28 Aug, so the earlier verdict that it was probably an artefact is withdrawn.

**Known and accepted:**

- One guard run at 13:21:50 did not fire with ~10 s left while an identical
  later run did. Never explained. **The `rescheduleAll` race below is a
  plausible mechanism** — if a cancel from a second pass landed after a first
  had armed that id, the alarm would simply be gone. Not proven, but the race
  is now closed, so watch whether this recurs.
- The stale re-arm race after a config sync — **found and fixed.**
  `AlarmScheduler.rescheduleAll` cancels every alarm and then re-arms them, with
  no synchronisation, and three passes run within ~200 ms on every app start:
  `YtmApp`'s startup re-arm plus `BootReceiver` handling `LOCKED_BOOT_COMPLETED`
  and `BOOT_COMPLETED`. Android **re-delivers both boot broadcasts whenever the
  app leaves the stopped state**, which is why the log holds 148 "boots" over 15
  days on a phone that had been up for five, and why every schedule is armed 3×
  per start. The device log showed two passes inside their arming loops at once
  — two `Scheduled` lines for one id with no `Cancelled` between them. The
  method is now `synchronized`; verified afterwards by checking that every arm
  is preceded by its own cancel (0 violations after, 2 before, using the same
  detector so the check could actually fail).
