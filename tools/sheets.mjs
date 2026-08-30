// Shared plumbing for the scripts that read and write the Google Sheet.
//
// It exists because the same few lines were copied into six scripts, and the
// copies drifted. The spreadsheet ID appeared ten times and the Firestore
// document path five, so either one changing meant finding every copy. Worse,
// a real bug had to be fixed twice: a ragged write leaves the previous run's
// text showing, and that was fixed in the catalog builder and then again,
// separately, in the change log.
//
// What is deliberately NOT here is how each script writes its grid. The catalog
// and the change log pad every row to the full width so no cell can show
// through; the schedule tab must not, because its yellow guidance columns sit
// immediately right of each section's content and padding would erase them.
// That is a real difference, not duplication, and flattening it would lose the
// distinction.
import { GoogleAuth } from "google-auth-library";
import { id } from "./ids.mjs";

/** The household's schedule spreadsheet. Supplied per-checkout; see ids.mjs. */
export const SHEET_ID = id("SHEET_ID");

/** Tab names, so a rename is one edit rather than a search. */
export const TAB = {
  catalog: "Catalog",
  schedule: "Schedule",
  changelog: "Schedule change log",
};

/**
 * An authenticated caller for the Sheets REST API.
 *
 * Returns `api(method, urlSuffix, body)` where the suffix is appended to the
 * spreadsheet URL, which is the shape every one of these scripts had already
 * settled on independently.
 */
export async function sheetsApi(id = SHEET_ID) {
  const auth = new GoogleAuth({
    keyFile: "./service-account.json",
    scopes: ["https://www.googleapis.com/auth/spreadsheets"],
  });
  const client = await auth.getClient();
  return (method, url, data) => client.request({
    method,
    url: `https://sheets.googleapis.com/v4/spreadsheets/${id}${url}`,
    data,
  });
}

/** Column index to its letter: 0 -> A, 26 -> AA. */
export const colName = (i) => (i < 26
  ? String.fromCharCode(65 + i)
  : String.fromCharCode(64 + Math.floor(i / 26)) + String.fromCharCode(65 + (i % 26)));

/** Column letter to its index: A -> 0, AA -> 26. */
export const colIndex = (s) =>
  [...s].reduce((n, ch) => n * 26 + (ch.charCodeAt(0) - 64), 0) - 1;

/** Read a range as a 2D array, or [] if it is empty. */
export const readRange = async (api, range) =>
  (await api("GET", `/values/${encodeURIComponent(range)}`)).data.values || [];

/** Write a single cell. */
export const writeCell = (api, range, value) =>
  api("PUT", `/values/${encodeURIComponent(range)}?valueInputOption=RAW`, { values: [[value]] });

/**
 * Rename a tab in place, keeping its id, formatting and anything written on it
 * by hand.
 *
 * Every one of these builders finds its tab by title and treats a miss as
 * "create it from nothing" - which is also when it applies its own column
 * widths. Simply changing the title constant would therefore build a fresh tab
 * with script defaults and orphan the real one, taking the yellow guidance
 * columns with it. Mutates the passed metadata so the caller's later lookup by
 * the new title succeeds.
 */
export async function renameTabInPlace(api, meta, previousTitle, title) {
  const previous = meta.sheets.find((s) => s.properties.title === previousTitle);
  const already = meta.sheets.find((s) => s.properties.title === title);
  if (!previous || already) return false;
  await api("POST", ":batchUpdate", { requests: [{ updateSheetProperties: {
    properties: { sheetId: previous.properties.sheetId, title },
    fields: "title",
  } }] });
  previous.properties.title = title;
  console.log(`renamed "${previousTitle}" to "${title}"`);
  return true;
}

/**
 * Pad every row to `width` so no cell can show the previous run's text.
 *
 * Sheets writes only the cells it is given, so a row shorter than the grid
 * leaves the rest of that row exactly as it was - and a bare spacer row writes
 * nothing whatsoever. Use only where the whole grid belongs to the generator.
 */
export const padRows = (rows, width) =>
  rows.map((r) => Array.from({ length: width }, (_, i) => r[i] ?? ""));

/** Clear whatever sits below `keptRows`, for the run where the grid shrinks. */
export async function clearBelow(api, tab, keptRows, hadRows, lastColumn = "AZ") {
  if (!hadRows || hadRows <= keptRows) return;
  await api(
    "POST",
    `/values/${encodeURIComponent(`${tab}!A${keptRows + 1}:${lastColumn}${hadRows}`)}:clear`,
    {},
  );
}
