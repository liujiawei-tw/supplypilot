# 測試與共享資源政策

依根目錄 AGENTS.md 執行。開發期間先跑受影響精準測試，交易、安全、schema 或跨模組風險時擴至相應整合測試。禁止刪減/skip 測試、放寬 assertions/timeouts、將 mock 結果當作真實 provider/browser/AWS gate。

預設並行上限：implementer 3；Full Vitest 1；Maven Verify 1；Docker/Testcontainers 高負載工作 1；共用高風險檔案同一 owner。硬體及近期實測可支持更低限制；不得未驗證就提高上限。

在高負載工作前以 `python scripts/supplypilot_orchestrator.py resource run --issue <number> <vitest|maven|docker> -- <command> [args...]` 取得鎖並執行命令；以 `resource status` 檢查持有者。預設鎖與主狀態路徑在 `git rev-parse --git-common-dir` 下的 `supplypilot-orchestrator/`，確保所有 linked worktrees 共用同一組鎖；明確指定 `--state-dir` 時，所有工作樹必須傳入同一絕對路徑。進程持有原子目錄鎖；OS 在進程退出後會執行正常清理。若進程崩潰留下紀錄，工具只報告 stale 並拒絕自動回收，須由確認該持有者已停止的維護者手動處置。不要無限重試，也不刪除 Docker volume 或停止其他服務。

完整交付依 AGENTS.md 本機 Gate 範圍執行，保存命令、版本、原始 log、SHA 和 PASS/FAIL/BLOCKED/NOT RUN。GitHub Actions 是否可用以使用者現行設定和實際 run 為準；此 Skill 不啟動 workflow。
