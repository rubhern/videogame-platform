// Canonical Markdown -> a deliberately small, safe text model. No HTML execution or runtime fetch.
import { readFileSync, writeFileSync } from 'node:fs';
function emit(relative, value) {
  const destination = new URL(relative, import.meta.url);
  const content = JSON.stringify(value, null, 2) + '\n';
  if (process.argv.includes('--check')) {
    if (readFileSync(destination, 'utf8') !== content) throw new Error('Regenerate canonical legal copy: '+relative);
  } else writeFileSync(destination, content);
}
const source = readFileSync(new URL('../docs/legal/public-texts-es.md', import.meta.url), 'utf8');
const sections = {
  privacidad: 'Política de privacidad', condiciones: 'Condiciones de uso',
  'aviso-legal': 'Aviso legal', cookies: 'Cookies y almacenamiento del navegador',
};
function blocks(text) {
  const result = [];
  for (const part of text.trim().split(/\n\s*\n/)) {
    if (part.startsWith('**Texto aprobado por el titular;')) continue;
    if (part.startsWith('### ')) result.push({kind:'heading',text:part.slice(4).trim()});
    else if (part.startsWith('|')) {
      const rows = part.split('\n').filter(line=>!/^\|[-| ]+\|$/.test(line))
        .map(line=>line.split('|').slice(1,-1).map(cell=>cell.trim()));
      result.push({kind:'table',rows});
    } else result.push({kind:'paragraph',text:part.replaceAll('\n',' ')});
  }
  return result;
}
const documents = Object.fromEntries(Object.entries(sections).map(([route,title])=>{
  const marker='## '+title+'\n';
  if (!source.includes(marker)) throw new Error('Missing canonical legal section: '+title);
  const text=source.split(marker)[1].split('\n## ')[0];
  if (/\[(?:nombre|identidad|por aprobar)/i.test(text)) throw new Error('Unresolved public identity');
  return [route,{title,blocks:blocks(text)}];
}));
const output = {documents};
emit('../frontend/src/shared/legal/public-texts.json',output);
// Only approved registration copy enters realm localization; no editorial metadata.
const basic=source.split('## Información básica antes de crear una cuenta\n')[1].split('\n## ')[0]
  .replace(/\*\*Texto aprobado por el titular;[^\n]+\n/,'').trim();
const escape = text => text.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const basicHtml=basic.split(/\n\s*\n/).map(text=>'<p>'+escape(text.replaceAll('\n',' ')).replace(/\[([^\]]+)\]\((mailto:[^)]+|https:\/\/[^)]+)\)/g,'<a href="$2">$1</a>')+'</p>').join('');
for (const [relative, origin] of [
  ['../deploy/public-production/keycloak/import/gameometro-realm.json', 'https://${APPLICATION_PUBLIC_HOST}'],
  ['../docker/keycloak/import/videogame-platform-realm.json', '${APPLICATION_PUBLIC_ORIGIN}'],
]) {
  const realm = JSON.parse(readFileSync(new URL(relative, import.meta.url), 'utf8'));
  realm.localizationTexts ??= {};
  realm.localizationTexts.es = {
    ...realm.localizationTexts.es,
    termsTitle: 'Antes de crear tu cuenta',
    termsText: basicHtml + '<p>Consulta la <a href="' + origin + '/privacidad">política de privacidad</a> y las <a href="' + origin + '/condiciones">condiciones de uso</a> antes de registrarte.</p>',
    acceptTerms: 'Declaro que tengo 18 años o más y acepto las condiciones de uso de Gameómetro.',
    termsAcceptanceRequired: 'Para crear tu cuenta, confirma que tienes 18 años o más y aceptas las condiciones de uso.',
  };
  emit(relative, realm);
}
