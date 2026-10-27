"""Disposable real-engine dashboard/query/Telegram and overhead proof. Called by the test entrypoint."""
import base64
import copy
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


HOST_BRIDGE_PROBE = """
import importlib.util
import json
from pathlib import Path
import resource
import sys
import time

spec = importlib.util.spec_from_file_location('sampler', sys.argv[1])
sampler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sampler)
start = time.perf_counter()
sampler.snapshot('vgp-telemetry-instrumentation-fixture')
record = json.dumps({'ts': time.time(), 'logger': 'http.log.access.log0',
                     'status': 503, 'response_origin': 'upstream'}) + '\\n'
sampler.edge_observations(record * 19999)
own = resource.getrusage(resource.RUSAGE_SELF)
children = resource.getrusage(resource.RUSAGE_CHILDREN)
# Linux ru_maxrss can retain the launching harness's high-water mark across exec.
# VmHWM belongs to this executable's memory image, not the pre-exec harness.
parent_peak = int(next(line.split()[1] for line in Path('/proc/self/status').read_text().splitlines()
                       if line.startswith('VmHWM:')))
print(json.dumps({'parentPeakKiB': parent_peak, 'childPeakKiB': children.ru_maxrss,
                 'conservativeCombinedMiB': (parent_peak + children.ru_maxrss) / 1024,
                 'cpuSeconds': own.ru_utime + own.ru_stime + children.ru_utime + children.ru_stime,
                 'wallSeconds': time.perf_counter() - start, 'records': 19999}))
"""


def main():
    # Load the same test helpers when invoked from its CLI module.
    import __main__ as test
    test.engine()
    run,ROOT,PROD,OBS,SHARED=test.run,test.ROOT,test.PROD,test.OBS,test.SHARED
    with tempfile.TemporaryDirectory(prefix='vgp-prod-telemetry-') as directory:
        d=Path(directory);d.chmod(0o755)
        config=test.rendered(d)
        selected=['grafana','prometheus','telemetry','node-exporter','blackbox']
        config['services']={k:v for k,v in config['services'].items() if k in selected}
        config['networks']={'fixture':{'internal':True},'loopback':{}}
        config['name']='vgp-prod-telemetry-'+secrets.token_hex(4)
        project=config['name']
        for service in config['services'].values():
            service['networks']={'fixture':{}}
            service.pop('depends_on',None);service.pop('profiles',None)
        for volume in config['volumes'].values():volume.pop('name',None)
        password=secrets.token_urlsafe(24)
        token='123456:'+secrets.token_urlsafe(32);chat='-123456'
        for key,file,value in [('grafana_admin_password','admin',password),('grafana_database_password','database',password),('grafana_telegram_bot_token','bot-token',token),('grafana_telegram_chat_id','chat-id',chat)]:
            (d/file).write_text(value);(d/file).chmod(0o644)
            config['secrets'][key]={'file':str(d/file)}
        # The mocked HTTPS destination has no route to the internet.
        run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',d/'mock.key','-out',d/'mock.crt','-days','1','-subj','/CN=api.telegram.org','-addext','subjectAltName=DNS:api.telegram.org'])
        (d/'mock.key').chmod(0o644);(d/'mode').write_text('ok');(d/'messages').touch()
        all_rules=test.rules()+json.loads((SHARED/'grafana/provisioning/alerting/private-dev.json').read_text())['groups'][0]['rules']
        values={r['data'][0]['model']['expr']:0 for r in all_rules if 'expr' in r['data'][0]['model']}
        test.sampler.atomic(d/'values.json',json.dumps(values))
        (d/'metrics.prom').write_text('keycloak_user_events_total{realm="gameometro",event="register",error=""} 3\nkeycloak_user_events_total{realm="gameometro",event="login",error="invalid_user_credentials"} 2\nkeycloak_user_events_total{realm="gameometro",event="login",error="invalid_user_credentials",client_ip="sensitive-marker"} 7\n')
        metrics=d/'textfile';metrics.mkdir(mode=0o755)
        (metrics/'host.prom').write_text(test.sampler.render([],now=time.time()))
        node=config['services']['node-exporter']
        # Docker Desktop does not provide a shared root mount; fixed-root fixture only.
        for volume in node['volumes']:
            if volume.get('source')=='/':volume.pop('bind',None)
        node['volumes']=[v for v in node['volumes'] if v['target']!='/textfile']+[{'type':'bind','source':str(metrics),'target':'/textfile','read_only':True}]
        gf=config['services']['grafana'];gf['networks']['loopback']={};gf['ports']=[{'target':3000,'published':'0','host_ip':'127.0.0.1','protocol':'tcp'}]
        gf['environment'].update({'GRAFANA_TELEGRAM_ENABLED':'false','SSL_CERT_FILE':'/fixture/mock.crt','GF_UNIFIED_ALERTING_ALERTMANAGER_CONFIG_POLL_INTERVAL':'1s'})
        gf['volumes'].append({'type':'bind','source':str(d),'target':'/fixture','read_only':True})
        # Only notification timing is accelerated. Original alert expressions and pending
        # periods are tested by Promtool; production rule files are loaded unchanged here.
        (d/'notifications.json.template').write_text((SHARED/'grafana/notifications.json.template').read_text().replace('"1m"','"1s"').replace('"5m"','"2s"').replace('"4h"','"30s"').replace('["category","=","operational"]','["category","=","operational"],["transport_fixture","=","true"]'))
        gf['volumes']=[v for v in gf['volumes'] if v['target']!='/etc/grafana/notifications.json.template']+[{'type':'bind','source':str(d/'notifications.json.template'),'target':'/etc/grafana/notifications.json.template','read_only':True}]
        prom=config['services']['prometheus'];prom['networks']['loopback']={};prom['ports']=[{'target':9090,'published':'0','host_ip':'127.0.0.1','protocol':'tcp'}]
        python=test.runtime.PYTHON_IMAGE
        config['services']['mock']={'image':python,'user':str(os.getuid())+':'+str(os.getgid()),'entrypoint':['python','/proof.py'],'networks':{'fixture':{'aliases':['api.telegram.org','keycloak','gameometro.com','auth.gameometro.com']}},'volumes':[f'{ROOT}/scripts/fixtures/production-telemetry.py:/proof.py:ro',f'{SHARED}/grafana/telegram-mock.py:/telegram-mock.py:ro',f'{d}:/fixture'],'read_only':True,'mem_limit':'64m','cpus':.25,'cap_drop':['ALL'],'security_opt':['no-new-privileges:true']}
        pgimage=re.search(r'image: (postgres:\S+)',(PROD/'compose.yaml').read_text())[1]
        config['services']['postgres']={'image':pgimage,'environment':{'POSTGRES_PASSWORD':password,'POSTGRES_DB':'videogame_platform'},'networks':{'fixture':{}},'mem_limit':'256m','cpus':.5,'volumes':[f'{ROOT}/backend/src/main/resources/db:/migrations:ro',f'{SHARED}/grafana/provision-reader.sql:/reader.sql:ro'],'tmpfs':['/var/lib/postgresql/18/docker:rw,size=256m']}
        source=d/'compose.json';source.write_text(json.dumps(config))
        compose=['docker','compose','-p',project,'-f',str(source)]
        auth='Basic '+base64.b64encode(('owner:'+password).encode()).decode()
        origin=prom_origin=None
        monitor=None; stop_monitor=threading.Event(); monitor_errors=[]
        def api(path,data=None,method=None,base=None):
            request=urllib.request.Request((base or origin)+path,data=json.dumps(data).encode() if data is not None else None,headers={'Authorization':auth,'Content-Type':'application/json'},method=method)
            try:
                with urllib.request.urlopen(request,timeout=20) as response:
                    body=response.read();return json.loads(body) if body else None
            except urllib.error.HTTPError as error:
                raise AssertionError(f'Telemetry fixture API failed: HTTP {error.code}') from None
        def poll(check,description,seconds=90):
            deadline=time.monotonic()+seconds
            while time.monotonic()<deadline:
                try:
                    if check():print(description,flush=True);return
                except (OSError,AssertionError,KeyError,TypeError):pass
                time.sleep(1)
            raise AssertionError(description+' did not pass')
        def query(expr):return api('/api/v1/query?'+urllib.parse.urlencode({'query':expr}),base=prom_origin)['data']['result']
        def sql(command):return run(compose+['exec','-T','postgres','psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','videogame_platform'],input=command)
        messages=lambda:[json.loads(x) for x in (d/'messages').read_text().splitlines()]
        try:
            run(compose+['up','-d','mock'])
            mock_id=run(compose+['ps','-q','mock']).strip()
            mock_info=json.loads(run(['docker','inspect',mock_id]))[0]
            mock_ip=next(iter(mock_info['NetworkSettings']['Networks'].values()))['IPAddress']
            gf['extra_hosts']=['api.telegram.org='+mock_ip]
            source.write_text(json.dumps(config))
            run(compose+['up','-d'])
            origin='http://'+run(compose+['port','grafana','3000']).strip()
            prom_origin='http://'+run(compose+['port','prometheus','9090']).strip()
            poll(lambda:api('/api/health')['database']=='ok','Production Grafana started within its configured ceiling')
            poll(lambda:sql('SELECT 1;'),'Isolated database ready')
            sql('CREATE ROLE videogame_app; CREATE ROLE videogame_app_migrator; CREATE ROLE videogame_keycloak;')
            # Same migration sources, SQL-first; no generated schema or separate fixture schema.
            migrations=sorted([*(ROOT/'backend/src/main/resources/db/migration').glob('*.sql'),*(ROOT/'backend/src/main/resources/db/dev-seed').glob('*.sql')],key=lambda x:x.name)
            for path in migrations:sql(path.read_text())
            sql("\\set reader_password '"+password+"'\n"+(SHARED/'grafana/provision-reader.sql').read_text())
            dashboards=api('/api/search?type=dash-db')
            assert {x['uid'] for x in dashboards}=={'vgp-runtime','vgp-application','vgp-product','vgp-synchronization','vgp-growth','vgp-production'}
            for uid in ('private-dev-prometheus','private-dev-postgres'):
                assert api('/api/datasources/uid/'+uid+'/health')['status']=='OK'
            checked=0
            for dashboard in dashboards:
                obj=api('/api/dashboards/uid/'+dashboard['uid'])['dashboard']
                if obj['uid']=='vgp-growth':
                    assert 'realm=\\"videogame-platform\\"' not in json.dumps(obj)
                canonical=SHARED/'grafana/dashboards'/(''+obj['uid']+'.json')
                if canonical.exists():
                    expected=json.loads(canonical.read_text())
                    actual_expr=[t['expr'] for p in obj['panels'] for t in p.get('targets',[]) if 'expr' in t]
                    expected_expr=[t['expr'].replace('realm="videogame-platform"','realm="gameometro"') for p in expected['panels'] for t in p.get('targets',[]) if 'expr' in t]
                    assert actual_expr==expected_expr, 'Canonical dashboard query parity lost'
                for panel in obj['panels']:
                    for target in panel.get('targets',[]):
                        if 'rawSql' not in target:continue
                        model=copy.deepcopy(target);model.update(refId='A',datasource={'uid':'private-dev-postgres','type':'grafana-postgresql-datasource'},intervalMs=60000,maxDataPoints=100)
                        result=api('/api/ds/query',{'from':str(int((time.time()-86400)*1000)),'to':str(int(time.time()*1000)),'queries':[model]})
                        assert not result['results']['A'].get('error');checked+=1
            print(f'All six dashboards provisioned; {checked} real PostgreSQL panel queries passed with the view-only role.',flush=True)
            # Explicit refusal probes for table reads, mutations, and identity DB connection.
            for sql_text in ['SELECT * FROM catalogue.game LIMIT 1','DELETE FROM ratings.rating','CREATE TABLE public.forbidden (id int)']:
                model={'refId':'A','datasource':{'uid':'private-dev-postgres'},'rawSql':sql_text,'format':'table','rawQuery':True}
                try:result=api('/api/ds/query',{'from':'0','to':'1','queries':[model]})
                except AssertionError:continue
                assert result['results']['A'].get('error')
            # Send representative bounded synthetic OTLP cardinality through the real Collector.
            routes=['/api/v1/releases','/api/v1/featured-releases','/api/v1/games','/api/v1/games/{gameId}','/api/v1/me/ratings','/api/v1/me/ratings/{gameId}','/api/v1/platforms','/api/v1/genres','/api/v1/release-calendar','/api/v1/me/rating-insights']
            start=time.time_ns()
            def metric_payload(step):
                points=[]
                for route in routes:
                    for method in ('GET','PUT','DELETE'):
                        for status in ('200','400','429','503'):
                            count=100*(step+1)
                            points.append({'count':str(count),'sum':count*75,'startTimeUnixNano':str(start),'timeUnixNano':str(time.time_ns()),'bucketCounts':[str(n*(step+1)) for n in [10,10,20,20,20,10,10,0]],'explicitBounds':[5,10,25,50,100,250,1000],'attributes':[{'key':k,'value':{'stringValue':v}} for k,v in {'uri':route,'method':method,'status':status,'outcome':'SUCCESS' if status=='200' else 'ERROR'}.items()]})
                metrics=[{'name':'http.server.requests','unit':'ms','histogram':{'aggregationTemporality':2,'dataPoints':points}}]
                for name,unit,labels,value in [('process.uptime','ms',{},(time.time_ns()-start)/1e6),('jvm.memory.used','bytes',{'area':'heap','id':'G1 Eden Space'},1e8),('jvm.memory.max','bytes',{'area':'heap','id':'G1 Eden Space'},2e8),('hikaricp.connections.pending','',{'pool':'HikariPool-1'},0),('hikaricp.connections.active','',{'pool':'HikariPool-1'},2),('hikaricp.connections.max','',{'pool':'HikariPool-1'},10),('system.cpu.usage','',{},0.25)]:
                    metrics.append({'name':name,'unit':unit,'gauge':{'dataPoints':[{'timeUnixNano':str(time.time_ns()),'asDouble':value,'attributes':[{'key':k,'value':{'stringValue':v}} for k,v in labels.items()]}]}})
                return {'resourceMetrics':[{'scopeMetrics':[{'metrics':metrics}]}]}
            (d/'metric.json').write_text(json.dumps(metric_payload(0)))
            send="import urllib.request,pathlib; r=urllib.request.Request('http://telemetry:4318/v1/metrics',data=pathlib.Path('/fixture/metric.json').read_bytes(),headers={'Content-Type':'application/json'}); urllib.request.urlopen(r,timeout=5).read()"
            poll(lambda:bool(query('up{job="collector"} == 1')),'Collector metrics listener ready')
            run(compose+['exec','-T','mock','python','-c',send])
            poll(lambda:bool(query('http_server_requests_milliseconds_count')),'OTLP -> Collector -> Prometheus produced synthetic series')
            poll(lambda:bool(query('keycloak_user_events_total{job="keycloak-product"}')),'Production realm Growth metrics collected')
            # Each job scrapes at its own offset within the interval: wait for the security job too.
            poll(lambda:bool(query('keycloak_user_events_total{job="keycloak-security"}')),'Production realm security metrics collected')
            assert not query('keycloak_user_events_total{client_ip!=""}')
            assert sum(float(x['value'][1]) for x in query('keycloak_user_events_total{job="keycloak-security",event="login"}'))==2
            print('Sensitive-label samples rejected before storage without merging counters.',flush=True)
            # Measure bounded representative dashboard refresh and rule-evaluation overhead.
            samples=[]
            ids=run(compose+['ps','-q',*selected]).split()
            def sample_resources():
                try:
                    while not stop_monitor.is_set():
                        stats=run(['docker','stats','--no-stream','--format','{{json .}}',*ids])
                        samples.extend(json.loads(line) for line in stats.splitlines())
                        stop_monitor.wait(0.2)
                except Exception:
                    monitor_errors.append('Resource sampler failed')
            monitor=threading.Thread(target=sample_resources,daemon=True);monitor.start()
            models=[]
            for dashboard in dashboards:
                obj=api('/api/dashboards/uid/'+dashboard['uid'])['dashboard']
                for panel in obj['panels']:
                    for target in panel.get('targets',[]):
                        if 'expr' not in target:continue
                        model=copy.deepcopy(target)
                        for key in ('$__rate_interval','$__range','$__interval'):model['expr']=model['expr'].replace(key,'5m')
                        for key in ('$route','$method'):model['expr']=model['expr'].replace(key,'.*')
                        model.update(datasource={'uid':'private-dev-prometheus','type':'prometheus'},intervalMs=60000,maxDataPoints=30)
                        models.append(model)
            for step in range(4):
                (d/'metric.json').write_text(json.dumps(metric_payload(step+1)))
                run(compose+['exec','-T','mock','python','-c',send])
                for offset in range(0,len(models),4):
                    batch=copy.deepcopy(models[offset:offset+4])
                    for i,model in enumerate(batch):model['refId']=chr(65+i)
                    result=api('/api/ds/query',{'from':str(int((time.time()-300)*1000)),'to':str(int(time.time()*1000)),'queries':batch})
                    assert not any(frame.get('error') for frame in result['results'].values())
                raw=run(compose+['ps','-q',*selected]).split()
                stats=run(['docker','stats','--no-stream','--format','{{json .}}',*raw])
                samples.extend(json.loads(line) for line in stats.splitlines())
                time.sleep(5)
            stop_monitor.set();monitor.join(timeout=10)
            assert not monitor.is_alive() and not monitor_errors and samples
            def mib(text):
                m=re.match(r'([0-9.]+)([A-Za-z]+)',text.strip());return float(m[1])*{'B':1/1048576,'KiB':1/1024,'MiB':1,'GiB':1024}[m[2]]
            maxima={}
            for sample in samples:
                name=next(n for n in selected if sample['Name'].endswith('-'+n+'-1'))
                val=maxima.setdefault(name,{'memoryMiB':0,'cpuPercent':0})
                val['memoryMiB']=max(val['memoryMiB'],mib(sample['MemUsage'].split('/')[0]));val['cpuPercent']=max(val['cpuPercent'],float(sample['CPUPerc'].rstrip('%')))
            for name in selected:
                info=json.loads(run(['docker','inspect',run(compose+['ps','-q',name]).strip()]))[0]
                assert info['State']['Running'] and not info['State']['OOMKilled'] and info['RestartCount']==0, name+' restarted or hit OOM during measurement'
            head=query('prometheus_tsdb_head_series');series=float(head[0]['value'][1]) if head else None
            disk={name:int(run(compose+['exec','-T',name,'du','-sk',path]).split()[0]) for name,path in [('prometheus','/prometheus'),('grafana','/var/lib/grafana')]}
            # Measure the actual host bridge's bounded parse plus local Docker CLI cost.
            # The fixed synthetic project name cannot inspect production/private-dev.
            bridge=json.loads(run(['python3','-c',HOST_BRIDGE_PROBE,OBS/'sample-host.py']))
            assert bridge['conservativeCombinedMiB'] < 64, f'Host bridge exceeds 64 MiB: {bridge}'
            evidence={'hostBridge':bridge,'diskKiB':disk,'kind':'isolated-synthetic-telemetry','workload':{'otlpSeries':1207,'dashboards':6,'promqlPanelsPerRefresh':len(models),'refreshes':4,'sqlPanels':checked,'scrapeSeconds':30,'resourceObservations':len(samples), 'resourceSampling':'during queries and refreshes'},'maxima':maxima,'headSeries':series,'realHostCapacityAccepted':False}
            # No raw container IDs, host paths, secrets or network identifiers enter evidence.
            output=ROOT/'target/production-observability-evidence.json';output.parent.mkdir(exist_ok=True);output.write_text(json.dumps(evidence,indent=2)+'\n')
            print('Sanitized overhead evidence: '+str(output),flush=True)
            # Use the real provisioned receiver and production labels, with a controllable
            # datasource only for transport timing. All external networks remain denied.
            api('/api/datasources',{'uid':'fixture-prometheus','name':'Disposable transport values','type':'prometheus','access':'proxy','url':'http://mock:9090','jsonData':{'httpMethod':'GET'}},method='POST')
            folder=api('/api/folders',{'title':'Disposable transport proof'})['uid']
            template=next(r for r in test.rules() if r['uid']=='prod-backup-unverified')
            rule=copy.deepcopy(template);rule.update(uid='fixture-prod-backup',title='Fixture production backup',folderUID=folder,ruleGroup='fixture',**{'for':'0s'})
            rule['labels']['transport_fixture']='true'
            rule['data'][0]['datasourceUid']='fixture-prometheus'
            rule['data'][0]['model']['expr']='fixture-backup';values['fixture-backup']=1
            test.sampler.atomic(d/'values.json',json.dumps(values))
            api('/api/v1/provisioning/alert-rules',rule,method='POST')
            api('/api/v1/provisioning/folder/'+folder+'/rule-groups/fixture',{'title':'fixture','folderUid':folder,'interval':10,'rules':[rule]},method='PUT')
            def fixture_firing():
                return any(r.get('state')=='firing' for g in api('/api/prometheus/grafana/api/v1/rules')['data']['groups'] for r in g['rules'] if r.get('name')=='Fixture production backup')
            poll(fixture_firing,'Production fixture fires while notification delivery is disabled')
            assert not messages(),'Disabled delivery must remain silent even for an active alert'
            gf['environment']['GRAFANA_TELEGRAM_ENABLED']='true';source.write_text(json.dumps(config));run(compose+['up','-d','--no-deps','--force-recreate','grafana'])
            origin='http://'+run(compose+['port','grafana','3000']).strip()
            poll(lambda:api('/api/health')['database']=='ok','Production notification provisioning enabled with synthetic secrets')
            values['fixture-backup']=1;test.sampler.atomic(d/'values.json',json.dumps(values))
            poll(lambda:any('Environment: production' in m['text'] and 'Verified off-host backup' in m['text'] for m in messages()),'Production critical Telegram firing reached the HTTPS mock')
            count=len(messages());time.sleep(4);assert len(messages())==count
            values['fixture-backup']=0;test.sampler.atomic(d/'values.json',json.dumps(values))
            poll(lambda:any('Recovered:' in m['text'] and 'Environment: production' in m['text'] for m in messages()),'Production Telegram recovery reached the mock')
            assert all(len(m['text'])<=4096 and token not in m['text'] and chat not in m['text'] and 'sensitive-marker' not in m['text'] for m in messages())
            logs=run(compose+['logs','--no-color','grafana'])
            assert token not in logs and chat not in logs
            info=run(['docker','inspect',run(compose+['ps','-q','grafana']).strip()]);assert token not in info and chat not in info
            print('Production notification deduplication, recovery, message bounds and secret redaction passed.',flush=True)
        except Exception:
            try:
                groups=api('/api/prometheus/grafana/api/v1/rules')['data']['groups']
                states=[{k:r.get(k) for k in ('name','state','health','lastError')} for g in groups for r in g['rules'] if r.get('name','').startswith('Fixture')]
                print('Fixture rule state: '+json.dumps(states).replace(token,'[redacted]').replace(chat,'[redacted]'),flush=True)
                print('Mock deliveries: '+str(len(messages())),flush=True)
                mock_logs=run(compose+['logs','--no-color','--no-log-prefix','--tail','15','mock'])
                print('Mock diagnostics: '+mock_logs.replace(token,'[redacted]').replace(chat,'[redacted]'),flush=True)
                logs=run(compose+['logs','--no-color','--no-log-prefix','grafana'])
                diagnostics=[line for line in logs.splitlines() if ('telegram' in line.lower() or 'fixture-prometheus' in line) and ('error' in line.lower() or 'warn' in line.lower())]
                print('Transport diagnostics: '+json.dumps(diagnostics[-6:]).replace(token,'[redacted]').replace(chat,'[redacted]'),flush=True)
            except Exception:pass
            failures=[]
            for service in selected:
                cid=run(compose+['ps','-aq',service]).strip()
                if cid:
                    state=json.loads(run(['docker','inspect',cid]))[0]
                    failures.append({'service':service,'status':state['State']['Status'],'exitCode':state['State']['ExitCode'],'oomKilled':state['State']['OOMKilled'],'restartCount':state['RestartCount']})
            print('Telemetry failure state: '+json.dumps(failures),flush=True)
            raise
        finally:
            stop_monitor.set()
            if monitor:monitor.join(timeout=10)
            run(compose+['down','--volumes','--remove-orphans'])
