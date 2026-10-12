#!/usr/bin/env python3
"""Bounded local authenticated/mixed functional proof, reusing the performance harness."""
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time
import uuid

import baseline as b

PG = 'videogame-platform-performance-postgres'
USER_COUNT = 3
PREFIX = 'performance-85-player-'
REALM = '/admin/realms/videogame-platform/users'


def admin_client(runtime):
    # Reuse the real-Keycloak provisioning seam already tested by the identity gate.
    path = b.ROOT / 'deploy/private-dev/bin/provision-oidc-smoke-user'
    loader = importlib.machinery.SourceFileLoader('performance_keycloak_admin', str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    values = module.parse_runtime_environment(Path(runtime) / '.env')
    client = module.KeycloakAdmin('http://localhost:8180')
    client.authenticate(values['KEYCLOAK_ADMIN_USERNAME'], values['KEYCLOAK_ADMIN_PASSWORD'])
    return client


def game(number):
    return str(uuid.UUID(hashlib.md5(f'performance:game:{number}'.encode()).hexdigest()))


def user_specs():
    return [{'username': PREFIX + str(i + 1), 'email': f'{PREFIX}{i + 1}@example.test',
             'gameId': game(3 + 5*i), 'createValue': 6 + i, 'updateValue': 7 + i,
             'titleOrder': [1, 0] if i == 0 else [0, 1],
             'search': f'{2 + 5*i:06}', 'alias': f'Aliasquest {1 + 5*i:06}',
             'seeds': [{'gameId': game(1 + 5*i), 'value': 1 + i},
                       {'gameId': game(2 + 5*i), 'value': 4 + i}]}
            for i in range(USER_COUNT)]


def ready():
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if b.get(b.MANAGEMENT + '/health/readiness')['status'] == 'UP':
                return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('Application readiness did not become UP')


def compose(directory, manifest, active):
    env = {**os.environ, 'APPLICATION_VERSION': manifest['applicationVersion']}
    args = ['docker', 'compose', '--project-name', 'videogame-platform', '--env-file',
            str(Path(manifest['runtimeCheckout']) / '.env'), '-f', str(b.ROOT / 'compose.yaml'),
            '-f', str(b.ROOT / 'compose.observability.yaml'), '-f', str(directory / 'runtime.yaml')]
    if active:
        args += ['-f', str(b.TOOL / 'compose.growth.yaml'), '-f', str(b.TOOL / 'compose.authenticated.yaml')]
    with (directory / 'operations.log').open('a') as output:
        subprocess.run(args + ['up', '-d', '--no-deps', '--force-recreate', 'keycloak', 'application'],
                       env=env, stdout=output, stderr=output, check=True, timeout=120)
    ready()
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            b.get('http://localhost:8180/realms/videogame-platform/.well-known/openid-configuration')
            return
        except Exception:
            time.sleep(1)
    raise RuntimeError('Keycloak discovery did not become ready')


def sql(text):
    # Every SQL observation must first pass dataset.sh's local-engine/container guards.
    b.command('bash', str(b.TOOL / 'dataset.sh'), 'inspect')
    return b.command('docker', 'exec', '-i', PG, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
                     '-U', 'postgres', '-d', 'videogame_performance', input=text)


def persistent_state():
    profile = json.loads(b.command('bash', str(b.TOOL / 'dataset.sh'), 'current'))
    container = b.service('postgres')
    fingerprint = b.command('docker', 'exec', '-i', container['Id'], 'psql', '-X', '-qAt',
                            '-v', 'ON_ERROR_STOP=1', '-U', 'videogame_app', '-d', 'videogame_platform',
                            input="""BEGIN READ ONLY; SET LOCAL statement_timeout='3s';
SELECT md5(count(*)::text || ':' ||
 coalesce(sum(('x'||substr(md5(r::text),1,16))::bit(64)::bigint::numeric),0)::text || ':' ||
 coalesce(sum(('x'||substr(md5(r::text),17,16))::bit(64)::bigint::numeric),0)::text)
FROM ratings.rating r;
ROLLBACK;""")
    return {'counts': profile['counts'], 'ratingsFingerprint': fingerprint}


def remove_users(directory, manifest):
    client = admin_client(manifest['runtimeCheckout'])
    for user in manifest['createdUsers']:
        found = client.find_users(user['username'])
        if not found:
            continue  # idempotent recovery after partial restoration
        if len(found) != 1 or found[0]['id'] != user['id'] or found[0].get('email') != user['email']:
            raise RuntimeError('Synthetic-user ownership mismatch; refusing deletion')
        client._request('DELETE', REALM + '/' + user['id'], expected={204})
        if client.find_users(user['username']):
            raise RuntimeError('Synthetic user remains after deletion')
    b.write(directory / 'users-restored.json', {'remainingSyntheticUsers': 0, 'removed': len(manifest['createdUsers'])})


def restore(directory):
    directory = Path(directory).resolve()
    if directory.parent != (b.TOOL / 'results').resolve() or not directory.name.startswith('authenticated-'):
        raise RuntimeError('Recovery accepts only this harness result directory')
    manifest = json.loads((directory / 'manifest.json').read_text())
    # This read-only call rejects a changed remote Docker context before any mutation.
    persistent_state()
    existing = b.command('docker', 'ps', '-a', '--no-trunc', '--filter', 'name=^/' + PG + '$', '--format', '{{.ID}}')
    if existing and existing != manifest.get('disposablePostgresId'):
        raise RuntimeError('Recovery belongs to another disposable database; refusing mutation')
    # Accounts exist only in the disposable identity database. Restoring Keycloak
    # detaches it before the verified anonymous database volume is removed.
    compose(directory, manifest, False)
    b.command('bash', str(b.TOOL / 'dataset.sh'), 'stop')
    after = persistent_state()
    before = json.loads((directory / 'persistent-before.json').read_text())
    b.write(directory / 'restoration.json', {'readiness': 'UP', 'before': before, 'after': after,
                                            'unchanged': before == after, 'remainingSyntheticUsers': 0})
    if before != after:
        raise RuntimeError('Persistent data changed during isolated experiment')


def raw_evidence(directory):
    with (directory / 'metrics.json').open() as raw:
        return aggregate_raw(raw, directory)


def aggregate_raw(raw, directory):
    cases = defaultdict(lambda: {'requests': 0, 'checksPassed': 0, 'checksFailed': 0,
                                 'statuses': Counter(), 'errors': Counter(), 'timeouts': 0})
    operations = Counter()
    sessions = Counter()
    by_scenario = defaultdict(Counter)
    for line in raw:
        item = json.loads(line)
        if item['type'] != 'Point':
            continue
        data = item['data']
        tags, value = data.get('tags', {}), data['value']
        scenario = tags.get('scenario', 'unknown')
        if item['metric'] == 'http_reqs':
            by_scenario[scenario]['requests'] += int(value)
        if item['metric'] == 'checks':
            by_scenario[scenario]['checksPassed' if value else 'checksFailed'] += 1
        name = tags.get('name')
        if name:
            case = cases[name]
            if item['metric'] == 'http_reqs':
                case['requests'] += int(value)
                case['statuses'][tags.get('status', 'unknown')] += int(value)
                if tags.get('error_code'):
                    case['errors'][tags['error_code']] += int(value)
            elif item['metric'] == 'checks':
                case['checksPassed' if value else 'checksFailed'] += 1
            elif item['metric'] == 'response_timeouts':
                case['timeouts'] += int(value)
        if item['metric'] == 'rating_operations':
            operations[tags['phase'] + ':' + tags['operation']] += int(value)
            by_scenario[scenario][tags['phase'] + ':' + tags['operation']] += int(value)
        if item['metric'] in ['authenticated_sessions', 'session_reuses']:
            sessions[item['metric']] += int(value)
            by_scenario[scenario][item['metric']] += int(value)
    summary = json.loads((directory / 'summary.json').read_text())['metrics']
    for name, case in cases.items():
        case['percentilesMs'] = summary.get(f'http_req_duration{{name:{name}}}', {}).get('values')
    return {'scenarioResults': dict(by_scenario), 'cases': dict(cases), 'operations': dict(operations), 'sessions': dict(sessions),
            'http': {key: summary.get(key, {}).get('values', {}) for key in
                     ['http_reqs', 'checks', 'http_req_failed', 'http_req_duration', 'response_timeouts', 'dropped_iterations', 'iterations']}}


def measure(directory, credentials, workload, mode):
    b.write(directory / 'workload.json', workload)
    application = b.service('application')
    postgres = json.loads(b.command('docker', 'inspect', PG))[0]
    b.write(directory / 'before.json', b.snapshot(application, postgres))
    samples, clocks, failures = [], [], []
    stop = threading.Event()
    def observe():
        while not stop.is_set():
            try:
                samples.append(b.snapshot(application, postgres))
            except Exception as error:
                failures.append(type(error).__name__)
            stop.wait(5)
    def audit():
        while not stop.is_set():
            clocks.append({'wallSeconds': time.time(), 'monotonicSeconds': time.monotonic()})
            stop.wait(0.25)
    threads = [threading.Thread(target=observe), threading.Thread(target=audit)]
    for thread in threads:
        thread.start()
    runner = directory.name + '-k6'
    args = ['docker', 'run', '--rm', '--name', runner, '--network', b.NETWORK,
            '--user', f'{os.getuid()}:{os.getgid()}', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--memory', '256m', '--cpus', '1',
            '--volume', f'{b.TOOL}:/scripts:ro', '--volume', f'{directory}:/results',
            '--volume', f'{credentials}:/credentials:ro', '-e', 'K6_NO_USAGE_REPORT=true', b.IMAGE]
    script = '/scripts/' + mode + '.js'
    try:
        # Inspect before sending a request: catches parse/options/VU allocation errors.
        b.write(directory / 'k6-options.json', json.loads(b.command(*args, 'inspect', script)))
        with (directory / 'k6.log').open('w') as output:
            code = subprocess.run(args + ['run', '--log-output', 'none', '--out', 'json=/results/metrics.json', script],
                                  stdout=output, stderr=output, timeout=110).returncode
    finally:
        # A controller timeout must not leave a generator touching the restored application.
        running = b.command('docker', 'ps', '-a', '--filter', 'name=^/' + runner + '$', '--format', '{{.ID}}')
        if running:
            b.command('docker', 'rm', '-f', runner)
        stop.set()
        for thread in threads:
            thread.join()
        b.write(directory / 'samples.json', samples)
        b.write(directory / 'clock-samples.json', clocks)
    b.write(directory / 'after.json', b.snapshot(application, postgres))
    clock = b.clock_audit(directory)
    clock['pairedWallMonotonicSamples'] = len(clocks)
    clock['wallMonotonicDiscontinuities'] = [
        {'sample': i, 'wallDeltaSeconds': c['wallSeconds'] - p['wallSeconds'],
         'monotonicDeltaSeconds': c['monotonicSeconds'] - p['monotonicSeconds']}
        for i, (p, c) in enumerate(zip(clocks, clocks[1:]))
        if abs((c['wallSeconds']-p['wallSeconds'])-(c['monotonicSeconds']-p['monotonicSeconds'])) > 0.1]
    evidence = raw_evidence(directory)
    evidence.update(clockAudit=clock, k6ExitCode=code, observationFailures=failures,
                    remainingRatings=int(sql('BEGIN READ ONLY; SELECT count(*) FROM ratings.rating; ROLLBACK;')),
                    capacityInterpretationAllowed=False)
    b.write(directory / 'evidence.json', evidence)
    if code or evidence['remainingRatings']:
        raise RuntimeError('Functional workload failed; inspect retained evidence (no capacity interpretation)')


def run(runtime, mode='mixed'):
    runtime = Path(runtime).resolve()
    persistent = persistent_state()  # local Docker boundary before provisioning or Compose mutation
    application = b.service('application')
    settings = dict(entry.split('=', 1) for entry in application['Config']['Env'])
    if settings.get('APPLICATION_DB_URL') != 'jdbc:postgresql://postgres:5432/videogame_platform' \
            or settings.get('CATALOGUE_SYNC_SCHEDULING_ENABLED', 'false') != 'false' \
            or settings.get('OIDC_ISSUER_URI') != 'http://localhost:8180/realms/videogame-platform':
        raise RuntimeError('Requires supported local persistent OIDC application with scheduling disabled')
    if b.command('docker', 'ps', '--filter', 'label=com.docker.compose.project=videogame-platform',
                 '--filter', 'label=com.docker.compose.service=catalogue-localizer', '--format', '{{.ID}}'):
        raise RuntimeError('Stop the optional localizer before the serving-only experiment')
    if b.command('docker', 'ps', '-a', '--filter', 'name=^/' + PG + '$', '--format', '{{.ID}}'):
        raise RuntimeError('An existing performance database must be reviewed/removed separately')
    users = user_specs()
    b.TOOL.joinpath('results').mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='authenticated-', dir=b.TOOL / 'results'))
    manifest = {'runtimeCheckout': str(runtime), 'applicationVersion': application['Config']['Image'].rsplit(':', 1)[1],
                'createdUsers': [], 'purpose': 'functional-only', 'workloadMode': mode, 'createdAt': b.now(),
                'application': b.environment(application), 'k6Image': b.IMAGE,
                'sourceRevision': b.command('git', '-C', str(b.ROOT), 'rev-parse', 'HEAD'),
                'scriptSha256': {name: hashlib.sha256((b.TOOL / name).read_bytes()).hexdigest()
                                 for name in ['authenticated.js', 'mixed.js', 'baseline.js']}}
    b.write(directory / 'manifest.json', manifest)
    b.write(directory / 'persistent-before.json', persistent)
    (directory / 'runtime.yaml').write_text('services:\n  application:\n    env_file: !override\n'
        + '      - path: ' + json.dumps(str(runtime / 'backend/.env')) + '\n        required: true\n')
    print('Evidence: ' + str(directory), flush=True)
    try:
        date = b.get(b.PRODUCT + '/releases?view=recent&weeks=1&page=1&pageSize=20')['evaluatedOn']
        try:
            b.command('bash', str(b.TOOL / 'dataset.sh'), 'generate', 'authenticated', date)
        finally:
            manifest['disposablePostgresId'] = b.command('docker', 'ps', '-a', '--no-trunc',
                '--filter', 'name=^/' + PG + '$', '--format', '{{.ID}}')
            b.write(directory / 'manifest.json', manifest)
        b.write(directory / 'profile.json', json.loads(b.command('bash', str(b.TOOL / 'dataset.sh'), 'inspect')))
        b.command('docker', 'network', 'connect', b.NETWORK, PG)
        sql("CREATE ROLE videogame_keycloak LOGIN PASSWORD 'performance-identity-only';")
        sql("CREATE DATABASE performance_keycloak OWNER videogame_keycloak;")
        compose(directory, manifest, True)
        client = admin_client(runtime)
        if any(client.find_users(user['username']) for user in users):
            raise RuntimeError('Fresh disposable identity database unexpectedly contains synthetic users')
        # Only new, independently owned synthetic accounts. Admin OAuth is management-only;
        # the product VUs never receive admin credentials/tokens or call the token endpoint.
        with tempfile.TemporaryDirectory(prefix='vgp-performance-credentials-') as private:
            for user in users:
                user['password'] = secrets.token_urlsafe(24)
                client._request('POST', REALM, body={
                    'username': user['username'], 'email': user['email'], 'firstName': 'Synthetic',
                    'lastName': 'Player', 'enabled': True, 'requiredActions': [],
                    'credentials': [{'type': 'password', 'value': user['password'], 'temporary': False}],
                }, expected={201})
                found = client.find_users(user['username'])
                if len(found) != 1:
                    raise RuntimeError('Synthetic account creation was not unique')
                manifest['createdUsers'].append({key: found[0][key] for key in ['id', 'username', 'email']})
                b.write(directory / 'manifest.json', manifest)
            b.write(Path(private) / 'users.json', users)
            os.chmod(Path(private) / 'users.json', 0o600)
            application, keycloak = b.service('application'), b.service('keycloak')
            # DNS/port mapping preserves localhost Host, cookies, redirect URI and issuer.
            app_ip = application['NetworkSettings']['Networks'][b.NETWORK]['IPAddress']
            kc_ip = keycloak['NetworkSettings']['Networks'][b.NETWORK]['IPAddress']
            requests = [
                {'name': 'releases_recent', 'path': '/releases?view=recent&weeks=1&page=1&pageSize=20'},
                {'name': 'releases_upcoming', 'path': '/releases?view=upcoming&weeks=1&page=1&pageSize=20'},
                {'name': 'releases_featured', 'path': '/featured-releases'},
                {'name': 'catalogue_search', 'path': '/games?q=adventure&page=1&pageSize=20'},
                {'name': 'game_detail', 'path': '/games/' + users[0]['gameId']},
            ]
            workload = {'runId': directory.name, 'dataset': 'authenticated', 'origin': 'http://localhost:8080',
                        'keycloakOrigin': 'http://localhost:8180', 'cookieName': 'vgp_session',
                        'baseUrl': 'http://localhost:8080/api/v1', 'evaluatedOn': date,
                        'hosts': {'localhost:8080': app_ip + ':8080', 'localhost:8180': kc_ip + ':8080'},
                        'requests': requests, 'iterationsPerUser': 2, 'publicIterations': 20,
                        'requestPauseSeconds': 1, 'gameId': users[0]['gameId'],
                        'userPool': [{key: value for key, value in user.items() if key != 'password'} for user in users]}
            measure(directory, Path(private), workload, mode)
            remove_users(directory, manifest)
    finally:
        restore(directory)
    return directory


def report(directory):
    directory = Path(directory)
    evidence = json.loads((directory / 'evidence.json').read_text())
    manifest = json.loads((directory / 'manifest.json').read_text())
    observations = [json.loads((directory / 'before.json').read_text()),
                    *json.loads((directory / 'samples.json').read_text()),
                    json.loads((directory / 'after.json').read_text())]
    def measurement(observation, name, statistic):
        return next((m['value'] for m in observation['metrics'][name].get('measurements', [])
                     if m['statistic'] == statistic), None)
    ranges = {}
    for name in ['jvm.memory.used', 'jvm.threads.live', 'hikaricp.connections.active',
                 'hikaricp.connections.pending', 'hikaricp.connections.max']:
        values = [measurement(o, name, 'VALUE') for o in observations]
        values = [value for value in values if value is not None]
        ranges[name] = {'min': min(values), 'max': max(values)} if values else {'unavailable': True}
    deltas = {}
    for name, statistic in [('jvm.gc.pause', 'COUNT'), ('jvm.gc.pause', 'TOTAL_TIME'),
                            ('hikaricp.connections.timeout', 'COUNT')]:
        before, after = (measurement(o, name, statistic) for o in [observations[0], observations[-1]])
        deltas[name + ':' + statistic] = after - before if before is not None and after is not None else None
    pg_before, pg_after = observations[0]['database'], observations[-1]['database']
    cases = {}
    for name, case in evidence['cases'].items():
        trend = case.pop('percentilesMs') or {}
        cases[name] = {**case, 'percentilesMs': {key: trend.get(source) for key, source in
                                               [('p50', 'med'), ('p95', 'p(95)'), ('p99', 'p(99)')]}}
    restoration = json.loads((directory / 'restoration.json').read_text())
    for side in ['before', 'after']:
        restoration[side].pop('ratingsFingerprint', None)
    # Raw receipts retain aggregate fingerprints; tracked evidence needs only equality.
    restoration['persistentRatingsFingerprintUnchanged'] = restoration['unchanged']
    return {'runId': directory.name, 'functionalPassed': evidence['k6ExitCode'] == 0,
            'capacityInterpretationAllowed': False, 'sourceRevision': manifest['sourceRevision'],
            'syntheticUsersCreated': len(manifest['createdUsers']), 'scenarioResults': evidence.get('scenarioResults', {}),
            'http': evidence['http'], 'cases': cases, 'operations': evidence['operations'],
            'sessions': evidence['sessions'], 'remainingExperimentRatings': evidence['remainingRatings'],
            'clockAudit': evidence['clockAudit'],
            'telemetry': {'periodicSamples': len(observations)-2, 'ranges': ranges, 'counterDeltas': deltas,
                          'gcTimeUnit': observations[-1]['metrics']['jvm.gc.pause'].get('baseUnit'),
                          'postgresCounterDeltas': {key: pg_after[key]-pg_before[key] for key in
                                                   ['xact_commit', 'xact_rollback', 'blks_read', 'blks_hit', 'temp_files', 'temp_bytes', 'deadlocks']
                                                   if key in pg_before and key in pg_after},
                          'sampledUngrantedLocksMax': max(o['database'].get('ungranted_locks', 0) for o in observations),
                          'cgroupCountersRetained': ['cpu.stat', 'memory.current', 'memory.events'],
                          'observationFailures': evidence['observationFailures']},
            'restoration': restoration,
            'explicitAccountDeletion': json.loads((directory / 'users-restored.json').read_text())
                                       if (directory / 'users-restored.json').exists() else None}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    entry = sub.add_parser('run')
    entry.add_argument('--runtime-checkout', required=True)
    entry.add_argument('--workload', choices=['authenticated', 'mixed'], default='mixed')
    entry = sub.add_parser('restore')
    entry.add_argument('directory')
    entry = sub.add_parser('report')
    entry.add_argument('directory')
    entry.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.command == 'run':
        run(args.runtime_checkout, args.workload)
    elif args.command == 'restore':
        restore(args.directory)
    else:
        b.write(Path(args.output), report(args.directory))
