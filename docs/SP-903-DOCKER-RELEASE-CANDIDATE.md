# SP-903 Production / Demo Docker 部署

## Compose 架構

`compose.yaml` 是唯一服務基底，定義 MySQL、PostgreSQL/pgvector、Spring Boot Backend、React/Nginx Frontend。兩個 Web Container 使用相同 Dockerfile 建置方式、Health Check、資源限制與網路設定。

Production 預設使用 `prod` Profile、固定的 `supplypilot_prod` / `supplypilot_semantic_prod` 資料庫、專屬專案 Volume、`127.0.0.1:8080` 前端入口及內部資料網路。資料庫埠不發布到主機。Production 不掛載 Demo 環境檔，不啟用 Demo Seeder。

Demo 只用 `compose.demo.yaml` 覆蓋必要差異：Compose Project、Profile、資料庫名稱、Demo 文件 Storage、基線密碼來源及 Frontend Port。Demo 專案名稱和三個 named volume 保留舊 SP-903 名稱，讓原 Demo 資料繼續使用；資料庫憑證若與舊 Volume 不符，受控啟動器會在啟動 Backend 前拒絕繼續。不得為了解決憑證錯誤移除 Volume 或 Reset 資料。

開發資料庫使用 `compose.dev.yaml` 的 `supplypilot-dev-v2` 專案與新 Volume，避免接管先前 `compose.yaml` 建立的開發 Volume。舊開發資料仍留在 Docker，不會由這份配置修改。

## 環境檔與 Secret

- `.env.example` 是 Production 範本；使用者人工複製並維護 `.env`。
- `.env.demo.example` 是 Demo 範本；使用者人工複製並維護 `.env.demo`。
- Production 與 Demo 的 MySQL、PostgreSQL、JWT、Demo 密碼必須分開設定。
- `.env`、`.env.demo` 及其他 `.env.*` 都由 `.gitignore` 和 `.dockerignore` 排除。只允許範本 `.env.example` 進 Git；建置 Context 不會包含環境檔。
- 標準 Production 啟動使用 Compose 自動讀取根目錄 `.env`：

  ```powershell
  docker compose up -d --build
  ```

- 標準 Demo 啟動明確指定 `.env.demo` 和 Override：

  ```powershell
  docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
  ```

Compose 的 Process Environment 優先於 `--env-file`。執行標準命令前，確認目前 PowerShell 工作階段沒有同名的 DB、JWT、Demo 或 Compose Project 環境變數；受控 Demo 啟動器會清除並在結束時還原插值用的 Process Environment。Compose Project 名稱、資料庫名稱和 Demo volume 名稱由 Compose 檔案定義，避免憑證外的 shell 變數改變資料位置。

Demo Backend 以唯讀 Compose Secret 掛載 `.env.demo`，每次基線帳號登入時重新解析 `DEMO_ACCOUNT_PASSWORD`。這滿足檔案儲存後下一個登入請求讀取新密碼的行為，沒有重新 Seed，也不把密碼寫入業務資料作為登入同步條件。此本機 Secret source 是整份 `.env.demo`，因此 Backend 容器可讀取檔案中的資料庫 root 憑證及其他 Demo Secret。僅在可信任 Docker 主機執行；Production Compose 沒有此掛載。容器檔案以唯讀模式提供，Backend 不會將其內容記錄或回傳。Windows Docker Desktop 對直接編輯及原子替換的實測結果須以本次驗收記錄為準，見本文末的測試狀態。

資料庫密碼若錯誤，資料庫應用程式登入驗證會失敗，Backend 不會啟動 Flyway 或 Seeder。MySQL `mysqladmin ping` 及 PostgreSQL `pg_isready` 只代表伺服器可回應，不足以證明應用程式憑證正確。標準 Compose 會依資料庫 Health Check 控制 Backend 啟動；Windows 受控啟動器另外在 Backend 前實際驗證兩個應用程式帳號。

Docker daemon 管理者可檢視 Container 設定和掛載的 Secret。請勿在終端輸出未遮蔽的 `docker compose config`、`docker inspect` 或環境檔內容。

## Production 初始管理員

資料庫 migration 新增 `users.must_change_password`。Production 啟動後以 MySQL named lock 序列化 Bootstrap：只有 `users` 表完全空白時，建立一個 `admin` 帳號，使用現有 BCrypt `PasswordEncoder` 雜湊 `admin`，角色 `ADMIN`、狀態 `ACTIVE` 並設定必須改密碼。第二次啟動不重設帳號密碼；資料庫已有任何使用者時不建立或覆寫 Admin。

初始登入取得的 JWT 帶有必須改密碼狀態及受限權限。只有 `GET /api/v1/auth/me` 和 `POST /api/v1/auth/change-password` 可使用該權杖。新密碼遵守 6 至 100 字元；成功後原權杖仍維持受限狀態，使用者需重新登入。Backend 只儲存新密碼 BCrypt 雜湊；錯誤訊息及日誌不包含密碼。

若資料庫有既有使用者但沒有 Admin，Bootstrap 會略過。受控恢復需先限制 Production 網路入口、確認資料庫與目標使用者、取得可還原的備份，由授權 DBA 透過受控管理連線使用 BCrypt `PasswordEncoder` 產生雜湊，再為已核准帳號設定 `ADMIN` 角色；完成後核對該帳號登入及權限並記錄操作者。不得建立匿名管理員 Endpoint，也不得在已有人員的 DB 自動插入高權限帳號。

`admin/admin` 是首次本機初始化用的指定憑證，不適合長期或公開使用。公開部署前必須登入修改密碼、以新密碼重新登入，確認初始權杖不能呼叫人員管理 API，並確認新權杖只能由真正 ADMIN 使用。

## 人員管理

`ADMIN` 可透過 Frontend「人員管理」查看清單、新增帳號、設定顯示名稱／初始密碼／既有角色、修改顯示名稱和角色，以及啟用或停用帳號。既有 `POST/GET/PATCH /api/v1/users` 契約與 Backend `@PreAuthorize("hasRole('ADMIN')")` 維持權威；Frontend 依登入角色顯示管理頁。一般帳號的密碼最低長度維持 6 字元。

## 首次 Demo 啟動、重啟與舊資料

既有 SP-903 Demo 使用 `compose.release-candidate.yaml` 和 `.env`，其三個 volume 名稱為：

```text
supplypilot-sp903-local-demo-mysql
supplypilot-sp903-local-demo-pgvector
supplypilot-sp903-local-demo-documents
```

新的 `compose.demo.yaml` 保留這些 volume 名稱與原 project 名稱，能沿用 Compose 標籤確認歸屬的舊資源。首次切換前，人工把舊 Demo 專用的 DB/JWT/Demo 設定移至 `.env.demo`，並確認 DB 帳密與既有 Volume 初始化時一致。不要把 Production 的 `.env` 直接用作 Demo 設定。舊開發 Volume 名稱不會被 `compose.dev.yaml` 引用。

Windows 上的受控啟動順序：

```powershell
pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1
```

啟動器會先檢查 `.env.demo` 必要鍵、重複鍵、JWT 格式、忽略規則、Docker Engine、Compose Volume/Container 歸屬和 Compose 結構；接著只啟動兩個資料庫、以實際應用程式帳密執行 `SELECT 1`，成功後才啟動 Backend 和 Frontend。啟動器沒有 Reset 或刪除資源的路徑。四個服務健康後，重複執行相同命令可用來恢復或重啟 Stack。

安全停止與恢復：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml stop
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d
```

也可重複執行受控啟動器。Docker Desktop 或 Windows 重啟後執行 `up -d`／啟動器，Compose 的 `unless-stopped` 會恢復容器。資料庫、文件和 pgvector 的資料使用 named volume 持久化。禁止使用 `down -v`、`docker volume rm`、`docker volume prune`，也不要自行執行 Demo Reset。

若舊 Volume 的 Compose 標籤或帳密不符，啟動器會拒絕接管；人工檢查 Docker Volume/Container metadata 並驗證原應用程式帳密後，再依據確認後的相容路徑處理。不要靜默建立新空資料庫並聲稱舊資料已恢復。SP-910 r4 的專案和 Volume 不在本 Compose 的資源清單中。

## 驗證命令與範圍

請使用臨時測試環境檔驗證 Compose，勿讀取或輸出實際 `.env`：

```powershell
docker compose --env-file $env:TEMP\supplypilot-prod-test.env config --quiet
docker compose --env-file $env:TEMP\supplypilot-demo-test.env -f compose.yaml -f compose.demo.yaml config --quiet
```

測試檔應只含隔離的假憑證，Demo 測試檔另設 `DEMO_ENV_SOURCE` 指向隔離的測試 Secret 檔。`docker compose config` 成功不代表 Runtime 啟動、密碼或 Volume 相容性通過。不要用這些驗證檔啟動真實服務。

| 驗收項目 | 狀態 |
|---|---|
| Production / Demo Compose 結構驗證 | 隔離假憑證 `docker compose config --quiet` 均成功；Production 四服務容器建置成功 |
| Production Admin 真實 Container 登入及改密碼 | 隔離 Production Stack 通過：初始登入 200、受限 token 呼叫人員 API 為 403、改密碼 204、舊密碼登入 401、新密碼登入 200、人員清單 200、新增 201、更新 200 |
| Demo 五角色登入及變更檔案後即時生效 | 未執行 Demo Stack 的五角色 API 驗證。Java 認證測試 4/4 通過；Windows Docker Desktop 假 Secret 檔直接覆寫及原子替換可由 Container 讀到新內容 |
| Fresh Start、重啟及三種資料持久化 | 隔離 Production Stack 停止並重新啟動後，管理員登入與 MySQL 人員資料保留，pgvector 測試資料保留；文件 Volume 內測試標記通過 Backend Container 停止／啟動後仍存在。未驗證既有 Demo Volume 的真實資料相容性 |
| AWS、HTTPS、Gemini、Semantic Gate、午夜 Reset | 不屬於此本機 Compose 實作證據，依 Issue #45 / #117 後續 Gate 驗收 |
