# SP-910 真實 E9 驗收紀錄

## 本次結果

**SP-910 E9 BLOCKED — PARTIAL EVIDENCE AVAILABLE**

本紀錄只涵蓋 2026-10-15 在本機檢查或真正執行的項目。沒有將既有 CI／測試文件或其他 SHA 的結果套用為本次 E9 證據。本次只連線到新建且唯一命名的 SP-910 資料庫／Volume／文件 root；既有 Volume 均未操作。Guarded Reset 尚未執行。

## 任務與 Git 基線

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#117 SP-910](https://github.com/liujiawei-tw/supplypilot/issues/117)，GitHub 查核狀態：OPEN（2026-10-15）
- 依賴查核：[#43](https://github.com/liujiawei-tw/supplypilot/issues/43)、[#112](https://github.com/liujiawei-tw/supplypilot/issues/112)、[#110](https://github.com/liujiawei-tw/supplypilot/issues/110) 均 CLOSED
- GitHub API 確認 `main` 最新 SHA：`8aa1031158d8bc317057b356c1b38ba9c59efefa`（合併 PR #119）
- Feature Branch：`codex/sp-910-real-e9-acceptance`，已從最新 `main` 快轉建立
- 初次證據 Commit：`375be0f7bf0d2afd48736b6995d2823082f75d73`；此後更新本紀錄的 Commit 由 Git Head 記錄
- 驗收時間：2026-10-15 13:40 起 Asia/Taipei；實際步驟時間見 Dataset Marker 與測試輸出

## 環境預檢

| 項目 | 實際觀察 | 狀態 |
|---|---|---|
| Windows | PowerShell 回報版本字串 `10.0.26200`；Maven Wrapper 識別為 Windows 11、amd64 | 已盤點；版本標示有差異 |
| Java／Maven | Zulu OpenJDK 21.0.10；Maven Wrapper 3.9.16 | 可用 |
| Node.js／npm | Node 24.15.0；npm 11.12.1 | 可用 |
| Docker Compose | Compose CLI 5.3.0 | CLI 可用 |
| Docker Engine／Compose | Docker Desktop Engine 29.6.1；Compose CLI 5.3.0。一般沙箱命令起初無 named-pipe 權限，經核准的只讀 inventory／專用資源建立可用 | PASS（僅本機 Docker DB 服務） |
| 專用 DB／Volume | `mysql:8.4.11` + `supplypilot-sp910-mysql-data`；`pgvector/pgvector:pg17`（PostgreSQL 17.11）+ `supplypilot-sp910-pgvector-data`；容器 `supplypilot-sp910-mysql`、`supplypilot-sp910-pgvector`，只綁 `127.0.0.1:13316`／`127.0.0.1:15439` | PASS；實際 database 已唯讀查核 |
| Backend／Frontend | Backend Maven `0.0.1-SNAPSHOT` 以 demo profile 啟動並回過 `/actuator/health` HTTP 200；Vite 顯示 ready，但本機 Browser 無法連至 Vite | Backend startup PASS；Browser UI BLOCKED |
| Browser | Codex in-app browser 可操作；對 `127.0.0.1:5173` 回 connection refused，對 `localhost:5173` 回 timeout | Browser 工具可用；產品 UI E2E **BLOCKED** |
| Demo／文件儲存 | Dataset `sp-905-zh-tw-v1`、Marker COMPLETE、Baseline Date `2026-10-15`；storage root `backend/data/sp910-demo-documents` | Fresh Demo PASS |
| Gemini 設定 | `.env` 的 `AI_API_KEY` alias 以程序環境安全注入；值未輸出。Embedding `gemini-embedding-001`／768 維真實 smoke 成功。Chat `gemini-3.6-flash` 有設定預設，但未發出 Chat 請求 | Embedding PASS；Chat BLOCKED／NOT RUN |
| Git | 工作目錄原先乾淨；首次本機 fetch 受 `.git` ACL 拒絕，後經授權 fetch 成功，確認最新 main 並快轉分支 | 可用 |

既有 `.env` 的 DB／Reset 設定未被使用。只讀 Docker inventory 顯示主機另有其他專案 Volume 與既有 `supplypilot_supplypilot-mysql-data`；本次未掛載或查詢其內容。SP-910 使用上述全新明確命名的資源。Backend 的 Flyway log 與實際 MySQL `DATABASE()` 均指向 `supplypilot_sp910_demo`；PostgreSQL `current_database()` 為 `supplypilot_sp910_semantic_demo`；文件 root leaf 是 `sp910-demo-documents`。未設定 Semantic Read 為 true，Guarded Reset 尚未使用 confirmation。

### Fresh Demo 實際基線

正式 demo profile initializer 在 `2026-10-15 14:04:09 +08:00` 記錄 `DEMO_DATASET_COMPLETE key=SP-901 version=sp-905-zh-tw-v1 baselineDate=2026-10-15`。唯讀查詢的 Dataset Marker 為 `COMPLETE`（created_at `14:04:08.659222`，updated_at `14:04:09.695788`）。基線數量為 Users 5、Materials 12、Suppliers 6、SupplierMaterial 30、Demands 12、Purchase Orders 10；PO 為 DRAFT 2、APPROVED 2、ORDERED 3、RECEIVED 2、REJECTED 1。五份 baseline Documents 均 READY、Version 1.0；SUP-005 文件不存在。

五份正式 Seed 文件的儲存原檔逐一與 `samples/documents` 對應檔案計算 SHA-256 相同；每份有一個 Chunk，metadata／Storage／Chunk 內容一致，並以 Java UTF-16 字串索引契約核對 offsets。第六份 `ui-upload-sup-005-v1.txt` 未由 Seeder 建立。Seeder 的初始 ingestion 使用 `LocalLexicalEmbeddingProvider`；這不是 Gemini 索引，也未核准出站，因此本基線不能算 Semantic Backfill。

### Gemini Provider Smoke 實測界線

Gemini API key 僅由既有本機安全設定讀取並注入程序；沒有輸出值或寫入 Repository。官方模型文件確認本次實際使用的 Embedding model ID `gemini-embedding-001` 支援 768 維輸出（[Embedding 文件](https://ai.google.dev/gemini-api/docs/models/gemini-embedding-001)）；設定的 Chat 預設 ID 為 `gemini-3.6-flash`（[模型文件](https://ai.google.dev/gemini-api/docs/models/gemini-3.6-flash?hl=en)）。一筆合成文字的 opt-in `SemanticRealProviderSmokeTests` 真正呼叫 Embedding API 並成功，耗時 1.761 秒。該測試不是文件 Backfill，沒有寫入本次 pgvector DB；Gemini Chat 尚無成功或失敗請求，狀態 NOT RUN。

## 驗收矩陣

狀態定義沿用 Issue：PASS 代表指定環境的真實執行符合斷言；FAIL 代表真實執行不符合斷言；BLOCKED 代表必要服務、憑證或安全確認欠缺；NOT RUN 代表未執行；PARTIAL 僅代表有部分可用證據。

| 案例 | 預期／實際 | 狀態 | 操作／證據與限制 |
|---|---|---|---|
| A1 環境盤點 | Windows／Docker／Java／Node 版本可讀；服務埠由容器綁定核對，Frontend Browser 路由不可達 | PARTIAL | 本機 PowerShell、Docker、Browser；見環境表 |
| A2 隔離目標 | 新 DB 名稱、實際 MySQL／PostgreSQL DB、Flyway target、storage leaf、容器與全新 Volumes 均一致；Read Switch 初始 false | PASS | 新建容器／Volume + 唯讀 SQL；沒有碰觸既有 Volume |
| A3 中文 Fresh Demo | 基線筆數、PO 分布、Dataset Marker、Baseline Date、五份 READY 文件、無 SUP-005 均符合 | PASS | 正式 demo profile initializer；MySQL 唯讀查核。每份原檔 SHA 等於 sample，版本 1.0、各 1 Chunk、UTF-16 offset 與 extractor 正規化文本吻合 |
| A4 採購 Browser E2E | 五角色與完整採購主線、750→1050 KG、重複收貨 409；UI 服務未啟動 | BLOCKED | Browser 對 `127.0.0.1:5173` 實際連線受拒；沒有以 API 代替 |
| B1 真實 Gemini | 真實 Embedding API 呼叫成功；Chat 尚未呼叫；Semantic DB 專用但 UI 操作不可達 | PARTIAL | Repository `SemanticRealProviderSmokeTests` 1 test PASS，合成字串、`gemini-embedding-001`、768 維，未使用 Fake；Chat 預設 ID `gemini-3.6-flash` 未實測 |
| B2 五份文件出站審核 | Admin UI 逐份核對政策並取得審核結果；Admin UI 未啟動 | BLOCKED | 未操作、未假定 APPROVED |
| B3 真實 Embedding Backfill | Admin UI Backfill 到 `morePossible=false` 並核對 pgvector；未能操作 UI | BLOCKED | 僅有 provider smoke；未經 Backfill、未寫入 Semantic Index |
| B4 Readiness／Gate Audit／Read Switch | 真實持久化 `passed=true`、auditId 並顯式啟用 Read；未執行 | BLOCKED | 沒有 Gate 結果或 auditId |
| B5 五份 Source-Cited Query | 五個答案均有正確文件、Version、Chunk、UTF-16 Offset、Hash；未查詢 | BLOCKED | 沒有來源證據 |
| B6 AI 故障情境 | 證據不足、Gate、Gemini、pgvector 故障契約與核心 API 可用性；尚未在本次隔離服務注入故障 | BLOCKED | 未注入故障 |
| C1 第六份上傳前 Gate | 五份文件已核准／索引／Audit／Query 並保留時間；前置 Gate 不存在 | BLOCKED | 未上傳 |
| C2 Browser Upload／Ingestion | Browser 建立 SUP-005 metadata、原檔、Chunk、READY 並量時；UI 不可用 | BLOCKED | 未上傳 `ui-upload-sup-005-v1.txt` |
| C3 SUP-005 Review／Backfill／Gate | 新文件後 Gate 失效／恢復由後端實際證明；未執行 | BLOCKED | 無狀態或計時證據 |
| C4 SUP-005 Source Query | 真 Gemini 正確答案及第六份文件 Source／Version／Chunk／Offset；未查詢 | BLOCKED | 無來源證據 |
| D1 中文問英文文件 | 以額外虛構英文文件正常上傳、索引並由中文問題檢索；未執行 | BLOCKED | DB 隔離已具備，但 Browser／ADMIN Gate 流程不可達 |
| D2 英文問中文文件 | 真實 Gemini 引用 SUP-002 中文原文且答案正確；未執行 | BLOCKED | 無來源證據 |
| D3 跨語結果／限制 | 實際候選、相似度、回答及限制；無 Query | NOT RUN | 不推定跨語品質 |
| E1 Reset 前證據 | 完整業務／文件／索引／Gate 狀態；本次沒有基線 | BLOCKED | 未建立 Reset 前快照 |
| E2 Guarded Reset | 正式 CLI、一次性程序與 Exit Code；資源已隔離但前置 Browser／Reset 前證據不完整 | NOT RUN | `scripts/reset-demo.ps1` 未執行，依驗收順序保留目前 Fresh baseline |
| E3 Reset 後 Browser | 基線恢復、SUP-005／舊索引／審核／Audit 清除；未 Reset | NOT RUN | 無前後狀態 |
| E4 Gate 重建 | Admin UI 真實重審、Backfill、Audit、Source Query 與恢復時間；Reset 未執行 | BLOCKED | 不宣稱 AI 自動恢復 |
| E5 一般重啟 | 證明一般重啟不 Reset；沒有安全 Demo 服務 | NOT RUN | 未重啟 Backend |
| F 證據文件 | 記錄本次實際預檢、測試與限制 | PARTIAL | 本文件；不含敏感設定值或虛構截圖 |

## 本次自動化驗證（非 E9 PASS）

| 類型 | 指令 | 結果 | 環境／限制 |
|---|---|---|---|
| Frontend Lint | `npm run lint` | PASS，exit 0 | 本機，`frontend/` |
| Frontend Build | `npm run build` | PASS，exit 0 | Vite build 完成；輸出約 1.19 MB chunk 的既有警告 |
| Semantic 管理 UI 測試 | `npm test -- tests/semanticOperationsPanel.test.tsx --maxWorkers=1` | PASS，1 file／9 tests | 本機 Vitest；不是 Browser E2E |
| Semantic API client 測試 | `npm test -- tests/semanticIndexApi.test.ts --maxWorkers=1` | PASS，1 file／8 tests | 本機 Vitest；不是 Backend／Gemini 驗證 |
| Frontend 全套 Vitest | `npm test` | 初次 20 個 suite 因 Windows temp rename `EPERM` 失敗；改專案暫存目錄後執行仍長時間沒有摘要，為避免無限等待而中止 | 不標 PASS；後續以相關兩個 targeted suites 實際通過作有限回歸證據 |
| GitHub CI | 未對本報告 SHA 執行 | NOT RUN | 尚無本報告 commit／push |
| Backend regression | 未執行完整套件 | NOT RUN | 本次沒有程式碼變更；另有下列兩個 targeted backend suites 真正執行 |
| Backend Demo Seed Smoke | `./mvnw -Dtest=DemoSeedSmokeIntegrationTests test` | PASS，5 tests，0 failures／errors／skips，33.31 秒 | 本機 Testcontainers 專用暫存 MySQL；不連線本次 Demo DB |
| 真實 Gemini Embedding Smoke | `./mvnw -Dtest=SemanticRealProviderSmokeTests -Dsemantic.real-smoke=true test` | PASS，1 test，0 failures／errors／skips，1.761 秒；Maven build 6.905 秒 | 真實 `gemini-embedding-001`，768 維合成輸入；不是文件 Backfill、Chat 或 Gate |

以上 Lint／Build／Vitest 都是程式層級檢查，不能替代 Browser E2E、Gemini、pgvector 或 Gate Audit。

## 阻擋解除條件與 #45 交接

1. 讓本次工作程序與本機 Browser 能安全連線至正在運行的 Frontend／Backend。現有產品 UI 測試不可由 API 或 curl 替代。
2. 在可達 UI 上完成五角色採購流程、文件出站審核、Backfill、Readiness、持久化 Gate Audit、五份 Source Query、SUP-005 上傳／索引／查詢、跨語查詢與 AI 故障情境。
3. 依序保存完整 Reset 前快照，再透過正式 `scripts/reset-demo.ps1` 執行隔離 Demo Reset，並完成 Browser Reset 後驗證及人工重建真實 Gate。
4. 以容器化部署重新跑核心情境，再由 #45 獨立執行 AWS／排程／HTTPS 驗證；本次沒有測得 Reset 或 Gate 恢復耗時，兩者不可推定。

## Findings 與限制

- **沒有發現可歸因於產品程式碼的 FAIL。** Docker 隔離資源、Fresh Demo、測試級 Demo Seed Smoke 及一次真實 Embedding Provider Smoke 已實際完成；Browser 無法連上本機 UI 是本次主要阻擋。
- **P0 Release Gate 仍 BLOCKED：** 採購 Browser E2E、真實 Gemini Chat 與文件來源查詢、文件審核／正式 Backfill／Semantic Gate、SUP-005 Browser E2E、跨語檢索、Guarded Reset 與 Reset 後 Gate 恢復均未完成。一次 Embedding Smoke 不代表文件已索引或 Gate PASS。
- 未將 `.env` 任何值輸出至終端、文件或截圖；既有 `.env` 指向的 DB／文件目錄未使用或修改。
- 沒有生成或保存產品 Browser 截圖；只有一次本機 Browser 連線拒絕結果，不以錯誤頁冒充 E2E 證據。
