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
               'TELEMETRY_DEPLOYMENT_ENVIRONMENT', 'CATALOGUE_SYNC_SCHEDULING_ENABLED'}
    settings = dict(entry.split('=', 1) for entry in container['Config']['Env'])
    return {'image': container['Config']['Image'], 'imageId': container['Image'],
            'memoryLimitBytes': container['HostConfig']['Memory'],
            'cpuLimit': container['HostConfig']['NanoCpus'] / 1e9,
            'startedAt': container['State']['StartedAt'],
            'settings': {key: settings[key] for key in sorted(allowed & settings.keys())}}


def snapshot(application, postgres):
    meters = ['http.server.requests', 'jvm.memory.used', 'jvm.memory.max', 'jvm.gc.pause',
              'jvm.threads.live', 'process.cpu.usage', 'system.cpu.usage',
              'hikaricp.connections.active', 'hikaricp.connections.pending',
              'hikaricp.connections.max', 'hikaricp.connections.timeout', 'hikaricp.connections.acquire']
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
        blks_read, blks_hit, temp_files, temp_bytes, deadlocks, stats_reset
 FROM pg_stat_database WHERE datname = current_database()
) d;
ROLLBACK;
"""
        observed['database'] = json.loads(command('docker', 'exec', '-i', postgres['Id'],
            'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'videogame_app',
            '-d', 'videogame_platform', input=sql))
    except Exception as error:
        observed['database'] = {'unavailable': type(error).__name__}
    try:
        observed['containers'] = [json.loads(line) for line in command('docker', 'stats',
            '--no-stream', '--format', '{{json .}}', application['Id'], postgres['Id']).splitlines()]
    except Exception as error:
        observed['containers'] = {'unavailable': type(error).__name__}
    return observed


def main():
    if len(sys.argv) != 1:
        raise RuntimeError('Usage: python3 tools/performance/baseline.py (fixed local workload; no overrides)')
    context = os.environ.get('DOCKER_CONTEXT')
    endpoint = (command('docker', 'context', 'inspect', context, '--format', '{{.Endpoints.docker.Host}}')
                if context else os.environ.get('DOCKER_HOST') or
                command('docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'))
    if not endpoint.startswith(('unix:///', 'npipe:////./pipe/')):
        raise RuntimeError('Baseline requires a local Docker engine')
    application, postgres = service('application'), service('postgres')
    ports = application['HostConfig']['PortBindings']
    if ports.get('8080/tcp') != [{'HostIp': '127.0.0.1', 'HostPort': '8080'}]:
        raise RuntimeError('Expected the supported loopback application port')
    if NETWORK not in application['NetworkSettings']['Networks']:
        raise RuntimeError('Expected the supported local application network')
    settings = dict(entry.split('=', 1) for entry in application['Config']['Env'])
    if settings.get('APPLICATION_DB_URL') != 'jdbc:postgresql://postgres:5432/videogame_platform':
        raise RuntimeError('Expected the supported current local application database')
    if settings.get('CATALOGUE_SYNC_SCHEDULING_ENABLED', 'false').lower() != 'false':
        raise RuntimeError('Disable scheduled synchronization for this isolated public-read baseline')
    if get(MANAGEMENT + '/health/readiness')['status'] != 'UP':
        raise RuntimeError('Application is not ready')

    (TOOL / 'results').mkdir(exist_ok=True)
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
