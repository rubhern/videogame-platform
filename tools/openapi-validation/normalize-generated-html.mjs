import { readFile, writeFile } from 'node:fs/promises';

const outputUrl = new URL(
  '../../docs/architecture/api/reference/index.html',
  import.meta.url,
);
const html = await readFile(outputUrl, 'utf8');
const normalizedHtml = `${html
  .split(/\r?\n/u)
  .map((line) => line.trimEnd())
  .join('\n')
  .replace(/\n*$/u, '')}\n`;

await writeFile(outputUrl, normalizedHtml, 'utf8');
