"""Boundary and clock controls; never calls Docker, SSH, Keycloak or Windows."""
import argparse
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
