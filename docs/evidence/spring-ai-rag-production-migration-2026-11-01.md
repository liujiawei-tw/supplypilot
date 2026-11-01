# 正式環境 Spring AI RAG 遷移紀錄（2026-11-01）

## 部署與資料庫

- 目標：`54.175.145.174`，`/home/ubuntu/supplypilot`；依使用者指定視為正式環境。Compose 專案為 `supplypilot-sp903-local-demo`，本次只重建 Backend，沒有停止 MySQL、pgvector、Frontend 或刪除 volumes。
- 部署版本：`codex/spring-ai-rag-migration`，commit `2270e49b345945579de9ea477e6aca396e71a19e`；Backend image 建置成功並以 `supplypilot-sp903-local-demo-backend` 運行。
- MySQL Flyway 最新版本：V34（success）；無待套用的 MySQL migration。
- Semantic PostgreSQL Flyway：V1、V2、V3 均 success；V3 由管理員 readiness 呼叫套用。
- RAG 世代：`gemini-embedding-001-768-spring-ai-chunk500-overlap70-v2`，模型 `gemini-embedding-001`，維度 768。
- 回填逐筆執行：文件 ID 1 先完成 1 筆；之後游標處理 ID 2–121，共 120 筆。總計 `ready=121, failed=0, skipped=0, requiresReview=0, pending=0`。
- 恢復背景 worker 後，semantic index job 共 238 筆，全部 `SUCCEEDED`；沒有 queued、processing、retry 或 failed 工作。
- 最終 readiness：`passed=true`，`ready=121, failed=0, requiresReview=0, pending=0`。最終 gate audit：`auditId=6, passed=true`。
- 正式 `.env.demo` 最終設定：`SEMANTIC_READ_ENABLED=true`、`SEMANTIC_INDEX_WORKER_ENABLED=true`、`SEMANTIC_TOP_K=8`、`SEMANTIC_SIMILARITY_THRESHOLD=0.4`、chunk size/overlap `500/70`。檔案權限維持 600。
- 最終 Backend 與 MySQL、pgvector、Frontend 均 healthy；Backend restart count 為 0。
- 遷移前備份位於 `/var/backups/supplypilot/rag-migration-20261101T060802Z`；備份 hash 與 dump 結構驗證通過。完整 restore rehearsal 未完成：先前在該主機以臨時資料庫容器測試時觸發 OOM，故本次沒有重做。

## 正式 Gemini 文件問答

呼叫正式 `/api/v1/document-queries`，不是 Browser E2E。評估依答案語意與引用文件內容；不要求固定英文原句。

| 題目 | Gemini 答覆 | 結果 |
|---|---|---|
| 採購單進入 ORDERED 後，SUP-001 的 MAT-001 要等多久交貨？ | `5 個日曆日。` | **PASS**。HTTP 200 / `ANSWERED`；SUP-001 合約來源載明 ORDERED 起算 5 個日曆日。 |
| SUP-001 的服務時段是哪些日子、幾點到幾點？ | `服務日子：週一至週五；服務時間：09:00–16:30（虛構情境設定）` | **PASS**。HTTP 200 / `ANSWERED`；來源 `DOC-SUP-001-SUPPLIER-PROFILE-SP907`。 |
| SUP-001/MAT-001 承諾到貨日異動時，案例契約要求記錄哪些資訊？ | 首次答覆列出原日期、新日期、通知時間、收件角色；確認重問另加「異動／改期原因」。 | **PARTIAL，未列為通過**。HTTP 200 / `ANSWERED`，但引用結果沒有檢索到載有這四項條款的 `DOC-SUP-001-CONTRACT-TERMS-SP907`；反而引用 SUP-006 合約的同類條款，並將 SUP-001 案例紀錄中的原因補成契約要求。這題的答案與來源未完全對齊。 |

正式文件問答結果：**2 PASS、1 PARTIAL**。讀取閘門仍維持開啟；未把第三題列為通過。

## 本次驗證範圍

- `AiAnswerEvidencePolicyTests`：3 tests，0 failures，0 errors，0 skipped；Maven `BUILD SUCCESS`。
- 合併執行 AiAnswerEvidencePolicyTests,RagDocumentPostProcessorTests,DocumentQueryServiceTests 未完成：在 DocumentQueryServiceTests 遇 Mockito Windows 動態 attach pipe 錯誤後卡住，人工停止、exit 1；不列為 PASS。之後單獨重跑本次變更的 policy suite 並成功。
- Browser E2E：`NOT RUN`（依使用者指示不做）。
- 獨立審查：未執行（依使用者最新指示）。

