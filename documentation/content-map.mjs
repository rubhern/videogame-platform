import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const root = new URL('../',import.meta.url);
// Curated reader journeys. Source paths remain repository paths, never editable copies.
export const groups = [
  ['Start here', [
    ['README.md', 'Project overview'], ['docs/README.md', 'Documentation map'],
    ['docs/development/local-setup.md', 'Local setup'],
    ['backend/README.md', 'Backend quick start'], ['frontend/README.md', 'Frontend quick start'],
    ['documentation/README.md', 'Using this portal'],
  ]],
  ['Product & direction', [
    ['docs/product/product-brief.md', 'Product brief & future direction'],
    ['docs/product/mvp-story-map.md', 'Accepted MVP story map'],
    ['docs/product/assumptions-and-decisions.md', 'Assumptions & decisions'],
    ['docs/product/glossary.md', 'Glossary'],
  ]],
  ['Architecture & contracts', [
    ['docs/architecture/mvp-solution-architecture.md', 'Solution architecture'],
    ['docs/architecture/diagrams/README.md', 'Visual architecture'],
    ['docs/architecture/domain/mvp-domain-model.md', 'Domain model'],
    ['docs/architecture/application/mvp-use-cases.md', 'Application use cases'],
    ['docs/architecture/api/api-conventions.md', 'API conventions'],
    ['docs/development/openapi.md', 'OpenAPI workflow & contract'],
    ['backend/postman/README.md', 'API requests & Postman'],
    ['backend/postman/igdb-exploration.md', 'IGDB integration exploration'],
    ['docs/architecture/technology/mvp-technology-baseline.md', 'Technology baseline'],
  ]],
  ['Decisions', [
    ['docs/decisions/README.md', 'Decision register'],
    ['docs/decisions/0032-checkpoint-historical-synchronization-by-month.md', 'Historical month checkpoints · proposed'],
  ]],
  ['Development & quality', [
    ['docs/development/delivery-lifecycle.md', 'Delivery & validation'],
    ['docs/development/work-management.md', 'Issues & Project workflow'],
    ['docs/development/continuous-integration.md', 'Continuous integration'],
    ['docs/development/database-migrations.md', 'Database migrations'],
    ['docs/development/frontend-design.md', 'Frontend design'],
    ['docs/development/ai-assistance.md', 'AI assistance'],
  ]],
  ['Platform & operations', [
    ['docs/architecture/deployment/mvp-platform-and-delivery.md', 'Private platform design'],
    ['deploy/private-dev/README.md', 'Private-dev operator guide'],
    ['deploy/public-production/README.md', 'Public-production runtime · prepared'],
    ['docs/development/observability.md', 'Observability'],
    ['docs/development/operations-runbook.md', 'Operations & acceptance evidence'],
    ['docs/development/data-protection.md', 'Data protection & rights procedures'],
    ['docs/legal/public-texts-es.md', 'Spanish legal texts · draft for review'],
  ]],
  ['Research & history', [
    ['docs/research/README.md', 'Research index & limitations'],
    ['docs/research/browser-session-lifetime.md', 'Browser session lifetime · #361'],
    ['docs/research/historical-sync-pilot.md', 'Historical sync pilot · #246'],
    ['docs/research/reviews/user-facing-copy-audit-2026-10-19.md', 'User-facing copy audit · #286'],
    ['tools/igdb-poc/README.md', 'IGDB proof of concept'],
  ]],
];
export function routeFor(source) {
  if (source === 'README.md') return '/overview/';
  return '/' + source.replace(/\/README\.md$/, '').replace(/\.md$/, '').toLowerCase() + '/';
}
export const labels = new Map(groups.flatMap(([, entries]) => entries));
export function groupFor(source) {
  return groups.find(([, entries]) => entries.some(([path]) => path === source))?.[0]
    ?? (source.startsWith('docs/decisions/') ? 'Decisions' : 'Research & history');
}
export function sidebar(sources) {
  return [ { label: 'Knowledge base', link: '/' }, ...groups.map(([label, entries]) => ({
    label, collapsed: !['Start here', 'Architecture & contracts'].includes(label),
    items: [...entries.map(([path, title]) => ({label: title, link: routeFor(path)})),
      ...sources.filter(source => !labels.has(source) && groupFor(source) === label)
        .map(source => ({label: readFileSync(fileURLToPath(new URL(source,root)),'utf8').match(/^# (.+)$/m)?.[1] || source, link: routeFor(source)}))],
  })) ];
}
