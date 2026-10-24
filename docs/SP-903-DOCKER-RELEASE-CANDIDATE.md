# SP-903 Production / Demo Docker 部署

## Compose 架構

`compose.yaml` 是唯一服務基底，定義 MySQL、PostgreSQL/pgvector、Spring Boot Backend、React/Nginx Frontend。兩個 Web Container 使用相同 Dockerfile 建置方式、Health Check、資源限制與網路設定。

Production 預設使用 `prod` Profile、固定的 `supplypilot_prod` / `supplypilot_semantic_prod` 資料庫、專屬專案 Volume、`127.0.0.1:8080` 前端入口及內部資料網路。MySQL 保留 `data` 網路，另加入只有 MySQL 使用的 `mysql-host` bridge network，以支援主機埠轉送；這個 bridge network 也允許 MySQL 容器對外連線。MySQL 發布到 Docker 主機的 `127.0.0.1:13306`，容器內仍使用 `3306`；主機埠可由 `.env` 的 `PRODUCTION_MYSQL_HOST_PORT` 調整。此設定供 Docker 主機上的 MySQL Workbench 連線；pgvector 仍不發布主機埠。Docker Engine 28.0.0 之前的版本有同一區域網路網段仍可能連到 localhost 發布埠的例外；發布環境應使用 Docker Engine 28.0.0 以上版本，或另以主機防火牆限制連線。Production 不掛載 Demo 環境檔，不啟用 Demo Seeder。

Demo 只用 `compose.demo.yaml` 覆蓋必要差異：Compose Project、Profile、資料庫名稱、Demo 文件 Storage、基線密碼 Environment、Frontend Port 及 MySQL 主機埠。MySQL 發布到 `127.0.0.1:13307`，容器內仍使用 `3306`；主機埠可由 `.env.demo` 的 `DEMO_MYSQL_HOST_PORT` 調整。Workbench 使用 `.env.demo` 的 `DB_USERNAME`／`DB_PASSWORD` 連接 Schema `supplypilot_demo`。Backend 繼承基底的 `data`、`web` 網路，可直接連接 MySQL／pgvector 並服務 Frontend。Demo 專案名稱和三個 Named Volume 保留既有 SP-903 名稱。不得為了解決憑證錯誤移除 Volume 或 Reset 資料。

Demo Backend 使用 `!override` 取代基底的整組 volume mounts，因此合併後只會掛載兩項：文件 volume `sp903-document-storage:/app/data/demo-documents` 和唯讀樣本 `./samples/documents:/app/samples/demo-documents:ro`。不會留下基底的 `/app/data/documents` 或 Production 文件 volume。Host 樣本路徑保留原名，因 SP-901 Semantic Gate 的手動 UI 上傳也使用該路徑。

開發資料庫使用 `compose.dev.yaml` 的 `supplypilot-dev-v2` 專案與新 Volume，避免接管先前 `compose.yaml` 建立的開發 Volume。舊開發資料仍留在 Docker，不會由這份配置修改。

## 環境檔與 Secret

- `.env.example` 是 Production 範本；使用者人工複製並維護 `.env`。
- `.env.demo.example` 是 Demo 範本；使用者人工複製並維護 `.env.demo`。
- Production 與 Demo 的 MySQL、PostgreSQL、JWT、Demo 密碼必須分開設定。
- `.env`、`.env.demo` 及其他 `.env.*` 都由 `.gitignore` 和 `.dockerignore` 排除。只允許 `.env.example` 與 `.env.demo.example` 範本進 Git；建置 Context 不會包含實際環境檔。
- 標準 Production 啟動使用 Compose 自動讀取根目錄 `.env`：

  ```powershell
  docker compose up -d --build
  ```

- Demo 明確指定 `.env.demo` 和兩份 Compose 檔：

  ```powershell
  docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
  ```

Compose 插值優先使用 Shell Environment，其次才是 `--env-file`。因此即使指定 `.env.demo`，同名的 Shell 變數仍可能覆蓋檔案值。PowerShell 可檢查變數是否存在而不輸出內容，並只在目前工作階段移除該覆蓋：

  ```powershell
  Test-Path Env:DEMO_ACCOUNT_PASSWORD
  Remove-Item Env:DEMO_ACCOUNT_PASSWORD -ErrorAction SilentlyContinue
  ```

若 Demo 密碼含 `$`，請以單引號包住 `.env.demo` 值，例如 `DEMO_ACCOUNT_PASSWORD='literal$example'`；Compose 會將單引號值視為字面值，不進行插值。Production 直接使用根目錄 `.env`，Demo 命令則明確指定 `.env.demo`。

五個 Demo 基線帳號共用 Compose 注入 Backend 的 `DEMO_ACCOUNT_PASSWORD`。Spring Boot Demo Profile 啟動時載入並驗證其 6–100 字元長度，登入時只對五個基線帳號使用 Demo 密碼；帳號仍須存在且 ACTIVE，角色依資料庫，非基線帳號繼續使用 BCrypt。基線資料庫密碼 Hash 維持隨機不可用值，避免產生第二條認證路徑。Production Profile 不建立 Demo Authenticator。

Compose Environment 在 Container 建立時固定。Docker Desktop Restart、`docker restart` 及 `docker compose restart` 都不會重新讀取 `.env.demo`。修改密碼後使用標準 `docker compose down` 再 `docker compose up -d --build`；Compose 以目前設定建立 Container，Named Volume 保留資料。一般 `up -d --build` 也會依設定變更重建必要服務。五個基線帳號的新登入改用新密碼、舊密碼失效；正常啟動不會重新 Seed。已核發 JWT 依原有效期運作。Docker 管理者可查看 Container Environment，因此 Demo 密碼不能重用正式企業密碼。

Issue #45 已取消不重建 Backend 的密碼熱更新條件，但仍含本輪淘汰的自訂啟動器、Volume Ownership／Mount Contract 守衛及 DB Container ID 不變要求。這些剩餘文字需由 Issue 負責者同步；本輪不修改 Issue，Issue 仍保持 Open。

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

資料庫密碼若錯誤，資料庫應用程式登入驗證會失敗，Backend 不會啟動 Flyway 或 Seeder。MySQL `mysqladmin ping` 及 PostgreSQL `pg_isready` 只代表伺服器可回應，不足以證明應用程式憑證正確。標準 Compose 會依資料庫 Health Check 控制 Backend 啟動；應用程式帳密錯誤會由 Backend 啟動及 Health Check 顯示失敗。

Docker daemon 管理者可檢視 Container 設定和掛載的 Secret。請勿在終端輸出未遮蔽的 `docker compose config`、`docker inspect` 或環境檔內容。

## Production 初始管理員

資料庫 migration 新增 `users.must_change_password` 與 `system_initialization`。Production 啟動時在單一 InnoDB 交易內新增唯一初始化標記；只有標記首次成功寫入且 `users` 完全空白時，建立一個 `admin` 帳號，使用 BCrypt `PasswordEncoder` 雜湊 `admin`，角色 `ADMIN`、狀態 `ACTIVE` 並設定必須改密碼。唯一鍵由 MySQL 交易序列化並行啟動，標記與 Admin 插入一併提交或回滾，不依賴連線池上的 session lock。後續啟動及使用者改密碼後都不重設帳號。初始化標記存在但 users 為空、或既有使用者中沒有 ACTIVE ADMIN 時，Backend 會在啟動時失敗並要求依恢復程序處理；不會自動重建預設帳號。

初始登入取得的 JWT 帶有必須改密碼狀態及受限權限。只有 `GET /api/v1/auth/me` 和 `POST /api/v1/auth/change-password` 可使用該權杖。新密碼遵守 6 至 100 字元；成功後原權杖仍維持受限狀態，使用者需重新登入。Backend 只儲存新密碼 BCrypt 雜湊；錯誤訊息及日誌不包含密碼。

若資料庫已初始化但 users 沒有 ACTIVE ADMIN，Backend 會停止啟動。受控恢復需先限制 Production 網路入口、確認資料庫與目標使用者、取得可還原的備份，由授權 DBA 透過受控管理連線使用 BCrypt `PasswordEncoder` 產生雜湊，再為已核准帳號設定 `ADMIN` 角色並啟用；完成後核對該帳號登入及權限並記錄操作者。不得刪除初始化標記或建立匿名管理員 Endpoint。舊版在加入此標記前若曾初始化、之後又清空 users，資料庫沒有歷史證據可辨別此狀態；升級前須由 DBA 核對既有資料與備份，不能把空 users 表直接當作全新安裝。

`admin/admin` 是首次本機初始化用的指定憑證，不適合長期或公開使用。公開部署前必須登入修改密碼、以新密碼重新登入，確認初始權杖不能呼叫人員管理 API，並確認新權杖只能由真正 ADMIN 使用。

## 人員管理

`ADMIN` 可透過 Frontend「人員管理」查看清單、新增帳號、設定顯示名稱／初始密碼／既有角色、修改顯示名稱和角色，以及啟用或停用帳號。既有 `POST/GET/PATCH /api/v1/users` 契約與 Backend `@PreAuthorize("hasRole('ADMIN')")` 維持權威；Frontend 依登入角色顯示管理頁。一般帳號的密碼最低長度維持 6 字元。

## Demo 啟動、停止與既有資料

歷史版本的 SP-903 Demo 曾使用 `compose.release-candidate.yaml` 和 `.env`；該舊 Compose 檔已自目前分支移除（需要核對時可由 Git History 取回），不再作為啟動入口。既有 Demo 的三個 volume 名稱為：

```text
supplypilot-sp903-local-demo-mysql
supplypilot-sp903-local-demo-pgvector
supplypilot-sp903-local-demo-documents
```

`compose.demo.yaml` 保留既有 MySQL、pgvector、文件 Volume 與 Project 名稱，支援標準 Compose 重建 Container 後沿用 Volume。首次切換或修正既有資料時，先人工確認 `.env.demo` 的 DB 帳密與原資料庫初始化時一致；不要把 Production 的 `.env` 當 Demo 設定。Compose 不會自動遷移或修復既有資料庫使用者密碼。

啟動 Demo：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
```

停止並重新建立 Container／Network：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml down
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
```

Production 使用 `docker compose down` 及 `docker compose up -d --build`。`docker compose down` 預設保留 Named Volume；**禁止使用 `down -v`**。`docker compose restart` 不重新讀取環境變數；`docker compose up -d --build` 會用新環境設定建立或重建需要更新的服務。

## Container 生命週期與資料庫維護

- **Restart**：重啟既有 Container，不重新讀取 Compose 環境變數。
- **Recreate**：以目前 Compose 設定替換 Container。`docker compose up` 會在服務設定或 Image 變更時重建必要 Container；Container ID 改變是正常生命週期，不是資料遺失判定。
- **Database Migration**：Backend 連線資料庫後由 Flyway 套用版本化 Schema Migration；migration 不等於清空資料或重建資料庫 Container。
- **Database Reset**：清除或重建業務資料的破壞性操作，必須使用獨立授權程序；正常啟動不執行 Reset。
- **Volume Persistence**：Container 由 Named Volume 取得持久資料。正常 `down` 不移除 Volume；資料持久性以正確的 Compose Project／Volume、Schema 及資料內容驗證，不以 Container ID 判斷。

資料庫使用者密碼的輪替是 DB 維護作業。修改 Compose Environment 不會變更已初始化資料目錄中的 DB 使用者密碼；應由 DBA 依備份、授權、停機影響及恢復驗證程序處理。容器 Image／設定更新可由標準 Compose 重建，但要先確認 Project、既有 Volume、Schema 與備份。

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml ps
```

禁止使用 `down -v`、`docker volume rm`、`docker volume prune`，也不要自行執行 Demo Reset。SP-910 r4 的 Project 和 Volume 與本 Compose 隔離。

## 驗證命令與範圍

請使用臨時測試環境檔驗證 Compose，勿讀取或輸出實際 `.env`：

```powershell
docker compose --env-file $env:TEMP\supplypilot-prod-test.env config --quiet
docker compose --env-file $env:TEMP\supplypilot-demo-test.env -f compose.yaml -f compose.demo.yaml config --quiet
```

測試檔應只含隔離的假憑證，Demo 測試檔需設置至少 6 字元的假 `DEMO_ACCOUNT_PASSWORD`。`docker compose config` 成功不代表 Runtime 啟動、密碼或 Volume 相容性通過。不要用這些驗證檔啟動真實服務。

| 驗收項目 | 狀態 |
|---|---|
| Production / Demo Compose 結構與插值 | `python -m unittest scripts.tests.test_compose_config -v`：4/4 PASS；包含標準 Compose 合併、Shell 優先權、單引號 `$` 字面值及 Production 不注入 Demo 密碼 |
| Backend Maven 完整回歸 | `cd backend; .\mvnw.cmd -B --no-transfer-progress verify`：Exit 0，629 tests、0 failures、0 errors、1 skipped；Docker Desktop Testcontainers；包含 Demo Authenticator／Seed Smoke 整合測試 |
| Docker Image Build | 隔離 Compose E2E 成功建置 Production-like 與 Demo 的 Backend、Frontend images；Dockerfile 內 Vite production build 成功。使用本輪專屬 image tag |
| Frontend Lint / Test / Build | `npm run lint` Exit 0（既有 `UsersPage.tsx:32` warning）；`npm run build` Exit 0（既有 chunk-size warning）。完整 Vitest 第一次 194/198（4 項超時），4 項單獨重跑全 PASS；無 Docker／Maven 負載時第二次完整跑 197/198，唯一失敗為 SP-806 `getByRole('#21')` 找不到按鈕，先前完整跑曾通過；相關 delayed-response 測例群單獨重跑 4/4 PASS。Frontend 程式本輪未修改 |
| Windows Docker Desktop Docker CLI | Docker Engine 29.6.1、Docker Compose v5.3.0；隔離 Docker E2E 已執行，不修改 Windows Firewall，也未接觸既有 SupplyPilot stack／Volume |
| Demo Compose Runtime | **PASS（隔離測試）**：四服務 Healthy；五個基線帳號真實 HTTP 登入及角色正確；單引號 `$` 密碼按字面值解析；`down → up` 與直接 `up -d --build` 後新密碼成功、舊密碼遭拒；文件、MySQL、pgvector marker 與 Seeder `COMPLETE` 保留 |
| Production-like Compose Runtime | **PASS（非企業 Production）**：四服務 Healthy；初始 `admin/admin` 強制修改密碼，重建後新密碼仍有效、舊密碼遭拒；Demo 基線帳號不可登入；MySQL 與文件 marker 跨 `down → up` 保留 |
| 隔離與清理 | E2E 使用唯一 Compose Project、Volume、Port、假資料庫／Demo 密碼及 image tag；結束只清理本輪建立且經 Compose labels 核對的資源，未使用 `down -v`、全域 Prune 或觸碰固定 SP-903／SP-910 r4 Volume |
| 現有環境保護 | 本輪未讀寫 `.env`、`.env.demo`，未操作現有 Demo／Dev Stack 或其 Volume，未接觸 AWS；需由使用者以文件中的標準 Compose 命令套用現有 Demo 密碼 |
| Linux Docker Engine | **NOT RUN**：本次工作環境為 Windows Docker Desktop，Linux Host 的檔案權限與 mount 行為尚未實測 |
| GitHub Actions | 本次 SHA Push 後查核對應 workflow；查核完成前不標示最終 SHA CI PASS |
| AWS、HTTPS、Gemini、Semantic Gate、午夜 Reset | 不屬於本機 Compose 驗收證據，依後續 Gate 驗收 |
