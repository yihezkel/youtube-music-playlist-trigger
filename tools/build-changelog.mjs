// Build the "Schedule change log" tab.
//
// Two sources. This session's changes are written out explicitly, because the
// assistant knows what it did and why. The user's own history is reconstructed
// by diffing the sheet's Drive revisions, which only exposes milestone
// snapshots - so those dates are "on or before", and many changes will have
// happened between milestones and be invisible.
import { readFileSync } from "node:fs";
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const TAB = "Schedule change log";
const TODAY = "2026-08-26";

const legacy = JSON.parse(readFileSync("sheet-legacy.json", "utf8"));
const { changes: historic } = JSON.parse(readFileSync("revision-changes.json", "utf8"));

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (m, u, d) => client.request({ method: m, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}`, data: d });

// --- reasons the user wrote, keyed loosely by show name ----------------------
const norm = (s) => String(s).toLowerCase().replace(/\(.*?\)/g, " ")
  .replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();
const noteBy = new Map(Object.entries(legacy.notes).map(([k, v]) => [norm(k), v]));
function reasonFor(show) {
  const k = norm(show);
  let note = noteBy.get(k);
  if (!note) {
    for (const [key, v] of noteBy) {
      if (key.length > 6 && (k.includes(key) || key.includes(k))) { note = v; break; }
    }
  }
  if (!note) return "";
  // Keep only the parts that read as a reason. A "When:" fragment is a
  // scheduling detail, and a TODO is something the user intends to do next -
  // neither explains why a show was added or dropped, and presenting one as
  // the reason would be worse than leaving the cell blank.
  const parts = note.replace(/^\[[^\]]*\]\s*/, "").split("|")
    .map((s) => s.trim())
    .filter((s) => s && !/^When:/i.test(s) && !/^TODO:/i.test(s));
  return parts.join(" | ");
}

// --- this session's changes --------------------------------------------------
const AI = "AI";
const ME = "Jason";
const mine = [
  ["Timing", "(all blocks)", "Whole schedule",
    "Rebuilt around the hours you gave me: kids 07:30-08:00 and 16:00-19:30, Sarah 08:00-15:50 and 16:30-19:30, 15-year-old to 21:30, Friday from 15:10 (about 12:10 in winter), motzaei Shabat anchored to nightfall. Covers every timing change made this session."],

  ["Added", "Ask Haviv Anything", "B - Sarah's Day (Sun, Mon, Wed, Thu)",
    "Sarah's block runs nearly eight hours but held only about three hours of shows, so it replayed the same five roughly three times a day. Promoted from your ideas list to help fill it; 4.9 stars."],
  ["Added", "Philosophize This!", "B - Sarah's Day (all days), E - Teen (Tue)",
    "Same reason - one idea per episode, no background needed. 4.8 stars from 15,362 ratings."],
  ["Added", "TechStuff", "B - Sarah's Day (all days)", "Same reason. How a technology actually works, 2,618 episodes to draw on."],
  ["Added", "History for the Curious", "B - Sarah's Day (Sun, Mon, Tue, Thu)", "Same reason. 4.9 stars."],
  ["Added", "Curiosity Weekly", "B - Sarah's Day (Mon-Thu), E - Teen (Thu)", "Same reason. Twelve minutes of science news, useful for filling the tail of a block."],
  ["Added", "The Office of Rabbi Sacks", "B - Sarah's Day (all days)", "Same reason. Ten minutes, so it closes a day without overrunning."],
  ["Added", "Shapell's Virtual Beit Midrash", "B - Sarah's Day (Sun, Mon, Thu)", "Same reason. Torah shiurim, 500 in the archive."],
  ["Added", "The Jordan B. Peterson Podcast", "B - Sarah's Day (Tue)",
    "Same reason, but flagged rather than assumed: long-form and strongly opinionated, so say if you would rather it were not there."],
  ["Added", "Economist Radio", "B - Sarah's Day (all days)",
    "Already on your Google Home daily routine but never carried into the app. Now in the app line-up."],
  ["Added", "Uncanny Valley (WIRED)", "B - Sarah's Day (all days)", "Also already on Google Home and missing from the app."],
  ["Added", "18Forty - Exploring Big Jewish Ideas", "B - Sarah's Day (Wed)", "Already in the teen block; added to Sarah's day as well."],

  ["Added", "Greeking Out from National Geographic Kids", "D - Family Table (Sun)", "Topping up the family block so it fills its three hours without replaying."],
  ["Added", "Wow in the World", "D - Family Table (Sun, Wed)", "Same reason."],
  ["Added", "SciShow Tangents", "D - Family Table (Mon)", "Same reason."],
  ["Added", "Who Smarted?", "D - Family Table (Mon)", "Same reason."],
  ["Added", "99% Invisible", "D - Family Table (Wed)", "Same reason."],
  ["Added", "Smash Boom Best", "D - Family Table (Tue)", "Same reason."],

  ["Not considered", "Making Sense with Sam Harris", "B - Sarah's Day",
    "Checked again rather than relying on the earlier conversation. The free feed mixes full episodes with paywall previews - 12 and 21 minute items next to 85 and 81 minute ones - so a random pick would often cut off mid-conversation. Left out until the app can filter by length."],

  ["Added", "The Q & A with Rabbi Breitowitz", "D - Family Table (Tue, Thu)",
    "Highest-rated show in the catalog (4.9 from 247). Was missing from my first draft; your TODO also asked to shift it earlier, so it now anchors two family blocks on its own publishing days."],
  ["Added", "TorahAnytime Daily Dose", "A - Morning Launch", "Two-minute Torah clip that cannot overrun the school run; 1,951-episode archive, 4.9 stars."],
  ["Added", "Up First (NPR)", "A - Morning Launch", "The whole news diet in ~13 minutes - enough to stay informed, no more."],
  ["Added", "Short Wave (NPR)", "A - Morning Launch", "One science idea a day, ~13 minutes, pitched right for 9-15."],
  ["Added", "The Indicator from Planet Money", "D - Family Table (Tue), E - Teen", "Nine minutes of business and economics news."],
  ["Added", "Planet Money", "B - Sarah's Day, D - Family Table", "Economics as storytelling; the clearest on-ramp to business thinking for the kids."],
  ["Added", "How I Built This with Guy Raz", "B - Sarah's Day", "Founder interviews, in the same vein as Business Wars which you already like."],
  ["Added", "Business Movers", "B - Sarah's Day", "Same producer and format as Business Wars."],
  ["Added", "Cautionary Tales with Tim Harford", "B - Sarah's Day, D - Family Table", "True stories of things going wrong with the lesson drawn out; strong for this age range."],
  ["Added", "Revisionist History", "B - Sarah's Day, D - Family Table", "Gladwell re-examining accepted stories."],
  ["Added", "99% Invisible", "B - Sarah's Day, D - Family Table", "Design and the built world."],
  ["Added", "Smash Boom Best", "D - Family Table (Thu)", "Structured debate; teaches argument and the kids pick sides."],
  ["Added", "Greeking Out from National Geographic Kids", "D - Family Table (Tue)", "Mythology and general knowledge."],
  ["Added", "Who Smarted?", "C - Landing, D - Family Table (Wed)", "Short, funny and factual; lands across the whole 9-15 range."],
  ["Added", "Wow in the World", "D - Family Table (Mon)", "Lighter science for the younger end."],
  ["Added", "Meaningful People", "B - Sarah's Day, H - Motzaei Shabat", "Long-form Jewish interviews; publishes on Saturdays so it is genuinely new motzaei Shabat."],
  ["Added", "Jewish History Nerds", "B - Sarah's Day, G - Motzaei Shabat", "Jewish history, accessible to the kids."],
  ["Added", "Hidden Brain", "B - Sarah's Day", "Promoted from your ideas list. Recommended by Avi."],
  ["Added", "Call Me Back", "B - Sarah's Day, E - Teen", "Promoted from your ideas list, and the replacement for Honestly. Recommended by Avi."],
  ["Added", "Unpacking Israeli History", "B - Sarah's Day, E - Teen", "Promoted from your ideas list. Recommended by Avi."],
  ["Added", "Parsha Perspectives", "F - Erev Shabat", "Promoted from your ideas list; replaces Into the Verse. Recommended by Avi."],

  ["Removed", "Honestly with Bari Weiss", "Weekly", "You asked to remove it. It had also gone quiet - nothing new for 131 days."],
  ["Removed", "WIRED Business", "News", "You asked to remove it. Also dead since September 2024."],
  ["Removed", "Life Kit", "Weekly", "Your note: I think we should stop - PC and off topic."],
  ["Removed", "Radiolab", "Weekly", "Your note: terribly anti-Israel. Remove."],
  ["Removed", "60-Second Science (now Science Quickly)", "Weekly", "Your note: actually cancel, due to uncomfortable content."],
  ["Removed", "Curiosity Daily", "News", "Your note: has been coming on at 15:00, which has uncomfortable talk. Short Wave does the same job, actively."],
  ["Removed", "ABC News Nightline", "Daily / News", "Your note: low priority, get rid of for something else."],
  ["Removed", "Into the Verse", "Daily", "Your TODO: swap with something else. Replaced by A Book Like No Other and Parsha Perspectives."],
  ["Removed", "WSJ Tech News Briefing", "Daily", "The feed is mostly two-minute TNB Tech Minute shorts several times a day, so a random pick almost never returns the real briefing."],
  ["Removed", "Real Simple Tips", "News", "Ended in 2024, and household tips carry little learning value for the kids."],
  ["Removed", "This Day in History", "News", "Ended in 2024. Kept in the catalog as an archive but not scheduled."],
  ["Removed", "TED Radio Hour", "Weekly", "Dropped to make room; TED Talks Daily covers the same ground in the teen evening."],
  ["Removed", "No Stupid Questions", "Weekly", "Dropped to make room in a rebuilt line-up."],
  ["Removed", "The Economist Asks", "Weekly", "Its standalone feed is a 2015 stub; the live show now publishes inside Economist Podcasts."],
  ["Removed", "Economist Radio", "Daily", "Your TODO: try to find something better that's daily."],
  ["Removed", "7 Good Minutes Daily Self-Improvement", "Daily", "Dropped to make room; The Mindset Mentor covers self-improvement and you noted Sarah likes it."],
  ["Removed", "Uncanny Valley (WIRED)", "Daily", "It changed format - you recorded 6-14 minutes, it now runs 28-50 - so it no longer fits the slot it was in."],
  ["Removed", "News routine (21 briefings)", "News", "The whole 16:37 news block. You asked for the kids to hear only the minimum reasonable amount of news, which Up First and The Indicator now cover. Six of the 21 were Assistant news briefs with no public feed and could not be moved to the app anyway."],
];

// --- rows --------------------------------------------------------------------
const rows = mine.map(([change, show, where, reason]) => [TODAY, AI, change, show, where, reason]);

for (const c of historic) {
  // Some cells had a URL pasted after the name; the log wants the show.
  const show = c.show.replace(/\s*https?:\/\/\S+/g, "").trim();
  rows.push([c.date, ME, c.change, show, "Weekly / Daily / News", reasonFor(c.show)]);
}
rows.sort((a, b) => (b[0].localeCompare(a[0])) || a[2].localeCompare(b[2]) || a[3].localeCompare(b[3]));

const HEAD = ["Date", "Changed by", "Change", "Show", "Where", "Reason"];
const note = [
  [],
  ["About this log"],
  ["This session's rows", "Written from what was actually changed on 26 Aug 2026. All timing changes are collapsed into the single 'Timing' row."],
  ["Your earlier rows", "Reconstructed by comparing the 38 revisions Google exposes for this file, back to 11 Mar 2022. Google only keeps milestone revisions, not every edit, so a date means 'on or before' and changes made and undone between milestones are invisible."],
  ["Blank reasons", "No note explaining the change was found anywhere on the sheet. Left blank rather than guessed at."],
];

const meta = (await api("GET", "?fields=sheets.properties")).data;
const old = meta.sheets.find((s) => s.properties.title === TAB);
if (old) await api("POST", ":batchUpdate", { requests: [{ deleteSheet: { sheetId: old.properties.sheetId } }] });
const made = await api("POST", ":batchUpdate", { requests: [{ addSheet: { properties: {
  title: TAB, gridProperties: { rowCount: rows.length + 20, columnCount: HEAD.length, frozenRowCount: 1 } } } }] });
const sheetId = made.data.replies[0].addSheet.properties.sheetId;
await api("PUT", `/values/${encodeURIComponent(TAB)}!A1?valueInputOption=RAW`, { values: [HEAD, ...rows, ...note] });

const dataEnd = 1 + rows.length;
const req = [
  { repeatCell: { range: { sheetId, startRowIndex: 0, endRowIndex: 1 },
      cell: { userEnteredFormat: { backgroundColor: { red: 0.12, green: 0.22, blue: 0.38 },
        textFormat: { bold: true, foregroundColor: { red: 1, green: 1, blue: 1 } }, verticalAlignment: "MIDDLE" } },
      fields: "userEnteredFormat(backgroundColor,textFormat,verticalAlignment)" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 0, endIndex: 1 }, properties: { pixelSize: 95 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 1, endIndex: 3 }, properties: { pixelSize: 95 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 3, endIndex: 4 }, properties: { pixelSize: 280 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 4, endIndex: 5 }, properties: { pixelSize: 210 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 5, endIndex: 6 }, properties: { pixelSize: 620 }, fields: "pixelSize" } },
  { repeatCell: { range: { sheetId, startRowIndex: 1, endRowIndex: dataEnd },
      cell: { userEnteredFormat: { wrapStrategy: "WRAP", verticalAlignment: "TOP" } },
      fields: "userEnteredFormat(wrapStrategy,verticalAlignment)" } },
  { setBasicFilter: { filter: { range: { sheetId, startRowIndex: 0, endRowIndex: dataEnd, startColumnIndex: 0, endColumnIndex: HEAD.length } } } },
  { repeatCell: { range: { sheetId, startRowIndex: dataEnd + 2, endRowIndex: dataEnd + 3 },
      cell: { userEnteredFormat: { textFormat: { bold: true, fontSize: 11 } } }, fields: "userEnteredFormat.textFormat" } },
];
const rule = (col, value, bg) => ({ addConditionalFormatRule: { rule: {
  ranges: [{ sheetId, startRowIndex: 1, endRowIndex: dataEnd, startColumnIndex: col, endColumnIndex: col + 1 }],
  booleanRule: { condition: { type: "TEXT_EQ", values: [{ userEnteredValue: value }] }, format: { backgroundColor: bg } } }, index: 0 } });
req.push(
  rule(2, "Added", { red: 0.82, green: 0.93, blue: 0.82 }),
  rule(2, "Removed", { red: 0.95, green: 0.86, blue: 0.86 }),
  rule(2, "Timing", { red: 0.87, green: 0.90, blue: 0.98 }),
  rule(1, "AI", { red: 0.93, green: 0.95, blue: 1.00 }),
);
await api("POST", ":batchUpdate", { requests: req });

console.log(`wrote "${TAB}": ${rows.length} rows`);
console.log(`  this session: ${mine.length}`);
console.log(`  reconstructed: ${historic.length} (${historic.length ? historic[0].date : "-"} .. ${historic.length ? historic[historic.length-1].date : "-"})`);
console.log(`  with a reason: ${rows.filter((r) => r[5]).length}`);
