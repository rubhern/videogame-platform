#!/usr/bin/env python3
"""One fresh, private #276 bootstrap. Default: read-only preflight. No updates/reset."""
import sys
sys.dont_write_bytecode = True

import argparse
import contextlib
import fcntl
import hashlib
import importlib.machinery
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import ssl
import stat
import subprocess
import time
import uuid
import copy
import threading
import socket
import array
import tempfile
import struct

import private_production_control as control
import prepare_private_production as prepare
import private_dev_promotion as trust
import public_production_deployment as deployment
import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
ADMIN = Path('/var/lib/gameometro/production')
JOURNAL = ADMIN / 'first-install'
TLS = Path('/etc/gameometro/production/private-tls')
HELPER = 'python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258'
OPERATIONAL_FILES = ('scripts/private_production_control.py', 'scripts/validate_private_production_truststore.py', 'scripts/install_private_production.py', 'scripts/prepare_private_production.py',
                     'scripts/private_dev_promotion.py', 'scripts/public_production_deployment.py',
                     'scripts/public_production_integration.py')
require = trust.require


run = control.run


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def secure(path, *, mode=None, directory=False, gid=None):
    require(path.is_absolute() and path.resolve() == path, 'Symlinked or relative protected path.')
    info = path.lstat()
    require((stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))
            and info.st_uid == 0 and not info.st_mode & 0o022, 'Unsafe protected owner/type/permissions.')
    require(mode is None or stat.S_IMODE(info.st_mode) == mode, 'Unexpected protected mode.')
    require(gid is None or info.st_gid == gid, 'Unexpected secret group.')
    for parent in path.parents:
        ancestor = parent.lstat()
        require(ancestor.st_uid == 0 and not ancestor.st_mode & 0o022, 'Writable/untrusted protected-path ancestor.')
    require('system.posix_acl_access' not in os.listxattr(path), 'Unexpected protected-file ACL.')
    return info


def operational_fingerprint(root=ROOT):
    entries = []
    for name in OPERATIONAL_FILES:
        path = root / name
        require(path.is_file() and not path.is_symlink(), 'Missing operational source.')
        data = path.read_bytes()
        blob = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()
        entries.append(f'100644 blob {blob}\t{name}'.encode())
        require(not path.stat().st_mode & 0o111, 'Unexpected operational source mode.')
    return trust.contract_hash(entries)


def verify_operational(api, revision, expected):
    require(re.fullmatch('[0-9a-f]{40}', revision or '')
            and re.fullmatch('[0-9a-f]{64}', expected or ''), 'Exact reviewed operational identities required.')
    # This separately published rollout binds new operator code to the old accepted
    # images. It does not grant the old runtime fingerprint to the new adapter.
    selected = trust.discover_promotion(api, revision)
    tree = api.request(f'/git/trees/{revision}?recursive=1')
    expected_tree = deployment.inventory(tree, lambda p: p in OPERATIONAL_FILES)
    require(expected_tree == operational_fingerprint() == expected,
            'Operational code differs from reviewed published rollout.')
    return selected


def verify_export(source, tree):
    require(not tree.get('truncated', True) and len(tree['tree']) <= 10000, 'Incomplete candidate tree.')
    expected = {e['path']: e for e in tree['tree'] if e['type'] != 'tree'
                and e['path'].split('/')[0] in ('deploy', 'docker', 'scripts', 'tools')}
    found = set()
    for parent, dirs, files in os.walk(source, followlinks=False):
        secure(Path(parent), directory=True)
        require(not any((Path(parent) / d).is_symlink() for d in dirs), 'Symlinked candidate directory.')
        for name in files:
            path = Path(parent) / name
            relative = path.relative_to(source).as_posix()
            entry = expected.get(relative)
            require(entry is not None and entry['type'] == 'blob' and entry['mode'] in ('100644', '100755'),
                    'Unexpected candidate source.')
            secure(path)
            data = path.read_bytes()
            require(hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest() == entry['sha']
                    and bool(path.stat().st_mode & 0o111) == (entry['mode'] == '100755'),
                    'Candidate file bytes or executable mode changed.')
            found.add(relative)
    require(found == set(expected), 'Incomplete candidate export.')


durable = control.durable


def daemon_flags(argv):
    return control.daemon_policy(argv, {}, {})


# The accepted recovery lock/gate protocol is retained. This bootstrap lease also
# closes the gate on lost stdin/TERM, before releasing flock. A failed claim never
# touches someone else's READY/PENDING/maintenance marker. No new long-lived service.
BOOTSTRAP_LEASE = r'''
import array,fcntl,json,os,pathlib,signal,socket,sys
p=pathlib.Path('/gate');run,configuration,mode,channel=sys.argv[1:]
def interrupted(*_): raise SystemExit(1)
for sig in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP):signal.signal(sig,interrupted)
def write(name,value):
 with (p/(name+'.tmp')).open('w') as f:json.dump(value,f);f.flush();os.fsync(f.fileno())
 os.replace(p/(name+'.tmp'),p/name)
 fd=os.open(p,os.O_DIRECTORY);os.fsync(fd);os.close(fd)
with (p/'ACTOR.lock').open('a') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 if mode=='initial':
  if set(x.name for x in p.iterdir())!={'ACTOR.lock'}:raise SystemExit(1)
  owner={'runId':run,'configuration':configuration,'status':'claimed'}
  write('INSTALLATION-OWNER.json',owner)
 else:
  owner=json.loads((p/'INSTALLATION-OWNER.json').read_text())
  assert owner['runId']==run and owner['configuration']==configuration
  assert not (p/'IDENTITY-MAINTENANCE.json').exists()
  if (p/'PENDING.json').exists():
   pending=json.loads((p/'PENDING.json').read_text())
   assert pending.get('runId')==run and pending.get('kind')=='private-first-install-interrupted'
 committed=False
 try:
  handoff=socket.socket(socket.AF_UNIX);handoff.connect(channel)
  handoff.sendmsg([json.dumps({'runId':run,'configuration':configuration}).encode()],
    [(socket.SOL_SOCKET,socket.SCM_RIGHTS,array.array('i',[lock.fileno()]))]);handoff.close()
  print('locked',flush=True)
  if mode=='initial' and sys.stdin.readline()=='COMMIT\n':
   owner['status']='commit-acknowledged';write('INSTALLATION-OWNER.json',owner)
   print('committed',flush=True)
   if sys.stdin.readline()=='RELEASE\n':
    owner['status']='released';write('INSTALLATION-OWNER.json',owner);committed=True
  elif mode!='initial':
   sys.stdin.readline()
 finally:
  if not committed:
   (p/'READY').unlink(missing_ok=True)
   write('PENDING.json',{'kind':'private-first-install-interrupted','runId':run})
   owner['status']='interrupted';write('INSTALLATION-OWNER.json',owner)
print('released' if committed else 'closed',flush=True)
'''


def check_kernel_lease(runtime):
    require(getattr(runtime, '_lease_active', False), 'Exclusive runtime lease is not held.')
    try:
        metadata = os.fstat(runtime._lease_fd)
    except (AttributeError, OSError, TypeError):
        raise RuntimeError('Exclusive kernel lease descriptor is unavailable.') from None
    require((metadata.st_dev, metadata.st_ino) == runtime._lease_identity,
            'Exclusive kernel lease descriptor changed.')


@contextlib.contextmanager
def bootstrap_lease(runtime, mode='initial'):
    channel_dir = tempfile.TemporaryDirectory(prefix='vgp-lease-', dir=str(runtime.state) if hasattr(runtime, 'state') else None)
    channel = Path(channel_dir.name) / 'lease.sock'
    server = socket.socket(socket.AF_UNIX)
    try:
        server.bind(str(channel)); server.listen(1)
        # Public CLI is root-only (0600). Non-root disposable fixtures need the
        # container's root UID to connect; their private temporary directory is 0700.
        channel.chmod(0o600 if os.geteuid() == 0 else 0o622)
    except BaseException:
        server.close(); channel_dir.cleanup()
        raise
    retained_fd = None
    try:
        actor = subprocess.Popen(['env', '--default-signal=INT,TERM', 'docker', 'run', '--rm', '-i',
                              '--log-driver', 'none',
                              '--label', 'gameometro.recovery-helper=true', '--network', 'none',
                              '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                              '--memory', '96m', '--pids-limit', '32', '-v', runtime.gate_volume + ':/gate',
                              '--mount', f'type=bind,source={channel},target=/lease.sock,readonly',
                              HELPER, 'python3', '-c', BOOTSTRAP_LEASE,
                              runtime.run_id, runtime.run_configuration, mode, '/lease.sock'],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, start_new_session=True)
    except BaseException:
        server.close(); channel_dir.cleanup()
        raise
    previous_actor = getattr(runtime, '_lease_actor', None)
    previous_active = getattr(runtime, '_lease_active', False)
    previous_fd = getattr(runtime, '_lease_fd', None)
    previous_identity = getattr(runtime, '_lease_identity', None)
    expected_exit = threading.Event()
    observer = None
    def line(expected):
        require(select.select([actor.stdout], [], [], 10)[0] and actor.stdout.readline(64) == expected,
                'Lease acknowledgement unavailable; completion is inconclusive.')
    try:
        require(select.select([server, actor.stdout], [], [], control.remaining(60 if mode == 'initial' else 10))[0], 'Lease descriptor handoff unavailable.')
        require(select.select([server], [], [], 0)[0], 'Actor refused lease before descriptor handoff.')
        peer, _ = server.accept()
        try:
            peer.settimeout(control.remaining(5))
            require(struct.unpack('3i', peer.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[1] == 0,
                    'Lease peer is not the rootful accepted helper.')
            data, ancillary, flags, _ = peer.recvmsg(2048, socket.CMSG_SPACE(4))
            descriptors = array.array('i')
            for level, kind, payload in ancillary:
                if (level, kind) == (socket.SOL_SOCKET, socket.SCM_RIGHTS): descriptors.frombytes(payload)
            if len(descriptors) != 1:
                for descriptor in descriptors: os.close(descriptor)
                raise RuntimeError('Missing or unexpected kernel lease descriptor count.')
            retained_fd = descriptors[0]; os.set_inheritable(retained_fd, False)
            require(not flags and json.loads(data) == {'runId': runtime.run_id, 'configuration': runtime.run_configuration},
                    'Lease descriptor identity differs.')
            metadata = os.fstat(retained_fd)
            require(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == 0, 'Invalid recovery lock descriptor.')
        finally:
            peer.close()
        require(select.select([actor.stdout], [], [], control.remaining(60 if mode == 'initial' else 10))[0] and actor.stdout.readline(32) == b'locked\n',
                'Bootstrap recovery lease unavailable, concurrent, or gate not empty.')
        runtime._lease_actor = actor
        runtime._lease_active = True
        runtime._lease_fd = retained_fd
        runtime._lease_identity = (metadata.st_dev, metadata.st_ino)
        def observe():
            while not expected_exit.wait(0.2):
                if actor.poll() is not None:
                    control.cancel('engine-lease-lost'); return
        observer = threading.Thread(target=observe, daemon=True)
        observer.start()
        yield
        runtime.check_lease()
        expected_exit.set()
        if observer: observer.join(timeout=1)
        if mode == 'initial':
            require(not control.CANCEL.is_set(), 'Cancelled before lease completion.')
            actor.stdin.write(b'COMMIT\n'); actor.stdin.flush(); line(b'committed\n')
            actor.stdin.write(b'RELEASE\n'); actor.stdin.flush(); line(b'released\n')
    finally:
        expected_exit.set()
        runtime._lease_actor, runtime._lease_active = previous_actor, previous_active
        runtime._lease_fd, runtime._lease_identity = previous_fd, previous_identity
        try:
            try: actor.stdin.close()
            except BrokenPipeError: pass
            try:
                code = actor.wait(timeout=10)
            except subprocess.TimeoutExpired:
                actor.terminate()
                try: code = actor.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    actor.kill(); code = actor.wait(timeout=5)
        finally:
            # Keep ownership until the helper has finished fencing. Never LOCK_UN:
            # that would unlock the shared open description in the helper too.
            if retained_fd is not None: os.close(retained_fd)
            actor.stdout.close()
            server.close(); channel_dir.cleanup()
    require(code == 0, 'Bootstrap supervisor failed; inspect the closed recovery gate.')


PROBE = r'''
import http.client,json,runpy,ssl
ca='/run/private-tls/ca.crt'
ctx=ssl.create_default_context(cafile=ca)
def get(host,path,context=ctx):
 c=http.client.HTTPSConnection(host,context=context,timeout=15)
 c.request('GET',path);r=c.getresponse();b=r.read(262145);c.close()
 assert len(b)<=262144
 return r.status,b
assert get('gameometro.com','/')[0]==200
s,b=get('gameometro.com','/api/v1/releases?page=1&pageSize=1')
assert s==200 or s==503 and json.loads(b).get('code')=='CATALOGUE_NOT_READY'
assert get('gameometro.com','/actuator/health')[0]==404
assert get('auth.gameometro.com','/admin/')[0]==404
assert get('auth.gameometro.com','/realms/master/.well-known/openid-configuration')[0]==404
try: get('auth.gameometro.com','/realms/gameometro/.well-known/openid-configuration',ssl.create_default_context())
except ssl.SSLCertVerificationError: pass
else: raise AssertionError('Private TLS was trusted without its CA')
runpy.run_path('/work/check-oidc-issuer-contract.py',run_name='__main__')
'''

class Host:
    def __init__(self, configuration, adapter, operational_revision, truststore_evidence=None, evidence_dir=None):
        self.configuration, self.adapter, self.operational_revision = configuration, adapter, operational_revision
        self.bundle = prepare.TARGET
        self.source = self.bundle / 'candidate'
        self.file = self.bundle / 'compose.private.json'
        self.source_file = self.file
        self.truststore_evidence = truststore_evidence
        self.evidence_dir = evidence_dir
        self.base = ['docker', 'compose', '-p', prepare.PROJECT, '-f', str(self.file), '--profile', '*']
        self.check = 'platform'

    def compose(self, *args, **kwargs):
        if hasattr(self, 'recovery'):
            self.recovery.check_lease()
        return run([*self.base, *args], **kwargs)

    def preflight(self, *, recovery=False):
        self.check = 'platform'
        if self.evidence_dir is not None: secure(self.evidence_dir, mode=0o700, directory=True)
        result = {'mode': 'host-read-only-preflight', 'productionReady': False, 'outcome': 'inconclusive',
                  'observedAtUnix': int(time.time()),
                  'checks': {'externalIpv4Ipv6Denial': 'inconclusive', 'hostLossRecovery': 'pending'}}
        try:
            result = self._preflight(recovery=recovery)
            result['outcome'] = 'passed-for-private-qualification-only'
            return result
        finally:
            result['lastCheck'] = self.check
            if hasattr(self, 'docker_evidence'): result['docker'] = self.docker_evidence
            if self.evidence_dir is not None:
                durable(self.evidence_dir / ('preflight-' + uuid.uuid4().hex + '.json'), result)

    def _preflight(self, *, recovery=False):
        require(os.geteuid() == 0 and sys.platform == 'linux', 'Local Linux root operation required.')
        release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
        require(release.get('ID', '').strip('"') == 'debian' and release.get('VERSION_ID', '').strip('"') == '13',
                'This first installation targets the reviewed Debian 13 host only.')
        require(not any(k.startswith('COMPOSE_') for k in os.environ), 'Ambient Compose overrides are forbidden.')
        integration.verify_local_docker()
        require(run(['timedatectl', 'show', '--property=NTPSynchronized', '--value']).strip() == b'yes',
                'Synchronized host clock required.')
        # Do not trigger socket activation while claiming a read-only preflight.
        run(['systemctl', 'is-active', '--quiet', 'docker.service'], output=False)
        info = json.loads(run(['docker', 'info', '--format', '{{json .}}']))
        self.docker_evidence = {'engine': info.get('ServerVersion', 'unknown'),
                                'compose': run(['docker', 'compose', 'version', '--short']).decode().strip()}
        self.docker_evidence.update(control.versions(self.docker_evidence['engine'], self.docker_evidence['compose']))
        require(info.get('OSType') == 'linux' and info.get('Architecture') in ('x86_64', 'amd64')
                and info.get('CgroupVersion') == '2', 'Unexpected Docker platform.')
        require(not any('rootless' in s or 'userns' in s for s in info.get('SecurityOptions', [])),
                'This reviewed secret-delivery model requires a rootful daemon without user remapping.')
        self.docker_root = Path(info['DockerRootDir'])
        pid = run(['systemctl', 'show', 'docker.service', '--property=MainPID', '--value']).decode().strip()
        require(re.fullmatch('[1-9][0-9]{0,9}', pid), 'Docker service PID unavailable.')
        listeners = run(['ss', '-H', '-lntp']).decode()
        require(f'pid={pid},' not in listeners, 'Docker administration must not listen on TCP.')
        command_line = Path('/proc') / pid / 'cmdline'
        with command_line.open('rb') as stream:
            raw = stream.read(65537)
        require(len(raw) <= 65536, 'Docker process arguments exceed the supported bound.')
        daemon = Path('/etc/docker/daemon.json')
        options = {}
        if daemon.exists():
            metadata = secure(daemon)
            require(metadata.st_size <= 262144, 'Daemon configuration exceeds its bound.')
            process = (Path('/proc') / pid / 'stat').read_text().rsplit(')', 1)[1].split()
            started = time.time() - (time.monotonic() - int(process[19]) / os.sysconf('SC_CLK_TCK'))
            require(metadata.st_mtime < started - 2, 'Daemon configuration freshness is inconclusive; controlled restart qualification required.')
            options = json.loads(daemon.read_text())
            self.docker_evidence['configurationSha256'] = sha(daemon)
        self.check = 'docker-effective-policy'
        self.docker_evidence['observedConfigKeys'] = sorted(options)
        self.docker_evidence['observedArgumentNames'] = [a.decode().split('=', 1)[0] for a in raw.split(b'\0') if a.startswith(b'-')]
        self.docker_evidence['effectivePolicy'] = control.daemon_policy([a.decode() for a in raw.split(b'\0') if a], options, info)
        self.check = 'protected-paths'
        secure(ADMIN, mode=0o700, directory=True)
        secure(self.bundle, mode=0o700, directory=True)
        secure(ROOT, directory=True)
        for name in OPERATIONAL_FILES:
            secure(ROOT / name)
        if not recovery:
            require(not JOURNAL.exists() and not JOURNAL.is_symlink()
                    and not (ADMIN / 'first-install-intent.json').exists(), 'Previous claim exists; inspect/reconcile instead of retry.')
        for name in ('runtime.env', 'compose.candidate.json', 'compose.private.json', 'preparation.json'):
            secure(self.bundle / name, mode=0o600)
        secure(self.bundle / 'Caddyfile.private', mode=0o644)
        require(re.fullmatch('[0-9a-f]{64}', self.configuration or '') and sha(self.file) == self.configuration,
                'Reviewed private configuration hash differs.')
        api = trust.GitHub(run(['gh', 'auth', 'token']).decode().strip())
        self.check = 'operational-trust'
        operational = verify_operational(api, self.operational_revision, self.adapter)
        self.check = 'candidate-trust'
        selected = integration.resolve(api, prepare.REVISION)
        tree = api.request(f'/git/trees/{prepare.REVISION}?recursive=1')
        verify_export(self.source, tree)
        previous = deployment.ROOT
        try:
            deployment.ROOT = self.source
            self.schema = deployment.verify_configuration(api, prepare.REVISION, prepare.FINGERPRINT)
        finally:
            deployment.ROOT = previous
        receipt = json.loads((self.bundle / 'preparation.json').read_text())
        self.check = 'configuration'
        require(receipt['sourceRevision'] == prepare.REVISION
                and receipt['candidateRuntimeFingerprint'] == prepare.FINGERPRINT
                and receipt['adapterSha256'] == sha(ROOT / 'scripts/prepare_private_production.py')
                and receipt['privateCaddySha256'] == sha(self.bundle / 'Caddyfile.private')
                and receipt['configurationSha256'] == self.configuration,
                'Preparation receipt no longer matches the bundle.')
        clean_env = {k: os.environ[k] for k in ('PATH', 'HOME', 'DOCKER_CONFIG') if k in os.environ}
        base = json.loads(run(['docker', 'compose', '--env-file', self.bundle / 'runtime.env',
                               '-f', self.source / 'deploy/public-production/compose.yaml',
                               '-f', self.source / 'deploy/public-production/compose.observability.yaml',
                               '--profile', '*', 'config', '--format', 'json'], env=clean_env))
        self.config = json.loads(self.file.read_text())
        require(base == json.loads((self.bundle / 'compose.candidate.json').read_text()), 'Candidate rendering changed.')
        prepare.validate(base, self.config, self.bundle / 'Caddyfile.private', TLS)
        require((self.bundle / 'Caddyfile.private').read_text() == prepare.private_caddy(
            (self.source / 'deploy/public-production/caddy/Caddyfile').read_text()), 'Private TLS edge delta changed.')
        services = self.config['services']
        require({n: services[n]['image'] for n in selected['images']} == selected['images'], 'Candidate digests changed.')
        require(services['application']['environment']['TELEMETRY_SERVICE_VERSION'] == prepare.VERSION,
                'Unexpected candidate version.')
        require(services['application']['environment']['CATALOGUE_SYNC_SCHEDULING_ENABLED'] == 'false'
                and services['grafana']['environment']['GRAFANA_TELEGRAM_ENABLED'] == 'false', 'Background work/notifications must stay off.')
        images = {n: s['image'] for n, s in services.items()}
        images['recovery-helper'] = HELPER
        self.check = 'registry'
        self.images = prepare.registry_images(images, runner=run)
        self.check = 'inputs'
        self.inputs(recovery=recovery)
        self.check = 'capacity-headroom'
        self.headroom()
        self.check = 'empty-resources'
        if not recovery: self.fresh()
        self.check = 'subnets'
        self.routes()
        return {'sourceRevision': prepare.REVISION, 'configurationSha256': self.configuration,
                'operationalRevision': self.operational_revision, 'adapterFingerprint': self.adapter,
                'sourceRunId': selected['source_run_id'], 'sourceRunAttempt': selected['source_run_attempt'],
                'securityRunId': selected['securityRunId'], 'operationalRunId': operational['source_run_id'],
                'schemaInventory': self.schema, 'images': images, 'productionReady': False,
                'observedAtUnix': int(time.time()), 'privateCaSha256': sha(TLS / 'ca.crt'),
                'privateLeafSha256': sha(TLS / 'edge.crt'),
                'docker': self.docker_evidence,
                'checks': {'sourceAndConfiguration': 'passed', 'localIsolationConfiguration': 'passed',
                           'externalIpv4Ipv6Denial': 'inconclusive-before-authorized-host-probes',
                           'hostLossRecovery': 'pending', 'targetWorkloadQualification': 'pending'},
                'mode': 'private-first-install-synthetic-only', 'publishedPorts': []}

    def inputs(self, *, recovery=False):
        secure(TLS, mode=0o700, directory=True)
        for name, mode in (('ca.crt', 0o644), ('edge.crt', 0o644), ('edge.key', 0o600), ('truststore.p12', 0o644)):
            require(0 < secure(TLS / name, mode=mode).st_size <= 2 * 1024 * 1024, 'Missing/oversized TLS input.')
        for host in ('gameometro.com', 'auth.gameometro.com'):
            run(['openssl', 'verify', '-CAfile', TLS / 'ca.crt', '-verify_hostname', host, TLS / 'edge.crt'])
        run(['openssl', 'x509', '-in', TLS / 'edge.crt', '-checkend', '86400', '-noout'])
        cert_key = run(['openssl', 'x509', '-in', TLS / 'edge.crt', '-pubkey', '-noout'])
        private_key = run(['openssl', 'pkey', '-in', TLS / 'edge.key', '-passin', 'pass:', '-pubout'])
        require(cert_key == private_key, 'Private TLS certificate/key mismatch.')
        store = TLS / 'truststore.p12'
        check = subprocess.run(['openssl', 'pkcs12', '-in', str(store), '-passin', 'pass:changeit',
                                '-info', '-noout'], capture_output=True, timeout=30)
        require(check.returncode == 0 and b'keybag' not in check.stderr.lower(), 'Invalid JVM certificate-only truststore.')
        public_certs = run(['openssl', 'pkcs12', '-in', store, '-passin', 'pass:changeit', '-nokeys']).decode()
        der = run(['openssl', 'x509', '-in', TLS / 'ca.crt', '-outform', 'DER'])
        require(der in [ssl.PEM_cert_to_DER_cert(p) for p in re.findall(
            r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', public_certs, re.S)],
            'JVM truststore lacks the reviewed private CA.')
        import validate_private_production_truststore as stores
        evidence = TLS / 'truststore.validation.json'
        secure(evidence, mode=0o600)
        require(self.truststore_evidence and sha(evidence) == self.truststore_evidence,
                'Independently reviewed JVM truststore evidence hash is required.')
        stores.verify_receipt(json.loads(evidence.read_text()), store, TLS / 'ca.crt',
                              self.config['services']['application']['image'])
        services = self.config['services']
        secret_root = Path('/etc/gameometro/production/secrets')
        groups = services['application']['group_add']
        require(len(groups) == 1 and re.fullmatch('[1-9][0-9]{0,9}', str(groups[0])), 'Unexpected secret consumer group.')
        gid = int(groups[0])
        secure(secret_root, mode=0o750, directory=True, gid=gid)
        for name, secret in self.config['secrets'].items():
            path = Path(secret['file'])
            require(path.parent == secret_root, 'Secret path outside protected inventory.')
            st = secure(path, mode=0o640, gid=gid)
            require(st.st_size <= 4096 and (st.st_size > 0 or path.name.startswith(('igdb-', 'grafana-telegram-'))),
                    'Empty or oversized required secret.')
            value = path.read_bytes().decode('utf-8').rstrip('\n')
            optional = path.name.startswith(('igdb-', 'grafana-telegram-'))
            require((value or optional) and value == value.strip() and not any(c in value for c in '\r\n\x00'),
                    'Invalid required secret encoding/content.')
            for service_name, service in services.items():
                if any(s['source'] == name for s in service.get('secrets', [])):
                    require(gid in [int(g) for g in service.get('group_add', [])], 'Secret consumer lacks protected group.')
                    require(re.fullmatch('[0-9]+(?::[0-9]+)?', self.images[service_name]['user']),
                            'Unresolved effective image user.')
        model = Path('/var/lib/gameometro/production/catalogue-models/tcbig-initial')
        model_mounts = services['catalogue-localizer']['volumes']
        require(any(m['target'] == '/model' and m['source'] == str(model) for m in model_mounts),
                'Unexpected model mount path.')
        require(secure(model, directory=True).st_mode & 0o005 == 0o005,
                'Model directory must be traversable/readable by the localizer.')
        for child in model.iterdir():
            info = secure(child)
            require(info.st_mode & 0o004, 'Model must be readable by the non-root localizer.')
        previous = integration.ROOT
        try:
            integration.ROOT = self.source
            integration.verify_model(model)
        finally:
            integration.ROOT = previous
        secure(ADMIN / 'metrics', mode=0o755, directory=True)
        if not recovery: require(not any((ADMIN / 'metrics').iterdir()), 'Initial metrics directory must be empty.')
        require(any(m.get('source') == str(ADMIN / 'metrics') and m.get('target') == '/textfile'
                    for m in services['node-exporter']['volumes']), 'Unexpected metrics bind path.')
        # Every bind must exist already; no Docker-created host paths, symlink traversal,
        # writable repository mounts or unreviewed socket/device access.
        for service in services.values():
            for mount in service.get('volumes', []):
                if mount['type'] == 'bind':
                    path = Path(mount['source'])
                    require(path.exists() and path.resolve() == path and mount.get('read_only'), 'Unsafe/missing runtime bind.')

    def fresh(self, *, allow_gate=False):
        for cmd in (['docker', 'ps', '-aq'], ['docker', 'volume', 'ls', '-q'], ['docker', 'network', 'ls', '-q']):
            require(not run([*cmd, '--filter', f'label=com.docker.compose.project={prepare.PROJECT}']).strip(),
                    'Existing project resources; initial installation refused.')
        # Catch unlabelled resources too. Listing errors are refusals, never "absent".
        volumes = set(run(['docker', 'volume', 'ls', '--format', '{{.Name}}']).decode().splitlines())
        networks = set(run(['docker', 'network', 'ls', '--format', '{{.Name}}']).decode().splitlines())
        names = run(['docker', 'ps', '-a', '--format', '{{.Names}}']).decode().splitlines()
        require(not any(n.startswith((prepare.PROJECT + '-', prepare.PROJECT + '_')) for n in names),
                'A target container name is already claimed.')
        if allow_gate:
            volumes.discard(self.recovery.gate_volume)
        require(not volumes.intersection(v['name'] for v in self.config['volumes'].values())
                and not networks.intersection(n['name'] for n in self.config['networks'].values()),
                'A target volume/network name is already claimed.')

    def routes(self):
        reserved = [ipaddress.ip_network(c['subnet']) for n in self.config['networks'].values()
                    for c in n.get('ipam', {}).get('config', [])]
        routes = json.loads(run(['ip', '-json', 'route', 'show', 'table', 'all']))
        require(len(routes) <= 4096, 'Host route inventory exceeds supported bound.')
        subnets = [r['dst'] for r in routes if r.get('dst') not in (None, 'default', '0.0.0.0/0')]
        ids = run(['docker', 'network', 'ls', '-q']).decode().split()
        require(len(ids) <= 128, 'Docker network inventory exceeds supported bound.')
        for network in ids:
            info = json.loads(run(['docker', 'network', 'inspect', network]))[0]
            if info['Name'] in {n['name'] for n in self.config['networks'].values()}:
                continue
            subnets.extend(c['Subnet'] for c in info.get('IPAM', {}).get('Config', []) if c.get('Subnet'))
        require(not any(a.version == b.version and a.overlaps(b) for a in reserved
                        for b in map(ipaddress.ip_network, subnets)), 'Host/Docker/tailnet route overlaps runtime trust subnet.')

    def claim(self):
        state = JOURNAL / 'recovery'
        state.mkdir(mode=0o700)
        self.source_file = self.file
        self.config = copy.deepcopy(self.config)
        for service in self.config['services'].values():
            labels = service.setdefault('labels', {})
            require(control.RUN_LABEL not in labels and control.CONFIG_LABEL not in labels, 'Attempt labels already present.')
            labels.update({control.RUN_LABEL: self.run_id, control.CONFIG_LABEL: self.configuration})
        self.file = JOURNAL / 'runtime.json'
        durable(self.file, self.config)
        self.runtime_sha = sha(self.file)
        self.base = ['docker', 'compose', '-p', prepare.PROJECT, '-f', str(self.file), '--profile', '*']
        self.load_recovery(state)

    def load_recovery(self, state):
        path = self.source / 'deploy/public-production/bin/recovery'
        loader = importlib.machinery.SourceFileLoader('accepted_private_bootstrap_recovery', str(path))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        module = importlib.util.module_from_spec(spec)
        loader.exec_module(module)
        def guarded(args, *, data=None, output=None, timeout=1800):
            require(output is None, 'Unsupported bootstrap output transport.')
            return run(args, data=data, timeout=min(timeout, 180))
        module.run = guarded  # Bounded/cancellable transport; candidate guard logic is unchanged.
        class Bootstrap(module.Runtime):
            @contextlib.contextmanager
            def engine_lock(self):
                with bootstrap_lease(self):
                    yield
            def check_lease(self):
                check_kernel_lease(self)
        self.recovery = Bootstrap(self.file, prepare.PROJECT, state)
        self.recovery.run_id = self.run_id
        self.recovery.run_configuration = self.configuration

    def assert_lease(self):
        self.recovery.check_lease()

    def pull(self):
        for image in sorted({i['image'] for i in self.images.values()}):
            run(['docker', 'pull', '--platform', 'linux/amd64', image], output=False, timeout=900)
        self.headroom()

    def headroom(self):
        memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines() if ':' in line)
        total = int(memory['MemTotal'].split()[0]) * 1024
        available = int(memory['MemAvailable'].split()[0]) * 1024
        ceiling = sum(int(s['mem_limit']) for name, s in self.config['services'].items() if name != 'migration')
        require(available >= ceiling + total // 5, 'Insufficient memory for the configured envelope plus recovery headroom.')
        for path in (ADMIN, self.docker_root):
            disk = shutil.disk_usage(path)
            require(disk.free >= disk.total // 4, 'Insufficient free disk/recovery headroom.')

    def up(self, *services):
        self.gate_open()
        self.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '240', '--pull', 'never',
                     *services, timeout=300, output=False)
        self.running(services)

    def gate_open(self):
        require(sha(self.source_file) == self.configuration
                and sha(self.file) == getattr(self, 'runtime_sha', self.configuration), 'Private configuration changed during installation.')
        require(self.recovery.gate('check') == b'open'
                and self.recovery.gate('maintenance-check') == b'none', 'Recovery/identity fence closed.')

    def running(self, names):
        for name in names:
            cid = self.compose('ps', '-q', name).decode().split()
            require(len(cid) == 1, 'Missing/ambiguous installed service.')
            value = json.loads(run(['docker', 'inspect', cid[0]]))[0]
            require(value['State']['Running'] and value['State'].get('Health', {}).get('Status', 'healthy') == 'healthy'
                    and value['Config']['Image'] == self.config['services'][name]['image']
                    and not value['HostConfig'].get('PortBindings')
                    and value['HostConfig']['RestartPolicy']['Name'] == 'no', 'Live image, health, restart or exposure mismatch.')
            host = value['HostConfig']; intended_service = self.config['services'][name]
            require(not host.get('Privileged') and not host.get('PublishAllPorts')
                    and not host.get('Devices') and host.get('NetworkMode') not in ('host', 'container')
                    and bool(host.get('ReadonlyRootfs')) == bool(intended_service.get('read_only')),
                    'Live namespace, device or filesystem privilege drift.')
            image = json.loads(run(['docker', 'image', 'inspect', intended_service['image']]))[0]
            require(value.get('Image') == image['Id'], 'Live image content identity differs.')
            require(value['Config'].get('User', '') == intended_service.get('user', image['Config'].get('User', ''))
                    and sorted(map(str, host.get('GroupAdd') or [])) == sorted(map(str, intended_service.get('group_add', [])))
                    and bool(host.get('Init')) == bool(intended_service.get('init')), 'Effective user/group/init drift.')
            def caps(v): return {c.removeprefix('CAP_') for c in (v or [])}
            require(caps(host.get('CapAdd')) == caps(intended_service.get('cap_add'))
                    and caps(host.get('CapDrop')) == caps(intended_service.get('cap_drop'))
                    and {x.removesuffix(':true') for x in host.get('SecurityOpt', [])} == {
                        x.removesuffix(':true') for x in intended_service.get('security_opt', [])}, 'Effective capability/security drift.')
            require(host.get('Memory') == int(intended_service['mem_limit'])
                    and host.get('NanoCpus') == int(float(intended_service['cpus']) * 1e9)
                    and host.get('PidsLimit') == int(intended_service['pids_limit'])
                    and host.get('PidMode', '') == '' and host.get('IpcMode') == 'private'
                    and not host.get('UTSMode') and not host.get('UsernsMode'), 'Effective resource/namespace drift.')
            require(all(not bindings for bindings in (value['NetworkSettings'].get('Ports') or {}).values()), 'Unexpected IPv4/IPv6 publication.')
            require(value['Config'].get('Entrypoint') == intended_service.get('entrypoint', image['Config'].get('Entrypoint'))
                    and value['Config'].get('Cmd') == intended_service.get('command', None if intended_service.get('entrypoint') is not None else image['Config'].get('Cmd')),
                    'Effective entrypoint/command differs from the reviewed guard.')
            require(value['Config'].get('Labels', {}).get(control.RUN_LABEL) == self.run_id
                    and value['Config']['Labels'].get(control.CONFIG_LABEL) == self.configuration, 'Running attempt identity differs.')
            expected_mounts = {m['target'] for m in intended_service.get('volumes', [])}
            expected_mounts.update(s['target'] if s['target'].startswith('/') else '/run/secrets/' + s['target']
                                   for s in intended_service.get('secrets', []))
            image_volumes = set((image['Config'].get('Volumes') or {}))
            require(all(m['Destination'] in expected_mounts or
                        m['Type'] == 'volume' and m['Destination'] in image_volumes for m in value.get('Mounts', [])),
                    'Unreviewed effective mount.')
            actual_mounts = {m['Destination']: m for m in value.get('Mounts', [])}
            require(expected_mounts <= set(actual_mounts), 'Missing effective secret/runtime mount.')
            for mount in intended_service.get('volumes', []):
                actual_mount = actual_mounts[mount['target']]
                require(actual_mount['Type'] == mount['type']
                        and bool(actual_mount['RW']) == (not mount.get('read_only', False)), 'Effective mount type/access drift.')
                if mount['type'] == 'bind':
                    require(actual_mount['Source'] == mount['source'], 'Effective bind source drift.')
                else:
                    require(actual_mount.get('Name') == self.config['volumes'][mount['source']]['name'], 'Effective durable volume drift.')
            for secret in intended_service.get('secrets', []):
                target = secret['target'] if secret['target'].startswith('/') else '/run/secrets/' + secret['target']
                actual_mount = actual_mounts[target]
                require(actual_mount['Type'] == 'bind' and not actual_mount['RW']
                        and actual_mount['Source'] == self.config['secrets'][secret['source']]['file'], 'Effective secret bind drift.')
            require(set(value['NetworkSettings']['Networks']) == {
                self.config['networks'][n]['name'] for n in intended_service.get('networks', {})}, 'Unexpected live network membership.')
            require(host.get('NetworkMode') in set(value['NetworkSettings']['Networks']), 'Unexpected network namespace sharing.')
            for network in self.config['services'][name].get('networks', {}):
                intended = self.config['networks'][network]
                actual = json.loads(run(['docker', 'network', 'inspect', intended['name']]))[0]
                require(not actual['EnableIPv6'] and actual['Driver'] == 'bridge'
                        and actual['Internal'] == intended.get('internal', False)
                        and actual['Options'].get('com.docker.network.bridge.gateway_mode_ipv4') == 'nat',
                        'Live network isolation differs from the private contract.')
                control.validate_network_options(actual['Options'])
                require(actual.get('EnableIPv4') is True and actual.get('Scope') == 'local'
                        and actual.get('IPAM', {}).get('Driver') == 'default'
                        and not actual.get('Ingress') and not actual.get('Attachable'), 'Unknown effective network configuration.')
                control.validate_ipam(actual['IPAM'], intended.get('ipam', {}))
                endpoint = value['NetworkSettings']['Networks'][intended['name']]
                require(endpoint['NetworkID'] == actual['Id'] and not endpoint.get('GlobalIPv6Address')
                        and not endpoint.get('LinkLocalIPv6Address'), 'Effective endpoint identity/IPv6 differs.')
                fixed = (intended_service['networks'][network] or {}).get('ipv4_address')
                if fixed: require(endpoint['IPAddress'] == fixed, 'Fixed trusted proxy address drift.')

    def migrate(self):
        self.gate_open()
        self.compose('run', '--rm', '--no-deps', '--pull', 'never', 'migration', timeout=900, output=False)
        version = self.recovery.sql('videogame_platform',
            'SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;').decode().strip()
        require(version == '20261023.130000', 'Unexpected accepted-candidate migration endpoint.')
        require(self.recovery.sql('videogame_platform',
            'SELECT bool_and(success) FROM flyway_schema_history;').strip() == b't', 'Failed Flyway history.')
        history = self.recovery.sql('videogame_platform',
            "SELECT md5(string_agg(version || ':' || script || ':' || checksum::text, ',' ORDER BY installed_rank)) FROM flyway_schema_history;").decode().strip()
        require(re.fullmatch('[0-9a-f]{32}', history), 'Invalid Flyway history fingerprint.')
        return {'version': version, 'historyFingerprint': history}

    def reader(self):
        path = Path(self.config['secrets']['grafana_database_password']['file'])
        password = path.read_text().strip()
        require(password and not any(c in password for c in '\r\n\x00'), 'Invalid Grafana reader input.')
        sql = "SELECT convert_from(decode('" + password.encode().hex() + "','hex'),'UTF8') AS reader_password\n\\gset\n"
        self.recovery.sql('videogame_platform', sql + (self.source / 'deploy/private-dev/grafana/provision-reader.sql').read_text())

    def smoke(self):
        self.gate_open()
        self.running([n for n in self.config['services'] if n != 'migration'])
        info = json.loads(self.compose('exec', '-T', 'application', 'wget', '-qO-', '-T', '15',
                                       'http://127.0.0.1:8081/actuator/info'))
        require(info.get('build', {}).get('sourceRevision') == prepare.REVISION
                and info.get('build', {}).get('version') == prepare.VERSION, 'Running BFF build mismatch.')
        # The accepted application is a JRE image. Verify the configured store as
        # its actual consumer, without injecting a JDK or modifying that image.
        self.compose('exec', '-T', 'application', 'keytool', '-list', '-keystore',
                     '/run/private-tls/truststore.p12', '-storepass', 'changeit', output=False)
        env = self.config['services']['application']['environment']
        env = {k: v for k, v in env.items() if k.startswith(('OIDC_', 'APPLICATION_OIDC_'))}
        env['OIDC_CONTRACT_CA_FILE'] = '/run/private-tls/ca.crt'
        env_args = [a for k, v in env.items() for a in ('-e', f'{k}={v}')]
        run(['docker', 'run', '--rm', '-i', '--network', self.config['networks']['telemetry']['name'],
             '--label', f'{control.RUN_LABEL}={self.run_id}', '--label', f'{control.CONFIG_LABEL}={self.configuration}',
             '--label', f'com.docker.compose.project={prepare.PROJECT}', '--label', 'com.docker.compose.service=private-probe',
             '--log-driver', 'none',
             '--label', 'gameometro.private-probe=true', '--read-only', '--cap-drop', 'ALL',
             '--security-opt', 'no-new-privileges:true', '--memory', '128m', '--pids-limit', '32',
             '-v', f'{TLS}/ca.crt:/run/private-tls/ca.crt:ro',
             '-v', f'{self.source}/scripts/check-oidc-issuer-contract.py:/work/check-oidc-issuer-contract.py:ro',
             *env_args, HELPER, 'python3', '-'], data=PROBE.encode(), output=False, timeout=180)
        self.gate_open()

    def close(self, phase):
        def stop():
            self.assert_lease()
            run(['docker', 'run', '--rm', '--log-driver', 'none', '--network', 'none', '--read-only',
                 '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '96m', '--pids-limit', '32',
                 '-v', self.recovery.gate_volume + ':/gate', HELPER, 'python3', '-c', control.FENCE,
                 self.run_id, self.configuration], output=False, timeout=15)
            journal_error = None
            try:
                self.recovery.closed('first-install-' + phase)
            except OSError as error:
                # The owned gate is already fenced under the retained lease.
                # Local journal I/O must not prevent physical containment.
                journal_error = error
            try:
                result = control.stop_owned(self.run_id, self.configuration, prepare.PROJECT,
                                            set(self.config['services']) | {'private-probe'}, self.assert_lease, runner=run)
            except Exception as stop_error:
                if journal_error is not None:
                    raise ExceptionGroup('Recovery persistence and physical shutdown failed.',
                                         [journal_error, stop_error]) from None
                raise
            if journal_error is not None:
                raise journal_error
            return result
        with control.cleanup_window():
            try:
                self.assert_lease()
                held = True
            except BaseException:
                held = False
            if held:
                return stop()
            # Reacquire, never infer ownership from a missing/dead actor or cached ID.
            # Competing lease/maintenance/unknown fence => no stop and inconclusive evidence.
            with bootstrap_lease(self.recovery, mode='reconcile'):
                return stop()


def intent_file():
    return ADMIN / "first-install-intent.json"


def claim_intent(receipt):
    path = intent_file()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write(json.dumps(receipt, sort_keys=True) + "\n")
        stream.flush(); os.fsync(stream.fileno())
    control.fsync_dir(ADMIN)


def failed_attempt(receipt, journal_claimed):
    value = {**receipt, "outcome": "failure", "finishedAtUnix": int(time.time()),
             "cancellation": control.CAUSE, "commandCompletion": "inconclusive" if control.UNCERTAIN_COMMAND else "observed"}
    value.setdefault('shutdown', {'status': 'not-attempted', 'reason': 'runtime-ownership-not-established'})
    # Root-side sidecar dominates a stale/provisional success after storage errors.
    for path in [ADMIN / ("first-install-failure-" + receipt["runId"] + ".json"),
                 *([JOURNAL / "installation.json"] if journal_claimed else [])]:
        try:
            durable(path, value)
        except BaseException:
            pass  # Intent/partial writes remain; inspection must report inconclusive.
    return value


def first_install(host):
    host.preflight()
    lock_path = ADMIN / "first-install.lock"
    if lock_path.exists() or lock_path.is_symlink(): secure(lock_path, mode=0o600)
    fd = os.open(lock_path, os.O_CREAT | os.O_NOFOLLOW | os.O_RDWR, 0o600)
    receipt = None
    journal_claimed = False
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        receipt = {**host.preflight(), "runId": str(uuid.uuid4()), "outcome": "running",
                   "phase": "claim", "startedAtUnix": int(time.time())}
        host.run_id = receipt["runId"]
        try:
            claim_intent(receipt)
            JOURNAL.mkdir(mode=0o700)
            journal_claimed = True
            control.fsync_dir(ADMIN)
            durable(JOURNAL / "installation.json", receipt)
            def checkpoint(phase):
                receipt["phase"] = phase
                durable(JOURNAL / "installation.json", receipt)
            host.claim()
            checkpoint("pull"); host.pull()
            checkpoint("recovery-initialize")
            with host.recovery.lock():
                try:
                    host.fresh(allow_gate=True)
                    host.recovery.initialize()
                    durable(JOURNAL / "recovery-initialize.json", {"outcome": "success", "runId": receipt["runId"]})
                    checkpoint("postgres"); host.up("postgres")
                    checkpoint("flyway"); migration = host.migrate()
                    receipt.update(migrationVersion=migration["version"], flywayHistoryFingerprint=migration["historyFingerprint"])
                    checkpoint("grafana-reader"); host.reader()
                    checkpoint("dependencies")
                    host.up("keycloak", "catalogue-localizer", "caddy", "telemetry", "prometheus", "grafana", "node-exporter", "blackbox")
                    checkpoint("application"); host.up("application")
                    checkpoint("private-smoke"); host.smoke()
                    receipt.update(outcome="prepared", phase="lease-finalize")
                    durable(JOURNAL / "installation.json", receipt)
                except BaseException:
                    try:
                        receipt["shutdown"] = host.close(receipt["phase"])
                    except BaseException:
                        receipt["shutdown"] = {"status": "inconclusive", "reason": "lease-or-stop-unconfirmed"}
                    raise
            require(not control.CANCEL.is_set(), "Cancellation before final acceptance.")
            success = {**receipt, "outcome": "success", "phase": "complete", "leaseCompletion": "acknowledged-and-exited",
                       "finishedAtUnix": int(time.time())}
            durable(JOURNAL / "installation.json", success)
            return success
        except BaseException:
            if receipt["phase"] == "lease-finalize" and 'shutdown' not in receipt:
                try:
                    receipt["shutdown"] = host.close("lease-finalize")
                except BaseException:
                    receipt["shutdown"] = {"status": "inconclusive", "reason": "ownership-unavailable-no-stop-attempted"}
            failed_attempt(receipt, journal_claimed)
            raise
    finally:
        os.close(fd)


def inspect_interruption():
    result = {"mode": "read-only-interruption-inspection", "productionReady": False,
              "status": "inconclusive", "cleanupGuaranteed": False}
    try:
        secure(ADMIN, mode=0o700, directory=True)
        secure(intent_file(), mode=0o600)
        intent = json.loads(intent_file().read_text())
        run_id = intent["runId"]
        require(re.fullmatch("[0-9a-f-]{36}", run_id), "Invalid attempt identity.")
        result["runId"] = run_id
        lock_fd = os.open(ADMIN / 'first-install.lock', os.O_RDONLY | os.O_NOFOLLOW)
        try:
            try:
                fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                result['localExclusiveOperationActive'] = False
            except BlockingIOError:
                result['localExclusiveOperationActive'] = True
        finally:
            os.close(lock_fd)
        path = JOURNAL / "installation.json"
        if path.exists():
            secure(path, mode=0o600)
            state = json.loads(path.read_text())
            require(state.get("runId") == run_id, "Intent/journal identity mismatch.")
            if state.get('outcome') == 'success':
                require(state.get('phase') == 'complete' and state.get('leaseCompletion') == 'acknowledged-and-exited',
                        'Success without lease completion evidence.')
            result.update(recordedOutcome=state.get("outcome"), phase=state.get("phase"))
        else:
            result["reason"] = "journal-creation-incomplete"
        failures = list(ADMIN.glob("first-install-failure-" + run_id + ".json"))
        result["failureSidecar"] = bool(failures)
        result["pendingWrites"] = len(list(ADMIN.glob("first-install*.pending")))
        if JOURNAL.exists(): result["pendingWrites"] += len(list(JOURNAL.rglob("*.pending")))
        integration.verify_local_docker()
        result["ownedContainers"] = control.owned_containers(run_id, intent["configurationSha256"], prepare.PROJECT,
                                                            set(json.loads((JOURNAL / "runtime.json").read_text())["services"]) | {"private-probe"}) if (JOURNAL / "runtime.json").exists() else []
        if result['localExclusiveOperationActive']:
            result.update(status='exclusive-operation-in-progress', recovery='inspect-without-interference')
        elif failures or result.get("recordedOutcome") != "success" or result["pendingWrites"]:
            result.update(status="interrupted-or-failed", recovery="explicit-stop-only-reconciliation-required")
        else:
            result.update(status="recorded-success-requires-live-qualification")
    except BaseException:
        result["reason"] = "unreadable-missing-or-contradictory-evidence"
    return result


def reconcile_interrupted(host):
    require(os.geteuid() == 0 and sys.platform == 'linux', 'Local Linux root reconciliation required.')
    integration.verify_local_docker()
    secure(ADMIN, mode=0o700, directory=True)
    secure(ROOT, directory=True)
    for name in OPERATIONAL_FILES: secure(ROOT / name)
    fd = os.open(ADMIN / "first-install.lock", os.O_CREAT | os.O_NOFOLLOW | os.O_RDWR, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        secure(intent_file(), mode=0o600)
        intent = json.loads(intent_file().read_text())
        require(intent["configurationSha256"] == host.configuration and intent["adapterFingerprint"] == host.adapter
                and intent["operationalRevision"] == host.operational_revision
                and operational_fingerprint() == host.adapter, "Interrupted attempt/operational identity differs.")
        state_path = JOURNAL / 'installation.json'
        sidecar = ADMIN / ('first-install-failure-' + intent['runId'] + '.json')
        state = json.loads(state_path.read_text()) if state_path.exists() else {}
        require(not state or state.get('runId') == intent['runId'], 'Conflicting attempt journal.')
        require(state.get('outcome') != 'success' or sidecar.exists(), 'Not an interrupted bootstrap.')
        if sidecar.exists():
            secure(sidecar, mode=0o600)
            failure = json.loads(sidecar.read_text())
            require(failure.get('runId') == intent['runId'], 'Failure sidecar identity differs.')
            if failure.get('commandCompletion') == 'inconclusive': control.UNCERTAIN_COMMAND = True
        elif state.get('outcome') in ('running', 'prepared'):
            control.UNCERTAIN_COMMAND = True  # SIGKILL may have left a daemon request in flight.
        require(prepare.fingerprint(host.source) == prepare.FINGERPRINT and sha(host.file) == host.configuration,
                "Recovery source/configuration changed.")
        host.run_id = intent["runId"]
        base = json.loads(host.file.read_text()); expected = copy.deepcopy(base)
        for service in expected["services"].values():
            service.setdefault("labels", {}).update({control.RUN_LABEL: host.run_id, control.CONFIG_LABEL: host.configuration})
        host.file = JOURNAL / "runtime.json"
        if not host.file.exists():
            require(not control.owned_containers(host.run_id, host.configuration, prepare.PROJECT,
                                                set(base['services']) | {'private-probe'}), 'Missing runtime journal but containers exist.')
            result = {'runId': host.run_id, 'mode': 'stop-only-reconciliation', 'shutdown': {'status': 'inconclusive-no-runtime-journal-no-stop'},
                      'productionReady': False, 'journalCleared': False}
            durable(ADMIN / ('first-install-reconciliation-' + uuid.uuid4().hex + '.json'), result)
            return result
        secure(host.file, mode=0o600)
        require(json.loads(host.file.read_text()) == expected, "Interrupted runtime ownership delta differs.")
        host.config = expected; host.runtime_sha = sha(host.file)
        host.base = ["docker", "compose", "-p", prepare.PROJECT, "-f", str(host.file), "--profile", "*"]
        gate = expected["volumes"]["recovery-state"]["name"]
        require(gate in run(["docker", "volume", "ls", "--format", "{{.Name}}"]).decode().splitlines(),
                "Missing gate: no lease can be proven; do not create a replacement.")
        host.load_recovery(JOURNAL / "recovery")
        result = {"runId": host.run_id, "mode": "explicit-stop-only-reconciliation", "productionReady": False,
                  "shutdown": host.close("explicit-interruption-reconciliation")}
        durable(JOURNAL / ("reconciliation-" + uuid.uuid4().hex + ".json"), result)
        return result
    finally:
        os.close(fd)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--print-adapter-fingerprint', action='store_true')
    parser.add_argument('--truststore-evidence-sha256')
    parser.add_argument('--evidence-dir', type=Path)
    parser.add_argument('--inspect-interrupted', action='store_true')
    parser.add_argument('--reconcile-interrupted', action='store_true')
    parser.add_argument('--install', action='store_true', help='Explicit first install; never an update or retry')
    parser.add_argument('--configuration-sha256')
    parser.add_argument('--adapter-fingerprint')
    parser.add_argument('--operational-revision')
    args = parser.parse_args()
    if args.print_adapter_fingerprint:
        print(operational_fingerprint())
        return 0
    control.signal_handlers()
    watcher = control.watch_session() if args.install or args.reconcile_interrupted else None
    host = Host(args.configuration_sha256, args.adapter_fingerprint, args.operational_revision, args.truststore_evidence_sha256, args.evidence_dir)
    try:
        require(sum((args.install, args.inspect_interrupted, args.reconcile_interrupted)) <= 1, 'Choose one operation.')
        if args.inspect_interrupted:
            print(json.dumps(inspect_interruption())); return 0
        if args.reconcile_interrupted:
            result = reconcile_interrupted(host)
            print(json.dumps(result))
            return 0 if result['shutdown']['status'] == 'confirmed' else 1
        require(args.evidence_dir is not None, 'Protected preflight evidence directory is required.')
        if args.install:
            first_install(host)
            print('Private synthetic-only first installation passed. No public/data/recovery acceptance granted.')
        else:
            summary = host.preflight()
            print(json.dumps({**summary, 'outcome': 'preflight-passed'}))
        return 0
    except BaseException:
        print(json.dumps({'outcome': 'refused-or-failed', 'lastPreflightCheck': host.check,
                          'productionReady': False, 'inspectProtectedJournal': bool(args.install)}))
        return 1
    finally:
        if watcher: watcher.set()


if __name__ == '__main__':
    raise SystemExit(main())
