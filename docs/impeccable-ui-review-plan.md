# SupplyPilot — Impeccable 前端改善審查與執行計畫

> 狀態：**規格與改善 Backlog；尚未開始 UI-A/B/C/D 的新實作或瀏覽器實測**。日期：2026-10-14。  
> 來源： [Impeccable](https://github.com/pbakaus/impeccable) 的 Operate／Critique／Audit／Harden／Adapt／Clarify／Optimize 準則、2026-10-14 `main` 的前端原始碼、`docs/frontend-design.md`、`docs/semantic-rag-runbook.md` 及相關 Issues。  
> 改善任務：[UI-A #110](https://github.com/liujiawei-tw/supplypilot/issues/110) · [UI-B #112](https://github.com/liujiawei-tw/supplypilot/issues/112) · [UI-C #113](https://github.com/liujiawei-tw/supplypilot/issues/113) · [UI-D #114](https://github.com/liujiawei-tw/supplypilot/issues/114)。本文件由規格任務 [SP-909 #115](https://github.com/liujiawei-tw/supplypilot/issues/115) 維護。

## 1. 決策摘要

- **不要立即跳過 SP-901 的未完成外部驗收，直接改四包前端。** [PR #111](https://github.com/liujiawei-tw/supplypilot/pull/111) 目前仍為 Open、未合併。Code Review `FINAL PASS` 僅針對其已提交 SHA 的程式碼，不是 E9 真實服務或瀏覽器流程 PASS。
- **UI-A 不重複開 Issue**：沿用 SP-905 #110，負責整站繁中與六份虛構 Demo 文件、zh-TW locale、文案/Enum display map、中文語料重建索引的回歸。
- **UI-B #112 是本機第六份文件 Browser E2E 的真實 UI 缺口**：現有 `DocumentsPage` 有 Upload/Ingestion；Backend 有 Admin outbound approval、Backfill、Readiness、Gate Audit，前端尚缺對應管理介面。補齊「UI 真正可操作的流程」而不是把 curl/Postman 錄成影片。
- **UI-C #113** 讓 Dashboard 成為角色工作台、Recommendation 先顯示決策與下一步；使用既有授權資料，不能虛構跨角色 KPI。
- **UI-D #114** 先修已確認的第一頁 100 筆 Master Data 選擇限制，再以 Browser 證據做 RWD／無障礙／效能及輕量 Ant Design tokens 的改善。
- 四項均維持 Thin Frontend；不換框架、重造 Design System、不改寫核心 Business Logic／Permission、沒有額外 AI 寫入能力。

## 2. 實際審查結論與證據等級

| 項目 | 位置 | 目前可確認的事實 | 嚴重度／處理 |
| --- | --- | --- | --- |
| Semantic 管理 UI 缺口 | `frontend/src/pages/Documents/DocumentsPage.tsx`、`frontend/src/api/document.ts`、`backend/.../semantic/SemanticOperationsController.java` | 有 Upload/Ingestion；Admin outbound review、Backfill、Readiness、Gate Audit 的 Backend 路由未形成完整前端流程 | P1：UI-B；以第六份 E2E 驗收為阻擋 |
| Dashboard 尚非真實工作台 | `frontend/src/pages/Dashboard/DashboardPage.tsx` | 顯示功能入口及「目前沒有待辦資料」 | P2：UI-C |
| PO 主檔選取只載前 100 筆 | `frontend/src/pages/PurchaseOrders/PurchaseOrderForm.tsx` | `listSuppliers`／`listMaterials` 使用 `page:0,size:100` | P1：UI-D 的第一個修復項 |
| Business/UI 文案混雜 | Route、各 `pages/`、`components/` | 多頁英中混用、Raw Enum／技術欄位直接顯示 | P1：UI-A；UI-C/D 只改善展示層級，不重做翻譯 |
| 推薦結果資訊密度 | `RecommendationSummary.tsx`、`SupplierRankingTable.tsx` | Snapshot、Ranking 與 Create Draft 已實作，但拆在多區塊 | P2：UI-C；業務結果與 ranking 一律取自 Backend |
| RWD 與可及性驗收缺口 | `AppLayout.tsx`、Document Drawer、多品項表單、寬表格 | 有 Ant Design breakpoint 與部分 `scroll.x`；但尚無實際 375px／768px／1440px、200% zoom、鍵盤／實體觸控證據 | P2：UI-D；**待實測，不預先斷言失敗** |
| 載入與錯誤界線 | Loading/Empty/Error/BusinessError 以及 Import、Receiving、AI | 已有基本 loading/error、確認與不盲目重送；值得保留 | 不應推翻；UI-A/C/D 只改善文案與層級 |

**不宣稱**已執行 Impeccable CLI Detector、Lighthouse、axe、真實 Browser Screenshot、觸控裝置測試或測得具體 WCAG 違規分數。實作時記錄環境、尺寸、步驟、圖片與 PASS/FAIL/BLOCKED。

## 3. 工作包、邊界及驗收

### UI-A — SP-905 #110：繁體中文與 Demo 文件

沿用 #110 原範圍，不建立平行翻譯 Issue。中文化頁面、操作/錯誤/空狀態、Enum display label、AntD zh-TW locale 與六份虛構文件，保留所有 API JSON 欄位、Domain Enum、Code/ID 與多語檢索能力。更動五份 Seed 文件與第六份 UI 文件內容後，使用 Fresh/受控重建與重新 Ingestion、審核、Backfill、Gate；**舊 Embedding/Gate 不可再用**。

**Gate**：必須先核對 SP-901 #43 合併／結案與未完成 E9 驗收的正式追蹤方式，依 #110 的明確前置條件辦理；Fake provider 測試不等於真實 Gemini PASS。

### UI-B — SP-906 #112：Document → Semantic Gate 可視化操作

僅管理員可見出站核准／排除、Backfill、Readiness、Gate Audit 的 UI，沿用既有 REST 與 Role Matrix；明確顯示：
`MySQL Document READY` ≠ `Semantic Index READY` ≠ `Gate PASS` ≠ 已啟用 Semantic Read Switch。

狀態與錯誤以 Backend 為準，不為了 Demo 造假通過；新文件完成 Ingestion 後應可解釋 Gate 暫時關閉及恢復條件。Semantic Read Switch 維持操作手冊指定的環境設定，不提供任意切換的前端按鈕。第六份虛構文件只能由**本機已授權角色 UI** 真正上傳，不被 Seed；AWS 共用 PROCUREMENT_MANAGER 展示帳號依舊不能上傳。

**Gate**：需連同 [SP-901 #43](https://github.com/liujiawei-tw/supplypilot/issues/43) 的第六份文件瀏覽器 E2E 實測與本機真實 Gemini、pgvector、可追溯來源／版本／chunk／offset 一起核對，不因 `npm test` PASS 而自行宣稱整段 E9 完成。

### UI-C — SP-907 #113：Role-based Inbox 與 Recommendation 決策層級

Dashboard 依角色組合**已授權的現有查詢資料**並導向現有合法 Route；不能新增假的 Recommendation List／跨權限待辦／未知的 Aggregate API。推薦結果第一層看 Outcome、Net Requirement、推薦供應商與下一步；第二層看已保存計算依據；第三層才展開 Ranking 與技術欄位。所有數值、rank、狀態與 ID 必須與後端回應一致，不合併不同單位物料。

**Gate**：至少驗證採購、經理、倉管、管理員視圖；三種 Recommendation outcome；Empty/Error/Loading；既有角色/採購動作 regression。

### UI-D — SP-908 #114：技術品質與跨裝置可用性

優先修正大於 100 筆選擇範圍：遠端搜尋／分頁、已選物料/供應商可識別、API error fallback，避免高頻 API 或 race 造成選錯。其餘以測試結果決定：375px／768px／1440px、200% zoom、鍵盤焦點、Modal/Drawer focus、長欄位與大數值、Touch Target、表格水平捲動。統一必要的 Ant Design Token 與顯示狀態；先量測才評估 Lazy Loading／debounce 等優化。

**Gate**：101+ 筆 Master Data 的真實可選性、Browser RWD/鍵盤測試、lint/test/build、核心業務回歸；不能把程式檢查代替裝置測試。

## 4. E9 依賴與執行順序（不得繞開 Gate）

| 階段 | 原訂依賴／決策 | UI 新規劃如何插入 | 完成證據 |
| --- | --- | --- | --- |
| 0 | SP-900 #42 P0 Test Gate | 保持原條件，不因 UI 改善取消 | 完整 P0 測試與 CI |
| 1 | SP-901 #43、[PR #111](https://github.com/liujiawei-tw/supplypilot/pull/111) | **先解決 #111 Merge 前驗收與 #43 Closure 歧義**；不能靠一行 `Closes #43` 讓未完成項目遺失 | 真實 Provider/Gate 與 Browser 驗收狀態均有明確、仍開放的責任項 |
| 2 | 第六份文件本機 Browser E2E | UI-B #112 是尚缺的 UI 前置；按 AGENTS 一 Issue 一 Branch，需先有 #111 的合法合併／依賴安排，不能在未合併的功能分支交錯修改 | UI Upload → Ingest → Admin Review → Backfill → Gate → Query → Source/Version 全流程 |
| 3 | SP-905 #110 UI-A | 依現有 #110 的 SP-901 完成／合併前提再動工；中文文件變更後重跑相關檢索與 Source Gate | 繁中站、中文文件、索引重建、多語回歸 |
| 4（可選） | UI-C #113、UI-D #114 | 若列入此次面試展示版本，應在 SP-903 容器最後驗收前完成並重測；若影響發版時程可延期，且不視為 SP-901／Gemini 未完成項目 | 各自獨立測試、Browser Screenshot 與 Code Review |
| 5 | SP-903 #45 | **本機 Docker 優先，再 AWS EC2／HTTPS**；以最後合併的中文 UI／文件重做兩環境測試 | 啟動、持久化、重置、Gemini Chat/Embedding、Gate、角色與來源核對 |
| 6 | SP-904 #46 | 所有 Release Gate 通過後錄製從頭到尾的實際操作影片，再作最後發版審查 | 最終 Acceptance／證據／影片一致 |

### 特別注意：#111 的 `Closes #43`

[PR #111](https://github.com/liujiawei-tw/supplypilot/pull/111) 的 Code Review 記載已提交程式碼的 FINAL PASS，但**真實 Gemini、Semantic Gate、第六份文件完整查詢、Browser Reset E2E 尚未完成**；PR 內又包含 `Closes #43`。若不處理合併條件，合併可能自動關閉仍有未完成驗收的 #43。

**本文件不擅自修改 PR #111、Issue #43 的 Definition of Done，也不將它 Merge**。啟動新的功能分支前必須明確處理：要嘛按原 #43 完成剩餘驗收再合併，要嘛經正式 Scope／Issue 調整把餘項移到仍 Open 且明確阻擋 E9 Release 的任務；不可僅以 PR Code Review PASS 代替這項決策。UI-B 與第六份 Browser E2E 有相依，因此目前流程有必須先澄清的循環關係，不能假裝沒有。

## 5. 工期與交付流程

- UI-A：2–4 工作天；UI-B：2–3；UI-C：2–3；UI-D：3–5；跨包 Regression／Review 緩衝約 1；**總估 10–16 工作天**。
- 估算條件：既有 API 足夠、必要的 Code Review／測試與 PR 如期完成、每工作天約 6 小時有效開發／驗證時間；**不含** SP-900/SP-901 未完成外部驗收、SP-903 Local/AWS、SP-904 影片。UI-B 若 API／權限契約有真實缺口可能增加工時。
- 基本流程遵照根目錄 `AGENTS.md`：一 Issue 對一 Feature Branch；Codex 實作／Commit／Push；GPT 以確切遠端 SHA 獨立 Review，通過 FINAL PASS 後建立繁中 PR 並獨立一行 `Closes #該Issue`；只有使用者可 Merge；不得自行關閉 Issue。
- 每一個 `PASS` 必須註明是「程式碼審查」、「自動測試」、「真實 Provider」、「本機 Browser」或「AWS」哪一種，不得混用。文件規劃完成不代表功能完成。

## 6. 原有設計不可被改壞的底線

- 不在 Frontend 重做淨需求、Ranking、逾期（以 Backend `isOverdue`）或授權業務判斷。
- 不改 JWT/RouteGuard/Backend RBAC 邊界；Warehouse Receiving 使用受限 DTO，不曝光價格／採購敏感細節。
- PO/Receive/Inventory Import/AI Draft 高風險 Mutation，未知結果不得默默重送，確認憑證與 `expiresAt` 由 Backend 決定。
- Semantic 文件來源、版本、有效狀態、管理員出站授權、Credential/個資外送政策、Gate Audit 與 Read Switch 行為必須保持可追溯且真實。
- UI-A 翻譯 display label 不可修改傳輸 Enum、資料庫、原始文件版本／料號／供應商代碼；改正文後不可沿用舊向量與舊 Gate。
- Impeccable 對這類 ERP／Dashboard 是 **Operate Mode**：優先清楚、可掃描、可靠與符合現有元件慣例；不需要高成本動畫、新 Framework、Dark Mode 或沒有業務意義的視覺效果。
