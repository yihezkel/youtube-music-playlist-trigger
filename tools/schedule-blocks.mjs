// The listening schedule, in one place.
//
// This file is the only definition of what plays when. build-schedules.mjs
// turns it into app schedules and pushes them to the device; schedule-sheet.mjs
// renders the same data onto the "Schedule" tab.
//
// It exists because those two used to hold separate hand-maintained copies, and
// they had already drifted: the sheet showed music opening the Landing block
// when the app in fact plays it last, and it labelled Business Wars, Business
// Movers, Unpacking Israeli History and A Book Like No Other as "Random" when
// the app plays all four in order. Anything the sheet says about the schedule
// is now derived from the same values the device is given.

// Per-entry episode mode. News is only useful newest-first; serialised shows
// that number their parts have to run in order; everything else is an evergreen
// archive and is best shuffled. Without this a whole block had to share one
// setting, which is why Business Wars played "part 1" one morning and "part 5"
// of a different series the same afternoon.
export const MODE = {
  "Up First (NPR)": "newest",
  "Short Wave (NPR)": "newest",
  "The Indicator from Planet Money": "newest",
  "TED Talks Daily": "newest",
  "This American Life": "newest",
  "Call Me Back": "newest",
  "Meaningful People": "newest",
  "Business Wars": "sequential",
  "Business Movers": "sequential",
  "Unpacking Israeli History": "sequential",
  // Aleph Beta is deliberately not sequential: the rebuilt feed spans ten
  // separate series, so running it in order would jump between them.
};

/** How a mode reads on the sheet. Absent from MODE means the block default. */
export const MODE_LABEL = { newest: "Newest", sequential: "In order" };
/**
 * Shortest episode worth drawing, per show, in minutes.
 *
 * Some feeds carry two formats under one name, so a random draw is really a
 * draw between formats. Measured from the live feeds rather than guessed:
 *
 *   Jews You Should Know   288 eps. 73 of them are "Torah You Should Know",
 *                          a 3-7 minute Friday d'var Torah series that ran
 *                          Nov 2020 - Jun 2022 - a quarter of the feed. There
 *                          is nothing at all between 8 and 28 minutes, so any
 *                          floor in that band keeps the same 211 interviews
 *                          (median 54). They are also parsha-specific and
 *                          years old, so a random draw was usually the wrong
 *                          week as well as four minutes long.
 *   Smash Boom Best        237 eps, 45 under 10 minutes (trailers and shorts)
 *                          against a 30-45 minute debate format; empty from
 *                          15 to 25. Floor keeps 191 (81%), median 37.
 *   The School of Greatness  Different shape, and worth knowing: 1,978 eps
 *                          with 247 in the 5-10 minute band and no real gap
 *                          anywhere. That is a deliberate short format, not
 *                          an anomaly, so this floor is a preference for the
 *                          long interviews rather than a format separation.
 *                          Keeps 1,635 (83%), median 68.
 */
export const MIN_MINUTES = {
  "Jews You Should Know": 20,
  "Smash Boom Best": 20,
  "The School of Greatness": 20,
};
/** Median once MIN_MINUTES is applied, measured; used for block sizing. */
export const FILTERED_MEDIAN = {
  "Jews You Should Know": 54,
  "Smash Boom Best": 37,
  "The School of Greatness": 68,
};export const modeLabel = (name) =>
  name === MUSIC || isPlaylist(name) ? "—" : (MODE_LABEL[MODE[name]] || "Random");

// Music comes from the user's existing YT Music playlists, which live in the
// config's defaults; resolved at push time so renames there follow through.
export const MUSIC = "__MUSIC__";

/**
 * A specific YouTube Music playlist as a queue entry, by name.
 *
 * MUSIC means "the default rotation", which is the five music playlists, and
 * that is right where a block just wants something to wind down to. It is no
 * use for naming one particular playlist, which is what the four that used to
 * sit on the old disabled "Afternoon" schedule needed: Science
 * Misunderstandings, Quora, Old Sayings New Ears and Google Home are spoken
 * word, not background music, and belong at a chosen point in a day rather than
 * in the wind-down rotation.
 *
 * The name must match one in playlist-list.mjs, which is generated from the
 * device config, so a typo fails the build rather than arming a schedule that
 * plays nothing.
 */
export const PLAYLIST_PREFIX = "__PLAYLIST__";
export const playlist = (name) => `${PLAYLIST_PREFIX}${name}`;
export const isPlaylist = (s) => typeof s === "string" && s.startsWith(PLAYLIST_PREFIX);
export const playlistName = (s) => s.slice(PLAYLIST_PREFIX.length);

// ISO day numbers, as the app stores them.
export const SUN = 7, MON = 1, TUE = 2, WED = 3, THU = 4, FRI = 5, SAT = 6;
export const WEEKDAYS = [SUN, MON, TUE, WED, THU];
export const hm = (h, m = 0) => h * 60 + m;

// A queue is sized to outlast its block rather than to be replayed. Where a
// queue is shorter than its block the block restarts it from the top, which is
// fine for a half-hour window but meant Sarah's near-eight-hour day was three
// laps of the same five shows. Totals are checked by `node build-schedules.mjs`.
export const BLOCKS = [
  {
    id: "A", name: "Morning Launch", who: "Kids",
    time: "07:30 – 08:00", mins: 30, start: hm(7, 30), stop: hm(8, 0),
    idea: "A Torah thought, the day's headlines, one science idea. Short items only — nothing that can overrun the school run.",
    queues: [{
      label: "Every day", appName: "A Morning Launch", days: WEEKDAYS,
      shows: [
        ["TorahAnytime Daily Dose", "2-minute clip from a 1,951-episode archive — a Torah thought that cannot overrun"],
        ["Up First (NPR)", "The day's news in ~13 min. With The Indicator on Tuesdays this is the entire news diet"],
        ["Short Wave (NPR)", "One science idea, ~13 min, pitched right for 9–15"],
      ],
    }],
  },

  {
    id: "B", name: "Sarah's Day", who: "Sarah",
    time: "08:00 – 15:50", mins: 470, start: hm(8), stop: hm(15, 50),
    idea: "Long-form listening while the house is quiet. Opens with The Mindset Mentor every day, per your note that she likes it and would rather repeat it than miss it. Nearly eight hours, so each day carries a full day's worth of distinct shows rather than a short queue played over.",
    queues: [
      {
        label: "Sunday", appName: "B Sarah Sun", days: [SUN], shows: [
          ["The Mindset Mentor", "Sarah's daily opener"],
          ["Business Wars", "Her favourite; runs in order so a series holds together"],
          ["How I Built This with Guy Raz", ""],
          ["Hidden Brain", ""],
          ["Freakonomics Radio", ""],
          [playlist("Science Misunderstandings"), "One of your own playlists, off the old Afternoon routine that has been switched off for months"],
          ["Ask Haviv Anything", "4.9 stars — Israel and current affairs, in depth"],
          ["Philosophize This!", "4.8 stars — one idea per episode, no background needed"],
          ["Economist Radio", "Carried over from your Google Home daily routine, which the app had never picked up"],
          ["TechStuff", "How a technology actually works, without assuming you know"],
          ["History for the Curious", "4.9 stars"],
          ["Uncanny Valley (WIRED)", "Also carried over from Google Home"],
          ["Shapell's Virtual Beit Midrash", "Torah shiurim, 500 in the archive"],
          ["The Office of Rabbi Sacks", "10 minutes — a short close to the day"],
        ],
      },
      {
        label: "Monday", appName: "B Sarah Mon", days: [MON], shows: [
          ["The Mindset Mentor", "Sarah's daily opener"],
          ["Meaningful People", ""],
          ["Planet Money", ""],
          ["This American Life", "Newest only — the feed holds just 15 episodes"],
          [playlist("Quora"), "One of your own playlists, off the old Afternoon routine"],
          ["Ask Haviv Anything", ""],
          ["Philosophize This!", ""],
          ["Shapell's Virtual Beit Midrash", ""],
          ["TechStuff", ""],
          ["Economist Radio", ""],
          ["Uncanny Valley (WIRED)", ""],
          ["History for the Curious", ""],
          ["Curiosity Weekly", "12 minutes of science news"],
          ["The Office of Rabbi Sacks", ""],
        ],
      },
      {
        label: "Tuesday", appName: "B Sarah Tue", days: [TUE], shows: [
          ["The Mindset Mentor", "Sarah's daily opener"],
          ["Business Movers", "In order, like Business Wars"],
          ["Cautionary Tales with Tim Harford", ""],
          ["Call Me Back", "Newest — current affairs only works fresh"],
          ["Revisionist History", ""],
          [playlist("Old Sayings, New Ears"), "One of your own playlists, off the old Afternoon routine"],
          ["Ask Haviv Anything", ""],
          ["Shapell's Virtual Beit Midrash", ""],
          ["Economist Radio", ""],
          ["Philosophize This!", ""],
          ["TechStuff", ""],
          ["Uncanny Valley (WIRED)", ""],
          ["History for the Curious", ""],
          ["Curiosity Weekly", ""],
          ["The Office of Rabbi Sacks", ""],
        ],
      },
      {
        label: "Wednesday", appName: "B Sarah Wed", days: [WED], shows: [
          ["The Mindset Mentor", "Sarah's daily opener"],
          ["Business Wars", ""],
          ["Unpacking Israeli History", "In order — the series build on each other"],
          ["SeforimChatter", ""],
          ["Hidden Brain", ""],
          ["18Forty - Exploring Big Jewish Ideas", "4.8 stars, long-form Jewish thought"],
          [playlist("Google Home"), "One of your own playlists, off the old Afternoon routine"],
          ["Ask Haviv Anything", ""],
          ["Economist Radio", ""],
          ["Philosophize This!", ""],
          ["Uncanny Valley (WIRED)", ""],
          ["TechStuff", ""],
          ["Curiosity Weekly", ""],
          ["The Office of Rabbi Sacks", ""],
        ],
      },
      {
        label: "Thursday", appName: "B Sarah Thu", days: [THU], shows: [
          ["The Mindset Mentor", "Sarah's daily opener"],
          ["How I Built This with Guy Raz", ""],
          ["Jewish History Nerds", ""],
          ["Stuff You Should Know", "Your note asked for two more a week — this is one"],
          ["Planet Money", ""],
          ["Shapell's Virtual Beit Midrash", ""],
          ["History for the Curious", ""],
          ["Ask Haviv Anything", ""],
          ["Philosophize This!", ""],
          ["Economist Radio", ""],
          ["TechStuff", ""],
          ["Uncanny Valley (WIRED)", ""],
          ["Curiosity Weekly", ""],
          ["The Office of Rabbi Sacks", ""],
        ],
      },
    ],
  },

  {
    id: "C", name: "Landing", who: "Kids",
    time: "16:00 – 16:30", mins: 30, start: hm(16), stop: hm(16, 30),
    idea: "Kids walk in. One short, funny, factual show, then music to decompress into the evening.",
    queues: [{
      // Music sits last as a wind-down. It no longer has to: the app now polls
      // for the end of a playlist, so a queue can carry on past one.
      label: "Every day", appName: "C Landing", days: WEEKDAYS,
      shows: [
        ["Who Smarted?", "Funny, factual, 16 min — lands across the whole 9–15 range"],
        [MUSIC, "Rotate the existing playlists (Best, Pearl Jam, Effervescent Poodles…). If the playlist runs out the queue starts again rather than going quiet"],
      ],
    }],
  },

  {
    id: "D", name: "Family Table", who: "Kids + Sarah",
    time: "16:30 – 19:30", mins: 180, start: hm(16, 30), stop: hm(19, 30),
    idea: "The main block, both audiences present. Rabbi Breitowitz anchors Tuesday and Thursday — his own publishing days, and moved earlier as your TODO asked.",
    queues: [
      {
        label: "Sunday — Torah & Science", appName: "D Family Sun", days: [SUN], shows: [
          ["Aleph Beta", "Rabbi Fohrman's close Tanach reading. Drawn from a feed rebuilt out of Aleph Beta's own published episode metadata: 136 episodes rather than the four their public feed carries"],
          ["SciShow Tangents", "Science panel game, genuinely funny. 4.9 stars, 338-episode archive"],
          ["Business Wars", "Sarah's favourite, and the rivalry stories hold teenagers easily"],
          ["Stuff You Should Know", "Your note said 2 more a week — this is one of them. 2,867 episodes to draw on"],
          ["Greeking Out from National Geographic Kids", ""],
          ["Wow in the World", "Lighter science for the younger end"],
        ],
      },
      {
        label: "Monday — People & Stories", appName: "D Family Mon", days: [MON], shows: [
          ["Jews You Should Know", "Biography interviews. Publisher says permanent hiatus, but the archive stays available"],
          ["Planet Money", "Economics as storytelling; the clearest on-ramp to business thinking for kids"],
          ["99% Invisible", "Design and the built world — changes how you look at ordinary things"],
          ["Wow in the World", "Lighter science for the younger end of the range"],
          ["SciShow Tangents", ""],
          ["Who Smarted?", "Light finish"],
        ],
      },
      {
        label: "Tuesday — Rabbi Breitowitz", appName: "D Family Tue - Rabbi Breitowitz", days: [TUE], shows: [
          ["The Q & A with Rabbi Breitowitz", "The best-rated show in your whole catalog: 4.9 from 247 ratings, 385 episodes"],
          ["The Indicator from Planet Money", "9 minutes of business/econ news — the rest of the 'basic news' diet"],
          ["Greeking Out from National Geographic Kids", "Mythology, well told; strong general knowledge"],
          ["SciShow Tangents", ""],
          ["Smash Boom Best", "Structured debate — teaches argument, and kids pick sides"],
        ],
      },
      {
        label: "Wednesday — Story & Design", appName: "D Family Wed", days: [WED], shows: [
          ["Cautionary Tales with Tim Harford", "True stories of things going wrong, with the lesson drawn out"],
          ["Revisionist History", "Gladwell re-examining what everyone thinks they know"],
          ["Stuff You Should Know", "The second of your 'two more a week'"],
          ["Who Smarted?", "Light finish"],
          ["99% Invisible", ""],
          ["Wow in the World", ""],
        ],
      },
      {
        label: "Thursday — Breitowitz & Community", appName: "D Family Thu - Rabbi Breitowitz", days: [THU], shows: [
          ["The Q & A with Rabbi Breitowitz", "His second publishing day, and the 'one more a week' your TODO asked for"],
          ["Behind the Bima", "Community and rabbinic conversation, ~70m"],
          ["Smash Boom Best", "Structured debate — teaches argument, and kids pick sides"],
        ],
      },
    ],
  },

  {
    id: "E", name: "Teen Evening", who: "Your 15-year-old",
    time: "19:30 – about 21:30, Sun–Thu", mins: 120, start: hm(19, 30), stop: null, endsWithQueue: true,
    idea: "Longer, more demanding material once the younger ones are down. Last block of the night, so it is not cut off: the queue is sized to land near 21:30 and the final episode is allowed to finish. TED Talks Daily sits at the back of each night — your note suggested a last slot around 8pm.",
    queues: [
      {
        label: "Sunday", appName: "E Teen Sun", days: [SUN], shows: [
          ["Orthodox Conundrum", "Recommended by Aharon, per your notes"],
          ["StarTalk Radio", "Your note: can do one more a week"],
          ["TED Talks Daily", "Short, at the back of the evening"],
        ],
      },
      {
        label: "Monday", appName: "E Teen Mon", days: [MON], shows: [
          ["The School of Greatness", "Your note: one more a week"],
          ["Something You Should Know", "Your note: one more a week"],
          ["TED Talks Daily", ""],
        ],
      },
      {
        label: "Tuesday", appName: "E Teen Tue", days: [TUE], shows: [
          ["18Forty - Exploring Big Jewish Ideas", "Serious Jewish thought, ~80m"],
          ["The Indicator from Planet Money", ""],
          ["Philosophize This!", "Added so the night fills without looping back to 18Forty"],
          ["TED Talks Daily", ""],
        ],
      },
      {
        label: "Wednesday", appName: "E Teen Wed", days: [WED], shows: [
          ["SeforimChatter", "Your note: one more a week. 5.0 stars"],
          ["Unpacking Israeli History", "Recommended by Avi"],
          ["TED Talks Daily", ""],
        ],
      },
      {
        label: "Thursday", appName: "E Teen Thu", days: [THU], shows: [
          ["Call Me Back", "Recommended by Avi — current affairs, Israel focus"],
          ["Freakonomics Radio", ""],
          ["Curiosity Weekly", "Added so the night fills without looping"],
          ["TED Talks Daily", ""],
        ],
      },
    ],
  },

  {
    id: "F", name: "Erev Shabat", who: "Everyone",
    time: "Fri, from 15:10 (≈12:10 in winter)", mins: null, start: hm(15, 10), stop: null,
    idea: "Parsha, then music. Needs no end time: the app blocks playback for Shabat and mutes the speaker 15 minutes before it starts.",
    queues: [{
      label: "Friday", appName: "F Erev Shabat", days: [FRI], shows: [
        ["Aleph Beta", "Torah for erev Shabat, from the rebuilt 136-episode feed"],
        ["Parsha Perspectives", "Recommended by Avi. Replaces Into the Verse, which your TODO asked to swap out"],
        [MUSIC, "Runs until the app mutes for Shabat"],
      ],
    }],
  },

  {
    id: "G", name: "Motzaei Shabat — kids", who: "Kids",
    time: "Shabat ends + 30 min, runs 60 min", mins: 60,
    start: hm(20), stop: null, anchor: "ShabatYomTovEnd", offset: 30, autoStop: 60,
    idea: "Anchored to nightfall, not the clock, and stopped after a fixed hour rather than at a fixed 20:30 — that clock stop gave the kids two and a half hours in December and nine minutes in August. An hour is an hour all year. Music closes it out and is stopped by the same alarm.",
    queues: [{
      label: "Motzaei Shabat (kids)", appName: "G Motzaei Shabat - kids", days: [SAT], shows: [
        ["TorahAnytime Daily Dose", "Two minutes to open on"],
        ["Jewish History Nerds", "Jewish history, accessible to the kids"],
        [MUSIC, "Winds down the rest of the hour, and is stopped by the block's own stop"],
      ],
    }],
  },

  {
    id: "H", name: "Motzaei Shabat — teen", who: "Your 15-year-old",
    time: "When the kids' block ends, runs about 76 min", mins: 75,
    start: hm(20, 30), stop: null, endsWithQueue: true, mode: "Latest",
    startsAfter: "sched-g-motzaei-shabat-kids",
    idea: "Follows the kids' block rather than starting at a fixed 20:30, so it tracks nightfall through the year instead of colliding with the kids in summer and leaving a gap in winter. Sized around Meaningful People, whose typical episode is 76 minutes.",
    queues: [{
      label: "Motzaei Shabat (teen)", appName: "H Motzaei Shabat - teen", days: [SAT], shows: [
        ["Meaningful People", "Publishes on Saturdays, so the newest episode is genuinely new at this point in the week. At ~76m it fills the block on its own"],
      ],
    }],
  },
];

/** Every queue, flattened, with its block attached. */
export const queues = () =>
  BLOCKS.flatMap((b) => b.queues.map((q) => ({ block: b, ...q })));
