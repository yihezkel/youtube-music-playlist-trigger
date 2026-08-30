# YTM Trigger

Android app that turns a spare phone into a scheduled kitchen radio: at the times you set it
plays podcasts and YouTube Music playlists — over Bluetooth or the built-in speaker — and
keeps itself working without anyone picking it up.

Built for a household in Israel, so the schedule follows sunset and nightfall rather than the
clock, and nothing plays on Shabat or Yom Tov.

**Current version: 0.6.0** — see [Releases](../../releases) for the APK.

**Contents** — [What it does](#what-it-does) · [Screen lock](#screen-lock) ·
[Planning the schedule](#planning-the-schedule) · [Install](#install) ·
[First-time setup](#first-time-setup) · [Remote control](#remote-control-optional)

## What it does

### When it plays

- **Scheduled triggers**: per-schedule day-of-week + time picker. Multiple schedules supported.
- **Trigger time can follow the calendar, not just the clock.** Each schedule picks what its
  time is measured from:
  - **Clock** — a fixed wall-clock time (the default, and the previous behaviour);
  - **Sunset ± N minutes** — real sunset at the configured latitude/longitude, which in Israel
    moves by over three hours across the year, so a fixed time drifts against it;
  - **Shabat/Yom Tov ends ± N minutes** — nightfall at the end of a window. This is *not* the
    same as a sunset offset: it also covers Yom Tov and multi-day festivals, and it deliberately
    yields nothing on a day when no window ends, so such a schedule fires only on motzaei
    Shabat / Yom Tov even if extra days are ticked. When one window runs straight into the
    next (Shabat into Yom Tov), the intervening nightfall is not treated as an end.
- **Optional stop time** per schedule — leave it blank (the default) to play until stopped,
  or set a clock time to pause automatically. A stop time at or before the start time means
  the next day, so an overnight schedule stops in the morning. The console and the app both
  have a **Stop** button for the same thing on demand.
- **Blocks can follow other blocks.** A schedule can start when another one finishes
  instead of at a clock time. Motzaei Shabat is why: the kids' block is anchored to
  nightfall, so its start moves nearly three hours across the year, while the teen block
  after it had a fixed 20:30 start — in midsummer they collided, in midwinter there was a
  long gap. A fixed *offset* from nightfall would not fix it either, because how long the
  first block runs depends on which episodes it happens to draw.

  **This is deliberately read-only in the app and the console** — both show a chained block
  saying "Starts when *X* finishes, not at a clock time", and neither lets you change it.
  Only `tools/schedule-blocks.mjs` sets it.

  **To make it editable** (a decision deferred, not refused) the groundwork is already
  done: `ScheduleChain` holds the rules, `HealthChecks` surfaces a broken chain as a red
  check, and `build-schedules.mjs` refuses to push a bad one. All four failure modes are
  detected — a block following itself, a block following one that no longer exists or is
  disabled, two blocks following the same predecessor (only the first would ever run), and
  a loop. What is still missing is only the UI: a predecessor picker that excludes choices
  that would create those states, and a decision about what should happen to a follower
  when its predecessor is deleted.

### What it plays

- **Playlists, songs and podcasts.** An entry can be:
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
  - Spotify's own API is deliberately not used: it requires a **Premium** account and its
    public page exposes only 12 episodes, whereas the feed for the same show carries the
    full back catalogue (384 episodes, in the case this was built against).
  - Spotify **originals and exclusives** have no public feed and cannot be played.
- **Which episode, per entry.** A block legitimately mixes shows with opposite needs, which a
  single per-schedule setting could not express. The schedule sets a default; any entry can
  override it by ending its name with qualifiers, in any order:

      https://feeds.npr.org/510325/podcast.xml  [The Indicator | newest]
      https://rss.art19.com/business-wars       [Business Wars | sequential]

  - **Random** suits evergreen archives; **newest** suits news and feeds that mix short and
    long formats; **in order** suits a serial that tells one story across numbered parts,
    where random produces part 1 followed by part 5 of a different series.
  - **`min N` — a length floor.** Some feeds carry two formats under one name, so a random
    draw is really a draw between formats. *Jews You Should Know* publishes 45–100 minute
    biography interviews and, in the same feed, 73 episodes of a 3–7 minute *Torah You
    Should Know* series — a quarter of the feed, with nothing at all between 8 and 28
    minutes. `min 20` picks the format rather than the show:

        https://rss.libsyn.com/shows/104921/….xml  [Jews You Should Know | newest | min 20]

    The floor applies only to the pool of candidates, so an episode left part-heard is still
    resumed; episodes whose feed omits a duration are kept, on the same principle as the
    half-episode rule; and a floor that would exclude every episode is ignored rather than
    dropping the show from the block.
  - **In-order position is remembered per feed** and advances only when an episode is heard
    to the end, so a block that cuts one off resumes it rather than skipping past it. If the
    marked episode drops out of the feed, or the show has been heard through, it starts again
    from the oldest rather than going silent.
- **Entries are edited as fields, not as text.** Each playlist, song or podcast is its own
  row in the app and in the web console — URL, name, and for podcasts which episode to play
  and the shortest episode worth starting — with an **Add** button for the next one. The
  stored form is unchanged (`url [Name | mode | min N]`), because the device config, the
  console and the schedule tooling all share that one grammar; only the editing changed, so
  nobody has to remember the bracket syntax or discover that a typo silently fell back to
  the default. Anything the fields don't model is carried through untouched and flagged
  rather than dropped.
- **Named playlists** — anywhere a playlist URL is accepted you can give it a name:
  `https://music.youtube.com/playlist?list=PLKNLlLCOCLas&si=txZZ [Quora]`.
  Lists then show the name with the URL underneath. The name is cosmetic — playback always
  uses the playlist ID — so labelled and bare URLs are interchangeable.

### How a block runs

- **Continuous play** — a schedule can run its entries as a queue rather than picking one:
  the next starts the moment the current finishes, wrapping back to the top so the block
  stays filled until its stop time. Podcast entries chain by themselves; a YouTube Music
  entry hands control to YT Music, so the app watches for the playlist ending and moves the
  queue on when it does, which means music no longer has to be the last thing in a queue.
- **Won't start an episode it can't get halfway through.** With less than half an episode's
  worth of block remaining, the block simply ends early rather than playing a fragment.
  Episodes whose feed omits a duration are always played — unknown must not mean "skip".
- **Resumes what was cut off.** A block ends on a clock time, so something is always
  interrupted. The position is remembered per feed and picked up next time, five minutes
  earlier than where it stopped so the context is re-established rather than resuming
  mid-sentence. An episode heard to the end clears its mark.
- **Recovers from a playback error.** A stream that dies mid-episode is retried once from
  where it stopped; if that fails the queue advances to the next entry and the failure is
  recorded. Before this, an error simply stopped the player: on 27 August a block died
  39 minutes into its second episode and played nothing for the remaining seven hours, with
  nothing anywhere to say so.
- **Recovers from a silent stall.** The player samples its own position every 10 seconds.
  Three unchanged samples are noted; twelve — about two minutes of a stream that is
  connected but not advancing — are treated as a failure and go down the same
  retry-then-advance path.
- **Pause and resume a block** from a **Playback** card at the top of the home screen. This
  is not the same as stopping it: stop ends the block and releases the episode, keeping only
  a resume mark, whereas pause holds the player, its exact position and its place in the
  queue, so resuming continues the same episode rather than choosing a new one. It works
  whatever the block is playing through — this app's own podcast player, YouTube Music or
  the Aleph Beta app — because it goes through media sessions. A paused block still ends at
  its stop time and is still silenced before Shabat, and the end-of-playlist watcher holds
  the queue still while paused instead of mistaking a pause for a finished playlist.
- **Keeps the screen awake only while music plays.** Free-tier YouTube Music pauses
  anything you didn't upload yourself the moment the screen sleeps, which normally forces
  the developer option "Stay awake" on — lighting the panel 24 hours a day. Instead the app
  holds a 1×1 transparent overlay carrying `FLAG_KEEP_SCREEN_ON` for exactly as long as
  YouTube Music reports it is playing, and can pin brightness near zero so the screen is
  technically on but practically black. Needs a one-time **Display over other apps** grant;
  toggles live in **Default settings**.
  - Turn **off** Developer options → "Stay awake" once this is enabled.
  - A wake lock is not used: `SCREEN_BRIGHT_WAKE_LOCK`/`SCREEN_DIM_WAKE_LOCK` have been
    deprecated since API 17 and are unreliable, and a `PARTIAL_WAKE_LOCK` explicitly does
    not keep the display on.

### Shabat and Yom Tov

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

### Starting YouTube Music

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

### Keeping itself working

- **6-hourly background self-test** that silently confirms the whole flow still works.
  Plays an audible TTS + alarm tone ("YouTube Music Bluetooth phone isn't working and needs
  attention") if the test fails three different ways in a row.
  - Volume is forced to 0 during the test.
  - Skipped automatically on Shabat and Yom Tov. A manual **Run now** asks for
    confirmation first rather than being silently blocked.
  - Every run is persisted as a **structured forensic record**
    (`filesDir/selftest-history/YYYY-MM.jsonl`): per-strategy attempt, real intent dispatch
    result, accessibility step trace with latencies, and MediaSession / audio-active
    timelines. Tap **Export last 20 runs (JSON)** on the Self-test screen to share them.
- **Sixteen health checks**, shown in the app and mirrored in the console: alarms armed,
  block chaining, enabled schedules, screen lock, accessibility, MediaSession probe,
  battery exemption, background restriction, automatic time, YouTube Music installed,
  network, media volume, playback paused, and failures in the last week. Each says what is
  wrong in a sentence and where to fix it, rather than reporting a raw flag.
- **Failures in the last week** — a 7-day bar chart plus one sentence per failure, at the
  bottom of the Self-test screen and of the web console, or "No failures in the last week!"
  when there were none. The count is aligned to calendar days, so "today" means today.
- **Accessibility resilience** — if Android disables the accessibility service, the app
  re-enables it automatically (on launch, on boot, before every trigger and self-test, plus
  a live settings watcher), and escalates through re-asserting the setting, recreating the
  component, and finally restarting its own process. Requires a one-time
  `WRITE_SECURE_SETTINGS` grant; see setup below.
  - The service can also end up **bound but delivering nothing** — Android reports it
    running, and no event ever arrives. This is why a block can go quiet with every check
    green. It is now detected rather than guessed at: YouTube Music publishing a media
    session, or a launch the app made itself, is independent evidence that it ran, so
    silence afterwards proves the binding is dead and the health screen says so.
  - **For that state the only cure found is a reboot.** The escalation above does not clear
    it — neither a process restart nor unbinding and rebinding the service, both tried and
    recorded — so the health check says to reboot rather than pretending it can self-heal.
    Reinstalling the APK reliably causes it, so reboot after any install.
- **Pause is visible.** A pause left in force is reported by the health screen and called
  degraded after a quarter of an hour. Every other check asks whether playback *could*
  start; none noticed a block deliberately held silent.
- **Persistent logs** with in-app viewer, level filter, search, copy/share. 14-day
  retention. Diagnostic "EvalFix" markers let speculative fixes be evaluated and pruned
  over time.

### Controlling it

- **Remote control** — change playlists and schedules, trigger playback, pause, resume, stop,
  and read the phone's logs and all sixteen of its health checks from a browser. Optional;
  see below. The app and the console cover the same ground: every schedule field is editable
  in both, and both can pause, resume and stop.
- **Manual trigger** from a **Play now** button on each schedule, or from a home-screen widget.
- **Setup checklist + diagnostics** with vendor-specific advice (Samsung, Xiaomi, Huawei,
  Oppo, Vivo, OnePlus, Pixel) for "Sleeping apps" / "Auto-launch" / "Protected apps" systems.

## Screen lock

**Podcasts play with the phone locked. YouTube Music does not.** This is a
platform boundary rather than a bug, and it was established by measurement on
the device rather than inference.

The difference is who does the playing. A podcast is played by this app itself,
in a foreground service with no activity and no window, so a keyguard has
nothing to refuse. Starting YouTube Music means opening *its* screen and pressing
its Play button through the accessibility service — and Android will not let an
app open a screen over a secure keyguard, which is correct behaviour. What the
log shows is `Aborting: systemui bouncer is foreground`: the deep link launches
YouTube Music, the lock screen takes the foreground back, and the Play press
never lands.

| Lock | Podcasts, queueing, volume, stop, Shabat mute | YouTube Music |
|---|---|---|
| None | works | works |
| Swipe (no credential) | works | works |
| PIN / pattern / password | works | **does not start** |

A swipe lock is fine: it shows a keyguard but holds no credential, so
`requestDismissKeyguard` clears it.

### What was tried, and why it did not work

Both of these are recorded so they are not re-attempted from scratch:

- **MediaBrowserService** — the interface a car head unit or Android Auto uses to
  browse and play someone's library, with no activity at all. YouTube Music
  declares one (`.mediabrowser.MusicBrowserService`) and **refuses this app**:
  `Connection failed`. It is allowlisted by caller signature. That is a
  deliberate security boundary and not something to work around.
- **Media session transport controls** — no allowlist, and service calls rather
  than windows. YouTube Music advertises `PLAY_FROM_URI`, `PLAY_FROM_MEDIA_ID`
  and `PLAY_FROM_SEARCH` (decoded from `actions=241335`). Both `playFromUri` and
  `playFromSearch` are **accepted and do nothing**: YouTube Music stays
  `STOPPED(1)`. Advertising an action is not honouring it, and honouring these
  appears reserved for privileged callers such as Assistant. The app still tries
  this route before giving up, because it costs seconds and would be the whole
  answer if it ever started working.

Reading a playlist's contents through the **YouTube Data API** is possible for a
public playlist, but it returns metadata only — no audio. Obtaining audio would
mean extracting stream URLs, which YouTube's terms prohibit, so it is not a
route this project will take.

### What happens instead

A block does not fall silent. When an entry cannot start because the phone is
locked, the app **says so out loud** and plays something that will work:

> "Best can't play while the phone is locked. Playing Rabbi Breitowitz instead,
> from this block."

It looks in the block's own queue first, because the household chose those shows
for that hour, and falls back to the default entries in Settings when a block is
all music. The announcement is spoken rather than posted, since nobody is
watching a phone in a kitchen, and it finishes before playback starts so it is
not talked over.

Note that this only helps if *something* reachable is a podcast. Every block that
contains music currently also contains a podcast, so the substitute comes from
the block itself and the Settings defaults are never reached. They are insurance
against a block that is all music — Rabbi Breitowitz Q&A and The Office of Rabbi
Sacks sit at the end of the default list for that purpose. If a block were all
music **and** the Settings defaults were all music, there would be nothing to
substitute and the block would be skipped with a failure notification.

The list is walked in order until something actually starts, because a
substitute arrives late in its block and can be declined for being too long for
what is left — the same half-episode rule that governs normal playback. That is
why the fallback list holds both a long show and a short one: Rabbi Breitowitz
runs about 71 minutes and needs 36 of block remaining, which erev Shabat has and
a half-hour Landing block does not; Rabbi Sacks runs 10 and fits anywhere.

### The one arrangement that gives you both

If music mattered more than the lock, the fix is to stop asking another app to
play it: anything this app plays itself works locked. That means music from
files on the phone or from a feed served to it — the same machinery the podcast
path already uses, including queue chaining, resume and stop-at-block-end. It
needs the audio in a form the app can play, so it is not available for a
streaming library.

## Planning the schedule

The line-up itself is not edited on the phone. `tools/schedule-blocks.mjs` is the **single
definition of what plays when**; `build-schedules.mjs` turns it into the device config and
pushes it, and `schedule-sheet.mjs` renders the same data to a Google Sheet. They used to be
separate hand-maintained copies and had already drifted — the sheet showed music opening a
block the app plays it last in, and called four sequential shows "Random".

An entry there is a podcast by name, `MUSIC` for the default playlist rotation, or
`playlist("Quora")` for one particular YouTube Music playlist. An unknown playlist name
fails the build rather than arming a schedule that goes quiet at that point in the queue.

Three tabs, all generated:

| Tab | What it holds |
|---|---|
| **Schedule** | The day, the week, the running order, how queues behave, and what was recently settled |
| **Catalog** | Everything playable — 103 shows and 9 playlists — with publishing rate, episode lengths and how predictable they are, plus **Plays via**: whether each works on the Google Home, in this app, both or neither |
| **Schedule change log** | Every addition and removal, reconstructed back to 2022 from the sheet's own revision history |

### Asking for changes

Yellow **"Change guidance from us"** columns on the Schedule and Catalog tabs are where you
write what you want different. The loop:

```sh
node tools/guidance.mjs                 # what is pending (exit 10 if any)
#   … make the change, and add its row to build-changelog.mjs
node tools/archive-guidance.mjs --all   # file it, and clear the yellow cell
```

Applied guidance moves one column right, stamped `Applied <date>`, so the request survives
as history instead of being deleted. Catalog guidance is keyed to the show and follows a row
that re-sorts; Schedule guidance is positional, which is why a section's row count must not
change.

### Two watchdogs

Both are Windows scheduled tasks, both read-only, and neither involves AI: they
cost nothing, cannot edit anything, and exist only so a problem is noticed.

| Task | Runs | Does |
|---|---|---|
| **YTM Trigger - check schedule guidance** | 09:00 every second Sunday | `tools/guidance.mjs --notify` — opens a GitHub issue when the yellow cells have something in them |
| **YTM Trigger - check phone is alive** | hourly | `tools/checkin.mjs --notify` — opens a GitHub issue when the phone has not checked in for 90 minutes, and **closes it again** when the phone comes back |

The second exists because of 30 August. The phone was off from 08:00 to 12:25;
block B fired at 08:00, played one episode and then nothing happened for four
and a half hours. Every other safeguard here runs *on the phone* — the health
checks, the failure log, the self-test alert — so a phone that is off reports
nothing at all. Nobody noticed until it was plugged back in. Ninety minutes is
six of the app's fifteen-minute polls: long enough that a sleeping phone will
not trip it, short enough to catch a morning like that one within the hour.

They log to `%LOCALAPPDATA%\ytm-trigger-guidance.log` and
`…\ytm-trigger-checkin.log`.

- Both run only while you are logged on, because `gh` reads its token from the
  Windows credential store, which a task running as SYSTEM cannot reach. They
  also need this PC to be on — a watchdog on the same desk has its own blind
  spot.
- Duplicate issues are avoided by listing open issues and matching the title in
  code, **not** by `gh issue list --search`: search is a separate index that lags
  creation, so a second run soon after the first would not see the issue it had
  just opened.
- Remove either with
  `Unregister-ScheduledTask -TaskName "YTM Trigger - check phone is alive"`.

### The same schedule on Google Home

`tools/google-home-automations.mjs` generates
[`tools/google-home-automations.yaml`](tools/google-home-automations.yaml) from the same
source, for the Google Home **script editor** (Public Preview, at
[home.google.com/automations](https://home.google.com/automations)). Paste each numbered
section as its own automation — the editor takes one `metadata` + one `automations` block
per script — and replace the `SPEAKER NAME - ROOM` placeholder.

It is an approximation on purpose, and the gaps are the point:

| The app does | Google Home |
|---|---|
| Anchors to sunset and to nightfall at the end of Shabat/Yom Tov | Sunset offsets exist (`at: sunset+30min`); **a Jewish calendar does not** |
| Never plays on Shabat or Yom Tov, and mutes 15 minutes before | Weekdays only avoids Shabat; **Yom Tov will still fire** — turn them off yourself |
| Plays a queue of a dozen shows per block | One command, so one show per block |
| Picks the episode: random, newest, in order, `min N` | Whatever Assistant resolves from the name |
| Resumes what was cut off, skips what it can't finish | Neither |
| Starts a block when another finishes | Not expressible |
| Plays your own YouTube Music playlists | Google's docs say an action needing Voice Match or personal results **will not run** in a household automation, so these ask for podcasts by name instead |

The erev Shabat and both motzaei Shabat blocks are **not generated at all** — their times
depend on nightfall and on a festival running into Shabat, which only the app works out.
Where the sheet records a show as having actually played on your old Assistant routines the
name is used as-is; the two that aren't recorded are flagged `UNVERIFIED` in the file, to be
said aloud once before they're relied on.

**Don't run these and the app on the same speaker** — they will talk over each other. They
are for a speaker the phone doesn't drive, or for when the phone is out of action.

## Install

Download the APK from the [Releases page](../../releases) and sideload it. The app needs Android 14 (API 34) or newer.

## Build from source

Working on this? Read [`docs/working-notes.md`](docs/working-notes.md) first —
build and device-test commands, the constraints that were established by
measurement rather than assumption, and what is deliberately left undone.

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
- **Play now**, **Pause**, **Resume**, **Stop**, **Run self-test**, **Request logs**
- Read uploaded logs in the browser
- See all sixteen health checks, the same ones the app shows
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
