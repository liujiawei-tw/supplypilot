# PR 與 Merge 政策

一張 Issue 對應一條 Feature Branch、一張 PR。PR Base 為 main，標題/描述使用繁體中文台灣用語，描述含獨立一行 Closes #<number>。

只有在 GPT 對該確切 Head SHA 回覆 FINAL PASS 後才可建 PR。內部 Reviewer、lint/test、agent 自述或舊 SHA Review 均不能代替。Push 新 commit 或上游契約改變即使舊 Final PASS 失效，須重做必要驗證與 GPT Review。

Codex/Orchestrator 不建立未獲核准的 PR、不建立 Draft PR、不關閉 Issue、不 Merge。Merge 由使用者人工執行。進入 `MERGED` 或執行 `state sync-main <state.json> --issue <number>` 時，協調器透過 `gh pr view` 核實 PR 已 merged 至 `main` 且取得 merge commit，透過 `gh issue view` 核實 Issue Closed，再用 `git ls-remote origin refs/heads/main` 取得即時 main SHA。呼叫者提供的 JSON、SHA 或 Agent 自述不能取代查核；GitHub 或遠端資訊不足時不改狀態。驗證之前不得推進依賴或宣告 MERGED。

成功同步會記錄 main SHA 歷史事件，將相依 Issue 的舊 Gate／Review 證據標為 `STALE`，保留舊 SHA 與證據。若 main diff 涉及 backend/frontend source、API／DTO／Schema／migration／contract 路徑或資料格式，契約核驗也會失效；無法讀取 diff 時採 fail-closed。協調器不更新下游 `base_sha`，也不改動任何 feature branch/worktree；須先重新核對依賴、contract 與實際整合狀態。

唯一例外修復入口為 Issue #166 的 `state recover-merged`，固定核對 #162／PR #165 的 live GitHub REST API 事實、目前 `main` 與 merge ancestry。預設只產生 dry-run 預覽、外部備份及 diff manifest；實際 State 寫入須在獨立核准後互動確認，並在共用 State lock 下重新查核 GitHub、重驗 State／WIP／owner/Gate、備份及原子更新。命令拒絕一般 Issue、PR 或 caller JSON 證據，不回填 Issue Gate、Release Gate 或 GPT Review PASS；重複 Recovery、活躍 owner/Gate、WIP 指紋變化或 API 查核失敗均採 fail-closed。
