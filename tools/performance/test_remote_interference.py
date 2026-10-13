"""Focused offline overlap controls; never starts a generator or contacts a host."""
import datetime
import json
from pathlib import Path
import tempfile
import unittest

from remote_interference import correlate, markdown


class Overlap(unittest.TestCase):
    def evidence(self, start=5, end=45, stable=True, requests=600, offsets=(0, 0), outcome='SUCCEEDED', translated=0, scenario='serving-sync-reuse', completed_inferences=0):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        directory = Path(tmp.name)
        base = datetime.datetime(2026, 10, 13, 10, tzinfo=datetime.timezone.utc)
        def stamp(seconds):
            return (base + datetime.timedelta(seconds=seconds)).isoformat()
        def write(name, value):
            (directory / name).write_text(json.dumps(value))
        write('background.json', {'status': outcome, 'report': {'startedAt': stamp(start), 'completedAt': stamp(end), 'outcome': outcome,
            'counters': {'updatedGames': 64, 'failedGames': 0, 'deferredGames': 0}},
            'localizationBefore': {'translated': 0, 'reused': 0, 'failed': 0},
            'localizationAfter': {'translated': translated, 'reused': 66, 'failed': 0},
            'localizer': {'completedInferences': completed_inferences, 'failedInferences': 0}})
        write('clock-bridge.json', {'hostMinusControllerSeconds': list(offsets)})
        write('background-preparation.json', {'scenario': scenario, 'games': 64})
        points = [{'type': 'Point', 'metric': 'http_req_duration', 'data': {'time': stamp(n / 10 + .1),
                    'value': 100, 'tags': {'status': '200'}}} for n in range(requests)]
        (directory / 'metrics.json').write_text('\n'.join(json.dumps(p) for p in points))
        observation = {'samples': [{'wallSeconds': (base + datetime.timedelta(seconds=n)).timestamp()}
                                  for n in range(0, 60, 10)], 'failures': []}
        return correlate(directory, observation, stable)

    def test_long_successful_reuse_overlap_reports_three_periods_but_no_causality(self):
        result = self.evidence()
        self.assertAlmostEqual(result['syncDurationSeconds'], 40)
        self.assertAlmostEqual(result['conservativeOverlapSeconds'], 40)
        self.assertTrue(result['interferenceObservationUsable'])
        self.assertTrue(result['reuseVerified'])
        self.assertFalse(result['attributionAllowed'])
        self.assertGreater(result['periods']['during']['count'], 300)
        self.assertGreater(result['periods']['after']['count'], 100)
        self.assertIn('exploratory correlation', markdown(result))

    def test_uncertain_boundaries_are_counted_separately_without_losing_requests(self):
        result = self.evidence(offsets=(-.1, .1))
        self.assertEqual(sum(p['count'] for p in result['periods'].values()), 600)
        self.assertGreater(result['periods']['boundary']['count'], 0)

    def test_inference_requires_both_meter_and_real_helper_completion_evidence(self):
        without_helper = self.evidence(scenario='serving-sync-inference', translated=4)
        self.assertFalse(without_helper['inferenceVerified'])
        self.assertFalse(without_helper['interferenceObservationUsable'])
        verified = self.evidence(scenario='serving-sync-inference', translated=4, completed_inferences=4)
        self.assertTrue(verified['inferenceVerified'])
        self.assertTrue(verified['interferenceObservationUsable'])
        self.assertIsNone(verified['reuseVerified'])

    def test_two_game_sized_short_window_cannot_support_attribution(self):
        result = self.evidence(end=6)
        self.assertFalse(result['overlapSufficient'])
        self.assertFalse(result['interferenceObservationUsable'])
        self.assertIn('no degradation attributed', result['interpretation'])

    def test_work_after_k6_or_without_http_has_no_overlap(self):
        result = self.evidence(start=65, end=105)
        self.assertEqual(result['conservativeOverlapSeconds'], 0)
        self.assertFalse(result['interferenceObservationUsable'])
        self.assertFalse(self.evidence(requests=0)['attributionAllowed'])

    def test_work_outlasting_k6_explicitly_reports_missing_serving_recovery(self):
        result = self.evidence(start=5, end=80)
        self.assertFalse(result['httpRecoveryAfterSyncObserved'])
        self.assertIn('serving recovery after sync is unobserved', markdown(result))

    def test_clock_uncertainty_and_failed_work_or_inference_invalidate_observation(self):
        for parameters in [{'stable': False}, {'offsets': (-40, 40)}, {'outcome': 'FAILED'}, {'translated': 1}]:
            with self.subTest(parameters=parameters):
                result = self.evidence(**parameters)
                self.assertFalse(result['interferenceObservationUsable'])
                self.assertFalse(result['attributionAllowed'])


if __name__ == '__main__':
    unittest.main()
