# SP-910 真實 E9 驗收紀錄

## 本次結果

**SP-910 E9 BLOCKED — PARTIAL EVIDENCE AVAILABLE**

本紀錄只涵蓋 2026-10-15 在本機檢查或真正執行的項目。沒有將既有 CI／測試文件或其他 SHA 的結果套用為本次 E9 證據。未能核實隔離資料資源前，沒有連線、初始化、刪除或 Reset 任何資料庫／文件目錄。

## 任務與 Git 基線

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#117 SP-910](https://github.com/liujiawei-tw/supplypilot/issues/117)，GitHub 查核狀態：OPEN（2026-10-15）
- 依賴查核：[#43](https://github.com/liujiawei-tw/supplypilot/issues/43)、[#112](https://github.com/liujiawei-tw/supplypilot/issues/112)、[#110](https://github.com/liujiawei-tw/supplypilot/issues/110) 均 CLOSED
- GitHub API 確認 `main` 最新 SHA：`8aa1031158d8bc317057b356c1b38ba9c59efefa`（合併 PR #119）
- Feature Branch：`codex/sp-910-real-e9-acceptance`，已從最新 `main` 快轉建立
- 本紀錄建立前的 HEAD：`8aa1031158d8bc317057b356c1b38ba9c59efefa`
- 驗收時間：2026-10-15 13:40–14:00 Asia/Taipei（約；Vitest 各案例執行時間另記）

## 環境預檢

| 項目 | 實際觀察 | 狀態 |
|---|---|---|
| Windows | PowerShell 回報版本字串 `10.0.26200`；Maven Wrapper 識別為 Windows 11、amd64 | 已盤點；版本標示有差異 |
| Java／Maven | Zulu OpenJDK 21.0.10；Maven Wrapper 3.9.16 | 可用 |
| Node.js／npm | Node 24.15.0；npm 11.12.1 | 可用 |
| Docker Compose | Compose CLI 5.3.0 | CLI 可用 |
| Docker Engine | 連線 `dockerDesktopLinuxEngine` named pipe 時回報 permission denied | **BLOCKED**；無法查容器／Volume 或啟動專用服務 |
| 前端／後端 | 前端腳本為 Vite；後端提供 `backend/mvnw.cmd`。未觀察到可供驗收的已啟動服務 | 前端實際瀏覽器連線受拒；後端未啟動 |
| Browser | Codex in-app browser 可操作；導覽 `http://127.0.0.1:5173` 得到 `net::ERR_CONNECTION_REFUSED` | Browser 工具可用；產品 UI 不可用 |
| Port／MySQL／pgvector | 服務埠、服務版本、實際 DB、容器及 Volume 所屬無法確認 | **BLOCKED** |
| Demo／文件儲存 | 預期 Dataset Version 為 `sp-905-zh-tw-v1`；本次沒有初始化。實際 Marker、Baseline Date、Storage root 未核對 | **NOT RUN** |
| Gemini 設定 | 程序環境未注入 Gemini／Semantic 變數。`.env` 中存在 `AI_API_KEY` 變數名稱，但值未讀出或輸出；`GEMINI_API_KEY` 與 `GEMINI_MODEL` 變數名稱不存在於 `.env`。未驗證 API Key 有效性、模型可用性 | **BLOCKED** |
| Git | 工作目錄原先乾淨；首次本機 fetch 受 `.git` ACL 拒絕，後經授權 fetch 成功，確認最新 main 並快轉分支 | 可用 |

`.env` 含有現存 DB／Reset 設定。因 Docker Engine 不可連線，無法用實際連線確認 DB、Flyway datasource、Semantic DB、Volume 與 storage root 是否為專用資源；故沒有載入或使用其中的 DB／Reset 目標。`compose.yaml` 宣告的 Volume 名稱是一般 `supplypilot-mysql-data` 與 `supplypilot-pgvector-data`，本次無法由 Docker 確認其是否已存在或用途。

## 驗收矩陣

狀態定義沿用 Issue：PASS 代表指定環境的真實執行符合斷言；FAIL 代表真實執行不符合斷言；BLOCKED 代表必要服務、憑證或安全確認欠缺；NOT RUN 代表未執行；PARTIAL 僅代表有部分可用證據。

| 案例 | 預期／實際 | 狀態 | 操作／證據與限制 |
|---|---|---|---|
| A1 環境盤點 | 完整確認工具、服務與埠；工具版本部分可讀，Docker daemon、DB、埠與 Volume 未能確認 | PARTIAL | 本機 PowerShell／CLI；版本見上表 |
| A2 隔離目標 | 專用 MySQL、pgvector、Storage、Volume、帳號均可核對；實際連線與目標未知 | BLOCKED | 未建立／操作 DB；未執行 Reset |
| A3 中文 Fresh Demo | 5 Users、12 Materials、6 Suppliers、30 SupplierMaterial、12 Demands、10 POs、5 READY 文件、無 SUP-005；本次未初始化 | NOT RUN | 無 Dataset Marker／Browser／DB 證據 |
| A4 採購 Browser E2E | 五角色與完整採購主線、750→1050 KG、重複收貨 409；UI 服務未啟動 | BLOCKED | Browser 對 `127.0.0.1:5173` 實際連線受拒；沒有以 API 代替 |
| B1 真實 Gemini | 使用有效 Chat／Embedding 模型與隔離 pgvector；無安全注入的可驗證憑證、模型 ID 或 DB 連線 | BLOCKED | 沒有發出 Gemini API 請求；Fake Provider 未用作真實證據 |
| B2 五份文件出站審核 | Admin UI 逐份核對政策並取得審核結果；Admin UI 未啟動 | BLOCKED | 未操作、未假定 APPROVED |
| B3 真實 Embedding Backfill | 真實 Gemini Backfill、批次游標、狀態及索引核對；無可用 Gate 環境 | BLOCKED | 未寫入 pgvector |
| B4 Readiness／Gate Audit／Read Switch | 真實持久化 `passed=true`、auditId 並顯式啟用 Read；未執行 | BLOCKED | 沒有 Gate 結果或 auditId |
| B5 五份 Source-Cited Query | 五個答案均有正確文件、Version、Chunk、UTF-16 Offset、Hash；未查詢 | BLOCKED | 沒有來源證據 |
| B6 AI 故障情境 | 證據不足、Gate、Gemini、pgvector 故障契約與核心 API 可用性；無隔離服務 | BLOCKED | 未注入故障 |
| C1 第六份上傳前 Gate | 五份文件已核准／索引／Audit／Query 並保留時間；前置 Gate 不存在 | BLOCKED | 未上傳 |
| C2 Browser Upload／Ingestion | Browser 建立 SUP-005 metadata、原檔、Chunk、READY 並量時；UI 不可用 | BLOCKED | 未上傳 `ui-upload-sup-005-v1.txt` |
| C3 SUP-005 Review／Backfill／Gate | 新文件後 Gate 失效／恢復由後端實際證明；未執行 | BLOCKED | 無狀態或計時證據 |
| C4 SUP-005 Source Query | 真 Gemini 正確答案及第六份文件 Source／Version／Chunk／Offset；未查詢 | BLOCKED | 無來源證據 |
| D1 中文問英文文件 | 以額外虛構英文文件正常上傳、索引並由中文問題檢索；未執行 | BLOCKED | 無隔離測試環境 |
| D2 英文問中文文件 | 真實 Gemini 引用 SUP-002 中文原文且答案正確；未執行 | BLOCKED | 無來源證據 |
| D3 跨語結果／限制 | 實際候選、相似度、回答及限制；無 Query | NOT RUN | 不推定跨語品質 |
| E1 Reset 前證據 | 完整業務／文件／索引／Gate 狀態；本次沒有基線 | BLOCKED | 未建立 Reset 前快照 |
| E2 Guarded Reset | 專用資源上的正式 CLI、一次性程序與 Exit Code；隔離目標未核實 | BLOCKED | `scripts/reset-demo.ps1` 未執行 |
| E3 Reset 後 Browser | 基線恢復、SUP-005／舊索引／審核／Audit 清除；未 Reset | NOT RUN | 無前後狀態 |
| E4 Gate 重建 | Admin UI 真實重審、Backfill、Audit、Source Query 與恢復時間；未 Reset／無 Gemini | BLOCKED | 不宣稱 AI 自動恢復 |
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
| Backend regression | 未執行 | NOT RUN | 本次無程式碼變更；Docker/Testcontainers 也不可用 |

以上 Lint／Build／Vitest 都是程式層級檢查，不能替代 Browser E2E、Gemini、pgvector 或 Gate Audit。

## 阻擋解除條件與 #45 交接

1. 提供可由本次工作程序安全讀取的 Gemini Chat／Embedding 設定（不用貼到對話；可在安全 Secret／程序環境注入），包含實際相容模型 ID、768 維度與 generation key；執行前只回報設定存在性與模型／維度，不顯示 Secret。
2. 讓 Docker Engine 可用，並以新的明確專用名稱建立 MySQL、pgvector、文件 root、Volume 與本機測試帳號；確認所有實際 database、Flyway datasource、PostgreSQL `current_database()` 與 storage leaf 後，才執行 Fresh Seed／Browser E2E／Guarded Reset。
3. 啟動相符版本的 Backend 與 Frontend，經 Browser 真正操作五角色、Semantic Admin UI、第六份文件與 Reset 前後流程。
4. 以容器化部署重新跑核心情境，再由 #45 獨立執行 AWS／排程／HTTPS 驗證；本次沒有測得 Reset 或 Gate 恢復耗時，兩者不可推定。

## Findings 與限制

- **沒有發現可歸因於產品程式碼的 FAIL。** 本次主要阻擋屬本機 Docker 權限、未驗證的憑證／服務與未啟動 Browser UI。
- **P0 Release Gate 仍 BLOCKED：** Fresh Demo、真實 Gemini／Semantic Gate、五份來源、SUP-005 Browser E2E、跨語檢索、Guarded Reset 與 Reset 後 Gate 恢復均未完成。
- 未將 `.env` 任何值輸出至終端、文件或截圖；未連接或修改其中指定的資料庫／目錄。
- 沒有生成或保存產品 Browser 截圖；只有一次本機 Browser 連線拒絕結果，不以錯誤頁冒充 E2E 證據。
