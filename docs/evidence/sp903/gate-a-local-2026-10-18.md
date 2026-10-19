# SP-903 Gate A 本機建置與 Production 設定證據

**狀態：Gate A image build 與隔離 Production profile smoke PASS；完整 Gate A 尚未宣告完成。** 本紀錄只涵蓋下列本機操作，未包含 SP-903 Demo Compose Fresh Start、真實 Gemini 或完整 E9 Browser 驗收。

## 執行環境

- Repository：`liujiawei-tw/supplypilot`
- Base／執行程式 SHA：`dd5484d6cb3288d81b90e0d6f6c83d2f2d5912e8`
- Feature Branch：`codex/sp903-docker-release-candidate`
- 執行時間：2026-10-18 09:18（Asia/Taipei）
- Docker Desktop：4.82.0；Engine：29.6.1；Compose：5.3.0；平台：Windows amd64／Linux containers
- r4 保護檢查：只有 `supplypilot-sp910-r4-mysql` 與 `supplypilot-sp910-r4-pgvector` 仍運行；沒有連接、停止、讀寫其資料或 volumes。

## Image 建置與本機檢查

| Image | 本機 Image ID | Runtime UID | Layers | 結果 |
|---|---|---:|---:|---|
| `supplypilot-backend:sp903-rc` | `sha256:b90a4657808fb94a76a434215412f30a30d761096347e9908f502c3aebaef6a9` | `10001:10001` | 8 | PASS |
| `supplypilot-frontend:sp903-rc` | `sha256:43a0de18d27be0ec4b6e3a9f82ba8aff5e4b8fd1bad3dfd999b88de7958e1b5d` | `101` | 11 | PASS |

Backend 以 Java 21 JDK 建置，Maven Wrapper 成功封裝 Spring Boot 4.1.1 executable JAR；確認 archive 有 `BOOT-INF/`。最終 Runtime 使用 Java 21 JRE。Frontend 使用 Node 24 `npm ci` 與 Vite production build；Nginx 使用非 root image。兩個 image 的 Config Env 及 history 以 Secret 名稱／`.env` 參照做檢查，未發現這些變數或檔案參照（只記錄布林結果，沒有輸出值）。這是 image metadata/history 檢查，不是外部專業 Image 掃描器報告。2026-10-18 最終重建由 Compose 正式 service build 執行，未啟動服務或建立 volumes；本機 Image ID 已更新如表。

- `docker compose -f compose.release-candidate.yaml config --quiet`：PASS（只使用虛構設定佔位值）；`docker compose ... build`：兩個 image 均 PASS
- `$env:VITE_API_BASE_URL='/api/v1'; npm run build --prefix frontend`：PASS；既有 Vite 500 KB chunk 警告，最大 JS bundle 1,482.81 KB／gzip 467.80 KB。
- `./mvnw.cmd -B --no-transfer-progress '-Dtest=ProductionConfigurationTests,DemoDataInitializerActivationTests,SecurityCorsTests' test`：13 tests、0 failures、0 errors、0 skipped。
- PowerShell start wrapper parser：PASS；缺少 Secret 時 fail-fast，且無敏感值輸出：PASS。
- 本節為此前 DPAPI 實作階段的歷史證據；該 Launcher 已依最新決策移除，不能作為目前 Secret 流程的驗證結果。
- `git diff --check`：PASS。

## Production profile 隔離 Container Smoke

使用最終 Backend image 啟動一次性 `prod` profile Backend，連到兩個另開的 tmpfs-only MySQL／PostgreSQL container。三者使用專屬 `supplypilot-sp903-prod-smoke*` 名稱及隔離 Docker network，不掛持久 volume、不對外發布 DB port；測完已移除這三個 containers 與 network。

- 啟動日誌：`The following 1 profile is active: "prod"`；`Started SupplyPilotApplication`。
- 使用 MySQL 非 root `smoke_user` 成功完成 Flyway 共 20 migrations，最新成功版本 V20。
- Application 啟動後由容器內呼叫 `GET /actuator/health`，回 `200` 且 `status=UP`；JPA EntityManager 建立成功，`ddl-auto=validate` 無 schema validation failure。
- `users` 筆數為 0：此 prod profile 沒有 Demo Seeder。
- 無效 Base64 JWT 的第一次啟動以非零失敗；這是預期負向結果。無法連線的錯誤 MySQL URL 亦以 Exit 1 在 Flyway 初始化期間 fail fast，且沒有標記為啟動成功。
- 啟動時的 MySQL 8.4 `caching_sha2_password` 連線先重現 `Public Key Retrieval is not allowed`；Release Candidate JDBC URL 已加 `allowPublicKeyRetrieval=true`，production smoke 隨後通過。這只適用於本機服務間的 demo Compose，不代表 TLS Production 設計。
- 2026-10-18 09:25（Asia/Taipei）以最新 Backend Image ID `sha256:b90a4657808fb94a76a434215412f30a30d761096347e9908f502c3aebaef6a9` 再跑同一 prod smoke：profile log 為 `prod`、Flyway 20 migrations 到 V20、Application 啟動完成、容器內 `wget /actuator/health` 成功、Demo `users` row count 為 0。MySQL／pgvector 僅使用隔離 internal network 與 tmpfs，完成後移除全部暫存容器與 network。

## 尚未執行

- SP-903 Demo Compose 新 volumes 的 Fresh Start、五份 seed 文件、前端 Browser login／採購流程、restart persistence、Guarded Reset 與 Reset 後重建 Gate 尚未跑。
- 真實 Chat／Embedding、Semantic Gate、Source Citation 尚未在本 Release Candidate 容器中跑。此工作階段 Process／User／Machine 都沒有 `GEMINI_API_KEY`、`AI_API_KEY` 或 SP903 Demo credentials；我沒有從 r4 擷取或接觸其 Secret。
- AWS／HTTPS／午夜排程尚未開始。
- GitHub Actions Container Job 尚未由 GitHub 執行。

Compose 預定使用三個全新專屬 volumes；盤點時沒有既有 SP-903 volume/network，也沒有 `127.0.0.1:8081` port 衝突。首次建立只在完整核對目標後執行。受控 Reset 屬破壞性操作，須在隔離資料建立後依 Runbook 核對精確目標並取得授權。

## 2026-10-18 `.env` 啟動流程修訂

- 已刪除 `scripts/secret-launcher-sp903.ps1`，並將 `scripts/start-sp903-demo.ps1` 改為讀取 Repository 根目錄既有 `.env`。未修改 `.env`，不建立或清除 DPAPI Store。
- `.env` 變數名稱／非空狀態已在本機檢查，未輸出值；必填 Secret 名稱均存在，Gemini 使用既有 `AI_API_KEY` 別名。`JWT_SECRET` 格式檢查失敗：不是符合後端要求的 Base64、至少 32-byte 金鑰，因此啟動腳本在 Docker 操作前 fail fast。需要使用者在本機自行修正此變數；本紀錄不含其值。
- PowerShell 5.1 AST parser：PASS。使用空白臨時 `.env` 的缺少變數測試：PASS，僅回報 `DB_USERNAME` 名稱並在 Docker 呼叫前停止。
- `ProductionConfigurationTests,DemoDataInitializerActivationTests,SecurityCorsTests,DemoDatasetCoordinatorPasswordTests`：16 tests、0 failures、0 errors、0 skipped。
- `docker compose ... config --quiet`：PASS；Compose JSON 在記憶體中驗證後僅記錄布林結果：Backend DB URL／DB 名稱為獨立 demo 目標、三個 Volume 名稱為 SP-903 專用、Frontend 未配置任何 Backend Secret。未輸出 Compose 設定或 Secret 值。
- `.env` 在 `.gitignore`、`.dockerignore` 中受保護，`git check-ignore` 確認忽略，且工作樹沒有 `.env` 變更。
- Docker Engine 唯讀盤點：Engine 29.6.1；沒有列出容器；存在 SP-910 r4 命名的 MySQL／PostgreSQL Volumes，沒有 SP-903 專用 Volume。此次操作未啟動、停止、修改或清除任何容器或 Volume。
- 由於 `.env` 的 `JWT_SECRET` 格式不符，SP-903 Docker Stack、Backend／Frontend health smoke 與 Gate B 尚未執行。修正前不會連線或初始化任何資料庫。
