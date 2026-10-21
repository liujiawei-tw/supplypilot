# Issue #139（V2-202）本機驗收紀錄

- 日期：2026-10-22（Asia/Taipei）
- Repository：`liujiawei-tw/supplypilot`
- Base：`main` @ `ace1924d7eba1b3eb4383833136a5e07f5a32917`
- Feature branch：`codex/issue-139-v2-202`

## 驗收環境

- Java：21.0.10
- Node.js：v24.15.0
- Python：3.13.5（GitHub Workflow 指定 3.12；本機暫行 Gate 使用可用版本，版本差異需保留）
- Docker Engine：29.6.1（Docker Desktop）
- Docker Compose：v5.3.0
- Testcontainers：使用 MySQL 8.4.11；完整後端 verify 執行時 Docker Engine 可用

## 驗收結果

| 工作 | 指令／結果 |
| --- | --- |
| 後端完整 Gate | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`：PASS；669 tests、0 failures、0 errors、1 skipped，BUILD SUCCESS，耗時 10:15。涵蓋最終 V25 migration、lock/concurrency 測試及既有 SQL fixtures。 |
| 精準後端單測 | `-Dtest=PurchaseOrderServiceTests test`：4 tests、0 failures/errors。 |
| PO lifecycle 整合測試 | `-Dtest=PurchaseOrderLifecycleIntegrationTests test`：13 tests、0 failures/errors；包含 `ORDERED + NULL ordered_progress` 被 MySQL CHECK 拒絕。 |
| 前端依賴 | `cd frontend; npm ci`：完成，213 packages audited、0 vulnerabilities。 |
| 前端 lint | `npm run lint`：PASS。 |
| 前端完整測試 | Node 24 Linux 容器：`npm ci`、`npm run lint` PASS；`npm test` FAIL，24 files／205 tests 中 204 passed、1 timeout。唯一失敗為既有 `masterData.test.tsx` 的 “keeps Supplier create and edit values when backend submissions fail”，timeout 40 秒；該檔隔離重跑 10/10 PASS，duration 145.53 秒，此測例耗時 28.13 秒。完整套件與單檔隔離結果不同，原因尚未定論；GitHub CI 仍須以交付 SHA 核對。Linux 容器因 `npm test` 非零退出而未進入該容器 build；Windows Node 24 的 `npm run build` PASS，且最終 frontend Docker image build PASS。 |
| 前端 build | `$env:VITE_API_BASE_URL='http://localhost:8080/api/v1'; npm run build`：PASS。Vite 有既有大型 chunk 建議（約 1.5 MB），build 成功。 |
| Compose unit tests | `python -m unittest scripts.tests.test_compose_config -v`：4 tests PASS。 |
| Compose config | Production、Demo、Development 三種 `docker compose ... config --quiet`：全部 PASS。 |
| Backend image | `docker build --file backend/Dockerfile --tag supplypilot-backend:ci .`：PASS；最終 V25 migration 已重建。 |
| Frontend image | `docker build --file frontend/Dockerfile --build-arg VITE_API_BASE_URL=/api/v1 --tag supplypilot-frontend:ci .`：PASS；前端產品程式完成後建置。 |
| `git diff --check` | PASS（完成最終變更後再確認）。 |

## GitHub Actions 證據

- 交付 SHA `6580be95d4ff541744065454ec9c80f87c619af6` 的 GitHub Actions Run `39642896016`（2026-10-21）：`Detect CI scope` 為 `failure`；Backend CI、Frontend CI、Container image and Compose validation 均為 `skipped`。因此 GitHub Actions 尚未驗證本次變更。
- 該 Run 的 job log API 回應 404（BlobNotFound），無法取得 scope gate 的詳細錯誤；不得推定是程式測試失敗或 GitHub Billing 阻擋。
- PR #155 已合併的歷史 CI Run `39622948089`：`Detect CI scope` 失敗，Backend／Frontend／Container jobs skipped；這不代表 GitHub CI PASS。
- PR #155 描述亦記錄先前 Run `39620449208` 因 GitHub runner/free quota 或 Billing 限制無法取得 runner。這些是暫行本機 Gate 的背景證據，不是本 Issue 的 GitHub CI 結果。
- Issue #139 驗收狀態須區分本機驗收與 GitHub CI：後端完整 verify、前端 Linux 完整 suite、Docker image 等依上方逐項列示；前端 Linux suite 有一個 timeout，且 GitHub CI jobs 被 skipped，不能標成 GitHub CI PASS 或完整 `FINAL PASS — LOCAL VERIFIED / GITHUB CI PENDING`。

## Vitest timeout 詳細紀錄

- 前端倉庫設定為單一 worker 與每個測例 30 秒 timeout；未更改 timeout、跳過或放寬斷言。
- 被停止的併行完整套件在 `sp806.test.tsx` 記錄 25 tests／7 timeouts（該檔 474.30 秒），在 `demandInventory.test.tsx` 記錄 12 tests／2 timeouts（該檔 127.03 秒）。該 run 與完整 Maven Testcontainers verify 同時執行，未完成總套件，不作 PASS。
- 依專案前次 Issue #137 紀錄要求隔離執行：本次 `sp806.test.tsx` 單檔 25 tests／21 passed／4 timed out，duration 456.28 秒。timeout 名稱與堆疊保存在本機 `frontend/issue-139-sp806.log`，不提交原始 stderr log；錯誤均為 Vitest `Test timed out`，不是 assertion mismatch。
- `demandInventory.test.tsx` 隔離執行 12/12 PASS，duration 118.99 秒。
- Node 24 Linux 完整 suite 的唯一 timeout 為上述 `masterData.test.tsx` 案例；完整堆疊與 suite 摘要保存在忽略路徑 `backend/target/issue-139-frontend-ci.log`。Node 24 Linux 隔離重跑該檔 10/10 PASS、145.53 秒，timeout 案例 28.13 秒，完整 stack 在 `backend/target/issue-139-masterdata-test.log`。Windows SP-806 和併行全套 timeout 的詳細輸出保存在 `frontend/issue-139-sp806.log`／`frontend/issue-139-npm-test.log`。
- 不提高 timeout、不略過或刪除測例、不放寬斷言。Push 後以交付 SHA 的 GitHub Frontend CI 結果作最終交叉確認；若 GitHub CI 無法執行，完整本機 Linux suite 的單一 timeout 必須如實列為未通過項目。
