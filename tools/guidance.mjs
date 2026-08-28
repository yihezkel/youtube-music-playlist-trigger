// Read the "Change guidance from us" columns Jason fills in by hand.
//
//   node guidance.mjs            list anything pending
//   node guidance.mjs --json     the same, as JSON
//   node guidance.mjs --notify   list it, and raise a GitHub issue if any is
//                                pending and no open one already exists
//
// Exit code is 0 when nothing is pending and 10 when something is, so a
// scheduled task can branch on it without parsing the output.
//
// This only ever reads the sheet. Applying guidance changes the schedule, which
// is a judgement call, so it stays a thing a person asks for in a chat; see
// archive-guidance.mjs for the bookkeeping half once a change has been made.
import { GoogleAuth } from "google-auth-library";
import { execSync } from "node:child_process";
import { writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const ID = "<SHEET_ID>";
const HEADER = "change guidance from us";

/**
 * Is this cell a real guidance column header, or just text that mentions one?
 *
 * The catalog's own legend has a row whose first cell is exactly "Change
 * guidance from us", explaining what the column is for. Matching on the words
 * alone treated that as a header and reported every legend row beneath it as
 * pending guidance - fifteen phantom items the first time the legend was
 * written. A real header always sits in a header row with another heading to
 * its left; the legend key sits in column A with nothing beside it.
 */
const isHeader = (row, c) =>
  String(row[c] ?? "").trim().toLowerCase() === HEADER &&
  c > 0 &&
  String(row[c - 1] ?? "").trim() !== "";

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (u) => client.request({ url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}` });

const colName = (i) => (i < 26
  ? String.fromCharCode(65 + i)
  : String.fromCharCode(64 + Math.floor(i / 26)) + String.fromCharCode(65 + (i % 26)));

/**
 * The label for a row, used to say what a piece of guidance is about.
 *
 * The catalog is one show per row, so column A names it. The schedule tab is
 * sections stacked in one grid, and its first column carries the block, so A
 * works there too - but a section's own heading is more useful when the row
 * label is blank.
 */
function subjectFor(vals, r) {
  const own = String((vals[r] || [])[0] || "").split("\n")[0].trim();
  if (own) return own;
  for (let i = r - 1; i >= 0 && i > r - 30; i--) {
    const s = String((vals[i] || [])[0] || "").split("\n")[0].trim();
    if (s) return `${s} (continued)`;
  }
  return "?";
}

const meta = (await api("?fields=sheets.properties")).data;
const pending = [];

for (const s of meta.sheets) {
  const tab = s.properties.title;
  const vals = (await api(`/values/${encodeURIComponent(`${tab}!A1:AZ400`)}`)).data.values || [];

  // A tab can hold several guidance columns - the schedule has one per section -
  // and each only governs the rows of its own section, which run until the next
  // guidance header appears in that same column.
  const headers = [];
  vals.forEach((row, r) => (row || []).forEach((cell, c) => {
    if (isHeader(row || [], c)) headers.push({ r, c });
  }));

  for (const h of headers) {
    const nextInColumn = headers
      .filter((o) => o.c === h.c && o.r > h.r)
      .reduce((min, o) => Math.min(min, o.r), Infinity);
    // Where a later section reuses this column for its own data, stop before it,
    // or every cell below would be read as guidance.
    const otherHeaderRows = headers.filter((o) => o.r > h.r).map((o) => o.r);
    const end = Math.min(nextInColumn, ...otherHeaderRows, vals.length);

    for (let r = h.r + 1; r < end; r++) {
      const text = String((vals[r] || [])[h.c] || "").trim();
      if (!text) continue;
      pending.push({
        tab,
        cell: `${colName(h.c)}${r + 1}`,
        subject: subjectFor(vals, r),
        text,
      });
    }
  }
}

if (process.argv.includes("--json")) {
  console.log(JSON.stringify(pending, null, 1));
} else if (!pending.length) {
  console.log("No change guidance pending.");
} else {
  console.log(`${pending.length} piece(s) of change guidance pending:\n`);
  for (const p of pending) {
    console.log(`  ${p.tab} ${p.cell}  [${p.subject}]`);
    console.log(`      ${p.text.replace(/\s+/g, " ")}\n`);
  }
}

if (pending.length && process.argv.includes("--notify")) {
  const TITLE = "Change guidance pending on the schedule sheet";
  try {
    // List open issues and match the title here, rather than asking the search
    // API. Search is a separate index and lags issue creation by anything from
    // seconds to minutes, so a run shortly after another would not see the
    // issue it had just opened and would file a duplicate.
    const raw = execSync("gh issue list --state open --limit 100 --json number,title", { encoding: "utf8" });
    const existing = JSON.parse(raw).find((i) => i.title === TITLE);
    if (!existing) {
      const body = [
        "The yellow \"Change guidance from us\" cells have something in them.",
        "",
        ...pending.map((p) => `- **${p.subject}** (${p.tab} ${p.cell}): ${p.text.replace(/\s+/g, " ")}`),
        "",
        "Open a Copilot CLI session in the repo and ask for the schedule to be reworked with this in mind.",
        "Closing this issue does not clear the guidance; that happens when the change is made.",
      ].join("\n");
      // Via a file, not the command line. A JSON-quoted argument keeps its
      // newlines as a literal backslash-n on Windows, which gh passes straight
      // through, and the issue arrives as one unreadable line.
      const tmp = join(tmpdir(), `ytm-guidance-${Date.now()}.md`);
      writeFileSync(tmp, body, "utf8");
      try {
        execSync(`gh issue create --title ${JSON.stringify(TITLE)} --body-file ${JSON.stringify(tmp)}`, { stdio: "inherit" });
      } finally {
        rmSync(tmp, { force: true });
      }
    } else {
      console.log(`Issue #${existing.number} is already open for this; not raising another.`);
    }
  } catch (e) {
    console.error(`Could not raise a GitHub issue: ${e.message.split("\n")[0]}`);
  }
}

process.exit(pending.length ? 10 : 0);
