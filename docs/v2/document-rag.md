# SupplyPilot V2 — Documents & AI / 實用版採購 RAG

> 已確認需求，尚未完成 V2 實作。對應：[#145](https://github.com/liujiawei-tw/supplypilot/issues/145)、[#146](https://github.com/liujiawei-tw/supplypilot/issues/146)、[#147](https://github.com/liujiawei-tw/supplypilot/issues/147)。現有程式包含 SP-710 Document Chunk、SP-722 Gemini embedding/pgvector、OutboundDocumentPolicy、Evidence Validator 及 Spring AI Tool Calling。

## 1. 保留的三種使用情境

1. 公司採購 SOP／採購權限或流程文件查詢。
2. 歷史合約或供應商文件**單份條款內容查詢**；不做跨合約比較、缺少條款判定。
3. 供應商評鑑、投訴與歷史報告**文件查詢與有來源摘要**；不憑文件自動計算客觀可靠度或交期 KPI。

僅以文件能提供的證據作答；若查不到足夠段落應回 INSUFFICIENT_EVIDENCE，而非虛構。

## 2. V2 混合檢索流程

    使用者提問
      → Spring AI / SEARCH_DOCUMENTS（保留 Tool Calling）
      → 後端 HybridRetrievalService 協調
           ├─ MySQL：文件元資料、供應商、型號/料號/合約編號、字詞精確或部分匹配
           └─ pgvector：已發布文件的 Gemini 語意向量檢索
      → 檢索候選合併、去重、排名與來源狀態過濾
      → Evidence Validator：核對文件 ID/版本/Chunk/原文摘錄／內容支持程度
      → AI Answer：回答 + 文件名稱/版本/逐字摘錄 + 文件檢視入口

- **不是**每次讓 LLM 在 MySQL 和 pgvector 中二選一，而是由後端確定性地協調候選結果。
- 不新增 Elasticsearch；沿用目前 MySQL 與 PostgreSQL/pgvector。
- 不新增 PDF 頁碼欄位、頁碼抽取、條文號解析。現有 Chunk index/offset 可作內部證據查核，但不必把技術座標顯示為使用者要求的「頁碼」。
- 不宣稱「引用文字存在」代表「答案 100% 正確/完整」；對數字、條件與拒答設可測試的支持邊界。來源一律後端查核，不由 LLM 自造。
- 相關文件不符合查詢時回不足證據；不能為回答完整而將無關內容塞入來源。

## 3. 敏感資料安全（採既有方案 A）

- 沿用現有 OutboundDocumentPolicy、管理員針對內容版本/hash 的核准及 REQUIRES_REVIEW/SKIPPED 規則。
- 包含金額、折扣、銀行帳戶、認證資訊等敏感內容，依既有策略不可外送 Gemini Embedding；**MySQL 搜尋命中不構成 Chat 外送許可**。
- Hybrid Retrieval 需在**交給 LLM 之前**過濾、驗證可外送的證據；不可新增「lexical 路徑免審查」後門。
- 文件級 ACL 暫緩；這不等於放棄現有登入/角色、原文讀取及索引操作授權。受此限制，部分敏感合約可能無法透過語意/AI 問答查詢，應誠實呈現。

## 4. 逐文件發布（取代日常全域 Gate）

1. 上傳與解析完成，文件在 MySQL 可為 READY，但**不代表可供語意查詢**。
2. 管理員對**特定文件來源版本/hash**核准合法出站。
3. 核准成功即排入**持久化、非同步**索引工作；不要求管理員另按 Backfill 或 Gate Audit。
4. 真實 Gemini Embedding → pgvector 建立／驗證 Chunk、model generation、來源 hash。
5. 只在該文件索引完整、政策合規與版本一致後**原子發布**；其他已發布文件始終可正常查詢。
6. 新文件索引失敗或待審核不會關閉所有已發布文件；停用文件即從檢索中排除。現有系統級 Generation/部署驗收可保留，但不作為每次上傳的全域阻斷。

**重試：** 429、逾時及暫時性外部 5xx 自動有限次、退避重試；尊重 Retry-After。審核拒絕／違反敏感政策等不可自動重試。超過上限 FAILED 並提供管理員安全錯誤資訊及手動重試入口。重啟後任務狀態可復原；同一文件多次觸發必須冪等，舊代次或舊 hash 不可發布覆蓋新版。

## 5. Demo 基線自動恢復

- Demo 使用可維護的 Baseline Manifest，**目前五份虛構文件，未來允許調整**；唯一識別、版本、原文 SHA-256、審核狀態及 dataset version 相綁定。
- 重置時只對 manifest 白名單且**內容與版本完全一致、曾合法核准**的基線文件恢復審核並自動索引。新增或修改語料必須先經首次管理員審核；不繼承舊授權。
- 其他一般上傳文件（包含 E2E 使用的第六份虛構供應商文件）不能自動核准，也不得在重置後偽造仍存在。
- 實際恢復須經真實 Gemini Embedding 與可追溯來源確認；Fake Provider、READY 字樣或舊 Gate PASS 不能充當本次驗收證據。

## 6. 明確不做

- 文件級 ACL、逐人授權清單
- PDF 頁碼定位、條文號抽取與來源跳到頁數
- 新舊合約比較、缺漏條款偵測
- 違反敏感政策時另建本地 Embedding 以規避限制
- 整份企業文件的無條件外送與原文日誌

## 7. 驗收

- 同一句查詢可兼用合約編號/料號精準匹配與語意候選；資料來源去重且不混入錯版本或無關文件。
- 回答有真實文件名稱、版本與引用原文；無證據不回答實質細節。
- 未核准/敏感文件不經任何路徑送入 Gemini；Provider 故障不阻斷核心採購 MySQL 業務。
- 新文件排隊、失敗、重試、手動再索引不影響既有可查文件；管理員不需日常全域 Gate 操作。
- Demo 白名單版本/hash 不符不能自動恢復核准或發布索引。
