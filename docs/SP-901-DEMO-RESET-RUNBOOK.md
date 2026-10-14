# SP-901 Demo Reset Runbook

## 安全界線

首次初始化與 Reset 都只允許 `demo` profile，且在讀寫 `demo_dataset_state` 前要求：

- `SPRING_PROFILES_ACTIVE` 必須是 `demo`，不能包含 `prod`。
- MySQL `SELECT DATABASE()` 必須等於 `DEMO_ALLOWED_DATABASE`。
- `DOCUMENT_STORAGE_PATH` 的正規化絕對路徑必須有獨立的 `demo` 路徑標記（例如 `demo-documents`），避免原始文件落入共用路徑。

Reset 另外要求：

- `DEMO_RESET_CONFIRMATION=RESET-SP-901-DEMO`。
- `SEMANTIC_DB_URL` 的 database 名稱必須等於 `DEMO_ALLOWED_SEMANTIC_DATABASE`。

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
$env:DOCUMENT_STORAGE_PATH = '.\data\demo-documents'
Set-Location backend
.\mvnw.cmd -B --no-transfer-progress spring-boot:run
```

空資料庫會先核對實際 MySQL database 與原始文件儲存路徑，再套用 Flyway、建立 relational baseline、上傳並 ingestion 五份原始文件，最後將 `demo_dataset_state` 設為 `COMPLETE`。錯誤 database 或非 Demo 文件路徑會在第一筆 dataset state 寫入前停止。同版本第二次啟動只核對 marker 並跳過，不覆寫操作後資料。資料庫非空但沒有 marker、版本不同、或 marker 為 partial／FAILED 時會停止。

## Manual Reset

Reset 會清除專用 PostgreSQL semantic tables、刪除 metadata 對應原始文件、清除專用 MySQL 業務資料，然後重建 baseline。先備份需要保留的 Demo 操作結果。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:DB_URL = 'jdbc:mysql://localhost:3306/supplypilot_demo'
$env:SEMANTIC_DB_URL = 'jdbc:postgresql://localhost:5432/supplypilot_semantic_demo'
$env:DOCUMENT_STORAGE_PATH = '.\data\demo-documents'
$env:DEMO_ACCOUNT_PASSWORD = '...'
.\scripts\reset-demo.ps1 -AllowedDatabase supplypilot_demo -AllowedSemanticDatabase supplypilot_semantic_demo
```

腳本改以 `DemoResetApplication` 啟動非 Web 的單次命令：只執行一次 guarded reset 與 baseline rebuild，成功回傳 `0`，失敗回傳非 `0`，完成後關閉 Spring context。腳本暫時設定的 confirmation／allowed database 環境變數會在 `finally` 還原；一般 Backend 啟動不會執行 reset，也不再支援持續性的 `DEMO_RESET_ENABLED` 旗標。Reset 中途失敗即視為不可用，不得宣稱 baseline 或舊 Gate 仍有效；修正原因後重新執行完整 Reset。

## Reset 後檢查

1. `demo_dataset_state` 唯一一筆 `SP-901 / sp-901-v1 / COMPLETE`，並記錄 baseline date。
2. 五個帳號可用新注入密碼登入；不公開 `admin` 密碼。
3. Materials 12、Suppliers 6、SupplierMaterial 30、Demands 12。
4. PO 分布為 DRAFT 2、APPROVED 2、ORDERED 3、RECEIVED 2、REJECTED 1。
5. MAT-001 透過 Recommendation API得到 `1000 + 200 - 300 - 150 = 750 KG`。
6. 五份文件原檔存在，Metadata／Chunk 為 READY；第六份不存在。
7. PostgreSQL 舊 index 與 Gate audit 已清空，因此此時 Semantic Gate **不是 PASS**。
8. 逐份重新人工核准出站、真實 Backfill、Readiness 與 Gate Audit；只有成功後才啟用 Semantic Read 並驗證來源。

## 2026-10-14 驗證狀態

- `DemoSeedTargetSafetyIntegrationTests`：PASS，2 tests、0 failures、0 errors；以空 MySQL 驗證錯誤 database 與非 Demo 文件路徑皆在 `demo_dataset_state`、users、metadata、filesystem 零寫入時停止。
- `DemoResetCommandRunnerTests`：PASS，4 tests、0 failures、0 errors；驗證單次執行、成功關閉 context、失敗回傳非零與例外傳遞。
- `DemoResetIntegrationTests`：PASS，1 test、0 failures、0 errors；使用隔離 MySQL、文件目錄與 PostgreSQL semantic tables 驗證清除及 baseline 重建，並確認後續一般初始化不會再次刪除操作後資料。
- Backend full regression：PASS，525 tests、0 failures、0 errors、1 skipped；skipped 為未提供金鑰的真實 Gemini smoke，未以 Fake provider 取代。
- Manual Browser Reset 前後一致性：NOT RUN。本次 Browser stack 未配置專用 pgvector，因此沒有以不完整目標強行執行 reset；仍須依上一節在同一組專用 MySQL、pgvector 與文件目錄完成實測。
- 真實 Gemini Backfill／Gate 恢復：BLOCKED，未提供未追蹤的 Gemini API key；不得以 Fake provider 取代。

## 一致性與失敗復原

Reset 順序是 PostgreSQL → 原始文件 → MySQL → baseline rebuild。任一步失敗皆停止；這是安全失敗，不會沿用舊 Gate。由於跨資料庫與 filesystem 沒有分散式 transaction，復原方式是排除連線／權限／磁碟問題後，對同一組三個專用目標重跑完整 guarded reset，而不是手動把狀態改為 READY 或 PASS。

`DemoDataInitializer` 若 relational transaction 失敗會 rollback；文件階段失敗則 marker 為 `FAILED`，後續啟動不會靜默補寫。必須查明原因並走 guarded reset。

## SP-903 接入條件

SP-901 不建立 AWS Scheduler。SP-903 若接入台灣時間每日 `00:00`，只可把 `DemoResetApplication` 當成一次性 Job 呼叫，並注入專用 database 名稱、文件 volume、pgvector database 與 secret；Job 必須以 process exit code 判定成功或失敗，不可啟動長時間運行的 Backend。午夜進行中的展示操作可能被清除，需事先告知使用者。

Reset 會重建五份 Document READY baseline，但刻意清除舊 semantic index、outbound approval 與 Gate audit。因此午夜 Reset 後可依「Reset 後檢查」第 8 步重新建立真實 Gate；SP-901 不會自動偽造 ADMIN Approval 或 Gate Audit。SP-903 若要自動恢復 Semantic Read，必須另行設計具真實 ADMIN 身分、真實 Gemini、稽核與失敗告警的受控工作流；在該工作流實測前，Semantic Gate 仍是 `BLOCKED`。
