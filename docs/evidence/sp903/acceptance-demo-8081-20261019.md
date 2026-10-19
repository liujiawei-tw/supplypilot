# SP-903 #45 Demo 8081 Docker Compose 驗收

- 結果：**PASS**（本次容器化、瀏覽器登入及持久化驗收範圍）
- 驗收時間：2026-10-19，Asia/Taipei（UTC+08:00）；約 22:30–22:37
- 驗收程式碼 HEAD：`9ec872360a3126a7690b7af844207e1bdb4cef5d`
- Branch：`codex/sp903-docker-release-candidate`
- Docker Engine：`29.6.1`
- Docker Compose：`v5.3.0`
- Browser：Microsoft Edge `155.0.4283.62`，Playwright 隔離 Browser Context
- Compose Project：`[憑證重疊片段已遮蔽]-sp903-local-demo`
- Browser URL：`http://127.0.0.1:8081/login`；登入後 `/dashboard`
- Frontend Origin：`http://127.0.0.1:8081`

## Compose 與服務

以標準命令建置啟動，並以不帶 `-v` 的標準 `down` 後再次建置啟動：

```powershell
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml down
docker compose --env-file .env.demo -f compose.yaml -f compose.demo.yaml up -d --build
```

重啟後服務狀態：

| Service | 結果 |
|---|---|
| mysql | Healthy |
| pgvector | Healthy |
| backend | Healthy |
| frontend | Healthy |

- Backend `SPRING_PROFILES_ACTIVE=demo`。
- MySQL target：`[憑證重疊片段已遮蔽]_demo`；pgvector target：`[憑證重疊片段已遮蔽]_semantic_demo`。
- Frontend 僅綁定 `127.0.0.1:8081`；資料庫沒有主機公開 Port。
- Frontend `/health`：HTTP 200（重啟前後均已確認）。
- Login 與 `/api/v1/auth/me` 由真實 Browser 經 Frontend/Nginx 同源 Proxy；所有驗收呼叫 HTTP 200，沒有 CORS 阻擋或 502。
- `.env.demo` 存在且必要變數齊全；有效 Compose 密碼與 `.env.demo` 值於記憶體比對相符。密碼未輸出或保存。

## 真實 Browser 登入

以 Playwright 啟動 Edge，對每個帳號使用全新 Browser Context。瀏覽器明確導覽至 `http://127.0.0.1:8081/login`，確認 Origin 為 `http://127.0.0.1:8081`，透過登入頁 Locator 提交表單。沒有使用 API 呼叫代替 Browser E2E，也沒有保存截圖、HAR、Network Trace、Cookie 或 Token。

| 帳號 | `/auth/me` Role | Dashboard 角色標籤 | Login | `/auth/me` | 未授權人員管理頁 | Logout／Session 清除 | Console／Page Error |
|---|---|---|---:|---:|---|---|---:|
| admin | `ADMIN` | 系統管理員 | 200 | 200 | 可開啟，Users API 200 | PASS | 0 / 0 |
| manager | `PROCUREMENT_MANAGER` | 採購經理 | 200 | 200 | 顯示 403 權限拒絕 | PASS | 0 / 0 |
| buyer | `PROCUREMENT_STAFF` | 採購人員 | 200 | 200 | 顯示 403 權限拒絕 | PASS | 0 / 0 |
| warehouse | `WAREHOUSE_STAFF` | 倉庫人員 | 200 | 200 | 顯示 403 權限拒絕 | PASS | 0 / 0 |
| planner | `PRODUCTION_PLANNER` | 生產規劃人員 | 200 | 200 | 顯示 403 權限拒絕 | PASS | 0 / 0 |

每個帳號皆進入 Dashboard、登出後回到 `/login`，本機 Session Token key 已清除，登出後再導覽至 `/dashboard` 會被導回 `/login`。重啟後另以獨立 Context 重做 `admin` 登入 Smoke Test：Login 200、`/auth/me` 200、Dashboard／登出 PASS、Session 清除、Console Error 0。

## 持久化快照

所有 SQL 均為唯讀 `SELECT`。前後數值完全相同：

| MySQL `[憑證重疊片段已遮蔽]_demo` 資料表 | down 前 | up 後 |
|---|---:|---:|
| users | 6 | 6 |
| materials | 12 | 12 |
| inventory | 12 | 12 |
| suppliers | 6 | 6 |
| supplier_materials | 30 | 30 |
| demands | 13 | 13 |
| purchase_orders | 11 | 11 |
| purchase_order_items | 11 | 11 |
| stock_transactions | 5 | 5 |
| document_metadata | 6 | 6 |
| document_chunks | 6 | 6 |
| ai_monthly_token_usage | 1 | 1 |
| demo_dataset_state | 1 | 1 |

- Demo Marker：`SP-901` / `sp-905-zh-tw-v1` / `COMPLETE` / baseline `2026-10-18`，down 前後一致。
- pgvector database：`[憑證重疊片段已遮蔽]_semantic_demo`。

| pgvector 資料表 | down 前 | up 後 |
|---|---:|---:|
| semantic_chunks | 6 | 6 |
| semantic_document_indexes | 6 | 6 |
| semantic_generations | 1 | 1 |
| semantic_gate_audit | 2 | 2 |
| semantic_flyway_history | 1 | 1 |

- Document Storage Volume：`[憑證重疊片段已遮蔽]-sp903-local-demo-documents`；down 前後皆 6 檔、4,551 bytes，以下相對路徑內容 Manifest SHA-256 完全相同：`0e875211d60944840b6a035bf8bc930ae23ccfa604fa82d6ad17c2077055647d`。
- 前次唯讀快照記錄 6 檔、28,672 bytes 配置容量；本次重算 `du` 為 28 KiB，與該配置容量一致。本次另以精確檔案位元組及固定相對路徑演算法建立 down 前後可直接比較的摘要。
- 三個 Volume 在 down 前、down 後都存在，Compose Project 標籤均為 `[憑證重疊片段已遮蔽]-sp903-local-demo`，邏輯名稱與掛載位置符合目前 Compose 設定。
- Backend 重啟記錄 `DEMO_DATASET_ALREADY_COMPLETE`；未觸發 Seed、Reset 或重複建立基線資料。

## 資源隔離及安全

- 重啟前後唯一運行的 Compose Project 為 `[憑證重疊片段已遮蔽]-sp903-local-demo`；唯一運行容器為其四個服務。
- Production 與 SP-910 r4 容器未被操作；其 Volume 名稱／歸屬與 Demo 不同。Demo DB 沒有主機 Port 映射。
- `down` 沒有使用 `-v`；沒有 Reset、清庫或 Prune。
- 沒有修改產品程式碼、`.env.demo`、資料庫帳號／權限或既有業務資料。
- 沒有把密碼、JWT、Cookie、Authorization Header、完整 Headers 或 Network Trace 寫入證據。
- Gemini E9、AWS、午夜 Reset：NOT RUN（不在本次範圍）。
- 產品缺陷：未發現。第一輪測試曾因 Locator 按鈕名稱空白字元不符而調整測試 Locator；程式與產品依賴未變更，最終所有 Browser 案例 PASS。
