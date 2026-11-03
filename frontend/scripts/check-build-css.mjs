/**
 * Runs after `vite build`: the built stylesheet must hold the component rules. Without them the production app comes up
 * unstyled, which no development-server test can see (D-125). One selector from each area of the app.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REQUIRED = ['.signin-card', '.scan-stage', '.sidebar', '.rv-card', '.dt', '.tile', '.set-section'];
const MIN_BYTES = 50_000;

const assets = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist', 'assets');
const css = readdirSync(assets)
  .filter(name => name.endsWith('.css'))
  .map(name => readFileSync(join(assets, name), 'utf8'))
  .join('');
const missing = REQUIRED.filter(selector => !css.includes(selector));
if (missing.length > 0 || css.length < MIN_BYTES) {
  console.error(`build: the stylesheet is incomplete (${css.length} bytes; missing: ${missing.join(' ') || 'none'})`);
  process.exit(1);
}
console.log(`build: stylesheet complete (${css.length} bytes)`);
