import json
import os
import subprocess
import sys
import tempfile
import contextlib
import hashlib
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import supplypilot_orchestrator as orch
import supplypilot_full_gate as full_gate


def row(number, dependencies=(), **kwargs):
    result = {"number": number, "dependencies": list(dependencies), "merged": False,
              "closed": False, "base_sha": "main-a", "contract_verified": True,
              "contract_version": "v1", "verified_contract_version": "v1",
              "implementation_state": "NOT_STARTED", "owned_files": []}
    result.update(kwargs)
    return result


class GraphTests(unittest.TestCase):
    def _verified_artifact(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        artifact = Path(temp.name) / "gate.log"
        artifact.write_text("runner transcript", encoding="utf-8")
        return [{"path": str(artifact), "sha256": hashlib.sha256(
            artifact.read_bytes()).hexdigest()}]

    def test_three_independent_issues_are_ready(self):
        graph = {"main_sha": "main-a", "issues": [row(10), row(20), row(30)]}
        plan = orch.plan_graph(graph)
        self.assertEqual([10, 20, 30], plan["ready_frontier"])

    def test_unmerged_dependency_blocks_release_and_code(self):
        graph = {"main_sha": "main-a", "issues": [row(1), row(2, [1])]}
        plan = orch.plan_graph(graph)
        self.assertEqual([1], plan["ready_frontier"])
        self.assertFalse(plan["issues"]["2"]["gpt_final_pass"])
        self.assertEqual([], plan["release_frontier"])

    def test_user_authorized_provisional_contract_only_opens_code_gate(self):
        source = {"issue": 1, "branch": "codex/issue-1-a", "commit_sha": "abc",
                  "contract_version": "v1", "contract": "field x exists",
                  "verification_state": "TARGETED_VERIFIED"}
        graph = {"main_sha": "main-a", "issues": [
            row(1), row(2, [1], provisional_contract={
                "user_authorized": True, "sources": [source]})]}
        plan = orch.plan_graph(graph)
        self.assertEqual([1, 2], plan["ready_frontier"])
        self.assertTrue(plan["issues"]["2"]["provisional"])
        self.assertFalse(plan["issues"]["2"]["gpt_final_pass"])

    def test_missing_dependency_rejected(self):
        with self.assertRaisesRegex(orch.PolicyError, "missing dependency"):
            orch.validate_graph({"main_sha": "a", "issues": [row(2, [1])]})

    def test_cycle_rejected(self):
        with self.assertRaisesRegex(orch.PolicyError, "Circular dependency"):
            orch.validate_graph({"main_sha": "a", "issues": [row(1, [2]), row(2, [1])]})

    def test_merged_dependency_advances_frontier(self):
        dep = row(1, merged=True, closed=True)
        graph = {"main_sha": "main-a", "issues": [dep, row(2, [1])]}
        self.assertEqual([2], orch.plan_graph(graph)["ready_frontier"])

    def test_stale_base_is_not_release_ready(self):
        graph = {"main_sha": "new-main", "issues": [
            row(1, base_sha="old-main", merged=True, closed=True)]}
        plan = orch.plan_graph(graph)
        self.assertFalse(plan["issues"]["1"]["gpt_final_pass"])

    def test_merged_issue_is_not_a_release_candidate(self):
        graph = {"main_sha": "main-a", "issues": [
            row(1, merged=True, closed=True)]}
        plan = orch.plan_graph(graph)
        self.assertEqual([], plan["release_frontier"])

    def test_changed_contract_version_blocks_release_until_reverified(self):
        graph = {"main_sha": "main-a", "issues": [
            row(1, contract_version="v2", verified_contract_version="v1")]}
        plan = orch.plan_graph(graph)
        self.assertFalse(plan["issues"]["1"]["gpt_final_pass"])
        self.assertIn("contract_version_changed_or_unverified", plan["issues"]["1"]["reasons"])

    def test_existing_wip_is_never_reassigned(self):
        graph = {"main_sha": "main-a", "issues": [
            row(1, implementation_state="IMPLEMENTING")]}
        plan = orch.plan_graph(graph)
        self.assertEqual([], plan["ready_frontier"])
        self.assertIn("already_assigned_or_started", plan["issues"]["1"]["reasons"])

    def test_shared_file_owner_blocks_parallel_dispatch(self):
        graph = {"main_sha": "a", "issues": [
            row(1, owned_files=["backend/schema.sql"]),
            row(2, owned_files=["backend/schema.sql"]),
            row(3)]}
        plan = orch.plan_graph(graph)
        self.assertEqual([3], plan["ready_frontier"])

    def test_parent_directory_owner_conflicts_with_child_path(self):
        graph = {"main_sha": "a", "issues": [
            row(1, owned_files=["frontend/src/"]),
            row(2, owned_files=["frontend/src/components/Kanban.tsx"]),
            row(3, owned_files=["frontend/src2/"])]}
        plan = orch.plan_graph(graph)
        self.assertEqual([3], plan["ready_frontier"])

    def test_active_issue_owns_same_file_until_terminal_release(self):
        graph = {"main_sha": "a", "issues": [
            row(1, implementation_state="IMPLEMENTING", owned_files=["backend\\schema.sql"]),
            row(2, owned_files=["BACKEND/schema.sql"]), row(3)]}
        plan = orch.plan_graph(graph)
        self.assertEqual([3], plan["ready_frontier"])
        self.assertIn("file_owned_by_active_issue_1", " ".join(plan["issues"]["2"]["reasons"]))
        graph["issues"][0].update(implementation_state="MERGED", merged=True, closed=True)
        self.assertEqual([2, 3], orch.plan_graph(graph)["ready_frontier"])

    def test_active_parent_path_blocks_candidate_child_file(self):
        graph = {"main_sha": "a", "issues": [
            row(1, implementation_state="IMPLEMENTING", owned_files=["frontend/src"]),
            row(2, owned_files=["frontend/src/components/Board.tsx"])]}
        self.assertEqual([], orch.plan_graph(graph)["ready_frontier"])

    def test_active_child_path_blocks_candidate_parent_directory(self):
        graph = {"main_sha": "a", "issues": [
            row(1, implementation_state="IMPLEMENTING", owned_files=["frontend/src/Board.tsx"]),
            row(2, owned_files=["frontend/src"])]}
        self.assertEqual([], orch.plan_graph(graph)["ready_frontier"])

    def test_blocked_environment_keeps_file_owner_until_explicit_release(self):
        graph = {"main_sha": "a", "issues": [
            row(1, implementation_state="BLOCKED_ENVIRONMENT", owned_files=["backend/src/service.py"]),
            row(2, owned_files=["backend/src/service.py"])]}
        self.assertEqual([], orch.plan_graph(graph)["ready_frontier"])
        graph["issues"][0]["ownership_state"] = "RELEASED"
        self.assertEqual([2], orch.plan_graph(graph)["ready_frontier"])

    def test_active_owner_without_file_list_fails_closed(self):
        active = row(1, implementation_state="IMPLEMENTING")
        del active["owned_files"]
        with self.assertRaisesRegex(orch.PolicyError, "ownership is unknown"):
            orch.plan_graph({"main_sha": "a", "issues": [active, row(2)]})

    def test_blocked_environment_owner_stays_held(self):
        plan = orch.plan_graph({"main_sha": "a", "issues": [
            row(1, implementation_state="BLOCKED_ENVIRONMENT", owned_files=["backend/src/a.py"]),
            row(2, owned_files=["backend/src/a.py"])]})
        self.assertEqual([], plan["ready_frontier"])

    def test_independent_candidates_still_share_ready_frontier(self):
        plan = orch.plan_graph({"main_sha": "a", "issues": [
            row(1, owned_files=["frontend/src"]), row(2, owned_files=["backend/src"])]})
        self.assertEqual([1, 2], plan["ready_frontier"])

    def test_started_owner_cannot_be_reassigned_while_marked_held(self):
        graph = {"main_sha": "a", "issues": [
            row(1, ownership_state="HELD", owned_files=["scripts/tool.py"]),
            row(2, owned_files=["scripts/tool.py"])]}
        self.assertEqual([], orch.plan_graph(graph)["ready_frontier"])

    def test_adopted_but_not_resumed_wip_remains_an_owner_and_not_a_candidate(self):
        graph = {"main_sha": "a", "issues": [
            row(1, implementation_state="BLOCKED_DEPENDENCY", owned_files=["scripts/tool.py"],
                wip_adoption={"status": "ADOPTED_PENDING_RESUME"}),
            row(2, owned_files=["scripts/tool.py"])]}
        plan = orch.plan_graph(graph)
        self.assertEqual([], plan["ready_frontier"])
        self.assertIn("already_assigned_or_started", plan["issues"]["1"]["reasons"])
        self.assertIn("file_owned_by_active_issue_1", " ".join(plan["issues"]["2"]["reasons"]))

    def test_owned_paths_reject_parent_escape(self):
        with self.assertRaisesRegex(orch.PolicyError, "escapes the repository"):
            orch.plan_graph({"main_sha": "a", "issues": [row(1, owned_files=["src/../secret.txt"])]})

    def test_not_started_is_not_release_frontier(self):
        plan = orch.plan_graph({"main_sha": "main-a", "issues": [row(1)]})
        self.assertEqual([1], plan["ready_frontier"])
        self.assertEqual([], plan["full_gate_eligible_frontier"])
        self.assertEqual([], plan["full_gate_verified_frontier"])
        self.assertEqual([], plan["gpt_handoff_frontier"])
        self.assertEqual([], plan["release_frontier"])

    def test_full_gate_evidence_must_match_head_and_prerequisites(self):
        issue = row(1, implementation_state="FULL_GATE_VERIFIED", head_sha="head-1",
                    verification={"status": "FULL_GATE_VERIFIED", "sha": "head-1",
                                  "evidence": self._verified_artifact()})
        self.assertEqual([1], orch.plan_graph({"main_sha": "main-a", "issues": [issue]})[
            "full_gate_verified_frontier"])
        issue["verification"]["sha"] = "older-head"
        self.assertEqual([], orch.plan_graph({"main_sha": "main-a", "issues": [issue]})[
            "full_gate_verified_frontier"])
        issue["verification"]["sha"] = "head-1"
        issue["base_sha"] = "old-main"
        self.assertEqual([], orch.plan_graph({"main_sha": "main-a", "issues": [issue]})[
            "full_gate_verified_frontier"])

    def test_targeted_gate_is_only_eligible_for_full_gate(self):
        issue = row(1, implementation_state="TARGETED_VERIFIED", head_sha="head-1",
                    verification={"status": "TARGETED_VERIFIED", "sha": "head-1"})
        plan = orch.plan_graph({"main_sha": "main-a", "issues": [issue]})
        self.assertEqual([1], plan["full_gate_eligible_frontier"])
        self.assertEqual([], plan["full_gate_verified_frontier"])

    def test_gpt_handoff_requires_full_gate_internal_review_and_pushed_sha(self):
        issue = row(1, implementation_state="WAITING_GPT_REVIEW", head_sha="head-1",
                    verification={"status": "FULL_GATE_VERIFIED", "sha": "head-1",
                                  "evidence": self._verified_artifact()},
                    internal_review="PASS", handoff={"local_sha": "head-1", "remote_sha": "head-1"})
        self.assertEqual([1], orch.plan_graph({"main_sha": "main-a", "issues": [issue]})[
            "gpt_handoff_frontier"])
        issue["handoff"]["remote_sha"] = "old-head"
        self.assertEqual([], orch.plan_graph({"main_sha": "main-a", "issues": [issue]})[
            "gpt_handoff_frontier"])

    def test_final_pass_frontier_requires_exact_reviewed_head(self):
        issue = row(1, implementation_state="GPT_FINAL_PASS", head_sha="head-1",
                    verification={"status": "FULL_GATE_VERIFIED", "sha": "head-1",
                                  "evidence": self._verified_artifact()},
                    internal_review="PASS", handoff={"local_sha": "head-1", "remote_sha": "head-1"},
                    gpt_review={"status": "FINAL_PASS", "reviewed_sha": "head-1"})
        self.assertEqual([1], orch.plan_graph({"main_sha": "main-a", "issues": [issue]})[
            "release_frontier"])
        issue["gpt_review"]["reviewed_sha"] = "old-head"
        self.assertEqual([], orch.plan_graph({"main_sha": "main-a", "issues": [issue]})[
            "release_frontier"])


class GateTests(unittest.TestCase):
    def test_internal_review_does_not_open_pr_gate(self):
        ok, _ = orch.can_open_pr({"head_sha": "abc", "internal_review": "PASS",
                                  "gpt_review": {"status": "NOT_REVIEWED"}}, "abc")
        self.assertFalse(ok)

    def test_gpt_review_must_match_exact_head(self):
        record = {"head_sha": "new", "gpt_review": {
            "status": "FINAL_PASS", "reviewed_sha": "old"}}
        self.assertFalse(orch.can_open_pr(record, "new")[0])
        record["gpt_review"]["reviewed_sha"] = "new"
        self.assertTrue(orch.can_open_pr(record, "new")[0])


class ResourceTests(unittest.TestCase):
    def test_another_owner_blocks_resource_without_reclaiming(self):
        with tempfile.TemporaryDirectory() as temp:
            state_dir = Path(temp)
            lock_dir = state_dir / "locks" / "high-load.lock"
            lock_dir.mkdir(parents=True)
            (lock_dir / "owner.json").write_text(json.dumps({
                "issue": 1, "pid": os.getpid(), "host": __import__("socket").gethostname(),
                "token": "active-owner"
            }), encoding="utf-8")
            with self.assertRaisesRegex(orch.PolicyError, "resource is busy"):
                orch.run_with_resource("vitest", 2, [sys.executable, "-c", "pass"], state_dir)
            self.assertTrue(lock_dir.exists())
            self.assertEqual(1, orch.resource_status(state_dir)["vitest"]["owner"]["issue"])

    def test_child_return_releases_resource_lock(self):
        with tempfile.TemporaryDirectory() as temp:
            command = [sys.executable, "-c", "raise SystemExit(7)"]
            with patch.object(orch.subprocess, "run") as run:
                run.return_value.returncode = 7
                self.assertEqual(7, orch.run_with_resource("maven", 5, command, Path(temp)))
                run.assert_called_once_with(command, check=False)
            status = orch.resource_status(Path(temp))["maven"]
            self.assertFalse(status["busy"])
            self.assertFalse(status["stale_record"])

    def test_crashed_owner_is_reported_as_stale_without_reclaim(self):
        stale_owner = {"issue": 99, "host": "same-host", "pid": 999999}
        self.assertTrue(orch._is_stale_lock(True, stale_owner, False))
        self.assertTrue(orch._is_stale_lock(True, None, None))
        self.assertFalse(orch._is_stale_lock(True, stale_owner, True))
        self.assertFalse(orch._is_stale_lock(False, stale_owner, False))

    def test_owner_record_failure_releases_new_lock_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(Path, "write_text", side_effect=OSError("read only")):
                with self.assertRaisesRegex(OSError, "read only"):
                    orch.run_with_resource("docker", 5, ["docker", "build"], Path(temp))
            self.assertFalse((Path(temp) / "locks" / "high-load.lock").exists())

    def test_vitest_lock_blocks_docker(self):
        self._assert_cross_type_block("vitest", "docker")

    def test_maven_lock_blocks_vitest(self):
        self._assert_cross_type_block("maven", "vitest")

    def test_docker_lock_blocks_maven(self):
        self._assert_cross_type_block("docker", "maven")

    def _assert_cross_type_block(self, holder_kind, blocked_kind):
        with tempfile.TemporaryDirectory() as temp:
            state_dir = Path(temp)
            lock_dir = state_dir / "locks" / "high-load.lock"
            lock_dir.mkdir(parents=True)
            (lock_dir / "owner.json").write_text(json.dumps({
                "issue": 11, "pid": os.getpid(), "host": __import__("socket").gethostname(),
                "resource": holder_kind, "resource_group": "high-load", "token": "held"
            }), encoding="utf-8")
            with self.assertRaisesRegex(orch.PolicyError, "resource is busy"):
                orch.run_with_resource(blocked_kind, 12, [sys.executable, "-c", "pass"], state_dir)
            self.assertEqual(holder_kind, orch.resource_status(state_dir)[blocked_kind]["owner"]["resource"])

    def test_resource_lock_releases_for_every_type_after_command_returns(self):
        with tempfile.TemporaryDirectory() as temp:
            with patch.object(orch.subprocess, "run") as run:
                run.return_value.returncode = 0
                self.assertEqual(0, orch.run_with_resource("vitest", 1, ["cmd"], Path(temp)))
                self.assertEqual(0, orch.run_with_resource("docker", 2, ["cmd"], Path(temp)))
            self.assertFalse(any(status["busy"] for status in orch.resource_status(Path(temp)).values()))

    def test_abnormal_command_exit_keeps_diagnostic_lock_for_manual_review(self):
        with tempfile.TemporaryDirectory() as temp:
            state_dir = Path(temp)
            with patch.object(orch.subprocess, "run", side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    orch.run_with_resource("docker", 18, ["docker", "build"], state_dir)
            status = orch.resource_status(state_dir)["vitest"]
            self.assertTrue(status["busy"])
            self.assertEqual(18, status["owner"]["issue"])
            self.assertEqual("INTERRUPTED_OR_FAILED", status["owner"]["status"])

    def test_resource_status_is_read_only_and_does_not_create_lock_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            state_dir = Path(temp)
            self.assertFalse((state_dir / "locks").exists())
            status = orch.resource_status(state_dir)
            self.assertFalse(status["vitest"]["busy"])
            self.assertFalse((state_dir / "locks").exists())

    def test_linked_worktrees_resolve_to_the_same_default_lock_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "repo"
            root.mkdir()
            other = Path(temp) / "worktree"
            subprocess.run(["git", "-C", str(root), "init", "-b", "main"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
            subprocess.run(["git", "-C", str(root), "config", "user.name", "Test User"], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.email", "test@example.com"], check=True)
            (root / "base.txt").write_text("base\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "add", "base.txt"], check=True)
            subprocess.run(["git", "-C", str(root), "commit", "-m", "base"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
            subprocess.run(["git", "-C", str(root), "worktree", "add", "-b", "codex/issue-42-test",
                            str(other)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
            with contextlib.chdir(root):
                first = orch.shared_state_dir()
            with contextlib.chdir(other):
                second = orch.shared_state_dir()
            self.assertEqual(first, second)


class StateTests(unittest.TestCase):
    def _make_state(self, root):
        worktree = Path(root).resolve()
        self._git(worktree, "init", "-b", "codex/issue-9-example")
        self._git(worktree, "config", "user.name", "Test User")
        self._git(worktree, "config", "user.email", "test@example.com")
        (worktree / "src").mkdir()
        (worktree / "src" / "a.py").write_text("VALUE = 1\n", encoding="utf-8")
        (worktree / "scripts" / "tests").mkdir(parents=True)
        (worktree / "scripts" / "tests" / "__init__.py").write_text("", encoding="utf-8")
        (worktree / "frontend").mkdir()
        (worktree / "frontend" / "package.json").write_text('{"scripts":{"test":"vitest run"}}', encoding="utf-8")
        (worktree / "backend").mkdir()
        (worktree / "backend" / "pom.xml").write_text("<project />", encoding="utf-8")
        self._git(worktree, "add", "src/a.py", "scripts/tests/__init__.py", "frontend/package.json", "backend/pom.xml")
        self._git(worktree, "commit", "-m", "base")
        self._state_head = self._git(worktree, "rev-parse", "HEAD")
        self._state_remote = worktree.parent / f"{worktree.name}-origin.git"
        self._git(worktree.parent, "init", "--bare", str(self._state_remote))
        self._git(worktree, "remote", "add", "origin", str(self._state_remote))
        self._git(worktree, "push", "-u", "origin", "codex/issue-9-example")
        state_path = worktree.parent / f"{worktree.name}-orchestrator.json"
        main_sha = self._state_head
        self._state_base_sha = main_sha
        self._state_worktree = str(worktree)
        self._state_branch = "codex/issue-9-example"
        with patch.object(orch, "_verified_remote_main_sha", return_value=main_sha):
            orch.state_init(state_path, main_sha)
        orch.state_register(state_path, {
            "number": 9, "dependencies": [], "base_sha": main_sha,
            "branch": "codex/issue-9-example", "worktree": self._state_worktree,
            "contract_version": "v1", "owned_files": ["src/a.py"]
        })
        return state_path

    def _ready_evidence(self):
        return {"code_gate": "PASS", "authorized_issue": 9,
                "branch_verified": True, "worktree_verified": True,
                "branch": "codex/issue-9-example", "worktree": self._state_worktree,
                "worktree_clean": True, "worktree_head": self._state_base_sha}

    def _artifact(self, root, name, content):
        return {"command": ["python", "-m", "unittest", "scripts.tests.test_supplypilot_orchestrator"],
                "output_hint": content}

    def test_head_change_invalidates_old_verification_and_review(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            (Path(temp) / "src" / "a.py").write_text("VALUE = 2\n", encoding="utf-8")
            self._git(Path(temp), "add", "src/a.py")
            self._git(Path(temp), "commit", "-m", "update")
            new_sha = self._git(Path(temp), "rev-parse", "HEAD")
            self._git(Path(temp), "push", "origin", "codex/issue-9-example")
            orch.state_set_head(path, 9, new_sha, new_sha, new_sha)
            record = orch.read_json(path)["issues"]["9"]
            self.assertEqual("IMPLEMENTING", record["implementation_state"])
            self.assertEqual(new_sha, record["head_sha"])
            self.assertEqual("NOT_RUN", record["verification"]["status"])
            self.assertEqual("NOT_REVIEWED", record["gpt_review"]["status"])

    def test_head_update_requires_authorized_implementation_state(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            with self.assertRaisesRegex(orch.PolicyError, "only while.*IMPLEMENTING"):
                orch.state_set_head(path, 9, "new-sha", "new-sha", "new-sha")

    def test_head_update_rejects_caller_claim_when_remote_ref_differs(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            (Path(temp) / "src" / "a.py").write_text("VALUE = 2\n", encoding="utf-8")
            self._git(Path(temp), "add", "src/a.py")
            self._git(Path(temp), "commit", "-m", "local only")
            local_sha = self._git(Path(temp), "rev-parse", "HEAD")
            with self.assertRaisesRegex(orch.PolicyError, "actual local HEAD and origin branch SHA"):
                orch.state_set_head(path, 9, local_sha, local_sha, local_sha)

    def test_gpt_final_pass_and_pr_require_exact_sha_and_closes_line(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, self._state_head, self._state_head, self._state_head)
            targeted_evidence = {"status": "PASS", "sha": "sha-9", **self._artifact(
                temp, "targeted.log", "targeted tests passed")}
            targeted_evidence["sha"] = self._state_head
            with patch.object(orch, "_execute_gate_command", return_value=Mock(returncode=0, stdout="Ran 55 tests in 1.0s\nOK")):
                orch.state_transition(path, 9, "TARGETED_VERIFIED", targeted_evidence)
            with patch.object(orch, "_verify_feature_base", return_value={
                "verified_worktree_branch": "codex/issue-9-example",
                "verified_worktree_head": self._state_head, "verified_base_main_sha": self._state_base_sha
            }):
                orch.state_transition(path, 9, "FULL_GATE_PENDING", {
                    "dependencies_merged": True, "current_main_sha": "caller-fake",
                    "contract_verified": True, "contract_version": "v1",
                    "verified_contract_version": "v1"
                })
            full_evidence = {"status": "PASS", "sha": self._state_head, **self._artifact(
                temp, "full-gate.log", "full gate passed")}
            full_evidence["command"] = ["supplypilot-full-gate"]
            with patch.object(orch, "_verified_remote_main_sha", return_value=self._state_base_sha), \
                    patch.object(orch, "_execute_full_gate", return_value=Mock(returncode=0, stdout="full gate passed")):
                orch.state_transition(path, 9, "FULL_GATE_VERIFIED", full_evidence)
            with patch.object(orch, "_verify_local_remote_feature_head", return_value={
                "branch": self._state_branch, "local_sha": self._state_head, "remote_sha": "b" * 40
            }):
                with self.assertRaisesRegex(orch.PolicyError, "local, remote, and current HEAD"):
                    orch.state_transition(path, 9, "WAITING_GPT_REVIEW", {
                        "local_sha": self._state_head, "remote_sha": self._state_head, "internal_review": "PASS"
                    })
            orch.state_transition(path, 9, "WAITING_GPT_REVIEW", {
                "local_sha": self._state_head, "remote_sha": self._state_head, "internal_review": "PASS"
            })
            with self.assertRaisesRegex(orch.PolicyError, "exact current HEAD"):
                orch.state_transition(path, 9, "GPT_FINAL_PASS", {
                    "review_status": "FINAL_PASS", "reviewed_sha": "old-sha"
                })
            orch.state_transition(path, 9, "GPT_FINAL_PASS", {
                "review_status": "FINAL_PASS", "reviewed_sha": self._state_head,
                "url": "https://github.com/liujiawei-tw/supplypilot/pull/999"
            })
            with self.assertRaisesRegex(orch.PolicyError, "standalone Closes"):
                orch.state_transition(path, 9, "PR_OPEN", {
                    "base": "main", "head_sha": self._state_head, "url": "https://github.com/example/pr/999",
                    "description": "Closes #9 thanks"
                })
            orch.state_transition(path, 9, "PR_OPEN", {
                "base": "main", "head_sha": self._state_head, "url": "https://github.com/example/pr/999",
                "number": 999, "description": "Summary\n\nCloses #9"
            })
            failed_merge_calls = [
                Mock(stdout="https://github.com/liujiawei-tw/supplypilot.git"),
                Mock(stdout=json.dumps({"state": "CLOSED", "mergedAt": "2026-10-22T00:00:00Z",
                                        "mergeCommit": {"oid": "a" * 40}, "baseRefName": "main",
                                        "headRefName": "codex/issue-9-example", "headRefOid": self._state_head,
                                        "body": "Closes #9"})),
                Mock(stdout=json.dumps({"state": "OPEN"})),
            ]
            with patch.object(orch.subprocess, "run", side_effect=failed_merge_calls):
                with self.assertRaisesRegex(orch.PolicyError, "Issue is not closed"):
                    orch.state_transition(path, 9, "MERGED", {
                        "verified_github": True, "merge_commit": "fake", "main_sha": "f" * 40
                    })
            self.assertEqual("PR_OPEN", orch.read_json(path)["issues"]["9"]["implementation_state"])
            successful_merge_calls = [
                Mock(stdout="https://github.com/liujiawei-tw/supplypilot.git"),
                Mock(stdout=json.dumps({"state": "CLOSED", "mergedAt": "2026-10-22T00:00:00Z",
                                        "mergeCommit": {"oid": "a" * 40}, "baseRefName": "main",
                                        "headRefName": "codex/issue-9-example", "headRefOid": self._state_head,
                                        "body": "Closes #9"})),
                Mock(stdout=json.dumps({"state": "CLOSED"})),
                Mock(stdout=f"{'b' * 40}\trefs/heads/main\n"),
            ]
            with patch.object(orch.subprocess, "run", side_effect=successful_merge_calls):
                orch.state_transition(path, 9, "MERGED", {
                    "verified_github": True, "merge_commit": "caller-fake", "main_sha": "c" * 40
                })
            self.assertEqual("MERGED", orch.read_json(path)["issues"]["9"]["implementation_state"])
            self.assertEqual("b" * 40, orch.read_json(path)["main_sha"])

    def test_state_init_rejects_unverified_or_arbitrary_main_sha(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "state.json"
            with patch.object(orch, "_verified_remote_main_sha", return_value="b" * 40):
                with self.assertRaisesRegex(orch.PolicyError, "full commit SHA"):
                    orch.state_init(path, "arbitrary")
                with self.assertRaisesRegex(orch.PolicyError, "does not match"):
                    orch.state_init(path, "a" * 40)
            self.assertFalse(path.exists())

    def test_full_gate_pending_requires_feature_worktree_to_contain_verified_main(self):
        with tempfile.TemporaryDirectory() as temp:
            worktree = self._existing_worktree(temp)
            head = self._git(worktree, "rev-parse", "HEAD")
            record = {"worktree": str(worktree), "branch": "codex/issue-42-existing", "head_sha": head}
            with patch.object(orch, "_verified_remote_main_sha", return_value=head):
                proof = orch._verify_feature_base(record, head)
            self.assertEqual(head, proof["verified_base_main_sha"])
            with patch.object(orch, "_verified_remote_main_sha", return_value="b" * 40):
                with self.assertRaisesRegex(orch.PolicyError, "does not contain"):
                    orch._verify_feature_base(record, "b" * 40)

    def test_github_merge_verification_requires_issue_specific_pr_and_current_head(self):
        pr = {"state": "CLOSED", "mergedAt": "2026-10-22T00:00:00Z",
              "mergeCommit": {"oid": "a" * 40}, "baseRefName": "main",
              "headRefName": "codex/issue-9-example", "headRefOid": "old-head", "body": "Closes #9"}
        calls = [Mock(stdout="https://github.com/liujiawei-tw/supplypilot.git"),
                 Mock(stdout=json.dumps(pr))]
        with patch.object(orch.subprocess, "run", side_effect=calls):
            with self.assertRaisesRegex(orch.PolicyError, "Issue and exact HEAD"):
                orch._verify_github_merge({"number": 9, "branch": "codex/issue-9-example",
                                           "head_sha": "current-head", "pr": {"number": 99}})

    def test_duplicate_issue_assignment_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            with self.assertRaisesRegex(orch.PolicyError, "already has a state record"):
                orch.state_register(path, {
                    "number": 9, "dependencies": [], "base_sha": "a" * 40,
                    "branch": "codex/issue-9-other", "worktree": "C:/other",
                    "contract_version": "v2", "owned_files": []
                })

    def test_worktree_must_be_unique_and_issue_specific(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            with self.assertRaisesRegex(orch.PolicyError, "dedicated"):
                orch.state_register(path, {
                    "number": 10, "dependencies": [], "base_sha": "a" * 40,
                    "branch": "main", "worktree": "C:/worktrees/issue-10",
                    "contract_version": "v1", "owned_files": []
                })
            with self.assertRaisesRegex(orch.PolicyError, "already assigned"):
                existing_worktree = orch.read_json(path)["issues"]["9"]["worktree"]
                orch.state_register(path, {
                    "number": 10, "dependencies": [], "base_sha": self._state_base_sha,
                    "branch": "codex/issue-10-other", "worktree": existing_worktree,
                    "contract_version": "v1", "owned_files": []
                })

    def test_final_pass_cannot_be_marked_merged_with_caller_supplied_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            with self.assertRaisesRegex(orch.PolicyError, "Issue and PR number"):
                orch.state_transition(path, 9, "MERGED", {
                    "pr_merged": True, "issue_closed": True,
                    "merge_commit": "merge", "main_sha": "main-b"
                })

    def test_main_sync_stales_descendant_evidence_without_deleting_it(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_register(path, {
                "number": 10, "dependencies": [9], "base_sha": self._state_base_sha,
                "branch": "codex/issue-10-downstream", "worktree": "C:/worktrees/issue-10",
                "contract_version": "contract-v1", "contract_verified": True, "owned_files": []
            })
            state = orch.read_json(path)
            state["issues"]["9"].update(
                implementation_state="MERGED", pr={"number": 999},
                merge={"status": "MERGED", "merge_commit": "a" * 40})
            state["issues"]["10"]["verification"] = {
                "status": "FULL_GATE_VERIFIED", "sha": "old-head", "evidence": ["old-gate.log"]}
            state["issues"]["10"]["gpt_review"] = {
                "status": "FINAL_PASS", "reviewed_sha": "old-head", "evidence": "old-review"}
            orch.atomic_write_json(path, state)
            with patch.object(orch, "_verify_github_merge", return_value={
                "verified_github": True, "pr": 999, "issue": 9,
                "merge_commit": "a" * 40, "main_sha": "b" * 40
            }):
                orch.state_sync_main(path, 9)
            result = orch.read_json(path)
            downstream = result["issues"]["10"]
            self.assertEqual("b" * 40, result["main_sha"])
            self.assertEqual(self._state_base_sha, downstream["base_sha"])
            self.assertEqual("STALE", downstream["verification"]["status"])
            self.assertEqual(["old-gate.log"], downstream["verification"]["evidence"])
            self.assertEqual("old-head", downstream["verification"]["sha"])
            self.assertEqual("STALE", downstream["gpt_review"]["status"])
            self.assertEqual("old-head", downstream["gpt_review"]["reviewed_sha"])
            self.assertFalse(downstream["contract_verified"])
            self.assertEqual([10], result["events"][-1]["affected_issues"])

    def test_two_verified_merges_advance_main_and_allow_new_baseline_registration(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            state = orch.read_json(path)
            state["issues"]["9"].update(
                implementation_state="MERGED", pr={"number": 999},
                merge={"status": "MERGED", "merge_commit": "a" * 40})
            orch.atomic_write_json(path, state)
            with patch.object(orch, "_verify_github_merge", return_value={
                "verified_github": True, "pr": 999, "issue": 9,
                "merge_commit": "a" * 40, "main_sha": "b" * 40
            }):
                orch.state_sync_main(path, 9)
            self.assertEqual("b" * 40, orch.read_json(path)["main_sha"])
            orch.state_register(path, {
                "number": 10, "dependencies": [9], "base_sha": "b" * 40,
                "branch": "codex/issue-10-next", "worktree": "C:/worktrees/issue-10",
                "contract_version": "v2", "owned_files": []
            })
            state = orch.read_json(path)
            state["issues"]["10"].update(
                implementation_state="MERGED", pr={"number": 1000},
                merge={"status": "MERGED", "merge_commit": "c" * 40})
            orch.atomic_write_json(path, state)
            with patch.object(orch, "_verify_github_merge", return_value={
                "verified_github": True, "pr": 1000, "issue": 10,
                "merge_commit": "c" * 40, "main_sha": "d" * 40
            }):
                orch.state_sync_main(path, 10)
            self.assertEqual("d" * 40, orch.read_json(path)["main_sha"])
            plan = orch.plan_graph({"main_sha": "d" * 40, "issues": [
                row(9, merged=True, closed=True), row(10, [9], merged=True, closed=True),
                row(11, [10], base_sha="d" * 40)]})
            self.assertEqual([11], plan["ready_frontier"])

    def test_state_writer_fails_closed_when_another_writer_holds_lock(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            lock_dir = Path(str(path) + ".lock")
            lock_dir.mkdir()
            (lock_dir / "owner.json").write_text(json.dumps({"pid": 123}), encoding="utf-8")
            before = path.read_bytes()
            with self.assertRaisesRegex(orch.PolicyError, "State is locked"):
                orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            self.assertEqual(before, path.read_bytes())

    def test_arbitrary_gate_json_without_real_hash_matched_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, self._state_head, self._state_head, self._state_head)
            with self.assertRaisesRegex(orch.PolicyError, "approved command"):
                orch.state_transition(path, 9, "TARGETED_VERIFIED", {
                    "status": "PASS", "sha": self._state_head, "command": ["fake-test"],
                    "exit_code": 0, "artifacts": ["fake.log"]
                })
            with self.assertRaisesRegex(orch.PolicyError, "approved command"):
                orch.state_transition(path, 9, "TARGETED_VERIFIED", {
                    "status": "PASS", "sha": self._state_head, "command": ["python", "-c", "print('PASS')"],
                    "exit_code": 0, "artifacts": [{"path": "real.log", "sha256": "0" * 64}]
                })
            self.assertEqual("NOT_RUN", orch.read_json(path)["issues"]["9"]["verification"]["status"])
            valid = {"sha": self._state_head,
                     "command": ["python", "-m", "unittest", "scripts.tests.test_supplypilot_orchestrator"],
                     "status": "PASS", "exit_code": 0, "artifacts": [{"path": "fake", "sha256": "0" * 64}]}
            with patch.object(orch, "_execute_gate_command", return_value=Mock(returncode=1, stdout="test failed")):
                with self.assertRaisesRegex(orch.PolicyError, "failed with exit code 1"):
                    orch.state_transition(path, 9, "TARGETED_VERIFIED", valid)
            self.assertEqual("NOT_RUN", orch.read_json(path)["issues"]["9"]["verification"]["status"])
            with patch.object(orch, "_execute_gate_command", return_value=Mock(returncode=0, stdout="Ran 3 tests in 0.01s\nOK")):
                orch.state_transition(path, 9, "TARGETED_VERIFIED", valid)
            evidence = orch.read_json(path)["issues"]["9"]["verification"]["evidence"]
            self.assertEqual(1, len(evidence))
            self.assertTrue(Path(evidence[0]["path"]).is_file())
            self.assertTrue(orch._verification_artifacts_valid(evidence))

    def test_targeted_gate_rejects_dirty_worktree_before_running_tests(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, self._state_head, self._state_head, self._state_head)
            (Path(temp) / "src" / "uncommitted-test-config.py").write_text("FAKE = True\n", encoding="utf-8")
            evidence = {"sha": self._state_head,
                        "command": ["python", "-m", "unittest", "scripts.tests.test_example"]}
            with patch.object(orch, "_execute_gate_command") as run:
                with self.assertRaisesRegex(orch.PolicyError, "clean worktree"):
                    orch.state_transition(path, 9, "TARGETED_VERIFIED", evidence)
            run.assert_not_called()
            self.assertEqual("NOT_RUN", orch.read_json(path)["issues"]["9"]["verification"]["status"])

    def test_gate_executes_outside_state_lock_and_preserves_concurrent_issue_update(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, self._state_head, self._state_head, self._state_head)
            state = orch.read_json(path)
            state["issues"]["10"] = {"number": 10, "implementation_state": "READY_TO_CODE",
                                      "events": []}
            orch.atomic_write_json(path, state)
            started, release = threading.Event(), threading.Event()
            outcomes = []

            def slow_gate(*_args, **_kwargs):
                started.set()
                if not release.wait(5):
                    raise AssertionError("test did not release the isolated Gate runner")
                return Mock(returncode=0, stdout="Ran 1 test in 0.01s\nOK")

            evidence = {"sha": self._state_head,
                        "command": ["python", "-m", "unittest", "scripts.tests.test_example"]}
            with patch.object(orch, "_execute_gate_command", side_effect=slow_gate):
                worker = threading.Thread(target=lambda: self._capture_exception(
                    outcomes, orch.state_transition, path, 9, "TARGETED_VERIFIED", evidence))
                worker.start()
                self.assertTrue(started.wait(5), "Gate runner never started")
                with self.assertRaisesRegex(orch.PolicyError, "active Gate reservation"):
                    orch.state_transition(path, 9, "TARGETED_VERIFIED", evidence)
                orch.state_transition(path, 10, "IMPLEMENTING", {})
                self.assertEqual("IMPLEMENTING", orch.read_json(path)["issues"]["10"]["implementation_state"])
                release.set()
                worker.join(5)
                self.assertFalse(worker.is_alive())
            self.assertEqual([], outcomes)
            state = orch.read_json(path)
            self.assertEqual("IMPLEMENTING", state["issues"]["10"]["implementation_state"])
            self.assertEqual("TARGETED_VERIFIED", state["issues"]["9"]["verification"]["status"])
            self.assertEqual("COMMITTED", state["issues"]["9"]["gate_reservation"]["status"])

    @staticmethod
    def _capture_exception(output, function, *args):
        try:
            function(*args)
        except BaseException as exc:
            output.append(exc)

    def test_interrupted_gate_requires_explicit_manual_recovery_without_pass_or_lock_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, self._state_head, self._state_head, self._state_head)
            evidence = {"sha": self._state_head,
                        "command": ["python", "-m", "unittest", "scripts.tests.test_example"]}
            with patch.object(orch, "_execute_gate_command", side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    orch.state_transition(path, 9, "TARGETED_VERIFIED", evidence)
            state = orch.read_json(path)
            reservation = state["issues"]["9"]["gate_reservation"]
            self.assertEqual("RUNNING", reservation["status"])
            self.assertEqual("NOT_RUN", state["issues"]["9"]["verification"]["status"])
            lock = Path(temp) / "locks" / "high-load.lock"
            lock.mkdir(parents=True)
            (lock / "owner.json").write_text('{"resource":"vitest"}', encoding="utf-8")
            with self.assertRaisesRegex(orch.PolicyError, "owner-stopped"):
                orch.state_gate_recover(path, 9, reservation["token"], False, "stopped")
            with self.assertRaisesRegex(orch.PolicyError, "token"):
                orch.state_gate_recover(path, 9, "wrong-token", True, "confirmed stopped")
            orch.state_gate_recover(path, 9, reservation["token"], True, "owner process confirmed stopped")
            state = orch.read_json(path)
            self.assertEqual("ABORTED", state["issues"]["9"]["gate_reservation"]["status"])
            self.assertEqual("NOT_RUN", state["issues"]["9"]["verification"]["status"])
            self.assertTrue((lock / "owner.json").is_file())

    def test_gate_context_change_during_execution_stales_result_without_lost_update(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, self._state_head, self._state_head, self._state_head)
            state = orch.read_json(path)
            state["issues"]["9"]["dependencies"] = [10]
            state["issues"]["10"] = {"number": 10, "implementation_state": "IMPLEMENTING",
                                      "branch": "codex/issue-10-upstream", "head_sha": "a" * 40,
                                      "merge": {"status": "PENDING"}}
            orch.atomic_write_json(path, state)
            started, release = threading.Event(), threading.Event()
            outcomes = []

            def slow_gate(*_args, **_kwargs):
                started.set()
                release.wait(5)
                return Mock(returncode=0, stdout="Ran 1 test in 0.01s\nOK")

            evidence = {"sha": self._state_head,
                        "command": ["python", "-m", "unittest", "scripts.tests.test_example"]}
            with patch.object(orch, "_execute_gate_command", side_effect=slow_gate):
                worker = threading.Thread(target=lambda: self._capture_exception(
                    outcomes, orch.state_transition, path, 9, "TARGETED_VERIFIED", evidence))
                worker.start()
                self.assertTrue(started.wait(5))
                with orch.state_lock(path):
                    changed = orch.read_json(path)
                    changed["issues"]["10"]["head_sha"] = "b" * 40
                    orch.atomic_write_json(path, changed)
                release.set()
                worker.join(5)
                self.assertFalse(worker.is_alive())
            self.assertEqual(1, len(outcomes))
            self.assertIsInstance(outcomes[0], orch.PolicyError)
            self.assertIn("stale", str(outcomes[0]).lower())
            state = orch.read_json(path)
            self.assertEqual("IMPLEMENTING", state["issues"]["9"]["implementation_state"])
            self.assertEqual("NOT_RUN", state["issues"]["9"]["verification"]["status"])
            self.assertEqual("STALE", state["issues"]["9"]["gate_reservation"]["status"])
            self.assertEqual("b" * 40, state["issues"]["10"]["head_sha"])

    def test_worktree_change_during_gate_keeps_transcript_and_never_records_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, self._state_head, self._state_head, self._state_head)
            evidence = {"sha": self._state_head,
                        "command": ["python", "-m", "unittest", "scripts.tests.test_example"]}
            evidence_dir = path.parent / "evidence"
            existing_logs = set(evidence_dir.glob("issue-9-targeted_verified-*.log"))

            def mutate_then_pass(*_args, **_kwargs):
                (Path(temp) / "src" / "a.py").write_text("VALUE = 999\n", encoding="utf-8")
                return Mock(returncode=0, stdout="Ran 1 test in 0.01s\nOK")

            with patch.object(orch, "_execute_gate_command", side_effect=mutate_then_pass):
                with self.assertRaisesRegex(orch.PolicyError, "worktree changed during execution"):
                    orch.state_transition(path, 9, "TARGETED_VERIFIED", evidence)
            state = orch.read_json(path)
            self.assertEqual("NOT_RUN", state["issues"]["9"]["verification"]["status"])
            self.assertEqual("STALE", state["issues"]["9"]["gate_reservation"]["status"])
            logs = set(evidence_dir.glob("issue-9-targeted_verified-*.log")) - existing_logs
            self.assertEqual(1, len(logs))
            self.assertIn('"exit_code": 0', next(iter(logs)).read_text(encoding="utf-8"))

    def test_gate_command_allowlist_rejects_partial_full_gate_and_arbitrary_compose_run(self):
        self.assertTrue(orch._gate_command_supported(["python", "-m", "unittest", "suite"]))
        self.assertFalse(orch._gate_command_supported(["python", "-m", "unittest", "-h"]))
        self.assertFalse(orch._gate_command_supported(["python", "-m", "pytest", "--collect-only"]))
        self.assertFalse(orch._gate_command_supported(["python", "-m", "pytest", "tests", "--collect-only"]))
        self.assertFalse(orch._gate_command_supported(["npm", "run", "lint"]))
        self.assertFalse(orch._gate_command_supported(["npm", "run", "build"]))
        self.assertFalse(orch._gate_command_supported(["npm", "test"]))
        self.assertFalse(orch._gate_command_supported(["npm", "test", "--", "--passWithNoTests"]))
        self.assertFalse(orch._gate_command_supported(["npm", "test", "--", "../outside.test.tsx"]))
        self.assertFalse(orch._gate_command_supported(["npm", "test", "--", "tests/a.test.tsx", "--passWithNoTests"]))
        self.assertFalse(orch._gate_command_supported(["docker", "compose", "config", "--quiet"]))
        self.assertFalse(orch._gate_command_supported(["docker", "build", "--file", "Dockerfile", "."]))
        self.assertFalse(orch._gate_command_supported(
            ["docker", "compose", "run", "backend", "sh", "-c", "echo escaped"]))
        self.assertFalse(orch._gate_command_supported(["mvnw", "verify", "-DskipTests=true"]))
        self.assertFalse(orch._gate_command_supported(["mvnw", "verify", "-Dmaven.test.skip=true"]))
        self.assertFalse(orch._gate_command_supported(["mvnw", "verify", "--define", "skipTests=true"]))
        self.assertFalse(orch._gate_command_supported(["mvnw", "verify", "--define=skipTests=true"]))
        self.assertFalse(orch._gate_command_supported(["mvnw", "verify", "-D", "skipTests=true"]))
        self.assertFalse(orch._gate_command_supported(
            ["mvnw", "verify", "-D", "maven.test.failure.ignore=true"]))
        self.assertFalse(orch._gate_command_supported(["mvnw", "verify", "--fail-never"]))
        self.assertFalse(orch._gate_command_supported(["mvnw", "verify", "-fn"]))
        self.assertFalse(orch._gate_command_supported(
            ["mvnw", "verify", "-Dmaven.test.failure.ignore=true"]))
        self.assertFalse(orch._gate_command_supported(
            ["mvnw", "verify", "-Dsurefire.test.failure.ignore=true"]))
        self.assertTrue(orch._gate_command_supported(["supplypilot-full-gate"], "FULL_GATE_VERIFIED"))
        for partial in (["npm", "test"], ["npm", "run", "lint"],
                        ["docker", "compose", "config", "--quiet"], ["python", "-m", "unittest"]):
            self.assertFalse(orch._gate_command_supported(partial, "FULL_GATE_VERIFIED"))

    def test_targeted_gate_contract_selects_frontend_backend_and_python_roots(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "repo"
            frontend = root / "frontend"
            backend = root / "backend"
            frontend_tests = frontend / "tests"
            (frontend_tests).mkdir(parents=True)
            backend.mkdir(parents=True)
            (root / "scripts" / "tests").mkdir(parents=True)
            (frontend / "package.json").write_text('{"scripts":{"test":"vitest run"}}', encoding="utf-8")
            (backend / "pom.xml").write_text("<project />", encoding="utf-8")
            (frontend_tests / "smoke.test.tsx").write_text("", encoding="utf-8")
            wrapper = backend / ("mvnw.cmd" if os.name == "nt" else "mvnw")
            wrapper.write_text("@echo off\n" if os.name == "nt" else "#!/bin/sh\n", encoding="utf-8")
            install = Path(temp) / "node-install"
            install.mkdir()
            npm_name = "npm.cmd" if os.name == "nt" else "npm"
            trusted_npm = install / npm_name
            trusted_npm.write_text("@echo off\n" if os.name == "nt" else "#!/bin/sh\n", encoding="utf-8")
            if os.name != "nt":
                trusted_npm.chmod(0o755)
                wrapper.chmod(0o755)
            npm_command = ["npm", "test", "--", "tests/smoke.test.tsx"]
            maven_command = ["mvnw", "test", "-Dtest=ExampleTest"]
            python_command = ["python", "-m", "unittest", "scripts.tests.test_example"]
            with patch.dict(os.environ, {"PATH": str(install), "PATHEXT": ".COM;.EXE;.BAT;.CMD"}):
                self.assertTrue(orch._gate_command_supported(npm_command, worktree=root))
                self.assertEqual(frontend.resolve(), orch._gate_working_directory(npm_command, root))
            self.assertTrue(orch._gate_command_supported(maven_command, worktree=root))
            self.assertEqual(backend.resolve(), orch._gate_working_directory(maven_command, root))
            self.assertTrue(orch._gate_command_supported(python_command, worktree=root))
            self.assertEqual(root.resolve(), orch._gate_working_directory(python_command, root))
            self.assertFalse(orch._gate_command_supported(npm_command, worktree=frontend))
            self.assertFalse(orch._gate_command_supported(
                ["npm", "test", "--", "tests/../../outside.test.tsx"], worktree=root))
            self.assertFalse(orch._gate_command_supported(
                ["mvnw", "test", "-Dtest=ExampleTest", "-DskipTests"], worktree=root))
            self.assertFalse(orch._gate_command_supported(
                ["mvnw", "test", "-Dtest=ExampleTest;echo"], worktree=root))
            with patch.object(orch, "_run_captured", return_value=Mock(returncode=0, stdout="Ran 1 test")) as run:
                orch._execute_gate_command(python_command, root)
                self.assertEqual(root.resolve(), run.call_args.args[1])
            with patch.dict(os.environ, {"PATH": str(install), "PATHEXT": ".COM;.EXE;.BAT;.CMD"}), \
                    patch.object(orch, "_run_captured", return_value=Mock(returncode=0, stdout="1 passed")) as run:
                orch._execute_gate_command(npm_command, root, issue=9, state_dir=Path(temp) / "state")
                self.assertEqual(frontend.resolve(), run.call_args.args[1])
            with patch.object(orch, "_run_captured", return_value=Mock(returncode=0, stdout="Tests run: 1")) as run:
                orch._execute_gate_command(maven_command, root, issue=9, state_dir=Path(temp) / "state")
                self.assertEqual(backend.resolve(), run.call_args.args[1])

    def test_targeted_gate_requires_a_nonzero_executed_test_count(self):
        self.assertTrue(orch._verification_output_contains_test_run(
            ["npm", "test", "--", "tests/one.test.ts"], "Tests 1 passed (1)"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["npm", "test", "--", "tests/one.test.ts"], "No test files found"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["npm", "test", "--", "tests/one.test.ts"], "Tests 0 passed (0)"))
        self.assertTrue(orch._verification_output_contains_test_run(
            ["python", "-m", "unittest", "scripts.tests.test_example"], "Ran 1 test in 0.01s\nOK"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["python", "-m", "unittest", "scripts.tests.test_example"], "Ran 0 tests in 0.00s\nOK"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["mvnw", "test", "-Dtest=ExampleTest"],
            "Tests run: 1, Failures: 0, Errors: 0, Skipped: 1"))

    def test_gate_command_allowlist_rejects_untrusted_runner_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            sandbox = Path(temp)
            worktree = sandbox / "repo"
            worktree.mkdir()
            fake_python = worktree / "python.exe"
            fake_maven = worktree / "mvnw.cmd"
            npm_name = "npm.cmd" if os.name == "nt" else "npm"
            fake_npm = worktree / "frontend" / npm_name
            fake_root_npm = worktree / npm_name
            trusted_npm = sandbox / "node-install" / npm_name
            self.assertFalse(orch._gate_command_supported(
                [str(fake_python), "-m", "unittest", "scripts.tests.test_example"]))
            self.assertFalse(orch._gate_command_supported(
                [str(fake_maven), "verify"], worktree=Path(temp)))
            fake_root_npm.write_text("@echo off\necho Tests 1 passed (1)\n", encoding="utf-8")
            fake_npm.parent.mkdir()
            (worktree / "frontend" / "package.json").write_text('{"scripts":{"test":"vitest run"}}', encoding="utf-8")
            (worktree / "frontend" / "tests").mkdir()
            (worktree / "frontend" / "tests" / "smoke.test.ts").write_text("", encoding="utf-8")
            fake_npm.write_text("@echo off\necho Tests 1 passed (1)\n", encoding="utf-8")
            trusted_npm.parent.mkdir()
            trusted_npm.write_text("@echo off\necho trusted npm\n", encoding="utf-8")
            if os.name != "nt":
                fake_root_npm.chmod(0o755)
                fake_npm.chmod(0o755)
                trusted_npm.chmod(0o755)
            fake_path = os.pathsep.join((str(worktree), str(fake_npm.parent), str(trusted_npm.parent)))
            with patch.dict(orch.os.environ, {"PATH": fake_path, "PATHEXT": ".COM;.EXE;.BAT;.CMD"}), \
                    patch.object(orch.Path, "cwd", return_value=worktree):
                self.assertFalse(orch._gate_command_supported([str(fake_npm), "test"], worktree=worktree))
                self.assertFalse(orch._gate_command_supported([str(fake_root_npm), "test"], worktree=worktree))
                self.assertFalse(orch._gate_command_supported(["npm", "test"], worktree=worktree))
                self.assertTrue(orch._gate_command_supported(
                    ["npm", "test", "--", "tests/smoke.test.ts"], worktree=worktree))
                self.assertTrue(orch._gate_command_supported(
                    [str(trusted_npm), "test", "--", "tests/smoke.test.ts"], worktree=worktree))
                self.assertEqual(str(trusted_npm.resolve()), orch._trusted_npm_runner("npm", worktree))
                self.assertEqual(str(trusted_npm.resolve()), orch._trusted_path_runner("npm", worktree))

    def test_full_gate_resolves_host_tools_outside_worktree_and_uses_filtered_path(self):
        with tempfile.TemporaryDirectory() as temp:
            sandbox = Path(temp)
            worktree = sandbox / "repo"
            install = sandbox / "trusted-bin"
            frontend = worktree / "frontend"
            frontend.mkdir(parents=True)
            install.mkdir()
            extension = ".exe" if os.name == "nt" else ""
            names = {"java": f"java{extension}", "node": f"node{extension}",
                     "npm": "npm.cmd" if os.name == "nt" else "npm", "docker": f"docker{extension}"}
            for name in names.values():
                (install / name).write_text("trusted runner\n", encoding="utf-8")
            (worktree / names["docker"]).write_text("fake docker\n", encoding="utf-8")
            (frontend / names["npm"]).write_text("fake npm\n", encoding="utf-8")
            if os.name != "nt":
                for path in [*(install / name for name in names.values()),
                             worktree / names["docker"], frontend / names["npm"]]:
                    path.chmod(0o755)
            with patch.object(full_gate, "ROOT", worktree), \
                    patch.object(full_gate.Path, "cwd", return_value=worktree), \
                    patch.dict(os.environ, {"PATH": os.pathsep.join((str(worktree), str(frontend), str(install))),
                                            "PATHEXT": ".COM;.EXE;.BAT;.CMD"}):
                runners, env = full_gate.resolve_runners()
            self.assertEqual({key: str((install / name).resolve()) for key, name in names.items()}, runners)
            self.assertEqual(str(install.resolve()), env["PATH"])
            self.assertNotIn(str(worktree), env["PATH"])

    def test_verification_refuses_pass_if_worktree_changes_during_execution(self):
        with tempfile.TemporaryDirectory() as temp:
            worktree = self._existing_worktree(temp)
            head = self._git(worktree, "rev-parse", "HEAD")
            branch = self._git(worktree, "branch", "--show-current")
            record = {"number": 42, "branch": branch, "head_sha": head, "worktree": str(worktree)}
            evidence = {"sha": head, "command": ["python", "-m", "unittest", "scripts.tests.test_example"]}
            evidence_dir = Path(temp) / "evidence"

            def mutate_during_run(*args, **kwargs):
                (worktree / "tracked.txt").write_text("changed during test\n", encoding="utf-8")
                return Mock(returncode=0, stdout="Ran 1 test in 0.01s\n\nOK\n")

            with patch.object(orch, "_execute_gate_command", side_effect=mutate_during_run):
                with self.assertRaisesRegex(orch.PolicyError, "changed during execution"):
                    orch._verify_gate_artifacts(record, evidence, evidence_dir, "TARGETED_VERIFIED")
            logs = list(evidence_dir.glob("*.log"))
            self.assertEqual(1, len(logs))
            transcript = json.loads(logs[0].read_text(encoding="utf-8"))
            self.assertEqual(head, transcript["worktree_after"]["head_sha"])
            self.assertTrue(transcript["worktree_after"]["dirty"])

    def test_targeted_gate_requires_a_nonzero_test_count_in_actual_output(self):
        self.assertFalse(orch._verification_output_contains_test_run(
            ["python", "-m", "unittest", "-h"], "usage: unittest [options]\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["python", "-m", "unittest", "scripts.tests.missing"], "Ran 0 tests in 0.00s\nOK\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["python", "-m", "pytest", "tests"], "no tests ran\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["python", "-m", "pytest", "tests"], "3 skipped in 0.1s\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["npm", "test"], "Tests 3 skipped (3)\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["mvnw", "verify"], "Tests run: 2, Failures: 0, Errors: 0, Skipped: 2\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["mvnw", "verify"], "Tests run: 2, Failures: 1, Errors: 0, Skipped: 0\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["mvnw", "verify"], "Tests run: 2, Failures: 0, Errors: 1, Skipped: 0\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["mvnw", "verify"], "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n"
            "Tests run: 3, Failures: 1, Errors: 0, Skipped: 0\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["python", "-m", "unittest", "scripts.tests.test_example"],
            "Ran 2 tests in 0.01s\nOK (skipped=2)\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["python", "-m", "unittest", "scripts.tests.test_example"],
            "Ran 2 tests in 0.01s\nFAILED (failures=1)\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["python", "-m", "pytest", "tests"], "2 passed, 1 failed in 0.3s\n"))
        self.assertFalse(orch._verification_output_contains_test_run(
            ["npm", "test"], "Tests 1 failed | 2 passed (3)\n"))
        self.assertTrue(orch._verification_output_contains_test_run(
            ["python", "-m", "unittest", "scripts.tests.test_example"], "Ran 2 tests in 0.01s\nOK\n"))
        self.assertTrue(orch._verification_output_contains_test_run(
            ["python", "-m", "pytest", "tests"], "2 passed, 1 skipped in 0.3s\n"))
        self.assertTrue(orch._verification_output_contains_test_run(
            ["npm", "test"], "Tests  4 passed (4)\n"))
        self.assertTrue(orch._verification_output_contains_test_run(
            ["mvnw", "verify"], "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0\n"))

    def test_targeted_npm_runner_uses_shared_high_load_lock(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            frontend = root / "frontend"
            (frontend / "tests").mkdir(parents=True)
            (frontend / "package.json").write_text('{"scripts":{"test":"vitest run"}}', encoding="utf-8")
            (frontend / "tests" / "smoke.test.ts").write_text("", encoding="utf-8")
            state_dir = root / "shared"
            lock_dir = state_dir / "locks" / "high-load.lock"
            def assert_locked(*args, **kwargs):
                self.assertTrue(lock_dir.is_dir())
                return Mock(returncode=0, stdout="Tests 2 passed (2)")
            with patch.object(orch, "shared_state_dir", return_value=state_dir), \
                    patch.object(orch, "_run_captured", side_effect=assert_locked):
                result = orch._execute_gate_command(["npm", "test", "--", "tests/smoke.test.ts"], root, 162)
            self.assertEqual(0, result.returncode)
            self.assertFalse(lock_dir.exists())

    def test_resource_cli_rejects_a_per_worktree_lock_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            shared = Path(temp) / "common" / "supplypilot-orchestrator"
            with patch.object(orch, "shared_state_dir", return_value=shared):
                self.assertEqual(shared, orch._resource_state_dir(str(shared)))
                with self.assertRaisesRegex(orch.PolicyError, "shared Git common-dir"):
                    orch._resource_state_dir(str(Path(temp) / "worktree-local"))

    def test_full_gate_execution_holds_shared_high_load_lock(self):
        with tempfile.TemporaryDirectory() as temp:
            state_dir = Path(temp) / "state"
            lock_dir = state_dir / "locks" / "high-load.lock"
            def assert_locked(*args, **kwargs):
                self.assertTrue(lock_dir.is_dir())
                return Mock(returncode=0, stdout="FULL GATE PASS")
            with patch.object(orch.subprocess, "run", side_effect=assert_locked) as run:
                result = orch._execute_full_gate(Path(temp), 162, state_dir)
            self.assertEqual(0, result.returncode)
            self.assertFalse((state_dir / "locks" / "high-load.lock").exists())
            command = run.call_args.args[0]
            self.assertEqual("supplypilot_full_gate.py", Path(command[1]).name)

    def _git(self, path, *args):
        return subprocess.run(["git", "-C", str(path), *args], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True).stdout.strip()

    def _existing_worktree(self, root, number=42):
        worktree = Path(root) / "existing"
        worktree.mkdir()
        self._git(worktree, "init", "-b", f"codex/issue-{number}-existing")
        self._git(worktree, "config", "user.name", "Test User")
        self._git(worktree, "config", "user.email", "test@example.com")
        (worktree / "tracked.txt").write_text("base\n", encoding="utf-8")
        (worktree / "scripts" / "tests").mkdir(parents=True)
        (worktree / "scripts" / "tests" / "__init__.py").write_text("", encoding="utf-8")
        self._git(worktree, "add", "tracked.txt")
        self._git(worktree, "add", "scripts/tests/__init__.py")
        self._git(worktree, "commit", "-m", "base")
        return worktree

    def _adoption_record(self, worktree, number=42):
        return {"number": number, "dependencies": [], "base_sha": self._git(worktree, "rev-parse", "HEAD"),
                "branch": f"codex/issue-{number}-existing", "worktree": str(worktree),
                "contract_version": "v1", "owned_files": ["tracked.txt"]}

    def _provisional_resume_fixture(self, temp, include_contract=True):
        root = Path(temp)
        upstream_root = root / "upstream"
        downstream_root = root / "downstream"
        upstream_root.mkdir()
        downstream_root.mkdir()
        upstream = self._existing_worktree(upstream_root)
        self._git(upstream, "branch", "-m", "codex/issue-41-source")
        upstream_sha = self._git(upstream, "rev-parse", "HEAD")
        downstream = self._existing_worktree(downstream_root)
        base_sha = self._git(downstream, "rev-parse", "HEAD")
        state_path = root / "state.json"
        with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
            orch.state_init(state_path, base_sha)
        orch.state_register(state_path, {
            "number": 41, "dependencies": [], "base_sha": base_sha,
            "branch": "codex/issue-41-source", "worktree": str(upstream),
            "head_sha": upstream_sha, "contract_version": "v1",
            "owned_files": ["tracked.txt"], "implementation_state": "IMPLEMENTING",
        })
        upstream_state = orch.read_json(state_path)
        upstream_state["issues"]["41"]["implementation_state"] = "IMPLEMENTING"
        upstream_state["issues"]["41"]["head_sha"] = upstream_sha
        orch.atomic_write_json(state_path, upstream_state)
        (downstream / "tracked.txt").write_text("preserve provisional WIP\n", encoding="utf-8")
        (downstream / "untracked.txt").write_text("preserve untracked WIP\n", encoding="utf-8")
        record = self._adoption_record(downstream)
        record["dependencies"] = [41]
        if include_contract:
            record["provisional_contract"] = {
                "user_authorized": True,
                "sources": [{"issue": 41, "branch": "codex/issue-41-source",
                             "commit_sha": upstream_sha, "contract_version": "v1",
                             "contract": "upstream exports field x as a string",
                             "verification_state": "NOT_RUN"}],
            }
        orch.state_adopt(state_path, record, 42)
        fingerprint = orch.read_json(state_path)["issues"]["42"]["wip_adoption"]["fingerprint"]
        return state_path, upstream, downstream, upstream_sha, base_sha, fingerprint

    def test_provisional_resume_records_source_contract_and_preserves_only_code_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            path, _upstream, downstream, upstream_sha, base_sha, fingerprint = self._provisional_resume_fixture(temp)
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            state = orch.read_json(path)
            record = state["issues"]["42"]
            resume = record["wip_adoption"]["resume_evidence"]
            self.assertEqual("PROVISIONAL", resume["mode"])
            self.assertTrue(resume["user_authorized"])
            self.assertEqual(42, resume["authorized_issue"])
            self.assertTrue(resume["code_gate_pass"])
            self.assertEqual([41], resume["unmet_dependencies"])
            self.assertEqual(upstream_sha, resume["source_issues"][0]["commit_sha"])
            self.assertEqual("codex/issue-41-source", resume["source_issues"][0]["branch"])
            self.assertEqual("v1", resume["source_issues"][0]["contract_version"])
            self.assertEqual(hashlib.sha256(b"upstream exports field x as a string").hexdigest(),
                             resume["source_issues"][0]["contract_sha256"])
            self.assertEqual("NOT_RUN", record["verification"]["status"])
            self.assertEqual("preserve provisional WIP\n", (downstream / "tracked.txt").read_text(encoding="utf-8"))
            self.assertEqual("preserve untracked WIP\n", (downstream / "untracked.txt").read_text(encoding="utf-8"))
            record["head_sha"] = record["wip_adoption"]["head"]
            orch.atomic_write_json(path, state)
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha), \
                    patch.object(orch, "_verify_feature_base", return_value={}):
                with self.assertRaisesRegex(orch.PolicyError, "not verified MERGED"):
                    orch.state_transition(path, 42, "FULL_GATE_PENDING", {})

    def test_merged_dependencies_allow_general_resume_without_provisional_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            path, _upstream, _downstream, _source_sha, base_sha, fingerprint = self._provisional_resume_fixture(temp)
            state = orch.read_json(path)
            source = state["issues"]["41"]
            source["implementation_state"] = "MERGED"
            source["merge"] = {"status": "MERGED", "merge_commit_sha": "f" * 40,
                               "issue_closed": True}
            orch.atomic_write_json(path, state)
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            resumed = orch.read_json(path)["issues"]["42"]
            evidence = resumed["wip_adoption"]["resume_evidence"]
            self.assertEqual("GENERAL", evidence["mode"])
            self.assertEqual([], evidence["unmet_dependencies"])
            self.assertEqual([], evidence["source_issues"])
            self.assertEqual("IMPLEMENTING", resumed["implementation_state"])

    def test_provisional_resume_rejects_changed_source_commit_and_missing_contract(self):
        with tempfile.TemporaryDirectory() as temp:
            path, upstream, _downstream, _source_sha, base_sha, fingerprint = self._provisional_resume_fixture(temp)
            (upstream / "tracked.txt").write_text("changed source\n", encoding="utf-8")
            self._git(upstream, "add", "tracked.txt")
            self._git(upstream, "commit", "-m", "source contract changed")
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                with self.assertRaisesRegex(orch.PolicyError, "commit changed"):
                    orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            self.assertEqual("BLOCKED_DEPENDENCY", orch.read_json(path)["issues"]["42"]["implementation_state"])

        with tempfile.TemporaryDirectory() as temp:
            path, upstream, _downstream, _source_sha, base_sha, fingerprint = self._provisional_resume_fixture(temp)
            state = orch.read_json(path)
            source = state["issues"]["42"]["provisional_contract"]["sources"][0]
            source["worktree_state"] = orch.capture_worktree(upstream)["fingerprint"]
            orch.atomic_write_json(path, state)
            (upstream / "tracked.txt").write_text("uncommitted upstream WIP\n", encoding="utf-8")
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                with self.assertRaisesRegex(orch.PolicyError, "WIP fingerprint changed"):
                    orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            self.assertEqual("BLOCKED_DEPENDENCY", orch.read_json(path)["issues"]["42"]["implementation_state"])

        with tempfile.TemporaryDirectory() as temp:
            path, _upstream, _downstream, _source_sha, base_sha, fingerprint = self._provisional_resume_fixture(temp)
            state = orch.read_json(path)
            state["issues"]["41"]["contract_version"] = "v2"
            orch.atomic_write_json(path, state)
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                with self.assertRaisesRegex(orch.PolicyError, "contract version changed"):
                    orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            self.assertEqual("BLOCKED_DEPENDENCY", orch.read_json(path)["issues"]["42"]["implementation_state"])

        with tempfile.TemporaryDirectory() as temp:
            path, _upstream, _downstream, _source_sha, base_sha, fingerprint = self._provisional_resume_fixture(
                temp, include_contract=False)
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                with self.assertRaisesRegex(orch.PolicyError, "explicit, complete provisional"):
                    orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            self.assertEqual("BLOCKED_DEPENDENCY", orch.read_json(path)["issues"]["42"]["implementation_state"])

    def test_provisional_resume_rejects_contract_content_changed_after_authorization(self):
        with tempfile.TemporaryDirectory() as temp:
            path, _upstream, _downstream, _source_sha, base_sha, fingerprint = self._provisional_resume_fixture(temp)
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            state = orch.read_json(path)
            record = state["issues"]["42"]
            record["provisional_contract"]["sources"][0]["contract"] = (
                "upstream exports field x as an integer")
            orch.atomic_write_json(path, state)
            changed = orch.read_json(path)
            with self.assertRaisesRegex(orch.PolicyError, "contract/WIP changed"):
                orch._validate_resumed_provisional_contract(changed, changed["issues"]["42"])

    def test_clean_existing_worktree_can_be_adopted_without_claiming_test_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            worktree = self._existing_worktree(temp)
            path = Path(temp) / "state.json"
            base_sha = self._git(worktree, "rev-parse", "HEAD")
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_init(path, base_sha)
            orch.state_adopt(path, self._adoption_record(worktree), 42)
            record = orch.read_json(path)["issues"]["42"]
            self.assertEqual("ADOPTED_PENDING_RESUME", record["wip_adoption"]["status"])
            self.assertTrue(record["wip_adoption"]["clean"])
            self.assertEqual("BLOCKED_DEPENDENCY", record["implementation_state"])
            self.assertEqual("NOT_RUN", record["verification"]["status"])

    def test_dirty_wip_and_untracked_files_survive_adopt_and_authorized_resume(self):
        with tempfile.TemporaryDirectory() as temp:
            worktree = self._existing_worktree(temp)
            (worktree / "tracked.txt").write_text("changed\n", encoding="utf-8")
            (worktree / "untracked.txt").write_text("keep me\n", encoding="utf-8")
            path = Path(temp) / "state.json"
            base_sha = self._git(worktree, "rev-parse", "HEAD")
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_init(path, base_sha)
            orch.state_adopt(path, self._adoption_record(worktree), 42)
            record = orch.read_json(path)["issues"]["42"]
            fingerprint = record["wip_adoption"]["fingerprint"]
            self.assertFalse(record["wip_adoption"]["clean"])
            self.assertEqual(["untracked.txt"], record["wip_adoption"]["untracked_files"])
            self.assertEqual("NOT_RUN", record["verification"]["status"])
            with self.assertRaisesRegex(orch.PolicyError, "explicit authorization"):
                orch.state_resume(path, 42, 41, fingerprint, code_gate_pass=True)
            with self.assertRaisesRegex(orch.PolicyError, "Code Gate"):
                orch.state_resume(path, 42, 42, fingerprint)
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            resumed = orch.read_json(path)["issues"]["42"]
            self.assertEqual("IMPLEMENTING", resumed["implementation_state"])
            self.assertEqual("NOT_RUN", resumed["verification"]["status"])
            self.assertEqual("changed\n", (worktree / "tracked.txt").read_text(encoding="utf-8"))
            self.assertEqual("keep me\n", (worktree / "untracked.txt").read_text(encoding="utf-8"))

    def test_changed_wip_fingerprint_requires_readoption_before_resume(self):
        with tempfile.TemporaryDirectory() as temp:
            worktree = self._existing_worktree(temp)
            path = Path(temp) / "state.json"
            base_sha = self._git(worktree, "rev-parse", "HEAD")
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_init(path, base_sha)
            orch.state_adopt(path, self._adoption_record(worktree), 42)
            fingerprint = orch.read_json(path)["issues"]["42"]["wip_adoption"]["fingerprint"]
            (worktree / "tracked.txt").write_text("changed after adoption\n", encoding="utf-8")
            with self.assertRaisesRegex(orch.PolicyError, "re-adopt"):
                orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            orch.state_adopt(path, self._adoption_record(worktree), 42)
            readopted = orch.read_json(path)["issues"]["42"]
            new_fingerprint = readopted["wip_adoption"]["fingerprint"]
            self.assertNotEqual(fingerprint, new_fingerprint)
            self.assertEqual([fingerprint], [item["fingerprint"] for item in readopted["wip_adoption"]["history"]])
            self.assertEqual("BLOCKED_DEPENDENCY", readopted["implementation_state"])
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_resume(path, 42, 42, new_fingerprint, code_gate_pass=True)
            self.assertEqual("IMPLEMENTING", orch.read_json(path)["issues"]["42"]["implementation_state"])

    def test_resume_rejects_stale_main_even_when_local_state_has_not_synced(self):
        with tempfile.TemporaryDirectory() as temp:
            worktree = self._existing_worktree(temp)
            base_sha = self._git(worktree, "rev-parse", "HEAD")
            path = Path(temp) / "state.json"
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_init(path, base_sha)
            orch.state_adopt(path, self._adoption_record(worktree), 42)
            fingerprint = orch.read_json(path)["issues"]["42"]["wip_adoption"]["fingerprint"]
            with patch.object(orch, "_verified_remote_main_sha", return_value="b" * 40):
                with self.assertRaisesRegex(orch.PolicyError, "origin/main advanced"):
                    orch.state_resume(path, 42, 42, fingerprint, code_gate_pass=True)
            self.assertEqual("BLOCKED_DEPENDENCY", orch.read_json(path)["issues"]["42"]["implementation_state"])

    def test_adoption_rejects_wrong_branch_and_duplicate_issue(self):
        with tempfile.TemporaryDirectory() as temp:
            worktree = self._existing_worktree(temp)
            path = Path(temp) / "state.json"
            base_sha = self._git(worktree, "rev-parse", "HEAD")
            with patch.object(orch, "_verified_remote_main_sha", return_value=base_sha):
                orch.state_init(path, base_sha)
            wrong = self._adoption_record(worktree)
            wrong["branch"] = "codex/issue-42-another"
            with self.assertRaisesRegex(orch.PolicyError, "branch does not match"):
                orch.state_adopt(path, wrong, 42)
            orch.state_adopt(path, self._adoption_record(worktree), 42)
            with self.assertRaisesRegex(orch.PolicyError, "already has this state/fingerprint"):
                orch.state_adopt(path, self._adoption_record(worktree), 42)


if __name__ == "__main__":
    unittest.main()
