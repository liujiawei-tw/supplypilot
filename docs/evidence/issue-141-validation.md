# Issue #141（V2-204）本機驗收紀錄

## 版本與驗收範圍

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#141](https://github.com/liujiawei-tw/supplypilot/issues/141)
- Base branch／SHA：`main`／`923de72580597c00ce2634276a451aca76898769`
- Feature branch：`codex/issue-141-v2-204`
- P2 修正起始 Head：`aaf8bb2d97878151cca91cd7135025ceab7deee6`（歷史驗證版本）
- 最終交付驗證起始 Head：`25200eb65ea19fbf72faba2e71a380b8919c7350`（fetch 後遠端與本機相同）
- 原功能實作 Commit：`33afa05336ae9efa0317962c43bb83e3cd9cc2c8`
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`；未查詢、等待或依賴 Actions。
- 使用者明確核准本次 Issue #141 完整 Backend Maven Verify 與 Docker／Compose Gate；另核准暫不執行完整 Frontend Vitest，並允許在必要 Gate 通過且 GPT Review 通過後作為 Issue #141 一次性例外交付。
- 此授權只適用 #141。即使本次必要 Gate 通過，也不代表完整 CI 或完整本機 CI 等價驗證通過。

## 測試版本對照

| 驗證批次 | 程式碼／工作樹版本 | 結果與範圍 |
| --- | --- | --- |
| 原始 Issue 精準驗證 | 以 `923de72580597c00ce2634276a451aca76898769` 為基底的未提交 Issue #141 工作樹；不是該 SHA 的乾淨 checkout，也不是後續 Commit | 後端 52 tests 通過；前端 PO／收貨 API 精準測試 29 tests 通過；lint、Production Build 通過；Compose Python 4 tests 通過（詳見下節）。 |
| 前次內部 Review 修正後驗證 | 前次修正工作樹；對應其後提交的 `aaf8bb2d97878151cca91cd7135025ceab7deee6`。當時測試是在提交前工作樹執行，不能解讀成 GPT 已重跑或某個後續 Commit 的新驗收 | 後端精準測試 52/52 通過；Receiving 頁面指定案例 1 passed、17 skipped；前端 Build 重建成功。這些是歷史結果，本次未重跑。 |
| 本次 P2 Red | 以 `aaf8bb2d97878151cca91cd7135025ceab7deee6` 為基底，加入新負向整合測試但尚未修改 DTO 的未提交工作樹 | 指定新測例如預期失敗：`{}` 得到 HTTP 200，預期 HTTP 400。此為 Red 證據，不是驗收通過。 |
| 本次 P2 Green（提交前） | 以 `aaf8bb2d97878151cca91cd7135025ceab7deee6` 為基底，包含目前 DTO、整合測試修正的未提交工作樹；執行期間程式碼未再修改 | 指定負向案例 1/1 通過；`PurchaseOrderReceivingIntegrationTests` 9/9 通過；`git diff --check` 通過。MySQL Testcontainers 整合測試。 |
| 前次 P2 程式碼／測試 Commit | `68ae905b396b204571082f2cbabde93c7f682080` | 前次 Green 驗證對應的 P2 DTO／測試內容；不是本次最後 Gate 驗證的 fixture 版本。 |
| 前次 P2 文件 Head | `25200eb65ea19fbf72faba2e71a380b8919c7350` | 前次文件更新 Commit；本次最終驗收文件會再以文件 Commit 更新。 |
| 本次最終 Gate 程式碼／測試 Commit | `e81cf3a` | 完整 Maven Verify 及相關 Docker／Compose Gate 對應此程式碼版本；測試實際在其提交前、內容完全相同的工作樹執行，沒有將測試說成在 Commit checkout 上重跑。 |
| 本次最終驗收文件 | 程式碼／測試 Commit 後續的 docs-only Commit；完整 Head SHA 見交接回報 | 文件版本與程式碼測試版本分開，docs-only Commit 未修改程式碼。 |

## 最終交付驗證（Issue #141 一次性 Gate 例外）

使用者明確授權：執行完整 Backend Maven Verify 與 Docker／Compose Gate；本次不執行完整 Frontend Vitest，保留前端精準測試、Lint 與 Production Build 的歷史證據。使用者允許必要 Gate 通過且 GPT 獨立 Review 通過後，按 #141 一次性例外交付。這不是完整 CI PASS 或完整本機 CI 等價驗證 PASS。

驗證起始程式碼為遠端／本機一致的 `25200eb65ea19fbf72faba2e71a380b8919c7350`。Maven 初次執行時，兩個測試 fixture 尚未符合 #141 的人工收貨驗收前置條件；其後只調整這兩個測試 fixture，精準案例 Green，並以相同程式碼差異重跑完整 Verify。下表測試證據均為「基底 SHA 加本次未提交測試 fixture 差異」的工作樹內容，程式碼內容與後續程式碼／測試 Commit `e81cf3a` 完全一致，沒有在 Commit checkout 上重跑。最終驗收文件則另以 docs-only Commit 提交，最終完整 Head SHA 記錄於交接回報。

執行環境：Windows 11 x86_64、Java 21.0.10（Azul Zulu）、Maven Wrapper 3.9.16、Docker Desktop／Engine 29.6.1（Docker Desktop Linux Engine）、Docker Compose v5.3.0。

| Gate／項目 | 結果 | 指令、版本、耗時與證據 |
| --- | --- | --- |
| Gate A 初次 Backend Maven Verify | FAIL；682 tests、7 failures、0 errors、1 skipped；9:02 | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`；對應 `25200eb65ea19fbf72faba2e71a380b8919c7350` 加未提交工作樹。失敗明細為 `DemoSeedSmokeIntegrationTests.runsRecommendationDraftApproveOrderReceiveThroughRealApisAndPreservesAudit` 1 個，以及 `StockTransactionControllerIntegrationTests` 6 個（`filtersInclusivelyPaginatesAndValidatesAllQueryParameters`、`returnsManualAndPurchaseReceiveDataWithStrictContracts`、`supportsAllSortFieldsAndStableIdTieBreak`、`enforcesCompleteRoleMatrixForListAndDetail`、`preservesStockTransactionActorSnapshotAfterRenameAndPermanentDeletion`、`usesBoundedQueryCountForDifferentPageSizes`）。前者在 `DemoSeedSmokeIntegrationTests.java:316`、後六者都在 `StockTransactionControllerIntegrationTests.setUp:92 -> createAndReceivePurchaseOrder:308` 得到 `Status expected:<200> but was:<409>`；共同原因是舊 fixture 從 `ORDERED` 直接收貨，缺少 #141 要求的人工驗收步驟。完整初次輸出保存在 [issue-141-initial-mvn-verify-25200eb.log](issue-141-initial-mvn-verify-25200eb.log)；其中 27 個只供整合測試使用的暫時 JWT（25 個授權標頭及 2 個 `accessToken` 回應值）已遮蔽。原 `backend/target/surefire-reports/` 報告在最終重跑後遭覆寫；原始 log 內含完整失敗輸出、682／7／0／1 統計及 9:02 耗時。 |
| 問題精準 Red | FAIL（預期重現）；1 test | `cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=StockTransactionControllerIntegrationTests#returnsManualAndPurchaseReceiveDataWithStrictContracts" test`；精準重現 setup 的 HTTP 409，舊 fixture 預期 200。 |
| 問題精準 Green | PASS；2 tests、0 failures/errors/skips；1:17 | `cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=StockTransactionControllerIntegrationTests#returnsManualAndPurchaseReceiveDataWithStrictContracts,DemoSeedSmokeIntegrationTests#runsRecommendationDraftApproveOrderReceiveThroughRealApisAndPreservesAudit" test`；兩 fixture 先以管理員 API 開始驗收並提交三旗標全 true，再由原角色執行正式收貨；原收貨及稽核斷言保留。 |
| Gate A 最終 Backend Maven Verify | PASS；682 tests、0 failures、0 errors、1 skipped；8:49 | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`；測試在 `25200eb…` 基底加精準 Green 的兩個測試 fixture 差異之未提交工作樹執行，內容與程式碼／測試 Commit `e81cf3a` 一致，未修改生產程式。107 份 Surefire XML 合計 682 tests。POM 未設定 Maven Failsafe Plugin，Verify 測試報告由 Surefire 產生，`target/failsafe-reports` 不存在。Testcontainers MySQL 8.4.11，Flyway V1–V27 均成功套用。 |
| 唯一 skipped 測試 | SKIPPED（明確 opt-in） | `SemanticRealProviderSmokeTests.developerApiReturnsConfiguredDimension` 使用 `Assumptions.assumeTrue(Boolean.getBoolean("semantic.real-smoke"))`；未設定 `-Dsemantic.real-smoke=true`，也未呼叫付費 Gemini API。此案例沒有執行，不列為 PASS。 |
| Python 3.12 Compose unittest（首次容器嘗試） | FAIL／環境不適用；4 errors | 官方 `python:3.12-slim` 為 Python 3.12.15，但容器沒有 `docker` CLI；四個案例均因 `FileNotFoundError: docker` 在 subprocess 啟動時報錯。這是容器執行環境失敗，不是 Compose 斷言失敗。 |
| Python 3.12 Compose unittest（有效執行） | PASS；4 tests；2.504 秒 | Python 3.12.14，Codex 桌面既有 bundled Python runtime；`python -m unittest scripts.tests.test_compose_config -v`（實際使用 bundled Python 3.12.14 絕對路徑）。 |
| Docker Engine 檢查 | PASS | `docker info --format "Server={{.ServerVersion}} OS={{.OperatingSystem}} Arch={{.Architecture}} CPUs={{.NCPU}}"`；Server 29.6.1、Docker Desktop、x86_64、12 CPUs。 |
| Production Compose Config | PASS | `docker compose --env-file <temporary-prod-env> config --quiet`；僅使用 CI placeholder 暫存 env。 |
| Demo Compose Config | PASS | `docker compose --env-file <temporary-demo-env> -f compose.yaml -f compose.demo.yaml config --quiet`；使用 CI placeholder 及 `DEMO_ACCOUNT_PASSWORD=ci-demo-placeholder`。 |
| Development Compose Config | PASS | `docker compose --env-file <temporary-dev-env> -f compose.dev.yaml config --quiet`；同一 placeholder 環境。三組連續執行合計 1.092 秒，起訖時間 2026-10-22 14:34:09.021–14:34:10.114 +08:00。臨時 env 檔已逐一移除；只做 config，未啟動服務、重置 Demo 或刪除資料。 |
| Backend Docker Image | PASS；20.966 秒 | `docker build --file backend/Dockerfile --tag supplypilot-backend:ci .`；Image ID／RepoDigest：`sha256:51c5ec48d5d9f2f5540407b119d0c1a3d2e09c2a2420704d2b3ea4b132e3d1c0`。Build log 暫存於 `%TEMP%\supplypilot-issue-141-backend-image-25200eb.log`。 |
| Frontend Docker Image | PASS；13.188 秒 | `docker build --file frontend/Dockerfile --build-arg VITE_API_BASE_URL=/api/v1 --tag supplypilot-frontend:ci .`；Image ID／RepoDigest：`sha256:33654b1fe43062c6a7fd30fa28ba06725344eb6303504679f9609088c7741e8d`。Dockerfile 執行既有 `npm ci` 及 Production Build，未執行 Vitest；Vite 有 bundle >500 kB 提示。Build log 暫存於 `%TEMP%\supplypilot-issue-141-frontend-image-25200eb.log`。 |
| `git diff --check`／變更範圍 | PASS | 2026-10-22；無 whitespace 錯誤。差異只有兩個既有整合測試 fixture；未修改生產程式、V27 Migration、Workflow、Frontend、部署設定；未將真實憑證寫入 Repository。 |
| 完整 Frontend Vitest | `NOT RUN TO COMPLETION — USER-APPROVED ISSUE #141 EXCEPTION` | 依使用者明確核准，不執行 `npm test`、`npm run test` 或 `vitest run`。歷史前端 29/29 精準測試、Receiving 指定案例、Lint 與 Production Build 見下節；不是本次新 Head 重跑。 |
| GitHub Actions | `PAUSED BY USER / NOT USED AS GATE` | 依使用者指示未執行或查詢；本機結果不稱 GitHub CI PASS。 |

完整 Frontend Vitest 未執行代表最新完整前端案例數及跨模組回歸尚未驗證，Windows Vitest 耗時／穩定性仍未解決，留待 Issue #160。此一次性例外只適用 Issue #141。

## 本次 P2 精準驗證明細

執行環境：Java 21.0.10、Spring Boot 測試設定、Docker Desktop 29.6.1、Testcontainers 2.0.5、MySQL 8.4.11，Flyway V1–V27 成功套用。測試均在本機提交前工作樹、基底 SHA `aaf8bb2d97878151cca91cd7135025ceab7deee6` 執行。

| 項目 | 結果 | 指令／證據 |
| --- | --- | --- |
| Red：未修正 DTO 的缺欄請求 | 預期失敗 | `cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=PurchaseOrderReceivingIntegrationTests#rejectsIncompleteNullAndUnknownInspectionRequestsWithoutChangingAnyState" test`；`{}` 回 HTTP 200，測試預期 400，確認原缺陷。 |
| Green：缺欄、null、未知欄位與無副作用 | PASS，1 test | 同上指定測例；缺任一旗標、任一旗標為 null、未知 `unknownStage` 均回 400 `VALIDATION_FAILED`。檢查 PO 狀態／進度、驗收旗標／備註、操作者快照、時間、version、庫存及 StockTransaction 均不變。 |
| 收貨驗收／交易回歸 | PASS，9 tests | `cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=PurchaseOrderReceivingIntegrationTests" test`；三旗標全 false 可保存且收貨被拒；全 true 可走既有收貨流程；該類別既有授權、PO 狀態、庫存交易等案例通過。 |
| 差異空白檢查 | PASS | `git diff --check`；無錯誤輸出（Git 僅提示工作副本 LF／CRLF 轉換）。 |
| 內部雙軸 Code Review | PASS | Matt Pocock `code-review` Spec 與 Standards 兩位審查者針對本次 diff 獨立複審；未發現阻擋項。 |

前次 P2 未修改前端契約，故沒有執行前端測試。上述 P2 測試是在當時提交前工作樹執行；前次文件 Head `25200eb65ea19fbf72faba2e71a380b8919c7350` 更新後，未重跑程式碼測試。

## 原始 Issue 精準驗證歷史

以下是之前工作樹留下的結果，並非本次程式碼 Commit 上重新執行：

| 項目 | 歷史結果 | 執行資訊 |
| --- | --- | --- |
| 後端 Issue 相關單元與整合測試 | PASS，52 tests | Java 21.0.10；`cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=PurchaseOrderTests,PurchaseOrderReceivingIntegrationTests,PurchaseOrderConcurrentReceivingIntegrationTests,ReceivingPurchaseOrderControllerIntegrationTests" test`；Testcontainers MySQL 8.4.11，Flyway V1–V27。工作樹以 base SHA `923de72580597c00ce2634276a451aca76898769` 為基底並含未提交變更。 |
| 前端 PO／收貨 UI 與 API 測試 | PASS，2 files／29 tests | Node 24.22.1 Linux；`npm test -- --run tests/sp805.test.tsx tests/sp805Api.test.ts`；以 base SHA `923de72580597c00ce2634276a451aca76898769` 為基底的未提交工作樹。 |
| 前端 Receiving 精準案例 | PASS，1 passed／17 skipped | Node 24.22.1 Linux；`npm test -- --run tests/sp805.test.tsx -t "loads warehouse detail only from the restricted endpoint"`；於前次 Review 修正工作樹重驗，對應後續 Head `aaf8bb2d97878151cca91cd7135025ceab7deee6`，非本次 P2 工作樹。 |
| 前端 lint／Production Build | PASS | Node 24.22.1 Linux；`npm run lint` 及 `VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build`；Build 在前次 Review 的 Receiving DTO 修正後重建。 |
| Compose Python 單元測試 | PASS，4 tests | `python -m unittest scripts.tests.test_compose_config -v`；主機 Python 3.13.5，不是 Python 3.12。 |

## 未執行項目與歷史失敗

- 完整前端 Vitest：`NOT RUN TO COMPLETION — USER-APPROVED ISSUE #141 EXCEPTION`。不保證最新完整案例數或跨模組前端回歸；Windows Vitest 耗時／穩定性問題留待 #160。本次從未啟動完整 Frontend Vitest。
- Backend 完整 `mvn verify`：Gate A 最終 `PASS`，但 1 個明確 opt-in 的 Gemini real-provider smoke 測試為 skipped；見「最終交付驗證」表。此結果不等於完整 CI PASS。
- Compose Production／Demo／Development `config --quiet`：三組均 `PASS`，以 CI placeholder 環境執行。
- Backend／Frontend Docker Image builds：均 `PASS`，詳見記錄的 Image ID／RepoDigest。
- Python 3.12 Compose 測試：Python 3.12.14 bundled runtime `PASS` 4/4。首次 Python 3.12.15 容器嘗試因容器缺少 Docker CLI 而 4 errors；此環境失敗已保留。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`，未執行或查詢；本機 Gate 不能替代成 GitHub CI PASS。
- 前次前端 Receiving 頁面精準案例曾因移除付款區塊時漏留狀態 `Tag` import 而失敗；補回 import 後同一案例通過。保留此歷史失敗紀錄。
- 前次後端精準測試曾有 48 tests 中 5 個失敗：2 個 Domain 測試的檢查者未持久化，3 個重複收貨測試用舊 JPA `@Version` 比較；修正後相關測試重跑 52/52 通過。保留此歷史失敗紀錄。
- Node 24 Linux 的 npm audit 歷史回報 2 個 moderate vulnerabilities；本次未碰前端套件或 lockfile。

## 本次修正與內部 Review

- P2-01：`ReceivingInspectionRequest` 三個驗收旗標改為 `@NotNull Boolean`，缺欄或明確 null 均進入既有 Bean Validation 400 `VALIDATION_FAILED`；使用 DTO 區域 `@JsonAnySetter` 拒絕未知欄位，未改動全域 Jackson 設定。`note` 仍 nullable 並保留 2000 字元限制。`false` 仍為合法值，原有收貨准入條件不變。
- P2-02：本文件明確區分原始精準驗證、前次 Review 修正後驗證、本次 Red／Green、最終 Gate 工作樹證據、程式碼測試 Commit 與文件 Commit；不把提交前測試描述成新 Commit checkout 測試。
- 最終 Gate 所需測試 fixture 修正：兩個舊整合測試在直接呼叫收貨前，新增 ADMIN 開始驗收並提交三旗標全 true 的 API 步驟。未修改生產收貨規則，原角色收貨及庫存／StockTransaction assertions 保留。
- 本次雙軸內部複審：Spec PASS，確認 fixtures 符合 #141 全數人工驗收前置；Standards 無硬性違規。Standards 指出兩 fixture 有少量重複 setup 序列，屬低優先級判斷性 smell，兩個測試脈絡分開且僅兩處使用，沒有阻擋修正或要求抽出共用 helper。
- 內部 Spec Review：PASS；確認缺欄／null／unknown 欄位拒絕、400 格式、資料無副作用及 Issue 範圍。
- 內部 Standards Review：PASS；確認 DTO 驗證先於 service unboxing、Jackson 未知欄位錯誤走既有 400 handler，且無授權／交易回歸。指出測試 helper 對非 null note 字串採手動 JSON 引號，但現有受控輸入不含需跳脫字元，非阻擋項。
