// Extract the Notes / TODO wording and recorded durations from the Weekly,
// Daily and News tabs into tools/sheet-legacy.json.
//
// Run this BEFORE trimming those tabs. Once their lower halves are removed the
// wording only survives here, and the catalog is generated from this file.
import { GoogleAuth } from "google-auth-library";
import { writeFileSync } from "node:fs";

const ID = "<SHEET_ID>";
const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const get = (r) => client.request({ url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}/values/${encodeURIComponent(r)}` });

const tabs = {};
for (const t of ["Weekly", "Daily", "News"]) tabs[t] = (await get(`${t}!A1:AA200`)).data.values || [];

const notes = {};
const durations = {};
const clean = (s) => String(s ?? "").replace(/\s+/g, " ").trim();

function record(name, when, note, todo, dur, src) {
  const n = clean(name);
  if (!n || n.length < 3) return;
  const bits = [when && `When: ${clean(when)}`, clean(note), todo && `TODO: ${clean(todo)}`]
    .filter(Boolean);
  if (bits.length) {
    const line = `[${src}] ${bits.join(" | ")}`;
    notes[n] = notes[n] ? `${notes[n]} ${line}` : line;
  }
  const d = clean(dur);
  if (/^\d+\s*(-\s*\d+)?$/.test(d) && !durations[n]) durations[n] = d.replace(/\s+/g, " ");
}

// Weekly lower half: B=Source C=Duration D=When E=Notes F=TODO
tabs.Weekly.slice(15).forEach((r) => record(r[1], r[3], r[4], r[5], r[2], "Weekly"));
// Daily: B=Source C=Duration D=Notes E=TODO
tabs.Daily.slice(1).forEach((r) => record(r[1], "", r[3], r[4], r[2], "Daily"));
// News: A=Source B=Duration C=Notes D=TODO
tabs.News.slice(1).forEach((r) => record(r[0], "", r[2], r[3], r[1], "News"));

// Free-text context that is not about any one show (who gets home when, and
// similar). Worth keeping, but it belongs on the schedule, not the catalog.
const context = [];
for (const [t, rows] of Object.entries(tabs)) {
  for (const r of rows) {
    const cells = r.map(clean).filter(Boolean);
    if (cells.length !== 1) continue;
    const s = cells[0];
    if (/gets home|Fridays are different|coming on at|Once fully prioritized|Rego through/i.test(s)) {
      context.push(`[${t}] ${s}`);
    }
  }
}

writeFileSync("sheet-legacy.json", JSON.stringify({ notes, durations, context }, null, 1));
console.log(`notes: ${Object.keys(notes).length}  durations: ${Object.keys(durations).length}  context: ${context.length}`);
context.forEach((c) => console.log("   " + c));
