# Issue #144 本機驗證紀錄

- Repository: liujiawei-tw/supplypilot
- Branch: codex/issue-144-po-search
- Base: origin/main at 9825612c6636c3543d42c3bcfcba015058b52acf
- Validated product/test source commit: f8335ed714f307c51907e51c6aa7ab9d052e9564
- Post-review evidence target HEAD: 72706a2e61fd9ac2d1843c9f52cacde70fe3d793
- GitHub Actions: PAUSED BY USER / NOT USED AS GATE

## HEAD 與證據適用範圍

GitHub 比較 f8335ed714f307c51907e51c6aa7ab9d052e9564 與 72706a2e61fd9ac2d1843c9f52cacde70fe3d793 顯示只有本驗證文件新增；Backend、Frontend、測試與相依檔案均未變。因此原始 Backend 10 tests、Frontend 5 tests 與 npm ci 證據適用於相同產品／測試源碼。以下另記錄在 reviewed HEAD 72706a2e61fd9ac2d1843c9f52cacde70fe3d793 上執行的後審查精準驗證。

## 環境

- Windows; Java 21.0.10; Maven 3.9.16; Node.js v24.15.0; npm 11.12.1
- Docker Desktop 29.6.1; Testcontainers 2.0.5; integration test database image mysql:8.4.11
- Backend 後審查測試使用從 HEAD 72706a2e61fd9ac2d1843c9f52cacde70fe3d793 Worktree 複製的 source snapshot（不含 target）於 Temp 執行，Docker Testcontainers 建立隨機 port 的隔離 MySQL；沒有連線 localhost:3306，未操作持久化 Demo 或正式資料。
- Frontend 後審查測試直接在 HEAD 72706a2e61fd9ac2d1843c9f52cacde70fe3d793 Worktree 執行。

## 必要精準驗證

| 範圍 | 命令 | 結果 | 原始輸出 |
|---|---|---|---|
| Backend PO API（原始證據，source f8335ed） | .\mvnw.cmd -B -Dtest=PurchaseOrderControllerIntegrationTests test（於 backend） | Exit 0；10 tests、0 failures、0 errors、0 skipped；BUILD SUCCESS | [issue-144-backend-focused.log](issue-144-backend-focused.log) |
| Frontend 依賴（原始證據，source f8335ed） | npm ci（於 frontend） | Exit 0；安裝 212 packages。npm 輸出列出 2 moderate vulnerabilities；本次未執行 npm audit fix | [issue-144-npm-ci.log](issue-144-npm-ci.log) |
| Frontend PO／Kanban 查詢狀態（原始證據，source f8335ed） | .\node_modules\.bin\vitest.cmd run tests/issue143PurchaseOrdersPage.test.tsx --pool=threads --maxWorkers=1 --reporter=verbose --testTimeout=30000 --hookTimeout=30000（於 frontend） | Exit 0；1 file、5 tests passed | [issue-144-frontend-focused.log](issue-144-frontend-focused.log) |
| Backend PO API（後審查，target HEAD 72706a2） | .\mvnw.cmd -B -Dtest=PurchaseOrderControllerIntegrationTests test（於 Temp backend source snapshot） | Exit 0；10 tests、0 failures、0 errors、0 skipped；BUILD SUCCESS | C:\Users\z0976\AppData\Local\Temp\issue-144-post-review-backend-elevated.log |
| Backend 日期／逾期與分頁補充回歸（後審查，target HEAD 72706a2） | .\mvnw.cmd -B -Dtest=PurchaseOrderOrderingIntegrationTests test（於同一 Temp backend source snapshot） | Exit 0；5 tests、0 failures、0 errors、0 skipped；BUILD SUCCESS | C:\Users\z0976\AppData\Local\Temp\issue-144-post-review-ordering-elevated.log |
| Frontend PO／Kanban 查詢狀態（後審查，target HEAD 72706a2） | .\node_modules\.bin\vitest.cmd run tests/issue143PurchaseOrdersPage.test.tsx --configLoader runner --pool=threads --maxWorkers=1 --reporter=verbose --testTimeout=30000 --hookTimeout=30000（於 frontend） | Exit 0；1 file、5 tests passed；90.26 秒 | C:\Users\z0976\AppData\Local\Temp\issue-144-post-review-frontend-final.log |

--configLoader runner 用於避免唯讀 Worktree 中 Vite config loader 寫入 node_modules 暫存檔；測試仍直接讀取該 Worktree 的 source。

## 執行限制與原始失敗紀錄

首次後審查執行遭遇唯讀 Worktree 的 target/classes 寫入拒絕及未提升權限的 Docker Npipe/Testcontainers access denied；後續在 Temp source snapshot 與已連線的 Docker Desktop 下完成上述隔離整合測試。與 #148 Vitest 同時執行時，部分 #144 draft-create 案例曾 timeout；停止併跑後再次單獨執行，5/5 通過，並以該 clean run 作為結果。相關原始記錄仍保存在本機：

- Maven Worktree EPERM：C:\Users\z0976\AppData\Local\Temp\issue-144-post-review-backend.log、C:\Users\z0976\AppData\Local\Temp\issue-144-post-review-backend-retry.log
- Testcontainers Npipe 權限失敗：C:\Users\z0976\AppData\Local\Temp\issue-144-post-review-backend-retry2.log
- 併跑中的 Vitest timeout/中止記錄：C:\Users\z0976\AppData\Local\Temp\issue-144-post-review-frontend.log、C:\Users\z0976\AppData\Local\Temp\issue-144-post-review-frontend-retry.log
- 提升權限及序列重跑的成功原始輸出見上表。

## 覆蓋重點與範圍

- 後端整合案例涵蓋 PO 完整／部分單號搜尋、供應商代碼與名稱、物料代碼、AND 複合既有篩選、空白與無結果、LIKE 特殊字元、SQL injection 字串、超長輸入驗證、未登入／無角色權限，以及 101 張各含兩筆符合品項的 PO 跨六頁去重與 totalElements。
- PO ordering 補充案例涵蓋 overdue true/false、日期區間、既有篩選組合、pagination/count 與非法 sort 欄位；排序回傳列實際順序未由這些測試斷言。
- 101 筆測試驗證每頁筆數、totalElements/totalPages 與跨頁唯一 ID，但沒有斷言 PO number 實際遞增。實作仍以資料庫 Pageable 排序分頁，並為非 id 主排序加入 id tie-breaker。
- 前端聚焦案例確認四個搜尋欄位會送入共用 PO query，與 Supplier ID 合併，並在 Kanban／List 切換時保留搜尋值及查詢條件。
- 瀏覽器 E2E：NOT RUN；本 Issue 的必要 UI 驗證為上列 Vitest。完整 Backend／Frontend 套件：DEFERRED TO V2 INTEGRATION，依 2026-10-24 核定流程處理。
