# Issue #148 P2：API 十進位精度追補驗證

## 驗證對象

- Branch：`codex/issue-148-ui-format-rwd`
- 程式與測試 commit：`be3878b7259691a789589c3a95efc7c9cf44ce1c`
- 基底 worktree HEAD：`7560a75dcdec85158dfc55e460845c600a355ffb`
- 環境：Windows、Node.js `v24.15.0`、npm `11.12.1`、Vitest `4.1.11`、Vite `8.3.4`

此 commit 將 BigDecimal response token 保留為字串，並讓 Inventory、SupplierMaterial、PO、Receiving、StockTransaction 的 response DTO 依既有 parser 保留精度。Axios 設定使用 response-only 的 `transformResponse`；request JSON payload 不變。共用 formatter 以字串分組，保留原小數位數，不轉成 JavaScript `number`。

## 精準測試

最後一次合併執行：

```powershell
npm exec -- vitest run --pool=forks --maxWorkers=1 tests/demandInventoryApi.test.ts tests/masterDataApi.test.ts tests/sp805Api.test.ts tests/issue148DisplayFormatters.test.ts tests/supplierMaterialPrecision.test.tsx
```

結果：5 files、26 tests 全數通過，exit code 0。原始輸出：[issue-148-p2-final-precision-with-render.log](issue-148-p2-final-precision-with-render.log)

各檔獨立結果：

- `demandInventoryApi.test.ts`：4/4，exit 0；[log](issue-148-p2-demandInventoryApi.log)
- `masterDataApi.test.ts`：4/4，exit 0；[log](issue-148-p2-masterDataApi.log)
- `sp805Api.test.ts`：13/13，exit 0；整個 API test file 均執行；[log](issue-148-p2-sp805-precision.log)
- `issue148DisplayFormatters.test.ts`：4/4，exit 0；[log](issue-148-p2-displayFormatters.log)
- `supplierMaterialPrecision.test.tsx`：1/1，exit 0；[log](issue-148-p2-supplierMaterial-render-final.log)

SupplierMaterial 元件測試將 JSON 原始 `999999999999999.1234`（DECIMAL(19,4)）送過真實 API response transform 和列表 render，斷言畫面顯示 `TWD 999,999,999,999,999.1234`；另驗證 MOQ `0.0001 KG`、包裝倍數 `12.3400 KG`。初次測試因缺少既有 AuthContext provider 而失敗，測試 harness 已補上 provider 與 RelatedEntitySelect fixtures；初次失敗保留於 [issue-148-p2-supplierMaterial-render.log](issue-148-p2-supplierMaterial-render.log)，修正後結果見上方 final log。

PO response 與 StockTransaction list/detail 的 API regression 一併通過；原有 PO PATCH BigDecimal request-string assertions 保持不變。未修改既有 assertions、timeout，亦未修改 `frontend/tests/sp805.test.tsx`。

## Lint 與 Build

- `npm run lint`：exit 0；出現既有 `SemanticOperationsPanel.tsx` `react(set-state-in-effect)` warning。[log](issue-148-p2-precision-lint-final.log)
- `npm run build`：exit 0；Vite 提示既有 bundle chunk 超過 500 kB。[log](issue-148-p2-precision-build-final.log)

## 完整套件與 Browser 範圍

本次 P2 追補沒有重跑完整 Vitest，也不改寫先前完整套件的失敗結果。原有完整套件失敗 log 保留且未修改，包括 `issue-148-vitest-final-60s.log` 與 `issue-148-vitest-final-timeout-adjusted.log`；它們不是本文件所列精準測試的替代品。

本次沒有執行 Browser E2E。先前 Browser 證據僅涵蓋未登入的 `/login` 頁面在 320、390、1280 px viewport 無水平溢位；沒有保存 screenshot 檔。因本機 API/帳號不可用，Supplier、Inventory、PO 等受保護頁面的 Browser E2E 維持 NOT RUN。200% zoom 驗收維持 BLOCKED；先前範圍見 [issue-148-browser-and-scope-notes.md](issue-148-browser-and-scope-notes.md)。

本文件在程式與測試已驗證並提交至上述 commit 後新增；本文件提交不修改程式、測試或上述驗證結果。