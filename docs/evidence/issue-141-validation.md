# Issue #141（V2-204）本機驗收紀錄

## 版本與驗收範圍

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#141](https://github.com/liujiawei-tw/supplypilot/issues/141)
- Base branch／SHA：`main`／`923de72580597c00ce2634276a451aca76898769`
- Feature branch：`codex/issue-141-v2-204`
- 本次修正起始 Head：`aaf8bb2d97878151cca91cd7135025ceab7deee6`（遠端與本機相同）
- 原功能實作 Commit：`33afa05336ae9efa0317962c43bb83e3cd9cc2c8`
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`；未查詢、等待或依賴 Actions。
- 本次僅處理 GPT Review P2-01（驗收旗標欄位驗證）及 P2-02（證據版本對照）。本地精準測試不代表完整本機 CI Gate。

## 測試版本對照

| 驗證批次 | 程式碼／工作樹版本 | 結果與範圍 |
| --- | --- | --- |
| 原始 Issue 精準驗證 | 以 `923de72580597c00ce2634276a451aca76898769` 為基底的未提交 Issue #141 工作樹；不是該 SHA 的乾淨 checkout，也不是後續 Commit | 後端 52 tests 通過；前端 PO／收貨 API 精準測試 29 tests 通過；lint、Production Build 通過；Compose Python 4 tests 通過（詳見下節）。 |
| 前次內部 Review 修正後驗證 | 前次修正工作樹；對應其後提交的 `aaf8bb2d97878151cca91cd7135025ceab7deee6`。當時測試是在提交前工作樹執行，不能解讀成 GPT 已重跑或某個後續 Commit 的新驗收 | 後端精準測試 52/52 通過；Receiving 頁面指定案例 1 passed、17 skipped；前端 Build 重建成功。這些是歷史結果，本次未重跑。 |
| 本次 P2 Red | 以 `aaf8bb2d97878151cca91cd7135025ceab7deee6` 為基底，加入新負向整合測試但尚未修改 DTO 的未提交工作樹 | 指定新測例如預期失敗：`{}` 得到 HTTP 200，預期 HTTP 400。此為 Red 證據，不是驗收通過。 |
| 本次 P2 Green（提交前） | 以 `aaf8bb2d97878151cca91cd7135025ceab7deee6` 為基底，包含目前 DTO、整合測試修正的未提交工作樹；執行期間程式碼未再修改 | 指定負向案例 1/1 通過；`PurchaseOrderReceivingIntegrationTests` 9/9 通過；`git diff --check` 通過。MySQL Testcontainers 整合測試。 |
| 程式碼／測試 Commit | `68ae905b396b204571082f2cbabde93c7f682080` | 本次 Green 驗證對應的程式碼和測試內容，Commit 前後沒有執行程式碼變更。不能稱為在 Commit checkout 上重新執行。 |
| 最終文件 Head | 此文件更新 Commit 為最終 Head；SHA 以交付時本機／遠端 Head 核對為準 | 僅含驗收文件更新；測試證據對應上一列程式碼／測試 Commit，沒有將文件 Commit 說成新程式碼測試版本。 |

## 本次 P2 精準驗證明細

執行環境：Java 21.0.10、Spring Boot 測試設定、Docker Desktop 29.6.1、Testcontainers 2.0.5、MySQL 8.4.11，Flyway V1–V27 成功套用。測試均在本機提交前工作樹、基底 SHA `aaf8bb2d97878151cca91cd7135025ceab7deee6` 執行。

| 項目 | 結果 | 指令／證據 |
| --- | --- | --- |
| Red：未修正 DTO 的缺欄請求 | 預期失敗 | `cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=PurchaseOrderReceivingIntegrationTests#rejectsIncompleteNullAndUnknownInspectionRequestsWithoutChangingAnyState" test`；`{}` 回 HTTP 200，測試預期 400，確認原缺陷。 |
| Green：缺欄、null、未知欄位與無副作用 | PASS，1 test | 同上指定測例；缺任一旗標、任一旗標為 null、未知 `unknownStage` 均回 400 `VALIDATION_FAILED`。檢查 PO 狀態／進度、驗收旗標／備註、操作者快照、時間、version、庫存及 StockTransaction 均不變。 |
| 收貨驗收／交易回歸 | PASS，9 tests | `cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=PurchaseOrderReceivingIntegrationTests" test`；三旗標全 false 可保存且收貨被拒；全 true 可走既有收貨流程；該類別既有授權、PO 狀態、庫存交易等案例通過。 |
| 差異空白檢查 | PASS | `git diff --check`；無錯誤輸出（Git 僅提示工作副本 LF／CRLF 轉換）。 |
| 內部雙軸 Code Review | PASS | Matt Pocock `code-review` Spec 與 Standards 兩位審查者針對本次 diff 獨立複審；未發現阻擋項。 |

本次未修改前端契約，故沒有執行前端測試。上述測試是提交前工作樹證據；本次文件 Commit 更新後，未重跑程式碼測試。

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

- 完整前端 Vitest：`NOT RUN TO COMPLETION`。使用者要求停止完整套件並停在精準測試；中止前已完成的檔案不代表完整套件 PASS。本次沒有重啟。
- 完整後端 `mvn verify`：`NOT RUN`；本次依指示只跑收貨驗收精準整合測試。
- Compose Production／Demo／Development `config --quiet`：`NOT RUN`。
- Backend／Frontend Docker Image builds：`NOT RUN`。
- Python 3.12 Compose 測試：`NOT RUN`；歷史主機為 Python 3.13.5。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`，未查詢；不構成本機完整 Gate 的替代或例外。
- 前次前端 Receiving 頁面精準案例曾因移除付款區塊時漏留狀態 `Tag` import 而失敗；補回 import 後同一案例通過。保留此歷史失敗紀錄。
- 前次後端精準測試曾有 48 tests 中 5 個失敗：2 個 Domain 測試的檢查者未持久化，3 個重複收貨測試用舊 JPA `@Version` 比較；修正後相關測試重跑 52/52 通過。保留此歷史失敗紀錄。
- Node 24 Linux 的 npm audit 歷史回報 2 個 moderate vulnerabilities；本次未碰前端套件或 lockfile。

## 本次修正與內部 Review

- P2-01：`ReceivingInspectionRequest` 三個驗收旗標改為 `@NotNull Boolean`，缺欄或明確 null 均進入既有 Bean Validation 400 `VALIDATION_FAILED`；使用 DTO 區域 `@JsonAnySetter` 拒絕未知欄位，未改動全域 Jackson 設定。`note` 仍 nullable 並保留 2000 字元限制。`false` 仍為合法值，原有收貨准入條件不變。
- P2-02：本文件明確區分原始精準驗證、前次 Review 修正後驗證、本次 Red／Green 工作樹證據，以及程式碼與文件 Commit。待建立 Commit 後填入上方兩個 SHA 欄位。
- 內部 Spec Review：PASS；確認缺欄／null／unknown 欄位拒絕、400 格式、資料無副作用及 Issue 範圍。
- 內部 Standards Review：PASS；確認 DTO 驗證先於 service unboxing、Jackson 未知欄位錯誤走既有 400 handler，且無授權／交易回歸。指出測試 helper 對非 null note 字串採手動 JSON 引號，但現有受控輸入不含需跳脫字元，非阻擋項。
