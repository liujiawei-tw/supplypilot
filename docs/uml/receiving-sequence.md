# SupplyPilot — Receiving Sequence Diagram

> **文件目的**：描述 Purchase Order Receiving 的 Security、Domain State Guard、Inventory 更新、StockTransaction Audit、Optimistic Lock 與 All-or-Nothing Transaction。  
> **視覺化文件**：`receiving-sequence.html`

---

# 1. Endpoint

```text
POST /api/v1/purchase-orders/{id}/receive
```

允許 Role：

```text
WAREHOUSE_STAFF
ADMIN
```

V1：

```text
Full Receiving Only
```

不支援：

```text
Partial Receiving
```

---

# 2. Participants

```text
Client
JwtAuthenticationFilter
PurchaseOrderController
ReceivingService
PurchaseOrderRepository
InventoryRepository
Inventory
StockTransactionRepository
PurchaseOrder
CurrentUserProvider
```

---

# 3. Why ReceivingService Is Separate

Receiving 不只是修改：

```text
PurchaseOrder.status
```

而是同時改：

```text
PurchaseOrder
Inventory[]
StockTransaction[]
```

因此它是：

```text
Cross-Aggregate Use Case
```

由：

```text
ReceivingService
```

負責 orchestrate。

---

# 4. Security

流程：

```text
Client
↓
JwtAuthenticationFilter
↓
Validate JWT
↓
Validate WAREHOUSE_STAFF / ADMIN
↓
Controller
```

錯誤：

```text
401 UNAUTHENTICATED
403 ACCESS_DENIED
```

---

# 5. Audit User

Receiving User：

```text
CurrentUserProvider
```

取得。

Client 不得傳：

```text
receivedBy
createdBy
```

---

# 6. Transaction Boundary

`ReceivingService.receive()`：

```java
@Transactional
```

Atomic Scope：

```text
PurchaseOrder
+
all affected Inventory rows
+
all StockTransaction rows
```

全部成功才 Commit。

---

# 7. Load Purchase Order

首先：

```text
PurchaseOrderRepository.findById(...)
```

取得：

```text
PurchaseOrder
PurchaseOrderItems
version
```

若不存在：

```text
404 PURCHASE_ORDER_NOT_FOUND
```

---

# 8. State Guard

Domain：

```text
PurchaseOrder
```

必須確認：

```text
status = ORDERED
```

只有：

```text
ORDERED → RECEIVED
```

合法。

若：

```text
DRAFT
APPROVED
REJECTED
RECEIVED
```

呼叫 receive：

```text
409 INVALID_PO_STATUS_TRANSITION
```

---

# 9. Inventory Update

對每個：

```text
PurchaseOrderItem
```

執行：

```text
load Inventory
↓
Inventory.receive(item.quantity)
↓
save
```

`Inventory.receive()`：

```text
quantity = quantity + received quantity
```

Entity 自己維護 quantity invariant。

---

# 10. StockTransaction

每一筆 PO Item 同時建立：

```text
StockTransaction
```

Type：

```text
PURCHASE_RECEIVE
```

Reference：

```text
material
purchaseOrder
quantity
createdBy
createdAt
```

目的：

```text
Inventory movement audit
```

---

# 11. Inventory and StockTransaction Must Be Atomic

禁止：

```text
Inventory increased
but
StockTransaction missing
```

因此：

```text
Inventory update
+
StockTransaction insert
```

必須在同一 Transaction。

若 StockTransaction insert 失敗：

```text
Inventory update rollback
```

---

# 12. Full Receive Example

PO：

```text
Material A +100
Material B +200
Material C +300
```

正常：

```text
Inventory A +100
Inventory B +200
Inventory C +300
3 StockTransaction created
PO → RECEIVED
```

然後 Commit。

---

# 13. Failure Example

若：

```text
A success
B success
C fails
```

結果：

```text
A rollback
B rollback
C unchanged
all StockTransactions rollback
PO remains ORDERED
```

不能留下 Partial Receiving。

---

# 14. PurchaseOrder Status Update

所有 Item 都完成後：

```text
PurchaseOrder.receive(user, now)
```

設定：

```text
status = RECEIVED
receivedBy
receivedAt
```

然後才準備 Commit。

---

# 15. PurchaseOrder Optimistic Lock

`PurchaseOrder`：

```java
@Version
```

防：

```text
duplicate receiving
concurrent lifecycle update
```

例：

```text
Request A reads ORDERED version=3
Request B reads ORDERED version=3
```

A：

```text
ORDERED → RECEIVED
version 3 → 4
commit
```

B：

```text
tries update version 3
```

結果：

```text
OptimisticLockException
```

整個 B Transaction：

```text
ROLLBACK
```

回：

```text
409 CONCURRENT_MODIFICATION
```

---

# 16. Inventory Optimistic Lock

`Inventory` 也使用：

```java
@Version
```

避免 Receiving 與：

```text
Inventory Import
Manual Adjustment
another Receiving
```

同時更新同一 Material 時 Lost Update。

---

# 17. Inventory Conflict Example

Inventory：

```text
quantity = 100
version = 5
```

Request A Receiving：

```text
+50
```

Request B Adjustment：

```text
-20
```

若 A 先 Commit：

```text
150
version = 6
```

B 使用 version 5：

```text
OptimisticLockException
```

B：

```text
rollback
```

不能悄悄覆蓋成：

```text
80
```

---

# 18. Why No Automatic Retry

V1：

```text
Optimistic Conflict
→ 409
```

不 automatic retry。

原因：

Receiving 是：

```text
high-impact business action
```

自動 retry 可能在使用者不知道最新 Inventory / PO State 的情況下重新執行。

V1 要求：

```text
refresh
review current state
retry explicitly
```

---

# 19. Duplicate Receive Protection

有兩層：

## Layer 1 — State Guard

```text
status must be ORDERED
```

已 RECEIVED：

```text
reject
```

## Layer 2 — Optimistic Lock

處理兩個 concurrent request 都先讀到 ORDERED 的 race condition。

兩層都需要。

---

# 20. Incoming Inventory After Receiving

Recommendation 只把：

```text
ORDERED PO
```

算 Incoming。

Receiving Commit 後：

```text
PO = RECEIVED
```

這張 PO 不再算 Incoming。

因為實際庫存已經：

```text
Inventory += received quantity
```

避免：

```text
Current Inventory
+
same PO Incoming
```

重複計算。

---

# 21. Error Paths

## Purchase Order Missing

```text
404 PURCHASE_ORDER_NOT_FOUND
```

## Invalid Status

```text
409 INVALID_PO_STATUS_TRANSITION
```

## Inventory Missing

這代表資料完整性問題。

處理：

```text
Rollback whole receiving transaction
```

## Inventory Optimistic Conflict

```text
409 CONCURRENT_MODIFICATION
```

## PO Optimistic Conflict

```text
409 CONCURRENT_MODIFICATION
```

## StockTransaction Failure

```text
Rollback all Inventory changes
Rollback PO status
```

## Unexpected Failure

```text
500 INTERNAL_SERVER_ERROR
+
Rollback
```

---

# 22. Transaction Commit Order Concept

程式邏輯：

```text
Load PO
↓
Validate ORDERED
↓
Update all Inventory
↓
Create all StockTransaction
↓
PO.receive()
↓
Flush
↓
Version Checks
↓
COMMIT
```

真正 SQL flush 時機由 JPA 控制。

設計上要求的是：

> 所有變更必須屬於同一個 DB Transaction。

---

# 23. Concurrency Strategy

V1 採：

```text
Optimistic Lock
```

而不是：

```text
PESSIMISTIC_WRITE
SERIALIZABLE
distributed lock
```

理由：

```text
small company workload
low contention expected
simple implementation
good correctness / complexity balance
```

---

# 24. Deadlock / Multi-row Note

Receiving 可能更新多個 Inventory。

未來如果 concurrency 高，可以：

```text
sort PO Items by materialId
```

固定更新順序，降低 lock ordering 問題。

但在 V1 optimistic locking 模式下：

```text
conflict
→ rollback
→ 409
```

即可。

---

# 25. Security Boundary

AI V1 不提供：

```text
Receive Tool
```

即使使用者是：

```text
WAREHOUSE_STAFF
ADMIN
```

Receiving 仍要求：

```text
explicit human API/UI action
```

這是產品安全邊界。

---

# 26. Sequence Summary

```text
Authenticate
↓
Authorize Warehouse/Admin
↓
ReceivingService @Transactional
↓
Load ORDERED PO
↓
For each PO Item:
    Load Inventory @Version
    ↓
    Inventory.receive()
    ↓
    Create PURCHASE_RECEIVE StockTransaction
↓
PO.receive()
↓
Optimistic Version Checks
↓
Commit
↓
RECEIVED
```

任何一步：

```text
fail
```

則：

```text
Rollback PO
Rollback every Inventory update
Rollback every StockTransaction
```

---

# 27. Design Intent

這張圖鎖定 SupplyPilot 最重要的資料一致性規則：

1. Receiving 是 Cross-Aggregate Transaction。
2. Full Receiving 必須 All-or-Nothing。
3. Inventory movement 必須留下 StockTransaction。
4. PO Status 與庫存不可分開 Commit。
5. State Guard 防一般 Duplicate Receive。
6. `PurchaseOrder.@Version` 防 concurrent Duplicate Receive。
7. `Inventory.@Version` 防 Lost Update。
8. Conflict 回 `409`，V1 不 automatic retry。
9. `RECEIVED` PO 不再計入 Incoming Inventory。
10. AI 不允許觸發 Receiving。

---

# 28. UML Core Set Status

目前核心 UML 已完成：

```text
Domain Class Diagram
Recommendation Sequence Diagram
Inventory Import Sequence Diagram
Purchase Order Lifecycle Sequence Diagram
Receiving Sequence Diagram
```

下一階段：

```text
Testing Strategy
```

將依照這些 UML 與既有規格定義：

```text
Unit Test
Service Test
Repository Test
Security Test
Integration Test
Concurrency Test
AI Tool Boundary Test
```
