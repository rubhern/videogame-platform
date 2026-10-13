"""Boundary and clock controls; never calls Docker, SSH, Keycloak or Windows."""
import argparse
import json
import io
import contextlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import remote
import remote_worker as worker


class Safety(unittest.TestCase):
    def actor(self):
        tmp=tempfile.TemporaryDirectory(prefix='vgp-performance-85-',dir='/tmp')
        self.addCleanup(tmp.cleanup)
        return worker.Experiment(tmp.name)

    def test_no_shared_mutation_without_owner_approval(self):
        args=argparse.Namespace(command='smoke',approved_maintenance_window=False)
        with patch.object(remote,'Remote') as bridge:
            with self.assertRaisesRegex(RuntimeError,'explicit owner approval'):
                remote.execute(args)
            bridge.assert_not_called()

    def test_foreign_recovery_path_rejected_before_host_command(self):
        with patch.object(worker,'command') as command:
            with self.assertRaisesRegex(RuntimeError,'Invalid owned'):
                worker.Experiment('/tmp/some-other-project')
            command.assert_not_called()

    def test_stale_database_oid_refuses_cleanup_before_recreation(self):
        actor=self.actor()
        actor.state.update(phase='active',databaseOid=42,candidateId='owned')
        with patch.object(actor,'sql',return_value='43'),patch.object(actor,'compose') as compose,patch.object(worker,'admin') as admin:
            with self.assertRaisesRegex(RuntimeError,'another run'):
                actor.restore()
            compose.assert_not_called(); admin.assert_not_called()

    def test_unexpected_principal_data_is_retained_and_private_dev_restored(self):
        actor=self.actor()
        actor.state.update(phase='active',databaseOid=42,candidateId='owned',privateBefore={'counts':1},originalScheduling='true')
        container={'Id':'owned','Config':{'Env':['APPLICATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform','CATALOGUE_SYNC_SCHEDULING_ENABLED=true']}}
        def sql(query,*args):
            return '42' if 'pg_database' in query else '1'
        with patch.object(actor,'sql',side_effect=sql) as calls,patch.object(actor,'compose') as compose, \
             patch.object(worker,'service',return_value=container),patch.object(actor,'private_counts',return_value={'counts':1}), \
             patch.object(actor,'actuator',return_value={'status':'UP'}):
            with self.assertRaisesRegex(RuntimeError,'retained'):
                actor.restore()
            compose.assert_called_once_with(False)
            self.assertFalse(any('DROP DATABASE' in c.args[0] for c in calls.call_args_list))
            self.assertTrue(actor.state['restoration']['databaseRetainedForReview'])

    def test_partial_rollback_restores_application_before_admin_failure(self):
        actor=self.actor()
        actor.state.update(phase='active',databaseOid=42,candidateId='owned',originalScheduling='true',users=[{'id':'synthetic'}])
        container={'Id':'owned','Config':{'Env':['APPLICATION_DB_URL=jdbc:postgresql://postgres:5432/videogame_platform','CATALOGUE_SYNC_SCHEDULING_ENABLED=true']}}
        with patch.object(actor,'sql',side_effect=['42','0']),patch.object(actor,'compose') as compose, \
             patch.object(worker,'service',return_value=container) as services,patch.object(worker,'admin',side_effect=RuntimeError('admin unavailable')):
            with self.assertRaisesRegex(RuntimeError,'admin unavailable'):
                actor.restore()
            compose.assert_called_once_with(False)
            self.assertIsNone(actor.state['candidateId'])
            self.assertEqual(actor.state['phase'],'private-restored')
            self.assertEqual(services.call_args_list[0].kwargs,{'include_stopped':True})

    def test_backsteps_are_retained_and_clock_sources_are_independent(self):
        clock=remote.paired_audit([{'wallSeconds':2,'monotonicSeconds':1},{'wallSeconds':1,'monotonicSeconds':2}])
        self.assertEqual(len(clock['backsteps']),1)
        self.assertEqual(len(clock['discontinuities']),1)
        self.assertEqual(clock['maxAbsoluteDifferenceSeconds'],2)

    def test_created_identity_is_recorded_before_followup_and_email_is_lowercase(self):
        actor=self.actor(); actor.state['phase']='prepared'
        identity='11111111-1111-4111-8111-111111111111'
        client=Mock()
        client.find_users.side_effect=[[],[]]
        client._request.return_value=(b'',{'Location':'http://localhost/admin/realms/videogame-platform/users/'+identity})
        with patch.object(worker,'admin',return_value=client):
            with self.assertRaisesRegex(RuntimeError,'ambiguous'):
                actor.users([{},{}])
        record=actor.state['users'][0]
        self.assertEqual(record['id'],identity)
        self.assertEqual(record['email'],record['email'].lower())
        self.assertTrue(actor.receipt.exists())

    def test_repeated_recovery_does_not_hide_retained_foreign_data(self):
        actor=self.actor()
        actor.state.update(phase='restored',restoration={'databaseRetainedForReview':True,'privateDataUnchanged':True})
        with self.assertRaisesRegex(RuntimeError,'retained'):
            actor.restore()


    def test_clock_is_dispatchable_through_the_host_rpc(self):
        actor=self.actor()
        stream=io.StringIO('{"action":"clock"}\n')
        output=io.StringIO()
        with patch.object(worker,'Experiment',return_value=actor),patch.object(worker.sys,'stdin',stream), \
             patch.object(worker.select,'select',side_effect=[([stream],[],[]),([],[],[])]), \
             patch.object(worker.fcntl,'flock'),patch('builtins.open',return_value=io.StringIO()), \
             contextlib.redirect_stdout(output):
            worker.serve(actor.directory)
        result=json.loads(output.getvalue())
        self.assertTrue(result['ok'])
        self.assertGreater(result['value']['wallSeconds'],0)
        self.assertGreater(result['value']['monotonicSeconds'],0)

    def test_failed_background_command_retains_start_end_and_durable_completion(self):
        actor=self.actor()
        actor.state.update(providerId='provider',providerName='fixture',referenceDate='2026-10-13',noInference=True)
        env=['IGDB_API_BASE_URI=http://fixture:8093/v4/','IGDB_TOKEN_URI=http://fixture:8093/oauth2/token',
             'CATALOGUE_TRANSLATION_ENDPOINT=http://127.0.0.1:9/translate']
        durable={'startedAt':'2026-10-13T10:00:00Z','completedAt':'2026-10-13T10:00:40Z','outcome':'SUCCEEDED'}
        with patch.object(actor,'boundary'),patch.object(worker,'service',return_value={'Id':'app','Config':{'Env':env}}), \
             patch.object(actor,'localization',return_value={'translated':0,'reused':64,'failed':0}), \
             patch.object(worker,'command',side_effect=RuntimeError('timeout')),patch.object(actor,'actuator',return_value=durable):
            result=actor.background_sync()
        self.assertEqual(result['status'],'command-failed')
        self.assertEqual(result['syncDurationSeconds'],40)
        self.assertGreaterEqual(result['commandDurationSeconds'],0)
        self.assertEqual(result,json.loads((actor.directory/'background-last.json').read_text()))

    def test_reuse_refuses_real_token_or_translation_endpoint_before_dispatch(self):
        actor=self.actor()
        actor.state.update(providerId='provider',providerName='fixture',referenceDate='2026-10-13',noInference=True)
        env=['IGDB_API_BASE_URI=http://fixture:8093/v4/','IGDB_TOKEN_URI=http://fixture:8093/oauth2/token',
             'CATALOGUE_TRANSLATION_ENDPOINT=http://model/translate']
        with patch.object(actor,'boundary'),patch.object(worker,'service',return_value={'Id':'app','Config':{'Env':env}}), \
             patch.object(worker,'command') as command:
            with self.assertRaisesRegex(RuntimeError,'blocked inference'):
                actor.background_sync()
            command.assert_not_called()

    def test_observation_failure_does_not_block_restoration(self):
        actor=self.actor()
        actor.state.update(phase='active',privateBefore={'counts':1})
        actor.threads=[Mock()]
        with patch.object(actor,'stop_observation',side_effect=RuntimeError('sampling failed')), \
             patch.object(actor,'private_counts',return_value={'counts':1}),patch.object(actor,'actuator',return_value={'status':'UP'}):
            receipt=actor.restore()
        self.assertTrue(receipt['privateDataUnchanged'])
        self.assertEqual(actor.state['observationStopFailure'],'RuntimeError')

    def test_reuse_preparation_preserves_resource_bounds_and_blocks_inference(self):
        actor=self.actor()
        actor.state.update(phase='active',referenceDate='2026-10-13',schemaChecksum='checksum',
                           dependencies={'catalogue-localizer':{'imageId':'existing-image'}})
        (actor.directory/'override.json').write_text(json.dumps({'services':{'application':{'environment':{}}}}))
        def command(*args, **kwargs):
            return 'owned-provider' if args[:2]==('docker','run') else ''
        completed={'report':{'outcome':'SUCCEEDED'},'syncDurationSeconds':43}
        with patch.object(actor,'boundary'),patch.object(worker,'command',side_effect=command) as commands, \
             patch.object(actor,'sql') as sql,patch.object(actor,'recreate_experiment'), \
             patch.object(actor,'background_sync',return_value=completed) as sync:
            prepared=actor.background_prepare('serving-sync-reuse')
        self.assertEqual((prepared['games'],prepared['releases']),(64,128))
        self.assertEqual(sync.call_count,2)
        env=json.loads((actor.directory/'override.json').read_text())['services']['application']['environment']
        self.assertEqual(env['CATALOGUE_TRANSLATION_ENDPOINT'],'http://127.0.0.1:9/translate')
        self.assertTrue(env['IGDB_API_BASE_URI'].endswith(':8093/v4/'))
        actor_args=next(c.args for c in commands.call_args_list if c.args[:2]==('docker','run'))
        self.assertEqual(actor_args[actor_args.index('--memory')+1],'128m')
        self.assertEqual(actor_args[actor_args.index('--cpus')+1],'.25')
        self.assertNotIn('--publish',actor_args)
        resets=[c for c in sql.call_args_list if 'DELETE FROM catalogue.game_summary_translation' in c.args[0]]
        self.assertEqual(len(resets),2)
        self.assertTrue(all(c.args[1]==worker.DATABASE for c in sql.call_args_list))

    def test_reuse_seed_checks_disposable_boundary_before_any_write(self):
        from provider_fixture import build_reuse_input
        actor=self.actor()
        fixture=build_reuse_input(remote.b.ROOT,'2026-10-13')
        with patch.object(actor,'boundary',side_effect=RuntimeError('foreign DB')),patch.object(actor,'sql') as sql:
            with self.assertRaisesRegex(RuntimeError,'foreign DB'):
                actor.seed_reuse_cache(fixture)
            sql.assert_not_called()

    def test_normal_profile_excludes_only_adversarial_cases_with_exact_distribution(self):
        adversarial=remote.b.growth_requests('adversarial')
        normal=remote.b.growth_requests('normal')
        self.assertEqual(adversarial,remote.b.growth_requests())
        self.assertEqual(len(adversarial),20)
        self.assertEqual([r for r in adversarial if r['name'] not in remote.b.ADVERSARIAL_CASES],normal)
        distribution=remote.b.request_distribution(normal,300)
        self.assertEqual(sum(distribution.values()),300)
        self.assertEqual(sorted(set(distribution.values())),[17,18])
        with self.assertRaises(ValueError):
            remote.b.growth_requests('stress')

    def test_vus_are_explicit_and_bounded(self):
        self.assertEqual(remote.default_vus(5),4)
        remote.validate_vus(5,None)
        remote.validate_vus(5,50)
        for rate,vus in [(5,0),(5,61),(1,13)]:
            with self.assertRaisesRegex(RuntimeError,'VUs'):
                remote.validate_vus(rate,vus)

    def test_settle_refuses_to_measure_when_autovacuum_never_quiets(self):
        actor=self.actor(); actor.state['phase']='active'
        busy={'at':'t','autovacuumWorkers':1,'autovacuumWorkersOtherDatabases':0,'vacuumProgress':1,'tables':[]}
        with patch.object(actor,'boundary'),patch.object(actor,'sql') as sql,patch.object(actor,'maintenance',return_value=busy), \
             patch.object(worker.time,'sleep'):
            with self.assertRaisesRegex(RuntimeError,'did not settle'):
                actor.settle(quiet_seconds=1,deadline_seconds=0)
            self.assertIn('VACUUM (ANALYZE)',sql.call_args.args[0])
            self.assertEqual(sql.call_args.args[1],worker.DATABASE)

    def test_application_log_keeps_allowlisted_warn_error_fields_only(self):
        lines=['{"@timestamp":"t","log":{"level":"ERROR","logger":"com.videogameplatform.api.delivery.ApiExceptionHandler"},'
               '"message":"API request failed: code=CATALOGUE_READ_FAILED","correlationId":"run-7","error":{"sql_state":"57014"}}',
               '{"@timestamp":"t","log":{"level":"WARN","logger":"other"},"message":"private detail","user":"secret"}',
               '{"@timestamp":"t","log":{"level":"INFO","logger":"other"},"message":"x"}','not json']
        result=worker.filter_application_log(lines)
        self.assertEqual(result['levels'],{'ERROR':1,'WARN':1,'INFO':1})
        self.assertEqual(result['unparsedLines'],1)
        self.assertEqual(result['events'][0]['error.sql_state'],'57014')
        self.assertEqual(result['events'][0]['correlationId'],'run-7')
        self.assertNotIn('message',result['events'][1])
        self.assertNotIn('secret',str(result))


if __name__=='__main__': unittest.main()
