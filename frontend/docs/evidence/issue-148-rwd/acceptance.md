# Issue #148：瀏覽器與 RWD 驗收證據

日期：2026-10-24（Asia/Taipei）
分支：`codex/issue-148-ui-format-rwd`
基底：`f9df23a6ee813bae0ffd904987c9c2cfb744c39c`
範圍：`frontend/src/layouts/AppLayout.tsx`、`frontend/src/layouts/AppLayout.css` 與隔離的 UI mock API fixture `frontend/tests/issue148-mock-api.mjs`。PO-number search 未變更。

## 環境與驗收標籤

- 真實 Chrome（CUA）瀏覽器操作；前端為本機 Vite `http://127.0.0.1:5173`，後端資料由隔離 mock API `http://127.0.0.1:9090` 回應。fixture 僅用測試身份 `ui-test/ui-test-only` 與固定資料。
- 下列瀏覽器結果全部標記 **MOCKED UI PASS**，只證明 UI、版面和 mock 流程。沒有連接真實後端，也沒有把 mock POST 說成 authenticated E2E。
- **AUTHENTICATED E2E：BLOCKED / NOT RUN**。隔離的 Testcontainers/API 憑證流程無法啟動（Docker engine 不可用／權限錯誤）；未讀取、輸出、猜測或修改任何 production credential。
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

- PO List supplierId 查詢在 320/390 及 768/1280 的 List 模式操作：供應商 ID 2 顯示 `PO-2026-000902`，排除 `PO-2026-000901`，四個尺寸均無外層 overflow。**PO-number filter：NOT RUN**，原因是目前 UI 沒有 PO number 查詢輸入欄位；本工作不改 #144 搜尋行為。
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
