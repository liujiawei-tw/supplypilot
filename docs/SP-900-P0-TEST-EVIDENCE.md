# SP-900 P0 Backend Test Gate 驗收證據

## 1. 驗收目標與基準

- Issue：[SP-900 #42](https://github.com/liujiawei-tw/supplypilot/issues/42)
- Repository：`liujiawei-tw/supplypilot`
- Base Branch：`main`
- Base SHA：`7321b1ed33536e6673e687320902a32f88abb562`
- Feature Branch：`codex/sp-900`
- 盤點日期：2026-10-13（Asia/Taipei）
- 目標：逐項證明 P0 核心商業規則、交易原子性、授權邊界與併發正確性均有有效測試保護；不追求 100% coverage。

`docs/e9-quality-release-decisions.md` 尚未合併 `main`。本次僅參考 `docs/e9-planning-20261013` 的品質證據界線，未合併或複製該分支變更。SP-901、SP-903、SP-904 與真實 Gemini／Semantic RAG Demo Gate 均不屬於本 Issue。

## 2. P0 Test Coverage Matrix

`Initial Status` 表示本次實際讀取 production code、測試方法與斷言後的盤點結果；不代表已於本分支重跑成功。`Final Status` 需以本文件第 5 節的實際執行證據更新。

| P0 Requirement | Specification | Production Code | Test Class / Method | Assertion Evidence | Initial Status | Action | Final Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Net Requirement | Testing Strategy §4；Acceptance Criteria §7–9 | `NetRequirementCalculator.calculate`、`determineShortageStatus` | `NetRequirementCalculatorTests` 全部 9 tests | 公式 `100+20-30-10=80`、raw=0、庫存充足 clamp 為 0、小數精度、正負與 null 邊界 | COVERED | 無重複新增 | PASS |
| Purchase Quantity | Testing Strategy §5 | `PurchaseQuantityCalculator.calculate`、`calculateTotalPurchaseCost` | `PurchaseQuantityCalculatorTests` 全部 9 tests | MOQ、package multiple 向上取整、無包裝倍數、精確倍數、小數倍數、BigDecimal 成本與非法輸入 | COVERED | 無重複新增 | PASS |
| Supplier Hard Constraints | Testing Strategy §6；Fixed Clock 規則 | `SupplierConstraintEvaluator.evaluate` | `SupplierConstraintEvaluatorTests` 全部 12 tests | 供應商／關聯停用、交期不可行與等於邊界、零 lead time、錯誤 material/supplier 關聯、固定日期 | COVERED | 無重複新增 | PASS |
| Supplier Ranking | Testing Strategy §7 | `SupplierScoreCalculator.calculate`、`SupplierRankingService.rank` | `SupplierRankingServiceTests` 全部 14 tests | 權重與正規化、只排名 qualified candidate、全精度、total cost→lead time→reliability→supplier code deterministic tie-break | COVERED | 無重複新增 | PASS |
| PurchaseOrder Lifecycle | Testing Strategy §8；Acceptance Criteria §11–18 | `PurchaseOrder.approve`、`reject`、`markOrdered`、`receive` | `PurchaseOrderTests` lifecycle tests；`PurchaseOrderLifecycleIntegrationTests` | 合法轉換、所有非法／terminal 轉換、缺交期不寫 audit、後端使用 authenticated audit、重複收貨拒絕 | COVERED | 無重複新增 | PASS |
| Inventory Domain | Testing Strategy §9；Acceptance Criteria §3、§16 | `Inventory.adjust`、`replaceSnapshot`、`receive` | `InventoryTests` 全部 5 tests | `100+50=150`、`100-30=70`、負庫存失敗且狀態不變、snapshot replace、正數 receiving 與 decimal DB 邊界 | COVERED | 無重複新增 | PASS |
| RecommendationOutcome | Testing Strategy §10；Acceptance Criteria §7–9 | `PurchaseRecommendationService.createRecommendation`、`RecommendationOutcome` | `RecommendationControllerIntegrationTests.createsPeriodEndAggregatedRecommendationWithBoundaryDemandIncomingAndRanking`、`persistsBothNonPurchaseOutcomesAndRejectsUnauthorizedRequests` | 三種 outcome 均由真實計算／候選結果產生並持久化；無合格供應商仍為 201 正常結果 | COVERED | 無重複新增 | PASS |
| IncomingInventoryQuery | Testing Strategy §17；Acceptance Criteria §10 | `PurchaseOrderIncomingInventoryQuery.calculateIncomingQuantity` | `PurchaseOrderIncomingInventoryQueryIntegrationTests` 全部 6 tests | 真實 MySQL 僅計算 ORDERED 且交期不晚於 requiredDate；排除 DRAFT／APPROVED／RECEIVED／REJECTED／late／其他 material，跨 PO 加總 | COVERED | 無重複新增 | PASS |
| Receiving Rollback | Testing Strategy §22、§44；Acceptance Criteria §16–17；Transaction Boundary Receiving | `ReceivingService.receive` | `PurchaseOrderReceivingIntegrationTests.thirdStockTransactionDatabaseFailureRollsBackEarlierFlushedWorkAndPoAudit` | 第三筆 StockTransaction DB constraint failure 後重新查 DB：三筆 Inventory 數量不變、transaction=0、PO 非 RECEIVED 且 audit 為 null | COVERED | 無重複新增 | PASS |
| Inventory Import Rollback | Testing Strategy §23；Acceptance Criteria §4–5；Transaction Boundary §11–13 | `InventoryImportValidationService.validate`、`InventoryImportConfirmService.confirm` | `InventoryImportConfirmIntegrationTests.rollsBackEveryInventoryChangeWhenLaterInventoryUpdateFails`；`InventoryOptimisticLockIntegrationTests.rollsBackImportAndKeepsBatchValidatedWhenItsInventoryWriteIsStale` | 後段 trigger failure／stale write 後重新查 DB：全部 quantity/version 保持正確、Batch 維持 VALIDATED、無 StockTransaction；重複 confirm 僅一次套用 | COVERED | 無重複新增 | PASS |
| Recommendation Snapshot Atomicity | Testing Strategy §24；Acceptance Criteria §7–9；Transaction Boundary Recommendation Snapshot | `PurchaseRecommendationService.createRecommendation` | `RecommendationControllerIntegrationTests.rollsBackHeaderAndAllResultsWhenDatabaseRejectsOneCandidateResult` | 第二候選 DB constraint failure 後 header=0、candidate rows=0；另有 snapshot immutability 與三 outcome persistence | COVERED | 無重複新增 | PASS |
| Security Role Matrix | Testing Strategy §18–20；Acceptance Criteria §13、§16、§23；Security Design Role Matrix | `SecurityConfig` 與各 service `@PreAuthorize` | `UserAuthorizationIntegrationTests`；`PurchaseOrderReceivingIntegrationTests.adminCanReceiveButOtherRolesAndAnonymousCannot`；各 Controller Integration security tests | No JWT/invalid/expired=401、wrong role=403、正確角色成功；Receiving、Inventory、PO、Recommendation、AI 與 User 高風險 endpoint 均有角色斷言；service proxy 防止繞過 method security | COVERED | 無重複新增 | PASS |
| Inventory Optimistic Lock | Testing Strategy §26；NFR §6；Validation Exception §19 | `Inventory.@Version`、`InventoryAdjustmentService`、`InventoryImportConfirmService` | `InventoryOptimisticLockIntegrationTests` 2 tests | 兩個獨立 transaction 讀同 version；僅一方 commit，另一方 409／optimistic failure；quantity/version 與 audit 最終狀態證明無 lost update | COVERED | 無重複新增 | PASS |
| PO Concurrent Receiving | Testing Strategy §27、§44；Acceptance Criteria §18 | `PurchaseOrder.@Version`、`ReceivingService.receive` | `PurchaseOrderConcurrentReceivingIntegrationTests.concurrentRequestsReadTheSamePoVersionAndOnlyOneReceiveCommits`（`@RepeatedTest(3)`） | barrier/probe 證明雙方讀相同 ORDERED/version；結果 200+409、失敗型別為 optimistic lock；PO/Inventory version 各只 +1、數量與 transactions 只增加一次、duplicate 再提交仍不變 | COVERED | 無重複新增 | PASS |
| AI Authorization / Forbidden Write Boundary | Testing Strategy §28–31；Acceptance Criteria §20–23；Security Design AI Boundary | `AiAssistantService`、`SpringAiReadTools`、`AiToolRegistry`、`AiQueryPlanner`、`AiDraftProposalService` | `AiControllerIntegrationTests`、`AiReadToolBoundaryTests`、`SpringAiProviderClientTests`、`SpringAiDraftProposalGeneratorTests`、`AiDraftProposalIntegrationTests`、`AiQueryPlannerTests.blocksForbiddenActionsBeforeAnyToolCanRun` | 401/403 與登入身分傳遞；read tools 呼叫 application/query service；模型未知工具 fail closed；AI draft 必須後端 confirm 且只產生 DRAFT；禁止 approve/reject/order/receive/adjust/user/master-data write | PARTIAL | 強化 forbidden action 列舉；將純 in-memory 安全測試 timeout 由 1 秒調整為 10 秒，避免完整 suite 負載將預期安全拒絕誤判為 timeout，安全斷言維持不變 | PASS |

## 3. 初始盤點摘要

- P0 項目總數：15
- 初始 `COVERED`：14
- 初始 `PARTIAL`：1
- 初始 `MISSING`：0
- 初始 `NOT_VERIFIED`：0
- 本次補強後 `PASS`：15
- 本次補強後剩餘 `PARTIAL`／`MISSING`：0
- 發現的缺口：AI Forbidden Write 測試未直接列舉 `Order PO` 與 `Master Data Write`，已在既有測試補上。
- 首次完整 `verify` 發現 `SpringAiDraftProposalGeneratorTests` 的 1 秒測試 timeout 在整套測試負載下會先回 `AI_TIMEOUT`，遮蔽應驗證的 `AI_INVALID_RESPONSE`。僅將該純 in-memory 測試 timeout 提高至 10 秒，未變更 production timeout、產品邏輯或安全期待值。
- 未發現需修改 production code 的規格違反。

## 4. 重要測試設計證據

### Rollback

- Receiving：以 MySQL constraint 讓第三筆 `StockTransaction` insert 失敗，確保前兩筆已執行的 Inventory 與 StockTransaction、PO status/audit 全部 rollback。
- Inventory Import：以 MySQL trigger 讓後段 Inventory update 失敗，確認先前 update、version、Batch status 全數 rollback；另以真實 stale version 證明 optimistic failure 亦維持 Batch `VALIDATED`。
- Recommendation Snapshot：以 MySQL constraint 讓其中一筆 candidate result 失敗，確認 recommendation header 與所有 result 均未提交。

### Concurrency

- Inventory：使用兩個 request/transaction 與測試 probe，證明相同 version 的其中一筆 stale write 回 409，失敗 transaction 的 audit 未提交。
- PurchaseOrder Receiving：使用兩個 request thread、兩個獨立 transaction/persistence context 與有限 timeout；`@RepeatedTest(3)` 降低偶然通過風險，並驗證 DB 最終數量、version、audit 與 StockTransaction 數量。

### Security / AI Boundary

- 高風險 API 同時驗證 401、403 與允許角色，且 service method security 可阻止直接呼叫繞過 controller。
- 自動測試注入 deterministic Fake `ChatModel`；真實 provider smoke test 保持 opt-in。
- AI read tools 只透過既有 application/query service；未知／寫入工具 fail closed。Draft proposal 必須由目前使用者明確 confirm，且仍呼叫既有 `PurchaseOrderService.createDraft()`，不能 approve/order/receive/adjust。

## 5. 測試環境與實際執行結果

| 項目 | 證據 |
| --- | --- |
| Java 版本 | OpenJDK Zulu `21.0.10` |
| Maven 版本 | Apache Maven `3.9.16`（Maven Wrapper） |
| Docker / Testcontainers | Docker Desktop client/server `29.6.1`；Testcontainers `2.0.5`；MySQL `8.4.11`；PostgreSQL `17.11`（pgvector `pg17`） |
| 精準 P0 tests | `./mvnw.cmd -B --no-transfer-progress -Dtest=<19 個 P0 test classes> test`：`140` tests、`0` failures、`0` errors、`0` skipped，`BUILD SUCCESS`，`02:34` |
| 完整 `Maven verify` | `./mvnw.cmd -B --no-transfer-progress verify`：`BUILD SUCCESS`，`05:50`，完成時間 `2026-10-13T19:17:45+08:00` |
| Tests / Failures / Errors / Skipped | `506 / 0 / 0 / 1`（由 Surefire XML 再彙總核對） |
| Skipped 原因 | `SemanticRealProviderSmokeTests.developerApiReturnsConfiguredDimension` 以 `semantic.real-smoke` system property opt-in；一般自動測試不呼叫付費 API，屬 SP-904 真實 provider gate |
| `git diff --check` | PASS（無 whitespace error） |

完整驗證的第一次嘗試在 suite 負載下，`SpringAiDraftProposalGeneratorTests.forbiddenModelToolCannotExecute` 因測試專用 1 秒 timeout 先回 `AI_TIMEOUT`，未抵達預期的 `AI_INVALID_RESPONSE` 斷言。將該純 in-memory 測試 timeout 調整為 10 秒後，先重跑相關 5 tests（`0/0/0`），再重跑完整 `verify` 取得上述成功結果；production 設定與行為未變更。

## 6. Issue Acceptance Criteria

- [x] 所有 P0 Tests Local 全綠。
- [x] Automated Integration Test 不呼叫付費 AI API。
- [x] Receiving / Import / Recommendation Rollback + Concurrency Scenarios 全綠。

## 7. 範圍界線與未解風險

- SP-901 Demo Seeder／Demo Documents／完整 E2E：未納入，亦未宣稱驗收。
- SP-903 Production images／AWS／HTTPS：未納入，亦未宣稱驗收。
- SP-904 最終發版／真實 Gemini Chat 與 Semantic RAG 雙環境 Demo Gate：未納入；`SemanticRealProviderSmokeTests` 若維持 opt-in skipped，須在最終結果明確揭露。
- 本文件是測試追溯與執行證據，不新增或改寫產品規格。
