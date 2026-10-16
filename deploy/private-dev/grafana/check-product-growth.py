#!/usr/bin/env python3
"""Disposable proof of the actual optimized private Keycloak image with PostgreSQL."""
import json
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
from importlib.machinery import SourceFileLoader

alerts = SourceFileLoader('alerts', str(Path(__file__).with_name('check-alerts.py'))).load_module()
run = alerts.run
DEPLOY = alerts.DEPLOY


def build_image(build_args, rebuild=False):
    command = ['docker', 'build', '--quiet', '--file', str(DEPLOY / 'keycloak/Dockerfile')]
    for name, value in sorted(build_args.items()):
        command += ['--build-arg', name + '=' + value]
    if rebuild:
        command.append('--no-cache')
    result = subprocess.run(command + [str(alerts.ROOT)], text=True, capture_output=True, timeout=300)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip().splitlines()[-1]


def main():
    alerts.local_engine()
    compose_base = ['docker', 'compose', '--env-file', str(DEPLOY / 'runtime.env.example'), '--file', str(DEPLOY / 'compose.yaml')]
    base = json.loads(run(compose_base + ['config', '--format', 'json']))
    growth = json.loads(run(compose_base + ['--file', str(DEPLOY / 'compose.product-growth.yaml'), 'config', '--format', 'json']))
    changed = {name for name in base['services'] if base['services'][name] != growth['services'][name]}
    assert changed == {'keycloak', 'prometheus'}
    assert set(growth['services']['keycloak']['networks']) == set(base['services']['keycloak']['networks']) | {'telemetry'}
    assert growth['services']['keycloak']['environment'] == base['services']['keycloak']['environment']
    for name in changed:
        for field in ('ports', 'mem_limit', 'cpus', 'pids_limit', 'secrets'):
            assert base['services'][name].get(field) == growth['services'][name].get(field)
    assert 'ports' not in growth['services']['prometheus']
    print('Growth overlay changes only Keycloak network/Prometheus scrape; base identity/listeners/secrets/limits preserved.', flush=True)
    runner = re.search(r'playwright_image="([^\"]+)"', (alerts.ROOT / 'scripts/validate-identity.sh').read_text())[1]
    mailpit = re.search(r'image: (\S+)', (DEPLOY / 'compose.mail.yaml').read_text())[1]
    build_args = base['services']['keycloak']['build']['args']
    assert build_args == {'KEYCLOAK_EVENT_METRICS_USER_ENABLED': 'true'}
    # Local development and the separate production-identity proof share this recipe.
    # Their default must remain disabled; there is still just one existing private image.
    default_image = build_image({})
    default_env = json.loads(run(['docker', 'image', 'inspect', default_image]))[0]['Config']['Env']
    assert 'KC_EVENT_METRICS_USER_ENABLED=false' in default_env
    image = build_image(build_args)
    with tempfile.TemporaryDirectory(prefix='vgp-growth-proof-') as directory:
        temp = Path(directory)
        temp.chmod(0o755)
        realm = json.loads((alerts.ROOT / 'docker/keycloak/import/videogame-platform-realm.json').read_text())
        # Isolated HTTP fixture, with email verification additionally enabled to exercise both events.
        # Production realm and private-dev import are never edited or mounted writable.
        realm.update({'sslRequired': 'none', 'verifyEmail': True})
        realm['smtpServer']['from'] = 'fixture@example.invalid'
        realm['clients'].append({'clientId': 'fixture', 'enabled': True, 'publicClient': True,
                                 'standardFlowEnabled': True, 'redirectUris': ['http://keycloak:8080/callback']})
        realm['users'] = [{'username': 'seed', 'email': 'seed@example.invalid', 'enabled': True, 'emailVerified': True}]
        (temp / 'realm.json').write_text(json.dumps(realm))
        credential = secrets.token_urlsafe(24)
        for name in ('database', 'admin', 'bff'):
            (temp / name).write_text(credential)
            (temp / name).chmod(0o644)
        # Exercise the installed entrypoint and start --optimized, not start-dev or a rebuilt H2 substitute.
        keycloak = {k: v for k, v in base['services']['keycloak'].items()
                    if k in ('entrypoint', 'read_only', 'cap_drop', 'security_opt', 'tmpfs', 'mem_limit', 'cpus', 'pids_limit')}
        env = dict(base['services']['keycloak']['environment'])
        env.update({'KC_BOOTSTRAP_ADMIN_USERNAME': 'fixture-admin', 'KC_DB_URL': 'jdbc:postgresql://postgres:5432/keycloak',
                    'KC_DB_USERNAME': 'fixture', 'KC_HOSTNAME': 'http://keycloak:8080',
                    'APPLICATION_PUBLIC_ORIGIN': 'http://application:8080'})
        assert set(env['KC_EVENT_METRICS_USER_EVENTS'].split(',')) == {
            'login', 'register', 'verify_email', 'send_verify_email', 'send_reset_password',
            'reset_password', 'user_disabled_by_temporary_lockout'}
        assert env['KC_EVENT_METRICS_USER_TAGS'] == 'realm'
        keycloak.update({'image': image, 'environment': env, 'networks': ['fixture'],
                         'volumes': [str(temp / 'realm.json') + ':/opt/keycloak/data/import/realm.json:ro',
                                     str(DEPLOY / 'bin/run-keycloak') + ':/opt/videogame-platform/bin/run-keycloak:ro'],
                         'secrets': ['keycloak_db_password', 'keycloak_admin_password', 'keycloak_bff_client_secret'],
                         'depends_on': {'postgres': {'condition': 'service_healthy'}}})
        definition = {'services': {
            'keycloak': keycloak,
            'grafana': {'image': base['services']['grafana']['image'], 'networks': ['fixture'],
                        'environment': {'GF_SECURITY_ADMIN_USER': 'fixture-admin', 'GF_SECURITY_ADMIN_PASSWORD': credential,
                                        'GF_AUTH_ANONYMOUS_ENABLED': 'false', 'GF_PLUGINS_PREINSTALL_DISABLED': 'true',
                                        'GF_ANALYTICS_REPORTING_ENABLED': 'false', 'GF_UNIFIED_ALERTING_ENABLED': 'true'},
                        'volumes': [str(DEPLOY / 'grafana/provisioning/datasources/prometheus.yaml') + ':/etc/grafana/provisioning/datasources/prometheus.yaml:ro',
                                    str(DEPLOY / 'grafana/provisioning/alerting/product-activity.json') + ':/etc/grafana/provisioning/alerting/product-activity.json:ro'],
                        'mem_limit': '512m', 'cpus': 0.5, 'pids_limit': 128},
            'postgres': {'image': base['services']['postgres']['image'], 'networks': ['fixture'],
                         'environment': {'POSTGRES_DB': 'keycloak', 'POSTGRES_USER': 'fixture', 'POSTGRES_PASSWORD': credential},
                         'volumes': ['postgres-data:/var/lib/postgresql/18/docker'], 'mem_limit': '256m', 'cpus': 1, 'pids_limit': 128,
                         'healthcheck': {'test': ['CMD', 'pg_isready', '-U', 'fixture', '-d', 'keycloak'],
                                         'interval': '1s', 'timeout': '3s', 'retries': 30}},
            'prometheus': {'image': alerts.prometheus_image(),
                           'command': ['--config.file=/etc/prometheus/prometheus.yaml', '--storage.tsdb.path=/prometheus'],
                           'volumes': [str(DEPLOY / 'prometheus') + ':/etc/prometheus:ro'],
                           'tmpfs': ['/prometheus:rw,nosuid,size=32m,uid=65534,gid=65534'],
                           'networks': ['fixture'], 'mem_limit': '128m', 'cpus': 0.5, 'pids_limit': 32},
            'mailpit': {'image': mailpit, 'networks': ['fixture'], 'mem_limit': '128m', 'cpus': 0.25, 'pids_limit': 64},
            'browser': {'image': runner, 'entrypoint': ['node', '/proof.mjs'],
                        'environment': {'FIXTURE_PASSWORD': credential},
                        'volumes': [str(Path(__file__).with_name('product-growth-proof.mjs')) + ':/proof.mjs:ro',
                                    str(alerts.ROOT) + ':/work:ro', str(temp) + ':/fixture'],
                        'networks': ['fixture'], 'mem_limit': '768m', 'cpus': 1, 'pids_limit': 256, 'profiles': ['test']}},
            'networks': {'fixture': {'internal': True}}, 'volumes': {'postgres-data': {}},
            'secrets': {name: {'file': str(temp / filename)} for name, filename in (
                ('keycloak_db_password', 'database'), ('keycloak_admin_password', 'admin'), ('keycloak_bff_client_secret', 'bff'))}}
        source = temp / 'compose.json'
        source.write_text(json.dumps(definition))
        project = 'vgp-growth-proof-' + secrets.token_hex(4)
        compose = ['docker', 'compose', '--project-name', project, '--file', str(source)]
        try:
            run(compose + ['up', '--detach', 'keycloak', 'mailpit', 'prometheus', 'grafana'])
            subprocess.run(compose + ['run', '--rm', 'browser'], check=True, timeout=240)
            # A fresh augmentation followed by container recreation must preserve PostgreSQL identities.
            definition['services']['keycloak']['image'] = build_image(build_args, rebuild=True)
            source.write_text(json.dumps(definition))
            run(compose + ['up', '--detach', '--no-deps', '--force-recreate', 'keycloak'])
            subprocess.run(compose + ['run', '--rm', '--env', 'PROOF_RESTART=true', 'browser'], check=True, timeout=150)
            logs = run(compose + ['logs', '--no-color', 'keycloak'])
            assert 'start-dev' not in logs and 'build time options have values that differ' not in logs
            print('Optimized image rebuild/recreation, PostgreSQL account/realm persistence, registration/login/recovery and bounded metrics passed.', flush=True)
        except Exception:
            # Never emit identity payloads, mail links, or fixture credentials.
            logs = run(compose + ['logs', '--no-color', '--tail', '35', 'keycloak', 'postgres'])
            print(logs.replace(credential, '[redacted]'), flush=True)
            raise
        finally:
            run(compose + ['down', '--volumes', '--remove-orphans'])


if __name__ == '__main__':
    main()
