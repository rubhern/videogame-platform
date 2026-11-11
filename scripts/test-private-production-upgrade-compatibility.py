#!/usr/bin/env python3
"""Production upgrade compatibility: the in-place upgrader must accept this tree from earlier releases.

The upgrader refuses any runtime input, secret, protected setting or realm-import change it has no
reviewed rule for. This renders the real production Compose of each baseline revision and of this
working tree with synthetic inputs, then runs the upgrader's own input, secret, delta, runtime and realm
checks. A change that would make `Deploy Production` refuse at `upgrade-plan` fails here, in the
pull request, instead of on the host. Docker Compose renders the configuration; nothing starts.

Baselines: the accepted first installation (prepare.REVISION) and every revision named in the
space-separated PRODUCTION_UPGRADE_BASELINES environment variable (CI passes the pull request's base).
"""
import sys
sys.dont_write_bytecode = True

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import prepare_private_production as prepare
import private_dev_promotion as trust
import private_production_acceptance as acceptance
import private_production_control as control
import private_production_upgrade as upgrade

ROOT = Path(__file__).resolve().parents[1]
EXAMPLE = 'deploy/public-production/runtime.env.example'
TLS = Path('/etc/gameometro/production/private-tls')
PUBLISHED = ('application', 'keycloak', 'caddy', 'catalogue-localizer')


def baselines():
    names = [prepare.REVISION, *os.environ.get('PRODUCTION_UPGRADE_BASELINES', '').split()]
    resolved = []
    for name in names:
        revision = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', '--verify', '--quiet', name + '^{commit}'],
                                  capture_output=True, text=True).stdout.strip()
        if not revision:
            raise SystemExit(f'Upgrade baseline {name} is not available locally; fetch it first.')
        if revision not in resolved:
            resolved.append(revision)
    return resolved


def images(marker):
    return {name: f'ghcr.io/rubhern/{name}@sha256:' + marker * 64 for name in PUBLISHED}


def render(bundle, values, marker):
    """The upgrader's rendering of one source tree: base Compose, bundle binds and the private delta."""
    source = bundle / 'candidate'
    values = {**values, 'APPLICATION_VERSION': '1.0.0'}
    for name, image in images(marker).items():
        values[name.upper().replace('-', '_') + '_IMAGE'] = image
    runtime = bundle / 'runtime.env'
    runtime.write_text(''.join(f'{key}={value}\n' for key, value in sorted(values.items())))
    base = prepare.render_base(runtime, source)
    prepare.rebind_source(base, source, bundle)
    (bundle / upgrade.CADDYFILE).write_text(prepare.private_caddy((source / 'deploy/public-production/caddy/Caddyfile').read_text()))
    config = prepare.adapt(base, bundle / upgrade.CADDYFILE, TLS)
    for name, service in config['services'].items():
        service['restart'] = 'no' if name in upgrade.ONE_SHOT else acceptance.POLICY
    return config


class Compatibility(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.bundles = Path(cls.tmp.name)
        target = cls.bundles / 'target-private' / 'candidate'
        for tree in ('deploy', 'docker', 'scripts', 'tools'):
            shutil.copytree(ROOT / tree, target / tree, ignore=shutil.ignore_patterns('__pycache__', 'node_modules', '.venv'))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def upgrade_from(self, revision):
        bundle = self.bundles / f'{revision[:12]}-private'
        (bundle / 'candidate').mkdir(parents=True)
        prepare.export_source(ROOT, revision, bundle / 'candidate')
        previous = prepare.parse_runtime_env((bundle / 'candidate' / EXAMPLE).read_text())
        active = render(bundle, previous, '1')
        for service in active['services'].values():
            # Every installed service carries its installation lineage, which upgrades preserve.
            service['labels'] = {control.RUN_LABEL: 'compatibility', control.CONFIG_LABEL: '0' * 64}
        target_bundle = self.bundles / 'target-private'
        # The upgrader carries the active inputs forward and adds only reviewed defaults.
        values, inputs = upgrade.runtime_inputs(
            previous, prepare.parse_runtime_env((target_bundle / 'candidate' / EXAMPLE).read_text()), {})
        rendered = render(target_bundle, values, '2')
        with patch.object(upgrade, 'BUNDLES', self.bundles):
            config, changed = upgrade.reconcile(active, rendered, target_bundle)
        upgrade.validate_runtime(config, {**images('2'), 'catalogue-localizer': images('2')['catalogue-localizer']})
        realm = upgrade.realm_import_delta(json.loads((bundle / 'candidate' / upgrade.REALM_IMPORT).read_text()),
                                           json.loads((target_bundle / 'candidate' / upgrade.REALM_IMPORT).read_text()))
        return inputs, changed, realm

    def test_every_baseline_upgrades_to_this_tree(self):
        for revision in baselines():
            with self.subTest(baseline=revision[:12]):
                try:
                    inputs, changed, _ = self.upgrade_from(revision)
                except trust.Refused as error:
                    self.fail(f'Deploy Production would refuse the upgrade from {revision[:12]}: {error} '
                              'Extend scripts/private_production_upgrade.py with a reviewed rule in this change '
                              '(docs/development/delivery-lifecycle.md#production-upgrade-compatibility).')
                self.assertEqual(inputs['ownerKeys'], [])
                self.assertIn('application', changed, 'A new release always recreates the application.')
                self.assertNotIn('postgres', changed)

    def test_an_unreviewed_runtime_input_is_caught(self):
        previous = prepare.parse_runtime_env((ROOT / EXAMPLE).read_text())
        example = {**previous, 'PRODUCTION_NEW_INPUT': 'x'}
        with self.assertRaisesRegex(trust.Refused, 'different runtime input inventory'):
            upgrade.runtime_inputs(previous, example, {})


if __name__ == '__main__':
    unittest.main()
