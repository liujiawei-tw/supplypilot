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
