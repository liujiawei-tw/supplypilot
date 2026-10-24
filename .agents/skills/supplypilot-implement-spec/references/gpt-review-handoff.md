# Codex → GPT Review 與 CI 交接模板

> 一般 SupplyPilot 交付依 `AGENTS.md` 的 2026-10-24 簡化流程。此模板不要求 Orchestrator Issue Gate、Contract flag、Reservation 或 State Handoff Frontier。

## A. Codex 完成實作並 Push → GPT Code Review

- Repository：`liujiawei-tw/supplypilot`
- Issue：`#<number> <URL>`
- Base：`main / <40-char SHA>`
- Feature Branch：`<branch>`
- 遠端 HEAD：`<40-char SHA>`
- 需求／Acceptance Criteria：`<links>`
- 完整 `main...HEAD` Diff／實際修改檔案：`<paths>`
- 必要 GitHub 依賴／PR 是否已 Merge：`<references>`
- Codex 開發期測試與內部預檢：`<commands, results>`（**不是**正式交付 CI）
- Known risks／未執行項目：`<items>`

GPT 以 GitHub 上的精確 HEAD 獨立 Code Review，回報 P0/P1/P2 與 `CODE_REVIEW_PASS` 或 `CHANGES_REQUIRED`。阻擋問題交回 Codex 在同 Branch 修正、Push 新 SHA，再 Review。

## B. GPT Code Review PASS → Codex 必要 CI

- Reviewed SHA：`<40-char SHA>`
- 受影響行為／風險 → 必要測試與 Build／Lint：`<mapping and reasons>`
- 每項命令、工作目錄、Java／Node／Docker 等版本、Tests／Failures／Errors／Skipped、Exit Code：`<results>`
- 原始 logs／報告路徑與 SHA-256：`<evidence>`
- 未執行、環境 BLOCKED、Release Pending 項目：`<explicit status and reason>`
- Actions：`PAUSED BY USER / NOT USED AS GATE`（當前決策）

只需執行實際改動與風險所要求的 CI；必要測試失敗不得 PASS，opt-in Provider Smoke 不可偽裝成功，真實 Gemini／Browser／AWS 不得以 Mock 代替。Codex 執行的測試不可寫成 GPT 親自執行。

## C. GPT 建立 PR → 使用者 Merge

GPT 驗證 Code Review 對**目前 HEAD**有效、必要 CI 也綁定同一 SHA 且通過，核發正式交付 PASS 後建立繁體中文 PR，目標 `main`，描述單獨一行：

`Closes #<IssueNumber>`

回讀核對 PR Base、Head、SHA、描述。使用者人工 Merge，GPT 核對 PR merged、main SHA、Issue closed；不得自行 Merge。

## 舊 Orchestrator 補充

舊 `ISSUE_GATE_VERIFIED`／`FULL_GATE_VERIFIED`／`state review-attest` 只是當用戶明確選用舊工具時才適用的額外內部流程，**不作一般 Code Review、CI 或 PR 前置條件**。工具仍存在且執行時不可以偽造 PASS；使用者要求移除其強制入口時應在另案實作與審查。
