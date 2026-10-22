#!/usr/bin/env python3
"""Bounded host-only textfile sampler. No listener, remote Docker, logs or secrets.

Standard node_exporter owns host resources. This bridge adds only fixed-service
cgroup v2, Docker lifecycle, protected recovery evidence and calibration signals.
The host's existing Docker administrator runs it; no container gets Docker access.
"""
import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import time

SERVICES = ('caddy', 'application', 'postgres', 'keycloak', 'catalogue-localizer',
            'telemetry', 'prometheus', 'grafana', 'node-exporter', 'blackbox')


def protected_json(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o022 or path.stat().st_uid not in (0, os.geteuid()) or path.stat().st_size > 16384:
        raise ValueError('Unsafe evidence')
    return json.loads(path.read_text())


def timestamp(value):
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('Timezone required')
    return result.timestamp()


def atomic(path, content):
    path = Path(path)
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
            temporary.chmod(0o644)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def cgroup_values(directory):
    def fields(name):
        return dict(line.split() for line in (directory / name).read_text().splitlines())
    cpu, memory = fields('cpu.stat'), fields('memory.events')
    limit = (directory / 'memory.max').read_text().strip()
    quota, period = (directory / 'cpu.max').read_text().split()
    if limit == 'max' or quota == 'max':
        raise ValueError('Expected bounded cgroup v2')
    return {'cpu_seconds_total': int(cpu['usage_usec']) / 1e6,
            'cpu_throttled_periods_total': int(cpu.get('nr_throttled', 0)),
            'cpu_periods_total': int(cpu.get('nr_periods', 0)),
            'cpu_limit_cores': int(quota) / int(period),
            'memory_bytes': int((directory / 'memory.current').read_text()),
            'memory_limit_bytes': int(limit), 'oom_total': int(memory['oom_kill'])}


def command(args, max_bytes=16384):
    # Refuse remote contexts even if inherited from an operator's shell. Stream only
    # the bounded result; capture_output would allocate the complete Docker log tail.
    env = {**os.environ, 'DOCKER_HOST': 'unix:///var/run/docker.sock', 'DOCKER_CONTEXT': ''}
    actor=subprocess.Popen(['timeout','--kill-after=1','5','docker','--host','unix:///var/run/docker.sock',*args],
                           env=env,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    try:
        result=actor.stdout.read(max_bytes+1)
        if len(result)>max_bytes:
            raise ValueError('Observation exceeds byte budget')
        if actor.wait(timeout=6):
            raise ValueError('Docker observation failed')
        return result.decode()
    finally:
        if actor.poll() is None:actor.kill()
        actor.wait();actor.stdout.close()


def snapshot(project):
    ids = command(['ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project]).split()
    if len(ids) > 24:
        raise ValueError('Container inventory exceeds bound')
    if not ids:
        return []
    # Never ask Docker for the full inspect object: it can include secret environment values.
    template = ('{"service":{{json (index .Config.Labels "com.docker.compose.service")}},'
                '"id":{{json .Id}},"pid":{{.State.Pid}},"running":{{.State.Running}},'
                '"oom":{{.State.OOMKilled}},"restarts":{{.RestartCount}},'
                '"started":{{json .State.StartedAt}}}')
    return [json.loads(line) for line in command(['inspect', '--format', template, *ids]).splitlines()]


def render(containers, offhost=None, backup=None, capacity=None, now=None, cgroup_root=Path('/sys/fs/cgroup')):
    now = time.time() if now is None else now
    lines = []
    def emit(name, value, service=None):
        if not math.isfinite(float(value)):
            raise ValueError('Non-finite metric')
        label = '{service="' + service + '"}' if service else ''
        lines.append(f'production_{name}{label} {float(value):.17g}')
    emit('host_sample_timestamp_seconds', now)
    by_service = {}
    for item in containers:
        if item['service'] not in SERVICES:
            continue
        if item['service'] in by_service:
            raise ValueError('Duplicate steady service')
        by_service[item['service']] = item
    for service in SERVICES:
        item = by_service.get(service)
        emit('container_running', int(bool(item and item['running'])), service)
        if not item:
            continue
        emit('container_restarts_total', item['restarts'], service)
        emit('container_oom_killed', int(item['oom']), service)
        emit('container_started_seconds', timestamp(item['started']), service)
        observed = False
        if item['running']:
            try:
                entry = next(x[3:] for x in Path(f'/proc/{item["pid"]}/cgroup').read_text().splitlines() if x.startswith('0::'))
                directory = (cgroup_root / entry.lstrip('/')).resolve()
                if not directory.is_relative_to(cgroup_root.resolve()):
                    raise ValueError('Invalid cgroup')
                for name, value in cgroup_values(directory).items():
                    emit('container_' + name, value, service)
                observed = True
            except (OSError, ValueError, StopIteration, KeyError):
                pass
        emit('container_resources_observed', int(observed), service)
    # A local copy or checksum-only receipt never qualifies as verified off-host recovery.
    verified = False
    if offhost:
        try:
            verified = (offhost['kind'] == 'offhost-verification' and offhost['environment'] == 'production'
                        and all(offhost[x] is True for x in ('ciphertextIntegrity', 'storageIsolationVerified', 'independentRecoveryVerified'))
                        and 0 <= now - timestamp(offhost['verifiedAt']) <= 35 * 86400
                        and 0 <= now - timestamp(offhost['recoveryPoint']) <= 35 * 86400)
        except (KeyError, TypeError, ValueError):
            pass
    emit('offhost_verified', int(verified))
    if verified:
        emit('offhost_recovery_point_seconds', timestamp(offhost['recoveryPoint']))
        emit('offhost_verification_seconds', timestamp(offhost['verifiedAt']))
    emit('backup_attempt_observed', int(bool(backup)))
    if backup:
        emit('backup_last_attempt_failed', int(backup.get('outcome') != 'success' or backup.get('kind') not in ('paired-backup', 'backup')))
    calibrated = False
    if capacity:
        try:
            calibrated = (capacity['scope'] == 'real-vps' and capacity['environment'] == 'production'
                          and 0 <= now - timestamp(capacity['measuredAt']) <= 30 * 86400
                          and now < timestamp(capacity['expiresAt']) and 0 < float(capacity['sustainableRps']) < 1e9)
        except (KeyError, TypeError, ValueError):
            pass
    emit('capacity_calibrated', int(calibrated))
    if calibrated:
        emit('capacity_rps', capacity['sustainableRps'])
    return '\n'.join(lines) + '\n'


def edge_observations(text, now=None):
    """Fixed five-minute window, bounded at input; no durable counter/cursor needed.

    Docker logs may contain runtime messages too. Only the safe access schema counts;
    truncation or a malformed access record makes the whole observation unknown.
    """
    now=time.time() if now is None else now
    counts={(origin,status):0 for origin in ('maintenance','overload','quota','upstream') for status in ('429','500','502','503','504')}
    complete=len(text.encode()) < 4*1024*1024 and len(text.splitlines()) < 20000
    records=0
    allowed={'level','ts','logger','msg','bytes_read','duration','size','status','response_origin'}
    for line in text.splitlines():
        try: item=json.loads(line)
        except ValueError: continue
        if not str(item.get('logger','')).startswith('http.log.access'):continue
        # A Caddy upgrade that adds a field must be reviewed before enabling this bridge.
        if set(item)-allowed or item.get('response_origin','upstream') not in ('maintenance','overload','quota','upstream'):
            complete=False;continue
        if not 0 <= now-float(item.get('ts',0)) <= 300:continue
        records+=1
        origin,status=item.get('response_origin','upstream'),str(item.get('status'))
        if (origin,status) in counts:counts[origin,status]+=1
    lines=[f'production_edge_observation_complete {int(complete)}']
    if complete:
        lines.append(f'production_edge_access_records_5m {records}')
        lines += [f'production_edge_responses_5m{{origin="{o}",status="{s}"}} {n}' for (o,s),n in counts.items()]
    return '\n'.join(lines)+'\n'


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--project', default='gameometro-production', choices=['gameometro-production'])
    parser.add_argument('--offhost-evidence', type=Path)
    parser.add_argument('--backup-receipt', type=Path)
    parser.add_argument('--capacity-evidence', type=Path)
    args = parser.parse_args()
    def evidence(path):
        try:
            return protected_json(path) if path else None
        except (OSError, ValueError):
            return None
    try:
        containers=snapshot(args.project)
        content = render(containers, evidence(args.offhost_evidence),
                         evidence(args.backup_receipt), evidence(args.capacity_evidence))
        try:
            logs=command(['logs','--since','5m','--tail','20000',next(item['id'] for item in containers if item['service']=='caddy')],max_bytes=4*1024*1024)
            # subprocess stdout is bounded by the container log ceiling, but reject a
            # bridge window above 4 MiB rather than treating truncation as zero.
            content += edge_observations(logs)
        except Exception:
            content += 'production_edge_observation_complete 0\n'
        atomic(args.output, content)
    except Exception:
        # Preserve the last atomic sample. Its timestamp will become stale and alert.
        raise SystemExit('Host telemetry sample failed; values withheld.') from None


if __name__ == '__main__':
    main()
