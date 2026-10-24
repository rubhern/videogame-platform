#!/usr/bin/env python3
"""Synthetic complete host-loss recovery using retained trusted-main publications."""
import argparse
import base64
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
monitoring = load('recovery_monitoring_overlay', ROOT / 'scripts/rehearse-production-cleanup.py').monitoring


def grafana_datasource(exercise):
    """Authenticate to the real Grafana API; its datasource must authenticate to PostgreSQL."""
    config = json.loads(exercise.file.read_text())
    opener = adapter.DockerOpener(config['networks']['telemetry']['name'], exercise.project)
    secret = Path(config['secrets']['grafana_admin_password']['file']).read_text().strip()
    auth = base64.b64encode(('owner:'+secret).encode()).decode()
    request = urllib.request.Request('http://grafana:3000/api/ds/query',
        data=json.dumps({'from':'now-1m','to':'now','queries':[{'refId':'A',
            'datasource':{'uid':'private-dev-postgres','type':'grafana-postgresql-datasource'},
            'rawSql':'SELECT count(*)::int AS visible_rows FROM ratings.observability_summary',
            'format':'table'}]}).encode(),
        headers={'Authorization':'Basic '+auth,'Content-Type':'application/json'})
    deadline=time.monotonic()+90
    while True:
        try:
            with opener.open(request) as response:
                result=json.loads(response.read())['results']['A']
            core.require(result.get('status',200)==200 and not result.get('error') and result.get('frames'),
                         'Grafana PostgreSQL datasource failed.')
            core.require(result['frames'][0]['data']['values'][0][0] >= 0,
                         'Grafana PostgreSQL datasource returned no readable rows.')
            return
        except (urllib.error.URLError, core.Refused, adapter.recovery.Refused, KeyError, IndexError):
            core.require(time.monotonic()<deadline,'Grafana PostgreSQL datasource did not become ready.')
            time.sleep(1)


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
        integration.verify_local_docker()
        receipt['dockerEngine'] = integration.command(['docker','info','--format','{{.Name}} {{.OperatingSystem}}']).stdout.strip()
        receipt['workingTreeBase'] = integration.command(['git','rev-parse','HEAD']).stdout.strip()
        receipt['runtimeSourceFingerprint'] = integration.deployment.local_configuration()
        receipt['sourceFiles'] = {p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in (
            'scripts/rehearse-production-recovery.py','scripts/rehearse-production-cleanup.py',
            'deploy/public-production/bin/recovery','deploy/private-dev/bin/personal-data-ledger')}
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
        core.run(['gpg', '--homedir', private_key, '--batch', '--faked-system-time', str(int(time.time())-300), '--passphrase', '', '--quick-generate-key', 'Synthetic recovery owner', 'rsa2048', 'sign,encr', '1d'])
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
        monitoring(source)
        receipt['sourceRenderedConfigurationSha256'] = hashlib.sha256(source.file.read_bytes()).hexdigest()
        config = json.loads(source.file.read_text())
        receipt['configuredImages'] = {name:service['image'] for name,service in config['services'].items()}
        receipt['exercisedServices'] = ['postgres','migration','keycloak','mailpit','caddy','application','grafana']
        receipt['catalogueSyncSchedulingEnabled'] = config['services']['application']['environment'].get('CATALOGUE_SYNC_SCHEDULING_ENABLED')
        core.require(config['networks']['notification-egress']['internal'] is True, 'Rehearsal notification egress is not isolated.')
        receipt['notificationEgressIsolatedOnlyInRehearsal'] = True
        os.umask(0o077)
        source.stage_a()
        receipt['migrationVersion'] = source.receipt['migrationVersion']
        reader_password = owner / 'grafana-password'; reader_password.write_bytes(Path(config['secrets']['grafana_database_password']['file']).read_bytes()); reader_password.chmod(0o600)
        core.Runtime(source.file, source.project, source_dir/'recovery-journal').provision_reader(reader_password)
        receipt['monitoringReaderProvisioned'] = True
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
        source.up('caddy', 'application', 'grafana')
        grafana_datasource(source)
        receipt['grafanaDatasourceBeforeBackup'] = True
        runtime = core.Runtime(source.file, source.project, state)
        require = core.require
        sql = runtime.sql
        def observed_sql(database, query, output=None):
            if 'pg_terminate_backend' in query:
                require(not source.compose('ps','-q','--status','running','grafana').stdout.strip(),
                        'Grafana was still running at the session drain.')
                receipt['grafanaStoppedBeforeDrain'] = True
            return sql(database,query,output)
        runtime.sql = observed_sql
        local = work / 'source-backup'
        print('Closing writes, draining and capturing encrypted pair.', flush=True)
        inflight = []
        reader = subprocess.Popen([*runtime.base,'exec','-T','postgres','bash','-ec',
            'IFS= read -r PGPASSWORD; export PGPASSWORD PGAPPNAME=recovery-reader-test; exec psql -XqAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U videogame_grafana -d videogame_platform'],
            stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        reader.stdin.write((reader_password.read_text()+"\nSET statement_timeout='120s'; SELECT pg_sleep(100);\n").encode()); reader.stdin.close()
        inflight.append(reader)
        deadline=time.monotonic()+15
        while runtime.sql('postgres', "SELECT count(*) FROM pg_stat_activity WHERE usename='videogame_grafana' AND application_name='recovery-reader-test' AND wait_event='PgSleep';").strip()!=b'1':
            require(time.monotonic()<deadline,'Monitoring reader connection was not established.');time.sleep(.1)
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
            receipt['monitoringReaderDrained'] = reader.returncode != 0
            require(runtime.sql('postgres', "SELECT NOT rolcanlogin FROM pg_roles WHERE rolname='videogame_grafana';").strip()==b't','Monitoring reader was not fenced.')
            runtime.sql('postgres','CREATE ROLE synthetic_unknown LOGIN;')
            try:
                try: runtime.assert_fenced()
                except core.Refused: receipt['unknownLoginRoleRefused'] = True
                else: raise core.Refused('Unknown login role was admitted.')
            finally: runtime.sql('postgres','DROP ROLE synthetic_unknown;')
            runtime.assert_fenced()
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
        # Restore ordinary logins explicitly, then start a real profiled erasure while
        # consumers remain stopped. The existing maintenance fence owns the admin calls.
        runtime.thaw()
        require(runtime.sql('postgres', "SELECT rolcanlogin FROM pg_roles WHERE rolname='videogame_grafana';").strip()==b't','Thaw left monitoring reader fenced.')
        receipt['monitoringReaderThawed'] = True
        source.up('keycloak')
        ledger = owner / 'deletions.jsonl'; ledger_copy = independent / 'deletions.jsonl'
        archive_inputs = {'archive_root':owner/'archive','archive_copy':independent/'archive',
            'gnupg_home':private_key,'recipient':fingerprint,'signer':fingerprint,
            'proof_root':owner/'proof','holds':owner/'holds'}
        for name in ('archive_root','archive_copy','proof_root','holds'): archive_inputs[name].mkdir(mode=0o700)
        host = [sys.executable,ROOT/'deploy/public-production/bin/recovery-personal-data','--config',source.file,
            '--project',source.project,'--state',state,'--admin-username','disposable-admin',
            '--admin-password-file',source_dir/'secrets/keycloak-admin-password','--identity-cleanup']
        maintenance = load('recovery_profile_maintenance',ROOT/'deploy/public-production/bin/identity-maintenance')
        maintenance_command = [sys.executable,ROOT/'deploy/public-production/bin/identity-maintenance',
            '--config',source.file,'--project',source.project,'--state',state]
        # Pause BEFORE a destructive command executes, AFTER the real ledger sealed both copies.
        marker=owner/'sealed-inflight'; wrapper=owner/'pause-host.py'
        wrapper.write_text('import pathlib,subprocess,sys,time\ncommand='+repr(list(map(str,host)))+
            '\nif sys.argv[1]=="delete-ratings":\n pathlib.Path('+repr(str(marker))+').write_text("sealed")\n time.sleep(600)\n'+
            'r=subprocess.run(command+sys.argv[1:],capture_output=True)\nsys.stdout.buffer.write(r.stdout);sys.exit(r.returncode)\n')
        archive_args=[item for key,value in archive_inputs.items() for item in ('--'+key.replace('_','-'),str(value))]
        erasure=subprocess.Popen([sys.executable,'-I',ROOT/'deploy/private-dev/bin/personal-data-ledger',
            '--ledger',ledger,'--ledger-copy',ledger_copy,'--host-command',shlex.join([sys.executable,str(wrapper)]),
            *archive_args,'erase','--request','SYNTHETIC-RECOVERY','--keycloak-user-id',subject,
            '--archive-profile','requested-account-v1','--maintenance-command',shlex.join(map(str,maintenance_command))],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
        try:
            deadline=time.monotonic()+120
            while not marker.exists():
                require(erasure.poll() is None and time.monotonic()<deadline,'Profiled erasure did not reach sealed in-flight boundary.');time.sleep(.2)
            original_archive_sha = hashlib.sha256((archive_inputs['archive_root']/'SYNTHETIC-RECOVERY/source.gpg').read_bytes()).hexdigest()
            original_ciphertext=(archive_inputs['archive_root']/'SYNTHETIC-RECOVERY/source.gpg').read_bytes()
            require(original_ciphertext==(archive_inputs['archive_copy']/'SYNTHETIC-RECOVERY/source.gpg').read_bytes(),'Archive copies differ before host loss.')
            require(source.sql('SELECT count(*) FROM ratings.rating;')=='1','Destructive step ran before interruption.')
            receipt['profiledErasureInterruptedAfterSealingBeforeDeletion']=True
        finally:
            if erasure.poll() is None: os.killpg(erasure.pid,signal.SIGTERM)
            erasure.wait(timeout=15)
        protected_config = owner / 'compose.json'; protected_config.write_bytes(source.file.read_bytes())
        secret_copy = owner / 'secrets'; shutil.copytree(source_dir / 'secrets', secret_copy)
        print('Destroying original runtime with sealed incomplete erasure; restoring independent encrypted pair.', flush=True)
        source.cleanup(); shutil.rmtree(local)
        require(not runtime.compose('ps', '-aq').strip(), 'Original runtime remains.')
        shutil.rmtree(source_dir)
        checkpoint = owner / 'checkpoint.json'
        core.write(checkpoint, {'environment': 'production', 'asOf': core.utc().isoformat(timespec='seconds'), 'ledgerSha256': core.digest(ledger)})
        replacement_dir = work / 'replacement'; replacement_dir.mkdir()
        replacement = RecoveryExercise(replacement_dir, a.evidence, selected, None)
        exercises.append(replacement)
        os.umask(0o022)
        replacement.prepare()
        monitoring(replacement)
        os.umask(0o077)
        reconstructed = protected_config.read_text().replace(str(source_dir), str(replacement_dir)).replace(source.project, replacement.project)
        replacement.file.write_text(reconstructed)
        receipt['replacementRenderedConfigurationSha256'] = hashlib.sha256(replacement.file.read_bytes()).hexdigest()
        # Recover credentials from separately retained protected material, with newly rotated
        # synthetic DB role passwords (roles are reconstructed, not restored from dumps).
        for path in secret_copy.iterdir():
            (replacement_dir / 'secrets' / path.name).write_bytes(path.read_bytes())
        for name in ['postgres-admin-password', 'application-db-password', 'application-migration-db-password', 'keycloak-db-password']:
            (replacement_dir / 'secrets' / name).write_text(secrets.token_hex(24))
        replacement_state = replacement_dir / 'recovery-journal'
        restored = core.Runtime(replacement.file, replacement.project, replacement_state)
        recovery_started = time.monotonic()
        datasource_password = Path(json.loads(replacement.file.read_text())['secrets']['grafana_database_password']['file'])
        require(reader_password.read_bytes()==datasource_password.read_bytes(), 'Reconstruction credential differs from the actual Grafana datasource secret.')
        receipt['reconstructionCredentialMatchesDatasourceSecret'] = True
        result = restored.restore(copied, private_key, reader_password)
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
        # Missing archive inputs must fail closed for this genuinely incomplete profile.
        try: restored.replay(ledger,ledger_copy,checkpoint,host_command)
        except core.Refused: receipt['missingArchiveReplayRefused']=True
        else: raise core.Refused('Profiled replay succeeded without protected archive inputs.')
        require(not (replacement_state/'REPLAY.json').exists() and restored.gate('check')==b'closed','Failed replay reopened the runtime.')
        result = restored.replay(ledger, ledger_copy, checkpoint, host_command, archive_inputs=archive_inputs)
        require((archive_inputs['archive_root']/'SYNTHETIC-RECOVERY/source.gpg').read_bytes()==original_ciphertext
                and (archive_inputs['archive_copy']/'SYNTHETIC-RECOVERY/source.gpg').read_bytes()==original_ciphertext,'Replay recaptured or replaced the original ciphertext.')
        receipt['originalArchiveCiphertextPreserved']=True
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
        require(replacement.sql("SELECT NOT rolcanlogin FROM pg_roles WHERE rolname='videogame_grafana';",'postgres')=='t','Reader reopened before replay acceptance.')
        # Real unknown role must prevent reopening before any known role is enabled.
        restored.sql('postgres','CREATE ROLE synthetic_unknown LOGIN;')
        try:
            try: restored.reopen(ledger,ledger_copy,checkpoint)
            except core.Refused: receipt['unknownLoginRoleReopenRefused'] = True
            else: raise core.Refused('Reopen admitted an unknown login role.')
            require(restored.gate('check')==b'closed','Refused reopen opened the gate.')
        finally: restored.sql('postgres','DROP ROLE synthetic_unknown;')
        restored.reopen(ledger, ledger_copy, checkpoint)
        require(replacement.sql("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname='videogame_grafana';",'postgres')=='t','Reader reconstruction failed.')
        require(replacement.sql("SELECT has_table_privilege('videogame_grafana','ratings.observability_summary','SELECT') AND NOT has_table_privilege('videogame_grafana','ratings.rating','SELECT');",'videogame_platform')=='t','Monitoring grants were not safely reconstructed.')
        reader_check=subprocess.run([*restored.base,'exec','-T','postgres','bash','-ec',
            'IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql -XqAt -v ON_ERROR_STOP=1 -h 127.0.0.1 -U videogame_grafana -d videogame_platform'],
            input=(reader_password.read_text()+"\nSHOW default_transaction_read_only; SELECT count(*) >= 0 FROM ratings.observability_summary;\n").encode(),capture_output=True)
        require(reader_check.returncode==0 and reader_check.stdout.splitlines()==[b'on',b't'],'Reconstructed monitoring reader could not authenticate/read safely.')
        receipt['monitoringReaderFreshLoginVerified']=True
        receipt['monitoringReaderReconstructedAndReopened']=True
        replacement.up('mailpit', 'caddy', 'application', 'grafana')
        grafana_datasource(replacement)
        receipt['grafanaDatasourceAfterReopenRestart'] = True
        proof = restored.record_recovery(copied, ledger, ledger_copy, checkpoint)
        proof['outcome'] = 'success'
        core.write(a.evidence / 'verified-recovery.json', proof)
        receipt['readiness'] = True
        print('Completing the interrupted requested-account ledger through fenced live retry.', flush=True)
        retry_host=shlex.join([str(x) for x in [sys.executable,ROOT/'deploy/public-production/bin/recovery-personal-data',
            '--config',replacement.file,'--project',replacement.project,'--state',replacement_state,
            '--admin-username','disposable-admin','--admin-password-file',replacement_dir/'secrets/keycloak-admin-password',
            '--identity-cleanup']])
        retry_maintenance=shlex.join([str(x) for x in [sys.executable,ROOT/'deploy/public-production/bin/identity-maintenance',
            '--config',replacement.file,'--project',replacement.project,'--state',replacement_state]])
        core.run([sys.executable,'-I',ROOT/'deploy/private-dev/bin/personal-data-ledger','--ledger',ledger,
            '--ledger-copy',ledger_copy,'--host-command',retry_host,*archive_args,
            'retry','--maintenance-command',retry_maintenance])
        events=[json.loads(line) for line in ledger.read_text().splitlines()]
        ledger_core = load('recovery_completed_ledger', ROOT/'deploy/private-dev/bin/personal-data-ledger')
        with ledger_core.open_ledger(ledger,ledger_copy) as completed_ledger:
            completed=completed_ledger.entry(subject)
            require(completed['completedAt'] and all(completed['steps'].get(step)=='done' for step in ledger_core.STEPS),
                    'Retry left an incomplete ledger state.')
        require(events[-1]['event']=='completed' and ledger.read_bytes()==ledger_copy.read_bytes(),
                'Retry did not complete the replicated ledger.')
        proof=json.loads((archive_inputs['proof_root']/'SYNTHETIC-RECOVERY.json').read_text())
        require(proof['verifiedAbsent'] is True and proof['archiveSha256']==original_archive_sha,
                'Suppression proof does not reference the original sealed ciphertext.')
        require(all((archive_inputs[key]/'SYNTHETIC-RECOVERY/source.gpg').read_bytes()==original_ciphertext
                    for key in ('archive_root','archive_copy')), 'Retry replaced sealed ciphertext.')
        require(restored.gate('maintenance-check')==b'none' and restored.gate('check')==b'open',
                'Retry did not safely release its own maintenance fence.')
        receipt['requestedAccountRetryCompleted']=True
        receipt['suppressionProofOriginalArchiveSha256']=True
        receipt['sealedCiphertextPreservedThroughRetry']=True
        receipt['retryMaintenanceReleased']=True
        receipt['originalArchiveSha256']=original_archive_sha
        receipt['ledgerCompletionSha256']=core.digest(ledger)
        receipt['verifiedRecoveryRecordedBeforeLedgerRetry']=True
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
