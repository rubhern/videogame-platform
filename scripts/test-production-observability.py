#!/usr/bin/env python3
"""Production telemetry contract, exact PromQL tests and disposable integration proof.

No real configuration, credentials, DNS, Telegram endpoint or running environment.
"""
import argparse
import base64
import copy
import importlib.util
import json
import ipaddress
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PROD = ROOT / 'deploy/public-production'
OBS = PROD / 'observability'
SHARED = ROOT / 'deploy/private-dev'


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


runtime = module(ROOT / 'scripts/test-public-production-runtime.py', 'production_runtime_contract')
sampler = module(OBS / 'sample-host.py', 'production_host_sampler')


def run(args, **kwargs):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True, timeout=180, **kwargs)
    if result.returncode:
        raise AssertionError(result.stderr[-4000:] or result.stdout[-4000:])
    return result.stdout


def engine():
    assert not os.environ.get('DOCKER_HOST'), 'Use the local Docker engine only'
    assert run(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).strip().startswith('unix:///')


def rules():
    return json.loads((OBS / 'grafana/alerts.json').read_text())['groups'][0]['rules']


def rendered(directory):
    env, _, _ = runtime.synthetic_inputs(directory)
    return json.loads(run(['docker', 'compose', '-f', PROD / 'compose.yaml', '-f', PROD / 'compose.observability.yaml',
                           '--env-file', env, '--profile', 'application', '--profile', 'deployment', 'config', '--format', 'json']))


def static():
    with tempfile.TemporaryDirectory() as temp:
        config = rendered(temp)
    services = config['services']
    names = {'telemetry', 'prometheus', 'grafana', 'node-exporter', 'blackbox'}
    # The base-only topology cannot reveal exhaustion when the overlay starts.
    ipam = config['networks']['telemetry']['ipam']['config'][0]
    subnet, pool = (ipaddress.ip_network(ipam[k]) for k in ('subnet','ip_range'))
    dynamic = sum('telemetry' in s.get('networks',{}) and not (s['networks']['telemetry'] or {}).get('ipv4_address') for s in services.values())
    assert pool.subnet_of(subnet) and pool.num_addresses - 2 >= dynamic + 2
    assert ipaddress.ip_address(ipam['gateway']) not in pool
    assert ipaddress.ip_address('10.203.3.2') not in pool

    assert sum(runtime.megabytes(services[n]['mem_limit']) for n in names) + 64 == 640
    for name in names:
        service = services[name]
        assert not service.get('ports') and not service.get('privileged')
        assert service['read_only'] and service['cap_drop'] == ['ALL']
        assert not service.get('cap_add') and not service.get('pid') and not service.get('network_mode')
        assert '@sha256:' in service['image']
        assert all('docker.sock' not in v['source'] for v in service.get('volumes', []))
    for service in ('application', 'postgres', 'keycloak', 'caddy'):
        assert not any(x in services[service].get('depends_on', {}) for x in names)
    assert services['application']['environment']['TELEMETRY_OTLP_METRICS_ENABLED'] == 'true'
    assert services['application']['environment']['MANAGEMENT_SERVER_ADDRESS'] == '127.0.0.1'
    assert services['grafana']['environment']['GRAFANA_PRODUCT_NOTIFICATIONS_ENABLED'] == 'false'
    assert set(services['grafana']['networks']) == {'telemetry', 'notification-egress', 'observability-data'}
    assert set(services['postgres']['networks']) == {'application-data', 'identity-data', 'observability-data'}
    # Only the read-only, existing analytics grants and two connections; no new DB privilege.
    sql = (SHARED / 'grafana/provision-reader.sql').read_text()
    assert 'CONNECTION LIMIT 2' in sql and 'default_transaction_read_only = on' in sql
    assert 'GRANT SELECT ON catalogue.observability_inventory' in sql and 'keycloak' not in sql.lower()
    canonical = list((SHARED / 'grafana/dashboards').glob('*.json'))
    assert len(canonical) == 5 and not list((OBS / 'grafana').glob('vgp-*.json'))
    for path in canonical:
        dashboard = json.loads(path.read_text())
        assert dashboard['uid'] == path.stem
        for panel in dashboard['panels']:
            for target in panel.get('targets', []):
                datasource = target.get('datasource', panel.get('datasource', {}))
                assert datasource.get('uid') in ('private-dev-prometheus', 'private-dev-postgres')
    for rule in rules():
        assert rule['labels']['environment'] == 'production'
        assert rule['labels']['category'] == 'operational'
        assert rule['annotations']['action']
        assert '#276' in rule['annotations']['calibration']
        if rule['uid'] in ('prod-telemetry-missing','prod-sample-stale'):
            assert rule['data'][0]['model']['expr'].endswith('or vector(0)')
            assert rule['noDataState']=='Alerting' and rule['execErrState']=='Alerting'
        dashboard=json.loads((OBS/'grafana/capacity.json').read_text())
        assert int(rule['annotations']['__panelId__']) in {panel['id'] for panel in dashboard['panels']}
        assert rule['data'][0]['datasourceUid'] in ('private-dev-prometheus', 'private-dev-postgres')
    print('Production isolation, 640 MiB envelope, canonical dashboards and datasource contracts passed.', flush=True)


class HostSignals(unittest.TestCase):
    NOW = 1800000000
    @unittest.skipUnless(sys.platform == 'linux', 'The host bridge measures Linux processes')
    def test_bridge_memory_does_not_inherit_the_harness_peak(self):
        smoke = module(ROOT / 'scripts/production_observability_smoke.py', 'host_bridge_smoke')
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / 'sampler.py'
            fixture_code = '''import subprocess, sys
def snapshot(project):
    assert project == 'vgp-telemetry-instrumentation-fixture'
    subprocess.run([sys.executable, '-I', '-c', 'pass'], check=True)
def edge_observations(text):
    assert len(text.splitlines()) == 19999
    workload = bytearray(PROBE_MIB * 1024 * 1024)
    del workload
'''
            # Keep a large harness alive while exec starts the much smaller probe.
            # ru_maxrss falsely charges this allocation to the probe on Linux.
            harness = bytearray(96 * 1024 * 1024)
            for own_mib in (0, 96):
                with self.subTest(own_allocation_mib=own_mib):
                    fixture.write_text(fixture_code.replace('PROBE_MIB', str(own_mib)))
                    result = subprocess.run([sys.executable, '-I', '-c', smoke.HOST_BRIDGE_PROBE, str(fixture)],
                                            capture_output=True, text=True, check=True, timeout=10)
                    self.assertEqual(len(harness), 96 * 1024 * 1024)
                    measured = json.loads(result.stdout)
                    if own_mib:
                        # A real peak must remain visible even after memory is freed.
                        self.assertGreaterEqual(measured['parentPeakKiB'], own_mib * 1024)
                        self.assertGreater(measured['conservativeCombinedMiB'], 64)
                    else:
                        self.assertLess(measured['parentPeakKiB'], 64 * 1024)
                        self.assertLess(measured['conservativeCombinedMiB'], 64)
                    self.assertGreater(measured['childPeakKiB'], 0)
                    self.assertEqual(measured['records'], 19999)

    def test_unknown_is_not_zero_health(self):
        text = sampler.render([], now=self.NOW)
        self.assertIn('production_offhost_verified 0', text)
        self.assertIn('production_capacity_calibrated 0', text)
        self.assertNotIn('production_offhost_recovery_point_seconds', text)
        self.assertNotIn('production_capacity_rps ', text)
        self.assertIn('production_backup_last_attempt_failed 1',sampler.render([],backup={'kind':'backup','outcome':'failure'},now=self.NOW))
        self.assertEqual(text.count('production_container_running{'), 10)
        self.assertIn('production_host_sample_timestamp_seconds 1800000000', text)

    def test_local_or_corrupt_backup_never_verifies(self):
        for item in ({'kind':'encrypted-copy'}, {'kind':'encrypted-pull','independentRecoveryVerified':False}, {'kind':'offhost-verification'}):
            self.assertIn('production_offhost_verified 0', sampler.render([], item, now=self.NOW))

    def test_verified_fresh_evidence_and_expiry(self):
        date = lambda seconds: __import__('datetime').datetime.fromtimestamp(seconds, __import__('datetime').timezone.utc).isoformat()
        proof = {'kind':'offhost-verification','environment':'production','verifiedAt':date(self.NOW-60),
                 'recoveryPoint':date(self.NOW-3600),'ciphertextIntegrity':True,'storageIsolationVerified':True,'independentRecoveryVerified':True}
        self.assertIn('production_offhost_verified 1', sampler.render([], proof, now=self.NOW))
        self.assertIn('production_offhost_verified 0', sampler.render([], proof, now=self.NOW+36*86400))
        self.assertIn('production_offhost_verified 0', sampler.render([], proof, now=self.NOW-7200))
        cap = {'scope':'real-vps','environment':'production','measuredAt':date(self.NOW-3600),'expiresAt':date(self.NOW+3600),'sustainableRps':42}
        self.assertIn('production_capacity_rps 42', sampler.render([], capacity=cap, now=self.NOW))
        cap['scope']='local'
        self.assertNotIn('production_capacity_rps ', sampler.render([], capacity=cap, now=self.NOW))

    def test_offhost_attestation_refuses_local_proof(self):
        import sys
        sys.path.insert(0,str(OBS))
        verifier=module(OBS/'verify-offhost.py','offhost_verifier')
        date=__import__('datetime').datetime.fromtimestamp(self.NOW-60,__import__('datetime').timezone.utc).isoformat()
        manifest={'backupId':'fixture','recoveryPoint':date}
        pull={'kind':'encrypted-pull','outcome':'success','ciphertextIntegrity':True,'backupId':'fixture'}
        restore={'kind':'recovery-rehearsal','outcome':'success','deletionReplay':True,'readiness':True,'scope':'owner-reconstructed-target','backupId':'fixture'}
        isolation={'environment':'production','storageIsolationVerified':True,'reviewedAt':date}
        self.assertTrue(verifier.attest(manifest,pull,restore,isolation,self.NOW)['independentRecoveryVerified'])
        for key,value in [('scope','isolated-local'),('deletionReplay',False),('backupId','other')]:
            altered={**restore,key:value}
            with self.assertRaises(ValueError):verifier.attest(manifest,pull,altered,isolation,self.NOW)

    def test_edge_observation_bounds_and_privacy(self):
        record={'ts':self.NOW,'logger':'http.log.access.log0','status':503,'response_origin':'overload'}
        output=sampler.edge_observations(json.dumps(record),self.NOW)
        self.assertIn('production_edge_observation_complete 1',output)
        self.assertIn('origin="overload",status="503"} 1',output)
        upstream={k:v for k,v in record.items() if k!='response_origin'}
        self.assertIn('origin="upstream",status="503"} 1',sampler.edge_observations(json.dumps(upstream),self.NOW))
        record['request']={'remote_ip':'sensitive-marker'}
        output=sampler.edge_observations(json.dumps(record),self.NOW)
        self.assertIn('production_edge_observation_complete 0',output)
        self.assertNotIn('sensitive-marker',output)
        self.assertNotIn('production_edge_responses',output)
        self.assertIn('production_edge_observation_complete 0',sampler.edge_observations('x'*4194305,self.NOW))

    def test_cgroup_and_atomic_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory)
            for name,content in {'cpu.stat':'usage_usec 1000000\nnr_throttled 2\nnr_periods 10\n','memory.events':'oom_kill 1\n','memory.max':'1000','memory.current':'900','cpu.max':'100000 100000'}.items():
                (d/name).write_text(content)
            values = sampler.cgroup_values(d)
            self.assertEqual(values['cpu_seconds_total'], 1)
            self.assertEqual(values['oom_total'], 1)
            sampler.atomic(d/'host.prom','old')
            sampler.atomic(d/'host.prom','new')
            self.assertEqual((d/'host.prom').read_text(),'new')
            (d/'unsafe.json').write_text('{}');(d/'unsafe.json').chmod(0o666)
            with self.assertRaises(ValueError): sampler.protected_json(d/'unsafe.json')
            (d/'memory.max').write_text('max')
            with self.assertRaises(ValueError): sampler.cgroup_values(d)


def promtool():
    engine()
    shared = json.loads((SHARED / 'grafana/provisioning/alerting/private-dev.json').read_text())['groups'][0]['rules']
    production = [r for r in rules() if r['data'][0]['datasourceUid'] == 'private-dev-prometheus']
    image = re.search(r'image: (prom/prometheus:\S+)', (SHARED / 'compose.observability.yaml').read_text())[1]
    cases=[]
    sentinels=[('node_cpu_seconds_total{job="host",mode="idle"}','0+1x60'),
               ('node_memory_MemAvailable_bytes{job="host"}','100+0x60'),
               ('node_filesystem_avail_bytes{job="host",mountpoint="/"}','1000+0x60')]
    sentinels += [(f'probe_success{{job="{j}"}}','1+0x60') for j in ('identity-health','tls-product','tls-identity')]
    sentinels += [(f'probe_ssl_earliest_cert_expiry{{job="{j}"}}','10000000+0x60') for j in ('tls-product','tls-identity')]
    def case(uid, series, fire='10m', labels=None, clear='25m'):
        rule=next(r for r in production if r['uid']==uid)
        if uid=='prod-telemetry-missing':
            present={name for name,_ in series}
            series=[*series,*[(name,values) for name,values in sentinels if name not in present]]
        expected={**rule['labels'], **(labels or {}), 'alertname':uid}
        # Alert annotations aren't part of these translated Prometheus rules.
        cases.append({'name':uid,'interval':'1m','input_series':[{'series':n,'values':v} for n,v in series],
                      'alert_rule_test':[{'eval_time':fire,'alertname':uid,'exp_alerts':[{'exp_labels':expected,'exp_annotations':{}}]},
                                         {'eval_time':clear,'alertname':uid,'exp_alerts':[]}]})
    case('prod-memory-warning',[('production_container_memory_bytes{service="application"}','900+0x14 500+0x40'),('production_container_memory_limit_bytes{service="application"}','1000+0x55')],labels={'service':'application'})
    case('prod-memory-critical',[('production_container_memory_bytes{service="application"}','950+0x14 500+0x40'),('production_container_memory_limit_bytes{service="application"}','1000+0x55')],labels={'service':'application'})
    case('prod-backup-unverified',[('production_offhost_verified','0+0x14 1+0x40'),('production_backup_attempt_observed','1+0x55'),('production_backup_last_attempt_failed','0+0x55')])
    case('prod-tls-critical',[('probe_ssl_earliest_cert_expiry{job="tls-product"}','100000+0x14 99999999+0x40')],labels={'job':'tls-product'})
    case('prod-identity-health',[('probe_success{job="identity-health"}','0+0x14 1+0x40')],labels={'job':'identity-health'})
    case('prod-upstream-503',[('production_edge_responses_5m{origin="upstream",status="503"}','10+0x14 0+0x40')],clear='30m')
    case('prod-edge-503',[('production_edge_responses_5m{origin="overload",status="503"}','20+0x14 0+0x40')])
    case('prod-cpu-warning',[('production_container_cpu_seconds_total{service="application"}','0+48x14 672+0x40'),('production_container_cpu_limit_cores{service="application"}','1+0x55')],labels={'service':'application'},clear='30m')
    case('prod-cpu-critical',[('production_container_cpu_seconds_total{service="application"}','0+60x14 840+0x40'),('production_container_cpu_limit_cores{service="application"}','1+0x55')],labels={'service':'application'},clear='30m')
    case('prod-host-pressure',[('node_memory_MemAvailable_bytes','100+0x14 500+0x40'),('node_memory_MemTotal_bytes','1000+0x55')])
    case('prod-disk-warning',[('node_filesystem_avail_bytes{mountpoint="/"}','200+0x14 500+0x40'),('node_filesystem_size_bytes{mountpoint="/"}','1000+0x55')],labels={'mountpoint':'/'})
    case('prod-disk-critical',[('node_filesystem_avail_bytes{mountpoint="/"}','100+0x14 500+0x40'),('node_filesystem_size_bytes{mountpoint="/"}','1000+0x55')],labels={'mountpoint':'/'})
    case('prod-capacity-warning',[('caddy_http_request_duration_seconds_count{job="caddy",handler="subroute"}','0+480x14 6720+0x40'),('production_capacity_rps','10+0x55'),('production_capacity_calibrated','1+0x55')],clear='30m')
    case('prod-capacity-critical',[('caddy_http_request_duration_seconds_count{job="caddy",handler="subroute"}','0+600x14 8400+0x40'),('production_capacity_rps','10+0x55'),('production_capacity_calibrated','1+0x55')],clear='30m')
    case('prod-pool-critical',[('hikaricp_connections_timeout_total','0+2x14 28+0x40')],clear='30m')
    case('prod-sample-stale',[('production_host_sample_timestamp_seconds','0+60x55'),('production_edge_observation_complete','0+0x14 1+0x40'),('production_edge_access_records_5m','1+0x55'),('node_textfile_scrape_error','0+0x55')])
    case('prod-oom-restarts',[('production_container_oom_killed{service="application"}','1+0x14 0+0x40')],labels={'service':'application'})
    case('prod-backup-stale-warning',[('production_offhost_recovery_point_seconds','-70000+0x14 900+60x40')])
    case('prod-backup-stale-critical',[('production_offhost_recovery_point_seconds','-100000+0x14 900+60x40')])
    case('prod-upstream-503',[('production_edge_responses_5m{origin="upstream",status="502"}','10+0x14 0+0x40')],clear='30m')
    # Missing an expected scrape target, including its disappearance from the config.
    expected=['application-otlp','collector','caddy','host','keycloak-product','keycloak-security','tls-product','tls-identity','identity-health','prometheus']
    case('prod-telemetry-missing',[(f'up{{job="{j}"}}','0+0x14 1+0x40' if j=='collector' else '1+0x55') for j in expected],labels={'job':'collector'})
    case('prod-telemetry-missing',[(f'up{{job="{j}"}}','_ _ _ _ _ _ _ _ _ _ _ _ _ _ _ 1+0x40' if j=='collector' else '1+0x55') for j in expected],labels={'job':'collector'})
    case('prod-telemetry-missing',[(f'up{{job="{j}"}}','1+0x60') for j in expected]+[('node_filesystem_avail_bytes{job="host",mountpoint="/"}','_ _ _ _ _ _ _ _ _ _ _ _ _ _ _ 1000+0x40')],labels={'job':'host','mountpoint':'/'})
    # Healthy/quiet traffic, no capacity proof and no abuse events never fire those rules.
    cases.append({'name':'quiet traffic and unknown capacity','interval':'1m','input_series':[{'series':'production_capacity_calibrated','values':'0+0x60'}],
                  'alert_rule_test':[{'eval_time':'20m','alertname':uid,'exp_alerts':[]} for uid in ['prod-capacity-warning','prod-capacity-critical','prod-traffic-trend','prod-identity-failures','prod-upstream-503']]})
    cases.append({'name':'explicit healthy availability values','interval':'1m',
                  'input_series':[{'series':f'up{{job="{j}"}}','values':'1+0x60'} for j in expected]+[{'series':name,'values':values} for name,values in sentinels]+[
                      {'series':'production_host_sample_timestamp_seconds','values':'0+60x60'},
                      {'series':'production_edge_observation_complete','values':'1+0x60'},
                      {'series':'production_edge_access_records_5m','values':'0+0x60'},
                      {'series':'node_textfile_scrape_error','values':'0+0x60'}],
                  'promql_expr_test':[{'expr':next(r for r in production if r['uid']==uid)['data'][0]['model']['expr'],
                                      'eval_time':'10m','exp_samples':[{'labels':'{}','value':0}]} for uid in ('prod-telemetry-missing','prod-sample-stale')]})
    with tempfile.TemporaryDirectory(prefix='vgp-prod-promql-') as directory:
        d=Path(directory);d.chmod(0o755)
        (d/'rules.json').write_text(json.dumps({'groups':[{'name':'production','interval':'60s','rules':[{'alert':r['uid'],'expr':'('+r['data'][0]['model']['expr']+') > 0','for':r['for'],'labels':r['labels']} for r in production]}]}))
        (d/'tests.json').write_text(json.dumps({'rule_files':['rules.json'],'evaluation_interval':'60s','tests':cases}))
        print(run(['docker','run','--rm','--network','none','--read-only','--tmpfs','/tmp:rw,size=32m','--volume',f'{d}:/tests:ro','--workdir','/tests','--entrypoint','/bin/promtool',image,'test','rules','tests.json']),flush=True)
        # Parse every shared dashboard PromQL expression after replacing Grafana variables.
        expressions=[]
        for path in [*(SHARED/'grafana/dashboards').glob('*.json'),OBS/'grafana/capacity.json']:
            for panel in json.loads(path.read_text())['panels']:
                for target in panel.get('targets',[]):
                    if 'expr' in target:
                        expr=target['expr']
                        for key in ('$__rate_interval','$__range','$__interval'):expr=expr.replace(key,'5m')
                        for key in ('$route','$method'):expr=expr.replace(key,'.*')
                        expressions.append({'record':'fixture_query_'+str(len(expressions)),'expr':expr})
        (d/'queries.json').write_text(json.dumps({'groups':[{'name':'dashboards','rules':expressions}]}))
        print(run(['docker','run','--rm','--network','none','--volume',f'{d}:/tests:ro','--entrypoint','/bin/promtool',image,'check','rules','/tests/queries.json']),flush=True)
    print(run(['docker','run','--rm','--network','none','--volume',f'{OBS}:/etc/prometheus:ro','--entrypoint','/bin/promtool',image,'check','config','/etc/prometheus/prometheus.yaml']),flush=True)
    print(f'{len(cases)} original-timing firing/recovery/absence cases and {len(expressions)} dashboard queries passed.',flush=True)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(__doc__);parser.add_argument('--smoke',action='store_true');args=parser.parse_args()
    static()
    result=unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(HostSignals))
    if not result.wasSuccessful():raise SystemExit(1)
    promtool()
    if args.smoke:
        import production_observability_smoke
        production_observability_smoke.main()
