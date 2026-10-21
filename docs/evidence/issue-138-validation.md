# Issue #138（V2-201）實作與驗證證據

> 狀態：本機完整 CI 等價驗證通過，對應程式碼 Commit `00016df2a1e96c98bfdd60c29f658c9938a353ba`；GitHub Actions Run `39620107663`（Commit `0157918240ff2a31db8aae7027215dca895ef42e`）的 `Detect CI scope` 在無 Runner／無步驟下失敗，Backend、Frontend、Container jobs 均 Skipped，未執行測試。前一 Run `39602579538` 的 Check annotation 明確指出付款失敗或 Actions spending limit 阻擋，因此本機驗收不等於 GitHub CI PASS，Billing 限制仍未證實解除。舊 Run `39598790523`、`39602321149` 亦有相同阻擋。此紀錄只反映本 Issue Feature Branch 的實際結果；不能代表其他 V2 Issue、AWS 或正式 Demo 驗收。

## 基線與差異分析

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#138](https://github.com/liujiawei-tw/supplypilot/issues/138)，開啟中
- Base：`origin/main`，PR #154 Merge Commit `7ec5a3826c929cbbe3c10ec8325ad97208a2bbf5`
- Feature Branch：`codex/issue-138-v2-201`
- 依賴：#135、#136、#137 已關閉；PR #154 的 Merge Commit 已位於 `origin/main`。

| 分類 | 基線實際狀態 | 本 Issue 處理 |
|---|---|---|
| 已符合、保留 | `POST /purchase-orders` 已建立持久化 DRAFT、正式 PO ID/Number；交期可空；核准檢查日期；Domain 要求有效供應商/品項/數量/單價；PO 有 `@Version`；收貨使用既有交易；#154 已提供負責人及操作者快照 | 沿用既有 API、Domain、Flyway 與交易流程 |
| 部分符合、修正 | Draft Edit 以完整 item 清單替換資料，但 Entity 會將未帶回的 Recommendation ID、快照及覆寫原因清空；編輯 Service 也重新依賴推薦資料檢查 | Draft Edit 僅更新商業欄位，歷史來源快照保持不可變；編輯不再查詢/驗證 Recommendation 記錄；編輯與核准共用 PO `PESSIMISTIC_WRITE` 列鎖並保留 `@Version` |
| 尚未實作、補齊 | V2 ADMIN 仍可使用舊 Demand 寫入及 Recommendation 建立 Service；舊入口仍在 Sidebar/Dashboard；PO 表單提交隱藏推薦欄位 | V2 ADMIN 寫入受 Service 權限拒絕；移除 V2 導覽/快速操作；新 PO 表單不提交推薦參照 |
| V1 退場策略 | Demand/Recommendation/Ranking 有歷史資料、Recommendation FK 及查詢/AI相容路徑 | 不刪表、不刪資料、不移除 FK；保留讀取；Demand/Recommendation 寫入不授權 V2 ADMIN；舊 V1 角色保留相容行為 |
| 相容性風險 | 已有推薦來源 PO 可能由完整清單編輯被改寫；SupplierMaterial 狀態影響建立/更換項目 | 增補歷史快照保留驗證；SupplierMaterial 維持 ACTIVE 供應關係驗證，單價改由 Draft 的手動/暫估輸入 |

## 修改內容

- 後端 Demand 寫入 Service 僅保留舊 `PRODUCTION_PLANNER` 相容授權，Recommendation 建立 Service 排除 V2 `ADMIN`；歷史讀取不變。
- Draft Edit 僅更新數量、單價、單位等商業資料，保留原 Recommendation FK、來源快照與覆寫原因；不再要求歷史 Recommendation 資料可用才能編輯。
- V2 ADMIN 可讀取已知 ID 的舊 Recommendation 詳情以追溯快照；該頁不載入 Supplier Ranking、不提供依推薦建立草稿，Recommendation 建立工作區仍拒絕 ADMIN。舊 Ranking/草稿測試改用仍獲授權的採購人員身分。
- 前端 Sidebar 與 Dashboard 不再提供 Demand/Recommendation/Ranking 新業務入口；PO 草稿表單建立/編輯均不要求或提交推薦資料。PO 詳情將舊來源標為歷史決策依據。
- 保留建立者預設負責人、認領/改派、操作人員快照及現有 409/未知結果查核流程。
- ACTIVE SupplierMaterial 關聯仍視為有效供應關係的 Domain 驗證，不移除；尚無規格批准取消此條件。

## Migration 與資料保留

- 不需要新增 Migration：既有 V9 PO Schema 已允許 `expected_delivery_date NULL`、PO item 數量/單價與 nullable Recommendation 欄位；現有 Domain/API/DB 已滿足直接建立 DRAFT。
- 未修改 V1–V24 Flyway SQL；V23/V24 Checksum 與 PR #154 相容性不變。
- 未刪除 Demand、Purchase Recommendation、Supplier Recommendation Result、PO、PO item、Inventory、StockTransaction、User Snapshot、Document 或 RAG Index 資料；未執行 Demo Reset。
- 舊 PO Recommendation 外鍵與讀取查詢仍保留；Draft Edit 無法清除其歷史來源快照。

## 驗證結果

| 類別 | 指令 / 證據 | 結果 | SHA |
|---|---|---|---|
| 後端精準測試 | `cd backend; .\mvnw.cmd '-Dtest=PurchaseOrderUpdateIntegrationTests,DemandControllerIntegrationTests,RecommendationControllerIntegrationTests,PurchaseOrderLifecycleIntegrationTests' test` | PASS，23 tests，0 failures/errors/skips；Testcontainers MySQL 8.4.11 驗證 V1–V24 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| 後端 PO controller/update | `cd backend; .\mvnw.cmd '-Dtest=PurchaseOrderControllerIntegrationTests,PurchaseOrderUpdateIntegrationTests' test` | PASS，14 tests，0 failures/errors/skips；Update suite 重跑 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| 後端鎖定/lifecycle/收貨併發 | `cd backend; .\mvnw.cmd '-Dtest=PurchaseOrderUpdateIntegrationTests,PurchaseOrderLifecycleIntegrationTests,PurchaseOrderControllerIntegrationTests,PurchaseOrderConcurrentReceivingIntegrationTests' test` | PASS，24 tests，0 failures/errors/skips；含收貨併發與重複收貨驗證 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| T13 編輯／核准並發 | `cd backend; .\mvnw.cmd '-Dtest=PurchaseOrderUpdateIntegrationTests' test` | PASS，7 tests，0 failures/errors/skips；並發 PATCH/核准後狀態為 APPROVED，若更新未先提交則原品項數量不變；接受 `PO_NOT_EDITABLE` 或 `CONCURRENT_MODIFICATION` 衝突契約 | `00d62913d3539d7f36dbbdb8e68c5f548d1b6566` |
| T11 人工供應商評分 | `cd backend; .\mvnw.cmd '-Dtest=SupplierControllerIntegrationTests' test` | PASS，5 tests，0 failures/errors/skips；驗證 0–100 分數與越界拒絕 | `585f54cda848d6385be773e8ddc53c77e57d9733` 的程式碼樹 |
| 前端精準測試 | `cd frontend; npm test -- --maxWorkers=1 --no-file-parallelism tests/dashboard.test.tsx tests/v2Authorization.test.ts tests/purchaseOrderApi.test.ts` | PASS，3 files，13 tests；受限沙盒 Temp rename EPERM 後在授權環境重跑通過 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| 前端 Recommendation 歷史詳情與授權 | `cd frontend; npm test -- --maxWorkers=1 --no-file-parallelism tests/recommendation.test.tsx tests/v2Authorization.test.ts` | PASS，2 files，28 tests；含 ADMIN 歷史唯讀、不得載入排名、不可進入建立工作區，以及既有採購人員 Ranking/草稿流程 | 本次 GPT Review 修正程式碼樹 |
| 前端 ADMIN 歷史詳情精準測試 | `cd frontend; npm test -- --maxWorkers=1 --no-file-parallelism tests/recommendation.test.tsx -t "allows V2 ADMIN to read a historical recommendation"` | PASS，1 passed，25 skipped | 本次 GPT Review 修正程式碼樹 |
| 後端 Recommendation 快照保留 | `cd backend; .\mvnw.cmd '-Dtest=PurchaseOrderUpdateIntegrationTests' test` | PASS，7 tests，0 failures/errors/skips；涵蓋 Recommendation FK、Supplier Recommendation Result ID、原建議供應商/數量、供應商/數量覆寫原因 | 本次 GPT Review 修正程式碼樹 |
| 前端 Build | `cd frontend; npm run build` | PASS；Vite bundle 大於 500 kB 的分包建議警告 | 本次 GPT Review 修正程式碼樹 |
| 前端 Lint | `cd frontend; npm run lint` | PASS | 本次 GPT Review 修正程式碼樹 |
| Build | `cd frontend; npm run build` | PASS；Vite 輸出 bundle 大於 500 kB 的分包建議警告 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| Lint | `cd frontend; npm run lint` | PASS | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| GitHub Actions Run `39598790523` | [CI Merge Gate](https://github.com/liujiawei-tw/supplypilot/actions/runs/39598790523)，push event，branch `codex/issue-138-v2-201`，SHA `b4615ef8324a8055fe5421b7fe0818f6a3e4430c`；GitHub Check Run annotation 顯示 `The job was not started because recent account payments have failed or your spending limit needs to be increased. Please check the 'Billing & plans' section in your settings` | `Detect CI scope` = Failure，runner 未分配且 steps 為空；Backend CI、Frontend CI、Container image and Compose validation = Skipped（依賴 scope job）。因此該 Run 沒有執行任何 Backend/Frontend/Container 測試，不能標記為測試失敗或 PASS。最小修復：Repository/Organization owner 在 GitHub Settings 的 Billing & plans 修正付款或提高 Actions spending limit，之後重跑 Workflow。 | `b4615ef8324a8055fe5421b7fe0818f6a3e4430c` |
| GitHub Actions Run `39602321149` | [CI Merge Gate](https://github.com/liujiawei-tw/supplypilot/actions/runs/39602321149)，push event，branch `codex/issue-138-v2-201`，SHA `e29e28afcf5f3f9b6ad2ecd3e4b975aac7d6a0be`；GitHub Check Run annotation 顯示與 Run `39598790523` 相同的 account payments/spending limit 訊息 | `Detect CI scope` = Failure，runner 未分配、steps 空白；Backend CI、Frontend CI、Container image and Compose validation = Skipped。該 SHA 的 Workflow 仍未執行 CI 測試。新 SHA 的後續本機證據：Backend 7/7、Frontend 28/28、Build PASS、Lint PASS；這些本機結果不等於 GitHub CI Gate。Billing 修復後仍須對最終 Feature Branch Head 重跑完整 CI。 | `e29e28afcf5f3f9b6ad2ecd3e4b975aac7d6a0be` |
| Migration/隔離資料庫 | 無 Schema 變更；Testcontainers MySQL 8.4.11 驗證乾淨 V1–V24 Migration | PASS；未執行 Demo DB 操作 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| `git diff --check` | `git diff --check` | PASS；只有 Git 的 LF→CRLF working-copy 提示 | 本次 GPT Review 修正程式碼樹 |

## 本機完整 CI 等價驗證

- 日期：2026-10-21（Asia/Taipei）。本次完整本機驗證使用相同 Feature Branch 的修改工作樹，程式碼與測試 SHA 為 `00016df2a1e96c98bfdd60c29f658c9938a353ba`；程式碼變更包括後端測試修正及前端 V2 PO 歷史來源斷言修正。最終 Head 後續只增加本驗證文件。
- 環境：Windows；Java 21.0.10 (Zulu)、Maven Wrapper 3.9.16、Node.js 24.15.0、npm 11.12.1、Python 3.12.14、Docker Desktop/Engine 29.6.1；Testcontainers 啟動隔離 MySQL 8.4.11 與 PostgreSQL 17.11。
- Backend CI 等價：`cd backend; .\mvnw.cmd -B --no-transfer-progress verify` — **PASS**，647 tests，0 failures，0 errors，1 skipped，Maven `BUILD SUCCESS`，耗時約 10 分 48 秒。測試 XML/文字報告位於 `backend/target/surefire-reports/`。唯一 skipped 為需明確 opt-in 的 `SemanticRealProviderSmokeTests.developerApiReturnsConfiguredDimension`（`semantic.real-smoke=false`；不呼叫付費真實 Gemini）。首次完整 Verify 暴露 4 個過期測試設定問題（兩個 PO service mock 未 stub `findByIdForUpdate`、兩項舊 Demo smoke 仍以 V2 ADMIN 呼叫受限 Recommendation API）；修正測試 fixture/stub 後，精準測試 13/13 PASS，完整 Verify 再 PASS。未修改採購業務邏輯。
- Frontend CI 等價：`cd frontend; npm ci` — PASS（212 packages，audit 0 vulnerabilities）；`npm run lint` — PASS；`npm test` — **PASS**，24 files、202 tests 全通過，耗時 1057.94 秒；`$env:VITE_API_BASE_URL='http://localhost:8080/api/v1'; npm run build` — PASS。Build 有 Vite bundle 大於 500 kB 的分包建議警告，不影響成功結果。
- 前端重跑紀錄：第一次 `npm test` 與完整 Maven Verify 併行，24 files 中 5 files/14 tests 失敗（含 timeout/render assertions）。依 AGENTS.md 對失敗檔案逐檔定位重跑；初次單檔重跑中 sp805 的兩項非偶發斷言確認與 V2 API 契約不符，修正測試為「前端只送商業欄位、歷史推薦快照仍由詳情顯示」；timeout 及其他失敗檔案定位重跑皆通過。最後停止其他重負載工作後，整套 24 files/202 tests 重跑全部 PASS，未調高 timeout、略過或刪除測試。
- Container/Compose：`docker info` — PASS；`python -m unittest scripts.tests.test_compose_config -v` — PASS，4 tests；以臨時非真實 CI Placeholder 環境變數檔執行 Production、Demo、Development 三種 `docker compose config --quiet` — 全部 PASS；`docker build --file backend/Dockerfile --tag supplypilot-backend:ci .` — PASS；`docker build --file frontend/Dockerfile --build-arg VITE_API_BASE_URL=/api/v1 --tag supplypilot-frontend:ci .` — PASS。僅執行 Compose config 驗證，未執行 `up`、`down -v`、持久化 Demo Volume 操作或 Demo Reset。完整後端測試中的 Demo Reset 整合測例僅操作 Testcontainers 建立的隔離測試資料庫，不涉及持久化 Demo Volume。
- Git：`git diff --check` 與 `git diff --check origin/main...HEAD` 均 PASS。本機測試結果對應程式碼 Commit `00016df2a1e96c98bfdd60c29f658c9938a353ba`；最終 Head 後續僅包含本驗證文件變更。`AGENTS.md` 亦為流程政策文件，不改變程式碼測試對應 SHA。
- GitHub Actions：Run [39620107663](https://github.com/liujiawei-tw/supplypilot/actions/runs/39620107663) 對應文件 Commit `0157918240ff2a31db8aae7027215dca895ef42e`：`Detect CI scope` = Failure，`runner_name` 空白且 `steps` 為空；Backend CI、Frontend CI、Container image and Compose validation = Skipped。這次沒有執行測試。前一 Run [39602579538](https://github.com/liujiawei-tw/supplypilot/actions/runs/39602579538) 對應文件 SHA `f29fbfb59ee2a9e3a9bc1312366b325dad50e33b`，Check annotation 明確表示 recent account payments failed or spending limit needs increase。新 Run 的失敗拓撲相同，Billing 限制尚無已解除證據；狀態為 **BLOCKED_BY_BILLING / GitHub Actions 無有效測試結果**，不是程式測試 FAIL 或 PASS。需 Repository/Organization Owner 至 GitHub Settings → Billing & plans 修復付款狀態或 Actions spending limit；本次未變更帳務或 Workflow。
- 本機完整驗證證明此工作樹通過 CI 等價測試，但不等於 GitHub CI PASS。免費額度／帳務恢復且可有效執行 Workflow 後，須恢復 GitHub CI Gate。GPT 仍須獨立審查程式與上述證據，才可給出 `FINAL PASS — LOCAL VERIFIED / GITHUB CI PENDING`；若 Branch Protection 要求 GitHub Status Checks，不得繞過。

## 風險與範圍

- P2：既有「舊 V1 角色」可保留 Demand/Recommendation 相容操作；V23 後的 V2 帳號皆為 ADMIN，後端不授權其執行這些新寫入。
- P2：PO 仍要求 ACTIVE SupplierMaterial 關聯；本 Issue 將此保留為有效供應關係驗證，沒有擅自移除。
- P2／CI 環境阻擋：Run `39598790523` 與 `39602321149` 的 GitHub Check annotation 均明確指出帳務付款失敗或 Actions 支出上限需提高；workflow 未分配 runner，三個驗證 jobs 全部 Skipped。這是 GitHub 執行環境阻擋證據，不能藉由修改 workflow 或縮減驗證排除。修正帳務後須在最終 Feature Branch SHA 取得 Backend、Frontend、Container jobs 有效結果。
- #139～#144 的 CANCELLED、供應商承諾交期、整筆驗收/付款、Stage/Kanban、搜尋欄位不在本次實作範圍；既有 PO API/Domain 保持可供後續擴充。
- 本次未執行 AWS Deployment、Demo Reset、Production 資源修改或真實 Gemini/Browser/AWS E2E。
