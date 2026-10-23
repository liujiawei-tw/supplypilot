# 狀態機與狀態證據

狀態必須反映外部可核對事件。由單一 Orchestrator writer 維護共享 JSON state，放在 `git rev-parse --git-common-dir` 下的 `supplypilot-orchestrator/`，避免不同 worktree 產生不一致副本。狀態讀改寫使用原子目錄鎖；既有鎖視為不確定狀態並 fail closed，不自動回收。需保留每 Issue 的 dependencies、base/main SHA、branch、worktree、API/Schema contract version、file ownership、implementation、Issue Gate、Release Gate、GPT review、PR 與 merge 欄位。Issue Markdown 與 agent 自述不能替代 GitHub PR/Issue 狀態。

| 狀態 | 意義與推進證據 |
| --- | --- |
| BLOCKED_DEPENDENCY | 有未完成的必要依賴；除經使用者授權的 provisional Code Gate 外不派工 |
| READY_TO_CODE | 已通過 Code Gate，Worktree/branch/owner 已核對；尚未代表開始編碼 |
| IMPLEMENTING | 唯一 implementer 正在其 Issue Worktree 中工作 |
| TARGETED_VERIFIED | 開發期精準測試紀錄；不是 Issue Gate 或 Release Gate |
| ISSUE_GATE_VERIFIED | Orchestrator 依精確 base-to-HEAD Diff 建立必要測試計畫，實際執行全部必要命令並保存真實結果；不代表 Release Gate 通過 |
| WAITING_DEPENDENCIES | 實作完成但必要上游未 merge；不得交 GPT 審查或開 PR |
| FULL_GATE_PENDING | 必要依賴 merged、feature HEAD 已包含當前 main 且契約已核實；可執行新的 Issue Gate、恢復實作，Release Candidate 也可執行完整 Release Gate |
| FULL_GATE_VERIFIED | 固定 Full Gate manifest 對確切 SHA 執行通過；屬 Release Gate 證據，不是一般 Issue Gate 的必要條件 |
| WAITING_GPT_REVIEW | Issue Gate 對確切 SHA 仍有效，內部預檢通過且 local/remote HEAD 相等 |
| CHANGES_REQUIRED | GPT 對此 SHA 要求修正，或 main 更新使舊 Gate／Review 失效；若 main 已前進，先經 FULL_GATE_PENDING 重驗整合基準，再恢復實作並重跑受影響 Gate |
| GPT_FINAL_PASS | GPT 明確對目前 Head SHA Final PASS，且人工互動 attestation 有效 |
| PR_OPEN | GPT PASS 後 PR 已建並核對描述/Base/Head |
| MERGED | GitHub 證明 PR 已合併至 main、Issue 已關閉，並由 `git ls-remote` 核實目前 main SHA |
| BLOCKED_ENVIRONMENT | 明確記錄缺少的環境、權限或資源 |
| FAILED | 可重現的未恢復失敗 |

新流程為 `IMPLEMENTING → ISSUE_GATE_VERIFIED → WAITING_GPT_REVIEW → GPT_FINAL_PASS → PR_OPEN`。`TARGETED_VERIFIED` 僅為開發期證據；從舊 state 讀到 `FULL_GATE_VERIFIED`、`TARGETED_VERIFIED` 或其他歷史值，都不會建立 `issue_gate` 紀錄或自動升級為 `ISSUE_GATE_VERIFIED`。要交 GPT 審查，必須用目前 Orchestrator 對當前 base/head Diff 重新執行 Issue Gate。

每個 Issue Gate 由 Orchestrator 取得 Git Diff、受影響行為、必要命令與 `NOT_APPLICABLE` 理由。必要命令由固定影響對照產生；呼叫端不能提供 PASS、退出碼、log、測試清單或任意排除項目來建立可信結果。未知變更路徑或無法映射的依賴會拒絕 Gate，直到擴大並核實受影響測試。每項 Gate transcript 記錄變更路徑、受影響行為、必要測試、N/A 原因、實際命令、CWD、時間、退出碼、輸出、Worktree 前後狀態及 SHA-256。

`state init` 只接受與即時 `origin/main` 相同的完整 commit SHA。開始實作、Issue Gate、GPT handoff 與 PR Gate 都核對 branch、worktree、clean 狀態、feature 包含當前 main、契約版本、已合併依賴及 file owner。所有 test/review record 綁定 SHA；HEAD、main、依賴或契約版本／來源改變時使相應 Issue Gate、Release Gate 與 Review 證據標為 stale。main 前進會使下游 Gate／Review 失效；若 main diff 涉及 `backend/src`、`frontend/src`、API／DTO／Schema／migration／contract 路徑或資料格式，契約核驗也會失效。無法讀取 main diff 時採 fail-closed，視為契約已變更。下游狀態回到 `CHANGES_REQUIRED`；只有 `FULL_GATE_PENDING` 重新核實即時 main、feature ancestry、乾淨且與遠端一致的 feature HEAD、依賴與契約後，才可恢復實作或執行新 Issue Gate。Planner 對證據檔重算 SHA-256；`state set-head`、GPT handoff、GPT attestation 與 PR Gate 核對本機 branch/HEAD 及遠端 feature ref，不只比較傳入字串。

唯一一次的 #162／PR #165 stale-state recovery 使用 `state recover-merged <state.json> --issue 162 --pr 165 --dry-run --backup-out <path> --manifest-out <path>`。命令直接唯讀查詢 GitHub REST API，核對 repository、PR base/head/merge SHA、Issue 關閉、目前 main 與 merge ancestry；不接受 caller 提供的 JSON/SHA 作為 GitHub 證據。預設 dry-run 不取得 State 寫入鎖、不改 State JSON，只在明確指定的輸出路徑建立原始 State 備份和包含 SHA-256／before-after diff 的 manifest。正式套用須使用 `--apply`、互動終端確認 `RECOVER ISSUE #162 FROM PR #165`，在 State lock 下重驗 WIP 指紋、owner 與 Gate reservation，先備份再原子寫入。此流程只記錄 PR #165/#162 合併事實、同步 main 並釋放已確認的 #162 ownership；保留 `verification: NOT_RUN`、`issue_gate: null`、GPT review 原值，且明確記錄 Issue Gate `NOT_VERIFIED`、Release Gate `NOT_RUN`。不可重複套用，也不會使任何下游 Issue 自動通過 Gate。

`FULL_GATE_VERIFIED` 保留為整合後 Release Candidate 的完整 Gate 結果。它仍要求固定 `scripts/supplypilot_full_gate.py` manifest、獨立 GPT manifest 子項審查、人類確認與 manifest/runner SHA-256 trust baseline；執行前後重算 digest。這個 Release Gate 不會由 Issue Gate PASS 產生，也不要求每個 Issue 在 GPT handoff 或 PR 前執行。舊 state 僅有 Full Gate 記錄時，仍須另行執行 Issue Gate。

既有工作成果使用 `state adopt` 記錄 branch、HEAD、index、tracked diff、untracked files 與內容指紋。採用後仍是 `BLOCKED_DEPENDENCY`、驗證保持 `NOT_RUN`。只有使用者明確授權、目前 Issue Code Gate 通過、再次核對同一 branch/worktree、WIP 指紋不變、即時 `origin/main` 仍等於 state base、feature HEAD 包含此 main、Issue State 的持久契約核驗欄位有效，且所有依賴已核實 MERGED/closed 時，`state resume --code-gate-pass` 才可一般恢復。使用者可明確採納每項尚未合併依賴的 provisional contract 來源；紀錄需保存來源 Issue、branch、commit SHA 或 WIP 指紋、契約版本與內容雜湊、verification state、授權與未滿足依賴。上游來源、branch、SHA、WIP 指紋、contract version/content 或 verification state 變更時，拒絕恢復／Gate，須重新採用並重新授權。Provisional resume 只允許在 Issue Worktree 編碼；不允許 Issue Gate、GPT Review 或 PR，直到依賴實際合併且契約完成核驗。

Issue Gate 與 Full Gate 使用短暫 State Lock 建立 reservation 與提交結果；測試本身在 State Lock 外執行，避免長測試阻塞其他 Issue 的 state 更新。reservation 記錄 token、Issue、SHA、Gate、owner、時間、branch/worktree、命令、impact plan 與 context hash。測試後重載狀態，確認 reservation 仍有效、worktree/HEAD clean、依賴/契約/main context 未變，才可寫入 PASS。中斷的 Gate 留在 `RUNNING`，不自動重跑或回收資源鎖；確認 owner 已停止後可明確執行 `state gate-recover PATH --issue N --token TOKEN --owner-stopped --reason REASON`，只把 reservation 標記為 `ABORTED`，不刪除共用 high-load lock。

`BLOCKED_ENVIRONMENT` 會繼續持有已登記檔案 owner；只有 Issue 進入終止狀態或 owner 明確標記 `RELEASED` 才能重新分配重疊路徑。

Planner 分別輸出 Code Ready、Issue Gate Eligible、Issue Gate Verified、Release Gate Verified、GPT Handoff Ready 與 GPT Final PASS frontier。`gpt_handoff_frontier` 只接受新的、確切 SHA 的 Issue Gate 證據與 local/remote HEAD 相等；`release_frontier` 只包含該 Issue 已取得 GPT Final PASS 的確切 SHA，並不表示整體 Release Gate 通過。

## Review 修正後的強制驗證

- 首次 `state adopt` 以 Git 計算 WIP HEAD 與記錄中 main 的真實 merge-base；舊 base 可唯讀登記並標記 `STALE_BASE`，但不得 Resume、Review 或進 Gate。先安全整合目前 main、重驗契約與依賴，再重新 Adopt/Resume。未提交內容與指紋須保留。
- `READY_TO_CODE` 忽略呼叫端宣稱的 Gate PASS，重新核對即時 `origin/main`、branch/HEAD/clean Worktree、contract version、已合併或具完整授權 provisional contract 的依賴、以及其他 active Issue 的檔案 owner。缺資料即 fail closed。
- 從 `CHANGES_REQUIRED`、`TARGETED_VERIFIED`、`ISSUE_GATE_VERIFIED` 或 `BLOCKED_ENVIRONMENT` 重新進入 `IMPLEMENTING` 時，重新核對即時 `origin/main`、登記 branch、feature HEAD、clean Worktree、feature HEAD 包含目前 main、contract、依賴、owner 與 Issue 授權。若記錄的 base 已落後，必須先經 `FULL_GATE_PENDING` 完成整合基準檢查；該狀態不能由呼叫端自述通過。
- 一般 state transition 的 Evidence JSON 不得登記 `GPT_FINAL_PASS`。使用 `state review-attest` 經互動 TTY，由人類核對 ChatGPT URL、完整 UTF-8 回覆檔及其 SHA-256；正文須有唯一且一致的非引用／非 code fence `Reviewed SHA: <40 位 SHA>` 行和最後有效 `FINAL PASS` verdict，確認字串綁定此 SHA。Planner 與 PR Gate 重新核對 attestation 與證據檔。
- Final Pass attestation 與 `can_open_pr()` 必須重新驗證持久化 Issue Gate 證據、SHA／main／契約版本／依賴仍一致，並確認即時 `origin/main` 仍等於 state main。Release Gate 證據不替代 Issue Gate 證據。
- `PR_OPEN` 轉移前透過 GitHub CLI 唯讀核對 PR 存在且 Open、Repository、Base `main`、Head branch/SHA、URL 及獨立 `Closes #Issue` 行；缺少或不一致時拒絕記錄 PR 狀態。
- `FULL_GATE_VERIFIED` 需先經 `state trust-full-gate` 互動登記獨立 GPT Review 對確切 SHA 上 `scripts/supplypilot_full_gate.py` 的明確正向子項審查（原文獨立行 `FULL_GATE_MANIFEST_REVIEW: PASS`）與 SHA-256 baseline。子項 verdict 與整體 verdict 分開；Full Gate 執行前後核對 manifest/runner digest。一般 Feature Branch Evidence JSON 不能登記或覆寫 baseline。
