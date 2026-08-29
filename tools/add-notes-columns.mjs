// Adds a "Notes from us" header beside each guidance column on the schedule
// tab, so applied guidance has somewhere to go that matches the catalog: one
// column to the right, in the same row.
//
// The columns are at different letters because each hugs the right edge of its
// own section and the sections are different widths, so this works from the
// guidance headers rather than from fixed positions.
import { GoogleAuth } from 'google-auth-library';

const ID = '<SHEET_ID>';
const auth = new GoogleAuth({ keyFile: 'service-account.json', scopes: ['https://www.googleapis.com/auth/spreadsheets'] });
const client = await auth.getClient();
const api = (m, u, d) => client.request({ method: m, url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}${u}`, data: d });

const col = (i) => String.fromCharCode(65 + i);
const meta = (await api('GET', '?fields=sheets.properties')).data;
const sheet = meta.sheets.find((s) => s.properties.title === 'Schedule');
const vals = (await api('GET', `/values/${encodeURIComponent('Schedule!A1:Z260')}`)).data.values || [];

const targets = [];
vals.forEach((row, r) => (row || []).forEach((cell, c) => {
  if (String(cell || '').trim().toLowerCase() === 'change guidance from us' && c > 0 && String(row[c - 1] || '').trim()) {
    targets.push({ r, c });
  }
}));

// Section 2's guidance sits in column I, the last column the grid has, so the
// notes column beside it does not exist yet. Widen before writing, or the API
// rejects the range outright.
const needed = Math.max(...targets.map((t) => t.c + 2));
const have = sheet.properties.gridProperties?.columnCount || 0;
if (needed > have) {
  await api('POST', ':batchUpdate', { requests: [{ updateSheetProperties: {
    properties: { sheetId: sheet.properties.sheetId, gridProperties: { columnCount: needed } },
    fields: 'gridProperties.columnCount',
  } }] });
  console.log(`widened the tab from ${have} to ${needed} columns`);
}

for (const t of targets) {
  const ref = `Schedule!${col(t.c + 1)}${t.r + 1}`;
  const current = String((vals[t.r] || [])[t.c + 1] || '').trim();
  if (current) { console.log(`${ref} already reads "${current}" - leaving it`); continue; }
  await api('PUT', `/values/${encodeURIComponent(ref)}?valueInputOption=RAW`, { values: [['Notes from us']] });
  console.log(`wrote "Notes from us" at ${ref}  (beside guidance ${col(t.c)}${t.r + 1})`);
}
