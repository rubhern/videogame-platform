#!/usr/bin/env python3
"""Opt-in local synthetic PostgreSQL/Keycloak restore through the Actions adapter.

Only the Actions API and S3 transport are replaced. Uses genuine PostgreSQL custom dumps,
GPG decryption-subkey export, canonical paired backup, offline identity verification,
real isolated pg_restore and the actual cleanup. No production/WSL key or VPS is accessed.
"""
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
from unittest.mock import patch
import uuid

import production_recovery_executor as executor
import production_deploy as deploy
import prepare_private_production as prepare

ROOT = Path(__file__).resolve().parents[1]


def main():
    os.umask(0o022)
    loader = importlib.machinery.SourceFileLoader('synthetic_actions_restore', str(ROOT/'scripts/rehearse-private-production-upgrade.py'))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    checker = importlib.util.module_from_spec(spec)
    loader.exec_module(checker)
    core = executor.load_core()
    images = json.loads((ROOT/'docs/research/public-production-candidate-evidence/final-9ffc05a-campaign.json').read_text())['publications']
    project = 'vgp-production-test-' + secrets.token_hex(6)
    run_id = str(int.from_bytes(secrets.token_bytes(5), 'big'))
    sha = 'a'*40
    previous = Path.cwd()
    with tempfile.TemporaryDirectory(prefix='vgp-actions-recovery-') as name:
        work = Path(name)
        work.chmod(0o755)
        secrets_dir = work/'secrets'
        checker.synthetic_secrets(secrets_dir)
        private, public = work/'original-key', work/'public-key'
        private.mkdir(mode=0o700); public.mkdir(mode=0o700)
        def gpg(home, *args, data=None):
            return core.run(['gpg', '--homedir', home, '--batch', '--pinentry-mode', 'loopback', '--passphrase', '', *args], data=data)
        config_file = work/'compose.json'
        env = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'GITHUB_SHA': sha,
               'GITHUB_RUN_ID': run_id, 'GITHUB_RUN_ATTEMPT': '1', 'GH_TOKEN': 'synthetic-unused', 'RUNNER_TEMP': str(work)}
        try:
            gpg(private, '--quick-generate-key', 'Synthetic Actions recovery #467', 'ed25519', 'cert', '1d')
            primary = next(x.split(':')[9] for x in gpg(private, '--with-colons', '--list-keys').decode().splitlines() if x.startswith('fpr:'))
            gpg(private, '--quick-add-key', primary, 'cv25519', 'encr', '1d')
            subkey = [x.split(':')[9] for x in gpg(private, '--with-colons', '--with-subkey-fingerprint', '--list-keys').decode().splitlines() if x.startswith('fpr:')][-1]
            key = gpg(private, '--armor', '--export-secret-subkeys', subkey+'!').decode()
            gpg(public, '--import', data=gpg(private, '--export', primary))
            values = prepare.parse_runtime_env((ROOT/'deploy/public-production/runtime.env.example').read_text())
            values.update(PRODUCTION_SECRETS_DIR=str(secrets_dir), PRODUCTION_SECRETS_GID=str(os.getgid()),
                          CATALOGUE_TRANSLATION_MODEL_DIR=str(work), PRODUCTION_METRICS_DIR=str(work))
            for key_name, image in images.items(): values[key_name.upper().replace('-', '_')+'_IMAGE'] = image
            runtime_env = work/'runtime.env'
            runtime_env.write_text(''.join(f'{k}={v}\n' for k,v in values.items()))
            config = checker.isolate(prepare.render_base(runtime_env, ROOT), project)
            for service in config['services'].values(): service.pop('ports', None)
            config_file.write_text(json.dumps(config))
            state = work/'source-state'; state.mkdir(mode=0o700)
            runtime = core.Runtime(config_file, project, state)
            runtime.initialize()
            runtime.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'postgres')
            subject = '11111111-1111-4111-8111-111111111111'
            principal = str(uuid.UUID(bytes=hashlib.md5((core.ISSUER+'\n'+subject).encode()).digest(), version=3))
            runtime.sql('videogame_platform', "CREATE SCHEMA ratings; CREATE TABLE ratings.rating(user_id uuid); INSERT INTO ratings.rating VALUES ('"+principal+"'); CREATE TABLE flyway_schema_history(installed_rank int,version text,success boolean); INSERT INTO flyway_schema_history VALUES (1,'1',true);")
            runtime.sql('videogame_keycloak', "CREATE TABLE databasechangelog(id text,author text,md5sum text,orderexecuted int); INSERT INTO databasechangelog VALUES ('1','synthetic','checksum',1); CREATE TABLE realm(id text,name text); INSERT INTO realm VALUES ('realm','gameometro'); CREATE TABLE user_entity(id text,realm_id text); INSERT INTO user_entity VALUES ('"+subject+"','realm');")
            # Include the installed monitoring reader, as the real production pair does.
            runtime.sql('videogame_platform', 'CREATE SCHEMA catalogue;')
            for view in deploy.upgrade.READER_VIEWS:
                runtime.sql('videogame_platform', 'CREATE VIEW ' + view + ' AS SELECT 1 AS synthetic_value;')
            reader = work/'reader-password'
            reader.write_text(secrets.token_hex(24)); reader.chmod(0o600)
            runtime.provision_reader(reader)
            pair = work/'source-pair'
            with runtime.lock(): runtime.backup(pair, public, primary)
            manifest = core.verify(pair)
            env.update(RECOVERY_GPG_KEY=key, RECOVERY_GPG_PASSPHRASE='', RECOVERY_S3_CONFIG='{}',
                       RECOVERY_PRIMARY_FINGERPRINT=primary, RECOVERY_DECRYPTION_FINGERPRINT=subkey, RECOVERY_KEY_SCOPE='subkey')
            real_invoke = executor.invoke
            fault = {'mode': None}
            def transport(args, **kwargs):
                if 'admission' in args: return b''
                if 'fetch' in args:
                    destination = Path(args[args.index('--destination')+1])
                    core.copy_pair(pair, destination)
                    if fault['mode'] == 'interrupt':
                        raise KeyboardInterrupt
                    if fault['mode'] == 'corrupt':
                        ciphertext = destination/core.FILES[0]
                        ciphertext.write_bytes(ciphertext.read_bytes() + b'tampered')
                    return b''
                return real_invoke(args, **kwargs)
            os.chdir(work)
            with patch.dict(os.environ, env), patch.object(deploy, 'admission'), patch.object(deploy, 'approved'), patch.object(executor, 'invoke', side_effect=transport):
                try:
                    executor.prepare(sha, run_id, '1')
                    ready = json.loads(Path('recovery-ready.json').read_text())
                    sizes = [int(x) for x in runtime.sql('postgres', "SELECT sum(pg_database_size(datname)),max(pg_database_size(datname)) FROM pg_database WHERE datname IN ('videogame_platform','videogame_keycloak');").decode().strip().split('|')]
                    request = {'revision': sha, 'runId': run_id, 'attempt': '1', 'nonce': ready['nonce'],
                               'kind': 'recovery-request', 'outcome': 'success', 'planSha256': 'd'*64,
                               'publication': {'kind': 's3-publish', 'outcome': 'success', 'backupId': manifest['backupId']},
                               'resources': dict(databaseBytes=sizes[0],largestDatabaseBytes=sizes[1])}
                    with patch.object(executor.evidence, 'read', return_value=request): executor.restore(sha, run_id, '1')
                    executor.cleanup(sha, run_id, '1')
                    result = json.loads(Path('recovery-result.json').read_text())
                    assert result['cleanup'] == 'verified' and result['backupId'] == manifest['backupId']
                    assert runtime.gate('check') == b'closed' and (state/'PENDING.json').exists()
                    assert not executor.paths(run_id, '1').exists()
                    Path('recovery-result.json').unlink()
                    for index, failure in enumerate(('corrupt', 'interrupt'), start=1):
                        failed_run = str(int(run_id) + index)
                        failed_env = {**env, 'GITHUB_RUN_ID': failed_run}
                        fault['mode'] = failure
                        with patch.dict(os.environ, failed_env):
                            try:
                                executor.prepare(sha, failed_run, '1')
                                failed_ready = json.loads(Path('recovery-ready.json').read_text())
                                failed_request = {**request, 'runId': failed_run, 'nonce': failed_ready['nonce']}
                                refused = False
                                try:
                                    with patch.object(executor.evidence, 'read', return_value=failed_request):
                                        executor.restore(sha, failed_run, '1')
                                except (Exception, KeyboardInterrupt):
                                    refused = True
                                assert refused, 'The failed restore was accepted.'
                            finally:
                                executor.cleanup(sha, failed_run, '1')
                            assert not executor.paths(failed_run, '1').exists()
                            assert not Path('recovery-result.json').exists()
                            assert runtime.gate('check') == b'closed'
                finally:
                    if executor.paths(run_id, '1').exists(): executor.cleanup(sha, run_id, '1')
            print(json.dumps({'outcome':'success','scope':'local-synthetic-only','subkeyOnly':True,
                              'realPairedBackupAndRestore':True,'monitoringReaderRestored':True,'sourceFencePreserved':True,'cleanupVerified':True,'ciphertextFailureRefused':True,'interruptionCleaned':True,
                              'actionsAndS3Transport':'simulated','vpsChanged':False}))
        finally:
            os.chdir(previous)
            if config_file.exists():
                core.run(['docker','compose','-p',project,'-f',config_file,'--profile','*','down','--volumes','--remove-orphans','--timeout','5'])
            for ring in (private,public):
                subprocess.run(['gpgconf','--homedir',str(ring),'--kill','all'],capture_output=True)


if __name__ == '__main__':
    main()
