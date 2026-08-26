// Capture the scheduling information held only on the Daily and News tabs
// before those tabs are removed, and fold it into tools/sheet-legacy.json.
//
// What is actually unique to them:
//   Daily - the clock time each show occupies in the Google Home daily routine
//   News  - the running order of the 16:37 news routine, which is a priority
//           list rather than an alphabetical one
// The Weekly grid is left alone; shows that appear only there are marked so
// the catalog column is not misleadingly blank for them.
import { readFileSync, writeFileSync } from "node:fs";
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const get = (r) => client.request({ url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}/values/${encodeURIComponent(r)}` });

const legacy = JSON.parse(readFileSync("sheet-legacy.json", "utf8"));
const slots = {};
const extras = [];
const clean = (s) => String(s ?? "").replace(/\s+/g, " ").trim();

// The sheet and the catalog use different names for the same show.
const ALIAS = { "WSJ Updates": "WSJ What's News" };
const canonical = (s) => ALIAS[s] || s;

// --- Daily: time -> show -----------------------------------------------------
for (const row of (await get("Daily!A1:B40")).data.values || []) {
  const time = clean(row[0]);
  const show = clean(row[1]);
  if (!/^\d{1,2}:\d{2}$/.test(time) || !show) continue;
  if (/^Ideas,/.test(show)) continue;
  if (/^News routine/i.test(show)) { extras.push(`${time} - News routine (see the News rows below)`); continue; }
  slots[canonical(show)] = `Daily ${time}`;
}

// --- News: running order -----------------------------------------------------
let order = 0;
for (const row of (await get("News!A1:A40")).data.values || []) {
  const show = clean(row[0]);
  if (!show || show === "Source" || /^Ideas,/.test(show)) continue;
  order += 1;
  slots[canonical(show)] = `News routine #${order} (16:37)`;
}

// --- Weekly: which shows live on the grid, so they are not left blank --------
const weekly = new Set();
for (const row of (await get("Weekly!A1:H12")).data.values || []) {
  for (const cell of row.slice(1)) {
    const s = clean(cell).replace(/\s*\([^)]*\)\s*$/, "").trim();
    if (!s || s.length < 4) continue;
    if (/^(Sunday|Monday|Tuesday|Wednesday|Thursday|Friday|Matza|Daily|Ideas,)/i.test(s)) continue;
    weekly.add(s);
  }
}
for (const s of weekly) if (!slots[s]) slots[s] = "Weekly grid";

legacy.slots = slots;
legacy.dailyExtras = extras;
writeFileSync("sheet-legacy.json", JSON.stringify(legacy, null, 1));

console.log(`captured ${Object.keys(slots).length} slot assignments`);
for (const [k, v] of Object.entries(slots)) console.log(`   ${v.padEnd(28)} ${k}`);
console.log("\nnot podcasts, kept as a note:", extras.join(" | ") || "(none)");
