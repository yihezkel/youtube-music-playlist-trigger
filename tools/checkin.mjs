// Is the phone still talking to us?
//
//   node checkin.mjs            report how long ago the phone last checked in
//   node checkin.mjs --json     the same, as JSON
//   node checkin.mjs --notify   raise a GitHub issue when it has gone quiet,
//                               and close that issue once it comes back
//
// Exit code is 0 when the phone is current and 10 when it is not, so a
// scheduled task can branch without parsing the output.
//
// This exists because of 30 Aug. The phone was off from about 08:00 to 12:25;
// block B fired at 08:00, played one episode and then nothing happened for four
// and a half hours. Every other safeguard in this project assumes the app is
// running - the health checks, the failure log, the self-test alert all live on
// the phone, so a phone that is off reports nothing at all. Nobody noticed until
// it was plugged back in.
//
// It reads and reports; it never changes anything on the phone or the sheet.
import { execSync } from "node:child_process";
import { writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DEVICE_DOC, firestore } from "./device.mjs";

/**
 * How quiet is too quiet.
 *
 * The app polls every fifteen minutes, so a single missed poll means nothing -
 * a phone can be asleep, on a slow network, or mid-reboot. Ninety minutes is
 * six intervals: long enough that a healthy phone will not trip it, short
 * enough that a morning like the 30th is caught within the hour rather than at
 * the next fortnightly sweep.
 */
const STALE_MIN = 90;

const TITLE = "The YTM Trigger phone has gone quiet";

const snap = await firestore().doc(DEVICE_DOC).get();
if (!snap.exists) {
  console.error(`No device document at ${DEVICE_DOC}. Has the app ever synced?`);
  process.exit(1);
}

const data = snap.data() || {};
let state = {};
try { state = JSON.parse(data.json || "{}"); } catch { /* fall back to the top-level fields */ }

const lastMs = Number(state.updatedAtMs || data.updatedAtMs || 0);
const ageMin = lastMs ? Math.round((Date.now() - lastMs) / 60000) : null;
const stale = ageMin == null || ageMin > STALE_MIN;

const when = lastMs
  ? new Date(lastMs).toLocaleString("en-US", { timeZone: "Asia/Jerusalem" })
  : "never";
const forHowLong = ageMin == null ? "unknown"
  : ageMin < 60 ? `${ageMin} min`
    : `${Math.floor(ageMin / 60)}h ${ageMin % 60}m`;

// The health checks the phone reported when it last spoke. Worth carrying into
// the issue: a phone that went quiet having already flagged something is a
// different story from one that was fine and simply stopped.
const checks = Array.isArray(state.healthChecks) ? state.healthChecks : [];
const notOk = checks.filter((c) => c && c.status && c.status !== "Ok");

const report = {
  lastCheckIn: when,
  ageMinutes: ageMin,
  stale,
  staleAfterMinutes: STALE_MIN,
  appVersion: state.appVersionName ?? data.appVersionName ?? null,
  appliedConfigRevision: state.appliedConfigRevision ?? null,
  accessibilityHealthy: state.accessibilityHealthy ?? null,
  playbackState: state.playbackState ?? null,
  checksNeedingAttention: notOk.map((c) => `${c.name}: ${c.detail ?? c.status}`),
};

if (process.argv.includes("--json")) {
  console.log(JSON.stringify(report, null, 1));
} else if (!stale) {
  console.log(`Phone checked in ${forHowLong} ago (${when}). Fine.`);
  if (notOk.length) console.log(`  but ${notOk.length} health check(s) needed attention: ${report.checksNeedingAttention.join("; ")}`);
} else {
  console.log(`Phone has not checked in for ${forHowLong} (last: ${when}).`);
  console.log(`Anything scheduled since then has not played, and nothing on the phone can tell you so.`);
}

if (process.argv.includes("--notify")) {
  const openIssues = () => {
    // The issues listing, not the search API: search is a separate index that
    // lags creation, so a run soon after another would file a duplicate.
    const raw = execSync("gh issue list --state open --limit 100 --json number,title", { encoding: "utf8" });
    return JSON.parse(raw).filter((i) => i.title === TITLE);
  };
  try {
    const open = openIssues();
    if (stale && !open.length) {
      const body = [
        `The phone last checked in **${forHowLong} ago** (${when}).`,
        "",
        "It polls every 15 minutes, so this means it is off, offline, or the app is not running.",
        "While it is quiet nothing scheduled will play, and nothing on the phone can report that:",
        "the health checks, the failure log and the self-test alert all run on the phone itself.",
        "",
        `- App version: ${report.appVersion ?? "unknown"}`,
        `- Config revision applied: ${report.appliedConfigRevision ?? "unknown"}`,
        `- Accessibility healthy at last check-in: ${report.accessibilityHealthy ?? "unknown"}`,
        ...(notOk.length
          ? ["", "It had already flagged:", ...notOk.map((c) => `- ${c.name}: ${c.detail ?? c.status}`)]
          : ["", "Everything it could check was fine when it last spoke."]),
        "",
        "This issue closes itself once the phone checks in again.",
      ].join("\n");
      const tmp = join(tmpdir(), `ytm-checkin-${Date.now()}.md`);
      writeFileSync(tmp, body, "utf8");
      try {
        execSync(`gh issue create --title ${JSON.stringify(TITLE)} --body-file ${JSON.stringify(tmp)}`, { stdio: "inherit" });
      } finally {
        rmSync(tmp, { force: true });
      }
    } else if (stale) {
      console.log(`Issue #${open[0].number} is already open for this; not raising another.`);
    } else if (open.length) {
      // Self-resolving, so a recovered phone does not leave a stale alarm.
      for (const i of open) {
        execSync(
          `gh issue close ${i.number} --comment ${JSON.stringify(`The phone checked in again at ${when}. Closing automatically.`)}`,
          { stdio: "inherit" },
        );
        console.log(`Closed #${i.number}: the phone is back.`);
      }
    }
  } catch (e) {
    console.error(`Could not reach GitHub: ${e.message.split("\n")[0]}`);
  }
}

process.exit(stale ? 10 : 0);
