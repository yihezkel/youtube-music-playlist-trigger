// Trim the Weekly / Daily / News tabs once their content has been folded into
// the Podcast Catalog.
//
// Run tools/sheet-legacy.mjs FIRST - it captures the wording this deletes.
// A full backup also lives in the session files (sheet-backup.json).
//
// Weekly keeps its day grid but loses everything below it. Its Notes/TODO
// columns are NOT deleted as columns, because on that tab columns E and F are
// Thursday and Friday of the grid - only the lower rows carried notes.
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (m, u, d) => client.request({ method: m, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}`, data: d });

const meta = (await api("GET", "?fields=sheets.properties")).data;
const idOf = (t) => meta.sheets.find((s) => s.properties.title === t)?.properties.sheetId;

// 1-based, inclusive row numbers to keep; everything after is removed.
const PLAN = {
  Weekly: { keepRows: 9, dropCols: null },
  Daily:  { keepRows: 17, dropCols: [3, 5] },  // 0-based [start,end) => D,E
  News:   { keepRows: 22, dropCols: [2, 4] },  // C,D
};

const requests = [];
for (const [tab, p] of Object.entries(PLAN)) {
  const sheetId = idOf(tab);
  if (sheetId == null) { console.log(`skip ${tab}: not found`); continue; }
  // Rows first, then columns, so the column indices still refer to the
  // original layout when they are applied.
  requests.push({ deleteRange: {
    range: { sheetId, startRowIndex: p.keepRows, endRowIndex: 1000 },
    shiftDimension: "ROWS" } });
  if (p.dropCols) {
    requests.push({ deleteRange: {
      range: { sheetId, startColumnIndex: p.dropCols[0], endColumnIndex: p.dropCols[1] },
      shiftDimension: "COLUMNS" } });
  }
}

await api("POST", ":batchUpdate", { requests });

// Leave a pointer so the missing content is findable rather than mysterious.
for (const [tab, p] of Object.entries(PLAN)) {
  const row = p.keepRows + 2;
  await api("PUT", `/values/${encodeURIComponent(tab)}!A${row}?valueInputOption=RAW`, {
    values: [["Ideas, dropped shows, notes and TODOs now live in the 'Podcast Catalog' tab."]],
  });
}

for (const tab of Object.keys(PLAN)) {
  const r = await api("GET", `/values/${tab}!A1:H40`);
  const rows = r.data.values || [];
  console.log(`\n===== ${tab}: ${rows.length} rows =====`);
  rows.forEach((row, i) => {
    const s = row.map((c) => String(c || "").replace(/\s+/g, " ")).join(" | ");
    if (s.replace(/\|/g, "").trim()) console.log(`${String(i + 1).padStart(3)}: ${s.slice(0, 150)}`);
  });
}
