#!/usr/bin/env python3
"""Opt-in local functional interference experiment; never a capacity/stress gate.

prepare creates an immutable acquisition/checkpoint; scenario restores it before
every run; restore reconnects the original local catalogue and removes disposable
state. run performs all phases with restoration in finally. No real provider calls.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import urllib.request

import baseline as b
from provider_fixture import build_input, digest

PG = 'videogame-platform-performance-postgres'
PROVIDER = 'videogame-platform-performance-provider'
HELPER_IMAGE = 'videogame-platform/catalogue-localizer:1.0.0'
SCENARIOS = ['serving-only', 'serving-sync-reuse', 'serving-sync-inference']


def stamp():
    return {'wall': b.now(), 'wallNs': time.time_ns(), 'monotonicNs': time.monotonic_ns()}


def schema_hash():
    return hashlib.sha256(b''.join(path.read_bytes() for path in sorted(
        (b.ROOT / 'backend/src/main/resources/db/migration').glob('V*.sql')))).hexdigest()


def sql(text):
    return b.command('docker', 'exec', '-i', PG, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
                     '-U', 'postgres', '-d', 'videogame_performance', input=text)


def boundary():
    # dataset.sh verifies local engine, image, label, autoremove and anonymous mount.
    profile = json.loads(b.command('bash', str(b.TOOL / 'dataset.sh'), 'inspect'))
    marker = json.loads(sql('SELECT row_to_json(b) FROM performance_data.boundary b;'))
    if marker != {'purpose': 'disposable-performance-only', 'schema_checksum': schema_hash(),
                  'profile': 'growth', 'reference_date': profile['evaluation_date_madrid']}:
        raise RuntimeError('Fresh growth for the current Madrid date and matching schema required')
    return profile


def load(directory):
    directory = Path(directory).resolve()
    if directory.parent != (b.TOOL / 'results').resolve() or not directory.name.startswith('interference-'):
        raise RuntimeError('Experiment must reside in the ignored performance results directory')
    return directory, json.loads((directory / 'experiment.json').read_text())


def compose(directory, experiment, active, *args):
    environment = {**os.environ, 'APPLICATION_VERSION': experiment['applicationVersion'],
                   'CATALOGUE_TRANSLATION_MODEL_DIR': experiment['modelDirectory']}
    command = ['docker', 'compose', '--project-name', 'videogame-platform', '--env-file',
               str(Path(experiment['runtimeCheckout']) / '.env'), '-f', str(b.ROOT / 'compose.yaml'),
               '-f', str(b.ROOT / 'compose.observability.yaml'), '-f', str(directory / 'runtime.yaml')]
    if active:
        command += ['-f', str(b.ROOT / 'compose.localization.yaml'), '-f', str(b.TOOL / 'compose.growth.yaml'),
                    '-f', str(b.TOOL / 'compose.interference.yaml'), '--profile', 'localization']
    with (directory / 'operations.log').open('a') as output:
        subprocess.run(command + list(args), env=environment, stdout=output, stderr=output,
                       check=True, timeout=120)


def ready(url):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            response = b.get(url)
            if response.get('status') == 'UP' or response.get('ready') is True:
                return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('Readiness deadline exceeded: ' + url)


def remove_provider():
    result = subprocess.run(['docker', 'inspect', PROVIDER], capture_output=True, text=True)
    if result.returncode:
        return
    container = json.loads(result.stdout)[0]
    if container['Config']['Labels'].get('videogame-platform.performance-provider') != 'disposable' \
            or not container['HostConfig']['AutoRemove'] or container['Config']['Image'] != HELPER_IMAGE:
        raise RuntimeError('Unrecognized provider container; refusing removal')
    b.command('docker', 'rm', '--force', PROVIDER)


def compare_inputs(directory):
    ledgers = [json.loads((directory / name / 'provider-evidence.json').read_text()) for name in SCENARIOS[1:]]
    def semantic_calls(ledger):
        return [{key: row[key] for key in ['path', 'status', 'querySha256', 'responseSha256', 'gameIds']}
                for row in ledger['requests']]
    workloads = [json.loads((directory / name / 'workload.json').read_text())['requests'] for name in SCENARIOS]
    comparison = {'sameInputHash': ledgers[0]['payloadSha256'] == ledgers[1]['payloadSha256'],
                  'sameProviderCallsAndResponses': semantic_calls(ledgers[0]) == semantic_calls(ledgers[1]),
                  'samePublicCases': all(value == workloads[0] for value in workloads),
                  'externalProviderTimingIncluded': False}
    b.write(directory / 'comparison.json', comparison)
    if not all(comparison[key] for key in ['sameInputHash', 'sameProviderCallsAndResponses', 'samePublicCases']):
        raise RuntimeError('Comparable-input control failed')
    return comparison


def provider(directory):
    remove_provider()
    b.command('docker', 'run', '--detach', '--rm', '--name', PROVIDER, '--network', b.NETWORK,
              '--label', 'videogame-platform.performance-provider=disposable', '--read-only',
              '--label', 'com.docker.compose.project=performance-experiment',
              '--label', 'com.docker.compose.service=performance-provider',
              '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '128m', '--cpus', '0.25',
              '--publish', '127.0.0.1:8093:8093',
              '--mount', f'type=bind,source={b.TOOL / "provider_fixture.py"},target=/fixture.py,readonly',
              '--mount', f'type=bind,source={directory / "provider-input.json"},target=/input.json,readonly',
              '--entrypoint', 'python', HELPER_IMAGE, '/fixture.py', '/input.json')
    ready('http://127.0.0.1:8093/ready')


def sync(date):
    started = stamp()
    request = urllib.request.Request(b.MANAGEMENT + '/cataloguesync', method='POST',
                                    data=json.dumps({'from': date, 'to': date}).encode(),
                                    headers={'Content-Type': 'application/json'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=90) as response:
        report = json.load(response)
    return {'dispatched': started, 'acknowledged': stamp(), 'report': report}


def logs(container):
    result = subprocess.run(['docker', 'logs', container], capture_output=True, text=True, check=True)
    return result.stdout + result.stderr


def translation_state():
    return json.loads(sql("""SELECT json_build_object(
        'games', (SELECT count(*) FROM performance_data.interference_game),
        'fingerprints', (SELECT json_agg(json_build_object('fingerprint',f.fingerprint,
            'resolved',c.translated_text IS NOT NULL,'runtimeRevision',c.runtime_revision,
            'claimed',c.claim_id IS NOT NULL) ORDER BY f.fingerprint)
            FROM performance_data.interference_fingerprint f JOIN catalogue.content_translation c USING(fingerprint)),
        'linkedSummaries', (SELECT count(*) FROM catalogue.game_summary_translation l
            JOIN catalogue.game_external_reference r USING(game_id)
            JOIN performance_data.interference_game i ON i.provider_id=r.provider_id WHERE r.provider='IGDB'));
        """))


def observations():
    values = {}
    meters = ['http.server.requests', 'jvm.memory.used', 'jvm.gc.pause', 'jvm.threads.live',
              'process.cpu.usage', 'hikaricp.connections.active', 'hikaricp.connections.pending',
              'hikaricp.connections.timeout', 'catalogue.synchronization.provider.request.duration',
              'catalogue.synchronization.provider.retry', 'catalogue.synchronization.trigger.duration']
    for meter in meters:
        try:
            values[meter] = b.get(b.MANAGEMENT + '/metrics/' + meter
                                  + ('?tag=area:heap' if meter == 'jvm.memory.used' else ''))
        except Exception as error:
            values[meter] = {'unavailable': type(error).__name__}
    for outcome in ['translated', 'reused', 'skipped', 'failed', 'busy', 'fallback']:
        try:
            meter = b.get(b.MANAGEMENT + '/metrics/catalogue.localization.content?tag=outcome:' + outcome)
            values['localization.' + outcome] = sum(point['value'] for point in meter['measurements']
                                                    if point['statistic'] == 'COUNT')
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            values['localization.' + outcome] = 0  # An unregistered bounded outcome counter.
    return values


def public_completed():
    result = {}
    for uri in ['/api/v1/games', '/api/v1/games/{gameId}', '/api/v1/releases', '/api/v1/featured-releases']:
        try:
            meter = b.get(b.MANAGEMENT + '/metrics/http.server.requests?tag=uri:'
                          + urllib.parse.quote(uri, safe=''))
            result[uri] = sum(point['value'] for point in meter['measurements'] if point['statistic'] == 'COUNT')
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            result[uri] = 0
    return result


def light_sample(containers):
    result = {'clock': stamp(), 'cgroups': {}}
    for label, container in containers.items():
        try:
            # One exec per container; CPU use/throttling, RAM/OOM and block-device bytes/ops.
            result['cgroups'][label] = dict(zip(['cpu.stat', 'memory.current', 'memory.events', 'io.stat'],
                b.command('docker', 'exec', container, 'sh', '-c',
                    'for f in cpu.stat memory.current memory.events io.stat; do cat /sys/fs/cgroup/$f; echo __FILE__; done')
                .split('__FILE__')[:4]))
        except Exception as error:
            result['cgroups'][label] = {'unavailable': type(error).__name__}
    result['postgres'] = json.loads(sql("""BEGIN READ ONLY; SET LOCAL statement_timeout='2s';
        SELECT json_build_object('activity',(SELECT json_agg(a) FROM
            (SELECT state,wait_event_type,wait_event,count(*) FROM pg_stat_activity
             WHERE datname=current_database() GROUP BY state,wait_event_type,wait_event) a),
          'locks',(SELECT json_agg(l) FROM (SELECT locktype,mode,granted,count(*) FROM pg_locks
             WHERE pid IN (SELECT pid FROM pg_stat_activity WHERE datname=current_database())
             GROUP BY locktype,mode,granted) l),
          'database',(SELECT row_to_json(d) FROM (SELECT xact_commit,xact_rollback,blks_read,blks_hit,
             temp_files,temp_bytes,deadlocks FROM pg_stat_database WHERE datname=current_database()) d),
          'sync',(SELECT row_to_json(s) FROM (SELECT run_id,run_status,started_at,heartbeat_at,completed_at,
             outcome_code,report FROM catalogue.synchronization_run ORDER BY started_at DESC LIMIT 1) s));
        ROLLBACK;"""))
    result['application'] = observations()
    result['completedClock'] = stamp()
    return result


def prepare(runtime, model):
    b.TOOL.joinpath('results').mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='interference-', dir=b.TOOL / 'results'))
    application = b.service('application')
    settings = dict(value.split('=', 1) for value in application['Config']['Env'])
    if settings['APPLICATION_DB_URL'] != 'jdbc:postgresql://postgres:5432/videogame_platform' \
            or settings.get('CATALOGUE_SYNC_SCHEDULING_ENABLED', 'false') != 'false':
        raise RuntimeError('Start from the supported persistent local application with cron disabled')
    if b.command('docker', 'ps', '--filter', 'label=com.docker.compose.project=videogame-platform',
                 '--filter', 'label=com.docker.compose.service=catalogue-localizer', '--format', '{{.ID}}'):
        raise RuntimeError('Stop the optional localizer before this isolated experiment')
    model = Path(model).resolve()
    model_manifest = json.loads((model / 'manifest.json').read_text())
    experiment = {'runtimeCheckout': str(Path(runtime).resolve()), 'modelDirectory': str(model),
                  'applicationVersion': application['Config']['Image'].rsplit(':', 1)[1],
                  'originalApplication': b.environment(application), 'modelManifest': model_manifest,
                  'schemaChecksum': schema_hash(), 'capacityInterpretationAllowed': False,
                  'providerTimingScope': 'Excluded: fixture timings never estimate real IGDB synchronization duration',
                  'created': stamp(), 'scenarios': SCENARIOS, 'rate': 1, 'durationSeconds': 60}
    b.write(directory / 'experiment.json', experiment)
    # Preserve the runtime's existing secret source; never copy or serialize its values.
    (directory / 'runtime.yaml').write_text('services:\n  application:\n    env_file: !override\n'
        + '      - path: ' + json.dumps(str(Path(runtime).resolve() / 'backend/.env')) + '\n        required: true\n')
    b.write(directory / 'persistent-before.json', json.loads(b.command('bash', str(b.TOOL / 'dataset.sh'), 'current')))
    profile = boundary()
    experiment['referenceDate'] = profile['evaluation_date_madrid']
    inspection = json.loads(b.command('docker', 'inspect', PG))[0]
    if b.NETWORK not in inspection['NetworkSettings']['Networks']:
        b.command('docker', 'network', 'connect', b.NETWORK, PG)
    calibrations = []
    try:
        for count in [2, 4, 8]:
            if calibrations:
                compose(directory, experiment, True, 'stop', 'application')
                b.command('bash', str(b.TOOL / 'dataset.sh'), 'generate', 'growth', experiment['referenceDate'])
            data = build_input(b.ROOT, count, experiment['referenceDate'])
            b.write(directory / 'provider-input.json', data)
            provider(directory)
            compose(directory, experiment, True, 'up', '-d', '--no-deps', '--force-recreate',
                    'catalogue-localizer', 'application')
            ready('http://127.0.0.1:8092/ready')
            ready(b.MANAGEMENT + '/health/readiness')
            measured = sync(experiment['referenceDate'])
            elapsed = (measured['acknowledged']['monotonicNs'] - measured['dispatched']['monotonicNs']) / 1e9
            calibrations.append({'games': count, 'fixtureBackedOverlapSeconds': elapsed, **measured})
            if measured['report']['outcome'] != 'SUCCEEDED':
                raise RuntimeError('Calibration synchronization failed')
            if 3 <= elapsed <= 40:
                break
            if elapsed > 40:
                raise RuntimeError('Smallest fixture exceeds functional overlap budget; inspect before continuing')
        else:
            raise RuntimeError('No bounded calibration produced a three-second overlap window')
        experiment.update(backgroundGames=count, backgroundReleases=count * 2,
                          expectedInferences=count + 2, inputSha256=data['payloadSha256'],
                          calibrationCriterion='Fixture-backed window between 3 and 40 seconds; collection time adds to the nominal 1-second sampling interval',
                          calibrations=calibrations)
        ids = ','.join("'" + str(game['id']) + "'" for game in data['games'])
        sql(f"""CREATE TABLE performance_data.interference_game(provider_id text PRIMARY KEY);
            INSERT INTO performance_data.interference_game SELECT unnest(ARRAY[{ids}]);
            CREATE TABLE performance_data.interference_fingerprint(fingerprint varchar(64) PRIMARY KEY);
            INSERT INTO performance_data.interference_fingerprint
            SELECT catalogue.spanish_source_fingerprint(g.summary_text) FROM catalogue.game_snapshot g
            JOIN catalogue.game_external_reference r USING(game_id)
            JOIN performance_data.interference_game i ON i.provider_id=r.provider_id WHERE r.provider='IGDB'
            UNION SELECT catalogue.spanish_source_fingerprint('Tactical exploration')
            UNION SELECT catalogue.spanish_source_fingerprint('Cooperative missions');""")
        state = translation_state()
        if len(state['fingerprints']) != count + 2 or not all(row['resolved'] for row in state['fingerprints']):
            raise RuntimeError('Real localizer did not resolve the calibration subset')
        b.write(directory / 'calibration-state.json', state)
        (directory / 'calibration-localizer.log').write_text(logs(b.service('catalogue-localizer')['Id']))
        compose(directory, experiment, True, 'stop', 'application')
        with (directory / 'checkpoint.dump').open('wb') as output:
            subprocess.run(['docker', 'exec', PG, 'pg_dump', '-Fc', '-U', 'postgres', '-d', 'videogame_performance'],
                           stdout=output, check=True, timeout=120)
        experiment['checkpointSha256'] = hashlib.sha256((directory / 'checkpoint.dump').read_bytes()).hexdigest()
        b.write(directory / 'experiment.json', experiment)
        print(directory, flush=True)
        return directory
    except Exception:
        restore(directory)
        raise


def scenario(directory, name):
    directory, experiment = load(directory)
    boundary()
    if name not in SCENARIOS or hashlib.sha256((directory / 'checkpoint.dump').read_bytes()).hexdigest() != experiment['checkpointSha256']:
        raise RuntimeError('Scenario or checkpoint mismatch')
    data = json.loads((directory / 'provider-input.json').read_text())
    if data['payloadSha256'] != experiment['inputSha256'] or digest({key: value for key, value in data.items() if key != 'payloadSha256'}) != data['payloadSha256']:
        raise RuntimeError('Immutable provider input mismatch')
    level = directory / name
    level.mkdir()  # Refuse to overwrite existing evidence.
    compose(directory, experiment, True, 'stop', 'application')
    with (directory / 'checkpoint.dump').open('rb') as source, (directory / 'operations.log').open('a') as output:
        subprocess.run(['docker', 'exec', '-i', PG, 'pg_restore', '--clean', '--if-exists', '--exit-on-error',
                        '-U', 'postgres', '-d', 'videogame_performance'], stdin=source, stdout=output,
                       stderr=output, check=True, timeout=120)
    boundary()
    state_sql = (b.TOOL / 'interference-state.sql').read_text()
    b.command('docker', 'exec', '-i', PG, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
              '-v', 'schema_checksum=' + schema_hash(), '-v', 'scenario=' + name,
              '-U', 'postgres', '-d', 'videogame_performance', input=state_sql)
    # pg_dump does not preserve planner statistics; settle restore writes offline.
    # This prepares comparable fixture state, without changing any query/index.
    sql('ANALYZE; CHECKPOINT;')
    b.write(level / 'state-before.json', translation_state())
    provider(directory)  # Fresh bounded request ledger per scenario.
    compose(directory, experiment, True, 'up', '-d', '--no-deps', '--force-recreate', 'catalogue-localizer', 'application')
    ready('http://127.0.0.1:8092/ready')
    ready(b.MANAGEMENT + '/health/readiness')
    application, localizer = b.service('application'), b.service('catalogue-localizer')
    postgres = json.loads(b.command('docker', 'inspect', PG))[0]
    requests = b.growth_requests()
    for request in requests:
        body = b.get(b.PRODUCT + request['path'])
        if request['kind'].startswith('releases_') and 'page' in body:
            request['expectedTotal'] = body['page']['totalItems']
        elif request['kind'] == 'catalogue_search':
            if body['page']['totalItems'] != request['expectedTotal']:
                raise RuntimeError('Search cohort changed')
            request['firstGameId'] = body['items'][0]['gameId']
        elif request['kind'] == 'game_detail':
            request['ratingCount'] = body['ratingStatistics']['count']
    workload = {'runId': 'perf-' + directory.name + '-' + name, 'dataset': 'interference',
                'evaluatedOn': experiment['referenceDate'], 'requests': requests, 'rate': 1,
                'durationSeconds': 60, 'preAllocatedVUs': 4, 'maxVUs': 4}
    b.write(level / 'workload.json', workload)
    b.write(level / 'before.json', b.snapshot(application, postgres))
    containers = {'application': application['Id'], 'postgres': postgres['Id'], 'localizer': localizer['Id']}
    samples, clocks, failures = [], [], []
    stopped = threading.Event()
    def sample_loop():
        while not stopped.is_set():
            try:
                samples.append(light_sample(containers))
            except Exception as error:
                failures.append({'clock': stamp(), 'error': str(error)})
            stopped.wait(1)
    def clock_loop():
        while not stopped.is_set():
            clocks.append(stamp())
            stopped.wait(0.25)
    threads = [threading.Thread(target=sample_loop), threading.Thread(target=clock_loop)]
    for thread in threads:
        thread.start()
    before_metrics = observations()
    runner = workload['runId'] + '-k6'
    background, overlap_counters = None, None
    try:
        start = stamp()
        with (level / 'k6.log').open('w') as output:
            process = subprocess.Popen(['docker', 'run', '--rm', '--name', runner, '--network', b.NETWORK,
                '--user', f'{os.getuid()}:{os.getgid()}', '--read-only', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges:true', '--memory', '1g', '--cpus', '1',
                '--mount', f'type=bind,source={b.TOOL},target=/scripts,readonly',
                '--mount', f'type=bind,source={level},target=/results', '--env', 'K6_NO_USAGE_REPORT=true',
                b.IMAGE, 'run', '--out', 'json=/results/metrics.json', '/scripts/baseline.js'], stdout=output, stderr=output)
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                time.sleep(5)
                if name != 'serving-only':
                    overlap_start = {'clock': stamp(), 'completed': public_completed()}
                    future = executor.submit(sync, experiment['referenceDate'])
                    background = future.result(timeout=95)
                    overlap_counters = {'beforeDispatch': overlap_start,
                                        'afterAcknowledgement': {'clock': stamp(), 'completed': public_completed()}}
                exit_code = process.wait(timeout=75)
        end = stamp()
    finally:
        stopped.set()
        for thread in threads:
            thread.join(timeout=15)
        b.write(level / 'samples.json', samples)
        b.write(level / 'clock-samples.json', clocks)
        b.write(level / 'sampling-errors.json', failures)
        if 'process' in locals() and process.poll() is None:
            subprocess.run(['docker', 'stop', '-t', '1', runner], capture_output=True)
            process.wait(timeout=15)
    after_metrics = observations()
    helper_logs = logs(localizer['Id'])
    (level / 'localizer.log').write_text(helper_logs)
    (level / 'application.log').write_text(logs(application['Id']))
    state_after = translation_state()
    b.write(level / 'state-after.json', state_after)
    provider_evidence = b.get('http://127.0.0.1:8093/evidence')
    b.write(level / 'provider-evidence.json', provider_evidence)
    b.write(level / 'after.json', b.snapshot(application, postgres))
    audit = b.clock_audit(level)
    audit['pairedDiscontinuities'] = [{'previous': previous, 'current': current,
        'wallMinusMonotonicSeconds': ((current['wallNs'] - previous['wallNs']) -
                                     (current['monotonicNs'] - previous['monotonicNs'])) / 1e9}
        for previous, current in zip(clocks, clocks[1:])
        if abs((current['wallNs'] - previous['wallNs']) - (current['monotonicNs'] - previous['monotonicNs'])) > 100_000_000]
    translated = helper_logs.count('translation outcome=completed')
    delta = {outcome: after_metrics['localization.' + outcome] - before_metrics['localization.' + outcome]
             for outcome in ['translated', 'reused', 'skipped', 'failed', 'busy', 'fallback']}
    expected = experiment['expectedInferences'] if name == 'serving-sync-inference' else 0
    summary = json.loads((level / 'summary.json').read_text())
    evidence = {'scenario': name, 'inputSha256': experiment['inputSha256'], 'checkpointSha256': experiment['checkpointSha256'],
        'capacityInterpretationAllowed': False, 'servingWindow': {'launched': start, 'finished': end},
        'backgroundWindow': background, 'overlapHttpCounters': overlap_counters,
        'inferenceCompletions': translated, 'localizationDelta': delta, 'clockAudit': audit,
        'samplingErrors': failures, 'sampleCount': len(samples), 'application': b.environment(application),
        'postgres': b.environment(postgres), 'localizer': b.environment(localizer),
        'metricsBefore': before_metrics, 'metricsAfter': after_metrics, 'k6ExitCode': exit_code,
        'k6Metrics': summary['metrics'], 'stateBefore': json.loads((level / 'state-before.json').read_text()),
        'stateAfter': state_after, 'harnessSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'k6ScriptSha256': hashlib.sha256((b.TOOL / 'baseline.js').read_bytes()).hexdigest(), 'k6Image': b.IMAGE}
    observed_ids = sorted({value for row in provider_evidence['requests'] if row['path'] == '/v4/games'
                           for value in row['gameIds']})
    expected_ids = [] if name == 'serving-only' else sorted(game['id'] for game in data['games'])
    evidence['processedProviderGameIds'] = observed_ids
    evidence['functionalPassed'] = (exit_code == 0 and translated == expected and delta['translated'] == expected
        and observed_ids == expected_ids and all(row['status'] == 200 for row in provider_evidence['requests'])
        and delta['failed'] == 0 and delta['busy'] == 0 and not failures
        and (name == 'serving-only' or background['report']['outcome'] == 'SUCCEEDED'
             and all(row['resolved'] and not row['claimed'] for row in state_after['fingerprints'])
             and state_after['linkedSummaries'] == experiment['backgroundGames'])
        and (name != 'serving-sync-reuse' or delta['reused'] == experiment['expectedInferences']))
    b.write(level / 'evidence.json', evidence)
    print(name + ': ' + ('PASS' if evidence['functionalPassed'] else 'FAIL'), flush=True)
    if not evidence['functionalPassed']:
        raise RuntimeError('Functional scenario failed; inspect ' + str(level))
    return evidence


def restore(directory):
    directory, experiment = load(directory)
    # Restoration must also fail closed if the caller changes Docker context.
    b.command('bash', str(b.TOOL / 'dataset.sh'), 'current')
    compose(directory, experiment, True, 'stop', 'application', 'catalogue-localizer')
    compose(directory, experiment, False, 'up', '-d', '--no-deps', '--force-recreate', 'application')
    ready(b.MANAGEMENT + '/health/readiness')
    remove_provider()
    b.command('bash', str(b.TOOL / 'dataset.sh'), 'stop')
    after = json.loads(b.command('bash', str(b.TOOL / 'dataset.sh'), 'current'))
    b.write(directory / 'persistent-after.json', after)
    before = json.loads((directory / 'persistent-before.json').read_text())
    # Profiles include time-dependent fields; compare durable catalogue cardinalities.
    b.write(directory / 'restoration.json', {'readiness': 'UP', 'beforeCounts': before['counts'],
                                            'afterCounts': after['counts'], 'sameCounts': before['counts'] == after['counts']})
    if before['counts'] != after['counts']:
        raise RuntimeError('Persistent catalogue cardinalities changed; inspect restoration evidence')
    print('Restored persistent application; disposable database/provider removed; localizer stopped.', flush=True)


def report(directory, output):
    """Condense raw evidence without converting clock-invalid measurements into capacity."""
    directory, experiment = load(directory)
    comparison = compare_inputs(directory)
    before = json.loads((directory / 'persistent-before.json').read_text())['counts']
    after = json.loads((directory / 'persistent-after.json').read_text())['counts']
    if before != after:
        raise RuntimeError('Persistent catalogue changed')
    scenarios = []
    def counter_values(raw):
        return {line.split()[0]: int(line.split()[1]) for line in raw.strip().splitlines()}
    def meter_value(sample, name):
        return max([point['value'] for point in sample['application'][name].get('measurements', [])] or [0])
    for name in SCENARIOS:
        level = directory / name
        evidence = json.loads((level / 'evidence.json').read_text())
        samples = json.loads((level / 'samples.json').read_text())
        ledger = json.loads((level / 'provider-evidence.json').read_text())
        observed_ids = sorted({value for row in ledger['requests'] if row['path'] == '/v4/games' for value in row['gameIds']})
        expected_ids = [] if name == 'serving-only' else list(range(850001, 850001 + experiment['backgroundGames']))
        if not evidence['functionalPassed'] or observed_ids != expected_ids or not all(row['status'] == 200 for row in ledger['requests']):
            raise RuntimeError('Functional provider control failed: ' + name)
        resources = {}
        for label in ['application', 'postgres', 'localizer']:
            groups = [sample['cgroups'][label] for sample in samples]
            if not groups or any('unavailable' in group for group in groups):
                resources[label] = {'unavailable': True}
                continue
            first, last = [counter_values(group['cpu.stat']) for group in [groups[0], groups[-1]]]
            resources[label] = {'cpuCounterDelta': {key: last[key] - first[key] for key in first},
                                'maximumObservedMemoryBytes': max(int(group['memory.current'].strip()) for group in groups),
                                'memoryEventsBefore': counter_values(groups[0]['memory.events']),
                                'memoryEventsAfter': counter_values(groups[-1]['memory.events']),
                                'ioBefore': groups[0]['io.stat'].strip(), 'ioAfter': groups[-1]['io.stat'].strip()}
        clocks = [sample['clock']['monotonicNs'] for sample in samples]
        background = evidence['backgroundWindow']
        overlap_samples = ([sample for sample in samples if background['dispatched']['monotonicNs'] <=
                            sample['clock']['monotonicNs'] <= background['acknowledged']['monotonicNs']] if background else [])
        bracket = evidence['overlapHttpCounters']
        public_delta = (sum(bracket['afterAcknowledgement']['completed'].values()) -
                        sum(bracket['beforeDispatch']['completed'].values())) if bracket else 0
        overlap_window = ({'fromMonotonicNs': max(background['dispatched']['monotonicNs'],
                            evidence['servingWindow']['launched']['monotonicNs']),
                           'toMonotonicNs': min(background['acknowledged']['monotonicNs'],
                            evidence['servingWindow']['finished']['monotonicNs'])} if background else None)
        if background and (not overlap_samples or public_delta <= 0 or
                           overlap_window['fromMonotonicNs'] >= overlap_window['toMonotonicNs']):
            raise RuntimeError('Serving/background overlap control failed: ' + name)
        metrics = evidence['k6Metrics']
        scenarios.append({'scenario': name, 'functionalPassed': True, 'processedProviderGameIds': observed_ids,
            'stateBefore': evidence['stateBefore'], 'stateAfter': evidence['stateAfter'],
            'inferenceCompletions': evidence['inferenceCompletions'], 'localizationDelta': evidence['localizationDelta'],
            'http': {key: metrics[key]['values'] for key in ['http_reqs', 'http_req_duration', 'http_req_failed',
                     'checks', 'response_timeouts', 'dropped_iterations'] if key in metrics},
            'perCaseDuration': {request['name']: metrics['http_req_duration{name:' + request['name'] + '}']['values']
                               for request in json.loads((level / 'workload.json').read_text())['requests']},
            'clockAudit': evidence['clockAudit'], 'servingWindow': evidence['servingWindow'], 'backgroundWindow': background,
            'controllerOverlapWindow': overlap_window,
            'publicCompletionsInControllerBracket': public_delta, 'overlapHttpCounters': bracket,
            'sampleCount': len(samples), 'samplesInBackgroundWindow': len(overlap_samples),
            'runningSyncSamples': sum(sample['postgres']['sync'] is not None and sample['postgres']['sync']['run_status'] == 'running'
                                      for sample in samples),
            'maximumSampleIntervalSeconds': max([(right-left)/1e9 for left,right in zip(clocks, clocks[1:])] or [0]),
            'resources': resources, 'maximumObservedHeapBytes': max(meter_value(sample, 'jvm.memory.used') for sample in samples),
            'maximumObservedHikariActive': max(meter_value(sample, 'hikaricp.connections.active') for sample in samples),
            'maximumObservedHikariPending': max(meter_value(sample, 'hikaricp.connections.pending') for sample in samples),
            'maximumObservedUngrantedLocks': max(sum(row['count'] for row in sample['postgres']['locks'] if not row['granted']) for sample in samples),
            'postgresBefore': samples[0]['postgres']['database'], 'postgresAfter': samples[-1]['postgres']['database'],
            'metricsBefore': evidence['metricsBefore'], 'metricsAfter': evidence['metricsAfter'],
            'application': evidence['application'], 'postgres': evidence['postgres'], 'localizer': evidence['localizer'],
            'providerEvidence': ledger, 'rawEvidenceSha256': {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                for path in sorted(level.iterdir()) if path.is_file()}})
    input_data = json.loads((directory / 'provider-input.json').read_text())
    result = {'scope': 'Functional WSL validation only; no capacity or real-provider duration conclusion',
        'capacityInterpretationAllowed': False, 'rawDirectory': 'tools/performance/results/' + directory.name,
        'experiment': {key: value for key, value in experiment.items() if key not in ['runtimeCheckout','modelDirectory']},
        'providerInput': input_data, 'comparison': comparison, 'scenarios': scenarios,
        'restoration': {'sameCounts': True, 'beforeCounts': before, 'afterCounts': after},
        'focusedStateGuards': json.loads((directory / 'state-guard-checks.json').read_text())
            if (directory / 'state-guard-checks.json').exists() else 'Not executed in this run',
        'pendingProviderTimingCalibration': 'Small normal-rate real-IGDB sample of call latency, retries/rate-limit waits and end-to-end sync; not a provider load test'}
    b.write(Path(output), result)
    print(output, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ['run', 'prepare']:
        entry = sub.add_parser(name)
        entry.add_argument('--runtime-checkout', required=True, help='Supported checkout containing existing .env/backend/.env')
        entry.add_argument('--model-dir', required=True, help='Installed approved real CT2 model; never downloaded by this runner')
    entry = sub.add_parser('scenario')
    entry.add_argument('directory')
    entry.add_argument('name', choices=SCENARIOS)
    entry = sub.add_parser('restore')
    entry.add_argument('directory')
    entry = sub.add_parser('report')
    entry.add_argument('directory')
    entry.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.command in ['run', 'prepare']:
        result = prepare(args.runtime_checkout, args.model_dir)
        if args.command == 'run':
            try:
                evidence = [scenario(result, name) for name in SCENARIOS]
                b.write(result / 'evidence.json', {'experiment': json.loads((result / 'experiment.json').read_text()),
                                                'scenarios': evidence, 'comparison': compare_inputs(result)})
            finally:
                restore(result)
    elif args.command == 'scenario':
        scenario(args.directory, args.name)
    elif args.command == 'restore':
        restore(args.directory)
    else:
        report(args.directory, args.output)
