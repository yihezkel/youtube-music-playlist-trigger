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
cfg.schedules = cfg.schedules.filter((s) => s.name !== NAME);

const deviceNow = execSync(`"${ADB}" shell "date +%H:%M"`).toString().trim();
const [hh, mm] = deviceNow.split(":").map(Number);
const start = hh * 60 + mm + lead;
const stop = start + length;
const fmt = (m) => `${String(Math.floor(m / 60) % 24).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

if (mode !== "clean") {
  const urls = mode === "guard"
    ? [`${TORAH} [Short clip]`, `${SHORTWAVE} [Long episode]`]
    : mode === "modes"
      // Schedule-level mode is Random below, so if per-entry parsing works the
      // log must show Latest and Sequential with modeFrom=entry.
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
    podcastEpisodeMode: mode === "modes" ? "Random" : "Latest",
    continuousPlay: true,
    lastPickedPlaylistIds: [],
  });
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
const log = execSync(`"${ADB}" shell "run-as com.jasonschoenbrun.ytmtrigger cat files/logs/$(date +%Y-%m-%d).log"`).toString();
const applied = log.split("\n").filter((l) => l.includes("Applied remote config")).pop();
const armed = log.split("\n").filter((l) => l.includes("id=zz-queue-test")).pop();
console.log("  " + (applied || "no sync line").trim());
console.log("  " + (armed || "not armed").trim());
process.exit(0);
