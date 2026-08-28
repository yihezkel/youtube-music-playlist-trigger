// Create/refresh the "Podcast Catalog" tab from podcast-stats.json.
//
// Any text already typed into the "Our preferences" column is read back and
// re-applied before the tab is rebuilt. That column is the user's, not the
// generator's, and losing it on a refresh would be a data-loss bug.
import { readFileSync } from "node:fs";
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const TAB = "Podcast Catalog";
const OLD_TABS = ["Podcast Catalogue"]; // earlier spelling
import { MUSIC, queues } from "./schedule-blocks.mjs";
import { PODCASTS } from "./podcast-list.mjs";
const rows = JSON.parse(readFileSync("podcast-stats.json", "utf8"));

// podcast-stats.json is a cache of what was fetched from each feed. The
// editorial fields - status, slot, note - belong to the master list, so take
// them from there rather than from whatever the cache happened to hold when it
// was last rebuilt. Without this, changing a status in podcast-list.mjs did
// nothing until every feed was re-fetched.
{
  const master = new Map(PODCASTS.map((p) => [p.name, p]));
  let stale = 0;
  for (const r of rows) {
    const m = master.get(r.name);
    if (!m) continue;
    if (r.status !== m.status || (m.note || "") !== (r.note || "")) stale++;
    r.status = m.status;
    r.slot = m.slot;
    if (m.note) r.note = m.note;
  }
  if (stale) console.log(`refreshed ${stale} row(s) from the master list`);
}

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (method, url, data) => client.request({ method, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${url}`, data });

const norm = (s) => String(s).toLowerCase().replace(/\(.*?\)/g, " ").replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();

function publishing(r) {
  if (r.kind === "News brief") return "n/a (Assistant)";
  if (r.kind === "Discontinued") return "Ended";
  if (!r.ok || r.daysSinceLast == null) return "Unknown";
  if (r.daysSinceLast <= 21) return "Active";
  if (r.daysSinceLast <= 120) return "Slowing";
  if (r.daysSinceLast <= 400) return "Dormant";
  return "Ended";
}
function predictability(r) {
  if (!r.ok || !r.durMedian || r.durSd == null) return "";
  if (r.durP90 != null && r.durMedian >= 1 && r.durP90 >= r.durMedian * 3) return "Mixed formats";
  const cv = r.durSd / Math.max(r.durMedian, 1);
  if (cv < 0.20) return "Tight";
  if (cv < 0.45) return "Moderate";
  return "Wide";
}
const rating = (r) => (r.ratingCount ? `${r.ratingAvg} (${r.ratingCount.toLocaleString("en-US")})` : "");

// --- carry the user's own column across a rebuild -----------------------------
// Column index of the column that belongs to Jason rather than to this script.
const USER_COL = 4;
// Whatever he has titled it. Kept so a rebuild does not rename his column back.
let userHeading = "Our preferences";
const meta = (await api("GET", "?fields=sheets.properties")).data;
const prefs = new Map();
for (const t of [TAB, ...OLD_TABS]) {
  if (!meta.sheets.find((s) => s.properties.title === t)) continue;
  try {
    const got = await api("GET", `/values/${encodeURIComponent(t)}!A1:AZ200`);
    const vals = got.data.values || [];
    const head = vals[0] || [];
    const iName = head.indexOf("Podcast");
    // Match the column by either name, and fall back to its position. Jason
    // renamed it to "Change guidance from us" and coloured it yellow; matching
    // only the original wording made this return -1, which silently dropped
    // every note in it on the next rebuild.
    let iPref = head.findIndex((h) => /our preferences|change guidance/i.test(String(h)));
    if (iPref < 0 && head.length > USER_COL) iPref = USER_COL;
    if (iName < 0 || iPref < 0) continue;
    if (head[iPref]) userHeading = String(head[iPref]);
    for (const row of vals.slice(1)) {
      const k = norm(row[iName] || "");
      const v = String(row[iPref] || "").trim();
      if (k && v) prefs.set(k, v);
    }
  } catch { /* nothing to carry over */ }
}
console.log(`carried over ${prefs.size} preference note(s)`);

// --- notes and recorded durations carried over from the old tab layout -------
// Read from tools/sheet-legacy.json, not the live tabs: the lower halves of
// Weekly/Daily/News have been folded into this catalog and removed, so that
// wording now survives only in that file.
const legacy = JSON.parse(readFileSync("sheet-legacy.json", "utf8"));
const sheetDur = new Map(Object.entries(legacy.durations).map(([k, v]) => [norm(k), v]));
const sheetNote = new Map(Object.entries(legacy.notes).map(([k, v]) => [norm(k), v]));
function lookupNote(name) {
  const k = norm(name);
  if (sheetNote.has(k)) return sheetNote.get(k);
  for (const [key, v] of sheetNote) if (key.length > 6 && (k.includes(key) || key.includes(k))) return v;
  return "";
}
function lookupSheetDur(name) {
  const k = norm(name);
  if (sheetDur.has(k)) return sheetDur.get(k);
  for (const [key, v] of sheetDur) if (key.length > 6 && (k.includes(key) || key.includes(k))) return v;
  return "";
}
function drift(r, recorded) {
  if (!recorded || !r.ok || r.durMedian == null) return "";
  const m = /^(\d+)(?:\s*-\s*(\d+))?$/.exec(recorded);
  if (!m) return "";
  const lo = Number(m[1]), hi = m[2] ? Number(m[2]) : Number(m[1]);
  if (r.durMedian > hi * 1.6) return `LONGER than recorded (${recorded}m)`;
  if (r.durMedian < lo * 0.6) return `SHORTER than recorded (${recorded}m)`;
  return "";
}

// A show the app is actually scheduled to play is being done, whatever an older
// hand-maintained list said. Deriving it from the schedule rather than repeating
// it here is what stops the catalog claiming "Considering" for something that
// has been in the line-up for weeks.
const scheduled = new Set(
  queues().flatMap((q) => q.shows.map(([n]) => n)).filter((n) => n !== MUSIC).map(norm));
const promoted = [];
for (const r of rows) {
  if (r.status !== "Doing" && scheduled.has(norm(r.name))) {
    promoted.push(`${r.name} (was ${r.status})`);
    r.status = "Doing";
  }
}
const orphan = [...scheduled].filter((n) => !rows.some((r) => norm(r.name) === n));
if (promoted.length) console.log(`promoted to Doing: ${promoted.join(", ")}`);
if (orphan.length) console.log(`SCHEDULED BUT NOT IN CATALOG: ${orphan.join(", ")}`);

const ORDER = { Doing: 0, "Suggested by AI": 1, Considering: 2, Dropped: 3 };
rows.sort((a, b) =>
  (ORDER[a.status] - ORDER[b.status]) || a.slot.localeCompare(b.slot) || a.name.localeCompare(b.name));

const HEAD = [
  "Podcast", "Status", "Group", "Type", "Our preferences", "Your notes", "What it is",
  "Rating (ratings)", "Publishing?", "Last episode", "Days since",
  "Eps/week (last 90d)", "Eps/week (lifetime)", "Eps last 365d", "Typical days",
  "Day regularity", "Length median (m)", "Length mean (m)", "Length SD (m)",
  "Length P10-P90 (m)", "Length min-max (m)", "Length predictability",
  "Recorded by you (m)", "Drift vs recorded", "Episodes in feed",
  "Genre", "Notes", "Match confidence", "Feed URL",
];

const body = rows.map((r) => {
  const recorded = lookupSheetDur(r.name);
  return [
    r.name, r.status, r.slot, r.kind || "Podcast",
    prefs.get(norm(r.name)) || "",
    lookupNote(r.name),
    r.description || "", rating(r), publishing(r),
    r.lastEpisode || "", r.daysSinceLast ?? "",
    r.ok ? r.perWeekRecent : "", r.ok ? (r.perWeekLifetime ?? "") : "", r.ok ? r.episodesLast365 : "",
    r.ok ? (r.dayPattern || "irregular") : "",
    r.ok && r.dayConcentration ? r.dayConcentration : "",
    r.ok ? (r.durMedian ?? "") : "", r.ok ? (r.durMean ?? "") : "", r.ok ? (r.durSd ?? "") : "",
    r.ok && r.durP10 != null ? `${r.durP10} - ${r.durP90}` : "",
    r.ok && r.durMin != null ? `${r.durMin} - ${r.durMax}` : "",
    predictability(r), recorded, drift(r, recorded),
    r.ok ? r.episodesInFeed : "", r.ok ? (r.genre || "") : "",
    [r.note, r.ok ? "" : r.error].filter(Boolean).join(" | "),
    r.ok ? `${r.confidence}%` : "", r.feedUrl || "",
  ];
});

const legend = [
  [],
  ["How to read this tab"],
  ["Your notes", "The Notes and TODO wording you had written on the Weekly/Daily/News tabs, carried over before those were trimmed. Read-only history - write new thoughts in the 'Our preferences' column."],
  ["Our preferences", "Yours to write in - e.g. 'Sarah loves this', 'too long for the morning'. Kept intact when this tab is regenerated, and read back when we next reassess the line-up."],
  ["Status: Considering", "Shows you had already noted as ideas."],
  ["Status: Suggested by AI", "Shows proposed here that were not previously on the sheet."],
  ["Rating (ratings)", "Apple Podcasts star average, with the number of ratings behind it. Apple is used because it publishes by far the largest public pool of podcast ratings - Spotify publishes none."],
  ["Eps/week (last 90d)", "Current publishing rate - the number to schedule against. Blank/0 means nothing new in 90 days."],
  ["Eps/week (lifetime)", "Average across the whole feed. Much lower than the 90d figure means the show sped up; much higher means it has slowed."],
  ["Typical days", "Weekdays carrying most episodes over the last year. 'irregular' means no day dominates."],
  ["Day regularity", "Share of the last year's episodes on the single busiest weekday. 1.00 = reliably one day a week; below ~0.4 = scattered."],
  ["Length predictability", "Tight = SD under 20% of median (safe in a fixed slot). Moderate = under 45%. Wide = highly variable."],
  ["  ...Mixed formats", "The feed carries two lengths at once - short daily cut-downs alongside full episodes. A random-episode pick will nearly always return the short one, so use newest-episode mode instead."],
  ["Episodes in feed", "Back-catalogue depth. This is the pool a random-episode schedule draws from, and is a floor: some publishers truncate their feed."],
  ["Drift vs recorded", "Flags a show whose current typical length sits well outside the range you had recorded by hand - the schedule assumption no longer holds."],
  ["Type: News brief", "A Google Assistant news briefing, not a podcast. No public RSS, so these cannot move to the YTM Trigger app by feed."],
  ["Match confidence", "Name similarity between the label and the matched feed. Low values are not necessarily wrong - renamed shows score low but are correct (see Notes)."],
  [],
  ["What is not here", "This tab describes shows, not when they play. Current scheduling lives on 'Schedule'; every past addition and removal lives on 'Schedule change log'."],
  [`Generated ${new Date().toISOString().slice(0, 16).replace("T", " ")} from public RSS feeds (iTunes directory) and Apple Podcasts ratings.`],
];

// --- rebuild ------------------------------------------------------------------
// The tab is reused, never deleted and recreated. Dropping it threw away
// everything Jason had done to it by hand - he has renamed column E to "Change
// guidance from us" and coloured it yellow. Column widths and wrap are set only
// when the tab is created from nothing, so on an ordinary run his formatting is
// left alone. Only tabs under an older name are still deleted, since those are
// genuinely superseded.
const del = OLD_TABS
  .map((t) => meta.sheets.find((s) => s.properties.title === t))
  .filter(Boolean)
  .map((s) => ({ deleteSheet: { sheetId: s.properties.sheetId } }));
if (del.length) await api("POST", ":batchUpdate", { requests: del });

const existing = meta.sheets.find((s) => s.properties.title === TAB);
const fresh = !existing;
let sheetId;
if (existing) {
  sheetId = existing.properties.sheetId;
  const grid = existing.properties.gridProperties || {};
  const needRows = body.length + legend.length + 40;
  if ((grid.rowCount || 0) < needRows || (grid.columnCount || 0) < HEAD.length) {
    await api("POST", ":batchUpdate", { requests: [{ updateSheetProperties: {
      properties: { sheetId, gridProperties: {
        rowCount: Math.max(grid.rowCount || 0, needRows),
        columnCount: Math.max(grid.columnCount || 0, HEAD.length),
        frozenRowCount: 1, frozenColumnCount: 1,
      } },
      fields: "gridProperties(rowCount,columnCount,frozenRowCount,frozenColumnCount)",
    } }] });
  }
} else {
  const made = await api("POST", ":batchUpdate", {
    requests: [{ addSheet: { properties: {
      title: TAB,
      gridProperties: { rowCount: body.length + 40, columnCount: HEAD.length, frozenRowCount: 1, frozenColumnCount: 1 },
    } } }],
  });
  sheetId = made.data.replies[0].addSheet.properties.sheetId;
}

// Write the whole grid, including his column: its values are keyed to the show
// name and re-emitted, because the sort order changes when statuses change and
// leaving the column in place would attach his notes to the wrong shows. Only
// values are written here - cell colours are a separate thing and survive.
const HEAD_OUT = HEAD.slice();
HEAD_OUT[USER_COL] = userHeading;
await api("PUT", `/values/${encodeURIComponent(TAB)}!A1?valueInputOption=RAW`, {
  values: [HEAD_OUT, ...body, ...legend],
});


const dataEnd = 1 + body.length;
const width = (start, end, px) => ({ updateDimensionProperties: {
  range: { sheetId, dimension: "COLUMNS", startIndex: start, endIndex: end }, properties: { pixelSize: px }, fields: "pixelSize" } });
const wrap = (start, end) => ({ repeatCell: {
  range: { sheetId, startRowIndex: 1, endRowIndex: dataEnd, startColumnIndex: start, endColumnIndex: end },
  cell: { userEnteredFormat: { wrapStrategy: "WRAP", verticalAlignment: "TOP" } },
  fields: "userEnteredFormat(wrapStrategy,verticalAlignment)" } });

const req = [
  { repeatCell: {
      range: { sheetId, startRowIndex: 0, endRowIndex: 1 },
      cell: { userEnteredFormat: {
        backgroundColor: { red: 0.12, green: 0.22, blue: 0.38 },
        textFormat: { bold: true, foregroundColor: { red: 1, green: 1, blue: 1 }, fontSize: 10 },
        verticalAlignment: "MIDDLE", wrapStrategy: "WRAP" } },
      fields: "userEnteredFormat(backgroundColor,textFormat,verticalAlignment,wrapStrategy)" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "ROWS", startIndex: 0, endIndex: 1 }, properties: { pixelSize: 62 }, fields: "pixelSize" } },
  { setBasicFilter: { filter: { range: { sheetId, startRowIndex: 0, endRowIndex: dataEnd, startColumnIndex: 0, endColumnIndex: HEAD.length } } } },
  { repeatCell: {
      range: { sheetId, startRowIndex: dataEnd + 2, endRowIndex: dataEnd + 3 },
      cell: { userEnteredFormat: { textFormat: { bold: true, fontSize: 11 } } }, fields: "userEnteredFormat.textFormat" } },
];
// Widths, wrap and the tint on his column are applied only when the tab is
// created from nothing. He has renamed that column and given it a stronger
// yellow, and re-running this must not paint over it.
if (fresh) {
  req.push(
    width(0, 1, 250), width(1, 2, 118), width(2, 3, 85), width(3, 4, 95),
    width(4, 5, 210), width(5, 6, 210), width(6, 7, 430),
    width(7, 26, 105), width(26, 27, 300), width(27, 29, 110),
    wrap(4, 7), wrap(26, 27),
    { repeatCell: {
        range: { sheetId, startRowIndex: 1, endRowIndex: dataEnd, startColumnIndex: USER_COL, endColumnIndex: USER_COL + 1 },
        cell: { userEnteredFormat: { backgroundColor: { red: 1, green: 0.98, blue: 0.87 } } },
        fields: "userEnteredFormat.backgroundColor" } },
  );
}

// The tab is no longer deleted between runs, so its conditional formats are not
// cleared for us. Without this they would pile up a duplicate set every run.
const existingRules = (await api("GET", "?fields=sheets(properties(sheetId),conditionalFormats)")).data
  .sheets.find((s) => s.properties.sheetId === sheetId)?.conditionalFormats || [];
for (let i = existingRules.length - 1; i >= 0; i--) {
  req.push({ deleteConditionalFormatRule: { sheetId, index: i } });
}


const rule = (col, value, bg) => ({ addConditionalFormatRule: { rule: {
  ranges: [{ sheetId, startRowIndex: 1, endRowIndex: dataEnd, startColumnIndex: col, endColumnIndex: col + 1 }],
  booleanRule: { condition: { type: "TEXT_EQ", values: [{ userEnteredValue: value }] }, format: { backgroundColor: bg } } }, index: 0 } });
req.push(
  rule(1, "Doing", { red: 0.82, green: 0.93, blue: 0.82 }),
  rule(1, "Suggested by AI", { red: 0.85, green: 0.90, blue: 0.98 }),
  rule(1, "Considering", { red: 1.00, green: 0.96, blue: 0.78 }),
  rule(1, "Dropped", { red: 0.94, green: 0.87, blue: 0.87 }),
  rule(8, "Active", { red: 0.82, green: 0.93, blue: 0.82 }),
  rule(8, "Slowing", { red: 1.00, green: 0.96, blue: 0.78 }),
  rule(8, "Dormant", { red: 0.99, green: 0.89, blue: 0.79 }),
  rule(8, "Ended", { red: 0.94, green: 0.87, blue: 0.87 }),
  rule(21, "Mixed formats", { red: 0.97, green: 0.86, blue: 0.95 }),
  rule(21, "Tight", { red: 0.86, green: 0.94, blue: 0.98 }),
);
await api("POST", ":batchUpdate", { requests: req });

console.log(`wrote "${TAB}": ${body.length} shows`);
console.log("by status:", rows.reduce((m, r) => (m[r.status] = (m[r.status] || 0) + 1, m), {}));
