// Dumps the Weekly tab so we can see exactly what would be lost by deleting it,
// rather than assuming sheet-legacy.json already holds everything.
import { GoogleAuth } from 'google-auth-library';

const ID = '<SHEET_ID>';
const auth = new GoogleAuth({ keyFile: 'service-account.json', scopes: ['https://www.googleapis.com/auth/spreadsheets'] });
const client = await auth.getClient();
const res = await client.request({
  url: `https://sheets.googleapis.com/v4/spreadsheets/${ID}/values/${encodeURIComponent('Weekly!A1:AA300')}`,
});
const rows = res.data.values || [];
console.log(`total rows returned: ${rows.length}`);
rows.forEach((r, i) => {
  const cells = (r || []).map((c) => String(c ?? '').replace(/\s+/g, ' ').trim());
  if (!cells.some(Boolean)) return;
  console.log(`${String(i + 1).padStart(3)}: ${cells.map((c) => c.slice(0, 42)).join(' | ')}`);
});
