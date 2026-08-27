# Working notes

Context for anyone — human or agent — picking this project up. Written after a
long session that touched nearly every part of it, and kept in the repo because
most of it is expensive to rediscover.

Read `README.md` first for what the app does. This file is about *working on*
it: how to build and test, which facts were established by measurement rather
than assumption, and what is deliberately left undone.

---

## 1. What this is, physically

A dedicated Android phone (Motorola, `ZY22KJFNJM`, Android 14) sits in the house
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
# volume: send as many keyevent 24 as the keyevent 25 you sent
```

Check: `locksettings get-disabled` → `true`,
`dumpsys trust | grep deviceLocked` → `0`, `Password quality: {0=0}`.

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
| Lock handling | `playback/LockScreenGuard.kt`, `LockSafeFallback.kt`, `Announcer.kt` | see §6 |
| Health | `health/HealthChecks.kt` | 14 checks, on the merged Health & self-test screen |

Facts that are easy to get wrong:

- **`PlaylistPicker.Choice.url` is the bare URL.** Brackets are stripped, so a
  label or per-entry episode mode must be carried on `Choice` explicitly. A
  first attempt at per-entry modes silently ignored every one of them.
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

Four tabs: **Weekly** (the old Google Home record), **Podcast Catalog**,
**Recommended Schedule**, **Schedule change log**.

| Tool | Builds |
|---|---|
| `tools/podcast-sheet.mjs` | Podcast Catalog (103 shows) |
| `tools/schedule-sheet.mjs` | Recommended Schedule |
| `tools/build-changelog.mjs` | Schedule change log |
| `tools/build-schedules.mjs` | the device config — pass `push` to write |
| `tools/schedule-blocks.mjs` | **the single source for the schedule** |

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

- **Sunset vs Shabat-ends anchors.** He asked whether they are duplicates. The
  *time* is derived (`endOf = sunset + endOffsetMin`); the *day selection* is
  not — Shabat-ends fires only on Saturdays and Yom Tov end-days, and suppresses
  the nightfall where Shabat runs into Yom Tov. Merging them would make the
  offset ambiguous and fail silently (the Shabat gate would block it, so the
  symptom is a missed block, not playback on Shabat). **An open offer stands to
  reword the editor labels instead:** *"Sunset (every ticked day)"* /
  *"Shabat/Yom Tov ends (only motzaei Shabat / Yom Tov)"*. He has not answered.
- **The Weekly tab** still duplicates Google Home state. Left alone while the
  Google Home automations run in parallel.
- **31 Google Home podcast automations** are still live alongside the app. He
  intended to delete them once satisfied.
- **`store_memory` was failing** at the end of the session ("an unexpected error
  occurred"), so the screenshot preference in §3 is not in agent memory. If it
  is still broken, put it in `copilot-instructions.md` instead.

**Closed — do not reopen without new information:**

- Starting YouTube Music or the Aleph Beta app behind a secure lock (§6).
- Implementing the schedule on Google Home — its automations play one podcast
  each and cannot queue.
- Reproducing the "zombie accessibility service" state — could not be
  reproduced; the earlier sighting was probably an artefact.

**Known and accepted:**

- One guard run at 13:21:50 did not fire with ~10 s left while an identical
  later run did. Never explained. Not seen since.
- The stale re-arm race after a config sync — real, self-correcting, worked
  around by restarting the app twice.
