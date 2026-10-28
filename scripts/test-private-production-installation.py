#!/usr/bin/env python3
"""Fresh-host bootstrap failures and trust boundaries; never connects to a VPS."""
import contextlib
import ast
import copy
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import socket
import select
import array
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from unittest.mock import MagicMock

import install_private_production as install
import prepare_private_production as prepare
import private_dev_promotion as trust
import validate_private_production_truststore as stores


class FakeHost:
    def __init__(self, failure=None):
        self.calls = []
        self.failure = failure
        self.locked = False
        self.recovery = self

    def event(self, value):
        self.calls.append(value)
        if value == self.failure:
            raise trust.Refused('Synthetic failure: credential/value must never reach receipt.')

    def preflight(self):
        self.event('preflight')
        install.require(not install.JOURNAL.exists(), 'Previous installation journal exists.')
        return {'productionReady': False, 'mode': 'private-first-install-synthetic-only'}

    def claim(self):
        self.event('claim')
        (install.JOURNAL / 'recovery').mkdir()

    def pull(self):
        self.event('pull')

    @contextlib.contextmanager
    def lock(self):
        self.event('lease')
        self.locked = True
        try:
            yield
            self.event('lease-finalize')
        finally:
            self.locked = False

    def fresh(self, *, allow_gate):
        assert self.locked and allow_gate
        self.event('claimed-freshness')

    def initialize(self):
        assert self.locked
        self.event('initialize')

    def up(self, *names):
        assert self.locked
        self.event('up:' + ','.join(names))

    def migrate(self):
        assert self.locked
        self.event('flyway')
        return {'version': '20261023.130000', 'historyFingerprint': 'e' * 32}

    def reader(self):
        assert self.locked
        self.event('reader')

    def smoke(self):
        assert self.locked
        self.event('smoke')

    def close(self, phase):
        self.event('close:' + phase)
        (install.JOURNAL / 'recovery/PENDING.json').write_text('{}')
        return {'status': 'confirmed'}


class Transition(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.scope = contextlib.ExitStack()
        self.scope.enter_context(patch.object(install, 'ADMIN', self.root))
        self.scope.enter_context(patch.object(install, 'JOURNAL', self.root / 'first-install'))
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(self.scope.close)

    def test_preflight_refusal_is_read_only(self):
        host = FakeHost('preflight')
        with self.assertRaises(trust.Refused):
            install.first_install(host)
        self.assertEqual(host.calls, ['preflight'])
        self.assertEqual(list(self.root.iterdir()), [])

    def test_initial_order_and_durable_receipt(self):
        host = FakeHost()
        result = install.first_install(host)
        self.assertEqual(host.calls, ['preflight', 'preflight', 'claim', 'pull', 'lease',
            'claimed-freshness', 'initialize', 'up:postgres', 'flyway', 'reader',
            'up:keycloak,catalogue-localizer,caddy,telemetry,prometheus,grafana,node-exporter,blackbox',
            'up:application', 'smoke', 'lease-finalize'])
        self.assertEqual(result['outcome'], 'success')
        self.assertFalse(result['productionReady'])
        self.assertEqual(result['migrationVersion'], '20261023.130000')
        self.assertEqual(result['flywayHistoryFingerprint'], 'e' * 32)
        self.assertEqual(json.loads((install.JOURNAL / 'installation.json').read_text())['phase'], 'complete')
        with self.assertRaises(trust.Refused):
            install.first_install(FakeHost())

    def test_failures_never_activate_later_phases_and_preserve_journal(self):
        failures = ['claim', 'pull', 'lease', 'claimed-freshness', 'initialize', 'up:postgres', 'flyway',
                    'reader', 'up:keycloak,catalogue-localizer,caddy,telemetry,prometheus,grafana,node-exporter,blackbox',
                    'up:application', 'smoke', 'lease-finalize']
        for failure in failures:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(dir=self.root) as tmp, \
                    patch.object(install, 'ADMIN', Path(tmp)), patch.object(install, 'JOURNAL', Path(tmp) / 'journal'):
                host = FakeHost(failure)
                with self.assertRaises(trust.Refused):
                    install.first_install(host)
                receipt = (install.JOURNAL / 'installation.json').read_text()
                self.assertEqual(json.loads(receipt)['outcome'], 'failure')
                self.assertNotIn('credential/value', receipt)
                if failure in ('up:postgres', 'flyway', 'reader'):
                    self.assertNotIn('up:application', host.calls)
                if failure in ('claim', 'pull', 'lease'):
                    self.assertFalse(any(c.startswith('close:') for c in host.calls))
                else:
                    self.assertTrue((install.JOURNAL / 'recovery/PENDING.json').exists())

    def test_failure_to_close_is_explicit_and_journal_remains(self):
        host = FakeHost('flyway')
        def failed_close(_):
            raise OSError('do not expose diagnostics')
        host.close = failed_close
        with self.assertRaises(trust.Refused):
            install.first_install(host)
        receipt = json.loads((install.JOURNAL / 'installation.json').read_text())
        self.assertEqual(receipt['shutdown']['status'], 'inconclusive')

    def test_failed_final_receipt_write_reconciles_without_false_success(self):
        original = install.durable
        def fail_success(path, value):
            if value.get('outcome') == 'success' and value.get('phase') == 'complete':
                raise OSError('synthetic full disk')
            original(path, value)
        host = FakeHost()
        with patch.object(install, 'durable', side_effect=fail_success), self.assertRaises(OSError):
            install.first_install(host)
        self.assertIn('close:lease-finalize', host.calls)
        self.assertIn('lease-finalize', host.calls)
        self.assertEqual(json.loads((install.JOURNAL / 'installation.json').read_text())['outcome'], 'failure')

    def test_concurrent_installer_cannot_claim_or_pull(self):
        lock = self.root / 'first-install.lock'
        with lock.open('w') as stream, patch.object(install, 'secure'):
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            host = FakeHost()
            with self.assertRaises(BlockingIOError):
                install.first_install(host)
            self.assertEqual(host.calls, ['preflight'])
            self.assertFalse(install.JOURNAL.exists())


class Lease(unittest.TestCase):
    def start(self, root):
        script = install.BOOTSTRAP_LEASE.replace("pathlib.Path('/gate')", f'pathlib.Path({str(root)!r})')
        channel = tempfile.TemporaryDirectory()
        self.addCleanup(channel.cleanup)
        server = socket.socket(socket.AF_UNIX)
        server.bind(channel.name + '/socket'); server.listen(1)
        actor = subprocess.Popen(['python3', '-c', script, '11111111-1111-4111-8111-111111111111', 'c'*64, 'initial', channel.name + '/socket'], stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        actor.retained_fd = None
        try:
            ready = select.select([server, actor.stdout], [], [], 5)[0]
            if server in ready:
                peer, _ = server.accept()
                with peer:
                    _, ancillary, _, _ = peer.recvmsg(2048, socket.CMSG_SPACE(4))
                    descriptors = array.array('i'); descriptors.frombytes(ancillary[0][2])
                    actor.retained_fd = descriptors[0]
        finally: server.close()
        return actor

    def finish(self, process):
        if not process.stdin.closed: process.stdin.close()
        if process.poll() is None:
            process.wait(timeout=5)
        process.stdout.close()
        if process.retained_fd is not None: os.close(process.retained_fd)

    def test_actor_death_retains_actual_flock_until_controller_releases_descriptor(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); actor = self.start(root)
            self.addCleanup(self.finish, actor)
            self.assertEqual(actor.stdout.readline(), b'locked\n')
            actor.kill(); actor.wait(timeout=5)
            with (root / 'ACTOR.lock').open('a') as contender:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(contender, fcntl.LOCK_EX | fcntl.LOCK_NB)
                os.close(actor.retained_fd); actor.retained_fd = None
                fcntl.flock(contender, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_lost_owner_stdin_closes_gate_durably(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            actor = self.start(root)
            self.addCleanup(self.finish, actor)
            self.assertEqual(actor.stdout.readline(), b'locked\n')
            (root / 'READY').touch()
            actor.stdin.close()
            self.assertEqual(actor.wait(timeout=5), 0)
            self.assertFalse((root / 'READY').exists())
            self.assertTrue((root / 'PENDING.json').exists())

    def test_committed_success_keeps_gate_open(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            actor = self.start(root)
            self.addCleanup(self.finish, actor)
            self.assertEqual(actor.stdout.readline(), b'locked\n')
            (root / 'READY').touch()
            actor.stdin.write(b'COMMIT\n')
            actor.stdin.flush()
            self.assertEqual(actor.stdout.readline(), b'committed\n')
            actor.stdin.write(b'RELEASE\n'); actor.stdin.flush()
            self.assertEqual(actor.stdout.readline(), b'released\n')
            self.assertEqual(actor.wait(timeout=5), 0)
            actor.stdin.close()
            self.assertTrue((root / 'READY').exists())
            self.assertFalse((root / 'PENDING.json').exists())

    def test_term_closes_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            actor = self.start(root)
            self.addCleanup(self.finish, actor)
            self.assertEqual(actor.stdout.readline(), b'locked\n')
            (root / 'READY').touch()
            actor.send_signal(signal.SIGTERM)
            actor.wait(timeout=5)
            actor.stdin.close()
            self.assertTrue((root / 'PENDING.json').exists())

    def test_existing_ready_pending_or_identity_fence_is_never_reinitialized(self):
        for name in ('READY', 'PENDING.json', 'IDENTITY-MAINTENANCE.json'):
            with self.subTest(marker=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / name).write_text('original-fence')
                actor = self.start(root)
                self.addCleanup(self.finish, actor)
                actor.stdin.close()
                self.assertEqual(actor.wait(timeout=5), 1)
                self.assertEqual((root / name).read_text(), 'original-fence')

    def test_existing_engine_lease_is_respected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with (root / 'ACTOR.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                actor = self.start(root)
                self.addCleanup(self.finish, actor)
                actor.stdin.close()
                self.assertNotEqual(actor.wait(timeout=5), 0)
                self.assertFalse((root / 'PENDING.json').exists())


class Contracts(unittest.TestCase):
    def test_complete_read_only_preflight_with_real_candidate_sources_and_compose(self):
        # Real export, both renderings, full tree comparison and the unchanged #275
        # verifier. Only privileged host metadata, protected inputs and remote APIs
        # are simulated; no daemon operation or real credential is used.
        campaign = json.loads((install.ROOT / 'docs/research/public-production-candidate-evidence/final-9ffc05a-campaign.json').read_text())
        selected = {'images': campaign['publications'], 'source_run_id': '40192338489',
                    'source_run_attempt': '1', 'securityRunId': 40192338507}
        entries = subprocess.run(['git', 'ls-tree', '-rz', prepare.REVISION], capture_output=True, check=True).stdout
        tree = []
        for item in entries.decode().split('\0'):
            if not item: continue
            metadata, path = item.split('\t', 1)
            mode, kind, digest = metadata.split()
            tree.append({'path': path, 'type': kind, 'mode': mode, 'sha': digest})
        class API:
            def request(self, path):
                assert path == f'/git/trees/{prepare.REVISION}?recursive=1'
                return {'tree': tree, 'truncated': False}
        original_prepare = prepare.command
        original_host_run = install.run
        original_text, original_open, original_exists = Path.read_text, Path.open, Path.exists
        original_sha = install.sha
        calls = []
        def preparation_command(args, **kwargs):
            if args == ['gh', 'auth', 'token']: return b'synthetic-read-only-token'
            return original_prepare(args, **kwargs)
        def registry(images, **_):
            return {n: {'image': image, 'user': '0'} for n, image in images.items()}
        def text(path, *args, **kwargs):
            if str(path) == '/etc/os-release': return 'ID=debian\nVERSION_ID="13"\n'
            return original_text(path, *args, **kwargs)
        def opened(path, *args, **kwargs):
            if str(path) == '/proc/123/cmdline': return io.BytesIO(b'/usr/bin/dockerd\0-H\0fd://\0')
            return original_open(path, *args, **kwargs)
        def exists(path):
            return False if str(path) == '/etc/docker/daemon.json' else original_exists(path)
        def host_run(args, **kwargs):
            calls.append([str(a) for a in args])
            if args[:3] == ['docker', 'compose', 'version']: return b'5.6.1\n'
            if args[:2] == ['docker', 'compose']: return original_host_run(args, **kwargs)
            if args[0] == 'gh': return b'synthetic-read-only-token'
            if args[0] == 'timedatectl': return b'yes\n'
            if args[0] == 'systemctl': return b'123\n' if '--property=MainPID' in args else b''
            if args[0] == 'ss': return b''
            if args[:2] == ['docker', 'info']:
                return json.dumps({'ServerVersion': '29.9.2', 'OSType': 'linux', 'Architecture': 'x86_64', 'CgroupVersion': '2',
                                   'DockerRootDir': '/var/lib/docker', 'SecurityOptions': []}).encode()
            if args[0] == 'ip': return b'[]'
            if args[:2] in (['docker', 'network'], ['docker', 'volume'], ['docker', 'ps']): return b''
            raise AssertionError('Unexpected host command')
        with tempfile.TemporaryDirectory() as tmp, contextlib.ExitStack() as scope:
            bundle = Path(tmp) / 'bundle'
            scope.enter_context(patch.object(prepare, 'TARGET', bundle))
            scope.enter_context(patch.object(prepare, 'command', side_effect=preparation_command))
            scope.enter_context(patch.object(prepare.trust, 'GitHub', return_value=API()))
            scope.enter_context(patch.object(prepare.integration, 'resolve', return_value=selected))
            scope.enter_context(patch.object(prepare.integration, 'verify_local_docker'))
            scope.enter_context(patch.object(prepare, 'registry_images', side_effect=registry))
            receipt = prepare.prepare(bundle, install.ROOT / 'deploy/public-production/runtime.env.example', install.TLS)
            scope.enter_context(patch.object(install, 'ADMIN', Path(tmp) / 'admin'))
            scope.enter_context(patch.object(install, 'JOURNAL', Path(tmp) / 'admin/first-install'))
            scope.enter_context(patch.object(install, 'secure'))
            scope.enter_context(patch.object(os, 'geteuid', return_value=0))
            scope.enter_context(patch.dict(os.environ, {k: v for k, v in os.environ.items() if not k.startswith('COMPOSE_')}, clear=True))
            scope.enter_context(patch.object(Path, 'read_text', text))
            scope.enter_context(patch.object(Path, 'open', opened))
            scope.enter_context(patch.object(Path, 'exists', exists))
            scope.enter_context(patch.object(install, 'sha', side_effect=lambda p: 'c' * 64 if p.parent == install.TLS else original_sha(p)))
            scope.enter_context(patch.object(install, 'verify_operational', return_value=selected))
            scope.enter_context(patch.object(install, 'run', side_effect=host_run))
            host = install.Host(receipt['configurationSha256'], install.operational_fingerprint(), 'a' * 40)
            with patch.object(host, 'inputs'), patch.object(host, 'headroom'):
                summary = host.preflight()
            self.assertFalse(summary['productionReady'])
            self.assertEqual(summary['sourceRevision'], prepare.REVISION)
            self.assertFalse(install.JOURNAL.exists())
            self.assertFalse(any(c[:2] == ['docker', 'run'] or 'pull' in c or 'up' in c for c in calls))

    def test_effective_daemon_flags_cannot_disable_docker_protections(self):
        install.daemon_flags(['/usr/bin/dockerd', '-H', 'fd://', '--containerd=/run/containerd/containerd.sock'])
        for flags in (['--iptables=false'], ['--ip6tables', 'false'], ['--allow-direct-routing'],
                      ['--config-file=/unreviewed/config'], ['--userns-remap=default'],
                      ['--iptables=false', '--iptables=true']):
            with self.subTest(flags=flags), self.assertRaises(trust.Refused):
                install.daemon_flags(['/usr/bin/dockerd', *flags])

    def test_actual_headroom_must_cover_limits_and_preserved_free_disk(self):
        host = install.Host('a', 'b', 'c')
        host.docker_root = Path('/docker')
        host.config = {'services': {'application': {'mem_limit': 1024 ** 3}}}
        for fault in ('memory', 'disk', 'none'):
            memory = 'MemTotal: 8388608 kB\nMemAvailable: ' + ('1048576' if fault == 'memory' else '6291456') + ' kB\n'
            disk = SimpleNamespace(total=100, free=20 if fault == 'disk' else 60)
            with self.subTest(fault=fault), patch.object(Path, 'read_text', return_value=memory), \
                    patch.object(install.shutil, 'disk_usage', return_value=disk):
                if fault == 'none': host.headroom()
                else:
                    with self.assertRaises(trust.Refused): host.headroom()

    def test_operational_inventory_covers_every_local_operator_import(self):
        imported = set()
        for name in install.OPERATIONAL_FILES:
            tree = ast.parse((install.ROOT / name).read_text())
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    modules = [a.name for a in node.names]
                elif isinstance(node, ast.ImportFrom):
                    modules = [node.module or '']
                else:
                    continue
                for module in modules:
                    candidate = 'scripts/' + module.replace('.', '/') + '.py'
                    if (install.ROOT / candidate).is_file(): imported.add(candidate)
        self.assertFalse(imported - set(install.OPERATIONAL_FILES))

    def test_protected_ownership_modes_groups_symlinks_and_acls(self):
        path = MagicMock()
        path.is_absolute.return_value = True
        path.resolve.return_value = path
        path.parents = []
        correct = {'st_mode': 0o100640, 'st_uid': 0, 'st_gid': 20101}
        path.lstat.return_value = SimpleNamespace(**correct)
        with patch.object(os, 'listxattr', return_value=[]):
            install.secure(path, mode=0o640, gid=20101)
            for key, value in [('st_uid', 1000), ('st_gid', 1), ('st_mode', 0o100666), ('st_mode', 0o120640)]:
                path.lstat.return_value = SimpleNamespace(**{**correct, key: value})
                with self.subTest(field=key, value=value), self.assertRaises(trust.Refused):
                    install.secure(path, mode=0o640, gid=20101)
            path.lstat.return_value = SimpleNamespace(**correct)
            path.resolve.return_value = Path('/different/symlink-target')
            with self.assertRaises(trust.Refused): install.secure(path)
            path.resolve.return_value = path
        with patch.object(os, 'listxattr', return_value=['system.posix_acl_access']):
            with self.assertRaises(trust.Refused): install.secure(path)

    def test_tls_and_secret_input_failures_are_before_mutation(self):
        expected = {
            'key-mismatch': 'Private TLS certificate/key mismatch.',
            'store-private-key': 'Invalid JVM certificate-only truststore.',
            'store-missing-ca': 'JVM truststore lacks the reviewed private CA.',
            'empty-secret': 'Empty or oversized required secret.',
            'oversized-secret': 'Empty or oversized required secret.',
            'multiline-secret': 'Invalid required secret encoding/content.',
            'consumer-group': 'Secret consumer lacks protected group.',
            'unresolved-user': 'Unresolved effective image user.',
        }
        # Synthetic OpenSSL/privileged metadata, but a complete hash-bound receipt
        # passes the real receipt verifier before any secret rejection is asserted.
        for fault in (*expected, 'valid'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); tls = root / 'tls'; tls.mkdir(mode=0o700)
                (root / 'metrics').mkdir()
                certificate = '-----BEGIN CERTIFICATE-----\nYWJj\n-----END CERTIFICATE-----'
                for name, value in (('ca.crt', certificate), ('edge.crt', certificate),
                                    ('edge.key', 'synthetic fixture'), ('truststore.p12', 'synthetic PKCS12 fixture')):
                    (tls / name).write_text(value)
                anchor = hashlib.sha256(b'abc').hexdigest()
                receipt = {'schema': 1, 'kind': 'real-jvm-public-truststore-validation', 'outcome': 'success',
                           'image': stores.IMAGE, 'sourceRevision': stores.REVISION,
                           'validatorSha256': stores.digest(Path(stores.__file__)),
                           'truststoreSha256': stores.digest(tls / 'truststore.p12'), 'caSha256': stores.digest(tls / 'ca.crt'),
                           'defaultAnchors': [anchor], 'observedAnchors': [anchor], 'privateAnchors': [anchor]}
                evidence = tls / 'truststore.validation.json'
                evidence.write_text(json.dumps(receipt)); evidence.chmod(0o600)
                host = install.Host('a', 'b', 'c', truststore_evidence=stores.digest(evidence))
                model = Path('/var/lib/gameometro/production/catalogue-models/tcbig-initial')
                host.config = {'services': {
                    'application': {'image': stores.IMAGE, 'group_add': ['20101'], 'secrets': [{'source': 'app'}]},
                    'keycloak': {'group_add': ['20101'], 'secrets': [{'source': 'app'}]},
                    'catalogue-localizer': {'volumes': [{'type': 'bind', 'target': '/model', 'source': str(model), 'read_only': True}]},
                    'node-exporter': {'volumes': [{'type': 'bind', 'target': '/textfile', 'source': str(root / 'metrics'), 'read_only': True}]}},
                    'secrets': {'app': {'file': '/etc/gameometro/production/secrets/application-db-password'}}}
                host.images = {'application': {'user': 'unknown-name' if fault == 'unresolved-user' else '10001'},
                               'keycloak': {'user': '10000'}}
                if fault == 'consumer-group': host.config['services']['keycloak']['group_add'] = ['20102']
                commands = []
                def fake(args, **_):
                    commands.append(args)
                    if args[1] == 'pkey': return b'wrong' if fault == 'key-mismatch' else b'public'
                    if '-pubkey' in args: return b'public'
                    if '-nokeys' in args: return b'' if fault == 'store-missing-ca' else certificate.encode()
                    if '-outform' in args: return b'abc'
                    return b''
                size = 0 if fault == 'empty-secret' else 4097 if fault == 'oversized-secret' else 9
                value = b'bad\nvalue' if fault == 'multiline-secret' else b'synthetic'
                original_read = Path.read_bytes
                original_exists, original_iterdir = Path.exists, Path.iterdir
                def read(path):
                    return value if path.name == 'application-db-password' else original_read(path)
                def metadata(path, **_):
                    return SimpleNamespace(st_size=size if Path(path).name == 'application-db-password' else 128, st_mode=0o755)
                info = SimpleNamespace(returncode=0, stderr=b'Shrouded Keybag' if fault == 'store-private-key' else b'')
                with patch.object(install, 'TLS', tls), patch.object(install, 'ADMIN', root), \
                        patch.object(install, 'secure', side_effect=metadata), patch.object(install, 'run', side_effect=fake), \
                        patch.object(subprocess, 'run', return_value=info), patch.object(Path, 'read_bytes', read), \
                        patch.object(stores.control, 'run', side_effect=fake), \
                        patch.object(stores, 'verify_receipt', wraps=stores.verify_receipt) as checked_store, \
                        patch.object(Path, 'exists', lambda path: True if path == model else original_exists(path)), \
                        patch.object(Path, 'iterdir', lambda path: iter(()) if path == model else original_iterdir(path)), \
                        patch.object(install.integration, 'verify_model') as checked_model:
                    if fault == 'valid':
                        host.inputs()
                        checked_model.assert_called_once_with(model)
                    else:
                        with self.assertRaises(trust.Refused) as caught: host.inputs()
                        self.assertEqual(str(caught.exception), expected[fault])
                        checked_model.assert_not_called()
                    if fault in ('empty-secret', 'oversized-secret', 'multiline-secret', 'consumer-group', 'unresolved-user', 'valid'):
                        checked_store.assert_called_once()
                    else:
                        checked_store.assert_not_called()
                    self.assertTrue(commands and all(c[0] == 'openssl' for c in commands))

    def test_route_collision_refuses_with_read_only_commands(self):
        host = install.Host('a', 'b', 'c')
        host.config = {'networks': {'edge': {'name': 'gameometro-production_edge',
                              'ipam': {'config': [{'subnet': '10.203.1.0/28'}]}}}}
        for route in ('10.203.0.0/16', '100.64.0.0/10'):
            def fake(args, **_):
                if args[0] == 'ip': return json.dumps([{'dst': route}]).encode()
                return b''
            with patch.object(install, 'run', side_effect=fake):
                if route == '10.203.0.0/16':
                    with self.assertRaises(trust.Refused): host.routes()
                else:
                    host.routes()

    def test_special_docker_networks_preserve_route_collision_checks(self):
        host = install.Host('a', 'b', 'c')
        host.config = {'networks': {'edge': {'name': 'gameometro-production_edge',
                              'ipam': {'config': [{'subnet': '10.203.1.0/28'}]}}}}
        special = [{'Name': 'host', 'Driver': 'host', 'IPAM': {'Config': None}},
                   {'Name': 'none', 'Driver': 'null', 'IPAM': {'Config': None}}]
        for route, subnet, refused in (
                ('100.64.0.0/10', '172.17.0.0/16', False),
                ('10.203.0.0/16', '172.17.0.0/16', True),
                ('100.64.0.0/10', '10.203.1.0/29', True)):
            networks = [*special, {'Name': 'bridge', 'Driver': 'bridge',
                                   'IPAM': {'Config': [{'Subnet': subnet}]}}]
            def fake(args, **_):
                if args[0] == 'ip': return json.dumps([{'dst': route}]).encode()
                if args == ['docker', 'network', 'ls', '-q']: return b'0 1 2'
                if args[:3] == ['docker', 'network', 'inspect']:
                    return json.dumps([networks[int(args[3])]]).encode()
                self.fail(f'Unexpected command: {args}')
            with self.subTest(route=route, subnet=subnet), patch.object(install, 'run', side_effect=fake):
                if refused:
                    with self.assertRaisesRegex(trust.Refused, 'overlaps runtime trust subnet'):
                        host.routes()
                else:
                    host.routes()

    def test_unexpected_docker_ipam_is_refused(self):
        host = install.Host('a', 'b', 'c')
        host.config = {'networks': {}}
        networks = [
            {'Name': 'bridge', 'Driver': 'bridge', 'IPAM': {'Config': None}},
            {'Name': 'custom', 'Driver': 'host', 'IPAM': {'Config': None}},
            {'Name': 'host', 'Driver': 'bridge', 'IPAM': {'Config': None}},
            {'Name': 'none', 'Driver': 'bridge', 'IPAM': {'Config': None}},
            {'Name': 'bridge', 'Driver': 'bridge', 'IPAM': {}},
            {'Name': 'bridge', 'Driver': 'bridge', 'IPAM': None},
            {'Name': 'bridge', 'Driver': 'bridge'},
            *({'Name': name, 'Driver': driver, 'IPAM': {'Config': value}}
              for name, driver in (('bridge', 'bridge'), ('host', 'host'), ('none', 'null'))
              for value in ({}, '', [None], ['unexpected'])),
        ]
        for network in networks:
            def fake(args, **_):
                if args[0] == 'ip': return b'[]'
                if args == ['docker', 'network', 'ls', '-q']: return b'network-id'
                if args == ['docker', 'network', 'inspect', 'network-id']:
                    return json.dumps([network]).encode()
                self.fail(f'Unexpected command: {args}')
            with self.subTest(network=network), patch.object(install, 'run', side_effect=fake):
                with self.assertRaisesRegex(trust.Refused, 'Unexpected Docker network IPAM'):
                    host.routes()

    def test_exact_operational_source_binding(self):
        class API:
            def request(self, _):
                files = []
                for name in install.OPERATIONAL_FILES:
                    data = (install.ROOT / name).read_bytes()
                    files.append({'path': name, 'type': 'blob', 'mode': '100644',
                                  'sha': hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()})
                return {'tree': files, 'truncated': False}
        with patch.object(trust, 'discover_promotion') as trusted:
            install.verify_operational(API(), 'a' * 40, install.operational_fingerprint())
            trusted.assert_called_once()
            with self.assertRaises(trust.Refused):
                install.verify_operational(API(), 'a' * 40, '0' * 64)

    def test_export_drift_missing_extra_modes_and_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'scripts').mkdir()
            file = root / 'scripts/probe.py'
            data = b'original'
            file.write_bytes(data)
            file.chmod(0o644)
            tree = {'truncated': False, 'tree': [{'path': 'scripts/probe.py', 'type': 'blob',
                    'mode': '100644', 'sha': hashlib.sha1(b'blob 8\0' + data).hexdigest()}]}
            with patch.object(install, 'secure'):
                install.verify_export(root, tree)
                for change in ('bytes', 'missing', 'extra', 'mode', 'symlink'):
                    with self.subTest(change=change):
                        if file.is_symlink() or file.exists(): file.unlink()
                        extra = root / 'scripts/extra.py'
                        extra.unlink(missing_ok=True)
                        file.write_bytes(data)
                        file.chmod(0o644)
                        if change == 'bytes': file.write_bytes(b'changed')
                        if change == 'missing': file.unlink()
                        if change == 'extra': extra.write_text('unexpected')
                        if change == 'mode': file.chmod(0o755)
                        if change == 'symlink':
                            file.unlink()
                            file.symlink_to('/etc/passwd')
                        with self.assertRaises(trust.Refused):
                            install.verify_export(root, tree)

    def test_unlabelled_volume_network_container_and_listing_failures_refused(self):
        host = install.Host('a' * 64, 'b' * 64, 'c' * 40)
        host.config = {'volumes': {'data': {'name': 'gameometro-production_postgres-data'}},
                       'networks': {'edge': {'name': 'gameometro-production_edge'}}}
        for collision in ('volume', 'network', 'container', 'listing'):
            def fake(args, **_):
                if collision == 'listing': raise trust.Refused('unavailable')
                if '--filter' in args: return b''
                if collision == 'volume' and args[:3] == ['docker', 'volume', 'ls']:
                    return b'gameometro-production_postgres-data\n'
                if collision == 'network' and args[:3] == ['docker', 'network', 'ls']:
                    return b'gameometro-production_edge\n'
                if collision == 'container' and args[:3] == ['docker', 'ps', '-a']:
                    return b'gameometro-production-postgres-1\n'
                return b''
            with self.subTest(collision=collision), patch.object(install, 'run', side_effect=fake):
                with self.assertRaises(trust.Refused): host.fresh()

    def test_wrong_host_refused_before_external_commands(self):
        with patch.object(os, 'geteuid', return_value=1000), patch.object(install, 'run') as runner:
            with self.assertRaises(trust.Refused): install.Host('a', 'b', 'c').preflight()
            runner.assert_not_called()

    def test_clock_context_platform_and_user_remapping_refuse_before_mutation(self):
        for fault in ('context', 'clock', 'daemon-inactive', 'architecture', 'cgroup', 'rootless', 'userns'):
            calls = []
            def fake(args, **_):
                calls.append(args)
                if args[0] == 'timedatectl': return b'no\n' if fault == 'clock' else b'yes\n'
                if args[0] == 'systemctl':
                    if fault == 'daemon-inactive': raise trust.Refused('inactive')
                    return b''
                return json.dumps({'ServerVersion': '29.9.2', 'OSType': 'linux', 'Architecture': 'arm64' if fault == 'architecture' else 'x86_64',
                                   'CgroupVersion': '1' if fault == 'cgroup' else '2',
                                   'SecurityOptions': ['name=' + fault]}).encode()
            with self.subTest(fault=fault), patch.object(os, 'geteuid', return_value=0), \
                    patch.object(Path, 'read_text', return_value='ID=debian\nVERSION_ID="13"\n'), \
                    patch.dict(os.environ, {}, clear=True), patch.object(install, 'run', side_effect=fake), \
                    patch.object(install.integration, 'verify_local_docker',
                                 side_effect=trust.Refused('remote') if fault == 'context' else None):
                with self.assertRaises(trust.Refused): install.Host('a', 'b', 'c').preflight()
                self.assertTrue(all(c[0] == 'timedatectl' or c[:2] in (['docker', 'info'], ['docker', 'compose'], ['systemctl', 'is-active']) for c in calls))
                if fault == 'daemon-inactive': self.assertFalse(any(c[0] == 'docker' for c in calls))

    def test_configuration_changed_or_fence_closed_prevents_next_start(self):
        host = install.Host('a' * 64, 'b' * 64, 'c' * 40)
        class Gate:
            def gate(self, _): return b'closed'
        host.recovery = Gate()
        for digest in ('wrong', 'a' * 64):
            with patch.object(install, 'sha', return_value=digest), patch.object(host, 'compose') as compose:
                with self.assertRaises(trust.Refused): host.up('application')
                compose.assert_not_called()

    def test_unsafe_live_publication_is_rejected(self):
        host = install.Host('a', 'b', 'c')
        host.config = {'services': {'application': {'image': 'accepted', 'networks': {}}}}
        info = {'State': {'Running': True, 'Health': {'Status': 'healthy'}}, 'Config': {'Image': 'accepted'},
                'HostConfig': {'PortBindings': {'443/tcp': [{'HostIp': '::', 'HostPort': '443'}]},
                               'RestartPolicy': {'Name': 'no'}}}
        with patch.object(host, 'compose', return_value=b'one-id'), \
                patch.object(install, 'run', return_value=json.dumps([info]).encode()):
            with self.assertRaises(trust.Refused): host.running(['application'])

    def test_no_update_reset_or_rollback_mode(self):
        for option in ('--update', '--reset', '--rollback', '--force'):
            p = subprocess.run(['python3', install.__file__, option], capture_output=True, timeout=10)
            self.assertEqual(p.returncode, 2)
            self.assertIn(b'unrecognized arguments', p.stderr)


if __name__ == '__main__':
    unittest.main()
