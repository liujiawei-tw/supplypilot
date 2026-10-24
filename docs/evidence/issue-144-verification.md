# Issue #144 本機驗證紀錄

- Repository: liujiawei-tw/supplypilot
- Branch: codex/issue-144-po-search
- Base: origin/main at 9825612c6636c3543d42c3bcfcba015058b52acf
- Validated source commit: f8335ed714f307c51907e51c6aa7ab9d052e9564
- GitHub Actions: PAUSED BY USER / NOT USED AS GATE

## 環境

- Windows; Java 21.0.10; Node.js v24.15.0; npm 11.12.1
- Docker Desktop 29.6.1; Testcontainers 2.0.5; integration test database image mysql:8.4.11
- Backend 使用 test profile 與 Testcontainers 隔離資料庫；未操作持久化 Demo 或正式資料。

## 必要精準驗證

| 範圍 | 命令 | 結果 | 原始輸出 |
|---|---|---|---|
| Backend PO API | .\mvnw.cmd -B -Dtest=PurchaseOrderControllerIntegrationTests test（於 backend） | Exit 0；10 tests、0 failures、0 errors、0 skipped；BUILD SUCCESS | [issue-144-backend-focused.log](issue-144-backend-focused.log) |
| Frontend 依賴 | npm ci（於 frontend） | Exit 0；安裝 212 packages。npm 輸出列出 2 moderate vulnerabilities；本次未執行 npm audit fix | [issue-144-npm-ci.log](issue-144-npm-ci.log) |
| Frontend PO／Kanban 查詢狀態 | .\node_modules\.bin\vitest.cmd run tests/issue143PurchaseOrdersPage.test.tsx --pool=threads --maxWorkers=1 --reporter=verbose --testTimeout=30000 --hookTimeout=30000（於 frontend） | Exit 0；1 file、5 tests passed | [issue-144-frontend-focused.log](issue-144-frontend-focused.log) |

Vitest 輸出包含 jsdom 對 getComputedStyle() pseudo-elements 的 Not implemented 訊息；5 個測試皆通過。Backend 編譯輸出含既有 Spring AI deprecated API warnings，未阻止測試。

## 覆蓋重點與範圍

- 後端整合案例涵蓋 PO 完整／部分單號搜尋、供應商代碼與名稱、物料代碼、AND 複合既有篩選、空白與無結果、LIKE 特殊字元、SQL injection 字串、超長輸入驗證、未登入／無角色權限，以及 101 張各含兩筆符合品項的 PO 跨六頁去重與 totalElements。
- 前端聚焦案例確認四個搜尋欄位會送入共用 PO query，與 Supplier ID 合併，並在 Kanban／List 切換時保留搜尋值及查詢條件。
- 瀏覽器 E2E：NOT RUN；本 Issue 的必要 UI 驗證為上列 Vitest。完整 Frontend 套件：DEFERRED TO V2 INTEGRATION，依 2026-10-24 核定流程處理。
