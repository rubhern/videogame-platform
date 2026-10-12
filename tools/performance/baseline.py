#!/usr/bin/env python3
"""Fixed local public-read baseline. Python standard library + Docker only."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import threading
import urllib.parse
import urllib.request

TOOL = Path(__file__).resolve().parent
ROOT = TOOL.parent.parent
IMAGE = 'grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34'
NETWORK = 'videogame-platform_default'
PRODUCT = 'http://127.0.0.1:8080/api/v1'
MANAGEMENT = 'http://127.0.0.1:8081/actuator'


def command(*args, input=None):
    return subprocess.check_output(args, input=input, text=True, timeout=120).strip()


def get(url, correlation=None):
    headers = {'Accept': 'application/json'}
    if correlation:
        headers['X-Correlation-ID'] = correlation
    # Local measurements must bypass proxies and never follow a redirect to another target.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(urllib.request.Request(url, headers=headers), timeout=10) as response:
        return json.load(response)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def service(name):
    ids = command('docker', 'ps', '--filter', 'label=com.docker.compose.project=videogame-platform',
                  '--filter', 'label=com.docker.compose.service=' + name, '--format', '{{.ID}}').splitlines()
    if len(ids) != 1:
        raise RuntimeError('Expected one running supported local ' + name + ' container')
    return json.loads(command('docker', 'inspect', ids[0]))[0]


def environment(container):
    # Never serialize the complete Docker inspection: it contains credentials.
    allowed = {'SPRING_PROFILES_ACTIVE', 'APPLICATION_DB_MAXIMUM_POOL_SIZE',
               'APPLICATION_DB_CONNECTION_TIMEOUT', 'APPLICATION_DB_VALIDATION_TIMEOUT',
               'APPLICATION_CATALOGUE_READINESS_TIMEOUT', 'CATALOGUE_JDBC_READ_TIMEOUT',
               'RATINGS_JDBC_OPERATION_TIMEOUT', 'TELEMETRY_OTLP_METRICS_ENABLED',
               'TELEMETRY_OTLP_METRICS_STEP', 'TELEMETRY_OTLP_TRACES_ENABLED',
               'TELEMETRY_DEPLOYMENT_ENVIRONMENT', 'CATALOGUE_SYNC_SCHEDULING_ENABLED', 'APPLICATION_FLYWAY_ENABLED'}
    settings = dict(entry.split('=', 1) for entry in container['Config']['Env'])
    return {'image': container['Config']['Image'], 'imageId': container['Image'],
            'memoryLimitBytes': container['HostConfig']['Memory'],
            'cpuLimit': container['HostConfig']['NanoCpus'] / 1e9,
            'startedAt': container['State']['StartedAt'],
            'settings': {key: settings[key] for key in sorted(allowed & settings.keys())}}


def snapshot(application, postgres, runner=None):
    meters = ['http.server.requests', 'jvm.memory.used', 'jvm.memory.max', 'jvm.gc.pause',
              'jvm.threads.live', 'process.cpu.usage', 'system.cpu.usage',
              'hikaricp.connections.active', 'hikaricp.connections.pending',
              'hikaricp.connections.max', 'hikaricp.connections.timeout', 'hikaricp.connections.acquire',
              'catalogue.localization.backfill', 'catalogue.localization.content']
    observed = {'at': now(), 'metrics': {}}
    for meter in meters:
        try:
            suffix = '?tag=area:heap' if meter.startswith('jvm.memory.') else ''
            observed['metrics'][meter] = get(MANAGEMENT + '/metrics/' + meter + suffix)
        except Exception as error:
            observed['metrics'][meter] = {'unavailable': type(error).__name__}
    try:
        sql = """BEGIN READ ONLY;
SET LOCAL statement_timeout = '3s';
SELECT row_to_json(d) FROM (
 SELECT clock_timestamp() AS at, numbackends, xact_commit, xact_rollback,
        blks_read, blks_hit, temp_files, temp_bytes, deadlocks, stats_reset,
        (SELECT json_agg(a) FROM (SELECT state, wait_event_type, wait_event, count(*)
          FROM pg_stat_activity WHERE datname=current_database()
          GROUP BY state, wait_event_type, wait_event) a) AS activity,
        (SELECT count(*) FROM pg_locks WHERE NOT granted) AS ungranted_locks
 FROM pg_stat_database WHERE datname = current_database()
) d;
ROLLBACK;
"""
        observed['database'] = json.loads(command('docker', 'exec', '-i', postgres['Id'],
            'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'videogame_app',
            '-d', dict(entry.split('=', 1) for entry in postgres['Config']['Env'])
                .get('POSTGRES_DB', 'videogame_platform'), input=sql))
    except Exception as error:
        observed['database'] = {'unavailable': type(error).__name__}
    try:
        observed['containers'] = [json.loads(line) for line in command('docker', 'stats',
            '--no-stream', '--format', '{{json .}}', application['Id'], postgres['Id'], *([runner] if runner else [])).splitlines()]
    except Exception as error:
        observed['containers'] = {'unavailable': type(error).__name__}
    observed['cgroups'] = {}
    for label, container in [('application', application['Id']), ('postgres', postgres['Id'])] + ([('k6', runner)] if runner else []):
        try:
            observed['cgroups'][label] = {name: command('docker', 'exec', container, 'cat', '/sys/fs/cgroup/' + name)
                for name in ['cpu.stat', 'memory.current', 'memory.events']}
        except Exception as error:
            observed['cgroups'][label] = {'unavailable': type(error).__name__}
    return observed


def clock_audit(directory):
    # Concurrent completions can be emitted in a different order from their timestamps.
    # Only a reversal exceeding 100 ms is a guest-clock backstep candidate; a paired
    # host monotonic/wall audit independently checks the clock during growth runs.
    previous = None
    backsteps, reordered = [], []
    samples = 0
    metrics_file = directory / 'metrics.json'
    if metrics_file.exists():
        with metrics_file.open() as raw:
            for line in raw:
                point = json.loads(line)
                if point['type'] == 'Point' and point['metric'] == 'http_reqs':
                    timestamp = datetime.datetime.fromisoformat(point['data']['time'])
                    if previous and timestamp < previous:
                        reversal = (previous - timestamp).total_seconds()
                        entry = {'sample': samples, 'previous': previous.isoformat(), 'at': timestamp.isoformat(), 'seconds': reversal}
                        (backsteps if reversal > 0.1 else reordered).append(entry)
                    previous = timestamp
                    samples += 1
    return {'httpSamples': samples, 'backsteps': backsteps, 'concurrentReorderings': reordered,
            'rawMetricsAvailable': metrics_file.exists()}


def growth_requests():
    def identity(value):
        import uuid
        return str(uuid.UUID(hashlib.md5(value.encode()).hexdigest()))
    pc, ps5 = [identity('performance:platform:' + str(n)) for n in [0, 1]]
    worldwide, north_america = [identity('performance:region:' + str(n)) for n in [0, 2]]
    requests = []
    def release(name, view, weeks=1, page=1, **filters):
        parameters = {'view': view, 'weeks': weeks, 'page': page, 'pageSize': 20, **filters}
        requests.append({'name': name, 'kind': 'releases_' + view, 'view': view, 'page': page,
                         **{key: value for key, value in filters.items() if key in ['platformIds', 'regionIds']},
                         'path': '/releases?' + urllib.parse.urlencode(parameters, doseq=True)})
    release('releases_recent', 'recent')
    release('releases_upcoming', 'upcoming')
    requests.append({'name': 'releases_featured', 'kind': 'releases_featured', 'path': '/featured-releases'})
    release('recent_pc', 'recent', 4, platformIds=[pc])
    release('recent_multi_filters', 'recent', 4, platformIds=[pc, ps5], regionIds=[worldwide, north_america])
    release('upcoming_north_america', 'upcoming', 4, regionIds=[north_america])
    release('upcoming_approximate', 'upcoming', 4, includeApproximateDates='true')
    release('recent_deep', 'recent', 4, page=51)
    release('upcoming_deep', 'upcoming', 4, page=51)
    for name, query, page, total in [
        ('search_selective', 'Needle', 1, 50), ('search_broad', 'Adventure', 1, 50000),
        ('search_deep_10k', 'Adventure', 501, 50000), ('search_deep_48k', 'Adventure', 2401, 50000),
        ('search_alias', 'Aliasquest 000001', 1, 1),
        ('search_accent', 'Adventure Élite', 1, 5000), ('search_normalized', '  adventure ELITE  ', 1, 5000)]:
        requests.append({'name': name, 'kind': 'catalogue_search', 'page': page, 'expectedTotal': total,
                         'path': '/games?' + urllib.parse.urlencode({'q': query, 'page': page, 'pageSize': 20})})
    for n, fanout in [(4, 1), (1, 2), (2, 3), (3, 10)]:
        game = identity('performance:game:' + str(n))
        requests.append({'name': 'detail_fanout_' + str(fanout), 'kind': 'game_detail',
                         'path': '/games/' + game, 'gameId': game, 'releaseCount': fanout})
    return requests


def growth(application):
    settings = dict(entry.split('=', 1) for entry in application['Config']['Env'])
    if settings.get('APPLICATION_DB_URL') != 'jdbc:postgresql://videogame-platform-performance-postgres:5432/videogame_performance':
        raise RuntimeError('Apply compose.growth.yaml to the existing local application first')
    if settings.get('APPLICATION_FLYWAY_ENABLED') != 'false' or any(settings.get(key) for key in ['IGDB_CLIENT_ID', 'IGDB_CLIENT_SECRET']):
        raise RuntimeError('Growth requires disabled Flyway and empty provider credentials')
    if command('docker', 'ps', '--filter', 'label=com.docker.compose.project=videogame-platform',
               '--filter', 'label=com.docker.compose.service=catalogue-localizer', '--format', '{{.ID}}'):
        raise RuntimeError('Stop the local localization helper for this control experiment')
    # dataset.sh enforces image, label, auto-removal and anonymous-volume boundary.
    profile = json.loads(command('bash', str(TOOL / 'dataset.sh'), 'inspect'))
    if profile['counts']['games'] != 50000 or profile['counts']['releases'] != 200000 or profile['counts']['ratings'] != 500000:
        raise RuntimeError('Expected the existing growth dataset cardinalities')
    boundary = json.loads(command('docker', 'exec', 'videogame-platform-performance-postgres',
        'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'videogame_performance',
        '-c', 'SELECT row_to_json(b) FROM performance_data.boundary b'))
    checksum = hashlib.sha256(b''.join(path.read_bytes() for path in sorted((ROOT / 'backend/src/main/resources/db/migration').glob('V*.sql')))).hexdigest()
    if boundary['profile'] != 'growth' or boundary['purpose'] != 'disposable-performance-only' or boundary['schema_checksum'] != checksum:
        raise RuntimeError('Expected the growth disposable marker and current schema checksum')
    profile['synthetic_boundary'] = boundary
    postgres = json.loads(command('docker', 'inspect', 'videogame-platform-performance-postgres'))[0]
    if NETWORK not in postgres['NetworkSettings']['Networks']:
        raise RuntimeError('Connect the verified disposable database to the supported local network')
    if profile['synthetic_boundary']['reference_date'] != profile['evaluation_date_madrid']:
        raise RuntimeError('Regenerate growth for the application evaluation date')
    result = Path(tempfile.mkdtemp(prefix='growth-' + datetime.datetime.now(datetime.timezone.utc)
                                  .strftime('%Y%m%dT%H%M%SZ-'), dir=TOOL / 'results'))
    write(result / 'profile.json', profile)
    requests = growth_requests()
    preflight = []
    for request in requests:
        body = get(PRODUCT + request['path'], 'perf-' + result.name + '-preflight-' + request['name'])
        if request['kind'].startswith('releases_') and request['kind'] != 'releases_featured':
            if body['page']['totalItems'] <= (request['page']-1)*20 or not body['items']:
                raise RuntimeError('Empty release fixture: ' + request['name'])
            request['expectedTotal'] = body['page']['totalItems']
            evaluated_on = body['evaluatedOn']
        if request['kind'] == 'catalogue_search':
            if body['page']['totalItems'] != request['expectedTotal'] or not body['items']:
                raise RuntimeError('Unexpected search fixture: ' + request['name'])
            request['firstGameId'] = body['items'][0]['gameId']
        if request['kind'] == 'game_detail':
            if len(body['releases']) != request['releaseCount'] or body['ratingStatistics']['status'] != 'available':
                raise RuntimeError('Unexpected detail fixture: ' + request['name'])
            request['ratingCount'] = body['ratingStatistics']['count']
        preflight.append({'name': request['name'], 'page': body.get('page'),
                          'items': len(body.get('items', [])), 'releaseCount': len(body.get('releases', [])),
                          'ratingStatistics': body.get('ratingStatistics')})
    by_name = {request['name']: request for request in requests}
    if evaluated_on != profile['evaluation_date_madrid']:
        raise RuntimeError('Application and dataset evaluation dates disagree')
    if by_name['search_accent']['firstGameId'] != by_name['search_normalized']['firstGameId']:
        raise RuntimeError('Accent and normalized searches disagree')
    write(result / 'preflight.json', preflight)
    experiment = {'dataset': 'growth', 'durationSecondsPerLevel': 300, 'rates': [5, 10, 25, 50],
                  'requests': requests, 'preflightRequests': 20, 'levelDirectories': [],
                  'criterion': 'Functional checks only; latency, dropped iterations and achieved rate are observations',
                  'startedAt': now(), 'sampleIntervalSeconds': 10, 'cooldownSeconds': 30}
    write(result / 'experiment.json', experiment)
    exit_code = 0
    for rate in experiment['rates']:
        level = result / ('rps-' + str(rate))
        level.mkdir()
        run_id = 'perf-' + result.name + '-rps-' + str(rate)
        runner = run_id + '-k6'
        workload = {'runId': run_id, 'dataset': 'growth', 'evaluatedOn': evaluated_on, 'requests': requests,
                    'rate': rate, 'durationSeconds': 300, 'preAllocatedVUs': rate*2, 'maxVUs': rate*12}
        write(level / 'workload.json', workload)
        docker_args = ['docker', 'run', '--rm', '--network', NETWORK, '--user', f'{os.getuid()}:{os.getgid()}',
                       '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                       '--memory', '1g', '--cpus', '1', '--mount', f'type=bind,source={TOOL},target=/scripts,readonly',
                       '--mount', f'type=bind,source={level},target=/results', '--env', 'K6_NO_USAGE_REPORT=true', IMAGE]
        manifest = {'runId': run_id, 'dataset': 'growth', 'harnessRevision': command('git', '-C', str(ROOT), 'rev-parse', 'HEAD'),
                    'scriptSha256': hashlib.sha256((TOOL / 'baseline.js').read_bytes()).hexdigest(),
                    'runnerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    'k6Image': IMAGE, 'k6Version': command(*docker_args, 'version'),
                    'application': environment(application), 'postgres': environment(postgres),
                    'applicationInfo': get(MANAGEMENT + '/info'),
                    'options': json.loads(command(*docker_args, 'inspect', '/scripts/baseline.js')),
                    'javaVersion': command('docker', 'exec', application['Id'], 'java', '--version'),
                    'docker': json.loads(command('docker', 'info', '--format',
                        '{"version":"{{.ServerVersion}}","os":"{{.OperatingSystem}}","kernel":"{{.KernelVersion}}","architecture":"{{.Architecture}}","cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}}}')),
                    'runnerLimits': {'memoryBytes': 1073741824, 'cpus': 1}, 'workload': workload}
        write(level / 'before.json', snapshot(application, postgres))
        stopped = threading.Event()
        clock_samples = []
        def audit_wall():
            while not stopped.is_set():
                clock_samples.append({'wallSeconds': time.time(), 'monotonicSeconds': time.monotonic()})
                stopped.wait(0.25)
        def sample():
            with (level / 'observations.jsonl').open('w') as output:
                while not stopped.wait(10):
                    output.write(json.dumps(snapshot(application, postgres, runner)) + '\n')
                    output.flush()
        manifest['startedAt'] = now()
        write(level / 'manifest.json', manifest)
        print('Results: ' + str(level), flush=True)
        start = time.monotonic()
        audit_thread = threading.Thread(target=audit_wall)
        sampler = threading.Thread(target=sample)
        audit_thread.start()
        sampler.start()
        try:
            with (level / 'console.log').open('w') as output:
                completed = subprocess.run(docker_args[:2] + ['--name', runner] + docker_args[2:] +
                    ['run', '--quiet', '--out', 'json=/results/metrics.json', '/scripts/baseline.js'],
                    stdout=output, stderr=subprocess.STDOUT, timeout=420)
        finally:
            stopped.set()
            audit_thread.join()
            sampler.join()
        manifest.update(finishedAt=now(), elapsedMonotonicSeconds=time.monotonic()-start, exitCode=completed.returncode)
        audit = clock_audit(level)
        audit['pairedWallMonotonicSamples'] = len(clock_samples)
        audit['wallMonotonicBacksteps'] = [
            {'sample': i, 'wallDelta': b['wallSeconds']-a['wallSeconds'],
             'monotonicDelta': b['monotonicSeconds']-a['monotonicSeconds']}
            for i, (a,b) in enumerate(zip(clock_samples, clock_samples[1:]))
            if (b['wallSeconds']-a['wallSeconds']) - (b['monotonicSeconds']-a['monotonicSeconds']) < -0.1]
        write(level / 'clock-samples.json', clock_samples)
        manifest['clockAudit'] = audit
        write(level / 'manifest.json', manifest)
        write(level / 'after.json', snapshot(application, postgres))
        print((level / 'console.log').read_text(), end='', flush=True)
        experiment['levelDirectories'].append(level.name)
        write(result / 'experiment.json', experiment)
        exit_code = max(exit_code, completed.returncode)
        if audit['backsteps'] or audit['wallMonotonicBacksteps']:
            print('Clock backstep: stop escalation; capacity interpretation is invalid', flush=True)
            break
        # Idle interval separates levels and captures recovery without another workload.
        time.sleep(30)
        write(level / 'cooldown.json', snapshot(application, postgres))
        if get(MANAGEMENT + '/health/readiness')['status'] != 'UP':
            print('Readiness lost: stop escalation', flush=True)
            break
    experiment['finishedAt'] = now()
    write(result / 'experiment.json', experiment)
    print('Experiment: ' + str(result), flush=True)
    return exit_code


def main():
    if sys.argv[1:] not in ([], ['growth']):
        raise RuntimeError('Usage: python3 tools/performance/baseline.py [growth] (fixed local workloads; no overrides)')
    context = os.environ.get('DOCKER_CONTEXT')
    endpoint = (command('docker', 'context', 'inspect', context, '--format', '{{.Endpoints.docker.Host}}')
                if context else os.environ.get('DOCKER_HOST') or
                command('docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'))
    if not endpoint.startswith(('unix:///', 'npipe:////./pipe/')):
        raise RuntimeError('Baseline requires a local Docker engine')
    application = service('application')
    ports = application['HostConfig']['PortBindings']
    if ports.get('8080/tcp') != [{'HostIp': '127.0.0.1', 'HostPort': '8080'}]:
        raise RuntimeError('Expected the supported loopback application port')
    if NETWORK not in application['NetworkSettings']['Networks']:
        raise RuntimeError('Expected the supported local application network')
    settings = dict(entry.split('=', 1) for entry in application['Config']['Env'])
    if settings.get('CATALOGUE_SYNC_SCHEDULING_ENABLED', 'false').lower() != 'false':
        raise RuntimeError('Disable scheduled synchronization for this isolated public-read baseline')
    if get(MANAGEMENT + '/health/readiness')['status'] != 'UP':
        raise RuntimeError('Application is not ready')

    (TOOL / 'results').mkdir(exist_ok=True)
    if sys.argv[1:] == ['growth']:
        return growth(application)
    postgres = service('postgres')
    if settings.get('APPLICATION_DB_URL') != 'jdbc:postgresql://postgres:5432/videogame_platform':
        raise RuntimeError('Expected the supported current local application database')
    result = Path(tempfile.mkdtemp(prefix='current-' + datetime.datetime.now(datetime.timezone.utc)
                                  .strftime('%Y%m%dT%H%M%SZ-'), dir=TOOL / 'results'))
    run_id = 'perf-' + result.name
    profile = json.loads(command('bash', str(TOOL / 'dataset.sh'), 'current'))
    write(result / 'profile.json', profile)
    search = get(PRODUCT + '/games?q=zelda&page=1&pageSize=20', run_id + '-preflight-search')
    if not search.get('items'):
        raise RuntimeError('The current catalogue needs a Zelda match for this fixed baseline')
    game_id = search['items'][0]['gameId']
    recent = get(PRODUCT + '/releases?view=recent&weeks=1&page=1&pageSize=20', run_id + '-preflight-recent')
    upcoming = get(PRODUCT + '/releases?view=upcoming&weeks=1&page=1&pageSize=20', run_id + '-preflight-upcoming')
    featured = get(PRODUCT + '/featured-releases', run_id + '-preflight-featured')
    detail = get(PRODUCT + '/games/' + urllib.parse.quote(game_id, safe=''), run_id + '-preflight-detail')
    workload = {'runId': run_id, 'gameId': game_id, 'evaluatedOn': recent['evaluatedOn']}
    write(result / 'workload.json', workload)
    docker_args = ['docker', 'run', '--rm', '--network', NETWORK, '--user', f'{os.getuid()}:{os.getgid()}',
                   '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                   '--memory', '256m', '--cpus', '1',
                   '--mount', f'type=bind,source={TOOL},target=/scripts,readonly',
                   '--mount', f'type=bind,source={result},target=/results',
                   '--env', 'K6_NO_USAGE_REPORT=true', IMAGE]
    options = json.loads(command(*docker_args, 'inspect', '/scripts/baseline.js'))
    manifest = {'runId': run_id, 'dataset': 'current', 'harnessRevision': command('git', '-C', str(ROOT), 'rev-parse', 'HEAD'),
                'scriptSha256': hashlib.sha256((TOOL / 'baseline.js').read_bytes()).hexdigest(),
                'k6Image': IMAGE, 'k6Version': command(*docker_args, 'version'),
                'docker': json.loads(command('docker', 'info', '--format',
                    '{"version":"{{.ServerVersion}}","os":"{{.OperatingSystem}}","kernel":"{{.KernelVersion}}","architecture":"{{.Architecture}}","cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}}}')),
                'application': environment(application), 'postgres': environment(postgres),
                'applicationInfo': get(MANAGEMENT + '/info'), 'options': options,
                'javaVersion': command('docker', 'exec', application['Id'], 'java', '--version'),
                'runnerLimits': {'memoryBytes': 268435456, 'cpus': 1},
                'preflight': {'requests': 5, 'searchTotal': search['page']['totalItems'],
                              'recentTotal': recent['page']['totalItems'], 'upcomingTotal': upcoming['page']['totalItems'],
                              'featuredItems': len(featured['items']), 'featuredSelection': featured['selection'],
                              'detailReleases': len(detail['releases']), 'ratingStatistics': detail['ratingStatistics']},
                'workload': workload}
    write(result / 'before.json', snapshot(application, postgres))
    manifest['startedAt'] = now()
    write(result / 'manifest.json', manifest)
    print('Results: ' + str(result), flush=True)
    started_monotonic = time.monotonic()
    with (result / 'console.log').open('w') as output:
        completed = subprocess.run(docker_args + ['run', '--quiet', '--out', 'json=/results/metrics.json',
                                                   '/scripts/baseline.js'], stdout=output,
                                   stderr=subprocess.STDOUT, timeout=180)
    manifest.update(finishedAt=now(), elapsedMonotonicSeconds=time.monotonic() - started_monotonic,
                    exitCode=completed.returncode)
    write(result / 'manifest.json', manifest)
    # A stepped VM wall clock distorts k6's summary rate and time-window correlation.
    previous = None
    backsteps = []
    samples = 0
    metrics_file = result / 'metrics.json'
    for line in metrics_file.read_text().splitlines() if metrics_file.exists() else []:
        point = json.loads(line)
        if point['type'] == 'Point' and point['metric'] == 'http_reqs':
            timestamp = datetime.datetime.fromisoformat(point['data']['time'])
            if previous and timestamp < previous:
                backsteps.append({'sample': samples, 'previous': previous.isoformat(),
                                  'at': timestamp.isoformat()})
            previous = timestamp
            samples += 1
    manifest['clockAudit'] = {'httpSamples': samples, 'backsteps': backsteps,
                            'rawMetricsAvailable': metrics_file.exists()}
    write(result / 'manifest.json', manifest)
    write(result / 'after.json', snapshot(application, postgres))
    print((result / 'console.log').read_text(), end='')
    print('k6 exit code: ' + str(completed.returncode) + '; summary.json and timestamped metrics.json retained')
    if backsteps:
        print('Observed backwards wall-clock steps: ' + str(len(backsteps))
              + '; interpret summary throughput and time-window telemetry with manifest clockAudit.')
    return completed.returncode


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        # HTTP/container errors may contain sensitive material: show only their class.
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        sys.exit('Baseline failed: ' + message)
