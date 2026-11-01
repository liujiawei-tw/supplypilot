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

### V2-101 授權覆寫

V2 帳號的 `role` 固定為 `ADMIN`，代表採購業務角色；人員管理以獨立 `canManageUsers` 能力授權。登入與 `/auth/me` 的 user object 都回傳 `canManageUsers`。新建帳號可省略 `role`（伺服器以 `ADMIN` 建立），並可由已授權管理者明確設定 `canManageUsers`，預設為 `false`。更新 API 僅允許 `displayName`、`status`、`canManageUsers`；舊 V1 角色不接受為新帳號指派值。

`GET /api/v1/users`、`GET /api/v1/users/{id}`、`POST /api/v1/users` 與 `PATCH /api/v1/users/{id}` 均要求 `canManageUsers=true`，否則回 `403 ACCESS_DENIED`。最後一位啟用的人員管理者不能被停用或撤銷此能力。

## GET /api/v1/users

**V1 Role**

```text
ADMIN
```

支援 Pagination。

---

## POST /api/v1/users

建立使用者。

**V1 Role**

```text
ADMIN
```

---

## GET /api/v1/users/{id}

取得使用者。

---

## PATCH /api/v1/users/{id}

V1 可修改：

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

授權：`PRODUCTION_PLANNER`、`PROCUREMENT_STAFF`、`WAREHOUSE_STAFF`、`ADMIN`；`PROCUREMENT_MANAGER` 不可呼叫。Inventory 頁面的 Stock Transactions Tab 使用其獨立授權的查詢 API，不會為顯示該 Tab 呼叫 Inventory GET。

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
    "id": 31,
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
    "id": 32,
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
poNumber
supplierCode
supplierName
materialCode
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

### V2-207 搜尋條件

- `poNumber` 支援完整單號或部分單號包含比對；`supplierCode`、`supplierName`、`materialCode` 皆為不分大小寫的部分比對。
- 搜尋值先去除前後空白；空字串或純空白不套用該條件。每個搜尋值最多 100 字元；超過上限回 `400 VALIDATION_FAILED`。
- `%`、`_`、`!` 是一般字元，不作為 SQL `LIKE` 萬用字元。查詢以資料庫條件套用後再分頁，不會先取一頁再在應用程式過濾。
- 所有已提供條件以 AND 組合。`materialId` 與 `materialCode` 同時提供時，必須是同一筆 PO 品項同時符合兩者；物料搜尋採 `EXISTS`，故一張 PO 即使有多筆符合品項仍只計一次，`totalElements` 以 PO 為單位。
- 此查詢沿用本節列出的 PO 讀取角色與 Service 授權檢查；新增搜尋參數不擴大可見資料範圍。

範例：

```text
GET /api/v1/purchase-orders?poNumber=P-a12b&supplierCode=SUP-00&supplierName=北區&materialCode=MAT-0&page=0&size=20&sort=poNumber,asc
```

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
currency
createdBy
approvedBy
orderedBy
receivedBy
recommendedSupplierIdSnapshot
recommendedQuantitySnapshot
```

上述欄位由 Backend 產生或取得。每筆 PO item 的 `currency` 是建立／替換品項時從該 PO 供應商的有效 SupplierMaterial 複製的歷史快照。更新供應商但保留品項時，草稿快照依新供應商的 SupplierMaterial 更新；只更新交期等非商務欄位時保留原快照。既有品項由 V31 回填 `TWD`；目前 Currency enum 與 SupplierMaterial 資料庫約束只允許 `TWD`。

### Acceptance Conditions

`expectedDeliveryDate` 在建立 DRAFT 時可省略或為 `null`；PATCH DRAFT 可設定、修改或清除。核准前才必須補齊。

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
      "currency": "TWD",
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
      "currency": "TWD",
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
expectedDeliveryDate exists
```

缺少交期回 `400 EXPECTED_DELIVERY_DATE_REQUIRED`，PO 維持 DRAFT，核准稽核欄位不寫入。此檢查由 Backend 在核准交易內執行；不得要求建立 DRAFT 時必填。既有 `APPROVED` 且交期為空的資料須在下單前辨識與修復，`order` 原有防線仍保留。

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

下單 Request 不接受交期。Order Modal 僅顯示已核准交期；若舊資料缺交期，阻擋下單並導向人工資料修復，不以 Draft Edit API 修改 APPROVED PO。

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
成功回傳 `200 OK` 與下列 `ReceivingPurchaseOrderResponse`（受限 DTO）；第二次收貨回 `409 DUPLICATE_RECEIVING`，不再增加 Inventory 或建立 StockTransaction。既有實作目前回傳完整 `PurchaseOrderResponse`，此處為待實作的**縮減回應契約**，會影響依賴完整回應的客戶端及整合測試，須作為 Backend B 的相容性變更明確驗證。

```json
{
  "id": 1001,
  "poNumber": "PO-20261011-001001",
  "supplierId": 3,
  "supplierCode": "SUP-003",
  "supplierName": "供應商",
  "status": "RECEIVED",
  "expectedDeliveryDate": "2026-10-20",
  "isOverdue": false,
  "items": [{ "id": 11, "materialId": 1, "materialCode": "MAT-001", "materialName": "物料", "quantity": 50, "unit": "KG" }],
  "orderedAt": "2026-10-11T01:00:00Z",
  "receivedBy": 5,
  "receivedByDisplayName": "倉管人員",
  "receivedAt": "2026-10-11T02:00:00Z"
}
```

`ReceivingPurchaseOrderResponse` 與 Read Detail、List item 共用完全相同的欄位。`isOverdue` 由 Backend Clock 計算，僅 `ORDERED` 且交期早於今天時為 `true`；`RECEIVED` 一律為 `false`。此欄位只供展示，不新增 `OVERDUE` PO 狀態。不得包含 `unitPrice`、`total`、推薦評分、覆寫理由、採購建立／核准稽核資料。`receivedBy*`、`receivedAt` 在 ORDERED 時為 `null`。收貨寫入交易與既有 URL、角色、錯誤碼不變。

## Receiving 專用 Read API（Backend B 前置契約）

`GET /api/v1/receiving/purchase-orders` 與 `GET /api/v1/receiving/purchase-orders/{id}` 僅允許 `WAREHOUSE_STAFF`、`ADMIN`；未登入為 `401`，其他角色為 `403`。這是新的讀取介面，不使用一般 PO Read API 讓倉管取得完整採購資訊。

List 預設 `status=ORDERED`；明確指定 `status=RECEIVED` 可查歷史，用於重複提交及未知結果核實。其他狀態回 `400 VALIDATION_FAILED`。支援 `materialId`、`expectedDeliveryFrom`、`expectedDeliveryTo`、`orderedFrom`、`orderedTo`、`page`（預設 0）、`size`（預設 20、上限 100）、`sort`（預設 `id,asc`）；日期參數採 ISO-8601 LocalDate，Instant 欄位依注入的 Backend Clock 時區轉換當地日期（V2 預設 `Asia/Taipei`）後比較且範圍含兩端，反向範圍回 `400 VALIDATION_FAILED`。排序白名單為 `id`、`poNumber`、`expectedDeliveryDate`、`orderedAt`、`receivedAt`，非 id 排序以 `id ASC` 穩定次序；非法參數回 `400 VALIDATION_FAILED`。回應採既有 `PageResponse<ReceivingPurchaseOrderResponse>` 結構。先對 PO 分頁及計數，再批次取得該頁 Items、Material 與必要 Supplier / Receiver 資料，避免含 Items 的 List 對每筆 PO 反覆查詢造成 N+1；不得因 fetch join Items 使分頁或 `totalElements` 失準，亦不新增 Endpoint。Detail 不在 ORDERED / RECEIVED 範圍或不存在時回 `404 PURCHASE_ORDER_NOT_FOUND`，避免暴露其他狀態。

---

# 18. StockTransaction API

StockTransaction 為 Audit Resource。

V1 不提供直接 Create / Update API。

---

## GET /api/v1/stock-transactions

此查詢為 E8 StockTransaction Display 的 Backend C 前置依賴；`main` 目前尚無查詢 Controller。只提供唯讀查詢，既有收貨與人工調整寫入機制不變。授權：`WAREHOUSE_STAFF`、`PROCUREMENT_MANAGER`、`ADMIN`；未登入 `401`，其他角色 `403`。

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

`materialId`、`purchaseOrderId` 為正整數；`type` 限 `PURCHASE_RECEIVE`、`MANUAL_ADJUSTMENT`；`from` / `to` 為 ISO-8601 Instant，含起點、終點（`createdAt >= from` 且 `createdAt <= to`），反向範圍回 `400 VALIDATION_FAILED`。`page` 預設 0、`size` 預設 20 且最大 100；`sort` 預設 `createdAt,desc`，白名單 `id`、`createdAt`、`materialId`、`type`、`purchaseOrderId`、`quantity`，同值以 `id ASC` 穩定排序。回既有 `PageResponse<StockTransactionReadResponse>`。不提供跨物料 `totalQuantity`：不同單位不能直接相加。

對外 `sort=materialId` 須由 Backend 映射為 JPA 路徑 `material.id`；`StockTransaction` 沒有名為 `materialId` 的 Entity Property，不得把外部欄位名直接傳給 Spring Data `Sort`。

```json
{"id": 71, "materialId": 1, "materialCode": "MAT-001", "materialName": "物料", "unit": "KG", "type": "PURCHASE_RECEIVE", "quantity": 50, "purchaseOrderId": 1001, "purchaseOrderNumber": "PO-20261011-001001", "reason": null, "createdBy": 5, "createdByDisplayName": "倉管人員", "createdAt": "2026-10-11T02:00:00Z"}
```

`quantity` 為帶正負號的異動量，`unit` 取 Material 基本單位；`purchaseOrderId`、`purchaseOrderNumber` 僅 PURCHASE_RECEIVE 非空。`StockTransaction` 現有的 `purchaseOrderId` 是 Long 欄位，沒有 `PurchaseOrder` JPA Association；讀取時先取得當頁交易的非空 PO ID，再以既有 ID 批次唯讀查詢 PO 資料補 `purchaseOrderNumber`，避免 N+1，不修改資料庫關聯或 StockTransaction Entity，也不授權存取完整 PO。`reason` 僅 MANUAL_ADJUSTMENT 非空。不得提供 POST / PATCH / PUT / DELETE StockTransaction。

---

## GET /api/v1/stock-transactions/{id}

回單一 `StockTransactionReadResponse`；不存在回 `404 STOCK_TRANSACTION_NOT_FOUND`（Backend C 須新增錯誤碼及 HTTP mapping）。授權與 List 相同。

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
Parse / TokenTextSplitter / persist source chunks in MySQL
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

### 文件問答索引階段（Spring AI 遷移工作分支）

`READY` 只表示 MySQL 文件來源及來源 chunks 已保存，**不表示已完成出站核准或可供問答檢索**。管理員核准且政策判定可出站後，持久化索引工作才呼叫 EmbeddingModel 並寫入 PostgreSQL `semantic_rag_vectors`。索引完整發布並通過後端來源檢查後，才可進入生成 Context。

目前實作分支改以 Spring AI `TextReader`／`TikaDocumentReader`、`TokenTextSplitter`、`PgVectorStore` 與 Gemini Embedding 建立索引；MySQL 仍是文件來源及交易狀態的權威資料庫。Testcontainers 已驗證 V3 schema migration 與 PgVectorStore 寫入；正式／Demo 資料庫 migration、Backfill、跨庫資料完整性對帳及正式啟用尚未驗收。操作程序與限制見 [`semantic-rag-runbook.md`](semantic-rag-runbook.md)；本段不代表已完成部署或已啟用。

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

## POST /api/v1/document-queries

文件問答 API 維持既有路徑；已登入的 `PROCUREMENT_STAFF`、`PROCUREMENT_MANAGER` 或 `ADMIN` 可查詢。`question` 必填，最多 2000 字元。

```json
{
  "question": "SUP-002 的標準交期是多久？"
}
```

有已驗證文件證據時回 `200 OK`、`status=ANSWERED`、答案及由後端建立的 `sources`。沒有已驗證證據時回 `200 OK`、`status=INSUFFICIENT_EVIDENCE`、`answer=null`、`sources=[]`。來源欄位為 `documentId`、`documentKey`、`documentType`、`title`、`version`、`supplierId`／`supplierCode`／`supplierName`、`chunkIndex`、`startOffset`、`endOffset`、`excerpt` 與 `relevanceScore`；offset 是正規化抽取文字內的 Java UTF-16 字元位置，分數來自實際檢索結果，不得由模型生成。

`DocumentSearchTool` 的 `SEARCH_DOCUMENTS` 也呼叫同一個 `DocumentQueryService`，不另設舊 RAG 查詢路徑。遷移分支的服務路徑使用 Spring AI `ChatClient`、`RetrievalAugmentationAdvisor`、`VectorStoreDocumentRetriever` 及 PgVectorStore。`SEMANTIC_READ_ENABLED=false` 時不執行檢索，文件 API 回 HTTP 503 `AI_PROVIDER_UNAVAILABLE`；經正常檢索但無已驗證來源才回 HTTP 200 `INSUFFICIENT_EVIDENCE`。AI 助理將文件工具故障包裝為既有 HTTP 503 `AI_QUERY_FAILED`。分層測試已執行，但正式／Demo DB Backfill、完整性對帳及真實文件查詢 E2E 尚未驗收，不能宣稱已正式啟用。

---

# 20. AI API

---

## POST /api/v1/ai/query

權限：`PROCUREMENT_STAFF`、`PROCUREMENT_MANAGER`、`ADMIN`。V1 為單輪同步 JSON，無持久對話記憶或 Streaming 契約。

### Request

```json
{
  "message": "為什麼 MAT-001 建議買 200kg？"
}
```

### Possible Tools

```text
QUERY_INVENTORY / QUERY_INVENTORY_BATCH / QUERY_INVENTORY_BY_NAME
QUERY_SUPPLIER_QUOTES / QUERY_AVERAGE_PRICE / QUERY_SUPPLIER
QUERY_PURCHASE_ORDER / QUERY_OPEN_PO / SEARCH_DOCUMENTS
QUERY_RECOMMENDATION / EXPLAIN_RECOMMENDATION / QUERY_SUPPLIER_RANKING（僅在使用者明確詢問已保存的舊版建議時）
```

### Response 與資料來源

`status` 為 `ANSWERED`、`NOT_FOUND`、`INSUFFICIENT_EVIDENCE` 或 `NEEDS_CLARIFICATION`。`answer` 只包含後端唯讀工具回傳的可信事實；複合查詢不完整時，仍可回傳個別已驗證的部分事實，並由 `details.notes` 說明缺少的資料；沒有可用部分事實時 `answer=null`。經驗證的簡短 AI 摘要另以 `summary` 回傳，且只有摘要實際顯示時 `answerSource` 才會是 `MODEL_EXPLANATION`。模型輸出無效或摘要不符安全句型時，`summary=null`、`answerSource=BACKEND_FACTS`。查詢回應另回傳後端建立的 `details` 物件，包含 `inventory`、`quotes`、`averagePrices`、`suppliers`、`purchaseOrders`、`candidates`、`unmatchedIdentifiers` 與 `notes` 陣列。`purchaseOrderTotalCount` 保留符合條件的真實 PO 總數；`purchaseOrders` 僅包含本次最多取得的 100 筆，前端須分別顯示總數、取得數與尚未列出的數量。數量、單價、平均值、評分與金額均由 API 以 Decimal 欄位回傳；前端保留精度後再格式化。

- 物料名稱先查實際物料主檔；唯一命中才讀庫存，多筆命中回 `NEEDS_CLARIFICATION` 並列出候選，零筆回 `NOT_FOUND`。
- 多物料庫存以批次唯讀工具查詢。只有原問題中明確出現的物料代碼可查；重複代碼去重，未知代碼列入 `unmatchedIdentifiers`，不會阻擋其他有效代碼的結果。
- 物料與供應商名稱都先查詢實際主檔；唯一匹配才查詢業務資料，多筆匹配回 `NEEDS_CLARIFICATION`，查無資料回 `NOT_FOUND`。英文、中英混合及含連字號或數字的名稱保留原始文字比對。
- 目前報價只取啟用中的 `SupplierMaterial`、供應商與物料主檔，不使用推薦快照、歷史採購單或文件舊價格。計價單位取物料基本單位。平均價格以物料、幣別及計價單位分組，計算同組有效報價的算術平均；不同幣別或單位不得混算。
- 供應商評價直接回傳主檔中的啟用狀態、可靠度與品質評分；不合成未核定的總評分。若原問題明確列出多個供應商代碼，所有代碼逐項查詢；查無的代碼逐項列入 `unmatchedIdentifiers`。模型額外提供的代碼會在工具邊界拒絕。
- 單筆 PO 以完整 PO Number 精確查詢。正式 `status` 與 Kanban `stage` 分欄回傳，總額由後端依 PO 明細快照按幣別加總。`REJECTED` 與 `CANCELLED` 的下一步依正式狀態標示為已終止；其他狀態才依 Stage 提示，不推測合法空 Stage 的流程位置。
- `NOT_FOUND` 代表查詢成功且沒有符合條件的業務資料。工具或資料服務失敗回 `AI_QUERY_FAILED`，不得轉成 `NOT_FOUND`；Provider 不可用、逾時及模型回覆驗證失敗仍分別使用既有 AI 錯誤碼。
- `sources` 去重後只提供可讀來源名稱、文件版本／引用（文件來源適用）及現有資源連結，不把工具執行細節交給使用者。

### Rules

- AI 不自行計算核心採購數字。
- AI 不可直接 Approve。
- AI 不可直接 Order。
- AI 不可直接 Receive。
- AI 建立 PO 時只能建立 DRAFT。
- AI Tool 呼叫與 REST API 共用相同 Application Service。
- 查詢 Tools 僅為唯讀；識別碼須逐項由原始問題驗證，不能藉由模型生成代碼擴大查詢範圍。
- `summary` 僅允許依工具結果狀態選用經核准的安全句型，不得包含數值、識別碼、狀態或其他業務事實。這是受限制的自然語言摘要，不代表任意 LLM 改寫已完成語意證明。
- V2 一般採購問答不得以推薦或供應商排名替代現行報價、供應商主檔、庫存或 PO 資料。

---

# 21. AI Draft Creation

SP-721 採「提議 → 使用者審查 → 明確確認 → DRAFT」。模型只會取得既有六個唯讀工具；不註冊寫入工具。V1 要求請求提供所有寫入欄位作為明確依據，模型產生的 JSON 必須與這些欄位完全一致；缺少欄位回補充資訊，不猜測數字或識別碼。

## POST /api/v1/ai/purchase-draft-proposals

權限：`PROCUREMENT_STAFF`、`ADMIN`。未登入 401，其餘角色 403。Provider 未配置、逾時或回覆無效依既有 AI 503 契約。請求不會建立採購單。

```json
{
  "message": "請整理 MAT-001 的採購草稿供我確認",
  "draft": {
    "supplierId": 12,
    "expectedDeliveryDate": "2026-10-20",
    "items": [{
      "materialId": 7,
      "quantity": 200,
      "unitPrice": 50,
      "recommendationId": 91,
      "supplierRecommendationResultId": 18,
      "supplierOverrideReason": null,
      "quantityOverrideReason": null
    }]
  }
}
```

成功回應：

```json
{
  "status": "PENDING_CONFIRMATION",
  "proposalId": "b908da27-64e0-4d46-bf21-7ba779c60181",
  "draft": { "supplierId": 12, "expectedDeliveryDate": "2026-10-20", "items": [{ "materialId": 7, "quantity": 200, "unitPrice": 50, "recommendationId": 91, "supplierRecommendationResultId": 18, "supplierOverrideReason": null, "quantityOverrideReason": null }] },
  "expiresAt": "2026-10-11T09:10:00Z",
  "confirmationToken": "<後端核發的隨機憑證>",
  "missingFields": []
}
```

必要的 `supplierId`、`items` 或明細 `materialId`、`quantity`、`unitPrice` 缺漏時，回 `200`、`status=NEEDS_CLARIFICATION` 與 `missingFields`；`proposalId`、`draft`、`expiresAt`、`confirmationToken` 為 `null`。無效數值與目前業務資料不合規時使用既有錯誤碼。`expectedDeliveryDate` 可為 `null`，沿用一般 DRAFT 規則。

## POST /api/v1/ai/purchase-draft-proposals/{proposalId}/confirm

使用者須顯示完整 `draft` 並明確提交下列內容；若要修改，需重新產生 Proposal。

```json
{
  "confirmationToken": "<提議回應中的憑證>",
  "draft": { "supplierId": 12, "expectedDeliveryDate": "2026-10-20", "items": [{ "materialId": 7, "quantity": 200, "unitPrice": 50, "recommendationId": 91, "supplierRecommendationResultId": 18, "supplierOverrideReason": null, "quantityOverrideReason": null }] }
}
```

成功回 `200` 與一般 `PurchaseOrderResponse`，其中 `status=DRAFT`。確認服務只從 MySQL 已保存的 Payload 建立採購單，不採用 Client 提交的寫入值；先比對 Client Payload 與原提議一致，再於同一 MySQL Transaction 呼叫 `PurchaseOrderService.createDraft()`。提議以目前使用者 ID 綁定，預設有效 10 分鐘（`supplypilot.ai.draft-proposal-ttl`，上限 1 小時）；MySQL 只保存 SHA-256 Token Hash。`SELECT FOR UPDATE` 序列化同一 Proposal 的確認；成功後保存 `purchase_order_id` 與 `consumed_at`，失敗則整筆交易 Rollback。

錯誤：`AI_PROPOSAL_NOT_FOUND` 404、`AI_PROPOSAL_EXPIRED` 409、`AI_PROPOSAL_ALREADY_CONFIRMED` 409、`AI_CONFIRMATION_INVALID` 400、跨使用者 `ACCESS_DENIED` 403。重送成功確認不會建立第二張採購單。

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

## POST /api/v1/document-queries（文件 RAG）

權限：`PROCUREMENT_STAFF`、`PROCUREMENT_MANAGER`、`ADMIN`。請求中的 `question` 必填、不可空白，長度上限 2,000 字元；後端先依出站規則檢查問題，再交給 `ChatClient → RetrievalAugmentationAdvisor → VectorStoreDocumentRetriever → PgVectorStore`。Embedding 查詢逾時或 Chat Provider 失敗使用 `AI_TIMEOUT`／`AI_PROVIDER_UNAVAILABLE`，不退回舊 MySQL lexical retrieval。

```json
{"question":"SUP-001 的 MAT-001 標準交期是多久？"}
```

有經後端即時來源核對的 Context，且模型回傳非空答案時回 `200`。後端回傳已驗證的來源清單，但不逐句驗證生成答案是否受來源支持；系統提示仍要求模型只根據 Context 作答。沒有可用來源、模型回傳空答案或 `INSUFFICIENT_EVIDENCE` 時，回 `INSUFFICIENT_EVIDENCE`：

```json
{
  "status": "ANSWERED",
  "answer": "SUP-001 的 MAT-001 標準交期為 ORDERED 後 5 個曆日。",
  "sources": [{
    "documentId": 42,
    "documentKey": "DOC-SUP-001-CONTRACT",
    "documentType": "SUPPLIER_CONTRACT",
    "title": "SUP-001 供貨契約",
    "version": "v2",
    "supplierId": 7,
    "supplierCode": "SUP-001",
    "supplierName": "福爾摩沙原料工坊",
    "chunkIndex": 0,
    "startOffset": 0,
    "endOffset": 96,
    "excerpt": "SUP-001 supplies MAT-001 ...",
    "relevanceScore": 0.87
  }]
}
```

找不到通過即時來源狀態、核准與出站政策檢查的檢索候選，或模型依 Context 回傳空答案／`INSUFFICIENT_EVIDENCE` 時，API 回 `200`、`status=INSUFFICIENT_EVIDENCE`、`answer=null`、`sources=[]`。Context 是否足以支持回答由模型依 system prompt 判斷；後端不再用答案支持驗證器檢查生成結果。Sources 的 document/version/chunk/offset 與 excerpt 仍由後端依 MySQL 權威資料建立，不接受模型自報來源。若 `SEMANTIC_READ_ENABLED=false`，不執行檢索而回 HTTP 503 `AI_PROVIDER_UNAVAILABLE`；透過 `/api/v1/ai/query` 呼叫時，既有工具錯誤包裝碼為 `AI_QUERY_FAILED`。

文件問答只會把通過來源及適用性檢查的 Context 提供給模型。有限多文件摘要或供應商比較仍應明確指定對象與要比較的事實；模型依 system prompt 僅根據 Context 作答，後端不驗證生成後的數值、差額或答案主張。Demo Catalog 的歷史／現行適用性規則另按明確日期與文件期間限制候選來源。

索引由 MySQL 文件儲存與出站核准流程觸發：核准來源 hash 後建立持久索引工作；worker 重新檢查 READY、版本、hash、generation、Demo 資格與出站政策，再用 Spring AI 文件讀取／chunking 寫入 `semantic_rag_vectors`。完整發布成功後，同步維護 PostgreSQL `semantic_chunks` 相容投影，讓既有 Agentic SQL 讀取契約可延續。`semantic_chunks`、索引登錄表與歷史資料不刪除；新文件問答只從 `semantic_rag_vectors` 檢索。PgVectorStore migration／相容投影與 Agentic SQL existing contract tests 已在 Testcontainers Maven suite 通過；部署資料 Backfill、正式文件數量／metadata 完整性對帳與真實文件查詢 E2E 尚未執行。

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
STOCK_TRANSACTION_NOT_FOUND
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

## Receiving Read（Backend B 前置依賴）

```text
GET   /api/v1/receiving/purchase-orders
GET   /api/v1/receiving/purchase-orders/{id}
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

## V2-102：人員刪除、負責人與 PO 歷史快照

- `DELETE /api/v1/users/{id}` 需要 `USER_MANAGEMENT`；成功回 `204`，找不到回 `404`，無權限回 `403`，未登入回 `401`。最後一位啟用且具人員管理能力的帳號受併發鎖保護，不可刪除。
- `GET /api/v1/users/assignable` 回傳啟用中的 ADMIN 業務帳號最小指派資料，供採購操作使用。
- `POST /api/v1/purchase-orders/{id}/claim` 將未指派採購單指派給目前登入者；`POST /api/v1/purchase-orders/{id}/assign` 接受 `{ "userId": <id> }`。兩者都保留正式採購單狀態，並以列鎖與 `@Version` 防止競態覆寫。無效或停用帳號不可指派。
- PO 回應提供 `assignedTo`、`assignedToDisplayName`、`assignedToUsername`；未指派時三者為 `null`。歷史建立、核准、駁回、下單及收貨操作者使用 `*UserId`、`*Username`、`*DisplayName` 快照欄位與既有事件時間。
- V24 讓人員 FK 刪除時置空，另存非 FK 的歷史 ID 快照；PO 操作者的 username/display name 由舊資料回填，無法還原既有資料從未記錄的原姓名。

## V2-201：直接建立採購草稿

- `POST /api/v1/purchase-orders` 直接建立正式 `DRAFT`，要求有效供應商與至少一筆有效品項；數量為正、單價非負，使用既有 `DECIMAL(19,4)` 精度。`expectedDeliveryDate` 可省略或為 `null`；核准命令仍由 Domain 要求該日期。
- `PATCH /api/v1/purchase-orders/{id}` 只允許 `DRAFT`，可更換有效供應商、完整替換品項及設定/清除期望到貨日。Draft Edit 與核准先取得同一 PO 的 `PESSIMISTIC_WRITE` 列鎖並在鎖內檢查狀態，避免彼此交錯造成覆寫；`@Version` 仍保留作版本保護。失敗時交易不提交部分資料。
- V2 帳號是 `ADMIN` 業務角色。Demand 的建立/編輯/取消僅保留舊角色相容，V2 `ADMIN` 無法寫入；採購 Recommendation 建立亦不授權 V2 `ADMIN`。歷史 Demand/Recommendation 查詢仍可供舊資料相容，資料列與外鍵保留。
- Draft Edit 不讀取或重驗歷史 Recommendation 關聯以決定 PO 是否可編輯。既有 PO item 的推薦 ID、供應商/數量快照與覆寫原因不可透過草稿編輯改寫或清除；新手動 PO 不需這些欄位。

## V2-202：採購單駁回、取消與第 5/6 欄保護

`POST /api/v1/purchase-orders/{id}/reject` 僅將 DRAFT 駁回為 REJECTED；原因必填。`POST /api/v1/purchase-orders/{id}/cancel` 接受 `{ "reason": "..." }`，原因長度 1–2000 且不可空白；僅 ADMIN 可將 APPROVED 或 `orderedProgress=FOLLOW_UP` 的 ORDERED PO 取消為 CANCELLED。缺少/無效輸入回 400，未登入回 401，無權限回 403，PO 不存在回 404，狀態或階段不允許回 `409 INVALID_PO_STATUS_TRANSITION`。取消回傳一般 PO 回應，包括 `cancelReason`、`cancelledBy`、`cancelledByUsername`、`cancelledByDisplayName`、`cancelledAt`；操作者由登入帳號、時間由 Backend Clock 產生。

`POST /api/v1/purchase-orders/{id}/begin-receiving-inspection` 無 Request body，僅 ADMIN 可將 ORDERED 的 `FOLLOW_UP` 或 `LEGACY_UNKNOWN` 設為 `RECEIVING_INSPECTION`。這是供 #141/#142 沿用的受控第 5→6 欄契約，不接受任意 Stage 輸入。PO 回應提供 `orderedProgress`；對非 ORDERED 狀態為 `null`。CANCELLED 為終態，不能再次取消、核准、下單、收貨或指派。

PO 狀態命令（核准、駁回、下單、開始驗收、取消、收貨）先以 `findByIdForUpdate()` 鎖 PO。收貨取得 PO 列鎖後才寫 Inventory 與 StockTransaction；任一品項失敗整筆回滾。Incoming Inventory 仍僅加總 ORDERED，因此取消後自動排除且不改實際庫存或交易記錄。

## V2-203：期望到貨日與供應商承諾交期

一般 `PurchaseOrderResponse`（Detail 與 List）提供 `expectedDeliveryDate` 與 `supplierPromisedDeliveryDate`。前者是公司核准的期望到貨日，僅 DRAFT Draft Edit 可修改；後者是供應商目前承諾日期，無承諾時 API 明確回傳 `null`，不會以期望日代替。

`PUT /api/v1/purchase-orders/{id}/supplier-promised-delivery-date` 接受 `{ "supplierPromisedDeliveryDate": "2026-11-05" }`，或以 `{ "supplierPromisedDeliveryDate": null }` 清除目前承諾。省略欄位、未知欄位及無效日期回 `400 VALIDATION_FAILED`。僅有採購業務權限的使用者可呼叫；DRAFT、RECEIVED、REJECTED、CANCELLED 回 `409 INVALID_PO_STATUS_TRANSITION`，APPROVED 與 ORDERED 可更新。成功回 `200` 及完整 PO 回應。

更新使用 Service 交易、`findByIdForUpdate()` PO 列鎖與既有 `@Version`，不接收 Client 操作者、狀態或核准日期。Flyway V26 新增 nullable `supplier_promised_delivery_date`，既有資料保持 `NULL`，不以舊 `expected_delivery_date` 回填。逾期仍是 `status=ORDERED AND expectedDeliveryDate < 台灣目前日期`；更新或清除承諾日不影響逾期判定。Backend Clock 使用 `Asia/Taipei` 計算業務日期；事件時間仍以 Instant 保存。

## V2-204：全數驗收、整筆收貨與付款確認

第 5→6 欄沿用 `POST /api/v1/purchase-orders/{id}/begin-receiving-inspection`。PO 仍為 `ORDERED`，`orderedProgress` 設為 `RECEIVING_INSPECTION`；此命令不動庫存與 StockTransaction。驗收紀錄透過 `PUT /api/v1/purchase-orders/{id}/receiving-inspection` 保存 `{ "allItemsReceived": true, "allQuantitiesConfirmed": true, "allQualityAccepted": true, "note": null }`。三項均由使用者明確提交；任一為 `false` 時可保留異常備註，但收貨服務會拒絕入庫。一般 PO GET 回應及收貨專用 GET 回應都提供目前驗收旗標、備註與最後驗收者/時間快照。

既有 `POST /api/v1/purchase-orders/{id}/receive` 仍為正式收貨命令，但後端只接受 `status=ORDERED`、`orderedProgress=RECEIVING_INSPECTION` 且三項驗收旗標全為 true、存在驗收操作者快照與時間的 PO。第 5 欄、未到貨、短收、數量未確認、品質不合格、未知舊階段或未曾提交驗收的舊資料均回 `409 RECEIVING_INSPECTION_INCOMPLETE` 或狀態錯誤，不可由直接呼叫 `/receive` 繞過。成功時仍在單一交易內按「鎖 PO → 寫 Inventory → 建 StockTransaction → 更新 PO」執行；失敗整筆 rollback，重複收貨回 409。

`POST /api/v1/purchase-orders/{id}/confirm-payment` 只接受 `RECEIVED` 且尚未確認付款的 PO；`POST /api/v1/purchase-orders/{id}/revoke-payment-confirmation` 只接受 `RECEIVED` 且目前已有付款確認的 PO。兩者無 request body，操作者與時間由登入帳號及 Backend Clock 取得。一般採購單回應包含 `paymentConfirmed`、付款確認者快照與 `paymentConfirmedAt`；倉庫專用白名單回應不包含付款狀態或付款操作者資料。確認或撤銷均不改 PO `RECEIVED`、Inventory、StockTransaction；重複或錯誤狀態回 `409 INVALID_PO_STATUS_TRANSITION`。V27 將既有 PO 預設為未驗收、未付款，不將舊 ORDERED／RECEIVED 資料推測成新狀態。

所有驗收、收貨、付款確認與撤銷均由 Service `@PreAuthorize` 保護，並先取得 PO `PESSIMISTIC_WRITE` 列鎖；Request 不接受 Client 狀態、操作者 ID 或時間。未登入為 401，角色不足為 403，非法 body 為 400。未知寫入結果時 Client 需重新查詢 Backend 狀態，不自動重送。

## V2-205：持久化 Kanban Stage 與受控轉移

一般 `PurchaseOrderResponse` 新增 nullable `stage`，值為 `NEW_REQUEST`、`SOURCING`、`APPROVAL`、`APPROVED_PENDING_ORDER`、`ORDER_FOLLOW_UP`、`RECEIVING_INSPECTION`、`PAYMENT_RECONCILIATION`、`COMPLETED`。Stage 代表目前看板欄位，`status` 仍是正式採購單狀態；終止 PO 與無法判定舊階段的資料回傳 `stage: null`，不得當作活動看板卡片。

`POST /api/v1/purchase-orders/{id}/begin-sourcing` 僅允許 Stage 1→2；`POST /api/v1/purchase-orders/{id}/submit-for-approval` 僅允許 Stage 2→3。兩者都無 Request Body，呼叫動作固定，不接受 Client Stage 或 PO Status；重複或並發送出相同動作時，後續請求因 Stage 前置條件已變而回 `409 INVALID_PO_STATUS_TRANSITION`。Stage 3→4 使用既有核准 API，4→5 使用下單 API，5→6 使用開始驗收 API，6→7 使用整筆收貨 API，7→8 與 8→7 分別使用付款確認及撤銷 API。其他跳關、倒退、錯誤 status/stage 組合均回 `409 INVALID_PO_STATUS_TRANSITION`（收貨條件不完整仍依既有錯誤契約）。`ORDERED + LEGACY_UNKNOWN + stage=NULL` 不推測歷史欄位；僅 ADMIN 可呼叫既有 `POST /api/v1/purchase-orders/{id}/begin-receiving-inspection`，明確將其 disposition 到 Stage 6，原子更新 `orderedProgress` 與 `stage`，且不改庫存或交易。非 ADMIN 為 403，重複處置或其他不符合狀態的命令為 409。Stage 前進僅允許 `PROCUREMENT_STAFF`、`PROCUREMENT_MANAGER`、`ADMIN`，並透過 `@Transactional` 與 `findByIdForUpdate()` 序列化；狀態與 Stage 僅由 Aggregate 命令更新。

V28 Migration 將 DRAFT、APPROVED、已知 OrderedProgress 與明確付款事實映射到對應 Stage；ORDERED + `LEGACY_UNKNOWN` 保持 NULL，避免猜測歷史第 5/6 欄位置。此類資料從活動 Stage 集合排除，直到 ADMIN 使用 `begin-receiving-inspection` 明確將其處置進 Stage 6；不接受任意 Stage 輸入。一般 PO GET／List 回應會保存並提供目前 `stage`，重讀後仍以 Backend 為準。Stage 不接受 PATCH 欄位，任何直接修改 PO `status` 的請求亦不在 API 契約中。

## V2-302 Semantic Index Job API（Issue #146）

以下端點限 ADMIN 角色，遵守既有 JWT／角色授權。回應僅包含索引工作狀態與安全錯誤代碼，不含文件內容、Provider 回應本文或敏感資訊。

| Method | Path | 行為 |
|---|---|---|
| POST | /api/v1/semantic-index/documents/{id}/approve-outbound | 管理員審查該文件當前來源 hash；核准後建立持久化索引工作。Worker 會在送往 EmbeddingModel 前重驗狀態、版本與 outbound policy。 |
| POST | /api/v1/semantic-index/documents/{id}/exclude-outbound | 管理員明確排除目前文件來源，撤銷舊 generation 的查詢資格。 |
| GET | /api/v1/semantic-index/jobs?limit=50 | 讀取近期索引工作；limit 預設 50，範圍 1–200。 |
| POST | /api/v1/semantic-index/jobs/{jobId}/retry | 將仍符合現行文件來源、版本、核准及模型 generation 的 FAILED job 重設為 QUEUED。 |
| POST | /api/v1/semantic-index/backfill?afterId=0&limit=25 | 管理員執行有界 cursor backfill／恢復作業；一般核准流程會自動排入單文件工作，不要求日常按全域 Gate。 |
| GET | /api/v1/semantic-index/readiness | 管理員查詢現行索引 readiness 與排除／失敗計數。 |
| POST | /api/v1/semantic-index/gate-audit | 管理員記錄 readiness audit；不可用於取代本遷移需完成的正式資料核對。 |

Job response 欄位：id、documentId、version、status、attempts、maxAttempts、retryCount、nextAttemptAt、failureCode、updatedAt。狀態包括 QUEUED、PROCESSING、RETRY_WAIT、SUCCEEDED、FAILED、SUPERSEDED。重複核准事件依文件、版本、來源 hash、模型 generation、文件 generation 去重。重試非 FAILED job 為冪等回應（若仍在執行或等待中），不會新增 provider 呼叫。
