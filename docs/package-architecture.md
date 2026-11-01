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

---

## Spring AI Implementation Decision — SP-720 / SP-721 (2026-10-11)

- SP-720 必須使用與 Spring Boot 4.1.x 相容的穩定版 **Spring AI 2.0.x**，以 `ChatClient` / `ChatModel` 整合真正的 LLM；以 `@Tool` 或 `ToolCallback` 實作 Tool Calling。禁止使用 Spring AI 2.1 Milestone 當作正式部署基準。
- `ai/` 擁有 ChatClient 與 Tool Registry / Allowlist；Tool 僅提供 Recommendation、Supplier Ranking、Inventory、Open PO、SP-710 Document Query 的唯讀能力，且呼叫原有 Application / Query Service。
- Spring AI Tool 執行必須保留當前登入使用者 SecurityContext、Service Method Security、CurrentUserProvider；禁止 System Admin 與 Repository Write。對 User 提供任意 class/method/SQL 執行能力均不允許。
- 保留 SP-710 原有 `DocumentQueryService`、索引與來源證據契約，不因導入 Spring AI 改寫；SP-721 延續同一 ChatClient / Tool Calling，Proposal 與 Backend 確認後的 `createDraft` 嚴格分離。
- Provider 呼叫不可置於全域長交易內，AI Provider 不可成為核心採購 API 的必要依賴。
- 工程決策詳細說明見 `docs/adr/ADR-SP-720-spring-ai-adoption.md`。

---

## Confirmed SP-720/721/722 AI Decisions (2026-10-11)

- **Google Gemini** is the SP-720 LLM provider; prioritize a low-cost **Flash** model. Verify exact Chat Model ID, Tool Calling capability and price at implementation time; keep model ID configurable.
- Use stable **Spring AI 2.0.x** `ChatClient` / `ChatModel` and explicit Spring AI read-only Tool Calling under current-user Backend Authorization. AI-generated explanations require backend verification of structured authoritative figures.
- **SP-722 (#80)** is the dedicated Semantic RAG upgrade using **Google Gemini Embedding + PostgreSQL / pgvector**; this supersedes any earlier suggestion that MySQL / application-side cosine is the final semantic vector store. Existing **SP-710 MySQL lexical index remains transitional**; do not rewrite it inside SP-720.
- MySQL continues holding procurement transactions and document metadata; pgvector owns semantic document chunk/vector indexes. Cross-database updates are not atomic; SP-722 must design idempotent reindex, READY-status gating, and failure recovery.
- V1 AI requests are **single-turn, no chat memory, synchronous JSON**. Disable AI cleanly when configuration/provider unavailable (AI 503, core APIs unaffected).
- SP-721 requires **Proposal → server-issued Confirm Token/ID → Backend verification → DRAFT via existing Service**; never treat an LLM statement or simple client `confirmed=true` as sufficient.
- Basic cost / observability boundaries: token count when available, latency, error metrics, capped output/tool-call iterations, provider timeout and sensitive-data-redacted logs.
- Full decision record: `docs/adr/ADR-SP-720-spring-ai-adoption.md`; semantic work: GitHub Issue #80.

---

## SP-720 已確認的產品行為決策（2026-10-11；八項補充）

1. **單一模型**：Google Gemini，固定使用一個低成本 Flash 類 Chat Model；以可配置、經驗證的 model ID 部署；本 Issue 不實作多模型切換或自動 fallback 到旗艦模型。
2. **憑證與額度**：使用 Google AI Studio API Key，透過安全環境設定注入；啟用月度用量／預算觀察及超額警示設計。注意平台費用警示不等於應用端硬性費用上限；未取得可靠官方預算控制能力前不可宣稱已強制封頂。按可用 usage metadata 做估算與告警，不能取得時應明確標示無法精確計價。
3. **複合查詢**：單次問題可以由 Spring AI 選擇並依序呼叫多個*已核准唯讀* Tools；仍需 Tool Call 次數／迴圈數／Timeout 上限，所有 Tools 以目前使用者身份再次授權，不擴張成寫入工具。
4. **缺少必要識別資料**：不猜測 Material、Recommendation、Supplier 或 PO；回覆需要補充的欄位，不觸發未授權或任意猜測查詢。V1 沒有 Chat Memory，後續需重新提出完整問句。
5. **回答契約**：自然語言說明 + 可核對的 Backend 數值與來源。後端維持結構化欄位的權威，來源不由 LLM 自行產生；維持原 AI Query JSON contract，若須新增明確的補充資訊狀態，先以最小相容性調整實作並補 API 測試。
6. **模型回答不可信時安全降級**：當**已成功取得授權的、足夠且可信的 Backend Facts**，但 LLM 的文字含有矛盾／未被支援的數值或內容時，可改回由 Backend 產出的確定性 facts answer，且標明 fallback 來源；不能把模型不可信內容當成權威。**Provider unavailable／timeout／無 Key 應依原規格回 503，不能用 fallback 掩蓋；RAG INSUFFICIENT_EVIDENCE 必須維持證據不足，不得改寫為肯定答案。** 無可驗證 Backend Facts 時不得 fallback 成虛構答案。對 Provider invalid-response 的錯誤碼／fallback 邊界須在測試中明示。
7. **資料出站最小化**：只把完成特定查詢所必需的工具欄位與 RAG 相關片段傳送 Gemini；最小化 Supplier／Contract 等商業敏感資訊，嚴禁傳送 Credential、完整資料庫 Dump 或無關文件。Tool Result 應有欄位白名單／長度限制並避免記錄原始機敏 Payload。
8. **真實 Demo**：正式 Demo 使用真正的 Gemini Provider；未配置 Key 時 AI Query 回既有 503，但 Core API 正常可用。Fake ChatModel 僅供 Automated Tests，不能以 deterministic formatter 宣稱完成正式 AI Demo。

### 八項新增驗收／測試
- [ ] 真實 ChatClient + 單一 Gemini Flash 模型可由安全設定啟用；不依賴免費／付費配額推測。
- [ ] 同一查詢可呼叫兩個以上已核准唯讀 Tools，且受工具執行數量／輪數／時間上限限制；沒有寫入。
- [ ] 缺少必要識別資料時不猜測，回覆使用者需補充的資訊。
- [ ] 正常回答附可核對的結構化數值與來源；數值錯置／矛盾時安全 fallback 到 Backend Facts 或依明確條件失敗。
- [ ] Provider unavailable／timeout／未配置 Key 回 503；RAG 證據不足仍回 INSUFFICIENT_EVIDENCE。
- [ ] Gemini Request Payload 只含必要資料／相關文件片段，排除金鑰與無關商業資料。
- [ ] 能記錄／估算請求用量、延遲、失敗與預算警示，不能宣稱 Google AI Studio 原生具備未驗證的硬額度限制。
- [ ] Test 使用可控制的 Fake ChatModel，不呼叫真實付費 Provider；Demo 手動 Smoke Test 使用真實 Gemini。

---

## 目前 Spring AI 文件 RAG 架構（本機 main，2026-11-01）

本節更新較早 SP-710／SP-722 文件問答路徑描述；Spring AI 查詢與分類程式已在本機 main。此本機程式狀態不表示最新變更已部署到 Demo／正式服務。

```text
POST /api/v1/document-queries ─┐
                              ├─ DocumentQueryService
AI SEARCH_DOCUMENTS ──────────┘       │
                                      ▼
                              ChatClient + Gemini ChatModel
                                      │
                           RetrievalAugmentationAdvisor
                                      │
                           VectorStoreDocumentRetriever
                                      │
                  PgVectorStore + Gemini EmbeddingModel
                                      │
                 PostgreSQL semantic_rag_vectors
```

問答與 AI Tool 仍共用同一個 `DocumentQueryService`；`DocumentQueryController` 維持 `/api/v1/document-queries` API。索引流程是 `DocumentIngestionService → SpringAiDocumentProcessingService (TextReader/TikaDocumentReader + TokenTextSplitter) → DocumentIndexPersistenceService (MySQL source chunks) → approved durable SemanticIndexWorker → SpringAiVectorIndexService → PgVectorStore`。新 Query 只使用 Advisor／PgVectorStore；MySQL `document_metadata` 與 `document_chunks` 仍提供權威狀態及來源證明。PostgreSQL DataSource 與 MySQL `DataSource` 分開注入。

`DocumentPreviewService` 負責使用者預覽；預覽原始段落／表格契約不能以 RAG 的 Tika 輸出取代。`SpringAiDocumentProcessingService` 用 `TextReader`／Tika、`TokenTextSplitter` 與可驗證 offset 建立 MySQL source chunks；PDF/DOCX Tika 解析受 `DOCUMENT_RAG_PARSER_TIMEOUT`（預設 30 秒）和輸入大小限制。`OutboundDocumentPolicy`、逐文件審核、job 重試與 Demo provenance 在索引／發布流程執行。RAG Advisor 以 generation、READY、APPROVED 及明確供應商代碼 metadata filter 檢索候選，並交給模型判斷。`AiToolResultPolicy` 只處理 `/api/v1/ai/query` 的工具狀態、複合查詢與業務代碼涵蓋，不做文件摘錄支持判斷。Gemini 生成後不逐句驗證答案主張。

PgVectorStore 的正式資料表為 `semantic_rag_vectors`，先用 `indexType=NONE` 做 exact similarity。`SpringAiVectorIndexService` 只發布完整且仍核准的向量 generation；發布交易也從此向量表更新 `semantic_chunks` 的 Agentic SQL 相容投影。MySQL `document_chunks` 與 PostgreSQL `semantic_chunks`／`semantic_document_indexes`／`semantic_generations`／`semantic_gate_audit` schema、既有資料與 Agentic SQL 可讀欄位均保留。`SemanticVectorCleanupScheduler` 預設每 300,000 毫秒清理一頁失效／舊代次向量。

相容性與驗收：`semantic_chunks` 相容投影與既有 Agentic SQL contract 均有 Testcontainers 驗證。舊 lexical／semantic retrieval 類別已移除；`RagDocumentPostProcessor` 與其測試也已從目前程式移除。最新 PostProcessor 移除後的 Advisor 精準測試 10/10、RAG Testcontainers 問答矩陣 42/42 通過；完整 Maven `verify` 未在這次修改後重跑。舊版本的測試結果與部署記錄仍保留於 [`RAG 驗證記錄`](evidence/spring-ai-rag-validation-2026-11-01.md)。

遷移驗證與環境狀態見 [`semantic-rag-runbook.md`](semantic-rag-runbook.md)。`SEMANTIC_READ_ENABLED` 程式預設為 `false`；部署設定可另行開啟。正式環境已記錄 V3 migration、121 份回填及 readiness/gate，正式 Gemini 問答 2 題 PASS、1 題 PARTIAL；隔離本機 Demo 三題 Gemini 問答記錄為 3/3 PASS。最新本機程式尚未部署，Browser E2E 未執行。歷史全量 Maven `verify` 曾 FAIL；此次 PostProcessor 修正後未重跑全量套件。詳見 [`RAG 驗證記錄`](evidence/spring-ai-rag-validation-2026-11-01.md) 與 [`正式環境遷移記錄`](evidence/spring-ai-rag-production-migration-2026-11-01.md)。

