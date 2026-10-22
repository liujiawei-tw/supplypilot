import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import supplypilot_orchestrator as orch


def row(number, dependencies=(), **kwargs):
    result = {"number": number, "dependencies": list(dependencies), "merged": False,
              "closed": False, "base_sha": "main-a", "contract_verified": True,
              "contract_version": "v1", "verified_contract_version": "v1",
              "implementation_state": "NOT_STARTED", "owned_files": []}
    result.update(kwargs)
    return result


class GraphTests(unittest.TestCase):
    def test_three_independent_issues_are_ready(self):
        graph = {"main_sha": "main-a", "issues": [row(10), row(20), row(30)]}
        plan = orch.plan_graph(graph)
        self.assertEqual([10, 20, 30], plan["ready_frontier"])

    def test_unmerged_dependency_blocks_release_and_code(self):
        graph = {"main_sha": "main-a", "issues": [row(1), row(2, [1])]}
        plan = orch.plan_graph(graph)
        self.assertEqual([1], plan["ready_frontier"])
        self.assertFalse(plan["issues"]["2"]["release_ready"])

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
        self.assertFalse(plan["issues"]["2"]["release_ready"])

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
        self.assertFalse(plan["issues"]["1"]["release_ready"])

    def test_merged_issue_is_not_a_release_candidate(self):
        graph = {"main_sha": "main-a", "issues": [
            row(1, merged=True, closed=True)]}
        plan = orch.plan_graph(graph)
        self.assertEqual([], plan["release_frontier"])

    def test_changed_contract_version_blocks_release_until_reverified(self):
        graph = {"main_sha": "main-a", "issues": [
            row(1, contract_version="v2", verified_contract_version="v1")]}
        plan = orch.plan_graph(graph)
        self.assertFalse(plan["issues"]["1"]["release_ready"])
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
            lock_dir = state_dir / "locks" / "vitest.lock"
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
            self.assertFalse((Path(temp) / "locks" / "docker.lock").exists())


class StateTests(unittest.TestCase):
    def _make_state(self, root):
        state_path = Path(root) / "orchestrator.json"
        orch.state_init(state_path, "main-a")
        orch.state_register(state_path, {
            "number": 9, "dependencies": [], "base_sha": "main-a",
            "branch": "codex/issue-9-example", "worktree": "C:/worktrees/issue-9",
            "contract_version": "v1", "owned_files": ["src/a.py"]
        })
        return state_path

    def _ready_evidence(self):
        return {"code_gate": "PASS", "authorized_issue": 9,
                "branch_verified": True, "worktree_verified": True,
                "branch": "codex/issue-9-example", "worktree": "C:/worktrees/issue-9",
                "worktree_clean": True, "worktree_head": "main-a"}

    def test_head_change_invalidates_old_verification_and_review(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, "new-sha", "new-sha", "new-sha")
            record = orch.read_json(path)["issues"]["9"]
            self.assertEqual("IMPLEMENTING", record["implementation_state"])
            self.assertEqual("new-sha", record["head_sha"])
            self.assertEqual("NOT_RUN", record["verification"]["status"])
            self.assertEqual("NOT_REVIEWED", record["gpt_review"]["status"])

    def test_head_update_requires_authorized_implementation_state(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            with self.assertRaisesRegex(orch.PolicyError, "only while.*IMPLEMENTING"):
                orch.state_set_head(path, 9, "new-sha", "new-sha", "new-sha")

    def test_gpt_final_pass_and_pr_require_exact_sha_and_closes_line(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            orch.state_transition(path, 9, "READY_TO_CODE", self._ready_evidence())
            orch.state_transition(path, 9, "IMPLEMENTING", {})
            orch.state_set_head(path, 9, "sha-9", "sha-9", "sha-9")
            orch.state_transition(path, 9, "TARGETED_VERIFIED", {
                "status": "PASS", "sha": "sha-9", "artifacts": ["targeted.log"]
            })
            orch.state_transition(path, 9, "FULL_GATE_PENDING", {
                "dependencies_merged": True, "current_main_sha": "main-a",
                "contract_verified": True, "contract_version": "v1",
                "verified_contract_version": "v1"
            })
            orch.state_transition(path, 9, "FULL_GATE_VERIFIED", {
                "status": "PASS", "sha": "sha-9", "artifacts": ["full-gate.log"]
            })
            orch.state_transition(path, 9, "WAITING_GPT_REVIEW", {
                "local_sha": "sha-9", "remote_sha": "sha-9", "internal_review": "PASS"
            })
            with self.assertRaisesRegex(orch.PolicyError, "exact current HEAD"):
                orch.state_transition(path, 9, "GPT_FINAL_PASS", {
                    "review_status": "FINAL_PASS", "reviewed_sha": "old-sha"
                })
            orch.state_transition(path, 9, "GPT_FINAL_PASS", {
                "review_status": "FINAL_PASS", "reviewed_sha": "sha-9",
                "url": "https://github.com/liujiawei-tw/supplypilot/pull/999"
            })
            with self.assertRaisesRegex(orch.PolicyError, "standalone Closes"):
                orch.state_transition(path, 9, "PR_OPEN", {
                    "base": "main", "head_sha": "sha-9", "url": "https://github.com/example/pr/999",
                    "description": "Closes #9 thanks"
                })
            orch.state_transition(path, 9, "PR_OPEN", {
                "base": "main", "head_sha": "sha-9", "url": "https://github.com/example/pr/999",
                "number": 999, "description": "Summary\n\nCloses #9"
            })
            with self.assertRaisesRegex(orch.PolicyError, "merge and Issue closure"):
                orch.state_transition(path, 9, "MERGED", {"pr_merged": True, "issue_closed": False})
            orch.state_transition(path, 9, "MERGED", {
                "pr_merged": True, "issue_closed": True,
                "merge_commit": "merge-sha", "main_sha": "main-b"
            })
            self.assertEqual("MERGED", orch.read_json(path)["issues"]["9"]["implementation_state"])

    def test_duplicate_issue_assignment_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            with self.assertRaisesRegex(orch.PolicyError, "already has a state record"):
                orch.state_register(path, {
                    "number": 9, "dependencies": [], "base_sha": "main-a",
                    "branch": "codex/issue-9-other", "worktree": "C:/other",
                    "contract_version": "v2", "owned_files": []
                })

    def test_worktree_must_be_unique_and_issue_specific(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            with self.assertRaisesRegex(orch.PolicyError, "dedicated"):
                orch.state_register(path, {
                    "number": 10, "dependencies": [], "base_sha": "main-a",
                    "branch": "main", "worktree": "C:/worktrees/issue-10",
                    "contract_version": "v1", "owned_files": []
                })
            with self.assertRaisesRegex(orch.PolicyError, "already assigned"):
                orch.state_register(path, {
                    "number": 10, "dependencies": [], "base_sha": "main-a",
                    "branch": "codex/issue-10-other", "worktree": "C:/worktrees/issue-9",
                    "contract_version": "v1", "owned_files": []
                })

    def test_final_pass_cannot_be_marked_merged_without_pr_and_user_merge_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self._make_state(temp)
            with self.assertRaisesRegex(orch.PolicyError, "Illegal state transition"):
                orch.state_transition(path, 9, "MERGED", {
                    "pr_merged": True, "issue_closed": True,
                    "merge_commit": "merge", "main_sha": "main-b"
                })


if __name__ == "__main__":
    unittest.main()
