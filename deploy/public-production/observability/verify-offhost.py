#!/usr/bin/env python3
"""Derive a monitoring attestation on the independent backup destination.

The existing recovery tool proves ciphertext integrity and full restore/replay.
A separate owner record attests storage/administration isolation; this tool cannot
prove that physical or privilege boundary. Copy only the sanitized output to the
source's protected observability input. No network or credentials are used here.
"""
import argparse
import datetime as dt
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
from importlib import import_module
sampler=import_module('sample-host')
loader=importlib.machinery.SourceFileLoader('production_recovery_verification',str(Path(__file__).resolve().parents[1]/'bin/recovery'))
spec=importlib.util.spec_from_loader(loader.name,loader);recovery=importlib.util.module_from_spec(spec);loader.exec_module(recovery)


def attest(manifest,pull,restore,isolation,now):
    if not (pull.get('kind')=='encrypted-pull' and pull.get('outcome')=='success' and pull.get('ciphertextIntegrity') is True
            and restore.get('kind')=='recovery-rehearsal' and restore.get('outcome')=='success'
            and restore.get('deletionReplay') is True and restore.get('readiness') is True
            and restore.get('scope')=='owner-reconstructed-target'
            and manifest['backupId']==pull.get('backupId')==restore.get('backupId')
            and isolation.get('environment')=='production' and isolation.get('storageIsolationVerified') is True
            and 0 <= now-sampler.timestamp(isolation['reviewedAt']) <= 30*86400
            and 0 <= now-sampler.timestamp(manifest['recoveryPoint']) <= 35*86400):
        raise ValueError('Off-host evidence does not verify')
    return {'kind':'offhost-verification','environment':'production','verifiedAt':dt.datetime.fromtimestamp(now,dt.timezone.utc).isoformat(),
            'recoveryPoint':manifest['recoveryPoint'],'ciphertextIntegrity':True,'storageIsolationVerified':True,'independentRecoveryVerified':True}


def main():
    parser=argparse.ArgumentParser(__doc__)
    for flag in ('backup','pull-receipt','restore-receipt','isolation-record','output'):parser.add_argument('--'+flag,type=Path,required=True)
    a=parser.parse_args()
    try:
        m=recovery.verify(a.backup)
        restore=sampler.protected_json(a.restore_receipt)
        if restore.get('manifestSha256') != recovery.digest(a.backup/'manifest.json'):
            raise ValueError('Recovery manifest mismatch')
        result=attest(m,sampler.protected_json(a.pull_receipt),restore,sampler.protected_json(a.isolation_record),__import__('time').time())
        # Protected transport input: this status is an owner attestation, not a public receipt.
        sampler.atomic(a.output,json.dumps(result)+'\n');a.output.chmod(0o600)
    except Exception:
        raise SystemExit('Off-host attestation refused; evidence values withheld.') from None


if __name__=='__main__':main()
