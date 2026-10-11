# SupplyPilot — Security Design

> **文件目的**：定義 SupplyPilot V1 的 Authentication、Authorization、JWT、Password Storage、Role Matrix、Spring Security Filter Chain、401/403、CurrentUserProvider、AI Tool Security 與 Secret Management。  
> **文件定位**：V1 Security Baseline  
> **上游文件**：
> - `functional-requirements.md`
> - `domain-model.md`
> - `erd.md`
> - `api.md`
> - `package-architecture.md`
> - `transaction-boundary.md`
> - `validation-exception.md`

---

# 1. Security Scope

SupplyPilot V1 採：

```text
Spring Security
+
JWT
+
Role-based Authorization
```

V1 處理：

```text
Authentication
Authorization
Password Storage
JWT
Current User
Role Check
401 / 403
AI Tool Authorization
CORS
CSRF strategy
Secret Management
Basic Security Logging
```

V1 不處理：

```text
OAuth2 Login
SSO
Refresh Token Rotation
MFA
Fine-grained Permission Table
ABAC
Multi-tenant Security
Distributed Session
```

---

# 2. Authentication Flow

Login Endpoint：

```text
POST /api/v1/auth/login
```

流程：

```text
username + password
↓
AuthenticationManager
↓
CustomUserDetailsService
↓
UserRepository
↓
PasswordEncoder.matches()
↓
Validate User ACTIVE
↓
Authentication Success
↓
Generate JWT
↓
Return Access Token
```

---

# 3. Password Storage

禁止：

```text
Plain Text Password
Manual SHA-256(password)
```

V1 使用：

```text
BCryptPasswordEncoder
```

Database 只保存：

```text
password_hash
```

API Response 永不回傳：

```text
password
passwordHash
```

---

# 4. User Security Model

User：

```text
User
├── id
├── username
├── passwordHash
├── displayName
├── role
└── status
```

Role：

```text
PRODUCTION_PLANNER
PROCUREMENT_STAFF
PROCUREMENT_MANAGER
WAREHOUSE_STAFF
ADMIN
```

Status：

```text
ACTIVE
INACTIVE
```

V1 不建立：

```text
Role table
Permission table
UserRole join table
```

---

# 5. Inactive User

若：

```text
User.status = INACTIVE
```

登入必須拒絕。

不允許：

```text
login success
→ later API checks
```

應於 Authentication 階段直接阻擋。

---

# 6. JWT Strategy

V1 使用 Access Token Only。

不實作：

```text
Refresh Token Rotation
Token Family
Device Session Management
```

建議 TTL：

```text
1 hour
```

可透過 configuration 調整。

---

# 7. JWT Claims

建議：

```json
{
  "sub": "buyer01",
  "userId": 10,
  "role": "PROCUREMENT_STAFF",
  "iat": 1791410000,
  "exp": 1791413600
}
```

必要資訊：

```text
subject
userId
role
issuedAt
expiration
```

---

# 8. JWT Signing

V1 單體 Backend 使用：

```text
HS256
```

即可。

不需要：

```text
RSA Key Pair
JWKS
Distributed Token Verification
```

JWT Secret：

```text
environment variable
```

例如：

```text
JWT_SECRET
```

不可 hardcode。

---

# 9. JWT Validation

每次 Request 至少驗證：

```text
signature
expiration
subject
```

如果 Token：

```text
missing
invalid
expired
```

則 Authentication Failure。

---

# 10. JwtAuthenticationFilter

受保護 Request：

```text
Authorization: Bearer <token>
```

流程：

```text
Read Authorization Header
↓
Extract Bearer Token
↓
Validate JWT
↓
Build AuthenticatedUser
↓
Build Authentication
↓
SecurityContextHolder
↓
Continue Filter Chain
```

---

# 11. AuthenticatedUser

Security Principal 建議使用：

```text
AuthenticatedUser
├── id
├── username
└── role
```

不要直接將 JPA `User` Entity 放入 SecurityContext。

原因：

```text
Avoid Lazy Loading
Avoid Entity Lifecycle Coupling
Avoid SecurityContext Serialization Issues
```

---

# 12. SecurityFilterChain

概念：

```text
/auth/login
→ permitAll

/actuator/health
→ permitAll or restricted depending environment

/swagger-ui/**
/v3/api-docs/**
→ dev only / restricted in production

/api/v1/**
→ authenticated
```

Session：

```text
SessionCreationPolicy.STATELESS
```

---

# 13. CSRF

V1 使用：

```text
Bearer JWT
Authorization Header
Stateless REST API
```

因此可：

```text
CSRF disabled
```

前提：

```text
JWT not stored in authentication cookie
```

若未來改 Cookie Authentication，必須重新評估 CSRF。

---

# 14. CORS

禁止 Production：

```text
allowedOrigins = "*"
```

尤其不可搭配 Credentials。

Development 可允許：

```text
http://localhost:3000
http://localhost:5173
```

Production：

```text
https://<frontend-domain>
```

透過 environment / configuration 設定。

---

# 15. Authorization Strategy

採兩層：

## Web Layer

粗粒度：

```text
authenticated
public endpoint
```

## Service / Use Case Layer

敏感 Business Action 使用 Method Security：

```text
@PreAuthorize
```

原因：

```text
REST
AI Tool
Internal Application Call
```

都可能進入同一 Service。

真正的 Authorization 不能只放 Controller。

---

# 16. Method Security

啟用：

```java
@EnableMethodSecurity
```

例如：

```java
@PreAuthorize("hasAnyRole('PROCUREMENT_MANAGER','ADMIN')")
public PurchaseOrderResponse approve(Long id) {
    ...
}
```

敏感 Operation 建議 Service Method 自己保護。

---

# 17. Role Matrix — PRODUCTION_PLANNER

可以：

```text
View Material
View Inventory
Create Demand
Update Demand
Cancel Demand
View Recommendation
```

不能：

```text
Create PO
Approve PO
Order PO
Receive PO
Manage Master Data
Manage Users
```

---

# 18. Role Matrix — PROCUREMENT_STAFF

可以：

```text
View Material
View Supplier
View Inventory
Create Recommendation
View Supplier Ranking
Create PO Draft
Update PO Draft
Mark Ordered
View PO
Upload Documents
AI Query
RAG Query
```

不能：

```text
Approve PO
Reject PO
Receive PO
Manual Inventory Adjustment
Manage Users
```

---

# 19. Role Matrix — PROCUREMENT_MANAGER

可以：

```text
View Recommendation
View Ranking
View PO
Approve PO
Reject PO
AI Query
RAG Query
```

V1 建議不直接 Create PO。

目的是保持 Role Responsibility 清楚。

---

# 20. Role Matrix — WAREHOUSE_STAFF

可以：

```text
View Inventory
Validate Inventory Import
Confirm Inventory Import
Receive PO
Manual Inventory Adjustment
View StockTransaction
```

不能：

```text
Create PO
Approve PO
Manage Supplier Master
Manage User
```

---

# 21. Role Matrix — ADMIN

可以：

```text
all V1 authorized operations
```

但：

> ADMIN 只能 bypass Authorization，不能 bypass Domain Invariant。

例如：

```text
RECEIVED → DRAFT
```

仍然非法。

Admin 不得跳過：

```text
PO lifecycle
Inventory non-negative rule
Transaction constraint
Recommendation reference validation
```

---

# 22. Endpoint Role Matrix

| Endpoint / Use Case | Allowed Roles |
|---|---|
| Material GET | Authenticated relevant users |
| Material POST/PATCH | ADMIN |
| Supplier GET | PROCUREMENT_STAFF, PROCUREMENT_MANAGER, ADMIN |
| Supplier POST/PATCH | ADMIN |
| SupplierMaterial POST/PATCH | ADMIN |
| Demand POST/PATCH/CANCEL | PRODUCTION_PLANNER, ADMIN |
| Inventory GET | Relevant authenticated users |
| Inventory Import | WAREHOUSE_STAFF, ADMIN |
| Manual Adjustment | WAREHOUSE_STAFF, ADMIN |
| Recommendation POST | PROCUREMENT_STAFF, PROCUREMENT_MANAGER, ADMIN |
| PO Create/Update Draft | PROCUREMENT_STAFF, ADMIN |
| PO Approve/Reject | PROCUREMENT_MANAGER, ADMIN |
| PO Mark Ordered | PROCUREMENT_STAFF, ADMIN |
| PO Receive | WAREHOUSE_STAFF, ADMIN |
| StockTransaction GET | WAREHOUSE_STAFF, PROCUREMENT_MANAGER, ADMIN |
| Document Upload | PROCUREMENT_STAFF, ADMIN |
| AI Query | PROCUREMENT_STAFF, PROCUREMENT_MANAGER, ADMIN |

---

# 23. CurrentUserProvider

Package：

```text
security/
└── CurrentUserProvider.java
```

Responsibility：

```text
getCurrentUserId()
getCurrentUsername()
getCurrentRole()
```

Audit User 一律由：

```text
SecurityContext
```

取得。

Client 不允許傳：

```text
createdBy
approvedBy
rejectedBy
orderedBy
receivedBy
uploadedBy
```

---

# 24. Audit Example

Approve PO：

```text
currentUser = CurrentUserProvider
↓
PurchaseOrderService.approve()
↓
po.approve(currentUser.id, now)
↓
approvedBy / approvedAt
```

禁止：

```json
{
  "approvedBy": 5
}
```

Reject PO 同樣由 `CurrentUserProvider` 提供 `rejectedBy`，由 Backend Clock 提供 `rejectedAt`；Client 不得傳入或覆寫 rejection audit。

---

# 25. 401 vs 403

## 401 — UNAUTHENTICATED

代表：

```text
not authenticated
```

例如：

```text
No JWT
Invalid JWT
Expired JWT
```

## 403 — ACCESS_DENIED

代表：

```text
authenticated
but
not permitted
```

例如：

```text
PROCUREMENT_STAFF
calls
POST /purchase-orders/{id}/approve
```

---

# 26. AuthenticationEntryPoint

處理：

```text
401
```

Response：

```json
{
  "code": "UNAUTHENTICATED",
  "message": "Authentication is required.",
  "traceId": "...",
  "timestamp": "...",
  "path": "..."
}
```

---

# 27. AccessDeniedHandler

處理：

```text
403
```

Response：

```json
{
  "code": "ACCESS_DENIED",
  "message": "You do not have permission to perform this operation.",
  "traceId": "...",
  "timestamp": "...",
  "path": "..."
}
```

格式與一般 ErrorResponse 一致。

---

# 28. Security Exception Boundary

401 / 403 主要交給：

```text
AuthenticationEntryPoint
AccessDeniedHandler
```

不要所有 Security Failure 都透過：

```text
BusinessException
```

因為很多 Security Error 發生在 Controller 前。

---

# 29. AI Tool Security

AI Tool 不能因為是 Internal Backend Call 就 bypass Security。

流程：

```text
Authenticated HTTP User
↓
SecurityContext
↓
AI Query
↓
AI Tool Call
↓
Application Service
↓
CurrentUserProvider
```

Tool Action 使用：

```text
current user identity
current user role
```

---

# 30. AI 不使用 System Admin

禁止：

```text
AI Tool
→ SYSTEM_ADMIN
→ execute everything
```

否則：

```text
PROCUREMENT_STAFF
↓
AI
↓
ADMIN privileges
```

會形成 Authorization Bypass。

---

# 31. AI Allowed Actions

V1 AI 可：

```text
Query Recommendation
Explain Recommendation
Explain Supplier Ranking
Query Inventory
Query Open PO
RAG Search
Create PO Draft
```

---

# 32. AI Forbidden Actions

V1 AI 不提供：

```text
Approve PO
Reject PO
Mark Ordered
Receive PO
Modify Inventory
Manual Adjustment
User Management
Master Data Modification
```

即使登入者本身有權限，也不向 AI 暴露這些 Tool。

原因：

```text
high-risk transaction
requires explicit human action
```

---

# 33. AI Tool Service Boundary

例如：

```text
PurchaseOrderTool
↓
PurchaseOrderService.createDraft()
```

而不是：

```text
PurchaseOrderTool
↓
PurchaseOrderRepository.save()
```

AI 不能繞過：

```text
Authorization
Validation
Transaction
Domain Lifecycle
Audit
```

---

# 34. Secret Management

以下不能進 Git：

```text
JWT_SECRET
DB_PASSWORD
AWS_ACCESS_KEY
AWS_SECRET_KEY
AI_API_KEY
```

使用：

```text
Environment Variables
GitHub Actions Secrets
Cloud Secret Manager
```

---

# 35. application.yml

可以：

```yaml
security:
  jwt:
    expiration-seconds: 3600
```

Secret：

```yaml
security:
  jwt:
    secret: ${JWT_SECRET}
```

不寫真實 Secret。

---

# 36. Swagger / OpenAPI

Development：

```text
/swagger-ui
/v3/api-docs
```

可開放。

Production：

```text
disable
or
authenticated/restricted
```

如果作品集需要讓面試官看 API，可以保留，但避免將敏感管理操作暴露成匿名可執行。

---

# 37. Actuator

可以 expose：

```text
/actuator/health
/actuator/info
```

不要公開：

```text
/env
/configprops
/beans
```

Production 採最小暴露原則。

---

# 38. Security Logging

可記錄：

```text
login success
login failure
access denied
invalid token
expired token
```

不可 Log：

```text
password
passwordHash
full JWT
Authorization header
API key
secret
```

---

# 39. Sensitive API Data

UserResponse 可包含：

```text
id
username
displayName
role
status
```

不可包含：

```text
password
passwordHash
```

---

# 40. Login Rate Limiting

V1 可不強制實作。

V1.1 建議：

```text
rate limit /auth/login
```

避免 brute-force。

不在 V1 強制加入：

```text
captcha
account lockout
IP reputation
```

---

# 41. JWT Limitation — User Disabled After Login

V1 Access Token 已簽發後：

```text
User becomes INACTIVE
```

舊 Token 在 TTL 到期前可能仍有效。

V1 接受此限制。

V1.1 可考慮：

```text
shorter TTL
token blacklist
session version
revocation strategy
```

---

# 42. Security Package

```text
security/
├── SecurityConfig.java
├── JwtAuthenticationFilter.java
├── JwtTokenService.java
├── AuthenticatedUser.java
├── CurrentUserProvider.java
├── CustomUserDetailsService.java
├── CustomAuthenticationEntryPoint.java
└── CustomAccessDeniedHandler.java
```

---

# 43. Security Flow

```text
Request
↓
JwtAuthenticationFilter
↓
Token Valid?
│
├── No
│   → AuthenticationEntryPoint
│   → 401
│
└── Yes
    ↓
SecurityContext
    ↓
Method Security
    │
    ├── Not Allowed
    │   → AccessDeniedHandler
    │   → 403
    │
    └── Allowed
        ↓
Application Service
        ↓
Domain Validation
        ↓
Transaction
```

---

# 44. Security Rules — Final

正式鎖定：

1. Spring Security + JWT。
2. Stateless Session。
3. BCrypt Password Hash。
4. JWT TTL V1 約 1 小時。
5. UserRole 使用單一 Enum。
6. User INACTIVE 不可登入。
7. Audit User 由 SecurityContext 取得。
8. Client 不可傳 Audit User。
9. 401 / 403 分開處理。
10. AuthenticationEntryPoint 處理 401。
11. AccessDeniedHandler 處理 403。
12. Method Security 用於敏感 Use Case。
13. Admin 不可 bypass Domain Lifecycle。
14. AI Tool 以目前登入使用者身份執行。
15. AI 不使用 System Admin Identity。
16. AI V1 只能 Create Draft，不可 Approve / Order / Receive / Modify Inventory。
17. Secret 全部由 Environment / Secret Store 提供。
18. JWT Secret 不進 Git。
19. CORS 只允許指定 Origin。
20. JWT Header Stateless 模式下 disable CSRF。
21. `SessionCreationPolicy.STATELESS`。
22. Swagger / Actuator Production 要限制。
23. 不 Log password / token / secret。
24. PasswordHash 永不出現在 API Response。
25. SecurityContext 使用 `AuthenticatedUser`，不直接使用 JPA User Entity。
26. High-risk authorization 不只放 Controller。
27. AI Tool 不能繞過 Service / Transaction / Audit。
28. Production 採最小暴露原則。
29. Login Rate Limit 留 V1.1。
30. Token Revocation 留 V1.1。

---

# 45. Next Stage

Security Design 完成後：

```text
Security Design
↓
UML
↓
Testing Strategy
↓
Implementation Plan
```

下一階段建議正式建立 UML。

優先順序：

```text
1. Domain Class Diagram
2. Recommendation Sequence Diagram
3. Inventory Import Sequence Diagram
4. Purchase Order Lifecycle Sequence Diagram
5. Receiving Sequence Diagram
```

UML 內容應反映已確定的：

```text
Domain
Package Boundary
Validation
Security
Transaction
Exception Flow
```

---

## SP-720 / SP-721 Spring AI Tool Security Addendum (2026-10-11)

- Spring AI 2.0.x Tool Calling 僅能註冊經審查的明確 Allowlist，SP-720 限 Recommendation、Supplier Ranking、Inventory、Open PO、SP-710 Document Query 等唯讀工具。
- 工具不得開放任意 Service Method、Repository、Bean、SQL、URL 或反射執行；Prompt Injection 與模型輸出都不能當作授權決策。
- AI Controller 通過 Role Check 不是 Tool 的最終權限；Tool 必須以當前登入者 SecurityContext 呼叫既有受 Method Security 保護的 Application / Query Service，不得使用 System Admin 身份或繞過 CurrentUserProvider。
- Spring AI 自身不取代 Spring Security；不讓模型註冊／啟動 Approve、Reject、Order、Receive、Adjust Inventory、Master Data Write，SP-720 也不得暴露 createDraft。
- SP-721 的 Draft Proposal 必須在 Backend 驗證明確使用者確認後才可呼叫既有 `PurchaseOrderService.createDraft()`；LLM 不能自行聲稱已確認。

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

