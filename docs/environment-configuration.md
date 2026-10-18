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
AI_MAX_MODEL_CALLS

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

SP-901 的 `DemoDataInitializer` 另使用：

```text
DEMO_ACCOUNT_PASSWORD
DEMO_BASELINE_DATE
DEMO_DATASET_VERSION
DEMO_SAMPLES_PATH
```

帳號密碼只由執行環境注入，沒有 Repository 預設值。`DEMO_BASELINE_DATE` 留空時由 Spring `Clock`
取得並記錄在 dataset marker；重啟不會改寫。破壞性 Reset 預設停用，啟用時還必須提供
`DEMO_RESET_CONFIRMATION`、`DEMO_ALLOWED_DATABASE`、`DEMO_ALLOWED_SEMANTIC_DATABASE`，並使用明確含
`demo` 的 `DOCUMENT_STORAGE_PATH`。完整步驟見 `SP-901-DEMO-RESET-RUNBOOK.md`。

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
GEMINI_MODEL=gemini-3.6-flash
AI_PROVIDER_TIMEOUT=15s
AI_DRAFT_PROPOSAL_TTL=PT10M
AI_MAX_OUTPUT_TOKENS=512
AI_MAX_TOOL_CALLS=4
AI_MAX_MODEL_CALLS=5
AI_MONTHLY_TOKEN_WARNING_ENABLED=false
AI_MONTHLY_TOKEN_WARNING_THRESHOLD=1000000
```

預設 `AI_CHAT_PROVIDER=none`，不建立 `ChatModel`；核心 Backend 正常啟動，AI Endpoint 回
`AI_PROVIDER_UNAVAILABLE`（HTTP 503）。啟用 Gemini Developer API 時設定
`AI_CHAT_PROVIDER=google-genai` 與由 Secret Manager／執行環境注入的 `GEMINI_API_KEY`。
`GEMINI_MODEL` 是明確可覆寫的 Flash Model ID；既有 `.env` 的 `AI_API_KEY`、`AI_MODEL`
也可作為相容別名，但 `GEMINI_*` 優先。`.env` 不會被直接載入為 Spring 環境變數，
啟動流程仍須安全地注入；單純填入 `.env` 不會自動啟用 Provider。

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
GEMINI_MODEL=gemini-3.6-flash
AI_PROVIDER_TIMEOUT=15s
AI_MAX_OUTPUT_TOKENS=512
AI_MAX_TOOL_CALLS=4
AI_MAX_MODEL_CALLS=5
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

- SP-720 使用 Spring AI `2.0.1`、`spring-ai-starter-model-google-genai`、`ChatClient` / `ChatModel` 與 Gemini Developer API。預設 Model ID 為官方 stable 且支援 Function Calling 的 `gemini-3.6-flash`，可用 `GEMINI_MODEL` 覆寫。
- API Key 或憑證禁止 Commit、寫入 Log 或傳回 HTTP Response。Timeout / Unavailable / Invalid Response 沿用既有 AI Error Contract。
- Local/Test 若未設定 Provider，核心 Backend 不得因為 AI 功能未啟用而無法啟動；呼叫未啟用的 AI 能力才返回適切既有 AI Error。
- 自動化測試透過 Fake ChatModel／ToolCallback 運作，不得呼叫真實付費 Provider。Production 需要有效 Provider 設定，且不得使用 Fake Provider 偽裝成真正的模型整合。
- V1 使用同步單輪 JSON，不配置 Chat Memory。`AI_MAX_OUTPUT_TOKENS` 限制每次模型輸出，`AI_MAX_TOOL_CALLS` 限制一次請求的工具執行次數，`AI_MAX_MODEL_CALLS` 限制模型呼叫輪數，`AI_PROVIDER_TIMEOUT` 限制整次 Provider 等待時間。預設分別為 512、4、5、15 秒。
- 六個唯讀工具的 `returnDirect` 關閉，Spring AI 可以在同一請求呼叫多個工具後生成受限自然語言引言。最終 `answer` 的事實段落與 `sources` 一律由 Backend 已授權工具結果組成；模型引言只能使用安全句型。模型輸出不合格而已有充分事實時，`answerSource=BACKEND_FACTS`；合格時為 `MODEL_EXPLANATION`。`INSUFFICIENT_EVIDENCE` 仍回 `answer=null`。
- 缺少 Material Code／Recommendation ID 等必要識別時，`status=NEEDS_CLARIFICATION`、`answer` 說明需補充的欄位、`tools=[]`、`sources=[]`、`answerSource=BACKEND_CLARIFICATION`；V1 不保存上下文，需重新提出完整問題。
- Tool Result 傳給 Gemini 的內容限定為工具種類、狀態及最多 1500 字的查詢事實，不傳 Source 摘錄或無關資料。月度 Token 用量警示的實際規則與驗證方式見下節；此警示不是平台硬費用上限。
- Spring AI Tool Call 的參數／結果 observability 預設不匯出；應用只記錄 outcome、latency 與可取得的 total token usage，不記錄 prompt、API Key 或完整採購／合約內容。

### 月度 Token 預算警示（SP-720 V1）

- 預設停用；設定 `AI_MONTHLY_TOKEN_WARNING_ENABLED=true` 與正整數 `AI_MONTHLY_TOKEN_WARNING_THRESHOLD` 後啟用。門檻單位是 **Token**，不是貨幣。未配置且驗證可靠的 Google 費率，因此 V1 不提供金額估算，也不宣稱精確成本。
- Backend 將可取得的 Spring AI Provider `totalTokens` 按 **UTC 曆月**累加到 `ai_monthly_token_usage`（新 migration V16）；同一月跨程序重啟仍保留。Provider 成功但未回 Usage Metadata、逾時或呼叫後失敗，另累加 `missing_usage_requests`。此時 `known_tokens` 僅是下界，`supplypilot.ai.budget.monthly.estimate.available=0`，不可把下界說成完整用量或費用。資料庫記帳失敗時記錄 `AI_MONTHLY_TOKEN_ESTIMATE_UNAVAILABLE reason=accounting_failure` 並增加 `supplypilot.ai.budget.accounting.failures`，AI 回應不因此假裝估算成功。
- 啟用後，當當月 `known_tokens >= threshold`，應用以資料庫條件更新只觸發**每月一次**的 `AI_MONTHLY_TOKEN_BUDGET_WARNING` 結構化 WARN 事件，並增加 `supplypilot.ai.budget.warning.events`。規則在下一次 Provider 結果入帳時評估；只改設定而沒有新查詢不會主動掃描。這是內建、可實際觸發的告警途徑；部署時將 Backend WARN 日誌送往既有日誌告警接收端即可通知值班人員。沒有外部日誌接收端時仍會輸出 WARN，但不會自行發送電子郵件。`known_tokens` 即使是下界，跨越門檻仍可安全警告。
- 可在受控監控環境透過 Actuator metrics 觀察 `supplypilot.ai.budget.monthly.known.tokens`、`supplypilot.ai.budget.monthly.estimate.available`、`supplypilot.ai.budget.monthly.warning.threshold`、`supplypilot.ai.budget.monthly.warning.enabled`、`supplypilot.ai.budget.warning.events`；沒有暴露 Prompt、Key、文件或採購內容。這是 **Budget Warning**，不攔截請求，也不是 Google AI Studio 或應用端的 **Hard Spending Limit**。

驗證：在測試環境設定門檻 `10` 並啟用警示，用 Fake ChatModel 產生累計至少 10 個有 Usage Metadata 的 Token；確認只出現一次 `AI_MONTHLY_TOKEN_BUDGET_WARNING`、`warning.events` 增加，並執行 `SELECT period, known_tokens, missing_usage_requests, warning_issued FROM ai_monthly_token_usage;` 核對當月資料。讓下一次呼叫不帶 Usage Metadata，確認 `missing_usage_requests` 增加、`estimate.available=0`。`AiMonthlyUsageMonitorIntegrationTests` 自動驗證跨重啟、跨 UTC 月份與實際告警觸發，不會呼叫付費 Gemini API。

Provider 停用或缺 Key 時，即使問句缺 Material Code，合法且已登入的 AI Query 仍回 `AI_PROVIDER_UNAVAILABLE`／503；已啟用 Provider 才回 `NEEDS_CLARIFICATION`。設定檢查只查本地 `ChatModel` Bean，不會對 Gemini 發送請求；401／403 仍先由 Spring Security 決定。

### 手動 Gemini Smoke Test（不納入自動測試）

1. 由安全的 shell／部署 secret 設定 `AI_CHAT_PROVIDER=google-genai`、`GEMINI_API_KEY`（或相容別名 `AI_API_KEY`），視需要覆寫 `GEMINI_MODEL`（或 `AI_MODEL`）。不要將密鑰值輸出到終端紀錄。
2. 啟動 Backend，使用具有 `PROCUREMENT_STAFF` 或 `ADMIN` 身分的 JWT 呼叫 `POST /api/v1/ai/query`，例如詢問既存 Material 的庫存。
3. 確認同步回應的 `tools` 僅出現唯讀 allowlist、`answer` 的權威事實段落等於 Backend tool 的確定性 facts，且 log 只有 outcome／latency／token usage。
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

---

## SP-722 五項確認決策（2026-10-11）

SP-722 實作設定、PostgreSQL migration、Backfill 與切換操作步驟見 [semantic-rag-runbook.md](semantic-rag-runbook.md)。

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

---

## SP-903 Docker 環境檔與資料隔離

`compose.yaml` 是 Production 的標準四服務設定；根目錄 `.env` 由使用者人工建立及維護，範本為 `.env.example`。`compose.demo.yaml` 只覆蓋 Demo 必要差異；Demo 必須明確指定 `.env.demo`，範本是 `.env.demo.example`：

```powershell
docker compose up -d --build
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
```

Production 使用 `prod` Profile，資料庫為 `supplypilot_prod` 和 `supplypilot_semantic_prod`。Demo 使用 `demo` Profile，資料庫為 `supplypilot_demo` 和 `supplypilot_semantic_demo`。兩環境分開使用 Compose Project、資料庫帳密、JWT Secret、MySQL/pgvector 資料 Volume、文件 Storage Volume 和 Frontend Host Port。資料庫不發布主機連接埠；Production 預設前端只監聽 `127.0.0.1:8080`，Demo 只監聽 `127.0.0.1:8081`。

原本開發用 MySQL 設定移至 `compose.dev.yaml`，使用 `supplypilot-dev-v2` 專案和新 Volume；先前 `compose.yaml` 建立的舊開發 Volume 不會自動接管或刪除。既有 SP-903 Demo 保留 `supplypilot-sp903-local-demo` 專案與原 MySQL、pgvector、文件 Volume 名稱。切換前須確認新 `.env.demo` DB 帳密與舊資料庫初始化時一致，使用受控啟動器驗證歸屬和登入。

**絕不在 Repository 工作中讀取、建立、覆寫、刪除或搬移真實 `.env`、`.env.demo`，也不輸出其中的值。** Git 和 Docker Build Context 排除真實環境檔；只追蹤 `.env.example`、`.env.demo.example` 範本。Production 與 Demo 範本不得放入真實憑證。

Compose 插值遵守 Shell Environment 優先於 `--env-file`。Production 標準命令會自動讀取 `.env`，Windows Shell 中已存在的同名變數仍可覆蓋它。固定的 Profile、資料庫名稱、資料 Volume 及 loopback 綁定避免部分環境變數誤設造成服務跨環境連線；DB 驗證或 Flyway 失敗時 Backend Health Check 應維持失敗。受控 Demo 啟動器會清除其程序內的插值變數、檢查 Volume/Container Compose labels，先用 DB 應用程式帳號實際執行 `SELECT 1`，成功後才啟動 Backend。

### Demo 基線帳號密碼即時讀取

`demo` Profile 對 `admin`、`manager`、`buyer`、`warehouse`、`planner` 的每次新登入，會重新讀取唯讀目錄掛載中的 password-only 檔案。受控 Demo 啟動器讀取 `.env.demo`，只同步 `DEMO_ACCOUNT_PASSWORD`；缺檔、I/O 錯誤、格式錯誤、重複 Key、空值、少於 6 或超過 100 字元時會移除舊密碼檔，基線登入因此失敗。這五個帳號只依檔案密碼驗證，不保留資料庫雜湊的舊密碼備援；仍須通過帳號存在和 ACTIVE 狀態檢查。其他 Demo 使用者沿用資料庫 BCrypt 驗證。Production 不建立此元件，也不掛載 Demo 環境檔。

啟動器每 100 ms 檢查 `.env.demo`，以目錄內暫存檔和原子替換更新 host-mounted password 檔，涵蓋檔案就地寫入與原子替換；Backend 不重啟、不重新 Seed，也不寫業務資料。新密碼只用於下一次登入，已簽發 JWT 依原有效期到期。此即時同步需要由 `pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1` 啟動，直接執行 Compose 不會啟動主機檔案同步器。Backend 只能讀取單一 Demo 密碼，不會讀到資料庫 root 憑證或其他 `.env.demo` 設定。

### 初始 Production 管理員

只有 `prod` Profile 啟用管理員 Bootstrap。每個全新空 `users` 表在 InnoDB transaction 中建立一次性初始化標記和使用 BCrypt 雜湊的 `admin/admin`、`ADMIN`、`ACTIVE` 使用者，並標記強制改密碼；唯一鍵可序列化並行啟動，兩項寫入同時提交或回滾。正常重啟不會重設密碼。首次權杖只可呼叫 `/api/v1/auth/me` 和 `/api/v1/auth/change-password`；新密碼維持一般 6 至 100 字元，成功後必須重新登入以取得一般權限權杖。

若初始化標記存在但 `users` 為空，或資料庫已有使用者但沒有 ACTIVE ADMIN，Backend 會拒絕啟動且不會建立預設帳號；依 `docs/SP-903-DOCKER-RELEASE-CANDIDATE.md` 的 DBA 復原程序處理。標記加入前已初始化且後來清空 users 的舊資料庫無法從目前狀態辨別，升級前須核對備份和歷史。Production 對外開放前，必須已修改初始密碼、用新密碼重新登入，並確認初始 JWT 仍不能呼叫一般管理 API。
