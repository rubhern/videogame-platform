"""Focused provider protocol checks; no network, third-party dependency or CI gate."""
import datetime
from pathlib import Path
import unittest

from provider_fixture import build_input, response_for


class ProviderProtocol(unittest.TestCase):
    def setUp(self):
        self.data = build_input(Path(__file__).resolve().parents[2], 2, '2026-10-07')

    def test_window_cursor_and_date_boundaries(self):
        epoch = int(datetime.datetime(2026, 10, 7, tzinfo=datetime.timezone.utc).timestamp())
        def query(lower, upper, after):
            return f'fields id,game; where date >= {lower} & date < {upper} & game > {after}; sort game asc; limit 500;'
        rows = response_for(self.data, '/v4/release_dates', query(epoch, epoch + 86400, 0))
        self.assertEqual([850001, 850001, 850002, 850002], [row['game'] for row in rows])
        self.assertEqual([], response_for(self.data, '/v4/release_dates', query(epoch, epoch + 86400, 850002)))
        self.assertEqual([], response_for(self.data, '/v4/release_dates', query(epoch - 86400, epoch, 0)))

    def test_game_release_and_logo_queries_use_the_same_subset(self):
        game = response_for(self.data, '/v4/games', 'fields id,summary; where id = (850002); sort id asc; limit 1;')
        rows = response_for(self.data, '/v4/release_dates', 'fields game,id,date; where game = (850002); sort id asc; limit 26;')
        self.assertEqual([850002], [row['id'] for row in game])
        self.assertEqual([850002, 850002], [row['game'] for row in rows])
        self.assertEqual([], response_for(self.data, '/v4/logos',
            'fields game,image_id; where game = (850002) & id > 0; sort id asc; limit 100;'))

    def test_unknown_unbounded_and_remote_paths_fail_closed(self):
        for path, query in [('/v4/games', 'fields id; limit 501;'),
                            ('/v4/games', 'fields id; where id = (116530); sort id asc; limit 1;'),
                            ('https://api.igdb.com/v4/games', 'fields id; where id = (850001); limit 1;')]:
            with self.assertRaises(ValueError):
                response_for(self.data, path, query)

    def test_input_and_texts_are_deterministic(self):
        self.assertEqual(self.data, build_input(Path(__file__).resolve().parents[2], 2, '2026-10-07'))
        self.assertEqual(2, len({game['summary'] for game in self.data['games']}))
        self.assertEqual(4, len(self.data['releaseDates']))


if __name__ == '__main__':
    unittest.main()
