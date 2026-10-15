#!/usr/bin/env python3
"""Regressions for parallel required checks, packaging coverage and OCI identity."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('oci', ROOT / 'scripts/inspect-application-oci.py')
oci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oci)


class DeliveryTests(unittest.TestCase):
    def test_parallel_checks_remain_required_and_fail_closed(self):
        workflow = (ROOT / '.github/workflows/build-and-verify.yml').read_text()
        starts = list(re.finditer(r'^  ([a-z][a-z-]+):\n', workflow, re.M))
        jobs = {match[1]: workflow[match.end():starts[i + 1].start() if i + 1 < len(starts) else len(workflow)]
                for i, match in enumerate(starts)}
        gate = jobs['required-quality-gate']
        for job, variable in [('container-image', 'CONTAINER_IMAGE'),
                              ('runtime-telemetry', 'RUNTIME_TELEMETRY'),
                              ('runtime-alerts', 'RUNTIME_ALERTS'),
                              ('runtime-product-growth', 'RUNTIME_PRODUCT_GROWTH'),
                              ('runtime-notifications', 'RUNTIME_NOTIFICATIONS'),
                              ('runtime-logs', 'RUNTIME_LOGS'),
                              ('localization-image', 'LOCALIZATION_IMAGE')]:
            self.assertIn("if: ${{ needs.detect-changes.outputs.container == 'true' }}", jobs[job])
            self.assertIn('    needs: detect-changes\n', jobs[job])
            self.assertIn(f'      - {job}\n', gate)
            self.assertIn(f'{variable}: ${{{{ needs.{job}.result }}}}', gate)
            self.assertIn(f'{job} "$CONTAINER_EXPECTED" "${variable}"', gate)
            for applicable, result in [('true', 'failure'), ('true', 'cancelled'),
                                       ('true', 'skipped'), ('false', 'success')]:
                checked = subprocess.run(['bash', str(ROOT / 'scripts/verify-ci-results.sh'), job,
                                          applicable, result], capture_output=True)
                self.assertNotEqual(checked.returncode, 0, (job, applicable, result))
        for job in ('browser-smoke', 'identity-compatibility'):
            self.assertIn('PACKAGE_SKIP_BACKEND_TESTS: ${{ needs.detect-changes.outputs.backend }}', jobs[job])
        self.assertIn('backend "$BACKEND_EXPECTED" "$BACKEND"', gate)
        self.assertIn('./mvnw -Dsource.revision="${GITHUB_SHA}" clean verify', jobs['backend'])
        self.assertNotIn('setup-qemu', workflow)
        self.assertNotIn('linux/arm64', workflow)
        self.assertIn("github.event_name == 'push'", jobs['publish-container-image'])
        self.assertIn('--preserve-digests', jobs['publish-container-image'])
        self.assertIn('"$actual_digest" != "$expected_digest"', jobs['publish-container-image'])

    def test_packaging_defaults_to_tests_and_validates_opt_out(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'backend/target').mkdir(parents=True)
            (root / 'backend/target/application.jar').touch()
            for name in ('package-application.sh', 'backend-artifact.sh'):
                shutil.copy(ROOT / 'scripts' / name, root / 'scripts' / name)
            shutil.copy(ROOT / 'pom.xml', root / 'pom.xml')
            log = root / 'maven-args'
            (root / 'mvnw').write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$TEST_MAVEN_ARGS"\n')
            (root / 'mvnw').chmod(0o755)
            (root / 'npm').write_text('#!/bin/sh\nexit 0\n')
            (root / 'npm').chmod(0o755)
            env = {**os.environ, 'PATH': str(root) + ':' + os.environ['PATH'], 'TEST_MAVEN_ARGS': str(log)}
            env.pop('PACKAGE_SKIP_BACKEND_TESTS', None)
            for value in (None, 'false', 'true', 'invalid'):
                test_env = dict(env)
                if value is not None:
                    test_env['PACKAGE_SKIP_BACKEND_TESTS'] = value
                result = subprocess.run(['bash', str(root / 'scripts/package-application.sh')],
                                        env=test_env, capture_output=True)
                self.assertEqual(result.returncode, 2 if value == 'invalid' else 0, result.stderr)
                if value != 'invalid':
                    self.assertEqual('-DskipTests' in log.read_text().splitlines(), value == 'true')
                    self.assertIn('clean\npackage', log.read_text())

    def test_oci_platform_and_tamper_checks(self):
        def archive(directory, architecture='amd64', extra=False, tamper=False, nested=False):
            blobs = {}
            def descriptor(data):
                content = json.dumps(data).encode() if isinstance(data, dict) else data
                digest = hashlib.sha256(content).hexdigest()
                blobs['blobs/sha256/' + digest] = content
                return {'digest': 'sha256:' + digest, 'size': len(content)}
            layer = descriptor(b'filesystem bytes')
            config = descriptor({'os': 'linux', 'architecture': architecture})
            manifest = descriptor({'config': config, 'layers': [layer]})
            expected_digest = manifest['digest']
            if nested:
                manifest['platform'] = {'os': 'linux', 'architecture': architecture}
                manifest = descriptor({'manifests': [manifest]})
                expected_digest = manifest['digest']
            index = {'manifests': [manifest] * (2 if extra else 1)}
            if tamper:
                blobs['blobs/sha256/' + layer['digest'][7:]] = b'changed bytes'
            blobs['index.json'] = json.dumps(index).encode()
            path = Path(directory) / 'application.oci.tar'
            with tarfile.open(path, 'w') as tar:
                for name, content in blobs.items():
                    item = tarfile.TarInfo(name)
                    item.size = len(content)
                    tar.addfile(item, io.BytesIO(content))
            return path, expected_digest, config['digest']
        with tempfile.TemporaryDirectory() as directory:
            for nested in (False, True):
                path, digest, config = archive(directory, nested=nested)
                self.assertEqual(oci.inspect_archive(path)[:2], (digest, config))
            for options in ({'architecture': 'arm64'}, {'architecture': 'arm64', 'nested': True},
                            {'extra': True}, {'tamper': True}):
                path, _, _ = archive(directory, **options)
                with self.assertRaises(ValueError):
                    oci.inspect_archive(path)


if __name__ == '__main__':
    unittest.main()
