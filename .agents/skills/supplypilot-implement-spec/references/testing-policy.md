# Issue Gate、Release Gate 與共享資源政策

依根目錄 `AGENTS.md`、當前 Issue 與已核准 Scope 執行。每張 Issue 在 GPT Review／PR 前執行 impact-based Issue Gate；Release Candidate 才執行完整 Release Gate。兩種 Gate 分開保存與審查，Issue Gate PASS 不能推導 Release Gate PASS。

## Issue Gate 選測

Orchestrator 以登記的 base SHA 與目前乾淨 HEAD 計算完整 Git Diff，沿用固定 impact 對照列出：`變更 → 受影響行為／依賴 → 必要測試／Build／Lint → 實際結果`。對未影響項目保存 `NOT_APPLICABLE` 和具體理由。選測須納入直接／間接依賴、共用元件、API／DTO／Schema、授權、交易、併發及配置風險；沒有可靠映射的路徑 fail closed，不能由呼叫端自行宣稱無關或 PASS。

目前 Issue #162 Orchestrator 的映射是：變更 `scripts/supplypilot_orchestrator.py`、`scripts/supplypilot_full_gate.py` 或 `scripts/tests/test_supplypilot_orchestrator.py` 時，執行完整 `scripts.tests.test_supplypilot_orchestrator` unittest module，涵蓋 Graph、Gate、State、Resource、Worktree 與 Full Gate 信任契約。同一變更中的本 Issue Skill／handoff／AGENTS 或 Issue 證據路徑可列為非執行程式，並記錄其不影響的測試為 `NOT_APPLICABLE`。若整個 Diff 只有文件或證據，且目前沒有對應的文件驗證 runner，Orchestrator 會因沒有可執行的必要測試而 fail closed；須先核實並加入適用的文件驗證，再通過 Issue Gate。其他尚未映射的程式路徑不可從此 mapping 排除測試；須先擴大並核實受影響 suite。

`ISSUE_GATE_VERIFIED` 不接受呼叫端傳入的 PASS、退出碼、log、測試清單或 N/A 理由。Orchestrator 依真實 diff 產生 required command，並在登記的 Issue Worktree 實際執行；每個必要命令都必須 exit 0、輸出非零已執行案例且沒有 Failures/Errors/全數 Skip。只跑某個 Targeted Test 會保留為開發期 `TARGETED_VERIFIED`，不能推進 Issue Gate。任何必要檢查失敗、未執行、依賴不明、SHA／main／contract context 改變，都不得進 `ISSUE_GATE_VERIFIED`。

Gate transcript 保存完整變更路徑、影響對照、命令、實際執行命令、CWD、開始／結束時間、退出碼、測試輸出、Worktree 前後狀態和 SHA-256。Planner／GPT review 可檢查該對照；不得只憑開發者聲稱某測試無關就自動通過。舊 state 沒有 `issue_gate` 證據者不得提升為新 Issue Gate PASS。

## Release Gate

既有 `scripts/supplypilot_full_gate.py` 保留為 Release Candidate 完整驗證工具，涵蓋 Backend Maven Verify、Linux Node 24 完整 Frontend Vitest、Frontend Lint／Build、Python／Compose、Docker Engine 與 Backend/Frontend Image Build，以及實際交付範圍所需 Browser／RAG／Gemini E2E。只在整合後 Release Candidate 執行；未執行或失敗項目分別標為 `NOT RUN`／`BLOCKED`／`FAIL`。不得把 Issue Gate、mock、靜態檢查或多個 Issue 單獨 PASS 拼成 Release Gate PASS。

Full Gate 維持固定 `supplypilot-full-gate` command、Issue Worktree clean／精確 HEAD、獨立 GPT 對 manifest 的 `FULL_GATE_MANIFEST_REVIEW: PASS`、人類互動 trust baseline，以及 manifest／runner 執行前後 SHA-256 檢查。不得弱化或刪除此信任邊界。Release Gate 不屬每一張 Issue 的一般 GPT Review／PR 前置條件。

## Runner 與共享資源

不得刪減／skip 測試、放寬 assertions/timeouts、將 mock 結果當成真實 provider/browser/AWS gate。一般精準驗證只接受受限 runner：Python 由目前執行 Orchestrator 的 `sys.executable` 啟動；Maven 使用 Issue Worktree 的 `backend/mvnw`／`mvnw.cmd` 和核准測試選擇；npm 使用已解析安裝路徑，只能跑核准的測試檔。拒絕任意 shell、路徑逃逸、`docker compose run`、pytest collect-only、Maven skip/failure-ignore、lint/build/Compose config 冒充測試 Gate。CWD 由 Orchestrator 決定。

預設並行上限 implementer 3。Full Vitest、Maven Verify、Docker/Testcontainers、Docker Image Build 等高負載完整驗證共用一個 `high-load` 互斥鎖；npm/Vitest 與 Maven targeted 命令也依既有 runner 規則取鎖。鎖位於 `git rev-parse --git-common-dir` 下的 `supplypilot-orchestrator/`，供 linked worktrees 共用。正常結束才釋放；異常中止保留診斷紀錄，不自動回收或停止其他服務。鎖不可取得時立即 BLOCKED，不無限重試。

Gate 更新分三段：短暫 State Lock 建立 `RUNNING` reservation；在 State Lock 外執行測試、持有必要資源鎖；測試後重載 state，核對 reservation token、Issue、SHA、worktree、依賴、契約、main 與 impact context，只有仍相符且所有必要驗證通過才保存 Gate PASS。中斷不自動重跑或清除 reservation；確認 owner 已停止並提供原因後可用 `state gate-recover` 標記 `ABORTED`，資源鎖須另行人工診斷。

每筆證據記錄 SHA、環境版本、命令、案例數、退出碼、log 路徑、結果與風險。相同 SHA／環境／範圍的有效證據不需無理由重跑；程式或影響範圍改變後重跑受影響驗證。GitHub Actions 是否使用遵從使用者最新指示；本 Skill 不啟動 workflow。
