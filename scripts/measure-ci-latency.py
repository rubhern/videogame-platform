#!/usr/bin/env python3
"""Snapshot recent successful quality/security pairs from GitHub Actions (read-only)."""
import argparse
import concurrent.futures
import datetime
import json
import math
import pathlib
import statistics
import subprocess
import time

QUALITY = 'Walking-skeleton build and verify'
SECURITY = 'Security'
PRIVATE_DEV = 'Promote application to private dev'


def api(path):
    for attempt in range(3):
        response = subprocess.run(['gh', 'api', path], capture_output=True, check=False)
        if response.returncode == 0:
            return json.loads(response.stdout)
        if attempt == 2 or not any(f'HTTP {status}'.encode() in response.stderr
                                   for status in (500, 502, 503, 504)):
            raise SystemExit(f'GitHub evidence unavailable for {path}: {response.stderr.decode().strip()}')
        time.sleep(attempt + 1)


def elapsed(start, end):
    def instant(value):
        return datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
    return round((instant(end) - instant(start)).total_seconds())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', default='rubhern/videogame-platform')
    parser.add_argument('--output-directory', type=pathlib.Path, required=True)
    parser.add_argument('--pr-samples', type=int, default=12)
    parser.add_argument('--main-samples', type=int, default=3)
    args = parser.parse_args()
    directory = args.output_directory
    directory.mkdir(parents=True, exist_ok=True)
    runs = []
    for page in (1, 2):
        runs.extend(api(f'repos/{args.repository}/actions/runs?per_page=100&page={page}')['workflow_runs'])
    groups = {}
    promotions = {}
    for run in runs:
        if (run['name'] == PRIVATE_DEV and run['head_branch'] == 'main'
                and run['event'] == 'workflow_dispatch' and run['conclusion'] == 'success'
                and run['run_attempt'] == 1):
            promotions.setdefault(run['head_sha'], []).append(run)
        if run['name'] not in (QUALITY, SECURITY):
            continue
        groups.setdefault((run['event'], run['head_sha']), {}).setdefault(run['name'], run)
    selected = []
    for event, count in (('pull_request', args.pr_samples), ('push', args.main_samples)):
        pairs = [pair for (kind, _), pair in groups.items() if kind == event
                 and len(pair) == 2 and all(run['conclusion'] == 'success'
                 and run['run_attempt'] == 1 for run in pair.values())
                 and (event != 'push' or all(run['head_branch'] == 'main' for run in pair.values()))]
        selected.extend(pairs[:count])

    def fetch(run):
        # Capture the exact attempt; a later rerun must not change this evidence.
        path = f"repos/{args.repository}/actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs?per_page=100"
        jobs = api(path)
        (directory / f"{run['id']}-jobs.json").write_text(json.dumps(jobs, indent=2) + '\n')
        return run['id'], jobs['jobs']

    paired_runs = [run for pair in selected for run in pair.values()]
    matching_promotions = {}
    for pair in selected:
        run = pair[QUALITY]
        if run['event'] == 'push':
            candidates = promotions.get(run['head_sha'], ())
            if candidates:
                matching_promotions[run['head_sha']] = min(candidates, key=lambda item: item['created_at'])
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        jobs_by_run = dict(executor.map(fetch, [*paired_runs, *matching_promotions.values()]))
    report = []
    for pair in selected:
        start = min(run['created_at'] for run in pair.values())
        result = {'event': pair[QUALITY]['event'], 'sha': pair[QUALITY]['head_sha'], 'runs': []}
        for run in pair.values():
            jobs = jobs_by_run[run['id']]
            gate = next(job for job in jobs if job['name'].startswith('Required '))
            record = {'id': run['id'], 'url': run['html_url'], 'attempt': run['run_attempt'],
                      'gate_s': elapsed(start, gate['completed_at']),
                      'workflow_s': elapsed(run['created_at'], run['updated_at']),
                      'initial_wait_s': elapsed(run['created_at'], min(job['started_at'] for job in jobs)),
                      'jobs': []}
            for job in jobs:
                active = job['conclusion'] != 'skipped'
                record['jobs'].append({'name': job['name'], 'result': job['conclusion'],
                    'started_at': job['started_at'], 'completed_at': job['completed_at'],
                    's': elapsed(job['started_at'], job['completed_at']) if active else 0,
                    'steps': [{'name': step['name'], 'result': step['conclusion'],
                        's': elapsed(step['started_at'], step['completed_at'])
                        if step['conclusion'] != 'skipped' and step.get('started_at') and step.get('completed_at') else 0}
                        for step in job.get('steps', [])]})
            result['runs'].append(record)
        result['merge_ready_s'] = max(record['gate_s'] for record in result['runs'])
        if result['event'] == 'push':
            app_image = next(job for job in jobs_by_run[pair[QUALITY]['id']]
                             if job['name'] == 'Publish immutable application image')
            result['main_to_application_publication_s'] = elapsed(start, app_image['completed_at'])
            result['main_to_release_ci_s'] = max(elapsed(start, run['updated_at']) for run in pair.values())
        result['ci_runner_s'] = sum(job['s'] for run in result['runs'] for job in run['jobs'])
        promotion = matching_promotions.get(result['sha']) if result['event'] == 'push' else None
        if promotion:
            dev_jobs = jobs_by_run[promotion['id']]
            verify = next(job for job in dev_jobs if job['name'] == 'Derive and verify exact dispatch artifact')
            deploy = next(job for job in dev_jobs if job['name'] == 'Owner-approved private-dev application promotion')
            host = next(step for step in deploy['steps']
                        if step['name'] == 'Install the revision on the private host and deploy it')
            result['private_dev'] = {
                'url': promotion['html_url'],
                'main_to_dispatch_s': elapsed(start, promotion['created_at']),
                'dispatch_initial_wait_s': elapsed(promotion['created_at'], verify['started_at']),
                'dispatch_to_verified_s': elapsed(promotion['created_at'], verify['completed_at']),
                'host_and_smoke_s': elapsed(host['started_at'], host['completed_at']),
                'main_to_private_dev_ready_s': elapsed(start, deploy['completed_at']),
                'runner_s': sum(elapsed(job['started_at'], job['completed_at']) for job in (verify, deploy)),
            }
        report.append(result)
    (directory / 'runs.json').write_text(json.dumps(runs, indent=2) + '\n')
    (directory / 'baseline.json').write_text(json.dumps(report, indent=2) + '\n')
    for event in ('pull_request', 'push'):
        values = sorted(result['merge_ready_s'] for result in report if result['event'] == event)
        if values:
            print(f'{event}: n={len(values)}, median={statistics.median(values)}s, '
                  f'p90={values[math.ceil(len(values) * .9) - 1]}s (nearest rank)')
    for label, values in (
        ('main to application publication', [r['main_to_application_publication_s'] for r in report if r['event'] == 'push']),
        ('main to complete release CI', [r['main_to_release_ci_s'] for r in report if r['event'] == 'push']),
        ('main to private-dev ready', [r['private_dev']['main_to_private_dev_ready_s'] for r in report if 'private_dev' in r]),
    ):
        if values:
            print(f'{label}: n={len(values)}, median={statistics.median(values)}s')
    print(f'Evidence: {directory / "baseline.json"}')


if __name__ == '__main__':
    main()
