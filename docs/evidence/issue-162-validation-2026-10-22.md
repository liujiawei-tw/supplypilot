# Issue #162 驗收紀錄

基線 Gate 證據日期：2026-10-22；P1 修正與精準回歸日期：2026-10-23。GitHub Actions 維持 `PAUSED BY USER / NOT USED AS GATE`；未查詢、啟動、重跑或更動 Workflow。此文件記錄本機與 Codex CLI 實測，不宣稱 GPT 親自執行測試。

## Skill 與協調工具

| 項目 | 結果 |
| --- | --- |
| Codex CLI | `codex-cli 0.163.0-alpha.11` |
| Skill format validator | **PASS**：`skill-creator/scripts/quick_validate.py` 回報 `Skill is valid!` |
| 真實 Skill 載入 | **PASS**：唯讀 `codex exec` 載入 `$supplypilot-implement-spec`，確認名稱／v1.0 標題，以及 #162 合併前僅允許唯讀規劃與 dry run 的邊界；未執行 Skill 工作流或修改檔案 |
| Orchestrator tests（本次 P1 修正後精準回歸） | **PASS**：68 tests，27.549 秒；包含活動 Issue 檔案 owner 衝突、`BLOCKED_ENVIRONMENT` owner 保留與明確釋放、路徑正規化及父子衝突、採用 WIP owner、精確 GPT Final PASS release frontier、main 同步使舊證據失效、state resume 遠端 main 核對、state set-head／handoff 實際 local/remote ref 核對、固定 Full Gate manifest、Targeted 實測計數與 skip/failure 拒絕、Maven skip/failure-ignore 參數拒絕、npm/Maven/Full Gate 共用鎖、Full Gate Java/Node/npm/Docker 絕對 runner 防偽，以及測試期間工作樹變動拒絕 PASS。完整 unittest 輸出保存於 [回歸 Log](issue-162-orchestrator-p1-regression-2026-10-23.log) |
| 真實 Issue 唯讀 dry run | **PASS**：以重新核對的 Issue/PR 狀態及 #142／#143／#160 WIP 快照執行 `validate`、`plan`；輸入與輸出摘要及指紋比對見 [dry-run 紀錄](issue-162-readonly-dry-run-2026-10-22.md)。`ready_frontier=[145]`，所有 Full Gate／GPT handoff／release frontier 為空 |
| 共享資源鎖 smoke | **PASS（先前紀錄）**：透過共用 Git common directory 鎖執行 Python Compose unittest、Compose config、Docker image pull/build；每項後鎖皆正常釋放。本次未重跑這些完整 Gate 項目 |

## 本機完整 CI 等價 Gate

環境：Java 21.0.10；Node.js 24.15.0；npm 11.12.1；主機 Python 3.13.5（Workflow 要求 3.12）；Docker Engine 29.6.1、Docker Desktop 4.82.0。

| Gate | 結果 |
| --- | --- |
| Backend `backend\\.\\mvnw.cmd -B --no-transfer-progress verify` | **PASS**：682 tests、0 failures、0 errors、1 skipped；`BUILD SUCCESS`，10:05；MySQL/Testcontainers 8.4.11。Skipped 為 opt-in 真實 Gemini smoke，未呼叫付費 Provider |
| Frontend `npm ci` | **PASS**：安裝 212 packages；npm audit 顯示 2 moderate vulnerabilities，未修改依賴 |
| Frontend `npm run lint` | **PASS** |
| Frontend `npm test` | **FAIL**：24 files 中 23 passed、1 failed；210 tests 中 208 passed、2 failed；總耗時 980.15 秒。`tests/sp806.test.tsx` 中 `ignores a delayed title response after switching documents`（10.894s）與 `ignores a delayed ingestion verification response after switching documents`（10.304s）失敗。完整摘要與錯誤在執行輸出；依專案規定隔離重跑結果見下方 log |
| 隔離 `npm test -- --run tests/sp806.test.tsx` | **FAIL**：25 tests 中 24 passed、1 failed，353.79 秒。`ignores a delayed ingestion response after switching documents` 無法找到 `#21` button；完整輸出保存於 [隔離 Log](issue-162-sp806-isolated.log) |
| Frontend `VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build` | **PASS**：TypeScript 與 Vite build 成功；保留既有 1.5 MB chunk >500 kB 警告 |
| Compose unittest | **PASS（版本受限）**：4/4；在主機 Python 3.13.5 執行。此機 `py -0p` 只有 Python 3.10，預設 `python` 是 3.13.5，未安裝 Workflow 要求的 Python 3.12，因此不能標記 Python 3.12 PASS |
| Production / Demo / Development Compose `config --quiet` | **PASS**：使用本次建立的虛構 placeholder env；沒有啟動服務、刪除資料或執行 reset |
| Backend Docker Image build | **PASS**：`supplypilot-backend:issue162` |
| Frontend Docker Image build | **PASS**：`supplypilot-frontend:issue162` |
| Orchestrator Python compile（本次 P1 修正後） | **PASS**：`python -m py_compile scripts/supplypilot_orchestrator.py scripts/supplypilot_full_gate.py scripts/tests/test_supplypilot_orchestrator.py` |
| Skill validator（本次 Skill 修改後） | **PASS**：`quick_validate.py` 回報 `Skill is valid!` |
| `git diff --check` | **PASS**：本次工作樹 diff 無 whitespace errors；僅有 Git 對 LF/CRLF 的提示 |

## 本次 P1 修正範圍與證據

本次只在 Issue #162 工作樹修正：活動／採用中的 Issue 對檔案與目錄路徑的排他 owner；Full Gate、GPT handoff 與 release frontier 的階段及 SHA 條件；`origin/main` 的實際 SHA 同步與舊證據失效；#142／#143／#160 現有未提交 WIP 的兩階段採用／恢復；以及 Vitest、Maven、Docker 共用的互斥高負載鎖；固定 Full Gate manifest 與測試命令白名單防止單一命令、skip/collect-only/help 或 failure-ignore 假冒通過；Full Gate 以過濾 PATH 解析 Java/Node/npm/Docker 絕對 runner，並在測試後重核工作樹 branch/HEAD/dirty，防止執行期間變動被登記為 PASS。測例不改寫 #142／#143／#160，也沒有執行它們的測試。

本次精準驗證執行環境為 Python 3.13.5；Workflow 指定的 Python 3.12 未安裝。最新 68 測例（27.549 秒）是在 Issue #162 分支原 HEAD `c2629218d00d9049cc356740514f719fdca0e648` 上、套用本次修正後執行；相同程式碼已提交為 `61549c1d9c900824e622f211248c2a126ed14273`。Windows Codex CLI 0.163.0-alpha.11 以唯讀模式載入最新 Skill，確認名稱、v1.0 標題及未合併時只允許唯讀規劃與 Dry Run；沒有執行其跨 Issue 工作流。

## 判定與限制

- **完整本機 Gate：FAIL / 未達 `LOCAL VERIFIED`。** Frontend 完整 Vitest 有 2 項失敗，且 Compose unittest 沒有在 Python 3.12 執行；未提高 timeout、跳過測試、修改 #160 或宣稱通過。
- 這次失敗位於目前使用者正在處理的 #160 覆蓋範圍。真實 Issue #160 的驗收／WIP 未改動；本 Issue 只按 `AGENTS.md` 執行完整套件與隔離診斷，並保留證據。
- 本次沒有執行真實 Browser、Gemini Provider、AWS、Production deploy 或 Demo reset；Issue #162 不要求執行這些端到端項目。
- 未執行 GitHub Actions，故沒有 GitHub CI PASS。

## 第二輪 P1-06～P1-08 精準修正（2026-10-23）

本輪只修改 #162 Orchestrator、其單元測試，以及 SupplyPilot Implement-Spec Skill 的 Gate／狀態機說明。P1-06 將 Targeted Gate 限定為單一測試檔／指定 unittest module，按 runner 選擇 frontend、backend 或 repository root 的執行目錄，驗證實際測試數並保存命令、CWD、時間、輸出及前後工作樹快照。P1-07 以短暫 State Lock 建立 Gate reservation、在鎖外執行驗證，完成後重核 reservation token 與 context；中斷需確認 owner 已停止並人工 recovery，Recovery 不會自動清除共用高負載鎖。P1-08 記錄使用者明確授權的 provisional dependency contract 來源與指紋，只有來源契約未漂移才可恢復編碼；依賴未合併前仍不能通過 Full Gate。

本輪程式與測試驗證所對應的 commit：`bbc01e1da2f40994d1c6a26ab3665c9f082b2fbc`。下方各 log 由該 commit 的相同程式／測試內容執行產生；隨後只會再提交本驗收文件中的 SHA 對應紀錄。

| 驗證 | 結果 |
| --- | --- |
| Orchestrator `StateTests` | **PASS**：38 tests，77.576 秒；涵蓋 Gate reservation 併發／中斷／context 漂移、實際執行計數、runner/CWD 約束、已合併依賴的正常 Resume、provisional contract 漂移與恢復條件。輸出：[State regression log](issue-162-p1-06-p1-08-state-regression-2026-10-23.log) |
| Orchestrator `GraphTests`、`GateTests` 與 ResourceTests（除 linked-worktree 測試） | **PASS**：39 tests，0.232 秒。輸出：[Graph/resource regression log](issue-162-p1-06-p1-08-graph-resource-regression-2026-10-23.log) |
| ResourceTests linked-worktree lock 測試（隔離執行） | **PASS**：1 test，0.920 秒。輸出：[Linked-worktree log](issue-162-p1-06-p1-08-linked-worktree-regression-2026-10-23.log) |
| 兩個修正 fixture 的精準複測 | **PASS**：2 tests，2.302 秒；已納入上述完整 StateTests 通過結果。輸出：[Focused fixes log](issue-162-p1-06-p1-08-focused-fixes-2026-10-23.log) |
| P1-06 真實單檔 smoke | **PASS**：`npm test -- tests/displayLabels.test.ts`，在 `frontend/` 執行；Node 24.15.0、npm 11.12.1、1 個檔案／2 個測試通過。輸出：[Targeted smoke log](issue-162-p1-06-targeted-smoke-2026-10-23.log) |
| Skill format validator 與只讀 Skill Load smoke | **PASS**：`quick_validate.py` 回報 `Skill is valid!`；Codex CLI 只讀載入 `supplypilot-implement-spec` v1.0，確認未合併前只有唯讀規劃／dry run 邊界。輸出：[Skill Load smoke log](issue-162-p1-06-p1-08-skill-load-smoke-2026-10-23.log) |
| Python compile 與 `git diff --check` | **PASS**：本輪最後一次檢查成功；僅有 Git LF/CRLF 提示 |

Orchestrator 測試分組執行合計 **78/78 PASS**（State 38 + Graph/Gate/Resource 39 + 隔離 linked-worktree 1）；未以單一 unittest 進程執行整套，linked-worktree 測試隔離執行。上述為精準 Orchestrator regression，不是本機完整 CI 等價 Gate。完整前端 Vitest 的既有失敗與 Python 3.12 缺失仍維持前述 **FAIL / NOT RUN** 狀態；未重跑完整前端套件，也未執行或更動 GitHub Actions。GPT Relay 對話網址已核對為使用者提供的同一對話，但瀏覽器當時顯示驗證／CAPTCHA，尚未提交本輪 review 請求，因此 **Relay review 尚未執行、沒有 Final Pass**。Matt Pocock Standards／Spec 內部預檢完成；Spec 測試覆蓋建議已補測並複查，Standards 僅留兩項非阻擋維護性觀察。本輪 commit/push 待完成。

## 第三輪 GPT Findings 修正與 Review Handoff（2026-10-23）

本輪沿用同一 Feature Branch，修正 GPT 對 `ac6e8f2d12afce069b2108ddcc717b07575c0771` 提出的 P1-09～P1-12：舊 main-base WIP 可唯讀採用但在整合前無法 Resume；`READY_TO_CODE` 由 Orchestrator 重新核實即時 Main／依賴／工作樹／owner；GPT Final Pass 不再接受呼叫端 JSON 聲稱而要求綁定 SHA 的人工 TTY attestation；Full Gate runner manifest 必須在執行前已有 review trust baseline，並於執行前後核對內容 digest。內部 Spec 預檢又發現 trust baseline 曾只要求證據提到 manifest，可能把非正向審查當成信任依據。追加修正為證據必須有獨立完整一行 `FULL_GATE_MANIFEST_REVIEW: PASS`；基線建立與後續有效性檢查共用精確行驗證，引用該字串或只有 `CHANGES REQUIRED` 的負向案例會拒絕。同步更新 GPT Handoff、State Machine 與 Testing Policy。

| 驗證 | 結果 |
| --- | --- |
| Orchestrator 完整單元測試檔（程式碼 SHA `d4bad48cc5f5137482e5383f545a92cf65824fe9`，追加 marker 修正前） | **PASS**：68 tests，27.549 秒；完整輸出見 [Orchestrator P1 regression log](issue-162-orchestrator-p1-regression-2026-10-23.log) |
| Full Gate trust 正向、SHA/digest tamper、CHANGES REQUIRED/引用 marker 負向精準測試（程式碼 SHA `38eaec4`） | **PASS**：2 tests；`StateTests.test_full_gate_trust_requires_interactive_review_record_and_detects_tampering` 與 `StateTests.test_full_gate_trust_rejects_changes_required_review_verdict` |
| Python compile | **PASS**：`python -m compileall -q scripts/supplypilot_orchestrator.py scripts/tests/test_supplypilot_orchestrator.py` |
| `git diff --check` | **PASS**：無 whitespace error；Git 顯示 LF/CRLF 轉換提示 |
| Matt Pocock Standards／Spec 內部預檢 | **PASS，非 GPT Review**：無 documented standards breach；Spec 確認 marker 缺口已修正；保留大型 Orchestrator 模組的維護性觀察 |
| Python 版本 | `3.13.5`；本機環境未符合完整 CI 等價 Gate 指定的 Python `3.12` |
| Skill validator | **NOT RUN**：本輪沒有找到可用的 repo validator 指令；本次 Skill 修改只涉及參考文件 |
| 本機完整 CI 等價 Gate | **BLOCKED / NOT RUN on SHA `38eaec4`**：既有完整驗收紀錄中的 Frontend Vitest 失敗（SP-806，208/210）及 Python 3.12 缺口仍未解決；不修改暫停中的 #160，也不把精準回歸當作完整 Gate PASS |
| GitHub Actions | **PAUSED BY USER / NOT USED AS GATE**；本輪未啟動或修改 Workflow |
| #160、#142、#143 | 維持暫停；本輪未修改、測試、Resume 或清除其工作樹 |

本輪本機分支曾確認 `d4bad48cc5f5137482e5383f545a92cf65824fe9`，遠端仍在 `ac6e8f2d12afce069b2108ddcc717b07575c0771`，`main` 為 `9240d6aeeba2361476a0454ac965a214769638a0`。追加 P1-12 修正 commit 為 `38eaec4`；本證據文件更新後須以最終提交再次核對 Local／Remote Head SHA。GPT Relay 正式 Review 尚未送出；尚無新的 GPT verdict、PR 或 Final Pass。

## 第四輪 GPT BLOCKED Findings 修正與重新交接（2026-10-23）

上一輪 GPT Relay 對精確 Head `fb5fe875ca5c4b27b64e41defe0b586b1f14df57` 的實際判定為 **BLOCKED**，指出 P1-10A、P1-11A、P1-12A、P1-12B；未提供 `FULL_GATE_MANIFEST_REVIEW: PASS`，沒有 Final Pass。此輪僅修正同一 Issue #162：

- 將活動檔案 owner 衝突檢查抽成共用 gate，`state resume` 與 `READY_TO_CODE → IMPLEMENTING` 實際取得執行權時重新檢查，並在檢查期間持有 state lock。
- GPT review attestation 僅接受不在 Markdown code fence／引用中的獨立最後 verdict 行；`CHANGES REQUIRED`、`NO FINAL PASS`、`BLOCKED` 不會被正文中提到的 `FINAL PASS` 誤判。`PR_OPEN` 狀態轉移及 PR gate 都驗證持久化的有效 attestation；`pr-gate` 現在依 state file 與 Issue 編號讀取 Orchestrator 狀態，不再接收任意 Issue Record JSON。
- Full Gate manifest marker 必須在非引用／非 code fence 正文的獨立行出現，且 review 的最後 verdict 是 `FINAL PASS`。Trust baseline 綁定目前 Issue HEAD、manifest digest 與執行它的 `supplypilot_orchestrator.py` runner digest；HEAD 更新或 WIP 重新採用會清除舊 trust，建立 baseline 時亦會核對互動確認期間兩個檔案都未變。

| 驗證 | 結果 |
| --- | --- |
| Orchestrator `StateTests`（45 tests） | **PASS**：完整 StateTests 類別 45/45；輸出：[State regression log](issue-162-p1-09-p1-12-state-regression-2026-10-23.log)。這次完整類別執行後另有兩項小幅 fail-closed 加固（owner path normalization、`PR_OPEN` attestation 核對），由下列最終精準回歸覆蓋 |
| 最終精準回歸（完整 state gate、resume owner overlap、Full Gate manifest/runner trust、GPT Final Pass→PR 及 GateTests） | **PASS**：10 tests，12.669 秒；其中包括否定／引用 verdict、任意 JSON PR gate、trust SHA/runner tampering、在 READY 後 owner 改變等案例 |
| Python `compileall`（Orchestrator、Full Gate、測試模組） | **PASS** |
| `git diff --check` | **PASS**：無 whitespace errors；僅 Git LF/CRLF 轉換提示 |
| 完整 Orchestrator 測試模組單次執行 | **INTERRUPTED / NOT PASS**：Windows 執行在 `ResourceTests.test_linked_worktrees_resolve_to_the_same_default_lock_directory` 開始時收到 `KeyboardInterrupt`；該 linked-worktree 測試隔離執行為 **PASS**。完整狀態測試類別另有 45/45 PASS 紀錄；未把中斷的單次整套執行標成 PASS |
| 本機完整 CI 等價 Gate | **BLOCKED / NOT RUN**：前次完整 Gate 證據仍有 Frontend SP-806 失敗及 Python 3.12 缺失；此輪未重跑完整 Gate，沒有宣稱 `LOCAL VERIFIED` |
| GitHub Actions | **PAUSED BY USER / NOT USED AS GATE**；未啟動或修改 Workflow |
| #160、#142、#143 | 維持暫停；此輪未修改、測試、Resume 或清除其工作樹 |

本輪仍在 Issue #162 Feature Branch；實作尚待 commit/push 及核對 local／remote Head。GPT Relay 將沿用已核對的同一 Conversation URL 重新審查最終 Head。此輪尚未取得新 GPT verdict、Final Pass 或 PR；不得建立 PR。Merge 仍由使用者決定。

## 第五輪 GPT Review P1-13／P1-14 修正（2026-10-23）

GPT 對 `8fa5b99727d7ad8550498ff7c86388e72f8f29b8` 的實際判定為 **CHANGES REQUIRED**。本輪只處理該 Review 新增的兩項 P1：

- P1-13：將首次 `READY_TO_CODE → IMPLEMENTING` 的 main-base Code Gate，與既有 Issue 的重新進入區分。從 `CHANGES_REQUIRED`、`TARGETED_VERIFIED` 或 `BLOCKED_ENVIRONMENT` Resume 時，仍核對 `origin/main`、登記分支、記錄中的 feature HEAD、乾淨工作樹、main ancestry、契約、依賴、活動 owner 與 Issue 授權，但允許 feature HEAD 已領先 main；未記錄的新 HEAD 會拒絕。
- P1-14：Full Gate manifest 信任只依賴獨立、明確的 `FULL_GATE_MANIFEST_REVIEW: PASS` 子項 verdict，不要求整份 GPT review 已 `FINAL PASS`。SHA、manifest 識別行與 verdict 均須出現在非 Markdown 引用／程式碼區塊的正文；overall `BLOCKED`／`CHANGES REQUIRED` 仍不會被判成 Final Pass，也不會直接推進 Issue 狀態。

| 驗證 | 結果 |
| --- | --- |
| P1-13／P1-14 精準 StateTests | **PASS**：8/8，14.549 秒；輸出：[P1-13／P1-14 精準回歸](issue-162-p1-13-p1-14-state-regression-2026-10-23.log) |
| 完整 Orchestrator `StateTests` 類別 | **PASS**：49/49，95.908 秒；同一證據檔記錄完整命令及結果 |
| Python `compileall` | **PASS**：Orchestrator 與測試模組 |
| `git diff --check` | **PASS**：無 whitespace errors |
| 本機完整 CI 等價 Gate | **BLOCKED / NOT RUN**：已知 SP-806 Vitest 失敗及主機 Python 3.12 缺失未解；本輪沒有執行完整 Gate |
| GitHub Actions／PR／Merge | Actions 維持使用者暫停；尚無 GPT 新 verdict，未建立 PR、未 Merge |
| #160、#142、#143 | 維持暫停；本輪未修改、測試或 Resume |

本輪程式與測試修改已完成，待 commit/push 並核對 local／remote Head SHA 後，沿用使用者已核對的同一 GPT Relay Conversation URL 發起正式 review。完整 Gate 尚未通過，因此不得宣稱可建立 PR。

## 第六輪 GPT Review Findings 與修正（2026-10-23）

GPT Relay 沿用同一 Conversation URL 審查確切 Head 947e49c41d113c6a0f0a9fa148dab2d0e997cff3，並核對遠端 branch HEAD 完全相符、Base main 為 9240d6aeeba2361476a0454ac965a214769638a0、Issue #162 Open、Ahead 10／Behind 0。GPT 的單一整體 verdict 為 **CHANGES REQUIRED**。完整 Findings 與 GitHub 核對摘要保存於 [GPT Review 947e49c](issue-162-gpt-review-947e49c-2026-10-23.md)。

- P1-13 與 P1-14：GPT 確認已修正。
- P1-15：state_resume() 與 FULL_GATE_PENDING 仍可能信任呼叫端的契約 PASS/版本欄位，未核對持久化 Issue State 的契約驗證狀態與版本一致性。
- P1-16：GPT Final Pass 證據的 SHA 檢查仍為全文包含，缺少獨立、未引用的精確 Reviewed SHA 行，且未拒絕互相矛盾的 SHA。
- P1-12B 補充：Full Gate 執行前核對 manifest/runner digest，但結束後只再核對 manifest；GPT 未提供 FULL_GATE_MANIFEST_REVIEW: PASS 子項標記。

僅針對以上 #162 範圍完成修正：新增共用持久化契約驗證 predicate，Resume 與 Full Gate eligibility 都重新檢查 State；Final Pass 要求唯一一致的非引用／非 code fence 40 位 Reviewed SHA 行；Full Gate 執行前後核對 manifest 與 runner digest，runner 變更會以失敗退出且不記錄 PASS。同步 implement-spec 狀態機、測試政策及 GPT Review handoff 規則。

| 驗證 | 結果 |
| --- | --- |
| 精準 P1-15／P1-16／runner 回歸 | **PASS**：8/8，9.581 秒 |
| 完整 StateTests | **PASS**：52/52，101.045 秒 |
| 完整 GateTests | **PASS**：5/5 |
| 完整 Orchestrator 單次模組 | **INTERRUPTED / NOT PASS**：在 linked-worktree resource 測試的 git init -b main 收到 KeyboardInterrupt |
| Linked-worktree resource 隔離測試 | **PASS**：1/1，0.649 秒 |
| 中斷後剩餘五個 ResourceTests | 測試輸出顯示 5/5 OK，但命令 runner 兩次回報 exit code 1；記為 **INDETERMINATE**，不宣稱 PASS |
| Python compileall／git diff --check | **PASS** |
| 完整本機 CI 等價 Gate | **BLOCKED / NOT RUN**：SP-806 Vitest 歷史 208/210 與 host 缺 Python 3.12；本輪未重跑完整 Gate |
| GPT verdict／manifest 子項／PR | 整體 **CHANGES REQUIRED**；GPT 未給 manifest 子項 PASS，沒有 Final Pass，不具 PR 資格；未建立 PR、未 Merge |
| GitHub Actions／其他暫停 Issue | Actions 由使用者暫停；#160、#142、#143 維持暫停，未修改、測試或 Resume |

本輪程式修正與文件同步完成後仍須在同一 feature branch Commit／Push、核對新 local／remote Head SHA，並透過同一 GPT Relay 對話重新審查。不得將精準回歸、資源測試部分結果或 manifest 子項信任等同完整 Gate／整體 Final Pass。

## 第七輪 GPT Review 與 P1-17／P2-01 修正（2026-10-23）

沿用同一 GPT Relay Conversation URL，GPT 獨立核對 Issue #162 的 `617058da2a7d4316fda336a94c111dfcf40d12fa`：GitHub 遠端 Feature HEAD 完全相符、base `main` 為 `9240d6aeeba2361476a0454ac965a214769638a0`、Issue Open、ahead 11／behind 0、28 個檔案變更。GPT 明確指出受限 shell 當時無法取得本機 `git status`，因此不宣稱該 SHA 工作樹乾淨；之後 Codex 以獲准讀取方式核實 worktree 為 clean 且 local／remote SHA 相同。

整體 verdict：**CHANGES REQUIRED**。GPT 確認 P1-15、P1-16、P1-12B 已修正，並對該 SHA 的 Full Gate Manifest 子項給出獨立 **PASS**；這不代表完整 Gate 或整體 Final PASS。新 finding：

- **P1-17**：main 前進後舊 Full Gate 已標記 STALE、契約已失效，但 Issue 仍可留在 `WAITING_GPT_REVIEW` 並通過 Final Pass attestation／PR gate。
- **P2-01**：`PR_OPEN` 原先只檢查使用者提供的 URL/Base/Head/Closes 行，沒有向 GitHub 即時核實 PR 確實存在且 Open。

同一 #162 分支已完成修正：main Sync 使尚未終止 Issue 的 `WAITING_GPT_REVIEW`／`GPT_FINAL_PASS` 回到 `CHANGES_REQUIRED`；Final Pass attestation 和 PR Gate 重新核對持久化 Full Gate 證據、SHA、base/main、契約與依賴，並確認即時 `origin/main`；`PR_OPEN` 以 GitHub CLI 唯讀核對 Repository、Open 狀態、main base、Head branch/SHA、URL 及獨立 `Closes #Issue`。同步更新 state-machine 文件。完整 GPT 回覆保存於 [GPT Review 617058da](issue-162-gpt-review-617058da-2026-10-23.md)。

| 驗證 | 結果 |
| --- | --- |
| P1-17／P2-01 最終精準回歸 | **PASS**：3/3，6.040 秒 |
| 完整 StateTests 最終版本 | **PASS**：54/54，103.080 秒 |
| GateTests 最終版本 | **PASS**：5/5，0.018 秒 |
| Python compileall／git diff --check | **PASS** |
| 完整 Orchestrator 模組／完整本機 CI Gate | 本輪未執行；完整本機 Gate 仍因 SP-806 208/210 與缺少 Python 3.12 而 **BLOCKED / NOT RUN** |
| GPT review／PR | `617058da…` 的 verdict 為 **CHANGES REQUIRED**；P1-17／P2-01 修正尚待新 SHA 複審，無整體 Final PASS，沒有建立 PR 或 Merge |
| 暫停範圍 | GitHub Actions 使用者暫停；#160、#142、#143 保持暫停 |

## 第八輪 GPT Review 與 P1-18 修正（2026-10-23）

沿用同一 GPT Relay 對話，GPT 審查確切 Head `cbd9d34abb151184b94a3beeeeb011a2428b12a9`，核對 GitHub 遠端 branch HEAD 相符、base `main` 為 `9240d6aeeba2361476a0454ac965a214769638a0`、Issue #162 Open、ahead 12／behind 0。GPT 確認 P1-17、P2-01 已修正，並再次核准此 SHA 的 Manifest／Runner 子項；整體 verdict 仍是 **CHANGES REQUIRED**。

新阻擋 finding **P1-18**：`can_open_pr()` 驗證 state 中的 reviewed Head、Gate、契約、main 與依賴，但未重新查詢即時 `origin/<feature-branch>`。若遠端分支已前進，仍可能在 PR 建立前錯誤放行舊 SHA。完整 GPT 回覆保存於 [GPT Review cbd9d34](issue-162-gpt-review-cbd9d34-2026-10-23.md)。

同一 #162 分支已在 `can_open_pr()` 加入唯讀 `_verify_local_remote_feature_head(record)` 查核，必須本機分支、遠端分支、Persisted/Reviewed HEAD 全部一致；查詢失敗或 SHA 漂移都 fail closed。新增負向測試模擬遠端 Feature Branch 前進至 SHA B，確保 SHA A 的完整 Gate／Final Pass 不會取得 PR Gate 放行；同步更新 state-machine 文件。

| 驗證 | 結果 |
| --- | --- |
| 最終 PR Gate 精準回歸＋GateTests | **PASS**：6/6，4.757 秒 |
| 完整 StateTests | 前一個來源 SHA `cbd9d34`：**PASS** 54/54，103.080 秒；之後只改 `can_open_pr()` 遠端 Feature HEAD 一項，已由本輪精準回歸覆蓋 |
| Python compileall／git diff --check | **PASS** |
| 完整 Orchestrator／完整本機 CI 等價 Gate | 本輪未執行；完整 Gate 仍因 SP-806 歷史 Vitest 208/210 與缺少 Python 3.12 而 **BLOCKED / NOT RUN** |
| GPT／PR | `cbd9d34…` 整體 **CHANGES REQUIRED**，P1-18 尚待新 SHA 複審；沒有 Final PASS、沒有 PR、沒有 Merge |
| 暫停範圍 | GitHub Actions 使用者暫停；#160、#142、#143 保持暫停 |
