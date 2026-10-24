#!/usr/bin/env python3
"""Real synthetic cleanup through the production transport; no schedule or host operation."""
import argparse
import importlib.machinery
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import traceback
import textwrap
import public_production_integration as integration

ROOT = integration.ROOT
loader = importlib.machinery.SourceFileLoader('cleanup_adapter', str(ROOT/'deploy/public-production/bin/recovery-personal-data'))
spec = importlib.util.spec_from_loader(loader.name, loader)
adapter = importlib.util.module_from_spec(spec); loader.exec_module(adapter)
core = adapter.recovery
require = core.require


def monitoring(exercise):
    """Render the real overlay; isolate every network and trust only the disposable CA."""
    d = exercise.directory
    for name in ('grafana-admin-password','grafana-database-password','grafana-telegram-bot-token','grafana-telegram-chat-id'):
        p = d/'secrets'/name; p.write_text(os.urandom(24).hex()); p.chmod(0o640)
    (d/'metrics').mkdir(mode=0o755)
    env = d/'runtime.env'
    env.write_text(env.read_text()+'PRODUCTION_METRICS_DIR='+str(d/'metrics')+'\n')
    rendered = integration.command(['docker','compose','--env-file',env,'-f',integration.COMPOSE,
        '-f',ROOT/'deploy/public-production/compose.observability.yaml','--profile','*','config','--format','json'])
    config = integration.isolated_config(json.loads(rendered.stdout),exercise.project,d)
    for mount in config['services']['node-exporter']['volumes']:
        if mount.get('source')=='/': mount.pop('bind',None)
    content = (ROOT/'deploy/public-production/observability/blackbox.yaml').read_text()
    (d/'blackbox.yaml').write_text(content.replace('server_name:','ca_file: /run/pki/ca.crt\n        server_name:'))
    blackbox = config['services']['blackbox']
    blackbox['volumes'] = [m for m in blackbox['volumes'] if m['target']!='/etc/blackbox/config.yaml']
    blackbox['volumes'] += [integration.mount(d/'blackbox.yaml','/etc/blackbox/config.yaml'),integration.mount(d/'pki','/run/pki')]
    exercise.file.write_text(json.dumps(config))
    exercise.receipt['configurationSha256'] = hashlib.sha256(exercise.file.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--component', choices=('all','cleanup','telemetry'), default='all')
    parser.add_argument('--model-dir', type=Path, help='Existing approved checksum-verified model; never downloaded')
    parser.add_argument('--browser', action='store_true', help='Include the existing synthetic production browser journey')
    parser.add_argument('--evidence', required=True, type=Path)
    args = parser.parse_args()
    def interrupted(*_):
        raise core.Refused('Interrupted; keep private runtime evidence.')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    args.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    receipt = {'kind':'production-cleanup-rehearsal','outcome':'failure','productionReady':False,
               'scope':'local-synthetic-working-tree-transport-with-trusted-main-images','component':args.component,'checks':[]}
    exercise = ring = None
    with tempfile.TemporaryDirectory(prefix='vgp-cleanup-rehearsal-') as directory:
        try:
            integration.verify_local_docker()
            selected = integration.resolve(integration.trust.GitHub(integration.command(['gh','auth','token']).stdout.strip()), args.revision)
            receipt['provenance'] = selected
            receipt['runtimeSourceFingerprint'] = integration.deployment.local_configuration()
            receipt['sourceFiles'] = {p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in (
                'deploy/public-production/bin/recovery-personal-data','deploy/public-production/compose.yaml',
                'deploy/public-production/compose.observability.yaml','scripts/rehearse-production-cleanup.py')}
            for image in selected['images'].values(): integration.command(['docker','pull',image])
            info = json.loads(integration.command(['docker','image','inspect',selected['images']['application']]).stdout)[0]
            selected['applicationVersion'] = info['Config']['Labels']['org.opencontainers.image.version']
            work = Path(directory); d = work/'runtime'; d.mkdir()
            model = integration.verify_model(args.model_dir) if args.model_dir else None
            receipt['localizer'] = 'pending' if model else 'not-exercised-model-not-supplied'
            exercise = integration.Exercise(d,args.evidence,selected,model)
            exercise.prepare(); monitoring(exercise); exercise.stage_a()
            if args.component != 'cleanup':
                exercise.up('telemetry','prometheus')  # Producer starts with its real collector, not minutes of artificial export failure.
            receipt['renderedConfigurationSha256'] = exercise.receipt['configurationSha256']
            exercise.up('mailpit','keycloak','caddy','application')
            if model:
                exercise.up('catalogue-localizer')
                exercise.probe('python3','-',network='application-data',data='''import json,urllib.request
r=urllib.request.Request('http://catalogue-localizer:8092/translate',data=json.dumps({'text':'Explore a mysterious world and discover hidden treasures.'}).encode(),headers={'Content-Type':'application/json'})
v=json.load(urllib.request.urlopen(r,timeout=120))
assert v['revision'].startswith('opus-tcbig:') and 'mundo' in v['text'].lower() and 'tesoro' in v['text'].lower()
''')
                receipt['localizer'] = 'passed'
            if args.browser:
                exercise.stage_c()
                receipt['checks'].append('browser-signup-welcome-ownership-csrf-recovery-cookie-inventory')
            state = d/'recovery-journal'
            runtime = core.Runtime(exercise.file,exercise.project,state)
            password = d/'secrets/keycloak-admin-password'
            def connect():
                return adapter.runtime(exercise.file,exercise.project,state,'disposable-admin',password,core=runtime,identity_cleanup=True)
            if args.component != 'telemetry':
                target = connect()
                subjects = []
                for i in range(3):
                    username = f'cleanup-{i}@example.test'
                    target.keycloak('POST','/users',{'username':username,'email':username,'emailVerified':False,
                        'enabled':True,'firstName':'Synthetic','lastName':'Cleanup'})
                    subjects.append(target.keycloak('GET','/users?username='+username+'&exact=true&max=2')[0]['id'])
                # Fixture only: change ages while Keycloak is stopped to avoid cached representations.
                exercise.compose('stop','keycloak')
                exercise.sql("UPDATE user_entity SET created_timestamp=1 WHERE username LIKE 'cleanup-%@example.test';",'videogame_keycloak')
                exercise.up('keycloak'); target = connect()
                user = target.account(subjects[1])
                target.keycloak('PUT','/users/'+subjects[1],{**user,'emailVerified':True})
                owner = work/'owner'; owner.mkdir(mode=0o700)
                independent = work/'independent'; independent.mkdir(mode=0o700)
                for parent,name in ((owner,'archive'),(owner,'proof'),(owner,'holds'),(independent,'archive')):
                    (parent/name).mkdir(mode=0o700)
                ring = owner/'keyring'; ring.mkdir(mode=0o700)
                core.run(['gpg','--homedir',ring,'--batch','--faked-system-time',str(int(time.time())-300),
                    '--pinentry-mode','loopback','--passphrase','','--quick-generate-key','Synthetic cleanup','ed25519','sign','1d'])
                listing = core.run(['gpg','--homedir',ring,'--batch','--with-colons','--list-keys']).decode()
                fpr = next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
                core.run(['gpg','--homedir',ring,'--batch','--pinentry-mode','loopback','--passphrase','','--quick-add-key',fpr,'cv25519','encr','1d'])
                hold = owner/'holds'/(subjects[2]+'.json'); hold.write_text('{}'); hold.chmod(0o600)
                host = ['python3',ROOT/'deploy/public-production/bin/recovery-personal-data','--config',exercise.file,
                    '--project',exercise.project,'--state',state,'--admin-username','disposable-admin','--admin-password-file',password,'--identity-cleanup']
                maintenance = ['python3',ROOT/'deploy/public-production/bin/identity-maintenance','--config',exercise.file,'--project',exercise.project,'--state',state]
                fence = shlex.join(map(str,maintenance))
                ledger = ['python3','-I',ROOT/'deploy/private-dev/bin/personal-data-ledger','--ledger',owner/'ledger.jsonl',
                    '--ledger-copy',independent/'ledger.jsonl','--archive-root',owner/'archive','--archive-copy',independent/'archive',
                    '--proof-root',owner/'proof','--holds',owner/'holds','--gnupg-home',ring,'--recipient',fpr,'--signer',fpr]
                def drive(*commands, command=host, success=True):
                    result = integration.command([*ledger,'--host-command',shlex.join(map(str,command)),*commands],check=False)
                    require((result.returncode==0)==success,'Unexpected cleanup outcome; raw diagnostics withheld.')
                    return result
                drive('cleanup-unverified')
                require(not (owner/'ledger.jsonl').exists() and runtime.gate('maintenance-check')==b'none','Dry run changed state.')
                receipt['checks'].append('dry-run-no-mutation')
                # A real native Admin API verification update is already in flight when
                # maintenance enters. Hold the final body byte; no completed-command simulation.
                race = owner/'race'; race.mkdir(mode=0o700)
                user=target.account(subjects[0])
                held_script = """import http.client,json,pathlib,sys,time
q=json.load(sys.stdin);data=json.dumps(q['user']).encode();c=http.client.HTTPConnection('keycloak',8080,timeout=120)
c.putrequest('PUT','/admin/realms/gameometro/users/'+q['subject']);c.putheader('Authorization','Bearer '+q['token']);c.putheader('Content-Type','application/json');c.putheader('Content-Length',str(len(data)));c.endheaders();c.send(data[:-1]);pathlib.Path('/race/ready').touch()
deadline=time.monotonic()+120
while not pathlib.Path('/race/release').exists():
 if time.monotonic()>deadline:raise SystemExit(2)
 time.sleep(.1)
c.send(data[-1:]);assert c.getresponse().status==204
"""
                held=subprocess.Popen(['docker','run','--rm','-i','--label','vgp.production-test='+exercise.project,
                    '--network',json.loads(exercise.file.read_text())['networks']['identity-data']['name'],
                    '--user',str(os.getuid())+':'+str(os.getgid()),'--read-only','--cap-drop','ALL',
                    '--security-opt','no-new-privileges:true','--memory','96m','--pids-limit','32',
                    '-v',str(race)+':/race',adapter.CLIENT,'python3','-c',held_script],
                    stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                held.stdin.write(json.dumps({'subject':subjects[0],'token':target.admin.access_token,
                    'user':{**user,'emailVerified':True}}).encode());held.stdin.close()
                cleaner=None
                try:
                    deadline=time.monotonic()+30
                    maintenance_module=adapter.load('inflight_cleanup_maintenance',ROOT/'deploy/public-production/bin/identity-maintenance')
                    while not (race/'ready').exists() or maintenance_module.Maintenance(runtime).keycloak_active()==0:
                        require(held.poll() is None and time.monotonic()<deadline,'Native verification request did not enter flight.');time.sleep(.2)
                    cleaner=subprocess.Popen([*map(str,ledger),'--host-command',shlex.join(map(str,host)),
                        'cleanup-unverified','--execute','--maintenance-command',fence],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                    deadline=time.monotonic()+60
                    while runtime.gate('maintenance-check')==b'none':
                        require(cleaner.poll() is None and time.monotonic()<deadline,'Cleanup did not acquire its fence.');time.sleep(.2)
                    require(maintenance_module.Maintenance(runtime).keycloak_active()>0 and cleaner.poll() is None,'Drain did not wait for in-flight verification.')
                    require(exercise.sql("SELECT NOT email_verified AND enabled FROM user_entity WHERE id='"+subjects[0]+"';",'videogame_keycloak')=='t','Cleanup mutated an account while its verification was in flight.')
                    (race/'release').touch()
                    require(held.wait(timeout=30)==0 and cleaner.wait(timeout=120)==0,'In-flight verification/cleanup did not settle safely.')
                    target=connect();require(target.account(subjects[0])['emailVerified'] is True,'Cleanup removed the newly verified account.')
                    require(runtime.gate('maintenance-check')==b'none','In-flight cleanup did not reopen safely.')
                    receipt['checks'].append('native-verification-inflight-deny-drain-wait-and-preservation')
                finally:
                    (race/'release').touch()
                    if held.poll() is None:held.terminate()
                    held.wait(timeout=30)
                    if cleaner is not None and cleaner.poll() is None:cleaner.terminate();cleaner.wait(timeout=30)
                # Reset only the fixture for the independent lost-acknowledgement scenario.
                target=connect();user=target.account(subjects[0]);target.keycloak('PUT','/users/'+subjects[0],{**user,'emailVerified':False})
                fault = owner/'interrupt'; fault.touch(mode=0o600)
                wrapper = owner/'host.py'
                wrapper.write_text('import pathlib,subprocess,sys\ncommand='+repr(list(map(str,host)))+'\np=pathlib.Path('+repr(str(fault))+')\nr=subprocess.run(command+sys.argv[1:],capture_output=True)\nif sys.argv[1]=="revoke" and p.exists():\n p.unlink();sys.exit(1)\nsys.stdout.buffer.write(r.stdout);sys.exit(r.returncode)\n')
                drive('cleanup-unverified','--execute','--maintenance-command',fence,command=['python3',wrapper],success=False)
                target = connect()
                require(target.account(subjects[0])['enabled'] is False,'Interrupted revoke did not disable account.')
                require(runtime.gate('maintenance-check')==b'none','Interrupted cleanup did not settle the fence.')
                receipt['checks'].append('real-revocation-lost-acknowledgement-safe-exit')
                user = target.account(subjects[0])
                target.keycloak('PUT','/users/'+subjects[0],{**user,'emailVerified':True})
                drive('retry','--maintenance-command',fence,success=False)
                target = connect()
                require(target.account(subjects[0])['emailVerified'] is True,'Retry deleted newly verified account.')
                require(runtime.gate('maintenance-check')==b'none','Refused retry did not settle fence.')
                receipt['checks'].append('new-verification-race-refused')
                # Explicit test-only reconciliation, never performed by the transport.
                user = target.account(subjects[0])
                target.keycloak('PUT','/users/'+subjects[0],{**user,'emailVerified':False})
                drive('retry','--maintenance-command',fence)
                target = connect()
                require(target.account(subjects[0]) is None,'Retry did not erase eligible account.')
                require(target.account(subjects[1]) and target.account(subjects[2]),'Protected accounts disappeared.')
                require(runtime.gate('maintenance-check')==b'none','Successful retry did not reopen identity.')
                events = [json.loads(line) for line in (owner/'ledger.jsonl').read_text().splitlines()]
                reference = next(event['request'] for event in events if event['event']=='requested')
                drive('archive-verify','--request',reference)
                require((owner/'archive'/reference/'source.gpg').read_bytes()==(independent/'archive'/reference/'source.gpg').read_bytes(),'Archive copies differ.')
                exercise.probe('python3','-',data="import ssl,urllib.request; c=ssl.create_default_context(cafile='/run/pki/ca.crt'); assert urllib.request.urlopen('https://gameometro.com/',context=c,timeout=10).status==200")
                receipt['checks'].append('retry-archive-suppression-proof-and-safe-reopening')
            if args.component != 'cleanup':
                reader = (ROOT/'deploy/private-dev/grafana/provision-reader.sql').read_text()
                secret = (d/'secrets/grafana-database-password').read_text().strip()
                exercise.compose('exec','-T','postgres','gosu','postgres','psql','-Xq','-v','ON_ERROR_STOP=1','-d','videogame_platform',
                    data="\\set reader_password '"+secret+"'\n"+reader)
                exercise.up('telemetry','prometheus','grafana','node-exporter','blackbox')
                probe = textwrap.dedent('''
    import base64,json,time,urllib.request,urllib.parse,urllib.error
    def query(expr):
     return json.load(urllib.request.urlopen('http://prometheus:9090/api/v1/query?'+urllib.parse.urlencode({'query':expr}),timeout=10))['data']['result']
    def wait_query(expr):
     deadline=time.monotonic()+120
     while time.monotonic()<deadline:
      try:
       if query(expr):return
      except (urllib.error.URLError,TimeoutError):pass
      time.sleep(2)
     raise AssertionError('Actual metric evidence absent after startup budget')
    wait_query('http_server_requests_milliseconds_count{job="application-otlp"}')
    print('MILESTONE:app-otlp',flush=True)
    request=urllib.request.Request('http://grafana:3000/api/search?type=dash-db',headers={'Authorization':'Basic '+base64.b64encode(('owner:'+PASSWORD).encode()).decode()})
    deadline=time.monotonic()+60
    while True:
     try:
      dashboards=json.load(urllib.request.urlopen(request,timeout=10));break
     except (urllib.error.URLError,TimeoutError):
      if time.monotonic()>=deadline:raise
      time.sleep(1)
    assert len(dashboards)==6
    print('MILESTONE:six-dashboards',flush=True)
    wait_query('up{job="keycloak-product"} == 1')
    print('MILESTONE:keycloak-scrape',flush=True)
    ''')
                probe = probe.replace("def query(expr):","PASSWORD="+repr((d/'secrets/grafana-admin-password').read_text().strip())+"\ndef query(expr):")
                result = exercise.probe('python3','-',network='telemetry',data=probe,check=False)
                receipt['telemetryMilestones'] = [name for name in ('app-otlp','six-dashboards','keycloak-scrape')
                                                 if 'MILESTONE:'+name in result.stdout]
                if result.returncode:
                    receipt['telemetryFailureSignatures'] = [name for name in ('URLError','HTTPError','TimeoutError','AssertionError',
                        'Actual metric evidence absent after startup budget','Connection refused') if name in result.stderr]
                require(result.returncode == 0,'Telemetry probe failed; inspect sanitized milestones/signatures.')
                receipt['checks'].append('combined-candidate-app-otlp-keycloak-prometheus-six-dashboards')
                exercise.compose('stop','telemetry')
                exercise.probe('python3','-',data="import ssl,urllib.request; c=ssl.create_default_context(cafile='/run/pki/ca.crt'); assert urllib.request.urlopen('https://gameometro.com/',context=c,timeout=10).status==200")
                exercise.up('telemetry')
                receipt['checks'].append('collector-loss-product-available')
            ids = exercise.compose('ps','-q','--status','running').stdout.split()
            samples = json.loads(integration.command(['docker','inspect',*ids]).stdout)
            receipt['runtimeState'] = [{
                'service':item['Config']['Labels']['com.docker.compose.service'],
                'limitBytes':item['HostConfig']['Memory'],'oomKilled':item['State']['OOMKilled'],
                'restarts':item['RestartCount']} for item in samples]
            require(all(not item['oomKilled'] and not item['restarts'] for item in receipt['runtimeState']),
                    'Combined runtime restarted or was OOM-killed.')
            receipt['outcome'] = 'success'
        except Exception as error:
            receipt['failureType'] = type(error).__name__
            receipt['failedProbe'] = exercise.receipt.get('failedProbe') if exercise else None
            if isinstance(error,core.Refused):
                receipt['reason'] = str(error)
            receipt['operationalFailures'] = exercise.receipt.get('operationalFailures',[]) if exercise else []
            receipt['failedAt'] = [{'file':Path(f.filename).name,'line':f.lineno} for f in traceback.extract_tb(error.__traceback__)]
            print('Cleanup rehearsal failed; private diagnostics withheld.',file=sys.stderr)
        finally:
            if exercise:
                try:
                    exercise.receipt['runtimeEvents'] = []
                    exercise.cleanup(); receipt['teardown'] = 'passed'
                except Exception:
                    receipt['outcome'] = 'failure'; receipt['teardown'] = 'failed'
            if ring: subprocess.run(['gpgconf','--homedir',str(ring),'--kill','all'],capture_output=True)
            core.write(args.evidence/'cleanup-rehearsal.json',receipt)
    return 0 if receipt['outcome']=='success' else 1


if __name__ == '__main__':
    sys.exit(main())
