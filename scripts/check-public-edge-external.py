#!/usr/bin/env python3
"""External, read-only acceptance probe of the public edge (#276 phase 5).

Run from a network outside the VPS and outside the tailnet path, against the public address:
  python3 scripts/check-public-edge-external.py --ipv4 A [--ipv6 B] --receipt new.json

Checks public certificates for both names, HTTP to HTTPS redirection, the anonymous catalogue,
refused personal routes, the issuer contract, the closed admin console, denied management and
database ports on IPv4 and IPv6, and that the per-source search limit returns 429 even when every
request forges X-Forwarded-For. The burst is bounded (2 x 25 GETs of one search) and stays far
below the aggregate zones. No account, rating or registration is created. The receipt holds
statuses only.
"""
import argparse
import datetime as dt
import http.client
import json
import socket
import ssl
import sys
import time

APP, AUTH = 'gameometro.com', 'auth.gameometro.com'
ISSUER = f'https://{AUTH}/realms/gameometro'
DENIED_PORTS = (2375, 2376, 3000, 5432, 8080, 8081, 8443, 9090, 9100, 10000)
SEARCH = '/api/v1/games?q=zelda'
BURST = 25  # The search zone admits 20 per 10 s per source.


def get(address, host, path, *, port=443, headers=None, tls=True, timeout=15):
    raw = socket.create_connection((address, port), timeout=timeout)
    if tls:
        context = ssl.create_default_context()
        raw = context.wrap_socket(raw, server_hostname=host)
    connection = (http.client.HTTPSConnection if tls else http.client.HTTPConnection)(host, port, timeout=timeout)
    connection.sock = raw
    try:
        connection.request('GET', path, headers={'Host': host, **(headers or {})})
        response = connection.getresponse()
        body = response.read(262145)
        return response.status, dict(response.getheaders()), body, (raw.getpeercert() if tls else None)
    finally:
        connection.close()


def certificate_days(certificate):
    expires = dt.datetime.strptime(certificate['notAfter'], '%b %d %H:%M:%S %Y %Z').replace(tzinfo=dt.timezone.utc)
    return (expires - dt.datetime.now(dt.timezone.utc)).days


def closed(address, port, timeout=4):
    family = socket.AF_INET6 if ':' in address else socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM) as probe:
        probe.settimeout(timeout)
        return probe.connect_ex((address, port)) != 0


def burst(address, forged):
    statuses = []
    for index in range(BURST):
        headers = {'X-Forwarded-For': f'198.51.100.{index + 1}', 'X-Real-Ip': f'198.51.100.{index + 1}'} if forged else {}
        statuses.append(get(address, APP, SEARCH, headers=headers)[0])
    return statuses


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--ipv4', required=True)
    parser.add_argument('--ipv6')
    parser.add_argument('--receipt', required=True)
    args = parser.parse_args()
    checks, failures = {}, []

    def check(name, condition, detail=None):
        checks[name] = {'ok': bool(condition), **({'detail': detail} if detail is not None else {})}
        if not condition:
            failures.append(name)

    for host in (APP, AUTH):
        try:
            status, _, _, certificate = get(args.ipv4, host, '/' if host == APP else '/realms/gameometro/.well-known/openid-configuration')
            check(f'tls:{host}', status in (200, 302, 303) and certificate_days(certificate) >= 7,
                  {'status': status, 'daysLeft': certificate_days(certificate)})
        except (OSError, ssl.SSLError) as error:
            check(f'tls:{host}', False, type(error).__name__)
        status, headers, _, _ = get(args.ipv4, host, '/', port=80, tls=False)
        check(f'redirect:{host}', status in (301, 308) and headers.get('Location', '').startswith(f'https://{host}'),
              {'status': status})
    check('catalogue', get(args.ipv4, APP, '/api/v1/releases?page=1&pageSize=1')[0] == 200)
    check('personal-refused', get(args.ipv4, APP, '/api/v1/me/ratings')[0] == 401)
    status, _, body, _ = get(args.ipv4, AUTH, '/realms/gameometro/.well-known/openid-configuration')
    check('issuer', status == 200 and json.loads(body).get('issuer') == ISSUER)
    check('admin-console-closed', get(args.ipv4, AUTH, '/admin/master/console/')[0] in (403, 404))
    check('master-realm-closed', get(args.ipv4, AUTH, '/realms/master/.well-known/openid-configuration')[0] in (403, 404))
    for address in filter(None, (args.ipv4, args.ipv6)):
        open_ports = [port for port in DENIED_PORTS if not closed(address, port)]
        check(f'denied-ports:{address}', not open_ports, open_ports)
        checks[f'ssh-exposed:{address}'] = {'ok': None, 'detail': not closed(address, 22)}
    plain = burst(args.ipv4, False)
    check('search-limit', 429 in plain and plain[0] == 200, {'429': plain.count(429)})
    time.sleep(11)
    forged = burst(args.ipv4, True)
    check('search-limit-ignores-forged-headers', 429 in forged and forged[0] == 200, {'429': forged.count(429)})
    receipt = {'kind': 'public-edge-external-check', 'checkedAt': dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds'),
               'outcome': 'success' if not failures else 'failure', 'failures': failures, 'checks': checks}
    with open(args.receipt, 'x') as stream:
        json.dump(receipt, stream, indent=2, sort_keys=True)
    print(json.dumps({'outcome': receipt['outcome'], 'failures': failures}))
    return 0 if not failures else 1


if __name__ == '__main__':
    sys.exit(main())
