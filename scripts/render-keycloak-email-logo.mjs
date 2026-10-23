// Rasterize the canonical vector without changing its geometry, colours or wordmark.
// Uses the repository's existing Playwright/Chromium; run inside its pinned test image.
import { chromium } from '@playwright/test';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const source = path.join(root, 'frontend/src/shared/brand/gameometro-logo.svg');
const target = path.join(root, 'docker/keycloak/themes/gameometro/email/resources/img/gameometro-logo.png');
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 548, height: 101 }, deviceScaleFactor: 1 });
  await page.setContent(`<style>html,body{margin:0;background:transparent}svg{display:block;width:548px;height:101px}</style>${await readFile(source, 'utf8')}`);
  await mkdir(path.dirname(target), { recursive: true });
  await page.locator('svg').screenshot({ path: target, omitBackground: true });
} finally {
  await browser.close();
}
