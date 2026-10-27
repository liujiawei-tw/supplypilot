# Agentic SQL 聚合運算缺口：測試證據

## 證據範圍

- Repository：`liujiawei-tw/supplypilot`
- Branch：`feature/agentic-sql-answer-engine`
- 修正前 HEAD：`39a620e055c45fcdefa3830086492590920f8db0`
- 程式與測試 Commit：`c530f1851a9e3dbe60663a405a7da6c321e742a0`
- 執行環境：Windows 11 amd64、Zulu Java 21.0.10、Maven Wrapper 3.9.16、Docker Desktop 29.6.1、Testcontainers 2.0.5。
- 整合資料庫：MySQL 8.4.11、PostgreSQL 16-alpine。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`；未操作或依賴 Actions。

## 最終驗證

| 範圍 | 結果 | Exit Code | 原始日誌 |
|---|---:|---:|---|
| Lineage、答案支持度、AnswerService Prompt、輸出政策、MySQL／PostgreSQL Testcontainers executor 整合 | 96 tests；0 failures、0 errors、0 skipped | 0 | [final-aggregate-regression.log](final-aggregate-regression.log) |
| 其中 SQL executor 雙資料庫整合 | 22 tests；0 failures、0 errors、0 skipped | 0 | [final-aggregate-regression.log](final-aggregate-regression.log) |

最終命令由 Repository 根目錄執行：

```powershell
.\backend\mvnw.cmd "-Dtest=AgenticSqlStatementValidatorTests,AgenticSqlAnswerSupportVerifierTests,AgenticSqlAnswerServiceTests,AgenticSqlOutputPolicyTests,AgenticSqlExecutorIntegrationTests" -f backend\pom.xml test
```

最終日誌首行記錄程式 SHA `c530f185…`；MySQL 與 PostgreSQL Testcontainers 均實際啟動。測試明細為：AnswerSupportVerifier 17、Executor Integration 22、StatementValidator 41、AnswerService 4、OutputPolicy 12，總計 96。

## 本輪負向及正向涵蓋

- `SUM(unit_price + 999)` 與 `AVG(unit_price + 999)` 產生 `@derived-aggregate` lineage，不再產生直接 `@aggregate` marker。
- 使用實際 MySQL 結果驗證含加數的 SUM 與 AVG，若模型將結果標成原始 `SUM(column)`／`AVG(column)`，答案支持度驗證器拒絕。
- 只有 `DERIVED`、attribute 與 SQL 衍生聚合運算式完全一致、且文字明確說明「計算」時，才可引用衍生聚合結果。未分組 aggregate result 沒有實體主體時可省略 subject；分組衍生聚合必須引用同列 subject。
- 正常 `COUNT(*)`、`SUM(column)`、`AVG(column)` 仍保留直接聚合來源並可通過答案支持度驗證。
- 單元 lineage 測試分別用 MySQL 的 SUM、PostgreSQL 的 AVG 語法；Testcontainers executor 套件仍實際覆蓋兩種引擎。

## RED 與修正歷程

- [red-aggregate-lineage.log](red-aggregate-lineage.log)：基準 HEAD 上新增測試後，57 tests、2 failures、0 errors、0 skipped，Exit Code 1；重現 SUM/AVG 內的運算式被標為直接聚合，以及衍生 aggregate 無法作明確 DERIVED 計算的問題。
- [green-aggregate-lineage-unit-final.log](green-aggregate-lineage-unit-final.log)：58 tests、0 failures、0 errors、0 skipped，Exit Code 0，涵蓋 direct／derived／grouped-derived lineage 與答案支持度。
- [green-derived-aggregate-negative-test.log](green-derived-aggregate-negative-test.log)：新增實際 SQL 負向案例 1/1 通過，Exit Code 0。
- [green-aggregate-executor-testcontainers-final.log](green-aggregate-executor-testcontainers-final.log)：完整 executor 整合 22/22 通過，Exit Code 0。
- `green-aggregate-executor-testcontainers*.log` 保留早期測試中校正 fixture 數值與 JDBC 小數 scale 斷言的非零結果；最終以程式 Commit 後的 96 項日誌作準。

## 尚未執行

- 真實 Gemini：`NOT RUN`。
- 正式雙資料庫 A/B benchmark：`NOT RUN`；本輪只修正 lineage／答案支持度，沒有呼叫 provider 或執行 `--live`。
- Browser E2E、AWS、部署：`NOT RUN`。
- 未修改舊 RAG、舊 AI API、`main` 或 GitHub Actions。
