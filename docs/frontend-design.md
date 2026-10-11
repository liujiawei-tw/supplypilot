# SupplyPilot — Frontend Design

> **文件目的**：定義 SupplyPilot V1 前端的技術棧、頁面、Route、角色顯示規則、API 串接、JWT、錯誤處理與作品集展示重點。  
> **定位**：作品集用 Thin Frontend。前端的任務是完整展示後端 Business Flow，而不是把 SupplyPilot 變成前端作品集。  
> **原則**：Frontend 約佔整體專案工作量 20–25%，Backend 約 75–80%。

---

# 1. Frontend Goal

SupplyPilot 前端的主要目的：

```text
讓使用者可以操作完整採購流程
↓
讓面試官看得懂 Backend Business Logic
↓
讓 Security / Transaction / Lifecycle / AI Boundary 有可視化結果
```

前端優先：

```text
Flow clarity
Data clarity
Role clarity
Error clarity
```

而不是：

```text
Animation
Complex visual design
Custom design system
Perfect mobile UI
```

---

# 2. Technology Stack

V1 建議：

```text
React
TypeScript
Vite
Ant Design
Axios
React Router
```

可選：

```text
TanStack Query
```

如果時間緊：

```text
Axios + local component state
```

即可。

不建議 V1 加：

```text
Redux
Zustand
Next.js
Micro Frontend
Custom UI Framework
```

除非真的有需求。

---

# 3. Frontend Repository Structure

建議前後端分 Repo：

```text
supplypilot-backend/
supplypilot-frontend/
```

Frontend：

```text
supplypilot-frontend/
├── src/
│   ├── api/
│   │   ├── axiosClient.ts
│   │   ├── auth.ts
│   │   ├── material.ts
│   │   ├── supplier.ts
│   │   ├── demand.ts
│   │   ├── inventory.ts
│   │   ├── recommendation.ts
│   │   ├── purchaseOrder.ts
│   │   ├── document.ts
│   │   └── ai.ts
│   │
│   ├── auth/
│   │   ├── AuthContext.tsx
│   │   ├── RequireAuth.tsx
│   │   └── RoleGuard.tsx
│   │
│   ├── components/
│   │   ├── StatusTag/
│   │   ├── ErrorAlert/
│   │   ├── ConfirmAction/
│   │   └── PageHeader/
│   │
│   ├── layouts/
│   │   └── AppLayout.tsx
│   │
│   ├── pages/
│   │   ├── Login/
│   │   ├── Dashboard/
│   │   ├── Materials/
│   │   ├── Suppliers/
│   │   ├── Demands/
│   │   ├── Inventory/
│   │   ├── Recommendations/
│   │   ├── PurchaseOrders/
│   │   ├── Documents/
│   │   └── AiAssistant/
│   │
│   ├── router/
│   │   └── routes.tsx
│   │
│   ├── types/
│   ├── utils/
│   ├── App.tsx
│   └── main.tsx
│
├── .env.example
├── package.json
├── vite.config.ts
└── README.md
```

---

# 4. Authentication

Login：

```text
POST /api/v1/auth/login
```

Frontend：

```text
username
password
↓
login()
↓
receive JWT
↓
store token
↓
load /auth/me
↓
build AuthContext
```

---

# 5. JWT Storage

V1 作品集可以使用：

```text
localStorage
```

原因：

```text
simple implementation
Bearer Authorization Header
no cookie CSRF complexity
```

但 README 應註明：

> Production-grade authentication 可改為 HttpOnly Secure Cookie / Refresh Token 策略；V1 為降低 MVP 複雜度採 Bearer JWT。

---

# 6. Axios Client

所有 API Request 經過：

```text
axiosClient
```

Request Interceptor：

```text
Authorization: Bearer <JWT>
```

Response Interceptor：

```text
401
→ logout / redirect Login

403
→ show permission error

409
→ show business conflict

500 / 503
→ show system/provider error
```

---

# 7. Role Guard

Frontend Role Guard 只負責：

```text
UI visibility
route UX
button visibility
```

真正 Security 仍由 Backend：

```text
@PreAuthorize
```

處理。

不能因為：

```text
button hidden
```

就認為功能安全。

---

# 8. Routes

建議：

```text
/login

/dashboard

/materials
/suppliers
/demands
/inventory
/inventory/import
/recommendations
/recommendations/:id
/purchase-orders
/purchase-orders/:id
/receiving/purchase-orders
/receiving/purchase-orders/:id
/documents
```

採 Hybrid Navigation：Sidebar 依業務模組（主檔、需求／庫存、採購決策、採購單／收貨、文件）分組，頁內以流程連結串接 Demand → Inventory → Recommendation → PO → Receiving。Sidebar 與 Route Guard 依 `security-design.md` 授權矩陣顯示，Backend Spring Security 為權威。Global Header 提供角色、帳號與可授權的 AI Drawer 入口；Page Header 提供標題、狀態、主要動作與返回流程連結。無權限頁面顯示清楚 403，不藉由隱藏入口代替授權。

`/inventory` 對具 Inventory GET 或 StockTransaction GET 任一權限的角色開放；`/inventory/import` 僅對 `WAREHOUSE_STAFF`、`ADMIN` 開放。Sidebar 的 Inventory 入口在任一 Tab 可用時顯示；收貨入口只對倉管與 ADMIN 顯示。`/purchase-orders/:id` 是一般採購 PO Detail；`/receiving/purchase-orders/:id` 是倉管受限 Receiving Detail。兩者使用各自的 Read API 與 DTO。

---

# 9. Login Page

內容：

```text
SupplyPilot logo/name
Username
Password
Login button
Demo account hints
```

Demo Accounts 可顯示：

```text
planner
buyer
manager
warehouse
admin
```

但不要顯示真 Production Password。

作品集環境可使用固定 Demo Password。

---

# 10. Dashboard

Dashboard 目的是：

> 使用者一進來就知道今天有什麼採購工作。

可顯示：

```text
Low / shortage materials
Pending Recommendations
DRAFT POs
POs waiting approval
ORDERED POs
Overdue POs
Recent Receiving
```

V1 不需要做複雜 BI。

Dashboard 採 Role-Based Operations Inbox，僅呈現該角色可查與可操作的待辦；每張卡片連到有授權的現有列表或工作頁。若沒有對應 Backend aggregate API，以已授權查詢組成可用的待辦，不虛構跨角色統計或 Recommendation 歷史清單。

---

# 11. Material Page

列表：

```text
Code
Name
Base Unit
Safety Stock
Status
Updated At
```

ADMIN：

```text
Create
Edit
Activate / Inactivate
```

其他 Role：

```text
Read only
```

---

# 12. Supplier Page

Supplier List：

```text
Code
Name
Country
Reliability
Quality
Status
```

Supplier Detail：

```text
SupplierMaterial
├── Material
├── Unit Price
├── MOQ
├── Package Multiple
├── Lead Time
└── Status
```

ADMIN 可維護。

---

# 13. Demand Page

主要 Role：

```text
PRODUCTION_PLANNER
ADMIN
```

欄位：

```text
Material
Quantity
Required Date
Status
Note
```

功能：

```text
Create
Edit
Cancel
```

不要提供 Hard Delete。

---

# 14. Inventory Page

欄位：

```text
Material
Current Quantity
Base Unit
Safety Stock
Updated At
```

Warehouse/Admin 顯示：

```text
Manual Adjustment
Import Inventory
```

其他有 Inventory GET 權限的 Role 可 Read Only；`PROCUREMENT_MANAGER` 無此 API 權限。

Inventory 主頁採 `Stock Levels` / `Stock Transactions` Tabs，分別只在 Inventory GET（`PRODUCTION_PLANNER`、`PROCUREMENT_STAFF`、`WAREHOUSE_STAFF`、`ADMIN`）與 StockTransaction GET（`WAREHOUSE_STAFF`、`PROCUREMENT_MANAGER`、`ADMIN`）有權限時顯示及載入。進入 `/inventory` 時預設開第一個有權限的 Tab；`PROCUREMENT_MANAGER` 直接進 Stock Transactions，不能先呼叫 Inventory GET。Deep Link（例如 `/inventory?tab=stock-transactions`）只啟用有權限的 Tab；指定無權限 Tab 時轉到第一個有權限的 Tab，不發送該 API 請求；兩者都無權限才顯示 403。Import 與 Manual Adjustment Action 仍只給倉管／ADMIN。不可把不同基本單位的 quantity 相加為無單位總量。SP-802 負責 Tab 容器與庫存頁，SP-805 負責交易查詢內容。

---

# 15. Inventory Import UI

流程：

```text
Upload CSV
↓
Validate
↓
Preview
↓
Confirm
```

Preview Table：

| Row | Material Code | Quantity | Unit | Result |
|---:|---|---:|---|---|
| 1 | MAT-001 | 100 | KG | OK |
| 2 | MAT-002 | 30 | PCS | UNIT_MISMATCH |

若：

```text
VALIDATION_FAILED
```

Confirm Button Disable。

若：

```text
VALIDATED
```

才可：

```text
Confirm Import
```

Confirm 前要二次確認。

---

# 16. Recommendation Page

Recommendation 是單次決策工作站：輸入物料與期間 → POST 計算 → 直接呈現結果、供應商排名與建立 DRAFT 入口。可用已知 ID 開啟 Detail；Backend 無歷史 List API，故不設虛構歷史清單。跨頁保留材料／需求的導航上下文，但數值與決策以 Backend 回應為準。

這是 SupplyPilot 前端最重要的頁面。

Input：

```text
Material
Period Start
Period End
```

Action：

```text
Calculate Recommendation
```

---

# 17. Recommendation Summary

顯示：

```text
Expected Demand
Safety Stock
Current Inventory
Incoming Inventory
Net Requirement
Outcome
```

例如：

```text
Expected Demand      1,000 KG
Safety Stock           200 KG
Current Inventory      300 KG
Incoming Inventory     150 KG
--------------------------------
Net Requirement        750 KG
```

Outcome Badge：

```text
PURCHASE_NOT_REQUIRED
SUPPLIER_RECOMMENDED
NO_QUALIFIED_SUPPLIER
```

---

# 18. Supplier Ranking Table

顯示：

| Rank | Supplier | Qualified | Qty | Unit Price | Total Cost | Lead Time | Reliability | Quality | Score |
|---:|---|---|---:|---:|---:|---:|---:|---:|---:|

Disqualified Supplier 仍可顯示：

```text
reason
```

例如：

```text
DELIVERY_DATE_NOT_FEASIBLE
SUPPLIER_INACTIVE
```

---

# 19. Recommendation Action

若：

```text
outcome = SUPPLIER_RECOMMENDED
```

顯示：

```text
Create PO Draft
```

點擊後：

```text
recommended supplier
recommended quantity
recommendation reference
supplier result reference
```

帶入 Draft Form。

---

# 20. Purchase Order List

欄位：

```text
PO Number
Supplier
Status
Expected Delivery Date
Created By
Created At
```

Status：

```text
DRAFT
APPROVED
ORDERED
RECEIVED
REJECTED
```

使用明確 Badge。

---

# 21. Purchase Order Detail

顯示：

```text
Header
Supplier
Items
Recommendation Snapshot Reference
Override Reason
Lifecycle Audit
Expected Delivery
Status
```

Items：

```text
Material
Quantity
Unit
Unit Price
Recommended Quantity
Override Reason
```

---

# 22. PO Role Actions

PROCUREMENT_STAFF：

```text
DRAFT
→ Edit

APPROVED
→ Mark Ordered
```

PROCUREMENT_MANAGER：

```text
DRAFT
→ Approve
→ Reject
```

WAREHOUSE_STAFF：

```text
/receiving/purchase-orders/:id 的 ORDERED
→ Receive
```

ADMIN：

```text
Role access to V1 operations
```

`/purchase-orders/:id` 只供一般採購 PO Detail 使用，並讀完整 `PurchaseOrderResponse`；Warehouse 不可開啟此頁，亦不可為了收貨先呼叫一般 PO GET。Warehouse 在 `/receiving/purchase-orders/:id` 使用受限 DTO 執行 Receive。ADMIN 可依用途進入兩個頁面，採購動作留在一般 PO Detail，Receive 動作留在 Receiving Detail。

但 UI 不提供非法 transition。

Backend 仍為最終防線。

---

# 23. Reject Dialog

Reject：

```text
Reason required
```

Modal：

```text
Reject Purchase Order
Reason textarea
Confirm
```

Frontend 可先驗 required。

Backend 再驗一次。

---

# 24. Mark Ordered Dialog

必須確認：

```text
Expected Delivery Date
```

若 PO 還沒有：

```text
block Order and show legacy-data repair guidance
```

Order Modal 只顯示已核准交期與明確確認動作，不輸入或修改交期。DRAFT 可留空或清除交期；Approve 前顯示必填錯誤，需在 DRAFT Edit 補齊。APPROVED 不可再使用 Draft Edit API。

---

# 25. Receiving UI

Receiving 使用 `/receiving/purchase-orders` List 與 `/receiving/purchase-orders/:id` Detail，與一般 `/purchase-orders/:id` 採購 PO Detail 分開。`WAREHOUSE_STAFF`、`ADMIN` 透過受限 Read DTO 查看 ORDERED 待收貨，並可切換 RECEIVED 歷史核實未知結果；不顯示單價、金額、評分與覆寫理由。Receive Action 僅位於此 Receiving Detail，不在一般 PO Detail 顯示。

ORDERED：

```text
[Confirm Receiving]
```

Modal 顯示：

```text
PO Number
Supplier
All PO Items
Receiving Type: Full Receive
```

提示：

```text
This will update inventory and create stock transactions.
```

確認後：

```text
POST /purchase-orders/{id}/receive
```

成功：

```text
PO → RECEIVED
Refresh Inventory
```

提交後以 Receiving Detail 重新確認狀態；收到 `409 DUPLICATE_RECEIVING` 或逾時／未知結果時先查詢 ORDERED / RECEIVED 真實狀態，再告知使用者，不自動重送 Receive。

---

# 26. Receiving Demo Value

作品集 Demo 建議刻意展示：

```text
Before:
Inventory MAT-001 = 100

Receive:
PO item = +50

After:
Inventory MAT-001 = 150
StockTransaction = +50 PURCHASE_RECEIVE
PO = RECEIVED
```

這是 Transaction / Audit 最直接的視覺證明。

---

# 27. StockTransaction UI

置於 Inventory 的 `Stock Transactions` Tab；只有具 StockTransaction GET 權限者，才可從 PO / Receiving Detail 看見以 `purchaseOrderId` 篩選的連結，連到 `/inventory?tab=stock-transactions&purchaseOrderId={id}`。不具權限者不顯示連結，也不發送查詢。依 Backend C 唯讀 API 查詢；尚未實作前不得以假資料宣稱功能完成。

Read-only table：

```text
Time
Material
Type
Quantity
PO Number
Reason
Created By
```

用於展示：

```text
PURCHASE_RECEIVE
MANUAL_ADJUSTMENT
```

---

# 28. Document Page

V1：

```text
Upload Document
List Document Metadata
Status
Supplier
Type
Version
Uploaded At
```

Status：

```text
PROCESSING
READY
FAILED
INACTIVE
```

---

# 29. AI Assistant

採全站 Global Drawer：

```text
right-side Drawer
```

可採約 480–520px 的響應式寬度；多品項 Draft 必須可完整檢視，窄螢幕可使用全寬 Drawer。入口只向 AI Query 授權角色顯示，Proposal / Confirm 控制僅 `PROCUREMENT_STAFF`、`ADMIN` 可見。

V1 可支援：

```text
Explain recommendation
Query inventory
Query open PO
RAG document question
Create PO Draft
```

---

# 30. AI UI Boundary

AI 回覆若要建立 Draft：

```text
AI proposes action
↓
User reviews all supplier / item / quantity / price / delivery date fields
↓
User explicitly confirms with backend confirmationToken before expiresAt
↓
Backend createDraft()
```

AI V1 為單輪同步 JSON，無持久對話記憶或 Streaming。Tool 保持唯讀；提議可有空交期，但核准前須補齊。倒數依回應 `expiresAt` 計算，不固定寫死十分鐘；過期重新提議，不重用 Token。`PROCUREMENT_MANAGER` 僅可 Query，不可 Proposal / Confirm。

Frontend 不提供 AI：

```text
Approve
Reject
Order
Receive
Inventory Adjustment
```

---

# 31. AI Explanation Example

User：

```text
為什麼推薦 Supplier A？
```

UI 顯示：

```text
Supplier A 在目前評分權重下總分最高。
雖然總成本略高，但交期較短、Reliability 較高。
```

並顯示：

```text
Source / Recommendation ID
```

如果 RAG：

```text
Document source
version
```

一併顯示。

---

# 32. Error UX

Backend Error：

```json
{
  "code": "CONCURRENT_MODIFICATION",
  "message": "...",
  "traceId": "...",
  "timestamp": "...",
  "path": "..."
}
```

Frontend：

```text
Business Error
→ readable alert

409 concurrency
→ "資料已被其他操作更新，請重新整理後再試。"

500
→ generic system error + traceId

503 AI
→ AI unavailable, core system remains usable
```

`409 DUPLICATE_RECEIVING`：查 Receiving Detail 的 RECEIVED 狀態，明確告知已完成與否。Network timeout / unknown result：先查 Backend 真實狀態，不自動重送 Receive。`403` 顯示未授權頁面；`500` 僅顯示安全訊息及 `traceId`。

---

# 33. Validation Boundary

Frontend：

```text
required
basic number/date format
button disable
```

Backend：

```text
real business validation
cross-entity validation
domain invariant
security
```

Frontend 不複製所有 business rule。

---

# 34. Loading / Empty / Error States

每個主要頁面至少處理：

```text
Loading
Empty
Error
Success
```

不要只處理成功畫面。

---

# 35. Concurrency UX

收到：

```text
409 CONCURRENT_MODIFICATION
```

Frontend：

```text
show warning
↓
invalidate / refetch current resource
↓
ask user to review latest state
```

不要自動重送高風險操作。

---

# 36. Overdue Display

OVERDUE 不是 DB Status。

PO List / Detail 直接使用 Backend `PurchaseOrderResponse.isOverdue`；Receiving List / Detail 使用受限 `ReceivingPurchaseOrderResponse.isOverdue`。瀏覽器不得自行用日期判定業務逾期。只有 `status=ORDERED` 且 Backend 回傳 `isOverdue=true` 才顯示：

```text
OVERDUE
```

Badge。

`RECEIVED` 的 `isOverdue=false`，不得標成目前逾期；逾期只是展示狀態，不建立 `OVERDUE` DB Status。

但仍保留真正狀態：

```text
ORDERED
```

---

# 37. Frontend Testing

V1 不需要大規模測試。

P0：

```text
Login flow
Role route/button visibility
Inventory Import preview
Recommendation render
PO lifecycle action visibility
409 handling
```

可使用：

```text
Vitest
React Testing Library
```

如果時間不足，可保：

```text
manual demo checklist
```

但 Login / Role / Lifecycle 至少應有少量測試。

---

# 38. Frontend Deployment

建議：

```text
Vercel
Netlify
Cloudflare Pages
```

Backend：

```text
AWS EC2 / Render / Railway
```

Frontend env：

```text
VITE_API_BASE_URL
```

不要 hardcode API URL。

---

# 39. CORS Coordination

Backend Production：

```text
allowed origin = actual frontend domain
```

Frontend：

```text
https://supplypilot-demo.xxx
```

不要：

```text
*
```

---

# 40. UI Priority

P0：

```text
Login
Inventory Import
Recommendation
PO List / Detail
Approve / Reject / Order / Receive
```

P1：

```text
Material
Supplier
Demand
Dashboard
StockTransaction
```

P2：

```text
Document UI polish
AI drawer polish
charts
advanced filter
responsive perfection
```

---

# 41. What Not to Build

14-day V1 不做：

```text
Complex animation
Dark/Light theme toggle
Custom design system
Drag/drop dashboard
WebSocket real-time
Mobile app
Pixel-perfect responsive
Large chart suite
Complex state management
```

---

# 42. Demo Flow

面試 Demo：

```text
Login as Planner
↓
Create Demand

Login as Warehouse
↓
Import Inventory

Login as Buyer
↓
Calculate Recommendation
↓
View Supplier Ranking
↓
Create PO Draft

Login as Manager
↓
Approve PO

Login as Buyer
↓
Mark Ordered

Login as Warehouse
↓
Receive PO

Check:
Inventory
StockTransaction
PO status

Login as Buyer
↓
Ask AI:
"Why was this supplier recommended?"
```

這條流程應是整個前端設計的核心。

---

# 43. Final Frontend Rules

1. Frontend 是 Thin Client，不重做 Backend Business Logic。
2. React + TypeScript + Vite + Ant Design。
3. Axios 統一處理 Bearer JWT。
4. RoleGuard 只改善 UX，真正 Authorization 在 Backend。
5. Recommendation Page 是作品集主頁面。
6. Inventory Import 必須有 Validate / Preview / Confirm UI。
7. PO Lifecycle 必須依 Role 顯示正確 Action。
8. Receiving 使用明確 Confirmation。
9. 409 不自動 retry 高風險操作。
10. AI 不提供 Approve / Order / Receive / Inventory Write UI。
11. Frontend env 使用 `VITE_API_BASE_URL`。
12. Production CORS 只允許前端 Domain。
13. 不投入大量時間做 UI polish。
14. 前端與後端以 Vertical Slice 同步開發。
15. Frontend 工作量控制在整體 20–25%。

---

# 44. Implementation Timing

14 天內：

```text
Day 2
Frontend skeleton + Login

Day 3–5
Master / Demand / Inventory pages

Day 6–7
Recommendation UI

Day 8–10
Purchase Order lifecycle UI

Day 11
Document UI

Day 12
AI Assistant

Day 13
Frontend deployment / CORS integration

Day 14
End-to-end demo / bug fix
```

不要等到 Day 13 才第一次開始前端。
