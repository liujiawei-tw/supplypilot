# SupplyPilot — Virtual Company Profile

> **文件目的**：定義 SupplyPilot 專案所使用的虛擬公司背景，作為後續需求分析、User Flow、Domain Model、ERD、API、測試資料、RAG 文件與 Demo 情境的共同基礎。  
> **文件定位**：上游規格文件（Working Document）  
> **目前狀態**：V0.1 — 初始公司設定  
> **重要原則**：本文件只描述會影響系統設計與開發決策的公司資訊，不追求完整企業世界觀。

---

# 1. 公司基本資料

## 1.1 公司名稱

**中文名稱：福爾摩沙風味食品有限公司**  
**英文名稱：Formosa Flavor Labs Co., Ltd.**

> 此名稱為 SupplyPilot 專案中的虛擬公司名稱。

## 1.2 公司類型

B2B 食品／飲料原料製造商。

公司主要向供應商採購食品與飲料相關原料，再進行加工、調配、生產與出貨，客戶主要為其他企業，而非一般消費者。

## 1.3 公司規模

約 **22 人**。

整體屬於小型企業，符合 SupplyPilot 原始規劃中約 5～30 人的小型企業目標場景。

## 1.4 主要客戶

- 手搖飲品牌
- 咖啡連鎖
- 烘焙業者
- 餐飲品牌
- 食品加工廠

## 1.5 主要產品

- 水果濃縮液
- 茶類濃縮液
- 糖漿
- 調味粉
- 香料粉
- 果醬／果泥
- 奶茶基底
- 風味醬

---

# 2. 組織與主要角色

SupplyPilot V1 不模擬完整企業組織，只保留與採購、庫存與供應鏈流程直接相關的角色。

## 2.1 Procurement Staff — 採購人員

SupplyPilot 的主要使用者。

主要工作：

- 查看缺料狀況
- 查看採購建議
- 比較不同供應商
- 建立 Purchase Order Draft
- 查詢未交採購單
- 查看供應商資料
- 查詢採購相關文件
- 追蹤預計交貨日期

## 2.2 Procurement Manager — 採購主管

主要工作：

- 查看採購草稿
- 檢查採購理由
- 修改採購數量
- 核准採購
- 拒絕採購
- 查看供應商推薦與比較結果

SupplyPilot 中高風險採購行為仍由人員確認，不交由 AI 自動執行。

## 2.3 Warehouse Staff — 倉庫人員

主要工作：

- 收貨
- 確認實際收貨數量
- 執行必要的庫存調整
- 查看原料庫存
- 建立或觸發庫存異動紀錄

此角色是後續 Transaction、Concurrency、Idempotency 與 StockTransaction 設計的重要使用情境來源。

## 2.4 Production Planner — 生產／物料人員

主要工作：

- 提供未來生產需求
- 提供未來原料需求
- 查看原料是否足以支撐排定生產

V1 不建立完整生產管理或 MRP 模組。

初期假設 Production Planner 會將未來一段時間的需求提供給 SupplyPilot，作為 Demand 的來源。

## 2.5 Admin — 系統管理員

主要工作：

- 管理使用者
- 管理角色
- 維護基本 Master Data
- 處理基礎系統設定

V1 不建立複雜 IAM 或企業級權限系統。

---

# 3. 公司現行資訊系統環境

在 SupplyPilot 上線前，公司已經存在一套基本 ERP。

目前日常工作主要依賴：

```text
ERP
+
Excel
+
Email
+
LINE / Teams
+
人工經驗
```

## 3.1 ERP 目前能做的事情

- 原料料號
- 基本庫存
- 基本採購單
- 收貨紀錄
- 基礎交易資料

## 3.2 ERP 目前不足的地方

- 主動判斷未來缺料
- 整合 Demand、Inventory、Incoming PO
- 自動產生採購建議
- 多供應商條件比較
- 解釋採購建議理由
- 查詢企業非結構化文件
- 整理過往採購決策依據

---

# 4. SupplyPilot 在公司的定位

SupplyPilot **不是 ERP 替代品**。

SupplyPilot 的定位為：

> **Procurement Intelligence Layer**

也就是建立在既有企業資料與採購流程之上的採購與供應鏈決策平台。

SupplyPilot 主要負責：

- 整合採購決策需要的資訊
- 判斷原料缺口
- 計算採購需求
- 比較供應商
- 建立採購草稿
- 解釋決策原因
- 協助查詢企業文件
- 協助採購人員更快做出可追溯的決策

ERP 或核心 Business Logic 仍負責確定性的交易與資料。

---

# 5. 原料類型

初期原料分為五大類。

## 5.1 Fruit Ingredients — 水果原料

- Strawberry Concentrate
- Mango Puree
- Passion Fruit Concentrate

## 5.2 Tea Ingredients — 茶類原料

- Black Tea Extract
- Green Tea Extract
- Oolong Tea Extract

## 5.3 Sweeteners — 糖類／甜味劑

- Fructose Syrup
- Cane Sugar
- Maltodextrin

## 5.4 Flavoring — 香料／調味料

- Vanilla Flavor
- Peach Flavor
- Lemon Flavor

## 5.5 Dairy / Creamer — 奶粉／奶精

- Whole Milk Powder
- Non-dairy Creamer

V1 不需要建立數百種原料；正式測試資料可在系統結構確定後再擴充。

---

# 6. 倉庫設定

V1 設定：

- 1 個主要原料倉庫
- 1 個主要生產區

V1 **不實作多倉庫架構**。

原因是多倉會額外引入：

- Warehouse-level Inventory
- Stock Transfer
- Transfer Order
- Cross-warehouse Reservation
- In-transit Transfer

這些問題會明顯提高 V1 的系統複雜度，但不是目前 SupplyPilot 最核心的展示價值。

---

# 7. 供應商設定

公司約有 **8～12 家主要供應商**。

同一個原料可能由多個供應商提供。

不同供應商可能存在以下差異：

- Price
- MOQ
- Lead Time
- Quality
- Reliability
- Currency
- Country / Region

範例：

```text
Material:
Strawberry Concentrate

Supplier A
Price: 80 / kg
MOQ: 100 kg
Lead Time: 14 days

Supplier B
Price: 85 / kg
MOQ: 50 kg
Lead Time: 7 days

Supplier C
Price: 72 / kg
MOQ: 300 kg
Lead Time: 30 days
```

此設定使 Supplier Ranking 與 Supplier Recommendation 具有真實商業意義。

---

# 8. 採購特性

## 8.1 Lead Time

供應商交期大約分布在：

```text
3 ～ 45 days
```

通常：

- 本地原料 Lead Time 較短
- 進口原料 Lead Time 較長

## 8.2 MOQ

MOQ 由 SupplierMaterial 關係決定。

常見範例：

```text
50 kg
100 kg
200 kg
500 kg
```

相同原料在不同供應商可能有不同 MOQ。

## 8.3 採購頻率

```text
高使用量原料
→ 每週採購

一般原料
→ 每 2～4 週

進口原料
→ 每 1～3 個月
```

V1 不要求自動建立固定採購週期，但這些資料可以作為後續 Demand Forecast 與採購分析的情境基礎。

---

# 9. 核心需求計算假設

公司不以單純的低庫存判斷是否採購。

核心邏輯為：

```text
Net Requirement
=
Expected Demand
+ Safety Stock
- Available Inventory
- Incoming Inventory
```

之後再考慮：

- MOQ
- 包裝倍數
- Supplier Constraint
- Lead Time
- 可供應量

範例：

```text
Current Inventory: 200 kg
Expected Demand: 400 kg
Safety Stock: 50 kg
Incoming Inventory: 100 kg

Net Requirement
=
400
+ 50
- 200
- 100

= 150 kg
```

因此即使目前 Inventory 看起來很高，只要未來需求更高，仍然可能需要採購。

---

# 10. SupplyPilot 上線前的 As-Is 採購概念流程

目前預設的採購流程如下：

```text
ERP 匯出庫存資料
        ↓
採購人員查看目前庫存
        ↓
確認未來需求
        ↓
確認未交 PO
        ↓
Excel 人工整理與計算
        ↓
找歷史價格 / 報價資料
        ↓
詢問供應商
        ↓
比較 Price / MOQ / Lead Time
        ↓
人工決定供應商
        ↓
建立採購單
        ↓
主管確認 / 核准
        ↓
供應商出貨
        ↓
倉庫收貨
        ↓
ERP 更新庫存
```

此流程目前仍是初步假設。

後續會另外進行 As-Is Flow 的完整拆解與確認。

---

# 11. 公司目前核心痛點

## 11.1 無法快速知道真正缺多少原料

問題來源：

- Inventory 在 ERP
- Demand 由生產人員提供
- Incoming PO 在採購紀錄
- Safety Stock 另外維護

採購人員需要人工整理資料。

## 11.2 Supplier Comparison 過度依賴個人經驗

不同供應商存在：

- 價格差異
- MOQ 差異
- Lead Time 差異
- Reliability 差異
- Quality 差異

目前缺少一致、可追溯的比較方式。

## 11.3 未交 PO 容易漏追

可能發生：

```text
Expected Delivery Date 已過
但 PO 尚未完成收貨
```

目前依賴採購人員自行追蹤。

未來 SupplyPilot 可考慮加入：

- Overdue PO
- Open PO
- Delivery Risk

## 11.4 文件分散

相關企業文件可能分散於：

- Supplier Contract
- Quotation
- Product Specification
- Quality Requirement
- Procurement SOP

採購人員需要自行搜尋 PDF、Email 或共用資料夾。

此痛點是後續 RAG 的主要使用情境之一。

## 11.5 採購決策理由沒有被系統性保存

例如某次採購選擇較昂貴的 Supplier B，可能是因為：

```text
+ Lead Time 較短
+ Reliability 較高
+ MOQ 較低
- Price 較高
```

目前這類判斷通常存在採購人員個人經驗中。

SupplyPilot 希望讓採購建議與實際決策具備可追溯性。

---

# 12. AI 在虛擬公司的角色

AI 可以：

- 使用自然語言查詢採購資料
- 解釋缺料原因
- 解釋採購建議
- 整理供應商差異
- 整合多個 Tool 的查詢結果
- 查詢採購 SOP
- 查詢 Supplier Contract
- 查詢產品規格
- 產生 Purchase Order Draft
- 將複雜資訊轉換成容易理解的說明

AI 不可以：

- 直接修改庫存
- 直接完成收貨
- 直接核准 PO
- 直接執行高風險交易
- 把推測視為資料庫事實
- 自己計算核心交易數字並直接寫入正式資料

---

# 13. V1 不處理的公司情境

目前 V1 刻意不處理：

- 多倉庫
- 多公司／多法人
- 多國分公司
- 複雜 MRP
- 完整生產排程
- 財務會計
- HR
- CRM
- 完整 ERP
- 高度複雜的供應鏈網路
- Microservices
- Event-driven architecture

除非後續需求分析發現其中某項對 SupplyPilot 核心流程不可缺少，否則不主動加入。

---

# 14. 後續需要繼續確認的事項

> **V1 Design Freeze（2026-10-08）**：本節保留為需求分析歷程。下列事項已由後續的 To-Be Flow、Functional Requirements、Domain Model、API、Security、Testing 與 Backlog 文件定案；若文字衝突，以後續專項規格與 `SUPPLYPILOT_V1_ISSUES_ZH-TW.md` 為準。

以下內容尚未最終定案：

1. 完整 As-Is Procurement Flow
2. SupplyPilot To-Be Flow
3. Production Planner 如何提供 Demand
4. Safety Stock 如何設定
5. Demand 的時間範圍
6. Incoming Inventory 的認定方式
7. PO 的完整狀態流程
8. Partial Receiving 是否納入 V1
9. Purchase Approval Rule
10. Supplier Ranking Formula
11. Supplier Reliability 的來源
12. Supplier Quality Score 的來源
13. Overdue PO 判斷規則
14. 是否需要 Purchase Request
15. 是否需要 Requester / Approver 分離
16. Manual Inventory Adjustment 的權限
17. ERP 與 SupplyPilot 在 V1 是否真實串接，或以 Mock / Import Data 模擬
18. 文件版本管理方式
19. RAG 文件類型與來源
20. Demo Data 規模

---

# 15. 對後續系統設計的影響

此 Virtual Company Profile 將作為以下文件與設計工作的上游依據：

```text
company-profile.md
        ↓
requirements.md
        ↓
User Flow
        ↓
Domain Model
        ↓
ERD
        ↓
API
        ↓
Business Rules
        ↓
Testing Strategy
        ↓
Demo Data
```

若未來修改本文件中的重要公司假設，例如：

- 單倉庫改為多倉庫
- 新增 Purchase Request
- 新增多層核准
- Demand 來源改變
- ERP 整合方式改變
- Supplier 關係改變

則必須重新檢查相關 Domain、ERD、API、Business Logic 與 Test Cases 是否受到影響。

---

# 16. 一句話公司定義

> **Formosa Flavor Labs 是一家約 22 人的台灣 B2B 食品與飲料原料製造商，向多家國內外供應商採購水果、茶類、糖類、香料與奶粉等原料。公司已有基本 ERP，但採購人員仍大量依賴 Excel 與人工方式整合庫存、需求、在途採購與供應商資訊。SupplyPilot 作為 Procurement Intelligence Layer，協助公司判斷缺料、計算採購需求、比較供應商、建立採購草稿並追蹤收貨，同時使用 AI 協助查詢、文件理解與決策解釋，而正式交易與核准仍由 Backend Business Logic 與人員控制。**
