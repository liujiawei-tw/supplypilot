# SP-901 Demo Data 與 E2E

## 交付定位

- Issue：[SP-901 #43](https://github.com/liujiawei-tw/supplypilot/issues/43)
- Dataset Version：`sp-905-zh-tw-v1`（五份 baseline 文件已完成繁體中文化；舊 `sp-901-v1` 不可視為相同語料）
- Profile：僅 `demo`，且 `supplypilot.demo-data-enabled=true`
- Date Anchor：啟動時由 Spring `Clock` 取得，或以 `DEMO_BASELINE_DATE` 指定；實際值寫入 `demo_dataset_state.baseline_date`
- 所有公司、文件、需求與報價條件均為虛構資料。

## Baseline Dataset

| 類別 | 數量／內容 |
| --- | --- |
| Users | 5：`planner`、`buyer`、`manager`、`warehouse`、`admin` |
| Materials | 12；`MAT-001` 至 `MAT-012`，`MAT-005` 為 INACTIVE |
| Suppliers | 6；`SUP-001` 至 `SUP-006`，`SUP-004` 為 INACTIVE |
| SupplierMaterial | 30；含 Testing Strategy 的 MAT-001 五組條件，其中 SUP-004 關聯為 INACTIVE |
| Demand | 12；日期皆相對於 baseline date |
| Purchase Orders | DRAFT 2、APPROVED 2、ORDERED 3、RECEIVED 2、REJECTED 1 |
| Documents | 五種 DocumentType 各一份；原檔 Upload、Storage、Ingestion 後為 READY |

Demo 帳號密碼只接受執行環境的 `DEMO_ACCOUNT_PASSWORD`，至少 12 字元；Repository 不提供預設明文密碼。五個角色共用此密碼是本機受控 Demo 的便利設計，AWS 對外帳號交付仍依 SP-903 的單一經理帳號策略處理。

## Standard Scenario B

查詢區間為 baseline date 至 baseline date + 30 天：

```text
MAT-001 Expected Demand  = 1000 KG
MAT-001 Safety Stock     =  200 KG
MAT-001 Current Inventory=  300 KG
MAT-001 Incoming ORDERED =  150 KG
Net Requirement          =  750 KG
```

MAT-001 供應條件：SUP-001 為 800 KG、SUP-002 為 750 KG、SUP-003 為 1000 KG；SUP-004 因供應商及關聯停用不得成為有效候選。所有 Recommendation、Ranking 與 Snapshot 均由既有 Calculator／Service 產生，不由 Seeder 寫入結果。

初始 PO 分布由受控 fixture 在單一 relational transaction 內呼叫既有 Domain lifecycle method 建立；這只用於重現基線狀態。可操作的 Happy Path 及其自動化驗證仍走既有 Recommendation／Purchase Order Service 與 HTTP API，不另建捷徑。

## CSV Samples

路徑為 `samples/`，Header 固定為 `material_code,quantity,unit`：

- `inventory-valid.csv`
- `inventory-invalid-unit.csv`
- `inventory-invalid-quantity.csv`
- `inventory-duplicate-material.csv`
- `inventory-mixed-errors.csv`

操作：以 `warehouse` 或 `admin` 登入 `/inventory/import`，先 Preview；只有 `VALIDATED` 可 Confirm。`DemoSeedSmokeIntegrationTests` 使用真實 API 驗證一個成功 Confirm 與一個 `VALIDATION_FAILED`。

## 五份 Baseline 文件

| 檔名 | DocumentType | Version | Supplier | 預期問題 |
| --- | --- | --- | --- | --- |
| `supplier-contract-sup-001-v1.txt` | SUPPLIER_CONTRACT | 1.0 | SUP-001 | MAT-001 標準交期為何？ |
| `quotation-sup-002-v1.txt` | QUOTATION | 1.0 | SUP-002 | 報價有效多久？ |
| `procurement-sop-v1.txt` | PROCUREMENT_SOP | 1.0 | 無 | 哪個角色執行收貨？ |
| `product-specification-mat-001-v1.txt` | PRODUCT_SPECIFICATION | 1.0 | SUP-001 | MAT-001 單位及儲存條件為何？ |
| `quality-requirement-v1.txt` | QUALITY_REQUIREMENT | 1.0 | SUP-003 | 哪些缺陷需隔離批次？ |

Seeder 使用既有 `DocumentService.upload` 與 `DocumentIngestionService.ingest`，不是直接插入 READY metadata。五份文件在初始階段只可聲稱 **Document READY**；Seeder 不會自動核准出站、寫入向量或偽造 Gate PASS。

## Semantic RAG 準備流程

以 `admin` 依 `semantic-rag-runbook.md` 執行：

1. 逐份 `POST /api/v1/semantic-index/documents/{id}/approve-outbound`。
2. `POST /api/v1/semantic-index/backfill?afterId=0&limit=25`，直到 `morePossible=false`，再從 0 重跑一次處理可重試失敗。
3. `GET /api/v1/semantic-index/readiness`，確認 READY 至少一份且 FAILED／REQUIRES_REVIEW／PENDING 為零。
4. `POST /api/v1/semantic-index/gate-audit`，確認 `passed=true`。
5. 明確以 `SEMANTIC_READ_ENABLED=true` 重啟後才查詢，並核對 `DocumentQueryResponse.sources` 的 document ID、version、chunk 及 offset。

預期不足證據問題：「這些文件是否規定冷凍海運的保險費率？」應為 `INSUFFICIENT_EVIDENCE`。新增或變更 READY 文件後 Gate 需重新 Backfill 與 Audit，不能沿用舊 PASS。

### 真實 Gemini 安全執行前提

真實 Gemini 尚未於本 Issue 工作環境實測。執行人必須使用未追蹤的 shell 環境變數或部署 Secret 注入下列設定，不得把值寫入 Repository、終端輸出、測試報告或截圖：

- `AI_CHAT_PROVIDER=google-genai`
- `AI_EMBEDDING_PROVIDER=google-genai`
- `GEMINI_API_KEY`（或既有相容別名 `AI_API_KEY`）
- `GEMINI_MODEL`、`GEMINI_EMBEDDING_MODEL=gemini-embedding-001`
- `SEMANTIC_DIMENSIONS=768`、與模型／維度一致的 `SEMANTIC_GENERATION_KEY`
- 專用 pgvector 的 `SEMANTIC_DB_URL`、`SEMANTIC_DB_USERNAME`、`SEMANTIC_DB_PASSWORD`

先保持 `SEMANTIC_READ_ENABLED=false` 啟動。以 `admin` 檢視五份 READY 原文並逐份核准出站，完成 Backfill、Readiness 及 Gate Audit 後，才以 `SEMANTIC_READ_ENABLED=true` 重啟並執行 Gemini Chat／Source／Version Query。新增第六份 READY 文件會使舊 Gate 證據失效；必須重新核准該版本、Backfill、Readiness、Gate Audit，確認 Gate 恢復後再查詢。執行完畢後從 shell 移除金鑰；若 provider、金鑰或 pgvector 任一缺少，狀態維持 `BLOCKED`，不得改用 Fake provider 取代真實證據。

## 第六份 UI 文件流程

第六份 `samples/documents/ui-upload-sup-005-v1.txt` 不會被 Seeder 建立。完整演示：

1. 以有上傳權限的 `buyer` 或 `admin` 登入本機 UI。
2. Documents 選擇 `SUPPLIER_CONTRACT`、SUP-005、Title `SUP-005 Seasonal Delivery Addendum`、Version `1.0`，上傳該檔案。
3. 執行 Ingestion 並等到 Document READY。
4. 以 `admin` 審核出站、Backfill、Readiness、Gate Audit。
5. 啟用／確認 Semantic Read 後詢問「SUP-005 應何時回報季節性交期變更？」；預期來源是此文件 Version 1.0，依據為交期前兩個工作天。
6. 記錄 Upload、Ingestion、Review、Backfill、Gate 恢復與 Query 的實際耗時；不得預先宣稱完成時間。

## 仍需人工完成的 Browser E2E Checklist

以下項目只有在同一次隔離 Demo 環境以真實 UI／Backend 完成並保存結果後才能勾選；API test、Component test 或 screenshot 不能代替 Browser E2E：

- [ ] 以 `planner`、`buyer`、`manager`、`warehouse`、`admin` 實際登入，確認導覽與頁面按角色顯示；另以匿名請求確認 401，並確認錯誤角色操作回 403。
- [ ] 以 `buyer` 查詢 MAT-001，區間為 baseline date 至 +30 天，核對 Recommendation 與 Supplier Ranking 顯示 `1000 + 200 - 300 - 150 = 750 KG`。
- [ ] 以 `buyer` 建立含 Recommendation 證據的 DRAFT 並補交期；以 `manager` Approve；切回 `buyer` Mark Ordered；以 `warehouse` Full Receive。
- [ ] 核對 PO 為 RECEIVED、Actor／時間稽核正確、MAT-001 Inventory 由 300 增為 1050 KG，且只有一筆對應 PO 的 `PURCHASE_RECEIVE 750 KG` StockTransaction；重複收貨必須回 409。
- [ ] 修改 SupplierMaterial 主檔後重新開啟 Recommendation Detail，確認已保存的 quantity、unit price、score 與排名 Snapshot 不變。
- [ ] 以 `admin` 開啟 Documents，逐一檢視五份 Baseline 的 DocumentType、Supplier、Version、READY 狀態、Metadata 與原檔；使用真實 Gemini Query 核對五份文件的 `sources` document ID、version、chunk 與 offset。
- [ ] 對五份 Baseline 逐份執行 ADMIN Outbound Approval，真實 Gemini Embedding Backfill 至 `morePossible=false`，Readiness 無 FAILED／REQUIRES_REVIEW／PENDING，Gate Audit `passed=true`；再驗證不足證據問題回 `INSUFFICIENT_EVIDENCE`。
- [ ] 確認第六份文件在 Reset／Seed 後不存在；從 UI Upload `samples/documents/ui-upload-sup-005-v1.txt`，Explicit Ingestion 到 READY，再執行 ADMIN Review → Backfill → Readiness → Gate Audit，確認 Gate 恢復。
- [ ] 查詢「SUP-005 應何時回報季節性交期變更？」，確認答案依據為交期前兩個工作天，且 Source 指向第六份文件 Version 1.0。
- [ ] 在專用 Demo MySQL、pgvector 與文件目錄上執行 guarded reset；Reset 前記錄操作後狀態，Reset 後確認五帳號、12/6/30/12 基線數量、10 張基線 PO 分布、MAT-001 750 KG、五份 READY 文件恢復、第六份不存在、舊 semantic index／Gate 已清空且必須重新核准與建索引。

## 自動化證據與限制

- `DemoDataInitializerActivationTests`：demo／flag／prod isolation，以及一般初始化與單次 reset command 互斥。
- `DemoSeedTargetSafetyIntegrationTests`：首次 seed 的實際 MySQL database 與 Demo 原始文件路徑 fail-closed；拒絕時 dataset state、users、metadata 與 filesystem 零寫入。
- `DemoNonDemoDatabaseSafetyIntegrationTests`：即使使用者把共用 database 名稱原樣當作 allowed 值，Seed／Reset 仍拒絕，且既有資料列與原檔不變。
- `DemoResetCommandRunnerTests`：一次性 Reset 成功／失敗退出生命週期與 context 關閉。
- `DemoSeedSmokeIntegrationTests`：MySQL Testcontainers、資料量與狀態、冪等、partial-state fail closed、750 KG 真實 Recommendation API、完整採購 API、CSV success/failure、文件 READY。
- `DemoResetIntegrationTests`：MySQL、文件原檔及 PostgreSQL Gate／index 清除後重建；完成後的一般初始化不會再次清除操作後資料。
- 自動測試不呼叫付費 Gemini。Fake provider 的既有 Semantic 測試不能代替真實 Gemini。
- Browser E2E、真實 Gemini Chat／Embedding、實際 Semantic Gate、AWS 定時重置與 AWS 部署必須以當次實測結果另行標記 PASS／FAIL／NOT RUN／BLOCKED。

## 2026-10-14 實際驗證結果

| 驗證 | 結果 | 證據／限制 |
| --- | --- | --- |
| Backend full regression | PASS | `backend\\.\\mvnw.cmd -B --no-transfer-progress verify`：526 tests、0 failures、0 errors、1 skipped，BUILD SUCCESS；skipped 為未提供金鑰的真實 Gemini smoke |
| P1 targeted Backend | PASS | `-Dtest=DemoDataInitializerActivationTests,DemoResetCommandRunnerTests,DemoNonDemoDatabaseSafetyIntegrationTests,DemoSeedTargetSafetyIntegrationTests,DemoResetIntegrationTests,DemoSeedSmokeIntegrationTests test`：18 tests、0 failures、0 errors、0 skipped；包含非 Demo database 即使列為 allowed 仍拒絕、storage leaf 隔離、一次性 Reset 與 5 個 SP-901 smoke |
| 實際一次性 Reset CLI | PASS | 隔離 MySQL `supplypilot_demo_p103`、pgvector `supplypilot_semantic_demo_p103`、storage `sp901-demo-reset-storage` 實跑 `scripts/reset-demo.ps1`；log 證明啟動 `DemoResetApplication` 而非 Web Application，兩次成功皆自行結束且 exit code 0；MAT-001 999 恢復 300、五份 READY 文件及 12/6/30/12、10 張 PO 基線恢復、原檔 UUID 重建、第六份不存在 |
| Reset 失敗與一般重啟 | PASS | 錯誤 PostgreSQL credential 實跑 exit code 1，既有五份文件與 relational baseline 保留；預設 Maven profile 啟動 log 為 `SupplyPilotApplication`，MAT-001 人工改為 999 後重啟仍為 999，未再次刪除資料 |
| Frontend dependency install | PASS | `npm ci`：212 packages、0 vulnerabilities；第一次 npm 自身 `Exit handler never called`，獨立重跑成功 |
| Frontend lint | PASS | `npm run lint` |
| Frontend tests | PASS | `npm test -- --run`：18 test files、149 tests PASS；首次 sandbox run 因 Windows temp cache rename `EPERM` 而 0 tests，受控重跑實際執行 149 tests 並 exit code 0 |
| Frontend production build | PASS | `npm run build`；有既存的 >500 kB chunk warning |
| Secret pattern scan | PASS | 未發現 Gemini／AWS key 或 private key pattern |
| 真實 Gemini Embedding／Chat | BLOCKED | 未提供可用 API key；依規格不讀取、不輸出、不假造付費呼叫結果 |
| 真實 Semantic Backfill／Gate | BLOCKED | 需要真實 Gemini Embedding；Fake provider 測試不算真實 PASS |
| Browser 登入與採購流程 | PASS | 內建 Browser 以明確 `http://localhost:3000` 來源執行：Buyer Recommendation 750 KG → DRAFT #11 → Manager Approve → Buyer Order → Warehouse Receive；PO RECEIVED、MAT-001 1050 KG、`PURCHASE_RECEIVE 750 KG` 均由 UI 實際核對 |
| Browser 五份 Baseline 文件 | PASS（Document 層） | Admin UI 顯示五種 DocumentType 各一份、Version 1.0、READY；Source／Version 語意查詢仍受真實 Gemini 阻擋 |
| Browser 第六份文件 | PARTIAL | Admin UI Upload `ui-upload-sup-005-v1.txt` 成功建立 Document #6，Explicit Ingestion 後 READY；Outbound Review、Backfill、Readiness、Gate Audit、Source Query 尚未完成 |
| Browser Gemini Query | BLOCKED | Admin UI 實際提問後回 `AI_PROVIDER_UNAVAILABLE`；執行環境刻意保持 provider `none` 且未提供 API key |
| Browser Reset 前後一致性 | NOT RUN | 已完成的是隔離環境 CLI／Backend Reset 生命週期，不是 Browser E2E；仍須以 UI 建立操作後狀態，再跨 Reset 前後核對五份基線、第六份消失與 Gate 清空／重建 |
| AWS Scheduler／Deployment | NOT RUN | 明確屬 SP-903，本 Issue 不操作 AWS |

初次使用既有 Chrome 的自動控制因無法可靠確認目前 URL 而依安全政策停止；後續改用由工具建立、明確綁定 `http://localhost:3000` 的內建 Browser，未繞過任何警告或修改安全設定，完成表列的採購及文件 Document 層證據。整體 Browser E2E 仍未完成：缺少真實 Gemini Chat／Embedding 金鑰，ADMIN Outbound Review 後的真實 Backfill、Readiness、Gate Audit、Source／Version Query，以及 Browser Reset 前後一致性均未驗收。CLI Reset PASS 不得視為 Browser Reset E2E PASS。

目前交付狀態為 **`BLOCKED — READY FOR PARTIAL CODE REVIEW`**。這表示已完成的程式碼與證據可交 GPT 獨立審查，但不代表 SP-901 全部驗收、PR 核准或 Issue 完成。
