# SupplyPilot — ERD Specification

> **文件目的**：將 `domain-model.md` 的 V1 Domain Model 轉換為正式資料庫關聯規格，定義資料表、主鍵、外鍵、唯一限制、Nullable、Index、Cardinality 與資料完整性規則。  
> **文件定位**：V1 Database / ERD Baseline  
> **視覺化文件**：`erd.html`  
> **上游文件**：`domain-model.md`、`functional-requirements.md`

---

# 1. ERD Review 結論

目前 ERD 共 14 個核心 Entity，與 `domain-model.md` 的 Entity List 一致：

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

檢查項目包括：

- Entity 數量與名稱
- PK / FK / Unique Constraint
- Nullable
- 1:1 / 1:N Cardinality
- Recommendation / PO Snapshot
- PurchaseOrder Aggregate
- Index
- Hard Delete Policy
- Lifecycle 欄位
- Audit FK
- V1 Domain 邊界

## 1.1 Review 時發現並修正的關聯遺漏

`PurchaseRecommendation` 具有：

```text
recommended_supplier_id
```

其欄位定義為 Nullable FK，同時代表當次 Recommendation 的推薦供應商 Snapshot Reference。

原 `erd.html` 欄位有 FK，但沒有畫出：

```text
Supplier
1
↓
N
PurchaseRecommendation
```

此次已補上該關聯。

> 注意：`domain-model.md` 的 `PurchaseRecommendation` 欄位定義包含 `recommendedSupplierId`，但其 Relationship Overview 未列出此關聯。ERD 依欄位 FK 定義補足；後續若更新 `domain-model.md`，建議同步補上此關聯。

除此之外，未發現需要改變既定 V1 Domain 結構的 ERD 錯誤。

---

# 2. Cardinality Summary

```text
Material 1 ── 1 Inventory

Material 1 ── N Demand
Material 1 ── N SupplierMaterial
Material 1 ── N InventoryImportItem (optional material reference)
Material 1 ── N PurchaseRecommendation
Material 1 ── N PurchaseOrderItem
Material 1 ── N StockTransaction

Supplier 1 ── N SupplierMaterial
Supplier 1 ── N SupplierRecommendationResult
Supplier 1 ── N PurchaseRecommendation (recommended supplier, optional)
Supplier 1 ── N PurchaseOrder
Supplier 1 ── N DocumentMetadata (optional on document)

PurchaseRecommendation 1 ── N SupplierRecommendationResult
PurchaseRecommendation 1 ── N PurchaseOrderItem (optional reference)

SupplierRecommendationResult 1 ── N PurchaseOrderItem (optional reference)

PurchaseOrder 1 ── N PurchaseOrderItem
PurchaseOrder 1 ── N StockTransaction

InventoryImportBatch 1 ── N InventoryImportItem

User 1 ── N Demand
User 1 ── N InventoryImportBatch
User 1 ── N PurchaseRecommendation
User 1 ── N PurchaseOrder audit references
User 1 ── N StockTransaction
User 1 ── N DocumentMetadata
```

---

# 3. material

## 3.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| code | VARCHAR | No | UNIQUE |
| name | VARCHAR | No | |
| base_unit | ENUM / VARCHAR | No | BaseUnit |
| safety_stock | DECIMAL | No | >= 0 |
| status | ENUM / VARCHAR | No | ACTIVE / INACTIVE |
| created_at | TIMESTAMP | No | |
| updated_at | TIMESTAMP | No | |

## 3.2 Rules

- `code` 不可修改。
- 有歷史交易後 `base_unit` 不可修改。
- 不 Hard Delete，以 `INACTIVE` 停用。
- 建立 Material 時同步建立 `Inventory(quantity=0)`。

## 3.3 Index

```text
PK(id)
UNIQUE(code)
INDEX(status)
```

---

# 4. supplier

## 4.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| code | VARCHAR | No | UNIQUE |
| name | VARCHAR | No | |
| country | VARCHAR | Yes | |
| reliability_score | DECIMAL | No | 0~100 |
| quality_score | DECIMAL | No | 0~100 |
| status | ENUM / VARCHAR | No | ACTIVE / INACTIVE |
| created_at | TIMESTAMP | No | |
| updated_at | TIMESTAMP | No | |

## 4.2 Rules

- Reliability / Quality V1 為手動維護 Master Data。
- 不 Hard Delete。

## 4.3 Index

```text
PK(id)
UNIQUE(code)
INDEX(status)
```

---

# 5. supplier_material

SupplierMaterial 是正式 Domain Entity，不是單純 Join Table。

## 5.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| supplier_id | BIGINT | No | FK → supplier.id |
| material_id | BIGINT | No | FK → material.id |
| unit_price | DECIMAL | No | >= 0 |
| moq | DECIMAL | No | > 0 |
| package_multiple | DECIMAL | Yes | > 0 |
| lead_time_days | INT | No | >= 0 |
| currency | ENUM / VARCHAR | No | V1 = TWD |
| status | ENUM / VARCHAR | No | ACTIVE / INACTIVE |
| created_at | TIMESTAMP | No | |
| updated_at | TIMESTAMP | No | |

## 5.2 Unique

```text
UNIQUE(supplier_id, material_id)
```

同一供應商對同一原料 V1 只有一筆目前有效供貨條件。

## 5.3 Index

```text
INDEX(material_id, status)
INDEX(supplier_id, status)
```

---

# 6. inventory

## 6.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| material_id | BIGINT | No | FK + UNIQUE |
| quantity | DECIMAL | No | >= 0 |
| updated_at | TIMESTAMP | No | |
| version | BIGINT | No | V1 `@Version` |

## 6.2 Cardinality

```text
Material 1 : 1 Inventory
```

## 6.3 Rules

- 不允許負庫存。
- ERP Import 使用 Snapshot Replace。
- `version` 正式於 V1 使用 JPA `@Version`，避免 Lost Update。

---

# 7. demand

## 7.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| material_id | BIGINT | No | FK → material.id |
| quantity | DECIMAL | No | > 0 |
| required_date | DATE | No | |
| status | ENUM / VARCHAR | No | ACTIVE / CANCELLED |
| note | VARCHAR / TEXT | Yes | |
| created_by | BIGINT | No | FK → user.id |
| created_at | TIMESTAMP | No | |
| updated_at | TIMESTAMP | No | |

## 7.2 Index

```text
INDEX(material_id, status, required_date)
INDEX(required_date)
```

## 7.3 Rules

- Demand 不 Hard Delete。
- 只有 ACTIVE Demand 參與 Calculation Period Aggregation。

---

# 8. inventory_import_batch

## 8.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| filename | VARCHAR | No | |
| uploaded_by | BIGINT | No | FK → user.id |
| uploaded_at | TIMESTAMP | No | |
| row_count | INT | No | >= 0 |
| status | ENUM / VARCHAR | No | Import lifecycle |
| error_summary | TEXT | Yes | |

## 8.2 Lifecycle

```text
UPLOADED
→ VALIDATED
→ IMPORTED
```

Failure：

```text
VALIDATION_FAILED
IMPORT_FAILED
```

## 8.3 Index

```text
INDEX(uploaded_at)
INDEX(status)
INDEX(uploaded_by)
```

## 8.4 InventoryImportItem Relationship

V1 正式建立：

```text
InventoryImportBatch 1 : N InventoryImportItem
```

每一列 Validate / Preview 結果會持久化，Confirm 讀取相同 Item，避免跨 HTTP Request 遺失已驗證資料。

---

# 8A. inventory_import_item

## 8A.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| batch_id | BIGINT | No | FK → inventory_import_batch.id |
| row_number | INT | No | > 0 |
| material_id | BIGINT | Yes | FK → material.id |
| material_code_snapshot | VARCHAR | No | Snapshot |
| quantity | DECIMAL | Yes | Parsed value |
| unit | ENUM / VARCHAR | Yes | Parsed value |
| valid | BOOLEAN | No | |
| error_code | VARCHAR | Yes | Row validation result |
| error_message | VARCHAR / TEXT | Yes | Row validation result |

## 8A.2 Index

```text
INDEX(batch_id, row_number)
INDEX(material_id)
```

## 8A.3 Rules

- Row-level validation error 是 preview result，不是 request-level exception。
- `material_id` 可為 NULL，以保存不存在料號的驗證結果。
- Confirm 僅處理 `VALIDATED` Batch。
- ERP Import 為 Snapshot Replace，不建立 StockTransaction。

---

# 9. purchase_recommendation

PurchaseRecommendation 為 Calculation Snapshot Root。

## 9.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| material_id | BIGINT | No | FK → material.id |
| period_start | DATE | No | |
| period_end | DATE | No | |
| expected_demand | DECIMAL | No | Snapshot |
| safety_stock | DECIMAL | No | Snapshot |
| current_inventory | DECIMAL | No | Snapshot |
| incoming_inventory | DECIMAL | No | Snapshot |
| net_requirement | DECIMAL | No | Snapshot |
| outcome | ENUM / VARCHAR | No | RecommendationOutcome |
| recommended_supplier_id | BIGINT | Yes | FK → supplier.id + Snapshot Ref |
| recommended_quantity | DECIMAL | Yes | Snapshot |
| recommended_supplier_score | DECIMAL | Yes | Snapshot |
| created_by | BIGINT | No | FK → user.id |
| created_at | TIMESTAMP | No | |

## 9.2 Rules

`outcome` 必須為：

```text
PURCHASE_NOT_REQUIRED
SUPPLIER_RECOMMENDED
NO_QUALIFIED_SUPPLIER
```

以下可以為 NULL：

```text
recommended_supplier_id
recommended_quantity
recommended_supplier_score
```

Recalculation 建立新 Snapshot，不覆蓋舊 Recommendation。

## 9.3 Index

```text
INDEX(material_id, created_at)
INDEX(period_end)
```

---

# 10. supplier_recommendation_result

## 10.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| recommendation_id | BIGINT | No | FK → purchase_recommendation.id |
| supplier_id | BIGINT | No | FK → supplier.id |
| qualified | BOOLEAN | No | |
| disqualified_reason | ENUM / VARCHAR | Yes | Required when unqualified |
| recommended_quantity | DECIMAL | Yes | Snapshot |
| unit_price_snapshot | DECIMAL | Yes | Snapshot |
| total_cost | DECIMAL | Yes | Snapshot |
| lead_time_days_snapshot | INT | Yes | Snapshot |
| reliability_score_snapshot | DECIMAL | Yes | Snapshot |
| quality_score_snapshot | DECIMAL | Yes | Snapshot |
| price_score | DECIMAL | Yes | Ranking component |
| lead_time_score | DECIMAL | Yes | Ranking component |
| reliability_score | DECIMAL | Yes | Ranking component |
| quality_score | DECIMAL | Yes | Ranking component |
| total_score | DECIMAL | Yes | |
| rank | INT | Yes | |

## 10.2 Cardinality

```text
PurchaseRecommendation 1 : N SupplierRecommendationResult
Supplier 1 : N SupplierRecommendationResult
```

## 10.3 Index

```text
INDEX(recommendation_id)
INDEX(recommendation_id, rank)
INDEX(supplier_id)
```

---

# 11. purchase_order

PurchaseOrder 為 Aggregate Root。

## 11.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| po_number | VARCHAR | No | UNIQUE |
| supplier_id | BIGINT | No | FK → supplier.id |
| status | ENUM / VARCHAR | No | PO lifecycle |
| stage | VARCHAR(32) | Yes | V2 current Kanban position; NULL only for terminal or unresolved legacy records |
| expected_delivery_date | DATE | Yes | Required before ORDERED |
| reject_reason | TEXT | Yes | Required for REJECTED |
| created_by | BIGINT | No | FK → user.id |
| created_at | TIMESTAMP | No | |
| approved_by | BIGINT | Yes | FK → user.id |
| approved_at | TIMESTAMP | Yes | |
| ordered_by | BIGINT | Yes | FK → user.id |
| ordered_at | TIMESTAMP | Yes | |
| received_by | BIGINT | Yes | FK → user.id |
| received_at | TIMESTAMP | Yes | |
| updated_at | TIMESTAMP | No | |
| version | BIGINT | No | V1 `@Version` |

## 11.2 Status

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
CANCELLED
```

V2 `stage` is separate from `status`: stages 1–3 are DRAFT, 4 is APPROVED, 5–6 are ORDERED, and 7–8 are RECEIVED. V28 constrains compatible pairs and indexes `stage`; REJECTED/CANCELLED have no active stage. An ORDERED row with `ordered_progress=LEGACY_UNKNOWN` keeps `stage=NULL` until explicitly resolved.

## 11.3 Allowed Transition

```text
DRAFT → APPROVED
DRAFT → REJECTED
APPROVED → ORDERED
ORDERED → RECEIVED
```

## 11.4 Derived Overdue

```text
status = ORDERED
AND currentDate > expected_delivery_date
```

`OVERDUE` 不存入 `status`。

## 11.5 Index

```text
UNIQUE(po_number)
INDEX(supplier_id)
INDEX(status)
INDEX(status, expected_delivery_date)
INDEX(created_at)
```

---

# 12. purchase_order_item

## 12.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| purchase_order_id | BIGINT | No | FK → purchase_order.id |
| material_id | BIGINT | No | FK → material.id |
| quantity | DECIMAL | No | > 0 |
| unit_price | DECIMAL | No | Snapshot |
| unit | ENUM / VARCHAR | No | Snapshot |
| recommendation_id | BIGINT | Yes | FK → purchase_recommendation.id |
| supplier_recommendation_result_id | BIGINT | Yes | FK → supplier_recommendation_result.id |
| recommended_supplier_id_snapshot | BIGINT | Yes | Snapshot scalar |
| recommended_quantity_snapshot | DECIMAL | Yes | Snapshot |
| supplier_override_reason | TEXT | Yes | Conditional required |
| quantity_override_reason | TEXT | Yes | Conditional required |
| created_at | TIMESTAMP | No | |

## 12.2 Unique

```text
UNIQUE(purchase_order_id, material_id)
```

## 12.3 Override

若：

```text
purchase_order.supplier_id != recommended_supplier_id_snapshot
```

則：

```text
supplier_override_reason REQUIRED
```

若：

```text
quantity != recommended_quantity_snapshot
```

則：

```text
quantity_override_reason REQUIRED
```

## 12.4 Index

```text
INDEX(purchase_order_id)
INDEX(material_id)
INDEX(recommendation_id)
INDEX(supplier_recommendation_result_id)
```

---

# 13. stock_transaction

## 13.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| material_id | BIGINT | No | FK → material.id |
| type | ENUM / VARCHAR | No | |
| quantity | DECIMAL | No | signed, != 0 |
| purchase_order_id | BIGINT | Yes | FK → purchase_order.id |
| reason | TEXT | Yes | Conditional required |
| created_by | BIGINT | No | FK → user.id |
| created_at | TIMESTAMP | No | |

## 13.2 Type Rules

### PURCHASE_RECEIVE

```text
purchase_order_id REQUIRED
reason OPTIONAL
```

### MANUAL_ADJUSTMENT

```text
purchase_order_id NULL
reason REQUIRED
```

## 13.3 Index

```text
INDEX(material_id, created_at)
INDEX(purchase_order_id)
INDEX(type)
```

---

# 14. user

## 14.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| username | VARCHAR | No | UNIQUE |
| password_hash | VARCHAR | No | |
| display_name | VARCHAR | No | |
| role | ENUM / VARCHAR | No | |
| status | ENUM / VARCHAR | No | ACTIVE / INACTIVE |
| created_at | TIMESTAMP | No | |
| updated_at | TIMESTAMP | No | |

## 14.2 Index

```text
UNIQUE(username)
INDEX(status)
```

---

# 15. document_metadata

## 15.1 Columns

| Column | Type Concept | Null | Key / Constraint |
|---|---|---:|---|
| id | BIGINT | No | PK |
| document_type | ENUM / VARCHAR | No | |
| supplier_id | BIGINT | Yes | FK → supplier.id |
| title | VARCHAR | No | |
| version | VARCHAR | No | |
| storage_path | VARCHAR | No | |
| status | ENUM / VARCHAR | No | PROCESSING / READY / FAILED / INACTIVE |
| uploaded_by | BIGINT | No | FK → user.id |
| uploaded_at | TIMESTAMP | No | |
| updated_at | TIMESTAMP | No | |

## 15.2 Storage Responsibility

```text
MySQL
→ Metadata

S3
→ Original File

Vector Store
→ Embedding / Retrieval Index
```

## 15.3 Index

```text
INDEX(document_type)
INDEX(supplier_id)
INDEX(status)
INDEX(uploaded_at)
```

---

# 16. Foreign Key Delete Policy

核心 Business Data 採保守策略：

```text
ON DELETE RESTRICT
```

為主要原則。

不依賴 DB Cascade Delete 清除歷史交易資料。

| Parent | Child | Delete Policy |
|---|---|---|
| Material | Inventory | RESTRICT |
| Material | Demand | RESTRICT |
| Material | SupplierMaterial | RESTRICT |
| Material | PurchaseRecommendation | RESTRICT |
| Material | PurchaseOrderItem | RESTRICT |
| Material | StockTransaction | RESTRICT |
| Supplier | SupplierMaterial | RESTRICT |
| Supplier | Recommendation Result | RESTRICT |
| Supplier | PurchaseRecommendation | RESTRICT |
| Supplier | PurchaseOrder | RESTRICT |
| Supplier | DocumentMetadata | RESTRICT |
| PurchaseRecommendation | Recommendation Result | RESTRICT in DB |
| PurchaseRecommendation | PurchaseOrderItem | RESTRICT |
| PurchaseOrder | PurchaseOrderItem | RESTRICT |
| PurchaseOrder | StockTransaction | RESTRICT |
| User | Audit References | RESTRICT |

Application lifecycle 使用：

```text
INACTIVE
CANCELLED
PO Status
```

取代 Hard Delete。

---

# 17. DB Constraint vs Business Validation

## 17.1 DB 必須保證

### Unique

```text
material.code
supplier.code
user.username
purchase_order.po_number

supplier_material(supplier_id, material_id)
inventory.material_id
purchase_order_item(purchase_order_id, material_id)
```

### Foreign Key

所有正式 Domain Relationship 使用 FK。

### NOT NULL

Domain 必填資料同時設 DB NOT NULL。

### CHECK Candidate

```text
quantity >= 0
safety_stock >= 0
unit_price >= 0
lead_time_days >= 0
reliability_score BETWEEN 0 AND 100
quality_score BETWEEN 0 AND 100
```

---

## 17.2 Application / Domain 負責

```text
Material ACTIVE
Supplier ACTIVE
SupplierMaterial ACTIVE
Supplier 是否供應 Material
Demand 日期是否合法
PO transition 合法性
Override Reason 條件
Reject Reason 條件
Expected Delivery Date 條件
Inventory 不得變負數
Duplicate Receiving
Supplier Hard Constraint
APPROVED PO immutable
```

---

# 18. Snapshot Policy

Snapshot 是 SupplyPilot Decision Traceability 的核心。

## PurchaseRecommendation Snapshot

保存：

```text
expected_demand
safety_stock
current_inventory
incoming_inventory
net_requirement
recommended_supplier_id
recommended_quantity
recommended_supplier_score
```

## SupplierRecommendationResult Snapshot

保存：

```text
unit_price_snapshot
lead_time_days_snapshot
reliability_score_snapshot
quality_score_snapshot
recommended_quantity
total_cost
score breakdown
rank
```

## PurchaseOrderItem Snapshot

保存：

```text
quantity
unit_price
unit
recommended_supplier_id_snapshot
recommended_quantity_snapshot
```

Master Data 後續變動不得改寫上述歷史結果。

---

# 19. Non-table Concepts

以下不建立 Table：

## NetRequirement

計算結果，保存在 Recommendation Snapshot。

## Shortage

```text
netRequirement > 0
```

## Overdue

```text
PO.status = ORDERED
AND today > expectedDeliveryDate
```

## Supplier Score

屬於某次 `SupplierRecommendationResult`。

---

# 20. Transaction Boundary Impact

ERD 只描述持久化結構；以下跨 Entity 更新由 Service Transaction 控制。

## Receiving

```text
PurchaseOrder
+
Inventory
+
StockTransaction
```

必須同一 Transaction：

```text
Validate PO
→ Update Inventory
→ Insert StockTransaction
→ PO.receive()
→ COMMIT
```

任一步失敗：

```text
ROLLBACK
```

## Recommendation

由 Recommendation Service 讀取：

```text
Demand
Inventory
ORDERED PurchaseOrder
SupplierMaterial
Supplier
```

計算後寫入：

```text
PurchaseRecommendation
SupplierRecommendationResult
```

---

# 21. V1 ERD Known Simplifications

這些不是 ERD Error，而是刻意的 V1 Boundary：

1. 單一 Warehouse，因此 Inventory 為 Material 1:1。
2. V1 不建立 `Warehouse` Entity。
3. V1 已建立 `InventoryImportItem`，支援 Validate / Preview / Confirm。
4. V1 不建立 Quotation History。
5. V1 單一 Currency = TWD。
6. V1 不做 Unit Conversion。
7. V1 Full Receiving Only，因此沒有 Receiving Header / Receiving Item。
8. V1 User 單一 Role Enum，不做 Role / Permission Tables。
9. V1 不建立 Cancellation History。
10. Reliability / Quality 為 Supplier Master Data，不從交易歷史自動推導。

---

# 22. ERD Baseline 結論

目前 V1 ERD 可以支撐完整核心流程：

```text
Material / Supplier Master
↓
Demand
↓
Inventory
↓
Purchase Recommendation
↓
Supplier Ranking
↓
Purchase Order
↓
Approval
↓
Ordering
↓
Receiving
↓
Inventory Update
↓
StockTransaction Audit
```

同時保留：

```text
Recommendation Snapshot
Human Override Traceability
PO Lifecycle Audit
Inventory Audit
RAG Document Metadata
```

因此目前 ERD 足以進入下一階段：

```text
API Design
```

在沒有新的 Functional Requirement 前，不需要再增加資料表。
