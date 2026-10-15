# SP-905 繁體中文化契約與人工驗收紀錄

## Dataset 與文件基線

- Dataset Version：`sp-905-zh-tw-v1`；舊 `sp-901-v1` marker 會因版本不符停止初始化，需遵循 [`SP-901-DEMO-RESET-RUNBOOK.md`](SP-901-DEMO-RESET-RUNBOOK.md) 的 Guarded Reset。
- Baseline 僅 Seed 五份文件；每份 `Version=1.0`，文件標題須與 TXT 的 `標題：` 行一致。第六份 `ui-upload-sup-005-v1.txt` 只供人員於 UI 上傳，Seeder 不建立 metadata、chunk 或 embedding。
- 正式採購狀態值、API 傳輸值及 Database Enum 維持英文原值；繁中僅用於顯示。
- 五份文件重新 Ingestion 才產生與新正文一致的 Chunk。正式 Semantic 部署還須重新審核、Backfill、Readiness、Gate Audit；舊 Source Hash／Embedding／Audit 不得沿用。

## 可執行契約

- `TextChunkerTests` 驗證中文、英文、混合內容與 Supplementary Unicode 字元都能以現有 Java UTF-16 String offset 切回相同原文。Offsets 是 Java 字串索引，Source citation 沿用原始文件切片，不因 UI 翻譯而改 API 契約。
- Demo Seed Integration Test 驗證五份 READY、Metadata title、Version、原檔與 chunk 內容相符；固定 Dataset Version 與第六份不 Seed；reset tests 核對 semantic index/audit 清除後才重建 relational baseline。
- 自動化 Fake／local lexical 查詢只表示可測查詢路徑及文字保存，不代表跨語向量搜尋品質。

## 跨語手動驗收（由 SP-910 #117 執行）

在隔離 Demo 上，以真實 Gemini Embedding／Chat、全新 Dataset `sp-905-zh-tw-v1`、ADMIN UI 操作與 Browser 證據執行：

1. 使用中文問題「SUP-001 的 MAT-001 標準交期是多久？」查詢一份英文文件，核對答案需為 ORDERED 後 5 個曆日，並逐項核對 Document ID、Version、Chunk Index、Start Offset、End Offset 與原始引用。
2. 使用英文問題 `How long is SUP-002's quotation valid?` 查詢中文文件，核對答案為 30 calendar days，並逐項核對同一組 Source 定位欄位。
3. 透過 UI 上傳第六份文件並完成正式 ingestion／review／backfill／readiness／gate，查詢其季節性交期通知條件，確認為預計交期前 2 個工作天。
4. 同時測量資料庫尚未索引、Evidence 不足、Provider 503 及 Gate 未通過的行為；不得把 Fake Provider 結果當作 PASS。

狀態：**BLOCKED／NOT RUN**（SP-905 程式交付階段未執行真實 Gemini 與完整 Browser Release Gate）。解除條件：#117 提供隔離 MySQL／pgvector／storage、可安全注入的 Gemini Chat／Embedding credentials，並以合併後繁中程式碼依 #117 保留完整環境、SHA、generation、時間、Browser 與 Source 證據。
