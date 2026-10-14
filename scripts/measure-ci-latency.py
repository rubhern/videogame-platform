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

QUALITY = 'Walking-skeleton build and verify'
SECURITY = 'Security'


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path]))


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
    for run in runs:
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

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        jobs_by_run = dict(executor.map(fetch, [run for pair in selected for run in pair.values()]))
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
        report.append(result)
    (directory / 'runs.json').write_text(json.dumps(runs, indent=2) + '\n')
    (directory / 'baseline.json').write_text(json.dumps(report, indent=2) + '\n')
    for event in ('pull_request', 'push'):
        values = sorted(result['merge_ready_s'] for result in report if result['event'] == event)
        if values:
            print(f'{event}: n={len(values)}, median={statistics.median(values)}s, '
                  f'p90={values[math.ceil(len(values) * .9) - 1]}s (nearest rank)')
    print(f'Evidence: {directory / "baseline.json"}')


if __name__ == '__main__':
    main()
