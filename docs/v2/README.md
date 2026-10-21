# SupplyPilot V2 — 需求決策總綱

> 版本：V2；決策日期：2026-10-20～21。狀態：**規格彙整中，非已實作／已驗收**。追蹤：[規格 Issue #135](https://github.com/liujiawei-tw/supplypilot/issues/135)。

## 1. 版本規則

- 本目錄是 SupplyPilot **V2 新增及變更部分**的規格基線；若 V1 的功能、流程、權限、UI、AI Gate 與 V2 明確衝突，V2 決策優先。
- 未被 V2 覆蓋的安全、交易、授權、稽核與錯誤契約仍按現有 V1 設計處理。不得為精簡功能而犧牲安全性與資料一致性。
- 舊 V1 文件與測試證據作歷史基線保存，尚未實作的 V2 功能不得標示為 PASS。
- 歷次對話「需求 #1～#17」題號有重用；以功能名稱及實際 GitHub Issue 為唯一追蹤鍵。

## 2. 已確認的主要決策

| 範圍 | V2 |
|---|---|
| 帳號 | 多帳號、單一 ADMIN 業務角色；全部帳號有採購操作權，僅額外授權者可管理人員 |
| 人員生命週期 | 可停用或永久刪除；歷史操作姓名快照保留；未完成 PO 改為未指派並允許重領／指派；保護最後一位啟用管理員 |
| 主流程 | 新請購直接建立 PO DRAFT；Demand、採購自動推薦及供應商排名不再是 V2 功能 |
| PO 草稿 | 供應商、一項以上品項、數量、單價必填；可用暫估單價，期望到貨日可後補，付款條件非必填，審核中仍可編輯 |
| 看板 | 八階段 Kanban，卡片是真實 PO；與 PO 正式狀態分離；只保存目前進度，依序推進，付款撤銷允許 8→7 |
| 駁回／取消 | 第 1～3 欄 REJECTED；第 4～5 欄 CANCELLED；第 6 欄起不允許取消；取消不更動庫存 |
| 收貨／付款 | 第 6 欄人工整筆驗收（數量與品質），合格後一次收貨入庫；第 7→8 欄人工確認付款 |
| 交期 | 核准後期望到貨日不可改；供應商承諾交期獨立保存且可更新 |
| 供應商 | 保留 0～100 的人工可靠度及品質評分，不做歷史評鑑或自動計分 |
| 查詢與 UI | PO 號、供應商代碼/名稱、物料代碼搜尋；同頁看板／列表切換；桌面優先，手機基本查詢 |
| RAG | MySQL 關鍵字／元資料 + pgvector Hybrid Retrieval，Evidence Validator，答案附來源文件及原文摘錄 |
| 文件發布 | 管理員核准後非同步自動索引、逐文件原子發布、自動有限次重試、失敗可由管理員重試 |
| Demo | 受控 Baseline Manifest 可更新，重置後僅已核准且版本/hash 一致文件自動恢復真實索引 |
| 格式 | zh-TW、Asia/Taipei、千分位；AI 金額最多兩位小數且去除尾隨零，不改 BigDecimal 精度 |

## 3. 明確不做或延後

- 不做：文件級 ACL、PDF 頁碼定位、條文號自動定位、合約比較／缺漏分析、供應商自動排名、庫存安全量警示、PO 匯出、PO 關聯附件、通知中心、每次 Kanban 移動歷史、部分收貨、自動付款。
- RAG 的敏感文件出站攔截保持原樣；禁止搜尋新路徑繞過已核准外送檢查。
- **AWS 公開 Demo 共用帳號的最終角色及上傳／人員管理權限，延至 AWS 部署階段再討論。** [#133](https://github.com/liujiawei-tw/supplypilot/issues/133) 尚未取得 V2 公開權限最終授權，不提前部署。
- 已有 Issues 不重開：[AI 回答格式 #126](https://github.com/liujiawei-tw/supplypilot/issues/126) 更新為最多兩位小數；[AI 複合條件查詢 #127](https://github.com/liujiawei-tw/supplypilot/issues/127) 仍是獨立 Backlog，不與 PO 列表搜尋混淆。

## 4. 文件索引

- [帳號與安全](accounts-and-security.md)
- [採購與 Kanban](procurement-and-kanban.md)
- [Documents / AI](document-rag.md)
- [前端與顯示](ui-and-reporting.md)
- [Demo 與驗收](demo-and-acceptance.md)
- [V1 → V2 差異／Issue 對照](migration-and-issue-map.md)
- [Matt Pocock Skills × Codex × GPT 協作與新對話交接](matt-pocock-codex-gpt-handoff.md)（工程方法與交付權限；不變更 V2 產品 Scope）

## 5. 工作原則

只在 V2 規格合併後逐張實作 Issue；遵守 [AGENTS.md](../../AGENTS.md) 單 Issue／單 Branch／GPT Review FINAL PASS／使用者人工 Merge。建議先完成本機真實測試與 Docker，再 AWS，最後製作完整操作影片。**建立 Issue 或 Markdown 不代表程式已完成。**
