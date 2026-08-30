// Reads the phone's reported state and says whether anything is fatal.
//
//   node daily-health.mjs          a verdict, in plain words
//   node daily-health.mjs --json   the same, machine-readable
//   node daily-health.mjs --md     a markdown report, used as the email body
//
// Exit code is 0 when nothing is fatal and 10 when something is, so CI can
// branch without parsing the output.
//
// Runs in GitHub Actions rather than on a PC, so it keeps working when the
// house is empty and the laptop is shut. It only ever reads.
//
// The judgement itself lives in health-verdict.mjs, with no I/O, so it can be
// tested against states the phone has never actually been in.
//
// Credentials come from the YTM_SERVICE_ACCOUNT environment variable when set,
// so the workflow can supply a secret, and fall back to the local
// service-account.json for running it by hand.
import { readFileSync } from "node:fs";
import { classify, toMarkdown } from "./health-verdict.mjs";

const DEVICE_DOC = "users/<USER_ID>/devices/<DEVICE_ID>";

const admin = (await import("firebase-admin")).default;
const cred = process.env.YTM_SERVICE_ACCOUNT
  ? JSON.parse(process.env.YTM_SERVICE_ACCOUNT)
  : JSON.parse(readFileSync("./service-account.json", "utf8"));
admin.initializeApp({ credential: admin.credential.cert(cred) });

const snap = await admin.firestore().doc(DEVICE_DOC).get();
if (!snap.exists) {
  console.log("FATAL: there is no device document at all. The app has never synced.");
  process.exit(10);
}

const data = snap.data() || {};
let state = {};
try { state = JSON.parse(data.json || "{}"); } catch { /* fall back to the top-level fields */ }
// Older records kept these outside the json blob.
state.updatedAtMs ??= data.updatedAtMs;
state.appVersionName ??= data.appVersionName;

const v = classify(state);

if (process.argv.includes("--json")) {
  console.log(JSON.stringify(v, null, 1));
} else if (process.argv.includes("--md")) {
  console.log(toMarkdown(v));
} else if (v.fatal.length) {
  // Deliberately terse: this repository is public, so its Actions logs are too.
  console.log(`FATAL: ${v.fatal.length} problem(s) nothing else will fix.`);
  for (const f of v.fatal) console.log(`  - ${f.what}`);
} else {
  console.log(`Nothing fatal. Phone checked in ${v.quietForMinutes} minute(s) ago.`);
  if (v.degraded.length) console.log(`Degraded but handled: ${v.degraded.length}`);
}

process.exit(v.fatal.length ? 10 : 0);
