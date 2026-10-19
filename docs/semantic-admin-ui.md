# Semantic 管理員 UI 操作與驗收紀錄

## 管理入口與權限

登入 ADMIN 後開啟 **Documents**。文件清單和明細沿用既有 `/documents` API；ADMIN 另外會看到「Semantic 索引管理」。`PROCUREMENT_MANAGER` 共用展示帳號、其他業務角色及未登入使用者沒有管理入口，實際授權仍由 Backend Spring Security 保護。

目前無需新 Route 或 Sidebar 項目：Semantic 操作依賴文件清單／明細與 AI Query，放在 Documents 工作區可避免管理員在文件流程間切換，也不改變現有 Route 權限。未授權使用者即使自行呼叫 API，仍須由 Backend 拒絕。

## 建議步驟與正式 API

1. 在 Documents 選取文件。UI 顯示 Document ID、Title、Type、Supplier、Version 與 Documents API 的 Status。只有 `READY` 可進行出站核准／排除；Document READY 不代表 Semantic 已審核或索引。
2. **核准出站**或**排除出站**前確認操作。核准可能將符合政策的文件片段送至外部 Embedding Provider。UI 使用 `POST /api/v1/semantic-index/documents/{id}/approve-outbound` 或 `POST /api/v1/semantic-index/documents/{id}/exclude-outbound`，呈現 Backend 本次回應的 version、sourceHash、approvedBy、status、reason。`REQUIRES_REVIEW` 會保留原狀，不會被 UI 改成 APPROVED。
3. 在 Semantic 索引管理設定 `afterId`（預設 0）與 `limit`（預設 25，最大 100），明確執行一批。對應 `POST /api/v1/semantic-index/backfill?afterId=…&limit=…`。UI 呈現本批 READY、FAILED、SKIPPED、REQUIRES_REVIEW、`lastId`、`morePossible`；仍有下一批時以回傳的 `lastId` 作為下一批 `afterId`。不自動循環。FAILED 必須依 reason／服務狀態處理，不會一律建議重按 Backfill。
4. 執行 **讀取目前 Readiness**，對應 `GET /api/v1/semantic-index/readiness`。顯示 READY、SKIPPED、FAILED、REQUIRES_REVIEW、PENDING、Model ID、Dimensions、Generation Key 與 Findings。此回應是即時檢查，`passed=true` 不表示有已保存 Audit，也不能確認先前的 Approval／Audit POST。
5. 確認後執行 **Gate Audit**，對應 `POST /api/v1/semantic-index/gate-audit`。UI 顯示該次回應的 passed、auditId、model、dimension、generation、計數與 Findings。Audit PASS 不會啟用 Semantic Read Switch。只有在符合 Runbook Gate 條件後，由授權維運人員依 `docs/semantic-rag-runbook.md` 顯式設定 `SEMANTIC_READ_ENABLED=true` 並重啟 Backend；UI 不讀寫此環境設定。
6. 文件明細可展開既有 AI Query。查詢以 Backend 結果為準，需核對 Source 的 Document ID、Version、Chunk、Offset 和相關摘錄。Gate PASS 不保證特定文件一定命中；證據不足或 Provider 錯誤不能被 UI 改成肯定回答。

## 操作狀態與可查核界線

| 操作／狀態 | UI 可確認的來源 | 限制與後續 |
| --- | --- | --- |
| Document Status | `GET /api/v1/documents/{id}` | 這是文件業務狀態，不包含持久化 Semantic Review 狀態。 |
| Outbound Review 成功 | 該次 approve/exclude POST 的 Review 回應 | 不代表未來版本／來源仍相同；來源版本變更後重新審核。沒有 Review 查詢端點。 |
| Backfill 成功回應 | 該次 Backfill Batch 回應 | Readiness 可重新計算目前索引狀態，但無法證明發生逾時的特定批次是否完成。 |
| Readiness | `GET readiness` 的即時 GateReport | 不保存 Audit，也不證明先前 POST 結果。 |
| Audit 成功回應 | 該次 `POST gate-audit` 回應含 auditId | 沒有 Audit 查詢端點；逾時後即使 Readiness PASS，也不能證明 Audit 已保存。 |
| 401／403／404／409／500／503／逾時 | 共用 Axios 與 `toApiError()`；錯誤顯示 traceId（若後端有回傳） | Mutation 遇 409、5xx、逾時或斷線時保留 UNKNOWN 並禁止盲目重送。先查核後端索引／稽核紀錄；只有完成人工查核後才可在該頁解除防重鎖定。 |

若查核能力不足，維持 UNKNOWN；不得以 Document READY、Readiness PASS 或錯誤消失推定審核／稽核成功。按鈕 Loading 和同步 Ref 防止連點及 React 重繪造成重送。UNKNOWN 防重鎖定存在本分頁 `sessionStorage`，重新載入仍會保留；Outbound Review 以文件 ID＋Version 鎖定。只有明確確認已人工查核後，才能解除鎖定。切換文件會清除前一份文件的回應顯示。

## 第六份文件 Browser 驗收步驟（交接 SP-910 #117）

在隔離的本機 Demo 環境，以 ADMIN 完成下列操作並記錄實際時間、HEAD SHA、文件 ID、Version、Gate JSON、Provider／模型與來源資料；不得使用正式／共用資料庫，也不得以 Fake Provider 或 API／CLI 代替 Browser E2E：

1. 確認 `samples/documents/ui-upload-sup-005-v1.txt` 尚未建立為第六份文件；此 Host 路徑維持不變，不同於 Demo container 的 `/app/samples/demo-documents` mount；以授權上傳角色經 Documents UI 上傳並執行既有 Ingestion。
2. 確認 Backend 文件狀態 READY，再以 ADMIN 在文件明細核准出站；記錄 sourceHash 與實際 Review 回應。
3. Backfill 使用 UI 顯示的 `lastId` 逐批進行，直到 Backend 回 `morePossible=false`；以 Readiness Findings 核對所有狀態與原因。
4. 執行正式 Gate Audit，記錄 auditId、passed、generation、model、dimension 與 Findings。依 Runbook 在外部操作 Semantic Read Switch，勿將 Audit PASS 當成已切換。
5. 於文件明細展開既有 AI Query，詢問「SUP-005 應何時回報季節性交期變更？」並核對答案與第六份文件 Version 1.0、chunk／offset、相關摘錄。記錄 Gate 恢復所需實際時間與新增 READY 文件造成的暫時 Gate 關閉。
6. 若環境未配置真實 Gemini／隔離 pgvector，記錄 `BLOCKED` 或 `NOT RUN` 及解除條件；這項真實 Provider 與完整 Browser E2E 屬於 #117，不能標成 #112 的 UI 測試 PASS。

