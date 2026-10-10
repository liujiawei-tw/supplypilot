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
