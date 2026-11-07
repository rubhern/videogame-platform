#!/usr/bin/env python3
"""L8 (#276): two-copy synchronous S3 durability of the deletion ledger and legal archive.

In-process against a fake S3 with versioning and a Compliance default; no network or Docker.
Proves the barrier (no host mutation without both acknowledgements), prefix reconciliation,
cloud-only reconstruction for recovery, refusal when the ledger cannot be rebuilt and verified,
the production guard and the archive mirror/purge.
"""
from __future__ import annotations

import datetime as dt
import importlib.machinery
import importlib.util
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


ledger_module = load("personal_data_ledger_under_test", ROOT / "deploy/private-dev/bin/personal-data-ledger")
cloud_module = ledger_module.cloud_module
recovery = load("recovery_under_test", ROOT / "deploy/public-production/bin/recovery")
PRODUCTION = "https://auth.gameometro.com/realms/gameometro"
SUBJECT = "11111111-1111-4111-8111-111111111111"


class ProviderError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.response = {"Error": {"Code": code}}


class FakeBucket:
    def __init__(self, name, locked):
        self.name, self.locked, self.versions, self.counter = name, locked, {}, 0
        self.fail = set()       # operation names that raise
        self.lie = False        # read-back returns different metadata
        self.unlock = False     # retention not applied

    def client(self):
        bucket = self

        class Client:
            def __getattr__(self, operation):
                def call(**kw):
                    assert kw.pop("Bucket") == bucket.name
                    if operation in bucket.fail:
                        raise ProviderError("InternalError")
                    return getattr(bucket, operation)(**kw)
                return call
        return Client()

    def put_object(self, Key, Body, Metadata, ContentMD5):
        self.counter += 1
        version = str(self.counter) if self.locked else "null"
        self.versions.setdefault(Key, []).append((version, Body, dict(Metadata)))
        return {"VersionId": version} if self.locked else {}

    def _find(self, Key, VersionId=None):
        entries = self.versions.get(Key) or []
        if not entries:
            raise ProviderError("NoSuchKey")
        if VersionId is None:
            return entries[-1]
        return next(e for e in entries if e[0] == VersionId)

    def head_object(self, Key, VersionId=None):
        version, body, metadata = self._find(Key, VersionId)
        return {"ContentLength": len(body) + (1 if self.lie else 0), "Metadata": metadata}

    def get_object_retention(self, Key, VersionId=None):
        until = dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=36)
        return {"Retention": {"Mode": "GOVERNANCE" if self.unlock else "COMPLIANCE", "RetainUntilDate": until}}

    def get_object(self, Key):
        version, body, metadata = self._find(Key)
        return {"Body": io.BytesIO(body), "ContentLength": len(body), "Metadata": metadata, "VersionId": version}

    def list_object_versions(self, Prefix, MaxKeys):
        items = [{"Key": k, "VersionId": v} for k, entries in self.versions.items() if k.startswith(Prefix)
                 for v, _, _ in entries]
        return {"IsTruncated": False, "Versions": items}

    def delete_object(self, Key, VersionId="null"):
        self.versions[Key] = [e for e in self.versions[Key] if e[0] != VersionId]
        if not self.versions[Key]:
            del self.versions[Key]


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        for name in ("owner", "independent", "secrets", "fetch-a", "fetch-b", "receipts"):
            (self.root / name).mkdir(mode=0o700)
        self.buckets = {name: FakeBucket(name, name.startswith("ledger")) for name in
                        ("ledger-gra", "ledger-sbg", "archive-gra", "archive-sbg")}
        specs = {"ledger": [], "archive": []}
        for index, (name, region) in enumerate((("ledger-gra", "gra"), ("ledger-sbg", "sbg"),
                                                ("archive-gra", "gra"), ("archive-sbg", "sbg"))):
            credentials = self.root / "secrets" / f"{name}.json"
            credentials.write_text(json.dumps({"accessKey": f"AK{index}", "secretKey": "s"}))
            credentials.chmod(0o600)
            kind = name.split("-")[0]
            spec = {"endpoint": f"https://s3.{region}.example", "region": region, "bucket": name,
                    "credentials": str(credentials)}
            spec["key" if kind == "ledger" else "prefix"] = "ledger/production.jsonl" if kind == "ledger" else "archive"
            specs[kind].append(spec)
        self.config = self.root / "secrets" / "cloud.json"
        self.config.write_text(json.dumps(specs))
        self.config.chmod(0o600)
        self.cloud = self.make_cloud()
        ledger_module.CLOUD, ledger_module.MUTATING = self.cloud, False
        self.ledger_path = self.root / "owner" / "ledger.jsonl"
        self.copy_path = self.root / "independent" / "ledger.jsonl"

    def tearDown(self):
        ledger_module.CLOUD, ledger_module.MUTATING = None, False
        self.tmp.cleanup()

    def make_cloud(self):
        return cloud_module.CloudCopies(self.config, client_factory=lambda spec, _: self.buckets[spec["bucket"]].client())

    def request(self, ledger):
        ledger.append({"event": "requested", "request": "REQ-001", "keycloakUserId": SUBJECT,
                       "userId": "22222222-2222-4222-8222-222222222222"})

    def current(self, name):
        return self.buckets[name].versions["ledger/production.jsonl"][-1][1]


class Configuration(Base):
    def test_four_separate_identities_buckets_and_regions_are_required(self):
        specs = json.loads(self.config.read_text())
        for mutate in ("same-region", "same-bucket", "shared-identity", "one-copy"):
            with self.subTest(mutate=mutate):
                broken = json.loads(json.dumps(specs))
                if mutate == "same-region": broken["ledger"][1]["region"] = "gra"
                elif mutate == "same-bucket": broken["archive"][1]["bucket"] = "archive-gra"
                elif mutate == "shared-identity": broken["archive"][0]["credentials"] = broken["ledger"][0]["credentials"]
                else: broken["ledger"] = broken["ledger"][:1]
                self.config.write_text(json.dumps(broken))
                with self.assertRaises(cloud_module.CloudError): self.make_cloud()
        self.config.write_text(json.dumps(specs))
        self.config.chmod(0o644)
        with self.assertRaises(cloud_module.CloudError): self.make_cloud()


class Barrier(Base):
    def test_every_append_is_acknowledged_by_both_locked_copies(self):
        with ledger_module.open_ledger(self.ledger_path, self.copy_path, create=True) as ledger:
            self.request(ledger)
            local = self.ledger_path.read_bytes()
        self.assertEqual(self.current("ledger-gra"), local)
        self.assertEqual(self.current("ledger-sbg"), local)

    def test_failed_lock_readback_or_second_copy_stops_before_any_host_mutation(self):
        for fault in ("put", "lock", "readback"):
            with self.subTest(fault=fault):
                for path in (self.ledger_path, self.copy_path):
                    path.unlink(missing_ok=True)
                bucket = self.buckets["ledger-sbg"]
                bucket.versions.clear(); self.buckets["ledger-gra"].versions.clear()
                bucket.fail, bucket.unlock, bucket.lie = ({"put_object"} if fault == "put" else set(),
                                                         fault == "lock", fault == "readback")
                host_log = self.root / "receipts" / f"host-{fault}.log"
                host = [sys.executable, "-c", f"import sys; open({str(host_log)!r},'a').write(sys.argv[1]); print('{{}}')"]
                entry = {"request": "REQ-001", "keycloakUserId": SUBJECT, "userId": "22222222-2222-4222-8222-222222222222"}
                with ledger_module.open_ledger(self.ledger_path, self.copy_path, create=True) as ledger:
                    with self.assertRaises(cloud_module.CloudError):
                        self.request(ledger)
                        ledger_module.run_steps(ledger, host, entry, replay=False)
                self.assertFalse(host_log.exists(), "a host step ran without both acknowledgements")
                bucket.fail, bucket.unlock, bucket.lie = set(), False, False

    def test_retry_pushes_an_unacknowledged_suffix_and_restores_a_lost_local_suffix(self):
        with ledger_module.open_ledger(self.ledger_path, self.copy_path, create=True) as ledger:
            self.request(ledger)
        full = self.ledger_path.read_bytes()
        # Cloud behind (second copy lost the last acknowledgement): reopening pushes it.
        self.buckets["ledger-sbg"].versions["ledger/production.jsonl"] = []
        with ledger_module.open_ledger(self.ledger_path, self.copy_path):
            pass
        self.assertEqual(self.current("ledger-sbg"), full)
        # Local behind (workstation restored from older storage): reopening extends it.
        for path in (self.ledger_path, self.copy_path):
            path.write_bytes(b""); path.chmod(0o600)
        with ledger_module.open_ledger(self.ledger_path, self.copy_path):
            pass
        self.assertEqual(self.ledger_path.read_bytes(), full)

    def test_divergent_cloud_copy_refuses(self):
        with ledger_module.open_ledger(self.ledger_path, self.copy_path, create=True) as ledger:
            self.request(ledger)
        version, body, metadata = self.buckets["ledger-gra"].versions["ledger/production.jsonl"][-1]
        tampered = body.replace(b"REQ-001", b"REQ-999")
        self.buckets["ledger-gra"].versions["ledger/production.jsonl"].append(
            ("x", tampered, {"sha256": cloud_module.sha256(tampered)}))
        with self.assertRaises(ledger_module.LedgerError):
            with ledger_module.open_ledger(self.ledger_path, self.copy_path):
                pass

    def test_production_mutation_without_cloud_copies_refuses(self):
        ledger_module.CLOUD, ledger_module.MUTATING = None, True
        with self.assertRaises(ledger_module.LedgerError): ledger_module.require_cloud(PRODUCTION)
        ledger_module.require_cloud("https://identity.example.test/realms/videogame-platform")
        ledger_module.MUTATING = False
        ledger_module.require_cloud(PRODUCTION)


class Reconstruction(Base):
    def arguments(self, ledger, copy, checkpoint):
        return type("A", (), {"ledger": ledger, "ledger_copy": copy, "checkpoint": checkpoint})()

    def test_recovery_reopens_only_on_a_ledger_rebuilt_from_both_cloud_copies(self):
        with ledger_module.open_ledger(self.ledger_path, self.copy_path, create=True) as ledger:
            self.request(ledger)
        ledger, copy, checkpoint = (self.root / "fetch-a" / "ledger", self.root / "fetch-b" / "ledger",
                                    self.root / "fetch-a" / "checkpoint.json")
        self.assertEqual(ledger_module.command_ledger_fetch(self.arguments(ledger, copy, checkpoint), None), 0)
        self.assertEqual(ledger.read_bytes(), self.ledger_path.read_bytes())
        self.assertEqual(recovery.ledger_evidence(ledger, copy, checkpoint), recovery.digest(ledger))

    def test_missing_corrupt_or_unequal_cloud_copy_refuses_reconstruction(self):
        with ledger_module.open_ledger(self.ledger_path, self.copy_path, create=True) as ledger:
            self.request(ledger)
        key = "ledger/production.jsonl"
        original = list(self.buckets["ledger-sbg"].versions[key])
        for fault in ("missing", "corrupt", "unequal"):
            with self.subTest(fault=fault):
                versions = self.buckets["ledger-sbg"].versions
                versions[key] = list(original)
                if fault == "missing": del versions[key]
                elif fault == "corrupt": versions[key].append(("c", b"x\n", {"sha256": "0" * 64}))
                else: versions[key].append(("u", b"", {"sha256": cloud_module.sha256(b"")}))
                target = self.root / f"fetch-{fault}"
                target.mkdir(mode=0o700)
                (target / "b").mkdir(mode=0o700)
                with self.assertRaises((cloud_module.CloudError, ledger_module.LedgerError)):
                    ledger_module.command_ledger_fetch(
                        self.arguments(target / "ledger", target / "b" / "ledger", target / "checkpoint.json"), None)
                self.assertFalse((target / "checkpoint.json").exists())


class Archive(Base):
    def test_archive_is_mirrored_to_both_unlocked_copies_and_purged_from_both(self):
        directory = self.root / "owner" / "REQ-001"
        directory.mkdir(mode=0o700)
        (directory / "source.json.gpg").write_bytes(b"ciphertext")
        (directory / "source.json.gpg.sig").write_bytes(b"signature")
        receipts = self.cloud.put_archive("REQ-001", directory)
        self.assertEqual(len(receipts), 4)
        for name in ("archive-gra", "archive-sbg"):
            self.assertEqual(sorted(self.buckets[name].versions),
                             ["archive/REQ-001/source.json.gpg", "archive/REQ-001/source.json.gpg.sig"])
        self.assertEqual(self.cloud.purge_archive("REQ-001"), 4)
        self.assertFalse(self.buckets["archive-gra"].versions or self.buckets["archive-sbg"].versions)

    def test_failed_archive_copy_refuses_before_the_ratings_step(self):
        directory = self.root / "owner" / "REQ-002"
        directory.mkdir(mode=0o700)
        (directory / "source.json.gpg").write_bytes(b"ciphertext")
        self.buckets["archive-sbg"].fail = {"put_object"}
        with self.assertRaises(cloud_module.CloudError): self.cloud.put_archive("REQ-002", directory)


if __name__ == "__main__":
    unittest.main()
