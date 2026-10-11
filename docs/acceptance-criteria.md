# SupplyPilot — Acceptance Criteria

> **目的**：定義 SupplyPilot V1 核心 Feature 的可驗收條件。  
> **格式**：Given / When / Then。  
> **用途**：開發完成判定、Integration Test、Demo Script、PR Review。

---

# 1. Authentication

**Given** ACTIVE user with valid credentials  
**When** login  
**Then**

```text
200
JWT returned
role returned
```

**Given** invalid password  
**Then**

```text
401 UNAUTHENTICATED
```

---

# 2. Material Creation

**Given** unique material code  
**When** ADMIN creates material  
**Then**

```text
Material created
Inventory(quantity=0) created
same transaction
```

If Inventory creation fails：

```text
Material must rollback
```

---

# 3. Inventory Adjustment

**Given**

```text
Inventory = 100
```

**When**

```text
adjust -30
```

**Then**

```text
Inventory = 70
StockTransaction MANUAL_ADJUSTMENT = -30
```

If resulting quantity < 0：

```text
request rejected
no transaction committed
```

---

# 4. Inventory Import Validate

**Given** valid CSV  
**When** Warehouse validates  
**Then**

```text
Batch = VALIDATED
ImportItems persisted
Inventory unchanged
```

**Given** row errors  
**Then**

```text
Batch = VALIDATION_FAILED
row errors returned in preview
Inventory unchanged
```

---

# 5. Inventory Import Confirm

**Given**

```text
Batch = VALIDATED
```

**When** confirm  
**Then**

```text
all Inventory quantities replaced by snapshot
Batch = IMPORTED
```

If any row update fails：

```text
all Inventory updates rollback
Batch not IMPORTED
```

---

# 6. Demand Aggregation

**Given**

```text
ACTIVE 500
ACTIVE 300
CANCELLED 400
```

within period.

**When** aggregate demand  
**Then**

```text
800
```

---

# 7. Recommendation — Purchase Not Required

**Given**

```text
Demand 100
Safety 20
Inventory 150
Incoming 0
```

**When** create recommendation  
**Then**

```text
Net Requirement = 0
Outcome = PURCHASE_NOT_REQUIRED
Recommendation snapshot persisted
```

---

# 8. Recommendation — Supplier Recommended

**Given**

```text
Demand 1000
Safety 200
Inventory 300
Incoming 150
```

**Then**

```text
Net Requirement = 750
```

Supplier candidate must：

```text
pass hard constraints before ranking
```

At least one qualified supplier：

```text
Outcome = SUPPLIER_RECOMMENDED
all candidate snapshots persisted
rank deterministic
```

---

# 9. Recommendation — No Qualified Supplier

**Given**

```text
Net Requirement > 0
all suppliers fail hard constraint
```

**Then**

```text
Outcome = NO_QUALIFIED_SUPPLIER
201 recommendation created
not HTTP error
```

---

# 10. Incoming Inventory

Count only：

```text
PO = ORDERED
expectedDeliveryDate <= requiredDate
```

Ignore：

```text
DRAFT
APPROVED
RECEIVED
REJECTED
late ORDERED PO
```

---

# 11. PO Draft

**Given** valid supplier / items  
**When** Buyer creates Draft  
**Then**

```text
status = DRAFT
PO + all Items persisted atomically
unique poNumber generated
```

No duplicate material in same PO.

---

# 12. Recommendation Override

If selected supplier differs：

```text
supplierOverrideReason required
```

If quantity differs：

```text
quantityOverrideReason required
```

Without required reason：

```text
400
no change committed
```

---

# 13. PO Approval

E8 對齊驗收：建立／編輯 DRAFT 可留空或清除 `expectedDeliveryDate`；缺交期 Approve 回 `400 EXPECTED_DELIVERY_DATE_REQUIRED`，狀態與稽核維持原值。補齊後可成功 Approve，再以已核准交期 Mark Ordered；Order request 不輸入交期。

**Given**

```text
PO = DRAFT
user = PROCUREMENT_MANAGER
```

**When** approve  
**Then**

```text
PO = APPROVED
approvedBy from SecurityContext
approvedAt set
```

Wrong role：

```text
403
```

---

# 14. PO Rejection

**Given**

```text
PO = DRAFT
```

Reason required.

**Then**

```text
PO = REJECTED
rejectReason persisted
rejectedBy from SecurityContext
rejectedAt set
terminal
```

---

# 15. Mark Ordered

**Given**

```text
PO = APPROVED
expectedDeliveryDate set
```

**When** Buyer orders  
**Then**

```text
PO = ORDERED
orderedBy / orderedAt set
```

Only after commit does it count as Incoming Inventory.

---

# 16. Receiving

E8 對齊驗收：`WAREHOUSE_STAFF`、`ADMIN` 可查專用 ORDERED List / Detail 與 RECEIVED 歷史；其他角色 403、未登入 401。List 日期／物料篩選、分頁與排序有效。Read 與 Receive 成功回應只含受限 DTO，JSON 不得出現單價、金額、推薦評分、覆寫理由及不必要的採購稽核。原 `POST /api/v1/purchase-orders/{id}/receive` 仍完成整張 PO 的原子收貨。

倉管只在專用 Receiving Detail 執行 Receive，不需取得完整 `PurchaseOrderResponse`；ADMIN 依操作用途進入一般採購 PO Detail 或專用 Receiving Detail。Receiving 受限 DTO 的 `isOverdue` 由 Backend 計算，ORDERED 可為 true，RECEIVED 必為 false；含 Items 的分頁 List 須避免 N+1 且保持正確分頁與 `totalElements`。

**Given**

```text
PO = ORDERED
Inventory MAT-001 = 100
PO Item = 50
```

**When** Warehouse receives  
**Then**

```text
Inventory = 150
PURCHASE_RECEIVE +50 created
PO = RECEIVED
```

All in one transaction.

---

# 17. Receiving Rollback

**Given** PO with A/B/C  
**When** C fails during StockTransaction creation  
**Then**

```text
A unchanged
B unchanged
C unchanged
PO remains ORDERED
no receiving StockTransaction committed
```

---

# 18. Duplicate / Concurrent Receiving

E8 對齊驗收：同一 PO 並行收貨僅一次成功，其餘為 409，Inventory 與 StockTransaction 只增加一次；再次提交為 `409 DUPLICATE_RECEIVING`。逾時或未知結果時，UI 先查專用 Receiving Detail 的 ORDERED / RECEIVED 狀態，再提供下一步，不能自動重送。

Two requests read same PO version.

**Then**

```text
one succeeds
one gets 409 CONCURRENT_MODIFICATION
inventory increases once
```

---

# 19. Document Upload

**When** accepted document uploaded  
**Then**

```text
DocumentMetadata = PROCESSING
```

If ingestion succeeds：

```text
READY
```

If ingestion fails：

```text
FAILED
```

Core procurement remains available.

---

# 20. AI Recommendation Explanation

AI must use persisted deterministic result.

AI must not independently change:

```text
net requirement
ranking
PO lifecycle
```

---

# 21. AI Draft

AI may call：

```text
PurchaseOrderService.createDraft()
```

Result：

```text
DRAFT only
```

AI must not expose tools for：

```text
approve
reject
order
receive
inventory adjustment
```

---

# 22. Error Contract

Every standard error returns：

```text
code
message
traceId
timestamp
path
```

Validation may include：

```text
fieldErrors
```

---

# 23. Security

E8 對齊驗收：Sidebar、Route 與 Action 依 Backend 授權矩陣呈現；AI Query 限採購人員、經理與 ADMIN，AI Proposal / Confirm 限採購人員與 ADMIN。Token 過期按 `expiresAt` 驗證與提示；全域 Drawer 不擴大權限，403 顯示未授權畫面。

No JWT：

```text
401
```

Authenticated wrong role：

```text
403
```

Admin cannot bypass Domain lifecycle.

---

# 24. Frontend Core Flow

E8 對齊驗收：Recommendation 使用現有單次計算與 ID Detail，不要求不存在的歷史 List；Inventory 顯示 Stock Levels / Stock Transactions Tabs，交易查詢支援 `materialId`、`type`、`purchaseOrderId`、日期、分頁、排序，且不跨單位加總。CSV Import 保持 Validate → Preview → Confirm。`409 CONCURRENT_MODIFICATION` 顯示警告、重新取得資料並由使用者審閱；`500` 顯示安全訊息與 `traceId`，AI `503` 不阻斷核心流程。

`PROCUREMENT_MANAGER` 進 `/inventory` 時僅顯示並載入 Stock Transactions，不呼叫無權限的 Inventory GET；僅具 Stock Levels 權限者也能正常開頁。Sidebar、預設 Tab、Deep Link、匯入／調整 Action 與連到交易清單的 Detail 連結均須依各自 API 權限。`/inventory/import` 只供倉管與 ADMIN。PO List / Detail 與 Receiving List / Detail 的逾期 Badge 均依各自 Backend `isOverdue`，不在瀏覽器重新計算，且 `RECEIVED` 不顯示目前逾期。

User must be able to complete：

```text
Login
→ Demand
→ Inventory Import
→ Recommendation
→ Ranking
→ Draft
→ Approval
→ Order
→ Receive
→ Inventory / StockTransaction
```

without Swagger/manual DB edits.
