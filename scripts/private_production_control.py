"""Bounded cancellation, ownership and evidence primitives for private bootstrap."""
import contextlib
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
import time
import select
import uuid

import private_dev_promotion as trust

CANCEL = threading.Event()
CAUSE = None
UNCERTAIN_COMMAND = False
LOCAL = threading.local()
RUN_LABEL = 'gameometro.installation.run'
CONFIG_LABEL = 'gameometro.installation.configuration'
OWNER = 'INSTALLATION-OWNER.json'
FENCE = r'''
import json,os,pathlib,sys
p=pathlib.Path('/gate');run,configuration=sys.argv[1:]
owner=json.loads((p/'INSTALLATION-OWNER.json').read_text())
assert owner['runId']==run and owner['configuration']==configuration
assert not (p/'IDENTITY-MAINTENANCE.json').exists()
pending=p/'PENDING.json'
if pending.exists():
 v=json.loads(pending.read_text());assert v.get('runId')==run and v.get('kind')=='private-first-install-interrupted'
with (p/'fence.tmp').open('w') as f:
 json.dump({'kind':'private-first-install-interrupted','runId':run},f);f.flush();os.fsync(f.fileno())
os.replace(p/'fence.tmp',pending);(p/'READY').unlink(missing_ok=True)
fd=os.open(p,os.O_DIRECTORY);os.fsync(fd);os.close(fd)
'''
SUPPORTED = {('29.8.2', '5.6.0'): 'local-disposable-mechanisms',
             ('29.9.2', '5.6.1'): 'target-qualification-pending'}
require = trust.require


def cancel(reason):
    global CAUSE
    CAUSE = CAUSE or reason
    CANCEL.set()


@contextlib.contextmanager
def cleanup_window(timeout=90):
    previous = getattr(LOCAL, 'cleanup', False)
    deadline = getattr(LOCAL, 'deadline', None)
    LOCAL.cleanup = True
    LOCAL.deadline = min(deadline or float('inf'), time.monotonic() + timeout)
    try:
        yield
    finally:
        LOCAL.cleanup = previous
        LOCAL.deadline = deadline


def remaining(timeout):
    deadline = getattr(LOCAL, 'deadline', None)
    value = min(timeout, deadline-time.monotonic()) if deadline is not None else timeout
    require(value > 0, 'Cleanup command budget exhausted; state is inconclusive.')
    return value


def signal_handlers():
    previous = {}
    for name in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        previous[name] = signal.signal(name, lambda number, _: cancel(signal.Signals(number).name))
    return previous


def watch_session(fd=0):
    """Detect observable SSH/PTY teardown, not an unknowable physical link failure."""
    stopped = threading.Event()
    def observe():
        poll = select.poll()
        try:
            poll.register(fd, select.POLLIN | select.POLLHUP | select.POLLERR)
            while not stopped.is_set():
                for _, events in poll.poll(200):
                    if events & (select.POLLHUP | select.POLLERR):
                        cancel('session-closed'); return
                    if events & select.POLLIN and not os.read(fd, 1):
                        cancel('session-eof'); return
        except (OSError, ValueError):
            cancel('session-observation-unavailable')
    thread = threading.Thread(target=observe, daemon=True)
    thread.start()
    return stopped


def run(args, *, data=None, timeout=120, output=True, env=None):
    global UNCERTAIN_COMMAND
    if not getattr(LOCAL, 'cleanup', False):
        require(not CANCEL.is_set(), 'Operation cancelled; controlled cleanup required.')
    timeout = remaining(timeout)
    process = subprocess.Popen([str(a) for a in args], stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                               stdout=subprocess.PIPE if output else subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, env=env, start_new_session=True)
    deadline = time.monotonic() + timeout
    first = True
    try:
        while True:
            try:
                value, _ = process.communicate(input=data if first else None, timeout=0.2)
                require(process.returncode == 0, 'Host prerequisite or operation failed; diagnostics withheld.')
                require(len(value or b'') <= 2 * 1024 * 1024, 'Host metadata exceeds its bound.')
                return value or b''
            except subprocess.TimeoutExpired:
                first = False
            if time.monotonic() >= deadline or (CANCEL.is_set() and not getattr(LOCAL, 'cleanup', False)):
                UNCERTAIN_COMMAND = True
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.communicate(timeout=2)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.communicate(timeout=2)
                raise trust.Refused('Command interrupted or timed out; daemon work may remain in flight.')
    finally:
        if process.poll() is None:
            process.kill(); process.wait(timeout=2)


def durable(path, value):
    """Unique temporary names; retain incomplete writes for forensic reconciliation."""
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.pending')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(json.dumps(value, sort_keys=True, indent=2) + '\n')
        stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, path)
    fsync_dir(path.parent)


def fsync_dir(path):
    fd = os.open(path, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def versions(engine, compose):
    pair = (engine, compose.removeprefix('v'))
    require(pair in SUPPORTED, 'Unreviewed Docker/Compose version tuple.')
    return {'engine': pair[0], 'compose': pair[1], 'qualification': SUPPORTED[pair]}


def daemon_policy(argv, config, info):
    """Small allowlist, not a generic dockerd configuration interpreter."""
    require(argv and Path(argv[0]).name == 'dockerd', 'Unknown Docker process.')
    names = {'hosts', 'containerd', 'config-file', 'log-driver', 'log-opts', 'data-root',
             'iptables', 'ip6tables', 'allow-direct-routing', 'userns-remap', 'live-restore',
             'firewall-backend', 'default-network-opts', 'ipv6', 'ip-masq', 'icc',
             'userland-proxy', 'experimental', 'features'}
    require(set(config) <= names - {'config-file'}, 'Unknown relevant daemon configuration.')
    flags = {}
    index = 1
    while index < len(argv):
        arg = argv[index]
        require(arg.startswith('-'), 'Unparsed Docker argument.')
        key, separator, value = arg.partition('=')
        key = 'hosts' if key == '-H' else key.removeprefix('--')
        require(key in names, 'Unreviewed Docker argument.')
        if not separator:
            if index + 1 < len(argv) and not argv[index + 1].startswith('-'):
                index += 1; value = argv[index]
            else:
                value = 'true'
        require(key not in flags, 'Repeated Docker argument.')
        flags[key] = value
        index += 1
    require(not set(flags).intersection(config), 'Ambiguous CLI/file daemon configuration.')
    effective = {**config, **flags}
    def boolean(key, default):
        value = effective.get(key, default)
        require(value in (True, False, 'true', 'false'), 'Unknown Docker boolean.')
        return value in (True, 'true')
    require(boolean('iptables', True) and boolean('ip6tables', True)
            and boolean('ip-masq', True) and boolean('icc', True)
            and not boolean('allow-direct-routing', False) and not boolean('live-restore', False)
            and not boolean('experimental', False) and not boolean('ipv6', False), 'Unsupported Docker isolation settings.')
    require(effective.get('firewall-backend', 'iptables') == 'iptables'
            and not effective.get('userns-remap') and not info.get('LiveRestoreEnabled'), 'Unknown effective firewall/namespace model.')
    require(not effective.get('features'), 'Unreviewed experimental daemon features.')
    require(effective.get('containerd', '/run/containerd/containerd.sock') == '/run/containerd/containerd.sock', 'Unknown container runtime endpoint.')
    require(boolean('userland-proxy', True) in (True, False), 'Unknown userland proxy configuration.')
    require(not info.get('Plugins', {}).get('Authorization'), 'Unreviewed Docker authorization plugin.')
    require(info.get('Swarm', {}).get('LocalNodeState', 'inactive') == 'inactive', 'Swarm is outside this installation.')
    if 'LoggingDriver' in info:
        require(effective.get('log-driver', 'json-file') == info['LoggingDriver']
                and info['LoggingDriver'] in ('local', 'json-file'), 'Effective logging differs from the reviewed file/arguments.')
    if 'data-root' in effective: require(effective['data-root'] == info.get('DockerRootDir'), 'Effective data root differs.')
    require(isinstance(effective.get('log-opts', {}), dict)
            and set(effective.get('log-opts', {})) <= {'max-size', 'max-file', 'mode', 'max-buffer-size', 'compress'},
            'Unknown daemon log options.')
    require(effective.get('config-file', '/etc/docker/daemon.json') == '/etc/docker/daemon.json', 'Unknown config source.')
    hosts = effective.get('hosts', ['unix:///var/run/docker.sock'])
    if isinstance(hosts, str): hosts = [hosts]
    require(hosts and all(h == 'fd://' or h.startswith('unix:///') for h in hosts), 'Non-Unix Docker administration.')
    network_defaults = effective.get('default-network-opts', {})
    require(isinstance(network_defaults, dict) and set(network_defaults) <= {'bridge'}, 'Unknown network driver defaults.')
    validate_network_options(network_defaults.get('bridge', {}), require_nat=False)
    return {'recognizedConfigurationKeys': sorted(effective), 'filtering': 'enabled',
            'firewallBackend': 'iptables', 'directRouting': False, 'liveRestore': False}


def validate_network_options(options, *, require_nat=True):
    allowed = {'com.docker.network.bridge.gateway_mode_ipv4': 'nat',
               'com.docker.network.enable_ipv4': 'true',
               'com.docker.network.enable_ipv6': 'false',
               'com.docker.network.bridge.gateway_mode_ipv6': 'nat',
               'com.docker.network.bridge.enable_ip_masquerade': 'true',
               'com.docker.network.bridge.enable_icc': 'true'}
    require(set(options) <= set(allowed) and all(options[k] == allowed[k] for k in options),
            'Unknown or unsafe bridge option, including trusted host interfaces.')
    require(not require_nat or options.get('com.docker.network.bridge.gateway_mode_ipv4') == 'nat', 'Protected NAT not explicit.')


def validate_ipam(actual, intended):
    require(set(actual) <= {'Driver', 'Options', 'Config'} and actual.get('Driver') == 'default'
            and not actual.get('Options'), 'Unknown effective IPAM policy.')
    entries = actual.get('Config', [])
    require(len(entries) == 1 and set(entries[0]) <= {'Subnet', 'Gateway', 'IPRange'}
            and ipaddress.ip_network(entries[0]['Subnet']).version == 4
            and ipaddress.ip_address(entries[0]['Gateway']) in ipaddress.ip_network(entries[0]['Subnet']),
            'Unknown IPAM allocation or gateway.')
    expected = intended.get('config', [])
    if expected:
        require(len(expected) == 1 and set(expected[0]) <= {'subnet', 'gateway', 'ip_range'}
                and entries == [{name: expected[0][key] for key, name in
                    (('subnet', 'Subnet'), ('gateway', 'Gateway'), ('ip_range', 'IPRange')) if key in expected[0]}],
                'Effective IPAM differs from the fixed proxy trust topology.')
    else:
        require(not entries[0].get('IPRange'), 'Unreviewed dynamic IPAM range.')


def owned_containers(run_id, configuration, project, services, runner=run):
    ids = runner(['docker', 'ps', '-aq', '--filter', f'label={RUN_LABEL}={run_id}']).decode().split()
    require(len(ids) <= 32, 'Attempt container inventory exceeds bound.')
    result = []
    for cid in ids:
        require(re.fullmatch('[0-9a-f]{12,64}', cid), 'Invalid container identity.')
        item = json.loads(runner(['docker', 'inspect', cid]))[0]
        labels = item['Config'].get('Labels') or {}
        require(labels.get(RUN_LABEL) == run_id and labels.get(CONFIG_LABEL) == configuration
                and labels.get('com.docker.compose.project') == project
                and labels.get('com.docker.compose.service') in services,
                'Container ownership ambiguous; no stop authorized.')
        result.append({'id': item['Id'], 'service': labels['com.docker.compose.service'],
                       'running': bool(item['State']['Running'])})
    return result


def stop_owned(run_id, configuration, project, services, lease_check, *, runner=run, budget=60):
    """Only exact IDs, under an exclusive live lease. Never project-wide stop."""
    deadline = time.monotonic() + budget
    with cleanup_window():
        lease_check()
        before = owned_containers(run_id, configuration, project, services, runner)
        active = [v['id'] for v in before if v['running']]
        if active:
            lease_check()
            runner(['docker', 'stop', '--time', '5', *active], timeout=max(1, min(20, deadline-time.monotonic())), output=False)
        lease_check()
        remaining = owned_containers(run_id, configuration, project, services, runner)
        active = [v['id'] for v in remaining if v['running']]
        if active:
            lease_check()
            runner(['docker', 'kill', '--signal', 'KILL', *active], timeout=max(1, min(10, deadline-time.monotonic())), output=False)
        lease_check()
        after = owned_containers(run_id, configuration, project, services, runner)
        require(time.monotonic() <= deadline and not any(v['running'] for v in after), 'Owned shutdown not confirmed within budget.')
        return {'status': 'inconclusive' if UNCERTAIN_COMMAND else 'confirmed',
                'scope': 'exact-attempt-container-ids', 'containers': after,
                'reason': 'interrupted-daemon-request-may-finish-late' if UNCERTAIN_COMMAND else None}
