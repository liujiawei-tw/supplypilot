# SupplyPilot V2 — 帳號、權限、永久刪除與交接

> 規格基線；實作狀態依各 Issue 的合併與驗收記錄判定。對應：[#136](https://github.com/liujiawei-tw/supplypilot/issues/136)、[#137](https://github.com/liujiawei-tw/supplypilot/issues/137)。舊 V1 多角色矩陣見 docs/security-design.md（衝突部分由本文件覆蓋）。

## 1. 帳號與業務授權

- V2 只有一種目前使用中的**業務角色 ADMIN**，但允許**多個不同帳號**。不能把 ADMIN 當成「全系統只有一個使用者」。
- 所有有效 V2 帳號均能執行完整**採購業務操作**（建立／編輯草稿、核准／駁回、正式下單、取消、交期追蹤、驗收／收貨、付款確認、讀取授權文件／使用 AI 等）。
- **人員管理是獨立能力**：只有明確授權具 canManageUsers（正式命名由技術設計落地）者，才可建立、變更、停用、永久刪除其他帳號及授權管理能力；不能因使用者的 role 字串為 ADMIN 就自動授予每個人員管理權限。
- 保留未來新增角色的演進空間，但 **V2 不實作新 RBAC 分級、跨企業、多組織或文件級 ACL**。
- 登入、密碼儲存、Token 時效、帳號停用、401/403、後端 Service 權威授權均保留。前端隱藏按鈕不是授權替代品。
- Demo **初始化帳號**只建立 admin；**AWS 公開 Demo 共用帳號權限**未定，延至 #133 部署時再決策，不能把 Demo 初始化 admin 直接推論成要公開高權限帳號。

### V2-101 落地契約

- `users.can_manage_users` 是獨立持久能力；新建帳號預設 `false`。人員管理 list/detail/create/update 服務均以此能力授權，不能只檢查 `role=ADMIN`。
- V23 將既有角色遷移為 `ADMIN`，只將遷移前已是 V1 `ADMIN` 的帳號保留 `can_manage_users=true`；既有其他角色不獲提升。Production 首次建立的 `admin` 及新 Demo baseline `admin` 明確獲管理能力。
- 每次 Bearer Token 請求都以資料庫的帳號啟用狀態、角色、管理能力與首次改密碼狀態重建 principal。Token 的 `userId`、`subject` 或角色與資料庫不一致時拒絕請求；停用或撤銷管理能力後，舊 Token 不沿用過時權限。Token 簽章與期限仍須有效。
- 建立與更新帳號的 API 不允許指派舊 V1 業務角色；新建帳號的業務角色固定為 `ADMIN`。只有已授權者可透過 `canManageUsers` 授予／撤銷人員管理能力。
- 停用或撤銷最後一個有效人員管理帳號會被拒絕；`UserService` 以 MySQL `SELECT ... FOR UPDATE` 鎖定啟用管理者集合，序列化並發停用／撤權，確保至少保留一名。永久刪除及 PO 交接仍屬 #137。
- Production Bootstrap 以 `ACTIVE + can_manage_users=true` 判定管理者；只有業務角色 `ADMIN` 而無管理能力時會 Fail Closed，不會自動提權。
- V2 Demo relational baseline 只建立 `admin`；既有資料集只會依既有 Dataset Guard/明確 reset 流程處理，不藉此 Issue 自動清除或重建持久資料。

## 2. 生命週期與永久刪除

| 動作 | 要求 |
|---|---|
| 停用 | 停止登入/使用，既有 PO 與業務交易資料仍有效 |
| 永久刪除 | 允許由獲授權管理者執行，**不得連帶刪除 PO、庫存交易與稽核歷史** |
| 最後管理者保護 | **不能停用或刪除最後一位啟用且具有 canManageUsers 的帳號**；包含並發保護 |
| 未完成 PO 負責人被刪除 | 將現行負責人欄位轉為**未指派**，不是刪掉採購單 |
| 重新指派 | 所有具有 V2 採購業務權限的有效使用者可認領未指派 PO 或重新指派 |
| PO 建立時 | 當前建立者預設為負責人 |
| 歷史操作者 | 建立／核准／下單／收貨等姓名快照**不可因刪除帳號而消失或被其他人覆寫** |

## 3. 資料模型與實作邊界

- 人員管理授權與業務角色要在後端安全邊界明確區分。資料搬遷不得將舊 V1 STAFF／MANAGER／WAREHOUSE 等角色無條件提升成人員管理員。
- PO 的現在負責人與歷史操作者是不同語意：**負責人可改派／為空，事件操作者歷史文字快照不可丟失**。
- 現有的 JPA FK 若阻止永久刪除，先以 Flyway／關聯策略保留歷史快照與參照完整性，驗證再刪帳號；不得使用級聯刪除達成需求。
- Account Delete、Claim、Assign 等請求都要驗證權限、並發與失敗邊界。未完成 PO 變未指派不代表 PO 正式狀態改變。
- 本文件不主張保存完整「管理員權限變更歷史」或另建企業 RBAC 引擎；安全事件是否須保留由既有 Security/Audit 契約與實作風險稽核決定。

## 4. 文件 RAG 的安全限制

- **文件級 ACL 暫不實作**，不因此取消基本後端認證授權及敏感資料出站政策。
- 能在 MySQL 內搜尋原文，不代表已取得向 Google Gemini Chat/Embedding 傳遞原文的授權；任何準備外送的內容仍須符合已核准文檔及既有 outbound policy。
- 禁止把「單一 ADMIN 業務角色」理解為無條件允許上傳敏感公司真實資料、外送 Google，或公開高權限 Demo 帳號。

## 5. 驗收矩陣

- 不具 canManageUsers 的正常採購帳號，採購業務 API 成功，人員管理 API 403。
- 最後一個有效帳號管理員刪除/停用操作必須失敗；含併發測試。
- 永久刪除 PO 負責人，未完成 PO 仍在且未指派；可合法認領，稽核姓名快照不消失。
- 已完成與已取消 PO 的歷史操作者仍可顯示，資料庫 foreign key／Unique／Transaction 無損。
- Demo 僅初始化 admin；未取得 AWS 權限決策前，不進行公網開放。
