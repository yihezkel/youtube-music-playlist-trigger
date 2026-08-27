// Push a purpose-built test schedule and force the app to sync it.
//
//   node test-schedule.mjs guard   - short clip then a long one, in a block too
//                                    short for the long one: the 50% rule must
//                                    decline to start it
//   node test-schedule.mjs resume  - one long episode cut off by the stop, so a
//                                    resume position is saved
//   node test-schedule.mjs clean
import admin from "firebase-admin";
import { readFileSync } from "node:fs";
import { execSync } from "node:child_process";

const DOC = "users/<USER_ID>/devices/<DEVICE_ID>/data/config";
const TORAH = "https://rss.buzzsprout.com/1566434.rss";     // ~108s episodes
const SHORTWAVE = "https://feeds.npr.org/510351/podcast.xml"; // ~13 min episodes
const NAME = "ZZ QueueTest";
const NAME2 = "ZZ QueueTest Follower";
const ADB = `${process.env.LOCALAPPDATA}\\Android\\Sdk\\platform-tools\\adb.exe`;

const mode = process.argv[2] || "clean";
const lead = Number(process.argv[3] ?? 3);   // minutes until the block starts
const length = Number(process.argv[4] ?? 5); // block length in minutes

admin.initializeApp({ credential: admin.credential.cert(JSON.parse(readFileSync("./service-account.json", "utf8"))) });
const db = admin.firestore();
const ref = db.doc(DOC);
const snap = await ref.get();
const cfg = JSON.parse(snap.get("json"));
const revision = Number(snap.get("revision") || 0);
cfg.schedules = cfg.schedules.filter((s) => s.name !== NAME && s.name !== NAME2);

const deviceNow = execSync(`"${ADB}" shell "date +%H:%M"`).toString().trim();
const [hh, mm] = deviceNow.split(":").map(Number);
// Wrap into the day. Run at 23:58 with a 2-minute lead this used to produce
// timeMinutes=1440, which Schedule.localTime() rejected with a
// DateTimeException that aborted the whole re-arm pass and left every
// remaining alarm cancelled. The app tolerates it now; the tool should not
// have been emitting it either.
const wrap = (m) => ((m % 1440) + 1440) % 1440;
const start = wrap(hh * 60 + mm + lead);
const stop = wrap(start + length);
const fmt = (m) => `${String(Math.floor(m / 60) % 24).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

if (mode !== "clean") {
  const urls = mode === "guard"
    ? [`${TORAH} [Short clip]`, `${SHORTWAVE} [Long episode]`]
    : mode === "modes" || mode === "wrap"
      // Schedule-level mode is Random below, so if per-entry parsing works the
      // log must show Latest and Sequential with modeFrom=entry.
      //
      // For "wrap" the same pair doubles as the wrap test: entry 1 is a newest
      // entry, which must play on lap 0 and be skipped on every later lap,
      // leaving entry 2 to carry the rest of the block.
      ? [`${TORAH} [Newest one | newest]`, `${TORAH} [In order | sequential]`]
      : [`${SHORTWAVE} [Long episode]`];
  cfg.schedules.push({
    id: "zz-queue-test",
    name: NAME,
    enabled: true,
    daysOfWeek: [1, 2, 3, 4, 5, 6, 7],
    timeMinutes: start,
    stopTimeMinutes: stop,
    timeAnchor: "FixedClock",
    anchorOffsetMinutes: 0,
    playlistUrls: urls,
    targetVolumePercent: 0,   // silent: these run during the household's day
    autoStopMinutes: null,
    enableShuffle: false,
    skipFirstTrack: false,
    podcastEpisodeMode: mode === "modes" || mode === "wrap" ? "Random" : "Latest",
    continuousPlay: true,
    lastPickedPlaylistIds: [],
  });
  // "chain": the first block has no stop time, so it ends with its queue and
  // must hand on to the follower rather than the follower waiting on a clock.
  if (mode === "chain") {
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.stopTimeMinutes = null;
    first.playlistUrls = [`${TORAH} [Chain first]`];
    cfg.schedules.push({
      ...first,
      id: "zz-queue-test-2",
      name: NAME2,
      timeMinutes: wrap(start + 60),      // deliberately far off; must never be used
      stopTimeMinutes: null,
      startsAfter: "zz-queue-test",
      playlistUrls: [`${TORAH} [Chain second]`],
    });
  }
  // "autostop": no clock stop, a fixed run instead, and music last so the queue
  // itself can never end. The stop alarm has to both pause YT Music and hand on
  // to the follower.
  if (mode === "autostop") {
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.stopTimeMinutes = null;
    first.autoStopMinutes = length;
    first.playlistUrls = [`${TORAH} [Autostop first]`, ...(cfg.defaultPlaylistUrls || []).slice(0, 1)];
    cfg.schedules.push({
      ...first,
      id: "zz-queue-test-2",
      name: NAME2,
      timeMinutes: wrap(start + 60),
      stopTimeMinutes: null,
      autoStopMinutes: null,
      startsAfter: "zz-queue-test",
      playlistUrls: [`${TORAH} [Autostop follower]`],
    });
  }
  // "abfeed": the rebuilt Aleph Beta feed. URL comes from the gitignored
  // private-feeds.json so the token never lands in a tracked file.
  if (mode === "abfeed") {
    const pf = JSON.parse(readFileSync("private-feeds.json", "utf8"));
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.stopTimeMinutes = wrap(start + length);
    first.podcastEpisodeMode = "Random";
    first.playlistUrls = [`${pf["Aleph Beta"]} [Aleph Beta]`, `${TORAH} [After Aleph Beta]`];
  }
  // "minlen": a feed carrying two formats under one name. Jews You Should Know
  // holds 73 three-to-seven-minute divrei Torah among 288 episodes, with
  // nothing between 8 and 28 minutes, so a 20-minute floor must leave exactly
  // the 211 interviews. Run it repeatedly: without the floor roughly one draw
  // in four is a four-minute episode.
  if (mode === "minlen") {
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.stopTimeMinutes = wrap(start + length);
    first.podcastEpisodeMode = "Random";
    first.playlistUrls = [
      // "newest" alone would pick the 3-minute 2024 sign-off announcement,
      // which sits at the top of this feed. Combined with the floor it must
      // pick the newest *interview* instead - Episode 205, 55 minutes - which
      // makes this a falsifiable test of both qualifiers and of their order.
      "https://rss.libsyn.com/shows/104921/destinations/562825.xml [Jews You Should Know | newest | min 20]",
      `${TORAH} [Chaser | sequential]`,
    ];
  }
  // "alephbeta": drive the Aleph Beta app. Their public feed carries 4 episodes
  // where the subscription holds 68, so the app is the only way to the archive.
  if (mode === "alephbeta") {
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.stopTimeMinutes = wrap(start + length);
    first.playlistUrls = [
      "https://www.alephbeta.org/playlist/a-book-like-no-other [A Book Like No Other]",
      `${TORAH} [After Aleph Beta]`,
    ];
  }
  // "trailer": Aleph Beta's feed carries a 2m19s trailer among five items, so
  // it is the case that proves trailers are left out of the rotation.
  if (mode === "trailer") {
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.playlistUrls = ["https://rss.buzzsprout.com/2113502.rss [A Book Like No Other | sequential]"];
    first.podcastEpisodeMode = "Sequential";
  }
  // "locksub": a music entry that cannot play while locked, with a podcast
  // later in the same block. The substitute should come from the block.
  if (mode === "locksub") {
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.stopTimeMinutes = wrap(start + length);
    first.playlistUrls = [
      (cfg.defaultPlaylistUrls || [])[0],
      `${TORAH} [Podcast in this block]`,
    ];
  }
  // "locksub2": a music-only block, so the substitute has to be borrowed from
  // the Settings defaults instead.
  if (mode === "locksub2") {
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.stopTimeMinutes = wrap(start + length);
    first.playlistUrls = [(cfg.defaultPlaylistUrls || [])[0]];
  }
  // "musicend": a music entry in the middle of a queue. Nothing reports when a
  // playlist finishes, so the watcher has to notice and move on to entry 3.
  if (mode === "musicend") {
    const first = cfg.schedules[cfg.schedules.length - 1];
    first.stopTimeMinutes = wrap(start + 30);
    first.playlistUrls = [
      `${TORAH} [Before music]`,
      (cfg.defaultPlaylistUrls || [])[0],
      `${TORAH} [After music]`,
    ];
  }
}

await ref.set({ json: JSON.stringify(cfg), revision: revision + 1 }, { merge: true });
console.log(`revision ${revision + 1} | mode=${mode}` +
  (mode === "clean" ? "" : ` | device ${deviceNow} | block ${fmt(start)}-${fmt(stop)}`));

// Restarting the app syncs on launch; tapping "Sync now" by coordinate is
// unreliable because the layout shifts with scroll position.
//
// Twice on purpose. The first launch applies the config, but the reschedule
// that runs at start-up can race it and re-arm from the pre-sync schedules;
// the second launch re-arms from the updated store.
for (let i = 0; i < 2; i++) {
  execSync(`"${ADB}" shell am force-stop com.jasonschoenbrun.ytmtrigger`);
  await new Promise((r) => setTimeout(r, 3000));
  execSync(`"${ADB}" shell am start -n com.jasonschoenbrun.ytmtrigger/.ui.MainActivity`);
  await new Promise((r) => setTimeout(r, i === 0 ? 16000 : 12000));
}
const log = execSync(`"${ADB}" shell "run-as com.jasonschoenbrun.ytmtrigger tail -400 files/logs/$(date +%Y-%m-%d).log"`, { maxBuffer: 64 * 1024 * 1024 }).toString();
const applied = log.split("\n").filter((l) => l.includes("Applied remote config")).pop();
const armed = log.split("\n").filter((l) => l.includes("id=zz-queue-test")).pop();
console.log("  " + (applied || "no sync line").trim());
console.log("  " + (armed || "not armed").trim());
process.exit(0);
