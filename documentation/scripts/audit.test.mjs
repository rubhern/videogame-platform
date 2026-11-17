import test from 'node:test';
import assert from 'node:assert/strict';
import { verifyAudit, waiver } from '../../scripts/audit-build-dependencies.mjs';

const now = new Date('2026-11-18T00:00:00Z');
function report() {
  return { auditReportVersion: 2, metadata: { vulnerabilities: { critical: 0, high: 2 } },
    vulnerabilities: {
      picomatch: { severity: 'high', via: [{ name: 'picomatch', url: waiver.advisory,
        severity: 'high', range: '<=4.0.7' }] },
      astro: { severity: 'high', via: ['picomatch'] },
    } };
}
test('only the approved advisory and its inherited findings pass', () => {
  assert.equal(verifyAudit(report(), now), 2);
  const data = report();
  data.vulnerabilities.astro.via.push({ name: 'astro', url: 'https://example.invalid/new',
    severity: 'high', range: '*' });
  assert.throws(() => verifyAudit(data, now), /Unaccepted/);
});
test('expiration, missing evidence and registry errors fail closed', () => {
  for (const date of [waiver.expiresAt, '2026-11-25', '2026-11-16', 'invalid']) {
    assert.throws(() => verifyAudit(report(), new Date(date)), /seven-day/);
  }
  for (const data of [{}, { ...report(), error: { code: 'E503' } }]) {
    assert.throws(() => verifyAudit(data, now), /evidence/);
  }
});
test('unknown dependencies, cycles, critical findings and changed advisory scope fail', () => {
  for (const mutate of [
    data => { data.vulnerabilities.astro.via = ['absent']; },
    data => { data.vulnerabilities.astro.via = ['astro']; },
    data => { data.vulnerabilities.picomatch.severity = 'critical'; },
    data => { data.metadata.vulnerabilities.critical = 1; },
    data => { data.vulnerabilities.picomatch.via[0].range = '*'; },
    data => { data.vulnerabilities.picomatch.via[0].url += '-other'; },
  ]) {
    const data = report();
    mutate(data);
    assert.throws(() => verifyAudit(data, now));
  }
});

test('moderate dependencies preserve the high audit threshold without waiving new high advisories', () => {
  const data = report();
  data.vulnerabilities.minimatch = { severity: 'moderate',
    via: [{ name: 'minimatch', url: 'https://example.invalid/moderate', severity: 'moderate' }] };
  data.vulnerabilities.astro.via.push('minimatch');
  assert.equal(verifyAudit(data, now), 2);
  data.vulnerabilities.astro.via.push({ name: 'astro',
    url: 'https://example.invalid/high', severity: 'high' });
  assert.throws(() => verifyAudit(data, now), /Unaccepted/);
});
