# UI-D 瀏覽器驗收紀錄

## 執行環境

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#114](https://github.com/liujiawei-tw/supplypilot/issues/114)
- Branch：`codex/sp908-ui-d-frontend-quality`
- 測試時 Git HEAD：`6afa095ebbd761c6294789824fdb1b7280cd2bd6`；前端變更在工作樹，尚未 commit。
- Browser：Microsoft Edge `155.0.4283.62`（headed）
- Playwright：`1.64.1`
- 角色：`PROCUREMENT_STAFF`，controlled fixture user `ui-d-buyer`
- URL：`http://127.0.0.1:5180/purchase-orders`、`/documents`、`/dashboard`
- 資料：瀏覽器攔截 API 的受控 Fixture；120 筆供應商與 120 筆物料。**不是 Backend 整合測試，也不是實體手機／平板測試。**

## 操作結果

| 檢查 | 結果 | 證據 |
|---|---|---|
| 新增 PO，375 × 812 CSS px | PASS；鍵盤搜尋／選取 Supplier ID 120、Material ID 101，POST payload ID 正確；Modal 置中且 Escape 後焦點回到開啟按鈕 | `screenshots/po-modal-375.png`、manifest `po-create-375` |
| 新增 PO，768 × 1024 CSS px | PASS；同上 | `screenshots/po-modal-768.png`、manifest `po-create-768` |
| 新增 PO，1440 × 1000 CSS px | PASS；同上 | `screenshots/po-modal-1440.png`、manifest `po-create-1440` |
| 文件 Drawer，375 × 812 CSS px | PASS；長標題可見，Drawer 在 viewport 內 | `screenshots/document-drawer-375.png` |
| AI Drawer，375 × 812 CSS px | PASS；Drawer 寬度符合 viewport，可見且操作按鈕可開啟 | `screenshots/ai-drawer-375.png` |
| PO 清單水平捲動，375 × 812 CSS px | PASS；表格寬 434px、容器 280px，`overflow-x:auto` 且可捲至表格尾端；頁面寬 360px，沒有超出 375px viewport | `screenshots/po-list-375.png`、manifest `po-list-horizontal-scroll-375` |
| Sidebar 開合，375 × 812 CSS px | PASS；Sidebar Trigger 可見並可展開側欄 | `screenshots/sidebar-375.png`、manifest `sidebar-toggle-375` |
| App Header，375／768／1440 CSS px | PASS；Header 未超出 viewport，AI 助理按鈕矩形在 viewport 內；375px 窄螢幕缺陷已修正 | manifest `app-header-*` |
| Modal 鍵盤與焦點 | PASS；以 Enter 開啟、Escape 關閉、焦點返回；Select 搜尋後以 ArrowDown／Enter 選取 | manifest `po-create-*` |
| Browser Zoom 200% | BLOCKED；對 headed Edge page 發送 `Ctrl+Plus`（fallback `Ctrl+Shift+Equal`）各 8 次後，`devicePixelRatio=1`、CSS viewport 仍為 1440 × 1000。這沒有改變瀏覽器縮放，故不視為 200% 驗收。 | manifest `browser-zoom-200`；`screenshots/browser-zoom-200.png` 是未縮放畫面 |

## 可重現缺陷與修正

初次在 375px 點擊「AI 助理」時，Edge 回報按鈕在 viewport 外，無法操作。將 App Header 在窄螢幕改為自然增高並換行，標題與操作列可使用完整可視寬度。修正後 Edge 在 375／768／1440px 的 Header 檢查均通過，375px 的 AI 助理按鈕可點擊。桌面版保持原本排列。

PO 清單初次量測時，表格 `scrollWidth=434px`、容器 `clientWidth=280px`、`overflow-x=visible`，並使文件寬至 474px，無法在窄螢幕操作尾端欄位。為表格啟用 Ant Design 水平捲動後，`overflow-x=auto`、可捲動至尾端，頁面寬度回到 360px。修正前畫面保留於 `screenshots/po-list-375-before.png`。

## API 與限制

Playwright route 使用本地受控 Fixture 回應 `/auth/me`、`/purchase-orders`、`/suppliers`、`/materials`、`/documents`。供應商與物料查詢在頁面操作時依 code/name 與頁碼回傳資料；POST `/purchase-orders` 收到 Supplier `120` 與 Material `101`。完整 Request 路徑、參數、狀態碼及案例資料見 [`edge-fixture-manifest.json`](./edge-fixture-manifest.json)。無真實 Backend 呼叫。

200% Browser Zoom 尚未完成：Playwright 頁面鍵盤不控制 Edge 瀏覽器介面；本回合 Computer Use 在無法可靠確認目前 browser URL 後停止。需由可控制 Edge 瀏覽器 UI 的工作階段實際調整縮放，再記錄有效 CSS viewport 與操作證據。沒有用 `deviceScaleFactor=2` 代替 Browser Zoom。

## 程式驗證

- `npm run lint`：PASS。
- `npm test -- --reporter=dot`：首次全套 23 檔、197 項，20 檔通過、3 檔失敗；182 項通過、15 項失敗，含 timeout 及一個未找到 `SUP-001` 的清單斷言。
- `npm test -- --reporter=dot --maxWorkers=2`：22 檔通過、1 檔失敗；195/197 通過，剩 2 個 SP-806 AI 對話案例超過該測試明確設定的 30 秒。
- 兩個剩餘 SP-806 案例以 `--maxWorkers=1` 合併隔離重跑：2/2 通過，實際測試時間共 46.96 秒。第一次逾時的 Material 建立案例隔離重跑：通過，15.77 秒。
- 本次最後修改後，RelatedEntitySelect 元件回歸：9/9 通過；Lint 與 Build 亦在最後修改後重跑通過。
- 本次最後修改後，PO 新增與編輯的 page-6 主檔精確 ID／舊 Draft 快照保留案例：2/2 通過。
- Production Build：PASS，JS `1,216.76 kB`（gzip `390.29 kB`）；保留既有 Vite 500 kB chunk 警告，未因 warning 做未經量測支持的 bundle 重構。
- 搜尋輸入套用 250ms debounce；120 筆選擇器測試確認完整 code/name 條件各只發出 1 次對應查詢。沒有收集舊實作的計時／請求數作前後基準，因此不宣稱效能百分比改善。
- 全套測試在目前環境仍有 timeout；隔離後失敗案例通過，但全套未取得一次全綠結果。
- GitHub Actions：[`CI Merge Gate`](https://github.com/liujiawei-tw/supplypilot/actions/workflows/ci.yml) 設定有 push trigger，但本次沒有取得 run/job/step 明細。已查 commit combined status（`statuses=[]`）及可用的 commit workflow-runs 介面（回傳空清單；此介面只列 PR-triggered runs）；未登入的 REST 查詢回 404。故 CI 終態維持 NOT VERIFIED，不能宣稱 SUCCESS。

## 追補驗收（2026-10-17）

本節追補既有紀錄，不覆寫前次環境／縮放阻擋事實。

- GitHub Actions run [`39036428482`](https://github.com/liujiawei-tw/supplypilot/actions/runs/39036428482) 使用含程式碼 commit `cbf62297a613d5f0a70fa2361442b1c5dea6913e`。`Frontend CI` job 與 `Install dependencies`、`Lint`、`Test`、`Build` steps 均為 SUCCESS；`Backend CI` 為預期的 SKIPPED（前端變更）。CI log 的 `npm test` 結果為 23/23 test files、198/198 tests PASS，測試耗時 801.44 秒；Build 產物 JS 1,216.76 kB、gzip 390.29 kB，仍有既有 500 kB Vite chunk warning。詳見 [`actions-run-verification.json`](./actions-run-verification.json)。
- 文件追補 commit `d8c621763426c3c58cad90e3c197076384c61d29` 的 run [`39036554417`](https://github.com/liujiawei-tw/supplypilot/actions/runs/39036554417) 為 workflow SUCCESS，但 Frontend CI、Backend CI 都是 SKIPPED。這次文件變更 run 不作為程式碼驗證；程式碼驗證以 run `39036428482` 為準。
- 本機以預設 `npm test`（Vitest 仍使用 repo 設定的單 worker 與 30 秒個別 test timeout）完整重跑：23/23 files、198/198 tests PASS，耗時 946.43 秒。首次嘗試在受限制的系統 Temp 路徑發生 EPERM rename、尚未進入測試；重跑只將 `TEMP`／`TMP` 指向工作區暫存目錄，沒有更改測試程式、斷言、timeout 或 worker 設定。
- 本機 `npm run lint` PASS；`npm run build` PASS，JS 1,216.76 kB、gzip 390.29 kB，Vite 500 kB chunk warning 保留。`git diff --check` PASS。
- 200% 原生瀏覽器縮放仍為 BLOCKED。此次 Computer Use inventory 沒有列出原生 app/window，僅有 IAB 與 Codex MCP Apps；目前工作階段也未提供 `listWindows`。沒有把 CSS viewport、deviceScaleFactor 或模擬縮放冒充 200% Browser Zoom。新補的環境觀察見 [`native-edge-zoom-followup.json`](./native-edge-zoom-followup.json)；原先 `browser-zoom-200` BLOCKED 記錄與未縮放 screenshot 均保留。
- Impeccable：讀取已安裝 Skill 4.5.2 的 audit/harden 指引並遵循 Operate 原則；未成功執行 CLI，前次 launcher 回報 cache directory failure，因此不宣稱已跑 Impeccable CLI audit。
