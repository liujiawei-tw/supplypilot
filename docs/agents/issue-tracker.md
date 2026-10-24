# SupplyPilot — Agent Skills 的 Issue Tracker 設定

> 供 Matt Pocock `implement`／`code-review` 等 Skill 尋找既有 GitHub Issue 使用。這份文件不是建立新 Issue、改寫 `AGENTS.md` 或修改 Workflow 的授權。

## Tracker

- **類型：** GitHub Issues。
- **Repository：** `liujiawei-tw/supplypilot`（https://github.com/liujiawei-tw/supplypilot）。
- **PRs as a request surface: no.**
- **唯一執行權威：** [根目錄 AGENTS.md](../../AGENTS.md)；產品規格採 [V2 規格總綱](../v2/README.md)，遵守個別 Issue 的前置依賴與驗收條件。
- **本次工作：** 已核准的單一 Issue；絕不因 Skill 的 `to-spec`、`to-tickets` 或 `triage` 模板而自行新增、關閉、重排其他 Issue。

## GitHub CLI 唯讀讀取（Windows Codex CLI）

```powershell
gh auth status
gh repo view liujiawei-tw/supplypilot
gh issue view <IssueNumber> --repo liujiawei-tw/supplypilot --json number,title,body,state,comments,labels
gh pr view <PRNumber> --repo liujiawei-tw/supplypilot --json number,title,body,baseRefName,headRefName,headRefOid,state,mergedAt
```

- 若 `gh` 無法使用，改採已連線且授權的 GitHub 工具讀取；不得假稱已成功查詢。請勿在 Log 印出 Secret／Token。
- 交付前核對 `origin/main`、當次 Issue、前置 PR、Branch 與 Head SHA。Skill 需要查詢 Spec 時，以 Issue 與 `docs/v2/` 對應契約為準。
- **寫入限制：** Codex 僅能按 `AGENTS.md` 在當次 Feature Branch 實作、Commit／Push；GPT 先對遠端確切 HEAD 獨立 Code Review，Codex 再跑必要 CI；GPT 核實證據後建立繁體中文 PR（含獨立 `Closes #<IssueNumber>`），使用者自行 Merge。舊 Orchestrator State／Gate 非一般 PR 前置。禁止 Skills 自行開 PR、Merge、改 Label、關閉 Issue。
- 若 `code-review` 需要 fixed point，使用該次驗證的 base commit／`main`，先核對引用存在；Reviewer 須記錄實際比較 SHA。內部 `code-review` 不等於 GPT Final PASS。

完整交接請讀 [Matt Pocock V2 協作文件](../v2/matt-pocock-codex-gpt-handoff.md)。
