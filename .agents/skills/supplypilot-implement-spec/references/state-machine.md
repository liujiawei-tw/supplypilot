# 狀態機與狀態證據

狀態必須反映外部可核對事件。由單一 Orchestrator writer 維護共享 JSON state，建議放在 `git rev-parse --git-common-dir` 下的 `supplypilot-orchestrator/orchestrator.json`，避免不同 worktree 產生彼此不一致的副本。狀態讀改寫使用原子目錄鎖；既有鎖視為不確定狀態並 fail closed，不自動回收。需保留每 Issue 的 dependencies、base/main SHA、branch、worktree、API/Schema contract version、file ownership、implementation、verification、GPT review、PR 與 merge 欄位。Issue markdown、agent 自述不能替代 GitHub PR/Issue 狀態。

| 狀態 | 意義與推進證據 |
| --- | --- |
| BLOCKED_DEPENDENCY | 有未完成的必要依賴；除經使用者授權的 provisional code gate 外不派工 |
| READY_TO_CODE | 已通過 Code Gate，Worktree/branch/owner 已核對；尚未代表開始編碼 |
| IMPLEMENTING | 唯一 implementer 正在其 Issue Worktree 中工作 |
| TARGETED_VERIFIED | 受影響精準測試通過，記錄 SHA 與 log |
| WAITING_DEPENDENCIES | 實作完成但必要上游未 merge；不得進 Release Gate |
| FULL_GATE_PENDING | 必要依賴 merged，已整合當前 main，等待完整交付 gate |
| FULL_GATE_VERIFIED | 完整 Gate 對確切 SHA 通過並附證據 |
| WAITING_GPT_REVIEW | Commit/Push SHA 相等；handoff 完整 |
| CHANGES_REQUIRED | GPT 對此 SHA 要求修正；回到 IMPLEMENTING，新 commit |
| GPT_FINAL_PASS | GPT 明確對目前 Head SHA Final PASS |
| PR_OPEN | GPT PASS 後 PR 已建並核對描述/Base/Head |
| MERGED | GitHub CLI 證明 PR 已合併至 main、Issue 已關閉，並由 `git ls-remote` 核實目前 main SHA |
| BLOCKED_ENVIRONMENT | 明確記錄缺少的環境、權限或資源 |
| FAILED | 可重現的未恢復失敗 |

對應 transition 需更新證據欄位並保留事件歷史。重新啟動時先 reload state + GitHub 真實狀態，驗證 worktree owner，不能因狀態檔存在就重派工作。每個 test/review record 都綁定 SHA；HEAD 改變時使相關驗證與 review 標為 stale。

`state init` 只接受與即時 `origin/main` 相同的完整 commit SHA。進入 `FULL_GATE_PENDING` 時，協調器唯讀核對 feature worktree 的 branch、HEAD、clean 狀態和目前 `origin/main` 祖先關係；呼叫端 JSON 不能代替整合證據。測試驗證由 Orchestrator 在登記 worktree 實際執行支援的 test-runner argv；它忽略呼叫端 PASS/退出碼/雜湊聲稱，並自行保存輸出與 SHA-256。Full Gate 要求 clean worktree 與精確 HEAD；Planner 也會核對證據檔案仍存在且雜湊相符。`state set-head` 與 GPT handoff 都查核本機 branch/HEAD 及 `origin` feature ref，而非只比較傳入字串。進入 `MERGED` 會核對 GitHub PR repo/base/branch/head/merge commit/`Closes #Issue`、Issue closure 及即時 main SHA，記錄歷史事件；相依 Issue 的舊驗證、GPT Review 與契約核驗標記 `STALE`，保留原 SHA 與 evidence，不替下游 Worktree rebase 或更新 `base_sha`。需要重驗依賴並重新整合 main 後，才可推進完整 Gate。

既有工作成果使用 `state adopt` 記錄 branch、HEAD、index、tracked diff、untracked files 與內容指紋。採用後仍是 `BLOCKED_DEPENDENCY`、驗證保持 `NOT_RUN`。只有使用者明確授權、目前 Issue Code Gate 通過、再次核對同一 branch/worktree、WIP 指紋不變、即時 `origin/main` 仍等於 state base、feature HEAD 包含此 main，且所有依賴已核實 MERGED/closed 時，`state resume --code-gate-pass` 才可一般恢復。使用者可明確採納每項尚未合併依賴的 provisional contract 來源；紀錄需保存來源 Issue、branch、commit SHA 或 WIP 指紋、契約版本與內容雜湊、verification state、授權與未滿足依賴。上游來源、branch、SHA、WIP 指紋、contract version/content 或 verification state 變更時，拒絕恢復／Gate，須重新採用並重新授權。Provisional resume 只允許在 Issue Worktree 編碼；它不會改寫 WIP、不會宣稱 Targeted/Full Gate 通過，也不允許進入 Full Gate、GPT release 或 PR 狀態，直到依賴實際合併且契約完成核驗。HEAD 相同不代表工作樹內容相同；任何指紋變更都須重新採用、清楚保留舊指紋歷史，再重新確認。

Targeted/Full Gate 使用短暫 State Lock 建立 reservation 與提交結果；測試本身在 State Lock 外執行，以免長測試阻塞其他 Issue 的 state 更新。reservation 記錄 token、Issue、SHA、Gate、owner、時間、branch/worktree、完整命令與 context hash。測試後重新載入狀態，確認 reservation 仍有效、worktree/HEAD clean、依賴/契約/main context 未變，才可寫入 PASS。中斷的 Gate 留在 `RUNNING`，不自動重跑或回收資源鎖；確認 owner 已停止後可明確執行 `state gate-recover PATH --issue N --token TOKEN --owner-stopped --reason REASON`，只把 reservation 標記為 `ABORTED`，不刪除共用 high-load lock。

`BLOCKED_ENVIRONMENT` 會繼續持有其已登記檔案 owner；只有 Issue 進入終止狀態或 owner 明確標記 `RELEASED` 才能重新分配重疊路徑。

Planner 的 `ready_frontier` 是 Code Ready。Full Gate Eligibility、Full Gate Verified、GPT Handoff Ready、GPT Final PASS 各自使用獨立 frontier；`release_frontier` 只包含確切 SHA 已取得 GPT Final PASS 的 Issue。實作未開始、執行中或只有 targeted tests 的 Issue 不可列為正式 Release Ready。
