#!/usr/bin/env python3
"""In-process S3 failures and exact-version tests; no cloud SDK/account required."""
import datetime as dt
import email.utils
import hashlib
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import types
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
        self.now = now; self.objects = {}; self.markers = {}; self.calls = []; self.uploads = {}; self.fail = None
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
                result = {'Name': kw['Bucket'], 'IsTruncated': False,
                          'Versions': [{'Key': k, 'VersionId': v, 'Size': len(data), 'LastModified': modified}
                                       for (k, v), (data, modified) in self.objects.items()],
                          'DeleteMarkers': [{'Key': k, 'VersionId': v, 'LastModified': modified}
                                            for (k, v), modified in self.markers.items()]}
            elif operation == 'delete_object':
                assert set(kw) == {'Bucket', 'Key', 'VersionId'}
                identity = kw['Key'], kw['VersionId']
                marker = identity in self.markers
                if marker:
                    del self.markers[identity]
                else:
                    _, modified = self.objects[identity]
                    assert self.mode == 'COMPLIANCE' and self.hold == 'OFF'
                    assert self.now >= modified + dt.timedelta(days=33) + self.extra
                    del self.objects[identity]
                result = {'VersionId': kw['VersionId'], 'DeleteMarker': marker}
            elif operation == 'get_object':
                data, _ = self.objects[kw['Key'], kw['VersionId']]
                result = {'Body': io.BytesIO(data), 'ContentLength': len(data), 'VersionId': kw['VersionId']}
            elif operation == 'get_object_lock_configuration': result = {'ObjectLockConfiguration': self.lock}
            elif operation == 'get_bucket_versioning': result = {'Status': 'Enabled'}
            elif operation == 'get_bucket_lifecycle_configuration': result = {'Rules': self.lifecycle}
            elif operation == 'get_object_retention':
                _, modified = self.objects[kw['Key'], kw['VersionId']]
                result = {'Retention': {'Mode': self.mode, 'RetainUntilDate': modified + dt.timedelta(days=33) + self.extra}}
            elif operation == 'get_object_legal_hold':
                if self.hold is None:  # OVHcloud: a version that never had a legal hold.
                    error = RuntimeError('NoSuchObjectLockConfiguration')
                    error.response = {'Error': {'Code': 'NoSuchObjectLockConfiguration'}}
                    raise error
                result = {'LegalHold': {'Status': self.hold}}
            else: raise AssertionError(operation)
            return {**result, 'ResponseMetadata': {'HTTPStatusCode': 204 if operation == 'delete_object' else 200,
                                                  'HTTPHeaders': {'date': email.utils.format_datetime(self.now, usegmt=True)}}}
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

    def age(self, days=34):
        self.now += dt.timedelta(days=days)
        self.fake.now = self.now

    def expire(self, lifecycle=None):
        self.expiry_receipt = {}
        self.checkpoints = []
        def checkpoint():
            self.checkpoints.append(json.loads(json.dumps(self.expiry_receipt)))
        return self.store.expire(self.lifecycle if lifecycle is None else lifecycle, self.expiry_receipt, checkpoint)

    def deletes(self):
        return [kw for op, kw in self.fake.calls if op == 'delete_object']

    def cli(self, *options, config_changes=None, duplicate=False):
        config = self.root / 's3.json'
        fields = {'endpoint': 'https://s3.gra.io.cloud.ovh.net', 'region': 'gra', 'bucket': 'synthetic',
                  'accessKey': 'synthetic', 'secretKey': 'private-test-secret', 'lifecycleSha256': self.lifecycle}
        core.write(config, {**fields, **(config_changes or {})})
        if duplicate:
            config.write_text(config.read_text().replace('{', '{"region": "gra",', 1))
        config.chmod(0o600)
        receipt = self.root / 'cli-receipt.json'
        modules = {'boto3': types.SimpleNamespace(client=lambda *a, **kw: self.fake),
                   'botocore.config': types.SimpleNamespace(Config=lambda **kw: kw)}
        handlers = {sig: core.signal.getsignal(sig) for sig in (core.signal.SIGALRM, core.signal.SIGINT, core.signal.SIGTERM)}
        mask = os.umask(0o077)
        try:
            with patch.dict(sys.modules, modules), patch.object(sys, 'argv',
                    ['recovery', 'expire', '--s3-config', str(config), '--receipt', str(receipt), *options]):
                code = core.main()
        finally:
            core.signal.alarm(0)
            for sig, handler in handlers.items(): core.signal.signal(sig, handler)
            os.umask(mask)
        return code, json.loads(receipt.read_text())

    def test_expiry_execute_versions_markers_orphans_and_idempotency(self):
        self.publish()
        key, version = next(iter(self.fake.objects))
        self.fake.objects[key, 'noncurrent'] = self.fake.objects[key, version]
        del self.fake.objects[next(k for k in self.fake.objects if k[0].endswith('receipt.json'))]
        self.fake.markers[key, 'marker'] = self.now
        self.age()
        expected = set(self.fake.objects) | set(self.fake.markers)
        result = self.expire()
        self.assertEqual(result['outcome'], 'success')
        self.assertTrue(result['absenceVerified'])
        self.assertEqual({(a['key'], a['versionId']) for a in result['attempts']}, expected)
        self.assertTrue(all(a['state'] == 'absence-verified' for a in result['attempts']))
        self.assertFalse(self.fake.objects or self.fake.markers)
        self.assertTrue(all(set(kw) == {'Bucket', 'Key', 'VersionId'} for kw in self.deletes()))
        # Every attempted deletion has a durable unconfirmed intent first.
        for index in range(len(expected)):
            self.assertTrue(any(len(c['attempts']) == index + 1
                                and c['attempts'][-1]['state'] == 'unconfirmed' for c in self.checkpoints))
        count = len(self.deletes())
        self.assertEqual(self.expire()['attempts'], [])
        self.assertEqual(len(self.deletes()), count)

    def test_expiry_preserves_retained_versions_and_exact_boundary(self):
        self.publish()
        self.assertEqual(self.expire()['attempts'], [])
        self.assertFalse(self.deletes())
        self.age(33)
        self.assertEqual(len(self.expire()['attempts']), 5)

    def test_expiry_hold_anywhere_refuses_before_deletes(self):
        self.publish(); self.age(); self.fake.hold = 'ON'
        with self.assertRaises(core.Refused): self.expire()
        self.assertFalse(self.deletes())
        self.assertEqual(self.expiry_receipt['outcome'], 'failure')

    def test_expiry_rechecks_hold_and_retention_after_plan(self):
        self.publish(); self.age()
        original = self.store.expiry_plan
        for attribute, value in [('hold', 'ON'), ('extra', dt.timedelta(hours=1))]:
            def changed():
                plan = original(); setattr(self.fake, attribute, value); return plan
            with self.subTest(attribute=attribute), patch.object(self.store, 'expiry_plan', side_effect=changed):
                with self.assertRaises(core.Refused): self.expire()
            setattr(self.fake, attribute, 'OFF' if attribute == 'hold' else dt.timedelta())
        self.assertFalse(self.deletes())

    def test_expiry_configuration_and_provider_preflight_failures(self):
        self.publish(); self.age()
        for operation in ('get_bucket_versioning', 'get_object_lock_configuration', 'get_bucket_lifecycle_configuration',
                          'list_object_versions', 'get_object_retention', 'get_object_legal_hold'):
            self.fake.fail = operation
            with self.subTest(operation=operation), self.assertRaises(RuntimeError): self.expire()
            self.assertFalse(self.deletes())
        self.fake.fail = None
        for fingerprint in ('invalid', '0'*64):
            with self.subTest(fingerprint=fingerprint), self.assertRaises(core.Refused): self.expire(fingerprint)
        self.fake.lock['Rule']['DefaultRetention']['Days'] = 34
        with self.assertRaises(core.Refused): self.expire()
        self.assertFalse(self.deletes())

    def test_expiry_unknown_and_incomplete_inventories_never_delete(self):
        self.publish(); self.age()
        original = self.fake.list_object_versions
        for change in ({'IsTruncated': None}, {'IsTruncated': True}, {'CommonPrefixes': [{'Prefix': 'hidden/'}]},
                       {'Name': 'other'}, {'Prefix': 'pairs/'}, {'NextKeyMarker': 'unused', 'IsTruncated': True},
                       {'Versions': None}, {'NextKeyMarker': 'contradictory-end'}):
            with self.subTest(change=change), patch.object(self.fake, 'list_object_versions',
                    side_effect=lambda **kw: {**original(**kw), **change}):
                with self.assertRaises((core.Refused, KeyError)): self.expire()
            self.assertFalse(self.deletes())
        self.fake.objects['unknown/version', 'v'] = (b'x', self.now)
        with self.assertRaises(core.Refused): self.expire()
        self.assertFalse(self.deletes())

    def test_cli_rejects_ambiguous_or_inconsistent_configuration(self):
        self.publish(); self.age()
        for changes, duplicate in (({'region': 'other'}, False), ({'lifecycleSha256': None}, False), ({}, True)):
            with self.subTest(changes=changes, duplicate=duplicate):
                code, receipt = self.cli('--execute', config_changes=changes, duplicate=duplicate)
                self.assertEqual(code, 1)
                self.assertEqual(receipt['outcome'], 'failure')
                self.assertFalse(self.deletes())
                (self.root / 'cli-receipt.json').unlink()

    def test_expiry_preserves_recent_version_while_deleting_expired_pair(self):
        self.publish(); self.age()
        key, _ = next(iter(self.fake.objects))
        recent_key = key.replace('20261029T120000Z', self.now.strftime('%Y%m%dT%H%M%SZ'))
        self.fake.objects[recent_key, 'recent'] = (b'recent encrypted bytes', self.now)
        receipt = self.expire()
        self.assertEqual(len(receipt['attempts']), 5)
        self.assertEqual(set(self.fake.objects), {(recent_key, 'recent')})

    def test_expiry_invalid_version_metadata_refuses_before_any_delete(self):
        self.publish(); self.age()
        original = self.fake.list_object_versions
        for changes in ({'VersionId': 'null'}, {'VersionId': 3}, {'VersionId': 'x' * 1025},
                        {'LastModified': self.now + dt.timedelta(minutes=2)}, {'Size': -1}):
            def invalid(**kw):
                result = original(**kw)
                result['Versions'][-1].update(changes)
                return result
            with self.subTest(changes=changes), patch.object(self.fake, 'list_object_versions', side_effect=invalid):
                with self.assertRaises(core.Refused): self.expire()
            self.assertFalse(self.deletes())

    def test_expiry_overflow_time_and_call_bounds(self):
        self.publish(); self.age()
        with patch('recovery_s3.MAX_VERSIONS', 4):
            with self.assertRaises(core.Refused): self.expire()
        with patch('recovery_s3.MAX_CALLS', self.store.calls):
            with self.assertRaises(core.Refused): self.expire()
        with patch('recovery_s3.time.monotonic', return_value=self.store.deadline):
            with self.assertRaises(core.Refused): self.expire()
        self.assertFalse(self.deletes())

    def test_expiry_delete_failure_or_interruption_keeps_unconfirmed_receipt(self):
        self.publish(); self.age()
        for error in (RuntimeError('private diagnostic'), KeyboardInterrupt(), core.Refused('Interrupted.')):
            with self.subTest(error=type(error).__name__), patch.object(self.fake, 'delete_object', side_effect=error):
                with self.assertRaises(type(error)): self.expire()
            self.assertEqual(self.expiry_receipt['outcome'], 'failure')
            self.assertFalse(self.expiry_receipt['absenceVerified'])
            self.assertEqual(self.checkpoints[-1]['attempts'][0]['state'], 'unconfirmed')

    def test_expiry_lost_ack_after_mutation_retry_uses_fresh_inventory(self):
        self.publish(); self.age()
        original = self.fake.delete_object
        def lost(**kw):
            original(**kw)
            raise RuntimeError('lost acknowledgement')
        with patch.object(self.fake, 'delete_object', side_effect=lost):
            with self.assertRaises(RuntimeError): self.expire()
        self.assertEqual(len(self.fake.objects), 4)
        self.assertFalse(self.expiry_receipt['absenceVerified'])
        self.assertEqual(len(self.expire()['attempts']), 4)
        self.assertFalse(self.fake.objects)

    def test_expiry_acknowledgement_never_substitutes_for_absence(self):
        self.publish(); self.age()
        original = self.fake.delete_object
        def unchanged(**kw):
            identity = kw['Key'], kw['VersionId']; value = self.fake.objects[identity]
            result = original(**kw); self.fake.objects[identity] = value
            return result
        with patch.object(self.fake, 'delete_object', side_effect=unchanged):
            with self.assertRaises(core.Refused): self.expire()
        self.assertFalse(self.expiry_receipt['absenceVerified'])
        self.assertTrue(all(a['state'] == 'unconfirmed' and 'acknowledgedAt' in a for a in self.expiry_receipt['attempts']))

    def test_expiry_bad_ack_status_and_skew_are_failure(self):
        self.publish(); self.age()
        original = self.fake.delete_object
        for change in ({'VersionId': 'wrong'}, {'VersionId': None}, {'DeleteMarker': True},
                       {'ResponseMetadata': {'HTTPStatusCode': 200}}):
            def bad(**kw):
                return {**original(**kw), **change}
            with self.subTest(change=change), patch.object(self.fake, 'delete_object', side_effect=bad):
                with self.assertRaises(core.Refused): self.expire()
            self.assertFalse(self.expiry_receipt['absenceVerified'])
        self.fake.now += dt.timedelta(seconds=61)
        with self.assertRaises(core.Refused): self.expire()

    def test_expiry_predelete_inventory_change_and_verification_failure(self):
        self.publish(); self.age()
        original = self.store.inventory
        count = 0
        def changed():
            nonlocal count
            count += 1
            entries = original()
            return entries[:-1] if count == 2 else entries
        with patch.object(self.store, 'inventory', side_effect=changed):
            with self.assertRaises(core.Refused): self.expire()
        self.assertFalse(self.deletes())
        count = 0
        def unavailable():
            nonlocal count
            count += 1
            if count == 3: raise RuntimeError('provider verification unavailable')
            return original()
        with patch.object(self.store, 'inventory', side_effect=unavailable):
            with self.assertRaises(RuntimeError): self.expire()
        self.assertFalse(self.expiry_receipt['absenceVerified'])
        self.assertEqual(self.expiry_receipt['outcome'], 'failure')

    def test_cli_default_plan_and_explicit_execution(self):
        self.publish(); self.age()
        code, result = self.cli()
        self.assertEqual(code, 2)  # Due versions retain the existing deadline alerts.
        self.assertFalse(result['deletionEnabled'])
        self.assertFalse(self.deletes())
        (self.root / 'cli-receipt.json').unlink()
        code, result = self.cli('--execute')
        self.assertEqual(code, 2)
        self.assertTrue(result['absenceVerified'])
        self.assertEqual(result['outcome'], 'success')
        self.assertEqual(result['adapterVersion'], '0.2.2')
        self.assertFalse(result['productionReady'])

    def test_cli_failed_execution_persists_sanitized_attempt(self):
        self.publish(); self.age(); self.fake.fail = 'delete_object'
        code, result = self.cli('--execute')
        self.assertEqual(code, 1)
        self.assertEqual(result['outcome'], 'failure')
        self.assertEqual(result['attempts'][0]['state'], 'unconfirmed')
        self.assertNotIn('private', json.dumps(result))
        self.assertNotIn('private-test-secret', json.dumps(result))

    def test_cli_receipt_failure_prevents_delete(self):
        self.publish(); self.age()
        original = core.write
        def failed(path, value):
            if value.get('kind') == 's3-expiry' and value.get('attempts'):
                raise OSError('disk failure')
            original(path, value)
        with patch.object(core, 'write', side_effect=failed):
            with self.assertRaises(OSError): self.cli('--execute')
        self.assertFalse(self.deletes())

    def test_cli_termination_after_delete_preserves_intent_and_retry(self):
        self.publish(); self.age()
        original = self.fake.delete_object
        def terminated(**kw):
            original(**kw)
            core.signal.raise_signal(core.signal.SIGTERM)
        with patch.object(self.fake, 'delete_object', side_effect=terminated):
            code, receipt = self.cli('--execute')
        self.assertEqual(code, 1)
        self.assertEqual(receipt['outcome'], 'failure')
        self.assertFalse(receipt['absenceVerified'])
        self.assertEqual(receipt['attempts'][0]['state'], 'unconfirmed')
        self.assertEqual(len(self.fake.objects), 4)
        (self.root / 'cli-receipt.json').unlink()
        code, receipt = self.cli('--execute')
        self.assertEqual(receipt['outcome'], 'success')
        self.assertEqual(len(receipt['attempts']), 4)

    def test_expiry_paginated_inventory_and_final_marker_absence(self):
        self.publish(); self.age()
        key = next(iter(self.fake.objects))[0]
        self.fake.markers[key, 'marker'] = self.now
        original = self.fake.list_object_versions
        def paged(**kw):
            result = original(**kw)
            rows = [(False, e) for e in result['Versions']] + [(True, e) for e in result['DeleteMarkers']]
            cursor = kw.get('KeyMarker'), kw.get('VersionIdMarker')
            if cursor[0]:
                start = next(i + 1 for i, (_, e) in enumerate(rows) if (e['Key'], e['VersionId']) == cursor)
                rows = rows[start:]
            selected = rows[:2]
            result.update(Versions=[e for m, e in selected if not m], DeleteMarkers=[e for m, e in selected if m],
                          IsTruncated=len(rows) > 2)
            if result['IsTruncated']:
                result.update(NextKeyMarker=selected[-1][1]['Key'], NextVersionIdMarker=selected[-1][1]['VersionId'])
            return result
        with patch.object(self.fake, 'list_object_versions', side_effect=paged):
            result = self.expire()
        self.assertTrue(result['absenceVerified'])
        self.assertEqual(len(result['attempts']), 6)

    def test_expiry_page_budget_is_enforced_before_mutation(self):
        self.publish(); self.age()
        original = self.fake.list_object_versions
        base = original(Bucket='synthetic')
        entry = base['Versions'][0]
        def pages(**kw):
            version = str(int(kw.get('VersionIdMarker', '0')) + 1)
            return {**base, 'Versions': [{**entry, 'VersionId': version}], 'IsTruncated': True,
                    'NextKeyMarker': entry['Key'], 'NextVersionIdMarker': version}
        with patch.object(self.fake, 'list_object_versions', side_effect=pages):
            with self.assertRaisesRegex(core.Refused, 'pagination budget'): self.expire()
        self.assertFalse(self.deletes())

    def test_expiry_partial_failure_and_configuration_race(self):
        self.publish(); self.age()
        original = self.fake.delete_object
        def drift(**kw):
            result = original(**kw)
            self.fake.lifecycle = [{'ID': 'unexpected'}]
            return result
        with patch.object(self.fake, 'delete_object', side_effect=drift):
            with self.assertRaises(core.Refused): self.expire()
        self.assertEqual(len(self.deletes()), 1)
        self.assertEqual(self.expiry_receipt['outcome'], 'failure')
        self.assertFalse(self.expiry_receipt['absenceVerified'])
        self.assertIn('acknowledgedAt', self.expiry_receipt['attempts'][0])

    def test_expiry_safe_with_local_clock_ahead_of_retention(self):
        self.publish(); self.age(33)
        self.fake.now -= dt.timedelta(seconds=30)
        with self.assertRaises(core.Refused): self.expire()
        self.assertFalse(self.deletes())

    def test_execute_flag_is_refused_for_other_commands(self):
        receipt = self.root / 'wrong-command.json'
        with patch.object(sys, 'argv', ['recovery', 'verify', '--execute', '--receipt', str(receipt)]):
            self.assertEqual(core.main(), 1)
        self.assertEqual(json.loads(receipt.read_text())['outcome'], 'failure')
        self.assertFalse(self.deletes())

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
            result = {'Versions': entries[:2], 'IsTruncated': True,
                      'NextKeyMarker': entries[1]['Key'], 'NextVersionIdMarker': entries[1]['VersionId']}
            if kw.get('KeyMarker'):
                result = {'Versions': entries[2:], 'IsTruncated': False}
            return {**result, 'Name': 'synthetic', 'ResponseMetadata': {'HTTPStatusCode': 200,
                    'HTTPHeaders': {'date': email.utils.format_datetime(self.fake.now, usegmt=True)}}}
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

    def test_admission_lists_without_reading_content_and_warns_at_d7(self):
        self.publish()
        self.fake.calls.clear()
        evidence = self.store.admission_evidence(self.lifecycle, 1000000)
        self.assertEqual((evidence['kind'], evidence['alerts']), ('s3-admission', []))
        self.assertGreater(evidence['retainedBytes'], 0)
        self.assertFalse({op for op, _ in self.fake.calls} & {'get_object', 'put_object', 'delete_object', 'get_object_retention'})
        with patch('recovery_s3.WARNING_BYTES', 1):
            self.assertEqual(self.store.admission_evidence(self.lifecycle, 1000000)['alerts'], ['capacity-warning'])
            self.assertIn('capacity-warning', self.store.observe(self.lifecycle, 1000000)['alerts'])
        with self.assertRaises(core.Refused):
            self.store.admission_evidence(self.lifecycle, 1)

    def test_inventory_accepts_botocore_url_encoding_echoed_by_the_provider(self):
        # Regression (#276, first real OVHcloud listing): botocore adds EncodingType=url to
        # ListObjectVersions and decodes the keys, and the provider echoes the field.
        original = self.fake.list_object_versions
        for published in (False, True):
            if published: self.publish()
            with self.subTest(published=published), patch.object(self.fake, 'list_object_versions',
                    side_effect=lambda **kw: {**original(**kw), 'EncodingType': 'url'}):
                self.assertEqual(self.store.admission_evidence(self.lifecycle, 1000000)['kind'], 's3-admission')
        with patch.object(self.fake, 'list_object_versions',
                          side_effect=lambda **kw: {**original(**kw), 'EncodingType': 'base64'}):
            with self.assertRaises(core.Refused):
                self.store.admission_evidence(self.lifecycle, 1000000)

    def test_never_held_version_reads_as_legal_hold_off_but_other_errors_still_refuse(self):
        # Regression (#276, first real OVHcloud observation): GetObjectLegalHold on a version that
        # never had a hold answers NoSuchObjectLockConfiguration instead of Status OFF.
        self.publish()
        self.fake.hold = None
        self.assertNotIn('retention-incident', self.store.observe(self.lifecycle, 1000000).get('alerts', []))
        def denied(**kw):
            error = RuntimeError('AccessDenied'); error.response = {'Error': {'Code': 'AccessDenied'}}; raise error
        with patch.object(self.fake, 'get_object_legal_hold', side_effect=denied), self.assertRaises(RuntimeError):
            self.store.observe(self.lifecycle, 1000000)

    def test_admission_refuses_configuration_drift_and_clock_skew(self):
        self.fake.lock['Rule']['DefaultRetention']['Days'] = 34
        with self.assertRaises(core.Refused):
            self.store.admission_evidence(self.lifecycle, 1000000)
        self.fake.lock['Rule']['DefaultRetention']['Days'] = 33
        self.store.provider_clock = lambda: self.now + dt.timedelta(seconds=61)
        with self.assertRaises(core.Refused):
            self.store.admission_evidence(self.lifecycle, 1000000)

    def test_publish_accepts_admission_evidence(self):
        evidence = self.store.admission_evidence(self.lifecycle, 1000000)
        evidence['outcome'] = 'success'
        core.write(self.proof, evidence); self.proof.chmod(0o600)
        self.assertIn('receiptObject', self.publish())
        for field, value in [('outcome', 'failure'), ('bucket', 'other'), ('alerts', ['multiple-versions']), ('kind', 's3-publish')]:
            core.write(self.proof, {**evidence, field: value}); self.proof.chmod(0o600)
            with self.subTest(field=field), self.assertRaises(core.Refused): self.publish()

    def second_pair(self):
        self.now += dt.timedelta(hours=12); self.fake.now = self.now
        manifest = json.loads((self.pair / 'manifest.json').read_text())
        manifest.update(backupId='b' * 32, recoveryPoint=self.now.isoformat())
        core.write(self.pair / 'manifest.json', manifest)
        (self.pair / 'SHA256SUMS').write_text(''.join(f'{core.digest(self.pair/n)}  {n}\n' for n in core.FILES[:-1]))
        self.refresh()
        return self.publish()

    def object_reads(self):
        return sum(1 for op, kw in self.fake.calls if op == 'get_object')

    def test_observer_hashes_each_pair_once(self):
        self.publish()
        state, saved = {}, []
        first = self.store.observe(self.lifecycle, 1000000, state=state, checkpoint=lambda: saved.append(1))
        self.assertEqual(len(first['pairs']), 1)
        self.assertEqual(saved, [1])
        self.fake.calls.clear()
        again = self.store.observe(self.lifecycle, 1000000, state=state)
        self.assertEqual(self.object_reads(), 0)
        self.assertEqual(again['latestIntegrityRecoveryPoint'], first['latestIntegrityRecoveryPoint'])
        self.second_pair()
        self.fake.calls.clear()
        third = self.store.observe(self.lifecycle, 1000000, state=state)
        self.assertEqual(len(third['pairs']), 2)
        self.assertEqual(self.object_reads(), 5, 'Only the new pair and its receipt are read.')

    def test_observer_limit_verifies_newest_first_and_never_counts_unhashed(self):
        self.publish(); newest = self.second_pair()
        state = {}
        result = self.store.observe(self.lifecycle, 1000000, state=state, verify_limit=1)
        self.assertIn('integrity-backlog', result['alerts'])
        self.assertEqual([p['backupId'] for p in result['pairs']], [newest['backupId']])
        self.assertNotIn('incomplete-attempt', result['alerts'])
        self.assertNotIn('integrity-backlog', self.store.observe(self.lifecycle, 1000000, state=state, verify_limit=1)['alerts'])

    def test_observer_state_detects_a_removed_version_and_forgets_expired_pairs(self):
        proof = self.publish(); state = {}
        self.store.observe(self.lifecycle, 1000000, state=state)
        obj = proof['objects'][core.FILES[0]]
        saved = self.fake.objects.pop((obj['key'], obj['versionId']))
        with self.assertRaises(core.Refused): self.store.observe(self.lifecycle, 1000000, state=state)
        self.fake.objects[obj['key'], obj['versionId']] = saved
        self.fake.objects.clear()
        self.store.observe(self.lifecycle, 1000000, state=state)
        self.assertEqual(state['verified'], {})

    def test_multipart_lock_anchored_before_last_modified_is_valid(self):
        # A multipart lock can start at initiation, so retain-until is under 33 days from completion.
        self.publish()
        entry = self.fake.list_object_versions(Bucket='synthetic')['Versions'][0]
        self.now += dt.timedelta(hours=1); self.fake.now = self.now
        completed = {**entry, 'LastModified': entry['LastModified'] + dt.timedelta(hours=1)}
        self.store.protection(completed)
        original = self.fake.get_object_retention
        def short(**kw):
            result = original(**kw)
            result['Retention']['RetainUntilDate'] -= dt.timedelta(seconds=1)
            return result
        with patch.object(self.fake, 'get_object_retention', side_effect=short):
            with self.assertRaises(core.Refused): self.store.protection(entry)

    def test_abandoned_attempt_does_not_prevent_next_capture(self):
        self.publish()
        key = next(k for k in self.fake.objects if k[0].endswith('receipt.json'))
        del self.fake.objects[key]
        self.refresh()
        replacement = self.publish()
        self.assertIn(replacement['receiptObject']['key'], {k for k, _ in self.fake.objects})


if __name__ == '__main__': unittest.main()
