# Issue #142（V2-205）精準驗收紀錄

## 版本與範圍

- Issue：https://github.com/liujiawei-tw/supplypilot/issues/142
- Repository：`liujiawei-tw/supplypilot`
- Base branch／SHA：`origin/main`／`9240d6aeeba2361476a0454ac965a214769638a0`
- Feature branch：`codex/issue-142-v2-205`
- 工作目錄：`.worktrees/issue-142-v2-205`
- 驗證時 HEAD：`9240d6aeeba2361476a0454ac965a214769638a0`；Issue 變更尚未提交。
- 環境：Windows 11、Java 21.0.10（Zulu）、Maven Wrapper 3.9.16。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`；本次未執行。
- 本紀錄只涵蓋使用者核准的 Java 編譯、精準測試、靜態檢查；完整 CI Gate 延至 Issue #160 Merge 後。

## 精準驗證結果

| 項目 | 結果 | 指令／證據 |
| --- | --- | --- |
| Java 主程式編譯 | PASS，17.496 秒 | `cd backend; .\mvnw.cmd -B --no-transfer-progress -DskipTests compile`；以 Java 21 編譯 253 個主程式來源檔。只有既有 Spring AI deprecation warnings。 |
| PurchaseOrder Domain 單元測試 | PASS，22/22；測例執行 0.709 秒，Maven 總計 25.281 秒 | `cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=PurchaseOrderTests" test`。涵蓋 Stage 轉移、非法轉移、狀態配對、取消／拒絕及 Stage 7/8 等 Domain 行為。 |
| PurchaseOrder Service 單元測試 | PASS，5/5；測例執行 3.161 秒 | `cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=PurchaseOrderTests,PurchaseOrderServiceTests" test` 的同一執行批次中，Service 類別 5 項全數通過。該批次因當時 3 個 Domain fixture 問題而整體 FAIL；Domain fixture 修正後只重跑受影響的 Domain 類別，未重複執行未變更的 Service 類別。 |
| Migration／MySQL 8.4.11 精準整合測試 | BLOCKED / DEFERRED | 未執行 `V23MigrationIntegrationTests`。唯讀檢查確認 Docker 現有 `node:24-bookworm-slim` 容器正執行 `npm test -- --maxWorkers=1 tests/sp806.test.ts`（Vitest worker CPU 約 106%、記憶體約 1.019 GiB），屬 #160 測試工作；依指示不啟動會競爭 CPU／RAM 的 MySQL Testcontainers。 |
| Transaction／Security／Controller 整合測試 | BLOCKED / DEFERRED | 這些代表性整合測試依賴 Testcontainers／Spring Context；為避免干擾上述 #160 Vitest 容器，本次未啟動。 |
| `git diff --check` | PASS | 最後一次執行無錯誤輸出。 |
| 靜態 Code Review | PASS（靜態檢查範圍） | 核對 Stage/status 轉移、`@Transactional`、列鎖 `findByIdForUpdate()`、新端點 `@PreAuthorize` 角色及 V28 CHECK；不代表 GPT 獨立 Review 或整合驗證通過。 |

## 失敗與修正紀錄

1. 首次精準測試在 test compile 失敗：`PurchaseOrderLifecycleIntegrationTests.databaseRejectsStageAndLifecycleStatusMismatch()` 呼叫 `draftId()`，但未宣告 checked exception。已將測試方法宣告為 `throws Exception`；之後全 118 個測試來源檔編譯成功。
2. 首次 Domain／Service 精準執行時，Domain 22 項中 3 項失敗／錯誤：既有 Domain 測例直接核准 Stage 1 PO，或只以反射改 `status` 而未同步 `stage`。這些是測例未建立新 Stage 前置狀態；已更新測例沿 Stage 1→2→3 前進，及在測試 Approved 狀態時同步設定 Stage 4。修正後 `PurchaseOrderTests` 22/22 通過。
3. Service 類別在上述同一次執行中已 5/5 通過，該類別及其受測程式未再修改，故沒有重跑。

## Migration、Transaction 與 Legacy 檢查

- V28 SQL 與遷移測試程式已靜態檢視；預期回填為 DRAFT→Stage 1、APPROVED→Stage 4、ORDERED 依 `ordered_progress` 映射 Stage 5/6、RECEIVED 依明確 `payment_confirmed_at` 映射 Stage 7/8。
- `ORDERED + LEGACY_UNKNOWN` 回填 `stage=NULL`；CHECK 僅在該 Legacy 狀態容許 `NULL`。應用程式的收貨驗收、正式收貨與取消轉移均要求具體合法進度／Stage，無法從這筆未知紀錄直接執行；回應 Stage 可為 `null`，不會推測成已完成。
- 原始驗證紀錄當時尚無 Legacy 狀態處置流程；此後使用者核定由 ADMIN 透過既有 `begin-receiving-inspection` 命令明確處置至 Stage 6，詳見下方開發優先續作附錄。本 Issue 未新增任意 Stage 變更 API。
- MySQL 實際遷移、CHECK 在 MySQL 上的行為、JPA 持久化及 PO／Inventory／StockTransaction 原子性仍未由本次精準整合測試驗證，待 #160 合併後補驗。

## 未執行與後續 Gate

以下均為 **NOT RUN**，不可解讀為 PASS：

- Backend 全量 `.\mvnw.cmd -B --no-transfer-progress verify`。
- Frontend `npm ci`、Lint、全量 Vitest 與 Build。
- Windows／Linux 全量測試、Docker Image Builds、Production／Demo／Development Compose Gate。
- GitHub Actions。
- 本次因 #160 資源占用而延後的 V28 MySQL Migration、Transaction、Security 與 Controller 整合測試。

待 #160 合併且使用者啟動正式驗證階段後，再依專案 Gate 整合 #160、執行完整後端／前端／Docker／Compose 驗收，並記錄對應版本與結果。本機精準測試不替代完整 CI Gate。

## 驗收狀態

`ISSUE #142 — UNIT VERIFIED / REQUIRED MIGRATION & INTEGRATION BLOCKED-DEFERRED / FULL CI DEFERRED / WAITING FOR #160 MERGE`

## 2026-10-23 開發優先續作附錄

本附錄記錄使用者核准開發期優先編碼後的增量結果，並更新上方 Legacy 段落中「目前沒有處置流程」的歷史判斷。上方原始驗證紀錄保留，不代表本次新變更已完成正式 Issue Gate。

- 驗證基線：Issue Worktree `codex/issue-142-v2-205` 的 HEAD 仍為 `9240d6aeeba2361476a0454ac965a214769638a0`；本次未整合 main。查核到最新 main `1230319d088da191ea2d90def086a4eafbe1c330`。使用者核定 ADMIN 可用既有 `begin-receiving-inspection` 命令將 `ORDERED + LEGACY_UNKNOWN + stage=NULL` 明確處置到 Stage 6；此操作不推測過去欄位、不改 Inventory 或 StockTransaction。
- 下列快照與測試 Log 保存在工作站本機的 Repository `.worktrees/coord-evidence/`，不屬於 Git 追蹤檔案。
- WIP 完整性基線先保存於 `.worktrees/coord-evidence/issue142-before-20261023-214906-342/`：22 個 tracked paths 的二進位 Diff 為 64,680 bytes、SHA-256 `19c6ecd7b0a052a6ec50f9013e8ba2a87a965f46d1cb0c84892d657d8b1408b3`；3 個原有 untracked 檔案分別複製並核對 manifest 內 SHA-256。用 Orchestrator `capture_worktree` 同一指紋演算法從保存快照重建的初始指紋為 `bb82bb9ae95bfdf1724b895facec743a06ad61a4aab86c00041e25a71a000232`，與原 State 記錄吻合；目前預期實作後指紋為 `2d2e5bb6053e5713ec07f96e1a1f80d43389f530db4c8fae6b747698f14172f7`，原始 3 個 untracked 檔案仍在，State 未變更。
- Legacy Domain 測例先紅後綠。紅燈指令 `cd backend; .\mvnw.cmd -B -Dtest=PurchaseOrderTests test`：Tests 23，Errors 1，Exit Code 1；失敗為 Legacy 記錄的 `beginReceivingInspection()` 被拒絕，原始 Log：`.worktrees/coord-evidence/issue142-work-20261023-215200-689/PurchaseOrderTests-red.log`。實作後指令 `cd backend; .\mvnw.cmd -B --no-transfer-progress -Dtest=PurchaseOrderTests test`：Tests 23、Failures 0、Errors 0、Skipped 0，Exit Code 0，Log：`.worktrees/coord-evidence/issue142-work-20261023-215315-892/PurchaseOrderTests-green.log`。
- 更新後 Controller/Lifecycle、Receiving 與 V28 Migration 測試來源共 118 個 test source files；`cd backend; .\mvnw.cmd -B --no-transfer-progress -DskipTests test-compile` 編譯成功，Exit Code 0，Log：`.worktrees/coord-evidence/issue142-work-20261023-215833-937/backend-test-compile-final.log`。包含 Admin/非 Admin Legacy disposition、Stage GET 回讀、禁止 PATCH status/Stage、八欄 Stage 回應與 Legacy V28 CHECK 負向案例。
- `git diff --check`：Exit Code 0。上述 Controller/DB Migration/Testcontainers 整合案例尚未執行；Docker Engine 探測回 `permission denied while trying to connect to npipe:////./pipe/dockerDesktopLinuxEngine`，因此標示 `BLOCKED / NOT RUN`，未啟動容器。GitHub Actions 為 `PAUSED BY USER / NOT USED AS GATE`。
- 當前驗收狀態：`DEVELOPMENT SLICE VERIFIED: PurchaseOrder Domain 23/23; TEST SOURCES COMPILE; INTEGRATION/MYSQL MIGRATION BLOCKED BY DOCKER ACCESS; FORMAL ISSUE GATE NOT RUN`。

### 22:02 測試來源增量

- 將 lifecycle approval/rejection 的資料庫失敗測試補上 stage rollback 斷言（分別維持 `APPROVAL`、`NEW_REQUEST`）；收貨第三筆 StockTransaction 寫入失敗測試補上 `RECEIVING_INSPECTION` 未改變的斷言。這些是既有測試範圍的 Stage 持久化回歸覆蓋。
- 變更後再次執行 `cd backend; .\mvnw.cmd -B --no-transfer-progress -DskipTests test-compile`：118 個 test source files 編譯，`BUILD SUCCESS`，Exit Code 0（2026-10-23 22:02 Asia/Taipei）。該次終端輸出已保留於 Codex 執行紀錄，沒有另存新原始 Log 檔。
- 同一版本 `git diff --check` Exit Code 0。Docker API 仍拒絕連線，故 Migration／Controller／Transaction integration cases 仍 `BLOCKED / NOT RUN`；這些結果不構成正式 Issue Gate PASS。
