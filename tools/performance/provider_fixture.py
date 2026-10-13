#!/usr/bin/env python3
"""Bounded HTTP fixture, following IgdbFixtureServer; never forwards to a provider.

Responses model data semantics, NOT IGDB latency, retries or operational duration.
The existing localizer image supplies Python; no extra package/image is required.
"""
import argparse
import copy
import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import re
import time


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def build_input(root, count, date):
    if count not in (2, 4, 8):
        raise ValueError('Calibration supports only 2, 4 or 8 games')
    fixtures = root / 'backend/src/test/resources/provider/igdb'
    details = json.loads((fixtures / 'details-game-response.json').read_text())[0]
    core = json.loads((fixtures / 'game-response.json').read_text())[0]
    releases = json.loads((fixtures / 'release-dates-response.json').read_text())[:2]
    day = datetime.date.fromisoformat(date)
    epoch = int(datetime.datetime.combine(day, datetime.time(), datetime.timezone.utc).timestamp())
    games, rows = [], []
    for n in range(1, count + 1):
        game = {**copy.deepcopy(core), **copy.deepcopy(details)}
        game.update(id=850000 + n, name=f'Performance Fixture Mission {n:02d}',
                    url=f'https://www.igdb.com/games/performance-fixture-mission-{n:02d}',
                    updated_at=epoch, summary=f'Experiment mission {n:02d}. ' + details['summary'].strip())
        game['genres'] = [{'id': 985001, 'name': 'Tactical exploration', 'slug': 'tactical-exploration'}]
        game['game_modes'] = [{'id': 985002, 'name': 'Cooperative missions', 'slug': 'cooperative-missions'}]
        games.append(game)
        for slot, template in enumerate(releases, 1):
            row = copy.deepcopy(template)
            row.update(id=game['id'] * 10 + slot, game=game['id'], date=epoch,
                       y=day.year, m=day.month, d=day.day)
            rows.append(row)
    value = {'referenceDate': date, 'games': games, 'releaseDates': rows,
             'fixtureSources': {name: hashlib.sha256((fixtures / name).read_bytes()).hexdigest()
                                for name in ['details-game-response.json', 'game-response.json',
                                             'release-dates-response.json']},
             'timingScope': 'Internal fixture-backed pipeline only; external provider timing excluded'}
    value['payloadSha256'] = digest(value)
    return value


# A fixed offline reuse cohort; local calibration inputs retain their original bounds.
REUSE_GAMES = 64


def build_reuse_input(root, date):
    data = build_input(root, 2, date)
    templates = data['games']
    releases = data['releaseDates']
    data['games'], data['releaseDates'] = [], []
    for n in range(1, REUSE_GAMES + 1):
        game = copy.deepcopy(templates[(n - 1) % 2])
        game.update(id=850000 + n, name=f'Performance Fixture Mission {n:02d}',
                    url=f'https://www.igdb.com/games/performance-fixture-mission-{n:02d}')
        data['games'].append(game)
        for slot, template in enumerate(releases[:2], 1):
            row = copy.deepcopy(template)
            row.update(id=game['id'] * 10 + slot, game=game['id'])
            data['releaseDates'].append(row)
    data['translationScope'] = 'Two shared summaries plus two taxonomy labels; offline fixture cache'
    data['payloadSha256'] = digest({k: v for k, v in data.items() if k != 'payloadSha256'})
    return data


def response_for(data, path, query):
    limit_match = re.search(r'limit (\d+);$', query)
    if not limit_match or not 1 <= int(limit_match[1]) <= 500:
        raise ValueError('Unsupported or unbounded query')
    limit = int(limit_match[1])
    if path == '/v4/release_dates' and query.startswith('fields id,game;'):
        match = re.fullmatch(r'fields id,game; where date >= (\d+) & date < (\d+) & game > (\d+); sort game asc; limit (\d+);', query)
        if not match:
            raise ValueError('Unsupported release window')
        lower, upper, after, _ = map(int, match.groups())
        return [{'id': row['id'], 'game': row['game']} for row in data['releaseDates']
                if lower <= row['date'] < upper and row['game'] > after][:limit]
    key = 'id' if path == '/v4/games' else 'game'
    match = re.search(r'where ' + key + r' = \(([0-9,]+)\)', query)
    if not match or path not in ['/v4/games', '/v4/release_dates', '/v4/logos']:
        raise ValueError('Unsupported endpoint/query')
    ids = [int(value) for value in match[1].split(',')]
    if not 1 <= len(ids) <= 100 or not set(ids) <= {game['id'] for game in data['games']}:
        raise ValueError('Unknown or oversized acquisition subset')
    if path == '/v4/logos':
        return []
    source = data['games'] if path == '/v4/games' else data['releaseDates']
    return sorted([row for row in source if row[key] in ids], key=lambda row: row['id'])[:limit]


def serve(path):
    data = json.loads(Path(path).read_text())
    ledger = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # No token arguments, headers or texts in access logs.

        def send(self, status, body):
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if self.path == '/ready':
                self.send(200, {'status': 'UP'})
            elif self.path == '/evidence':
                self.send(200, {'payloadSha256': data['payloadSha256'], 'requests': ledger})
            else:
                self.send(404, {'error': 'Unknown fixture endpoint'})

        def do_POST(self):
            started = time.monotonic_ns()
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 <= length <= 16384 or len(ledger) >= 1000:
                self.send(413, {'error': 'Fixture bound exceeded'})
                return
            query = self.rfile.read(length).decode()
            try:
                result = ({'access_token': 'fixture-access-token', 'expires_in': 5184000,
                           'token_type': 'bearer'} if self.path.startswith('/oauth2/token')
                          else response_for(data, self.path, query))
                status = 200
            except ValueError as error:
                result, status = {'error': str(error)}, 400
            ledger.append({'path': self.path.split('?')[0], 'status': status,
                           'querySha256': None if self.path.startswith('/oauth2/token') else digest(query),
                           'responseSha256': digest(result), 'monotonicStartedNs': started,
                           'monotonicPreparedNs': time.monotonic_ns(),
                           'gameIds': sorted({row.get('game', row.get('id')) for row in result})
                           if isinstance(result, list) and self.path != '/v4/logos' else []})
            self.send(status, result)
    HTTPServer(('0.0.0.0', 8093), Handler).serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', help='Immutable JSON generated by interference.py')
    serve(parser.parse_args().input)
