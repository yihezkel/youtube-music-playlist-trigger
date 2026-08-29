// Build the "Schedule" tab.
//
// Blocks are queues, not timetables: each block has a start and a stop, and its
// shows play back to back until the stop. Episode lengths vary threefold within
// a single show, so per-episode times cannot fill a window continuously.
import { readFileSync } from "node:fs";
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const TAB = "Schedule";
/** What the tab was called before; renamed in place on the next run. */
const PREVIOUS_TAB = "Recommended Schedule";
const stats = JSON.parse(readFileSync("podcast-stats.json", "utf8"));
const legacy = JSON.parse(readFileSync("sheet-legacy.json", "utf8"));

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (m, u, d) => client.request({ method: m, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}`, data: d });

const find = (n) => stats.find((s) => s.name === n) || stats.find((s) => s.name.startsWith(n));
const mins = (n) => find(n)?.durMedian ?? PRIVATE_MEDIAN[n] ?? null;
/** Typical length for feeds that live outside podcast-stats.json. */
const PRIVATE_MEDIAN = { "Aleph Beta": 36 };
const label = (n) => (n === MUSIC || isPlaylist(n) ? "—" : (mins(n) ? `${mins(n)}m` : "—"));

import { BLOCKS, MUSIC, isPlaylist, modeLabel, playlistName, queues } from "./schedule-blocks.mjs";

// Music is a set of YT Music playlists in the app rather than a feed, so it has
// no duration and no episode mode; everything else is looked up by name. A
// named playlist is the same, but says which one.
const showName = (s) => (
  s === MUSIC ? "Music — your YTM playlists"
    : isPlaylist(s) ? `${playlistName(s)} — your YTM playlist`
      : s
);

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
// Rows are padded with blanks so that shrinking content actually clears what
// was there before. The tab is no longer deleted between runs, so a row that
// gets shorter — or a spacer that lands where text used to be — would otherwise
// leave the old text sitting on screen.
//
// How far to pad matters: a row must never be padded into a guidance column,
// which would erase what Jason writes there. Spacers and full-width banners pad
// to 8 because none of them falls beside a guidance column; the per-day
// sub-headings inside section 3 pad only to 6, because column G beside them
// is his.
const PAD = { title: 8, sub: 8, section: 8, sub2: 6 };
const push = (arr, kind) => {
  const want = arr.length === 0 ? 8 : (PAD[kind] || arr.length);
  const row = want > arr.length ? [...arr, ...Array(want - arr.length).fill("")] : arr;
  rows.push(row);
  if (kind) fmt.push({ r: rows.length - 1, kind });
};

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
  const total = q.shows.reduce((s, [n]) => s + (n === MUSIC || isPlaylist(n) ? 0 : (mins(n) || 0)), 0);
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
  ["Aleph Beta", "Their public feed carries four episodes of A Book Like No Other where their own site lists 136 across ten series — the RSS was pruned, but the audio never was. The entry plays a feed rebuilt from the episode metadata they publish, so this slot now draws on 80 hours rather than four episodes. Coverage was checked against Aleph Beta's own member search: of 56 audio items it returned, 55 were already in the feed. Series range from the three Parsha Cycles (87 episodes, ~30 min each) to Matan Torah and The Shofar's Cry (~58 min)."],
].forEach(([k, v]) => push(["", k, v], "note"));
push([]);

push(["5 · Recently decided"], "section");
push(["Question", "How it was settled"], "head");
[
  // Deliberately one row, as it was: the yellow guidance columns to the right
  // are aligned by row, so adding a row here would shift them against their
  // content for every section below.
  ["Resolved — Jews You Should Know mixed two formats; now filtered by length",
   "Measured from the feed: of 288 episodes, 73 are \"Torah You Should Know\", a 3-to-7-minute d'var Torah series published on Fridays between Nov 2020 and Jun 2022 — a quarter of the feed. So one random draw in four was a four-minute parsha thought, usually for the wrong week since they are parsha-specific and years old, instead of a 45-to-100-minute biography interview. The two formats separate cleanly: no episode falls between 8 and 28 minutes. The entry now carries a 20-minute minimum, which keeps all 211 interviews and excludes the short series. Nothing was ever lost to this — the block was not silent, it just moved on early. Smash Boom Best gets the same treatment (45 of 237 episodes under 10 minutes against a 30-to-45-minute debate). The School of Greatness is a different shape worth knowing: 247 of its 1,978 episodes sit in the 5-to-10 minute band with no gap anywhere, so that is a deliberate short format and the floor there is a preference for the long interviews rather than a format separation. Nothing about the schedule is currently open; the remaining open items are app-level and live in docs/working-notes.md."],
].forEach((r) => push(r, "change"));
push([]);

push(["6 · Change history"], "section");
push(["", "Every addition, removal and timing change now lives on the 'Schedule change log' tab, " +
  "newest first, with the reason where one was written down."], "note");
push([]);
push(["Applied to Google Home and to the YTM Trigger app. The app is the live system; " +
  "the Google Home podcast routines are still running in parallel until you delete them."], "sub");

// ---------------------------------------------------------------- write
//
// The tab is reused, never deleted and recreated. It used to be dropped and
// rebuilt each run, which threw away everything Jason had changed by hand: the
// yellow "Change guidance from us" columns he added beside each section, the
// Overflow wrapping he set on the rightmost text column of each section, and
// the narrower widths he gave columns C and F. Those are his, and this script
// no longer has an opinion about them.
//
// Two rules keep it out of his way. Column widths and wrap strategy are set
// only when the tab is created from nothing, so on every ordinary run they are
// left exactly as he left them. And no row is ever formatted wider than its own
// content, which is what keeps the guidance columns untouched - each one sits
// immediately to the right of its section's last column.
const meta = (await api("GET", "?fields=sheets.properties")).data;
// The tab was called "Recommended Schedule" while it was still a proposal. It
// is generated from schedule-blocks.mjs, the same source the phone's config is
// built from, so it is simply the schedule and is named that way now.
//
// Renaming has to move the existing sheet rather than let the lookup below miss
// and create a new one. Everything Jason has done by hand lives on that sheet -
// the yellow guidance columns, the narrowed C and F, the Overflow wrapping -
// and a new tab would start with none of it while his work sat orphaned under
// the old name.
{
  const previous = meta.sheets.find((s) => s.properties.title === PREVIOUS_TAB);
  const already = meta.sheets.find((s) => s.properties.title === TAB);
  if (previous && !already) {
    await api("POST", ":batchUpdate", { requests: [{ updateSheetProperties: {
      properties: { sheetId: previous.properties.sheetId, title: TAB },
      fields: "title",
    } }] });
    previous.properties.title = TAB;
    console.log(`renamed "${PREVIOUS_TAB}" to "${TAB}"`);
  }
}
const old = meta.sheets.find((s) => s.properties.title === TAB);
const fresh = !old;
let sheetId;
if (old) {
  sheetId = old.properties.sheetId;
  const grid = old.properties.gridProperties || {};
  const needRows = rows.length + 20;
  // Grow if the schedule got longer; never shrink, which would delete his
  // columns along with the cells we own.
  if ((grid.rowCount || 0) < needRows || (grid.columnCount || 0) < 8) {
    await api("POST", ":batchUpdate", { requests: [{ updateSheetProperties: {
      properties: { sheetId, gridProperties: {
        rowCount: Math.max(grid.rowCount || 0, needRows),
        columnCount: Math.max(grid.columnCount || 0, 8),
        frozenRowCount: 2,
      } },
      fields: "gridProperties(rowCount,columnCount,frozenRowCount)",
    } }] });
  }
  // Stale merges from a previous, differently shaped run would corrupt the new
  // layout. Only our own columns are unmerged.
  await api("POST", ":batchUpdate", { requests: [{ unmergeCells: {
    range: { sheetId, startRowIndex: 0, endRowIndex: grid.rowCount || rows.length, startColumnIndex: 0, endColumnIndex: 8 },
  } }] }).catch(() => { /* nothing was merged */ });
} else {
  const made = await api("POST", ":batchUpdate", { requests: [{ addSheet: { properties: {
    title: TAB, gridProperties: { rowCount: rows.length + 20, columnCount: 9, frozenRowCount: 2 } } } }] });
  sheetId = made.data.replies[0].addSheet.properties.sheetId;
}
await api("PUT", `/values/${encodeURIComponent(TAB)}!A1?valueInputOption=RAW`, { values: rows });
// Clear anything left below the new content. Everything of his sits well above
// this point, inside the sections themselves.
if (old) {
  const lastRow = old.properties.gridProperties?.rowCount || rows.length;
  if (lastRow > rows.length) {
    await api("POST", `/values/${encodeURIComponent(TAB)}!A${rows.length + 1}:H${lastRow}:clear`, {});
  }
}

const C = {
  navy: { red: 0.11, green: 0.20, blue: 0.36 },
  band: { red: 0.90, green: 0.93, blue: 0.98 },
  head: { red: 0.85, green: 0.89, blue: 0.95 },
  sub2: { red: 0.95, green: 0.96, blue: 0.90 },
  tot: { red: 0.97, green: 0.97, blue: 0.97 },
  white: { red: 1, green: 1, blue: 1 },
};
const req = [];
// Column widths and the blanket wrap are applied only when the tab is created
// from nothing. On an existing tab they are Jason's: he has narrowed C and F
// and set the rightmost text column of each section to Overflow, and re-running
// this script must not undo that.
if (fresh) {
  req.push(
    { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 0, endIndex: 1 }, properties: { pixelSize: 250 }, fields: "pixelSize" } },
    { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 1, endIndex: 2 }, properties: { pixelSize: 210 }, fields: "pixelSize" } },
    { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 2, endIndex: 3 }, properties: { pixelSize: 270 }, fields: "pixelSize" } },
    { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 3, endIndex: 5 }, properties: { pixelSize: 115 }, fields: "pixelSize" } },
    { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 5, endIndex: 6 }, properties: { pixelSize: 560 }, fields: "pixelSize" } },
    { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 6, endIndex: 8 }, properties: { pixelSize: 200 }, fields: "pixelSize" } },
    { repeatCell: { range: { sheetId, startRowIndex: 0, endRowIndex: rows.length, startColumnIndex: 0, endColumnIndex: 8 },
        cell: { userEnteredFormat: { wrapStrategy: "WRAP", verticalAlignment: "TOP", textFormat: { fontSize: 10 } } },
        fields: "userEnteredFormat(wrapStrategy,verticalAlignment,textFormat)" } },
  );
}
// How far right a row may be formatted. Never past its own content: the yellow
// guidance columns sit immediately right of each section's last column, so
// staying inside the content is what leaves them alone. Banner rows hold a
// single cell but are meant to read as a full-width band, and none of them
// falls beside a guidance column.
const widthFor = (r, kind) => {
  if (kind === "title" || kind === "sub" || kind === "section") return 8;
  if (kind === "head") return Math.max((rows[r] || []).length, 1);
  return 6;
};
for (const { r, kind } of fmt) {
  const endColumnIndex = widthFor(r, kind);
  const range = { sheetId, startRowIndex: r, endRowIndex: r + 1, startColumnIndex: 0, endColumnIndex };
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

// A named playlist has no feed and no recorded duration, so it is expected to
// be absent from both lookups. build-schedules.mjs is where a playlist name is
// checked, against playlist-list.mjs, and it throws on one it does not know.
const unmatched = [...new Set(queues().flatMap((q) => q.shows.map(([n]) => n)))]
  .filter((n) => n !== MUSIC && !isPlaylist(n) && !find(n) && !(n in PRIVATE_MEDIAN));
console.log(unmatched.length ? `UNMATCHED SHOWS: ${unmatched.join(", ")}` : "all show names resolve");
