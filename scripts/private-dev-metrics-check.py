#!/usr/bin/env python3
"""Metrics assertions called by validate-private-dev-runtime.sh; never print credentials."""
import argparse
import base64
import json
import math
import pathlib
import re
import subprocess
import time
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument('--env-file')
parser.add_argument('--config')
parser.add_argument('--compose-override')
parser.add_argument('--static', action='store_true')
parser.add_argument('--synthetic', action='store_true')
parser.add_argument('--local', action='store_true')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
dashboards = sorted((root / 'deploy/private-dev/grafana/dashboards').glob('*.json'))
expected_uids = {'vgp-runtime', 'vgp-application', 'vgp-synchronization', 'vgp-product'}
assert {p.stem for p in dashboards} == expected_uids
for path in dashboards:
    dashboard = json.loads(path.read_text())
    assert dashboard['uid'] == path.stem and dashboard['editable'] is False
    assert dashboard['refresh'] in {'1m', '5m'}
    assert {link['url'] for link in dashboard['links']} == {'/d/' + uid for uid in expected_uids}
    ids = set()
    cells = set()
    for panel in dashboard['panels']:
        assert panel['id'] not in ids
        ids.add(panel['id'])
        grid = panel['gridPos']
        assert 0 <= grid['x'] and grid['x'] + grid['w'] <= 24
        for x in range(grid['x'], grid['x'] + grid['w']):
            for y in range(grid['y'], grid['y'] + grid['h']):
                assert (x,y) not in cells, 'Overlapping panels'
                cells.add((x,y))
        for target in panel.get('targets', []):
            uid = target['datasource']['uid']
            assert uid in {'private-dev-prometheus', 'private-dev-postgres'}
            if uid == 'private-dev-prometheus':
                expression = target['expr']
                assert 'job=' in expression
                assert 'or vector(0)' not in expression
                assert not re.search(r'(?i)(user_id|game_id|release_id|session_id|correlation|trace_id|token|password|search_term)', expression)
                assert set(re.findall(r'\$[A-Za-z_]+', expression)) <= {'$__rate_interval', '$__range', '$route', '$method'}
                if path.stem == 'vgp-runtime':
                    assert 'http_server' not in expression and 'catalogue_' not in expression
            else:
                assert target['rawSql'].startswith('SELECT ')
                assert re.search(r'(catalogue|ratings|public)\.observability_', target['rawSql'])
                assert not re.search(r'(?i)\b(user_id|INSERT|UPDATE|DELETE|DROP|ALTER)\b', target['rawSql'])
                if 'rankings' in target['rawSql'] or 'ORDER BY started_at DESC' in target['rawSql']:
                    assert 'ordering_key' in target['rawSql'] and 'LIMIT ' in target['rawSql']
if args.static:
    print('Dashboard JSON, datasource references, bounded queries and refresh passed')
    raise SystemExit(0)
if not args.config or not args.env_file:
    parser.error('--config and --env-file are required for runtime checks')
config = json.loads(pathlib.Path(args.config).read_text())
if args.synthetic:
    assert config['name'].startswith(('vgp-telemetry-smoke-', 'vgp-local-metrics-smoke-')), 'Synthetic work requires an explicitly disposable project'
files = ('compose.yaml', 'compose.observability.yaml') if args.local else (
    'deploy/private-dev/compose.yaml', 'deploy/private-dev/compose.telemetry-smoke.yaml')
compose = ['docker', 'compose', '--env-file', args.env_file, '--project-name', config['name']]
for file in files:
    compose += ['--file', str(root / file)]
if args.compose_override:
    compose += ['--file', args.compose_override]
if args.local:
    compose += ['--profile', 'observability']
secret = pathlib.Path(config['secrets']['grafana_admin_password']['file']).read_text().strip()
assert secret, 'Grafana secret must not be empty'


def request(url, data=None, authenticated=False, expected=200):
    # curl receives all request material through stdin, never argv/environment/logs.
    lines = ['silent', 'show-error', 'max-time = 20', 'url = ' + json.dumps(url),
             'write-out = "\\n%{http_code}"']
    if authenticated:
        token = base64.b64encode(('owner:' + secret).encode()).decode()
        lines.append('header = ' + json.dumps('Authorization: Basic ' + token))
    if data is not None:
        lines += ['header = "Content-Type: application/json"',
                  'data = ' + json.dumps(json.dumps(data))]
    result = subprocess.run(compose + ['run', '--rm', '--no-deps', '-T', 'telemetry-smoke',
                                      '--config', '-'], input='\n'.join(lines),
                            text=True, capture_output=True, timeout=40)
    assert result.returncode == 0, 'Metrics HTTP transport failed (request details withheld)'
    body, status = result.stdout.rsplit('\n', 1)
    assert int(status) == expected, f'Metrics HTTP status {status}; expected {expected}'
    return json.loads(body) if body else None


def query(expression, grafana=False, at=None):
    base = 'http://grafana:3000/api/datasources/proxy/uid/private-dev-prometheus' if grafana else 'http://prometheus:9090'
    params = {'query': expression}
    if at is not None:
        params['time'] = at
    result = request(base + '/api/v1/query?' + urllib.parse.urlencode(params), authenticated=grafana)
    assert result['status'] == 'success', 'Prometheus query failed'
    return result['data']['result']


def eventually(check, description, attempts=30):
    for _ in range(attempts):
        try:
            if check():
                return
        except (AssertionError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise AssertionError(description)


synthetic_start = str(time.time_ns() - 120_000_000_000)


def synthetic_metrics(count):
    """Only the isolated smoke project receives these explicitly synthetic samples."""
    now = time.time_ns()
    metrics = []
    def point(labels, **values):
        return dict(timeUnixNano=str(now), startTimeUnixNano=synthetic_start,
                    attributes=[{'key': k, 'value': {'stringValue': v}} for k, v in labels.items()], **values)
    def counter(name, labels, unit='', multiplier=1):
        metrics.append({'name': name, 'unit': unit, 'sum': {'aggregationTemporality': 2, 'isMonotonic': True,
                         'dataPoints': [point(labels, asDouble=count*multiplier)]}})
    def histogram(name, labels, unit='', value=100):
        metrics.append({'name': name, 'unit': unit, 'histogram': {'aggregationTemporality': 2,
                        'dataPoints': [point(labels, count=str(count), sum=count*value,
                                            bucketCounts=[str(count), '0'], explicitBounds=[value])]}})
    histogram('http.server.requests', {'uri': '/api/v1/releases', 'method': 'GET', 'status': '200', 'outcome': 'SUCCESS', 'synthetic_unapproved_dimension': 'must-not-be-stored'}, 'ms')
    for method, status, value in [('GET', '404', 100), ('GET', '401', 100),
                                  ('PUT', '404', 1000), ('DELETE', '404', 1000)]:
        histogram('http.server.requests', {'uri': '/api/v1/me/ratings/{gameId}',
                  'method': method, 'status': status, 'outcome': 'CLIENT_ERROR'}, 'ms', value)
    counter('catalogue.synchronization.run', {'outcome': 'succeeded'})
    histogram('catalogue.synchronization.run.duration', {'outcome': 'succeeded'}, 'ms')
    histogram('catalogue.synchronization.run.records', {'kind': 'unchanged_games'})
    histogram('catalogue.synchronization.run.records', {'kind': 'logo_observed_games'})
    counter('catalogue.synchronization.provider.request', {'operation': 'window', 'outcome': 'success'})
    histogram('catalogue.synchronization.provider.request.duration', {'operation': 'window'}, 'ms')
    histogram('hikaricp.connections.acquire', {'pool': 'HikariPool-1'}, 'ms', 7)
    histogram('jvm.gc.pause', {'action': 'end of minor GC', 'cause': 'G1 Evacuation Pause'}, 'ms', 12)
    counter('hikaricp.connections.timeout', {'pool': 'HikariPool-1'}, multiplier=0)
    counter('platform.http.errors', {'code': 'AUTHENTICATION_REQUIRED', 'kind': 'client_rejection'})
    counter('platform.http.errors', {'code': 'RATING_NOT_FOUND', 'kind': 'expected_absence'})
    counter('catalogue.featured.selection', {'status': 'ranked', 'freshness': 'fresh', 'month': 'current', 'lead_image': 'artwork'})
    counter('catalogue.synchronization.provider.retry', {'operation': 'window'})
    counter('catalogue.synchronization.provider.mapping.failure', {'reason': 'invalid_record'})
    counter('catalogue.search.result.outcome', {'outcome': 'results'})
    for eligibility in ('ELIGIBLE_RELEASE_FOUND', 'RELEASE_NOT_OCCURRED'):
        counter('catalogue.game.details', {'eligibility': eligibility, 'aggregate': 'available'})
    histogram('catalogue.releases.result.count', {'view': 'recent'})
    counter('process.cpu.time', {}, 'nanoseconds', 1_000_000_000)
    for name, unit, labels, value in [
        ('jvm.memory.used', 'bytes', {'area': 'heap', 'id': 'G1 Eden Space'}, 1024),
        ('jvm.memory.max', 'bytes', {'area': 'heap', 'id': 'G1 Eden Space'}, 2048),
        ('process.uptime', 'seconds', {}, 120),
        ('jvm.memory.committed', 'bytes', {'area': 'heap', 'id': 'G1 Eden Space'}, 2048),
        ('jvm.memory.used', 'bytes', {'area': 'nonheap', 'id': 'Metaspace'}, 512),
        ('jvm.threads.peak', 'threads', {}, 20),
        ('jvm.threads.daemon', 'threads', {}, 8),
        ('jvm.threads.states', 'threads', {'state': 'runnable'}, 10),
        ('system.cpu.usage', '', {}, 0.2),
        ('hikaricp.connections.idle', '', {'pool': 'HikariPool-1'}, 9),
        ('jvm.threads.live', 'threads', {}, 10), ('process.cpu.usage', '', {}, 0.1),
        ('hikaricp.connections.active', '', {'pool': 'HikariPool-1'}, 1),
        ('hikaricp.connections.max', '', {'pool': 'HikariPool-1'}, 10),
        ('hikaricp.connections.pending', '', {'pool': 'HikariPool-1'}, 0)]:
        metrics.append({'name': name, 'unit': unit, 'gauge': {'dataPoints': [point(labels, asDouble=value)]}})
    return {'resourceMetrics': [{'resource': {'attributes': [
        {'key': 'service.name', 'value': {'stringValue': 'metrics-smoke'}},
        {'key': 'service.instance.id', 'value': {'stringValue': 'synthetic-not-for-storage'}}]},
        'scopeMetrics': [{'metrics': metrics}]}]}


for service_name in ('telemetry', 'prometheus', 'grafana'):
    container = subprocess.check_output(compose + ['ps', '--quiet', service_name], text=True).strip()
    assert container, f'{service_name} is absent'
    host = json.loads(subprocess.check_output(
        ['docker', 'inspect', '--format', '{{json .HostConfig}}', container], text=True))
    expected_service = config['services'][service_name]
    assert host['Memory'] == int(expected_service['mem_limit']), f'{service_name} memory bound drift'
    assert host['NanoCpus'] == int(float(expected_service['cpus']) * 1_000_000_000), f'{service_name} CPU bound drift'
    assert host['PidsLimit'] == int(expected_service['pids_limit']), f'{service_name} PID bound drift'
    assert host['LogConfig'] == {'Type': 'local', 'Config': {'max-file': '3', 'max-size': '10m'}}
    bindings = host.get('PortBindings') or {}
    if args.local and service_name == 'telemetry':
        assert bindings == {'4318/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '4318'}]}, 'Local OTLP access drift'
    elif service_name != 'grafana' or (args.synthetic and not args.local):
        assert not bindings, f'{service_name} unexpectedly publishes a port'
    else:
        assert bindings == {'3000/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '3000'}]}, 'Grafana access drift'
    assert host['ReadonlyRootfs'] and host['CapDrop'] == ['ALL'], f'{service_name} hardening drift'
print('Running metrics services match configured resource, log and access bounds')


def expand(expression):
    return expression.replace('$__rate_interval', '4m').replace('$__range', '7d').replace('$route', '/api/v1/.*').replace('$method', '.*')


def panel_query(uid, title, index=0):
    dashboard = json.loads((root / 'deploy/private-dev/grafana/dashboards' / (uid + '.json')).read_text())
    panel = next(p for p in dashboard['panels'] if p['title'] == title)
    return expand(panel['targets'][index]['expr'])


def values(uid, title):
    return [float(s['value'][1]) for s in query(panel_query(uid, title), grafana=True)]


def sql_query(target):
    body = {'from': str(int((time.time() - 30*86400)*1000)), 'to': str(int(time.time()*1000)),
            'queries': [dict(target, intervalMs=60000, maxDataPoints=100)]}
    result = request('http://grafana:3000/api/ds/query', body, authenticated=True)
    response = result['results']['A']
    assert not response.get('error') and response.get('status', 200) == 200, 'SQL panel query failed'
    return response.get('frames', [])


if args.synthetic:
    # Disposable SQL evidence: production migrations plus deterministic catalogue seed.
    # No owner database, existing project or application image is touched.
    subprocess.run(compose + ['up', '--detach', '--wait', 'postgres'], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
    files = sorted(list((root / 'backend/src/main/resources/db/migration').glob('*.sql')) +
                   list((root / 'backend/src/main/resources/db/dev-seed').glob('*.sql')), key=lambda p:p.name)
    result = subprocess.run(compose + ['exec', '-T', '--user', 'postgres', 'postgres', 'psql', '--username=postgres',
                            '--dbname=videogame_platform', '--set=ON_ERROR_STOP=1', '--quiet'],
                            input='SET ROLE videogame_app_migrator;\n' + '\n'.join(p.read_text() for p in files), text=True, capture_output=True, timeout=60)
    assert result.returncode == 0, 'Disposable SQL migrations/seed failed: ' + result.stderr
    db_secret = pathlib.Path(config['secrets']['grafana_database_password']['file']).read_text().strip()
    script = "\\set reader_password '" + db_secret.replace('\\', '\\\\').replace("'", "\\'") + "'\n"
    script += (root / 'deploy/private-dev/grafana/provision-reader.sql').read_text()
    result = subprocess.run(compose + ['exec', '-T', '--user', 'postgres', 'postgres', 'psql', '--username=postgres',
                            '--dbname=videogame_platform', '--set=ON_ERROR_STOP=1', '--quiet'],
                            input=script, text=True, capture_output=True, timeout=30)
    assert result.returncode == 0, 'Disposable reader provisioning failed (details withheld)'
    fixture_sql = """
INSERT INTO ratings.game_listing (game_id,slug,canonical_title,normalized_title,cover_kind)
SELECT game_id,slug,canonical_title,lower(canonical_title),'unavailable' FROM catalogue.game_snapshot;
INSERT INTO ratings.rating(user_id,game_id,value)
SELECT md5('synthetic-user-' || n)::uuid, g.game_id, 1 + (n % 10)
FROM generate_series(1,12) n CROSS JOIN
    (SELECT game_id FROM catalogue.game_snapshot ORDER BY game_id LIMIT 3) g;
INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,started_at,heartbeat_at,completed_at,run_status,outcome_code,report)
SELECT md5('synthetic-run-' || n)::uuid,'synthetic',current_date-30,current_date,
       now()-n*interval '1 day',now()-n*interval '1 day'+interval '30 seconds',
       now()-n*interval '1 day'+n*interval '60 seconds',
       CASE n WHEN 1 THEN 'succeeded' WHEN 2 THEN 'partial' ELSE 'failed' END,
       'SYNTHETIC_SMOKE',jsonb_build_object('counters',jsonb_build_object(
          'createdGames',n,'updatedGames',2,'unchangedGames',10,'deferredGames',1,'failedGames',n-1,
          'createdReleases',n*2,'updatedReleases',1,'unchangedReleases',20,'deletedReleases',0,
          'inspectedReleaseDates',30,'providerRequests',4,'providerRetries',1,'providerLatencyMillis',1000))
FROM generate_series(1,3) n;
"""
    result = subprocess.run(compose + ['exec', '-T', '--user', 'postgres', 'postgres', 'psql',
                           '--username=postgres', '--dbname=videogame_platform', '--set=ON_ERROR_STOP=1', '--quiet'],
                           input=fixture_sql, text=True, capture_output=True, timeout=30)
    assert result.returncode == 0, 'Disposable analytics fixture failed'

# Anonymous dashboard and datasource access must be denied, even on the private network.
eventually(lambda: request('http://grafana:3000/api/health')['database'] == 'ok', 'Grafana did not start')
request('http://grafana:3000/api/search', expected=401)
datasource = request('http://grafana:3000/api/datasources/uid/private-dev-prometheus', authenticated=True)
assert datasource['url'] == 'http://prometheus:9090' and datasource['readOnly'], 'Datasource provisioning mismatch'
request('http://grafana:3000/api/datasources/uid/private-dev-prometheus/health', authenticated=True)
eventually(lambda: bool(query('otelcol_receiver_accepted_metric_points_total{job="collector"}')), 'Collector receiver counter name/configuration mismatch')
reader = request('http://grafana:3000/api/datasources/uid/private-dev-postgres', authenticated=True)
assert reader['user'] == 'videogame_grafana' and reader['readOnly']
request('http://grafana:3000/api/datasources/uid/private-dev-postgres/health', authenticated=True)
if args.synthetic:
    request('http://telemetry:4318/v1/metrics', synthetic_metrics(1))
    eventually(lambda: bool(query('http_server_requests_milliseconds_count')), 'OTLP histogram missing')
    assert values('vgp-synchronization', 'Command outcomes · observed process') == [1], 'First run lost'
    assert values('vgp-synchronization', 'Provider mean duration · observed process') == [100]
    time.sleep(31)
    request('http://telemetry:4318/v1/metrics', synthetic_metrics(2))
    eventually(lambda: any(float(s['value'][1]) == 2 for s in query('http_server_requests_milliseconds_count')),
               'Second cumulative sample missing')
    assert values('vgp-runtime', 'Heap occupancy') == [0.5]
    assert values('vgp-runtime', 'Pool occupancy') == [0.1]
    assert values('vgp-runtime', 'Process CPU consumed')[0] > 0, 'CPU timer translation/rate missing'
    assert math.isclose(values('vgp-runtime', 'Mean connection acquisition')[0], 7, rel_tol=1e-9), 'Acquisition mean/units incorrect'
    assert math.isclose(values('vgp-runtime', 'Mean GC pause')[0], 12, rel_tol=1e-9), 'GC mean/units incorrect'
    errors = query(panel_query('vgp-application', 'Client rejections / server failures'), grafana=True)
    assert {s['metric']['status'] for s in errors} == {'401', '404'}
    raw_errors = query('sum by (method) (http_server_requests_milliseconds_count{job="application-otlp",status=~"4..|5.."} unless http_server_requests_milliseconds_count{job="application-otlp",uri="/api/v1/me/ratings/{gameId}",method="GET",status="404"})', grafana=True)
    assert {s['metric']['method']: float(s['value'][1]) for s in raw_errors} == {'GET':2, 'PUT':2, 'DELETE':2}
    absence = values('vgp-application', 'Expected unrated-game reads · range')
    assert absence and absence[0] > 0, 'Expected absence was hidden'
    p95 = query(panel_query('vgp-application', 'Slowest routes · p95'), grafana=True)
    rating_latency = {s['metric']['method']: float(s['value'][1]) for s in p95 if s['metric']['uri'] == '/api/v1/me/ratings/{gameId}'}
    assert 90 <= rating_latency['GET'] <= 100 and 900 <= rating_latency['PUT'] <= 1000
    print('Sparse first totals, resource ratios, histogram units/methods and expected-absence classification passed')
elif not args.local:
    eventually(lambda: bool(query('jvm_memory_used_bytes')), 'Real application JVM metrics missing')

seen = set()
for path in sorted((root / 'deploy/private-dev/grafana/dashboards').glob('*.json')):
    expected = json.loads(path.read_text())
    actual = request('http://grafana:3000/api/dashboards/uid/' + expected['uid'], authenticated=True)
    assert actual['meta']['provisioned'], 'Dashboard is not provisioned'
    assert actual['dashboard']['panels'] == expected['panels'], 'Provisioned panels differ from repository'
    nonempty = 0
    for panel in expected['panels']:
        for target in panel.get('targets', []):
            if target['datasource']['uid'] == 'private-dev-postgres':
                sql_query(target)
                continue
            expression = expand(target['expr'])
            data = query(expression, grafana=True)
            finite = any('value' in s and math.isfinite(float(s['value'][1])) for s in data)
            if finite:
                nonempty += 1
    if not args.local:
        assert nonempty, f'No real query data for {expected["uid"]}; generate representative activity first'
    seen.add(expected['uid'])
    print(f'{expected["uid"]}: provisioning matches; queries succeed; {nonempty} queries have finite data')
assert seen == expected_uids
assert {item["uid"] for item in request("http://grafana:3000/api/search?type=dash-db", authenticated=True)} == expected_uids, "Obsolete or duplicate dashboards remain"
series = query('{job="application-otlp"}')
allowed = {'__name__', 'job', 'instance', 'uri', 'method', 'status', 'outcome', 'le', 'area', 'id',
           'exception', 'error', 'action', 'cause', 'state', 'pool', 'name', 'view', 'eligibility', 'aggregate', 'kind', 'operation', 'reason', 'code', 'freshness', 'month', 'lead_image'}
for sample in series:
    assert set(sample['metric']) <= allowed, 'Unexpected stored metric dimension'
    assert sample['metric'].get('instance') == 'telemetry:9464', 'Unbounded resource instance stored'
    uri = sample['metric'].get('uri', '')
    assert '?' not in uri and len(uri) <= 160, 'Raw request input in route label'
if args.synthetic:
    p95 = query('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_milliseconds_bucket{uri="/api/v1/releases"}[4m])))')
    assert len(p95) == 1 and 90 <= float(p95[0]['value'][1]) <= 100, 'Histogram units/buckets are incorrect'
    at = str(time.time())
    before = query('catalogue_synchronization_run_total', at=at)
    # Only disposable project services are recreated; production mode is strictly read-only.
    subprocess.run(compose + ['up', '--detach', '--no-deps', '--force-recreate', 'prometheus', 'grafana'], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
    eventually(lambda: query('catalogue_synchronization_run_total', grafana=True, at=at) == before,
               'Retained samples did not survive recreation')
    for uid in seen:
        assert request('http://grafana:3000/api/dashboards/uid/' + uid, authenticated=True)['meta']['provisioned']
    print('Prometheus retained samples and Grafana provisioning survive container recreation')
logs = subprocess.check_output(compose + ['logs', '--no-color', 'grafana'], text=True)
assert secret not in logs, 'Grafana logs exposed the administrative secret'
if args.local and not query('jvm_memory_used_bytes'):
    print('No application JVM data yet. Enable OTLP metrics on the backend, generate traffic and wait for export/scrape intervals.')
print('Metrics configuration, authentication, stored labels and dashboard queries passed')
