SupplyPilot Issue #162 — 正式獨立 Code Review
CHANGES REQUIRED

本輪 Reviewed HEAD： cbd9d34abb151184b94a3beeeeb011a2428b12a9

一、GitHub 獨立核對

項目



查核結果




Repository



liujiawei-tw/supplypilot




Issue



#162，Open




遠端 Branch HEAD



完全相符




實際 main SHA



9240d6aeeba2361476a0454ac965a214769638a0




相對 main



Ahead 12／Behind 0




相對前次 617058da



新增 1 個 Commit




完整 Diff



30 個變更檔案




本機工作樹乾淨



Codex 回報；GPT 未獨立核實

我已直接閱讀 GitHub 原始碼、完整變更清單、Issue 驗收條件、AGENTS.md、Skill／State Machine、Full Gate Manifest、單元測試及交接證據。

二、上一輪 Findings 複審

Finding



本輪判定



查核結果




P1-17：過期 Full Gate 仍能取得 Final PASS



已修正



Main Sync、Final Attestation 及 PR Gate 均加入失效核對




P2-01：PR_OPEN 未核實實際 GitHub PR



已修正



使用 gh pr view 核對實際 PR 狀態與身分

P1-17：已修正

Orchestrator L350–398、L1200–1260、L1980–2015

新增 _release_gate_failure() 統一核對持久化 Full Gate 狀態、原始證據檔 SHA-256、目前 Head、main Base、契約版本與依賴。

state_attest_gpt_review() 在確認前及取得 State Lock 後都重新檢查；can_open_pr() 也會呼叫該函式並核對即時 origin/main。

當 Main Sync 使其他 Issue 的驗證過期時，其 WAITING_GPT_REVIEW 或 GPT_FINAL_PASS 狀態會退回 CHANGES_REQUIRED。

對前次 P1-17 的具體問題而言，修正成立。

P2-01：已修正

Orchestrator L1949–1978

_verify_github_pr() 現在核對 PR：

確實存在且 OPEN。

Repository 與 Base main 正確。

Head Branch 與完整 SHA 相符。

PR URL 正確。

實際 GitHub PR Body 含獨立 Closes #162 行。

不再只接受呼叫端提供的 PR Metadata。

三、本輪新增阻擋性 Finding
P1-18 — PR Gate 未重新核對遠端 Feature HEAD

Priority：P1 · Exact-SHA Release Authorization

檔案： scripts/supplypilot_orchestrator.py L350–373

觸發條件

假設 Issue B 已完成所有必要 Gate，取得 GPT 對 SHA A 的 Final PASS，且持久化 Orchestrator State 正確記錄 SHA A。

此時 Feature Branch 被另一個操作推送到 SHA B，但沒有執行 Orchestrator 的 state set-head。

現在呼叫：

can_open_pr(state_path, issue_number, SHA_A)

函式會核對：

持久化 Head、Review SHA 與 Attestation。

Full Gate 證據及其雜湊。

契約、依賴及 main。

即時 origin/main。

但它沒有核對即時 origin/<feature-branch> 是否仍為 SHA A。

因此，在遠端 Feature Branch 已經指向 SHA B 時，can_open_pr() 仍可能回傳允許建立 PR。

雖然後續 PR_OPEN 會對實際 GitHub PR Head 進行檢查，並拒絕不一致的 SHA，但PR 此時可能已經建立，這違反 #162「只有 GPT 對確切遠端 Head Final PASS 後才可建立 PR」的硬性限制。

最小修正

在 can_open_pr() 的正式放行路徑加入 _verify_local_remote_feature_head(record) 或等價唯讀核對，要求實際遠端 Feature SHA 與目前 Reviewed／Persisted SHA 完全一致。無法查詢或不一致時 Fail Closed。

新增負向測試：持久化 Full Gate 與 GPT PASS 對應 SHA A，但模擬遠端 Feature Branch 已前進至 SHA B；can_open_pr() 必須拒絕，不能等 PR 建立後才發現。

這是本輪唯一確認的新 P1。沒有發現需要重新設計整套 Orchestrator 的理由。

四、Manifest／Runner 子項審查

我重新閱讀了本 SHA 的 Full Gate Manifest 及相關 Runner 信任邏輯。

確認 Manifest 仍完整要求 Backend Maven Verify、Frontend npm ci／lint／完整 Vitest／build、Compose Tests／Config 與兩個 Docker Image Build，並檢查必要執行環境版本。

Full Gate 執行前後均會核對已獨立審查的 Manifest／Runner SHA-256；Head 更新後舊基線不能直接沿用。本輪沒有發現新的 Manifest 子項阻擋問題。

Manifest／Runner Trust 子項：PASS。

這僅核准這個確切 SHA 的 Manifest 信任設計，不代表上述完整測試已執行或通過，也不能替代整體 Final PASS。

五、測試證據與交付狀態

我直接閱讀了 P1-17／P2-01 回歸紀錄 與驗收文件。

項目



GitHub 上的 Codex 紀錄



判定




P1-17／P2-01 最終精準回歸



3/3 PASS，6.040 秒



Codex 紀錄




StateTests



54/54 PASS，103.080 秒



Codex 紀錄




GateTests



5/5 PASS，0.018 秒



Codex 紀錄




Python Compileall



PASS



Codex 紀錄




git diff --check



PASS



Codex 紀錄




完整 Orchestrator 單次套件



本輪未執行



NOT RUN




Frontend 完整 Vitest



歷史 208/210，SP-806 失敗



FAIL




Python 3.12 等價環境



尚未具備



NOT RUN




本輪完整本機 Gate



未執行



BLOCKED／NOT RUN




GitHub Actions



使用者暫停



NOT USED AS GATE

以上測試結果來自已提交的 Codex 紀錄，並非 GPT 重新執行。

本輪 GPT 親自執行的測試：無。 本次獨立驗證方式為遠端 GitHub 程式碼、Diff、測試原始碼與證據檢查。

依 AGENTS.md，上述精準 PASS 不足以取代完整本機 Gate。

六、整體正式 Verdict

Issue #162

CHANGES REQUIRED

Reviewed HEAD： cbd9d34abb151184b94a3beeeeb011a2428b12a9

程式碼複審： P1-17、P2-01 已修正；新增 P1-18。

Manifest 子項： PASS。

Full Local Gate： BLOCKED／NOT RUN。

建立 PR 資格： 不具備。

下一輪只需要對 P1-18 進行最小修正，並補上遠端 Feature HEAD 漂移的負向測試。即使該修正完成，仍必須在完整本機 Gate 真正通過後，才能取得整體 Final PASS。

本次未建立 PR、留言、修改 GitHub 或 Merge。#160、#142、#143 維持暫停。

單一整體 Verdict：CHANGES REQUIRED。

以下三行僅為這次 Manifest／Runner Trust 子項的獨立核准，不代表整體 Final PASS：

Reviewed SHA: cbd9d34abb151184b94a3beeeeb011a2428b12a9

Reviewed scripts/supplypilot_full_gate.py

FULL_GATE_MANIFEST_REVIEW: PASS

Conversation URL: https://chatgpt.com/g/g-p-6ac65e558f8c8191b315bd0a9e60198e-supplypilot/c/6ad8e7e9-0e84-83ec-845a-46ccce61e1c4
