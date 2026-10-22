# 依賴圖與 Gate 政策

## Issue graph input

協調工具接受 JSON 匯出，不自行呼叫 GitHub。這可讓 agent 使用當前可用的 GitHub connector 或 gh 讀取，再保留來源和擷取時間。請勿手動填入未核實狀態。

Required root shape:
    {
      "main_sha": "<current main commit>",
      "issues": [{
        "number": 142, "dependencies": [138, 139, 140, 141, 160],
        "state": "open", "merged": false, "base_sha": "<commit>",
        "branch": "codex/issue-142-example", "worktree": "<absolute path>",
        "contract_version": "kanban-stage-v1",
        "owned_files": ["backend/src/..."], "provisional_contract": null
      }]
    }

Dependencies are hard prerequisites from Issue bodies/confirmed decisions. Missing nodes and cycles are errors. merged must reflect verified PR/Issue state, not an implementer's claim.

## Code Gate

An Issue is code-ready when all dependencies are verified merged and closed. Alternatively, a user-authorized provisional contract may allow implementation with each unmet dependency, but only with source issue, branch, commit/worktree state, contract/version, and evidence state recorded. A provisional implementation stays provisional and cannot satisfy downstream release gates.

The planner reports five distinct frontiers:

- `ready_frontier` is **Code Ready**: the Issue has not started, dependencies are merged/closed or a user-authorized provisional contract permits coding, and no active file owner overlaps.
- `full_gate_eligible_frontier` is **Eligible for Full Gate**: the implementation has SHA-matched targeted evidence; all dependencies are merged/closed; `base_sha` matches current `main_sha`; and the API/schema contract is verified.
- `full_gate_verified_frontier` is **Full Gate Verified**: the state is `FULL_GATE_VERIFIED` or later, full evidence matches the current `head_sha`, and dependencies, base, and contract remain current.
- `gpt_handoff_frontier` is **Ready for GPT Handoff**: the full gate remains valid, internal review passed, and local/remote/recorded HEADs match.
- `release_frontier` is **GPT Final PASS**: all prior gates remain valid and GPT's `FINAL_PASS` is for the exact current `head_sha`.

`NOT_STARTED`, `IMPLEMENTING`, targeted evidence, or contract correctness alone never means Release Ready. A changed HEAD, dependency merge, main SHA, or contract invalidates applicable downstream evidence and requires re-evaluation.

The planner treats `READY_TO_CODE`, `IMPLEMENTING`, verification/review states, and explicit `ownership_state` values `OWNED`/`HELD` as file owners. Active rows must include `owned_files`; missing ownership is a fail-closed error. Paths are normalized for Windows separators and case, and parent/child paths overlap. Ownership is available again only after the prior row reaches `MERGED`/`FAILED` or explicitly releases it.

Run the reviewed snapshot with:

```powershell
python scripts/supplypilot_orchestrator.py validate <reviewed-graph.json>
python scripts/supplypilot_orchestrator.py plan <reviewed-graph.json>
```

`ready_frontier` is only the graph Code Gate. Before writing work, separately verify user authorization, branch/worktree identity and cleanliness, repository state, and resource availability. The planner never creates a branch/worktree or dispatches an agent.

Reject:
- missing dependency references, cycles, duplicate Issue numbers, and unreviewed dependency assumptions;
- overlapping owned_files between concurrent Issues unless the orchestrator assigns a single owner before dispatch;
- stale base SHA or changed contract version/hash since the last verification;
- ready states inferred only from Issue number order.

After a dependency merges, re-read GitHub and update the graph. Re-evaluate descendants' source SHA, API/schema contract, tests, and base. Any changed contract invalidates dependent verification.

Persistent state uses `state sync-main <state.json> --issue <number>` after the Issue has a recorded `MERGED` state. The tool verifies the PR's merged state/base/merge commit and Issue closure through `gh`, obtains `origin/main` with `git ls-remote`, records the new SHA/event, and marks dependent verification/reviews stale while retaining their previous evidence. It never rewrites a feature branch or its `base_sha`. `state init` also requires a full SHA equal to verified `origin/main`; a supplied SHA alone is not proof.

## Current SupplyPilot dry-run graph

Read GitHub Issues #142, #143, #145, #146 and #147 live. Expected edges: #143 depends on #142 (and Issue criteria); #145 is independent of those; #146 depends on #145; #147 depends on #146. Issue #142 also lists #138/#139/#140/#141/#160. Do not classify an Issue as formally releasable while its dependency PR is unmerged/Issue open.
