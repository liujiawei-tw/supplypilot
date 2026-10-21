# SupplyPilot V2 — Demo、安全重置、驗收及部署順序

> 已確認 V2 規劃；新增自動恢復功能、AWS 部署與 V2 全站實測仍**未完成**。對應：[#147](https://github.com/liujiawei-tw/supplypilot/issues/147)、[#149](https://github.com/liujiawei-tw/supplypilot/issues/149)、既有 [AWS #133](https://github.com/liujiawei-tw/supplypilot/issues/133) 與 [Final Review #46](https://github.com/liujiawei-tw/supplypilot/issues/46)。

## 1. 環境順序及不可冒稱的完成條件

1. 先完成 V2 規格與全部必要程式變更，逐張 Issue 測試／Review／人工 Merge。
2. **本機標準 Docker Compose（Frontend、Backend、MySQL、PostgreSQL/pgvector）** 與真實 Browser／Gemini Chat + Embedding + pgvector E2E 驗收。
3. 再依 #133 討論和執行 AWS EC2／HTTPS／台灣午夜 Reset 的正式公開展示驗收；**AWS 公開共用帳號權限在此階段決定，不預設 V1 的 PROCUREMENT_MANAGER 或直接公開 admin**。
4. 最後製作完整從頭到尾的操作影片；影片不能替代實際測試，Release Review #46 必須對應 V2 的新 SHA 和最新規格。

**本文件、Issues 的建立與先前 V1 CI PASS 均不能視為 V2 已完成、AWS 已部署或真實 Provider Gate PASS。**

## 2. Demo 基線策略

- Demo 是受控、可重現的虛構企業資料，與 prod profile／正式企業資料嚴格隔離。保留現有 DEMO_ALLOWED_DATABASE、Semantic DB、DOCUMENT_STORAGE_PATH、Flyway/Storage Guard 等安全檢查。
- 基線文件目前五份，可在未來增加、刪除或更新；不要在應用程式把「五份固定文字與版本」寫死。
- **Baseline Manifest** 記錄文件唯一 ID/邏輯鍵、檔名、文件類型、version、內容 SHA-256、已核准來源哈希與語料 dataset 版本；只有明確白名單且 hash/version 一致者可在重置後自動恢復有效出站核准。
- 基線文件內容或版號一變，先經一般管理員首次審核；**不得繼承舊 hash 授權**。
- 測試用第六份 UI Upload 供應商文件不是 baseline，重置後不應存在，也不享有白名單自動審核。

## 3. 重置與 RAG 恢復

    受控 Demo Reset
      → 重新建立 relational 與虛構文件基線
      → 依 manifest 對比 hash/version/先前合法審核
      → 對合格基線安全恢復核准
      → 自動排入持久化 Embedding Job
      → 真實 Gemini Embedding + pgvector
      → 逐文件驗證及原子發布
      → 查詢／來源驗證

- **每天 Asia/Taipei 00:00 重置**的決策保留；部署前由 #133 實作並驗證實際排程。面試前可受控手動 Reset。重置可能中斷正在進行的展示操作，需明確告知。
- 一般 Docker 重啟**不得**觸發 Reset；不得直接執行未確認目標的資料庫清表、Volume 刪除或不帶守衛的初始化。
- Reset 或索引失敗必須可辨識 FAIL、可再執行安全恢復；**Document READY 不代表索引已發布**。保留來源、版本、檢索及真實模型證據。
- 若 Gemini 服務未提供、限流或中斷，RAG 恢復不宣稱完成；核心採購業務不得被 RAG 故障阻斷。

## 4. V2 驗收矩陣摘要

| 區塊 | 必須具體核對 |
|---|---|
| 帳號 | 多 ADMIN 業務帳號、canManageUsers 403、最後管理者保護、永久刪除後 PO 未指派與快照 |
| 草稿 | 最低必填、新請購=正式 DRAFT、期望日後補、審核中可編輯、核准前日期 Gate |
| 看板 | 八欄 Stage/PO 狀態映射、業務操作確認、非法跳欄與只前進、8→7 撤銷付款 |
| 取消 | 1～3 REJECTED、4～5 CANCELLED、6～8 禁止、取消原因與在途庫存排除 |
| 收貨付款 | 5→6 無入庫、異常留第 6 欄、6→7 原子整筆收貨、7→8 人工付款、不得重複入庫 |
| 交期 | 期望日期核准後不可改、承諾日期獨立可改、逾期不隨承諾日期消失 |
| 搜尋 | PO/供應商/物料文字條件及原有日期/狀態，含 101 筆以上分頁與安全查詢 |
| RAG | MySQL+pgvector 查詢融合、文件名稱/版本/原文摘錄、無證據拒答、敏感出站不繞過 |
| 索引 | 新文件未索引／失敗不阻斷已發布文件；核准後自動 Job、Retry、Reboot 恢復與 admin Retry |
| Demo | 已核准 manifest 文件 Reset 後自動真實索引；修改 hash 不繼承授權，第六份不自動保存 |
| UI | 繁中、時區、RWD、金額格式、只保留使用者確認的功能範圍 |
| 可靠性 | JWT、後端權限、交易回滾、併發 409、Provider 503、安全錯誤訊息及秘密資料保護 |

## 5. 部署前延後事項

**AWS 共用 Demo 帳號權限明確尚未決策。** 因 V2 已改成單一 ADMIN 業務角色，原 #133 描述的一組 PROCUREMENT_MANAGER 帳號不能當作 V2 權威。AWS 階段須先確定共同帳號的人員管理、文件上傳及出站核准授權，再做公開測試。

暫不決定雲端成本、域名、額外雲端服務、公開登入限制細節；不得因此新增 HA、託管資料庫或無關架構。
