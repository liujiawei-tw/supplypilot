# Agentic SQL 問答實驗設計

> 狀態：隔離 PoC／A-B 實驗；不是 V2 正式 RAG 規格變更，不取代既有 API，不授權部署或合併。  
> 實驗分支：`feature/agentic-sql-answer-engine`  
> 起始 HEAD：`87a35b31b12cd8a124b54ee14e93ba466fec8182`  
> 資料出站決策：2026-10-27，本次實驗可將與問題相關、且登入者有權存取的商業資料（包含價格、報價、PO 金額及業務文件）送 Gemini 分析；系統憑證不得送出，個資與銀行識別資訊須遮罩。

## 獨立執行路徑

- 新入口為 `POST /api/v1/ai/agent-query`，採版本化回應 DTO。功能開關 `AGENTIC_SQL_ENABLED` 預設 `false`；未明確啟用時不建立 Agent 專用資料庫連線或執行入口。
- Agent 使用獨立的 System Prompt、ChatClient、request context 與兩項泛用工具：`inspect_schema`、`execute_readonly_sql`。核心問答路徑不呼叫舊 `AiQueryPlanner`、固定業務工具、`DocumentQueryService`、Hybrid/Semantic Retrieval 或 `EvidenceValidator`。
- 舊 `POST /api/v1/ai/query` 與 `POST /api/v1/document-queries` 保持原狀，僅由 A/B runner 作為 baseline 呼叫。runner 收齊兩邊結果後才並排呈現，不會把任一方的答案或工具軌跡傳給另一方。
- 新入口沿用已登入的採購角色授權。SQL 工具本身不提供 HTTP、檔案、Shell 或其他網路能力；資料表文字與文件內容皆視為不可信資料。

## 資料來源與權威性

- MySQL 是採購、供應商、物料、庫存、PO、文件元資料及原始文件 chunks 的權威來源。
- PostgreSQL 目前保存 Semantic RAG 的衍生索引、向量與稽核資料，不是第二套採購主檔，也沒有跨資料庫 FK 或分散式交易。新 Agent 可依問題自行探索可存取的 PostgreSQL 資料；Semantic chunk 必須標記為可能缺失／過期的衍生資料，並保留 MySQL 原始文件的核對線索。
- 不假設跨引擎 JOIN。跨庫問題由模型分開查詢，再依結果中可驗證的鍵整合；沒有真實關聯鍵時不得捏造關係。
- SQL 結果來源由後端從實際資料庫、表、結果列及可證明的主鍵資訊生成。模型不能自行建立來源或引用。回應與工具軌跡不回傳資料庫密碼、原始 SQL 錯誤、stack trace 或敏感 SQL 條件。

## 唯讀與資料保護

- MySQL 與 PostgreSQL 使用兩組 Agent 專用連線帳號，不重用主應用程式或 SemanticStore 的帳密。連線設唯讀，statement 設時間上限，另以 SQL AST 拒絕非 SELECT／唯讀 CTE、多重語句、鎖定、寫檔與非安全函式。資料庫端最小權限 `SELECT` grant 是必要的第二道保護；應用程式不建立 DB user 或修改 grant。
- Schema Discovery 隨設定的資料庫 schema metadata 動態列出業務表與欄，不依賴每種問題一支固定業務工具。系統 schema、認證／安全內部資料及憑證欄位不得提供給模型。
- Schema Discovery 以穩定排序和 1–100 筆有界頁面回傳；回應提供 `nextOffset`，並可用 schema 或精確表名縮小範圍。SQL 工具仍依同一 metadata policy 過濾 system schema、認證表及機密欄位。
- 本次實驗政策允許相關商業價格、成本、折扣、PO 金額與業務文件內容送 Gemini；此決策不改變舊 RAG 的 `OutboundDocumentPolicy`。密碼、雜湊、API key、token、private key 與認證資料會排除；個人身分識別碼、聯絡方式及銀行帳號會遮罩，不向模型提供原始值。
- 文件原文在傳入模型前經獨立的 `AgenticSqlDocumentEgressPolicy` 檢查：來源必須可追溯並投影直接 `document_id`；MySQL 文件 metadata 必須為 `READY` 且有目前版本，原始 chunks 只保留目前版本並與 READY 狀態同一交易替換；PostgreSQL `semantic_chunks` 還必須投影 `document_version` 並符合目前 MySQL 文件版本。無法確認時拒絕該工具結果並要求 Agent 改寫。凡 SQL 在投影或條件中引用文件 `content`，均執行來源檢查；每次出站也會掃描實際字串值並遮罩 SWIFT/BIC、銀行帳號、個資及系統機密；合法業務金額仍保留。此檢查不依賴舊 RAG 檢索、Embedding、向量發布或相似度。
- JDBC 欄位 metadata 無法安全追溯敏感來源時，含敏感欄位的 derived `FROM (SELECT ...)` 會以可修正的 `SQL_REJECTED` 拒絕；沒有敏感欄位的一般 derived query 正常執行，不會以全列遮罩冒充成功取得業務資料。可改用 CTE、直接 JOIN 或直接投影。
- 最終模型回覆採 Agentic SQL 專用的結構化 claims；每項事實引用本次 SQL 結果的 `toolCallId`、列與欄。後端核對引用、數值、業務識別值與 MIN/MAX 比較；SQL 聚合輸出列可直接作為可查證證據。未在來源值明示的風險、因果、適合度與合作建議會省略，除非是經驗證的比較語句；不存在的來源引用或不支持的事實會回 `PARTIAL`；重複業務識別值在不同表有矛盾數值時回傳衝突警示。這套驗證不使用舊 RAG `EvidenceValidator` 或語意相似度門檻。
- 雙資料庫狀態區分為：成功查詢皆無列且沒有工具失敗為 `NO_DATA`；至少一個資料來源成功、另一來源失敗為 `PARTIAL`；沒有任何 SQL 查詢成功且曾發生工具／資料庫錯誤為 `QUERY_FAILED`；沒有可判斷的查詢且沒有錯誤時才為 `NEEDS_CLARIFICATION`。
- 使用者已補充：目前專案不會有員工個資、供應商銀行帳號或身分證號。上述欄位與值的遮罩仍作為防禦性保護，避免未來資料來源或 schema 擴充時意外外送；一般業務資料表與欄位仍採動態探索。
- 新路徑只將回答所需的結果送模型，不預先把整庫 schema 或資料載入 prompt。工具呼叫、單次查詢時間、回傳列數、欄數、字元數和模型輸出都設上限。日誌只記工具類型、資料庫、表、耗時、列數、截斷、錯誤分類與可取得的 token 用量；不得記問題原文、SQL、結果內容或憑證。
- 真實 Gemini／正式資料環境的啟用須另行核對 Gemini 資料處理條款與部署設定。功能預設關閉；本次不部署、不改正式 RAG 開關，也不宣稱已完成真實 Provider 驗收。

## A/B 基準

- 至少 20 道固定題目涵蓋主檔與採購、跨表／跨庫、文件、無資料／歧義／注入／敏感資料／Provider 或資料庫故障。
- 每題定義舊系統 baseline route、預期證據和查核理由。採購問題使用舊 AI query；文件問題使用舊 document query；舊系統無法支持的跨領域問題標記 `UNSUPPORTED`。
- A/B 結果記錄原始回應、來源、每題正確性／完整度（0–5）、無根據主張、可查核性、延遲、工具呼叫與 token／成本（未知為 `N/A`）。只有完成真實 fixture run 才能填入執行值；未捕捉的資料庫 snapshot hash 保持 `NOT_CAPTURED`，不填虛構結果。

## 確認過的限制

- 目前 Repo 的 PostgreSQL 不是完整業務資料庫；供應商、庫存、報價及 PO 權威資料仍只有 MySQL。
- 本機 Agent 專用 DB user/grant 與 live Gemini credentials/呼叫結果，只有在實際建立並執行後才可標示為驗證完成。
- A/B 靜態題集及 fake ChatModel 測試不等於 Gemini、多資料庫服務或 Browser E2E 通過。GitHub Actions 依 Repository 指示為 `PAUSED BY USER / NOT USED AS GATE`。
