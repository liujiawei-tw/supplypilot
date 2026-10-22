# SupplyPilot — Domain Model

> **文件目的**：定義 SupplyPilot V1 的核心 Domain Entity、Aggregate、關聯、Snapshot、生命週期、Constraint、Index、Enum 與 Domain / Service Responsibility，作為後續 ERD、API Design、JPA Mapping、Service Layer 與 Testing Strategy 的主要依據。  
> **文件定位**：核心 Domain 規格文件  
> **目前狀態**：V1 Domain Model Baseline  
> **關聯文件**：
> - `company-profile.md`
> - `as-is-procurement-flow.md`
> - `to-be-procurement-flow.md`
> - `functional-requirements.md`

---

# 1. Domain Modeling 原則

SupplyPilot V1 採以下原則：

1. Entity 具有明確身份與生命週期。
2. 交易歷史與 Recommendation 使用 Snapshot，避免被目前 Master Data 改寫。
3. 核心 Business Entity 不 Hard Delete。
4. 交易與 Lifecycle Rule 由 Domain / Service 保護。
5. DB Constraint 作為資料完整性的最後防線。
6. Controller / DTO 只處理輸入格式驗證，不承擔商業規則。
7. Aggregate Root 控制其 Child Entity 的修改。
8. 跨 Aggregate 的交易由 Application / Domain Service 協調。
9. 確定性的採購計算由 Backend Business Logic 負責。
10. AI 不直接控制核心 Domain Lifecycle。

---

# 2. V1 Entity List

V1 主要 Entity：

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

共 14 個主要 Entity。

---

# 3. Aggregate Overview

## 3.1 Material Aggregate

```text
Material
```

負責：

- 原料身份
- Base Unit
- Safety Stock
- Active / Inactive Lifecycle

---

## 3.2 Supplier Aggregate

```text
Supplier
```

負責：

- 供應商身份
- Reliability / Quality Master Score
- Active / Inactive Lifecycle

---

## 3.3 SupplierMaterial Aggregate

```text
SupplierMaterial
```

代表：

> 某 Supplier 對某 Material 的目前有效供貨條件。

負責：

- Unit Price
- MOQ
- Package Multiple
- Lead Time
- Currency
- Active / Inactive Lifecycle

SupplierMaterial 是正式 Entity，不是單純 Many-to-Many Join Table。

---

## 3.4 Demand Aggregate

```text
Demand
```

負責：

- 未來 Material Requirement
- Required Date
- Active / Cancelled Lifecycle

---

## 3.5 Inventory Aggregate

```text
Inventory
```

負責：

- Material Current Quantity
- Current Inventory State

Inventory 與 Material 在 V1 為 1:1。

---

## 3.6 Recommendation Aggregate

```text
PurchaseRecommendation
└── SupplierRecommendationResult
```

此 Aggregate 為 Decision Snapshot。

負責保存：

- Net Requirement Calculation Snapshot
- Supplier Candidate Snapshot
- Ranking Result
- Disqualification Result
- Recommended Supplier / Quantity

---

## 3.7 PurchaseOrder Aggregate

```text
PurchaseOrder
└── PurchaseOrderItem
```

PurchaseOrder 是 Aggregate Root。

負責：

- PO Lifecycle
- Supplier
- Approval / Ordering / Receiving Lifecycle
- PurchaseOrderItem Ownership
- Core Purchasing Data Immutability

---

## 3.8 StockTransaction

```text
StockTransaction
```

獨立 Audit Entity。

負責：

- Inventory Change History
- Purchase Receiving
- Manual Adjustment

---

# 4. Material

```text
Material
├── id
├── code
├── name
├── baseUnit
├── safetyStock
├── status
├── createdAt
└── updatedAt
```

## 4.1 Fields

| Field | Concept Type | Nullable | Constraint |
|---|---|---:|---|
| id | Long | No | PK |
| code | String | No | Unique |
| name | String | No | |
| baseUnit | BaseUnit Enum | No | |
| safetyStock | Decimal | No | >= 0 |
| status | MaterialStatus | No | ACTIVE / INACTIVE |
| createdAt | DateTime | No | |
| updatedAt | DateTime | No | |

## 4.2 Rules

- `code` 不可修改。
- `name` 可修改。
- `safetyStock` 可修改。
- 一旦 Material 已有交易或歷史資料，`baseUnit` 不可修改。
- Material 不 Hard Delete。
- Material 停用時改為 `INACTIVE`。

## 4.3 Index / Constraint

```text
PK(id)
UNIQUE(code)
INDEX(status)
```

---

# 5. Supplier

```text
Supplier
├── id
├── code
├── name
├── country
├── reliabilityScore
├── qualityScore
├── status
├── createdAt
└── updatedAt
```

## 5.1 Fields

| Field | Nullable | Constraint |
|---|---:|---|
| id | No | PK |
| code | No | Unique |
| name | No | |
| country | Yes | |
| reliabilityScore | No | 0 ~ 100 |
| qualityScore | No | 0 ~ 100 |
| status | No | ACTIVE / INACTIVE |
| createdAt | No | |
| updatedAt | No | |

## 5.2 Rules

- `code` 為 Business Identifier。
- Reliability / Quality Score 使用 Decimal。
- V1 Score 由 Master Data 手動維護。
- Supplier 不 Hard Delete。
- 停用以 `INACTIVE` 表示。

## 5.3 Index / Constraint

```text
PK(id)
UNIQUE(code)
INDEX(status)
```

---

# 6. SupplierMaterial

```text
SupplierMaterial
├── id
├── supplierId
├── materialId
├── unitPrice
├── moq
├── packageMultiple
├── leadTimeDays
├── currency
├── status
├── createdAt
└── updatedAt
```

## 6.1 Fields

| Field | Nullable | Constraint |
|---|---:|---|
| id | No | PK |
| supplierId | No | FK |
| materialId | No | FK |
| unitPrice | No | >= 0 |
| moq | No | > 0 |
| packageMultiple | Yes | > 0 |
| leadTimeDays | No | >= 0 |
| currency | No | V1 = TWD |
| status | No | ACTIVE / INACTIVE |
| createdAt | No | |
| updatedAt | No | |

## 6.2 Rules

- 同一 Supplier + Material 只保留一筆目前有效供貨條件。
- `unitPrice` 為目前有效價格。
- `leadTimeDays` 放在 SupplierMaterial，不放 Supplier。
- SupplierMaterial 不 Hard Delete。
- 停用改為 `INACTIVE`。

## 6.3 Constraint

```text
UNIQUE(supplier_id, material_id)
```

## 6.4 Index

```text
INDEX(material_id, status)
INDEX(supplier_id, status)
```

---

# 7. Inventory

```text
Inventory
├── id
├── materialId
├── quantity
├── updatedAt
└── version
```

## 7.1 Fields

| Field | Nullable | Constraint |
|---|---:|---|
| id | No | PK |
| materialId | No | Unique FK |
| quantity | No | >= 0 |
| updatedAt | No | |
| version | No | V1 Optimistic Lock (`@Version`) |

## 7.2 Relationship

```text
Material 1 : 1 Inventory
```

## 7.3 Rules

- Material 建立時同步建立 `Inventory(quantity = 0)`。
- Inventory 不允許負數。
- ERP Import 是 Snapshot Replace，不是 Increment。
- V1 使用 Transaction + Domain State Validation。
- `version` 正式於 V1 使用 JPA `@Version`，避免 Receiving / Import / Adjustment 的 Lost Update。

## 7.4 Constraint / Index

```text
UNIQUE(material_id)
```

---

# 8. Demand

```text
Demand
├── id
├── materialId
├── quantity
├── requiredDate
├── status
├── note
├── createdBy
├── createdAt
└── updatedAt
```

## 8.1 Fields

| Field | Nullable | Constraint |
|---|---:|---|
| id | No | PK |
| materialId | No | FK |
| quantity | No | > 0 |
| requiredDate | No | Valid Future / Current Date Rule |
| status | No | ACTIVE / CANCELLED |
| note | Yes | |
| createdBy | No | FK User |
| createdAt | No | |
| updatedAt | No | |

## 8.2 Rules

- Demand 不 Hard Delete。
- 取消需求使用 `CANCELLED`。
- 只有 `ACTIVE` Demand 參與 Aggregation。
- Demand 依 Calculation Period 聚合。
- 過去 Demand 不自動永久帶入未來 Calculation。

## 8.3 Index

```text
INDEX(material_id, status, required_date)
INDEX(required_date)
```

---

# 9. InventoryImportBatch

```text
InventoryImportBatch
├── id
├── filename
├── uploadedBy
├── uploadedAt
├── rowCount
├── status
└── errorSummary
```

## 9.1 Fields

| Field | Nullable | Constraint |
|---|---:|---|
| id | No | PK |
| filename | No | |
| uploadedBy | No | FK User |
| uploadedAt | No | |
| rowCount | No | >= 0 |
| status | No | InventoryImportStatus |
| errorSummary | Yes | |

## 9.2 Lifecycle

```text
UPLOADED
↓
VALIDATED
↓
IMPORTED
```

Validation Fail：

```text
VALIDATION_FAILED
```

Import Fail：

```text
IMPORT_FAILED
```

## 9.3 Rules

- V1 Import 採 All-or-Nothing。
- InventoryImportBatch 負責 Import Audit。
- ERP Snapshot Import 不建立一般 StockTransaction。
- V1 正式建立 `InventoryImportItem`，保存 Validate / Preview 結果，供後續 Confirm 使用。

## 9.4 InventoryImportItem

`InventoryImportItem` 是 V1 正式 Entity，屬於 `InventoryImportBatch` 的 Child Entity。

```text
InventoryImportItem
├── id
├── batchId
├── rowNumber
├── materialId
├── materialCodeSnapshot
├── quantity
├── unit
├── valid
├── errorCode
└── errorMessage
```

Rules：

- `InventoryImportBatch 1:N InventoryImportItem`。
- `materialId` 可 Nullable，因為不存在的 material code 也必須能保存驗證錯誤。
- Row-level validation error 保存於 Item，不以 HTTP Exception 表示。
- Confirm 僅能使用 `VALIDATED` Batch 的持久化 Item。
- Inventory Import 是 Snapshot Replace，不建立一般 `StockTransaction`。

Constraint / Index：

```text
INDEX(batch_id, row_number)
INDEX(material_id)
```

## 9.5 Index

```text
INDEX(uploaded_at)
INDEX(status)
INDEX(uploaded_by)
```

---

# 10. PurchaseRecommendation

```text
PurchaseRecommendation
├── id
├── materialId
├── periodStart
├── periodEnd
├── expectedDemand
├── safetyStock
├── currentInventory
├── incomingInventory
├── netRequirement
├── outcome
├── recommendedSupplierId
├── recommendedQuantity
├── recommendedSupplierScore
├── createdBy
└── createdAt
```

## 10.1 Snapshot Principle

以下欄位皆保存當時計算 Snapshot：

```text
expectedDemand
safetyStock
currentInventory
incomingInventory
netRequirement
outcome
recommendedSupplier
recommendedQuantity
recommendedSupplierScore
```

不能只回查現在 Master Data。

## 10.2 Nullable

`outcome` 必填，明確區分：

```text
PURCHASE_NOT_REQUIRED
SUPPLIER_RECOMMENDED
NO_QUALIFIED_SUPPLIER
```

以下欄位可 Nullable：

```text
recommendedSupplierId
recommendedQuantity
recommendedSupplierScore
```

其中：

- `PURCHASE_NOT_REQUIRED`：`netRequirement = 0`。
- `SUPPLIER_RECOMMENDED`：`netRequirement > 0` 且存在 qualified supplier。
- `NO_QUALIFIED_SUPPLIER`：`netRequirement > 0` 且無 qualified supplier。

## 10.3 Rules

- Recommendation 必須持久化。
- Recalculation 建立新的 Snapshot。
- 舊 Recommendation 不被後續 Master Data 改寫。
- PO Item 必須 Reference 當時使用的 Recommendation。
- `NO_QUALIFIED_SUPPLIER` 是合法 Recommendation Outcome，不是 Exception。

## 10.4 Index

```text
INDEX(material_id, created_at)
INDEX(period_end)
```

---

# 11. SupplierRecommendationResult

```text
SupplierRecommendationResult
├── id
├── recommendationId
├── supplierId
├── qualified
├── disqualifiedReason
├── recommendedQuantity
├── unitPriceSnapshot
├── totalCost
├── leadTimeDaysSnapshot
├── reliabilityScoreSnapshot
├── qualityScoreSnapshot
├── priceScore
├── leadTimeScore
├── reliabilityScore
├── qualityScore
├── totalScore
└── rank
```

## 11.1 Responsibility

代表：

> 某次 PurchaseRecommendation 中，某 Supplier 的完整 Candidate / Ranking Snapshot。

## 11.2 Rules

若：

```text
qualified = false
```

則：

```text
disqualifiedReason REQUIRED
```

V1 Disqualification Reason：

```text
SUPPLIER_INACTIVE
SUPPLIER_MATERIAL_INACTIVE
DELIVERY_DATE_NOT_FEASIBLE
```

## 11.3 Snapshot Fields

必須保存：

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

## 11.4 Relationship

```text
PurchaseRecommendation 1 : N SupplierRecommendationResult
```

## 11.5 Index

```text
INDEX(recommendation_id)
INDEX(recommendation_id, rank)
INDEX(supplier_id)
```

---

# 12. PurchaseOrder

```text
PurchaseOrder
├── id
├── poNumber
├── supplierId
├── status
├── expectedDeliveryDate
├── rejectReason
├── createdBy
├── createdAt
├── approvedBy
├── approvedAt
├── rejectedBy
├── rejectedAt
├── orderedBy
├── orderedAt
├── receivedBy
├── receivedAt
├── updatedAt
└── version
```

## 12.1 Fields

核心欄位：

```text
poNumber
supplier
status
expectedDeliveryDate
```

Lifecycle Audit：

```text
createdBy / createdAt
approvedBy / approvedAt
rejectedBy / rejectedAt
orderedBy / orderedAt
receivedBy / receivedAt
```

## 12.2 Status

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
```

## 12.3 Rules

- PurchaseOrder 是 Aggregate Root。
- 一張 PO 只能有一個 Supplier。
- 一張 PO 可以包含多個 Item。
- APPROVED 後核心採購欄位不可直接修改。
- `expectedDeliveryDate` 在 DRAFT 可為 `null`，建立與編輯時均可留空或清除；進入 APPROVED 前必須存在。核准交易內缺少時回 `EXPECTED_DELIVERY_DATE_REQUIRED`，不得更新狀態或稽核。APPROVED 後不得以 Draft Edit API 補交期；既有異常資料仍由 Order 防線拒絕下單。
- REJECTED 為 Terminal State。
- Reject 時 `rejectReason`、`rejectedBy`、`rejectedAt` 必須在同一 transaction 保存；`rejectedBy` 來自 authenticated user。
- V1 不提供 CANCELLED。
- V1 不提供 Delete API，包含 DRAFT。
- `OVERDUE` 不屬於 PO Status。
- `version` 正式於 V1 使用 JPA `@Version`，防止 concurrent lifecycle update / duplicate receiving。

## 12.4 State Transition

```text
DRAFT → APPROVED
DRAFT → REJECTED
APPROVED → ORDERED
ORDERED → RECEIVED
```

其餘非法轉換拒絕。

## 12.5 Overdue

動態判斷：

```text
status = ORDERED
AND
currentDate > expectedDeliveryDate
```

## 12.6 Index

```text
UNIQUE(po_number)
INDEX(supplier_id)
INDEX(status)
INDEX(status, expected_delivery_date)
INDEX(created_at)
```

---

# 13. PurchaseOrderItem

```text
PurchaseOrderItem
├── id
├── purchaseOrderId
├── materialId
├── quantity
├── unitPrice
├── unit
├── recommendationId
├── supplierRecommendationResultId
├── recommendedSupplierIdSnapshot
├── recommendedQuantitySnapshot
├── supplierOverrideReason
├── quantityOverrideReason
└── createdAt
```

## 13.1 Rules

- PO Item 由 PurchaseOrder Aggregate 管理。
- 不提供獨立 Item 修改 API。
- 同一 PO 不允許重複 Material。
- `quantity > 0`
- `unitPrice >= 0`
- `unit` 必須等於 Material.baseUnit。
- `unitPrice` / `unit` 為 PO Snapshot。
- Recommendation Reference 放在 Item，不放 Header。

## 13.2 Override Rule

若：

```text
PurchaseOrder.supplierId
!=
recommendedSupplierIdSnapshot
```

則：

```text
supplierOverrideReason REQUIRED
```

若：

```text
quantity
!=
recommendedQuantitySnapshot
```

則：

```text
quantityOverrideReason REQUIRED
```

## 13.3 Constraint / Index

```text
UNIQUE(purchase_order_id, material_id)

INDEX(purchase_order_id)
INDEX(material_id)
INDEX(recommendation_id)
INDEX(supplier_recommendation_result_id)
```

---

# 14. StockTransaction

```text
StockTransaction
├── id
├── materialId
├── type
├── quantity
├── purchaseOrderId
├── reason
├── createdBy
└── createdAt
```

## 14.1 Type

```text
PURCHASE_RECEIVE
MANUAL_ADJUSTMENT
```

## 14.2 Quantity

使用 Signed Decimal：

```text
+200
-5
```

且：

```text
quantity != 0
```

## 14.3 Nullable Rules

### PURCHASE_RECEIVE

```text
purchaseOrderId REQUIRED
reason OPTIONAL
```

### MANUAL_ADJUSTMENT

```text
purchaseOrderId = NULL
reason REQUIRED
```

## 14.4 Rules

- 每個 PO Item 收貨時建立一筆 StockTransaction。
- Manual Adjustment 成功時建立一筆 StockTransaction。
- Inventory Import 不建立一般 StockTransaction。
- 核心 Inventory Audit 不依賴修改 Inventory.quantity 本身。
- `StockTransaction` 的寫入僅由收貨或人工調整 use case 執行；E8 所需的 List / Detail 是唯讀投影，不增加 Entity 寫入入口。Read DTO 附 Material 基本單位，不跨單位加總數量。

## 14.5 Index

```text
INDEX(material_id, created_at)
INDEX(purchase_order_id)
INDEX(type)
```

---

# 15. User

```text
User
├── id
├── username
├── passwordHash
├── displayName
├── role
├── status
├── createdAt
└── updatedAt
```

## 15.1 Role

```text
PRODUCTION_PLANNER
PROCUREMENT_STAFF
PROCUREMENT_MANAGER
WAREHOUSE_STAFF
ADMIN
```

## 15.2 Status

```text
ACTIVE
INACTIVE
```

## 15.3 Rules

- Username Unique。
- User 不 Hard Delete。
- 停用改為 INACTIVE。
- V1 不建立 UserRole / Permission 多表架構。

## 15.4 Index

```text
UNIQUE(username)
INDEX(status)
```

---

# 16. DocumentMetadata

```text
DocumentMetadata
├── id
├── documentType
├── supplierId
├── title
├── version
├── storagePath
├── status
├── uploadedBy
├── uploadedAt
└── updatedAt
```

## 16.1 DocumentType

```text
SUPPLIER_CONTRACT
QUOTATION
PROCUREMENT_SOP
PRODUCT_SPECIFICATION
QUALITY_REQUIREMENT
```

## 16.2 Status

```text
ACTIVE
INACTIVE
```

## 16.3 Rules

- supplierId Nullable。
- Supplier-specific 文件可有 supplierId。
- Procurement SOP 類文件可沒有 supplierId。
- Binary File 可存 S3。
- MySQL 保存 Metadata。
- Embedding / Vector Index 存 Vector Store。

## 16.4 Index

```text
INDEX(document_type)
INDEX(supplier_id)
INDEX(status)
INDEX(uploaded_at)
```

---

# 17. Relationship Overview

```text
Material
│
├── 1:1 Inventory
├── 1:N Demand
├── 1:N SupplierMaterial
├── 1:N PurchaseRecommendation
├── 1:N InventoryImportItem (optional material reference)
├── 1:N PurchaseOrderItem
└── 1:N StockTransaction

Supplier
│
├── 1:N SupplierMaterial
├── 1:N SupplierRecommendationResult
├── 1:N PurchaseRecommendation (recommended supplier, nullable)
├── 1:N PurchaseOrder
└── 1:N DocumentMetadata

InventoryImportBatch
│
└── 1:N InventoryImportItem

PurchaseRecommendation
│
├── N:1 Material
├── N:1 Supplier (recommended supplier, nullable)
└── 1:N SupplierRecommendationResult

PurchaseOrder
│
├── N:1 Supplier
└── 1:N PurchaseOrderItem

PurchaseOrderItem
│
├── N:1 PurchaseRecommendation (nullable)
└── N:1 SupplierRecommendationResult (nullable)

User
│
├── Demand.createdBy
├── InventoryImportBatch.uploadedBy
├── PurchaseRecommendation.createdBy
├── PurchaseOrder.createdBy
├── PurchaseOrder.approvedBy
├── PurchaseOrder.orderedBy
├── PurchaseOrder.receivedBy
├── StockTransaction.createdBy
└── DocumentMetadata.uploadedBy
```

---

# 18. Non-Entity Calculated Concepts

以下概念不建立獨立 Entity / Table。

## 18.1 NetRequirement

```text
Expected Demand
+ Safety Stock
- Current Inventory
- Incoming Inventory
```

結果保存在 Recommendation Snapshot。

---

## 18.2 Shortage

```text
shortage = netRequirement > 0
```

不做獨立 Entity。

---

## 18.3 Overdue

```text
status = ORDERED
AND
currentDate > expectedDeliveryDate
```

動態計算。

不做：

```text
OVERDUE PO Status
```

---

## 18.4 Supplier Score

Score 屬於：

```text
SupplierRecommendationResult
```

不建立獨立 SupplierScore Table。

---

# 19. Domain / Service Responsibility

---

## 19.1 PurchaseOrder Aggregate Responsibility

PurchaseOrder 負責：

```text
approve()
reject()
markOrdered()
receive()
```

並自行驗證合法 State Transition。

PO 不自行查 Repository。

---

## 19.2 ReceivingService

Receiving 涉及跨 Aggregate：

```text
PurchaseOrder
Inventory
StockTransaction
```

因此由 Application / Domain Service 協調。

流程：

```text
Load PO
↓
Validate PO
↓
Update Inventory
↓
Create StockTransaction
↓
PO.receive()
↓
Commit
```

全部在單一 Transaction Boundary。

正式 receiving invariant：

```text
PurchaseOrder.receive(receiver, now)
  only ORDERED -> RECEIVED
  duplicate RECEIVED -> DUPLICATE_RECEIVING
  preserve supplier/items/recommendation and prior lifecycle audit

Inventory.receive(quantity, now)
  quantity > 0
  incremental add, never snapshot replacement
  DECIMAL(19,4) compatible

StockTransaction.purchaseReceive(...)
  type = PURCHASE_RECEIVE
  quantity > 0
  purchaseOrderId required
  reason = null
  actor/time backend-owned
```

`PurchaseOrder` 與 `Inventory` 均保留 `@Version`；`stock_transactions.purchase_order_id` 以 FK 指向 `purchase_orders.id`。

---

## 19.3 PurchaseRecommendationService

Recommendation 涉及：

```text
Demand
Inventory
Incoming PurchaseOrder
SupplierMaterial
Supplier
```

因此由 Service 協調。

流程：

```text
Aggregate Demand
↓
Read Inventory
↓
Calculate Incoming
↓
Calculate Net Requirement
↓
Load Supplier Candidates
↓
Hard Constraint
↓
Supplier-specific Quantity
↓
Ranking
↓
Persist Recommendation Snapshot
```

---

# 20. Snapshot Design

以下資料必須保存 Snapshot。

## 20.1 PurchaseRecommendation

```text
expectedDemand
safetyStock
currentInventory
incomingInventory
netRequirement
recommendedSupplier
recommendedQuantity
recommendedSupplierScore
```

---

## 20.2 SupplierRecommendationResult

```text
unitPriceSnapshot
leadTimeDaysSnapshot
reliabilityScoreSnapshot
qualityScoreSnapshot
recommendedQuantity
totalCost
priceScore
leadTimeScore
reliabilityScore
qualityScore
totalScore
rank
```

---

## 20.3 PurchaseOrderItem

```text
quantity
unitPrice
unit
recommendedSupplierIdSnapshot
recommendedQuantitySnapshot
```

---

# 21. Hard Delete Policy

核心 Business Entity 不 Hard Delete。

| Entity | Delete Strategy |
|---|---|
| Material | INACTIVE |
| Supplier | INACTIVE |
| SupplierMaterial | INACTIVE |
| Demand | CANCELLED |
| Inventory | 不刪 |
| PurchaseRecommendation | 不刪 |
| SupplierRecommendationResult | 跟 Recommendation 保存 |
| PurchaseOrder | 不刪 |
| PurchaseOrderItem | 跟 PO Lifecycle 保存 |
| StockTransaction | 不刪 |
| User | INACTIVE |
| DocumentMetadata | INACTIVE |
| InventoryImportBatch | 不刪 |

DB FK 預設以：

```text
ON DELETE RESTRICT
```

為主要策略。

---

# 22. JPA Cascade 原則

採保守策略。

## PurchaseOrder → PurchaseOrderItem

可使用：

```text
CascadeType.PERSIST
```

是否使用 REMOVE 應非常保守。

V1 因 PO 不 Hard Delete，可不依賴 REMOVE。

---

## PurchaseRecommendation → SupplierRecommendationResult

可 Cascade Persist。

因 Result 完全屬於 Recommendation Snapshot。

---

## Supplier → SupplierMaterial

不使用 Cascade Remove。

---

## Material → Inventory

由 Service 顯式建立，不依賴 Cascade Magic。

---

# 23. Audit Field Strategy

## Master / Mutable Entity

通常使用：

```text
createdAt
updatedAt
```

例如：

```text
Material
Supplier
SupplierMaterial
Inventory
Demand
User
DocumentMetadata
```

---

## Transaction Entity

重視：

```text
createdBy
createdAt
```

及 Lifecycle Audit：

```text
approvedBy / approvedAt
orderedBy / orderedAt
receivedBy / receivedAt
```

避免只使用單一：

```text
updatedBy
```

模糊真正業務事件。

---

# 24. Decimal / Money / Quantity

所有：

```text
quantity
unitPrice
totalCost
safetyStock
score
```

Java 使用：

```text
BigDecimal
```

不使用：

```text
double
float
```

---

# 25. Date / Time

純日期：

```text
requiredDate
expectedDeliveryDate
```

使用：

```text
LocalDate
```

事件時間：

```text
createdAt
updatedAt
approvedAt
orderedAt
receivedAt
uploadedAt
```

建議後端以：

```text
Instant / UTC
```

儲存。

API / UI 顯示時轉換使用者時區。

---

# 26. Enum List

## MaterialStatus

```text
ACTIVE
INACTIVE
```

## SupplierStatus

```text
ACTIVE
INACTIVE
```

## SupplierMaterialStatus

```text
ACTIVE
INACTIVE
```

## DemandStatus

```text
ACTIVE
CANCELLED
```

## PurchaseOrderStatus

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
```

## StockTransactionType

```text
PURCHASE_RECEIVE
MANUAL_ADJUSTMENT
```

## UserRole

```text
PRODUCTION_PLANNER
PROCUREMENT_STAFF
PROCUREMENT_MANAGER
WAREHOUSE_STAFF
ADMIN
```

## UserStatus

```text
ACTIVE
INACTIVE
```

## InventoryImportStatus

```text
UPLOADED
VALIDATED
VALIDATION_FAILED
IMPORTED
IMPORT_FAILED
```

## DocumentType

```text
SUPPLIER_CONTRACT
QUOTATION
PROCUREMENT_SOP
PRODUCT_SPECIFICATION
QUALITY_REQUIREMENT
```

## RecommendationOutcome

```text
PURCHASE_NOT_REQUIRED
SUPPLIER_RECOMMENDED
NO_QUALIFIED_SUPPLIER
```

## DocumentStatus

```text
PROCESSING
READY
FAILED
INACTIVE
```

## DisqualificationReason

```text
SUPPLIER_INACTIVE
SUPPLIER_MATERIAL_INACTIVE
DELIVERY_DATE_NOT_FEASIBLE
```

## BaseUnit

```text
G
KG
ML
L
PCS
```

## Currency

V1：

```text
TWD
```

---

# 27. Database Constraints

以下 Constraint 必須由 DB 保證。

## Unique

```text
Material.code
Supplier.code
User.username
PurchaseOrder.poNumber

SupplierMaterial(supplier_id, material_id)

Inventory.material_id

PurchaseOrderItem(purchase_order_id, material_id)
```

---

## Foreign Keys

所有 Domain 關聯需使用正式 FK。

例如：

```text
material_id
supplier_id
user_id
purchase_order_id
recommendation_id
supplier_recommendation_result_id
```

---

## NOT NULL

Domain 上必填欄位，DB 也必須 NOT NULL。

不可只依賴 DTO `@NotNull`。

---

## CHECK Candidate

適合 DB CHECK：

```text
quantity >= 0
safety_stock >= 0
unit_price >= 0
lead_time_days >= 0
reliability_score BETWEEN 0 AND 100
quality_score BETWEEN 0 AND 100
```

Business Lifecycle Rule 不放 DB CHECK。

---

# 28. Application / Domain Validation

Application / Domain 負責：

- Material 是否 ACTIVE
- Supplier 是否 ACTIVE
- SupplierMaterial 是否 ACTIVE
- Supplier 是否供應 Material
- Demand 日期是否合法
- PO 狀態是否合法
- Override Reason 是否必填
- Reject Reason 是否必填
- Expected Delivery Date 是否存在
- Inventory 是否會變負數
- Receiving 是否重複
- Supplier Hard Constraint
- Approved PO 是否嘗試修改核心欄位

---

# 29. DTO Validation

Controller / Request DTO 負責基本格式驗證：

```text
@NotBlank
@NotNull
@Positive
@PositiveOrZero
@Size
```

例如：

```text
quantity > 0
code 不空
name 不空
requiredDate 格式正確
```

DTO Validation 不負責：

```text
Supplier 是否 ACTIVE
PO 是否可 RECEIVE
Material 是否可用
```

這些是 Business Validation。

---

# 30. Recommended DB Index Summary

## Material

```text
UNIQUE(code)
INDEX(status)
```

## Supplier

```text
UNIQUE(code)
INDEX(status)
```

## SupplierMaterial

```text
UNIQUE(supplier_id, material_id)
INDEX(material_id, status)
INDEX(supplier_id, status)
```

## Inventory

```text
UNIQUE(material_id)
```

## Demand

```text
INDEX(material_id, status, required_date)
INDEX(required_date)
```

## PurchaseRecommendation

```text
INDEX(material_id, created_at)
INDEX(period_end)
```

## SupplierRecommendationResult

```text
INDEX(recommendation_id)
INDEX(recommendation_id, rank)
INDEX(supplier_id)
```

## PurchaseOrder

```text
UNIQUE(po_number)
INDEX(supplier_id)
INDEX(status)
INDEX(status, expected_delivery_date)
INDEX(created_at)
```

## PurchaseOrderItem

```text
UNIQUE(purchase_order_id, material_id)
INDEX(purchase_order_id)
INDEX(material_id)
INDEX(recommendation_id)
INDEX(supplier_recommendation_result_id)
```

## StockTransaction

```text
INDEX(material_id, created_at)
INDEX(purchase_order_id)
INDEX(type)
```

## InventoryImportBatch

```text
INDEX(uploaded_at)
INDEX(status)
INDEX(uploaded_by)
```

## InventoryImportItem

```text
INDEX(batch_id, row_number)
INDEX(material_id)
```

## User

```text
UNIQUE(username)
INDEX(status)
```

## DocumentMetadata

```text
INDEX(document_type)
INDEX(supplier_id)
INDEX(status)
INDEX(uploaded_at)
```

---

# 31. Key Domain Decisions

正式鎖定：

1. SupplierMaterial 是正式 Entity，不是 Join Table。
2. Inventory 獨立於 Material。
3. Material 建立時建立 quantity = 0 的 Inventory。
4. Material.code 不可修改。
5. Material 有歷史交易後 baseUnit 不可修改。
6. Recommendation 必須持久化為 Snapshot。
7. SupplierRecommendationResult 保存所有 Candidate Ranking / Disqualification Snapshot。
8. PurchaseOrder 是 Aggregate Root。
9. PurchaseOrderItem 由 PurchaseOrder 管理。
10. Recommendation Reference 放在 PurchaseOrderItem，不放 PO Header。
11. Supplier / Quantity Override 以 PO Item 為追蹤單位。
12. Shortage / NetRequirement / Overdue 不建立獨立 Entity。
13. Receiving 由 Service 協調 PO / Inventory / StockTransaction。
14. Recommendation 由 Service 協調 Demand / Inventory / PO / SupplierMaterial。
15. 核心 Business Entity 不 Hard Delete。
16. 核心 Unique Rule 必須由 DB Constraint 保證。
17. Snapshot 不依賴當前 Master Data 動態回查。
18. Money / Quantity / Score 使用 BigDecimal。
19. Lifecycle Rule 放 Domain / Service，不塞 DB Constraint。
20. DTO Validation 與 Business Validation 分層處理。
21. InventoryImportItem 正式納入 V1，以支援 Validate / Preview / Confirm。
22. Inventory 與 PurchaseOrder 正式於 V1 使用 `@Version`。
23. PurchaseRecommendation 使用 `RecommendationOutcome` 明確表示結果。
24. DocumentStatus 使用 `PROCESSING / READY / FAILED / INACTIVE` 表示 RAG ingestion lifecycle。

---

# 32. 下一階段

下一階段：

```text
Domain Model
↓
ERD
↓
API Design
↓
Package Architecture
↓
Transaction Boundary
↓
Error / Validation Strategy
↓
Testing Strategy
```

下一份建議文件：

```text
erd.md
```

ERD 應直接依照本文件建立，而不是重新發明 Domain。

## V2-102 資料模型補充

StockTransaction 除 `created_by_user_id_snapshot` 外，V24 亦回填並保存 `created_by_username_snapshot` 與 `created_by_display_name_snapshot`；新建人工調整及收貨交易會在建立時一併擷取三項操作者快照。查詢 API 的 `createdBy` 與 `createdByDisplayName` 讀取快照，帳號改名或刪除不改變歷史稽核資料。V23 為已套用遷移，保持其歷史 SQL 與 checksum 不變；V23 後加入的 `idx_users_manager_status_id` 由 V24 建立。

`purchase_orders.assigned_to` 是可空的目前負責人 FK，刪除帳號時設為 `NULL`；與建立者及各事件操作者分離。建立、核准、駁回、下單、收貨的 `*_by_user_id_snapshot`、`*_username_snapshot`、`*_display_name_snapshot` 是事件發生當下寫入的非 FK 快照；事件時間仍由原 `created_at`、`approved_at`、`rejected_at`、`ordered_at`、`received_at` 保存。V24 保留 Demand、Recommendation、Inventory Import、StockTransaction、DocumentMetadata 與 AI 草稿資料列，並將歷史使用者 FK 置空、以非 FK ID snapshot 留存原關聯識別。

## V2-201 PO Draft 編輯與歷史來源快照

PO 與 PO item 原有正式 Entity/Schema 已支援直接建立 `DRAFT`、nullable `expected_delivery_date`、非空 item 集合、`quantity > 0`、`unit_price >= 0`、資料庫 `DECIMAL(19,4)`、PO `@Version` 與單一交易邊界；本 Issue 不新增資料欄位或 Migration。Draft Edit 與核准以同一 PO 列鎖序列化狀態檢查，並保留 `@Version`。Draft Edit 只能變更商業欄位，既有 item 的 `recommendation_id`、`supplier_recommendation_result_id`、推薦供應商/數量快照及覆寫原因保留為不可變歷史資料。

建立或更換供應商/品項仍要求 ACTIVE Supplier、ACTIVE Material 與 ACTIVE SupplierMaterial 關聯。SupplierMaterial 是現行供應主檔的有效供應關係，V2-201 沒有核准移除此 Domain 驗證；手動草稿可輸入暫估單價，不使用該主檔價格作為 PO 單價。

## V2-203：兩種獨立交期

`PurchaseOrder.expectedDeliveryDate` 是公司期望到貨日：DRAFT 可設定或清除，核准後保留不變。`PurchaseOrder.supplierPromisedDeliveryDate` 是供應商目前承諾日，nullable 且獨立持久化；`NULL` 表示目前沒有已知承諾，不推導為期望日期。只有 APPROVED／ORDERED 可以透過專用業務命令更新；更新接受明確的 null 以清除承諾，並更新 PO `updatedAt`。

逾期仍依 `ORDERED && currentDate.isAfter(expectedDeliveryDate)` 動態計算。`ClockConfiguration` 使用 `Asia/Taipei`，因此 UTC 午夜前後按台灣日曆日期比較；兩種交期都是 `LocalDate`，不轉為 Instant。承諾日變更不影響正式狀態、期望日或收貨交易。

## V2-202 PO 終止稽核與 ORDERED 進度保護

V2 `PurchaseOrderStatus` 增加終態 `CANCELLED`。`cancelReason`、`cancelledAt`、`cancelledBy` 與非 FK 的 user ID/username/displayName 快照同交易寫入；刪除操作人時 FK 可置空，快照仍供歷史明細讀取。取消不刪除/重建 PO、供應商、品項或交易資料。

V25 新增 nullable `ordered_progress`：新標記下單寫入 `FOLLOW_UP`；明確進入到貨驗收後寫入 `RECEIVING_INSPECTION`；舊 ORDERED 記錄回填 `LEGACY_UNKNOWN`，因 V1 沒有保存能判斷第 5/6 欄的事件。資料庫 CHECK 保證該欄只有 status=ORDERED 時有值；其他狀態必須為 NULL。只有 APPROVED 或 ORDERED + FOLLOW_UP 可取消；RECEIVING_INSPECTION/LEGACY_UNKNOWN 皆拒絕。完整八階段及狀態映射仍由 #142 負責。

## V2-204 收貨驗收與目前付款確認

V27 在 `purchase_orders` 增加三個預設 `FALSE` 的目前驗收事實欄位（整筆到貨、全品項數量確認、全品質合格）、目前 `inspection_note`、最後驗收者/時間與非 FK 操作者快照。驗收紀錄只可在 `ORDERED + RECEIVING_INSPECTION` 更新；短收、未到貨或品質異常可保留任一旗標為 false 及備註，但不得入庫。只有三旗標全真且有驗收操作者/時間後才允許整筆收貨。收貨後保留最後驗收資料供明細查詢；不新增逐次驗收歷史表。

付款目前狀態由 nullable `payment_confirmed_at` 是否存在推導。確認時保存付款確認者 FK、使用者 ID/username/displayName 快照與 Instant；撤銷時清除這些目前狀態欄位，不保留額外付款歷史。V27 的資料庫 CHECK 禁止非 RECEIVED PO 被標記已付款，且付款時間與操作者快照必須同時完整或同時為空。既有 RECEIVED PO 保持未確認付款；不以舊資料推測付款狀態。付款狀態不改 `PurchaseOrderStatus.RECEIVED`、Inventory 或 StockTransaction。

第 5/6 欄仍由既有 `OrderedProgress` 最小契約表示；收貨後從正式 `RECEIVED` 加上付款時間推導目前位置為第 7/8 欄。此 Issue 不加入完整 Stage 欄位、遷移或一般轉換引擎，留給 #142。
