// Render the default 1200x630 link-preview image (#581) from the canonical Gameómetro logo.
// The logo's geometry and colours are unchanged; the night canvas and the ice-light line come
// from the brand families in frontend/src/styles/index.css. Run with the repository's
// Playwright/Chromium, on the host or inside the pinned test image of validate-browser.sh.
import { chromium } from '@playwright/test';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const logo = await readFile(path.join(root, 'frontend/src/shared/brand/gameometro-logo.svg'), 'utf8');
const font = await readFile(path.join(root, 'frontend/public/assets/fonts/mona-sans-latin.woff2'));
const fontLatinExt = await readFile(path.join(root, 'frontend/public/assets/fonts/mona-sans-latin-ext.woff2'));
const target = path.join(root, 'frontend/public/assets/share/gameometro.png');

// The logo is centred, so a client that crops the preview to a central square thumbnail still
// shows the G and most of the wordmark; the line beneath is supporting copy only.
const html = `<!doctype html><html lang="es"><head><meta charset="utf-8"><style>
@font-face{font-family:"Mona Sans";src:url(data:font/woff2;base64,${font.toString('base64')}) format("woff2");font-weight:200 900;unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+2000-206F}
@font-face{font-family:"Mona Sans";src:url(data:font/woff2;base64,${fontLatinExt.toString('base64')}) format("woff2");font-weight:200 900}
html,body{margin:0;width:1200px;height:630px;background:#06080d}
body{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:40px;
  background:radial-gradient(ellipse 60% 70% at 50% 46%,#0f1626 0%,#06080d 72%)}
svg{display:block;width:860px;height:auto}
p{margin:0;font:500 34px/1.25 "Mona Sans",system-ui,sans-serif;color:#dbe7ff;letter-spacing:.005em}
</style></head><body>${logo}<p>Lanzamientos de videojuegos y puntuaciones de la comunidad</p></body></html>`;

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1 });
  await page.setContent(html);
  await page.evaluate(() => document.fonts.ready);
  await mkdir(path.dirname(target), { recursive: true });
  await page.screenshot({ path: target, type: 'png' });
} finally {
  await browser.close();
}
