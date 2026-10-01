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
parser.add_argument('--static', action='store_true')
parser.add_argument('--synthetic', action='store_true')
parser.add_argument('--local', action='store_true')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
dashboards = sorted((root / 'deploy/private-dev/grafana/dashboards').glob('*.json'))
assert {p.stem for p in dashboards} == {'vgp-runtime', 'vgp-synchronization', 'vgp-journey'}
for path in dashboards:
    dashboard = json.loads(path.read_text())
    assert dashboard['uid'] == path.stem and dashboard['editable'] is False
    assert dashboard['refresh'] == '1m' and not dashboard['templating']['list']
    for panel in dashboard['panels']:
        for target in panel.get('targets', []):
            assert target['datasource']['uid'] == 'private-dev-prometheus'
            expression = target['expr']
            assert 'job="application-otlp"' in expression
            assert not re.search(r'(?i)(user_id|game_id|release_id|session_id|correlation|trace_id|token|password|search_term)', expression)
            assert set(re.findall(r'\$[A-Za-z_]+', expression)) <= {'$__rate_interval', '$__range'}
if args.static:
    print('Dashboard JSON, datasource references, bounded queries and refresh passed')
    raise SystemExit(0)
if not args.config or not args.env_file:
    parser.error('--config and --env-file are required for runtime checks')
config = json.loads(pathlib.Path(args.config).read_text())
if args.local and args.synthetic:
    parser.error('Local verification never injects synthetic samples or recreates services')
files = ('compose.yaml', 'compose.observability.yaml') if args.local else (
    'deploy/private-dev/compose.yaml', 'deploy/private-dev/compose.telemetry-smoke.yaml')
compose = ['docker', 'compose', '--env-file', args.env_file, '--project-name', config['name']]
for file in files:
    compose += ['--file', str(root / file)]
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
    counter('catalogue.synchronization.provider.request', {'operation': 'window', 'outcome': 'success'})
    histogram('catalogue.synchronization.provider.request.duration', {'operation': 'window'}, 'ms')
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
    elif service_name != 'grafana' or args.synthetic:
        assert not bindings, f'{service_name} unexpectedly publishes a port'
    else:
        assert bindings == {'3000/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '3000'}]}, 'Grafana access drift'
    assert host['ReadonlyRootfs'] and host['CapDrop'] == ['ALL'], f'{service_name} hardening drift'
print('Running metrics services match configured resource, log and access bounds')


def panel_query(uid, panel_id, index=0):
    dashboard = json.loads((root / 'deploy/private-dev/grafana/dashboards' / (uid + '.json')).read_text())
    panel = next(p for p in dashboard['panels'] if p['id'] == panel_id)
    # Datasource min interval is 60s, so Grafana's minimum rate interval is 4m.
    return panel['targets'][index]['expr'].replace('$__rate_interval', '4m').replace('$__range', '7d')


def values(uid, panel_id):
    return [float(s['value'][1]) for s in query(panel_query(uid, panel_id), grafana=True)]

# Anonymous dashboard and datasource access must be denied, even on the private network.
eventually(lambda: request('http://grafana:3000/api/health')['database'] == 'ok', 'Grafana did not start')
request('http://grafana:3000/api/search', expected=401)
datasource = request('http://grafana:3000/api/datasources/uid/private-dev-prometheus', authenticated=True)
assert datasource['url'] == 'http://prometheus:9090' and datasource['readOnly'], 'Datasource provisioning mismatch'
request('http://grafana:3000/api/datasources/uid/private-dev-prometheus/health', authenticated=True)
if args.synthetic:
    request('http://telemetry:4318/v1/metrics', synthetic_metrics(1))
    eventually(lambda: bool(query('http_server_requests_milliseconds_count')), 'OTLP histogram missing')
    # Sparse meters can first appear after the event: exact totals/means must work
    # before there is a counter baseline for rate/increase.
    assert values('vgp-synchronization', 1) == [1], 'First completed run was lost'
    assert values('vgp-synchronization', 2) == [100], 'First duration is not the recorded milliseconds'
    assert values('vgp-synchronization', 3) == [100], 'Record sum was confused with observation count'
    assert sorted(values('vgp-journey', 5)) == [1, 1], 'First detail categories were lost or extrapolated'
    # Let Prometheus observe two different cumulative samples without changing production intervals.
    time.sleep(31)
    request('http://telemetry:4318/v1/metrics', synthetic_metrics(2))
    eventually(lambda: any(float(s['value'][1]) == 2 for s in query('http_server_requests_milliseconds_count')),
               'Second cumulative sample missing')
    assert values('vgp-synchronization', 1) == [2], 'Discrete runs must remain exact'
    assert values('vgp-synchronization', 2) == [100], 'Cumulative duration mean is incorrect'
    assert values('vgp-runtime', 4) == [0.5], 'Heap occupancy does not use its maximum'
    assert values('vgp-runtime', 7) == [0.1], 'Pool utilization does not use its maximum'
    errors = query(panel_query('vgp-journey', 2), grafana=True)
    by_method = {s['metric']['method']: float(s['value'][1]) for s in errors}
    assert set(by_method) == {'GET', 'PUT', 'DELETE'}, 'Actionable methods were hidden'
    absence = query(panel_query('vgp-journey', 7), grafana=True)
    assert len(absence) == 1 and by_method['GET'] == float(absence[0]['value'][1]) == 2, \
        f'GET absence should be separate from authentication failure: {by_method}, {absence}'
    assert all(v == 2 for v in by_method.values()), 'Discrete errors must remain exact and write failures visible'
    p95 = query(panel_query('vgp-runtime', 3), grafana=True)
    rating_latency = {s['metric']['method']: float(s['value'][1]) for s in p95
                      if s['metric']['uri'] == '/api/v1/me/ratings/{gameId}'}
    assert 90 <= rating_latency['GET'] <= 100 and 900 <= rating_latency['PUT'] <= 1000, \
        'HTTP methods were mixed in the percentile'
    print('Sparse first events, exact totals/means, pressure ratios and HTTP method/error classification passed')
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
            expression = target['expr'].replace('$__rate_interval', '4m').replace('$__range', '7d')
            data = query(expression, grafana=True)
            finite = any('value' in s and math.isfinite(float(s['value'][1])) for s in data)
            if args.synthetic:
                assert finite, f'Synthetic fixture has no data for panel: {panel["title"]}'
            if finite:
                nonempty += 1
    if not args.local:
        assert nonempty, f'No real query data for {expected["uid"]}; generate representative activity first'
    seen.add(expected['uid'])
    print(f'{expected["uid"]}: provisioning matches; queries succeed; {nonempty} queries have finite data')
assert len(seen) == 3
series = query('{job="application-otlp"}')
allowed = {'__name__', 'job', 'instance', 'uri', 'method', 'status', 'outcome', 'le', 'area', 'id',
           'exception', 'error', 'action', 'cause', 'state', 'pool', 'name', 'view', 'eligibility', 'aggregate', 'kind', 'operation', 'reason'}
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
