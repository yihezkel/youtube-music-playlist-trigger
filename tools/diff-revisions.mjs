// Diff the show set between consecutive revisions to produce added/removed rows.
//
// Only revisions up to the point this session began are treated as the user's
// own history; the 2026 edits are this assistant's and are logged separately
// from what it actually did rather than inferred from a diff.
import { readFileSync, writeFileSync } from "node:fs";

const CUTOFF = "2026-08-25"; // this session's first edit
const revs = JSON.parse(readFileSync("revision-shows.json", "utf8"))
  .filter((r) => r.date < CUTOFF)
  .sort((a, b) => a.modifiedTime.localeCompare(b.modifiedTime));

// A show flickering in and out of consecutive milestones is usually a layout
// shuffle rather than a real decision, so require it to stay changed.
const STICKY = 1;

// The lower halves of these tabs mix show names with free-text notes in the
// same columns, so the diff picks up both. Notes are not changes to the
// line-up - but they are where the reasons are written, so they are kept and
// matched back to the show changed around the same time.
const NOTE_MARKERS = [
  "recommended by", "no new episodes", "can do ", "can't find", "cant find",
  "swap with", "only available", "too much", "actually cancel", "i think we",
  "replace with", "shifted to", "move to", "consider ", "reevaluate",
  "seems ", "not sure", "better to listen", "multiple/day", "every 2 weeks",
  "about once", "generally ", "usually ", "/year", "/week", "don't", "dont ",
  "stuff we", "anymore", "not as educational", "in depth", "world economics",
  "said", "can also be done", "find something", "multiples on", "no value",
  "redo this", "this row",
];
function looksLikeNote(s) {
  const l = s.toLowerCase();
  // Section headings and stray cells from the tabs' own structure.
  if (["doing", "done", "removed", "ideas", "not done"].includes(l)) return true;
  if (/^change to \d+%$/i.test(l)) return true;
  if (NOTE_MARKERS.some((m) => l.includes(m))) return true;
  if (/[;]/.test(s)) return true;
  if (/^\d+x?\//.test(l)) return true;
  // Day patterns such as "Mon+Wed+Fri" or "Tues+Wed+Thurs+Sat" sit in the same
  // columns as show names on the Weekly tab.
  if (/\+/.test(s)) return true;
  if (/^(sun|mon|tues?|wed|thur?s?|fri|sat|shabb?os|motza)\b/i.test(s)) return true;
  // A comma plus several words reads as prose rather than a title.
  if (s.includes(",") && s.split(/\s+/).length > 4) return true;
  return false;
}

const changes = [];
const notes = [];
for (let i = 1; i < revs.length; i++) {
  const prev = new Set(Object.keys(revs[i - 1].shows));
  const cur = new Set(Object.keys(revs[i].shows));
  const later = revs.slice(i + 1, i + 1 + STICKY).map((r) => new Set(Object.keys(r.shows)));

  for (const k of cur) {
    if (prev.has(k)) continue;
    if (later.length && !later.every((s) => s.has(k))) continue;
    const text = revs[i].shows[k];
    if (looksLikeNote(text)) notes.push({ date: revs[i].date, text });
    else changes.push({ date: revs[i].date, change: "Added", show: text, key: k });
  }
  for (const k of prev) {
    if (cur.has(k)) continue;
    if (later.length && later.some((s) => s.has(k))) continue;
    const text = revs[i - 1].shows[k];
    if (looksLikeNote(text)) continue; // a note being tidied away is not a change
    changes.push({ date: revs[i].date, change: "Removed", show: text, key: k });
  }
}

console.log(`revisions considered: ${revs.length} (${revs[0].date} .. ${revs[revs.length - 1].date})`);
console.log(`changes detected: ${changes.length}\n`);
const byDate = {};
for (const c of changes) (byDate[c.date] ??= []).push(c);
for (const d of Object.keys(byDate).sort().reverse()) {
  console.log(d);
  for (const c of byDate[d]) console.log(`   ${c.change.padEnd(7)} ${c.show}`);
}
console.log(`\nnotes captured (reason candidates): ${notes.length}`);
for (const n of notes.slice(-14)) console.log(`   ${n.date}  ${n.text}`);
writeFileSync("revision-changes.json", JSON.stringify({ changes, notes }, null, 1));
