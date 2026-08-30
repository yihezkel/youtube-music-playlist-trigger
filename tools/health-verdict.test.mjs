// Exercises the verdict against states the phone has never been in, because a
// monitor only ever run against a healthy phone has not been shown to work.
import { classify, toMarkdown, QUIET_MIN } from "./health-verdict.mjs";

const NOW = Date.parse("2026-08-30T12:00:00+03:00");
const min = (n) => NOW - n * 60000;
const ok = (title) => ({ title, health: "Ok", detail: "fine" });

let pass = 0, fail = 0;
const check = (name, cond) => {
  if (cond) { pass++; console.log(`  ok    ${name}`); }
  else { fail++; console.log(`  FAIL  ${name}`); }
};

const healthy = {
  updatedAtMs: min(5),
  appVersionName: "0.6.0",
  appliedConfigRevision: 113,
  healthChecks: [ok("Alarms armed"), ok("Network")],
  lastSelfTestSuccessMs: min(30),
  lastSelfTestFailureMs: min(3000),
};

console.log("a healthy phone:");
let v = classify(healthy, NOW);
check("nothing fatal", v.fatal.length === 0);
check("nothing degraded", v.degraded.length === 0);
check("markdown says so", toMarkdown(v, NOW).startsWith("Nothing fatal."));

console.log(`\nthe phone off for ${QUIET_MIN + 60} minutes:`);
v = classify({ ...healthy, updatedAtMs: min(QUIET_MIN + 60) }, NOW);
check("one fatal", v.fatal.length === 1);
check("names the silence", /gone quiet/i.test(v.fatal[0]?.what ?? ""));
check("says nothing can report it", /nothing on the phone can tell you/i.test(v.fatal[0]?.detail ?? ""));

console.log(`\njust inside the threshold (${QUIET_MIN - 1} min):`);
v = classify({ ...healthy, updatedAtMs: min(QUIET_MIN - 1) }, NOW);
check("not fatal - a sleeping phone must not trip it", v.fatal.length === 0);

console.log("\na phone that has never checked in:");
v = classify({ ...healthy, updatedAtMs: 0 }, NOW);
check("fatal", v.fatal.length === 1);
check("says never", /never/i.test(v.fatal[0]?.detail ?? ""));

console.log("\na red health check:");
v = classify({
  ...healthy,
  healthChecks: [ok("Network"), {
    title: "Alarms armed", health: "Broken", detail: "0 of 19",
    why: "Nothing will play.", where: "Settings",
  }],
}, NOW);
check("fatal", v.fatal.length === 1);
check("uses the check's title", v.fatal[0]?.what === "Alarms armed");
check("carries the why and where", /Nothing will play\..*Where to fix: Settings/.test(v.fatal[0]?.detail ?? ""));

console.log("\nan orange check on its own:");
v = classify({
  ...healthy,
  healthChecks: [{ title: "Recent failures", health: "Degraded", detail: "2 today" }],
}, NOW);
check("not fatal - the app handles these", v.fatal.length === 0);
check("still reported", v.degraded.length === 1 && /Recent failures/.test(v.degraded[0]));

console.log("\nthe self-test failing since the last success:");
v = classify({ ...healthy, lastSelfTestSuccessMs: min(500), lastSelfTestFailureMs: min(20), lastSelfTestFailureReason: "All 3 strategies failed." }, NOW);
check("fatal", v.fatal.length === 1);
check("names the self-test", /self-test/i.test(v.fatal[0]?.what ?? ""));
check("carries the reason", /All 3 strategies failed/.test(v.fatal[0]?.detail ?? ""));

console.log("\nan old failure with a newer success:");
v = classify({ ...healthy, lastSelfTestSuccessMs: min(20), lastSelfTestFailureMs: min(500) }, NOW);
check("not fatal - it recovered", v.fatal.length === 0);

console.log("\neverything wrong at once:");
v = classify({
  updatedAtMs: min(600),
  healthChecks: [
    { title: "Accessibility service", health: "Broken", detail: "Bound but delivering nothing" },
    { title: "Recent failures", health: "Degraded", detail: "3 today" },
  ],
  lastSelfTestSuccessMs: min(9000),
  lastSelfTestFailureMs: min(100),
}, NOW);
check("three fatal", v.fatal.length === 3);
check("one degraded", v.degraded.length === 1);
const md = toMarkdown(v, NOW);
check("email names all three", ["gone quiet", "Accessibility service", "self-test"].every((s) => md.includes(s)));
check("email separates the handled one", md.includes("though the app is handling these"));

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
