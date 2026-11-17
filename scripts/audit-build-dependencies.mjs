import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

export const waiver = {
  advisory: 'https://github.com/advisories/GHSA-fc5q-39xc-vjpf',
  approvedAt: '2026-11-17T18:07:32Z',
  expiresAt: '2026-11-24T18:07:32Z',
};

export function verifyAudit(report, now = new Date()) {
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())
      || now < new Date(waiver.approvedAt) || now >= new Date(waiver.expiresAt)) {
    throw new Error('Build dependency audit exception is outside its approved seven-day window.');
  }
  if (report?.auditReportVersion !== 2 || report.error
      || !report.vulnerabilities || !report.metadata?.vulnerabilities) {
    throw new Error('Missing or invalid npm audit evidence.');
  }
  const vulnerabilities = report.vulnerabilities;
  for (const severity of ['high', 'critical']) {
    const count = Object.values(vulnerabilities).filter(item => item.severity === severity).length;
    if (report.metadata.vulnerabilities[severity] !== count) {
      throw new Error('Incomplete npm audit vulnerability evidence.');
    }
  }
  const accepted = new Set(Object.entries(vulnerabilities)
    .filter(([, finding]) => ['info', 'low', 'moderate'].includes(finding.severity))
    .map(([name]) => name));
  // Resolve inherited findings only after every underlying finding was accepted.
  // Unknown references, cycles and new advisories therefore remain blocked.
  for (let pass = 0; pass < Object.keys(vulnerabilities).length; pass++) {
    for (const [name, finding] of Object.entries(vulnerabilities)) {
      if (!Array.isArray(finding.via) || finding.via.length === 0) continue;
      const covered = finding.via.every(via => typeof via === 'string'
        ? accepted.has(via)
        : name === 'picomatch' && via.name === 'picomatch'
          && via.url === waiver.advisory && via.severity === 'high'
          && via.range === '<=4.0.7');
      if (covered && finding.severity !== 'critical') accepted.add(name);
    }
  }
  const blocked = Object.entries(vulnerabilities)
    .filter(([name, finding]) => !['info', 'low', 'moderate'].includes(finding.severity)
      && !accepted.has(name))
    .map(([name]) => name);
  if (blocked.length) throw new Error('Unaccepted build dependency security findings: ' + blocked.join(', '));
  if (report.metadata.vulnerabilities.critical > 0) {
    throw new Error('Critical build dependency security findings cannot be waived.');
  }
  return Object.entries(vulnerabilities)
    .filter(([name, finding]) => finding.severity === 'high' && accepted.has(name)).length;
}

export function auditBuild(scope) {
  const root = fileURLToPath(new URL('..', import.meta.url));
  if (!['portal', 'frontend'].includes(scope)) throw new Error('Select --portal or --frontend.');
  const portal = scope === 'portal' ? path.join(root, 'documentation') : root;
  if (scope === 'frontend') {
    // The exception covers build tooling only. Production dependencies retain the direct audit.
    const production = spawnSync('npm', ['audit', '--omit=dev', '--audit-level=high'],
      { cwd: root, stdio: 'inherit', timeout: 120_000 });
    if (production.error || production.status !== 0) {
      throw new Error('Production dependency audit failed.');
    }
  }
  const result = spawnSync('npm', ['audit', '--json', '--audit-level=high'],
    { cwd: portal, encoding: 'utf8', timeout: 120_000, maxBuffer: 8 * 1024 * 1024 });
  if (result.error || ![0, 1].includes(result.status)) {
    throw new Error('Build dependency npm audit failed to return usable evidence.');
  }
  const accepted = verifyAudit(JSON.parse(result.stdout));
  if (accepted) {
    const latest = spawnSync('npm', ['view', 'picomatch', 'version', '--json'],
      { cwd: portal, encoding: 'utf8', timeout: 60_000, maxBuffer: 64 * 1024 });
    if (latest.error || latest.status !== 0) {
      throw new Error('Could not check whether a newer picomatch release is available.');
    }
    const version = JSON.parse(latest.stdout);
    const parts = typeof version === 'string' && version.match(/^(\d+)\.(\d+)\.(\d+)$/);
    if (!parts || Number(parts[1]) > 4
        || (Number(parts[1]) === 4 && (Number(parts[2]) > 0 || Number(parts[3]) > 7))) {
      throw new Error('A newer picomatch release requires review. Update the lockfile and remove the exception.');
    }
  }
  console.log('Build dependency audit passed. Temporarily accepted inherited findings: ' + accepted
    + '. Exception expires ' + waiver.expiresAt + '.');
  if (!accepted) console.log('Remove the obsolete build dependency audit exception and restore direct npm audit.');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { auditBuild(process.argv[2]?.replace(/^--/, '')); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
