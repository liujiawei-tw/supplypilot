# SupplyPilot — As-Is Procurement Flow

> **文件目的**：描述 Formosa Flavor Labs 在尚未導入 SupplyPilot 前的現行採購流程，作為後續 To-Be Flow、Functional Requirements、Domain Model、ERD、API 與測試情境設計的基礎。  
> **文件定位**：上游流程規格文件  
> **目前狀態**：V0.1 — 初版確認  
> **關聯文件**：`company-profile.md`

---

# 1. 流程範圍

本文件只描述與 SupplyPilot V1 直接相關的採購與收貨流程。

不涵蓋：

- 完整 MRP
- 完整生產排程
- 財務會計
- 多層核准
- 多倉庫
- Partial Receiving
- 複雜詢價系統
- 供應商 Portal

---

# 2. 已確認的 V1 前提

以下四項已確定作為 V1 設計基準。

## 2.1 Demand

V1 的 Demand 由：

**Production Planner／生產或物料人員輸入未來需求。**

SupplyPilot V1 不負責完整 MRP 或生產排程計算。

Demand 屬於系統輸入資料。

---

## 2.2 Inventory Data Source

現況假設：

**庫存資料主要由既有 ERP 匯出 Excel / CSV 後提供。**

V1 不要求直接串接真實 ERP API。

後續如需要，可再擴充成：

```text
ERP API
↓
SupplyPilot Integration
```

---

## 2.3 Purchase Order Approval

V1 所有 Purchase Order 均採：

```text
Procurement Staff
        ↓
建立 PO Draft
        ↓
Procurement Manager
        ↓
Approve / Reject
```

不做：

- 金額分級核准
- 多層 Approval
- 副總／總經理核准
- 動態 Approval Matrix

---

## 2.4 Receiving

V1 採：

**整張 Purchase Order 一次完整收貨。**

也就是：

```text
ORDERED
↓
RECEIVED
```

V1 不做 Partial Receiving。

Partial Receiving 預計留至 V1.1。

---

# 3. As-Is 採購流程總覽

```text
Production Planner
提供未來 Demand
        ↓
Procurement Staff
取得 ERP Inventory
        ↓
確認 Open Purchase Orders
        ↓
Excel 人工整合資料
        ↓
計算 Shortage
        ↓
計算 Purchase Quantity
        ↓
查詢可供應 Supplier
        ↓
查詢 Price / MOQ / Lead Time
        ↓
必要時重新詢價
        ↓
人工比較 Supplier
        ↓
建立 Purchase Order
        ↓
Procurement Manager Approval
        ↓
正式向 Supplier 下單
        ↓
人工追蹤 Delivery
        ↓
Warehouse Receiving
        ↓
ERP Inventory Update
        ↓
Stock Transaction Record
```

---

# 4. Demand 產生方式

Production Planner 每週或依實際需要整理未來一段時間的原料需求。

可能來源包括：

```text
Customer Orders
+
Production Plan
+
Manual Adjustment
```

範例：

```text
Future 14 Days Demand

Strawberry Concentrate   400 kg
Black Tea Extract        120 kg
Fructose Syrup           800 kg
Milk Powder              250 kg
```

V1 不追蹤 Demand 如何由完整生產排程推導。

---

# 5. Inventory 資料取得

採購人員從 ERP 查詢或匯出目前庫存。

現況流程假設：

```text
ERP
↓
Excel / CSV Export
↓
Procurement Staff
```

範例：

| Material | Inventory |
|---|---:|
| Strawberry Concentrate | 200 kg |
| Black Tea Extract | 80 kg |
| Fructose Syrup | 600 kg |

---

# 6. Open Purchase Order 查詢

採購人員除了目前 Inventory，還必須確認已下單但尚未收貨的採購單。

範例：

| PO | Material | Quantity | Expected Date | Status |
|---|---|---:|---|---|
| PO-20261001 | Strawberry Concentrate | 100 kg | 2026-10-10 | ORDERED |
| PO-20261002 | Strawberry Concentrate | 50 kg | 2026-10-15 | ORDERED |

這些尚未到貨的數量構成：

**Incoming Inventory**

---

# 7. Excel 人工整合

採購人員將：

- Demand
- Safety Stock
- Current Inventory
- Incoming Inventory

整合至 Excel。

範例：

| Material | Demand | Safety Stock | Inventory | Incoming | Shortage |
|---|---:|---:|---:|---:|---:|
| Strawberry Concentrate | 400 | 50 | 200 | 100 | 150 |
| Black Tea Extract | 120 | 30 | 80 | 0 | 70 |
| Fructose Syrup | 800 | 200 | 600 | 300 | 100 |

核心概念：

```text
Net Requirement
=
Expected Demand
+ Safety Stock
- Available Inventory
- Incoming Inventory
```

---

# 8. 缺料判斷

目前缺料判斷主要依靠：

```text
Excel
+
Procurement Staff
```

而不是系統主動產生採購建議。

只要：

```text
Net Requirement > 0
```

即代表存在採購需求。

---

# 9. Purchase Quantity 計算

Shortage 不一定等於最後實際採購量。

例如：

```text
Net Requirement = 150 kg
Supplier MOQ = 200 kg
```

則：

```text
Final Purchase Quantity = 200 kg
```

後續還可能考慮：

- MOQ
- Packaging Multiple
- Supplier Capacity
- Lead Time

概念上區分：

```text
Net Requirement
↓
Supplier Constraint
↓
Final Purchase Quantity
```

---

# 10. Supplier 查詢

採購人員需要找到可供應指定 Material 的供應商。

同一 Material 可能對應多個 Supplier。

因此：

```text
Supplier
↔
SupplierMaterial
↔
Material
```

Supplier 與 Material 為多對多關係。

---

# 11. Supplier 條件查詢

主要比較資訊包含：

- Price
- MOQ
- Lead Time
- Reliability
- Quality
- Currency
- Country / Region

資料目前可能分散於：

- ERP
- Excel
- Email
- Quotation PDF
- 採購人員個人經驗

---

# 12. 重新詢價

當既有價格過期或採購條件可能已改變時，採購人員可能重新向供應商詢價。

例如：

```text
Old Quote:
3 months ago
```

採購人員透過 Email、LINE 或其他通訊方式詢問：

- Current Price
- MOQ
- Lead Time
- Availability

V1 不建立完整 RFQ / Quotation Workflow。

但 As-Is 流程承認此步驟存在。

---

# 13. Supplier Decision

採購人員人工比較供應商。

範例：

```text
Supplier A
Price: Lower
Lead Time: 14 days

Supplier B
Price: Higher
Lead Time: 7 days

Supplier C
Price: Lowest
MOQ: Very High
Lead Time: 30 days
```

實際決策可能同時考量：

- Price
- MOQ
- Lead Time
- Reliability
- Quality
- Urgency
- Past Experience

現況最大的問題之一是：

**Supplier Decision 的理由通常沒有被系統化保存。**

---

# 14. Purchase Order 建立

採購人員選定供應商後建立 Purchase Order。

範例：

```text
PO-20261007-001

Supplier:
Supplier B

Material:
Strawberry Concentrate

Quantity:
200 kg

Unit Price:
85 / kg
```

---

# 15. Purchase Order Approval

V1 流程固定為單一層級核准：

```text
Procurement Staff
↓
DRAFT
↓
Procurement Manager
↓
APPROVED / REJECTED
```

所有 PO 均需要 Procurement Manager 核准。

---

# 16. 正式下單

PO 核准後，採購人員正式向 Supplier 下單。

之後 PO 狀態進入：

```text
ORDERED
```

只有正式 ORDERED 的採購單才計入：

**Incoming Inventory**

APPROVED 只代表內部核准完成，不代表供應商已收到正式訂單。

---

# 17. Delivery Tracking

Supplier 提供 Expected Delivery Date。

採購人員目前主要透過：

- ERP
- Excel
- Email
- 個人提醒

追蹤未交 PO。

如果：

```text
Current Date > Expected Delivery Date
AND
PO Status = ORDERED
```

則可能視為：

**Overdue Purchase Order**

此功能可作為 SupplyPilot 後續 To-Be Flow 的改善項目。

---

# 18. Receiving

Supplier 到貨後，由 Warehouse Staff 執行收貨。

V1 假設完整收貨：

```text
PO Quantity = 200 kg
Received Quantity = 200 kg
```

流程：

```text
找到 PO
↓
確認 Material
↓
確認 Quantity
↓
完成基本驗收
↓
Receive
```

狀態：

```text
ORDERED
↓
RECEIVED
```

Partial Receiving 不在 V1 範圍。

---

# 19. Inventory Update

收貨成功後更新 Inventory。

範例：

```text
Before Receiving:
200 kg

Received:
+200 kg

After Receiving:
400 kg
```

Inventory Update 必須與 PO Receiving、StockTransaction 保持一致。

---

# 20. Stock Transaction

每次庫存異動都應留下可追溯紀錄。

範例：

```text
Date:
2026-10-15

Type:
PURCHASE_RECEIVE

Material:
Strawberry Concentrate

Quantity:
+200 kg

Reference:
PO-20261007-001
```

不可只修改：

```text
Inventory.quantity
```

而不保留異動來源。

---

# 21. 現況主要問題

目前 As-Is Flow 的主要問題：

1. Demand、Inventory、Incoming PO 分散。
2. 採購人員需要人工使用 Excel 整合。
3. Shortage 與 Purchase Quantity 靠人工維護公式。
4. Supplier 資訊分散。
5. Supplier Decision 高度依賴經驗。
6. 採購決策理由沒有系統化保存。
7. Open PO / Overdue PO 需要人工追蹤。
8. 文件分散於 PDF、Email 與共用資料夾。
9. 資料輸入與計算容易發生人工錯誤。
10. 流程缺少一致的可追溯性。

---

# 22. 對 SupplyPilot 後續設計的影響

此 As-Is Flow 將作為後續 To-Be Flow 的比較基準。

後續必須明確回答：

```text
As-Is Step
↓
目前痛點
↓
SupplyPilot 是否處理
↓
如何處理
↓
是否屬於 V1
```

不能因為技術上能做，就直接加入系統。

---

# 23. 下一階段

下一步：

**設計 SupplyPilot To-Be Procurement Flow**

主要目標是逐步回答：

1. 哪些人工步驟被 SupplyPilot 自動化？
2. 哪些資料仍由人員輸入？
3. 哪些計算由 Backend Business Logic 處理？
4. 哪些工作交給 AI？
5. 哪些決策必須保留 Human Approval？
6. ERP 與 SupplyPilot 如何分工？
7. V1 的系統邊界在哪裡？
