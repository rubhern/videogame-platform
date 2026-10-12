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


def correlate(directory, password_file):
    manifest = json.loads((directory / 'manifest.json').read_text())
    start = datetime.datetime.fromisoformat(manifest['startedAt']).timestamp()
    end = datetime.datetime.fromisoformat(manifest['finishedAt']).timestamp()
    if datetime.datetime.now(datetime.timezone.utc).timestamp() < end + 90:
        sys.exit('Allow 90 seconds after the run for existing OTLP export and Prometheus scrape.')
    secret = password_file.read_text().strip()
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
        'pool_acquire_count': 'hikaricp_connections_acquire_milliseconds_count{job="application-otlp"}',
        'pool_usage_ms': 'hikaricp_connections_usage_milliseconds_sum{job="application-otlp"}',
        'pool_usage_count': 'hikaricp_connections_usage_milliseconds_count{job="application-otlp"}',
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
    if not re.fullmatch(r'perf-(?:current|growth)-[A-Za-z0-9_-]+', run_id):
        raise RuntimeError('Invalid local baseline run identifier')
    expression = '{environment="local",service_name="application"} |= ' + json.dumps(run_id) + ' |= "HTTP request completed"'
    try:
        # Ten-second completion slices stay below the configured 1,000-entry limit at 50 req/s.
        # Deduplicate only at adjacent slice boundaries; retain missing-log evidence.
        entries = []
        seen = set()
        cursor = start
        while cursor < end + 2:
            until = min(cursor + 10, end + 2)
            streams = query('private-dev-loki', '/loki/api/v1/query_range',
                            {'query': expression, 'start': int(cursor * 1e9),
                             'end': int(until * 1e9), 'limit': 1000, 'direction': 'forward'})
            returned = sum(len(stream['values']) for stream in streams)
            if returned >= 1000:
                raise RuntimeError('Log slice truncated; narrow the window')
            for stream in streams:
                for timestamp, line in stream['values']:
                    event = json.loads(line)
                    correlation = event.get('correlationId', '')
                    if re.fullmatch(re.escape(run_id) + r'-\d+', correlation) and correlation not in seen:
                        seen.add(correlation)
                        entries.append({'at': timestamp, 'iteration': int(correlation.rsplit('-', 1)[1]),
                                        'route': event['http']['route'], 'status': event['http']['status_code'],
                                        'durationMs': event.get('duration_ms'), 'errorCode': event.get('error', {}).get('code')})
            cursor = until
        evidence['logs'] = {'query': expression, 'completions': len(entries),
                            'byRoute': dict(Counter(entry['route'] for entry in entries)),
                            'byStatus': dict(Counter(str(entry['status']) for entry in entries)),
                            'entries': entries}
    except Exception as error:
        evidence['logs'] = {'query': expression, 'unavailable': type(error).__name__}
    (directory / 'server-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('Saved server-evidence.json; empty series or unavailable fields are gaps, not zero.')


def summarize_growth(directory):
    """Condense existing evidence; never infer missing signals as zero."""
    experiment = json.loads((directory / 'experiment.json').read_text())
    report = {'experiment': experiment, 'levels': []}
    def measurement(snapshot, meter, statistic='VALUE'):
        values = snapshot['metrics'].get(meter, {}).get('measurements', [])
        return next((value['value'] for value in values if value['statistic'] == statistic), None)
    def span(snapshots, meter):
        values = [measurement(snapshot, meter) for snapshot in snapshots]
        values = [value for value in values if value is not None]
        return {'min': min(values), 'max': max(values)} if values else {'unavailable': True}
    def delta(before, after, meter, statistic):
        a, b = measurement(before, meter, statistic), measurement(after, meter, statistic)
        return b-a if a is not None and b is not None else None
    def stat(text):
        return {key: int(value) for key, value in (line.split() for line in text.splitlines())}
    for level_name in experiment['levelDirectories']:
        level = directory / level_name
        manifest = json.loads((level / 'manifest.json').read_text())
        summary = json.loads((level / 'summary.json').read_text())['metrics']
        before, after = [json.loads((level / (name+'.json')).read_text()) for name in ['before', 'after']]
        cooldown = json.loads((level / 'cooldown.json').read_text()) if (level / 'cooldown.json').exists() else None
        samples = [json.loads(line) for line in (level / 'observations.jsonl').open()]
        snapshots = [before, *samples, after]
        statuses, names, errors = Counter(), Counter(), 0
        with (level / 'metrics.json').open() as raw:
            for line in raw:
                point = json.loads(line)
                if point['type'] != 'Point':
                    continue
                if point['metric'] == 'http_reqs':
                    statuses[point['data']['tags'].get('status', 'unavailable')] += point['data']['value']
                    names[point['data']['tags']['name']] += point['data']['value']
                if point['metric'] == 'http_req_failed':
                    errors += point['data']['value']
        rate = manifest['workload']['rate']
        native_count = summary['http_reqs']['values']['count']
        if native_count != sum(statuses.values()) or native_count != sum(names.values()):
            raise RuntimeError('Raw HTTP counts disagree with summary')
        item = {'rateInput': rate, 'expectedRequestBudget': rate*300, 'manifest': manifest,
                'nativeHttpRequests': summary['http_reqs']['values'],
                'completedRequestsPerConfiguredSecond': native_count/300,
                'latencyMilliseconds': summary['http_req_duration']['values'],
                'errorCount': errors, 'errorRate': summary['http_req_failed']['values']['rate'],
                'timeouts': summary.get('response_timeouts', {}).get('values'),
                'droppedIterations': summary.get('dropped_iterations', {}).get('values', {'count': 0}),
                'checks': summary['checks']['values'], 'byStatus': dict(statuses), 'byCaseCount': dict(names),
                'byCaseLatencyMilliseconds': {request['name']: summary.get('http_req_duration{name:'+request['name']+'}', {}).get('values')
                                              for request in experiment['requests']},
                'observations': len(samples),
                'jvm': {'heapBytes': span(snapshots, 'jvm.memory.used'), 'heapMaxBytes': span(snapshots, 'jvm.memory.max'),
                        'threads': span(snapshots, 'jvm.threads.live'), 'processCpu': span(snapshots, 'process.cpu.usage'),
                        'gcCountDelta': delta(before, after, 'jvm.gc.pause', 'COUNT'),
                        'gcPauseMsDelta': delta(before, after, 'jvm.gc.pause', 'TOTAL_TIME')},
                'hikari': {'active': span(snapshots, 'hikaricp.connections.active'),
                           'pending': span(snapshots, 'hikaricp.connections.pending'),
                           'timeoutsDelta': delta(before, after, 'hikaricp.connections.timeout', 'COUNT'),
                           'acquireCountDelta': delta(before, after, 'hikaricp.connections.acquire', 'COUNT'),
                           'acquireTimeMsDelta': delta(before, after, 'hikaricp.connections.acquire', 'TOTAL_TIME')},
                'httpServer': {'countDelta': delta(before, after, 'http.server.requests', 'COUNT'),
                               'totalTimeMsDelta': delta(before, after, 'http.server.requests', 'TOTAL_TIME')},
                'database': {}, 'resources': {}, 'cooldown': cooldown}
        if 'unavailable' not in before['database'] and 'unavailable' not in after['database']:
            item['database'] = {'delta': {key: after['database'][key]-before['database'][key]
                                          for key in ['xact_commit','xact_rollback','blks_read','blks_hit','temp_files','temp_bytes','deadlocks']},
                                'connections': {'min': min(x['database']['numbackends'] for x in snapshots if 'numbackends' in x['database']),
                                                'max': max(x['database']['numbackends'] for x in snapshots if 'numbackends' in x['database'])},
                                'sampledUngrantedLocksMax': max(x['database'].get('ungranted_locks',0) for x in snapshots),
                                'activity': [{'at': x['at'], 'groups': x['database'].get('activity')} for x in samples]}
        else:
            item['database'] = {'unavailable': True}
        for label in ['application', 'postgres', 'k6']:
            available = [x for x in snapshots if 'cpu.stat' in x.get('cgroups',{}).get(label,{})]
            if len(available) < 2:
                item['resources'][label] = {'unavailable': True}
                continue
            a, b = available[0], available[-1]
            elapsed = (datetime.datetime.fromisoformat(b['at'])-datetime.datetime.fromisoformat(a['at'])).total_seconds()
            cpu_a, cpu_b = [stat(x['cgroups'][label]['cpu.stat']) for x in [a,b]]
            events_a, events_b = [stat(x['cgroups'][label]['memory.events']) for x in [a,b]]
            item['resources'][label] = {'observedWindowSeconds': elapsed,
                'averageCpuCores': (cpu_b['usage_usec']-cpu_a['usage_usec'])/1e6/elapsed,
                'cpuDelta': {key: cpu_b[key]-cpu_a[key] for key in cpu_a},
                'sampledMemoryCurrentBytesMax': max(int(x['cgroups'][label]['memory.current']) for x in available),
                'memoryEventsDelta': {key: events_b[key]-events_a[key] for key in events_a}}
        server_file = level / 'server-evidence.json'
        if server_file.exists():
            evidence = json.loads(server_file.read_text())
            # Full completion entries remain in per-level raw evidence, outside Git.
            entries = evidence.get('logs', {}).pop('entries', [])
            evidence.get('logs', {})['retainedEntryCount'] = len(entries)
            evidence.get('logs', {})['failureExamples'] = [entry for entry in entries if entry['status'] != 200][:20]
            item['serverEvidence'] = evidence
            item['logsMatchHttpCount'] = evidence.get('logs', {}).get('completions') == native_count
        item['capacityInterpretationValid'] = (manifest['clockAudit']['rawMetricsAvailable']
            and manifest['clockAudit']['httpSamples'] == native_count
            and manifest['clockAudit'].get('pairedWallMonotonicSamples', 0) > 0
            and not (manifest['clockAudit']['backsteps'] or manifest['clockAudit'].get('wallMonotonicBacksteps')))
        if not item['capacityInterpretationValid']:
            # Preserve counts, durations, stock/counter observations and native output.
            # Rate/CPU calculations using stepped timestamps must not look valid.
            item['completedRequestsPerConfiguredSecond'] = None
            item['capacityInterpretationLimitation'] = 'Clock backsteps: do not interpret capacity or aligned time-series rates'
            for resource in item['resources'].values():
                if 'averageCpuCores' in resource:
                    resource['averageCpuCores'] = None
        report['levels'].append(item)
    report['control'] = json.loads((directory / 'control.json').read_text()) if (directory / 'control.json').exists() else None
    report['restoration'] = json.loads((directory / 'restoration.json').read_text()) if (directory / 'restoration.json').exists() else None
    report['notExecutedRates'] = [rate for rate in experiment['rates'] if 'rps-'+str(rate) not in experiment['levelDirectories']]
    (directory / 'growth-results.json').write_text(json.dumps(report, indent=2)+'\n')
    print('Saved growth-results.json; raw evidence and native percentiles retained.')


def main():
    if len(sys.argv) != 3:
        sys.exit('Usage: python3 tools/performance/correlate.py RUN_DIRECTORY GRAFANA_PASSWORD_FILE')
    directory, password_file = Path(sys.argv[1]), Path(sys.argv[2])
    if (directory / 'experiment.json').exists():
        experiment = json.loads((directory / 'experiment.json').read_text())
        for level in experiment['levelDirectories']:
            correlate(directory / level, password_file)
        summarize_growth(directory)
    else:
        correlate(directory, password_file)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        sys.exit('Correlation failed: ' + type(error).__name__)
