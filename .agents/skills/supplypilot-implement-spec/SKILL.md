---
name: supplypilot-implement-spec
description: Optional legacy multi-Issue Orchestrator diagnostic and planning skill; not a required approval gate for the ordinary SupplyPilot GPT/Codex development workflow.
---

# SupplyPilot Implement-Spec Orchestrator（選用工具）

> **2026-10-24 使用者決策：** 一般 Issue 不再強制使用此 Orchestrator 的 Code Gate、Issue Gate、Release Gate、State、Contract 或 Reservation 才能交付。最新規範見根目錄 `AGENTS.md` 和 `docs/v2/matt-pocock-codex-gpt-handoff.md`。本 Skill 與其 `references/` 仍保存舊工具使用時的安全要求，但不應自行阻擋一般 PR。

## 一般交付（不需啟動 Orchestrator）

1. GPT 核對 GitHub Repo、Issue、最新 main 與依賴，給 Codex Prompt。
2. Codex 在獨立 Branch／Worktree 實作，跑開發期受影響測試、內部 Review，Commit／Push。
3. GPT 直接讀取已 Push 精確 HEAD 的 Diff，獨立 Review Spec／Standards／Security／Transaction／Tests。
4. Codex 在 GPT Code Review 無阻擋 Finding 後執行受影響且必要的 CI 等價驗證，提供原始測試證據；若程式／測試再修改則 GPT 審查新 HEAD。
5. GPT 確認必要 CI PASS，建立繁體中文 PR，描述獨立一行 `Closes #<IssueNumber>`。
6. 使用者人工 Merge；以 GitHub 驗證 PR merged、main 及 Issue closed。

GitHub Actions 目前 `PAUSED BY USER / NOT USED AS GATE`。不隱藏必要 CI 失敗、不偽造 CI／Gemini／Browser／AWS PASS、不繞過 GitHub Branch Protection；高風險改動必須擴大安全、交易、併發與跨模組測試。

## 如使用者明確要求舊 Orchestrator 診斷

- 唯讀查詢最新 GitHub Issue／PR／merge、實際 Repo、Branch、HEAD、worktree、依賴與檔案衝突；不能拿 caller 自述的 Gate PASS 或 SHA 取代外部證據。
- 只有在另外獲得明確授權後，才可依原有 `references/state-machine.md`、`references/testing-policy.md`、`references/worktree-policy.md`、`references/dependency-policy.md` 進行持久 State 或 Gate 操作；舊 Runner 的 Fail-Closed、鎖定與證據信任條件不得繞過。
- `scripts/supplypilot_orchestrator.py` 仍有舊式強制 Gate 的執行邏輯，**文件改動不代表 Runner 已變更**。不得對正式 Shared State 擅自 Apply／解鎖、修補 Contract flag、製造 Reservation 或 Gate PASS。
- #168 的 Reconciliation 限定最多兩輪修復嘗試；第二輪仍無法穩定運作時停止擴張，按使用者方向另外審查如何移除剩餘強制入口，而不是犧牲必要測試品質。

## 不可自動做的事

不建立 Integration Branch、不自動 Merge、不自行關閉 Issue、不更改 GitHub Workflow／Branch Protection、不清理他人 WIP、不 Force Push、不擅自部署 AWS。需要新 Issue／政策或修改現行流程，須有使用者授權。

歷史 Orchestrator 測試與 Gate／Release Gate 實驗結果應如實保留，不回填為新 PR 的 CI PASS。
