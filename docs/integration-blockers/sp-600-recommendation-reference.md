# SP-600 Recommendation Reference Integration Blocker

SP-600 保留 `purchase_order_items` 的 Recommendation reference、snapshot 與 override reason 欄位，與 V1 ERD 相容。

SP-504 尚未提供 `PurchaseRecommendation` 與 `SupplierRecommendationResult` 的 schema、entity 或 repository，因此 SP-600 無法安全地驗證 Recommendation 是否存在、Material 是否相符，以及 Result 是否屬於該 Recommendation。

在 SP-504 完成前，SP-600 僅支援未提供任何 Recommendation reference 或 override 欄位的手動 PO。任何傳入 `recommendationId`、`supplierRecommendationResultId`、`supplierOverrideReason` 或 `quantityOverrideReason` 的請求一律以 `INVALID_RECOMMENDATION_REFERENCE` 拒絕；不會忽略或持久化這些值。
