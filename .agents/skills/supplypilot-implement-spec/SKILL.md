---
name: supplypilot-implement-spec
description: Orchestrate authorized SupplyPilot GitHub Issues across a dependency graph using isolated branches and worktrees, bounded agent concurrency, and independent per-Issue release gates. Use when the user explicitly asks to plan or run multiple SupplyPilot Issues in parallel.
---

# SupplyPilot Implement-Spec Orchestrator v1.1

Coordinate multiple explicitly authorized SupplyPilot Issues. This skill is a project-specific scheduler; it does not grant scope, GitHub permissions, or merge authority.

This Skill and its proposed AGENTS.md changes are tracked by Issue #162. Until they are merged to `main` and current `AGENTS.md` authorizes this workflow, use it only for read-only planning and dry runs; the current main-branch serial Issue rule remains binding.

## Before dispatch

1. Read repository AGENTS.md, docs/v2/README.md, docs/v2/migration-and-issue-map.md, this Issue's acceptance criteria and dependencies, and the relevant docs/v2/ spec. Read current API, DB, security, transaction, tests, and docs/agents/ contracts before implementation.
2. Query GitHub for the current Issue/PR/merge states and current main SHA. Use the connected GitHub tools or an authenticated gh; do not infer status from markdown or an agent report.
3. Limit work to the Issue set the user authorized. Build a dependency graph from explicit Issue blockers and confirmed contracts; run scripts/supplypilot_orchestrator.py validate and plan on a reviewed JSON export. Stop on missing dependencies, cycles, conflicting ownership, stale contracts, or unclear scope.
4. Apply the Code Gate before assigning implementation. A Provisional Contract may permit coding only if the user authorized it and its source Issue, branch, commit/worktree state, contract text/version, and verification status are recorded. It never satisfies the Issue Gate or Release Gate.
5. Check each Issue's branch, worktree, status, index, ownership and base SHA. Use the Codex managed Worktree controls where available; create or reuse a worktree only after verifying its exact Issue and branch. If this Codex client cannot create/inspect an isolated worktree, do not dispatch a writing agent. Never touch another Issue's WIP.
6. Reserve conflicting files to one Issue owner. Active Issues and explicit held owners keep ownership until they reach `MERGED`/`FAILED` or release it; normalize paths case-insensitively with Windows separators and treat directory/child paths as overlapping. Unknown ownership blocks dispatch.
7. For an existing WIP worktree, use `state adopt` to record branch, HEAD, index, tracked diffs, untracked files and a content fingerprint without changing that worktree. Adoption leaves verification `NOT_RUN` and does not resume work. Before resuming, verify the same fingerprint, worktree identity, base, dependencies, current Issue Code Gate and explicit user authorization with `state resume --code-gate-pass`; a changed fingerprint requires a fresh adoption, preserved fingerprint history and user confirmation.
8. Keep implementer concurrency at or below 3 by default; lower it for file/API overlap. Delegate bounded exploration, implementation, and internal review to subagents only when supported. Subagents inherit parent sandbox and approval settings.

## Run

- Read references/dependency-policy.md, worktree-policy.md, testing-policy.md, gpt-review-handoff.md, merge-policy.md, and state-machine.md as the active phase requires.
- Give each implementer only its Issue, exact worktree path, base SHA, acceptance criteria, required contract pointers, owned files, test budget, and stop conditions.
- Use spec-driven implementation, TDD for behavior changes, and diagnosing-bugs for reproduced failures. Do not install or run upstream setup scripts.
- After a dependency merges, re-fetch GitHub state and recompute the ready frontier. Do not integrate unmerged Issue branches into one another.
- Use the shared `high-load` resource lock for full Vitest, Maven Verify, Docker/Testcontainers, Docker image builds and other confirmed high-load validation. These types are mutually exclusive across linked worktrees. If unavailable or held, report BLOCKED/DEFERRED rather than interfering with another agent.
- Record evidence and state transitions with the Issue and exact SHA. Internal review is only a preflight.

## Issue Gate and Release Gate

Each Issue runs an impact-based `ISSUE_GATE_VERIFIED` before GPT review. The Orchestrator reads the committed base-to-HEAD diff, maps changed paths to affected behavior and required tests, records evidence-based `NOT_APPLICABLE` reasons, and fails closed for paths or dependencies it cannot map reliably. It executes every required test itself; a caller-supplied PASS, exit code, log, test list, or exclusion cannot satisfy the gate. `TARGETED_VERIFIED` remains development evidence and is never sufficient for GPT handoff.

Planner outputs Code Ready, Issue Gate Eligible, Issue Gate Verified, Release Gate Verified, GPT Handoff Ready and GPT Final PASS separately. `gpt_handoff_frontier` and PR authorization require a current Issue Gate for the exact HEAD, required dependencies/contracts, internal review, and local/remote HEAD equality. A legacy `FULL_GATE_VERIFIED` record does not create a new Issue Gate result.

`scripts/supplypilot_full_gate.py` remains the complete Release Gate for an integrated Release Candidate. It retains the trusted manifest/runner SHA checks and all full validation stages; an Issue Gate does not satisfy it. Release Gate PASS is never inferred from a targeted test, Issue Gate, or combining evidence from separate Issues.

After a GitHub-confirmed merge, run `state sync-main <state.json> --issue <number>`. The tool reads PR merge/base/commit and Issue closure through GitHub CLI and fetches current `origin/main`; caller-supplied SHA/evidence alone is insufficient. It records the new main SHA and event, marks dependent old Gate/review evidence stale, preserves their evidence, and leaves feature worktrees and `base_sha` unchanged pending explicit re-evaluation. Contract verification is also invalidated when the main diff touches backend/frontend source, API/DTO/Schema/migration/contract paths, or data formats; an unreadable diff is treated as contract change.

Commit and push only that Issue's feature branch when authorized by AGENTS.md. Produce the handoff template in references/gpt-review-handoff.md, then wait for GPT's independent review of that exact SHA. A changed head or dependency invalidates the previous review evidence.

Only GPT's FINAL PASS for the current SHA authorizes PR creation under current AGENTS.md. Each PR targets main, uses zh-TW, and contains a standalone Closes #<number> line. Only the user merges. After the user says it merged, verify the PR merge commit, current main, and Issue closure through GitHub before advancing dependent work.

## Never

Do not create an integration branch, merge Issue branches together, merge main, force-push, close Issues, create draft PRs, bypass branch protection, delete branches/worktrees, clean other agents' files, change CI gates, deploy AWS, or expand V2 scope. Do not call GPT review complete, Final PASS, PR open, or merged without the corresponding external evidence and authorization.

Use the reference files for exact gate and data schemas. If this branch's proposed AGENTS.md changes have not merged, treat them as a proposal; the current default branch remains authoritative.
