import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync, mkdirSync, mkdtempSync, renameSync, writeFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkStringify from 'remark-stringify';
import GithubSlugger from 'github-slugger';
import { visit } from 'unist-util-visit';
import { groups, labels, routeFor } from '../content-map.mjs';
export const portal = fileURLToPath(new URL('../', import.meta.url));
export const repo = path.resolve(portal, '..');
export const github = 'https://github.com/rubhern/videogame-platform/blob/main/';
export const processor = unified().use(remarkParse).use(remarkGfm).use(remarkStringify, {bullet:'-', fences:true});
export function trackedPaths() {
  return execFileSync('git', ['ls-files', '-z'], {cwd:repo, encoding:'utf8'}).split('\0').filter(Boolean);
}
export function sources() {
  const candidates = trackedPaths();
  const selected = new Set(groups.flatMap(([, entries]) => entries.map(([source]) => source)));
  for (const source of candidates) if (/^docs\/.+\.md$/.test(source)) selected.add(source);
  return [...selected].sort();
}
export function resolveLink(url, source, known) {
  if (/^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(url)) return url;
  const [, pathname, suffix] = url.match(/^([^?#]*)([\s\S]*)$/);
  if (!pathname) return routeFor(source) + suffix;
  const target = path.posix.normalize(path.posix.join(path.posix.dirname(source), decodeURIComponent(pathname)));
  if (target.startsWith('../') || path.isAbsolute(target)) throw new Error(`${source}: link escapes repository: ${url}`);
  if (!existsSync(path.join(repo, target))) throw new Error(`${source}: missing link target: ${url}`);
  if (known.has(target)) return routeFor(target) + suffix;
  if (/^docs\/architecture\/diagrams\/mermaid\/.*\.mmd$/.test(target)) return `/diagrams/${path.basename(target, '.mmd')}/` + suffix;
  if (target === 'docs/architecture/diagrams/structurizr/workspace.dsl') return '/diagrams/c4/' + suffix;
  // Only reviewed non-Markdown assets enter the site. Executable files link to GitHub.
  if (/^docs\/(product\/assets\/[^/]+\.(?:png|svg)|reference\/video-game-platform-vision\.pdf|architecture\/api\/openapi\.yaml|research\/[\w/.-]+\.(?:json|csv))$/.test(target)) {
    if (!trackedPaths().includes(target)) throw new Error(`${source}: attachment is not tracked: ${url}`);
    const out = path.join(portal, 'public/repository', target);
    mkdirSync(path.dirname(out), {recursive:true}); writeFileSync(out, readFileSync(path.join(repo,target)));
    return '/repository/' + target + suffix;
  }
  return github + target + suffix;
}
export function transform(text, source, known, diagram) {
  const tree = processor.parse(text);
  const first = tree.children.find(node => node.type === 'heading' && node.depth === 1);
  const title = first ? text.slice(first.position.start.offset, first.position.end.offset).replace(/^#\s+/, '') : labels.get(source);
  if (!title) throw new Error(`No title for ${source}`);
  if (first) tree.children.splice(tree.children.indexOf(first), 1);
  visit(tree, node => {
    if (['link', 'image', 'definition'].includes(node.type)) node.url = resolveLink(node.url, source, known);
    if (node.type === 'code' && node.lang === 'mermaid') {
      node.type = 'html'; node.value = diagram(node.value); delete node.lang;
    }
  });
  const anchor = first ? `<span id="${new GithubSlugger().slug(title.replace(/[*`]/g,''))}"></span>\n\n` : '';
  return {title, body:anchor + processor.stringify(tree)};
}
export function statusFor(source, text) {
  if (source.startsWith('docs/research/')) return ['Historical evidence', 'Point-in-time research. Current decisions and operator procedures remain with their canonical owners.'];
  const status = text.match(/^- \*\*Status:\*\*\s*(.+)$/m)?.[1];
  if (status && /superseded/i.test(status)) return ['Superseded', status];
  if (status && /proposed/i.test(status)) return ['Proposed', status];
  if (status && /pending|future/i.test(status)) return ['Approved / pending', status];
  return [status || 'Maintained reference', 'Current reference; future, pending and accepted-baseline sections retain the status stated in the source.'];
}
export function writePage(route, title, body, source, status) {
  const out = path.join(portal, 'src/content/docs', route === '/' ? 'index.md' : route.slice(1,-1) + '.md');
  mkdirSync(path.dirname(out), {recursive:true});
  const metadata = {title, editUrl: source ? github.replace('/blob/', '/edit/') + source : false};
  const context = source ? `<aside class="source-context" aria-label="Document context"><strong>${escapeHtml(status[0])}</strong><p>${escapeHtml(status[1])}</p><a href="${github + source}">Canonical source · ${escapeHtml(source)}</a></aside>\n\n` : '';
  const output = `---\n${JSON.stringify(metadata)}\n---\n\n${context}${body}`;
  try {
    if (readFileSync(out,'utf8') === output) return;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  // Replace the destination atomically; never follow a changed destination symlink.
  const temporary = mkdtempSync(path.join(path.dirname(out),'.portal-'));
  try {
    const file = path.join(temporary,'page');
    writeFileSync(file,output,{flag:'wx',mode:0o644});
    renameSync(file,out);
  } finally { rmSync(temporary,{recursive:true,force:true}); }
}
export function escapeHtml(text) { return text.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;'); }
export function cleanContent() {
  rmSync(path.join(portal, 'src/content/docs'), {recursive:true, force:true});
  rmSync(path.join(portal, 'public/repository'), {recursive:true, force:true});
}
