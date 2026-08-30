// Push a device config to Firestore. The app applies it on its next sync
// (or immediately via "Sync now" in the app).
//
//   node push-config.mjs test    - add a silent 2-entry queue for verification
//   node push-config.mjs clean   - remove the test schedule again
import admin from "firebase-admin";
import { CONFIG_DOC } from "./device.mjs";
import { readFileSync } from "node:fs";

const TORAH = "https://rss.buzzsprout.com/1566434.rss";

admin.initializeApp({ credential: admin.credential.cert(JSON.parse(readFileSync("./service-account.json", "utf8"))) });
const db = admin.firestore();
const ref = db.doc(CONFIG_DOC);

const snap = await ref.get();
const cfg = JSON.parse(snap.get("json"));
const revision = Number(snap.get("revision") || 0);
const mode = process.argv[2] || "show";

const TEST_NAME = "ZZ QueueTest";
cfg.schedules = cfg.schedules.filter((s) => s.name !== TEST_NAME);

if (mode === "test") {
  cfg.schedules.push({
    id: "zz-queue-test",
    name: TEST_NAME,
    enabled: true,
    daysOfWeek: [1, 2, 3, 4, 5, 6, 7],
    timeMinutes: Number(process.argv[4] ?? 12 * 60),
    stopTimeMinutes: process.argv[3] ? Number(process.argv[3]) : null,
    // Two entries so the chain has somewhere to go. Same short feed twice:
    // each visit re-picks a random episode, so they will differ.
    playlistUrls: [`${TORAH} [Torah 1]`, `${TORAH} [Torah 2]`],
    targetVolumePercent: 0,          // silent: this is being run at night
    autoStopMinutes: null,
    enableShuffle: false,
    skipFirstTrack: false,
    podcastEpisodeMode: process.argv[5] || "Random",
    continuousPlay: true,
    lastPickedPlaylistIds: [],
  });
}

if (mode === "test" || mode === "clean") {
  await ref.set({ json: JSON.stringify(cfg), revision: revision + 1 }, { merge: true });
  console.log(`wrote revision ${revision + 1}; schedules now: ${cfg.schedules.map((s) => s.name).join(", ")}`);
} else {
  console.log(`revision ${revision}; schedules: ${cfg.schedules.map((s) => s.name).join(", ")}`);
}
process.exit(0);
