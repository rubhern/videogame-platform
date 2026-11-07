#!/usr/bin/env python3
"""Opt-in notice migration proof on disposable Keycloak, no VPS or published ports."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
PYTHON = 'python:3.12.15-slim-bookworm@sha256:34386ef0cb081344d7ec1c103ba398e6e9f64e9ab3a1509accc92a4e24a07258'
PROBE = r'''
import importlib.machinery,importlib.util,json,pathlib,sys,time,urllib.request
sys.dont_write_bytecode=True
sys.path.insert(0,'/repo/scripts')
import production_registration_notice as n
loader=importlib.machinery.SourceFileLoader('identity','/repo/deploy/private-dev/bin/configure-keycloak-identity')
spec=importlib.util.spec_from_loader(loader.name,loader);i=importlib.util.module_from_spec(spec);loader.exec_module(i)
for attempt in range(90):
 try:
  urllib.request.urlopen('http://keycloak:8080/realms/master/.well-known/openid-configuration',timeout=2).close();break
 except Exception: time.sleep(1)
else: raise RuntimeError('Disposable Keycloak did not become ready')
a=i.provisioning.KeycloakAdmin('http://keycloak:8080');a.authenticate('synthetic',pathlib.Path('/work/password').read_text())
legacy='<p>Gameómetro es un servicio gratuito y sin fines comerciales para personas adultas. Para crear tu cuenta solo te pedimos tu email.</p>'
assert n.digest(legacy)==n.LEGACY
a._request('POST','/admin/realms',body={'realm':'gameometro','enabled':True,'registrationAllowed':False,
 'localizationTexts':{'es':{'termsText':legacy,'keep':'preserved'},'fr':{'keep':'preserved'}}},expected={201})
r=n.BoundedRealm(i.Realm(a,'gameometro'));target=n.notice(json.loads((n.ROOT/n.IMPORT).read_text()));path=pathlib.Path('/work/notice.json')
try: n.align(r,target,path,check=True)
except ValueError: pass
else: raise AssertionError('Old notice passed a read-only check')
assert not path.exists()
# Simulate a lost response AFTER an actual durable Keycloak localization PUT.
put=r.set_localization
writes=[]
def interrupted(key,value):
 writes.append(key);put(key,value);raise RuntimeError('synthetic lost acknowledgement')
r.set_localization=interrupted
try: n.align(r,target,path)
except RuntimeError: pass
else: raise AssertionError('Lost acknowledgement was not exercised')
saved=path.read_bytes();r.realm.send('PUT','',{'rememberMe':True},{204});r.set_localization=lambda *args: (_ for _ in ()).throw(AssertionError('Repeated PUT'))
n.align(r,target,path);n.align(r,target,path,check=True);n.align(r,target,path)
assert path.read_bytes()==saved and writes==['termsText']
assert n.localizations(r)=={'es':{'termsText':target.replace('https://${APPLICATION_PUBLIC_HOST}','https://gameometro.com'),'keep':'preserved'},'fr':{'keep':'preserved'}}
assert r.representation()['registrationAllowed'] is False and r.representation()['rememberMe'] is True
# Check that later drift cannot be accepted as a new baseline.
put('keep','unexpected')
try: n.align(r,target,path)
except ValueError: pass
else: raise AssertionError('Unrelated localization drift was adopted')
print('Disposable real-Keycloak notice apply, lost acknowledgement, resume, check, repeat and drift rejection passed.')
'''


def command(*args, timeout=180):
    result = subprocess.run(list(map(str, args)), capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Disposable notice proof failed; inspect the isolated fixture.')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keycloak-image', required=True)
    args = parser.parse_args()
    integration.verify_local_docker()
    if '@sha256:' not in args.keycloak_image:
        parser.error('A locally available immutable Keycloak image is required')
    command('docker', 'image', 'inspect', args.keycloak_image)
    command('docker', 'image', 'inspect', PYTHON)
    prefix = 'notice276-' + secrets.token_hex(6)
    network, container = prefix + '-network', prefix + '-keycloak'
    with tempfile.TemporaryDirectory(prefix='notice276-') as temporary:
        work = Path(temporary)
        password = secrets.token_urlsafe(24)
        (work / 'password').write_text(password)
        (work / 'password').chmod(0o600)
        try:
            command('docker', 'network', 'create', '--internal', network)
            command('docker', 'run', '--pull', 'never', '-d', '--name', container, '--network', network,
                    '--network-alias', 'keycloak', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                    '--memory', '768m', '--pids-limit', '256', '--entrypoint', '/opt/keycloak/bin/kc.sh',
                    '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=synthetic', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=' + password,
                    '--tmpfs', '/opt/keycloak/data:uid=1000,gid=0', args.keycloak_image,
                    'start-dev', '--db=dev-file', '--hostname-strict=false')
            output = command('docker', 'run', '--pull', 'never', '--rm', '--name', prefix + '-probe', '--network', network,
                             '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '128m',
                             '--pids-limit', '32', '--user', f'{os.getuid()}:{os.getgid()}',
                             '-v', f'{ROOT}:/repo:ro', '-v', f'{work}:/work', PYTHON, 'python3', '-c', PROBE)
            print(output.decode().strip())
        finally:
            subprocess.run(['docker', 'rm', '-f', prefix + '-probe', container], capture_output=True, timeout=30)
            subprocess.run(['docker', 'network', 'rm', network], capture_output=True, timeout=30)


if __name__ == '__main__':
    main()
