# SP-722 Semantic RAG 操作與驗證

## 版本與選型

- 基線為 Spring Boot 4.1.1、Java 21、Spring AI BOM 2.0.1，不升級 Boot／Spring AI。正式文件問答使用 `ChatClient`、`RetrievalAugmentationAdvisor`、`VectorStoreDocumentRetriever`、Spring AI `PgVectorStore`、`ContextualQueryAugmenter` 與既有 Gemini `ChatModel`／`EmbeddingModel`。
- 依賴包含 `spring-ai-rag`、`spring-ai-pgvector-store` 與 `spring-ai-tika-document-reader`。目前使用 PgVectorStore 核心模組，不加入會自動綁定預設資料源的 starter；它由程式明確使用 `SEMANTIC_DB_URL` 專用 PostgreSQL `DataSource`／`JdbcTemplate` 建立，Boot 的 `spring.datasource` 仍指向 MySQL。
- 預設模型為 `gemini-embedding-001`、768 維；目前 Demo 設定為 `SEMANTIC_TOP_K=8`、`SEMANTIC_SIMILARITY_THRESHOLD=0.4`、`DOCUMENT_RAG_CHUNK_SIZE=500`、`DOCUMENT_RAG_CHUNK_OVERLAP_CHARACTERS=70`。VectorStore 最多先取 80 個候選，後處理最多交付 TopK 個來源。模型／維度/TokenTextSplitter 參數或 wrapper 版本改變時，使用新的 `SEMANTIC_GENERATION_KEY`，並重新切塊及索引，不得混用不同世代。Embedding 呼叫前重新檢查來源版本與出站核准，provider 自動截斷維持停用。
- PgVectorStore 使用 `semantic_rag_vectors`，距離度量為 cosine，索引類型明確設 `NONE`（exact similarity），不建 HNSW／IVFFlat。設定 `initializeSchema=false` 及 `remove-existing-vector-store-table=false`；由向前相容的 PostgreSQL Flyway migration 建表。應用程式啟動不得刪除或重建資料表。
- Spring AI 2.0.1 `TokenTextSplitter` 以 CL100K_BASE 切分，並沒有 overlap 設定；本專案以保留來源字元位置的 wrapper 套用可設定 overlap，該 wrapper 必須有繁體中文、英文、表格文字、換行及 offset 精準性測試。Splitter 的 `maxNumChunks` 實作可能額外產生尾端 chunk，因此應用程式需在寫入前再做硬性數量檢查；超限時失敗，不得靜默截斷。
- PDF／DOCX 使用 `TikaDocumentReader`、TXT 使用 `TextReader`。Reader 前仍套用 20 MiB、合法副檔名、DOCX ZIP entry／展開大小及異常文件檢查。`DocumentPreviewService` 保留完整 TXT／DOCX 使用者預覽契約，不能把 Tika ingestion 文字當成預覽輸出。
- Relevance score 必須採 Spring AI Document 的實際檢索分數；來源 offsets 僅能在 chunk 精確對應正規化抽取文字時填入，並代表 Java UTF-16 字元位置，不是 PDF byte offset／頁碼。無法精確映射時回傳 `null`／不可用，不可虛構。

官方依據：[Spring AI 2.0.1 RAG API](https://docs.spring.io/spring-ai/docs/2.0.1/api/org/springframework/ai/rag/advisor/RetrievalAugmentationAdvisor.html)、[Spring AI 2.0.1 PgVectorStore API](https://docs.spring.io/spring-ai/docs/2.0.1/api/org/springframework/ai/vectorstore/pgvector/PgVectorStore.html)、[Spring AI RAG 參考文件](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)、[Spring AI ETL Pipeline](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)、[Gemini Embedding 模型與維度](https://ai.google.dev/gemini-api/docs/models/gemini-embedding-001)、[Gemini Embeddings 使用及正規化](https://ai.google.dev/gemini-api/docs/embeddings)。Google [價格頁](https://ai.google.dev/gemini-api/docs/pricing)與[官方發布文章](https://developers.googleblog.com/en/gemini-embedding-available-gemini-api/)列出 Embedding 計價；執行前重新確認當期價格。配額依專案/級別變化，請在 [AI Studio](https://aistudio.google.com/) 查實際 RPM/TPM/RPD；[Google 速率限制](https://ai.google.dev/gemini-api/docs/rate-limits)不保證固定額度。[區域清單](https://ai.google.dev/gemini-api/docs/available-regions)列有 Taiwan，但仍須由部署位置及實際帳戶確認。

## 資料與交易邊界

- MySQL `document_metadata` 與 `document_chunks` 是文件來源、狀態、權限及交易資料的權威來源。PostgreSQL 新增 Spring AI `semantic_rag_vectors` 供正式 RAG 查詢與寫入；沒有跨庫 FK 或分散式 ACID 交易。
- 保留既有 PostgreSQL `semantic_chunks`、`semantic_document_indexes`、`semantic_generations`、`semantic_gate_audit` 結構及資料。Agentic SQL 仍可讀取 `document_chunks`、`semantic_chunks` 的內容與來源版本以及 `semantic_document_indexes` 狀態；本次不得修改 Agentic SQL 程式、這些表的欄位／可讀取契約或 MySQL `document_chunks`。舊 `semantic_chunks` 不再是正式文件問答檢索來源；新 RAG 僅使用 `semantic_rag_vectors`。
- PostgreSQL Flyway 保留既有 semantic migrations，新增獨立、可追蹤的 forward migration 建立 Spring AI 相容 schema。向量 Store 啟動初始化與刪表選項均關閉；migration 不得 `DROP`／清空舊表，也不可設定 `remove-existing-vector-store-table=true`。不得執行 `docker compose down -v`。
- 新索引 generation 綁定 model ID、dimension、chunking version、overlap wrapper version 與來源 hash。MySQL durable job 重試保持冪等；向量文件以文件 ID／version／generation／chunk index 的穩定 ID upsert。只有全部 chunk 寫入並完成 count、metadata、dimension、source hash 核對後，索引 registry 才能標示 `READY`。沒有跨庫原子交易時，失敗保持不可查並可重試；舊 generation 不得發布覆蓋新 generation。

## 新舊 Schema 遷移、備份與正式啟用

1. **切換前備份／盤點。** 由維運人員對 MySQL 與既有 PostgreSQL 建立可還原的邏輯備份或 volume snapshot；保存目前應用程式 SHA、設定、generation key 與各表 row count。盤點 `document_metadata`、`document_chunks`、`semantic_generations`、`semantic_document_indexes`、`semantic_chunks`、`semantic_gate_audit`、`semantic_index_jobs` 的文件／版本／狀態／chunk 數量與來源 hash。這份程序文件不代表已對任何正式或 Demo 資料庫執行備份。
2. **擴充 Schema。** 套用新的版本化 PostgreSQL migration 建立 `semantic_rag_vectors`；確認 extension、`id`、`content`、`metadata`、`embedding vector(<設定維度>)` 與索引型態相符。既有表和 volume 均保留。Migration 可重入／有版本追蹤，不以應用程式啟動時自動建表作為遷移工具。
3. **重做向量。** 由 MySQL READY 文件清單以穩定 cursor 分頁執行持久化 Backfill。每份文件先重核目前 version、source hash、出站核准與 `OutboundDocumentPolicy`，再以當前 `TokenTextSplitter` 版本及 Gemini `EmbeddingModel` 產生新向量；不得複製 `semantic_chunks.embedding`。同一文件 job 重跑應 upsert 相同 ID，保留有限次 retry／失敗原因；來源或批准代次變動時隔離舊 job。
4. **完整性核對。** 逐文件核對 MySQL 權威 chunk 與新向量表的 `documentId`、version、supplier、type、document key、chunk index、offset、來源 hash、generation、embedding dimensions、metadata 與 chunk count；完成全體文件 READY／排除／待審／失敗分類及總數對帳。執行不含敏感資料的可重現檢索案例，逐項核對 retrieval、答案事實、來源、版本、授權與拒答。任何缺少或多出的 chunk、維度錯誤、版本不一致或未授權結果都阻擋切換。
5. **正式啟用 Gate。** `SEMANTIC_READ_ENABLED` 維持 `false`，直到備份可讀、migration、符合資格文件的 Backfill、完整性對帳、權限／撤銷／Demo 版本隔離測試、20 題固定案例與必要整合測試通過，並保存當次 SHA／指令／結果。通過後由授權維運人員明確設定 `SEMANTIC_READ_ENABLED=true`。啟用後 Query 必須走 Advisor → PgVectorStore；不得回退舊 Retrieval，也不得讓未核准文件進入 Gemini Context。
6. **失敗回復。** Gate 未過時保持 read switch 關閉、修正並重跑失敗文件；停止使用新索引不需要刪除新表。若需回到上一版應用程式，只能在核實舊 schema／索引仍符合該版本契約及備份可還原後進行。舊表與新表都保留，勿以刪表、volume 清除或回寫猜測資料當 rollback。切回舊版後，新上傳／新版本可能需重做舊索引，須另行盤點；不得把這種條件宣稱為已驗證回復。

## 出站政策

只有已有角色授權的管理員可審核出站及執行 Backfill。管理員對特定 MySQL READY 文件執行 `POST /api/v1/semantic-index/documents/{id}/approve-outbound`；審核結果連同來源 SHA-256、文件版本、管理員 ID、時間與原因保存。所有文件類型（包括 `SUPPLIER_CONTRACT`、`QUOTATION`）都可在審核後索引。若內容檢查發現 Credential、銀行帳戶、實際金額／折扣或無法安全處理的個資，結果為 `REQUIRES_REVIEW`，整份文件不傳 Google；重跑 Backfill 不會解除此狀態。可辨識的電子郵件、台灣身分證號及手機號碼遮蔽後才呼叫 EmbeddingModel，並只送必要的 chunk 文字。來源或版本變更後需重新由管理員審核。管理員也可用 `POST /api/v1/semantic-index/documents/{id}/exclude-outbound` 明確排除文件，記錄 `SKIPPED/ADMIN_EXCLUDED`；文件類型不會自動造成排除。規則式偵測仍須配合人工審查，不能以 LLM 自述取代政策。不得在日誌記錄文件全文或原始敏感欄位。

查詢政策允許「報價、成本、單價、折扣」等一般業務概念；含實際憑證、帳戶號碼、金額等原始敏感值的問題被拒絕，電子郵件、身分證號及手機號碼先遮蔽再外送。

MySQL V19 增加審核版本、狀態與原因。V18 時期只有來源雜湊的既有核准紀錄不會自動升級為 `APPROVED`，管理員需重新審查，以免沿用缺少版本與狀態的授權。

## 本機與 Demo 設定

1. 使用既有 MySQL 與 PostgreSQL 服務；設定 `.env` 的 MySQL、`SEMANTIC_DB_PASSWORD` 及 `SEMANTIC_DB_URL=jdbc:postgresql://localhost:5432/supplypilot_semantic`。可執行 `docker compose --profile semantic up -d` 啟動既有 semantic PostgreSQL profile。Demo PostgreSQL 使用原有服務與獨立持久化設定；不可建立第二台 PostgreSQL，也不可清除任何 volume。
2. 設定 `GEMINI_API_KEY`、`GEMINI_EMBEDDING_MODEL=gemini-embedding-001`、`SEMANTIC_DIMENSIONS=768`、新的 `SEMANTIC_GENERATION_KEY`、TopK／threshold 及 Chunk Size／Overlap／最大數量。沒有合法 Gemini key 時應標記真實 Provider 測試 `NOT RUN`；不以 Fake Provider 冒稱 Gemini 驗收。`SEMANTIC_EMBEDDING_TIMEOUT` 預設 15 秒；超時按安全錯誤與工作 Retry 規則處理。
3. 先建立／核對可還原備份，再按上方程序盤點舊表及資料量。使用版本化 migration 新增 `semantic_rag_vectors`，確認 migration 不會刪除舊表或初始化時清表。保持 `SEMANTIC_READ_ENABLED=false`。
4. 管理員先審查並對可出站文件核准來源雜湊，再用 `POST /api/v1/semantic-index/backfill?afterId=0&limit=25` 分頁處理；以回應 `lastId` 作下一頁 cursor，`morePossible=false` 後再由 0 執行，以重試符合條件的失敗文件。每次最多 100 份。回應有 READY/FAILED/SKIPPED/REQUIRES_REVIEW 計數。持久 Job worker 應使用新的 Spring AI PgVectorStore 寫入，而非舊 `semantic_chunks` 表。
5. 管理員讀取 `GET /api/v1/semantic-index/readiness`，處理所有 pending/failed/review；報表列出排除清單、來源版本、model ID、dimension、generation。Gate 必須至少有 **1 份 READY** 語意文件，且 FAILED、REQUIRES_REVIEW、PENDING 均為零；零文件或全部 SKIPPED 不通過。執行固定 20 題案例、授權／版本／撤銷／Demo 隔離、真實 PgVectorStore PostgreSQL 整合測試並留存 SHA 綁定證據。用 `POST /api/v1/semantic-index/gate-audit` 記錄統計；最新一次失敗審核會撤銷先前 PASS。只有 Gate 完整通過後，授權維運人員才明確設定 `SEMANTIC_READ_ENABLED=true`。
6. 啟用後正式查詢路徑為 `ChatClient → RetrievalAugmentationAdvisor → VectorStoreDocumentRetriever → PgVectorStore`。metadata filter 限制發佈世代；DocumentPostProcessor 再讀 MySQL 即時核對 READY、版本、審核、Demo 範圍、來源 hash 與 `OutboundDocumentPolicy`，通過後才把 Context 交給 Gemini ChatModel。來源由已驗證的 Spring AI Document metadata 建立。空證據回 `INSUFFICIENT_EVIDENCE` 並短路生成；PostgreSQL/Gemini 故障按 API 契約回安全錯誤，不得 fallback 至舊 RAG。

切換前保留完整 Gate JSON、授權測試結果、部署 commit 與設定變更紀錄。真實 Gemini API smoke test 需使用獲准測試文字和部署金鑰另行執行；Fake EmbeddingModel 測試不能視為真實 API 已驗證。

---

## 前端管理流程與展示驗收界線（2026-10-15 補充）

本 Runbook 上述 API、審核政策及 Semantic Read Switch 操作仍為權威，UI 改善只提供既有操作的視覺入口，不改後端語意或安全規則。

- **管理員操作 UI：** UI-B／SP-906 #112 在 Documents 工作區提供 outbound approval／exclude、Backfill、Readiness、Gate Audit 與 AI Query 來源核對入口，操作及錯誤／UNKNOWN 查核界線見 [Semantic 管理員 UI 操作說明](semantic-admin-ui.md)。這是 UI 技術交付，不代表真實 Provider／完整 Browser E2E 已完成。
- **本機六文件驗收：** 先核對五份正常可查的虛構文件基線；第六份文件由有權限帳號在瀏覽器實際上傳，經既有 Ingestion → ADMIN 出站審核 → Backfill → Gate Audit →（依操作手冊顯式設定讀取路徑）→ AI Query → Document ID／Version／Chunk／Offset 引用核對。新增 READY 文件後 Gate 暫時失效時，記錄恢復條件與實際耗時；未測不得宣稱可立即查詢。
- **Demo 權限：** AWS 公開展示共用 `PROCUREMENT_MANAGER` 帳號不具 Upload／Admin Review 權限；完整六文件人工操作安排於本機，以現有 Security Matrix 為準，不應擅自擴權。
- **保留未完成證據：** [SP-901 #43](https://github.com/liujiawei-tw/supplypilot/issues/43) 的程式交付已關閉；真實 Gemini Chat/Embedding、Gate、第六份 Source-cited Query 及 Browser Reset E2E 由仍開啟的 [SP-910 #117](https://github.com/liujiawei-tw/supplypilot/issues/117) 接手。Fake Provider 或 CI 綠燈不得標成真實環境 PASS。
- **不改既有部署順序：** SP-905 #110 文件繁中化後須依新 generation 重建 PgVectorStore 索引及 Gate；SP-903 先本機 Docker、後 AWS；最終驗收和影片在 SP-904 進行。

## Semantic 管理員 UI 操作（SP-906／Issue #112）

ADMIN 從 Documents 工作區進入 Semantic 索引管理；使用方式、各步驟 API、錯誤與 UNKNOWN 查核界線，以及第六份文件交接 #117 的 Browser 步驟，見 [`semantic-admin-ui.md`](semantic-admin-ui.md)。UI 僅提供既有 API 的明確操作入口，不新增審核狀態查詢 API，也不控制 `SEMANTIC_READ_ENABLED`。

## SP-905 繁中 Demo 語料索引更新

五份 baseline 文件現以 UTF-8 繁體中文保存，Demo Dataset Version 為 `sp-905-zh-tw-v1`。這些文件的舊英文 chunk、Source Hash、Embedding 與 Gate Audit 不可沿用。透過受控 Guarded Reset 建立新版 MySQL 文件及 chunk 後，重新執行每份文件的 ADMIN outbound review、Backfill、Readiness 與 Gate Audit；只有新 generation 的 Gate 通過後才可顯式啟用 Semantic Read。第六份 SUP-005 是人工 UI upload 驗收素材，永遠不加入 baseline Seeder。

SP-905 自動契約測試僅驗證 UTF-8／Unicode 儲存、Chunk offset 與來源切片一致，以及既有 API／來源契約；Fake Provider 或本機 lexical representation 不證明 Gemini 多語語意品質。中文問題查英文文件、英文問題查中文文件需由 SP-910 #117 使用實際 Provider 及 Browser 按其 Release Gate 驗證；在該驗收完成前不得宣稱跨語 Semantic PASS。

## V2-302 逐文件核准索引工作（Issue #146）

V2-302 將核准與 Embedding 拆成可恢復的背景工作。ADMIN 核准 READY 文件時，後端在同一個 MySQL 交易更新核准狀態及文件索引代次，並以文件版本、來源 SHA-256、語意模型 generation 與文件代次建立唯一 job。只有 APPROVED 文件會 enqueue；REQUIRES_REVIEW 或 SKIPPED 文件不會呼叫 Gemini。工作執行前與每個 chunk 的 provider 呼叫前會重新核對來源、版本、代次、核准狀態及 OutboundDocumentPolicy，只送出政策產生的安全文字。

- GET /api/v1/semantic-index/jobs?limit=50 列出近期工作；POST /api/v1/semantic-index/jobs/{jobId}/retry 僅允許 ADMIN 將仍符合目前核准快照的 FAILED 工作重新排入佇列。
- 狀態為 QUEUED、PROCESSING、RETRY_WAIT、SUCCEEDED、FAILED、SUPERSEDED。工作使用持久化 MySQL queue；process 重啟後逾期 lease 可重新領取，達到最大嘗試次數的逾期工作會成為 FAILED。
- HTTP 429、timeout 與 5xx 以指數退避重試，尊重有界的 Retry-After；其他 provider 4xx 與無效向量不重試。管理端只顯示安全錯誤代碼，不保存或回傳 provider 錯誤本文。
- Spring AI PgVectorStore 將穩定 ID 的新向量寫入 `semantic_rag_vectors`；完成 chunk 數量、維度、generation、來源 hash 與 metadata 完整性核對後才發布索引狀態。發布時程式會從新向量表同步維護 `semantic_chunks` 相容投影；舊 schema 與既有列保留，`semantic_chunks` 不參與文件問答檢索。失敗或未完成的代次不可進入查詢 Context；較舊文件代次或同代次不同來源雜湊不得覆蓋目前發布。查詢逐文件核對 MySQL 即時狀態、版本、來源 hash、核准政策與 Evidence Validator。遷移分支的 PgVectorStore projection integration 與 Agentic SQL regression 類別已通過；Demo／正式資料投影對帳仍未執行。
- 配置由 SEMANTIC_INDEX_WORKER_ENABLED、SEMANTIC_INDEX_POLL_INTERVAL_MILLIS、SEMANTIC_INDEX_LEASE_DURATION、SEMANTIC_INDEX_MAX_ATTEMPTS、SEMANTIC_INDEX_INITIAL_RETRY_DELAY、SEMANTIC_INDEX_MAX_RETRY_DELAY 控制。預設 worker 啟用、輪詢 1 秒、lease 5 分鐘、最多 4 次、退避 2 秒起且上限 1 分鐘。
- 以 job 清單檢查失敗代碼與嘗試次數；確認目前文件仍 READY 且核准有效後按「手動重試」。文件版本、來源、核准或模型 generation 已變更時，舊工作會被隔離，須對目前文件重新核准才能建立新工作。

Fake Provider 整合測試只驗證交易邊界、job 狀態、重試與資料隔離，不代表真實 Gemini API 通過。真實 Provider smoke test 仍須使用獲准測試文字及部署金鑰，並另行保存回應證據。

## 目前遷移分支狀態（2026-11-01）

本節描述功能分支中的程式狀態，不代表 main、任何已部署環境或實際資料庫已遷移。該分支已接上 `DocumentQueryService → ChatClient → RetrievalAugmentationAdvisor → VectorStoreDocumentRetriever → PgVectorStore`，新增 `semantic_rag_vectors` PostgreSQL migration，並讓索引工作寫入 Spring AI PgVectorStore。MySQL 仍是文件主檔、狀態及 source-chunk 權威資料庫；`SEMANTIC_READ_ENABLED` 預設為 `false`。

**歷史狀態快照（後續投影實作前）：** 遷移早期尚未證明會同步維護 `semantic_chunks` 相容投影。此狀態已被後續程式與測試取代：新索引會同步維護投影，PgVectorStore projection integration 與 Agentic SQL regression 類別已通過本機測試。以下保留當時測試記錄的狀態演進；目前原始驗證摘要見 [`Spring AI RAG 驗證記錄`](evidence/spring-ai-rag-validation-2026-11-01.md)。

### 目前驗證記錄

Maven 全量測試、遷移相關通過項目、read gate 修正的 targeted regression、Gemini provider smoke、Compose health smoke 與失敗分診，詳見 [`Spring AI RAG 驗證記錄`](evidence/spring-ai-rag-validation-2026-11-01.md)。全量 Maven `verify` 目前是 **FAIL**，不能宣稱 Backend 全量 CI 通過。`SEMANTIC_READ_ENABLED` 預設仍為 `false`。

目前沒有正式／Demo 文件數、chunk 數、資料庫 Backfill 結果或完整性對帳結果，也沒有正式啟用資格。除非本 Runbook 的備份、migration、投影、Backfill、核對、測試及切換條件全部以相同交付 SHA 的證據驗證完成，保持讀取閘門關閉；不得執行 `docker compose down -v` 或刪除／重建任何資料 Volume。
