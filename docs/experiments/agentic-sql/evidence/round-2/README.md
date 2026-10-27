# Agentic SQL 第二輪修正：測試證據

## 證據範圍

- Repository：`liujiawei-tw/supplypilot`
- Branch：`feature/agentic-sql-answer-engine`
- 審查基準：`8accebd4fb319825d590833a3389897da80ae62c`
- 最終修正 Commit：以包含本 README 與日誌的分支 HEAD 為準（SHA 於交接報告提供）
- 執行環境：Windows 11 amd64、Java 21.0.10 (Zulu)、Maven Wrapper 3.9.16、Python 3.13.5、Docker Desktop 29.6.1、Testcontainers 2.0.5
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`；未查詢、啟動或重跑 Actions。

所有日誌都隨修正分支提交，可直接從 GitHub 檢閱。`final-answer-subject-support.log` 是最後 Agentic SQL 程式及測試變更後的精準驗證；`branch-ai-controller.log` 是舊 API 獨立分診，之後僅修改 Agentic SQL prompt／verifier，未改舊 AI API。其後只更新本證據文件，沒有再改程式或測試。完整 115 項 Java 組在最後兩項答案／lineage 防護收斂前通過；後續 82 與 67 項精準重跑分別覆蓋 lineage 及主體支持度變更。沒有修改授權、功能開關或舊 RAG 查詢／回答程式。

## 最終精準驗證

| 範圍 | 結果 | Exit Code | 原始日誌 |
|---|---:|---:|---|
| Agentic SQL 單元、答案支持度、SQL 安全／遮罩、MySQL／PostgreSQL Testcontainers、授權、預設關閉、文件版本持久化、Document RAG、Semantic RAG | 115 tests；0 failures、0 errors、0 skipped | 0 | [final-java-focused.log](final-java-focused.log) |
| 加入 JOIN 未限定欄位 fail-closed 後重跑 Agentic SQL validator、support verifier、output policy、answer service、雙資料庫 executor | 82 tests；0 failures、0 errors、0 skipped | 0 | [final-lineage-hardening.log](final-lineage-hardening.log) |
| 最後加入列級 fact 必須有同列 subject 後，重跑 validator、support verifier、output policy、answer service | 67 tests；0 failures、0 errors、0 skipped | 0 | [final-answer-subject-support.log](final-answer-subject-support.log) |
| A/B runner Python 單元測試 | 9 tests；全數通過 | 0 | [ab-runner.log](ab-runner.log) |
| 舊 `AiControllerIntegrationTests` 在 `main` 隔離基線 | 7 tests；4 failures、0 errors、0 skipped | 1 | [baseline-ai-controller-87a35b31b12cd8a124b54ee14e93ba466fec8182-docker-access.log](baseline-ai-controller-87a35b31b12cd8a124b54ee14e93ba466fec8182-docker-access.log) |
| 舊 `AiControllerIntegrationTests` 在修正分支 | 7 tests；4 failures、0 errors、0 skipped | 1 | [branch-ai-controller.log](branch-ai-controller.log) |

最後兩組是受影響範圍的精準重跑，與完整 115 tests 組重疊，不可相加。115 tests 組完成文件版本、RAG、API 授權與功能開關回歸；82 tests 組驗證 JOIN lineage 收斂和雙資料庫 executor；67 tests 組驗證最後的 fact subject 要求與其服務整合。

## 測試命令

在 `backend` 目錄執行完整受影響 Java 組：

```powershell
.\mvnw.cmd "-Dtest=AgenticSqlStatementValidatorTests,AgenticSqlAnswerSupportVerifierTests,AgenticSqlOutputPolicyTests,AgenticSqlAnswerServiceTests,AgenticSqlExecutorIntegrationTests,AgenticSqlAuthorizationIntegrationTests,AgenticSqlDisabledIntegrationTests,DocumentIndexPersistenceServiceTests,DocumentRagIntegrationTests,SemanticRagIntegrationTests" test
```

最後 lineage 修正後的精準重跑：

```powershell
.\mvnw.cmd "-Dtest=AgenticSqlStatementValidatorTests,AgenticSqlAnswerSupportVerifierTests,AgenticSqlOutputPolicyTests,AgenticSqlAnswerServiceTests,AgenticSqlExecutorIntegrationTests" test
```

A/B runner：

```powershell
python -m unittest discover -s scripts/agentic-sql-benchmark/tests -v
```

舊 API 基線與修正分支均使用：

```powershell
.\mvnw.cmd "-Dtest=AiControllerIntegrationTests" test
```

基線測試使用 detached worktree `C:\Users\z0976\AppData\Local\Temp\supplypilot-agentic-sql-main-baseline-87a35b3`，位於 `main` 的 `87a35b31b12cd8a124b54ee14e93ba466fec8182`，未修改該 worktree。

## 舊 AI API 基線分診

`main` 與修正分支都得到相同 4 個失敗案例：未知物料預期 HTTP 404 但回 200、答案缺少 PO 編號、答案缺少 `ORDERED`、文件查詢回應缺少 `tools[0]`。PO fixture 使用隨機識別值，因此兩次日誌中的預期編號字串不同；失敗斷言相同。這些失敗在本次分支改動前已存在於 `main`，且修正分支沒有修改舊 AI／RAG 查詢與回答程式；Document RAG 8/8 與 Semantic RAG 22/22 通過。依本輪限縮範圍沒有擴大修舊模組。

第一次基線執行因沙箱無法連接 Docker named pipe 而未能啟動 Testcontainers，該環境錯誤保留於 [baseline-ai-controller-87a35b31b12cd8a124b54ee14e93ba466fec8182.log](baseline-ai-controller-87a35b31b12cd8a124b54ee14e93ba466fec8182.log)。之後在相同環境升權重跑完成，結果見表格中的 `docker-access` 日誌。

## 未執行與限制

- 真實 Gemini Provider：`NOT RUN`。
- 真實資料庫上的 A/B benchmark：`NOT RUN`；僅執行 runner 單元測試，沒有使用 `--live`。
- Browser E2E、AWS／部署驗收：`NOT RUN`。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`。
- Migration V34 將 `document_chunks.document_version` 加為可空欄位。既有 chunks 沒有可驗證版本時保持 `NULL` 並 fail closed，需由正常文件索引替換流程重新建立 provenance；不會以目前文件版本回填舊 chunks，避免替可能過期的原文製造可信版本。
- 答案支持度採 SQL projection lineage、同列主體／值、結構化來源欄位、數值、聚合、比較及常見業務定性主張檢查，不是任意自然語言的完整形式化證明。對未涵蓋的語意仍須在真實 Gemini A/B 實測中觀察誤放與誤拒。
- JOIN 中未限定且無法唯一追溯來源的欄位不作答案證據；Agent 可用 table alias 限定後重查。
- 開發期間的早期失敗日誌均保留，最終通過結果以表格中的兩份最終 Java 日誌為準。`lineage-verifier-initial.log`、`lineage-verifier-red-green-1.log`、`unit-lineage-verifier-output-policy*.log`、`answer-verifier-comparison-debug*.log`、`integration-agentic-and-rag.log`、`lineage-testcontainers-final.log` 及 `core-unit-final-*.log` 記錄修正過程中的初始失敗或診斷結果；不得當作最終通過證據。
