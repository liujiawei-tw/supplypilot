# SupplyPilot — Environment & Configuration Specification

> **目的**：定義 SupplyPilot V1 local / test / demo / production profiles、Environment Variables、Secret Handling 與前後端 Configuration。

---

# 1. Profiles

Backend：

```text
local
test
demo
prod
```

Files：

```text
application.yml
application-local.yml
application-test.yml
application-demo.yml
application-prod.yml
```

不要將 secret 寫死在 profile file。

---

# 2. Common Environment Variables

```text
SPRING_PROFILES_ACTIVE

DB_URL
DB_USERNAME
DB_PASSWORD

JWT_SECRET
JWT_EXPIRATION_SECONDS

FRONTEND_ORIGIN

AI_CHAT_PROVIDER
GEMINI_API_KEY
GEMINI_MODEL
AI_PROVIDER_TIMEOUT
AI_MAX_OUTPUT_TOKENS
AI_MAX_TOOL_CALLS

DOCUMENT_STORAGE_TYPE
DOCUMENT_STORAGE_PATH

AWS_REGION
AWS_S3_BUCKET
AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY
```

沒有使用 AWS 時，AWS variables 可不設定。

---

# 3. Local

建議：

```text
SPRING_PROFILES_ACTIVE=local
```

MySQL：

```text
Docker Compose
localhost
```

Document Storage：

```text
local filesystem
```

Frontend：

```text
http://localhost:5173
```

---

# 4. Test

```text
SPRING_PROFILES_ACTIVE=test
```

Database：

```text
Testcontainers MySQL
```

Clock：

```text
fixed in tests when needed
```

AI：

```text
mock / fake provider
```

Integration Test 不應呼叫真付費 AI API。

---

# 5. Demo

```text
SPRING_PROFILES_ACTIVE=demo
```

特性：

```text
DemoDataInitializer enabled
fictional seed data
demo accounts
resettable environment
```

不要使用真實公司資料。

---

# 6. Production

```text
SPRING_PROFILES_ACTIVE=prod
```

要求：

```text
no demo seeder
ddl-auto=validate
Flyway enabled
CORS exact frontend origin
secret from environment / secret store
```

---

# 7. JWT

```text
JWT_SECRET
```

要求：

```text
high entropy
not committed
different per environment
```

```text
JWT_EXPIRATION_SECONDS=3600
```

可作 baseline。

---

# 8. Database

Example：

```text
DB_URL=jdbc:mysql://...
DB_USERNAME=...
DB_PASSWORD=...
```

Production 不使用 root account。

---

# 9. Frontend Environment

Vite：

```text
VITE_API_BASE_URL
```

Example local：

```text
http://localhost:8080/api/v1
```

Production：

```text
https://api.example.com/api/v1
```

---

# 10. CORS

Backend：

```text
FRONTEND_ORIGIN
```

Local：

```text
http://localhost:5173
```

Production：

```text
exact deployed frontend domain
```

禁止：

```text
*
```

搭配 credential-sensitive configuration。

---

# 11. Document Storage

V1 abstraction：

```text
DocumentStorageService
```

Local：

```text
DOCUMENT_STORAGE_TYPE=local
DOCUMENT_STORAGE_PATH=./data/documents
```

Production 可：

```text
DOCUMENT_STORAGE_TYPE=s3
```

---

# 12. AI

```text
AI_CHAT_PROVIDER=none
GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.5-flash
AI_PROVIDER_TIMEOUT=15s
AI_MAX_OUTPUT_TOKENS=512
AI_MAX_TOOL_CALLS=1
```

預設 `AI_CHAT_PROVIDER=none`，不建立 `ChatModel`；核心 Backend 正常啟動，AI Endpoint 回
`AI_PROVIDER_UNAVAILABLE`（HTTP 503）。啟用 Gemini Developer API 時設定
`AI_CHAT_PROVIDER=google-genai` 與由 Secret Manager／執行環境注入的 `GEMINI_API_KEY`。
`GEMINI_MODEL` 是明確可覆寫的 Flash Model ID。

不得：

```text
commit API key
print API key
return API key in error
```

AI provider unavailable 時 core API 仍正常。

---

# 13. Git Rules

必須 commit：

```text
.env.example
application*.yml without secrets
```

不得 commit：

```text
.env
real password
real JWT secret
AI key
AWS secret
```

---

# 14. .env.example

只放 key：

```text
DB_URL=
DB_USERNAME=
DB_PASSWORD=
JWT_SECRET=
JWT_EXPIRATION_SECONDS=3600
FRONTEND_ORIGIN=http://localhost:5173
AI_CHAT_PROVIDER=none
GEMINI_API_KEY=
GEMINI_MODEL=gemini-3.5-flash
AI_PROVIDER_TIMEOUT=15s
AI_MAX_OUTPUT_TOKENS=512
AI_MAX_TOOL_CALLS=1
DOCUMENT_STORAGE_TYPE=local
DOCUMENT_STORAGE_PATH=./data/documents
```

---

# 15. Startup Validation

Production 啟動若缺：

```text
DB
JWT secret
required storage config
```

應 fail fast。

不要默默使用不安全 default。

---

# 16. Configuration Ownership

Configuration：

```text
config/
```

集中處理。

Business Entity 不直接讀：

```text
System.getenv()
```

---

# 17. Environment Matrix

| Config | Local | Test | Demo | Prod |
|---|---|---|---|---|
| DB | Docker MySQL | Testcontainers | Managed/Demo DB | Managed MySQL |
| Demo Seed | No | No | Yes | No |
| AI | Optional | Mock | Real/Optional | Real |
| Storage | Local | Temp | Local/S3 | S3/Managed |
| Swagger | Yes | Optional | Yes | Restricted |
| CORS | localhost | test client | demo frontend | prod frontend |

---

## Spring AI Provider Configuration — SP-720 (2026-10-11)

- SP-720 使用 Spring AI `2.0.1`、`spring-ai-starter-model-google-genai`、`ChatClient` / `ChatModel` 與 Gemini Developer API。預設 Model ID 為官方 stable 且支援 Function Calling 的 `gemini-3.5-flash`，可用 `GEMINI_MODEL` 覆寫。
- API Key 或憑證禁止 Commit、寫入 Log 或傳回 HTTP Response。Timeout / Unavailable / Invalid Response 沿用既有 AI Error Contract。
- Local/Test 若未設定 Provider，核心 Backend 不得因為 AI 功能未啟用而無法啟動；呼叫未啟用的 AI 能力才返回適切既有 AI Error。
- 自動化測試透過 Fake ChatModel／ToolCallback 運作，不得呼叫真實付費 Provider。Production 需要有效 Provider 設定，且不得使用 Fake Provider 偽裝成真正的模型整合。
- V1 使用同步單輪 JSON，不配置 Chat Memory。`AI_MAX_OUTPUT_TOKENS`、`AI_MAX_TOOL_CALLS` 與 `AI_PROVIDER_TIMEOUT` 分別限制每次輸出 Token、Tool Calling 次數與 Provider 等待時間。
- Spring AI Tool Call 的參數／結果 observability 預設不匯出；應用只記錄 outcome、latency 與可取得的 total token usage，不記錄 prompt、API Key 或完整採購／合約內容。

### 手動 Gemini Smoke Test（不納入自動測試）

1. 由安全的 shell／部署 secret 設定 `AI_CHAT_PROVIDER=google-genai`、`GEMINI_API_KEY`，視需要覆寫 `GEMINI_MODEL`。
2. 啟動 Backend，使用具有 `PROCUREMENT_STAFF` 或 `ADMIN` 身分的 JWT 呼叫 `POST /api/v1/ai/query`，例如詢問既存 Material 的庫存。
3. 確認同步回應的 `tools` 僅出現唯讀 allowlist、`answer` 等於 Backend tool 的確定性 facts，且 log 只有 outcome／latency／token usage。
4. 移除 `GEMINI_API_KEY` 並將 `AI_CHAT_PROVIDER=none` 後重啟；確認 AI Endpoint 回 503，而 Inventory 等核心 API 仍回成功。

---

## Confirmed SP-720/721/722 AI Decisions (2026-10-11)

- **Google Gemini** is the SP-720 LLM provider; prioritize a low-cost **Flash** model. Verify exact Chat Model ID, Tool Calling capability and price at implementation time; keep model ID configurable.
- Use stable **Spring AI 2.0.x** `ChatClient` / `ChatModel` and explicit Spring AI read-only Tool Calling under current-user Backend Authorization. AI-generated explanations require backend verification of structured authoritative figures.
- **SP-722 (#80)** is the dedicated Semantic RAG upgrade using **Google Gemini Embedding + PostgreSQL / pgvector**; this supersedes any earlier suggestion that MySQL / application-side cosine is the final semantic vector store. Existing **SP-710 MySQL lexical index remains transitional**; do not rewrite it inside SP-720.
- MySQL continues holding procurement transactions and document metadata; pgvector owns semantic document chunk/vector indexes. Cross-database updates are not atomic; SP-722 must design idempotent reindex, READY-status gating, and failure recovery.
- V1 AI requests are **single-turn, no chat memory, synchronous JSON**. Disable AI cleanly when configuration/provider unavailable (AI 503, core APIs unaffected).
- SP-721 requires **Proposal → server-issued Confirm Token/ID → Backend verification → DRAFT via existing Service**; never treat an LLM statement or simple client `confirmed=true` as sufficient.
- Basic cost / observability boundaries: token count when available, latency, error metrics, capped output/tool-call iterations, provider timeout and sensitive-data-redacted logs.
- Full decision record: `docs/adr/ADR-SP-720-spring-ai-adoption.md`; semantic work: GitHub Issue #80.

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

