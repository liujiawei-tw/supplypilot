# SP-903 Production / Demo Docker 部署

## Compose 架構

`compose.yaml` 是唯一服務基底，定義 MySQL、PostgreSQL/pgvector、Spring Boot Backend、React/Nginx Frontend。兩個 Web Container 使用相同 Dockerfile 建置方式、Health Check、資源限制與網路設定。

Production 預設使用 `prod` Profile、固定的 `supplypilot_prod` / `supplypilot_semantic_prod` 資料庫、專屬專案 Volume、`127.0.0.1:8080` 前端入口及內部資料網路。資料庫埠不發布到主機。Production 不掛載 Demo 環境檔，不啟用 Demo Seeder。

Demo 只用 `compose.demo.yaml` 覆蓋必要差異：Compose Project、Profile、資料庫名稱、Demo 文件 Storage、基線密碼來源及 Frontend Port。Demo 專案名稱和三個 named volume 保留舊 SP-903 名稱，讓原 Demo 資料繼續使用；資料庫憑證若與舊 Volume 不符，受控啟動器會在啟動 Backend 前拒絕繼續。不得為了解決憑證錯誤移除 Volume 或 Reset 資料。

Demo 的 `DOCUMENT_STORAGE_PATH` 是 `/app/data/demo-documents`，由既有 logical key `sp903-document-storage` 和 Docker volume `supplypilot-sp903-local-demo-documents` 掛載。這是既有 release candidate 使用的容器內位置；修正只恢復 mount target，不改 volume 名稱或檔案。Sample source 則是 Host `./samples/documents`，唯讀掛載至 Container `/app/samples/demo-documents`。Host 樣本路徑保留原名，因 SP-901 Semantic Gate 的手動 UI 上傳也使用該路徑。

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

- Demo 啟動請使用受控啟動器；它會明確使用 `.env.demo` 和兩份 Compose 檔：

  ```powershell
  pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1
  ```

Compose 的 Process Environment 優先於 `--env-file`。執行標準命令前，確認目前 PowerShell 工作階段沒有同名的 DB、JWT、Demo 或 Compose Project 環境變數；受控 Demo 啟動器會清除並在結束時還原插值用的 Process Environment。Compose Project 名稱、資料庫名稱和 Demo volume 名稱由 Compose 檔案定義，避免憑證外的 shell 變數改變資料位置。

目前基線登入仍使用 Host 同步器複製到 `.sp903-demo-credential/password` 的密碼檔。它會週期檢查來源，但沒有與登入建立同步屏障；因此**不保證 `.env.demo` 儲存完成後的第一個登入立即採用新值**。SP-903 第三輪的嚴格即時一致性尚未實作，狀態為 **BLOCKED**；不得把 100 ms 輪詢描述為符合要求。Backend 不掛載整份 `.env.demo`，Production Compose 不使用 Demo 憑證。

在 Host 驗證通道完成並通過 Windows Docker Desktop 與 Linux Docker Engine 的真實容器測試前，請勿依賴密碼輪替的「下一次登入立即生效」語意。現有同步器的檔案複製測試只能驗證複製與掛載，不是第一個 HTTP Login Request 一致性證據。

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
| Production / Demo Compose 結構驗證 | 隔離假憑證 `docker compose config --format json` 測試通過；檢查合併後 Demo `DOCUMENT_STORAGE_PATH`、samples mount、唯讀標記、既有 logical key 與 volume name，並檢查各自 loopback Frontend port 及資料庫不發布 |
| Demo Seeder／五角色 HTTP 登入／文件 Ingestion | Maven `verify` 使用 Windows Docker Desktop 上的 Testcontainers 完成，包含 Demo Seed Smoke HTTP 測試；不等同完整合併 Compose stack 啟動驗收 |
| 合併 Demo Compose 實際啟動、文件讀取／重啟持久性 | **NOT RUN**：Docker CLI 對目前 Docker Desktop Linux Engine named pipe 回覆 permission denied；未檢視或操作任何既有 Volume |
| Production Admin 真實 Container 登入及改密碼 | Maven `verify` 使用 Windows Docker Desktop 上的 Testcontainers 完成：首次初始化、受限 token、改密碼、重啟不重設、空 users 保護、無 ACTIVE ADMIN fail-closed、交易回滾與並行初始化 |
| `.env.demo` 儲存後第一個 HTTP Login Request 使用新密碼 | **BLOCKED**：現行 100 ms Host 輪詢複製沒有登入同步屏障；沒有第一個登入立即一致性的證據。Windows Docker Desktop 與 Linux Docker Engine 均未實測此一致性 |
| Fresh Start、重啟與舊 Volume 判定 | PowerShell 自動測試覆蓋全無 Volume、舊版相容 labels、部分 Volume、錯誤 project/key；本機 Docker Engine 無法連線，未檢查或操作真實 Volume，也未執行實際啟動 |
| AWS、HTTPS、Gemini、Semantic Gate、午夜 Reset | 不屬於此本機 Compose 實作證據，依 Issue #45 / #117 後續 Gate 驗收 |
