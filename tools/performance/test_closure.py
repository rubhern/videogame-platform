"""Offline completion controls; never contacts private-dev, Windows or providers."""
import argparse
import json
import io
import urllib.error
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock,patch
import closure
import remote_worker as worker
import summarize_closure

class Completion(unittest.TestCase):
    def test_condensed_evidence_excludes_protected_metadata_and_unknown_manifest_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            def write(name,value): (root/name).write_text(json.dumps(value))
            write('target.json',{'sourceRevision':'sha','image':'image','applicationVersion':'version',
                'origin':'private-origin','password':'private-secret',
                'configuration':{'localizer':{'modelRevision':'model'}}})
            write('manifest.json',{'sourceRevision':'sha','purpose':'completion','sutRevision':'sha',
                'scriptsSha256':{},'extra':'private-secret'})
            write('restoration.json',{'privateDataUnchanged':True,'privateAfter':{'private-user':'private-secret'}})
            write('policy.json',{'maximumVus':30})
            result=json.dumps(summarize_closure.summarize(root))
            self.assertNotIn('private-origin',result)
            self.assertNotIn('private-secret',result)
            self.assertNotIn('private-user',result)

    def test_generator_resources_retains_missing_final_exited_process_sample(self):
        result=summarize_closure.generator_resources([{'cpuSeconds':0,'workingSetBytes':10},
            {'cpuSeconds':1,'workingSetBytes':20},{'cpuSeconds':None,'workingSetBytes':None}])
        self.assertEqual(result,{'cpuSeconds':1,'maximumWorkingSetBytes':20,'resourceSamplesUnavailable':1})

    def test_no_window_approval_never_contacts_host(self):
        args=argparse.Namespace(approved_maintenance_window=False)
        with patch.object(closure.r,'Remote') as bridge:
            with self.assertRaisesRegex(RuntimeError,'approval'): closure.execute(args)
            bridge.assert_not_called()

    def test_saturation_uses_failures_delivery_and_latency_not_throttling(self):
        http={'http_reqs':{'count':600},'http_req_failed':{'rate':0},
              'dropped_iterations':{'count':0},'http_req_duration':{'p(95)':100}}
        self.assertFalse(closure.degraded(http,600))
        for field,key,value in [('http_req_failed','rate',.01),('dropped_iterations','count',6),
                                ('http_reqs','count',569),('http_req_duration','p(95)',1001)]:
            changed=json.loads(json.dumps(http));changed[field][key]=value
            with self.subTest(field=field): self.assertTrue(closure.degraded(changed,600))

    def test_mixed_users_start_together_and_passwords_are_not_retained(self):
        config=closure.mixed_configuration({'requestDistribution':{}},[{'username':'a','password':'secret'},
                                                                      {'username':'b','password':'secret'}])
        self.assertEqual(config['authStartSpacingSeconds'],0)
        self.assertEqual(config['publicStartSeconds'],0)
        self.assertEqual(config['iterationsPerUser'],5)
        self.assertNotIn('secret',json.dumps(config))

    def actor(self):
        directory=tempfile.TemporaryDirectory(prefix='vgp-performance-85-',dir='/tmp')
        self.addCleanup(directory.cleanup)
        return worker.Experiment(directory.name)

    def test_inference_and_reuse_use_identical_payload_and_calibrate_real_cache_misses(self):
        hashes=[]
        for scenario in ['serving-sync-reuse','serving-sync-inference']:
            actor=self.actor();actor.state.update(phase='active',referenceDate='2026-10-13',schemaChecksum='checksum',
                dependencies={'catalogue-localizer':{'imageId':'image'}})
            (actor.directory/'override.json').write_text(json.dumps({'services':{'application':{'environment':{}}}}))
            def command(*args,**kwargs): return 'provider' if args[:2]==('docker','run') else ''
            with patch.object(actor,'boundary'),patch.object(actor,'sql'),patch.object(actor,'recreate_experiment'), \
                 patch.object(actor,'seed_reuse_cache') as seed,patch.object(worker,'command',side_effect=command), \
                 patch.object(actor,'background_sync',return_value={'report':{'outcome':'SUCCEEDED'},'syncDurationSeconds':43}) as sync:
                result=actor.background_prepare(scenario)
            hashes.append(result['inputSha256'])
            self.assertEqual((result['games'],result['releases']),(64,128))
            self.assertEqual(sync.call_count,2)
            self.assertEqual(seed.call_count,1 if scenario.endswith('reuse') else 0)
        self.assertEqual(hashes[0],hashes[1])

    def test_observation_watchdog_detects_oom_readiness_and_missing_samples(self):
        actor=self.actor();actor.threads=[Mock()]
        sample={'wallSeconds':100,'readiness':{'status':'UP'},'cgroups':{name:{'memory.events':'oom 0\noom_kill 0'}
            for name in ['application','postgres','catalogue-localizer']}}
        actor.observation_before=sample;actor.samples=[json.loads(json.dumps(sample))]
        with patch.object(worker.time,'time',return_value=110):
            self.assertFalse(actor.observation_status()['abort'])
            actor.samples[0]['cgroups']['postgres']['memory.events']='oom 1\noom_kill 0'
            self.assertIn('postgres-oom',actor.observation_status()['reasons'])
            actor.samples[0]['readiness']['status']='DOWN'
            self.assertIn('readiness-down',actor.observation_status()['reasons'])
            actor.failures=[{'unavailable':True}]
            self.assertIn('observation-unavailable',actor.observation_status()['reasons'])

    def test_provider_sample_is_three_limit_one_reads_without_exported_credentials(self):
        actor=self.actor();actor.state.update(phase='inspected',privateBefore={})
        token=io.BytesIO(b'{"access_token":"private-token"}')
        replies=[io.BytesIO(b'[{"id":1}]') for _ in range(3)]
        for reply in replies: reply.status=200
        with patch.object(actor,'service',return_value={'Id':'owned','Config':{'Env':[]}}), \
             patch.object(worker,'command',side_effect=['private-client','private-secret']), \
             patch.object(actor,'private_counts',return_value={}),patch.object(worker.time,'sleep'), \
             patch.object(worker.urllib.request,'urlopen',side_effect=[token,*replies]) as calls:
            result=actor.provider_calibration()
        self.assertEqual(result['status'],'SUCCEEDED')
        self.assertEqual(len(result['samples']),3)
        self.assertEqual(calls.call_count,4)
        self.assertNotIn('private-',json.dumps(result))
        self.assertTrue(all(c.args[0].data==b'fields id; limit 1;' for c in calls.call_args_list[1:]))
        self.assertFalse(result['realSynchronizationDurationMeasured'])

    def test_provider_429_stops_without_retry(self):
        actor=self.actor();actor.state.update(phase='inspected',privateBefore={})
        with patch.object(actor,'service',return_value={'Id':'owned','Config':{'Env':[]}}), \
             patch.object(worker,'command',side_effect=['client','secret']),patch.object(worker.time,'sleep'), \
             patch.object(worker.urllib.request,'urlopen',side_effect=[io.BytesIO(b'{"access_token":"token"}'),
                 urllib.error.HTTPError('https://api.igdb.com/v4/games',429,'private detail',{},None)]) as calls:
            result=actor.provider_calibration()
        self.assertEqual((result['status'],result['httpStatus'],result['retries']),('BLOCKED',429,0))
        self.assertEqual(calls.call_count,2)
        self.assertNotIn('private detail',json.dumps(result))

    def test_provider_rejects_other_origin_before_reading_credentials(self):
        actor=self.actor();actor.state['phase']='inspected'
        with patch.object(actor,'service',return_value={'Id':'owned','Config':{'Env':['IGDB_API_BASE_URI=http://foreign/']}}), \
             patch.object(worker,'command') as command:
            with self.assertRaisesRegex(RuntimeError,'approved provider'): actor.provider_calibration()
            command.assert_not_called()

    def test_failed_background_drain_restores_private_application_before_refusing_cleanup(self):
        actor=self.actor();actor.state.update(phase='active',candidateId='owned',originalScheduling='true')
        actor.background_thread=Mock();actor.background_thread.is_alive.return_value=True
        container={'Id':'owned','Config':{'Env':['APPLICATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform',
            'CATALOGUE_SYNC_SCHEDULING_ENABLED=true']}}
        with patch.object(worker,'service',return_value=container),patch.object(actor,'compose') as compose:
            with self.assertRaisesRegex(RuntimeError,'Private-dev restored; background drain failed'):
                actor.restore()
        compose.assert_called_once_with(False)
        self.assertIsNone(actor.state['candidateId'])
        self.assertEqual(actor.state['phase'],'private-restored')

    def test_background_post_pins_validated_candidate_instead_of_a_later_replacement(self):
        actor=self.actor();actor.state.update(providerId='provider',providerName='fixture',referenceDate='2026-10-13',noInference=True)
        old={'Id':'candidate','Config':{'Env':['IGDB_API_BASE_URI=http://fixture:8093/v4/',
            'IGDB_TOKEN_URI=http://fixture:8093/oauth2/token','CATALOGUE_TRANSLATION_ENDPOINT=http://127.0.0.1:9/translate']}}
        with patch.object(actor,'boundary'),patch.object(actor,'service',side_effect=[old,{'Id':'private-restored'}]), \
             patch.object(actor,'localization',return_value={}),patch.object(actor,'actuator',return_value={}), \
             patch.object(actor,'logs',return_value=[]),patch.object(worker,'command',return_value='{}') as command:
            actor.background_sync()
        post=next(c.args for c in command.call_args_list if '--post-data' in c.args)
        self.assertEqual(post[2],'candidate')

    def test_safety_abort_requests_owned_generator_stop_and_still_captures_observation(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)/'stage';directory.mkdir();(directory/'generator-started.json').write_text('{}')
            bridge=Mock()
            def call(action,**kwargs):
                if action=='start_observation': return {'readiness':{'status':'UP'}}
                if action=='observation_status': return {'abort':True,'reasons':['postgres-oom']}
                if action=='stop_observation': return {'after':{},'applicationLog':{}}
                self.fail(action)
            bridge.call.side_effect=call
            future=Mock();future.done.side_effect=[False,True];future.result.return_value=None
            pool=Mock();pool.submit.return_value=future
            with patch.object(closure.concurrent.futures,'ThreadPoolExecutor') as executor, \
                 patch.object(closure.r,'native'),patch.object(closure.r,'report',return_value={}),patch.object(closure.time,'sleep'):
                executor.return_value.__enter__.return_value=pool
                result=closure.stage(bridge,directory,{'durationSeconds':60})
            self.assertTrue((directory/'generator-stop-requested.json').exists())
            self.assertTrue(result['safetyAbort']['abort'])
            self.assertIn('stop_observation',[c.args[0] for c in bridge.call.call_args_list])

if __name__=='__main__': unittest.main()
