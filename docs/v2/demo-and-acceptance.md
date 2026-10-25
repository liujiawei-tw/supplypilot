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
- sp-907 初始基線建立 10 家供應商、12 項物料、45 組供應關係、10 張具 V2 PO 狀態/看板階段的範例採購單，以及 115 份 `SEED_BASELINE` 文件；另有 1 項 `MANUAL_UI_UPLOAD_ONLY` 文件保持獨立且不由基線同步改動。不建立 V1 Demand／自動 Recommendation 範例。
- 文件 Catalog 的分類、審核文字、Fixture 或 hash 只作追溯與靜態驗證，不構成管理員授權、出站核准或已索引證據。
- `SEED_BASELINE` 僅從固定 Catalog 與來源 hash 同步，並依可信 Demo 系統審核政策及既有出站政策決定核准或 `REQUIRES_REVIEW`；雜湊或來源身分不符時記錄 conflict，不繼承舊審核。`MANUAL_UI_UPLOAD_ONLY` 及其他一般上傳仍按管理員流程，針對特定來源版本/hash 審查。

## 3. 既有 Demo 更新

### 3.1 版本與狀態判定

截至 2026-10-25，Demo 目標資料集為 `sp-907-zh-tw-v3`。啟動時仍採 Fail-Closed 判斷：已知舊版 `sp-905-zh-tw-v1`、`sp-906-zh-tw-v2` 可進入既有資料升級流程；`sp-901-v1` 不代表相同語料，不列為可升級版本。未知或未來版本、`INITIALIZING`、`FAILED`、未知狀態及不合法版本/狀態組合不得視為完整基線。目標版本 `UPDATE_REQUIRED` 或 `RELATIONAL_READY` 會安全重試尚未完成的升級/語料同步；目標版本 `COMPLETE` 不重做關聯式變更，但每次仍以冪等方式核對語料基線，並在結構化計畫中檢查 master 外的額外資料列。

### 3.2 `AUTO_SAFE` 關聯式升級

Demo Profile 的 Compose 預設為 `DEMO_DATASET_VERSION=sp-907-zh-tw-v3`、`DEMO_DATASET_UPGRADE_MODE=AUTO_SAFE`；Runner 只允許 `demo` 且不允許同時啟用 `prod`。主檔唯讀掛載。AUTO_SAFE 固定驗證 `samples/demo-master-data.json` 的 SHA-256：`6f66e13c66d7a42df96eda3e0b7abde5ba8872db6e53a7555961b0c356ea8245`。目標版本或雜湊不符時拒絕任何關聯式變更。

雜湊檢查、資料集 marker 與資料列鎖定、鎖定後的計畫及前置條件比對、允許的更新與 marker 寫入都在同一資料庫交易中執行。鎖定順序固定為資料集 marker、供應商、物料、供應關係。AUTO_SAFE 的新增/更新只限已固定主檔與明確 allowlist：既有供應商只將精確舊值的展示名稱/國別改成 sp-907 值；既有物料只將精確舊名稱改成目標名稱；只可新增 SUP-007 至 SUP-010 的完整核准供應商資料及 15 組固定供應關係，每組需符合完整價格、MOQ、包裝倍數、交期、幣別及狀態欄位。

資料庫中已被使用者修改的既有資料、非 allowlist 的新增/差異、master 未列出的額外供應商/物料/供應關係、缺少的既有基線實體，以及任何欄位不符都會列為 conflict。這些資料一律保留，不會自動新增、覆寫或刪除；已知且安全的 allowlist 變更可與 conflict 一起記錄，但 marker 會留在待處理狀態，不能因此標記完成。AUTO_SAFE 不會修改庫存、價格或既有供應關係條件、PO/快照、使用者資料或文件審核決策。

### 3.3 語料同步與完成 marker

關聯式升級後，AUTO_SAFE 會呼叫 `DemoCorpusSyncService.synchronizeBaseline()`；目標版本已完成時也會重跑此冪等同步，以便恢復中斷的來源基線工作。同步只處理 `SP-901`、`sp-907-zh-tw-v3` Catalog 中 115 筆 `SEED_BASELINE` 來源；唯一 `MANUAL_UI_UPLOAD_ONLY` 項目不會被自動建立、採用或改動。來源檔雜湊、關聯供應商、文件身分或既有來源不符時記錄 source conflict，失敗時保留可重試狀態。

資料集 marker 只有在目標版本的關聯式階段完成，且 115/115 來源已存在或採用、Ingestion 為 READY，並且沒有來源 conflict/failure 時才可成為 `COMPLETE`。這個 marker 不代表文件都已核准或語意索引已發布；核准政策、每份文件的索引狀態、排程與 pending/retry 由文件生命週期獨立追蹤。索引 pending 不會被冒充為索引完成，也不阻止關聯式/來源基線 marker 完成；Provider 未就緒不得讓採購核心不可用。

### 3.4 手動模式及保留資料

`DISABLED`、`DRY_RUN`、`APPLY` 仍可明確覆寫 Compose 預設。`DISABLED` 不執行升級或同步；`DRY_RUN` 只讀取資料庫與主檔，輸出資料庫名稱、來源/目標版本、變更明細、衝突、人工步驟及計畫 SHA-256。`APPLY` 維持人工確認流程：需同時提供本次計畫 SHA-256 與精確確認字串 `UPDATE-SP-901@<database>@sp-907-zh-tw-v3`；服務在鎖定資料列後重新產生計畫，主檔/資料庫前置條件改變就拒絕舊 SHA。APPLY 只處理關聯式計畫並保留待完成 marker，不替代 AUTO_SAFE 語料同步。

既有 PO 不透過資料庫直接改 Stage 或重建；需保留歷史採購快照。若展示需要八階段案例，請透過正式 UI/API 操作建立新的範例單。手動上傳項目仍由使用者透過 UI 建立並遵循一般審核流程；既有文件與審核狀態不因同步而被覆寫。更新範圍只限有明確 Demo 隔離標記的資料庫及文件儲存路徑，不得對正式環境執行更新或將這套機制解讀為 Reset。

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
