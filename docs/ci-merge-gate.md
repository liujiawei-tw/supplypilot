# CI Merge Gate

Workflow：`.github/workflows/ci.yml`

## 觸發條件

- 任一分支的 push。
- 目標為 `main` 的 Pull Request 在開啟、重新開啟或更新時。

## Checks

GitHub Actions 會顯示下列固定名稱的 checks：

- `Backend CI`：Ubuntu runner 使用 Java 21、Maven cache 與 Docker。執行一次
  `bash ./mvnw -B --no-transfer-progress verify`，保留 Surefire／Failsafe 報告 7 天。
  Testcontainers 會在 runner 的 Docker Engine 啟動 MySQL 8.4 與 pgvector PostgreSQL。
- `Frontend CI`：Ubuntu runner 使用 Node.js 24 與 npm cache，依序執行 `npm ci`、
  `npm run lint`、`npm test`、`npm run build`。Build 使用非機密的
  `VITE_API_BASE_URL=http://localhost:8080/api/v1`。

每個 job 都設定 timeout；指令的非零 exit code 會使該 check 失敗。Workflow 未使用
`continue-on-error`、`|| true`、正式 API endpoint 或機密資訊。

## Required status checks

CI Status Checks 與 GitHub 強制 Merge Gate 是不同設定。目前此 private repository 的
Rulesets API 回覆 GitHub Pro 或公開 repository 才可使用，且目前連線無法讀取 `main`
branch protection（403）。因此此變更只能提供 check 的紅綠燈，不能證明失敗 PR 已被
GitHub 強制阻擋合併。

若 repository 的方案與管理介面提供 branch protection，管理者應對 `main` 啟用
「Require status checks to pass before merging」，並選取：

- `Backend CI`
- `Frontend CI`

完成設定後，須以失敗的 PR 實際確認 GitHub 阻擋 Merge。
