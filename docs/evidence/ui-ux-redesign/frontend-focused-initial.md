# Integrated focused frontend test run

- Date: 2026-10-26 (Asia/Taipei).
- Environment: Windows, Node.js v24.15.0, npm 11.12.1.
- Working directory: `frontend/` in `codex/procurement-tool-ux-redesign`.
- Command: `npm run test -- --maxWorkers=1 --reporter=verbose tests/auth.test.tsx tests/demandInventory.test.tsx tests/issue143PurchaseOrders.test.tsx tests/issue143PurchaseOrdersPage.test.tsx tests/sp806.test.tsx tests/sp907Documents.test.tsx tests/aiAnswerDisplay.test.tsx tests/documentPreviewDrawer.test.tsx tests/semanticOperationsPanel.test.tsx tests/masterData.test.tsx tests/precisionEditRoundTrip.test.tsx tests/quickFinder.test.tsx`.
- Result: exit code 1; 12 files (8 passed, 4 failed), 140 tests (114 passed, 26 failed), duration 641.68 seconds. Full stdout/stderr is preserved in [frontend-focused.log](frontend-focused.log).

## Failure triage

- `sp907Documents.test.tsx`: one pagination selector searched inside the semantic `<table>` even though the desktop pagination is a sibling in `.document-workspace__table`.
- `sp806.test.tsx`: selectors still assumed the old split source text, old AI navigation label, and always-expanded admin operations. The new UI groups each source into a record button, changes the AI action label to `AI 查詢`, and places admin operations in a collapsed section. Tests need to interact with those current controls while keeping their existing API, authorization, and state assertions.
- `quickFinder.test.tsx`: a decorative search icon contributed the accessible name `search` to the text-labeled button. The icon is now explicitly hidden from assistive technology so the button name is just its visible action.
- `issue143PurchaseOrdersPage.test.tsx`: the 403 draft-create case exceeded the existing 30 second test limit in this larger serial group. The same final PO page source and tests passed 7/7 in the isolated serial run recorded in [po-focused-rerun.md](po-focused-rerun.md); no timeout was raised.

The two selector groups and QuickFinder accessible-name issue are being corrected and will be reverified. No failures are being discarded or reclassified as passes.
