#!/usr/bin/env python3
"""Registry acquisition retries must never conceal build or product-proof failures."""
import importlib.util
from pathlib import Path
import subprocess
import re
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('growth', Path(__file__).with_name('check-product-growth.py'))
growth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(growth)


class BaseImageDownloadTest(unittest.TestCase):
    def result(self, code, error=''):
        return subprocess.CompletedProcess([], code, stdout='', stderr=error)

    def test_success_uses_the_recipe_digest_without_waiting(self):
        with patch.object(growth.subprocess, 'run', return_value=self.result(0)) as run, \
                patch.object(growth.image_pull.time, 'sleep') as sleep:
            growth.pull_base_image()
        command = run.call_args.args[0]
        self.assertEqual(command[:3], ['docker', 'pull', '--quiet'])
        self.assertIn('ARG KEYCLOAK_IMAGE=' + command[3],
                      (growth.DEPLOY / 'keycloak/Dockerfile').read_text())
        self.assertRegex(command[3], r'@sha256:[0-9a-f]{64}$')
        self.assertEqual(run.call_args.kwargs['timeout'], 120)
        sleep.assert_not_called()

    def test_registry_server_failure_is_retried_before_the_proof(self):
        for status in ('429 Too Many Requests', '500 Internal Server Error', '502 Bad Gateway',
                       '503 Service Unavailable', '504 Gateway Timeout'):
            with self.subTest(status=status), \
                    patch.object(growth.subprocess, 'run', side_effect=[
                        self.result(1, 'unexpected status from HEAD request: ' + status), self.result(0),
                    ]) as run, patch.object(growth.image_pull.time, 'sleep') as sleep:
                growth.pull_base_image()
                self.assertEqual(run.call_count, 2)
                self.assertEqual(run.call_args_list[0], run.call_args_list[1])
                sleep.assert_called_once_with(5)

    def test_exhausted_registry_failure_stays_a_failure(self):
        with patch.object(growth.subprocess, 'run', return_value=self.result(1, '503 Service Unavailable')) as run, \
                patch.object(growth.image_pull.time, 'sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError, '503 Service Unavailable'):
                growth.pull_base_image()
        self.assertEqual(run.call_count, 3)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [5, 10])

    def test_permanent_failure_is_not_retried(self):
        for error in ('401 Unauthorized', '403 Forbidden', '404 Not Found', 'manifest unknown', 'digest mismatch'):
            with self.subTest(error=error), \
                    patch.object(growth.subprocess, 'run', return_value=self.result(1, error)) as run, \
                    patch.object(growth.image_pull.time, 'sleep') as sleep:
                with self.assertRaisesRegex(RuntimeError, error):
                    growth.pull_base_image()
                run.assert_called_once()
                sleep.assert_not_called()

    def test_build_failure_is_not_retried(self):
        with patch.object(growth.subprocess, 'run', return_value=self.result(1, 'augmentation failed')) as run:
            with self.assertRaisesRegex(RuntimeError, 'augmentation failed'):
                growth.build_image({})
        run.assert_called_once()

    def test_skopeo_uses_the_same_bounded_download(self):
        workflow = (growth.alerts.ROOT / '.github/workflows/build-and-verify.yml').read_text()
        images = re.findall(r'SKOPEO_IMAGE: (\S+)', workflow)
        self.assertEqual(len(images), 2)
        self.assertEqual(images[0], images[1])
        with patch.object(growth.image_pull.subprocess, 'run', side_effect=[
                self.result(1, 'received unexpected HTTP status: 503 Service Unavailable'), self.result(0),
        ]) as run, patch.object(growth.image_pull.time, 'sleep') as sleep:
            growth.image_pull.pull_image(images[0])
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args.args[0], ['docker', 'pull', '--quiet', images[0]])
        sleep.assert_called_once_with(5)

    def test_mutable_or_unsafe_reference_never_reaches_docker(self):
        for image in ('quay.io/skopeo/stable:latest', '--all-tags', '--all-tags@sha256:' + 'a' * 64,
                      'quay.io/skopeo/stable@sha256:bad'):
            with self.subTest(image=image), patch.object(growth.image_pull.subprocess, 'run') as run:
                with self.assertRaises(ValueError):
                    growth.image_pull.pull_image(image)
                run.assert_not_called()

    def test_publication_download_precedes_authentication_and_execution(self):
        workflow = (growth.alerts.ROOT / '.github/workflows/build-and-verify.yml').read_text()
        for name in ('publish-container-image', 'publish-dependency-images'):
            # Match whole job boundaries rather than nested YAML keys.
            job = workflow.split('\n  ' + name + ':\n', 1)[1]
            job = re.split(r'\n  [a-z][a-z-]+:\n', job, maxsplit=1)[0]
            with self.subTest(job=name):
                self.assertIn('contents: read', job)
                self.assertIn('persist-credentials: false', job)
                download = job.index('python3 scripts/ci_image_pull.py "$SKOPEO_IMAGE"')
                self.assertLess(job.index('actions/checkout@'), download)
                self.assertLess(download, job.index('uses: docker/login-action@'))
                self.assertLess(download, job.index('docker run --rm'))
                self.assertIn('--preserve-digests', job)


if __name__ == '__main__':
    unittest.main()
