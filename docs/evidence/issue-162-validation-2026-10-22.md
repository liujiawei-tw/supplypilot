# Issue #162 驗收紀錄

日期：2026-10-22。GitHub Actions 維持 `PAUSED BY USER / NOT USED AS GATE`；未查詢、啟動、重跑或更動 Workflow。此文件記錄本機與 Codex CLI 實測，不宣稱 GPT 親自執行測試。

## Skill 與協調工具

| 項目 | 結果 |
| --- | --- |
| Codex CLI | `codex-cli 0.163.0-alpha.11` |
| Skill format validator | **PASS**：`skill-creator/scripts/quick_validate.py` 回報 `Skill is valid!` |
| 真實 Skill 載入 | **PASS**：唯讀 `codex exec` 載入 `$supplypilot-implement-spec`，確認名稱／v1.0 標題，以及 #162 合併前僅允許唯讀規劃與 dry run 的邊界；未執行 Skill 工作流或修改檔案 |
| Orchestrator tests | **PASS**：24 tests，涵蓋 DAG、Provisional Code/Release Gate、契約版本重驗、WIP 不重派、目錄/檔案 owner 衝突、Worktree/Branch 唯一性、SHA 綁定 GPT Review、禁止非使用者 Merge、共用測試資源鎖及鎖建立失敗清理 |
| 真實 Issue 唯讀 dry run | **PASS**：輸入與輸出摘要見 [dry-run 紀錄](issue-162-readonly-dry-run-2026-10-22.md)；`validate` PASS，`ready_frontier=[145]`，`release_frontier=[]` |
| 共享資源鎖 smoke | **PASS**：透過共用 Git common directory 鎖執行 Python Compose unittest、Compose config、Docker image pull/build；每項後鎖皆正常釋放 |

## 本機完整 CI 等價 Gate

環境：Java 21.0.10；Node.js 24.15.0；npm 11.12.1；主機 Python 3.13.5（Workflow 要求 3.12）；Docker Engine 29.6.1、Docker Desktop 4.82.0。

| Gate | 結果 |
| --- | --- |
| Backend `backend\\.\\mvnw.cmd -B --no-transfer-progress verify` | **PASS**：682 tests、0 failures、0 errors、1 skipped；`BUILD SUCCESS`，10:05；MySQL/Testcontainers 8.4.11。Skipped 為 opt-in 真實 Gemini smoke，未呼叫付費 Provider |
| Frontend `npm ci` | **PASS**：安裝 212 packages；npm audit 顯示 2 moderate vulnerabilities，未修改依賴 |
| Frontend `npm run lint` | **PASS** |
| Frontend `npm test` | **FAIL**：24 files 中 23 passed、1 failed；210 tests 中 208 passed、2 failed；總耗時 980.15 秒。`tests/sp806.test.tsx` 中 `ignores a delayed title response after switching documents`（10.894s）與 `ignores a delayed ingestion verification response after switching documents`（10.304s）失敗。完整摘要與錯誤在執行輸出；依專案規定隔離重跑結果見下方 log |
| 隔離 `npm test -- --run tests/sp806.test.tsx` | **FAIL**：25 tests 中 24 passed、1 failed，353.79 秒。`ignores a delayed ingestion response after switching documents` 無法找到 `#21` button；完整輸出保存於 [隔離 Log](issue-162-sp806-isolated.log) |
| Frontend `VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build` | **PASS**：TypeScript 與 Vite build 成功；保留既有 1.5 MB chunk >500 kB 警告 |
| Compose unittest | **PASS（版本受限）**：4/4；在主機 Python 3.13.5 執行。此機 `py -0p` 只有 Python 3.10，預設 `python` 是 3.13.5，未安裝 Workflow 要求的 Python 3.12，因此不能標記 Python 3.12 PASS |
| Production / Demo / Development Compose `config --quiet` | **PASS**：使用本次建立的虛構 placeholder env；沒有啟動服務、刪除資料或執行 reset |
| Backend Docker Image build | **PASS**：`supplypilot-backend:issue162` |
| Frontend Docker Image build | **PASS**：`supplypilot-frontend:issue162` |
| Orchestrator Python compile | **PASS**：`python -m py_compile scripts/supplypilot_orchestrator.py scripts/tests/test_supplypilot_orchestrator.py` |
| Skill validator | **PASS**：`quick_validate.py` 回報 `Skill is valid!` |
| `git diff --check` | **PASS**：staged diff 無 whitespace errors |

## 判定與限制

- **完整本機 Gate：FAIL / 未達 `LOCAL VERIFIED`。** Frontend 完整 Vitest 有 2 項失敗，且 Compose unittest 沒有在 Python 3.12 執行；未提高 timeout、跳過測試、修改 #160 或宣稱通過。
- 這次失敗位於目前使用者正在處理的 #160 覆蓋範圍。真實 Issue #160 的驗收／WIP 未改動；本 Issue 只按 `AGENTS.md` 執行完整套件與隔離診斷，並保留證據。
- 本次沒有執行真實 Browser、Gemini Provider、AWS、Production deploy 或 Demo reset；Issue #162 不要求執行這些端到端項目。
- 未執行 GitHub Actions，故沒有 GitHub CI PASS。
