# Issue #139（V2-202）本機驗收紀錄

- 日期：2026-10-22（Asia/Taipei）
- Repository：`liujiawei-tw/supplypilot`
- Base：`main` @ `ace1924d7eba1b3eb4383833136a5e07f5a32917`
- Feature branch：`codex/issue-139-v2-202`

## 驗收環境

- Java：21.0.10
- Node.js：v24.15.0
- Python：3.13.5（GitHub Workflow 指定 3.12；本機暫行 Gate 使用可用版本，版本差異需保留）
- Docker Engine：29.6.1（Docker Desktop）
- Docker Compose：v5.3.0
- Testcontainers：使用 MySQL 8.4.11；完整後端 verify 執行時 Docker Engine 可用

## 使用者暫停 GitHub Actions／本機 Gate（2026-10-22）

- 狀態：`PAUSED BY USER / NOT USED AS GATE`。使用者明確決定暫停使用 GitHub Actions，直到親自通知恢復；本次未查詢或重跑 Actions，也不依賴 Actions 結果。
- 此決策沒有停用 GitHub Repository 設定。Push 仍可能觸發自動 Workflow；不得宣稱 GitHub Actions 已停止。
- 本機 Gate 依 Issue #139 程式碼 Commit `6580be95d4ff541744065454ec9c80f87c619af6` 對應的產品程式驗收；前端完整 Linux 測試是在文件 Head `05abb68f40f6002a11620f2614c5dd7b150f288d` 執行。之後 `origin/main` `24d17c1fea057def5577f8c6aeee0b47d8d2157a` 只修改規範／文件；`cfe7968` 及本次提交也未變更產品或正式測試程式。
- Gate 摘要：Backend `PASS`（Java 21.0.10，669 tests、0 failures/errors、1 skipped，對應 `6580be9`）；Frontend `npm ci`／lint `PASS`、完整 Node 24 Linux Vitest `FAIL`（204/205，SP806 案例 timeout）、該次 Linux build `PASS`；Compose unit/config 與 Backend／Frontend image `PASS`（既有 `6580be9` 驗收證據）。本次是規範／驗收文件變更，以上 Gate 未重跑；所有本機結果均以其實際程式碼版本為準。
- GitHub Actions 的既有失敗／Skipped Run 僅為歷史紀錄，保留於下節；它們不作目前 Gate，也不改寫為 PASS。

## 驗收結果

| 工作 | 指令／結果 |
| --- | --- |
| 後端完整 Gate | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`：PASS；669 tests、0 failures、0 errors、1 skipped，BUILD SUCCESS，耗時 10:15。涵蓋最終 V25 migration、lock/concurrency 測試及既有 SQL fixtures。 |
| 精準後端單測 | `-Dtest=PurchaseOrderServiceTests test`：4 tests、0 failures/errors。 |
| PO lifecycle 整合測試 | `-Dtest=PurchaseOrderLifecycleIntegrationTests test`：13 tests、0 failures/errors；包含 `ORDERED + NULL ordered_progress` 被 MySQL CHECK 拒絕。 |
| 前端依賴 | `cd frontend; npm ci`：完成，213 packages audited、0 vulnerabilities。 |
| 前端 lint | `npm run lint`：PASS。 |
| 前端完整測試（歷史執行） | 前次 Node 24 Linux 完整 suite：`npm ci`、`npm run lint` PASS；`npm test` FAIL，24 files／205 tests 中 204 passed、1 timeout。當時唯一失敗為 `masterData.test.tsx` 的 `keeps Supplier create and edit values when backend submissions fail`，timeout 40 秒；該檔隔離執行 10/10 PASS、145.53 秒，指定案例耗時 28.13 秒。該次 Linux 命令在 `npm test` 非零退出後停止，未執行 Linux build。此結果已由下方 2026-10-22 復驗更新；兩次完整 suite 的失敗測例不同，原因未定。 |
| 前端 build | `$env:VITE_API_BASE_URL='http://localhost:8080/api/v1'; npm run build`：PASS。Vite 有既有大型 chunk 建議（約 1.5 MB），build 成功。 |
| Compose unit tests | `python -m unittest scripts.tests.test_compose_config -v`：4 tests PASS。 |
| Compose config | Production、Demo、Development 三種 `docker compose ... config --quiet`：全部 PASS。 |
| Backend image | `docker build --file backend/Dockerfile --tag supplypilot-backend:ci .`：PASS；最終 V25 migration 已重建。 |
| Frontend image | `docker build --file frontend/Dockerfile --build-arg VITE_API_BASE_URL=/api/v1 --tag supplypilot-frontend:ci .`：PASS；前端產品程式完成後建置。 |
| `git diff --check` | PASS（完成最終變更後再確認）。 |

## GitHub Actions 證據

- 程式碼 Commit `6580be95d4ff541744065454ec9c80f87c619af6` 的 GitHub Actions Run `39642896016`（2026-10-21）：`Detect CI scope` 為 `failure`；Backend CI、Frontend CI、Container image and Compose validation 均為 `skipped`。
- 驗收文件 Commit／當時 Branch Head `05abb68f40f6002a11620f2614c5dd7b150f288d` 的 Run `39643022423`：`Detect CI scope` 同樣為 `failure`；Backend CI、Frontend CI、Container image and Compose validation 均為 `skipped`。該次 Job Log API 回應 404（`BlobNotFound`）。
- 兩次 Run 都未執行 Backend／Frontend／Container jobs，無法從 GitHub Actions 確認程式測試結果。未取得 `Detect CI scope` 的 Job log，故不推定 CI Failure 根因，也不推定為 GitHub Billing 阻擋。
- PR #155 已合併的歷史 CI Run `39622948089`：`Detect CI scope` 失敗，Backend／Frontend／Container jobs skipped；這不代表 GitHub CI PASS。
- PR #155 描述亦記錄先前 Run `39620449208` 因 GitHub runner/free quota 或 Billing 限制無法取得 runner。這些是暫行本機 Gate 的背景證據，不是本 Issue 的 GitHub CI 結果。
- GitHub Run `39643022423` 對應文件 Head `05abb68...`；其後整合 main 與本次驗收更新另有新 SHA。不得把舊 Run 說成新 Head 的 CI 結果。

## GPT Review Findings 復驗（2026-10-22）

### SHA 與執行版本

- Issue #139 程式碼 Commit：`6580be95d4ff541744065454ec9c80f87c619af6`。
- 前次驗收文件 Commit／本次 Node 24 Linux 測試執行時的 worktree Head：`05abb68f40f6002a11620f2614c5dd7b150f288d`。該 Head 相對程式碼 Commit 只有驗收文件變更；前端來源相同。
- 測試後整合的 `origin/main`：`24d17c1fea057def5577f8c6aeee0b47d8d2157a`，只帶入 AGENTS／README／協作文件；沒有前端程式碼變更。
- 測試結果只代表上述 Node 24 Linux worktree 來源版本；目前分支另含 main 合併與本驗收文件更新，須以新的交付 SHA 複審。

### Node 24 Linux 完整前端 Gate

- 環境：`node:24-bookworm-slim`，Node `v24.22.1`、npm `11.19.1`、Vitest `v4.1.11`；Vitest `jsdom`、`maxWorkers: 1`、全域 `testTimeout: 30_000`，指定 masterData 測例明確設 `40_000`。沒有與 Maven verify 或其他大型 Docker 建置同時執行。
- 指令順序：`npm ci` → `npm run lint` → `npm test -- --reporter=verbose` → `VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build`。原始完整輸出保存在 [`issue-139-node24-linux-full-rerun.log`](issue-139-node24-linux-full-rerun.log)。
- `npm ci`：PASS，213 packages audited、0 vulnerabilities。`npm run lint`：PASS，0 warnings／0 errors。前端完整 build：PASS，耗時約 0.85 秒；Vite 顯示既有大型 chunk 警告。
- 完整 Vitest：FAIL，24 files 中 23 passed、1 failed；205 tests 中 204 passed、1 failed；duration `981.05s`。唯一失敗是未修改的 `sp806.test.tsx`：`shows clarification without Confirm and locks unknown Confirm results without resending`，test timeout `30_000ms`，Vitest 顯示測例耗時 `31.817s`。
- Issue #139 指定案例 `masterData.test.tsx`／`keeps Supplier create and edit values when backend submissions fail`：本次完整 suite PASS，耗時 `27.977s`。前次同檔隔離執行 10/10 PASS；該案例記錄耗時 `28.13s`。本次完整 suite 沒有重現此案例 timeout。
- 最小化重跑：將完整 suite 唯一失敗的 SP806 案例以同一 Node 24 Linux 容器單獨執行，PASS，案例耗時 `20.705s`（Vitest duration `24.08s`）；原始輸出在 [`issue-139-node24-linux-sp806-isolated.log`](issue-139-node24-linux-sp806-isolated.log)。該檔其他 24 個案例因 `-t` 過濾未執行，這只作失敗定位，不能算完整 Gate。
- 測試資源樣本：每 5 秒記錄 129 筆，涵蓋約 `17:30:15–17:46:50 UTC`，見 [`issue-139-node24-linux-resource-samples.csv`](issue-139-node24-linux-resource-samples.csv)。宿主 CPU 平均 `21.9%`、最高 `68%`；可用記憶體最低約 `14.4 GiB`。Node 容器 CPU 平均 `106.8%`（約 1.07 核）、最高 `298.1%`；RSS 最高約 `1,014 MiB`，測試期間有回落，未見持續單調成長。
- 診斷結論：原先的 masterData timeout 本次完整 suite 未重現；該案例耗時接近但低於其 40 秒上限。沒有證據確認此前 timeout 是 CPU 競爭、JSDOM／Ant Design 成本、userEvent 或清理造成。完整 suite 的 timeout 現在落在既有 SP806 測例；該案例單獨重跑通過，但完整 suite 超時，顯示 suite 脈絡會影響耗時，尚未定位實際慢步驟或可重現根因。`tests/setup.ts` 有 `afterEach(cleanup)` 與 storage 清理；未修改 timeout、assertion、skip 或測試。
- Scope Conflict：SP806 測例與其 AI 助理業務程式不屬於 #139；未改動。前端完整 Gate 仍 FAIL，不能標為 READY。

### #139 與 #141 收貨範圍

- #139 只完成取消稽核／終態，以及第 5 欄 `FOLLOW_UP` 可取消、第 6 欄 `RECEIVING_INSPECTION` 不可取消的保護。`LEGACY_UNKNOWN` 同樣禁止取消。
- 已核實 `PurchaseOrder.validateReceivingEligibility()` 目前只檢查 `RECEIVED`／`ORDERED` lifecycle status，未強制 `orderedProgress == RECEIVING_INSPECTION`。
- 完整第 6 欄人工數量／品質驗收及 6→7 收貨准入由 #141 負責；此處只記錄待辦，不提前實作 #141。
- 本 Issue 沒有驗收 V2 完整收貨流程；不得將既有收貨交易測試或 #139 局部取消保護描述為完整流程 PASS。

## Vitest timeout 詳細紀錄

- 前端倉庫設定為單一 worker 與每個測例 30 秒 timeout；未更改 timeout、跳過或放寬斷言。
- 被停止的併行完整套件在 `sp806.test.tsx` 記錄 25 tests／7 timeouts（該檔 474.30 秒），在 `demandInventory.test.tsx` 記錄 12 tests／2 timeouts（該檔 127.03 秒）。該 run 與完整 Maven Testcontainers verify 同時執行，未完成總套件，不作 PASS。
- 依專案前次 Issue #137 紀錄要求隔離執行：本次 `sp806.test.tsx` 單檔 25 tests／21 passed／4 timed out，duration 456.28 秒。timeout 名稱與堆疊保存在本機 `frontend/issue-139-sp806.log`，不提交原始 stderr log；錯誤均為 Vitest `Test timed out`，不是 assertion mismatch。
- `demandInventory.test.tsx` 隔離執行 12/12 PASS，duration 118.99 秒。
- 前次 Node 24 Linux 完整 suite 的唯一 timeout 為 `masterData.test.tsx` 案例；其完整堆疊與摘要保存在忽略路徑 `backend/target/issue-139-frontend-ci.log`。該檔隔離重跑 10/10 PASS、145.53 秒，指定案例 28.13 秒，stack 在 `backend/target/issue-139-masterdata-test.log`。本次復驗的完整 suite 則只有 SP806 timeout，詳見上方及已提交完整 log。Windows SP-806 和併行全套 timeout 的詳細輸出保存在 `frontend/issue-139-sp806.log`／`frontend/issue-139-npm-test.log`。
- 不提高 timeout、不略過或刪除測例、不放寬斷言。Actions 暫停期間不查詢或依賴 GitHub Frontend CI；完整本機 Linux suite 有任何 timeout 均列為未通過項目。

## SP806 Node 24 Linux 效能診斷（2026-10-22）

### 執行版本與重現

- 程式碼／未修改測試版本：Feature Branch 原始 HEAD `cfe796859fab9c94d47cd57644e047de8c531ec3`；測例逐步計時期間只暫時插入診斷碼，執行後已還原，最終沒有正式測試檔差異。
- 環境：`node:24-bookworm-slim`，Node `v24.22.1`、npm `11.19.1`、Vitest `4.1.11`、jsdom、單一 worker、timeout 30 秒。診斷期間未執行 Maven verify 或 Docker image 建置。
- Linux SP806 單檔執行 `npm test -- tests/sp806.test.tsx --reporter=verbose`：24/25 通過；目標 `shows clarification without Confirm and locks unknown Confirm results without resending` 於 `30.048s` timeout。整檔耗時 `449.46s`（transform 4.87s、setup 2.77s、import 87.86s、tests 347.62s、environment 9.92s）；原始輸出見 [`issue-139-sp806-linux-single-file-timing.log`](issue-139-sp806-linux-single-file-timing.log)。
- 同 Node/Linux 目標案例隔離執行且只對同步 DOM 查詢加計時、未改變事件／await 順序：PASS，案例 `20.138s`；Vitest 總時間 `121.30s`，其中 import `87.28s`。輸出見 [`issue-139-sp806-linux-query-timing.log`](issue-139-sp806-linux-query-timing.log)。先前未插樁隔離結果為 `20.705s`，原始紀錄見 [`issue-139-node24-linux-sp806-isolated.log`](issue-139-node24-linux-sp806-isolated.log)。
- 單檔失敗 run 的逐步診斷顯示 `query + click submit proposal` 用時 `8.829s`、`find + click review button` `5.257s`、`query + click confirm` `1.336s`；失敗案例達 30 秒後，仍在執行的 `query + click discard` 又耗時 `2.403s` 才結束。案例只隔離時未 timeout，20.138～20.705 秒。
- 單檔執行期間的 `docker stats` 快照顯示 Node 容器約 `103.5–105.9%` CPU（約 1 核），記憶體快照最高 `982.4 MiB / 19.44 GiB`；同時運行的既有 Compose 服務快照均低於 `1%` CPU。該次沒有同步收集宿主機完整 CPU／可用 RAM 時序，不能據此宣稱排除所有資源競爭。

### 定位結果與 Scope Conflict

- 同步查詢插樁中，對全頁執行的 `getByRole` 提案按鈕查詢耗時 `4.376s`，`queryByRole` 確認審閱按鈕不存在耗時 `4.399s`，審閱按鈕 `getByRole` 耗時 `2.990s`；欄位 `getByLabelText` 約 `23–41ms`。Ant Design Drawer／全域 AI 助理仍掛在完整 App DOM 時，全頁角色查詢反覆掃描大型可及性樹，是有實測支持的主要熱點。
- 完整 Suite 的同一測例耗時 `31.817s` 並 timeout；隔離約 `20.1–20.7s`。差距約 11 秒。單檔完整執行也在 `30.048s` timeout。這些證據確認大 DOM 角色查詢與 UI 互動的累積成本，但尚未證明隔離與 Suite 的全部時間差由單一測例狀態、CPU 競爭或記憶體壓力造成。
- Vitest 全域 setup 有 `afterEach(cleanup)` 並清除 `localStorage`／`sessionStorage`；目標測例檔在每測例前清除 `localStorage`。SP806 測例檔未找到 fake timer 設定。診斷輸出有重複 Ant Design Drawer 樣式／`getComputedStyle` 警告；沒有證據顯示這些警告本身造成 timeout。
- 一次將 async `userEvent` 呼叫拆開包裝計時的隔離診斷出現「找不到 Proposal tab」；包裝改變了呼叫邊界，故該次不是原測例的有效 Gate，完整輸出另存 [`issue-139-sp806-linux-isolated-timing.log`](issue-139-sp806-linux-isolated-timing.log)，不以它判定產品或原測試失敗。同步查詢量測保留原事件順序，結果如上。
- 當時將 SP806 測試修正列為 `SCOPE CONFLICT`；使用者於 2026-10-22 對 `frontend/tests/sp806.test.tsx` 核准一次性有限範圍例外。依核准只在該檔將明確屬於 AI Drawer 的查詢限定於 Drawer，未修改產品程式或其他測試。
- 上述 `3a5b775` 歷史完整 Linux Vitest Gate 為 `FAIL`。修正後完整 Suite 結果及精準耗時見下方「核准的 SP806 查詢範圍修正與最終前端 Gate」；歷史結果不改寫。

## 核准的 SP806 查詢範圍修正與最終前端 Gate（2026-10-22）

### 變更與測試語義

- 使用者授權：僅修改 `frontend/tests/sp806.test.tsx`，把目標測例中 AI Drawer 內部的 tab、欄位、提案操作、確認鎖定狀態與快照 DOM 查詢改由 `within(drawer)` 搜尋。
- Drawer 以標題 `SupplyPilot AI 助理` 錨定，再取最近的 `.ant-drawer` 容器；查詢限定在實際 AI Drawer。
- 保留原有測試流程、所有欄位變更、userEvent／fireEvent 次數、HTTP Mock、Confirm 單次請求驗證、錯誤處理及所有斷言。Clarification 狀態驗證整個頁面沒有審閱按鈕的斷言仍為 `screen.queryByRole(...)`，沒有縮小成只查 Drawer。
- 變更程式碼 Commit：`aa317221c548bc70e6d3971216e9159afd0f5522`（`test: scope SP806 AI drawer queries`）。驗收文件與完整執行 log 將在後續文件 Commit 記錄；實際測試使用的工作樹程式碼內容與此程式碼 Commit 完全相同。基準版本為 Commit `3a5b7752cb5220276d07a86b18fac83376f746ec`。

### 精準測試與效能比較

- 環境：Docker `node:24-bookworm-slim`，Node `v24.22.1`、npm `11.19.1`、Vitest `v4.1.11`、jsdom；全域 timeout 維持 30 秒。測試期間沒有同時執行 Maven verify 或 Docker image 建置。
- 原未修改版目標案例：同 Node/Linux 設定下單獨 PASS，案例耗時 `20.885s`（Vitest 全程 `120.74s`）。原完整 Suite 中案例耗時 `31.817s` 並 timeout。
- 修正後目標案例單獨 PASS：案例 `14.963s`（Vitest 全程 `186.89s`）；相對原隔離案例減少 `5.922s`（約 28.4%）。完整輸出見 [`issue-139-sp806-target-after.log`](issue-139-sp806-target-after.log)。Vitest 匯入時間在兩次獨立執行間不同，故案例時長用於比較，總執行時間不視為性能改善指標。
- 修正後完整 `sp806.test.tsx`：25/25 PASS，總耗時 `443.34s`；目標案例 `19.674s`。完整逐案例輸出見 [`issue-139-sp806-file-after.log`](issue-139-sp806-file-after.log)。
- 全套完整 Suite 中同一目標案例 `19.458s` PASS，對照先前全套 `31.817s` timeout，減少 `12.359s`（約 38.8%）。此結果及同一套件內另一個先前超時案例的耗時保存在完整 verbose log。
- 執行中的唯讀 Docker 資源快照：SP806 單檔約 `105.63%` CPU、`917.2 MiB`；完整 Suite 快照約 `162.63%` CPU／`530.6 MiB`、`13.91%`／`314 MiB`、`9.55%`／`444.8 MiB`。Demo backend／MySQL 快照均低於 `1%` CPU。這些是間歇快照，並非全程宿主資源監控。

### 完整 Node 24 Linux 前端 Gate

- 指令順序：`npm ci` → `npm run lint` → `npm test -- --reporter=verbose`（完整 Vitest，保留逐案例耗時）→ `VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build`。
- `npm ci`：PASS，新增安裝 212 packages、審核 213 packages；npm audit 回報 2 moderate vulnerabilities。完整輸出見 [`issue-139-frontend-npm-ci.log`](issue-139-frontend-npm-ci.log)。
- `npm run lint`：PASS，113 files、0 warnings、0 errors；輸出見 [`issue-139-frontend-lint.log`](issue-139-frontend-lint.log)。
- 完整 Vitest：PASS，24/24 test files、205/205 tests；沒有 timeout。Vitest duration `2170.88s`（transform 5.88s、setup 67.31s、import 943.21s、tests 892.66s、environment 237.73s）。完整逐案例輸出見 [`issue-139-frontend-vitest.log`](issue-139-frontend-vitest.log)。
- 前端 build：PASS，`tsc -b` 與 Vite production build 成功，建置 8.60 秒；保留既有 1,501.94 kB JavaScript chunk 大小警告。輸出見 [`issue-139-frontend-build.log`](issue-139-frontend-build.log)。
- 本輪的測試與 Gate 對應上述 `aa31722` 程式碼內容；驗收文件 Commit 是本次文件與 log 提交，不改動測試程式。`git diff --check` 在程式 Commit 前通過，文件更新後會再次執行。
- 本次僅驗證使用者指定的完整前端 Gate；Backend 與 Docker／Compose 沒有因測試選擇器修改而重跑。既有歷史結果維持其原版本紀錄，不視為本次重新執行。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`；本輪未查詢、觸發、重跑或依賴 Actions。Push 可能觸發自動 Workflow；未宣稱 GitHub Repository Actions 已停用。
- 已核實 `PurchaseOrder.validateReceivingEligibility()` 尚未強制 `RECEIVING_INSPECTION`。#139 只完成取消及第 5／6 欄取消保護；完整第 6 欄人工驗收與 6→7 收貨准入由 #141 負責。本次未實作 #141，也不宣稱 V2 完整收貨流程通過驗收。
- 本輪限定修改沒有剩餘 `SCOPE CONFLICT`；已知風險為本機 npm audit 的 2 moderate vulnerabilities、既有大型前端 chunk 警告，以及 Vitest 完整 Suite 總耗時偏長。均未改動相應套件、測試標準或 timeout。
