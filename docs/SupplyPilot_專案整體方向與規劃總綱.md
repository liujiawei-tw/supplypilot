# SupplyPilot — AI 智慧採購與供應鏈決策平台

> **英文名稱**：SupplyPilot — AI-Powered Procurement & Supply Chain Decision Platform  
> **專案定位**：以小型企業為目標場景的 Java / Spring Boot 後端作品集專案  
> **主要目的**：作為轉職 Java Backend Engineer 的代表作品，展示從需求分析、系統設計、商業邏輯、資料一致性、AI 整合、部署到工程品質的完整能力。  
> **核心原則**：AI 是系統的一部分，不是整個系統。

---

# 1. 專案背景與目標

SupplyPilot 的核心問題不是「做一套完整 ERP」，而是解決小型企業在採購與庫存管理中常見的幾個實際痛點：

- 庫存資料分散，難以及時知道哪些原料即將不足。
- 採購人員需要人工整理庫存、在途採購、需求量與安全庫存。
- 供應商價格、交期、MOQ、品質與可靠度難以快速比較。
- 採購決策過度依賴人工經驗，缺乏一致的判斷依據。
- 企業文件、供應商合約與採購 SOP 不容易快速查找。
- 傳統系統能「記錄資料」，但不一定能協助使用者「理解資料與做決策」。

SupplyPilot 希望建立一個：

> **以可靠的商業邏輯負責計算與交易，以 AI 負責查詢、理解、解釋與輔助決策的採購／供應鏈平台。**

專案不追求模擬大型跨國企業，而是以約 5～30 人的小型企業為目標使用場景，控制系統規模，同時保留足夠的工程深度。

---

# 2. 作品集定位

本專案首先是一個 **Java Backend Engineering Portfolio Project**，其次才是 AI 專案。

希望透過 SupplyPilot 證明以下能力：

1. 能把真實商業需求轉換成 Domain Model 與系統流程。
2. 能使用 Java / Spring Boot 建構 RESTful Backend。
3. 能正確處理資料庫 Transaction、Validation 與資料一致性。
4. 能理解 MySQL、Redis、Elasticsearch 等不同儲存／查詢工具的責任邊界。
5. 能使用 Docker 與 AWS 部署實際可操作的服務。
6. 能建立基本測試、CI、監控與效能驗證。
7. 能整合 Spring AI、RAG、Tool Calling / Agent，而不是單純包一層聊天介面。
8. 能說明每項技術「為什麼使用」以及「不用的代價與替代方案」。
9. 能針對錯誤、併發、失敗情境與 AI 不確定性設計保護機制。
10. 能持續以版本迭代改善系統，而不是一次性完成作業。

---

# 3. 產品核心概念

SupplyPilot 的基本流程：

```text
商品 / 原料資料
      ↓
供應商資料
      ↓
目前庫存
      ↓
需求 / 安全庫存
      ↓
缺料判斷
      ↓
採購數量建議
      ↓
供應商比較
      ↓
建立採購草稿
      ↓
人工確認 / 核准
      ↓
正式採購單
      ↓
收貨
      ↓
庫存異動
```

AI 則工作在這條流程的上層：

```text
                       使用者
                          │
                          ▼
                     Spring Boot
                          │
          ┌───────────────┼──────────────┐
          │               │              │
          ▼               ▼              ▼
        MySQL           Redis      Elasticsearch
          │
          ▼
      Business Logic
          │
          ▼
       Spring AI
       ├── Tool Calling
       ├── RAG
       └── Procurement Agent
          │
          ▼
   分析 / 查詢 / 解釋 / 建議
          │
          ▼
      Human Approval
```

重要原則：

> **確定性的資料與計算交給程式；非結構化資訊的理解、自然語言互動與解釋才交給 AI。**

---

# 4. AI 與傳統後端的責任邊界

## 4.1 Java / Business Logic 負責

例如：

- 庫存數量。
- 安全庫存。
- 在途採購量。
- MOQ。
- 採購數量計算。
- PO 狀態轉換。
- 收貨。
- 庫存異動。
- Transaction。
- 權限。
- Supplier Ranking 的確定性公式。
- 資料 Validation。

例如：

```text
建議採購量
=
預計需求
+ 安全庫存
- 現有庫存
- 在途庫存
```

如果得到 85 kg，而供應商 MOQ 為 100 kg：

```text
原始需求：85 kg
MOQ：100 kg

最終建議：100 kg
```

這類結果不應讓 LLM 自行計算。

## 4.2 AI 負責

AI 主要提供：

- 使用自然語言查詢供應鏈資料。
- 解釋採購建議為什麼產生。
- 比較供應商差異。
- 統整多個後端 Tool 的結果。
- 查詢採購 SOP、供應商合約、產品規格文件。
- 將複雜資料轉換成人類容易理解的說明。
- 產生 Purchase Order Draft。

AI 不應直接：

- 自行修改庫存。
- 自行核准採購。
- 自行執行高風險交易。
- 將模型推測當成資料庫事實。

因此採用：

```text
AI Recommendation
      ↓
Draft
      ↓
Human Review
      ↓
Approve / Modify / Reject
```

---

# 5. 預計核心 Domain

第一階段主要 Domain：

## Material

原料基本資料，例如 code、name、unit、safetyStock、status。

## Supplier

供應商資料，例如 code、name、country、reliability、qualityScore、status。

## SupplierMaterial

描述供應商與原料之間的供應關係，例如 supplierId、materialId、price、MOQ、leadTime、currency。

## Inventory

目前庫存，例如 materialId、quantity、updatedAt。

## PurchaseOrder

採購單 Header，例如 supplierId、status、orderDate、expectedDeliveryDate、createdAt、updatedAt。

## PurchaseOrderItem

採購單內容，例如 purchaseOrderId、materialId、quantity、unitPrice。

## StockTransaction

庫存異動歷史，例如 PURCHASE_RECEIVE、MANUAL_ADJUSTMENT、CONSUMPTION。

## User

系統使用者。初期僅需要基本身份與權限架構。

> Domain 欄位與實際 ERD 尚未最終定案，下一階段再依需求與流程確認。

---

# 6. Purchase Order 狀態流程

第一版預計採：

```text
DRAFT
  ↓
APPROVED
  ↓
ORDERED
  ↓
RECEIVED
```

必要時可增加 CANCELLED、PARTIALLY_RECEIVED，但 V1 先避免過度複雜。

系統必須限制非法狀態轉換，例如：

```text
RECEIVED → DRAFT
```

應直接拒絕。

---

# 7. 收貨與 Transaction

收貨是 SupplyPilot 很重要的工程情境。

```text
BEGIN TRANSACTION

1. 確認 PO 狀態
2. 更新 PO 狀態
3. 增加 Inventory
4. 建立 StockTransaction

COMMIT
```

任一步驟失敗則 ROLLBACK。

藉此展示：

- @Transactional
- Data Consistency
- Error Handling
- Transaction Boundary

---

# 8. 採購建議

SupplyPilot 的主要 Business Logic 之一：

```text
Net Requirement
=
Demand
+ Safety Stock
- Available Inventory
- Incoming Inventory
```

之後再套用 MOQ、包裝倍數、供應商可供應量、Lead Time 等限制。

範例：

```text
Material: Strawberry Concentrate
Current Inventory: 20 kg
Incoming PO: 10 kg
Expected Demand: 100 kg
Safety Stock: 15 kg
Calculated Shortage: 85 kg
Recommended Purchase: 100 kg
Reason: Supplier MOQ = 100 kg
```

---

# 9. Supplier Ranking

第一版供應商推薦不交給 AI，而採確定性的 Ranking Formula。

初步概念：

```text
Supplier Score =
Price Score        × 40%
Lead Time Score    × 30%
Reliability Score  × 20%
Quality Score      × 10%
```

權重未來可調整。

AI 可以解釋排名與取捨，但 Ranking 本身由 Backend 計算。

---

# 10. Demand Forecast

V1 不追求複雜 Machine Learning。

先建立容易驗證的 Baseline，例如：

- Moving Average
- Weighted Moving Average

原因是小企業資料量有限時，複雜 ML 模型不一定具有實際收益。

之後資料足夠，再評估 Prophet、XGBoost、Time Series Model、Neural Network 等。

Forecast 是未來可擴充模組，不應阻塞核心 MVP。

---

# 11. Redis 定位

Redis 不作為核心資料來源。

預計用於：

- Supplier recommendation cache
- 高頻查詢 cache
- 未來 Session / Rate Limit 等

後續要明確討論：

- TTL
- Cache invalidation
- Redis unavailable fallback
- Cache hit rate

---

# 12. Elasticsearch 定位

Elasticsearch 用於：

- Material Search
- Supplier Search
- 未來 PO / Document Search

即使資料量不大，作品集中仍可用來展示 Relational Database 與 Search Engine 的責任分工。

---

# 13. RAG

RAG 不處理即時交易數字，而處理企業非結構化文件。

預計資料來源：

- Supplier Contract
- Procurement SOP
- Product Specification
- Quotation
- Quality Requirement

流程：

```text
Document
   ↓
Parse
   ↓
Chunk
   ↓
Embedding
   ↓
Vector Store
   ↓
Retrieval
   ↓
LLM
   ↓
Answer + Source
```

重要要求：

- 回答能附來源。
- 無足夠證據時承認資料不足。
- 文件保留版本資訊。

---

# 14. Procurement Agent

V1 不做 Multi-Agent，只設計一個 Procurement Agent。

預計 Tools：

```text
getInventory(materialId)
getDemand(materialId)
getPurchaseRecommendation(materialId)
getSupplierCandidates(materialId)
getOpenPurchaseOrders(materialId)
createPurchaseDraft(...)
```

Agent 可以分析與建立 Draft，但不能直接 Approve。

---

# 15. 初步技術架構

主要技術：

```text
Java
Spring Boot
Maven
Spring Data JPA
MySQL
Redis
Elasticsearch
Spring AI
Docker
Git / GitHub
AWS
CloudWatch
```

可能使用：

```text
JUnit
Testcontainers
GitHub Actions
k6
Spring Boot Actuator
```

前端不是核心求職目標，因此只需做到可操作、可展示，不投入大量時間在非必要的視覺效果。

---

# 16. 架構原則：Modular Monolith 優先

V1 不採 Microservices。

原因：

- 目標企業規模小。
- 開發者只有一人。
- Domain 尚在快速演進。
- 分散式系統成本大於收益。

初步模組概念：

```text
SupplyPilot
├── material
├── supplier
├── inventory
├── procurement
├── forecasting
├── search
├── ai
└── common
```

保持模組邊界，但先維持單一 Spring Boot Application。

---

# 17. 14 天 MVP 思路

14 天的目標不是做到最終版本，而是：

> **建立一個已部署、核心流程完整、可以被實際操作與展示的 V1。**

核心成功流程：

```text
Material
   ↓
Supplier
   ↓
Inventory
   ↓
Shortage Detection
   ↓
Purchase Recommendation
   ↓
Supplier Recommendation
   ↓
PO Draft
   ↓
Approval
   ↓
Receiving
   ↓
Inventory Update
```

至少應具備：

- MySQL
- Spring Boot
- JPA
- Validation
- Exception Handling
- Transaction
- Docker
- AWS Deployment
- 基本測試

時間允許後再加入：

- Redis
- Elasticsearch
- Spring AI
- RAG
- Agent
- Forecast
- CI
- CloudWatch
- Load Test

目前方向是希望 14 天盡可能完成整條技術鏈的「窄版實作」，而不是把每項技術做到企業級。

---

# 18. V1 → V1.1 → V1.2

## V1 — It works

- 核心流程可操作。
- 可部署。
- 可以 Demo。
- 主要 Business Logic 正確。

## V1.1 — It works correctly

強化：

- Transaction
- Optimistic Lock
- Idempotency
- Validation
- Exception Handling
- Security
- Unit Test
- Integration Test
- Concurrency Test

## V1.2 — We know how it behaves

強化：

- Load Testing
- Performance Optimization
- Redis performance measurement
- JPA N+1 檢查
- AI Evaluation
- RAG Evaluation
- Metrics
- Structured Logging
- CloudWatch
- Failure Testing

---

# 19. 預計工程情境

SupplyPilot 不只做 Happy Path。

後續應刻意測試：

## Concurrent Receiving

多個 Request 同時收同一張 PO，驗證 Duplicate Receiving、Lost Update、Inventory corruption。

## Duplicate Request

使用者重複送出建立 PO，驗證 Idempotency 與 Unique Constraint。

## Transaction Failure

Inventory 更新成功但 StockTransaction 建立失敗時，驗證 Rollback。

## Redis Failure

Redis unavailable 時 fallback 到 MySQL，核心流程不應完全停止。

## LLM Failure

Timeout、Provider unavailable、Invalid response 時，核心採購系統仍應可運作。

## RAG Insufficient Evidence

找不到文件證據時應回覆 insufficient evidence，而不是 hallucination。

---

# 20. Performance 與 Load Testing

未來透過 k6 等工具模擬不同使用者量，觀察：

- Throughput
- Average latency
- p95 latency
- Error rate
- Database bottleneck
- Cache hit rate

作品集應盡量保留 Before / After 數據。

核心思路：

> 發現問題 → 建立假設 → 改善 → 再測量。

---

# 21. Observability

第一階段不追求完整 Observability Platform。

預計採：

```text
Spring Boot Actuator
+
Structured Logging
+
AWS CloudWatch
```

初步監控：

- Health
- CPU
- Memory
- Error log
- Request latency
- AI latency
- LLM error
- 未來 Token usage
- Cache hit rate

---

# 22. Testing Strategy

預計逐步建立：

```text
Unit Test
      ↓
Service / Business Logic

Integration Test
      ↓
Spring Boot + Database

Concurrency Test
      ↓
Inventory / Receiving

Failure Test
      ↓
Redis / AI / Database

Load Test
      ↓
System Performance
```

可能使用 Testcontainers 啟動 MySQL、Redis、Elasticsearch 測試環境。

---

# 23. CI/CD

初步方向：

```text
Developer Push
      ↓
GitHub
      ↓
GitHub Actions
      ↓
Compile
      ↓
Test
      ↓
Build
      ↓
Docker Image
      ↓
Deploy
```

第一版不一定需要複雜 CD，但至少希望 CI 能 Build、Test，並阻止失敗版本合併。

---

# 24. AWS Deployment

初步考慮：

- EC2：Spring Boot / Docker
- RDS MySQL：Production Database
- S3：企業文件
- CloudWatch：Logs / Monitoring

AWS 架構仍需在後續規劃階段依成本與實際限制確認。

---

# 25. GitHub Repository 方向

Repository：

```text
supply-pilot
```

Java Package：

```text
com.supplypilot
```

Application：

```text
supply-pilot-api
```

預計文件結構：

```text
supply-pilot/
│
├── backend/
├── docs/
│   ├── requirements.md
│   ├── architecture.md
│   ├── erd.md
│   ├── api.md
│   ├── testing.md
│   ├── load-test.md
│   ├── ai-evaluation.md
│   └── adr/
│
├── docker/
├── scripts/
├── .github/
│   └── workflows/
├── docker-compose.yml
└── README.md
```

ADR 可記錄：

```text
001-why-modular-monolith.md
002-why-mysql.md
003-why-redis.md
004-why-elasticsearch.md
005-why-ai-cannot-approve-po.md
```

---

# 26. README 最終需要回答什麼

1. 這個專案解決什麼問題？
2. 目標使用者是誰？
3. 系統架構為什麼這樣設計？
4. 核心流程是什麼？
5. 哪些資料由 MySQL 管理？
6. Redis 解決什麼問題？
7. Elasticsearch 解決什麼問題？
8. AI 為什麼存在？
9. AI 不能做什麼？
10. 如何確保資料一致性？
11. 如何測試？
12. 如何部署？
13. 曾遇到什麼問題？
14. 如何改善效能？
15. 系統目前有哪些限制？

---

# 27. 專案真正的作品集價值

SupplyPilot 最終不是靠「技術數量」取勝，而是呈現：

```text
Business Problem
       ↓
Domain Modeling
       ↓
Architecture Decision
       ↓
Implementation
       ↓
Testing
       ↓
Deployment
       ↓
Measurement
       ↓
Failure
       ↓
Improvement
```

面試時可以從本專案延伸討論：

- Controller / Service / Repository
- DTO / Entity
- Validation
- Global Exception Handling
- Transaction
- Database Design
- Index
- Lock
- Concurrency
- Redis
- Elasticsearch
- Docker
- AWS
- CI/CD
- Monitoring
- Load Testing
- AI Tool Calling
- RAG
- Hallucination
- Human-in-the-loop
- Architecture Trade-off

---

# 28. V1 階段刻意不做

除非未來出現明確需求，V1 不主動加入：

- Microservices
- Kubernetes
- Kafka
- Event-driven architecture
- Multi-Agent
- 自建 LLM
- 自訓大型神經網路
- 複雜 ML pipeline
- 完整 ERP
- 財務會計模組
- HR
- CRM
- 多國跨區部署

---

# 29. 開發原則

整個專案採：

```text
需求
↓
最小可運作流程
↓
部署
↓
測試
↓
取得回饋
↓
找實際問題
↓
再增加技術
```

而不是：

```text
想到技術
↓
塞進專案
↓
再替技術找用途
```

每一項新增功能都必須回答：

> **它正在解決哪個具體問題？**

沒有答案就先不加入。

---

# 30. 下一階段規劃事項

下一個對話建議依以下順序細化：

1. 確定 Target Company 與實際使用場景。
2. 定義 V1 使用者角色。
3. 定義完整 User Flow。
4. 撰寫 Functional Requirements。
5. 撰寫 Non-functional Requirements。
6. 定義 Acceptance Criteria。
7. 建立 Domain Model。
8. 畫 ERD。
9. 定義 API。
10. 決定 Package Architecture。
11. 決定 Error / Validation Strategy。
12. 決定 Transaction Boundary。
13. 決定 Redis 使用位置。
14. 決定 Elasticsearch Index。
15. 決定 AI Tools。
16. 決定 RAG Document Model。
17. 決定 AWS Deployment Architecture。
18. 排出 14 天開發順序與每日 Done Definition。

---

# 31. 專案一句話定義

> **SupplyPilot 是一套面向小型企業的 AI 智慧採購與供應鏈決策平台，以 Spring Boot 與可靠的商業邏輯管理庫存、供應商與採購流程，再透過 AI、搜尋與企業知識協助使用者更快理解資訊並做出可追溯的採購決策。**

---

# 32. 現階段成功定義

專案不是以「用了多少技術」判斷成功。

真正的成功標準是：

> **任何一位面試官打開 SupplyPilot，都能看出這不是 CRUD 練習，而是一個有清楚 Domain、商業邏輯、工程取捨、AI 邊界、測試策略與部署思考的後端系統。**

同時，使用者實際打開系統時，至少能完整完成：

```text
看到缺料
→ 理解缺料原因
→ 查看採購建議
→ 比較供應商
→ 建立 PO
→ 核准
→ 收貨
→ 庫存正確更新
```

這條核心流程，是 SupplyPilot 後續所有設計的主幹。
