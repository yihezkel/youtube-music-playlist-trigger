// Create/refresh the "Podcast Catalogue" tab from podcast-stats.json.
import { readFileSync } from "node:fs";
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const TAB = "Podcast Catalogue";
const rows = JSON.parse(readFileSync("podcast-stats.json", "utf8"));

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (method, url, data) => client.request({ method, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${url}`, data });

// --- derived, scheduling-oriented fields -------------------------------------
function publishing(r) {
  if (r.kind === "News brief") return "n/a (Assistant)";
  if (r.kind === "Discontinued") return "Ended";
  if (!r.ok) return "Unknown";
  if (r.daysSinceLast == null) return "Unknown";
  if (r.daysSinceLast <= 21) return "Active";
  if (r.daysSinceLast <= 120) return "Slowing";
  if (r.daysSinceLast <= 400) return "Dormant";
  return "Ended";
}
// Length predictability drives how safely a show fits a fixed slot: the user
// already flagged TED Talks Daily as "hard to place" for exactly this reason.
function predictability(r) {
  if (!r.ok || !r.durMedian || r.durSd == null) return "";
  // A feed whose 90th percentile dwarfs its median is not merely variable, it
  // is carrying two different formats - typically short daily cut-downs plus
  // full episodes. That matters more than variance: a random-episode pick will
  // nearly always return the short one.
  if (r.durP90 != null && r.durMedian >= 1 && r.durP90 >= r.durMedian * 3) {
    return "Mixed formats";
  }
  const cv = r.durSd / Math.max(r.durMedian, 1);
  if (cv < 0.20) return "Tight";
  if (cv < 0.45) return "Moderate";
  return "Wide";
}
function cadence(r) {
  if (!r.ok) return "";
  const w = r.perWeekRecent;
  if (!w) return "None in 90d";
  if (w >= 6) return `${w}/wk (daily+)`;
  if (w >= 0.85) return `${w}/wk`;
  return `${w}/wk (irregular)`;
}

const ORDER = { Doing: 0, Considering: 1, Dropped: 2 };
rows.sort((a, b) =>
  (ORDER[a.status] - ORDER[b.status]) ||
  a.slot.localeCompare(b.slot) ||
  a.name.localeCompare(b.name));

// Pull the duration ranges already recorded on the existing tabs so the tab can
// show where reality has drifted from what the schedule assumes. A show that
// quietly changed length is the failure mode a fixed slot cannot absorb.
const sheetDur = new Map();
for (const tab of ["Weekly", "Daily", "News"]) {
  const r = await api("GET", `/values/${tab}!A1:F200`);
  for (const row of r.data.values || []) {
    const cells = row.map((c) => String(c || "").trim());
    for (let i = 0; i < cells.length - 1; i++) {
      const label = cells[i], dur = cells[i + 1];
      if (!label || label.length < 4) continue;
      if (!/^\d+\s*(-\s*\d+)?$/.test(dur)) continue;
      const key = norm(label);
      if (key && !sheetDur.has(key)) sheetDur.set(key, dur.replace(/\s+/g, " "));
    }
  }
}
function norm(s) {
  return String(s).toLowerCase()
    .replace(/\(.*?\)/g, " ")
    .replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();
}
function lookupSheetDur(name) {
  const k = norm(name);
  if (sheetDur.has(k)) return sheetDur.get(k);
  for (const [key, v] of sheetDur) {
    if (key.length > 6 && (k.includes(key) || key.includes(k))) return v;
  }
  return "";
}
// Flag only clear drift: the feed's typical range sitting well outside what the
// sheet recorded. Small differences are just sampling.
function drift(r, recorded) {
  if (!recorded || !r.ok || r.durMedian == null) return "";
  const m = /^(\d+)(?:\s*-\s*(\d+))?$/.exec(recorded);
  if (!m) return "";
  const lo = Number(m[1]), hi = m[2] ? Number(m[2]) : Number(m[1]);
  const med = r.durMedian;
  if (med > hi * 1.6) return `LONGER than recorded (${recorded}m)`;
  if (med < lo * 0.6) return `SHORTER than recorded (${recorded}m)`;
  return "";
}

const HEAD = [
  "Podcast", "Status", "Slot", "Type", "Publishing?", "Last episode", "Days since",
  "Eps/week (last 90d)", "Eps/week (lifetime)", "Eps last 365d", "Typical days",
  "Day regularity", "Length median (m)", "Length mean (m)", "Length SD (m)",
  "Length P10-P90 (m)", "Length min-max (m)", "Length predictability",
  "Recorded on other tabs (m)", "Drift vs recorded",
  "Episodes in feed", "Genre", "Notes", "Match confidence", "Feed URL",
];

const body = rows.map((r) => {
  const recorded = lookupSheetDur(r.name);
  return [
    r.name, r.status, r.slot, r.kind || "Podcast", publishing(r),
    r.lastEpisode || "", r.daysSinceLast ?? "",
    r.ok ? r.perWeekRecent : "", r.ok ? (r.perWeekLifetime ?? "") : "", r.ok ? r.episodesLast365 : "",
    r.ok ? (r.dayPattern || "irregular") : "",
    r.ok && r.dayConcentration ? r.dayConcentration : "",
    r.ok ? (r.durMedian ?? "") : "", r.ok ? (r.durMean ?? "") : "", r.ok ? (r.durSd ?? "") : "",
    r.ok && r.durP10 != null ? `${r.durP10} - ${r.durP90}` : "",
    r.ok && r.durMin != null ? `${r.durMin} - ${r.durMax}` : "",
    predictability(r),
    recorded, drift(r, recorded),
    r.ok ? r.episodesInFeed : "", r.ok ? (r.genre || "") : "",
    [r.note, r.ok ? "" : r.error].filter(Boolean).join(" | "),
    r.ok ? `${r.confidence}%` : "", r.feedUrl || "",
  ];
});

// --- (re)create the tab -------------------------------------------------------
const meta = (await api("GET", "?fields=sheets.properties")).data;
const existing = meta.sheets.find((s) => s.properties.title === TAB);
if (existing) {
  await api("POST", ":batchUpdate", { requests: [{ deleteSheet: { sheetId: existing.properties.sheetId } }] });
}
const made = await api("POST", ":batchUpdate", {
  requests: [{ addSheet: { properties: { title: TAB, gridProperties: { rowCount: body.length + 40, columnCount: HEAD.length, frozenRowCount: 1, frozenColumnCount: 1 } } } }],
});
const sheetId = made.data.replies[0].addSheet.properties.sheetId;

const legend = [
  [],
  ["How to read this tab"],
  ["Eps/week (last 90d)", "Current publishing rate - the number to schedule against. 'None in 90d' means nothing new has appeared."],
  ["Eps/week (lifetime)", "Average across the whole feed. Much lower than the 90d figure means the show sped up; much higher means it has slowed."],
  ["Typical days", "Weekdays carrying most episodes over the last year. 'irregular' means no day dominates."],
  ["Day regularity", "Share of the last year's episodes falling on the single busiest weekday. 1.00 = perfectly weekly on one day; below ~0.4 = scattered."],
  ["Length SD (m)", "Standard deviation of episode length in minutes."],
  ["Length predictability", "Tight = SD under 20% of median (safe in a fixed slot). Moderate = under 45%. Wide = highly variable, so a fixed slot will over- or under-run."],
  ["  ...Mixed formats", "The feed carries two lengths at once - short daily cut-downs alongside full episodes. A random-episode pick will nearly always return the short one, so prefer newest-episode mode or a different feed."],
  ["Episodes in feed", "Back-catalogue depth currently served by the RSS feed. This is the pool a random-episode schedule draws from, and is a floor: some publishers truncate their feed."],
  ["Type: News brief", "A Google Assistant news briefing, not a podcast. There is no public RSS, so these cannot be moved to the YTM Trigger app by feed."],
  ["Drift vs recorded", "Flags a show whose current typical length sits well outside the range recorded on the Weekly/Daily/News tabs - the schedule assumption no longer holds."],
  ["Match confidence", "Name similarity between the sheet's label and the matched feed. Low values are not necessarily wrong - renamed shows score low but are correct (see Notes)."],
  [],
  [`Generated ${new Date().toISOString().slice(0, 16).replace("T", " ")} from public RSS feeds via the iTunes directory. Spotify is not used: its API needs Premium.`],
];

await api("PUT", `/values/${encodeURIComponent(TAB)}!A1?valueInputOption=RAW`, {
  values: [HEAD, ...body, ...legend],
});

// --- formatting ---------------------------------------------------------------
const headerEnd = 1, dataEnd = 1 + body.length;
const txt = (s) => ({ userEnteredFormat: { textFormat: { bold: true } } });
const req = [
  { repeatCell: {
      range: { sheetId, startRowIndex: 0, endRowIndex: 1 },
      cell: { userEnteredFormat: {
        backgroundColor: { red: 0.15, green: 0.25, blue: 0.42 },
        textFormat: { bold: true, foregroundColor: { red: 1, green: 1, blue: 1 } },
        verticalAlignment: "MIDDLE", wrapStrategy: "WRAP" } },
      fields: "userEnteredFormat(backgroundColor,textFormat,verticalAlignment,wrapStrategy)" } },
  { updateDimensionProperties: {
      range: { sheetId, dimension: "COLUMNS", startIndex: 0, endIndex: 1 },
      properties: { pixelSize: 260 }, fields: "pixelSize" } },
  { updateDimensionProperties: {
      range: { sheetId, dimension: "COLUMNS", startIndex: 1, endIndex: HEAD.length },
      properties: { pixelSize: 110 }, fields: "pixelSize" } },
  { updateDimensionProperties: {
      range: { sheetId, dimension: "COLUMNS", startIndex: 20, endIndex: 21 },
      properties: { pixelSize: 320 }, fields: "pixelSize" } },
  { updateDimensionProperties: {
      range: { sheetId, dimension: "ROWS", startIndex: 0, endIndex: 1 },
      properties: { pixelSize: 58 }, fields: "pixelSize" } },
  { setBasicFilter: { filter: { range: { sheetId, startRowIndex: 0, endRowIndex: dataEnd, startColumnIndex: 0, endColumnIndex: HEAD.length } } } },
  { repeatCell: {
      range: { sheetId, startRowIndex: headerEnd, endRowIndex: dataEnd, startColumnIndex: 20, endColumnIndex: 21 },
      cell: { userEnteredFormat: { wrapStrategy: "WRAP" } }, fields: "userEnteredFormat.wrapStrategy" } },
  { repeatCell: {
      range: { sheetId, startRowIndex: dataEnd + 2, endRowIndex: dataEnd + 3 },
      cell: { userEnteredFormat: { textFormat: { bold: true } } }, fields: "userEnteredFormat.textFormat" } },
  { updateDimensionProperties: {
      range: { sheetId, dimension: "COLUMNS", startIndex: 1, endIndex: 2 },
      properties: { pixelSize: 100 }, fields: "pixelSize" } },
];

// Status and Publishing? colouring, so the three groups are readable at a glance.
const colourRule = (col, value, bg) => ({
  addConditionalFormatRule: { rule: {
    ranges: [{ sheetId, startRowIndex: headerEnd, endRowIndex: dataEnd, startColumnIndex: col, endColumnIndex: col + 1 }],
    booleanRule: { condition: { type: "TEXT_EQ", values: [{ userEnteredValue: value }] }, format: { backgroundColor: bg } } }, index: 0 },
});
req.push(
  colourRule(1, "Doing", { red: 0.80, green: 0.94, blue: 0.80 }),
  colourRule(1, "Considering", { red: 1.00, green: 0.95, blue: 0.75 }),
  colourRule(1, "Dropped", { red: 0.95, green: 0.85, blue: 0.85 }),
  colourRule(4, "Active", { red: 0.80, green: 0.94, blue: 0.80 }),
  colourRule(4, "Slowing", { red: 1.00, green: 0.95, blue: 0.75 }),
  colourRule(4, "Dormant", { red: 0.99, green: 0.88, blue: 0.78 }),
  colourRule(4, "Ended", { red: 0.95, green: 0.85, blue: 0.85 }),
  colourRule(19, "", { red: 1, green: 1, blue: 1 }),
  colourRule(17, "Wide", { red: 0.99, green: 0.88, blue: 0.78 }),
  colourRule(17, "Tight", { red: 0.85, green: 0.94, blue: 0.98 }),
  colourRule(17, "Mixed formats", { red: 0.98, green: 0.85, blue: 0.95 }),
);

await api("POST", ":batchUpdate", { requests: req });

console.log(`wrote "${TAB}": ${body.length} podcasts`);
const by = (k) => rows.reduce((m, r) => (m[r[k]] = (m[r[k]] || 0) + 1, m), {});
console.log("by status:", by("status"));
console.log("publishing:", rows.reduce((m, r) => (m[publishing(r)] = (m[publishing(r)] || 0) + 1, m), {}));
