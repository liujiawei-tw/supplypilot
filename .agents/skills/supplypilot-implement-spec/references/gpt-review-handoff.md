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

若需執行 Full Gate，先由獨立 GPT Review 審查確切 SHA 的 `scripts/supplypilot_full_gate.py`，並在非引用／非 code fence 的證據正文輸出獨立行 `FULL_GATE_MANIFEST_REVIEW: PASS`，明確表示 manifest 子項信任風險已解決。這個子項 verdict 與整體 verdict 分開：整體為 `CHANGES REQUIRED`／`BLOCKED` 時不可宣稱 Final Pass，但只要 manifest 子項明確通過，便可先由人類 reviewer 使用 `state trust-full-gate` 記錄 Review URL、審查 SHA、manifest 識別行、證據檔及 manifest SHA-256。Orchestrator 會在 Gate 前後核對 manifest 與 runner digest；只有經獨立審查與人工互動確認才可建立或更新 trust baseline。完整 Gate 通過後仍須對確切 Head 完成整體 GPT Review。
