# SupplyPilot — Testing Strategy

> **文件目的**：定義 SupplyPilot V1 的 Unit Test、Service Test、Repository Test、Security Test、Integration / Transaction Test、Concurrency Test、AI Tool Boundary Test，以及 Test Fixture、Integration Seed、Demo Seed、標準 Business Scenario 與 CSV 測試資料策略。  
> **文件定位**：V1 Testing Baseline  
> **上游文件**：
> - `functional-requirements.md`
> - `domain-model.md`
> - `erd.md`
> - `api.md`
> - `package-architecture.md`
> - `transaction-boundary.md`
> - `validation-exception.md`
> - `security-design.md`
> - UML diagrams

---

# 1. Testing Principle

SupplyPilot V1 採測試金字塔：

```text
少量 Integration / E2E
        ↑
Service / Repository / Security Test
        ↑
大量 Unit Test
```

測試重點不是追求：

```text
100% coverage
```

而是保護：

```text
Core Business Rules
Transaction Atomicity
Security Boundary
Concurrency Correctness
```

## Codex 開發期間的驗證節奏

V2 一般 Issue 採「精準 CI + 必要整合／負向驗證 + GPT 獨立 Code Review」交付：依實際 Diff、直接及間接依賴執行受影響 Vitest／Maven、適用 Lint／Build 及 Issue 明訂的 Browser 驗收；涉及共用認證／授權、資料庫 Schema／交易、RAG 出站或併發時，必須擴大必要整合測試，不得把高風險必跑測試延後。每張 Issue 不必重跑完整 Maven verify／Vitest；所有規劃的 V2 Issues 整合後才執行完整 Backend／Frontend CI 與跨模組回歸。GitHub Actions 目前依使用者決定暫停作為 Gate（PAUSED BY USER / NOT USED AS GATE），本機只可標 LOCAL VERIFIED。舊完整套件失敗必須保留且逐項分診；只有足以證明不影響該 Issue，且必要精準測試／驗收通過時，才可標為 DEFERRED TO V2 INTEGRATION，不得把原 FAIL 改寫為 PASS。保留原始日誌及其適用 SHA，參照 AGENTS.md 最新政策。

---

# 2. Test Categories

V1 測試分成：

```text
1. Domain Unit Test
2. Calculator / Ranking Unit Test
3. Service Test
4. Repository Test
5. Security Test
6. Integration / Transaction Test
7. Concurrency Test
8. AI Tool Boundary Test
```

---

# 3. Domain Unit Test

適合測：

```text
PurchaseOrder Lifecycle
Inventory behavior
Demand lifecycle
RecommendationOutcome
```

這類測試不啟 Spring Context。

---

# 4. NetRequirementCalculator Test

公式：

```text
Expected Demand
+ Safety Stock
- Current Inventory
- Incoming Inventory
```

結果：

```text
max(0, raw)
```

必測：

```text
Demand 100
Safety 20
Inventory 30
Incoming 10
→ 80
```

以及：

```text
raw < 0
→ 0
```

不能產生負 Net Requirement。

---

# 5. PurchaseQuantityCalculator Test

必測：

## MOQ

```text
netRequirement = 80
MOQ = 100
→ 100
```

## Package Multiple

```text
netRequirement = 105
MOQ = 50
packageMultiple = 20
→ 120
```

## No Package Multiple

```text
netRequirement = 105
MOQ = 50
→ 105
```

---

# 6. SupplierConstraintEvaluator Test

必測：

```text
Supplier inactive
→ disqualified
```

```text
SupplierMaterial inactive
→ disqualified
```

```text
today + leadTimeDays > requiredDate
→ DELIVERY_DATE_NOT_FEASIBLE
```

```text
all constraints pass
→ qualified
```

---

# 7. Supplier Ranking Test

Baseline：

```text
Price       40%
Lead Time   30%
Reliability 20%
Quality     10%
```

必測：

```text
score calculation
rank ordering
qualified candidate only
```

Tie Break：

```text
1. lower total purchase cost
2. shorter lead time
3. higher reliability
4. supplier id/code
```

Ranking 必須 deterministic。

---

# 8. PurchaseOrder Domain Test

合法：

```text
DRAFT → APPROVED
DRAFT → REJECTED
APPROVED → ORDERED
ORDERED → RECEIVED
```

非法：

```text
DRAFT → ORDERED
APPROVED → REJECTED
RECEIVED → APPROVED
REJECTED → APPROVED
```

非法 transition 必須 fail。

Approve / Reject HTTP integration tests 需使用 MySQL Testcontainers 驗證 lifecycle status、authenticated-user audit、401/403 role matrix、錯誤碼與 transaction rollback；Client 不得覆寫 status 或 audit。

---

# 9. Inventory Domain Test

必測：

```text
100 receive 50
→ 150
```

```text
100 adjust -30
→ 70
```

```text
100 adjust -150
→ fail
```

Inventory 不可變成負數。

---

# 10. RecommendationOutcome Test

三種：

```text
netRequirement = 0
→ PURCHASE_NOT_REQUIRED
```

```text
netRequirement > 0
qualified supplier exists
→ SUPPLIER_RECOMMENDED
```

```text
netRequirement > 0
zero qualified supplier
→ NO_QUALIFIED_SUPPLIER
```

---

# 11. Service Test

Service Test 測：

```text
Orchestration
Business Validation
Dependency Interaction
```

Repository 可使用 mock。

不需要真正啟 MySQL。

---

# 12. PurchaseRecommendationService Test

例如：

```text
Material ACTIVE
Demand = 100
Inventory = 20
Incoming = 30
Safety = 10
```

Then：

```text
netRequirement = 60
```

並驗證：

```text
PurchaseRecommendation persisted
SupplierRecommendationResults persisted
```

---

# 13. PurchaseOrderService Test

E8 契約增補：測試 DRAFT 無交期可建立／編輯，Approve 缺交期回 `EXPECTED_DELIVERY_DATE_REQUIRED` 且無狀態／稽核寫入，補交期後核准及下單成功；Order 原缺交期防線保留測試，但其前置資料須以舊資料模擬（例如直接資料庫佈置），不得再經新 Approve 產生。

必測：

```text
createDraft
updateDraft
approve
reject
markOrdered
```

錯誤情境：

```text
Supplier inactive
→ fail
```

```text
supplier override without reason
→ fail
```

```text
quantity override without reason
→ fail
```

```text
order without expectedDeliveryDate
→ fail
```

---

# 14. InventoryImportService Test

Validate：

```text
valid CSV
→ VALIDATED
```

```text
row error exists
→ VALIDATION_FAILED
```

重要：

Validate 階段：

```text
InventoryRepository update
```

不得被呼叫。

---

# 15. Repository Test

使用：

```java
@DataJpaTest
```

只測：

```text
custom query
constraint
mapping
```

不浪費時間測：

```text
save()
findById()
```

等 Spring Data 基礎功能。

---

# 16. Demand Repository Test

例如：

```text
sumActiveDemand()
```

確認：

```text
ACTIVE counts
CANCELLED ignored
date range correct
```

---

# 17. Incoming Inventory Query Test

核心案例：

```text
ORDERED
expectedDeliveryDate <= requiredDate
→ counted
```

```text
ORDERED
expectedDeliveryDate > requiredDate
→ ignored
```

```text
APPROVED
→ ignored
```

```text
RECEIVED
→ ignored
```

---

# 18. Security Test

使用：

```text
MockMvc
@WebMvcTest
or
@SpringBootTest
```

測 Role Matrix 與 Security Response。

---

# 19. Security Test Cases

E8 授權矩陣測試新增：Receiving 專用 GET 與 POST 的 WAREHOUSE_STAFF / ADMIN 允許、其他角色 403、未登入 401；StockTransaction GET 的 WAREHOUSE_STAFF / PROCUREMENT_MANAGER / ADMIN 允許；AI Query 與 Proposal / Confirm 分開測試，含 Drawer 角色可見性。

## No JWT

```text
protected endpoint
→ 401 UNAUTHENTICATED
```

## Wrong Role

```text
PROCUREMENT_STAFF
approve PO
→ 403 ACCESS_DENIED
```

## Correct Role

```text
PROCUREMENT_MANAGER
approve PO
→ allowed
```

## Warehouse

```text
WAREHOUSE_STAFF
receive PO
→ allowed
```

---

# 20. Audit Security Test

Request 不允許控制：

```text
approvedBy
orderedBy
receivedBy
uploadedBy
```

Audit User 必須來自：

```text
SecurityContext
↓
CurrentUserProvider
```

---

# 21. Transaction Integration Test

SupplyPilot V1 最重要的 Integration Test：

```text
Receiving rollback
Inventory Import rollback
Recommendation Snapshot atomicity
```

建議使用真 MySQL。

---

# 22. Receiving Rollback Test

初始：

```text
PO = ORDERED
Inventory A = 100
Inventory B = 200
```

故意讓：

```text
StockTransaction B insert fail
```

Then：

```text
Inventory A = 100
Inventory B = 200
PO = ORDERED
no StockTransaction committed
```

證明：

```text
@Transactional
```

真的保護整個 Use Case。

---

# 23. Inventory Import Rollback Test

準備：

```text
3 Inventory rows
```

Confirm 時讓：

```text
row 3 fails
```

Then：

```text
all quantities unchanged
Batch != IMPORTED
```

不可 Partial Import。

---

# 24. Recommendation Snapshot Atomicity Test

故意讓：

```text
SupplierRecommendationResult
```

其中一筆 persist fail。

Then：

```text
PurchaseRecommendation not committed
SupplierRecommendationResults not committed
```

不可 Partial Snapshot。

---

# 25. Concurrency Test

V1 正式使用：

```text
@Version
```

因此必須至少有核心 concurrency test。

---

# 26. Inventory Optimistic Lock Test

兩個 Transaction：

```text
A reads version=1
B reads version=1
```

A Commit：

```text
version 1 → 2
```

B Update：

```text
OptimisticLockException
```

驗證：

```text
no Lost Update
```

---

# 27. PurchaseOrder Concurrent Receive Test

兩個 Transaction：

```text
A reads ORDERED version=3
B reads ORDERED version=3
```

A Receive 成功。

B：

```text
Optimistic Lock failure
```

最後：

```text
Inventory increased once
StockTransaction only one set
PO = RECEIVED
```

---

# 28. AI Tool Boundary Test

AI 測試重點不是：

```text
LLM wording quality
```

而是：

```text
Tool permission boundary
Service boundary
High-risk action prohibition
```

---

# 29. AI Allowed Tool Test

允許：

```text
queryRecommendation
queryInventory
queryOpenPO
searchDocuments
createDraft
```

---

# 30. AI Forbidden Tool Test

禁止：

```text
approvePO
rejectPO
orderPO
receivePO
adjustInventory
userManagement
masterDataWrite
```

---

# 31. AI Tool Must Call Service

例如：

```text
PurchaseOrderTool.createDraft()
```

必須呼叫：

```text
PurchaseOrderService.createDraft()
```

不得直接：

```text
PurchaseOrderRepository.save()
```

避免繞過：

```text
Authorization
Validation
Transaction
Audit
Domain Rule
```

---

# 32. RAG Test

需要測：

```text
retrieval returns source metadata
```

以及：

```text
no evidence
→ INSUFFICIENT_EVIDENCE
```

而不是生成無來源答案。

---

# 33. Controller Test Scope

Controller Test 主要測：

```text
Request Mapping
DTO Validation
HTTP Status
Error Response Format
Security Integration
```

Business Logic 留在：

```text
Domain
Service
Integration Test
```

---

# 34. Test Database

Production：

```text
MySQL
```

Integration Test 建議：

```text
Testcontainers + MySQL
```

優於只使用：

```text
H2
```

因為可以接近真實：

```text
SQL dialect
constraint
transaction
locking
```

---

# 35. Testcontainers Strategy

Integration Test 啟動：

```text
MySQL Container
```

每次提供乾淨 DB。

作品集面試可說：

> Repository 與 transaction integration test 使用 Testcontainers MySQL，避免 H2 與 production database 行為差異。

---

# 36. Test Priority

## P0 — Must Have

```text
NetRequirementCalculator
PurchaseQuantityCalculator
SupplierConstraintEvaluator
Supplier Ranking
PurchaseOrder Lifecycle
Inventory Domain
RecommendationOutcome

IncomingInventoryQuery
Receiving Rollback
Inventory Import Rollback
Security Role Test
Optimistic Lock Test
```

---

# 37. P1 — Should Have

```text
PurchaseRecommendationService
PurchaseOrderService
InventoryImportService
Custom Repository Query
Error Response Mapping
DTO Validation
```

---

# 38. P2 — Nice to Have

```text
Document ingestion integration
AI provider failure handling
broader controller tests
pagination edge cases
additional RAG integration
```

---

# 39. Coverage Strategy

不要求：

```text
100%
```

可參考：

```text
overall >= 70%
core domain >= 90%
```

但重點仍是：

```text
critical business behavior
```

不是單純 coverage number。

---

# 40. CI Test Layers

**V2 每張 Issue 的精準 CI：** 依變更範圍執行必要 Compile、Unit／Service／Repository／Security、受影響 Vitest、API 契約、Integration（必要時實際 MySQL／PostgreSQL pgvector Testcontainers）、Concurrency、Lint／Build，以及該 Issue 的 Browser／可及性驗收。必要範圍的測試須全部通過；保存 Tests／Failures／Errors／Skipped、Exit Code、Log、精確 SHA。GitHub Actions 暫停作為 Gate，僅可標 LOCAL VERIFIED。

**V2 全部規劃 Issues 合併並整合後：** 再執行完整 Maven verify、完整 Vitest／Lint／TypeScript／Frontend Production Build、相關 MySQL／PostgreSQL Migration、跨模組安全／交易／併發與必要 E2E，解決先前保留的全量測試失敗。真實 Gemini／AWS／正式部署驗收另依範圍安排，不冒稱尚未執行項目 PASS。

原完整套件若已有 FAIL，保留原始失敗與逐項分診；只有可證明和本 Issue 無直接或間接關聯的失敗，才允許標記 DEFERRED TO V2 INTEGRATION。不足以排除本 Issue 風險的必要測試失敗仍阻擋交付。

---

# 41. PR Merge Gate

1. Codex 實作並執行受影響開發期測試；Commit／Push 精確 HEAD。
2. GPT 獨立 Code Review：0 Blocking Finding。
3. Codex 執行該 SHA 的必要精準 CI、風險整合測試及當張 Issue 驗收。
4. GPT 核實原始證據與已知風險，符合條件方核發 FINAL DELIVERY PASS，建立繁體中文 PR，獨立一行 Closes #Issue。
5. 使用者手動 Merge；所有 V2 Issues 整合後再執行完整 CI 與正式部署驗收。

**PR Gate PASS ≠ 完整 CI PASS ≠ GitHub Actions PASS ≠ Gemini／Browser／AWS 尚未執行部分 PASS。**

---

# 42. Test Naming

推薦：

```text
shouldXxxWhenYyy()
```

例如：

```java
shouldReturnZeroWhenInventoryExceedsRequirement()
```

```java
shouldRejectApprovalWhenPurchaseOrderIsReceived()
```

```java
shouldRollbackAllInventoryUpdatesWhenReceivingFails()
```

Test 名稱應描述 Business Behavior。

---

# 43. Test Package Structure

```text
src/test/java/com/supplypilot/
├── material/
├── supplier/
├── demand/
├── inventory/
├── recommendation/
├── procurement/
├── security/
└── integration/
```

盡量與 production package 對齊。

---

# 44. Final Testing Rules

正式鎖定：

1. Unit Test 保護純 Business Logic。
2. Calculator 不啟 Spring Context。
3. Domain Lifecycle 必須 Unit Test。
4. Custom Repository Query 必須測。
5. Receiving 必須有 Rollback Integration Test。
6. Inventory Import 必須有 All-or-Nothing Test。
7. Recommendation Snapshot 必須測 Atomicity。
8. Inventory / PO Optimistic Lock 必須有 concurrency test。
9. Security Role Matrix 必須測高風險 Endpoint。
10. No JWT → 401。
11. Wrong Role → 403。
12. Audit User 只來自 SecurityContext。
13. Integration Test 使用 MySQL Testcontainers 優於 H2。
14. AI 測試重點是 Tool Boundary。
15. AI 不可有 Approve / Order / Receive / Inventory Write Tool。
16. RAG 無證據應回 `INSUFFICIENT_EVIDENCE`。
17. 不追求 100% coverage。
18. Core Domain 優先高 coverage。
19. PR 必須通過核心 Test 才能 Merge。
20. Test Name 描述 Business Behavior。

SP-612 receiving 另以 MySQL Testcontainers 驗證：ORDERED full receive happy path、WAREHOUSE_STAFF / ADMIN 與 401/403 role matrix、三個 Item 對應三筆 Inventory 增量與三筆 `PURCHASE_RECEIVE`、GET Detail receiving audit、RECEIVED 排除 Incoming、duplicate guard，以及第三筆 StockTransaction constraint failure 時前兩筆已 flush 的 Inventory / StockTransaction 與 PO audit 全部 rollback。完整雙交易 barrier/latch 競爭測試仍屬 SP-613。

SP-613 以 MySQL 8.4 Testcontainers、兩個 request thread、兩個獨立 `READ_COMMITTED` transaction / persistence context 與有限 timeout 的 latch 驗證 concurrent receive。測試先在各自 transaction 中載入同一張 `ORDERED` PO，記錄雙方讀到相同 version，待兩方都完成讀取後只允許一方完成 receive 並 commit；另一方才繼續執行，因此會以 stale PO version 觸發 `ObjectOptimisticLockingFailureException`（cause 為 `PurchaseOrder` 的 `StaleObjectStateException`），HTTP 回 `409 CONCURRENT_MODIFICATION`。測試同時驗證成功方使 PO / 各 Inventory version 各增加一次、Inventory 僅增加一組 PO Item 數量、StockTransaction 數量恰等於 PO Item 數量，以及失敗 transaction 先前執行的 Inventory / StockTransaction 寫入全部 rollback。首次 commit 後另行送出的 receive 則獨立驗證為 `409 DUPLICATE_RECEIVING`，不可視為 optimistic lock 證據。

---

# 45. Next Stage

下一階段：

```text
Testing Strategy
↓
Implementation Plan
↓
14-Day Development Schedule
```

Implementation Plan 應將：

```text
Module
Dependency Order
Database Migration
API
Test
Docker
CI/CD
Deployment
AI/RAG
```

拆成可以逐日執行的工作項目。

---

# Part II — Test Data Strategy

# 1. Test Data 分類

SupplyPilot 將資料分成三類：

```text
1. Test Fixture / Builder
2. Integration Test Seed
3. Demo Seed Data
```

三者用途不同。

---

# 2. Test Fixture / Builder

用途：

```text
Unit Test
Service Test
Repository Test
```

特性：

```text
small
deterministic
explicit
easy to read
easy to modify
```

不追求真實資料量。

---

# 3. Integration Test Seed

用途：

```text
Transaction Test
Rollback Test
Concurrency Test
Security Integration Test
Repository Query Test
```

特性：

```text
small but relationally complete
real DB constraints
real transaction behavior
```

建議搭配：

```text
Testcontainers + MySQL
```

---

# 4. Demo Seed Data

用途：

```text
Frontend Demo
Portfolio Demo
README screenshots
Manual E2E
Interview presentation
```

特性：

```text
more realistic
larger data set
visually useful
business-story oriented
```

Demo Data 不應拿來取代 Unit Test Fixture。

---

# 5. Core Rule — No Random Data

正式測試資料禁止依賴：

```text
Math.random()
random UUID for business behavior
random date
random supplier score
```

因為會導致：

```text
flaky test
hard-to-debug failure
non-repeatable ranking
```

---

# 6. Fixed Clock

所有涉及：

```text
requiredDate
leadTime
expectedDeliveryDate
overdue
```

的測試，建議使用固定 Clock。

Baseline：

```text
TEST_TODAY = 2026-10-08
```

Production Code 若需要：

```java
Clock
```

由 Spring 注入。

Test：

```text
Clock.fixed(...)
```

避免直接：

```text
LocalDate.now()
```

散落在 Business Logic。

---

# 7. Business Code Naming

統一：

```text
Material:
MAT-001
MAT-002
MAT-003
...

Supplier:
SUP-001
SUP-002
SUP-003
...

PO:
由 production generator 建立
```

Test Entity ID 可：

```text
1L
2L
3L
```

保持簡單。

---

# 8. Baseline Users

測試 / Demo 固定使用：

| Username | Role |
|---|---|
| planner | PRODUCTION_PLANNER |
| buyer | PROCUREMENT_STAFF |
| manager | PROCUREMENT_MANAGER |
| warehouse | WAREHOUSE_STAFF |
| admin | ADMIN |

Test password 不要在 Domain Test 使用。

Security Integration Test 可使用：

```text
demo-password
```

再經 BCrypt 建立。

---

# 9. Baseline Materials

建議固定一組：

## MAT-001 — Assam Tea Extract

```text
code = MAT-001
name = Assam Tea Extract
baseUnit = KG
safetyStock = 200
status = ACTIVE
```

## MAT-002 — Mango Concentrate

```text
code = MAT-002
name = Mango Concentrate
baseUnit = KG
safetyStock = 100
status = ACTIVE
```

## MAT-003 — Fructose Syrup

```text
code = MAT-003
name = Fructose Syrup
baseUnit = KG
safetyStock = 300
status = ACTIVE
```

## MAT-004 — Milk Tea Base

```text
code = MAT-004
name = Milk Tea Base
baseUnit = KG
safetyStock = 150
status = ACTIVE
```

## MAT-005 — Tapioca Flavor Powder

```text
code = MAT-005
name = Tapioca Flavor Powder
baseUnit = KG
safetyStock = 50
status = INACTIVE
```

---

# 10. Baseline Suppliers

## SUP-001 — Formosa Ingredients

```text
reliability = 92
quality = 90
status = ACTIVE
```

## SUP-002 — East Harbor Trading

```text
reliability = 85
quality = 91
status = ACTIVE
```

## SUP-003 — Green Leaf Supply

```text
reliability = 88
quality = 86
status = ACTIVE
```

## SUP-004 — Old Vendor Co.

```text
reliability = 70
quality = 72
status = INACTIVE
```

---

# 11. Baseline SupplierMaterial

以 MAT-001 為主要 Recommendation 測試 Material。

## SUP-001 / MAT-001

```text
unitPrice = 100
MOQ = 800
packageMultiple = 100
leadTimeDays = 5
status = ACTIVE
```

## SUP-002 / MAT-001

```text
unitPrice = 104
MOQ = 500
packageMultiple = 50
leadTimeDays = 3
status = ACTIVE
```

## SUP-003 / MAT-001

```text
unitPrice = 97
MOQ = 1000
packageMultiple = 100
leadTimeDays = 10
status = ACTIVE
```

## SUP-004 / MAT-001

```text
unitPrice = 90
MOQ = 500
packageMultiple = 50
leadTimeDays = 2
status = INACTIVE
```

這組資料刻意製造：

```text
價格
MOQ
Lead Time
Reliability
Quality
```

之間的 trade-off。

---

# 12. Standard Scenario A — Purchase Not Required

Input：

```text
Material = MAT-001
Expected Demand = 100
Safety Stock = 20
Current Inventory = 150
Incoming Inventory = 0
```

Calculation：

```text
100 + 20 - 150 - 0 = -30
```

Final：

```text
Net Requirement = 0
Outcome = PURCHASE_NOT_REQUIRED
```

用途：

```text
NetRequirementCalculator
RecommendationOutcome
RecommendationService
```

---

# 13. Standard Scenario B — Normal Recommendation

Input：

```text
Material = MAT-001
Expected Demand = 1000
Safety Stock = 200
Current Inventory = 300
Incoming Inventory = 150
```

Calculation：

```text
1000 + 200 - 300 - 150 = 750
```

Final：

```text
Net Requirement = 750
```

Supplier-specific quantity：

```text
SUP-001:
MOQ 800
package 100
→ 800

SUP-002:
MOQ 500
package 50
→ 750

SUP-003:
MOQ 1000
package 100
→ 1000
```

用途：

```text
Recommendation
PurchaseQuantityCalculator
Ranking
Frontend Demo
```

---

# 14. Standard Scenario C — No Qualified Supplier

設定：

```text
Net Requirement > 0
```

但：

```text
all active SupplierMaterial lead time
>
requiredDate feasibility
```

Final：

```text
Outcome = NO_QUALIFIED_SUPPLIER
```

用途：

```text
SupplierConstraintEvaluator
RecommendationOutcome
Frontend warning state
```

---

# 15. Standard Scenario D — Inactive Supplier

使用：

```text
SUP-004
```

即使：

```text
price lowest
lead time shortest
```

仍：

```text
qualified = false
reason = SUPPLIER_INACTIVE
```

用途：

```text
Hard Constraint before Ranking
```

---

# 16. Standard Scenario E — Demand Aggregation

資料：

```text
Demand #1
MAT-001
ACTIVE
500 KG
2026-10-20

Demand #2
MAT-001
ACTIVE
300 KG
2026-10-25

Demand #3
MAT-001
CANCELLED
400 KG
2026-10-22
```

Expected：

```text
sumActiveDemand = 800
```

用途：

```text
DemandRepository
RecommendationService
```

---

# 17. Standard Scenario F — Incoming Inventory

PO：

```text
PO-A
status = ORDERED
MAT-001 = 150
expectedDeliveryDate = 2026-10-18
```

Required Date：

```text
2026-10-20
```

Expected：

```text
incoming = 150
```

另外：

```text
PO-B
status = APPROVED
→ ignored

PO-C
status = ORDERED
expectedDeliveryDate = 2026-10-25
→ ignored

PO-D
status = RECEIVED
→ ignored
```

---

# 18. Standard Scenario G — Receiving

Before：

```text
Inventory MAT-001 = 100
PO = ORDERED
PO Item MAT-001 = +50
```

After：

```text
Inventory = 150
StockTransaction = +50 PURCHASE_RECEIVE
PO = RECEIVED
```

用途：

```text
ReceivingService
Integration Test
Frontend Demo
```

---

# 19. Standard Scenario H — Receiving Rollback

PO：

```text
Item A +100
Item B +200
Item C +300
```

故意讓：

```text
StockTransaction C insert fail
```

Expected：

```text
Inventory A unchanged
Inventory B unchanged
Inventory C unchanged
PO remains ORDERED
no StockTransaction committed
```

---

# 20. Standard Scenario I — Inventory Import

CSV：

```csv
material_code,quantity,unit
MAT-001,300,KG
MAT-002,120,KG
MAT-003,500,KG
```

Validate：

```text
VALIDATED
```

Confirm：

```text
Inventory snapshot replaced
```

不是：

```text
Inventory += imported quantity
```

---

# 21. Standard Scenario J — Inventory Import Validation Error

CSV：

```csv
material_code,quantity,unit
MAT-001,300,KG
MAT-002,-20,KG
MAT-999,50,KG
MAT-003,100,PCS
MAT-001,50,KG
```

Expected：

```text
MAT-002 → INVALID_QUANTITY
MAT-999 → MATERIAL_NOT_FOUND
MAT-003 → UNIT_MISMATCH
second MAT-001 → DUPLICATE_MATERIAL
```

Batch：

```text
VALIDATION_FAILED
```

Inventory：

```text
unchanged
```

---

# 22. Standard Scenario K — Inventory Optimistic Lock

Initial：

```text
Inventory MAT-001
quantity = 100
version = 5
```

Transaction A：

```text
Receiving
100 → 150
version 5 → 6
```

Transaction B：

```text
Import / Adjustment
using version 5
```

Expected：

```text
OptimisticLockException
409 CONCURRENT_MODIFICATION
```

---

# 23. Standard Scenario L — Concurrent PO Receive

Initial：

```text
PO status = ORDERED
version = 3
```

Request A：

```text
reads version 3
```

Request B：

```text
reads version 3
```

A：

```text
receive
commit
version 4
```

B：

```text
commit fails
```

Expected：

```text
Inventory increased once
StockTransaction created once
PO RECEIVED
```

---

# 24. Test Fixture Pattern

建議建立：

```text
src/test/java/com/supplypilot/testdata/
```

例如：

```text
MaterialTestData
SupplierTestData
SupplierMaterialTestData
DemandTestData
InventoryTestData
PurchaseRecommendationTestData
PurchaseOrderTestData
UserTestData
```

---

# 25. Example Fixture API

```java
Material material =
    MaterialTestData.activeMaterial();

Supplier supplier =
    SupplierTestData.activeSupplier();

PurchaseOrder po =
    PurchaseOrderTestData.orderedPurchaseOrder();
```

避免每個 Test：

```text
手動 new 十幾個欄位
```

---

# 26. Fixture Design Rule

Fixture 應提供：

```text
sensible default
+
small override capability
```

例如：

```java
MaterialTestData.activeMaterial()
MaterialTestData.inactiveMaterial()
MaterialTestData.materialWithSafetyStock(new BigDecimal("200"))
```

不要建立過度複雜 fluent builder framework。

---

# 27. Test Object Mother vs Builder

V1 建議簡單使用：

```text
Test Data Factory / Object Mother
```

即可。

不要一開始做：

```text
complex generic builder framework
```

因為只會增加測試維護成本。

---

# 28. Repository Test Seed

Repository Test 建議明確 insert：

```text
ACTIVE Demand
CANCELLED Demand
ORDERED PO
APPROVED PO
RECEIVED PO
```

用來測：

```text
sumActiveDemand
incoming inventory query
status filtering
date filtering
```

資料量保持最小。

---

# 29. Integration Test Isolation

每個 Integration Test：

```text
must not depend on previous test
```

避免：

```text
test order dependency
```

策略：

```text
transaction rollback
database cleanup
or fresh Testcontainers context
```

依成本選擇。

---

# 30. Demo Dataset Size

Day 11–14 建立：

## Materials

```text
10–20
```

## Suppliers

```text
5–8
```

## SupplierMaterial

```text
20–40
```

## Demand

```text
10–20 records
```

## Purchase Orders

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
```

都至少有範例。

---

# 31. Demo Data Business Story

Demo Data 不應只是：

```text
Test Material 1
Supplier A
Supplier B
```

應像真公司：

```text
Assam Tea Extract
Jasmine Tea Extract
Mango Concentrate
Passion Fruit Puree
Fructose Syrup
Brown Sugar Syrup
Milk Tea Base
Taro Flavor Powder
```

這樣前端 Demo 才有說服力。

---

# 32. Demo Supplier Story

例如：

```text
Formosa Ingredients
East Harbor Trading
Green Leaf Supply
Pacific Food Materials
Golden Field Trading
```

不同：

```text
price
MOQ
lead time
reliability
quality
```

刻意做出 Ranking 差異。

---

# 33. Demo PO Distribution

準備：

```text
2 DRAFT
2 APPROVED
3 ORDERED
2 RECEIVED
1 REJECTED
```

這樣 Dashboard 和 PO List 才能有內容。

---

# 34. Demo Inventory Distribution

刻意包含：

```text
Inventory > safety
Inventory near safety
Inventory below safety
Inventory zero
Inventory with incoming PO
```

讓 Recommendation 頁能展示不同 Outcome。

---

# 35. Demo Documents

可準備：

```text
Supplier Contract
Supplier Quotation
Procurement SOP
Material Specification
Quality Requirement
```

每份有：

```text
title
supplier
documentType
version
status
```

供 RAG Demo。

---

# 36. CSV Sample Files

建議 Repository 保留：

```text
samples/
├── inventory-valid.csv
├── inventory-invalid-unit.csv
├── inventory-invalid-quantity.csv
├── inventory-duplicate-material.csv
└── inventory-mixed-errors.csv
```

用途：

```text
manual demo
integration test fixture
README
```

---

# 37. Seed Mechanism

Demo Seed 建議：

```text
Flyway demo migration
or
Spring profile seeder
```

不要 Production 永遠自動灌 Demo Data。

推薦：

```text
spring.profiles.active=demo
```

才執行 Demo Seeder。

---

# 38. Demo Profile

例如：

```text
application-demo.yml
```

啟用：

```text
DemoDataInitializer
```

Production：

```text
disabled
```

---

# 39. Test vs Demo Data Separation

正式分開：

```text
src/test/
→ Test Fixture

samples/
→ CSV sample

demo profile
→ Demo Seed
```

不要讓：

```text
Unit Test
```

依賴 Demo Seeder。

---

# 40. Sensitive Data Rule

所有 Test / Demo Data：

```text
must be fictional
```

不要放：

```text
real supplier confidential price
real employee identity
real contract
real company internal data
```

即使資料來自過去工作，也應改寫。

---

# 41. Data Reset for Demo

面試 Demo 最好能：

```text
reset demo environment
```

方式可簡單：

```text
re-run demo seed
reset DB snapshot
redeploy demo DB
```

不需要做 UI Reset Button。

---

# 42. Standard Demo Script Data

推薦固定一條主流程：

```text
Material:
MAT-001 Assam Tea Extract

Demand:
1000 KG

Safety:
200 KG

Inventory:
300 KG

Incoming:
150 KG

Net Requirement:
750 KG
```

Supplier：

```text
SUP-001
SUP-002
SUP-003
```

Ranking 結果固定。

接著：

```text
Create Draft
Approve
Order
Receive
```

這條資料應在每次 Demo 都能重現。

---

# 43. Snapshot Expectation

Recommendation Snapshot Test 要保存固定：

```text
expectedDemand
safetyStock
currentInventory
incomingInventory
netRequirement
supplier price
lead time
reliability
quality
rank
```

之後即使 Master Data 被改：

```text
old recommendation snapshot unchanged
```

這也應有 Test Data 支援。

---

# 44. Data Versioning

若 Demo Seed 有重大改動：

```text
v1
v2
```

可在 README 簡單記錄。

不需要做正式 Data Version Framework。

---

# 45. Implementation Timing

## 現在

先鎖：

```text
Baseline users
Baseline materials
Baseline suppliers
Standard scenarios
CSV examples
Naming rules
```

## Day 2–10

每做 Module：

```text
建立對應 Fixture
建立對應 Integration Seed
```

## Day 11–13

補完整：

```text
Demo Seed Data
Demo Documents
Dashboard distribution
PO distribution
```

## Day 14

使用固定 Demo Data：

```text
跑完整 E2E
截圖
README
面試 Demo rehearsal
```

---

# 46. Final Test Data Rules

1. Test Data 與 Demo Data 分開。
2. Test Data 必須 deterministic。
3. 不使用 random business data。
4. 日期測試使用固定 Clock。
5. Business Code 使用固定命名。
6. Standard Scenario 應可重複使用。
7. Unit Test Fixture 小而明確。
8. Integration Seed 關係完整但資料量小。
9. Demo Data 要有真實 Business Story。
10. Recommendation 基準案例固定。
11. Receiving 基準案例固定。
12. Inventory Import 必須保留 sample CSV。
13. Concurrency 測試必須使用明確 version。
14. Test 不依賴執行順序。
15. Demo Profile 不得污染 Production。
16. 所有 Test / Demo Data 必須 fictional。
17. 不使用真實公司機密資料。
18. Demo PO 要涵蓋所有主要狀態。
19. Demo Inventory 要涵蓋多種庫存情境。
20. Day 14 不應第一次才建立測試資料。

---

## SP-720 / SP-721 Spring AI Test Addendum (2026-10-11)

- 使用 Spring AI 2.0.x `ChatClient` / `ChatModel` / `@Tool` 或 `ToolCallback`。自動化 Integration Tests 應使用 Deterministic Fake ChatModel 與受控 Tool Invocation，不可呼叫付費 API。
- SP-720 必須驗證 Allowed Read-only Tools 確實呼叫既有 Application / Query Service，而非直接 Repository／重新計算採購數字；所有 Forbidden Write Tool 不可被模型呼叫或註冊。
- 驗證當前登入者身份與 Role／Method Security 穿過 AI → Tool → Service Boundary；匿名 401、錯誤角色 403；Prompt Injection 或假冒 System Admin 不得提權。
- 驗證 Provider Unavailable、Timeout、Invalid Response 的既有錯誤契約及核心 API 獨立性；保留 SP-710 的 `INSUFFICIENT_EVIDENCE` 與 Source / Version。
- SP-721 延續 Tool Boundary，驗證結構化 Draft Proposal 不會自行寫入；必須 Backend 確認後才走 `PurchaseOrderService.createDraft()`，只建立 DRAFT。

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

## V2-201 PO Draft 驗證增補

- 直接 PO 建立不帶 Demand/Recommendation/Ranking 參照；驗證空交期可建立、缺交期核准拒絕且資料/版本不變，補齊後核准成功，核准後 Draft Edit 拒絕。
- 驗證 ACTIVE Supplier/Material/SupplierMaterial 關係、至少一項品項、正數數量、非負單價及 `DECIMAL(19,4)` DB 邊界；保留 existing supplier-material validity gate。
- 對含歷史推薦來源的 PO 以完整 item 清單編輯數量/單價/供應商後，斷言 Recommendation FK 與歷史快照/覆寫原因保持不變；V2 ADMIN 對 Demand 寫入與 Recommendation 建立取得 403，歷史 read 不被刪除。
- PO 的 `@Version` 與完整交易回歸，另驗證 edit/approve 並發只允許合法提交者，409 後查詢最新狀態；收貨/庫存原子性及已合併 #137 快照測試仍須通過。

---

## E8 Backend 前置契約與 UI 測試盤點（2026-10-11）

- Backend A：`PurchaseOrderTests`、`PurchaseOrderServiceTests`、`PurchaseOrderLifecycleIntegrationTests` 補 DRAFT 空交期／核准失敗／補齊成功；`PurchaseOrderOrderingIntegrationTests` 現有 `approved(null, material)` 案例會在新 Approve 驗證先失敗，須改以既有異常 APPROVED 資料佈置測 Order 防線，不能刪除該防線覆蓋。檢查 `PurchaseOrderUpdateIntegrationTests` 的 DRAFT 清除交期與 APPROVED 不可編輯，並盤點 `PurchaseOrderIncomingInventoryQueryIntegrationTests`、AI Draft 相關 fixture。
- Backend B：新增 Receiving List / Detail 分頁、日期／物料篩選、ORDERED 預設與 RECEIVED 歷史、401/403/404 測試；成功 POST 嚴格比對受限 DTO 欄位與敏感欄位缺席。現有 `PurchaseOrderReceivingIntegrationTests` 斷言 `approvedBy` 等完整 PO 欄位，須改為受限回應；`PurchaseOrderConcurrentReceivingIntegrationTests` 仍須驗證 `receivedBy`、唯一成功、一次庫存／交易寫入與失敗回滾。檢查所有依賴 `PurchaseOrderResponse` 的 API client 與測試，而非預估只改少量斷言。
- Backend C：新增 StockTransaction List / Detail、正負 quantity、單位、篩選、日期界限、穩定排序、分頁、401/403/404、不可直接寫入與跨單位不合計測試；人工調整與收貨既有寫入測試維持。
- Frontend：測 SP-800 角色 Sidebar／Route、SP-802 Tabs、SP-803 Import 三步驟、SP-804 無歷史清單的工作站、SP-805 倉管收貨與核准交期、SP-806 Query／Proposal 角色差異及 `expiresAt`、SP-807 409／重複收貨／逾時未知結果先查詢及 403／500／AI 503。既有 Receiving 全交易原子性與並行測試不得因 DTO 變更而降低。

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

## V2-202 驗收紀錄

Issue #139 的測試指令、環境、測試數、Migration 與 CI 狀態以 [`docs/evidence/issue-139-validation.md`](evidence/issue-139-validation.md) 為準。須區分本機結果與 GitHub Actions；收貨/取消、雙取消、核准/駁回、下單/取消競態使用 Testcontainers MySQL 實際查核 PO、Inventory 版本/數量及 StockTransaction。沒有第 6 欄持久化資料時不得以 Mock 情境宣稱階段保護 PASS。

## V2-204 驗收測試矩陣

- Testcontainers MySQL 驗證 5→6 保持 PO `ORDERED`、不增加 Inventory、不寫 StockTransaction；異常旗標與備註可儲存、GET 可讀。
- 驗證未開始第 6 欄及每種未到貨/短收/品質不合格情境都不能通過舊 `/receive`；只有三旗標全真且保存驗收操作者/時間才完成整筆收貨。
- 使用多品項交易失敗注入，驗證 Inventory、StockTransaction、PO 收貨狀態及稽核全部 rollback；重複收貨及並發收貨至多一筆成功。
- 以同一 MySQL PO 鎖同步執行取消/開始驗收，以及重複付款確認/撤銷；查詢 PO、Inventory 版本與 StockTransaction 確認無競態雙重提交。
- 付款確認與撤銷皆維持 `RECEIVED`，且不改庫存或庫存交易；重複、未付款撤銷、非 RECEIVED 狀態、401/403、錯誤 request 與未知結果查詢流程均要驗證。
- Flyway V27 以既有 PO 預設旗標/付款未確認做舊資料相容測試；Front-end API、互動狀態及載入後持久資料需精準測試。完整 Issue #141 驗收記錄於 `docs/evidence/issue-141-validation.md`。
