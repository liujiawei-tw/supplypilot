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
3. SP-720 exposes only an explicit read-only allowlist: recommendation, supplier ranking, inventory, open PO and SP-710 document query. Tools call existing owning Application / Query Services under current-user authorization. No arbitrary bean / repository / SQL access; no system-admin impersonation.
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

