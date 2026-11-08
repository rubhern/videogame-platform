"""Bounded, same-run Actions evidence shared by the host and recovery runner."""
import io
import json
import time
import zipfile

import private_dev_promotion as trust

require = trust.require
RECOVERY_JOB = 'Isolated production recovery'
NAMES = {'ready': 'recovery-ready.json', 'request': 'recovery-request.json', 'result': 'recovery-result.json'}


def artifact_name(kind, run_id, attempt):
    require(kind in NAMES, 'Unknown recovery evidence kind.')
    return f'production-recovery-{kind}-{run_id}-{attempt}'


def job(api, run_id, missing_ok=False):
    jobs = api.request(f'/actions/runs/{run_id}/jobs?filter=latest&per_page=100')
    require(jobs.get('total_count', 101) <= 100, 'Too many workflow jobs.')
    matches = [j for j in jobs.get('jobs', []) if j.get('name') == RECOVERY_JOB]
    if not matches and missing_ok:
        return None
    require(len(matches) == 1, 'The isolated recovery job is missing or ambiguous.')
    return matches[0]


def read(api, kind, revision, run_id, attempt, *, wait_seconds=0, sleep=time.sleep, clock=time.monotonic):
    trust.validate_source_run(revision, run_id, attempt)
    deadline = clock() + wait_seconds
    while True:
        run = api.request(f'/actions/runs/{run_id}')
        require(run.get('head_sha') == revision and run.get('run_attempt') == int(attempt)
                and run.get('head_branch') == 'main' and run.get('event') == 'workflow_dispatch'
                and run.get('path') == '.github/workflows/deploy-production.yml'
                and run.get('head_repository', {}).get('full_name') == trust.REPOSITORY
                and run.get('status') == 'in_progress', 'Recovery evidence does not belong to the active dispatch.')
        recovery = job(api, run_id, missing_ok=wait_seconds > 0)
        if recovery is None:
            require(clock() < deadline, 'The recovery job did not become available.')
            sleep(min(30, max(0, deadline-clock())))
            continue
        require(recovery.get('status') != 'completed' or recovery.get('conclusion') == 'success',
                'The recovery job failed or was cancelled; inspect the fence.')
        if kind == 'result' and recovery.get('status') != 'completed':
            require(clock() < deadline, 'Recovery completion is not yet proven.')
            sleep(min(30, max(0, deadline-clock())))
            continue
        listing = api.request(f'/actions/runs/{run_id}/artifacts?per_page=100')
        require(listing.get('total_count', 101) <= 100, 'Too many recovery artifacts.')
        matches = [a for a in listing.get('artifacts', []) if a.get('name') == artifact_name(kind, run_id, attempt)]
        require(len(matches) <= 1, 'Ambiguous recovery evidence.')
        if not matches:
            require(clock() < deadline, 'Required recovery evidence is missing.')
            sleep(min(30, max(0, deadline-clock())))
            continue
        item = matches[0]
        require(not item.get('expired', True) and item.get('created_at', '') >= run['run_started_at'],
                'Recovery evidence is expired or predates this attempt.')
        archive = api.request(f"/actions/artifacts/{item['id']}/zip", archive=True)
        with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
            require(bundle.namelist() == [NAMES[kind]] and bundle.getinfo(NAMES[kind]).file_size <= 65536,
                    'Unexpected or oversized recovery evidence.')
            value = json.loads(bundle.read(NAMES[kind]))
        require(value.get('revision') == revision and value.get('runId') == str(run_id)
                and value.get('attempt') == str(attempt), 'Recovery evidence names another dispatch.')
        return value
