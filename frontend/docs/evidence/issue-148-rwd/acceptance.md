# Issue #148：瀏覽器與 RWD 驗收證據

日期：2026-10-24（Asia/Taipei）
分支：`codex/issue-148-ui-format-rwd`
基底：`f9df23a6ee813bae0ffd904987c9c2cfb744c39c`
範圍：`frontend/src/layouts/AppLayout.tsx`、`frontend/src/layouts/AppLayout.css` 與隔離的 UI mock API fixture `frontend/tests/issue148-mock-api.mjs`。PO-number search 未變更。

## 環境與驗收標籤

- 真實 Chrome（CUA）瀏覽器操作；前端為本機 Vite `http://127.0.0.1:5173`，後端資料由隔離 mock API `http://127.0.0.1:9090` 回應。fixture 僅用測試身份 `ui-test/ui-test-only` 與固定資料。
- 下列瀏覽器結果全部標記 **MOCKED UI PASS**，只證明 UI、版面和 mock 流程。沒有連接真實後端，也沒有把 mock POST 說成 authenticated E2E。
- **AUTHENTICATED E2E：BLOCKED / NOT RUN**。本次已用 Testcontainers MySQL 完成 Backend integration tests，但沒有啟動帶有隔離測試 API 與測試身份的 Browser 流程；Backend Testcontainers PASS 不等於 authenticated Browser E2E。未讀取、輸出、猜測或修改任何 production credential。
- **真實 200% browser zoom：NOT RUN / BLOCKED**。可用 CUA 只提供 viewport override，無法控制 Chrome zoom；未以 CSS scale 冒充。一般瀏覽器 viewport 仍用實際 Chrome 驗收。
- 圖片：CUA 在 Chrome 中直接捕捉的畫面保留於本次工作階段的工具輸出；CUA 沒有提供可寫入本 worktree 的 screenshot file API，因此**沒有可引用的截圖檔案路徑**。本文件可隨提交保存；原始 logs 於下方所述 worktree 路徑另外保留。
- 原始 terminal stdout/stderr 逐字保存在 worktree 的 `frontend/docs/evidence/issue-148-rwd/logs/`；此目錄被 `frontend/.gitignore` 忽略，因此不納入產品 commit。原始檔未正規化或修改；若後續封存 worktree，請先另行保存此目錄。

## 窄螢幕 Header 與 358px 冷載

修正前，320px 寬時 Sider trigger 位於 x=0–40、AI 按鈕 x=24–122，兩者實際重疊。358px 冷載亦曾重現 Sider 首幀仍為 200px 寬、AI 按鈕右緣 x=362（超出 viewport），body/document 寬 370px 的溢位。

修正只針對觀察到的 Header：窄螢幕 AI 按鈕左邊距由 40px 調為 28px，並在 React 初始 render 以 `matchMedia('(max-width: 991px)')` 初始化 Sider collapsed 狀態，避免 breakpoint listener 生效前先畫出展開 Sider。

最終程式碼下，在 Chrome 設定 viewport 為 358×900，做 3 次 cold navigation/reload；每次於 DOMContentLoaded 及約 +250ms、+400ms 讀取 body/document scrollWidth 與 DOM rect：

| 冷載 | DOMContentLoaded body/document 寬 | +250ms | +400ms | Sider 初始狀態 |
|---|---:|---:|---:|---|
| 1 | 358px | 343px | 343px | collapsed、寬度 0 |
| 2 | 343px | 343px | 343px | collapsed、寬度 0 |
| 3 | 343px | 343px | 343px | collapsed、寬度 0 |

三次都沒有超過 358px。320px 下 trigger x=0–40、AI 按鈕 x=52–150，留有 12px 間距且不重疊。358px 的第一次 body/document 寬等於 viewport；後續 343px 是內容自然寬度，並非溢位。修正後不再重現初始 370px 橫向溢位。

## 瀏覽器矩陣

對下列 7 條路由，分別在 320、390、768、1280 CSS px × 900px 執行；每個路由/尺寸至少 5 個穩定取樣，共 140 個穩定樣本。判定條件為 body/document scrollWidth 不大於 viewport，Header AI 按鈕與 Sider trigger 不重疊。

| 路由／視圖 | 320 | 390 | 768 | 1280 |
|---|---|---|---|---|
| Dashboard | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS |
| PO Kanban／List | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS |
| PO Detail `/purchase-orders/901` | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS |
| Supplier List | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS |
| Supplier Material tab | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS |
| Material List | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS |
| Inventory | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS |
| Documents | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS | MOCKED UI PASS |

表內 7 route groups 中，Supplier Material 是 `/suppliers` 頁面的分頁狀態；上列分成獨立驗收項。PO Kanban/List 在手機預設 List；768/1280 另外切換 List 驗證。Drawer 的幾何與 Escape 流程另列如下。

### 查詢、內容與互動

- 合併前 PO List supplierId 查詢在 320/390 及 768/1280 的 List 模式操作：供應商 ID 2 顯示 `PO-2026-000902`，排除 `PO-2026-000901`，四個尺寸均無外層 overflow。該次 browser run 的 #148 分支尚未含 #144 新增的 PO number 查詢欄位，因此 PO-number 真瀏覽器查詢標為 NOT RUN。#144 合併後，merge-sha grouped Vitest 中 `defaults to desktop Kanban and preserves the active filter across both views` 通過，涵蓋 PO number 輸入與 `poNumber` API query；該合併後 viewport/browser 矩陣仍 NOT RUN。
- Supplier List 在 320/390 以 `台灣國際` 查詢，長供應商名稱可見且排除 `SUP-002`。Material List 在 320/390 以 `MAT-000001` 查詢，目標列存在且排除 `MAT-000002`。Supplier Material 長 supplier/material 名稱四尺寸均可讀且無外層 overflow。
- Dashboard 顯示長供應商名稱；PO Detail 顯示長名稱、大數值、原幣別與日期：數量 `1,234,567,890.5000 KG`、單價 `TWD 123,456.7890`、合計 `TWD 152,415,787,532,388.3675`。UTC `2026-10-24T16:05Z` 顯示為台北時間 `2026/10/25 00:05`。
- Inventory 顯示 `1,234,567,890,123.5 KG`；資料表在內層可捲動，不產生頁面水平溢位。
- Documents 在 320px 檢查 mock empty state、503 safe error/reload、延遲 loading spinner，以及台北時間 `2026/10/25 上午12:05:00`。Detail Drawer 寬 320px、Status Select popup 保持在 viewport 內，均可用 Escape 關閉。
- Material 新增 Modal 在 320px 位於 x=8–312（寬304px），以 Escape 關閉，沒有送出。這只是 layout 操作。
- AI Assistant Drawer：320px x=0/w=320；390px x=0/w=390；768px x=248/w=520；1280px x=760/w=520。各尺寸內容及外層均無 overflow，Escape 可關閉。
- 真實 Chrome 手機鍵盤流程（320px）：Tab 到 collapsed navigation menu，再 Tab 聚焦 `.app-header__ai-button`；Enter 開啟 AI 助理，Escape 關閉。另在桌面用鍵盤 ArrowRight 切換 PO Kanban→List，列表顯示 2 筆。
- 只操作本地 mock 查詢、開關 Select/Modal/Drawer；沒有確認 PO、庫存調整、文件上傳/核准、AI proposal 確認或其他產品寫入。AI query 僅到隔離 mock API，沒有連接 AI provider。

## 最終差異上的精準檢查

以下命令皆在 `frontend/` 執行；raw output 保留在本目錄的 `logs/`。

| 命令／涵蓋 | 結果 | Raw log |
|---|---|---|
| `npm run test -- --reporter=verbose --maxWorkers=1 tests/auth.test.tsx` | EXIT 0；1 file、21/21 tests PASS | `logs/auth-final.txt` |
| `npm run test -- --reporter=verbose --maxWorkers=1 tests/issue143PurchaseOrdersPage.test.tsx` | EXIT 0；1 file、5/5 tests PASS | `logs/po-page-final.txt` |
| `npm run test -- --reporter=verbose --maxWorkers=1 --testNamePattern='keeps the route unchanged|isolates AI 503' tests/sp806.test.tsx` | EXIT 0；2 passed、26 skipped（名稱篩選） | `logs/ai-drawer-final.txt` |
| `npm run test -- --reporter=verbose --maxWorkers=1 --testNamePattern='locks the old Proposal after AI_PROPOSAL_ALREADY_CONFIRMED' tests/sp806.test.tsx` | EXIT 0；1 passed、27 skipped（名稱篩選） | `logs/ai-proposal-confirmed-final.txt` |
| `npm run test -- --reporter=verbose --maxWorkers=1 --testNamePattern='locks the old Proposal after AI_PROPOSAL_EXPIRED' tests/sp806.test.tsx` | EXIT 0；1 passed、27 skipped（名稱篩選） | `logs/ai-proposal-expired-final.txt` |
| `npm run test -- --reporter=verbose --maxWorkers=1 tests/issue148DisplayFormatters.test.ts tests/antdLocale.test.tsx` | EXIT 0；2 files、5/5 tests PASS | `logs/formatters-locale-final.txt` |
| `npm run lint` | EXIT 0；有一條既有 `SemanticOperationsPanel.tsx:98` `react(set-state-in-effect)` warning | `logs/lint-final.txt` |
| `npm run build` | EXIT 0；TypeScript 與 Vite build 完成；bundle >500kB warning | `logs/build-final.txt` |
| Impeccable detector：`& 'C:\Users\z0976\.codex\skills\impeccable\scripts\impeccable.cmd' detect --json --target frontend/src/layouts/AppLayout.tsx --target frontend/src/layouts/AppLayout.css`（對 AppLayout TSX/CSS） | EXIT 0；`[]` findings | CUA/terminal output |

精準測試沒有改 timeout、skip 或 assertion。完整 formatter/locale 與 auth/PO/AI 互動皆在最後的 collapsed-state 產品差異上重新執行。

## 完整 Vitest 原始 FAIL 與分診

保留原始命令 `npm run test -- --reporter=verbose` 的非零結果，沒有把它改寫為 PASS：

- **原始結果：EXIT 1；29 files：22 passed、7 failed；262 tests：225 passed、37 failed。**
- 原始完整輸出：`logs/vitest.txt`。該完整 runner 與當時的 precision Vitest runner 曾同時執行；Vitest 設定 `maxWorkers: '50%'`，多個失敗是 15–60 秒 timeout。另有若干 feature assertion 找不到預期按鈕/日期/訊息，原樣記錄，不用資源競爭籠統抹除。
- 7 個失敗 suites 與主要錯誤摘要：
  1. `tests/demandInventory.test.tsx`（2）：庫存調整 30 秒 timeout；409 case 找不到「調整庫存」。
  2. `tests/issue143PurchaseOrdersPage.test.tsx`（3）：三個 draft create 流程 timeout。完整 suite 結束後已在單檔、`--maxWorkers=1` 重新執行；該檔 5/5 通過。
  3. `tests/masterData.test.tsx`（4）：Material/Supplier/SupplierMaterial create/edit 流程 timeout（15、30、40、60 秒）。
  4. `tests/recommendation.test.tsx`（2）：日期驗證文字 assertion 不符；Recommendation→PO Draft 流程 timeout。
  5. `tests/sp805.test.tsx`（9）：PO 日期/取消/駁回/收貨按鈕 assertion 找不到預期元素，以及多個 PO 操作 timeout。
  6. `tests/sp806.test.tsx`（16）：文件審核、延遲回應、稽核歷史、上傳/ingestion 與 AI proposal 流程 timeout；若干頁面找不到預期控制項。
  7. `tests/sp807.test.tsx`（1）：收貨按鈕 assertion 找不到預期元素。
- 本 Issue 直接共用路徑另有 final-diff auth 21/21、PO page 5/5、AI header/drawer 開關與 route 2/2、兩個曾 timeout 的 proposal lock case 各自 1/1 通過。Vitest setup 將 `matchMedia.matches` 固定為 false，所以新增窄螢幕初始 collapsed 行為不影響 JSDOM desktop 測試路徑；其餘 suite 失敗指向各自的 Inventory/Master Data/Recommendation/PO lifecycle/Documents/Receiving 操作，並非 AppLayout Header/CSS 斷言。
- 依 V2 精準 CI 決策，以上完整套件仍標 **FAIL — DEFERRED TO V2 INTEGRATION**，非完整通過。保留原始 log、failure 名稱與斷言；V2 整合需重跑全套並處理已登記失敗。
- GitHub Actions：**PAUSED BY USER / NOT USED AS GATE**。

## 修正摘要

僅修正實測 Header 問題：AI 助理按鈕獲得明確 class、窄螢幕邊距調為 28px，Sider 在初次 render 即依 lg breakpoint collapsed。沒有改 PO 搜尋或 #144 共用 PO page 行為。
## 原始完整 Vitest 37 個 failure 逐案分診

原始 npm run test -- --reporter=verbose 結果仍保留為 **EXIT 1；29 files（22 passed／7 failed）；262 tests（225 passed／37 failed）**，原始 stdout/stderr 為 logs/vitest.txt。不改寫原始 FAIL。

已知該完整 runner 與 precision Vitest 曾重疊執行，且 #144 Vitest 曾在途中啟動；後續七個失敗 suite 均以 --maxWorkers=1 在無前端測試併跑時逐檔重跑。下表逐列保留原始 37 個測試名稱和原始錯誤；除 Demand 409 案外，其餘 36 案在同一 #148 HEAD 的 clean serial suite rerun 均通過，且程式未因此修改，判定為原執行資源競爭／非重現，沒有重現產品回歸。各 suite 的原始 serial output 保留於 logs/triage-*.txt。

| # | 原始失敗案例（raw test title） | 原始完整 runner 錯誤 | 單檔 serial rerun 結果／分類 |
|---:|---|---|---|
| 1 | SP-802 Inventory UI > shows detail and allows Warehouse to submit a signed adjustment exactly once before refetch | 30s timeout | PASS；demandInventory.test.tsx clean serial suite |
| 2 | SP-802 Inventory UI > keeps adjustment input and marks a 409 result unknown without retrying | 找不到 調整庫存 按鈕 | FAIL；精確案例在 #148 branch 與 clean origin/main 均 30s timeout；同基線既有測試／環境失敗，不歸因於 #148。Branch serial log triage-demandInventory.txt；隔離案例 triage-demandInventory-409-isolated.txt；main 對照 triage-main-demandInventory-409.txt |
| 3 | Issue #143 purchase-order page views > refreshes the current Kanban from backend after a successful draft create | 30s timeout | PASS；issue143PurchaseOrdersPage.test.tsx clean serial suite；另於 clean main 精確案例 PASS 1／1（4 skipped），triage-main-issue143-draft-create.txt |
| 4 | Issue #143 purchase-order page views > does not refresh the Kanban or report success after a 403 draft-create failure | 30s timeout | PASS；PO clean serial suite |
| 5 | Issue #143 purchase-order page views > does not refresh the Kanban or report success after a network error draft-create failure | 30s timeout | PASS；PO clean serial suite |
| 6 | master data pages > validates Material fields, creates once, then refetches | 30s timeout | PASS；masterData.test.tsx clean serial suite |
| 7 | master data pages > shows Material edit validation errors inside the modal and keeps edited values | 15s timeout | PASS；masterData clean serial suite |
| 8 | master data pages > keeps Supplier create and edit values when backend submissions fail | 40s timeout | PASS；masterData clean serial suite |
| 9 | master data pages > creates and edits SupplierMaterial through the UI without PATCHing immutable relationships or currency | 60s timeout | PASS；masterData clean serial suite |
| 10 | SP-804 Recommendation create > validates required fields and date order before sending a request | 找不到驗證文字 期間起日不可晚於迄日 | PASS；recommendation.test.tsx clean serial suite |
| 11 | SP-804 Recommendation to linked PO Draft > requires both override reasons and sends the selected result snapshot without decimal arithmetic | 30s timeout | PASS；recommendation clean serial suite |
| 12 | SP-805 PO lifecycle UI > shows both delivery dates and updates the supplier promise without changing the expected date | 找不到日期文字 2026/10/30 | PASS；sp805.test.tsx clean serial suite |
| 13 | SP-805 PO lifecycle UI > does not retry after a conflict and requires querying the actual PO state | 找不到 取消採購單 按鈕 | PASS；sp805 clean serial suite |
| 14 | SP-805 PO lifecycle UI > moves an ordered PO into receiving inspection and removes its cancellation action | 找不到 取消採購單 按鈕 | PASS；sp805 clean serial suite |
| 15 | SP-805 PO lifecycle UI > creates a Manual DRAFT with exact decimal strings after explicit confirmation | 30s timeout | PASS；sp805 clean serial suite |
| 16 | SP-805 PO lifecycle UI > creates a DRAFT with the exact IDs of page-6 master records and does not confuse duplicate names | 30s timeout | PASS；sp805 clean serial suite |
| 17 | SP-805 PO lifecycle UI > keeps linked recommendation evidence while filling delivery date, approving, and marking ordered | 30s timeout | PASS；sp805 clean serial suite |
| 18 | SP-805 PO lifecycle UI > requires a non-blank reject reason before enabling the confirmed request | 找不到 /駁\s*回/ 按鈕 | PASS；sp805 clean serial suite |
| 19 | SP-805 receiving and stock security boundaries > locks a 409 receive result until an explicit restricted refetch and never auto-retries | 找不到 確認整筆收貨 按鈕 | PASS；sp805 clean serial suite |
| 20 | SP-805 receiving and stock security boundaries > handles a receiving timeout by refetching RECEIVED without automatically resending | 找不到 確認整筆收貨 按鈕 | PASS；sp805 clean serial suite |
| 21 | SP-806 Document Management UI > confirms outbound approval and renders the actual 後端 Review result | 30s timeout | PASS；sp806.test.tsx clean serial suite |
| 22 | SP-806 Document Management UI > ignores a delayed title response after switching documents | 30s timeout | PASS；sp806 clean serial suite |
| 23 | SP-806 Document Management UI > ignores a delayed archive response after switching documents | 30s timeout | PASS；sp806 clean serial suite |
| 24 | SP-806 Document Management UI > ignores a delayed ingestion response after switching documents | 30s timeout | PASS；sp806 clean serial suite |
| 25 | SP-806 Document Management UI > ignores a delayed ingestion verification response after switching documents | 30s timeout | PASS；sp806 clean serial suite |
| 26 | SP-806 Document Management UI > marks saved Gate 稽核 responses historical after upload | 30s timeout | PASS；sp806 clean serial suite |
| 27 | SP-806 Document Management UI > marks saved Gate 稽核 responses historical after ingestion | 30s timeout | PASS；sp806 clean serial suite |
| 28 | SP-806 Document Management UI > marks saved Gate 稽核 responses historical after outbound approval | 30s timeout | PASS；sp806 clean serial suite |
| 29 | SP-806 Document Management UI > marks saved Gate 稽核 responses historical after outbound exclusion | 30s timeout | PASS；sp806 clean serial suite |
| 30 | SP-806 Document Management UI > locks an unknown or 409 ingestion result until the official document GET succeeds, without a resend | 30s timeout | PASS；sp806 clean serial suite |
| 31 | SP-806 Document Management UI > submits one upload on rapid clicks and locks an unknown upload instead of guessing its result | 30s timeout | PASS；sp806 clean serial suite |
| 32 | SP-806 Global AI 助理 > reviews a multi-item backend snapshot and sends one token-based Confirm with no ordinary PO create call | 30s timeout | PASS；sp806 clean serial suite |
| 33 | SP-806 Global AI 助理 > shows clarification without Confirm and locks unknown Confirm results without resending | 30s timeout | PASS；sp806 clean serial suite |
| 34 | SP-806 Global AI 助理 > locks the old Proposal after AI_PROPOSAL_ALREADY_CONFIRMED | 30s timeout | PASS；sp806 clean serial suite |
| 35 | SP-806 Global AI 助理 > locks the old Proposal after AI_PROPOSAL_EXPIRED | 30s timeout | PASS；sp806 clean serial suite |
| 36 | SP-806 Global AI 助理 > fails closed when Proposal expiresAt is invalid without calling Confirm | 30s timeout | PASS；sp806 clean serial suite |
| 37 | SP-807 receiving and import verification boundaries > verifies DUPLICATE_RECEIVING through restricted GET and reports RECEIVED without a resend | 找不到 確認整筆收貨 按鈕 | PASS；sp807.test.tsx clean serial suite |

七個 clean serial suite 結果為：Demand 1／12 passed、1 failed；PO 5／5 passed；masterData 10／10 passed；recommendation 25／25 passed；SP-805 18／18 passed；SP-806 28／28 passed；SP-807 14／14 passed。Demand 409 精確案例在 main 的單案對照同樣於 30s timeout，尚保留 FAIL；完整 262-test runner 仍是 EXIT 1。以上完整 suite 結果依 V2 決策標示 **FAIL — DEFERRED TO V2 INTEGRATION**，不宣稱全套通過。未調高任何 timeout、未刪除／skip 測試、未放寬 assertion。

frontend/src/pages/Inventory/InventoryPage.tsx 的 #148 diff 僅將庫存數量／時間格式化、使篩選換行、表格設內層橫向捲動並加 Drawer class；沒有改調整流程、按鈕或 409 處理。這與精確 409 案在 main 相同失敗相符。


### 七個原始失敗 suite 的序列重跑命令與結果

以下命令均在 frontend/ 執行，原始輸出留在 frontend/docs/evidence/issue-148-rwd/logs/：

| Suite | 命令 | 結果／raw log |
|---|---|---|
| Demand | npm run test -- --reporter=verbose --maxWorkers=1 tests/demandInventory.test.tsx | EXIT 1；1 failed、11 passed；logs/triage-demandInventory.txt |
| PO page | npm run test -- --reporter=verbose --maxWorkers=1 tests/issue143PurchaseOrdersPage.test.tsx | EXIT 0；5／5 passed；logs/triage-issue143PurchaseOrdersPage-serial-clean.txt |
| Master Data | npm run test -- --reporter=verbose --maxWorkers=1 tests/masterData.test.tsx | EXIT 0；10／10 passed；logs/triage-masterData-serial.txt |
| Recommendation | npm run test -- --reporter=verbose --maxWorkers=1 tests/recommendation.test.tsx | EXIT 0；25／25 passed；logs/triage-recommendation-serial.txt |
| SP-805 | npm run test -- --reporter=verbose --maxWorkers=1 tests/sp805.test.tsx | EXIT 0；18／18 passed；logs/triage-sp805-serial.txt |
| SP-806 | npm run test -- --reporter=verbose --maxWorkers=1 tests/sp806.test.tsx | EXIT 0；28／28 passed；logs/triage-sp806-serial.txt |
| SP-807 | npm run test -- --reporter=verbose --maxWorkers=1 tests/sp807.test.tsx | EXIT 0；14／14 passed；logs/triage-sp807-serial.txt |

Demand 精確比較在 clean main worktree 執行：npm run test -- --reporter=verbose --maxWorkers=1 --testNamePattern='keeps adjustment input and marks a 409 result unknown without retrying' tests/demandInventory.test.tsx，EXIT 1；1 failed、11 skipped，30s timeout，raw log logs/triage-main-demandInventory-409.txt。PO 成功草稿精確比較在 clean main worktree 執行：npm run test -- --reporter=verbose --maxWorkers=1 --testNamePattern='refreshes the current Kanban from backend after a successful draft create' tests/issue143PurchaseOrdersPage.test.tsx，EXIT 0；1 passed、4 skipped，raw log logs/triage-main-issue143-draft-create.txt。Clean main 的 SHA 為 9825612c6636c3543d42c3bcfcba015058b52acf。

### PO currency API 與 MySQL migration 證據

#148 相對 origin/main 的後端差異包含 PurchaseOrderItem currency snapshot、PurchaseOrderItemResponse 的 currency 欄位、mapper/service 將 SupplierMaterial 幣別放入 PO line 並在允許的草稿更新路徑刷新快照、Controller API response 契約測試，以及 Flyway migration V31。PO 建立 API 不接受使用者自行指定幣別；服務端以關聯 SupplierMaterial 的幣別為準。docs/api.md 已同步 response 欄位。

精準後端命令：backend/mvnw.cmd -f backend/pom.xml -Dtest=PurchaseOrderControllerIntegrationTests,PurchaseOrderUpdateIntegrationTests,V31PurchaseOrderCurrencyMigrationIntegrationTests test。結果 EXIT 0；20 tests run，0 failures、0 errors、0 skipped。原始成功 log：docs/evidence/issue-148-maven-currency-tests-rerun.log。該 log 使用 Testcontainers MySQL 8.4；V31PurchaseOrderCurrencyMigrationIntegrationTests 從空資料庫先 migrate 到 V30、插入沒有 currency 欄位的歷史 PO item，再 upgrade 至 V31，驗證 TWD backfill、欄位 NOT NULL、check constraint 存在，並確認 USD update 被拒。Flyway log 顯示 v1–v30 fresh migration 成功、validate 31 migrations 成功，然後套用 V31 至 schema version 31。早期一次 compile 曾因 test import package 寫錯而 EXIT 1；已修正 import，以上為修正後成功 rerun，不是把原失敗改寫成成功。

以 Flyway 12.4.0 ChecksumCalculator 對 migration resource 計算的 checksum，保留於 docs/evidence/issue-148-flyway-checksums.txt：

| 分支檔案 | Version | Flyway checksum |
|---|---:|---:|
| #148 V31__add_purchase_order_item_currency_snapshot.sql | 31 | -1035371520 |
| #147 V31__demo_baseline_approval_events.sql | 31 | -1468955155 |

#148 分支自身的 migration version scan 沒有重複數字版本；#147 worktree 亦新增另一個 V31，兩檔 checksum 不同。這是跨分支版本衝突，不是同一版本的 checksum drift；合併時需協調 migration renumber/version order，不能原樣同時保留兩個 V31。此 Issue 分支未修改任何已建立的 migration 歷史。前端 triage 後的 acceptance 文件與 checksum log 是文件／證據變更；上述產品程式與依賴未變，後端測試證據適用於本次 source HEAD bee6efde3bbe40996b34b0993a75ed39c9b74933。

## 最新 main 合併與合併後驗證

### 基底與合併

- 合併前 #148 HEAD：`aa7486c8c5ed58c6a7f7a64145f01d1f842183c5`。
- 合併前核對 `origin/main`：`e4df2d9ec5db7d4e859afe375f1fd293f2cfbcd8`。
- 在 #148 分支執行一般 `git merge origin/main`，無衝突；合併 commit：`d7787a501d9d7e65e67d33e83db9d1f521687b1b`。
- 自動合併的共同路徑包含 PO service/controller integration test、API 文件、前端 PO API 與 PO 頁面。#144 PO number 搜尋控制與 `poNumber` 查詢契約仍在合併結果；本分支未修改該搜尋。PO response 的 #148 `currency` 欄位及原幣別格式化也仍在合併後程式碼中。
- 合併後尚未產生新的產品程式碼差異；以下結果都是 merge SHA 上的驗證。

### Migration 完整掃描

命令依據：列舉 `backend/src/main/resources/db/migration` 的 `V{number}__*.sql`，依版本排序並以 `Group-Object Version` 檢查重號。原始掃描記錄：`logs/post-merge-migration-scan.txt`。

結果：31 個 migration，版本 `1–31` 每版各一個，重複版本 0，最高 V31 為 `V31__add_purchase_order_item_currency_snapshot.sql`（SHA-256 `1244483DAF1675F7D5AFA8748D2189EF7678BA66FECCE796C5606458ACC5C0E0`）。#147 未合併分支另有不同內容的 V31；跨分支整合仍需先協調版本重編，不能同時保留同版本。此分支未改寫 migration 歷史。

### 合併後前端精準 Vitest 與基線比較

命令均在 `frontend/`，均用單 worker；raw output 保留於忽略目錄 `frontend/docs/evidence/issue-148-rwd/logs/`。沒有修改 timeout、skip 或 assertion。

| 命令／SHA | 結果 | Raw log |
|---|---|---|
| `npm run test -- --reporter=verbose --maxWorkers=1 tests/issue143PurchaseOrdersPage.test.tsx tests/issue143PurchaseOrders.test.tsx tests/purchaseOrderApi.test.ts tests/issue148DisplayFormatters.test.ts tests/antdLocale.test.tsx tests/sp805.test.tsx`（#148 merge SHA） | **EXIT 1**；6 files：5 passed／1 failed；44 tests：38 passed／6 failed | `logs/post-main-intersection-vitest.txt` |
| `npm run test -- --reporter=verbose --maxWorkers=1 tests/sp805.test.tsx`（#148 merge SHA） | EXIT 0；18/18 passed；上列六例均在乾淨序列單檔運行中通過 | `logs/post-main-sp805-serial.txt` |
| `npm run test -- --reporter=verbose --maxWorkers=1 tests/issue143PurchaseOrdersPage.test.tsx tests/sp805.test.tsx`（#148 merge SHA） | EXIT 0；2 files、23/23 passed | `logs/post-main-po-page-sp805-intersection.txt` |
| 同 grouped 範圍，但在 clean `origin/main` SHA `e4df2d9ec5db7d4e859afe375f1fd293f2cfbcd8` 執行，去掉僅存在於 #148 的 formatter test | **EXIT 1**；5 files：4 passed／1 failed；40 tests：39 passed／1 failed；唯一失敗是 #143 draft-create Kanban refresh 30 秒 timeout（`tests/issue143PurchaseOrdersPage.test.tsx:291`） | `logs/main-grouped-intersection-vitest.txt` |

#148 merge SHA grouped run 的六個失敗皆在 `tests/sp805.test.tsx`，原始 stack／DOM 訊息詳見 `logs/post-main-intersection-vitest.txt`：

1. `does not retry after a conflict and requires querying the actual PO state`：找不到 `取消採購單` 按鈕；呼叫點 `tests/sp805.test.tsx:120`。
2. `moves an ordered PO into receiving inspection and removes its cancellation action`：找不到 `取消採購單` 按鈕；呼叫點 `tests/sp805.test.tsx:145`。
3. `creates a Manual DRAFT with exact decimal strings after explicit confirmation`：原訂 30 秒 timeout；測試列 `tests/sp805.test.tsx:167`。
4. `creates a DRAFT with the exact IDs of page-6 master records and does not confuse duplicate names`：原訂 30 秒 timeout；測試列 `tests/sp805.test.tsx:190`。
5. `restores off-page master labels in edit and preserves recommendation evidence and price snapshot`：原訂 30 秒 timeout；測試列 `tests/sp805.test.tsx:228`。
6. `keeps linked recommendation evidence while filling delivery date, approving, and marking ordered`：原訂 30 秒 timeout；測試列 `tests/sp805.test.tsx:268`。

不把 initial grouped EXIT 1 改寫為 PASS。後續 `sp805.test.tsx` 單檔 18/18、PO page + SP805 兩檔 23/23 均 PASS，表示這六例在序列精準範圍可通過；clean-main grouped comparison 的 #143 timeout 是另一案例，且其 stack/30 秒 timeout 可在 main 基線重現。初始 grouped 組合仍記錄為 FAIL，不能僅因較窄範圍成功而覆寫。

### 合併後 Backend、Lint 與 Build

| 命令 | 結果 | Raw log |
|---|---|---|
| `backend/mvnw.cmd '-Dtest=PurchaseOrderControllerIntegrationTests,PurchaseOrderUpdateIntegrationTests,V31PurchaseOrderCurrencyMigrationIntegrationTests' test` | EXIT 0；22 tests、0 failures／errors／skipped（controller 11、update 10、V31 migration 1） | `logs/post-merge-backend-precision.txt` |
| `npm run lint` | EXIT 0；保留既有 `SemanticOperationsPanel.tsx:98` `react(set-state-in-effect)` warning | `logs/post-merge-lint.txt` |
| `npm run build` | EXIT 0；`tsc -b` 與 Vite build 完成；3257 modules；bundle >500 kB warning | `logs/post-merge-build.txt` |

Backend 使用 Testcontainers MySQL 8.4。Flyway fresh schema 驗證 31 個 migrations；V31 專項先在空測試資料庫成功套用 V1–V30，再套用 V31 到 version 31，測試通過。這是隔離測試容器，不使用 production credentials。

### Browser 證據適用範圍與縮放

本文件較早的 320/390/768/1280、358px 冷載、鍵盤、Select/Modal/Drawer 與 UI mock 矩陣是合併前 Chrome 實測，沿用當時的 `aa7486c8c5ed58c6a7f7a64145f01d1f842183c5` UI revision；它們仍作為 `MOCKED UI PASS` 歷史證據。合併後未再控制共享 Chrome，因此 #144 合併後 PO List 搜尋的真瀏覽器矩陣是 **NOT RUN**，Authenticated E2E 維持 **BLOCKED / NOT RUN**。此前矩陣不宣稱已在 merge SHA 重跑。

原生 Chrome 200% zoom：**BLOCKED / NOT RUN**。CUA 僅能操作頁面 tab，未暴露 Chrome 視窗、選單或原生縮放控制；在 tab 嘗試 `Ctrl+plus` 與 `Ctrl+Equal` 未改變 `devicePixelRatio`（仍為 1.25）、`innerWidth`（1536）或 visual viewport scale（1）。沒有用 CSS scale、CDP page scale 或 device metrics 冒充 200%；依協作要求停止共享 Chrome 操作，亦未改變全域 Chrome zoom 設定。CUA 截圖僅留在工具輸出，沒有可引用的本機截圖路徑。

### 完整 Vitest 狀態

原始完整 Vitest 仍為 **FAIL — DEFERRED TO V2 INTEGRATION**：EXIT 1、29 files（22 passed／7 failed）、262 tests（225 passed／37 failed），原始 log `logs/vitest.txt` 不變。七個原失敗 suites 的逐案例分診與失敗名稱在上文既有表格；合併後 grouped EXIT 1 及 clean-main baseline 另以本節記錄。精準 rerun PASS 不會改寫上述任何原始 FAIL。

## 2026-10-25 GPT second-review P2 修正與驗收

### 驗證對象與程式修改

- Branch：`codex/issue-148-ui-format-rwd`
- 本節列出的產品程式與回歸測試綁定於 pre-push code SHA：`dd13f7ef2eb42d3334486618c913d6d30a9e16c5`（本驗收文件另以 docs-only commit 更新）。
- Material 的 `safetyStock` response DTO 保留 BigDecimal 字串；Material list/get/create/update response 套用 response-only lossless JSON parser。ID 與交期等整數欄位仍使用 number。
- SupplierMaterial 與 Material 編輯模式使用文字輸入及十進位字串驗證，避免 InputNumber 將 `12.3400` 正規化成 `12.34`；既有建立模式控制仍保留 InputNumber。只移除十進位欄位會把負值靜默夾為零的 `min=0` UI clamp，驗證器仍拒絕負值，且負值不送出 create/update 請求。
- 新增精確 read→edit→修改其他欄位→PATCH 測試，覆蓋 SupplierMaterial `unitPrice`、`moq`、`packageMultiple` 與 Material `safetyStock`；斷言 JSON request body 保留高精度數字及尾端零。既有 PO PATCH 行為未變。
- 產品程式在以下 build、lint 與 Vitest 驗證後沒有再變更；後續只調整測試案例及本文件。

### P2 精準驗證（Windows；Node.js v24.15.0、npm 11.12.1、Vitest 4.1.11）

| 命令／範圍 | 結果 | 證據 |
|---|---|---|
| `npm run test -- --maxWorkers=1 --testTimeout=60000 --reporter=verbose tests/precisionEditRoundTrip.test.tsx tests/supplierMaterialPrecision.test.tsx tests/masterDataApi.test.ts tests/masterData.test.tsx tests/purchaseOrderApi.test.ts tests/sp805Api.test.ts` | 6 files、31/31 passed、0 failures/errors/skipped；EXIT 0。這次完整六檔執行在最後兩個 test-only 修訂之前；產品程式相同。 | [Vitest 6-file log](../../../../docs/evidence/issue-148-review-p2-focused-ui-api-final.log) |
| `npm run test -- --maxWorkers=1 --testTimeout=60000 --reporter=verbose --testNamePattern='validates Material fields|creates and edits SupplierMaterial through' tests/masterData.test.tsx` | 2 個選取案例 passed、8 個因名稱篩選 skipped；EXIT 0。覆蓋 Material 與 SupplierMaterial 負值驗證及不得發送請求。 | [negative-validation log](../../../../docs/evidence/issue-148-review-p2-negative-validator-cases.log) |
| `npm run test -- --maxWorkers=1 --testTimeout=60000 --reporter=verbose tests/precisionEditRoundTrip.test.tsx` | 1 file、2/2 passed；EXIT 0。 | [edit-roundtrip log](../../../../docs/evidence/issue-148-review-p2-edit-roundtrip-final.log) |
| `npm run lint` | EXIT 0；僅有既有、未修改的 `SemanticOperationsPanel.tsx:98` `react(set-state-in-effect)` warning。 | [lint log](../../../../docs/evidence/issue-148-review-p2-lint-validated.log) |
| `npm run build` | EXIT 0；Vite build 成功，保留既有大型 bundle warning。 | [build log](../../../../docs/evidence/issue-148-review-p2-build.log) |
| 最終 UI targets 的 `impeccable detect --json` | EXIT 0；輸出 `[]`。 | [detector log](../../../../docs/evidence/issue-148-impeccable-detect-p2-final.log) |

這次 P2 的初始失敗及診斷輸出全部保留於 `docs/evidence/issue-148-review-p2-*.log`；包括編輯回歸測試的初始 adapter route 測試 harness 錯誤，以及負值被 InputNumber clamp 的重現。未刪除或放寬 PO、交易或既有失敗斷言。

### 完整 Vitest 歷史狀態

原始完整 Vitest 結果與 37 個案例逐項分診維持原樣，仍為 **FAIL — DEFERRED TO V2 INTEGRATION**：EXIT 1、29 files（22 passed、7 failed）、262 tests（225 passed、37 failed）；原始 log 是本目錄的 `logs/vitest.txt`。本次 P2 沒有重跑或改寫該完整結果。

既有分診記錄指出，37 個案例中有 36 個在 #148 branch 的 clean serial rerun 通過；唯一未排除案例是 DemandInventory 409 操作測試，在 #148 branch 與 clean `origin/main` 對照均 30 秒 timeout，分類為同基線既有測試失敗，而非 #148 回歸。原始錯誤、各 suite rerun 與該 main 對照 log 均按前文表格保存。六檔 P2 精準測試及兩個後續 test-only 選取案例只證明其涵蓋範圍，不會把完整套件 FAIL 改成 PASS。

### Browser 與整合限制

- 最新 P2 修正未執行 Browser E2E；登入頁曾在桌面 1280×900、手機 320×800、390×844 驗證無水平溢位，但沒有可引用的持久化 screenshot。這不代表已驗收登入後 Material、SupplierMaterial、PO、Inventory 或其他受保護流程。
- Authenticated page 操作、keyboard、loading、empty、error、長文字及本次編輯 roundtrip 的 Browser 驗收：**NOT RUN**。Chrome 原生 200% zoom：**BLOCKED**（依使用者確認），未以其他縮放方式替代。
- #148 新增 `V31__add_purchase_order_item_currency_snapshot.sql`，#147 分支亦使用 V31 migration version；版本衝突與整合順序仍未解決。合併前需由整合工作核對並安排 migration version sequencing，不能把目前 #148 MySQL migration 測試視為已解決跨分支衝突。現有 checksum/檔案對照見 [V31 evidence](../../../../docs/evidence/issue-148-flyway-checksums.txt)。
- GitHub Actions 仍為 **PAUSED BY USER / NOT USED AS GATE**；本節均為本機驗證。
