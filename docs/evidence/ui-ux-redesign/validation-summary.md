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
