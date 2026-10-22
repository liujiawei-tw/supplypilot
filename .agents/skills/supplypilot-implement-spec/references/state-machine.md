# 狀態機與狀態證據

狀態必須反映外部可核對事件。由單一 Orchestrator writer 維護共享 JSON state，建議放在 `git rev-parse --git-common-dir` 下的 `supplypilot-orchestrator/orchestrator.json`，避免不同 worktree 產生彼此不一致的副本。需保留每 Issue 的 dependencies、base/main SHA、branch、worktree、API/Schema contract version、file ownership、implementation、verification、GPT review、PR 與 merge 欄位。Issue markdown、agent 自述不能替代 GitHub PR/Issue 狀態。

| 狀態 | 意義與推進證據 |
| --- | --- |
| BLOCKED_DEPENDENCY | 有未完成的必要依賴；除經使用者授權的 provisional code gate 外不派工 |
| READY_TO_CODE | 已通過 Code Gate，Worktree/branch/owner 已核對 |
| IMPLEMENTING | 唯一 implementer 正在其 Issue Worktree 中工作 |
| TARGETED_VERIFIED | 受影響精準測試通過，記錄 SHA 與 log |
| WAITING_DEPENDENCIES | 實作完成但必要上游未 merge；不得進 Release Gate |
| FULL_GATE_PENDING | 必要依賴 merged，已整合當前 main，等待完整交付 gate |
| FULL_GATE_VERIFIED | 完整 Gate 對確切 SHA 通過並附證據 |
| WAITING_GPT_REVIEW | Commit/Push SHA 相等；handoff 完整 |
| CHANGES_REQUIRED | GPT 對此 SHA 要求修正；回到 IMPLEMENTING，新 commit |
| GPT_FINAL_PASS | GPT 明確對目前 Head SHA Final PASS |
| PR_OPEN | GPT PASS 後 PR 已建並核對描述/Base/Head |
| MERGED | GitHub 證明 PR merged、merge commit/main 和 Issue closed |
| BLOCKED_ENVIRONMENT | 明確記錄缺少的環境、權限或資源 |
| FAILED | 可重現的未恢復失敗 |

對應 transition 需更新證據欄位並保留事件歷史。重新啟動時先 reload state + GitHub 真實狀態，驗證 worktree owner，不能因狀態檔存在就重派工作。每個 test/review record 都綁定 SHA；HEAD 改變時使相關驗證與 review 標為 stale。
