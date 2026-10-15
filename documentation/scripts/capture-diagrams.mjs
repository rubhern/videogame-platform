import { chromium } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { portal } from './content.mjs';
const manifest = JSON.parse(readFileSync(path.join(portal,'.cache/content-manifest.json'),'utf8'));
const output = path.join(portal,'.cache/visual-review'); mkdirSync(output,{recursive:true});
const browser = await chromium.launch();
try {
  const page = await browser.newPage({viewport:{width:1800,height:1000}});
  for (const name of [...manifest.diagrams,'SystemContext','Containers']) {
    for (const theme of ['light','dark']) {
      const svg = readFileSync(path.join(portal,'public/diagrams',`${name}-${theme}.svg`));
      await page.setContent(`<html><body style="margin:0;background:${theme === 'dark' ? '#17191f' : '#fff'}"><img src="data:image/svg+xml;base64,${svg.toString('base64')}"></body></html>`);
      await page.locator('img').evaluate(image => image.decode());
      await page.screenshot({path:path.join(output,`${name}-${theme}.png`),fullPage:true});
    }
  }
} finally { await browser.close(); }
console.log(`Native-size diagrams for manual review: ${output}`);
