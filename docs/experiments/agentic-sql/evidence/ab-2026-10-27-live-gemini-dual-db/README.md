# 真實 Gemini／雙資料庫 A/B 驗收 — Smoke Gate BLOCKED

- Repository：`liujiawei-tw/supplypilot`
- Branch：`feature/agentic-sql-answer-engine`
- 測試基準 SHA：`e5e841e7b18a2cea625f79b6c8479fc67ad57897`
- 遠端分支在開始前回報相同 SHA；本次沒有修改產品程式或 `.env.demo`。
- 最終狀態：`BLOCKED_AT_SMOKE`。依驗收指示，20 題 A/B **NOT RUN**。
- GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`；未查詢、操作或依賴。

## 環境與資料

- Windows PowerShell；Docker Desktop 29.6.1。
- Docker Compose：`supplypilot-sp903-local-demo` 的 MySQL、PostgreSQL、Backend、Frontend 均 `healthy`；Frontend 回 `http://127.0.0.1:8081` HTTP 200。
- 開發 Compose 為空，未啟動或使用開發資料庫。DEMO MySQL 實際發布在 `127.0.0.1:3307`（不是歷史開發埠 `13316`）；PostgreSQL 使用 Compose 內部 `pgvector:5432`，未發布主機埠。
- MySQL `8.4.11`；PostgreSQL `17.11`。DEMO `demo_dataset_state` 為 `SP-901 / sp-907-zh-tw-v3 / COMPLETE`。
- MySQL 實際筆數：suppliers 10、materials 12、supplier_materials 45、purchase_orders 14、document_metadata 121、document_chunks 135；PostgreSQL semantic_chunks 135。
- Gemini：`AI_CHAT_PROVIDER=google-genai`，`GEMINI_MODEL=gemini-3.6-flash`，DEMO Backend 中 API key 非空且實際呼叫成功。舊 RAG 與 Agentic SQL 使用同一個 Spring `ChatModel` 及其預設模型選項；本次沒有另設 temperature，故具體 temperature 值未捕捉。
- Agentic SQL 在目前 DEMO Backend 已以臨時 Compose override 開啟。MySQL／PostgreSQL 各建立一個隨機密碼的專用 Demo 唯讀帳號；密碼沒有寫入 Repository 或 `.env.demo`。MySQL 權限為 `SELECT ON supplypilot_demo.*`；PostgreSQL role 非 superuser／非 create-role／非 create-db，具 CONNECT、public USAGE、既有 public 表 SELECT 及未來 public 表預設 SELECT 權限，無 INSERT／UPDATE／DELETE。Backend 的兩個 Agent pool 設為 read-only。
- DB 權限設定只變更 DEMO 帳號／grant metadata，未寫入或刪除業務資料；帳號憑證僅留在目前 Docker Backend 設定中。臨時 override 檔已刪除；之後不帶 override 重建 Backend 會恢復 `AGENTIC_SQL_ENABLED=false`。

## Smoke 結果

問題：`SUP-001 供應 MAT-001 的目前單價與幣別為何？`

| 項目 | 舊 RAG | Agentic SQL |
|---|---|---|
| API | `POST /api/v1/ai/query` | `POST /api/v1/ai/agent-query` |
| HTTP／狀態 | HTTP 200；`ANSWERED` | HTTP 200；`PARTIAL` |
| 實際答案 | 回傳 MAT-001 的 5 家供應商；其中 SUP-001 為 100 TWD/KG，另包含 SUP-002／003／005／010 | `查詢結果未能支持可核對的結論，因此沒有採用模型生成的事實內容。部分缺乏 SQL 結果支持的結論已省略。` |
| 資料來源／工具 | `QUERY_SUPPLIER_QUOTES`；5 筆 `SUPPLIER_MATERIAL` | 5 次工具呼叫：4 次 MySQL schema discovery，1 次 MySQL `EXECUTE_READONLY_SQL`，回傳 1 列；來源指向 `supplier_materials` row id 1 |
| Gemini／用量 | 真實 Gemini 回應；Legacy response 未提供 usage 欄位 | 真實 Gemini 回應；prompt 15,644、completion 348、total 18,421 tokens；費用 `N/A` |
| 實測耗時 | 6,274.54 ms | 19,951.70 ms |
| 判定 | 有回答正確的 SUP-001 值，但對單一供應商問題多回 4 家，範圍不精確 | MySQL 探索及 SQL 查詢成功，但答案支持驗證回報 `UNSUPPORTED_MODEL_CLAIMS`、`INVALID_MODEL_CLAIMS` 並降為 `PARTIAL`；未形成可用答案 |

獨立唯讀 MySQL ground-truth 查詢確認：`SUP-001 / MAT-001 = 100.0000 TWD、ACTIVE、lead_time_days=5`。本次 Agent smoke 只需要 MySQL，因此 Agent 未使用 PostgreSQL；PostgreSQL 的服務、schema、語意 chunk 資料及只讀權限已直接核對，但 **Agent PostgreSQL tool path 尚未驗證**。

此結果不列入 20 題 benchmark，也不計勝負／正確率。Smoke Gate 失敗原因不是 Gemini key、配額或 MySQL 連線：Agent 確實呼叫 Gemini（有累計 usage），完成 MySQL schema discovery 和唯讀 SQL，最終生成的結論未通過 Agentic SQL 支持度驗證而被拒絕。依流程在此停止，未開始其餘題目或注入 PostgreSQL 故障。

### SQL 可追溯性限制

Agent API 回傳工具種類、資料庫、表、列數、來源列與 toolCallId，但不回傳實際 SQL 文本或結果 cell。故本報告不臆造 Agent SQL；上述 ground-truth SQL 是獨立核對，不代表 Agent 實際 SQL：

```sql
SELECT s.code, s.name, m.code, m.name, sm.unit_price, sm.currency, sm.status, sm.lead_time_days
FROM supplier_materials sm
JOIN suppliers s ON s.id = sm.supplier_id
JOIN materials m ON m.id = sm.material_id
WHERE s.code = 'SUP-001' AND m.code = 'MAT-001';
```

## 執行命令與結果

- 遠端 HEAD：`git ls-remote origin refs/heads/feature/agentic-sql-answer-engine` → `e5e841e7b18a2cea625f79b6c8479fc67ad57897`，Exit Code 0。
- Compose 狀態：`docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml ps -a` → 4/4 healthy；開發組合 `docker compose --env-file .env -f compose.dev.yaml ps -a` → 無服務。
- 真實 Smoke：先以 Demo `admin` 登入，分別 POST 同一問題到上述兩個 API；兩個 API 均 HTTP 200，PowerShell 命令 Exit Code 0。Smoke 驗收結果仍為 **FAIL/BLOCKED**，因 Agent 回傳 `PARTIAL`。
- 初次嘗試使用 `buyer` 登入回 HTTP 401 `UNAUTHENTICATED`，未呼叫 Gemini；查到目前資料庫只有 `admin`、`test` 帳號後，改用 `admin` 成功。未讀取 password hash。
- A/B runner 指令 `python scripts/agentic-sql-benchmark/run_benchmark.py --live ...`：**NOT RUN**（Smoke Gate 未通過）。20 題結果與指標均 `NOT RUN`，沒有 Mock 結果冒充實測。

## 證據檔案與完整性

- [smoke-results.json](smoke-results.json)：兩個 API 的完整回應、來源／工具摘要、token usage 與耗時；不含 Bearer token 或 API key。
- 本 README 記錄資料庫版本、基準資料筆數、Smoke 結果及停止原因。
- 初始 `.env.demo` 沒有 Agentic SQL 開關或專用 DB 帳號設定；本次使用臨時 Compose override，不修改使用者 `.env.demo`。Gemini API key 和隨機 DB 密碼沒有寫入證據。
- DB snapshot SHA-256：`NOT_CAPTURED`；20 題未執行，沒有建立可重現 benchmark snapshot。`
- 20 題正確率、完整率、幻覺率、平均耗時、RAG／Agentic 勝負：`NOT RUN`。
- 由於 Smoke Gate 未通過，不建議據此判斷新舊架構整體優劣。進入 20 題前需先由 GPT Review 確認 Agentic 答案支持驗證與真實 Gemini 輸出的相容問題，並重新通過同題 Smoke；本輪未修改產品程式。
- 本次只提交測試證據文件至指定實驗分支；沒有建立 PR、Merge、部署或使用 GitHub Actions。