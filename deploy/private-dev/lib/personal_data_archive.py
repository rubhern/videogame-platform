"""Restricted legal source archive used by the existing deletion-ledger driver.

No runtime scheduling or host provisioning. GPG keys belong to the protected owner
context, never the VPS. Fixed ordinary profiles exclude ratings and credentials.
"""
from __future__ import annotations

import calendar
import datetime as dt
import hashlib
import fcntl
import json
import os
import pathlib
import re
import stat
import subprocess

UTC = dt.timezone.utc
MAX_BYTES = 20 * 1024 * 1024
PROFILES = ("pending-signup-v1", "requested-account-v1", "case-hold-v1")
REFERENCE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
FINGERPRINT = re.compile(r"[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64}")


class ArchiveError(RuntimeError):
    """Safe failure: do not include source data or GPG diagnostics."""


def timestamp(value: dt.datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_time(value: str) -> dt.datetime:
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError):
        raise ArchiveError("Invalid protected archive date") from None
    if parsed.tzinfo is None:
        raise ArchiveError("Archive dates must have a UTC offset")
    return parsed.astimezone(UTC)


def years(value: dt.datetime, count: int) -> dt.datetime:
    year = value.year + count
    return value.replace(year=year, day=min(value.day, calendar.monthrange(year, value.month)[1]))


def encoded(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def private(path: pathlib.Path, directory: bool = False) -> pathlib.Path:
    path = path.absolute()
    if path != path.resolve() or path.is_symlink():
        raise ArchiveError("Protected storage cannot use symlinks")
    metadata = path.stat()
    expected_type = stat.S_ISDIR(metadata.st_mode) if directory else stat.S_ISREG(metadata.st_mode)
    if not expected_type or metadata.st_mode & 0o077 or metadata.st_uid != os.geteuid() or (not directory and metadata.st_nlink != 1):
        raise ArchiveError("Protected storage must belong to this owner with no group/other access")
    return path


def read(path: pathlib.Path, limit: int = MAX_BYTES * 2) -> bytes:
    private(path)
    with path.open("rb") as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        raise ArchiveError("Protected file exceeds its byte budget")
    return value


def write(path: pathlib.Path, value: bytes, replace: bool = False) -> None:
    private(path.parent, True)
    if len(value) > MAX_BYTES * 2 or path.is_symlink():
        raise ArchiveError("Unsafe archive output")
    temporary = path.with_name(path.name + ".pending") if replace else path
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())
    if replace:
        if path.exists():
            private(path)
        os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def profile_payload(entry: dict, snapshot: dict, current: dt.datetime, hold: dict | None = None,
                    ratings: list | None = None) -> dict:
    profile = entry["archiveProfile"]
    user = snapshot.get("account")
    if profile not in PROFILES or not isinstance(user, dict) or not user.get("eligibleHuman"):
        raise ArchiveError("No eligible source account for this archive profile")
    if (user.get("keycloakUserId") != entry["keycloakUserId"] or
            snapshot.get("userId") != entry["userId"] or user.get("enabled") is not False):
        raise ArchiveError("Revoke the exact account before capturing its legal source")
    email = user.get("email")
    created = user.get("createdTimestamp")
    verified = user.get("emailVerified")
    if not isinstance(email, str) or not 1 <= len(email) <= 320 or type(created) is not int or created <= 0:
        raise ArchiveError("Source identity fields are invalid")
    if profile == "pending-signup-v1" and (verified is not False or snapshot.get("ratingCount") != 0):
        raise ArchiveError("Pending signup has verified email or unexpected ratings")
    if profile == "requested-account-v1" and verified is not True:
        raise ArchiveError("Requested-account profile requires a verified account")
    if type(verified) is not bool:
        raise ArchiveError("Verification state must be explicit")
    value = {"format": "gameometro-legal-source", "version": 1, "reference": entry["request"],
             "profile": profile, "issuer": snapshot["issuer"], "subject": entry["keycloakUserId"],
             "userId": entry["userId"], "email": email, "createdTimestamp": created,
             "emailVerified": verified, "blockedAt": timestamp(current),
             "expiresAt": timestamp(years(current, 3)), "reviewAt": timestamp(years(current, 1))}
    if profile == "case-hold-v1":
        if not hold or not REFERENCE.fullmatch(str(hold.get("caseReference", ""))) or not hold.get("reason"):
            raise ArchiveError("Exceptional archive needs a particular documented case")
        selected = set(hold.get("gameIds", []))
        if len(selected) > 100:
            raise ArchiveError("An exceptional case can name at most 100 games")
        rows = []
        for row in ratings or []:
            if row.get("gameId") not in selected or set(row) != {"gameId", "value", "createdAt", "updatedAt"}:
                raise ArchiveError("Exceptional source contains an unapproved field or row")
            rows.append(row)
        value["case"] = {key: hold[key] for key in ("caseReference", "reason")}
        value["ratings"] = rows
        if hold.get("until"):
            extended = parse_time(hold["until"])
            if extended < parse_time(value["expiresAt"]):
                raise ArchiveError("A particular hold cannot shorten the initial expiry")
            value["expiresAt"] = timestamp(extended)
    if len(encoded(value)) > MAX_BYTES:
        raise ArchiveError("Legal source exceeds the account byte budget")
    return value


def validate_source(source: dict) -> None:
    required = {"format", "version", "reference", "profile", "issuer", "subject", "userId", "email",
                "createdTimestamp", "emailVerified", "blockedAt", "expiresAt", "reviewAt"}
    profile = source.get("profile")
    if profile == "case-hold-v1":
        required |= {"case", "ratings"}
    if (set(source) != required or profile not in PROFILES or source.get("format") != "gameometro-legal-source"
            or source.get("version") != 1 or type(source.get("emailVerified")) is not bool):
        raise ArchiveError("Archived source violates its closed field profile")
    if (profile == "pending-signup-v1" and source["emailVerified"] is not False or
            profile == "requested-account-v1" and source["emailVerified"] is not True):
        raise ArchiveError("Archived verification state violates its profile")
    if profile == "case-hold-v1":
        if set(source["case"]) != {"caseReference", "reason"} or len(source["ratings"]) > 100:
            raise ArchiveError("Invalid particular-case source")
        for row in source["ratings"]:
            if set(row) != {"gameId", "value", "createdAt", "updatedAt"}:
                raise ArchiveError("Unapproved exceptional source fields")
    for field in ("blockedAt", "expiresAt", "reviewAt"):
        parse_time(source[field])


class Archive:
    def __init__(self, root: pathlib.Path, copy: pathlib.Path, gnupg: pathlib.Path,
                 recipient: str, signer: str, clock=None):
        self.roots = [private(root, True), private(copy, True)]
        if (self.roots[0] == self.roots[1] or self.roots[0] in self.roots[1].parents
                or self.roots[1] in self.roots[0].parents or os.path.samefile(*self.roots)):
            raise ArchiveError("Archive copies must use separate non-nested storage")
        self.gnupg = private(gnupg, True)
        if any(root == self.gnupg or root in self.gnupg.parents or self.gnupg in root.parents for root in self.roots):
            raise ArchiveError("Keys must be stored separately from ciphertext")
        if not FINGERPRINT.fullmatch(recipient) or not FINGERPRINT.fullmatch(signer):
            raise ArchiveError("Use complete GPG key fingerprints")
        self.recipient, self.signer = recipient.upper(), signer.upper()
        self.clock = clock or (lambda: dt.datetime.now(UTC))
        self.locks = []
        try:
            for root in self.roots:
                path = root / "archive.lock"
                descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
                handle = os.fdopen(descriptor, "a+b")
                self.locks.append(handle)
                private(path)
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BaseException:
            self.close()
            raise ArchiveError("Archive storage is locked or unavailable") from None

    def close(self):
        for handle in getattr(self, "locks", []):
            handle.close()
        self.locks = []

    def __del__(self):
        self.close()

    def gpg(self, *arguments: str, data: bytes | None = None) -> bytes:
        result = subprocess.run(["gpg", "--homedir", str(self.gnupg), "--batch", "--no-tty",
                                 "--max-output", str(MAX_BYTES), *arguments], input=data,
                                capture_output=True, timeout=20, check=False)
        if result.returncode:
            raise ArchiveError("GPG refused the protected operation; inspect privately")
        return result.stdout

    def dirs(self, reference: str, create: bool = True) -> list[pathlib.Path]:
        if not REFERENCE.fullmatch(reference):
            raise ArchiveError("Invalid opaque archive reference")
        result = []
        for root in self.roots:
            directory = root / reference
            if not directory.exists() and create:
                directory.mkdir(mode=0o700)
                # A durable child payload is insufficient if its directory entry can disappear.
                descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
            result.append(private(directory, True))
        return result

    def sign(self, value: bytes) -> bytes:
        return self.gpg("--local-user", self.signer, "--detach-sign", data=value)

    def verify_signature(self, document: pathlib.Path, signature: pathlib.Path) -> None:
        private(document); private(signature)
        result = subprocess.run(["gpg", "--homedir", str(self.gnupg), "--batch", "--no-tty",
                                 "--status-fd", "1", "--verify", str(signature), str(document)],
                                capture_output=True, timeout=20, check=False)
        valid = [line.split()[2:] for line in result.stdout.decode().splitlines()
                 if line.startswith("[GNUPG:] VALIDSIG ")]
        if result.returncode or not any(self.signer in values for values in valid):
            raise ArchiveError("Archive signature does not match the expected owner key")

    def signed_pair(self, directories: list[pathlib.Path], name: str) -> dict:
        values = []
        for directory in directories:
            value = read(directory / name, 16384)
            self.verify_signature(directory / name, directory / (name + ".sig"))
            values.append(value)
        if values[0] != values[1]:
            raise ArchiveError("Independent signed records disagree")
        return json.loads(values[0])

    def require_settled_review(self, directories: list[pathlib.Path]) -> None:
        for directory in directories:
            if any((directory / name).is_symlink() for name in ("review.json", "review.json.sig")):
                raise ArchiveError("Unsafe archive review path requires private reconciliation")
            for name in ("review.json.pending", "review.json.sig.pending"):
                path = directory / name
                if path.exists() or path.is_symlink():
                    raise ArchiveError("An interrupted archive review requires private reconciliation")
        if any((directory / "review.json").exists() or (directory / "review.json.sig").exists()
               for directory in directories):
            self.signed_pair(directories, "review.json")

    def verify(self, reference: str) -> dict:
        directories = self.dirs(reference, create=False)
        self.require_settled_review(directories)
        if any((directory / "destroyed.json").exists() or (directory / "destroyed.json").is_symlink()
               for directory in directories):
            raise ArchiveError("Archive is destroyed or destruction must be reconciled")
        manifest = self.signed_pair(directories, "manifest.json")
        for directory in directories:
            ciphertext = read(directory / "source.gpg")
            if hashlib.sha256(ciphertext).hexdigest() != manifest["ciphertextSha256"]:
                raise ArchiveError("Legal source integrity mismatch")
            source = json.loads(self.gpg("--decrypt", data=ciphertext))
            validate_source(source)
            if (manifest.get("reference") != reference or any(source[key] != manifest.get(key) for key in
                    ("format", "version", "reference", "profile", "blockedAt", "expiresAt", "reviewAt"))):
                raise ArchiveError("Archive source/manifest mismatch")
        return manifest

    def has_source(self, reference: str) -> bool:
        return any((directory / "source.gpg").exists() for directory in self.dirs(reference))

    def seal(self, entry: dict, snapshot: dict | None = None, hold: dict | None = None,
             ratings: list | None = None) -> dict:
        directories = self.dirs(entry["request"])
        if any((directory / "destroyed.json").exists() or (directory / "destroyed.json").is_symlink()
               for directory in directories):
            raise ArchiveError("Never recreate destroyed legal source")
        present = [directory for directory in directories if (directory / "source.gpg").exists()]
        if present:
            ciphertext = read(present[0] / "source.gpg")
            source = json.loads(self.gpg("--decrypt", data=ciphertext))
            if (source["subject"] != entry["keycloakUserId"] or source["userId"] != entry["userId"] or
                    source["profile"] != entry["archiveProfile"]):
                raise ArchiveError("Existing source belongs to another operation")
        else:
            if snapshot is None:
                raise ArchiveError("No pre-deletion source remains for this operation")
            source = profile_payload(entry, snapshot, self.clock(), hold, ratings)
            ciphertext = self.gpg("--trust-model", "always", "--recipient", self.recipient,
                                  "--encrypt", data=encoded(source))
        validate_source(source)
        for directory in directories:
            path = directory / "source.gpg"
            if path.exists():
                if read(path) != ciphertext:
                    raise ArchiveError("Independent source copies disagree")
            else:
                write(path, ciphertext)
        manifest = {key: source[key] for key in
                    ("format", "version", "reference", "profile", "blockedAt", "expiresAt", "reviewAt")}
        if source["profile"] == "case-hold-v1":
            manifest["holdReason"] = source["case"]["caseReference"]
        manifest["ciphertextSha256"] = hashlib.sha256(ciphertext).hexdigest()
        data = encoded(manifest)
        # Only identical staged records can be completed after interruption.
        signature = None
        for directory in directories:
            if (directory / "manifest.json").exists() and read(directory / "manifest.json") != data:
                raise ArchiveError("Existing manifest cannot be overwritten")
            if not (directory / "manifest.json").exists():
                write(directory / "manifest.json", data)
            if (directory / "manifest.json.sig").exists():
                self.verify_signature(directory / "manifest.json", directory / "manifest.json.sig")
                signature = read(directory / "manifest.json.sig")
        signature = signature or self.sign(data)
        for directory in directories:
            if not (directory / "manifest.json.sig").exists():
                write(directory / "manifest.json.sig", signature)
        return self.verify(entry["request"])

    def review(self, reference: str, *, hold_reason: str | None = None,
               extend_until: str | None = None, release_hold: bool = False) -> dict:
        manifest = self.verify(reference)
        directories = self.dirs(reference)
        if any((directory / "review.json").exists() for directory in directories):
            previous = self.signed_pair(directories, "review.json")
        else:
            previous = manifest
        expiry = parse_time(previous["expiresAt"])
        if extend_until:
            requested = parse_time(extend_until)
            if requested < expiry or not hold_reason:
                raise ArchiveError("Extension requires a particular reason and cannot shorten expiry")
            expiry = requested
        if hold_reason is not None and (not isinstance(hold_reason, str) or not REFERENCE.fullmatch(hold_reason)):
            raise ArchiveError("Use an opaque particular-case hold reference; keep grounds in protected case records")
        current = self.clock()
        reason = None if release_hold else (hold_reason or previous.get("holdReason"))
        review = {"reference": reference, "ciphertextSha256": manifest["ciphertextSha256"],
                  "reviewedAt": timestamp(current), "reviewAt": timestamp(years(current, 1)),
                  "expiresAt": timestamp(expiry), "holdReason": reason}
        history = previous.get("history", [])
        if not isinstance(history, list) or len(history) >= 64:
            raise ArchiveError("Review history needs a bounded owner retention review")
        review["history"] = [*history, {key: review[key] for key in
                                       ("reviewedAt", "expiresAt", "holdReason")}]
        value = encoded(review)
        if len(value) > 16384:
            raise ArchiveError("Review history exceeds its metadata budget")
        signature = self.sign(value)
        for directory in directories:
            write(directory / "review.json", value, replace=True)
            write(directory / "review.json.sig", signature, replace=True)
        return review

    def purge(self, reference: str, execute: bool = False) -> dict:
        directories = self.dirs(reference, create=False)
        destroyed = any((directory / "destroyed.json").exists() or (directory / "destroyed.json").is_symlink()
                        for directory in directories)
        if not destroyed:
            self.require_settled_review(directories)
        if destroyed:
            # Complete a previously signed write-ahead destruction intent after a copy outage.
            available = [directory for directory in directories
                         if (directory / "destroyed.json").exists() and (directory / "destroyed.json.sig").exists()]
            if not available:
                raise ArchiveError("Destruction intent is unsigned; reconcile privately")
            owner_copy = available[0]
            self.verify_signature(owner_copy / "destroyed.json", owner_copy / "destroyed.json.sig")
            value = read(owner_copy / "destroyed.json", 16384)
            tombstone = json.loads(value)
            if tombstone.get("reference") != reference:
                raise ArchiveError("Destruction proof is for another operation")
            for directory in directories:
                path = directory / "source.gpg"
                if path.exists() and hashlib.sha256(read(path)).hexdigest() != tombstone["ciphertextSha256"]:
                    raise ArchiveError("Restored source differs from its destruction proof")
                for name in ("destroyed.json", "destroyed.json.sig"):
                    path = directory / name
                    expected = value if name.endswith(".json") else read(owner_copy / name)
                    if path.exists() and read(path) != expected:
                        raise ArchiveError("Destruction records disagree")
                    if not path.exists() and execute:
                        write(path, expected)
            if execute:
                self.signed_pair(directories, "destroyed.json")
        else:
            manifest = self.verify(reference)
            if any((directory / "review.json").exists() for directory in directories):
                review = self.signed_pair(directories, "review.json")
                if review.get("ciphertextSha256") != manifest["ciphertextSha256"]:
                    raise ArchiveError("Review is for another legal source")
            else:
                review = manifest
            current = self.clock()
            if review.get("holdReason") or current >= parse_time(review["reviewAt"]):
                raise ArchiveError("Archive has an active hold or requires annual review")
            if current < parse_time(review["expiresAt"]):
                raise ArchiveError("Archive has not expired")
            tombstone = {"reference": reference, "destroyedAt": timestamp(current),
                         "ciphertextSha256": manifest["ciphertextSha256"], "kind": "legal-source-destruction"}
        if not execute:
            return {"reference": reference, "action": "would-destroy"}
        if not destroyed:
            value = encoded(tombstone); signature = self.sign(value)
            for directory in directories:
                write(directory / "destroyed.json", value)
                write(directory / "destroyed.json.sig", signature)
            self.signed_pair(directories, "destroyed.json")
        # Keep destruction proof to prevent an archive-store restore resurrecting its payload.
        for directory in directories:
            for name in ("source.gpg", "manifest.json", "manifest.json.sig", "review.json", "review.json.sig", "review.json.pending", "review.json.sig.pending"):
                path = directory / name
                if path.exists():
                    private(path)
                    path.unlink()
            descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        return {"reference": reference, "action": "destroyed"}
