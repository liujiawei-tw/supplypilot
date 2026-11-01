# Spring AI 文件 RAG 遷移驗證記錄（2026-11-01）

本記錄對應 `codex/spring-ai-rag-migration`，基底 `main` 為 `1a76b62b08ccd1c50d3ca74e586528d7a7104321`。全量 `verify` 是較早工作樹狀態的結果，**未在最後產品程式修正後重跑**。答案驗證器依使用者指示移除；移除後必要 RAG 單元／Testcontainers 測試 150 項通過。另已在本機 Demo DB 套用 PostgreSQL V3、完成 121 份文件回填並通過 readiness／gate audit；真實 Demo 文件三題 Gemini API 問答在診斷模式下為 0/3，結果詳見下節。正式環境資料庫未操作。

本記錄不是 PR Final PASS、資料庫切換核准或正式啟用證明。GitHub Actions 依使用者決策為 `PAUSED BY USER / NOT USED AS GATE`。

## 執行環境與主要結果

| 項目 | 命令／範圍 | 結果 | 原始日誌 |
|---|---|---|---|
| Demo reset FK 回歸（最新修正） | `cd backend; .\mvnw.cmd -Dtest=DemoResetIntegrationTests test`；Windows、Java 21、Docker/Testcontainers 可用 | **PASS**，1 test、0 failures/errors/skipped、Exit Code 0；涵蓋存在 FK 衝突紀錄時先刪衝突再重設文件；302 秒 | `backend/target/surefire-reports/com.supplypilot.demo.DemoResetIntegrationTests.txt` |
| Backend 全量 Maven | `mvn -f backend/pom.xml "-DargLine=-javaagent:C:\Users\z0976\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar" verify`；Windows、Java 21、Docker/Testcontainers 可用 | **FAIL**，1,093 tests、4 failures、0 errors、2 skipped、Exit Code 1；27:46 | `%TEMP%\supplypilot-backend-verify-final-agent-docker-20261101.log` |
| Base AI controller 對照 | `main` 基底 `1a76b62` 上只執行 `AiControllerIntegrationTests` | **FAIL**，7 tests、4 failures、0 errors、0 skipped。三項與全量執行相同；read gate 測試是舊契約斷言 `$.tools[0]`，當時尚未更新 | `%TEMP%\supplypilot-ai-controller-baseline-21.log` |
| Read gate 關閉 API 契約 | `mvn -f backend/pom.xml "-DargLine=-javaagent:C:\Users\z0976\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar" "-Dtest=AiControllerIntegrationTests#disabledDocumentSearchReturnsProviderUnavailableAndValidatesRequest" test` | **PASS**，1 test、0 failures/errors/skipped、Exit Code 0；驗證工具錯誤以 HTTP 503 `AI_QUERY_FAILED` 回傳 | `%TEMP%\supplypilot-ai-disabled-search-contract-final-20261101.log` |
| Advisor 與 Demo reset 回歸 | `mvn -f backend/pom.xml "-DargLine=-javaagent:C:\Users\z0976\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar" "-Dtest=SpringAiRagAdvisorPipelineTests,DemoResetIntegrationTests" test` | **PASS**，5 tests、0 failures/errors/skipped、Exit Code 0；包含沒有 `demoDatasetKey` metadata 的向量也會在明確 Demo reset 中刪除 | `%TEMP%\supplypilot-rag-reset-targeted-docker-20261101.log` |
| 固定 RAG 問答整合矩陣 | `mvn -f backend/pom.xml "-DargLine=-javaagent:C:\Users\z0976\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar" "-Dtest=SemanticRagQaMatrixIntegrationTests" test` | **PASS**，38 tests、0 failures/errors/skipped、Exit Code 0；透過 Testcontainers MySQL／PostgreSQL/pgvector、實際索引 worker、PgVectorStore、Advisor 與 API；測試使用 Fake Chat／Embedding | `%TEMP%\supplypilot-rag-qa-matrix-no-supplier-date.log` |
| 答案驗證器移除前的歷史測試 | `mvnw.cmd "-Dtest=RagAnswerValidatorTests,EvidenceValidatorTests" -DargLine=-javaagent:C:/Users/z0976/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar test`（於 `backend` 執行） | **歷史 PASS**，106 tests、0 failures/errors/skipped；後續依使用者指示刪除 `RagAnswerValidator`，本列只保留當時結果，不代表目前仍有答案主張驗證 | `%TEMP%\supplypilot-rag-validator-final20-20261101.log` |
| 文件 RAG 精準回歸套件 | `mvnw.cmd "-Dtest=SemanticRagQaMatrixIntegrationTests,SpringAiRagQaFixtureTests,SpringAiRagAdvisorPipelineTests,DocumentRagIntegrationTests" -DargLine=-javaagent:C:/Users/z0976/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar test`（於 `backend` 執行；Testcontainers 連接本機 Docker） | **PASS**，102 tests、0 failures/errors/skipped、Exit Code 0：矩陣 42/42、fixtures 50/50、Advisor 4/4、Document RAG 6/6；含 API → Advisor → 真正 PgVectorStore／Testcontainers 路徑，模型仍為 Fake Chat／Embedding | `%TEMP%\supplypilot-rag-integration-final7-20261101.log` |
| 移除答案驗證器後的必要 RAG 測試 | `mvnw.cmd "-Dtest=SpringAiRagAdvisorPipelineTests,RagDocumentPostProcessorTests,EvidenceValidatorTests,SpringAiRagQaFixtureTests,SemanticRagQaMatrixIntegrationTests,DocumentRagIntegrationTests" -DargLine=-javaagent:C:/Users/z0976/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar test`（於 `backend` 執行；Testcontainers） | **PASS**，150 tests、0 failures/errors/skipped：Pipeline 4、post-processor 8、來源驗證器 41、fixture provenance 49、QA 矩陣 42、文件 API 6；包含模型答案原樣返回與來源證據仍必須存在 | `%TEMP%\supplypilot-rag-answer-validator-removed-targeted-20261101.log` |
| QA 矛盾來源發現紀錄（修正前） | `mvnw.cmd -Dtest=SemanticRagQaMatrixIntegrationTests -DargLine=-javaagent:C:/Users/z0976/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar test`（修正前工作樹；Testcontainers） | **FAIL**，42 tests、1 failure、0 errors/skipped、Exit Code 1；一般問答把同一供應商／物料兩個互斥交期值都回答。修正後同案納入 42/42 通過矩陣 | `%TEMP%\supplypilot-rag-qa-matrix-expanded-live-supplier.log` |
| 真實 Gemini provider smoke | `SemanticRealProviderSmokeTests`，使用主工作樹 `.env.demo` 提供的 Gemini 設定；只測合成輸入的 Embedding 與 Chat provider 呼叫 | **PASS**，2 tests、0 failures/errors/skipped、Exit Code 0 | `%TEMP%\supplypilot-gemini-provider-smoke-26.log` |
| 真實 Gemini 合成文件問答 E2E | `SpringAiRealDocumentRagE2ETests`；自 `.env.demo` 以程序環境載入 Gemini 設定；啟動隔離 Testcontainers，向文件索引 1 題：「SUP-001 的 MAT-001 標準交期為多久？」；答案須語意等同 7 個日曆日並附正確來源 | **不穩定／未 PASS**。一次回傳 `ANSWERED`：「SUP-001 的 MAT-001 標準交期為 7 個日曆日。」且來源摘錄含同一數值；測試當時因判定式不接受空格而失敗。修正判定式後重跑回傳 HTTP 200 `INSUFFICIENT_EVIDENCE`、`answer=null`、無來源。直接 PgVectorStore 命中與 post-processor 授權證據檢查通過；未能確認失敗重跑的原始 Gemini 回覆，API 會隱藏被拒答案 | `%TEMP%\supplypilot-real-rag-e2e-final4-20261101.log`、`%TEMP%\supplypilot-real-rag-e2e-final5-20261101.log` |
| 移除答案驗證器後的三題真實 Gemini 文件問答 | `SpringAiRealDocumentRagE2ETests`；由 `.env.demo` 載入 Gemini key、隔離 Testcontainers，索引合成已核准文件；兩題交期正向改述、一題文件未載單價 | **FAIL，1/3**。交期直接問法回 `ANSWERED`：「SUP-001 的 MAT-001 標準交期為 7 個日曆日。」且測試確認來源 ID／摘錄；ORDERED 後交期改述及單價題均回 `INSUFFICIENT_EVIDENCE`，無答案／來源。API 未記錄原始 Gemini 回覆，不能判定後兩題由模型拒答或更前段的證據流程拒答 | `%TEMP%\supplypilot-real-gemini-rag-3q-after-validator-removal-20261101.log`；Secret scan：`False` |
| Compose 啟動 smoke | 隔離 Compose project `codex-supplypilot-rag-smoke-20261101`；MySQL、pgvector、backend、frontend 均 healthy，backend `/actuator/health` 為 HTTP 200 UP；索引 worker 關閉 | **PASS（啟動／health only）**。沒有真實文件 RAG query；使用唯一命名 volumes，`down` 後保留 volumes | Compose 臨時 override：`%TEMP%\supplypilot-rag-smoke-compose.override.yaml`；未保存一份獨立完整 console log |
| 差異格式檢查 | `git diff --check` | **PASS**，Exit Code 0；Git 顯示既有 CRLF/LF 轉換提醒，不是 whitespace error | 執行於遷移工作樹 |

## 全量 Maven 失敗分診

三個 controller failure 在精確 `main` 基底的隔離測試也失敗，已確認是此遷移之前存在的基線問題：

1. `explicitUnknownMaterialCodeIsQueriedAndCannotBeInventedByTheModel`：預期 HTTP 404、得到 200。
2. `oneQuestionExecutesInventoryAndOpenPoWithBothSources`：AI 答案未包含動態建立的 PO 編號。
3. `queriesOnlyOrderedPurchaseOrdersAndBlocksPromptInjection`：AI 答案未包含 `ORDERED`。

另一項是舊測試契約：`preservesInsufficientDocumentEvidenceContractAndValidatesRequest` 在全量執行時仍預期 read gate 關閉後回 200。遷移現在明確讓 `SEMANTIC_READ_ENABLED=false` 表示服務不可用並回 503；測試預期隨後改為 503 `AI_QUERY_FAILED`，上述 targeted Testcontainers 測試已通過。全量 `verify` 沒有在這個 test-only 修正後重跑，因此整體 Maven 結果仍記為 **FAIL**，不可視為 Backend CI PASS。

## 已通過的遷移相關測試

以下測試在全量執行中通過，涵蓋目前程式碼的單元與 Testcontainers 路徑。完整單類別結果保存在全量 Maven log／Surefire reports：

| 類別 | 結果 |
|---|---:|
| `SemanticRagIntegrationTests` | 21/21 |
| `DocumentRagIntegrationTests` | 6/6 |
| `SpringAiPgVectorStoreIntegrationTests` | 2/2 |
| `SpringAiRagAdvisorPipelineTests` | 4/4；另有後續 targeted 與 Demo reset 合計 5/5 |
| `SpringAiRagQaFixtureTests` | 移除答案驗證器後 49/49；其中固定 positive／insufficient fixture 驗證檢索後 provenance，不等於 Advisor + PgVectorStore E2E 問答 |
| `SemanticRagQaMatrixIntegrationTests` | 42/42 API／Advisor／PgVectorStore Testcontainers invocations：13 synthetic positive、8 retrieved-but-unsupported、11 個經審核固定 fixture positives、1 個跨供應商付款條件拒答、2 個錯誤供應商／物料識別負向、1 個矛盾有效來源案例（答案依 Fake Chat 原樣回傳）、1 個成功交期比較、5 個版本／metadata／授權／Provider failure cases。包括 RAG-018 歷史／現行雙來源。Fake EmbeddingModel 使用固定向量，驗證實際向量表讀取、預期候選及即時授權，不代表真實 Gemini 的相關性排名品質。 |
| `SpringAiVectorIndexServiceTests` | 7/7 |
| `RagAnswerValidatorTests` | 65/65；答案驗證器移除前的歷史測試，現已刪除 |
| `EvidenceValidatorTests` | 41/41；涵蓋跨標點摘錄保留同一來源內明確的歷史／現行角色 |
| `RagDocumentPostProcessorTests` | 移除答案驗證器後 8/8 |
| `DemoResetIntegrationTests` | 1/1；另有 targeted regression 覆蓋未標記 demo metadata 的向量 |
| Agentic SQL 主要 validator／executor／authorization regression 類別 | 4、12、5、1、22、1、41、1、1 tests，各類別全數通過 |

`SemanticRagIntegrationTests` 以 Testcontainers 真正啟動 MySQL 及 PostgreSQL/pgvector，使用測試 Chat／Embedding 模型驗證 API、索引及檢索契約；Fake model 不代表 Gemini 驗收。保留 `semantic_chunks` 相容投影的 PgVectorStore integration test 與 Agentic SQL 回歸已通過。Agentic SQL **production source 未修改**。

## 本機 Demo 資料庫遷移、回填與三題真實文件問答

- Target 是 Compose project `supplypilot-sp903-local-demo` 的既有 Demo MySQL／PostgreSQL／文件 volumes；正式環境 `supplypilot-prod-*` volumes 未啟動、未修改。
- 遷移前備份：`%TEMP%\supplypilot-demo-rag-backup-9fec45e5a928447289d25cb221fe5d4e\supplypilot_demo.sql`（1,749,552 bytes，SHA-256 `82DA0E9DD1B7C7C6FFD7093BD598B05FEFCB848E8CBB9678E33BF339946E76F6`）；`supplypilot_semantic_demo.dump`（598,764 bytes，SHA-256 `FB63CE0A708B4532E0C5FCBE9157C03AFB458E90CC4C2E1562A668D331D7B3CA`）。
- 遷移前 MySQL schema v34、PostgreSQL semantic schema v2；MySQL 已是最新版本，PostgreSQL V3 `spring ai pgvector store` 成功套用。舊語意索引表保留。舊 generation 的 chunking version 是 `sp710-v1`，與新版本不同；以新 generation key `gemini-embedding-001-768-spring-ai-v1` 建立隔離索引，並將本機 `.env.demo` 的 generation key 同步更新（原檔備份在同一目錄 `.env.demo.pre-generation-update`）。
- 回填：API 分頁回傳 100/100、21/21 ready，兩頁合計 121 ready、0 failed、0 skipped、0 requiresReview。新 `semantic_rag_vectors` 有 164 個 vectors；readiness 為 121 ready、0 pending；Gate Audit `auditId=5` 通過。
- 三題均依 `sp-907-zh-tw-v3` 文件原文明載事實撰寫。文件標示為 SupplyPilot 虛構 Demo 展示資料。為符合使用者指示，本次僅診斷性暫停空檢索／答案 marker 的短路轉換以觀察 Gemini 原始回覆；來源驗證仍保留。測試後已還原此暫時程式變更。

| 問題 | 文件明載答案 | Gemini API 診斷結果 | 判定 |
|---|---|---|---|
| 採購單進入 ORDERED 後，SUP-001 的 MAT-001 要等多久交貨？ | 5 個日曆日，從正式 PO 進入 ORDERED 起算（SUP-001 合約案例文件） | `INSUFFICIENT_EVIDENCE`，sources 為空 | FAIL；未檢索到文件來源 |
| SUP-001 的服務時段是哪些日子、幾點到幾點？ | 週一至週五 09:00–16:30（SUP-001 供應商基本資料案例文件） | `INSUFFICIENT_EVIDENCE`，sources 為空 | FAIL；未檢索到文件來源 |
| SUP-001/MAT-001 承諾到貨日異動時，案例契約要求記錄哪些資訊？ | 原日期、新日期、通知時間與收件角色 | `INSUFFICIENT_EVIDENCE`；回傳 3 個來源，但為交期協調案例、舊版合約及採購端評語，未提供所問完整欄位 | FAIL；來源排序／生成未支持答案 |

三題單一批次的完整 API 結果已記錄於 [`local-demo-rag-3q-2026-11-01.json`](local-demo-rag-3q-2026-11-01.json)。無來源的 Gemini marker 在診斷模式呈現為 `ANSWERED` 文字；**不可視為成功答案**。本次不是正式環境驗收，也沒有執行 Browser E2E。

### 向量候選與來源支持檢查追查

- 原問題「採購單進入 ORDERED 後，SUP-001 的 MAT-001 要等多久交貨？」的 trace 顯示 VectorStore 回傳 50 個候選，來源 post-processor 最後接受 0 個。剔除原因：41 個 `NO_SUPPORTED_EXCERPT`、5 個 `DEMO_FACT_NOT_APPLICABLE`、4 個 `LIVE_SOURCE_NOT_VERIFIED`（索引 generation 已非現行）。因此這次不是「向量完全沒命中」；主要原因是確定性答案支持摘錄檢查在 Gemini 收到 Context 前剔除了候選。
- 已移除該答案支持摘錄檢查。即時 READY／核准狀態、版本與來源 hash、索引 generation、出站政策、安全 chunk 對照及 Demo 資格檢查仍保留。
- 修改後必要測試：`mvnw.cmd '-Dtest=RagDocumentPostProcessorTests' '-DargLine=-javaagent:C:/Users/z0976/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar' test`，**PASS，9 tests、0 failures/errors/skipped，Exit Code 0**。
- 修改後以真實 Demo API／Gemini 重送同一問題：回 `ANSWERED`、5 個 sources；首個 SUP-001 合約來源原文包含「採購單進入正式狀態 ORDERED 後，標準交期為 5 個日曆日」。Gemini 回答只有「採購單進入 ORDERED 狀態後，SUP-001」，內容不完整，故此追查**證明 Context 已送達，但問答仍未通過**。結果記錄於 [`local-demo-rag-postprocessor-fix-2026-11-01.json`](local-demo-rag-postprocessor-fix-2026-11-01.json)。

### 調整 Gemini 輸出設定後的三題文件問答

- 文件 RAG Chat 設定改為 `temperature=0.4`，移除應用程式層級 `max-output-tokens=512`；RAG 使用 Gemini Provider／模型預設上限。其他明確使用 `AiProperties` 的 AI 呼叫維持 `AI_MAX_OUTPUT_TOKENS=512`。
- 以同一批 Demo 文件問題走真實 Gemini API：交期題回答「ORDERED 後 5 個日曆日」，服務時段題回答「週一至週五 09:00–16:30」，兩題都帶回對應來源。第三題回 `INSUFFICIENT_EVIDENCE` 且來源為空；該答案確實存在於 corpus 的 SUP-001 情境條款檔，但本次沒有留下檢索候選 trace，無法判斷是向量未命中或後處理剔除。
- 結果 **2/3 有來源且正確**，不是完整 RAG 問答 PASS；Browser E2E 未執行。完整回應摘要見 [`local-demo-rag-output-settings-3q-2026-11-01.json`](local-demo-rag-output-settings-3q-2026-11-01.json)。

QA 矩陣的中間版本曾遇到無法存取 Docker pipe 而被 Testcontainers skip、粗略 PII／答案斷言錯誤、以及整份合約文件按出站政策正確進入 `REQUIRES_REVIEW`。這些原始失敗 log 均保留：`%TEMP%\supplypilot-rag-qa-matrix-2.log`、`%TEMP%\supplypilot-rag-qa-matrix-docker.log`、`%TEMP%\supplypilot-rag-qa-matrix-expanded.log`、`%TEMP%\supplypilot-rag-qa-matrix-curated.log`、`%TEMP%\supplypilot-rag-qa-matrix-verified.log`、`%TEMP%\supplypilot-rag-qa-matrix-date-context.log`、`%TEMP%\supplypilot-rag-qa-matrix-expanded-live-supplier.log`。答案驗證器依使用者指示移除；來源出站政策與即時核准檢查保留。移除後最新 42/42 矩陣及 150/150 精準 RAG 測試結果見上表。

## 尚未驗證／不可宣稱通過

- 本機 Demo 的 PostgreSQL migration、Backfill、文件索引 readiness／Gate Audit 已驗證；正式環境 DB 未遷移或回填。
- 原本三題真實文件問答 **0/3 FAIL**；移除答案支持檢查後，單題 Context 測試收到了正確合約來源但答案只有半句；調整 Chat 輸出設定後三題有 2 題回答正確並附來源，另 1 題無來源，仍未通過完整問答驗收。
- Browser E2E 依使用者指示暫不執行；真實文件測試使用 API，不是 Browser E2E。
- 完整 Backend `verify`：目前為上述 **FAIL**；Frontend 全量 Vitest／Lint／Build 不屬本次 Backend 改動證據。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`。

`SEMANTIC_READ_ENABLED` 預設仍為 `false`。這些限制需要在實際資料與受控環境完成 Runbook 驗收後才能解除。

### 2026-11-01 後續 Demo 檢索設定三題真實 Gemini 問答

- 設定：`SEMANTIC_SIMILARITY_THRESHOLD=0.4`、`SEMANTIC_TOP_K=8`、原始候選 80、chunk `500/70`、temperature `0.4`；新世代 `gemini-embedding-001-768-spring-ai-chunk500-overlap70-v2`。
- Demo readiness audit **PASS**：121 READY、0 pending、0 failed、0 requires-review（audit ID 6）。
- 以真實 Demo API／Gemini 詢問交期、SUP-001 服務時段、SUP-001/MAT-001 到貨日異動紀錄三題，答案均有來源並符合文件事實，**3/3 PASS**。第 2 題因終端輸出截斷補查一次；Browser E2E 仍依使用者決定為 **NOT RUN**。
- 詳細問題、答案與設定見 [`local-demo-rag-new-retrieval-settings-3q-2026-11-01.json`](local-demo-rag-new-retrieval-settings-3q-2026-11-01.json)。
