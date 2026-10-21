# Issue #137 驗證紀錄

本紀錄對應 Feature Branch `codex/issue-137-v2-102`。測試時程式碼尚未提交，故版本識別為基於 `9c0130fb8ef0cdd6315d8dc3eea4486db955e008` 的 #137 未提交工作樹；提交後須以交付 SHA 的 GitHub Actions 結果作最終完整回歸證據。

| 驗證 | 指令／範圍 | 結果 | 版本與後續適用性 |
|---|---|---|---|
| Backend 完整回歸 | `cd backend; .\mvnw.cmd clean verify`；644 tests、0 failures、0 errors、1 skipped | PASS | #137 程式與測試修改後執行；後續僅新增規範／驗證文件，未改 Backend，仍適用於該工作樹程式碼。交付 SHA 仍由 CI 再驗。 |
| Frontend 完整 Vitest（本機） | `cd frontend; npm test -- --run` | FAIL；24 files 中 2 files failed，201 tests 中 3 個 SP-806 測例 timeout（每個約 30 秒）；Vitest 單 Worker，檔案耗時約 403 秒 | 同一未提交工作樹。完整執行工作階段結束時無法取回完整摘要／錯誤堆疊，故失敗測例名稱與卡點未能可靠記錄；不得標為 PASS。沒有再重跑完整套件。 |
| SP-806 隔離測試 | `cd frontend; npm test -- tests/sp806.test.tsx` | PASS；1 file、25 tests，duration 365.46 秒（tests 352.59 秒） | 同一未提交工作樹；證明 SP-806 在隔離執行時通過，不能單獨證明完整套件的 timeout 根因。未觀察到 #137 改動下的單檔失敗；整套執行中的狀態污染與資源競爭仍無法區分。 |
| Auth／SP-805 精準測試 | `npm test -- tests/auth.test.tsx tests/sp805.test.tsx` | PASS；2 files、32 tests | 在相同 #137 程式碼工作樹執行；後續只有文件變更，仍適用。 |
| Frontend Lint | `npm run lint` | PASS | 在前端程式修改後執行；僅其後有測試期望值及文件調整。 |
| Frontend Build | `npm run build` | PASS | 在最後前端產品程式修改後執行；其後沒有產品程式變更。 |
| Backend 相關整合測試 | `-Dtest=PurchaseOrderLifecycleIntegrationTests,PurchaseOrderOrderingIntegrationTests,PurchaseOrderReceivingIntegrationTests,ReceivingPurchaseOrderControllerIntegrationTests,V23MigrationIntegrationTests test` | PASS；23 tests | 與後續完整 Maven verify 對應同一 #137 程式工作樹。 |
| 使用者授權併發整合測試 | `-Dtest=UserAuthorizationIntegrationTests test` | PASS；24 tests | 包含刪除／撤銷競爭、最後一位管理者保護與併發衝突回應；對應最後完整 Maven verify 工作樹。 |
| Compose 設定 | `docker compose -f compose.yaml config --quiet`；`docker compose -f compose.dev.yaml config --quiet`；`docker compose -f compose.yaml -f compose.demo.yaml config --quiet` | PASS；三種配置 | Compose YAML 未修改；本次只影響資料庫遷移與服務 API。 |
| Docker Image Build／隔離 Smoke | Backend、Frontend image build；臨時 network/containers 啟動、API proxy 401、Frontend health 200、Backend restart health UP、Flyway V24 | PASS | 以 #137 工作樹 Dockerfile／應用程式建置。僅使用臨時容器與 network，沒有建立、刪除或修改 volume。 |
| Git diff whitespace | `git diff --check` | PASS | 加入本驗證紀錄後再執行。 |
| GitHub Actions 完整 CI | Feature Branch 交付 SHA | NOT RUN | Push 後取得同一 SHA 的 Workflow Run 與每個 Job 結果再更新。 |

## SP-806 診斷結論

完整 Vitest 中有 3 個 30 秒 timeout；隔離執行同一檔案則 25/25 通過。這表示失敗依賴完整套件的執行情境，但現有輸出不足以區分資源競爭、跨測試狀態污染或特定測例等待條件，也不足以證明由 #137 引入。因隔離測試已通過且完整執行的具體失敗堆疊無法恢復，沒有提高 timeout、跳過測試、放寬斷言或修改 SP-806 業務功能；以交付 SHA 的 GitHub CI 作完整前端回歸判定，CI 若失敗再針對其具體失敗診斷。
