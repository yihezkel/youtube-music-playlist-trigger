// Build the recommended blocks as app schedules and push them to the device
// config. Feed URLs come from podcast-stats.json so they are the same ones the
// catalog and schedule tabs were built from.
//
//   node build-schedules.mjs        - show what would be pushed
//   node build-schedules.mjs push   - write it
import admin from "firebase-admin";
import { readFileSync } from "node:fs";

const DOC = "users/<USER_ID>/devices/<DEVICE_ID>/data/config";
const stats = JSON.parse(readFileSync("podcast-stats.json", "utf8"));

import { BLOCKS, FILTERED_MEDIAN, MIN_MINUTES, MODE, MUSIC, queues } from "./schedule-blocks.mjs";

// Feed URLs that must never be committed - currently the rebuilt Aleph Beta
// feed, whose path carries a token so it is not discoverable. Looked up by show
// name, so schedule-blocks.mjs can name the show without holding the URL.
const privateFeeds = (() => {
  try { return JSON.parse(readFileSync("private-feeds.json", "utf8")); } catch { return {}; }
})();

/** The bracketed qualifiers for a show: mode, then any minimum length. */
const quals = (name) => [MODE[name], MIN_MINUTES[name] ? `min ${MIN_MINUTES[name]}` : null]
  .filter(Boolean)
  .map((q) => ` | ${q}`)
  .join("");

const feed = (name) => {
  if (privateFeeds[name]) {
    return `${privateFeeds[name]} [${name}${quals(name)}]`;
  }
  const s = stats.find((x) => x.name === name) || stats.find((x) => x.name.startsWith(name));
  if (!s?.feedUrl) throw new Error(`no feed for "${name}"`);
  return `${s.feedUrl} [${name}${quals(name)}]`;
};
const median = (name) => {
  // A filtered show's typical episode is longer than the feed's overall
  // median, so block sizing must use the filtered figure or it under-fills.
  if (FILTERED_MEDIAN[name]) return FILTERED_MEDIAN[name];
  if (privateFeeds[name]) return PRIVATE_MEDIAN[name] ?? 35;
  const s = stats.find((x) => x.name === name) || stats.find((x) => x.name.startsWith(name));
  return s?.durMedian ?? 0;
};
/** Typical episode length for feeds that are not in podcast-stats.json. */
const PRIVATE_MEDIAN = { "Aleph Beta": 36 };
admin.initializeApp({ credential: admin.credential.cert(JSON.parse(readFileSync("./service-account.json", "utf8"))) });
const db = admin.firestore();
const ref = db.doc(DOC);
const snap = await ref.get();
const cfg = JSON.parse(snap.get("json"));
const revision = Number(snap.get("revision") || 0);

const music = cfg.defaultPlaylistUrls || [];
const all = queues();
const built = all.map((q) => ({
  id: `sched-${q.appName.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`,
  name: q.appName,
  enabled: true,
  daysOfWeek: q.days,
  timeMinutes: q.block.start,
  stopTimeMinutes: q.block.stop,
  timeAnchor: q.block.anchor || "FixedClock",
  anchorOffsetMinutes: q.block.offset || 0,
  startsAfter: q.block.startsAfter || null,
  autoStopMinutes: q.block.autoStop || null,
  playlistUrls: q.shows.flatMap(([s]) => (s === MUSIC ? music : [feed(s)])),
  targetVolumePercent: 100,
  enableShuffle: true,
  skipFirstTrack: false,
  podcastEpisodeMode: q.block.mode || "Random",
  continuousPlay: true,
  lastPickedPlaylistIds: [],
}));

// Keep the user's own schedules but switch off the two that would now collide.
const keep = cfg.schedules
  .filter((s) => !s.name.startsWith("sched-") && !all.some((q) => q.appName === s.name))
  .map((s) => (["Afternoon", "Temp"].includes(s.name) ? { ...s, enabled: false } : s));

cfg.schedules = [...keep, ...built];

console.log(`${built.length} blocks built, ${keep.length} existing kept`);
for (const s of built) {
  console.log(`  ${s.name.padEnd(32)} d=${s.daysOfWeek.join("")} ${String(Math.floor(s.timeMinutes / 60)).padStart(2, "0")}:${String(s.timeMinutes % 60).padStart(2, "0")}` +
    `${s.stopTimeMinutes != null ? `-${String(Math.floor(s.stopTimeMinutes / 60)).padStart(2, "0")}:${String(s.stopTimeMinutes % 60).padStart(2, "0")}` : "-(shabat)"}` +
    ` ${s.timeAnchor === "FixedClock" ? "" : s.timeAnchor + "+" + s.anchorOffsetMinutes} entries=${s.playlistUrls.length} ${s.podcastEpisodeMode}`);
}
console.log("kept:", keep.map((s) => `${s.name}${s.enabled ? "" : " (disabled)"}`).join(", "));

// Queue depth. A queue shorter than its block gets replayed from the top, which
// is what made Sarah's day three laps of five shows. Music is unmeasured and
// always last, and it runs to the block's end, so a queue containing it is full
// by definition.
console.log("\nqueue depth (median episode lengths):");
let thin = 0, over = 0;
for (const q of all) {
  const B = q.block.mins;
  const hasMusic = q.shows.some(([s]) => s === MUSIC);
  const total = q.shows.reduce((n, [s]) => n + (s === MUSIC ? 0 : median(s)), 0);
  const tag = (q.block.id + " " + q.label).padEnd(34);
  // A block that ends with its queue is not judged on filling a window - it is
  // judged on landing near its nominal end, since nothing will cut it off.
  if (q.block.endsWithQueue) {
    const drift = total - B;
    const flag = Math.abs(drift) > 15 ? `  ENDS ${drift > 0 ? "+" : ""}${drift}m OFF TARGET` : "";
    if (Math.abs(drift) > 15) over++;
    console.log(`  ${tag} ${String(total).padStart(4)}m vs ${String(B).padStart(4)}m nominal  runs to the end${flag}`);
    continue;
  }
  if (q.block.autoStop) {
    console.log(`  ${tag} ${String(total).padStart(4)}m of ${String(q.block.autoStop).padStart(4)}m  stops after ${q.block.autoStop}m${hasMusic ? ", music fills the rest" : ""}`);
    continue;
  }
  if (B == null || hasMusic) {
    console.log(`  ${tag} ${String(total).padStart(4)}m  runs to block end`);
    continue;
  }
  const laps = total ? B / total : Infinity;
  const flag = laps > 1.35 ? "  THIN - replays" : "";
  if (laps > 1.35) thin++;
  console.log(`  ${tag} ${String(total).padStart(4)}m of ${String(B).padStart(4)}m  ${laps.toFixed(2)} laps${flag}`);
}
console.log(thin ? `\n${thin} queue(s) too thin for their block.` : "\nEvery timed queue outlasts its block.");
if (over) console.log(`${over} queue-ended block(s) land more than 15 min from their nominal end.`);

if (process.argv[2] === "push") {
  await ref.set({ json: JSON.stringify(cfg), revision: revision + 1 }, { merge: true });
  console.log(`\npushed revision ${revision + 1}`);
} else {
  console.log("\ndry run - pass 'push' to write");
}
process.exit(0);
