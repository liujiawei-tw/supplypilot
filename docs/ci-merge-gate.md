> **2026-10-24 最新交付流程：** GPT 讀 GitHub Issue／Repo → Codex 實作、Commit／Push → GPT Independent Code Review → Codex 執行必要 CI → GPT 建立 PR → 使用者 Merge／核對 Issue Closed。舊 Orchestrator Code Gate／Issue Gate／Release Gate／State 不再是一般 PR 強制前置。以下保留的是既有 GitHub Workflow 的**實際設定說明**，不代表目前正在使用該 Workflow。
>
> **GitHub Actions：`PAUSED BY USER / NOT USED AS GATE`。** 未經使用者恢復，不手動啟動或依賴 Actions；Push 仍可能依 GitHub Workflow 自動觸發，不宣稱 Actions 已停用。Codex 在 GPT Code Review 後依實際 Diff、直接／間接依賴、安全／交易／併發／跨端風險選擇必要本機 CI；保存完整日誌、退出碼、失敗及 skipped／未跑項目，GPT 核實後才建 PR。文件改動不要求無關的全量 Backend／Frontend／Docker；必要 CI 失敗一律阻擋 PR。Branch Protection 的必要狀態檢查不因本機驗證而自動滿足，不能繞過。

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
