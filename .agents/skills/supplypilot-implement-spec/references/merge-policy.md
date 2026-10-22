# PR 與 Merge 政策

一張 Issue 對應一條 Feature Branch、一張 PR。PR Base 為 main，標題/描述使用繁體中文台灣用語，描述含獨立一行 Closes #<number>。

只有在 GPT 對該確切 Head SHA 回覆 FINAL PASS 後才可建 PR。內部 Reviewer、lint/test、agent 自述或舊 SHA Review 均不能代替。Push 新 commit 或上游契約改變即使舊 Final PASS 失效，須重做必要驗證與 GPT Review。

Codex/Orchestrator 不建立未獲核准的 PR、不建立 Draft PR、不關閉 Issue、不 Merge。Merge 由使用者人工執行。使用者告知已 Merge 後，透過 GitHub 核實 PR merged、merge commit、main SHA 和 Issue closed；驗證之前不得推進依賴或宣告 MERGED。
