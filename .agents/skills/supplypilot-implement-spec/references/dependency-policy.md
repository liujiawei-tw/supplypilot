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

The planner exposes code-ready and release-ready separately. `release_ready` requires an unstarted Issue, all dependencies merged and closed, a base at the current `main_sha`, and current verified contracts. A code-ready result never implies release-ready.

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

## Current SupplyPilot dry-run graph

Read GitHub Issues #142, #143, #145, #146 and #147 live. Expected edges: #143 depends on #142 (and Issue criteria); #145 is independent of those; #146 depends on #145; #147 depends on #146. Issue #142 also lists #138/#139/#140/#141/#160. Do not classify an Issue as formally releasable while its dependency PR is unmerged/Issue open.
