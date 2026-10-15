#!/usr/bin/env python3
"""Disposable Keycloak 26.7 registration/verification semantics; no real users or host changes."""
import json
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
from importlib.machinery import SourceFileLoader
alerts=SourceFileLoader('alerts',str(Path(__file__).with_name('check-alerts.py'))).load_module()
run=alerts.run
DEPLOY=alerts.DEPLOY


def main():
    alerts.local_engine()
    # Optional overlay must never change application/promotion policy, listeners or limits.
    compose_base=['docker','compose','--env-file',str(DEPLOY/'runtime.env.example'),'--file',str(DEPLOY/'compose.yaml')]
    base=json.loads(run(compose_base+['config','--format','json']))
    growth=json.loads(run(compose_base+['--file',str(DEPLOY/'compose.product-growth.yaml'),'config','--format','json']))
    changed={name for name in base['services'] if base['services'][name]!=growth['services'][name]}
    assert changed=={'keycloak','prometheus'}
    assert set(growth['services']['keycloak']['networks'])==set(base['services']['keycloak']['networks'])|{'telemetry'}
    for name in changed:
        for field in ('ports','mem_limit','cpus','pids_limit','secrets'):
            assert base['services'][name].get(field)==growth['services'][name].get(field)
    assert 'ports' not in growth['services']['prometheus']
    print('Optional growth overlay: only Keycloak/Prometheus change; listeners, limits, secrets and application preserved.',flush=True)
    keycloak=re.search(r'ARG KEYCLOAK_IMAGE=(\S+)',(DEPLOY/'keycloak/Dockerfile').read_text())[1]
    runner=re.search(r'playwright_image="([^\"]+)"',(alerts.ROOT/'scripts/validate-identity.sh').read_text())[1]
    mailpit=re.search(r'image: (\S+)',(DEPLOY/'compose.mail.yaml').read_text())[1]
    with tempfile.TemporaryDirectory(prefix='vgp-growth-proof-') as directory:
        temp=Path(directory)
        temp.chmod(0o755)
        realm={'realm':'videogame-platform','enabled':True,'registrationAllowed':True,'verifyEmail':True,'sslRequired':'none','requiredCredentials':['password'],'loginTheme':'keycloak',
               'smtpServer':{'host':'mailpit','port':'1025','from':'fixture@example.invalid'},
               'clients':[{'clientId':'fixture','enabled':True,'publicClient':True,'standardFlowEnabled':True,
                           'redirectUris':['http://keycloak:8080/callback']}],
               'users':[{'username':'seed','email':'seed@example.invalid','enabled':True,'emailVerified':True}]}
        (temp/'realm.json').write_text(json.dumps(realm))
        overlay=(DEPLOY/'compose.product-growth.yaml').read_text()
        env=dict(re.findall(r'^      (KC_\w+): [\"]?([^\"\n]+)',overlay,re.M))
        assert env=={'KC_EVENT_METRICS_USER_ENABLED':'true','KC_EVENT_METRICS_USER_EVENTS':'register,verify_email','KC_EVENT_METRICS_USER_TAGS':'realm'}
        env.update({'KC_BOOTSTRAP_ADMIN_USERNAME':'fixture-admin','KC_BOOTSTRAP_ADMIN_PASSWORD':'fixture-password',
                    'KC_METRICS_ENABLED':'true','KC_HTTP_ENABLED':'true','KC_HOSTNAME':'http://keycloak:8080'})
        definition={'services':{'keycloak':{'image':keycloak,'command':['start-dev','--import-realm'],'environment':env,
                          'volumes':['keycloak-data:/opt/keycloak/data',str(temp/'realm.json')+':/opt/keycloak/data/import/realm.json:ro'],
                          'networks':['fixture'],'mem_limit':'1g','cpus':2,'pids_limit':256},
                      'prometheus':{'image':alerts.prometheus_image(),'command':['--config.file=/etc/prometheus/prometheus.yaml','--storage.tsdb.path=/prometheus'],
                        'volumes':[str(DEPLOY/'prometheus')+':/etc/prometheus:ro'],
                        'tmpfs':['/prometheus:rw,nosuid,size=32m,uid=65534,gid=65534'],
                        'networks':['fixture'],'mem_limit':'128m','cpus':0.5,'pids_limit':32},
                      'mailpit':{'image':mailpit,'networks':['fixture'],'mem_limit':'128m','cpus':0.25,'pids_limit':64},
                      'browser':{'image':runner,'entrypoint':['node','/proof.mjs'],'volumes':[str(Path(__file__).with_name('product-growth-proof.mjs'))+':/proof.mjs:ro',str(alerts.ROOT)+':/work:ro'],
                                 'networks':['fixture'],'mem_limit':'768m','cpus':1,'pids_limit':256,'profiles':['test']}},
                    'networks':{'fixture':{'internal':True}},'volumes':{'keycloak-data':{}}}
        source=temp/'compose.json';source.write_text(json.dumps(definition))
        project='vgp-growth-proof-'+secrets.token_hex(4)
        compose=['docker','compose','--project-name',project,'--file',str(source)]
        try:
            run(compose+['up','--detach','keycloak','mailpit','prometheus'])
            subprocess.run(compose+['run','--rm','browser'],check=True,timeout=120)
            run(compose+['restart','keycloak'])
            print(run(compose+['run','--rm','--env','PROOF_RESTART=true','browser']),flush=True)
            print('Pinned Keycloak: committed register/verify_email, failed-event exclusion, no PII labels, lazy counters and restart reset passed.',flush=True)
        finally:
            run(compose+['down','--volumes','--remove-orphans'])


if __name__=='__main__':
    main()
