# Initial focused document and AI test run

- Command: `npm test -- tests/sp907Documents.test.tsx tests/aiAnswerDisplay.test.tsx tests/documentPreviewDrawer.test.tsx`
- Working directory: `frontend/` in the `codex/procurement-tool-ux-redesign` worktree.
- Initial result: 3 test files; 20 passed and 2 failed (22 total). `documentPreviewDrawer.test.tsx` passed. The failures were selector assertions: `aiAnswerDisplay.test.tsx` used a singular lookup for `原文摘錄`, which appeared once for each of two sources; `sp907Documents.test.tsx` looked up `供應商檔案` as a standalone text node although the document type and supplier are grouped in the first source cell.
- Exit code: not retained in the captured tool result.
- Raw stdout: not saved; the displayed output was truncated.
- Follow-up: both selectors were corrected (`getAllByText(...).toHaveLength(2)` for the source labels and first-cell text assertions for the document metadata). The focused tests were not rerun after those corrections.
