# Issue #141（V2-204）本機驗收紀錄

## 驗收版本

- Repository：`liujiawei-tw/supplypilot`
- Issue：[#141](https://github.com/liujiawei-tw/supplypilot/issues/141)
- Feature Branch：`codex/issue-141-v2-204`
- Base SHA：`923de72580597c00ce2634276a451aca76898769`
- 驗收時工作樹基底 SHA：`923de72580597c00ce2634276a451aca76898769`（驗收內容包含該 SHA 上尚未提交的 Issue #141 工作樹變更）
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`；未查詢、等待或依賴 Actions。

## 精準驗收結果

| 項目 | 結果 | 證據 |
| --- | --- | --- |
| 後端 Issue 相關單元與整合測試 | PASS | Java 21.0.10；`cd backend; .\mvnw.cmd -B --no-transfer-progress "-Dtest=PurchaseOrderTests,PurchaseOrderReceivingIntegrationTests,PurchaseOrderConcurrentReceivingIntegrationTests,ReceivingPurchaseOrderControllerIntegrationTests" test`；52 tests，0 failures/errors/skips。包含驗收與收貨競速、匿名／倉庫角色 401/403、倉庫 DTO 白名單。Testcontainers MySQL 8.4.11，Flyway V1–V27 成功套用。 |
| 前端 PO／收貨 UI 與 API 測試 | PASS（精準） | Node 24.22.1 Linux；`npm test -- --run tests/sp805.test.tsx tests/sp805Api.test.ts`；2 files、29 tests 通過。Review 後因 Receiving 白名單調整，另以 `npm test -- --run tests/sp805.test.tsx -t "loads warehouse detail only from the restricted endpoint"` 重驗受影響頁面，1 passed、17 skipped（僅執行該案例）。 |
| 前端 lint | PASS | Node 24.22.1 Linux；`npm run lint`，0 warnings/errors。 |
| 前端 Production Build | PASS | Node 24.22.1 Linux；`VITE_API_BASE_URL=http://localhost:8080/api/v1 npm run build` 成功；Review 後 Receiving DTO 調整另重建。Vite 回報單一 bundle 超過 500 kB 的建議警告。 |
| Compose Python 單元測試 | PASS（版本不同） | `python -m unittest scripts.tests.test_compose_config -v`；4 tests 通過。主機 Python 3.13.5；未在 Python 3.12 執行。 |
| `git diff --check` | PASS | 工作樹無 whitespace/error 輸出。 |

## 未完成項目

- 完整前端 Vitest：NOT RUN TO COMPLETION。Node 24 Linux 全量套件曾執行多個測試檔；使用者要求停止全量測試並停在精準測試後，該流程被中止，未取得完整套件結論。已完成的檔案不代表完整套件 PASS。
- 後端完整 Maven `verify`：NOT RUN。
- Compose Production／Demo／Development `config --quiet`：NOT RUN。
- Backend／Frontend Docker image build：NOT RUN。
- Python 3.12 Compose 單元測試：NOT RUN（主機為 Python 3.13.5）。
- GitHub Actions：依使用者指示不作為 Gate，未查詢。

## 已知注意事項

- Node 24 Linux 安裝相依套件時 npm audit 回報 2 個 moderate vulnerabilities；未變更套件或 lockfile 以規避此既有訊息。
- 完整 Vitest 的中止是使用者對驗收範圍的明確指示，不是測試成功或失敗的結論。
- Review 後首次 Receiving 頁面精準測試因移除付款區塊時漏留狀態 `Tag` 元件 import 而失敗；修正 import 後相同案例重跑 PASS。這是已修正的測試失敗。
- Matt Pocock `code-review` Spec／Standards 內部雙軸預檢：初審發現付款資料曝光與競速／授權測試缺口；修正後兩軸複審 PASS，無剩餘 findings。
- 本文件記錄的是未提交工作樹版本；Commit SHA 與遠端 Head SHA 將於交付時補充。
