# SupplyPilot — Package Architecture

> **文件目的**：定義 SupplyPilot V1 的 Java / Spring Boot Package Architecture、Module Boundary、Layer Responsibility、Cross-module Dependency、Transaction Boundary、AI Tool Boundary 與實際 Class 組成。  
> **文件定位**：V1 Package Architecture Baseline  
> **架構型態**：Modular Monolith  
> **Package Strategy**：Package by Feature + Layer inside Feature  
> **上游文件**：`functional-requirements.md`、`domain-model.md`、`erd.md`、`api.md`

---

# 1. Architecture Decision

SupplyPilot V1 採：

```text
Modular Monolith
+
Package by Feature
+
Layer inside Feature
```

不採全域 `controller/service/repository/entity` 分法，也不採完整 Hexagonal / Clean Architecture ceremony 或 Microservices。

V1 目標：
- Domain Boundary 清楚
- Module Responsibility 清楚
- 容易維護與測試
- 避免 Circular Dependency
- 保持 14 天 MVP 可實作性

---

# 2. Root Package

```text
com.supplypilot
└── SupplyPilotApplication.java
```

Top-level Modules：

```text
material
supplier
demand
inventory
recommendation
procurement
document
ai
user
security
config
common
```

---

# 3. Standard Dependency Rule

```text
Controller
↓
Service
↓
Domain / Repository
```

禁止：

```text
Controller → Repository
Controller → Entity mutation
AI Tool → Repository write
```

跨 Module：
- Read 可使用 Query Service / Repository Read Query / Query Interface
- Write 必須走 Owning Service / Domain

---

# 4. Material Module

```text
material/
├── controller/
│   └── MaterialController.java
├── service/
│   └── MaterialService.java
├── domain/
│   ├── Material.java
│   ├── MaterialStatus.java
│   └── BaseUnit.java
├── repository/
│   └── MaterialRepository.java
├── dto/
│   ├── request/
│   │   ├── CreateMaterialRequest.java
│   │   └── UpdateMaterialRequest.java
│   └── response/
│       └── MaterialResponse.java
└── mapper/
    └── MaterialMapper.java
```

MaterialService：
- create
- get
- search
- update
- activate / inactivate

建立 Material 時同步建立 `Inventory(quantity=0)`，兩者同一 Transaction。

---

# 5. Supplier Module

```text
supplier/
├── controller/
│   ├── SupplierController.java
│   └── SupplierMaterialController.java
├── service/
│   ├── SupplierService.java
│   └── SupplierMaterialService.java
├── domain/
│   ├── Supplier.java
│   ├── SupplierMaterial.java
│   ├── SupplierStatus.java
│   ├── SupplierMaterialStatus.java
│   └── Currency.java
├── repository/
│   ├── SupplierRepository.java
│   └── SupplierMaterialRepository.java
├── dto/
└── mapper/
```

SupplierMaterial 為正式 Domain Entity，不是單純 Join Table。

---

# 6. Demand Module

```text
demand/
├── controller/
│   └── DemandController.java
├── service/
│   └── DemandService.java
├── domain/
│   ├── Demand.java
│   └── DemandStatus.java
├── repository/
│   └── DemandRepository.java
├── dto/
└── mapper/
```

Recommendation 需要聚合 Demand 時，V1 可在 Repository 提供：

```java
BigDecimal sumActiveDemand(
    Long materialId,
    LocalDate start,
    LocalDate end
);
```

這屬 Persistence Query，不屬 Business Logic。

---

# 7. Inventory Module

```text
inventory/
├── controller/
│   ├── InventoryController.java
│   └── InventoryImportController.java
├── service/
│   ├── InventoryQueryService.java
│   ├── InventoryImportService.java
│   └── InventoryAdjustmentService.java
├── domain/
│   ├── Inventory.java
│   ├── InventoryImportBatch.java
│   ├── InventoryImportItem.java
│   ├── InventoryImportStatus.java
│   ├── StockTransaction.java
│   └── StockTransactionType.java
├── repository/
│   ├── InventoryRepository.java
│   ├── InventoryImportBatchRepository.java
│   ├── InventoryImportItemRepository.java
│   └── StockTransactionRepository.java
├── parser/
│   └── InventoryCsvParser.java
├── validator/
│   └── InventoryImportValidator.java
├── dto/
└── mapper/
```

## InventoryCsvParser

只做：

```text
CSV
↓
Parsed Row DTO
```

例如：
- rowNumber
- materialCode
- quantity
- unit

不查 DB、不更新 Inventory。

## InventoryImportValidator

負責：
- materialCode exists
- quantity valid
- unit matches
- duplicate material
- format validation

可產出 `InventoryImportValidationResult`。

Validate 完成後由 `InventoryImportService` 持久化：

```text
InventoryImportBatch
+
InventoryImportItem[]
```

Confirm 只讀取同一 Batch 的已驗證 Item，再進行 All-or-Nothing Inventory Snapshot Replace。

`Inventory` 正式於 V1 使用 `@Version`。

---

# 8. Recommendation Module

```text
recommendation/
├── controller/
│   └── RecommendationController.java
├── service/
│   └── PurchaseRecommendationService.java
├── calculator/
│   ├── NetRequirementCalculator.java
│   └── PurchaseQuantityCalculator.java
├── ranking/
│   ├── SupplierConstraintEvaluator.java
│   ├── SupplierRankingService.java
│   └── SupplierScoreCalculator.java
├── query/
│   └── IncomingInventoryQuery.java
├── domain/
│   ├── PurchaseRecommendation.java
│   ├── SupplierRecommendationResult.java
│   ├── RecommendationOutcome.java
│   └── DisqualificationReason.java
├── repository/
│   ├── PurchaseRecommendationRepository.java
│   └── SupplierRecommendationResultRepository.java
├── dto/
└── mapper/
```

## NetRequirementCalculator

純計算：

```text
Expected Demand
+ Safety Stock
- Current Inventory
- Incoming Inventory
```

結果：

```text
max(0, raw)
```

不查 DB、不存 Entity。

## PurchaseQuantityCalculator

輸入：
- netRequirement
- MOQ
- packageMultiple

輸出：
- supplier-specific recommendedQuantity

## SupplierConstraintEvaluator

負責 Hard Constraint：
- Supplier ACTIVE
- SupplierMaterial ACTIVE
- Delivery Feasibility

例如：

```text
currentDate + leadTimeDays <= requiredDate
```

## SupplierScoreCalculator

V1 Baseline：

```text
Price       40%
Lead Time   30%
Reliability 20%
Quality     10%
```

只計算 Score，不做 DB Query。

## SupplierRankingService

流程：

```text
Qualified Candidates
↓
Score
↓
Sort
↓
Tie Break
↓
Rank
```

Tie Break：
1. lower total purchase cost
2. shorter lead time
3. higher reliability
4. deterministic supplier id/code

## PurchaseRecommendationService

負責 Orchestration：

```text
Load Material
↓
Aggregate Demand
↓
Read Inventory
↓
Query Incoming
↓
NetRequirementCalculator
↓
Load Supplier Candidates
↓
ConstraintEvaluator
↓
PurchaseQuantityCalculator
↓
SupplierRankingService
↓
Persist Snapshot
```

原則：

> Service 負責流程，Calculator 負責公式。

---

# 9. IncomingInventoryQuery

為避免：

```text
recommendation ↔ procurement
```

互相 Service Injection，建立：

```text
recommendation/query/
└── IncomingInventoryQuery.java
```

概念：

```java
public interface IncomingInventoryQuery {
    BigDecimal calculateIncomingQuantity(
        Long materialId,
        LocalDate requiredDate
    );
}
```

---

# 10. Procurement Module

```text
procurement/
├── controller/
│   └── PurchaseOrderController.java
├── service/
│   ├── PurchaseOrderService.java
│   └── ReceivingService.java
├── query/
│   └── PurchaseOrderIncomingInventoryQuery.java
├── domain/
│   ├── PurchaseOrder.java
│   ├── PurchaseOrderItem.java
│   └── PurchaseOrderStatus.java
├── repository/
│   └── PurchaseOrderRepository.java
├── dto/
└── mapper/
```

## PurchaseOrderIncomingInventoryQuery

實作 `IncomingInventoryQuery`。

查詢：

```text
PO.status = ORDERED
AND expectedDeliveryDate <= requiredDate
AND material = requested material
```

APPROVED 不計入 Incoming。

## PurchaseOrderItem Repository Decision

V1 不建立一般用途 `PurchaseOrderItemRepository`。

原因：
- PurchaseOrderItem 是 Aggregate Child
- 不應獨立 CRUD
- 由 PurchaseOrderRepository 隨 Aggregate 保存

## PurchaseOrderService

負責：
- createDraft
- updateDraft
- get
- search
- approve
- reject
- markOrdered

Receiving 獨立給 `ReceivingService`。

## PurchaseOrder Domain Behavior

建議 Domain Method：

```text
approve()
reject()
markOrdered()
receive()
```

Entity 自己保護合法 State Transition。

Approve / Reject 的 status、reason 與 authenticated-user audit 由同一 Aggregate method 修改，並在同一 Service transaction 保存。

`PurchaseOrder` 正式於 V1 使用 `@Version`，optimistic conflict 對外映射為 `409 CONCURRENT_MODIFICATION`。

---

# 11. ReceivingService

Receiving 跨：

```text
PurchaseOrder
Inventory
StockTransaction
```

因此獨立 Service：

```text
@Transactional

Load PO
↓
Validate ORDERED
↓
for each item:
    Load Inventory
    Inventory.receive(quantity)
    Create StockTransaction(PURCHASE_RECEIVE)
↓
PO.receive()
↓
Commit
```

任一步失敗：

```text
Rollback
```

Inventory Entity 建議提供：
- `receive(quantity)`
- `adjust(quantity)`

避免 Service 到處 `setQuantity()`。

---

# 12. StockTransaction Decision

V1 不建立 general-purpose：

```text
StockTransactionService.create(...)
```

StockTransaction 只能由：
- ReceivingService
- InventoryAdjustmentService

產生。

如需查詢，可另外建立 `StockTransactionQueryService`。

---

# 13. Document Module

```text
document/
├── controller/
│   └── DocumentController.java
├── service/
│   ├── DocumentService.java
│   ├── DocumentStorageService.java
│   └── DocumentIndexingService.java
├── infrastructure/
│   └── S3DocumentStorageService.java
├── domain/
│   ├── DocumentMetadata.java
│   ├── DocumentType.java
│   └── DocumentStatus.java
├── repository/
│   └── DocumentMetadataRepository.java
├── dto/
└── mapper/
```

`DocumentStorageService` 抽象 Storage 行為，Domain 不直接知道 AWS S3 SDK。

`DocumentStatus`：

```text
PROCESSING
READY
FAILED
INACTIVE
```

Document ingestion 與 MySQL 不做 distributed transaction，採 eventually consistent。

---

# 14. AI Module

```text
ai/
├── controller/
│   └── AiController.java
├── service/
│   └── AiAssistantService.java
├── tool/
│   ├── RecommendationTool.java
│   ├── PurchaseOrderTool.java
│   ├── InventoryTool.java
│   └── DocumentSearchTool.java
├── rag/
│   ├── RetrievalService.java
│   └── RagAnswerService.java
├── client/
│   └── AiProviderClient.java
└── dto/
```

Spring AI / LLM Integration Detail 儘量集中在 `ai/`。

## AI Tool Boundary

```text
PurchaseOrderTool
↓
PurchaseOrderService.createDraft()
```

不是：

```text
PurchaseOrderTool
↓
PurchaseOrderRepository.save()
```

同理：
- RecommendationTool → PurchaseRecommendationService
- InventoryTool → InventoryQueryService
- DocumentSearchTool → RetrievalService / DocumentService

AI 不可直接：
- Approve
- Order
- Receive
- Modify Inventory

---

# 15. User Module

```text
user/
├── controller/
├── service/
├── domain/
│   ├── User.java
│   ├── UserRole.java
│   └── UserStatus.java
├── repository/
├── dto/
└── mapper/
```

---

# 16. Security Package

```text
security/
├── SecurityConfig.java
├── JwtAuthenticationFilter.java
├── JwtTokenService.java
├── AuthenticatedUser.java
├── CurrentUserProvider.java
└── CustomUserDetailsService.java
```

Service 可透過 `CurrentUserProvider` 取得：
- current user id
- username
- role

避免由 Client 傳 Audit User。

---

# 17. Common Package

```text
common/
├── exception/
│   ├── ErrorCode.java
│   ├── BusinessException.java
│   ├── GlobalExceptionHandler.java
│   ├── ErrorResponse.java
│   └── FieldErrorResponse.java
└── pagination/
    └── PageResponse.java
```

禁止 Common 變成：
- Utils.java
- Helper.java
- CommonService.java
- Misc.java

---

# 18. Exception Boundary

Domain / Service 不直接知道 HTTP。

不要：

```java
throw new ResponseStatusException(HttpStatus.CONFLICT);
```

應：

```text
Domain / Service
↓
BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION)
↓
GlobalExceptionHandler
↓
HTTP 409
```

Domain 表達 Business Meaning，Web Layer 負責 HTTP Meaning。

---

# 19. Mapper Rule

Mapper 負責：
- Entity → Response DTO
- Request DTO → initial input / command structure

Mapper 不負責：
- Business Validation
- Repository Query
- Transaction
- Lifecycle Transition

V1 可手寫 Mapper，不必先引入 MapStruct。

---

# 20. Service Interface Decision

V1 不機械式建立：

```text
MaterialService interface
MaterialServiceImpl
```

若只有一個 Implementation，直接使用：

```text
MaterialService
```

只有存在多 implementation / replaceable adapter 時才抽 interface。

---

# 21. Transaction Boundary

主要放 Service Layer。

Command：

```text
@Transactional
```

例如：
- createMaterial
- confirmInventoryImport
- adjustInventory
- createPurchaseOrder
- approvePurchaseOrder
- markOrdered
- receivePurchaseOrder

Query 可視需要：

```text
@Transactional(readOnly = true)
```

不在 Controller 建 Transaction Boundary。

完整收貨由獨立 `procurement/service/ReceivingService` 協調 `PurchaseOrder`、`Inventory` 與 `StockTransaction`；不得把跨 Aggregate 流程放入 `PurchaseOrderController` 或一般 PO CRUD/lifecycle service。

---

# 22. Cross-module Read / Write Rule

## Read

可透過：
- Repository Read Query
- Query Service
- Query Interface

## Write

必須走：
- Owning Service
- Owning Domain

避免：

```text
Module A → directly mutate Module B Entity
```

---

# 23. Circular Dependency Rule

最重要案例：

```text
recommendation ↔ procurement
```

解法：

```text
recommendation
↓
IncomingInventoryQuery
↑
procurement implementation
```

禁止：

```text
PurchaseRecommendationService
↔
PurchaseOrderService
```

互相 Injection。

---

# 24. Full Package Tree

```text
com.supplypilot
│
├── SupplyPilotApplication.java
├── material/
│   ├── controller/
│   ├── service/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   │   ├── request/
│   │   └── response/
│   └── mapper/
├── supplier/
│   ├── controller/
│   ├── service/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   └── mapper/
├── demand/
│   ├── controller/
│   ├── service/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   └── mapper/
├── inventory/
│   ├── controller/
│   ├── service/
│   ├── domain/
│   ├── repository/
│   ├── parser/
│   ├── validator/
│   ├── dto/
│   └── mapper/
├── recommendation/
│   ├── controller/
│   ├── service/
│   ├── calculator/
│   ├── ranking/
│   ├── query/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   └── mapper/
├── procurement/
│   ├── controller/
│   ├── service/
│   ├── query/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   └── mapper/
├── document/
│   ├── controller/
│   ├── service/
│   ├── infrastructure/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   └── mapper/
├── ai/
│   ├── controller/
│   ├── service/
│   ├── tool/
│   ├── rag/
│   ├── client/
│   └── dto/
├── user/
│   ├── controller/
│   ├── service/
│   ├── domain/
│   ├── repository/
│   ├── dto/
│   └── mapper/
├── security/
├── config/
└── common/
    ├── exception/
    └── pagination/
```

---

# 25. Architecture Rules — Final

正式鎖定：

1. 採 Modular Monolith。
2. 採 Package by Feature。
3. Feature 內再分 Layer。
4. 不使用全域 Controller / Service / Repository 資料夾。
5. Domain Entity 放 `domain/`。
6. Controller 不直接操作 Repository。
7. Controller 不直接修改 Entity。
8. Business Lifecycle Rule 放 Domain / Service。
9. Transaction Boundary 放 Service。
10. Calculator 不查 DB。
11. Repository 不放 Business Logic。
12. AI Tool 不直接操作 Repository。
13. AI Tool 與 REST API 共用 Application Service。
14. Recommendation / Procurement 使用 `IncomingInventoryQuery` 解 Circular Dependency。
15. PurchaseOrderItem 不建立一般獨立 Controller / Service。
16. PurchaseOrderItem 預設透過 PurchaseOrder Aggregate 保存。
17. StockTransaction 不提供 general-purpose Create Service。
18. Cross-module Read 可使用 Query Boundary。
19. Cross-module Write 必須走 Owning Service / Domain。
20. `common/` 只放真正 Cross-cutting Concern。
21. Domain / Service 不直接依賴 HTTP Status。
22. V1 不機械式建立 Service Interface + Impl。
23. Mapper 不承擔 Business Logic。
24. AI 不得污染核心 Deterministic Domain Logic。
25. AI Provider Failure 時核心 Domain 仍可正常運作。

---

# 26. Implementation Priority

建議順序：

```text
1. common / security base
2. material
3. supplier
4. demand
5. inventory
6. recommendation
7. procurement
8. document
9. ai
```

理由：

```text
Master Data
↓
Planning / Inventory
↓
Decision Engine
↓
Procurement Transaction
↓
AI Enhancement
```

核心流程先於 AI。

---

# 27. Next Stage

下一階段：

```text
Package Architecture
↓
Transaction Boundary Specification
↓
Validation / Exception Strategy
↓
Security Design
↓
Testing Strategy
↓
Implementation Plan
```

下一份建議文件：

```text
transaction-boundary.md
```

應明確定義：
- 哪些 Use Case 必須 `@Transactional`
- 哪些操作失敗需要 Rollback
- Receiving Transaction
- Inventory Import Transaction
- PO Lifecycle Transaction
- Recommendation Snapshot Transaction
- Concurrency / Lock Boundary
