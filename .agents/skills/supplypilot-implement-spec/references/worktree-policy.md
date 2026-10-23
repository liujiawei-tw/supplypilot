# Branch 與 Worktree 政策

每張 Issue 使用 codex/issue-<number>-<slug> Branch 及專屬 worktree。Codex Desktop managed worktrees 預設 detached HEAD；以其 Git UI「Create branch here」或受控 Git 操作建立該 Issue branch 後，再驗證 branch、HEAD 和 root。

開始前核對 GitHub Issue/PR、branch 是否存在、worktree list、worktree status、index/unstaged diff、base SHA、同 Issue 是否已有 owner，以及 Issue 依賴和使用者授權。既有 worktree 有 WIP 時，可用 `state adopt` 唯讀保存 branch、HEAD、index、unstaged diff、untracked files 與內容指紋；此操作不代表測試通過或允許恢復工作。恢復前用 `state resume --code-gate-pass` 再驗證使用者授權、目前 Code Gate、依賴、base 與指紋；指紋改變時重新採用，保留舊快照紀錄，再由使用者確認目前 WIP。沿用原路徑，不重建覆蓋、不 reset、不 clean、不 stash、不 checkout 另一 Issue。

主控可允許下游 agent 對上游 branch 唯讀取契約；記錄來源 Issue、branch、SHA（或未提交 diff 狀態）、契約、驗證與 merge 狀態。禁止直接寫上游 worktree。

禁止 reset --hard、clean、force push、刪 branch/worktree、cherry-pick 未驗證成果、跨 Issue merge、merge main、改 branch protection。衝突就停下並回報檔案與原因。

所有 Git 檢查以唯讀方式執行。採用只記錄既有狀態；Resume 不略過 Code Gate、測試 Gate、GPT Review 或使用者授權。不要以 HEAD 相同推定 index、工作目錄或 untracked files 相同。真實其他 Issue 的 Worktree 僅按使用者指示唯讀檢查，不在 Dry Run 中註冊、恢復或執行測試。

Codex subagents 具平行工作能力，但官方文件未承諾每個 subagent 自動取得隔離 worktree。主控必須實際建立並確認各 worktree 的獨立路徑和 branch，再指派寫入工作；無法證明隔離則只派唯讀工作或停止。

首次 Adopt 不得要求既有 WIP 的 base 等於新 main。協調器須從實際 Git commit graph 計算 WIP HEAD 與記錄 main 的 merge-base，保存真實 `base_sha` 並將舊 base 標記 `STALE_BASE`。Adopt 只做唯讀快照；舊 base 的 WIP 不可 Resume 或 Release，必須先安全整合最新 main 並重驗依賴/契約，再重新確認 WIP 指紋。禁止以傳入 JSON 偽造較新的 base SHA。
