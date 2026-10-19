# SP-903 Production / Demo Docker 部署

## Compose 架構

`compose.yaml` 是唯一服務基底，定義 MySQL、PostgreSQL/pgvector、Spring Boot Backend、React/Nginx Frontend。兩個 Web Container 使用相同 Dockerfile 建置方式、Health Check、資源限制與網路設定。

Production 預設使用 `prod` Profile、固定的 `supplypilot_prod` / `supplypilot_semantic_prod` 資料庫、專屬專案 Volume、`127.0.0.1:8080` 前端入口及內部資料網路。資料庫埠不發布到主機。Production 不掛載 Demo 環境檔，不啟用 Demo Seeder。

Demo 只用 `compose.demo.yaml` 覆蓋必要差異：Compose Project、Profile、資料庫名稱、Demo 文件 Storage、基線密碼 Environment、Frontend Port。Backend 繼承基底的 `data`、`web` 網路，可直接連接 MySQL／pgvector 並服務 Frontend。Demo 專案名稱和三個 named volume 保留舊 SP-903 名稱，讓原 Demo 資料繼續使用；資料庫憑證若與舊 Volume 不符，受控啟動器會在啟動 Backend 前拒絕繼續。不得為了解決憑證錯誤移除 Volume 或 Reset 資料。

Demo Backend 使用 `!override` 取代基底的整組 volume mounts，因此合併後只會掛載兩項：文件 volume `sp903-document-storage:/app/data/demo-documents` 和唯讀樣本 `./samples/documents:/app/samples/demo-documents:ro`。不會留下基底的 `/app/data/documents` 或 Production 文件 volume。Host 樣本路徑保留原名，因 SP-901 Semantic Gate 的手動 UI 上傳也使用該路徑。

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

五個 Demo 基線帳號共用 Compose 注入 Backend 的 `DEMO_ACCOUNT_PASSWORD`。Spring Boot Demo Profile 啟動時載入並驗證其 6–100 字元長度，登入時只對五個基線帳號使用 Demo 密碼；帳號仍須存在且 ACTIVE，角色依資料庫，非基線帳號繼續使用 BCrypt。基線資料庫密碼 Hash 維持隨機不可用值，避免產生第二條認證路徑。Production Profile 不建立 Demo Authenticator。

Compose Environment 在 Container 建立時固定；Docker Desktop Restart、`docker restart` 及 `docker compose restart` 都不會重新讀取 `.env.demo`。若密碼包含 `$`，請以單引號格式設定，避免 Compose 插值。啟動器會在操作任何 Container 前核對檔案解析值與 Compose 生效值，重建後核對 Backend Environment；只輸出一致／不一致結果，不顯示密碼。修改 `.env.demo` 不會熱更新運行中的 Backend；執行受控啟動器會驗證兩個 DB 應用帳號，再只重建 Backend 以套用新密碼，並確認一般啟動期間既有 MySQL、pgvector Container ID 不變。五個基線帳號的新登入改用新密碼、舊密碼失效；不需重建 DB、pgvector 或 Frontend，不需 Reset 或重新 Seed。已核發 JWT 依原有效期運作。Docker 管理者可查看 Container Environment，因此 Demo 密碼不能重用正式企業密碼。

此密碼更新方式是明確需求變更，取代 Issue #45 原先「寫入 `.env.demo` 後，下次登入立即採用新密碼且不重啟 Backend」的驗收條件。Issue #45 已於 2026-10-19 同步新條件；不再要求未重建 Backend 的密碼熱更新，Issue 仍保持 Open，PR／Merge／Closed 依既定流程。

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

歷史版本的 SP-903 Demo 曾使用 `compose.release-candidate.yaml` 和 `.env`；該舊 Compose 檔已自目前分支移除（需要核對時可由 Git History 取回），不再作為啟動入口。既有 Demo 的三個 volume 名稱為：

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

啟動器檢查 `.env.demo` 必要鍵與格式、JWT 強度、忽略規則、Docker Engine、Compose Config、Volume／Container 歸屬與完整 Mount Contract。對既有 MySQL／pgvector，啟動前以 `docker compose config --hash` 比對 Container 的 Compose 設定雜湊；設定不符或沒有可核對的雜湊時明確停止，提示走受控維護流程。接著使用 `up --no-recreate` 啟動兩個資料庫服務：既有停止中的 Container 可啟動，缺少的 Container 可在 Volume Ownership 完整驗證後建立。記錄啟動前既有 ID，並在啟動後確認原有 Container 沒有被替換；Fresh Start 建立的新 Container 不做虛構的舊 ID 比較。接著使用應用程式帳密執行 `SELECT 1`；驗證通過後只以 `--force-recreate --no-deps backend` 重建 Backend，再啟動 Frontend 並檢查四個服務健康。此啟動器不 Reset 或刪除任何 Volume。

Backend 合併後只有兩個 Mount：Demo 文件 named volume `/app/data/demo-documents` 和唯讀 `./samples/documents:/app/samples/demo-documents:ro`。其他服務與文件 Volume 都按 Compose labels、volume name、mountpoint、source、target、type 和唯讀狀態核對；未知 Container 或額外 Mount 拒絕啟動。

升級器只接受已知的舊 Backend 配置：舊密碼來源 Bind Mount `./.sp903-demo-credential:/run/supplypilot-demo-credentials:ro`，或舊 mTLS Client Bind Mount `./.sp903-demo-verifier-client:/run/supplypilot-demo-verifier:ro`。若同時有較早版 `/app/data/documents` `document-storage` volume，也只在其 Compose ownership 和 mountpoint 完全吻合時接受。升級前須核對 Demo project/service、每個舊 Mount 的 Host source、Container destination、type、唯讀模式及原文件 Volume；未知或不符項目一律拒絕。確認資料庫帳密後，啟動器只重建 Backend、核對 MySQL 與 pgvector Container ID 不變，並確認 Backend 不再掛載舊 Bind Mount。舊 Host 憑證／密碼目錄和脫離 Container 的文件 Volume 都會保留；啟動器不會刪除、搬移或覆寫它們。

直接 Compose 啟動方式：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
```

修改 `.env.demo` 後須受控重建 Backend 才會套用新基線密碼；使用上方啟動器會完成資料庫驗證、Backend-only Recreate 和健康檢查。密碼修改不需重建 MySQL、pgvector 或 Frontend，不需 Reset／重新 Seed；新密碼生效後舊密碼拒絕，既有 JWT 依原有效期運作。Container Environment 可由 Docker 管理者檢視，Demo 密碼不得使用正式企業密碼。

## Container 生命週期與資料庫維護

- **Restart**：重啟既有 Container。一般 Demo 啟動可啟動停止中的資料庫 Container；資料目錄由既有 named volume 提供。
- **Recreate**：以目前 Compose 設定替換 Container。一般啟動只重建 Backend 以載入 Demo 密碼；資料庫服務使用 `--no-recreate`，且設定雜湊漂移時先停止。
- **Database Migration**：Backend 連線資料庫後由 Flyway 套用版本化 Schema Migration；migration 不等於清空資料或重建資料庫 Container。
- **Database Reset**：清除或重建業務資料的破壞性操作，必須使用獨立授權程序；一般啟動器不執行 Reset。
- **Volume Persistence**：Container ID 改變本身不代表資料遺失；資料持久性需核對正確的 Compose Volume Ownership、掛載來源、Schema 與資料內容。一般啟動仍會拒絕未授權的既有資料庫 Container 替換。

若資料庫 Image 或設定需要更新，先確認 Environment/Project、Container、Volume Ownership、設定差異與停機影響，建立並驗證可還原備份，取得明確操作授權，再由維護者執行必要的資料庫 Container Recreate。完成後核對 Volume、Schema、代表性資料及 Backend 重新連線。此 Runbook 不提供自動重建或清除 Volume 的命令；不得把一般啟動參數改成 `--force-recreate`，也不得用 `down -v`、`docker volume rm` 或 `docker volume prune` 執行維護。若需重建，受控維護可接受 Container ID 改變，並以 Volume、Schema、資料與服務恢復證據驗收。

安全停止與恢復：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml stop
pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1
```

禁止使用 `down -v`、`docker volume rm`、`docker volume prune`，也不要自行執行 Demo Reset。舊 Volume 的 Compose 標籤或帳密不符時，啟動器拒絕接管；人工檢查 Docker Volume/Container metadata 並驗證原應用程式帳密後，再依據確認後的相容路徑處理。不要靜默建立新空資料庫並聲稱舊資料已恢復。SP-910 r4 的專案和 Volume 不在本 Compose 的資源清單中。

## 驗證命令與範圍

請使用臨時測試環境檔驗證 Compose，勿讀取或輸出實際 `.env`：

```powershell
docker compose --env-file $env:TEMP\supplypilot-prod-test.env config --quiet
docker compose --env-file $env:TEMP\supplypilot-demo-test.env -f compose.yaml -f compose.demo.yaml config --quiet
```

測試檔應只含隔離的假憑證，Demo 測試檔需設置至少 6 字元的假 `DEMO_ACCOUNT_PASSWORD`。`docker compose config` 成功不代表 Runtime 啟動、密碼或 Volume 相容性通過。不要用這些驗證檔啟動真實服務。

| 驗收項目 | 狀態 |
|---|---|
| Production / Demo Compose 結構驗證 | `python -m unittest scripts.tests.test_compose_config -v`：Exit 0，1 test PASS；以隔離假環境設定執行 Compose config validation |
| Backend Maven 完整回歸 | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`：Exit 0，629 tests、0 failures、0 errors、1 skipped；Docker Desktop Testcontainers；包含 9 項 Demo Seed Smoke API 整合案例 |
| Demo 原生登入與啟動密碼設定 | `DemoBaselineCredentialAuthenticatorTests`：6/6 PASS；`DemoSeedSmokeIntegrationTests`：9/9 PASS。這些是 Backend／Testcontainers 測試，不當作 Backend Docker Recreate E2E；真正的 HTTP Backend Recreate 已另由下方隔離 Compose 驗收 |
| Docker Image Build | 隔離 Compose E2E 成功建置 Production-like 與 Demo 的 Backend、Frontend images；Dockerfile 內 Vite production build 成功（chunk-size warning）。未沿用固定正式 image tag |
| Frontend Lint / Test / Build | `npm run lint` Exit 0（既有 `UsersPage.tsx:32` warning）；`npm run build` Exit 0（既有 chunk-size warning）。完整 `npm test -- --reporter=verbose`：23 files、195/198 PASS、3 個逾時；三個逾時案例個別重跑皆 1/1 PASS。完整套件約 19 分鐘，故將全套結果保留為 **FAIL（逾時）**，不以個別重跑取代全套結果。Frontend 程式本輪未修改 |
| Windows Docker Desktop Docker CLI | Docker Engine 29.6.1 可用；隔離 Docker E2E 已執行，不修改 Windows Firewall，也未接觸既有 SupplyPilot stack／Volume |
| Demo Compose Runtime | **PASS（隔離測試）**：四服務 Healthy；五基線帳號真實 HTTP Login／角色；Demo Password 改值後只 Recreate Backend，新密碼成功、舊密碼遭拒；MySQL／pgvector／Frontend Container ID 維持，Seeder COMPLETE marker、測試文件、MySQL 與 pgvector marker 保留；重複啟動及 Stop→Start 保留資料 |
| Production-like Compose Runtime | **PASS（非企業 Production）**：四服務 Healthy；`prod` Profile、無 Demo Seeder／Demo 資料；初始 `admin/admin` 強制更新密碼、舊密碼拒絕、受限 JWT 無管理權限、Admin／非 Admin 權限邊界；Backend Recreate 後管理員不重置；MySQL／pgvector／文件 marker 持久化 |
| 隔離與清理 | E2E 使用唯一 Compose Project、Volume、Port、JWT、資料庫／Demo 假密碼與 image tag；結束只清理本次建立且經 Compose labels 核對的資源，沒有 `down -v`、全域 Prune 或觸碰固定 SP-903／SP-910 r4 Volume |
| Production Admin 真實 Container 登入及改密碼 | Production-like 真實 Compose E2E **PASS**；不代表企業 Production 部署驗收 |
| Mount ownership 與舊 Container 升級控制流程 | `pwsh -NoProfile -File scripts/tests/test-sp903-demo-startup.ps1` Exit 0；fixture 覆蓋 Volume Ownership、完整 Mount Contract、既有／Fresh DB Container ID 與設定漂移拒絕。真實既有固定名稱 SP-903 Container 升級 **NOT RUN**：依隔離要求未接觸既有資源 |
| Linux Docker Engine | **NOT RUN**：本次工作環境為 Windows Docker Desktop，Linux Host 的檔案權限與 mount 行為尚未實測 |
| GitHub Actions | 本次 SHA Push 後查核對應 workflow；查核完成前不標示最終 SHA CI PASS |
| AWS、HTTPS、Gemini、Semantic Gate、午夜 Reset | 不屬於本機 Compose 驗收證據，依 Issue #45 / #117 後續 Gate 驗收 |
