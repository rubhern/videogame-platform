import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, existsSync, copyFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer';
import { chromium } from '@playwright/test';
import { run } from '@mermaid-js/mermaid-cli';
import { portal, repo, escapeHtml } from './content.mjs';
export const c4Image = 'structurizr/structurizr:2026.09.19@sha256:721136283c2f9cf1ba69037bc9de136c579d66fdcb2d771cb60546ec68def1a5';
export const plantumlImage = 'plantuml/plantuml:1.2026.8@sha256:d08610df482510844382caa4e016ba2bf7e3231f630f02ee12f250f3416c62b1';
const cache = path.join(portal,'.cache/diagrams');
const output = path.join(portal,'public/diagrams');
export function digest(value) { return createHash('sha256').update(value).digest('hex'); }
export function figure(name, caption, source) {
  return `<figure class="diagram"><div class="diagram-tools"><button type="button" data-diagram-zoom aria-pressed="false">Read at full size</button><a class="diagram-light" href="/diagrams/${name}-light.svg">Open SVG</a><a class="diagram-dark" href="/diagrams/${name}-dark.svg">Open SVG</a><a href="${source}">Original source</a></div><div class="diagram-viewport" tabindex="0" role="region" aria-label="${escapeHtml(caption)}; scroll when expanded"><img class="diagram-light" src="/diagrams/${name}-light.svg" alt="${escapeHtml(caption)}" loading="lazy"><img class="diagram-dark" src="/diagrams/${name}-dark.svg" alt="${escapeHtml(caption)}" loading="lazy"></div><figcaption>${escapeHtml(caption)}. Fit to the reading column; use full size to scroll details.</figcaption></figure>`;
}
export async function renderDiagrams(jobs) {
  mkdirSync(cache,{recursive:true}); mkdirSync(output,{recursive:true});
  const baseline = readFileSync(path.join(repo,'docs/architecture/diagrams/scripts/render-mermaid.sh'),'utf8').match(/MERMAID_CLI_VERSION="([^"]+)"/)[1];
  const installed = JSON.parse(readFileSync(path.join(portal,'node_modules/@mermaid-js/mermaid-cli/package.json'),'utf8')).version;
  if (baseline !== installed) throw new Error('Portal Mermaid CLI must match the pinned diagram catalogue renderer');
  let browser;
  try {
    for (const {name, text} of jobs) {
      for (const theme of ['light','dark']) {
        const config = {theme: theme === 'dark' ? 'dark' : 'default', securityLevel:'strict', deterministicIds:true, deterministicIDSeed:name, handDrawnSeed:38,
          fontFamily:'Arial, sans-serif', sequence:{wrap:true, width:210, actorMargin:60, messageMargin:40, noteMargin:20}, flowchart:{htmlLabels:true, wrappingWidth:220}};
        const key = digest(text + JSON.stringify(config) + readFileSync(path.join(portal,'package-lock.json')));
        const cached = path.join(cache,`${key}.svg`);
        if (!existsSync(cached)) {
          browser ??= await puppeteer.launch({executablePath:chromium.executablePath(), headless:true, args:['--no-sandbox']});
          const input = path.join(cache,`${key}.mmd`); writeFileSync(input,text);
          await run(input,cached,{browser,quiet:true,parseMMDOptions:{mermaidConfig:config,backgroundColor:theme === 'dark' ? '#17191f' : '#ffffff',svgId:name,viewport:{width:1800,height:1200,deviceScaleFactor:1}}});
        }
        copyFileSync(cached,path.join(output,`${name}-${theme}.svg`));
      }
      writeFileSync(path.join(output,`${name}.mmd`),text);
    }
  } finally { await browser?.close(); }
  const model = path.join(repo,'docs/architecture/diagrams/structurizr/workspace.dsl');
  const c4Key = digest(readFileSync(model) + c4Image + plantumlImage + readFileSync(fileURLToPath(import.meta.url)));
  const target = path.join(cache,c4Key); mkdirSync(target,{recursive:true});
  if (!['SystemContext','Containers'].every(view => existsSync(path.join(target,`structurizr-${view}.puml`)))) {
    execFileSync('docker',['run','--rm','--network','none','--user',`${process.getuid()}:${process.getgid()}`,
      '--volume',`${path.dirname(model)}:/model:ro`,'--volume',`${target}:/output`,c4Image,
      'export','-workspace','/model/workspace.dsl','-format','plantuml/c4plantuml','-output','/output'],{stdio:'inherit'});
  }
  for (const theme of ['light','dark']) {
    const themed = path.join(target,theme); mkdirSync(themed,{recursive:true});
    for (const view of ['SystemContext','Containers']) {
      // Only presentation changes here. Every element and relationship comes from Structurizr.
      let definition = readFileSync(path.join(target,`structurizr-${view}.puml`),'utf8');
      definition = definition.replace(/^SHOW_LEGEND\([^\n]*\)\r?\n/gm,'');
      // Graphviz's default spacing crowds adjacent relationship labels in the container view.
      definition = definition.replace('hide stereotypes','skinparam nodesep 100\nskinparam ranksep 100\nhide stereotypes');
      if (theme === 'dark') definition = definition.replace('BackgroundColor: #ffffff','BackgroundColor: #17191f').replaceAll('$bgColor="#ffffff"','$bgColor="#17191f"').replaceAll('#444444','#e4e4ed')
        .replaceAll('#4b5563','#b8c2d1').replaceAll('#0b4884','#b8c2d1')
        .replace('hide stereotypes','skinparam legendBackgroundColor #17191f\nskinparam legendFontColor #e4e4ed\nhide stereotypes');
      writeFileSync(path.join(themed,`${view}.puml`),definition);
    }
    if (!['SystemContext','Containers'].every(view => existsSync(path.join(themed,`${view}.svg`)))) {
      execFileSync('docker',['run','--rm','--network','none','--user',`${process.getuid()}:${process.getgid()}`,
        '--volume',`${themed}:/work`,plantumlImage,'-failfast2','-tsvg','/work/SystemContext.puml','/work/Containers.puml'],{stdio:'inherit'});
    }
    for (const view of ['SystemContext','Containers']) copyFileSync(path.join(themed,`${view}.svg`),path.join(output,`${view}-${theme}.svg`));
  }
  copyFileSync(model,path.join(output,'workspace.dsl'));
  for (const file of readdirSync(output).filter(file => file.endsWith('.svg'))) {
    let svg = readFileSync(path.join(output,file),'utf8');
    const bounds = svg.match(/viewBox="[\d.-]+ [\d.-]+ ([\d.]+) ([\d.]+)"/);
    if (bounds && svg.includes('width="100%"')) {
      svg = svg.replace('width="100%"',`width="${bounds[1]}" height="${bounds[2]}"`);
      writeFileSync(path.join(output,file),svg);
    }
    if (!svg.includes('<svg') || /class="error-icon"|>Syntax error/.test(svg) || svg.length < 300) throw new Error(`Invalid diagram: ${file}`);
    if (/<script\b|(?:href|src)=["'](?:https?:)?\/\//i.test(svg)) throw new Error(`Diagram has an external dependency: ${file}`);
  }
  console.log(`Rendered ${jobs.length} Mermaid diagrams and 2 C4 views in both themes`);
}
