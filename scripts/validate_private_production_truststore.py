#!/usr/bin/env python3
"""Produce public-certificate evidence using the exact accepted application's JVM."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import ssl

import private_dev_promotion as trust
import public_production_integration as integration
import private_production_control as control

ROOT = Path(__file__).resolve().parents[1]
REVISION = '9ffc05a2ff30e261564ddbfd412826cdec504137'
IMAGE = 'ghcr.io/rubhern/videogame-platform@sha256:16e393264891ffa69e27effe6b7064598830438507f4bc6466a6b73276a22eb7'
require = trust.require


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def certificates(text):
    values = re.findall(r'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', text, re.S)
    return sorted({hashlib.sha256(ssl.PEM_cert_to_DER_cert(v)).hexdigest() for v in values})


def jvm_entries(output):
    text = output.decode('utf-8')
    require('PrivateKeyEntry' not in text and 'SecretKeyEntry' not in text, 'Truststore must contain only public trusted certificates.')
    result = certificates(text)
    require(result and text.count('Entry type: trustedCertEntry') == len(re.findall('-----BEGIN CERTIFICATE-----', text)),
            'JVM did not recognize every public certificate as a trust anchor.')
    return result


def validate(directory, receipt, *, runner=control.run):
    integration.verify_local_docker()
    require(directory.is_absolute() and not receipt.exists(), 'Absolute public input directory and new receipt required.')
    for name in ('ca.crt', 'truststore.p12'):
        path = directory / name
        require(path.is_file() and path.resolve() == path and 0 < path.stat().st_size <= 2 * 1024 * 1024,
                'Missing, symlinked or oversized public trust input.')
    metadata = json.loads(runner(['docker', 'image', 'inspect', IMAGE]))[0]
    require(metadata['Os'] == 'linux' and metadata['Architecture'] == 'amd64'
            and metadata['Config']['Labels']['org.opencontainers.image.revision'] == REVISION, 'Accepted image must already be staged locally.')
    args = ['docker', 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges:true', '--log-driver', 'none', '--memory', '256m', '--pids-limit', '64',
            '--entrypoint', 'keytool']
    java = ['-J-Duser.language=en', '-J-Duser.country=US', '-list', '-rfc', '-storepass', 'changeit']
    baseline = jvm_entries(runner([*args, IMAGE, *java, '-cacerts']))
    observed = jvm_entries(runner([*args, '--mount', f'type=bind,source={directory / "truststore.p12"},target=/probe.p12,readonly',
                                  IMAGE, *java, '-keystore', '/probe.p12', '-storetype', 'PKCS12']))
    ca = certificates((directory / 'ca.crt').read_text())
    require(len(ca) == 1 and set(baseline) <= set(observed) and set(ca) <= set(observed),
            'The store must retain every accepted default anchor and add the private CA.')
    value = {'schema': 1, 'kind': 'real-jvm-public-truststore-validation', 'outcome': 'success',
             'image': IMAGE, 'sourceRevision': REVISION, 'validatorSha256': digest(Path(__file__)),
             'truststoreSha256': digest(directory / 'truststore.p12'), 'caSha256': digest(directory / 'ca.crt'),
             'defaultAnchors': baseline, 'observedAnchors': observed, 'privateAnchors': ca,
             'productionReady': False}
    control.durable(receipt, value)
    return value


def verify_receipt(value, store, ca, image):
    require(value.get('schema') == 1 and value.get('kind') == 'real-jvm-public-truststore-validation'
            and value.get('outcome') == 'success' and value.get('image') == image == IMAGE
            and value.get('sourceRevision') == REVISION and value.get('validatorSha256') == digest(Path(__file__))
            and value.get('truststoreSha256') == digest(store) and value.get('caSha256') == digest(ca),
            'Truststore evidence/source/artifact binding differs.')
    base = value.get('defaultAnchors', []); observed = value.get('observedAnchors', [])
    private = certificates(ca.read_text())
    require(base and observed and private and all(re.fullmatch('[0-9a-f]{64}', v) for v in base + observed)
            and set(base) <= set(observed) and set(private) <= set(observed)
            and value.get('privateAnchors') == private, 'Default/private JVM anchor inventory incomplete.')
    # Decode the actual PKCS12 certificate inventory too; Java's entry semantics
    # are bound by the independently reviewed receipt hash and exact store bytes.
    public = control.run(['openssl', 'pkcs12', '-in', store, '-passin', 'pass:changeit', '-nokeys']).decode()
    require(certificates(public) == observed, 'PKCS12 inventory differs from real-JVM evidence.')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    try:
        validate(args.directory, args.receipt)
        print('Accepted JVM truststore validation passed; review the protected receipt hash.')
        return 0
    except Exception:
        print('Truststore validation refused; no application was started and no secret was generated.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
