# SupplyPilot 專案簡報內容提要

完整簡報：[SupplyPilot 專案簡報 HTML](supplypilot-project-overview.html)。共六頁內容，另有一頁封面；內容依目前 main 程式與已記錄實驗證據整理。系統唯一人員使用者為採購人員。

## 六頁內容

1. **需求與問題**：採購資訊散落、答案難以核對來源；系統把查詢與流程集中，AI 協助整理但不代替採購決策。
2. **採購請求路徑**：採購人員 → 瀏覽器／React → Nginx `/api/v1` → Spring Boot 身分、權限與交易規則 → MySQL。
3. **文件 RAG**：文件核准 → 抽取與切段 → Embedding／向量索引 → 找候選文件 → Gemini 根據候選回答並回傳來源。這是可設定路徑，`SEMANTIC_READ_ENABLED` 預設關閉，Compose Provider 預設為 `none`。
4. **基礎設施**：四個 Compose 服務、三個網路、三個持久化 volumes、本機連接埠與部署狀態。AWS EC2 仍是目標，未宣稱已上線。
5. **為什麼使用 Tool Calling**：從自然語言查詢需求與權限限制說明採用原因；簡介目前程式定義的 12 個唯讀工具，依供應商、報價、庫存、採購單、文件、已存建議／排名分成六類。每題只開相關工具；已存建議／排名只讀既有結果。語意功能啟用時，文件問題才走 RAG。
6. **成本風險與實測證據**：比較企業與開發維護風險，呈現 11/02 的 10 題 Gemini 配對結果：平均／中位延遲為 Tool Calling 9.51／9.17 秒、Agentic SQL 20.04／19.04 秒；HTTP 2xx 為 7/10 與 6/10。人工判讀的完整正確答案為 1 題與 0 題；Tool Calling 另有部分答案、錯誤與 API 失敗，Agentic SQL 沒有可用答案。此為同一 DEMO 快照上的單次探索性比較。

## A/B 證據範圍

2026-11-02 本機 DEMO 真實 Gemini 對照共有 10 題有效配對，同一資料快照、每題各呼叫一次：

- Tool Calling：HTTP 2xx 7/10；完整正確 1 題、部分 5 題、錯誤 1 題；平均／中位延遲 9.51／9.17 秒。
- Agentic SQL：HTTP 2xx 6/10；沒有可用答案，5 題 `PARTIAL`、1 題 `NEEDS_CLARIFICATION`；平均／中位延遲 20.04／19.04 秒。
- Agentic SQL 成功回應回報 token；Tool Calling 未提供用量欄位，兩者費用皆 `N/A`，不能公平比較成本。這是單次探索性結果，不代表所有題型。

來源：[2026-11-02 本機 Gemini A/B 證據](../experiments/agentic-sql/evidence/ab-2026-11-02-live-gemini-demo/README.md)、[目前 main 架構圖與設定說明](../architecture/README.md)。
