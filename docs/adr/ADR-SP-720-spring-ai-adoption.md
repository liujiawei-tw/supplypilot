# ADR-SP-720 — Adopt Spring AI for SupplyPilot Assistant

- Status: Accepted for planned SP-720 / SP-721 implementation
- Date: 2026-10-11
- Scope: E7 AI Assistant / Controlled Tool Calling
- Baseline: Spring Boot 4.1.x, Java 21, existing SP-710 RAG

## Context

SP-700 and SP-710 have been implemented and merged. The original SP-720 / SP-721 requirements describe AI query and tool boundaries without making Spring AI mandatory. The team now explicitly chooses Spring AI for the remaining E7 assistant work, retaining the previously delivered services and business rules.

## Decision

1. SP-720 must use a stable Spring AI **2.0.x** release, compatible with Spring Boot 4.1.x, with `ChatClient` / `ChatModel` and `@Tool` or `ToolCallback`; do not use 2.1 Milestone as a production baseline. Determine exact patch and provider starter from verified Maven dependencies at implementation time.
2. At least one actual LLM provider must be configurable and usable; credentials and model configuration come from runtime environment / secret management. Automated tests use Fake ChatModel / Tool Callback and must not call paid providers.
3. SP-720 exposes an explicit read-only allowlist for current inventory (including name resolution and bounded multi-material batches), active supplier-material quotes and backend-calculated averages, supplier master facts, exact PO-number lookup, open PO lists and SP-710 document retrieval. Saved recommendation / ranking tools are available only for explicit questions about those legacy snapshots; they must not substitute for current quotes or supplier master facts. Tools run under the current user's authorization. No arbitrary bean / repository / SQL access; no system-admin impersonation.
4. Do not duplicate deterministic procurement calculation or rewrite SP-710 parsing, chunking, embedding, retrieval, and Source / Version evidence. The RAG tool delegates to the existing service.
5. SP-721 reuses this integration and produces schema-validated draft proposals. A separate backend-verified explicit user confirmation is required before calling the existing `PurchaseOrderService.createDraft()`; proposals alone never write.
6. The AI provider may fail independently; core procurement API availability must not depend on it. External AI calls are outside long DB transactions.

## Consequences

- SP-720 and SP-721 Issue descriptions, master backlog and relevant architecture, environment, security and testing specifications are amended.
- Existing completed SP-700 / SP-710 work remains unchanged.
- Tool permission, provider error mapping, fake-provider integration testing, evidence provenance and confirmation-before-write become explicit acceptance gates.
- Spring AI does not itself grant data access; Spring Security remains the authorization authority.

## References

- https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
- https://spring.io/projects/generations/
- GitHub Issues #32 and #33

## Confirmed design choices (2026-10-11; supersedes conflicting drafts)

- **LLM provider**: Google Gemini, preferring a low-cost Flash model. The exact model identifier must be validated against available API capabilities, Tool Calling support and pricing during SP-720 implementation; it remains configurable.
- **Framework and tools**: stable Spring AI 2.0.x, ChatClient / ChatModel, model-selected read-only Tool Calling executed by Spring AI and authorized again through current-user Backend Method Security. No arbitrary tools or privileged identity.
- **RAG strategy**: SP-710 remains unchanged and is integrated in SP-720. A **separate new SP-722 (#80)** upgrades semantic retrieval using **Google Gemini Embedding and PostgreSQL + pgvector**. MySQL remains the source of truth for procurement and DocumentMetadata; do not represent cross-database steps as atomic distributed transactions.
- **Answer trust**: explanations from LLM, while backend verifies structured numeric values, identifiers, units and authoritative business records. Unsupported/contradictory assertions must not be returned as trusted answers.
- **Conversations and transport**: V1 single-turn, no persisted chat memory; synchronous JSON response only.
- **SP-721 confirmation**: Proposal → server-issued Confirm Token/ID → backend verification → existing PurchaseOrderService.createDraft() → DRAFT. Client-provided booleans and LLM statements are not confirmation.
- **Provider availability**: optional AI activation; missing/unavailable provider returns established AI 503 contract, never prevents core business APIs starting.
- **Observability / cost controls**: basic token usage when available, latency, errors, bounded per-request tokens/tool-loop count/provider timeout, redaction of PII, credentials and sensitive procurement content.
- **Provider details awaiting runtime verification, not user selection**: exact Flash model ID, embedding model ID/dimension, max output tokens and Google-supported API endpoint; these must be verified and documented in SP-720/SP-722 implementation rather than invented.

## Semantic RAG ownership

SP-722 owns pgvector installation/schema, embeddings, migration/backfill, reindex idempotency, dimension/model-version compatibility, vector query, MySQL status gating and failure reconciliation. SP-720 must not expand to introduce PostgreSQL or rewrite SP-710. See [Issue #80](https://github.com/liujiawei-tw/supplypilot/issues/80).

---

## SP-720 已確認的產品行為決策（2026-10-11；八項補充）

1. **單一模型**：Google Gemini，固定使用一個低成本 Flash 類 Chat Model；以可配置、經驗證的 model ID 部署；本 Issue 不實作多模型切換或自動 fallback 到旗艦模型。
2. **憑證與額度**：使用 Google AI Studio API Key，透過安全環境設定注入；啟用月度用量／預算觀察及超額警示設計。注意平台費用警示不等於應用端硬性費用上限；未取得可靠官方預算控制能力前不可宣稱已強制封頂。按可用 usage metadata 做估算與告警，不能取得時應明確標示無法精確計價。
3. **複合查詢**：單次問題可以由 Spring AI 選擇並依序呼叫多個*已核准唯讀* Tools；仍需 Tool Call 次數／迴圈數／Timeout 上限，所有 Tools 以目前使用者身份再次授權，不擴張成寫入工具。
4. **缺少必要識別資料**：不猜測 Material、Recommendation、Supplier 或 PO；回覆需要補充的欄位，不觸發未授權或任意猜測查詢。V1 沒有 Chat Memory，後續需重新提出完整問句。
5. **回答契約**：自然語言說明 + 可核對的 Backend 數值與來源。後端維持結構化欄位的權威，來源不由 LLM 自行產生；維持原 AI Query JSON contract，若須新增明確的補充資訊狀態，先以最小相容性調整實作並補 API 測試。
6. **模型回答不可信時安全降級**：當**已成功取得授權的、足夠且可信的 Backend Facts**，但 LLM 的文字含有矛盾／未被支援的數值或內容時，可改回由 Backend 產出的確定性 facts answer，且標明 fallback 來源；不能把模型不可信內容當成權威。**Provider unavailable／timeout／無 Key 應依原規格回 503，不能用 fallback 掩蓋；RAG INSUFFICIENT_EVIDENCE 必須維持證據不足，不得改寫為肯定答案。** 無可驗證 Backend Facts 時不得 fallback 成虛構答案。對 Provider invalid-response 的錯誤碼／fallback 邊界須在測試中明示。
7. **資料出站最小化**：只把完成特定查詢所必需的工具欄位與 RAG 相關片段傳送 Gemini；最小化 Supplier／Contract 等商業敏感資訊，嚴禁傳送 Credential、完整資料庫 Dump 或無關文件。Tool Result 應有欄位白名單／長度限制並避免記錄原始機敏 Payload。
8. **真實 Demo**：正式 Demo 使用真正的 Gemini Provider；未配置 Key 時 AI Query 回既有 503，但 Core API 正常可用。Fake ChatModel 僅供 Automated Tests，不能以 deterministic formatter 宣稱完成正式 AI Demo。

### 八項新增驗收／測試
- [ ] 真實 ChatClient + 單一 Gemini Flash 模型可由安全設定啟用；不依賴免費／付費配額推測。
- [ ] 同一查詢可呼叫兩個以上已核准唯讀 Tools，且受工具執行數量／輪數／時間上限限制；沒有寫入。
- [ ] 缺少必要識別資料時不猜測，回覆使用者需補充的資訊。
- [ ] 正常回答附可核對的結構化數值與來源；數值錯置／矛盾時安全 fallback 到 Backend Facts 或依明確條件失敗。
- [ ] Provider unavailable／timeout／未配置 Key 回 503；RAG 證據不足仍回 INSUFFICIENT_EVIDENCE。
- [ ] Gemini Request Payload 只含必要資料／相關文件片段，排除金鑰與無關商業資料。
- [ ] 能記錄／估算請求用量、延遲、失敗與預算警示，不能宣稱 Google AI Studio 原生具備未驗證的硬額度限制。
- [ ] Test 使用可控制的 Fake ChatModel，不呼叫真實付費 Provider；Demo 手動 Smoke Test 使用真實 Gemini。

---

## SP-722 五項確認決策（2026-10-11）

此節為 Issue #80 的正式產品／部署邊界，保留原架構：MySQL 管理 DocumentMetadata、DocumentStatus、Supplier 關聯與採購交易；PostgreSQL + pgvector 管理 Semantic Chunk、Embedding、索引與來源定位；跨庫不是同一 ACID Transaction。

1. **驗證後切換**：在正式文件查詢從 SP-710 MySQL lexical retrieval 切換到 SP-722 semantic retrieval 之前，完成所有符合政策的有效文件 Backfill、來源／版本／Chunk／維度一致性檢查、MySQL READY 與授權查詢驗證，並形成可查驗的切換 Gate（紀錄模型 ID、維度、索引代次與檢查結果）。未通過 Gate 不得啟用新的 semantic read path。切換以顯式設定操作；不得因 Deploy 自動偷偷切換。
2. **故障回 503，不自動回退**：SP-722 成為正式文件 read path 後，PostgreSQL 不可用、Gemini Embedding 不可用／逾時時，文件語意查詢依既有 AI 503 Error Contract 失敗；不能自動切回 SP-710 舊 lexical search 並宣稱同一品質。Core MySQL 業務 API 應保持可用；沒有命中且服務正常時仍用 INSUFFICIENT_EVIDENCE，而非 503。
3. **所有符合資格的既有有效文件 Backfill**：以 MySQL 為文件權威來源。針對可索引且符合出站政策的文件及其當前有效版本執行完整 Backfill，保留進度、失敗原因、重試與冪等（documentId + version + modelId + dimension + chunkingVersion + indexGeneration）。不符合政策的文件明確標記 SKIPPED/REQUIRES_REVIEW，不視為索引成功；切換 Gate 不得悄悄忽略，需有可審計的範圍／排除清單。舊模型與新模型向量不可混用；失敗／部分完成向量不可被標記可供查詢。
4. **V1 Exact Similarity**：PostgreSQL + pgvector V1 使用 exact vector similarity；不要求 HNSW。應註記查詢維度、distance metric、topK、資料量與可觀測基準，並保留之後評估 HNSW 的路線，但本 Issue 不強制導入 HNSW。
5. **允許經授權企業文件、排除敏感內容**：僅授權文件可進入 Gemini Embedding pipeline。送出前依文件／供應商權限與明確的出站白名單、機敏欄位排除／遮蔽政策檢查；不得傳 Credentials、個資、銀行帳戶、無關商業資料、完整無關附件等。不能安全遮蔽的文件不得外送，狀態標為 REQUIRES_REVIEW；原文件依既有儲存及存取控制保護。查詢時也須對 MySQL 文件 READY／授權／版本做後驗檢查，不能以向量相似度代替權限。敏感資訊分類、遮蔽應由可驗證的後端規則實作，不能單靠 LLM 自述「已過濾」。

### Acceptance Gate / tests

- [ ] 已索引的所有合規有效文件完成 Backfill，對排除／待審文件有明確報表和切換 Gate 判斷；記錄每份文件進度、失敗重試、索引代次、模型 ID、維度、版本。
- [ ] Semantic 啟用前 Gate 未通過不可切換；通過後 Query 使用 pgvector exact similarity，驗證 model/dimension/version/READY/source and user authorization；不得返回 partial index 為有效證據。
- [ ] 切換後 PostgreSQL/Gemini 出錯回既有 503，不自動使用 SP-710；服務正常但證據不足回 INSUFFICIENT_EVIDENCE；核心 API 不受影響。
- [ ] 含敏感資訊的文件送出前被白名單排除／遮蔽或 SKIPPED/REQUIRES_REVIEW；測試驗證 Fake EmbeddingModel 不會收到敏感文字。
- [ ] Testcontainers MySQL + PostgreSQL pgvector、Fake EmbeddingModel、跨庫部分成功／失敗恢復、冪等 Reindex、權限／狀態隔離、Full Maven Verify。
- [ ] 正式 Google Gemini Embedding 的模型 ID、維度、價格、可用區域及 provider integration 必須在實作時核實；真實 API smoke test 未執行時不得宣稱已驗證。

### Scope
不遷移核心 MySQL 業務資料、不增加跨庫 FK、不改寫 SP-720 Tool Allowlist、不改動 SP-721 Confirmation、無 OCR／Multi-Agent／完整新前端。部署／資料出站可能涉及法務／公司授權時，沒有授權不得傳送真實文件。
