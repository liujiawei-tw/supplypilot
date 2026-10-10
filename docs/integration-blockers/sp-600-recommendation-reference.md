# SP-600 Recommendation Reference Integration — Resolved by SP-601

SP-600 保留 `purchase_order_items` 的 Recommendation reference、snapshot 與 override reason 欄位，與 V1 ERD 相容。

SP-504 已提供 `PurchaseRecommendation` 與 `SupplierRecommendationResult` 的 schema、entity 與 repository。SP-601 已整合 Recommendation-based Draft，從 server-side persisted snapshot 驗證 Recommendation、Material、SupplierRecommendationResult 與 PO Supplier 的一致性，並保存 Recommendation evidence 與必要的 override reason。

未提供 Recommendation reference 的 SP-600 manual Draft creation 仍維持原有行為；其 Recommendation reference、snapshot 與 override reason 欄位皆為 `null`。
