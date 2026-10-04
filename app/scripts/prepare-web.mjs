// Copies the web terminal (terminal/) into www/, the Capacitor webDir.
// Only the page and the local chart library: the PWA manifest, icons and service worker are web-only.
import { cpSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const src = join(root, '..', 'terminal');
const out = join(root, 'www');

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
for (const f of ['index.html', 'lightweight-charts.standalone.production.js']) cpSync(join(src, f), join(out, f));
console.log(`web assets copied from ${src} to ${out}`);
