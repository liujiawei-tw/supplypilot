# GPT Review Handoff 模板

    ## GPT Review Handoff
    - Repository: liujiawei-tw/supplypilot
    - Issue: #<number> <URL>
    - Base branch/SHA: main / <SHA>
    - Feature branch: codex/issue-<number>-<slug>
    - Head SHA: <full SHA>
    - Scope and acceptance criteria: <links and concise checklist>
    - Changed files: <paths>
    - Dependencies: <Issue/PR state, source SHA and contract versions>
    - Internal review: <result and evidence; not GPT review>
    - Targeted tests: <commands, exact results and log paths>
    - Full delivery gate: <PASS/FAIL/BLOCKED/NOT RUN per gate, versions, logs>
    - Known risks / deferred checks: <items>
    - Verification state applies to SHA: <full SHA>
    - GitHub Actions: <actual status or NOT RUN>

推送後再核對 local HEAD == remote feature branch head。交給 GPT 後狀態為 WAITING_GPT_REVIEW。GPT 必須直接從 GitHub 讀取準確 Head 與 diff。未執行測試不能寫成 PASS；GPT 沒有親自執行的測試須註記為 implementer evidence。

GPT 回覆需保存完整 UTF-8 證據檔並保留同一 Conversation URL。`CHANGES REQUIRED` 必須回到同一 Feature Branch 修正、精準驗證、Commit/Push 並重新審查。只有 GPT 對確切 SHA 明確 `FINAL PASS`，且人類 reviewer 在互動 TTY 使用 `state review-attest` 核對 URL、Reviewed SHA 和證據檔內容/SHA-256 後，Orchestrator 才能登記 `GPT_FINAL_PASS`。一般 `state transition --evidence` 不接受此狀態。

若需執行 Full Gate，先由獨立 GPT Review 審查 `scripts/supplypilot_full_gate.py`，並在證據原文輸出 `FULL_GATE_MANIFEST_REVIEW: PASS`，明確表示該 manifest 的信任風險已解決。若整體 Review 為 `CHANGES REQUIRED`，未明確確認此項已解決時不得建立 baseline。之後由人類 reviewer 使用 `state trust-full-gate` 記錄 Review URL、審查 SHA、證據檔和 manifest SHA-256。Orchestrator 會在 Gate 前後核對 digest；只有經再次獨立審查與人工互動確認才可更新 trust baseline。
