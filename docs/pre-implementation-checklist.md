# SupplyPilot — Pre-Implementation Checklist

> **目的**：Day 1 開始 Coding 前的最後 Gate。  
> **規則**：這份清單通過後停止擴張設計，進入實作；新問題以 Ticket / ADR 處理，不回頭無限重畫架構。

> **Review 結果（2026-10-08）**：Go。V1 Core Design Freeze 生效。所有設計就緒項目已由全文件稽核確認；實際功能完成狀態仍以 GitHub Issue 驗收條件為準。

---

# A. Requirement

- [x] Company profile finalized
- [x] As-Is flow finalized
- [x] To-Be flow finalized
- [x] Functional requirements finalized
- [x] V1 scope / non-goals clear
- [x] Acceptance criteria exists

---

# B. Domain / Data

- [x] Domain Model synchronized
- [x] Entity count = 14
- [x] InventoryImportItem included
- [x] RecommendationOutcome included
- [x] ERD MD synchronized
- [x] ERD HTML synchronized
- [x] Inventory `@Version` is V1
- [x] PurchaseOrder `@Version` is V1
- [x] DocumentStatus = PROCESSING / READY / FAILED / INACTIVE
- [x] Snapshot rules clear
- [x] Delete policy clear

---

# C. API

- [x] `/api/v1` baseline defined
- [x] DTO boundaries defined
- [x] Business action endpoints defined
- [x] Error response defined
- [x] 401 / 403 defined
- [x] 409 concurrency defined
- [x] Recommendation Outcome in response
- [x] Inventory Import Validate / Confirm defined
- [x] AI high-risk actions prohibited

---

# D. Architecture

- [x] Modular Monolith confirmed
- [x] Package by Feature confirmed
- [x] Service transaction boundary confirmed
- [x] IncomingInventoryQuery resolves circular dependency
- [x] PO Item aggregate ownership clear
- [x] ReceivingService boundary clear
- [x] AI Tool → Service, never Repository write
- [x] Coding standards documented

---

# E. Transaction / Validation

- [x] Material + Inventory atomic
- [x] Inventory Import confirm all-or-nothing
- [x] Manual adjustment + audit atomic
- [x] Recommendation snapshot atomic
- [x] PO + Items atomic
- [x] Receiving atomic
- [x] Optimistic locking behavior defined
- [x] DTO / Service / Domain / DB validation split defined

---

# F. Security

- [x] JWT strategy defined
- [x] BCrypt defined
- [x] User roles defined
- [x] CurrentUserProvider defined
- [x] Method Security defined
- [x] AI inherits user authorization
- [x] Secret handling defined
- [x] CORS strategy defined

---

# G. Testing / Data

- [x] Testing strategy exists
- [x] P0 tests identified
- [x] Testcontainers MySQL decision made
- [x] Test data naming defined
- [x] Fixed Clock strategy defined
- [x] Recommendation baseline scenario defined
- [x] Receiving rollback scenario defined
- [x] Inventory import sample CSV plan defined
- [x] Demo data is fictional

---

# H. Frontend

- [x] React / TypeScript / Vite selected
- [x] Page list defined
- [x] Role UI defined
- [x] JWT / Axios strategy defined
- [x] Recommendation UI defined
- [x] Inventory Import preview defined
- [x] PO Lifecycle UI defined
- [x] Receiving confirmation defined
- [x] Error / loading / empty behavior defined

---

# I. Environment

- [x] local profile defined
- [x] test profile defined
- [x] demo profile defined
- [x] prod profile defined
- [x] required env vars documented
- [x] `.env.example` planned
- [x] secrets excluded from Git
- [x] demo seeder disabled in prod

---

# J. Implementation

- [x] 14-day plan synchronized
- [x] Vertical Slice approach confirmed
- [x] Backend critical path understood
- [x] AI not on critical path
- [x] Day 14 reserved for validation / docs / bugfix
- [x] Scope cut order defined

---

# K. Day 1 Go / No-Go

Start coding when all critical items above are checked.

If a non-critical unknown appears during coding:

```text
create ticket
document assumption
continue implementation
```

Do not stop the project to redesign everything.

---

# Final Rule

After this checklist passes:

> **Design Freeze for V1 Core.**

Allowed during implementation:

```text
bug fix
clarification
small ADR
spec correction
```

Not allowed without strong evidence:

```text
switch to microservices
add Kafka/Redis for novelty
change core lifecycle casually
expand scope because a technology looks interesting
```
