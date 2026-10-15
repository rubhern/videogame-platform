import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { load } from 'cheerio';
export function validateSite(root) {
  const files = readdirSync(root,{recursive:true}).filter(file=>file.endsWith('.html'));
  const pages = new Map(files.map(file=>[path.join(root,file), load(readFileSync(path.join(root,file),'utf8'))]));
  const errors = [];
  for (const [file,$] of pages) {
    const base = new URL('https://documentation.invalid/'+path.relative(root,file).replace(/index\.html$/,''));
    $('a[href],img[src],script[src],link[href],source[srcset]').each((_,element)=>{
      const attr = element.name === 'a' || element.name === 'link' ? 'href' : element.name === 'source' ? 'srcset' : 'src';
      const raw = $(element).attr(attr);
      if (!raw || /^(?:mailto:|data:|tel:|javascript:)/.test(raw)) return;
      const url = new URL(raw,base);
      if (url.origin !== base.origin) {
        if (element.name !== 'a') errors.push(`${file}: external runtime resource ${raw}`);
        return;
      }
      let target = path.join(root,decodeURIComponent(url.pathname));
      if (existsSync(target) && statSync(target).isDirectory()) target = path.join(target,'index.html');
      if (!existsSync(target)) { errors.push(`${path.relative(root,file)}: missing ${raw}`); return; }
      if (element.name === 'a' && url.hash && target.endsWith('.html')) {
        const fragment = decodeURIComponent(url.hash.slice(1));
        const destination = pages.get(target);
        if (!destination || !destination('[id]').toArray().some(el=>destination(el).attr('id') === fragment)) errors.push(`${path.relative(root,file)}: missing fragment ${raw}`);
      }
    });
    if ($('h1').length !== 1) errors.push(`${file}: expected exactly one page title`);
  }
  if (!existsSync(path.join(root,'pagefind/pagefind.js'))) errors.push('Missing local search index');
  if (errors.length) throw new Error(errors.join('\n'));
  return files.length;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = fileURLToPath(new URL('../dist/',import.meta.url));
  const count = validateSite(root);
  console.log(`Validated ${count} HTML pages, internal links, anchors, assets and search`);
}
