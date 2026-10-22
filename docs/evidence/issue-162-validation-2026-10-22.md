# Issue #162 驗收紀錄

基線 Gate 證據日期：2026-10-22；P1 修正與精準回歸日期：2026-10-23。GitHub Actions 維持 `PAUSED BY USER / NOT USED AS GATE`；未查詢、啟動、重跑或更動 Workflow。此文件記錄本機與 Codex CLI 實測，不宣稱 GPT 親自執行測試。

## Skill 與協調工具

| 項目 | 結果 |
| --- | --- |
| Codex CLI | `codex-cli 0.163.0-alpha.11` |
| Skill format validator | **PASS**：`skill-creator/scripts/quick_validate.py` 回報 `Skill is valid!` |
| 真實 Skill 載入 | **PASS**：唯讀 `codex exec` 載入 `$supplypilot-implement-spec`，確認名稱／v1.0 標題，以及 #162 合併前僅允許唯讀規劃與 dry run 的邊界；未執行 Skill 工作流或修改檔案 |
| Orchestrator tests（本次 P1 修正後精準回歸） | **PASS**：68 tests，27.549 秒；包含活動 Issue 檔案 owner 衝突、`BLOCKED_ENVIRONMENT` owner 保留與明確釋放、路徑正規化及父子衝突、採用 WIP owner、精確 GPT Final PASS release frontier、main 同步使舊證據失效、state resume 遠端 main 核對、state set-head／handoff 實際 local/remote ref 核對、固定 Full Gate manifest、Targeted 實測計數與 skip/failure 拒絕、Maven skip/failure-ignore 參數拒絕、npm/Maven/Full Gate 共用鎖、Full Gate Java/Node/npm/Docker 絕對 runner 防偽，以及測試期間工作樹變動拒絕 PASS。完整 unittest 輸出保存於 [回歸 Log](issue-162-orchestrator-p1-regression-2026-10-23.log) |
| 真實 Issue 唯讀 dry run | **PASS**：以重新核對的 Issue/PR 狀態及 #142／#143／#160 WIP 快照執行 `validate`、`plan`；輸入與輸出摘要及指紋比對見 [dry-run 紀錄](issue-162-readonly-dry-run-2026-10-22.md)。`ready_frontier=[145]`，所有 Full Gate／GPT handoff／release frontier 為空 |
| 共享資源鎖 smoke | **PASS（先前紀錄）**：透過共用 Git common directory 鎖執行 Python Compose unittest、Compose config、Docker image pull/build；每項後鎖皆正常釋放。本次未重跑這些完整 Gate 項目 |

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
| Orchestrator Python compile（本次 P1 修正後） | **PASS**：`python -m py_compile scripts/supplypilot_orchestrator.py scripts/supplypilot_full_gate.py scripts/tests/test_supplypilot_orchestrator.py` |
| Skill validator（本次 Skill 修改後） | **PASS**：`quick_validate.py` 回報 `Skill is valid!` |
| `git diff --check` | **PASS**：本次工作樹 diff 無 whitespace errors；僅有 Git 對 LF/CRLF 的提示 |

## 本次 P1 修正範圍與證據

本次只在 Issue #162 工作樹修正：活動／採用中的 Issue 對檔案與目錄路徑的排他 owner；Full Gate、GPT handoff 與 release frontier 的階段及 SHA 條件；`origin/main` 的實際 SHA 同步與舊證據失效；#142／#143／#160 現有未提交 WIP 的兩階段採用／恢復；以及 Vitest、Maven、Docker 共用的互斥高負載鎖；固定 Full Gate manifest 與測試命令白名單防止單一命令、skip/collect-only/help 或 failure-ignore 假冒通過；Full Gate 以過濾 PATH 解析 Java/Node/npm/Docker 絕對 runner，並在測試後重核工作樹 branch/HEAD/dirty，防止執行期間變動被登記為 PASS。測例不改寫 #142／#143／#160，也沒有執行它們的測試。

本次精準驗證執行環境為 Python 3.13.5；Workflow 指定的 Python 3.12 未安裝。最新 68 測例（27.549 秒）是在 Issue #162 分支原 HEAD `c2629218d00d9049cc356740514f719fdca0e648` 上、套用本次修正後執行；相同程式碼已提交為 `61549c1d9c900824e622f211248c2a126ed14273`。Windows Codex CLI 0.163.0-alpha.11 以唯讀模式載入最新 Skill，確認名稱、v1.0 標題及未合併時只允許唯讀規劃與 Dry Run；沒有執行其跨 Issue 工作流。

## 判定與限制

- **完整本機 Gate：FAIL / 未達 `LOCAL VERIFIED`。** Frontend 完整 Vitest 有 2 項失敗，且 Compose unittest 沒有在 Python 3.12 執行；未提高 timeout、跳過測試、修改 #160 或宣稱通過。
- 這次失敗位於目前使用者正在處理的 #160 覆蓋範圍。真實 Issue #160 的驗收／WIP 未改動；本 Issue 只按 `AGENTS.md` 執行完整套件與隔離診斷，並保留證據。
- 本次沒有執行真實 Browser、Gemini Provider、AWS、Production deploy 或 Demo reset；Issue #162 不要求執行這些端到端項目。
- 未執行 GitHub Actions，故沒有 GitHub CI PASS。
