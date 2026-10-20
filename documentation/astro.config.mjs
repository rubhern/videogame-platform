import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import { prepare } from './scripts/prepare.mjs';
import { sources, repo } from './scripts/content.mjs';
import { sidebar } from './content-map.mjs';

export default defineConfig({
  output: 'static',
  server: { host: '127.0.0.1' },
  integrations: [
    { name:'canonical-repository-content', hooks:{
      'astro:config:setup': async ({command}) => { if (command !== 'preview') await prepare({clean:command === 'build'}); },
      'astro:server:setup': ({server}) => {
        server.watcher.add([`${repo}/docs`,`${repo}/README.md`,`${repo}/backend/README.md`,`${repo}/frontend/README.md`,`${repo}/backend/postman`,`${repo}/deploy/private-dev/README.md`,`${repo}/deploy/public-production/README.md`,`${repo}/documentation/README.md`,`${repo}/tools/igdb-poc/README.md`]);
        let pending = Promise.resolve();
        server.watcher.on('all', (event,file) => {
          const canonical = file.startsWith(`${repo}/docs/`) || sources().some(source => `${repo}/${source}` === file);
          if (!canonical || !/\.(?:md|mmd|dsl)$/.test(file)) return;
          if (!['change','add','unlink'].includes(event)) return;
          pending = pending.then(()=>prepare()).then(()=>server.ws.send({type:'full-reload'})).catch(error=>server.config.logger.error(error.stack));
        });
      },
    }},
    starlight({
      title:'Gameómetro · Docs',
      description:'Product, architecture and operations knowledge for Gameómetro.',
      sidebar: sidebar(sources()),
      customCss:['./src/styles/portal.css'],
      components:{PageTitle:'./src/components/PageTitle.astro',MarkdownContent:'./src/components/MarkdownContent.astro'},
      head:[{tag:'meta',attrs:{name:'robots',content:'noindex, nofollow'}}],
      social:[{icon:'github',label:'Source repository',href:'https://github.com/rubhern/videogame-platform'}],
      pagination:false,
    }),
  ],
});
