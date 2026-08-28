// Bookkeeping for guidance that has been acted on.
//
//   node archive-guidance.mjs "Podcast Catalog!E2" ["Schedule!F6" ...]
//   node archive-guidance.mjs --all
//   node archive-guidance.mjs --all --dry-run
//
// Moves the text out of the yellow "Change guidance from us" cell and into the
// notes cell beside it, stamped with the date it was applied, then clears the
// yellow cell so it reads as done. Run it only after the schedule has actually
// been changed, and add the matching row to the change log as well.
//
// Guidance is only cleared once it has been carried across, so a failure
// half way leaves the text somewhere rather than nowhere.
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";

// Where applied guidance goes, per tab. The catalog is one show per row with a
// notes column immediately to the right, so the move is unambiguous. The
// schedule tab has no notes column of its own, so it is left out until there is
// somewhere agreed to put it - better to refuse than to invent a destination
// and bury the text in a column that gets rewritten.
const DESTINATION = {
  "Podcast Catalog": { guidance: 4, notes: 5 }, // E -> F "Your notes"
};

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (m, u, d) => client.request({ method: m, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}`, data: d });

const colName = (i) => (i < 26
  ? String.fromCharCode(65 + i)
  : String.fromCharCode(64 + Math.floor(i / 26)) + String.fromCharCode(65 + (i % 26)));

const dry = process.argv.includes("--dry-run");
let cells = process.argv.slice(2).filter((a) => !a.startsWith("--"));

if (process.argv.includes("--all")) {
  const { execSync } = await import("node:child_process");
  let out = "";
  try {
    out = execSync("node guidance.mjs --json", { encoding: "utf8" });
  } catch (e) {
    out = e.stdout || "[]"; // exit code 10 just means something is pending
  }
  cells = JSON.parse(out).map((p) => `${p.tab}!${p.cell}`);
}

if (!cells.length) {
  console.log("Nothing to archive. Pass cell references, or --all.");
  process.exit(0);
}

const today = new Date().toISOString().slice(0, 10);
let done = 0, skipped = 0;

for (const ref of cells) {
  const [tab, a1] = ref.split("!");
  const dest = DESTINATION[tab];
  if (!dest) {
    console.log(`SKIP  ${ref}\n      No agreed notes column on "${tab}". Move it by hand, or add one to DESTINATION.`);
    skipped++;
    continue;
  }
  const m = /^([A-Z]+)(\d+)$/.exec(a1 || "");
  if (!m) { console.log(`SKIP  ${ref}: not a cell reference`); skipped++; continue; }
  const row = Number(m[2]);

  const gRef = `${tab}!${colName(dest.guidance)}${row}`;
  const nRef = `${tab}!${colName(dest.notes)}${row}`;
  const get = async (r) => ((await api("GET", `/values/${encodeURIComponent(r)}`)).data.values || [])[0]?.[0] ?? "";

  const guidance = String(await get(gRef)).trim();
  if (!guidance) { console.log(`SKIP  ${gRef}: already empty`); skipped++; continue; }

  const existing = String(await get(nRef)).trim();
  const stamped = `Applied ${today}: ${guidance}`;
  const merged = existing ? `${existing} | ${stamped}` : stamped;

  console.log(`${dry ? "WOULD MOVE" : "MOVED"}  ${gRef} -> ${nRef}`);
  console.log(`      ${stamped}`);
  if (!dry) {
    // Write the note first. If this fails the guidance is still in its cell.
    await api("PUT", `/values/${encodeURIComponent(nRef)}?valueInputOption=RAW`, { values: [[merged]] });
    await api("PUT", `/values/${encodeURIComponent(gRef)}?valueInputOption=RAW`, { values: [[""]] });
  }
  done++;
}

console.log(`\n${dry ? "would archive" : "archived"}: ${done}   skipped: ${skipped}`);
if (!dry && done) {
  console.log("Remember the change log row: node build-changelog.mjs after adding it to the list in that file.");
}
