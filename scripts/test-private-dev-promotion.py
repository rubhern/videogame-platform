#!/usr/bin/env python3
"""Offline regressions for the promotion trust/SSH/runtime boundary."""

import contextlib
import copy
import importlib.machinery
import importlib.util
import io
import json
import os
import re
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
        f"/actions/workflows/build-and-verify.yml/runs?head_sha={REVISION}&branch=main&event=push&per_page=100": {
            "total_count": 1, "workflow_runs": [run_record()],
        },
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
            "protection_rules": [{"type": "branch_policy"}], "can_admins_bypass": False,
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

    def test_successful_main_publication_and_dispatch_only_configuration(self):
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

    def test_second_approval_bypass_and_extra_branch_or_tag_policies_are_refused(self):
        for rule in ("required_reviewers", "wait_timer", "custom_deployment_protection_rule", "unknown"):
            values, api = api_fixture()
            values["/environments/dev"]["protection_rules"].append({"type": rule})
            with self.subTest(rule=rule), self.assertRaises(policy.Refused):
                policy.verify_environment(api)
        values, api = api_fixture()
        values["/environments/dev"]["can_admins_bypass"] = True
        with self.assertRaises(policy.Refused):
            policy.verify_environment(api)
        values, api = api_fixture()
        values["/environments/dev/deployment-branch-policies?per_page=100"]["total_count"] = 2
        with self.assertRaises(policy.Refused):
            policy.verify_environment(api)
        for branch in ({"name": "main", "type": "tag"}, {"name": "*", "type": "branch"}):
            values, api = api_fixture()
            values["/environments/dev/deployment-branch-policies?per_page=100"]["branch_policies"] = [branch]
            with self.subTest(branch=branch), self.assertRaises(policy.Refused):
                policy.verify_environment(api)
        values, api = api_fixture()
        values["/environments/dev"]["deployment_branch_policy"] = None
        with self.assertRaises(policy.Refused):
            policy.verify_environment(api)

    def test_discovery_derives_the_exact_sha_run_current_attempt_and_digest(self):
        _, api = api_fixture()
        result = policy.discover_promotion(api, REVISION)
        self.assertEqual(result, {"source_revision": REVISION, "source_run_id": "123",
                                 "source_run_attempt": "2", "image_digest": DIGEST, "securityRunId": 456})

    def test_discovery_never_selects_another_sha_or_ambiguous_or_missing_run(self):
        path = f"/actions/workflows/build-and-verify.yml/runs?head_sha={REVISION}&branch=main&event=push&per_page=100"
        for records in ([], [run_record(), run_record(run_id=124)],
                        [run_record(), {**run_record(run_id=124), "conclusion": "failure"}]):
            values, api = api_fixture()
            values[path] = {"total_count": len(records), "workflow_runs": records}
            with self.subTest(records=len(records)), self.assertRaises(policy.Refused):
                policy.discover_promotion(api, REVISION)
        values, api = api_fixture()
        values[path]["total_count"] = 101
        with self.assertRaises(policy.Refused):
            policy.discover_promotion(api, REVISION)
        for key, value in (("head_sha", "d" * 40), ("id", 124), ("event", "pull_request"),
                           ("head_branch", "feature"), ("conclusion", "failure"), ("status", "queued")):
            values, api = api_fixture()
            values["/actions/runs/123"][key] = value
            with self.subTest(key=key), self.assertRaises(policy.Refused):
                policy.discover_promotion(api, REVISION)

    def test_discovery_refuses_missing_expired_duplicate_or_old_attempt_publication(self):
        path = "/actions/runs/123/artifacts?per_page=100"
        for mutation in ("missing", "expired", "duplicate", "old-attempt", "truncated"):
            values, api = api_fixture()
            artifact = values[path]["artifacts"][0]
            if mutation == "missing":
                values[path] = {"total_count": 0, "artifacts": []}
            elif mutation == "expired":
                artifact["expired"] = True
            elif mutation == "duplicate":
                values[path]["artifacts"].append({**artifact, "id": 790})
                values[path]["total_count"] = 2
            elif mutation == "old-attempt":
                artifact["created_at"] = "2026-10-04T10:00:00Z"
            else:
                values[path]["total_count"] = 101
            with self.subTest(mutation=mutation), self.assertRaises(policy.Refused):
                policy.discover_promotion(api, REVISION)

    def test_discovery_refuses_mutable_mismatched_or_ambiguous_archive_records(self):
        valid_tag = f"tag={policy.IMAGE_REPOSITORY}:{REVISION}\n"
        valid_digest = f"digest={policy.IMAGE_REPOSITORY}@{DIGEST}\n"
        records = (valid_tag, valid_tag + valid_digest * 2,
                   valid_tag + f"digest={policy.IMAGE_REPOSITORY}:latest\n",
                   valid_tag + valid_digest.replace(policy.IMAGE_REPOSITORY, "ghcr.io/other/image"),
                   valid_tag.replace(REVISION, "d" * 40) + valid_digest)
        for record in records:
            values, api = api_fixture()
            bundle = io.BytesIO()
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("published-image.txt", record)
            values["/actions/artifacts/789/zip"] = bundle.getvalue()
            with self.subTest(record=record), self.assertRaises(policy.Refused):
                policy.discover_promotion(api, REVISION)

    def test_discovery_refuses_failed_security_and_attempt_changed_during_download(self):
        values, api = api_fixture()
        values["/actions/runs/456"]["conclusion"] = "failure"
        with self.assertRaises(policy.Refused):
            policy.discover_promotion(api, REVISION)
        values, api = api_fixture()
        original = api.request
        def request(path, **kwargs):
            if path == "/actions/artifacts/789/zip":
                values["/actions/runs/123"]["run_attempt"] = 3
            return original(path, **kwargs)
        api.request = request
        with self.assertRaisesRegex(policy.Refused, "stale"):
            policy.discover_promotion(api, REVISION)

    def test_discovery_cli_emits_bound_outputs_only_after_all_checks(self):
        values, api = api_fixture()
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "outputs"
            argv = ["promotion", "--discover", "--revision", REVISION]
            with patch.object(policy.sys, "argv", argv), patch.object(policy, "GitHub", return_value=api), \
                    patch.object(policy, "deployment_contract", return_value=CONTRACT) as contract, \
                    patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), contextlib.redirect_stdout(io.StringIO()):
                policy.main()
                contract.assert_called_once_with(ROOT, REVISION)
                self.assertEqual(dict(line.split("=", 1) for line in output.read_text().splitlines()), {
                    "contract": CONTRACT, "source_revision": REVISION, "image_digest": DIGEST,
                    "source_run_id": "123", "source_run_attempt": "2",
                })
                output.unlink()
                values["/environments/dev"]["protection_rules"].append({"type": "required_reviewers"})
                with self.assertRaises(policy.Refused):
                    policy.main()
                self.assertFalse(output.exists())
            for flag, value in (("--digest", DIGEST), ("--run-id", "123"), ("--attempt", "2")):
                with patch.object(policy.sys, "argv", argv + [flag, value]), self.assertRaises(policy.Refused):
                    policy.main()

    def test_preconnect_recheck_cannot_reselect_a_changed_tuple_or_ambiguous_run(self):
        for mutation in ("digest", "run", "attempt", "new-candidate"):
            values, api = api_fixture()
            digest, run_id, attempt = DIGEST, "123", "2"
            if mutation == "digest":
                digest = "sha256:" + "d" * 64
            elif mutation == "run":
                run_id = "124"
            elif mutation == "attempt":
                attempt = "1"
            else:
                path = f"/actions/workflows/build-and-verify.yml/runs?head_sha={REVISION}&branch=main&event=push&per_page=100"
                values[path] = {"total_count": 2, "workflow_runs": [run_record(), run_record(run_id=124)]}
            argv = ["promotion", "--revision", REVISION, "--digest", digest,
                    "--run-id", run_id, "--attempt", attempt]
            with self.subTest(mutation=mutation), patch.object(policy.sys, "argv", argv), \
                    patch.object(policy, "GitHub", return_value=api), \
                    patch.object(policy, "deployment_contract") as contract, self.assertRaises(policy.Refused):
                policy.main()
            contract.assert_not_called()

    def test_preconnect_recheck_accepts_the_unchanged_derived_tuple(self):
        _, api = api_fixture()
        stdout = io.StringIO()
        argv = ["promotion", "--revision", REVISION, "--digest", DIGEST,
                "--run-id", "123", "--attempt", "2"]
        with patch.object(policy.sys, "argv", argv), patch.object(policy, "GitHub", return_value=api), \
                patch.object(policy, "deployment_contract", return_value=CONTRACT), \
                patch.dict(os.environ, {"GITHUB_OUTPUT": ""}), contextlib.redirect_stdout(stdout):
            policy.main()
        result = json.loads(stdout.getvalue())
        self.assertEqual((result["source_revision"], result["image_digest"], result["source_run_id"],
                          result["source_run_attempt"], result["contract"]), (REVISION, DIGEST, "123", "2", CONTRACT))

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


class WorkflowTests(unittest.TestCase):
    def test_dispatch_has_no_inputs_and_both_jobs_require_main_and_owner(self):
        workflow = (ROOT / ".github/workflows/deploy-private-dev.yml").read_text()
        trigger = workflow.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(trigger.strip(), "workflow_dispatch:")
        for job in ("verify", "promote"):
            section = workflow.split(f"\n  {job}:\n", 1)[1]
            section = re.split(r"\n  [a-z]+:\n", section, maxsplit=1)[0]
            self.assertIn("github.ref == 'refs/heads/main'", section)
            self.assertIn("github.actor == 'rubhern'", section)
            self.assertIn("github.triggering_actor == 'rubhern'", section)
            self.assertIn("ref: ${{ github.sha }}", section)

    def test_only_verified_dispatch_outputs_reach_the_environment_and_host(self):
        workflow = (ROOT / ".github/workflows/deploy-private-dev.yml").read_text()
        verify, promote = workflow.split("\n  promote:\n", 1)
        self.assertIn("SOURCE_REVISION: ${{ github.sha }}", verify)
        self.assertIn('--discover --revision "$SOURCE_REVISION"', verify)
        self.assertNotIn("secrets.", verify)
        self.assertNotIn("environment:", verify)
        self.assertNotIn("inputs.", workflow)
        self.assertIn("needs: verify", promote)
        self.assertIn("environment:\n      name: dev", promote)
        for key in ("source_revision", "image_digest", "source_run_id", "source_run_attempt"):
            self.assertIn(f"{key}: ${{{{ steps.verify.outputs.{key} }}}}", verify)
            self.assertEqual(promote.count('${{ needs.verify.outputs.' + key + ' }}'), 3)
        self.assertLess(promote.index('python3 scripts/private_dev_promotion.py'), promote.index('secrets.PRIVATE_DEV_SSH_KEY'))
        self.assertIn('tags: tag:vgp-deploy', promote)
        self.assertIn('StrictHostKeyChecking=yes', promote)
        self.assertIn('"vgp-deploy@$DEPLOY_HOST"', promote)
        self.assertIn('"promote $SOURCE_REVISION $IMAGE_DIGEST $CONTRACT $SOURCE_RUN_ID $SOURCE_RUN_ATTEMPT"', promote)


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
