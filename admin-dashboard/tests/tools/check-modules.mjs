// Static check: import every dashboard module (catches syntax errors and
// missing/renamed named exports, which fail ES module linking).
import { readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { installDomStub } from './dom-stub.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const ROOT = join(here, '../../../backend/app/static/admin/js');

function walk(dir) {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : (p.endsWith('.js') ? [p] : []);
  });
}

installDomStub('');
const files = walk(ROOT).sort((a, b) => (a.endsWith('app.js') ? 1 : 0) - (b.endsWith('app.js') ? 1 : 0));
let failed = 0;
for (const f of files) {
  try {
    await import(pathToFileURL(f).href);
    console.log(`ok    ${relative(ROOT, f)}`);
  } catch (e) {
    failed += 1;
    console.error(`FAIL  ${relative(ROOT, f)}\n      ${e && e.message}`);
  }
}
console.log(`\n${files.length - failed}/${files.length} modules imported without error.`);
process.exit(failed ? 1 : 0);
