// Build podcast feeds for Aleph Beta from their own published metadata.
//
//   node alephbeta-feed.mjs          - crawl (cached) and write the feeds
//   node alephbeta-feed.mjs --fresh  - ignore the cache and refetch everything
//
// Why this exists
// ---------------
// Aleph Beta's public RSS feeds carry only the series in progress: 52 episodes
// across three shows, where their own site lists roughly twice that. The audio
// itself was never paywalled - every episode page publishes a schema.org
// PodcastEpisode block whose associatedMedia.contentUrl is an ordinary
// unauthenticated Buzzsprout MP3. Only the feed was trimmed.
//
// So this reassembles a feed from the metadata they publish for machines to
// read, for one household that pays for a subscription. It is deliberately
// polite: it fetches and enforces robots.txt before anything else, reads only
// pages listed in their own sitemap, fetches a few at a time with a pause
// between batches (or their crawl-delay, whichever is longer), and caches every
// page so a rebuild costs almost nothing.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { createHash, randomBytes } from "node:crypto";
import { join } from "node:path";

const SITE = "https://www.alephbeta.org";
const CACHE = ".ab-cache";
const OUT_ROOT = "../web/private-feeds";
const UA = "Mozilla/5.0 (compatible; personal-podcast-organiser)";
const BATCH = 4;            // pages in flight
const PAUSE_MS = 400;       // between batches
const CACHE_TTL_MS = 7 * 24 * 3600 * 1000;

const fresh = process.argv.includes("--fresh");
mkdirSync(CACHE, { recursive: true });

// A stable, unguessable directory so the feeds are reachable by the phone but
// not discoverable. Kept out of git along with the feeds themselves.
const tokenFile = ".ab-feed-token";
const token = existsSync(tokenFile)
  ? readFileSync(tokenFile, "utf8").trim()
  : (() => { const t = randomBytes(12).toString("hex"); writeFileSync(tokenFile, t); return t; })();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- robots.txt ----------------------------------------------------------
// Enforced rather than assumed. Until now this file only claimed to respect
// robots.txt in a comment: the crawl happened to stay inside /content/ because
// that is what it was told to fetch, so the guarantee held by luck rather than
// by construction. If their sitemap ever listed a disallowed path, nothing
// would have stopped it.
const robots = { rules: [], crawlDelayMs: 0 };

/** Turn a robots.txt path pattern into a matcher. Supports * and a trailing $. */
function ruleToRegex(path) {
  const escaped = path.replace(/[.+?^${}()|[\]\\]/g, "\\$&");
  const body = escaped.replace(/\*/g, ".*");
  return new RegExp("^" + (body.endsWith("$") ? body.slice(0, -1) + "$" : body));
}

async function loadRobots() {
  const res = await fetch(`${SITE}/robots.txt`, { headers: { "user-agent": UA } });
  if (!res.ok) throw new Error(`robots.txt returned ${res.status} - refusing to crawl blind`);
  const text = await res.text();
  let applies = false;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/#.*/, "").trim();
    if (!line) continue;
    const [k, ...rest] = line.split(":");
    const key = k.trim().toLowerCase();
    const value = rest.join(":").trim();
    if (key === "user-agent") {
      // Only the wildcard group: we are not pretending to be a named crawler.
      applies = value === "*";
    } else if (applies && (key === "disallow" || key === "allow")) {
      if (value) robots.rules.push({ allow: key === "allow", path: value, re: ruleToRegex(value) });
    } else if (applies && key === "crawl-delay") {
      const s = Number(value);
      if (Number.isFinite(s) && s > 0) robots.crawlDelayMs = s * 1000;
    }
  }
  console.log(`robots.txt: ${robots.rules.length} rules for *` +
    (robots.crawlDelayMs ? `, crawl-delay ${robots.crawlDelayMs / 1000}s` : ""));
  for (const r of robots.rules) console.log(`  ${r.allow ? "Allow  " : "Disallow"} ${r.path}`);
}

/** Standard longest-match-wins; Allow beats Disallow at equal length. */
function allowed(url) {
  const path = new URL(url).pathname + new URL(url).search;
  let best = null;
  for (const r of robots.rules) {
    if (!r.re.test(path)) continue;
    if (!best || r.path.length > best.path.length || (r.path.length === best.path.length && r.allow)) best = r;
  }
  return !best || best.allow;
}

async function get(url) {
  if (!allowed(url)) {
    // Loud, not silent: if their rules change, that is something to look at
    // rather than something to quietly skip past.
    throw new Error(`robots.txt disallows ${url}`);
  }
  const key = join(CACHE, createHash("sha1").update(url).digest("hex") + ".html");
  if (!fresh && existsSync(key)) {
    const age = Date.now() - Number(readFileSync(key + ".t", "utf8") || 0);
    if (age < CACHE_TTL_MS) return readFileSync(key, "utf8");
  }
  const res = await fetch(url, { headers: { "user-agent": UA } });
  const body = await res.text();
  writeFileSync(key, body);
  writeFileSync(key + ".t", String(Date.now()));
  return body;
}

const flat = (o) => (Array.isArray(o) ? o.flatMap(flat) : [o]);

function episodeFrom(html, pageUrl) {
  for (const b of html.matchAll(/<script type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g)) {
    let parsed;
    try { parsed = JSON.parse(b[1]); } catch { continue; }
    for (const o of flat(parsed)) {
      if (!o || o["@type"] !== "PodcastEpisode") continue;
      const audio = o.associatedMedia?.contentUrl;
      if (!audio) continue;
      return {
        title: String(o.name || "").trim(),
        description: String(o.description || "").trim(),
        published: o.datePublished ? new Date(o.datePublished) : null,
        durationSec: isoDuration(o.duration),
        episodeNumber: o.episodeNumber ?? null,
        seasonNumber: o.seasonNumber ?? null,
        series: String(o.partOfSeries?.name || "Aleph Beta").trim(),
        audio,
        page: pageUrl,
      };
    }
  }
  return null;
}

/** PT38M9S -> 2289. Their duration strings are hours/minutes/seconds only. */
function isoDuration(s) {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(String(s || ""));
  if (!m) return null;
  return (+(m[1] || 0)) * 3600 + (+(m[2] || 0)) * 60 + (+(m[3] || 0));
}

const esc = (s) => String(s)
  .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
  .replace(/"/g, "&quot;").replace(/[\x00-\x08\x0b\x0c\x0e-\x1f]/g, "");

const hhmmss = (sec) => sec == null ? "" :
  [Math.floor(sec / 3600), Math.floor(sec / 60) % 60, sec % 60]
    .map((n, i) => (i ? String(n).padStart(2, "0") : String(n))).join(":");

function rss(title, episodes) {
  const items = episodes.map((e) => `    <item>
      <title>${esc(e.title)}</title>
      <link>${esc(e.page)}</link>
      <guid isPermaLink="false">${esc(e.audio.split("?")[0])}</guid>
      <pubDate>${(e.published || new Date(0)).toUTCString()}</pubDate>
      <description>${esc(e.description)}</description>
      <enclosure url="${esc(e.audio)}" type="audio/mpeg" length="0"/>
      ${e.durationSec != null ? `<itunes:duration>${hhmmss(e.durationSec)}</itunes:duration>` : ""}
      ${e.seasonNumber != null ? `<itunes:season>${e.seasonNumber}</itunes:season>` : ""}
      ${e.episodeNumber != null ? `<itunes:episode>${e.episodeNumber}</itunes:episode>` : ""}
      <itunes:episodeType>full</itunes:episodeType>
    </item>`).join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>${esc(title)}</title>
    <link>${SITE}</link>
    <language>en</language>
    <description>${esc(title)} - assembled from alephbeta.org's published episode metadata for personal use. Not affiliated with Aleph Beta.</description>
    <itunes:author>Aleph Beta</itunes:author>
${items}
  </channel>
</rss>
`;
}

// --- crawl ---------------------------------------------------------------
await loadRobots();
const index = await get(`${SITE}/sitemap.xml`);
const maps = [...index.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1])
  .filter((u) => /content-\d+\.xml$/.test(u));
let pages = [];
for (const m of maps) {
  const xml = await get(m);
  pages = pages.concat([...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((x) => x[1]));
}
pages = [...new Set(pages)].filter((u) => u.includes("/content/"));
console.log(`${pages.length} content pages listed in the sitemap`);

const episodes = [];
let done = 0, errors = 0;
for (let i = 0; i < pages.length; i += BATCH) {
  await Promise.all(pages.slice(i, i + BATCH).map(async (u) => {
    try {
      const e = episodeFrom(await get(u), u);
      if (e) episodes.push(e);
    } catch { errors++; }
    done++;
  }));
  if (done % 200 < BATCH) process.stdout.write(`  ${done}/${pages.length}\r`);
  await sleep(Math.max(PAUSE_MS, robots.crawlDelayMs));
}
console.log(`\nscanned ${done} pages (${errors} failed), found ${episodes.length} podcast episodes`);

// --- group and write -----------------------------------------------------
const bySeries = new Map();
for (const e of episodes) {
  if (!bySeries.has(e.series)) bySeries.set(e.series, []);
  bySeries.get(e.series).push(e);
}
const outDir = join(OUT_ROOT, token);
mkdirSync(outDir, { recursive: true });

const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);
const written = [];
for (const [series, list] of [...bySeries].sort((a, b) => b[1].length - a[1].length)) {
  list.sort((a, b) => (b.published?.getTime() || 0) - (a.published?.getTime() || 0));
  const file = `${slug(series)}.xml`;
  writeFileSync(join(outDir, file), rss(series, list));
  written.push({ series, file, n: list.length });
}
// One combined feed as well: most blocks want "an Aleph Beta shiur", not a
// particular season, and the seasons are small.
const all = [...episodes].sort((a, b) => (b.published?.getTime() || 0) - (a.published?.getTime() || 0));
writeFileSync(join(outDir, "all.xml"), rss("Aleph Beta - all podcast episodes", all));

console.log(`\nwrote ${written.length} series feeds + all.xml to web/private-feeds/${token}/`);
written.slice(0, 12).forEach((w) => console.log(`  ${String(w.n).padStart(4)}  ${w.file}`));
console.log(`  ${String(all.length).padStart(4)}  all.xml`);
const totalMin = Math.round(all.reduce((n, e) => n + (e.durationSec || 0), 0) / 60);
console.log(`\ntotal listening: ${Math.floor(totalMin / 60)}h ${totalMin % 60}m`);
