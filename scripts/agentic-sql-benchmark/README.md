# Agentic SQL A/B Benchmark

這是實驗用 CLI 與 20 題固定題集，對同一問題分別呼叫舊系統與新 Agent，待兩個呼叫都完成後才輸出比較。舊回答、來源、工具軌跡或摘要絕不會送入新 Agent。

## 狀態

**真實 Gemini／MySQL／PostgreSQL A/B benchmark：NOT RUN。** 目前只有版本化 Demo seed/corpus 中的題目依據；題集 `snapshot.hash` 均為 `null`、`snapshot.status` 為 `NOT_CAPTURED`。這些預期依據不是實際 API 回答、實際 DB 匯出或 benchmark PASS。不得將靜態題集、mock HTTP 測試或 fake provider 結果稱為真實 A/B 結果。

GitHub Actions 依專案決策標示 `PAUSED BY USER / NOT USED AS GATE`；此 runner 不查詢、觸發或依賴 Actions。

## 題集

預設檔案為 [`samples/agentic-sql/benchmark-cases.jsonl`](../../samples/agentic-sql/benchmark-cases.jsonl)，一行一題、共 20 題：

- `basic`：6 題，庫存、供應商、物料、供應條件、幣別／單位、PO 狀態。
- `cross_table_and_cross_database`：5 題，多條件、供應商比較、PO 價格快照、MySQL 原始來源與 PostgreSQL 衍生索引核對。
- `document`：4 題，文件來源、歷史與現行版本、資料不足及虛構案例界線。
- `unknown_ambiguous_security_failure`：5 題，查無資料、名稱歧義、缺少查詢範圍、提示注入／憑證要求，以及 PostgreSQL 故障下的 MySQL 路徑。

每題包含 `baselineRoute`、`expectedEvidence`、`expectedAnswerRationale`、dataset/version/hash 與明確的 snapshot 欄位。資料集依據是 `SP-901 / sp-907-zh-tw-v3` 的版本化主資料及 corpus catalog：

- `samples/demo-master-data.json` SHA-256：`122734DC4DD60D7FA6ED7B845C55D2EFE5C7AE2B2547E5AEE95DD3989C27A467`
- `samples/documents/corpus-catalog.json` SHA-256：`A831EB8B21771E39F525668972EE4526F124090AB1CCB416A551608DEB7DBDAD`
- `samples/documents/rag-fixtures.json` SHA-256：`270CE0F184B0A2ED3520BEB5D8BB73F4263C0D66D70DA3223EB752078A38D598`

沒有實際資料庫 snapshot 時不會推算或填造 hash。依據 seed 的數值只描述乾淨、固定版本的 Demo seed；live benchmark 應先準備相同的隔離 Demo DB snapshot，並在題目結果中記錄其 SHA-256。PostgreSQL `semantic_chunks` 是衍生索引；題集不假設索引已發布、完整或可用。

`baselineRoute` 使用以下值：

- `ai_query`：呼叫舊 `POST /api/v1/ai/query`，request 為 `{ "message": "..." }`。
- `document_query`：呼叫舊 `POST /api/v1/document-queries`，request 為 `{ "question": "..." }`。
- `UNSUPPORTED`：舊路徑不提供題目需要的一般跨庫比較；不呼叫 baseline，並記錄 `UNSUPPORTED`，不將其算成答錯。

新 Agent 一律呼叫 `POST /api/v1/ai/agent-query`，request 為 `{ "question": "..." }`。

## 單元測試

只使用 Python 標準函式庫與 mock HTTP response，不需要 Gemini、資料庫、登入 token 或網路：

```powershell
python -m unittest discover -s scripts/agentic-sql-benchmark/tests -v
```

## 執行真實 A/B

執行前，操作者需確認隔離 Demo runtime、兩個資料庫與登入授權，兩路使用相同 Gemini model／temperature，並把資料固定在相同 snapshot。PostgreSQL 故障案例須由隔離環境明確注入；runner 不會替使用者關閉服務或修改資料。預設只接受 loopback URL；非 loopback 目標必須使用 HTTPS 並加上 `--allow-remote-https`，因為 runner 會將 Bearer token 與業務問題送到指定主機。

PowerShell 範例（命令會產生外部 API 呼叫與本機結果檔）：

```powershell
$env:SUPPLYPILOT_BENCHMARK_TOKEN = '<有效的隔離 Demo Bearer token>'
python scripts/agentic-sql-benchmark/run_benchmark.py `
  --live `
  --base-url http://localhost:8080 `
  --model '<兩路共用模型名稱>' `
  --temperature '<兩路共用 temperature>' `
  --snapshot-hash '<64 位 SHA-256；由操作者對固定 snapshot 計算>' `
  --output artifacts/agentic-sql-ab-results.jsonl
```

`--live` 是必要的明確網路呼叫選項；不加此參數時 runner 不會連線。預設 loopback 以外的明文 HTTP 一律拒絕；非 loopback HTTPS 還需 `--allow-remote-https` 明確授權。token 只放在 Authorization header，不寫入結果檔或終端摘要。模型與 temperature 參數只是結果 metadata，不會覆寫伺服器設定；執行前需自行確認兩個 endpoint 的 provider 設定相同。若沒有可核對的 snapshot hash，省略 `--snapshot-hash`，結果會標示 `NOT_CAPTURED`，該 run 不可宣稱可重現。

每題先單獨呼叫 baseline（若支援），再單獨呼叫 Agent；即使 baseline HTTP 失敗也會繼續呼叫 Agent。兩邊完成後才寫入一行比較結果，並印出精簡並排摘要。結果 JSONL 保存各自的 raw／parsed response、sources、latency、tools/toolCalls、used databases、usage、可取得的 token/cost（API 未提供時為 `N/A`）、錯誤原因與空白人工評分欄位。runner 不保存授權 token。原始回答可能包含業務資料，請將輸出存放在適當保護的位置，不要提交 benchmark 結果或 DB 匯出檔。

## 評分

CLI 只收集證據，不自動判定回答正確，也不產生答案均值或 p95。每筆結果的 `review` 欄位預設為 `UNREVIEWED`；由人工依 `expectedEvidence` 與原始 DB row／文件版本逐題填入 correctness、completeness、unsupported claims、verifiability、winner、失敗原因及備註。若 DB snapshot 未捕捉、資料版本不符或必要服務不可用，將該結果標示 `NOT RUN`／`BLOCKED`，不要用 seed 靜態值替代實際輸出。
