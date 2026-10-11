# SupplyPilot V1 — 可執行 GitHub Issues 開發 Backlog

> **目的**：將目前已確認的 19 份 SupplyPilot Specification 編譯成可直接建立於 GitHub、並供 GPT-5.6 Sol / Codex 逐張執行的 Issue Backlog。  
> **執行模式**：Specification → Milestone / Epic → 單一邊界清楚的 GitHub Issue → Codex 實作 → 測試 → PR / Review。  
> **V1 原則**：開發期間不任意擴張 Scope。若發現規格衝突或新問題，以小型 Ticket / ADR / Spec Correction 處理，不預設重新設計整套架構。

---

# 0. Source of Truth

本 Backlog 來源為下列 19 份 Specification：

- `SupplyPilot_專案整體方向與規劃總綱.md`
- `company-profile.md`
- `as-is-procurement-flow.md`
- `to-be-procurement-flow.md`
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `package-architecture.md`
- `transaction-boundary.md`
- `validation-exception.md`
- `security-design.md`
- `testing-strategy-merged.md`
- `frontend-design.md`
- `non-functional-requirements.md`
- `environment-configuration.md`
- `coding-standards.md`
- `acceptance-criteria.md`
- `cross-document-consistency-review.md`
- `pre-implementation-checklist.md`

> 若 Issue 與 Specification 發生衝突，**不得自行猜測**。先停止該 Issue，指出衝突條文，透過明確的 Spec Correction / ADR 解決後再繼續實作。

---

# 1. V1 全域約束

以下規則套用至所有 Issue：

- 架構採 **Modular Monolith + Package by Feature + Layer inside Feature**。
- Backend 負責確定性計算、Transaction、Lifecycle、Validation、Authorization 與 Data Integrity。
- AI 負責查詢、理解、說明與輔助決策，不得成為核心採購數字的來源。
- 高風險行為由人員控制。AI 最多只能建立 `DRAFT`，不得執行 Approve / Reject / Order / Receive / Adjust Inventory / Manage Users / Master Data Write。
- Controller 僅處理 HTTP / DTO Validation；Service 負責 Use Case orchestration、Cross-Entity Validation 與 Transaction；Domain 保護 Lifecycle / Invariant；Repository 負責 Persistence / Query。
- 禁止 Controller → Repository shortcut；禁止 AI Tool → Repository write。
- 核心 Entity 不 Hard Delete，除非 Specification 明確允許。
- V1 為單公司、單倉庫、TWD 單一幣別；不做 Unit Conversion Engine、Partial Receiving、Microservices。
- `Inventory` 與 `PurchaseOrder` 依規格使用 optimistic locking（`@Version`）。
- 外部 AI / File / Provider 呼叫不得長時間佔用 DB Transaction。
- 所有程式碼遵循 `coding-standards.md`。

---

# 2. 全域完成定義（Definition of Done）

一張 Issue 只有在所有適用條件都成立時才算完成：

- Issue 中的驗收條件全部通過。
- Required Tests 通過，P0 行為不可無測試保護。
- 相關 build / compile / lint 通過。
- 不得無理由修改與 Issue 無關的檔案或加入 Scope 外功能。
- Security / Transaction / Domain Boundary 維持正確。
- 不得加入 Secret、真實公司機密資料或 Sensitive Log。
- 規格沒有支持的假設必須明確記錄，不得自行補完成「既定事實」。

---

# 3. GitHub Project 設定

**Milestone：** `SupplyPilot V1`

建議 Labels：

`area:backend`, `area:frontend`, `area:ai`, `area:infra`  
`type:feature`, `type:test`, `type:setup`, `type:bug`  
`priority:p0`, `priority:p1`, `priority:p2`  
`risk:transaction`, `risk:security`, `risk:concurrency`  
`complexity:simple`, `complexity:normal`, `complexity:hard`

---

# 4. Codex 執行規約

Codex 執行每一張 Issue 時：

1. 完整閱讀該 Issue。
2. 只讀取 Issue 指定的 Specification、`coding-standards.md`，以及目前 repo 中真正相關的程式碼與測試。
3. 修改程式前先檢查目前 repository 狀態。
4. 只實作足以滿足驗收條件的最小完整方案。
5. 不得實作「不在本 Issue 範圍」中的項目。
6. 執行 Required Tests 與必要 Regression Tests。
7. 完成後回報：修改檔案、測試命令與結果、驗收條件狀態、使用的假設、剩餘風險。
8. Dependencies 尚未完成或合併時，不得直接開始依賴它的 Issue。

---

# 5. Epic / Dependency Overview

```text
E0 基礎建設
  ↓
E1 Security / User ──────────────┐
  ↓                             │
E2 Master Data                  │
  ↓                             │
E3 Demand        E4 Inventory   │
   \              /             │
    └──→ E5 Recommendation ←────┘
              ↓
         E6 Procurement
              ↓
     E7 Document / RAG / AI
              ↓
        E8 Thin Frontend
              ↓
        E9 Quality / Release
```

---

# 6. Issue 索引

| ID | Epic | Issue 標題 | Priority | Complexity | Dependencies |
|---|---|---|---|---|---|
| SP-001 | E0 | 建立 Backend、Profiles、MySQL 與 Flyway 基礎環境 | P0 | Normal | None |
| SP-002 | E0 | 建立共用 Validation 與 Exception Contract | P0 | Normal | None |
| SP-003 | E0 | 建立測試基礎設施、Fixtures 與 Fixed Clock | P0 | Normal | None |
| SP-004 | E0 | 建立 Frontend Shell 與環境基線 | P1 | Normal | None |
| SP-100 | E1 | 實作 User Domain 與 Admin 使用者管理 | P0 | Normal | SP-001, SP-002 |
| SP-101 | E1 | 實作 JWT 登入與 Current User API | P0 | Hard | SP-100 |
| SP-102 | E1 | 實作角色授權與 CurrentUserProvider Audit Boundary | P0 | Hard | SP-101 |
| SP-200 | E2 | 實作 Material Vertical Slice 與 Inventory 原子建立 | P0 | Hard | SP-001, SP-002, SP-100 |
| SP-210 | E2 | 實作 Supplier Vertical Slice 與 Lifecycle | P0 | Normal | SP-001, SP-002, SP-102 |
| SP-220 | E2 | 實作 SupplierMaterial 供應條件 Vertical Slice | P0 | Normal | SP-200, SP-210 |
| SP-300 | E3 | 實作 Demand 建立、修改、取消與查詢 Lifecycle | P0 | Normal | SP-200, SP-102 |
| SP-301 | E3 | 實作 Demand Aggregation Query | P0 | Normal | SP-300 |
| SP-400 | E4 | 實作 Inventory 查詢 API | P0 | Normal | SP-200, SP-102 |
| SP-401 | E4 | 實作人工庫存調整與 StockTransaction | P0 | Hard | SP-400 |
| SP-410 | E4 | 實作 Inventory CSV Validate 與 Preview Persistence | P0 | Hard | SP-200, SP-400, SP-003 |
| SP-411 | E4 | 實作 All-or-Nothing Inventory Import Confirm | P0 | Hard | SP-410 |
| SP-412 | E4 | 實作 Inventory Optimistic Lock 與 409 Conflict | P0 | Hard | SP-401, SP-411, SP-002 |
| SP-500 | E5 | 實作由 ORDERED PO 推導的 IncomingInventoryQuery | P0 | Normal | SP-600 |
| SP-501 | E5 | 實作確定性的 Net Requirement 與 Purchase Quantity Calculator | P0 | Normal | SP-301, SP-400 |
| SP-502 | E5 | 實作 Supplier Hard Constraint Evaluation | P0 | Normal | SP-220, SP-501 |
| SP-503 | E5 | 實作 Supplier Score Ranking 與 Deterministic Tie-break | P0 | Normal | SP-502 |
| SP-504 | E5 | 實作 PurchaseRecommendation Snapshot API 與 Outcomes | P0 | Hard | SP-500, SP-501, SP-502, SP-503 |
| SP-600 | E6 | 實作 Purchase Order Draft Aggregate 與建立 API | P0 | Hard | SP-200, SP-210, SP-220, SP-102 |
| SP-601 | E6 | 實作 Recommendation-based Draft 與人工 Override 紀錄 | P0 | Normal | SP-504, SP-600 |
| SP-602 | E6 | 實作 DRAFT 修改規則與 Aggregate Ownership | P0 | Normal | SP-600 |
| SP-610 | E6 | 實作 PO Approve / Reject Lifecycle Action | P0 | Hard | SP-602, SP-102 |
| SP-611 | E6 | 實作 Mark Ordered、Expected Delivery、Open / Overdue Query | P0 | Normal | SP-610 |
| SP-612 | E6 | 實作完整 PO Receiving Transaction | P0 | Hard | SP-611, SP-401, SP-412 |
| SP-613 | E6 | 實作 PurchaseOrder Optimistic Lock 與 Concurrent Receive Guard | P0 | Hard | SP-612, SP-002 |
| SP-700 | E7 | 實作 DocumentMetadata Upload 與 Lifecycle | P0 | Hard | SP-001, SP-002, SP-102 |
| SP-710 | E7 | 實作 RAG Ingestion 與 Evidence-grounded Document Query | P0 | Hard | SP-700 |
| SP-720 | E7 | 實作 AI Assistant Query 與 Read-only Procurement Tools | P0 | Hard | SP-504, SP-611, SP-710, SP-102 |
| SP-721 | E7 | 實作 AI 輔助 PO Draft 建立與明確 User Confirmation | P0 | Hard | SP-601, SP-720 |
| SP-800 | E8 | 實作 Frontend Authentication、Session 與 Role Guards | P1 | Normal | SP-004, SP-101, SP-102 |
| SP-801 | E8 | 實作 Material / Supplier / SupplierMaterial Admin Pages | P1 | Normal | SP-800, SP-200, SP-210, SP-220 |
| SP-802 | E8 | 實作 Demand 與 Inventory 操作頁面 | P1 | Normal | SP-800, SP-300, SP-400, SP-401 |
| SP-803 | E8 | 實作 Inventory Import Validate / Preview / Confirm UI | P1 | Normal | SP-800, SP-410, SP-411 |
| SP-804 | E8 | 實作 Recommendation 與 Supplier Ranking UI | P1 | Normal | SP-800, SP-504 |
| SP-805 | E8 | 實作 Purchase Order Lifecycle、Receiving 與 Stock UI | P1 | Normal | SP-800, SP-610, SP-611, SP-612 |
| SP-806 | E8 | 實作 Document 與 AI Assistant UI | P1 | Normal | SP-800, SP-700, SP-720, SP-721 |
| SP-807 | E8 | 實作 Frontend Error、Loading 與 Concurrency UX 基線 | P1 | Normal | SP-800, SP-803, SP-805 |
| SP-900 | E9 | 完成 P0 Backend Integration / Security Test Gate | P0 | Hard | SP-003, SP-412, SP-504, SP-613, SP-720 |
| SP-901 | E9 | 建立 Deterministic Demo Seed 與 End-to-End Business Scenario | P1 | Normal | SP-504, SP-612, SP-700, SP-800 |
| SP-902 | E9 | 建立 Backend / Frontend CI Merge Gate | P1 | Normal | SP-003, SP-004 |
| SP-903 | E9 | Containerize Release Candidate 並驗證 Production Configuration | P1 | Hard | SP-001, SP-900, SP-807 |
| SP-904 | E9 | 執行最終 V1 Acceptance、文件與 Scope Freeze Release Review | P0 | Hard | SP-900, SP-901, SP-902, SP-903 |

---

# 7. 詳細 GitHub Issues

# E0 — 基礎建設

## SP-001 — 建立 Backend、Profiles、MySQL 與 Flyway 基礎環境

- **Epic：** E0 — 基礎建設
- **Priority：** P0
- **Complexity：** Normal
- **Risk：** Configuration
- **Dependencies：** None
- **Labels：** `area:backend`, `area:infra`, `type:setup`, `priority:p0`

### 目標
建立後續所有 Vertical Slice 共用、可實際啟動的 Spring Boot 基線。

### 本 Issue 範圍
- 建立 `com.supplypilot` Maven / Spring Boot Application。
- 設定 local / test / demo / prod profiles 與必要 Environment Variables。
- 透過 Docker Compose 啟動 Local MySQL。
- 啟用 Flyway 並建立初始 migration 路徑。
- 建立 `.env.example`，Secret 不可提交 Git。
- 依規格僅暴露必要 Actuator health / info。

### 不在本 Issue 範圍
- 商業功能實作。
- Redis、Elasticsearch、Microservices、HA。

### 規則
- Production 使用 `ddl-auto=validate` + Flyway。
- Integration Test 使用 Testcontainers MySQL。
- Secret 由 Environment / Secret Store 注入。

### 驗收條件
- [ ] `local` profile 可連線 Docker MySQL 並成功啟動。
- [ ] `test` profile 可使用隔離 MySQL 執行測試。
- [ ] Flyway migration 成功。
- [ ] Production 必填設定缺失時有明確錯誤。
- [ ] Git 中沒有 Secret。

### 必要測試
- [ ] Application Context smoke test。
- [ ] 適用時加入 configuration startup validation test。

### Specification References
- `environment-configuration.md`
- `non-functional-requirements.md`
- `package-architecture.md`
- `coding-standards.md`

---

## SP-002 — 建立共用 Validation 與 Exception Contract

- **Epic：** E0 — 基礎建設
- **Priority：** P0
- **Complexity：** Normal
- **Risk：** Error Contract
- **Dependencies：** None
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
建立所有 API 共用的 Validation / Exception / Error Response 基礎。

### 本 Issue 範圍
- 建立 `ErrorCode` baseline。
- 實作 BusinessException、Resource Not Found、Concurrency 等 mapping。
- 實作 `GlobalExceptionHandler`。
- Error Response 包含規格定義的 code / message / timestamp / path / traceId。
- Bean Validation field error 採一致格式。
- Database duplicate / integrity error 不得洩漏內部資訊。

### 不在本 Issue 範圍
- 各 Feature 專屬 Business Logic。

### 規則
- DTO Validation 處理格式與單欄位規則。
- Service 處理 Cross-Entity Rule。
- Domain 保護 Invariant。
- DB Constraint 是最後防線。

### 驗收條件
- [ ] Invalid DTO 回傳一致的 4xx contract。
- [ ] 找不到資源回傳規格定義的 NOT_FOUND code。
- [ ] Optimistic Lock 對應 409 `CONCURRENT_MODIFICATION`。
- [ ] Unexpected failure 回 generic 5xx 並附 traceId。

### 必要測試
- [ ] Validation / Not Found / Conflict / 5xx handler tests。

### Specification References
- `validation-exception.md`
- `api.md`
- `coding-standards.md`
- `acceptance-criteria.md`

---

## SP-003 — 建立測試基礎設施、Fixtures 與 Fixed Clock

- **Epic：** E0 — 基礎建設
- **Priority：** P0
- **Complexity：** Normal
- **Risk：** Testing
- **Dependencies：** None
- **Labels：** `area:backend`, `type:test`, `priority:p0`

### 目標
在商業功能大量增加前，先建立 deterministic test infrastructure。

### 本 Issue 範圍
- 建立對應 Module 的 JUnit test structure。
- Testcontainers MySQL integration base / configuration。
- 核心 Entity Fixture / Builder。
- 可注入與 Fixed `Clock` 策略。
- 建立虛構 baseline users / materials / suppliers codes。
- 建立 sample Inventory CSV fixture directory。

### 不在本 Issue 範圍
- 完整 Business Test Suite；各 Feature 自己補測試。

### 規則
- 不使用 Random Business Data。
- Test 不可依賴執行順序。
- Demo Data 與 Test Data 分離。
- Integration Test 不呼叫付費 AI API。

### 驗收條件
- [ ] Repository Integration Test 可跑於 Testcontainers MySQL。
- [ ] Fixed Clock test 可重現日期規則。
- [ ] Shared Fixture 不產生隱藏 DB coupling。

### 必要測試
- [ ] Testcontainers / Fixed Clock smoke tests。

### Specification References
- `testing-strategy-merged.md`
- `environment-configuration.md`
- `pre-implementation-checklist.md`

---

## SP-004 — 建立 Frontend Shell 與環境基線

- **Epic：** E0 — 基礎建設
- **Priority：** P1
- **Complexity：** Normal
- **Risk：** None
- **Dependencies：** None
- **Labels：** `area:frontend`, `type:setup`, `priority:p1`

### 目標
先建立 Thin Frontend 基礎，不在此 Issue 實作商業頁面。

### 本 Issue 範圍
- React + TypeScript + Vite + Ant Design + Axios + React Router。
- `VITE_API_BASE_URL`。
- Base Layout / Router / 共用 Loading、Error、Empty 元件。
- Axios client 與 Auth Token / Error Interceptor placeholder。

### 不在本 Issue 範圍
- Redux、Zustand、Next.js、Custom Design System。
- Feature Pages。

### 規則
- Frontend 只做作品集所需 Thin UI。
- 優先 Flow / Data / Role / Error clarity，不追求動畫與完美 Mobile UI。

### 驗收條件
- [ ] Frontend 可 build 並 local 啟動。
- [ ] Base Route 可顯示 App Shell。
- [ ] API Base URL 由 Environment 決定。

### 必要測試
- [ ] 若 test runner 已初始化，加入 frontend smoke test。

### Specification References
- `frontend-design.md`
- `environment-configuration.md`
- `coding-standards.md`

---

# E1 — Security / User

## SP-100 — 實作 User Domain 與 Admin 使用者管理

- **Priority：** P0
- **Complexity：** Normal
- **Risk：** None
- **Dependencies：** SP-001, SP-002
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
建立 V1 User Persistence 與 Security 所需最小 Admin User API。

### 本 Issue 範圍
- User：username / passwordHash / displayName / role / status。
- Roles：PRODUCTION_PLANNER、PROCUREMENT_STAFF、PROCUREMENT_MANAGER、WAREHOUSE_STAFF、ADMIN。
- ACTIVE / INACTIVE。
- User repository / service / DTO / API。
- 建立或更新密碼時使用 BCrypt，不回傳 Hash。

### 不在本 Issue 範圍
- Role / Permission table、Multi-role、OAuth / SSO / MFA。

### 規則
- INACTIVE User 不得登入。
- Password 不得明文儲存或回傳。

### 驗收條件
- [ ] ADMIN 可建立、查詢、修改 User。
- [ ] Duplicate username 被拒絕。
- [ ] Response 不包含 password / passwordHash。

### 必要測試
- [ ] Username uniqueness repository test。
- [ ] Status / Duplicate service tests。

### Specification References
- `domain-model.md`
- `api.md`
- `security-design.md`
- `functional-requirements.md`

---

## SP-101 — 實作 JWT 登入與 Current User API

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Security
- **Dependencies：** SP-100
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:security`, `complexity:hard`

### 目標
實作 Stateless Login 與 Authentication Context。

### 本 Issue 範圍
- Spring Security Stateless Chain。
- `CustomUserDetailsService` / `AuthenticationManager`。
- BCrypt password verification。
- HS256 JWT Service，TTL 可設定，baseline 約 1 小時。
- JWT Authentication Filter / `AuthenticatedUser`。
- `POST /api/v1/auth/login`。
- `GET /api/v1/auth/me`。
- 401 AuthenticationEntryPoint。

### 不在本 Issue 範圍
- Refresh Token、Token Blacklist、Session Management、Login Rate Limit。

### 規則
- INACTIVE User 在 Authentication 階段直接拒絕。
- 不記錄完整 JWT、Authorization Header、Password、Secret。
- JWT Secret 來自 Environment。

### 驗收條件
- [ ] ACTIVE User + 正確密碼可取得 JWT 與 Role。
- [ ] 錯誤密碼回 401。
- [ ] Missing / Invalid / Expired Token 回 401。
- [ ] `/auth/me` 回目前登入 User Metadata。

### 必要測試
- [ ] Login success / failure。
- [ ] Missing / Invalid JWT。
- [ ] INACTIVE User Login。

### Specification References
- `security-design.md`
- `api.md`
- `acceptance-criteria.md`
- `environment-configuration.md`

---

## SP-102 — 實作 Role Authorization 與 CurrentUserProvider Audit Boundary

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Security
- **Dependencies：** SP-101
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:security`, `complexity:hard`

### 目標
在 Use Case Boundary 強制角色授權，並提供可信任的 Audit Identity。

### 本 Issue 範圍
- 啟用 Method Security。
- `CurrentUserProvider` 從 SecurityContext 取得 User。
- 套用規格定義的 Role Matrix。
- 403 AccessDeniedHandler。
- Audit Field 一律由 Current User 取得。
- ADMIN 不可繞過 Domain Lifecycle。

### 不在本 Issue 範圍
- ABAC / Fine-grained Permission Table。
- AI System Admin Identity。

### 規則
- Client 不可傳入 Audit User。
- AI 未來沿用目前登入 User Authorization。
- UI Hide 不是 Security Boundary。

### 驗收條件
- [ ] 錯誤 Role 回 403。
- [ ] 正確 Role 可執行受保護 Use Case。
- [ ] Audit User 來自 SecurityContext，而非 Request Payload。

### 必要測試
- [ ] Planner / Procurement Staff / Manager / Warehouse / Admin Role Matrix tests。
- [ ] Audit Security Test。

### Specification References
- `security-design.md`
- `functional-requirements.md`
- `testing-strategy-merged.md`
- `api.md`

---

# E2 — Master Data

## SP-200 — 實作 Material Vertical Slice 與 Inventory 原子建立

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Transaction
- **Dependencies：** SP-001, SP-002, SP-100
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:transaction`

### 目標
完成 Material CRUD / Status 行為，並保證每個新 Material 都有 `Inventory(quantity=0)`。

### 本 Issue 範圍
- Material Entity / Enum / Repository / DTO / Mapper / Service / Controller。
- List / Get / Create / Update editable fields / Status。
- 建立 Material 時，同一 Transaction 建立 Inventory(0)。
- ACTIVE / INACTIVE Lifecycle。

### 不在本 Issue 範圍
- Hard Delete、Dynamic Safety Stock、Unit Conversion Engine。

### 規則
- Material Code Unique。
- Safety Stock >= 0。
- Base Unit 必須為 V1 允許值。
- INACTIVE Material 保留歷史資料，不可加入新下游交易。

### 驗收條件
- [ ] ADMIN 可建立 Unique Material。
- [ ] Material + Inventory(0) 同時 Commit。
- [ ] Inventory 建立失敗時 Material Rollback。
- [ ] Duplicate Code 被拒絕。

### 必要測試
- [ ] Material Domain / Service tests。
- [ ] Unique constraint test。
- [ ] Material + Inventory rollback integration test。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `transaction-boundary.md`
- `acceptance-criteria.md`
- `package-architecture.md`

---

## SP-210 — 實作 Supplier Vertical Slice 與 Lifecycle

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-001, SP-002, SP-102
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
建立 Recommendation / Procurement 使用的 Supplier Master Data。

### 本 Issue 範圍
- Supplier Entity / Status / Repository / DTO / Mapper / Service / Controller。
- Create / List / Get / Update / Activate / Inactivate。
- Reliability / Quality Score = 0～100。

### 不在本 Issue 範圍
- 自動推導 Reliability / Quality。
- Hard Delete。

### 規則
- INACTIVE Supplier 不得成為新 Recommendation Candidate 或建立新 PO，但歷史資料保留。

### 驗收條件
- [ ] ADMIN 可維護 Supplier。
- [ ] Invalid Score 被拒絕。
- [ ] INACTIVE Supplier 仍可查閱歷史資料。

### 必要測試
- [ ] Supplier Service / Domain Validation。
- [ ] Repository Unique Constraint。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `package-architecture.md`

---

## SP-220 — 實作 SupplierMaterial 供應條件 Vertical Slice

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-200, SP-210
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
表示某 Supplier 對某 Material 目前有效的供貨條件。

### 本 Issue 範圍
- SupplierMaterial Entity / Repository / DTO / Mapper / Service / Controller。
- Unit Price、MOQ、Package Multiple、Lead Time、Currency、Status。
- Create / List / Get / Update / Activate / Inactivate。
- Supplier + Material 關係 Constraint / Index。

### 不在本 Issue 範圍
- Quotation History / RFQ Workflow。
- Multi-currency Conversion。

### 規則
- 建立新關係時 Supplier / Material 必須存在且 ACTIVE。
- 數值需符合 Positive / Non-negative 規則。
- 使用 Material Base Unit。

### 驗收條件
- [ ] ADMIN 可建立有效 SupplierMaterial。
- [ ] Duplicate / Constraint Violation 被拒絕。
- [ ] INACTIVE SupplierMaterial 後續不可進 Recommendation。

### 必要測試
- [ ] SupplierMaterial Service Validation。
- [ ] Repository Constraint Tests。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `package-architecture.md`
- `validation-exception.md`

---

# E3 — Demand

## SP-300 — 實作 Demand 建立、修改、取消與查詢 Lifecycle

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-200, SP-102
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
實作由 Production Planner 輸入的未來需求。

### 本 Issue 範圍
- Demand Entity / Status / Repository / DTO / Mapper / Service / Controller。
- Create / List / Get / Update / Cancel Action Endpoint。
- Required Date / Quantity Validation。
- Role Restriction。

### 不在本 Issue 範圍
- MRP、Production Scheduling、Forecasting、Hard Delete。

### 規則
- Material 必須存在且 ACTIVE。
- Quantity > 0。
- CANCELLED Demand 保留，但不可進 Active Aggregation。

### 驗收條件
- [ ] Planner / ADMIN 可建立有效 Demand。
- [ ] Invalid / INACTIVE Material 被拒絕。
- [ ] Cancel 僅允許 ACTIVE → CANCELLED。
- [ ] 未授權 Role 被拒絕。

### 必要測試
- [ ] Demand Domain / Service Lifecycle。
- [ ] Role Security Tests。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `transaction-boundary.md`

---

## SP-301 — 實作 Demand Aggregation Query

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-300
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
提供 Net Requirement 所需、可重現的 Demand Aggregation。

### 本 Issue 範圍
- Repository / Service Query：依 Calculation Period / Date Boundary 聚合 ACTIVE Demand。
- 依 Material 計算 Aggregate Quantity。

### 不在本 Issue 範圍
- Forecast Generation。

### 規則
- CANCELLED Demand 排除。
- 涉及 Current Date 時使用 Fixed Clock semantics。

### 驗收條件
- [ ] Period 內 ACTIVE 500 + ACTIVE 300 + CANCELLED 400，結果 = 800。
- [ ] Period 外 Demand 不計入。

### 必要測試
- [ ] Demand Repository Aggregation Integration Test。
- [ ] Acceptance Baseline Scenario。

### Specification References
- `functional-requirements.md`
- `testing-strategy-merged.md`
- `acceptance-criteria.md`

---

# E4 — Inventory

## SP-400 — 實作 Inventory 查詢 API

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-200, SP-102
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
安全提供目前 Inventory，不提供任意 Quantity Mutation API。

### 本 Issue 範圍
- List 與 MaterialId Lookup。
- 適用時支援 Pagination。
- 回傳 Quantity、Material / Unit，必要時包含 Version。

### 不在本 Issue 範圍
- Generic `PATCH quantity`。
- Multi-Warehouse。

### 規則
- V1 Inventory 主要以 Material 識別。
- List size 最大 100。

### 驗收條件
- [ ] Authorized User 可查詢 Inventory。
- [ ] Unknown Material / Inventory 回規格錯誤。
- [ ] 無 API 可任意直接改 Quantity。

### 必要測試
- [ ] Repository / Query API tests。

### Specification References
- `api.md`
- `functional-requirements.md`
- `non-functional-requirements.md`

---

## SP-401 — 實作人工 Inventory Adjustment 與 StockTransaction

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Transaction
- **Dependencies：** SP-400
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:transaction`

### 目標
允許 Warehouse / ADMIN 調整 Inventory，同時以原子交易保留 Audit Trail。

### 本 Issue 範圍
- Inventory Domain Adjustment。
- `MANUAL_ADJUSTMENT` StockTransaction。
- Adjustment Action API + Reason。
- Current User Audit Fields。
- 與 Optimistic Lock 相容的更新路徑。

### 不在本 Issue 範圍
- Direct StockTransaction Create API。
- Negative Inventory。

### 規則
- 調整後 Inventory 不可 < 0。
- Inventory + StockTransaction 必須同一 Transaction Commit。
- Reason 依規格必填。

### 驗收條件
- [ ] Inventory 100 調整 -30 → 70，StockTransaction = -30。
- [ ] 造成 Quantity < 0 時拒絕且不 Commit。
- [ ] 僅允許指定 Role 執行。

### 必要測試
- [ ] Inventory Domain Test。
- [ ] Transaction Rollback Test。
- [ ] Security Test。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `transaction-boundary.md`
- `acceptance-criteria.md`

---

## SP-410 — 實作 Inventory CSV Validate 與 Preview Persistence

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Import
- **Dependencies：** SP-200, SP-400, SP-003
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `complexity:hard`

### 目標
以兩階段流程 Validate ERP CSV，保存可重現 Preview，但 Validate 階段不得修改 Inventory。

### 本 Issue 範圍
- CSV Parser。
- File Validation：只允許 CSV + Size Limit。
- Row Validation：Code / Existence / Quantity / Unit / Duplicate / Blank / Format。
- `InventoryImportBatch` + `InventoryImportItem` Persistence。
- `POST /inventory-imports/validate`。
- `VALIDATED` / `VALIDATION_FAILED`。
- Preview Counts + Row Errors。

### 不在本 Issue 範圍
- Excel Import、ERP API Integration、Partial Success Update。

### 規則
- Validate 階段 **不得改變 Inventory.quantity**。
- Server 不信任上傳檔案的 Path / Filename。
- Preview Row 必須 Persistence，讓 Confirm 成為第二個 Request。

### 驗收條件
- [ ] Valid CSV → VALIDATED Batch / Items，Inventory 不變。
- [ ] 任一 Row Error → VALIDATION_FAILED Preview，Inventory 不變。
- [ ] Error 包含 rowNumber / materialCode / errorCode / message。

### 必要測試
- [ ] Parser Unit Tests。
- [ ] 所有規格 Row Error Validator Tests。
- [ ] Validate 不修改 Inventory 的 Service Test。

### Specification References
- `functional-requirements.md`
- `transaction-boundary.md`
- `domain-model.md`
- `api.md`
- `acceptance-criteria.md`
- `non-functional-requirements.md`

---

## SP-411 — 實作 All-or-Nothing Inventory Import Confirm

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Transaction
- **Dependencies：** SP-410
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:transaction`, `complexity:hard`

### 目標
將 VALIDATED Inventory Snapshot 以原子方式套用。

### 本 Issue 範圍
- `POST /inventory-imports/{batchId}/confirm`。
- 僅允許 Batch=VALIDATED 且尚未 Imported。
- 讀取 Persisted Valid Items。
- `Inventory.quantity` 採 Replace，不是 Increment。
- 全部成功才將 Batch 設為 IMPORTED。

### 不在本 Issue 範圍
- ERP Snapshot Import 建立一般 StockTransaction。
- 失敗 Transaction 內另外 Persist `IMPORT_FAILED`。

### 規則
- 全批 All-or-Nothing。
- 技術失敗 Rollback Entire Batch；V1 Batch 保持 VALIDATED。
- Import 代表 Current Snapshot Replacement。

### 驗收條件
- [ ] Valid Batch 更新所有 Quantity 並成為 IMPORTED。
- [ ] 任一 Row Update 失敗，所有 Inventory Rollback，Batch 不得成為 IMPORTED。
- [ ] 第二次 Confirm 被拒絕。

### 必要測試
- [ ] Later Row Failure Transaction Integration Test。
- [ ] Duplicate Confirm Lifecycle Test。

### Specification References
- `functional-requirements.md`
- `transaction-boundary.md`
- `acceptance-criteria.md`
- `testing-strategy-merged.md`
- `cross-document-consistency-review.md`

---

## SP-412 — 實作 Inventory Optimistic Lock 與 409 Conflict

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Concurrency
- **Dependencies：** SP-401, SP-411, SP-002
- **Labels：** `area:backend`, `type:test`, `priority:p0`, `risk:concurrency`, `complexity:hard`

### 目標
避免 Inventory Silent Lost Update。

### 本 Issue 範圍
- Inventory 使用 JPA `@Version`。
- Optimistic Lock Failure → 409 `CONCURRENT_MODIFICATION`。
- Adjustment / Import / Receiving Path 相容。

### 不在本 Issue 範圍
- Distributed Lock、Global SERIALIZABLE、High-risk Write Auto Retry。

### 規則
- Conflict 後由 Client Refresh 並重新確認最新狀態。

### 驗收條件
- [ ] 兩個 Transaction 讀到相同 Version 時，不可都 Silent Update 成功。
- [ ] Stale Update 收到 Conflict 且 Quantity 不被破壞。

### 必要測試
- [ ] Real MySQL Concurrent Optimistic Lock Integration Test。

### Specification References
- `domain-model.md`
- `transaction-boundary.md`
- `validation-exception.md`
- `testing-strategy-merged.md`
- `non-functional-requirements.md`

---

# E5 — Recommendation

## SP-500 — 實作由 ORDERED PO 推導的 IncomingInventoryQuery

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-600
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
提供 Recommendation Module 讀取 Incoming Quantity 的邊界，避免 Circular Dependency。

### 本 Issue 範圍
- Recommendation Side 定義 / 使用 `IncomingInventoryQuery`。
- Procurement Adapter / Query 從 ORDERED PO Items 取得資料。
- 套用 `expectedDeliveryDate <= demandRequiredDate`。

### 不在本 Issue 範圍
- DRAFT / APPROVED / REJECTED / RECEIVED 計入 Incoming。

### 規則
- Cross-module Read 可用 Query Interface；Recommendation 不得寫 Procurement Data。

### 驗收條件
- [ ] Required Date 前可到貨的 ORDERED Quantity 計入 Incoming。
- [ ] 其他 Status 或到貨太晚皆不計入。

### 必要測試
- [ ] 涵蓋所有 PO Status + Date Boundary 的 Integration / Repository Test。

### Specification References
- `functional-requirements.md`
- `package-architecture.md`
- `testing-strategy-merged.md`
- `acceptance-criteria.md`

---

## SP-501 — 實作確定性的 Net Requirement 與 Purchase Quantity Calculator

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-301, SP-400
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
將核心採購數學完全放在 Backend Deterministic Logic，而非 AI。

### 本 Issue 範圍
- `NetRequirementCalculator`。
- Shortage Outcome。
- 依 Supplier MOQ / Package Multiple 計算 `PurchaseQuantityCalculator`。
- 使用 BigDecimal 計算 Total Purchase Cost。

### 不在本 Issue 範圍
- AI Calculation、Dynamic Safety Stock、Unit Conversion。

### 規則
- Net Requirement = `max(0, Demand + Safety Stock - Current Inventory - Incoming Inventory)`。
- Calculator 必須 deterministic 且容易 Unit Test。
- Money / Quantity 依規格使用 BigDecimal。

### 驗收條件
- [ ] Demand 100 + Safety 20 - Inventory 30 - Incoming 10 = 80。
- [ ] Raw Result < 0 時 = 0。
- [ ] MOQ / Package Multiple 案例符合規格。

### 必要測試
- [ ] Normal / Zero / Negative / MOQ / Package Multiple / No Package Unit Tests。

### Specification References
- `functional-requirements.md`
- `testing-strategy-merged.md`
- `coding-standards.md`

---

## SP-502 — 實作 Supplier Hard Constraint Evaluation

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-220, SP-501
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
在 Supplier Ranking 前先依規格 Hard Constraints 篩掉不合格 Candidate。

### 本 Issue 範圍
- `SupplierConstraintEvaluator`。
- 排除 INACTIVE Supplier。
- 排除 INACTIVE SupplierMaterial。
- 依 Lead Time 排除無法在 Required Date 前交貨者。
- 回傳規格定義的 Disqualification Reason。

### 不在本 Issue 範圍
- LLM Supplier Qualification。
- 規格之外的新 Hard Constraint。

### 規則
- Hard Constraint 必須先於 Ranking。
- `DELIVERY_DATE_NOT_FEASIBLE` 是 Disqualification Reason，不是 General API Exception。

### 驗收條件
- [ ] INACTIVE Supplier 被排除。
- [ ] INACTIVE SupplierMaterial 被排除。
- [ ] Lead Time 不可行者以正確 Reason 被排除。

### 必要測試
- [ ] 每一項 Hard Constraint Unit Test。

### Specification References
- `functional-requirements.md`
- `testing-strategy-merged.md`
- `validation-exception.md`
- `cross-document-consistency-review.md`

---

## SP-503 — 實作 Supplier Score Ranking 與 Deterministic Tie-break

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-502
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
使用規格定義的 deterministic formula 排序 Qualified Supplier Candidates。

### 本 Issue 範圍
- Price / Lead Time / Reliability / Quality Normalization / Scoring。
- 權重：Price 40%、Lead Time 30%、Reliability 20%、Quality 10%。
- 實作規格定義的 Tie-break Order。
- Stable Rank。

### 不在本 Issue 範圍
- LLM Ranking、ML Ranking、未規格化的 Dynamic Weight。

### 規則
- 只有通過 Hard Constraint 的 Candidate 才進 Ranking。
- Ranking Input 後續必須寫入 Snapshot。

### 驗收條件
- [ ] Baseline Suppliers 產生 Testing Strategy 定義的固定排序。
- [ ] Tie Case 可 deterministic 解決。

### 必要測試
- [ ] Ranking Unit Tests，包含 Tie / Edge Values。

### Specification References
- `functional-requirements.md`
- `testing-strategy-merged.md`
- `to-be-procurement-flow.md`

---

## SP-504 — 實作 PurchaseRecommendation Snapshot API 與 Outcomes

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Transaction
- **Dependencies：** SP-500, SP-501, SP-502, SP-503
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:transaction`, `complexity:hard`

### 目標
建立包含計算輸入與 Supplier Result 的 Persisted Recommendation Snapshot。

### 本 Issue 範圍
- PurchaseRecommendation + SupplierRecommendationResult Persistence / Service / API。
- POST Recommendation Creation + GET Query。
- Outcomes：`PURCHASE_NOT_REQUIRED` / `SUPPLIER_RECOMMENDED` / `NO_QUALIFIED_SUPPLIER`。
- Snapshot：Demand / Safety / Inventory / Incoming / Net Requirement，以及 Supplier Price / Lead / Reliability / Quality / Score / Rank。
- Recommendation + 所有 Result Atomic Persistence。

### 不在本 Issue 範圍
- 將 `NO_QUALIFIED_SUPPLIER` 當 Exception。
- Master Data 改變後重寫舊 Snapshot。

### 規則
- Recommendation 是 Historical Snapshot。
- AI 不計算數字或 Ranking。
- No Qualified Supplier 是正常 Outcome。

### 驗收條件
- [ ] Net Requirement = 0 時 Persist `PURCHASE_NOT_REQUIRED`。
- [ ] 有合格 Supplier 時 Persist `SUPPLIER_RECOMMENDED` + Ranking。
- [ ] 無 Candidate 時 Persist `NO_QUALIFIED_SUPPLIER`。
- [ ] 任一 Candidate Persistence Failure → Entire Recommendation Rollback。

### 必要測試
- [ ] 三種 Outcome Service Tests。
- [ ] Snapshot Atomicity Integration Test。
- [ ] Master Data 改變後 Snapshot Immutability Test。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `transaction-boundary.md`
- `acceptance-criteria.md`
- `testing-strategy-merged.md`

---

# E6 — Procurement

## SP-600 — 實作 Purchase Order Draft Aggregate 與建立 API

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Transaction
- **Dependencies：** SP-200, SP-210, SP-220, SP-102
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:transaction`

### 目標
原子建立單一 Supplier、1 個以上 Items 的有效 DRAFT Purchase Order。

### 本 Issue 範圍
- PurchaseOrder + PurchaseOrderItem Aggregate / Repository / Service / DTO / API。
- Collision-safe Display PO Number。
- Multi-item Draft。
- Supplier / Material / Relationship ACTIVE Validation。
- Quantity / Price Validation。
- 同一 PO 不允許 Duplicate Material。
- PO + Items Atomic Persistence。
- createdBy 由 CurrentUserProvider 取得。

### 不在本 Issue 範圍
- Multi-Supplier PO、Partial Receiving、PO Cancellation、Multi-level Approval。

### 規則
- 一張 PO 僅一個 Supplier。
- Client 不得控制 Status / Audit / Snapshot Fields。
- PO Number 不得用不安全的「今日最大值 + 1」。

### 驗收條件
- [ ] Procurement Staff / ADMIN 可建立有效 DRAFT。
- [ ] Multi-item 同一 PO 成功 Persist。
- [ ] Duplicate Material 被拒絕。
- [ ] 任一 Item Failure → Entire PO Rollback。

### 必要測試
- [ ] PurchaseOrder Domain / Service Tests。
- [ ] PO + Items Transaction Test。
- [ ] PO Number Uniqueness Test。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `transaction-boundary.md`
- `testing-strategy-merged.md`

---

## SP-601 — 實作 Recommendation-based Draft 與人工 Override 紀錄

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-504, SP-600
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
讓 Recommendation 可作為 PO Draft 起點，同時保留 Human Override Evidence。

### 本 Issue 範圍
- Validate Recommendation 與 Selected SupplierRecommendationResult Reference。
- 依 Model 複製必要 Recommendation Reference / Snapshot Links。
- 支援 Supplier Override / Quantity Override。
- Recommendation 被改動時依規格要求 Override Reason。
- Persist Override Reason / Reference。

### 不在本 Issue 範圍
- Recommendation / AI 自動 Approve / Order。

### 規則
- Human 可修改或拒絕 Recommendation。
- Supplier / Quantity 與 Recommendation 不同時，依規格要求 Override Reason。

### 驗收條件
- [ ] 可用推薦 Supplier / Quantity 建立 Draft。
- [ ] 需 Reason 的 Override 缺少 Reason 時拒絕。
- [ ] Override Reason 可查詢且保留。

### 必要測試
- [ ] PurchaseOrderService Override Tests。
- [ ] Recommendation Override Acceptance Test。

### Specification References
- `functional-requirements.md`
- `api.md`
- `validation-exception.md`
- `acceptance-criteria.md`

---

## SP-602 — 實作 DRAFT 修改規則與 Aggregate Ownership

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-600
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
在不直接暴露 Child Mutation 的前提下，支援允許的 Draft Editing。

### 本 Issue 範圍
- PATCH / Update 規格允許的 DRAFT Fields / Items。
- PurchaseOrderItem 僅能經由 PurchaseOrder Owning Service / Aggregate 修改。
- Edit 時重新驗證 Lifecycle / Item Rules。

### 不在本 Issue 範圍
- 修改 APPROVED / ORDERED / RECEIVED Business Data。
- Direct Child Repository Write API。

### 驗收條件
- [ ] 合法 DRAFT 修改成功。
- [ ] Protected State 修改被 Lifecycle Error 拒絕。

### 必要測試
- [ ] PO Domain / Service Update Lifecycle Tests。

### Specification References
- `domain-model.md`
- `api.md`
- `package-architecture.md`
- `functional-requirements.md`

---

## SP-610 — 實作 PO Approve / Reject Lifecycle Action

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Security
- **Dependencies：** SP-602, SP-102
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:security`

### 目標
實作由 Manager 控制的 DRAFT Approval / Rejection Transition。

### 本 Issue 範圍
- Approve Action。
- Reject Action + Required Reject Reason。
- approvedBy / rejected metadata 來自 Authenticated User。
- REJECTED Terminal State。
- Method Security。

### 不在本 Issue 範圍
- Multi-level Approval、Approval Matrix、Client-set Status。

### 規則
- Lifecycle 只能透過合法 Domain Method Transition。
- ADMIN 也不可繞過 Lifecycle。

### 驗收條件
- [ ] Authorized Manager：DRAFT → APPROVED。
- [ ] DRAFT → REJECTED 必須有 Reason。
- [ ] Illegal Transition 回規格 Conflict / Error。
- [ ] REJECTED 不可 Order。

### 必要測試
- [ ] PO Domain State Machine。
- [ ] Approve / Reject Service Tests。
- [ ] Role Security Tests。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `security-design.md`
- `acceptance-criteria.md`

---

## SP-611 — 實作 Mark Ordered、Expected Delivery、Open / Overdue Query

- **Priority：** P0
- **Complexity：** Normal
- **Dependencies：** SP-610
- **Labels：** `area:backend`, `type:feature`, `priority:p0`

### 目標
完成正式下單與收貨前的 Delivery Tracking。

### 本 Issue 範圍
- APPROVED → ORDERED Action。
- Order 前驗證 ExpectedDeliveryDate 等 Required Fields。
- orderedBy 由 Current User 取得。
- Open / ORDERED PO Query。
- Overdue 計算 / Query，但不新增 OVERDUE DB Status。

### 不在本 Issue 範圍
- Supplier Portal、自動下單、Persisted OVERDUE Status。

### 規則
- 只有 APPROVED 可以 ORDERED。
- OVERDUE = `status=ORDERED && today > expectedDeliveryDate`。

### 驗收條件
- [ ] Authorized Procurement Staff 可將 Valid APPROVED PO 標記為 ORDERED。
- [ ] 缺 Required Ordering Fields 時拒絕。
- [ ] Open / Overdue Query 正確，Stored Status 仍為 ORDERED。

### 必要測試
- [ ] Mark Ordered Lifecycle / Service Tests。
- [ ] Fixed Clock Overdue Query Test。

### Specification References
- `functional-requirements.md`
- `domain-model.md`
- `api.md`
- `acceptance-criteria.md`

---

## SP-612 — 實作完整 PO Receiving Transaction

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Transaction
- **Dependencies：** SP-611, SP-401, SP-412
- **Labels：** `area:backend`, `type:feature`, `priority:p0`, `risk:transaction`, `complexity:hard`

### 目標
讓 ORDERED PO 只能完整收貨一次，並原子更新 Inventory、StockTransaction 與 PO Status。

### 本 Issue 範圍
- ReceivingService + Receive Action Endpoint。
- 驗證 PO=ORDERED + V1 Full Receive。
- 每個 Item 增加對應 Inventory。
- 每個 Item 建立 `PURCHASE_RECEIVE` StockTransaction。
- 同一 Transaction 內 PO → RECEIVED。
- receivedBy 來自 Current User。

### 不在本 Issue 範圍
- Partial Receiving。
- Service 外直接修改 Inventory。

### 規則
- DB Transaction 中不得呼叫 External / AI Service。
- 所有 Inventory Update + StockTransaction + PO Status Atomic。

### 驗收條件
- [ ] ORDERED PO 收貨成功：Inventory 各增加一次、StockTransaction 建立、PO=RECEIVED。
- [ ] 任一 Item / StockTransaction 失敗 → 全部 Rollback。
- [ ] 非 ORDERED PO 被拒絕。

### 必要測試
- [ ] Receiving Happy Path Integration Test。
- [ ] Later Item Failure Rollback Integration Test。
- [ ] WAREHOUSE_STAFF / ADMIN Security Test。

### Specification References
- `transaction-boundary.md`
- `functional-requirements.md`
- `acceptance-criteria.md`
- `testing-strategy-merged.md`
- `package-architecture.md`

---

## SP-613 — 實作 PurchaseOrder Optimistic Lock 與 Concurrent Receive Guard

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Concurrency
- **Dependencies：** SP-612, SP-002
- **Labels：** `area:backend`, `type:test`, `priority:p0`, `risk:concurrency`, `complexity:hard`

### 目標
防止 Duplicate / Concurrent Receiving 與 Silent PO Lifecycle Race。

### 本 Issue 範圍
- PurchaseOrder 使用 `@Version`。
- Stale Update → 409 `CONCURRENT_MODIFICATION`。
- 保留 Domain Duplicate Receive Guard。

### 不在本 Issue 範圍
- Idempotency-Key、Distributed Lock。

### 規則
- V1 使用 Lifecycle Guard + Optimistic Lock，不導入進階 Idempotency Framework。

### 驗收條件
- [ ] 兩個 Concurrent Receive 僅一個成功。
- [ ] Inventory 只增加一次。
- [ ] 僅存在一組 StockTransactions。
- [ ] 第二個 Request 收到 Lifecycle / Concurrency Conflict。

### 必要測試
- [ ] Real MySQL Concurrent Receive Integration Test。

### Specification References
- `functional-requirements.md`
- `transaction-boundary.md`
- `testing-strategy-merged.md`
- `acceptance-criteria.md`

---

# E7 — Document / RAG / AI

## SP-700 — 實作 DocumentMetadata Upload 與 Lifecycle

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** File Safety
- **Dependencies：** SP-001, SP-002, SP-102
- **Labels：** `area:backend`, `area:ai`, `type:feature`, `priority:p0`

### 目標
建立安全的 Document Upload / Metadata Lifecycle，作為 RAG 基礎。

### 本 Issue 範圍
- DocumentMetadata Entity / Repository / Service / API。
- Upload / List / Get / Update Metadata。
- Allowed Document Types / Formats / Size Limit。
- Server-side Storage Filename + Path Traversal Protection。
- Local / Demo 使用 Local Filesystem `DocumentStorageService` Boundary。
- Status：PROCESSING / READY / FAILED / INACTIVE。
- Supplier / Type / Version / uploadedBy Metadata。

### 不在本 Issue 範圍
- 除非 Deployment Config 明確啟用，否則不做 S3。
- Arbitrary File Types。

### 規則
- DB 與 External File Storage 不是同一 Atomic Transaction，依規格 Compensation / Lifecycle Strategy 處理。
- 不信任 Original Path。

### 驗收條件
- [ ] Authorized Procurement Staff / ADMIN 可上傳 Supported Document。
- [ ] Unsupported / Oversized / Unsafe File 被拒絕。
- [ ] Metadata 正確反映 Success / Failure 並保留 Audit User。

### 必要測試
- [ ] File Validation Tests。
- [ ] Document Service Lifecycle Test。

### Specification References
- `domain-model.md`
- `api.md`
- `transaction-boundary.md`
- `non-functional-requirements.md`
- `environment-configuration.md`

---

## SP-710 — 實作 RAG Ingestion 與 Evidence-grounded Document Query

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** AI
- **Dependencies：** SP-700
- **Labels：** `area:ai`, `type:feature`, `priority:p0`, `complexity:hard`

### 目標
針對採購文件進行有 Source Evidence 的回答，並與即時交易數字分離。

### 本 Issue 範圍
- Parse V1 所需 Supported READY Documents。
- Chunk / Embed / Store Retrieval Representation。
- Query Relevant Chunks。
- 回 `Answer + Source / Version Metadata`。
- Evidence 不足時回 Application Result：`INSUFFICIENT_EVIDENCE`。

### 不在本 Issue 範圍
- 使用 RAG 當 Inventory / PO Numeric Truth。
- 將 Hallucination 當正式企業資料。
- Multi-Agent RAG。

### 規則
- RAG 只處理 Unstructured Documents。
- Insufficient Evidence 是 Application Result，不是 Provider Failure。
- Automated Integration Test 不呼叫付費 Provider。

### 驗收條件
- [ ] Known Question 回答含 Source。
- [ ] Evidence 不足回 `INSUFFICIENT_EVIDENCE`，不可 Fabricate Fact。
- [ ] Provider Failure 與 Insufficient Evidence 可區分。

### 必要測試
- [ ] 使用 Deterministic Fixture / Fake Provider 的 Retrieval / Evidence Tests。
- [ ] Insufficient Evidence Test。

### Specification References
- `functional-requirements.md`
- `validation-exception.md`
- `testing-strategy-merged.md`
- `environment-configuration.md`

---

## SP-720 — 實作 AI Assistant Query 與 Read-only Procurement Tools

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** AI / Security
- **Dependencies：** SP-504, SP-611, SP-710, SP-102
- **Labels：** `area:ai`, `type:feature`, `priority:p0`, `risk:security`, `complexity:hard`

### 目標
讓 AI 透過安全的 Application Service Tools 查詢與解釋既有採購資料。

### 本 Issue 範圍
- `POST /api/v1/ai/query`。
- Recommendation Explanation、Inventory Query、Supplier / Recommendation Data、Open PO Query、Document Search Tools。
- AI Tool 呼叫 Application Service / Query Boundary，不直接寫 Repository。
- Tool Execution 沿用 Current User Authorization。

### Spring AI 2.0.x 正式技術要求（2026-10-11 增補）
- 使用支援 Spring Boot 4.1.x 的 Spring AI 2.0.x 穩定版（不使用 2.1 Milestone）；透過 ChatClient / ChatModel 串接至少一個可設定的真實 LLM Provider。
- 使用 @Tool 或 ToolCallback 提供明確唯讀 Allowlist：Recommendation、Supplier Ranking、Inventory、Open PO、SP-710 Document Query。所有 Tool 呼叫既有 Application / Query Service，禁止 Repository Write、System Admin Identity、任意 Tool／Bean／SQL 執行。
- 以登入使用者 SecurityContext / Method Security 執行工具，保留 SP-710 的文件來源與證據不足契約；不得改寫核心採購數字。
- Provider 設定與 API Key 不得寫入 Repo；缺少 AI 設定時核心 API 仍可運作。
- 自動化測試必須使用 Fake ChatModel／Fake Tool Callback，不得呼叫付費 Provider。

### Spring AI 新增驗收與測試
- [ ] 已設定時可由 ChatClient 使用真實 LLM Provider。
- [ ] Tool Calling 只會使用唯讀 Allowlist 並呼叫既有 Service。
- [ ] User Authorization 與 Forbidden Tool / Prompt Injection 不可繞過。
- [ ] Provider Failure / INSUFFICIENT_EVIDENCE（依既有契約）清楚分流；核心 API 不受影響。
- [ ] Fake ChatModel 的 Tool Integration、Role、Provider Failure、核心 Regression Tests 通過。

### 不在本 Issue 範圍
- Approve / Reject / Order / Receive / Adjust Inventory / User Admin / Master Data Write。
- AI 執行 Deterministic Procurement Calculation。
- AI System Admin Identity。

### 規則
- AI 不在 Core Procurement Critical Path。
- AI Failure 不可阻塞 Core Backend Flow。

### 驗收條件
- [ ] Authorized Query 可使用 Stored Deterministic Result 解釋 Recommendation。
- [ ] AI 可透過 Service 查 Open PO / Inventory。
- [ ] Forbidden Action Tool 不存在或被 Block。
- [ ] Provider Unavailable 依規格回 AI Error，但 Core API 正常。

### 必要測試
- [ ] Allowed Tool Tests。
- [ ] Forbidden Tool Boundary Tests。
- [ ] Tool Must Call Service Test。
- [ ] Provider Failure Mapping Test。

### Specification References
- `functional-requirements.md`
- `security-design.md`
- `package-architecture.md`
- `testing-strategy-merged.md`
- `to-be-procurement-flow.md`

---

## SP-721 — 實作 AI 輔助 PO Draft 建立與明確 User Confirmation

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** AI / Security
- **Dependencies：** SP-601, SP-720
- **Labels：** `area:ai`, `area:backend`, `type:feature`, `priority:p0`, `risk:security`

### 目標
允許 AI 提議並建立 **DRAFT only**，且必須透過相同 PurchaseOrderService 與 Current User Permission。

### 本 Issue 範圍
- AI Draft Proposal / Tool Schema。
- Client 明確 Confirm 後才呼叫 `createDraft`。
- Reuse PurchaseOrderService + 所有 Validation / Authorization。
- 保留 Recommendation / Override Rules。

### Spring AI 沿用要求（2026-10-11 增補）
- 重用 SP-720 的 Spring AI 2.0.x ChatClient / Tool Calling，不另建 Provider Framework。
- 使用受 Schema 驗證的 Draft Proposal，必須由 Backend 明確驗證 User Confirmation 才能呼叫既有 PurchaseOrderService.createDraft()；Prompt Injection 或 LLM 自述不構成 Confirm。
- 只能建立 DRAFT，禁止 Approve／Reject／Order／Receive。
- [ ] 測試 Proposal、No Confirmation／No Write、權限、Prompt Injection 與合法確認後只建立 DRAFT，使用 Fake ChatModel，不呼叫付費 Provider。

### 不在本 Issue 範圍
- AI Approve / Reject / Order / Receive。
- AI Tool Direct Repository Save。

### 規則
- AI 只能建立 DRAFT。
- Write Action 前必須 User Confirmation。
- AI 繼承 Current User Authorization。

### 驗收條件
- [ ] Confirmed Valid AI Draft 透過 Service 建立正常 DRAFT。
- [ ] Unconfirmed Proposal 不產生 Write。
- [ ] 無 Draft 權限的 User 不能透過 AI 繞過權限。

### 必要測試
- [ ] AI Draft Success Test。
- [ ] No Confirmation / No Write Test。
- [ ] Role Security Test。

### Specification References
- `functional-requirements.md`
- `security-design.md`
- `frontend-design.md`
- `acceptance-criteria.md`

---

# E8 — Thin Frontend

## SP-800 — 實作 Frontend Authentication、Session 與 Role Guards

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-004, SP-101, SP-102
- **Labels：** `area:frontend`, `type:feature`, `priority:p1`

### 目標
讓 Thin Frontend 對 Backend Authentication，並正確控制 Route / Action Visibility。

### 本 Issue 範圍
- Login Page。
- AuthContext / Session State。
- Axios Authorization Header / Interceptor。
- RequireAuth / RoleGuard。
- 401 Logout / Re-auth Flow。
- 403 Readable Error。

### 不在本 Issue 範圍
- Refresh Token Workflow。
- 將 Frontend Visibility 當成真正 Security Boundary。

### 驗收條件
- [ ] Valid Login 可進入 App。
- [ ] Unauthenticated Route 被 Redirect / Block。
- [ ] Role-restricted Route / Action Hide / Disable，Backend 仍負責最終授權。

### 必要測試
- [ ] Login Flow。
- [ ] Role Route / Button Visibility。

### Specification References
- `frontend-design.md`
- `security-design.md`
- `acceptance-criteria.md`

---

## SP-801 — 實作 Material / Supplier / SupplierMaterial Admin Pages

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-800, SP-200, SP-210, SP-220
- **Labels：** `area:frontend`, `type:feature`, `priority:p1`

### 目標
提供足以操作與 Demo 下游流程的 Master Data UI。

### 本 Issue 範圍
- Material List / Create / Edit / Status。
- Supplier List / Create / Edit / Status。
- SupplierMaterial List / Create / Edit / Status。
- Basic Validation / Loading / Empty / Error States。

### 不在本 Issue 範圍
- Custom Design System、Advanced Grid / Editor。

### 規則
- 不在 Frontend 重複實作 Cross-Entity Business Rules。

### 驗收條件
- [ ] ADMIN 可從 UI 完成支援的 Master Data 操作。
- [ ] Backend Error 可讀地顯示。

### 必要測試
- [ ] 視價值加入 Targeted Component / Form Tests。

### Specification References
- `frontend-design.md`
- `api.md`
- `functional-requirements.md`

---

## SP-802 — 實作 Demand 與 Inventory 操作頁面

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-800, SP-300, SP-400, SP-401
- **Labels：** `area:frontend`, `type:feature`, `priority:p1`

### 目標
將 Demand Input、Inventory Visibility 與 Manual Adjustment 納入 Demo Flow。

### 本 Issue 範圍
- Demand List / Create / Edit / Cancel + Role-aware Action。
- Inventory List / Detail。
- Allowed Role 的 Manual Adjustment Dialog。
- Loading / Empty / Error States。

### 不在本 Issue 範圍
- Forecast UI、Direct Inventory Edit。

### 驗收條件
- [ ] Planner 可 Create / Cancel Demand。
- [ ] Warehouse 可輸入 Reason 調整 Inventory。
- [ ] Unauthorized Action 不顯示。

### 必要測試
- [ ] 適用時加入 Critical Role / Action Visibility Test。

### Specification References
- `frontend-design.md`
- `api.md`
- `functional-requirements.md`

---

## SP-803 — 實作 Inventory Import Validate / Preview / Confirm UI

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-800, SP-410, SP-411
- **Labels：** `area:frontend`, `type:feature`, `priority:p1`

### 目標
讓兩階段 CSV Import 在 Demo 中清楚、安全且可理解。

### 本 Issue 範圍
- CSV Upload。
- 顯示 Validate Response Counts + Row Errors。
- 只有 VALIDATED Batch 才可 Confirm。
- 顯示 Import Success Result。
- Validation Failed 時 Inventory View 不變。

### 不在本 Issue 範圍
- Excel Import、Background Import Job。

### 驗收條件
- [ ] Valid File 可 Validate → Preview → Confirm。
- [ ] Invalid Rows 顯示 Row / Code / Message。
- [ ] VALIDATION_FAILED 不可 Confirm。

### 必要測試
- [ ] Inventory Import Preview Core Test。

### Specification References
- `frontend-design.md`
- `api.md`
- `acceptance-criteria.md`

---

## SP-804 — 實作 Recommendation 與 Supplier Ranking UI

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-800, SP-504
- **Labels：** `area:frontend`, `type:feature`, `priority:p1`

### 目標
把 Backend Decision Logic 清楚視覺化，供作品集展示。

### 本 Issue 範圍
- Create / Query Recommendation UI。
- Demand / Safety / Current / Incoming / Net Requirement / Outcome Summary。
- Supplier Ranking Table + Disqualification Reasons。
- Recommendation → Create Draft Flow。

### 不在本 Issue 範圍
- Browser 端重新計算 Ranking。
- AI 作為 Numeric Recommendation Source。

### 規則
- Frontend 只展示 Backend Result，不重做 Core Formula。

### 驗收條件
- [ ] `PURCHASE_NOT_REQUIRED`、`SUPPLIER_RECOMMENDED`、`NO_QUALIFIED_SUPPLIER` 正確 Render。
- [ ] Ranking / Snapshot 資訊容易理解。

### 必要測試
- [ ] Recommendation Render Test。

### Specification References
- `frontend-design.md`
- `acceptance-criteria.md`
- `functional-requirements.md`

---

## SP-805 — 實作 Purchase Order Lifecycle、Receiving 與 Stock UI

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-800, SP-610, SP-611, SP-612
- **Labels：** `area:frontend`, `type:feature`, `priority:p1`

### 目標
在 Thin UI 中提供完整 Human-controlled PO Lifecycle。

### 本 Issue 範圍
- PO List / Detail。
- Demo 所需 DRAFT Create / Edit。
- Approve / Reject + Role Rule。
- Mark Ordered Dialog / Expected Delivery Date。
- Overdue Badge。
- Receive Confirmation。
- StockTransaction Display。

### 不在本 Issue 範圍
- Partial Receiving、Client `PATCH status`。

### 規則
- 高風險 Action 必須有明確 User Action / Confirmation。

### 驗收條件
- [ ] 正確 Role 可操作 DRAFT → APPROVED → ORDERED → RECEIVED Happy Path。
- [ ] Reject 必須輸入 Reason。
- [ ] Overdue Display 不修改 Stored Status。

### 必要測試
- [ ] PO Lifecycle Action Visibility Core Test。

### Specification References
- `frontend-design.md`
- `functional-requirements.md`
- `acceptance-criteria.md`

---

## SP-806 — 實作 Document 與 AI Assistant UI

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-800, SP-700, SP-720, SP-721
- **Labels：** `area:frontend`, `area:ai`, `type:feature`, `priority:p1`

### 目標
提供小型 Document / AI UI，不讓 Frontend 搶走專案重心。

### 本 Issue 範圍
- Document Upload / List / Status / Type / Version / Supplier Metadata UI。
- AI Assistant Page / Drawer。
- Recommendation Explanation + Source / Recommendation ID。
- RAG Source / Version。
- AI Draft Proposal，Write 前 Explicit Confirmation。

### 不在本 Issue 範圍
- AI Approval / Order / Receive / Adjustment Controls。
- Complex Chat Workspace。

### 驗收條件
- [ ] Document State 可視。
- [ ] RAG Answer 顯示 Source。
- [ ] AI Draft 必須明確 Confirm。
- [ ] AI Outage 不影響正常 Navigation。

### 必要測試
- [ ] 適用時加入 Confirmation / Boundary UI Test。

### Specification References
- `frontend-design.md`
- `acceptance-criteria.md`
- `security-design.md`

---

## SP-807 — 實作 Frontend Error、Loading 與 Concurrency UX 基線

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-800, SP-803, SP-805
- **Labels：** `area:frontend`, `type:test`, `priority:p1`

### 目標
讓 Non-Happy Path 在 Demo 中安全且容易理解。

### 本 Issue 範圍
- Reusable Business Error Alert。
- 主要頁面 Loading / Empty / Error / Success。
- 409 `CONCURRENT_MODIFICATION`：Warning → Refetch / Invalidate → User Review。
- 500 Generic Error + traceId。
- 503 AI Unavailable Message。

### 不在本 Issue 範圍
- High-risk Write Auto Retry。

### 驗收條件
- [ ] 409 不會 Silent Retry Receive / Approve / Order。
- [ ] 500 顯示 Safe Generic Error + traceId。
- [ ] AI Outage 時 Core App 仍可用。

### 必要測試
- [ ] 409 Handling Test。
- [ ] Core Error State Tests。

### Specification References
- `frontend-design.md`
- `non-functional-requirements.md`
- `acceptance-criteria.md`

---

# E9 — Quality / Release

## SP-900 — 完成 P0 Backend Integration / Security Test Gate

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Quality
- **Dependencies：** SP-003, SP-412, SP-504, SP-613, SP-720
- **Labels：** `area:backend`, `type:test`, `priority:p0`, `complexity:hard`

### 目標
完成作品集可信度所需的完整 P0 Test Matrix。

### 本 Issue 範圍
- Audit Testing Strategy 全部 P0 Tests。
- 補齊 Domain / Calculator / Service / Repository / Security / Transaction / Concurrency / AI Boundary Test 缺口。
- Integration Test 使用 Testcontainers MySQL。
- Date-sensitive Rule 使用 Fixed Clock。

### 不在本 Issue 範圍
- 追求 100% Coverage。
- 只為 Coverage Percentage 補 P2 Test。

### 規則
- 重點保護 Core Business Rules、Atomicity、Security Boundary、Concurrency Correctness。

### 驗收條件
- [ ] 所有 P0 Tests Local 全綠。
- [ ] Automated Integration Test 不呼叫付費 AI API。
- [ ] Receiving / Import / Recommendation Rollback + Concurrency Scenarios 全綠。

### 必要測試
- [ ] 本 Issue 本身即為 P0 Test Gate。

### Specification References
- `testing-strategy-merged.md`
- `acceptance-criteria.md`
- `non-functional-requirements.md`

---

## SP-901 — 建立 Deterministic Demo Seed 與 End-to-End Business Scenario

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-504, SP-612, SP-700, SP-800
- **Labels：** `area:backend`, `area:frontend`, `type:test`, `priority:p1`

### 目標
以虛構資料建立可重複的 Portfolio Demo。

### 本 Issue 範圍
- `demo` profile 專用 DemoDataInitializer。
- Testing Strategy 中的 Baseline Fictional Users / Materials / Suppliers / SupplierMaterial。
- Demo POs 涵蓋主要 Status。
- Sample CSV + Demo Documents。
- Standard Recommendation Scenario + Complete Happy Path。

### 不在本 Issue 範圍
- 真實公司 / Confidential Data。
- Production Seeding。

### 規則
- Prod 禁用 Demo Seed。
- Demo Data deterministic 且 fictional。

### 驗收條件
- [ ] Fresh Demo Environment 可重現 Recommendation → Draft → Approve → Order → Receive。
- [ ] Production Profile 永遠不執行 Demo Seeder。

### 必要測試
- [ ] Demo Profile Seed Smoke Test。
- [ ] Manual / Automated E2E Checklist。

### Specification References
- `testing-strategy-merged.md`
- `environment-configuration.md`
- `frontend-design.md`
- `pre-implementation-checklist.md`

---

## SP-902 — 建立 Backend / Frontend CI Merge Gate

- **Priority：** P1
- **Complexity：** Normal
- **Dependencies：** SP-003, SP-004
- **Labels：** `area:infra`, `type:setup`, `priority:p1`

### 目標
自動化 Portfolio PR Merge 前最低必要檢查。

### 本 Issue 範圍
- GitHub Actions 或等效 CI。
- Backend Compile / Unit / Integration Layer。
- Frontend Build / Test。
- Test / Build Failure 必須讓 CI Red。

### 不在本 Issue 範圍
- Complex Deployment Pipeline。
- Multi-environment Promotion Workflow。

### 規則
- CI Command 盡可能與 Local Command 一致。

### 驗收條件
- [ ] Clean PR 自動執行檢查。
- [ ] 故意破壞 Build / Test 時 CI Red。

### 必要測試
- [ ] CI Workflow Validation。

### Specification References
- `testing-strategy-merged.md`
- `SupplyPilot_專案整體方向與規劃總綱.md`

---

## SP-903 — Containerize Release Candidate 並驗證 Production Configuration

- **Priority：** P1
- **Complexity：** Hard
- **Risk：** Deployment
- **Dependencies：** SP-001, SP-900, SP-807
- **Labels：** `area:infra`, `type:setup`, `priority:p1`

### 目標
產出可部署的 Portfolio Release Candidate，但不擅自發明規格未定義的 Cloud Architecture。

### 本 Issue 範圍
- Backend Production Docker Image。
- Frontend Production Build / Container / Static Artifact，依實際方案處理。
- 驗證 Prod Env、CORS Exact Origin、Flyway / ddl validate、Demo Seeder Disabled。
- Health Endpoint Smoke Check。
- 紀錄 Deployment Prerequisites 與尚未決定的 Provider-specific Items。

### 不在本 Issue 範圍
- 自行發明 19 份 Specification 未定義的 AWS Topology。
- HA / Multi-AZ / Microservices。

### 規則
- Deployment Design 未定義的地方必須記錄 Gap，不得自行猜測。

### 驗收條件
- [ ] Production Artifacts 成功 Build。
- [ ] Valid Production-like Config 可啟動 App。
- [ ] Image 不包含 Secret / Demo Seed。
- [ ] Health Endpoint 正常。

### 必要測試
- [ ] Container Startup Smoke Test。

### Specification References
- `environment-configuration.md`
- `non-functional-requirements.md`
- `pre-implementation-checklist.md`
- `SupplyPilot_專案整體方向與規劃總綱.md`

---

## SP-904 — 執行最終 V1 Acceptance、文件與 Scope Freeze Release Review

- **Priority：** P0
- **Complexity：** Hard
- **Risk：** Release
- **Dependencies：** SP-900, SP-901, SP-902, SP-903
- **Labels：** `area:infra`, `type:test`, `priority:p0`

### 目標
依既有 Acceptance Criteria 與 Pre-Implementation / Release Principles 判斷 SupplyPilot V1 是否達到 Portfolio-ready。

### 本 Issue 範圍
- 執行完整 V1 Happy Path + Critical Failure Paths。
- 每項 Acceptance Criteria 必須有 Implementation / Test Evidence。
- 驗證 Role / Security Matrix。
- 確認 Explicit V1 Out-of-Scope 沒被偷加進來。
- README 更新：Business Problem、Architecture、Decisions、Demo Flow、Test Strategy、Known Limitations。
- Non-blocking 未完成項目建立 Future Ticket / ADR，不重畫 V1。

### 不在本 Issue 範圍
- Release Review 階段增加新 Feature。

### 規則
- Final Phase 只做 Validation / Docs / Bugfix，不擴 Scope。

### 驗收條件
- [ ] Core E2E Flow 可操作。
- [ ] P0 Test Gate 全綠。
- [ ] README 讓面試官能理解 Business Problem、Architecture、AI Boundary、Trade-offs。
- [ ] Known Limitations 明確列出。

### 必要測試
- [ ] Final Manual Acceptance Checklist + Automated Suite。

### Specification References
- `acceptance-criteria.md`
- `pre-implementation-checklist.md`
- `functional-requirements.md`
- `SupplyPilot_專案整體方向與規劃總綱.md`

---

# 8. V1 明確不做項目

除非 V1 Scope 正式變更，否則不要為以下內容建立實作 Issue：

- Multi-Warehouse / Multi-company。
- Multi-currency / FX Conversion。
- Unit Conversion Engine。
- Partial Receiving。
- PO Cancellation。
- Multi-level Approval / Dynamic Approval Matrix。
- Full RFQ / Quotation History / Supplier Portal。
- Full MRP / Complete Production Scheduling。
- 為展示技術而加入 Microservices / Event-driven Architecture。
- Automated Supplier Negotiation。
- AI Auto Approval / AI Auto Ordering / AI Receiving / AI Inventory Adjustment。
- Idempotency-Key Framework（V1 使用 Lifecycle Guard + Optimistic Lock）。
- Distributed Lock / Global SERIALIZABLE。
- Dynamic Safety Stock、Automatic Reliability Score、Automatic Quality Score。
- 僅為展示技術而加入 Redis / Elasticsearch，除非後續正式 Specification 將其升級為 V1 Requirement。

---

# 9. 建議實作順序（不是死板日曆）

1. **Foundation：** SP-001 → SP-004。
2. **Security / User：** SP-100 → SP-102。
3. **Master Data：** SP-200 → SP-220。
4. **Demand + Inventory：** SP-300 / 301 與 SP-400 / 401 / 410 / 411 / 412。
5. **Procurement Skeleton：** SP-600 → SP-602；先建立 PO Data Source，供 IncomingInventoryQuery 使用。
6. **Recommendation：** SP-500 → SP-504。
7. **PO Lifecycle / Receiving：** SP-610 → SP-613。
8. **Document / RAG / AI：** SP-700 → SP-721。
9. **Thin Frontend：** SP-800 → SP-807。
10. **Release Gate：** SP-900 → SP-904。

> 注意：SP-500 需要 PurchaseOrder Data Model / Query Source，因此實作上刻意排在基本 PO Draft Aggregate 之後。這只是 **Implementation Dependency**，不代表商業流程從「Recommendation → PO」被改變。

---

# 10. Issue 建立與維護規則

- 本檔案是用來建立 GitHub Issues 的 **Bootstrap / Source Backlog**。
- GitHub Issues 建立並 Review 完成後，本檔案 Freeze 為 Baseline。
- 後續 Execution Status、Clarification、Bug Discussion 都留在 GitHub Issues / PR。
- 發現 Spec Conflict 時建立 Clarification / ADR / Spec Correction Ticket，不可偷偷改行為。
- 不要維護兩份各自獨立演進的 Backlog。

---

# 11. 最終 V1 Release Test

Release Candidate 必須能完整展示：

```text
建立 Material / Supplier / SupplierMaterial
→ 建立 Demand
→ Validate + Confirm Inventory CSV
→ 計算 Net Requirement
→ 建立 Supplier Recommendation Snapshot
→ Human Review / 必要時 Override
→ 建立 PO Draft
→ Manager Approve
→ Procurement Mark Ordered
→ Warehouse Full Receive
→ Inventory Updated + StockTransaction Created
→ AI 可以查詢與解釋，但不能繞過 Authorization 或 High-risk Lifecycle
```

---

**Backlog Status：** V1 Implementation Baseline  
**語言：** 繁體中文（台灣用語）；程式識別字、API、Enum、Class、Label 保留原始英文。

---

## Confirmed SP-720/721/722 AI Decisions (2026-10-11)

- **Google Gemini** is the SP-720 LLM provider; prioritize a low-cost **Flash** model. Verify exact Chat Model ID, Tool Calling capability and price at implementation time; keep model ID configurable.
- Use stable **Spring AI 2.0.x** `ChatClient` / `ChatModel` and explicit Spring AI read-only Tool Calling under current-user Backend Authorization. AI-generated explanations require backend verification of structured authoritative figures.
- **SP-722 (#80)** is the dedicated Semantic RAG upgrade using **Google Gemini Embedding + PostgreSQL / pgvector**; this supersedes any earlier suggestion that MySQL / application-side cosine is the final semantic vector store. Existing **SP-710 MySQL lexical index remains transitional**; do not rewrite it inside SP-720.
- MySQL continues holding procurement transactions and document metadata; pgvector owns semantic document chunk/vector indexes. Cross-database updates are not atomic; SP-722 must design idempotent reindex, READY-status gating, and failure recovery.
- V1 AI requests are **single-turn, no chat memory, synchronous JSON**. Disable AI cleanly when configuration/provider unavailable (AI 503, core APIs unaffected).
- SP-721 requires **Proposal → server-issued Confirm Token/ID → Backend verification → DRAFT via existing Service**; never treat an LLM statement or simple client `confirmed=true` as sufficient.
- Basic cost / observability boundaries: token count when available, latency, error metrics, capped output/tool-call iterations, provider timeout and sensitive-data-redacted logs.
- Full decision record: `docs/adr/ADR-SP-720-spring-ai-adoption.md`; semantic work: GitHub Issue #80.

---

## SP-720 已確認的產品行為決策（2026-10-11；八項補充）

1. **單一模型**：Google Gemini，固定使用一個低成本 Flash 類 Chat Model；以可配置、經驗證的 model ID 部署；本 Issue 不實作多模型切換或自動 fallback 到旗艦模型。
2. **憑證與額度**：使用 Google AI Studio API Key，透過安全環境設定注入；啟用月度用量／預算觀察及超額警示設計。注意平台費用警示不等於應用端硬性費用上限；未取得可靠官方預算控制能力前不可宣稱已強制封頂。按可用 usage metadata 做估算與告警，不能取得時應明確標示無法精確計價。
3. **複合查詢**：單次問題可以由 Spring AI 選擇並依序呼叫多個*已核准唯讀* Tools；仍需 Tool Call 次數／迴圈數／Timeout 上限，所有 Tools 以目前使用者身份再次授權，不擴張成寫入工具。
4. **缺少必要識別資料**：不猜測 Material、Recommendation、Supplier 或 PO；回覆需要補充的欄位，不觸發未授權或任意猜測查詢。V1 沒有 Chat Memory，後續需重新提出完整問句。
5. **回答契約**：自然語言說明 + 可核對的 Backend 數值與來源。後端維持結構化欄位的權威，來源不由 LLM 自行產生；維持原 AI Query JSON contract，若須新增明確的補充資訊狀態，先以最小相容性調整實作並補 API 測試。
6. **模型回答不可信時安全降級**：當**已成功取得授權的、足夠且可信的 Backend Facts**，但 LLM 的文字含有矛盾／未被支援的數值或內容時，可改回由 Backend 產出的確定性 facts answer，且標明 fallback 來源；不能把模型不可信內容當成權威。**Provider unavailable／timeout／無 Key 應依原規格回 503，不能用 fallback 掩蓋；RAG INSUFFICIENT_EVIDENCE 必須維持證據不足，不得改寫為肯定答案。** 無可驗證 Backend Facts 時不得 fallback 成虛構答案。對 Provider invalid-response 的錯誤碼／fallback 邊界須在測試中明示。
7. **資料出站最小化**：只把完成特定查詢所必需的工具欄位與 RAG 相關片段傳送 Gemini；最小化 Supplier／Contract 等商業敏感資訊，嚴禁傳送 Credential、完整資料庫 Dump 或無關文件。Tool Result 應有欄位白名單／長度限制並避免記錄原始機敏 Payload。
8. **真實 Demo**：正式 Demo 使用真正的 Gemini Provider；未配置 Key 時 AI Query 回既有 503，但 Core API 正常可用。Fake ChatModel 僅供 Automated Tests，不能以 deterministic formatter 宣稱完成正式 AI Demo。

### 八項新增驗收／測試
- [ ] 真實 ChatClient + 單一 Gemini Flash 模型可由安全設定啟用；不依賴免費／付費配額推測。
- [ ] 同一查詢可呼叫兩個以上已核准唯讀 Tools，且受工具執行數量／輪數／時間上限限制；沒有寫入。
- [ ] 缺少必要識別資料時不猜測，回覆使用者需補充的資訊。
- [ ] 正常回答附可核對的結構化數值與來源；數值錯置／矛盾時安全 fallback 到 Backend Facts 或依明確條件失敗。
- [ ] Provider unavailable／timeout／未配置 Key 回 503；RAG 證據不足仍回 INSUFFICIENT_EVIDENCE。
- [ ] Gemini Request Payload 只含必要資料／相關文件片段，排除金鑰與無關商業資料。
- [ ] 能記錄／估算請求用量、延遲、失敗與預算警示，不能宣稱 Google AI Studio 原生具備未驗證的硬額度限制。
- [ ] Test 使用可控制的 Fake ChatModel，不呼叫真實付費 Provider；Demo 手動 Smoke Test 使用真實 Gemini。

