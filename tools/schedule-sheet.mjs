// Build the "Recommended Schedule" tab.
//
// Blocks are queues, not timetables: each block starts at a fixed time and
// plays its shows back to back until the block's stop time. That is the only
// way to fill a window continuously when episode lengths vary by 3x.
import { readFileSync } from "node:fs";
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const TAB = "Recommended Schedule";
const stats = JSON.parse(readFileSync("podcast-stats.json", "utf8"));

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (m, u, d) => client.request({ method: m, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}`, data: d });

const find = (n) => stats.find((s) => s.name === n) || stats.find((s) => s.name.startsWith(n));
const mins = (n) => { const s = find(n); return s?.durMedian ?? null; };
const label = (n) => { const m = mins(n); return m ? `${m}m` : "—"; };

// R = random from the archive; N = newest episode only.
const R = "Random", N = "Newest";

const BLOCKS = [
  { id: "A", name: "Morning Launch", time: "07:30 – 08:00", who: "Kids", mins: 30,
    idea: "A Torah thought, the day's headlines, one science idea. Short items only — nothing that can overrun the school run." },
  { id: "B", name: "The Long Day", time: "08:00 – 15:50", who: "Sarah", mins: 470,
    idea: "Long-form listening while the house is quiet. Business and narrative non-fiction, themed by day so it never feels samey." },
  { id: "C", name: "Landing", time: "16:00 – 16:30", who: "Kids", mins: 30,
    idea: "Kids walk in — music first to decompress, then one short, fun, factual show." },
  { id: "D", name: "Family Table", time: "16:30 – 19:30", who: "Kids + Sarah", mins: 180,
    idea: "The main block, with both audiences present. Torah, then something scientific or narrative, then lighter fare as the evening goes on." },
  { id: "E", name: "Erev Shabat", time: "Fri, from 15:10 (≈12:10 in winter)", who: "Everyone", mins: null,
    idea: "Parsha-focused, then music. Needs no end time: the app already stops and mutes 15 minutes before Shabat." },
  { id: "F", name: "Motzaei Shabat", time: "Shabat ends + 30 min", who: "Everyone", mins: 120,
    idea: "Uses the new Shabat-end anchor, so it follows nightfall through the year instead of drifting against it." },
];

// day -> ordered queue. Each entry: [show, mode, why]
const Q = {
  "A|Every day": [
    ["TorahAnytime Daily Dose", R, "2-minute clip from a 1,951-episode archive — a Torah thought that cannot overrun"],
    ["Up First (NPR)", N, "The day's news in ~13 min. This is the whole news diet — enough to stay informed, no more"],
    ["Short Wave (NPR)", N, "One science idea, ~13 min, pitched right for 9–15"],
  ],
  "C|Every day": [
    ["Music — your YTM playlists", "—", "Rotate the existing playlists (Best, Pearl Jam, Effervescent Poodles…) to decompress"],
    ["Who Smarted?", R, "Funny, factual, 16 min — lands well across the whole 9–15 range"],
  ],
  "D|Sunday — Torah & Science": [
    ["Into the Verse (Aleph Beta)", R, "Parsha-linked Torah. Archive only, but the parsha cycle repeats annually so it never goes stale"],
    ["SciShow Tangents", R, "Science panel game, genuinely funny. 4.9 stars; archive of 338"],
    ["Business Wars", R, "Sarah's favourite, and the rivalry stories hold teenagers easily"],
    ["Stuff You Should Know", R, "Deep bench (2,866 episodes) to fill whatever time is left"],
  ],
  "D|Monday — People & Stories": [
    ["Jews You Should Know", R, "Biography interviews. On permanent hiatus, but the publisher says the archive is the point — 288 episodes, 4.9"],
    ["Planet Money", R, "Economics as storytelling; the clearest on-ramp to business thinking for kids"],
    ["99% Invisible", R, "Design and the built world — changes how you look at ordinary things"],
    ["Wow in the World", R, "Lighter science for the younger end of the range"],
  ],
  "D|Tuesday — Big Jewish Ideas": [
    ["18Forty", R, "Serious Jewish thought. Long (median 80m), so it anchors the block"],
    ["The Indicator from Planet Money", N, "9 minutes of business/econ news — the second half of the 'basic news' diet"],
    ["Greeking Out from National Geographic Kids", R, "Mythology, well told; strong general knowledge"],
  ],
  "D|Wednesday — Story & Design": [
    ["A Book Like No Other (Aleph Beta)", R, "Close Tanach reading, same house as Into the Verse"],
    ["Cautionary Tales with Tim Harford", R, "True stories of things going wrong, with the lesson drawn out. Superb for this age"],
    ["Revisionist History", R, "Gladwell re-examining what everyone thinks they know"],
  ],
  "D|Thursday — Community & Curiosity": [
    ["Behind the Bima", R, "Community and rabbinic conversation, ~70m"],
    ["Smash Boom Best", R, "Structured debate — teaches argument, and kids pick sides"],
    ["Life Kit", N, "Practical how-to-live-well episodes, ~20m"],
  ],
  "B|Sunday": [["Business Wars", R, ""], ["How I Built This with Guy Raz", R, ""], ["Hidden Brain", R, ""], ["Freakonomics Radio", R, ""]],
  "B|Monday": [["Meaningful People", R, ""], ["Planet Money", R, ""], ["This American Life", N, ""], ["99% Invisible", R, ""]],
  "B|Tuesday": [["Business Movers", R, ""], ["Cautionary Tales with Tim Harford", R, ""], ["Call Me Back", N, ""], ["Revisionist History", R, ""]],
  "B|Wednesday": [["Business Wars", R, ""], ["Unpacking Israeli History", R, ""], ["Freakonomics Radio", R, ""], ["Hidden Brain", R, ""]],
  "B|Thursday": [["How I Built This with Guy Raz", R, ""], ["Jewish History Nerds", R, ""], ["Stuff You Should Know", R, ""], ["Planet Money", R, ""]],
  "E|Friday": [
    ["Into the Verse (Aleph Beta)", R, "Parsha, timed for erev Shabat"],
    ["Parsha Perspectives", R, "Second parsha voice if there is time"],
    ["Music — your YTM playlists", "—", "Runs until the app mutes for Shabat"],
  ],
  "F|Motzaei Shabat": [
    ["Meaningful People", R, "Long-form Jewish interviews to open the week"],
    ["Jewish History Nerds", R, "Jewish history, accessible to the kids"],
    ["Music — your YTM playlists", "—", ""],
  ],
};

const WEEK = [
  ["Block", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Motzaei Shabat"],
  ["A · Morning Launch\n07:30–08:00 · Kids", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "(kids leave later — skip)", "—"],
  ["B · The Long Day\n08:00–15:50 · Sarah", "Business & minds", "People & stories", "Business & ideas", "Business & Israel", "Founders & history", "(short — erev Shabat)", "—"],
  ["C · Landing\n16:00–16:30 · Kids", "Music + Who Smarted?", "Music + Who Smarted?", "Music + Who Smarted?", "Music + Who Smarted?", "Music + Who Smarted?", "—", "—"],
  ["D · Family Table\n16:30–19:30 · Kids + Sarah", "Torah & Science", "People & Stories", "Big Jewish Ideas", "Story & Design", "Community & Curiosity", "—", "—"],
  ["E/F · Shabat edges", "—", "—", "—", "—", "—", "Parsha + music\nfrom ~15:10", "Shabat ends +30m\nLong-form + music"],
];

// ---------------------------------------------------------------- build rows
const rows = [];
const fmt = [];           // {r, kind}
const push = (arr, kind) => { rows.push(arr); if (kind) fmt.push({ r: rows.length - 1, kind }); };

push(["Recommended Podcast Schedule"], "title");
push([`Built ${new Date().toISOString().slice(0, 10)} · kids 07:30–08:00 and 16:00–19:30 · Sarah 08:00–15:50 and 16:30–19:30 · Fridays the kids are home by 15:00 (about 12:00 once the clocks go back)`], "sub");
push([]);

push(["1 · The day at a glance"], "section");
push(["Block", "Time", "Who's home", "Roughly fills", "The idea"], "head");
for (const b of BLOCKS) {
  push([`${b.id} · ${b.name}`, b.time, b.who, b.mins ? `${Math.floor(b.mins / 60)}h ${b.mins % 60}m` : "until Shabat", b.idea]);
}
push([]);

push(["2 · The week"], "section");
push(WEEK[0], "head");
for (const r of WEEK.slice(1)) push(r, "week");
push([]);

push(["3 · What plays, in order"], "section");
push(["Block / day", "#", "Show", "Typical", "Episode", "Why it's here"], "head");
for (const [key, list] of Object.entries(Q)) {
  const [bid, day] = key.split("|");
  const b = BLOCKS.find((x) => x.id === bid);
  push([`${bid} · ${b.name} — ${day}`], "sub2");
  list.forEach(([show, mode, why], i) => {
    push(["", String(i + 1), show, show.startsWith("Music") ? "—" : label(show), mode, why]);
  });
  const total = list.reduce((s, [n]) => s + (n.startsWith("Music") ? 0 : (mins(n) || 0)), 0);
  push(["", "", "Queue length (median episodes)", `${Math.floor(total / 60)}h ${total % 60}m`, "", b.mins ? `Block is ${Math.floor(b.mins / 60)}h ${b.mins % 60}m — the queue repeats/continues to fill it` : "Runs until the Shabat mute"], "total");
}
push([]);

push(["4 · How it plays"], "section");
[
  ["Continuous, not timed", "Each block is a queue. When an episode ends the next one starts immediately, so a 22-minute episode and a 78-minute one both simply flow on. Only the block's start and stop times are fixed."],
  ["Random vs newest", "'Random' draws from the whole back catalogue — right for evergreen shows and for the archives below. 'Newest' is for news and for feeds that mix formats, where a random pick would land on the wrong thing."],
  ["Shabat and Yom Tov", "Nothing needs a Friday end time. The app already blocks all playback for Shabat and Yom Tov, and stops and mutes the speaker 15 minutes before it starts."],
  ["Motzaei Shabat", "Scheduled against the new 'Shabat/Yom Tov ends' anchor rather than a clock time, so it tracks nightfall through the year."],
  ["Music", "Music entries mean your existing YTM Trigger playlists. Rotating them keeps the kids' blocks from feeling like school."],
].forEach(([k, v]) => push(["", k, v], "note"));
push([]);

push(["5 · What I changed, and why"], "section");
push(["Change", "Show", "Reasoning"], "head");
[
  ["Removed", "Honestly with Bari Weiss", "As you asked. It had also gone quiet — nothing new for 131 days."],
  ["Removed", "WIRED Business", "As you asked. It had been dead since Sept 2024 anyway, and the news diet is already covered."],
  ["Replacement for Honestly", "Call Me Back (Dan Senor)", "Closest fit: serious current-affairs conversation with an Israel focus. Active, 4.8 from 3,102 ratings. Placed in Sarah's block."],
  ["On Sam Harris", "Making Sense", "High quality (4.6, 25,974) and I've left it in the catalog — but I would not schedule it for the kids: Harris is one of the best-known public critics of religion, and a chunk of the back catalogue argues against religious belief directly. Your call for your own block; Cautionary Tales and Revisionist History scratch a similar itch without that."],
  ["Kept as archive", "Jews You Should Know", "The feed itself says: 'permanent hiatus, but our rich archive remains fully available.' 288 episodes, 4.9 stars. Ideal random-play material."],
  ["Kept as archive", "SciShow Tangents", "Quiet since Sept 2025, but 338 episodes at 4.9 and pitched perfectly at 9–15."],
  ["Kept as archive", "Into the Verse", "Quiet since Jan 2025, but it is parsha-linked — the cycle repeats every year, so the archive stays current by definition."],
  ["Kept as archive", "This Day in History", "Ended 2024, but 1,999 episodes of 8-minute history. Useful short filler."],
  ["Dropped", "Real Simple Tips", "Ended 2024 and it is household tips — little learning value for the kids, and Sarah has better options."],
  ["Dropped", "Curiosity Daily", "Dormant, and the feed now carries only 19 episodes. Short Wave does the same job, better and actively."],
  ["Not actually stalled", "The Economist Asks", "Its standalone feed is a 2015 stub; the real show now publishes inside Economist Podcasts. Nothing to mourn — just point at the right feed."],
  ["Newest-episode only", "Science Quickly (was 60-Second Science)", "Feed mixes 3-minute and 15-minute items. Newest keeps it in the morning slot's budget."],
  ["Newest-episode only", "PBS NewsHour – Science", "Mixed 5–37 minutes; newest avoids a 37-minute surprise."],
  ["Dropped", "WSJ Tech News Briefing", "The feed is mostly 2-minute 'TNB Tech Minute' shorts several times a day. The Indicator gives better tech/business substance in 9 minutes."],
  ["Stays dropped", "Toras Avigdor", "Episode lengths run 5 to 903 minutes. Nothing sensible can be scheduled around that."],
].forEach((r) => push(r, "change"));
push([]);
push(["Everything above is a proposal — nothing has been applied to Google Home or the YTM Trigger app yet."], "sub");

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
  tot:  { red: 0.97, green: 0.97, blue: 0.97 },
  white: { red: 1, green: 1, blue: 1 },
};
const req = [
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 0, endIndex: 1 }, properties: { pixelSize: 250 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 1, endIndex: 2 }, properties: { pixelSize: 200 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 2, endIndex: 3 }, properties: { pixelSize: 260 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 3, endIndex: 6 }, properties: { pixelSize: 120 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 5, endIndex: 6 }, properties: { pixelSize: 520 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 6, endIndex: 8 }, properties: { pixelSize: 190 }, fields: "pixelSize" } },
  { repeatCell: { range: { sheetId, startRowIndex: 0, endRowIndex: rows.length, startColumnIndex: 0, endColumnIndex: 8 },
      cell: { userEnteredFormat: { wrapStrategy: "WRAP", verticalAlignment: "TOP", textFormat: { fontSize: 10 } } },
      fields: "userEnteredFormat(wrapStrategy,verticalAlignment,textFormat)" } },
];
for (const { r, kind } of fmt) {
  const range = { sheetId, startRowIndex: r, endRowIndex: r + 1, startColumnIndex: 0, endColumnIndex: 8 };
  if (kind === "title") {
    req.push({ mergeCells: { range, mergeType: "MERGE_ALL" } },
      { repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.navy, textFormat: { bold: true, fontSize: 18, foregroundColor: C.white }, verticalAlignment: "MIDDLE" } }, fields: "userEnteredFormat(backgroundColor,textFormat,verticalAlignment)" } },
      { updateDimensionProperties: { range: { sheetId, dimension: "ROWS", startIndex: r, endIndex: r + 1 }, properties: { pixelSize: 46 }, fields: "pixelSize" } });
  } else if (kind === "sub") {
    req.push({ mergeCells: { range, mergeType: "MERGE_ALL" } },
      { repeatCell: { range, cell: { userEnteredFormat: { textFormat: { italic: true, fontSize: 10, foregroundColor: { red: 0.35, green: 0.35, blue: 0.35 } } } }, fields: "userEnteredFormat.textFormat" } });
  } else if (kind === "section") {
    req.push({ mergeCells: { range, mergeType: "MERGE_ALL" } },
      { repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.band, textFormat: { bold: true, fontSize: 13, foregroundColor: C.navy }, verticalAlignment: "MIDDLE" } }, fields: "userEnteredFormat(backgroundColor,textFormat,verticalAlignment)" } },
      { updateDimensionProperties: { range: { sheetId, dimension: "ROWS", startIndex: r, endIndex: r + 1 }, properties: { pixelSize: 34 }, fields: "pixelSize" } });
  } else if (kind === "head") {
    req.push({ repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.head, textFormat: { bold: true } } }, fields: "userEnteredFormat(backgroundColor,textFormat)" } });
  } else if (kind === "sub2") {
    req.push({ mergeCells: { range, mergeType: "MERGE_ALL" } },
      { repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.sub2, textFormat: { bold: true } } }, fields: "userEnteredFormat(backgroundColor,textFormat)" } });
  } else if (kind === "total") {
    req.push({ repeatCell: { range, cell: { userEnteredFormat: { backgroundColor: C.tot, textFormat: { italic: true } } }, fields: "userEnteredFormat(backgroundColor,textFormat)" } });
  } else if (kind === "week") {
    req.push({ updateDimensionProperties: { range: { sheetId, dimension: "ROWS", startIndex: r, endIndex: r + 1 }, properties: { pixelSize: 46 }, fields: "pixelSize" } },
      { repeatCell: { range: { ...range, startColumnIndex: 0, endColumnIndex: 1 }, cell: { userEnteredFormat: { textFormat: { bold: true } } }, fields: "userEnteredFormat.textFormat" } });
  } else if (kind === "change") {
    req.push({ repeatCell: { range: { ...range, startColumnIndex: 0, endColumnIndex: 1 }, cell: { userEnteredFormat: { textFormat: { bold: true } } }, fields: "userEnteredFormat.textFormat" } });
  } else if (kind === "note") {
    req.push({ repeatCell: { range: { ...range, startColumnIndex: 1, endColumnIndex: 2 }, cell: { userEnteredFormat: { textFormat: { bold: true } } }, fields: "userEnteredFormat.textFormat" } });
  }
}
await api("POST", ":batchUpdate", { requests: req });
console.log(`wrote "${TAB}": ${rows.length} rows`);
