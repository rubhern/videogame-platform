#!/usr/bin/env python3
"""Prepare an unpublished #276 installation bundle locally; never execute it."""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile

import private_dev_promotion as trust
import public_production_deployment as deployment
import public_production_integration as integration

ROOT = Path(__file__).resolve().parents[1]
REVISION = '9ffc05a2ff30e261564ddbfd412826cdec504137'
FINGERPRINT = 'f41aa6308c84873f069db01e34d7f2d3be92278365c7fb73c1cb074b3ee4bb3b'
VERSION = '0.51.0-SNAPSHOT'
PROJECT = 'gameometro-production'
TARGET = Path('/srv/gameometro/installations/9ffc05a-private')
JAVA_TLS = ('-Djavax.net.ssl.trustStore=/run/private-tls/truststore.p12 '
            '-Djavax.net.ssl.trustStorePassword=changeit')
require = trust.require


def command(args, *, env=None):
    result = subprocess.run([str(a) for a in args], env=env, capture_output=True, timeout=120)
    require(result.returncode == 0, 'Preparation prerequisite failed; raw output withheld.')
    require(len(result.stdout) <= 32 * 1024 * 1024, 'Preparation output exceeds its bound.')
    return result.stdout


def private_caddy(source):
    for host in ('gameometro.com', 'auth.gameometro.com'):
        source, count = re.subn(r'^' + re.escape(host) + r' \{',
                               host + ' {\n\ttls /run/private-tls/edge.crt /run/private-tls/edge.key',
                               source, flags=re.M)
        require(count == 1, 'Candidate canonical TLS host is missing or ambiguous.')
    return source


def bind(source, target):
    return {'type': 'bind', 'source': str(source), 'target': target, 'read_only': True,
            'bind': {'create_host_path': False}}


def adapt(base, caddyfile, tls):
    """Only the reviewed private ingress/TLS delta; preserve all other candidate fields."""
    config = copy.deepcopy(base)
    # First private qualification must not restart an unaccepted/interrupted boot.
    for service in config['services'].values():
        service['restart'] = 'no'
    for network in config.get('networks', {}).values():
        network['enable_ipv6'] = False
        network.setdefault('driver_opts', {})['com.docker.network.bridge.gateway_mode_ipv4'] = 'nat'
    config['services']['caddy']['ports'] = []
    mounts = config['services']['caddy']['volumes']
    original = [v for v in mounts if v['target'] == '/etc/caddy/Caddyfile']
    require(len(original) == 1, 'Candidate edge mount is missing or ambiguous.')
    mounts[mounts.index(original[0])] = bind(caddyfile, '/etc/caddy/Caddyfile')
    for name in ('edge.crt', 'edge.key'):
        mounts.append(bind(tls / name, '/run/private-tls/' + name))
    app = config['services']['application']
    require('JAVA_TOOL_OPTIONS' not in app['environment'], 'Unexpected candidate JVM options.')
    app['environment']['JAVA_TOOL_OPTIONS'] = JAVA_TLS
    app['volumes'].append(bind(tls / 'truststore.p12', '/run/private-tls/truststore.p12'))
    return config


def validate(base, config, caddyfile, tls):
    # Exact equality makes any unreviewed override a refusal, including future services.
    require(config == adapt(base, caddyfile, tls), 'Unreviewed private runtime delta.')
    services = config['services']
    require({'telemetry', 'prometheus', 'grafana', 'node-exporter', 'blackbox'} <= set(services),
            'Complete observability overlay is required.')
    for service in services.values():
        require(not service.get('ports') and not service.get('build')
                and not service.get('privileged') and 'network_mode' not in service,
                'Private runtime forbids publications, builds, privileges and host networking.')
        require('@sha256:' in service['image'], 'Every runtime image must be immutable.')
    require(config['name'] == PROJECT, 'Unexpected private project.')
    require(services['migration']['image'] == services['application']['image'],
            'Migration must use the application image.')


def registry_images(images, runner=command):
    result = {}
    cache = {}
    for name, image in sorted(images.items()):
        # Remote OCI config, without pulling layers, creating containers or using a daemon.
        if image not in cache:
            cache[image] = json.loads(runner(['docker', 'buildx', 'imagetools', 'inspect', image,
                                            '--format', '{{json .Image}}']))
        value = cache[image]
        # Multi-platform dependency indexes expose one config per platform.
        value = value.get('linux/amd64', value)
        labels = value.get('config', {}).get('Labels') or {}
        require(value.get('os') == 'linux' and value.get('architecture') == 'amd64',
                'Published image is unavailable for linux/amd64.')
        if name in ('application', 'keycloak', 'caddy', 'catalogue-localizer'):
            require(labels.get('org.opencontainers.image.revision') == REVISION,
                    'Published image source differs from the accepted candidate.')
        if name == 'application':
            require(labels.get('org.opencontainers.image.version') == VERSION,
                    'Published application version differs from acceptance.')
        result[name] = {'image': image, 'os': value['os'], 'architecture': value['architecture'],
                        'user': value.get('config', {}).get('User') or '0',
                        'sourceRevision': labels.get('org.opencontainers.image.revision')}
    return result


def fingerprint(source):
    previous = deployment.ROOT
    try:
        deployment.ROOT = source
        return deployment.local_configuration()
    finally:
        deployment.ROOT = previous


def prepare(output, env_file, tls):
    require(not output.exists() and output.parent.is_dir(), 'A new output directory is required.')
    require(output.is_absolute() and tls.is_absolute() and env_file.is_absolute(),
            'Absolute local paths are required.')
    require(tls == Path('/etc/gameometro/production/private-tls'), 'Unexpected private TLS directory.')
    require(ROOT not in output.parents and output != ROOT, 'Bundle must stay outside the checkout.')
    integration.verify_local_docker()
    campaign = json.loads((ROOT / 'docs/research/public-production-candidate-evidence/final-9ffc05a-campaign.json').read_text())
    require(campaign['sourceRevision'] == REVISION and campaign['runtimeSourceFingerprint'] == FINGERPRINT,
            'Acceptance identities changed.')
    api = trust.GitHub(command(['gh', 'auth', 'token']).decode().strip())
    selected = integration.resolve(api, REVISION)
    require(selected['images'] == campaign['publications'], 'Live publications differ from acceptance.')
    # No host operation is exposed, even if every preparation check passes.
    output.mkdir(mode=0o700)
    source = output / 'candidate'
    source.mkdir(mode=0o700)
    archive = command(['git', '-C', ROOT, 'archive', REVISION, 'deploy', 'docker', 'scripts', 'tools'])
    with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
        members = bundle.getmembers()
        require(len(members) <= 10000 and all(m.isfile() or m.isdir() for m in members),
                'Candidate archive contains unsupported sources.')
        bundle.extractall(source, filter='data')
    require(fingerprint(source) == FINGERPRINT, 'Exported operational sources differ from signed candidate.')
    values = {}
    # Parse non-secret inputs as data; never source a shell file or inherit Compose overrides.
    for line in env_file.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, sep, value = line.partition('=')
        require(sep and re.fullmatch('[A-Z][A-Z0-9_]*', key) and key not in values
                and value and not any(c in value for c in ('$', '"', "'", '\x00')),
                'Invalid or duplicate non-secret runtime input.')
        values[key] = value
    expected_keys = set(re.findall(r'^([A-Z][A-Z0-9_]*)=',
                                  (source / 'deploy/public-production/runtime.env.example').read_text(), re.M))
    require(set(values) == expected_keys, 'Supply exactly the candidate runtime.env input inventory.')
    for key, path in {
        'PRODUCTION_SECRETS_DIR': '/etc/gameometro/production/secrets',
        'CATALOGUE_TRANSLATION_MODEL_DIR': '/var/lib/gameometro/production/catalogue-models/tcbig-initial',
        'PRODUCTION_METRICS_DIR': '/var/lib/gameometro/production/metrics',
    }.items():
        require(values[key] == path, 'Unexpected protected host input path.')
    require(re.fullmatch('[1-9][0-9]{0,9}', values['PRODUCTION_SECRETS_GID']), 'Invalid secret consumer group.')
    for name, image in selected['images'].items():
        values[name.upper().replace('-', '_') + '_IMAGE'] = image
    values['APPLICATION_VERSION'] = VERSION
    require(values['PRODUCTION_TELEGRAM_ENABLED'] == 'false', 'Owner notification activation is separate.')
    runtime = output / 'runtime.env'
    runtime.write_text(''.join(f'{k}={v}\n' for k, v in sorted(values.items())))
    base = json.loads(command(['docker', 'compose', '--env-file', runtime,
                              '-f', source / 'deploy/public-production/compose.yaml',
                              '-f', source / 'deploy/public-production/compose.observability.yaml',
                              '--profile', '*', 'config', '--format', 'json'],
                             env={k: os.environ[k] for k in ('PATH', 'HOME', 'DOCKER_CONFIG') if k in os.environ}))
    # Render locally, but bind only the future, separate immutable host bundle.
    # The host's existing /srv/gameometro/repo checkout is never an operational input.
    for service in base['services'].values():
        for mount in service.get('volumes', []):
            if mount['type'] == 'bind' and Path(mount['source']).is_relative_to(source):
                mount['source'] = str(TARGET / 'candidate' / Path(mount['source']).relative_to(source))
    caddyfile = output / 'Caddyfile.private'
    caddyfile.write_text(private_caddy((source / 'deploy/public-production/caddy/Caddyfile').read_text()))
    # Public configuration bytes must be readable after the daemon bind-mounts the
    # file into a capability-dropped container; the bundle's parent remains 0700.
    caddyfile.chmod(0o644)
    config = adapt(base, TARGET / caddyfile.name, tls)
    validate(base, config, TARGET / caddyfile.name, tls)
    images = {name: service['image'] for name, service in config['services'].items()}
    checked = registry_images(images)
    rendered = json.dumps(config, sort_keys=True, indent=2) + '\n'
    (output / 'compose.private.json').write_text(rendered)
    # The baseline/delta and every external bind remain reviewable; no secret bytes are read.
    (output / 'compose.candidate.json').write_text(json.dumps(base, sort_keys=True, indent=2) + '\n')
    receipt = {'mode': 'local-preparation-only', 'executionEnabled': False, 'productionReady': False,
               'observedAt': datetime.now(timezone.utc).isoformat(),
               'targetDirectory': str(TARGET),
               'sourceRevision': REVISION, 'candidateRuntimeFingerprint': FINGERPRINT,
               'adapterSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               'configurationSha256': hashlib.sha256(rendered.encode()).hexdigest(),
               'privateCaddySha256': hashlib.sha256(caddyfile.read_bytes()).hexdigest(),
               'images': checked, 'publishedPorts': [],
               'pending': ['published operational rollout compatibility approval',
                           'renewed security disposition before real operation',
                           'host freshness, routes, socket, effective IPv4/IPv6 denial',
                           'secret ownership and per-consumer readability',
                           'model checksums and private TLS positive/negative tests',
                           'independent recovery material, backup/S3 and restore qualification',
                           'explicit private bootstrap authorization; public acceptance remains separate']}
    (output / 'preparation.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New local protected bundle directory')
    parser.add_argument('--env-file', type=Path, required=True, help='Non-secret candidate input inventory')
    parser.add_argument('--tls-dir', type=Path, required=True, help='Future protected TLS input directory; not read')
    args = parser.parse_args()
    try:
        prepare(args.output, args.env_file, args.tls_dir)
    except trust.Refused as error:
        print(str(error))
        print('Preparation refused. No runtime operation was attempted.')
        return 1
    except (OSError, ValueError, subprocess.SubprocessError):
        print('Preparation refused; inspect inputs privately. No runtime operation was attempted.')
        return 1
    print('Private bundle prepared locally. Execution disabled; review preparation.json and operator prerequisites.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
