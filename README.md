# SupplyPilot

SupplyPilot 是採購與供應鏈決策平台。Backend 以 Java 21、Spring Boot、MySQL、Flyway 與 PostgreSQL/pgvector 建置；Frontend 使用 React、TypeScript、Vite 與 Nginx。

## 目前開發版本：V2

> **版本狀態（2026/10/21）：V2 需求規格已經 [PR #150](https://github.com/liujiawei-tw/supplypilot/pull/150) 合併至 main，正在進入 V2 功能開發；根目錄以下操作說明仍主要適用於目前已實作的 V1 系統。**
> **不要將 V2 規格決策或已建立的 GitHub Issues 誤認為功能已完成、通過 E2E，或已部署 AWS。**

- **V1（現有程式基線）：** Java / Spring Boot、MySQL、PostgreSQL/pgvector、React 與 Docker Compose，仍保有原本 Demand、Recommendation、V1 多角色以及目前 Semantic Gate 的已實作流程。
- **V2（本輪開發目標）：** 直接建立 PO DRAFT 與八階段 Kanban；採購單取消、整筆驗收與人工付款確認、獨立的期望到貨日及供應商承諾交期、採購單搜尋；多帳號單一 ADMIN 業務角色、獨立人員管理授權；MySQL + pgvector 混合檢索、來源原文引用、管理員核准後逐文件自動索引與 Demo 基線恢復。這些是**待依 Issue 實作及驗收的需求**，不是目前所有功能均可操作的宣稱。
- **規格權威：** [V2 需求總綱](docs/v2/README.md)、[V1→V2 差異與 Issues](docs/v2/migration-and-issue-map.md)；帳號、PO／看板、RAG、UI、Demo 之詳細契約見 [docs/v2/](docs/v2/README.md)。V1 規格與 V2 明確衝突時，以已確認的 V2 需求為準；V2 未覆蓋之安全、交易及驗證限制仍須遵守。
- **實作追蹤：** [V2 Issues #136～#149](https://github.com/liujiawei-tw/supplypilot/issues?q=is%3Aissue+is%3Aopen+V2)；既有 [AI 回答格式 #126](https://github.com/liujiawei-tw/supplypilot/issues/126) 與 [AI 多條件查詢 #127](https://github.com/liujiawei-tw/supplypilot/issues/127) 分開追蹤。**狀態以 GitHub Issues、已合併 PR 及實際測試證據為準。**
- **交付順序：** V2 功能逐張 Issue／Code Review／PR → 本機測試及 Docker／真實 AI/RAG 驗收 → [AWS Demo #133](https://github.com/liujiawei-tw/supplypilot/issues/133) → 最終展示影片。AWS 公開 Demo 共用帳號權限**延後至部署階段決策**，目前不自動部署或對外授權。

**V2 本輪明確不新增：** 文件級 ACL、PDF 頁碼定位、合約比較、Supplier Ranking、庫存安全庫存警示、採購單 CSV/Excel/PDF 匯出、PO 附件關聯及通知中心；請勿依舊版 V1 提案自行恢復已取消的功能。

## V2 Agent 協作（Matt Pocock Skills）

SupplyPilot V2 維持 **Codex 實作／測試／Commit/Push → GPT 遠端獨立 Code Review／Final PASS／建立 PR → 使用者人工 Merge** 的原有流程。Matt Pocock Skills 僅協助 Codex 的 TDD、除錯、單一 Issue 實作及內部 Review；不啟用原版跨 Issue `implement-spec` 整合／自動開 PR 模式。

- **協作與新 GPT 對話接續：** [Matt Pocock Skills × Codex × GPT 協作交接](docs/v2/matt-pocock-codex-gpt-handoff.md)
- **強制規則：** [AGENTS.md](AGENTS.md)；每張 V2 Issue 依其核准 Scope 與前置依賴執行。
- **狀態聲明：** 文件導入不是本機 Codex CLI 安裝成功、功能實作完成、測試或部署通過的證據。是否已安裝應由使用者的 Windows 環境實際驗證。

## Docker 啟動方式

> **以下為目前已實作 V1 基線的操作方式，非 V2 已完成證明。** V2 各模組的資料庫、角色、種子資料與畫面需依相關 Issue 實作後，才可更新此節為 V2 實際操作說明。

根目錄 `compose.yaml` 是 Production 與 Demo 共用的唯一基底，包含 MySQL、pgvector、Backend 和 Frontend 四個服務。Production 與 Demo 的網站和 MySQL 都綁定主機回環位址 `127.0.0.1`；預設 MySQL 主機埠分別為 `13306` 和 `13307`。MySQL 保留內部 `data` 網路，另接專屬 bridge network 供主機埠發布；其他服務不加入該網路。這個 bridge network 允許 MySQL 容器對外連線，pgvector 則仍留在內部資料網路且不發布主機埠。Docker Engine 28.0.0 之前的版本有同一區域網路網段仍可能連到 localhost 發布埠的例外；發布環境應使用 Docker Engine 28.0.0 以上版本，或另以主機防火牆限制連線。

### Production

1. 將 `.env.example` 複製為 `.env`，填入 Production 專用資料庫帳密、至少 32 bytes 的 Base64 JWT Secret，以及 exact `FRONTEND_ORIGIN`。請人工維護 `.env`；此 Repository 不會建立、覆寫或輸出它。
2. 啟動：

   ```powershell
   docker compose up -d --build
   ```

3. 開啟 `http://127.0.0.1:8080`，並以 `docker compose ps` 確認四個服務為 healthy。Backend 容器內的健康端點是 `/actuator/health`，不發布到主機。

MySQL Workbench 使用 Hostname `127.0.0.1`、Port `13306`、`.env` 的 `DB_USERNAME`／`DB_PASSWORD`，Schema `supplypilot_prod`。可用 `.env` 的 `PRODUCTION_MYSQL_HOST_PORT` 調整主機埠；容器內 Backend 仍連 `mysql:3306`。

全新且 `users` 表為空的 Production 資料庫會由 Backend 建立一個 `admin` / `admin` 管理員，使用 BCrypt 雜湊並標示為必須變更密碼。首次登入後，Backend 只允許讀取登入者資訊和修改密碼；修改完成需用新密碼重新登入。已有任何使用者時，Bootstrap 不建立、不覆寫帳號。若既有資料庫缺少管理員，依 `docs/SP-903-DOCKER-RELEASE-CANDIDATE.md` 的受控人工恢復程序操作。

`admin/admin` 僅適合受控的首次本機啟動。把網站公開給外部使用者前，必須完成新密碼登入與一般管理 API 驗證；不得讓仍使用初始密碼的環境對外開放。

### Demo

1. 將 `.env.demo.example` 複製為 `.env.demo`，設定與 Production 不同的 DB 帳密、JWT Secret、`DEMO_ACCOUNT_PASSWORD` 和 Gemini 設定。不要把 `.env.demo` 提交到 Git。
2. 啟動：

   ```powershell
   docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
   ```
3. 開啟 `http://127.0.0.1:8081`。

Demo MySQL Workbench 使用 Hostname `127.0.0.1`、Port `13307`、`.env.demo` 的 `DB_USERNAME`／`DB_PASSWORD`，Schema `supplypilot_demo`。可用 `.env.demo` 的 `DEMO_MYSQL_HOST_PORT` 調整主機埠；容器內 Backend 仍連 `mysql:3306`。若主機的原生 MySQL 服務正在使用 `3306`，這兩個預設埠可避免衝突。

**目前 V1 Demo** 的五個基線帳號 `admin`、`manager`、`buyer`、`warehouse`、`planner` 共用 `.env.demo` 的 `DEMO_ACCOUNT_PASSWORD`。密碼在 Backend 建立時載入；修改 `.env.demo` 後使用下方標準 Compose `down` 再 `up -d --build`，不要用 `docker compose restart` 更新環境變數。`down` 預設保留 Named Volume，禁止加 `-v`。新密碼生效、舊密碼失效；不會重新 Seed，文件與索引由 Named Volume 保留。已核發 JWT 依原有效期運作。Issue #45 已取消即時密碼熱更新，改為以 Compose 重建 Backend 驗收。Production 不啟用 Demo 認證元件；Docker 管理者可以檢視 Container Environment，因此 Demo 密碼不可與正式企業密碼共用。部署限制與驗收狀態見 `docs/SP-903-DOCKER-RELEASE-CANDIDATE.md`。

若密碼含 `$`，在 `.env.demo` 將整個值用單引號括起來，例如 `DEMO_ACCOUNT_PASSWORD='example$literal'`；Compose 對 `.env` 單引號值不做插值。Compose 插值來源的 Shell Environment 優先於 `--env-file`。在 PowerShell 可只檢查變數是否存在而不顯示值，並清除目前工作階段的覆蓋值：

```powershell
Test-Path Env:DEMO_ACCOUNT_PASSWORD
Remove-Item Env:DEMO_ACCOUNT_PASSWORD -ErrorAction SilentlyContinue
```

若 `Test-Path` 回傳 `True`，先確認目前 PowerShell 工作階段設定了同名變數；`Remove-Item` 只移除該 Shell 工作階段的變數，不會修改 `.env.demo`。Compose 的 `--env-file .env.demo` 明確選用 Demo 插值檔，但同名 Shell 變數仍優先。不要使用 `docker compose config` 或 `docker inspect` 將含秘密的完整輸出貼到 Log 或證據中。文件來源使用唯讀 `./samples/documents` bind mount；上傳文件寫入 `supplypilot-sp903-local-demo-documents` Named Volume。

### 開發環境

舊開發 Volume 不會由新設定接管。新開發 MySQL/pgvector 使用獨立的 `supplypilot-dev-v2` 專案及 Volume：

```powershell
docker compose --env-file .env -f compose.dev.yaml up -d
```

接著依 `backend/src/main/resources/application-local.yml` 啟動 Backend。正式版與 Demo 使用各自獨立的專案、資料庫、持久化 Volume、憑證和前端連接埠。

## 安全停止與重新啟動

保留資料並停止 Production：

```powershell
docker compose stop
```

保留資料並重新啟動：

```powershell
docker compose up -d
```

停止並重新建立 Demo Container／Network（保留 Volume）：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml down
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
```

Demo 密碼變更後也使用上述 `down → up`。Production 使用 `docker compose down` 與 `docker compose up -d --build`。`docker compose restart` 只重啟既有 Container，不會重新讀取環境變數；`docker compose up` 會依服務設定及 Image 變更重建必要 Container。不要加 `-v`，也不要移除或清理 Volume；Named Volume 才保存資料。

文件原檔存於文件 Storage Volume；PostgreSQL/pgvector 索引存於獨立 pgvector Volume。Demo 使用既有 SP-903 的明確 Volume 名稱以延續資料。開發環境切換至 v2 Volume，舊開發 Volume 保留在 Docker 中，不會自動遷移。

## 人員管理

> **現行 V1 行為：** 以下是已實作的多角色、人員管理操作。V2 將改為多帳號、單一 `ADMIN` 採購業務角色，並以獨立授權控制人員管理；尚未完成時請勿把新規格當作已存在的操作能力。

管理員登入後可在側邊選單開啟「人員管理」，建立帳號、指定既有角色、設定初始密碼，以及調整顯示名稱和啟用狀態。一般人員不會看到此頁；Backend 仍會以 ADMIN 權限檢查拒絕未授權請求。一般新密碼維持 6 至 100 個字元。

## 本機開發與驗證

- Backend：`cd backend; .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local`
- Frontend：`cd frontend; npm install; npm run dev`
- Backend tests：`cd backend; .\mvnw.cmd verify`
- Frontend：`cd frontend; npm run lint; npm test; npm run build`
- Compose 結構驗證：見 `docs/SP-903-DOCKER-RELEASE-CANDIDATE.md`。驗證時使用隔離的假憑證，不要輸出未遮蔽的 Compose 設定或 Container Environment。

更多 Profile、Environment Variable 及 Secret 規則見 `docs/environment-configuration.md`。

## V2-102 Migration 與 Docker Compose

Flyway V24 在每個 Backend 啟動時依既有設定套用。MySQL DDL 可能逐句自動提交，不能視為完整可交易回滾；部署前須依既有備份／復原 Runbook 操作，並確認 Flyway 與應用版本一致。V24 只新增快照與指派欄位、回填現有值及調整 User FK，不清空或重建資料庫。

本次盤點到 `compose.yaml`（預設/Production）、`compose.dev.yaml`（Local DB）、`compose.demo.yaml`（Demo overlay）。它們共用同一 Backend Dockerfile／Flyway classpath，V24 未增加環境變數、公開埠、Volume 或啟動命令，因此無需改 Compose YAML；驗證 Demo 時須以 `docker compose -f compose.yaml -f compose.demo.yaml config --quiet` 組合檢查。既有 volume 名稱與持久化設定維持不變。
