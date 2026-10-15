import { readFileSync, readdirSync, existsSync, writeFileSync, statSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { portal } from './content.mjs';
export function writeNotices() {
  mkdirSync(path.join(portal,'.cache'),{recursive:true});
  const lock = JSON.parse(readFileSync(path.join(portal,'package-lock.json'),'utf8'));
  const notices = ['Third-party dependencies used to build or serve Gameómetro documentation.\nThe static container contains no npm runtime. Optional packages for other platforms are not installed.\n'];
  const inventory = [];
  for (const [location,pkg] of Object.entries(lock.packages)) {
    if (!location || !existsSync(path.join(portal,location))) continue;
    const directory = path.join(portal,location);
    const manifest = JSON.parse(readFileSync(path.join(directory,'package.json'),'utf8'));
    const licenseFiles = readdirSync(directory).filter(file=>/^licen[sc]e(?:[.-].*)?$/i.test(file) && statSync(path.join(directory,file)).isFile());
    if (!manifest.license && !licenseFiles.length) throw new Error(`Review missing dependency licence: ${location}`);
    const license = manifest.license || 'See bundled licence';
    inventory.push({name:manifest.name,version:pkg.version,license});
    notices.push(`\n--- ${manifest.name} ${pkg.version} (${license}) ---\n`);
    for (const file of licenseFiles) notices.push(readFileSync(path.join(directory,file),'utf8'));
  }
  writeFileSync(path.join(portal,'.cache/licenses.json'),JSON.stringify(inventory,null,2));
  writeFileSync(path.join(portal,'public/third-party-notices.txt'),notices.join('\n'));
  console.log(`Recorded licences for ${inventory.length} installed dependencies`);
}
