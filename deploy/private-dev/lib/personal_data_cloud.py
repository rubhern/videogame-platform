"""Synchronous two-copy S3 durability for the deletion ledger and the legal archive (#276, L8).

Used by `personal-data-ledger`, owner-side or from the scheduled production cleanup (ADR-0044);
never by a workflow.
Each destination has its own bucket, region and S3 identity. A write returns only after both
destinations acknowledge an exact version whose size and SHA-256 are read back, so no mutation
that depends on the record can start without two durable copies. The ledger buckets carry an
Object Lock Compliance default; the archive buckets carry none, so a purge can remove the source.

Configuration (owner-only JSON, never in Git):
  {"ledger":  [{"endpoint", "region", "bucket", "key", "credentials"}, {...}],
   "archive": [{"endpoint", "region", "bucket", "prefix", "credentials"}, {...}]}
`credentials` is an absolute owner-only JSON file holding `accessKey` and `secretKey`.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import stat

LEDGER_LOCK_MINIMUM = dt.timedelta(days=35)  # D6 default is 36 days; 35 is the hard floor.
MAX_LEDGER_BYTES = 128 * 1024 * 1024
MAX_ARCHIVE_FILES = 32
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_LISTING = 1000
KEY = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")
REFERENCE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
HTTPS = re.compile(r"https://[A-Za-z0-9.-]+/?")


class CloudError(RuntimeError):
    """Safe failure: never includes credentials, object bodies or provider diagnostics."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _private_file(path: pathlib.Path) -> None:
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise CloudError("Cloud configuration and credentials must be absolute regular files")
    metadata = path.stat()
    if metadata.st_mode & 0o077 or metadata.st_uid != os.geteuid() or not stat.S_ISREG(metadata.st_mode):
        raise CloudError("Cloud configuration and credentials must be private to their owner")


def _read_json(path: pathlib.Path, limit: int = 16384) -> dict:
    _private_file(path)
    if path.stat().st_size > limit:
        raise CloudError("Cloud configuration file is too large")
    try:
        value = json.loads(path.read_text())
    except json.JSONDecodeError:
        raise CloudError("Cloud configuration file is not valid JSON") from None
    if not isinstance(value, dict):
        raise CloudError("Cloud configuration file must be a JSON object")
    return value


def default_client(destination: dict, credentials: dict):
    import boto3
    from botocore.config import Config
    return boto3.client(
        "s3", endpoint_url=destination["endpoint"], region_name=destination["region"],
        aws_access_key_id=credentials["accessKey"], aws_secret_access_key=credentials["secretKey"],
        config=Config(connect_timeout=10, read_timeout=60, retries={"max_attempts": 2, "mode": "standard"}))


class Destination:
    def __init__(self, spec: dict, kind: str, client_factory) -> None:
        field = "key" if kind == "ledger" else "prefix"
        if set(spec) != {"endpoint", "region", "bucket", field, "credentials"}:
            raise CloudError(f"Each {kind} destination needs exactly endpoint, region, bucket, {field}, credentials")
        if not (isinstance(spec["endpoint"], str) and HTTPS.fullmatch(spec["endpoint"])
                and all(isinstance(spec[name], str) and spec[name] for name in ("region", "bucket", field))
                and KEY.fullmatch(spec[field].rstrip("/") or "x")):
            raise CloudError(f"Invalid {kind} destination")
        self.kind, self.bucket, self.region = kind, spec["bucket"], spec["region"]
        self.key = spec.get("key")
        self.prefix = spec["prefix"].rstrip("/") + "/" if kind == "archive" else None
        self.credentials_path = pathlib.Path(spec["credentials"])
        credentials = _read_json(self.credentials_path)
        if not all(isinstance(credentials.get(name), str) and credentials[name] for name in ("accessKey", "secretKey")):
            raise CloudError("Credentials file needs accessKey and secretKey")
        self.access_key = credentials["accessKey"]
        self.client = client_factory(spec, credentials)

    def call(self, operation: str, **arguments):
        try:
            return getattr(self.client, operation)(Bucket=self.bucket, **arguments)
        except CloudError:
            raise
        except Exception as error:  # Provider/SDK detail may carry request data: keep it out.
            code = getattr(error, "response", {}).get("Error", {}).get("Code", type(error).__name__)
            raise CloudError(f"{self.kind} copy in {self.bucket} refused {operation} ({code})") from None

    def put(self, key: str, data: bytes, *, locked: bool) -> dict:
        digest = sha256(data)
        answer = self.call("put_object", Key=key, Body=data, Metadata={"sha256": digest},
                           ContentMD5=__import__("base64").b64encode(hashlib.md5(data).digest()).decode())
        version = answer.get("VersionId")
        if locked and (not isinstance(version, str) or version in ("", "null")):
            raise CloudError(f"{self.kind} copy in {self.bucket} acknowledged no exact version")
        exact = {"VersionId": version} if isinstance(version, str) and version not in ("", "null") else {}
        head = self.call("head_object", Key=key, **exact)
        if head.get("ContentLength") != len(data) or head.get("Metadata", {}).get("sha256") != digest:
            raise CloudError(f"{self.kind} copy in {self.bucket} did not read back the acknowledged bytes")
        if locked:
            retention = self.call("get_object_retention", Key=key, **exact).get("Retention", {})
            until = retention.get("RetainUntilDate")
            if (retention.get("Mode") != "COMPLIANCE" or not isinstance(until, dt.datetime)
                    or until - dt.datetime.now(dt.timezone.utc) < LEDGER_LOCK_MINIMUM):
                raise CloudError(f"Ledger copy in {self.bucket} is not under its Compliance lock")
        return {"bucket": self.bucket, "region": self.region, "key": key, "versionId": version, "sha256": digest}

    def get_current(self, key: str, limit: int) -> tuple[bytes, dict] | None:
        try:
            answer = self.client.get_object(Bucket=self.bucket, Key=key)
        except Exception as error:
            code = getattr(error, "response", {}).get("Error", {}).get("Code")
            if code in ("NoSuchKey", "404"):
                return None
            raise CloudError(f"{self.kind} copy in {self.bucket} refused get_object ({code or type(error).__name__})") from None
        if answer.get("ContentLength", 0) > limit:
            raise CloudError(f"{self.kind} copy in {self.bucket} exceeds its byte budget")
        data = answer["Body"].read(limit + 1)
        if len(data) > limit or answer.get("Metadata", {}).get("sha256") != sha256(data):
            raise CloudError(f"{self.kind} copy in {self.bucket} failed its integrity check")
        return data, {"bucket": self.bucket, "region": self.region, "key": key,
                      "versionId": answer.get("VersionId"), "sha256": sha256(data)}


class CloudCopies:
    def __init__(self, config_path: pathlib.Path, client_factory=default_client) -> None:
        config = _read_json(config_path)
        if set(config) != {"ledger", "archive"}:
            raise CloudError("Cloud configuration needs exactly the ledger and archive destinations")
        self.ledger, self.archive = [], []
        for kind in ("ledger", "archive"):
            specs = config[kind]
            if not isinstance(specs, list) or len(specs) != 2:
                raise CloudError(f"Exactly two {kind} destinations are required")
            destinations = [Destination(spec, kind, client_factory) for spec in specs]
            if len({d.bucket for d in destinations}) != 2 or len({d.region for d in destinations}) != 2:
                raise CloudError(f"The two {kind} copies need distinct buckets in distinct regions")
            setattr(self, kind, destinations)
        every = self.ledger + self.archive
        if (len({d.credentials_path.resolve() for d in every}) != 4 or len({d.access_key for d in every}) != 4
                or len({d.bucket for d in every}) != 4):
            raise CloudError("Ledger and archive copies need four separate buckets and S3 identities")

    # Ledger ------------------------------------------------------------------------------------

    def put_ledger(self, data: bytes) -> list[dict]:
        """Both acknowledgements, or an exception before the caller may mutate anything."""
        if len(data) > MAX_LEDGER_BYTES:
            raise CloudError("Ledger exceeds the reviewed byte budget")
        return [destination.put(destination.key, data, locked=True) for destination in self.ledger]

    def current_ledgers(self) -> list[tuple[bytes, dict] | None]:
        return [destination.get_current(destination.key, MAX_LEDGER_BYTES) for destination in self.ledger]

    def reconstruct(self) -> tuple[bytes, list[dict]]:
        """Recovery source: both copies present, intact and identical, or a refusal."""
        copies = self.current_ledgers()
        if any(copy is None for copy in copies):
            raise CloudError("A cloud ledger copy is missing; recovery cannot reopen")
        (first, first_meta), (second, second_meta) = copies
        if first != second:
            raise CloudError("The cloud ledger copies differ; run any ledger command with --cloud-copies "
                             "to reconcile both before recovery")
        return first, [first_meta, second_meta]

    # Archive -----------------------------------------------------------------------------------

    def put_archive(self, reference: str, directory: pathlib.Path) -> list[dict]:
        if not REFERENCE.fullmatch(reference):
            raise CloudError("Invalid archive reference")
        files = sorted(p for p in directory.iterdir() if p.is_file() and not p.is_symlink())
        if not files or len(files) > MAX_ARCHIVE_FILES or sum(p.stat().st_size for p in files) > MAX_ARCHIVE_BYTES:
            raise CloudError("Archive directory is empty or exceeds its bounds")
        receipts = []
        for destination in self.archive:
            for path in files:
                receipts.append(destination.put(destination.prefix + reference + "/" + path.name,
                                                path.read_bytes(), locked=False))
        return receipts

    def purge_archive(self, reference: str) -> int:
        """Remove every version under one reference from both copies and verify absence."""
        if not REFERENCE.fullmatch(reference):
            raise CloudError("Invalid archive reference")
        removed = 0
        for destination in self.archive:
            prefix = destination.prefix + reference + "/"
            listing = destination.call("list_object_versions", Prefix=prefix, MaxKeys=MAX_LISTING)
            if listing.get("IsTruncated"):
                raise CloudError("Archive purge listing exceeded its bound")
            for item in listing.get("Versions", []) + listing.get("DeleteMarkers", []):
                if not item["Key"].startswith(prefix):
                    raise CloudError("Archive purge listing escaped its reference")
                version = item.get("VersionId")
                exact = {"VersionId": version} if isinstance(version, str) and version not in ("", "null") else {}
                destination.call("delete_object", Key=item["Key"], **exact)
                removed += 1
            after = destination.call("list_object_versions", Prefix=prefix, MaxKeys=MAX_LISTING)
            if after.get("Versions") or after.get("DeleteMarkers"):
                raise CloudError(f"Archive copy in {destination.bucket} still holds the purged reference")
        return removed
