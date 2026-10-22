# Branch 與 Worktree 政策

每張 Issue 使用 codex/issue-<number>-<slug> Branch 及專屬 worktree。Codex Desktop managed worktrees 預設 detached HEAD；以其 Git UI「Create branch here」或受控 Git 操作建立該 Issue branch 後，再驗證 branch、HEAD 和 root。

開始前核對 GitHub Issue/PR、branch 是否存在、worktree list、worktree status、index/unstaged diff、base SHA、同 Issue 是否已有 owner，以及 Issue 依賴和使用者授權。既有 worktree 有 WIP 時，只能安全接續其原狀，不重建覆蓋、不 reset、不 clean、不 checkout 另一 Issue。

主控可允許下游 agent 對上游 branch 唯讀取契約；記錄來源 Issue、branch、SHA（或未提交 diff 狀態）、契約、驗證與 merge 狀態。禁止直接寫上游 worktree。

禁止 reset --hard、clean、force push、刪 branch/worktree、cherry-pick 未驗證成果、跨 Issue merge、merge main、改 branch protection。衝突就停下並回報檔案與原因。

Codex subagents 具平行工作能力，但官方文件未承諾每個 subagent 自動取得隔離 worktree。主控必須實際建立並確認各 worktree 的獨立路徑和 branch，再指派寫入工作；無法證明隔離則只派唯讀工作或停止。
