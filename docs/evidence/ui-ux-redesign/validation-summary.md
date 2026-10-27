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

## 2026-10-27：PR #186 合併前 UX 修正

- 分支預檢：工作分支 `codex/procurement-tool-ux-redesign` 起始 HEAD 為 `8c51ed6dc3cbaf63648c4f938d92414854b125f7`，包含指定設計基準 `a5bb6bad7c03018941fed36a5bce9a5f557d805b`。遠端 `main` 當時已前進至 `c09630d59dda91f1abf508c73b763486a1ec303b`；它已是工作分支祖先（比較為 ahead 4、behind 0），沒有未整合的 main 更新。使用者提供的 `8c51...` 是該分支 HEAD，不是當時最新的遠端 main。
- QuickFinder 關閉時清除查詢、結果、錯誤及搜尋狀態，並保留請求序號保護。收貨清單桌面／手機及明細預計交期改用共用 `formatDate` 顯示 `YYYY/MM/DD`，空值顯示「未設定」；收貨佇列切換查詢時隱藏上一頁資料並顯示載入狀態。API 契約與後端日期未變更。
- TDD 重現：第一次測試執行包含測試環境選擇器／非同步等待設定錯誤，原始輸出保留於 [ux-findings-red.log](../../../.impeccable/validation/ux-findings-red.log)。修正測試環境後的產品缺陷重現結果保留於 [ux-findings-red-rerun.log](../../../.impeccable/validation/ux-findings-red-rerun.log)：QuickFinder 重開後舊查詢殘留、日期顯示格式不符、篩選請求期間手機清單仍顯示舊資料；舊查詢晚到覆蓋新結果的防護測試通過。
- 三項 Finding 精準測試：7/7 通過，Exit Code 0；原始輸出：[ux-findings-focused.log](../../../.impeccable/validation/ux-findings-focused.log)。兩個受影響完整測試檔：27/27 通過，Exit Code 0；原始輸出：[ux-findings-affected-files.log](../../../.impeccable/validation/ux-findings-affected-files.log)。
- 完整 Frontend Vitest 最新結果：34 個測試檔，30 通過、4 失敗；317 個測試，305 通過、12 失敗，Exit Code 1；原始輸出：[vitest-final-after-ux-fixes.log](../../../.impeccable/validation/vitest-final-after-ux-fixes.log)。12 項落在 `demandInventory.test.tsx` 2 項、`issue143PurchaseOrdersPage.test.tsx` 3 項、`masterData.test.tsx` 3 項及 `sp805.test.tsx` 4 項。這些同名案例也存在較早的完整套件原始記錄 [vitest-final.log](../../../.impeccable/validation/vitest-final.log)；本次三項 Finding 的精準測試與受影響完整測試檔均通過。完整 Vitest 仍標記 **FAIL**，沒有將既有案例改寫為成功或修改舊版 DOM 選擇器來掩蓋結果。
- 310/310 全通過的說法：檢查 `docs/evidence/ui-ux-redesign/` 與 `.impeccable/validation/` 的原始紀錄，未找到可支持該數字的日誌。已找到的早期完整結果分別是 301 項中 294 通過、7 失敗（[frontend-full-final.log](frontend-full-final.log)），以及 301 項中 251 通過、50 失敗（[vitest-final.log](../../../.impeccable/validation/vitest-final.log)）。
- `npm run lint`：Exit Code 0，6 項 Oxlint 警告（路由 fast-refresh 1 項、`set-state-in-effect` 5 項）；原始輸出：[lint-final-after-ux-fixes.log](../../../.impeccable/validation/lint-final-after-ux-fixes.log)。`VITE_API_BASE_URL=/api/v1 npm run build`：Exit Code 0，轉譯 3,279 個模組；JS bundle 1,619.50 kB（gzip 503.96 kB），有 Vite 大型 chunk 建議警告；原始輸出：[build-final-after-ux-fixes.log](../../../.impeccable/validation/build-final-after-ux-fixes.log)。
- 三項 Finding 的真實登入瀏覽器驗收：`NOT RUN`。目前沒有可用的非空收貨採購資料，未以假資料冒充驗收；登入後採購／收貨工作流程及相關手機瀏覽器驗收維持 `NOT RUN`。真實 Gemini／RAG／部署 E2E 維持 `NOT RUN`。GitHub Actions 仍為 `PAUSED BY USER / NOT USED AS GATE`。
- 本次 `git diff --check`：通過；日誌：[diff-check-final-after-ux-fixes.log](../../../.impeccable/validation/diff-check-final-after-ux-fixes.log)。

## 2026-10-27：PR #186 第二輪驗證與收貨錯誤狀態

- 起始預檢：分支 `codex/procurement-tool-ux-redesign`、HEAD `c82ae6a5c9dd509242bbc525b892f78a0efd37c6`、工作樹乾淨；遠端 feature head 相同，PR #186 開啟且 head 相同。最新遠端 `main` 為 `c09630d59dda91f1abf508c73b763486a1ec303b`，已包含在 feature branch（ahead 5、behind 0）。
- Finding 4：收貨清單 API 失敗時保留頁面上方可重試的 ErrorState；桌面表格改用無文案占位，手機版錯誤狀態不渲染 EmptyState，避免錯誤與「目前沒有待收貨 PO／尚無已收貨紀錄」同時顯示。請求成功回傳空集合後，桌面與手機仍正常顯示「尚無已收貨紀錄」。未修改 API、權限或業務流程。
- 回歸測試使用 `MemoryRouter` 直接掛載 ReceivingPage，模擬第一次清單 GET 回傳 500、點擊「重新載入」後回傳成功空集合；分別檢查桌面表格與手機清單。修正前測試確認錯誤已顯示但桌面仍出現空狀態，原始重現：[receiving-error-state-red-component.log](../../../.impeccable/validation/receiving-error-state-red-component.log)。早期 route harness 試跑紀錄也保留於 `receiving-error-state-red.log`、`receiving-error-state-red-final.log` 與 `receiving-error-state-red-ordered-rerun.log`；最終驗收使用元件層級 MemoryRouter 測試。
- Finding 4 精準回歸：1/1 通過，Exit Code 0；原始輸出：[receiving-error-state-focused.log](../../../.impeccable/validation/receiving-error-state-focused.log)。完整 `tests/sp807.test.tsx`：22/22 通過，Exit Code 0；原始輸出：[receiving-error-state-sp807.log](../../../.impeccable/validation/receiving-error-state-sp807.log)。
- 先前平行模式的完整 Vitest 原始結果維持 FAIL（34 個檔案、317 項中 305 通過、12 失敗，Exit Code 1），見 [vitest-final-after-ux-fixes.log](../../../.impeccable/validation/vitest-final-after-ux-fixes.log)。在相同起始 HEAD `c82ae6a`、沒有程式修改的狀態，依指定單一 Worker／60 秒逾時命令完整重跑：34/34 檔案、317/317 測試通過，Exit Code 0；原始輸出：[vitest-single-worker-baseline.log](../../../.impeccable/validation/vitest-single-worker-baseline.log)。前次 12 個失敗案例在這次完整序列執行中均通過，因此分診為對多 Worker 執行敏感的非同步等待／元件渲染問題；未找到產品行為缺陷或舊 UI 選擇器不相容的證據。依使用者指定的分流條件，因完整單一 Worker 套件已全綠，沒有再逐項單獨重跑。
- 修改 Finding 4 後，完整單一 Worker Frontend Vitest：34/34 檔案、318/318 測試通過，Exit Code 0；原始輸出：[vitest-final-single-worker-after-finding4.log](../../../.impeccable/validation/vitest-final-single-worker-after-finding4.log)。
- Frontend Lint：Exit Code 0，6 項既有 Oxlint 警告（fast-refresh 1、set-state-in-effect 5）；原始輸出：[lint-final-after-finding4.log](../../../.impeccable/validation/lint-final-after-finding4.log)。`VITE_API_BASE_URL=/api/v1 npm run build`：Exit Code 0、3,279 個模組；JS bundle 1,619.55 kB（gzip 503.99 kB），有 Vite 大型 chunk 建議警告；原始輸出：[build-final-after-finding4.log](../../../.impeccable/validation/build-final-after-finding4.log)。
- `git diff --check`：Exit Code 0；原始輸出：[diff-check-final-after-finding4.log](../../../.impeccable/validation/diff-check-final-after-finding4.log)。真實登入瀏覽器驗收：`NOT RUN`，目前無可用非空收貨採購資料；未以假資料代替驗收。GitHub Actions 維持 `PAUSED BY USER / NOT USED AS GATE`。
