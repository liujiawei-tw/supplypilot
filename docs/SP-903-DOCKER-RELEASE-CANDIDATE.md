# SP-903 Production / Demo Docker 部署

## Compose 架構

`compose.yaml` 是唯一服務基底，定義 MySQL、PostgreSQL/pgvector、Spring Boot Backend、React/Nginx Frontend。兩個 Web Container 使用相同 Dockerfile 建置方式、Health Check、資源限制與網路設定。

Production 預設使用 `prod` Profile、固定的 `supplypilot_prod` / `supplypilot_semantic_prod` 資料庫、專屬專案 Volume、`127.0.0.1:8080` 前端入口及內部資料網路。資料庫埠不發布到主機。Production 不掛載 Demo 環境檔，不啟用 Demo Seeder。

Demo 只用 `compose.demo.yaml` 覆蓋必要差異：Compose Project、Profile、資料庫名稱、Demo 文件 Storage、基線密碼來源及 Frontend Port。Demo 專案名稱和三個 named volume 保留舊 SP-903 名稱，讓原 Demo 資料繼續使用；資料庫憑證若與舊 Volume 不符，受控啟動器會在啟動 Backend 前拒絕繼續。不得為了解決憑證錯誤移除 Volume 或 Reset 資料。

Demo Backend 使用 `!override` 取代基底的整組 volume mounts，因此合併後只會掛載三項：文件 volume `sp903-document-storage:/app/data/demo-documents`、唯讀 Client 憑證目錄 `./.sp903-demo-verifier-client:/run/supplypilot-demo-verifier:ro`，以及唯讀樣本 `./samples/documents:/app/samples/demo-documents:ro`。不會留下基底的 `/app/data/documents` 或 Production 文件 volume。Host 樣本路徑保留原名，因 SP-901 Semantic Gate 的手動 UI 上傳也使用該路徑。

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

五個基線帳號使用 Host Authority + Per-Request Synchronous Verification。啟動器在 Host 產生專用 CA、Verifier Server 憑證與 Backend Client 憑證；只有 Client 憑證與 CA 公開憑證唯讀掛入 Backend，Server 私鑰及 CA 私鑰留在 Host。Host Verifier 只接受 mTLS，Backend 每次 Login 建立獨立的 128-bit Request ID 與 256-bit nonce；Response 必須回顯兩者，且回應在驗證過的 TLS 通道內傳回。Host 端拒絕重放 Request ID、限制來源檔 64 KiB、並行 32 個請求，且不記錄請求內容。每次請求重新讀取 `.env.demo`，不保留舊密碼或成功結果作為備援；來源錯誤、驗證失敗、Verifier 停止或通訊逾時均 Fail Closed。五個基線帳號的 DB BCrypt 值是隨機不可用值，Baseline Authenticator 不會回退 DB Hash；其他 Demo 使用者仍使用 BCrypt。Production Profile 不建立此元件。

Host Verifier 使用 `0.0.0.0:18443` 接收 Docker Desktop `host.docker.internal` 與 Linux `host-gateway` 流量；服務需要 Python 3、OpenSSL 及允許 Docker Bridge 到 Host TCP/18443 的主機防火牆規則。Linux Docker Engine Host 另需 `acl` / `setfacl`；啟動器將 Server/CA 私鑰限為 Host 使用者可讀，Client 憑證目錄只授予 Host 使用者及 Backend UID 10001 搜尋／讀取。Windows 檔案使用 Host 目錄繼承的 NTFS ACL，啟動器不擴大 ACL。mTLS 阻止沒有專用 Client Key 的匿名或 LAN 呼叫。啟動器重複執行時會用隔離 Client 憑證對現有 Listener 做 mTLS 健康檢查，錯誤服務會令啟動失敗。Verifier process 獨立於 Backend/Engine 生命週期，Backend 或 Engine 重啟後可繼續連線；Verifier 停止期間登入拒絕，恢復後下一次登入重新向 Host 讀取來源。已簽發 JWT 按原期限有效。

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

啟動器會先檢查 `.env.demo` 必要鍵、重複鍵、JWT 格式、忽略規則、Docker Engine、Compose Volume/Container 歸屬和 Compose 結構；接著產生或核對本機 mTLS 憑證，啟動 Host Verifier，再只啟動兩個資料庫並以實際應用程式帳密執行 `SELECT 1`，成功後才啟動 Backend 和 Frontend。啟動器沒有 Reset 或刪除資源的路徑。四個服務健康後，重複執行相同命令可用來恢復或重啟 Stack。

每個既有 Container 的完整 Docker Inspect Mount 集合都須符合 service 契約。Backend 的 bind source 會正規化 Windows 路徑、Docker Desktop VM 對應路徑或 Linux 路徑後做完整相等比對；樣本與憑證 bind 必須唯讀。MySQL、pgvector 和文件 volume 需符合明確名稱、Compose project/key labels、mountpoint、target 與寫入權限。Frontend 不允許任何額外 mount。無法確認的 metadata 一律拒絕。

升級前若 Backend Container 同時掛載 `/app/data/documents` 上的 Compose volume key `document-storage` 與 Demo 文件 volume，啟動器只對同時符合 Demo project/service labels、舊 volume key 及舊 target 的這一種已知配置暫時放行。DB 帳密檢查成功後記錄 MySQL 與 pgvector Container ID，再以 `up --force-recreate --no-deps backend` 只重建 Backend；重建後核對 DB Container ID 不變並嚴格檢查全部 mounts。之後一般應用程式啟動也帶 `--no-deps`，避免 Compose 再建立資料庫。舊額外 volume 只會脫離 Container，保留在 Docker 中，不刪除、不搬移、不覆寫。未知額外 Mount 仍拒絕啟動。

安全停止與恢復：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml stop
pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1
```

受控啟動器也可重複執行。Docker Desktop 或 Windows 重啟後請執行啟動器；Compose 的 `unless-stopped` 會恢復容器。直接執行 Compose `up` 不會啟動 Host Verifier，五個基線帳號因此無法登入。資料庫、文件和 pgvector 的資料使用 named volume 持久化。禁止使用 `down -v`、`docker volume rm`、`docker volume prune`，也不要自行執行 Demo Reset。

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
| Production / Demo Compose 結構驗證 | `python -m unittest scripts.tests.test_compose_config scripts.tests.test_sp903_demo_host_verifier -v`：6 tests PASS。隔離設定驗證 Demo mount 集合、唯讀權限、port 與 Production/Demo 隔離；未啟動既有 Compose 專案 |
| Backend Maven 完整回歸 | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`：PASS，629 tests、0 failures、0 errors、1 skipped；Testcontainers 使用 Windows Docker Desktop 建立臨時 MySQL/pgvector。Demo Seed Smoke 測試包含 11 項 API 整合案例 |
| Demo Host Verifier 與跨程序 RPC | Python Host verifier tests：6/6 PASS，覆蓋 mTLS、真實子程序、五帳號、source rotation/error、nonce/request binding、replay rejection 與 concurrency |
| 五個基線帳號 HTTP Login 與來源即時輪替 | `DemoSeedSmokeIntegrationTests`：11/11 PASS，經 Spring Login endpoint + mTLS Host verifier 測試五帳號、原子替換、直接覆寫及快速輪替、並行登入、錯密碼、來源錯誤、Verifier 停止／恢復及非基線 DB BCrypt 路徑。測試只使用隔離假 `.env.demo` |
| Docker Image Build | Docker Desktop `desktop-linux`：Backend `supplypilot-sp903-backend:verification` 與 Frontend `supplypilot-sp903-frontend:verification` 均建置成功；未啟動服務或掛載 volume |
| Frontend Lint / Test / Build | `npm run lint` PASS（有既存 `UsersPage.tsx:32` lint warning）；`npm run build` PASS（有既存 chunk size warning）。完整 Vitest：23 files／198 tests，193 PASS、5 FAIL（皆為 timeout：`masterData.test.tsx` 1 項、`sp806.test.tsx` 4 項）；定向 `demandInventory.test.tsx` + `recommendation.test.tsx` 為 38/38 PASS。Frontend 程式本輪未修改 |
| Windows Docker Desktop Docker CLI | 一般工作階段 `docker info` 對 `default` 與 `desktop-linux` context 回覆 named pipe `permission denied`。Maven/Testcontainers 與升權後的 image build 可連線，但不代表 Compose 專案 runtime 驗收完成 |
| 合併 Demo Compose 實際啟動、Fresh/Repeated/Stop→Start、文件讀取與重啟持久性 | **NOT RUN**：既有 compose 使用固定 volume 名稱，為避免接觸使用者資料未啟動 stack。未檢視、掛載、重建、重設或刪除任何既有 volume |
| Production Admin 真實 Container 登入及改密碼 | Maven `verify` 的隔離 Testcontainers 覆蓋首次初始化、受限 token、改密碼、重啟不重設、空 users 保護、無 ACTIVE ADMIN fail-closed、交易回滾與並行初始化；不是完整 Production Compose 啟動驗收 |
| Mount ownership 與舊 Container 升級控制流程 | `pwsh -NoProfile -File scripts/tests/test-sp903-demo-startup.ps1` PASS；fixture 覆蓋 Linux 大小寫、Windows path 正規化、mount ownership、舊雙文件 mount、Backend-only `--no-deps` recreate、DB Container ID 未變／變更與失敗分支。**真實舊 Container upgrade NOT RUN**：未接觸固定名稱的現有 Demo 資源 |
| Linux Docker Engine | **NOT RUN**：本次工作環境為 Windows Docker Desktop，未連接 Linux Host；Linux firewall、setfacl、host-gateway 與實際容器 mount 尚未實測 |
| GitHub Actions | 程式 Commit `7f5c0ffdad93ab1067e67a38ca0457555e59d6af` 的 push Run [#140](https://github.com/liujiawei-tw/supplypilot/actions/runs/39146743930) 曾啟動；查核發現 workflow 還引用已移除的輪詢同步測試，因此已在後續修正 CI 接線。請以包含該 workflow 修正的最新 Commit SHA 查核新 Run；未完成前不標示 CI PASS |
| AWS、HTTPS、Gemini、Semantic Gate、午夜 Reset | 不屬於此本機 Compose 實作證據，依 Issue #45 / #117 後續 Gate 驗收 |
