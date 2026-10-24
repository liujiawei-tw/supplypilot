# SupplyPilot V2 — Demo、資料集與驗收部署順序

> V2 規格已確認；DEMO 資料擴充、正式部署與全站實測須依各自驗收證據確認。Demo Reset 已取消；#147 已 Closed — Not planned，#149 亦已移除 Reset、AWS 與舊版 Release Gate 前置條件。

## 1. 環境順序及完成條件

1. 逐張完成 V2 Issue 的實作、必要測試、獨立 Review 與人工 Merge。
2. 以標準 Docker Compose 啟動 Frontend、Backend、MySQL 與 PostgreSQL/pgvector，再執行真實 Browser、Gemini Chat、Embedding 與 pgvector E2E。
3. AWS Demo #133 與最終 Review #46 依各自最新範圍，在實際部署階段執行；本 Issue 不修改 AWS 或部署設定。
4. 完成必要部署驗收後製作操作影片。影片不能替代測試，最終 Review 須對應 V2 新 SHA 與最新規格。

文件、Issue 建立或 V1 測試結果都不能視為 V2 已完成、AWS 已部署或真實 Provider Gate PASS。

## 2. Demo 基線與語料契約

- Demo 使用與 prod profile 隔離的虛構企業資料。保留 MySQL、Semantic DB、文件儲存路徑與 Flyway/Storage Guard。
- samples/demo-master-data.json 是供應商、物料、SupplierMaterial、PO 範例與數量的結構化資料契約；samples/documents/corpus-catalog.json 與 rag-fixtures.json 記錄文件來源及驗證情境。
- 初始 Seed 建立 10 家供應商、12 項物料、45 組供應關係、10 張具 V2 PO 狀態/看板階段的範例採購單，以及 15 份文件；不建立 V1 Demand／自動 Recommendation 範例。SUP-005 人工 UI Upload 文件獨立於 Seed。
- 文件 Catalog 的分類、審核文字、Fixture 或 hash 只作追溯與靜態驗證，不構成管理員授權、出站核准或已索引證據。
- 新文件及新來源版本首次都要依正常管理員流程審查；來源內容或 hash 改變時不得繼承舊審核。

## 3. 既有 Demo 更新

啟動時依資料集狀態採 Fail-Closed 判斷：`COMPLETE` 且版本相同時略過 Seed；`COMPLETE` 且為合法舊版本時保留資料並標示 `DEMO_DATASET_UPDATE_REQUIRED`，不 Reset、不自動升級；`UPDATE_REQUIRED` 且版本相同時保留資料並標示文件及看板人工步驟仍未完成。`INITIALIZING`、`RELATIONAL_READY`、`FAILED`、未知狀態、未來版本或不合法版本組合均拒絕當成完整基線。

Compose Demo 將 master data 與文件目錄唯讀掛載，並固定讀取 `sp-906-zh-tw-v2`。更新工具預設 `DISABLED`；維護者需將 `DEMO_DATASET_UPGRADE_MODE` 設為 `DRY_RUN`，核對輸出的資料庫名稱、來源/目標版本、變更明細、衝突、人工步驟及計畫 SHA-256。計畫雜湊包含主檔原始內容 SHA-256、待套用值、資料庫狀態及相關供應商、物料、供應關係前置條件。DRY_RUN 只讀取資料庫與主檔，不執行資料寫入。

確認計畫後，切換到 `APPLY` 時服務在目標鎖定後重新產生計畫；只有 `DEMO_DATASET_UPGRADE_PLAN_SHA256=<本次 DRY_RUN 的 SHA-256>` 與 `DEMO_DATASET_UPGRADE_CONFIRMATION=UPDATE-SP-901@<database>@sp-906-zh-tw-v2` 均符合時才套用。APPLY 讀取一次主檔，核對其內容雜湊後使用同一份快照套用，不會核准或套用另一份主檔；供應商、物料、供應關係或資料集狀態在 DRY_RUN 後改變時會拒絕舊 SHA。資料列鎖定與資料更新位於同一交易，發生錯誤時整筆更新回滾。套用後標記為 `UPDATE_REQUIRED`，表示後續文件及看板人工步驟仍未完成；工具不會替文件核准／索引，也不會修改既有 PO、庫存、使用者或文件審核。Compose 範例預設保持 `DISABLED`，不得在本 Issue 對既有 Demo 資料庫執行。

自動更新僅能將仍符合舊基線值的供應商/物料展示名稱改為繁中，新增缺少的非核心供應商與供應關係，並保留既有狀態、庫存、價格、PO 快照、使用者資料、V1 Demand 與文件審核。既有 SupplierMaterial 價格／條件差異、已被使用者修改的值、缺少的既有物料/庫存或資料衝突會列為人工處理。更新後狀態為 UPDATE_REQUIRED，表示文件與看板資料仍待人工完成，不會冒稱與全新 Seed 完全一致。

既有 PO 不透過資料庫直接改 Stage 或重建；需保留歷史採購快照。若展示需要八階段案例，請透過正式 UI/API 操作建立新的範例單。既有同名同版文件保留原檔、hash 及審核狀態，新的來源版本由管理員按正常流程上傳及審查。新增文件不得自動核准或在核准前送 Gemini。

更新適用範圍只限有明確 Demo 隔離標記的資料庫及文件儲存路徑。不得對正式環境執行更新或將這套機制解讀為 Reset。

## 4. RAG 文件與索引驗收

- Document READY 代表上傳及解析完成，不代表可供語意查詢或已發布索引。
- 新文件須經管理員對特定來源版本/hash 審查；通過後依 #146 持久化非同步索引流程執行。
- 真實 Gemini Embedding、pgvector 發布、引用來源及回答品質只有在實際執行並保存證據後才可標示 PASS。靜態 Fixture 或 Fake Provider 不代表 RAG 品質 PASS。
- Gemini 不可用、限流或中斷時，不能宣稱索引或查詢驗收完成；採購核心流程仍須可用。

## 5. V2 驗收矩陣摘要

| 區塊 | 必須具體核對 |
|---|---|
| 帳號 | 多 ADMIN 業務帳號、canManageUsers 403、最後管理者保護、永久刪除後 PO 未指派與快照 |
| 草稿 | 最低必填、新採購直接建立正式 DRAFT、期望日後補、審核中可編輯、核准前日期 Gate |
| 看板 | 八欄 Stage/PO 狀態映射、業務操作確認、非法跳欄與只前進、8→7 撤銷付款 |
| 取消 | 1～3 REJECTED、4～5 CANCELLED、6～8 禁止、取消原因與在途庫存排除 |
| 收貨付款 | 5→6 無入庫、異常留第 6 欄、6→7 原子整筆收貨、7→8 人工付款、不得重複入庫 |
| 交期 | 期望日期核准後不可改、承諾日期獨立可改、逾期不隨承諾日期消失 |
| 搜尋 | PO/供應商/物料文字條件及原有日期/狀態，含 101 筆以上分頁與安全查詢 |
| RAG | MySQL+pgvector 查詢融合、文件名稱/版本/原文摘錄、無證據拒答、敏感出站不繞過 |
| 索引 | 新文件未索引/失敗不阻斷已發布文件；核准後自動 Job、Retry、Reboot 恢復與 admin Retry |
| Demo | 種子資料/語料契約一致；新增文件待審；既有資料升級具 Demo 隔離、Dry Run、目標確認及冪等性 |
| UI | 繁中、時區、RWD、金額格式、只保留使用者確認的功能範圍 |
| 可靠性 | JWT、後端權限、交易回滾、併發 409、Provider 503、安全錯誤訊息及秘密資料保護 |

## 6. 部署階段另行決策

AWS 共用 Demo 帳號權限依部署階段規格決策。原 #133 描述的 V1 PROCUREMENT_MANAGER 方案及公開高權限 admin 均不是預設方案。部署前要確認共同帳號的人員管理、文件上傳與出站核准權限，再執行公開展示驗收。

雲端成本、域名與額外雲端服務由部署階段另行決定；不得因本文件新增無關架構。
