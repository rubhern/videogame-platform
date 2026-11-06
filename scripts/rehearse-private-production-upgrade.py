#!/usr/bin/env python3
"""Disposable real-service proof of the in-place upgrade; never a deployment or VPS qualification.

Default mode installs the signed candidate's real images in an isolated project with synthetic
accounts and data, takes a real paired backup, then drives private_production_upgrade through its
refusals, real SIGKILL and SIGTERM of a forked controller, attempted manual starts under the fence,
resume and completion against real PostgreSQL, Flyway, Keycloak, edge, application and monitoring
containers. No translation model is supplied, so the optional localizer really cannot become healthy:
that exercises the optional-service path, not localization. Daemon and host restart semantics are
owned by scripts/test-private-production-restart-docker.py.

--restore-check restores one real encrypted pair into a throwaway isolated PostgreSQL with synthetic
role passwords and writes the isolated-restore receipt the upgrade requires. It reads no production
secret and needs only the owner's decryption keyring, on the owner's machine.
"""
import sys
sys.dont_write_bytecode = True

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import traceback
import urllib.parse
import uuid

import install_private_production as install
import prepare_private_production as prepare
import private_dev_promotion as trust
import private_production_acceptance as acceptance
import private_production_control as control
import private_production_upgrade as upgrade
import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
CAMPAIGN = ROOT / 'docs/research/public-production-candidate-evidence/final-9ffc05a-campaign.json'
SEED = ROOT / 'backend/src/main/resources/db/dev-seed/V20260809_130000__seed_bounded_prototype_catalogue.sql'
ISSUER = 'https://auth.gameometro.com/realms/gameometro'
# node-exporter needs an rslave mount of the host root, which Docker Desktop cannot provide.
INSTALLED = ('keycloak', 'caddy', 'telemetry', 'prometheus', 'grafana', 'blackbox', 'application')
SCOPE = ('postgres', *INSTALLED, 'catalogue-localizer')
OWNER = "import json;open('/gate/INSTALLATION-OWNER.json','w').write(json.dumps({'runId':__import__('sys').argv[1],'configuration':__import__('sys').argv[2],'status':'released'}))"
HTTP = ("import sys,json,urllib.request,urllib.error,base64\nq=json.load(sys.stdin)\n"
        "r=urllib.request.Request(q['url'],data=None if q['data'] is None else base64.b64decode(q['data']),headers=q['headers'],method=q['method'])\n"
        "try:\n response=urllib.request.build_opener(urllib.request.ProxyHandler({})).open(r,timeout=20)\n"
        "except urllib.error.HTTPError as e:\n response=e\n"
        "print(json.dumps({'code':response.code,'body':base64.b64encode(response.read()).decode()}))\n")
# The edge reads the gate itself: behind a closed gate both hosts are maintenance, whatever is upstream.
EDGE_CLOSED = r'''
import http.client,ssl,time
ctx=ssl.create_default_context(cafile='/run/private-tls/ca.crt')
def status(host):
 c=http.client.HTTPSConnection(host,context=ctx,timeout=10)
 c.request('GET','/');s=c.getresponse().status;c.close()
 return s
for attempt in range(30):
 try:
  assert status('gameometro.com')==503 and status('auth.gameometro.com')==503
  break
 except (OSError,http.client.HTTPException):
  time.sleep(1)
else:
 raise SystemExit(1)
'''
require = trust.require


def shell(args, *, data=None, check=True, timeout=600):
    result = subprocess.run([str(a) for a in args], input=data, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'{[str(a) for a in args][:6]} failed: {result.stderr.decode(errors="replace")[-600:]}')
    return result


def isolate(config, project):
    """Only names and egress: isolated resources, the same services, mounts, limits and policy."""
    config['name'] = project
    for name, value in config['networks'].items():
        value['name'] = f'{project}-{name}'
        value['internal'] = True
    for name, value in config['volumes'].items():
        value['name'] = f'{project}-{name}'
    return config


def synthetic_secrets(directory):
    directory.mkdir(mode=0o750)
    for name in ('postgres-admin-password', 'application-db-password', 'application-migration-db-password',
                 'keycloak-db-password', 'keycloak-admin-password', 'keycloak-bff-client-secret',
                 'keycloak-smtp-username', 'keycloak-smtp-password', 'igdb-client-id', 'igdb-client-secret',
                 'grafana-admin-password', 'grafana-database-password', 'grafana-telegram-bot-token',
                 'grafana-telegram-chat-id'):
        path = directory / name
        path.write_text('' if name.startswith(('igdb-', 'grafana-telegram-')) else secrets.token_hex(24))
        path.chmod(0o640)


def synthetic_tls(tls, image):
    tls.mkdir(mode=0o755)
    shell(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=Disposable upgrade CA',
           '-keyout', tls / 'ca.key', '-out', tls / 'ca.crt'])
    shell(['openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=Disposable upgrade edge',
           '-keyout', tls / 'edge.key', '-out', tls / 'edge.csr'])
    (tls / 'extensions').write_text('subjectAltName=DNS:gameometro.com,DNS:auth.gameometro.com\nextendedKeyUsage=serverAuth\n')
    shell(['openssl', 'x509', '-req', '-in', tls / 'edge.csr', '-CA', tls / 'ca.crt', '-CAkey', tls / 'ca.key', '-CAcreateserial',
           '-days', '1', '-extfile', tls / 'extensions', '-out', tls / 'edge.crt'])
    (tls / 'ca.key').unlink()
    # The installed store holds the old JVM's roots plus the private CA, as the installer requires.
    shell(['docker', 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}', '-v', f'{tls}:/t', '--entrypoint', 'sh', image, '-ec',
           'keytool -importkeystore -noprompt -srckeystore "$JAVA_HOME/lib/security/cacerts" -srcstorepass changeit '
           '-destkeystore /t/truststore.p12 -deststoretype PKCS12 -deststorepass changeit >/dev/null 2>&1; '
           'keytool -importcert -noprompt -alias private-ca -file /t/ca.crt -keystore /t/truststore.p12 -storepass changeit >/dev/null 2>&1'])
    for path in tls.iterdir():
        path.chmod(0o644)


class Admin:
    """A disposable client on the private identity network; no port is published."""

    def __init__(self, network, project, username, password):
        self.network, self.project = network, project
        self.token = None
        self.token = json.loads(self.call('POST', '/realms/master/protocol/openid-connect/token', form={
            'client_id': 'admin-cli', 'grant_type': 'password', 'username': username, 'password': password}))['access_token']

    def request(self, method, path, body=None, form=None):
        headers = {'Authorization': 'Bearer ' + self.token} if self.token else {}
        data = None
        if form is not None:
            data, headers['Content-Type'] = urllib.parse.urlencode(form).encode(), 'application/x-www-form-urlencoded'
        elif body is not None:
            data, headers['Content-Type'] = json.dumps(body).encode(), 'application/json'
        query = {'url': 'http://keycloak:8080' + path, 'method': method, 'headers': headers,
                 'data': None if data is None else base64.b64encode(data).decode()}
        answer = json.loads(shell(['docker', 'run', '--rm', '-i', '--network', self.network, '--read-only', '--cap-drop', 'ALL',
                                   '--label', f'vgp.production-test={self.project}', install.HELPER, 'python3', '-c', HTTP],
                                  data=json.dumps(query).encode()).stdout)
        return answer['code'], base64.b64decode(answer['body'])

    def call(self, method, path, body=None, form=None):
        code, payload = self.request(method, path, body, form)
        require(code < 300, f'Keycloak refused {method} {path}: {code}')
        return payload


def login_status(admin, username, password):
    """A real password grant on the rehearsal's own client: the stored credential still authenticates."""
    saved, admin.token = admin.token, None
    try:
        return admin.request('POST', '/realms/gameometro/protocol/openid-connect/token', form={
            'grant_type': 'password', 'client_id': 'synthetic-upgrade-login', 'username': username, 'password': password,
            'scope': 'openid'})[0]
    finally:
        admin.token = saved


class Rehearsal(upgrade.Upgrade):
    fault = None
    kill = None  # (phase, delay seconds): a real SIGKILL of this controller
    terminate = None  # phase: a real SIGTERM, which the tool must contain
    before_kill = None

    def verify_operational(self, api):
        """The unpublished working tree cannot pass; a real run must, and the receipt says so."""

    def verify_claimed_operator(self, record):
        """As above."""

    def host_checks(self):
        info = json.loads(self.run(['docker', 'info', '--format', '{{json .}}']))
        return control.versions(info['ServerVersion'], self.run(['docker', 'compose', 'version', '--short']).decode().strip())

    def scope(self):
        return list(SCOPE)

    def storage_paths(self):
        return (self.admin,)  # Docker Desktop keeps its data root inside its virtual machine.

    def localize(self, config):
        return isolate(config, prepare.PROJECT)

    def verify_inputs(self, host):
        host.verify_truststore(install.TLS / 'truststore.p12')

    def start(self, host, *names, timeout=600):
        # Without a model the localizer fails its readiness; only the wait is shorter here.
        super().start(host, *names, timeout=120 if 'catalogue-localizer' in names else timeout)

    def phase_reached(self, phase):
        if phase == self.fault:
            type(self).fault = None
            raise trust.Refused('Injected rehearsal interruption.')
        if self.terminate == phase:
            control.signal_handlers()
            os.kill(os.getpid(), signal.SIGTERM)
        if self.kill and self.kill[0] == phase:
            if self.before_kill:
                self.before_kill()
            threading.Timer(self.kill[1], os.kill, (os.getpid(), signal.SIGKILL)).start()
            if not self.kill[1]:
                time.sleep(60)


class Stack:
    def __init__(self, work, target, evidence):
        self.work, self.target, self.evidence = work, target, evidence
        self.project = 'vgp-production-test-' + secrets.token_hex(6)
        self.admin = work / 'admin'
        self.bundles = work / 'installations'
        self.installed = self.bundles / f'{prepare.REVISION[:7]}-private'
        self.run_id = str(uuid.uuid4())
        self.report = {'kind': 'private-upgrade-rehearsal', 'outcome': 'failure', 'productionReady': False,
                       'scope': 'isolated-local-synthetic-only', 'project': self.project, 'target': target,
                       'operationalIdentity': 'not-applicable-unpublished-working-tree',
                       'notExercised': ['localization with a real model (the localizer cannot become healthy here, by design)',
                                        'node-exporter (Docker Desktop has no shared root mount)',
                                        'a real off-host copy (simulated receipt)', 'operational publication (unpublished working tree)',
                                        'the installer input contract against real protected paths (contract test only)',
                                        'daemon or host restart of this full stack (see the restart-semantics harness)',
                                        'catalogue-scale data, real accounts and real VPS Docker'],
                       'steps': {}}

    def patch(self):
        prepare.PROJECT, prepare.TARGET = self.project, self.installed
        install.TLS, upgrade.BUNDLES, upgrade.ADMIN = self.work / 'tls', self.bundles, self.admin
        install.ADMIN = self.admin
        install.secure = lambda *_, **__: os.stat(_[0])
        # Docker Desktop reports directory binds through its own host share. Only the rehearsal
        # accepts that spelling; the target's rootful Debian daemon keeps the exact comparison.
        install.Host.same_bind = staticmethod(lambda actual, intended: actual == intended or actual.startswith('/run/desktop/mnt/host/'))
        render = prepare.render_base
        prepare.render_base = lambda runtime, source: {**render(runtime, source), 'name': self.project}

    def step(self, name, value=True):
        self.report['steps'][name] = value
        print(f'  {name}: {json.dumps(value)[:160]}', flush=True)

    def upgrader(self, forward_of=None):
        return Rehearsal(self.target, repository=ROOT, admin=self.admin, bundles=self.bundles, forward_of=forward_of).load()

    def compose(self, file, *args, **kwargs):
        return shell(['docker', 'compose', '-p', self.project, '-f', file, '--profile', '*', *args], **kwargs)

    def recovery_cli(self, command, *args, check=True):
        source = self.installed / 'candidate'
        receipt = self.work / 'receipts' / f'{command}-{secrets.token_hex(4)}.json'
        result = shell(['python3', '-I', source / 'deploy/public-production/bin/recovery', command,
                        '--config', self.admin / acceptance.ACCEPTED, '--project', self.project,
                        '--state', self.admin / 'first-install/recovery', '--receipt', receipt, *args], check=check)
        return result, receipt

    def install(self):
        """A faithful installed state: the real candidate, its journal and the #452 transition."""
        (self.work / 'receipts').mkdir(mode=0o700)
        self.bundles.mkdir(mode=0o755)
        self.admin.mkdir(mode=0o700)
        self.installed.mkdir(mode=0o700)
        campaign = json.loads(CAMPAIGN.read_text())
        images = campaign['publications']
        for image in images.values():
            shell(['docker', 'pull', '-q', image], timeout=900)
        secret_dir = self.work / 'secrets'
        synthetic_secrets(secret_dir)
        synthetic_tls(self.work / 'tls', images['application'])
        (self.work / 'model').mkdir()
        (self.work / 'metrics').mkdir(mode=0o755)
        source = self.installed / 'candidate'
        source.mkdir(mode=0o700)
        prepare.export_source(ROOT, prepare.REVISION, source)
        values = prepare.parse_runtime_env((source / 'deploy/public-production/runtime.env.example').read_text())
        values.update(PRODUCTION_SECRETS_DIR=str(secret_dir), PRODUCTION_SECRETS_GID=str(os.getgid()),
                      CATALOGUE_TRANSLATION_MODEL_DIR=str(self.work / 'model'), PRODUCTION_METRICS_DIR=str(self.work / 'metrics'),
                      APPLICATION_VERSION=prepare.VERSION, KEYCLOAK_ADMIN_USERNAME='disposable-admin')
        for name, image in images.items():
            values[name.upper().replace('-', '_') + '_IMAGE'] = image
        runtime_env = self.installed / 'runtime.env'
        runtime_env.write_text(''.join(f'{k}={v}\n' for k, v in sorted(values.items())))
        base = prepare.render_base(runtime_env, source)
        prepare.rebind_source(base, source, self.installed)
        (self.installed / 'Caddyfile.private').write_text(prepare.private_caddy((source / 'deploy/public-production/caddy/Caddyfile').read_text()))
        (self.installed / 'Caddyfile.private').chmod(0o644)
        config = prepare.adapt(base, self.installed / 'Caddyfile.private', install.TLS)
        prepare.validate(base, config, self.installed / 'Caddyfile.private', install.TLS)
        config = isolate(config, self.project)
        prepare.prepare_mount_directories(config, source)
        private = self.installed / 'compose.private.json'
        private.write_text(json.dumps(config, sort_keys=True, indent=2) + '\n')
        sha = install.sha(private)
        journal = self.admin / 'first-install'
        (journal / 'recovery').mkdir(parents=True, mode=0o700)
        labelled = json.loads(json.dumps(config))
        for service in labelled['services'].values():
            service.setdefault('labels', {}).update({control.RUN_LABEL: self.run_id, control.CONFIG_LABEL: sha})
        control.durable(journal / 'runtime.json', labelled)
        accepted = acceptance.accepted_configuration(labelled, ROOT)
        control.durable(self.admin / acceptance.ACCEPTED, accepted)
        control.durable(self.admin / 'first-install-intent.json', {'runId': self.run_id, 'configurationSha256': sha})
        control.durable(journal / 'installation.json', {'runId': self.run_id, 'outcome': 'success', 'phase': 'complete',
                                                        'leaseCompletion': 'acknowledged-and-exited'})
        control.durable(self.admin / acceptance.RECORD, {
            'status': 'applied', 'runId': self.run_id, 'configurationSha256': sha,
            'acceptedConfigurationSha256': install.sha(self.admin / acceptance.ACCEPTED)})
        self.config, self.accepted_file = accepted, self.admin / acceptance.ACCEPTED
        self.provenance = {p: install.sha(self.admin / p) for p in
                           ('first-install/runtime.json', 'first-install/installation.json', 'first-install-intent.json',
                            acceptance.ACCEPTED, acceptance.RECORD)}
        self.provenance['compose.private.json'] = sha

    def start(self):
        """The installer's order on the installed images, plus the #452 pre-applied view."""
        transition = acceptance.Transition(self.admin, ROOT)
        transition.evidence = {'runId': self.run_id, 'configuration': self.provenance['compose.private.json']}
        recovery = acceptance.load_recovery(self.installed / 'candidate', self.accepted_file, self.admin / 'first-install/recovery')
        recovery.initialize()
        gate = recovery.gate_volume
        shell(['docker', 'run', '--rm', '--network', 'none', '--label', f'vgp.production-test={self.project}', '-v', f'{gate}:/gate',
               install.HELPER, 'python3', '-c', OWNER.replace("__import__('sys').argv[1]", repr(self.run_id)).replace(
                   "__import__('sys').argv[2]", repr(self.provenance['compose.private.json']))])
        self.compose(self.accepted_file, 'up', '-d', '--no-deps', '--wait', '--wait-timeout', '240', '--pull', 'never', 'postgres')
        self.compose(self.accepted_file, 'run', '--rm', '--no-deps', '--pull', 'never', 'migration', timeout=900)
        rows = json.loads(recovery.sql('videogame_platform', upgrade.HISTORY_SQL))
        require(len(rows) == 33 and rows[-1]['version'] == '20261023.130000', 'Unexpected installed migration endpoint.')
        self.step('installedFlyway', {'rank': rows[-1]['rank'], 'version': rows[-1]['version']})
        host = install.Host(self.provenance['compose.private.json'], None, None)
        host.run_id, host.config, host.file, host.source, host.recovery = self.run_id, self.config, self.accepted_file, self.installed / 'candidate', recovery
        host.reader()
        transition.apply_view(recovery)
        self.step('coverageViewPreApplied', bool(json.loads(recovery.sql('videogame_platform',
            "SELECT to_json(has_table_privilege('videogame_grafana','catalogue.observability_localization_coverage','SELECT'));"))))
        self.compose(self.accepted_file, 'up', '-d', '--no-deps', '--wait', '--wait-timeout', '300', '--pull', 'never', 'keycloak', timeout=420)
        self.seed(recovery)
        for name in INSTALLED[1:]:
            self.compose(self.accepted_file, 'up', '-d', '--no-deps', '--wait', '--wait-timeout', '300', '--pull', 'never', name, timeout=420)
        self.containers = {name: self.compose(self.accepted_file, 'ps', '-q', name).stdout.decode().strip() for name in ('postgres', *INSTALLED)}
        self.volumes = {v: shell(['docker', 'volume', 'inspect', '--format', '{{.CreatedAt}}', v]).stdout.decode().strip()
                        for v in (s['name'] for s in self.config['volumes'].values())
                        if not shell(['docker', 'volume', 'inspect', v], check=False).returncode}
        self.step('installedStackHealthy', ['postgres', *INSTALLED])

    def seed(self, recovery):
        secret = self.config['secrets']['keycloak_admin_password']['file']
        self.keycloak_admin = Admin(self.config['networks']['identity-data']['name'], self.project, 'disposable-admin', Path(secret).read_text().strip())
        admin = self.keycloak_admin
        self.password = secrets.token_urlsafe(18)
        admin.call('POST', '/admin/realms/gameometro/clients', {
            'clientId': 'synthetic-upgrade-login', 'publicClient': True, 'protocol': 'openid-connect',
            'directAccessGrantsEnabled': True, 'standardFlowEnabled': False})
        self.users = [f'upgrade-{n}@example.test' for n in range(3)]
        for user in self.users:
            admin.call('POST', '/admin/realms/gameometro/users', {
                'username': user, 'email': user, 'emailVerified': True, 'enabled': True,
                'credentials': [{'type': 'password', 'value': self.password, 'temporary': False}]})
        require(login_status(admin, self.users[0], self.password) == 200, 'Synthetic login failed before the upgrade.')
        subject = json.loads(admin.call('GET', f'/admin/realms/gameometro/users?username={urllib.parse.quote(self.users[0])}&exact=true'))[0]['id']
        # The canonical principal of this subject, as the BFF and recovery derive it.
        digest = hashlib.md5((ISSUER + '\n' + subject).encode()).digest()
        principal = str(uuid.UUID(bytes=digest, version=3))
        recovery.sql('videogame_platform', SEED.read_text())
        recovery.sql('videogame_platform', f"INSERT INTO ratings.rating(user_id,game_id,value) SELECT '{principal}',game_id,8 FROM catalogue.game LIMIT 1;")
        self.step('syntheticAccountsAndRating', {'users': len(self.users)})

    def live_mounts(self):
        """Failure diagnostics only: paths and images of synthetic containers, never their output."""
        result = {}
        for name in SCOPE:
            try:
                identity = self.compose(self.accepted_file, 'ps', '-aq', name).stdout.decode().split()
                value = json.loads(shell(['docker', 'inspect', *identity]).stdout)[0]
                result[name] = {'image': value['Config']['Image'], 'running': value['State']['Running'],
                                'exit': value['State']['ExitCode'], 'mounts': {m['Destination']: m.get('Source') for m in value['Mounts']}}
            except (RuntimeError, IndexError, OSError, AttributeError, ValueError) as error:
                result[name] = type(error).__name__
        return result

    def cleanup(self):
        if not getattr(self, 'accepted_file', None) or not self.accepted_file.exists():
            return
        self.compose(self.accepted_file, 'down', '-v', '--remove-orphans', '--timeout', '5', check=False, timeout=180)
        helpers = shell(['docker', 'ps', '-aq', '--filter', f'label=vgp.production-test={self.project}'], check=False).stdout.decode().split()
        probes = shell(['docker', 'ps', '-aq', '--filter', f'label=com.docker.compose.project={self.project}'], check=False).stdout.decode().split()
        if helpers or probes:
            shell(['docker', 'rm', '-f', *helpers, *probes], check=False)
        volumes = shell(['docker', 'volume', 'ls', '-q', '--filter', f'name={self.project}'], check=False).stdout.decode().split()
        networks = shell(['docker', 'network', 'ls', '-q', '--filter', f'name={self.project}'], check=False).stdout.decode().split()
        if volumes:
            shell(['docker', 'volume', 'rm', '-f', *volumes], check=False)
        if networks:
            shell(['docker', 'network', 'rm', *networks], check=False)


def refused(function, *args, **kwargs):
    try:
        function(*args, **kwargs)
    except RuntimeError as error:  # trust.Refused and the recovery actor's own Refused
        return str(error)
    raise AssertionError(f'{getattr(function, "__name__", function)} was not refused')


def keyrings(work):
    private = work / 'owner-keyring'
    public = work / 'runtime-keyring'
    for path in (private, public):
        path.mkdir(mode=0o700)
    shell(['gpg', '--homedir', private, '--batch', '--faked-system-time', str(int(time.time()) - 300), '--passphrase', '',
           '--quick-generate-key', 'Synthetic upgrade owner', 'rsa2048', 'sign,encr', '1d'])
    listing = shell(['gpg', '--homedir', private, '--batch', '--with-colons', '--list-keys']).stdout.decode()
    fingerprint = next(x.split(':')[9] for x in listing.splitlines() if x.startswith('fpr:'))
    exported = shell(['gpg', '--homedir', private, '--batch', '--export', fingerprint]).stdout
    shell(['gpg', '--homedir', public, '--batch', '--import'], data=exported)
    return private, public, fingerprint


def restore_check(backup, keyring, receipt, work):
    """A real restore of one pair into a throwaway target; the role passwords are synthetic."""
    manifest = json.loads((backup / 'manifest.json').read_text())
    require(not receipt.exists(), 'Receipt already exists.')
    project = 'vgp-production-test-' + secrets.token_hex(6)
    secret_dir = work / 'restore-secrets'
    synthetic_secrets(secret_dir)
    # Exactly the pair's three identities; the other services are never started.
    values = prepare.parse_runtime_env((ROOT / 'deploy/public-production/runtime.env.example').read_text())
    values.update(PRODUCTION_SECRETS_DIR=str(secret_dir), PRODUCTION_SECRETS_GID=str(os.getgid()),
                  APPLICATION_IMAGE=manifest['images']['application'], KEYCLOAK_IMAGE=manifest['images']['keycloak'],
                  APPLICATION_VERSION='restore-check', CATALOGUE_TRANSLATION_MODEL_DIR=str(work),
                  PRODUCTION_METRICS_DIR=str(work))
    env = work / 'restore.env'
    env.write_text(''.join(f'{k}={v}\n' for k, v in sorted(values.items())))
    config = isolate(prepare.render_base(env, ROOT), project)
    for service in config['services'].values():
        service.pop('ports', None)
    # The pair's own PostgreSQL, even if this checkout has moved on to a newer pin.
    config['services']['postgres']['image'] = manifest['images']['postgres']
    file = work / 'restore-compose.json'
    file.write_text(json.dumps(config))
    state = work / 'restore-journal'
    state.mkdir(mode=0o700)
    reader = work / 'restore-reader-password'
    reader.write_text(secrets.token_hex(24))
    reader.chmod(0o600)
    try:
        result = shell(['python3', '-I', ROOT / 'deploy/public-production/bin/recovery', 'restore', '--config', file,
                        '--project', project, '--state', state, '--backup', backup, '--keyring', keyring,
                        '--grafana-password-file', reader, '--receipt', receipt], check=False, timeout=3600)
        # The recovery actor's stderr holds only its fixed refusal texts, never raw diagnostics.
        require(result.returncode == 0, 'The isolated restore failed; the pair is unproven: ' + result.stderr.decode(errors='replace')[-300:])
        return json.loads(receipt.read_text())
    finally:
        shell(['docker', 'compose', '-p', project, '-f', file, '--profile', '*', 'down', '-v', '--timeout', '5'], check=False, timeout=180)
        shell(['docker', 'volume', 'rm', '-f', f'{project}-recovery-state'], check=False)


def flyway_endpoint(recovery):
    rows = json.loads(recovery.sql('videogame_platform', upgrade.HISTORY_SQL))
    return {'rank': rows[-1]['rank'], 'version': rows[-1]['version'], 'scripts': [r['script'] for r in rows[33:]]}


def assert_fenced_state(stack, upgrader, phase, may_log_in=()):
    gate = upgrader.gate_state()
    require(not gate['ready'] and gate['pending'], f'{phase}: the gate is open.')
    logins = json.loads(upgrader.recovery.sql('postgres', "SELECT coalesce(json_agg(rolname),'[]') FROM pg_roles WHERE rolcanlogin AND rolname<>'postgres';"))
    require(set(logins) <= set(may_log_in), f'{phase}: a consumer role can log in: {logins}')
    return {'gateClosed': True, 'maintenance': bool(gate['maintenance']), 'rolesThatCanLogIn': sorted(logins)}


def sessions(upgrader, role):
    return json.loads(upgrader.recovery.sql('postgres', f"SELECT count(*) FROM pg_stat_activity WHERE usename='{role}';"))


def killed(stack, action, kill=None, terminate=None, before_kill=None):
    """A real controller process that dies abruptly, or handles SIGTERM, with no chance to clean up otherwise."""
    Rehearsal.kill, Rehearsal.terminate, Rehearsal.before_kill = kill, terminate, staticmethod(before_kill) if before_kill else None
    sys.stdout.flush()
    pid = os.fork()
    if pid == 0:
        try:
            action(stack.upgrader())
        except BaseException:
            pass
        os._exit(77)
    _, status = os.waitpid(pid, 0)
    Rehearsal.kill, Rehearsal.terminate, Rehearsal.before_kill = None, None, None
    return status


def wait_for(condition, seconds, message):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(1)
    raise AssertionError(message)


def prove_fence(stack, upgrader, target_file, *, keycloak_can_log_in=False):
    """Everything an operator or a restart policy could start while the upgrade fence is pending."""
    result = {}
    for label, file in (('superseded', stack.accepted_file), ('target', target_file)):
        stack.compose(file, 'up', '-d', '--no-deps', '--pull', 'never', 'application', check=False, timeout=180)
        identity = stack.compose(file, 'ps', '-aq', 'application').stdout.decode().strip()
        wait_for(lambda: 'authorization is closed' in shell(['docker', 'logs', identity], check=False).stderr.decode(errors='replace')
                 + shell(['docker', 'logs', identity], check=False).stdout.decode(errors='replace'), 60,
                 f'The {label} application did not refuse behind the closed gate.')
        require(sessions(upgrader, 'videogame_app') == 0, f'The {label} application reached PostgreSQL.')
        result[f'{label}ApplicationRefused'] = True
        stack.compose(file, 'stop', '--timeout', '5', 'application', check=False)
        actor = stack.compose(file, 'run', '--rm', '--no-deps', '--pull', 'never', 'migration', check=False, timeout=120)
        require(actor.returncode != 0, f'The {label} migration actor ran behind the closed gate.')
        result[f'{label}MigrationActorRefused'] = True
    if not keycloak_can_log_in:
        stack.compose(stack.accepted_file, 'up', '-d', '--no-deps', '--pull', 'never', 'keycloak', check=False, timeout=180)
        time.sleep(25)
        require(sessions(upgrader, 'videogame_keycloak') == 0, 'Keycloak reached PostgreSQL behind NOLOGIN.')
        result['keycloakCannotLogIn'] = True
        stack.compose(stack.accepted_file, 'stop', '--timeout', '5', 'keycloak', check=False)
    stack.compose(target_file, 'up', '-d', '--no-deps', '--pull', 'never', 'caddy', check=False, timeout=120)
    upgrader.host(json.loads(target_file.read_text()), target_file, target_file.parent / 'candidate').probe(EDGE_CLOSED)
    result['edgeAnswersMaintenanceOnBothHosts'] = True
    stack.compose(target_file, 'stop', '--timeout', '5', 'caddy', check=False)
    for command in ('thaw', 'reopen'):
        require(stack.recovery_cli(command, check=False)[0].returncode != 0, f'recovery {command} ran over the pending upgrade.')
    result['recoveryActorsRefused'] = ['thaw', 'reopen']
    return result


def exercise(stack):
    work = stack.work
    print('Installing the signed candidate in an isolated project.', flush=True)
    stack.install()
    stack.start()
    private, public, fingerprint = keyrings(work)
    target_file = stack.bundles / f'{stack.target[:12]}-private' / upgrade.TARGET_CONFIG

    print('Reading the plan while the runtime is open.', flush=True)
    plan = stack.upgrader().plan()
    require(plan['flyway']['currentRank'] == 33 and len(plan['flyway']['pending']) == 5, 'Unexpected Flyway change set.')
    require(plan['realm']['changes'].keys() == {'rememberMe', 'ssoSessionIdleTimeoutRememberMe',
                                                'ssoSessionMaxLifespanRememberMe', 'rememberMeMapper'}, 'Unexpected realm delta.')
    require(plan['prerequisites']['keycloakAdministratorAuthenticates'] is True, 'The administrator credential was not proven.')
    require(plan['environmentKeys'] == {'application': [upgrade.VERSION_ENVIRONMENT]}, 'Unexpected environment delta.')
    stack.step('planOpenRuntime', {k: plan[k] for k in ('planSha256', 'changedServices', 'keptRunning', 'environmentKeys', 'acceptedDashboard')}
               | {'flyway': plan['flyway'], 'realm': plan['realm']['changes'], 'inventory': plan['inventory']})
    require(not plan['prerequisites']['writersFencedByBackup'], 'The fence was reported while open.')

    print('Proving refusals before any mutation.', flush=True)
    stack.step('stageRefusesUnreviewedHash', refused(stack.upgrader().stage, '0' * 64))
    stack.step('stagedBundle', stack.upgrader().stage(plan['planSha256'])['bundle'])
    manual = work / 'manual-dumps'
    manual.mkdir(mode=0o700)
    (manual / 'manifest.json').write_text('{}')
    (work / 'offhost.json').write_text('{}')
    stack.step('manualDumpsRefused', refused(stack.upgrader().apply, plan['planSha256'], backup=manual,
                                             offhost=work / 'offhost.json', restore=work / 'offhost.json', reference='rehearsal'))
    require(not (stack.admin / upgrade.UPGRADES / stack.target / upgrade.JOURNAL).exists(), 'A refusal claimed a journal.')

    print('Taking a real paired backup; writers stay fenced.', flush=True)
    backups = work / 'backups'
    backups.mkdir(mode=0o700)
    pair = backups / 'pair'
    stack.recovery_cli('backup', '--keyring', public, '--recipient', fingerprint, '--destination', pair)
    manifest = json.loads((pair / 'manifest.json').read_text())
    stack.step('canonicalPair', {'backupId': manifest['backupId'], 'applicationSchema': manifest['applicationSchema']})
    probe = stack.upgrader()
    stack.step('fenceAfterBackup', assert_fenced_state(stack, probe, 'backup'))
    plan_fenced = probe.plan(plan['planSha256'])
    require(plan_fenced['planSha256'] == plan['planSha256'], 'The plan hash is not stable across the fence.')
    require(plan_fenced['prerequisites']['writersFencedByBackup'], 'The backup fence was not recognised.')
    require(plan_fenced['prerequisites']['keycloakAdministratorAuthenticates'] is None, 'A stopped Keycloak cannot prove the credential.')
    stack.step('planHashStableAcrossFence', True)
    closed = stack.compose(target_file, 'run', '--rm', '--no-deps', '--pull', 'never', 'migration', check=False, timeout=300)
    require(closed.returncode != 0 and flyway_endpoint(probe.recovery)['rank'] == 33, 'The migration actor ran while the gate was closed.')
    stack.step('migrationActorRefusesClosedGate', True)

    print('Restoring the real pair into a throwaway target.', flush=True)
    restore_receipt = work / 'restore-receipt.json'
    # The owner's real command line, as a separate process on the machine holding the keyring.
    shell(['python3', Path(__file__).resolve(), '--restore-check', '--backup', pair, '--keyring', private, '--receipt', restore_receipt], timeout=3700)
    restored = json.loads(restore_receipt.read_text())
    stack.step('isolatedRestore', {k: restored.get(k) for k in ('kind', 'outcome', 'backupId')})
    _, copy_receipt = stack.recovery_cli('copy', '--backup', pair, '--destination', backups / 'copy')
    stack.step('localCopyIsNotOffHost', refused(stack.upgrader().apply, plan['planSha256'], backup=pair, offhost=copy_receipt,
                                                restore=restore_receipt, reference='rehearsal'))
    # The transfer itself is an owner step on another machine; this receipt stands in for its output.
    offhost = work / 'offhost-receipt.json'
    offhost.write_text(json.dumps({'kind': 'encrypted-pull', 'backupId': manifest['backupId'], 'outcome': 'success', 'simulated': True}))
    offhost.chmod(0o600)
    restore_receipt.chmod(0o600)
    evidence = dict(backup=pair, offhost=offhost, restore=restore_receipt, reference='rehearsal 455')
    sha = plan['planSha256']

    print('Handled interruption before the schema changes, then abandoning.', flush=True)
    Rehearsal.fault = 'migration'
    refused(stack.upgrader().apply, sha, **evidence)
    interrupted = stack.upgrader()
    require(interrupted.read_journal()['status'] == 'failed' and flyway_endpoint(interrupted.recovery)['rank'] == 33, 'Unexpected first interruption.')
    stack.step('interruptedBeforeSchema', assert_fenced_state(stack, interrupted, 'pre-schema'))
    require(stack.recovery_cli('backup', '--keyring', public, '--recipient', fingerprint, '--destination', backups / 'second', check=False)[0].returncode != 0,
            'A backup started while the upgrade held the fence.')
    stack.step('backupRefusedUnderTheFence', True)
    stack.step('abandoned', interrupted.abandon()['mode'])
    require(interrupted.gate_state()['maintenance'] is None, 'Abandon left the identity fence.')
    require(json.loads((stack.admin / 'first-install/recovery/PENDING.json').read_text()) == {'phase': upgrade.FENCED_PHASE, 'backupId': manifest['backupId']},
            'Abandon did not return to the backup fence.')

    print('SIGKILL with the migration actor in flight.', flush=True)
    # A real lock on the first migrated table keeps the actor in flight, instead of racing its speed.
    # Taken after the claim: any such session before it is a foreign writer, which the claim refuses.
    def hold_first_migrated_table():
        subprocess.Popen(['docker', 'compose', '-p', stack.project, '-f', str(stack.accepted_file), '--profile', '*', 'exec', '-T', 'postgres',
                          'gosu', 'postgres', 'psql', '-XAq', '-d', 'videogame_platform', '-c',
                          'BEGIN; LOCK TABLE catalogue.game_community_score IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(60); COMMIT;'],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        time.sleep(3)
    status = killed(stack, lambda u: u.apply(sha, **evidence), kill=('migration-actor', 8), before_kill=hold_first_migrated_table)
    require(os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL, f'The controller was not killed: {status}')
    interrupted = stack.upgrader()
    in_flight = bool(interrupted.migration_actor_running())
    require(in_flight, 'The migration actor was expected in flight.')
    record = interrupted.read_journal()
    require(record['status'] == 'running' and record['phase'] == 'migration' and record['migrationStarted'], 'Unexpected journal after SIGKILL.')
    stack.step('sigkillDuringMigration', {'actorStillRunning': in_flight, 'phase': record['phase'],
                                          **assert_fenced_state(stack, interrupted, 'sigkill-migration', ('videogame_app_migrator',))})
    stack.step('resumeRefusedWhileActorRuns', refused(interrupted.resume, sha))
    stack.step('abandonRefusedWhileActorRuns', refused(interrupted.abandon))
    wait_for(lambda: not interrupted.migration_actor_running(), 180, 'The orphaned migration actor did not finish.')
    stack.step('fenceHoldsAfterSigkill', prove_fence(stack, interrupted, target_file))
    endpoint = flyway_endpoint(interrupted.recovery)
    require(endpoint['rank'] == 38 and endpoint['version'] == '20261106.120000', 'The orphaned actor did not reach the target endpoint.')
    stack.step('flyway33To38', endpoint)
    stack.step('abandonRefusedAfterSchema', refused(interrupted.abandon))
    stack.step('inspectAfterSchema', {k: v for k, v in upgrade.inspect(interrupted).items() if k in ('status', 'phase', 'advice', 'flyway')})
    grant = json.loads(interrupted.recovery.sql('videogame_platform',
        "SELECT to_json(has_table_privilege('videogame_grafana','catalogue.observability_localization_coverage','SELECT'));"))
    require(grant is True, 'The coverage view lost its reader grant after Flyway replaced it.')
    stack.step('coverageViewGrantSurvivedFlyway', True)
    require(json.loads(interrupted.recovery.sql('videogame_keycloak', upgrade.REALM_SQL))['rememberMe'] is False, 'The realm changed before its phase.')

    print('SIGTERM after the target Keycloak started: the tool must contain it.', flush=True)
    status = killed(stack, lambda u: u.resume(sha), terminate='realm-migration')
    require(os.WIFEXITED(status), f'The controller died instead of containing SIGTERM: {status}')
    interrupted = stack.upgrader()
    record = interrupted.read_journal()
    require(record['status'] == 'failed' and record['containment'] == 'confirmed' and record['cancellation'] == 'SIGTERM', f'Unexpected SIGTERM journal: {record["status"]}')
    stack.step('sigtermContained', {'failedPhase': record['failedPhase'], **assert_fenced_state(stack, interrupted, 'sigterm')})

    print('SIGKILL with the realm half migrated.', flush=True)
    secret = stack.config['secrets']['keycloak_admin_password']['file']
    network = stack.config['networks']['identity-data']['name']

    def half_migrate():
        admin = Admin(network, stack.project, 'disposable-admin', Path(secret).read_text().strip())
        admin.call('PUT', '/admin/realms/gameometro', {'rememberMe': True})
    before_realm = interrupted.read_journal()['identityBefore']
    status = killed(stack, lambda u: u.resume(sha), kill=('realm-migration', 0), before_kill=half_migrate)
    require(os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL, f'The controller was not killed: {status}')
    interrupted = stack.upgrader()
    half = json.loads(interrupted.recovery.sql('videogame_keycloak', upgrade.REALM_SQL))
    require(half['rememberMe'] is True and half['rememberMeMapper'] is False and half['ssoSessionIdleTimeoutRememberMe'] == 0, f'Unexpected half state: {half}')
    # The target image's startup import saw a changed file and an existing realm: it must have skipped it.
    now_digests = json.loads(interrupted.recovery.sql('videogame_keycloak', upgrade.IDENTITY_SQL))
    changed = sorted(k for k in before_realm if before_realm[k] != now_digests[k])
    require(not changed, f'Starting the target Keycloak or a partial realm update changed unrelated state: {changed}')
    stack.step('realmHalfMigratedThenKilled', {'realm': half, 'unrelatedDigestsChanged': changed,
                                               **assert_fenced_state(stack, interrupted, 'sigkill-realm', ('videogame_keycloak',))})
    stack.step('fenceHoldsWithTargetKeycloakRunning', prove_fence(stack, interrupted, target_file, keycloak_can_log_in=True))

    print('SIGKILL after reopening.', flush=True)
    status = killed(stack, lambda u: u.resume(sha), kill=('activation', 0))
    require(os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL, f'The controller was not killed: {status}')
    interrupted = stack.upgrader()
    gate = interrupted.gate_state()
    target = json.loads(target_file.read_text())
    require(gate['ready'] and not gate['pending'], 'The gate was expected open after reopening.')
    require(json.loads((stack.admin / upgrade.UPGRADES / upgrade.ACTIVE).read_text())['revision'] == stack.target, 'ACTIVE still names the superseded runtime.')
    superseded = {}
    for name in plan['changedServices']:
        if name not in upgrade.ONE_SHOT:
            identity = stack.compose(target_file, 'ps', '-aq', name).stdout.decode().split()
            require(len(identity) == 1, f'{name}: one container expected.')
            superseded[name] = json.loads(shell(['docker', 'inspect', identity[0]]).stdout)[0]['Config']['Image'] == target['services'][name]['image']
    require(all(superseded.values()), f'A superseded container survived until reopening: {superseded}')
    stack.step('noSupersededContainerAfterReopening', superseded)

    print('Resuming to completion.', flush=True)
    done = stack.upgrader().resume(sha)
    require(done['mode'] == 'applied', 'The upgrade did not complete.')
    require(done['optionalDegraded'] == ['catalogue-localizer'], f"Unexpected optional state: {done['optionalDegraded']}")
    stack.step('applied', done)

    print('Verifying preservation and repeatability.', flush=True)
    final = stack.upgrader()
    verified = final.verify()
    require(verified['optionalDegraded'] == ['catalogue-localizer'], 'Verify lost the degraded optional service.')
    stack.step('verified', verified)
    stack.step('secondResumeRefused', refused(final.resume, sha))
    stack.step('samePlanRefusedAfterCompletion', refused(final.plan))
    host = final.host()
    snapshots = sorted(p.name for p in final.directory.glob('keycloak-identity-before-*.json'))
    final.realm_command(host, '--check')
    final.realm_command(host, '--snapshot-dir', '/snapshots')
    final.realm_command(host, '--check')
    require(snapshots and sorted(p.name for p in final.directory.glob('keycloak-identity-before-*.json')) == snapshots, 'A repeated alignment changed something.')
    stack.step('realmMigrationRepeatable', {'snapshots': len(snapshots)})
    admin = Admin(network, stack.project, 'disposable-admin', Path(secret).read_text().strip())
    require(all(login_status(admin, user, stack.password) == 200 for user in stack.users), 'A synthetic account can no longer sign in.')
    stack.step('existingPasswordsAuthenticate', len(stack.users))
    identities = json.loads(final.recovery.sql('videogame_keycloak', upgrade.IDENTITY_SQL))
    record = final.read_journal()
    changed = sorted(k for k in record['identityBefore'] if identities[k] != record['identityBefore'][k])
    require(not changed, f'Identity state changed: {changed}')
    stack.step('identityFingerprintsUnchanged', {'digests': len(identities), 'users': identities['users'][0], 'credentials': identities['credentials'][0]})
    data = final.query('videogame_platform', upgrade.DATA_SQL)
    require(data == record['dataBefore'] and data['ratings'] == 1 and data['games'] > 0, 'Catalogue or rating rows changed.')
    stack.step('dataPreserved', data)
    reader = final.query('postgres', upgrade.READER_SQL)
    require(reader == record['readerBefore'] and any(v.startswith('statement_timeout=') for v in reader['settings']), 'The reader settings changed.')
    stack.step('readerSettingsPreserved', reader)
    require({p: install.sha(stack.admin / p) for p in stack.provenance if p != 'compose.private.json'}
            == {k: v for k, v in stack.provenance.items() if k != 'compose.private.json'}, 'Installation provenance changed.')
    require(install.sha(stack.installed / 'compose.private.json') == stack.provenance['compose.private.json'], 'The installed bundle changed.')
    stack.step('firstInstallProvenanceUnchanged', sorted(stack.provenance))
    kept = {}
    for name in set(plan['keptRunning']) & set(SCOPE):
        identity = stack.compose(target_file, 'ps', '-q', name).stdout.decode().strip()
        kept[name] = identity == stack.containers[name]
    require(all(kept.values()) and 'postgres' in kept, f'An unchanged service was recreated: {kept}')
    stack.step('unchangedServicesNotRecreated', kept)
    live = {}
    for name in ('postgres', *INSTALLED):
        value = json.loads(shell(['docker', 'inspect', stack.compose(target_file, 'ps', '-q', name).stdout.decode().strip()]).stdout)[0]
        require(not value['HostConfig'].get('PortBindings') and value['HostConfig']['RestartPolicy']['Name'] == 'unless-stopped',
                'Published port or restart drift.')
        require(value['Config']['Image'] == target['services'][name]['image'], 'A service runs another image.')
        live[name] = value['Config']['Image'].rsplit('@', 1)[-1][:19]
    require(target['services']['migration']['restart'] == 'no', 'The one-shot actor gained a restart policy.')
    for name, value in stack.volumes.items():
        require(shell(['docker', 'volume', 'inspect', '--format', '{{.CreatedAt}}', name]).stdout.decode().strip() == value, 'A durable volume was replaced.')
    stack.step('noPublicationsVolumesKeptOneShotUnchanged', live)
    stack.report['outcome'] = 'success'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--target-revision', help='Trusted-main source SHA whose publications are the target')
    parser.add_argument('--evidence', type=Path, help='New protected directory for the sanitized report')
    parser.add_argument('--restore-check', action='store_true')
    parser.add_argument('--backup', type=Path)
    parser.add_argument('--keyring', type=Path)
    parser.add_argument('--receipt', type=Path)
    args = parser.parse_args()
    if args.restore_check:
        require(all((args.backup, args.keyring, args.receipt)), '--backup, --keyring and a new --receipt are required.')
        integration.verify_local_docker()
        os.umask(0o022)  # Disposable role secrets must reach their synthetic consumers.
        with tempfile.TemporaryDirectory(prefix='vgp-restore-check-') as name:
            work = Path(name)
            work.chmod(0o755)
            result = restore_check(args.backup.resolve(), args.keyring.resolve(), args.receipt.resolve(), work)
        print(json.dumps({k: result.get(k) for k in ('kind', 'outcome', 'backupId', 'durationSeconds')}))
        return 0
    require(args.target_revision and args.evidence, '--target-revision and a new --evidence directory are required.')
    os.umask(0o077)
    args.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
    integration.verify_local_docker()
    temporary = tempfile.mkdtemp(prefix='vgp-upgrade-rehearsal-', dir=os.environ.get('VGP_REHEARSAL_DIR'))
    work = Path(temporary).resolve()
    work.chmod(0o755)
    stack = Stack(work, args.target_revision, args.evidence)
    stack.patch()
    started = time.monotonic()
    try:
        exercise(stack)
        return 0
    except BaseException:
        stack.report['failure'] = traceback.format_exc(limit=6)[-1800:]
        stack.report['liveMounts'] = stack.live_mounts()
        print(stack.report['failure'], file=sys.stderr)
        return 1
    finally:
        stack.report['durationSeconds'] = round(time.monotonic() - started)
        stack.report['sourceFiles'] = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in
                                       ('scripts/private_production_upgrade.py', 'scripts/rehearse-private-production-upgrade.py')}
        (args.evidence / 'report.json').write_text(json.dumps(stack.report, indent=2) + '\n')
        if os.environ.get('VGP_REHEARSAL_KEEP') != '1':
            stack.cleanup()
            shutil.rmtree(work, ignore_errors=True)
        print(json.dumps({'outcome': stack.report['outcome'], 'report': str(args.evidence / 'report.json')}))


if __name__ == '__main__':
    raise SystemExit(main())
