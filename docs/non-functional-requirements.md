# SupplyPilot — Non-Functional Requirements

> **目的**：定義 SupplyPilot V1 的 Performance、Availability、Security、Data Integrity、Scalability、File Limit、Maintainability 與 Deployment Boundary。  
> **目標場景**：5–30 人小型企業內部採購系統。

---

# 1. Capacity Baseline

V1 預期：

```text
5–30 internal users
single company
single warehouse
low concurrent write contention
```

不以：

```text
thousands of concurrent users
global multi-region traffic
```

為設計目標。

---

# 2. Performance Targets

一般 CRUD：

```text
P95 < 500 ms
```

不含外部 AI / File Processing。

Recommendation：

```text
typical material recommendation < 2 sec
```

Inventory list / PO list：

```text
normal page size <= 100
```

Inventory Import：

```text
V1 target: 500 rows interactive use
```

不要求大量 ETL 級效能。

---

# 3. AI Performance

AI / RAG：

```text
independent from core procurement SLA
```

Provider latency 可較高。

AI Failure：

```text
must not block core procurement flow
```

---

# 4. Availability

V1 Portfolio：

```text
single backend instance acceptable
no HA requirement
```

不要求：

```text
multi-AZ app cluster
active-active
automatic failover
```

Database 若使用 managed MySQL，可使用平台基本備份能力。

---

# 5. Data Integrity

必須：

```text
FK
UNIQUE
NOT NULL
transaction
@Version where specified
```

關鍵 Atomic Flow：

```text
Material + Inventory creation
Inventory Adjustment + StockTransaction
Inventory Import Confirm
Recommendation Snapshot
PO + Items
Receiving
```

---

# 6. Concurrency

V1：

```text
optimistic locking
```

Entity：

```text
Inventory
PurchaseOrder
```

Conflict：

```text
409 CONCURRENT_MODIFICATION
```

不使用：

```text
distributed lock
SERIALIZABLE globally
```

---

# 7. Security

必須：

```text
BCrypt
JWT signature validation
role authorization
method security for high-risk use cases
secret via environment
CORS allowlist
no sensitive logs
```

Token：

```text
~1 hour V1 TTL
```

---

# 8. File Upload Limits

Inventory CSV：

```text
max 5 MB
```

Document Upload：

```text
max 20 MB
```

V1 Allowed Inventory：

```text
.csv
```

Document allowed：

```text
.pdf
.txt
.docx
```

若實作 extractor 不支援某格式，應在 API validation 明確拒絕。

---

# 9. File Safety

至少：

```text
validate extension
validate content type
generate server-side storage filename
do not trust original path
```

不得允許 path traversal。

---

# 10. Pagination

List endpoint：

```text
default size = 20
max size = 100
```

Sort field：

```text
whitelist
```

---

# 11. Maintainability

要求：

```text
feature package boundary
service transaction boundary
business logic independently testable
Flyway migration
standard error contract
```

禁止 V1 因追求擴充性導入過量 framework。

---

# 12. Testability

核心 calculator：

```text
no Spring Context required
```

Date-sensitive logic：

```text
inject Clock
```

Integration：

```text
MySQL Testcontainers preferred
```

---

# 13. Observability Baseline

至少：

```text
structured application logs
traceId
HTTP method
path
status
duration
```

提供：

```text
/actuator/health
```

V1 不要求：

```text
ELK
Prometheus
Grafana
OpenTelemetry backend
```

---

# 14. Backup / Recovery

Portfolio V1：

```text
managed DB backup or manual snapshot acceptable
```

正式企業化後再定：

```text
RPO
RTO
restore drill
```

---

# 15. Scalability Boundary

V1 不使用：

```text
microservices
Kafka
Redis
Elasticsearch
```

除非後續有實測瓶頸。

Scale-up / single-instance baseline 優先。

---

# 16. Compatibility

Backend：

```text
document supported JDK version in README
```

Frontend：

```text
modern Chromium / Edge / Firefox
```

不要求 IE compatibility。

---

# 17. Accessibility / UX Baseline

V1 至少：

```text
keyboard-accessible forms where Ant Design provides
clear status text
do not rely only on color
loading/error/empty state
confirmation for high-risk action
```

---

# 18. Reliability Rule

External service failure：

```text
AI
Embedding
S3
```

不可造成：

```text
core DB transaction held indefinitely
```

Document ingestion 採：

```text
eventually consistent
```

---

# 19. V1 Boundary Summary

SupplyPilot V1 優先：

```text
correctness
data integrity
security
testability
clear failure behavior
```

不優先：

```text
extreme throughput
high availability
distributed architecture
advanced observability stack
```
