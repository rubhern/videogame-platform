#!/usr/bin/env python3
"""Regressions for parallel required checks, packaging coverage and OCI identity."""
import hashlib
import fnmatch
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
    def test_browser_matrix_and_parallel_runtime_are_complete_and_fail_closed(self):
        workflow = (ROOT / '.github/workflows/build-and-verify.yml').read_text()
        starts = list(re.finditer(r'^  ([a-z][a-z-]+):\n', workflow, re.M))
        jobs = {match[1]: workflow[match.end():starts[i + 1].start() if i + 1 < len(starts) else len(workflow)]
                for i, match in enumerate(starts)}
        gate = jobs['required-quality-gate']
        for name, area, variable in [('browser-smoke', 'browser', 'BROWSER_SMOKE'),
                                     ('production-runtime', 'production_runtime', 'PRODUCTION_RUNTIME')]:
            job = jobs[name]
            self.assertIn(f"if: ${{{{ needs.detect-changes.outputs.{area} == 'true' }}}}", job)
            self.assertIn('    needs: detect-changes\n', job)
            for forbidden in ('continue-on-error', 'exclude:', 'include:', '--retries'):
                self.assertNotIn(forbidden, job)
            self.assertIn(f'      - {name}\n', gate)
            self.assertIn(f'{variable}: ${{{{ needs.{name}.result }}}}', gate)
            for expected, result in [('true', 'failure'), ('true', 'cancelled'),
                                     ('true', 'skipped'), ('false', 'success')]:
                checked = subprocess.run(['bash', str(ROOT / 'scripts/verify-ci-results.sh'),
                                          name, expected, result], capture_output=True)
                self.assertNotEqual(checked.returncode, 0, (name, expected, result))

        browser = jobs['browser-smoke']
        self.assertIn('      fail-fast: false\n', browser)
        self.assertIn('        shard: [1, 2]\n', browser)
        self.assertEqual(browser.count('shard: ['), 1)
        self.assertEqual(re.findall(r'^        if: (.+)$', browser, re.M), ['${{ !cancelled() }}'])
        self.assertIn('BROWSER_SHARD: ${{ matrix.shard }}/2', browser)
        self.assertIn("PACKAGE_SKIP_BACKEND_TESTS: ${{ needs.detect-changes.outputs.backend == 'true' "
                      "|| matrix.shard != 1 }}", browser)
        self.assertIn('name: playwright-report-${{ matrix.shard }}', browser)
        self.assertNotIn('REUSE_PACKAGE', browser)
        self.assertIn('run: bash scripts/validate-browser.sh', browser)
        script = (ROOT / 'scripts/validate-browser.sh').read_text()
        self.assertIn('browser_test_arguments=()', script)  # Local default remains the whole suite.
        self.assertIn('browser_test_arguments+=(--shard="$BROWSER_SHARD" --workers=2)', script)
        self.assertIn('npm --prefix frontend run test:e2e -- "${browser_test_arguments[@]}"', script)
        for invalid in ('0/2', '3/2', '1/0', '1/100', 'all', '1/2 --retries=2'):
            result = subprocess.run(['bash', str(ROOT / 'scripts/validate-browser.sh')],
                                    env={**os.environ, 'BROWSER_SHARD': invalid}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2, invalid)
            self.assertIn('BROWSER_SHARD must be', result.stderr)

        runtime = jobs['production-runtime']
        self.assertNotIn('matrix:', runtime)  # Avoid extra runner queues for these branches.
        proofs = {
            'operations': ['test-public-production-deployment.py', 'test-public-production-integration.py',
                           'test-production-recovery.py', 'test-production-recovery-lock.py',
                           'test-production-identity-maintenance.py', 'test-production-cleanup.py'],
            'observability': ['test-production-observability.py --smoke'],
            'edge': ['test-public-production-runtime.py'],
        }
        for commands in proofs.values():
            for command in commands:
                self.assertEqual(runtime.count(command), 1)
        dispatch = re.search(r'^        run: \|\n((?:          .*\n|\n)+)', runtime, re.M)[1]
        dispatch = re.sub(r'^          ', '', dispatch, flags=re.M)
        # Execute the actual orchestration with recording proofs: failures in every
        # branch and termination of a branch must fail the step, after all waits.
        recorder = '''set -euo pipefail
python3() {
  printf '%s\\n' "$*" >> "$PROOF_LOG"
  if [[ "$*" == "${FAIL_PROOF:-}" ]]; then return 7; fi
  if [[ "$*" == "${CANCEL_PROOF:-}" ]]; then kill -TERM "$BASHPID"; fi
}
'''
        commands = [f'scripts/{command}' for group in proofs.values() for command in group]
        commands[-1] = '-I ' + commands[-1]
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'proofs'
            for failed in (None, *commands):
                log.write_text('')
                result = subprocess.run(['bash', '-c', recorder + dispatch],
                                        env={**os.environ, 'PROOF_LOG': str(log), 'FAIL_PROOF': failed or ''},
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 1 if failed else 0, (failed, result.stderr))
                ran = log.read_text().splitlines()
                for first in (commands[0], commands[-2], commands[-1]):
                    self.assertIn(first, ran, 'all independent branches must run even after a failure')
                if failed is None:
                    self.assertEqual(sorted(ran), sorted(commands))
            for cancelled in (commands[0], commands[-2], commands[-1]):
                log.write_text('')
                result = subprocess.run(['bash', '-c', recorder + dispatch],
                                        env={**os.environ, 'PROOF_LOG': str(log), 'CANCEL_PROOF': cancelled},
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 1, (cancelled, result.stderr))

    def test_documentation_optimization_preserves_required_coverage(self):
        workflow = (ROOT / '.github/workflows/build-and-verify.yml').read_text()
        docs = workflow.split('  documentation-contracts:\n', 1)[1].split('\n  frontend:\n', 1)[0]
        self.assertIn("if: ${{ needs.detect-changes.outputs.documentation == 'true' }}", docs)
        self.assertIn('    needs: detect-changes\n', docs)
        self.assertIn('    runs-on: ubuntu-24.04\n', docs)
        self.assertIn('cache-dependency-path: |\n            package-lock.json\n'
                      '            documentation/package-lock.json', docs)
        self.assertIn('node node_modules/@playwright/test/cli.js install chromium\n', docs)
        self.assertNotIn('--with-deps', docs)
        self.assertNotIn('PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS', docs)
        self.assertNotIn('continue-on-error', docs)
        self.assertNotIn('|| true', docs)
        # These checks stay unconditional whenever documentation is applicable.
        for command in ('bash scripts/validate-docs.sh',
                        'npm ci --prefix documentation --ignore-scripts',
                        'npm audit --prefix documentation --audit-level=high',
                        'npm test && npm run build',
                        'bash documentation/scripts/test-container.sh --browser'):
            step = next(step for step in docs.split('      - name: ') if command in step)
            self.assertNotIn('        if:', step)
        for command in ('npm ci --ignore-scripts', 'bash scripts/validate-openapi.sh',
                        'npm run build:openapi-docs'):
            step = next(step for step in docs.split('      - name: ') if command in step)
            self.assertIn("if: ${{ needs.detect-changes.outputs.openapi == 'true' }}", step)
        self.assertIn('git diff --exit-code -- docs/architecture/api/reference/index.html', docs)
        gate = workflow.split('  required-quality-gate:\n', 1)[1].split('\n  publish-container-image:\n', 1)[0]
        self.assertIn('      - documentation-contracts\n', gate)
        self.assertIn('DOCUMENTATION_CONTRACTS: ${{ needs.documentation-contracts.result }}', gate)
        self.assertIn('documentation-contracts "$DOCUMENTATION_EXPECTED" "$DOCUMENTATION_CONTRACTS"', gate)
        for expected, result in [('true', 'failure'), ('true', 'cancelled'),
                                 ('true', 'skipped'), ('false', 'success')]:
            checked = subprocess.run(['bash', str(ROOT / 'scripts/verify-ci-results.sh'),
                                      'documentation-contracts', expected, result], capture_output=True)
            self.assertNotEqual(checked.returncode, 0, (expected, result))

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
        self.assertIn("PACKAGE_SKIP_BACKEND_TESTS: ${{ needs.detect-changes.outputs.backend == 'true' "
                      "|| matrix.shard != 1 }}", jobs['browser-smoke'])
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

    def test_codeql_init_and_analyze_keep_one_revision_and_one_update_group(self):
        workflow = (ROOT / '.github/workflows/security.yml').read_text()
        uses = re.findall(r'uses: (github/codeql-action/(?:init|analyze))@([0-9a-f]{40})', workflow)
        self.assertEqual({action for action, _ in uses},
                         {'github/codeql-action/init', 'github/codeql-action/analyze'})
        self.assertEqual(len({revision for _, revision in uses}), 1,
                         'CodeQL init and analyze reject mismatched action revisions')
        config = (ROOT / '.github/dependabot.yml').read_text()
        actions = config.split('  - package-ecosystem: github-actions\n', 1)[1]
        actions = actions.split('  - package-ecosystem:', 1)[0]
        group = re.search(r'    groups:\n      codeql:\n        patterns:\n((?:          - .+\n)+)', actions)
        self.assertIsNotNone(group, 'Keep the coupled CodeQL actions in one Dependabot PR')
        patterns = [line.strip().removeprefix('- ').strip('"\'')
                    for line in group[1].splitlines()]
        for action, _ in uses:
            self.assertTrue(any(fnmatch.fnmatchcase(action, pattern) for pattern in patterns), action)

    def test_dependency_submission_uses_the_pinned_wrapper_for_both_graphs(self):
        workflow = (ROOT / '.github/workflows/dependency-submission.yml').read_text()
        self.assertIn('            pom: pom.xml\n', workflow)
        self.assertIn('            pom: tools/igdb-poc/pom.xml\n', workflow)
        self.assertIn('          directory: ${{ github.workspace }}\n', workflow)
        self.assertIn('          maven-args: -f ${{ matrix.pom }}\n', workflow)
        self.assertNotIn('ignore-maven-wrapper: true', workflow)
        self.assertIn('correlator: maven-backend-reactor', workflow)
        self.assertIn('correlator: maven-igdb-poc', workflow)
        self.assertLess(workflow.index('name: Bootstrap the pinned Maven Wrapper'),
                        workflow.index('name: Resolve and submit the complete Maven dependency graph'))

    def test_maven_bootstrap_bounds_transient_download_recovery_and_fails_closed(self):
        workflow = (ROOT / '.github/workflows/dependency-submission.yml').read_text()
        bootstrap = workflow.split('      - name: Bootstrap the pinned Maven Wrapper\n', 1)[1]
        body = bootstrap.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0]
        script = '\n'.join(line[10:] if line.startswith('          ') else line
                           for line in body.splitlines())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'bin').mkdir()
            (root / 'bin/sleep').write_text('#!/bin/sh\nexit 0\n')
            (root / 'bin/sleep').chmod(0o755)
            (root / 'mvnw').write_text(
                '#!/usr/bin/env python3\n'
                'import json, os, pathlib, sys\n'
                'p = pathlib.Path("attempts.json")\n'
                'calls = json.loads(p.read_text()) if p.exists() else []\n'
                'calls.append(sys.argv[1:])\n'
                'p.write_text(json.dumps(calls))\n'
                'sys.exit(1 if len(calls) <= int(os.environ["BOOTSTRAP_FAILURES"]) else 0)\n')
            (root / 'mvnw').chmod(0o755)
            env = {**os.environ, 'PATH': str(root / 'bin') + ':' + os.environ['PATH']}
            for failures, expected_attempts, succeeds in [(0, 1, True), (2, 3, True), (99, 3, False)]:
                (root / 'attempts.json').unlink(missing_ok=True)
                result = subprocess.run(['bash', '-euo', 'pipefail', '-c', script], cwd=root,
                                        env={**env, 'BOOTSTRAP_FAILURES': str(failures)},
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)
                calls = json.loads((root / 'attempts.json').read_text())
                self.assertEqual(len(calls), expected_attempts)
                self.assertTrue(all(call == ['--batch-mode', '--no-transfer-progress', '--version']
                                    for call in calls))

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
