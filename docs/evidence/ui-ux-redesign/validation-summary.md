# SupplyPilot UI/UX 改版驗證紀錄

日期：2026-10-26（Asia/Taipei）

## 環境與必要本機檢查

- Node.js `v24.15.0`、npm `11.12.1`、Vitest `4.1.11`、Vite `8.3.4`；GitHub Actions 依使用者決策標示 `PAUSED BY USER / NOT USED AS GATE`。
- `npm run lint`：Exit Code 0。Oxlint 仍列出路由檔 fast refresh 及五個頁面的 `set-state-in-effect` 警告，原始結果見 [frontend-lint-after-fix.log](frontend-lint-after-fix.log)。
- `npm run build`：Exit Code 0，轉譯 3,267 個模組。Vite 提醒主要 bundle 為 1,234.07 kB（gzip 394.80 kB），超過 500 kB 建議門檻；沒有 TypeScript 錯誤。原始結果見 [frontend-build-after-fix.log](frontend-build-after-fix.log)。先前首次 build 的 `Space.align="stretch"` 型別錯誤已改為支援的 `start`；舊失敗輸出保存在 [frontend-build-final.log](frontend-build-final.log)。

## Vitest

- 完整前端套件曾執行一次：34 個檔案中 31 通過、3 失敗；301 項中 294 通過、7 失敗，Exit Code 1。失敗來自改版後仍尋找舊側欄／舊文案的測試選擇器，不可把這次完整套件失敗改寫為 PASS。完整原始輸出：[frontend-full-final.log](frontend-full-final.log)。
- 修正選擇器後重跑三個受影響完整測試檔（Dashboard、Inventory Import、Receiving）：3 檔通過，34/34 項通過，Exit Code 0。完整原始輸出：[receiving-navigation-files-final.log](receiving-navigation-files-final.log)。完整前端套件沒有在這些測試斷言修改後再次執行。
- 其他受影響精準驗證：PO 頁 7/7、文件與 AI 29/29、QuickFinder 4/4、Receiving 佇列 1/1、主要地標無障礙回歸 1/1 通過；對應原始輸出保存在本目錄的 `po-page-final.log`、`documents-ai-final.log`、`quick-finder-after-build-fix.log`、`receiving-queue-focused.log` 與 `accessibility-refinements.log`。

## 瀏覽器與未執行範圍

- 本機 Vite 預覽可渲染 `/login`。目前 `127.0.0.1:8080` 沒有可用後端，且沒有登入憑證，因此沒有進入需授權的採購／收貨頁，也沒有以假資料冒充瀏覽器 E2E。
- 已登入主要流程、手機尺寸、鍵盤操作及 200% 縮放：`NOT RUN`。真實 Gemini／RAG／部署 E2E：`NOT RUN`。
- 專案使用者指示 Actions 暫停期間不作 Gate；未查詢或依賴 GitHub Actions。

## 2026-10-27：滿版登入與 Compose 預覽補驗

- `npm run lint`：Exit Code 0；保留 6 個 Oxlint 警告（1 個 route fast-refresh、5 個 set-state-in-effect），原始輸出見 [lint-final2.log](../../../.impeccable/validation/lint-final2.log)。
- `VITE_API_BASE_URL=/api/v1 npm run build`：Exit Code 0，轉譯 3,278 個模組；JS bundle 1,609.72 kB（gzip 501.78 kB），有超過 500 kB 的 Vite 建議警告。相同環境變數下結果與 Docker frontend image build 一致。原始輸出：[build-with-docker-env.log](../../../.impeccable/validation/build-with-docker-env.log)。
- 主題與路由精準測試：登入／授權 `tests/auth.test.tsx` 27/27、歷史採購建議 `tests/recommendation.test.tsx` 25/25，合併 2 檔 52/52 通過；輸出：[auth-recommendation-final.log](../../../.impeccable/validation/auth-recommendation-final.log)。
- 八個受影響 Vitest 檔案合併執行時 109/113 通過，4 項因測試逾時而 Exit Code 1；沒有將該批結果標成 PASS。4 個逾時案例各自單獨重跑皆通過：SP-805 交期 1/1、SP-806 AI 503 1/1、多品項提案與未知結果 2/2、供應商失敗表單 1/1。原始批次與精準重跑分別保留於 `.impeccable/validation/affected-ui-suites.log`、`focused-sp805-date.log`、`focused-sp806-503.log`、`focused-sp806-proposal.log`、`focused-masterdata-supplier.log`。
- 先前完整前端套件結果仍為 FAIL（34 檔中 10 檔失敗；301 項中 50 項失敗），見 [vitest-final.log](../../../.impeccable/validation/vitest-final.log)。本次沒有再次宣稱完整 Vitest 全綠；其後修復的文案契約及最後主題 token 已用上述精準測試驗證。
- Docker Compose 專案 `supplypilot-ux-preview` 已以最新 frontend image 重建並啟動。Frontend、Backend、MySQL、pgvector 健康檢查皆為 `healthy`；前端網址 `http://127.0.0.1:8081/login`。
- 瀏覽器滿版檢查：桌機 1280×720 的 document 高度 720；手機 390×844、390×667、320×568 各自 document 寬高都等於 viewport。登入區塊完整位於畫面內，320×568 的表單底部為 y=565，沒有水平或垂直捲動。短暫 viewport override 已重設為桌機尺寸。
- 登入畫面為真實 Compose Browser 畫面；沒有提交登入表單。真實 Gemini／RAG／AWS E2E、已登入採購工作流程與 200% 縮放：`NOT RUN`。GitHub Actions 仍 `PAUSED BY USER / NOT USED AS GATE`。

## 2026-10-27：手機導覽提示與 PO 八階段路徑

- 手機導覽右側加入不攔截觸控的漸層與箭頭提示；在 390×844 Compose 預覽中，導覽容器可視寬 370px、內容寬 704px，提示顯示寬 38px。文件寬 380px，沒有導覽造成的水平頁面溢出。原始可視畫面為已登入的 `/purchase-orders`。
- PO 階段路徑改為桌面單列連線步驟、平板四欄、手機可鍵盤聚焦的水平滑動列；手機摘要會顯示後端確認的目前階段與下一步，完成階段顯示「流程已完成」。未知階段仍沿用不推測位置的狀態提示。
- `npm run lint`：Exit Code 0，仍有 6 項既有 Oxlint 警告；原始輸出：[follow-up-lint.log](../../../.impeccable/validation/follow-up-lint.log)。
- `VITE_API_BASE_URL=/api/v1 npm run build`：Exit Code 0，轉譯 3,278 個模組；JS bundle 1,610.31 kB（gzip 501.97 kB），有 Vite 大型 chunk 建議警告；原始輸出：[follow-up-build.log](../../../.impeccable/validation/follow-up-build.log)。
- 精準測試 `npm run test -- tests/issue143PurchaseOrders.test.tsx`：第一次在收集測試前因 Windows sandbox 暫存目錄 rename `EPERM` 結束（Exit Code 1，原始輸出：[follow-up-po-stage-test.log](../../../.impeccable/validation/follow-up-po-stage-test.log)）；將 `TEMP`／`TMP` 指到工作區暫存目錄後重跑，17/17 通過、Exit Code 0（[follow-up-po-stage-test-retry.log](../../../.impeccable/validation/follow-up-po-stage-test-retry.log)）。測試涵蓋八階段、目前階段與下一步摘要，以及未知階段不推測。
- 以最新 frontend image 重建並更新 `supplypilot-ux-preview`；`docker compose ps` 顯示 Frontend、Backend、MySQL、pgvector 均為 `healthy`，前端仍在 `http://127.0.0.1:8081`。
- 實際採購資料限制：重新載入後清單顯示沒有符合條件的 PO；已存在的 PO-20261109-000001 詳情由後端回報 Stage `—`。因此未用推測資料宣稱已完成瀏覽器中的非空階段列驗收；階段列呈現與摘要由上述既有 PO 契約測試驗證。瀏覽器 console 沒有 error/warning。
