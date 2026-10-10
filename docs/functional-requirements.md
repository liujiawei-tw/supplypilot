# SupplyPilot — Functional Requirements

> **文件目的**：定義 SupplyPilot V1 的功能需求、商業規則、角色權限、狀態轉換、驗證規則與完成標準，作為後續 Domain Model、ERD、API、Service Layer、Testing Strategy 與實作工作的主要依據。  
> **文件定位**：核心功能規格文件  
> **目前狀態**：V1 Functional Requirements Baseline  
> **關聯文件**：
> - `company-profile.md`
> - `as-is-procurement-flow.md`
> - `to-be-procurement-flow.md`

---

# 1. 系統目標

SupplyPilot 是一套面向小型企業的 AI 智慧採購與供應鏈決策平台。

V1 主要目標不是取代完整 ERP，而是建立一個：

> **以可靠的 Backend Business Logic 負責採購計算、資料一致性與交易流程，以 AI 負責查詢、理解與解釋的 Procurement Intelligence Layer。**

核心責任分工：

```text
確定性資料與規則
→ Backend Business Logic

非結構化資訊理解與說明
→ AI

高風險採購決策
→ Human Approval
```

---

# 2. V1 功能模組

V1 Functional Requirements 分為：

```text
FR-100  Material / Master Data
FR-200  Demand
FR-300  Inventory Import
FR-400  Shortage & Net Requirement
FR-500  Supplier Recommendation
FR-600  Purchase Order Draft
FR-700  Approval / Ordering / Delivery Tracking
FR-800  Receiving / Inventory / StockTransaction
FR-900  AI Assistant / RAG
```

其中 V1 核心交易流程為：

```text
Demand
↓
Inventory Import
↓
Net Requirement
↓
Shortage Detection
↓
Supplier Recommendation
↓
PO Draft
↓
Approval
↓
Ordering
↓
Receiving
↓
Inventory Update
↓
StockTransaction
```

---

# 3. 使用者角色

V1 角色：

```text
PRODUCTION_PLANNER
PROCUREMENT_STAFF
PROCUREMENT_MANAGER
WAREHOUSE_STAFF
ADMIN
```

---

# 4. Role Permission Matrix

| 功能 | Production Planner | Procurement Staff | Procurement Manager | Warehouse Staff | Admin |
|---|---:|---:|---:|---:|---:|
| Demand Create / Edit / Cancel | ✅ | ❌ | View | ❌ | ✅ |
| Inventory Import | ❌ | View | View | ✅ | ✅ |
| View Recommendation | View | ✅ | ✅ | View | ✅ |
| Create PO Draft | ❌ | ✅ | View | ❌ | ✅ |
| Approve / Reject PO | ❌ | ❌ | ✅ | ❌ | ✅ |
| Mark PO as Ordered | ❌ | ✅ | ❌ | ❌ | ✅ |
| Receive PO | ❌ | ❌ | ❌ | ✅ | ✅ |
| Manual Inventory Adjustment | ❌ | ❌ | ❌ | ✅ | ✅ |
| Master Data Management | ❌ | ❌ | ❌ | ❌ | ✅ |
| RAG Document Upload | ❌ | ✅ | ❌ | ❌ | ✅ |
| RAG Document Query | ❌ | ✅ | ✅ | ❌ | ✅ |

V1 採 Role-based 權限即可，不建立複雜 Permission Matrix Framework。

---

# 5. 共通 V1 設計規則

## BR-001 — Currency

V1 僅支援：

```text
TWD
```

資料模型可保留 currency 欄位，但第一版限制單一幣別。

不處理：

- Exchange Rate
- FX Conversion
- Multi-currency Ranking

---

## BR-002 — Material Base Unit

每個 Material 使用固定 Base Unit。

V1 可保留下列單位：

```text
G
KG
ML
L
PCS
```

但同一 Material：

```text
Demand
Inventory
SupplierMaterial
PO Item
StockTransaction
```

皆必須使用同一 Base Unit。

V1 不建立 Unit Conversion Engine。

---

## BR-003 — Negative Inventory

V1 不允許：

```text
Inventory.quantity < 0
```

任何可能導致負庫存的操作必須拒絕。

---

# 6. FR-100 — Material / Master Data

---

## FR-101 — Create Material

**Role**

```text
ADMIN
```

系統允許建立 Material。

最小業務資料：

```text
code
name
baseUnit
safetyStock
status
```

### Validation

- code 必填
- code 不可重複
- name 必填
- baseUnit 必須為系統允許單位
- safetyStock >= 0

---

## FR-102 — Material Status

Material 狀態：

```text
ACTIVE
INACTIVE
```

### Business Rules

`INACTIVE` Material：

- 不可新增 Demand
- 不可新增至新的 PO Item
- 不進新的 Recommendation
- 歷史資料必須保留
- 不允許 Hard Delete

---

## FR-103 — Safety Stock

V1 Safety Stock 直接維護於：

```text
Material.safetyStock
```

V1 不實作動態 Safety Stock Algorithm。

---

# 7. FR-110 — Supplier

---

## FR-111 — Create Supplier

**Role**

```text
ADMIN
```

最小業務資料：

```text
code
name
status
reliabilityScore
qualityScore
```

可保留：

```text
country
```

---

## FR-112 — Supplier Status

狀態：

```text
ACTIVE
INACTIVE
```

`INACTIVE` Supplier：

- 不進 Supplier Candidate
- 不可建立新 PO
- 歷史 PO 保留

---

## FR-113 — Reliability / Quality Score

V1：

```text
reliabilityScore = 0 ~ 100
qualityScore = 0 ~ 100
```

由 Master Data 手動維護。

V1 不從：

```text
On-time Delivery Rate
Defect Rate
```

自動推導。

---

# 8. FR-120 — SupplierMaterial

Supplier 與 Material 為多對多關係。

---

## FR-121 — SupplierMaterial Data

最小資料：

```text
supplier
material
unitPrice
moq
leadTimeDays
status
currency
```

---

## FR-122 — SupplierMaterial Status

狀態：

```text
ACTIVE
INACTIVE
```

Supplier 本身為 ACTIVE 不代表所有 SupplierMaterial 關係皆有效。

---

## FR-123 — Lead Time

Lead Time 維護於：

```text
SupplierMaterial.leadTimeDays
```

而不是 Supplier 層級。

---

## FR-124 — Current Price

V1 直接使用：

```text
SupplierMaterial.unitPrice
```

作為目前有效價格。

V1 不做完整 Quotation History。

---

# 9. FR-200 — Demand

---

## FR-201 — Create Demand

**Role**

```text
PRODUCTION_PLANNER
ADMIN
```

建立某個 Material 的未來需求。

最小資料：

```text
material
quantity
requiredDate
createdBy
createdAt
status
```

### Validation

- Material 必須存在
- Material.status = ACTIVE
- quantity > 0
- requiredDate >= currentDate

---

## FR-202 — Update Demand

只有未來的 ACTIVE Demand 可修改。

Validation 同 FR-201。

---

## FR-203 — Cancel Demand

Demand 不 Hard Delete。

狀態：

```text
ACTIVE
CANCELLED
```

取消時：

```text
ACTIVE
↓
CANCELLED
```

只有 ACTIVE Demand 參與 Net Requirement Calculation。

---

## FR-204 — View Demand

使用者可依：

```text
material
requiredDate range
status
```

查詢 Demand。

---

## FR-205 — Demand Aggregation

Demand 必須依 Calculation Period 聚合。

例如：

```text
10/10  100 kg
10/15  200 kg
10/25  300 kg
```

若 Calculation Period 截止日為：

```text
10/20
```

則：

```text
Expected Demand = 300 kg
```

不是 600 kg。

---

# 10. FR-300 — Inventory Import

V1 Inventory 來源：

```text
Existing ERP
↓
CSV / Excel Export
↓
SupplyPilot
```

實作優先建議：

```text
CSV First
```

XLSX 可留至後續版本。

---

## FR-301 — Upload Inventory CSV

**Role**

```text
WAREHOUSE_STAFF
ADMIN
```

範例：

```csv
material_code,quantity,unit
MAT-001,200,KG
MAT-002,80,KG
MAT-003,600,KG
```

---

## FR-302 — Inventory Import Validation

必須驗證：

- material_code 必填
- Material 必須存在
- quantity 必填
- quantity 必須為數字
- quantity >= 0
- unit 必須與 Material.baseUnit 相符
- 檔案格式正確
- 同一批資料不可有無法處理的重複 Material

建議錯誤碼：

```text
MATERIAL_CODE_REQUIRED
MATERIAL_NOT_FOUND
QUANTITY_REQUIRED
INVALID_QUANTITY
NEGATIVE_QUANTITY
UNIT_MISMATCH
INVALID_FILE_FORMAT
DUPLICATE_MATERIAL
```

---

## FR-303 — Preview Import

Upload 後不直接更新 Inventory。

流程：

```text
Upload
↓
Parse
↓
Validate All Rows
↓
Preview
↓
Confirm Import
```

Preview 至少顯示：

```text
totalRows
validRows
errorRows
errors
```

錯誤需指出：

```text
rowNumber
materialCode
errorCode
message
```

---

## FR-304 — All-or-Nothing Import

V1 Inventory Import 採：

> **All-or-Nothing**

只要任一列 Validation 失敗：

```text
整批 Inventory 不更新
```

所有資料驗證成功後才允許 Confirm Import。

---

## FR-305 — Confirm Inventory Import

Confirm 後進入 Transaction。

```text
BEGIN

Update Inventory A
Update Inventory B
Update Inventory C
...

Create InventoryImportBatch

COMMIT
```

任一步驟失敗：

```text
ROLLBACK
```

---

## FR-306 — Snapshot Replace Rule

ERP Inventory Import 表示：

> **目前庫存快照**

因此：

```text
CSV:
MAT-001,200
```

代表：

```text
Inventory.quantity = 200
```

不是：

```text
Inventory.quantity += 200
```

---

## FR-307 — InventoryImportBatch

系統應保留 Import Batch 資訊。

最小資訊：

```text
id
filename
uploadedBy
uploadedAt
rowCount
status
```

必要時可加入：

```text
successfulRowCount
failedRowCount
```

---

## FR-308 — Import 不建立一般 StockTransaction

ERP Snapshot Import 不視為：

```text
PURCHASE_RECEIVE
MANUAL_ADJUSTMENT
```

因此 Import 不建立一般 StockTransaction。

Import Audit 由：

```text
InventoryImportBatch
```

負責。

---

# 11. FR-400 — Net Requirement / Shortage

---

## FR-401 — Calculate Net Requirement

輸入：

```text
material
calculationPeriod
```

Backend 取得：

```text
Expected Demand
Safety Stock
Current Inventory
Incoming Inventory
```

公式：

```text
Raw Net Requirement
=
Expected Demand
+ Safety Stock
- Current Inventory
- Incoming Inventory
```

最終：

```text
Net Requirement = max(0, Raw Net Requirement)
```

---

## FR-402 — Shortage Status

```text
Net Requirement > 0
→ SHORTAGE

Net Requirement = 0
→ NO_SHORTAGE
```

V1 不做：

```text
CRITICAL
HIGH
MEDIUM
LOW
```

---

## FR-403 — Incoming Inventory Source

只有：

```text
PO.status = ORDERED
```

才可計入 Incoming Inventory。

以下狀態不計入：

```text
DRAFT
APPROVED
REJECTED
RECEIVED
```

---

## FR-404 — Incoming Delivery Date Rule

`ORDERED` 還不夠。

只有：

```text
expectedDeliveryDate <= demandRequiredDate
```

或落在該次 Calculation Period 可用範圍內的 PO，才可抵扣 Demand。

例如：

```text
Demand Required Date:
2026-10-20

Incoming PO:
500 kg
Expected Delivery:
2026-11-01
```

此 500 kg 不可抵扣 10/20 的 Demand。

---

## FR-405 — Net Requirement Result

Backend 至少回傳：

```text
material
expectedDemand
safetyStock
currentInventory
incomingInventory
netRequirement
shortage
calculationPeriod
```

不得只回傳單一最終數字。

---

# 12. FR-500 — Supplier Recommendation

---

## FR-501 — Supplier Candidate Selection

流程：

```text
Supplier Candidates
↓
Hard Constraint Check
↓
Qualified Suppliers
↓
Supplier-specific Quantity
↓
Ranking
```

---

## FR-502 — Hard Constraints

V1 Hard Constraints：

### HC-01

```text
Supplier.status = ACTIVE
```

### HC-02

```text
SupplierMaterial.status = ACTIVE
```

且 Supplier 確實供應該 Material。

### HC-03

Delivery Feasibility：

```text
currentDate + leadTimeDays
<=
requiredDate
```

不符合者：

```text
DISQUALIFIED
```

不進 Ranking。

---

## FR-503 — MOQ Rule

MOQ **不是** V1 Hard Constraint。

例如：

```text
Net Requirement = 150 kg
MOQ = 200 kg
```

則：

```text
Recommended Quantity = 200 kg
```

而不是淘汰 Supplier。

---

## FR-504 — Supplier-specific Purchase Quantity

每個 Supplier 必須獨立計算 Recommendation Quantity。

例如：

```text
Net Requirement = 160
```

Supplier A：

```text
MOQ = 100
Package Multiple = 25
Recommended Qty = 175
```

Supplier B：

```text
MOQ = 200
Recommended Qty = 200
```

不同 Supplier 的 Recommended Quantity 可以不同。

---

## FR-505 — Packaging Multiple

若 SupplierMaterial 存在 Packaging Multiple：

```text
Recommended Qty
=
符合 MOQ 且符合 Packaging Multiple 的最小數量
```

例如：

```text
Net Requirement = 160
Package Multiple = 25
```

結果：

```text
175
```

若 V1 實作時間不足，可先保留欄位與規則設計，實際 V1 最低要求為 MOQ。

---

## FR-506 — Total Purchase Cost

Supplier Price Factor 必須基於：

```text
Recommended Quantity × Unit Price
```

而不是只比較 Unit Price。

例如：

Supplier A：

```text
80 × 200 = 16,000
```

Supplier B：

```text
85 × 160 = 13,600
```

雖然 B Unit Price 較高，該次採購 Total Cost 可能較低。

---

## FR-507 — Supplier Ranking Formula

V1 Baseline：

```text
Supplier Score =
Price Score        × 40%
Lead Time Score    × 30%
Reliability Score  × 20%
Quality Score      × 10%
```

AI 不參與 Score Calculation。

---

## FR-508 — Ranking Tie-break

Score 相同時：

```text
1. Lower Total Cost
2. Shorter Lead Time
3. Higher Reliability
4. Deterministic Supplier ID / Code order
```

最後一層只為確保排序穩定。

---

## FR-509 — Recommendation Persistence

Purchase Recommendation 必須持久化為 Snapshot。

至少保存：

```text
material
calculationPeriod
netRequirement
recommendedSupplier
recommendedQuantity
supplierScore
createdAt
```

以及必要的 Calculation Snapshot。

目的：

```text
System Recommendation
vs
Human Decision
```

可追溯比較。

### V1 Period-End Aggregated Recommendation Decision

Recommendation 的 Calculation Period 採 `periodStart <= Demand.requiredDate <= periodEnd`（起訖日皆包含）聚合 ACTIVE Demand。

V1 將 `periodEnd` 作為唯一的 `requiredDate`：Incoming Inventory 僅計入 `expectedDeliveryDate <= periodEnd` 的 ORDERED PO，Supplier delivery feasibility 也以 `currentDate + leadTimeDays <= periodEnd` 判定。

此為 **Period-End Aggregated Recommendation**；它不保證計算期間內逐日皆無缺料。V1 不做逐筆 Demand 分配或以最早 Demand 日期重算。

---

## FR-510 — Recommendation Recalculation

使用者可以重新產生 Recommendation。

新 Recommendation 建立新 Snapshot。

已建立 PO 的 Recommendation Reference 不因後續重算而改變。

---

# 13. FR-600 — Purchase Order Draft

---

## FR-601 — Create PO Draft

**Role**

```text
PROCUREMENT_STAFF
ADMIN
```

建立：

```text
PurchaseOrder
status = DRAFT
```

---

## FR-602 — PO Header

業務欄位概念：

```text
poNumber
supplier
status
expectedDeliveryDate
createdBy
createdAt
updatedAt
approvedBy
approvedAt
orderedBy
orderedAt
receivedBy
receivedAt
recommendationReference
```

V1 不把所有 lifecycle timestamp 混成單一 orderDate。

---

## FR-603 — Multiple PO Items

一張 PO 可以包含多個 Item。

```text
PurchaseOrder
1
↓
N
PurchaseOrderItem
```

---

## FR-604 — PO Item Validation

每個 Item：

```text
Material exists
Material.status = ACTIVE
Supplier supplies Material
SupplierMaterial.status = ACTIVE
quantity > 0
unitPrice >= 0
unit = Material.baseUnit
```

---

## FR-605 — Duplicate Material in Same PO

同一張 PO 不允許重複 Material Item。

例如：

```text
MAT-001 100kg
MAT-001 200kg
```

必須先合併後建立。

---

## FR-606 — Single Supplier per PO

一張 PO 只能屬於一個 Supplier。

所有 Item 必須確認：

```text
PO.supplier
```

確實可供應該 Item Material。

---

## FR-607 — Recommendation Reference

若 PO 從 Recommendation 建立：

```text
PO → Recommendation Snapshot
```

必須保留關聯。

---

# 14. FR-610 — Human Override

---

## FR-611 — Supplier Override

若：

```text
Recommended Supplier != Selected Supplier
```

則：

```text
overrideReason REQUIRED
```

---

## FR-612 — Quantity Override

若：

```text
Recommended Quantity != PO Quantity
```

則：

```text
overrideReason REQUIRED
```

---

## FR-613 — Override Persistence

V1 可先使用文字：

```text
overrideReason
```

未來再評估：

```text
overrideType
```

---

# 15. FR-700 — PO Lifecycle

V1 Status：

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
```

V1 不加入：

```text
CANCELLED
PARTIALLY_RECEIVED
SUBMITTED
```

---

# 16. PO State Transition Matrix

| From | To | Allowed | Action |
|---|---|---:|---|
| DRAFT | APPROVED | ✅ | Manager Approve |
| DRAFT | REJECTED | ✅ | Manager Reject |
| APPROVED | ORDERED | ✅ | Procurement marks formally ordered |
| ORDERED | RECEIVED | ✅ | Warehouse full receiving |
| APPROVED | REJECTED | ❌ | V1 不支援 |
| ORDERED | APPROVED | ❌ | 禁止倒退 |
| RECEIVED | ORDERED | ❌ | 禁止倒退 |
| REJECTED | DRAFT | ❌ | REJECTED 為終態 |
| REJECTED | ORDERED | ❌ | 非法 |
| DRAFT | RECEIVED | ❌ | 非法跳轉 |
| APPROVED | RECEIVED | ❌ | 必須先 ORDERED |
| RECEIVED | REJECTED | ❌ | 非法 |

所有非法轉換：

```text
INVALID_PO_STATUS_TRANSITION
```

---

# 17. FR-701 — Approve PO

**Role**

```text
PROCUREMENT_MANAGER
ADMIN
```

前提：

```text
status = DRAFT
```

成功：

```text
DRAFT
↓
APPROVED
```

記錄：

```text
approvedBy
approvedAt
```

---

# 18. FR-702 — Reject PO

**Role**

```text
PROCUREMENT_MANAGER
ADMIN
```

前提：

```text
status = DRAFT
```

Reject：

```text
DRAFT
↓
REJECTED
```

必須：

```text
rejectReason REQUIRED
rejectedBy from authenticated user
rejectedAt from backend clock
```

---

# 19. FR-703 — REJECTED Terminal State

V1：

```text
REJECTED
```

為 Terminal State。

不允許：

```text
REJECTED → DRAFT
```

如需修改，建立新的 Draft。

---

# 20. FR-704 — Approved PO Immutability

PO 進入：

```text
APPROVED
```

後，不允許直接修改以下核心欄位：

```text
supplier
material
quantity
unitPrice
```

原因：

> Manager 核准的是特定版本的採購內容。

如需變更，建立新 Draft。

---

# 21. FR-710 — Formal Ordering

---

## FR-711 — Mark PO as Ordered

**Role**

```text
PROCUREMENT_STAFF
ADMIN
```

前提：

```text
status = APPROVED
```

成功：

```text
APPROVED
↓
ORDERED
```

記錄：

```text
orderedBy
orderedAt
```

---

## FR-712 — Required Fields Before Ordering

進入 ORDERED 前至少必須存在：

```text
supplier
PO items
quantity
unitPrice
expectedDeliveryDate
```

缺少 Expected Delivery Date：

```text
禁止 ORDERED
```

---

# 22. FR-720 — Delivery Tracking

---

## FR-721 — Overdue Detection

若：

```text
status = ORDERED
AND
currentDate > expectedDeliveryDate
```

則：

```text
isOverdue = true
```

---

## FR-722 — OVERDUE Is Not PO Status

`OVERDUE` 不屬於 lifecycle status。

PO 仍然：

```text
ORDERED
```

但 API 可表達：

```text
isOverdue = true
```

或：

```text
deliveryStatus = OVERDUE
```

---

## FR-723 — View Open / Overdue PO

Procurement Staff / Manager 可查詢：

```text
Open PO
Overdue PO
Upcoming Delivery
```

---

# 23. FR-800 — Receiving

---

## FR-801 — Receive Purchase Order

**Role**

```text
WAREHOUSE_STAFF
ADMIN
```

前提：

```text
PO.status = ORDERED
```

V1：

> **Full Receiving Only**

---

## FR-802 — Receiving Validation

系統必須確認：

```text
PO exists
PO.status = ORDERED
PO not already received
all PO items valid
```

否則拒絕。

---

## FR-803 — Update Inventory

對每個 PO Item：

```text
Inventory.quantity
+=
POItem.quantity
```

不得造成資料不一致。

---

## FR-804 — Create StockTransaction

每個 PO Item 建立一筆 StockTransaction。

例如：

```text
type = PURCHASE_RECEIVE
material = MAT-001
quantity = +200
reference = PO-001
```

PO 有 3 個 Item：

```text
建立 3 筆 StockTransaction
```

---

## FR-805 — Update PO Status

所有 Inventory / StockTransaction 更新成功後：

```text
ORDERED
↓
RECEIVED
```

記錄：

```text
receivedBy
receivedAt
```

---

## FR-806 — Receiving Transaction Boundary

Receiving 必須在同一 Transaction Boundary。

```text
BEGIN

Validate PO
↓
Update Inventory Item 1
↓
Insert StockTransaction 1
↓
Update Inventory Item 2
↓
Insert StockTransaction 2
↓
...
↓
Update PO = RECEIVED

COMMIT
```

任何步驟失敗：

```text
ROLLBACK
```

---

# 24. FR-810 — Duplicate Receiving / Concurrency

---

## FR-811 — Duplicate Receiving Guard

最基本條件：

```text
PO.status must be ORDERED
```

第一次成功後：

```text
PO.status = RECEIVED
```

再次 Receive：

```text
Reject
```

---

## FR-812 — V1 vs V1.1 Concurrency Strategy

V1 最低要求：

```text
Transaction
+
Status Validation
```

V1.1 強化：

```text
Optimistic Lock
@Version
Concurrency Test
```

可優先考慮：

```text
PurchaseOrder
Inventory
```

加入 Optimistic Lock。

---

## FR-813 — Idempotency

V1：

```text
Transaction + State Guard
```

V1.1：

```text
Idempotency-Key
```

---

# 25. FR-820 — Manual Inventory Adjustment

---

## FR-821 — Manual Adjustment

**Role**

```text
WAREHOUSE_STAFF
ADMIN
```

輸入：

```text
material
adjustmentQuantity
reason
```

例如：

```text
MAT-001
-5 kg
Reason: Damaged inventory
```

---

## FR-822 — Adjustment Reason

```text
reason REQUIRED
```

---

## FR-823 — Negative Inventory Protection

若：

```text
Current Inventory + Adjustment < 0
```

則：

```text
Reject
INSUFFICIENT_INVENTORY
```

---

## FR-824 — Adjustment StockTransaction

成功 Adjustment 必須建立：

```text
StockTransaction
type = MANUAL_ADJUSTMENT
```

並記錄 Reason。

---

# 26. StockTransaction Types

V1 至少：

```text
PURCHASE_RECEIVE
MANUAL_ADJUSTMENT
```

未來可加入：

```text
CONSUMPTION
TRANSFER
RETURN
```

但不屬於目前 V1 必要範圍。

---

# 27. FR-900 — AI Assistant

AI 是輔助層，不是核心交易依賴。

---

## FR-901 — Explain Purchase Recommendation

使用者可詢問：

> 為什麼這個 Material 建議買這個數量？

流程：

```text
AI
↓
Tool Calling
↓
getPurchaseRecommendation(...)
↓
Backend Structured Result
↓
LLM Explanation
```

LLM 不自行重算核心數字。

---

## FR-902 — Explain Supplier Recommendation

AI 透過 Backend Tool 取得：

```text
price
totalCost
leadTime
reliability
quality
score
disqualifiedReason
```

再生成說明。

Supplier Ranking 本身由 Backend 計算。

---

## FR-903 — Query Open Purchase Orders

AI 使用：

```text
getOpenPurchaseOrders(materialId)
```

取得即時資料。

不得使用 RAG 取代交易資料查詢。

---

## FR-904 — Create PO Draft via AI

AI 可以呼叫：

```text
createPurchaseDraft(...)
```

但只能建立：

```text
DRAFT
```

AI 不允許：

```text
APPROVE
ORDER
RECEIVE
```

Tool 呼叫仍必須經過 Backend Validation / Service Layer。

---

## FR-905 — AI Explanation Persistence

V1：

> AI Explanation 不持久化。

真正需要 Audit 的資料：

```text
Backend Calculation
Recommendation Snapshot
Supplier Score
Human Override
Approval
Reject Reason
```

---

## FR-906 — AI Failure Boundary

發生：

```text
LLM Timeout
Provider unavailable
Invalid response
RAG failure
```

時：

```text
AI function unavailable
```

但下列核心功能仍必須正常：

```text
Demand
Inventory
Recommendation
PO
Approval
Ordering
Receiving
Inventory Update
```

---

# 28. FR-910 — RAG

---

## FR-911 — RAG Document Types

V1 文件類型：

```text
SUPPLIER_CONTRACT
QUOTATION
PROCUREMENT_SOP
PRODUCT_SPECIFICATION
QUALITY_REQUIREMENT
```

---

## FR-912 — RAG Document Metadata

至少保存：

```text
documentId
documentType
supplierId (optional)
title
version
uploadedAt
uploadedBy
```

---

## FR-913 — RAG Response

回答必須包含：

```text
Answer
+
Source
```

若證據不足：

```text
INSUFFICIENT_EVIDENCE
```

不得將模型推測當成正式企業資料。

---

# 29. Error Response Strategy

所有 API 應使用一致 Error Response。

範例：

```json
{
  "code": "INVALID_PO_STATUS",
  "message": "Purchase order must be ORDERED before receiving.",
  "timestamp": "2026-10-08T00:00:00+08:00"
}
```

可再視 API Design 加入：

```text
path
fieldErrors
traceId
```

---

# 30. Business / Validation Error

典型錯誤：

```text
MATERIAL_NOT_FOUND
MATERIAL_INACTIVE
SUPPLIER_NOT_FOUND
SUPPLIER_INACTIVE
INVALID_QUANTITY
NEGATIVE_QUANTITY
UNIT_MISMATCH
INVALID_PO_STATUS
INVALID_PO_STATUS_TRANSITION
OVERRIDE_REASON_REQUIRED
REJECT_REASON_REQUIRED
EXPECTED_DELIVERY_DATE_REQUIRED
INSUFFICIENT_INVENTORY
DUPLICATE_MATERIAL
```

原則上對應：

```text
4xx
```

實際 HTTP Status 於 API Design 階段定義。

---

# 31. System Error

例如：

```text
Database unavailable
Unexpected exception
Infrastructure failure
```

原則上：

```text
5xx
```

透過 GlobalExceptionHandler 統一處理。

---

# 32. Purchase Order Number

顯示格式建議：

```text
PO-YYYYMMDD-XXXX
```

例如：

```text
PO-20261008-0001
```

實作不得使用：

```text
查今天最大值 + 1
```

這種可能產生 concurrency collision 的方式。

建議使用：

```text
DB identity / sequence / unique internal id
+
formatted display number
```

確保唯一性。

---

# 33. V1 Explicitly Out of Scope

V1 不主動實作：

```text
Multi-Warehouse
Multi-company
Multi-currency
FX Conversion
Unit Conversion Engine
Partial Receiving
PO Cancellation
Multi-level Approval
Approval Matrix
Full RFQ Workflow
Quotation History
Supplier Portal
Full MRP
Complete Production Scheduling
Event-driven Architecture
Microservices
Automated Supplier Negotiation
AI Auto Approval
AI Auto Ordering
Idempotency-Key
Advanced Concurrency Control
Dynamic Safety Stock
Automatic Reliability Score Calculation
Automatic Quality Score Calculation
```

---

# 34. V1 Done Definition

SupplyPilot V1 只有在以下流程完整可操作時才算完成：

```text
1. 建立 Material
2. 建立 Supplier
3. 建立 SupplierMaterial
4. 設定 Safety Stock
5. Production Planner 建立 Demand
6. Warehouse 匯入 Inventory CSV
7. 系統完成 Inventory Validation / Import
8. 系統計算 Net Requirement
9. 系統判斷 Shortage
10. 系統取得 Supplier Candidates
11. 系統執行 Hard Constraint
12. 系統計算 Supplier-specific Purchase Quantity
13. 系統計算 Supplier Ranking
14. 系統保存 Recommendation Snapshot
15. Procurement Staff 建立 PO Draft
16. 若 Override，留下 Override Reason
17. Procurement Manager Approve / Reject
18. Reject 時留下 Reject Reason
19. Approved PO 核心欄位不可修改
20. Procurement Staff Mark as ORDERED
21. 只有 ORDERED PO 計入 Incoming Inventory
22. 系統判斷 Overdue
23. Warehouse 執行 Full Receiving
24. Inventory 正確增加
25. 每個 Item 建立 StockTransaction
26. PO 正確轉為 RECEIVED
27. Receiving 失敗時 Transaction Rollback
28. Manual Inventory Adjustment 可正常執行
29. Inventory 不允許變負數
30. 核心流程不依賴 AI 才能運作
```

AI 加值能力至少應做到：

```text
Explain Purchase Recommendation
Explain Supplier Recommendation
Query Open PO
RAG Document Query
```

但：

> AI 故障時，以上核心採購流程仍必須可使用。

---

# 35. 已確認的核心 Business Rules Summary

## Procurement

1. 只有 `ORDERED` PO 計入 Incoming Inventory。
2. Incoming PO 只有在 Required Date 前可到貨，才可抵扣該次 Demand。
3. Supplier Recommendation 先 Hard Constraint，再 Ranking。
4. MOQ 不直接淘汰 Supplier，而是影響 Recommended Quantity。
5. Ranking 的 Price Factor 以 Total Purchase Cost 為基礎。
6. Recommendation 必須持久化為 Snapshot。
7. Procurement Staff 可 Override，但必須留下 Reason。

## Purchase Order

8. 一張 PO 可包含多個 Item。
9. 一張 PO 只能有一個 Supplier。
10. DRAFT 可 APPROVE 或 REJECT。
11. Reject Reason 必填。
12. REJECTED 是 Terminal State。
13. APPROVED 後核心採購欄位不可修改。
14. V1 不做 CANCELLED。
15. `OVERDUE` 不是 lifecycle status。

## Inventory

16. ERP Import 是 Snapshot Replace。
17. Import 採 All-or-Nothing。
18. Import 不建立一般 StockTransaction。
19. Inventory 不允許負數。
20. Manual Adjustment 必須留下 Reason 並建立 StockTransaction。
21. V1 Receiving 為 Full Receiving Only。

## Master Data

22. Material 使用 ACTIVE / INACTIVE。
23. Supplier 使用 ACTIVE / INACTIVE。
24. SupplierMaterial 使用 ACTIVE / INACTIVE。
25. Safety Stock 儲存在 Material。
26. Lead Time 儲存在 SupplierMaterial。
27. Reliability / Quality Score V1 手動維護。
28. V1 Currency = TWD。
29. 每個 Material 固定 Base Unit，不做 Unit Conversion Engine。

## AI

30. AI 不自行計算核心採購數字。
31. AI 不直接修改 Inventory。
32. AI 不直接 Approve / Order / Receive。
33. AI 可以建立 PO Draft，但仍需經過 Backend Validation。
34. AI Explanation V1 不持久化。
35. AI / RAG Failure 不得阻斷核心採購流程。

---

# 36. 下一階段

Functional Requirements 完成後，下一階段依序進入：

```text
Functional Requirements
↓
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
↓
Implementation Plan
```

下一份主要文件建議：

```text
domain-model.md
```

該文件應開始正式定義：

- Entity / Aggregate Candidate
- Value Object Candidate
- Entity Relationship
- Lifecycle Ownership
- Domain Responsibility
- 哪些資料應持久化
- 哪些資料應動態計算
- 哪些規則屬於 Domain / Service
