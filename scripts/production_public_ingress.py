#!/usr/bin/env python3
"""Reviewed, reversible public-ingress transition of the installed production runtime (#276).

The only difference from the active private runtime is the product edge: Caddy publishes 80/443
on the host's public IPv4 address and obtains public ACME certificates instead of the private edge
certificate. Every other service, mount, network, secret and image stays identical. Owner-only
administration keeps its Tailscale listeners (ADR-0035).

Self-registration is closed and verified before any port is published. It is reopened only by
the separate `--open-registration` decision. A transition whose public certificate and issuer
cannot be verified within its budget recreates the private edge automatically, with registration
still closed. `--revert` returns to the private edge at any time.

`ACTIVE.json` names the public configuration while it runs, so the backup cycle and the in-place
upgrade follow the real runtime; INGRESS.json keeps the private file it replaced.

Run as root from the reviewed operational source, like the other private-production tools:
  --plan --public-ipv4 A                         read-only
  --apply --public-ipv4 A --owner-reference R    close registration, publish, verify or revert
  --verify                                       read-only check of the recorded mode
  --revert --owner-reference R                   private edge again; registration stays closed
  --open-registration --owner-reference R        Phase 6 go decision only
"""
import argparse
import copy
import hashlib
import http.client
import ipaddress
import json
import os
import re
from pathlib import Path
import socket
import ssl
import sys
import time

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

import install_private_production as install  # noqa: E402
import prepare_private_production as prepare  # noqa: E402
import private_dev_promotion as trust  # noqa: E402
import private_production_acceptance as acceptance  # noqa: E402
import private_production_control as control  # noqa: E402

require = trust.require
ROOT = SCRIPTS.parent
ADMIN = Path('/var/lib/gameometro/production')
INGRESS_DIR = ADMIN / 'ingress'
STATE = INGRESS_DIR / 'INGRESS.json'
ACTIVE = ADMIN / 'upgrades/ACTIVE.json'
HOSTS = ('gameometro.com', 'auth.gameometro.com')
CADDYFILE_TARGET = '/etc/caddy/Caddyfile'
PRIVATE_TLS_TARGETS = ('/run/private-tls/edge.crt', '/run/private-tls/edge.key')
PRIVATE_TLS_LINE = '\n\ttls /run/private-tls/edge.crt /run/private-tls/edge.key'
REGISTRATION_ACTOR = 'deploy/public-production/bin/configure-public-registration'
ISSUER = 'https://auth.gameometro.com/realms/gameometro'
CERTIFICATE_BUDGET = 300


def sha(data):
    return hashlib.sha256(data).hexdigest()


def public_ports(ipv4):
    return [{'mode': 'ingress', 'host_ip': ipv4, 'target': port, 'published': str(port), 'protocol': 'tcp'}
            for port in (80, 443)]


def public_caddyfile(private_text):
    """Exact inverse of prepare.private_caddy for the two product hosts; admin blocks unchanged."""
    text = private_text
    for host in HOSTS:
        # Anchored per line: 'gameometro.com {' is also a suffix of 'auth.gameometro.com {'.
        text, count = re.subn('^' + re.escape(host + ' {' + PRIVATE_TLS_LINE), host + ' {', text, flags=re.M)
        require(count == 1, 'The private edge does not carry the reviewed TLS delta.')
    require('/run/private-tls/' not in text, 'An unreviewed private TLS reference would remain.')
    return text


def derive_public(config, caddyfile_path, ipv4):
    """Public twin of a private runtime configuration: only the edge differs."""
    result = copy.deepcopy(config)
    caddy = result['services']['caddy']
    require(not caddy.get('ports'), 'The active edge already publishes ports.')
    mounts = caddy.get('volumes', [])
    edge = [m for m in mounts if m['target'] == CADDYFILE_TARGET]
    tls = [m for m in mounts if m['target'] in PRIVATE_TLS_TARGETS]
    require(len(edge) == 1 and len(tls) == 2, 'The active edge mounts differ from the private contract.')
    caddy['volumes'] = [m for m in mounts if m['target'] not in PRIVATE_TLS_TARGETS]
    caddy['volumes'][caddy['volumes'].index(edge[0])] = prepare.bind(caddyfile_path, CADDYFILE_TARGET)
    require(any(m.get('target') == '/data' and m['type'] == 'volume' for m in caddy['volumes']),
            'Public certificates need the edge data volume.')
    caddy['ports'] = public_ports(ipv4)
    return result


def validate_public(private, public, caddyfile_path, ipv4):
    require(public == derive_public(private, caddyfile_path, ipv4), 'Unreviewed public runtime delta.')
    for name, service in public['services'].items():
        require(name == 'caddy' or not service.get('ports'), 'Only the edge may publish ports.')
        require(not service.get('privileged') and 'network_mode' not in service and '@sha256:' in service['image'],
                'Public runtime forbids privileges, host networking and mutable images.')


def check_ipv4(value, interfaces):
    address = ipaddress.ip_address(value)
    require(address.version == 4 and address.is_global, 'A global public IPv4 address is required.')
    require(value in interfaces, 'The public IPv4 address is not assigned to this host.')
    return value


def host_ipv4_addresses(runner=control.run):
    output = json.loads(runner(['ip', '-j', '-4', 'addr', 'show']))
    return {a['local'] for i in output if not i['ifname'].startswith(('tailscale', 'docker', 'br-', 'veth', 'lo'))
            for a in i.get('addr_info', [])}


def dns_points_here(ipv4, resolve=socket.getaddrinfo):
    """ACME HTTP-01 needs the names here first; an AAAA record would send clients to a closed v6 edge."""
    for host in HOSTS:
        try:
            v4 = {a[4][0] for a in resolve(host, 443, socket.AF_INET, socket.SOCK_STREAM)}
        except socket.gaierror:
            v4 = set()
        require(v4 == {ipv4}, f'{host} must resolve only to {ipv4} before publication.')
        try:
            v6 = {a[4][0] for a in resolve(host, 443, socket.AF_INET6, socket.SOCK_STREAM)}
        except socket.gaierror:
            v6 = set()
        require(not v6, f'{host} has an AAAA record; this edge serves IPv4 only until IPv6 is qualified.')


def https_get(ipv4, host, path, timeout=15):
    """Public trust only: the system store, SNI and the real address, never the private CA."""
    context = ssl.create_default_context()
    raw = socket.create_connection((ipv4, 443), timeout=timeout)
    connection = http.client.HTTPSConnection(host, context=context, timeout=timeout)
    connection.sock = context.wrap_socket(raw, server_hostname=host)
    try:
        connection.request('GET', path, headers={'Host': host})
        response = connection.getresponse()
        return response.status, response.read(262145)
    finally:
        connection.close()


def public_edge_ready(ipv4, get=https_get):
    status, _ = get(ipv4, 'gameometro.com', '/api/v1/releases?page=1&pageSize=1')
    require(status == 200, 'Public catalogue did not answer through the edge.')
    status, _ = get(ipv4, 'gameometro.com', '/api/v1/me/ratings')
    require(status == 401, 'Personal routes must refuse anonymous public access.')
    status, body = get(ipv4, 'auth.gameometro.com', '/realms/gameometro/.well-known/openid-configuration')
    require(status == 200 and json.loads(body).get('issuer') == ISSUER, 'Public issuer contract failed.')


class Ingress:
    def __init__(self, runner=control.run, clock=time.monotonic, sleep=time.sleep, get=https_get,
                 resolve=socket.getaddrinfo, admin=ADMIN):
        self.run, self.clock, self.sleep, self.get, self.resolve = runner, clock, sleep, get, resolve
        self.admin = admin
        self.directory = admin / 'ingress'
        self.state_file = self.directory / 'INGRESS.json'
        self.active_file = admin / 'upgrades/ACTIVE.json'

    # --- state ---
    def active(self):
        pointer = json.loads(self.active_file.read_text())
        path = Path(pointer['configurationFile'])
        require(install.sha(path) == pointer['configurationSha256'], 'The active configuration changed.')
        return pointer, path, json.loads(path.read_text())

    def state(self):
        return json.loads(self.state_file.read_text()) if self.state_file.exists() else None

    def unfinished_upgrade(self):
        root = self.admin / 'upgrades'
        return any(json.loads(p.read_text()).get('status') in ('running', 'failed') for p in root.glob('*/journal.json'))

    def compose(self, path, *arguments, timeout=600):
        return self.run(['docker', 'compose', '-p', prepare.PROJECT, '-f', str(path), '--profile', '*', *arguments],
                        timeout=timeout)

    def gate_open(self, source, configuration_file):
        recovery = acceptance.load_recovery(source, configuration_file, self.admin / 'first-install/recovery')
        require(recovery.gate('check') == b'open' and recovery.gate('maintenance-check') == b'none',
                'The recovery or identity fence is closed; no ingress change.')
        return recovery

    def registration(self, config, state, check=False, source=ROOT):
        # The reviewed operational source of this tool provides the actor and its identity module.
        environment = config['services']['keycloak']['environment']
        secret = Path(config['secrets']['keycloak_admin_password']['file'])
        actor = source / REGISTRATION_ACTOR
        require(actor.is_file(), 'The active source lacks the reviewed registration actor.')
        output = self.run(['docker', 'run', '--rm', '-i', '--log-driver', 'none',
                           '--network', config['networks']['identity-data']['name'], '--read-only', '--cap-drop', 'ALL',
                           '--security-opt', 'no-new-privileges:true', '--memory', '96m', '--pids-limit', '32',
                           '--label', f'com.docker.compose.project={prepare.PROJECT}',
                           '--label', 'com.docker.compose.service=private-probe', '--label', 'gameometro.recovery-helper=true',
                           '-v', f'{source}:/candidate:ro', install.HELPER, 'python3', '-I', '/candidate/' + REGISTRATION_ACTOR,
                           '--origin', 'http://keycloak:8080', '--admin-username', environment['KC_BOOTSTRAP_ADMIN_USERNAME'],
                           '--admin-password-file', '/dev/stdin', '--state', state, *(['--check'] if check else [])],
                          data=secret.read_bytes(), timeout=120)
        return json.loads(output)

    def write_state(self, value):
        self.directory.mkdir(mode=0o700, exist_ok=True)
        control.durable(self.state_file, value)

    def point_active(self, pointer, path):
        control.durable(self.active_file, {**pointer, 'configurationFile': str(path),
                                           'configurationSha256': install.sha(path), 'updatedAtUnix': int(time.time())})

    # --- operations ---
    def plan(self, ipv4):
        pointer, path, config = self.active()
        state = self.state()
        require(not state or state['mode'] == 'private', 'Public ingress is already recorded; use --verify or --revert.')
        require(not self.unfinished_upgrade(), 'An unfinished upgrade owns the runtime.')
        check_ipv4(ipv4, host_ipv4_addresses(self.run))
        caddyfile = Path(next(m['source'] for m in config['services']['caddy']['volumes'] if m['target'] == CADDYFILE_TARGET))
        text = public_caddyfile(caddyfile.read_text())
        target = self.directory / pointer['revision'][:12] / 'Caddyfile.public'
        public = derive_public(config, target, ipv4)
        dns_points_here(ipv4, self.resolve)
        return {'mode': 'plan', 'revision': pointer['revision'], 'privateConfigurationSha256': pointer['configurationSha256'],
                'publicIpv4': ipv4, 'changedServices': ['caddy'], 'publishedPorts': ['80/tcp', '443/tcp'],
                'caddyfileSha256': sha(text.encode()), 'registration': 'closed before publication',
                'productionReady': False}, (pointer, path, config, public, target, text)

    def apply(self, ipv4, owner):
        result, (pointer, path, config, public, target, text) = self.plan(ipv4)
        source = path.parent / 'candidate'
        recovery = self.gate_open(source, path)
        self.registration(config, 'closed')
        self.registration(config, 'closed', check=True)
        target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        target.write_text(text)
        target.chmod(0o644)
        public_file = target.with_name('compose.public.json')
        public_file.write_text(json.dumps(public, indent=2, sort_keys=True) + '\n')
        public_file.chmod(0o600)
        validate_public(config, json.loads(public_file.read_text()), target, ipv4)
        record = {'mode': 'applying', 'revision': pointer['revision'], 'publicIpv4': ipv4, 'ownerReference': owner,
                  'privateConfigurationFile': str(path), 'privateConfigurationSha256': pointer['configurationSha256'],
                  'publicConfigurationFile': str(public_file), 'publicConfigurationSha256': install.sha(public_file),
                  'registration': 'closed', 'startedAtUnix': int(time.time())}
        self.write_state(record)
        try:
            self.compose(public_file, 'up', '-d', '--no-deps', '--force-recreate', '--pull', 'never', 'caddy')
            self.point_active(pointer, public_file)
            deadline = self.clock() + CERTIFICATE_BUDGET
            while True:
                try:
                    public_edge_ready(ipv4, self.get)
                    break
                except (OSError, ssl.SSLError, ValueError, trust.Refused, http.client.HTTPException):
                    require(self.clock() < deadline, 'The public certificate and issuer were not verified in time.')
                    self.sleep(10)
        except BaseException:
            self.private_edge(pointer, path, record, 'reverted-after-failure')
            raise
        require(recovery.gate('check') == b'open', 'The recovery gate closed during the transition.')
        record.update(mode='public', appliedAtUnix=int(time.time()))
        self.write_state(record)
        return {**result, 'mode': 'public', 'registration': 'closed'}

    def private_edge(self, pointer, path, record, mode):
        self.compose(path, 'up', '-d', '--no-deps', '--force-recreate', '--pull', 'never', 'caddy')
        control.durable(self.active_file, {**pointer, 'configurationFile': str(path),
                                           'configurationSha256': install.sha(path), 'updatedAtUnix': int(time.time())})
        record.update(mode=mode, revertedAtUnix=int(time.time()))
        self.write_state(record)

    def revert(self, owner):
        record = self.state()
        require(record and record['mode'] in ('public', 'applying'), 'No public ingress is recorded.')
        pointer = json.loads(self.active_file.read_text())
        path = Path(record['privateConfigurationFile'])
        require(install.sha(path) == record['privateConfigurationSha256'], 'The private configuration changed.')
        record['revertReference'] = owner
        self.private_edge({**pointer, 'revision': record['revision']}, path, record, 'private')
        return {'mode': 'private', 'registration': record['registration']}

    def verify(self):
        record = self.state()
        pointer, path, config = self.active()
        if not record or record['mode'] != 'public':
            require(not config['services']['caddy'].get('ports'), 'Unrecorded published edge.')
            return {'mode': 'private', 'productionReady': False}
        require(str(path) == record['publicConfigurationFile'] and pointer['configurationSha256'] == record['publicConfigurationSha256'],
                'ACTIVE.json does not name the recorded public configuration.')
        public_edge_ready(record['publicIpv4'], self.get)
        self.registration(config, record['registration'], check=True)
        return {'mode': 'public', 'publicIpv4': record['publicIpv4'], 'registration': record['registration'],
                'productionReady': False}

    def open_registration(self, owner):
        record = self.state()
        require(record and record['mode'] == 'public', 'Registration opens only on a verified public edge.')
        pointer, path, config = self.active()
        source = Path(record['privateConfigurationFile']).parent / 'candidate'
        self.gate_open(source, path)
        public_edge_ready(record['publicIpv4'], self.get)
        self.registration(config, 'open')
        self.registration(config, 'open', check=True)
        record.update(registration='open', registrationOpenedAtUnix=int(time.time()), registrationReference=owner)
        self.write_state(record)
        return {'mode': 'public', 'registration': 'open'}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    action = parser.add_mutually_exclusive_group(required=True)
    for name in ('plan', 'apply', 'verify', 'revert', 'open-registration'):
        action.add_argument('--' + name, action='store_true')
    parser.add_argument('--public-ipv4')
    parser.add_argument('--owner-reference')
    args = parser.parse_args()
    require(os.geteuid() == 0, 'Run as root on the production host.')
    os.umask(0o077)
    ingress = Ingress()
    if args.plan or args.apply:
        require(args.public_ipv4, '--public-ipv4 is required.')
    if args.apply or args.revert or args.open_registration:
        require(args.owner_reference and len(args.owner_reference) <= 128, '--owner-reference is required.')
    if args.plan:
        value = ingress.plan(args.public_ipv4)[0]
    elif args.apply:
        value = ingress.apply(args.public_ipv4, args.owner_reference)
    elif args.verify:
        value = ingress.verify()
    elif args.revert:
        value = ingress.revert(args.owner_reference)
    else:
        value = ingress.open_registration(args.owner_reference)
    print(json.dumps(value, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except trust.Refused as error:
        print(json.dumps({'mode': 'refused', 'reason': str(error)}), file=sys.stderr)
        raise SystemExit(1)
