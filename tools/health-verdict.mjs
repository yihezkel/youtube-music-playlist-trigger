// Decides whether the phone's reported state is fatal, degraded or fine.
//
// Kept free of I/O so it can be tested against states the phone has never
// actually been in. A monitor whose only exercise is a healthy phone is a
// monitor that has never been shown to fire.
//
// "Fatal" is deliberately narrow, and borrows the app's own severity language:
// green means everything it can do it can do, orange means something is wrong
// and the app already handles it, red means nothing covers it and a block will
// be missed or silent. Only red counts here, plus the two things the phone
// cannot report about itself - being off, and playback having stopped working.

/**
 * How long a silence means the phone is off.
 *
 * The app checks in every fifteen minutes. Two hours is eight intervals, which
 * a sleeping or briefly offline phone will not reach, and short enough that a
 * morning like 30 Aug - off from 08:00, first block at 07:30 - is caught by a
 * run later the same day.
 */
export const QUIET_MIN = 120;

const fmt = (ms) => new Date(ms).toLocaleString("en-GB", {
  timeZone: "Asia/Jerusalem", dateStyle: "medium", timeStyle: "short",
});

/**
 * @param state the phone's reported state, parsed from the device document
 * @param nowMs the moment to judge against, so tests are not clock-dependent
 */
export function classify(state = {}, nowMs = Date.now()) {
  const fatal = [];
  const degraded = [];

  const lastMs = Number(state.updatedAtMs || 0);
  const ageMin = lastMs ? Math.round((nowMs - lastMs) / 60000) : null;

  // 1. The phone is not talking. Everything else in this project reports from
  // the phone, so this is the one failure that hides all the others.
  if (ageMin == null || ageMin > QUIET_MIN) {
    const howLong = ageMin == null ? "never"
      : ageMin < 60 ? `${ageMin} minutes` : `${Math.floor(ageMin / 60)}h ${ageMin % 60}m`;
    fatal.push({
      what: "The phone has gone quiet",
      detail: `Last check-in ${lastMs ? `${howLong} ago (${fmt(lastMs)})` : "never"}. `
        + "It checks in every 15 minutes, so it is off, offline, or the app is not running. "
        + "Nothing scheduled will play, and nothing on the phone can tell you so.",
    });
  }

  // 2. A red health check: the app's own definition of "nothing covers this".
  const checks = Array.isArray(state.healthChecks) ? state.healthChecks : [];
  for (const c of checks) {
    if (!c || !c.health) continue;
    if (c.health === "Broken") {
      fatal.push({
        what: c.title,
        detail: [c.detail, c.why, c.where && `Where to fix: ${c.where}`].filter(Boolean).join(" — "),
      });
    } else if (c.health === "Degraded") {
      degraded.push(`${c.title}: ${c.detail ?? ""}`.trim());
    }
  }

  // 3. Playback itself is failing. The self-test is the only thing that proves
  // the whole chain still works end to end, so a failure newer than the last
  // success means it stopped working and has not recovered since.
  const okMs = Number(state.lastSelfTestSuccessMs || 0);
  const badMs = Number(state.lastSelfTestFailureMs || 0);
  if (badMs && badMs > okMs) {
    fatal.push({
      what: "The self-test is failing",
      detail: `Last failure ${fmt(badMs)}`
        + (okMs ? `, last success ${fmt(okMs)}. ` : ", and it has never succeeded. ")
        + (state.lastSelfTestFailureReason ?? ""),
    });
  }

  return {
    fatal,
    degraded,
    quietForMinutes: ageMin,
    lastCheckIn: lastMs ? fmt(lastMs) : null,
    appVersion: state.appVersionName ?? null,
    configRevision: state.appliedConfigRevision ?? null,
  };
}

/** The report as an email body. */
export function toMarkdown(v, nowMs = Date.now()) {
  const lines = [];
  if (v.fatal.length) {
    lines.push(`The YTM Trigger phone needs attention — ${v.fatal.length} thing${v.fatal.length > 1 ? "s" : ""} nothing else will fix.`, "");
    for (const f of v.fatal) lines.push(`**${f.what}**`, "", f.detail, "");
  } else {
    lines.push("Nothing fatal.", "");
  }
  if (v.degraded.length) {
    lines.push("Also worth knowing, though the app is handling these:", "");
    for (const d of v.degraded) lines.push(`- ${d}`);
    lines.push("");
  }
  lines.push("---", "");
  lines.push(`Last check-in: ${v.lastCheckIn ?? "never"}`);
  lines.push(`App ${v.appVersion ?? "?"}, config revision ${v.configRevision ?? "?"}`);
  lines.push(`Checked ${fmt(nowMs)} Israel time.`);
  return lines.join("\n");
}
