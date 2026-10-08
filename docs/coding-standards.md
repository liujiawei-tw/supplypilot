# SupplyPilot — Coding Standards

> **目的**：規範 SupplyPilot V1 Java / Spring Boot 與 React / TypeScript 的程式碼風格、責任邊界與 Agent Coding 約束。  
> **原則**：一致性優先於個人偏好；Business Logic 不散落；不要為 V1 過度抽象。

---

# 1. Backend Package / Naming

Root：

```text
com.supplypilot
```

Feature package：

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
common
config
```

命名：

```text
XxxController
XxxService
XxxRepository
XxxRequest
XxxResponse
XxxMapper
XxxStatus
XxxType
```

不要使用：

```text
XxxManager
XxxHelper
XxxUtil
```

承擔不清楚的 Business Responsibility。

---

# 2. Layer Responsibility

Controller：

```text
HTTP mapping
Request DTO
Bean Validation
HTTP response
```

Controller 不做：

```text
Business calculation
Repository access
transaction orchestration
```

Service：

```text
Use Case orchestration
cross-entity validation
transaction boundary
authorization method boundary
```

Domain Entity：

```text
own lifecycle
own invariant
state transition
```

Repository：

```text
persistence
custom query
```

Mapper：

```text
DTO ↔ Domain mapping
```

Mapper 不做 Business Logic。

---

# 3. Entity / DTO

禁止直接：

```text
Entity as API Request
Entity as API Response
```

DTO 分：

```text
request/
response/
```

Request 不接受 Backend-owned field：

```text
id
status
createdBy
approvedBy
orderedBy
receivedBy
version
snapshot field
```

---

# 4. Dependency Injection

一律使用：

```text
constructor injection
```

禁止：

```java
@Autowired
private XxxService service;
```

Field Injection。

---

# 5. Service Interface

V1 不機械式建立：

```text
XxxService
XxxServiceImpl
```

若只有一個 implementation：

```text
XxxService
```

直接 class 即可。

只有真的有多 implementation / boundary abstraction 才建立 interface。

例：

```text
DocumentStorageService
AiProviderClient
IncomingInventoryQuery
```

---

# 6. Domain Method

優先：

```java
purchaseOrder.approve(...)
inventory.receive(...)
inventory.adjust(...)
```

避免 Service 到處：

```java
entity.setStatus(...)
entity.setQuantity(...)
```

---

# 7. Transaction

`@Transactional` 放：

```text
Application Service public method
```

不放：

```text
Controller
DTO
Entity
Mapper
```

Query 可：

```java
@Transactional(readOnly = true)
```

不要在 DB Transaction 內長時間等：

```text
LLM
S3
Embedding Provider
Vector Store
```

---

# 8. Exception

使用：

```text
BusinessException + ErrorCode
```

不要大量建立：

```text
MaterialNotFoundException
SupplierNotFoundException
...
```

`BusinessException` 必須 extends：

```text
RuntimeException
```

避免 catch 後吞掉例外。

---

# 9. Validation

DTO：

```text
@NotNull
@NotBlank
@Positive
@PositiveOrZero
```

Service：

```text
cross-entity / repository-based rule
```

Domain：

```text
invariant / lifecycle
```

DB：

```text
FK
UNIQUE
NOT NULL
CHECK
@Version
```

---

# 10. Numeric Types

以下一律：

```text
BigDecimal
```

- quantity
- price
- score
- total cost
- safety stock
- demand

禁止：

```text
double
float
```

處理金額與數量。

---

# 11. BigDecimal

比較：

```java
a.compareTo(b)
```

不要：

```java
a.equals(b)
```

作數值大小判斷。

明確指定 rounding：

```text
RoundingMode
```

不要依隱含 rounding。

---

# 12. Date / Time

Business Date：

```text
LocalDate
```

例如：

```text
requiredDate
expectedDeliveryDate
```

Event Timestamp：

```text
Instant
```

測試與 Business Current Date：

```text
Clock
```

避免核心邏輯散落：

```java
LocalDate.now()
Instant.now()
```

---

# 13. Enum

JPA 一律：

```java
@Enumerated(EnumType.STRING)
```

禁止：

```text
ORDINAL
```

Enum name 視為 persistence contract。

---

# 14. Null / Optional

Repository `findById`：

```text
Optional<T>
```

Domain field Nullable 應與規格一致。

不要把 Optional：

```text
存成 Entity field
```

---

# 15. Lombok

V1 可使用：

```text
@Getter
@NoArgsConstructor(access = PROTECTED)
```

謹慎使用：

```text
@Builder
```

禁止 Entity：

```text
@Data
@Setter
```

避免所有欄位任意可變。

---

# 16. Entity Setter

Entity 預設：

```text
no public setter
```

透過：

```text
constructor
factory
domain method
```

改狀態。

---

# 17. JPA Aggregate

`PurchaseOrderItem`：

```text
no independent CRUD API
```

由：

```text
PurchaseOrder
```

管理。

Recommendation Result 同理，透過 Snapshot Root 建立。

---

# 18. Logging

使用：

```text
SLF4J
```

禁止：

```text
System.out.println
```

禁止 log：

```text
password
passwordHash
JWT
Authorization header
API key
secret
full confidential document
```

---

# 19. API Response

Success：

```text
DTO directly
```

不強制 generic envelope。

Error：

```text
standard ErrorResponse
```

包含：

```text
code
message
traceId
timestamp
path
fieldErrors?
```

---

# 20. Repository Query

方法名稱簡單時可 Spring Data derived query。

複雜 Business Query：

```text
explicit JPQL / query method
```

不要把 Business Calculation 塞進 SQL，除非只是 aggregation/filter。

---

# 21. Mapping

V1 可：

```text
manual mapper
```

不強制 MapStruct。

若 Mapper 變多再導入。

---

# 22. Frontend Naming

React Component：

```text
PascalCase
```

function / variable：

```text
camelCase
```

API module：

```text
material.ts
purchaseOrder.ts
```

Types：

```text
MaterialResponse
CreateMaterialRequest
PurchaseOrderResponse
```

與 Backend contract 對齊。

---

# 23. Frontend Responsibility

Frontend 可：

```text
required validation
format validation
button visibility
role-based UX
```

Frontend 不重做：

```text
PO lifecycle rule
supplier qualification
inventory invariant
authorization truth
```

---

# 24. Agent Coding Rules

任何 Coding Agent 開始修改前必須：

```text
1. Read relevant spec
2. Read existing package
3. Identify files to change
4. Do not modify unrelated files
5. Follow current architecture
6. Add/update tests
7. Report deviations
```

不得：

```text
silently redesign architecture
add Redis/Kafka/microservice
change API contract without spec update
bypass Service to write Repository from AI Tool
```

---

# 25. Definition of Clean Code for V1

優先：

```text
clear
testable
explicit
small
consistent
```

不是：

```text
maximum abstraction
maximum design patterns
minimum line count
```
