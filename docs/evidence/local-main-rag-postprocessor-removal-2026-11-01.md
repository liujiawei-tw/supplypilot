# 本機 main：RAG PostProcessor 移除驗證（2026-11-01）

## 程式狀態

- 程式版本：`204f93e`（本機 `main` 已快轉至此；未推送遠端）。
- `RagDocumentPostProcessor`、`RagEvidenceCollector` 與 `NoVerifiedRagEvidenceException` 已移除；主程式與測試沒有這些類別的引用。
- 文件 RAG 使用 Spring AI `ChatClient`、`RetrievalAugmentationAdvisor`、`VectorStoreDocumentRetriever`、PgVectorStore。Retriever 最多取 10 份，threshold 使用設定值（預設 0.4）；符合向量 metadata filter 的候選直接進模型 Context，由模型判斷相關性及答案支持度。
- `AiQuestionIntent` 以 Spring AI `ChatModel` 呼叫模型進行工具分類；JSON Prompt、結果解析與工具 allowlist wrapper 由應用程式維護。
- 直連 `/api/v1/document-queries` 沒有自製 RAG PostProcessor。`/api/v1/ai/query` 的 `AiAnswerEvidencePolicy`／`EvidenceValidator` 仍可能依來源格式與摘錄支持度拒絕 `SEARCH_DOCUMENTS` 結果；此為 Advisor 外部的另一層 API 結果政策。

## 本次必要驗證

| 範圍 | 命令 | 結果 |
|---|---|---|
| Advisor／服務精準測試 | PowerShell 從本機 Maven repository 解析 `byte-buddy-agent` JAR，設定 `-DargLine=-javaagent:$agentPath`，再執行下方測試選取器。 | **PASS**，10 tests、0 failures、0 errors、0 skipped。 |
| RAG Testcontainers 問答矩陣 | 同上，測試選取器為 `SemanticRagQaMatrixIntegrationTests`。 | **PASS**，42 tests、0 failures、0 errors、0 skipped；使用 MySQL／PostgreSQL Testcontainers 及 Fake Chat／Embedding。 |
| 格式檢查 | `git diff --check` | **PASS**，無 whitespace errors。 |

兩項 Maven 測試均在 `backend` 目錄執行，命令格式如下：

```powershell
$agentPath = Get-ChildItem "$env:USERPROFILE\.m2\repository\net\bytebuddy\byte-buddy-agent" -Recurse -Filter 'byte-buddy-agent-*.jar' | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
```

精準測試：

```powershell
.\mvnw.cmd -B -ntp "-DargLine=-javaagent:$agentPath" "-Dtest=SpringAiRagAdvisorPipelineTests,DocumentQueryServiceTests,SemanticConfigurationDataSourceIsolationTests" test
```

RAG 矩陣：

```powershell
.\mvnw.cmd -B -ntp "-DargLine=-javaagent:$agentPath" -Dtest=SemanticRagQaMatrixIntegrationTests test
```

完整 Maven `verify`、此最新程式版本的真實 Gemini E2E 與 Browser E2E 均未執行。這份記錄不代表最新程式已部署；正式環境與隔離本機 Demo 的既有執行紀錄見相鄰的 RAG 驗證與正式遷移報告。
