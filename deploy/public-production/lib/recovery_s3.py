"""Bounded S3 transport for recovery's existing encrypted inventory; no scheduler.

Uploader, observer and expiry actor require separate credentials. Expiry execution
is explicit; local preparation does not authorize provider or production activation.
"""
import base64
import datetime as dt
import email.utils
import hashlib
import io
import json
from pathlib import Path
import re
import time
import uuid
import urllib.request
import urllib.error

VERSION = "0.2.0"

PART = 16 * 1024 * 1024
MAX_VERSIONS = 2048
MAX_JSON = 128 * 1024
MAX_CALLS = 20000
MAX_STATE = 4 * 1024 * 1024
# Owner-approved D7 storage values (#276). Admission thresholds, not a provider quota.
WARNING_BYTES = 180 * 1024 ** 3
LIMIT_BYTES = 200 * 1024 ** 3
# Findings a capture may proceed with: none shows the rolling store itself is unsafe.
ADMITTED_ALERTS = {'capacity-warning', 'integrity-backlog', 'integrity-critical', 'missed-copy-warning', 'restore-missing',
                   'restore-warning', 'restore-critical', 'incomplete-attempt'}
KEY = re.compile(r'pairs/(\d{8}T\d{6}Z)/([0-9a-f]{32})/([0-9a-f]{32})/([^/]+)')


class Store:
    def __init__(self, core, client, bucket, provider_clock):
        self.c, self.s3, self.bucket = core, client, bucket
        self.deadline = time.monotonic() + 1800
        self.provider_clock = provider_clock
        self.calls = 0

    def call(self, operation, **kw):
        self.c.require(time.monotonic() < self.deadline and self.calls < MAX_CALLS, 'S3 operation budget exhausted.')
        self.calls += 1
        response = getattr(self.s3, operation)(Bucket=self.bucket, **kw)
        self.c.require(time.monotonic() < self.deadline, 'S3 operation budget exhausted.')
        self.c.require(response['ResponseMetadata']['HTTPStatusCode'] == (204 if operation == 'delete_object' else 200),
                       'Unexpected S3 response status.')
        date = response['ResponseMetadata']['HTTPHeaders']['date']
        remote = email.utils.parsedate_to_datetime(date)
        self.c.require(abs((self.c.utc() - remote).total_seconds()) <= 60, 'S3 clock skew exceeds 60 seconds.')
        self.remote_now = remote
        return response

    def admission(self, point):
        self.c.require(0 <= (self.c.utc() - point).total_seconds() < 6 * 3600,
                       'S3 upload admission window closed.')

    def put(self, key, stream, size, point):
        self.admission(point)
        if size <= PART:
            data = stream.read(PART + 1)
            self.c.require(len(data) == size, 'S3 upload size changed.')
            self.admission(point)
            response = self.call('put_object', Key=key, Body=data, ContentLength=size, ServerSideEncryption='AES256',
                                 ContentMD5=base64.b64encode(hashlib.md5(data, usedforsecurity=False).digest()).decode())
        else:
            upload = None
            try:
                # No retention/hold headers: only the prequalified bucket default.
                upload = self.call('create_multipart_upload', Key=key, ServerSideEncryption='AES256')['UploadId']
                parts = []
                for number in range(1, 10001):
                    self.admission(point)
                    data = stream.read(PART)
                    if not data:
                        break
                    response = self.call('upload_part', Key=key, UploadId=upload, PartNumber=number, Body=data,
                                         ContentMD5=base64.b64encode(hashlib.md5(data, usedforsecurity=False).digest()).decode())
                    parts.append({'PartNumber': number, 'ETag': response['ETag']})
                self.c.require(stream.read(1) == b'' and len(parts) < 10000, 'Multipart part bound exceeded.')
                self.admission(point)
                response = self.call('complete_multipart_upload', Key=key, UploadId=upload, MultipartUpload={'Parts': parts})
                upload = None
            finally:
                if upload is not None:
                    # Attempt abort even after the operation budget is exhausted.
                    self.s3.abort_multipart_upload(Bucket=self.bucket, Key=key, UploadId=upload)
        self.admission(point)
        version = response.get('VersionId')
        self.c.require(isinstance(version, str) and version not in ('', 'null'), 'Missing durable S3 version acknowledgement.')
        return version

    def publish(self, backup, admission, max_bytes):
        self.c.require(abs((self.c.utc() - self.provider_clock()).total_seconds()) <= 60,
                       'S3 clock skew exceeds 60 seconds.')
        m = self.c.verify(backup)
        point = self.c.timestamp(m['recoveryPoint'])
        self.admission(point)
        self.c.private(admission)
        self.c.require(admission.stat().st_size <= 4 * 1024 * 1024, 'Oversized S3 admission evidence.')
        proof = json.loads(admission.read_text())
        size = sum((backup / n).stat().st_size for n in self.c.FILES)
        self.c.require(proof['kind'] in ('s3-observation', 's3-admission') and proof['bucket'] == self.bucket
                       and proof['outcome'] == 'success'
                       and set(proof['alerts']) <= ADMITTED_ALERTS
                       and 0 <= (self.c.utc() - self.c.timestamp(proof['verifiedAt'])).total_seconds() <= 3600
                       and type(proof['retainedBytes']) is int and proof['retainedBytes'] >= 0
                       and 0 < max_bytes and proof['retainedBytes'] + size + MAX_JSON <= max_bytes,
                       'Fresh independent capacity admission required or storage bound exceeded.')
        prefix = f"pairs/{point.strftime('%Y%m%dT%H%M%SZ')}/{m['backupId']}/{uuid.uuid4().hex}/"
        objects = {}
        for name in self.c.FILES:
            path = backup / name
            with path.open('rb') as stream:
                objects[name] = {'key': prefix + name, 'versionId': self.put(prefix + name, stream, path.stat().st_size, point),
                                 'bytes': path.stat().st_size, 'sha256': self.c.digest(path)}
        receipt = {'kind': 's3-publish', 'adapterVersion': VERSION, 'bucket': self.bucket, 'backupId': m['backupId'],
                   'recoveryPoint': m['recoveryPoint'], 'objects': objects,
                   'manifestSha256': self.c.digest(backup / 'manifest.json')}
        data = json.dumps(receipt, sort_keys=True).encode()
        version = self.put(prefix + 'receipt.json', io.BytesIO(data), len(data), point)
        return {**receipt, 'receiptObject': {'key': prefix + 'receipt.json', 'versionId': version},
                'ciphertextIntegrity': False, 'independentRecoveryVerified': False}

    def inventory(self):
        entries, cursor, seen, cursors = [], {}, set(), set()
        for _ in range(32):
            page = self.call('list_object_versions', MaxKeys=128, **cursor)
            self.c.require(page.get('Name') == self.bucket and type(page.get('IsTruncated')) is bool
                           and not page.get('CommonPrefixes') and not page.get('Prefix')
                           and not page.get('Delimiter') and not page.get('EncodingType'), 'Incomplete S3 inventory.')
            self.c.require(all(isinstance(page.get(group, []), list) for group in ('Versions', 'DeleteMarkers'))
                           and sum(len(page.get(group, [])) for group in ('Versions', 'DeleteMarkers')) <= 128,
                           'Invalid S3 inventory page.')
            for marker, group in ((False, 'Versions'), (True, 'DeleteMarkers')):
                for item in page.get(group, []):
                    self.c.require(len(entries) < MAX_VERSIONS, 'S3 inventory overflow; reconcile before continuing.')
                    match = KEY.fullmatch(item['Key'])
                    self.c.require(match and match[4] in (*self.c.FILES, 'receipt.json')
                                   and isinstance(item.get('VersionId'), str)
                                   and 0 < len(item['VersionId']) <= 1024
                                   and item['VersionId'] != 'null', 'Unknown S3 inventory entry.')
                    modified = item.get('LastModified')
                    self.c.require(isinstance(modified, dt.datetime) and modified.utcoffset() is not None
                                   and modified <= min(self.c.utc(), self.remote_now) + dt.timedelta(seconds=60)
                                   and (marker or (type(item.get('Size')) is int and item['Size'] >= 0)),
                                   'Invalid S3 version metadata.')
                    point = dt.datetime.strptime(match[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
                    self.c.require(modified >= point, 'Invalid S3 version chronology.')
                    identity = (item['Key'], item['VersionId'])
                    self.c.require(identity not in seen, 'Repeated S3 inventory entry.')
                    seen.add(identity)
                    entries.append({**item, 'marker': marker})
            if not page['IsTruncated']:
                self.c.require(not page.get('NextKeyMarker') and not page.get('NextVersionIdMarker'),
                               'Ambiguous S3 pagination end.')
                return entries
            next_cursor = (page.get('NextKeyMarker'), page.get('NextVersionIdMarker'))
            self.c.require(all(isinstance(v, str) and v for v in next_cursor)
                           and next_cursor in seen and next_cursor not in cursors, 'Ambiguous S3 pagination cursor.')
            cursors.add(next_cursor)
            cursor = dict(zip(('KeyMarker', 'VersionIdMarker'), next_cursor))
        raise self.c.Refused('S3 pagination budget exhausted.')

    def configuration(self, expected_lifecycle):
        self.c.require(isinstance(expected_lifecycle, str) and re.fullmatch('[0-9a-f]{64}', expected_lifecycle),
                       'Reviewed S3 lifecycle fingerprint required.')
        lock = self.call('get_object_lock_configuration')['ObjectLockConfiguration']
        self.c.require(lock == {'ObjectLockEnabled': 'Enabled', 'Rule': {'DefaultRetention': {'Mode': 'COMPLIANCE', 'Days': 33}}},
                       'S3 lock configuration drift.')
        versioning = self.call('get_bucket_versioning')
        self.c.require(versioning.get('Status') == 'Enabled' and versioning.get('MFADelete', 'Disabled') == 'Disabled',
                       'S3 versioning drift.')
        lifecycle = self.call('get_bucket_lifecycle_configuration')['Rules']
        self.c.require(hashlib.sha256(json.dumps(lifecycle, sort_keys=True).encode()).hexdigest() == expected_lifecycle,
                       'S3 lifecycle configuration drift.')

    def protection(self, entry):
        args = {'Key': entry['Key'], 'VersionId': entry['VersionId']}
        retention = self.call('get_object_retention', **args)['Retention']
        hold = self.call('get_object_legal_hold', **args)['LegalHold']['Status']
        point = dt.datetime.strptime(KEY.fullmatch(entry['Key'])[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
        modified, until = entry['LastModified'], retention['RetainUntilDate']
        self.c.require(isinstance(until, dt.datetime) and until.utcoffset() is not None
                       and retention['Mode'] == 'COMPLIANCE' and hold == 'OFF'
                       and 0 <= (modified - point).total_seconds() <= 21600
                       # A multipart lock may start at initiation, before LastModified, but
                       # never before the recovery point: anchor the minimum there.
                       and until >= point + dt.timedelta(days=33)
                       and until - modified <= dt.timedelta(days=34, minutes=5)
                       and until <= point + dt.timedelta(days=34, hours=6)
                       and modified <= self.c.utc() + dt.timedelta(seconds=60), 'S3 protection invariant violated.')
        return point, until

    def download(self, key, version, output, max_bytes, expected=None):
        response = self.call('get_object', Key=key, VersionId=version)
        body = response['Body']
        try:
            self.c.require(response.get('VersionId') == version and 0 <= response['ContentLength'] <= max_bytes,
                           'S3 exact version or byte bound mismatch.')
            size, sha = 0, hashlib.sha256()
            while True:
                self.c.require(time.monotonic() < self.deadline, 'S3 stream budget exhausted.')
                data = body.read(min(1024 * 1024, max_bytes - size + 1))
                if not data:
                    break
                size += len(data)
                self.c.require(size <= max_bytes, 'S3 stream byte bound exceeded.')
                sha.update(data); output.write(data)
            self.c.require(size == response['ContentLength'], 'Truncated S3 version.')
            if expected:
                self.c.require(size == expected['bytes'] and sha.hexdigest() == expected['sha256'], 'S3 integrity mismatch.')
            return size
        finally:
            body.close()

    def receipt(self, entry):
        output = io.BytesIO()
        self.download(entry['Key'], entry['VersionId'], output, MAX_JSON)
        proof = json.loads(output.getvalue())
        prefix = entry['Key'].rsplit('/', 1)[0] + '/'
        match = KEY.fullmatch(entry['Key'])
        point = self.c.timestamp(proof['recoveryPoint'])
        self.c.require(proof['kind'] == 's3-publish' and proof['bucket'] == self.bucket
                       and proof['backupId'] == match[2] and point.strftime('%Y%m%dT%H%M%SZ') == match[1]
                       and set(proof['objects']) == set(self.c.FILES), 'Invalid S3 pair receipt.')
        for name, obj in proof['objects'].items():
            self.c.require(obj['key'] == prefix + name and isinstance(obj['versionId'], str)
                           and obj['versionId'] not in ('', 'null') and type(obj['bytes']) is int and obj['bytes'] > 0
                           and re.fullmatch('[0-9a-f]{64}', obj['sha256']), 'Invalid S3 object reference.')
        return proof

    def fetch(self, proof, destination, max_bytes):
        for name, obj in proof['objects'].items():
            match = KEY.fullmatch(obj['key'])
            self.c.require(match and match[4] == name and name in self.c.FILES
                           and isinstance(obj['versionId'], str) and obj['versionId'] not in ('', 'null')
                           and type(obj['bytes']) is int and obj['bytes'] > 0
                           and re.fullmatch('[0-9a-f]{64}', obj['sha256']), 'Invalid S3 fetch reference.')
        self.c.require(proof['bucket'] == self.bucket and set(proof['objects']) == set(self.c.FILES)
                       and sum(o['bytes'] for o in proof['objects'].values()) <= max_bytes
                       and max_bytes > 0 and not destination.exists(), 'Unsafe S3 retrieval inputs.')
        with self.c.staged(destination) as staging:
            for name in self.c.FILES:
                obj = proof['objects'][name]
                with (staging / name).open('xb') as stream:
                    self.download(obj['key'], obj['versionId'], stream, obj['bytes'], obj)
            manifest = self.c.verify(staging)
            self.c.require(manifest['backupId'] == proof['backupId'] and manifest['recoveryPoint'] == proof['recoveryPoint']
                           and self.c.digest(staging / 'manifest.json') == proof['manifestSha256'], 'S3 manifest mismatch.')
            self.c.publish(staging, destination)
        return {'kind': 's3-fetch', 'backupId': proof['backupId'], 'manifestSha256': proof['manifestSha256'],
                'ciphertextIntegrity': True, 'independentRecoveryVerified': False}

    def expiry_plan(self):
        """Delete-only actor needs no object-content or configuration read authority."""
        candidates, alerts = [], set()
        entries = self.inventory()
        for entry in entries:
            point = dt.datetime.strptime(KEY.fullmatch(entry['Key'])[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
            until = entry['LastModified'] if entry['marker'] else self.protection(entry)[1]
            if self.c.utc() >= until:
                candidates.append({'key': entry['Key'], 'versionId': entry['VersionId'], 'marker': entry['marker'],
                                   'lastModified': entry['LastModified'].isoformat(), 'retainUntil': until.isoformat()})
                age = self.c.utc() - point
                if age >= dt.timedelta(days=35): alerts.add('retention-incident')
                elif age >= dt.timedelta(days=34, hours=12): alerts.add('expiry-critical')
                elif self.c.utc() - until >= dt.timedelta(hours=2): alerts.add('expiry-warning')
        return {'kind': 's3-expiry-plan', 'bucket': self.bucket, 'verifiedAt': self.c.utc().isoformat(),
                'inventorySha256': self.inventory_digest(entries),
                'candidates': candidates, 'alerts': sorted(alerts), 'deletionEnabled': False}

    @staticmethod
    def inventory_digest(entries):
        rows = sorted((e['Key'], e['VersionId'], e['marker'], e['LastModified'].isoformat(), e.get('Size')) for e in entries)
        return hashlib.sha256(json.dumps(rows).encode()).hexdigest()

    def expire(self, lifecycle, receipt, checkpoint):
        """Fresh plan only; persist intent before each exact-version mutation.

        Acknowledgement alone is never absence. On any failure, attempted versions
        remain unconfirmed until an operator reconciles the protected receipt.
        """
        receipt.update(kind='s3-expiry', adapterVersion=VERSION, bucket=self.bucket,
                       outcome='failure', deletionEnabled=True, attempts=[], absenceVerified=False)
        checkpoint()
        self.configuration(lifecycle)
        plan = self.expiry_plan()  # Complete bounded protection scan before any DELETE.
        receipt.update(plan=plan, lifecycleSha256=lifecycle)
        checkpoint()
        before = self.inventory()
        self.c.require(self.inventory_digest(before) == plan['inventorySha256'], 'S3 inventory changed before expiry.')
        by_id = {(e['Key'], e['VersionId']): e for e in before}
        removed = set()
        for candidate in plan['candidates']:
            # Configuration/retention can change after planning; server-side
            # Compliance and Legal Hold enforcement must also be qualified (P1–P5).
            self.configuration(lifecycle)
            identity = (candidate['key'], candidate['versionId'])
            entry = by_id[identity]
            until = entry['LastModified'] if entry['marker'] else self.protection(entry)[1]
            self.c.require(until.isoformat() == candidate['retainUntil']
                           and min(self.c.utc(), self.remote_now) >= until, 'S3 version no longer eligible for expiry.')
            attempt = {**candidate, 'state': 'unconfirmed', 'attemptedAt': self.c.utc().isoformat()}
            receipt['attempts'].append(attempt)
            checkpoint()  # Durable intent survives timeout, termination or lost acknowledgement.
            response = self.call('delete_object', Key=identity[0], VersionId=identity[1])
            self.c.require(response.get('VersionId') == identity[1]
                           and response.get('DeleteMarker', False) is entry['marker'], 'Ambiguous S3 deletion acknowledgement.')
            attempt.update(acknowledgedAt=self.c.utc().isoformat())
            removed.add(identity)
            checkpoint()
        self.configuration(lifecycle)
        after = self.inventory()  # No prefix/delimiter: verify versions AND markers.
        expected = [e for e in before if (e['Key'], e['VersionId']) not in removed]
        self.c.require(self.inventory_digest(after) == self.inventory_digest(expected), 'S3 deletion absence unconfirmed or inventory changed.')
        verified = self.c.utc().isoformat()
        for attempt in receipt['attempts']:
            attempt.update(state='absence-verified', verifiedAt=verified)
        receipt.update(outcome='success', absenceVerified=True, verifiedAt=verified,
                       inventoryAfterSha256=self.inventory_digest(after), alerts=plan['alerts'])
        checkpoint()
        return receipt

    def admission_evidence(self, lifecycle, max_bytes):
        """The VPS's own capacity and configuration check: listing only, never object content.

        A local guard for the next upload. It proves nothing about integrity, which stays
        with the independent observer, and it cannot see incomplete multipart bytes.
        """
        self.c.require(abs((self.c.utc() - self.provider_clock()).total_seconds()) <= 60,
                       'S3 clock skew exceeds 60 seconds.')
        self.configuration(lifecycle)
        retained = sum(e.get('Size', 0) for e in self.inventory())
        self.c.require(0 < max_bytes and retained <= max_bytes, 'S3 retained-byte budget exceeded.')
        return {'kind': 's3-admission', 'bucket': self.bucket, 'verifiedAt': self.c.utc().isoformat(),
                'retainedBytes': retained, 'alerts': ['capacity-warning'] if retained >= WARNING_BYTES else [],
                'independentRecoveryVerified': False}

    def observe(self, lifecycle, max_bytes, restore=None, plan=False, state=None, verify_limit=None, checkpoint=None):
        """With a state, each version set is hashed once; later runs inspect metadata only.

        verify_limit bounds the ciphertext streamed by one run, newest pair first. A pair
        not yet hashed is never counted as integrity evidence.
        """
        if plan:
            return self.expiry_plan()
        self.configuration(lifecycle)
        entries = self.inventory()  # Full bounded scan includes unknown prefixes, orphans and markers.
        alerts, keys, pairs, receipts = set(), set(), [], []
        verified = (state if state is not None else {}).setdefault('verified', {})
        versions = {(e['Key'], e['VersionId']) for e in entries if not e['marker']}
        retained = sum(e.get('Size', 0) for e in entries)
        self.c.require(retained <= max_bytes and max_bytes > 0, 'S3 retained-byte budget exceeded.')
        if retained >= WARNING_BYTES:
            alerts.add('capacity-warning')
        for e in entries:
            if e['Key'] in keys:
                alerts.add('multiple-versions')
            keys.add(e['Key'])
            match = KEY.fullmatch(e['Key'])
            point = dt.datetime.strptime(match[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
            if e['marker']:
                alerts.add('delete-marker')
                until = e['LastModified']
            else:
                point, until = self.protection(e)
            age = self.c.utc() - point
            if self.c.utc() >= until:
                if age >= dt.timedelta(days=35): alerts.add('retention-incident')
                elif age >= dt.timedelta(days=34, hours=12): alerts.add('expiry-critical')
                elif self.c.utc() - until >= dt.timedelta(hours=2): alerts.add('expiry-warning')
            if not e['marker'] and match[4] == 'receipt.json':
                receipts.append(e)
        hashed = 0
        for e in sorted(receipts, key=lambda item: item['Key'], reverse=True):  # Keys sort by recovery point.
            reference = e['Key'] + '@' + e['VersionId']
            proof = verified.get(reference)
            if proof is None and verify_limit is not None and hashed >= verify_limit:
                alerts.add('integrity-backlog')
                continue
            if proof is None:
                proof = self.receipt(e)
                self.c.require(all((o['key'], o['versionId']) in versions for o in proof['objects'].values()), 'S3 pair has missing versions.')
                # Reuse the exact same verifier as recovery, with ephemeral ciphertext only.
                with self.c.tempfile.TemporaryDirectory() as name:
                    self.fetch(proof, Path(name) / 'pair', max_bytes)
                hashed += 1
                verified[reference] = proof
                if checkpoint:
                    checkpoint()
            # A hashed set must still exist exactly as it was published.
            self.c.require(all((o['key'], o['versionId']) in versions for o in proof['objects'].values()), 'S3 pair has missing versions.')
            pairs.append({**proof, 'receiptObject': {'key': e['Key'], 'versionId': e['VersionId']}})
        current = {e['Key'] + '@' + e['VersionId'] for e in receipts}
        for reference in set(verified) - current:
            del verified[reference]  # Expired pairs leave the state with their versions.
        completed = {e['Key'].rsplit('/', 1)[0] for e in receipts}
        if any(e['Key'].rsplit('/', 1)[0] not in completed for e in entries):
            alerts.add('incomplete-attempt')
        latest = max((self.c.timestamp(p['recoveryPoint']) for p in pairs), default=None)
        if latest is None or self.c.utc() - latest > dt.timedelta(hours=24): alerts.add('integrity-critical')
        elif self.c.utc() - latest > dt.timedelta(hours=14): alerts.add('missed-copy-warning')
        if restore is None:
            alerts.add('restore-missing')
        else:
            self.c.require(restore['kind'] == 'recovery-rehearsal' and restore['outcome'] == 'success'
                           and restore['deletionReplay'] is True and restore['readiness'] is True
                           and restore['scope'] == 'owner-reconstructed-target'
                           and any(p['backupId'] == restore['backupId'] and p['manifestSha256'] == restore['manifestSha256'] for p in pairs),
                           'S3 restore evidence mismatch.')
            restored_age = self.c.utc() - self.c.timestamp(restore['verifiedAt'])
            self.c.require(restored_age >= dt.timedelta(), 'Future restore evidence.')
            if restored_age > dt.timedelta(days=14): alerts.add('restore-critical')
            elif restored_age > dt.timedelta(days=8): alerts.add('restore-warning')
        return {'kind': 's3-observation', 'bucket': self.bucket,
                'verifiedAt': self.c.utc().isoformat(), 'retainedBytes': retained,
                'alerts': sorted(alerts), 'pairs': pairs, 'candidates': [],
                'deletionEnabled': False, 'independentRecoveryVerified': False,
                'latestIntegrityRecoveryPoint': latest.isoformat() if latest else None,
                'fullRestoreVerifiedAt': restore['verifiedAt'] if restore else None,
                'healthy': not alerts}


def dispatch(core, args, result=None):
    def timeout(*_):
        raise core.Refused('S3 operation budget exhausted.')
    core.signal.signal(core.signal.SIGALRM, timeout)
    core.signal.alarm(1800)
    core.private(args.s3_config)
    core.require(args.s3_config.stat().st_size <= MAX_JSON, 'Oversized S3 configuration.')
    def unique_fields(pairs):
        fields = {}
        for key, value in pairs:
            core.require(key not in fields, 'Ambiguous S3 configuration fields.')
            fields[key] = value
        return fields
    config = json.loads(args.s3_config.read_text(), object_pairs_hook=unique_fields)
    core.require(all(isinstance(config[k], str) and config[k].strip() for k in
                     ('endpoint', 'region', 'bucket', 'accessKey', 'secretKey')), 'Explicit S3 identity required.')
    core.require(re.fullmatch(r'https://s3\.[a-z0-9-]+\.io\.cloud\.ovh\.net', config['endpoint'])
                 and config['endpoint'] == f"https://s3.{config['region']}.io.cloud.ovh.net"
                 and re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', config['bucket']), 'Invalid OVH S3 target.')
    import boto3
    from botocore.config import Config
    # Explicit credentials only; never fall back to instance metadata or ambient profiles.
    client = boto3.client('s3', endpoint_url=config['endpoint'], region_name=config['region'],
                          aws_access_key_id=config['accessKey'], aws_secret_access_key=config['secretKey'],
                          config=Config(connect_timeout=10, read_timeout=30, retries={'total_max_attempts': 1},
                                        s3={'addressing_style': 'path'}))
    def provider_clock():
        # Unsigned TLS probe: the upload-only identity needs no read/list permission.
        request = urllib.request.Request(config['endpoint'], method='HEAD')
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return email.utils.parsedate_to_datetime(response.headers['Date'])
    store = Store(core, client, config['bucket'], provider_clock)
    if args.command == 'publish':
        return store.publish(args.backup, args.observer_receipt, args.max_copy_bytes)
    if args.command == 'fetch':
        core.private(args.publish_receipt)
        core.require(args.publish_receipt.stat().st_size <= MAX_JSON, 'Oversized S3 pair reference.')
        return store.fetch(json.loads(args.publish_receipt.read_text()), args.destination, args.max_copy_bytes)
    restore = None
    if args.verified_receipt:
        core.private(args.verified_receipt)
        core.require(args.verified_receipt.stat().st_size <= MAX_JSON, 'Oversized S3 restore evidence.')
        restore = json.loads(args.verified_receipt.read_text())
    if args.command == 'admission':
        return store.admission_evidence(config.get('lifecycleSha256'), args.max_copy_bytes)
    if args.command == 'expire':
        if args.execute:
            core.require(isinstance(result, dict), 'Expiry receipt context required.')
            return store.expire(config.get('lifecycleSha256'), result, lambda: core.write(args.receipt, result))
        return store.expiry_plan()
    state = None
    if args.observer_state:
        state = {}
        if args.observer_state.exists():
            core.private(args.observer_state)
            core.require(args.observer_state.stat().st_size <= MAX_STATE, 'Oversized S3 observer state.')
            state = json.loads(args.observer_state.read_text())
    def checkpoint():
        core.write(args.observer_state, state)
    observation = store.observe(config['lifecycleSha256'], args.max_copy_bytes, restore, state=state,
                                verify_limit=args.max_verify_pairs, checkpoint=checkpoint if state is not None else None)
    if state is not None:
        checkpoint()
    return observation
