# SP-903 本機 Docker Release Candidate

## 環境區分

`compose.release-candidate.yaml` 是隔離的本機 `demo` 部署：專案名、MySQL database、pgvector database、三個 named volume 和文件根目錄都明確標記為 `sp903-local-demo`。MySQL 與 PostgreSQL 僅接在內部網路，不發布主機連接埠；前端只在 `127.0.0.1` 發布，並透過 Nginx `/api/v1` 代理到 Backend。此 demo stack 不代表企業 Production 架構。

後端 image 以 Java 21/Maven Wrapper 多階段建置，Runtime 僅含 JRE 與 executable Spring Boot JAR，UID/GID 10001 執行。建置會確認 JAR 含 `BOOT-INF/`。前端以 Node 24 建置，使用非 root Nginx Runtime、SPA fallback、hashed assets immutable cache、`index.html` no-cache、21 MB request limit、API forwarded headers，以及 120 秒 upstream timeout。API URL 是相對路徑 `/api/v1`；不把服務端 `localhost` 傳給瀏覽器，也不在 Vite bundle 注入 Secret。

Production image 與部署 profile 是兩件事。Production 必須以 `SPRING_PROFILES_ACTIVE=prod` 啟動；prod configuration 會驗證 JWT、Exact CORS Origin 和 Document Storage 必填欄位，使用 non-root DB 帳號、Flyway 和 Hibernate validate，且停用 Demo Seeder。Demo Compose 使用 `demo` profile 和 `sp-905-zh-tw-v1`，只在其專用空資料庫進行受控初始化。

## 本機建置與設定驗證

本機唯一 Secret 來源是 Repository 根目錄既有 `.env`。啟動器不建立、讀取或清除 DPAPI store，也不使用 Windows User／Machine ENV 保存 Secret。腳本只檢查必要變數是否存在／非空、JWT 是否為至少 32-byte Base64、Demo 密碼是否至少 6 字元；錯誤訊息不包含 Secret 值。Gemini key 可使用既有 `GEMINI_API_KEY`，或專案支援的 `AI_API_KEY` 別名。

在 Repository 根目錄執行唯一啟動命令：

```powershell
pwsh -NoProfile -File .\scripts\start-sp903-demo.ps1
```

啟動器從自身路徑定位 Repository 與 `.env`，以 Compose 原生 `--env-file` 載入設定，並暫時清除本腳本程序中同名 Process ENV，避免 Compose 的 shell 環境優先權蓋過 `.env`；結束時會恢復原值。Compose 將 `.env` 的 DB／JWT／Demo／Gemini Secret 明確傳給 Backend，DB 密碼也只傳給相應資料庫容器；Frontend 不會收到 Backend Secret。Root `.env` 受 `.gitignore` 與 `.dockerignore` 保護，亦不複製到 Image、不放入 Build Args。請勿輸出未遮蔽的 `docker compose config` 或 Container Environment。Docker 管理員仍可透過本機 Docker daemon 檢視 Runtime Container 環境變數，需將該主機視為受信任的本機管理環境。

Compose 固定使用獨立的 `supplypilot_demo`／`supplypilot_semantic_demo` 資料庫、`supplypilot-sp903-local-demo` Project、專用 MySQL／PostgreSQL／文件 Volume、Compose 服務名稱作為 Backend DB Host，以及 `demo` Profile。`.env` 中一般開發用的 `DB_URL`、`SEMANTIC_DB_URL`、DB 名稱、Profile、Storage 路徑及 Frontend Origin 不會被拿來設定此 Stack。若專用 SP-903 Volume 已存在，啟動器先停止並要求確認既有 DB 憑證一致性；不會重置 Volume。SP-910 r4 資源不會被此 Compose Project 操作。

CI 只使用非真實 placeholder 執行 Compose 結構檢查與 Image Build，不呼叫 Gemini。本機前端網址為 `http://localhost:8081`。Backend `GET /actuator/health` 表示 Application／DB readiness，不代表 Gemini、Semantic Backfill 或 Gate 已就緒；首次啟動仍需核對 Profile、Flyway、Hibernate Validate、Demo marker、文件與 Browser 登入。

## 資料與 Reset 安全

Compose volumes 僅屬於 `supplypilot-sp903-local-demo` 命名；不得將此檔改指向 SP-910 r4、production 或共用資料庫。首次啟動會在此新隔離 volume 建立 Demo baseline，並把五份 Seed 文件寫入專用文件 volume。一般 Restart 不執行 Reset。

Reset 會清除指定專用 MySQL、pgvector 與文件資料。執行前依 `docs/SP-901-DEMO-RESET-RUNBOOK.md` 核實目標容器、database、volume、文件根目錄、備份及授權；沿用既有 `DemoResetApplication` Guard。Reset 成功後須用真實 ADMIN UI 重新審核、Gemini Embedding Backfill、Readiness、Gate Audit 與 Semantic Query。Baseline 文件 `READY` 不代表 Semantic Gate PASS。

## 驗收證據界線與未決部署資訊

- `prod` 的設定正負向單元測試：`ProductionConfigurationTests`、`SecurityCorsTests`；Demo Seeder profile 邊界：`DemoDataInitializerActivationTests`。它們驗證設定契約，不能替代實際 prod Container 啟動及 Profile Log 證據。
- 目前可部署的本機／EC2文件儲存為持久化 local volume。雖設定合約接受 `s3` 型別，Repository 目前未提供 S3 `DocumentStorageService`；AWS 若需 S3 backend 必須另列 implementation gap，不能只設環境變數便聲稱可用。
- 本機 Docker Compose Build／Fresh Start／Migration／Browser E2E／持久化／Reset、真實 Gemini Chat／Embedding 與 Semantic Gate，需在隔離 SP-903 stack 完成並另存於 `docs/evidence/sp903/`；不能引用 SP-910 非容器化或先前 r4 的結果當作容器 PASS。
- AWS Gate 尚未進入：需確認 Account／Region／EC2 規格與存取方式、可信 HTTPS／DNS／憑證續期、日誌告警、備份／還原及每日 `Asia/Taipei` 00:00 Reset 的人工 ADMIN 審核恢復與失敗告警程序。此文件不宣稱建立了 AWS 資源或決定其入口。
