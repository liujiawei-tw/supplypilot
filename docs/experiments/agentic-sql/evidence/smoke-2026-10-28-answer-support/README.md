# Agentic SQL 真實 Gemini Smoke 修正證據

## 範圍與基準

- Repository：`liujiawei-tw/supplypilot`
- 分支：`feature/agentic-sql-answer-engine`
- 修正前遠端 HEAD：`22d4daacacbfeb49c76cb8d6c7d14673411f651a`
- DEMO Dataset：`SP-901 / sp-907-zh-tw-v3`
- Gemini：`gemini-3.6-flash`，由正在運作的 DEMO Backend 真實呼叫；回應包含 Gemini token usage。
- MySQL：`8.4.11`。Smoke 所需資料只在 MySQL；本題未呼叫 PostgreSQL。
- 查詢唯讀，使用專用 Agent DB 身分；Demo `admin` JWT 僅在記憶體使用，沒有寫入證據。
- `.env.demo` 未修改；證據不含 Gemini API Key、JWT、資料庫密碼或原始模型輸出。

## 根因診斷

真實 Gemini 輸出先後發生 `INVALID_MODEL_OUTPUT_JSON`；在 JSON MIME/schema 模式下，其中一筆被 Jackson 分類為 `MODEL_OUTPUT_JSON_TRUNCATED`。失敗發生在 JSON parsing 階段，該輸出沒有進入 claims 結構或 evidence reference 核對，故不是 SQL 工具或 evidence cell 引用錯誤。另一次原始模式呼叫有兩次 SQL 嘗試被安全驗證器拒絕，之後仍成功查詢 1 列。

失敗輸出的模型原文沒有保存。分類碼確認是 JSON EOF／截斷，但該次沒有可用的 Provider finish reason，因此不能判定是模型主動停止或傳輸截斷。Agentic SQL 設定的輸出上限為 2048；Demo 容器未覆寫此設定，失敗回應 completion usage 為 529–794 tokens，現有證據不支持將原因歸因於 token 上限。

## 最小修正

- `AgenticSqlAnswerService` 在 Google GenAI 上保留已設定模型與生成參數，加入 `application/json` 及 claims/evidence response schema；僅約束最終答案格式，不限制業務資料表、欄位或 SQL。其他 Provider 選項不變。
- `AgenticSqlAnswerSupportVerifier` 將 JSON parsing、claims 結構、claim 結構、evidence reference、事實不符、比較、支持度與 verifier 例外分類為固定代碼。診斷不回傳模型原文或例外訊息。
- `AgenticSqlAnswerSupportVerifier` 的證據檢查、`ANSWERED` 門檻與拒絕不支持結論的行為沒有放寬。
- Schema 模式能產生正確答案，但同一模式仍有一次 JSON 截斷並安全回 `PARTIAL`。後續同題真實 Gemini 呼叫通過支持度驗證；這證明 Smoke 有成功案例，尚不足以證明模型格式輸出沒有偶發失敗。失敗時仍 fail closed，不能被標成 `ANSWERED`。
- Google GenAI structured output 與 Gemini 3 function calling 的支援見 [Google Gemini Structured Outputs](https://ai.google.dev/gemini-api/docs/structured-output?hl=en)；Spring AI 的 Google GenAI runtime options 見 [Spring AI Google GenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html)。

## 真實 Gemini Smoke

同一問題：`SUP-001 供應 MAT-001 的目前單價與幣別為何？`

| 呼叫 | JSON mode/schema | 狀態／支持度 | API latency | completion tokens | 工具結果 |
|---|---|---|---:|---:|---|
| [gemini-smoke-diagnostic-first.json](gemini-smoke-diagnostic-first.json) | 否 | `PARTIAL`／`FAIL`：`INVALID_MODEL_OUTPUT_JSON`；另有兩次 `SQL_REJECTED` 後成功 | 25,401 ms | 1,159 | MySQL 1 列成功 |
| [gemini-smoke-final.json](gemini-smoke-final.json) | 是 | `PARTIAL`／`FAIL`：`INVALID_MODEL_OUTPUT_JSON` | 20,066 ms | 529 | Schema discovery 與 MySQL 1 列成功 |
| [gemini-smoke-structured-format-diagnostic.json](gemini-smoke-structured-format-diagnostic.json) | 是 | `PARTIAL`／`FAIL`：`MODEL_OUTPUT_JSON_TRUNCATED` | 20,574 ms | 794 | MySQL 1 列成功 |
| [gemini-smoke-finishreason-diagnostic.json](gemini-smoke-finishreason-diagnostic.json) | 是 | `ANSWERED`／`PASS`，無 warnings | 25,306 ms | 834 | MySQL Schema discovery 與唯讀 SQL 成功 |

Smoke 通過案例的答案為 SUP-001 供應 MAT-001 目前單價 `100.0000`、幣別 `TWD`；使用 MYSQL，查詢涉及 `supplier_materials`、`suppliers`、`materials`，查得 1 列。獨立唯讀 DEMO MySQL 查核結果見 [database-ground-truth.log](database-ground-truth.log)。真實 Gemini 回傳 prompt／completion／total tokens 為 21,070／834／24,229，費用資料不可得（`N/A`）。使用者端耗時 25,460.96 ms。API 回應沒有持久化原始 evidence cell。

Smoke 登入從 `.env.demo` 讀取 Demo 密碼，以記憶體 JWT 呼叫 `/api/v1/ai/agent-query`；HTTP 200、PowerShell Exit Code 0。JWT 與密碼未保存，摘要見 [gemini-smoke-command.log](gemini-smoke-command.log)。

## 測試與建置

環境：OpenJDK 21.0.10 (Zulu)、Repository Maven Wrapper、Windows PowerShell 7.6.6。所有完整命令、Maven 原始輸出及退出碼保存在此目錄。

| 命令 | Tests | Failures | Errors | Skipped | Exit Code | 證據 |
|---|---:|---:|---:|---:|---:|---|
| `.\mvnw.cmd '-Dtest=AgenticSqlAnswerSupportVerifierTests' test` | 23 | 0 | 0 | 0 | 0 | [verifier-tests-final.log](verifier-tests-final.log) |
| `.\mvnw.cmd '-Dtest=AgenticSqlAnswerServiceTests' test` | 5 | 0 | 0 | 0 | 0 | [service-tests-final.log](service-tests-final.log) |
| `.\mvnw.cmd '-Dtest=AgenticSqlOutputPolicyTests,AgenticSqlStatementValidatorTests,AgenticSqlExecutorIntegrationTests,AgenticSqlAuthorizationIntegrationTests' test` | 76 | 0 | 0 | 0 | 0 | [security-integration-tests-final.log](security-integration-tests-final.log) |
| `.\mvnw.cmd -DskipTests package` | — | — | — | — | 0 | [package-final.log](package-final.log) |

共 104 個精準 Unit／Integration tests 通過。包含 JSON 格式錯誤分類、SQL 假證據與衍生聚合答案支持、文件出站保護、MySQL／PostgreSQL Testcontainers 唯讀執行與 API 授權回歸。

## 執行限制與未執行項目

- 真實 Gemini Smoke：同題至少一筆請求通過，正確回答 `100.0000 TWD` 且支持度 `PASS`；另有三筆因最終 JSON 格式失敗而安全回 `PARTIAL`。偶發格式問題是殘餘風險，不能宣稱已消除。
- Smoke 題目只需 MySQL，因此 PostgreSQL Agent 工具路徑未由這次 Smoke 覆蓋；Testcontainers 雙資料庫回歸已執行。
- 正式 20 題 A/B：`NOT RUN`，等待 GPT 對修正後精確遠端 HEAD 的獨立 Code Review。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`。
- Browser E2E、AWS：`NOT RUN`。
- 模型原始最終輸出未保存；失敗分類可確認 JSON parsing／EOF 截斷，但無法回溯錯誤字元或 Provider finish reason。
- 一項探索性 answer-repair 測試在 Surefire 開始測試類後沒有產生結果，已中止並撤回該測試，標記 `NOT COMPLETED`；不計入上述 104 項統計。中止記錄見 `answer-repair-regression-red-retry.log` 與 `answer-repair-regression-red-nofork.log`。
