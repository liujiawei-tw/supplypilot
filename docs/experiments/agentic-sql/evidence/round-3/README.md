# Agentic SQL 第三輪修正：測試證據

## 證據範圍

- Repository：`liujiawei-tw/supplypilot`
- Branch：`feature/agentic-sql-answer-engine`
- GPT 審查基準 HEAD：`87b76cbca5c008e46b4e3453af7c563605195f96`
- 程式與測試 Commit：`bc5f3e46f68ccdaef37beec733b401a784cf9be7`
- 執行環境：Windows 11 amd64、Zulu Java 21.0.10、Maven Wrapper 3.9.16、Python 3.13.5、Docker Desktop 29.6.1、Testcontainers 2.0.5。
- 資料庫容器：MySQL 8.4.11、PostgreSQL 16-alpine（Agentic SQL executor）；RAG 整合測試另使用其測試設定的資料庫版本。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`，本輪未查詢、啟動、重跑或依賴 Actions。

## 最終驗證

| 範圍 | 結果 | Exit Code | 原始日誌 |
|---|---:|---:|---|
| Agentic SQL 單元、答案支持度、輸出政策、MySQL／PostgreSQL Testcontainers、授權、功能開關、Document RAG 與 Semantic RAG 精準回歸 | 123 tests；0 failures、0 errors、0 skipped | 0 | [final-agentic-sql-java-commit-rerun.log](final-agentic-sql-java-commit-rerun.log) |
| 雙資料庫 executor 負向及正常路徑（含文件 SQL 轉換、巢狀 EXISTS、原始文件遮罩） | 21 tests；0 failures、0 errors、0 skipped | 0 | [green-agentic-sql-exists-fix-docker-6.log](green-agentic-sql-exists-fix-docker-6.log) |
| A/B runner Python 單元測試 | 9 tests；全數通過 | 0 | [ab-runner-commit.log](ab-runner-commit.log) |

最終 Java 命令由 Repository 根目錄執行：

```powershell
.\backend\mvnw.cmd "-Dtest=AgenticSqlStatementValidatorTests,AgenticSqlAnswerSupportVerifierTests,AgenticSqlAnswerServiceTests,AgenticSqlOutputPolicyTests,AgenticSqlExecutorIntegrationTests,AgenticSqlAuthorizationIntegrationTests,AgenticSqlDisabledIntegrationTests,DocumentRagIntegrationTests,SemanticRagIntegrationTests" -f backend\pom.xml test
```

A/B runner 命令：

```powershell
python -m unittest discover -s scripts/agentic-sql-benchmark/tests -v
```

## 負向測試與修正過程證據

- [red-agentic-sql-tests.log](red-agentic-sql-tests.log)：修正前 RED 測試，60 tests、7 failures、0 errors、0 skipped，Exit Code 1。失敗重現了 SQL 常數／加工值及文件內容轉換等待修問題，不是最終結果。
- [final-agentic-sql-java-2.log](final-agentic-sql-java-2.log)：修正迭代中 123 tests、1 failure；失敗定位到 `EXISTS` 巢狀查詢的 parser visitor 漏徑。
- [green-agentic-sql-exists-fix-docker-2.log](green-agentic-sql-exists-fix-docker-2.log) 至 [green-agentic-sql-exists-fix-docker-5.log](green-agentic-sql-exists-fix-docker-5.log)：Docker 實際啟動後仍重現同一負向案例，保留作為診斷歷程。
- [green-agentic-sql-exists-fix.log](green-agentic-sql-exists-fix.log)：沙箱無法存取 Docker pipe，Testcontainers 21 tests 全部 skipped，雖 Maven exit 0 但不視為整合測試通過。
- 最終 [green-agentic-sql-exists-fix-docker-6.log](green-agentic-sql-exists-fix-docker-6.log) 及程式 Commit 後的 123 項套件日誌，均確認 MySQL/PostgreSQL 容器實際啟動，相關負向與正常文件路徑通過。

修正前負向案例包括 `COUNT(*) + 999`、`CONCAT(name, '已破產')`、`REVERSE(document.content)`、`SUBSTRING(document.content)`、以轉換後文件內容作列存在條件，以及 MySQL 與 PostgreSQL 的正常原文政策路徑。答案 verifier 不接受 `@transformed` 或 `@derived-aggregate` 當作業務事實；直接數值欄位運算使用明確 `DERIVED` 標記與運算式支持，保留合法計算及直接聚合。

## 舊 AI API 失敗分診

本輪依使用者指示不重跑舊 `AiControllerIntegrationTests`。`main` 與實驗分支相同四項失敗已在第二輪以隔離 `main` worktree 分診，結果、案例與原始日誌見 [第二輪證據](../round-2/README.md) 及其中的 baseline／branch logs。本輪只修改 Agentic SQL、新增測試及本實驗文件，未修改舊 AI／RAG 模組。

## 未執行與殘餘限制

- 真實 Gemini：`NOT RUN`；本輪 Fake ChatModel／單元測試不代表 Provider 驗收。
- 正式雙資料庫 A/B benchmark：`NOT RUN`；只執行 runner 單元測試，未使用 `--live`。
- Browser E2E、AWS、部署：`NOT RUN`。
- 遇到無法安全追蹤的文件原文轉換或巢狀內容條件時會 fail closed；可能拒絕部分涉及文件內容的複雜 SQL，Agent 可改寫成直接安全投影後由後端執行遮罩／版本驗證。
- 自然語言支持度檢查是確定性 SQL lineage、欄位、同列主體／值、業務數字與受限定性判斷，不構成通用形式化證明。真實 Gemini 的誤放／誤拒仍需後續 A/B 實測評估。
