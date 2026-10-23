#!/usr/bin/env python3
"""Conservative SupplyPilot Issue DAG and shared test-resource coordinator."""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import json
import os
import shlex
import socket
import subprocess
import sys
import time
import uuid
import hashlib
import re
import urllib.parse
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, Iterator

RESOURCES = ("vitest", "maven", "docker")
HIGH_LOAD_GROUP = "high-load"
GPT_REVIEW_CONFIRMATION_PREFIX = "CONFIRM GPT FINAL PASS "
ACTIVE_STATES = {"READY_TO_CODE", "IMPLEMENTING", "TARGETED_VERIFIED", "ISSUE_GATE_VERIFIED",
                 "WAITING_DEPENDENCIES", "FULL_GATE_PENDING", "FULL_GATE_VERIFIED", "WAITING_GPT_REVIEW", "CHANGES_REQUIRED",
                 "GPT_FINAL_PASS", "PR_OPEN", "BLOCKED_ENVIRONMENT"}
TERMINAL = {"MERGED", "FAILED"}
VALID_GPT = {"NOT_REVIEWED", "CHANGES_REQUIRED", "FINAL_PASS"}


class PolicyError(ValueError):
    pass


def read_json(path: str | Path) -> dict[str, Any]:
    with Path(path).open("r", encoding="utf-8-sig") as f:
        value = json.load(f)
    if not isinstance(value, dict):
        raise PolicyError("Input must be a JSON object.")
    return value


def validate_graph(graph: dict[str, Any]) -> dict[int, dict[str, Any]]:
    if not isinstance(graph.get("main_sha"), str) or not graph["main_sha"].strip():
        raise PolicyError("main_sha is required.")
    rows = graph.get("issues")
    if not isinstance(rows, list):
        raise PolicyError("issues must be an array.")
    issues: dict[int, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("number"), int):
            raise PolicyError("Every issue requires an integer number.")
        number = row["number"]
        if number in issues:
            raise PolicyError(f"Duplicate issue #{number}.")
        deps = row.get("dependencies", [])
        if not isinstance(deps, list) or any(not isinstance(d, int) for d in deps):
            raise PolicyError(f"Issue #{number}: dependencies must be integer issue numbers.")
        if number in deps:
            raise PolicyError(f"Issue #{number} depends on itself.")
        issues[number] = row
    for number, row in issues.items():
        for dep in row.get("dependencies", []):
            if dep not in issues:
                raise PolicyError(f"Issue #{number} references missing dependency #{dep}.")
    visiting: set[int] = set()
    visited: set[int] = set()

    def visit(number: int) -> None:
        if number in visiting:
            raise PolicyError(f"Circular dependency includes issue #{number}.")
        if number in visited:
            return
        visiting.add(number)
        for dep in issues[number].get("dependencies", []):
            visit(dep)
        visiting.remove(number)
        visited.add(number)

    for number in issues:
        visit(number)
    return issues


def _merged(row: dict[str, Any]) -> bool:
    return row.get("merged") is True and row.get("closed") is True


def _provisional_authorized(row: dict[str, Any], unmet: list[int]) -> bool:
    contract = row.get("provisional_contract")
    if not isinstance(contract, dict) or contract.get("user_authorized") is not True:
        return False
    sources = contract.get("sources")
    if not isinstance(sources, list):
        return False
    by_number = {s.get("issue"): s for s in sources if isinstance(s, dict)}
    for dep in unmet:
        source = by_number.get(dep)
        if not source:
            return False
        required = ("branch", "contract_version", "contract", "verification_state")
        if not all(isinstance(source.get(k), str) and source[k].strip() for k in required):
            return False
        if not isinstance(source.get("commit_sha") or source.get("worktree_state"), str):
            return False
    return True


def _provisional_resume_sources(state: dict[str, Any], record: dict[str, Any],
                                unmet: list[int]) -> list[dict[str, Any]]:
    contract = record.get("provisional_contract")
    if not _provisional_authorized(record, unmet) or not isinstance(contract, dict):
        raise PolicyError("Unmerged dependencies require an explicit, complete provisional Code Gate contract.")
    by_number = {source["issue"]: source for source in contract["sources"]
                 if isinstance(source, dict) and isinstance(source.get("issue"), int)}
    verified_sources = []
    for dependency in unmet:
        source = by_number.get(dependency)
        if not isinstance(source, dict):
            raise PolicyError(f"Provisional source Issue #{dependency} is missing from the contract.")
        upstream = state.get("issues", {}).get(str(dependency))
        if not isinstance(upstream, dict):
            raise PolicyError(f"Provisional source Issue #{dependency} is missing from state.")
        branch = source["branch"]
        if branch != upstream.get("branch"):
            raise PolicyError(f"Provisional source Issue #{dependency} branch changed; re-authorize the contract.")
        upstream_verification = upstream.get("verification", {})
        verification_state = (upstream_verification.get("status")
                              if isinstance(upstream_verification, dict) else None)
        verification_state = verification_state or upstream.get("implementation_state")
        if source["verification_state"] != verification_state:
            raise PolicyError(f"Provisional source Issue #{dependency} verification state changed.")
        if source["contract_version"] != upstream.get("contract_version"):
            raise PolicyError(f"Provisional source Issue #{dependency} contract version changed.")
        source_worktree = upstream.get("worktree")
        if not isinstance(source_worktree, str):
            raise PolicyError(f"Provisional source Issue #{dependency} worktree is missing.")
        source_root = Path(source_worktree).resolve(strict=True)
        snapshot = capture_worktree(source_root)
        if snapshot["branch"] != branch:
            raise PolicyError(f"Provisional source Issue #{dependency} actual branch changed.")
        source_sha = source.get("commit_sha")
        source_wip = source.get("worktree_state")
        adoption = upstream.get("wip_adoption", {})
        recorded_sha = upstream.get("head_sha") or (
            adoption.get("head") if isinstance(adoption, dict) else None)
        if source_sha:
            if (not re.fullmatch(r"[0-9a-fA-F]{40}", source_sha)
                    or source_sha != snapshot["head"] or source_sha != recorded_sha):
                raise PolicyError(f"Provisional source Issue #{dependency} commit changed; re-authorize the contract.")
            if not snapshot["clean"] and not source_wip:
                raise PolicyError(f"Provisional source Issue #{dependency} has unrecorded WIP; adopt and fingerprint it.")
        if source_wip:
            if source_wip != snapshot["fingerprint"]:
                raise PolicyError(f"Provisional source Issue #{dependency} WIP fingerprint changed; re-adopt and re-authorize.")
        if not source_sha and not source_wip:
            raise PolicyError(f"Provisional source Issue #{dependency} needs a commit SHA or WIP fingerprint.")
        content = source["contract"]
        verified_sources.append({
            "source_issue": dependency,
            "branch": branch,
            "commit_sha": source_sha or None,
            "worktree_fingerprint": source_wip or None,
            "contract_version": source["contract_version"],
            "contract": content,
            "contract_sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
            "verification_state": source["verification_state"],
        })
    return verified_sources


def _validate_resumed_provisional_contract(state: dict[str, Any], record: dict[str, Any]) -> None:
    adoption = record.get("wip_adoption", {})
    resume = adoption.get("resume_evidence", {}) if isinstance(adoption, dict) else {}
    if not isinstance(resume, dict) or resume.get("mode") != "PROVISIONAL":
        return
    unmet = []
    for dependency in record.get("dependencies", []):
        upstream = state.get("issues", {}).get(str(dependency))
        merge = upstream.get("merge", {}) if isinstance(upstream, dict) else {}
        if (not isinstance(upstream, dict) or upstream.get("implementation_state") != "MERGED"
                or not isinstance(merge, dict) or merge.get("status") != "MERGED"):
            unmet.append(dependency)
    if not unmet:
        return
    sources = _provisional_resume_sources(state, record, unmet)
    expected = resume.get("source_issues")
    if sources != expected or resume.get("unmet_dependencies") != unmet:
        raise PolicyError("Provisional source contract/WIP changed since Resume; re-adopt and re-authorize.")


def _normalize_owned_path(value: str) -> str:
    raw = value.replace("\\", "/").strip()
    windows = PureWindowsPath(value)
    if not raw or raw.startswith("/") or windows.drive:
        raise PolicyError(f"owned_files path must be repository-relative: {value!r}.")
    parts = [part for part in PurePosixPath(raw).parts if part not in ("", ".")]
    if not parts or ".." in parts:
        raise PolicyError(f"owned_files path is ambiguous or escapes the repository: {value!r}.")
    return "/".join(parts).casefold()


def _paths_overlap(left: str, right: str) -> bool:
    a, b = _normalize_owned_path(left), _normalize_owned_path(right)
    return a == b or a.startswith(b + "/") or b.startswith(a + "/")


def plan_graph(graph: dict[str, Any]) -> dict[str, Any]:
    issues = validate_graph(graph)
    main_sha = graph["main_sha"]
    candidates: list[int] = []
    result: dict[str, Any] = {}
    for number, row in issues.items():
        deps = row.get("dependencies", [])
        unmet = [d for d in deps if not _merged(issues[d])]
        started = (row.get("implementation_state", "NOT_STARTED") in ACTIVE_STATES | TERMINAL
                    or _merged(row) or row.get("closed") is True)
        adoption = row.get("wip_adoption", {})
        adopted_wip = isinstance(adoption, dict) and adoption.get("status") in {
            "ADOPTED_PENDING_RESUME", "RESUMED"
        }
        if adopted_wip:
            started = True
        provisional = bool(unmet and _provisional_authorized(row, unmet))
        code_ready = not unmet or provisional
        reason = []
        if unmet and not provisional:
            code_ready = False
            reason.append("unmerged_dependencies")
        if started:
            code_ready = False
            reason.append("already_assigned_or_started")
        contract_current = (row.get("contract_verified") is True
                            and isinstance(row.get("contract_version"), str)
                            and row.get("contract_version") == row.get("verified_contract_version"))
        if not contract_current:
            reason.append("contract_version_changed_or_unverified")
        owned = row.get("owned_files", [])
        state = row.get("implementation_state", "NOT_STARTED")
        ownership_state = row.get("ownership_state")
        if ownership_state is not None and not isinstance(ownership_state, str):
            raise PolicyError(f"Issue #{number}: ownership_state must be a string.")
        explicitly_released = ownership_state == "RELEASED"
        holds_ownership = not explicitly_released and (
            state in ACTIVE_STATES or ownership_state in {"OWNED", "HELD"} or adopted_wip
        )
        if holds_ownership and "owned_files" not in row:
            raise PolicyError(f"Issue #{number}: active ownership is unknown; fail closed.")
        if not isinstance(owned, list) or any(not isinstance(p, str) or not p.strip() for p in owned):
            raise PolicyError(f"Issue #{number}: owned_files must be a string array.")
        normalized_owned = [_normalize_owned_path(p) for p in owned]
        if len(set(normalized_owned)) != len(normalized_owned):
            raise PolicyError(f"Issue #{number}: owned_files contains duplicate normalized paths.")
        if code_ready:
            candidates.append(number)
        head = row.get("head_sha")
        verification = row.get("verification", {})
        verified_artifacts = _verification_artifacts_valid(
            verification.get("evidence", []) if isinstance(verification, dict) else []
        )
        issue_gate = row.get("issue_gate")
        issue_impact = issue_gate.get("impact", {}) if isinstance(issue_gate, dict) else {}
        issue_result = issue_impact.get("actual_result", {}) if isinstance(issue_impact, dict) else {}
        issue_gate_verified = (
            row.get("implementation_state") in {
                "ISSUE_GATE_VERIFIED", "FULL_GATE_VERIFIED", "WAITING_GPT_REVIEW", "CHANGES_REQUIRED", "GPT_FINAL_PASS", "PR_OPEN"
            }
            and isinstance(head, str) and bool(head)
            and isinstance(row.get("issue_gate"), dict)
            and row["issue_gate"].get("status") == "ISSUE_GATE_VERIFIED"
            and row["issue_gate"].get("sha") == head
            and _verification_artifacts_valid(row["issue_gate"].get("evidence", []))
            and isinstance(issue_impact, dict)
            and issue_impact.get("base_sha") == row.get("base_sha")
            and issue_impact.get("head_sha") == head
            and isinstance(issue_impact.get("required_tests"), list) and bool(issue_impact["required_tests"])
            and isinstance(issue_result, dict) and issue_result.get("status") == "PASS"
            and issue_result.get("exit_code") == 0
            and not unmet and not provisional and row.get("base_sha") == main_sha and contract_current
        )
        full_gate_verified = (
            row.get("implementation_state") in {
                "ISSUE_GATE_VERIFIED", "FULL_GATE_VERIFIED", "WAITING_GPT_REVIEW", "CHANGES_REQUIRED", "GPT_FINAL_PASS", "PR_OPEN"
            }
            and isinstance(head, str) and bool(head)
            and isinstance(verification, dict)
            and verification.get("status") == "FULL_GATE_VERIFIED"
            and verification.get("sha") == head
            and verified_artifacts
            and not unmet and not provisional and row.get("base_sha") == main_sha and contract_current
        )
        eligible_for_issue_gate = (
            row.get("implementation_state") in {
                "IMPLEMENTING", "TARGETED_VERIFIED", "WAITING_DEPENDENCIES", "FULL_GATE_PENDING", "FULL_GATE_VERIFIED"
            }
            and not unmet and not provisional and row.get("base_sha") == main_sha
            and contract_current and isinstance(head, str) and bool(head)
        )
        eligible_for_full_gate = (
            row.get("implementation_state") in {"TARGETED_VERIFIED", "WAITING_DEPENDENCIES", "FULL_GATE_PENDING"}
            and not unmet and not provisional and row.get("base_sha") == main_sha
            and contract_current and isinstance(head, str) and bool(head)
            and isinstance(verification, dict) and verification.get("status") == "TARGETED_VERIFIED"
            and verification.get("sha") == head
        )
        handoff = row.get("handoff", {})
        ready_for_gpt_handoff = (
            issue_gate_verified and row.get("implementation_state") == "WAITING_GPT_REVIEW"
            and row.get("internal_review") == "PASS"
            and isinstance(handoff, dict) and handoff.get("local_sha") == head
            and handoff.get("remote_sha") == head
        )
        gpt_review = row.get("gpt_review", {})
        gpt_final_pass = (
            issue_gate_verified and row.get("implementation_state") == "GPT_FINAL_PASS"
            and row.get("internal_review") == "PASS"
            and isinstance(handoff, dict) and handoff.get("local_sha") == head
            and handoff.get("remote_sha") == head
            and isinstance(gpt_review, dict) and gpt_review.get("status") == "FINAL_PASS"
            and gpt_review.get("reviewed_sha") == head
            and _gpt_review_attestation_valid(gpt_review.get("attestation"), head)
        )
        result[number] = {
            "code_ready": code_ready,
            "eligible_for_issue_gate": eligible_for_issue_gate,
            "issue_gate_verified": issue_gate_verified,
            "eligible_for_full_gate": eligible_for_full_gate,
            "full_gate_verified": full_gate_verified,
            "release_gate_verified": full_gate_verified,
            "ready_for_gpt_handoff": ready_for_gpt_handoff,
            "gpt_final_pass": gpt_final_pass,
            "provisional": provisional,
            "unmet_dependencies": unmet,
            "reasons": reason,
            "owned_files": owned,
            "base_current": row.get("base_sha") == main_sha,
        }

    active_owners = [n for n, row in issues.items()
                     if row.get("ownership_state") != "RELEASED" and (
                         row.get("implementation_state", "NOT_STARTED") in ACTIVE_STATES
                         or row.get("ownership_state") in {"OWNED", "HELD"}
                         or (isinstance(row.get("wip_adoption"), dict)
                             and row["wip_adoption"].get("status") in {"ADOPTED_PENDING_RESUME", "RESUMED"}))]
    blocked_candidates: set[int] = set()
    for number in candidates:
        for owner in active_owners:
            if owner == number:
                continue
            owner_paths = issues[owner].get("owned_files")
            if not isinstance(owner_paths, list):
                raise PolicyError(f"Issue #{owner}: active ownership is unknown; fail closed.")
            for path in result[number]["owned_files"]:
                for owner_path in owner_paths:
                    if _paths_overlap(path, owner_path):
                        blocked_candidates.add(number)
                        result[number]["reasons"].append(f"file_owned_by_active_issue_{owner}:{owner_path}")
                        break
    for index, number in enumerate(candidates):
        for other in candidates[index + 1:]:
            conflict = next(((left, right) for left in result[number]["owned_files"]
                             for right in result[other]["owned_files"] if _paths_overlap(left, right)), None)
            if conflict:
                blocked_candidates.update((number, other))
                result[number]["reasons"].append(f"file_owned_by_issue_{other}:{conflict[1]}")
                result[other]["reasons"].append(f"file_owned_by_issue_{number}:{conflict[0]}")
    for number in blocked_candidates:
        result[number]["code_ready"] = False
    ready = sorted(n for n in candidates if result[n]["code_ready"])
    release_gate_verified_frontier = sorted(n for n, item in result.items() if item["full_gate_verified"])
    return {"main_sha": main_sha, "issues": {str(n): result[n] for n in sorted(result)},
            "ready_frontier": ready,
            "issue_gate_eligible_frontier": sorted(n for n, item in result.items() if item["eligible_for_issue_gate"]),
            "release_gate_eligible_frontier": sorted(n for n, item in result.items() if item["eligible_for_full_gate"]),
            "release_gate_verified_frontier": release_gate_verified_frontier,
            "full_gate_eligible_frontier": sorted(n for n, item in result.items() if item["eligible_for_full_gate"]),
            "full_gate_verified_frontier": release_gate_verified_frontier,
            "gpt_handoff_frontier": sorted(n for n, item in result.items() if item["ready_for_gpt_handoff"]),
            "release_frontier": sorted(n for n, item in result.items() if item["gpt_final_pass"])}


def can_open_pr(state_path: Path, issue_number: int, current_head: str) -> tuple[bool, str]:
    """Check the persisted orchestrator record; never trust caller-supplied release JSON."""
    state = read_json(state_path)
    record = state.get("issues", {}).get(str(issue_number))
    if not isinstance(record, dict):
        return False, f"Issue #{issue_number} has no persisted orchestrator state."
    review = record.get("gpt_review", {})
    if not isinstance(review, dict) or review.get("status") != "FINAL_PASS":
        return False, "GPT FINAL PASS is required."
    if review.get("reviewed_sha") != current_head:
        return False, "GPT review SHA does not match current HEAD."
    if record.get("implementation_state") != "GPT_FINAL_PASS":
        return False, "Persisted orchestrator state does not record GPT_FINAL_PASS."
    if not _gpt_review_attestation_valid(review.get("attestation"), current_head):
        return False, "An interactive external GPT review attestation is required."
    if record.get("head_sha") != current_head:
        return False, "Recorded HEAD does not match current HEAD."
    failure = _issue_gate_failure(state, record, current_head)
    if failure:
        return False, failure
    try:
        remote_main = _verified_remote_main_sha()
    except PolicyError as exc:
        return False, str(exc)
    if remote_main != state.get("main_sha"):
        return False, "origin/main advanced; synchronize main and rerun the Issue Gate."
    try:
        feature = _verify_local_remote_feature_head(record)
    except PolicyError as exc:
        return False, str(exc)
    if (feature.get("branch") != record.get("branch")
            or feature.get("local_sha") != current_head
            or feature.get("remote_sha") != current_head):
        return False, "Local and remote feature branch HEAD must still match the reviewed SHA."
    return True, "PR gate satisfied for exact HEAD."


def _issue_gate_context_failure(state: dict[str, Any], record: dict[str, Any]) -> str | None:
    """Require current integration, verified contract, and merged dependencies for Issue Gate."""
    main_sha = state.get("main_sha")
    if (not isinstance(main_sha, str) or record.get("base_sha") != main_sha
            or record.get("base_status", "CURRENT") != "CURRENT"):
        return "Issue base is stale relative to the recorded main; rerun integration and the Issue Gate."
    if not _record_contract_verified(record):
        return "Persisted Issue contract is not verified at its current version."
    for dependency in record.get("dependencies", []):
        dependency_record = state.get("issues", {}).get(str(dependency))
        merge = dependency_record.get("merge", {}) if isinstance(dependency_record, dict) else None
        if (not isinstance(dependency_record, dict)
                or dependency_record.get("implementation_state") != "MERGED"
                or not isinstance(merge, dict) or merge.get("status") != "MERGED"):
            return f"Dependency #{dependency} is not verified MERGED."
    return None


def _issue_gate_failure(state: dict[str, Any], record: dict[str, Any], current_head: str) -> str | None:
    """Return why a recorded impact-based Issue Gate can no longer authorize review."""
    context_failure = _issue_gate_context_failure(state, record)
    if context_failure:
        return context_failure
    if record.get("head_sha") != current_head:
        return "Issue Gate HEAD does not match the reviewed current HEAD."
    verification = record.get("issue_gate")
    if (not isinstance(verification, dict) or verification.get("status") != "ISSUE_GATE_VERIFIED"
            or verification.get("sha") != current_head
            or not _verification_artifacts_valid(verification.get("evidence"))):
        return "Issue Gate verification is missing, stale, incomplete, or does not match current HEAD."
    impact = verification.get("impact")
    actual = impact.get("actual_result") if isinstance(impact, dict) else None
    if (not isinstance(actual, dict) or actual.get("status") != "PASS" or actual.get("exit_code") != 0
            or not isinstance(impact.get("required_tests"), list) or not impact["required_tests"]):
        return "Issue Gate is missing its runner-derived required-test plan or successful execution result."
    try:
        root = Path(record["worktree"]).resolve(strict=True)
        current_plan = _issue_gate_impact_plan(root, record.get("base_sha"), current_head)
    except (KeyError, OSError, PolicyError) as exc:
        return f"Issue Gate impact plan cannot be revalidated: {exc}"
    if any(impact.get(key) != current_plan.get(key) for key in (
            "base_sha", "head_sha", "changed_paths", "behaviors", "required_tests",
            "required_commands", "not_applicable", "release_pending")):
        return "Issue Gate impact plan differs from the current committed diff."
    return None


def _verification_artifacts_valid(artifacts: Any) -> bool:
    if not isinstance(artifacts, list) or not artifacts:
        return False
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            return False
        path, expected = artifact.get("path"), artifact.get("sha256")
        if not isinstance(path, str) or not Path(path).is_absolute() \
                or not isinstance(expected, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
            return False
        try:
            actual = hashlib.sha256(Path(path).read_bytes()).hexdigest()
        except OSError:
            return False
        if actual != expected.lower():
            return False
    return True


def _lock_dir(kind: str, state_dir: Path) -> Path:
    if kind not in RESOURCES:
        raise PolicyError(f"Unknown resource {kind!r}; expected one of {', '.join(RESOURCES)}.")
    folder = state_dir / "locks"
    return folder / f"{HIGH_LOAD_GROUP}.lock"


def shared_state_dir() -> Path:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--git-common-dir"],
            check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PolicyError("Cannot find the shared Git common directory; pass an explicit --state-dir.") from exc
    raw_common_dir = (result.stdout or "").strip()
    if not raw_common_dir:
        raise PolicyError("Git returned an empty common directory; pass an explicit --state-dir.")
    common_dir = Path(raw_common_dir)
    if not common_dir.is_absolute():
        common_dir = Path.cwd() / common_dir
    return common_dir.resolve() / "supplypilot-orchestrator"


def _resource_state_dir(requested: str | None) -> Path:
    shared = shared_state_dir()
    if requested is not None and Path(requested).resolve() != shared:
        raise PolicyError(f"High-load resources must use the shared Git common-dir lock path: {shared}.")
    return shared


def _read_owner(lock_dir: Path) -> dict[str, Any] | None:
    try:
        with (lock_dir / "owner.json").open("r", encoding="utf-8") as f:
            value = json.load(f)
        return value if isinstance(value, dict) else None
    except (OSError, json.JSONDecodeError):
        return None


def _process_alive(owner: dict[str, Any] | None) -> bool | None:
    if not owner:
        return None
    if owner.get("host") != socket.gethostname():
        return None
    pid = owner.get("pid")
    if not isinstance(pid, int):
        return None
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError as exc:
        if exc.errno == getattr(os, "ESRCH", 3) or getattr(exc, "winerror", None) == 87:
            return False
        return None


def _is_stale_lock(lock_exists: bool, owner: dict[str, Any] | None,
                   owner_alive: bool | None) -> bool:
    return lock_exists and (owner is None or owner_alive is False)


def resource_status(state_dir: Path) -> dict[str, Any]:
    result = {}
    lock_dir = state_dir / "locks" / f"{HIGH_LOAD_GROUP}.lock"
    exists = lock_dir.exists()
    owner = _read_owner(lock_dir) if exists else None
    alive = _process_alive(owner) if exists else False
    stale = _is_stale_lock(exists, owner, alive)
    for kind in RESOURCES:
        result[kind] = {"busy": exists, "owner": owner, "stale_record": stale,
                        "owner_process_alive": alive, "resource_group": HIGH_LOAD_GROUP}
    return result


def run_with_resource(kind: str, issue: int, argv: list[str], state_dir: Path) -> int:
    with _resource_lock(kind, issue, argv, state_dir):
        result = subprocess.run(argv, check=False)
        return result.returncode


@contextlib.contextmanager
def _resource_lock(kind: str, issue: int, argv: list[str], state_dir: Path):
    lock_dir = _lock_dir(kind, state_dir)
    lock_dir.parent.mkdir(parents=True, exist_ok=True)
    try:
        # Directory creation is atomic across Windows processes and survives a crash as a
        # diagnosable stale record. It is intentionally never auto-reclaimed.
        lock_dir.mkdir()
    except FileExistsError:
        owner = _read_owner(lock_dir)
        raise PolicyError(f"{kind} resource is busy; owner={json.dumps(owner, ensure_ascii=False)}")
    owner = {
        "issue": issue, "pid": os.getpid(), "host": socket.gethostname(),
        "resource": kind, "resource_group": HIGH_LOAD_GROUP,
        "token": uuid.uuid4().hex,
        "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "command": argv,
    }
    owner_path = lock_dir / "owner.json"
    try:
        owner_path.write_text(json.dumps(owner, indent=2), encoding="utf-8")
    except OSError:
        try:
            lock_dir.rmdir()
        except OSError:
            pass
        raise
    completed = False
    try:
        yield
        completed = True
    finally:
        current = _read_owner(lock_dir)
        if current and current.get("token") == owner["token"]:
            if completed:
                owner_path.unlink(missing_ok=True)
                try:
                    lock_dir.rmdir()
                except OSError:
                    # Preserve unexpected contents for manual inspection.
                    pass
            else:
                current["status"] = "INTERRUPTED_OR_FAILED"
                current["ended_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
                atomic_write_json(owner_path, current)


STATE_VALUES = {
    "BLOCKED_DEPENDENCY", "READY_TO_CODE", "IMPLEMENTING", "TARGETED_VERIFIED", "ISSUE_GATE_VERIFIED",
    "WAITING_DEPENDENCIES", "FULL_GATE_PENDING", "FULL_GATE_VERIFIED",
    "WAITING_GPT_REVIEW", "CHANGES_REQUIRED", "GPT_FINAL_PASS", "PR_OPEN",
    "MERGED", "BLOCKED_ENVIRONMENT", "FAILED",
}
TRANSITIONS = {
    "BLOCKED_DEPENDENCY": {"READY_TO_CODE"},
    "READY_TO_CODE": {"IMPLEMENTING"},
    "IMPLEMENTING": {"TARGETED_VERIFIED", "ISSUE_GATE_VERIFIED", "WAITING_DEPENDENCIES"},
    "TARGETED_VERIFIED": {"IMPLEMENTING", "ISSUE_GATE_VERIFIED", "WAITING_DEPENDENCIES", "FULL_GATE_PENDING"},
    "ISSUE_GATE_VERIFIED": {"IMPLEMENTING", "WAITING_GPT_REVIEW", "FULL_GATE_PENDING"},
    "WAITING_DEPENDENCIES": {"FULL_GATE_PENDING"},
    "FULL_GATE_PENDING": {"IMPLEMENTING", "FULL_GATE_VERIFIED", "ISSUE_GATE_VERIFIED"},
    "FULL_GATE_VERIFIED": {"ISSUE_GATE_VERIFIED", "WAITING_GPT_REVIEW"},
    "WAITING_GPT_REVIEW": {"CHANGES_REQUIRED", "GPT_FINAL_PASS"},
    "CHANGES_REQUIRED": {"IMPLEMENTING", "FULL_GATE_PENDING"},
    "GPT_FINAL_PASS": {"PR_OPEN"},
    "PR_OPEN": {"MERGED"},
    "BLOCKED_ENVIRONMENT": {"READY_TO_CODE", "IMPLEMENTING", "FULL_GATE_PENDING"},
    "FAILED": set(),
    "MERGED": set(),
}
for _state in STATE_VALUES - {"MERGED", "FAILED"}:
    TRANSITIONS[_state] = TRANSITIONS.get(_state, set()) | {"BLOCKED_ENVIRONMENT", "FAILED"}


def _verify_code_gate(state: dict[str, Any], record: dict[str, Any], *,
                      allow_existing_feature_head: bool = False) -> dict[str, Any]:
    number = record.get("number")
    main_sha = state.get("main_sha")
    worktree_value = record.get("worktree")
    if not isinstance(number, int) or not isinstance(main_sha, str) or not isinstance(worktree_value, str):
        raise PolicyError("Code Gate requires a registered Issue, main SHA, and worktree.")
    root = Path(worktree_value).resolve(strict=True)
    if _verified_remote_main_sha(root) != main_sha:
        raise PolicyError("Code Gate is stale because origin/main differs from the recorded main SHA.")
    if record.get("base_sha") != main_sha or record.get("base_status", "CURRENT") != "CURRENT":
        raise PolicyError("Code Gate is blocked until the Issue branch is integrated with current main.")
    if not _record_contract_verified(record):
        raise PolicyError("Code Gate requires a current, verified contract version.")

    snapshot = capture_worktree(root)
    expected_head = record.get("head_sha") if allow_existing_feature_head else main_sha
    if (snapshot["branch"] != record.get("branch") or snapshot["head"] != expected_head
            or not snapshot["clean"]):
        if allow_existing_feature_head:
            raise PolicyError("Code Gate resume requires the registered feature branch at its recorded clean HEAD.")
        raise PolicyError("Code Gate requires the registered dedicated branch at current main with a clean worktree.")
    _verify_feature_contains_main({**record, "head_sha": snapshot["head"]}, main_sha)

    owned_files = record.get("owned_files")
    if not isinstance(owned_files, list) or not owned_files:
        raise PolicyError("Code Gate requires a known, non-empty file ownership list.")
    normalized_owned = [_normalize_owned_path(item) for item in owned_files]
    if len(set(normalized_owned)) != len(normalized_owned):
        raise PolicyError("Code Gate ownership contains duplicate paths.")

    unmet = []
    for dependency in record.get("dependencies", []):
        upstream = state.get("issues", {}).get(str(dependency))
        merge = upstream.get("merge", {}) if isinstance(upstream, dict) else {}
        if (not isinstance(upstream, dict) or upstream.get("implementation_state") != "MERGED"
                or not isinstance(merge, dict) or merge.get("status") != "MERGED"):
            unmet.append(dependency)
    provisional_sources = _provisional_resume_sources(state, record, unmet) if unmet else []

    _verify_no_active_file_owner_conflict(state, record, owned_files)

    return {
        "code_gate": "PASS", "branch_verified": True, "worktree_verified": True,
        "branch": snapshot["branch"], "worktree": str(root), "worktree_clean": True,
        "worktree_head": snapshot["head"], "verified_main_sha": main_sha,
        "unmet_dependencies": unmet, "provisional_sources": provisional_sources,
    }


def _verify_no_active_file_owner_conflict(state: dict[str, Any], record: dict[str, Any],
                                          owned_files: list[str] | None = None) -> None:
    number = record.get("number")
    owned_files = owned_files if owned_files is not None else record.get("owned_files")
    if not isinstance(owned_files, list) or not owned_files:
        raise PolicyError("Code Gate requires a known, non-empty file ownership list.")
    owned_files = [_normalize_owned_path(path) for path in owned_files]
    for other_number, owner in state.get("issues", {}).items():
        if str(other_number) == str(number) or not isinstance(owner, dict):
            continue
        if owner.get("ownership_state") == "RELEASED":
            continue
        active = (owner.get("implementation_state", "NOT_STARTED") in ACTIVE_STATES
                  or owner.get("ownership_state") in {"OWNED", "HELD"}
                  or (isinstance(owner.get("wip_adoption"), dict)
                      and owner["wip_adoption"].get("status") in {"ADOPTED_PENDING_RESUME", "RESUMED"}))
        if not active:
            continue
        owner_files = owner.get("owned_files")
        if not isinstance(owner_files, list):
            raise PolicyError(f"Issue #{other_number} has unknown active file ownership; Code Gate fails closed.")
        owner_files = [_normalize_owned_path(path) for path in owner_files]
        if any(_paths_overlap(left, right) for left in owned_files for right in owner_files):
            raise PolicyError(f"Code Gate file ownership conflicts with active Issue #{other_number}.")


def _gpt_review_attestation_valid(attestation: Any, current_head: str) -> bool:
    if not isinstance(attestation, dict):
        return False
    required = ("reviewed_sha", "conversation_url", "evidence_path", "evidence_sha256",
                "confirmed_by", "confirmation", "confirmed_at")
    if not all(isinstance(attestation.get(key), str) and attestation[key].strip() for key in required):
        return False
    if attestation["reviewed_sha"] != current_head:
        return False
    if attestation["confirmation"] != GPT_REVIEW_CONFIRMATION_PREFIX + current_head:
        return False
    parsed = urllib.parse.urlparse(attestation["conversation_url"])
    if parsed.scheme != "https" or parsed.netloc.casefold() != "chatgpt.com" or "/c/" not in parsed.path:
        return False
    evidence_path = Path(attestation["evidence_path"])
    expected = attestation["evidence_sha256"]
    if not evidence_path.is_absolute() or not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
        return False
    try:
        content = evidence_path.read_text(encoding="utf-8")
        actual = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
    except OSError:
        return False
    return (actual == expected.lower() and _has_positive_final_verdict(content, current_head))


def _review_lines(content: str) -> list[str]:
    """Return review text outside Markdown fences and quoted lines."""
    lines: list[str] = []
    in_fence = False
    for line in content.splitlines():
        stripped = line.strip()
        if re.match(r"^(```|~~~)", stripped):
            in_fence = not in_fence
            continue
        if in_fence or stripped.startswith(">"):
            continue
        lines.append(stripped)
    return lines


def _has_positive_final_verdict(content: str, reviewed_sha: str) -> bool:
    lines = _review_lines(content)
    reviewed_shas = [match.group(1) for line in lines
                     if (match := re.fullmatch(r"Reviewed SHA:\s*([0-9a-fA-F]{40})", line,
                                               re.IGNORECASE))]
    verdicts = [re.sub(r"^Verdict:\s*", "", line, flags=re.IGNORECASE).upper()
                for line in lines
                if re.fullmatch(r"(?:Verdict:\s*)?(?:FINAL PASS|CHANGES REQUIRED|NO FINAL PASS|BLOCKED)",
                                line, re.IGNORECASE)]
    exact_sha = (bool(reviewed_shas) and re.fullmatch(r"[0-9a-fA-F]{40}", reviewed_sha)
                 and all(value.casefold() == reviewed_sha.casefold() for value in reviewed_shas))
    return bool(exact_sha and verdicts and verdicts[-1] == "FINAL PASS")


def _record_contract_verified(record: dict[str, Any]) -> bool:
    contract_version = record.get("contract_version")
    return (record.get("contract_verified") is True
            and isinstance(contract_version, str) and bool(contract_version.strip())
            and record.get("verified_contract_version") == contract_version)


def _trusted_full_gate_manifest_valid(trusted: Any, root: Path, current_head: str) -> bool:
    if not isinstance(trusted, dict):
        return False
    required = ("path", "sha256", "runner_path", "runner_sha256", "reviewed_sha", "conversation_url", "review_evidence_path",
                "review_evidence_sha256", "confirmed_by", "confirmation", "confirmed_at")
    if not all(isinstance(trusted.get(key), str) and trusted[key].strip() for key in required):
        return False
    manifest = root / "scripts" / "supplypilot_full_gate.py"
    runner = root / "scripts" / "supplypilot_orchestrator.py"
    if trusted["path"] != str(manifest.resolve()) or not re.fullmatch(r"[0-9a-fA-F]{64}", trusted["sha256"]):
        return False
    if (trusted["runner_path"] != str(runner.resolve())
            or not re.fullmatch(r"[0-9a-fA-F]{64}", trusted["runner_sha256"])):
        return False
    if trusted["reviewed_sha"] != current_head:
        return False
    if trusted["confirmation"] != (
            f"TRUST FULL GATE {trusted['sha256']} {trusted['runner_sha256']}"):
        return False
    parsed = urllib.parse.urlparse(trusted["conversation_url"])
    if parsed.scheme != "https" or parsed.netloc.casefold() != "chatgpt.com" or "/c/" not in parsed.path:
        return False
    evidence_path = Path(trusted["review_evidence_path"])
    if not evidence_path.is_absolute() or not re.fullmatch(r"[0-9a-fA-F]{64}", trusted["review_evidence_sha256"]):
        return False
    try:
        content = evidence_path.read_text(encoding="utf-8")
        evidence_sha = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
        manifest_sha = hashlib.sha256(manifest.read_bytes()).hexdigest()
        runner_sha = hashlib.sha256(runner.read_bytes()).hexdigest()
    except OSError:
        return False
    return (trusted["reviewed_sha"] in content and "supplypilot_full_gate.py" in content
            and _has_full_gate_manifest_pass(content)
            and evidence_sha == trusted["review_evidence_sha256"].lower()
            and manifest_sha == trusted["sha256"].lower()
            and runner_sha == trusted["runner_sha256"].lower())


def _has_full_gate_manifest_pass(content: str) -> bool:
    lines = _review_lines(content)
    verdicts = [re.sub(r"^FULL_GATE_MANIFEST_REVIEW:\s*", "", line, flags=re.IGNORECASE).upper()
                for line in lines
                if re.fullmatch(r"FULL_GATE_MANIFEST_REVIEW:\s*(?:PASS|CHANGES REQUIRED|BLOCKED|FAIL)",
                                line, re.IGNORECASE)]
    return bool(verdicts) and verdicts[-1] == "PASS"


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


@contextlib.contextmanager
def state_lock(path: Path) -> Iterator[None]:
    """Serialize state read/modify/write operations; a leftover lock fails closed."""
    lock_dir = Path(str(path) + ".lock")
    lock_dir.parent.mkdir(parents=True, exist_ok=True)
    try:
        lock_dir.mkdir()
    except FileExistsError as exc:
        owner = _read_owner(lock_dir)
        raise PolicyError(f"State is locked; owner={json.dumps(owner, ensure_ascii=False)}") from exc
    owner = {"pid": os.getpid(), "host": socket.gethostname(),
             "started_at": dt.datetime.now(dt.timezone.utc).isoformat()}
    try:
        (lock_dir / "owner.json").write_text(json.dumps(owner, indent=2), encoding="utf-8")
        yield
    finally:
        (lock_dir / "owner.json").unlink(missing_ok=True)
        try:
            lock_dir.rmdir()
        except OSError:
            pass


def state_init(path: Path, main_sha: str) -> None:
    with state_lock(path):
        _state_init(path, main_sha)


def _state_init(path: Path, main_sha: str) -> None:
    if path.exists():
        raise PolicyError(f"State file already exists; refusing to overwrite: {path}")
    if not isinstance(main_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", main_sha):
        raise PolicyError("main_sha must be a full commit SHA verified against origin/main.")
    verified_sha = _verified_remote_main_sha()
    if main_sha != verified_sha:
        raise PolicyError("main_sha does not match the verified origin/main SHA.")
    atomic_write_json(path, {
        "schema_version": 1, "main_sha": main_sha,
        "trusted_full_gate_manifest": None,
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "issues": {}, "events": [],
    })


def state_register(path: Path, record: dict[str, Any]) -> None:
    with state_lock(path):
        _state_register(path, record)


def _state_register(path: Path, record: dict[str, Any], *, allow_stale_base: bool = False) -> None:
    number = record.get("number")
    if not isinstance(number, int):
        raise PolicyError("Issue record needs integer number.")
    required = ("dependencies", "base_sha", "branch", "worktree", "contract_version", "owned_files")
    if not all(key in record for key in required):
        raise PolicyError("Issue record is missing one or more required graph/worktree fields.")
    if not isinstance(record["dependencies"], list) or any(not isinstance(dep, int) for dep in record["dependencies"]):
        raise PolicyError("Issue dependencies must be an integer array.")
    if not isinstance(record["owned_files"], list) or any(
            not isinstance(item, str) or not item.strip() for item in record["owned_files"]):
        raise PolicyError("Issue owned_files must be a non-empty-string array.")
    state = read_json(path)
    key = str(number)
    if key in state.get("issues", {}):
        raise PolicyError(f"Issue #{number} already has a state record; refusing duplicate assignment.")
    expected_branch_prefix = f"codex/issue-{number}-"
    if not isinstance(record["branch"], str) or not record["branch"].startswith(expected_branch_prefix):
        raise PolicyError(f"Issue #{number} must use its dedicated {expected_branch_prefix}<slug> branch.")
    if not isinstance(record["worktree"], str) or not Path(record["worktree"]).is_absolute():
        raise PolicyError("Issue worktree must be an absolute path.")
    base_status = "CURRENT" if record["base_sha"] == state.get("main_sha") else "STALE_BASE"
    if base_status == "STALE_BASE" and not allow_stale_base:
        raise PolicyError("Issue base_sha must match the current main_sha at registration.")
    for existing in state.get("issues", {}).values():
        if existing.get("branch") == record["branch"] or existing.get("worktree") == record["worktree"]:
            raise PolicyError("Branch or worktree is already assigned to another Issue.")
    normalized = {
        **record,
        "base_status": base_status,
        "implementation_state": "BLOCKED_DEPENDENCY",
        "verification": {"status": "NOT_RUN", "sha": None, "evidence": []},
        "issue_gate": None,
        "gpt_review": {"status": "NOT_REVIEWED", "reviewed_sha": None},
        "trusted_full_gate_manifest": None,
        "pr": {"status": "NONE", "number": None, "head_sha": None},
        "merge": {"status": "UNMERGED", "merge_commit": None},
        "events": [],
    }
    state.setdefault("issues", {})[key] = normalized
    event = {"issue": number, "from": None, "to": "BLOCKED_DEPENDENCY",
             "at": dt.datetime.now(dt.timezone.utc).isoformat(), "evidence": {"registered": True}}
    state.setdefault("events", []).append(event)
    atomic_write_json(path, state)


def _git_bytes(worktree: Path, *args: str) -> bytes:
    try:
        result = subprocess.run(["git", "-C", str(worktree), *args], check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PolicyError(f"Cannot inspect existing Issue worktree with git {' '.join(args)}.") from exc
    return result.stdout


def _worktree_merge_base(worktree: Path, main_sha: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(worktree), "merge-base", "HEAD", main_sha], check=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding="utf-8", errors="replace",
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PolicyError("Cannot verify the existing WIP worktree base against recorded main.") from exc
    base_sha = result.stdout.strip()
    if not re.fullmatch(r"[0-9a-fA-F]{40}", base_sha):
        raise PolicyError("Existing WIP does not have a verifiable commit base against recorded main.")
    return base_sha


def capture_worktree(worktree: Path) -> dict[str, Any]:
    root = worktree.resolve(strict=True)
    branch = _git_bytes(root, "branch", "--show-current").decode("utf-8", "replace").strip()
    head = _git_bytes(root, "rev-parse", "HEAD").decode("ascii", "replace").strip()
    if not branch:
        raise PolicyError("Existing Issue worktree is detached; adoption requires its dedicated branch.")
    status = _git_bytes(root, "status", "--porcelain=v1", "-z", "--untracked-files=all")
    staged = _git_bytes(root, "diff", "--cached", "--binary")
    unstaged = _git_bytes(root, "diff", "--binary")
    untracked = _git_bytes(root, "ls-files", "--others", "--exclude-standard", "-z")
    digest = hashlib.sha256()
    for label, payload in ((b"branch", branch.encode()), (b"head", head.encode()),
                           (b"status", status), (b"staged", staged), (b"unstaged", unstaged)):
        digest.update(label + b"\0" + payload + b"\0")
    untracked_paths: list[str] = []
    for raw_path in untracked.split(b"\0"):
        if not raw_path:
            continue
        relative = os.fsdecode(raw_path)
        candidate = root / relative
        target = candidate.resolve(strict=True)
        if os.path.commonpath((str(root), str(target))) != str(root):
            raise PolicyError("Untracked path resolves outside the worktree; refusing adoption.")
        if candidate.is_symlink():
            content = os.readlink(candidate).encode("utf-8", "surrogateescape")
        elif candidate.is_file():
            content = candidate.read_bytes()
        else:
            raise PolicyError(f"Cannot fingerprint untracked path {relative!r}.")
        digest.update(b"untracked\0" + raw_path + b"\0" + hashlib.sha256(content).digest())
        untracked_paths.append(relative)
    return {"branch": branch, "head": head, "clean": not status,
            "index_sha256": hashlib.sha256(staged).hexdigest(),
            "worktree_sha256": hashlib.sha256(unstaged).hexdigest(),
            "untracked_files": sorted(untracked_paths), "fingerprint": digest.hexdigest()}


def state_adopt(path: Path, record: dict[str, Any], authorized_issue: int) -> None:
    number = record.get("number")
    if not isinstance(number, int) or authorized_issue != number:
        raise PolicyError("Adoption requires explicit authorization for the same Issue number.")
    if not isinstance(record.get("worktree"), str):
        raise PolicyError("Adoption requires the existing absolute worktree path.")
    worktree = Path(record["worktree"]).resolve(strict=True)
    first = capture_worktree(worktree)
    second = capture_worktree(worktree)
    if first != second:
        raise PolicyError("Worktree changed while capturing its WIP fingerprint; retry after it is stable.")
    expected_prefix = f"codex/issue-{number}-"
    if first["branch"] != record.get("branch") or not first["branch"].startswith(expected_prefix):
        raise PolicyError("Existing worktree branch does not match the dedicated Issue branch.")
    state_main_sha = read_json(path).get("main_sha")
    if not isinstance(state_main_sha, str):
        raise PolicyError("Adoption requires the recorded main SHA.")
    actual_base_sha = _worktree_merge_base(worktree, state_main_sha)
    record = dict(record)
    record["worktree"] = str(worktree)
    record["base_sha"] = actual_base_sha
    record["base_status"] = "CURRENT" if actual_base_sha == state_main_sha else "STALE_BASE"
    record["wip_adoption"] = {
        "status": "ADOPTED_PENDING_RESUME", "fingerprint": first["fingerprint"],
        "branch": first["branch"], "head": first["head"], "clean": first["clean"],
        "index_sha256": first["index_sha256"], "worktree_sha256": first["worktree_sha256"],
        "untracked_files": first["untracked_files"], "authorized_issue": authorized_issue,
        "base_sha": actual_base_sha, "base_status": record["base_status"],
        "adopted_at": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    with state_lock(path):
        if capture_worktree(worktree) != first:
            raise PolicyError("Worktree changed before adoption was recorded; retry after it is stable.")
        state = read_json(path)
        if state.get("main_sha") != state_main_sha:
            raise PolicyError("Recorded main changed during adoption; retry against the current state.")
        existing = state.get("issues", {}).get(str(number))
        if not existing:
            _state_register(path, record, allow_stale_base=True)
            return
        reservation = existing.get("gate_reservation")
        if isinstance(reservation, dict) and reservation.get("status") == "RUNNING":
            raise PolicyError("Cannot re-adopt WIP while this Issue has an active Gate reservation.")
        if existing.get("branch") != first["branch"] or Path(existing.get("worktree", "")).resolve() != worktree:
            raise PolicyError("Existing Issue record points to a different branch or worktree.")
        previous = existing.get("wip_adoption")
        if not isinstance(previous, dict) or previous.get("fingerprint") == first["fingerprint"]:
            raise PolicyError(f"Issue #{number} already has this state/fingerprint; refusing duplicate adoption.")
        if existing.get("implementation_state") in TERMINAL:
            raise PolicyError("A terminal Issue cannot adopt or replace an existing WIP snapshot.")
        history = list(previous.get("history", []))
        history.append({key: previous[key] for key in (
            "status", "fingerprint", "branch", "head", "clean", "index_sha256", "worktree_sha256", "adopted_at"
        ) if key in previous})
        record["wip_adoption"]["history"] = history
        existing["wip_adoption"] = record["wip_adoption"]
        existing["base_sha"] = actual_base_sha
        existing["base_status"] = record["base_status"]
        existing["implementation_state"] = "BLOCKED_DEPENDENCY"
        existing["verification"] = {"status": "NOT_RUN", "sha": None, "evidence": []}
        existing["issue_gate"] = None
        existing["gpt_review"] = {"status": "NOT_REVIEWED", "reviewed_sha": None}
        existing["trusted_full_gate_manifest"] = None
        event = {"issue": number, "type": "WIP_READOPTED", "at": dt.datetime.now(dt.timezone.utc).isoformat(),
                 "evidence": {"previous_fingerprint": previous.get("fingerprint"),
                              "fingerprint": first["fingerprint"], "authorized_issue": authorized_issue}}
        existing.setdefault("events", []).append(event)
        state.setdefault("events", []).append(event)
        atomic_write_json(path, state)


def state_resume(path: Path, number: int, authorized_issue: int, fingerprint: str,
                 code_gate_pass: bool = False) -> None:
    with state_lock(path):
        state = read_json(path)
        record = state.get("issues", {}).get(str(number))
        adoption = record.get("wip_adoption") if record else None
        if not record or not isinstance(adoption, dict):
            raise PolicyError(f"Issue #{number} has no adopted worktree snapshot.")
        reservation = record.get("gate_reservation")
        if isinstance(reservation, dict) and reservation.get("status") == "RUNNING":
            raise PolicyError("Cannot resume an Issue while a Gate reservation is active.")
        if authorized_issue != number or adoption.get("authorized_issue") != number:
            raise PolicyError("Resume requires explicit authorization for the same Issue number.")
        if code_gate_pass is not True:
            raise PolicyError("Resume requires the current Issue Code Gate to pass.")
        if adoption.get("status") != "ADOPTED_PENDING_RESUME":
            raise PolicyError("Adopted WIP is not pending resume.")
        if fingerprint != adoption.get("fingerprint"):
            raise PolicyError("WIP fingerprint changed; re-adopt and re-confirm before resuming.")
        current = capture_worktree(Path(record["worktree"]))
        if current["fingerprint"] != fingerprint or current["branch"] != record.get("branch"):
            raise PolicyError("Worktree identity or WIP changed since adoption; re-adopt before resuming.")
        if record.get("base_sha") != state.get("main_sha"):
            raise PolicyError("Adopted worktree base is stale; re-evaluate dependencies and base first.")
        if not _record_contract_verified(record):
            raise PolicyError("Resume requires the persisted Issue contract to be verified at its current version.")
        if _verified_remote_main_sha() != state.get("main_sha"):
            raise PolicyError("origin/main advanced since the recorded base; synchronize main before resuming.")
        _verify_feature_contains_main({**record, "head_sha": adoption.get("head")}, state["main_sha"])
        _verify_no_active_file_owner_conflict(state, record)
        unmet = []
        for dependency in record.get("dependencies", []):
            dependency_record = state.get("issues", {}).get(str(dependency))
            merge = dependency_record.get("merge", {}) if isinstance(dependency_record, dict) else {}
            if (not dependency_record or dependency_record.get("implementation_state") != "MERGED"
                    or not isinstance(merge, dict) or merge.get("status") != "MERGED"):
                unmet.append(dependency)
        if unmet:
            provisional_sources = _provisional_resume_sources(state, record, unmet)
            resume_mode = "PROVISIONAL"
        else:
            provisional_sources = []
            resume_mode = "GENERAL"
        adoption["status"] = "RESUMED"
        adoption["resumed_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        adoption["resume_evidence"] = {
            "mode": resume_mode,
            "user_authorized": True,
            "authorized_issue": authorized_issue,
            "code_gate_pass": True,
            "fingerprint": fingerprint,
            "source_issues": provisional_sources,
            "unmet_dependencies": unmet,
            "resumed_at": adoption["resumed_at"],
        }
        record["implementation_state"] = "IMPLEMENTING"
        event = {"issue": number, "from": "BLOCKED_DEPENDENCY", "to": "IMPLEMENTING",
                 "type": "WIP_RESUMED", "at": dt.datetime.now(dt.timezone.utc).isoformat(),
                 "evidence": adoption["resume_evidence"]}
        record.setdefault("events", []).append(event)
        state.setdefault("events", []).append(event)
        atomic_write_json(path, state)


def _transition_record(record: dict[str, Any], target: str,
                       evidence: dict[str, Any], main_sha: str,
                       state: dict[str, Any]) -> None:
    current = record.get("implementation_state")
    if target not in STATE_VALUES:
        raise PolicyError(f"Unknown state {target}.")
    if target not in TRANSITIONS.get(current, set()):
        raise PolicyError(f"Illegal state transition: {current} -> {target}.")
    number = record["number"]
    head = record.get("head_sha")
    if target in {"READY_TO_CODE", "IMPLEMENTING"}:
        if evidence.get("code_gate") != "PASS" or evidence.get("authorized_issue") != number:
            raise PolicyError(f"{target} requires Code Gate PASS for this user-authorized Issue.")
        if (evidence.get("branch_verified") is not True or evidence.get("worktree_verified") is not True
                or evidence.get("branch") != record.get("branch")
                or evidence.get("worktree") != record.get("worktree")
                or evidence.get("worktree_clean") is not True
                or evidence.get("worktree_head") != (head if current in {
                    "CHANGES_REQUIRED", "FULL_GATE_PENDING", "TARGETED_VERIFIED",
                    "ISSUE_GATE_VERIFIED", "BLOCKED_ENVIRONMENT"
                } else record.get("base_sha"))):
            raise PolicyError(f"{target} requires independently verified branch and worktree.")
    elif target in {"TARGETED_VERIFIED", "FULL_GATE_VERIFIED", "ISSUE_GATE_VERIFIED"}:
        if evidence.get("status") != "PASS" or not head or evidence.get("sha") != head:
            raise PolicyError(f"{target} requires PASS evidence for the exact current head_sha.")
        if target == "ISSUE_GATE_VERIFIED":
            impact = evidence.get("impact")
            if not isinstance(impact, dict) or not impact.get("required_tests"):
                raise PolicyError("ISSUE_GATE_VERIFIED requires a runner-derived impact plan and executed tests.")
            record["issue_gate"] = {"status": target, "sha": head,
                                    "evidence": evidence.get("artifacts", []), "impact": impact}
        else:
            record["verification"] = {"status": target, "sha": head,
                                      "evidence": evidence.get("artifacts", [])}
    elif target == "FULL_GATE_PENDING":
        if evidence.get("dependencies_merged") is not True:
            raise PolicyError("FULL_GATE_PENDING requires verified merged/closed dependencies.")
        if evidence.get("current_main_sha") != main_sha or not _record_contract_verified(record):
            raise PolicyError("FULL_GATE_PENDING requires current main and verified API/schema contracts.")
        verified_head = evidence.get("verified_worktree_head")
        if not isinstance(verified_head, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", verified_head):
            raise PolicyError("FULL_GATE_PENDING requires the independently verified feature HEAD.")
        record["head_sha"] = verified_head
        record["base_sha"] = main_sha
    elif target == "WAITING_GPT_REVIEW":
        if not head or not (evidence.get("local_sha") == evidence.get("remote_sha") == head):
            raise PolicyError("GPT handoff requires local, remote, and current HEAD SHA equality.")
        if evidence.get("internal_review") != "PASS":
            raise PolicyError("GPT handoff requires a passed internal preflight.")
        failure = _issue_gate_failure(state, record, head or "")
        if failure:
            raise PolicyError(f"GPT handoff requires a current Issue Gate: {failure}")
        record["internal_review"] = "PASS"
        record["handoff"] = {"local_sha": head, "remote_sha": head}
    elif target in {"CHANGES_REQUIRED", "GPT_FINAL_PASS"}:
        required_review = "CHANGES_REQUIRED" if target == "CHANGES_REQUIRED" else "FINAL_PASS"
        if evidence.get("review_status") != required_review or not head or evidence.get("reviewed_sha") != head:
            raise PolicyError(f"{target} requires GPT evidence for the exact current HEAD.")
        if target == "GPT_FINAL_PASS" and not _gpt_review_attestation_valid(
                evidence.get("attestation"), head):
            raise PolicyError("GPT_FINAL_PASS requires a verified interactive external-review attestation.")
        if target == "GPT_FINAL_PASS":
            failure = _issue_gate_failure(state, record, head)
            if failure:
                raise PolicyError(f"GPT_FINAL_PASS requires a current Issue Gate: {failure}")
        record["gpt_review"] = {"status": required_review, "reviewed_sha": head,
                                "evidence": evidence.get("url"),
                                "attestation": evidence.get("attestation")}
    elif target == "PR_OPEN":
        description = evidence.get("description", "")
        expected = f"Closes #{number}"
        gpt_review = record.get("gpt_review", {})
        if (not isinstance(gpt_review, dict) or gpt_review.get("status") != "FINAL_PASS"
                or gpt_review.get("reviewed_sha") != head
                or not _gpt_review_attestation_valid(gpt_review.get("attestation"), head)):
            raise PolicyError("PR_OPEN requires GPT FINAL PASS for the current HEAD.")
        if evidence.get("verified_github_pr") is not True:
            raise PolicyError("PR_OPEN requires live GitHub verification of the exact open PR.")
        failure = _issue_gate_failure(state, record, head)
        if failure:
            raise PolicyError(f"PR_OPEN requires a current Issue Gate: {failure}")
        if evidence.get("base") != "main" or evidence.get("head_sha") != head:
            raise PolicyError("PR_OPEN requires base main and PR head SHA equal to reviewed HEAD.")
        if not evidence.get("url") or not any(line.strip() == expected for line in description.splitlines()):
            raise PolicyError(f"PR_OPEN requires a verified URL and standalone {expected} line.")
        record["pr"] = {"status": "OPEN", "number": evidence.get("number"),
                        "head_sha": head, "url": evidence["url"]}
    elif target == "MERGED":
        if evidence.get("verified_github") is not True:
            raise PolicyError("MERGED requires live GitHub PR/Issue verification.")
        if not evidence.get("merge_commit") or not evidence.get("main_sha"):
            raise PolicyError("MERGED requires the verified merge commit and current main SHA.")
        record["merge"] = {"status": "MERGED", "merge_commit": evidence["merge_commit"],
                           "main_sha": evidence["main_sha"]}
    record["implementation_state"] = target


def state_transition(path: Path, number: int, target: str, evidence: dict[str, Any]) -> None:
    if target == "GPT_FINAL_PASS":
        raise PolicyError("GPT_FINAL_PASS cannot be set from Evidence JSON; use the interactive review-attest command.")
    if target in {"TARGETED_VERIFIED", "FULL_GATE_VERIFIED", "ISSUE_GATE_VERIFIED"}:
        _state_transition_with_gate(path, number, target, evidence)
        return
    with state_lock(path):
        state = read_json(path)
        record = state.get("issues", {}).get(str(number))
        if not record:
            raise PolicyError(f"Issue #{number} has no registered state record.")
        reservation = record.get("gate_reservation")
        if isinstance(reservation, dict) and reservation.get("status") == "RUNNING":
            raise PolicyError(f"Issue #{number} has an active Gate reservation; wait or use confirmed recovery.")
        if target == "MERGED":
            verified = _verify_github_merge(record)
        elif target == "FULL_GATE_PENDING":
            base_proof = _verify_feature_base(record, state["main_sha"])
            missing_dependencies = []
            for dependency in record.get("dependencies", []):
                dependency_record = state.get("issues", {}).get(str(dependency))
                merge = dependency_record.get("merge", {}) if isinstance(dependency_record, dict) else {}
                if (not isinstance(dependency_record, dict)
                        or dependency_record.get("implementation_state") != "MERGED"
                        or not isinstance(merge, dict) or merge.get("status") != "MERGED"):
                    missing_dependencies.append(dependency)
            if missing_dependencies:
                raise PolicyError(f"Dependencies are not verified MERGED: {missing_dependencies}.")
            verified = dict(evidence)
            verified.update(base_proof)
            verified.update({"dependencies_merged": True, "current_main_sha": state["main_sha"]})
        elif target == "WAITING_GPT_REVIEW":
            if _verified_remote_main_sha(record.get("worktree")) != state.get("main_sha"):
                raise PolicyError("origin/main advanced; synchronize main and rerun the Issue Gate.")
            verified = dict(evidence)
            verified.update(_verify_local_remote_feature_head(record))
        elif target == "PR_OPEN":
            if _verified_remote_main_sha() != state.get("main_sha"):
                raise PolicyError("origin/main advanced; synchronize main and rerun the Issue Gate.")
            verified = dict(evidence)
            verified.update(_verify_github_pr(record, evidence, number))
        elif target in {"READY_TO_CODE", "IMPLEMENTING"}:
            if evidence.get("authorized_issue") != number:
                raise PolicyError(f"{target} requires explicit authorization for this Issue.")
            verified = dict(evidence)
            reentry = record.get("implementation_state") in {
                "CHANGES_REQUIRED", "FULL_GATE_PENDING", "TARGETED_VERIFIED", "BLOCKED_ENVIRONMENT"
            }
            if reentry:
                code_gate = _verify_code_gate(state, record, allow_existing_feature_head=True)
            else:
                code_gate = _verify_code_gate(state, record)
            verified.update(code_gate)
        else:
            verified = evidence
        _state_transition_locked(path, state, record, number, target, verified)


def state_attest_gpt_review(path: Path, number: int, review_status: str, reviewed_sha: str,
                            conversation_url: str, evidence_path: Path, confirmed_by: str) -> None:
    if review_status != "FINAL_PASS":
        raise PolicyError("Only an exact GPT FINAL PASS can be attested for release.")
    if not confirmed_by.strip():
        raise PolicyError("A human reviewer identity is required for the Final Pass attestation.")
    before_state = read_json(path)
    before = before_state.get("issues", {}).get(str(number))
    if (not isinstance(before, dict) or before.get("implementation_state") != "WAITING_GPT_REVIEW"
            or reviewed_sha != before.get("head_sha")):
        raise PolicyError("Final Pass attestation requires the current SHA of an Issue waiting for GPT review.")
    failure = _issue_gate_failure(before_state, before, reviewed_sha)
    if failure:
        raise PolicyError(f"Final Pass attestation requires a current Issue Gate: {failure}")
    remote_feature = _verify_local_remote_feature_head(before)
    if remote_feature.get("local_sha") != reviewed_sha or remote_feature.get("remote_sha") != reviewed_sha:
        raise PolicyError("GPT Final Pass attestation requires local and remote feature HEAD to match the reviewed SHA.")
    if _verified_remote_main_sha() != before_state.get("main_sha"):
        raise PolicyError("origin/main advanced; synchronize main and rerun the Issue Gate.")
    if not sys.stdin.isatty():
        raise PolicyError("GPT Final Pass attestation requires an interactive human terminal.")
    try:
        resolved_evidence = evidence_path.resolve(strict=True)
        content = resolved_evidence.read_text(encoding="utf-8")
        digest = hashlib.sha256(resolved_evidence.read_bytes()).hexdigest()
    except OSError as exc:
        raise PolicyError("GPT review evidence file must exist and be readable as UTF-8.") from exc
    attestation = {
        "reviewed_sha": reviewed_sha, "conversation_url": conversation_url,
        "evidence_path": str(resolved_evidence), "evidence_sha256": digest,
        "confirmed_by": confirmed_by.strip(),
        "confirmation": "", "confirmed_at": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    if not _gpt_review_attestation_valid({**attestation, "confirmation": GPT_REVIEW_CONFIRMATION_PREFIX + reviewed_sha},
                                         reviewed_sha):
        raise PolicyError("Review evidence must show a standalone positive FINAL PASS verdict for the exact SHA and a ChatGPT conversation URL.")
    expected = GPT_REVIEW_CONFIRMATION_PREFIX + reviewed_sha
    print(f"Review status: {review_status}\nReviewed SHA: {reviewed_sha}\nConversation: {conversation_url}")
    print(f"Evidence SHA-256: {digest}\nReviewer: {confirmed_by.strip()}")
    confirmation = input(f"Human reviewer: type exactly '{expected}' to attest: ").strip()
    if confirmation != expected:
        raise PolicyError("Human Final Pass confirmation did not match the exact reviewed SHA.")
    attestation["confirmation"] = confirmation

    with state_lock(path):
        state = read_json(path)
        record = state.get("issues", {}).get(str(number))
        if not isinstance(record, dict) or record.get("implementation_state") != "WAITING_GPT_REVIEW":
            raise PolicyError("GPT Final Pass attestation requires an Issue waiting for GPT review.")
        if reviewed_sha != record.get("head_sha"):
            raise PolicyError("GPT review SHA does not match the current Issue HEAD.")
        remote_feature = _verify_local_remote_feature_head(record)
        if remote_feature.get("local_sha") != reviewed_sha or remote_feature.get("remote_sha") != reviewed_sha:
            raise PolicyError("GPT Final Pass attestation requires local and remote feature HEAD to match the reviewed SHA.")
        failure = _issue_gate_failure(state, record, reviewed_sha)
        if failure:
            raise PolicyError(f"Final Pass attestation requires a current Issue Gate: {failure}")
        if _verified_remote_main_sha() != state.get("main_sha"):
            raise PolicyError("origin/main advanced; synchronize main and rerun the Issue Gate.")
        evidence = {"review_status": "FINAL_PASS", "reviewed_sha": reviewed_sha,
                    "url": conversation_url, "attestation": attestation}
        _state_transition_locked(path, state, record, number, "GPT_FINAL_PASS", evidence)


def state_trust_full_gate_manifest(path: Path, number: int, reviewed_sha: str,
                                   conversation_url: str, evidence_path: Path,
                                   confirmed_by: str) -> None:
    if not confirmed_by.strip():
        raise PolicyError("A human reviewer identity is required to trust a Full Gate manifest.")
    if not sys.stdin.isatty():
        raise PolicyError("Trusting a Full Gate manifest requires an interactive human terminal.")
    state = read_json(path)
    record = state.get("issues", {}).get(str(number))
    if not isinstance(record, dict) or reviewed_sha != record.get("head_sha"):
        raise PolicyError("Manifest review must target the exact current Issue HEAD.")
    worktree = Path(record.get("worktree", "")).resolve(strict=True)
    manifest = (worktree / "scripts" / "supplypilot_full_gate.py").resolve(strict=True)
    if not manifest.is_file() or worktree not in manifest.parents:
        raise PolicyError("Full Gate manifest must be a regular file inside the registered Issue worktree.")
    try:
        resolved_evidence = evidence_path.resolve(strict=True)
        content = resolved_evidence.read_text(encoding="utf-8")
        evidence_sha256 = hashlib.sha256(resolved_evidence.read_bytes()).hexdigest()
    except OSError as exc:
        raise PolicyError("Independent review evidence must exist and be readable as UTF-8.") from exc
    review_lines = _review_lines(content)
    has_exact_sha = any(re.fullmatch(rf"Reviewed SHA:\s*{re.escape(reviewed_sha)}", line,
                                     re.IGNORECASE) for line in review_lines)
    has_manifest_reference = any(re.fullmatch(
        r"Reviewed(?: file)? scripts/supplypilot_full_gate\.py", line, re.IGNORECASE)
        for line in review_lines)
    parsed = urllib.parse.urlparse(conversation_url)
    if (parsed.scheme != "https" or parsed.netloc.casefold() != "chatgpt.com" or "/c/" not in parsed.path
            or not has_exact_sha or not has_manifest_reference
            or not _has_full_gate_manifest_pass(content)):
        raise PolicyError(
            "Review evidence must explicitly identify this SHA and Full Gate manifest and report "
            "FULL_GATE_MANIFEST_REVIEW: PASS in the ChatGPT conversation.")
    manifest_sha256 = hashlib.sha256(manifest.read_bytes()).hexdigest()
    runner = (worktree / "scripts" / "supplypilot_orchestrator.py").resolve(strict=True)
    runner_sha256 = hashlib.sha256(runner.read_bytes()).hexdigest()
    expected = f"TRUST FULL GATE {manifest_sha256} {runner_sha256}"
    print("Proceed only after an independent GPT Review confirms this manifest trust issue is resolved.")
    print(f"Manifest: {manifest}\nManifest SHA-256: {manifest_sha256}")
    print(f"Runner: {runner}\nRunner SHA-256: {runner_sha256}")
    print(f"Reviewed SHA: {reviewed_sha}\nConversation: {conversation_url}")
    print(f"Review evidence SHA-256: {evidence_sha256}\nReviewer: {confirmed_by.strip()}")
    confirmation = input(f"Human reviewer: type exactly '{expected}' to trust this reviewed manifest: ").strip()
    if confirmation != expected:
        raise PolicyError("Human Full Gate manifest confirmation did not match its SHA-256.")
    if hashlib.sha256(manifest.read_bytes()).hexdigest() != manifest_sha256:
        raise PolicyError("Full Gate manifest changed while its trust baseline was being confirmed.")
    if hashlib.sha256(runner.read_bytes()).hexdigest() != runner_sha256:
        raise PolicyError("Full Gate runner changed while its trust baseline was being confirmed.")
    trusted = {
        "path": str(manifest), "sha256": manifest_sha256,
        "runner_path": str(runner), "runner_sha256": runner_sha256,
        "reviewed_sha": reviewed_sha, "conversation_url": conversation_url,
        "review_evidence_path": str(resolved_evidence),
        "review_evidence_sha256": evidence_sha256,
        "confirmed_by": confirmed_by.strip(), "confirmation": confirmation,
        "confirmed_at": dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    with state_lock(path):
        latest = read_json(path)
        current = latest.get("issues", {}).get(str(number))
        if not isinstance(current, dict) or current.get("head_sha") != reviewed_sha:
            raise PolicyError("Issue HEAD changed while the Full Gate manifest trust was being confirmed.")
        if hashlib.sha256(manifest.read_bytes()).hexdigest() != manifest_sha256:
            raise PolicyError("Full Gate manifest changed while its trust baseline was being recorded.")
        current["trusted_full_gate_manifest"] = trusted
        event = {"issue": number, "type": "FULL_GATE_MANIFEST_TRUSTED",
                 "at": trusted["confirmed_at"], "evidence": trusted}
        current.setdefault("events", []).append(event)
        latest.setdefault("events", []).append(event)
        atomic_write_json(path, latest)


def _gate_context_sha(state: dict[str, Any], record: dict[str, Any]) -> str:
    dependencies = {}
    for dependency in record.get("dependencies", []):
        source = state.get("issues", {}).get(str(dependency), {})
        verification = source.get("verification", {}) if isinstance(source, dict) else {}
        adoption = source.get("wip_adoption", {}) if isinstance(source, dict) else {}
        dependencies[str(dependency)] = {
            "implementation_state": source.get("implementation_state"),
            "branch": source.get("branch"),
            "head_sha": source.get("head_sha"),
            "merge": source.get("merge"),
            "contract_version": source.get("contract_version"),
            "verified_contract_version": source.get("verified_contract_version"),
            "contract_verified": source.get("contract_verified"),
            "verification": verification,
            "wip_fingerprint": adoption.get("fingerprint") if isinstance(adoption, dict) else None,
        }
    snapshot = {
        "main_sha": state.get("main_sha"),
        "base_sha": record.get("base_sha"),
        "contract_version": record.get("contract_version"),
        "verified_contract_version": record.get("verified_contract_version"),
        "contract_verified": record.get("contract_verified"),
        "dependencies": dependencies,
        "provisional_contract": record.get("provisional_contract"),
        "issue_gate": record.get("issue_gate"),
        "trusted_full_gate_manifest": record.get("trusted_full_gate_manifest"),
        "resume_evidence": (record.get("wip_adoption", {}).get("resume_evidence")
                            if isinstance(record.get("wip_adoption"), dict) else None),
    }
    encoded = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _state_transition_with_gate(path: Path, number: int, target: str,
                                evidence: dict[str, Any]) -> None:
    token = uuid.uuid4().hex
    remote_main_before = None
    if target in {"FULL_GATE_VERIFIED", "ISSUE_GATE_VERIFIED"}:
        snapshot = read_json(path).get("issues", {}).get(str(number))
        if not isinstance(snapshot, dict) or not isinstance(snapshot.get("worktree"), str):
            raise PolicyError(f"Issue #{number} has no registered worktree for live main verification.")
        remote_main_before = _verified_remote_main_sha(snapshot["worktree"])
    with state_lock(path):
        state = read_json(path)
        record = state.get("issues", {}).get(str(number))
        if not isinstance(record, dict):
            raise PolicyError(f"Issue #{number} has no registered state record.")
        previous = record.get("implementation_state")
        if target not in TRANSITIONS.get(previous, set()):
            raise PolicyError(f"Illegal state transition: {previous} -> {target}.")
        current = record.get("gate_reservation")
        if isinstance(current, dict) and current.get("status") == "RUNNING":
            raise PolicyError(f"Issue #{number} already has an active Gate reservation.")
        if evidence.get("sha") != record.get("head_sha") or not record.get("head_sha"):
            raise PolicyError("Verification command must target the exact current HEAD.")
        worktree_value = record.get("worktree")
        if not isinstance(worktree_value, str):
            raise PolicyError("Verification requires the registered Issue worktree.")
        root = Path(worktree_value).resolve(strict=True)
        branch, head, dirty = _verification_worktree_snapshot(root)
        if branch != record.get("branch") or head != record.get("head_sha") or dirty:
            raise PolicyError("Gate reservation requires the registered branch/HEAD and a clean worktree.")
        command = evidence.get("command")
        if target == "ISSUE_GATE_VERIFIED":
            if "command" in evidence or "commands" in evidence:
                raise PolicyError("Issue Gate commands are derived from the committed diff and cannot be caller-selected.")
        elif not _gate_command_supported(command, target, root):
            raise PolicyError("Verification requires an approved command and trusted runner for this gate.")
        impact = None
        if target == "ISSUE_GATE_VERIFIED":
            prerequisite_failure = _issue_gate_context_failure(state, record)
            if prerequisite_failure:
                raise PolicyError(f"Issue Gate prerequisites are not satisfied: {prerequisite_failure}")
            impact = _issue_gate_impact_plan(root, record.get("base_sha"), head)
        if remote_main_before is not None and remote_main_before != state.get("main_sha"):
            raise PolicyError("Code Gate is stale because origin/main advanced before verification.")
        _validate_resumed_provisional_contract(state, record)
        context_sha = _gate_context_sha(state, record)
        reservation = {
            "token": token, "issue": number, "sha": head, "gate_type": target,
            "branch": branch, "worktree": str(root), "status": "RUNNING",
            "owner_pid": os.getpid(), "owner_host": socket.gethostname(),
            "started_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "command": command,
            "required_commands": impact["required_commands"] if impact is not None else None,
            "context_sha256": context_sha,
            "worktree_before": {"branch": branch, "head_sha": head, "dirty": False},
        }
        if remote_main_before is not None:
            reservation["origin_main_sha"] = remote_main_before
        if impact is not None:
            reservation["impact_plan"] = impact
        record["gate_reservation"] = reservation
        event = {"issue": number, "type": "GATE_RESERVED", "gate_token": token,
                 "at": reservation["started_at"],
                 "evidence": {key: value for key, value in reservation.items() if key != "token"}}
        record.setdefault("events", []).append(event)
        state.setdefault("events", []).append(event)
        atomic_write_json(path, state)

    try:
        verified = _verify_gate_artifacts(
            record, evidence, path.parent / "evidence", target,
            trusted_manifest=record.get("trusted_full_gate_manifest"),
        )
    except Exception as exc:
        _finish_gate_reservation(path, number, token, target, None, str(exc))
        raise

    try:
        remote_main = (_verified_remote_main_sha(root)
                       if target in {"FULL_GATE_VERIFIED", "ISSUE_GATE_VERIFIED"} else None)
    except Exception as exc:
        _finish_gate_reservation(path, number, token, target, None, str(exc))
        raise
    with state_lock(path):
        state = read_json(path)
        current = state.get("issues", {}).get(str(number))
        if not isinstance(current, dict):
            raise PolicyError("Reserved Issue record disappeared during Gate execution.")
        active = current.get("gate_reservation")
        if not isinstance(active, dict) or active.get("token") != token or active.get("status") != "RUNNING":
            raise PolicyError("Gate reservation token is no longer active; result was not committed.")
        branch_after, head_after, dirty_after = _verification_worktree_snapshot(root)
        stale_reasons = []
        if current.get("branch") != branch or current.get("worktree") != str(root):
            stale_reasons.append("Issue branch/worktree identity changed")
        if current.get("head_sha") != head or branch_after != branch or head_after != head or dirty_after:
            stale_reasons.append("Issue branch/HEAD/worktree changed during Gate")
        if _gate_context_sha(state, current) != context_sha:
            stale_reasons.append("main/dependency/contract Gate context changed during execution")
        try:
            _validate_resumed_provisional_contract(state, current)
        except PolicyError as exc:
            stale_reasons.append(str(exc))
        if target == "ISSUE_GATE_VERIFIED":
            prerequisite_failure = _issue_gate_context_failure(state, current)
            if prerequisite_failure:
                stale_reasons.append(prerequisite_failure)
        if target in {"FULL_GATE_VERIFIED", "ISSUE_GATE_VERIFIED"} and remote_main != state.get("main_sha"):
            stale_reasons.append("origin/main advanced or differs from the recorded main SHA")
        if target not in TRANSITIONS.get(current.get("implementation_state"), set()):
            stale_reasons.append("Issue state no longer permits this Gate result")
        if stale_reasons:
            _finish_gate_reservation_locked(path, state, current, token, "STALE", "; ".join(stale_reasons))
            raise PolicyError("Gate result is stale and was not recorded as PASS: " + "; ".join(stale_reasons))
        active.update({"status": "COMMITTED", "ended_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                       "result": verified})
        _state_transition_locked(path, state, current, number, target, verified)


def _finish_gate_reservation(path: Path, number: int, token: str, target: str,
                             result: dict[str, Any] | None, reason: str) -> None:
    with state_lock(path):
        state = read_json(path)
        record = state.get("issues", {}).get(str(number))
        if isinstance(record, dict):
            stale = any(marker in reason.casefold() for marker in (
                "changed during execution", "branch/head/worktree changed", "context changed"))
            _finish_gate_reservation_locked(path, state, record, token,
                                            "STALE" if stale else "FAILED", reason)


def _finish_gate_reservation_locked(path: Path, state: dict[str, Any], record: dict[str, Any],
                                    token: str, status: str, reason: str) -> None:
    reservation = record.get("gate_reservation")
    if not isinstance(reservation, dict) or reservation.get("token") != token:
        return
    reservation.update({"status": status, "ended_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                        "failure_reason": reason})
    event = {"issue": record.get("number"), "type": f"GATE_{status}", "gate_token": token,
             "at": reservation["ended_at"], "evidence": {"reason": reason}}
    record.setdefault("events", []).append(event)
    state.setdefault("events", []).append(event)
    atomic_write_json(path, state)


def _state_transition_locked(path: Path, state: dict[str, Any], record: dict[str, Any],
                             number: int, target: str, evidence: dict[str, Any]) -> None:
    previous = record["implementation_state"]
    _transition_record(record, target, evidence, state["main_sha"], state)
    event = {"issue": number, "from": previous, "to": target,
             "at": dt.datetime.now(dt.timezone.utc).isoformat(), "evidence": evidence}
    record.setdefault("events", []).append(event)
    state.setdefault("events", []).append(event)
    if target == "MERGED":
        _apply_main_sync(state, number, evidence)
    atomic_write_json(path, state)


def _repository_slug() -> str:
    result = subprocess.run(["git", "remote", "get-url", "origin"], check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace")
    remote = result.stdout.strip()
    match = re.search(r"(?:github\.com[:/])([^/]+/[^/]+?)(?:\.git)?$", remote)
    if not match:
        raise PolicyError("origin is not a recognizable GitHub repository URL.")
    return match.group(1)


def _main_diff_changes_contract_surface(old_sha: str, new_sha: str,
                                       worktree: str | Path | None = None) -> bool:
    try:
        command = ["git"]
        if worktree is not None:
            command.extend(["-C", str(worktree)])
        command.extend(["diff", "--no-renames", "--name-status", old_sha, new_sha])
        result = subprocess.run(command, check=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True,
                                encoding="utf-8", errors="replace")
    except (OSError, subprocess.CalledProcessError):
        return True
    contract_markers = {
        "api", "apis", "dto", "schema", "schemas", "migration", "migrations",
        "openapi", "graphql", "proto", "contract", "contracts", "entity", "entities",
        "data", "format",
    }
    for line in result.stdout.splitlines():
        fields = line.split("\t")
        for raw_path in fields[1:]:
            path = raw_path.replace("\\", "/").casefold()
            parts = [part for part in path.split("/") if part]
            filename_tokens = set(re.split(r"[^a-z0-9]+", Path(parts[-1]).stem)) if parts else set()
            if (any(part in contract_markers for part in parts)
                    or bool(filename_tokens & contract_markers)
                    or (len(parts) > 1 and parts[0] in {"backend", "frontend"} and parts[1] == "src")
                    or path.endswith((".sql", ".graphql", ".proto"))):
                return True
    return False


def _verified_remote_main_sha(worktree: str | Path | None = None) -> str:
    try:
        command = ["git"]
        if worktree is not None:
            command.extend(["-C", str(worktree)])
        command.extend(["ls-remote", "origin", "refs/heads/main"])
        result = subprocess.run(command, check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace")
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PolicyError("Cannot verify the current origin/main SHA.") from exc
    fields = result.stdout.split()
    main_sha = fields[0] if len(fields) >= 2 and fields[1] == "refs/heads/main" else None
    if not isinstance(main_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", main_sha):
        raise PolicyError("origin/main did not return a valid commit SHA.")
    return main_sha


def _verify_local_remote_feature_head(record: dict[str, Any]) -> dict[str, str]:
    worktree, branch = record.get("worktree"), record.get("branch")
    if not isinstance(worktree, str) or not isinstance(branch, str):
        raise PolicyError("Feature HEAD verification requires a recorded worktree and branch.")
    root = Path(worktree)
    local_branch = _git_bytes(root, "branch", "--show-current").decode("utf-8", "replace").strip()
    local_sha = _git_bytes(root, "rev-parse", "HEAD").decode("ascii", "replace").strip()
    if local_branch != branch or not re.fullmatch(r"[0-9a-fA-F]{40}", local_sha):
        raise PolicyError("Local Issue worktree branch or HEAD does not match its registered identity.")
    try:
        result = subprocess.run(["git", "-C", worktree, "ls-remote", "origin", f"refs/heads/{branch}"],
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding="utf-8", errors="replace")
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PolicyError("Cannot verify the Issue feature branch on origin.") from exc
    fields = result.stdout.split()
    ref = f"refs/heads/{branch}"
    remote_sha = fields[0] if len(fields) >= 2 and fields[1] == ref else None
    if not isinstance(remote_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", remote_sha):
        raise PolicyError("origin does not contain the registered Issue feature branch.")
    return {"branch": branch, "local_sha": local_sha, "remote_sha": remote_sha}


def _verify_feature_base(record: dict[str, Any], main_sha: str) -> dict[str, Any]:
    worktree_value = record.get("worktree")
    expected_branch, expected_head = record.get("branch"), record.get("head_sha")
    if not isinstance(worktree_value, str) or not isinstance(expected_branch, str) \
            or not isinstance(expected_head, str):
        raise PolicyError("Full Gate requires recorded branch, worktree, and committed HEAD.")
    snapshot = capture_worktree(Path(worktree_value))
    allow_updated_head = record.get("implementation_state") == "CHANGES_REQUIRED"
    if snapshot["branch"] != expected_branch or (snapshot["head"] != expected_head and not allow_updated_head):
        raise PolicyError("Full Gate worktree branch/HEAD differs from the registered Issue branch/HEAD.")
    if not snapshot["clean"]:
        raise PolicyError("Full Gate requires a clean committed worktree; uncommitted WIP is not verified.")
    if allow_updated_head:
        feature = _verify_local_remote_feature_head(record)
        if feature["local_sha"] != feature["remote_sha"] or feature["local_sha"] != snapshot["head"]:
            raise PolicyError("Full Gate requires the integrated feature HEAD to match origin exactly.")
    if _verified_remote_main_sha(worktree_value) != main_sha:
        raise PolicyError("State main_sha no longer matches the verified origin/main SHA.")
    _verify_feature_contains_main({**record, "head_sha": snapshot["head"]}, main_sha)
    return {"verified_worktree_branch": snapshot["branch"], "verified_worktree_head": snapshot["head"],
            "verified_base_main_sha": main_sha}


def _verify_feature_contains_main(record: dict[str, Any], main_sha: str) -> None:
    worktree_value, expected_head = record.get("worktree"), record.get("head_sha")
    if not isinstance(worktree_value, str) or not isinstance(expected_head, str):
        raise PolicyError("Feature ancestry verification requires a worktree and commit HEAD.")
    try:
        subprocess.run(["git", "-C", worktree_value, "merge-base", "--is-ancestor", main_sha, expected_head],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PolicyError("Feature HEAD does not contain the verified current main commit.") from exc


def _gate_command_supported(command: Any, target: str = "TARGETED_VERIFIED",
                            worktree: Path | None = None) -> bool:
    if not isinstance(command, list) or not command or any(not isinstance(arg, str) for arg in command):
        return False
    if target == "FULL_GATE_VERIFIED":
        # Full verification is one fixed repository manifest. Individual lint, build,
        # config, or test commands can never attest the whole gate.
        return command == ["supplypilot-full-gate"]
    executable = Path(command[0].replace("\\", "/")).name.casefold()
    requested_path = Path(command[0])
    is_bare_executable = requested_path.parent == Path(".") and PureWindowsPath(command[0]).parent == PureWindowsPath(".")
    args = command[1:]
    if os.name == "nt" and executable.endswith((".cmd", ".bat")) \
            and any(re.search(r"[%!&|<>^()]", arg) for arg in args):
        return False
    if executable in {"python", "python.exe", "python3", "python3.exe", "py", "py.exe"}:
        if not is_bare_executable and Path(command[0]).resolve() != Path(sys.executable).resolve():
            return False
        if len(args) < 3 or args[0] != "-m" or args[1] != "unittest":
            return False
        targets = [item for item in args[2:] if item not in {"-v", "--verbose"}]
        if not targets or any(not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", item)
                              for item in targets):
            return False
        return worktree is None or _gate_working_directory(command, worktree) is not None
    if executable in {"npm", "npm.cmd", "npm.exe"}:
        trusted_npm = _trusted_npm_runner(executable, worktree)
        if not trusted_npm or (not is_bare_executable and Path(command[0]).resolve() != Path(trusted_npm).resolve()):
            return False
        if (args == ["test"] or args in (["run", "lint"], ["run", "build"])):
            if target != "ISSUE_GATE_VERIFIED":
                return False
            return worktree is None or _gate_working_directory(command, worktree) == (worktree.resolve() / "frontend").resolve()
        if len(args) < 3 or args[:2] != ["test", "--"]:
            return False
        if worktree is None:
            return all(_safe_frontend_test_path(item) for item in args[2:])
        frontend = (worktree.resolve() / "frontend").resolve()
        if _gate_working_directory(command, worktree) != frontend:
            return False
        for item in args[2:]:
            if not _safe_frontend_test_path(item):
                return False
            candidate = (frontend / Path(*PurePosixPath(item.replace("\\", "/")).parts)).resolve()
            try:
                if os.path.commonpath((str(frontend), str(candidate))) != str(frontend) or not candidate.is_file():
                    return False
            except ValueError:
                return False
        return True
    if executable in {"mvn", "mvn.cmd", "mvnw", "mvnw.cmd"}:
        unsafe_properties = {
            "skiptests", "maven.test.skip", "skipits", "skipsurefiretests", "skipfailsafetests",
            "maven.test.failure.ignore", "surefire.test.failure.ignore", "testfailureignore",
        }
        if not args or args[0] not in {"test", "verify"} \
                or any(arg.casefold() in {"--fail-never", "-fn"} for arg in args):
            return False
        test_properties = [arg[7:] for arg in args[1:] if arg.startswith("-Dtest=")]
        integration_properties = [arg[10:] for arg in args[1:] if arg.startswith("-Dit.test=")]
        allowed = {"-B", "--no-transfer-progress"}
        other_args = [arg for arg in args[1:] if arg not in allowed
                      and not arg.startswith(("-Dtest=", "-Dit.test="))]
        if other_args or len(test_properties) > 1 or len(integration_properties) > 1:
            return False
        if (test_properties and not re.fullmatch(r"[A-Za-z0-9_.$,*?]+(?:#[A-Za-z0-9_]+)?", test_properties[0])) \
                or (integration_properties and not re.fullmatch(r"[A-Za-z0-9_.$,*?]+(?:#[A-Za-z0-9_]+)?",
                                                                integration_properties[0])):
            return False
        if not test_properties and not integration_properties and target != "ISSUE_GATE_VERIFIED":
            return False
        if args[0] == "test" and integration_properties:
            return False
        for index, arg in enumerate(args):
            property_arg = None
            if arg.startswith("-D"):
                property_arg = arg[2:]
                if not property_arg and index + 1 < len(args):
                    property_arg = args[index + 1]
            elif arg.startswith("--define="):
                property_arg = arg.split("=", 1)[1]
            elif arg == "--define" and index + 1 < len(args):
                property_arg = args[index + 1]
            if property_arg and property_arg.split("=", 1)[0].casefold() in unsafe_properties:
                return False
        if worktree is None:
            return True
        root = worktree.resolve()
        if is_bare_executable:
            wrapper = root / "backend" / Path(command[0]).name
        else:
            wrapper = Path(command[0]) if Path(command[0]).is_absolute() else root / command[0]
        allowed_wrappers = {(root / "backend" / name).resolve() for name in ("mvnw", "mvnw.cmd")}
        return wrapper.resolve() in allowed_wrappers and _gate_working_directory(command, worktree) == (root / "backend").resolve()
    return False


def _gate_working_directory(command: list[str], repository: Path) -> Path | None:
    """Select the repository module directory from the validated test runner."""
    root = repository.resolve()
    executable = Path(command[0].replace("\\", "/")).name.casefold()
    if executable in {"npm", "npm.cmd", "npm.exe"}:
        expected, marker = root / "frontend", root / "frontend" / "package.json"
    elif executable in {"mvn", "mvn.cmd", "mvnw", "mvnw.cmd"}:
        expected, marker = root / "backend", root / "backend" / "pom.xml"
    else:
        expected, marker = root, root / "scripts" / "tests"
    try:
        resolved_marker = marker.resolve(strict=True)
        resolved_expected = expected.resolve(strict=True)
        if os.path.commonpath((str(root), str(resolved_marker))) != str(root):
            return None
        if os.path.commonpath((str(root), str(resolved_expected))) != str(root):
            return None
        if not resolved_expected.is_dir():
            return None
        return resolved_expected
    except OSError:
        return None


def _execute_gate_command(command: list[str], worktree: Path, issue: int | None = None,
                          state_dir: Path | None = None) -> subprocess.CompletedProcess[str]:
    cwd = _gate_working_directory(command, worktree)
    if cwd is None:
        raise PolicyError("Targeted Gate worktree is not a repository root with the expected module layout.")
    resource_kind = _targeted_resource_kind(command)
    if resource_kind and issue is not None:
        lock_root = state_dir if state_dir is not None else shared_state_dir()
        with _resource_lock(resource_kind, issue, command, lock_root):
            return _run_captured(command, cwd)
    return _run_captured(command, cwd)


def _run_captured(command: list[str], worktree: Path) -> subprocess.CompletedProcess[str]:
    if os.name == "nt" and command[0].casefold().endswith((".cmd", ".bat")):
        return subprocess.run(subprocess.list2cmdline(command), cwd=worktree, check=False,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding="utf-8", errors="replace", shell=True)
    return subprocess.run(command, cwd=worktree, check=False, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")


def _targeted_resource_kind(command: list[str]) -> str | None:
    executable = Path(command[0].replace("\\", "/")).name.casefold()
    if executable in {"npm", "npm.cmd", "npm.exe"}:
        return "vitest"
    if executable in {"mvn", "mvn.cmd", "mvnw", "mvnw.cmd"}:
        return "maven"
    return None


def _prepare_gate_command(command: list[str], worktree: Path) -> list[str]:
    executable = Path(command[0].replace("\\", "/")).name.casefold()
    if executable in {"python", "python.exe", "python3", "python3.exe", "py", "py.exe"}:
        return [sys.executable, *command[1:]]
    if executable in {"npm", "npm.cmd", "npm.exe"}:
        resolved = _trusted_npm_runner(executable, worktree)
        if not resolved:
            raise PolicyError("Cannot resolve the installed npm runner.")
        return [resolved, *command[1:]]
    if executable in {"mvnw", "mvnw.cmd"}:
        path = Path(command[0])
        wrapper = path if path.is_absolute() else worktree / path
        if path.parent == Path(".") and PureWindowsPath(command[0]).parent == PureWindowsPath("."):
            wrapper = worktree / "backend" / path.name
        return [str(wrapper.resolve(strict=True)), *command[1:]]
    return list(command)


def _trusted_path_entries(worktree: Path | None = None) -> list[str]:
    """Return absolute PATH entries outside the current directory and Issue worktree."""
    # Search PATH entries explicitly. shutil.which on Windows may prepend CWD even
    # when a filtered path argument is supplied, which can select a fake npm.cmd.
    current = Path.cwd().resolve()
    worktree_root = worktree.resolve() if worktree is not None else None
    safe_entries = []
    for raw_entry in os.environ.get("PATH", "").split(os.pathsep):
        if not raw_entry:
            continue
        entry = Path(raw_entry)
        if not entry.is_absolute():
            continue
        try:
            resolved = entry.resolve()
            if resolved == current or (worktree_root and
                                       os.path.commonpath((str(worktree_root), str(resolved))) == str(worktree_root)):
                continue
        except (OSError, ValueError):
            continue
        safe_entries.append(str(resolved))
    return safe_entries


def _trusted_path_runner(executable: str, worktree: Path | None = None) -> str | None:
    safe_entries = _trusted_path_entries(worktree)
    candidates = [executable]
    if os.name == "nt" and not Path(executable).suffix:
        extensions = [item.casefold() for item in os.environ.get("PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";")]
        candidates = [executable + extension for extension in extensions]
    for entry in safe_entries:
        for name in candidates:
            candidate = Path(entry) / name
            if candidate.is_file() and (os.name == "nt" or os.access(candidate, os.X_OK)):
                return str(candidate.resolve())
    return None


def _trusted_npm_runner(executable: str, worktree: Path | None = None) -> str | None:
    return _trusted_path_runner(executable, worktree)


def _verification_worktree_snapshot(root: Path) -> tuple[str, str, bool]:
    branch = _git_bytes(root, "branch", "--show-current").decode("utf-8", "replace").strip()
    head = _git_bytes(root, "rev-parse", "HEAD").decode("ascii", "replace").strip()
    dirty = bool(_git_bytes(root, "status", "--porcelain=v1", "-z"))
    return branch, head, dirty


def _safe_frontend_test_path(value: str) -> bool:
    relative = PurePosixPath(value.replace("\\", "/"))
    return (not relative.is_absolute() and ".." not in relative.parts and bool(relative.parts)
            and relative.parts[0] == "tests"
            and relative.name.endswith((".test.ts", ".test.tsx", ".test.js", ".test.jsx"))
            and not any(re.search(r"[%!&|<>^()]", part) for part in relative.parts))


# Only page entry points with direct UI/API coverage are mapped narrowly.
_FRONTEND_PAGE_TESTS = {
    "frontend/src/pages/Materials/MaterialsPage.tsx": (
        "frontend/tests/masterData.test.tsx", "frontend/tests/masterDataApi.test.ts"),
    "frontend/src/pages/Documents/DocumentsPage.tsx": (
        "frontend/tests/sp806.test.tsx", "frontend/tests/sp806Api.test.ts",
        "frontend/tests/semanticOperationsPanel.test.tsx", "frontend/tests/semanticIndexApi.test.ts"),
    "frontend/src/pages/PurchaseOrders/PurchaseOrdersPage.tsx": (
        "frontend/tests/sp805.test.tsx", "frontend/tests/sp805Api.test.ts",
        "frontend/tests/purchaseOrderApi.test.ts"),
}


def _frontend_page_imports(root: Path, revision: str, path: str) -> tuple[str, ...] | None:
    """Return literal module references, or None when the page cannot be inspected."""
    try:
        source = subprocess.run(
            ["git", "-C", str(root), "show", f"{revision}:{path}"],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return None
    # Vite's glob imports resolve dependencies from a pattern rather than a
    # static module specifier, so a page using import.meta is not safely mapped.
    if re.search(r"\bimport\s*\.\s*meta\b", source):
        return None
    dynamic_calls = re.finditer(
        r"\b(?:import|require)\b(?:\s|/\*.*?\*/|//[^\r\n]*(?:\r?\n|$))*\(([^)]*)\)",
        source, re.DOTALL,
    )
    for call in dynamic_calls:
        if ("/*" in call.group(0) or "//" in call.group(0)
                or not re.fullmatch(r"(['\"])[^'\"\\]*\1", call.group(1).strip())):
            return None
    modules = re.findall(
        r"\bfrom\s*['\"]([^'\"]+)['\"]"
        r"|^\s*import\s*['\"]([^'\"]+)['\"]"
        r"|\bimport\s*\(\s*['\"]([^'\"]+)['\"]\s*\)"
        r"|\brequire\s*\(\s*['\"]([^'\"]+)['\"]\s*\)",
        source, re.MULTILINE,
    )
    return tuple(sorted({module for match in modules for module in match if module}))


def _frontend_gate_scripts(root: Path, revision: str) -> tuple[str, str, str] | None:
    """Read the test/lint/build scripts whose output is trusted by the Issue Gate."""
    try:
        source = subprocess.run(
            ["git", "-C", str(root), "show", f"{revision}:frontend/package.json"],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        ).stdout
        manifest = json.loads(source)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError):
        return None
    scripts = manifest.get("scripts") if isinstance(manifest, dict) else None
    if not isinstance(scripts, dict):
        return None
    required = tuple(scripts.get(name) for name in ("test", "lint", "build"))
    if any(not isinstance(value, str) or not value.strip() for value in required):
        return None
    return required  # type: ignore[return-value]


def _issue_gate_impact_plan(root: Path, base_sha: Any, head_sha: Any) -> dict[str, Any]:
    """Build a conservative fixed-command test plan from the committed change paths."""
    if not isinstance(base_sha, str) or not isinstance(head_sha, str) or not base_sha or not head_sha:
        raise PolicyError("Issue Gate impact analysis requires the verified base and current HEAD SHA.")
    try:
        changed = subprocess.run(
            ["git", "-C", str(root), "diff", "--name-only", "--no-renames", f"{base_sha}...{head_sha}"],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        ).stdout.splitlines()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PolicyError("Could not derive changed paths for the Issue Gate impact plan.") from exc
    paths = sorted(path.replace("\\", "/") for path in changed if path.strip())
    if not paths:
        raise PolicyError("Issue Gate cannot pass with an empty base-to-HEAD diff.")

    orchestrator_paths = {
        "scripts/supplypilot_orchestrator.py",
        "scripts/supplypilot_full_gate.py",
        "scripts/tests/test_supplypilot_orchestrator.py",
    }
    known_docs = {"AGENTS.md", "docs/v2/matt-pocock-codex-gpt-handoff.md"}
    backend_roots = ("backend/src/main/java/", "backend/src/test/java/",
                     "backend/src/main/resources/", "backend/pom.xml")
    frontend_roots = ("frontend/src/", "frontend/tests/", "frontend/package.json",
                      "frontend/vite.config.", "frontend/vitest.config.", "frontend/tsconfig")
    deployment_prefixes = ("compose", "docker-compose", "docker/", "dockerfile",
                           "backend/dockerfile", "frontend/dockerfile")
    backend_paths: list[str] = []
    frontend_paths: list[str] = []
    deployment_paths: list[str] = []
    docs_paths: list[str] = []
    affects_orchestrator = False
    cross_module = False
    unknown: list[str] = []
    for path in paths:
        lower = path.casefold()
        if path in orchestrator_paths:
            affects_orchestrator = True
        elif path in known_docs or path.startswith(".agents/skills/supplypilot-implement-spec/") \
                or re.fullmatch(r"docs/evidence/issue-162-[A-Za-z0-9_.-]+", path):
            docs_paths.append(path)
        elif path.startswith(backend_roots):
            backend_paths.append(path)
            if (path.startswith("backend/src/main/resources/")
                    or any(token in lower for token in ("/controller/", "/dto/", "/entity/", "/api/", "/security/",
                                                       "/auth/", "/migration/", "/schema/", "/transaction/",
                                                       "/inventory/", "/purchaseorder/", "/purchase-order/",
                                                       "/common/", "/exception/", "/sensitive/", "/outbound/"))
                    or re.search(r"(controller|request|response|dto|entity|schema|migration|security|auth|exception|error)\w*\.java$", lower)
                    or path.endswith("pom.xml")):
                cross_module = True
        elif path.startswith(frontend_roots):
            frontend_paths.append(path)
            frontend_parts = PurePosixPath(lower).parts
            filename = PurePosixPath(lower).name
            if (any(token in frontend_parts for token in ("api", "auth", "router", "routes", "types", "dto",
                                                          "contracts", "schemas"))
                    or re.search(r"(^|[._-])(api|dto|schema|contract)([._-]|$)", filename)
                    or re.search(r"api.?types?|types?\.(?:ts|tsx|js|jsx|d\.ts)$", filename)
                    or re.search(r"(api.?client|axios|auth|router|route|request|response)",
                                 filename)):
                cross_module = True
        elif path.casefold().startswith(deployment_prefixes):
            deployment_paths.append(path)
        else:
            unknown.append(path)
    if unknown:
        raise PolicyError(
            "Issue Gate impact is not mapped for changed paths; expand and verify the affected suite before proceeding: "
            + ", ".join(unknown)
        )

    if "frontend/package.json" in frontend_paths:
        base_scripts = _frontend_gate_scripts(root, base_sha)
        head_scripts = _frontend_gate_scripts(root, head_sha)
        if base_scripts is None or head_scripts is None or base_scripts != head_scripts:
            raise PolicyError(
                "Issue Gate BLOCKED: frontend/package.json test/lint/build scripts changed or could not be verified; "
                "the approved npm commands cannot be trusted for this diff."
            )

    commands: list[list[str]] = []
    behaviors: list[dict[str, Any]] = []
    not_applicable: dict[str, str] = {}
    if affects_orchestrator:
        command = [sys.executable, "-m", "unittest", "scripts.tests.test_supplypilot_orchestrator", "-v"]
        commands.append(command)
        behaviors.append({"change": [path for path in paths if path in orchestrator_paths],
                          "affected_behavior": "Orchestrator graph, gate execution/trust, state invalidation, resource and worktree contracts",
                          "required_tests": ["scripts.tests.test_supplypilot_orchestrator (complete module)"]})

    if backend_paths:
        classes: set[str] = set()
        integration_classes: set[str] = set()
        can_map_all = True
        backend_source_changes = [path for path in backend_paths
                                  if path.startswith("backend/src/main/java/") and path.endswith(".java")]
        for path in backend_paths:
            if path.startswith("backend/src/test/java/"):
                test_file = root / Path(*PurePosixPath(path).parts)
                if test_file.suffix == ".java":
                    classes.add(test_file.stem)
                else:
                    can_map_all = False
            elif path.startswith("backend/src/main/java/") and path.endswith(".java"):
                # A sibling test name cannot prove that indirect consumers,
                # transaction behavior, or shared contracts are covered.
                # The project has no reliable source-to-test dependency map,
                # so production changes run the complete Backend test suite.
                continue
            else:
                can_map_all = False
        if cross_module or backend_source_changes or not can_map_all or (not classes and not integration_classes):
            backend_command = ["mvnw.cmd", "test"]
            backend_tests = "complete Backend Maven test suite (conservative contract/security/dependency coverage)"
            backend_behavior = "Backend behavior and API/DTO/schema or shared dependency contracts"
            commands.append(backend_command)
        else:
            if classes and integration_classes:
                backend_command = ["mvnw.cmd", "verify", "-Dtest=" + ",".join(sorted(classes)),
                                   "-Dit.test=" + ",".join(sorted(integration_classes))]
            elif integration_classes:
                backend_command = ["mvnw.cmd", "verify", "-Dit.test=" + ",".join(sorted(integration_classes))]
            else:
                backend_command = ["mvnw.cmd", "test", "-Dtest=" + ",".join(sorted(classes))]
            commands.append(backend_command)
            backend_tests = "mapped Backend tests: " + ", ".join(sorted(classes | integration_classes))
            backend_behavior = "Backend implementation and matching Unit/Integration tests"
        behaviors.append({"change": backend_paths, "affected_behavior": backend_behavior,
                          "required_tests": [backend_tests]})

    if frontend_paths:
        selected_tests = {
            path for path in frontend_paths
            if path.startswith("frontend/tests/")
            and path.endswith((".test.ts", ".test.tsx", ".test.js", ".test.jsx"))
        }
        source_changes = [path for path in frontend_paths if path.startswith("frontend/src/")]
        for path in source_changes:
            selected_tests.update(_FRONTEND_PAGE_TESTS.get(path, ()))
        frontend_build_changes = [path for path in frontend_paths if path.startswith((
            "frontend/src/", "frontend/package.json", "frontend/vite.config.", "frontend/vitest.config.",
            "frontend/tsconfig"))]
        has_unmapped_source = any(path not in _FRONTEND_PAGE_TESTS for path in source_changes)
        for path in source_changes:
            if path in _FRONTEND_PAGE_TESTS:
                base_imports = _frontend_page_imports(root, base_sha, path)
                head_imports = _frontend_page_imports(root, head_sha, path)
                if base_imports is None or head_imports is None or base_imports != head_imports:
                    has_unmapped_source = True
        if selected_tests and not cross_module and not has_unmapped_source:
            ordered_tests = sorted(selected_tests)
            frontend_command = ["npm", "test", "--",
                                *[path.removeprefix("frontend/") for path in ordered_tests]]
            frontend_tests = "mapped Frontend tests: " + ", ".join(ordered_tests)
        else:
            frontend_command = ["npm", "test"]
            frontend_tests = "complete Frontend Vitest suite (unmapped, shared or cross-module dependency)"
        commands.append(frontend_command)
        if frontend_build_changes or cross_module:
            commands.extend([["npm", "run", "lint"], ["npm", "run", "build"]])
        behaviors.append({"change": frontend_paths,
                          "affected_behavior": "Frontend pages, components, API client and shared UI dependencies",
                          "required_tests": [frontend_tests] + (["Frontend lint", "Frontend production build"]
                                                                   if frontend_build_changes or cross_module else [])})

    if cross_module and not backend_paths:
        commands.append(["mvnw.cmd", "test"])
        behaviors.append({"change": frontend_paths,
                          "affected_behavior": "Shared Frontend contract, auth, router or global configuration with Backend compatibility risk",
                          "required_tests": ["complete Backend Maven test suite"]})
    if cross_module and not frontend_paths:
        commands.extend([["npm", "test"], ["npm", "run", "lint"], ["npm", "run", "build"]])
        behaviors.append({"change": backend_paths,
                          "affected_behavior": "Backend API/DTO/schema/security contract consumed by Frontend",
                          "required_tests": ["complete Frontend Vitest suite", "Frontend lint", "Frontend production build"]})

    if deployment_paths:
        raise PolicyError(
            "Issue Gate BLOCKED: deployment changes have no approved trusted Docker/Compose Issue Gate runner; "
            "Docker/Compose validation is RELEASE_PENDING for the Release Gate: "
            + ", ".join(deployment_paths)
        )
    if not commands:
        raise PolicyError("This change has no mapped executable Issue Gate suite; fail closed until its behavior is mapped.")

    if not frontend_paths and not cross_module:
        not_applicable["frontend_vitest"] = "Diff contains no Frontend source, tests, API client, or shared UI contract paths."
    if not backend_paths and not cross_module:
        not_applicable["backend_maven"] = "Diff contains no Backend implementation, test, schema, transaction, authorization, or API contract paths."
    release_pending: dict[str, str] = {}
    backend_runtime_paths = [path for path in backend_paths
                             if path.startswith(("backend/src/main/", "backend/pom.xml"))]
    frontend_runtime_paths = [path for path in frontend_paths if not path.startswith("frontend/tests/")]
    runtime_changes = bool(backend_runtime_paths or frontend_runtime_paths or deployment_paths)
    backend_configuration = [path for path in backend_paths
                             if path.startswith("backend/src/main/resources/")
                             and Path(path).suffix.casefold() in {".yml", ".yaml", ".properties"}]
    if backend_configuration:
        release_pending["backend_runtime_configuration"] = (
            "Backend runtime configuration requires Release Candidate profile and deployment validation: "
            + ", ".join(backend_configuration))
    if runtime_changes:
        release_pending["compose_docker"] = (
            "Application or deployment inputs changed; Docker/Compose image validation remains required at Release Gate.")
        release_pending["browser_rag_gemini_e2e"] = (
            "Application behavior may affect Browser, Documents/RAG, provider configuration or API boundaries; "
            "run the delivery-scope E2E at Release Gate.")
    else:
        not_applicable["compose_docker"] = (
            "Diff contains no application runtime, Dockerfile, Compose, image or deployment input.")
        not_applicable["browser_rag_gemini_e2e"] = (
            "Diff contains no application behavior, Browser flow, Documents/RAG, provider or API boundary change.")
    return {"base_sha": base_sha, "head_sha": head_sha, "changed_paths": paths,
            "behaviors": behaviors,
            "required_tests": [test for behavior in behaviors for test in behavior["required_tests"]],
            "required_commands": commands, "not_applicable": not_applicable,
            "release_pending": release_pending}


def _verification_output_contains_test_run(command: list[str], output: str,
                                          *, reject_skips: bool = False) -> bool:
    if reject_skips and re.search(r"\b(?:skipped\s*[=:]?\s*[1-9]\d*|[1-9]\d*\s+skipped|Skipped:\s*[1-9]\d*)\b",
                                  output, re.IGNORECASE):
        return False
    executable = Path(command[0].replace("\\", "/")).name.casefold()
    args = command[1:]
    if executable in {"python", "python.exe", "python3", "python3.exe", "py", "py.exe"}:
        if args[1] == "unittest":
            ran = re.search(r"Ran\s+([1-9]\d*)\s+tests?", output, re.IGNORECASE)
            skipped = re.search(r"skipped=(\d+)", output, re.IGNORECASE)
            return (bool(ran) and (not skipped or int(skipped.group(1)) < int(ran.group(1)))
                    and not _output_has_test_failures(output))
        return _output_has_executed_test_count(output)
    if executable in {"npm", "npm.cmd", "npm.exe"}:
        return _output_has_executed_test_count(output)
    if executable in {"mvn", "mvn.cmd", "mvnw", "mvnw.cmd"}:
        summaries = re.findall(
            r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)",
            output, re.IGNORECASE)
        return bool(summaries) and all(int(failures) == 0 and int(errors) == 0
                                       for _, failures, errors, _ in summaries) \
            and any(int(total) > int(skipped) for total, _, _, skipped in summaries)
    return False


def _verification_test_count(command: list[str], output: str) -> int | None:
    executable = Path(command[0].replace("\\", "/")).name.casefold()
    args = command[1:]
    if executable in {"python", "python.exe", "python3", "python3.exe", "py", "py.exe"}:
        match = re.search(r"Ran\s+([1-9]\d*)\s+tests?", output, re.IGNORECASE)
        return int(match.group(1)) if match else None
    if executable in {"npm", "npm.cmd", "npm.exe"} and args and args[0] == "test":
        # Vitest's Tests summary is the case count; Test Files is not.
        summaries = re.findall(r"^\s*Tests\s+(\d+)\s+passed\b",
                               output, re.IGNORECASE | re.MULTILINE)
        return int(summaries[-1]) if summaries else None
    if executable in {"mvn", "mvn.cmd", "mvnw", "mvnw.cmd"}:
        pattern = re.compile(
            r"Tests run:\s*(\d+),\s*Failures:\s*\d+,\s*Errors:\s*\d+,\s*Skipped:\s*\d+",
            re.IGNORECASE)
        summaries = []
        for match in pattern.finditer(output):
            line_start = output.rfind("\n", 0, match.start()) + 1
            line_end = output.find("\n", match.end())
            if line_end < 0:
                line_end = len(output)
            line = output[line_start:line_end]
            summaries.append((int(match.group(1)), line))
        detailed = [count for count, row in summaries if re.search(r"\s-- in\s+\S+", row)]
        if detailed:
            return sum(detailed)
        return summaries[0][0] if len(summaries) == 1 else None
    return None


def _output_has_executed_test_count(output: str) -> bool:
    return (any(int(count) > 0 for count in re.findall(
        r"\b(\d+)\s+(?:tests?\s+)?(?:passed|xfailed|xpassed)\b", output, re.IGNORECASE))
        and not _output_has_test_failures(output))


def _output_has_test_failures(output: str) -> bool:
    return bool(re.search(r"\b[1-9]\d*\s+(?:tests?\s+)?(?:failed|errors?)\b", output, re.IGNORECASE)
                or re.search(r"\b(?:failures|errors)=[1-9]\d*\b", output, re.IGNORECASE))


def _verify_gate_artifacts(record: dict[str, Any], evidence: dict[str, Any],
                           evidence_dir: Path, target: str,
                           trusted_manifest: dict[str, Any] | None = None) -> dict[str, Any]:
    """Run an approved test command and persist its actual output as verification evidence."""
    if evidence.get("sha") != record.get("head_sha"):
        raise PolicyError("Verification command must target the exact current HEAD.")
    worktree_value = record.get("worktree")
    if not isinstance(worktree_value, str):
        raise PolicyError("Verification requires the registered Issue worktree.")
    root = Path(worktree_value).resolve(strict=True)
    impact = _issue_gate_impact_plan(root, record.get("base_sha"), record.get("head_sha")) \
        if target == "ISSUE_GATE_VERIFIED" else None
    if target == "ISSUE_GATE_VERIFIED":
        if "command" in evidence or "commands" in evidence:
            raise PolicyError("Issue Gate commands are derived from the committed diff and cannot be caller-selected.")
        commands = impact["required_commands"]
        for planned_command in commands:
            if not _gate_command_supported(planned_command, target, root):
                raise PolicyError("Impact plan contains a command without an approved trusted runner.")
    else:
        command = evidence.get("command")
        if not _gate_command_supported(command, target, root):
            raise PolicyError("Verification requires an approved command and trusted runner for this gate; arbitrary or partial commands are rejected.")
        commands = [command]
    branch, actual_head, dirty = _verification_worktree_snapshot(root)
    if branch != record.get("branch") or actual_head != record.get("head_sha"):
        raise PolicyError("Verification worktree branch/HEAD does not match the registered Issue HEAD.")
    if dirty:
        raise PolicyError("Verification requires a clean worktree at the committed HEAD.")
    manifest_sha256 = None
    if target == "FULL_GATE_VERIFIED":
        manifest_path = root / "scripts" / "supplypilot_full_gate.py"
        if not _trusted_full_gate_manifest_valid(trusted_manifest, root, record.get("head_sha")):
            raise PolicyError("Full Gate manifest has no independent, human-confirmed trust baseline.")
        manifest_sha256 = trusted_manifest["sha256"].lower()
        prepared_command = [sys.executable, str(manifest_path)]
    started_at = dt.datetime.now(dt.timezone.utc).isoformat()
    command_results = []
    artifact_evidence = []
    failures = []
    for planned_command in commands:
        run_cwd = root if target == "FULL_GATE_VERIFIED" else _gate_working_directory(planned_command, root)
        if run_cwd is None:
            raise PolicyError("Targeted Gate worktree is not a repository root with the expected module layout.")
        prepared_command = (_prepare_gate_command(planned_command, root)
                            if target != "FULL_GATE_VERIFIED" else prepared_command)
        command_started = dt.datetime.now(dt.timezone.utc).isoformat()
        try:
            if target == "FULL_GATE_VERIFIED":
                result = _execute_full_gate(
                    root, int(record["number"]), shared_state_dir(), manifest_sha256,
                    trusted_manifest["runner_sha256"].lower())
            else:
                result = _execute_gate_command(prepared_command, root, int(record["number"]), shared_state_dir())
            exit_code, output = result.returncode, result.stdout
        except OSError as exc:
            exit_code, output = 127, f"Could not start approved command: {exc}"
        command_after = _verification_worktree_snapshot(root)
        is_test_command = (Path(planned_command[0].replace("\\", "/")).name.casefold()
                           in {"python", "python.exe", "python3", "python3.exe", "py", "py.exe",
                               "npm", "npm.cmd", "npm.exe", "mvn", "mvn.cmd", "mvnw", "mvnw.cmd"}
                           and not (Path(planned_command[0].replace("\\", "/")).name.casefold()
                                    in {"npm", "npm.cmd", "npm.exe"}
                                    and planned_command[1:2] == ["run"]))
        test_count = _verification_test_count(planned_command, output) if is_test_command else None
        passed = exit_code == 0 and (not is_test_command or
                                     (test_count is not None and test_count > 0
                                      and _verification_output_contains_test_run(
                                          planned_command, output, reject_skips=target == "ISSUE_GATE_VERIFIED")))
        command_result = {"command": planned_command, "executed_command": prepared_command,
                          "cwd": str(run_cwd), "sha": actual_head,
                          "started_at": command_started,
                          "ended_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                          "worktree_after": {"branch": command_after[0], "head_sha": command_after[1],
                                             "dirty": command_after[2]},
                          "status": "PASS" if passed else ("FAIL" if exit_code != 0 else "BLOCKED"),
                          "exit_code": exit_code, "test_count": test_count, "output": output}
        artifact = evidence_dir / f"issue-{record['number']}-{target.lower()}-{actual_head}-{uuid.uuid4().hex}.log"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text(json.dumps({"issue": record["number"], "gate": target,
                                        "branch": branch, **command_result}, ensure_ascii=False, indent=2),
                            encoding="utf-8")
        artifact_item = {"path": str(artifact.resolve()),
                         "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}
        command_result["artifact"] = artifact_item
        command_results.append(command_result)
        artifact_evidence.append(artifact_item)
        if not passed:
            failure = (f"failed with exit code {exit_code}" if exit_code != 0 else
                       f"{command_result['status']} with no verified executed test count")
            failures.append(f"{planned_command!r}: {failure}, tests={test_count}")
    after_branch, after_head, after_dirty = _verification_worktree_snapshot(root)
    if target == "FULL_GATE_VERIFIED":
        artifact = evidence_dir / f"issue-{record['number']}-{target.lower()}-{actual_head}-{uuid.uuid4().hex}.log"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_text(json.dumps({"issue": record["number"], "gate": target, "sha": actual_head,
                                        "branch": branch, "command": command,
                                        "executed_command": prepared_command, "started_at": started_at,
                                        "ended_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                                        "worktree_after": {"branch": after_branch, "head_sha": after_head,
                                                           "dirty": after_dirty},
                                        "manifest_sha256": manifest_sha256,
                                        "command_results": command_results}, ensure_ascii=False, indent=2),
                            encoding="utf-8")
        artifact_evidence = [{"path": str(artifact.resolve()),
                              "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}]
    if after_branch != branch or after_head != actual_head or after_dirty:
        raise PolicyError("Verification worktree changed during execution; refusing to record PASS; evidence="
                          + ", ".join(item["path"] for item in artifact_evidence) + ".")
    if failures:
        raise PolicyError("Required verification command(s) did not pass; evidence="
                          + ", ".join(item["path"] for item in artifact_evidence)
                          + "; " + "; ".join(failures))
    if target == "TARGETED_VERIFIED" and not _verification_output_contains_test_run(command, output):
        raise PolicyError(f"Verification command reported success without a nonzero test count; evidence={artifact_evidence}.")
    verified = {"status": "PASS", "sha": actual_head,
                "command": command if target != "ISSUE_GATE_VERIFIED" else None,
                "exit_code": 0, "artifacts": artifact_evidence}
    if impact is not None:
        impact["actual_results"] = command_results
        impact["actual_result"] = {"status": "PASS", "exit_code": 0,
                                   "artifacts": artifact_evidence}
        verified["impact"] = impact
    return verified


def _execute_full_gate(root: Path, issue: int, state_dir: Path,
                       expected_manifest_sha256: str | None = None,
                       expected_runner_sha256: str | None = None) -> subprocess.CompletedProcess[str]:
    manifest = root / "scripts" / "supplypilot_full_gate.py"
    runner = root / "scripts" / "supplypilot_orchestrator.py"
    if (not isinstance(expected_manifest_sha256, str)
            or not re.fullmatch(r"[0-9a-fA-F]{64}", expected_manifest_sha256)
            or not isinstance(expected_runner_sha256, str)
            or not re.fullmatch(r"[0-9a-fA-F]{64}", expected_runner_sha256)
            or not manifest.is_file()
            or not runner.is_file()
            or hashlib.sha256(manifest.read_bytes()).hexdigest() != expected_manifest_sha256.lower()
            or hashlib.sha256(runner.read_bytes()).hexdigest() != expected_runner_sha256.lower()):
        raise PolicyError("Full Gate manifest or runner differs from its independently reviewed trust baseline.")
    command = [sys.executable, str(root / "scripts" / "supplypilot_full_gate.py")]
    with _resource_lock("docker", issue, command, state_dir):
        result = subprocess.run(command, cwd=root, check=False, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        actual_manifest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        actual_runner = hashlib.sha256(runner.read_bytes()).hexdigest()
        if actual_manifest != expected_manifest_sha256.lower():
            return subprocess.CompletedProcess(
                command, 125, result.stdout + "\nFull Gate manifest changed during execution.\n")
        if actual_runner != expected_runner_sha256.lower():
            return subprocess.CompletedProcess(
                command, 125, result.stdout + "\nFull Gate runner changed during execution.\n")
        return result


def _run_gh_json(command: list[str]) -> dict[str, Any]:
    try:
        result = subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                encoding="utf-8", errors="replace")
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PolicyError(f"GitHub CLI verification failed for {shlex.join(command)}.") from exc
    try:
        value = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise PolicyError("GitHub CLI returned invalid JSON; refusing state update.") from exc
    if not isinstance(value, dict):
        raise PolicyError("GitHub CLI returned an unexpected response; refusing state update.")
    return value


def _verify_github_merge(record: dict[str, Any]) -> dict[str, Any]:
    issue = record.get("number")
    pr_number = record.get("pr", {}).get("number") if isinstance(record.get("pr"), dict) else None
    if not isinstance(issue, int) or not isinstance(pr_number, int):
        raise PolicyError("A recorded Issue and PR number are required for GitHub merge verification.")
    repo = _repository_slug()
    pr = _run_gh_json(["gh", "pr", "view", str(pr_number), "--repo", repo,
                       "--json", "state,mergedAt,mergeCommit,baseRefName,headRefName,headRefOid,body"])
    merge_commit = pr.get("mergeCommit")
    merge_sha = merge_commit.get("oid") if isinstance(merge_commit, dict) else None
    expected_close = f"Closes #{issue}"
    body = pr.get("body")
    if (pr.get("state") != "CLOSED" or not pr.get("mergedAt") or pr.get("baseRefName") != "main"
            or pr.get("headRefName") != record.get("branch")
            or pr.get("headRefOid") != record.get("head_sha")
            or not isinstance(body, str) or not any(line.strip() == expected_close for line in body.splitlines())
            or not isinstance(merge_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", merge_sha)):
        raise PolicyError("GitHub does not confirm the recorded PR merged into main for this Issue and exact HEAD.")
    gh_issue = _run_gh_json(["gh", "issue", "view", str(issue), "--repo", repo, "--json", "state"])
    if gh_issue.get("state") != "CLOSED":
        raise PolicyError("GitHub Issue is not closed; refusing MERGED state.")
    main_sha = _verified_remote_main_sha()
    return {"verified_github": True, "pr": pr_number, "issue": issue,
            "merge_commit": merge_sha, "main_sha": main_sha,
            "pr_url": f"https://github.com/{repo}/pull/{pr_number}"}


def _verify_github_pr(record: dict[str, Any], evidence: dict[str, Any], issue: int) -> dict[str, Any]:
    pr_number = evidence.get("number")
    url = evidence.get("url")
    description = evidence.get("description")
    expected_close = f"Closes #{issue}"
    repo = _repository_slug()
    expected_url = f"https://github.com/{repo}/pull/{pr_number}"
    if (not isinstance(pr_number, int) or pr_number <= 0
            or url not in {expected_url, expected_url + "/"}
            or not isinstance(description, str)
            or not any(line.strip() == expected_close for line in description.splitlines())):
        raise PolicyError(f"PR_OPEN requires a GitHub URL and standalone {expected_close} line.")
    pr = _run_gh_json(["gh", "pr", "view", str(pr_number), "--repo", repo,
                       "--json", "state,mergedAt,baseRefName,headRefName,headRefOid,body,url"])
    body = pr.get("body")
    if (pr.get("state") != "OPEN" or pr.get("mergedAt")
            or pr.get("baseRefName") != "main"
            or pr.get("headRefName") != record.get("branch")
            or pr.get("headRefOid") != record.get("head_sha")
            or not isinstance(body, str)
            or not any(line.strip() == expected_close for line in body.splitlines())
            or pr.get("url") not in {expected_url, expected_url + "/"}):
        raise PolicyError("GitHub does not confirm an open PR for this repository, main base, Issue, and exact HEAD.")
    return {"verified_github_pr": True, "number": pr_number, "url": expected_url,
            "base": "main", "head_sha": record.get("head_sha"), "github_state": "OPEN"}


def _apply_main_sync(state: dict[str, Any], number: int, evidence: dict[str, Any]) -> None:
    old_sha, new_sha = state.get("main_sha"), evidence["main_sha"]
    if old_sha == new_sha:
        return
    if not re.fullmatch(r"[0-9a-fA-F]{40}", new_sha):
        raise PolicyError("Only a verified full Git commit SHA can become main_sha.")
    worktree = next((record.get("worktree") for record in state.get("issues", {}).values()
                     if isinstance(record, dict) and isinstance(record.get("worktree"), str)), None)
    contract_surface_changed = _main_diff_changes_contract_surface(old_sha, new_sha, worktree)
    affected: set[int] = {number}
    changed = True
    while changed:
        changed = False
        for key, record in state.get("issues", {}).items():
            if int(key) not in affected and any(dep in affected for dep in record.get("dependencies", [])):
                affected.add(int(key))
                changed = True
    for key, record in state.get("issues", {}).items():
        issue_number = int(key)
        if (issue_number != number and record.get("implementation_state") not in TERMINAL
                and record.get("base_sha") != new_sha):
            affected.add(issue_number)
    for affected_number in affected - {number}:
        record = state["issues"][str(affected_number)]
        verification = record.get("verification", {})
        review = record.get("gpt_review", {})
        issue_gate = record.get("issue_gate")
        if isinstance(verification, dict) and verification.get("status") not in {None, "NOT_RUN", "STALE"}:
            verification["status"] = "STALE"
            verification["stale_reason"] = f"dependency #{number} merged; main advanced to {new_sha}"
        if isinstance(issue_gate, dict):
            issue_gate["status"] = "STALE"
            issue_gate["stale_reason"] = f"dependency #{number} merged; main advanced to {new_sha}"
        if isinstance(review, dict) and review.get("status") not in {None, "NOT_REVIEWED", "STALE"}:
            review["status"] = "STALE"
            review["stale_reason"] = f"dependency #{number} merged; main advanced to {new_sha}"
        if contract_surface_changed:
            record["contract_verified"] = False
        if record.get("implementation_state") in {
                "ISSUE_GATE_VERIFIED", "FULL_GATE_VERIFIED", "WAITING_GPT_REVIEW", "GPT_FINAL_PASS"
        }:
            record["implementation_state"] = "CHANGES_REQUIRED"
    state["main_sha"] = new_sha
    event = {"type": "MAIN_SYNCED", "issue": number, "old_main_sha": old_sha,
             "new_main_sha": new_sha, "merge_commit": evidence["merge_commit"],
             "contract_surface_changed": contract_surface_changed,
             "affected_issues": sorted(affected - {number}),
             "at": dt.datetime.now(dt.timezone.utc).isoformat()}
    state.setdefault("events", []).append(event)


def state_sync_main(path: Path, number: int) -> None:
    with state_lock(path):
        state = read_json(path)
        record = state.get("issues", {}).get(str(number))
        if not record or record.get("implementation_state") != "MERGED":
            raise PolicyError("Only a recorded MERGED Issue can synchronize main.")
        evidence = _verify_github_merge(record)
        if evidence["merge_commit"] != record.get("merge", {}).get("merge_commit"):
            raise PolicyError("Verified GitHub merge commit differs from the recorded Issue merge.")
        if state.get("main_sha") == evidence["main_sha"]:
            raise PolicyError("State already records the verified current main SHA.")
        _apply_main_sync(state, number, evidence)
        atomic_write_json(path, state)


def state_set_head(path: Path, number: int, sha: str, local_sha: str, remote_sha: str) -> None:
    with state_lock(path):
        _state_set_head(path, number, sha, local_sha, remote_sha)


def _state_set_head(path: Path, number: int, sha: str, local_sha: str, remote_sha: str) -> None:
    state = read_json(path)
    record = state.get("issues", {}).get(str(number))
    if not record:
        raise PolicyError(f"Issue #{number} has no registered state record.")
    reservation = record.get("gate_reservation")
    if isinstance(reservation, dict) and reservation.get("status") == "RUNNING":
        raise PolicyError("Cannot update HEAD while this Issue has an active Gate reservation.")
    current = record.get("implementation_state")
    if current in {"PR_OPEN", "MERGED"}:
        raise PolicyError("Resolve the existing PR state explicitly before recording a new head.")
    if current != "IMPLEMENTING":
        raise PolicyError("Record a new HEAD only while the authorized Issue is IMPLEMENTING.")
    verified = _verify_local_remote_feature_head(record)
    if not sha or not (sha == local_sha == remote_sha == verified["local_sha"] == verified["remote_sha"]):
        raise PolicyError("Head update requires actual local HEAD and origin branch SHA to match.")
    old_sha = record.get("head_sha")
    if old_sha == sha:
        raise PolicyError("Head SHA is unchanged.")
    record["head_sha"] = sha
    record["verification"] = {"status": "NOT_RUN", "sha": None, "evidence": []}
    record["issue_gate"] = None
    record["gpt_review"] = {"status": "NOT_REVIEWED", "reviewed_sha": None,
                            "invalidated_reviewed_sha": record.get("gpt_review", {}).get("reviewed_sha")}
    record["trusted_full_gate_manifest"] = None
    record["implementation_state"] = "IMPLEMENTING"
    event = {"issue": number, "type": "HEAD_UPDATED", "old_sha": old_sha, "new_sha": sha,
             "from": current, "to": "IMPLEMENTING",
             "at": dt.datetime.now(dt.timezone.utc).isoformat(),
             "evidence": {"local_sha": local_sha, "remote_sha": remote_sha}}
    record.setdefault("events", []).append(event)
    state.setdefault("events", []).append(event)
    atomic_write_json(path, state)


def state_gate_recover(path: Path, number: int, token: str,
                       owner_stopped: bool, reason: str) -> None:
    if owner_stopped is not True or not reason.strip():
        raise PolicyError("Gate recovery requires explicit owner-stopped confirmation and a reason.")
    with state_lock(path):
        state = read_json(path)
        record = state.get("issues", {}).get(str(number))
        reservation = record.get("gate_reservation") if isinstance(record, dict) else None
        if not isinstance(reservation, dict) or reservation.get("status") != "RUNNING":
            raise PolicyError(f"Issue #{number} has no interrupted Gate reservation to recover.")
        if reservation.get("token") != token:
            raise PolicyError("Gate recovery token does not match the active reservation.")
        reservation.update({"status": "ABORTED", "ended_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                            "recovery_reason": reason.strip(), "owner_stopped_confirmed": True})
        event = {"issue": number, "type": "GATE_ABORTED", "gate_token": token,
                 "at": reservation["ended_at"],
                 "evidence": {"reason": reason.strip(), "owner_stopped_confirmed": True}}
        record.setdefault("events", []).append(event)
        state.setdefault("events", []).append(event)
        atomic_write_json(path, state)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("validate", "plan"):
        cmd = sub.add_parser(name)
        cmd.add_argument("graph", help="path to a reviewed JSON graph export")
    gate = sub.add_parser("pr-gate")
    gate.add_argument("state", help="path to persistent orchestrator state JSON")
    gate.add_argument("--issue", type=int, required=True)
    gate.add_argument("--head", required=True)
    st = sub.add_parser("state")
    state_sub = st.add_subparsers(dest="state_command", required=True)
    init = state_sub.add_parser("init")
    init.add_argument("path")
    init.add_argument("--main-sha", required=True)
    register = state_sub.add_parser("register")
    register.add_argument("path")
    register.add_argument("record")
    transition = state_sub.add_parser("transition")
    transition.add_argument("path")
    transition.add_argument("--issue", type=int, required=True)
    transition.add_argument("--to", required=True, choices=sorted(STATE_VALUES))
    transition.add_argument("--evidence", required=True, help="JSON evidence file verified by the orchestrator")
    set_head = state_sub.add_parser("set-head")
    set_head.add_argument("path")
    set_head.add_argument("--issue", type=int, required=True)
    set_head.add_argument("--sha", required=True)
    set_head.add_argument("--local-sha", required=True)
    set_head.add_argument("--remote-sha", required=True)
    state_list = state_sub.add_parser("list")
    state_list.add_argument("path")
    sync_main = state_sub.add_parser("sync-main")
    sync_main.add_argument("path")
    sync_main.add_argument("--issue", type=int, required=True)
    adopt = state_sub.add_parser("adopt")
    adopt.add_argument("path")
    adopt.add_argument("record")
    adopt.add_argument("--authorized-issue", type=int, required=True)
    resume = state_sub.add_parser("resume")
    resume.add_argument("path")
    resume.add_argument("--issue", type=int, required=True)
    resume.add_argument("--authorized-issue", type=int, required=True)
    resume.add_argument("--fingerprint", required=True)
    resume.add_argument("--code-gate-pass", action="store_true", required=True)
    recover_gate = state_sub.add_parser("gate-recover")
    recover_gate.add_argument("path")
    recover_gate.add_argument("--issue", type=int, required=True)
    recover_gate.add_argument("--token", required=True)
    recover_gate.add_argument("--owner-stopped", action="store_true", required=True)
    recover_gate.add_argument("--reason", required=True)
    review_attest = state_sub.add_parser("review-attest")
    review_attest.add_argument("path")
    review_attest.add_argument("--issue", type=int, required=True)
    review_attest.add_argument("--review-status", required=True, choices=["FINAL_PASS"])
    review_attest.add_argument("--reviewed-sha", required=True)
    review_attest.add_argument("--conversation-url", required=True)
    review_attest.add_argument("--evidence-file", required=True)
    review_attest.add_argument("--confirmed-by", required=True)
    trust_gate = state_sub.add_parser("trust-full-gate")
    trust_gate.add_argument("path")
    trust_gate.add_argument("--issue", type=int, required=True)
    trust_gate.add_argument("--reviewed-sha", required=True)
    trust_gate.add_argument("--conversation-url", required=True)
    trust_gate.add_argument("--evidence-file", required=True)
    trust_gate.add_argument("--confirmed-by", required=True)
    res = sub.add_parser("resource")
    res_sub = res.add_subparsers(dest="resource_command", required=True)
    status = res_sub.add_parser("status")
    status.add_argument("--state-dir", help="shared state directory; defaults to the common Git directory")
    run = res_sub.add_parser("run")
    run.add_argument("kind", choices=RESOURCES)
    run.add_argument("--issue", type=int, required=True)
    run.add_argument("--state-dir", help="shared state directory; defaults to the common Git directory")
    run.add_argument("argv", nargs=argparse.REMAINDER,
                     help="command after --, held under the exclusive resource lock")
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            issues = validate_graph(read_json(args.graph))
            print(json.dumps({"valid": True, "issues": sorted(issues)}, indent=2))
        elif args.command == "plan":
            print(json.dumps(plan_graph(read_json(args.graph)), indent=2))
        elif args.command == "pr-gate":
            ok, message = can_open_pr(Path(args.state), args.issue, args.head)
            print(json.dumps({"allowed": ok, "message": message}, indent=2))
            return 0 if ok else 2
        elif args.command == "state" and args.state_command == "init":
            state_init(Path(args.path), args.main_sha)
        elif args.command == "state" and args.state_command == "register":
            state_register(Path(args.path), read_json(args.record))
        elif args.command == "state" and args.state_command == "transition":
            state_transition(Path(args.path), args.issue, args.to, read_json(args.evidence))
        elif args.command == "state" and args.state_command == "set-head":
            state_set_head(Path(args.path), args.issue, args.sha, args.local_sha, args.remote_sha)
        elif args.command == "state" and args.state_command == "list":
            print(json.dumps(read_json(args.path), ensure_ascii=False, indent=2))
        elif args.command == "state" and args.state_command == "sync-main":
            state_sync_main(Path(args.path), args.issue)
        elif args.command == "state" and args.state_command == "adopt":
            state_adopt(Path(args.path), read_json(args.record), args.authorized_issue)
        elif args.command == "state" and args.state_command == "resume":
            state_resume(Path(args.path), args.issue, args.authorized_issue, args.fingerprint, args.code_gate_pass)
        elif args.command == "state" and args.state_command == "gate-recover":
            state_gate_recover(Path(args.path), args.issue, args.token, args.owner_stopped, args.reason)
        elif args.command == "state" and args.state_command == "review-attest":
            state_attest_gpt_review(Path(args.path), args.issue, args.review_status, args.reviewed_sha,
                                    args.conversation_url, Path(args.evidence_file), args.confirmed_by)
        elif args.command == "state" and args.state_command == "trust-full-gate":
            state_trust_full_gate_manifest(Path(args.path), args.issue, args.reviewed_sha,
                                           args.conversation_url, Path(args.evidence_file), args.confirmed_by)
        elif args.command == "resource" and args.resource_command == "status":
            state_dir = _resource_state_dir(args.state_dir)
            print(json.dumps(resource_status(state_dir), indent=2))
        elif args.command == "resource" and args.resource_command == "run":
            command = args.argv[1:] if args.argv and args.argv[0] == "--" else args.argv
            if not command:
                raise PolicyError("Provide a command after --.")
            state_dir = _resource_state_dir(args.state_dir)
            return run_with_resource(args.kind, args.issue, command, state_dir)
    except (PolicyError, OSError, json.JSONDecodeError) as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
