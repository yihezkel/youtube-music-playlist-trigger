// Generate Google Home script-editor automations from the same source of truth
// the app and the sheet come from.
//
//   node google-home-automations.mjs > google-home-automations.yaml
//
// This is a deliberate approximation, not a port. Google Home can express when
// to start and roughly what to play; it cannot express most of what the app
// does. What it can and cannot do is set out in the header of the generated
// file, so the gap is visible to whoever pastes it in.
//
// The one non-negotiable is Shabat and Yom Tov. Google Home has no Jewish
// calendar, so the blocks that sit against those edges - erev Shabat and both
// motzaei Shabat blocks - are not generated at all rather than emitted with a
// caveat, and a Yom Tov warning is printed for the weekday ones, which will
// happily play through a festival unless they are turned off.
import { readFileSync } from "node:fs";
import { BLOCKS, MUSIC, isPlaylist, playlistName, queues } from "./schedule-blocks.mjs";

const legacy = JSON.parse(readFileSync("sheet-legacy.json", "utf8"));
const norm = (s) => String(s).toLowerCase().replace(/\(.*?\)/g, " ")
  .replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();

// Shows the sheet records as having sat in one of the old Assistant routines,
// so they are known to play on a speaker by name. Anything else is a guess, and
// a guess that fails is silence in a kitchen.
const provenOnHome = new Set(Object.keys(legacy.slots || {}).map(norm));

const SPEAKER = "SPEAKER NAME - ROOM";
const DAY = { 7: "SUN", 1: "MON", 2: "TUE", 3: "WED", 4: "THU", 5: "FRI", 6: "SAT" };
const hhmm = (m) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

// Blocks that touch Shabat or Yom Tov. Not emitted; see the header.
const OBSERVANCE_BLOCKS = new Set(["F", "G", "H"]);

const lines = [];
const out = (s = "") => lines.push(s);

out("# Google Home automations for the YTM Trigger schedule");
out("#");
out("# GENERATED from tools/schedule-blocks.mjs - the same file the app config and");
out("# the Schedule tab come from. Regenerate rather than editing by hand:");
out("#   node tools/google-home-automations.mjs > google-home-automations.yaml");
out("#");
out("# Paste into the script editor at https://home.google.com/automations");
out("# (Public Preview). Replace every occurrence of:");
out(`#   ${SPEAKER}`);
out("# with the speaker exactly as the Home app names it, including the room.");
out("#");
out("# ---------------------------------------------------------------------------");
out("# READ THIS BEFORE TURNING THEM ON");
out("#");
out("# Shabat and Yom Tov. Google Home has no Jewish calendar and no way to");
out("# express one. These automations run on weekdays only, so they will not fire");
out("# on Shabat - but they WILL fire on Yom Tov, including the ones that fall on");
out("# a Sunday to Thursday. You have to turn them off for a festival yourself.");
out("# The erev Shabat and motzaei Shabat blocks are deliberately NOT generated:");
out("# their times depend on nightfall and on whether a festival runs into Shabat,");
out("# which only the app works out.");
out("#");
out("# Your own playlists probably will not work. Google's documentation says an");
out("# Assistant action needing Voice Match or personal results does not run in a");
out("# household automation, and a personal YouTube Music playlist is exactly");
out("# that. Named podcasts are public and should be fine. Each automation below");
out("# therefore asks for a show the sheet records as having actually played on");
out("# your old Assistant routines, not a playlist.");
out("#");
out("# One show, not a queue. A Home automation issues a single command. The app");
out("# plays a queue of a dozen shows, picks the episode, skips one it cannot");
out("# finish, resumes what was cut off and moves on when a stream dies. None of");
out("# that exists here: this starts one show at the right time and stops at the");
out("# end of the block.");
out("#");
out("# PASTE ONE AT A TIME. The script editor takes a single automation per");
out("# script - one metadata block and one automations block. Each numbered");
out("# section below is its own script; create a new automation for each and");
out("# paste only that section.");
out("#");
out("# Do not run these and the app at once on the same speaker. They will talk");
out("# over each other. Use these only if the phone is out of action, or point");
out("# them at a speaker the phone does not drive.");
out("# ---------------------------------------------------------------------------");
out();

let emitted = 0;
const skipped = [];
const noProvenShow = [];
const unverified = [];

for (const block of BLOCKS) {
  if (OBSERVANCE_BLOCKS.has(block.id)) {
    skipped.push(`${block.id} ${block.name} - anchored to Shabat/Yom Tov`);
    continue;
  }
  for (const q of queues().filter((x) => x.block.id === block.id)) {
    const days = q.days.filter((d) => d !== 5 && d !== 6);
    if (!days.length) continue;

    // Prefer a show the sheet records as having played on a speaker. Failing
    // that, take the first podcast anyway and mark it unverified: a block with
    // no automation at all is silence, which is the failure this whole project
    // exists to avoid, and a name that does not resolve is easy to spot once.
    const podcasts = q.shows.map(([s]) => s).filter((s) => s !== MUSIC && !isPlaylist(s));
    const proven = podcasts.find((s) => provenOnHome.has(norm(s)));
    const candidate = proven ?? podcasts[0];
    if (!candidate) { noProvenShow.push(`${q.appName} - no podcast in the queue`); continue; }
    if (!proven) unverified.push(`${q.appName}: ${candidate}`);

    emitted++;
    out(`# ===== ${emitted}. ${q.appName} ${"=".repeat(Math.max(3, 50 - q.appName.length))}`);
    if (!proven) {
      out(`# UNVERIFIED: "${candidate}" is not recorded as having played on a speaker.`);
      out(`# Say "Hey Google, play ${candidate}" once and check it resolves.`);
    }
    out(`metadata:`);
    out(`  name: ${q.appName}`);
    out(`  description: ${block.name}, ${block.time}. Generated from schedule-blocks.mjs.`);
    out(`automations:`);
    out(`  starters:`);
    out(`  - type: time.schedule`);
    out(`    at: ${hhmm(block.start)}`);
    out(`    weekdays:`);
    for (const d of days) out(`    - ${DAY[d]}`);
    out(`  actions:`);
    out(`  - type: assistant.command.OkGoogle`);
    out(`    okGoogle: Play ${candidate} on ${SPEAKER.split(" - ")[0]}`);
    out(`    devices: ${SPEAKER}`);
    out();

    if (block.stop != null) {
      emitted++;
      out(`# ===== ${emitted}. ${q.appName} - stop ${"=".repeat(Math.max(3, 43 - q.appName.length))}`);
      out(`metadata:`);
      out(`  name: ${q.appName} - stop`);
      out(`  description: Ends the block at ${hhmm(block.stop)}, as the app does.`);
      out(`automations:`);
      out(`  starters:`);
      out(`  - type: time.schedule`);
      out(`    at: ${hhmm(block.stop)}`);
      out(`    weekdays:`);
      for (const d of days) out(`    - ${DAY[d]}`);
      out(`  actions:`);
      out(`  - type: assistant.command.OkGoogle`);
      out(`    okGoogle: Stop`);
      out(`    devices: ${SPEAKER}`);
      out();
    }
  }
}

out("# ---------------------------------------------------------------------------");
out(`# ${emitted} automations generated.`);
for (const s of skipped) out(`# Not generated: ${s}`);
for (const s of noProvenShow) out(`# Not generated: ${s}`);
if (unverified.length) { out("#"); out("# Unverified show names - check each resolves on a speaker once:"); for (const s of unverified) out(`#   ${s}`); }
out("# ---------------------------------------------------------------------------");

console.log(lines.join("\n"));
