# SupplyPilot V2 — Documents & AI / 實用版採購 RAG

> 已確認需求，尚未完成 V2 實作。對應：[#145](https://github.com/liujiawei-tw/supplypilot/issues/145)、[#146](https://github.com/liujiawei-tw/supplypilot/issues/146)、[#147](https://github.com/liujiawei-tw/supplypilot/issues/147)。現有程式包含 SP-710 Document Chunk、SP-722 Gemini embedding/pgvector、OutboundDocumentPolicy、Evidence Validator 及 Spring AI Tool Calling。

## 1. 保留的三種使用情境

1. 公司採購 SOP／採購權限或流程文件查詢。
2. 歷史合約或供應商文件**單份條款內容查詢**；不做跨合約比較、缺少條款判定。
3. 供應商評鑑、投訴與歷史報告**文件查詢與有來源摘要**；不憑文件自動計算客觀可靠度或交期 KPI。

僅以文件能提供的證據作答；若查不到足夠段落應回 INSUFFICIENT_EVIDENCE，而非虛構。

## 2. V2 混合檢索流程

    使用者提問
      → Spring AI / SEARCH_DOCUMENTS（保留 Tool Calling）
      → 後端 HybridRetrievalService 協調
           ├─ MySQL：文件元資料、供應商、型號/料號/合約編號、字詞精確或部分匹配
           └─ pgvector：已發布文件的 Gemini 語意向量檢索
      → 檢索候選合併、去重、排名與來源狀態過濾
      → Evidence Validator：核對文件 ID/版本/Chunk/原文摘錄／內容支持程度
      → AI Answer：回答 + 文件名稱/版本/逐字摘錄 + 文件檢視入口

- **不是**每次讓 LLM 在 MySQL 和 pgvector 中二選一，而是由後端確定性地協調候選結果。
- 不新增 Elasticsearch；沿用目前 MySQL 與 PostgreSQL/pgvector。
- 不新增 PDF 頁碼欄位、頁碼抽取、條文號解析。現有 Chunk index/offset 可作內部證據查核，但不必把技術座標顯示為使用者要求的「頁碼」。
- 不宣稱「引用文字存在」代表「答案 100% 正確/完整」；對數字、條件與拒答設可測試的支持邊界。來源一律後端查核，不由 LLM 自造。
- 相關文件不符合查詢時回不足證據；不能為回答完整而將無關內容塞入來源。

## 3. 敏感資料安全（採既有方案 A）

- 沿用現有 OutboundDocumentPolicy、管理員針對內容版本/hash 的核准及 REQUIRES_REVIEW/SKIPPED 規則。
- 可遮罩的 Email、電話與台灣身分證字號以等長遮罩輸出，保留來源 Chunk offset 對應；憑證、銀行、商業及個人敏感內容仍須阻擋出站。
- 包含金額、折扣、銀行帳戶、認證資訊等敏感內容，依既有策略不可外送 Gemini Embedding；**MySQL 搜尋命中不構成 Chat 外送許可**。
- Hybrid Retrieval 需在**交給 LLM 之前**過濾、驗證可外送的證據；不可新增「lexical 路徑免審查」後門。
- 文件級 ACL 暫緩；這不等於放棄現有登入/角色、原文讀取及索引操作授權。受此限制，部分敏感合約可能無法透過語意/AI 問答查詢，應誠實呈現。

## 4. 逐文件發布（取代日常全域 Gate）

1. 上傳與解析完成，文件在 MySQL 可為 READY，但**不代表可供語意查詢**。
2. 一般文件由管理員對**特定文件來源版本/hash**核准合法出站；固定可信 Demo Seed 由專用系統審核 seam 重新驗證來源及既有出站政策後決定是否核准。
3. 核准成功即排入**持久化、非同步**索引工作；不要求管理員另按 Backfill 或 Gate Audit。
4. 真實 Gemini Embedding → pgvector 建立／驗證 Chunk、model generation、來源 hash。
5. 只在該文件索引完整、政策合規與版本一致後**原子發布**；其他已發布文件始終可正常查詢。
6. 新文件索引失敗或待審核不會關閉所有已發布文件；停用文件即從檢索中排除。現有系統級 Generation/部署驗收可保留，但不作為每次上傳的全域阻斷。

**重試：** 429、逾時及暫時性外部 5xx 自動有限次、退避重試；Retry-After 是最短等待時間，本地指數退避以 maxRetryDelay 封頂。若伺服器要求的等待超出 MySQL DATETIME(6) 可保存範圍，工作轉為 FAILED 供管理員查核，不會提前自動重試。審核拒絕／違反敏感政策等不可自動重試。超過上限 FAILED 並提供管理員安全錯誤資訊及手動重試入口。重啟後任務狀態可復原；同一文件多次觸發必須冪等，舊代次或舊 hash 不可發布覆蓋新版。

Documents API 會分開回傳文件 ingestion `status`、持久化 `semanticReview` 與 `semanticIndex`。只有本機文字抽取和 chunks 驗證完成才標示 Document `READY`；只有持久 worker 工作成功且 PostgreSQL 已發布的來源、hash、generation、chunk 及向量維度證明全數吻合，Semantic 狀態才是 `INDEXED`（UI 顯示「已發布」）。缺少索引資料回 `PENDING`；PostgreSQL/provider 暫不可用回 `UNAVAILABLE` 或持續待處理，不能將外部索引推定為成功，也不能讓採購核心 API 因外部索引狀態故障而報錯。

## 5. Demo 語料與首次審查

- Demo Seed 使用 `samples/documents/corpus-catalog.json` 記錄文件鍵、類型、theme、版本、標題、供應商/物料關聯、`SP-901` corpus provenance 與來源 SHA-256；`samples/documents/rag-fixtures.json` 記錄靜態問答及預期引用。Catalog 欄位只建立可信來源清單；它本身不記錄或授予核准結果，也不能證明資料已傳送或索引已發布。
- `DemoCorpusSyncService` 只讀取 demo Repository corpus 的 catalog 與來源，固定核對 schema、dataset key、corpus version、文件鍵、類型、標題、版本、supplier 關聯、政策、provenance 和 canonical-LF source hash。同步與系統核准共用 `DemoCorpusCatalogTrust`，要求 catalog 為一般檔案且非 symlink，且 UTF-8 內容換行正規化為 LF 後符合固定 SHA-256 `E906CA49D8B1A4F3CEB58994A9E77B3E36EB29FE4BEFA1783ACA12086F1D3189`；外部自製同 envelope catalog 不受信任。重跑以 `(datasetKey, documentKey, version)` 和持久 provenance 辨識基線，絕不只靠標題合併。
- 無 provenance 的舊 Seed 只有在 type/title/version/supplier 與 catalog 完全相符、唯一候選且資料庫儲存來源的 canonical-LF hash 完全相符時才可採用並寫入 provenance。候選歧義、來源或欄位不同、已存在 keyed baseline 被改動時，建立同步 conflict 並保留原文件；不覆寫其來源，也不覆蓋有效人工審查 audit。
- 只有 demo profile、明確允許的 MySQL DEMO 資料庫、demo 文件儲存根目錄、相符 Repository catalog entry、READY chunks/source proof 及當下 `OutboundDocumentPolicy` 都通過時，專用 system approval seam 才能記錄 `SYSTEM / TRUSTED_DEMO_CORPUS` 審查和 enqueue 現有 durable job。它不偽裝管理員，沒有 HTTP 授權入口；一般上傳與一般 API 仍走使用者既有審查流程。需審查或政策阻擋的結果會保留原因，不排索引工作。
- Semantic PostgreSQL URL 必須指向設定的 demo database，host 僅允許 Compose `pgvector` 或 loopback (`localhost`、`127.0.0.1`、`::1`)。URL 缺少或目標不符時保留文件 READY、記錄 `REQUIRES_REVIEW` 且不 enqueue；已驗證為 demo 的目標暫時不可連線時，durable job 可等待既有 worker 重試，API 必須顯示待處理/不可用，直到 PostgreSQL 發布證明核對成功。
- 純文字來源的 `sourceSha256` 使用 UTF-8 文字將 CRLF/CR 換行正規化為 LF 後計算；`samples/documents/*.txt` 固定以 LF 儲存，確保跨平台 hash 穩定。
- `SEED_BASELINE` 是唯一可能進入受限 system approval 的分類；policy 不允許時仍須人工處理。SUP-005 人工 UI Upload 文件為 `MANUAL_UI_UPLOAD_ONLY`，沒有可信 provenance，不得被 Seeder 建立、採用或引用為 baseline fixture。
- Catalog/source 更新若與資料庫既有來源不同，sync 保留並標記衝突；一般文件仍由正常上傳、審查與版本流程處理。標題變更會讓舊 review 失效，不能複製舊核准。
- 靜態 Fixture PASS 只代表檔案、來源關聯及預期格式通過檢查，不表示 Gemini RAG 回答品質 PASS。

## 6. 文件全文預覽與原檔下載 API

文件內容以已授權的文件 ID 尋址；API 不接受儲存路徑，也不在 `DocumentResponse` 對外回傳 `storagePath`。

| Endpoint | 回應 | 說明 |
|---|---|---|
| `GET /api/v1/documents/{id}/preview` | PDF：`application/pdf` 原始 bytes；TXT／DOCX：`text/plain;charset=UTF-8` | PDF 以 inline 串流預覽。TXT 以嚴格 UTF-8 解碼完整原文。DOCX 轉為完整文字，段落和表格依文件 body 順序輸出；同列儲存格以 Tab 分隔、列與段落以換行分隔。 |
| `GET /api/v1/documents/{id}/file` | 原始檔案 Media Type 與 bytes | 以 attachment 串流下載原始檔案。 |

兩個端點皆要求已登入的 `PROCUREMENT_STAFF`、`PROCUREMENT_MANAGER` 或 `ADMIN`。回應設定 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff` 和安全的 `Content-Disposition` 檔名；全文預覽與原檔下載均限制在 20 MiB。文字預覽的來源格式可由 `Content-Disposition` 檔名副檔名辨別（`.txt` 或 `.docx`）；前端也可將兩者統一呈現為全文文字預覽。找不到文件回 `404 DOCUMENT_NOT_FOUND`；文件記錄存在但原檔缺失回通用 `404 DOCUMENT_FILE_NOT_FOUND`；無效文字／DOCX 格式回 `422 DOCUMENT_PREVIEW_INVALID`；超過大小上限回 `400 FILE_TOO_LARGE`。

預覽與下載只讀已存來源，不會變更 ingestion、審核、索引狀態，不會建立 indexing job，也不會呼叫 Gemini 或 Semantic Provider。DOCX 預覽使用獨立的 body-order 抽取器；RAG ingestion 既有的段落／表格抽取順序保持不變。

## 7. 明確不做

- 文件級 ACL、逐人授權清單
- PDF 頁碼定位、條文號抽取與來源跳到頁數
- 新舊合約比較、缺漏條款偵測
- 違反敏感政策時另建本地 Embedding 以規避限制
- 整份企業文件的無條件外送與原文日誌

## 8. 驗收

- 同一句查詢可兼用合約編號/料號精準匹配與語意候選；資料來源去重且不混入錯版本或無關文件。
- 回答有真實文件名稱、版本與引用原文；無證據不回答實質細節。
- 未核准/敏感文件不經任何路徑送入 Gemini；Provider 故障不阻斷核心採購 MySQL 業務。
- 新文件排隊、失敗、重試、手動再索引不影響既有可查文件；管理員不需日常全域 Gate 操作。
- Demo Catalog 版本/hash 不符時不得自動核准或發布索引；衝突必須保留並由人工查核。文件 `READY`、政策核准、durable job 狀態及 Semantic `INDEXED` 必須各自有真實證據，不得互相推定。
