# Corrected Documents, AI, and QuickFinder test rerun

- Date: 2026-10-26 (Asia/Taipei).
- Environment: Windows, Node.js v24.15.0, npm 11.12.1.
- Command: `npm run test -- --maxWorkers=1 --reporter=dot tests/sp806.test.tsx tests/sp907Documents.test.tsx tests/quickFinder.test.tsx`.
- Result: exit code 1; 3 files (1 passed, 2 failed), 32 tests (11 passed, 21 failed), duration 257.83 seconds. Full stdout/stderr is preserved in [documents-finder-focused.log](documents-finder-focused.log).

## Triage

- All 3 QuickFinder tests passed after the search icon was hidden from the accessible name.
- `sp806.test.tsx` still used global document button queries while the page renders both desktop table and mobile list records in the DOM. The query needs to scope to the desktop table. The AI button name also included the decorative robot icon label; the shell icon is now marked `aria-hidden`. One refresh assertion uses the old label instead of the current `重新讀取狀態` action.
- `sp907Documents.test.tsx` pagination now targets the desktop table container correctly, but the second record's title is a button label (`#32 · SUP-001 第二份基線文件`), and the assertion retained an old separate-text lookup against a potentially stale table node.

The assertions for API requests, page number, document state, and AI/semantic behavior remain in place. The remaining selectors are being corrected to interact with the current UI structure.
