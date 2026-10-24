#!/usr/bin/env python3
"""Registry acquisition retries must never conceal build or product-proof failures."""
import importlib.util
from pathlib import Path
import subprocess
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
                patch.object(growth.time, 'sleep') as sleep:
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
                    ]) as run, patch.object(growth.time, 'sleep') as sleep:
                growth.pull_base_image()
                self.assertEqual(run.call_count, 2)
                self.assertEqual(run.call_args_list[0], run.call_args_list[1])
                sleep.assert_called_once_with(5)

    def test_exhausted_registry_failure_stays_a_failure(self):
        with patch.object(growth.subprocess, 'run', return_value=self.result(1, '503 Service Unavailable')) as run, \
                patch.object(growth.time, 'sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError, '503 Service Unavailable'):
                growth.pull_base_image()
        self.assertEqual(run.call_count, 3)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [5, 10])

    def test_permanent_failure_is_not_retried(self):
        for error in ('401 Unauthorized', '403 Forbidden', '404 Not Found', 'manifest unknown', 'digest mismatch'):
            with self.subTest(error=error), \
                    patch.object(growth.subprocess, 'run', return_value=self.result(1, error)) as run, \
                    patch.object(growth.time, 'sleep') as sleep:
                with self.assertRaisesRegex(RuntimeError, error):
                    growth.pull_base_image()
                run.assert_called_once()
                sleep.assert_not_called()

    def test_build_failure_is_not_retried(self):
        with patch.object(growth.subprocess, 'run', return_value=self.result(1, 'augmentation failed')) as run:
            with self.assertRaisesRegex(RuntimeError, 'augmentation failed'):
                growth.build_image({})
        run.assert_called_once()


if __name__ == '__main__':
    unittest.main()
