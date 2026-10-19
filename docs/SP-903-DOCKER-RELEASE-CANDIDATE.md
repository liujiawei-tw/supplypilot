# SP-903 Production / Demo Docker 部署

## Compose 架構

`compose.yaml` 是唯一服務基底，定義 MySQL、PostgreSQL/pgvector、Spring Boot Backend、React/Nginx Frontend。兩個 Web Container 使用相同 Dockerfile 建置方式、Health Check、資源限制與網路設定。

Production 預設使用 `prod` Profile、固定的 `supplypilot_prod` / `supplypilot_semantic_prod` 資料庫、專屬專案 Volume、`127.0.0.1:8080` 前端入口及內部資料網路。資料庫埠不發布到主機。Production 不掛載 Demo 環境檔，不啟用 Demo Seeder。

Demo 只用 `compose.demo.yaml` 覆蓋必要差異：Compose Project、Profile、資料庫名稱、Demo 文件 Storage、基線密碼來源、Frontend Port，以及只供 Backend 使用的 Host Verifier 網路。Demo 專案名稱和三個 named volume 保留舊 SP-903 名稱，讓原 Demo 資料繼續使用；資料庫憑證若與舊 Volume 不符，受控啟動器會在啟動 Backend 前拒絕繼續。不得為了解決憑證錯誤移除 Volume 或 Reset 資料。

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

Host Verifier 僅綁定已核對的 Docker Host 介面 IPv4，並要求來源位址落在 Demo 專用 `verifier` 網路子網；該網路只連 Backend，不連 Frontend。Windows Docker Desktop 啟動前從容器端解析 `host.docker.internal`、核對該位址屬於 Docker/WSL 虛擬介面，並要求 Windows Firewall 已啟用且入站預設拒絕；涵蓋 TCP/18443 的廣泛 Allow 規則會令啟動失敗。啟動器不修改 Firewall。Linux Docker Engine 以 `host-gateway` 對照 Docker bridge gateway 和 Host 本機介面；Verifier 僅接受 Backend 專用網段，啟動器不改 Docker 管理的防火牆鏈。若任一網路路徑／隔離條件無法核對，啟動器拒絕服務，不會退回 `0.0.0.0`。啟動後以隔離的 Docker 憑證探測容器實際路徑，同時驗證 Server hostname、私有 CA 和 Client identity。

內部 `Backend → Host Verifier` 完全使用 OpenSSL 建立的私有 CA 與免費 mTLS 憑證，不申請公開 CA 的 `host.docker.internal` 憑證。Backend 僅接受 `https://host.docker.internal:18443/v1/verify`（隔離測試可用 `https://localhost:<port>/v1/verify`），拒絕 UserInfo、其他 Host/Port/Path、HTTP、Query、Fragment 和 Redirect。Java 預設 TLS Hostname Verification，信任庫只含專用私有 CA；Python 要求有效 Client Certificate 並核對 `SP903 Demo Backend` 身分。CA、Server/Client 憑證到期、用途、簽發鏈或公私鑰不一致時啟動失敗；登入通道不降級至 HTTP。私鑰不放入 Git、Image 或日誌。Linux 以 `chmod` / `setfacl` 限制權限；Windows 檢查 NTFS ACL，發現廣泛讀取權限時拒絕啟動。私鑰輪替須人工停止 Demo、將舊憑證目錄安全備份至 Repository 外的受限位置、在空白的新目錄重新執行受控啟動器，確認 Backend mount、TLS/mTLS 與新登入成功後，再人工清理備份；不會自動刪除舊目錄。

Verifier 由單一實例 Supervisor 管理，使用 OS 檔案鎖，並以程序參數身分和雙向 TLS 健康探測辨識既有服務；僅有 TCP Listener 不足以重用程序，未知程序不會被終止。本次啟動建立的 Supervisor 若 Backend 啟動或後續 Mount 驗證失敗，啟動器會終止本次建立的程序樹；既有合法 Supervisor 會保留。Verifier Crash 後以 5、10、20、40、60 秒間隔重啟，15 分鐘內最多重啟五次後停止，登入在服務恢復前 Fail Closed；恢復後每個新登入仍重新讀取 `.env.demo`。Docker Engine 重啟時 host-gateway 和 Compose verifier 網路保留；Host OS 重啟後須執行啟動器重新核對路徑。已簽發 JWT 按原期限有效。

### 未來 AWS 公開網站 HTTPS（架構預留，未部署）

本機 Demo 的 loopback HTTP 不變。未來 AWS 公開網站優先採用 Let's Encrypt 免費 IP Address Certificate，不需要先購買網域。官方目前要求 IP 憑證使用 `shortlived` Profile，效期 160 小時；Certbot 5.4+ 的 webroot 支援 IP 驗證，官方範例為：

```bash
sudo certbot certonly --staging \
  --preferred-profile shortlived \
  --webroot --webroot-path /var/www/letsencrypt \
  --ip-address "$EC2_PUBLIC_IPV4"
```

先用 Staging 驗證 HTTP-01、EC2 對外 80 埠及 Nginx webroot 路徑。Certbot 目前不會自動把 IP 憑證安裝到 Nginx；部署時須手動將 `fullchain.pem` / `privkey.pem` 設定至 443，HTTP 僅供 ACME challenge 及安全導向 HTTPS，登入/API 不在 HTTP 提供。確認 Staging 通過後才移除 `--staging` 申請公開信任憑證，並設定 Certbot 自動續期與僅在實際更新成功後執行的 `--deploy-hook 'systemctl reload nginx'`。使用 `certbot renew --dry-run` 驗證續期排程；監控憑證實際 `notAfter`、IP SAN、完整信任鏈、瀏覽器信任狀態、更新後 Nginx Reload、續期失敗告警和過期後服務恢復。160 小時效期要求持續自動續期，單次簽發不構成可用的長期 HTTPS。

申請前須確認 EC2 真正 Public IPv4 並確保展示期間穩定。EC2 自動公網 IPv4 在 Stop/Start 後可能更換；Elastic IP 可維持固定，但 AWS 目前對使用中與閒置 Public IPv4 均收費，標示費率為每個 IP 每小時 USD 0.005（30 天約 USD 3.60，未含 EC2、流量或稅費）。先確認帳戶預算與展示期間的停止策略。若 IP 憑證實測不可行，再評估免費 DNS 子網域；不以購買網域為前提，也不在本輪建立 AWS 資源或送出 ACME 申請。

官方依據： [Let's Encrypt IP 與短效憑證一般供應及效期](https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability)、[Certbot IP 憑證 Staging／webroot 指令與安裝限制](https://letsencrypt.org/2026/03/11/shorter-certs-certbot.html)、[Certbot renew 與 deploy hook 說明](https://eff-certbot.readthedocs.io/en/stable/using.html)、[EC2 Stop/Start 公網 IPv4 行為](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/Stop_Start.html)、[AWS Public IPv4 費率](https://aws.amazon.com/vpc/pricing/)。

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

啟動器會先檢查 `.env.demo` 必要鍵、重複鍵、JWT 格式、忽略規則、Docker Engine、Compose Volume/Container 歸屬和 Compose 結構；產生或核對本機 mTLS 憑證後，只啟動兩個資料庫並用應用程式帳密執行 `SELECT 1`。資料庫憑證通過後，啟動器建立／核對 Backend 專用網路，檢查 Docker Host 路徑、Windows Firewall 或 Linux bridge，再啟動並用隔離 Docker Client 憑證驗證 Host Verifier，最後才啟動 Backend 和 Frontend。啟動器沒有 Reset 或刪除資料 Volume 的路徑。四個服務健康後，重複執行相同命令可用來恢復或重啟 Stack。

每個既有 Container 的完整 Docker Inspect Mount 集合都須符合 service 契約。Backend 的 bind source 會正規化 Windows 路徑、Docker Desktop VM 對應路徑或 Linux 路徑後做完整相等比對；樣本與憑證 bind 必須唯讀。MySQL、pgvector 和文件 volume 需符合明確名稱、Compose project/key labels、mountpoint、target 與寫入權限。Frontend 不允許任何額外 mount。無法確認的 metadata 一律拒絕。

升級前若 Backend Container 同時掛載 `/app/data/documents` 上的舊 `document-storage` volume 和 Demo 文件 volume，並使用 `./.sp903-demo-credential:/run/supplypilot-demo-credentials:ro` 舊憑證 Bind Mount，啟動器只對精確符合 Demo project/service labels、Volume Ownership/target、Host source 和唯讀模式的組合暫時放行。未知 Bind Mount 一律拒絕。DB 帳密檢查成功後記錄 MySQL 與 pgvector Container ID，再以 `up --force-recreate --no-deps backend` 只重建 Backend；重建後核對 DB Container ID 不變並嚴格檢查全部新 Mount。之後一般應用程式啟動也帶 `--no-deps`，避免 Compose 再建立資料庫。舊額外 volume 只會脫離 Container，保留在 Docker 中，不刪除、不搬移、不覆寫；舊 Host 憑證目錄也不會自動刪除。確認新版 Mount 和 mTLS Login 已成功後，人工將 `.sp903-demo-credential` 移至受限備份位置，驗證備份不再需要後才可人工刪除：

```powershell
Remove-Item -LiteralPath .\.sp903-demo-credential -Recurse
```

此刪除命令只供確認新 Backend Mount、TLS/mTLS 路徑和新密碼登入成功後，由授權操作者人工執行；啟動器不會執行它。

安全停止與恢復：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml stop
pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1
```

受控啟動器也可重複執行。Docker Desktop 或 Windows 重啟後請執行啟動器；Compose 的 `unless-stopped` 會恢復容器。直接執行 Compose `up` 不會啟動 Host Verifier，五個基線帳號因此無法登入。資料庫、文件和 pgvector 的資料使用 named volume 持久化。禁止使用 `down -v`、`docker volume rm`、`docker volume prune`，也不要自行執行 Demo Reset。

### 選用：由 OS 管理啟動與恢復

啟動器預設只建立／驗證 Stack 後返回。OS 服務範例須由授權操作者自行安裝和啟用；它們不會被啟動器自動安裝。`-WaitForVerifier` 讓受管程序保持前景，Supervisor 對 Verifier 子程序執行有限次數的 Crash Recovery。

Windows Docker Desktop 以登入工作階段中的 Task Scheduler 執行，因 Docker Desktop daemon 通常屬於使用者工作階段。以下命令建立目前使用者的登入工作；使用前依實際 Repository 路徑調整 `$repo`：

```powershell
$repo = (Get-Location).Path
$action = New-ScheduledTaskAction -Execute 'pwsh.exe' -Argument "-NoProfile -File `"$repo\scripts\start-sp903-demo.ps1`" -WaitForVerifier" -WorkingDirectory $repo
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName 'SupplyPilot SP-903 Demo' -Action $action -Trigger $trigger -Settings $settings -User $env:USERNAME -RunLevel Highest
Start-ScheduledTask -TaskName 'SupplyPilot SP-903 Demo'
Get-ScheduledTaskInfo -TaskName 'SupplyPilot SP-903 Demo'
```

檢查可用 `Get-ScheduledTask` / `Get-ScheduledTaskInfo`。要停止或解除安裝時，先停用重試，安全停止已核對身分的 Verifier 程序樹，再停止／移除 Task；若只是暫停後續登入，Docker Stack 與 Volume 仍在：

```powershell
Disable-ScheduledTask -TaskName 'SupplyPilot SP-903 Demo'
pwsh -NoProfile -File .\scripts\stop-sp903-demo-host-verifier.ps1
Stop-ScheduledTask -TaskName 'SupplyPilot SP-903 Demo'
# 解除安裝時才執行：
Unregister-ScheduledTask -TaskName 'SupplyPilot SP-903 Demo' -Confirm:$false
```

重新啟用已安裝工作用 `Enable-ScheduledTask` 再 `Start-ScheduledTask`。停止 OS 任務不會清除 Docker Volume。需同時停止 Compose Stack 時，再依前述安全停止命令執行 `docker compose ... stop`。

Linux 使用 [systemd 範本](../deploy/systemd/supplypilot-sp903-demo.service.example)。人工複製後先將 `User`、`WorkingDirectory` 和 `ExecStart` 改成實際部署路徑，再執行：

```bash
sudo install -m 0644 deploy/systemd/supplypilot-sp903-demo.service.example /etc/systemd/system/supplypilot-sp903-demo.service
sudo systemctl daemon-reload
sudo systemctl enable --now supplypilot-sp903-demo.service
systemctl status supplypilot-sp903-demo.service
sudo systemctl stop supplypilot-sp903-demo.service
sudo systemctl disable supplypilot-sp903-demo.service
sudo rm /etc/systemd/system/supplypilot-sp903-demo.service
sudo systemctl daemon-reload
```

安裝前需確認 service user 可使用 Docker daemon、Python 3、OpenSSL、`setfacl`、`.env.demo` 與 Repository。停止服務不刪除 Docker 資料。

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
| Production / Demo Compose 結構驗證 | 第七輪 `python -m unittest scripts.tests.test_compose_config -v`：Exit 0，1 test PASS；Python Verifier／Supervisor／Compose 合併回歸共 11 tests PASS。只產生假環境檔做 `docker compose config`，沒有啟動既有 Compose 專案 |
| Backend Maven 完整回歸 | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`：PASS，第七輪升權 Docker Desktop Testcontainers：Exit 0，630 tests、0 failures、0 errors、1 skipped；包含 11 項 Demo Seed Smoke API 整合案例 |
| Demo Host Verifier 與跨程序 RPC | 第七輪 Python Host Verifier、Supervisor 與 Compose Regression：Exit 0，11 tests PASS；覆蓋 TLS/mTLS、錯誤 CA／Hostname／Client identity、來源輪替、明文請求拒絕、重啟退避及 verifier 網路拓樸 |
| 五個基線帳號 HTTP Login 與來源即時輪替 | `DemoSeedSmokeIntegrationTests`：11/11 PASS，經 Spring Login endpoint + mTLS Host verifier 測試五帳號、原子替換、直接覆寫及快速輪替、並行登入、錯密碼、來源錯誤、Verifier 停止／恢復及非基線 DB BCrypt 路徑。測試只使用隔離假 `.env.demo` |
| Docker Image Build | Docker Desktop `desktop-linux`：`docker build` 建置 Backend `supplypilot-backend:sp903-seventh-review` 與 Frontend `supplypilot-frontend:sp903-seventh-review` 均 Exit 0；未啟動 SupplyPilot stack 或掛載既有 volume |
| Frontend Lint / Test / Build | 第七輪 `npm run lint` Exit 0（有既存 `UsersPage.tsx:32` warning）；`npm test` Exit 0，23 files／198 tests 全數 PASS；`npm run build` Exit 0（有既存 chunk-size warning）。Frontend 程式本輪未修改 |
| Windows Docker Desktop Docker CLI | 一般工作階段 `docker info` 回覆 named pipe `permission denied`；升權驗收的 Docker Engine 與 image build 已成功。一次性 mysql 容器將 `host.docker.internal` 解析為 `192.168.65.254`，該 IP 未配置於 Windows Host；目前受控啟動器會 fail closed。Windows Firewall 規則未修改；Firewall 設定與完整 Verifier 容器 mTLS 路徑、Compose Stack runtime **NOT RUN** |
| 合併 Demo Compose 實際啟動、Fresh/Repeated/Stop→Start、文件讀取與重啟持久性 | **NOT RUN**：既有 compose 使用固定 volume 名稱，為避免接觸使用者資料未啟動 stack。未檢視、掛載、重建、重設或刪除任何既有 volume |
| Production Admin 真實 Container 登入及改密碼 | Maven `verify` 的隔離 Testcontainers 覆蓋首次初始化、受限 token、改密碼、重啟不重設、空 users 保護、無 ACTIVE ADMIN fail-closed、交易回滾與並行初始化；不是完整 Production Compose 啟動驗收 |
| Mount ownership 與舊 Container 升級控制流程 | `pwsh -NoProfile -File scripts/tests/test-sp903-demo-startup.ps1` Exit 0；fixture 覆蓋 Linux 大小寫、Windows path 正規化、精確舊雙文件 volume + 舊唯讀 credential bind、Backend-only `--no-deps` recreate、DB Container ID 不變／變更及失敗分支。**真實舊 Container upgrade NOT RUN**：未接觸固定名稱的現有 Demo 資源 |
| Linux Docker Engine | **NOT RUN**：本次工作環境為 Windows Docker Desktop，未連接 Linux Host；Linux firewall、setfacl、host-gateway 與實際容器 mount 尚未實測 |
| GitHub Actions | 程式 Commit `7f5c0ffdad93ab1067e67a38ca0457555e59d6af` 的 push Run [#140](https://github.com/liujiawei-tw/supplypilot/actions/runs/39146743930) 曾啟動；查核發現 workflow 還引用已移除的輪詢同步測試，因此已在後續修正 CI 接線。請以包含該 workflow 修正的最新 Commit SHA 查核新 Run；未完成前不標示 CI PASS |
| AWS、HTTPS、Gemini、Semantic Gate、午夜 Reset | 不屬於此本機 Compose 實作證據，依 Issue #45 / #117 後續 Gate 驗收 |
