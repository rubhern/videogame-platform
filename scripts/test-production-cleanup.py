#!/usr/bin/env python3
"""Production privacy transport refusals; complete ledger races live in the privacy gate."""
import argparse
import json
import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader('production_cleanup_test', str(ROOT / 'deploy/public-production/bin/recovery-personal-data'))
spec = importlib.util.spec_from_loader(loader.name, loader)
adapter = importlib.util.module_from_spec(spec)
loader.exec_module(adapter)
SUBJECT = '11111111-1111-4111-8111-111111111111'


class Cleanup(unittest.TestCase):
    def arguments(self, command='revoke', **kwargs):
        return argparse.Namespace(identity_cleanup=True, command=command, keycloak_user_id=SUBJECT,
            user_id=adapter.pd.derive_user_id(adapter.recovery.ISSUER, SUBJECT), cutoff_ms=1, max=100,
            game_ids=None, after_game_id=SUBJECT, application_must_be_stopped=False, **kwargs)

    def test_mutation_without_fence_is_refused(self):
        core, target = Mock(), Mock()
        core.gate.return_value = b'none'
        with self.assertRaisesRegex(adapter.recovery.Refused, 'identity fence'):
            adapter.dispatch(self.arguments(), core, target)
        target.assert_not_called()
        self.assertEqual(target.method_calls, [])

    def test_fence_is_rechecked_under_the_callers_lease_before_mutation(self):
        core, target, maintenance = Mock(), Mock(issuer=adapter.recovery.ISSUER), Mock()
        core.gate.return_value = SUBJECT.encode()
        with patch.object(adapter, 'load', return_value=maintenance), patch.dict(adapter.pd.STEPS, {'revoke': Mock(return_value={'step': 'revoke'})}):
            result = adapter.dispatch(self.arguments(), core, target)
            maintenance.Maintenance.assert_called_once_with(core)
            maintenance.Maintenance.return_value.check.assert_called_once_with(SUBJECT)
            adapter.pd.STEPS['revoke'].assert_called_once_with(target, SUBJECT, self.arguments().user_id, False)
            self.assertEqual(result, {'step': 'revoke'})

    def test_unproved_drain_prevents_mutation(self):
        core, maintenance = Mock(), Mock()
        core.gate.return_value = SUBJECT.encode()
        maintenance.Maintenance.return_value.check.side_effect = adapter.recovery.Refused('unproved drain')
        with patch.object(adapter, 'load', return_value=maintenance), patch.dict(adapter.pd.STEPS, {'revoke': Mock()}) as _:
            with self.assertRaises(adapter.recovery.Refused):
                adapter.dispatch(self.arguments(), core, Mock())
            adapter.pd.STEPS['revoke'].assert_not_called()

    def test_discovery_uses_existing_bounded_server_filter(self):
        target = Mock()
        target.issuer = adapter.recovery.ISSUER
        target.keycloak.return_value = []
        result = adapter.dispatch(self.arguments('cleanup-candidates'), Mock(), target)
        self.assertEqual(result['candidates'], [])
        target.keycloak.assert_called_once_with('GET', '/users?emailVerified=false&createdBefore=1&first=0&max=100&briefRepresentation=true')
        for maximum in (0, 101):
            args = self.arguments('cleanup-candidates'); args.max = maximum
            with self.assertRaises(adapter.pd.ProvisioningError): adapter.dispatch(args, Mock(), target)

    def test_restore_mode_keeps_stopped_application_guard(self):
        args = self.arguments(); args.identity_cleanup = False
        with patch.dict(adapter.pd.STEPS, {'revoke': Mock()}):
            adapter.dispatch(args, Mock(), Mock(issuer=adapter.recovery.ISSUER))
            self.assertTrue(adapter.pd.STEPS['revoke'].call_args.args[-1])

    def test_live_mode_refuses_global_logout(self):
        with self.assertRaises(adapter.recovery.Refused):
            adapter.dispatch(self.arguments('recovery-logout-all'), Mock(), Mock())

    def test_live_runtime_refuses_closed_gate_before_authentication(self):
        core = Mock(); core.gate.return_value = b'closed'
        with tempfile.TemporaryDirectory() as directory:
            password = Path(directory) / 'password'; password.write_text('synthetic'); password.chmod(0o600)
            with patch.object(adapter.pd.provisioning, 'KeycloakAdmin') as admin:
                with self.assertRaisesRegex(adapter.recovery.Refused, 'open recovery'):
                    adapter.runtime(None, 'synthetic', Path(directory), 'synthetic', password, core=core, identity_cleanup=True)
                admin.assert_not_called()

    def test_restore_cli_preserves_the_parent_recovery_lease(self):
        core = Mock()
        arguments = ['recovery-personal-data','--config','/protected/compose.json','--project','vgp-production-test-unit',
                     '--state','/protected/journal','--admin-username','synthetic',
                     '--admin-password-file','/protected/password','identify','--keycloak-user-id',SUBJECT]
        with patch.object(sys,'argv',arguments), patch.object(adapter.recovery,'Runtime',return_value=core), \
             patch.object(adapter,'runtime'), patch.object(adapter,'dispatch',return_value={}), \
             patch('builtins.print'):
            adapter.main()
        core.lock.assert_not_called()
        core.check_lease.assert_called_once()

    def test_missing_application_allows_restore_reads_but_never_live_cleanup_or_mutation(self):
        for live in (False,True):
            core = Mock(); core.gate.return_value = b'open'
            config = json.dumps({'networks':{'identity-data':{'name':'synthetic'}}}).encode()
            core.compose.side_effect = [b'',config,b'pg',b'']
            with tempfile.TemporaryDirectory() as directory:
                password = Path(directory)/'password'; password.write_text('synthetic'); password.chmod(0o600)
                with patch.object(adapter.pd.provisioning,'KeycloakAdmin'):
                    if live:
                        with self.assertRaises(adapter.recovery.Refused):
                            adapter.runtime(None,'synthetic',Path(directory),'synthetic',password,core=core,identity_cleanup=True)
                    else:
                        target = adapter.runtime(None,'synthetic',Path(directory),'synthetic',password,core=core)
                        with self.assertRaises(adapter.pd.ProvisioningError):
                            adapter.pd.revoke(target,SUBJECT,self.arguments().user_id,True)

    def test_live_cli_serializes_every_transport_call(self):
        core = Mock()
        core.lock.return_value.__enter__ = Mock()
        core.lock.return_value.__exit__ = Mock(return_value=False)
        arguments = ['recovery-personal-data','--config','/protected/compose.json','--project','vgp-production-test-unit',
                     '--state','/protected/journal','--admin-username','synthetic',
                     '--admin-password-file','/protected/password','--identity-cleanup','cleanup-candidates','--cutoff-ms','1']
        with patch.object(sys,'argv',arguments), patch.object(adapter.recovery,'Runtime',return_value=core), \
             patch.object(adapter,'runtime'), patch.object(adapter,'dispatch',return_value={}), \
             patch('builtins.print'):
            adapter.main()
        core.lock.assert_called_once_with(maintenance=True)
        core.check_lease.assert_called_once()


if __name__ == '__main__':
    unittest.main()
