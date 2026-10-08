# SupplyPilot — Domain Class Diagram

> **文件目的**：說明 SupplyPilot V1 Domain Class Diagram 的 Class Responsibility、Association、Aggregate Boundary、Domain Service 與 Query Boundary。  
> **視覺化文件**：`domain-class-diagram.html`  
> **模型版本**：包含 Transaction / Validation / Security 設計後的最新 V1 修正。

---

# 1. Diagram Scope

本圖包含兩類 Class：

## Domain Entity / Aggregate

```text
Material
Supplier
SupplierMaterial
Inventory
Demand
InventoryImportBatch
InventoryImportItem
PurchaseRecommendation
SupplierRecommendationResult
PurchaseOrder
PurchaseOrderItem
StockTransaction
User
DocumentMetadata
```

共 14 個 Entity。

## Domain / Application Service

```text
NetRequirementCalculator
PurchaseQuantityCalculator
SupplierConstraintEvaluator
SupplierScoreCalculator
SupplierRankingService
PurchaseRecommendationService
PurchaseOrderService
ReceivingService
InventoryImportService
IncomingInventoryQuery
PurchaseOrderIncomingInventoryQuery
```

---

# 2. Main Domain Associations

```text
Material 1 : 1 Inventory
Material 1 : N Demand
Material 1 : N SupplierMaterial
Supplier 1 : N SupplierMaterial

Material 1 : N PurchaseRecommendation
PurchaseRecommendation 1 : N SupplierRecommendationResult
Supplier 1 : N SupplierRecommendationResult
Supplier 1 : N PurchaseRecommendation (recommended supplier, optional)

Supplier 1 : N PurchaseOrder
PurchaseOrder 1 : N PurchaseOrderItem
Material 1 : N PurchaseOrderItem

PurchaseRecommendation 1 : N PurchaseOrderItem (optional reference)
SupplierRecommendationResult 1 : N PurchaseOrderItem (optional reference)

Material 1 : N StockTransaction
PurchaseOrder 1 : N StockTransaction

InventoryImportBatch 1 : N InventoryImportItem
Material 1 : N InventoryImportItem (optional when validation failed)

Supplier 1 : N DocumentMetadata (optional supplier reference)
User 1 : N InventoryImportBatch
User 1 : N StockTransaction
User 1 : N DocumentMetadata
```

---

# 3. Aggregate Boundaries

## PurchaseOrder Aggregate

```text
PurchaseOrder
└── PurchaseOrderItem
```

`PurchaseOrder` 為 Aggregate Root。

外部不得獨立修改 `PurchaseOrderItem`。

主要 Domain Method：

```text
approve()
reject()
markOrdered()
receive()
```

---

## Recommendation Snapshot Aggregate

```text
PurchaseRecommendation
└── SupplierRecommendationResult
```

用途是保留「當下決策快照」。

Snapshot 不應被後續 Master Data 修改。

---

## Inventory Import Aggregate

```text
InventoryImportBatch
└── InventoryImportItem
```

`InventoryImportItem` 在 V1 正式加入，用來支援：

```text
Validate
→ Preview
→ Confirm
```

跨 HTTP Request 的持久化。

---

# 4. Domain Behavior

## Inventory

```text
receive(quantity)
adjust(delta)
```

Inventory 自己保護：

```text
quantity >= 0
```

並使用：

```text
@Version
```

防止 Lost Update。

## PurchaseOrder

```text
approve()
reject()
markOrdered()
receive()
```

PurchaseOrder 自己保護 Lifecycle：

```text
DRAFT → APPROVED
DRAFT → REJECTED
APPROVED → ORDERED
ORDERED → RECEIVED
```

並使用：

```text
@Version
```

防止 Duplicate Receiving / Concurrent State Change。

---

# 5. RecommendationOutcome

`PurchaseRecommendation` 正式包含：

```text
RecommendationOutcome
```

值：

```text
PURCHASE_NOT_REQUIRED
SUPPLIER_RECOMMENDED
NO_QUALIFIED_SUPPLIER
```

用途是區分：

```text
recommendedSupplier == null
```

到底代表：

```text
不需要採購
```

還是：

```text
需要採購但沒有合格供應商
```

---

# 6. Recommendation Calculation Components

## NetRequirementCalculator

```text
Expected Demand
+ Safety Stock
- Current Inventory
- Incoming Inventory
```

輸出：

```text
max(0, raw)
```

純計算，不查 DB。

## PurchaseQuantityCalculator

依：

```text
Net Requirement
MOQ
Package Multiple
```

計算 supplier-specific recommended quantity。

## SupplierConstraintEvaluator

Hard Constraint：

```text
Supplier ACTIVE
SupplierMaterial ACTIVE
Delivery Feasibility
```

## SupplierScoreCalculator

V1 Weight：

```text
Price       40%
Lead Time   30%
Reliability 20%
Quality     10%
```

## SupplierRankingService

```text
Qualified Candidates
→ Score
→ Sort
→ Tie Break
→ Rank
```

Tie Break：

```text
lower total cost
shorter lead time
higher reliability
supplier id/code
```

---

# 7. PurchaseRecommendationService

負責 Orchestration：

```text
Load Material
↓
Aggregate Demand
↓
Read Inventory
↓
Query Incoming PO
↓
Calculate Net Requirement
↓
Evaluate Supplier Constraint
↓
Calculate Supplier Quantity
↓
Rank Suppliers
↓
Persist PurchaseRecommendation
↓
Persist SupplierRecommendationResult
```

Transaction 需保證：

```text
Recommendation + all Results
```

Atomic。

---

# 8. IncomingInventoryQuery

為避免：

```text
recommendation ↔ procurement
```

形成 circular dependency，Recommendation 只依賴：

```text
IncomingInventoryQuery
```

Procurement 提供：

```text
PurchaseOrderIncomingInventoryQuery
```

其查詢條件：

```text
PO.status = ORDERED
AND expectedDeliveryDate <= requiredDate
```

因此：

```text
APPROVED PO
```

不計入 Incoming Inventory。

---

# 9. PurchaseOrderService

負責：

```text
createDraft
updateDraft
approve
reject
markOrdered
get/search
```

Receiving 因為跨多個 Aggregate，由 `ReceivingService` 獨立處理。

---

# 10. ReceivingService

協調：

```text
PurchaseOrder
Inventory
StockTransaction
```

流程：

```text
Load PO
↓
Validate ORDERED
↓
for each item:
    Inventory.receive()
    create StockTransaction
↓
PO.receive()
↓
Commit
```

整個流程：

```text
@Transactional
```

任何一步失敗：

```text
Rollback All
```

---

# 11. InventoryImportService

兩階段：

## Validate

```text
Parse CSV
↓
Validate Rows
↓
Persist Batch + Items
```

不更新 Inventory。

## Confirm

```text
Load VALIDATED Batch
↓
Load Import Items
↓
Replace Inventory Snapshot
↓
Mark Batch IMPORTED
```

Confirm 是：

```text
All-or-Nothing Transaction
```

---

# 12. Snapshot Classes

以下欄位表示歷史決策，不應依賴目前 Master Data 動態改寫：

## PurchaseRecommendation

```text
expectedDemand
safetyStock
currentInventory
incomingInventory
netRequirement
recommendedSupplier
recommendedQuantity
recommendedSupplierScore
outcome
```

## SupplierRecommendationResult

```text
unitPriceSnapshot
leadTimeDaysSnapshot
reliabilityScoreSnapshot
qualityScoreSnapshot
recommendedQuantity
totalCost
score breakdown
rank
```

## PurchaseOrderItem

```text
unitPrice
unit
recommendedSupplierIdSnapshot
recommendedQuantitySnapshot
```

---

# 13. Security-related Domain Boundaries

`User` 是 Domain Entity，但 Authentication Principal 使用：

```text
AuthenticatedUser
```

而不是直接將 JPA `User` 放入 SecurityContext。

Audit User：

```text
createdBy
approvedBy
orderedBy
receivedBy
uploadedBy
```

一律來自 SecurityContext / CurrentUserProvider。

---

# 14. DocumentMetadata Lifecycle

DocumentStatus：

```text
PROCESSING
READY
FAILED
INACTIVE
```

原因：

```text
MySQL
S3
Vector Store
Embedding Provider
```

無法共享同一 ACID Transaction。

Document ingestion 採：

```text
Eventually Consistent
```

---

# 15. Diagram Interpretation Rule

圖中：

```text
實線
```

表示主要 Domain Association。

```text
虛線
```

表示：

```text
uses
orchestrates
creates
manages
implements
```

等 Service / Query dependency。

UML 圖主要表達責任與依賴，不等同 ERD；DB 欄位與 Index 仍以：

```text
erd.md
```

為正式資料庫規格。

---

# 16. Design Baseline

目前 Domain Class Diagram 已反映：

```text
14 Entity
PurchaseOrder Aggregate
Recommendation Snapshot Aggregate
Inventory Import Aggregate
Optimistic Lock
RecommendationOutcome
IncomingInventoryQuery
Receiving transaction orchestration
AI-independent deterministic core
```

下一組 UML 建議：

```text
Recommendation Sequence Diagram
```

它會把：

```text
Security
Validation
Repository Reads
Calculators
Ranking
Transaction
Snapshot Persistence
Exception Path
```

完整串起來。
