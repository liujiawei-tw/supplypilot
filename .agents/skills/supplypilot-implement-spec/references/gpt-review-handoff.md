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
    - Issue Gate impact matrix: <changed files → affected behavior/dependencies → required checks → results>
    - Issue Gate: <PASS/FAIL/BLOCKED, exact commands, test counts, exit codes and log paths>
    - NOT_APPLICABLE: <checks and evidence-based reasons>
    - Release Gate: <PASS/FAIL/BLOCKED/NOT RUN; never infer from Issue Gate>
    - Known risks / deferred checks: <items>
    - Verification state applies to SHA: <full SHA>
    - GitHub Actions: <actual status or NOT RUN>

推送後再核對 local HEAD == remote feature branch head。只有新的 `ISSUE_GATE_VERIFIED` 對確切 SHA 有效、內部預檢通過且 local/remote HEAD 相等，才可交 GPT 並進入 `WAITING_GPT_REVIEW`。Issue Gate 只涵蓋該 Issue 的影響範圍；不代表 Release Candidate 的完整 Release Gate 通過。GPT 必須直接從 GitHub 讀取準確 Head 與 diff，審查 impact matrix、必要測試、N/A 理由及失敗／未執行項目。未執行測試不能寫成 PASS；GPT 沒有親自執行的測試須註記為 implementer evidence。

GPT 回覆需保存完整 UTF-8 證據檔並保留同一 Conversation URL。`CHANGES REQUIRED` 必須回到同一 Feature Branch 修正、精準驗證、Commit/Push 並重新審查。只有 GPT 對確切 SHA 明確 `FINAL PASS`，且人類 reviewer 在互動 TTY 使用 `state review-attest` 核對 URL、唯一且一致的非引用／非 code fence `Reviewed SHA: <40 位 SHA>` 行、最後有效 verdict 和證據檔 SHA-256 後，Orchestrator 才能登記 `GPT_FINAL_PASS`。一般 `state transition --evidence` 不接受此狀態。

Release Candidate 執行 Full Gate 前，先由獨立 GPT Review 審查確切 SHA 的 `scripts/supplypilot_full_gate.py`，並在非引用／非 code fence 的證據正文輸出獨立行 `FULL_GATE_MANIFEST_REVIEW: PASS`。此 manifest 子項 verdict 與整體 Issue review 分開：整體為 `CHANGES REQUIRED`／`BLOCKED` 時不可宣稱 Final Pass；manifest 子項通過後，人類 reviewer 可使用 `state trust-full-gate` 登記 Review URL、審查 SHA、證據檔及 manifest SHA-256。Orchestrator 會在 Gate 前後核對 manifest 與 runner digest；只有經獨立審查與互動確認才可建立或更新 trust baseline。Release Gate 結果只適用於其確切 Release Candidate SHA，不能回填成 Issue Gate 證據。
