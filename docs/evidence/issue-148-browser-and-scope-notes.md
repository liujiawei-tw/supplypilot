# Issue #148 驗收與範圍紀錄（2026-10-24）

## File ownership matrix

- Frontend 顯示與 RWD：共用 `displayFormatters.ts`、PO detail/list、Supplier/SupplierMaterial、Material、Inventory/StockTransactions、Dashboard、AppLayout，以及受影響 Vitest。
- PO 幣別 API 擴充（使用者已明確選擇納入）：`PurchaseOrderItem` currency snapshot、PO create/update service、response mapper/DTO、V31 migration、PO controller/update/migration integration tests、前端 response type/detail display、`docs/api.md`。
- 明確排除：PO search/query handlers、#144 搜尋欄與 request-state 行為、#147 demo/RAG 資料與介面；未修改 request DTO 接收幣別，未改 Receiving 專用白名單回應。

## Browser

- Chrome 本機 Vite 登入頁：桌面 1280×900，`innerWidth`、`documentElement.scrollWidth`、`body.scrollWidth` 皆為 1280；表單置中，無水平溢位。
- 手機 320×800：三種寬度指標皆為 320，無水平溢位。
- 手機直向 390×844：三種寬度指標皆為 390，無水平溢位。
- 截圖由 root CUA inline 擷取，未保存為 worktree 圖檔。
- 僅驗證未登入的 `/login`。本機 8080 API 未啟動，SupplyPilot 資料庫 Compose 因缺少 `DB_PASSWORD` 無法取得服務設定；未讀取或猜測憑證。PO、Supplier、SupplierMaterial、Material、Inventory、Dashboard、AI 等受保護畫面與操作：`NOT RUN / BLOCKED`。
- 受保護畫面的鍵盤操作、200% zoom、loading、empty、error、長文字狀態：`NOT RUN`。登入頁鍵盤／200% zoom 亦未驗收。
- Vite 最終只綁定 `127.0.0.1:5173`，驗收後已停止；目前無 5173 listener。啟動記錄：`issue-148-vite-localhost.log`。

## CSS 與 UI 驗證

- `AppLayout.css`、`DashboardPage.css` 均通過 PostCSS 語法解析，且用 Vitest 使用的 jsdom CSSOM 注入時 `jsdom css errors=0`；production build 成功。最終 Vitest 中仍見 `Could not parse CSS stylesheet` 與 jsdom pseudo-element `getComputedStyle` 訊息；新 CSS 靜態解析無錯，但 Vitest warning 的確切來源未確認，完整輸出保留於測試 log。
- 原多 worker 五檔測試 FAIL 保留於 `issue-148-vitest-focused-rerun.log`（9/46 failed，含併行逾時）；zh-TW 變更後、timeout 調整前的五檔單 worker rerun 保留於 `issue-148-vitest-final-60s.log`（45/46 passed，一個 SP-805 case 找不到按鈕）。較早的五檔結果 `issue-148-vitest-final.log`（45/46，一個 page-6 測試逾時）及其隔離 PASS（26.34 秒，`issue-148-sp805-page6-isolated-final.log`）皆保留。
- 最終 `impeccable detect --json` 已掃描所有本次修改的 formatter、PO detail/list、Supplier/SupplierMaterial、Material、Inventory、StockTransactions、Dashboard、AppLayout TSX/CSS targets，結果 `[]`、exit 0，見 `issue-148-impeccable-detect-final.log`。

## PO 幣別快照 API

- 使用者已明確授權將最小 PO item API currency 欄位納入 #148。建立及替換草稿品項從該 PO 供應商的有效 SupplierMaterial 取得貨幣；更新供應商但保留 items 時依新供應商關係刷新快照；普通交期等非商務更新保留原快照。
- 新增 response-only `PurchaseOrderItemResponse.currency`，request 不接受客戶端 currency；UI 從該快照格式化單價及小計，不查詢當前 SupplierMaterial。
- V31 將既有 rows 回填為 `TWD` 並設 NOT NULL/check。現有 enum 與 SupplierMaterial 資料庫 CHECK 僅允許 `TWD`，因此目前整合測試不能建立另一幣別以做差異比對。
- Maven 精準測試 `PurchaseOrderControllerIntegrationTests,PurchaseOrderUpdateIntegrationTests,V31PurchaseOrderCurrencyMigrationIntegrationTests`：20 tests、0 failures/errors/skipped、exit 0；MySQL 8.4 Testcontainers 啟動成功並完成舊資料回填、NOT NULL、CHECK constraint 驗證。原第一次 Maven 編譯失敗保留於 `issue-148-maven-currency-tests.log`，修正 import 後成功結果保留於 `issue-148-maven-currency-tests-rerun.log`。Repo migration 採 MySQL/InnoDB，未發現 PostgreSQL profile。

## Frontend 本機驗證

- `npm run lint`：exit 0；有一個既有 warning 位於未修改的 `src/pages/Documents/SemanticOperationsPanel.tsx:98:26`，完整輸出 `issue-148-lint-currency-final.log`。
- `npm run build`：exit 0；TypeScript 與 Vite build 完成（3,257 modules），有既有大 chunk warning；完整輸出 `issue-148-build-currency-final.log`。
- 初次最終五檔單 worker suite（60s per-test timeout）為 45/46，唯一失敗為 `SP-805 PO lifecycle UI > confirms the move into receiving inspection without issuing a receive command`，Testing Library 預設約 1s async query timeout 時找不到「開始到貨驗收」；完整 FAIL 保留於 `issue-148-vitest-final-60s.log`，未覆寫。
- 完全相同 case 單獨執行 1/1 PASS、9.62s（`issue-148-sp805-failedcase-isolated-final.log`）；整個 `sp805.test.tsx` 單 worker 隔離重跑 18/18 PASS、exit 0、259.93s（summary evidence `issue-148-sp805-file-isolated-summary.log`）。該五檔 failure 約 1.16s 即在預設 async query wait 超時，顯示 test-runner 資源負載下可發生延遲；依 root 同意，僅將這個既有 `findByRole` 的 wait 從預設 1s 增至 5s，selector 和 assertion 不變。
- timeout 調整後重新執行相同五檔單 worker suite，`--testTimeout=60000`：5 files、46 tests 全通過，0 failures/errors/skipped，exit 0，duration 354.68s，完整 log `issue-148-vitest-final-timeout-adjusted.log`。舊 45/46 FAIL 仍保留，沒有改寫。
- 五檔 suite 中仍有 `Could not parse CSS stylesheet` 的 jsdom stderr 警告；另以 PostCSS 與 Vitest jsdom CSSOM 對新改的 AppLayout/Dashboard CSS 驗證皆通過，見 `issue-148-css-parse.log` 與 `issue-148-jsdom-css-parse.log`。前端 production build exit 0。warning 來源未確認，沒有宣稱其無關或消失。
- 最終 staged code/docs `git diff --cached --check -- . ':!docs/evidence/*.log'` exit 0；原始 `.log` 內容維持原樣，因此 Maven/build 輸出中的 logger 空格會被完整 log whitespace check 標示，沒有改寫原始證據。
