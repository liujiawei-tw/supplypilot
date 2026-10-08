# SupplyPilot — Purchase Order Lifecycle Sequence Diagram

> **文件目的**：描述 Purchase Order 從 DRAFT 建立、DRAFT 修改、Manager Approval / Rejection 到正式 ORDERED 的 Security、Validation、Domain State Guard、Transaction 與 Concurrency Control。  
> **視覺化文件**：`purchase-order-lifecycle.html`

---

# 1. Lifecycle

V1 PurchaseOrder Status：

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
```

合法：

```text
DRAFT → APPROVED
DRAFT → REJECTED
APPROVED → ORDERED
ORDERED → RECEIVED
```

非法：

```text
所有 Backward Transition
所有 Skip Transition
REJECTED → anything
```

`REJECTED` 為 Terminal State。

---

# 2. Participants

```text
Client
JwtAuthenticationFilter
PurchaseOrderController
PurchaseOrderService
SupplierRepository
MaterialRepository
RecommendationRepository
PurchaseOrder
PurchaseOrderRepository
CurrentUserProvider
```

Receiving 另由：

```text
ReceivingService
```

處理，並在獨立 Sequence Diagram 詳述。

---

# 3. Create Draft

Endpoint：

```text
POST /api/v1/purchase-orders
```

Role：

```text
PROCUREMENT_STAFF
ADMIN
```

流程：

```text
Authenticate / Authorize
↓
DTO Validation
↓
PurchaseOrderService.createDraft()
↓
Load Supplier
↓
Load Materials
↓
Load Recommendation references if provided
↓
Business Validation
↓
Create PurchaseOrder DRAFT
↓
Create PurchaseOrderItems
↓
Persist Aggregate
↓
201 Created
```

---

# 4. Create Draft Validation

Service 必須驗證：

```text
Supplier exists
Supplier ACTIVE
Items not empty
Material exists
Material ACTIVE
SupplierMaterial exists / ACTIVE
No duplicated material in PO
quantity > 0
unitPrice >= 0
```

若有 Recommendation：

```text
Recommendation exists
Recommendation.material = PO Item.material
SupplierRecommendationResult belongs to Recommendation
```

---

# 5. Override Validation

若：

```text
PO.supplier != recommendedSupplier
```

則：

```text
supplierOverrideReason required
```

若：

```text
POItem.quantity != recommendedQuantity
```

則：

```text
quantityOverrideReason required
```

Snapshot 欄位由 Backend 建立，不信任 Client。

---

# 6. Create Draft Transaction

Create Draft：

```text
@Transactional
```

Atomic Scope：

```text
PurchaseOrder
+
all PurchaseOrderItems
```

任一 Item 失敗：

```text
Rollback whole PO
```

---

# 7. Draft Editing

Endpoint：

```text
PATCH /api/v1/purchase-orders/{id}
```

Role：

```text
PROCUREMENT_STAFF
ADMIN
```

只有：

```text
status = DRAFT
```

可修改：

```text
supplier
expectedDeliveryDate
items
```

非 DRAFT：

```text
409 PO_NOT_EDITABLE
```

---

# 8. Domain Guard

`PurchaseOrder` 自己保護：

```text
assertEditable()
```

或等價 Domain Rule。

Controller / Service 不應只靠：

```text
if status == ...
```

而讓 Entity 本身毫無保護。

---

# 9. Approve

Endpoint：

```text
POST /api/v1/purchase-orders/{id}/approve
```

Role：

```text
PROCUREMENT_MANAGER
ADMIN
```

流程：

```text
SecurityContext
↓
CurrentUserProvider
↓
Load PO
↓
po.approve(user, now)
↓
Persist
↓
200 APPROVED
```

Domain Guard：

```text
status must be DRAFT
```

記錄：

```text
approvedBy
approvedAt
```

---

# 10. Reject

Endpoint：

```text
POST /api/v1/purchase-orders/{id}/reject
```

Role：

```text
PROCUREMENT_MANAGER
ADMIN
```

Request：

```json
{
  "reason": "Quantity exceeds current purchasing plan."
}
```

Domain / Service Rule：

```text
status = DRAFT
reason not blank
```

Result：

```text
DRAFT → REJECTED
```

`REJECTED` 不 reopen。

需要重做：

```text
Create new DRAFT
```

---

# 11. Mark Ordered

Endpoint：

```text
POST /api/v1/purchase-orders/{id}/order
```

Role：

```text
PROCUREMENT_STAFF
ADMIN
```

Domain Guard：

```text
status = APPROVED
```

Business Rule：

```text
expectedDeliveryDate required
```

成功：

```text
APPROVED → ORDERED
```

記錄：

```text
orderedBy
orderedAt
```

---

# 12. Incoming Inventory Boundary

只有：

```text
ORDERED
```

PO 才開始計入：

```text
Incoming Inventory
```

因此：

```text
DRAFT
APPROVED
REJECTED
```

都不算 Incoming。

Recommendation 透過：

```text
IncomingInventoryQuery
```

取得 ORDERED PO 資料。

---

# 13. RECEIVE Boundary

`ORDERED → RECEIVED` 不由一般 `PurchaseOrderService` 單獨處理。

原因：

Receiving 涉及：

```text
PurchaseOrder
Inventory
StockTransaction
```

因此交給：

```text
ReceivingService
```

並使用跨 Aggregate Transaction。

---

# 14. Optimistic Lock

PurchaseOrder V1 使用：

```java
@Version
```

目的：

```text
Prevent concurrent lifecycle update
Prevent duplicate receiving
```

例如：

```text
Request A
Request B
```

同時讀：

```text
status = ORDERED
version = 3
```

A Commit：

```text
version 3 → 4
```

B 再 Commit：

```text
OptimisticLockException
```

回：

```text
409 CONCURRENT_MODIFICATION
```

---

# 15. Security Rules

Audit User 不由 Client 傳。

例如 Approve：

```text
CurrentUserProvider
↓
approvedBy
```

Order：

```text
CurrentUserProvider
↓
orderedBy
```

Admin 也不能繞過：

```text
Domain Lifecycle
```

例如：

```text
RECEIVED → DRAFT
```

即使 ADMIN 也非法。

---

# 16. Error Paths

## No JWT / Invalid JWT

```text
401 UNAUTHENTICATED
```

## Wrong Role

```text
403 ACCESS_DENIED
```

## Edit Non-DRAFT

```text
409 PO_NOT_EDITABLE
```

## Invalid Transition

```text
409 INVALID_PO_STATUS_TRANSITION
```

## Reject Reason Missing

```text
400 REJECT_REASON_REQUIRED
```

## Expected Delivery Date Missing

```text
400 EXPECTED_DELIVERY_DATE_REQUIRED
```

## Concurrent Update

```text
409 CONCURRENT_MODIFICATION
```

---

# 17. PO Number

Create Draft 時禁止：

```text
SELECT MAX(poNumber) + 1
```

避免 concurrent collision。

使用：

```text
DB-generated ID
→ format display poNumber
```

並保留：

```text
UNIQUE(po_number)
```

---

# 18. Sequence Summary

```text
Create Draft
↓
DRAFT
├── Edit Draft
│
├── Manager Approve
│   ↓
│   APPROVED
│   ↓
│   Procurement Mark Ordered
│   ↓
│   ORDERED
│   ↓
│   ReceivingService
│   ↓
│   RECEIVED
│
└── Manager Reject
    ↓
    REJECTED
    ↓
    terminal
```

---

# 19. Design Intent

這張 Sequence Diagram 強調：

1. PO Status 不是 Client 可以任意 PATCH 的欄位。
2. Lifecycle 使用 Business Action Endpoint。
3. Role Authorization 與 Domain State Guard 是兩件事。
4. Admin 不能 bypass Domain Invariant。
5. PurchaseOrderItem 屬於 PO Aggregate。
6. Recommendation reference / override 必須在 Draft 時驗證。
7. `ORDERED` 才算 Incoming Inventory。
8. Receiving 另由跨 Aggregate Transaction 處理。
9. `@Version` 防止 concurrent lifecycle corruption。
10. Audit User 永遠來自 SecurityContext。

下一組 UML：

```text
Receiving Sequence Diagram
```
