# SupplyPilot — Validation & Exception Strategy

> **文件目的**：定義 SupplyPilot V1 的 Validation 分層、Business Exception、Error Code、HTTP Mapping、Import Row Error、Concurrency Error 與 Error Response 規格。  
> **文件定位**：V1 Validation / Exception Baseline  
> **上游文件**：
> - `functional-requirements.md`
> - `domain-model.md`
> - `erd.md`
> - `api.md`
> - `package-architecture.md`
> - `transaction-boundary.md`

---

# 1. Validation Layers

SupplyPilot V1 將 Validation 分成四層：

```text
1. Request / DTO Validation
2. Application / Service Validation
3. Domain Invariant Validation
4. Database Constraint
```

每一層負責不同問題，避免重複與責任混亂。

---

# 2. DTO Validation

DTO Validation 只處理：

> Request 本身的格式與單欄位條件是否合法。

常用：

```java
@NotBlank
@NotNull
@Positive
@PositiveOrZero
@Size
@FutureOrPresent
```

例如：

```text
name = ""
quantity = -5
materialId = null
```

此類錯誤應在 Controller 入口層被擋下。

---

# 3. DTO 不負責 Business Rule

DTO 不處理：

```text
Material 是否存在
Material 是否 ACTIVE
Supplier 是否 ACTIVE
PO 是否為 DRAFT
Supplier 是否供應該 Material
Recommendation 是否屬於該 Material
```

V1 不建立大量：

```text
@ValidSupplier
@ValidPurchaseOrder
@ActiveMaterial
```

這類需要 Repository / Domain State 的自訂 Annotation。

---

# 4. Application / Service Validation

Service 負責：

> 需要查詢其他 Entity、Repository、Current Date 或 Cross-module Data 才能判斷的規則。

例如 Create Purchase Order：

```text
Supplier exists
Supplier ACTIVE
Material exists
Material ACTIVE
SupplierMaterial exists
SupplierMaterial ACTIVE
Recommendation exists
Recommendation matches Material
SupplierRecommendationResult belongs to Recommendation
Override reason required if recommendation changed
```

這些不屬於 DTO Validation。

---

# 5. Domain Invariant Validation

Entity 自己保護：

> 無論從 REST、AI Tool、Batch Job 或其他 Service 呼叫，都不能違反的規則。

例如：

```text
PurchaseOrder.approve()
→ only DRAFT can be approved
→ expectedDeliveryDate must exist at approval

PurchaseOrder.markOrdered()
→ only APPROVED can become ORDERED

Inventory.adjust()
→ resulting quantity cannot be negative
```

Domain 不依賴 HTTP。

API 層將核准缺交期的 Domain Error `EXPECTED_DELIVERY_DATE_REQUIRED` 對應為 HTTP 400；DRAFT 建立與編輯仍允許空交期。

---

# 6. Database Constraint

DB 是最後防線。

負責：

```text
PRIMARY KEY
FOREIGN KEY
UNIQUE
NOT NULL
CHECK
@Version
```

例如：

```text
UNIQUE material.code
UNIQUE supplier.code
UNIQUE supplier_material(supplier_id, material_id)
UNIQUE purchase_order.po_number
```

即使 Application Validation 發生 race condition，DB 仍必須保證資料完整性。

---

# 7. Example — Create Demand

## DTO

```text
materialId required
quantity > 0
requiredDate required
```

## Service

```text
Material exists
Material ACTIVE
requiredDate >= currentDate
```

## Domain

```text
Demand quantity > 0
ACTIVE → CANCELLED only
```

## DB

```text
material_id FK
quantity NOT NULL
status NOT NULL
```

---

# 8. Exception Strategy

V1 不建立大量 Exception subclass：

```text
MaterialNotFoundException
SupplierNotFoundException
PurchaseOrderNotEditableException
...
```

核心採：

```text
BusinessException
+
ErrorCode
```

Concept：

```java
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

}
```

---

# 9. Runtime Exception and Rollback

`BusinessException` extends：

```text
RuntimeException
```

因此 Spring Transaction 預設：

```text
BusinessException
→ rollback
```

避免 checked exception 導致 transaction 意外 commit。

---

# 10. ErrorCode

`ErrorCode` 只描述：

```text
Business Meaning
```

例如：

```text
MATERIAL_NOT_FOUND
SUPPLIER_INACTIVE
INVALID_PO_STATUS_TRANSITION
CONCURRENT_MODIFICATION
```

Domain 不直接依賴：

```text
HttpStatus
ResponseEntity
Web Layer
```

---

# 11. HTTP Mapping

HTTP Mapping 在 Web / Exception Handler 層處理。

概念：

```text
ErrorCode
↓
ErrorHttpStatusMapper / GlobalExceptionHandler
↓
HttpStatus
```

例如：

```text
MATERIAL_NOT_FOUND
→ 404

INVALID_PO_STATUS_TRANSITION
→ 409

SUPPLIER_INACTIVE
→ 400

CONCURRENT_MODIFICATION
→ 409
```

---

# 12. GlobalExceptionHandler

統一處理：

```text
BusinessException
MethodArgumentNotValidException
ConstraintViolationException
ObjectOptimisticLockingFailureException
DataIntegrityViolationException
Exception
```

---

# 13. Standard Error Response

```json
{
  "code": "INVALID_PO_STATUS_TRANSITION",
  "message": "Purchase order cannot be approved in current status.",
  "traceId": "6f09e0a2...",
  "timestamp": "2026-10-08T00:00:00Z",
  "path": "/api/v1/purchase-orders/10/approve"
}
```

---

# 14. Validation Error Response

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed.",
  "traceId": "abc123...",
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

HTTP：

```text
400 Bad Request
```

---

# 15. Trace ID

ErrorResponse V1 正式包含：

```text
traceId
```

用途：

```text
Client Error Response
↓
traceId
↓
Application Log
↓
Server-side Troubleshooting
```

---

# 16. Resource Not Found Strategy

Repository lookup：

```java
repository.findById(id)
    .orElseThrow(() ->
        new BusinessException(ErrorCode.MATERIAL_NOT_FOUND)
    );
```

不使用：

```text
null
```

一路往下傳。

---

# 17. Not Found vs Inactive

必須區分：

```text
MATERIAL_NOT_FOUND
MATERIAL_INACTIVE

SUPPLIER_NOT_FOUND
SUPPLIER_INACTIVE
```

存在但停用，不等於不存在。

---

# 18. Purchase Order Errors

建議區分：

```text
INVALID_PO_STATUS_TRANSITION
PO_NOT_EDITABLE
```

例如：

```text
RECEIVED → APPROVED
```

是：

```text
INVALID_PO_STATUS_TRANSITION
```

而：

```text
PATCH APPROVED PO Items
```

是：

```text
PO_NOT_EDITABLE
```

---

# 19. Optimistic Lock Exception

V1：

```text
PurchaseOrder.@Version
Inventory.@Version
```

遇到：

```text
ObjectOptimisticLockingFailureException
```

轉為：

```text
CONCURRENT_MODIFICATION
```

HTTP：

```text
409 Conflict
```

Message：

```text
The resource was modified by another request. Refresh and try again.
```

V1 不自動 retry。

---

# 20. Database Duplicate Constraint

Service 可以先：

```text
existsByCode(...)
```

但不能依賴它作最終保證。

Concurrent Request 仍可能：

```text
Request A → not exists
Request B → not exists
A insert
B insert
```

DB Unique Constraint 必須攔下 B。

---

# 21. DataIntegrityViolationException

不能全部映射成：

```text
DUPLICATE_CODE
```

因為可能是：

```text
Unique violation
FK violation
NOT NULL violation
CHECK violation
```

策略：

1. Application Validation 優先產生精準 Business Error。
2. DB Constraint 作 safety net。
3. 若能安全辨識 constraint name，轉成精準 ErrorCode。
4. 無法精準辨識時：

```text
DATA_INTEGRITY_VIOLATION
409
```

---

# 22. Inventory Import Validation

Inventory Import 的 Row Error 不等於 Request Exception。

Validate API：

```text
POST /inventory-imports/validate
```

目的本來就是：

```text
Preview validation results
```

所以：

```text
MATERIAL_NOT_FOUND
UNIT_MISMATCH
INVALID_QUANTITY
DUPLICATE_MATERIAL
```

應變成 Row-level Validation Result。

---

# 23. Inventory Import — File Level Error

例如：

```text
INVALID_FILE_FORMAT
FILE_TOO_LARGE
FILE_EMPTY
CORRUPTED_FILE
```

屬 Request-level Error。

HTTP：

```text
400
```

---

# 24. Inventory Import — Row Level Error

例如：

```json
{
  "row": 7,
  "materialCode": "MAT-001",
  "code": "UNIT_MISMATCH",
  "message": "Expected KG but received PCS."
}
```

API 可以：

```text
HTTP 200
status = VALIDATION_FAILED
```

因為 API 本身成功完成「驗證」這個行為。

---

# 25. Recommendation Outcome

原本只看：

```text
recommendedSupplierId == null
```

不足以判斷原因。

因此 V1 正式新增：

```text
PurchaseRecommendation.outcome
```

Enum：

```text
PURCHASE_NOT_REQUIRED
SUPPLIER_RECOMMENDED
NO_QUALIFIED_SUPPLIER
```

---

# 26. Recommendation Outcome Meaning

## PURCHASE_NOT_REQUIRED

```text
netRequirement = 0
```

## SUPPLIER_RECOMMENDED

```text
netRequirement > 0
+
at least one qualified supplier
```

## NO_QUALIFIED_SUPPLIER

```text
netRequirement > 0
+
zero qualified supplier
```

---

# 27. NO_QUALIFIED_SUPPLIER

這是：

```text
valid recommendation outcome
```

不是一般 Exception。

API 可正常：

```text
201 Created
```

例如：

```json
{
  "id": 501,
  "outcome": "NO_QUALIFIED_SUPPLIER",
  "calculation": {
    "netRequirement": 150
  },
  "recommendation": {
    "supplierId": null,
    "quantity": null,
    "score": null
  }
}
```

---

# 28. Recommendation Reference Errors

以下仍是 Exception：

```text
RECOMMENDATION_NOT_FOUND
INVALID_RECOMMENDATION_REFERENCE
INVALID_SUPPLIER_RECOMMENDATION_REFERENCE
```

例如 PO Item 指向不屬於該 Material 的 Recommendation。

---

# 29. RAG Insufficient Evidence

```text
RAG_INSUFFICIENT_EVIDENCE
```

不應當成 Provider Failure。

較合理：

```json
{
  "status": "INSUFFICIENT_EVIDENCE",
  "answer": null,
  "sources": []
}
```

屬合法 Application Result。

---

# 30. AI Provider Exception

真正 External Failure：

```text
AI_PROVIDER_UNAVAILABLE
AI_TIMEOUT
AI_INVALID_RESPONSE
```

HTTP 可映射：

```text
503 Service Unavailable
```

或依實際 Provider Error 細分。

---

# 31. Security Errors

Security Error 主要由 Spring Security 處理。

```text
UNAUTHENTICATED
→ 401

ACCESS_DENIED
→ 403
```

建議使用：

```text
AuthenticationEntryPoint
AccessDeniedHandler
```

而不是 Service 內到處自行拋 Security BusinessException。

---

# 32. Logging Strategy

## Expected 4xx Business Error

例如：

```text
MATERIAL_NOT_FOUND
INVALID_PO_STATUS_TRANSITION
VALIDATION_FAILED
```

通常：

```text
INFO / WARN
```

不要全部 ERROR。

## Unexpected 5xx

```text
ERROR
+
stack trace
+
traceId
```

## Security Failure

通常：

```text
WARN
```

依情境調整。

---

# 33. ErrorCode Baseline

## Resource

```text
MATERIAL_NOT_FOUND
SUPPLIER_NOT_FOUND
SUPPLIER_MATERIAL_NOT_FOUND
DEMAND_NOT_FOUND
INVENTORY_NOT_FOUND
PURCHASE_ORDER_NOT_FOUND
RECOMMENDATION_NOT_FOUND
DOCUMENT_NOT_FOUND
USER_NOT_FOUND
INVENTORY_IMPORT_BATCH_NOT_FOUND
```

## Status / Lifecycle

```text
MATERIAL_INACTIVE
SUPPLIER_INACTIVE
SUPPLIER_MATERIAL_INACTIVE
USER_INACTIVE
DEMAND_NOT_ACTIVE
PO_NOT_EDITABLE
INVALID_PO_STATUS_TRANSITION
```

## Validation

```text
VALIDATION_FAILED
INVALID_QUANTITY
INVALID_DATE_RANGE
UNIT_MISMATCH
DUPLICATE_MATERIAL
DUPLICATE_CODE
EXPECTED_DELIVERY_DATE_REQUIRED
REJECT_REASON_REQUIRED
SUPPLIER_OVERRIDE_REASON_REQUIRED
QUANTITY_OVERRIDE_REASON_REQUIRED
```

## Inventory

```text
INSUFFICIENT_INVENTORY
INVENTORY_IMPORT_NOT_VALIDATED
INVENTORY_IMPORT_ALREADY_CONFIRMED
INVALID_FILE_FORMAT
FILE_EMPTY
FILE_TOO_LARGE
CONCURRENT_MODIFICATION
```

## Recommendation

```text
INVALID_RECOMMENDATION_REFERENCE
INVALID_SUPPLIER_RECOMMENDATION_REFERENCE
```

## Infrastructure / Integrity

```text
DATA_INTEGRITY_VIOLATION
INTERNAL_SERVER_ERROR
```

## AI

```text
AI_PROVIDER_UNAVAILABLE
AI_TIMEOUT
AI_INVALID_RESPONSE
```

---

# 34. HTTP Mapping Baseline

| Category | HTTP |
|---|---:|
| DTO Validation | 400 |
| Business Validation | 400 |
| Inactive Resource | 400 |
| Authentication | 401 |
| Authorization | 403 |
| Not Found | 404 |
| Duplicate | 409 |
| Illegal Lifecycle | 409 |
| Concurrency Conflict | 409 |
| Data Integrity Conflict | 409 |
| AI Provider Failure | 503 |
| Unexpected Error | 500 |

---

# 35. Validation Flow

```text
Request
↓
DTO Validation
│
├── fail
│   → 400 VALIDATION_FAILED
│
└── pass
    ↓
Service Validation
    │
    ├── fail
    │   → BusinessException
    │
    └── pass
        ↓
Domain Method
        │
        ├── invariant fail
        │   → BusinessException
        │
        └── pass
            ↓
Database
            │
            ├── unique/FK/check fail
            │   → mapped integrity error
            │
            ├── optimistic lock fail
            │   → 409 CONCURRENT_MODIFICATION
            │
            └── success
```

---

# 36. Formal Domain Revision

V1 正式新增：

```text
RecommendationOutcome
```

到：

```text
PurchaseRecommendation
```

欄位：

```text
outcome
```

Enum：

```text
PURCHASE_NOT_REQUIRED
SUPPLIER_RECOMMENDED
NO_QUALIFIED_SUPPLIER
```

此修正需同步：

```text
domain-model.md
erd.md
erd.html
api.md
```

---

# 37. Final Validation / Exception Rules

正式鎖定：

1. Validation 分 DTO / Service / Domain / DB 四層。
2. DTO 只處理格式與單欄位條件。
3. Cross-entity Business Rule 放 Service。
4. Entity 自己保護 invariant。
5. DB Constraint 作最後防線。
6. 使用 `BusinessException + ErrorCode`。
7. Domain 不直接依賴 HTTP。
8. Optimistic Lock 統一映射 `CONCURRENT_MODIFICATION / 409`。
9. DB duplicate 不得直接變 500。
10. Inventory Import row error 是 validation result。
11. `NO_QUALIFIED_SUPPLIER` 是 Recommendation Outcome。
12. `RAG_INSUFFICIENT_EVIDENCE` 是合法 application result。
13. ErrorResponse 包含 `traceId`。
14. Expected 4xx 不全部記 ERROR。
15. Unexpected 5xx 記 ERROR + stack trace。
16. Security 401 / 403 由 Spring Security handling。
17. `DataIntegrityViolationException` 不可全部誤映射為 duplicate。
18. BusinessException extends RuntimeException 以支援 transaction rollback。
19. V1 不建立大量 Exception subclass。
20. RecommendationOutcome 正式納入 V1 Domain。

---

# 38. Next Stage

下一階段：

```text
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

下一份建議文件：

```text
security-design.md
```

Security Design 應定義：

```text
JWT
Login Flow
Password Storage
Role Authorization
Spring Security Filter Chain
CurrentUserProvider
401 / 403
Endpoint Role Matrix
AI Tool Authorization
```
