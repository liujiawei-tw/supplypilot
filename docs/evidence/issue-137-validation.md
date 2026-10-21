# Issue #137 驗證紀錄

本紀錄對應 Feature Branch `codex/issue-137-v2-102`。歷史列保留各次執行當時的工作樹與結果，不視為目前交付 SHA 的證據；目前 Review 修正的測試結果見文末「本次 Review 修正驗證」。最終完整回歸以交付 SHA 的 GitHub Actions 為準。

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
| GitHub Actions 完整 CI | Feature Branch 交付 SHA | NOT RUN | Push 後取得同一 SHA 的 Workflow Run 與每個 Job 結果再更新。 |

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
| GitHub Actions | 本次交付 SHA | NOT RUN | Push 後查核各 Job，僅用相同 Head SHA 結果作完整回歸證據。 |

### Docker Demo V23 Checksum 調查

Git 歷史顯示 V23 原始已套用內容位於 `a4977ee478ff7b7d5ce35b82334ed58a396600d1`，該 SQL 僅新增 `can_manage_users`；其 Flyway checksum 為 `1761549949`。後續 `f9a00229ce8f9d4df31a01364482f583c566b46e` 在已發佈 V23 加入 `idx_users_manager_status_id`，使 checksum 變為 `1985409924`。現有 Demo Volume 的 V23 history checksum 是 `1761549949`，資料庫結構符合原 V23、索引不存在，V24 尚未套用。故障根因是修改已套用的版本化 Migration，並非 JWT Filter 或資料損壞。

修正方式是恢復 V23 原文，將該索引移至尚未套用的 V24，讓 Flyway 正常驗證 V23 並執行後續版本。V24 的 MySQL DDL 可能逐句自動提交；真實 Demo 尚未執行 V24，也沒有修改或 repair `flyway_schema_history`。原 Demo 資料備份位於 ignored 路徑 `backend/target/sp903-demo-mysql-before-issue137.tar.gz`，SHA-256 `A9C28815173537205729C5DBB54D705F369630CAEE29F509FC72F5AF054BDFA1`；備份清單及隔離複本比對已通過。這些備份／隔離準備不代表真實 Demo 修復或重啟 PASS。

Compose 檔案盤點：`compose.yaml`（Production 基本配置）、`compose.demo.yaml`（Demo overlay）、`compose.dev.yaml`（Development）。三份檔案皆用 placeholder env 成功執行 config 驗證；本次沒有修改 Compose 服務、Project、資料庫、Volume 或 Health Check；CI workflow 增加 `compose.dev.yaml` 配置檢查。`backend/Dockerfile`、`frontend/Dockerfile` 未修改，兩個映像已在本次工作樹重建成功。隔離 Clone Runtime、V24、Backend health、Frontend health/API proxy 及容器重啟資料持久性均通過；宿主機瀏覽器路徑未測。真實 Demo Volume 仍未執行 V24 或重啟，因為這會對既有持久資料庫寫入 Migration History 與 Schema，需使用者明確核准；不得把克隆結果標示成真實 Demo Runtime PASS。

## SP-806 診斷結論

先前完整 Vitest 有 3 個 30 秒 timeout；當時 SP-806 單檔 25/25 通過。本次在目前 Review 修正工作樹再次單獨執行，仍為 25/25 PASS，耗時 396.91 秒（測例耗時 387.48 秒）。這確認隔離執行穩定通過，但尚不足以區分完整套件中的資源競爭、跨測試狀態污染或等待條件，也不足以證明或排除 #137 引入；完整執行的具體錯誤堆疊無法恢復。沒有提高 timeout、跳過案例、放寬斷言或修改 SP-806 功能。以交付 SHA 的 GitHub CI 完整 Vitest 結果判斷是否可交付；若 CI 失敗，依其實際失敗名稱與堆疊精準診斷。
