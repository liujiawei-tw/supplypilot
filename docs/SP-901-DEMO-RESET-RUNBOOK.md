# SP-901 Demo Reset Runbook

## 安全界線

首次初始化與 Reset 都只允許 `demo` profile，且在讀寫 `demo_dataset_state` 前要求：

- `SPRING_PROFILES_ACTIVE` 必須是 `demo`，不能包含 `prod`。
- `DEMO_ALLOWED_DATABASE` 本身必須是明確的 Demo 名稱（`demo` 為獨立 token，且不得含 `prod`／`production`／`shared`／`common` token），MySQL `SELECT DATABASE()` 也必須精確等於該名稱。
- Flyway 執行前除了保留上述 Primary DataSource 檢查，還會對 Flyway 實際持有的 DataSource 另開連線執行 `SELECT DATABASE()`；結果必須精確等於 `DEMO_ALLOWED_DATABASE`。`spring.flyway.url`、`@FlywayDataSource` 或其他方式建立的獨立 Migration DataSource 都不能繞過此檢查。
- Flyway 的 `defaultSchema` 與 `schemas` 若有設定，每一個值都必須同時符合 Demo 命名規則並精確等於 `DEMO_ALLOWED_DATABASE`；額外 schema、非 Demo schema、空白 schema 一律在 `flyway.migrate()` 前 fail closed。
- Demo profile 不允許任何非空白 Flyway connection initialization SQL。Preflight 直接檢查 Flyway 實際 configuration 的 `initSql` 並在 `flyway.migrate()` 前 fail closed，避免 migration 新連線透過 `USE` 或其他初始化語句改變目標或先產生副作用；非 Demo profile 不受此限制。
- `DOCUMENT_STORAGE_PATH` 的正規化絕對路徑，其最後一層 storage root 名稱必須符合相同的明確 Demo 命名規則（例如 `demo-documents`）；只有上層目錄叫 `demo-project` 而實際 root 是 `data/documents` 不會被放行。

Reset 另外要求：

- `DEMO_RESET_CONFIRMATION=RESET-SP-901-DEMO`。
- `DEMO_ALLOWED_SEMANTIC_DATABASE` 本身必須是明確的 Demo 名稱；`SEMANTIC_DB_URL` 的 database 名稱及 PostgreSQL `current_database()` 都必須精確相等。

任何條件不符即停止，不刪資料。不要將 allowed database 設成共用、正式或不確定用途的資料庫。

## Fresh Demo 初始化

在安全 shell 注入必要值，不要輸出密碼：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:DB_URL = 'jdbc:mysql://localhost:3306/supplypilot_demo'
$env:DB_USERNAME = '...'
$env:DB_PASSWORD = '...'
$env:JWT_SECRET = '...'
$env:DEMO_ACCOUNT_PASSWORD = '...'
Set-Location backend
$env:DOCUMENT_STORAGE_PATH = '.\data\demo-documents' # relative to backend after Set-Location
.\mvnw.cmd -B --no-transfer-progress spring-boot:run
```

空資料庫會先核對實際 MySQL database 與原始文件儲存路徑，再套用 Flyway、建立 relational baseline、上傳並 ingestion 五份原始文件，最後將 `demo_dataset_state` 設為 `COMPLETE`。錯誤 database 或非 Demo 文件路徑會在第一筆 dataset state 寫入前停止。同版本第二次啟動只核對 marker 並跳過，不覆寫操作後資料。資料庫非空但沒有 marker、版本不同、或 marker 為 partial／FAILED 時會停止。

## Manual Reset

Reset 會清除專用 PostgreSQL semantic tables、刪除 metadata 對應原始文件、清除專用 MySQL 業務資料，然後重建 baseline。先備份需要保留的 Demo 操作結果。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:DB_URL = 'jdbc:mysql://localhost:3306/supplypilot_demo'
$env:SEMANTIC_DB_URL = 'jdbc:postgresql://localhost:5432/supplypilot_semantic_demo'
# 此範例由 repository root 執行；reset-demo.ps1 對相對路徑一律以 backend 為基準。
$env:DOCUMENT_STORAGE_PATH = '.\data\demo-documents' # 等同 <repo>\backend\data\demo-documents
$env:DEMO_ACCOUNT_PASSWORD = '...'
.\scripts\reset-demo.ps1 -AllowedDatabase supplypilot_demo -AllowedSemanticDatabase supplypilot_semantic_demo
```

腳本以 Maven `demo-reset` profile 明確選用 `DemoResetApplication`，啟動非 Web 的單次命令：只執行一次 guarded reset 與 baseline rebuild，成功回傳 `0`，失敗回傳非 `0`，完成後關閉 Spring context。預設 Maven build 仍固定使用 `SupplyPilotApplication`，不依賴無法覆蓋的 `spring-boot.run.main-class` CLI property。腳本會先將 `DOCUMENT_STORAGE_PATH`（若為相對路徑，基準固定為 `backend`）正規化為絕對路徑，再傳給 Java；confirmation／allowed database／storage path 都會在 `finally` 還原。一般 Backend 啟動不會執行 reset，也不再支援持續性的 `DEMO_RESET_ENABLED` 旗標。Reset 中途失敗即視為不可用，不得宣稱 baseline 或舊 Gate 仍有效；修正原因後重新執行完整 Reset。

## Reset 後檢查

1. `demo_dataset_state` 唯一一筆 `SP-901 / sp-905-zh-tw-v1 / COMPLETE`，並記錄 baseline date。
2. 五個帳號可用新注入密碼登入；不公開 `admin` 密碼。
3. Materials 12、Suppliers 6、SupplierMaterial 30、Demands 12。
4. PO 分布為 DRAFT 2、APPROVED 2、ORDERED 3、RECEIVED 2、REJECTED 1。
5. MAT-001 透過 Recommendation API得到 `1000 + 200 - 300 - 150 = 750 KG`。
6. 五份文件原檔存在，Metadata／Chunk 為 READY；第六份不存在。
7. PostgreSQL 舊 index 與 Gate audit 已清空，因此此時 Semantic Gate **不是 PASS**。
8. 逐份重新人工核准出站、真實 Backfill、Readiness 與 Gate Audit；只有成功後才啟用 Semantic Read 並驗證來源。

## SP-905 繁中語料版本與受控重建

SP-905 將五份 baseline 原檔及 Metadata 標題更新為繁體中文，Dataset Version 固定為 `sp-905-zh-tw-v1`。原本 `sp-901-v1` 的 COMPLETE marker 代表舊英文語料；應用遇到版本不一致會 fail closed，不會將其靜默當作中文資料，也不會在一般啟動時覆寫既有資料。

- Fresh、空白且已驗證隔離的 Demo 環境會使用新版本上傳五份 sample，並透過一般 Ingestion 重新建立 Document Chunk；第六份 SUP-005 文件不會 Seed。
- 既有 Demo 若要更換語料，先確認 MySQL、PostgreSQL semantic database、文件 root 都是專用 Demo 目標，完整遵守本 Runbook 前述 Demo／Prod、Database Allowlist、Flyway Preflight、Storage Path、Reset Confirmation 及 Semantic Database Guard，再依「Manual Reset」執行一次性 Guarded Reset。保留資料需先依環境程序備份；不可手動刪除 marker、直接 SQL 清表或複製舊向量。
- Reset 完成後核對 `SP-901 / sp-905-zh-tw-v1 / COMPLETE`、五份 Metadata 標題與 sample `標題：` 相符、Version `1.0`、Document READY、Chunk 由本次原檔 Ingestion 產生、第六份文件不存在。
- Reset 同時清除舊 Semantic Index、出站核准與 Gate Audit。五份文件 READY 不等於 Semantic READY 或 Gate PASS；須以新版 chunks 重新 ADMIN 審核、真實 Embedding Backfill、Readiness、Gate Audit，再依 Runbook 顯式啟用 Semantic Read。
- 沒有真實 Gemini 憑證或隔離服務時，僅執行自動測試；真實重建與 Gate 狀態列為 BLOCKED，禁止在未知或共用資料庫嘗試 Reset。

## 2026-10-14 驗證狀態

- `DemoSeedTargetSafetyIntegrationTests`：PASS，2 tests、0 failures、0 errors；以空 MySQL 驗證錯誤 database 與「上層為 `demo-project`、實際 root 為 `data/documents`」皆在 `demo_dataset_state`、users、metadata 零寫入時停止，既有 sentinel 原檔未被刪除或覆寫。
- `DemoNonDemoDatabaseSafetyIntegrationTests`：PASS，1 test、0 failures、0 errors；即使 caller 把實際共用 database 名稱原樣填入 allowed 值，首次 Seed 與 Reset 仍拒絕，既有資料列及原檔不變。
- `DemoResetCommandRunnerTests`：PASS，4 tests、0 failures、0 errors；驗證單次執行、成功關閉 context、失敗回傳非零與例外傳遞。
- `DemoResetIntegrationTests`：PASS，1 test、0 failures、0 errors；使用隔離 MySQL、文件目錄與 PostgreSQL semantic tables 驗證清除及 baseline 重建，拒絕非 Demo semantic allowed database，並確認後續一般初始化不會再次刪除操作後資料。
- P1-06 Flyway Preflight：PASS，`DemoFlywayPreflightIntegrationTests` 6 tests、0 failures、0 errors、0 skipped。除原有 P1-05 的 shared Primary 拒絕、合法 Demo 正向 Migration／Seed、非 Demo profile 正常 Migration 外，新增三組隔離 Testcontainers MySQL 負向案例：合法 Demo Primary 搭配非 Demo `spring.flyway.url`、含合法與非 Demo 值的 `spring.flyway.schemas`、非 Demo `spring.flyway.default-schema`。三者均以正確原因在 `flyway.migrate()` 前拒絕，合法 Primary 與非 Demo Migration Database 的 table/schema snapshot、sentinel row、`flyway_schema_history`、文件 sentinel 均未變動。
- P1-07 Flyway init SQL Preflight：PASS，使 `DemoFlywayPreflightIntegrationTests` 增為 7 tests、0 failures、0 errors、0 skipped。以隔離 Testcontainers MySQL 建立合法 Demo Primary 與含 sentinel 的 `shared_flyway_init_target`，設定 `spring.flyway.init-sqls=USE shared_flyway_init_target`；Demo context 以明確安全原因在 `flyway.migrate()` 前拒絕。Primary 與另一資料庫的 table/schema snapshot、sentinel row、`flyway_schema_history` 及文件 sentinel 均未變動；合法 Demo 預設 Flyway 仍正常 Migration／Seed，非 Demo profile 的正常 Flyway 路徑不受影響。
- P2-01 PowerShell 腳本生命週期：PASS，11 cases、0 failures。原始四個環境變數「存在且有值」與「不存在」兩組，均通過 Runner success `0`、non-zero `17`、exception 傳遞與 `finally` 還原；Runner 期間收到正規化絕對 `DOCUMENT_STORAGE_PATH`。同一相對路徑由 repository root 或無關暫存 CWD 解析的結果相同，且均以 backend root 為基準；絕對路徑不會被再加上 backend root。合法 Demo leaf 會執行 Runner，`demo-project/data/documents` 因最後一層為 `documents` 而在 Runner 前被拒絕，環境與 sentinel 檔案不變。此測試以 AST 載入正式函式、注入 Command Runner，未啟動 Maven、Spring Boot、資料庫或真正 Reset。
- PowerShell AST Parser：PASS；`scripts/reset-demo.ps1` 與 `scripts/tests/reset-demo.Tests.ps1` 均無 parser error。
- SP-901 targeted Backend：PASS，25 tests、0 failures、0 errors、0 skipped；含 `DemoFlywayPreflightIntegrationTests` 7、`DemoDataInitializerActivationTests` 5、`DemoResetCommandRunnerTests` 4、`DemoNonDemoDatabaseSafetyIntegrationTests` 1、`DemoSeedTargetSafetyIntegrationTests` 2、`DemoResetIntegrationTests` 1、`DemoSeedSmokeIntegrationTests` 5。
- Backend full regression：2026-10-14 20:14 +08:00 啟動的本次 `mvnw.cmd -B --no-transfer-progress verify` PASS，Maven exit code `0`、`BUILD SUCCESS`，533 tests、0 failures、0 errors、1 skipped；skipped 為未提供金鑰的 `SemanticRealProviderSmokeTests` 真實 Gemini smoke，未以 Fake provider 取代。七個 SP-901 targeted 類別及 P1-05／P1-06／P1-07 Flyway Preflight 案例均在此次 full regression 執行，其 MySQL／pgvector 來自 Testcontainers 的隔離資源。
- 實際 CLI Reset 生命週期：PASS。於隔離的 `supplypilot_demo_p103` MySQL、`supplypilot_semantic_demo_p103` pgvector 與 `sp901-demo-reset-storage` 執行 `scripts/reset-demo.ps1`；log 明確顯示 `Starting DemoResetApplication`、`DEMO_RESET_COMMAND_START`／`COMPLETE` 與 `DEMO_RESET_APPLICATION_EXIT code=0`。程序自行結束且 shell exit code 為 0；將 MAT-001 inventory 改為 999 後第二次執行，恢復為 300、五份 READY 文件與完整 12/6/30/12、10 張 PO 基線，五個原檔 storage UUID 全數重建，第六份不存在。
- 實際 CLI 失敗與一般重啟：PASS。以錯誤 PostgreSQL credential 執行時 `DemoResetApplication` exit code 為 1，既有 MySQL marker、MAT-001=300、五份 READY 與五個原檔均保留；其後把 MAT-001 改為 999，以預設 Maven profile 啟動一般 Backend，log 顯示 `Starting SupplyPilotApplication` 與 `DEMO_DATASET_ALREADY_COMPLETE`，執行期間 inventory 仍為 999，證明重啟沒有再次 Reset。
- Manual Browser Reset 前後一致性：NOT RUN。上述是實際 CLI／Backend 生命週期驗證，不是 Browser E2E；仍須依 Browser checklist 從 UI 操作狀態跨越 Reset 前後核對。
- 真實 Gemini Backfill／Gate 恢復：BLOCKED，未提供未追蹤的 Gemini API key；不得以 Fake provider 取代。

## 一致性與失敗復原

Reset 順序是 PostgreSQL → 原始文件 → MySQL → baseline rebuild。任一步失敗皆停止；這是安全失敗，不會沿用舊 Gate。由於跨資料庫與 filesystem 沒有分散式 transaction，復原方式是排除連線／權限／磁碟問題後，對同一組三個專用目標重跑完整 guarded reset，而不是手動把狀態改為 READY 或 PASS。

`DemoDataInitializer` 若 relational transaction 失敗會 rollback；文件階段失敗則 marker 為 `FAILED`，後續啟動不會靜默補寫。必須查明原因並走 guarded reset。

## SP-903 接入條件

SP-901 不建立 AWS Scheduler。SP-903 若接入台灣時間每日 `00:00`，只可把 `DemoResetApplication` 當成一次性 Job 呼叫，並注入專用 database 名稱、文件 volume、pgvector database 與 secret；Job 必須以 process exit code 判定成功或失敗，不可啟動長時間運行的 Backend。午夜進行中的展示操作可能被清除，需事先告知使用者。

Reset 會重建五份 Document READY baseline，但刻意清除舊 semantic index、outbound approval 與 Gate audit。因此午夜 Reset 後可依「Reset 後檢查」第 8 步重新建立真實 Gate；SP-901 不會自動偽造 ADMIN Approval 或 Gate Audit。SP-903 若要自動恢復 Semantic Read，必須另行設計具真實 ADMIN 身分、真實 Gemini、稽核與失敗告警的受控工作流；在該工作流實測前，Semantic Gate 仍是 `BLOCKED`。
