const fs = require('fs');
const vm = require('vm');
const html = fs.readFileSync('web/index.html', 'utf8');
const m = html.match(/<script type="module">([\s\S]*?)<\/script>/);
if (!m) { console.error('no module script found'); process.exit(1); }
// Strip whole ESM import statements (they can span lines) and top-level await,
// so the remainder parses as a plain script.
const body = m[1]
  .replace(/^\s*import[\s\S]*?;\s*$/gm, '')
  .replace(/\bawait /g, '');
try {
  new vm.Script(body, { filename: 'console.js' });
  console.log('web console JS parses OK');
} catch (e) {
  console.error('PARSE ERROR:', e.message);
  const line = (e.stack || '').split('\n')[1] || '';
  console.error(line.trim());
  process.exit(1);
}
