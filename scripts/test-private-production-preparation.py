#!/usr/bin/env python3
"""Focused #276 preparation tests: fake registry and no runtime mutations."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import prepare_private_production as adapter
import private_dev_promotion as trust


class Preparation(unittest.TestCase):
    def setUp(self):
        services = {name: {'image': 'example@sha256:' + '1' * 64}
                    for name in ('telemetry', 'prometheus', 'grafana', 'node-exporter', 'blackbox',
                                 'application', 'migration', 'caddy')}
        services['application'].update(environment={}, volumes=[])
        services['caddy'].update(ports=[{'target': 443, 'published': '443'}],
                                 volumes=[adapter.bind('/candidate/Caddyfile', '/etc/caddy/Caddyfile')])
        self.base = {'name': adapter.PROJECT, 'services': services,
                     'networks': {'edge': {'internal': True}}}
        self.file, self.tls = Path('/protected/Caddyfile.private'), Path('/protected/tls')
        self.config = adapter.adapt(self.base, self.file, self.tls)

    def test_private_delta_preserves_candidate(self):
        before = copy.deepcopy(self.base)
        config = adapter.adapt(self.base, self.file, self.tls)
        adapter.validate(self.base, config, self.file, self.tls)
        self.assertEqual(before, self.base)
        self.assertFalse(config['services']['caddy']['ports'])
        self.assertTrue(all(s['restart'] == 'no' for s in config['services'].values()))
        self.assertFalse(config['networks']['edge']['enable_ipv6'])
        self.assertEqual(config['networks']['edge']['driver_opts']['com.docker.network.bridge.gateway_mode_ipv4'], 'nat')
        self.assertEqual(config['services']['application']['environment']['JAVA_TOOL_OPTIONS'], adapter.JAVA_TLS)
        self.assertTrue(all(v['read_only'] for v in config['services']['caddy']['volumes']))

    def test_combined_overlay_cannot_reintroduce_ports_or_weaken_runtime(self):
        for name in self.config['services']:
            for key, value in [('ports', [{'host_ip': '127.0.0.1', 'target': 443, 'published': '443'}]),
                               ('network_mode', 'host'), ('privileged', True), ('build', '.')]:
                with self.subTest(service=name, field=key):
                    config = copy.deepcopy(self.config)
                    config['services'][name][key] = value
                    with self.assertRaises(trust.Refused):
                        adapter.validate(self.base, config, self.file, self.tls)

    def test_automatic_restart_ipv6_and_unprotected_routing_are_refused(self):
        for mutation in ('restart', 'ipv6', 'routing'):
            config = copy.deepcopy(self.config)
            if mutation == 'restart': config['services']['application']['restart'] = 'unless-stopped'
            if mutation == 'ipv6': config['networks']['edge']['enable_ipv6'] = True
            if mutation == 'routing':
                config['networks']['edge']['driver_opts']['com.docker.network.bridge.gateway_mode_ipv4'] = 'nat-unprotected'
            with self.subTest(mutation=mutation), self.assertRaises(trust.Refused):
                adapter.validate(self.base, config, self.file, self.tls)

    def test_no_unreviewed_environment_mount_image_or_network_delta(self):
        for field in ('environment', 'volumes', 'image', 'networks'):
            with self.subTest(field=field):
                config = copy.deepcopy(self.config)
                config['services']['application'][field] = 'unexpected'
                with self.assertRaises(trust.Refused):
                    adapter.validate(self.base, config, self.file, self.tls)

    def test_observability_required(self):
        base = copy.deepcopy(self.base)
        del base['services']['grafana']
        with self.assertRaises(trust.Refused):
            adapter.validate(base, adapter.adapt(base, self.file, self.tls), self.file, self.tls)

    def test_missing_or_ambiguous_edge_mount_refused(self):
        for mounts in ([], self.base['services']['caddy']['volumes'] * 2):
            base = copy.deepcopy(self.base)
            base['services']['caddy']['volumes'] = mounts
            with self.assertRaises(trust.Refused):
                adapter.adapt(base, self.file, self.tls)

    def test_tls_keeps_hosts_and_existing_admission(self):
        original = 'gameometro.com {\n import edge_admission\n}\nauth.gameometro.com {\n import edge_admission\n}\n'
        result = adapter.private_caddy(original)
        self.assertEqual(result.count('tls /run/private-tls/edge.crt /run/private-tls/edge.key'), 2)
        self.assertEqual(result.count('import edge_admission'), 2)
        with self.assertRaises(trust.Refused):
            adapter.private_caddy('gameometro.com {\n}')

    def test_remote_registry_contract_and_no_mutation(self):
        calls = []
        def fake(args):
            calls.append(args)
            return json.dumps({'os': 'linux', 'architecture': 'amd64', 'config': {'Labels': {
                'org.opencontainers.image.revision': adapter.REVISION,
                'org.opencontainers.image.version': adapter.VERSION}}}).encode()
        adapter.registry_images({'application': 'image@sha256:' + '1' * 64}, fake)
        self.assertEqual(calls[0][:4], ['docker', 'buildx', 'imagetools', 'inspect'])
        for value in ({'os': 'linux', 'architecture': 'arm64'},
                      {'os': 'linux', 'architecture': 'amd64', 'config': {'Labels': {}}},
                      {'os': 'linux', 'architecture': 'amd64', 'config': {'Labels': {
                          'org.opencontainers.image.revision': adapter.REVISION,
                          'org.opencontainers.image.version': 'wrong'}}}):
            with self.assertRaises(trust.Refused):
                adapter.registry_images({'application': 'image'}, lambda _: json.dumps(value).encode())

    def test_multiplatform_registry_and_shared_migration_image(self):
        calls = []
        def fake(args):
            calls.append(args)
            return json.dumps({'linux/amd64': {'os': 'linux', 'architecture': 'amd64',
                                               'config': {'Labels': {}}}}).encode()
        result = adapter.registry_images({'postgres': 'one-image', 'migration': 'one-image'}, fake)
        self.assertEqual(len(calls), 1)
        self.assertEqual(result['postgres']['architecture'], 'amd64')

    def test_existing_bundle_refused_before_any_external_command(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(adapter, 'command') as runner:
            with self.assertRaises(trust.Refused):
                adapter.prepare(Path(tmp), Path('/missing/env'), self.tls)
            runner.assert_not_called()

    def test_real_candidate_export_and_combined_render_without_registry_or_daemon_mutation(self):
        campaign = json.loads((adapter.ROOT / 'docs/research/public-production-candidate-evidence/final-9ffc05a-campaign.json').read_text())
        actual_command = adapter.command
        calls = []
        def local_only(args, **kwargs):
            calls.append([str(a) for a in args])
            if args == ['gh', 'auth', 'token']:
                return b'fake-read-only-token'
            return actual_command(args, **kwargs)
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(adapter.integration, 'verify_local_docker'), \
                patch.object(adapter.integration, 'resolve', return_value={'images': campaign['publications']}), \
                patch.object(adapter, 'registry_images', return_value={}), \
                patch.object(adapter, 'command', side_effect=local_only):
            output = Path(tmp) / 'new-bundle'
            previous_umask = os.umask(0o077)
            try:
                receipt = adapter.prepare(output, adapter.ROOT / 'deploy/public-production/runtime.env.example',
                                          Path('/etc/gameometro/production/private-tls'))
            finally:
                os.umask(previous_umask)
            self.assertEqual(adapter.fingerprint(output / 'candidate'), adapter.FINGERPRINT)
            self.assertEqual((output / 'Caddyfile.private').stat().st_mode & 0o777, 0o644)
            self.assertEqual(output.stat().st_mode & 0o777, 0o700)
            self.assertFalse(receipt['executionEnabled'])
            base = json.loads((output / 'compose.candidate.json').read_text())
            config = json.loads((output / 'compose.private.json').read_text())
            directory_binds = {p.relative_to(output / 'candidate').as_posix(): p
                               for p in adapter.candidate_binds(config, output / 'candidate') if p.is_dir()}
            self.assertEqual(set(directory_binds), {
                'docker/postgres/init', 'deploy/public-production/keycloak/import',
                'deploy/private-dev/grafana/dashboards', 'deploy/public-production/observability/grafana'})
            for name, path in directory_binds.items():
                with self.subTest(mount=name):
                    self.assertEqual(path.stat().st_mode & 0o777, 0o755)
                    self.assertTrue(all(p.stat().st_mode & 0o004 for p in path.rglob('*') if p.is_file()))
            for path in ('candidate', 'candidate/docker', 'candidate/deploy', 'candidate/scripts'):
                self.assertEqual((output / path).stat().st_mode & 0o777, 0o700)
            for name in ('compose.candidate.json', 'compose.private.json', 'runtime.env', 'preparation.json'):
                self.assertEqual((output / name).stat().st_mode & 0o777, 0o600)
            entries = subprocess.run(['git', 'ls-tree', '-rz', adapter.REVISION, 'deploy', 'docker', 'scripts', 'tools'],
                                     capture_output=True, check=True).stdout.decode().split('\0')
            for entry in filter(None, entries):
                metadata, name = entry.split('\t', 1)
                mode, _, digest = metadata.split()
                path = output / 'candidate' / name
                data = path.read_bytes()
                self.assertEqual(hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest(), digest)
                self.assertEqual(path.stat().st_mode & 0o777, int(mode, 8) & 0o777)
            adapter.validate(base, config, adapter.TARGET / 'Caddyfile.private',
                             Path('/etc/gameometro/production/private-tls'))
            self.assertEqual(len(config['services']), 11)
            self.assertFalse(any(s.get('ports') for s in config['services'].values()))
            self.assertFalse(any(str(output) in m['source'] for s in config['services'].values()
                                 for m in s.get('volumes', []) if m['type'] == 'bind'))
            docker_calls = [c for c in calls if c[0] == 'docker']
            self.assertEqual(len(docker_calls), 1)
            self.assertEqual(docker_calls[0][-3:], ['config', '--format', 'json'])

    def test_nested_mount_directories_only_are_normalized(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'candidate'
            nested = source / 'public/nested'
            nested.mkdir(parents=True, mode=0o700)
            (source / 'public').chmod(0o700)
            source.chmod(0o700)
            script = nested / 'entrypoint'
            script.write_bytes(b'#!/bin/sh\nexit 0\n'); script.chmod(0o755)
            config = {'services': {'fixture': {'volumes': [adapter.bind(
                adapter.TARGET / 'candidate/public', '/public')]}}}
            adapter.prepare_mount_directories(config, source)
            self.assertEqual(source.stat().st_mode & 0o777, 0o700)
            self.assertEqual(nested.stat().st_mode & 0o777, 0o755)
            self.assertEqual((source / 'public').stat().st_mode & 0o777, 0o755)
            self.assertEqual(script.stat().st_mode & 0o777, 0o755)

    def test_execution_and_update_flags_do_not_exist(self):
        for option in ('--execute', '--first-install', '--update', '--rollback'):
            result = subprocess.run(['python3', str(Path(adapter.__file__)), '--output', '/new/output',
                                     '--env-file', '/missing/env', '--tls-dir', '/missing/tls', option],
                                    capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertIn(b'unrecognized arguments', result.stderr)


if __name__ == '__main__':
    unittest.main()
