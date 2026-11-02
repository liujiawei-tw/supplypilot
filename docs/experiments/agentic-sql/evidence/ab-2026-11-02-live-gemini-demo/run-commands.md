# 執行命令與環境

## 環境

- Windows 本機 Docker DEMO；Frontend loopback `http://127.0.0.1:8081`，Backend 與 MySQL/PostgreSQL 皆在同一 Compose DEMO。
- DEMO 開始前服務健康；資料集 `SP-901 / sp-907-zh-tw-v3 / COMPLETE`。
- Python `3.13.5`。
- `AI_CHAT_PROVIDER=google-genai`；模型 `gemini-3.6-flash`。Temperature 沒有由後端 runtime 暴露。runner 的 `--temperature` 僅寫入 metadata，不會改變服務設定。
- Authorization Bearer token 由本機 Demo admin login 臨時取得，只存在測試程序記憶體；未寫入檔案或日誌。
- Snapshot fingerprint：`A97501CEA5F2DCAA08E7D0C0B981FD5EA5DD4EFD9DF5C34CBB003DB07310E0EB`。

## Runner 呼叫

每次呼叫均使用相同 loopback base URL、Gemini model metadata、timeout 與 snapshot fingerprint。沒有將一方答案或工具軌跡傳給另一方。

```powershell
python scripts/agentic-sql-benchmark/run_benchmark.py --live --cases docs/experiments/agentic-sql/evidence/ab-2026-11-02-live-gemini-demo/cases-smoke.jsonl --base-url http://127.0.0.1:8081 --output docs/experiments/agentic-sql/evidence/ab-2026-11-02-live-gemini-demo/smoke-result.jsonl --model gemini-3.6-flash --temperature "provider default (not exposed)" --snapshot-hash A97501CEA5F2DCAA08E7D0C0B981FD5EA5DD4EFD9DF5C34CBB003DB07310E0EB --timeout-seconds 120
```

- Smoke runner exit code: `0`; one paired case written.
- Remaining 9 runner exit code: `1` due recorded HTTP 503 responses. Runner still wrote all 9 comparison rows. Original output and exit evidence: `remaining-9-results.jsonl`, `remaining-9-command.log`.
- Live PO replacement runner exit code: `0`; one paired case written. Output and log: `replacement-live-po-result.jsonl`, `replacement-live-po-command.log`.
- Final score file is 10 valid pairs. The absent `DEMO-PO-001` attempt remains in `results-all-attempts-11.jsonl` and is excluded from scoring.

Remaining-nine command (exit code `1`; nine comparison rows were written):

```powershell
python scripts/agentic-sql-benchmark/run_benchmark.py --live --cases docs/experiments/agentic-sql/evidence/ab-2026-11-02-live-gemini-demo/cases-remaining-9.jsonl --base-url http://127.0.0.1:8081 --output docs/experiments/agentic-sql/evidence/ab-2026-11-02-live-gemini-demo/remaining-9-results.jsonl --model gemini-3.6-flash --temperature "provider default (not exposed)" --snapshot-hash A97501CEA5F2DCAA08E7D0C0B981FD5EA5DD4EFD9DF5C34CBB003DB07310E0EB --timeout-seconds 120
```

Live PO replacement command (exit code `0`):

```powershell
python scripts/agentic-sql-benchmark/run_benchmark.py --live --cases docs/experiments/agentic-sql/evidence/ab-2026-11-02-live-gemini-demo/case-replacement-live-po.jsonl --base-url http://127.0.0.1:8081 --output docs/experiments/agentic-sql/evidence/ab-2026-11-02-live-gemini-demo/replacement-live-po-result.jsonl --model gemini-3.6-flash --temperature "provider default (not exposed)" --snapshot-hash A97501CEA5F2DCAA08E7D0C0B981FD5EA5DD4EFD9DF5C34CBB003DB07310E0EB --timeout-seconds 120
```

The exact JSONL inputs, endpoint outputs, response codes, latency, token usage and available tool traces are retained beside this file.

## Cleanup

After testing, the Agentic SQL backend override was removed and the original Compose backend was recreated. The backend returned to healthy with Agentic SQL disabled by default. The temporary MySQL user was dropped. PostgreSQL grants were explicitly revoked and the temporary role was dropped. No business rows were changed; the pre-run and post-run snapshot fingerprints match.
