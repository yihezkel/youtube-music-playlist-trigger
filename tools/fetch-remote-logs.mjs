#!/usr/bin/env node
/**
 * Pull YTM Trigger diagnostics out of Firestore to a local folder.
 *
 * Used by the weekly monitor so the phone's health can be reviewed without
 * it being physically present or connected over USB.
 *
 * ## Why a service account
 * The Firebase CLI has no document-read command (only delete/indexes/backups),
 * and the CLI's stored OAuth token belongs to a human login: it is revoked by
 * `firebase logout` and is not meant for unattended jobs. A service account is
 * the supported mechanism for automated reads and keeps this independent of
 * whoever is signed in.
 *
 * Credentials are resolved in this order:
 *   1. $YTM_SERVICE_ACCOUNT  — path to the key JSON
 *   2. tools/service-account.json  (gitignored)
 *   3. $GOOGLE_APPLICATION_CREDENTIALS via application-default credentials
 *
 * Usage:
 *   node fetch-remote-logs.mjs [--out <dir>] [--days N]
 */

import { existsSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { initializeApp, cert, applicationDefault } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';

const here = dirname(fileURLToPath(import.meta.url));

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const outDir = resolve(arg('out', join(here, '..', '.remote-logs')));
const maxDays = Number(arg('days', '14'));

function credential() {
  const explicit = process.env.YTM_SERVICE_ACCOUNT;
  const local = join(here, 'service-account.json');
  for (const p of [explicit, local].filter(Boolean)) {
    if (existsSync(p)) {
      return { cred: cert(JSON.parse(readFileSync(p, 'utf8'))), how: `service account ${p}` };
    }
  }
  if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    return { cred: applicationDefault(), how: 'application default credentials' };
  }
  console.error(
    'No credentials found.\n' +
    'Firebase console -> Project settings -> Service accounts -> Generate new private key,\n' +
    'then save it as tools/service-account.json (gitignored) or set YTM_SERVICE_ACCOUNT.',
  );
  process.exit(2);
}

const { cred, how } = credential();
initializeApp({ credential: cred });
const db = getFirestore();

const iso = (ms) => (ms ? new Date(Number(ms)).toISOString() : 'never');
const ageDays = (ms) => (ms ? (Date.now() - Number(ms)) / 86400000 : Infinity);

mkdirSync(outDir, { recursive: true });
console.log(`Using ${how}`);
console.log(`Writing to ${outDir}`);

const summary = { fetchedAtMs: Date.now(), devices: [] };

// The admin SDK bypasses security rules, so every device under every user is
// reachable without knowing the uid up front.
const users = await db.collection('users').listDocuments();
if (users.length === 0) console.warn('No users found - has the phone ever synced?');

for (const user of users) {
  const devices = await user.collection('devices').listDocuments();
  for (const device of devices) {
    const id = device.id;
    const dir = join(outDir, id);
    mkdirSync(dir, { recursive: true });

    const snap = await device.get();
    let state = {};
    try { state = JSON.parse(snap.data()?.json || '{}'); } catch { /* keep {} */ }
    writeFileSync(join(dir, 'state.json'), JSON.stringify(state, null, 2));

    for (const docId of ['config', 'reported']) {
      const d = await device.collection('data').doc(docId).get();
      if (d.exists) {
        writeFileSync(join(dir, `${docId}.json`), JSON.stringify(d.data(), null, 2));
      }
    }

    const logs = await device.collection('logs').get();
    const files = [];
    for (const doc of logs.docs) {
      const data = doc.data();
      if (ageDays(data.uploadedAtMs) > maxDays) continue;
      const ext = doc.id.startsWith('runs-') ? 'jsonl' : 'log';
      const name = `${doc.id}.${ext}`;
      writeFileSync(join(dir, name), data.content || '');
      files.push({ name, sizeBytes: data.sizeBytes ?? null, truncated: !!data.truncated, uploadedAtMs: data.uploadedAtMs ?? null });
    }

    const entry = {
      deviceId: id,
      model: state.deviceModel ?? snap.data()?.deviceModel ?? null,
      appVersion: state.appVersionName ?? snap.data()?.appVersionName ?? null,
      lastCheckIn: iso(state.updatedAtMs),
      lastCheckInAgeDays: Number(ageDays(state.updatedAtMs).toFixed(2)),
      accessibilityHealthy: state.accessibilityHealthy ?? null,
      notificationListenerReady: state.notificationListenerReady ?? null,
      batteryOptimizationIgnored: state.batteryOptimizationIgnored ?? null,
      lastSelfTestSuccess: iso(state.lastSelfTestSuccessMs),
      lastSelfTestSuccessAgeDays: Number(ageDays(state.lastSelfTestSuccessMs).toFixed(2)),
      lastSelfTestFailure: iso(state.lastSelfTestFailureMs),
      lastSelfTestFailureReason: state.lastSelfTestFailureReason ?? null,
      lastSelfTestSkip: iso(state.lastSelfTestSkipMs),
      scheduleCount: state.scheduleCount ?? null,
      files,
    };
    summary.devices.push(entry);

    console.log(`\n${entry.model || id} (${entry.appVersion || '?'})`);
    console.log(`  last check-in : ${entry.lastCheckIn} (${entry.lastCheckInAgeDays}d ago)`);
    console.log(`  last success  : ${entry.lastSelfTestSuccess}`);
    console.log(`  last failure  : ${entry.lastSelfTestFailure} ${entry.lastSelfTestFailureReason || ''}`);
    console.log(`  files         : ${files.length}`);
  }
}

writeFileSync(join(outDir, 'summary.json'), JSON.stringify(summary, null, 2));
console.log(`\nSummary written to ${join(outDir, 'summary.json')}`);
