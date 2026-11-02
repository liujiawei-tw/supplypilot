# 本機 DEMO 核對值

核對來源為 live `SP-901 / sp-907-zh-tw-v3 / COMPLETE` 資料庫。MySQL 邏輯匯出包含 `suppliers`、`materials`、`supplier_materials`、`purchase_orders`、`purchase_order_items`、`inventory`；PostgreSQL 摘要包含 `public.semantic_chunks`。核對均為唯讀。

| 資料項目 | Live DEMO 核對值 |
|---|---|
| MAT-001 庫存 | `3200.0000 KG`；更新時間 `2026-10-19T11:42:17.623671Z` |
| SUP-001 / MAT-001 | `ACTIVE`、`100.0000 TWD/KG`、MOQ `800 KG`、包裝倍數 `100 KG`、交期 5 天 |
| SUP-002 / MAT-002 | `ACTIVE`、`73.0000 TWD/KG`、MOQ `400 KG`、包裝倍數 `25 KG`、交期 8 天 |
| MAT-001 關係 | SUP-001 `ACTIVE 100 / MOQ 800 / 5 天`；SUP-002 `ACTIVE 104 / MOQ 500 / 3 天`；SUP-003 `ACTIVE 97 / MOQ 1000 / 10 天`；SUP-004 `INACTIVE 90 / MOQ 500 / 2 天`；SUP-005 `ACTIVE 102 / MOQ 600 / 7 天`；SUP-010 `ACTIVE 99 / MOQ 800 / 5 天`（單價 TWD/KG） |
| XTB-02 條件 `ACTIVE`、MOQ ≤ 600 KG、交期 ≤ 7 天 | SUP-002 與 SUP-005 |
| BAS-06 SUP-002 / MAT-001 PO | PO `PO-20261018-000011` 為 `RECEIVED`、750 KG；該 PO 是 live replacement 題使用的現存資料 |
| XTB-03-LIVE PO 價格 | PO item snapshot `104.0000 TWD/KG`；SUP-002/MAT-001 現行關係 `104.0000 TWD/KG`、`ACTIVE` |
| SUP-999 | 無供應商主檔 |
| 名稱含「茶萃取物」 | `MAT-001`、`MAT-006`、`MAT-011` |

`DEMO-PO-001` 不在 live `purchase_orders` 中；因此靜態 fixture 原本 XTB-03 未納入計分，改用 `PO-20261018-000011` 的 live 題。MAT-001 的庫存也已高於初始 seed 值；BAS-01 以 snapshot 中的目前庫存為準。

## 安全測試身分

- 臨時 MySQL 身分僅獲 `SELECT`：`suppliers`、`materials`、`supplier_materials`、`purchase_orders`、`purchase_order_items`、`inventory`。
- 臨時 PostgreSQL 身分為 non-superuser，僅獲 `CONNECT`、`public` schema `USAGE`、`public.semantic_chunks` `SELECT`。
- MySQL 測試使用者已刪除；PostgreSQL 權限已撤銷且角色已刪除；後端已還原至 Agentic SQL 預設關閉並確認 healthy。
