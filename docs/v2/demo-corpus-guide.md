# SupplyPilot DEMO 繁中 RAG 語料使用說明

> Corpus Version：`sp-907-zh-tw-v3`；Dataset Key：`SP-901`。本文說明虛構展示語料的結構、引用方式與審查邊界，不代表語料已上傳、核准、索引或完成 Gemini 驗收。

## 語料來源與檔案

- [主資料契約](../../samples/demo-master-data.json) 是供應商、物料、供應關係、PO 範例及 Corpus Version 的一致性依據。供應商及物料名稱、代碼、單位、MOQ、包裝倍數與交期以該檔為準。
- [Corpus Catalog](../../samples/documents/corpus-catalog.json) 使用 schema version 2，列出文件鍵、`DocumentType`、theme、版號、來源檔、canonical-LF SHA-256、供應商／物料關聯、fixture 關聯和 provenance。Catalog 不包含 approval result。
- [RAG Fixtures](../../samples/documents/rag-fixtures.json) 列出 126 條靜態問答案例、預期答案要點、引用來源、引用要求、案例類別及預期狀態。
- `samples/documents/` 包含 115 份 `SEED_BASELINE` 文件，以及 1 份 `MANUAL_UI_UPLOAD_ONLY` 文件。Seed 文件分類如下：

| DocumentType | 文件數 |
| --- | ---: |
| `SUPPLIER_CONTRACT` | 13 |
| `QUOTATION` | 13 |
| `PROCUREMENT_SOP` | 3 |
| `PRODUCT_SPECIFICATION` | 13 |
| `QUALITY_REQUIREMENT` | 13 |
| `SUPPLIER_PROFILE` | 20 |
| `SUPPLIER_COOPERATION_RECORD` | 30 |
| `SUPPLIER_EVALUATION` | 10 |

其中保留 15 份原有基線（6 份供應商專屬文件、9 份共享 SOP／規格／品質文件），並新增 100 份供應商專屬主題文件。新增主題包含供應商檔案、供應能力、合約條件、報價、產品規格、品質要求、交付協調、合作往來、事件改善及買方評估；新增文件使用版本化 document key。共享 legacy 文件保留原有供應商關聯（可能為空），不會強行掛到單一供應商。Catalog 的 `seedDocumentCount` 是基線數量，Coordinator 依目前 catalog 驗證，並要求所有必要 `DocumentType` 均有 Seed。

PO 範例由同一主檔的供應商、物料及 `SupplierMaterial` 條件交叉驗證：供應商/物料組合必須存在，數量須達 MOQ 並符合包裝倍數；PO `status` 必須與 Kanban `stage` 相符。`REJECTED`／`CANCELLED` 必須提供終止原因且不帶流程 Stage；`RECEIVED/PAYMENT_RECONCILIATION` 表示已收貨、付款尚未確認，`RECEIVED/COMPLETED` 則要求收貨完成且付款已確認。這些是靜態語料契約檢查，不代表資料已寫入資料庫。

## 文件內容約束

所有內容均為虛構的繁體中文供應鏈展示案例。業務代碼、狀態及物料單位維持系統使用的英文識別值。報價文件只記錄結構化資料中可核對的供貨期間、MOQ、包裝倍數與交期，不記錄單價或金額；其他文件亦不含憑證、個人資料或認證結果。歷史條件清楚標示適用期間，不能當成現行條件引用。

採購 SOP 使用 V2 直接建立 PO `DRAFT` 及八階段 Kanban，並說明正式採購單狀態與看板階段分離。文件沒有把 V1 Demand／Recommendation 流程帶回主流程。

## 信任邊界、審查、出站與索引

`DemoCorpusSyncService` 只信任 demo profile 中 Repository 的目前 corpus catalog 和來源檔，並精確核對 dataset/version、document key、type/title/version、supplier code/name、theme、來源 filename/hash、approval/outbound policy 與完整 provenance。同步與系統核准共用 `DemoCorpusCatalogTrust`：catalog 必須是一般檔案（regular file）、不可為 symlink，且 UTF-8 文字將 CRLF/CR 正規化為 LF 後須符合固定 SHA-256 `E906CA49D8B1A4F3CEB58994A9E77B3E36EB29FE4BEFA1783ACA12086F1D3189`；改用自製同 envelope catalog 或只改 `samplesPath` 不會取得信任。內容 hash 同樣將換行正規化為 LF。同步會以 `(datasetKey, documentKey, version)` 和持久 provenance 做冪等比對，絕不只用標題判斷身分。

無 provenance 的舊資料只會在唯一候選、type/title/version/supplier 完全相符，且資料庫保存來源的 canonical hash 與 catalog 相同時採用；檢查通過後才寫 provenance。重複候選、任何欄位或 source hash 不符、或既有 keyed baseline 被修改，會留下 sync conflict 並保留既有文件與來源，不會覆寫內容。有效人工審查 audit 也不會被 legacy adoption 覆蓋。

`SEED_BASELINE` catalog row 本身不是審查憑據。獨立的 system-only approval service 在每筆文件重新確認 Repository 的精確 catalog entry、demo MySQL/storage target、來源 SHA、READY chunks 與 OutboundDocumentPolicy，然後以 `SYSTEM / TRUSTED_DEMO_CORPUS` 記錄審查者、原因、時間、版本、source hash 和 policy decision，並在同一 MySQL transaction enqueue 既有 durable semantic job。這不是管理員身分，也沒有一般 HTTP authorization path。普通 UI upload 不含可信 baseline provenance，不能走這條授權 seam。

文件 ingestion `READY`、Semantic review `APPROVED`、持久 job 完成、PostgreSQL `INDEXED`（UI「已發布」）是獨立狀態。只有 worker 成功且 PostgreSQL 已發布來源/chunks/hash/generation/vector dimensions 全部吻合，API 才回報 `INDEXED`。等待 job、provider/DB 暫時不可用、缺少發布證明都不會顯示已發布。

Semantic PostgreSQL 必須使用 `allowedSemanticDatabase` 指定的 demo database，並位於 Compose `pgvector` 或 loopback host (`localhost`、`127.0.0.1`、`::1`)。缺少 URL 或目標不符時文件仍可保持 READY，但 system review 是 `REQUIRES_REVIEW`，不會 enqueue。符合 allowlist 的 demo target 暫時不可連線時，job 仍可 durable 排隊並由既有 worker retry；API 標示 `PENDING`/`UNAVAILABLE`，採購 MySQL API 不因外部 Semantic DB 暫時故障而失敗。

`UI-SUP-005-ADDENDUM` 對應 `ui-upload-sup-005-v1.txt`，用途限於人工 UI Upload 驗收。其分類為 `MANUAL_UI_UPLOAD_ONLY`，Coordinator 不得自動 Seed，也不得當成基線來源。`RAG-021` 只適用於此文件由測試人員人工上傳後的查詢情境。

## 雜湊維護與 Fixture 解讀

文件內容有任何變更後，先重新計算來源檔案 SHA-256，再更新 Catalog 的 `sourceSha256`。雜湊以 UTF-8 文字內容計算；計算前將 CRLF 或單獨 CR 換行正規化為 LF，因此不同作業系統的 checkout 會得到相同結果。`samples/documents/*.txt` 在 Repository 中應固定使用 LF 換行。下列 PowerShell 指令可從 Repository 根目錄計算單一檔案的 canonical-LF 雜湊：

```powershell
$path = 'samples/documents/quotation-sup-002-v1.txt'
$text = [System.IO.File]::ReadAllText($path) -replace "`r`n?", "`n"
$bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($text)
$digest = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
($digest | ForEach-Object { $_.ToString('x2') }) -join ''
```

文件內容、標題或來源版本改變不會沿用舊審查。Catalog/Repository hash 改變時同步會標 conflict；需要更新資料時應按新版本和實際 policy 重新審查，不得複製舊核准或因 Fixture 曾出現 `APPROVED` 而恢復授權。durable worker 的既有退避與有限重試適用於暫時 provider/DB 失敗；超限狀態留待管理員安全查核或按既有操作重試。被 policy 阻擋或人工排除的來源不會自動重試出站。

Fixture 的 `expectedStatus`、答案要點及引用條件是靜態驗證契約，不表示執行過 RAG、Browser、pgvector 或 Gemini。實際執行時應確認答案逐點有來源支持、引用標題及版本正確，並排除無關文件；缺少證據的題目應拒答，不得補寫推測。

## Dataset Version 與 AUTO_SAFE 操作

目前 `main` 的 `.env.demo.example` 仍以 `sp-906-zh-tw-v2` 為例；本功能分支的目標是 `sp-907-zh-tw-v3`，其 `.env.demo.example` 是可提交範例。`.env.demo` 是使用者本機設定，可能含有 Secret，不應提交，也不可用範例檔整份覆寫。

此分支的後端 `AUTO_SAFE` 只接受目標版本 `sp-907-zh-tw-v3`。請保留既有 `DEMO_DATASET_UPGRADE_MODE=AUTO_SAFE`，並確認 Docker Compose 實際解析出的 Dataset Version 是 `sp-907-zh-tw-v3`。若解析結果仍是 `sp-906-zh-tw-v2`，請先停止啟動並在本機 `.env.demo` 將重複的 Dataset Version 設定整理成單一 `DEMO_DATASET_VERSION=sp-907-zh-tw-v3`；不要改寫其他 Secret。版本不相容時後端會拒絕啟動，不應藉由連接既有資料庫來試錯。

從 Repository 根目錄可用以下 PowerShell 指令只檢查核准欄位；不要改成輸出完整 Compose Config：

```powershell
$resolvedDemoConfig = docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml config --format json | ConvertFrom-Json
$resolvedDemoBackend = $resolvedDemoConfig.services.backend
[pscustomobject]@{
  DatasetVersion = $resolvedDemoBackend.environment.DEMO_DATASET_VERSION
  UpgradeMode = $resolvedDemoBackend.environment.DEMO_DATASET_UPGRADE_MODE
  ActiveProfile = $resolvedDemoBackend.environment.SPRING_PROFILES_ACTIVE
} | Format-List
```

本分支實作與驗證使用隔離的資料庫及測試 Volume；不會自動升級使用者既有 Demo 資料庫。
