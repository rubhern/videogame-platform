"""Private production admin ingress contract and scoped host installation (#464).

No default mutation; --install requires a published operational source and owner policy
acknowledgement. Runtime changes belong exclusively to private_production_upgrade.py.
"""
import argparse
import copy
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import socket
import sys
import tempfile
import urllib.request
import urllib.error

HOST = 'gameometro-prod.tailca0b11.ts.net'
GRAFANA = f'https://{HOST}:8443/'
KEYCLOAK = f'https://{HOST}:10000'
EDGE = '10.203.1.2'
ENDPOINTS = {'grafana': (8443, 18445), 'keycloak': (10000, 18446)}
STATE = Path('/var/lib/gameometro/production/admin-ingress.json')
UNITS = Path('/etc/systemd/system')


def environment(name):
    return {'grafana': {'GF_SERVER_ROOT_URL': GRAFANA, 'GF_SECURITY_COOKIE_SECURE': 'true'},
            'keycloak': {'KC_HOSTNAME_ADMIN': KEYCLOAK}}.get(name, {})


def adapt(config):
    """Private adapter only: no public candidate or runtime-input change."""
    for name in ENDPOINTS:
        if name in config['services']:  # Small preparation fixtures may omit identity.
            config['services'][name].setdefault('environment', {}).update(environment(name))
    return config


def allowed_setting(name, key, before, after):
    """Exact upgrade exception; never a general relaxation of KC_* or existing settings."""
    if key not in environment(name) or after != environment(name)[key]:
        return False
    return before in (None, after, 'http://localhost:3000/' if key == 'GF_SERVER_ROOT_URL' else None)


def caddy():
    # Bind existing fixed edge IP, never wildcard/LAN. Host access is proven at install.
    # No edge_admission: it rejects the bridge gateway. Repeat its recovery checks here.
    gate = '''
        @closed {
            not file {
                root /run/recovery
                try_files /READY
            }
        }
        respond @closed 503
        @pending file {
            root /run/recovery
            try_files /PENDING.json
        }
        respond @pending 503
        @identity file {
            root /run/recovery
            try_files /IDENTITY-MAINTENANCE.json
        }
        respond @identity 503
'''
    result = '\n# Owner-only administration; appended only by the private adapter (#464).\n'
    for name, (port, backend) in ENDPOINTS.items():
        origin = f'{HOST}:{port}'
        paths = '' if name == 'grafana' else '''
        @allowed path /admin /admin/* /resources/* /realms/master/.well-known/openid-configuration /realms/master/protocol/openid-connect/* /realms/master/login-actions/*
        handle @allowed {
'''
        proxy = f'''            reverse_proxy {name}:{3000 if name == 'grafana' else 8080} {{
                import upstream_headers
                header_up Host {origin}
                header_up X-Forwarded-Host {origin}
                header_up X-Forwarded-Proto https
                header_up X-Forwarded-Port {port}
            }}
'''
        if paths:
            proxy = paths + proxy + '        }\n        respond 404\n'
        result += f'''http://:{backend} {{
    bind {EDGE}
    header -Server
    request_body {{
        max_size 1MB
    }}
    route {{
        # Only host-originated traffic through this Docker gateway may use this listener.
        @notBridge not remote_ip 10.203.1.1
        respond @notBridge 403
        @wrongHost not host {HOST}
        respond @wrongHost 421
{gate}{proxy}    }}
}}
'''
    return result


def unit_files(script):
    units = {}
    for name, (_, backend) in ENDPOINTS.items():
        units[f'gameometro-admin-{name}.service'] = f'''[Unit]
Description=Gameometro private {name} loopback bridge
After=docker.service
Wants=docker.service
StartLimitIntervalSec=0

[Service]
DynamicUser=yes
ExecStart=/usr/bin/socat TCP4-LISTEN:{backend},bind=127.0.0.1,reuseaddr,fork,max-children=16 TCP4:{EDGE}:{backend},bind=10.203.1.1,connect-timeout=5
Restart=always
RestartSec=5
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
RestrictAddressFamilies=AF_INET
CapabilityBoundingSet=
MemoryMax=32M
TasksMax=32
LimitNOFILE=128
StandardOutput=null
StandardError=null

[Install]
WantedBy=multi-user.target
'''
    units['gameometro-admin-serve.service'] = f'''[Unit]
Description=Gameometro private admin Tailscale Serve reconciliation
After=tailscaled.service docker.service gameometro-admin-grafana.service gameometro-admin-keycloak.service
Requires=tailscaled.service gameometro-admin-grafana.service gameometro-admin-keycloak.service
StartLimitIntervalSec=0

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 {script} --reconcile
RemainAfterExit=yes
Restart=on-failure
RestartSec=15
NoNewPrivileges=yes
ProtectSystem=strict
ReadWritePaths=/var/lib/gameometro/production/admin-ingress.lock
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6

[Install]
WantedBy=multi-user.target
'''
    return units


def command(*args):
    return subprocess.run(args, check=True, capture_output=True, timeout=45).stdout


def status():
    value = command('/usr/bin/tailscale', 'serve', 'status', '--json')
    if len(value) > 1024 * 1024:
        raise ValueError('Serve state exceeds bound')
    return json.loads(value)


def checked(value):
    """Refuse conflicts before any mutation, including foreground routes and all Funnel."""
    if value.get('Foreground') or any(value.get('AllowFunnel', {}).values()):
        raise ValueError('Foreground routes or Funnel present; reconcile manually first')
    for port, backend in ENDPOINTS.values():
        tcp = value.get('TCP', {}).get(str(port))
        web = value.get('Web', {}).get(f'{HOST}:{port}')
        expected = {'Handlers': {'/': {'Proxy': f'http://127.0.0.1:{backend}'}}}
        if tcp not in (None, {'HTTPS': True}) or web not in (None, expected):
            raise ValueError('Conflicting admin route; inspect and retire the temporary route explicitly')
        if (tcp is None) != (web is None):
            raise ValueError('Incomplete admin Serve route; inspect before reconciliation')
        if any(k.endswith(f':{port}') and k != f'{HOST}:{port}' for k in value.get('Web', {})):
            raise ValueError('Unexpected hostname on admin port')
    return value


def unrelated(value):
    result = copy.deepcopy(value)
    for port, _ in ENDPOINTS.values():
        result.get('TCP', {}).pop(str(port), None)
        result.get('Web', {}).pop(f'{HOST}:{port}', None)
    return {k: v for k, v in result.items() if v}


def routes(remove=False):
    before = checked(status())
    for port, backend in ENDPOINTS.values():
        if remove:
            if str(port) in before.get('TCP', {}):
                command('/usr/bin/tailscale', 'serve', f'--https={port}', 'off')
        else:
            command('/usr/bin/tailscale', 'serve', '--bg', '--yes', f'--https={port}', f'http://127.0.0.1:{backend}')
    after = checked(status())
    if unrelated(before) != unrelated(after):
        raise ValueError('Unrelated Serve state changed; inspect before continuing')
    for port, backend in ENDPOINTS.values():
        actual = after.get('Web', {}).get(f'{HOST}:{port}')
        expected = None if remove else {'Handlers': {'/': {'Proxy': f'http://127.0.0.1:{backend}'}}}
        if actual != expected or (str(port) in after.get('TCP', {})) == remove:
            raise ValueError('Serve postcondition failed; rerun scoped reconciliation')


def active():
    import install_private_production as install
    pointer = install.ADMIN / 'upgrades/ACTIVE.json'
    install.secure(pointer, mode=0o600)
    info = json.loads(pointer.read_text())
    path = Path(info['configurationFile'])
    install.secure(path, mode=0o600)
    if install.sha(path) != info['configurationSha256']:
        raise ValueError('Active runtime fingerprint differs')
    journal = install.ADMIN / 'upgrades' / info['revision'] / 'upgrade.json'
    install.secure(journal, mode=0o600)
    if json.loads(journal.read_text())['status'] != 'complete':
        raise ValueError('Upgrade not complete')
    config = json.loads(path.read_text())
    for name in ENDPOINTS:
        if any(config['services'][name]['environment'].get(k) != v for k, v in environment(name).items()):
            raise ValueError('Upgrade the immutable runtime before enabling admin ingress')
    mounts = config['services']['caddy']['volumes']
    edge = Path(next(m['source'] for m in mounts if m['target'] == '/etc/caddy/Caddyfile'))
    install.secure(edge, mode=0o644)
    if not edge.read_text().endswith(caddy()):
        raise ValueError('Active edge lacks the reviewed private admin routes')
    return config


def probe():
    for name, (port, backend) in ENDPOINTS.items():
        path = '/login' if name == 'grafana' else '/admin/master/console/'
        request = urllib.request.Request(f'http://127.0.0.1:{backend}{path}', headers={'Host': f'{HOST}:{port}'})
        # Do not follow any upstream redirect, or leak a probe to public DNS.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args):
                return None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            response = opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if response.code not in (200, 302, 303):
                raise ValueError('Loopback bridge/edge gate not ready')
            if name == 'keycloak' and response.code == 200:
                body = response.read(1024 * 1024).decode()
                if not re.search(r'"serverBaseUrl"\s*:\s*"' + re.escape(KEYCLOAK) + r'"', body):
                    raise ValueError('Master realm frontend has not been aligned by the upgrade')


def write_unit(path, data):
    """Atomic unit replacement: interruption cannot leave a truncated unit to adopt."""
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, prefix=path.name + '.', suffix='.pending', delete=False) as stream:
        stream.write(data)
        stream.flush()
        os.fchmod(stream.fileno(), 0o644)
        os.fsync(stream.fileno())
        temporary = Path(stream.name)
    os.replace(temporary, path)
    import private_production_control as control
    control.fsync_dir(path.parent)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    for name in ('install', 'remove', 'verify', 'reconcile'):
        modes.add_argument('--' + name, action='store_true')
    parser.add_argument('--operational-revision')
    parser.add_argument('--adapter-fingerprint')
    parser.add_argument('--owner-reference')
    parser.add_argument('--policy-sha256', help='SHA256 of owner-reviewed effective tailnet policy; acknowledgement, not ACL proof')
    args = parser.parse_args()
    import install_private_production as install
    import private_dev_promotion as trust
    os.umask(0o077)
    try:
        install.require(os.geteuid() == 0 and sys.platform == 'linux', 'Linux root operation required.')
        install.secure(install.ADMIN, mode=0o700, directory=True)
        # Serialize host ingress operations; upgrades retain their independent recovery lease.
        with (install.ADMIN / 'admin-ingress.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            units = unit_files(Path(__file__).resolve())
            if args.install:
                install.require(args.owner_reference and re.fullmatch('[0-9a-f]{64}', args.policy_sha256 or ''),
                                'Owner reference and effective-policy hash required.')
                api = trust.GitHub(install.run(['gh', 'auth', 'token']).decode().strip())
                install.verify_operational(api, args.operational_revision, args.adapter_fingerprint)
                install.secure(Path(__file__).resolve(), mode=0o644)
                active()
                node = json.loads(command('/usr/bin/tailscale', 'status', '--json'))
                install.require(node['Self']['DNSName'].rstrip('.') == HOST, 'Unexpected tailnet node.')
                checked(status())
                # Preflight every owned file before writing any. Never overwrite another unit.
                for name, data in units.items():
                    path = UNITS / name
                    if path.exists() or path.is_symlink():
                        install.secure(path, mode=0o644)
                        install.require(path.read_text() == data, 'Existing unit differs; reviewed removal required.')
                if STATE.exists():
                    install.secure(STATE, mode=0o600)
                for name, (_, backend) in ENDPOINTS.items():
                    if not (UNITS / f'gameometro-admin-{name}.service').exists():
                        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
                            listener.bind(('127.0.0.1', backend))
                command('/usr/bin/socat', '-V')
                for name, data in units.items():
                    path = UNITS / name
                    write_unit(path, data)
                install.control.durable(STATE, {'script': str(Path(__file__).resolve()), 'policySha256': args.policy_sha256,
                                              'ownerReference': args.owner_reference})
                command('/usr/bin/systemctl', 'daemon-reload')
                for name in ENDPOINTS:
                    command('/usr/bin/systemctl', 'enable', '--now', f'gameometro-admin-{name}.service')
                for name in ENDPOINTS:
                    command('/usr/bin/systemctl', 'is-active', f'gameometro-admin-{name}.service')
                probe()  # Real host -> fixed edge -> Docker DNS, before enabling Serve.
                routes()
                # Reconcile after reboot, without running a second copy while holding this lock.
                command('/usr/bin/systemctl', 'enable', 'gameometro-admin-serve.service')
                for name in units:
                    command('/usr/bin/systemctl', 'is-enabled', name)
            else:
                if args.remove and not STATE.exists() and not any((UNITS / name).exists() for name in units):
                    value = checked(status())
                    install.require(not any(str(port) in value.get('TCP', {}) for port, _ in ENDPOINTS.values()),
                                    'Unowned admin routes remain; inspect manually.')
                    print(json.dumps({'outcome': 'already-removed', 'productionReady': False}))
                    return 0
                install.secure(STATE, mode=0o600)
                install.require(json.loads(STATE.read_text())['script'] == str(Path(__file__).resolve()), 'Use the installed operator source.')
                for name, data in units.items():
                    if args.remove and not (UNITS / name).exists() and not (UNITS / name).is_symlink():
                        continue
                    install.secure(UNITS / name, mode=0o644)
                    install.require((UNITS / name).read_text() == data, 'Installed unit differs.')
                if args.remove:
                    install.require(args.owner_reference, 'Owner removal reference required.')
                    if (UNITS / 'gameometro-admin-serve.service').exists():
                        command('/usr/bin/systemctl', 'disable', '--now', 'gameometro-admin-serve.service')
                    routes(remove=True)
                    for name in ENDPOINTS:
                        if (UNITS / f'gameometro-admin-{name}.service').exists():
                            command('/usr/bin/systemctl', 'disable', '--now', f'gameometro-admin-{name}.service')
                    for name in units:
                        (UNITS / name).unlink(missing_ok=True)
                    command('/usr/bin/systemctl', 'daemon-reload')
                    STATE.unlink()
                else:
                    active()
                    for name in units:
                        command('/usr/bin/systemctl', 'is-enabled', name)
                    for name in ENDPOINTS:
                        command('/usr/bin/systemctl', 'is-active', f'gameometro-admin-{name}.service')
                    probe()
                    if args.reconcile:
                        routes()
                    else:
                        value = checked(status())
                        for port, _ in ENDPOINTS.values():
                            install.require(value.get('TCP', {}).get(str(port)) == {'HTTPS': True}
                                            and f'{HOST}:{port}' in value.get('Web', {}), 'Serve route missing.')
        print(json.dumps({'outcome': 'verified' if args.verify else 'applied', 'productionReady': False}))
        return 0
    except Exception as error:
        reason = str(error) if isinstance(error, (ValueError, trust.Refused)) else 'Host command or protected input failed; inspect locally.'
        print(json.dumps({'outcome': 'refused-or-failed', 'reason': reason, 'advice': 'Inspect prerequisites and owned routes locally; rerun or remove only admin ingress.'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
