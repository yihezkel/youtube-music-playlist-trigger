// Resolve every podcast in podcast-list.mjs to its public RSS feed, then derive
// the scheduling statistics the sheet needs. Writes podcast-stats.json.
//
// Uses the iTunes Search API to find feeds (free, no auth, no Premium) and then
// reads the feed directly. Deliberately avoids Spotify: its Web API needs a
// Premium account and its public pages only render a dozen episodes.
import { writeFileSync, readFileSync } from "node:fs";
import { PODCASTS } from "./podcast-list.mjs";

/** Feed URLs that must not be committed; absent is fine. */
const PRIVATE_FEEDS = (() => {
  try { return JSON.parse(readFileSync("private-feeds.json", "utf8")); } catch { return {}; }
})();

const UA = { "User-Agent": "Mozilla/5.0 (podcast-schedule-audit)" };
const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getText(url, ms = 30000) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, { headers: UA, signal: ctl.signal, redirect: "follow" });
    if (!r.ok) throw new Error("HTTP " + r.status);
    return await r.text();
  } finally { clearTimeout(t); }
}

// Cheap normalised similarity so an obviously wrong iTunes hit can be flagged
// rather than silently treated as fact.
const norm = (s) => s.toLowerCase().replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();
function similar(a, b) {
  const A = new Set(norm(a).split(" ")), B = new Set(norm(b).split(" "));
  let hit = 0; for (const w of A) if (B.has(w)) hit++;
  return hit / Math.max(A.size, 1);
}

async function resolveFeed(p) {
  // A feed we host ourselves, whose URL carries a token and so must not be
  // committed. Looked up by name from the gitignored private-feeds.json, and
  // never sent to the iTunes directory - it is not listed there.
  if (p.privateFeed && PRIVATE_FEEDS[p.name]) {
    return { feedUrl: PRIVATE_FEEDS[p.name], itunesId: null, itunesName: p.name, confidence: 100 };
  }
  const url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=8&term=" +
    encodeURIComponent(p.q || p.name);
  const j = JSON.parse(await getText(url));
  if (!j.results?.length) return null;
  const usable = j.results.filter((r) => r.feedUrl);
  if (!usable.length) return null;
  // `pick` names the exact show when the sheet's label no longer matches the
  // feed - renamed shows, or common words that pull in unrelated results.
  let best;
  if (p.pick) {
    const exact = usable.find((r) => norm(r.collectionName) === norm(p.pick));
    if (exact) best = { r: exact, s: 1 };
  }
  if (!best) {
    best = usable.map((r) => ({ r, s: similar(p.pick || p.name, r.collectionName || "") }))
      .sort((a, b) => b.s - a.s)[0];
  }
  return {
    feedUrl: best.r.feedUrl,
    itunesId: best.r.collectionId ?? null,
    itunesName: best.r.collectionName,
    itunesCount: best.r.trackCount ?? null,
    genre: best.r.primaryGenreName ?? "",
    confidence: Math.round(best.s * 100),
  };
}

// Apple Podcasts carries by far the largest public pool of podcast ratings;
// Spotify does not publish counts at all. Read from the page's JSON-LD block,
// which is scoped to the page's own show. Scraping the first "ratingCount" in
// the page markup is wrong: a show page embeds ~15 of them for the
// "You Might Also Like" rail, and the first belongs to a different podcast.
async function fetchApple(itunesId) {
  if (!itunesId) return {};
  const html = await getText(`https://podcasts.apple.com/us/podcast/id${itunesId}`, 30000);
  const m = /"aggregateRating"\s*:\s*\{[\s\S]{0,4000}?"itemReviewed"\s*:\s*\{[\s\S]{0,2000}?\}/.exec(html)
    || /"aggregateRating"\s*:\s*\{[\s\S]{0,6000}?\}\s*\}/.exec(html);
  if (!m) return {};
  const blob = m[0];
  const avg = /"ratingValue"\s*:\s*([\d.]+)/.exec(blob)?.[1];
  const cnt = /"reviewCount"\s*:\s*(\d+)/.exec(blob)?.[1];
  const name = /"name"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(blob)?.[1];
  const desc = /"description"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(blob)?.[1];
  const out = {};
  if (avg && cnt) {
    out.ratingAvg = Number(avg);
    out.ratingCount = Number(cnt);
    out.ratingSource = "Apple Podcasts";
    out.ratingOf = name ? name.replace(/\\"/g, '"') : "";
  }
  if (desc) out.appleDescription = desc.replace(/\\"/g, '"').replace(/\\n/g, " ").replace(/\s+/g, " ").trim();
  return out;
}

// One-sentence summary taken from the feed's own channel description, so it
// describes the show as its publisher does rather than as I imagine it.
function channelDescription(xml) {
  const head = xml.split(/<item[\s>]/)[0];
  const raw =
    /<itunes:summary>([\s\S]*?)<\/itunes:summary>/i.exec(head)?.[1] ??
    /<description>([\s\S]*?)<\/description>/i.exec(head)?.[1] ?? "";
  const text = raw
    .replace(/<!\[CDATA\[|\]\]>/g, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&[a-z]+;|&#\d+;/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!text) return "";
  const first = text.split(/(?<=[.!?])\s+/)[0] || text;
  return (first.length > 240 ? first.slice(0, 237) + "..." : first);
}

function parseDuration(raw, fallbackBytes) {
  if (raw) {
    const s = String(raw).trim();
    if (/^\d+$/.test(s)) return Number(s);
    const parts = s.split(":").map(Number);
    if (parts.every((n) => Number.isFinite(n))) {
      if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
      if (parts.length === 2) return parts[0] * 60 + parts[1];
    }
  }
  // ~1 MB/min at 128 kbps: only a fallback, and only when nothing better exists.
  if (fallbackBytes > 0) return Math.round(fallbackBytes / (128000 / 8));
  return null;
}

function parseFeed(xml) {
  const items = [...xml.matchAll(/<item[\s>][\s\S]*?<\/item>/g)].map((m) => m[0]);
  const out = [];
  for (const it of items) {
    // A trailer is an advert for the show, not an episode of it, and the app
    // now skips them at playback. Counting them here would overstate how much a
    // feed holds - Aleph Beta's "A Book Like No Other" reported five episodes
    // when it has four and a promo.
    const type = /<itunes:episodeType>([\s\S]*?)<\/itunes:episodeType>/i.exec(it)?.[1]?.trim();
    if (type && /^trailer$/i.test(type)) continue;
    const pub = /<pubDate>([\s\S]*?)<\/pubDate>/.exec(it)?.[1]?.trim();
    const dur = /<itunes:duration>([\s\S]*?)<\/itunes:duration>/i.exec(it)?.[1]?.trim();
    const len = Number(/<enclosure[^>]*\blength="(\d+)"/i.exec(it)?.[1] || 0);
    const d = pub ? new Date(pub) : null;
    out.push({
      date: d && !isNaN(d) ? d : null,
      seconds: parseDuration(dur, len),
    });
  }
  return out;
}

const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
function stdev(a) {
  if (a.length < 2) return 0;
  const m = mean(a);
  return Math.sqrt(a.reduce((s, v) => s + (v - m) ** 2, 0) / (a.length - 1));
}
function pct(a, p) {
  const s = [...a].sort((x, y) => x - y);
  return s[Math.min(s.length - 1, Math.max(0, Math.floor((p / 100) * s.length)))];
}

function analyse(eps) {
  const dated = eps.filter((e) => e.date).sort((a, b) => b.date - a.date);
  const durs = eps.map((e) => e.seconds).filter((s) => s && s > 0).map((s) => s / 60);
  const now = new Date();
  const last = dated[0]?.date ?? null;
  const first = dated[dated.length - 1]?.date ?? null;
  const daysSince = last ? Math.round((now - last) / 86400000) : null;

  // Recent cadence is what matters for scheduling; lifetime rate is skewed by
  // long-dead back catalogues and by shows that changed frequency.
  const win = 90;
  const recent = dated.filter((e) => (now - e.date) / 86400000 <= win);
  const perWeekRecent = recent.length ? +(recent.length / (win / 7)).toFixed(2) : 0;
  const spanDays = first && last ? Math.max(1, (last - first) / 86400000) : null;
  const perWeekLife = spanDays ? +((dated.length / spanDays) * 7).toFixed(2) : null;

  // Day-of-week pattern from the last year only, so an old schedule doesn't
  // masquerade as the current one.
  const yr = dated.filter((e) => (now - e.date) / 86400000 <= 365);
  const hist = new Array(7).fill(0);
  for (const e of yr) hist[e.date.getDay()]++;
  const top = hist.map((c, i) => ({ d: DAY[i], c }))
    .filter((x) => x.c >= Math.max(2, 0.15 * Math.max(...hist)))
    .sort((a, b) => b.c - a.c);
  const spread = yr.length ? +(Math.max(...hist) / yr.length).toFixed(2) : 0;

  return {
    episodesInFeed: eps.length,
    firstSeen: first ? first.toISOString().slice(0, 10) : "",
    lastEpisode: last ? last.toISOString().slice(0, 10) : "",
    daysSinceLast: daysSince,
    active: daysSince != null && daysSince <= 45,
    perWeekRecent,
    perWeekLifetime: perWeekLife,
    episodesLast90: recent.length,
    episodesLast365: yr.length,
    dayPattern: top.length ? top.map((x) => x.d).join("+") : "",
    // 1.0 means every episode lands on one weekday; low values mean irregular.
    dayConcentration: spread,
    durMin: durs.length ? Math.round(Math.min(...durs)) : null,
    durMax: durs.length ? Math.round(Math.max(...durs)) : null,
    durMean: durs.length ? Math.round(mean(durs)) : null,
    durMedian: durs.length ? Math.round(pct(durs, 50)) : null,
    durSd: durs.length ? Math.round(stdev(durs)) : null,
    durP10: durs.length ? Math.round(pct(durs, 10)) : null,
    durP90: durs.length ? Math.round(pct(durs, 90)) : null,
    durSamples: durs.length,
  };
}

const results = [];
let n = 0;
for (const p of PODCASTS) {
  n++;
  const row = { ...p, kind: p.kind || "Podcast" };
  // Google Assistant news briefs are a separate catalogue from podcasts: there
  // is no public RSS to read, and no way to migrate them to this app by feed.
  if (row.kind !== "Podcast") {
    row.ok = false;
    row.error = "no public RSS (Assistant news brief)";
    results.push(row);
    console.log(`${String(n).padStart(3)}/${PODCASTS.length}  brief ${p.name}`);
    continue;
  }
  try {
    const f = await resolveFeed(p);
    if (!f) throw new Error("no feed found");
    Object.assign(row, f);
    const xml = await getText(f.feedUrl, 45000);
    const eps = parseFeed(xml);
    if (!eps.length) throw new Error("feed had no items");
    Object.assign(row, analyse(eps));
    row.description = channelDescription(xml);
    // Ratings and the editorial blurb are a bonus: a failure here must not lose
    // the feed statistics, but it must be visible rather than silently empty.
    try {
      Object.assign(row, await fetchApple(f.itunesId));
    } catch (e) {
      row.ratingError = String(e.message || e).slice(0, 60);
    }
    if (row.appleDescription) {
      const first = row.appleDescription.split(/(?<=[.!?])\s+/)[0] || row.appleDescription;
      row.description = first.length > 240 ? first.slice(0, 237) + "..." : first;
    }
    row.ok = true;
  } catch (e) {
    row.ok = false;
    row.error = String(e.message || e).slice(0, 120);
  }
  results.push(row);
  console.log(
    `${String(n).padStart(3)}/${PODCASTS.length}  ${row.ok ? "ok  " : "FAIL"}  ` +
    `${p.name.slice(0, 42).padEnd(42)} ${row.ok ? `conf=${row.confidence}% eps=${row.episodesInFeed} /wk=${row.perWeekRecent} ${row.dayPattern}` : row.error}`
  );
  await sleep(350); // stay well under the iTunes rate limit
}

writeFileSync("podcast-stats.json", JSON.stringify(results, null, 2));
console.log(`\nwrote podcast-stats.json  (${results.filter(r => r.ok).length}/${results.length} resolved)`);
