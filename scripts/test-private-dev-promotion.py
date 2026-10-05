#!/usr/bin/env python3
"""Offline regressions for the promotion trust/SSH/runtime boundary."""

import contextlib
import copy
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import stat
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error
import zipfile

import private_dev_promotion as policy

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader("promotion_host", str(ROOT / "deploy/private-dev/bin/promote-private-dev"))
spec = importlib.util.spec_from_loader(loader.name, loader)
host = importlib.util.module_from_spec(spec)
loader.exec_module(host)
REVISION = "a" * 40
DIGEST = "sha256:" + "b" * 64
CONTRACT = "c" * 64
COMMAND = f"promote {REVISION} {DIGEST} {CONTRACT} 123 2"


def run_record(workflow="build-and-verify.yml", run_id=123):
    return {
        "id": run_id, "head_repository": {"full_name": policy.REPOSITORY},
        "event": "push", "head_branch": "main", "head_sha": REVISION,
        "path": f".github/workflows/{workflow}", "status": "completed",
        "conclusion": "success", "run_attempt": 2, "run_started_at": "2026-10-05T10:00:00Z",
    }


def api_fixture():
    values = {
        "/actions/runs/123": run_record(),
        "/actions/runs/123/jobs?filter=latest&per_page=100": {
            "total_count": 2, "jobs": [
                {"name": name, "conclusion": "success"}
                for name in ("Required quality gate", "Publish immutable application image")
            ],
        },
        f"/compare/{REVISION}...main": {"status": "ahead", "merge_base_commit": {"sha": REVISION}},
        f"/actions/workflows/security.yml/runs?head_sha={REVISION}&branch=main&event=push&per_page=1": {
            "workflow_runs": [{"id": 456}],
        },
        "/actions/runs/456": run_record("security.yml", 456),
        "/actions/runs/456/jobs?filter=latest&per_page=100": {
            "total_count": 1, "jobs": [{"name": "Required security gate", "conclusion": "success"}],
        },
        "/actions/runs/123/artifacts?per_page=100": {
            "total_count": 1, "artifacts": [{
                "id": 789, "name": f"application-image-publication-{REVISION}", "expired": False,
                "created_at": "2026-10-05T10:30:00Z",
            }],
        },
        "/environments/dev": {
            "protection_rules": [{
                "type": "required_reviewers", "prevent_self_review": False,
                "reviewers": [{"type": "User", "reviewer": {"login": "rubhern"}}],
            }],
            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True},
        },
        "/environments/dev/deployment-branch-policies?per_page=100": {
            "total_count": 1, "branch_policies": [{"name": "main", "type": "branch"}],
        },
    }
    bundle = io.BytesIO()
    with zipfile.ZipFile(bundle, "w") as archive:
        archive.writestr("published-image.txt",
                        f"tag={policy.IMAGE_REPOSITORY}:{REVISION}\ndigest={policy.IMAGE_REPOSITORY}@{DIGEST}\n")
    values["/actions/artifacts/789/zip"] = bundle.getvalue()
    return values, Mock(request=lambda path, **_kwargs: copy.deepcopy(values[path]))


class EvidenceTests(unittest.TestCase):
    def test_receipt_summary_preserves_failure_and_refuses_forged_metadata(self):
        receipt = {
            "sourceRevision": REVISION, "image": f"{policy.IMAGE_REPOSITORY}@{DIGEST}",
            "sourceRunId": 123, "sourceRunAttempt": 2,
            "applicationVersion": "0.28.0-SNAPSHOT", "migrationVersion": "20261005.120000",
            "outcome": "failure", "phase": "database-migration",
            "unexpectedPrivateLog": "test-only-credential",
        }
        summary = policy.receipt_summary(receipt, REVISION, DIGEST, "123", "2")
        self.assertIn("**failure**", summary)
        self.assertNotIn("test-only-credential", summary)
        for field, value in (("image", "other"), ("applicationVersion", "version\n`injection`"),
                             ("outcome", "success")):
            with self.subTest(field=field), self.assertRaises(policy.Refused):
                policy.receipt_summary({**receipt, field: value}, REVISION, DIGEST, "123", "2")

    def test_successful_main_publication_and_approval_configuration(self):
        _, api = api_fixture()
        policy.verify_environment(api)
        run, security_id = policy.verify_trusted_main(api, REVISION, DIGEST, "123", "2")
        self.assertEqual(security_id, 456)
        policy.verify_publication(api, run, REVISION, DIGEST)

    def test_untrusted_failed_missing_and_stale_runs_are_refused(self):
        mutations = (
            ("event", "pull_request"), ("head_branch", "feature"),
            ("head_sha", "d" * 40), ("path", ".github/workflows/other.yml"),
            ("head_repository", {"full_name": "attacker/fork"}),
            ("status", "in_progress"), ("conclusion", "cancelled"), ("run_attempt", 3),
        )
        for key, value in mutations:
            with self.subTest(key=key):
                values, api = api_fixture()
                values["/actions/runs/123"][key] = value
                with self.assertRaises(policy.Refused):
                    policy.verify_trusted_main(api, REVISION, DIGEST, "123", "2")
        for name in ("Required quality gate", "Publish immutable application image"):
            values, api = api_fixture()
            values["/actions/runs/123/jobs?filter=latest&per_page=100"]["jobs"] = [
                {"name": name, "conclusion": "failure"},
            ]
            with self.assertRaises(policy.Refused):
                policy.verify_trusted_main(api, REVISION, DIGEST, "123", "2")

    def test_current_main_ancestry_and_latest_security_are_required(self):
        for key in ("comparison", "security-absent", "security-failed"):
            with self.subTest(key=key):
                values, api = api_fixture()
                if key == "comparison":
                    values[f"/compare/{REVISION}...main"]["merge_base_commit"]["sha"] = "d" * 40
                elif key == "security-absent":
                    values[f"/actions/workflows/security.yml/runs?head_sha={REVISION}&branch=main&event=push&per_page=1"]["workflow_runs"] = []
                else:
                    values["/actions/runs/456"]["conclusion"] = "failure"
                with self.assertRaises(policy.Refused):
                    policy.verify_trusted_main(api, REVISION, DIGEST, "123", "2")

    def test_publication_digest_expiry_and_run_attempt_binding(self):
        values, api = api_fixture()
        with self.assertRaises(policy.Refused):
            policy.verify_publication(api, run_record(), REVISION, "sha256:" + "d" * 64)
        for key, value in (("expired", True), ("created_at", "2026-10-04T10:00:00Z")):
            with self.subTest(key=key):
                values["/actions/runs/123/artifacts?per_page=100"]["artifacts"][0][key] = value
                with self.assertRaises(policy.Refused):
                    policy.verify_publication(api, run_record(), REVISION, DIGEST)

    def test_missing_reviewers_and_extra_branch_or_tag_policies_are_refused(self):
        values, api = api_fixture()
        values["/environments/dev"]["protection_rules"] = []
        with self.assertRaises(policy.Refused):
            policy.verify_environment(api)
        values, api = api_fixture()
        values["/environments/dev/deployment-branch-policies?per_page=100"]["total_count"] = 2
        with self.assertRaises(policy.Refused):
            policy.verify_environment(api)

    def test_artifact_redirect_never_forwards_the_workflow_token(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError(
            policy.API_ROOT, 302, "", {"Location": "https://storage.example/signed"}, None,
        )
        response = io.BytesIO(b"publication")
        with patch.object(policy.urllib.request, "build_opener", return_value=opener), \
                patch.object(policy.urllib.request, "urlopen", return_value=response) as storage:
            self.assertEqual(policy.GitHub("test-only-token").request("/actions/artifacts/1/zip", archive=True), b"publication")
        self.assertEqual(opener.open.call_args.args[0].get_header("Authorization"), "Bearer test-only-token")
        self.assertEqual(storage.call_args.args, ("https://storage.example/signed",))


class HostTests(unittest.TestCase):
    def test_a_different_runtime_project_is_refused(self):
        configuration = Mock()
        with patch.object(host, "RUNTIME_ENV", configuration):
            configuration.read_text.return_value = "COMPOSE_PROJECT_NAME=another-project\n"
            with self.assertRaises(policy.Refused):
                host.verify_runtime_project()
            configuration.read_text.return_value = "COMPOSE_PROJECT_NAME=videogame-platform-dev\n"
            host.verify_runtime_project()

    def test_forced_entry_point_rejects_a_non_installed_invocation_before_loading_repository_code(self):
        result = subprocess.run(
            ["/usr/bin/python3", "-I", str(ROOT / "deploy/private-dev/bin/promote-private-dev")],
            env={"PATH": "/usr/bin:/bin", "SUDO_USER": "vgp-deploy", "SSH_ORIGINAL_COMMAND": COMMAND},
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_unacknowledged_runtime_stops_before_any_host_work(self):
        approval = Mock()
        approval.lstat.return_value = Mock(st_uid=0, st_mode=stat.S_IFREG | 0o640)
        approval.read_text.return_value = "d" * 64
        with patch.object(host.socket, "gethostname", return_value="vgpdev"), \
                patch.object(host, "deployment_contract", return_value=CONTRACT), \
                patch.object(host, "APPROVED_CONTRACT", approval), \
                patch.object(host, "verify_trusted_main") as verify, \
                patch.object(host.subprocess, "run") as deploy:
            with self.assertRaisesRegex(policy.Refused, "has not been acknowledged"):
                host.promote(COMMAND)
            verify.assert_not_called()
            deploy.assert_not_called()

    def test_active_localization_overlay_is_never_silently_removed(self):
        with patch.object(host.subprocess, "check_output", side_effect=[
            b"container-id\n", b"/opt/videogame-platform/deploy/private-dev/compose.yaml,/opt/videogame-platform/deploy/private-dev/compose.localization.yaml\n",
        ]), self.assertRaisesRegex(policy.Refused, "Compose overlay"):
            host.verify_base_application()
        with patch.object(host.subprocess, "check_output", side_effect=[
            b"container-id\n", b"/opt/videogame-platform/deploy/private-dev/compose.yaml\n",
        ]):
            host.verify_base_application()

    def test_shell_scp_injection_and_arbitrary_arguments_are_refused(self):
        for command in ("sh", "scp -t /tmp", COMMAND + "; id", COMMAND + " extra",
                        COMMAND.replace(REVISION, "$(id)"), COMMAND.replace(DIGEST, "latest")):
            with self.subTest(command=command), self.assertRaises((policy.Refused, ValueError)):
                host.parse_command(command)
        self.assertEqual(host.parse_command(COMMAND), (REVISION, DIGEST, CONTRACT, "123", "2"))

    def test_runtime_drift_stops_before_deployment_or_network(self):
        with patch.object(host.socket, "gethostname", return_value="vgpdev"), \
                patch.object(host, "verify_installation"), \
                patch.object(host, "deployment_contract", return_value="d" * 64), \
                patch.object(host, "verify_trusted_main") as verify, \
                patch.object(host.subprocess, "run") as deploy:
            with self.assertRaisesRegex(policy.Refused, "Runtime/deployment contracts differ"):
                host.promote(COMMAND)
            verify.assert_not_called()
            deploy.assert_not_called()

    def test_success_and_failure_keep_logs_private_and_return_original_evidence(self):
        for code, outcome, phase in ((0, "success", "complete"), (42, "failure", "database-migration")):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                def fake_deploy(arguments, **kwargs):
                    self.assertEqual(arguments[0], str(host.REPOSITORY_ROOT / "deploy/private-dev/bin/deploy-private-dev"))
                    self.assertEqual(kwargs["env"], host.SAFE_ENV)
                    kwargs["stdout"].write("test-only-credential\n")
                    directory = Path(arguments[-1])
                    (directory / "evidence.json").write_text(json.dumps({
                        "sourceRevision": REVISION, "image": f"{policy.IMAGE_REPOSITORY}@{DIGEST}",
                        "applicationVersion": "0.28.0-SNAPSHOT", "migrationVersion": "20261005.120000",
                        "outcome": outcome, "phase": phase,
                    }), encoding="utf-8")
                    return subprocess.CompletedProcess(arguments, code)
                stdout = io.StringIO()
                approval = Mock()
                approval.lstat.return_value = Mock(st_uid=0, st_mode=stat.S_IFREG | 0o640)
                approval.read_text.return_value = CONTRACT
                with patch.object(host.socket, "gethostname", return_value="vgpdev"), \
                        patch.object(host, "verify_installation"), \
                        patch.object(host, "deployment_contract", return_value=CONTRACT), \
                        patch.object(host, "verify_trusted_main", return_value=(run_record(), 456)), \
                        patch.object(host, "remote_deployment_contract", return_value=CONTRACT), \
                        patch.object(host, "verify_base_application"), \
                        patch.object(host, "verify_runtime_project"), \
                        patch.object(host, "APPROVED_CONTRACT", approval), \
                        patch.object(host, "EVIDENCE_ROOT", root), \
                        patch.object(host.subprocess, "run", side_effect=fake_deploy), \
                        contextlib.redirect_stdout(stdout):
                    self.assertEqual(host.promote(COMMAND), 0 if code == 0 else 1)
                receipt = json.loads(stdout.getvalue())
                self.assertEqual((receipt["outcome"], receipt["phase"]), (outcome, phase))
                self.assertNotIn("test-only-credential", stdout.getvalue())
                self.assertEqual(json.loads(Path(receipt["evidencePath"]).read_text())["outcome"], outcome)
                log = next(root.glob("*/deployment.log"))
                self.assertEqual(log.stat().st_mode & 0o777, 0o640)


class ContractTests(unittest.TestCase):
    def test_remote_and_git_fingerprints_bind_the_same_paths_modes_and_blob_bytes(self):
        entries = [b"100644 blob " + b"a" * 40 + b"\tdeploy/private-dev/compose.yaml",
                   b"100755 blob " + b"b" * 40 + b"\tdocker/postgres/init/bootstrap.sh"]
        api = Mock()
        api.request.return_value = {"truncated": False, "tree": [
            {"mode": "040000", "type": "tree", "sha": "c" * 40, "path": "deploy/private-dev/bin"},
            {"mode": "100755", "type": "blob", "sha": "b" * 40, "path": "docker/postgres/init/bootstrap.sh"},
            {"mode": "100644", "type": "blob", "sha": "a" * 40, "path": "deploy/private-dev/compose.yaml"},
            {"mode": "100644", "type": "blob", "sha": "d" * 40, "path": "frontend/src/main.tsx"},
        ]}
        self.assertEqual(policy.remote_deployment_contract(api, REVISION), policy.contract_hash(entries))
        api.request.return_value["truncated"] = True
        with self.assertRaises(policy.Refused):
            policy.remote_deployment_contract(api, REVISION)

    def test_runtime_coverage_includes_dependency_and_helper_contracts(self):
        for path in ("deploy/private-dev/runtime.env.example",
                     "deploy/private-dev/bin/run-application", "deploy/private-dev/smoke/package-lock.json",
                     "docker/keycloak/import/videogame-platform-realm.json",
                     "docker/postgres/init/001-create-databases.sh",
                     "tools/catalogue-localization/models.json", "scripts/private_dev_promotion.py"):
            with self.subTest(path=path):
                self.assertTrue(policy.is_deployment_contract(path))

    def test_runtime_changes_block_but_app_and_documentation_changes_do_not(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=repo).decode().strip()
            git("init", "-q")
            for path in ("deploy/private-dev/compose.yaml", "deploy/private-dev/README.md", "frontend/src/main.tsx"):
                target = repo / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("initial", encoding="utf-8")
            def snapshot():
                git("add", ".")
                git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                    "commit", "-qm", "fixture")
                return git("rev-parse", "HEAD")
            first = snapshot()
            fingerprint = policy.deployment_contract(repo, first)
            (repo / "frontend/src/main.tsx").write_text("app change", encoding="utf-8")
            (repo / "deploy/private-dev/README.md").write_text("doc change", encoding="utf-8")
            self.assertEqual(policy.deployment_contract(repo, snapshot()), fingerprint)
            runtime = repo / "deploy/private-dev/compose.yaml"
            runtime.write_text("dependency update", encoding="utf-8")
            with self.assertRaises(policy.Refused):
                policy.deployment_contract(repo, "HEAD", installed=True)
            self.assertNotEqual(policy.deployment_contract(repo, snapshot()), fingerprint)
            git("update-index", "--assume-unchanged", "deploy/private-dev/compose.yaml")
            runtime.write_text("hidden drift", encoding="utf-8")
            with self.assertRaises(policy.Refused):
                policy.deployment_contract(repo, "HEAD", installed=True)


if __name__ == "__main__":
    unittest.main()
