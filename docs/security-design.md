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
