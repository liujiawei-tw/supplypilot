# Issue #160 驗收紀錄

## 範圍與交付狀態

- Issue：[#160](https://github.com/liujiawei-tw/supplypilot/issues/160)
- 分支：`codex/issue-160-frontend-vitest-performance`
- 基底：`main`，Commit `9240d6aeeba2361476a0454ac965a214769638a0`（PR #161）
- 本文件及程式變更對應此分支最後的交付 Commit；Commit SHA 於 GPT Review Handoff 提供。
- 開發已依使用者決定凍結，不再增加功能、效能修改或額外測試。
- **完整本機 CI 等價 Gate：`BLOCKED / INCOMPLETE`。** 使用者要求本輪停止額外測試；這是停止驗證的決定，不代表完整 Gate 豁免、規範修改或取得 Final PASS。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`，本輪沒有啟動。

## 問題、診斷與修改

歷史 Windows 完整測試曾回報 11 個失敗。保留的 Issue 基線證據 `issue-160-windows-baseline-elevated.log` 顯示在 210 個案例版本中有 2 個實際失敗：SP-805 在 PO 資料尚未載入時找不到 `Claim PO-000042`；SP-806 在文件列尚未完成載入時找不到 `#21` 按鈕。這兩項是即時查詢早於非同步資料渲染的競態，不是已證實的業務斷言錯誤。最終兩次 Windows 完整測試均通過。

本分支只修改以下三個測試檔：

- `frontend/tests/sp806.test.tsx`：等待 `#21` 按鈕及可操作的文件解析按鈕；AI 多品項案例把 DOM 查詢限制在既有 drawer，保留 `userEvent` 點擊、數值輸入、token 確認內容、未知 token 保護及不呼叫一般 PO 建立 API 的 Assertions。將四種獨立 Gate Audit 失效情境拆成四個案例；保留各自的歷史稽核、READY／核准／略過及上傳請求數斷言，沒有刪除或 Skip 案例，也沒有提高 Timeout。
- `frontend/tests/sp805.test.tsx`：在取消、草稿編輯、收貨與 PO ownership 操作前等待 `PO-000042` 呈現，避免資料尚未載入時就查詢操作按鈕。
- `frontend/tests/demandInventory.test.tsx`：明確等待 `/demands`、物料 placeholder、`/materials/1` 及最後的物料名稱，保留最後資料斷言；這將載入順序從隱含假設改為明確同步條件。

互動涵蓋檢查：SP-805 欄位仍使用 `userEvent.type()`；SP-806 多品項案例仍以 `userEvent` 操作 drawer 與確認流程。必要的 `fireEvent.change()` 保留在原有數字／日期欄位案例，不取代一般鍵盤輸入互動。等待查詢只補足非同步條件，沒有放寬或移除斷言。SP-806 Gate Audit 的案例拆分後總數由 210 增至 213（+3），不是刪減案例。

## 最終測試證據

以下均以原始 Log 末尾的 Vitest／Maven 摘要、wrapper Exit Code 及時間核對。三次前端全量均對應本分支最終測試程式碼；完整 Gate 其餘未執行部分另列於後。

| 平台／驗證 | 命令／設定 | 實際結果 | 執行時間 | 原始證據 |
|---|---|---:|---:|---|
| Linux Node 24 `npm ci` | `node:24-bookworm-slim` 容器 | PASS，Exit 0 | 20 秒 | `issue-160-linux-final-query-scope-v3.log` |
| Linux lint | `npm run lint` | PASS，Exit 0 | 1 秒 | 同上 |
| Linux Vitest 全量 | `npm test -- --reporter=verbose`（maxWorkers=1） | 24/24 檔、213/213 測試 PASS，Exit 0 | Vitest 951.41 秒；wrapper 952 秒 | 同上 |
| Linux build | `npm run build` | PASS，Exit 0 | 7 秒 | 同上 |
| Windows Vitest 全量 #1 | Node 24.15.0、maxWorkers=1 | 24/24 檔、213/213 測試 PASS，Exit 0 | Vitest 1057.20 秒；wrapper 1059.34 秒 | `issue-160-windows-final-query-scope-1.log` |
| Windows Vitest 全量 #2 | Node 24.15.0、maxWorkers=1 | 24/24 檔、213/213 測試 PASS，Exit 0 | Vitest 1172.51 秒；wrapper 1174.61 秒 | `issue-160-windows-final-query-scope-2.log` |
| Backend Maven Verify | `backend\\.\\mvnw.cmd -B --no-transfer-progress verify`、Java 21.0.10 | `BUILD SUCCESS`、Exit 0；682 tests，0 failures、0 errors、1 skipped | wrapper 742.64 秒；Maven 12:19 | `issue-160-backend-maven-verify.log` |

Maven 的 1 個 skipped 是現有 Maven 測試摘要；沒有為本 Issue 新增 Skip。Maven Verify 為本輪使用者指定可完成的既有命令，已完成且原始輸出完整保存。

## 效能與 Worker 決策

- Issue 原目標：Linux 全量 Vitest 由 913.28 秒降至 5–8 分鐘。另有保存的 Linux Worker 1 基線 log 為 899.76 秒（24/24、210/210）；這是不同歷史紀錄，故分開列示，不混為同一個精確比較點。
- 最終 Linux 全量：951.41 秒。相較 Issue 目標基線 913.28 秒慢 38.13 秒；相較保存的 899.76 秒基線慢 51.65 秒。**整體效能目標未達成，也沒有整體加速的證據。**
- SP-806 多品項 AI 案例：較早 Linux 單檔 Log 記錄 31.389 秒並觸發既有 30 秒上限；最終 Linux 完整執行記錄 17.638 秒。這是同平台兩次不同執行脈絡的量測，僅可視為該案例本輪改善的佐證，不等同控制變因下的精確收益。Windows 完整執行相同案例分別約 18.985／18.887 秒。原始個別案例輸出見 `issue-160-linux-sp806-final-file-single-worker.log`、`issue-160-sp806-file-scoped-after-query-scope.log` 及兩份 Windows 全量 Log。
- SP-805 與 Demand Manager 的等待修正用於消除資料載入競態；目前沒有足夠的同平台、同條件逐案例前後量測支持量化效能收益，因此不宣稱有提速。
- 正式 Vitest 設定保留 `maxWorkers=1`。Worker 2 Linux 全量候選耗時 616.07 秒但有 2 個 timeout（208/210），不穩定且未全綠，不能作為有效效能結果，故不採用。未繼續 Worker 實驗。
- 原 Frontend CI Job 的 15 分鐘限制仍有風險：Windows 兩次全量分別約 17.7 與 19.6 分鐘，Linux Vitest 本身約 15.9 分鐘（尚未計入 npm ci、lint、build）。本輪未修改 GitHub Actions Timeout。

## 尚未執行的 Gate 與風險

因使用者明確要求 Maven 完成後停止額外測試，下列項目均為 **`NOT RUN`**：

- Python 3.12 `scripts.tests.test_compose_config`。
- Production、Demo、Development Compose `config --quiet`。
- Backend Docker Image Build。
- Frontend Docker Image Build。
- 其他完整 Local CI Gate 項目及任何新增完整 Linux／Windows Suite。

因此，雖然 Windows Vitest 兩次、Linux Vitest 一次、Linux npm ci／lint／build 及 Backend Maven Verify 均有 PASS 證據，仍未滿足 `AGENTS.md` 所要求的完整本機 CI 等價 Gate。本輪已執行的 Maven 命令結果不代表 Docker／Compose Gate 完成。使用者接受本輪停止額外驗證所留下的未驗證風險；這不構成正式 Gate 豁免。

其他已知風險：Issue 原效能目標未達；CI 前端 15 分鐘 Timeout 仍可能中止完整工作流程；歷史 Windows 11 失敗的原始歷史範圍大於本分支基線 log 可重現的 2 個案例，因此本紀錄只對該 Log 證實的兩個競態做根因判定，不聲稱已逐一歸因所有歷史 11 個失敗。最終兩次完整 Windows 執行均 213/213 通過，Linux 完整執行 213/213 通過。

## 證據索引與收尾決策

`docs/evidence/issue-160-*.log` 保存本 Issue 的原始基線、精準診斷、失敗與中斷執行、Worker 候選及最終驗證輸出；包含歷史失敗證據，未刪除。最終關鍵證據為：

- `issue-160-windows-baseline-elevated.log`：Windows 基線 2 個可重現的非同步資料載入失敗。
- `issue-160-linux-final-query-scope-v3.log`：Linux Node 24 最終 npm ci／lint／213 測試／build 完整流程。
- `issue-160-windows-final-query-scope-1.log`、`issue-160-windows-final-query-scope-2.log`：Windows Node 24 兩次連續全量 PASS。
- `issue-160-backend-maven-verify.log`：Backend Maven Verify 完整輸出及 Exit 0。
- `issue-160-linux-final-worker2-candidate.log`：Worker 2 全量候選失敗記錄。
- `issue-160-linux-sp806-final-file-single-worker.log`、`issue-160-sp806-file-scoped-after-query-scope.log`、`issue-160-sp806-multi-item-scoped-after-worktree-temp.log`：SP-806 精準診斷及最終量測輸出。

本輪依使用者決定凍結開發並停止新增測試。只提交 Issue #160 的程式碼、驗收文件與必要原始證據；不建立 PR、不 Merge、不關閉 Issue。交 GPT 審查時須以最終 Commit SHA 為準，分開回報 Code Review 狀態、已通過的個別測試與 `BLOCKED / INCOMPLETE` 的完整 Gate 狀態；由於完整 Gate 未完成，不得宣稱 `FINAL PASS` 或具備建立 PR 資格。
