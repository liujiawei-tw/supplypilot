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

- Demo 啟動請使用受控啟動器；它會明確使用 `.env.demo` 和兩份 Compose 檔，並維持動態密碼同步：

  ```powershell
  pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1
  ```

Compose 的 Process Environment 優先於 `--env-file`。執行標準命令前，確認目前 PowerShell 工作階段沒有同名的 DB、JWT、Demo 或 Compose Project 環境變數；受控 Demo 啟動器會清除並在結束時還原插值用的 Process Environment。Compose Project 名稱、資料庫名稱和 Demo volume 名稱由 Compose 檔案定義，避免憑證外的 shell 變數改變資料位置。

受控 Demo 啟動器讀取 `.env.demo`，只把 `DEMO_ACCOUNT_PASSWORD` 寫入 `.sp903-demo-credential/password`，再以唯讀目錄掛載給 Backend。主機同步器每 100 ms 檢查來源並以同目錄暫存檔加原子替換更新密碼檔；缺檔、格式錯誤或重複 Key 時會移除舊密碼檔，令基線登入失敗。Backend 不會掛載 `.env.demo`，也不會重啟或重新 Seed；只有後續的新登入使用新密碼，既有 JWT 到期規則不變。Production Compose 不掛載 Demo 憑證。

要啟用檔案變更即時同步，Demo 必須透過以下受控啟動器啟動；直接執行 Compose 不會啟動主機同步器。啟動器需有 Python 3、PowerShell 7 與 Docker CLI。Windows Docker Desktop 與 Linux Docker Engine 的目錄掛載行為由 `DemoCredentialFileMountTests` 以實際 Container 驗證；執行結果依本次測試記錄，見本文末的測試狀態。

資料庫密碼若錯誤，資料庫應用程式登入驗證會失敗，Backend 不會啟動 Flyway 或 Seeder。MySQL `mysqladmin ping` 及 PostgreSQL `pg_isready` 只代表伺服器可回應，不足以證明應用程式憑證正確。標準 Compose 會依資料庫 Health Check 控制 Backend 啟動；Windows 受控啟動器另外在 Backend 前實際驗證兩個應用程式帳號。

Docker daemon 管理者可檢視 Container 設定和掛載的 Secret。請勿在終端輸出未遮蔽的 `docker compose config`、`docker inspect` 或環境檔內容。

## Production 初始管理員

資料庫 migration 新增 `users.must_change_password` 與 `system_initialization`。Production 啟動時在單一 InnoDB 交易內新增唯一初始化標記；只有標記首次成功寫入且 `users` 完全空白時，建立一個 `admin` 帳號，使用 BCrypt `PasswordEncoder` 雜湊 `admin`，角色 `ADMIN`、狀態 `ACTIVE` 並設定必須改密碼。唯一鍵由 MySQL 交易序列化並行啟動，標記與 Admin 插入一併提交或回滾，不依賴連線池上的 session lock。後續啟動及使用者改密碼後都不重設帳號。初始化標記存在但 users 為空、或既有使用者中沒有 ACTIVE ADMIN 時，Backend 會在啟動時失敗並要求依恢復程序處理；不會自動重建預設帳號。

初始登入取得的 JWT 帶有必須改密碼狀態及受限權限。只有 `GET /api/v1/auth/me` 和 `POST /api/v1/auth/change-password` 可使用該權杖。新密碼遵守 6 至 100 字元；成功後原權杖仍維持受限狀態，使用者需重新登入。Backend 只儲存新密碼 BCrypt 雜湊；錯誤訊息及日誌不包含密碼。

若資料庫已初始化但 users 沒有 ACTIVE ADMIN，Backend 會停止啟動。受控恢復需先限制 Production 網路入口、確認資料庫與目標使用者、取得可還原的備份，由授權 DBA 透過受控管理連線使用 BCrypt `PasswordEncoder` 產生雜湊，再為已核准帳號設定 `ADMIN` 角色並啟用；完成後核對該帳號登入及權限並記錄操作者。不得刪除初始化標記或建立匿名管理員 Endpoint。舊版在加入此標記前若曾初始化、之後又清空 users，資料庫沒有歷史證據可辨別此狀態；升級前須由 DBA 核對既有資料與備份，不能把空 users 表直接當作全新安裝。

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
pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1
```

受控啟動器也可重複執行。Docker Desktop 或 Windows 重啟後請執行啟動器，Compose 的 `unless-stopped` 會恢復容器並重新啟動密碼同步器。直接執行 Compose `up` 會恢復容器，但不會啟動同步器。資料庫、文件和 pgvector 的資料使用 named volume 持久化。禁止使用 `down -v`、`docker volume rm`、`docker volume prune`，也不要自行執行 Demo Reset。

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
| Production / Demo Compose 結構驗證 | 隔離假憑證 `docker compose config --format json` 解析測試通過；合併設定僅發布各自 loopback Frontend port、資料庫不發布、文件 mount 不重複 |
| Production Admin 真實 Container 登入及改密碼 | Testcontainers 整合測試新增：首次初始化、受限 token、改密碼、重啟不重設、標記防止空 users 重建、無 ACTIVE ADMIN fail-closed、交易回滾與並行初始化。本機 Docker Engine 無法連線，該整合測試未執行 |
| Demo 五角色登入及變更檔案後即時生效 | Testcontainers Demo HTTP 整合測試覆蓋五角色、旋轉後舊密碼拒絕、新密碼成功、停用帳號拒絕及一般帳號仍走 DB 密碼；本機 Docker Engine 無法連線，該測試未執行。PowerShell 與 Python 同步器回歸測試可獨立執行 |
| Fresh Start、重啟與舊 Volume 判定 | PowerShell 自動測試覆蓋全無 Volume、舊版相容 labels、部分 Volume、錯誤 project/key；本機 Docker Engine 無法連線，未檢查或操作真實 Volume，也未執行實際啟動 |
| AWS、HTTPS、Gemini、Semantic Gate、午夜 Reset | 不屬於此本機 Compose 實作證據，依 Issue #45 / #117 後續 Gate 驗收 |
