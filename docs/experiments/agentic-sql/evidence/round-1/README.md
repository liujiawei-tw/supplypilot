# Agentic SQL 第一輪修正：測試證據

## 證據綁定

- Repository：`liujiawei-tw/supplypilot`
- Branch：`feature/agentic-sql-answer-engine`
- 程式與測試 Commit：`b93d9e9a3ca84840755a671c6c6ae2b7c8c83c75`
- 測試環境：Windows 11 amd64、Java 21.0.10 (Zulu)、Maven 3.9.16、Python 3.13.5、Docker Desktop 29.6.1、Testcontainers 2.0.5
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`

## 精準測試結果

| 命令／範圍 | 結果 | Exit Code | 原始日誌 |
|---|---:|---:|---|
| Agentic SQL Unit、MySQL/PostgreSQL Testcontainers、API 授權、預設關閉、Document RAG 與 Semantic RAG | 106 Tests；0 Failures、0 Errors、0 Skipped | 0 | [java-focused-b93d9e9a3ca84840755a671c6c6ae2b7c8c83c75.log](java-focused-b93d9e9a3ca84840755a671c6c6ae2b7c8c83c75.log) |
| A/B runner Python unittest | 9 Tests；全數通過 | 0 | [ab-runner-b93d9e9a3ca84840755a671c6c6ae2b7c8c83c75.log](ab-runner-b93d9e9a3ca84840755a671c6c6ae2b7c8c83c75.log) |
| 舊 `/api/v1/ai/query` `AiControllerIntegrationTests` | 7 Tests；4 Failures、0 Errors、0 Skipped | 1 | [legacy-ai-api-b93d9e9a3ca84840755a671c6c6ae2b7c8c83c75.log](legacy-ai-api-b93d9e9a3ca84840755a671c6c6ae2b7c8c83c75.log) |

主要 Java 套件通過的類別明細：

- `AgenticSqlOutputPolicyTests`：10/10
- `AgenticSqlAnswerServiceTests`：4/4
- `AgenticSqlAnswerSupportVerifierTests`：7/7
- `AgenticSqlExecutorIntegrationTests`：15/15（MySQL 8.4.11、PostgreSQL 16；含敏感資料輸出、文件狀態／版本、MySQL 與 PostgreSQL 分頁、唯讀交易）
- `AgenticSqlStatementValidatorTests`：38/38
- `AgenticSqlAuthorizationIntegrationTests`：1/1
- `AgenticSqlDisabledIntegrationTests`：1/1
- `DocumentRagIntegrationTests`：8/8
- `SemanticRagIntegrationTests`：22/22

舊 AI API 的四個失敗為：未知物料預期 404 卻回 200、回答未包含 PO 編號、回答未包含 `ORDERED`、文件查詢回應缺少 `tools[0]`。本次 Commit 未修改舊 AI/RAG 程式碼；這些測試仍在精確 Commit 上失敗，因此本證據不宣稱舊 AI API 回歸通過。依使用者本輪限縮範圍，未擴大修改舊模組。

## 執行命令

在 `backend` 目錄執行 Java 精準套件：

```powershell
.\mvnw.cmd "-Dtest=AgenticSqlOutputPolicyTests,AgenticSqlAnswerSupportVerifierTests,AgenticSqlStatementValidatorTests,AgenticSqlAnswerServiceTests,AgenticSqlExecutorIntegrationTests,AgenticSqlAuthorizationIntegrationTests,AgenticSqlDisabledIntegrationTests,DocumentRagIntegrationTests,SemanticRagIntegrationTests" test
```

在 Repository 根目錄執行 A/B runner 單元測試：

```powershell
python -m unittest discover -s scripts/agentic-sql-benchmark/tests -v
```

舊 AI API 精準回歸測試：

```powershell
.\mvnw.cmd "-Dtest=AiControllerIntegrationTests" test
```

## 未執行與限制

- 真實 Gemini Provider：`NOT RUN`。
- 真實 MySQL／PostgreSQL 服務上的 A/B benchmark：`NOT RUN`；只執行 runner 單元測試，未使用 `--live`。
- Browser E2E、AWS／部署驗收：`NOT RUN`。
- `AiControllerIntegrationTests` 的 4 個失敗尚未診斷或修復；沒有因本輪修改舊 AI/RAG 模組。
- Claims 驗證使用實際工具結果引用、數字／業務識別值、聚合結果、MIN/MAX 與常見風險／建議／因果詞彙檢查；它不是任意自然語言的形式化語意證明，需在真實 Gemini A/B 實測中繼續評估誤放與誤拒。
- GitHub Actions 依使用者決策維持 `PAUSED BY USER / NOT USED AS GATE`；未查詢或重跑 Actions。

## 其他原始日誌

以下為實作期間的中間測試記錄，不取代上述綁定 `b93d9e9a3ca84840755a671c6c6ae2b7c8c83c75` 的最終精準結果：

- [combined-legacy-rag-regression-attempt.log](combined-legacy-rag-regression-attempt.log)：初次合併執行的 88 個測試，因同一舊 AI API 測試類別出現 4 個失敗而 Exit Code 1。
- [java-focused-before-final-hardening.log](java-focused-before-final-hardening.log)：完成末段答案詞彙與 MySQL 分頁補強前的 105 個測試記錄。
- [ab-runner.log](ab-runner.log)：Commit 前 A/B runner 9 個單元測試記錄。
