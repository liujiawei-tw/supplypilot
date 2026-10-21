# Issue #137 驗證紀錄

本紀錄對應 Feature Branch `codex/issue-137-v2-102`。歷史列保留各次執行當時的工作樹與結果，不視為目前交付 SHA 的證據；目前 Review 修正的測試結果見「本次 Review 修正驗證」，正式 Demo 最新唯讀驗收見文末。程式完整回歸以對應程式 SHA 的 GitHub Actions 為準。

| 驗證 | 指令／範圍 | 結果 | 版本與後續適用性 |
|---|---|---|---|
| Backend 完整回歸 | `cd backend; .\mvnw.cmd clean verify`；644 tests、0 failures、0 errors、1 skipped | PASS | #137 程式與測試修改後執行；後續僅新增規範／驗證文件，未改 Backend，仍適用於該工作樹程式碼。交付 SHA 仍由 CI 再驗。 |
| Frontend 完整 Vitest（本機） | `cd frontend; npm test -- --run` | FAIL；24 files 中 2 files failed，201 tests 中 3 個 SP-806 測例 timeout（每個約 30 秒）；Vitest 單 Worker，檔案耗時約 403 秒 | 同一未提交工作樹。完整執行工作階段結束時無法取回完整摘要／錯誤堆疊，故失敗測例名稱與卡點未能可靠記錄；不得標為 PASS。沒有再重跑完整套件。 |
| SP-806 隔離測試 | `cd frontend; npm test -- tests/sp806.test.tsx` | PASS；1 file、25 tests，duration 365.46 秒（tests 352.59 秒） | 同一未提交工作樹；證明 SP-806 在隔離執行時通過，不能單獨證明完整套件的 timeout 根因。未觀察到 #137 改動下的單檔失敗；整套執行中的狀態污染與資源競爭仍無法區分。 |
| Auth／SP-805 精準測試 | `npm test -- tests/auth.test.tsx tests/sp805.test.tsx` | PASS；2 files、32 tests | 在相同 #137 程式碼工作樹執行；後續只有文件變更，仍適用。 |
| Frontend Lint | `npm run lint` | PASS | 在前端程式修改後執行；僅其後有測試期望值及文件調整。 |
| Frontend Build | `npm run build` | PASS | 在最後前端產品程式修改後執行；其後沒有產品程式變更。 |
| Backend 相關整合測試 | `-Dtest=PurchaseOrderLifecycleIntegrationTests,PurchaseOrderOrderingIntegrationTests,PurchaseOrderReceivingIntegrationTests,ReceivingPurchaseOrderControllerIntegrationTests,V23MigrationIntegrationTests test` | PASS；23 tests | 與後續完整 Maven verify 對應同一 #137 程式工作樹。 |
| 使用者授權併發整合測試 | `-Dtest=UserAuthorizationIntegrationTests test` | PASS；24 tests | 包含刪除／撤銷競爭、最後一位管理者保護與併發衝突回應；對應最後完整 Maven verify 工作樹。 |
| Compose 設定 | `docker compose -f compose.yaml config --quiet`；`docker compose -f compose.dev.yaml config --quiet`；`docker compose -f compose.yaml -f compose.demo.yaml config --quiet` | PASS；三種配置 | Compose YAML 未修改；本次只影響資料庫遷移與服務 API。 |
| Docker Image Build／隔離 Smoke | Backend、Frontend image build；臨時 network/containers 啟動、API proxy 401、Frontend health 200、Backend restart health UP、Flyway V24 | PASS | 以 #137 工作樹 Dockerfile／應用程式建置。僅使用臨時容器與 network，沒有建立、刪除或修改 volume。 |
| Git diff whitespace | `git diff --check` | PASS | 加入本驗證紀錄後再執行。 |
| GitHub Actions 完整 CI | 原審查 SHA `c02eaed6d90209bb9844ee01c2855d7c0c235e95`；Run #173 | PASS | [GitHub Actions Run #173](https://github.com/liujiawei-tw/supplypilot/actions/runs/39567728185) 全部 Job 通過；這是前一版 SHA 的結果，不取代本次修正 SHA 的回歸。 |

## 本次 Review 修正驗證（基於 `c02eaed6d90209bb9844ee01c2855d7c0c235e95` 的目前工作樹）

以下是本次工作樹的增量驗證，不可與上表歷史結果混為一談。Commit 後以新的 HEAD SHA 重查 GitHub Actions。

| 驗證 | 指令／範圍 | 結果 | 適用性／後續 |
|---|---|---|---|
| Backend PO lifecycle／receiving | `cd backend; .\mvnw.cmd -B --no-transfer-progress '-Dtest=PurchaseOrderLifecycleIntegrationTests,PurchaseOrderReceivingIntegrationTests' test` | PASS；12 tests、0 failures、0 errors、0 skipped | 本次工作樹；覆蓋 REJECTED 禁止認領／指派、RECEIVED 相容性、收貨交易與 rollback。V23 Migration 及 StockTransaction 各自的精準測試亦已在本次工作樹分開通過（V23MigrationIntegrationTests 1 test；StockTransactionControllerIntegrationTests 6 tests）。 |
| Frontend owner mutation | `npm test -- tests/sp805.test.tsx -t 'locks detail owner changes' --reporter=dot` | PASS；1 passed、11 skipped（由 `-t` 篩選） | 本次工作樹；覆蓋 409 後不重送、明確重新查詢解鎖。 |
| Frontend SP-806 單檔 | `npm test -- tests/sp806.test.tsx` | PASS；1 file、25/25 tests；duration 396.91 秒（tests 387.48 秒） | 本次工作樹；Vitest 單 Worker。單檔可通過，但不能據此解釋先前完整套件 3 個 timeout；不提高 30 秒 timeout、不跳過案例。 |
| Frontend Lint | `npm run lint` | PASS | 本次工作樹。 |
| Frontend Build | `npm run build` | PASS；Vite 提示主 JS chunk 約 1.22 MB（gzip 約 392 KB）超過 500 kB chunk 門檻 | 本次工作樹；此提示為既有 bundle 大小警告，build 成功。 |
| 完整本機 Frontend Vitest | `npm test -- --run` | NOT RUN（本次） | 避免重跑已記錄的完整回歸；最終由交付 SHA CI 執行。先前同檔 SP-806 的隔離與整套差異保留在上方歷史紀錄，原因目前未能定論。 |
| Docker Compose 設定 | 以 `backend/target/issue137-compose.env` 的 placeholder 值驗證 Production、Demo overlay、Development 三組 Compose | PASS；三種組合皆成功 | 本次工作樹；沒有啟動 Compose 專案或掛載真實 Demo Volume。 |
| Docker Build | `docker build --file backend/Dockerfile --tag supplypilot-backend:issue137-review .`；`docker build --file frontend/Dockerfile --tag supplypilot-frontend:issue137-review .` | PASS；兩個映像建置完成 | 本次工作樹。Frontend image 內建置成功，Vite 顯示既有大 chunk 警告。 |
| 隔離既有資料庫 Runtime | 克隆 volume `supplypilot-issue-137-sp903-mysql-copy-20261021`，Backend `demo` profile 連線 MySQL 8.4.11 | PASS；Flyway 驗證原 V23 checksum `1761549949`，僅套用 V24，啟動日誌記錄 `Started SupplyPilotApplication`；Backend actuator health `UP` | 僅操作既有隔離克隆，不掛載或寫入真實 Demo volume。初次啟動使用無效 Base64 測試 JWT 而在 JWT bean 失敗，尚未進行 Flyway；改用有效隔離測試值後重跑成功。 |
| 隔離 Frontend／API Proxy | `frontend:issue137-review` 容器內 `/health`；`/api/v1/purchase-orders` 經 Nginx proxy | PASS；health `ok`，API 返回預期 `401`，證明 proxy 抵達需認證的後端路由 | 只驗證隔離內部網路；未驗證宿主機瀏覽器連線。 |
| 隔離容器重啟／資料保留 | 重啟 `issue137-backend-smoke` 後查 health、Flyway history、users、purchase_orders、stock_transactions、管理索引 | PASS；health `UP`；V23 checksum 1761549949、V24 success；users=6、PO=12、stock_transactions=6；索引 metadata 有 3 列 | 數據只來自隔離複本。 |
| Git whitespace | `git diff --check` | PASS | 本次工作樹；文件更新完成後仍須再跑一次。 |
| GitHub Actions 完整 CI | `1a0ea5035a6817b432aba0a44edc19d1020c52ba`；Run #174 | PASS；Detect CI scope、Backend CI、Frontend CI、Container image and Compose validation 全部成功；Frontend Vitest 24/24 files、202/202 tests | [GitHub Actions Run #174](https://github.com/liujiawei-tw/supplypilot/actions/runs/39574370963)；push 觸發，workflow Head SHA 與本次程式 Commit 完全一致。Frontend CI 13m 27s；未重跑本機整套 Vitest。 |

### Docker Demo V23 Checksum 調查

Git 歷史顯示 V23 原始已套用內容位於 `a4977ee478ff7b7d5ce35b82334ed58a396600d1`，該 SQL 僅新增 `can_manage_users`；其 Flyway checksum 為 `1761549949`。後續 `f9a00229ce8f9d4df31a01364482f583c566b46e` 在已發佈 V23 加入 `idx_users_manager_status_id`，使 checksum 變為 `1985409924`。首次故障調查時的 Demo Volume V23 history checksum 是 `1761549949`，結構符合原 V23、索引不存在、V24 尚未套用。故障根因是修改已套用的版本化 Migration，並非 JWT Filter 或資料損壞。

修正方式是恢復 V23 原文，將該索引移至 V24。使用者後續確認正式 Demo 已由先前正式啟動流程成功套用 V24；本次驗收沒有再次 Migration，也沒有修改或 repair `flyway_schema_history`。舊 V23 備份位於 ignored 路徑 `backend/target/sp903-demo-mysql-before-issue137-review.tar.gz`，SHA-256 `A9C28815173537205729C5DBB54D705F369630CAEE29F509FC72F5AF054BDFA1`；該備份可還原但不含目前 V24 狀態，不可作為目前資料的回復點。最新備份與還原驗證見文末。

Compose 檔案盤點：`compose.yaml`（Production 基本配置）、`compose.demo.yaml`（Demo overlay）、`compose.dev.yaml`（Development）。三份檔案皆用 placeholder env 成功執行 config 驗證；本次沒有修改 Compose 服務、Project、資料庫、Volume 或 Health Check；CI workflow 增加 `compose.dev.yaml` 配置檢查。`backend/Dockerfile`、`frontend/Dockerfile` 未修改，兩個映像已在本次工作樹重建成功。隔離 Clone Runtime、V24、Backend health、Frontend health/API proxy 及容器重啟資料持久性均通過；宿主機瀏覽器路徑未測。以上是先前隔離測試，不代表目前正式 Demo Runtime；最新正式 Volume 的 V24／資料狀態與目前服務狀態見文末。

## SP-806 診斷結論

先前完整 Vitest 有 3 個 30 秒 timeout；當時 SP-806 單檔 25/25 通過。本次在目前 Review 修正工作樹再次單獨執行，仍為 25/25 PASS，耗時 396.91 秒（測例耗時 387.48 秒）。這確認隔離執行穩定通過，但尚不足以區分完整套件中的資源競爭、跨測試狀態污染或等待條件，也不足以證明或排除 #137 引入；完整執行的具體錯誤堆疊無法恢復。沒有提高 timeout、跳過案例、放寬斷言或修改 SP-806 功能。以交付 SHA 的 GitHub CI 完整 Vitest 結果判斷是否可交付；若 CI 失敗，依其實際失敗名稱與堆疊精準診斷。

## 正式 SP-903 Demo V24 唯讀最終驗收（2026-10-21）

### 範圍、服務狀態與觀測方式

目標 Docker Volume 標籤確認為 Compose Project `supplypilot-sp903-local-demo` 的 `sp903-mysql-data`，實際 Volume 名稱 `supplypilot-sp903-local-demo-mysql`；另兩個既有 Volume 為 `supplypilot-sp903-local-demo-pgvector` 與 `supplypilot-sp903-local-demo-documents`。此次確認時，沒有該 Compose Project 的 MySQL、pgvector、Backend 或 Frontend Container，也沒有容器掛載這三個 Volume。正式服務未啟動的原因無法從不存在的 Container 診斷；本次沒有重啟或重建任何正式服務。

以下 DB／檔案檢查來自正式 Volume 以唯讀 mount 複製出的隔離快照，以及該快照的備份還原件；不是正式 Container Runtime 結果。正式 Volume 在建立備份時均未被 Container 掛載，沒有活動中的服務寫入或 DDL。正式 Volume 的掛載來源為唯讀；查詢在隔離還原件執行。

### Flyway 與 V24 Schema

| 項目 | 驗收結果 |
|---|---|
| V23 History | checksum `1761549949`、`success=1`；DB 記錄時間 `2026-10-21 02:40:09`（欄位不含時區） |
| V24 History | checksum `-1757611084`、`success=1`；與目前 V24 Migration 的已驗證 checksum 一致；DB 記錄時間 `2026-10-21 10:50:37`（欄位不含時區）。V24 實際執行程序／操作者來源 `UNKNOWN`；本次沒有重新執行 Migration。 |
| V24 結構 | `users.can_manage_users` 為 NOT NULL；`idx_users_manager_status_id` 三欄索引存在。PO 的 `assigned_to` 及建立、核准、駁回、下單、收貨快照欄位均存在；`idx_purchase_orders_assigned_to` 存在。StockTransaction 與 Demand、Inventory Import、Purchase Recommendation、Document Metadata、AI Draft Proposal 的 V24 快照欄位存在。 |
| 外鍵 | PO 六個 User 關聯及其他 V24 User 關聯均為 `ON DELETE SET NULL`；PO 建立者、負責人及 StockTransaction 操作者沒有孤兒外鍵。 |
| 表完整性 | 還原件上的 `CHECK TABLE` 對 users、PO／items、StockTransaction、Demand、Import、Recommendation、Document Metadata／chunks、AI Draft Proposal 均回報 `OK`。 |

### 人員刪除後的業務資料

使用者確認正式 Demo Users 從 6 減至 1，是使用者親自登入並測試人員永久刪除功能造成的預期結果；不恢復帳號，也不追查刪除來源。隔離還原查詢確認剩餘帳號 `id=5` 為 `ACTIVE`、`ADMIN`、`can_manage_users=1`，目前仍有一位有效人員管理者。

| 資料 | 還原件唯讀結果 |
|---|---|
| Purchase Orders | 12 張、13 個 PO Item 保留。建立者快照 12/12；核准 9/12、駁回 1/12、下單 6/12、收貨 5/12 的快照符合實際事件。11 張 PO 的 `assigned_to` 與現行 `created_by` 為 NULL，且刪除者的原始 ID／姓名快照仍在；另 1 張仍有指派人。 |
| StockTransaction | 6 筆保留；原始操作者 ID、username、display name 快照皆為 6/6；其中 3 筆現行 User FK 為 NULL，歷史快照仍在。 |
| 其他歷史表 | Demand 13（12 筆 NULL 建立者有 ID 快照）；Inventory Import Batch 5；Purchase Recommendation 8（1 筆 NULL 建立者有 ID 快照）；Document Metadata 6（全部 READY）；AI Purchase Draft Proposal 0。 |
| 文件與語意索引 | 6 筆 metadata 的六個 `storage_path` 均在文件 Volume 還原件找到。PostgreSQL 17／pgvector 0.8.7 還原件有 6 筆 `READY` semantic index、6 個 chunk、1 筆 generation、3 筆 audit；六份 metadata source hash 與對應 semantic index source hash 一致。 |

### 目前狀態備份與隔離還原

備份於本機時間 `2026-10-21 19:07:11–19:07:12 +08:00` 建立。三個來源 Volume 均以唯讀 mount 複製；每份 tar 清單完整列舉、SHA-256 已計算，並還原至各自全新隔離 Volume。沒有覆寫既有備份或 Volume。

| 備份來源 | 檔案（ignored `backend/target/`） | 大小／項目 | SHA-256 | 還原驗證 |
|---|---|---:|---|---|
| MySQL `supplypilot-sp903-local-demo-mysql` | `sp903-demo-mysql-v24-final-20261021.tar.gz` | 6,660,108 bytes／219 entries | `424F3E40EC1373FF6458DCAD6CE66863925CCA6621143BDA7AEBA2874FF439D4` | MySQL 8.4.11 還原件啟動並可查詢；V23/V24 History、Schema、資料筆數與完整性檢查通過。 |
| pgvector `supplypilot-sp903-local-demo-pgvector` | `sp903-demo-pgvector-v24-final-20261021.tar.gz` | 6,966,075 bytes／1,316 entries | `10BF5C743FE25015F9D7AC91BF0E28708694639DA5C10C78822C129C37B32EEB` | PostgreSQL 17 還原件可連線；vector extension、semantic tables、索引、chunks 與 source hashes 可讀。 |
| 文件 `supplypilot-sp903-local-demo-documents` | `sp903-demo-documents-v24-final-20261021.tar.gz` | 2,506 bytes／7 entries | `7B11EF697CF6CB6315AF9D0D161442E8A018B9A1FE03FCA5FD432234CD93D938` | 隔離還原件有 6 個文件檔案；均與 MySQL metadata 路徑對應，檔案可計算 SHA-256。 |

來源三個 Volume 在備份時皆離線、無執行中或停止中的掛載容器，因此備份期間無應用程式寫入。三份資料在靜止狀態下依序備份，非跨 MySQL／PostgreSQL／檔案系統原子交易快照；已用文件 ID、版本及 source hash 驗證目前六份文件與語意索引互相對應。這是目前 V24 狀態的可還原本機備份，不涵蓋 AWS 或外部 Secret／設定。

### 正式 Docker Runtime 與交付狀態

| 驗證 | 結果 |
|---|---|
| MySQL／pgvector／Backend／Frontend Container | `NOT RUN`：正式 Project 無 Container；本次遵守限制，沒有重啟或建立。 |
| Backend `/actuator/health` | `NOT RUN`：服務未啟動，無法請求。 |
| Frontend `/health`、`/api/v1/purchase-orders` Proxy | `NOT RUN`：Frontend 未啟動，無法請求。 |
| 正式服務日誌 | `NOT AVAILABLE`：目前沒有正式 Container 可供讀取。 |
| 正式 Demo 功能 | 帳號刪除由使用者已親自驗證；本次未登入、未新增／刪除帳號、未寫入任何正式業務資料。 |

依 `docs/SP-903-DOCKER-RELEASE-CANDIDATE.md`，服務需由使用者核准後以標準 Demo Compose 啟動（例如 `docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build`）。這會建立／啟動四個正式 Demo Container，Backend 啟動會連線正式 MySQL／pgvector 並執行 Flyway 啟動驗證；雖然目前 DB History 已有 V24 success，且 Dataset 狀態為 `COMPLETE`，啟動仍會寫服務／資料庫運行狀態，且應用程式啟動鉤子可能觸及資料庫。因此超出本次唯讀授權，需使用者明確核准後才能做 Health、API Proxy 與正式日誌驗收。

本次僅更新驗證文件；程式完整 CI 仍為 SHA `1a0ea5035a6817b432aba0a44edc19d1020c52ba` 的 [Run #174](https://github.com/liujiawei-tw/supplypilot/actions/runs/39574370963)（Backend、Frontend、Container/Compose 全通過）；目前文件 HEAD `e83b191742e720f58f99dae5bab96db9d4ab2977` 的 [Run #175](https://github.com/liujiawei-tw/supplypilot/actions/runs/39576262894) 整體成功，程式 jobs 因文件專用變更 skipped。此次沒有修改產品程式、Migration 或測試，未重跑完整 Maven／Vitest。正式 Runtime 尚未驗收，故本紀錄狀態為 `BLOCKED — REQUIRES USER APPROVAL`，尚未標記 `READY FOR GPT RE-REVIEW`。
