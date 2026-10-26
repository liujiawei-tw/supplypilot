# PO focused rerun evidence

日期：2026-10-26（Asia/Taipei）

執行目錄：`frontend/`

命令：

```text
npm run test -- --run tests/issue143PurchaseOrdersPage.test.tsx --maxWorkers=1 --reporter=verbose -t 'refreshes the current Kanban|403 draft-create failure'
```

結果：`PO_VITEST_EXIT_CODE=1`。Vitest 摘要：1 test file failed；2 failed、5 skipped；Duration 75.61s。完整 stdout/stderr 已保存於 [po-focused-rerun.log](po-focused-rerun.log)。

失敗案例：

- `refreshes the current Kanban from backend after a successful draft create`：Test timed out in 30000ms；Vitest stack 指向 `issue143PurchaseOrdersPage.test.tsx:330:3`。
- `does not refresh the Kanban or report success after a 403 draft-create failure`：Test timed out in 30000ms；Vitest stack 指向 `issue143PurchaseOrdersPage.test.tsx:354:14`。

verbose 輸出未提供測試 async body 的最後等待點；兩項案例均呼叫 `submitManualDraft()`，但無證據確認是否執行到 helper 的最後一次點擊。輸出另有 jsdom 提示 `Not implemented: Window's getComputedStyle() method: with pseudo-elements`。

前一輪未以檔案保存完整輸出；以下僅記錄當時可核對摘錄，未重建全文：`Test Files 1 failed | 1 passed (2)`、`Tests 2 failed | 22 passed (24)`、失敗為上述 draft-create refresh 與 403 逾時，`PO_VITEST_EXIT_CODE=1`。

清理：暫時 dependency junction 已移除；worktree `frontend/node_modules` 還原為普通目錄，原 `.vite` 快取存在。Agent A 暫存路徑未建立或修改。

## 後續診斷與 PO Page 最終驗證

`fireEvent` helper 試驗只作診斷，不作最終驗證。`po-draft-cases.log` 顯示直接派發欄位變更後，欄位值雖已出現，但 Ant Design 表單的非同步檢查尚未開啟確認對話框，因此同步查詢確認按鈕失敗。`po-draft-cases-fixed.log` 接著顯示，建立草稿後同步查詢看板區域也可能早於看板重新載入完成。再加等待的 `fireEvent` 試跑通過，但依指示已還原真實 `userEvent` 操作。最終 helper 只在 Review 後等待相同的確認對話框。

建立成功後的看板檢查改用 `findByRole` 等待相同區域出現；條件未變，只等待現有的非同步後端重載。最終驗證使用 `userEvent`、實際 PO 表單 Review 流程及原有 API 結果斷言。

草稿建立重點案例命令：

```text
npm run test -- --run tests/issue143PurchaseOrdersPage.test.tsx --maxWorkers=1 --reporter=verbose -t 'refreshes the current Kanban|403 draft-create failure'
```

結果：`PO_DRAFT_CASES_USEREVENT_EXIT_CODE=0`；2 passed、5 skipped，61.64 秒。完整輸出：[po-draft-cases-userevent.log](po-draft-cases-userevent.log)。

完整 PO Page 命令：

```text
npm run test -- --run tests/issue143PurchaseOrdersPage.test.tsx --maxWorkers=1 --reporter=verbose
```

結果：`PO_PAGE_FINAL_EXIT_CODE=0`；7 passed，101.46 秒。完整輸出：[po-page-final.log](po-page-final.log)。

Impeccable detector 是手動執行，沒有 hook 觸發。命令與 targets：

```text
impeccable.cmd detect --json frontend/src/pages/PurchaseOrders/PurchaseOrdersPage.tsx frontend/src/pages/PurchaseOrders/PurchaseOrderKanban.tsx frontend/src/pages/PurchaseOrders/PurchaseOrderWaybill.tsx frontend/src/pages/PurchaseOrders/PurchaseOrderWaybill.css frontend/tests/issue143PurchaseOrders.test.tsx frontend/tests/issue143PurchaseOrdersPage.test.tsx
```

回傳 JSON 已保存於 [po-impeccable-findings.json](po-impeccable-findings.json)。當時回報 4px waybill 頂線及 3px 未知階段側線；兩者已改為細中性色分隔。依父任務要求，修正後沒有再次執行 detector。