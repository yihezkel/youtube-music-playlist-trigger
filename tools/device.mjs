// The phone, as the tools address it.
//
// The Firestore document path was written out in five scripts. It is one
// device, and the id in the middle of that path is the only thing tying these
// scripts to the phone in the kitchen, so it belongs in one place where it can
// be found and changed.
import admin from "firebase-admin";
import { readFileSync } from "node:fs";
import { id } from "./ids.mjs";

/** The config document the app syncs from. */
export const CONFIG_DOC = `users/${id("USER_ID")}/devices/${id("DEVICE_ID")}/data/config`;

/**
 * The device document itself, which the phone stamps on every check-in.
 *
 * Its `json` field carries the state the console shows - health checks, recent
 * failures, playback state - and `updatedAtMs` says when the phone last spoke.
 */
export const DEVICE_DOC = CONFIG_DOC.replace(/\/data\/config$/, "");

let app = null;

/** The Firestore handle, initialised once however many callers ask for it. */
export function firestore() {
  if (!app) {
    app = admin.initializeApp({
      credential: admin.credential.cert(JSON.parse(readFileSync("./service-account.json", "utf8"))),
    });
  }
  return admin.firestore();
}

/** The config document reference. */
export const configRef = () => firestore().doc(CONFIG_DOC);

/**
 * The current config and its revision.
 *
 * Every caller was doing the same three steps - get the snapshot, JSON.parse
 * the "json" field, coerce the revision to a number - and a missed coercion
 * would push a revision that never increments.
 */
export async function readConfig() {
  const snap = await configRef().get();
  return {
    cfg: JSON.parse(snap.get("json")),
    revision: Number(snap.get("revision") || 0),
    snap,
  };
}
