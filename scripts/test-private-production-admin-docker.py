#!/usr/bin/env python3
"""Disposable #464 real Caddy/Keycloak/Grafana proof; no host ports or live state.

Requires existing locally built production Keycloak and Caddy images. Supply immutable
images with --caddy-image/--keycloak-image; never pulls or builds implicitly.
"""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time

import private_production_admin as admin
import prepare_private_production as prepare

ROOT = Path(__file__).resolve().parents[1]
PYTHON = 'python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258'
GRAFANA = 'grafana/grafana:13.2.2@sha256:ac461fb352abc50da10a51c7d02462e9c05488f11f53f14b3ad79a8145f638a0'
PROBE = r'''
import base64,hashlib,html,http.cookiejar,json,re,ssl,urllib.request,urllib.error,urllib.parse
from pathlib import Path
host=__HOST__
admin='https://'+host+':10000'
grafana='https://'+host+':8443'
password=Path('/test/password').read_text()
ctx=ssl.create_default_context(cafile='/test/ca.crt')
class Stop(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args): return None
def client(cookies=None):
 return urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ctx),Stop(),urllib.request.HTTPCookieProcessor(cookies if cookies is not None else http.cookiejar.CookieJar()))
def request(opener,url,data=None,headers=None):
 if isinstance(data,dict): data=urllib.parse.urlencode(data).encode()
 try: r=opener.open(urllib.request.Request(url,data=data,headers=headers or {}),timeout=15)
 except urllib.error.HTTPError as e: r=e
 with r: return r.code,dict(r.headers),r.read(2*1024*1024).decode()
op=client()
for backend in (18445,18446):
 status,_,_=request(op,'http://edge:'+str(backend)+'/admin/realms',headers={'Host':host+':10000','X-Forwarded-For':'10.203.1.1'})
 assert status==403,status
# Actual canonical public allowlist, including spoofed forwarding headers and encodings.
for path in ['/admin','/admin/realms','/admin/master/console/','/realms/master/.well-known/openid-configuration','/realms/master/protocol/openid-connect/token','/%61dmin/realms','//admin/realms','/admin;foo/realms']:
 status,_,_=request(op,'https://auth.gameometro.com'+path,headers={'X-Forwarded-Host':host+':10000','X-Forwarded-Proto':'https','X-Forwarded-Port':'10000','Forwarded':'host='+host+':10000;proto=https'})
 assert status==404,(path,status)
status,_,_=request(op,'https://auth.gameometro.com/admin/realms',headers={'Host':host+':10000'})
assert status==421,status
status,_,body=request(op,'https://auth.gameometro.com/realms/gameometro/.well-known/openid-configuration')
assert status==200 and json.loads(body)['issuer']=='https://auth.gameometro.com/realms/gameometro'
status,_,_=request(op,admin+'/realms/gameometro/protocol/openid-connect/token')
assert status==404,status
status,_,_=request(op,admin+'/admin/realms')
assert status==401,status
status,_,body=request(op,admin+'/admin/master/console/')
assert status==200 and admin in body and 'https://auth.gameometro.com' not in body
# Native admin code flow with PKCE, secure cookies, token exchange and authenticated REST.
verifier='x'*64
challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
redirect=admin+'/admin/master/console/'
query=urllib.parse.urlencode({'client_id':'security-admin-console','redirect_uri':redirect,'response_type':'code','scope':'openid','state':'synthetic','nonce':'synthetic','code_challenge':challenge,'code_challenge_method':'S256'})
jar=http.cookiejar.CookieJar();browser=client(jar)
status,_,body=request(browser,admin+'/realms/master/protocol/openid-connect/auth?'+query)
assert status==200,status
form=html.unescape(re.search(r'<form[^>]+action="([^"]+)"',body)[1])
assert form.startswith(admin+'/realms/master/'),form
status,headers,body=request(browser,form,{'username':'owner','password':password,'credentialId':''})
assert status==302,(status,body[:100])
location=headers['Location'];assert location.startswith(redirect),location
code=urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)['code'][0]
status,_,body=request(browser,admin+'/realms/master/protocol/openid-connect/token',{'grant_type':'authorization_code','client_id':'security-admin-console','code':code,'redirect_uri':redirect,'code_verifier':verifier})
assert status==200,(status,body[:100])
token=json.loads(body)['access_token']
status,_,body=request(browser,admin+'/admin/realms',headers={'Authorization':'Bearer '+token})
assert status==200 and 'gameometro' in body
assert any(c.secure for c in jar), 'No Secure identity cookie'
# Real Grafana native authentication, redirect origin and secure session.
status,headers,_=request(op,grafana+'/')
assert status in (200,302) and (status==200 or headers['Location'].startswith(('/login',grafana)))
assert request(op,grafana+'/api/user')[0]==401
jar=http.cookiejar.CookieJar();browser=client(jar)
status,_,body=request(browser,grafana+'/login',json.dumps({'user':'owner','password':password}).encode(),{'Content-Type':'application/json'})
assert status==200,(status,body[:100])
assert request(browser,grafana+'/api/user')[0]==200
assert any(c.name=='grafana_session' and c.secure and c.has_nonstandard_attr('HttpOnly') for c in jar)
print('Native admin PKCE login/REST, Grafana login/cookies, canonical issuer and spoofed public denial passed.')
'''
SEED = r'''
import importlib.machinery,importlib.util,json,pathlib
loader=importlib.machinery.SourceFileLoader('alignment','/repo/deploy/public-production/bin/configure-keycloak-admin')
spec=importlib.util.spec_from_loader(loader.name,loader);m=importlib.util.module_from_spec(spec);loader.exec_module(m)
a=m.provisioning.KeycloakAdmin('http://keycloak:8080');a.authenticate('owner',pathlib.Path('/test/password').read_text())
a._request('POST','/admin/realms',body={'realm':'gameometro','enabled':True,'rememberMe':True},expected={201})
m.align(a,__ORIGIN__,pathlib.Path('/test/master.json'),reauthenticate=lambda: a.authenticate('owner',pathlib.Path('/test/password').read_text()))
m.align(a,__ORIGIN__,pathlib.Path('/test/master.json'),reauthenticate=lambda: a.authenticate('owner',pathlib.Path('/test/password').read_text()))
m.align(a,__ORIGIN__,pathlib.Path('/test/master.json'),check=True)
'''


def run(*args):
    result = subprocess.run([str(a) for a in args], capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError(result.stdout.decode()[:1500]+result.stderr.decode()[:1500])
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--caddy-image', required=True)
    parser.add_argument('--keycloak-image', required=True)
    args = parser.parse_args()
    for image in [args.caddy_image, args.keycloak_image, PYTHON, GRAFANA]:
        run('docker', 'image', 'inspect', image)
    prefix = 'admin464-' + secrets.token_hex(5)
    network = prefix + '-network'
    containers = []
    definitions = {}
    with tempfile.TemporaryDirectory(prefix='admin464-') as temporary:
        work = Path(temporary)
        work.chmod(0o755)
        (work / 'password').write_text(secrets.token_urlsafe(24))
        (work / 'password').chmod(0o644)  # Synthetic, isolated probe input only.
        password = (work / 'password').read_text()
        try:
            run('docker', 'network', 'create', '--internal', network)
            info = json.loads(run('docker', 'network', 'inspect', network))[0]
            subnet = ipaddress.ip_network(info['IPAM']['Config'][0]['Subnet'])
            edge = str(subnet.network_address + 2)
            def start(name, image, *arguments, aliases=(), options=()):
                definitions[name] = (image, arguments, aliases, options)
                container = prefix + '-' + name
                containers.append(container)
                return run('docker', 'run', '-d', '--name', container, '--network', network,
                           *[item for alias in (name, *aliases) for item in ('--network-alias', alias)],
                           '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', *options, image, *arguments)
            start('keycloak', args.keycloak_image, 'start-dev', '--db=dev-file', '--hostname=https://auth.gameometro.com',
                  '--hostname-admin='+admin.KEYCLOAK, '--proxy-headers=xforwarded', '--proxy-trusted-addresses='+edge,
                  options=('--ip', str(subnet.network_address+4), '--entrypoint', '/opt/keycloak/bin/kc.sh', '--memory', '768m', '--pids-limit', '256',
                           '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=owner', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD='+password,
                           '--tmpfs', '/opt/keycloak/data:uid=1000,gid=0'))
            start('grafana', GRAFANA, options=('--ip', str(subnet.network_address+5), '--memory', '320m', '--pids-limit', '128',
                  '-e', 'GF_SECURITY_ADMIN_USER=owner', '-e', 'GF_SECURITY_ADMIN_PASSWORD='+password,
                  '-e', 'GF_SERVER_ROOT_URL='+admin.GRAFANA, '-e', 'GF_SECURITY_COOKIE_SECURE=true',
                  '-e', 'GF_AUTH_ANONYMOUS_ENABLED=false', '-e', 'GF_PLUGINS_PREINSTALL_DISABLED=true',
                  '-e', 'GOMAXPROCS=2', '-e', 'GF_PLUGINS_DISABLE_PLUGINS=elasticsearch,grafana-pyroscope-datasource,influxdb,jaeger,loki,mssql,mysql,opentsdb,stackdriver,tempo,zipkin',
                  '--tmpfs', '/var/lib/grafana:uid=472,gid=0'))
            def python(script):
                return run('docker', 'run', '--rm', '--network', network, '--cap-drop', 'ALL',
                           '--security-opt', 'no-new-privileges:true', '--memory', '96m', '--pids-limit', '32',
                           '--user', f'{os.getuid()}:{os.getgid()}',
                           '-v', f'{ROOT}:/repo:ro', '-v', f'{work}:/test', PYTHON, 'python3', '-c', script)
            # Bounded readiness poll, no retry of the actual assertions.
            python("import time,urllib.request\nfor i in range(90):\n try:\n  urllib.request.urlopen('http://keycloak:8080/realms/master/.well-known/openid-configuration',timeout=2);break\n except Exception: time.sleep(1)\nelse: raise RuntimeError('Keycloak not ready')")
            python(SEED.replace('__ORIGIN__', repr(admin.KEYCLOAK)))
            public = (ROOT / 'deploy/public-production/caddy/Caddyfile').read_text()
            caddy = prepare.private_caddy(public).replace('tls /run/private-tls/edge.crt /run/private-tls/edge.key', 'tls internal')
            for address in [admin.EDGE, '10.203.3.2']:
                caddy = caddy.replace(address, edge)
            caddy = caddy.replace('@notBridge not remote_ip 10.203.1.1', '@notBridge not remote_ip '+edge)
            # TLS facades emulate Serve's termination, forwarding to the actual admin listeners.
            for name, (port, backend) in admin.ENDPOINTS.items():
                caddy += f'\nhttps://{admin.HOST}:{port} {{\n tls internal\n reverse_proxy {edge}:{backend}\n}}\n'
            (work / 'Caddyfile').write_text(caddy)
            (work / 'READY').touch()
            start('edge', args.caddy_image, 'caddy', 'run', '--config', '/etc/caddy/Caddyfile',
                  aliases=(admin.HOST, 'gameometro.com', 'auth.gameometro.com'),
                  options=('--ip', edge, '--cap-add', 'NET_BIND_SERVICE', '--memory', '128m',
                           '-v', f'{work}/Caddyfile:/etc/caddy/Caddyfile:ro', '-v', f'{work}:/run/recovery:ro'))
            root = '/data/caddy/pki/authorities/local/root.crt'
            for _ in range(30):
                result = subprocess.run(['docker', 'cp', f'{prefix}-edge:{root}', str(work / 'ca.crt')], capture_output=True)
                if result.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Edge did not start: '+run('docker', 'logs', prefix+'-edge').decode()[-3000:])
            print(python(PROBE.replace('__HOST__', repr(admin.HOST))).decode().strip())
            # Gates must also close the private routes, including an identity-only fence.
            for fence in ['PENDING.json', 'IDENTITY-MAINTENANCE.json']:
                (work / fence).write_text('{}')
                python("import ssl,urllib.request,urllib.error\nc=ssl.create_default_context(cafile='/test/ca.crt')\nfor url in "+repr([admin.GRAFANA+'login', admin.KEYCLOAK+'/admin/master/console/'])+":\n try: urllib.request.urlopen(url,context=c,timeout=5);raise AssertionError('Gate open')\n except urllib.error.HTTPError as e: assert e.code==503,e.code")
                (work / fence).unlink()
            # Force a different upstream address without changing the proxy or publishing a port.
            run('docker', 'rm', '-f', prefix+'-grafana')
            image, arguments, aliases, options = definitions['grafana']
            start('grafana', image, *arguments, aliases=aliases, options=tuple(str(subnet.network_address+7) if v == str(subnet.network_address+5) else v for v in options))
            python("import time,urllib.request\nfor i in range(45):\n try:\n  urllib.request.urlopen('http://grafana:3000/api/health',timeout=2);break\n except Exception: time.sleep(1)\nelse: raise RuntimeError('Grafana not ready')")
            python("import ssl,urllib.request,urllib.error\nc=ssl.create_default_context(cafile='/test/ca.crt')\ntry: urllib.request.urlopen("+repr(admin.GRAFANA+'api/user')+",context=c,timeout=10);raise AssertionError('Anonymous API open')\nexcept urllib.error.HTTPError as e: assert e.code==401,e.code")
            print('Private recovery/identity fences and upstream recreation at a different IP passed.')
        finally:
            for container in reversed(containers):
                subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
            subprocess.run(['docker', 'network', 'rm', network], capture_output=True)


if __name__ == '__main__':
    main()
