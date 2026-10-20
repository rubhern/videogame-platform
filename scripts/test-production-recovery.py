#!/usr/bin/env python3
"""Focused real encryption plus failure/refusal tests. No real host or personal data."""
import copy
import datetime as dt
import importlib.machinery
import importlib.util
import json
import io
import tarfile
import os
from pathlib import Path
import subprocess
import signal
import time
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader('recovery_test_core', str(ROOT / 'deploy/public-production/bin/recovery'))
spec = importlib.util.spec_from_loader(loader.name, loader)
core = importlib.util.module_from_spec(spec)
loader.exec_module(core)


class Recovery(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        cls.key = cls.root / 'private'; cls.key.mkdir(mode=0o700)
        cls.public = cls.root / 'public'; cls.public.mkdir(mode=0o700)
        core.run(['gpg', '--homedir', cls.key, '--batch', '--faked-system-time', str(int(time.time())-300), '--passphrase', '', '--quick-generate-key', 'Synthetic recovery test', 'rsa2048', 'encr', '1d'])
        listing = core.run(['gpg', '--homedir', cls.key, '--batch', '--with-colons', '--list-keys']).decode()
        cls.fpr = next(x.split(':')[9] for x in listing.splitlines() if x.startswith('fpr:'))
        core.run(['gpg', '--homedir', cls.public, '--batch', '--import'], data=core.run(['gpg', '--homedir', cls.key, '--batch', '--export', cls.fpr]))

    @classmethod
    def tearDownClass(cls):
        for ring in [cls.key, cls.public]:
            subprocess.run(['gpgconf', '--homedir', str(ring), '--kill', 'all'], capture_output=True)
        cls.tmp.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=self.root)
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        # Freshness decisions are deterministic even if the workstation wall clock is adjusted.
        clock = patch.object(core, 'utc', return_value=dt.datetime(2026, 10, 20, 12, 0, tzinfo=dt.timezone.utc))
        clock.start(); self.addCleanup(clock.stop)
        self.state = self.work / 'state'; self.state.mkdir(mode=0o700)
        self.runtime = core.Runtime.__new__(core.Runtime)
        self.runtime.state = self.state
        self.runtime.project = 'vgp-production-test-unit'
        self.runtime.gate_volume = 'synthetic-gate'
        lease = patch.object(self.runtime, 'engine_lock', return_value=__import__('contextlib').nullcontext())
        lease.start(); self.addCleanup(lease.stop)
        self.gate_patch = patch.object(self.runtime, 'gate', return_value=b'closed')
        self.gate_patch.start(); self.addCleanup(self.gate_patch.stop)
        self.runtime.images = {x: x + '@sha256:' + 'b' * 64 for x in ['postgres','application','keycloak']}
        self.runtime.volumes = {}
        self.runtime.config_hash = 'a' * 64
        self.meta = {'postgresMajor': '18', 'applicationSchema': '1', 'keycloakSchema': 'abc'}

    def fake_sql(self, db, query, output=None):
        return b't\n'

    def backup(self, destination=None, fault=None):
        destination = destination or self.work / 'pair'
        def compose(*args, **kw):
            if 'pg_dump' in args:
                if fault:
                    raise core.Refused('sensitive@example.test')
                kw['output'].write(b'PGDMP synthetic secret bytes')
            return b''
        with patch.object(self.runtime, 'compose', side_effect=compose), patch.object(self.runtime, 'sql', side_effect=self.fake_sql), \
             patch.object(self.runtime, 'metadata', return_value=self.meta), patch.object(self.runtime, 'identity'):
            return self.runtime.backup(destination, self.public, self.fpr)

    def test_encrypt_verify_copy_decrypt_and_cleanup(self):
        result = self.backup()
        pair = self.work / 'pair'
        self.assertFalse(result['independentRecoveryVerified'])
        manifest = core.verify(pair)
        copied = self.work / 'copy'
        core.copy_pair(pair, copied)
        self.assertEqual(core.verify(copied), manifest)
        with core.scratch() as d:
            core.crypto('bc_decrypt_file', copied / core.FILES[0], d / 'plain', self.key)
            self.assertEqual((d / 'plain').read_bytes(), b'PGDMP synthetic secret bytes')
        self.assertFalse(d.exists())
        self.assertFalse((self.state / 'READY').exists())
        self.assertNotIn('secret bytes', json.dumps(manifest))

    def test_public_keyring_refuses_private_key(self):
        with self.assertRaises(core.Refused):
            core.public_key(self.key, self.fpr)

    def test_encryption_failure_leaves_closed_no_published_pair_or_plaintext(self):
        with patch.object(core, 'crypto', side_effect=core.Refused('encryption failure')), self.assertRaises(core.Refused):
            self.backup()
        self.assertFalse((self.work / 'pair').exists())
        self.assertFalse((self.work / 'pair.partial').exists())
        self.assertFalse((self.state / 'READY').exists())
        self.assertTrue((self.state / 'PENDING.json').exists())

    def test_interrupted_dump_leaves_closed_and_thaw_refuses(self):
        with self.assertRaises(core.Refused): self.backup(fault=True)
        self.assertFalse((self.work / 'pair.partial').exists())
        with self.assertRaises(core.Refused): self.runtime.thaw()

    def test_missing_pair_and_ciphertext_corruption(self):
        self.backup()
        pair = self.work / 'pair'
        (pair / core.FILES[0]).write_bytes(b'corrupt')
        with self.assertRaises(core.Refused): core.verify(pair)
        (pair / core.FILES[0]).unlink()
        with self.assertRaises(core.Refused): core.verify(pair)

    def test_manifest_identity_and_truncated_checksums_refused(self):
        self.backup()
        pair = self.work / 'pair'
        (pair / 'SHA256SUMS').write_text('')
        with self.assertRaises(core.Refused): core.verify(pair)
        m = json.loads((pair / 'manifest.json').read_text()); m['issuer'] = 'https://other.invalid'
        core.write(pair / 'manifest.json', m)
        with self.assertRaises(core.Refused): core.verify(pair)

    def test_symlink_refused(self):
        self.backup()
        pair = self.work / 'pair'
        (pair / core.FILES[0]).rename(self.work / 'cipher')
        (pair / core.FILES[0]).symlink_to(self.work / 'cipher')
        with self.assertRaises(core.Refused): core.verify(pair)

    def test_copy_existing_and_interrupted_cleanup(self):
        self.backup()
        with self.assertRaises(core.Refused): core.copy_pair(self.work / 'pair', self.work / 'pair')
        with patch.object(core.shutil, 'copyfile', side_effect=OSError('copy interrupted')), self.assertRaises(OSError):
            core.copy_pair(self.work / 'pair', self.work / 'copy')
        self.assertFalse((self.work / 'copy.partial').exists())

    def test_active_unknown_or_prepared_writers_refused(self):
        for value in [b'f\n', b'', b't\nf\n']:
            with patch.object(self.runtime, 'sql', return_value=value), self.assertRaises(core.Refused):
                self.runtime.assert_fenced()

    def test_fence_disables_logins_before_stop_and_drain(self):
        events = []
        with patch.object(self.runtime, 'sql', side_effect=lambda db,q: events.append(q) or b't\n'), \
             patch.object(self.runtime, 'stop_writers', side_effect=lambda: events.append('stop')):
            self.runtime.fence()
        self.assertIn('NOLOGIN', events[0])
        self.assertEqual(events[1], 'stop')
        self.assertIn('pg_terminate_backend(pid, 5000)', events[2])

    def test_restore_existing_container_or_volume_refused(self):
        for results in [[b'container']]:
            with patch.object(core, 'run', side_effect=results), self.assertRaises(core.Refused): self.runtime.fresh()

    def test_unsafe_project_refused_before_docker(self):
        for project in ['videogame-platform-dev', 'default', '../production', 'vgp-production-test-;x']:
            with patch.object(core, 'run') as runner, self.assertRaises(core.Refused):
                core.Runtime(self.work / 'config', project, self.state)
            runner.assert_not_called()

    def test_failed_decryption_creates_no_database_and_leaves_closed(self):
        self.backup()
        with patch.object(self.runtime, 'fresh'), patch.object(self.runtime, 'compose') as compose, \
             patch.object(core, 'crypto', side_effect=core.Refused('decrypt')), self.assertRaises(core.Refused):
            self.runtime.restore(self.work / 'pair', self.public)
        compose.assert_not_called()
        self.assertEqual(json.loads((self.state / 'PENDING.json').read_text())['phase'], 'restoring')

    def test_restore_failure_leaves_pending_and_cleans_plaintext(self):
        self.backup()
        with patch.object(self.runtime, 'fresh'), patch.object(self.runtime, 'compose', side_effect=core.Refused('startup')), self.assertRaises(core.Refused):
            self.runtime.restore(self.work / 'pair', self.key)
        self.assertFalse((self.state / 'READY').exists())

    def evidence(self, hours=0):
        owner = self.work / 'owner'; owner.mkdir(mode=0o700)
        independent = self.work / 'independent'; independent.mkdir(mode=0o700)
        ledger = owner / 'ledger'; ledger.write_text(''); ledger.chmod(0o600)
        ledger_copy = independent / 'ledger'; ledger_copy.write_text(''); ledger_copy.chmod(0o600)
        checkpoint = owner / 'checkpoint'
        core.write(checkpoint, {'asOf': (core.utc()-dt.timedelta(hours=hours)).isoformat(), 'environment':'production', 'ledgerSha256':core.digest(ledger)})
        return ledger, ledger_copy, checkpoint

    def test_empty_current_ledger_requires_two_matching_copies(self):
        paths = self.evidence()
        self.assertEqual(core.ledger_evidence(*paths), core.digest(paths[0]))
        paths[1].write_text('different')
        with self.assertRaises(core.Refused): core.ledger_evidence(*paths)

    def test_stale_and_missing_deletion_evidence_refused(self):
        paths = self.evidence(hours=2)
        with self.assertRaises(core.Refused): core.ledger_evidence(*paths)
        paths[1].unlink()
        with self.assertRaises(FileNotFoundError): core.ledger_evidence(*paths)

    def test_reopen_missing_replay_refuses_even_with_fresh_ledger(self):
        paths = self.evidence()
        with self.assertRaises(FileNotFoundError): self.runtime.reopen(*paths)
        self.assertFalse((self.state / 'READY').exists())

    def test_concurrent_actor_lock(self):
        with self.runtime.lock(), self.assertRaises(core.Refused):
            with self.runtime.lock(): pass

    def test_retention_never_deletes_last_rehearsed_pair(self):
        root = self.work / 'backups'; root.mkdir(mode=0o700)
        self.backup(root / 'pair')
        m = core.verify(root / 'pair')
        receipt = self.work / 'proof'
        core.write(receipt, {'kind':'recovery-rehearsal', 'outcome':'success', 'deletionReplay':True,'readiness':True,
                            'backupId':m['backupId'], 'manifestSha256':core.digest(root / 'pair/manifest.json')})
        self.assertEqual(core.retain(root, receipt)['kept'], 1)
        m['recoveryPoint'] = (core.utc()-dt.timedelta(days=36)).isoformat()
        core.write(root / 'pair/manifest.json', m)
        (root / 'pair/SHA256SUMS').write_text(''.join(f'{core.digest(root / "pair" / n)}  {n}\n' for n in core.FILES[:-1]))
        proof = json.loads(receipt.read_text()); proof['manifestSha256'] = core.digest(root / 'pair/manifest.json'); core.write(receipt,proof)
        with self.assertRaises(core.Refused): core.retain(root,receipt)
        self.assertTrue((root / 'pair').exists())

    def test_pull_bad_alias_and_target_refused(self):
        config = self.work / 'ssh'; config.touch(mode=0o600)
        for alias in ['-oProxyCommand=x','host;touch']:
            with self.assertRaises(core.Refused): core.pull_pair(config, alias, '/safe/path', self.work / 'new', 100)

    def test_actual_decryption_without_secret_key_fails_and_cleans_scratch(self):
        self.backup()
        with core.scratch() as directory:
            with self.assertRaises(core.Refused):
                core.crypto('bc_decrypt_file', self.work / 'pair' / core.FILES[0], directory / 'plaintext', self.public)
        self.assertFalse(directory.exists())

    def test_pull_checks_inventory_byte_bound_interruption_and_pinned_ssh(self):
        self.backup()
        config = self.work / 'ssh'; config.touch(mode=0o600)
        def bundle(unsafe=False):
            data = io.BytesIO()
            with tarfile.open(fileobj=data, mode='w') as archive:
                for name in core.FILES:
                    archive.add(self.work / 'pair' / name, arcname=name)
                if unsafe:
                    entry = tarfile.TarInfo('../escape'); entry.size = 1
                    archive.addfile(entry, io.BytesIO(b'x'))
            return data.getvalue()
        class Actor:
            def __init__(self, data, code=0): self.stdout, self.code = io.BytesIO(data), code
            def wait(self, timeout=None): return self.code
            def poll(self): return self.code
            def kill(self): self.code = -9
        for index, (data, code, limit, success) in enumerate([
                (bundle(), 0, 1000000, True), (bundle(True), 0, 1000000, False),
                (bundle(), 1, 1000000, False), (bundle(), 0, 1, False)]):
            destination = self.work / ('pulled-' + str(index))
            with patch.object(core.subprocess, 'Popen', return_value=Actor(data, code)) as popen:
                if success:
                    self.assertFalse(core.pull_pair(config, 'source-reader', '/ciphertext/pair', destination, limit)['storageIsolationVerified'])
                else:
                    with self.assertRaises(core.Refused): core.pull_pair(config, 'source-reader', '/ciphertext/pair', destination, limit)
                    self.assertFalse(destination.exists())
                self.assertIn('-oStrictHostKeyChecking=yes', popen.call_args.args[0])
            self.assertFalse(destination.with_name(destination.name + '.partial').exists())
        self.assertFalse((self.work / 'escape').exists())

    def test_restored_schema_mismatch_or_orphan_fails_closed(self):
        with patch.object(self.runtime, 'compose') as compose:
            # Neither missing schemas nor mismatched identity snapshots can authorize startup.
            with self.assertRaises(FileNotFoundError): self.runtime.reopen(*self.evidence())
            compose.assert_not_called()

    def test_identity_stream_join_refuses_orphans_and_accepts_exact_issuer(self):
        import uuid, hashlib
        subject = '11111111-1111-4111-8111-111111111111'
        user = str(uuid.UUID(bytes=hashlib.md5((core.ISSUER+'\n'+subject).encode()).digest(), version=3))
        for principal, accepted in [(user, True), ('00000000-0000-0000-0000-000000000000', False)]:
            def sql(database, query, output=None):
                if output:
                    output.write(((subject if database == core.DATABASES[1] else principal)+'\n').encode())
                return b'1\n'
            with patch.object(self.runtime, 'sql', side_effect=sql):
                if accepted: self.runtime.identity()
                else:
                    with self.assertRaises(core.Refused): self.runtime.identity()

    def test_restore_refuses_unlabelled_referenced_volume(self):
        self.runtime.volumes = {'postgres-data': {'name': 'vgp-production-test-unit-postgres-data'}}
        with patch.object(core.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)), self.assertRaises(core.Refused):
            self.runtime.fresh()

    def test_production_private_key_refused_on_runtime_docker_endpoint(self):
        self.runtime.project = 'gameometro-production'
        with patch.dict(os.environ, {'DOCKER_HOST': 'unix:///var/run/docker.sock'}), self.assertRaises(core.Refused):
            self.runtime.restore(self.work / 'pair', self.key)

    def test_sigterm_during_copy_cleans_staging_and_emits_sanitized_failure(self):
        # A fake SSH executable stalls before a tar header; no host/network is contacted.
        binary = self.work / 'bin'; binary.mkdir()
        ssh = binary / 'ssh'
        ssh.write_text('#!/usr/bin/env python3\nimport time\ntime.sleep(120)\n')
        ssh.chmod(0o700)
        config = self.work / 'ssh-config'; config.touch(mode=0o600)
        receipt = self.work / 'interrupted.json'
        target = self.work / 'copied'
        process = subprocess.Popen([sys.executable, str(ROOT / 'deploy/public-production/bin/recovery'), 'pull',
            '--ssh-config', str(config), '--ssh-host', 'synthetic', '--remote-backup', '/ciphertext/pair',
            '--max-copy-bytes', '1000000', '--destination', str(target), '--receipt', str(receipt)],
            env={**os.environ, 'PATH': str(binary)+os.pathsep+os.environ['PATH']}, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic()+5
            while not target.with_name(target.name+'.partial').exists() and time.monotonic()<deadline:
                time.sleep(0.01)
            self.assertTrue(target.with_name(target.name+'.partial').exists())
            process.send_signal(signal.SIGTERM)
            output, error = process.communicate(timeout=5)
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse(target.exists())
            self.assertFalse(target.with_name(target.name+'.partial').exists())
            self.assertEqual(json.loads(receipt.read_text())['outcome'], 'failure')
            self.assertNotIn(str(self.work).encode(), output+error)
        finally:
            if process.poll() is None: process.kill()
            process.communicate()

    def test_missing_stale_changed_or_wrong_backup_replay_cannot_reopen(self):
        paths = self.evidence()
        core.write(self.state / 'PENDING.json', {'phase': 'deletion-replay-required', 'backupId':'a'*32})
        for patch_record in [{'backupId':'b'*32}, {'ledgerSha256':'0'*64},
                             {'verifiedAt':(core.utc()-dt.timedelta(hours=2)).isoformat()}]:
            record = {'backupId':'a'*32, 'ledgerSha256':core.digest(paths[0]), 'verifiedAt':core.utc().isoformat()}
            record.update(patch_record)
            core.write(self.state / 'REPLAY.json', record)
            with patch.object(self.runtime, 'sql') as sql, self.assertRaises(core.Refused):
                self.runtime.reopen(*paths)
            sql.assert_not_called()
            self.assertFalse((self.state / 'READY').exists())

    def test_replay_failure_removes_old_proof_and_keeps_startup_closed(self):
        paths = self.evidence()
        core.write(self.state / 'PENDING.json', {'phase':'deletion-replay-required','backupId':'a'*32})
        core.write(self.state / 'SOURCE.json', {'recoveryPoint':(core.utc()-dt.timedelta(days=1)).isoformat()})
        core.write(self.state / 'REPLAY.json', {'old':True})
        with patch.object(self.runtime, 'sql'), patch.object(self.runtime, 'compose', return_value=b''), \
             patch.object(core, 'run', side_effect=core.Refused('replay failed')), self.assertRaises(core.Refused):
            self.runtime.replay(*paths, 'safe-command')
        self.assertFalse((self.state / 'REPLAY.json').exists())
        self.assertFalse((self.state / 'READY').exists())
        self.assertTrue((self.state / 'PENDING.json').exists())

    def test_retention_daily_weekly_bound_and_expiry(self):
        root = self.work / 'store'; root.mkdir(mode=0o700)
        self.backup(root / 'seed')
        seed = root / 'seed'
        candidates = []
        for day in range(40):
            pair = root / ('day-' + str(day))
            core.copy_pair(seed, pair)
            m = json.loads((pair / 'manifest.json').read_text())
            m['backupId'] = f'{day:032x}'
            m['recoveryPoint'] = (core.utc()-dt.timedelta(days=day, hours=1)).isoformat()
            core.write(pair / 'manifest.json', m)
            (pair / 'SHA256SUMS').write_text(''.join(f'{core.digest(pair / n)}  {n}\n' for n in core.FILES[:-1]))
            candidates.append(pair)
        core.shutil.rmtree(seed)
        proof = self.work / 'proof'
        m = core.verify(candidates[34])
        core.write(proof, {'kind':'recovery-rehearsal','outcome':'success','deletionReplay':True,'readiness':True,
                          'backupId':m['backupId'],'manifestSha256':core.digest(candidates[34]/'manifest.json')})
        result = core.retain(root, proof)
        self.assertLessEqual(result['kept'], 12)
        self.assertTrue(candidates[34].exists())
        self.assertFalse(any(p.exists() for p in candidates[35:]))

    def test_verified_recovery_receipt_binds_actual_readiness_and_manifest(self):
        self.backup()
        paths = self.evidence()
        pair = self.work / 'pair'
        m = core.verify(pair)
        (self.state / 'PENDING.json').unlink()
        core.write(self.state / 'SOURCE.json', {'backupId':m['backupId'],'manifestSha256':core.digest(pair/'manifest.json')})
        core.write(self.state / 'REPLAY.json', {'backupId':m['backupId'],'ledgerSha256':core.digest(paths[0])})
        core.write(self.state / 'READY', {'backupId':m['backupId']})
        with patch.object(self.runtime, 'gate', return_value=b'open'), patch.object(self.runtime, 'compose', return_value=b'') as compose:
            proof = self.runtime.record_recovery(pair, *paths)
            self.assertTrue(proof['readiness'])
            self.assertEqual(proof['manifestSha256'],core.digest(pair/'manifest.json'))
            self.assertIn('http://127.0.0.1:8081/actuator/health/readiness', compose.call_args.args)
        with patch.object(self.runtime, 'gate', return_value=b'open'), patch.object(self.runtime, 'compose', side_effect=core.Refused('not ready')), self.assertRaises(core.Refused):
            self.runtime.record_recovery(pair, *paths)

    def test_offline_revocation_uses_admin_api_and_refuses_remaining_sessions(self):
        loader = importlib.machinery.SourceFileLoader('recovery_offline_adapter_test', str(ROOT / 'deploy/public-production/bin/recovery-personal-data'))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        adapter = importlib.util.module_from_spec(spec); loader.exec_module(adapter)
        from unittest.mock import Mock
        target, database = Mock(), Mock()
        subject = '11111111-1111-4111-8111-111111111111'
        row = json.dumps([subject, 'client/with-path']).encode()+b'\n'
        database.sql.side_effect = [row, b'', b'0']
        self.assertTrue(adapter.logout_all(target, database)['revoked'])
        target.keycloak.assert_any_call('POST', '/logout-all')
        target.keycloak.assert_any_call('DELETE', '/users/'+subject+'/consents/client%2Fwith-path', missing_ok=True)
        database.sql.side_effect = [b'', b'1']
        with self.assertRaises(adapter.recovery.Refused): adapter.logout_all(target,database)
        database.sql.side_effect = [row]
        with patch.object(adapter.time,'monotonic',side_effect=[0,121]), self.assertRaises(adapter.recovery.Refused):
            adapter.logout_all(target,database)

    def test_lost_runtime_lease_refuses_more_database_work(self):
        from unittest.mock import Mock
        self.runtime._lease_actor = Mock()
        self.runtime._lease_actor.poll.return_value = 1
        with patch.object(core,'run') as command, self.assertRaises(core.Refused):
            self.runtime.compose('up','application')
        command.assert_not_called()

    def test_guard_missing_ready_pending_and_approved(self):
        guard = (ROOT / 'deploy/public-production/bin/run-application').read_text()
        shell = guard.replace('/run/recovery', str(self.state)).replace('/opt/gameometro/bin/shared-run-application', '/bin/true')
        def status():
            return subprocess.run(['sh', '-c', shell], capture_output=True).returncode
        self.assertNotEqual(status(), 0)
        (self.state / 'READY').touch()
        self.assertEqual(status(), 0)
        (self.state / 'PENDING.json').touch()
        self.assertNotEqual(status(), 0)



if __name__ == '__main__':
    os.umask(0o077)
    unittest.main()
