"""Offline correlation of bounded remote fixture work and real k6 request intervals."""
import datetime
import json


def epoch(value):
    return datetime.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


def percentiles(values):
    if not values:
        return {'count': 0}
    values = sorted(values)
    def percentile(q):
        position = (len(values) - 1) * q
        lower = int(position)
        upper = min(lower + 1, len(values) - 1)
        return values[lower] + (values[upper] - values[lower]) * (position - lower)
    return {'count': len(values), 'p50Ms': percentile(.5), 'p95Ms': percentile(.95),
            'p99Ms': percentile(.99)}


def correlate(directory, observation, stable):
    background = json.loads((directory / 'background.json').read_text())
    bridge = json.loads((directory / 'clock-bridge.json').read_text())
    report = background.get('report') or background.get('durableReport', {})
    result = {'sync': background, 'clockBridge': bridge, 'attributionAllowed': False,
              'criteria': {'minimumOverlapSeconds': 10, 'minimumContainedRequests': 50,
                           'minimumServerSamples': 2},
              'limitations': ['Request starts reconstructed from k6 http_req_duration exclude connection setup.',
                              'Clock bridge assumes Windows and WSL share UTC; independent clock audits must pass.',
                              'Server snapshots are sequential, coarse and add observation overhead.',
                              'Sparse period tails and different request mixes are descriptive, not reliable comparative percentiles.',
                              'No paired serving-only run at this rate; correlation cannot establish causality.',
                              'Fixed cache reuse, two releases/game; no inference, new acquisition, historical backfill or real IGDB timing.']}
    if not report.get('completedAt') or not report.get('startedAt'):
        result['interpretation'] = 'Synchronization completion unavailable; no degradation attributed to synchronization.'
        return result
    started, finished = epoch(report['startedAt']), epoch(report['completedAt'])
    # Host wall - controller wall is bracketed by the single immediate SSH clock RPC.
    offsets = bridge['hostMinusControllerSeconds']
    start_low, start_high = started - offsets[1], started - offsets[0]
    end_low, end_high = finished - offsets[1], finished - offsets[0]
    periods = {key: [] for key in ['before', 'during', 'after', 'boundary']}
    requests = []
    errors = {key: 0 for key in periods}
    with (directory / 'metrics.json').open() as raw:
        for line in raw:
            item = json.loads(line)
            if item.get('type') != 'Point' or item.get('metric') != 'http_req_duration':
                continue
            data = item['data']
            end = epoch(data['time'])
            start = end - data['value'] / 1000
            requests.append((start, end))
            period = ('before' if end < start_low else 'after' if start > end_high
                      else 'during' if start >= start_high and end <= end_low else 'boundary')
            periods[period].append(data['value'])
            if str(data.get('tags', {}).get('expected_response', data.get('tags', {}).get('status') == '200')).lower() != 'true':
                errors[period] += 1
    if not requests:
        result['interpretation'] = 'No HTTP request intervals; no degradation attributed to synchronization.'
        return result
    http_start, http_end = min(s for s, _ in requests), max(e for _, e in requests)
    overlap = max(0, min(http_end, end_low) - max(http_start, start_high))
    samples = [s for s in observation['samples'] if started <= s['wallSeconds'] <= finished]
    before, after = background.get('localizationBefore', {}), background.get('localizationAfter', {})
    deltas = {key: after[key] - before[key] if before.get(key) is not None and after.get(key) is not None else None
              for key in ['translated', 'reused', 'failed']}
    preparation = json.loads((directory / 'background-preparation.json').read_text())
    overlap_sufficient = overlap >= 10 and len(periods['during']) >= 50 and len(samples) >= 2
    counters = report.get('counters', {})
    successful = (background.get('status') == 'SUCCEEDED' and report.get('outcome') == 'SUCCEEDED' and
                  sum(counters.get(key, 0) for key in ['createdGames', 'updatedGames', 'unchangedGames']) == preparation['games']
                  and counters.get('failedGames') == 0 and counters.get('deferredGames') == 0)
    reuse_verified = (preparation['scenario'] != 'serving-sync-reuse' or
                      (deltas['translated'] == 0 and deltas['failed'] == 0 and
                       deltas['reused'] is not None and deltas['reused'] >= preparation['games']))
    inference_required = preparation['scenario'] == 'serving-sync-inference'
    helper = background.get('localizer', {})
    inference_verified = (deltas['translated'] is not None and deltas['translated'] >= 4 and deltas['failed'] == 0
        and helper.get('completedInferences', 0) >= 4 and helper.get('failedInferences') == 0)
    usable = (overlap_sufficient and stable and successful and reuse_verified
        and (not inference_required or inference_verified) and not observation['failures'])
    result.update(syncDurationSeconds=finished-started, conservativeOverlapSeconds=overlap,
                  httpEnvelopeSeconds=http_end-http_start,
                  conservativeHttpWindowFraction=overlap/(http_end-http_start) if http_end > http_start else 0,
                  requestIntervalsIntersectingSync=sum(e >= start_high and s <= end_low for s, e in requests),
                  serverSampleCountDuringSync=len(samples), localizationDelta=deltas,
                  successfulWorkVerified=successful, reuseVerified=reuse_verified if not inference_required else None,
                  inferenceVerified=inference_verified if inference_required else None,
                  httpRecoveryAfterSyncObserved=bool(periods['after']),
                  periods={key: {**percentiles(values), 'httpErrors': errors[key]} for key, values in periods.items()},
                  overlapSufficient=overlap_sufficient, interferenceObservationUsable=usable,
                  interpretation=('Overlap controls passed; exploratory correlation only, no causal attribution.'
                                  if usable else 'Insufficient overlap or failed evidence controls; no degradation attributed to synchronization.'))
    if not result['httpRecoveryAfterSyncObserved']:
        result['limitations'].append('No HTTP request fully after synchronization; serving recovery after sync is unobserved.')
    return result


def markdown(result):
    lines = ['# Remote synchronization interference evidence', '', result['interpretation'], '',
             'Raw PostgreSQL/JVM/Hikari, cgroups, clocks and recovery: observation.json, before.json, after.json, restoration.json.',
             'Synchronization lifecycle, result, localization and fixture ledger: background.json.', '']
    if 'syncDurationSeconds' in result:
        lines += [f"Sync duration: {result['syncDurationSeconds']:.3f} s; conservative overlap: {result['conservativeOverlapSeconds']:.3f} s.",
                  f"HTTP window fraction: {result['conservativeHttpWindowFraction']:.1%}; server samples during sync: {result['serverSampleCountDuringSync']}.", '',
                  '| Period | Requests | p50 ms | p95 ms | p99 ms | HTTP errors |',
                  '|---|---:|---:|---:|---:|---:|']
        for period, values in result['periods'].items():
            lines.append('| ' + ' | '.join(str(x) for x in [period, values['count'], values.get('p50Ms', '-'),
                         values.get('p95Ms', '-'), values.get('p99Ms', '-'), values['httpErrors']]) + ' |')
    recovery=result.get('restoration')
    if recovery:
        lines += ['', 'Restoration: ' + json.dumps({key:recovery.get(key) for key in
                    ['privateDataUnchanged','dependenciesUnchanged','originalSchedulingRestored',
                     'originalEnvironmentVerified','databaseRetainedForReview','readiness']})]
    else:
        lines += ['', 'Restoration receipt pending; do not interpret this run as recovered.']
    lines += ['', *['- ' + limitation for limitation in result['limitations']], '']
    return '\n'.join(lines)
