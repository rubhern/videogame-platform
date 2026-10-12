"""Focused safety/recovery controls; no real resource mutations."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import authenticated as a


class Safety(unittest.TestCase):
    def test_remote_or_missing_local_boundary_stops_before_mutation(self):
        with patch.object(a, 'persistent_state', side_effect=RuntimeError('remote engine')), \
                patch.object(a, 'compose') as compose, patch.object(a, 'admin_client') as admin:
            with self.assertRaisesRegex(RuntimeError, 'remote engine'):
                a.run('/unused')
            compose.assert_not_called()
            admin.assert_not_called()

    def test_foreign_recovery_directory_is_rejected_before_docker(self):
        with patch.object(a.b, 'command') as command:
            with self.assertRaisesRegex(RuntimeError, 'Recovery accepts only'):
                a.restore('/tmp/some-other-project')
            command.assert_not_called()

    def test_stale_recovery_refuses_a_newer_disposable_database(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool = Path(tmp)
            directory = tool/'results'/'authenticated-old'
            directory.mkdir(parents=True)
            (directory/'manifest.json').write_text('{"disposablePostgresId":"old-container"}')
            with patch.object(a.b, 'TOOL', tool), patch.object(a, 'persistent_state'), \
                    patch.object(a.b, 'command', return_value='new-container'), patch.object(a, 'compose') as compose:
                with self.assertRaisesRegex(RuntimeError, 'another disposable database'):
                    a.restore(directory)
                compose.assert_not_called()

    def test_cleanup_refuses_replaced_identity(self):
        client = Mock()
        client.find_users.return_value = [{'id': 'foreign', 'email': 'same@example.test'}]
        with patch.object(a, 'admin_client', return_value=client):
            with self.assertRaisesRegex(RuntimeError, 'ownership mismatch'):
                a.remove_users(Path('/unused'), {'runtimeCheckout': '/unused',
                    'createdUsers': [{'id': 'owned', 'username': 'synthetic', 'email': 'same@example.test'}]})
        client._request.assert_not_called()

    def test_independent_pool_has_distinct_write_targets_and_seed_histories(self):
        users = a.user_specs()
        self.assertEqual(len({u['username'] for u in users}), 3)
        games = [u['gameId'] for u in users] + [s['gameId'] for u in users for s in u['seeds']]
        self.assertEqual(len(set(games)), 9)
        self.assertTrue(all(u['email'].endswith('@example.test') for u in users))

    def test_raw_failures_and_expected_statuses_are_not_hidden(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            points = [
                {'type': 'Point', 'metric': 'http_reqs', 'data': {'value': 1, 'tags': {'name': 'rating_absence', 'status': '404'}}},
                {'type': 'Point', 'metric': 'http_reqs', 'data': {'value': 1, 'tags': {'name': 'rating_create', 'status': '0', 'error_code': '1050'}}},
                {'type': 'Point', 'metric': 'checks', 'data': {'value': 0, 'tags': {'name': 'rating_create'}}},
                {'type': 'Point', 'metric': 'response_timeouts', 'data': {'value': 1, 'tags': {'name': 'rating_create'}}},
            ]
            (directory/'metrics.json').write_text('\n'.join(map(json.dumps, points)))
            (directory/'summary.json').write_text('{"metrics": {}}')
            result = a.raw_evidence(directory)['cases']
            self.assertEqual(result['rating_absence']['statuses'], {'404': 1})
            self.assertEqual(result['rating_create']['errors'], {'1050': 1})
            self.assertEqual(result['rating_create']['checksFailed'], 1)
            self.assertEqual(result['rating_create']['timeouts'], 1)


if __name__ == '__main__':
    unittest.main()
