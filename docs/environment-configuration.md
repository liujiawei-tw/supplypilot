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

AI_API_KEY
AI_MODEL

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
AI_API_KEY
AI_MODEL
```

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
AI_API_KEY=
AI_MODEL=
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

- SP-720 正式支援 Spring AI 2.0.x ChatClient / ChatModel，以及一個實際可使用的 LLM Provider Adapter。`AI_API_KEY`、`AI_MODEL` 延續既有環境變數規格；可依選用 Provider 增加必要且清楚文件化的端點、逾時與啟用旗標。
- API Key 或憑證禁止 Commit、寫入 Log 或傳回 HTTP Response。Timeout / Unavailable / Invalid Response 沿用既有 AI Error Contract。
- Local/Test 若未設定 Provider，核心 Backend 不得因為 AI 功能未啟用而無法啟動；呼叫未啟用的 AI 能力才返回適切既有 AI Error。
- 自動化測試透過 Fake ChatModel／ToolCallback 運作，不得呼叫真實付費 Provider。Production 需要有效 Provider 設定，且不得使用 Fake Provider 偽裝成真正的模型整合。

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

