#!/usr/bin/env python3
"""Disposable native Grafana -> HTTPS Telegram mock proof. Never uses real credentials/egress."""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

from importlib.machinery import SourceFileLoader
alerts = SourceFileLoader('alerts', str(Path(__file__).with_name('check-alerts.py'))).load_module()
DEPLOY = alerts.DEPLOY
run = alerts.run
# Message links use the configured private origin; this placeholder is never resolved.
ROOT_URL = 'https://grafana.private-dev.invalid'


def poll(check, message, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                print(message, flush=True)
                return
        except (OSError, AssertionError, KeyError):
            pass
        time.sleep(1)
    raise AssertionError(message + ' did not pass')


def main(smoke):
    alerts.local_engine()
    image = re.search(r'image: (grafana/grafana:\S+)', (DEPLOY/'compose.observability.yaml').read_text())[1]
    runner = re.search(r'playwright_image="([^\"]+)"', (alerts.ROOT/'scripts/validate-identity.sh').read_text())[1]
    # A cold runner may need more than the 90-second runtime budget to download layers.
    # Pull explicitly so network acquisition and actual renderer execution have separate limits.
    run(['docker', 'pull', image], timeout=300)
    if smoke:
        run(['docker', 'pull', runner], timeout=300)
    with tempfile.TemporaryDirectory(prefix='vgp-telegram-mock-') as directory:
        temp = Path(directory)
        temp.chmod(0o755)
        # Placeholders are generated only inside disposable infrastructure, never read from runtime.
        token = '123456:' + secrets.token_hex(20)
        chat = '-123456789012'
        password = secrets.token_urlsafe(24)
        for name, value in [('bot-token', token), ('chat-id', chat), ('admin', password), ('database', password)]:
            (temp/name).write_text(value)
            (temp/name).chmod(0o644)  # Fake values; parent removed in finally.
        (temp/'empty').write_text('')
        (temp/'empty').chmod(0o644)
        mounts = [str(DEPLOY/'grafana/bin/provision-notifications')+':/renderer:ro',
                  str(DEPLOY/'grafana/notifications.json.template')+':/etc/grafana/notifications.json.template:ro',
                  str(DEPLOY/'grafana/provisioning')+':/etc/grafana/provisioning:ro',
                  str(temp/'bot-token')+':/run/secrets/grafana_telegram_bot_token:ro',
                  str(temp/'chat-id')+':/run/secrets/grafana_telegram_chat_id:ro']
        base = ['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
                '--tmpfs','/tmp:rw,noexec,nosuid,size=32m','--memory','128m','--pids-limit','32','--entrypoint','/bin/sh']
        for mount in mounts:
            base += ['--volume',mount]
        for operational, product in [('false','false'), ('true','false'), ('true','true')]:
            output = run(base + ['--env','GRAFANA_TELEGRAM_ENABLED='+operational,'--env','GRAFANA_PRODUCT_NOTIFICATIONS_ENABLED='+product,
                                image,'-c','/bin/sh /renderer && cat /tmp/grafana-provisioning/alerting/notifications.json'])
            config = json.loads(output)
            policy, = config['policies']
            ops, native, growth = policy['routes']
            enabled = operational == 'true'
            assert ops['receiver'] == native['receiver'] == ('private-dev-telegram' if enabled else 'empty')
            assert bool(ops['mute_time_intervals']) != enabled
            assert growth['receiver'] == ('private-dev-telegram-product' if product == 'true' else 'empty')
            assert bool(growth['mute_time_intervals']) != (product == 'true')
            assert policy['repeat_interval']=='4h' and growth['repeat_interval']=='24h'
            if enabled:
                # Both contact points reuse the same protected values; only resolved delivery differs.
                points = {cp['name']: cp for cp in config['contactPoints']}
                assert set(points) == {'private-dev-telegram'} | ({'private-dev-telegram-product'} if product == 'true' else set())
                for name, template, resolved in (('private-dev-telegram', 'gameometro.telegram', True),
                                                 ('private-dev-telegram-product', 'gameometro.telegram.product', False)):
                    if name not in points:
                        continue
                    receiver, = points[name]['receivers']
                    assert receiver['settings']['bottoken']==token and receiver['settings']['chatid']==chat
                    assert receiver['settings']['message']=='{{ template "'+template+'" . }}'
                    assert receiver['settings']['parse_mode']=='None' and receiver['disableResolveMessage'] is not resolved
            else:
                assert not config['contactPoints'] and token not in output and chat not in output
        for invalid in ('', token+'\n$INJECTION', 'invalid-token'):
            (temp/'bot-token').write_text(invalid)
            bad=subprocess.run(base+['--env','GRAFANA_TELEGRAM_ENABLED=true',image,'-c','/bin/sh /renderer'],capture_output=True,text=True)
            assert bad.returncode and 'Invalid or unavailable Telegram' in bad.stderr
            assert token not in bad.stderr and chat not in bad.stderr
        (temp/'bot-token').write_text(token)
        bad=subprocess.run(base+['--env','GRAFANA_PRODUCT_NOTIFICATIONS_ENABLED=true',image,'-c','/bin/sh /renderer'],capture_output=True,text=True)
        assert bad.returncode and 'Invalid or unavailable Telegram' in bad.stderr
        print('Default disabled, independent product opt-in, separate no-resolve product receiver, fail-closed secrets, JSON rendering and policy ownership passed.',flush=True)
        if not smoke:
            return
        # TLS interception exists ONLY in the internal test network; production URL/CA are unchanged.
        run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(temp/'mock.key'),'-out',str(temp/'mock.crt'),
             '-days','1','-subj','/CN=api.telegram.org','-addext','subjectAltName=DNS:api.telegram.org'])
        (temp/'mock.key').chmod(0o644)
        (temp/'mode').write_text('ok')
        (temp/'messages').touch()
        (temp/'values.json').write_text('{}')
        # Fast policy intervals are a notification transport test seam, not deployed settings.
        template = (DEPLOY/'grafana/notifications.json.template').read_text().replace('"1m"','"1s"').replace('"5m"','"2s"').replace('"4h"','"30s"')
        (temp/'notifications.json.template').write_text(template)
        definition = {'services': {'grafana': {'extends':{'file':str(DEPLOY/'compose.observability.yaml'),'service':'grafana'},
                       'environment':{'GF_UNIFIED_ALERTING_ENABLED':'true','GF_SERVER_ROOT_URL':ROOT_URL,'GF_UNIFIED_ALERTING_ALERTMANAGER_CONFIG_POLL_INTERVAL':'1s','GF_SMTP_ENABLED':'false','GF_LOG_LEVEL':'info',
                                      'GF_LOG_FILTERS':'ngalert.notifier.telegram:info','GRAFANA_TELEGRAM_ENABLED':'false',
                                      'GRAFANA_PRODUCT_NOTIFICATIONS_ENABLED':'false','SSL_CERT_FILE':'/fixture/mock.crt'},
                       'secrets':['grafana_telegram_bot_token','grafana_telegram_chat_id'],
                       'volumes':[str(DEPLOY/'grafana/bin/provision-notifications')+':/etc/grafana/provision-notifications:ro',
                                  str(temp/'notifications.json.template')+':/etc/grafana/notifications.json.template:ro',str(temp)+':/fixture:ro']},
                    'mock': {'image':runner,'user':str(os.getuid())+':'+str(os.getgid()),'entrypoint':['python3','/mock.py'],'network_mode':None,
                             'networks':{'telemetry':{'aliases':['api.telegram.org']}},
                             'volumes':[str(Path(__file__).with_name('telegram-mock.py'))+':/mock.py:ro',str(temp)+':/fixture'],
                             'read_only':True,'cap_drop':['ALL'],'security_opt':['no-new-privileges:true'],
                             'mem_limit':'128m','cpus':0.5,'pids_limit':32}},
                      'networks':{'telemetry':{'internal':True},'edge':{}},'volumes':{'grafana-data':{}},
                      'secrets':{name:{'file':str(temp/file)} for name,file in [('grafana_admin_password','admin'),
                         ('grafana_database_password','database'),('grafana_telegram_bot_token','bot-token'),('grafana_telegram_chat_id','chat-id')]}}
        del definition['services']['mock']['network_mode']
        source = temp/'compose.json'
        source.write_text(json.dumps(definition))
        project = 'vgp-telegram-mock-'+secrets.token_hex(4)
        compose = ['docker','compose','--project-name',project,'--file',str(source)]
        rendered=json.loads(run(compose+['config','--format','json']))
        # Only random loopback Grafana HTTP; the Telegram destination is pinned to the internal mock.
        rendered['services']['grafana']['ports']=[{'target':3000,'published':'0','host_ip':'127.0.0.1','protocol':'tcp'}]
        rendered['services']['grafana']['networks']={'telemetry':None,'edge':None}
        source.write_text(json.dumps(rendered))
        origin = None
        auth = 'Basic '+base64.b64encode(('owner:'+password).encode()).decode()
        def api(path, data=None, method=None):
            req=urllib.request.Request(origin+path, data=json.dumps(data).encode() if data is not None else None,
                                       headers={'Authorization':auth,'Content-Type':'application/json'},method=method)
            try:
                with urllib.request.urlopen(req,timeout=15) as response:
                    body=response.read()
                    return json.loads(body) if body else None
            except urllib.error.HTTPError as error:
                # Deliberately do not display response bodies; contact settings include the chat ID.
                raise AssertionError('Grafana test endpoint HTTP '+str(error.code)) from None
        def connect():
            nonlocal origin
            container=run(compose+['ps','--quiet','grafana']).strip()
            info=json.loads(run(['docker','inspect',container]))[0]
            port=run(compose+['port','grafana','3000']).strip().rsplit(':',1)[1]
            origin='http://127.0.0.1:'+port
            return api('/api/health')['database']=='ok'
        def messages():
            return [json.loads(line) for line in (temp/'messages').read_text().splitlines()]
        fixture_folder=None
        fixture_rules={}
        fixture_values={}
        provisioned_rules={r['uid']:r for r in alerts.all_rules()}
        def alert(category='operational', severity='warning', resolved=False, sync=False, tag='', registrations=1):
            key='fixture-'+category+'-'+severity+('-sync' if sync else '')+tag
            fixture_values[key]=0 if resolved else registrations
            pending=temp/'values.pending.json'
            pending.write_text(json.dumps(fixture_values))
            pending.replace(temp/'values.json')
            # Updating a rule's expression resets Grafana's state. Change only the
            # observed value so grouping/deduplication exercise one continuous alert.
            if key in fixture_rules:
                return
            model=json.loads((DEPLOY/'grafana/provisioning/alerting/private-dev.json').read_text())['groups'][0]['rules'][0]
            rule={k:v for k,v in model.items() if k not in {'uid','isPaused'}}
            rule.update({'uid':key,'title':'Fixture '+('sync' if sync else category)+' '+severity+tag,
                         'folderUID':fixture_folder,'ruleGroup':'fixture','for':'0s'})
            # Real provisioned annotations (texts, dashboard links, count template) drive the messages.
            source=provisioned_rules['vgp-registration-window' if category=='product' else 'vgp-sync-failures' if sync else 'vgp-pool-pressure']
            rule['labels']=dict(source['labels'], severity=severity)
            rule['annotations']=dict(source['annotations'])
            rule['data'][0].update({'datasourceUid':'fixture-prometheus','model':{
                'refId':'A','expr':key,'instant':True,'range':False,'format':'table'}})
            api('/api/v1/provisioning/alert-rules',rule,method='POST')
            fixture_rules[key]=rule
            api('/api/v1/provisioning/folder/'+fixture_folder+'/rule-groups/fixture',
                {'title':'fixture','folderUid':fixture_folder,'interval':10,'rules':list(fixture_rules.values())},method='PUT')
        def activate(product=False, enabled=True):
            rendered['services']['grafana']['environment'].update({'GRAFANA_TELEGRAM_ENABLED':str(enabled).lower(),
                       'GRAFANA_PRODUCT_NOTIFICATIONS_ENABLED':str(product).lower()})
            source.write_text(json.dumps(rendered))
            run(compose+['up','--detach','--no-deps','--force-recreate','grafana'])
            poll(connect,'Grafana recreated on original volume')
        try:
            run(compose+['up','--detach','mock'])
            mock_id=run(compose+['ps','--quiet','mock']).strip()
            mock_info=json.loads(run(['docker','inspect',mock_id]))[0]
            mock_ip=next(iter(mock_info['NetworkSettings']['Networks'].values()))['IPAddress']
            # Pin the native API hostname to the isolated fixture; DNS can never fall back to Telegram.
            rendered['services']['grafana']['extra_hosts']=['api.telegram.org='+mock_ip]
            source.write_text(json.dumps(rendered))
            run(compose+['up','--detach','grafana'])
            poll(connect,'Isolated Grafana authenticated; native file provisioning loaded')
            api('/api/datasources',{'uid':'fixture-prometheus','name':'Disposable notification values',
                'type':'prometheus','access':'proxy','url':'http://mock:9090',
                'jsonData':{'httpMethod':'GET'}},method='POST')
            fixture_folder=api('/api/folders',{'title':'Disposable notification fixture'},method='POST')['uid']
            policy=api('/api/v1/provisioning/policies')
            assert policy['routes'][0]['receiver']=='empty'
            alert()
            time.sleep(12)
            assert not messages(), 'Default delivery must be silent'
            activate()
            cp=api('/api/v1/provisioning/contact-points')
            receiver=next(c for c in cp if c['uid']=='vgp-telegram')
            assert token not in json.dumps(cp), 'Contact-point read API must redact the token'
            assert receiver['settings']['chatid']==chat
            # Secret values never appear in process env/argv or Docker metadata.
            info=run(['docker','inspect',run(compose+['ps','--quiet','grafana']).strip()])
            assert token not in info and chat not in info
            perms=run(compose+['exec','-T','grafana','stat','-c','%a','/tmp/grafana-provisioning','/tmp/grafana-provisioning/alerting/notifications.json'])
            assert perms.split()==['700','600']
            alert()
            poll(lambda:len(messages())>=1,'Native Telegram English firing notification delivered to HTTPS mock')
            assert all('Gameómetro' in m['text'] and len(m['text'])<=4096 for m in messages())
            firing=messages()[-1]['text']
            for line in ('Gameómetro — Operational warning\n','Severity: warning\n','Environment: private-dev\n',
                         'Problem: Database connection pool pressure\n','First action: Check Hikari pending',
                         'Dashboard: '+ROOT_URL+'/d/vgp-runtime','Alert list: '+ROOT_URL+'/alerting/list',
                         'Runbook: https://github.com/rubhern/videogame-platform/blob/main/docs/development/operations-runbook.md#private-dev-alerts'):
                assert line in firing, 'Operational firing message lacks '+line.strip()+':\n'+firing
            examples={'operational firing':firing}
            count=len(messages()); alert(); time.sleep(4)
            assert len(messages())==count, 'Identical alerts must deduplicate'
            alert(resolved=True)
            poll(lambda:any('Recovered: Database connection pool pressure' in m['text'] and 'no longer met' in m['text'] for m in messages()),'Operational recovery delivered')
            examples['operational recovery']=next(m['text'] for m in messages() if 'Recovered:' in m['text'])
            assert examples['operational recovery'].startswith('Gameómetro — Operational alert closed\n')
            count=len(messages()); alert(category='product',severity='info'); time.sleep(12)
            assert len(messages())==count, 'Product delivery remains independent and muted'
            alert(category='product',severity='info',resolved=True)
            activate(product=True)
            cp={c['uid']:c for c in api('/api/v1/provisioning/contact-points')}
            assert not cp['vgp-telegram']['disableResolveMessage'] and cp['vgp-telegram-product']['disableResolveMessage']
            assert token not in json.dumps(cp)
            assert api('/api/v1/provisioning/policies')['routes'][2]['receiver']=='private-dev-telegram-product'
            product_messages=lambda:[m for m in messages() if 'registration' in m['text']]
            single=('Gameómetro — New user registration detected\nNew registrations observed: 1\n'
                    'Environment: private-dev\nView in Grafana: '+ROOT_URL+'/d/vgp-growth')
            # A fractional increase estimate above zero is reported as one observed registration.
            alert(category='product',severity='info',registrations=0.6)
            poll(lambda:any(m['text'].startswith(single) for m in messages()),'Product route delivers the English registration notice with a Growth link')
            examples['registration']=product_messages()[-1]['text']
            def product_state(expected):
                groups=api('/api/prometheus/grafana/api/v1/rules')['data']['groups']
                rule=next(r for g in groups for r in g['rules'] if r['name']=='Fixture product info')
                firing=[a for a in rule.get('alerts',[]) if a['state']=='Alerting']
                return firing[0] if len(firing)==1 and firing[0]['annotations'].get('observed_registrations')==expected else None
            first=product_state('1')
            assert first and first['activeAt']
            count=len(product_messages())
            alert(category='product',severity='info',registrations=2)
            poll(lambda:product_state('2'),'The same product alert observes two registrations')
            time.sleep(12)
            assert len(product_messages())==count, 'Two observations in the firing window are grouped, not per-user deliveries'
            alert(category='product',severity='info',registrations=3)
            poll(lambda:product_state('3'),'The same product alert observes three registrations')
            time.sleep(12)
            assert len(product_messages())==count, 'More registrations while firing must not create individual notifications'
            current=product_state('3')
            assert current['labels']==first['labels'] and current['activeAt']==first['activeAt'], 'Observed values must not reset the firing identity/lifecycle'
            count=len(messages())
            alert(category='product',severity='info',resolved=True)
            poll(lambda:product_state('3') is None and not any(a['labels'].get('alertname')=='Fixture product info' and a['status']['state']=='active'
                     for a in api('/api/alertmanager/grafana/api/v2/alerts')),'Product rule returned to Normal')
            # Several accelerated group intervals pass; the no-resolve receiver must stay silent.
            time.sleep(15)
            assert len(messages())==count, 'Registration-window resolution must not send any Telegram message'
            print('Registration-window resolution produced no Telegram message.',flush=True)
            count=len(product_messages())
            alert(category='product',severity='info',registrations=2.6)
            poll(lambda:len(product_messages())>count,'A new activity window notifies after previous resolution')
            examples['registrations']=product_messages()[-1]['text']
            assert examples['registrations'].startswith('Gameómetro — New user registrations detected\nNew registrations observed: 3 (estimate;')
            count=len(messages())
            alert(category='product',severity='info',resolved=True)
            time.sleep(15)
            assert len(messages())==count, 'Every product resolution remains silent'
            alert(sync=True)
            poll(lambda:any('Problem: Catalogue synchronization needs attention' in m['text'] for m in messages()),'Synchronization route delivered')
            alert(sync=True,resolved=True)
            poll(lambda:any('Window expired: Catalogue synchronization' in m['text'] and 'does NOT confirm a later successful run' in m['text'] for m in messages()),'Synchronization resolution does not claim recovery')
            examples['synchronization window expired']=next(m['text'] for m in messages() if 'Window expired:' in m['text'])
            assert 'Recovered' not in examples['synchronization window expired']
            (temp/'mode').write_text('fail')
            count=len(messages()); alert(severity='critical')
            poll(lambda:any(m['mode']=='fail' for m in messages()[count:]),'Telegram API failure exercised')
            (temp/'mode').write_text('ok')
            poll(lambda:any(m['mode']=='ok' and 'Operational critical alert' in m['text'] for m in messages()[count:]),'A later notification cycle succeeds after a failed attempt (no in-attempt retry)')
            (temp/'mode').write_text('timeout')
            count=len(messages()); alert(severity='critical',sync=True)
            poll(lambda:any(m['mode']=='timeout' for m in messages()[count:]),'Telegram HTTPS timeout exercised')
            # Grafana HTTP client timeout is 30s; wait for its redacted error, then retry.
            poll(lambda:'<redacted>' in run(compose+['logs','--no-color','grafana']), 'Transport timeout reported',seconds=65)
            logs=run(compose+['logs','--no-color','grafana'])
            assert token not in logs and chat not in logs
            assert 'webhook response status 503' in logs and '<redacted>' in logs
            (temp/'mode').write_text('ok')
            poll(lambda:any(m['mode']=='ok' and 'Operational critical alert' in m['text'] for m in messages()[count:]),'Timeout retry recovers; acknowledgement may still be ambiguous')
            for index in range(6):
                alert(tag='-batch-'+str(index))
            poll(lambda:any('Showing 4 of 6 alerts' in m['text'] for m in messages()),'Large group renders four entries plus full-group link')
            assert all(len(m['text'])<4096 for m in messages())
            assert not any(re.search(r'[¿¡ñ]|\b(?:AVISO|CRÍTICO|registros|Panel privado|Guía)\b',m['text']) for m in messages()), 'Spanish notification text remains'
            activate(product=True)
            assert api('/api/v1/provisioning/policies')['routes'][0]['receiver']=='private-dev-telegram'
            assert len([c for c in api('/api/v1/provisioning/contact-points') if c['uid'] in ('vgp-telegram','vgp-telegram-product')])==2
            # Exercise disabling on the same persisted database with production polling,
            # including the previous contact points and active alert instances.
            rendered['services']['grafana']['environment']['GF_UNIFIED_ALERTING_ALERTMANAGER_CONFIG_POLL_INTERVAL']='60s'
            # Product alone off: operational delivery stays on, the product route becomes muted.
            count=len(product_messages())
            alert(category='product',severity='info',registrations=1)
            poll(lambda:len(product_messages())>count,'Product notice delivered before disabling only the product route')
            count=len(product_messages())
            activate(product=False)
            policy=api('/api/v1/provisioning/policies')
            assert policy['routes'][0]['receiver']=='private-dev-telegram' and policy['routes'][2]['receiver']=='empty'
            alert(category='product',severity='info',resolved=True)
            alert(category='product',severity='info',registrations=1)
            until=time.monotonic()+65
            while time.monotonic()<until:
                assert len(product_messages())==count, 'Disabled product route must never send'
                time.sleep(1)
            print('Disabled product route stays silent through recreation while operational delivery remains enabled.',flush=True)
            count=len(messages())
            activate(enabled=False)
            assert api('/api/v1/provisioning/policies')['routes'][0]['receiver']=='empty'
            alert(tag='-disabled')
            until=time.monotonic()+65
            while time.monotonic()<until:
                assert len(messages())==count, 'Disabled persisted receiver must never send'
                time.sleep(1)
            assert len(messages())==count, 'Disabling delivery must silence persisted active alerts immediately'
            print('Disabled persisted receiver stays silent through recreation with production config polling.',flush=True)
            for name, text in examples.items():
                print('--- Example '+name+' message (fixture values) ---\n'+text,flush=True)
            print('Native provisioning, English templates, grouping/deduplication, silent product resolutions, later windows, operational recoveries, redacted failures and recreation passed. No real Telegram access.',flush=True)
        except Exception:
            logs=run(compose+['logs','--no-color','--tail','30','grafana','mock'])
            print(logs.replace(token,'[redacted]').replace(chat,'[redacted]').replace(password,'[redacted]'),flush=True)
            raise
        finally:
            run(compose+['down','--volumes','--remove-orphans'])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--smoke',action='store_true')
    main(parser.parse_args().smoke)
