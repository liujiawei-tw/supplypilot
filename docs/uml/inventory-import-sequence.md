# SupplyPilot — Inventory Import Sequence Diagram

> **文件目的**：描述 Inventory Import 的兩階段流程 `Validate → Preview → Confirm`，以及 `InventoryImportBatch / InventoryImportItem`、Row-level Validation、All-or-Nothing Transaction 與 Optimistic Lock。  
> **視覺化文件**：`inventory-import-sequence.html`

---

# 1. Participants

```text
Client
JwtAuthenticationFilter
InventoryImportController
InventoryImportService
InventoryCsvParser
InventoryImportValidator
MaterialRepository
InventoryImportBatchRepository
InventoryRepository
```

---

# 2. Security

允許：

```text
WAREHOUSE_STAFF
ADMIN
```

Validate / Confirm 都必須通過：

```text
JWT
Role Authorization
```

Token 錯誤：

```text
401 UNAUTHENTICATED
```

權限不足：

```text
403 ACCESS_DENIED
```

---

# 3. Validate Phase

Endpoint：

```text
POST /api/v1/inventory-imports/validate
```

Content：

```text
multipart/form-data
CSV file
```

流程：

```text
Client
↓
JwtAuthenticationFilter
↓
InventoryImportController
↓
InventoryImportService.validate(file)
↓
InventoryCsvParser
↓
InventoryImportValidator
```

---

# 4. File-level Validation

Request-level Error：

```text
INVALID_FILE_FORMAT
FILE_EMPTY
FILE_TOO_LARGE
CORRUPTED_FILE
```

這些直接：

```text
400
```

不進 Row Preview。

---

# 5. CSV Parsing

`InventoryCsvParser` 只負責：

```text
CSV
→ Parsed Row DTO
```

例如：

```text
rowNumber
materialCode
quantity
unit
```

Parser 不查 DB、不更新 Inventory。

---

# 6. Row Validation

`InventoryImportValidator` 需要：

```text
MaterialRepository
```

驗證：

```text
materialCode exists
quantity valid
unit matches Material.baseUnit
duplicate material in file
```

Row Error 是：

```text
validation result
```

不是 HTTP Exception。

---

# 7. Validation Preview

例如：

```json
{
  "batchId": 102,
  "status": "VALIDATION_FAILED",
  "totalRows": 30,
  "validRows": 28,
  "errorRows": 2,
  "errors": [
    {
      "row": 7,
      "materialCode": "MAT-001",
      "code": "UNIT_MISMATCH",
      "message": "Expected KG but received PCS."
    }
  ]
}
```

HTTP 可以：

```text
200 OK
```

因為「驗證這件事情」成功完成。

---

# 8. Validate Persistence Transaction

Validate 後：

```text
BEGIN
```

保存：

```text
InventoryImportBatch
+
InventoryImportItem[]
```

Batch：

```text
VALIDATED
```

或：

```text
VALIDATION_FAILED
```

然後：

```text
COMMIT
```

重要：

```text
Inventory.quantity MUST NOT change
```

---

# 9. Why InventoryImportItem Exists

Validate 與 Confirm 是不同 HTTP Request。

若只存：

```text
InventoryImportBatch
```

Confirm 時沒有已驗證 rows 可以使用。

因此 V1 正式使用：

```text
InventoryImportBatch
1:N
InventoryImportItem
```

保存 Preview Data。

---

# 10. User Preview

Validate 後：

```text
Client reviews preview
```

只有：

```text
Batch.status = VALIDATED
```

才允許 Confirm。

若：

```text
VALIDATION_FAILED
```

必須修正檔案並重新 Validate。

---

# 11. Confirm Phase

Endpoint：

```text
POST /api/v1/inventory-imports/{batchId}/confirm
```

流程：

```text
Load Batch
↓
Validate status = VALIDATED
↓
Load ImportItems
↓
BEGIN
↓
Replace Inventory snapshots
↓
Mark Batch IMPORTED
↓
COMMIT
```

---

# 12. Snapshot Replace

ERP Import 不是：

```text
Inventory += imported quantity
```

而是：

```text
Inventory.quantity = imported quantity
```

因為 ERP 檔案代表：

```text
Current Inventory Snapshot
```

---

# 13. All-or-Nothing

例如：

```text
100 rows
```

若：

```text
row 1~99 update success
row 100 fails
```

結果必須：

```text
ROLLBACK ALL
```

不可留下：

```text
99 rows new snapshot
1 row old snapshot
```

---

# 14. Optimistic Lock

Inventory V1 使用：

```java
@Version
```

目的：

```text
Prevent Lost Update
```

例如：

```text
Inventory = 100
version = 5
```

Receiving：

```text
100 → 150
version 5 → 6
```

Import 同時嘗試：

```text
replace 100 → 80
using version 5
```

則：

```text
Optimistic Lock Conflict
```

結果：

```text
Rollback entire import confirm
409 CONCURRENT_MODIFICATION
```

---

# 15. Confirm Failure

以下任一情況：

```text
Inventory save failure
Optimistic lock conflict
DB constraint failure
unexpected exception
```

Confirm Transaction 必須：

```text
ROLLBACK
```

Batch 不可被標記為：

```text
IMPORTED
```

---

# 16. Batch Status on Confirm Failure

V1 簡化：

```text
Confirm transaction rollback
↓
Batch remains VALIDATED
```

API 回錯誤。

`IMPORT_FAILED` Enum 可以保留，但 V1 不強制使用獨立 transaction 持久化 failure status。

這樣避免：

```text
rollback transaction
+
another transaction just to save failure state
```

的額外複雜度。

---

# 17. StockTransaction Rule

Inventory Import 不建立：

```text
StockTransaction
```

原因：

```text
ERP import = current snapshot replacement
```

不是一般：

```text
stock movement
```

Inventory Import 的 Audit 使用：

```text
InventoryImportBatch
InventoryImportItem
```

---

# 18. Error Paths

## File-level

```text
INVALID_FILE_FORMAT
FILE_EMPTY
FILE_TOO_LARGE
CORRUPTED_FILE
→ 400
```

## Row-level

```text
MATERIAL_NOT_FOUND
INVALID_QUANTITY
UNIT_MISMATCH
DUPLICATE_MATERIAL
→ Preview Result
```

## Confirm invalid state

```text
INVENTORY_IMPORT_NOT_VALIDATED
→ 409
```

## Confirm concurrency conflict

```text
CONCURRENT_MODIFICATION
→ 409
```

## Unexpected

```text
Rollback
→ 500
```

---

# 19. Audit

`uploadedBy`：

```text
SecurityContext
↓
CurrentUserProvider
```

Client 不傳：

```text
uploadedBy
```

---

# 20. Sequence Summary

```text
Validate Request
↓
JWT / Role
↓
Parse CSV
↓
Validate Rows
↓
Persist Batch + ImportItems
↓
Preview
↓
User confirms
↓
Validate Batch = VALIDATED
↓
@Transactional
↓
Replace all Inventory snapshots
↓
Optimistic Lock check
↓
Mark Batch IMPORTED
↓
Commit
```

若任何 Confirm Step 失敗：

```text
Rollback entire batch
```

---

# 21. Design Intent

這張 Sequence Diagram 強調：

1. Validate 與 Confirm 是不同 Use Case。
2. Row Error 不用 HTTP Exception 表達。
3. InventoryImportItem 是跨 Request Preview 的必要資料。
4. Confirm 才修改 Inventory。
5. Confirm 必須 All-or-Nothing。
6. ERP Import 是 Snapshot Replace。
7. Import 不建立 StockTransaction。
8. Inventory `@Version` 防 Lost Update。
9. Audit User 來自 SecurityContext。
10. Failure 不得留下 Partial Inventory State。

下一組 UML：

```text
Purchase Order Lifecycle Sequence Diagram
```
