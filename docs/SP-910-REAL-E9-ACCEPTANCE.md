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
