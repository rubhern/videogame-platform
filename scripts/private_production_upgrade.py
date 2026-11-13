#!/usr/bin/env python3
"""Owner-operated in-place upgrade of the installed private runtime. Default: read-only plan.

Forward-only by design. The apply path consumes the write fence a canonical paired backup leaves
closed, so that backup is exactly the pre-upgrade recovery point, and holds the recovery lease and
identity fence until the target is activated. Every step verifies its postcondition before acting,
so an interruption resumes by fencing again and repeating the same reviewed steps. It never edits
the installation journal, reverses Flyway, restores over live volumes or starts a superseded image.
"""
import sys
sys.dont_write_bytecode = True

import argparse
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
import uuid
import zlib

import install_private_production as install
import prepare_private_production as prepare
import private_dev_promotion as trust
import private_production_acceptance as acceptance
import private_production_control as control
import public_production_integration as integration
import validate_private_production_truststore as stores
import private_production_admin as admin
import production_registration_notice as registration
import production_public_ingress as ingress

ROOT = Path(__file__).resolve().parents[1]
ADMIN = install.ADMIN
BUNDLES = Path('/srv/gameometro/installations')
REPOSITORY = Path('/srv/gameometro/repo')
UPGRADES = 'upgrades'
ACTIVE = 'ACTIVE.json'
JOURNAL = 'upgrade.json'
PLAN = 'upgrade-plan.json'
TARGET_CONFIG = 'compose.target.json'
CADDYFILE = 'Caddyfile.private'
PUBLIC_CADDYFILE = 'Caddyfile.public'
PRIVATE_TWIN = 'compose.private.json'
UPGRADE_LABEL = 'gameometro.upgrade.revision'
FENCED_PHASE = 'backup-complete-writers-still-fenced'
# The accepted first installation's services plus the reviewed additions (SERVICE_ADDITIONS).
PERSISTENT, ONE_SHOT = (*acceptance.PERSISTENT, 'loki'), acceptance.ONE_SHOT
# Trusted main publishes these; every other image stays, and PostgreSQL never changes here.
PUBLISHED = {'application': 'application', 'migration': 'application', 'keycloak': 'keycloak',
             'caddy': 'caddy', 'catalogue-localizer': 'catalogue-localizer'}
ALLOWED_FIELDS = {'image', 'environment', 'volumes', 'labels', 'mem_limit', 'pids_limit'}
PROTECTED_ENVIRONMENT = ('KC_', 'OIDC_', 'APPLICATION_OIDC_')
FORBIDDEN_ENVIRONMENT = ('ACCOUNT_DELETION_EXECUTOR_ENABLED', 'ACCOUNT_DELETION_EXECUTOR_CREDENTIAL_FILE')
DASHBOARD_TARGET = acceptance.DASHBOARD_TARGET
# Dependency-safe activation: the BFF validates its issuer through the edge at startup.
ACTIVATION = ('telemetry', 'prometheus', 'node-exporter', 'blackbox', 'caddy', 'application')
# Not needed for product readiness (#452): a failure is recorded, and never fences the product.
# Loki starts before Grafana, which provisions it as a datasource.
OPTIONAL = ('loki', 'catalogue-localizer', 'grafana')
# The reviewed guard refuses a closed gate, so only this tool, holding the lease and the identity
# fence, runs what that guard wraps. The gate therefore stays closed until the reviewed reopening.
MIGRATION_ENTRYPOINT = '/opt/gameometro/bin/shared-run-migrations'
VERSION_ENVIRONMENT = 'TELEMETRY_SERVICE_VERSION'
FENCE_STOPPED = {'caddy', 'application', 'keycloak', 'grafana'}
MIGRATION_TIMEOUT = 1800
# Reviewed runtime-input additions. When a target adds one of these inputs, the upgrade fills it
# with this default; any other change to the input inventory still refuses. Extend these tables in
# the same change that adds a production runtime input, secret or protected setting
# (docs/development/delivery-lifecycle.md#production-upgrade-compatibility).
INPUT_DEFAULTS = {'PRODUCTION_FEEDBACK_ENABLED': 'false', 'PRODUCTION_FEEDBACK_INBOX': ''}
# Owner-chosen values, read from this protected file on every plan; only these keys are accepted.
OWNER_INPUTS = Path('/etc/gameometro/production/runtime-inputs.env')
OWNER_INPUT_KEYS = ('PRODUCTION_FEEDBACK_ENABLED', 'PRODUCTION_FEEDBACK_INBOX')
# Secrets a target may add: Compose name -> (consumer, its *_FILE setting). The upgrade creates a
# missing file empty (root, the secret group, 0640) only while its integration stays disabled.
SECRET_ADDITIONS = {
    'feedback_smtp_username': ('application', 'FEEDBACK_SMTP_USERNAME_FILE'),
    'feedback_smtp_password': ('application', 'FEEDBACK_SMTP_PASSWORD_FILE'),
}
# Existing settings a target may change: (service, setting) -> (accepted previous value, target value),
# or None when the owner inputs select the value (validated by validate_runtime).
SETTING_TRANSITIONS = {
    ('keycloak', 'KC_LOG_LEVEL'): ('info', 'info,org.keycloak.services.managers.AuthenticationManager:error'),
    ('application', 'FEEDBACK_ENABLED'): None,
    ('application', 'FEEDBACK_INBOX'): None,
}
# ADR-0040: Grafana loads the Loki datasource it now provisions; every other plugin stays disabled.
GRAFANA_DISABLED_PLUGINS = ','.join(p for p in acceptance.DISABLED_PLUGINS.split(',') if p != 'loki')
SETTING_TRANSITIONS[('grafana', 'GF_PLUGINS_DISABLE_PLUGINS')] = (acceptance.DISABLED_PLUGINS, GRAFANA_DISABLED_PLUGINS)
# Services and named volumes a target may add: service -> (its networks, its named volumes). An added
# service gets the same private hardening as the rest; it publishes nothing and holds no secret.
SERVICE_ADDITIONS = {'loki': ({'telemetry'}, {'loki-data'})}
assert set(SERVICE_ADDITIONS) <= set(PERSISTENT)
REALM_IMPORT = 'deploy/public-production/keycloak/import/gameometro-realm.json'
NOTICE_COMMAND = 'scripts/production_registration_notice.py'
REALM_COMMAND = 'deploy/private-dev/bin/configure-keycloak-identity'
MIGRATION_DIRECTORY = 'backend/src/main/resources/db/migration/'
BFF_CLIENT = 'videogame-platform-bff'
REMEMBER_MAPPER = 'native remember me'
# Session portion of the reviewed realm migration; the notice actor owns localization.
SESSION_SETTINGS = ('rememberMe', 'ssoSessionIdleTimeout', 'ssoSessionMaxLifespan',
                    'ssoSessionIdleTimeoutRememberMe', 'ssoSessionMaxLifespanRememberMe')
# The owner-approved password policy (#473); the same actor applies it and no other value passes.
PASSWORD_POLICY = 'passwordPolicy'
APPROVED_PASSWORD_POLICY = ('length(8) and maxLength(128) and notEmail and notContainsUsername'
                            ' and passwordBlacklist(gameometro-common-passwords.txt)')
OFF_HOST_RECEIPTS = ('encrypted-pull', 's3-publish')
READER_VIEWS = ('catalogue.observability_inventory', 'catalogue.observability_release_quality',
                'catalogue.observability_runs', 'catalogue.observability_player_scores',
                'catalogue.observability_localization_coverage', 'ratings.observability_summary',
                'ratings.observability_score_distribution', 'ratings.observability_game_rankings',
                'public.observability_database')
GATE_STATE = ("import json,os\np='/gate/'\ndef read(n):\n try:\n  return json.load(open(p+n))\n except Exception:\n  return None\n"
              "print(json.dumps({'ready':os.path.isfile(p+'READY'),'pending':os.path.exists(p+'PENDING.json'),"
              "'maintenance':(read('IDENTITY-MAINTENANCE.json') or {}).get('runId'),"
              "'owner':read('INSTALLATION-OWNER.json')}))")
# Anonymous only: personal routes refuse, the public catalogue answers from local state.
AUTHORIZATION_PROBE = r'''
import http.client,os,ssl
ctx=ssl.create_default_context(cafile=os.environ.get('OIDC_CONTRACT_CA_FILE') or None)
def get(path):
 c=http.client.HTTPSConnection('gameometro.com',context=ctx,timeout=15)
 c.request('GET',path);r=c.getresponse();b=r.read(262145);c.close()
 assert len(b)<=262144
 return r.status,b
assert get('/api/v1/me/ratings')[0]==401
assert get('/api/v1/releases?page=1&pageSize=1')[0]==200
'''
ADMIN_PROBE = ("import sys,urllib.parse,urllib.request\nf=urllib.parse.urlencode({'client_id':'admin-cli','grant_type':'password',"
               "'username':sys.argv[1],'password':sys.stdin.read().rstrip('\\r\\n')}).encode()\n"
               "urllib.request.urlopen('http://keycloak:8080/realms/master/protocol/openid-connect/token',f,timeout=20).close()\n")
REALM_SQL = """SELECT json_build_object('passwordPolicy',coalesce(password_policy,''),'rememberMe',remember_me,'ssoSessionIdleTimeout',sso_idle_timeout,
 'ssoSessionMaxLifespan',sso_max_lifespan,'ssoSessionIdleTimeoutRememberMe',sso_idle_timeout_remember_me,
 'ssoSessionMaxLifespanRememberMe',sso_max_lifespan_remember_me,'rememberMeMapper',EXISTS(
  SELECT FROM protocol_mapper m JOIN client c ON c.id=m.client_id WHERE c.realm_id=r.id
  AND c.client_id='""" + BFF_CLIENT + """' AND m.name='""" + REMEMBER_MAPPER + """'
  AND m.protocol_mapper_name='gameometro-remember-me')) FROM realm r WHERE r.name='gameometro';"""
# Digests only: nothing here returns an identity, an address or a credential. Accounts first: they
# must be untouched by the target image's startup, and everything must be untouched by the alignment.
ACCOUNT_DIGESTS = {
    'users': "user_entity t,r WHERE t.realm_id=r.id",
    'credentials': "credential t JOIN user_entity u ON u.id=t.user_id,r WHERE u.realm_id=r.id",
    'roles': "user_role_mapping t JOIN user_entity u ON u.id=t.user_id,r WHERE u.realm_id=r.id",
    'groups': "user_group_membership t JOIN user_entity u ON u.id=t.user_id,r WHERE u.realm_id=r.id",
    'attributes': "user_attribute t JOIN user_entity u ON u.id=t.user_id,r WHERE u.realm_id=r.id",
    'requiredActions': "user_required_action t JOIN user_entity u ON u.id=t.user_id,r WHERE u.realm_id=r.id",
    'federatedIdentities': "federated_identity t JOIN user_entity u ON u.id=t.user_id,r WHERE u.realm_id=r.id",
}
CONFIGURATION_DIGESTS = {
    'realmAttributes': "realm_attribute t,r WHERE t.realm_id=r.id",
    'realmSmtp': "realm_smtp_config t,r WHERE t.realm_id=r.id",
    'realmCredentials': "realm_required_credential t,r WHERE t.realm_id=r.id",
    'realmEventListeners': "realm_events_listeners t,r WHERE t.realm_id=r.id",
    'realmEventTypes': "realm_enabled_event_types t,r WHERE t.realm_id=r.id",
    'realmLocales': "realm_supported_locales t,r WHERE t.realm_id=r.id",
    'realmLocalizations': "realm_localizations t,r WHERE t.realm_id=r.id",
    'requiredActionProviders': "required_action_provider t,r WHERE t.realm_id=r.id",
    'flows': "authentication_flow t,r WHERE t.realm_id=r.id",
    'executions': "authentication_execution t,r WHERE t.realm_id=r.id",
    'authenticatorConfig': "authenticator_config t,r WHERE t.realm_id=r.id",
    'components': "component t,r WHERE t.realm_id=r.id",
    'componentConfig': "component_config t JOIN component c ON c.id=t.component_id,r WHERE c.realm_id=r.id",
    'identityProviders': "identity_provider t,r WHERE t.realm_id=r.id",
    'realmRoles': "keycloak_role t,r WHERE t.realm=r.id",
    'clients': "client t,r WHERE t.realm_id=r.id",
    'clientAttributes': "client_attributes t JOIN client c ON c.id=t.client_id,r WHERE c.realm_id=r.id",
    'clientRedirects': "redirect_uris t JOIN client c ON c.id=t.client_id,r WHERE c.realm_id=r.id",
    'clientOrigins': "web_origins t JOIN client c ON c.id=t.client_id,r WHERE c.realm_id=r.id",
    'clientScopes': "client_scope t,r WHERE t.realm_id=r.id",
    'clientScopeLinks': "client_scope_client t JOIN client c ON c.id=t.client_id,r WHERE c.realm_id=r.id",
    'mappers': "protocol_mapper t LEFT JOIN client c ON c.id=t.client_id LEFT JOIN client_scope s ON s.id=t.client_scope_id,r "
               "WHERE (c.realm_id=r.id OR s.realm_id=r.id) AND t.name<>'" + REMEMBER_MAPPER + "'",
    'mapperConfig': "protocol_mapper_config t JOIN protocol_mapper m ON m.id=t.protocol_mapper_id LEFT JOIN client c ON c.id=m.client_id "
                    "LEFT JOIN client_scope s ON s.id=m.client_scope_id,r WHERE (c.realm_id=r.id OR s.realm_id=r.id) AND m.name<>'" + REMEMBER_MAPPER + "'",
}
_DIGEST = "(SELECT json_build_array(count(*),md5(coalesce(string_agg(to_jsonb(t)::text,E'\\n' ORDER BY to_jsonb(t)::text),''))) FROM {})"
IDENTITY_SQL = ("WITH r AS (SELECT id FROM realm WHERE name='gameometro') SELECT json_build_object("
                + ','.join(f"'{name}'," + _DIGEST.format(source) for name, source in {**ACCOUNT_DIGESTS, **CONFIGURATION_DIGESTS}.items())
                + ",'realm',(SELECT md5((to_jsonb(x)-ARRAY['remember_me','sso_idle_timeout','sso_max_lifespan',"
                  "'sso_idle_timeout_remember_me','sso_max_lifespan_remember_me','password_policy'])::text) FROM realm x,r WHERE x.id=r.id));")
READER_SQL = "SELECT json_build_object('settings',coalesce(rolconfig,'{}'),'connectionLimit',rolconnlimit) FROM pg_roles WHERE rolname='videogame_grafana';"
DATA_SQL = """SELECT json_build_object('games',(SELECT count(*) FROM catalogue.game),
 'releases',(SELECT count(*) FROM catalogue.release_snapshot),'ratings',(SELECT count(*) FROM ratings.rating));"""
WORK_SQL = """SELECT json_build_object('runningSynchronizations',(SELECT count(*) FROM catalogue.synchronization_run WHERE run_status='running'),
 'activeTranslationClaims',(SELECT count(*) FROM catalogue.content_translation WHERE claimed_until>now()));"""
HISTORY_SQL = """SELECT coalesce(json_agg(json_build_object('rank',installed_rank,'version',version,'script',script,
 'checksum',checksum,'success',success) ORDER BY installed_rank),'[]') FROM flyway_schema_history WHERE type='SQL';"""
require = trust.require
run = control.run


def refusal_text(error):
    """Reviewed refusal texts only, from this tool or the recovery actor it loads; never raw output."""
    return str(error) if isinstance(error, trust.Refused) or type(error).__name__ == 'Refused' else 'inspect-locally'


def canonical(value):
    return json.dumps(value, sort_keys=True, indent=2) + '\n'


def digest(value):
    return hashlib.sha256((value if isinstance(value, str) else canonical(value)).encode()).hexdigest()


def flyway_checksum(data):
    """Flyway's checksum of a SQL resource, so a changed applied file refuses before any fence."""
    text = data.decode('utf-8').removeprefix('﻿')
    lines = re.split(r'\r\n|\r|\n', text)
    if lines and lines[-1] == '':
        lines.pop()
    crc = 0
    for line in lines:
        crc = zlib.crc32(line.encode('utf-8'), crc)
    return crc - (1 << 32) if crc >= (1 << 31) else crc


def migration_inventory(entries):
    """Versioned migrations of the target source, ordered as Flyway orders them."""
    result = []
    for name, data in entries:
        match = re.fullmatch(r'V(\d+)_(\d+)__[A-Za-z0-9_]+\.sql', name)
        require(match, 'Unexpected migration file name in the target source.')
        result.append({'version': f'{match[1]}.{match[2]}', 'script': name, 'checksum': flyway_checksum(data)})
    require(result, 'The target source has no migrations.')
    return sorted(result, key=lambda m: tuple(int(p) for p in m['version'].split('.')))


def version_key(version):
    return tuple(int(part) for part in version.split('.'))


def flyway_delta(history, files):
    """Applied history must be a clean prefix of the target; anything else needs a forward fix."""
    require(len(history) <= 2000 and len(files) <= 2000, 'Migration inventory exceeds its bound.')
    require(all(row['success'] for row in history), 'A failed Flyway row exists: forward recovery required, no repair.')
    known = {m['version']: m for m in files}
    for row in history:
        expected = known.get(row['version'])
        require(expected is not None, 'The database is ahead of the target source; refused.')
        require(expected['script'] == row['script'] and expected['checksum'] == row['checksum'],
                'An applied migration differs from the target source; refused.')
    applied = {row['version'] for row in history}
    pending = [m for m in files if m['version'] not in applied]
    if history and pending:
        current = max(version_key(row['version']) for row in history)
        require(all(version_key(m['version']) > current for m in pending), 'Out-of-order pending migration refused.')
    return {'currentRank': history[-1]['rank'] if history else 0,
            'currentVersion': history[-1]['version'] if history else None,
            'targetVersion': files[-1]['version'], 'pending': [m['script'] for m in pending]}


def realm_import_delta(previous, target):
    """Compare two realm imports semantically. Only the reviewed session, password-policy and registration-notice migrations may differ."""
    def clients(realm):
        return {c['clientId']: c for c in realm.get('clients', [])}
    try:
        previous = registration.reviewed_delta(previous, target)
    except ValueError as error:
        raise trust.Refused(str(error)) from None
    changed = sorted(k for k in set(previous) | set(target) if k != 'clients' and previous.get(k) != target.get(k))
    require(set(changed) <= {*SESSION_SETTINGS, PASSWORD_POLICY},
            'The realm import changed outside the reviewed session migration; a reviewed realm delta is required.')
    require(PASSWORD_POLICY not in changed or target.get(PASSWORD_POLICY) == APPROVED_PASSWORD_POLICY,
            'The realm import changed the password policy to an unreviewed value; a reviewed realm delta is required.')
    old, new = clients(previous), clients(target)
    require(set(old) == set(new), 'The realm import changed its clients; a reviewed realm delta is required.')
    mappers = []
    for name in old:
        before, after = dict(old[name]), dict(new[name])
        old_mappers = before.pop('protocolMappers', [])
        new_mappers = after.pop('protocolMappers', [])
        require(before == after and all(m in new_mappers for m in old_mappers),
                'The realm import changed a client; a reviewed realm delta is required.')
        added = [m for m in new_mappers if m not in old_mappers]
        require(name == BFF_CLIENT or not added, 'The realm import changed a client mapper outside the BFF.')
        require(all(m.get('name') == REMEMBER_MAPPER for m in added),
                'The realm import added an unreviewed protocol mapper.')
        mappers += [m['name'] for m in added]
    return {'settings': changed, 'mappers': mappers}


def desired_session_state(realm):
    bff = next(c for c in realm['clients'] if c['clientId'] == BFF_CLIENT)
    mapper = any(m.get('name') == REMEMBER_MAPPER and m.get('protocolMapper') == 'gameometro-remember-me'
                 for m in bff.get('protocolMappers', []))
    return {**{k: realm[k] for k in SESSION_SETTINGS}, PASSWORD_POLICY: realm.get(PASSWORD_POLICY, ''),
            'rememberMeMapper': mapper}


def verify_backup_evidence(manifest, images, config_hash, offhost, restore, *, now=None):
    """A canonical manifest plus its off-host and restore receipts; hashes of manual dumps never pass."""
    backup_id = acceptance.verify_backup(manifest, images, now=now)
    require(manifest.get('configurationSha256') == config_hash,
            'The pair was not captured from the active runtime configuration.')
    require(offhost.get('outcome') == 'success' and offhost.get('kind') in OFF_HOST_RECEIPTS
            and offhost.get('backupId') == backup_id, 'A verified off-host copy of this exact pair is required.')
    require(restore.get('outcome') == 'success' and restore.get('kind') == 'isolated-restore'
            and restore.get('backupId') == backup_id, 'A successful isolated restore of this exact pair is required.')
    return backup_id


def bundle_relative(path):
    path = Path(path)
    return '/'.join(path.relative_to(BUNDLES).parts[1:]) if path.is_relative_to(BUNDLES) else str(path)


def normalized(service):
    """A service with its bundle identity removed, so only real changes remain to classify."""
    value = json.loads(json.dumps(service))
    value.get('labels', {}).pop(UPGRADE_LABEL, None)
    for mount in value.get('volumes', []):
        if mount['type'] == 'bind':
            mount['source'] = bundle_relative(mount['source'])
    return value


def tree_digest(path):
    """Bytes and modes of one bind source; absent content never compares equal."""
    path = Path(path)
    if path.is_file():
        return hashlib.sha256(path.read_bytes()).hexdigest() + oct(path.stat().st_mode & 0o111)
    entries = []
    for parent, _, files in sorted(os.walk(path)):
        for name in sorted(files):
            file = Path(parent) / name
            entries.append(str(file.relative_to(path)) + ':' + tree_digest(file))
    return hashlib.sha256('\n'.join(entries).encode()).hexdigest()


def bound_files_equal(current, candidate, local):
    targets = {m['target']: m for m in candidate.get('volumes', []) if m['type'] == 'bind'}
    for mount in current.get('volumes', []):
        if mount['type'] != 'bind' or targets[mount['target']]['source'] == mount['source']:
            continue
        if tree_digest(mount['source']) != tree_digest(local(targets[mount['target']]['source'])):
            return False
    return True


def environment_delta(current, candidate):
    before, after = current.get('environment', {}), candidate.get('environment', {})
    return sorted(key for key in set(before) | set(after) if before.get(key) != after.get(key))


def review_delta(active, config, changed):
    """Images and environment keys a target changes; a reviewed added service has no active counterpart."""
    current = active['services']
    images = {n: s['image'] for n, s in config['services'].items() if s['image'] != current.get(n, {}).get('image')}
    keys = {n: environment_delta(current[n], config['services'][n]) for n in changed if n in current}
    return images, {n: k for n, k in keys.items() if k}


def secret_file_setting(name, key, value, added):
    """A new *_FILE setting is accepted only for a reviewed secret this upgrade adds."""
    return any(SECRET_ADDITIONS[secret] == (name, key) and value == '/run/secrets/' + secret for secret in added)


def setting_transition(name, key, before, after):
    if (name, key) not in SETTING_TRANSITIONS:
        return False
    expected = SETTING_TRANSITIONS[(name, key)]
    return expected is None or (before, after) == expected


def runtime_inputs(previous, example, owner):
    """The target's runtime inputs: the active values, reviewed defaults for new inputs, then owner choices."""
    expected = set(example)
    missing, extra = expected - set(previous), set(previous) - expected
    require(not extra and missing <= set(INPUT_DEFAULTS)
            and all(example[key] == INPUT_DEFAULTS[key] for key in missing),
            'The target requires a different runtime input inventory; a reviewed input change is required.')
    values = {**previous, **{key: INPUT_DEFAULTS[key] for key in missing}}
    require(set(owner) <= set(OWNER_INPUT_KEYS) & expected, 'The owner runtime inputs name an unreviewed key.')
    values.update(owner)
    require(values.get('PRODUCTION_FEEDBACK_INBOX') or values.get('PRODUCTION_FEEDBACK_ENABLED') != 'true',
            'Feedback delivery needs an owner inbox.')
    # Keys only, plus a digest binding the owner's values: the plan never carries a mailbox address.
    return values, {'defaulted': sorted(missing), 'ownerKeys': sorted(owner),
                    'ownerSha256': digest(canonical(owner)) if owner else None}


def validate_delta(name, current, candidate, fields, target_bundle, added_secrets=()):
    """Everything an upgrade may change, per service; any other difference is a refusal."""
    require(name != 'postgres', 'PostgreSQL configuration, image or bound-file changes need a separate procedure.')
    if 'secrets' in fields:
        before, after = current.get('secrets', []), candidate.get('secrets', [])
        new = [s for s in after if s not in before]
        require(all(s in after for s in before)
                and all(s.get('source') in added_secrets and SECRET_ADDITIONS[s['source']][0] == name
                        and s == {'source': s['source'], 'target': '/run/secrets/' + s['source']} for s in new),
                'A service would consume a secret outside the reviewed additions.')
        fields = [f for f in fields if f != 'secrets']
    require(set(fields) <= ALLOWED_FIELDS, 'Target configuration changes a field an in-place upgrade cannot change.')
    if 'image' in fields:
        require(name in PUBLISHED, 'A dependency outside the trusted publications changed its image.')
    if 'mem_limit' in fields:
        require(int(candidate['mem_limit']) >= int(current['mem_limit']), 'An accepted memory ceiling would decrease.')
    if 'pids_limit' in fields:
        require(int(candidate['pids_limit']) >= int(current['pids_limit']), 'An accepted process ceiling would decrease.')
    for key in environment_delta(current, candidate):
        if key in admin.environment(name):
            require(admin.allowed_setting(name, key, current.get('environment', {}).get(key), candidate.get('environment', {}).get(key)),
                    'The private admin ingress setting differs from its exact reviewed value.')
            continue
        if key == install.SCHEDULING and current.get('environment', {}).get(key) == 'false' \
                and candidate.get('environment', {}).get(key) == 'true':
            continue  # #484: the one reviewed activation of recurring acquisition.
        before, after = current.get('environment', {}).get(key), candidate.get('environment', {}).get(key)
        if key in current.get('environment', {}) and setting_transition(name, key, before, after):
            continue
        if before is None and secret_file_setting(name, key, after, added_secrets):
            continue
        require(not key.startswith(PROTECTED_ENVIRONMENT) and not key.endswith('_FILE'),
                'A protected identity, ingress or secret-file setting changed.')
        require(key == VERSION_ENVIRONMENT or key not in current.get('environment', {}),
                'An existing setting would change; only new settings and the version may differ.')
    volumes = lambda s: [m for m in s.get('volumes', []) if m['type'] != 'bind']
    require(volumes(current) == volumes(candidate), 'Durable volume attachments must not change.')
    removed = {m['target'] for m in current.get('volumes', [])} - {m['target'] for m in candidate.get('volumes', [])}
    require(removed <= {DASHBOARD_TARGET}, 'A mount would disappear; only the accepted dashboard override may.')
    for mount in candidate.get('volumes', []):
        if mount['type'] == 'bind' and mount not in current.get('volumes', []):
            require(mount.get('read_only') and (Path(mount['source']).is_relative_to(target_bundle)
                                                or Path(mount['source']).is_relative_to(install.TLS)),
                    'A new bind must be read-only and come from the target bundle or the TLS inputs.')


def validate_runtime(config, images):
    """The private, one-shot and #452 invariants of every resulting runtime."""
    services = config['services']
    require(config['name'] == prepare.PROJECT, 'Unexpected private project.')
    for name, service in services.items():
        # Only a public edge recorded by the reviewed ingress transition may publish, exactly 80/443.
        published = service.get('ports')
        require(not published or (name == 'caddy' and published == ingress.public_ports(published[0].get('host_ip'))),
                'Only the reviewed public edge may publish ports.')
        require(not service.get('privileged') and 'network_mode' not in service
                and not service.get('build') and '@sha256:' in service['image'],
                'Upgrades forbid privileges, host networking, builds and mutable images.')
        require(service.get('restart') == ('no' if name in ONE_SHOT else acceptance.POLICY),
                'Restart policy differs from the accepted runtime.')
    for name, source in PUBLISHED.items():
        require(services[name]['image'] == images[source], 'A service does not use the selected publication.')
    require(services['migration']['image'] == services['application']['image'], 'Migration must use the application image.')
    require(any(m['target'] == MIGRATION_ENTRYPOINT for m in services['migration'].get('volumes', [])),
            'The target no longer provides the reviewed migration entrypoint.')
    environment = services['application']['environment']
    require(install.reviewed_schedule(environment), 'Recurring acquisition must be off or on the reviewed default schedule.')
    require(services['grafana']['environment'].get('GRAFANA_TELEGRAM_ENABLED') == 'false', 'Notifications must stay off.')
    if 'FEEDBACK_ENABLED' in environment:
        require(environment['FEEDBACK_ENABLED'] in ('true', 'false')
                and (environment['FEEDBACK_ENABLED'] == 'false' or environment.get('FEEDBACK_INBOX')),
                'Feedback delivery needs an explicit flag and, when on, an owner inbox.')
    require(not set(environment) & set(FORBIDDEN_ENVIRONMENT) and 'account_deletion_executor_capability' not in config.get('secrets', {}),
            'A deferred overlay would be activated.')
    require(int(services['catalogue-localizer']['mem_limit']) >= int(acceptance.LOCALIZER_MEMORY),
            'The accepted localizer ceiling is not preserved.')
    require(services['grafana']['environment'].get('GF_PLUGINS_DISABLE_PLUGINS') in (acceptance.DISABLED_PLUGINS, GRAFANA_DISABLED_PLUGINS),
            'The reviewed Grafana plugin policy is not preserved.')
    require(any(Path(DASHBOARD_TARGET).is_relative_to(m['target']) for m in services['grafana'].get('volumes', [])),
            'The accepted localization dashboard is not mounted.')


def secret_additions(active, rendered):
    """Reviewed new secrets beside the existing protected ones; every existing one stays identical."""
    before, after = active.get('secrets', {}), rendered.get('secrets', {})
    require(all(after.get(name) == value for name, value in before.items()), 'Target configuration changes runtime secrets.')
    added = sorted(set(after) - set(before))
    directories = {Path(value['file']).parent for value in before.values()}
    require(set(added) <= set(SECRET_ADDITIONS) and len(directories) == 1,
            'Target configuration changes runtime secrets.')
    # Shaped exactly like the existing entries: the same Compose name prefix and protected directory.
    existing, sample = next(iter(before.items()))
    prefix = sample['name'][:-len(existing)] if 'name' in sample and sample['name'].endswith(existing) else None
    for name in added:
        expected = {'file': str(next(iter(directories)) / name.replace('_', '-'))}
        if prefix is not None:
            expected['name'] = prefix + name
        require(after[name] == expected, 'A reviewed secret addition must use the protected secret inventory.')
    return added


def validate_added_service(name, service, target_bundle):
    """A reviewed new service: only its own networks and volumes, read-only and unprivileged."""
    networks, volumes = SERVICE_ADDITIONS[name]
    require(set(service.get('networks', {})) == networks and not service.get('secrets')
            and not service.get('ports') and not service.get('group_add') and not service.get('cap_add')
            and service.get('read_only') is True and service.get('cap_drop') == ['ALL']
            and service.get('security_opt') == ['no-new-privileges:true'] and service.get('user')
            and not service.get('depends_on') and service.get('mem_limit') and service.get('pids_limit'),
            'An added service lacks the reviewed private hardening.')
    for mount in service.get('volumes', []):
        if mount['type'] == 'volume':
            require(mount['source'] in volumes, 'An added service mounts an unreviewed volume.')
        else:
            require(mount['type'] == 'bind' and mount.get('read_only')
                    and Path(mount['source']).is_relative_to(target_bundle),
                    'An added service bind must be read-only and come from the target bundle.')


def reconcile(active, rendered, target_bundle, local=lambda p: p):
    """Keep an unchanged service exactly as it runs; classify every other difference."""
    added_secrets = secret_additions(active, rendered)
    added = set(rendered['services']) - set(active['services'])
    require(set(active['services']) <= set(rendered['services']) and added <= set(SERVICE_ADDITIONS),
            'Target configuration changes the service inventory.')
    new_volumes = set(rendered.get('volumes', {})) - set(active.get('volumes', {}))
    require(new_volumes <= {v for n in added for v in SERVICE_ADDITIONS[n][1]}
            and {k: v for k, v in rendered.get('volumes', {}).items() if k not in new_volumes} == active.get('volumes', {}),
            'Target configuration changes runtime volumes.')
    for section in (set(active) | set(rendered)) - {'services', 'secrets', 'volumes'}:
        require(active.get(section) == rendered.get(section), 'Target configuration changes runtime ' + section + '.')
    config, changed = json.loads(json.dumps(rendered)), {}
    for name, candidate in config['services'].items():
        if name in added:
            validate_added_service(name, candidate, target_bundle)
            # It joins the installation it extends: the live checks require that lineage everywhere.
            lineage = active['services']['application'].get('labels', {})
            candidate['labels'] = {**candidate.get('labels', {}),
                                   **{k: lineage[k] for k in (control.RUN_LABEL, control.CONFIG_LABEL) if k in lineage}}
            changed[name] = ['added']
            continue
        current = active['services'][name]
        candidate['labels'] = {**candidate.get('labels', {}),
                               **{k: v for k, v in current.get('labels', {}).items() if k != UPGRADE_LABEL}}
        before, after = normalized(current), normalized(candidate)
        fields = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))
        same_mounts = {m['target'] for m in before.get('volumes', [])} == {m['target'] for m in after.get('volumes', [])}
        if not fields and same_mounts and bound_files_equal(current, candidate, local):
            config['services'][name] = json.loads(json.dumps(current))
            continue
        validate_delta(name, current, candidate, fields, target_bundle, added_secrets)
        changed[name] = fields or ['bound-files']
    return config, changed


def mark_changed(config, changed, revision):
    for name in changed:
        config['services'][name].setdefault('labels', {})[UPGRADE_LABEL] = revision


class Host(install.Host):
    """The installer's live-state contract, bound to one upgrade configuration."""

    def __init__(self, upgrade, config, path, source, revision=None, version=None, scope=None):
        super().__init__(upgrade.evidence['configuration'], None, None)
        self.upgrade = upgrade
        self.run_id, self.config, self.file, self.source = upgrade.evidence['runId'], config, path, source
        # A service a target adds is not part of the active runtime it replaces.
        scope = scope if scope is None else [n for n in scope if n in config['services']]
        self.base = ['docker', 'compose', '-p', prepare.PROJECT, '-f', str(path), '--profile', '*']
        self.expected_revision, self.expected_version = revision, version
        self.scope = scope
        self.recovery = upgrade.recovery
        self.local = lambda path: path
        self.degraded = ()

    def port_bindings_match(self, name, bindings):
        published = self.config['services'][name].get('ports')
        if not published:
            return super().port_bindings_match(name, bindings)
        if name != 'caddy' or not isinstance(published, list) or not isinstance(bindings, dict):
            return False
        ipv4 = published[0].get('host_ip')
        try:
            address = ipaddress.ip_address(ipv4)
        except ValueError:
            return False
        if address.version != 4 or not address.is_global or published != ingress.public_ports(ipv4):
            return False
        expected = {str(p['target']) + '/tcp': [{'HostIp': ipv4, 'HostPort': p['published']}] for p in published}
        # Docker can include unbound EXPOSE entries as null; only effective publications count.
        return {port: rows for port, rows in bindings.items() if rows} == expected

    def probe_ca_file(self):
        published = self.config['services']['caddy'].get('ports')
        if not published:
            return super().probe_ca_file()
        expected = {str(p['target']) + '/tcp': [{'HostIp': p['host_ip'], 'HostPort': p['published']}] for p in published}
        require(self.port_bindings_match('caddy', expected), 'Public TLS probe requires the reviewed edge bindings.')
        return None  # The public certificate must validate against the helper's system roots.

    def bind_path(self, path):
        # A plan reviews inputs before the bundle exists; the bytes are in its scratch copy.
        return self.local(super().bind_path(path))

    def smoke_services(self):
        return [n for n in self.scope if n != 'migration' and n not in self.degraded]

    def gate_open(self):
        require(self.recovery.gate('check') == b'open' and self.recovery.gate('maintenance-check') == b'none',
                'Recovery or identity fence closed.')

    def verify_truststore(self, store):
        """The target JVM is the store's consumer, so its own anchors are what it must retain."""
        image = self.config['services']['application']['image']
        if not all(self.upgrade.local_images({'application': image}).values()):
            return  # Reported as staging prerequisite; apply repeats this check with the image present.
        anchors = stores.jvm_entries(run(['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
                                          '--security-opt', 'no-new-privileges:true', '--log-driver', 'none',
                                          '--memory', '256m', '--pids-limit', '64', '--entrypoint', 'keytool', image,
                                          '-J-Duser.language=en', '-J-Duser.country=US', '-list', '-rfc',
                                          '-storepass', 'changeit', '-cacerts']))
        public = run(['openssl', 'pkcs12', '-in', store, '-passin', 'pass:changeit', '-nokeys']).decode()
        observed = stores.certificates(public)
        private = stores.certificates((install.TLS / 'ca.crt').read_text())
        require(set(anchors) <= set(observed) and private and set(private) <= set(observed),
                'The truststore lacks a target JVM anchor or the private CA; stage a revalidated store first.')


class Upgrade:
    def __init__(self, target, *, operational=None, adapter=None, repository=REPOSITORY, admin=None, bundles=None,
                 forward_of=None, runner=None):
        require(re.fullmatch('[0-9a-f]{40}', target or ''), 'A full lowercase target source SHA is required.')
        require(forward_of is None or (re.fullmatch('[0-9a-f]{40}', forward_of) and forward_of != target),
                'A forward fix names the full SHA of a different failed target.')
        self.target, self.operational, self.adapter = target, operational, adapter
        self.repository, self.forward_of = repository, forward_of
        self.admin = admin or install.ADMIN
        self.bundles = bundles or BUNDLES
        self.bundle = self.bundles / f'{target[:12]}-private'
        self.directory = self.admin / UPGRADES / target
        self.run = runner or control.run
        self.recovery = None

    # --- seams a disposable rehearsal replaces ---
    def github(self):
        return trust.GitHub(self.run(['gh', 'auth', 'token']).decode().strip())

    def verify_operational(self, api):
        install.verify_operational(api, self.operational, self.adapter)

    def host_checks(self):
        require(self.run(['timedatectl', 'show', '--property=NTPSynchronized', '--value']).strip() == b'yes',
                'Synchronized host clock required.')
        versions = control.versions(json.loads(self.run(['docker', 'info', '--format', '{{json .}}']))['ServerVersion'],
                                    self.run(['docker', 'compose', 'version', '--short']).decode().strip())
        return versions

    def scope(self):
        return list(PERSISTENT)

    def storage_paths(self):
        return self.admin, Path(json.loads(self.run(['docker', 'info', '--format', '{{json .}}']))['DockerRootDir'])

    def localize(self, config):
        """Rehearsal hook: the production rendering is used exactly as validated."""
        return config

    def verify_inputs(self, host):
        host.inputs(recovery=True)

    def phase_reached(self, phase):
        """Rehearsal hook for injecting an interruption after a phase is recorded."""

    def now(self):
        return datetime.now(timezone.utc)

    # --- installed lineage ---
    def load(self):
        transition = acceptance.Transition(self.admin, ROOT, runner=self.run)
        transition.load(recovery_pending=True)
        self.evidence = transition.evidence
        self.installed_source = transition.source
        record = json.loads((self.admin / acceptance.RECORD).read_text())
        accepted_file = self.admin / acceptance.ACCEPTED
        require(record.get('status') == 'applied' and record.get('runId') == self.evidence['runId']
                and record.get('acceptedConfigurationSha256') == install.sha(accepted_file),
                'The post-acceptance transition has not completed; complete it first.')
        self.active = self.active_state(accepted_file)
        self.recovery = acceptance.load_recovery(self.active['source'], self.active['file'],
                                                 self.admin / 'first-install/recovery')
        self.core = acceptance.recovery_module(self.active['source'])
        return self

    def active_state(self, accepted_file):
        pointer = self.admin / UPGRADES / ACTIVE
        if self.forward_of:
            path = self.bundles / f'{self.forward_of[:12]}-private' / TARGET_CONFIG
            record = self.read_journal(self.forward_of)
            require(record and record['status'] in ('failed', 'running') and record['planSha256'] and record['migrationStarted'],
                    'Only an interrupted upgrade past its migration start can be fixed forward.')
            require(install.sha(path) == record['targetConfigurationSha256'], 'The failed target configuration changed.')
            return {'file': path, 'config': json.loads(path.read_text()), 'revision': self.forward_of,
                    'source': path.parent / 'candidate', 'bundle': path.parent, 'predecessor': record}
        if pointer.exists():
            value = json.loads(pointer.read_text())
            path = Path(value['configurationFile'])
            require(install.sha(path) == value['configurationSha256'], 'The active upgrade configuration changed.')
            revision = value['revision']
            require(re.fullmatch('[0-9a-f]{40}', revision or ''), 'The active source revision is invalid.')
            # Public ingress relocates Compose, while recovery stays in the installed revision's bundle.
            bundle = self.bundles / f'{revision[:12]}-private'
            return {'file': path, 'config': json.loads(path.read_text()), 'revision': revision,
                    'source': bundle / 'candidate', 'bundle': bundle}
        return {'file': accepted_file, 'config': json.loads(accepted_file.read_text()), 'revision': prepare.REVISION,
                'source': self.installed_source, 'bundle': self.installed_source.parent}

    def read_journal(self, revision=None):
        path = self.admin / UPGRADES / (revision or self.target) / JOURNAL
        return json.loads(path.read_text()) if path.exists() else None

    def unfinished(self):
        """A single upgrade at a time: an unfinished journal blocks any other target."""
        root = self.admin / UPGRADES
        if not root.is_dir():
            return []
        return [p.parent.name for p in sorted(root.glob('*/' + JOURNAL))
                if json.loads(p.read_text())['status'] in ('running', 'failed')]

    def host(self, config=None, path=None, source=None):
        return Host(self, config or self.active['config'], path or self.active['file'], source or self.active['source'],
                    scope=self.scope())

    def target_host(self, plan):
        return Host(self, self.target_config, self.bundle / TARGET_CONFIG, self.bundle / 'candidate',
                    plan['target']['revision'], plan['target']['version'], self.scope())

    # --- read-only observations ---
    def gate_state(self):
        return json.loads(self.run(['docker', 'run', '--rm', '--log-driver', 'none', '--network', 'none', '--read-only',
                                    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '96m',
                                    '--pids-limit', '32', '-v', self.recovery.gate_volume + ':/gate:ro', install.HELPER,
                                    'python3', '-c', GATE_STATE], timeout=60))

    def query(self, database, statement):
        return json.loads(self.recovery.sql(database, 'BEGIN READ ONLY;\n' + statement + '\nCOMMIT;\n'))

    def observe_services(self, gate):
        """Live containers must be the active configuration; only fence-stopped or optional ones may be down."""
        host = self.host()
        result = {}
        closed = not gate['ready'] or gate['pending']
        for name in [n for n in self.scope() if n in host.config['services']]:
            identities = host.compose('ps', '-q', name).decode().split()
            if identities:
                host.running([name])
                result[name] = 'running'
                continue
            stopped = host.compose('ps', '-aq', name).decode().split()
            allowed = name in OPTIONAL or (closed and name in FENCE_STOPPED)
            require(allowed and len(stopped) <= 1, 'A required service is stopped or ambiguous.')
            if stopped:
                value = json.loads(self.run(['docker', 'inspect', stopped[0]]))[0]
                require(value['Config']['Image'] == host.config['services'][name]['image']
                        and host.port_bindings_match(name, value['HostConfig'].get('PortBindings')),
                        'A stopped service drifted from the active runtime.')
            result[name] = 'stopped'
        return result

    def capacity(self, config):
        memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines() if ':' in line)
        total = int(memory['MemTotal'].split()[0]) * 1024
        ceilings = sum(int(s['mem_limit']) for n, s in config['services'].items() if n in PERSISTENT)
        require(ceilings + total // 5 <= total, 'Target ceilings leave no platform memory reserve.')
        for path in self.storage_paths():
            usage = shutil.disk_usage(path)
            require(usage.free >= usage.total // 4, 'Insufficient free disk or recovery headroom.')

    def required_images(self, config):
        return {n: s['image'] for n, s in config['services'].items() if n in self.scope() or n in ONE_SHOT}

    def local_image(self, image):
        """The inspected config of a pulled digest, or None; a digest is content-addressed either way."""
        try:
            value = json.loads(self.run(['docker', 'image', 'inspect', image]))[0]
        except trust.Refused:
            return None
        # Docker records a pinned tag@digest reference without its tag.
        return value if re.sub(r':[^/@]+@', '@', image) in (value.get('RepoDigests') or []) else None

    def local_images(self, images):
        return {image: self.local_image(image) is not None for image in sorted(set(images.values()))}

    def image_identities(self, images):
        """Platform, user and source of each image, from the registry only when it is not pulled yet."""
        result, remote = {}, {}
        for name, image in images.items():
            value = self.local_image(image)
            if value is None:
                remote[name] = image
                continue
            labels = value['Config'].get('Labels') or {}
            require(value.get('Os') == 'linux' and value.get('Architecture') == 'amd64', 'Image is not linux/amd64.')
            require(name not in integration.REPOSITORIES or labels.get('org.opencontainers.image.revision') == self.target,
                    'Published image source differs from the target revision.')
            result[name] = {'image': image, 'user': value['Config'].get('User') or '0',
                            'sourceRevision': labels.get('org.opencontainers.image.revision'),
                            'version': labels.get('org.opencontainers.image.version')}
        if remote:
            result.update(prepare.registry_images(remote, runner=self.run, revision=self.target, version=None))
        return result

    # --- reviewed runtime inputs and secrets ---
    def owner_inputs(self):
        """The owner's protected choices for the reviewed owner keys; absent means none."""
        if not OWNER_INPUTS.exists():
            return {}
        install.secure(OWNER_INPUTS, mode=0o600)
        values = prepare.parse_runtime_env(OWNER_INPUTS.read_text())
        require(set(values) <= set(OWNER_INPUT_KEYS), 'The owner runtime inputs name an unreviewed key.')
        return values

    def create_secret_additions(self, config, added):
        """Create a missing reviewed secret empty; never overwrite one and never for an enabled integration."""
        application = config['services']['application']
        gid = int(application['group_add'][0])
        environment = application.get('environment', {})
        created = []
        for name in added:
            path = Path(config['secrets'][name]['file'])
            install.secure(path.parent, mode=0o750, directory=True, gid=gid)
            if path.exists():
                continue
            require(install.optional_secret(path.name, environment),
                    'An enabled integration needs its secret files provisioned before the upgrade.')
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o640)
            try:
                os.fchown(descriptor, 0, gid)
                os.fchmod(descriptor, 0o640)
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            created.append(path.name)
        if created:
            control.fsync_dir(next(iter(Path(config['secrets'][n]['file']).parent for n in added)))
        return created

    def missing_secret_additions(self, config, added):
        return {Path(config['secrets'][name]['file']).name for name in added
                if not Path(config['secrets'][name]['file']).exists()}

    # --- rendering ---
    def selected(self, api):
        selected = integration.resolve(api, self.target)
        comparison = api.request(f"/compare/{self.active['revision']}...{self.target}")
        require(comparison.get('status') == 'ahead', 'The target must be strictly ahead of the active source; no downgrade.')
        return selected

    def render(self, directory, api, selected):
        """Materialize the target in directory; its content is independent of that location."""
        source = directory / 'candidate'
        source.mkdir(mode=0o700)
        prepare.export_source(self.repository, self.target, source)
        tree = api.request(f'/git/trees/{self.target}?recursive=1')
        previous_runtime = self.active['bundle'] / 'runtime.env'
        values = prepare.parse_runtime_env(previous_runtime.read_text())
        example = (source / 'deploy/public-production/runtime.env.example').read_text()
        values, inputs = runtime_inputs(values, prepare.parse_runtime_env(example), self.owner_inputs())
        require(values['PRODUCTION_TELEGRAM_ENABLED'] == 'false', 'Owner notification activation is separate.')
        version = self.image_identities(selected['images'])['application']['version']
        require(re.fullmatch('[0-9A-Za-z.-]{1,100}', version or ''), 'The target application version is unreadable.')
        for name, image in selected['images'].items():
            values[name.upper().replace('-', '_') + '_IMAGE'] = image
        values['APPLICATION_VERSION'] = version
        runtime = directory / 'runtime.env'
        runtime.write_text(''.join(f'{k}={v}\n' for k, v in sorted(values.items())))
        base = prepare.render_base(runtime, source)
        prepare.rebind_source(base, source, self.bundle)
        caddy = prepare.private_caddy((source / 'deploy/public-production/caddy/Caddyfile').read_text())
        (directory / CADDYFILE).write_text(caddy)
        (directory / CADDYFILE).chmod(0o644)
        config = prepare.adapt(base, self.bundle / CADDYFILE, install.TLS)
        prepare.validate(base, config, self.bundle / CADDYFILE, install.TLS)
        for name, service in config['services'].items():
            service['restart'] = 'no' if name in ONE_SHOT else acceptance.POLICY
        config = self.localize(config)
        published = self.active['config']['services']['caddy'].get('ports')
        private_edge = None
        if published:
            # Preserve the reviewed public edge: the same derivation the ingress transition applied.
            ipv4 = published[0]['host_ip']
            require(published == ingress.public_ports(ipv4), 'The active edge publishes unreviewed ports.')
            private_edge = json.loads(json.dumps(config['services']['caddy']))
            text = ingress.public_caddyfile((directory / CADDYFILE).read_text())
            (directory / PUBLIC_CADDYFILE).write_text(text)
            (directory / PUBLIC_CADDYFILE).chmod(0o644)
            config = ingress.derive_public(config, self.bundle / PUBLIC_CADDYFILE, ipv4)

        def local(path):
            path = Path(path)
            return directory / path.relative_to(self.bundle) if path.is_relative_to(self.bundle) else path
        config, changed = reconcile(self.active['config'], config, self.bundle, local)
        require(changed, 'Nothing would change; refused.')
        validate_runtime(config, selected['images'])
        mark_changed(config, changed, self.target)
        (directory / TARGET_CONFIG).write_text(canonical(config))
        if private_edge is not None:
            # The private twin differs only in the edge; --revert of the ingress tool uses it.
            twin = json.loads(json.dumps(config))
            twin['services']['caddy'] = {**private_edge, 'labels': config['services']['caddy'].get('labels', {})}
            require(ingress.derive_public(twin, self.bundle / PUBLIC_CADDYFILE, ipv4) == config,
                    'The public target is not exactly the private target plus the reviewed edge.')
            (directory / PRIVATE_TWIN).write_text(canonical(twin))
        prepare.prepare_mount_directories(config, source, self.bundle)
        return {'tree': tree, 'config': config, 'changed': changed, 'version': version, 'local': local, 'inputs': inputs}

    def realm_plan(self, source):
        previous = json.loads((self.active['source'] / REALM_IMPORT).read_text())
        target = json.loads((source / REALM_IMPORT).read_text())
        realm_import_delta(previous, target)
        notice_target = registration.target_id(target)
        notice_migration = notice_target in (registration.APPROVED, registration.ELIGIBILITY)
        if notice_migration:
            actor = source / NOTICE_COMMAND
            require(actor.is_file() and actor.read_bytes() == Path(registration.__file__).read_bytes(),
                    'Target lacks the reviewed notice actor; choose a target published with this extension.')
        desired, live = desired_session_state(target), self.query('videogame_keycloak', REALM_SQL)
        notice_plan = None
        if notice_target == registration.APPROVED:
            notice_plan = {'targetSha256': notice_target, 'field': 'localizationTexts.es.termsText'}
        elif notice_migration:
            notice_plan = {'targetSha256': notice_target,
                           'fields': [f'localizationTexts.es.{key}' for key in sorted(registration.target_values(target))]}
        return {'registrationNotice': notice_plan,
                'changes': {k: {'from': live[k], 'to': desired[k]} for k in desired if live[k] != desired[k]},
                'preserved': 'users, credentials, identities, client secret, redirect URIs and every other realm setting',
                'method': 'scoped session, password-policy and registration-notice actors through the Admin API, never an import'}

    def accepted_dashboard(self, api, source):
        """#452 mounted one reviewed dashboard over the candidate's copy; the target must not lose it."""
        mounts = [m for m in self.active['config']['services']['grafana'].get('volumes', []) if m['target'] == DASHBOARD_TARGET]
        if not mounts:
            return 'carried-by-the-active-tree'
        target = source / acceptance.DASHBOARD
        if Path(mounts[0]['source']).read_bytes() == target.read_bytes():
            return 'identical'
        record = json.loads((self.admin / acceptance.RECORD).read_text())
        comparison = api.request(f"/compare/{record.get('operationalRevision')}...{self.target}")
        require(comparison.get('status') in ('ahead', 'identical'),
                'The target would replace the accepted dashboard with an older copy.')
        return 'superseded-by-a-newer-target'

    def migrations(self, tree):
        directory = MIGRATION_DIRECTORY
        entries = []
        names = [e for e in tree['tree'] if e['path'].startswith(directory) and e['type'] == 'blob']
        require(len(names) <= 2000, 'Migration inventory exceeds its bound.')
        for entry in names:
            data = self.run(['git', '-C', self.repository, 'show', f"{self.target}:{entry['path']}"])
            require(hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest() == entry['sha'],
                    'Target migration bytes differ from the trusted tree.')
            entries.append((entry['path'][len(directory):], data))
        files = migration_inventory(entries)
        return files, flyway_delta(self.query('videogame_platform', HISTORY_SQL), files)

    def build(self, directory, api):
        """Everything reviewable about this upgrade; the hash binds the owner's approval to it."""
        selected = self.selected(api)
        material = self.render(directory, api, selected)
        source = directory / 'candidate'
        install.verify_export(source, material['tree'])
        files, flyway = self.migrations(material['tree'])
        realm = self.realm_plan(source)
        dashboard = self.accepted_dashboard(api, source)
        config = material['config']
        changed_images, environment_keys = review_delta(self.active['config'], config, material['changed'])
        previous_images = {n: self.active['config']['services'][n]['image'] for n in PUBLISHED}
        reviewed = {
            'installed': {'runId': self.evidence['runId'], 'configurationSha256': self.evidence['configuration'],
                          'revision': prepare.REVISION},
            'current': {'revision': self.active['revision'], 'configurationFile': str(self.active['file']),
                        'configurationSha256': install.sha(self.active['file']), 'images': previous_images},
            'target': {'revision': self.target, 'version': material['version'], 'images': selected['images'],
                       'sourceRunId': selected['source_run_id'], 'sourceRunAttempt': selected['source_run_attempt'],
                       'securityRunId': selected['securityRunId']},
            'targetConfigurationSha256': digest(canonical(config)),
            'targetBundle': str(self.bundle),
            'changedServices': material['changed'], 'changedImages': changed_images,
            'keptRunning': sorted(set(config['services']) - set(material['changed']) - set(ONE_SHOT) - FENCE_STOPPED),
            'environmentKeys': environment_keys,
            'activationOrder': ['keycloak', *ACTIVATION, *OPTIONAL], 'acceptedDashboard': dashboard,
            'flyway': flyway, 'migrationInventory': files, 'realm': realm,
            'runtimeInputs': material['inputs'],
            'secretAdditions': sorted(set(config.get('secrets', {})) - set(self.active['config'].get('secrets', {}))),
            'adminIngress': {'grafana': config['services']['grafana']['environment'].get('GF_SERVER_ROOT_URL'),
                             'keycloak': config['services']['keycloak']['environment'].get('KC_HOSTNAME_ADMIN'),
                             'masterAttribute': 'frontendUrl', 'masterSnapshot': 'master-frontend.json'},
            'forwardFixOf': self.forward_of, 'publishedPorts': [], 'productionReady': False,
        }
        return {'reviewed': reviewed, 'planSha256': digest(reviewed), 'config': config, 'local': material['local']}

    def check_inputs(self, config, path, source, local=lambda p: p, pending=frozenset()):
        """The installer's input contract, against this configuration and its target JVM."""
        host = Host(self, config, path, source, scope=self.scope())
        host.local = local
        host.pending_secrets = frozenset(pending)
        host.images = self.image_identities(self.required_images(config))
        self.verify_inputs(host)

    def plan(self, reviewed_sha=None):
        """Read-only apart from a private scratch directory removed before returning."""
        versions = self.host_checks()
        require(not (self.forward_of is None and self.unfinished()), 'An unfinished upgrade exists; use --inspect.')
        api = self.github()
        self.verify_operational(api)
        with tempfile.TemporaryDirectory(prefix='.upgrade-plan-', dir=self.admin) as name:
            directory = Path(name)
            built = self.build(directory, api)
            config = built['config']
            # Staging creates reviewed secret additions empty; until then they are planned, not missing.
            pending = self.missing_secret_additions(config, built['reviewed']['secretAdditions'])
            self.check_inputs(config, directory / TARGET_CONFIG, directory / 'candidate', built['local'], pending)
            local = self.local_images(self.required_images(config))
        gate = self.gate_state()
        services = {} if self.forward_of else self.observe_services(gate)
        self.capacity(config)
        work = self.query('videogame_platform', WORK_SQL)
        reviewed = built['reviewed']
        identity = self.query('videogame_keycloak', IDENTITY_SQL)
        administrator = self.administrator_authenticates(self.host(), services)
        return {'mode': 'plan', 'planSha256': built['planSha256'], **reviewed, 'dockerVersions': versions,
                'liveServices': services, 'gate': {k: gate[k] for k in ('ready', 'pending', 'maintenance')},
                'inventory': {**self.query('videogame_platform', DATA_SQL), 'users': identity['users'][0],
                              'credentials': identity['credentials'][0]},
                'prerequisites': {**self.prerequisites(gate, work, local, reviewed_sha, built['planSha256']),
                                  'keycloakAdministratorAuthenticates': administrator}}

    def administrator_authenticates(self, host, services):
        """The realm migration runs after Flyway; prove its credential while that is still free. None: not provable now."""
        if services.get('keycloak') != 'running':
            return None
        environment = host.config['services']['keycloak']['environment']
        secret = Path(host.config['secrets']['keycloak_admin_password']['file'])
        try:
            self.run(['docker', 'run', '--rm', '-i', '--log-driver', 'none', '--network', host.config['networks']['identity-data']['name'],
                      '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '96m', '--pids-limit', '32',
                      '--label', 'gameometro.recovery-helper=true', install.HELPER, 'python3', '-c', ADMIN_PROBE,
                      environment['KC_BOOTSTRAP_ADMIN_USERNAME']], data=secret.read_bytes(), timeout=60, output=False)
            return True
        except trust.Refused:
            return False

    def prerequisites(self, gate, work, local, reviewed_sha, sha):
        fence = gate['pending'] and not gate['ready']
        return {'targetImagesLocal': all(local.values()), 'bundleStaged': (self.bundle / PLAN).exists(),
                'writersFencedByBackup': bool(fence), 'noActiveWork': not any(work.values()), 'activeWork': work,
                'reviewedHashMatches': reviewed_sha == sha if reviewed_sha else None,
                'requiredAtApply': ['fresh canonical pair with its fence still closed', 'off-host receipt', 'isolated-restore receipt']}

    # --- staging ---
    def stage(self, reviewed_sha):
        """Writes only the new bundle and pulls pinned digests; the runtime is not touched."""
        self.host_checks()
        require(not self.unfinished() or self.forward_of, 'An unfinished upgrade exists; use --inspect.')
        api = self.github()
        self.verify_operational(api)
        partial = self.bundle.with_name(self.bundle.name + '.partial')
        if partial.exists():
            shutil.rmtree(partial)
        if self.bundle.exists():
            with tempfile.TemporaryDirectory(prefix='.upgrade-plan-', dir=self.admin) as name:
                built = self.build(Path(name), api)
        else:
            partial.mkdir(mode=0o700)
            built = self.build(partial, api)
        require(built['planSha256'] == reviewed_sha, 'The plan differs from the reviewed hash.')
        if not self.bundle.exists():
            (partial / PLAN).write_text(canonical(built['reviewed']))
            for path in partial.iterdir():
                if path.is_file():
                    path.chmod(0o644 if path.name in (CADDYFILE, PUBLIC_CADDYFILE) else 0o600)
            partial.rename(self.bundle)
            control.fsync_dir(self.bundle.parent)
        # Only absent pinned digests: a present one is content-addressed and needs no registry.
        for image, present in self.local_images(self.required_images(built['config'])).items():
            if not present:
                self.run(['docker', 'pull', '--platform', 'linux/amd64', image], output=False, timeout=900)
        self.create_secret_additions(built['config'], built['reviewed']['secretAdditions'])
        self.verify_staged(built['reviewed'], built['config'], api)
        return {'mode': 'staged', 'planSha256': reviewed_sha, 'bundle': str(self.bundle), 'productionReady': False}

    def verify_staged(self, reviewed, config, api=None):
        """The bundle is what was reviewed, byte for byte; a changed bundle refuses."""
        install.secure(self.bundle, mode=0o700, directory=True)
        install.secure(self.bundle / TARGET_CONFIG, mode=0o600)
        install.secure(self.bundle / PLAN, mode=0o600)
        install.secure(self.bundle / CADDYFILE, mode=0o644)
        require(install.sha(self.bundle / TARGET_CONFIG) == reviewed['targetConfigurationSha256']
                and (self.bundle / TARGET_CONFIG).read_text() == canonical(config)
                and json.loads((self.bundle / PLAN).read_text()) == reviewed, 'The staged bundle differs from the reviewed plan.')
        source = self.bundle / 'candidate'
        if api is not None:
            install.verify_export(source, api.request(f'/git/trees/{self.target}?recursive=1'))
        install.verify_candidate_mounts(config, source, self.bundle)
        require((self.bundle / CADDYFILE).read_text() == prepare.private_caddy(
            (source / 'deploy/public-production/caddy/Caddyfile').read_text()), 'The private edge delta changed.')
        if config['services']['caddy'].get('ports'):
            install.secure(self.bundle / PUBLIC_CADDYFILE, mode=0o644)
            install.secure(self.bundle / PRIVATE_TWIN, mode=0o600)
            twin = json.loads((self.bundle / PRIVATE_TWIN).read_text())
            require((self.bundle / PUBLIC_CADDYFILE).read_text() == ingress.public_caddyfile((self.bundle / CADDYFILE).read_text())
                    and ingress.derive_public(twin, self.bundle / PUBLIC_CADDYFILE,
                                             config['services']['caddy']['ports'][0]['host_ip']) == config,
                    'The public edge or its private twin differs from the reviewed derivation.')
        require(all(self.local_images(self.required_images(config)).values()),
                'A target image is not available locally; stage again.')

    # --- apply ---
    def write(self, record):
        record['updatedAtUnix'] = int(time.time())
        control.durable(self.directory / JOURNAL, record)

    def phase(self, record, phase):
        record['phase'] = phase
        self.write(record)
        self.phase_reached(phase)

    def apply(self, reviewed_sha, *, backup=None, offhost=None, restore=None, reference=None):
        require(re.fullmatch(r'[A-Za-z0-9#:._ -]{1,120}', reference or ''), 'An owner authorization reference is required.')
        self.host_checks()
        require(self.read_journal() is None or self.read_journal()['status'] == 'abandoned',
                'An upgrade journal exists; use --inspect, then --resume or --forward-fix-of.')
        require(self.unfinished() == ([self.forward_of] if self.forward_of else []), 'Another upgrade is unfinished.')
        predecessor = self.active.get('predecessor')
        api = self.github()
        self.verify_operational(api)
        with tempfile.TemporaryDirectory(prefix='.upgrade-plan-', dir=self.admin) as name:
            built = self.build(Path(name), api)
        require(built['planSha256'] == reviewed_sha, 'The plan differs from the reviewed hash.')
        self.verify_staged(built['reviewed'], built['config'], api)
        self.check_inputs(built['config'], self.bundle / TARGET_CONFIG, self.bundle / 'candidate')
        self.target_config = built['config']
        record = self.claim_record(built, reference)
        with self.recovery.lock(maintenance=True):
            if predecessor:
                record.update(backup=predecessor['backup'], runUuid=predecessor['runUuid'], predecessor=self.forward_of,
                              identityBefore=predecessor['identityBefore'], dataBefore=predecessor['dataBefore'],
                              readerBefore=predecessor['readerBefore'], reopened=predecessor['reopened'])
                require(record['reopened'] or self.fence_continuous(record), 'Writers were not continuously fenced; the pair is stale.')
                self.carry_notice_snapshot(predecessor)
                self.directory.mkdir(parents=True, exist_ok=True)
            else:
                record['backup'] = self.verify_fence(backup, offhost, restore)
                record['identityBefore'] = self.query('videogame_keycloak', IDENTITY_SQL)
                record['dataBefore'] = self.query('videogame_platform', DATA_SQL)
                record['readerBefore'] = self.query('postgres', READER_SQL)
                self.directory.mkdir(parents=True, exist_ok=True)
                self.retain_evidence(backup, offhost, restore)
            self.write(record)
            control.durable(self.directory / PLAN, built['reviewed'])
            if predecessor:
                self.supersede(predecessor)
                self.refence(record)
            else:
                self.recovery.closed('upgrade-claimed', record['backup']['backupId'])
                require(self.recovery.gate('maintenance-enter', record['runUuid']) == b'entered',
                        'Another identity-maintenance run holds the fence.')
            return self.execute(record, built['reviewed'])

    def carry_notice_snapshot(self, predecessor):
        """Keep the original legal-text baseline through successive forward fixes."""
        previous = self.admin / UPGRADES / self.forward_of / 'registration-notice.json'
        destination = self.directory / previous.name
        if not previous.exists() and not previous.is_symlink():
            self.preserved(predecessor['identityBefore'], self.query('videogame_keycloak', IDENTITY_SQL),
                           ('realmLocalizations',))
            require(not destination.exists() and not destination.is_symlink(),
                    'A notice snapshot exists without its predecessor baseline.')
            return
        install.secure(previous, mode=0o600)
        require(previous.is_file() and not previous.is_symlink()
                and previous.stat().st_size <= 2 * registration.MAX_BYTES + 4096,
                'Invalid predecessor notice snapshot.')
        data = previous.read_bytes()
        saved = json.loads(data)
        target = json.loads((self.bundle / 'candidate' / REALM_IMPORT).read_text())
        require(isinstance(saved, dict) and saved.get('targetSha256') == registration.target_id(target)
                and isinstance(saved.get('before'), dict) and isinstance(saved.get('expected'), dict)
                and re.fullmatch('[0-9a-f]{64}', saved.get('realmSha256', '')),
                'Invalid predecessor notice baseline.')
        try:
            expected = registration.expected_localizations(saved['before'], target, live=True)
        except ValueError as error:
            raise trust.Refused('Invalid predecessor notice baseline.') from error
        require(saved['expected'] == expected, 'Invalid predecessor notice baseline.')
        if destination.exists() or destination.is_symlink():
            install.secure(destination, mode=0o600)
            require(destination.is_file() and not destination.is_symlink()
                    and destination.stat().st_size == len(data) and destination.read_bytes() == data,
                    'The inherited notice snapshot differs from its predecessor.')
            return
        self.directory.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=self.directory, suffix='.pending', delete=False) as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
            temporary = stream.name
        os.replace(temporary, destination)
        control.fsync_dir(self.directory)

    def claim_record(self, built, reference):
        return {'schema': 1, 'status': 'running', 'phase': 'claimed', 'runUuid': str(uuid.uuid4()),
                'planSha256': built['planSha256'], 'targetRevision': self.target,
                'targetConfigurationSha256': built['reviewed']['targetConfigurationSha256'],
                'previous': built['reviewed']['current'], 'recoverySource': str(self.active['source']),
                'recoveryConfiguration': str(self.active['file']), 'ownerReference': reference,
                'operationalRevision': self.operational, 'adapterFingerprint': self.adapter,
                'migrationStarted': False, 'identityStarted': False, 'reopened': False,
                'productionReady': False, 'startedAtUnix': int(time.time())}

    def supersede(self, predecessor):
        path = self.admin / UPGRADES / self.forward_of / JOURNAL
        predecessor.update(status='superseded', supersededBy=self.target, updatedAtUnix=int(time.time()))
        control.durable(path, predecessor)

    def verify_fence(self, backup, offhost, restore):
        """Nothing wrote since the pair: the same fence is still closed and the identities still match."""
        require(backup is not None and offhost is not None and restore is not None,
                'A paired backup, its off-host receipt and its restore receipt are required.')
        install.secure(backup, directory=True)
        for path in (offhost, restore):
            install.secure(path, mode=0o600)
        try:
            pair = self.core.verify(backup)
        except (RuntimeError, KeyError, ValueError, OSError, TypeError):
            raise trust.Refused('This is not a verified canonical paired backup; manual dumps never qualify.') from None
        manifest = json.loads((backup / 'manifest.json').read_text())
        backup_id = verify_backup_evidence(manifest, self.recovery.images, self.recovery.config_hash,
                                           json.loads(offhost.read_text()), json.loads(restore.read_text()), now=self.now())
        require(pair['backupId'] == backup_id, 'Backup identity changed during verification.')
        gate = self.gate_state()
        require(not gate['ready'] and gate['pending'] and not gate['maintenance'],
                'Writers must still be closed by the backup; a thawed or foreign fence needs a new pair.')
        pending = json.loads((self.admin / 'first-install/recovery/PENDING.json').read_text())
        require(pending == {'phase': FENCED_PHASE, 'backupId': backup_id},
                'Writers were not continuously fenced since this exact pair; take a new pair.')
        owner = acceptance.Transition(self.admin, ROOT, runner=self.run)
        owner.evidence = self.evidence
        owner.check_gate_owner(self.recovery)
        self.recovery.assert_fenced()
        metadata = self.recovery.metadata()
        require(all(metadata[k] == manifest[k] for k in ('postgresMajor', 'applicationSchema', 'keycloakSchema')),
                'Database identities differ from the pair; take a new pair.')
        require(not self.recovery.compose('ps', '-q', '--status', 'running', *sorted(FENCE_STOPPED | set(ONE_SHOT))).strip(),
                'A fenced writer is running.')
        require(not any(self.query('videogame_platform', WORK_SQL).values()),
                'Synchronization or localization work is recorded as active; thaw and resolve it first.')
        return {'backupId': backup_id, 'manifestSha256': install.sha(backup / 'manifest.json'),
                'recoveryPoint': manifest['recoveryPoint'], 'applicationSchema': manifest['applicationSchema'],
                'keycloakSchema': manifest['keycloakSchema'], 'images': manifest['images'],
                'offHostKind': json.loads(offhost.read_text())['kind'], 'restoreVerified': True}

    def retain_evidence(self, backup, offhost, restore):
        for name, path in (('backup-manifest.json', backup / 'manifest.json'), ('offhost-receipt.json', offhost),
                           ('restore-receipt.json', restore)):
            control.durable(self.directory / name, json.loads(path.read_text()))

    def fence_continuous(self, record):
        """Whether the pair still is the last write. A thaw or another backup breaks that for good."""
        path = self.admin / 'first-install/recovery/PENDING.json'
        gate = self.gate_state()
        if gate['ready'] or not gate['pending'] or not path.exists():
            return False
        pending = json.loads(path.read_text())
        backup = record['backup']['backupId']
        if pending.get('phase') == 'quiescing':
            # The canonical fence's first step. Only this upgrade can be there while it holds the fence.
            return gate['maintenance'] == record['runUuid']
        return pending.get('backupId') == backup and (pending.get('phase') == FENCED_PHASE or pending.get('phase', '').startswith('upgrade-'))

    def execute(self, record, reviewed):
        host = self.target_host(reviewed)
        try:
            self.phase(record, 'migration')
            self.migrate(host, record, reviewed)
            self.phase(record, 'reader')
            host.reader()
            self.phase(record, 'identity')
            self.identity(host, record)
            self.phase(record, 'replacing')
            self.replace_superseded(host, record, reviewed)
            self.phase(record, 'reopened')
            self.reopen(record)
            self.phase(record, 'activation')
            self.activate(host, record)
            self.phase(record, 'smoke')
            self.smoke(host, record)
            self.phase(record, 'finalizing')
            return self.finish(host, record, reviewed)
        except BaseException as error:
            self.fail(record, error)
            raise

    def migration_actor_running(self):
        return self.run(['docker', 'ps', '-q', '--filter', f'label=com.docker.compose.project={prepare.PROJECT}',
                         '--filter', 'label=com.docker.compose.service=migration']).strip()

    def migrate(self, host, record, reviewed):
        require(not self.migration_actor_running(), 'A migration actor is still running; wait and inspect.')
        files = reviewed['migrationInventory']
        state = flyway_delta(self.query('videogame_platform', HISTORY_SQL), files)
        if state['pending']:
            record['migrationStarted'] = True
            self.write(record)
            # The gate stays closed: an abrupt death here leaves every other actor refused, and only
            # this role can log in. Flyway applies each migration in its own transaction.
            self.recovery.sql('postgres', 'ALTER ROLE videogame_app_migrator LOGIN;')
            self.phase_reached('migration-actor')
            try:
                host.compose('run', '--rm', '--no-deps', '--pull', 'never', '--entrypoint', MIGRATION_ENTRYPOINT, 'migration',
                             timeout=MIGRATION_TIMEOUT, output=False)
            finally:
                with control.cleanup_window(120):
                    self.recovery.sql('postgres', 'ALTER ROLE videogame_app_migrator NOLOGIN;')
            state = flyway_delta(self.query('videogame_platform', HISTORY_SQL), files)
        require(not state['pending'] and state['currentVersion'] == state['targetVersion'],
                'Flyway did not reach the target endpoint.')
        require(not self.migration_actor_running(), 'A migration actor is still running; wait and inspect.')
        record['migration'] = {'version': state['currentVersion'], 'rank': state['currentRank']}

    def start(self, host, *names, timeout=600):
        host.compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', str(timeout), '--pull', 'never', *names,
                     timeout=timeout + 60, output=False)
        host.running(names)

    def realm_command(self, host, *arguments, command=REALM_COMMAND):
        """The shared alignment command on the private identity network; credentials cross stdin only."""
        environment = host.config['services']['keycloak']['environment']
        secret = Path(host.config['secrets']['keycloak_admin_password']['file'])
        return self.run(['docker', 'run', '--rm', '-i', '--log-driver', 'none', '--network', host.config['networks']['identity-data']['name'],
                         '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '96m', '--pids-limit', '32',
                         '--user', f'{os.getuid()}:{os.getgid()}', '--label', f'com.docker.compose.project={prepare.PROJECT}',
                         '--label', 'com.docker.compose.service=private-probe', '--label', 'gameometro.recovery-helper=true',
                         '-v', f'{host.source}:/candidate:ro', '-v', f'{self.directory}:/snapshots',
                         install.HELPER, 'python3', '-I', '/candidate/' + command, '--origin', 'http://keycloak:8080',
                         '--admin-username', environment['KC_BOOTSTRAP_ADMIN_USERNAME'], '--admin-password-file', '/dev/stdin',
                         *(['--realm', 'gameometro', '--sessions-only'] if command == REALM_COMMAND else
                           [] if command == NOTICE_COMMAND else ['--frontend-url', admin.KEYCLOAK]), *arguments],
                        data=secret.read_bytes(), timeout=120, output=False)

    def identity(self, host, record):
        before = record['identityBefore']
        self.recovery.sql('postgres', 'ALTER ROLE videogame_keycloak LOGIN;')
        record['identityStarted'] = True
        self.write(record)
        self.start(host, 'keycloak')
        started = self.query('videogame_keycloak', IDENTITY_SQL)
        if not record['reopened']:
            self.preserved(before, started, ACCOUNT_DIGESTS)
        target = json.loads((host.source / REALM_IMPORT).read_text())
        notice_migration = registration.target_id(target) in (registration.APPROVED, registration.ELIGIBILITY)
        if notice_migration:
            # First capture the original texts before any realm actor can change them.
            # On resume the durable notice snapshot verifies the already-aligned texts.
            if not (self.directory / 'registration-notice.json').exists():
                self.preserved(before, started, ('realmLocalizations',))
            self.realm_command(host, '--snapshot-dir', '/snapshots', command=NOTICE_COMMAND)
        self.phase_reached('realm-migration')
        self.realm_command(host, '--snapshot-dir', '/snapshots')
        self.realm_command(host, '--check')
        if host.config['services']['keycloak']['environment'].get('KC_HOSTNAME_ADMIN') == admin.KEYCLOAK:
            command = 'deploy/public-production/bin/configure-keycloak-admin'
            self.realm_command(host, '--snapshot-dir', '/snapshots', command=command)
            self.realm_command(host, '--check', command=command)
        if notice_migration:
            self.realm_command(host, '--check', command=NOTICE_COMMAND)
        after = self.query('videogame_keycloak', IDENTITY_SQL)
        if not record['reopened']:
            # Only the notice actor may change localizations; its durable snapshot verifies
            # every locale and text, while every other SQL digest remains unchanged.
            self.preserved(before, after, set(before) - ({'realmLocalizations'} if notice_migration else set()))
        live = self.query('videogame_keycloak', REALM_SQL)
        desired = desired_session_state(json.loads((host.source / REALM_IMPORT).read_text()))
        require(live == desired, 'The realm does not hold the reviewed session and password policy.')
        record['identityAfter'] = {'users': after['users'][0], 'credentials': after['credentials'][0]}

    def replace_superseded(self, host, record, reviewed):
        """Before anything can log in again: no superseded container remains to start, and ACTIVE names the target."""
        names = [n for n in reviewed['changedServices'] if n in self.scope() and n not in ONE_SHOT and n != 'keycloak']
        if names:
            host.compose('up', '--no-start', '--no-deps', '--pull', 'never', *names, timeout=300, output=False)
        self.write_active(record, reviewed)

    def write_active(self, record, reviewed):
        state_file = self.admin / 'ingress/INGRESS.json'
        twin = self.bundle / PRIVATE_TWIN
        if twin.exists():
            # Keep the ingress record on the upgraded runtime before ACTIVE names it.
            state = json.loads(state_file.read_text())
            require(state.get('mode') == 'public', 'A public target needs the recorded public ingress.')
            state.update(revision=self.target, privateConfigurationFile=str(twin), privateConfigurationSha256=install.sha(twin),
                         publicConfigurationFile=str(self.bundle / TARGET_CONFIG),
                         publicConfigurationSha256=reviewed['targetConfigurationSha256'])
            control.durable(state_file, state)
        control.durable(self.admin / UPGRADES / ACTIVE, {
            'revision': self.target, 'configurationFile': str(self.bundle / TARGET_CONFIG),
            'configurationSha256': reviewed['targetConfigurationSha256'], 'planSha256': record['planSha256'],
            'previous': record['previous'], 'updatedAtUnix': int(time.time())})

    @staticmethod
    def preserved(before, after, keys):
        require(all(before[k] == after[k] for k in keys),
                'Existing identity state changed during the realm migration; the fence stays closed.')

    def reopen(self, record):
        """The thaw equivalent for this forward-only state, which recovery thaw refuses by design."""
        record['reopened'] = True
        self.write(record)
        self.recovery.role_login(True)
        self.recovery.gate('open')
        self.core.write(self.recovery.state / 'READY', {'kind': 'private-upgrade', 'revision': self.target,
                                                           'backupId': record['backup']['backupId']})
        (self.recovery.state / 'PENDING.json').unlink(missing_ok=True)

    def activate(self, host, record):
        owner = self.recovery.gate('maintenance-check')
        require(owner in (b'none', record['runUuid'].encode()), 'Another identity-maintenance run holds the fence.')
        if owner != b'none':
            # Only the edge reads this fence, and it is stopped; the application needs identity to start.
            require(self.recovery.gate('maintenance-exit', record['runUuid']) == b'released', 'Identity fence release failed.')
        for name in ACTIVATION:
            if name in self.scope():
                self.start(host, name)
        record['optionalDegraded'] = []
        for name in OPTIONAL:
            if name in self.scope():
                try:
                    self.start(host, name)
                except trust.Refused:
                    # A cancellation still contains the upgrade; only this service's own failure is tolerated.
                    if control.CANCEL.is_set():
                        raise
                    record['optionalDegraded'].append(name)
        host.degraded = tuple(record['optionalDegraded'])

    def smoke(self, host, record):
        host.smoke()
        host.gate_open()
        host.probe(AUTHORIZATION_PROBE)
        for service, url in (('prometheus', 'http://prometheus:9090/-/ready'), ('grafana', 'http://grafana:3000/api/health')):
            if service in self.scope() and service not in host.degraded:
                host.probe(f"import urllib.request\nurllib.request.urlopen('{url}',timeout=15).close()\n")
        grants = ' AND '.join(f"has_table_privilege('videogame_grafana','{view}','SELECT')" for view in READER_VIEWS)
        require(self.query('videogame_platform', f'SELECT to_json({grants});') is True, 'The monitoring reader lost a grant.')
        data = self.query('videogame_platform', DATA_SQL)
        require(all(record['dataBefore'][k] <= data[k] for k in ('games', 'releases', 'ratings')),
                'Catalogue or rating rows were lost.')
        record['dataAfter'] = data
        reader = self.query('postgres', READER_SQL)
        require(set(record['readerBefore']['settings']) <= set(reader['settings'])
                and record['readerBefore']['connectionLimit'] == reader['connectionLimit'], 'The monitoring reader lost a setting.')

    def finish(self, host, record, reviewed):
        host.running(host.smoke_services())
        require(not self.migration_actor_running(), 'A one-shot actor is running.')
        self.recovery.check_lease()
        self.write_active(record, reviewed)
        record.update(status='complete', phase='complete', finishedAtUnix=int(time.time()),
                      commandCompletion='observed')
        self.write(record)
        return {'mode': 'applied', 'targetRevision': self.target, 'planSha256': record['planSha256'],
                'configurationFile': str(self.bundle / TARGET_CONFIG), 'migration': record['migration'],
                'optionalDegraded': record['optionalDegraded'], 'productionReady': False}

    def refence(self, record):
        """The one way back to a known state: the canonical fence, then this upgrade's closure."""
        self.recovery.fence()
        self.recovery.closed('upgrade-' + record['phase'], record['backup']['backupId'])
        owner = self.recovery.gate('maintenance-check')
        require(owner in (b'none', record['runUuid'].encode()), 'Another identity-maintenance run holds the fence.')
        require(self.recovery.gate('maintenance-enter', record['runUuid']) == b'entered', 'Identity fence refused.')

    def fail(self, record, error):
        record.update(status='failed', failedPhase=record['phase'], cancellation=control.CAUSE,
                      commandCompletion='inconclusive' if control.UNCERTAIN_COMMAND else 'observed',
                      reason=refusal_text(error))
        try:
            with control.cleanup_window(420):
                self.refence(record)
            record['containment'] = 'confirmed'
        except BaseException:
            record['containment'] = 'inconclusive'
        try:
            self.write(record)
        except BaseException:
            pass  # Pending writes and the closed fence remain; inspection reports inconclusive.

    def verify_claimed_operator(self, record):
        """Offline for the claimed tool, so an outage cannot strand it; a newer tool needs its publication."""
        require(install.operational_fingerprint() == self.adapter, 'The operator source differs from the supplied fingerprint.')
        if (self.operational, self.adapter) != (record.get('operationalRevision'), record.get('adapterFingerprint')):
            self.verify_operational(self.github())
            record.update(operationalRevision=self.operational, adapterFingerprint=self.adapter)

    def resume(self, reviewed_sha):
        """Fence again, then repeat the same idempotent steps. Fixed identities; no re-selection."""
        record = self.read_journal()
        require(record and record['status'] in ('running', 'failed'), 'No unfinished upgrade for this target.')
        self.verify_claimed_operator(record)
        require(record['planSha256'] == reviewed_sha, 'The plan differs from the claimed upgrade.')
        reviewed = json.loads((self.directory / PLAN).read_text())
        self.target_config = json.loads((self.bundle / TARGET_CONFIG).read_text())
        require(install.sha(self.bundle / TARGET_CONFIG) == record['targetConfigurationSha256']
                and digest(reviewed) == reviewed_sha, 'The claimed upgrade evidence changed.')
        self.verify_staged(reviewed, self.target_config)
        with self.recovery.lock(maintenance=True):
            require(not self.migration_actor_running(), 'A migration actor is still running; wait and inspect.')
            self.ensure_postgres()
            require(record['reopened'] or self.fence_continuous(record),
                    'Writers were not continuously fenced since the pair; --abandon and take a new pair.')
            record.update(status='running', attempts=record.get('attempts', 1) + 1)
            self.write(record)
            self.refence(record)
            return self.execute(record, reviewed)

    def ensure_postgres(self):
        host = self.host()
        if not host.compose('ps', '-q', 'postgres').strip():
            self.start(host, 'postgres', timeout=240)

    def abandon(self):
        """Back to the backup's own fence, only while the source and database identities are untouched."""
        record = self.read_journal()
        require(record and record['status'] in ('running', 'failed'), 'No unfinished upgrade for this target.')
        self.verify_claimed_operator(record)
        require(not record['identityStarted'] and not record.get('predecessor'),
                'Identity or a predecessor already advanced; only forward recovery remains.')
        with self.recovery.lock(maintenance=True):
            require(not self.migration_actor_running(), 'A migration actor is still running; wait and inspect.')
            metadata = self.recovery.metadata()
            backup = record['backup']
            require(metadata['applicationSchema'] == backup['applicationSchema'] and metadata['keycloakSchema'] == backup['keycloakSchema'],
                    'Schema history advanced; only forward recovery remains.')
            if not self.fence_continuous(record):
                # Never recreate the backup's fence over writes it did not capture.
                record.update(status='abandoned', finishedAtUnix=int(time.time()), fenceContinuous=False)
                self.write(record)
                return {'mode': 'abandoned', 'next': 'the pair is stale: take a new pair before another upgrade', 'productionReady': False}
            self.recovery.fence()
            self.recovery.closed(FENCED_PHASE, backup['backupId'])
            require(self.recovery.gate('maintenance-check') in (b'none', record['runUuid'].encode()), 'Another fence is held.')
            self.recovery.gate('maintenance-exit', record['runUuid'])
            record.update(status='abandoned', finishedAtUnix=int(time.time()))
            self.write(record)
        return {'mode': 'abandoned', 'next': 'recovery thaw with the active configuration', 'productionReady': False}

    def verify(self):
        """Read-only comparison of live state with the recorded activation."""
        pointer = json.loads((self.admin / UPGRADES / ACTIVE).read_text())
        require(pointer['revision'] == self.target, 'This target is not the active upgrade.')
        record = self.read_journal()
        require(record and record['status'] == 'complete', 'No completed upgrade matches this target.')
        host = self.host()
        host.degraded = tuple(record.get('optionalDegraded', ()))
        host.running(host.smoke_services())
        gate = self.gate_state()
        require(gate['ready'] and not gate['pending'] and not gate['maintenance'], 'The runtime is fenced.')
        require(not self.migration_actor_running(), 'A one-shot actor is running.')
        require(self.query('videogame_keycloak', REALM_SQL) == desired_session_state(
            json.loads((self.active['source'] / REALM_IMPORT).read_text())), 'The realm drifted from the reviewed policy.')
        return {'mode': 'verified', 'targetRevision': self.target, 'configurationFile': str(self.active['file']),
                'restartPolicy': acceptance.POLICY, 'services': host.smoke_services(),
                'optionalDegraded': list(host.degraded), 'productionReady': False}


ADVICE = {
    'not-started': 'Nothing was claimed. Plan again; thaw if a pair was taken for this attempt.',
    'before-schema': 'Schema and identity are untouched: --resume, or --abandon then recovery thaw.',
    'after-schema': 'Forward only: --resume after a transient fault, or --forward-fix-of with a newer published target, '
                    'or a separately authorized paired restore on an empty target. Never an older image.',
    'complete': 'Run --verify.',
}


def inspect(upgrade):
    """Diagnostic only: journals and observed fences, never raw command output."""
    result = {'mode': 'inspect', 'productionReady': False, 'status': 'inconclusive'}
    record = upgrade.read_journal()
    result['upgrades'] = upgrade.unfinished()
    if not record:
        result.update(status='not-started', advice=ADVICE['not-started'])
        return result
    result.update({k: record.get(k) for k in ('status', 'phase', 'failedPhase', 'planSha256', 'targetRevision', 'cancellation',
                                              'commandCompletion', 'containment', 'reason', 'migrationStarted',
                                              'identityStarted', 'reopened', 'attempts', 'previous')})
    result['pendingWrites'] = len(list(upgrade.directory.glob('*.pending')))
    advanced = record['migrationStarted'] or record['identityStarted']
    result['advice'] = ADVICE['complete'] if record['status'] == 'complete' else ADVICE['after-schema' if advanced else 'before-schema']
    try:
        result['gate'] = upgrade.gate_state()
        result['flyway'] = upgrade.recovery.metadata()['applicationSchema']
        result['migrationActorRunning'] = bool(upgrade.migration_actor_running())
    except BaseException:
        result['observation'] = 'unavailable'
    return result


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--stage', action='store_true', help='Write the reviewed bundle and pull pinned digests; no runtime change')
    mode.add_argument('--apply', action='store_true', help='Run the owner-authorized upgrade under the backup fence')
    mode.add_argument('--resume', action='store_true', help='Fence again and repeat the same claimed upgrade')
    mode.add_argument('--abandon', action='store_true', help='Return an unadvanced upgrade to the backup fence')
    mode.add_argument('--verify', action='store_true', help='Read-only comparison of live state with the activation')
    mode.add_argument('--inspect', action='store_true', help='Read-only journal and fence diagnostics')
    parser.add_argument('--target-revision', required=True)
    parser.add_argument('--forward-fix-of', help='Failed target this newer target replaces')
    parser.add_argument('--plan-sha256')
    parser.add_argument('--operational-revision')
    parser.add_argument('--adapter-fingerprint')
    parser.add_argument('--source-repository', type=Path, default=REPOSITORY)
    parser.add_argument('--backup', type=Path, help='Directory of the fresh canonical encrypted pair')
    parser.add_argument('--offhost-receipt', type=Path, help='Receipt of recovery pull or S3 publish for this pair')
    parser.add_argument('--restore-receipt', type=Path, help='Receipt of an isolated restore of this pair')
    parser.add_argument('--owner-reference', help='Owner authorization reference, for example an issue comment')
    args = parser.parse_args()
    control.signal_handlers()
    watcher = control.watch_session() if args.apply or args.resume or args.abandon else None
    try:
        require(os.geteuid() == 0 and sys.platform == 'linux', 'Local Linux root operation required.')
        require(not any(k.startswith('COMPOSE_') for k in os.environ), 'Ambient Compose overrides are forbidden.')
        integration.verify_local_docker()
        install.secure(ADMIN, mode=0o700, directory=True)
        upgrade = Upgrade(args.target_revision, operational=args.operational_revision, adapter=args.adapter_fingerprint,
                          repository=args.source_repository, forward_of=args.forward_fix_of).load()
        if args.inspect:
            result = inspect(upgrade)
        elif args.verify:
            result = upgrade.verify()
        elif args.stage:
            result = upgrade.stage(args.plan_sha256)
        elif args.apply:
            result = upgrade.apply(args.plan_sha256, backup=args.backup, offhost=args.offhost_receipt,
                                   restore=args.restore_receipt, reference=args.owner_reference)
        elif args.resume:
            result = upgrade.resume(args.plan_sha256)
        elif args.abandon:
            result = upgrade.abandon()
        else:
            result = upgrade.plan(args.plan_sha256)
        print(json.dumps(result))
        return 0
    except BaseException as error:
        # Raw Docker and environment output never reaches the terminal.
        print(json.dumps({'outcome': 'refused-or-failed', 'reason': refusal_text(error), 'productionReady': False}))
        return 1
    finally:
        if watcher:
            watcher.set()


if __name__ == '__main__':
    raise SystemExit(main())
