# SupplyPilot — Recommendation Sequence Diagram

> **文件目的**：描述 `POST /api/v1/recommendations` 從 Authentication、Validation、資料讀取、Net Requirement、Supplier Hard Constraint、Ranking 到 Snapshot Persistence 的完整執行順序。  
> **視覺化文件**：`recommendation-sequence.html`

---

# 1. Participants

```text
Client
JwtAuthenticationFilter
RecommendationController
PurchaseRecommendationService
MaterialRepository
DemandRepository
InventoryRepository
IncomingInventoryQuery
SupplierMaterialRepository
NetRequirementCalculator
SupplierConstraintEvaluator
PurchaseQuantityCalculator
SupplierRankingService
PurchaseRecommendationRepository
```

---

# 2. Security Entry

Request：

```text
POST /api/v1/recommendations
```

流程：

```text
Client
↓
JwtAuthenticationFilter
↓
Validate JWT
↓
Build SecurityContext
↓
RecommendationController
```

允許 Role：

```text
PROCUREMENT_STAFF
PROCUREMENT_MANAGER
ADMIN
```

若 Token：

```text
missing
invalid
expired
```

則：

```text
401 UNAUTHENTICATED
```

若 Role 不允許：

```text
403 ACCESS_DENIED
```

---

# 3. DTO Validation

Controller 接收：

```text
CreateRecommendationRequest
```

欄位：

```text
materialId
periodStart
periodEnd
```

DTO Validation：

```text
materialId required
periodStart required
periodEnd required
periodEnd >= periodStart
```

失敗：

```text
400 VALIDATION_FAILED
```

---

# 4. Service Validation

`PurchaseRecommendationService`：

```text
MaterialRepository.findById(materialId)
```

驗證：

```text
Material exists
Material ACTIVE
```

錯誤：

```text
404 MATERIAL_NOT_FOUND
400 MATERIAL_INACTIVE
```

---

# 5. Demand Aggregation

查：

```text
DemandRepository.sumActiveDemand(
    materialId,
    periodStart,
    periodEnd
)
```

只計算：

```text
Demand.status = ACTIVE
```

得到：

```text
expectedDemand
```

---

# 6. Current Inventory

查：

```text
InventoryRepository.findByMaterialId(materialId)
```

得到：

```text
currentInventory
```

Material 建立時就應存在 Inventory，因此缺少 Inventory 屬資料完整性異常。

---

# 7. Incoming Inventory Query

Recommendation 不直接依賴 PurchaseOrderService。

呼叫：

```text
IncomingInventoryQuery.calculateIncomingQuantity(
    materialId,
    requiredDate
)
```

Procurement Module 提供實作：

```text
PurchaseOrderIncomingInventoryQuery
```

只計入：

```text
PO.status = ORDERED
AND
expectedDeliveryDate <= requiredDate
```

因此：

```text
APPROVED
DRAFT
REJECTED
RECEIVED
```

不作為新的 Incoming Quantity。

---

# 8. Net Requirement

呼叫：

```text
NetRequirementCalculator.calculate(...)
```

公式：

```text
Expected Demand
+ Safety Stock
- Current Inventory
- Incoming Inventory
```

最終：

```text
max(0, raw)
```

---

# 9. PURCHASE_NOT_REQUIRED Branch

若：

```text
netRequirement = 0
```

則：

```text
RecommendationOutcome =
PURCHASE_NOT_REQUIRED
```

此時不需要 Supplier Ranking。

Recommendation 仍可以持久化為 Snapshot。

---

# 10. Supplier Candidate Loading

若：

```text
netRequirement > 0
```

查：

```text
SupplierMaterialRepository.findActiveCandidates(materialId)
```

取得對該 Material 的 Supplier / SupplierMaterial Candidate。

---

# 11. Hard Constraint

每個 Candidate 呼叫：

```text
SupplierConstraintEvaluator.evaluate(...)
```

條件：

```text
Supplier ACTIVE
SupplierMaterial ACTIVE
currentDate + leadTimeDays <= requiredDate
```

不合格：

```text
qualified = false
disqualifiedReason = ...
```

例如：

```text
SUPPLIER_INACTIVE
SUPPLIER_MATERIAL_INACTIVE
DELIVERY_DATE_NOT_FEASIBLE
```

---

# 12. Supplier-specific Quantity

Qualified Supplier 呼叫：

```text
PurchaseQuantityCalculator.calculate(
    netRequirement,
    moq,
    packageMultiple
)
```

每個 Supplier 可能產生不同：

```text
recommendedQuantity
```

---

# 13. Supplier Ranking

呼叫：

```text
SupplierRankingService.rank(...)
```

Baseline Weight：

```text
Price       40%
Lead Time   30%
Reliability 20%
Quality     10%
```

Price 使用：

```text
recommendedQuantity × unitPrice
```

即：

```text
Total Purchase Cost
```

而不是只比較 Unit Price。

Tie Break：

```text
1. lower total purchase cost
2. shorter lead time
3. higher reliability
4. supplier id/code
```

---

# 14. Recommendation Outcome

## 有合格 Supplier

```text
SUPPLIER_RECOMMENDED
```

並保存：

```text
recommendedSupplier
recommendedQuantity
recommendedSupplierScore
```

## 無合格 Supplier

```text
NO_QUALIFIED_SUPPLIER
```

這是合法結果，不是 HTTP Exception。

---

# 15. Snapshot Persistence Transaction

最後：

```text
@Transactional
```

保存：

```text
PurchaseRecommendation
+
all SupplierRecommendationResult
```

Snapshot 必須 Atomic。

若：

```text
Recommendation saved
but Result #3 fails
```

則：

```text
Rollback all
```

不可留下 Partial Snapshot。

---

# 16. Snapshot Content

## PurchaseRecommendation

```text
material
periodStart
periodEnd
expectedDemand
safetyStock
currentInventory
incomingInventory
netRequirement
outcome
recommendedSupplier
recommendedQuantity
recommendedSupplierScore
createdBy
createdAt
```

## SupplierRecommendationResult

```text
supplier
qualified
disqualifiedReason
recommendedQuantity
unitPriceSnapshot
leadTimeDaysSnapshot
reliabilityScoreSnapshot
qualityScoreSnapshot
totalCost
score breakdown
totalScore
rank
```

---

# 17. Transaction Consistency

V1 接受：

```text
Read Committed
```

下的短時間資料變動。

不使用：

```text
SERIALIZABLE
Pessimistic Lock
Distributed Snapshot
```

Recommendation 是：

```text
Decision Snapshot
```

不是整個系統的 global transaction snapshot。

---

# 18. Exception Paths

## DTO Invalid

```text
400 VALIDATION_FAILED
```

## Material Missing

```text
404 MATERIAL_NOT_FOUND
```

## Material Inactive

```text
400 MATERIAL_INACTIVE
```

## Persistence / Constraint Failure

```text
Rollback Recommendation Snapshot
```

再依 Error Mapping 回：

```text
409
or
500
```

---

# 19. Security / Audit

`createdBy` 不由 Client 傳。

來源：

```text
SecurityContext
↓
CurrentUserProvider
```

AI Tool 若觸發 Recommendation，也必須使用目前登入者的 SecurityContext。

---

# 20. Sequence Summary

完整流程：

```text
Authenticate
↓
Validate Request
↓
Validate Material
↓
Aggregate Demand
↓
Read Inventory
↓
Read Eligible Incoming PO
↓
Calculate Net Requirement
↓
if no purchase required:
    outcome = PURCHASE_NOT_REQUIRED
else:
    Load Suppliers
    ↓
    Hard Constraint
    ↓
    Supplier-specific Quantity
    ↓
    Ranking
    ↓
    outcome =
        SUPPLIER_RECOMMENDED
        or
        NO_QUALIFIED_SUPPLIER
↓
Persist Recommendation Snapshot
↓
Persist Candidate Result Snapshots
↓
Commit
↓
201 Created
```

---

# 21. Design Intent

此 Sequence Diagram 強調：

1. AI 不參與核心推薦計算。
2. Deterministic Calculation 可獨立 Unit Test。
3. Recommendation 不直接依賴 Procurement Service。
4. Incoming PO 透過 Query Boundary。
5. Hard Constraint 與 Ranking 分離。
6. `NO_QUALIFIED_SUPPLIER` 是正常結果。
7. Snapshot Persistence 必須 Atomic。
8. Security / Audit User 不由 Client 控制。

下一組 UML：

```text
Inventory Import Sequence Diagram
```
