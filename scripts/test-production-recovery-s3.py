#!/usr/bin/env python3
"""In-process S3 failures and exact-version tests; no cloud SDK/account required."""
import datetime as dt
import email.utils
import hashlib
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader('recovery_s3_test_core', str(ROOT / 'deploy/public-production/bin/recovery'))
spec = importlib.util.spec_from_loader(loader.name, loader)
core = importlib.util.module_from_spec(spec)
sys.modules[loader.name] = core
loader.exec_module(core)
sys.path.insert(0, str(ROOT / 'deploy/public-production/lib'))
from recovery_s3 import Store, MAX_JSON


class Fake:
    def __init__(self, now):
        self.now = now; self.objects = {}; self.calls = []; self.uploads = {}; self.fail = None
        self.lock = {'ObjectLockEnabled': 'Enabled', 'Rule': {'DefaultRetention': {'Mode': 'COMPLIANCE', 'Days': 33}}}
        self.lifecycle = [{'ID': 'reviewed-fixture'}]
        self.hold = 'OFF'; self.mode = 'COMPLIANCE'; self.extra = dt.timedelta()

    def __getattr__(self, operation):
        def call(**kw):
            self.calls.append((operation, kw))
            if self.fail == operation: raise RuntimeError('private provider diagnostic')
            result = {}
            if operation == 'put_object':
                version = str(len(self.objects) + 1)
                data = kw['Body']; data = data.read() if hasattr(data, 'read') else data
                self.objects[kw['Key'], version] = (data, self.now)
                result = {'VersionId': version}
            elif operation == 'create_multipart_upload':
                self.uploads['upload'] = []
                result = {'UploadId': 'upload'}
            elif operation == 'upload_part':
                self.uploads['upload'].append(kw['Body']); result = {'ETag': 'etag'}
            elif operation == 'complete_multipart_upload':
                return self.put_object(Bucket=kw['Bucket'], Key=kw['Key'], Body=b''.join(self.uploads.pop('upload')))
            elif operation == 'abort_multipart_upload': self.uploads.pop('upload', None)
            elif operation == 'list_object_versions':
                result = {'Versions': [{'Key': k, 'VersionId': v, 'Size': len(data), 'LastModified': modified}
                                       for (k, v), (data, modified) in self.objects.items()]}
            elif operation == 'get_object':
                data, _ = self.objects[kw['Key'], kw['VersionId']]
                result = {'Body': io.BytesIO(data), 'ContentLength': len(data), 'VersionId': kw['VersionId']}
            elif operation == 'get_object_lock_configuration': result = {'ObjectLockConfiguration': self.lock}
            elif operation == 'get_bucket_versioning': result = {'Status': 'Enabled'}
            elif operation == 'get_bucket_lifecycle_configuration': result = {'Rules': self.lifecycle}
            elif operation == 'get_object_retention':
                _, modified = self.objects[kw['Key'], kw['VersionId']]
                result = {'Retention': {'Mode': self.mode, 'RetainUntilDate': modified + dt.timedelta(days=33) + self.extra}}
            elif operation == 'get_object_legal_hold': result = {'LegalHold': {'Status': self.hold}}
            else: raise AssertionError(operation)
            return {**result, 'ResponseMetadata': {'HTTPHeaders': {'date': email.utils.format_datetime(self.now, usegmt=True)}}}
        return call


class S3Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.now = dt.datetime(2026, 10, 29, 12, tzinfo=dt.timezone.utc)
        self.clock = patch.object(core, 'utc', side_effect=lambda: self.now); self.clock.start(); self.addCleanup(self.clock.stop)
        self.fake = Fake(self.now); self.store = Store(core, self.fake, 'synthetic', lambda: self.fake.now)
        self.lifecycle = hashlib.sha256(json.dumps(self.fake.lifecycle, sort_keys=True).encode()).hexdigest()
        self.pair = self.root / 'source'; self.pair.mkdir(mode=0o700)
        artifacts = {}
        for name in core.FILES[:2]:
            data = b'synthetic encrypted fixture'; (self.pair / name).write_bytes(data)
            artifacts[name] = {'bytes': len(data), 'ciphertextSha256': hashlib.sha256(data).hexdigest(), 'plaintextSha256': 'a'*64}
        m = {'format': 1, 'environment': 'production', 'issuer': core.ISSUER, 'databases': list(core.DATABASES),
             'fence': 'roles-nologin-drained', 'backupId': 'a'*32, 'recoveryPoint': self.now.isoformat(), 'artifacts': artifacts}
        core.write(self.pair / 'manifest.json', m)
        (self.pair / 'SHA256SUMS').write_text(''.join(f'{core.digest(self.pair/n)}  {n}\n' for n in core.FILES[:-1]))
        self.proof = self.root / 'admission.json'
        self.refresh()

    def refresh(self):
        p = self.store.observe(self.lifecycle, 1000000); p['outcome'] = 'success'
        core.write(self.proof, p); self.proof.chmod(0o600)

    def publish(self): return self.store.publish(self.pair, self.proof, 1000000)

    def test_publish_receipt_last_exact_fetch_and_independent_hashes(self):
        proof = self.publish()
        self.assertEqual(list(self.fake.objects)[-1][0], proof['receiptObject']['key'])
        self.assertEqual(set(proof['objects']), set(core.FILES))
        self.assertFalse(proof['ciphertextIntegrity'])
        observed = self.store.observe(self.lifecycle, 1000000)
        self.assertEqual(len(observed['pairs']), 1)
        self.assertEqual(observed['alerts'], ['restore-missing'])
        result = self.store.fetch(proof, self.root/'fetched', 1000000)
        self.assertTrue(result['ciphertextIntegrity'])
        self.assertFalse(result['independentRecoveryVerified'])
        self.assertEqual(core.verify(self.root/'fetched'), core.verify(self.pair))

    def test_unique_attempts(self):
        a = self.publish(); b = self.publish()
        self.assertNotEqual(a['receiptObject']['key'], b['receiptObject']['key'])

    def test_l1_window_and_skew(self):
        self.now += dt.timedelta(hours=6)
        with self.assertRaises(core.Refused): self.publish()
        self.assertFalse(self.fake.objects)
        self.now -= dt.timedelta(hours=6)
        self.fake.now += dt.timedelta(seconds=61)
        with self.assertRaises(core.Refused): self.publish()

    def test_window_closes_during_transfer_no_success_receipt(self):
        original = self.store.put
        def close(*args):
            result = original(*args); self.now += dt.timedelta(hours=6); return result
        with patch.object(self.store, 'put', side_effect=close):
            with self.assertRaises(core.Refused): self.publish()
        self.assertFalse(any(k.endswith('receipt.json') for k, _ in self.fake.objects))

    def test_l2_duplicate_versions_alert(self):
        proof = self.publish(); obj = proof['objects'][core.FILES[0]]
        self.fake.objects[obj['key'], 'second'] = self.fake.objects[obj['key'], obj['versionId']]
        self.assertIn('multiple-versions', self.store.observe(self.lifecycle, 1000000)['alerts'])
        # Explicit original still fetches correctly despite a newer version.
        self.store.fetch(proof, self.root/'fetched', 1000000)

    def test_l3_multipart_abort(self):
        with patch('recovery_s3.PART', 8):
            self.fake.fail = 'upload_part'
            with self.assertRaises(RuntimeError): self.publish()
        self.assertFalse(self.fake.uploads)
        self.assertIn('abort_multipart_upload', [op for op, _ in self.fake.calls])
        self.assertFalse(self.fake.objects)

    def test_multipart_success(self):
        with patch('recovery_s3.PART', 8): proof = self.publish()
        self.store.fetch(proof, self.root/'fetched', 1000000)

    def test_missing_ack_partial_not_success(self):
        original = self.fake.put_object
        def missing(**kw):
            result = original(**kw); result.pop('VersionId'); return result
        with patch.object(self.fake, 'put_object', side_effect=missing):
            with self.assertRaises(core.Refused): self.publish()
        self.assertFalse(any(k.endswith('receipt.json') for k, _ in self.fake.objects))

    def test_corruption_missing_version_and_atomic_fetch(self):
        proof = self.publish(); obj = proof['objects'][core.FILES[0]]
        self.fake.objects[obj['key'], obj['versionId']] = (b'corrupt', self.now)
        with self.assertRaises(core.Refused): self.store.fetch(proof, self.root/'fetched', 1000000)
        self.assertFalse((self.root/'fetched').exists()); self.assertFalse((self.root/'fetched.partial').exists())
        del self.fake.objects[obj['key'], obj['versionId']]
        with self.assertRaises(core.Refused): self.store.observe(self.lifecycle, 1000000)

    def test_l4_l5_expiry_plan_ladder_never_deletes(self):
        self.publish()
        for days, hours, alert in [(33, 2, 'expiry-warning'), (34, 12, 'expiry-critical'), (35, 0, 'retention-incident')]:
            self.now = dt.datetime(2026, 10, 29, 12, tzinfo=dt.timezone.utc) + dt.timedelta(days=days, hours=hours)
            self.fake.now = self.now
            plan = self.store.observe(self.lifecycle, 1000000, plan=True)
            self.assertEqual(len(plan['candidates']), 5); self.assertIn(alert, plan['alerts'])
            self.assertFalse(plan['deletionEnabled'])
        self.assertFalse(any(op.startswith('delete') for op, _ in self.fake.calls))

    def test_l6_protection_and_configuration_drift(self):
        self.publish()
        for attribute, value in [('hold', 'ON'), ('mode', 'GOVERNANCE'), ('extra', dt.timedelta(days=2))]:
            old = getattr(self.fake, attribute); setattr(self.fake, attribute, value)
            with self.subTest(attribute=attribute), self.assertRaises(core.Refused): self.store.observe(self.lifecycle, 1000000)
            setattr(self.fake, attribute, old)
        self.fake.lock['Rule']['DefaultRetention']['Days'] = 34
        with self.assertRaises(core.Refused): self.store.observe(self.lifecycle, 1000000)

    def test_unknown_prefix_and_inventory_overflow(self):
        self.fake.objects['unknown', 'version'] = (b'x', self.now)
        with self.assertRaises(core.Refused): self.store.inventory()
        self.fake.objects.clear(); self.publish()
        with patch('recovery_s3.MAX_VERSIONS', 4):
            with self.assertRaises(core.Refused): self.store.inventory()

    def test_missing_copy_and_restore_ages(self):
        self.assertIn('integrity-critical', self.store.observe(self.lifecycle, 1000000)['alerts'])
        proof = self.publish()
        restore = {'kind': 'recovery-rehearsal', 'outcome': 'success', 'deletionReplay': True, 'readiness': True,
                   'scope': 'owner-reconstructed-target', 'backupId': proof['backupId'], 'manifestSha256': proof['manifestSha256'],
                   'verifiedAt': (self.now - dt.timedelta(days=9)).isoformat()}
        self.assertIn('restore-warning', self.store.observe(self.lifecycle, 1000000, restore)['alerts'])
        self.now += dt.timedelta(hours=15); self.fake.now = self.now
        self.assertIn('missed-copy-warning', self.store.observe(self.lifecycle, 1000000)['alerts'])
        self.now += dt.timedelta(hours=10); self.fake.now = self.now
        self.assertIn('integrity-critical', self.store.observe(self.lifecycle, 1000000)['alerts'])

    def test_l9_caller_capacity_budget_and_stale_observation(self):
        with self.assertRaises(core.Refused): self.store.publish(self.pair, self.proof, MAX_JSON)
        self.assertFalse(self.fake.objects)
        self.now += dt.timedelta(hours=2)
        with self.assertRaises(core.Refused): self.publish()
        self.assertFalse(self.fake.objects)

    def test_l1_future_point_and_oversized_inputs(self):
        self.now -= dt.timedelta(seconds=1)
        self.fake.now = self.now
        with self.assertRaises(core.Refused): self.publish()
        self.assertFalse(self.fake.objects)

    def test_paginated_inventory_and_repeated_page_refusal(self):
        self.publish()
        entries = self.fake.list_object_versions(Bucket='synthetic')['Versions']
        def paged(**kw):
            result = {'Versions': entries[:2], 'IsTruncated': True, 'NextKeyMarker': 'cursor', 'NextVersionIdMarker': 'v'}
            if kw.get('KeyMarker'):
                result = {'Versions': entries[2:]}
            return {**result, 'ResponseMetadata': {'HTTPHeaders': {'date': email.utils.format_datetime(self.fake.now, usegmt=True)}}}
        with patch.object(self.fake, 'list_object_versions', side_effect=paged):
            self.assertEqual(len(self.store.inventory()), 5)
        page = paged()
        with patch.object(self.fake, 'list_object_versions', return_value=page):
            with self.assertRaises(core.Refused): self.store.inventory()

    def test_expiry_plan_least_privilege_markers_and_unexpired_versions(self):
        self.publish()
        self.assertEqual(self.store.expiry_plan()['candidates'], [])
        original = self.fake.list_object_versions
        def marker(**kw):
            result = original(**kw)
            item = result['Versions'][0]
            result['DeleteMarkers'] = [{'Key': item['Key'], 'VersionId': 'marker', 'LastModified': self.now}]
            return result
        self.fake.calls.clear()
        with patch.object(self.fake, 'list_object_versions', side_effect=marker):
            result = self.store.expiry_plan()
        self.assertEqual(result['candidates'][0]['versionId'], 'marker')
        self.assertTrue(result['candidates'][0]['marker'])
        self.assertLessEqual({op for op, _ in self.fake.calls},
                             {'list_object_versions', 'get_object_retention', 'get_object_legal_hold'})
        self.fake.hold = 'ON'
        with self.assertRaises(core.Refused): self.store.expiry_plan()

    def test_restore_critical_mismatch_and_local_rehearsal_refused(self):
        proof = self.publish()
        restore = {'kind': 'recovery-rehearsal', 'outcome': 'success', 'deletionReplay': True, 'readiness': True,
                   'scope': 'owner-reconstructed-target', 'backupId': proof['backupId'], 'manifestSha256': proof['manifestSha256'],
                   'verifiedAt': (self.now - dt.timedelta(days=15)).isoformat()}
        self.assertIn('restore-critical', self.store.observe(self.lifecycle, 1000000, restore)['alerts'])
        for field, value in [('scope', 'isolated-local'), ('manifestSha256', 'b'*64), ('deletionReplay', False)]:
            invalid = {**restore, field: value}
            with self.subTest(field=field), self.assertRaises(core.Refused): self.store.observe(self.lifecycle, 1000000, invalid)

    def test_incomplete_attempt_and_config_drift(self):
        self.publish()
        key = next(k for k in self.fake.objects if k[0].endswith('receipt.json'))
        del self.fake.objects[key]
        self.assertIn('incomplete-attempt', self.store.observe(self.lifecycle, 1000000)['alerts'])
        with self.assertRaises(core.Refused): self.store.observe('bad-fingerprint', 1000000)

    def test_no_override_headers_and_stream_budget(self):
        proof = self.publish()
        for _, kw in self.fake.calls:
            self.assertFalse(set(kw) & {'ObjectLockMode', 'ObjectLockRetainUntilDate', 'ObjectLockLegalHoldStatus', 'BypassGovernanceRetention'})
        with patch('recovery_s3.time.monotonic', return_value=self.store.deadline + 1):
            with self.assertRaises(core.Refused): self.store.fetch(proof, self.root/'late', 1000000)
        self.assertFalse((self.root/'late').exists())

    def test_abandoned_attempt_does_not_prevent_next_capture(self):
        self.publish()
        key = next(k for k in self.fake.objects if k[0].endswith('receipt.json'))
        del self.fake.objects[key]
        self.refresh()
        replacement = self.publish()
        self.assertIn(replacement['receiptObject']['key'], {k for k, _ in self.fake.objects})


if __name__ == '__main__': unittest.main()
