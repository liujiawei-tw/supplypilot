# SupplyPilot — Codex 專案共用指示

本檔案適用於此儲存庫內所有 Codex 工作階段及子目錄。開始工作時請先遵守本檔案，並同時遵守目前 Issue、專案規格與使用者的明確指示。


## 2026-10-24 核定：簡化開發與交付流程（最高協作優先序）

使用者已明確決定以**實際開發、獨立 Code Review 與必要 CI 測試**取代一般 Issue 的多層 Orchestrator Gate。此決策優先於本 Repo 過往的 Code Gate／Issue Gate／Release Gate／State／Contract／Reservation／Frontier 強制前置敘述。保留歷史工具與資料以供診斷，但**不要求建立或修復 Orchestrator State 才能正常進行 Code Review、CI、PR**；不得宣稱舊工具程式碼已被移除或會自動放行。個別受保護的部署、安全與資料操作仍按其明確規格授權。

一般 Issue 依以下順序執行：
1. **GPT：** 從 GitHub 讀取最新 Repo／Issue／規格與依賴，提供 Codex 實作 Prompt。
2. **Codex：** 從目前 main 建立獨立 Branch／Worktree 實作，執行開發期受影響測試，內部預檢，Commit／Push；不 Push main 或 Force Push。
3. **GPT：** 對已 Push 的精確 HEAD 獨立 Code Review（Spec、Standards、安全、交易及回歸）；有阻擋 Finding 則 Codex 在同分支修正並重新 Review。
4. **Codex：** Code Review 沒有阻擋 Finding 後，針對交付 HEAD 執行依變更／風險決定的必要 CI 等價驗證，保留原始日誌、命令、退出碼與測試結果。若測試後改動程式或測試，重新 Push 並對新 SHA Review。
5. **GPT：** 核實 CI 證據及精確 HEAD，全部必要檢查通過後核發最終交付 PASS，直接建立繁體中文 PR，獨立一行 `Closes #<IssueNumber>`。
6. **使用者：** 親自決定 Merge；合併後核對 GitHub PR merged 與對應 Issue closed（未自動關閉則依授權處理）。

不再把 `ISSUE_GATE_VERIFIED`、`BLOCKED_DEPENDENCY`、`contract_verified`、`gate_reservation`、GPT Handoff Frontier、Release Gate 或舊 State JSON 當成一般 PR 的額外必要條件。這**不是**允許失敗測試、跳過必要安全／交易／併發／授權驗證、偽造 PASS、繞過 GitHub Branch Protection，或宣稱未執行的 Gemini／Browser／AWS E2E 已完成。完整部署與跨環境 E2E 在實際上線／Release 階段另行驗證。

**#168 有兩輪修正上限。** 每輪指完成修正並實際驗證的一次交付審查；第一輪失敗才安排第二輪。第二輪仍無法穩定恢復時停止擴大 Orchestrator，依使用者核定方向另行最小化移除一般流程中的強制入口，保留必要 CI／Review 與證據；不得因上限直接宣稱不安全變更通過。#142、#145 已通過的程式審查成果須保留，僅補足交付所需 CI。



## 2026-10-24 補充核定：Issue 精準 CI 與 V2 整合後完整 CI（取代舊全量 Gate 慣例）

**使用者最新決策：** V2 一般 Issue 不再以「每張 Issue 都跑完整 Backend Maven verify／完整 Frontend Vitest」為建立 PR 的必要條件。完成所有規劃的 V2 Issues 並整合後，統一執行完整 Backend／Frontend CI 與跨模組回歸；正式本機部署及 Release 仍需各自必要的真實 Browser／RAG／Gemini／Docker 驗收，未執行不得聲稱通過。

- **每張 Issue 仍須過 Gate：** 精準 Unit／Integration／API 契約測試與受影響模組回歸、適用的 Lint／Build、Issue 明訂 Browser／可及性驗收、必要安全／授權／交易／資料庫 Migration／併發驗證，按實際 Diff、共用依賴及風險擴大範圍；**安全與跨模組直接風險不可延後到最後**。不能為了節省時間跳過必要測試。
- **已執行的完整套件失敗：** 必須保留原始非零 Exit Code、失敗案例與逐項分診。只有具證據足以確認與本 Issue 直接及間接影響無關，且本次所有必要精準測試與驗收均通過，才可標示「完整套件：DEFERRED TO V2 INTEGRATION」而不阻擋本 Issue PR。未能排除本 Issue 風險的失敗仍屬 **BLOCKED**；絕不可將完整套件原本的 FAIL 改寫為 PASS 或籠統宣稱 Flaky。
- **證據與 SHA：** Codex 保留測試命令、環境、Tests／Failures／Errors／Skipped、Exit Code、原始日誌及 Browser 圖像，綁定 GPT Review 的精確 HEAD。若最新 Commit 只有測試或文件，且產品程式與依賴未變，可重用仍有效的前一 SHA 證據，但必須明列來源 SHA、適用範圍與最新修改的精準驗證。變更產品程式／必要測試要重新驗證受影響部分，並由 GPT Review 新 SHA。
- **交付定義：** GPT 獨立 Code Review PASS + 當張 Issue 必要精準 CI／驗收 PASS + 已核對證據，方可給予 **FINAL DELIVERY PASS／LOCAL VERIFIED** 並建立繁體中文 PR；使用者親自 Merge。完整回歸標為 **DEFERRED** 不等於已通過。GitHub Actions 持續 **PAUSED BY USER / NOT USED AS GATE**。
- **最終 V2 整合 Gate：** 全部規劃 Issues 合併後，執行完整 Maven verify、完整 Vitest、Frontend Lint／Build、影響範圍內的 MySQL／PostgreSQL Migration、相關安全／交易／跨模組整合與真實 Browser E2E，修復已登記失敗並保留證據；進入本機正式驗收／AWS／Demo 前按規格再做真實 Provider 與部署測試。必要全量 Gate 未綠不能宣稱 V2 整體完成。

以下既有「本機 CI 等價驗證」及「PR Gate」條文中若將非零 Exit Code 一律視為阻擋，**以本補充決策的「本次必要檢查」與「已分診、延後的無關完整套件」之區分解釋**；其餘安全、交易、權限與人工 Merge 限制維持不變。

## Matt Pocock Skills 與 Codex／GPT 角色

> 詳細交接見 [docs/v2/matt-pocock-codex-gpt-handoff.md](docs/v2/matt-pocock-codex-gpt-handoff.md)。Skills 是可選的實作／TDD／除錯／內部 Review 方法，不是需求權威，也不取代 GPT 的獨立 GitHub Review。

- 使用者決定 Scope／授權與 Merge；GPT 讀 GitHub、下達 Prompt、獨立審查、驗證 CI 證據及建立 PR；Codex 實作、測試、Commit／Push 與修正。
- 同一時間處理多張 Issue 時，仍需確認各自的 Branch／Worktree、依賴與檔案衝突；不要求使用多層 Orchestrator Gate 或維護 State JSON。
- 上游 `implement`／`tdd`／`diagnosing-bugs`／`code-review` 可以輔助實作；不授權第三方腳本自動 Merge、建 Draft PR、改 CI／規格或部署。
- 如需 Agent Skill 最小 Repo 設定，使用 [Issue Tracker](docs/agents/issue-tracker.md) 和 [Domain 導覽](docs/agents/domain.md)，不需自動執行 setup。

## 目前開發階段：SupplyPilot V2（強制）

> **V2 規格已透過 [PR #150](https://github.com/liujiawei-tw/supplypilot/pull/150) 合併 main，目前進入 V2 功能實作階段；現有 main 業務程式仍主要是 V1 基線。** V2 Markdown、Issue 或「已決策」不等於已寫程式、驗收 PASS 或 AWS 上線。

### 開始任何 V2 Issue 前的必讀與查核

1. 先閱讀本檔、[V2 需求總綱](docs/v2/README.md)、[V1→V2 差異與 Issue 對照](docs/v2/migration-and-issue-map.md)，再閱讀**分派給此實作者的 Issue**與其 V2 專題文件；須讀取該 Issue Worktree 基底上的實際程式與 API/DB/測試契約，不得只看設計 Markdown 直接猜測實作狀態。
2. **規格優先順序：** 使用者針對目前工作明確指示及已核定的 Scope → 當前 Issue 的驗收條件與相依 → `docs/v2/` 中適用的已確認 V2 需求 → 未被 V2 覆蓋的 V1 安全／交易／錯誤／測試契約。若 Issue、規格或程式間有實質衝突，先指出檔案/條文並依 ADR/規格修正程序解決，**不得默默猜測或自行擴 Scope**。
3. 依 GitHub 即時 Issue／PR 狀態確認必要依賴；個別 Issue 在獨立 Feature Branch／Worktree 內實作，不混入其他 Issue。PR 前置改為 GPT Code Review 與必要 CI 驗證，不以 Orchestrator State／Gate 作強制限制；尚未合併的依賴不得假稱已完成。
4. **V2 的已確認刪減需求必須遵守：** Demand 與自動 Recommendation/Ranking 不屬於 V2 新主流程；不得重新加入已取消的文件級 ACL、PDF 頁碼、合約比較、供應商自動排名、PO 匯出、通知中心等功能。V1 舊文檔的描述不能覆蓋新版的 Scope。
5. **不能降低安全／交易要求：** V2 帳號簡化不代表人員管理可無授權開放；RAG Hybrid Search 不能繞過敏感資料出站限制；Kanban 操作不能略過後端 PO/Inventory 交易；失敗與併發仍須驗證。V1 測試 PASS 不等於 V2 變更已驗收。
6. **進度與驗收：** 應以 GitHub Issues、實際已合併 PR、當次 Build/Test/E2E 證據為準；未執行須明確標示 `NOT RUN`／`BLOCKED`，不得將 Fake Provider、單純文件變更或靜態檢查宣稱為真實 Gemini／Browser／AWS PASS。
7. **交付順序：** V2 Issues 依 GitHub 已核實的依賴執行；每張 Issue 獨立 Branch、GPT Review、必要 CI、PR 與人工 Merge → 本機部署與必要 Docker／真實 Browser/Gemini/RAG E2E → AWS Demo [#133](https://github.com/liujiawei-tw/supplypilot/issues/133) → 最終展示影片。**AWS 公開 Demo 共用帳號角色、上傳/出站審核/人員管理權限，要到部署階段才決策；不得擅自延用 V1 `PROCUREMENT_MANAGER` 方案或公開高權限 `admin`。**
8. 完成任何 V2 Issue 時，同步更新**受影響**的 API、Domain/ERD、Security、Frontend、RAG Runbook、測試或 README 相關說明；保留 V1 歷史測試與驗收紀錄，不得改寫舊結果假裝新版通過。

**本章只補充 V2 專案背景及規格引用；下方繁體中文 PR、單 Issue／Branch、Codex→GPT Review、Final PASS 才建立 PR、由使用者親自 Merge 的原有強制規則全部持續有效。**


### Codex 測試與 CI 驗證節奏

- 開發階段先執行與修改相關的精準測試；必要時補整合／負向案例。**獨立 GPT Code Review 後**再執行交付所需 CI；不用重跑未改動且仍有效的相同證據。
- CI 以實際 Diff／直接與間接依賴／共用元件與風險決定：Backend 變更驗證相關 Maven 測試；API／DTO／Schema／Security／交易及跨端契約擴大涵蓋 Backend／Frontend；Frontend 變更驗證必要 Vitest、Lint、Build；不明影響需擴大而非自行標 N/A。實際部署與 Docker／Browser／Gemini E2E 依交付範圍另驗。
- Codex 保留精確 HEAD、環境、指令、測試數／Failures／Errors／Skipped、退出碼與原始證據；若 CI 失敗，修正、重新 Push、重新審查變動 SHA 後驗證。**必跑測試失敗不得 PASS**，opt-in 真實 Provider Smoke 的未執行要明確列為未執行／延後，不能冒稱執行。
- 不為純文件修改強制執行無關 Backend／Frontend／Docker 套件；文件一致性與差異檢查可作為本次 CI 等價範圍。高風險變更必須擴大驗證，不可以「簡化」為由刪除、Skip 或放寬斷言。

### 使用者暫停 GitHub Actions（暫行，優先於下方 CI Gate）

- **使用者於 2026-10-22 決定：** SupplyPilot Issues 暫停使用 GitHub Actions，直到使用者親自明確通知恢復。期間統一標示 `PAUSED BY USER / NOT USED AS GATE`；不等待、查詢、重跑或依賴 Actions 結果。
- 不修改 `.github/workflows/ci.yml`、Branch Protection 或 Repository Actions 設定。這項決策只暫停使用與依賴 Actions；Push 仍可能觸發 GitHub 自動 Workflow，不得宣稱 Repository Actions 已停止或停用。
- 在 Actions 暫停期間，由 Codex 依上方影響與風險範圍執行必要本機 CI 等價驗證；所需 Java／Node、Docker／Compose 檢查依受影響範圍及部署階段決定。未通過／未執行項目如實記錄，不得冒充 GitHub CI PASS。
- 此規範適用於後續 SupplyPilot Issues，直到使用者親自解除；既有 Workflow 與歷史 Actions Run 保留原狀。


### 本機 CI 等價驗證與 GitHub 狀態區分

- 以 **GPT 已 Review 的 HEAD** 為測試對象；Codex 依改動範圍執行必要檢查，無關項目明示原因，不用一律跑完整 Release 驗證。
- Maven `verify`、Node.js 24 `npm ci`／lint／Vitest／build、Compose／Docker image build 等仍可依適用範圍採用；真實 Browser／RAG／Gemini／AWS 只在實際執行並保存證據後宣稱 PASS。
- GPT 親自獨立核對 Repo／CI 證據；本機測試只能寫 **LOCAL VERIFIED**，不能寫成 GitHub Actions PASS。非零退出碼、未解決的必要測試失敗或不足的證據均阻擋建立 PR。
- GitHub Branch Protection／必要 Status Check 不因本機 CI 而自動滿足，不能繞過；如受 GitHub 設定阻擋 Merge，需如實回報並由使用者處理。

## GitHub Pull Request 語言規範（強制）

- **所有 SupplyPilot Pull Request（預設由 GPT 建立），標題與描述一律使用繁體中文（台灣，zh-TW）。**
- PR 內容中的摘要、修改內容、驗收條件、測試結果、風險、注意事項、未完成項目及 Review 回覆，同樣使用繁體中文。
- 不得因為 Codex 或 GPT 開啟新對話、切換工作階段、切換分支或處理不同 Epic／Issue，而將 PR 內容改成英文。
- 程式碼、變數、類別名稱、檔案路徑、API 路由、CLI 指令、錯誤代碼、Git commit SHA，以及無適當譯名的技術名詞可保留原文。
- 除非使用者針對**特定 PR**明確要求其他語言，否則不得覆蓋此預設規則。
- 建立 PR 前，先檢查標題與描述是否符合繁體中文規範。


## GitHub → Codex → GPT → CI → PR（強制）

1. GPT 讀取 GitHub 當前 Issue、最新 main／現有程式、必要依賴與規格，給出限縮 Scope 的 Codex Prompt。
2. Codex 建立專用 Feature Branch（需要時獨立 Worktree），實作、執行精準開發測試與內部預檢；Commit／Push 後核對遠端 HEAD。不可動其他 Issue WIP。
3. GPT 從 GitHub 取得精確 HEAD 的完整 Diff 獨立 Code Review（Spec、Standards、Security、Transaction、Tests）；P0/P1／阻擋性 P2 先修復並回到同一分支重新審查。
4. GPT Code Review PASS 後，Codex 針對已 Review HEAD 執行必要 CI；提交測試清單、環境、exit code、原始 logs、skip 與風險。CI FAIL／BLOCKED 必須處理；若新增 Commit，GPT 重新核實新 HEAD 及其變更後再確認 CI。
5. GPT 核對受影響測試皆通過、證據綁定相同 SHA，才發出正式交付 PASS，建立繁體中文 PR（`main` ← Feature），PR 內獨立一行 `Closes #<IssueNumber>`，回讀核對。
6. 使用者親自 Merge；GPT 再查 GitHub PR merged、main Head、Issue closed。若 Issue 未關閉，回報並由有授權的人處理。

**只有 Code Review＋必要 CI 結果是一般 PR 前置，沒有額外 Orchestrator State／Contract／Issue Gate Reservation 要求。** Codex 內部 Review、開發期測試、CI、GPT Code Review／交付 PASS 必須區分，不能把未執行項目當 PASS。GitHub Actions 仍為 `PAUSED BY USER / NOT USED AS GATE`。

## GitHub PR 與 Issue 自動關閉規範（強制）

- **針對已完成的單一 Issue 建立 PR 時，PR 描述必須包含獨立一行 `Closes #<Issue編號>`。** 不得只寫「關聯 Issue：#編號」、`Refs #編號` 或一般文字連結，因為這些不會在 Merge 後自動關閉 Issue。
- 建立 PR 之前，必須核對 GitHub 的實際 Issue 編號、PR 的 Base Branch（預設 `main`）與 Head Branch，避免關錯 Issue。
- PR 建立後再次讀取 PR 描述，確認 `Closes #<Issue編號>` 確實存在且指向正確 Issue。
- **PR 只能在 GPT 確認 Final PASS 後建立，Merge 仍由使用者手動執行。** 不得為了自動關閉 Issue 而自動 Merge。
- 使用者告知 Merge 完成後，須查核 GitHub PR 的 `merged` 狀態、Merge Commit 與對應 Issue 的 `closed` 狀態；若 Issue 未關閉，明確回報並安排修正，不能假稱已自動關閉。
- 此規則適用於新對話、新工作階段及往後所有 Epic / Issue；新的 SupplyPilot 工作開始前，先讀取 Repository 根目錄 `AGENTS.md`。若目前環境無法自動取得此檔案，需明確讀取或要求提供，不能假設已載入。
