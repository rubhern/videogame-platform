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
        self.assertIn('PACKAGE_SKIP_BACKEND_TESTS: ${{ needs.detect-changes.outputs.backend }}', jobs['browser-smoke'])
        self.assertIn('backend "$BACKEND_EXPECTED" "$BACKEND"', gate)
        self.assertIn('./mvnw -Dsource.revision="${GITHUB_SHA}" clean verify', jobs['backend'])
        self.assertNotIn('setup-qemu', workflow)
        self.assertNotIn('linux/arm64', workflow)
        self.assertIn("github.event_name == 'push'", jobs['publish-container-image'])
        self.assertIn('--preserve-digests', jobs['publish-container-image'])
        self.assertIn('"$actual_digest" != "$expected_digest"', jobs['publish-container-image'])

    def test_identity_legs_are_isolated_required_and_complete(self):
        workflow = (ROOT / '.github/workflows/build-and-verify.yml').read_text()
        starts = list(re.finditer(r'^  ([a-z][a-z-]+):\n', workflow, re.M))
        jobs = {match[1]: workflow[match.end():starts[i + 1].start() if i + 1 < len(starts) else len(workflow)]
                for i, match in enumerate(starts)}
        job, gate = jobs['identity-compatibility'], jobs['required-quality-gate']
        legs = ['private-dev', 'core', 'renewal', 'idle', 'production', 'personal-data']
        self.assertIn("if: ${{ needs.detect-changes.outputs.identity == 'true' }}", job)
        self.assertIn('    needs: detect-changes\n', job)
        self.assertIn('      fail-fast: false\n', job)
        self.assertIn(f"        leg: [{', '.join(legs)}]\n", job)
        self.assertEqual(job.count('leg: ['), 1, 'one matrix dimension, no include/exclude legs')
        for forbidden in ('include:', 'exclude:', 'continue-on-error', 'REUSE_PACKAGE', '--retries', 'max-parallel'):
            self.assertNotIn(forbidden, job)
        # Only the diagnostics upload is conditional; no leg can skip its proof and pass.
        self.assertEqual(re.findall(r'^        if: (.+)$', job, re.M), ['${{ failure() && !cancelled() }}'])
        self.assertIn('name: identity-browser-diagnostics-${{ matrix.leg }}', job)
        # The private-dev leg runs neither npm nor Maven and must not save an empty cache.
        self.assertIn("cache: ${{ matrix.leg != 'private-dev' && 'npm' || '' }}", job)
        self.assertIn("cache: ${{ matrix.leg != 'private-dev' && 'maven' || '' }}", job)
        self.assertIn("PACKAGE_SKIP_BACKEND_TESTS: ${{ needs.detect-changes.outputs.backend == 'true' "
                      "|| matrix.leg != 'personal-data' }}", job)
        self.assertIn('      - identity-compatibility\n', gate)
        self.assertIn('IDENTITY_COMPATIBILITY: ${{ needs.identity-compatibility.result }}', gate)
        self.assertIn('identity-compatibility "$IDENTITY_EXPECTED" "$IDENTITY_COMPATIBILITY"', gate)

        # Execute the leg dispatch with a recording stand-in for bash.
        dispatch = re.search(r'^        run: \|\n((?:          .*\n)+)', job, re.M)[1]
        dispatch = re.sub(r'^          ', '', dispatch, flags=re.M)
        recorder = 'bash() { printf "%s %s\\n" "$*" "${IDENTITY_REMEMBER_ME_PHASE:-}"; }\n'
        expected = {
            'private-dev': 'scripts/validate-private-dev-identity.sh',
            'core': 'scripts/validate-identity.sh core',
            'renewal': 'scripts/validate-identity.sh renewal',
            'idle': 'scripts/validate-identity.sh idle',
            'production': 'scripts/validate-production-identity.sh',
            'personal-data': 'scripts/validate-personal-data.sh',
        }
        self.assertEqual(sorted(expected), sorted(legs))
        for leg, command in expected.items():
            ran = subprocess.run(['bash', '-c', recorder + dispatch], env={**os.environ, 'IDENTITY_LEG': leg},
                                 capture_output=True, text=True)
            self.assertEqual((ran.returncode, ran.stdout.strip()), (0, command), leg)
        unknown = subprocess.run(['bash', '-c', recorder + dispatch], env={**os.environ, 'IDENTITY_LEG': 'all'},
                                 capture_output=True, text=True)
        self.assertEqual((unknown.returncode, unknown.stdout), (2, ''))

        # core + renewal + idle together run every journey of the complete local phase.
        script = (ROOT / 'scripts/validate-identity.sh').read_text()
        self.assertIn('all|core|continuity|renewal|idle) ;;', script)
        self.assertIn('if [[ "$identity_phase" =~ ^(all|core)$ && "${IDENTITY_REMEMBER_ME_ONLY:-false}" != "true" ]]; then', script)
        self.assertIn('if [[ "$identity_phase" =~ ^(core|continuity)$ ]]; then', script)
        refused = subprocess.run(['bash', str(ROOT / 'scripts/validate-identity.sh')],
                                 env={**os.environ, 'IDENTITY_REMEMBER_ME_PHASE': 'timed'}, capture_output=True, text=True)
        self.assertEqual(refused.returncode, 2, refused.stderr)

    def test_build_jobs_never_restore_a_partial_maven_repository(self):
        workflows = ROOT / '.github/workflows'
        build = (workflows / 'build-and-verify.yml').read_text()
        # A POM-only graph saved first under the build key left every build downloading
        # nearly all dependencies again; the submission graph therefore keeps no cache.
        submission = (workflows / 'dependency-submission.yml').read_text()
        self.assertNotIn('cache: maven', submission)
        self.assertNotIn('cache-dependency-path', submission)
        reactor = 'cache-dependency-path: |\n            pom.xml\n            backend/pom.xml\n'
        self.assertEqual(build.count(reactor), 5)
        self.assertEqual(build.count(reactor + '            .mvn/wrapper/maven-wrapper.properties\n'), 5)

    def test_dependency_images_publish_only_scanned_digests_from_trusted_main(self):
        workflow = (ROOT / '.github/workflows/build-and-verify.yml').read_text()
        starts = list(re.finditer(r'^  ([a-z][a-z-]+):\n', workflow, re.M))
        jobs = {match[1]: workflow[match.end():starts[i + 1].start() if i + 1 < len(starts) else len(workflow)]
                for i, match in enumerate(starts)}
        gate = jobs['required-quality-gate']
        for job, category, variable in [('dependency-images', 'dependency_images', 'DEPENDENCY_IMAGES'),
                                        ('production-runtime', 'production_runtime', 'PRODUCTION_RUNTIME')]:
            self.assertIn(f"if: ${{{{ needs.detect-changes.outputs.{category} == 'true' }}}}", jobs[job])
            self.assertIn(f'      - {job}\n', gate)
            self.assertIn(f'{job} "${variable}_EXPECTED" "${variable}"', gate)
            self.assertIn(f'{category}: ${{{{ steps.changes.outputs.{category} }}}}', jobs['detect-changes'])
        build, publish = jobs['dependency-images'], jobs['publish-dependency-images']
        matrix = 'image: [keycloak, catalogue-localizer, caddy]'
        self.assertIn(matrix, build)
        self.assertIn(matrix, publish)
        self.assertIn('bash scripts/validate-dependency-image.sh "$DEPENDENCY_IMAGE"', build)
        self.assertIn('SOURCE_REVISION: ${{ github.sha }}', build)
        self.assertNotIn('packages: write', build)
        # Only a trusted push, after every required check, may publish; pull requests never do.
        self.assertIn("if: ${{ github.event_name == 'push' }}", publish)
        self.assertIn('      - dependency-images\n      - required-quality-gate\n', publish)
        self.assertIn("if: ${{ github.event_name == 'push' && !cancelled() }}", build)
        self.assertIn('IMAGE_NAME: ghcr.io/${{ github.repository }}-${{ matrix.image }}', publish)
        self.assertIn('IMAGE_TAG: ${{ github.sha }}', publish)
        self.assertIn('sha256sum --check SHA256SUMS', publish)
        self.assertIn('--preserve-digests', publish)
        self.assertIn('oci-archive:/evidence/image.oci.tar', publish)
        self.assertIn('"$actual_digest" != "$expected_digest"', publish)
        self.assertIn('[[ "$revision" == "$GITHUB_SHA" ]]', publish)
        self.assertIn('password: ${{ github.token }}', publish)
        self.assertNotIn('latest', publish)
        self.assertNotIn('docker buildx build', publish)
        self.assertNotIn('secrets.', build + publish)

    def test_dependency_image_content_and_scan_policy_reject_violations(self):
        result = subprocess.run(['python3', '-I', str(ROOT / 'scripts/check-dependency-image-content.py'), '--self-test'],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('FAIL', result.stdout)

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
