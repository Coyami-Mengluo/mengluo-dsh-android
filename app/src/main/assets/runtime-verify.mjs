// Validate the official dependency lock before a mirror transports its bytes.
import fs from 'node:fs';
import path from 'node:path';
const [root, version, integrity] = process.argv.slice(2);
const lock = JSON.parse(fs.readFileSync(path.join(root, 'package-lock.json'), 'utf8'));
if (lock.lockfileVersion !== 3 || lock.packages?.['']?.dependencies?.['@deepseek-ai/dsh'] !== version) throw Error('Harness version lock mismatch');
for (const [name, pkg] of Object.entries(lock.packages)) {
  if (!name) continue;
  if (!name.startsWith('node_modules/') || name.includes('\\') || name.split('/').some(v => v === '..' || v === '.') || pkg.link) throw Error('Unsafe dependency path');
  const url = new URL(pkg.resolved);
  if (url.origin !== 'https://registry.npmjs.org' || url.username || url.password || url.search || url.hash || !url.pathname.endsWith('.tgz') || !/^sha512-[A-Za-z0-9+/]{86}==$/.test(pkg.integrity)) throw Error('Unverified npm dependency');
}
const pkg = lock.packages['node_modules/@deepseek-ai/dsh'];
if (pkg?.version !== version || pkg.integrity !== integrity) throw Error('Official artifact integrity mismatch');
for (const name of ['@deepseek-ai/dsh-web-app', '@deepseek-ai/dsh-web-frontend']) {
  if (lock.packages['node_modules/' + name]?.version !== version) throw Error('Official web package not in sync: ' + name);
}
console.log('Official dependency graph verified');
