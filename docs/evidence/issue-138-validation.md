# Issue #138（V2-201）實作與驗證證據

> 狀態：本機驗證通過；已推送 Feature Branch，但無法從目前 GitHub Actions 連線取得 push workflow run/job 證據。此紀錄只反映本 Issue Feature Branch 的實際結果；不能代表其他 V2 Issue、AWS 或正式 Demo 驗收。

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
| Build | `cd frontend; npm run build` | PASS；Vite 輸出 bundle 大於 500 kB 的分包建議警告 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| Lint | `cd frontend; npm run lint` | PASS | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| GitHub Actions | 推送後查詢 `github_fetch_commit_workflow_runs` 與 commit combined status；目前工具只列 PR 觸發 runs。`gh` CLI 未安裝，匿名 GitHub Actions REST API 回 404 | 此 SHA 的 workflow runs/jobs/statuses 均未能由可用介面取得；CI 證據 `NOT AVAILABLE`，不得視為 PASS | `00d62913d3539d7f36dbbdb8e68c5f548d1b6566` |
| Migration/隔離資料庫 | 無 Schema 變更；Testcontainers MySQL 8.4.11 驗證乾淨 V1–V24 Migration | PASS；未執行 Demo DB 操作 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |
| `git diff --check` | `git diff --check` | PASS；只有 Git 的 LF→CRLF working-copy 提示 | `6d5c890a7746e0192991387f5ca4c2c325208b8d` 的程式碼樹 |

## 風險與範圍

- P2：既有「舊 V1 角色」可保留 Demand/Recommendation 相容操作；V23 後的 V2 帳號皆為 ADMIN，後端不授權其執行這些新寫入。
- P2：PO 仍要求 ACTIVE SupplierMaterial 關聯；本 Issue 將此保留為有效供應關係驗證，沒有擅自移除。
- P2：GitHub Actions push workflow 的實際 run/job 狀態無法從目前連線介面取得；本機測試不代表 GitHub CI PASS。
- #139～#144 的 CANCELLED、供應商承諾交期、整筆驗收/付款、Stage/Kanban、搜尋欄位不在本次實作範圍；既有 PO API/Domain 保持可供後續擴充。
- 本次未執行 AWS Deployment、Demo Reset、Production 資源修改或真實 Gemini/Browser/AWS E2E。
