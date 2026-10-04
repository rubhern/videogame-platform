#!/usr/bin/env python3
"""Static guards and an opt-in, disposable Docker → Alloy → Loki → Grafana proof."""
import argparse
import base64
import datetime
import json
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.parse

ROOT = Path(__file__).resolve().parent.parent
DEPLOY = ROOT / 'deploy/private-dev'
CURL = 'curlimages/curl:8.22.0@sha256:58adaa4e8dca9c988bae2aba4ab3434a0bb2da16bbe3f92dec39ec7785166777'


def run(command, **kwargs):
    result = subprocess.run(command, text=True, capture_output=True, timeout=90, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout


def static():
    alloy = (DEPLOY / 'alloy/config.alloy').read_text()
    loki = (DEPLOY / 'loki/config.yaml').read_text()
    compose = (DEPLOY / 'compose.logs.yaml').read_text()
    assert not re.search(r'discovery\.docker|loki\.source\.docker|labelmap|stage\.labels', alloy)
    assert 'regex  = "service_name|environment"' in alloy, 'Only two fixed indexed labels permitted'
    assert 'regex         = "application"' in alloy, 'Only the fixed application tag is accepted'
    assert 'drop_malformed = true' in alloy, 'Docker fragments must not masquerade as complete ECS events'
    assert 'LOG_DEPLOYMENT_ENVIRONMENT: private-dev' in compose
    assert 'environment = sys.env("LOG_DEPLOYMENT_ENVIRONMENT")' in alloy
    assert 'label_structured_data  = false' in alloy
    assert 'max_streams = 1' in alloy and 'max_backoff_retries = 3' in alloy
    assert 'max_message_length     = 65536' in alloy
    assert 'longer_than         = "15KB"' in alloy, 'Drop oversized bodies without truncating ECS'
    for setting in ('retention_enabled: true', 'delete_request_store: filesystem',
                    'retention_period: 24h', 'period: 24h', 'schema: v13',
                    'ingestion_rate_mb: 0.01', 'max_global_streams_per_user: 4',
                    'max_label_names_per_series: 2', 'max_entries_limit_per_query: 1000',
                    'max_query_length: 24h', 'query_timeout: 10s',
                    'max_concurrent: 2', 'reporting_enabled: false'):
        assert setting in loki, setting
    assert not re.search(r'docker\.sock|privileged:|network_mode: host', compose)
    assert compose.count('@sha256:') == 2
    print('Log label/collection, retention, ingestion/query and privilege guards passed.')


def smoke():
    # Never accepts a live env file, project name or target volume. Cleanup is scoped
    # to this generated project and it contains no product/identity/database service.
    with tempfile.TemporaryDirectory(prefix='vgp-logs-') as directory:
        temp = Path(directory)
        project = 'vgp-logs-smoke-' + secrets.token_hex(4)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        password = secrets.token_urlsafe(32)
        (temp / 'grafana-admin-password').write_text(password)
        (temp / 'grafana-database-password').write_text(password)
        timestamp = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
        events = []
        for level in ('INFO', 'WARN', 'ERROR'):
            events.append({'@timestamp': timestamp, 'log.level': level,
                           'service.name': 'videogame-platform', 'message': 'bounded logging proof ' + level,
                           'event': 'http.request.completed', 'http.status_code': 500 if level == 'ERROR' else 200,
                           'correlationId': 'issue159-synthetic', 'traceId': '1' * 32,
                           'requestId': 'synthetic-request', 'gameId': 'synthetic-game', 'userId': 'synthetic-user'})
        # An overlong ECS line in the same burst must be dropped before it poisons
        # a Loki batch containing the three valid events.
        fixture_events = events + [{'@timestamp': timestamp, 'message': 'oversized-synthetic ' + 'x' * 17000}]
        (temp / 'events.jsonl').write_text(''.join(json.dumps(event) + '\n' for event in fixture_events))
        (temp / 'runtime.env').write_text(f'PRIVATE_DEV_SYSLOG_PORT={port}\n')
        # Use the shared Grafana definition through extends, with private logs-only
        # provisioning. Keep shared local/metrics datasource references unchanged.
        definition = {
            'services': {
                'alloy': {'extends': {'file': str(DEPLOY / 'compose.logs.yaml'), 'service': 'alloy'}},
                'loki': {'extends': {'file': str(DEPLOY / 'compose.logs.yaml'), 'service': 'loki'}},
                'grafana': {
                    'extends': {'file': str(DEPLOY / 'compose.observability.yaml'), 'service': 'grafana'},
                    'networks': ['logs'],
                    # Reset the inherited Grafana host port after JSON generation below.
                    'volumes': [str(DEPLOY / 'grafana/logs-provisioning/loki.yaml') +
                                ':/etc/grafana/provisioning/datasources/loki.yaml:ro']},
                'log-generator': {
                    'image': CURL, 'entrypoint': ['/bin/sh', '-ec'],
                    'command': ['cat /fixtures/events.jsonl; sleep 600'],
                    'volumes': [str(temp / 'events.jsonl') + ':/fixtures/events.jsonl:ro'],
                    'network_mode': 'none', 'read_only': True, 'cap_drop': ['ALL'],
                    'security_opt': ['no-new-privileges:true'],
                    'mem_limit': '32m', 'cpus': 0.1, 'pids_limit': 32,
                    'logging': {'driver': 'syslog', 'options': {
                        'syslog-address': f'udp://127.0.0.1:{port}', 'syslog-format': 'rfc5424micro',
                        'tag': 'application', 'mode': 'non-blocking', 'max-buffer-size': '1m',
                        'cache-max-size': '10m', 'cache-max-file': '3'}}}},
            'networks': {'logs': {'internal': True}, 'edge': {}, 'log-ingress': {}, 'telemetry': {'internal': True}},
            'volumes': {'loki-data': {}, 'grafana-data': {}},
            'secrets': {'grafana_admin_password': {'file': str(temp / 'grafana-admin-password')}, 'grafana_database_password': {'file': str(temp / 'grafana-database-password')}}}
        source = temp / 'source.json'
        source.write_text(json.dumps(definition))
        prefix = ['docker', 'compose', '--env-file', str(temp / 'runtime.env'), '--project-name', project]
        rendered = json.loads(run(prefix + ['--file', str(source), 'config', '--format', 'json']))
        rendered['services']['grafana'].pop('ports', None)
        source.write_text(json.dumps(rendered))
        compose = prefix + ['--file', str(source)]

        def request(url, authenticated=False, expected=200, method=None, data=None):
            config = ['silent', 'show-error', 'max-time = 15', 'url = ' + json.dumps(url),
                      'write-out = "\\n%{http_code}"']
            if data is not None:
                config += ['header = "Content-Type: application/json"', 'data = ' + json.dumps(json.dumps(data))]
            if method:
                config.append('request = ' + json.dumps(method))
            if authenticated:
                token = base64.b64encode(('owner:' + password).encode()).decode()
                config.append('header = ' + json.dumps('Authorization: Basic ' + token))
            result = run(['docker', 'run', '--rm', '-i', '--network', project + '_logs',
                          '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                          '--memory', '32m', '--cpus', '0.1', '--pids-limit', '32',
                          '--log-driver', 'none', CURL, '--config', '-'], input='\n'.join(config))
            body, status = result.rsplit('\n', 1)
            assert int(status) == expected, f'{urllib.parse.urlparse(url).path}: HTTP {status}: {body[:500]}'
            return json.loads(body) if body.startswith(('{', '[')) else body

        def eventually(check, description):
            deadline = time.monotonic() + 65
            last_failure = "no matching result"
            while True:
                try:
                    if check():
                        return
                except (AssertionError, IndexError, RuntimeError, subprocess.SubprocessError) as error:
                    last_failure = str(error)
                if time.monotonic() >= deadline:
                    raise AssertionError(description + ": " + last_failure)
                time.sleep(1)

        proxy = 'http://grafana:3000/api/datasources/proxy/uid/private-dev-loki'
        def query(expression):
            params = urllib.parse.urlencode({'query': expression, 'limit': 100})
            return request(proxy + '/loki/api/v1/query_range?' + params, authenticated=True)['data']['result']

        def entries(expression):
            return [value for stream in query(expression) for value in stream['values']]

        try:
            run(compose + ['up', '--detach', 'loki', 'alloy', 'grafana'])
            eventually(lambda: request('http://loki:3100/ready') == 'ready\n', 'Loki not ready')
            eventually(lambda: request('http://grafana:3000/api/health')['database'] == 'ok', 'Grafana not ready')
            # Native binary checks exercise exactly the pinned configurations.
            run(compose + ['exec', '-T', 'alloy', '/bin/alloy', 'validate', '/etc/alloy/config.alloy'])
            run(compose + ['exec', '-T', 'loki', '/usr/bin/loki', '-config.file=/etc/loki/config.yaml', '-verify-config=true'])
            run(compose + ['up', '--detach', 'log-generator'])
            expression = '{service_name="application",environment="private-dev"}'
            eventually(lambda: len(entries(expression)) == 3, 'Docker → Alloy logs missing')
            # Query results also carry detected_level structured metadata; the series
            # endpoint is the source of truth for indexed labels.
            series = request(proxy + '/loki/api/v1/series?' + urllib.parse.urlencode({'match[]': expression}), authenticated=True)['data']
            assert series == [{'environment': 'private-dev', 'service_name': 'application'}]
            actual = [json.loads(value[1]) for value in entries(expression)]
            assert sorted(actual, key=lambda x: x['log.level']) == sorted(events, key=lambda x: x['log.level']), 'ECS payload changed'
            for field, value in [('correlationId', 'issue159-synthetic'), ('traceId', '1' * 32),
                                 ('requestId', 'synthetic-request'), ('gameId', 'synthetic-game'), ('userId', 'synthetic-user')]:
                assert len(entries(expression + f' | json | {field}="{value}"')) == 3
            assert not entries(expression + ' |= "oversized-synthetic"')
            error_query = expression + ' | json level="[\\"log.level\\"]" | level="ERROR"'
            assert len(entries(error_query)) == 1
            assert len(entries(expression + ' |= "bounded logging proof WARN"')) == 1
            assert len(entries(expression + ' | json | event="http.request.completed"')) == 3
            request(proxy + '/loki/api/v1/labels', expected=401)
            request('http://grafana:3000/api/datasources/uid/private-dev-loki', expected=401)
            datasource = request('http://grafana:3000/api/datasources/uid/private-dev-loki', authenticated=True)
            assert datasource['readOnly'] and datasource['url'] == 'http://loki:3100'
            labels = request(proxy + '/loki/api/v1/labels', authenticated=True)['data']
            assert set(labels) == {'environment', 'service_name'}
            now_ns = str(time.time_ns())
            old_ns = str(time.time_ns() - 27 * 3600 * 1_000_000_000)
            for stamp, line, reason in [(old_ns, 'expired-synthetic', 'timestamp too old'),
                                       (now_ns, 'x' * 17000, 'max entry size')]:
                rejection = request('http://loki:3100/loki/api/v1/push', expected=400,
                                    data={'streams': [{'stream': {'environment': 'private-dev', 'service_name': 'application'},
                                                       'values': [[stamp, line]]}]})
                assert reason in rejection, rejection
            print('Loki rejects expired and oversized input using the pinned active configuration.')
            for service, memory, cpus in [('alloy', 192, 0.25), ('loki', 384, 0.5)]:
                container_id = run(compose + ['ps', '--quiet', service]).strip()
                actual = json.loads(run(['docker', 'inspect', container_id]))[0]
                host = actual['HostConfig']
                assert host['Memory'] == memory * 1024 * 1024
                assert host['NanoCpus'] == int(cpus * 1_000_000_000) and host['PidsLimit'] == 128
                assert host['ReadonlyRootfs'] and host['CapDrop'] == ['ALL']
                assert host['SecurityOpt'] == ['no-new-privileges:true']
                assert all('docker.sock' not in mount['Source'] for mount in actual['Mounts'])
                if service == 'loki':
                    assert not host['PortBindings']
                else:
                    assert host['PortBindings'] == {'1514/udp': [{'HostIp': '127.0.0.1', 'HostPort': str(port)}]}
            cached = run(compose + ['logs', '--no-log-prefix', 'log-generator'])
            assert 'issue159-synthetic' in cached, 'Docker local cache unavailable'
            # An unapproved syslog tag must not create an indexed service/stream.
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
                sender.sendto(f'<14>1 {timestamp} host forbidden pid id - unexpected-service'.encode(), ('127.0.0.1', port))
            time.sleep(2)
            assert not query('{environment="private-dev"} |= "unexpected-service"')
            # Flush to filesystem before recreation, so this proves retained storage,
            # rather than merely finding samples in a still-running ingester.
            request('http://loki:3100/flush', method='POST', expected=204)
            time.sleep(2)
            run(compose + ['up', '--detach', '--no-deps', '--force-recreate', 'loki', 'grafana'])
            eventually(lambda: len(entries(expression)) == 3, 'History lost after recreation')
            print('Docker syslog/ECS, searchable IDs/severity/event/message, exact labels, Grafana auth and Loki/Grafana recreation passed.')
            # Creation and stdout must still work with the collector absent; lost
            # events are not replayed. A fresh generator exercises Docker init too.
            run(compose + ['stop', 'alloy'])
            run(compose + ['up', '--detach', '--force-recreate', 'log-generator'])
            container = run(compose + ['ps', '--quiet', 'log-generator']).strip()
            assert json.loads(run(['docker', 'inspect', container]))[0]['State']['Running']
            run(compose + ['start', 'alloy'])
            time.sleep(2)
            run(compose + ['up', '--detach', '--force-recreate', 'log-generator'])
            eventually(lambda: len(entries(expression)) >= 6, 'Collection did not resume')
            print('Generator creation/output during Alloy outage and collection resumption passed; outage delivery is intentionally lossy.')
            stats = run(['docker', 'stats', '--no-stream', '--format', '{{.Name}} {{.MemUsage}} {{.CPUPerc}} {{.PIDs}}',
                         *run(compose + ['ps', '--quiet']).split()])
            print('Disposable resource snapshot:\n' + stats.strip())
        except Exception:
            print(run(compose + ['logs', '--no-color', '--tail', '10', 'alloy', 'loki']))
            alloy_id = run(compose + ['ps', '--quiet', 'alloy']).strip()
            if alloy_id:
                try:
                    metrics = run(['docker', 'run', '--rm', '--network', 'container:' + alloy_id,
                                   '--log-driver', 'none', CURL, '-s', '--max-time', '5',
                                   'http://127.0.0.1:12345/metrics'])
                    print('\n'.join(line for line in metrics.splitlines() if not line.startswith('#') and
                          ('syslog_entries_total' in line or 'syslog_parsing_errors_total' in line or
                           'loki_write_dropped_entries_total' in line or 'loki_write_sent_entries_total' in line)))
                except (RuntimeError, subprocess.SubprocessError):
                    print('Alloy metrics unavailable during failure diagnostics.')
            raise
        finally:
            # No product/database services exist in this generated definition.
            run(compose + ['down', '--volumes', '--remove-orphans'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument('--static', action='store_true')
    modes.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    static()
    if args.smoke:
        smoke()


if __name__ == '__main__':
    main()
