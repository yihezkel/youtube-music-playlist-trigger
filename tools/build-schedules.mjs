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

// Per-entry episode mode. News is only useful newest-first; serialised shows
// that number their parts have to run in order; everything else is an
// evergreen archive and is best shuffled. Without this a whole block had to
// share one setting, which is why Business Wars played "part 1" one morning
// and "part 5" of a different series the same afternoon.
const MODE = {
  "Up First (NPR)": "newest",
  "Short Wave (NPR)": "newest",
  "The Indicator from Planet Money": "newest",
  "TED Talks Daily": "newest",
  "This American Life": "newest",
  "Call Me Back": "newest",
  "Meaningful People": "newest",
  "Business Wars": "sequential",
  "Business Movers": "sequential",
  "Unpacking Israeli History": "sequential",
  "A Book Like No Other (Aleph Beta)": "sequential",
};

const feed = (name) => {
  const s = stats.find((x) => x.name === name) || stats.find((x) => x.name.startsWith(name));
  if (!s?.feedUrl) throw new Error(`no feed for "${name}"`);
  const mode = MODE[name];
  return `${s.feedUrl} [${name}${mode ? ` | ${mode}` : ""}]`;
};

// ISO day numbers, as the app stores them.
const SUN = 7, MON = 1, TUE = 2, WED = 3, THU = 4, FRI = 5, SAT = 6;
const WEEKDAYS = [SUN, MON, TUE, WED, THU];
const hm = (h, m = 0) => h * 60 + m;

// Music comes from the user's existing YT Music playlists, which live in the
// config's defaults; resolved at push time so renames there follow through.
const MUSIC = "__MUSIC__";

const BLOCKS = [
  { name: "A Morning Launch", days: WEEKDAYS, start: hm(7, 30), stop: hm(8, 0), mode: "Random",
    shows: ["TorahAnytime Daily Dose", "Up First (NPR)", "Short Wave (NPR)"] },

  { name: "B Sarah Sun", days: [SUN], start: hm(8), stop: hm(15, 50), mode: "Random",
    shows: ["The Mindset Mentor", "Business Wars", "How I Built This with Guy Raz", "Hidden Brain", "Freakonomics Radio"] },
  { name: "B Sarah Mon", days: [MON], start: hm(8), stop: hm(15, 50), mode: "Random",
    shows: ["The Mindset Mentor", "Meaningful People", "Planet Money", "This American Life"] },
  { name: "B Sarah Tue", days: [TUE], start: hm(8), stop: hm(15, 50), mode: "Random",
    shows: ["The Mindset Mentor", "Business Movers", "Cautionary Tales with Tim Harford", "Call Me Back", "Revisionist History"] },
  { name: "B Sarah Wed", days: [WED], start: hm(8), stop: hm(15, 50), mode: "Random",
    shows: ["The Mindset Mentor", "Business Wars", "Unpacking Israeli History", "SeforimChatter", "Hidden Brain"] },
  { name: "B Sarah Thu", days: [THU], start: hm(8), stop: hm(15, 50), mode: "Random",
    shows: ["The Mindset Mentor", "How I Built This with Guy Raz", "Jewish History Nerds", "Stuff You Should Know", "Planet Money"] },

  // Music sits last on purpose: a YT Music entry hands control to YT Music,
  // which never returns, so anything after it in a queue would never play.
  { name: "C Landing", days: WEEKDAYS, start: hm(16), stop: hm(16, 30), mode: "Random",
    shows: ["Who Smarted?", MUSIC] },

  { name: "D Family Sun", days: [SUN], start: hm(16, 30), stop: hm(19, 30), mode: "Random",
    shows: ["A Book Like No Other (Aleph Beta)", "SciShow Tangents", "Business Wars", "Stuff You Should Know"] },
  { name: "D Family Mon", days: [MON], start: hm(16, 30), stop: hm(19, 30), mode: "Random",
    shows: ["Jews You Should Know", "Planet Money", "99% Invisible", "Wow in the World"] },
  { name: "D Family Tue - Rabbi Breitowitz", days: [TUE], start: hm(16, 30), stop: hm(19, 30), mode: "Random",
    shows: ["The Q & A with Rabbi Breitowitz", "The Indicator from Planet Money", "Greeking Out from National Geographic Kids", "SciShow Tangents"] },
  { name: "D Family Wed", days: [WED], start: hm(16, 30), stop: hm(19, 30), mode: "Random",
    shows: ["Cautionary Tales with Tim Harford", "Revisionist History", "Stuff You Should Know", "Who Smarted?"] },
  { name: "D Family Thu - Rabbi Breitowitz", days: [THU], start: hm(16, 30), stop: hm(19, 30), mode: "Random",
    shows: ["The Q & A with Rabbi Breitowitz", "Behind the Bima", "Smash Boom Best"] },

  { name: "E Teen Sun", days: [SUN], start: hm(19, 30), stop: hm(21, 30), mode: "Random",
    shows: ["Orthodox Conundrum", "StarTalk Radio", "TED Talks Daily"] },
  { name: "E Teen Mon", days: [MON], start: hm(19, 30), stop: hm(21, 30), mode: "Random",
    shows: ["The School of Greatness", "Something You Should Know", "TED Talks Daily"] },
  { name: "E Teen Tue", days: [TUE], start: hm(19, 30), stop: hm(21, 30), mode: "Random",
    shows: ["18Forty", "The Indicator from Planet Money", "TED Talks Daily"] },
  { name: "E Teen Wed", days: [WED], start: hm(19, 30), stop: hm(21, 30), mode: "Random",
    shows: ["SeforimChatter", "Unpacking Israeli History", "TED Talks Daily"] },
  { name: "E Teen Thu", days: [THU], start: hm(19, 30), stop: hm(21, 30), mode: "Random",
    shows: ["Call Me Back", "Freakonomics Radio", "TED Talks Daily"] },

  // No stop time: the Shabat gate and the pre-Shabat mute end this block.
  { name: "F Erev Shabat", days: [FRI], start: hm(15, 10), stop: null, mode: "Random",
    shows: ["A Book Like No Other (Aleph Beta)", "Parsha Perspectives", MUSIC] },

  // Anchored to nightfall rather than the clock, so it tracks the year.
  { name: "G Motzaei Shabat - kids", days: [SAT], start: hm(20), stop: hm(20, 30), mode: "Random",
    anchor: "ShabatYomTovEnd", offset: 30,
    shows: ["TorahAnytime Daily Dose", "Jewish History Nerds", MUSIC] },
  { name: "H Motzaei Shabat - teen", days: [SAT], start: hm(20, 30), stop: hm(21, 30), mode: "Latest",
    shows: ["Meaningful People", "TED Talks Daily"] },
];

admin.initializeApp({ credential: admin.credential.cert(JSON.parse(readFileSync("./service-account.json", "utf8"))) });
const db = admin.firestore();
const ref = db.doc(DOC);
const snap = await ref.get();
const cfg = JSON.parse(snap.get("json"));
const revision = Number(snap.get("revision") || 0);

const music = cfg.defaultPlaylistUrls || [];
const built = BLOCKS.map((b) => ({
  id: `sched-${b.name.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`,
  name: b.name,
  enabled: true,
  daysOfWeek: b.days,
  timeMinutes: b.start,
  stopTimeMinutes: b.stop,
  timeAnchor: b.anchor || "FixedClock",
  anchorOffsetMinutes: b.offset || 0,
  playlistUrls: b.shows.flatMap((s) => (s === MUSIC ? music : [feed(s)])),
  targetVolumePercent: 100,
  autoStopMinutes: null,
  enableShuffle: true,
  skipFirstTrack: false,
  podcastEpisodeMode: b.mode,
  continuousPlay: true,
  lastPickedPlaylistIds: [],
}));

// Keep the user's own schedules but switch off the two that would now collide.
const keep = cfg.schedules
  .filter((s) => !s.name.startsWith("sched-") && !BLOCKS.some((b) => b.name === s.name))
  .map((s) => (["Afternoon", "Temp"].includes(s.name) ? { ...s, enabled: false } : s));

cfg.schedules = [...keep, ...built];

console.log(`${built.length} blocks built, ${keep.length} existing kept`);
for (const s of built) {
  console.log(`  ${s.name.padEnd(32)} d=${s.daysOfWeek.join("")} ${String(Math.floor(s.timeMinutes / 60)).padStart(2, "0")}:${String(s.timeMinutes % 60).padStart(2, "0")}` +
    `${s.stopTimeMinutes != null ? `-${String(Math.floor(s.stopTimeMinutes / 60)).padStart(2, "0")}:${String(s.stopTimeMinutes % 60).padStart(2, "0")}` : "-(shabat)"}` +
    ` ${s.timeAnchor === "FixedClock" ? "" : s.timeAnchor + "+" + s.anchorOffsetMinutes} entries=${s.playlistUrls.length} ${s.podcastEpisodeMode}`);
}
console.log("kept:", keep.map((s) => `${s.name}${s.enabled ? "" : " (disabled)"}`).join(", "));

if (process.argv[2] === "push") {
  await ref.set({ json: JSON.stringify(cfg), revision: revision + 1 }, { merge: true });
  console.log(`\npushed revision ${revision + 1}`);
} else {
  console.log("\ndry run - pass 'push' to write");
}
process.exit(0);
