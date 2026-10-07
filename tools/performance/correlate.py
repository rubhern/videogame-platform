#!/usr/bin/env python3
"""Read existing local Grafana datasources for one baseline's UTC window."""
import base64
from collections import Counter
import datetime
import json
from pathlib import Path
import re
import sys
import urllib.parse
import urllib.request


def main():
    if len(sys.argv) != 3:
        sys.exit('Usage: python3 tools/performance/correlate.py RUN_DIRECTORY GRAFANA_PASSWORD_FILE')
    directory = Path(sys.argv[1])
    manifest = json.loads((directory / 'manifest.json').read_text())
    start = datetime.datetime.fromisoformat(manifest['startedAt']).timestamp()
    end = datetime.datetime.fromisoformat(manifest['finishedAt']).timestamp()
    if datetime.datetime.now(datetime.timezone.utc).timestamp() < end + 90:
        sys.exit('Allow 90 seconds after the run for existing OTLP export and Prometheus scrape.')
    secret = Path(sys.argv[2]).read_text().strip()
    authorization = 'Basic ' + base64.b64encode(('owner:' + secret).encode()).decode()

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def query(uid, path, parameters):
        url = 'http://127.0.0.1:3000/api/datasources/proxy/uid/' + uid + path
        request = urllib.request.Request(url + '?' + urllib.parse.urlencode(parameters),
                                         headers={'Authorization': authorization})
        with opener.open(request, timeout=20) as response:
            result = json.load(response)
        if result.get('status') != 'success':
            raise RuntimeError('Datasource query did not succeed')
        return result['data']['result']

    expressions = {
        'http_count': 'sum by (uri,status) (http_server_requests_milliseconds_count{job="application-otlp"})',
        'http_time_ms': 'sum by (uri) (http_server_requests_milliseconds_sum{job="application-otlp"})',
        'heap_used_bytes': 'sum(jvm_memory_used_bytes{job="application-otlp",area="heap"})',
        'heap_max_bytes': 'sum(jvm_memory_max_bytes{job="application-otlp",area="heap"} > 0)',
        'gc_pause_ms': 'sum(jvm_gc_pause_milliseconds_sum{job="application-otlp"})',
        'gc_pause_count': 'sum(jvm_gc_pause_milliseconds_count{job="application-otlp"})',
        'threads': 'jvm_threads_live{job="application-otlp"}',
        'process_cpu': 'process_cpu_usage{job="application-otlp"}',
        'system_cpu': 'system_cpu_usage{job="application-otlp"}',
        'pool_active': 'hikaricp_connections_active{job="application-otlp"}',
        'pool_pending': 'hikaricp_connections_pending{job="application-otlp"}',
        'pool_max': 'hikaricp_connections_max{job="application-otlp"}',
        'pool_timeouts': 'hikaricp_connections_timeout_total{job="application-otlp"}',
        'pool_acquire_ms': 'hikaricp_connections_acquire_milliseconds_sum{job="application-otlp"}',
    }
    evidence = {'runId': manifest['runId'], 'start': start - 120, 'end': end + 90,
                'stepSeconds': 30, 'prometheus': {}}
    for name, expression in expressions.items():
        try:
            values = query('private-dev-prometheus', '/api/v1/query_range',
                           {'query': expression, 'start': start - 120, 'end': end + 90, 'step': 30})
            evidence['prometheus'][name] = {'query': expression, 'series': values}
        except Exception as error:
            evidence['prometheus'][name] = {'query': expression, 'unavailable': type(error).__name__}
    run_id = manifest['runId']
    if not re.fullmatch(r'perf-current-[A-Za-z0-9_-]+', run_id):
        raise RuntimeError('Invalid local baseline run identifier')
    expression = '{environment="local",service_name="application"} |= ' + json.dumps(run_id)
    try:
        streams = query('private-dev-loki', '/loki/api/v1/query_range',
                        {'query': expression, 'start': int(start * 1e9),
                         'end': int((end + 2) * 1e9), 'limit': 200, 'direction': 'forward'})
        entries = []
        for stream in streams:
            for timestamp, line in stream['values']:
                event = json.loads(line)
                correlation = event.get('correlationId', '')
                if re.fullmatch(re.escape(run_id) + r'-\d+', correlation):
                    entries.append({'at': timestamp, 'iteration': int(correlation.rsplit('-', 1)[1]),
                                    'route': event['http']['route'], 'status': event['http']['status_code'],
                                    'durationMs': event.get('duration_ms')})
        evidence['logs'] = {'query': expression, 'completions': len(entries),
                            'byRoute': dict(Counter(entry['route'] for entry in entries)),
                            'byStatus': dict(Counter(str(entry['status']) for entry in entries)),
                            'entries': entries}
    except Exception as error:
        evidence['logs'] = {'query': expression, 'unavailable': type(error).__name__}
    (directory / 'server-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('Saved server-evidence.json; empty series or unavailable fields are gaps, not zero.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        sys.exit('Correlation failed: ' + type(error).__name__)
