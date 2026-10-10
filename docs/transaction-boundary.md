# SupplyPilot — Transaction Boundary Specification

> **文件目的**：定義 SupplyPilot V1 各 Use Case 的 Transaction Boundary、Atomicity、Rollback、Concurrency Control 與跨系統一致性策略。  
> **文件定位**：V1 Transaction Baseline  
> **上游文件**：
> - `functional-requirements.md`
> - `domain-model.md`
> - `erd.md`
> - `api.md`
> - `package-architecture.md`

---

# 1. Core Principle

SupplyPilot V1 採：

```text
Controller
↓
Application Service
↓
@Transactional
↓
Repository / Domain
```

`@Transactional` 主要放在 Service Layer。

禁止：

```text
Controller @Transactional
Entity @Transactional
DTO @Transactional
```

---

# 2. Transaction Goals

V1 Transaction Design 主要保證：

1. 核心業務操作具有 Atomicity。
2. 跨多 Entity 更新失敗時全部 Rollback。
3. Inventory 與 Audit 不可出現部分成功。
4. Purchase Order Lifecycle 不可被非法跳轉。
5. Recommendation Snapshot 不可只保存部分 Candidate。
6. Inventory Import 必須 All-or-Nothing。
7. Receiving 不可 Duplicate Receive。
8. Concurrency 不可造成 Silent Lost Update。
9. 外部 API / AI / S3 不應長時間佔用 DB Transaction。
10. RAG / Embedding 採 Eventually Consistent。

---

# 3. Default Transaction Propagation

V1 使用 Spring 預設：

```text
Propagation.REQUIRED
```

即：

```text
Existing Transaction
→ Join

No Existing Transaction
→ Create
```

V1 不使用：

```text
REQUIRES_NEW
NESTED
MANDATORY
```

除非未來有明確需求。

---

# 4. Rollback Rule

Business Exception 建議：

```java
public class BusinessException extends RuntimeException
```

Spring 預設：

```text
RuntimeException
→ Rollback
```

因此：

```text
throw BusinessException
```

會觸發 Rollback。

禁止：

```text
catch exception
→ log only
→ normal return
```

否則可能導致錯誤 Transaction 被 Commit。

若 catch：

```text
log
↓
rethrow
```

---

# 5. Create Material Transaction

Use Case：

```text
POST /materials
```

Transaction：

```text
BEGIN

Validate Material Code
↓
Create Material
↓
Save Material
↓
Create Inventory(quantity = 0)
↓
Save Inventory

COMMIT
```

任何一步失敗：

```text
ROLLBACK
```

必須避免：

```text
Material exists
but
Inventory missing
```

---

# 6. Create Supplier Transaction

流程：

```text
BEGIN

Validate code unique
↓
Create Supplier
↓
Save Supplier

COMMIT
```

屬簡單單 Entity Transaction。

---

# 7. Create SupplierMaterial Transaction

流程：

```text
BEGIN

Load Supplier
↓
Load Material
↓
Validate Supplier ACTIVE
↓
Validate Material ACTIVE
↓
Check Supplier + Material duplicate
↓
Create SupplierMaterial
↓
Save

COMMIT
```

DB：

```text
UNIQUE(supplier_id, material_id)
```

仍是最終 concurrency 防線。

---

# 8. Create / Update Demand Transaction

Create：

```text
BEGIN

Load Material
↓
Validate ACTIVE
↓
Validate requiredDate
↓
Create Demand
↓
Save

COMMIT
```

Cancel：

```text
BEGIN

Load Demand
↓
Validate ACTIVE
↓
Demand.cancel()
↓
Save

COMMIT
```

---

# 9. Inventory Import — Revised V1 Design

原設計只有：

```text
InventoryImportBatch
```

無法完整支援：

```text
Validate
→ Preview
→ Confirm
```

因為 Validate 與 Confirm 是不同 HTTP Request。

V1 正式新增：

```text
InventoryImportItem
```

---

# 10. InventoryImportItem

建議欄位：

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

Relation：

```text
InventoryImportBatch
1 : N
InventoryImportItem
```

Purpose：

```text
Persist validated preview rows
```

Confirm 時直接讀取已驗證的 Item。

---

# 11. Inventory Import Validate Transaction

Use Case：

```text
POST /inventory-imports/validate
```

流程：

```text
Parse CSV
↓
Validate rows
↓
BEGIN

Create InventoryImportBatch
↓
Persist InventoryImportItems
↓
Set Batch status:
    VALIDATED
    or
    VALIDATION_FAILED

COMMIT
```

此階段：

```text
Inventory quantity MUST NOT change
```

Validation Failure 也可以保存 Batch + Item Error，作為 Preview / Audit。

---

# 12. Inventory Import Confirm Transaction

Use Case：

```text
POST /inventory-imports/{batchId}/confirm
```

Precondition：

```text
Batch.status = VALIDATED
```

Transaction：

```text
BEGIN

Load Batch
↓
Validate not already imported
↓
Load all valid InventoryImportItems
↓
for each item:
    Load Inventory
    Replace Inventory.quantity
↓
Batch.status = IMPORTED

COMMIT
```

任何一筆失敗：

```text
ROLLBACK ENTIRE BATCH
```

例如：

```text
100 rows
1~99 success
row 100 fail
```

結果：

```text
0 inventory changes committed
```

All-or-Nothing。

---

# 13. Inventory Import Failure

若 Confirm 中發生技術失敗：

```text
ROLLBACK
```

Batch 狀態處理有兩種策略。

V1 建議簡化：

```text
Transaction rollback
↓
Batch remains VALIDATED
```

由 API 回：

```text
IMPORT_FAILED
```

若要持久化：

```text
IMPORT_FAILED
```

狀態，需要額外獨立 transaction，V1 不建議增加這個複雜度。

因此正式 V1 建議：

> `IMPORT_FAILED` 可保留 Enum，但 Confirm rollback 後不強制寫入 DB；失敗資訊以 Log / Error Response 為主。

---

# 14. Manual Inventory Adjustment Transaction

Use Case：

```text
POST /inventory/{materialId}/adjustments
```

Transaction：

```text
BEGIN

Load Inventory
↓
Inventory.adjust(delta)
↓
Validate resulting quantity >= 0
↓
Save Inventory
↓
Create StockTransaction(MANUAL_ADJUSTMENT)

COMMIT
```

StockTransaction 失敗：

```text
Inventory update rollback
```

不可出現：

```text
Inventory changed
without
Audit record
```

---

# 15. Recommendation Snapshot Transaction

Use Case：

```text
POST /recommendations
```

主要流程：

```text
Load Material
Read Demand
Read Inventory
Read Incoming PO
Read Supplier / SupplierMaterial
↓
Calculate Net Requirement
↓
Evaluate Constraints
↓
Calculate Supplier-specific Quantity
↓
Rank
↓
BEGIN

Create PurchaseRecommendation
↓
Create all SupplierRecommendationResult
↓
Persist full Snapshot

COMMIT
```

V1 實作上可簡化為單一：

```java
@Transactional
createRecommendation(...)
```

---

# 16. Recommendation Atomicity

若：

```text
PurchaseRecommendation saved
```

但：

```text
SupplierRecommendationResult #3 insert fails
```

則：

```text
Recommendation
+
all previously inserted results
```

必須全部 rollback。

禁止留下：

```text
Partial Recommendation Snapshot
```

---

# 17. Recommendation Read Consistency

Recommendation 讀取：

```text
Demand
Inventory
ORDERED PO
SupplierMaterial
Supplier
```

V1 使用 DB 預設 isolation / Read Committed。

接受：

```text
short-lived concurrent data change
```

V1 不做：

```text
SERIALIZABLE
Pessimistic Lock
Global Snapshot Isolation
```

這些留給後續 hardening。

---

# 18. Create Purchase Order Draft Transaction

Use Case：

```text
POST /purchase-orders
```

流程：

```text
BEGIN

Load Supplier
↓
Validate Supplier ACTIVE
↓
Validate Items
↓
Load Material
↓
Load Recommendation refs if provided
↓
Validate Recommendation / Result ownership
↓
Validate override reasons
↓
Create PurchaseOrder
↓
Create PurchaseOrderItems
↓
Persist Aggregate

COMMIT
```

任一 Item fail：

```text
whole PO rollback
```

---

# 19. PO Number Generation

禁止：

```text
SELECT MAX(po_number) + 1
```

避免 concurrent collision。

V1 建議：

```text
DB-generated internal id
↓
Format display poNumber
```

例如：

```text
PO-20261008-000123
```

DB Unique Constraint：

```text
UNIQUE(po_number)
```

仍保留。

---

# 20. Update Draft Transaction

```text
BEGIN

Load PO
↓
Validate status = DRAFT
↓
Modify Supplier / Items / Delivery Date
↓
Revalidate Recommendation refs
↓
Revalidate Override reasons
↓
Persist Aggregate

COMMIT
```

非 DRAFT：

```text
PO_NOT_EDITABLE
409
```

---

# 21. Approve PO Transaction

```text
BEGIN

Load PO
↓
PO.approve()
↓
approvedBy
approvedAt
↓
Persist

COMMIT
```

合法：

```text
DRAFT → APPROVED
```

---

# 22. Reject PO Transaction

```text
BEGIN

Load PO
↓
Validate reject reason
↓
PO.reject()
↓
rejectReason
rejectedBy
rejectedAt
↓
Persist

COMMIT
```

合法：

```text
DRAFT → REJECTED
```

---

# 23. Mark Ordered Transaction

```text
BEGIN

Load PO
↓
Validate APPROVED
↓
Validate expectedDeliveryDate
↓
PO.markOrdered()
↓
orderedBy
orderedAt
↓
Persist

COMMIT
```

只有 Commit 成功後：

```text
PO starts counting as Incoming Inventory
```

---

# 24. Receiving Transaction

Receiving 是 V1 最重要的 Transaction Boundary。

Use Case：

```text
POST /purchase-orders/{id}/receive
```

Transaction：

```text
BEGIN

Load PurchaseOrder
↓
Validate status = ORDERED
↓
for each PurchaseOrderItem:
    Load Inventory
    Inventory.receive(item.quantity)
    Save Inventory
    Create StockTransaction(PURCHASE_RECEIVE)
↓
PO.receive()
↓
receivedBy
receivedAt

COMMIT
```

任何一步失敗：

```text
ROLLBACK EVERYTHING
```

---

# 25. Receiving Atomicity Example

PO：

```text
Item A +100
Item B +200
Item C +300
```

若：

```text
A success
B success
C fail
```

必須：

```text
Inventory A unchanged
Inventory B unchanged
Inventory C unchanged
PO remains ORDERED
No StockTransaction committed
```

禁止 Partial Receiving。

V1 Full Receiving Only。

---

# 26. Duplicate Receiving Guard

第一層：

```text
PO.status must be ORDERED
```

第一次：

```text
ORDERED → RECEIVED
```

第二次：

```text
status != ORDERED
→ reject
```

但單純 State Guard 無法完全防 concurrent requests。

因此 V1 正式啟用 Optimistic Lock。

---

# 27. Optimistic Lock — Revised V1 Decision

原計畫：

```text
V1.1
```

正式提前至：

```text
V1
```

啟用：

```java
@Version
```

於：

```text
PurchaseOrder
Inventory
```

---

# 28. PurchaseOrder Optimistic Lock

Scenario：

```text
Request A reads ORDERED version=3
Request B reads ORDERED version=3
```

A：

```text
receive
version 3 → 4
commit
```

B：

```text
update version=3
```

DB update affected row = 0：

```text
OptimisticLockException
```

API：

```text
409 Conflict
```

避免 Duplicate Receiving。

---

# 29. Inventory Optimistic Lock

Scenario：

```text
Inventory version=5 quantity=100
```

同時：

```text
Receiving
+
Inventory Import
```

Receiving：

```text
100 → 150
version 5 → 6
```

Import：

```text
tries replace with 80 using version 5
```

結果：

```text
Optimistic Lock Conflict
→ rollback
→ 409
```

避免：

```text
Lost Update
```

---

# 30. Optimistic Lock Error

建議 ErrorCode：

```text
CONCURRENT_MODIFICATION
```

HTTP：

```text
409 Conflict
```

V1 不自動 retry。

由 Client：

```text
refresh
↓
retry manually
```

---

# 31. Document Upload Transaction

不要：

```text
BEGIN DB TRANSACTION
↓
Upload S3
↓
Call embedding provider
↓
wait several seconds
↓
COMMIT
```

外部網路不應長時間佔 DB transaction。

---

# 32. Document Lifecycle — Revised V1 Decision

原：

```text
ACTIVE
INACTIVE
```

正式改為：

```text
PROCESSING
READY
FAILED
INACTIVE
```

Purpose：

```text
PROCESSING
→ file accepted / metadata saved / ingestion running

READY
→ embedding and indexing complete

FAILED
→ ingestion failed

INACTIVE
→ manually disabled / archived
```

---

# 33. Document Upload Flow

建議：

```text
Upload File to S3
↓
BEGIN

Save DocumentMetadata
status = PROCESSING

COMMIT
↓
Parse
↓
Chunk
↓
Embedding Provider
↓
Vector Store
↓
BEGIN

status = READY

COMMIT
```

若 ingestion fail：

```text
BEGIN
status = FAILED
COMMIT
```

---

# 34. Document Consistency Model

MySQL + S3 + Vector Store：

```text
cannot share one ACID transaction
```

因此採：

```text
Eventually Consistent
```

V1 不實作 Distributed Transaction。

---

# 35. AI Query Transaction Boundary

Use Case：

```text
POST /ai/query
```

不建立一個 global DB transaction。

AI Query 可以：

```text
Read Recommendation
Read PO
Read Inventory
RAG Retrieval
Call LLM
```

如果 Tool：

```text
create PO draft
```

則只有：

```text
PurchaseOrderService.createDraft()
```

自己開 transaction。

---

# 36. External Calls Rule

以下外部操作：

```text
LLM Provider
Embedding Provider
S3
Vector Store
```

盡量：

```text
outside long-running DB transaction
```

避免：

```text
DB connection held while waiting network
```

---

# 37. Read-only Transactions

Query Service 可使用：

```java
@Transactional(readOnly = true)
```

例如：

```text
get Material
search Supplier
get Inventory
get Recommendation
get PurchaseOrder
search StockTransaction
```

但不是所有 GET 都強制需要 annotation。

---

# 38. Transaction Boundary Summary

| Use Case | Transaction | Atomic Scope |
|---|---|---|
| Create Material | Yes | Material + Inventory |
| Create Supplier | Yes | Supplier |
| Create SupplierMaterial | Yes | SupplierMaterial |
| Create Demand | Yes | Demand |
| Cancel Demand | Yes | Demand lifecycle |
| Inventory Validate | Yes | Batch + ImportItems only |
| Inventory Confirm | Yes | Entire Inventory batch |
| Manual Adjustment | Yes | Inventory + StockTransaction |
| Create Recommendation | Yes | Recommendation + all Results |
| Create PO Draft | Yes | PO + all Items |
| Update PO Draft | Yes | Entire PO Aggregate |
| Approve PO | Yes | PO state + audit |
| Reject PO | Yes | PO state + reason |
| Mark Ordered | Yes | PO state + audit |
| Receive PO | Yes | PO + all Inventory + Transactions |
| Document Metadata | Yes | Metadata only |
| Document Ingestion | Separate tx | Eventually Consistent |
| AI Query | No global tx | Tool-specific |

---

# 39. V1 Schema / Domain Revisions

Transaction design introduces three formal V1 revisions.

## Revision A — InventoryImportItem

Entity count：

```text
13
→
14
```

新增：

```text
InventoryImportItem
```

Relation：

```text
InventoryImportBatch 1:N InventoryImportItem
```

---

## Revision B — Optimistic Lock

正式啟用：

```text
PurchaseOrder.version
Inventory.version
```

JPA：

```java
@Version
```

不再只是 V1.1 candidate。

---

## Revision C — DocumentStatus

改為：

```text
PROCESSING
READY
FAILED
INACTIVE
```

取代：

```text
ACTIVE
INACTIVE
```

---

# 40. Files Requiring Synchronization

因上述正式修正，後續需同步更新：

```text
domain-model.md
erd.md
erd.html
api.md
package-architecture.md
```

主要修改：

```text
Add InventoryImportItem
Enable @Version in V1
Update DocumentStatus
Add CONCURRENT_MODIFICATION error
```

不需要改變：

```text
core procurement flow
recommendation model
PO aggregate boundary
API overall structure
module architecture
```

---

# 41. Transaction Rules — Final

正式鎖定：

1. Transaction Boundary 主要放 Service Layer。
2. Runtime Business Exception 觸發 Rollback。
3. Create Material 與 Inventory 建立必須 Atomic。
4. Inventory Import Confirm 必須 All-or-Nothing。
5. Manual Adjustment 必須 Inventory + StockTransaction Atomic。
6. Recommendation + all Ranking Result 必須 Atomic。
7. PO + PO Items 建立必須 Atomic。
8. PO Lifecycle Change 必須在 Transaction 中完成。
9. Receiving 必須 PO + Inventory + StockTransaction Atomic。
10. PurchaseOrder / Inventory V1 使用 Optimistic Lock。
11. Optimistic Lock Conflict 回 409。
12. V1 不做 automatic retry。
13. External API 不放進長時間 DB Transaction。
14. Document ingestion 採 Eventually Consistent。
15. AI Query 不建立 global transaction。
16. AI Tool 的 write operation 使用既有 Service transaction。
17. V1 不使用 distributed transaction。
18. V1 不使用 SERIALIZABLE isolation。
19. InventoryImportItem 正式納入 V1。
20. DocumentStatus 正式支援 ingestion lifecycle。

---

# 42. Next Stage

Transaction Boundary 完成後：

```text
Transaction Boundary
↓
Validation / Exception Strategy
↓
Security Design
↓
UML
↓
Testing Strategy
↓
Implementation Plan
```

UML 建議在 Validation / Exception 與 Security Design 鎖定後製作。

優先 UML：

```text
1. Domain Class Diagram
2. Recommendation Sequence Diagram
3. Inventory Import Sequence Diagram
4. Purchase Order Lifecycle Sequence Diagram
5. Receiving Sequence Diagram
```
