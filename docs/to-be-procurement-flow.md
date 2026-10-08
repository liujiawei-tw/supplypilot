# SupplyPilot — To-Be Procurement Flow

> **文件目的**：定義 Formosa Flavor Labs 導入 SupplyPilot 後的目標採購流程，作為後續 Functional Requirements、Domain Model、ERD、API、Business Rules、Testing Strategy 與 Demo Flow 的直接依據。  
> **文件定位**：核心流程規格文件  
> **目前狀態**：V0.1 — 已確認核心流程與 V1 規則  
> **關聯文件**：
> - `company-profile.md`
> - `as-is-procurement-flow.md`

---

# 1. To-Be 設計原則

SupplyPilot 不以「將所有人工流程自動化」為目標。

系統採以下責任分工：

```text
確定性的資料與規則
→ Backend Business Logic

非結構化資訊理解與說明
→ AI

最終高風險採購決策
→ Human Approval
```

核心原則：

1. AI 不直接修改正式交易資料。
2. AI 不直接核准採購。
3. AI 不自行計算核心採購數字。
4. Backend 負責確定性的計算、狀態轉換與資料一致性。
5. 採購人員與主管保留最終決策權。
6. AI 或外部服務故障時，核心採購流程仍可運作。

---

# 2. To-Be 採購流程總覽

```text
Production Planner
輸入未來 Demand
        ↓
SupplyPilot
匯入 ERP Inventory Excel / CSV
        ↓
SupplyPilot
讀取 Open PO / Incoming Inventory
        ↓
Backend Business Logic
計算 Net Requirement
        ↓
Shortage Detection
        ↓
Backend
產生 Purchase Recommendation
        ↓
Backend
執行 Supplier Hard Constraint Check
        ↓
Backend
計算 Supplier Ranking
        ↓
AI
解釋推薦原因
        ↓
Procurement Staff
Review / Modify / Reject Recommendation
        ↓
建立 PO Draft
        ↓
Procurement Manager
Approve / Reject
        ↓
Procurement Staff
正式向 Supplier 下單
        ↓
PO → ORDERED
        ↓
SupplyPilot
追蹤 Expected Delivery Date
        ↓
Warehouse Staff
Full Receiving
        ↓
Transaction
更新 Inventory
+
建立 StockTransaction
        ↓
PO → RECEIVED
```

---

# 3. Demand Input

V1 的 Demand 由：

**Production Planner／生產或物料人員輸入未來需求。**

SupplyPilot V1 不負責完整 MRP 或生產排程。

Demand 屬於系統輸入資料。

基本欄位概念：

```text
Material
Expected Quantity
Required Date / Demand Period
```

範例：

```text
Material:
Strawberry Concentrate

Expected Quantity:
400 kg

Required By:
2026-10-21
```

## 3.1 Backend Responsibilities

Backend 負責：

- 驗證 Material 是否存在
- 驗證 Quantity > 0
- 驗證 Required Date 是否有效
- 儲存 Demand
- 提供後續 Shortage Calculation 使用

AI 不參與 Demand 計算。

---

# 4. Inventory Import

V1 不直接整合真實 ERP API。

Inventory 來源：

```text
Existing ERP
↓
Excel / CSV Export
↓
SupplyPilot Import
```

SupplyPilot Import 流程：

```text
Upload File
↓
Parse
↓
Validation
↓
Inventory Update
```

## 4.1 預期驗證情境

後續 Functional Requirements 必須處理：

- Material Code 缺失
- Material 不存在
- Quantity 非數字
- Quantity < 0
- 重複 Material
- 空白資料列
- 單位錯誤
- 檔案格式錯誤
- Import Transaction Strategy
- 錯誤資訊回傳

是否採全部成功才 Commit，或允許 Partial Success，需於 Functional Requirements / Transaction Strategy 階段正式定案。

---

# 5. Incoming Inventory

Incoming Inventory 由 SupplyPilot 自己管理的 Purchase Order 推導。

## 5.1 已確認 V1 規則

> **只有 `ORDERED` 狀態的 Purchase Order 才計入 Incoming Inventory。**

以下狀態不計入：

```text
DRAFT
APPROVED
REJECTED
RECEIVED
```

原因：

- `DRAFT` 尚未核准。
- `APPROVED` 只代表內部核准完成。
- `ORDERED` 才代表已正式向 Supplier 下單。
- `RECEIVED` 已轉入實際 Inventory。
- `REJECTED` 不存在有效採購承諾。

---

# 6. Net Requirement Calculation

核心公式：

```text
Net Requirement
=
Expected Demand
+ Safety Stock
- Current Inventory
- Incoming Inventory
```

範例：

```text
Expected Demand     400
Safety Stock         50
Current Inventory   200
Incoming Inventory  100

Net Requirement
=
150 kg
```

## 6.1 Backend Output

Backend 不應只回傳最終數字。

建議至少包含：

```json
{
  "material": "Strawberry Concentrate",
  "expectedDemand": 400,
  "safetyStock": 50,
  "currentInventory": 200,
  "incomingInventory": 100,
  "netRequirement": 150
}
```

目的：

- UI 可以顯示計算來源。
- AI 可以根據 Backend 結果進行 Explanation。
- 測試可以驗證每個輸入值。
- 使用者可以追溯推薦結果。

---

# 7. Shortage Detection

V1 採簡單且可驗證的規則：

```text
Net Requirement > 0
→ SHORTAGE

Net Requirement <= 0
→ NO SHORTAGE
```

V1 不增加：

```text
Critical
High
Medium
Low
```

等缺料風險分級。

風險分級留待後續版本評估。

---

# 8. Purchase Recommendation

Net Requirement 不一定等於實際採購量。

範例：

```text
Net Requirement = 150 kg
Supplier MOQ = 200 kg
```

則：

```text
Recommended Purchase Quantity = 200 kg
```

未來可進一步考慮：

- MOQ
- Packaging Multiple
- Supplier Capacity
- Lead Time

## 8.1 概念區分

系統需區分：

```text
Net Requirement
↓
Recommended Purchase Quantity
```

避免將缺料量與採購建議量混為單一欄位。

---

# 9. Supplier Candidate Selection

同一 Material 可能由多個 Supplier 供應。

流程不是直接對所有 Supplier 排名，而是：

```text
Supplier Candidates
↓
Hard Constraint Check
↓
Qualified Suppliers
↓
Ranking Formula
↓
Supplier Recommendation
```

---

# 10. Supplier Hard Constraints

## 10.1 已確認 V1 規則

> **Supplier Recommendation 必須先通過 Hard Constraint，再進 Ranking。**

可能的 Hard Constraint 包括：

- Supplier 是否為 Active
- Supplier 是否供應指定 Material
- SupplierMaterial 是否有效
- Lead Time 是否能符合 Required Date
- MOQ 是否可接受
- Supplier 是否存在供應量限制

V1 最終採用哪些 Hard Constraint，需於 Functional Requirements 與 Supplier Domain 階段明確定義。

不符合 Hard Constraint 的 Supplier：

```text
DISQUALIFIED
```

不進入 Ranking。

---

# 11. Supplier Ranking

V1 Baseline：

```text
Supplier Score =
Price Score        × 40%
Lead Time Score    × 30%
Reliability Score  × 20%
Quality Score      × 10%
```

權重目前作為初始設計。

後續 Functional Requirements 需要進一步定義：

- Price Score 如何 Normalize
- Lead Time Score 如何 Normalize
- Reliability Score 資料來源
- Quality Score 資料來源
- Score Range
- Tie-breaking Rule
- Missing Data Strategy

AI 不參與 Supplier Score 計算。

---

# 12. AI Recommendation Explanation

Backend 可能回傳：

```text
Supplier A
Score: 82

Supplier B
Score: 88

Supplier C
Disqualified
Reason: Lead time too long
```

AI 負責將 Backend 結果轉換成人類容易理解的說明。

例如：

> Supplier B 雖然價格較高，但交期較短且可靠度較高，因此在目前需求日期下總體排名最高。Supplier C 雖然價格最低，但無法在 Required Date 前交貨，因此未列入推薦。

重要原則：

```text
Decision / Score
→ Backend

Explanation
→ AI
```

不是由 LLM 自己判斷 Supplier Ranking。

---

# 13. Procurement Staff Review

Purchase Recommendation 產生後，不直接建立正式訂單。

Procurement Staff 可以：

```text
Accept
Modify
Reject
```

使用者可檢視：

- Material
- Net Requirement
- Recommended Quantity
- Recommended Supplier
- Supplier Ranking
- Ranking Factors
- AI Explanation

---

# 14. Override Rule

## 14.1 已確認 V1 規則

Procurement Staff 可以 Override 系統推薦。

可修改：

- Supplier
- Purchase Quantity

但只要修改系統推薦結果，就必須留下：

```text
Override Reason
```

範例：

```text
Recommended Quantity:
200 kg

Actual Draft Quantity:
300 kg

Override Reason:
Upcoming customer order confirmed
```

目的：

- 保留決策可追溯性
- 比較 Recommendation 與 Actual Decision
- 支援未來 Recommendation Evaluation
- 避免系統推薦結果被人工無聲覆蓋

---

# 15. Purchase Order Draft

當 Procurement Staff 接受或調整 Recommendation 後：

```text
Recommendation
↓
Create Purchase Order Draft
```

初始狀態：

```text
DRAFT
```

基本資訊預計包含：

- Supplier
- Material / PO Items
- Quantity
- Unit Price
- Expected Delivery Date
- Created By
- Recommendation Reference
- Override Reason（如有）

詳細欄位於 Domain Model / ERD 階段確定。

---

# 16. Purchase Order Approval

V1 採單層 Procurement Manager Approval。

流程：

```text
DRAFT
↓
Procurement Manager
├── Approve
└── Reject
```

Approve：

```text
DRAFT
↓
APPROVED
```

Reject：

```text
DRAFT
↓
REJECTED
```

---

# 17. Purchase Order Status

## 17.1 已確認 V1 狀態

V1 Purchase Order lifecycle：

```text
DRAFT
↓
APPROVED
↓
ORDERED
↓
RECEIVED
```

另有拒絕分支：

```text
DRAFT
↓
REJECTED
```

因此 V1 狀態集合為：

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
```

## 17.2 Illegal State Transition

系統必須拒絕非法狀態轉換。

例如：

```text
RECEIVED → DRAFT
REJECTED → ORDERED
DRAFT → RECEIVED
```

詳細 State Transition Matrix 於 Functional Requirements 階段定義。

---

# 18. Formal Ordering

`APPROVED` 不代表已經向 Supplier 正式下單。

Procurement Staff 完成實際下單後：

```text
APPROVED
↓
ORDERED
```

從 `ORDERED` 開始：

- Purchase Order 才計入 Incoming Inventory。
- 系統開始追蹤 Expected Delivery Date。
- Warehouse Staff 可以在到貨後進行 Receiving。

---

# 19. Delivery Tracking

SupplyPilot 根據：

```text
Expected Delivery Date
+
PO Status
```

判斷 PO 是否逾期。

## 19.1 已確認 V1 規則

> **`OVERDUE` 不作為 Purchase Order lifecycle status。**

PO 仍保持：

```text
ORDERED
```

但如果：

```text
Current Date > Expected Delivery Date
AND
PO Status = ORDERED
```

則系統可回傳：

```text
isOverdue = true
```

或：

```text
deliveryStatus = OVERDUE
```

實際實作方式於 Domain / API 階段確認。

原因：

`OVERDUE` 描述的是交貨時間狀態，而不是 PO lifecycle。

---

# 20. Receiving

Warehouse Staff 只能對：

```text
ORDERED
```

狀態的 PO 執行 Receive。

V1 採：

**Full Receiving Only**

例如：

```text
PO Quantity:
200 kg

Received Quantity:
200 kg
```

V1 不處理：

```text
Received:
100 / 200 kg
```

Partial Receiving 留至 V1.1。

---

# 21. Receiving Transaction

Receiving 是 V1 重要 Transaction Boundary。

建議流程：

```text
BEGIN TRANSACTION

1. Validate PO status = ORDERED
2. Validate PO can be received
3. Update Inventory
4. Insert StockTransaction
5. Update PO status = RECEIVED

COMMIT
```

任何一步失敗：

```text
ROLLBACK
```

目的：

避免發生：

- Inventory 已增加，但 PO 未更新
- PO 已變 RECEIVED，但 Inventory 未增加
- StockTransaction 缺失
- Duplicate Receiving

---

# 22. StockTransaction

每次庫存異動必須保留可追溯紀錄。

Purchase Receiving 範例：

```text
Type:
PURCHASE_RECEIVE

Material:
Strawberry Concentrate

Quantity:
+200 kg

Reference:
PO-20261007-001
```

概念：

```text
Inventory
→ 現在有多少

StockTransaction
→ 為什麼變成這個數字
```

不能只修改 `Inventory.quantity` 而不留下歷史來源。

---

# 23. AI Assistant

AI Assistant 為主流程上層的輔助功能，不作為核心交易流程的必要依賴。

可支援：

## 23.1 Purchase Recommendation Explanation

使用者：

> 為什麼 Strawberry Concentrate 建議買 200 kg？

流程：

```text
AI
↓
Tool Calling
↓
getPurchaseRecommendation()
↓
Backend Result
↓
LLM Explanation
```

## 23.2 Supplier Comparison

使用者：

> 哪個 Supplier 最適合這次採購？

流程：

```text
getSupplierCandidates()
↓
getSupplierRanking()
↓
Backend Result
↓
LLM Explanation
```

## 23.3 Document Query

使用者：

> Supplier A 的付款條件是什麼？

流程：

```text
RAG
↓
Supplier Contract
↓
Answer + Source
```

---

# 24. AI Failure Boundary

若發生：

- LLM Timeout
- Provider Unavailable
- Invalid Response
- RAG Failure

核心流程仍必須可以繼續：

```text
Demand
Inventory
Shortage Detection
Purchase Recommendation
Supplier Ranking
PO
Approval
Ordering
Receiving
Inventory Update
```

AI 失效不應造成核心採購系統不可使用。

---

# 25. As-Is → To-Be 對照

| As-Is | To-Be |
|---|---|
| Excel 整合需求、庫存與在途 PO | SupplyPilot 自動整合 |
| 人工計算 Shortage | Backend 計算 Net Requirement |
| 人工套用 MOQ | Backend 產生 Purchase Recommendation |
| 靠採購經驗比較 Supplier | Hard Constraint + Ranking |
| 決策理由未留存 | Recommendation + Override Reason |
| 人工找 PDF / Email | RAG 文件查詢 |
| Excel / 個人提醒追 PO | Overdue Detection |
| 收貨與庫存異動缺乏整體 Transaction 思考 | Receiving Transaction |
| 人工解釋採購原因 | AI Explanation |
| 推薦與最終決策混在一起 | System Recommendation + Human Decision |

---

# 26. V1 已確認核心規則

以下規則正式作為 V1 設計基準：

## Rule 1 — Incoming Inventory

> 只有 `ORDERED` PO 計入 Incoming Inventory。

## Rule 2 — Supplier Recommendation

> Supplier 必須先通過 Hard Constraint，再進入 Ranking。

## Rule 3 — Human Override

> Procurement Staff 可以修改 Supplier 或 Purchase Quantity，但必須留下 Override Reason。

## Rule 4 — PO Status

V1 狀態包含：

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
```

## Rule 5 — Overdue

> `OVERDUE` 不是 PO lifecycle status，而是依 `Expected Delivery Date` 與 `ORDERED` 狀態計算出的交貨狀態／旗標。

---

# 27. V1 暫不處理

本流程目前刻意不處理：

- Partial Receiving
- Multi-level Approval
- Approval Matrix
- Multi-Warehouse
- Full RFQ Workflow
- Supplier Portal
- Complete MRP
- Automated PO Sending
- Automated Supplier Negotiation
- AI Auto Approval
- AI Auto Ordering

---

# 28. 後續待定事項

以下內容交由 Functional Requirements 階段繼續細化：

1. Demand 欄位與日期模型
2. Safety Stock 的設定方式
3. Inventory Import Validation
4. Import Transaction Strategy
5. Demand Aggregation Rule
6. Incoming Inventory Aggregation Rule
7. Purchase Recommendation 的 MOQ / Packaging Rule
8. Supplier Hard Constraint 完整清單
9. Supplier Ranking Normalization
10. Reliability Score 資料來源
11. Quality Score 資料來源
12. Supplier Ranking Tie-breaking Rule
13. Recommendation 是否需要持久化
14. Override Reason 是否必填文字
15. PO Reject 是否需要 Reject Reason
16. PO Transition Permission
17. Expected Delivery Date 的建立與修改規則
18. Overdue 的 API 表達方式
19. Duplicate Receiving Prevention
20. Idempotency Strategy
21. Inventory Update Concurrency Strategy
22. StockTransaction 欄位
23. AI Tool Input / Output Schema
24. RAG Document Types
25. AI Explanation 是否需要持久化

---

# 29. 下一階段

下一階段：

# Functional Requirements

Functional Requirements 必須將本 To-Be Flow 轉換成：

```text
User / Role
↓
Action
↓
System Behavior
↓
Business Rule
↓
Validation
↓
Expected Result
↓
Acceptance Criteria
```

後續所有 Requirement 都必須能回頭對應到：

- `company-profile.md`
- `as-is-procurement-flow.md`
- `to-be-procurement-flow.md`

避免出現沒有商業來源、單純為了展示技術而加入的功能。
