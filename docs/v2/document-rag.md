# SupplyPilot V2 — Documents & AI / 實用版採購 RAG

> 本文件保留 V2 文件管理、權限、Demo 語料與答案證據需求。本次核定的 RAG 架構遷移以 Spring AI 2.0.1 原生元件取代舊正式查詢路徑；此文件與 Runbook 描述目標及操作程序，不代表程式切換、資料回填或真實 Gemini 驗收已完成。Agentic SQL 不在遷移範圍。

## 1. 保留的三種使用情境

1. 公司採購 SOP／採購權限或流程文件查詢。
2. 歷史合約或供應商文件單份條款查詢；不做缺少條款判定。報價文件可依 Catalog 同一供應商／物料／事實類型做明確期間的歷史與現行比較。有限多文件摘要及明確供應商比較可使用已核准且適用的來源；生成答案由模型依系統提示組成，後端不逐句驗證答案主張。
3. 供應商評鑑、投訴與歷史報告**文件查詢與有來源摘要**；不憑文件自動計算客觀可靠度或交期 KPI。

系統提示要求只以文件 Context 作答並由模型判斷候選文件是否支持問題；若向量檢索沒有相似度達 0.4 的候選，後端回 INSUFFICIENT_EVIDENCE。生成答案的主張不由後端逐句核驗，不能保證模型不虛構或不合併矛盾來源。

## 2. Spring AI 文件問答流程

    使用者提問
      → DocumentQueryService / SEARCH_DOCUMENTS
      → Spring AI ChatClient + RetrievalAugmentationAdvisor
      → VectorStoreDocumentRetriever + PgVectorStore（最多 10 份、相似度門檻 0.4）
      → ContextualQueryAugmenter：將檢索候選直接放入 Gemini Context
      → Gemini ChatModel：自行判斷候選與問題是否相關、是否足以回答
      → DocumentQueryResponse：模型答案 + Advisor 回傳的檢索文件來源

- 正式文件問答使用 Spring AI `RetrievalAugmentationAdvisor`、`VectorStoreDocumentRetriever`、`PgVectorStore`、`ChatClient`、`ContextualQueryAugmenter` 與 Gemini `EmbeddingModel`。不得保留自製 lexical/semantic 檢索或 RRF 作為正式查詢路徑。
- Retriever 使用 Filter Expression 限制已發布 generation、`READY` 文件與 `APPROVED` 出站狀態；相似度門檻為 0.4，候選最多 10 份。相似度全低於門檻時，回傳 `INSUFFICIENT_EVIDENCE`。
- 不使用自製 `DocumentPostProcessor` 做候選文件來源、版本、事實適用性或語意支持篩選；Spring AI Advisor 把符合 Retriever 條件的候選直接交給模型判斷。來源由 Advisor 回傳的實際 `Document` metadata 建立。
- 出站敏感資料政策仍由文件核准與索引流程執行；向量檢索只取 `APPROVED` 文件，模型不得用來判斷或規避敏感資料政策。
- PgVectorStore 使用獨立 PostgreSQL `DataSource`／`JdbcTemplate` 與 `semantic_rag_vectors` 資料表。不得讓 PgVectorStore 使用 Spring Boot 的 MySQL 主 `DataSource`。
- 採 exact similarity，不建 HNSW／IVFFlat。Schema 以可追蹤的 PostgreSQL Flyway Migration 建立；PgVectorStore 關閉啟動自動建表，並固定 `remove-existing-vector-store-table=false`。
- 不新增 PDF 頁碼欄位、頁碼抽取、條文號解析。現有 Chunk index/offset 可作內部證據查核，但不必把技術座標顯示為使用者要求的「頁碼」。
- 不宣稱「引用文字存在」代表「答案 100% 正確/完整」；對數字、條件與拒答設可測試的支持邊界。來源一律後端查核，不由 LLM 自造。
- 相關文件不符合查詢時回不足證據；不能為回答完整而將無關內容塞入來源。

## 3. 解析、切塊與來源位置

- PDF、DOCX 使用 Spring AI `TikaDocumentReader`；TXT 使用 `TextReader`。先檢查 20 MiB 檔案上限、合法副檔名、DOCX ZIP entry 與展開大小，再解析；異常、加密或無文字文件須安全失敗。
- 使用 Spring AI 2.0.1 `TokenTextSplitter`，自訂繁體中文標點，配置 Chunk Size、Overlap 與最大 Chunk 數。2.0.1 的 `TokenTextSplitter` 沒有原生 overlap 參數；需使用受測、保留來源位置的 wrapper 套用 overlap，並在切分後再次硬性驗證最大 Chunk 數。不得為取得 overlap 而升級 Spring AI。
- 文件 Preview 是獨立服務；TXT 嚴格 UTF-8 預覽及 DOCX 段落／表格原始順序、大小和 ZIP 安全限制必須保持，不可直接以 RAG 的 Tika 輸出替代使用者預覽。
- Chunk offsets 只有在 Chunk 文字可精確映射回正規化抽取純文字時才回傳；offset 是該純文字的 Java UTF-16 字元位置，不代表 PDF 位元組位置或 PDF 頁碼。無法精確映射時 API 欄位必須為 `null` 或明確回報不可用，不可填假值。
- `Document` Metadata 保存 `documentId`、`documentKey`、type、title、version、supplier、chunkIndex、offset、source hash、model generation、chunking version 及 Demo provenance。來源清單與 relevance score 取自 Advisor 回傳的實際檢索文件，LLM 不自行提供 Source ID 或分數。

## 4. 敏感資料安全（採既有方案 A）

- 沿用現有 OutboundDocumentPolicy、管理員針對內容版本/hash 的核准及 REQUIRES_REVIEW/SKIPPED 規則。
- 可遮罩的 Email、電話與台灣身分證字號以等長遮罩輸出，保留來源 Chunk offset 對應；憑證、銀行、商業及個人敏感內容仍須阻擋出站。
- 包含金額、折扣、銀行帳戶、認證資訊等敏感內容，依既有策略不可外送 Gemini Embedding；**MySQL 搜尋命中不構成 Chat 外送許可**。
- 僅已核准出站且狀態為 `READY` 的向量文件可進入 Retriever 候選；檢索候選不再經自製來源／語意驗證器篩除。敏感資料政策仍須在索引與出站核准流程中執行，不能委由 LLM 判斷。
- 文件級 ACL 暫緩；這不等於放棄現有登入/角色、原文讀取及索引操作授權。受此限制，部分敏感合約可能無法透過語意/AI 問答查詢，應誠實呈現。

## 5. 逐文件發布（取代日常全域 Gate）

1. 上傳與解析完成，文件在 MySQL 可為 READY，但**不代表可供語意查詢**。
2. 一般文件由管理員對**特定文件來源版本/hash**核准合法出站；固定可信 Demo Seed 由專用系統審核 seam 重新驗證來源及既有出站政策後決定是否核准。
3. 核准成功即排入**持久化、非同步**索引工作；不要求管理員另按 Backfill 或 Gate Audit。
4. 真實 Gemini Embedding → Spring AI PgVectorStore 寫入 `semantic_rag_vectors`；驗證文件版本、Chunk、model generation、來源 hash、維度與 metadata。
5. 只在該文件索引完整、政策合規與版本一致後**原子發布**；其他已發布文件始終可正常查詢。
6. 新文件索引失敗或待審核不會關閉所有已發布文件；停用文件即從檢索中排除。現有系統級 Generation/部署驗收可保留，但不作為每次上傳的全域阻斷。

**重試：** 429、逾時及暫時性外部 5xx 自動有限次、退避重試；Retry-After 是最短等待時間，本地指數退避以 maxRetryDelay 封頂。若伺服器要求的等待超出 MySQL DATETIME(6) 可保存範圍，工作轉為 FAILED 供管理員查核，不會提前自動重試。審核拒絕／違反敏感政策等不可自動重試。超過上限 FAILED 並提供管理員安全錯誤資訊及手動重試入口。重啟後任務狀態可復原；同一文件多次觸發必須冪等，舊代次或舊 hash 不可發布覆蓋新版。

Documents API 會分開回傳文件 ingestion `status`、持久化 `semanticReview` 與 `semanticIndex`。只有文字抽取、TokenTextSplitter 切塊、來源位置映射及 MySQL chunks 驗證完成才標示 Document `READY`；只有持久 worker 工作成功且 `semantic_rag_vectors` 已發布的來源、hash、generation、chunk、metadata 與向量維度證明全數吻合，Semantic 狀態才是 `INDEXED`（UI 顯示「已發布」）。缺少索引資料回 `PENDING`；PostgreSQL/provider 暫不可用回 `UNAVAILABLE` 或持續待處理，不能將外部索引推定為成功，也不能讓採購核心 API 因外部索引狀態故障而報錯。

### 舊資料表保留與新索引切換

- `document_metadata` 與 MySQL `document_chunks` 保留為權威文件與來源資料；不得刪改其 Agentic SQL 可讀取的欄位／版本契約。
- PostgreSQL `semantic_chunks`、`semantic_document_indexes`、`semantic_generations`、`semantic_gate_audit` 保留原結構及既有資料；Agentic SQL 的欄位與讀取契約不可變更。新問答只查 `semantic_rag_vectors`。目前遷移分支已加入從新向量索引同步投影至 `semantic_chunks` 的寫入路徑；PgVectorStore projection integration 與既有 Agentic SQL regression 類別已通過 Testcontainers Maven suite。正式／Demo 資料對帳仍未完成；詳見 [`semantic-rag-runbook.md`](../semantic-rag-runbook.md) 與 [`驗證記錄`](../evidence/spring-ai-rag-validation-2026-11-01.md)。
- 新表與舊表分離。不得把既有 embedding 複製到新表；新 Chunking／模型／metadata 契約不相同，必須從符合當前審核與出站政策的來源重做 Gemini Embedding。
- 遷移和切換程序、備份、可重入 Backfill、數量與來源完整性核對、啟用條件及回復方式，見 [`semantic-rag-runbook.md`](../semantic-rag-runbook.md)。

## 6. Demo 語料與首次審查

- Demo Seed 使用 `samples/documents/corpus-catalog.json` 記錄文件鍵、類型、theme、版本、標題、供應商/物料關聯、`SP-901` corpus provenance 與來源 SHA-256；`samples/documents/rag-fixtures.json` 記錄靜態問答及預期引用。Catalog schema 4 的 `businessFactApplicability` 描述 supplier/material/fact type/effective period，供 Demo 文件可信核准及索引 readiness 使用；問答時不由此欄位篩選相關候選或驗證模型答案。
- `DemoCorpusSyncService` 只讀取 demo Repository corpus 的 catalog 與來源，固定核對 schema 4、business fact applicability version 2、dataset key、corpus version、文件鍵、類型、標題、版本、supplier 關聯、政策、provenance 和 canonical-LF source hash。同步與系統核准共用 `DemoCorpusCatalogTrust`，要求 catalog 為一般檔案且非 symlink，且 UTF-8 內容換行正規化為 LF 後符合固定 SHA-256 `A831EB8B21771E39F525668972EE4526F124090AB1CCB416A551608DEB7DBDAD`；外部自製同 envelope catalog 不受信任。重跑以 `(datasetKey, documentKey, version)` 和持久 provenance 辨識基線，絕不只靠標題合併。
- 無 provenance 的舊 Seed 只有在 type/title/version/supplier 與 catalog 完全相符、唯一候選且資料庫儲存來源的 canonical-LF hash 完全相符時才可採用並寫入 provenance。候選歧義、來源或欄位不同、已存在 keyed baseline 被改動時，建立同步 conflict 並保留原文件；不覆寫其來源，也不覆蓋有效人工審查 audit。
- 只有 demo profile、明確允許的 MySQL DEMO 資料庫、demo 文件儲存根目錄、相符 Repository catalog entry、READY chunks/source proof 及當下 `OutboundDocumentPolicy` 都通過時，專用 system approval seam 才能記錄 `SYSTEM / TRUSTED_DEMO_CORPUS` 審查和 enqueue 現有 durable job。它不偽裝管理員，沒有 HTTP 授權入口；一般上傳與一般 API 仍走使用者既有審查流程。需審查或政策阻擋的結果會保留原因，不排索引工作。Policy 依具體敏感值與上下文判斷，不因單獨出現「姓名」「地址」「銀行」等詞而拒絕。只有固定 Catalog、原文 SHA、文件 provenance、報價主資料及雙資料庫目標均通過伺服器驗證的虛構 Demo 文件，才可略過商業金額規則；憑證、銀行帳戶、個資仍照常攔截，普通 Upload 不可自我宣稱 Demo。
- Semantic PostgreSQL URL 必須指向設定的 demo database，host 僅允許 Compose `pgvector` 或 loopback (`localhost`、`127.0.0.1`、`::1`)。URL 缺少或目標不符時保留文件 READY、記錄 `REQUIRES_REVIEW` 且不 enqueue；已驗證為 demo 的目標暫時不可連線時，durable job 可等待既有 worker 重試，API 必須顯示待處理/不可用，直到 PostgreSQL 發布證明核對成功。
- 純文字來源的 `sourceSha256` 使用 UTF-8 文字將 CRLF/CR 換行正規化為 LF 後計算；`samples/documents/*.txt` 固定以 LF 儲存，確保跨平台 hash 穩定。
- `SEED_BASELINE` 是唯一可能進入受限 system approval 的分類；policy 不允許時仍須人工處理。跨文件事實適用性按 Catalog 明確列出的 supplier、material、fact type 與期間篩選；預設、current 或 latest 查詢只使用 Catalog 指定且涵蓋今天的現行來源；缺少現行來源或來源已到期時回覆資料不足。歷史查詢可用明確年份、日期或期間，並依精確有效日期篩選。明確的歷史與現行比較要求同一 supplier/material/fact type 同時具備兩側來源，引用摘錄分別標示期間；含糊的以前及缺少任一側證據時 fail closed。受管制文件的 scope 不匹配時一律排除，歷史摘錄只保留相符期間證據及不含其他物料條件的句子。舊文件 metadata、原文、核准與 audit 保留；普通無 provenance Upload 不套用這項 Demo 專屬規則。SUP-005 人工 UI Upload 文件為 `MANUAL_UI_UPLOAD_ONLY`，沒有可信 provenance，不得被 Seeder 建立、採用或引用為 baseline fixture。
- Catalog/source 更新若與資料庫既有來源不同，sync 保留並標記衝突；一般文件仍由正常上傳、審查與版本流程處理。標題變更會讓舊 review 失效，不能複製舊核准。
- 靜態 Fixture PASS 只代表檔案、來源關聯及預期格式通過檢查，不表示 Gemini RAG 回答品質 PASS。

## 7. 文件全文預覽與原檔下載 API

文件內容以已授權的文件 ID 尋址；API 不接受儲存路徑，也不在 `DocumentResponse` 對外回傳 `storagePath`。

| Endpoint | 回應 | 說明 |
|---|---|---|
| `GET /api/v1/documents/{id}/preview` | PDF：`application/pdf` 原始 bytes；TXT／DOCX：`text/plain;charset=UTF-8` | PDF 以 inline 串流預覽。TXT 以嚴格 UTF-8 解碼完整原文。DOCX 轉為完整文字，段落和表格依文件 body 順序輸出；同列儲存格以 Tab 分隔、列與段落以換行分隔。 |
| `GET /api/v1/documents/{id}/file` | 原始檔案 Media Type 與 bytes | 以 attachment 串流下載原始檔案。 |

兩個端點皆要求已登入的 `PROCUREMENT_STAFF`、`PROCUREMENT_MANAGER` 或 `ADMIN`。回應設定 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff` 和安全的 `Content-Disposition` 檔名；全文預覽與原檔下載均限制在 20 MiB。文字預覽的來源格式可由 `Content-Disposition` 檔名副檔名辨別（`.txt` 或 `.docx`）；前端也可將兩者統一呈現為全文文字預覽。找不到文件回 `404 DOCUMENT_NOT_FOUND`；文件記錄存在但原檔缺失回通用 `404 DOCUMENT_FILE_NOT_FOUND`；無效文字／DOCX 格式回 `422 DOCUMENT_PREVIEW_INVALID`；超過大小上限回 `400 FILE_TOO_LARGE`。

預覽與下載只讀已存來源，不會變更 ingestion、審核、索引狀態，不會建立 indexing job，也不會呼叫 Gemini 或 Semantic Provider。DOCX 預覽使用獨立的 body-order 抽取器；RAG ingestion 使用 Tika，兩者的純文字順序不得混為同一契約。

## 8. 明確不做

- 文件級 ACL、逐人授權清單
- PDF 頁碼定位、條文號抽取與來源跳到頁數
- 無限制的新舊合約／條款比較、缺漏條款偵測（有界比較仍受文件適用性與來源授權限制；生成答案主張不再由後端逐句驗證）
- 違反敏感政策時另建本地 Embedding 以規避限制
- 整份企業文件的無條件外送與原文日誌

## 9. 驗收

- Spring AI Advisor 的向量檢索只回傳已發布 generation、`READY`、出站核准且相似度至少 0.4 的最多 10 份候選；其相關性與答案支持度由模型判斷。
- 回答來源取自 Advisor 實際回傳文件；沒有相似度達標候選時回 `INSUFFICIENT_EVIDENCE`。
- 未核准/敏感文件不經任何路徑送入 Gemini；Provider 故障不阻斷核心採購 MySQL 業務。
- 新文件排隊、失敗、重試、手動再索引不影響既有可查文件；管理員不需日常全域 Gate 操作。
- Demo Catalog 版本/hash 不符時不得自動核准或發布索引；衝突必須保留並由人工查核。文件 `READY`、政策核准、durable job 狀態及 Semantic `INDEXED` 必須各自有真實證據，不得互相推定。
