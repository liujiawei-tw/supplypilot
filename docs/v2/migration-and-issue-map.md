# SupplyPilot V2 — V1 差異矩陣、GitHub Issues 與實作順序

> 本文件是需求決策與工作拆分，不代表 V2 已完成。建檔時的 main 基線：87e8210e0d1dce412b91bd8972ae635223e59be7（2026-10-21 對照）；後續實作必須重讀當時最新 main 以避免 SHA 漂移。

## 1. V1 → V2 實際變更／保留／排除

| 領域 | V1 現況（程式或規格） | V2 已確認變更 | 實作 Issue |
|---|---|---|---|
| 帳號權限 | V1 生產、採購、經理、倉管、ADMIN 多角色及分割路由 | 多個 ADMIN 業務帳號；僅明確授權帳號具人員管理能力 | #136 |
| 帳號刪除 | V1 歷史關聯依 User FK；未具 V2 刪除／交接契約 | 可永久刪除、最後管理者保護、歷史姓名快照、未完成 PO 未指派 | #137 |
| 主流程 | Demand → Net Requirement → Recommendation → Ranking → PO | 新請購直接 PO DRAFT；Demand/推薦/排名退出 V2；不破壞既有歷史資料 | #138 |
| 供應商評分 | reliabilityScore/qualityScore 手工 0～100，另有排名演算法 | 仍人工管理兩項分數，無 Ranking/自動評分/評鑑歷史 | #138 |
| PO 草稿 | DRAFT 編輯/核准及原有欄位驗證 | 供應商、品項、數量、單價必填；暫估價可用；期望日後補；審核中可編輯 | #138 |
| PO 終止 | V1 有 DRAFT→REJECTED，無 CANCELLED | 第 1～3 REJECTED，第 4～5 CANCELLED，第 6 起禁止 | #139 |
| PO 交期 | expectedDeliveryDate 一個日期，核准後不能 Draft Edit | 增加獨立供應商承諾日期，可更新，保留公司原始交期 | #140 |
| 收貨/付款 | 既有整筆收貨入庫，無八欄人工品質/付款節點 | 5→6 只追蹤；異常留 6；全數合格才 6→7 收貨；7→8 人工付款，可 8→7 撤銷 | #141 |
| 看板模型 | V1 正式 PO 只有五個 lifecycle statuses，沒有 8 Stage 持久化 | 八階段目前位置、只往前、操作跨狀態須後端命令、無拖曳歷史 | #142 |
| 看板 UI | 現有 /purchase-orders 是 Table | 同頁看板/列表，桌面預設 Kanban，手機基本列表 | #143 |
| PO 查詢 | status、supplierId、materialId、日期、逾期、排序分頁 | 額外 PO 號、供應商代碼/名稱、物料代碼 | #144 |
| 文件查詢 | lexical/semantic 二選一，Evidence Validator | Hybrid Retrieval + 來源文件/版本/原文摘錄；無頁碼/合約比較 | #145 |
| Semantic Gate | V1 新 READY 文件可能阻斷整體 Gate；人工 Backfill/Audit | 已發布文件獨立可用，新文件管理員核准即非同步索引/重試/原子發布 | #146 |
| Demo 文件恢復 | Reset 後五份 READY、清除舊索引/核准，需人工重走 Gate | 可調整基線 Manifest、版本/哈希白名單合法恢復、真實索引自動重建 | #147 |
| 介面 / RWD | V1 有繁中與 UI-D 200%/RWD，格式仍需一致 | 統一 zh-TW、台灣時區/千分位/幣別；桌面完整、手機基本查詢 | #148 |
| AI 回答排版 | #126 Backlog 原最多 1 位小數 | **既有 #126 已更新最多 2 位、去尾零及分段排版** | #126 |
| E2E / Release | V1 SP-900/901/903/910 E9 證據已歷次執行，#46 Open | V2 新 SHA 重做對應測試，本機優先，再 AWS #133，最後影片 | #149、#133、#46 |
| 公開 Demo 身分 | #133 沿用 V1 PROCUREMENT_MANAGER 假設 | **明確延後到 AWS 部署階段**，不能由 V2 單一 ADMIN 推論公開授權 | #133 |

## 2. 明確維持現況／不新做

| 決策 | 處理 |
|---|---|
| 人工供應商評分 | **保留**，不加歷史、排名及自動演算 |
| 庫存頁 | **保留**，不加安全庫存提醒／篩選 |
| 逾期 | 保留後端 isOverdue 規則與 Dashboard/列表 Badge；不加通知中心 |
| PO 編輯交期 | 原期望到貨日核准後 **不可修改**；不與可更新供應商承諾日混淆 |
| PO 匯出 | 不做 CSV、Excel、PDF |
| PO 與 Document | 維持獨立，不做附件關聯 |
| RAG 源定位 | 附來源文件與逐字摘錄即可，不做 PDF 頁數及條文座標 |
| 合約分析 | 保留既有單份文件問答，不做合約比較、缺少條款判讀 |
| 文件級 ACL | 延後，不在 V2 本輪建置 |
| AI 敏感出站 | 維持原 OutboundDocumentPolicy，不自動合法化價格等敏感文件 |
| Kanban 過程 | 不留每次移動歷史，保留目前 Stage 與既有 PO 稽核 |
| 部分收貨 | 不做；不良/短收留第 6 欄 |
| 自動支付／提醒 | 不做；付款人員手動標記 |

## 3. GitHub V2 Issues 對照

| ID | GitHub | 主要產出 | 建議順序／依賴 |
|---|---|---|---|
| V2-000 | [#135](https://github.com/liujiawei-tw/supplypilot/issues/135) | 本目錄需求、差異矩陣與 Issues | 第 0 步，文件 PR 審核合併 |
| V2-101 | [#136](https://github.com/liujiawei-tw/supplypilot/issues/136) | ADMIN 業務帳號、獨立人員管理能力 | #135 |
| V2-102 | [#137](https://github.com/liujiawei-tw/supplypilot/issues/137) | 刪除帳號、歷史姓名、PO 交接 | #136 |
| V2-201 | [#138](https://github.com/liujiawei-tw/supplypilot/issues/138) | 移除主流程 Demand/Recommendation、建立 DRAFT | #135、#136 |
| V2-202 | [#139](https://github.com/liujiawei-tw/supplypilot/issues/139) | REJECTED vs CANCELLED、在途庫存修正 | #138 |
| V2-203 | [#140](https://github.com/liujiawei-tw/supplypilot/issues/140) | supplierPromisedDeliveryDate | #138 |
| V2-204 | [#141](https://github.com/liujiawei-tw/supplypilot/issues/141) | 第六欄全數驗收、第七欄請款、第八欄付款與撤銷 | #138～#140 |
| V2-205 | [#142](https://github.com/liujiawei-tw/supplypilot/issues/142) | 8-stage Domain/狀態轉換與安全不變量 | #138～#141 |
| V2-206 | [#143](https://github.com/liujiawei-tw/supplypilot/issues/143) | Kanban/List 同頁與手機基本查詢 | #141～#142 |
| V2-207 | [#144](https://github.com/liujiawei-tw/supplypilot/issues/144) | PO 編號、供應商/物料代碼查詢 | #138；可在 Domain 穩定後實作 |
| V2-301 | [#145](https://github.com/liujiawei-tw/supplypilot/issues/145) | MySQL + pgvector 檢索融合、可信來源 | #135、#136；與 PO 群組可分階段並行規劃，實作仍一單一 PR |
| V2-302 | [#146](https://github.com/liujiawei-tw/supplypilot/issues/146) | 非同步 Index Job、逐文件發布與 Retry | #145 |
| V2-303 | [#147](https://github.com/liujiawei-tw/supplypilot/issues/147) | Manifest 白名單與 Reset 後自動恢復 | #146 |
| V2-401 | [#148](https://github.com/liujiawei-tw/supplypilot/issues/148) | 全站 zh-TW/timezone/format/RWD 一致化 | #143；AI 金額由 #126 另做 |
| V2-900 | [#149](https://github.com/liujiawei-tw/supplypilot/issues/149) | V2 全站 E2E、資料遷移／安全與 README 對齊 | 必要 V2 Issues 合併後 |
| 既有 | [#126](https://github.com/liujiawei-tw/supplypilot/issues/126) | AI 金額 **最多兩位小數**與回答排版 | V2 顯示規格，避免再開重複 Issue |
| 既有 Backlog | [#127](https://github.com/liujiawei-tw/supplypilot/issues/127) | AI 多條件／金額比較精準查詢 | **獨立擴充，未因本輪 PO 列表搜尋自動納入已完成** |
| 延後部署 | [#133](https://github.com/liujiawei-tw/supplypilot/issues/133) | AWS、HTTPS、台灣午夜 Reset、雲端驗收 | V2 本機驗收後，先完成共用帳號權限決策 |
| 最終關卡 | [#46](https://github.com/liujiawei-tw/supplypilot/issues/46) | Release Review/影片等既有規則 | 新版 SHA/證據更新，不以舊 V1 PASS 代替 |

## 4. V2 程式影響檢查清單

- **Database / Flyway：** User 權限能力、PO 負責人與操作快照、CANCELLED、stage、承諾日、人工付款欄位/狀態、必要的文件索引 Job/Manifest 狀態。**先設計可向前遷移/回滾的方案**，不假設可直接刪舊 Demand/Recommendation 表。
- **Backend / API：** 採購業務權限簡化、Admin 人員管理、PO 草稿與終止、Stages/Receiving/Payment 命令、精確搜尋、Hybrid Query、per-document 索引/重試、重置保護與狀態檢查。
- **Frontend：** 人員與 PO 負責人、8 欄 Kanban+列表、操作確認與錯誤復原、雙交期、搜尋、RAG 原文引用、行動版查詢、格式統一。
- **Docs：** 開發 PR 隨程式同步修改原有 API、security-design、domain-model、ERD、frontend-design、semantic-rag-runbook、Demo Runbook、README；V1 歷史 E9 證據不竄改。
- **Testing：** Migration、角色負向、最後管理者保護、取消／收貨競態、庫存原子性、Stage 轉移、Provider Retry 與資料出站、UI 真實測試、瀏覽器/Reset E2E。

## 5. 尚未解決的事項與避免重複詢問原則

- **唯一明確延後的產品決策：AWS 公開 Demo 共用帳號權限**（部署前決定）；此時不要拿舊 V1 role 作為 V2 權威。
- 未被使用者指定的工程參數（重試次數、退避秒數、佇列實作、外觀小細節）可由各 Issue 提出有測試依據的安全預設，不需要重新要求使用者逐項選 A/B。
- 如實作發現會改變業務意義或安全約束的真衝突，再提交定點 ADR／決策；不把「未實作」重新包裝成「尚未決定」。
