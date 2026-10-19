# SupplyPilot

SupplyPilot 是採購與供應鏈決策平台。Backend 以 Java 21、Spring Boot、MySQL、Flyway 與 PostgreSQL/pgvector 建置；Frontend 使用 React、TypeScript、Vite 與 Nginx。

## Docker 啟動方式

根目錄 `compose.yaml` 是 Production 與 Demo 共用的唯一基底，包含 MySQL、pgvector、Backend 和 Frontend 四個服務。Production 預設只在本機回環位址提供網站，資料庫連接埠不會發布到主機。

### Production

1. 將 `.env.example` 複製為 `.env`，填入 Production 專用資料庫帳密、至少 32 bytes 的 Base64 JWT Secret，以及 exact `FRONTEND_ORIGIN`。請人工維護 `.env`；此 Repository 不會建立、覆寫或輸出它。
2. 啟動：

   ```powershell
   docker compose up -d --build
   ```

3. 開啟 `http://127.0.0.1:8080`，並以 `docker compose ps` 確認四個服務為 healthy。Backend 容器內的健康端點是 `/actuator/health`，不發布到主機。

全新且 `users` 表為空的 Production 資料庫會由 Backend 建立一個 `admin` / `admin` 管理員，使用 BCrypt 雜湊並標示為必須變更密碼。首次登入後，Backend 只允許讀取登入者資訊和修改密碼；修改完成需用新密碼重新登入。已有任何使用者時，Bootstrap 不建立、不覆寫帳號。若既有資料庫缺少管理員，依 `docs/SP-903-DOCKER-RELEASE-CANDIDATE.md` 的受控人工恢復程序操作。

`admin/admin` 僅適合受控的首次本機啟動。把網站公開給外部使用者前，必須完成新密碼登入與一般管理 API 驗證；不得讓仍使用初始密碼的環境對外開放。

### Demo

1. 將 `.env.demo.example` 複製為 `.env.demo`，設定與 Production 不同的 DB 帳密、JWT Secret、`DEMO_ACCOUNT_PASSWORD` 和 Gemini 設定。不要把 `.env.demo` 提交到 Git。
2. 啟動：

   ```powershell
   docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
   ```
3. 開啟 `http://127.0.0.1:8081`。

五個 Demo 基線帳號 `admin`、`manager`、`buyer`、`warehouse`、`planner` 共用 `.env.demo` 的 `DEMO_ACCOUNT_PASSWORD`。密碼在 Backend 建立時載入；修改 `.env.demo` 後使用下方標準 Compose `down` 再 `up -d --build`，不要用 `docker compose restart` 更新環境變數。`down` 預設保留 Named Volume，禁止加 `-v`。新密碼生效、舊密碼失效；不會重新 Seed，文件與索引由 Named Volume 保留。已核發 JWT 依原有效期運作。Issue #45 已取消即時密碼熱更新，改為以 Compose 重建 Backend 驗收。Production 不啟用 Demo 認證元件；Docker 管理者可以檢視 Container Environment，因此 Demo 密碼不可與正式企業密碼共用。部署限制與驗收狀態見 `docs/SP-903-DOCKER-RELEASE-CANDIDATE.md`。

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

管理員登入後可在側邊選單開啟「人員管理」，建立帳號、指定既有角色、設定初始密碼，以及調整顯示名稱和啟用狀態。一般人員不會看到此頁；Backend 仍會以 ADMIN 權限檢查拒絕未授權請求。一般新密碼維持 6 至 100 個字元。

## 本機開發與驗證

- Backend：`cd backend; .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local`
- Frontend：`cd frontend; npm install; npm run dev`
- Backend tests：`cd backend; .\mvnw.cmd verify`
- Frontend：`cd frontend; npm run lint; npm test; npm run build`
- Compose 結構驗證：見 `docs/SP-903-DOCKER-RELEASE-CANDIDATE.md`。驗證時使用隔離的假憑證，不要輸出未遮蔽的 Compose 設定或 Container Environment。

更多 Profile、Environment Variable 及 Secret 規則見 `docs/environment-configuration.md`。
