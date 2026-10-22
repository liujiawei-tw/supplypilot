#!/usr/bin/env python3
"""Conservative SupplyPilot Issue DAG and shared test-resource coordinator."""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import json
import os
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Iterator

RESOURCES = ("vitest", "maven", "docker")
ACTIVE_STATES = {"READY_TO_CODE", "IMPLEMENTING", "TARGETED_VERIFIED", "WAITING_DEPENDENCIES",
                 "FULL_GATE_PENDING", "FULL_GATE_VERIFIED", "WAITING_GPT_REVIEW", "CHANGES_REQUIRED",
                 "GPT_FINAL_PASS", "PR_OPEN"}
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


def _paths_overlap(left: str, right: str) -> bool:
    def normalize(value: str) -> str:
        return value.replace("\\", "/").removeprefix("./").rstrip("/")

    a, b = normalize(left), normalize(right)
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
        if not isinstance(owned, list) or any(not isinstance(p, str) or not p.strip() for p in owned):
            raise PolicyError(f"Issue #{number}: owned_files must be a string array.")
        if code_ready:
            candidates.append(number)
        release_ready = not started and not unmet and row.get("base_sha") == main_sha and contract_current
        result[number] = {
            "code_ready": code_ready,
            "release_ready": release_ready,
            "provisional": provisional,
            "unmet_dependencies": unmet,
            "reasons": reason,
            "owned_files": owned,
            "base_current": row.get("base_sha") == main_sha,
        }

    owners: dict[str, int] = {}
    for number in candidates:
        for path in result[number]["owned_files"]:
            conflict = next(((owned_path, owner) for owned_path, owner in owners.items()
                             if _paths_overlap(path, owned_path)), None)
            if conflict:
                owned_path, other = conflict
                for target in (number, other):
                    reason = f"file_owned_by_issue_{other if target == number else number}:{owned_path}"
                    if reason not in result[target]["reasons"]:
                        result[target]["code_ready"] = False
                        result[target]["reasons"].append(reason)
            else:
                owners[path] = number
    ready = sorted(n for n in candidates if result[n]["code_ready"])
    return {"main_sha": main_sha, "issues": {str(n): result[n] for n in sorted(result)},
            "ready_frontier": ready,
            "release_frontier": sorted(n for n, item in result.items() if item["release_ready"])}


def can_open_pr(record: dict[str, Any], current_head: str) -> tuple[bool, str]:
    review = record.get("gpt_review", {})
    if not isinstance(review, dict) or review.get("status") != "FINAL_PASS":
        return False, "GPT FINAL PASS is required."
    if review.get("reviewed_sha") != current_head:
        return False, "GPT review SHA does not match current HEAD."
    if record.get("head_sha") != current_head:
        return False, "Recorded HEAD does not match current HEAD."
    return True, "PR gate satisfied for exact HEAD."


def _lock_dir(kind: str, state_dir: Path) -> Path:
    if kind not in RESOURCES:
        raise PolicyError(f"Unknown resource {kind!r}; expected one of {', '.join(RESOURCES)}.")
    folder = state_dir / "locks"
    folder.mkdir(parents=True, exist_ok=True)
    return folder / f"{kind}.lock"


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
    for kind in RESOURCES:
        lock_dir = _lock_dir(kind, state_dir)
        exists = lock_dir.exists()
        owner = _read_owner(lock_dir) if exists else None
        alive = _process_alive(owner) if exists else False
        stale = _is_stale_lock(exists, owner, alive)
        result[kind] = {"busy": exists, "owner": owner, "stale_record": stale,
                        "owner_process_alive": alive}
    return result


def run_with_resource(kind: str, issue: int, argv: list[str], state_dir: Path) -> int:
    lock_dir = _lock_dir(kind, state_dir)
    try:
        # Directory creation is atomic across Windows processes and survives a crash as a
        # diagnosable stale record. It is intentionally never auto-reclaimed.
        lock_dir.mkdir()
    except FileExistsError:
        owner = _read_owner(lock_dir)
        raise PolicyError(f"{kind} resource is busy; owner={json.dumps(owner, ensure_ascii=False)}")
    owner = {
        "issue": issue, "pid": os.getpid(), "host": socket.gethostname(),
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
    try:
        return subprocess.run(argv, check=False).returncode
    finally:
        current = _read_owner(lock_dir)
        if current and current.get("token") == owner["token"]:
            owner_path.unlink(missing_ok=True)
            try:
                lock_dir.rmdir()
            except OSError:
                # Preserve unexpected contents for manual inspection.
                pass


STATE_VALUES = {
    "BLOCKED_DEPENDENCY", "READY_TO_CODE", "IMPLEMENTING", "TARGETED_VERIFIED",
    "WAITING_DEPENDENCIES", "FULL_GATE_PENDING", "FULL_GATE_VERIFIED",
    "WAITING_GPT_REVIEW", "CHANGES_REQUIRED", "GPT_FINAL_PASS", "PR_OPEN",
    "MERGED", "BLOCKED_ENVIRONMENT", "FAILED",
}
TRANSITIONS = {
    "BLOCKED_DEPENDENCY": {"READY_TO_CODE"},
    "READY_TO_CODE": {"IMPLEMENTING"},
    "IMPLEMENTING": {"TARGETED_VERIFIED", "WAITING_DEPENDENCIES"},
    "TARGETED_VERIFIED": {"IMPLEMENTING", "WAITING_DEPENDENCIES", "FULL_GATE_PENDING"},
    "WAITING_DEPENDENCIES": {"FULL_GATE_PENDING"},
    "FULL_GATE_PENDING": {"FULL_GATE_VERIFIED"},
    "FULL_GATE_VERIFIED": {"WAITING_GPT_REVIEW"},
    "WAITING_GPT_REVIEW": {"CHANGES_REQUIRED", "GPT_FINAL_PASS"},
    "CHANGES_REQUIRED": {"IMPLEMENTING"},
    "GPT_FINAL_PASS": {"PR_OPEN"},
    "PR_OPEN": {"MERGED"},
    "BLOCKED_ENVIRONMENT": {"READY_TO_CODE", "IMPLEMENTING", "FULL_GATE_PENDING"},
    "FAILED": set(),
    "MERGED": set(),
}
for _state in STATE_VALUES - {"MERGED", "FAILED"}:
    TRANSITIONS[_state] = TRANSITIONS.get(_state, set()) | {"BLOCKED_ENVIRONMENT", "FAILED"}


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def state_init(path: Path, main_sha: str) -> None:
    if path.exists():
        raise PolicyError(f"State file already exists; refusing to overwrite: {path}")
    if not main_sha.strip():
        raise PolicyError("main_sha is required.")
    atomic_write_json(path, {
        "schema_version": 1, "main_sha": main_sha,
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "issues": {}, "events": [],
    })


def state_register(path: Path, record: dict[str, Any]) -> None:
    number = record.get("number")
    if not isinstance(number, int):
        raise PolicyError("Issue record needs integer number.")
    required = ("dependencies", "base_sha", "branch", "worktree", "contract_version", "owned_files")
    if not all(key in record for key in required):
        raise PolicyError("Issue record is missing one or more required graph/worktree fields.")
    state = read_json(path)
    key = str(number)
    if key in state.get("issues", {}):
        raise PolicyError(f"Issue #{number} already has a state record; refusing duplicate assignment.")
    expected_branch_prefix = f"codex/issue-{number}-"
    if not isinstance(record["branch"], str) or not record["branch"].startswith(expected_branch_prefix):
        raise PolicyError(f"Issue #{number} must use its dedicated {expected_branch_prefix}<slug> branch.")
    if not isinstance(record["worktree"], str) or not Path(record["worktree"]).is_absolute():
        raise PolicyError("Issue worktree must be an absolute path.")
    if record["base_sha"] != state.get("main_sha"):
        raise PolicyError("Issue base_sha must match the current main_sha at registration.")
    for existing in state.get("issues", {}).values():
        if existing.get("branch") == record["branch"] or existing.get("worktree") == record["worktree"]:
            raise PolicyError("Branch or worktree is already assigned to another Issue.")
    normalized = {
        **record,
        "implementation_state": "BLOCKED_DEPENDENCY",
        "verification": {"status": "NOT_RUN", "sha": None, "evidence": []},
        "gpt_review": {"status": "NOT_REVIEWED", "reviewed_sha": None},
        "pr": {"status": "NONE", "number": None, "head_sha": None},
        "merge": {"status": "UNMERGED", "merge_commit": None},
        "events": [],
    }
    state.setdefault("issues", {})[key] = normalized
    event = {"issue": number, "from": None, "to": "BLOCKED_DEPENDENCY",
             "at": dt.datetime.now(dt.timezone.utc).isoformat(), "evidence": {"registered": True}}
    state.setdefault("events", []).append(event)
    atomic_write_json(path, state)


def _transition_record(record: dict[str, Any], target: str,
                       evidence: dict[str, Any], main_sha: str) -> None:
    current = record.get("implementation_state")
    if target not in STATE_VALUES:
        raise PolicyError(f"Unknown state {target}.")
    if target not in TRANSITIONS.get(current, set()):
        raise PolicyError(f"Illegal state transition: {current} -> {target}.")
    number = record["number"]
    head = record.get("head_sha")
    if target == "READY_TO_CODE":
        if evidence.get("code_gate") != "PASS" or evidence.get("authorized_issue") != number:
            raise PolicyError("READY_TO_CODE requires Code Gate PASS for this user-authorized Issue.")
        if (evidence.get("branch_verified") is not True or evidence.get("worktree_verified") is not True
                or evidence.get("branch") != record.get("branch")
                or evidence.get("worktree") != record.get("worktree")
                or evidence.get("worktree_clean") is not True
                or evidence.get("worktree_head") != record.get("base_sha")):
            raise PolicyError("READY_TO_CODE requires independently verified branch and worktree.")
    elif target in {"TARGETED_VERIFIED", "FULL_GATE_VERIFIED"}:
        if evidence.get("status") != "PASS" or not head or evidence.get("sha") != head:
            raise PolicyError(f"{target} requires PASS evidence for the exact current head_sha.")
        record["verification"] = {"status": target, "sha": head,
                                  "evidence": evidence.get("artifacts", [])}
    elif target == "FULL_GATE_PENDING":
        if evidence.get("dependencies_merged") is not True:
            raise PolicyError("FULL_GATE_PENDING requires verified merged/closed dependencies.")
        if (evidence.get("current_main_sha") != main_sha or evidence.get("contract_verified") is not True
                or evidence.get("contract_version") != record.get("contract_version")
                or evidence.get("verified_contract_version") != record.get("contract_version")):
            raise PolicyError("FULL_GATE_PENDING requires current main and verified API/schema contracts.")
        record["base_sha"] = main_sha
    elif target == "WAITING_GPT_REVIEW":
        if not head or not (evidence.get("local_sha") == evidence.get("remote_sha") == head):
            raise PolicyError("GPT handoff requires local, remote, and current HEAD SHA equality.")
        if evidence.get("internal_review") != "PASS":
            raise PolicyError("GPT handoff requires a passed internal preflight.")
        if record.get("verification", {}).get("status") != "FULL_GATE_VERIFIED" or record.get("verification", {}).get("sha") != head:
            raise PolicyError("GPT handoff requires the full delivery gate for the current HEAD.")
    elif target in {"CHANGES_REQUIRED", "GPT_FINAL_PASS"}:
        required_review = "CHANGES_REQUIRED" if target == "CHANGES_REQUIRED" else "FINAL_PASS"
        if evidence.get("review_status") != required_review or not head or evidence.get("reviewed_sha") != head:
            raise PolicyError(f"{target} requires GPT evidence for the exact current HEAD.")
        record["gpt_review"] = {"status": required_review, "reviewed_sha": head,
                                "evidence": evidence.get("url")}
    elif target == "PR_OPEN":
        description = evidence.get("description", "")
        expected = f"Closes #{number}"
        if record.get("gpt_review", {}).get("status") != "FINAL_PASS" or record.get("gpt_review", {}).get("reviewed_sha") != head:
            raise PolicyError("PR_OPEN requires GPT FINAL PASS for the current HEAD.")
        if evidence.get("base") != "main" or evidence.get("head_sha") != head:
            raise PolicyError("PR_OPEN requires base main and PR head SHA equal to reviewed HEAD.")
        if not evidence.get("url") or not any(line.strip() == expected for line in description.splitlines()):
            raise PolicyError(f"PR_OPEN requires a verified URL and standalone {expected} line.")
        record["pr"] = {"status": "OPEN", "number": evidence.get("number"),
                        "head_sha": head, "url": evidence["url"]}
    elif target == "MERGED":
        if evidence.get("pr_merged") is not True or evidence.get("issue_closed") is not True:
            raise PolicyError("MERGED requires GitHub-confirmed PR merge and Issue closure.")
        if not evidence.get("merge_commit") or not evidence.get("main_sha"):
            raise PolicyError("MERGED requires the merge commit and current main SHA.")
        record["merge"] = {"status": "MERGED", "merge_commit": evidence["merge_commit"],
                           "main_sha": evidence["main_sha"]}
    record["implementation_state"] = target


def state_transition(path: Path, number: int, target: str, evidence: dict[str, Any]) -> None:
    state = read_json(path)
    record = state.get("issues", {}).get(str(number))
    if not record:
        raise PolicyError(f"Issue #{number} has no registered state record.")
    previous = record["implementation_state"]
    _transition_record(record, target, evidence, state["main_sha"])
    event = {"issue": number, "from": previous, "to": target,
             "at": dt.datetime.now(dt.timezone.utc).isoformat(), "evidence": evidence}
    record.setdefault("events", []).append(event)
    state.setdefault("events", []).append(event)
    atomic_write_json(path, state)


def state_set_head(path: Path, number: int, sha: str, local_sha: str, remote_sha: str) -> None:
    if not sha or not (sha == local_sha == remote_sha):
        raise PolicyError("Head update requires local SHA, remote SHA, and requested SHA to match.")
    state = read_json(path)
    record = state.get("issues", {}).get(str(number))
    if not record:
        raise PolicyError(f"Issue #{number} has no registered state record.")
    current = record.get("implementation_state")
    if current in {"PR_OPEN", "MERGED"}:
        raise PolicyError("Resolve the existing PR state explicitly before recording a new head.")
    if current != "IMPLEMENTING":
        raise PolicyError("Record a new HEAD only while the authorized Issue is IMPLEMENTING.")
    old_sha = record.get("head_sha")
    if old_sha == sha:
        raise PolicyError("Head SHA is unchanged.")
    record["head_sha"] = sha
    record["verification"] = {"status": "NOT_RUN", "sha": None, "evidence": []}
    record["gpt_review"] = {"status": "NOT_REVIEWED", "reviewed_sha": None,
                            "invalidated_reviewed_sha": record.get("gpt_review", {}).get("reviewed_sha")}
    record["implementation_state"] = "IMPLEMENTING"
    event = {"issue": number, "type": "HEAD_UPDATED", "old_sha": old_sha, "new_sha": sha,
             "from": current, "to": "IMPLEMENTING",
             "at": dt.datetime.now(dt.timezone.utc).isoformat(),
             "evidence": {"local_sha": local_sha, "remote_sha": remote_sha}}
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
    gate.add_argument("record", help="path to Issue release state JSON")
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
            ok, message = can_open_pr(read_json(args.record), args.head)
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
        elif args.command == "resource" and args.resource_command == "status":
            state_dir = Path(args.state_dir) if args.state_dir else shared_state_dir()
            print(json.dumps(resource_status(state_dir), indent=2))
        elif args.command == "resource" and args.resource_command == "run":
            command = args.argv[1:] if args.argv and args.argv[0] == "--" else args.argv
            if not command:
                raise PolicyError("Provide a command after --.")
            state_dir = Path(args.state_dir) if args.state_dir else shared_state_dir()
            return run_with_resource(args.kind, args.issue, command, state_dir)
    except (PolicyError, OSError, json.JSONDecodeError) as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
