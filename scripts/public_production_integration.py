#!/usr/bin/env python3
"""Disposable production-runtime exercise; never a deployment or capacity acceptance."""
import argparse
import contextlib
import hashlib
import io
import importlib.util
import importlib.machinery
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import tempfile
import threading
import time
import zipfile

import private_dev_promotion as trust
import public_production_deployment as deployment

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ROOT / 'deploy/public-production/compose.yaml'
PLAYWRIGHT = 'mcr.microsoft.com/playwright@sha256:47b510dd9230bf0b9bfc9b20102241e577ea7a99334ab4c3ca19f19770e9b8ea'
REPOSITORIES = {
    'application': trust.IMAGE_REPOSITORY,
    'keycloak': trust.IMAGE_REPOSITORY + '-keycloak',
    'catalogue-localizer': trust.IMAGE_REPOSITORY + '-catalogue-localizer',
    'caddy': trust.IMAGE_REPOSITORY + '-caddy',
}
require = trust.require


def command(args, *, data=None, check=True, timeout=600):
    result = subprocess.run([str(x) for x in args], input=data, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        # Raw service/browser output can contain credentials, codes and email addresses.
        error = trust.Refused(f'Command failed ({result.returncode}): {args[0]} {args[1] if len(args)>1 else ""}; raw output withheld.')
        error.result = result
        raise error
    return result


def verify_local_docker():
    # A rehearsal flag must not redirect synthetic workloads onto a remote host.
    host = os.environ.get('DOCKER_HOST') if not os.environ.get('DOCKER_CONTEXT') else None
    if not host:
        host = command(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']).stdout.strip()
    require(host.startswith('unix:///'), 'Rehearsal requires a local Docker Unix socket; remote contexts are refused.')


def dependency_publication(api, run, revision, name):
    listing = api.request(f"/actions/runs/{run['id']}/artifacts?per_page=100")
    require(listing.get('total_count', 101) <= 100, 'Too many publication artifacts.')
    matches = [a for a in listing['artifacts'] if a['name'] == f'dependency-image-publication-{name}-{revision}']
    require(len(matches) == 1 and not matches[0]['expired'], f'{name}: missing, ambiguous or expired publication.')
    artifact = matches[0]
    require(artifact['created_at'] >= run['run_started_at'], f'{name}: publication predates run attempt.')
    archive = api.request(f"/actions/artifacts/{artifact['id']}/zip", archive=True)
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        require(bundle.namelist() == ['published-image.txt'], 'Unexpected dependency publication archive.')
        require(bundle.getinfo('published-image.txt').file_size <= 65536, 'Oversized publication.')
        lines = bundle.read('published-image.txt').decode().splitlines()
    repo = REPOSITORIES[name]
    require([x for x in lines if x.startswith('tag=')] == [f'tag={repo}:{revision}'], f'{name}: inconsistent source tag.')
    require([x for x in lines if x.startswith('revision=')] == [f'revision={revision}'], f'{name}: inconsistent source revision.')
    digests = [x.removeprefix('digest=') for x in lines if x.startswith('digest=')]
    require(len(digests) == 1 and re.fullmatch(re.escape(repo) + r'@sha256:[0-9a-f]{64}', digests[0]), f'{name}: immutable repository digest required.')
    return digests[0]


def resolve(api, revision):
    selected = trust.discover_promotion(api, revision)
    run_id, attempt = selected['source_run_id'], selected['source_run_attempt']
    run = trust.verify_run(api, run_id, revision, 'build-and-verify.yml', (
        'Required quality gate', 'Publish immutable application image',
        'Publish immutable production dependency image (keycloak)',
        'Publish immutable production dependency image (catalogue-localizer)',
        'Publish immutable production dependency image (caddy)',
    ), attempt)
    images = {'application': REPOSITORIES['application'] + '@' + selected['image_digest']}
    for name in ('keycloak', 'catalogue-localizer', 'caddy'):
        images[name] = dependency_publication(api, run, revision, name)
    trust.verify_run(api, run_id, revision, 'build-and-verify.yml', (), attempt)
    return {**selected, 'images': images}


def verify_model(directory):
    directory = Path(directory).resolve()
    manifest = json.loads((directory / 'manifest.json').read_text())
    approved = json.loads((ROOT / 'tools/catalogue-localization/models.json').read_text())['tcbig']
    require(manifest['model'] == approved, 'Only the approved tcbig model is supported.')
    names = {'model.bin', 'config.json', 'shared_vocabulary.json', 'source.spm', 'target.spm'}
    require(set(manifest['files']) == names, 'Incomplete model checksum inventory.')
    for name, digest in manifest['files'].items():
        with (directory / name).open('rb') as stream:
            require(hashlib.file_digest(stream, 'sha256').hexdigest() == digest, 'Model artifact checksum mismatch.')
    digest = hashlib.sha256(json.dumps(manifest['files'], sort_keys=True).encode()).hexdigest()
    require((directory / 'revision.txt').read_text().strip() == f'opus-tcbig:{digest};ct2-4.8.2;int8;beam2;chunk256;decode511', 'Model revision mismatch.')
    return directory


def isolated_config(config, project, directory):
    """Change only test transport/trust, synthetic inputs and deny all external egress."""
    config['name'] = project
    for name, value in config['networks'].items():
        value['name'] = f'{project}-{name}'
        value['internal'] = True
    for name, value in config['volumes'].items():
        value['name'] = f'{project}-{name}'
    config['networks']['test-client'] = {'name': project + '-test-client', 'internal': True}
    services = config['services']
    services['caddy'].pop('ports', None)
    services['caddy']['networks']['test-client'] = {'aliases': ['gameometro.com', 'auth.gameometro.com']}
    services['caddy']['volumes'] = [v for v in services['caddy']['volumes'] if v['target'] != '/etc/caddy/Caddyfile']
    services['caddy']['volumes'] += [mount(directory / 'Caddyfile', '/etc/caddy/Caddyfile'), mount(directory / 'pki', '/run/pki')]
    services['application']['environment']['JAVA_TOOL_OPTIONS'] = '-Djavax.net.ssl.trustStore=/run/pki/truststore.p12 -Djavax.net.ssl.trustStorePassword=disposable'
    services['application']['volumes'].append(mount(directory / 'pki', '/run/pki'))
    services['keycloak']['environment']['JAVA_OPTS_APPEND'] = '-Djavax.net.ssl.trustStore=/run/pki/truststore.p12 -Djavax.net.ssl.trustStorePassword=disposable'
    services['keycloak']['volumes'].append(mount(directory / 'pki', '/run/pki'))
    mail_image = re.search(r'^\s*image: (.+)$', (ROOT / 'deploy/private-dev/compose.mail.yaml').read_text(), re.M)[1]
    services['mailpit'] = {
        'image': mail_image, 'read_only': True, 'cap_drop': ['ALL'],
        'security_opt': ['no-new-privileges:true'], 'user': '65534:65534',
        'tmpfs': ['/tmp:rw,noexec,nosuid,size=32m'], 'mem_limit': '128m', 'pids_limit': 64,
        'cpus': 0.25, 'logging': services['postgres']['logging'],
        'networks': {'identity-edge': {'aliases': ['mail.gameometro.test']}, 'test-client': {}},
        'volumes': [mount(directory / 'pki', '/run/pki'), mount(directory / 'smtp-auth', '/run/smtp-auth')],
        'command': ['--max=100', '--smtp-disable-rdns', '--disable-version-check', '--allowed-hosts=mailpit:8025',
                    '--smtp=0.0.0.0:587', '--smtp-tls-cert=/run/pki/mail.crt', '--smtp-tls-key=/run/pki/mail.key',
                    '--smtp-require-starttls', '--smtp-auth-file=/run/smtp-auth'],
    }
    # Profiles force explicit stage selection; a bare up never starts the application.
    return config


def mount(source, target, read_only=True):
    return {'type': 'bind', 'source': str(source), 'target': target, 'read_only': read_only,
            'bind': {'create_host_path': False}}


class Exercise:
    def __init__(self, directory, evidence, selected, model):
        self.directory, self.evidence, self.selected, self.model = directory, evidence, selected, model
        self.project = 'vgp-production-test-' + secrets.token_hex(6)
        self.file = directory / 'compose.json'
        self.phase = 'prepare'
        self.stop = threading.Event()
        self.samples = {}
        self.thread = None
        self.expected_stops = []
        self.started_at = int(time.time())
        self.receipt = {'outcome': 'failure', 'phase': self.phase, 'provenance': selected,
                        'localizer': 'pending' if model else 'not-exercised-model-not-supplied',
                        'productionReady': False, 'stages': [], 'resources': self.samples,
                        'startedAtUnix': self.started_at,
                        'measurement': 'Docker sampled working-set memory every >=2 seconds; steady is the final idle sample. No VPS capacity claim.'}

    def compose(self, *args, **kwargs):
        return command(['docker', 'compose', '-p', self.project, '--env-file', '/dev/null', '-f', self.file, '--profile', '*', *args], **kwargs)

    def prepare(self):
        d = self.directory
        (d / 'browser').mkdir(mode=0o770)
        (d / 'browser').chmod(0o770)
        d.chmod(0o755)  # disposable mounts must be traversable by unrelated container UIDs
        secret_dir = d / 'secrets'
        secret_dir.mkdir(mode=0o750)
        names = ['postgres-admin-password', 'application-db-password', 'application-migration-db-password',
                 'keycloak-db-password', 'keycloak-admin-password', 'keycloak-bff-client-secret',
                 'keycloak-smtp-username', 'keycloak-smtp-password', 'igdb-client-id', 'igdb-client-secret']
        for name in names:
            path = secret_dir / name
            path.write_text('' if name.startswith('igdb-') else secrets.token_hex(24))
            path.chmod(0o640)
        pki = d / 'pki'
        pki.mkdir()
        command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=Disposable runtime CA', '-keyout', pki / 'ca.key', '-out', pki / 'ca.crt'])
        for name, hosts in [('edge', 'DNS:gameometro.com,DNS:auth.gameometro.com'), ('mail', 'DNS:mail.gameometro.test')]:
            command(['openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=Disposable runtime', '-keyout', pki / f'{name}.key', '-out', pki / f'{name}.csr'])
            (pki / 'extensions').write_text('subjectAltName=' + hosts + '\nextendedKeyUsage=serverAuth\n')
            command(['openssl', 'x509', '-req', '-in', pki / f'{name}.csr', '-CA', pki / 'ca.crt', '-CAkey', pki / 'ca.key', '-CAcreateserial', '-days', '1', '-extfile', pki / 'extensions', '-out', pki / f'{name}.crt'])
        (pki / 'ca.key').unlink()
        for path in pki.iterdir():
            path.chmod(0o644)
        # Browser trust tooling is prepared before joining internal runtime networks.
        # No package is installed on the host, and no production image is built/changed.
        tooling = d / 'tooling'
        tooling.mkdir()
        command(['docker', 'run', '--rm', '--label', f'vgp.production-test={self.project}', '-v', f'{tooling}:/tooling', PLAYWRIGHT, 'bash', '-ec',
                 f'apt-get update -qq >/dev/null; cd /tooling; apt-get download libnss3-tools=2:3.98-1ubuntu0.3 >/dev/null 2>&1; printf \"0bb7919188cb21d3bfba5d45b5aa45848aa61b92ced34ff3f8ecda7b92d788c0  %s\\n\" /tooling/*.deb | sha256sum -c - >/dev/null; dpkg-deb -x ./*.deb /tooling/nss; chown -R {os.getuid()}:{os.getgid()} /tooling'])
        app = self.selected['images']['application']
        command(['docker', 'run', '--rm', '--label', f'vgp.production-test={self.project}', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}', '-v', f'{pki}:/run/pki', '--entrypoint', 'keytool', app,
                 '-importcert', '-noprompt', '-alias', 'runtime-ca', '-file', '/run/pki/ca.crt', '-keystore', '/run/pki/truststore.p12', '-storetype', 'PKCS12', '-storepass', 'disposable'])
        smtp_user = (secret_dir / 'keycloak-smtp-username').read_text()
        smtp_password = (secret_dir / 'keycloak-smtp-password').read_text()
        (d / 'smtp-auth').write_text(smtp_user + ':' + smtp_password + '\n')
        (d / 'smtp-auth').chmod(0o644)
        caddy = (ROOT / 'deploy/public-production/caddy/Caddyfile').read_text()
        for host in ['gameometro.com', 'auth.gameometro.com']:
            caddy = re.sub(r'^' + re.escape(host) + r' \{', host + ' {\n\ttls /run/pki/edge.crt /run/pki/edge.key', caddy, flags=re.M)
        (d / 'Caddyfile').write_text(caddy)
        env = {
            'PRODUCTION_SECRETS_DIR': str(secret_dir), 'PRODUCTION_SECRETS_GID': str(os.getgid()),
            'KEYCLOAK_ADMIN_USERNAME': 'disposable-admin', 'PRODUCTION_SMTP_RELAY_HOST': 'mail.gameometro.test',
            'PRODUCTION_SMTP_RELAY_PORT': '587', 'PRODUCTION_SMTP_RELAY_FROM': 'no-reply@gameometro.com',
            'CATALOGUE_TRANSLATION_MODEL_DIR': str(self.model or d / 'model-unavailable'),
            'APPLICATION_VERSION': self.selected['applicationVersion'],
            **{name.upper().replace('-', '_') + '_IMAGE': image for name, image in self.selected['images'].items()},
        }
        env_file = d / 'runtime.env'
        env_file.write_text(''.join(f'{k}={v}\n' for k, v in env.items()))
        # Ignore ambient Compose interpolation, including production environment variables.
        clean = {k: v for k, v in os.environ.items() if k not in env and not k.startswith('COMPOSE_')}
        result = subprocess.run(['docker', 'compose', '--env-file', str(env_file), '-f', str(COMPOSE), '--profile', '*', 'config', '--format', 'json'], env=clean, text=True, capture_output=True, check=True)
        spec = importlib.util.spec_from_file_location('runtime_contract', ROOT / 'scripts/test-public-production-runtime.py')
        contract = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(contract)
        base = json.loads(result.stdout)
        violations = contract.compose_violations(base, str(secret_dir)) + contract.wrapper_violations()
        require(not violations, 'Production configuration contract failed: ' + '; '.join(violations))
        config = isolated_config(base, self.project, d)
        self.file.write_text(json.dumps(config))
        loader = importlib.machinery.SourceFileLoader('production_integration_recovery', str(ROOT / 'deploy/public-production/bin/recovery'))
        recovery_spec = importlib.util.spec_from_loader(loader.name, loader)
        recovery = importlib.util.module_from_spec(recovery_spec)
        loader.exec_module(recovery)
        state = d / 'recovery-journal'
        state.mkdir(mode=0o700)
        runtime = recovery.Runtime(self.file, self.project, state)
        runtime.initialize()
        self.recovery_runtime, self.recovery_core = runtime, recovery
        self.receipt['sourceFiles'] = {
            str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in [COMPOSE, ROOT / 'deploy/public-production/caddy/Caddyfile',
                         ROOT / 'deploy/public-production/bin/run-postgres',
                         ROOT / 'deploy/public-production/bin/run-keycloak',
                         ROOT / 'scripts/public_production_integration.py',
                         ROOT / 'scripts/run-production-runtime-browser.sh',
                         ROOT / 'frontend/tests/production-identity.spec.ts']}
        self.receipt['configurationSha256'] = hashlib.sha256(self.file.read_bytes()).hexdigest()
        # Fixed addresses are the trust contract; refuse overlap instead of moving a running network.
        networks = json.loads(command(['docker', 'network', 'ls', '--format', 'json']).stdout.replace('\n', ',').rstrip(',').join(['[', ']']))
        import ipaddress
        for network in networks:
            details = json.loads(command(['docker', 'network', 'inspect', network['ID']]).stdout)[0]
            for ipam in (details.get('IPAM', {}).get('Config') or []):
                subnet = ipam.get('Subnet')
                if subnet and ':' not in subnet:
                    for reserved in ['10.203.1.0/28', '10.203.2.0/28']:
                        require(not ipaddress.ip_network(subnet).overlaps(ipaddress.ip_network(reserved)), 'Production trust subnet already in use; refusing to touch existing networks.')

    @contextlib.contextmanager
    def deployment_lease(self):
        try:
            with self.recovery_runtime.lock():
                require(self.recovery_runtime.gate('check') == b'open', 'Recovery authorization is closed; deployment refused.')
                yield
        except self.recovery_core.Refused:
            raise trust.Refused('Recovery lease or authorization unavailable; deployment refused.') from None

    def inspect_image(self, image):
        return command(['docker', 'image', 'inspect', image])

    def inspect(self, cid):
        return command(['docker', 'inspect', cid])

    def cid(self, service):
        return self.compose('ps', '-aq', service).stdout.strip()

    def sql(self, query, database='videogame_platform'):
        return self.compose('exec', '-T', 'postgres', 'gosu', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-At', '-d', database, data=query).stdout.strip()

    def monitor(self):
        while not self.stop.is_set():
            try:
                ids = self.compose('ps', '-aq', check=False, timeout=5).stdout.split()
                if ids:
                    details = json.loads(command(['docker', 'inspect', *ids], timeout=5).stdout)
                    stats = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids], check=False, timeout=5)
                    current = {}
                    for line in stats.stdout.splitlines():
                        value = json.loads(line)
                        current[value['Name']] = value['MemUsage'].split(' / ')[0]
                    for item in details:
                        service = item['Config']['Labels']['com.docker.compose.service']
                        record = self.samples.setdefault(service, {'peakBytes': None, 'steadyBytes': None, 'samples': 0, 'restarts': 0, 'oomKilled': False, 'limitBytes': item['HostConfig']['Memory']})
                        record['restarts'] = max(record['restarts'], item['RestartCount'])
                        record['oomKilled'] |= item['State']['OOMKilled']
                        text = current.get(item['Name'].lstrip('/'))
                        if text and item['State']['Running']:
                            match = re.fullmatch(r'([\d.]+)([KMGT]?i?B)', text)
                            units = {'B': 1, 'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3, 'kB': 1000, 'MB': 1000**2, 'GB': 1000**3}
                            size = int(float(match[1]) * units[match[2]])
                            record['peakBytes'] = max(record['peakBytes'] or 0, size)
                            record['steadyBytes'] = size
                            record['samples'] += 1
            except Exception:
                self.receipt['resourceSamplingFailure'] = True
            self.stop.wait(2)

    def up(self, *services, no_deps=False):
        try:
            self.compose('up', '-d', '--no-build', *(['--no-deps'] if no_deps else []), '--wait', '--wait-timeout', '300', *services)
        except trust.Refused as error:
            failures = self.receipt.setdefault('operationalFailures', [])
            for service in services:
                logs = self.compose('logs', '--no-color', '--tail', '60', service, check=False).stdout
                signatures = [marker for marker in ('Read-only file system', 'Permission denied', 'not readable', 'Failed to start', 'OutOfMemoryError') if marker in logs]
                failures.append({'service': service, 'signatures': signatures})
            raise

    def stage_a(self):
        self.phase = 'A-postgres-migration'
        self.up('postgres')
        require(self.sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('videogame_app','videogame_app_migrator','videogame_keycloak') AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole;") == '3', 'Database bootstrap privileges mismatch.')
        require(self.sql("SELECT has_database_privilege('videogame_app','videogame_keycloak','CONNECT') OR has_database_privilege('videogame_keycloak','videogame_platform','CONNECT');") == 'f', 'Database roles cross isolation boundary.')
        # Authenticate through TCP using the mounted files, without putting values in argv.
        for role, database, file in [('videogame_app', 'videogame_platform', 'application_db_password'),
                                     ('videogame_app_migrator', 'videogame_platform', 'application_migration_db_password'),
                                     ('videogame_keycloak', 'videogame_keycloak', 'keycloak_db_password')]:
            self.compose('exec', '-T', 'postgres', 'bash', '-ec',
                         f'export PGPASSWORD="$(cat /run/secrets/{file})"; exec psql -X -h 127.0.0.1 -U {role} -d {database} -Atc "SELECT 1"')
        for role, database, file in [('videogame_app', 'videogame_keycloak', 'application_db_password'),
                                     ('videogame_keycloak', 'videogame_platform', 'keycloak_db_password')]:
            denied = self.compose('exec', '-T', 'postgres', 'bash', '-ec',
                                  f'export PGPASSWORD="$(cat /run/secrets/{file})"; exec psql -X -h 127.0.0.1 -U {role} -d {database} -Atc "SELECT 1"', check=False)
            require(denied.returncode != 0 and 'permission denied' in denied.stderr, 'Database cross-role connection was not denied.')
        # Real wrappers must refuse an empty required secret before reaching the DB.
        empty = self.directory / 'empty'
        empty.write_text('')
        empty.chmod(0o644)
        refused = self.compose('run', '--rm', '--no-deps', '-v', f'{empty}:/run/secrets/application_migration_db_password:ro', 'migration', check=False)
        require(refused.returncode != 0 and 'empty' in refused.stderr, 'Migration failed to refuse empty secret.')
        missing = self.compose('run', '--rm', '--no-deps', '-e', 'APPLICATION_MIGRATION_DB_PASSWORD_FILE=/run/secrets/missing', 'migration', check=False)
        require(missing.returncode != 0 and 'not readable' in missing.stderr, 'Migration failed to refuse missing secret.')
        self.compose('run', '--name', self.project + '-migration', '--no-deps', 'migration')
        require(self.sql('SELECT bool_and(success) FROM flyway_schema_history;') == 't', 'Flyway migration failed.')
        self.receipt['migrationVersion'] = self.sql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;')
        require(not self.cid('application'), 'Application started before migration.')
        require(self.sql("SELECT has_schema_privilege('videogame_app','catalogue','CREATE');") == 'f', 'Runtime role has DDL permission.')
        self.receipt['stages'].append('A')

    def probe(self, *args, data=None, check=True, network='test-client', environment=None):
        env_args = [arg for key, value in (environment or {}).items() for arg in ['-e', f'{key}={value}']]
        files = ['scripts/check-oidc-issuer-contract.py', 'scripts/run-production-runtime-browser.sh',
                 'frontend/package.json', 'frontend/playwright.config.ts', 'frontend/tests/production-identity.spec.ts', 'node_modules']
        mounts = [arg for path in files for arg in ['-v', f'{ROOT / path}:/work/{path}:ro']]
        result = command(['docker', 'run', '--rm', '--label', f'vgp.production-test={self.project}', '--group-add', str(os.getgid()), '-i', '--network', self.project + '-' + network,
                        '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '512m', '--pids-limit', '128',
                        '-v', f'{self.directory}/browser:/run/browser', '-v', f'{self.directory}/pki:/run/pki:ro', '-v', f'{self.directory}/tooling:/run/tooling:ro', *mounts, *env_args, PLAYWRIGHT, *args], data=data, check=False)
        if check and result.returncode:
            locations = sorted(set(re.findall(r'production-identity\.spec\.ts:\d+', result.stdout + result.stderr)))
            self.receipt['failedProbe'] = {'phase': self.phase, 'locations': locations,
                                          'exitCode': result.returncode,
                                          'timeoutBudgetsMs': sorted(set(re.findall(r'Test timeout of (\d+)ms', result.stdout + result.stderr)))}
            summary = self.directory / 'browser/cookie-inventory.json.failure.json'
            if summary.exists():
                self.receipt['failedProbe']['browser'] = json.loads(summary.read_text())
            inventory = self.directory / 'browser/cookie-inventory.json'
            if inventory.exists():
                data = json.loads(inventory.read_text())
                self.receipt['failedProbe']['rateRefusals'] = data.get('rateRefusals', [])
                self.receipt['failedProbe']['identityActionRequests'] = data.get('identityActionRequests')
            raise trust.Refused('Integration probe failed; raw evidence withheld.')
        return result

    def stage_b(self):
        self.phase = 'B-canonical-tls-oidc'
        self.up('mailpit', 'keycloak', 'caddy')
        self.deployer = deployment.Deployment(self, self.api, self.fingerprint, self.schema)
        self.deployer.run()
        env = json.loads(self.file.read_text())['services']['application']['environment']
        env = {k: v for k, v in env.items() if k.startswith('OIDC_') or k.startswith('APPLICATION_OIDC_')}
        self.probe('python3', '/work/scripts/check-oidc-issuer-contract.py', environment={**env, 'OIDC_CONTRACT_CA_FILE': '/run/pki/ca.crt'})
        # Negative Host, forwarded and forbidden routes exercise the actual upstreams.
        self.probe('python3', '-', data='''import http.client, ssl, json
ctx=ssl.create_default_context(cafile='/run/pki/ca.crt')
try:
 c=http.client.HTTPSConnection('gameometro.com',context=ssl.create_default_context(),timeout=10); c.request('GET','/')
except ssl.SSLCertVerificationError:
 pass
else:
 raise AssertionError('untrusted test CA was accepted')
def get(host,path,headers={}):
 c=http.client.HTTPSConnection(host,context=ctx,timeout=10); c.request('GET',path,headers=headers); r=c.getresponse(); b=r.read(); c.close(); return r.status,dict(r.headers),b
for host,paths in [('gameometro.com',['/actuator','/actuator/health','/actuator/sessionrevocation']),('auth.gameometro.com',['/admin/','/admin/realms/gameometro/users','/realms/master/.well-known/openid-configuration','/metrics','/health/ready'])]:
 for path in paths: assert get(host,path)[0]==404,(host,path)
assert get('gameometro.com','/',{'Host':'attacker.invalid'})[0]==421
spoof={'Forwarded':'for=203.0.113.9;proto=http;host=attacker.invalid','X-Forwarded-Host':'attacker.invalid','X-Forwarded-Proto':'http','X-Forwarded-For':'203.0.113.9','X-Forwarded-Port':'8443'}
status,headers,body=get('gameometro.com','/auth/login/keycloak',spoof)
assert status in (302,303) and headers['Location'].startswith('https://auth.gameometro.com/'),status
assert 'attacker.invalid' not in headers['Location']
status,_,body=get('auth.gameometro.com','/realms/gameometro/.well-known/openid-configuration',spoof)
assert status==200 and json.loads(body)['issuer']=='https://auth.gameometro.com/realms/gameometro'
''')
        self.receipt['stages'].append('B')

    def stage_c(self):
        self.phase = 'C-browser-security'
        # Reuse the bounded fixture that existing identity journeys depend on. Only synthetic
        # records, loaded after production-only Flyway has succeeded, never dev Flyway paths.
        fixture = ROOT / 'backend/src/main/resources/db/dev-seed/V20260809_130000__seed_bounded_prototype_catalogue.sql'
        self.sql(fixture.read_text())
        runner = ROOT / 'scripts/run-production-runtime-browser.sh'
        self.probe('bash', '/work/scripts/run-production-runtime-browser.sh', environment={
            'PLAYWRIGHT_BASE_URL': 'https://gameometro.com', 'PRODUCTION_IDENTITY_ORIGIN': 'https://auth.gameometro.com',
            'MAILPIT_API_ORIGIN': 'http://mailpit:8025', 'PRODUCTION_RUNTIME_TRUSTED_TLS': 'true',
            'COOKIE_INVENTORY_FILE': '/run/browser/cookie-inventory.json', 'SENSITIVE_VALUES_FILE': '/run/browser/sensitive-values.txt',
        })
        require(runner.is_file(), 'Browser runner missing.')
        inventory = json.loads((self.directory / 'browser/cookie-inventory.json').read_text())
        (self.evidence / 'cookie-inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
        self.receipt['stages'].append('C')

    def stage_d(self):
        self.phase = 'D-localizer-resources'
        if self.model:
            self.up('catalogue-localizer')
            result = self.probe('python3', '-', network='application-data', data='''import json,urllib.request
assert json.load(urllib.request.urlopen('http://catalogue-localizer:8092/ready',timeout=10))['ready']
r=urllib.request.Request('http://catalogue-localizer:8092/translate',data=json.dumps({'text':'Explore a mysterious world and discover hidden treasures.'}).encode(),headers={'Content-Type':'application/json'})
v=json.load(urllib.request.urlopen(r,timeout=120))
assert v['text'].strip() and v['text']!='Explore a mysterious world and discover hidden treasures.'
assert v['revision'].startswith('opus-tcbig:')
assert 'mundo' in v['text'].lower() and 'tesoro' in v['text'].lower()
print(json.dumps(v))
''')
            sample = json.loads(result.stdout)
            require(sample['revision'] == (self.model / 'revision.txt').read_text().strip(), 'Localizer served a different model revision.')
            self.receipt['localizer'] = {'outcome': 'passed', 'sample': sample}
        # Defined idle window after the identity workload, not a VPS capacity test.
        self.stop.wait(20)
        require(not self.receipt.get('resourceSamplingFailure'), 'Resource sampling failed.')
        for name in ['postgres', 'keycloak', 'caddy', 'application'] + (['catalogue-localizer'] if self.model else []):
            record = self.samples.get(name, {})
            require(record.get('samples', 0) > 0 and not record.get('oomKilled') and not record.get('restarts'), f'{name}: missing measurements, restart or OOM.')
        self.receipt['stages'].append('D')

    def stop_application(self):
        cid = self.cid('application')
        since = int(time.time())
        try:
            self.compose('stop', '--timeout', '30', 'application')
        finally:
            if cid:
                self.expected_stops.append((cid, since, int(time.time())))

    def expected_exit(self, event):
        return (event.get('Action') == 'die'
                and event['Actor']['Attributes'].get('com.docker.compose.service') == 'application'
                and event['Actor']['Attributes'].get('exitCode') in ('143', '137')
                and any(event['Actor'].get('ID') == cid and since <= event.get('time', 0) <= until
                        for cid, since, until in self.expected_stops))

    def stage_e(self):
        self.phase = 'E-deployment-transitions'
        # Keep a synthetic row in the existing product database across attempts.
        # No new user account or permanent schema is needed.
        sentinel = self.sql('SELECT game_id FROM catalogue.game ORDER BY game_id LIMIT 1;')
        require(re.fullmatch(r'[0-9a-f-]{36}', sentinel or ''), 'Synthetic catalogue state is missing.')
        query = f"SELECT md5(row_to_json(g)::text) FROM catalogue.game g WHERE game_id = '{sentinel}'::uuid;"
        before = self.sql(query)
        require(bool(before), 'Synthetic catalogue state is missing.')
        receipts = []
        for fault in (None, 'migration', None, 'readiness', None, 'smoke', None, None):
            previous = self.cid('application')
            try:
                result = self.deployer.run(fault=fault)
                require(fault is None and result['outcome'] == 'success', 'Injected deployment fault was not detected.')
            except trust.Refused:
                require(fault is not None, 'Normal deployment transition failed.')
                state = json.loads(self.deployer.state.read_text())
                require(state['outcome'] == 'failure' and state['phase'] == fault, 'Wrong failure transition.')
                if fault == 'migration':
                    require(self.cid('application') == previous, 'Migration failure replaced the application.')
                info = json.loads(self.inspect(self.cid('application')).stdout)[0]
                require(not info['State']['Running'], 'Failed candidate remains running.')
                receipts.append(state['failureReason'])
            require(self.sql(query) == before,
                    'Deployment attempt changed synthetic product state.')
        self.receipt['deploymentTransitions'] = {'outcome': 'passed', 'attempts': 8,
                                                'detectedFailures': receipts, 'syntheticStatePreserved': True}
        self.receipt['stages'].append('E')

    def cleanup(self):
        self.stop.set()
        if self.thread:
            self.thread.join(timeout=20)
        helpers = command(['docker', 'ps', '-aq', '--filter', f'label=vgp.production-test={self.project}']).stdout.split()
        if helpers:
            command(['docker', 'rm', '-f', *helpers])
        if self.file.exists():
            until = str(int(time.time()))
            events = command(['docker', 'events', '--since', str(self.started_at), '--until', until,
                              '--filter', 'type=container', '--filter', f'label=com.docker.compose.project={self.project}',
                              '--format', '{{json .}}'], check=False)
            self.receipt['runtimeEvents'] = []
            if events.returncode:
                self.receipt['eventObservation'] = 'failed'
            else:
                self.receipt['eventObservation'] = 'passed'
                for line in events.stdout.splitlines():
                    event = json.loads(line)
                    if event.get('Action') in ('oom', 'restart', 'die'):
                        attributes = event['Actor']['Attributes']
                        self.receipt['runtimeEvents'].append({'service': attributes.get('com.docker.compose.service'),
                                                             'action': event['Action'], 'exitCode': attributes.get('exitCode'),
                                                             'expectedOperatorStop': self.expected_exit(event)})
            self.compose('down', '--volumes', '--remove-orphans', '--timeout', '15')
            remaining = command(['docker', 'ps', '-aq', '--filter', f'label=com.docker.compose.project={self.project}']).stdout.strip()
            networks = command(['docker', 'network', 'ls', '-q', '--filter', f'label=com.docker.compose.project={self.project}']).stdout.strip()
            volumes = command(['docker', 'volume', 'ls', '-q', '--filter', f'label=com.docker.compose.project={self.project}']).stdout.strip()
            require(not remaining and not networks and not volumes, 'Disposable resources remain after teardown.')
        if self.directory.exists():
            command(['docker', 'run', '--rm', '--network', 'none', '-v', f'{self.directory}:/scratch',
                     PLAYWRIGHT, 'chown', '-R', f'{os.getuid()}:{os.getgid()}', '/scratch'])
        self.receipt['durationSeconds'] = int(time.time()) - self.started_at
        self.receipt['teardown'] = 'passed'
        bad = [event for event in self.receipt.get('runtimeEvents', [])
               if event['service'] in ('postgres', 'keycloak', 'application', 'caddy', 'catalogue-localizer')
               and (event['action'] in ('oom', 'restart') or event['action'] == 'die' and event['exitCode'] != '0'
                    and not event.get('expectedOperatorStop', False))]
        require(not bad, 'Runtime service exited, restarted or was OOM-killed during the exercise.')
        require(self.receipt.get('eventObservation', 'passed') == 'passed', 'Runtime event observation failed.')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True, help='Full trusted-main source SHA; never inferred from tags')
    parser.add_argument('--model-dir', type=Path, help='Existing approved checksum-verified tcbig directory; never downloaded')
    parser.add_argument('--evidence', required=True, type=Path, help='New output directory, sanitized receipts only')
    parser.add_argument('--stage', choices=['A', 'B', 'C', 'D', 'E'], default='E', help='Last stage to exercise')
    parser.add_argument('--operations-only', action='store_true',
                        help='Omit browser tests explicitly; this is never complete candidate acceptance')
    parser.add_argument('--digest', help='Expected immutable application digest; no fallback')
    parser.add_argument('--configuration-fingerprint', help='Owner-reviewed runtime source fingerprint')
    args = parser.parse_args(argv)
    args.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    receipt = {'outcome': 'failure', 'phase': 'publication-resolution', 'productionReady': False}
    exercise = None
    try:
        token = command(['gh', 'auth', 'token']).stdout.strip()
        selected = resolve(trust.GitHub(token), args.revision)
        receipt['provenance'] = selected
        if args.digest:
            require(selected['image_digest'] == args.digest, 'Selected digest differs from trusted publication.')
        receipt['phase'] = 'configuration-preflight'
        fingerprint = args.configuration_fingerprint or deployment.local_configuration()
        api = trust.GitHub(token)
        schema = deployment.verify_configuration(api, args.revision, fingerprint)
        receipt['runtimeConfigurationFingerprint'] = fingerprint
        verify_local_docker()
        receipt['phase'] = 'image-preflight'
        print('Trusted-main publications verified; pulling immutable images.', flush=True)
        # Check every required image before any runtime resources are created.
        for name, image in selected['images'].items():
            result = command(['docker', 'pull', image], check=False)
            require(result.returncode == 0, f'{name}: immutable GHCR image unavailable; authenticate Docker with read-only package access. No fallback permitted.')
            info = json.loads(command(['docker', 'image', 'inspect', image]).stdout)[0]
            require(info['Os'] == 'linux' and info['Architecture'] == 'amd64', f'{name}: unsupported platform.')
            labels = info['Config'].get('Labels') or {}
            require(labels.get('org.opencontainers.image.revision') == args.revision, f'{name}: OCI source revision mismatch.')
            if name == 'application':
                version = labels.get('org.opencontainers.image.version', '')
                require(re.fullmatch(r'[0-9A-Za-z.-]{1,100}', version), 'Invalid application image version.')
                selected['applicationVersion'] = version
        model = verify_model(args.model_dir) if args.model_dir else None
        with tempfile.TemporaryDirectory(prefix='vgp-production-test-') as temporary:
            exercise = Exercise(Path(temporary), args.evidence, selected, model)
            exercise.api, exercise.fingerprint, exercise.schema = api, fingerprint, schema
            receipt = exercise.receipt
            receipt['runtimeConfigurationFingerprint'] = fingerprint
            receipt['requestedStage'] = args.stage
            try:
                exercise.prepare()
                exercise.thread = threading.Thread(target=exercise.monitor, daemon=True)
                exercise.thread.start()
                for stage in 'ABCDE'[:'ABCDE'.index(args.stage) + 1]:
                    if stage == 'C' and args.operations_only:
                        # Deployment state-preservation checks need the existing bounded fixture.
                        fixture = ROOT / 'backend/src/main/resources/db/dev-seed/V20260809_130000__seed_bounded_prototype_catalogue.sql'
                        exercise.sql(fixture.read_text())
                        receipt['browser'] = 'not-exercised-operations-only'
                        continue
                    print(f'Running stage {stage}.', flush=True)
                    getattr(exercise, 'stage_' + stage.lower())()
                receipt['outcome'] = 'passed'
            except BaseException:
                receipt['outcome'] = 'failure'
                raise
            finally:
                receipt['phase'] = exercise.phase
                exercise.cleanup()
    except (trust.Refused, OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        receipt['outcome'] = 'failure'
        # Only our controlled refusals are safe to persist; libraries may echo raw input.
        receipt['reason'] = str(error) if isinstance(error, trust.Refused) else type(error).__name__
        print('Exercise refused/failed: ' + receipt['reason'], file=sys.stderr)
    finally:
        (args.evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return 0 if receipt['outcome'] == 'passed' else 1


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    sys.exit(main())
