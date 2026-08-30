// Download every available revision of the sheet as .xlsx.
//
// Drive exposes a subset of a Google Sheet's revisions - milestones, not the
// per-edit list the Version history sidebar shows - so this reconstructs an
// approximate history, not a complete one.
import { GoogleAuth } from "google-auth-library";
import { writeFileSync, mkdirSync, existsSync } from "node:fs";
import { SHEET_ID as ID } from "./sheets.mjs";
const OUT = "C:/Users/yischoen/.copilot/session-state/7b7cab1a-e3ed-4d93-8833-3f514326952c/files/revisions";
const XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

mkdirSync(OUT, { recursive: true });
const auth = new GoogleAuth({ keyFile: "./service-account.json",
  scopes: ["https://www.googleapis.com/auth/drive.readonly"] });
const client = await auth.getClient();

let all = [], page;
do {
  const u = new URL(`https://www.googleapis.com/drive/v3/files/${ID}/revisions`);
  u.searchParams.set("fields", "nextPageToken,revisions(id,modifiedTime,lastModifyingUser/displayName,exportLinks)");
  u.searchParams.set("pageSize", "1000");
  if (page) u.searchParams.set("pageToken", page);
  const r = await client.request({ url: u.toString() });
  all = all.concat(r.data.revisions || []);
  page = r.data.nextPageToken;
} while (page);
all.sort((a, b) => a.modifiedTime.localeCompare(b.modifiedTime));

// Drive rate-limits revision exports fairly aggressively, so this backs off
// rather than giving up: the download only has to happen once.
async function download(client, url, attempt = 0) {
  try {
    return await client.request({ url, responseType: "arraybuffer" });
  } catch (e) {
    const code = e.response?.status ?? e.code;
    if ((code === 429 || code === 500 || code === 503) && attempt < 6) {
      const wait = 2000 * Math.pow(2, attempt);
      console.log(`    rate-limited, waiting ${wait / 1000}s`);
      await new Promise((r) => setTimeout(r, wait));
      return download(client, url, attempt + 1);
    }
    throw e;
  }
}

const index = [];
for (const rev of all) {
  const date = rev.modifiedTime.slice(0, 10);
  const file = `${OUT}/${date}_${rev.id}.xlsx`;
  index.push({ id: rev.id, date, modifiedTime: rev.modifiedTime,
    who: rev.lastModifyingUser?.displayName || "", file });
  if (existsSync(file)) continue;
  const link = rev.exportLinks?.[XLSX];
  if (!link) { console.log(`  ${date} rev ${rev.id}: no xlsx export`); continue; }
  const res = await download(client, link);
  writeFileSync(file, Buffer.from(res.data));
  console.log(`  ${date} rev ${String(rev.id).padStart(5)}  ${(res.data.byteLength / 1024).toFixed(0)} KB`);
  await new Promise((r) => setTimeout(r, 1500));
}
writeFileSync(`${OUT}/index.json`, JSON.stringify(index, null, 1));
console.log(`\n${index.length} revisions, ${all[0].modifiedTime.slice(0,10)} .. ${all[all.length-1].modifiedTime.slice(0,10)}`);
