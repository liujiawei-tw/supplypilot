# Issue #140（V2-203）本機驗收紀錄

- 日期：2026-10-22（Asia/Taipei）
- Repository：`liujiawei-tw/supplypilot`
- Base：`origin/main` @ `3b2dab479feb7e3d0b46e52df0708e7cdb31f69f`
- Feature branch：`codex/issue-140-v2-203`
- 驗收範圍：本分支程式與測試工作樹；所有結果均為本機驗收，不代表 GitHub Actions 結果。

## 驗收環境

- Java：21.0.10（Zulu）
- Windows Node.js：v24.15.0；npm 11.12.1
- Linux container Node.js：v24.22.1；npm 11.19.1（`node:24-bookworm-slim`）
- Python：3.12.14
- Docker Engine：29.6.1（Docker Desktop）
- Docker Compose：v5.3.0
- Testcontainers MySQL：8.4.11

## GitHub Actions 狀態

- 狀態：`PAUSED BY USER / NOT USED AS GATE`。本次未查詢、等待、重跑或依賴 GitHub Actions；亦未修改 Workflow 或 Branch Protection。
- 以 Linux Node 24 container 執行 Frontend Workflow，對齊 repository CI 的 Ubuntu 執行環境；本結果仍是本機驗收，不是 GitHub Actions PASS。

## 驗收結果

| 工作 | 指令／結果 |
| --- | --- |
| 後端完整 Gate | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`：PASS；675 tests、0 failures、0 errors、1 skipped，BUILD SUCCESS，耗時 9:02。涵蓋 V26 migration 與 MySQL/Testcontainers 整合測試。 |
| 精準後端驗證 | `-Dtest=PurchaseOrderUpdateIntegrationTests,PurchaseOrderTests,ClockConfigurationTests,ReceivingPurchaseOrderControllerIntegrationTests#validatesDateBoundariesAndAllInvalidQueryParameters test`：PASS；31 tests、0 failures/errors，耗時 1:35。 |
| 後端完整驗證首次執行 | 675 tests 中有 1 個失敗：既有 `ReceivingPurchaseOrderControllerIntegrationTests.validatesDateBoundariesAndAllInvalidQueryParameters` 將 `2026-10-12T23:30Z` 當作台北 10/12；新台北日曆日契約下該時間已是 10/13。將測試 fixture 改為 `2026-10-12T02:00Z` 後，精準與完整後端驗證均 PASS；未放寬斷言。 |
| Windows 前端依賴 | `cd frontend; npm ci`：PASS；212 packages installed、213 audited；npm 回報 2 個 moderate severity vulnerabilities。 |
| Windows 前端 lint/build | `npm run lint`：PASS；`VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build`：PASS；Vite 有既有大型 chunk（約 1.5 MB）警告。 |
| Windows 前端完整測試 | `npm test`：FAIL；24 files 中 22 passed、2 failed；207 tests 中 196 passed、11 failed；耗時 1337.74 秒。`sp806.test.tsx` 有 7 個 30 秒 timeout；`sp805.test.tsx` 有 2 個 30 秒 timeout、2 個 assertion failures。完整測試期間的失敗在單檔重跑沒有重現。 |
| Windows `sp805` 單檔 | `npm test -- --run tests/sp805.test.tsx`：PASS；16/16，145.93 秒。完整輸出：[issue-140-sp805-isolated.log](issue-140-sp805-isolated.log)。 |
| Windows `sp806` 單檔 | `npm test -- --run tests/sp806.test.tsx`：PASS；25/25，373.55 秒。完整輸出：[issue-140-sp806-isolated.log](issue-140-sp806-isolated.log)。 |
| Linux 前端完整 CI 等價驗證 | `node:24-bookworm-slim` container 內依序執行 `npm ci`、`npm run lint`、`npm test`、`VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build`：全部 PASS；Vitest 24 files、207 tests 全數通過，耗時 913.28 秒；build 成功。完整輸出：[issue-140-frontend-linux-ci.log](issue-140-frontend-linux-ci.log)。 |
| Compose 單元測試 | Python 3.12.14 執行 `python -m unittest scripts.tests.test_compose_config -v`：4/4 PASS。 |
| Compose 設定 | Production、Demo、Development 三種 Workflow 指定 `docker compose ... config --quiet`：全部 PASS。僅使用 CI placeholder env，不含機密。 |
| Docker Engine／image | Docker Engine 檢查 PASS。Backend image `supplypilot-backend:ci` 建置 PASS（`sha256:79f7e1d9d619d52b81e628b340e2b42424cff3134239ba0a513c8a6efde46c2c`）；Frontend image `supplypilot-frontend:ci` 建置 PASS（`sha256:bf9868bcba1db642f5fa92ee5c014740f5c3d4dd85bec77502f3e1838a0cc2ab`）。 |
| `git diff --check` | PASS。 |

## Review 與範圍

- Spec Compliance：內部 review 無剩餘 findings；符合 Issue #140 的日期獨立性、NULL 清除語意、授權／狀態限制、期望日期逾期判定及前端呈現需求。
- Coding Standards：內部 review 無剩餘 findings；DTO 欄位存在性由 Bean Validation 與 Controller `@Valid` 驗證，服務層處理交易及生命週期規則。
- 安全／交易檢查：更新端點限定 `PROCUREMENT_STAFF`／`ADMIN`，只允許 APPROVED／ORDERED；使用既有 PO `findByIdForUpdate()` 及交易寫入模式，測試涵蓋未授權、狀態拒絕與併發更新。
- 未加入交期歷史、供應商自動對接、通知或其他 V2 Issue 功能。
- 本次未執行 AWS、Gemini 或 Browser E2E；這些不在 Issue #140 本機 CI Gate 內。
- GitHub Actions 未使用；未建立 PR、未 Merge、未關閉 Issue。
