# SupplyPilot — Cross-document Consistency Review

> Review scope: `domain-model.md`, `erd.md/html`, `api.md`, `package-architecture.md` against later `transaction-boundary.md`, `validation-exception.md`, `security-design.md`, UML and Testing documents.

## Review Result

### E8 規格對齊（2026-10-11）

- `api.md`、`domain-model.md`、`functional-requirements.md`、`transaction-boundary.md`、`validation-exception.md` 一致採 DRAFT 交期可空、Approve 必填、Order 沿用已核准交期；現有 Backend 尚未實作 Approve 驗證。
- `api.md`、`security-design.md`、`frontend-design.md` 一致採倉管專用 Receiving GET 與受限成功 DTO；一般 PO GET 仍限採購角色。`StockTransaction` 查詢為 E8 Backend 前置，現有 Controller 未實作，不能以文件規劃當現成 API。
- `acceptance-criteria.md`、`testing-strategy-merged.md`、本地 E8 Issue 規格同步 A／B／C 依賴與測試影響。三項 Backend 工作尚無 Issue 編號；本次未改遠端 Issues。
- 舊版 `APPROVED` 且交期空值的資料處理方式、Receiving POST 縮減回應對既有 API 客戶端的相容性，留待 Backend 工作實作前確認及驗證。既有 UML / HTML 圖示尚描繪舊核准與收貨回應，須於實作階段同步更新，不能作為本次新契約的權威。
- E8 第二輪修正以 `api.md`、`security-design.md`、`frontend-design.md`、`acceptance-criteria.md` 為目前文字契約權威；舊 UML Markdown 與 HTML 只作歷史參考，凡核准交期、Receiving DTO／路徑、StockTransaction 權限或逾期標示與文字契約不同者均不得沿用。Backend A／B／C 實作時須同步更新圖示，不得讓圖示定義額外 API。Inventory GET 不授權 `PROCUREMENT_MANAGER`，該角色僅能載入 Stock Transactions Tab。

The following stale decisions were found and synchronized:

1. Entity count `13 → 14`.
2. `InventoryImportItem` is now a formal V1 entity.
3. `Inventory` optimistic locking moved from V1.1 candidate to V1 `@Version`.
4. `PurchaseOrder` optimistic locking is explicitly V1 `@Version`.
5. `PurchaseRecommendation.outcome` / `RecommendationOutcome` added.
6. `NO_QUALIFIED_SUPPLIER` is a normal recommendation outcome, not an exception.
7. `DELIVERY_DATE_NOT_FEASIBLE` remains a supplier disqualification reason, not a general API error.
8. `DocumentStatus` synchronized to `PROCESSING / READY / FAILED / INACTIVE`.
9. Inventory Import confirm failure semantics synchronized: rollback leaves batch `VALIDATED` in V1; `IMPORT_FAILED` is not required to be persisted inside the failed transaction.
10. `CONCURRENT_MODIFICATION` added to API error baseline.
11. RAG insufficient evidence synchronized as an application result rather than provider failure.
12. Package architecture now includes `InventoryImportItem`, `InventoryImportItemRepository`, and `RecommendationOutcome`.
13. ERD visual regenerated to the 14-entity baseline.

## Files Updated

```text
domain-model.md
erd/erd.md
erd/erd.html
api.md
package-architecture.md
```

## Remaining Pre-implementation Documents

Still recommended before Day 1 coding:

```text
coding-standards.md
non-functional-requirements.md
environment-configuration.md
acceptance-criteria.md
pre-implementation-checklist.md
```

Secondary documents may be completed during Day 1–3:

```text
database-migration.md
logging-observability.md
deployment-design.md
git-workflow.md
```
