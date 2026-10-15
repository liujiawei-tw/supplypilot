# SP-722 Semantic RAG 操作與驗證

## 版本與選型

- Spring Boot 4.1.1、Spring AI 2.0.1。使用 Spring AI `EmbeddingModel` 與 `spring-ai-google-genai-embedding`；條件式建立 `GoogleGenAiTextEmbeddingModel`。未選 `AI_EMBEDDING_PROVIDER=google-genai` 或沒有 API Key 時不建立模型，核心 MySQL Backend 可啟動。
- 模型預設 `gemini-embedding-001`，輸出 768 維。Google 官方列出穩定 model ID、Developer API 支援、可配置 128–3072 維及 768 維建議值。文件輸入上限為 2,048 tokens；SP-710 chunk 長度為 900 字元並停用 provider 自動截斷，超限會記錄失敗。768 維向量在儲存與搜尋前做 L2 正規化。
- 以 pgvector `<=>` 做 cosine distance，`1 - distance` 是 relevanceScore。`SEMANTIC_TOP_K` 預設 5、`SEMANTIC_SIMILARITY_THRESHOLD` 預設 0.45。V1 不建 HNSW/IVFFlat，維持精確搜尋。新模型或維度須使用新的 `SEMANTIC_GENERATION_KEY`，完整 Backfill 和重新通過 Gate，舊索引保留但不可混用。
- `supplypilot.semantic.search.latency` 與 `supplypilot.semantic.search.candidates` 是查詢耗時與候選量指標。上線前以實際文件量記錄 p95、候選量與查詢正確率，待資料量增加後再評估 HNSW；本次不宣稱已有生產流量基準。
- 自訂 SQL 而非 `PgVectorStore`：本案需要 `(documentId, version, generationKey, chunkIndex)` 複合識別、整份文件原子發布、逐文件失敗狀態及跨庫來源核對。`PgVectorStore` 預設單一 `vector_store` 表與 HNSW，雖可設定 `NONE`，無法直接滿足上述索引生命週期。

官方依據：[Gemini Embedding 模型與維度](https://ai.google.dev/gemini-api/docs/models/gemini-embedding-001)、[Gemini Embeddings 使用及正規化](https://ai.google.dev/gemini-api/docs/embeddings)、[Spring AI Google GenAI Embedding](https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html)、[Spring AI PgVectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)。Google [價格頁](https://ai.google.dev/gemini-api/docs/pricing)與[官方發布文章](https://developers.googleblog.com/en/gemini-embedding-available-gemini-api/)列出 Embedding 計價；執行前重新確認當期價格。配額依專案/級別變化，請在 [AI Studio](https://aistudio.google.com/) 查實際 RPM/TPM/RPD；[Google 速率限制](https://ai.google.dev/gemini-api/docs/rate-limits)不保證固定額度。[區域清單](https://ai.google.dev/gemini-api/docs/available-regions)列有 Taiwan，但仍須由部署位置及實際帳戶確認。

## 資料與交易邊界

- MySQL 是 `DocumentMetadata`、`DocumentStatus`、供應商關聯與所有採購資料的唯一權威。PostgreSQL 只存語意片段、向量、來源定位、索引代次、處理狀態與 Gate audit。沒有跨庫 FK 或分散式 ACID 交易。
- 獨立 Flyway 路徑 `db/semantic/V1__semantic_index.sql`，首次 Backfill/Gate/查詢時在 PostgreSQL 遷移，不影響 MySQL 啟動。索引代次綁定 model ID、dimension、chunking version。所有向量在同份文件的一個 PostgreSQL 交易內替換，最後才把索引狀態設為 `READY`；`PROCESSING`、`FAILED`、`REQUIRES_REVIEW`、`SKIPPED` 不供查詢。
- 每份 MySQL READY 文件由既有 SP-710 chunk 及 offset Backfill。來源 SHA-256 包含 chunk index、offset、原文字；重跑同版本同雜湊不重新呼叫 Provider。中斷時 `PROCESSING` 可重跑，Provider 失敗記 `FAILED` 與安全錯誤代碼。模型或維度變更後新 generation 重新索引全部文件。

## 出站政策

只有已有角色授權的管理員可審核出站及執行 Backfill。管理員對特定 MySQL READY 文件執行 `POST /api/v1/semantic-index/documents/{id}/approve-outbound`；審核結果連同來源 SHA-256、文件版本、管理員 ID、時間與原因保存。所有文件類型（包括 `SUPPLIER_CONTRACT`、`QUOTATION`）都可在審核後索引。若內容檢查發現 Credential、銀行帳戶、實際金額／折扣或無法安全處理的個資，結果為 `REQUIRES_REVIEW`，整份文件不傳 Google；重跑 Backfill 不會解除此狀態。可辨識的電子郵件、台灣身分證號及手機號碼遮蔽後才呼叫 EmbeddingModel，並只送必要的 chunk 文字。來源或版本變更後需重新由管理員審核。管理員也可用 `POST /api/v1/semantic-index/documents/{id}/exclude-outbound` 明確排除文件，記錄 `SKIPPED/ADMIN_EXCLUDED`；文件類型不會自動造成排除。規則式偵測仍須配合人工審查，不能以 LLM 自述取代政策。不得在日誌記錄文件全文或原始敏感欄位。

查詢政策允許「報價、成本、單價、折扣」等一般業務概念；含實際憑證、帳戶號碼、金額等原始敏感值的問題被拒絕，電子郵件、身分證號及手機號碼先遮蔽再外送。

MySQL V19 增加審核版本、狀態與原因。V18 時期只有來源雜湊的既有核准紀錄不會自動升級為 `APPROVED`，管理員需重新審查，以免沿用缺少版本與狀態的授權。

## 本機與 Demo 設定

1. 設定 `.env` 的 MySQL、`SEMANTIC_DB_PASSWORD` 及 `SEMANTIC_DB_URL=jdbc:postgresql://localhost:5432/supplypilot_semantic`；執行 `docker compose --profile semantic up -d`。Demo 的 PostgreSQL 採相同 image/schema/環境變數，並需使用獨立的持久化儲存及密碼。
2. 設定 `AI_EMBEDDING_PROVIDER=google-genai`、`GEMINI_API_KEY`、`GEMINI_EMBEDDING_MODEL=gemini-embedding-001`、`SEMANTIC_DIMENSIONS=768`、`SEMANTIC_GENERATION_KEY=gemini-embedding-001-768-v1`。沒有金鑰時保持 provider `none`；只有 Backfill/語意查詢回 AI 503。`SEMANTIC_EMBEDDING_TIMEOUT` 預設 15 秒；超時回 `AI_TIMEOUT`。
3. 保持 `SEMANTIC_READ_ENABLED=false`。管理員先審查並對可出站文件核准來源雜湊，再用 `POST /api/v1/semantic-index/backfill?afterId=0&limit=25` 分頁處理；以回應 `lastId` 作下一頁 cursor，`morePossible=false` 後從 0 再執行一次可重試失敗文件。每次最多 100 份。回應有 READY/FAILED/SKIPPED/REQUIRES_REVIEW 計數。
4. 管理員讀取 `GET /api/v1/semantic-index/readiness`，處理所有 pending/failed/review；報表列出排除清單、來源版本、model ID、dimension、generation。Gate 必須至少有 **1 份 READY** 語意文件，且 FAILED、REQUIRES_REVIEW、PENDING 均為零；零文件或全部 SKIPPED 不通過。執行自動化授權/整合測試並留存證據。用 `POST /api/v1/semantic-index/gate-audit` 儲存帶管理員 ID 與 READY/SKIPPED/FAILED/REQUIRES_REVIEW/PENDING 統計的 Gate 紀錄；最新一次失敗審核會撤銷先前 PASS。`passed=true` 後才明確設定 `SEMANTIC_READ_ENABLED=true` 重啟。
5. 每次語意查詢重新檢查既有成功 audit 及所有 MySQL READY 文件的索引完整性，包括安全片段文字、chunk index、offset、維度與數量；新增 READY 文件或來源變更會關閉 Gate，直到 Backfill/重新稽核完成。查詢以精確相似度分頁取候選，略過舊的 PostgreSQL 來源後繼續找有效片段；命中後再核對 MySQL READY、版本、原始 chunk/offset、來源雜湊與出站政策，才送入 `EvidenceValidator` 及 `SEARCH_DOCUMENTS`。相似度不能授權文件。未命中回 `INSUFFICIENT_EVIDENCE`；PostgreSQL/Gemini 故障回 AI 503，無舊索引自動 fallback。

切換前保留完整 Gate JSON、授權測試結果、部署 commit 與設定變更紀錄。真實 Gemini API smoke test 需使用獲准測試文字和部署金鑰另行執行；Fake EmbeddingModel 測試不能視為真實 API 已驗證。

---

## 前端管理流程與展示驗收界線（2026-10-15 補充）

本 Runbook 上述 API、審核政策及 Semantic Read Switch 操作仍為權威，UI 改善只提供既有操作的視覺入口，不改後端語意或安全規則。

- **管理員操作 UI：** UI-B／SP-906 #112 在 Documents 工作區提供 outbound approval／exclude、Backfill、Readiness、Gate Audit 與 AI Query 來源核對入口，操作及錯誤／UNKNOWN 查核界線見 [Semantic 管理員 UI 操作說明](semantic-admin-ui.md)。這是 UI 技術交付，不代表真實 Provider／完整 Browser E2E 已完成。
- **本機六文件驗收：** 先核對五份正常可查的虛構文件基線；第六份文件由有權限帳號在瀏覽器實際上傳，經既有 Ingestion → ADMIN 出站審核 → Backfill → Gate Audit →（依操作手冊顯式設定讀取路徑）→ AI Query → Document ID／Version／Chunk／Offset 引用核對。新增 READY 文件後 Gate 暫時失效時，記錄恢復條件與實際耗時；未測不得宣稱可立即查詢。
- **Demo 權限：** AWS 公開展示共用 `PROCUREMENT_MANAGER` 帳號不具 Upload／Admin Review 權限；完整六文件人工操作安排於本機，以現有 Security Matrix 為準，不應擅自擴權。
- **保留未完成證據：** [SP-901 #43](https://github.com/liujiawei-tw/supplypilot/issues/43) 的程式交付已關閉；真實 Gemini Chat/Embedding、Gate、第六份 Source-cited Query 及 Browser Reset E2E 由仍開啟的 [SP-910 #117](https://github.com/liujiawei-tw/supplypilot/issues/117) 接手。Fake Provider 或 CI 綠燈不得標成真實環境 PASS。
- **不改既有部署順序：** SP-905 #110 文件繁中化後須重建新索引及 Gate；SP-903 先本機 Docker、後 AWS；最終驗收和影片在 SP-904 進行。

## Semantic 管理員 UI 操作（SP-906／Issue #112）

ADMIN 從 Documents 工作區進入 Semantic 索引管理；使用方式、各步驟 API、錯誤與 UNKNOWN 查核界線，以及第六份文件交接 #117 的 Browser 步驟，見 [`semantic-admin-ui.md`](semantic-admin-ui.md)。UI 僅提供既有 API 的明確操作入口，不新增審核狀態查詢 API，也不控制 `SEMANTIC_READ_ENABLED`。

## SP-905 繁中 Demo 語料索引更新

五份 baseline 文件現以 UTF-8 繁體中文保存，Demo Dataset Version 為 `sp-905-zh-tw-v1`。這些文件的舊英文 chunk、Source Hash、Embedding 與 Gate Audit 不可沿用。透過受控 Guarded Reset 建立新版 MySQL 文件及 chunk 後，重新執行每份文件的 ADMIN outbound review、Backfill、Readiness 與 Gate Audit；只有新 generation 的 Gate 通過後才可顯式啟用 Semantic Read。第六份 SUP-005 是人工 UI upload 驗收素材，永遠不加入 baseline Seeder。

SP-905 自動契約測試僅驗證 UTF-8／Unicode 儲存、Chunk offset 與來源切片一致，以及既有 API／來源契約；Fake Provider 或本機 lexical representation 不證明 Gemini 多語語意品質。中文問題查英文文件、英文問題查中文文件需由 SP-910 #117 使用實際 Provider 及 Browser 按其 Release Gate 驗證；在該驗收完成前不得宣稱跨語 Semantic PASS。
