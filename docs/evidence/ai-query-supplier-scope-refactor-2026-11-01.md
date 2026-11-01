# AI 查詢供應商範圍篩選驗證

日期：2026-11-01（Asia/Taipei）

基底 Git HEAD：`cba85498cc54c4ce915ee503c0f210bd0ac279c7`；本次修改保留在未提交工作樹。

## 變更範圍

- 移除 `/api/v1/ai/query` 檢索後逐份判斷文件摘錄支持度的 `AiAnswerEvidencePolicy` 與其專用 `EvidenceValidator`。
- 將工具狀態、複合查詢、供應商及物料代碼涵蓋規則保留於 `AiToolResultPolicy`。
- Spring AI `VectorStoreDocumentRetriever` 依明確供應商代碼加入 request-scoped metadata filter；條件包含已發布 generation、`READY`、`APPROVED`，不放寬既有出站審核條件。
- 未指定供應商代碼的查詢保留原本 generation／狀態條件。

## 精準 Maven 驗證

環境：Windows、Java 21、Spring AI 2.0.1；工作目錄 `backend`。

命令：

```powershell
.\mvnw.cmd "-DargLine=-javaagent:C:\Users\z0976\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar" "-Dtest=AiToolResultPolicyTests,AiAssistantServiceTests,SupplierScopedRagQueryTransformerTests,SpringAiRagAdvisorPipelineTests" test
```

結果：**PASS**，37 tests、0 failures、0 errors、0 skipped，exit code 0。原始 Maven log 保留於 `backend/target/ai-tool-result-and-rag-filter-focused-20261101.log`。Spring AI Advisor 測試讀取版控中的 `samples/documents/supplier-sup-003-supplier-profile-sp907-v2.txt`。

## 本機 Demo API 驗證

先以以下命令只重建及重啟 Demo backend；MySQL、pgvector、frontend 與具名 volumes 未重建：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build --force-recreate --no-deps backend
```

使用本機 Demo 管理員登入，密碼只從 `.env.demo` 在程序記憶體讀取，沒有寫入日誌；查詢使用現存 Demo 資料與向量索引。

問題：`Sup-003 服務時段`

| Endpoint | HTTP | 結果 | 來源 |
|---|---:|---|---|
| `POST /api/v1/document-queries` | 200 | `ANSWERED`；週一至週五 09:00–17:00 | 10 筆；全部 `supplierCode=SUP-003` |
| `POST /api/v1/ai/query` | 200 | `ANSWERED`；工具 `SEARCH_DOCUMENTS`，週一至週五 09:00–17:00 | 10 筆 |

## 範圍限制

本次只跑以上受影響的精準 Maven 測試及本機 Demo 查詢，沒有執行完整 backend Maven verify，也沒有驗證正式環境。答案主張仍由模型根據 Spring AI Advisor 提供的候選判斷；本次驗證證明 SUP-003 查詢被縮限至 SUP-003 Demo 文件並答對，不代表模型回答具有通用形式驗證保證。

## 瀏覽器 E2E 與前端解析修正（2026-11-02，Asia/Taipei）

第一次 Chrome E2E 使用登入中的本機 Demo 管理員，於 `http://127.0.0.1:8081/purchase-orders` 送出 `Sup-003 服務時段`，畫面顯示 `REQUEST_FAILED`。前端 nginx 同一時間記錄 `POST /api/v1/ai/query` 回 `HTTP 200`、11,968 bytes；後端記錄 provider `outcome=success`、10,771 ms。這表示請求確實由按鈕送到正確端點，故障在前端處理成功回應的階段。

根因是前端 `parseDecimalJson` 對未列入 BigDecimal 欄位清單的非整數數字丟出 `TypeError`。AI 文件來源 DTO 的 `relevanceScore` 是 Java `Double`，UI 型別則是 `number`，例如 `0.9` 會觸發此錯誤。新增回歸案例後，修正前結果為 **FAIL**：`Unexpected non-integral token for integer field "relevanceScore": 0.9`。修正後此欄位以有限 JavaScript `number` 解析，同時維持既有 BigDecimal 欄位作為字串的精度保護。

修正後驗證：

- 精準回歸命令：`npm test -- tests/sp805Api.test.ts -t "floating point AI source relevance scores"`；**1 passed、13 skipped、exit code 0**。
- 精準 lint：`npx oxlint src/api/decimalJson.ts tests/sp805Api.test.ts`；**exit code 0**。
- `docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build --no-deps frontend`；前端映像 build 成功並只重建 frontend，保留 backend、MySQL、pgvector 與 DEMO volumes。Docker build 的 `npm run build` 成功；Vite 顯示既有大型 chunk 提示。
- 再次於同一 Chrome 本機 Demo 頁面送出同一問題；UI 顯示 `ANSWERED`、`週一至週五 09:00–17:00（虛構情境設定）`，並顯示 10 筆文件來源卡片。
- 對應前端代理日誌：`POST /api/v1/ai/query` 為 **HTTP 200**、11,968 bytes；後端 provider `outcome=success`、9,285 ms。

此瀏覽器 E2E 使用本機 Demo 資料及已設定的真實 provider；沒有操作正式端。完整前端測試套件、完整 backend verify、AWS／正式環境 E2E 均未執行。
