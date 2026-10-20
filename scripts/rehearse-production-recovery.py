#!/usr/bin/env python3
"""Synthetic complete host-loss recovery using retained trusted-main publications."""
import argparse
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shlex
import signal
import shutil
import sys
import subprocess
import tempfile
import time
import traceback
import urllib.request
import urllib.error
import urllib.parse

import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    loader.exec_module(m)
    return m


core = load('production_recovery_exercise', ROOT / 'deploy/public-production/bin/recovery')
adapter = load('production_recovery_adapter', ROOT / 'deploy/public-production/bin/recovery-personal-data')
SUBJECT = '11111111-1111-4111-8111-111111111111'


class RecoveryExercise(integration.Exercise):
    def cleanup(self):
        # Recovery deliberately stops processes and fences DB logins; the integration
        # workload's unexpected-exit classifier is not applicable to those planned stops.
        self.stop.set()
        if self.thread:
            self.thread.join(timeout=20)
        helpers = integration.command(['docker', 'ps', '-aq', '--filter', f'label=vgp.production-test={self.project}']).stdout.split()
        if helpers:
            integration.command(['docker', 'rm', '-fv', *helpers])
        if self.file.exists():
            self.compose('down', '--volumes', '--remove-orphans', '--timeout', '15')
        for command in [['ps', '-aq'], ['volume', 'ls', '-q'], ['network', 'ls', '-q']]:
            core.require(not integration.command(['docker', *command, '--filter', f'label=com.docker.compose.project={self.project}']).stdout.strip(), 'Disposable recovery resources remain.')
        if self.directory.exists():
            integration.command(['docker', 'run', '--rm', '--network', 'none', '-v', f'{self.directory}:/scratch',
                adapter.CLIENT, 'chown', '-R', f'{os.getuid()}:{os.getgid()}', '/scratch'])
        self.receipt['teardown'] = 'passed'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    a = parser.parse_args()
    os.umask(0o077)
    a.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    started = time.monotonic()
    receipt = {'kind': 'recovery-rehearsal', 'outcome': 'failure', 'productionReady': False,
               'scope': 'isolated-local-synthetic-only', 'deletionReplay': False, 'readiness': False}
    exercises = []
    temporary = None
    keyrings = []
    def interrupted(*_):
        raise core.Refused('Interrupted.')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        token = integration.command(['gh', 'auth', 'token']).stdout.strip()
        selected = integration.resolve(integration.trust.GitHub(token), a.revision)
        for image in selected['images'].values():
            integration.command(['docker', 'pull', image])
        info = json.loads(integration.command(['docker', 'image', 'inspect', selected['images']['application']]).stdout)[0]
        selected['applicationVersion'] = info['Config']['Labels']['org.opencontainers.image.version']
        receipt['provenance'] = selected
        temporary = tempfile.TemporaryDirectory(prefix='vgp-recovery-exercise-')
        temp = temporary.name
        work = Path(temp)
        # Owner workstation: private decryption key and reconstruction inputs never mounted
        # into either source or replacement runtime. Source receives only the public key.
        owner = work / 'owner'; owner.mkdir(mode=0o700)
        independent = work / 'independent'; independent.mkdir(mode=0o700)
        private_key = owner / 'keyring'; private_key.mkdir(mode=0o700)
        public_key = work / 'runtime-public-key'; public_key.mkdir(mode=0o700)
        keyrings = [private_key, public_key]
        core.run(['gpg', '--homedir', private_key, '--batch', '--faked-system-time', str(int(time.time())-300), '--passphrase', '', '--quick-generate-key', 'Synthetic recovery owner', 'rsa2048', 'encr', '1d'])
        listing = core.run(['gpg', '--homedir', private_key, '--batch', '--with-colons', '--list-keys']).decode()
        fingerprint = next(x.split(':')[9] for x in listing.splitlines() if x.startswith('fpr:'))
        public = core.run(['gpg', '--homedir', private_key, '--batch', '--export', fingerprint])
        core.run(['gpg', '--homedir', public_key, '--batch', '--import'], data=public)
        source_dir = work / 'source'; source_dir.mkdir()
        source = RecoveryExercise(source_dir, a.evidence, selected, None)
        exercises.append(source)
        print('Preparing isolated source and trusted images.', flush=True)
        os.umask(0o022)
        source.prepare()
        os.umask(0o077)
        source.stage_a()
        source.up('mailpit', 'keycloak')
        state = source_dir / 'recovery-journal'
        (state / 'READY').unlink()
        core.Runtime(source.file, source.project, state).gate('close')
        target = adapter.runtime(source.file, source.project, state, 'disposable-admin', source_dir / 'secrets/keycloak-admin-password')
        target.keycloak('POST', '/users', {'id': SUBJECT, 'username': 'recovery@example.test', 'email': 'recovery@example.test',
                                       'emailVerified': True, 'enabled': True, 'firstName': 'Synthetic', 'lastName': 'Recovery'})
        accounts = target.keycloak('GET', '/users?username=recovery%40example.test&exact=true&max=2')
        core.require(len(accounts) == 1, 'Synthetic account was not created uniquely.')
        subject = accounts[0]['id']
        user_id = adapter.pd.derive_user_id(core.ISSUER, subject)
        # Test-only client and non-deleted user: obtain a genuine offline token to prove
        # general revocation rather than merely checking an empty restored session table.
        session_password = secrets.token_hex(24)
        target.keycloak('POST', '/clients', {'clientId':'synthetic-recovery-session', 'publicClient':True,
            'protocol':'openid-connect', 'directAccessGrantsEnabled':True, 'standardFlowEnabled':False,
            'optionalClientScopes':['offline_access']})
        target.keycloak('POST', '/users', {'username':'recovery-keeper@example.test','email':'recovery-keeper@example.test',
            'emailVerified':True,'enabled':True,'firstName':'Synthetic','lastName':'Keeper',
            'credentials':[{'type':'password','value':session_password,'temporary':False}]})
        request = urllib.request.Request('http://keycloak:8080/realms/gameometro/protocol/openid-connect/token',
            data=urllib.parse.urlencode({'grant_type':'password','client_id':'synthetic-recovery-session',
                'username':'recovery-keeper@example.test','password':session_password,'scope':'openid offline_access'}).encode(),
            headers={'Content-Type':'application/x-www-form-urlencoded'})
        with target.admin.opener.open(request) as response:
            token = json.loads(response.read())
        core.require(bool(token.get('refresh_token')), 'Synthetic offline token was not issued.')
        deadline = time.monotonic()+15
        while time.monotonic()<deadline:
            if int(source.sql("SELECT count(*) FROM offline_user_session WHERE offline_flag='1' AND realm_id=(SELECT id FROM realm WHERE name='gameometro');", 'videogame_keycloak'))>0:
                break
            time.sleep(0.1)
        else:
            raise core.Refused('Synthetic offline session was not persisted before backup.')
        receipt['offlineSessionCaptured'] = True
        keeper = target.keycloak('GET', '/users?username=recovery-keeper%40example.test&exact=true&max=2')[0]['id']
        # Real migrations and existing catalogue fixture, one rating owned by the synthetic principal.
        fixture = ROOT / 'backend/src/main/resources/db/dev-seed/V20260809_130000__seed_bounded_prototype_catalogue.sql'
        source.sql(fixture.read_text())
        source.sql(f"INSERT INTO ratings.rating(user_id,game_id,value) SELECT '{user_id}',game_id,8 FROM catalogue.game LIMIT 1;")
        core.Runtime(source.file, source.project, state).gate('open')
        core.write(state / 'READY', {'syntheticFreshStart': True})
        source.up('caddy', 'application')
        runtime = core.Runtime(source.file, source.project, state)
        require = core.require
        local = work / 'source-backup'
        print('Closing writes, draining and capturing encrypted pair.', flush=True)
        inflight = []
        try:
            # Real uncommitted writes in BOTH stores must roll back before the common point.
            for role, database, secret, statement in [
                ('videogame_app', 'videogame_platform', 'application_db_password', 'UPDATE ratings.rating SET value=7;'),
                ('videogame_keycloak', 'videogame_keycloak', 'keycloak_db_password', "UPDATE user_entity SET enabled=false WHERE realm_id=(SELECT id FROM realm WHERE name='gameometro');")]:
                process = subprocess.Popen([*runtime.base, 'exec', '-T', 'postgres', 'bash', '-ec',
                    f'export PGPASSWORD="$(cat /run/secrets/{secret})"; export PGAPPNAME=recovery-drain-test; exec psql -XqAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U {role} -d {database}'],
                    stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                process.stdin.write(('BEGIN;'+statement+'SELECT pg_sleep(120); COMMIT;').encode())
                process.stdin.close()
                inflight.append(process)
            deadline = time.monotonic()+10
            while time.monotonic()<deadline:
                if runtime.sql('postgres', "SELECT count(*) FROM pg_stat_activity WHERE application_name='recovery-drain-test' AND wait_event='PgSleep';").strip() == b'2':
                    break
                time.sleep(0.1)
            else:
                raise core.Refused('Synthetic in-flight writers did not reach the drain point.')
            backup_receipt = runtime.backup(local, public_key, fingerprint)
            for process in inflight:
                require(process.wait(timeout=10) != 0, 'In-flight transaction committed across the fence.')
            receipt['inflightWritesRolledBack'] = True
            for role, database, secret in [('videogame_app', 'videogame_platform', 'application_db_password'), ('videogame_keycloak', 'videogame_keycloak', 'keycloak_db_password')]:
                refused = source.compose('exec', '-T', 'postgres', 'bash', '-ec',
                    f'export PGPASSWORD="$(cat /run/secrets/{secret})"; exec psql -X -h 127.0.0.1 -U {role} -d {database} -Atc "SELECT 1"', check=False)
                require(refused.returncode != 0, 'A fenced consumer reconnected.')
        finally:
            for process in inflight:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=10)
        core.write(a.evidence / 'backup.json', backup_receipt)
        m = core.verify(local)
        receipt['backupId'] = m['backupId']
        receipt['manifestSha256'] = core.digest(local / 'manifest.json')
        receipt['backupSeconds'] = backup_receipt['durationSeconds']
        receipt['encryptedBytes'] = backup_receipt['encryptedBytes']
        receipt['plaintextDumpBytes'] = sum(x['plaintextBytes'] for x in m['artifacts'].values())
        copied = independent / 'encrypted-pair'
        core.copy_pair(local, copied)
        # Retain config and secret files separately, then destroy every source container/volume.
        protected_config = owner / 'compose.json'
        protected_config.write_bytes(source.file.read_bytes())
        secret_copy = owner / 'secrets'
        shutil.copytree(source_dir / 'secrets', secret_copy)
        print('Destroying original runtime; restoring only from the independent encrypted copy.', flush=True)
        source.cleanup()
        shutil.rmtree(local)
        require = core.require
        require(not runtime.compose('ps', '-aq').strip(), 'Original runtime remains.')
        shutil.rmtree(source_dir)  # Original host configuration and credentials are lost too.
        # Record a post-backup deletion in the existing two-copy ledger. A ledger records
        # requests before mutation, so recovery must replay even an interrupted request.
        ledger = owner / 'deletions.jsonl'; ledger_copy = independent / 'deletions.jsonl'
        ledger_module = load('production_recovery_ledger', ROOT / 'deploy/private-dev/bin/personal-data-ledger')
        with ledger_module.open_ledger(ledger, ledger_copy, create=True) as log:
            log.append({'event': 'requested', 'request': 'SYNTHETIC-RECOVERY', 'keycloakUserId': subject, 'userId': user_id})
        checkpoint = owner / 'checkpoint.json'
        core.write(checkpoint, {'environment': 'production', 'asOf': core.utc().isoformat(timespec='seconds'), 'ledgerSha256': core.digest(ledger)})
        replacement_dir = work / 'replacement'; replacement_dir.mkdir()
        replacement = RecoveryExercise(replacement_dir, a.evidence, selected, None)
        exercises.append(replacement)
        os.umask(0o022)
        replacement.prepare()
        os.umask(0o077)
        reconstructed = protected_config.read_text().replace(str(source_dir), str(replacement_dir)).replace(source.project, replacement.project)
        replacement.file.write_text(reconstructed)
        # Recover credentials from separately retained protected material, with newly rotated
        # synthetic DB role passwords (roles are reconstructed, not restored from dumps).
        for path in secret_copy.iterdir():
            (replacement_dir / 'secrets' / path.name).write_bytes(path.read_bytes())
        for name in ['postgres-admin-password', 'application-db-password', 'application-migration-db-password', 'keycloak-db-password']:
            (replacement_dir / 'secrets' / name).write_text(secrets.token_hex(24))
        replacement_state = replacement_dir / 'recovery-journal'
        restored = core.Runtime(replacement.file, replacement.project, replacement_state)
        recovery_started = time.monotonic()
        result = restored.restore(copied, private_key)
        core.write(a.evidence / 'restore.json', result)
        receipt['restoreSeconds'] = result['durationSeconds']
        require(replacement.sql('SELECT value FROM ratings.rating;') == '8', 'Uncommitted rating entered the recovered snapshot.')
        # Guard must reject application startup before ledger replay, even with correct secrets.
        denied = replacement.compose('run', '--rm', '--no-deps', 'application', check=False)
        require(denied.returncode != 0 and 'recovery authorization is closed' in denied.stderr, 'Application opened before replay.')
        host_command = shlex.join([str(x) for x in [sys.executable, ROOT / 'deploy/public-production/bin/recovery-personal-data',
            '--config', replacement.file, '--project', replacement.project, '--state', replacement_state,
            '--admin-username', 'disposable-admin', '--admin-password-file', replacement_dir / 'secrets/keycloak-admin-password']])
        print('Replaying protected deletion ledger with only Keycloak running.', flush=True)
        result = restored.replay(ledger, ledger_copy, checkpoint, host_command)
        core.write(a.evidence / 'deletion-replay.json', result)
        receipt['deletionReplay'] = True
        receipt['offlineSessionsRevoked'] = True
        recovered_admin = adapter.runtime(replacement.file, replacement.project, replacement_state,
            'disposable-admin', replacement_dir / 'secrets/keycloak-admin-password')
        require(recovered_admin.account(keeper)['enabled'], 'Uncommitted identity mutation entered the recovered snapshot.')
        refresh = urllib.request.Request('http://keycloak:8080/realms/gameometro/protocol/openid-connect/token',
            data=urllib.parse.urlencode({'grant_type':'refresh_token','client_id':'synthetic-recovery-session',
                'refresh_token':token['refresh_token']}).encode(),headers={'Content-Type':'application/x-www-form-urlencoded'})
        try:
            with recovered_admin.admin.opener.open(refresh) as response:
                response.read()
        except urllib.error.HTTPError as error:
            require(error.code == 400 and json.loads(error.read()).get('error') == 'invalid_grant', 'Offline refresh failed for an unexpected reason.')
        else:
            raise core.Refused('A pre-loss offline token was still usable after revocation.')
        receipt['oldOfflineRefreshRefused'] = True
        require(replacement.sql('SELECT count(*) FROM ratings.rating;') == '0', 'Deleted rating returned.')
        require(replacement.sql(f"SELECT count(*) FROM user_entity WHERE id='{subject}';", 'videogame_keycloak') == '0', 'Deleted identity returned.')
        restored.reopen(ledger, ledger_copy, checkpoint)
        replacement.up('mailpit', 'caddy', 'application')
        proof = restored.record_recovery(copied, ledger, ledger_copy, checkpoint)
        proof['outcome'] = 'success'
        core.write(a.evidence / 'verified-recovery.json', proof)
        receipt['readiness'] = True
        receipt['restoreToReadinessSeconds'] = round(time.monotonic()-recovery_started, 3)
        # Measurements are of this synthetic machine and data set only.
        receipt['databaseBytes'] = int(replacement.sql('SELECT sum(pg_database_size(datname)) FROM pg_database WHERE datname IN (\'videogame_platform\',\'videogame_keycloak\');', 'postgres'))
        receipt['outcome'] = 'success'
    except Exception as error:
        receipt['failedAt'] = [{'file': Path(f.filename).name, 'line': f.lineno} for f in traceback.extract_tb(error.__traceback__)]
        receipt['failureType'] = type(error).__name__
        print('Recovery rehearsal failed; raw diagnostics withheld.', file=sys.stderr)
        return 1
    finally:
        clean = True
        for exercise in reversed(exercises):
            try:
                exercise.cleanup()
            except Exception:
                clean = False
        for keyring in keyrings:
            try:
                core.run(['gpgconf', '--homedir', keyring, '--kill', 'all'], timeout=30)
            except Exception:
                clean = False
        if temporary:
            temporary.cleanup()
        receipt['exerciseSeconds'] = round(time.monotonic()-started, 3)
        receipt['cleanup'] = clean
        if not clean:
            receipt['outcome'] = 'failure'
        core.write(a.evidence / 'recovery-rehearsal.json', receipt)
    return 0 if receipt['outcome'] == 'success' else 1


if __name__ == '__main__':
    sys.exit(main())
