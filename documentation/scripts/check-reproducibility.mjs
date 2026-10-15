import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { portal } from './content.mjs';
function coldBuild() {
  // This directory contains only content-addressed generated diagram outputs.
  rmSync(path.join(portal,'.cache/diagrams'),{recursive:true,force:true});
  execFileSync('npm',['run','build'],{cwd:portal,stdio:'inherit'});
  const root = path.join(portal,'dist');
  return Object.fromEntries(readdirSync(root,{recursive:true,withFileTypes:true}).filter(entry=>entry.isFile()).map(entry=>{
    const file = path.join(entry.parentPath,entry.name);
    return [path.relative(root,file),createHash('sha256').update(readFileSync(file)).digest('hex')];
  }).sort(([a],[b])=>a.localeCompare(b)));
}
const first = coldBuild();
const second = coldBuild();
const changed = [...new Set([...Object.keys(first),...Object.keys(second)])].filter(file=>first[file] !== second[file]);
writeFileSync(path.join(portal,'.cache/reproducibility.json'),JSON.stringify({files:Object.keys(second).length,changed},null,2));
if (changed.length) throw new Error(`Cold builds differ: ${changed.join(', ')}`);
console.log(`Two cold builds produced ${Object.keys(second).length} byte-identical files`);
