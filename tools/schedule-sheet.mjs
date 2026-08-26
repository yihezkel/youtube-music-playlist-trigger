// Build the "Recommended Schedule" tab.
//
// Blocks are queues, not timetables: each block has a start and a stop, and its
// shows play back to back until the stop. Episode lengths vary threefold within
// a single show, so per-episode times cannot fill a window continuously.
import { readFileSync } from "node:fs";
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const TAB = "Recommended Schedule";
const stats = JSON.parse(readFileSync("podcast-stats.json", "utf8"));
const legacy = JSON.parse(readFileSync("sheet-legacy.json", "utf8"));

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (m, u, d) => client.request({ method: m, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}`, data: d });

const find = (n) => stats.find((s) => s.name === n) || stats.find((s) => s.name.startsWith(n));
const mins = (n) => find(n)?.durMedian ?? null;
const label = (n) => (n === MUSIC ? "—" : (mins(n) ? `${mins(n)}m` : "—"));

import { BLOCKS, MUSIC, modeLabel, queues } from "./schedule-blocks.mjs";

// Music is a set of YT Music playlists in the app rather than a feed, so it has
// no duration and no episode mode; everything else is looked up by name.
const showName = (s) => (s === MUSIC ? "Music — your YTM playlists" : s);

const WEEK = [
  ["Block", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Motzaei Shabat"],
  ["A · Morning Launch\n07:30–08:00 · Kids", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "(kids leave later)", "—"],
  ["B · Sarah's Day\n08:00–15:50", "Mindset + business", "Mindset + people", "Mindset + ideas", "Mindset + Israel", "Mindset + founders", "(short — erev Shabat)", "—"],
  ["C · Landing\n16:00–16:30 · Kids", "Who Smarted? + music", "Who Smarted? + music", "Who Smarted? + music", "Who Smarted? + music", "Who Smarted? + music", "—", "—"],
  ["D · Family Table\n16:30–19:30 · Kids + Sarah", "Torah & Science", "People & Stories", "★ Rabbi Breitowitz", "Story & Design", "★ Breitowitz & Community", "—", "—"],
  ["E · Teen Evening\n19:30 – about 21:30 · 15-year-old", "Orthodox Conundrum\n+ StarTalk", "School of Greatness\n+ SYSK", "18Forty", "SeforimChatter\n+ Israeli History", "Call Me Back\n+ Freakonomics", "—", "—"],
  ["F/G/H · Shabat edges", "—", "—", "—", "—", "—", "Parsha + music\nfrom ~15:10", "Kids: ends+30, ~40 min\nTeen: follows, ~76 min"],
];

const rows = [];
const fmt = [];
const push = (arr, kind) => { rows.push(arr); if (kind) fmt.push({ r: rows.length - 1, kind }); };

push(["Recommended Podcast Schedule"], "title");
push([`Built ${new Date().toISOString().slice(0, 10)} · kids 07:30–08:00 and 16:00–19:30 · Sarah 08:00–15:50 and 16:30–19:30 · 15-year-old to 21:30 · Fridays the kids are home by 15:00 (about 12:00 once the clocks go back)`], "sub");
push([]);

push(["1 · The day at a glance"], "section");
push(["Block", "Time", "Who's home", "Roughly fills", "The idea"], "head");
for (const b of BLOCKS) push([`${b.id} · ${b.name}`, b.time, b.who, b.mins ? `${Math.floor(b.mins / 60)}h ${b.mins % 60}m` : "varies", b.idea]);
push([]);

push(["2 · The week"], "section");
push(WEEK[0], "head");
for (const r of WEEK.slice(1)) push(r, "week");
push([]);

push(["3 · What plays, in order"], "section");
push(["Block / day", "#", "Show", "Typical", "Episode", "Why it's here"], "head");
for (const q of queues()) {
  push([`${q.block.id} · ${q.block.name} — ${q.label}`], "sub2");
  q.shows.forEach(([show, why], i) =>
    push(["", String(i + 1), showName(show), label(show), modeLabel(show), why]));
  const total = q.shows.reduce((s, [n]) => s + (n === MUSIC ? 0 : (mins(n) || 0)), 0);
  push(["", "", "Queue length (median episodes)", `${Math.floor(total / 60)}h ${total % 60}m`, "",
    q.block.endsWithQueue
      ? `Nothing cuts this block off — it ends with its last episode, at about ${Math.floor(q.block.mins / 60)}h ${q.block.mins % 60}m`
      : (q.block.mins ? `Block is ${Math.floor(q.block.mins / 60)}h ${q.block.mins % 60}m — the queue is sized to outlast it, so nothing is replayed` : "Runs to the block's end")], "total");
}
push([]);

push(["4 · How it plays"], "section");
[
  ["Continuous, not timed", "Each block is a queue. When an episode ends the next starts immediately, so a 22-minute episode and a 78-minute one both simply flow on. Only the block's start and stop are fixed."],
  ["Going round again", "Each queue is now sized to outlast its block, so in normal running nothing repeats. If a block does outlive its queue — episodes shorter than usual, say — it restarts from the top: random entries draw a different episode and sequential entries advance, while a 'newest' entry is passed over, because the newest episode is still the same episode it already played."],
  ["Random vs newest", "'Random' draws from the whole back catalogue — right for evergreen shows and archives. 'Newest' is for news and for feeds that mix formats, where a random pick lands on the wrong thing."],
  ["Shabat and Yom Tov", "No Friday end time is needed. The app blocks all playback for Shabat and Yom Tov and mutes the speaker 15 minutes before it begins."],
  ["Blocks that end with their queue", "The last block of a day has no stop time. It plays each show once and finishes with the last episode instead of being cut off mid-sentence, so its queue is sized to land near the nominal end rather than to outlast it. That covers the teen evening on weeknights, and both motzaei Shabat blocks."],
  ["Motzaei Shabat", "The kids' block starts 30 minutes after Shabat ends and simply plays its queue, about 40 minutes, whatever the season. The teen block then starts when the kids' block finishes rather than at a clock time. It used to stop at a fixed 20:30, which left the kids nine minutes in late August and two and a half hours to fill with music in December."],
  ["Music", "Music entries mean your existing YTM Trigger playlists. Rotating them keeps the kids' blocks from feeling like school. YouTube Music does not tell the app when a playlist ends, so the app checks every five minutes and moves the queue on when it has — which means a block no longer falls silent if a playlist runs out, and music no longer has to be the last thing in a queue."],
].forEach(([k, v]) => push(["", k, v], "note"));
push([]);

push(["5 · Things you should know before we build this"], "section");
push(["Issue", "Detail"], "head");
[
  ["Your sheet disagrees with the hours you gave me",
   "The Weekly tab said: 'Hallel gets home about 13:45 every day', 'Miryam gets home about 14:00 every day', 'Aharon gets home 17:40–18:00 every day, except Tuesdays at 15:25'. You told me the kids are around from 16:00. I have built to what you told me, but if those older lines are still true then roughly two hours of Sarah's block each afternoon actually has children in it, and should be family content instead."],
  ["A Book Like No Other has only 5 episodes",
   "It carries the Sunday and Friday Torah slots but the feed is tiny, so random play will repeat quickly. Worth pairing with another Aleph Beta feed or accepting the repetition."],
  ["Jews You Should Know needs length-aware picking",
   "Your note asked for it in a small slot on Sunday and a long slot after Wednesday. The feed mixes 3-minute Friday episodes with 45–100 minute interviews, and neither Google Home nor the app can currently filter by length — so it is scheduled as one Monday slot for now."],
].forEach((r) => push(r, "change"));
push([]);

push(["6 · Change history"], "section");
push(["", "Every addition, removal and timing change now lives on the 'Schedule change log' tab, " +
  "newest first, with the reason where one was written down."], "note");
push([]);
push(["Applied to Google Home and to the YTM Trigger app. The app is the live system; " +
  "the Google Home podcast routines are still running in parallel until you delete them."], "sub");

// ---------------------------------------------------------------- write
const meta = (await api("GET", "?fields=sheets.properties")).data;
const old = meta.sheets.find((s) => s.properties.title === TAB);
if (old) await api("POST", ":batchUpdate", { requests: [{ deleteSheet: { sheetId: old.properties.sheetId } }] });
const made = await api("POST", ":batchUpdate", { requests: [{ addSheet: { properties: {
  title: TAB, gridProperties: { rowCount: rows.length + 20, columnCount: 8, frozenRowCount: 2 } } } }] });
const sheetId = made.data.replies[0].addSheet.properties.sheetId;
await api("PUT", `/values/${encodeURIComponent(TAB)}!A1?valueInputOption=RAW`, { values: rows });

const C = {
  navy: { red: 0.11, green: 0.20, blue: 0.36 },
  band: { red: 0.90, green: 0.93, blue: 0.98 },
  head: { red: 0.85, green: 0.89, blue: 0.95 },
  sub2: { red: 0.95, green: 0.96, blue: 0.90 },
  tot: { red: 0.97, green: 0.97, blue: 0.97 },
  white: { red: 1, green: 1, blue: 1 },
};
const req = [
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 0, endIndex: 1 }, properties: { pixelSize: 250 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 1, endIndex: 2 }, properties: { pixelSize: 210 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 2, endIndex: 3 }, properties: { pixelSize: 270 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 3, endIndex: 5 }, properties: { pixelSize: 115 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 5, endIndex: 6 }, properties: { pixelSize: 560 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 6, endIndex: 8 }, properties: { pixelSize: 200 }, fields: "pixelSize" } },
  { repeatCell: { range: { sheetId, startRowIndex: 0, endRowIndex: rows.length, startColumnIndex: 0, endColumnIndex: 8 },
      cell: { userEnteredFormat: { wrapStrategy: "WRAP", verticalAlignment: "TOP", textFormat: { fontSize: 10 } } },
      fields: "userEnteredFormat(wrapStrategy,verticalAlignment,textFormat)" } },
];
for (const { r, kind } of fmt) {
  const range = { sheetId, startRowIndex: r, endRowIndex: r + 1, startColumnIndex: 0, endColumnIndex: 8 };
  const rowH = (px) => ({ updateDimensionProperties: { range: { sheetId, dimension: "ROWS", startIndex: r, endIndex: r + 1 }, properties: { pixelSize: px }, fields: "pixelSize" } });
  if (kind === "title") {
    req.push({ mergeCells: { range, mergeType: "MERGE_ALL" } },
      { repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.navy, textFormat: { bold: true, fontSize: 18, foregroundColor: C.white }, verticalAlignment: "MIDDLE" } }, fields: "userEnteredFormat(backgroundColor,textFormat,verticalAlignment)" } }, rowH(46));
  } else if (kind === "sub") {
    req.push({ mergeCells: { range, mergeType: "MERGE_ALL" } },
      { repeatCell: { range, cell: { userEnteredFormat: { textFormat: { italic: true, fontSize: 10, foregroundColor: { red: 0.35, green: 0.35, blue: 0.35 } } } }, fields: "userEnteredFormat.textFormat" } });
  } else if (kind === "section") {
    req.push({ mergeCells: { range, mergeType: "MERGE_ALL" } },
      { repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.band, textFormat: { bold: true, fontSize: 13, foregroundColor: C.navy }, verticalAlignment: "MIDDLE" } }, fields: "userEnteredFormat(backgroundColor,textFormat,verticalAlignment)" } }, rowH(34));
  } else if (kind === "head") {
    req.push({ repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.head, textFormat: { bold: true } } }, fields: "userEnteredFormat(backgroundColor,textFormat)" } });
  } else if (kind === "sub2") {
    req.push({ mergeCells: { range, mergeType: "MERGE_ALL" } },
      { repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.sub2, textFormat: { bold: true } } }, fields: "userEnteredFormat(backgroundColor,textFormat)" } });
  } else if (kind === "total") {
    req.push({ repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.tot, textFormat: { italic: true } } }, fields: "userEnteredFormat(backgroundColor,textFormat)" } });
  } else if (kind === "week") {
    req.push(rowH(56), { repeatCell: { range: { ...range, endColumnIndex: 1 }, cell: { userEnteredFormat: { textFormat: { bold: true } } }, fields: "userEnteredFormat.textFormat" } });
  } else if (kind === "change") {
    req.push({ repeatCell: { range: { ...range, endColumnIndex: 1 }, cell: { userEnteredFormat: { textFormat: { bold: true } } }, fields: "userEnteredFormat.textFormat" } });
  } else if (kind === "note") {
    req.push({ repeatCell: { range: { ...range, startColumnIndex: 1, endColumnIndex: 2 }, cell: { userEnteredFormat: { textFormat: { bold: true } } }, fields: "userEnteredFormat.textFormat" } });
  }
}
await api("POST", ":batchUpdate", { requests: req });
console.log(`wrote "${TAB}": ${rows.length} rows`);

const unmatched = [...new Set(queues().flatMap((q) => q.shows.map(([n]) => n)))]
  .filter((n) => n !== MUSIC && !find(n));
console.log(unmatched.length ? `UNMATCHED SHOWS: ${unmatched.join(", ")}` : "all show names resolve");
