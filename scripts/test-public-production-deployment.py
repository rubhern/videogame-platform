#!/usr/bin/env python3
"""Deployment trust, serialization, refusal and recovery tests; no registry or host."""
import ast
import contextlib
import re
import shutil
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import public_production_deployment as deploy

REV = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
IMAGE = deploy.trust.IMAGE_REPOSITORY + '@' + DIGEST
CONFIG = 'c' * 64
SCHEMA = 'd' * 64
HISTORY = 'e' * 32


def discovered_dependencies(root):
    """Independent reachability audit, not seeded from the fingerprint allowlist.

    Compose owns mounts; operator sections own entry commands. Test/rehearsal-only
    instructions and the provisioning script's --local branch are outside runtime.
    No code is executed and no secret/host path is opened.
    """
    def executable(path):
        return path.is_relative_to(root) and path.is_file() and (path.suffix in ('.py','.sh') or path.stat().st_mode & 0o111
                                   or path.read_bytes().startswith(b'#!'))

    production = root / 'deploy/public-production'
    environment = dict(os.environ, PRODUCTION_SECRETS_DIR='/synthetic/secrets', PRODUCTION_SECRETS_GID='1000',
                       KEYCLOAK_ADMIN_USERNAME='synthetic', PRODUCTION_SMTP_RELAY_HOST='mail.example.test',
                       PRODUCTION_SMTP_RELAY_FROM='no-reply@example.test', PRODUCTION_SMTP_RELAY_PORT='587',
                       APPLICATION_VERSION='0.51.0-SNAPSHOT', CATALOGUE_TRANSLATION_MODEL_DIR='/synthetic/model',
                       APPLICATION_IMAGE='synthetic@sha256:'+'a'*64, KEYCLOAK_IMAGE='synthetic@sha256:'+'a'*64,
                       CADDY_IMAGE='synthetic@sha256:'+'a'*64, CATALOGUE_LOCALIZER_IMAGE='synthetic@sha256:'+'a'*64)
    rendered = subprocess.run(['docker','compose','--env-file','/dev/null','-f',str(production/'compose.yaml'),
        '-f',str(production/'compose.observability.yaml'),'--profile','*','config','--format','json'],
        env=environment, capture_output=True, text=True)
    if rendered.returncode: raise AssertionError(rendered.stderr)
    config = json.loads(rendered.stdout)
    found, pending = set(), []
    for service in config['services'].values():
        for mount in service.get('volumes', []):
            if mount['type'] == 'bind':
                path = Path(mount['source'])
                if path.is_relative_to(root):
                    pending.extend(path.rglob('*') if path.is_dir() else [path])
    section = ''
    for line in (production/'README.md').read_text().splitlines():
        if line.startswith('##'):
            section = line.lower()
        if any(word in section for word in ('validation','exercise','limiter memory','not yet validated')):
            continue
        for name in re.findall(r'^\s*(?:python3(?: -I)?|bash)\s+((?:scripts|deploy)/[a-zA-Z0-9_./-]+)', line):
            pending.append(root/name)
    # Include command entrypoints without relying on RUNTIME_FILES or RUNTIME_DIRECTORIES.
    while pending:
        path = pending.pop()
        if path.is_dir() or path in found:
            continue
        if not path.is_file():
            raise AssertionError('Missing reachable production source: '+str(path.relative_to(root)))
        found.add(path)
        if not executable(path):
            continue
        text = path.read_text()
        if path.name == 'provision-grafana-reader.sh':
            # --local's backend-artifact helper is never invoked by --production.
            prefix, production_branch = text.split('elif [[ "$1" == --production ]]; then',1)
            branch, rest = production_branch.split('\nfi\n',1)
            text = prefix[:prefix.index('if [[ "$1" == --local ]]')] + branch.split('\nelse',1)[0] + rest
        if path.suffix == '.py' or text.startswith('#!/usr/bin/env python'):
            tree = ast.parse(text)
            aliases={'ROOT':root,'repository_root':root}
            def referenced_path(node):
                if isinstance(node,ast.Name): return aliases.get(node.id)
                if (isinstance(node,ast.BinOp) and isinstance(node.op,ast.Div)
                        and isinstance(node.right,ast.Constant) and isinstance(node.right.value,str)):
                    base=referenced_path(node.left)
                    return base/node.right.value if base is not None else None
                return None
            for statement in tree.body:
                if isinstance(statement,ast.Assign):
                    value=referenced_path(statement.value)
                    if value is not None:
                        for target in statement.targets:
                            if isinstance(target,ast.Name): aliases[target.id]=value
            if path.name == 'test-public-production-runtime.py':
                # --env-file returns before the deliberate negative-fixture mutations.
                tree.body=[n for n in tree.body if not isinstance(n,ast.FunctionDef) or n.name!='compose_mutations']
            for node in ast.walk(tree):
                # File operands, not arbitrary string inventories/documentation examples.
                if isinstance(node,ast.BinOp) and isinstance(node.op,ast.Div):
                    candidate=referenced_path(node)
                    if candidate is not None and candidate.is_relative_to(root) and candidate.is_file():
                        pending.append(candidate)
                if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute) and node.func.attr == 'with_name' and node.args and isinstance(node.args[0],ast.Constant):
                    candidate=path.parent/node.args[0].value
                    if candidate.is_file(): pending.append(candidate)
            for node in tree.body:
                if isinstance(node, (ast.Import,ast.ImportFrom)):
                    modules = [a.name for a in node.names] if isinstance(node,ast.Import) else [node.module or '']
                    for module in modules:
                        candidate=path.parent/(module.split('.')[0]+'.py')
                        if candidate.is_file(): pending.append(candidate)
        else:
            shell = '\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('#'))
            for name in re.findall(r'(?:scripts|deploy|docker|tools)/[a-zA-Z0-9_./-]+',shell):
                candidate=root/name
                if candidate.is_file(): pending.append(candidate)
    return {p.relative_to(root).as_posix() for p in found}


class Runtime:
    def __init__(self, directory):
        self.directory, self.evidence = directory, directory / 'evidence'
        self.evidence.mkdir()
        self.project = 'vgp-production-test-123456abcdef'
        self.file = directory / 'compose.json'
        self.selected = {'source_revision': REV, 'image_digest': DIGEST, 'images': {'application': IMAGE},
                         'applicationVersion': '0.30.0-SNAPSHOT', 'source_run_id': '123',
                         'source_run_attempt': '1', 'securityRunId': 456}
        self.config = {'services': {n: {'image': IMAGE if n in ('application', 'migration') else n + '@sha256:' + 'f' * 64}
                                   for n in ('application', 'migration', 'postgres', 'keycloak', 'caddy')},
                       'networks': {'data': {'name': self.project + '-data', 'internal': True}},
                       'volumes': {'data': {'name': self.project + '-data'}}, 'secrets': {}}
        secrets = directory / 'secrets'
        secrets.mkdir(mode=0o750)
        path = secrets / 'application-db-password'
        path.write_text('never-export-this-secret')
        path.chmod(0o640)
        self.config['secrets']['db'] = {'file': str(path)}
        self.save_config()
        self.calls = []
        self.app_image = None
        self.running = False
        self.migration_exit = 0
        self.history = HISTORY
        self.unhealthy = False
        self.smoke_failure = False

    def save_config(self):
        self.file.write_text(json.dumps(self.config))
        self.receipt = {'configurationSha256': hashlib.sha256(self.file.read_bytes()).hexdigest()}

    def cid(self, service):
        return '' if service == 'application' and self.app_image is None else service

    def deployment_lease(self):
        return contextlib.nullcontext()

    def inspect_image(self, image):
        return subprocess.CompletedProcess([], 0, json.dumps([{'Os': 'linux', 'Architecture': 'amd64',
                  'Config': {'Labels': {'org.opencontainers.image.revision': self.selected['source_revision'],
                                        'org.opencontainers.image.version': self.selected['applicationVersion']}}}]), '')

    def inspect(self, cid):
        image = self.app_image if cid == 'application' else self.config['services'][cid]['image']
        running = self.running if cid == 'application' else True
        health = 'unhealthy' if cid == 'application' and self.unhealthy else 'healthy'
        return subprocess.CompletedProcess([], 0, json.dumps([{'Config': {'Image': image},
                                                             'State': {'Running': running, 'Health': {'Status': health}}}]), '')

    def sql(self, query):
        return '20261020.120000' if 'LIMIT 1' in query else self.history

    def compose(self, *args, **kwargs):
        self.calls.append(args)
        if args[0] == 'stop':
            self.running = False
        if args[0] == 'exec':
            return subprocess.CompletedProcess(args, 0, json.dumps({'build': {'version': self.selected['applicationVersion'], 'sourceRevision': self.selected['source_revision']}}), '')
        code = self.migration_exit if args[0] == 'run' else 0
        return subprocess.CompletedProcess(args, code, 'private actor output', 'never-export-this-secret')

    def stop_application(self):
        return self.compose('stop', '--timeout', '30', 'application')

    def up(self, service, *, no_deps=False):
        assert no_deps, "Deployment must never recreate dependencies"
        self.calls.append(('activate', service))
        self.running, self.app_image = True, self.selected['images']['application']

    def probe(self, *args, **kwargs):
        self.calls.append(('smoke',))
        if self.smoke_failure:
            raise RuntimeError('never-export-this-secret')


class Deployments(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.runtime = Runtime(Path(self.tmp.name))
        self.api = unittest.mock.Mock()
        self.api.request.return_value = {'status': 'ahead'}
        self.deployer = deploy.Deployment(self.runtime, self.api, CONFIG, SCHEMA)
        self.selection = patch.object(deploy, 'verify_selection').start()
        self.configuration = patch.object(deploy, 'verify_configuration', return_value=SCHEMA).start()
        self.addCleanup(patch.stopall)

    def receipts(self):
        return [json.loads(p.read_text()) for p in self.runtime.evidence.glob('deployment-*.json')]

    def test_success_and_repeat_use_identical_immutable_actor_before_activation(self):
        first = self.deployer.run()
        second = self.deployer.run()
        self.assertEqual(first['outcome'], 'success')
        self.assertEqual(second['previousImage'], IMAGE)
        self.assertEqual(second['migrationVersion'], '20261020.120000')
        self.assertEqual(self.runtime.calls, [('stop', '--timeout', '30', 'application'),
                         ('run', '--rm', '--no-deps', 'migration'), ('activate', 'application'),
                         ('exec', '-T', 'application', 'wget', '-qO-', '-T', '15', 'http://127.0.0.1:8081/actuator/info'), ('smoke',)] * 2)
        self.assertEqual(len(self.receipts()), 2)
        self.selection.assert_called_with(self.api, self.runtime.selected)
        self.assertNotIn('never-export-this-secret', ''.join(p.read_text() for p in self.runtime.evidence.iterdir()))

    def test_first_release_and_retry_do_not_require_a_preexisting_flyway_table(self):
        history_available = False
        original = self.runtime.compose
        original_sql = self.runtime.sql
        def sql(query):
            if not history_available:
                raise AssertionError('Flyway history was queried before the migration actor created it')
            return original_sql(query)
        def compose(*args, **kwargs):
            nonlocal history_available
            result = original(*args, **kwargs)
            if args[0] == 'run' and result.returncode == 0:
                history_available = True
            return result
        with patch.object(self.runtime, 'sql', side_effect=sql), patch.object(self.runtime, 'compose', side_effect=compose):
            self.runtime.migration_exit = 1
            with self.assertRaises(deploy.trust.Refused):
                self.deployer.run()
            self.runtime.migration_exit = 0
            self.assertEqual(self.deployer.run()['outcome'], 'success')

    def test_invalid_provenance_and_configuration_prevent_all_mutation(self):
        for verifier in (self.selection, self.configuration):
            with self.subTest(verifier=verifier):
                verifier.side_effect = deploy.trust.Refused('private evidence body')
                with self.assertRaises(deploy.trust.Refused):
                    self.deployer.run()
                verifier.side_effect = None
                self.assertFalse(self.runtime.calls)
                self.assertFalse(self.deployer.state.exists())
        self.assertTrue(all(r['failureReason'] == 'preflight-refused' for r in self.receipts()))

    def test_concurrent_attempt_has_own_receipt_and_cannot_overwrite_state(self):
        self.deployer.run()
        before = self.deployer.state.read_bytes()
        self.runtime.calls.clear()
        with self.deployer.lock.open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(deploy.trust.Refused, 'Concurrent'):
                self.deployer.run()
        self.assertEqual(self.deployer.state.read_bytes(), before)
        self.assertEqual(self.runtime.calls, [])
        self.assertIn('concurrent-or-lock-unavailable', [r['failureReason'] for r in self.receipts()])

    def test_failed_migration_never_activates_and_forward_retry_is_possible(self):
        self.deployer.run()
        self.runtime.selected = {**self.runtime.selected, 'image_digest': DIGEST.replace('b', '1'), 'images': {'application': IMAGE.replace('b' * 64, '1' * 64)}}
        self.runtime.config['services']['application']['image'] = self.runtime.selected['images']['application']
        self.runtime.config['services']['migration']['image'] = self.runtime.selected['images']['application']
        self.runtime.save_config()
        self.runtime.calls.clear()
        self.runtime.migration_exit = 1
        with self.assertRaisesRegex(deploy.trust.Refused, 'Migration failed'):
            self.deployer.run()
        self.assertFalse(any(c[0] == 'activate' for c in self.runtime.calls))
        self.assertFalse(self.runtime.running)
        self.assertEqual(self.runtime.app_image, IMAGE)
        self.runtime.migration_exit = 0
        self.assertEqual(self.deployer.run()['outcome'], 'success')

    def test_readiness_and_smoke_failure_stop_candidate_and_preserve_failure(self):
        for flag, phase in (('unhealthy', 'readiness'), ('smoke_failure', 'smoke')):
            setattr(self.runtime, flag, True)
            with self.subTest(phase=phase), self.assertRaises(Exception):
                self.deployer.run()
            self.assertFalse(self.runtime.running)
            state = json.loads(self.deployer.state.read_text())
            self.assertEqual(state['outcome'], 'failure')
            self.assertEqual(state['phase'], phase)
            self.assertNotIn('never-export-this-secret', self.deployer.state.read_text())
            setattr(self.runtime, flag, False)
            self.deployer.run()

    def older_release(self):
        self.deployer.run()
        self.runtime.calls.clear()
        self.runtime.selected['source_revision'] = 'f' * 40
        self.api.request.return_value = {'status': 'behind'}

    def test_rollback_requires_explicit_request_and_identical_schema_and_history(self):
        self.older_release()
        with self.assertRaisesRegex(deploy.trust.Refused, 'explicit'):
            self.deployer.run()
        self.deployer.schema = '1' * 64
        self.configuration.return_value = self.deployer.schema
        with self.assertRaisesRegex(deploy.trust.Refused, 'compatibility'):
            self.deployer.run(rollback=True)
        self.deployer.schema = SCHEMA
        self.configuration.return_value = SCHEMA
        self.runtime.history = '2' * 32
        with self.assertRaisesRegex(deploy.trust.Refused, 'compatibility'):
            self.deployer.run(rollback=True)
        self.assertFalse(self.runtime.calls)
        self.runtime.history = HISTORY
        self.assertEqual(self.deployer.run(rollback=True)['outcome'], 'success')

    def test_closed_recovery_gate_or_busy_recovery_lease_prevents_mutation(self):
        for reason in ('Recovery authorization is closed', 'Another recovery actor is active'):
            with patch.object(self.runtime, 'deployment_lease', side_effect=deploy.trust.Refused(reason)):
                with self.assertRaises(deploy.trust.Refused):
                    self.deployer.run()
            self.assertFalse(self.runtime.calls)
            self.assertFalse(self.deployer.state.exists())

    def test_applied_migrations_are_never_reverted(self):
        self.older_release()
        self.runtime.history = '0' * 32
        with self.assertRaises(deploy.trust.Refused):
            self.deployer.run(rollback=True)
        self.assertFalse(self.runtime.calls)

    def test_render_drift_exposure_missing_secret_and_actor_mismatch_refused(self):
        for mutate in (lambda: self.runtime.file.write_text('{}'),
                       lambda: self.runtime.config['services']['application'].update(ports=['443:443']),
                       lambda: self.runtime.config['networks']['data'].update(internal=False),
                       lambda: self.runtime.config['services']['migration'].update(image='latest'),
                       lambda: (self.runtime.directory / 'secrets/application-db-password').unlink()):
            # A fresh runtime for each independent violation.
            with tempfile.TemporaryDirectory() as directory:
                self.runtime = Runtime(Path(directory))
                self.deployer = deploy.Deployment(self.runtime, self.api, CONFIG, SCHEMA)
                mutate()
                if self.runtime.file.read_text() != '{}':
                    self.runtime.save_config()
                with self.assertRaises(deploy.trust.Refused):
                    self.deployer.run()
                self.assertFalse(self.runtime.calls)

    def test_wrong_candidate_labels_or_digest_are_refused_before_migration(self):
        with patch.object(self.runtime, 'inspect_image', return_value=subprocess.CompletedProcess([], 0,
                          json.dumps([{'Os': 'linux', 'Architecture': 'arm64', 'Config': {'Labels': {}}}]), '')):
            with self.assertRaisesRegex(deploy.trust.Refused, 'platform'):
                self.deployer.run()
        self.runtime.selected['image_digest'] = 'sha256:' + '0' * 64
        with self.assertRaisesRegex(deploy.trust.Refused, 'digest'):
            self.deployer.run()
        self.assertFalse(self.runtime.calls)

    def test_failure_to_stop_is_reported_without_raw_output(self):
        original = self.runtime.compose
        def compose(*args, **kwargs):
            if args[0] == 'stop':
                raise RuntimeError('never-export-this-secret')
            return original(*args, **kwargs)
        with patch.object(self.runtime, 'compose', side_effect=compose), self.assertRaises(RuntimeError):
            self.deployer.run()
        state = json.loads(self.deployer.state.read_text())
        self.assertEqual(state['stopResult'], 'failed-private-operator-action-required')
        self.assertNotIn('never-export-this-secret', self.deployer.state.read_text())
        self.assertFalse(any(c[0] == 'activate' for c in self.runtime.calls))

    def test_runtime_info_mismatch_fails_smoke_and_stops_candidate(self):
        original = self.runtime.compose
        def compose(*args, **kwargs):
            if args[0] == 'exec':
                return subprocess.CompletedProcess(args, 0, '{"build":{"sourceRevision":"wrong"}}', '')
            return original(*args, **kwargs)
        with patch.object(self.runtime, 'compose', side_effect=compose), self.assertRaisesRegex(deploy.trust.Refused, 'metadata'):
            self.deployer.run()
        self.assertFalse(self.runtime.running)
        self.assertEqual(json.loads(self.deployer.state.read_text())['failureReason'], 'smoke-failed')

    def test_dependency_missing_and_unrecorded_or_interrupted_state_refused(self):
        with patch.object(self.runtime, 'cid', return_value=''):
            with self.assertRaises(deploy.trust.Refused):
                self.deployer.run()
        self.runtime.app_image = IMAGE
        with self.assertRaises(deploy.trust.Refused):
            self.deployer.run()
        self.runtime.app_image = None
        deploy.atomic_json(self.deployer.state, {'outcome': 'running'})
        with self.assertRaises((KeyError, deploy.trust.Refused)):
            self.deployer.run()
        self.assertFalse(self.runtime.calls)

    def test_interruption_is_sanitized_and_stops_application(self):
        with patch.object(self.runtime, 'up', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.deployer.run()
        self.assertEqual(json.loads(self.deployer.state.read_text())['outcome'], 'failure')
        self.assertFalse(self.runtime.running)


class Inputs(unittest.TestCase):
    def test_real_host_is_blocked_without_any_docker_call(self):
        with patch.object(deploy.sys, 'argv', ['deploy']), self.assertRaisesRegex(deploy.trust.Refused, 'Real-host'):
            deploy.main()

    def test_manual_rehearsal_requires_all_reviewed_inputs(self):
        with patch.object(deploy.sys, 'argv', ['deploy', '--rehearsal']), self.assertRaisesRegex(deploy.trust.Refused, 'required'):
            deploy.main()

    def test_manual_command_dispatches_exact_reviewed_inputs_to_isolated_harness(self):
        import public_production_integration as integration
        args = ['deploy', '--rehearsal', '--revision', REV, '--digest', DIGEST,
                '--configuration-fingerprint', CONFIG, '--evidence', '/unused-evidence']
        with patch.object(deploy.sys, 'argv', args), patch.object(integration, 'main', return_value=0) as main:
            self.assertEqual(deploy.main(), 0)
        self.assertEqual(main.call_args.args[0], ['--revision', REV, '--digest', DIGEST,
                         '--configuration-fingerprint', CONFIG, '--evidence', '/unused-evidence', '--stage', 'E'])

    def test_configuration_checks_actual_bytes_against_exact_source(self):
        fingerprint = deploy.local_configuration()
        self.assertRegex(fingerprint, r'^[0-9a-f]{64}$')
        with patch.object(deploy, 'inventory', return_value='0' * 64), self.assertRaisesRegex(deploy.trust.Refused, 'fingerprint'):
            deploy.verify_configuration(unittest.mock.Mock(), REV, fingerprint)

    def test_independently_discovered_production_dependencies_are_fingerprinted(self):
        required = discovered_dependencies(deploy.ROOT)
        self.assertIn('scripts/provision-grafana-reader.sh', required)
        self.assertIn('scripts/test-public-production-runtime.py', required)
        self.assertNotIn('scripts/backend-artifact.sh', required)  # --local-only provisioning branch
        self.assertNotIn('deploy/private-dev/compose.yaml', required)
        self.assertFalse({p for p in required if not deploy.runtime_path(p)})

    def test_new_untracked_executable_references_fail_completeness_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('deploy/public-production','deploy/private-dev/bin','deploy/private-dev/lib',
                         'deploy/private-dev/grafana','docker/postgres/init','scripts'):
                shutil.copytree(deploy.ROOT/name,root/name,ignore=shutil.ignore_patterns('__pycache__'))
            unknown = root/'scripts/new-production-dependency.py'; unknown.write_text('print("new")\n'); unknown.chmod(0o755)
            (root/'scripts/new_production_dependency.py').write_text('print("new imported actor")\n')
            actor=root/'scripts/new-production-actor'; actor.write_text('#!/bin/sh\nexit 0\n'); actor.chmod(0o755)
            self.assertFalse(deploy.runtime_path(unknown.relative_to(root).as_posix()))
            for source, addition in (
                ('deploy/public-production/README.md','\n## Operator action\npython3 scripts/new-production-dependency.py\n'),
                ('deploy/public-production/bin/recovery',"\nNEW_ACTOR = ROOT / 'scripts/new-production-dependency.py'\n"),
                ('scripts/test-public-production-runtime.py','\nimport new_production_dependency\n'),
                ('deploy/public-production/bin/recovery',"\nNEW_ACTOR = ROOT / 'scripts' / 'new-production-actor'\n"),
                ('deploy/public-production/compose.yaml','\n# placeholder\n')):
                path=root/source; old=path.read_text()
                if source.endswith('compose.yaml'):
                    # A new bind in the actual service graph, not a curated mutation list.
                    changed=re.sub(r'(?m)^( +)volumes:\n',r'\1volumes:\n\1  - ../../scripts/new-production-dependency.py:/opt/new.py:ro\n',old,count=1)
                else: changed=old+addition
                path.write_text(changed)
                missing={p for p in discovered_dependencies(root) if not deploy.runtime_path(p)}
                expected='scripts/new_production_dependency.py' if source.startswith('scripts/') else ('scripts/new-production-actor' if 'new-production-actor' in addition else 'scripts/new-production-dependency.py')
                self.assertIn(expected, missing)
                path.write_text(old)

    def test_new_nonexecutable_data_dependencies_fail_completeness_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name in ('deploy/public-production','deploy/private-dev/bin','deploy/private-dev/lib',
                         'deploy/private-dev/grafana','docker/postgres/init','scripts'):
                shutil.copytree(deploy.ROOT/name,root/name,ignore=shutil.ignore_patterns('__pycache__'))
            source=root/'deploy/public-production/bin/recovery'; original=source.read_text()
            for extension in ('sql','json'):
                with self.subTest(extension=extension):
                    data=root/f'scripts/new-production-data.{extension}'
                    # Non-executable data is discovered but must never be parsed as code.
                    data.write_text('SELECT 1;' if extension=='sql' else '{"data":"not executable"}')
                    data.chmod(0o644)
                    source.write_text(original+f"\nDATA = ROOT / 'scripts/new-production-data.{extension}'\n")
                    discovered=discovered_dependencies(root)
                    missing={name for name in discovered if not deploy.runtime_path(name)}
                    self.assertIn(data.relative_to(root).as_posix(),missing)
                    source.write_text(original)

    def test_symlink_directories_are_refused_not_skipped(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name in deploy.RUNTIME_DIRECTORIES: shutil.copytree(deploy.ROOT/name,root/name,dirs_exist_ok=True)
            for name in deploy.RUNTIME_FILES:
                target=root/name; target.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(deploy.ROOT/name,target)
            link=root/'deploy/public-production/hidden'; link.symlink_to(root/'deploy/private-dev/lib',target_is_directory=True)
            with patch.object(deploy,'ROOT',root), self.assertRaisesRegex(deploy.trust.Refused,'directory'): deploy.local_configuration()
            link.unlink()
            # Explicit inputs can have a symlinked parent too.
            shutil.rmtree(root/'deploy/private-dev/lib'); (root/'deploy/private-dev/lib').symlink_to(deploy.ROOT/'deploy/private-dev/lib',target_is_directory=True)
            with patch.object(deploy,'ROOT',root), self.assertRaisesRegex(deploy.trust.Refused,'Unsafe runtime'): deploy.local_configuration()

    def test_transitive_dependencies_mutations_invalidate_local_and_trusted_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for directory_name in deploy.RUNTIME_DIRECTORIES:
                shutil.copytree(deploy.ROOT / directory_name, root / directory_name, dirs_exist_ok=True)
            for name in deploy.RUNTIME_FILES:
                destination = root / name; destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(deploy.ROOT / name, destination)
            with patch.object(deploy, 'ROOT', root):
                entries = []
                for path in root.rglob('*'):
                    if path.is_file() and deploy.runtime_path(path.relative_to(root).as_posix()):
                        data = path.read_bytes(); entries.append({'path':path.relative_to(root).as_posix(), 'type':'blob',
                            'mode':'100755' if path.stat().st_mode & 0o111 else '100644',
                            'sha':hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest()})
                tree = {'truncated':False,'tree':entries}; api = unittest.mock.Mock(); api.request.return_value=tree
                expected = deploy.local_configuration()
                self.assertEqual(expected, deploy.inventory(tree, deploy.runtime_path))
                paths = [*deploy.RUNTIME_FILES, 'deploy/private-dev/grafana/dashboards/vgp-runtime.json',
                         'deploy/private-dev/grafana/bin/provision-notifications',
                         'deploy/private-dev/grafana/provisioning/datasources/postgres.yaml']
                for name in paths:
                    with self.subTest(path=name):
                        path=root/name; old=path.read_bytes(); path.write_bytes(old+b'\n# mutation')
                        with self.assertRaisesRegex(deploy.trust.Refused,'fingerprint'):
                            deploy.verify_configuration(api, REV, expected)
                        # Supplying the changed local hash still cannot authorize stale trusted sources.
                        with self.assertRaisesRegex(deploy.trust.Refused,'fingerprint'):
                            deploy.verify_configuration(api, REV, deploy.local_configuration())
                        path.write_bytes(old)
                added=root/'deploy/private-dev/grafana/dashboards/new.json'; added.write_text('{}')
                self.assertNotEqual(expected, deploy.local_configuration())
                added.unlink()
                (root/'deploy/private-dev/lib/personal_data_archive.py').unlink()
                with self.assertRaises((FileNotFoundError, deploy.trust.Refused)): deploy.local_configuration()

    def test_fingerprint_includes_source_mode_and_inventory_rejects_unsafe_or_missing(self):
        entries = {'truncated': False, 'tree': [{'path': 'deploy/public-production/compose.yaml', 'type': 'blob',
                                               'mode': '100644', 'sha': 'a' * 40}]}
        first = deploy.inventory(entries, deploy.runtime_path)
        entries['tree'][0]['mode'] = '100755'
        self.assertNotEqual(first, deploy.inventory(entries, deploy.runtime_path))
        entries['tree'][0]['mode'] = '120000'
        with self.assertRaises(deploy.trust.Refused):
            deploy.inventory(entries, deploy.runtime_path)
        with self.assertRaises(deploy.trust.Refused):
            deploy.inventory({'truncated': False, 'tree': []}, deploy.runtime_path)


if __name__ == '__main__':
    unittest.main()
