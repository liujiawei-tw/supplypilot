# SupplyPilot — Codex 專案共用指示

本檔案適用於此儲存庫內所有 Codex 工作階段及子目錄。開始工作時請先遵守本檔案，並同時遵守目前 Issue、專案規格與使用者的明確指示。

## Issue #162 提案：受控跨 Issue 平行（隨 PR 合併後生效）

本節與本分支的 supplypilot-implement-spec Skill 為 Issue #162 的未合併提案。Issue #162 PR 合併至 main 前，main 上現行 AGENTS.md 與原有單 Issue 序列規則仍為全專案權威；本提案不能用來啟動其他 Issue 的平行實作。

合併後，只有在使用者列出授權 Issue 集合、Orchestrator 驗證依賴圖及 Code Gate，並為每張 Issue 核實獨立 Worktree/Branch、檔案所有權及共享測試資源時，才可平行開發。Code Gate 可按使用者明確授權記錄 provisional contract；每張 Issue 的 GPT Review／PR 前執行依實際影響範圍選測的 Issue Gate。完整 Release Gate 保留給整合後 Release Candidate，不由 Issue Gate PASS 推導，也不作一般 Issue PR 前置。每張 Issue 獨立測試、Commit/Push、GPT Review、PR 和使用者 Merge。完整規範見 .agents/skills/supplypilot-implement-spec/ 與 docs/v2/matt-pocock-codex-gpt-handoff.md。

Planner 的 `ready_frontier` 只代表 Code Ready；Issue Gate Eligible／Verified、Release Gate Verified、GPT Handoff Ready 與確切 SHA 的 GPT Final PASS 分層輸出。`gpt_handoff_frontier` 要求新的 SHA 綁定 Issue Gate；舊 Full Gate 或 Targeted 記錄不會自動升級為 Issue Gate。`release_frontier` 僅包含已取得 GPT Final PASS 的 Issue，不表示整合 Release Gate 通過。Full Gate manifest／runner 仍須獨立信任核對、執行前後 SHA-256 驗證。Active file owner 的目錄/檔案重疊會阻擋新派工；existing WIP 必須先唯讀 Adopt，再經 Code Gate、指紋、依賴、base 與使用者授權核對才可 Resume。高負載完整驗證共用一個互斥資源鎖；異常中止保留鎖供診斷。Merge/Main SHA 只能由 GitHub PR/Issue 狀態與即時 `origin/main` 核實後推進；下游舊證據標為 STALE 並保留歷史，不自動覆寫 Worktree。

## V2 的 Matt Pocock Skills 協作定位（強制）

> 詳細契約與新對話對接：[docs/v2/matt-pocock-codex-gpt-handoff.md](docs/v2/matt-pocock-codex-gpt-handoff.md)。**Skills 是 Codex 的工程方法庫，不是第三位 Agent，也不覆蓋本檔、Issue 或 V2 規格。** 本節只決定使用邊界，不代表 Skills 已安裝或通過測試。

- **Matt Skills 最小相容設定：** GitHub Issue Tracker 使用 [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md)；領域文件導覽使用 [docs/agents/domain.md](docs/agents/domain.md)。這兩份已採既有規則設定，**不需要為使用這些設定自行執行會寫入檔案／Labels 的完整 setup**；若上游版本另有必要要求，先只讀核對並回報差異。

1. **角色不變：** 使用者決定需求及 Merge；Codex 以 Matt Pocock 的 `implement`／`tdd`／`diagnosing-bugs` 輔助單一 Issue 的程式實作與驗證，`code-review` 僅作 Codex 內部預檢；GPT 仍須針對已 Push 的確切 Head SHA **獨立審查** Spec／Standards／Security／Transaction／Tests，GPT Final PASS 後才建立 PR。
2. **跨 Issue 平行須經 Orchestrator Gate：** 使用者授權的 Issue 集合，在 Issue DAG、依賴、Code/Issue Gate、獨立 Worktree/Branch、檔案 owner 與測試資源均經 SupplyPilot Orchestrator 核實後，可平行實作不同 Issue；不符合者維持 BLOCKED。Release Gate 僅在整合 Release Candidate 執行。每張仍是單 Issue／單 Feature Branch／單 PR，沒有跨 Issue Integration Branch 或程式碼合併。禁止直接套用原版 implement-spec 的 auto-merger、Draft PR、自動關票或清理行為。
3. **適用方法：** 已核定 Issue 與測試契約優先；測試可先由既有 Acceptance Criteria 決定範圍，若真正缺少業務規則或測試邊界才提請確認。使用 TDD 不能取代必要整合／併發／安全測試；除錯須有實際重現訊號。每張 Issue 執行影響範圍 Issue Gate；完整 Release Gate 沿用下方原規定並在整合後候選版本執行。
4. **衝突處理：** 第三方 Skill 的 setup、triage、auto-PR、auto-merge、全域重構、額外 Ticket 或更改 AGENTS／GitHub／CI 等動作，若無當次授權與 Issue Scope，一律不得執行；先指出衝突再按本專案的 ADR／規格程序處理。
5. **安裝與交接分離：** 本文件不是 Windows Codex CLI 的安裝證據；不得自行執行第三方安裝／更新或宣稱新 GPT 對話已載入本機 Skills。新對話先核實本協作文件是否已合併 main、實際安裝狀態，以及正確的 Issue／Branch／SHA，再開始工作。
6. **不可降低既有 Gate：** Matt Skill 的內部 Review、Mock、文件檢查不可宣稱等於 GPT Final PASS、GitHub CI、真實 Gemini／Browser／AWS PASS。未執行或受阻明確標示 `NOT RUN`／`BLOCKED`，安全及交易要求不變。

## 目前開發階段：SupplyPilot V2（強制）

> **V2 規格已透過 [PR #150](https://github.com/liujiawei-tw/supplypilot/pull/150) 合併 main，目前進入 V2 功能實作階段；現有 main 業務程式仍主要是 V1 基線。** V2 Markdown、Issue 或「已決策」不等於已寫程式、驗收 PASS 或 AWS 上線。

### 開始任何 V2 Issue 前的必讀與查核

1. 先閱讀本檔、[V2 需求總綱](docs/v2/README.md)、[V1→V2 差異與 Issue 對照](docs/v2/migration-and-issue-map.md)，再閱讀**分派給此實作者的 Issue**與其 V2 專題文件；須讀取該 Issue Worktree 基底上的實際程式與 API/DB/測試契約，不得只看設計 Markdown 直接猜測實作狀態。
2. **規格優先順序：** 使用者針對目前工作明確指示及已核定的 Scope → 當前 Issue 的驗收條件與相依 → `docs/v2/` 中適用的已確認 V2 需求 → 未被 V2 覆蓋的 V1 安全／交易／錯誤／測試契約。若 Issue、規格或程式間有實質衝突，先指出檔案/條文並依 ADR/規格修正程序解決，**不得默默猜測或自行擴 Scope**。
3. 依 Orchestrator 的 Code Gate 與 Issue Gate 確認依賴及單 Issue PR 前驗收。正式交付前必要前置 Issue/PR 必須已 Merge/Closed；provisional contract 只在使用者明確授權時允許先寫程式，不能算 Issue Gate 或 Release Gate PASS。完整 Release Gate 僅對整合後 Release Candidate 執行。每個 Feature Branch 只修改其 Issue 範圍，不把其他 Issue 變更合進本分支。
4. **V2 的已確認刪減需求必須遵守：** Demand 與自動 Recommendation/Ranking 不屬於 V2 新主流程；不得重新加入已取消的文件級 ACL、PDF 頁碼、合約比較、供應商自動排名、PO 匯出、通知中心等功能。V1 舊文檔的描述不能覆蓋新版的 Scope。
5. **不能降低安全／交易要求：** V2 帳號簡化不代表人員管理可無授權開放；RAG Hybrid Search 不能繞過敏感資料出站限制；Kanban 操作不能略過後端 PO/Inventory 交易；失敗與併發仍須驗證。V1 測試 PASS 不等於 V2 變更已驗收。
6. **進度與驗收：** 應以 GitHub Issues、實際已合併 PR、當次 Build/Test/E2E 證據為準；未執行須明確標示 `NOT RUN`／`BLOCKED`，不得將 Fake Provider、單純文件變更或靜態檢查宣稱為真實 Gemini／Browser／AWS PASS。
7. **交付順序：** V2 Issues 依經核實的依賴與 Ready Frontier 實作；每張 Issue 獨立 Gate、Review 及 Merge → 本機測試與標準 Docker + 真實 Browser/Gemini/RAG E2E → AWS Demo [#133](https://github.com/liujiawei-tw/supplypilot/issues/133) → 最終展示影片。**AWS 公開 Demo 共用帳號角色、上傳/出站審核/人員管理權限，要到部署階段才決策；不得擅自延用 V1 `PROCUREMENT_MANAGER` 方案或公開高權限 `admin`。**
8. 完成任何 V2 Issue 時，同步更新**受影響**的 API、Domain/ERD、Security、Frontend、RAG Runbook、測試或 README 相關說明；保留 V1 歷史測試與驗收紀錄，不得改寫舊結果假裝新版通過。

**本章只補充 V2 專案背景及規格引用；下方繁體中文 PR、單 Issue／Branch、Codex→GPT Review、Final PASS 才建立 PR、由使用者親自 Merge 的原有強制規則全部持續有效。**

### Codex 測試執行節奏

- 每次程式修改後先依變更影響範圍執行精準測試；共用認證／授權、資料庫 Schema／交易或跨模組風險才擴大至相關整合測試。精準測試通過且程式未再變更時，不得為同一程式碼重複執行相同測試。
- 同一工作階段的完整回歸集中在程式修改完成後的交付驗證；完整回歸優先由 GitHub Actions 在已 Push 的 Feature Branch Head SHA 執行。本機完整回歸應有明確交付需要，不得為微小修正、文件修改或已由該 SHA CI 覆蓋的相同程式碼無理由重跑。
- 前端 Vitest 有已知單測 timeout 時，先單獨執行該測試檔並保留測例名稱、耗時及完整錯誤輸出，再比較完整套件紀錄；不得提高 timeout、skip／刪除測試或放寬斷言來掩蓋問題。精準重跑用於定位，交付 SHA 的 CI 才是完整回歸證據。
- 不得因此縮減 CI Gate、測試覆蓋、安全／授權／交易／併發驗證，或忽略任何失敗；程式變更後須重跑受影響驗證，並記錄結果對應的 SHA／工作樹版本。

### 使用者暫停 GitHub Actions（暫行，優先於下方 CI Gate）

- **使用者於 2026-10-22 決定：** SupplyPilot Issues 暫停使用 GitHub Actions，直到使用者親自明確通知恢復。期間統一標示 `PAUSED BY USER / NOT USED AS GATE`；不等待、查詢、重跑或依賴 Actions 結果。
- 不修改 `.github/workflows/ci.yml`、Branch Protection 或 Repository Actions 設定。這項決策只暫停使用與依賴 Actions；Push 仍可能觸發 GitHub 自動 Workflow，不得宣稱 Repository Actions 已停止或停用。
- 本機 Backend、Node.js 24 Linux Frontend（`npm ci`、lint、完整 test、build）、Docker／Compose Gate 繼續依原標準執行；所有未通過或未執行項目照實記錄。不得因暫停 Actions 而減少測試、跳過案例或直接宣稱 Final PASS。
- 此規範適用於後續 SupplyPilot Issues，直到使用者親自解除；暫停期間，前方測試節奏中「完整回歸優先由 GitHub Actions」的安排及下方暫行帳務規則均由本節與本機 Gate 取代。歷史 Actions Run 紀錄保留原狀。

### GitHub CI 免費額度／帳務阻擋時的暫行本機驗收

- **使用者已決定：** GitHub Actions 因免費額度或 Billing 限制無法執行時，Issue #138 及後續 Issues 暫用本機完整 CI 等價驗證作為交付 Gate，直到 GitHub CI 恢復有效執行。每次須保存 GitHub Run 的阻擋證據及本機驗收結果；本機驗收不得描述成 GitHub CI PASS。
- 本機完整驗收必須涵蓋 `.github/workflows/ci.yml` 對應工作：在 `backend` 目錄執行 `.\mvnw.cmd -B --no-transfer-progress verify`；在 `frontend` 目錄依序執行 `npm ci`、`npm run lint`、`npm test`、設定 `VITE_API_BASE_URL=http://localhost:8080/api/v1` 後執行 `npm run build`；Container／Compose 執行 Docker Engine 檢查、`python -m unittest scripts.tests.test_compose_config -v`、Production/Demo/Development Compose `config --quiet` 及 Backend/Frontend Docker Image 實際建置。工具版本應符合 Workflow（Java 21、Node.js 24、Python 3.12）；未能符合時須清楚記錄版本與限制。
- 完整測試不得跳過、刪除、放寬或以降低標準的方式重試。遇到失敗時記錄完整失敗證據，修正原因後重跑受影響驗證。
- GPT 須獨立審查程式碼及本機完整驗收證據，才可給出 `FINAL PASS — LOCAL VERIFIED / GITHUB CI PENDING`。此狀態只表示暫行本機 Gate 通過，不等於 GitHub Actions PASS。
- **不得停用或放寬 GitHub Actions，也不得修改成假成功。** GitHub 免費額度恢復並能有效執行 Workflow 後，恢復以 GitHub CI Gate 作為交付驗收。
- 若 GitHub Branch Protection 要求 GitHub Status Checks，不得繞過或代替；若暫行本機 Gate 通過但 Branch Protection 因缺少 CI Checks 阻擋 Merge，須明確回報並由使用者處理。

## GitHub Pull Request 語言規範（強制）

- **所有 SupplyPilot Pull Request（預設由 GPT 建立），標題與描述一律使用繁體中文（台灣，zh-TW）。**
- PR 內容中的摘要、修改內容、驗收條件、測試結果、風險、注意事項、未完成項目及 Review 回覆，同樣使用繁體中文。
- 不得因為 Codex 或 GPT 開啟新對話、切換工作階段、切換分支或處理不同 Epic／Issue，而將 PR 內容改成英文。
- 程式碼、變數、類別名稱、檔案路徑、API 路由、CLI 指令、錯誤代碼、Git commit SHA，以及無適當譯名的技術名詞可保留原文。
- 除非使用者針對**特定 PR**明確要求其他語言，否則不得覆蓋此預設規則。
- 建立 PR 前，先檢查標題與描述是否符合繁體中文規範。

## 既定交付流程

- 每張 GitHub Issue 獨立遵守 Scope、Feature Branch、Worktree、測試、Commit/Push、GPT Review 與 PR；不同 Issue 只有在 Issue #162 Orchestrator 的依賴、隔離與資源 Gate 通過後才可平行。
- Codex 負責實作、修正及回報驗證證據；GPT 負責獨立 Code Review。
- **只有在 GPT 確認 Final PASS 後，才可建立 PR；預設由 GPT 負責建立與回讀核對 PR，Codex 不自行建立 PR**（除非使用者對特定任務明確另有授權）。
- **不得自行 Merge PR，也不得自行將 Issue 視為完成。** PR Merge 由使用者確認執行。
- 下游 Issue 的 Issue Gate 必須等前置 PR Merge 且 Issue Closed；彼此獨立且已授權的 Issue 可按 Ready Frontier 同時進入 Code Gate。整合 Release Candidate 的 Release Gate 另行執行。

## Codex Commit / Push → GPT Review 交接流程（強制）

本流程適用於日後所有單一 Issue 的程式碼、測試及文件交付；**Commit / Push 不等於 GPT Review PASS、PR 核准或 Merge**。與其他規則同時適用。

1. **先核對環境與任務**：Codex 在動手前讀取 `AGENTS.md`、當前 GitHub Issue、相關規格及現有程式碼，檢查 `origin/main`、工作目錄及分支狀態。從已核對的 `main` 建立對應 Issue 的獨立 Feature Branch；不得直接修改 `main`，不得混入其他 Issue 或覆蓋使用者未提交的工作。
2. **實作並自行驗證**：Codex 在該 Feature Branch 完成 Issue Scope，執行適用的測試、Build、Lint、`git diff --check`，核對授權、敏感資訊、變更檔案與影響範圍。測試未執行、失敗或受環境限制時必須如實回報，不得假稱 PASS；阻擋性交付問題應先修正，不得當成完成。
3. **審查前允許 Commit / Push**：完成必要的實作及驗證後，Codex **可以在 GPT Code Review 前 Commit 並 Push 至遠端 Feature Branch**，並核對本機、遠端的 Head SHA 一致。這不是 Merge；禁止直接 Push 到 `main`、Force Push 已提交審查的分支、建立未授權的 PR 或修改／關閉 Issue。
4. **最小化交接資訊**：Codex 以繁體中文回報 Issue 編號與連結、Repository、Base Branch / SHA、Feature Branch、Head Commit SHA、Commit 摘要、修改檔案／範圍、測試指令及實際結果（含失敗、未執行與風險）。**不要求使用者手動複製程式碼或貼上完整 Diff。**
5. **GPT 遠端獨立 Review**：GPT 直接以 GitHub 連線取得已 Push 的確切 Head Commit、對 `main` 的 Diff、關鍵原始碼、測試與相關契約，核對 Issue Scope、Business Logic、Security、Transaction、Regression 及測試證據。可按 P0／P1／P2 回報附檔案／原因／修正建議的 Finding；給出 `CHANGES REQUIRED` 或 `FINAL PASS`。**靜態檢查、Codex 回報的本地測試，以及 GPT 實際執行的驗證須明確區分**，不得把測試聲稱當作 GPT 親自重跑。
6. **未通過則修正再審**：若 GPT 回報 P0／P1 或其他阻擋性問題，Codex 僅修正當前 Issue、重跑相關驗證，在**同一 Feature Branch 建立新的 Commit 並 Push**；不得用 Force Push 改寫已審查的 Commit。回報新的 Head SHA，由 GPT 以新 SHA 重新審查。尚未取得 Final PASS 不得建立 PR。
7. **Final PASS 後由 GPT 建立 PR**：GPT 明確對某個 Head SHA 給出 `FINAL PASS` 後，**GPT 預設直接從 GitHub 建立 PR**（使用者對特定任務明確授權其他分工時除外）；Codex 僅負責實作、測試、Commit、Push 及 Review Findings 修正，不預設建立 PR。PR Base 預設 `main`、Head 為已審查分支，標題／描述使用繁體中文，描述須有獨立一行 `Closes #<Issue編號>`。GPT 建立後回讀驗證 PR Base、Head、Head SHA、描述與範圍正確；Head 若發生新變更須重新審查。**PR 建立不等於 Merge，Merge 決策僅由使用者執行。**
8. **Merge 與下一個 Issue**：**只由使用者人工確認 Merge**；GPT 不得替使用者擅自 Merge。使用者告知完成後，GPT 須透過 GitHub 核實 PR `merged`、Merge Commit／`main` Head、Issue `closed`。確認完成後，才開始下一張 Issue（使用者明確授權例外時除外）。

## GitHub PR 與 Issue 自動關閉規範（強制）

- **針對已完成的單一 Issue 建立 PR 時，PR 描述必須包含獨立一行 `Closes #<Issue編號>`。** 不得只寫「關聯 Issue：#編號」、`Refs #編號` 或一般文字連結，因為這些不會在 Merge 後自動關閉 Issue。
- 建立 PR 之前，必須核對 GitHub 的實際 Issue 編號、PR 的 Base Branch（預設 `main`）與 Head Branch，避免關錯 Issue。
- PR 建立後再次讀取 PR 描述，確認 `Closes #<Issue編號>` 確實存在且指向正確 Issue。
- **PR 只能在 GPT 確認 Final PASS 後建立，Merge 仍由使用者手動執行。** 不得為了自動關閉 Issue 而自動 Merge。
- 使用者告知 Merge 完成後，須查核 GitHub PR 的 `merged` 狀態、Merge Commit 與對應 Issue 的 `closed` 狀態；若 Issue 未關閉，明確回報並安排修正，不能假稱已自動關閉。
- 此規則適用於新對話、新工作階段及往後所有 Epic / Issue；新的 SupplyPilot 工作開始前，先讀取 Repository 根目錄 `AGENTS.md`。若目前環境無法自動取得此檔案，需明確讀取或要求提供，不能假設已載入。
