// Generates tools/playlist-list.mjs from the live device config, so the YTM
// playlists in the catalog are the ones the app actually holds rather than a
// hand-copied list that will drift.
import admin from 'firebase-admin';
import { readFileSync, writeFileSync } from 'node:fs';

admin.initializeApp({ credential: admin.credential.cert(JSON.parse(readFileSync('./service-account.json', 'utf8'))) });
const snap = await admin.firestore().doc('users/<USER_ID>/devices/<DEVICE_ID>/data/config').get();
const cfg = JSON.parse(snap.get('json'));

const byId = new Map();
const note = (raw, where) => {
  const m = /^(https?:\/\/music\.youtube\.com\/playlist\?list=([\w-]+))(?:\s*\[([^\]]+)\])?/.exec(raw);
  if (!m) return;
  const [, url, id, label] = m;
  if (!byId.has(id)) byId.set(id, { id, url, name: label || id, usedBy: new Set() });
  if (where) byId.get(id).usedBy.add(where);
};

for (const raw of cfg.defaultPlaylistUrls || []) note(raw, 'Default rotation');
// A disabled schedule is never armed, so saying a playlist is "used by" it
// without qualification reads as though it still plays. Three pre-rebuild
// schedules are still in the config, switched off: Morning, Afternoon and Temp.
for (const s of cfg.schedules) {
  for (const raw of s.playlistUrls || []) note(raw, s.enabled ? s.name : `${s.name} (disabled)`);
}

const list = [...byId.values()].sort((a, b) => a.name.localeCompare(b.name));
const lines = list.map((p) => {
  const used = [...p.usedBy].sort().join(', ');
  return `  { name: ${JSON.stringify(p.name)}, id: ${JSON.stringify(p.id)},\n` +
    `    url: ${JSON.stringify(p.url)},\n` +
    `    usedBy: ${JSON.stringify(used)} },`;
});

const out = `// The YouTube Music playlists the app plays, generated from the live device
// config by regen-playlists.mjs so it cannot drift from what the phone holds.
//
// These are not podcasts and have no feed, no episode cadence and no published
// durations, so most of the catalog's columns are blank for them. They are in
// the catalog because it is meant to list everything that can be played, not
// only the things with an RSS feed.
export const PLAYLISTS = [
${lines.join('\n')}
];
`;

writeFileSync('playlist-list.mjs', out);
console.log(`wrote playlist-list.mjs with ${list.length} playlists`);
for (const p of list) console.log(`   ${p.name}  (${[...p.usedBy].length} place(s))`);
process.exit(0);
