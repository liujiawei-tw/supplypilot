# SupplyPilot V2 — 採購單、八階段 Kanban 與交期

> 狀態：已決策，尚待 V2 實作。對應：[#138](https://github.com/liujiawei-tw/supplypilot/issues/138)、[#139](https://github.com/liujiawei-tw/supplypilot/issues/139)、[#140](https://github.com/liujiawei-tw/supplypilot/issues/140)、[#141](https://github.com/liujiawei-tw/supplypilot/issues/141)、[#142](https://github.com/liujiawei-tw/supplypilot/issues/142)、[#143](https://github.com/liujiawei-tw/supplypilot/issues/143)、[#144](https://github.com/liujiawei-tw/supplypilot/issues/144)。

## 1. 草稿建立與主流程

「新請購」即建立真正的 PO DRAFT，一張 Kanban 卡片代表一張真實 PO。**不再強制經過 Demand → Recommendation → Ranking**。

- DRAFT 新建必須有：供應商、至少一個品項、合法數量及單價；單價允許暫估。
- 付款條件非必填。期望到貨日可以留空，**核准前必須補齊**。
- 第 1～3 欄的正式 PO 狀態仍是 DRAFT，**審核中可直接編輯草稿**。APPROVED 之後不可走 Draft Edit。
- 供應商主檔的 reliabilityScore / qualityScore 均為人工 0～100 分數，保留現有更新方式；不做新評鑑歷程或 Ranking。

## 2. 八階段及正式 PO 狀態映射

| 欄 | 顯示名稱 | PO lifecycle | 進入條件 |
|---:|---|---|---|
| 1 | 待處理／新請購 | DRAFT | 成功建立 PO 草稿 |
| 2 | 詢比議價 | DRAFT | 依序前進 |
| 3 | 審核／簽核中 | DRAFT | 依序前進；草稿仍可編輯 |
| 4 | 已核准待下單 | APPROVED | 3→4 核准 API 成功 |
| 5 | 已下單／生產／交期跟催 | ORDERED | 4→5 下單 API 成功 |
| 6 | 到貨驗收 | ORDERED | 5→6 人工移入驗收追蹤，不先入庫 |
| 7 | 請款核銷中 | RECEIVED | 6→7 整筆人工驗收合格且收貨交易成功 |
| 8 | 已完成／封存結案 | RECEIVED | 7→8 人工確認付款完成 |

- Stage 是獨立的目前進度欄位，**不是**任意改寫 PO.status；後端必須保證兩者相容。
- 遷移時 `ORDERED + LEGACY_UNKNOWN` 先以 `stage: null` 排除於八欄；只有 ADMIN 經 `begin-receiving-inspection` 明確選擇第 6 欄後，才成為 `RECEIVING_INSPECTION`。這不推測舊資料原先在哪一欄。
- 每次只前進一階，不允許跳關或倒退。**唯一例外：8→7 撤銷付款確認**，不能撤銷入庫或改回 ORDERED。
- 拖曳或按鈕均須經確認再呼叫受控 Backend 業務命令；API 失敗時卡片回復原位。不得由前端自行 PATCH 正式 PO.status。
- 只保留目前階段，**不新增逐次看板移動歷史表**；既有 PO 業務稽核、交易紀錄不因此取消。
- 已終止的 REJECTED／CANCELLED PO 不屬於活動八欄，仍可在歷史列表查閱。

## 3. 駁回及取消

| 位置 | 可做的終止行為 | 結果 |
|---|---|---|
| 第 1～3 欄 | 駁回 | DRAFT → REJECTED |
| 第 4 欄 | 取消 | APPROVED → CANCELLED |
| 第 5 欄 | 取消 | ORDERED → CANCELLED |
| 第 6～8 欄 | **禁止取消** | 保留原狀 |

- CANCELLED 為正式終態，不可恢復；取消原因必填，保留取消者、時間、原始 PO 內容。
- 第 5 欄下單後可以手動取消，不需將「供應商同意」當系統准入條件，但必須告知 **系統取消不代表供應商接受**。
- CANCELLED 不計入 Incoming Inventory，不可再收貨，**不能修改已存在的實際 Inventory 或 StockTransaction**。
- REJECTED 是 DRAFT 的駁回終態，與 CANCELLED 的核准/已下單取消不同，不互相替代。

## 4. 第 6 欄到貨／品質驗收與付款

- 5→6：供應商通知即將到貨即可移入；仍是 ORDERED，不先增加庫存。
- 第 6 欄：人工檢查**整筆數量與品質**。尚未到貨、短收、部分交貨或驗收不合格，保留在第 6 欄並輸入異常備註；**不支援部分收貨**。
- 6→7：只有整筆收到且全數合格，才能呼叫收貨 API。依既有 Transaction Boundary 同時更新 Inventory、StockTransaction 及 PO ORDERED→RECEIVED；任何失敗全部 rollback，重複收貨受阻擋。
- 7→8：採購人員明確標記已付款；8→7 僅撤銷付款確認。**沒有真實金流、銀行 API、供應商付款通知或自動請款**。

## 5. 兩種獨立日期

| 欄位建議 | 業務意義 | 可修改時機 |
|---|---|---|
| expectedDeliveryDate | 公司期望到貨日／核准基準 | DRAFT 可以；**APPROVED、ORDERED、RECEIVED 不可** |
| supplierPromisedDeliveryDate | 供應商最新承諾交期 | 獨立保存；APPROVED、ORDERED 可更新；不可覆蓋期望到貨日 |

- 逾期旗標仍使用 Backend 的 ORDERED + 當天晚於 expectedDeliveryDate 判定；**承諾交期修改不可抹去逾期事實**。
- `supplierPromisedDeliveryDate = NULL` 表示尚無目前供應商承諾；不得當成 expectedDeliveryDate 的別名或備援值。
- 不新增完整承諾交期變動歷史或自動提醒；空值不自動假設等於期望日。

## 6. 採購列表與畫面

- 同一 /purchase-orders 頁面可切換 Kanban / 列表，桌面預設看板。手機保留基本列表查詢，不必完整拖曳。
- 新增採購單號（全碼或部分碼）、供應商代碼、供應商名稱、物料代碼；既有日期區間、狀態、逾期、分頁與排序仍可合併。
- **後端先篩選再分頁**；多明細 join 不得造成 PO 重複計數。
- 不做列表 CSV/Excel/PDF 匯出，也不做採購單附件與文件關聯。

## 7. 關鍵驗收案例

1. DRAFT 缺期望日可以儲存但不能核准；第 3 欄可編輯；核准後無法更改公司期望日。
2. 第 1～3 欄終止為 REJECTED，第 4～5 欄為 CANCELLED，第 6 欄起取消拒絕。
3. 5→6 不增加庫存；不良/短收留第 6 欄；整筆驗收成功後庫存只增加一次。
4. 付款確認與撤銷不改變實際庫存或 PO RECEIVED。
5. 交期兩欄可獨立顯示與保存，逾期計算不受承諾日更新影響。
6. 看板非法跳關、並發收貨/取消、權限不足與 101 筆以上 PO 搜尋/分頁都須有負向驗證。

## V2-201 實作狀態

Issue #138 的程式與驗證狀態以 [`docs/evidence/issue-138-validation.md`](../evidence/issue-138-validation.md) 為準。本 Issue 保留原有 PO Draft、可空期望日、核准日期檢查、PO 編號、`@Version` 與收貨交易能力；Draft Edit 和核准對同一 PO 使用列鎖序列化；不建立 Kanban Stage 欄位或移除歷史資料。V2 新業務流程直接建立 PO，Demand/Recommendation 寫入不向 V2 ADMIN 開放，舊資料讀取及歷史參照保留。

## V2-202（Issue #139）實作狀態

程式與驗證狀態以 [`docs/evidence/issue-139-validation.md`](../evidence/issue-139-validation.md) 為準。已實作 DRAFT 駁回、APPROVED 取消、ORDERED 第 5 欄取消、取消稽核快照、不可復原終態、取消後排除在途查詢及禁止收貨。取消與收貨、核准、駁回、下單使用相同的 PO `PESSIMISTIC_WRITE` 列鎖，收貨先鎖 PO 再寫 Inventory/StockTransaction。

為辨認第 5/6 欄，只新增 ORDERED 專用的持久化 `ordered_progress` 契約：新下單為 `FOLLOW_UP`（第 5 欄），「開始到貨驗收」命令將其改為 `RECEIVING_INSPECTION`（第 6 欄）；第 6 欄起取消被後端拒絕。既有 ORDERED 資料無法由舊欄位可靠推算第 5 或第 6 欄，Migration 以 `LEGACY_UNKNOWN` 保留未知並禁止取消；管理員可經受控命令明確移入第 6 欄，不接受 Client 任意 Stage 值。此欄位與遷移規則是 #139 的最小取消保護契約；#141 沿用第 5→6 操作並補齊驗收/收貨流程，#142 再擴為完整八階段模型與轉移不變量。目前沒有完整八欄看板或任意 Stage API。

**#139 範圍限制：** #139 原始實作僅完成取消及第 5／6 欄的取消保護；第 6 欄完整人工數量／品質驗收與 6→7 收貨准入由 #141 實作。#141 實際完成狀態見下方 V2-204 實作紀錄。完整八階段與通用轉換仍由 #142 負責。

## V2-204 實作狀態（Issue #141）

- 5→6 沿用 `ordered_progress`；進入驗收時仍是 ORDERED，不動 Inventory/StockTransaction。
- 第 6 欄儲存三項明確驗收旗標與目前異常備註。只要未確認整筆到貨、全部數量與全部品質合格，舊 `/receive` 及所有其他收貨路徑均由 Backend 拒絕。
- 合格後仍一次更新 PO、Inventory 與 StockTransaction；付款確認及撤銷以目前付款者/時間欄位記錄，不變更 RECEIVED 或庫存。
- 欄位遷移、API 與驗收證據分別見 `docs/api.md`、`docs/domain-model.md`、`docs/transaction-boundary.md`、`docs/security-design.md`、`docs/testing-strategy-merged.md` 與 `docs/evidence/issue-141-validation.md`。
- 第 7/8 欄由 RECEIVED 及目前付款確認時間推導；本 Issue 沒有建立完整八欄 Stage 欄位或 Stage Transition Engine。
