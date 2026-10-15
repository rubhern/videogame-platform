import { readFileSync, readdirSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { sources, trackedPaths, repo, portal, transform, writePage, statusFor, github, cleanContent, escapeHtml } from './content.mjs';
import { writeNotices } from './licenses.mjs';
import { renderDiagrams, figure, digest } from './diagrams.mjs';
import { routeFor, groupFor, groups, labels } from '../content-map.mjs';
export async function prepare({clean = false} = {}) {
  if (clean) {
    cleanContent();
    rmSync(path.join(portal,'public/diagrams'),{recursive:true,force:true});
  }
  mkdirSync(path.join(portal,'public'),{recursive:true});
  writeFileSync(path.join(portal,'public/favicon.svg'),readFileSync(path.join(repo,'frontend/public/favicon.svg')));
  writeNotices();
  const selected = sources(); const known = new Set(selected); const jobs = [];
  const tracked = new Set(trackedPaths());
  const mermaidDir = 'docs/architecture/diagrams/mermaid';
  const catalogue = readFileSync(path.join(repo,'docs/architecture/diagrams/README.md'),'utf8');
  const diagramPages = readdirSync(path.join(repo,mermaidDir)).filter(name => name.endsWith('.mmd')).sort().map(file => {
    if (!tracked.has(`${mermaidDir}/${file}`)) throw new Error(`Catalogue diagram is not tracked: ${file}`);
    const name = path.basename(file,'.mmd');
    const row = catalogue.split('\n').find(line => line.includes(`mermaid/${file}`));
    if (!row) throw new Error(`Diagram missing from catalogue: ${file}`);
    const caption = row.split('|')[2].trim();
    jobs.push({name,text:readFileSync(path.join(repo,mermaidDir,file),'utf8')});
    return {name,caption};
  });
  for (const name of [...catalogue.matchAll(/mermaid\/([^`]+\.mmd)/g)].map(match => match[1])) {
    if (!diagramPages.some(page => page.name + '.mmd' === name)) throw new Error(`Catalogue references missing diagram ${name}`);
  }
  const pages = selected.map(source => {
    const text = readFileSync(path.join(repo,source),'utf8');
    const transformed = transform(text,source,known,code => {
      const name = 'inline-' + digest(source + code).slice(0,16); jobs.push({name,text:code});
      return figure(name,'Inline diagram from '+source,'/diagrams/'+name+'.mmd');
    });
    return {source,text,...transformed};
  });
  await renderDiagrams(jobs);
  for (const page of pages) {
    let body = page.body;
    if (page.source === 'docs/architecture/diagrams/README.md') body = `## Explore the system visually\n\n[System Context & Containers](/diagrams/c4/)\n\n${diagramPages.map(({name,caption})=>`- [${labels.get(name) || name.replaceAll('-',' ')}](/diagrams/${name}/) — ${caption}`).join('\n')}\n\n${body}`;
    writePage(routeFor(page.source),page.title,body,page.source,statusFor(page.source,page.text));
  }
  for (const {name,caption} of diagramPages) writePage(`/diagrams/${name}/`,name.replaceAll('-',' ').replace(/^./,c=>c.toUpperCase()),`[Visual architecture](/docs/architecture/diagrams/)\n\n${figure(name,caption,`/diagrams/${name}.mmd`)}\n\n[Canonical source](${github+mermaidDir+'/'+name+'.mmd'})`,undefined);
  writePage('/diagrams/c4/','System Context & Containers',`[Visual architecture](/docs/architecture/diagrams/)\n\nDerived directly from the canonical Structurizr DSL using the pinned official C4-PlantUML exporter and offline PlantUML renderer. The model describes the product system; this documentation service is outside its boundary.\n\n## System Context\n\n${figure('SystemContext','People and external systems interacting with VideoGame Platform','/diagrams/workspace.dsl')}\n\n## Containers\n\n${figure('Containers','Runtime responsibilities, external dependencies and data stores','/diagrams/workspace.dsl')}`,undefined);
  const sections = groups.map(([title,entries])=>`<section><h2>${escapeHtml(title)}</h2><ul>${entries.slice(0,4).map(([source,label])=>`<li><a href="${routeFor(source)}">${escapeHtml(label)}</a></li>`).join('')}</ul></section>`).join('');
  writePage('/','Gameómetro knowledge base',`<p class="portal-intro">Understand the product. Follow the architecture. Operate with confidence.</p>\n\nThe engineering reference for Gameómetro, from its accepted learning MVP to current private operation and post-MVP direction.\n\n<div class="portal-start"><a href="/docs/development/local-setup/">Get started locally →</a><a href="/docs/architecture/diagrams/">Explore visual architecture →</a></div>\n\n<div class="knowledge-grid">${sections}</div>\n\n## Read with the right context\n\nRepository sources retain their authority. Page context identifies maintained references, proposals, pending decisions and historical evidence. Approved documents can contain future sections; their own status always controls.\n\nUse the [documentation map](/docs/) to find the canonical owner. Search runs locally in your browser, with an index generated at build time. [Third-party notices](/third-party-notices.txt).`,undefined);
  mkdirSync(path.join(portal,'.cache'),{recursive:true});
  writeFileSync(path.join(portal,'.cache/content-manifest.json'),JSON.stringify({pages:pages.map(page=>({source:page.source,route:routeFor(page.source),title:page.title,group:groupFor(page.source)})),diagrams:jobs.map(({name})=>name)},null,2));
}
