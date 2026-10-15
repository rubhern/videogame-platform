import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, symlinkSync, lstatSync, statSync, readdirSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { repo, portal, sources, resolveLink, transform, statusFor, writePage } from './content.mjs';
import { routeFor } from '../content-map.mjs';
test('page generation replaces destination symlinks and leaves unchanged pages untouched',()=>{
  mkdirSync(path.join(portal,'src/content/docs'),{recursive:true});
  const directory = mkdtempSync(path.join(portal,'src/content/docs/write-test-'));
  const route = '/' + path.basename(directory) + '/page/';
  const output = path.join(directory,'page.md');
  const target = path.join(directory,'local-file');
  try {
    writeFileSync(target,'Must remain unchanged');
    symlinkSync(target,output);
    writePage(route,'Test page','Initial content');
    assert.equal(readFileSync(target,'utf8'),'Must remain unchanged');
    assert.equal(lstatSync(output).isSymbolicLink(),false);
    assert.match(readFileSync(output,'utf8'),/Initial content/);
    const before = statSync(output,{bigint:true}).mtimeNs;
    writePage(route,'Test page','Initial content');
    assert.equal(statSync(output,{bigint:true}).mtimeNs,before);
    writePage(route,'Test page','Updated content');
    assert.match(readFileSync(output,'utf8'),/Updated content/);
    assert.deepEqual(readdirSync(directory).sort(),['local-file','page.md']);
  } finally { rmSync(directory,{recursive:true,force:true}); }
});
test('content boundary excludes ignored/generated/configuration trees',()=>{
  const selected = sources();
  assert(selected.includes('docs/README.md'));
  assert(selected.includes('deploy/private-dev/README.md'));
  assert(selected.every(file=>file.endsWith('.md') && !/\.env|generated|node_modules|\.agents/.test(file)));
  assert.equal(new Set(selected.map(routeFor)).size,selected.length);
  const fixture = mkdtempSync(path.join(repo,'docs/research/portal-boundary-'));
  try {
    writeFileSync(path.join(fixture,'private.json'),'{"private":"local-only"}');
    const relative = path.relative(path.join(repo,'docs'),path.join(fixture,'private.json'));
    assert.throws(()=>resolveLink(relative,'docs/README.md',new Set(selected)),/attachment is not tracked/);
  } finally { rmSync(fixture,{recursive:true}); }
});
test('relative page links and fragments survive transformation',()=>{
  const known = new Set(sources());
  assert.equal(resolveLink('../README.md#canonical-ownership','docs/development/example.md',known),'/docs/#canonical-ownership');
  assert.equal(resolveLink('#details','docs/README.md',known),'#details');
  assert.equal(resolveLink('https://github.com/rubhern/videogame-platform/issues/38','docs/README.md',known),'https://github.com/rubhern/videogame-platform/issues/38');
  assert.throws(()=>resolveLink('missing.md','docs/README.md',known),/missing link target/);
  assert.throws(()=>resolveLink('../../escape','README.md',known),/escapes repository/);
});
test('frontmatter is derived and inline Mermaid becomes a rendered figure',()=>{
  const result = transform('# Existing title\n\n[Map](README.md)\n\n```mermaid\nflowchart LR\nA-->B\n```\n','README.md',new Set(['README.md']),code=>`<figure>${code.includes('A-->B')}</figure>`);
  assert.equal(result.title,'Existing title');
  assert.match(result.body,/\/overview\//);
  assert.match(result.body,/<figure>true<\/figure>/);
  assert.doesNotMatch(result.body,/# Existing title/);
});
test('historical and superseded sources cannot acquire a current label',()=>{
  assert.equal(statusFor('docs/research/test.md','')[0],'Historical evidence');
  assert.equal(statusFor('docs/decisions/test.md','- **Status:** Superseded by ADR-0019')[0],'Superseded');
  assert.equal(statusFor('docs/decisions/test.md','- **Status:** Proposed')[0],'Proposed');
});
