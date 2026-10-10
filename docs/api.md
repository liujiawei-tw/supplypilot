# SupplyPilot — API Design

> **文件目的**：定義 SupplyPilot V1 的 REST API、Request / Response DTO、Business Action、Validation、Error Code、HTTP Status、Security Boundary 與 API Acceptance Condition。  
> **文件定位**：V1 API Baseline  
> **上游文件**：
> - `functional-requirements.md`
> - `domain-model.md`
> - `erd.md`
> **API Version**：`/api/v1`

---

# 1. API Design Principles

SupplyPilot V1 API 採以下原則：

1. Entity 不直接作為 Request DTO。
2. Entity 不直接作為 Response DTO。
3. Controller 只處理 HTTP / DTO Validation。
4. Service 負責 Business Logic。
5. Domain Entity 負責自身 Lifecycle / Invariant。
6. Repository 不直接暴露給 Controller。
7. Business Status Transition 使用 Action Endpoint。
8. Client 不可直接修改 Audit 欄位。
9. Client 不可直接傳入 Backend Snapshot 欄位。
10. AI Tool 與 REST API 共用 Application Service。
11. Success Response 不強制包統一 `success/data/message` Envelope。
12. Error Response 使用統一格式。

標準流程：

```text
HTTP Request
↓
Controller
↓
Request DTO Validation
↓
Application Service
↓
Domain / Repository
↓
Response DTO
↓
HTTP Response
```

---

# 2. API Base Path

所有 V1 API：

```text
/api/v1
```

未來有 Breaking Change 時：

```text
/api/v2
```

V1 不使用 Header-based API Versioning。

---

# 3. Endpoint Naming Convention

使用：

```text
plural nouns
kebab-case
```

例如：

```text
/materials
/suppliers
/supplier-materials
/purchase-orders
/stock-transactions
/inventory-imports
```

Business Action 使用：

```text
POST /resource/{id}/action
```

例如：

```text
POST /demands/{id}/cancel
POST /purchase-orders/{id}/approve
POST /purchase-orders/{id}/reject
POST /purchase-orders/{id}/order
POST /purchase-orders/{id}/receive
```

不允許 Client 透過：

```text
PATCH status
```

直接控制 Business Lifecycle。

---

# 4. API Modules

```text
/api/v1/auth
/api/v1/users

/api/v1/materials
/api/v1/suppliers
/api/v1/supplier-materials

/api/v1/demands

/api/v1/inventory
/api/v1/inventory-imports

/api/v1/recommendations

/api/v1/purchase-orders

/api/v1/stock-transactions

/api/v1/documents
/api/v1/ai
```

---

# 5. Authentication Context

V1 建議採 JWT。

JWT 至少包含：

```text
userId
username
role
```

所有 Audit User：

```text
createdBy
approvedBy
rejectedBy
orderedBy
receivedBy
uploadedBy
```

必須由 Authentication Context 取得。

Client 不允許傳：

```json
{
  "approvedBy": 3
}
```

---

# 6. Auth API

## POST /api/v1/auth/login

登入。

### Request

```json
{
  "username": "buyer01",
  "password": "password"
}
```

### Success

```text
200 OK
```

Response：

```json
{
  "accessToken": "jwt-token",
  "tokenType": "Bearer",
  "user": {
    "id": 10,
    "username": "buyer01",
    "displayName": "Procurement A",
    "role": "PROCUREMENT_STAFF"
  }
}
```

---

## GET /api/v1/auth/me

取得目前登入使用者。

### Success

```text
200 OK
```

---

# 7. User API

## GET /api/v1/users

**Role**

```text
ADMIN
```

支援 Pagination。

---

## POST /api/v1/users

建立使用者。

**Role**

```text
ADMIN
```

---

## GET /api/v1/users/{id}

取得使用者。

---

## PATCH /api/v1/users/{id}

可修改：

```text
displayName
role
status
```

V1 Password Update 可獨立於後續版本補充。

---

# 8. Material API

---

## GET /api/v1/materials

查詢 Material List。

### Query Parameters

```text
status
code
name
page
size
sort
```

### Response Example

```json
{
  "content": [
    {
      "id": 1,
      "code": "MAT-001",
      "name": "Strawberry Concentrate",
      "baseUnit": "KG",
      "safetyStock": 50,
      "status": "ACTIVE",
      "createdAt": "2026-10-08T00:00:00Z",
      "updatedAt": "2026-10-08T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

## POST /api/v1/materials

**Role**

```text
ADMIN
```

### Request — CreateMaterialRequest

```json
{
  "code": "MAT-001",
  "name": "Strawberry Concentrate",
  "baseUnit": "KG",
  "safetyStock": 50
}
```

### DTO Validation

```text
code
- @NotBlank

name
- @NotBlank

baseUnit
- @NotNull

safetyStock
- @NotNull
- @PositiveOrZero
```

### Business Validation

```text
code must be unique
baseUnit must be supported
```

### Behavior

```text
Create Material
+
Create Inventory(quantity = 0)
```

同一 Transaction。

### Success

```text
201 Created
```

---

## GET /api/v1/materials/{id}

取得單筆 Material。

### Not Found

```text
MATERIAL_NOT_FOUND
404
```

---

## PATCH /api/v1/materials/{id}

**Role**

```text
ADMIN
```

### Editable Fields

```text
name
safetyStock
status
```

V1 不允許修改：

```text
code
baseUnit
```

### Example

```json
{
  "name": "Updated Strawberry Concentrate",
  "safetyStock": 60,
  "status": "ACTIVE"
}
```

---

# 9. Supplier API

---

## GET /api/v1/suppliers

Query：

```text
status
code
name
page
size
sort
```

---

## POST /api/v1/suppliers

**Role**

```text
ADMIN
```

### CreateSupplierRequest

```json
{
  "code": "SUP-001",
  "name": "ABC Foods",
  "country": "TW",
  "reliabilityScore": 92.5,
  "qualityScore": 95
}
```

### Validation

```text
code required
name required
reliabilityScore 0~100
qualityScore 0~100
code unique
```

### Success

```text
201 Created
```

---

## GET /api/v1/suppliers/{id}

---

## PATCH /api/v1/suppliers/{id}

可修改：

```text
name
country
reliabilityScore
qualityScore
status
```

---

# 10. SupplierMaterial API

SupplierMaterial 為正式 Resource。

---

## GET /api/v1/supplier-materials

Query：

```text
supplierId
materialId
status
page
size
```

---

## POST /api/v1/supplier-materials

**Role**

```text
ADMIN
```

### CreateSupplierMaterialRequest

```json
{
  "supplierId": 1,
  "materialId": 10,
  "unitPrice": 80,
  "moq": 100,
  "packageMultiple": 25,
  "leadTimeDays": 14,
  "currency": "TWD"
}
```

### DTO Validation

```text
supplierId required
materialId required
unitPrice >= 0
moq > 0
packageMultiple > 0 if present
leadTimeDays >= 0
currency required
```

### Business Validation

```text
Supplier exists
Supplier ACTIVE
Material exists
Material ACTIVE
Supplier + Material must be unique
```

### Success

```text
201 Created
```

---

## GET /api/v1/supplier-materials/{id}

---

## PATCH /api/v1/supplier-materials/{id}

可修改：

```text
unitPrice
moq
packageMultiple
leadTimeDays
status
```

---

# 11. Demand API

---

## GET /api/v1/demands

Query：

```text
materialId
status
requiredFrom
requiredTo
page
size
sort
```

---

## POST /api/v1/demands

**Role**

```text
PRODUCTION_PLANNER
ADMIN
```

### CreateDemandRequest

```json
{
  "materialId": 1,
  "quantity": 400,
  "requiredDate": "2026-10-21",
  "note": "Customer promotion"
}
```

### DTO Validation

```text
materialId required
quantity > 0
requiredDate required
```

### Business Validation

```text
Material exists
Material ACTIVE
requiredDate >= current date
```

### Success

```text
201 Created
```

---

## GET /api/v1/demands/{id}

---

## PATCH /api/v1/demands/{id}

只允許修改：

```text
quantity
requiredDate
note
```

前提：

```text
Demand.status = ACTIVE
```

---

## POST /api/v1/demands/{id}/cancel

Business Action。

### Role

```text
PRODUCTION_PLANNER
ADMIN
```

### Preconditions

```text
Demand exists
Demand.status = ACTIVE
```

### Result

```text
ACTIVE → CANCELLED
```

### Success

```text
200 OK
```

回更新後 DemandResponse。

---

# 12. Inventory Query API

Inventory 不提供一般 Update Endpoint。

---

## GET /api/v1/inventory

Query：

```text
materialId
materialCode
page
size
```

---

## GET /api/v1/inventory/{materialId}

以 Material 為主要識別。

### Response

```json
{
  "materialId": 1,
  "materialCode": "MAT-001",
  "materialName": "Strawberry Concentrate",
  "quantity": 200,
  "unit": "KG",
  "updatedAt": "2026-10-08T00:00:00Z"
}
```

Client 不需要依賴 Inventory Entity ID。

---

# 13. Inventory Import API

採：

```text
Validate
→ Preview
→ Confirm
```

兩階段。

---

## POST /api/v1/inventory-imports/validate

**Role**

```text
WAREHOUSE_STAFF
ADMIN
```

### Content Type

```text
multipart/form-data
```

### Request

```text
file
```

V1 優先支援：

```text
CSV
```

### Success Example

```json
{
  "batchId": 101,
  "status": "VALIDATED",
  "totalRows": 30,
  "validRows": 30,
  "errorRows": 0,
  "errors": []
}
```

### Validation Failed Example

```json
{
  "batchId": 102,
  "status": "VALIDATION_FAILED",
  "totalRows": 30,
  "validRows": 28,
  "errorRows": 2,
  "errors": [
    {
      "row": 5,
      "materialCode": "MAT-999",
      "code": "MATERIAL_NOT_FOUND",
      "message": "Material does not exist."
    }
  ]
}
```

Validation Fail 不更新 Inventory。

---

## POST /api/v1/inventory-imports/{batchId}/confirm

**Role**

```text
WAREHOUSE_STAFF
ADMIN
```

### Preconditions

```text
Batch exists
Batch.status = VALIDATED
Batch not already imported
```

### Transaction

```text
BEGIN
Replace all validated Inventory snapshots
Update Batch → IMPORTED
COMMIT
```

Failure：

```text
ROLLBACK entire confirm transaction
Batch remains VALIDATED in V1
```

`IMPORT_FAILED` 可保留為 Enum，但 V1 不要求在失敗 transaction 內持久化該狀態。

---

# 14. Manual Inventory Adjustment API

## POST /api/v1/inventory/{materialId}/adjustments

**Role**

```text
WAREHOUSE_STAFF
ADMIN
```

### Request

```json
{
  "quantity": -5,
  "reason": "Damaged inventory"
}
```

### Validation

```text
quantity != 0
reason not blank
Material exists
Inventory exists
Current Inventory + quantity >= 0
```

### Transaction

```text
Update Inventory
+
Create StockTransaction(MANUAL_ADJUSTMENT)
```

### Success

```text
200 OK
```

---

# 15. Recommendation API

---

## POST /api/v1/recommendations

建立新的 Recommendation Snapshot。

### Role

```text
PROCUREMENT_STAFF
PROCUREMENT_MANAGER
ADMIN
```

### CreateRecommendationRequest

```json
{
  "materialId": 1,
  "periodStart": "2026-10-08",
  "periodEnd": "2026-10-21"
}
```

### DTO Validation

```text
materialId required
periodStart required
periodEnd required
periodEnd >= periodStart
```

### Business Validation

```text
Material exists
Material ACTIVE
```

### Calculation

```text
Aggregate Demand
↓
Read Current Inventory
↓
Aggregate Eligible Incoming PO
↓
Calculate Net Requirement
↓
Load Supplier Candidates
↓
Hard Constraint
↓
Supplier-specific Quantity
↓
Total Purchase Cost
↓
Ranking
↓
Persist Snapshot
```

### Success

```text
201 Created
```

---

## GET /api/v1/recommendations/{id}

### Response — RecommendationResponse

```json
{
  "id": 501,
  "material": {
    "id": 1,
    "code": "MAT-001",
    "name": "Strawberry Concentrate",
    "unit": "KG"
  },
  "period": {
    "start": "2026-10-08",
    "end": "2026-10-21"
  },
  "calculation": {
    "expectedDemand": 400,
    "safetyStock": 50,
    "currentInventory": 200,
    "incomingInventory": 100,
    "netRequirement": 150
  },
  "outcome": "SUPPLIER_RECOMMENDED",
  "recommendation": {
    "purchaseRequired": true,
    "supplierId": 3,
    "supplierName": "Supplier B",
    "quantity": 200,
    "score": 88.5
  },
  "createdAt": "2026-10-08T00:00:00Z"
}
```

同一份 `PurchaseOrderResponse` 會依 lifecycle 回傳 nullable audit 欄位：

```text
approvedBy / approvedByDisplayName / approvedAt
rejectedBy / rejectedByDisplayName / rejectedAt / rejectReason
orderedBy / orderedByDisplayName / orderedAt
receivedBy / receivedByDisplayName / receivedAt
```

如果不需採購：

```json
{
  "outcome": "PURCHASE_NOT_REQUIRED",
  "recommendation": {
    "purchaseRequired": false,
    "supplierId": null,
    "quantity": null,
    "score": null
  }
}
```

如果需要採購但沒有合格供應商：

```json
{
  "outcome": "NO_QUALIFIED_SUPPLIER",
  "recommendation": {
    "purchaseRequired": true,
    "supplierId": null,
    "quantity": null,
    "score": null
  }
}
```

`NO_QUALIFIED_SUPPLIER` 是正常 Recommendation Outcome，不是 HTTP Error。

---

## GET /api/v1/recommendations/{id}/suppliers

取得完整 Candidate Ranking。

### Response Example

```json
[
  {
    "supplierId": 3,
    "supplierCode": "SUP-003",
    "supplierName": "Supplier B",
    "qualified": true,
    "recommendedQuantity": 200,
    "unitPrice": 85,
    "totalCost": 17000,
    "leadTimeDays": 7,
    "priceScore": 82,
    "leadTimeScore": 95,
    "reliabilityScore": 96,
    "qualityScore": 92,
    "totalScore": 88.5,
    "rank": 1
  },
  {
    "supplierId": 4,
    "supplierCode": "SUP-004",
    "supplierName": "Supplier C",
    "qualified": false,
    "disqualifiedReason": "DELIVERY_DATE_NOT_FEASIBLE"
  }
]
```

---

# 16. Purchase Order API

---

## GET /api/v1/purchase-orders

### Role

```text
PROCUREMENT_STAFF
PROCUREMENT_MANAGER
ADMIN
```

Query：

```text
status
supplierId
materialId
createdFrom
createdTo
expectedDeliveryFrom
expectedDeliveryTo
overdue
page
size
sort
```

### overdue=true

Backend 解釋為：

```text
status = ORDERED
AND expectedDeliveryDate < currentDate
```

不是查：

```text
status = OVERDUE
```

### overdue=false / 省略 overdue

- `overdue=false`：排除符合逾期公式的 PO；仍可包含 `DRAFT`、`APPROVED`、未逾期 `ORDERED`、`RECEIVED`、`REJECTED`。
- 省略 `overdue`：不套用逾期條件，因此同時包含逾期與未逾期 PO。

Open PO 在 V1 定義為 `status=ORDERED` 且尚未進入 `RECEIVED`；使用 `status=ORDERED` 查詢。
交期等於 Backend Clock 的今天不算逾期。`isOverdue` 由 Backend Clock 動態計算，不寫入資料庫。

`materialId` 使用 PO Item existence 條件，不會因一張 PO 有多個 Item 而重複回傳；分頁 count 以 PO 為單位。

---

## POST /api/v1/purchase-orders

建立 PO Draft。

### Role

```text
PROCUREMENT_STAFF
ADMIN
```

### CreatePurchaseOrderRequest

```json
{
  "supplierId": 3,
  "expectedDeliveryDate": "2026-10-20",
  "items": [
    {
      "materialId": 1,
      "quantity": 200,
      "unitPrice": 85,
      "recommendationId": 501,
      "supplierRecommendationResultId": 801,
      "supplierOverrideReason": null,
      "quantityOverrideReason": null
    }
  ]
}
```

### Client 不可傳

```text
poNumber
status
unit
createdBy
approvedBy
orderedBy
receivedBy
recommendedSupplierIdSnapshot
recommendedQuantitySnapshot
```

上述欄位由 Backend 產生或取得。

### Acceptance Conditions

```text
Supplier exists
Supplier ACTIVE
Items not empty

Each Material exists
Each Material ACTIVE

SupplierMaterial exists
SupplierMaterial ACTIVE

quantity > 0
unitPrice >= 0

No duplicate Material in same PO
```

若提供 Recommendation：

```text
Recommendation exists
Recommendation.material = PO Item.material
SupplierRecommendationResult belongs to Recommendation
```

若選擇 Supplier 與推薦不同：

```text
supplierOverrideReason REQUIRED
```

若 Quantity 與推薦不同：

```text
quantityOverrideReason REQUIRED
```

### Success

```text
status = DRAFT
201 Created
```

---

## GET /api/v1/purchase-orders/{id}

### Response — PurchaseOrderResponse

```json
{
  "id": 1001,
  "poNumber": "PO-20261008-0001",
  "supplier": {
    "id": 3,
    "code": "SUP-003",
    "name": "Supplier B"
  },
  "status": "DRAFT",
  "expectedDeliveryDate": "2026-10-20",
  "isOverdue": false,
  "items": [
    {
      "id": 5001,
      "material": {
        "id": 1,
        "code": "MAT-001",
        "name": "Strawberry Concentrate"
      },
      "quantity": 200,
      "unit": "KG",
      "unitPrice": 85,
      "total": 17000,
      "recommendationId": 501,
      "supplierOverride": false,
      "quantityOverride": false,
      "supplierOverrideReason": null,
      "quantityOverrideReason": null
    }
  ],
  "createdBy": {
    "id": 20,
    "displayName": "Procurement A"
  },
  "createdAt": "2026-10-08T00:00:00Z"
}
```

---

## PATCH /api/v1/purchase-orders/{id}

修改既有 PO Draft。此 API 與建立 API 使用不同 DTO，因為所有頂層欄位均為 optional，且欄位省略與明確傳入 `null` 的語意不同。

### Role

```text
PROCUREMENT_STAFF
ADMIN
```

### Request

```json
{
  "supplierId": 4,
  "expectedDeliveryDate": "2026-10-30",
  "items": [
    {
      "materialId": 1,
      "quantity": 250,
      "unitPrice": 82,
      "recommendationId": 501,
      "supplierRecommendationResultId": 802,
      "supplierOverrideReason": "Alternate supplier selected for available capacity",
      "quantityOverrideReason": "Demand increased after recommendation"
    }
  ]
}
```

Response 使用 `PurchaseOrderResponse`，成功回傳 `200 OK`。

```json
{
  "id": 1001,
  "poNumber": "PO-20261008-001001",
  "supplierId": 4,
  "supplierCode": "SUP-004",
  "supplierName": "Supplier D",
  "status": "DRAFT",
  "expectedDeliveryDate": "2026-10-30",
  "items": [
    {
      "id": 5001,
      "materialId": 1,
      "materialCode": "MAT-001",
      "materialName": "Strawberry Concentrate",
      "quantity": 250,
      "unit": "KG",
      "unitPrice": 82,
      "total": 20500,
      "recommendationId": 501,
      "supplierRecommendationResultId": 802,
      "supplierOverride": true,
      "quantityOverride": true,
      "supplierOverrideReason": "Alternate supplier selected for available capacity",
      "quantityOverrideReason": "Demand increased after recommendation"
    }
  ],
  "createdBy": 20,
  "createdByDisplayName": "Procurement A",
  "createdAt": "2026-10-08T00:00:00Z",
  "approvedBy": null,
  "approvedByDisplayName": null,
  "approvedAt": null,
  "rejectReason": null,
  "rejectedBy": null,
  "rejectedByDisplayName": null,
  "rejectedAt": null,
  "updatedAt": "2026-10-10T00:00:00Z"
}
```

### Optional Field 與 Null 語意

```text
省略 supplierId             → 保留原 Supplier
提供 supplierId             → 修改 Supplier；JSON null 不允許
省略 expectedDeliveryDate   → 保留原日期
提供日期                    → 設定新日期
expectedDeliveryDate: null  → 清除日期
省略 items                  → 保留原 Item 集合，不刪除任何 Item
提供 items                  → 視為整張 DRAFT 的完整 Item 清單
items: [] 或 items: null    → VALIDATION_FAILED
```

提供 `items` 時，不是單筆 Item patch。清單中未出現的原 Item 會被移除；新增 Material 會建立 Item；相同 Material 的 Item 會更新 Business Data，並保留既有 Item identity 與 `createdAt`。

### Lifecycle 與 Aggregate Ownership

只允許：

```text
status = DRAFT
```

時修改。

可修改的 Business Data：

```text
supplier
expectedDeliveryDate
完整 items 清單
```

Supplier、Expected Delivery Date 與 Items 均由 `PurchaseOrder` Aggregate Root 修改。不提供獨立 PurchaseOrderItem CRUD API。更新不重建 PO Header，也不重新產生 `poNumber`；`createdBy`、`createdAt` 保持不變，成功修改時更新 `updatedAt`，並保留 `@Version` 樂觀鎖。

### Validation

最終 Supplier 與 Item 集合會完整重新驗證：

```text
Supplier exists and ACTIVE
至少一個 Item
Material exists and ACTIVE
SupplierMaterial exists and ACTIVE
同一張 PO 不得有重複 Material
quantity > 0
unitPrice >= 0
unit 由 Material.baseUnit 取得
```

即使 PATCH 只修改 Supplier 且省略 `items`，仍會以新 Supplier 重新驗證所有保留 Item；任何關聯不合法時，整筆 transaction rollback。

若 Item 提供 Recommendation Reference：

```text
Recommendation exists
Recommendation.material = Item.material
SupplierRecommendationResult belongs to Recommendation
SupplierRecommendationResult.supplier = 最終 PO Supplier
Snapshot evidence 取自 persisted Recommendation
Supplier override 必須提供非空白 supplierOverrideReason
Quantity override 必須提供非空白 quantityOverrideReason
Quantity 使用 BigDecimal.compareTo() 比較
沒有對應 override 時不保存任意 reason
```

不額外限制 Recommendation outcome 或 `qualified` 狀態。

### Backend-owned Fields

Request 不接受：

```text
id
poNumber
status
createdBy / createdAt / updatedAt
version
approvedBy / approvedAt
rejectedBy / rejectedAt
orderedBy / orderedAt
receivedBy / receivedAt
rejectReason
unit
recommendedSupplierIdSnapshot
recommendedQuantitySnapshot
其他 Recommendation snapshot 欄位
```

出現任何未允許欄位時回傳 `400 VALIDATION_FAILED`。

若非 DRAFT：

```text
PO_NOT_EDITABLE
409 Conflict
```

---

# 17. Purchase Order Lifecycle Action API

---

## POST /api/v1/purchase-orders/{id}/approve

### Role

```text
PROCUREMENT_MANAGER
ADMIN
```

### Preconditions

```text
PO exists
PO.status = DRAFT
PO has at least one valid item
```

### Result

```text
DRAFT → APPROVED
```

記錄：

```text
approvedBy
approvedAt
```

`approvedBy` 由 `CurrentUserProvider` 取得，`approvedAt` 由 Backend Clock 產生；Request body 無法指定 status 或 audit 欄位。

### Success

```text
200 OK
```

回更新後 PO。

---

## POST /api/v1/purchase-orders/{id}/reject

### Role

```text
PROCUREMENT_MANAGER
ADMIN
```

### Request

```json
{
  "reason": "Quantity exceeds current purchasing plan."
}
```

### Preconditions

```text
PO.status = DRAFT
reason not blank
```

### Result

```text
DRAFT → REJECTED
```

記錄：

```text
rejectReason
rejectedBy
rejectedAt
```

`rejectedBy` 由 `CurrentUserProvider` 取得，`rejectedAt` 由 Backend Clock 產生。三者與 status 在同一 transaction 保存；欄位會出現在 action response 與後續 GET response。Client 傳入 status 或 audit 欄位時回 `400 VALIDATION_FAILED`。

### Success

```text
200 OK
```

回更新後 PO。缺少、null、空字串或純空白 reason 時回 `400 REJECT_REASON_REQUIRED`；非 DRAFT 轉換回 `409 INVALID_PO_STATUS_TRANSITION`。

---

## POST /api/v1/purchase-orders/{id}/order

### Role

```text
PROCUREMENT_STAFF
ADMIN
```

### Preconditions

```text
PO.status = APPROVED
expectedDeliveryDate exists
PO items valid
```

### Result

```text
APPROVED → ORDERED
```

記錄：

```text
orderedBy
orderedAt
```

`orderedBy` 由 `CurrentUserProvider` 取得，`orderedAt` 由注入的 Backend `Clock` 取得；Client 無法指定或覆寫 status 與 ordering audit。status、ordering audit 與 `updatedAt` 在同一 transaction 保存。重複呼叫或從非 `APPROVED` 狀態呼叫回 `409 INVALID_PO_STATUS_TRANSITION`。

成功 Response 與後續 Detail / List Response 均包含：

```text
orderedBy
orderedByDisplayName
orderedAt
isOverdue
```

只有 transaction commit 成功後才計入 Incoming Inventory；`ORDERED` 代表已下單未收貨，不會直接增加 Inventory，也不建立 StockTransaction。

---

## POST /api/v1/purchase-orders/{id}/receive

### Role

```text
WAREHOUSE_STAFF
ADMIN
```

V1：

```text
Full Receiving Only
```

此 endpoint 不接受收貨數量或任何 audit/status request 欄位；每筆收貨數量固定取自資料庫中的 PO Item snapshot。

### Preconditions

```text
PO exists
PO.status = ORDERED
PO not already received
all items valid
```

### Transaction

```text
BEGIN

for each item:
    load Inventory
    Inventory.receive(PO Item quantity)
    save Inventory
    create and save StockTransaction(PURCHASE_RECEIVE)

PO → RECEIVED

COMMIT
```

任一步驟失敗：

```text
ROLLBACK
```

記錄：

```text
receivedBy
receivedAt
```

`receivedBy` 取自 authenticated user，`receivedAt` 與 `updatedAt` 使用同一次 `Clock.instant()`。
成功回傳 `200 OK` 與更新後的 `PurchaseOrderResponse`；其 `status=RECEIVED`、`isOverdue=false`，並保留 Supplier、Items 及既有 Approval / Ordering Audit。第二次收貨回 `409 DUPLICATE_RECEIVING`，不再增加 Inventory 或建立 StockTransaction。

---

# 18. StockTransaction API

StockTransaction 為 Audit Resource。

V1 不提供直接 Create / Update API。

---

## GET /api/v1/stock-transactions

Query：

```text
materialId
type
purchaseOrderId
from
to
page
size
sort
```

---

## GET /api/v1/stock-transactions/{id}

---

# 19. Document API

---

## POST /api/v1/documents

### Role

```text
PROCUREMENT_STAFF
ADMIN
```

### Content Type

```text
multipart/form-data
```

Metadata：

```text
documentType
supplierId
title
version
file
```

### Processing

```text
Upload File
↓
Save DocumentMetadata(status = PROCESSING)
↓
Parse / Chunk / Embedding / Vector Store
↓
READY
```

若 ingestion 失敗：

```text
status = FAILED
```

DocumentStatus：

```text
PROCESSING
READY
FAILED
INACTIVE
```

實際 Storage 可使用 S3。

---

## GET /api/v1/documents

Query：

```text
documentType
supplierId
status
page
size
```

---

## GET /api/v1/documents/{id}

---

## PATCH /api/v1/documents/{id}

V1 可修改：

```text
title
status
```

Document Version Replacement 可留後續版本。

---

# 20. AI API

---

## POST /api/v1/ai/query

### Request

```json
{
  "message": "為什麼 MAT-001 建議買 200kg？"
}
```

### Possible Tools

```text
getPurchaseRecommendation
getSupplierRanking
getOpenPurchaseOrders
RAG Document Retrieval
createPurchaseDraft
```

### Rules

- AI 不自行計算核心採購數字。
- AI 不可直接 Approve。
- AI 不可直接 Order。
- AI 不可直接 Receive。
- AI 建立 PO 時只能建立 DRAFT。
- AI Tool 呼叫與 REST API 共用相同 Application Service。

---

# 21. AI Draft Creation

不建立第二套 Business Logic。

架構：

```text
REST Controller
        │
        ▼
PurchaseOrderService.createDraft()
        ▲
        │
AI Tool
```

REST 與 AI 都走同一 Service。

---

# 22. AI / RAG Failure

真正 Provider / Infrastructure 錯誤：

```text
AI_PROVIDER_UNAVAILABLE
AI_TIMEOUT
AI_INVALID_RESPONSE
```

若 RAG 無足夠證據，應回合法 application result：

```text
status = INSUFFICIENT_EVIDENCE
answer = null
sources = []
```

不視為 5xx Exception。

AI Failure 不得阻斷：

```text
Demand
Inventory
Recommendation Backend Logic
Purchase Order
Approval
Ordering
Receiving
StockTransaction
```

---

# 23. Pagination

所有 List API：

```text
?page=0
&size=20
&sort=createdAt,desc
```

規則：

```text
page >= 0
size >= 1
size <= 100
```

Sort Field 必須使用 whitelist。

不可直接讓 Client 任意指定 DB Column。

Purchase Order 可排序欄位為 `id`、`poNumber`、`status`、`expectedDeliveryDate`、`createdAt`、`updatedAt`、`orderedAt`。除 `id` 外，系統會自動加上 `id ASC` 作為穩定次要排序。

### Response

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 135,
  "totalPages": 7
}
```

不直接暴露 Spring Data `PageImpl` Serialization。

---

# 24. Success HTTP Status

| Operation | HTTP |
|---|---:|
| Create Resource | 201 Created |
| Query | 200 OK |
| Update | 200 OK |
| Business Action | 200 OK |
| Login | 200 OK |

Business Action 通常回更新後 Resource，而不是空 Body。

---

# 25. Error HTTP Status

| Situation | HTTP |
|---|---:|
| DTO Validation | 400 |
| Business Validation | 400 |
| Authentication Failure | 401 |
| Permission Failure | 403 |
| Resource Not Found | 404 |
| Duplicate Unique Resource | 409 |
| Illegal State Transition | 409 |
| Duplicate Receiving | 409 |
| Concurrency Conflict | 409 |
| AI Provider Failure | 503 |
| Unexpected Server Error | 500 |

---

# 26. Error Response

統一格式：

```json
{
  "code": "INVALID_PO_STATUS",
  "message": "Purchase order must be ORDERED before receiving.",
  "timestamp": "2026-10-08T00:00:00Z",
  "path": "/api/v1/purchase-orders/1001/receive"
}
```

Validation Error：

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed.",
  "timestamp": "2026-10-08T00:00:00Z",
  "path": "/api/v1/demands",
  "fieldErrors": [
    {
      "field": "quantity",
      "message": "must be greater than 0"
    }
  ]
}
```

Internal Exception 不直接暴露：

```text
SQLException
JpaSystemException
NullPointerException
Stack Trace
```

Client 只收到：

```text
INTERNAL_SERVER_ERROR
```

完整細節進 Log。

---

# 27. ErrorCode Enum — Resource

```text
MATERIAL_NOT_FOUND
SUPPLIER_NOT_FOUND
SUPPLIER_MATERIAL_NOT_FOUND
DEMAND_NOT_FOUND
PURCHASE_ORDER_NOT_FOUND
RECOMMENDATION_NOT_FOUND
INVENTORY_NOT_FOUND
USER_NOT_FOUND
DOCUMENT_NOT_FOUND
```

---

# 28. ErrorCode Enum — Status

```text
MATERIAL_INACTIVE
SUPPLIER_INACTIVE
SUPPLIER_MATERIAL_INACTIVE
USER_INACTIVE
```

---

# 29. ErrorCode Enum — Validation

```text
VALIDATION_FAILED
INVALID_QUANTITY
NEGATIVE_QUANTITY
INVALID_DATE_RANGE
UNIT_MISMATCH
DUPLICATE_MATERIAL
DUPLICATE_CODE
```

---

# 30. ErrorCode Enum — Recommendation

```text
INVALID_RECOMMENDATION_REFERENCE
INVALID_SUPPLIER_RECOMMENDATION_REFERENCE
```

`NO_QUALIFIED_SUPPLIER` 是 `RecommendationOutcome`；`DELIVERY_DATE_NOT_FEASIBLE` 是 Candidate `DisqualificationReason`，不作為一般 API Exception。

---

# 31. ErrorCode Enum — Purchase Order

```text
INVALID_PO_STATUS
INVALID_PO_STATUS_TRANSITION
PO_NOT_EDITABLE
EXPECTED_DELIVERY_DATE_REQUIRED
REJECT_REASON_REQUIRED
SUPPLIER_OVERRIDE_REASON_REQUIRED
QUANTITY_OVERRIDE_REASON_REQUIRED
DUPLICATE_RECEIVING
```

---

# 32. ErrorCode Enum — Inventory

```text
INSUFFICIENT_INVENTORY
INVENTORY_IMPORT_NOT_VALIDATED
INVENTORY_IMPORT_ALREADY_CONFIRMED
INVALID_FILE_FORMAT
FILE_EMPTY
FILE_TOO_LARGE
CONCURRENT_MODIFICATION
```

Row-level import validation error（例如 `UNIT_MISMATCH`、`INVALID_QUANTITY`、`DUPLICATE_MATERIAL`、`MATERIAL_NOT_FOUND`）以 Preview Result 回傳，不使用 HTTP Exception。

---

# 33. ErrorCode Enum — AI / RAG

```text
AI_PROVIDER_UNAVAILABLE
AI_TIMEOUT
AI_INVALID_RESPONSE
```

RAG 無證據以 `INSUFFICIENT_EVIDENCE` application result 表示。

---

# 34. Unit Price Rule

V1 允許：

```text
PurchaseOrderItem.unitPrice
!=
SupplierMaterial.unitPrice
```

原因：

- 最新詢價可能尚未更新 Master Data。
- 採購人員可能取得臨時議價價格。

V1 不建立：

```text
Price Override Reason
Quotation Approval Workflow
Quotation History
```

此功能可留 V1.1。

---

# 35. Snapshot Security

Client 不允許傳：

```text
recommendedSupplierIdSnapshot
recommendedQuantitySnapshot
unit snapshot
```

Backend 依：

```text
PurchaseRecommendation
SupplierRecommendationResult
Material
```

自行取得並保存 Snapshot。

Client Request 永遠不被視為 Audit Truth。

---

# 36. API Acceptance Baseline

V1 API 必須能支援以下完整流程：

```text
Create Material
↓
Create Supplier
↓
Create SupplierMaterial
↓
Create Demand
↓
Validate Inventory CSV
↓
Confirm Inventory Import
↓
Create Recommendation
↓
View Supplier Ranking
↓
Create PO Draft
↓
Edit Draft
↓
Approve / Reject
↓
Mark Ordered
↓
Detect Overdue
↓
Receive
↓
Update Inventory
↓
Create StockTransaction
↓
Manual Inventory Adjustment
↓
View Audit History
```

AI 加值：

```text
Explain Recommendation
Explain Supplier Ranking
Query Open PO
RAG Document Query
Create PO Draft
```

AI 不可成為核心流程必要依賴。

---

# 37. Endpoint Summary

## Auth / User

```text
POST  /api/v1/auth/login
GET   /api/v1/auth/me

GET   /api/v1/users
POST  /api/v1/users
GET   /api/v1/users/{id}
PATCH /api/v1/users/{id}
```

## Material

```text
GET   /api/v1/materials
POST  /api/v1/materials
GET   /api/v1/materials/{id}
PATCH /api/v1/materials/{id}
```

## Supplier

```text
GET   /api/v1/suppliers
POST  /api/v1/suppliers
GET   /api/v1/suppliers/{id}
PATCH /api/v1/suppliers/{id}
```

## SupplierMaterial

```text
GET   /api/v1/supplier-materials
POST  /api/v1/supplier-materials
GET   /api/v1/supplier-materials/{id}
PATCH /api/v1/supplier-materials/{id}
```

## Demand

```text
GET   /api/v1/demands
POST  /api/v1/demands
GET   /api/v1/demands/{id}
PATCH /api/v1/demands/{id}
POST  /api/v1/demands/{id}/cancel
```

## Inventory

```text
GET   /api/v1/inventory
GET   /api/v1/inventory/{materialId}
POST  /api/v1/inventory/{materialId}/adjustments
```

## Inventory Import

```text
POST  /api/v1/inventory-imports/validate
POST  /api/v1/inventory-imports/{batchId}/confirm
```

## Recommendation

```text
POST  /api/v1/recommendations
GET   /api/v1/recommendations/{id}
GET   /api/v1/recommendations/{id}/suppliers
```

## Purchase Order

```text
GET   /api/v1/purchase-orders
POST  /api/v1/purchase-orders
GET   /api/v1/purchase-orders/{id}
PATCH /api/v1/purchase-orders/{id}

POST  /api/v1/purchase-orders/{id}/approve
POST  /api/v1/purchase-orders/{id}/reject
POST  /api/v1/purchase-orders/{id}/order
POST  /api/v1/purchase-orders/{id}/receive
```

## StockTransaction

```text
GET   /api/v1/stock-transactions
GET   /api/v1/stock-transactions/{id}
```

## Document

```text
POST  /api/v1/documents
GET   /api/v1/documents
GET   /api/v1/documents/{id}
PATCH /api/v1/documents/{id}
```

## AI

```text
POST  /api/v1/ai/query
```

---

# 38. Key API Decisions

正式鎖定：

1. Entity 與 API DTO 分離。
2. Business Status Transition 使用 Action Endpoint。
3. Inventory 不提供任意 Quantity Update API。
4. StockTransaction 不提供直接 Create API。
5. Inventory Import 採 Validate → Confirm。
6. Recommendation 用 POST 建立持久化 Snapshot。
7. AI Tool 與 REST API 共用 Application Service。
8. Error Response 統一。
9. Success Response 不強制 Envelope。
10. Audit User 從 Authentication Context 取得。
11. Snapshot 欄位由 Backend 建立。
12. Illegal Lifecycle / Duplicate Operation 使用 409。
13. List API size 最大 100。
14. Inventory API 以 Material 為主要識別。
15. V1 允許 PO Unit Price 與 SupplierMaterial Current Price 不同。
16. Client 不可直接控制 PO status。
17. AI 最多建立 DRAFT，不可 Approve / Order / Receive。
18. AI Failure 不阻斷核心 Backend Flow。

---

# 39. Next Stage

API Design 完成後，下一階段：

```text
API Design
↓
Package Architecture
↓
Transaction Boundary
↓
Error / Validation Implementation
↓
Security Implementation
↓
Testing Strategy
↓
Implementation Plan
```

下一份建議文件：

```text
package-architecture.md
```

該文件應定義：

```text
controller
service
domain
repository
dto
exception
config
security
ai
```

以及 Modular Monolith 各 module 的 Java package boundary。
