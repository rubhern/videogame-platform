#!/usr/bin/env python3
"""Production admission, serialization, interruption and secret-boundary tests; no VPS."""
import contextlib
import copy
import json
import io
import zipfile
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch, MagicMock

import production_deploy as deploy
import production_recovery_executor as executor

SHA = 'a' * 40
IMAGES = {name: repo + '@sha256:' + 'b' * 64 for name, repo in deploy.integration.REPOSITORIES.items()}
PREFLIGHT = {'kind': 'recovery-ready', 'outcome': 'success', 'nonce': 'f'*64, 'primaryFingerprint': 'A'*40,
             'tmpfsFreeBytes': 10**10, 'diskFreeBytes': 10**10}
BACKUP = 'c' * 32


class Api:
    def __init__(self):
        self.run = {'head_sha': SHA, 'head_branch': 'main', 'path': deploy.WORKFLOW, 'event': 'workflow_dispatch',
                    'head_repository': {'full_name': deploy.trust.REPOSITORY}, 'actor': {'login': 'rubhern'},
                    'triggering_actor': {'login': 'rubhern'}, 'status': 'in_progress', 'run_attempt': 1, 'run_started_at': '2026-11-08T10:00:00Z'}
        self.policy = {'can_admins_bypass': False,
                       'protection_rules': [{'type': 'required_reviewers', 'prevent_self_review': False,
                                             'reviewers': [{'type': 'User', 'reviewer': {'login': 'rubhern'}}]}],
                       'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True}}
        self.branches = {'total_count': 1, 'branch_policies': [{'name': 'main', 'type': 'branch'}]}
        self.reviews = [{'state': 'approved', 'user': {'login': 'rubhern'}, 'environments': [{'name': 'production'}, {'name': 'production-recovery'}]}]

    def request(self, path):
        if path.endswith('/approvals'):
            return self.reviews
        if 'deployment-branch-policies' in path:
            return self.branches
        if path in ('/environments/production', '/environments/production-recovery'):
            return self.policy
        return self.run


class AdmissionTests(unittest.TestCase):
    def test_command_rejects_injection_and_retries(self):
        for value in (f'deploy {SHA} 1 2', f'deploy {SHA} 1 1; id', f'--host deploy {SHA} 1 1',
                      f'deploy main 1 1', f'deploy {SHA} 01 1', f'resume {SHA} 1 1', f'deploy {SHA} 1 1\n'):
            with self.subTest(value=value), self.assertRaises(deploy.trust.Refused):
                deploy.identity(value)

    def test_approval_and_all_publications_required(self):
        api = Api()
        with patch.object(deploy.integration, 'resolve', return_value={'images': IMAGES}) as resolve:
            self.assertEqual(deploy.admission(api, SHA, '123', '1', host=True)['images'], IMAGES)
            resolve.assert_called_once_with(api, SHA)
            api.reviews = []
            with self.assertRaises(deploy.trust.Refused):
                deploy.admission(api, SHA, '123', '1', host=True)
            self.assertEqual(resolve.call_count, 1)

    def test_runner_waits_for_both_approvals_in_either_order(self):
        for first in ('production', 'production-recovery'):
            with self.subTest(first=first):
                api = Api()
                api.run['status'] = 'waiting'
                both = api.reviews
                api.reviews = [{**both[0], 'environments': [{'name': first}]}]
                with self.assertRaises(deploy.trust.Refused):
                    deploy.dispatch(api, SHA, '123', '1')
                delays = []

                def advance(seconds):
                    delays.append(seconds)
                    api.run['status'] = 'in_progress'
                    if len(delays) == 2:
                        api.reviews = both

                deploy.wait_for_approvals(api, SHA, '123', '1', clock=lambda: sum(delays), sleep=advance)
                self.assertEqual(delays, [5, 5])

    def test_approval_wait_is_bounded_and_rejects_completed_runs(self):
        api = Api()
        api.run['status'] = 'waiting'
        delays = []
        with self.assertRaisesRegex(deploy.trust.Refused, 'within ten minutes'):
            deploy.wait_for_approvals(api, SHA, '123', '1', clock=lambda: sum(delays), sleep=delays.append)
        self.assertEqual(sum(delays), 600)
        api.run['status'] = 'completed'
        with self.assertRaises(deploy.trust.Refused):
            deploy.wait_for_approvals(api, SHA, '123', '1', sleep=self.fail)

    def test_untrusted_dispatches_refuse(self):
        for key, value in {'head_sha': 'b'*40, 'head_branch': 'dev', 'path': 'other.yml', 'event': 'push',
                           'actor': {'login': 'other'}, 'triggering_actor': {'login': 'other'},
                           'status': 'completed', 'run_attempt': 2,
                           'head_repository': {'full_name': 'other/fork'}}.items():
            api = Api()
            api.run[key] = value
            with self.subTest(key=key), self.assertRaises(deploy.trust.Refused):
                deploy.dispatch(api, SHA, '123', '1')

    def test_protection_cannot_be_bypassed(self):
        for mutation in ('bypass', 'reviewer', 'self-review', 'branch', 'tag', 'extra'):
            api = Api()
            if mutation == 'bypass': api.policy['can_admins_bypass'] = True
            if mutation == 'reviewer': api.policy['protection_rules'] = []
            if mutation == 'self-review': api.policy['protection_rules'][0]['prevent_self_review'] = True
            if mutation == 'branch': api.branches['branch_policies'][0]['name'] = '*'
            if mutation == 'tag': api.branches['branch_policies'][0]['type'] = 'tag'
            if mutation == 'extra': api.policy['protection_rules'].append({'type': 'custom_deployment_protection_rule'})
            with self.subTest(mutation=mutation), self.assertRaises(deploy.trust.Refused):
                deploy.environment(api)

    def test_peer_must_have_production_tag(self):
        config = {'sourceTag': 'tag:gameometro-production-deploy'}
        with patch.dict(os.environ, {'SSH_CONNECTION': '100.1.2.3 4000 100.4.5.6 22'}):
            for tags in ([], ['tag:vgp-deploy']):
                with patch.object(deploy, 'invoke', return_value=json.dumps({'Node': {'Tags': tags}})), self.assertRaises(deploy.trust.Refused):
                    deploy.peer(config)


class ExportTests(unittest.TestCase):
    def test_real_git_export_excludes_agent_symlinks_and_refuses_cached_drift(self):
        repository = Path(__file__).resolve().parents[1]
        revision = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', 'HEAD'], text=True).strip()
        listing = subprocess.check_output(['git', '-C', str(repository), 'ls-tree', '-rl', revision], text=True)
        entries = []
        for line in listing.splitlines():
            header, name = line.split('\t', 1)
            mode, kind, sha, size = header.split()
            entries.append({'path': name, 'mode': mode, 'type': kind, 'sha': sha, 'size': int(size)})
        tree = {'truncated': False, 'tree': entries}
        with tempfile.TemporaryDirectory() as name:
            destination = Path(name)/'source'
            deploy.materialize(repository, revision, tree, destination)
            self.assertTrue((destination/'scripts/private_production_upgrade.py').is_file())
            self.assertFalse((destination/'.claude').exists())
            deploy.materialize(repository, revision, tree, destination)
            (destination/'scripts/private_production_upgrade.py').write_text('untrusted modification')
            with self.assertRaises(deploy.trust.Refused): deploy.materialize(repository, revision, tree, destination)


class EvidenceTests(unittest.TestCase):
    def fixture(self, kind='result'):
        api = Api()
        value = {'revision': SHA, 'runId': '123', 'attempt': '1'}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as bundle:
            bundle.writestr(deploy.evidence.NAMES[kind], json.dumps(value))
        job = {'name': deploy.evidence.RECOVERY_JOB, 'status': 'completed', 'conclusion': 'success'}
        artifact = {'name': deploy.evidence.artifact_name(kind, '123', '1'), 'id': 10, 'expired': False,
                    'created_at': '2026-11-08T10:01:00Z'}
        def request(path, archive=False):
            if archive: return stream.getvalue()
            if '/jobs?' in path: return {'total_count': 1, 'jobs': [job]}
            if '/artifacts?' in path: return {'total_count': 1, 'artifacts': [artifact]}
            return api.run
        api.request = request
        return api, job, artifact

    def test_result_requires_successful_recovery_job(self):
        api, job, artifact = self.fixture()
        self.assertEqual(deploy.evidence.read(api, 'result', SHA, '123', '1')['revision'], SHA)
        for state, conclusion in [('in_progress', None), ('completed', 'failure'), ('completed', 'cancelled')]:
            job.update(status=state, conclusion=conclusion)
            with self.subTest(state=state, conclusion=conclusion), self.assertRaises(deploy.trust.Refused):
                deploy.evidence.read(api, 'result', SHA, '123', '1')

    def test_stale_foreign_or_expired_evidence_refuses(self):
        for mutation in ('stale', 'expired', 'run-attempt', 'sha', 'fork'):
            api, job, artifact = self.fixture()
            if mutation == 'stale': artifact['created_at'] = '2026-11-08T09:00:00Z'
            if mutation == 'expired': artifact['expired'] = True
            if mutation == 'run-attempt': api.run['run_attempt'] = 2
            if mutation == 'sha': api.run['head_sha'] = 'f'*40
            if mutation == 'fork': api.run['head_repository'] = {'full_name': 'other/fork'}
            with self.subTest(mutation=mutation), self.assertRaises(deploy.trust.Refused):
                deploy.evidence.read(api, 'result', SHA, '123', '1')


class GpgTests(unittest.TestCase):
    def test_real_decryption_subkey_export_has_no_primary_secret(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            original, exported = root/'original', root/'exported'
            original.mkdir(mode=0o700); exported.mkdir(mode=0o700)
            def command(home, *args, data=None):
                result = subprocess.run(['gpg', '--homedir', str(home), '--batch', '--pinentry-mode', 'loopback',
                                         '--passphrase', '', *args], input=data, capture_output=True, check=True)
                return result.stdout
            try:
                command(original, '--quick-generate-key', 'Synthetic recovery #467', 'ed25519', 'cert', '1d')
                listing = command(original, '--with-colons', '--list-keys').decode()
                primary = next(x.split(':')[9] for x in listing.splitlines() if x.startswith('fpr:'))
                command(original, '--quick-add-key', primary, 'cv25519', 'encr', '1d')
                listing = command(original, '--with-colons', '--with-subkey-fingerprint', '--list-secret-keys')
                with self.assertRaises(deploy.trust.Refused): executor.key_identity(listing, 'subkey')
                subkey = [x.split(':')[9] for x in listing.decode().splitlines() if x.startswith('fpr:')][-1]
                command(exported, '--import', data=command(original, '--armor', '--export-secret-subkeys', subkey+'!'))
                observed = executor.key_identity(command(exported, '--with-colons', '--with-subkey-fingerprint', '--list-secret-keys'), 'subkey')
                self.assertEqual(observed, (primary, subkey))
                ciphertext = command(exported, '--trust-model', 'always', '--recipient', subkey+'!', '--encrypt', data=b'synthetic round trip')
                self.assertEqual(command(exported, '--decrypt', data=ciphertext), b'synthetic round trip')
            finally:
                for home in (original, exported):
                    subprocess.run(['gpgconf', '--homedir', str(home), '--kill', 'all'], capture_output=True)

    def test_cleanup_wipes_secrets_even_when_docker_refuses_cleanup(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)/'recovery'; directory.mkdir()
            (directory/'gnupg').mkdir()
            (directory/'gnupg/private-key').write_text('SYNTHETIC SECRET')
            (directory/'restore').mkdir()
            (directory/'restore/restore-compose.json').write_text('{}')
            (directory/'context.json').write_text(json.dumps({'project': 'vgp-production-test-'+'a'*12}))
            with patch.object(executor, 'paths', return_value=directory), patch.dict(os.environ, {'RUNNER_TEMP': name}),                 patch.object(executor, 'invoke', side_effect=deploy.trust.Refused('cleanup failed')):
                with self.assertRaises(deploy.trust.Refused): executor.cleanup(SHA, '123', '1')
            self.assertFalse(directory.exists())

    def test_workflow_never_shares_recovery_and_deployment_credentials(self):
        workflow = (deploy.ROOT/'.github/workflows/deploy-production.yml').read_text()
        recovery, deployment = workflow.split('  recovery:', 1)[1].split('  deploy:', 1)
        self.assertIn('environment: production-recovery', recovery)
        self.assertIn('RECOVERY_GPG_KEY', recovery)
        self.assertNotIn('PRODUCTION_SSH_KEY', recovery)
        self.assertNotIn('RECOVERY_GPG_KEY', deployment)
        self.assertNotIn('RECOVERY_S3_CONFIG', deployment)
        self.assertNotIn('actions/cache', recovery)
        self.assertNotIn('self-hosted', recovery)


class RolloutTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.events = []
        self.failure = None
        self.actor = MagicMock()
        self.actor.load.return_value = self.actor
        self.actor.bundle = self.root/'target-bundle'
        self.actor.plan.return_value = {'publishedPorts': [], 'prerequisites': {'noActiveWork': True, 'keycloakAdministratorAuthenticates': True},
                                       'planSha256': 'd'*64, 'target': {'version': '0.60.0', 'images': IMAGES, 'sourceRunId': '10', 'sourceRunAttempt': '1', 'securityRunId': '20'},
                                       'flyway': {'currentRank': 1, 'currentVersion': '1.0', 'targetVersion': '2.0', 'pending': ['V2_0__next.sql']}}
        self.actor.verify.return_value = {'mode': 'verified', 'targetRevision': SHA,
                                          'services': list(deploy.upgrade.PERSISTENT), 'optionalDegraded': []}
        self.cycle = MagicMock()
        self.cycle.config = {'admin': str(self.root), 'project': 'gameometro-production', 'state': str(self.root/'first-install/recovery'),
                             'keyring': '/public/keyring', 'recipient': 'A'*40, 'uploaderConfig': '/uploader'}
        self.cycle.journal = {'status': 'idle'}
        self.cycle.exclusive.side_effect = self.lease
        self.cycle.budget.side_effect = contextlib.nullcontext
        self.cycle.core.verify.return_value = {'backupId': BACKUP}
        self.cycle.invoke.side_effect = self.publish
        self.runtime = self.cycle.runtime_factory.return_value
        self.runtime.lock.side_effect = contextlib.nullcontext
        self.runtime.sql.return_value = b'2000|1000'
        self.runtime.backup.side_effect = lambda *_: self.event('backup')
        self.actor.stage.side_effect = lambda *_: self.event('stage')
        self.actor.apply.side_effect = lambda *_, **__: self.event('apply')
        self.actor.verify.side_effect = lambda: (self.event('verify') or {'mode': 'verified', 'targetRevision': SHA,
                                            'services': list(deploy.upgrade.PERSISTENT), 'optionalDegraded': []})
        patches = [patch.object(deploy.install, 'ADMIN', self.root), patch.object(deploy.install, 'operational_fingerprint', return_value='e'*64),
                   patch.object(deploy, 'backup_units'),
                   patch.object(deploy, 'access', side_effect=lambda _: self.event('access')),
                   patch.object(deploy.evidence, 'read', side_effect=self.read_evidence),
                   patch.object(deploy.evidence, 'job', return_value={'status': 'in_progress'}),
                   patch.object(deploy.install, 'secure'), patch.dict(os.environ, {'GH_TOKEN': 'synthetic'}), patch.object(deploy, 'load_cycle', return_value=self.cycle),
                   patch.object(deploy.upgrade, 'Upgrade', return_value=self.actor), patch.object(deploy.admin, 'STATE', self.root/'admin.json'),
                   patch.object(deploy, 'invoke', return_value=b'{"outcome":"verified"}')]
        for item in patches:
            item.start()
            self.addCleanup(item.stop)
        self.config = {'backupCycleConfig': '/existing/backup.json', 'policySha256': 'f'*64}

    def event(self, name):
        self.events.append(name)
        if name == self.failure:
            raise deploy.trust.Refused('Synthetic failure containing SECRET which must never be published')

    @contextlib.contextmanager
    def lease(self):
        self.event('cycle-lock')
        try:
            yield
        finally:
            self.event('cycle-unlock')

    def publish(self, *_):
        self.event('publish')
        path = self.root/'publish.json'
        path.write_text(json.dumps({'outcome': 'success', 'kind': 's3-publish', 'backupId': BACKUP}))
        return path

    def read_evidence(self, _, kind, *args, **kwargs):
        self.event(kind)
        if kind == 'ready': return PREFLIGHT
        record = json.loads((self.root/'deployments/123-1/deployment.json').read_text())
        return {'kind': 'isolated-restore', 'outcome': 'success', 'backupId': BACKUP, 'nonce': record['nonce'],
                'requestSha256': record['requestSha256'], 'cleanup': 'verified'}

    def rollout(self):
        return deploy.Rollout(self.config, SHA, '123', '1')

    def test_full_sequence_under_backup_lock_and_no_thaw(self):
        self.rollout().run('prepare')
        self.actor.apply.assert_not_called()
        result = self.rollout().finish()
        self.assertEqual(result['outcome'], 'verified')
        self.assertLess(self.events.index('result'), self.events.index('apply'))
        self.assertLess(self.events.index('apply'), self.events.index('verify'))
        self.assertLess(self.events.index('cycle-lock'), self.events.index('backup'))
        self.assertGreater(len(self.events) - 1, self.events.index('verify'))
        self.runtime.thaw.assert_not_called()
        self.cycle.capture.assert_not_called()
        self.actor.apply.assert_called_once()

    def test_read_only_plan_never_stages_or_fences(self):
        self.assertEqual(self.rollout().run('plan')['outcome'], 'planned')
        self.actor.stage.assert_not_called()
        self.runtime.backup.assert_not_called()

    def test_failures_preserve_phase_and_never_retry_or_thaw(self):
        for name, phase in [('access', 'preflight'), ('ready', 'preflight'), ('stage', 'planned'),
                            ('backup', 'capturing'), ('publish', 'publishing'), ('result', 'awaiting-restore'),
                            ('apply', 'applying'), ('verify', 'verifying')]:
            with self.subTest(name=name):
                import shutil
                shutil.rmtree(self.root/'deployments', ignore_errors=True)
                self.events.clear()
                self.failure = name
                with self.assertRaises(deploy.trust.Refused):
                    self.rollout().run('prepare')
                    self.rollout().finish()
                journal = json.loads((self.root/'deployments/123-1/deployment.json').read_text())
                self.assertEqual(journal['phase'], phase)
                self.assertEqual(journal['outcome'], 'failure')
                self.assertNotIn('SECRET', json.dumps(journal))
                self.assertEqual(self.events.count(name), 1)
                self.runtime.thaw.assert_not_called()

    def test_preflight_failures_persist_only_a_specific_allowlisted_check(self):
        import shutil
        checks = [(deploy, 'access', 'access'), (deploy.evidence, 'read', 'recovery-ready'),
                  (self.actor, 'load', 'installed-runtime'), (deploy, 'load_cycle', 'backup-cycle'),
                  (self.cycle, 'exclusive', 'backup-lock'), (self.cycle, 'preflight', 'backup-runtime'),
                  (self.cycle, 'admission', 'host-s3-admission'), (deploy, 'backup_units', 'backup-services'),
                  (self.actor, 'plan', 'upgrade-plan')]
        for target, method, check in checks:
            with self.subTest(check=check):
                shutil.rmtree(self.root/'deployments', ignore_errors=True)
                with patch.object(target, method, side_effect=deploy.trust.Refused('SECRET child payload')):
                    with self.assertRaises(deploy.trust.Refused):
                        self.rollout().run('plan')
                journal = (self.root/'deployments/123-1/deployment.json').read_text()
                self.assertEqual(json.loads(journal)['check'], check)
                self.assertNotIn('SECRET', journal)
                self.runtime.backup.assert_not_called()
                self.actor.apply.assert_not_called()

    def test_host_failure_reports_stage_and_category_without_exception_or_token(self):
        token = self.root/'read-token'
        token.write_text('SECRET protected token')
        self.config['githubTokenFile'] = str(token)
        self.actor.load.side_effect = FileNotFoundError('SECRET /private/source/path')
        output = io.StringIO()
        with patch('sys.argv', ['production_deploy.py', '--host', 'plan', SHA, '123', '1']), \
                patch.object(os, 'geteuid', return_value=0), patch.object(deploy, 'read_config', return_value=self.config), \
                patch.object(deploy, 'admission'), patch.object(deploy.upgrade.control, 'signal_handlers'), \
                patch.object(deploy.upgrade.control, 'watch_session'), contextlib.redirect_stdout(output):
            self.assertEqual(deploy.main(), 1)
        receipt = json.loads(output.getvalue())
        self.assertEqual(receipt['check'], 'installed-runtime')
        self.assertEqual(receipt['category'], 'missing-file')
        self.assertNotIn('SECRET', output.getvalue())
        self.assertNotIn('/private/', output.getvalue())
        self.runtime.backup.assert_not_called()
        self.actor.apply.assert_not_called()

    def test_same_dispatch_cannot_be_replayed(self):
        self.rollout().run('plan')
        with self.assertRaises(deploy.trust.Refused): self.rollout().run('prepare')
        self.runtime.backup.assert_not_called()

    def test_scheduled_backup_pending_refuses_before_capture(self):
        self.cycle.journal['status'] = 'upload-pending'
        with self.assertRaises(deploy.trust.Refused): self.rollout().run('prepare')
        self.runtime.backup.assert_not_called()

    def test_foreign_restore_or_missing_cleanup_blocks_apply(self):
        self.rollout().run('prepare')
        valid = self.read_evidence(None, 'result')
        for key, value in (('backupId', 'd'*32), ('cleanup', 'unknown'), ('nonce', '0'*64), ('requestSha256', '0'*64)):
            with self.subTest(key=key), patch.object(deploy.evidence, 'read', return_value={**valid, key: value}):
                with self.assertRaises(deploy.trust.Refused): self.rollout().finish()
        self.actor.apply.assert_not_called()

    def test_failed_publication_blocks_restoration(self):
        def failed(*_):
            path = self.root/'publish.json'
            path.write_text(json.dumps({'outcome': 'failure', 'kind': 's3-publish', 'backupId': BACKUP}))
            return path
        self.cycle.invoke.side_effect = failed
        with self.assertRaises(deploy.trust.Refused): self.rollout().run('prepare')
        self.assertNotIn('result', self.events)
        self.actor.apply.assert_not_called()

    def test_session_eof_before_capture_blocks_backup(self):
        self.actor.stage.side_effect = lambda *_: deploy.upgrade.control.CANCEL.set()
        try:
            with self.assertRaises(deploy.trust.Refused): self.rollout().run('prepare')
            self.runtime.backup.assert_not_called()
        finally:
            deploy.upgrade.control.CANCEL.clear()

    def test_interrupt_does_not_adopt_or_reopen_fence(self):
        self.runtime.backup.side_effect = KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt): self.rollout().run('prepare')
        self.runtime.thaw.assert_not_called()
        self.actor.apply.assert_not_called()
        self.assertEqual(json.loads((self.root/'deployments/123-1/deployment.json').read_text())['phase'], 'capturing')


class BackupUnitTests(unittest.TestCase):
    def test_only_existing_execstart_paths_change(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            target = Path('/srv/gameometro/installations/' + 'b'*12 + '-private/candidate')
            configuration = Path('/etc/gameometro/production/backup-cycle.json')
            original = {}
            for unit, command in (('gameometro-backup.service', 'run'), ('gameometro-backup-reconcile.service', 'reconcile')):
                text = ('[Service]\nExecStart=/usr/bin/python3 /srv/gameometro/installations/' + 'a'*12
                        + '-private/candidate/deploy/public-production/bin/backup-cycle ' + command
                        + ' --config ' + str(configuration) + '\nProtectSystem=strict\nReadWritePaths=/existing\n')
                (root/unit).write_text(text)
                original[unit] = text
            timer = root/'gameometro-backup.timer'
            timer.write_text('NEVER CHANGE')
            def systemctl(args, **_):
                if 'FragmentPath' in ' '.join(str(a) for a in args): return str(root/args[2]).encode()
                return b''
            with patch.object(deploy, 'BACKUP_UNITS', root), patch.object(deploy.install, 'secure'), patch.object(deploy, 'invoke', side_effect=systemctl):
                deploy.backup_units(configuration, target)
                self.assertEqual((root/'gameometro-backup.service').read_text(), original['gameometro-backup.service'])
                deploy.backup_units(configuration, target, apply=True)
                for unit in original:
                    self.assertEqual((root/unit).read_text(), original[unit].replace('a'*12, 'b'*12))
                self.assertEqual(timer.read_text(), 'NEVER CHANGE')
                # Different command or extra ExecStart refuses before changing either unit.
                path = root/'gameometro-backup-reconcile.service'
                path.write_text(path.read_text() + 'ExecStart=/bin/sh -c evil\n')
                with self.assertRaises(deploy.trust.Refused): deploy.backup_units(configuration, target, apply=True)

    def test_accepted_keyring_override_is_preserved_and_other_directives_refuse(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            target = Path('/srv/gameometro/installations/' + 'b'*12 + '-private/candidate')
            config = Path('/etc/gameometro/production/backup-cycle.json')
            original = {}
            overrides = {}
            for unit, command in (('gameometro-backup.service', 'run'), ('gameometro-backup-reconcile.service', 'reconcile')):
                source = '[Service]\nExecStart=/usr/bin/python3 /srv/gameometro/installations/'+'a'*12+'-private/candidate/deploy/public-production/bin/backup-cycle '+command+' --config '+str(config)+'\n'
                (root/unit).write_text(source)
                original[unit] = source
                dropin = root/(unit+'.d')/'keyring.conf'
                dropin.parent.mkdir()
                dropin.write_text('[Service]\n# Approved #471 gpg-agent socket path.\nReadWritePaths=/etc/gameometro/production/backup-public-keyring\n')
                overrides[unit] = dropin
            def systemctl(args, **_):
                if 'FragmentPath' in ' '.join(map(str,args)): return str(root/args[2]).encode()
                if 'DropInPaths' in ' '.join(map(str,args)): return str(overrides[args[2]]).encode()
                return b''
            with patch.object(deploy,'BACKUP_UNITS',root), patch.object(deploy.install,'secure'), patch.object(deploy,'invoke',side_effect=systemctl):
                before = {unit:path.read_bytes() for unit,path in overrides.items()}
                deploy.backup_units(config,target,apply=True)
                for unit,path in overrides.items():
                    self.assertEqual(path.read_bytes(),before[unit])
                    self.assertEqual((root/unit).read_text(),original[unit].replace('a'*12,'b'*12))
                modified = overrides['gameometro-backup-reconcile.service']
                modified.write_text(modified.read_text()+'ExecStart=/bin/sh -c unwanted\n')
                with self.assertRaises(deploy.trust.Refused): deploy.backup_units(config,target,apply=True)



class TransportTests(unittest.TestCase):
    def test_ssh_stdin_remains_open_until_remote_finishes(self):
        with tempfile.TemporaryDirectory() as name, patch.dict(os.environ, {'RUNNER_TEMP': name, 'DEPLOY_HOST': 'gameometro-prod.example.ts.net'}):
            # Actual subprocess verifies EOF is not sent; no SSH or network.
            real = subprocess.Popen
            def substitute(*_, **kw):
                return real(['python3', '-c', 'import select,sys;sys.exit(1 if select.select([sys.stdin],[],[],0.1)[0] else 0)'], **kw)
            with patch.object(deploy.subprocess, 'Popen', side_effect=substitute):
                self.assertEqual(deploy.connect(SHA, '123', '1', 'prepare'), 0)

    def test_timeout_terminates_transport_without_retry(self):
        with tempfile.TemporaryDirectory() as name, patch.dict(os.environ, {'RUNNER_TEMP': name, 'DEPLOY_HOST': 'gameometro-prod.example.ts.net'}):
            process = MagicMock()
            process.wait.side_effect = [subprocess.TimeoutExpired('ssh', 10000), 0]
            process.poll.return_value = None
            with patch.object(deploy.subprocess, 'Popen', return_value=process) as spawn:
                with self.assertRaises(subprocess.TimeoutExpired): deploy.connect(SHA, '123', '1', 'prepare')
            self.assertEqual(spawn.call_count, 1)
            process.terminate.assert_called_once()
            process.stdin.close.assert_called_once()


class ReceiptTests(unittest.TestCase):
    def test_verified_receipt_requires_every_proof_and_exact_runner_identity(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as name:
            os.chdir(name)
            try:
                value = {'revision': SHA, 'runId': '123', 'attempt': '1', 'outcome': 'verified', 'phase': 'complete',
                         'mode': 'verified', 'admin': 'verified', 'backup': 'published-and-restored',
                         'backupServices': 'reconciled', 'backupId': BACKUP,
                         'planSha256': 'd'*64, 'adapterFingerprint': 'e'*64, 'version': '0.60.0', 'images': IMAGES,
                         'sourceRunId': '10', 'sourceRunAttempt': '1', 'securityRunId': '20',
                         'services': list(deploy.upgrade.PERSISTENT),
                         'migrations': {'currentRank': 1, 'currentVersion': '1.0', 'targetVersion': '2.0', 'pending': ['V2_0__next.sql']}}
                Path('release.json').write_text(json.dumps({'source_revision': SHA, 'images': IMAGES, 'adapterFingerprint': 'e'*64, 'source_run_id': '10', 'source_run_attempt': '1', 'securityRunId': '20'}))
                Path('raw').write_text(json.dumps(value))
                with patch('builtins.print'):
                    self.assertEqual(deploy.summarize(Path('raw'), SHA, '123', '1', 'false'), 0)
                    for key in ('revision', 'mode', 'admin', 'backupServices', 'backupId', 'images', 'migrations'):
                        invalid = copy.deepcopy(value)
                        invalid.pop(key)
                        Path('raw').write_text(json.dumps(invalid))
                        self.assertEqual(deploy.summarize(Path('raw'), SHA, '123', '1', 'false'), 1, key)
                    Path('raw').write_text(json.dumps(value))
                    Path('release.json').write_text(json.dumps({'source_revision': SHA, 'images': IMAGES, 'adapterFingerprint': 'f'*64}))
                    self.assertEqual(deploy.summarize(Path('raw'), SHA, '123', '1', 'false'), 1)
            finally:
                os.chdir(previous)

    def test_failed_recovery_handoff_keeps_awaiting_phase_in_summary(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as name:
            os.chdir(name)
            try:
                Path('raw').write_text(json.dumps({'kind': 'recovery-request', 'revision': SHA, 'runId': '123', 'attempt': '1'}))
                with patch('builtins.print'):
                    self.assertEqual(deploy.summarize(Path('raw'), SHA, '123', '1', 'false'), 1)
                self.assertEqual(json.loads(Path('deployment-receipt.json').read_text())['phase'], 'awaiting-restore')
            finally:
                os.chdir(previous)

    def test_failed_preflight_summary_retains_only_allowlisted_context(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as name:
            os.chdir(name)
            try:
                base = {'revision': SHA, 'runId': '123', 'attempt': '1', 'outcome': 'failure', 'phase': 'preflight'}
                for check, category, expected in [('upgrade-plan', 'check-refused', True),
                                                   ('SECRET child output', 'SECRET error', False),
                                                   ({'SECRET': 'value'}, ['SECRET'], False)]:
                    Path('raw').write_text(json.dumps({**base, 'check': check, 'category': category, 'reason': 'SECRET'}))
                    output = io.StringIO()
                    with contextlib.redirect_stdout(output):
                        self.assertEqual(deploy.summarize(Path('raw'), SHA, '123', '1', 'true'), 1)
                    receipt = Path('deployment-receipt.json').read_text()
                    self.assertNotIn('SECRET', receipt + output.getvalue())
                    self.assertEqual('check' in json.loads(receipt), expected)
                Path('raw').write_text(json.dumps({**base, 'runId': '456', 'check': 'upgrade-plan'}))
                with patch('builtins.print'):
                    deploy.summarize(Path('raw'), SHA, '123', '1', 'true')
                self.assertNotIn('check', json.loads(Path('deployment-receipt.json').read_text()))
            finally:
                os.chdir(previous)

    def test_exception_categories_never_include_raw_exception_text(self):
        for error, expected in [(FileNotFoundError('SECRET /private/path'), 'missing-file'),
                                (PermissionError('SECRET'), 'permission-denied'),
                                (subprocess.TimeoutExpired('SECRET', 10), 'timeout'),
                                (KeyboardInterrupt('SECRET'), 'interrupted'),
                                (deploy.trust.Refused('SECRET child output'), 'check-refused'),
                                (RuntimeError('SECRET'), 'unexpected')]:
            self.assertEqual(deploy.failure_category(error), expected)

    def test_raw_errors_and_secrets_never_enter_artifact(self):
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as name:
            os.chdir(name)
            try:
                Path('raw').write_text('{"SECRET":"value","outcome":"verified"}')
                self.assertEqual(deploy.summarize(Path('raw'), SHA, '123', '1', 'false'), 1)
                self.assertNotIn('SECRET', Path('deployment-receipt.json').read_text())
                Path('raw').write_text('SECRET: invalid JSON')
                self.assertEqual(deploy.summarize(Path('raw'), SHA, '123', '1', 'false'), 1)
                self.assertNotIn('SECRET', Path('deployment-receipt.json').read_text())
            finally: os.chdir(previous)



if __name__ == '__main__':
    unittest.main()
