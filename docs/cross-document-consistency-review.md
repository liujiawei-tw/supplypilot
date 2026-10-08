# SupplyPilot — Cross-document Consistency Review

> Review scope: `domain-model.md`, `erd.md/html`, `api.md`, `package-architecture.md` against later `transaction-boundary.md`, `validation-exception.md`, `security-design.md`, UML and Testing documents.

## Review Result

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
