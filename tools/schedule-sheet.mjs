// Build the "Recommended Schedule" tab.
//
// Blocks are queues, not timetables: each block has a start and a stop, and its
// shows play back to back until the stop. Episode lengths vary threefold within
// a single show, so per-episode times cannot fill a window continuously.
import { readFileSync } from "node:fs";
import { GoogleAuth } from "google-auth-library";

const ID = "<SHEET_ID>";
const TAB = "Recommended Schedule";
const stats = JSON.parse(readFileSync("podcast-stats.json", "utf8"));
const legacy = JSON.parse(readFileSync("sheet-legacy.json", "utf8"));

const auth = new GoogleAuth({ keyFile: "./service-account.json", scopes: ["https://www.googleapis.com/auth/spreadsheets"] });
const client = await auth.getClient();
const api = (m, u, d) => client.request({ method: m, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}`, data: d });

const find = (n) => stats.find((s) => s.name === n) || stats.find((s) => s.name.startsWith(n));
const mins = (n) => find(n)?.durMedian ?? null;
const label = (n) => (n.startsWith("Music") ? "—" : (mins(n) ? `${mins(n)}m` : "—"));

const R = "Random", N = "Newest", M = "—";

const BLOCKS = [
  { id: "A", name: "Morning Launch", time: "07:30 – 08:00", who: "Kids", mins: 30,
    idea: "A Torah thought, the day's headlines, one science idea. Short items only — nothing that can overrun the school run." },
  { id: "B", name: "Sarah's Day", time: "08:00 – 15:50", who: "Sarah", mins: 470,
    idea: "Long-form listening while the house is quiet. Opens with The Mindset Mentor every day, per your note that she likes it and would rather repeat it than miss it." },
  { id: "C", name: "Landing", time: "16:00 – 16:30", who: "Kids", mins: 30,
    idea: "Kids walk in — music first to decompress, then one short, funny, factual show." },
  { id: "D", name: "Family Table", time: "16:30 – 19:30", who: "Kids + Sarah", mins: 180,
    idea: "The main block, both audiences present. Rabbi Breitowitz anchors Tuesday and Thursday — his own publishing days, and moved earlier as your TODO asked." },
  { id: "E", name: "Teen Evening", time: "19:30 – 21:30, Sun–Thu", who: "Your 15-year-old", mins: 120,
    idea: "Longer, more demanding material once the younger ones are down. TED Talks Daily sits at the back end of each night — your note suggested a last slot around 8pm." },
  { id: "F", name: "Erev Shabat", time: "Fri, from 15:10 (≈12:10 in winter)", who: "Everyone", mins: null,
    idea: "Parsha, then music. Needs no end time: the app blocks playback for Shabat and mutes the speaker 15 minutes before it starts." },
  { id: "G", name: "Motzaei Shabat — kids", time: "Shabat ends + 30 min → 20:30", who: "Kids", mins: null,
    idea: "Anchored to nightfall, not the clock. Read the seasonal warning in section 5 — this window is 2½ hours in midwinter and under 10 minutes in midsummer." },
  { id: "H", name: "Motzaei Shabat — teen", time: "20:30 – 21:30", who: "Your 15-year-old", mins: 60,
    idea: "A fixed hour that works all year, unlike the kids' window above." },
];

const Q = {
  "A|Every day": [
    ["TorahAnytime Daily Dose", R, "2-minute clip from a 1,951-episode archive — a Torah thought that cannot overrun"],
    ["Up First (NPR)", N, "The day's news in ~13 min. With The Indicator on Tuesdays this is the entire news diet"],
    ["Short Wave (NPR)", N, "One science idea, ~13 min, pitched right for 9–15"],
  ],
  "B|Sunday": [["The Mindset Mentor", R, "Sarah's daily opener"], ["Business Wars", R, ""], ["How I Built This with Guy Raz", R, ""], ["Hidden Brain", R, ""], ["Freakonomics Radio", R, ""]],
  "B|Monday": [["The Mindset Mentor", R, ""], ["Meaningful People", R, ""], ["Planet Money", R, ""], ["This American Life", N, ""]],
  "B|Tuesday": [["The Mindset Mentor", R, ""], ["Business Movers", R, ""], ["Cautionary Tales with Tim Harford", R, ""], ["Call Me Back", N, ""], ["Revisionist History", R, ""]],
  "B|Wednesday": [["The Mindset Mentor", R, ""], ["Business Wars", R, ""], ["Unpacking Israeli History", R, ""], ["SeforimChatter", R, ""], ["Hidden Brain", R, ""]],
  "B|Thursday": [["The Mindset Mentor", R, ""], ["How I Built This with Guy Raz", R, ""], ["Jewish History Nerds", R, ""], ["Stuff You Should Know", R, ""], ["Planet Money", R, ""]],
  "C|Every day": [
    ["Music — your YTM playlists", M, "Rotate the existing playlists (Best, Pearl Jam, Effervescent Poodles…) to decompress"],
    ["Who Smarted?", R, "Funny, factual, 16 min — lands across the whole 9–15 range"],
  ],
  "D|Sunday — Torah & Science": [
    ["A Book Like No Other (Aleph Beta)", R, "Close Tanach reading. Only 5 episodes in the feed, so expect repeats"],
    ["SciShow Tangents", R, "Science panel game, genuinely funny. 4.9 stars, 338-episode archive"],
    ["Business Wars", R, "Sarah's favourite, and the rivalry stories hold teenagers easily"],
    ["Stuff You Should Know", R, "Your note said 2 more a week — this is one of them. 2,866 episodes to draw on"],
  ],
  "D|Monday — People & Stories": [
    ["Jews You Should Know", R, "Biography interviews. Publisher says: permanent hiatus, but the archive stays available. 288 episodes, 4.9"],
    ["Planet Money", R, "Economics as storytelling; the clearest on-ramp to business thinking for kids"],
    ["99% Invisible", R, "Design and the built world — changes how you look at ordinary things"],
    ["Wow in the World", R, "Lighter science for the younger end of the range"],
  ],
  "D|Tuesday — Rabbi Breitowitz": [
    ["The Q & A with Rabbi Breitowitz", R, "The best-rated show in your whole catalog: 4.9 from 247 ratings, 385 episodes. Tuesday is one of his publishing days"],
    ["The Indicator from Planet Money", N, "9 minutes of business/econ news — the rest of the 'basic news' diet"],
    ["Greeking Out from National Geographic Kids", R, "Mythology, well told; strong general knowledge"],
    ["SciShow Tangents", R, "Fills the tail of the block"],
  ],
  "D|Wednesday — Story & Design": [
    ["Cautionary Tales with Tim Harford", R, "True stories of things going wrong, with the lesson drawn out. Superb for this age"],
    ["Revisionist History", R, "Gladwell re-examining what everyone thinks they know"],
    ["Stuff You Should Know", R, "The second of your 'two more a week'"],
    ["Who Smarted?", R, "Light finish"],
  ],
  "D|Thursday — Breitowitz & Community": [
    ["The Q & A with Rabbi Breitowitz", R, "His second publishing day, and the 'one more a week' your TODO asked for"],
    ["Behind the Bima", R, "Community and rabbinic conversation, ~70m"],
    ["Smash Boom Best", R, "Structured debate — teaches argument, and kids pick sides"],
  ],
  "E|Sunday": [["Orthodox Conundrum", R, "Recommended by Aharon, per your notes"], ["StarTalk Radio", R, "Your note: can do one more a week"], ["TED Talks Daily", N, "Short, at the back of the evening"]],
  "E|Monday": [["The School of Greatness", R, "Your note: one more a week"], ["Something You Should Know", R, "Your note: one more a week"], ["TED Talks Daily", N, ""]],
  "E|Tuesday": [["18Forty", R, "Serious Jewish thought, ~80m"], ["The Indicator from Planet Money", N, ""], ["TED Talks Daily", N, ""]],
  "E|Wednesday": [["SeforimChatter", R, "Your note: one more a week. 5.0 stars"], ["Unpacking Israeli History", R, "Recommended by Avi"], ["TED Talks Daily", N, ""]],
  "E|Thursday": [["Call Me Back", N, "Recommended by Avi — current affairs, Israel focus"], ["Freakonomics Radio", R, ""], ["TED Talks Daily", N, ""]],
  "F|Friday": [
    ["A Book Like No Other (Aleph Beta)", R, "Parsha-adjacent Torah for erev Shabat"],
    ["Parsha Perspectives", R, "Recommended by Avi. Replaces Into the Verse, which your TODO asked to swap out"],
    ["Music — your YTM playlists", M, "Runs until the app mutes for Shabat"],
  ],
  "G|Motzaei Shabat (kids)": [
    ["TorahAnytime Daily Dose", R, "Deliberately first: in midsummer this whole window is under 10 minutes, so lead with something that always fits"],
    ["Jewish History Nerds", R, "Jewish history, accessible to the kids"],
    ["Music — your YTM playlists", M, ""],
  ],
  "H|Motzaei Shabat (teen)": [
    ["Meaningful People", N, "Publishes on Saturdays, so the newest episode is genuinely new at this point in the week"],
    ["TED Talks Daily", N, ""],
  ],
};

const WEEK = [
  ["Block", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Motzaei Shabat"],
  ["A · Morning Launch\n07:30–08:00 · Kids", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "Torah · News · Science", "(kids leave later)", "—"],
  ["B · Sarah's Day\n08:00–15:50", "Mindset + business", "Mindset + people", "Mindset + ideas", "Mindset + Israel", "Mindset + founders", "(short — erev Shabat)", "—"],
  ["C · Landing\n16:00–16:30 · Kids", "Music + Who Smarted?", "Music + Who Smarted?", "Music + Who Smarted?", "Music + Who Smarted?", "Music + Who Smarted?", "—", "—"],
  ["D · Family Table\n16:30–19:30 · Kids + Sarah", "Torah & Science", "People & Stories", "★ Rabbi Breitowitz", "Story & Design", "★ Breitowitz & Community", "—", "—"],
  ["E · Teen Evening\n19:30–21:30 · 15-year-old", "Orthodox Conundrum\n+ StarTalk", "School of Greatness\n+ SYSK", "18Forty", "SeforimChatter\n+ Israeli History", "Call Me Back\n+ Freakonomics", "—", "—"],
  ["F/G/H · Shabat edges", "—", "—", "—", "—", "—", "Parsha + music\nfrom ~15:10", "Kids: ends+30 → 20:30\nTeen: 20:30 → 21:30"],
];

const rows = [];
const fmt = [];
const push = (arr, kind) => { rows.push(arr); if (kind) fmt.push({ r: rows.length - 1, kind }); };

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
for (const [key, list] of Object.entries(Q)) {
  const [bid, day] = key.split("|");
  const b = BLOCKS.find((x) => x.id === bid);
  push([`${bid} · ${b.name} — ${day}`], "sub2");
  list.forEach(([show, mode, why], i) => push(["", String(i + 1), show, label(show), mode, why]));
  const total = list.reduce((s, [n]) => s + (n.startsWith("Music") ? 0 : (mins(n) || 0)), 0);
  push(["", "", "Queue length (median episodes)", `${Math.floor(total / 60)}h ${total % 60}m`, "",
    b.mins ? `Block is ${Math.floor(b.mins / 60)}h ${b.mins % 60}m — the queue continues from the top, drawing fresh episodes, to fill it` : "Runs to the block's end"], "total");
}
push([]);

push(["4 · How it plays"], "section");
[
  ["Continuous, not timed", "Each block is a queue. When an episode ends the next starts immediately, so a 22-minute episode and a 78-minute one both simply flow on. Only the block's start and stop are fixed."],
  ["Random vs newest", "'Random' draws from the whole back catalogue — right for evergreen shows and archives. 'Newest' is for news and for feeds that mix formats, where a random pick lands on the wrong thing."],
  ["Shabat and Yom Tov", "No Friday end time is needed. The app blocks all playback for Shabat and Yom Tov and mutes the speaker 15 minutes before it begins."],
  ["Motzaei Shabat", "Uses the 'Shabat/Yom Tov ends' anchor built this week, so it follows nightfall through the year instead of drifting against a clock time."],
  ["Music", "Music entries mean your existing YTM Trigger playlists. Rotating them keeps the kids' blocks from feeling like school."],
].forEach(([k, v]) => push(["", k, v], "note"));
push([]);

push(["5 · Things you should know before we build this"], "section");
push(["Issue", "Detail"], "head");
[
  ["The motzaei Shabat window collapses in summer",
   "You asked for Shabat-end + 30 min until 20:30. In late December Shabat ends about 17:30, so that is a 2½-hour block. In late August it ends about 19:51, so the window is 20:21 → 20:30 — nine minutes. That is why the kids' motzaei queue leads with a 2-minute item. If you would rather it stayed useful all year, either let it run past 20:30 in summer or drop it between about May and September."],
  ["Your sheet disagrees with the hours you gave me",
   "The Weekly tab said: 'Hallel gets home about 13:45 every day', 'Miryam gets home about 14:00 every day', 'Aharon gets home 17:40–18:00 every day, except Tuesdays at 15:25'. You told me the kids are around from 16:00. I have built to what you told me, but if those older lines are still true then roughly two hours of Sarah's block each afternoon actually has children in it, and should be family content instead."],
  ["A Book Like No Other has only 5 episodes",
   "It carries the Sunday and Friday Torah slots but the feed is tiny, so random play will repeat quickly. Worth pairing with another Aleph Beta feed or accepting the repetition."],
  ["Jews You Should Know needs length-aware picking",
   "Your note asked for it in a small slot on Sunday and a long slot after Wednesday. The feed mixes 3-minute Friday episodes with 45–100 minute interviews, and neither Google Home nor the app can currently filter by length — so it is scheduled as one Monday slot for now."],
].forEach((r) => push(r, "change"));
push([]);

push(["6 · What I changed, and why"], "section");
push(["Change", "Show", "Reasoning"], "head");
[
  ["Added — major fix", "The Q & A with Rabbi Breitowitz", "You were right, this was a bad miss. It is the highest-rated show in your catalog (4.9 from 247) with 385 episodes. Now anchors Family Table on both Tuesday and Thursday — his own publishing days, and earlier in the evening as your TODO asked."],
  ["Dropped — your note", "Life Kit", "'I think we should stop — PC and off topic.'"],
  ["Dropped — your note", "Radiolab", "'Terribly anti-Israel. Remove.'"],
  ["Dropped — your note", "60-Second Science / Science Quickly", "'Actually cancel, due to uncomfortable content.' I had planned it for the morning block; removed."],
  ["Dropped — your note", "Curiosity Daily", "'Has been coming on at 15:00, which has uncomfortable talk.' Short Wave does the same job."],
  ["Dropped — your note", "ABC News Nightline", "'Low priority. Get rid of for something else.'"],
  ["Not scheduled — your note", "Consider This from NPR", "'Quite liberal. Reevaluate.' Left in the catalog, not in the schedule."],
  ["Not scheduled — your note", "The Intelligence", "'Replace with something else — don't like Israel [coverage]'."],
  ["Played more — your note", "The Mindset Mentor", "'Sarah likes this a lot… so play it more, despite or even intentionally repeating.' Now opens her block every single day."],
  ["Played more — your note", "Stuff You Should Know", "'Can do 2 more/week' — added Sunday and Wednesday."],
  ["Played more — your note", "SeforimChatter, StarTalk, Something You Should Know, School of Greatness", "Each marked 'can do 1 more/week'; each gains a slot in the teen evening."],
  ["Moved later — your note", "TED Talks Daily", "'Hard to place because of the duration variability… maybe last slot at 8PM?' It now closes the teen evening every night, where an 8- or 60-minute episode does no harm."],
  ["Swapped out — your note", "Into the Verse", "'Swap with something else.' Replaced by Parsha Perspectives (recommended by Avi) and A Book Like No Other."],
  ["Removed — as asked", "Honestly with Bari Weiss / WIRED Business", "Both also dead: 131 and 721 days without an episode."],
  ["Kept as archive", "Jews You Should Know, SciShow Tangents, This Day in History", "No longer publishing, but the archives are excellent and evergreen."],
  ["Not scheduled", "Making Sense (Sam Harris)", "Left in the catalog. Harris is one of the best-known public critics of religion and much of the back catalogue argues against religious belief directly — not something to put in front of the kids."],
].forEach((r) => push(r, "change"));
push([]);
push(["Nothing here has been applied to Google Home or the YTM Trigger app yet."], "sub");

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
  tot: { red: 0.97, green: 0.97, blue: 0.97 },
  white: { red: 1, green: 1, blue: 1 },
};
const req = [
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 0, endIndex: 1 }, properties: { pixelSize: 250 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 1, endIndex: 2 }, properties: { pixelSize: 210 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 2, endIndex: 3 }, properties: { pixelSize: 270 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 3, endIndex: 5 }, properties: { pixelSize: 115 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 5, endIndex: 6 }, properties: { pixelSize: 560 }, fields: "pixelSize" } },
  { updateDimensionProperties: { range: { sheetId, dimension: "COLUMNS", startIndex: 6, endIndex: 8 }, properties: { pixelSize: 200 }, fields: "pixelSize" } },
  { repeatCell: { range: { sheetId, startRowIndex: 0, endRowIndex: rows.length, startColumnIndex: 0, endColumnIndex: 8 },
      cell: { userEnteredFormat: { wrapStrategy: "WRAP", verticalAlignment: "TOP", textFormat: { fontSize: 10 } } },
      fields: "userEnteredFormat(wrapStrategy,verticalAlignment,textFormat)" } },
];
for (const { r, kind } of fmt) {
  const range = { sheetId, startRowIndex: r, endRowIndex: r + 1, startColumnIndex: 0, endColumnIndex: 8 };
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

const unmatched = [...new Set(Object.values(Q).flat().map(([n]) => n))].filter((n) => !n.startsWith("Music") && !find(n));
console.log(unmatched.length ? `UNMATCHED SHOWS: ${unmatched.join(", ")}` : "all show names resolve");
