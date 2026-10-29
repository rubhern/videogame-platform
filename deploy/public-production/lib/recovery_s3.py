"""Bounded S3 transport for recovery's existing encrypted inventory; no scheduler.

Only the uploader writes. Observer and expiry planner require separate credentials.
Expiry is deliberately a plan until D3 and the real-provider gates are ratified.
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

VERSION = "0.1.0"

PART = 16 * 1024 * 1024
MAX_VERSIONS = 2048
MAX_JSON = 128 * 1024
KEY = re.compile(r'pairs/(\d{8}T\d{6}Z)/([0-9a-f]{32})/([0-9a-f]{32})/([^/]+)')


class Store:
    def __init__(self, core, client, bucket, provider_clock):
        self.c, self.s3, self.bucket = core, client, bucket
        self.deadline = time.monotonic() + 1800
        self.provider_clock = provider_clock

    def call(self, operation, **kw):
        self.c.require(time.monotonic() < self.deadline, 'S3 operation budget exhausted.')
        response = getattr(self.s3, operation)(Bucket=self.bucket, **kw)
        self.c.require(time.monotonic() < self.deadline, 'S3 operation budget exhausted.')
        date = response['ResponseMetadata']['HTTPHeaders']['date']
        remote = email.utils.parsedate_to_datetime(date)
        self.c.require(abs((self.c.utc() - remote).total_seconds()) <= 60, 'S3 clock skew exceeds 60 seconds.')
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
        self.c.require(proof['kind'] == 's3-observation' and proof['bucket'] == self.bucket
                       and proof['outcome'] == 'success'
                       and set(proof['alerts']) <= {'integrity-critical', 'missed-copy-warning', 'restore-missing', 'restore-warning', 'restore-critical', 'incomplete-attempt'}
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
        entries, cursor, seen = [], {}, set()
        for _ in range(32):
            page = self.call('list_object_versions', MaxKeys=128, **cursor)
            for marker, group in ((False, 'Versions'), (True, 'DeleteMarkers')):
                for item in page.get(group, []):
                    self.c.require(len(entries) < MAX_VERSIONS, 'S3 inventory overflow; reconcile before continuing.')
                    match = KEY.fullmatch(item['Key'])
                    self.c.require(match and match[4] in (*self.c.FILES, 'receipt.json')
                                   and item.get('VersionId') not in (None, '', 'null'), 'Unknown S3 inventory entry.')
                    identity = (item['Key'], item['VersionId'])
                    self.c.require(identity not in seen, 'Repeated S3 inventory entry.')
                    seen.add(identity)
                    entries.append({**item, 'marker': marker})
            if not page.get('IsTruncated'):
                return entries
            cursor = {'KeyMarker': page['NextKeyMarker'], 'VersionIdMarker': page['NextVersionIdMarker']}
        raise self.c.Refused('S3 pagination budget exhausted.')

    def configuration(self, expected_lifecycle):
        lock = self.call('get_object_lock_configuration')['ObjectLockConfiguration']
        self.c.require(lock == {'ObjectLockEnabled': 'Enabled', 'Rule': {'DefaultRetention': {'Mode': 'COMPLIANCE', 'Days': 33}}},
                       'S3 lock configuration drift.')
        self.c.require(self.call('get_bucket_versioning').get('Status') == 'Enabled', 'S3 versioning drift.')
        lifecycle = self.call('get_bucket_lifecycle_configuration')['Rules']
        self.c.require(hashlib.sha256(json.dumps(lifecycle, sort_keys=True).encode()).hexdigest() == expected_lifecycle,
                       'S3 lifecycle configuration drift.')

    def protection(self, entry):
        args = {'Key': entry['Key'], 'VersionId': entry['VersionId']}
        retention = self.call('get_object_retention', **args)['Retention']
        hold = self.call('get_object_legal_hold', **args)['LegalHold']['Status']
        point = dt.datetime.strptime(KEY.fullmatch(entry['Key'])[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
        modified, until = entry['LastModified'], retention['RetainUntilDate']
        self.c.require(retention['Mode'] == 'COMPLIANCE' and hold == 'OFF'
                       and 0 <= (modified - point).total_seconds() <= 21600
                       and dt.timedelta(days=33) <= until - modified <= dt.timedelta(days=34, minutes=5)
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
        for entry in self.inventory():
            point = dt.datetime.strptime(KEY.fullmatch(entry['Key'])[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
            until = entry['LastModified'] if entry['marker'] else self.protection(entry)[1]
            if self.c.utc() >= until:
                candidates.append({'key': entry['Key'], 'versionId': entry['VersionId'], 'marker': entry['marker']})
                age = self.c.utc() - point
                if age >= dt.timedelta(days=35): alerts.add('retention-incident')
                elif age >= dt.timedelta(days=34, hours=12): alerts.add('expiry-critical')
                elif self.c.utc() - until >= dt.timedelta(hours=2): alerts.add('expiry-warning')
        return {'kind': 's3-expiry-plan', 'bucket': self.bucket, 'verifiedAt': self.c.utc().isoformat(),
                'candidates': candidates, 'alerts': sorted(alerts), 'deletionEnabled': False}

    def observe(self, lifecycle, max_bytes, restore=None, plan=False):
        if plan:
            return self.expiry_plan()
        self.configuration(lifecycle)
        entries = self.inventory()  # Full bounded scan includes unknown prefixes, orphans and markers.
        alerts, keys, pairs = set(), set(), []
        versions = {(e['Key'], e['VersionId']) for e in entries if not e['marker']}
        retained = sum(e.get('Size', 0) for e in entries)
        self.c.require(retained <= max_bytes and max_bytes > 0, 'S3 retained-byte budget exceeded.')
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
                proof = self.receipt(e)
                self.c.require(all((o['key'], o['versionId']) in versions for o in proof['objects'].values()), 'S3 pair has missing versions.')
                # Reuse the exact same verifier as recovery, with ephemeral ciphertext only.
                with self.c.tempfile.TemporaryDirectory() as name:
                    self.fetch(proof, Path(name) / 'pair', max_bytes)
                pairs.append({**proof, 'receiptObject': {'key': e['Key'], 'versionId': e['VersionId']}})
        completed = {p['receiptObject']['key'].rsplit('/', 1)[0] for p in pairs}
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


def dispatch(core, args):
    def timeout(*_):
        raise core.Refused('S3 operation budget exhausted.')
    core.signal.signal(core.signal.SIGALRM, timeout)
    core.signal.alarm(1800)
    core.private(args.s3_config)
    core.require(args.s3_config.stat().st_size <= MAX_JSON, 'Oversized S3 configuration.')
    config = json.loads(args.s3_config.read_text())
    core.require(all(isinstance(config[k], str) and config[k].strip() for k in
                     ('endpoint', 'region', 'bucket', 'accessKey', 'secretKey')), 'Explicit S3 identity required.')
    core.require(re.fullmatch(r'https://s3\.[a-z0-9-]+\.io\.cloud\.ovh\.net', config['endpoint'])
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
    if args.command == 'expire':
        return store.expiry_plan()
    return store.observe(config['lifecycleSha256'], args.max_copy_bytes, restore)
