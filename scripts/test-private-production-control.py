#!/usr/bin/env python3
"""Simulated host/storage regressions; real Docker proofs are a separate command."""
import contextlib
import errno
import fcntl
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

import install_private_production as install
import private_production_control as control
import private_dev_promotion as trust
import validate_private_production_truststore as stores

spec = importlib.util.spec_from_file_location('bootstrap_fakes', Path(__file__).with_name('test-private-production-installation.py'))
fakes = importlib.util.module_from_spec(spec); spec.loader.exec_module(fakes)


class HostClose(unittest.TestCase):
    """Exercise Host.close and stop_owned, simulating only gate/daemon/storage I/O."""
    def setUp(self):
        control.CANCEL.clear(); control.CAUSE = None; control.UNCERTAIN_COMMAND = False
        self.host = install.Host('c'*64, 'a'*64, 'b'*40)
        self.host.run_id = '11111111-1111-4111-8111-111111111111'
        self.host.config = {'services': {'application': {}}}
        self.lease = Mock()
        self.closed = Mock()
        self.host.recovery = SimpleNamespace(gate_volume='owned-gate', check_lease=self.lease, closed=self.closed)
        self.active = True
        self.commands = []
        self.gate_error = self.stop_error = None
        self.scope = patch.object(install, 'run', side_effect=self.runner)
        self.scope.start(); self.addCleanup(self.scope.stop)

    def runner(self, args, **kwargs):
        self.commands.append(args)
        if args[:2] == ['docker', 'run']:
            self.assertIn(control.FENCE, args)
            self.assertEqual(args[-2:], [self.host.run_id, self.host.configuration])
            if self.gate_error: raise self.gate_error
            return b''
        if args[:2] == ['docker', 'ps']:
            return ('a'*64).encode()
        if args[:2] == ['docker', 'inspect']:
            return json.dumps([{'Id': 'a'*64, 'State': {'Running': self.active}, 'Config': {'Labels': {
                control.RUN_LABEL: self.host.run_id, control.CONFIG_LABEL: self.host.configuration,
                'com.docker.compose.project': install.prepare.PROJECT, 'com.docker.compose.service': 'application'}}}]).encode()
        if args[:2] == ['docker', 'stop']:
            self.assertEqual(args, ['docker', 'stop', '--time', '5', 'a'*64])
            self.lease.assert_called()
            if self.stop_error: raise self.stop_error
            self.active = False
            return b''
        self.fail('Unexpected command in scoped shutdown')

    def test_journal_enospc_or_eio_still_stops_owned_but_preserves_error(self):
        for number in (errno.ENOSPC, errno.EIO):
            with self.subTest(errno=number):
                self.active = True; self.commands.clear()
                failure = OSError(number, 'synthetic journal failure')
                self.closed.side_effect = failure
                with self.assertRaises(OSError) as caught: self.host.close('test')
                self.assertIs(caught.exception, failure)
                self.assertFalse(self.active)
                self.assertEqual(self.commands[0][:2], ['docker', 'run'])
                self.assertIn(['docker', 'inspect', 'a'*64], self.commands)

    def test_both_persistence_and_stop_failures_are_reported(self):
        journal = OSError(errno.ENOSPC, 'synthetic journal failure')
        stop = trust.Refused('synthetic daemon stop failure')
        self.closed.side_effect = journal; self.stop_error = stop
        with self.assertRaises(ExceptionGroup) as caught: self.host.close('test')
        self.assertEqual(caught.exception.exceptions, (journal, stop))
        self.assertTrue(self.active)

    def test_stop_failure_alone_never_returns_confirmed(self):
        self.stop_error = trust.Refused('synthetic daemon stop failure')
        with self.assertRaises(trust.Refused) as caught: self.host.close('test')
        self.assertIs(caught.exception, self.stop_error)
        self.assertTrue(self.active)

    def test_unverified_gate_owner_prevents_journal_and_stop(self):
        self.gate_error = trust.Refused('different gate owner')
        with self.assertRaisesRegex(trust.Refused, 'different gate owner'): self.host.close('test')
        self.closed.assert_not_called()
        self.assertEqual(len(self.commands), 1)
        self.assertTrue(self.active)

    def test_no_lease_and_refused_reacquisition_never_touches_resources(self):
        self.lease.side_effect = trust.Refused('lease absent')
        @contextlib.contextmanager
        def occupied(*args, **kwargs):
            raise trust.Refused('legitimate competing lease')
            yield
        with patch.object(install, 'bootstrap_lease', occupied), self.assertRaisesRegex(trust.Refused, 'competing lease'):
            self.host.close('test')
        self.assertEqual(self.commands, []); self.closed.assert_not_called()

    def test_positive_close_requires_observed_physical_stop(self):
        result = self.host.close('test')
        self.assertEqual(result['status'], 'confirmed')
        self.assertFalse(self.active)
        self.closed.assert_called_once_with('first-install-test')
        self.assertGreaterEqual(sum(args[:2] == ['docker', 'inspect'] for args in self.commands), 3)


class Regressions(unittest.TestCase):
    def setUp(self):
        control.CANCEL.clear(); control.CAUSE = None; control.UNCERTAIN_COMMAND = False
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.scope = contextlib.ExitStack()
        self.scope.enter_context(patch.object(install, 'ADMIN', self.root))
        self.scope.enter_context(patch.object(install, 'JOURNAL', self.root / 'first-install'))
        self.addCleanup(self.tmp.cleanup); self.addCleanup(self.scope.close)

    def test_journal_directory_creation_failure_keeps_durable_intent_and_sidecar(self):
        real = Path.mkdir
        def mkdir(path, *args, **kw):
            if path == install.JOURNAL: raise OSError('simulated storage failure')
            return real(path, *args, **kw)
        host = fakes.FakeHost()
        with patch.object(Path, 'mkdir', mkdir), self.assertRaises(OSError): install.first_install(host)
        self.assertTrue(install.intent_file().exists()); self.assertFalse(install.JOURNAL.exists())
        self.assertTrue(list(self.root.glob('first-install-failure-*.json')))
        self.assertNotIn('pull', host.calls)
        # An existing claim is not silently overwritten even if the directory is missing.
        with patch.object(install, 'secure'), self.assertRaises(FileExistsError): install.first_install(fakes.FakeHost())

    def test_first_journal_write_failure_is_not_outside_failure_handling(self):
        original = install.durable; failed = False
        def write(path, value):
            nonlocal failed
            if path.name == 'installation.json' and not failed:
                failed = True; raise OSError('full disk')
            original(path, value)
        host = fakes.FakeHost()
        with patch.object(install, 'durable', side_effect=write), self.assertRaises(OSError): install.first_install(host)
        self.assertTrue(install.intent_file().exists())
        self.assertEqual(json.loads((install.JOURNAL/'installation.json').read_text())['outcome'], 'failure')
        self.assertNotIn('pull', host.calls)

    def test_prepared_is_persisted_before_lease_ack_and_success_only_after(self):
        host = fakes.FakeHost()
        @contextlib.contextmanager
        def lease():
            host.locked = True
            try:
                yield
                self.assertEqual(json.loads((install.JOURNAL/'installation.json').read_text())['outcome'], 'prepared')
            finally: host.locked = False
        host.lock = lease
        result = install.first_install(host)
        self.assertEqual(result['outcome'], 'success')
        self.assertEqual(result['leaseCompletion'], 'acknowledged-and-exited')

    def test_lost_completion_ack_never_leaves_success(self):
        host = fakes.FakeHost('lease-finalize')
        with self.assertRaises(trust.Refused): install.first_install(host)
        self.assertEqual(json.loads((install.JOURNAL/'installation.json').read_text())['outcome'], 'failure')
        self.assertEqual(len([x for x in host.calls if x.startswith('close:')]), 1)

    def test_missing_and_contradictory_journals_are_detected_not_reset(self):
        run_id = '11111111-1111-4111-8111-111111111111'
        install.intent_file().write_text(json.dumps({'runId':run_id,'configurationSha256':'c'*64}))
        (self.root/'first-install.lock').touch()
        with patch.object(install,'secure'), patch.object(install.integration,'verify_local_docker'):
            value=install.inspect_interruption()
            self.assertEqual(value['status'],'interrupted-or-failed')
            install.JOURNAL.mkdir();(install.JOURNAL/'installation.json').write_text(json.dumps({'runId':'different'}))
            value=install.inspect_interruption()
            self.assertEqual(value['status'],'inconclusive')
            self.assertTrue(install.intent_file().exists())

    def test_inspection_never_calls_active_lock_holder_interrupted(self):
        run_id='11111111-1111-4111-8111-111111111111'
        install.intent_file().write_text(json.dumps({'runId':run_id,'configurationSha256':'c'*64}))
        with (self.root/'first-install.lock').open('w') as stream:
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with patch.object(install,'secure'),patch.object(install.integration,'verify_local_docker'):
                self.assertEqual(install.inspect_interruption()['status'],'exclusive-operation-in-progress')

    def test_inspection_refuses_success_without_ack_and_detects_external_pending_writes(self):
        run_id='11111111-1111-4111-8111-111111111111'
        install.intent_file().write_text(json.dumps({'runId':run_id,'configurationSha256':'c'*64}))
        (self.root/'first-install.lock').touch(); install.JOURNAL.mkdir()
        path=install.JOURNAL/'installation.json'
        state={'runId':run_id,'outcome':'success','phase':'complete'}
        path.write_text(json.dumps(state))
        with patch.object(install,'secure'),patch.object(install.integration,'verify_local_docker'):
            self.assertEqual(install.inspect_interruption()['status'],'inconclusive')
            state['leaseCompletion']='acknowledged-and-exited';path.write_text(json.dumps(state))
            (self.root/'first-install-failure-test.json.partial.pending').touch()
            value=install.inspect_interruption()
            self.assertEqual(value['status'],'interrupted-or-failed')
            self.assertEqual(value['pendingWrites'],1)

    def test_preflight_failure_records_protected_inconclusive_evidence(self):
        evidence=self.root/'evidence';evidence.mkdir(mode=0o700)
        host=install.Host('c'*64,'a'*64,'b'*40,evidence_dir=evidence)
        def unknown(**_):host.check='docker-effective-policy';raise trust.Refused('do not export raw detail')
        with patch.object(install,'secure'),patch.object(host,'_preflight',side_effect=unknown),self.assertRaises(trust.Refused):host.preflight()
        path=next(evidence.iterdir());value=json.loads(path.read_text())
        self.assertEqual(path.stat().st_mode&0o777,0o600)
        self.assertEqual(value['outcome'],'inconclusive')
        self.assertEqual(value['lastCheck'],'docker-effective-policy')
        self.assertNotIn('raw detail',path.read_text())

    def test_unknown_versions_and_effective_options_refuse(self):
        self.assertEqual(control.versions('29.9.2','5.6.1')['qualification'],'target-qualification-pending')
        for pair in [('29.9.3','5.6.1'),('30.0.0','5.6.1'),('29.9.2','5.7.0')]:
            with self.subTest(pair=pair),self.assertRaises(trust.Refused):control.versions(*pair)
        for config in ({'unknown-security-option':True}, {'firewall-backend':'nftables'},
                       {'live-restore':True},{'iptables':False},
                       {'storage-driver':'unreviewed'},
                       {'default-network-opts':{'bridge':{'com.docker.network.bridge.trusted_host_interfaces':'eth0'}}}):
            with self.subTest(config=config),self.assertRaises(trust.Refused):control.daemon_policy(['/usr/bin/dockerd'],config,{})
        with self.assertRaises(trust.Refused):control.daemon_policy(['/usr/bin/dockerd','--iptables=true'],{'iptables':True},{})

    def test_effective_ipam_preserves_fixed_proxy_topology_and_refuses_unknowns(self):
        actual={'Driver':'default','Options':{},'Config':[{'Subnet':'10.203.1.0/28','Gateway':'10.203.1.1','IPRange':'10.203.1.8/29'}]}
        intended={'config':[{'subnet':'10.203.1.0/28','gateway':'10.203.1.1','ip_range':'10.203.1.8/29'}]}
        control.validate_ipam(actual,intended)
        for changed in ({**actual,'Options':{'unknown':'value'}},
                        {**actual,'Config':[{**actual['Config'][0],'Gateway':'10.203.1.2'}]},
                        {**actual,'Config':[{**actual['Config'][0],'AuxiliaryAddresses':{}}]}):
            with self.subTest(changed=changed),self.assertRaises(trust.Refused):control.validate_ipam(changed,intended)

    def test_no_stop_when_lease_or_container_identity_is_unknown(self):
        runner=Mock()
        def no_lease():raise trust.Refused('other actor')
        with self.assertRaises(trust.Refused):control.stop_owned('run','c'*64,'project',{'app'},no_lease,runner=runner)
        runner.assert_not_called()
        item={'Id':'a'*64,'Config':{'Labels':{control.RUN_LABEL:'run',control.CONFIG_LABEL:'wrong',
              'com.docker.compose.project':'project','com.docker.compose.service':'app'}},'State':{'Running':True}}
        def metadata(args,**_):
            self.assertNotIn('stop',args);self.assertNotIn('kill',args)
            return ('a'*64).encode() if args[1]=='ps' else json.dumps([item]).encode()
        with self.assertRaises(trust.Refused):control.stop_owned('run','c'*64,'project',{'app'},lambda:None,runner=metadata)

    def test_interrupted_daemon_request_is_not_reported_fully_clean(self):
        control.UNCERTAIN_COMMAND=True
        value=control.stop_owned('run','c'*64,'project',{'app'},lambda:None,runner=lambda *a,**k:b'')
        self.assertEqual(value['status'],'inconclusive')

    def test_expired_cleanup_budget_does_not_start_new_commands(self):
        with control.cleanup_window(timeout=-1),self.assertRaises(trust.Refused):
            control.run(['this-command-must-not-run'])

    def test_java_receipt_cannot_be_a_flag_or_different_store(self):
        with self.assertRaises(trust.Refused):stores.verify_receipt({'outcome':'success'},self.root/'missing',self.root/'ca',stores.IMAGE)


if __name__=='__main__':unittest.main()
