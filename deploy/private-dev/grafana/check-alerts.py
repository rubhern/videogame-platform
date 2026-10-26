#!/usr/bin/env python3
"""Focused provisioning/PromQL checks and an isolated Grafana firing/resolution proof."""
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
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
DEPLOY = ROOT / 'deploy/private-dev'
PROVISIONING = DEPLOY / 'grafana/provisioning/alerting/private-dev.json'


def run(command, **kwargs):
    result = subprocess.run(command, text=True, capture_output=True, timeout=kwargs.pop("timeout", 90), **kwargs)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def load():
    return json.loads(PROVISIONING.read_text())


def all_rules():
    return load()['groups'][0]['rules'] + json.loads(PROVISIONING.with_name('product-activity.json').read_text())['groups'][0]['rules']


def static():
    config = load()
    group, = config['groups']
    assert config['apiVersion'] == 1 and group['interval'] == '60s'
    rules = group['rules']
    assert len(rules) == len({r['uid'] for r in rules}) == 5
    for rule in all_rules():
        a, b = rule['data']
        assert a['datasourceUid'] == 'private-dev-prometheus' and a['model']['instant']
        assert b['datasourceUid'] == '__expr__' and rule['condition'] == 'B'
        assert b['model']['type'] == 'threshold'
        assert b['model']['conditions'][0]['evaluator'] == {'params': [0], 'type': 'gt'}
        assert not re.search(r'\$(?:route|method|__range|__rate_interval)|(?i:password|token|user_id|game_id|correlation)', a['model']['expr'])
        assert rule['labels'] == ({'environment':'private-dev','service':'identity','severity':'info','category':'product'} if rule['uid']=='vgp-registration-window' else {'environment':'private-dev','service':'application','severity':'warning','category':'operational'})
        assert rule['annotations']['runbook_url'].endswith(('#private-dev-alerts','#product-growth'))
        # Titles, annotations and notification guidance are English.
        assert not re.search(r'[áéíóúñ¿¡]', json.dumps({k: rule[k] for k in ('title', 'annotations')}, ensure_ascii=False))
        assert not any(key.endswith('_es') for key in rule['annotations'])
        assert {'summary', 'action' if rule['labels']['category'] == 'operational' else 'observed_registrations'} <= set(rule['annotations'])
        dashboard = json.loads((DEPLOY / ('grafana/dashboards/' + rule['annotations']['__dashboardUid__'] + '.json')).read_text())
        assert int(rule['annotations']['__panelId__']) in {p['id'] for p in dashboard['panels']}
        assert rule['noDataState'] == ('Alerting' if rule['uid'] == 'vgp-telemetry-unavailable' else 'OK')
        assert rule['execErrState'] == ('Alerting' if rule['uid'] == 'vgp-telemetry-unavailable' else 'KeepLast')
    assert 'policies' not in config and 'contactPoints' not in config
    template = json.loads((DEPLOY/'grafana/notifications.json.template').read_text()
                          .replace('__TELEGRAM_CONTACT_POINT__','').replace('__TELEGRAM_PRODUCT_CONTACT_POINT__','')
                          .replace('__OPERATIONAL_RECEIVER__','empty').replace('__PRODUCT_RECEIVER__','empty')
                          .replace('__OPERATIONAL_MUTE__','["private-dev-ui-only"]').replace('__PRODUCT_MUTE__','["private-dev-ui-only"]'))
    policy, = template['policies']
    assert policy['receiver']=='empty'
    assert policy['group_by']==['environment','service','category','severity']
    assert len(policy['routes'])==3 and all(r['mute_time_intervals']==['private-dev-ui-only'] for r in policy['routes'])
    assert template['muteTimes'][0]['time_intervals']==[{'times':[{'start_time':'00:00','end_time':'24:00'}]}]
    assert not any('policies' in json.loads(p.read_text()) for p in PROVISIONING.parent.glob('*.json'))
    print('Six bounded rules, fixed labels, dashboard links and single disabled-by-default notification policy passed.',flush=True)


def local_engine():
    # This test never accepts an environment file, project name or remote daemon.
    context = json.loads(run(['docker', 'context', 'inspect']))[0]
    endpoint = context['Endpoints']['docker']['Host']
    assert not os.environ.get('DOCKER_HOST') and endpoint.startswith('unix://'), 'Use the local Unix Docker engine only'


def prometheus_image():
    return re.search(r'image: (prom/prometheus:\S+)', (DEPLOY / 'compose.observability.yaml').read_text())[1]


def rule_tests():
    """Promtool executes the EXACT provisioned expressions and pending periods."""
    local_engine()
    config = load()
    rules = all_rules()
    converted = [{'alert': r['uid'], 'expr': '(' + r['data'][0]['model']['expr'] + ') > 0',
                  'for': r['for'], 'labels': r['labels']} for r in rules]
    def series(name, values):
        return {'series': name, 'values': values}
    def samples(values):
        return ' '.join(str(x) for x in values)
    up = [series('up{job="application-otlp"}', '1+0x80'), series('up{job="collector"}', '1+0x80')]
    heartbeat = series('process_uptime_milliseconds{job="application-otlp"}', samples([i//2 * 60000 for i in range(81)]))
    base = up + [heartbeat]
    tests = []
    def case(name, inputs, firing=None, checks=('8m', '20m'), background=base):
        expected = firing or {}
        tests.append({'name': name, 'interval': '30s', 'input_series': background + inputs,
                      'alert_rule_test': [{'eval_time': at, 'alertname': rule['uid'],
                        'exp_alerts': [{'exp_labels': rule['labels'] | labels} for labels in expected.get((rule['uid'], at), [])]}
                       for at in checks for rule in rules]})
    case('idle / absent lazy HTTP and sync metrics', [])
    case('single error and expected empty catalogue or client rejection', [
        series('platform_http_errors_total{job="application-otlp",kind="server_failure",code="INTERNAL_ERROR"}', '0 0 1+0x78'),
        series('platform_http_errors_total{job="application-otlp",kind="server_failure",code="CATALOGUE_NOT_READY"}', '0+20x80'),
        series('platform_http_errors_total{job="application-otlp",kind="expected_absence",code="RATING_NOT_FOUND"}', '0+20x80')])
    counts = samples([max(0, min(i-4, 16))*2 for i in range(81)])
    case('unexpected failures fire then age out', [series('platform_http_errors_total{job="application-otlp",kind="server_failure",code="INTERNAL_ERROR"}', counts)],
         {('vgp-http-failures', '8m'): [{}]})
    case('counter reset is not a new failure', [series('platform_http_errors_total{job="application-otlp",kind="server_failure",code="INTERNAL_ERROR"}', '100+0x10 0+0x69')])
    route = 'job="application-otlp",uri="/api/v1/games",method="GET",status="200"'
    for volume in (2, 40):
        # Stop growth at minute 10: the same histogram then proves recovery at minute 20.
        vals = samples([min(i, 20)*volume for i in range(81)])
        inputs = [series('http_server_requests_milliseconds_count{' + route + '}', vals),
                  series('http_server_requests_milliseconds_bucket{' + route + ',le="1000"}', '0+0x80'),
                  series('http_server_requests_milliseconds_bucket{' + route + ',le="2000"}', vals),
                  series('http_server_requests_milliseconds_bucket{' + route + ',le="+Inf"}', vals)]
        case('latency with ' + ('sparse' if volume == 2 else 'sufficient') + ' traffic', inputs,
             {('vgp-http-latency', '8m'): [{'uri': '/api/v1/games', 'method': 'GET'}]} if volume == 40 else {})
    case('sustained pending then recovery', [series('hikaricp_connections_pending{job="application-otlp",pool="HikariPool-1"}', '0+0x3 2+0x12 0+0x63')],
         {('vgp-pool-pressure', '5m'): [{}]}, checks=('1m', '5m', '10m'))
    case('short queue is quiet', [series('hikaricp_connections_pending{job="application-otlp",pool="HikariPool-1"}', '0 1 1 0+0x77')])
    case('timeouts without pending then recovery', [series('hikaricp_connections_pending{job="application-otlp",pool="HikariPool-1"}', '0+0x80'),
        series('hikaricp_connections_timeout_total{job="application-otlp",pool="HikariPool-1"}', '0+0x3 2+0x76')],
        {('vgp-pool-pressure', '5m'): [{}]}, checks=('1m', '5m', '12m'))
    case('sync needing attention then window expiry', [series('catalogue_synchronization_run_assessment_total{job="application-otlp",assessment="attention"}', '0+0x3 1+0x76')],
         {('vgp-sync-failures', '8m'): [{'assessment': 'attention'}]})
    case('normal success, skips and idle are quiet', [series('catalogue_synchronization_run_assessment_total{job="application-otlp",assessment="clean"}', '0+1x80'),
        series('catalogue_synchronization_run_assessment_total{job="application-otlp",assessment="skipped"}', '0+1x80')])
    # A completed interval with only known IGDB data inconsistencies, and an operator stop, are
    # partial runs by outcome but need no operator.
    case('data-quality partial and operator stop are quiet', [
        series('catalogue_synchronization_run_total{job="application-otlp",outcome="partial"}', '0+0x3 2+0x76'),
        series('catalogue_synchronization_run_assessment_total{job="application-otlp",assessment="data_quality"}', '0+0x3 1+0x76'),
        series('catalogue_synchronization_run_assessment_total{job="application-otlp",assessment="stopped"}', '0+0x3 1+0x76')])
    case('healthy scrape but frozen OTLP', [], {('vgp-telemetry-unavailable', '10m'): [{}]}, checks=('2m', '10m'),
         background=up + [series('process_uptime_milliseconds{job="application-otlp"}', '60000+0x80')])
    case('missing heartbeat with healthy scrape targets', [],
         {('vgp-telemetry-unavailable', '8m'): [{}], ('vgp-telemetry-unavailable', '20m'): [{}]}, background=up)
    case('failed telemetry recovers after exports resume', [],
         {('vgp-telemetry-unavailable', '8m'): [{}]},
         background=up + [series('process_uptime_milliseconds{job="application-otlp"}',
                                samples([60000 if i < 24 else (i-22)//2*60000 for i in range(81)]))])
    case('one acquisition timeout is quiet', [series('hikaricp_connections_timeout_total{job="application-otlp",pool="HikariPool-1"}', '0+0x3 1+0x76')])
    case('interrupted partial sync is actionable', [series('catalogue_synchronization_run_total{job="application-otlp",outcome="partial"}', '0+0x3 1+0x76'),
        series('catalogue_synchronization_run_assessment_total{job="application-otlp",assessment="attention"}', '0+0x3 1+0x76')],
         {('vgp-sync-failures', '8m'): [{'assessment': 'attention'}]})
    case('scrape down', [], {('vgp-telemetry-unavailable', '8m'): [{}], ('vgp-telemetry-unavailable', '20m'): [{}]},
         background=[up[1], heartbeat, series('up{job="application-otlp"}', '0+0x80')])
    case('missing target and all application data', [], {('vgp-telemetry-unavailable', '8m'): [{}], ('vgp-telemetry-unavailable', '20m'): [{}]}, background=[])
    # A 2-minute scrape/OTLP interruption resets uptime but never exhausts the 5-minute pending period.
    case('expected restart is quiet and fresh export recovers', [], background=[up[1],
         series('up{job="application-otlp"}', '1+0x3 0+0x3 1+0x72'),
         series('process_uptime_milliseconds{job="application-otlp"}', samples([i//2*60000 if i<4 else (120000 if i<8 else (i-8)//2*60000) for i in range(81)]))])
    case('mixed codes including genuine 503 combine to an actionable failure', [
        series('platform_http_errors_total{job="application-otlp",kind="server_failure",code="INTERNAL_ERROR",status="500"}', samples([min(i,20)*0.3 for i in range(81)])),
        series('platform_http_errors_total{job="application-otlp",kind="server_failure",code="CATALOGUE_READ_FAILED",status="503"}', samples([min(i,20)*0.3 for i in range(81)]))],
        {('vgp-http-failures','8m'):[{}]})
    case('a later success cannot prove ordered sync recovery', [
        series('catalogue_synchronization_run_assessment_total{job="application-otlp",assessment="attention"}','0+0x3 1+0x76'),
        series('catalogue_synchronization_run_assessment_total{job="application-otlp",assessment="clean"}','0+0x5 1+0x74')],
        {('vgp-sync-failures','8m'):[{'assessment':'attention'}]})
    reg='keycloak_user_events_total{job="keycloak-product",realm="videogame-platform",event="register",error=""}'
    case('one observed registration opens one window', [series(reg,'0+0x3 1+0x76')],
         {('vgp-registration-window','4m'):[{}]},checks=('1m','4m','10m'))
    case('registration window fires once for multiple events then closes', [series(reg,'0+0x3 3+0x76')],
         {('vgp-registration-window','4m'):[{}]},checks=('1m','4m','10m'))
    case('another registration while firing extends the same window', [series(reg,'0+0x3 1+0x4 2+0x71')],
         {('vgp-registration-window','4m'):[{}],('vgp-registration-window','6m'):[{}]},checks=('4m','6m','12m'))
    case('a new registration opens a later window', [series(reg,'0+0x3 1+0x16 2+0x59')],
         {('vgp-registration-window','4m'):[{}],('vgp-registration-window','12m'):[{}]},checks=('4m','9m','12m'))
    case('failed registrations, other realms, email checks and logins excluded', [
        series(reg.replace('error=""','error="invalid_registration"'),'0+1x80'),
        series(reg.replace('videogame-platform','master'),'0+1x80'),
        series(reg.replace('register','verify_email'),'0+1x80'),
        series(reg.replace('register','login'),'0+1x80')])
    case('Keycloak reset without new event is quiet', [series(reg,'100+0x3 0+0x76')])
    case('a real registration after counter reset is observed', [series(reg,'100+0x3 0+0x8 1+0x67')],
         {('vgp-registration-window','8m'):[{}]},checks=('1m','8m','15m'))
    case('first lazy registration is an unobservable increase', [series(reg,'_ _ _ _ 1+0x76')])
    with tempfile.TemporaryDirectory(prefix='vgp-alert-rules-') as directory:
        temp = Path(directory)
        temp.chmod(0o755)
        (temp/'rules.json').write_text(json.dumps({'groups': [{'name':'private-dev','interval':'60s','rules':converted}]}))
        (temp/'tests.json').write_text(json.dumps({'rule_files':['rules.json'],'evaluation_interval':'60s','tests':tests}))
        print(run(['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL',
                   '--security-opt','no-new-privileges','--tmpfs','/tmp:rw,noexec,nosuid,size=32m','--memory','128m','--cpus','0.5','--pids-limit','32',
                   '--volume',str(temp)+':/tests:ro','--workdir','/tests','--entrypoint','/bin/promtool',
                   prometheus_image(),'test','rules','tests.json']), flush=True)
    print(run(['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL',
               '--security-opt','no-new-privileges','--volume',str(DEPLOY/'prometheus')+':/etc/prometheus:ro',
               '--entrypoint','/bin/promtool',prometheus_image(),'check','config','/etc/prometheus/prometheus.yaml']),flush=True)
    print(f'{len(tests)} Promtool scenarios passed using original expressions and pending periods.', flush=True)


def smoke(native_only=False):
    local_engine()
    with tempfile.TemporaryDirectory(prefix='vgp-alert-smoke-') as directory:
        temp = Path(directory)
        temp.chmod(0o755)
        project = 'vgp-alert-smoke-' + secrets.token_hex(4)
        password = secrets.token_urlsafe(32)
        private = temp/'secrets'
        private.mkdir(mode=0o700)
        for name in ('grafana-admin-password', 'grafana-database-password', 'grafana-telegram-bot-token', 'grafana-telegram-chat-id'):
            (private/name).write_text(password)
            (private/name).chmod(0o644)
        definition = {'services': {name: {'extends': {'file': str(DEPLOY/'compose.observability.yaml'), 'service': name}}
                       for name in ('telemetry','prometheus','grafana')},
                      'networks': {'telemetry': {'internal': True}, 'edge': {}},
                      'volumes': {'prometheus-data': {}, 'grafana-data': {}},
                      'secrets': {name.replace('-','_'): {'file': str(private/name)} for name in ('grafana-admin-password','grafana-database-password','grafana-telegram-bot-token','grafana-telegram-chat-id')}}
        # Resolve the SAME private-dev overrides; do not maintain a second alerting configuration.
        private_service = (DEPLOY/'compose.yaml').read_text().split('  grafana:\n',1)[1].split('\n  catalogue-localizer:',1)[0]
        definition['services']['grafana']['environment'] = dict(re.findall(r'^      (GF_\w+): [\"]?([^\"\n]+)',private_service,re.M))
        definition['services']['grafana']['environment']['GF_SERVER_ROOT_URL']='http://127.0.0.1:3000'
        definition['services']['grafana']['secrets']=['grafana_telegram_bot_token','grafana_telegram_chat_id']
        definition['services']['grafana']['volumes'] = [str(PROVISIONING.parent)+':/etc/grafana/provisioning/alerting:ro',str(DEPLOY/'grafana/bin/provision-notifications')+':/etc/grafana/provision-notifications:ro',str(DEPLOY/'grafana/notifications.json.template')+':/etc/grafana/notifications.json.template:ro']
        source = temp/'compose.json'
        source.write_text(json.dumps(definition))
        compose = ['docker','compose','--project-name',project,'--file',str(source)]
        rendered = json.loads(run(compose + ['config','--format','json']))
        rendered['services']['grafana']['ports'] = [{'target':3000,'published':'0','host_ip':'127.0.0.1','protocol':'tcp'}]
        source.write_text(json.dumps(rendered))
        authorization = 'Basic ' + base64.b64encode(('owner:'+password).encode()).decode()
        origin = None
        def api(path, authenticated=True, expected=200, data=None, method=None):
            request = urllib.request.Request(origin+path,data=json.dumps(data).encode() if data is not None else None,method=method,headers=({'Authorization':authorization,'Content-Type':'application/json'} if authenticated else {}))
            try:
                with urllib.request.urlopen(request,timeout=10) as response:
                    status, body = response.status, response.read()
            except urllib.error.HTTPError as error:
                status, body = error.code, error.read()
            assert status == expected, f'{path}: HTTP {status}'
            return json.loads(body) if body else None
        def query(expr):
            return api('/api/datasources/proxy/uid/private-dev-prometheus/api/v1/query?'+urllib.parse.urlencode({'query':expr}))['data']['result']
        def poll(check, description):
            deadline = time.monotonic()+90
            while time.monotonic()<deadline:
                try:
                    if check():
                        print(description,flush=True)
                        return
                except (OSError, AssertionError, KeyError):
                    pass
                time.sleep(2)
            raise AssertionError(description+' did not pass within bounded wait')
        started = time.monotonic()
        def emit(pending):
            now = str(time.time_ns())
            def gauge(name,value,unit='',labels={}):
                return {'name':name,'unit':unit,'gauge':{'dataPoints':[{'timeUnixNano':now,'asDouble':value,
                    'attributes':[{'key':k,'value':{'stringValue':v}} for k,v in labels.items()]}]}}
            payload = {'resourceMetrics':[{'scopeMetrics':[{'metrics':[
                gauge('hikaricp.connections.pending',pending,labels={'pool':'HikariPool-1'}),
                gauge('process.uptime',(time.monotonic()-started)*1000,'ms')]}]}]}
            run(compose+['exec','-T','prometheus','wget','-qO-','--header=Content-Type: application/json',
                         '--post-file=/dev/stdin','http://telemetry:4318/v1/metrics'],input=json.dumps(payload))
        def state():
            result = api('/api/prometheus/grafana/api/v1/rules')
            return {r['name']:r.get('state') for g in result['data']['groups'] for r in g['rules']}
        try:
            run(compose+['up','--detach','telemetry','prometheus','grafana'])
            port = run(compose+['port','grafana','3000']).strip().rsplit(':',1)[1]
            origin = 'http://127.0.0.1:'+port
            poll(lambda: api('/api/health',False)['database']=='ok','Grafana healthy')
            provisioned = api('/api/v1/provisioning/alert-rules')
            expected = {r['uid']:r for r in all_rules()}
            assert {r['uid'] for r in provisioned} == set(expected)
            for rule in provisioned:
                assert rule['provenance']=='file' and rule['for']==expected[rule['uid']]['for']
                assert rule['noDataState']==expected[rule['uid']]['noDataState']
                assert rule['execErrState']==expected[rule['uid']]['execErrState']
                assert rule['data'][0]['model']['expr']==expected[rule['uid']]['data'][0]['model']['expr']
            api('/api/v1/provisioning/alert-rules',False,401)
            policy = api('/api/v1/provisioning/policies')
            assert policy['routes'][0]['mute_time_intervals']==['private-dev-ui-only']
            assert api('/api/v1/provisioning/mute-timings')[0]['name']=='private-dev-ui-only'
            # Original rule timing, original Collector and Prometheus config; no acceleration.
            print('Exact file provisioning, pending periods, mute policy and anonymous denial passed.',flush=True)
            if native_only:
                def warm():
                    emit(0)
                    sample=query('sum(changes(process_uptime_milliseconds{job="application-otlp"}[3m]))')
                    return bool(sample) and float(sample[0]['value'][1])>0
                poll(warm,'Fresh advancing disposable OTLP before native state checks')
                poll(lambda:set(state().values())=={'inactive'},'Idle rules quiet before native state checks')
            else:
                for pending, target in ((1,'firing'),(0,'inactive')):
                    deadline = time.monotonic()+300
                    next_emit = 0
                    observed = set()
                    while time.monotonic()<deadline:
                        if time.monotonic()>=next_emit:
                            emit(pending)
                            next_emit=time.monotonic()+15
                        current=state()
                        observed.update(current.values())
                        if current.get('Hikari connection acquisition pressure')==target:
                            print(datetime.datetime.now(datetime.timezone.utc).isoformat()+': Hikari '+target+' (states observed: '+','.join(sorted(observed))+')',flush=True)
                            break
                        time.sleep(2)
                    else:
                        raise AssertionError('Pool alert did not reach '+target)
            assert query('hikaricp_connections_pending{job="application-otlp"}')[0]['value'][1]=='0'
            assert set(state().values()) == {'inactive'}, 'Idle telemetry/symptom rules should be quiet after recovery'
            evaluated = api('/api/prometheus/grafana/api/v1/rules')['data']['groups']
            assert all(r['health']=='ok' and not r.get('lastError') for g in evaluated for r in g['rules'])
            # Native engine behavior cannot be inferred from Promtool. Only these disposable
            # clones use zero pending time; the provisioned rules above retain original periods.
            def fixture(uid, source_uid, expression, native=False):
                source=next(r for r in provisioned if r['uid']==source_uid)
                copy={k:v for k,v in json.loads(json.dumps(source)).items() if k in
                      {'title','condition','data','noDataState','execErrState','for','annotations','labels'}}
                copy.update({'uid':uid,'title':uid,'folderUID':source['folderUID'],'ruleGroup':'fixture-native','for':'0s'})
                copy['data'][0]['model']['expr']=expression
                if native:
                    copy.update({'noDataState':'NoData','execErrState':'Error'})
                api('/api/v1/provisioning/alert-rules',expected=201,data=copy,method='POST')
                return copy
            fixture('fixture-nodata-ok','vgp-http-failures','nonexistent_fixture_metric')
            fixture('fixture-nodata-alert','vgp-telemetry-unavailable','nonexistent_fixture_metric')
            fixture('fixture-error-alert','vgp-telemetry-unavailable','sum(')
            fixture('fixture-native-nodata','vgp-telemetry-unavailable','nonexistent_fixture_metric',native=True)
            fixture('fixture-native-error','vgp-telemetry-unavailable','sum(',native=True)
            keep=fixture('fixture-keep-last','vgp-http-failures','sum(hikaricp_connections_pending{job="application-otlp"})')
            folder=keep['folderUID']
            group=[r for r in api('/api/v1/provisioning/alert-rules') if r['ruleGroup']=='fixture-native']
            api('/api/v1/provisioning/folder/'+folder+'/rule-groups/fixture-native',
                data={'title':'fixture-native','folderUid':folder,'interval':10,'rules':group},method='PUT')
            poll(lambda:state().get('fixture-nodata-ok')=='inactive' and
                 state().get('fixture-nodata-alert')=='firing' and state().get('fixture-error-alert')=='firing',
                 'Native No Data -> Normal/Alerting and Error -> Alerting behaviors passed (disposable zero-pending clones)')
            active=api('/api/alertmanager/grafana/api/v2/alerts')
            assert {'DatasourceNoData','DatasourceError'} <= {a['labels']['alertname'] for a in active}
            print('Native DatasourceNoData/DatasourceError instances created; notification policy explicitly routes both.',flush=True)
            emit(1)
            poll(lambda:state().get('fixture-keep-last')=='firing','KeepLast fixture initially firing')
            # An edited expression resets Grafana state. Fail the disposable datasource
            # instead, preserving the rule and its previous state throughout the error.
            run(compose+['stop','prometheus'])
            stopped_at=time.time()
            def failing_keeps_state():
                rows=[r for g in api('/api/prometheus/grafana/api/v1/rules')['data']['groups'] for r in g['rules']]
                # KeepLast presents health=ok here and omits lastError in this pinned API.
                # Prove a NEW evaluation while its datasource is stopped, with state retained.
                return any(r['name']=='fixture-keep-last' and r.get('state')=='firing' and
                           datetime.datetime.fromisoformat(r['lastEvaluation'].replace('Z','+00:00')).timestamp()>stopped_at for r in rows)
            poll(failing_keeps_state,'Query error retains previous firing state with KeepLast')
            run(compose+['start','prometheus'])
            emit(0)
            poll(lambda:state().get('fixture-keep-last')=='inactive','Native query recovery returns to Normal')
            for uid in ('fixture-nodata-ok','fixture-nodata-alert','fixture-error-alert','fixture-native-nodata','fixture-native-error','fixture-keep-last'):
                api('/api/v1/provisioning/alert-rules/'+uid,expected=204,method='DELETE')
            original = {r['uid']:r['data'][0]['model']['expr'] for r in provisioned}
            run(compose+['up','--detach','--no-deps','--force-recreate','grafana'])
            port = run(compose+['port','grafana','3000']).strip().rsplit(':',1)[1]
            origin='http://127.0.0.1:'+port
            poll(lambda: api('/api/health',False)['database']=='ok','Grafana recreated with original volume')
            actual = api('/api/v1/provisioning/alert-rules')
            assert {r['uid']:r['data'][0]['model']['expr'] for r in actual}==original
            assert api('/api/v1/provisioning/policies')['routes'][0]['mute_time_intervals']==['private-dev-ui-only']
            assert len(api('/api/search?type=dash-db'))==5
            print('Idempotent recreation, six unchanged rules, five dashboards and notification mute passed.',flush=True)
            stats = run(['docker','stats','--no-stream','--format','{{.Name}} {{.CPUPerc}} {{.MemUsage}}']+[
                        run(compose+['ps','--quiet',name]).strip() for name in ('telemetry','prometheus','grafana')])
            print('Disposable end-of-test resource snapshot (not live capacity evidence):\n'+stats,flush=True)
        except Exception:
            print(run(compose+['logs','--tail','60','grafana']).replace(password,'[redacted]').replace(authorization,'[redacted]'),flush=True)
            raise
        finally:
            run(compose+['down','--volumes','--remove-orphans'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group(required=True)
    for name in ('static','rules','smoke','native-smoke'):
        mode.add_argument('--'+name,action='store_true')
    args=parser.parse_args()
    static()
    if args.rules or args.smoke:
        rule_tests()
    if args.smoke or args.native_smoke:
        smoke(native_only=args.native_smoke)


if __name__=='__main__':
    main()
