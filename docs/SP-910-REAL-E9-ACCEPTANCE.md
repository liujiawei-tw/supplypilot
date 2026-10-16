# SP-910 真實 E9 驗收紀錄

**SP-910 E9 FAIL — REPRODUCIBLE FINDINGS**

本次真實 Browser、Gemini、pgvector 與語意 Gate 驗證發現可重現的查詢失敗及證據不足誤答，因此 E9 不通過。這份紀錄區分已實際執行、失敗、受前置條件阻擋及未執行項目；未把 API smoke、Unit Test 或 GitHub Actions 標成 Browser E2E PASS。

## 任務與 Git 狀態

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#117 SP-910](https://github.com/liujiawei-tw/supplypilot/issues/117)，先前查核為 OPEN。
- 依賴 Issue #43、#112、#110 先前查核均為 CLOSED。
- 驗收分支：`codex/sp-910-real-e9-acceptance`
- 本次檢查 HEAD：`27e17bcbaa876e2bb58b14318b6568ffb21b46e7`
- 起始基線為 `8aa1031158d8bc317057b356c1b38ba9c59efefa`；HEAD 與起始 SHA 的關係以該分支 Git 歷史為準。
- 本報告更新及 `docs/evidence/sp910/` 證據尚未提交。未建立 PR、未 Merge、未關閉 Issue。
- 驗收日期：2026-10-15，Asia/Taipei。各步驟時間依執行紀錄；下文未提供精確時間的項目不推算耗時。

## 執行環境與隔離

| 項目 | 實測環境／結果 |
|---|---|
| Windows | PowerShell 版本字串 `10.0.26200`；Maven Wrapper 識別 Windows 11、amd64 |
| Java／Maven | Zulu OpenJDK 21.0.10；Maven Wrapper 3.9.16 |
| Node.js／npm | Node 24.15.0；npm 11.12.1 |
| Docker | Docker Desktop Engine 29.6.1；Compose CLI 5.3.0 |
| MySQL | 專用容器 `supplypilot-sp910-mysql`，`mysql:8.4.11`，loopback `127.0.0.1:13316`；資料庫 `supplypilot_sp910_demo`；Volume `supplypilot-sp910-mysql-data` |
| Semantic DB | 專用容器 `supplypilot-sp910-pgvector`，PostgreSQL 17.11／pgvector，loopback `127.0.0.1:15439`；資料庫 `supplypilot_sp910_semantic_demo`；Volume `supplypilot-sp910-pgvector-data` |
| 文件目錄 | `backend/data/sp910-demo-documents` |
| Browser | Windows 主機 Microsoft Edge + Playwright headed automation；實際操作畫面與截圖位於 `docs/evidence/sp910/` |
| Frontend／Backend | Vite loopback `127.0.0.1:5173`；Backend `127.0.0.1:8081`，demo profile；Frontend API base 指向 `http://localhost:8081/api/v1`；Backend CORS Origin 使用 `http://127.0.0.1:5173` |
| Gemini | Chat `gemini-3.6-flash`、Embedding `gemini-embedding-001`、768 維、Generation `gemini-embedding-001-768-v1`；真實 API key 由本機安全設定注入，未寫入報告、截圖或 Repository |

本次 MySQL、PostgreSQL、Volume、文件目錄、帳號均為可辨識的 SP-910 專用資源。`SPRING_PROFILES_ACTIVE=demo`，實際資料庫與 `DEMO_ALLOWED_DATABASE`／`DEMO_ALLOWED_SEMANTIC_DATABASE` 相符；Flyway 目標與允許目標一致。其他既有 Docker Volume 未掛載、未查詢或修改。Gemini 憑證值未輸出。

### Browser 連線診斷與解除

初次 Browser 失敗不是產品 CORS 或 API 錯誤：Vite 曾在隔離執行環境啟動並回報 ready，但 Windows 主機無法連到該程序。將 Vite 改由同一 Windows 主機執行、綁定 loopback 後，主機 HTTP 與 Edge Browser 均可載入 UI。初始 Vite 未設定 `VITE_API_BASE_URL` 會使前端顯示設定錯誤；補上 `http://localhost:8081/api/v1` 後正常。`localhost:5173` Origin 不符合嚴格 CORS allowlist 而遭 403；使用已允許的 `http://127.0.0.1:5173` 後 OPTIONS 成功。未公開服務、未放寬防火牆、未修改 CORS 或安全政策。

已在真實 Edge 完成首頁、登入、Dashboard、Materials 導覽，並執行後續採購及 Semantic Admin Browser 操作。相關畫面見 `login.png`、`dashboard.png`、`materials.png` 及以下各案例截圖。

在本報告更新的工作階段重新檢查時，5173、8080、8081、8082 全部沒有 Listening，`127.0.0.1:5173` 與 `127.0.0.1:8081/actuator/health` 均無法連線；Docker CLI 回報無權存取 Docker Desktop named pipe。故本次工作階段無法重啟服務補做一般重啟 E5，也不宣稱服務目前運行。先前 Browser 證據仍是實際執行所得，但不能代替目前服務健康狀態。

## Demo Baseline

正式 demo profile initializer 實際記錄 Dataset `sp-905-zh-tw-v1`、Marker `COMPLETE`、Baseline Date `2026-10-15`。初始化時數量：Users 5、Materials 12、Suppliers 6、SupplierMaterial 30、Demands 12、Purchase Orders 10；PO 分布 DRAFT 2、APPROVED 2、ORDERED 3、RECEIVED 2、REJECTED 1。五份 Seed 文件均 READY、Version 1.0；SUP-005 第六份文件未由 Seeder 建立。文件原檔、Metadata 與 Chunk 內容一致。正式 Seeder 初始 lexical embedding 不計為 Gemini Backfill。

後續 A4 Browser 流程建立 PO #11，並加入一份僅供 D1 的虛構英文測試文件 #6；兩者均留在 SP-910 專用 Demo 資源。沒有上傳指定的 SUP-005 第六份文件，也沒有執行 Reset。

## 驗收矩陣

| Case | 實際結果與證據 | 狀態 |
|---|---|---|
| A1 環境／Browser 可達性 | 同機 Vite + Edge 解決隔離問題並完成 UI 操作；本次報告更新時服務均已停止，當前 HTTP 連線失敗，Docker pipe 權限不足 | PARTIAL |
| A2 專用資源隔離 | 全新、具名 DB、Volume、容器及文件 root；實際資料庫、Flyway 與 allowlist 一致 | PASS |
| A3 中文 Fresh Demo | Marker、筆數、PO 分布、五份 READY v1.0 文件符合；無 Seed SUP-005 | PASS |
| A4 採購 Browser 流程 | 真實 Edge 完成推薦、Draft、Manager 核准、Buyer ORDERED、Warehouse 收貨；MAT-001 750 KG，收貨後 1050 KG，PURCHASE_RECEIVE 750 KG；重複收貨 HTTP 409 `DUPLICATE_RECEIVING` | PASS |
| B1 Gemini Chat／Embedding | 真實 Gemini Chat inventory 查詢回 200、回答 MAT-001 1050 KG；真實 Gemini Embedding Backfill 完成。聊天文件查詢另見 B5 的失敗 | PARTIAL |
| B2 五份文件出站核准 | ADMIN UI 審核五份文件，均 APPROVED、Version 1.0、reviewer admin ID 5；source hash 已記錄於實際回應及 PG 索引核對 | PASS |
| B3 五份真實 Embedding Backfill | ADMIN UI 從 afterId=0、limit=25 執行；processed 5、READY 5、其他狀態 0、lastId=5、morePossible=false，約 4.01 秒 | PASS |
| B4 Readiness／Gate Audit | Readiness passed=true；UI 執行持久化 Audit passed=true、auditId=1；六份測試資料重建後 auditId=2。Read switch 以 Backend `SEMANTIC_READ_ENABLED=true` 啟動設定顯式啟用 | PASS |
| B5 五份來源查詢 | SUP-001 交期問題 503 `AI_INVALID_RESPONSE`；SUP-002 報價期限、MAT-001 儲存條件、品質隔離問題有回答及有效文件來源；SOP 收貨角色問題 403 `ACCESS_DENIED`。5 題未全通過 | FAIL |
| B6 證據不足 | 冷凍海運保險費率問題回 200 ANSWERED，提供五份不相關文件事實，非預期 `INSUFFICIENT_EVIDENCE` | FAIL |
| B6 Gate 未通過 | 新 READY 文件未索引時 Readiness passed=false、pending 1；Browser Query 503 `AI_PROVIDER_UNAVAILABLE`，符合 fail-closed | PASS |
| B6 Gemini Provider 故障 | 專用測試 Backend 使用無效測試憑證；Browser Query 503 `AI_PROVIDER_UNAVAILABLE` | PASS |
| B6 pgvector 故障 | 只停止 SP-910 pgvector 容器；Semantic Query 503 `AI_PROVIDER_UNAVAILABLE`，MySQL inventory AI query 仍 200；finally 恢復 pgvector 並確認 ready | PASS |
| C1 SUP-005 上傳前條件 | 五份中有必測來源查詢失敗，故不滿足 Issue 要求的全五份 Source Query PASS | BLOCKED |
| C2 SUP-005 Browser Upload／Ingestion | 未上傳 `samples/documents/ui-upload-sup-005-v1.txt`，沒有預先建檔 | NOT RUN |
| C3 SUP-005 審核／索引／Gate | C2/C1 前置未完成 | NOT RUN |
| C4 SUP-005 Source Query | C2/C3 前置未完成 | NOT RUN |
| D1 中文查英文 | Browser 正常上傳虛構英文文件 #6、UI ingestion、ADMIN 核准及真實索引；中文查詢仍 503 `AI_INVALID_RESPONSE`，無 Source | FAIL |
| D2 英文查中文 | Browser 問 `How long is SUP-002's quotation valid?` 回 200，正確包含 30 calendar days，引用中文 SUP-002 文件 #2、v1.0、chunk 0、有效 UTF-16 offsets；約 8.603 秒 | PASS |
| E1 Reset 前證據 | 已取得文字快照：PO #11 RECEIVED、MAT-001 1050 KG、文件 1–6 READY、六份審核／索引、Gate audit #2；未形成完整獨立快照檔，Reset 不具備完整證據包 | PARTIAL |
| E2 Guarded Reset | 正式 `scripts/reset-demo.ps1` 未執行 | NOT RUN |
| E3 Reset 後 Browser | 未 Reset | NOT RUN |
| E4 Reset 後重建 Gate | 未 Reset | NOT RUN |
| E5 一般 Backend Restart | 未完成；本次工作階段開始時服務已停止且 Docker named pipe 不可用 | NOT RUN |

## A4 採購 Browser 詳情

- Buyer 建立 MAT-001 Recommendation #1，淨需求 750 KG；排名 SUP-002（750 KG、104 TWD、3 日）、SUP-001（800 KG、100 TWD、5 日），非啟用 SUP-004 未推薦。
- Buyer 建立 DRAFT PO #11；Manager 以 UI 核准；Buyer 標記 ORDERED；Warehouse 收貨。稽核 Actor 分別為既有 Demo Manager #3、Buyer #2、Warehouse #4，時間／狀態由 Backend 實際產生。
- 收貨前庫存計算 `1000 + 200 - 300 - 150 = 750 KG`；收貨後 1050 KG，新增 `PURCHASE_RECEIVE 750.0000 KG`。重複收貨 409。
- UI 證據：`a4-recommendation.png`、`a4-recommendation-detail.png`、`a4-draft-created.png`、`a4-manager-approved.png`、`a4-buyer-ordered.png`、`a4-warehouse-received.png`、`a4-inventory-levels.png`、`a4-inventory-transactions.png`。

## Gemini、Semantic Gate 與來源證據

真實 Gemini 設定為 Chat `gemini-3.6-flash`、Embedding `gemini-embedding-001`／768、Generation `gemini-embedding-001-768-v1`。金鑰只透過本機安全環境注入。真實 Chat inventory 查詢成功；五份文件 Backfill 對真實 Gemini Embedding 成功。PG 實際索引每份 READY、每份一個 chunk，source hash 與文件出站核准 hash 相同。Java UTF-16 String offset 與正規化來源文字切片核對一致。

五份出站核准 Source Hash：

| Document | Source Hash |
|---|---|
| #1 SUP-001 | `d43706a4c4fe9c95456a3e8522f69691a3082a5621490380b2332f3e993b96af` |
| #2 SUP-002 | `d7aa2997bcf52076d64de371c6eb1cb93c8934bc91534867d2ad3f774b869ceb` |
| #3 採購 SOP | `c7d724003e8e1f1d92b1876ac1a34f5288fe2007a710acc488afb9b3ec22d37c` |
| #4 MAT-001 規格 | `c45ce4b2a995389c92dead5e80ecdd5a50d78a12e768f691ee4c031b44e989e7` |
| #5 品質要求 | `f986aa40f9fb3dba65d938ea98ecacba2581c00397b63903f36ee0ed6f4675b5` |

B5 逐題結果：

1. SUP-001 標準交期：HTTP 503 `AI_INVALID_RESPONSE`，無回答與來源；同一問題重試仍 503。
2. SUP-002 報價有效期：HTTP 200，回答包含自開立日起 30 個曆日；來源 #2、v1.0、chunk 0、offset 0–343。
3. SOP 收貨角色：HTTP 403 `ACCESS_DENIED`，同一問題重試仍 403。現有 `AiQueryPlanner` 的禁止意圖 regex 將一般「收貨」問題判定為 FORBIDDEN。此為安全政策行為，本驗收沒有修改政策或繞過拒絕。
4. MAT-001 單位與儲存：HTTP 200，回答 KG、陰涼乾燥、避免陽光直射；來源 #4、v1.0、chunk 0、offset 0–268。
5. 品質隔離：HTTP 200，包含批次識別缺失、封條破損、污染或物料不符；來源 #5、v1.0、chunk 0、offset 0–269。

每題完整實際回應保存在 `b5-query-results.json`；相關 UI 截圖為 `b5-query-sup001.png` 及 `b5-query-doc-2-result.png` 至 `b5-query-doc-5-result.png`。部分答案採用 BACKEND_FACTS，內容包含多筆候選文件；只有上表指出的來源片段可確認該題來源。不能把回答正確但錯誤／缺失 Source 算成 PASS。

Gate audit #1：五份文件 passed=true、auditId=1。D1 文件加入後，新文件未審核／未索引時 Readiness passed=false、pending=1，Semantic Query fail-closed；文件 #6 完成審核、Backfill 後，Audit #2 passed=true。這證明新增 READY 文件會令 Gate 失效，須完成索引與重新 Audit 才恢復。

### D1 額外英文測試文件

為避免把中文 Seed 冒充英文來源，建立虛構文件 `docs/evidence/sp910/cross-language-sup001-en.txt`，只存在 SP-910 測試資源，未加入 Seeder。ADMIN 以 Browser 上傳，Backend 產生 Document #6，初始 PROCESSING；明確由 UI ingestion 後 READY。ADMIN 核准出站，source hash `71fa840d5deb5fd12f15f891f927e50c73caaa02991057b89c5036ee2a078f17`；真實 Gemini Backfill 後六份 READY，Readiness 通過、Audit #2 通過。中文問題仍回 503 `AI_INVALID_RESPONSE`，未取得英文文件 Source，因此 D1 FAIL。證據：`d1-uploaded-en.png`、`d1-ingested-en.png`、`d1-approved-en.png`、`d1-backfill-en.png`、`d1-gate-audit-en.png`、`d1-chinese-to-english.json`、`d1-chinese-to-english.png`。

## 可重現 Findings

### P0 — 必要文件查詢未達來源驗收條件

- 重現：Semantic Read 啟用、Gate passed=true，使用 Browser 分別送出 SUP-001 交期問題與 SUP-001 英文測試文件的中文問題。
- 預期：真實 Gemini 回答條款並引用匹配文件來源。
- 實際：兩次均 HTTP 503 `AI_INVALID_RESPONSE`，無 Source。SOP 收貨角色問題則被現有禁止意圖規則回 403。
- 影響：B5 必要題未全通過，C1 前置條件未達成；不能進入 SUP-005 E2E，也不能宣稱 E9 完成。
- 修正範圍：此處不自行修改安全政策或 AI 業務邏輯；應另行安排產品缺陷審查及修正後重跑。

### P0 — 證據不足問題被回答

- 重現：送出「這些文件是否規定冷凍海運的保險費率？」
- 預期：`INSUFFICIENT_EVIDENCE`。
- 實際：HTTP 200 `ANSWERED`，以五份無關文件的 backend facts 組成答案。
- 影響：可能產生無依據的商務回答，違反 E9 明確條件。
- 證據：`b6-insufficient-evidence.json`、`b6-insufficient-evidence.png`。不因結果而更改預期答案。

### P2 — 一般重啟驗收尚缺

- 重現條件：目前工作階段的本機服務已停止，Docker CLI 無 named-pipe 權限；因此未能檢驗普通 Backend restart 是否誤觸 Demo Reset。
- 實際：5173／8080／8081／8082 都無 Listening，localhost HTTP 失敗；E5 NOT RUN。
- 解阻：在可存取 Docker Desktop 的同一 Windows 使用者工作階段恢復專用服務，記錄重啟前後 PO #11、MAT-001 庫存、文件數及 Gate 狀態。不可執行 reset-demo.ps1 作替代。

## 失敗情境與服務可用性

- Gate 未通過時 Semantic Query 回 503 `AI_PROVIDER_UNAVAILABLE`，符合 fail-closed。
- Gemini Provider 故障注入僅用本機專用測試 Backend 與無效 placeholder 測試憑證，Browser Query 回 503 `AI_PROVIDER_UNAVAILABLE`。
- 僅停止 SP-910 專用 pgvector 容器時，Semantic Query 回 503；MySQL inventory AI 查詢仍 HTTP 200。pgvector 已由測試 finally 流程恢復並確認 healthy。
- 證據：`d1-gate-invalidated.json`、`d1-gate-invalidated-query.png`、`b6-gemini-provider-failure.json`／`.png`、`b6-pgvector-failure.json`、`b6-pgvector-unavailable.png`、`b6-mysql-available.png`。

## 測試與 CI 區分

- Frontend `npm run lint`、`npm run build` 曾通過；既有大型 chunk 警告仍在。
- Semantic UI Vitest：9 tests 通過；Semantic API client Vitest：8 tests 通過。
- Backend Demo Seed Smoke：5 tests 通過；真實 Gemini Embedding smoke：1 test 通過。兩者均非 Browser E9。
- Frontend 全套 Vitest 曾因 Windows temp rename `EPERM` 失敗，改暫存目錄後仍無摘要而中止，不能標成全套 PASS。
- GitHub Actions #38691504272 為 SUCCESS，但 Backend／Frontend jobs 均 SKIPPED；不代表完整回歸 PASS。
- 完整命令與過程紀錄見先前任務日誌；本文件僅將上述可辨識的結果與真實 Browser 證據分開陳述。

## Reset 決定與 #45 交接

Guarded Reset 沒有執行。雖然 DB、Volume 與文件目錄為隔離專用資源，但 E9 必要 Source Query 已 FAIL、Reset 前完整證據包尚未落盤、且本次工作階段無法存取 Docker named pipe；所以沒有執行破壞性 Reset。Reset Exit Code、耗時、Reset 後 Browser 狀態及 Gate 恢復耗時均為 NOT RUN，不能推估。

#45 後續應在其目標 Docker／AWS 環境分別實測：安全注入 Gemini Chat／Embedding 設定、pgvector 持久化、Gate 審核／Backfill／Audit 重建順序、Reset 後人工或自動復原、普通 Backend restart 的資料保留、Browser UI 流程及實際耗時。本機結果不能外推成 Docker production 或 AWS PASS；本次也沒有驗證每天 00:00 Reset 的自動復原。

## 本次未完成項目與解除條件

1. 修復或另行審查 `AI_INVALID_RESPONSE` 與 `ACCESS_DENIED` 的必要查詢結果；修復後重跑五份 Source Query，保持原安全規則與原預期條款。
2. 修復證據不足時仍回 `ANSWERED` 的問題，重跑 B6。
3. 在失敗修正且 C1 五份來源查詢全部 PASS 後，才執行 SUP-005 正常 Browser Upload、Ingestion、Review、Backfill、Gate Audit、來源查詢。
4. 完成上述前置後，再保存完整 Reset 前快照、執行正式 Guarded Reset、Browser 驗證重置後基線、重建真實 Gate 並量測恢復時間。
5. 恢復可控的本機服務後完成 E5 一般 Backend restart 驗證。

本次未修改安全政策、業務規則或驗收條件；未清除資料庫、未手工 SQL 清表、未刪除 Marker、未變更 AWS 資源。證據目錄只收錄實際 Browser 畫面、真實回應及虛構 D1 測試檔，不含 API key、密碼、JWT、資料庫 dump 或真實公司資料。

## 第二輪 E9 驗收（合併 #121 後）

**狀態：SP-910 E9 BLOCKED — PARTIAL EVIDENCE AVAILABLE**

本節保留第一輪 FAIL 證據與結果，不覆寫第一輪記錄。第二輪使用合併 #121 的程式碼執行環境檢查及 Backend 回歸；新版 Backend 啟動時被現有 Demo Dataset Guard 阻擋，因此沒有重建或改動資料，也沒有把先前 Gemini／Gate／Browser 查詢結果套用為第二輪 PASS。

### Git 與執行環境

- 日期：2026-10-15，Asia/Taipei。
- Repository：`liujiawei-tw/supplypilot`；分支：`codex/sp-910-real-e9-acceptance`。
- 起始 `origin/main`：`363f8639e56aea120ab756549dc90226a4ebd7c1`（PR #121 merge commit；#120 已 Closed）。
- 第二輪執行基準 HEAD：`39433b8465f943e3741a10040b87d029091dac4e`，為將上述 main 合入原驗收分支後的本機 merge commit。驗收報告與 Edge 證據已透過一般 Push 發佈；交付提交 `86f5781f6ceff917d21bad59c6312d048fc78358`，遠端分支核對到相同 SHA。未 Force Push。
- 原第一輪文件與 `docs/evidence/sp910/` 均保留。第二輪新增 Edge 截圖位於 `docs/evidence/sp910/round2/preflight-home.png`。
- 預檢時曾確認 SP-910 MySQL 與 pgvector 容器名稱、loopback 綁定與獨立 named volume；當時 PostgreSQL readiness 成功。結束檢查時 Docker Desktop named pipe 回報拒絕存取，無法再次確認目前容器狀態。未連接、停止或修改其他容器與 Volume。
- 原 Backend 程序記憶體環境曾確認 `demo` profile、專用 MySQL／PostgreSQL database、專用文件目錄、Google GenAI Chat、Embedding `gemini-embedding-001`／768 維及 generation `gemini-embedding-001-768-v1`。Chat Model ID 為 `gemini-3.6-flash`。只確認 API 憑證存在及 Provider 曾配置，未讀出或記錄 Secret 值。舊程序停止後，無法從受支援的安全注入方式恢復完整憑證組。Repository `.env` 含空白 placeholder 且 profile 不是 `demo`，不能用來啟動驗收服務。沒有猜測或覆寫資料庫認證。

### 第二輪案例與實際結果

| 案例 | 預期 | 第二輪實際結果 | 狀態 | 證據／耗時 |
|---|---|---|---|---|
| R2-ENV-01 專用 DB 容器 | 專用 MySQL、pgvector 可用 | 預檢時容器存在；結束時 Docker API 因 named pipe 權限拒絕而無法再次確認 | PARTIAL | 預檢結果；未修改容器或 Volume |
| R2-BROWSER-01 Edge UI 基本連線 | 真實 Browser 首頁可載入 | Windows 主機 Microsoft Edge + Playwright headed 實際載入 `http://127.0.0.1:5173/`，HTTP 200 並導向 `/login`，頁面可見登入欄位與說明；保存真實畫面 | PASS（僅首頁載入） | `round2/preflight-home.png` 與同名 JSON；2026-10-15 19:56:48 Asia/Taipei，瀏覽器步驟約 8.6 秒。未登入，未測 Dashboard、查詢或授權流程 |
| R2-BACKEND-01 合併後 Backend 啟動 | 新版 Backend 使用已核對 Demo 目標並可服務 | 舊 PID 23944 曾在 8081 運行並持有安全注入環境。以該環境啟動合併後程式後，Spring Boot 約 9.165 秒進入應用啟動，隨即於 `DemoDatasetCoordinator.initializeBaseline` 拋出 `IllegalStateException: Demo dataset is partial or has a different version; run the guarded reset`，程序非零退出。未執行 Reset | BLOCKED | 非敏感啟動紀錄在本機 Temp，不納入 Repository；錯誤時間 2026-10-15 19:49:55 +08:00 |
| R2-VERIFY-01 Backend 回歸 | `backend\\mvnw.cmd -B --no-transfer-progress verify` 成功 | BUILD SUCCESS；549 tests，0 failure、0 error、1 skipped；耗時 16:35，完成於 2026-10-15 19:48:07 Asia/Taipei。此為 Backend regression，不是 Real Provider 或 Browser E9 | PASS（Backend regression） | 本機 Maven 輸出；Semantic RAG 整合測試不構成 Gemini E9 證據 |
| R2-GEMINI-01 五份文件與真實 Gemini | 使用有效 Provider、現行 Gate、Edge 查詢及正確引用 | Backend 未能持續運行；無法安全恢復有效環境組，也未重新核對五份文件出站審核、索引及 Gate | BLOCKED | 無第二輪 Query JSON／截圖；第一輪結果維持原狀，不重用 |
| R2-CROSS-01 跨語查詢 | 獨立英文資料集與中文來源實際互查 | 本輪 Backend 未就緒，未執行 | NOT RUN | 無第二輪證據 |
| R2-SUP005-01 SUP-005 UI 流程 | 五份基線 Source Query 全通過後再上傳 | 五題沒有在本輪驗證全數通過，不符合開始條件 | NOT RUN | 未建立或上傳正式 SUP-005 文件 |
| R2-RESET-01 Guarded Reset | 完整 E9 前置、快照與隔離再次確認後才執行 | 未執行 `scripts/reset-demo.ps1`；Marker／資料狀態異常正是程式阻擋啟動的原因之一，且本輪沒有可用的完整 Reset 前快照 | NOT RUN | 未清除或手動修改資料庫、文件目錄或 Volume |
| R2-RESTART-01 普通重啟 | 重啟後既有資料保留且 Reset Command 不執行 | 啟動被 Dataset Guard 拒絕，無法驗證一般重啟後的服務行為 | BLOCKED | Backend 啟動紀錄；Reset Command 未執行 |

### Browser 與啟動阻擋診斷

本輪採同一台 Windows 主機上的 Edge，而非遠端 in-app Browser。一般權限下的 Port 檢查未能列出 5173 Listener；提升權限後 Vite 回報 5173 已占用，主機 HTTP 與真實 Edge 均能取得登入頁 HTTP 200。因此可確認 Vite／主機網路／Browser 路徑可用。Backend 8081 已退出且未恢復。Edge 畫面只到登入頁，不能視為 Browser E2E 或登入驗收。結束時 Docker named pipe 不可用。

Backend 啟動錯誤明確指出：Demo Dataset Marker 狀態並非完整的目前版本，初始化器拒絕啟動並要求正式 Guarded Reset。這是資料狀態／初始化 Guard 阻擋，不是 Gemini Provider 的測試結果。因使用者要求不可為登入或啟動而直接 Reset，且無法保存可再次核對的 Reset 前快照，本輪停止於此。沒有修改 `DemoDatasetCoordinator`、Marker、Guard、SQL 資料或安全政策。

### 解除條件

需在具 Docker Desktop 存取權的 Windows 使用者工作階段唯讀核對 SP-910 專用容器，並透過核准的安全 Secret 注入流程重新提供 Demo DB、JWT、Demo Account、Semantic DB 與 Gemini 憑證；不得從聊天、Log 或 Repository 取得值。之後先保存目前 Marker、Demo 業務狀態、文件、Semantic 索引／核准／Gate 的完整快照，再依 Runbook 與使用者確認的隔離目標處理 partial／version mismatch。登入、Gemini、五份來源題、跨語、SUP-005、Reset 前後驗收均待解除後重新實測。

本輪結論為 **SP-910 E9 BLOCKED — PARTIAL EVIDENCE AVAILABLE**。549 項 Backend regression 測試通過及 Edge 首頁載入只代表各自範圍的結果。沒有宣稱 Real Gemini、Semantic Gate、五角色流程、SUP-005、Reset 或重啟驗收通過。

## 第三輪 E9 驗收（合併 #121 後的新隔離 Demo）

**整體狀態：SP-910 E9 FAIL — REPRODUCIBLE FINDINGS**

本輪沿用 SP-910 分支，保留第一、二輪記錄及其證據。新環境使用正式 Demo 初始化，真實 Edge、Google GenAI Chat／Embedding 與專用 pgvector 執行驗收。五份基線已建立 Gate；品質隔離查詢雖取得正確文件來源，但答案漏列必要條件，因此是實際 FAIL。依前置規則，SUP-005 與 Reset 不執行。

### Git、舊環境診斷與第三輪隔離資源

- Repository：`liujiawei-tw/supplypilot`；分支：`codex/sp-910-real-e9-acceptance`。
- 第三輪開始時分支 HEAD：`90ff068d431da761901eae027c11787ea841476e`；`origin/main`：`363f8639e56aea120ab756549dc90226a4ebd7c1`。該 main 已包含 PR #121；#120 已 Closed。第三輪實測報告與證據已提交並一般推送至原分支（首個證據提交 `a14363e080f944dc3b381a2444346d1e6e6878aa`）。
- 舊 SP-910 資料庫 Marker 的唯讀查詢結果為 `SP-901 / sp-905-zh-tw-v1 / 2026-10-15 / COMPLETE`。五份中文 Seed 文件、既有額外英文測試文件及操作後 PO 均仍在舊專用環境。先前啟動阻擋的可實證設定原因是 `.env` 的 `DEMO_DATASET_VERSION=sp-901-v1`、`SPRING_PROFILES_ACTIVE=local`，以及其資料庫名稱／密碼欄位與舊容器實際目標不一致；不是 Marker 損壞，也沒有執行 Reset。為保留舊環境與前兩輪證據，本輪另建全新專用環境。
- 新資源：MySQL 容器 `supplypilot-sp910-r3-mysql`、資料庫 `supplypilot_sp910_r3_demo`、Volume `supplypilot-sp910-r3-mysql-data`、loopback port 13317；PostgreSQL／pgvector 容器 `supplypilot-sp910-r3-pgvector`、資料庫 `supplypilot_sp910_r3_semantic_demo`、Volume `supplypilot-sp910-r3-pgvector-data`、loopback port 15440。文件根目錄為 `backend/data/sp910-r3-demo-documents`。各項皆與前兩輪分離，沒有刪除、覆寫或重新掛載舊資源。
- 第三輪使用新的隨機測試 DB／Demo/JWT Secret，DPAPI 加密檔存於 Repository 外且 ACL 限制於目前 Windows 身分；Gemini Key 使用已存在的本機安全來源，僅載入 Backend 程序環境。沒有把值輸出或提交。臨時環境檔已移除。
- 第三輪啟動時 Backend 使用 `demo` profile、明確 allowlist 與同名 MySQL／PostgreSQL 目標、獨立 Flyway 目標及文件目錄；Health 回 HTTP 200。正式初始化結果為 Marker `SP-901 / sp-905-zh-tw-v1 / 2026-10-15 / COMPLETE`，Users 5、Materials 12、Suppliers 6、SupplierMaterial 30、Demands 12、Purchase Orders 10、Documents 5、Chunks 5。PO 分布 DRAFT 2、APPROVED 2、ORDERED 3、RECEIVED 2、REJECTED 1。五份文件均 READY、Version 1.0；沒有 Seed SUP-005。
- 完成 Gate 後以 `SEMANTIC_READ_ENABLED=true` 正常重啟 Backend；健康檢查仍為 200，啟動記錄為 `DEMO_DATASET_ALREADY_COMPLETE`，沒有執行 Reset，資料集未重新 Seed。這只驗證本輪一次正常 Backend restart。工作階段後續 Docker Desktop named pipe 回報 permission denied，因此收尾時無法再次讀取容器狀態；不可將開始時的存活狀態推定為現在仍存活。

### Browser、出站審核、Backfill 與 Gate

- 使用 Windows Microsoft Edge headed Playwright，於真實 UI 以 `admin` 登入，HTTP 200，Dashboard 顯示系統管理員導覽；Materials 頁讀取 MAT-001 成功。截圖及 JSON：`docs/evidence/sp910/round3/r3-login-page.png`、`r3-after-login.png`、`r3-login.json`、`r3-materials.png`、`r3-materials.json`。
- ADMIN UI 顯示五份 Baseline 文件；逐一執行真實 Outbound Approval，五份均回 HTTP 200、Version 1.0、Reviewer ID 5、狀態 APPROVED。各 Source Hash 及實際回應見 `r3-outbound-approvals.json`；五份操作畫面為 `r3-outbound-doc-1.png` 至 `r3-outbound-doc-5.png`。
- ADMIN UI 正式 Backfill 使用 `afterId=0, limit=25`。已核對實際 PostgreSQL 索引為 `gemini-embedding-001-768-v1`、READY 5、5 chunks；UI 回應 `processed=5, ready=5, failed=0, skipped=0, requiresReview=0, lastId=5, morePossible=false`。保存的 UI 回應是初次索引完成後的一次冪等確認，耗時 1.752 秒；不得把此確認耗時誤作初次 Embedding 呼叫時間。模型 `gemini-embedding-001`、維度 768。證據 `r3-backfill.json/png`。
- Readiness 回 `passed=true`、READY 5、FAILED／SKIPPED／REQUIRES_REVIEW／PENDING 均 0、findings 空，耗時 2.612 秒；Gate Audit 持久化 `passed=true, auditId=1`，耗時 2.268 秒。Readiness／Audit 回應與畫面分別見 `r3-readiness.json/png`、`r3-gate-audit.json/png`。
- Semantic Read 在上述 Gate 通過後才顯式開啟；Gemini Chat 設定為 `google-genai`／`gemini-3.6-flash`。六次查詢均在 Edge AI Assistant 發出，Browser 保存實際回應與畫面。Embedding 設定為 `google-genai`／`gemini-embedding-001`／768／`gemini-embedding-001-768-v1`。

### 六組真實 Gemini Browser 查詢

下表時間為各 Browser 查詢的實測耗時。各來源完整 JSON 與真實畫面為 `docs/evidence/sp910/round3/r3-query-01` 至 `r3-query-06` 的 `.json`／`.png`。來源 offset 依 Java UTF-16 字串索引核對原始 Seed 文件；文件來源 excerpt 與其宣稱的完整 Chunk 範圍相符。

| 案例 | 實際結果 | 狀態 | 耗時 |
|---|---|---|---:|
| SUP-001 MAT-001 交期 | HTTP 200 `ANSWERED`；回答 ORDERED 後 5 個曆日；Document 1、v1.0、chunk 0、offset 0–336，原文核對一致 | PASS | 10.222 秒 |
| SUP-002 報價有效期 | HTTP 200 `ANSWERED`；回答自開立日起 30 個曆日；Document 2、v1.0、chunk 0、offset 0–343 | PASS | 7.785 秒 |
| SOP 收貨角色 | HTTP 200 `ANSWERED`；回答包含「倉庫人員」，引用 SOP Document 3、v1.0、chunk 0、offset 0–383；另有 Document 5 輔助來源。回答冗長，仍有額外段落 | PASS（內容命中；品質註記） | 7.956 秒 |
| MAT-001 單位／儲存 | HTTP 200 `ANSWERED`；回答 KG、陰涼乾燥處；Document 4、v1.0、chunk 0、offset 0–268。另帶入 Inventory 工具的目前庫存 300 KG | PASS（來源正確；有額外事實） | 9.532 秒 |
| 品質隔離條件 | HTTP 200 `ANSWERED`，但只說「須將該批隔離」，沒有列出缺少批次識別、封條破損、污染或物料不符。Document 5、v1.0、chunk 0、offset 0–269 的原文確實包含四項條件；來源正確但答案不完整 | **FAIL** | 7.806 秒 |
| 冷凍海運保險費率證據不足 | HTTP 200 `INSUFFICIENT_EVIDENCE`、answer null、sources 空；沒有編造答案或無關引用 | PASS | 11.506 秒 |

品質隔離題的 Evidence 是文件實際原文，故此 FAIL 是 Gemini 生成答案不完整，不是找錯文件或 Offset 不一致。保留原始失敗回應，沒有重試、改商業條款、修改政策或偽造來源。Gemini Chat 六次 Browser 請求均實際使用 `google-genai`；沒有 Fake Provider。證據只足以證明本地回應顯示 Provider／Model 設定和實際呼叫成功，未保存供應商原始內部 trace；不能聲稱已取得 Gemini 內部推理 trace。

### 後續項目狀態

| Case | 第三輪結果 | 狀態 |
|---|---|---|
| 舊 Demo Guard 診斷 | Marker 正確 COMPLETE；實際生效的舊設定版本、profile 與舊容器目標不符；未 Reset 舊資料 | PASS（診斷） |
| 新 Demo 初始化與五文件 | 正式初始化、基線數量及 READY 文件符合規格 | PASS |
| Browser 登入與 Dashboard／Materials 讀取 | 真實 Edge 登入與讀取成功 | PASS（此範圍） |
| 五份 Outbound Approval／Embedding／Readiness／Gate Audit | 五份 Approved、Backfill READY、Readiness 通過、Audit ID 1 通過 | PASS |
| 五份 Source Query | 前四題核心預期可回答，第五題漏列商業要求的四個條件 | **FAIL** |
| 獨立中英文跨語測試 | 尚未建立隔離的第二組 cross-language Dataset；當工作階段 Docker named pipe 回報權限拒絕，無法安全建立／核對其 MySQL、pgvector 與 Storage | BLOCKED；NOT RUN |
| SUP-005 Browser E2E | 品質隔離來源查詢未通過完整性條件，不符合先完成基線查詢再上傳的前置；未上傳、未解析、未建立第六份文件 | BLOCKED；NOT RUN |
| Guarded Reset | E9 前置未全通過；未執行 `scripts/reset-demo.ps1`，沒有清表、刪 Marker 或手動資料庫操作 | NOT RUN（依規則停止） |
| Reset 後 Gate 重建 | Reset 未執行 | NOT RUN |
| 採購五角色 Browser 流程 | 本輪重新建立的 r3 Dataset 尚未執行該流程；第一輪結果不能挪用為本輪 PASS | NOT RUN |
| Backend regression／GitHub CI | 本輪另行啟動 `backend\\mvnw.cmd -B --no-transfer-progress verify`，Maven 進入 Surefire 後停在 `SpringAiDraftProposalGeneratorTests` 且長時間無新輸出，未產生本輪完成摘要；為避免無限等待而中斷，故本輪回歸為 BLOCKED，不能記 PASS。可確認的最近完整結果仍是同一合併 #121 程式碼第二輪 `mvnw verify`：549 tests、0 failure、0 error、1 skipped。`gh` CLI 不存在；對 GitHub Actions API 的唯讀查詢回 404，故無法確認本輪提交的 CI。既有 Actions #38691504272 Backend／Frontend jobs 為 SKIPPED，不是完整回歸 PASS | BLOCKED（本輪命令未完成） |

### Findings 與交接

**P0 — 品質隔離查詢的 Gemini 答案不完整。** 重現問題為「品質要求中哪些狀況需要隔離批次？」；預期需明確列出批次識別缺失、封條破損、污染或物料不符。第三輪真實 Edge／Gemini 回答只稱須隔離，雖引對 Document 5 原文，未回答四種狀況。來源 offsets 正確，但使用者問題仍未得到完整答案。應在 Issue 範圍內另行審查產品修正，修復後重跑該題及全部五份 Source Query；不得為此降低答案要求。

**P1 — 本輪尚未完成剩餘 E9 Gate。** Cross-language、SUP-005、Guarded Reset、Reset 後 Gate 重建及 r3 採購流程均無第三輪 PASS。SUP-005 與 Reset 因前置 FAIL 而停止；Cross-language 因 Docker 權限無法建立獨立資料集而未執行。開始時建立的 r3 環境與 Browser 證據仍在；結束時 Docker Desktop API 回報 `permission denied`，5173／8081 無法在該次收尾命令取得 Listening 資訊。不可將 Browser 證據等同服務目前仍運行。

#45 仍需在其本機 Docker 與 AWS 各自驗證：真正安全 Secret 注入、資料持久化、Gemini／pgvector、Reset 後人工或自動 Gate 重建、普通重啟資料保留、Browser 流程與實際復原時間。本輪沒有執行 Reset，也沒有宣稱午夜 Reset 後 AI 會自動恢復。

## 第四輪 E9 驗收（PR #123 合併後）

**整體狀態：SP-910 E9 BLOCKED — PARTIAL EVIDENCE AVAILABLE**

本輪沿用原驗收分支，將 `origin/main` 的 PR #123 Merge SHA `08b9d079dad4aaf42f90a61891e2794fbc888eee` 以一般 merge 合入；沒有改動產品程式碼、Demo 資料、Marker、文件、容器或 Volume。第一至第三輪記錄與證據均保留。第四輪證據位於 `docs/evidence/sp910/round4/`。

### Git 與環境

- Repository：`liujiawei-tw/supplypilot`；Branch：`codex/sp-910-real-e9-acceptance`。
- Round 4 合併後 HEAD：`cf068cf33c0d9b4f5e36e900fd097670720778a3`，父 Commit 為原驗收分支 `ac3ac34352a4274a11ed3b73223280f251eda4cf` 與 main `08b9d079dad4aaf42f90a61891e2794fbc888eee`。
- Windows 11 10.0.26200、Java 21.0.10、Node.js 24.15.0、npm 11.12.1、Docker Engine 29.6.1。Docker 專用 r3 MySQL／pgvector 容器可透過具權限的 Windows 工作階段唯讀查詢；port 僅綁定 loopback：13317、15440。
- Demo Storage：`backend/data/sp910-r3-demo-documents`，五個原始檔。資源身份與第四輪唯讀快照詳見 `docs/evidence/sp910/round4/preflight.json`。沒有使用或觸碰其他 SP-910 容器／資料庫。
- Vite：既有 Node 程序 PID 30428 在 `127.0.0.1:5173`，主機 HTTP 200。8080 與 8081 有既存 Java Backend Listener，兩者 `/actuator/health` 均 HTTP 200。兩者對 `Origin: http://127.0.0.1:5173` 的 CORS preflight 都回 200 且明確允許該 Origin。這只能確認存活及 Origin 路徑；本輪沒有證據可將任一 Backend Listener 的運行設定對應到 r3，未呼叫業務 API。
- Codex in-app Browser 實際載入 `http://127.0.0.1:5173/login`，畫面顯示登入表單；另保存 Microsoft Edge headless 頁面截圖 `round4/browser-login-page.png`。CUA 當前沒有可選的 Edge 視窗，因此本輪未在 Edge 中完成登入或 UI 操作。首頁截圖不等於 E9 Browser E2E。

### r3 資料唯讀快照

| 項目 | 實際觀察 | 本輪判定 |
|---|---|---|
| Demo Marker | `sp-905-zh-tw-v1 / COMPLETE`，Baseline date `2026-10-15` | 快照；未初始化或重置 |
| Relational baseline | Users 5、Materials 12、Suppliers 6、SupplierMaterial 30、Demands 12、POs 10 | 快照符合數量 |
| PO 狀態 | DRAFT 2、APPROVED 2、ORDERED 3、RECEIVED 2、REJECTED 1 | 快照符合分布 |
| MAT-001 Inventory | 300 KG | 初始基線快照 |
| 五份中文文件 | Metadata 1–5，READY／Version 1.0／Outbound APPROVED；Storage 五個原檔 | 快照符合五份基線 |
| SUP-005 | MySQL metadata 無此文件 | 第六份正式文件未建立 |
| Semantic Index | `gemini-embedding-001-768-v1`；Model `gemini-embedding-001`；768 維；READY 五份 | 索引快照，未重新 Backfill |
| Gate Audit | Audit #1 passed，ready 5，其他狀態 0；時間 `2026-10-15 20:22:58 +08:00` | 舊 Audit 快照；本輪未重跑 Gate 或 Query |

Gate 舊 Audit 雖在 PostgreSQL 中仍為 passed，本輪沒有重新核對 Source Hash、目前 Backend 版本及 Gate validity，故不得以此宣稱第四輪 Gate 或查詢 PASS。

### 安全憑證與 Browser 阻擋

目前 PowerShell 的 Process／User／Machine 環境範圍都沒有繼承所需變數；Repository `.env` 存在且包含 DB／JWT／Demo credential 變數名稱，但沒有讀取任何值。其非敏感設定選擇 `SPRING_PROFILES_ACTIVE=local`、`supplypilot`／`supplypilot_semantic`，而 Demo allowlist 卻是 `supplypilot_demo`／`supplypilot_semantic_demo`，目標不一致，因此沒有載入或使用該設定。Windows Credential Manager 中沒有 SupplyPilot／SP-910／Gemini 相關 target；`%LOCALAPPDATA%\SupplyPilot` 沒有檔案。唯一找到的 Temp `.cred` 有廣泛 ACL，且建立時間早於 r3 資源；沒有讀取、解密或使用該檔。沒有猜測密碼，也沒有輸出或讀取既有 Java 程序環境／命令列中的 Secret。

本輪初始檢查（00:20 Asia/Taipei）曾看到 Vite 與兩個 Backend Listener 回應；到 00:46 再檢查時，5173、8080、8081 均無 Listening／HTTP 回應。in-app Browser 的分頁仍保留登入表單，但在服務停止時重新載入沒有證明主機可達，不能視為新鮮頁面或成功登入。沒有提交登入請求。

即使在 `.env` 設定 `DEMO_ACCOUNT_PASSWORD`，`DemoDatasetCoordinator` 只在建立全新 Demo 時使用它產生帳號密碼雜湊；Marker 已是 COMPLETE 時會直接跳過初始化，不會更新既有帳號密碼。不得為了登入而 Reset。

| 案例 | 預期 | 本輪實際 | 狀態 | 證據 |
|---|---|---|---|---|
| R4-PREFLIGHT-01 專用資源 | r3 隔離且可唯讀核對 | 容器、loopback port、資料庫 Marker／基線、五文件 Storage、pgvector Index／Audit 均已唯讀核對；未做寫入 | PARTIAL | `round4/preflight.json` |
| R4-BROWSER-01 UI 載入 | 真實 Browser 顯示並操作登入頁 | 初始階段載入 `/login` 並保存 Edge headless 截圖；00:46 服務已停止，in-app Browser 分頁殘留登入表單，未登入或執行操作 | PARTIAL | `round4/browser-login-page.png`；主機連線探測紀錄 |
| R4-GATE-01 品質隔離條件 | Edge＋Gemini 最終答案完整包含四項條件，正確來源及 offset | 未登入，沒有 Query JSON／Source／Provider Trace | BLOCKED | 缺少安全 Demo 帳密與可核對 Backend 環境 |
| R4-BASELINE-02 五個其他 Source Query | 五題都在 Browser 真實查詢並核對 Source | 未執行 | BLOCKED | 同上 |
| R4-CROSS-01 跨語 | 獨立資料集雙向跨語查詢 | 未建立隔離測試資料集，未執行 | NOT RUN | 基線查詢前置未通過，且無安全 Backend Secret |
| R4-BUSINESS-01 五角色流程 | 五角色採購 Browser E2E | 未執行 | BLOCKED | 無 Demo 帳號密碼／登入未完成 |
| R4-SUP005-01 UI 上傳與索引 | 五份基線查詢全部 PASS 後才上傳 | 未上傳；基線查詢未通過前置 | NOT RUN | 前置條件未滿足 |
| R4-RESET-01 Guarded Reset | 完整 E9 前置通過後正式 Reset | 未執行正式 Reset；沒有修改資料 | NOT RUN | E9 前置不全，不符合 Reset 條件 |
| R4-VERIFY-01 Backend regression | `backend\mvnw.cmd -B --no-transfer-progress verify` 完成成功 | Docker-enabled elevated run 搭配 Mockito 5.23.0 Java agent 後 BUILD SUCCESS；566 tests，0 failures、0 errors、1 skipped；耗時 7:17，完成於 2026-10-16 00:43:38 Asia/Taipei。Testcontainers 使用暫存 MySQL／PostgreSQL，不操作 SP-910 r3 持久化資料。先前兩次執行受 Mockito 動態 Agent／Docker 權限環境影響，不作為最終回歸結果 | PASS（Backend regression） | 本機 Maven 輸出；Mockito agent 僅由命令列指定，未修改 Repository 設定 |

### 本輪結果與解除條件

本輪可確認的是：r3 五文件資料與 Semantic 索引快照曾經唯讀核對；初始時 Vite 與兩個 Backend Listener 存活並接受 Vite Origin 的 CORS preflight，但 00:46 再檢查時服務已停止。沒有完成登入、Gemini Chat／Embedding、六題來源查詢、跨語、五角色流程、SUP-005、Reset 或 Reset 後 Gate 恢復。沒有 API 業務呼叫，沒有寫入或破壞性資料操作。

解除登入阻擋需要：以安全方式取得並注入明確指向 r3、與 allowlist 相符的 MySQL／pgvector 設定及憑證、JWT Secret；安全啟動前端與 Backend。由於現有 COMPLETE Dataset 的帳號密碼雜湊不會隨 `DEMO_ACCOUNT_PASSWORD` 環境值變更，須使用既有帳號密碼或另行核對正式允許的帳號復原流程；不可為了套用新密碼而 Reset。真實 Gemini E9 另需有效 Gemini API Secret 與正確 Provider 設定。之後從品質隔離 Browser 查詢重新開始；只有所有前置 Gate 通過才進入 SUP-005 與 Guarded Reset。

第四輪結論：**SP-910 E9 BLOCKED — PARTIAL EVIDENCE AVAILABLE**。本輪沒有因舊 Gate Audit、既有 Health、舊回合證據或單純 Browser 登入頁而宣稱 E9 PASS。

## 第四輪阻擋解除增補：r4 全新隔離 Demo（2026-10-16，Asia/Taipei）

本節是對第四輪紀錄的追加，不取代或改寫前述 r3 狀態及失敗證據。工作分支為 `codex/sp-910-real-e9-acceptance`，本次執行時 HEAD 為 `fe8b598c4e244e04eede3c434d7c1c131ced00f7`。依授權建立全新 r4 資源；r3 Marker 為 COMPLETE 的資料庫、容器、Volume、五份文件、索引及 Gate 均未重置、覆寫或修改。

### r4 隔離環境與初始化

| 項目 | r4 實際值／結果 |
|---|---|
| MySQL | `supplypilot-sp910-r4-mysql`，`127.0.0.1:13327`，專用 Volume `supplypilot-sp910-r4-mysql-data`，DB `supplypilot_sp910_r4_demo` |
| PostgreSQL／pgvector | `supplypilot-sp910-r4-pgvector`，`127.0.0.1:15450`，專用 Volume `supplypilot-sp910-r4-pgvector-data`，DB `supplypilot_sp910_r4_semantic_demo` |
| 文件儲存 | `C:\Users\z0976\AppData\Local\SupplyPilot\sp910-r4-demo-documents` |
| Backend／Frontend | Backend `127.0.0.1:8184`、Vite `127.0.0.1:5174`；兩者持續 Listening，Health／登入頁 HTTP 200 |
| Profile／Guard | `SPRING_PROFILES_ACTIVE=demo`；兩個 Demo allowlist database 名稱與實際連線目標相符；沒有使用 Repository `.env` |
| Secret 保存 | 新隨機 DB 密碼、JWT Secret、Demo Account Password 保存於 Repository 外的 Windows DPAPI CurrentUser 保護檔；受限 ACL。文件及證據不含 Secret 值 |
| Dataset | 正式 Seeder 從新空白 DB 初始化，Marker `SP-901 / sp-905-zh-tw-v1 / 2026-10-16 / COMPLETE`；Users 5、Materials 12、Suppliers 6、SupplierMaterial 30、Demands 12、POs 10；PO 狀態為 DRAFT 2、APPROVED 2、ORDERED 3、RECEIVED 2、REJECTED 1 |
| 文件 | Backend 建立的五份 Baseline 文件均 READY、Version 1.0，Storage 內五份原檔；未建立 SUP-005 第六份文件 |
| Semantic 初始狀態 | 新 pgvector DB 未複製 r3 的索引或 Gate；尚未執行 Gemini Backfill／Readiness／Gate Audit |

建立 r4 時 Backend 僅設定為 `AI_CHAT_PROVIDER=none`、`AI_EMBEDDING_PROVIDER=none`、Semantic Read 關閉，確保不會誤繼承或使用 Repository `.env`。此設定不構成 Gemini 測試。

### 安全憑證核對與真實 Browser 結果

Process／User／Machine 環境與 r4 DPAPI Secret 名稱清單皆沒有 `GEMINI_API_KEY`、`AI_CHAT_PROVIDER`、`AI_EMBEDDING_PROVIDER`、`SEMANTIC_READ_ENABLED` 或 `SEMANTIC_GENERATION_KEY`。Credential Manager 的 `gemini:antigravity` 僅能辨識為來源不明確的舊式 Antigravity target，無法證明它是授權的 Gemini Developer API Key；未讀取、解密或拿來當 API Key。既有廣泛 ACL 的 Temp `.cred` 也未讀取。故唯一已確認的外部憑證阻擋是：**需要使用者原已授權、可驗證的 Gemini Developer API Key 安全來源**。不要在對話貼 Key；請將它以本機互動式安全輸入方式寫入 r4 DPAPI Secret，再重啟 Backend 並核對 Provider 初始化。不得以 Mock／Fake Provider 代替。

真實 Microsoft Edge（headed Playwright，隔離 Profile）已完成 Buyer 登入：登入 API HTTP 200，畫面顯示 Buyer Dashboard；應用程式載入 `/api/v1/materials` 回 HTTP 200。Planner 登入與 Dashboard 亦有保存的真實 Edge 畫面。證據為 `docs/evidence/sp910/round4-resolution/r4-buyer-dashboard.png`、`r4-demo-planner-login-dashboard.png`、`r4-browser-procurement.json`。物料選擇的 Browser 自動化在 `/recommendations` 表單未成功完成選項選取，沒有建立 PO；採購核准、下單及收貨流程為 BLOCKED／NOT RUN。錯誤截圖 `r4-procurement-stop.png` 及執行紀錄保留作為可重現失敗證據，不將未完成動作標為 PASS。Edge 隔離 Profile 已從 Repository／證據目錄移除，避免留下登入工作階段資料。

| 案例 | 實際結果 | 狀態 | 證據 |
|---|---|---|---|
| r4 資料與資源隔離 | 全新專用容器、Volume、DB、Storage 與 Marker；r3 維持唯讀且未觸碰 | PASS（隔離／初始化） | 本節；r4 Browser／執行紀錄 |
| Backend／Frontend 可達 | 8184／5174 Listening；Health 與登入頁 HTTP 200 | PASS（可達性） | 本機唯讀 listener／HTTP 檢查 |
| 真實 Browser 登入／Dashboard | Edge Buyer 登入 HTTP 200；Dashboard 顯示 Buyer 角色；Materials API 200 | PASS（此範圍） | `r4-buyer-dashboard.png`、`r4-browser-procurement.json` |
| 五角色採購流程 | 推薦表單物料下拉選取未完成；未建立採購單 | BLOCKED | `r4-procurement-stop.png`、`r4-browser-procurement.json` |
| 真實 Gemini Chat／Embedding | 沒有已驗證且可用的授權 API Key；Backend 明確使用 none | BLOCKED | 本機 Secret-presence 記錄；此報告 |
| 五份文件出站核准／Backfill／Readiness／Gate | r4 尚未操作；沒有沿用 r3 Gate | NOT RUN | 新環境初始資料快照 |
| 品質隔離及其餘 Source Queries | 沒有 Gemini Provider／Semantic Gate，未執行 | BLOCKED | 本節 |
| 中英文跨語 | 未建立第二份受控文件／索引，未執行 | NOT RUN | Gate 前置未滿足 |
| SUP-005 UI Upload／Ingestion／Gate | 未上傳，避免未通過五份來源查詢前置 | NOT RUN | r4 文件清單只有五份 Baseline |
| Guarded Reset／Reset 後恢復 | 未執行 Reset；r4 目前保留完整初始 Baseline | NOT RUN | 無 Reset 命令執行紀錄 |

### Actions CI 與前端已知失敗

透過 GitHub Actions job API 核對：

| Run | Backend CI | Frontend CI | 結論 |
|---|---|---|---|
| [#38771906743](https://github.com/liujiawei-tw/supplypilot/actions/runs/38771906743) | SUCCESS，包含 `Run Maven verify` | SKIPPED | Backend CI PASS；不能稱完整 Backend＋Frontend 回歸 |
| [#38771907250](https://github.com/liujiawei-tw/supplypilot/actions/runs/38771907250) | SUCCESS，包含 `Run Maven verify` | SKIPPED | Backend CI PASS；不能稱完整 Backend＋Frontend 回歸 |
| [main #38765349851](https://github.com/liujiawei-tw/supplypilot/actions/runs/38765349851) | SUCCESS | FAILURE，Test step 失敗 | Frontend Build 因測試失敗跳過；保留為既有 main 失敗，不在 #117 驗收分支修改 |

main run 前端實際失敗案例為 `tests/sp806.test.tsx` 的「ignores a delayed archive response after switching documents」。Vitest 報告 21 個 test files passed、1 failed；180 tests passed、1 failed。失敗診斷為 Testing Library 在測試中找不到 accessible button `#21`。這是獨立前端測試問題，沒有改寫成成功，也沒有擴張 #117 範圍。

### 本次結論及下一步

本次已解除「缺少 Demo 登入帳密／服務未啟動」阻擋：r4 是可持續運作、專用且登入成功的新環境。仍未取得可驗證的 Gemini API Key；此外目前真實 Edge 採購流程在物料下拉選取處留下可重現的自動化失敗，故不能聲稱僅剩 Gemini 憑證這一個阻擋。五份 Gemini 查詢、跨語、SUP-005 與 Reset 必須在恢復真實 Provider 並完成 Browser 流程後接續。r3 未重置，r4 也未執行 Guarded Reset。結論仍為 **SP-910 E9 BLOCKED — PARTIAL EVIDENCE AVAILABLE**。

## r4 續驗增補：Edge 選料與採購流程／五份出站審核（2026-10-16，Asia/Taipei）

本節追加於第四輪紀錄後，不覆寫先前失敗紀錄。執行分支為 `codex/sp-910-real-e9-acceptance`，本次程式碼 HEAD 為 `53e3743290d69c224a3ea200e61969fb411e19d3`；未修改產品程式碼。r4 專用 DB／Volume／文件路徑持續沿用，沒有建立 r5、重新 Seed 或 Reset r3/r4。

### Browser 選料阻擋已解除（真正 Microsoft Edge headed Playwright）

首次定位曾遇到 Ant Design Select 的虛擬選項 DOM 節點不可見；這是 Browser locator／元件虛擬化交互問題，未修改產品程式。改用元件 accessible name `選取物料` 定位 combobox，展開後搜尋 `MAT-001`，以可見選項及鍵盤完成選取。真實頁面上的 Materials 查詢 HTTP 200，清單包含 MAT-001（id=1）；搜尋查詢亦 HTTP 200 並回傳同一物料。表單提交至 Recommendation Backend 的 HTTP 201 請求包含 `materialId=1`，日期 `2026-10-16` 至 `2026-11-15`。Backend 回傳 MAT-001、預期需求 1000 KG、安全庫存 200 KG、現有庫存 300 KG、在途 150 KG、淨需求 750 KG；推薦 SUP-002，數量 750 KG、評分 96.1。這證明 Form 保存了對應 Material ID 並取得真正 Backend Recommendation。

### 五角色 Browser 採購主線

以真正 Edge 操作 Planner、Buyer、Manager、Warehouse、Admin。Planner 登入與 Dashboard 可見；導覽到 `/recommendations` 顯示正式 403，符合角色權限。Buyer 從推薦結果建立 PO #11（`PO-20261016-000011`），Backend HTTP 201，狀態 DRAFT，關聯 Recommendation #1／Supplier Result #2，SUP-002，MAT-001 750 KG。Buyer 在 UI 補上預計交期 `2026-10-19`（PATCH 200）。Manager 於 `2026-10-16T01:17:44.662631300Z` 核准（HTTP 200）；Buyer 於 `2026-10-16T01:17:46.655624800Z` 標記 ORDERED（HTTP 200）；Warehouse 於 `2026-10-16T01:18:18.035580900Z` 收貨（HTTP 200）。重複收貨回 HTTP 409／`DUPLICATE_RECEIVING`。收貨後 Inventory API 與 Browser UI 都顯示 MAT-001 1050 KG，Stock Transaction #3 為 `PURCHASE_RECEIVE` 750 KG，Actor 為 Warehouse。

採購紀錄中有一個保留的自動化執行摘要為 FAIL：在 Buyer 標記 ORDERED 成功後，等待 Locator `getByText('已下單')` 因同時命中狀態標籤和 Modal 標題而 strict-mode 失敗。Backend 仍回 HTTP 200／ORDERED，後續 Warehouse Browser 流程完成且核對同一 PO；因此記錄為腳本 Locator 假失敗，不把它隱去，也不算產品業務失敗。日期更新、核准、下單、收貨各步驟及畫面證據分別保留。

### 五份基線文件 Outbound Approval

Admin 在文件管理 UI 檢視五份中文基線文件，確認 ID 1–5、Version 1.0、READY、標題及文件類型，並逐一透過確認 Dialog 執行正式 Outbound Approval。五個真實 Backend POST 均 HTTP 200，回傳 Version 1.0、Reviewer=ADMIN id 5、Status=APPROVED 及各自 canonical Source Hash。Hash 為 Backend `SemanticBackfillService` 按有序 Chunk tuple 計算，不能直接與原始檔案 SHA-256 比較；兩者已在證據中標明。每份文件均保存確認前後 Edge 截圖。此核准只證明 Outbound Approval，不代表 Embedding、Readiness 或 Gate 已完成。

| Case | 結果 | 狀態 | 主要證據 |
|---|---|---|---|
| R4-BROWSER-SELECT-01 Material API／搜尋／選取／Recommendation | Browser UI；Material id=1；推薦 750 KG；Backend HTTP 201 | PASS | `r4-browser-selection-retry.json`、`r4-browser-material-search-visible.png`、`r4-browser-material-selected.png`、`r4-browser-recommendation-result.png` |
| R4-BROWSER-ROLE-01 Planner 權限 | Planner Dashboard 可見；推薦路由顯示 403 | PASS（授權範圍） | `r4-browser-planner-role-check.json`、Planner 截圖 |
| R4-BROWSER-PO-01 Buyer Draft | PO #11，HTTP 201，DRAFT，Recommendation 關聯 | PASS | `r4-browser-draft-followup.json`、`r4-browser-draft-created.png` |
| R4-BROWSER-PO-02 Manager 核准／Buyer 下單 | APPROVED → ORDERED，兩次 HTTP 200；保存角色與時間 | PASS（Backend/UI 動作） | `r4-browser-procurement-through-order.json`、確認／完成截圖；另有保留的 Locator 假失敗 |
| R4-BROWSER-PO-03 Warehouse 收貨／重複收貨 | RECEIVED；重複 HTTP 409；Inventory 1050 KG；PURCHASE_RECEIVE 750 KG | PASS | `r4-browser-procurement-lifecycle.json`、收貨／庫存／異動截圖 |
| R4-OUTBOUND-01 五份文件審核 | 五筆 HTTP 200、APPROVED、Version 1.0、ADMIN reviewer | PASS | `r4-browser-admin-outbound-approvals.json`、每份確認與結果截圖 |
| R4-GEMINI-01 真實 Provider 與 Semantic | 尚未執行 Gemini API、Embedding、Backfill、Readiness、Gate 或 AI Query；未啟用 Semantic Read | BLOCKED／NOT RUN | 安全輸入提示已就緒；目前沒有從使用者取得 Key 的完成確認 |

證據檔保留了先前失敗與目前重試；`r4-browser-planner-role-check.json` 內的 `elapsedSeconds` 與開始／結束時間不一致，故不把該欄位作為耗時證據，僅使用原始時間戳。採購 JSON 中 ORDERED Locator 的失敗也依上述方式揭露，不覆寫原檔。

### 尚未完成與安全限制

五份文件已核准出站，但尚未使用 Gemini Embedding Provider Backfill，沒有 Readiness／Gate Audit、沒有 Semantic Read、沒有六題 Browser Query 或跨語測試。尚未執行 SUP-005 上傳、索引、查詢；尚未執行 Guarded Reset。Reset 仍須等所有 E9 前置測試 PASS，並在當下再次核對 r4 專用連線目標及完整快照後才可進行。

真實 Gemini Developer API Key 不存在於已核對的 r4 DPAPI Secret；已準備 Repository 外、Windows `Read-Host -AsSecureString` 的 CurrentUser DPAPI 安全寫入流程，不接受聊天貼 Key、不讀取來源不明的舊憑證。只有使用者在本機終端完成輸入後，才會驗證 Key 存在（不讀出值）、以相同 Windows 使用者安全啟動 Backend，並對實際 Gemini Chat／Embedding 進行有限呼叫。直到完成該流程前，Gemini／Semantic E9 保持 BLOCKED；不得使用 Fake Provider、沿用 r3 Gate 或把本節的 Browser 採購 PASS 擴大成完整 E9 PASS。

## r4 環境變數別名與目前可達性更正（2026-10-16，Asia/Taipei）

先前只以 `GEMINI_API_KEY` 名稱判斷憑證是否存在，遺漏了 Repository 已明確支援的 `AI_API_KEY` 相容別名。`backend/src/main/resources/application.yml` 的實際設定為 `spring.ai.google.genai.api-key: ${GEMINI_API_KEY:${AI_API_KEY:}}`，因此兩個名稱皆合法；不是使用者設定錯誤。Spring Boot 不會自動載入 Repository `.env`，環境變數必須存在於 Backend 啟動程序環境中。此項更正不表示本輪已驗證環境中的 Key 或實際 Gemini Provider 呼叫。

Codex 命令執行環境回報的 `LOCALAPPDATA` 為隔離 Sandbox 路徑、r4 本機 port 探測無法連線；該環境檢查不能代表使用者 Windows 工作階段，也不能用來否定其 User／Machine 環境變數。透過 Codex In-app Browser 對本機頁面進行唯讀檢查，`http://127.0.0.1:5174/login` 可載入，`http://127.0.0.1:8184/actuator/health` 回傳 `status=UP`。此 Health 僅證明 Backend 可達，不代表 Gemini Provider 已啟用。當前 CUA Edge connector 回報 Edge Browser 不可用；因此本輪沒有把 In-app Browser 冒充 Microsoft Edge，也沒有以其取代要求的 Edge E2E 證據。

**目前 Gemini 狀態維持 BLOCKED／NOT RUN**：需確認 `AI_API_KEY` 或 `GEMINI_API_KEY` 已注入實際 r4 Backend 程序，並從真實 Gemini Chat／Embedding 操作證明 Provider 可用。不得把 Codex Sandbox 的環境變數結果當成使用者 Windows 結果，亦不得將 Backend Health UP 推定為 Provider PASS。

## r4 真實 Gemini／Semantic Gate／Browser 續驗（2026-10-16，Asia/Taipei）

本節是後續實測追加，保留上方歷史紀錄，不覆寫 r3／r4 先前狀態。本次本機程式碼 HEAD 為 `53e3743290d69c224a3ea200e61969fb411e19d3`，分支 `codex/sp-910-real-e9-acceptance`；沒有改產品程式碼，沒有重建 r5、重新 Seed 或 Reset r3／r4。r4 專用 MySQL、pgvector、Volume、Storage 與五份 Dataset 持續沿用。

### Secret、Provider 與隔離狀態

前文只使用 DPAPI Gemini Key 的方式不符合目前已確認的 Repository 設定。`application.yml` 支援 `GEMINI_API_KEY`，並以 `AI_API_KEY` 作為合法 fallback。經使用者指出後，r4 Repository 外的啟動器改為只讀取已存在的 `AI_API_KEY`，不載入 `.env` 的其他 DB／JWT 設定，不輸出 Key，也不寫入 Repository／證據。r4 DPAPI 檔未增加或改寫 Gemini Key。實際 Gemini Embedding Backfill 成功，是此設定已進入 Backend 並成功呼叫 Provider 的實證；之後五題 Chat 回應 HTTP 200，亦證明 Chat Provider 真正被呼叫。沒有使用 Fake／Mock Provider。

| 項目 | 實測值／狀態 |
|---|---|
| Backend／Frontend | r4 Backend `127.0.0.1:8184`、Vite `127.0.0.1:5174` 均仍 Listening；Actuator Health `UP`；Microsoft Edge headed CDP 可用。Backend 綁定 loopback，未公開到網路 |
| Chat Provider／Model | `google-genai`／`gemini-3.6-flash`（r4 Runner 設定）；真實 Chat API 有成功及失敗回應 |
| Embedding Provider | `google-genai`；模型 `gemini-embedding-001`、768 維、Generation `gemini-embedding-001-768-v1` |
| Semantic Read | 正式 Gate Audit 通過後明確設定 `SEMANTIC_READ_ENABLED=true` 並重啟 Backend；此次 Browser 文件搜尋取得真正 Semantic Source |
| Git／環境 | 程式碼 HEAD `53e3743290d69c224a3ea200e61969fb411e19d3`；r4 專用環境；日期 2026-10-16，Asia/Taipei |

### 真實 Embedding、Readiness 與 Gate

Admin 透過真正 Microsoft Edge 操作 Backfill，請求 `afterId=0, limit=25`，Backend HTTP 200、耗時 3,826 ms；處理 5 份、READY 5、FAILED 0、SKIPPED 0、REQUIRES_REVIEW 0、`lastId=5`、`morePossible=false`。Readiness HTTP 200、耗時 1,331 ms，`passed=true`、五份 READY、其餘狀態皆 0、`findings=[]`。之後從 UI 執行正式 Gate Audit，HTTP 200、耗時 1,393 ms，持久化 `passed=true`、`auditId=1`。Gate 通過後才明確啟用 Semantic Read 並重新啟動 Backend。

證據：`r4-browser-backfill-batch-01.json/.png`、`r4-browser-readiness-after-backfill.json/.png`、`r4-browser-gate-audit-result.json/.png`。這只證明 r4 五份文件的真實 Embedding／Gate；不代表 Chat 所有查詢通過，也不代表 Reset 後 AI 已恢復。

### 真實 Edge Browser／Gemini Chat 與 Source 結果

下列操作均在 Admin 已登入的 Microsoft Edge headed Browser 中透過 AI Assistant UI 提交，Backend 實際接收 `POST /api/v1/ai/query`。JSON 保留 UI 可見回答／引用、HTTP 狀態、耗時與實際畫面檔名。來源 Chunk 的 UTF-16 offset 以正式 `TextChunker` end-exclusive 契約核對；三份成功引用文件經 LF 正規化及移除尾端空白後長度，分別與 API 的 `0–383`、`0–343`、`0–269` 相符。

| Case | 實際結果 | 狀態 | Source／證據 |
|---|---|---|---|
| SUP-001 MAT-001 標準交期（首次） | HTTP 503，`AI_INVALID_RESPONSE`，4,292 ms；沒有 Answer／Source；trace `e7da8e59-46ba-4940-b98a-d6c2c00b817e` | FAIL | `r4-browser-query-sup001-after-true-gemini.json/.png` |
| SOP 收貨角色 | HTTP 200，7,508 ms；回答「倉庫人員」。Source #3、Version 1.0、Chunk 0、Offset 0–383，原文確實寫明只有倉庫人員可收取 ORDERED 採購單。另有品質文件 #5 的額外引用，該來源不是此角色答案所需證據，已保留作為來源範圍限制 | PASS（答案來源有效；附帶一筆非必要 Source） | `r4-browser-query-sop-role-after-true-gemini.json/.png` |
| SUP-001 MAT-001 標準交期（一次重跑） | SOP 題成功後重跑仍 HTTP 503，`AI_INVALID_RESPONSE`，3,444 ms；沒有 Answer／Source；trace `6c5b38be-d22a-4108-9074-3002af4a2e6e` | FAIL（可重現） | `r4-browser-query-sup001-retry-after-sop.json/.png` |
| SUP-002 報價有效期限 | HTTP 200，6,546 ms；回答自開立日起 30 個曆日。Source #2、Version 1.0、Chunk 0、Offset 0–343，原文支持答案 | PASS | `r4-browser-query-sup002-after-true-gemini.json/.png` |
| MAT-001 基本單位／儲存條件 | HTTP 503，`AI_INVALID_RESPONSE`，3,641 ms；沒有 Answer／Source；trace `d66d7b0a-845d-4482-89ea-f83a22ddf6f9` | FAIL | `r4-browser-query-mat001-storage-after-true-gemini.json/.png` |
| 品質隔離條件 | HTTP 200，6,075 ms；Backend Facts 回答缺少批次識別、封條破損、污染、物料不符四種條件。Source #5、Version 1.0、Chunk 0、Offset 0–269，原文完整支持四項 | PASS | `r4-browser-query-quality-isolation-after-true-gemini.json/.png` |
| 冷凍海運保險費率（證據不足） | HTTP 200，12,939 ms；UI 顯示「證據不足」，沒有答案、沒有 Source，未編造費率 | PASS（安全拒答） | `r4-browser-query-insurance-insufficient-after-true-gemini.json/.png` |
| 英文問題查中文文件：`How long is SUP-002's quotation valid?` | HTTP 200，6,132 ms；答覆 30 個曆日。Source 為中文 SUP-002 #2、Version 1.0、Chunk 0、Offset 0–343 | PASS（英文→中文） | `r4-browser-query-en-to-zh-sup002.json/.png` |

Provider 日誌只保留安全映射後的 outcome、延遲及 token 用量，不保留 Model 原文／Tool Arguments。此輪八次 Chat 執行記錄到 5 次 `success` 與 3 次 `ai_invalid_response`；三個失敗回應的 Token 均 unavailable。與 UI 耗時略有差異的是後端記錄延遲。日誌沒有足夠 Provider／Tool Trace 可辨識 `AI_INVALID_RESPONSE` 的精確內部分支；不得推定為特定 Gemini 原因或改寫預期答案。SUP-001 題經一次有理由的重跑仍失敗，且 MAT-001 儲存題亦失敗；目前不能宣稱 #117 的五份基線 Source Query 全通過。

### 本輪矩陣更新與未執行項目

| E9 項目 | 本輪狀態 | 說明 |
|---|---|---|
| 五角色採購流程 | PASS（已於同一 r4 記錄） | 推薦至 RECEIVED；Inventory 1050 KG、PURCHASE_RECEIVE 750 KG、重複收貨 409；詳前節 |
| 五份 Outbound Approval | PASS（已於同一 r4 記錄） | Admin UI 逐份核准、Backend 回傳 APPROVED／Version 1.0 |
| 真實 Gemini Embedding／Readiness／Gate | PASS | 五份 READY，`passed=true`，持久化 Audit ID 1 |
| 五份來源查詢 | FAIL | SUP-001 交期兩次 503、MAT-001 儲存條件 503；其他三項通過 |
| 冷凍海運費率不足證據 | PASS | HTTP 200 `INSUFFICIENT_EVIDENCE` UI，沒有推測答案或來源 |
| 英文→中文跨語 | PASS | 英文 SUP-002 問題命中中文來源 #2 |
| 中文→英文跨語 | NOT RUN | 需額外英文文件及可控、可清除資料階段；現有五份基線查詢仍有 FAIL，不新增文件污染 r4 |
| SUP-005 Browser Upload／Ingestion／索引 | BLOCKED | 五份 Baseline Source Query 全部 PASS 的既定前置不滿足；本輪未上傳第六份文件 |
| Guarded Reset／Reset 後 Gate 恢復 | NOT RUN | E9 前置未全通過，沒有執行破壞性 Reset；r4 目前保留採購操作後狀態與五份已索引文件 |
| Backend／Frontend CI | NOT RUN（本輪證據更新尚未觸發 CI） | 此前 docs/evidence-only Actions 的 Backend／Frontend job 為 SKIPPED；不得稱完整回歸 PASS |

本輪真實實測結論為 **SP-910 E9 FAIL — REPRODUCIBLE FINDINGS**，同時 SUP-005、跨語中文→英文及 Reset 項目仍受前置／隔離條件限制。阻擋結果不是環境憑證不存在：`AI_API_KEY` 別名已被正確使用、真實 Gemini Chat／Embedding 均有成功呼叫證據。需要後續針對三個 `AI_INVALID_RESPONSE` trace 取得安全的 Provider／Tool 層診斷並修復或另行追蹤；在根因未證實前不修改 #117 的安全政策、Business Rule 或預期答案。r3 未受影響，r4 未 Reset。
