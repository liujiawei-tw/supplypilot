# SupplyPilot V2 — Matt Pocock Skills × Codex × GPT 協作與新對話交接

> 文件類型：V2 開發工程協作指引（非產品需求／非已驗收證據）。
> 適用範圍：SupplyPilot V2 的各張 GitHub Issue；主要作業環境為 Windows Codex CLI。
> 審查基準：2026-10-22，來源 [Matt Pocock Skills](https://github.com/mattpocock/skills)；參考 upstream main commit `5beb35b040e3de97e54fa9e386d7e42d15c295c2`。上游可能更新，使用前應核對實際安裝版本與 Skill 內容。
> 專屬追蹤：[Issue #156](https://github.com/liujiawei-tw/supplypilot/issues/156)。文件合併只建立協作規則，**不代表第三方 Skills 已安裝、功能已完成或測試已通過**。


## 1. 規則與最新流程

- **2026-10-24 最新使用者決策優先：** [根目錄 AGENTS.md](../../AGENTS.md) 的 GPT → Codex → GPT Code Review → Codex 必要 CI → GPT PR → 人工 Merge 是一般 Issue 的強制協作流程。
- GitHub Issue 與 [V2 規格總綱](README.md) 確認 Scope，修改前檢查最新 Repo／必要依賴、風險和現有測試；第三方 Skills 不改動授權邊界。
- 每張 Issue 原則上一個 Feature Branch／Worktree／PR；多 Issue 並行時用 GitHub 依賴及 Worktree／檔案衝突實際核對，不靠 Orchestrator 的多層 State／Gate 作一般交付的強制前置。
- 不把未合併上游當作已完成、不改 main、不 Force Push、不代替使用者 Merge，不隱藏失敗測試或跳過必要安全／授權／交易／併發回歸。
- GitHub Actions 目前 `PAUSED BY USER / NOT USED AS GATE`。一般 V2 Issue 採 **精準 CI／必要風險整合測試**；不要求每張 Issue 重跑全套 Maven／Vitest，完整 CI 在所有規劃 V2 Issues 合併後統一執行。Codex 在 GPT Code Review 後驗證必要範圍、保留原始證據，未執行 Gemini／Browser／AWS 不得宣稱 PASS。
- #168 最多兩輪 Orchestrator 修正；第二輪仍無法穩定運作，停止擴大修復，提請最小化移除其剩餘強制入口，**不刪除必要測試**。


## 2. 角色與驗收責任

| 角色 | 工作 | 不得代行 |
| --- | --- | --- |
| 使用者 | 決定 Scope、優先順序與 PR Merge | 不因工具顯示 PASS 而省略產品驗證 |
| GPT | GitHub 讀取／Prompt、獨立 Code Review、CI 證據核對、建立繁體中文 PR | 不冒稱自己跑過 Codex 的測試、不擅自 Merge |
| Codex | Branch／Worktree、實作、開發期精準測試、內部預檢、Commit／Push、Review 後必要 CI | 不自動開 PR、Merge、關閉 Issue 或偽造證據 |
| Matt Skills | 可選的 implement／tdd／diagnosing-bugs／內部 code-review 方法 | 不取代 GitHub 事實、GPT 審查或專案規格 |

GPT Review 檢查兩軸：**Spec**（驗收條件、範圍、V2 Business Rules）、**Standards**（程式碼品質、安全、API／資料庫／交易／例外及測試設計）。Code Review 可以先 PASS，但**正式交付 PASS 仍需必要 CI 實際通過**。

## 3. Skills 導入矩陣（使用目的及限制）

| Skill | 使用者／時機 | 具體規則 |
| --- | --- | --- |
| [implement](https://github.com/mattpocock/skills/blob/main/skills/engineering/implement/SKILL.md) | Codex：依已核定單一 Issue 實作 | 先讀 Repo 實況與 Issue；內部 `code-review` 僅為預檢；Commit 後仍 Push 給 GPT 審查，不得自動建 PR |
| [tdd](https://github.com/mattpocock/skills/blob/main/skills/engineering/tdd/SKILL.md) | Codex：狀態轉換、交易、授權、檢索及回歸邏輯 | 使用 Issue／既定契約確認測試 seam；Red → Green、一次一個可驗證垂直切片；缺少真正必要的規格才詢問，不新增 Scope |
| [diagnosing-bugs](https://github.com/mattpocock/skills/blob/main/skills/engineering/diagnosing-bugs/SKILL.md) | Codex：可重現失敗、CI、RAG、併發或 E2E 異常 | 先建立失敗訊號並重現，再診斷、修正、回歸；保密與去識別；不得藉除錯降低斷言或跳過測試 |
| [code-review](https://github.com/mattpocock/skills/blob/main/skills/engineering/code-review/SKILL.md) | Codex：Commit／Push 前的內部預檢 | 依 Spec／Standards 雙軸檢查；報告 Findings；**不是** GPT 遠端獨立 Review 或 Final PASS |
| [codebase-design](https://github.com/mattpocock/skills/blob/main/skills/engineering/codebase-design/SKILL.md) | Codex：相關 Issue 需要改善模組邊界時 | 只處理本 Issue 直接必要的設計，不順便全域重構 |
| [retro](https://github.com/mattpocock/skills/blob/main/skills/engineering/retro/SKILL.md) | 使用者要求時：比較實作／測試／Review 瓶頸 | 只提供可追溯改善建議；需修改 AGENTS、CI、其他 Issue 時另行核准 |
| [implement-spec](https://github.com/mattpocock/skills/blob/main/skills/engineering/implement-spec/SKILL.md) | 僅作選用的多 Issue 分析工具 | 原 #162 Orchestrator 的 State／Issue Gate／Release Gate 不再是一般 Code Review／CI／PR 前置；若明確使用舊工具，原本 fail-closed 不可偽造成功；不得自動建立 Integration Branch 或 Merge |
| setup／triage／to-spec／to-tickets／pr | 目前不納入預設開發路徑 | V2 已有 Issue 與核定規格；setup 可能產生文件及修改 AGENTS，不能未經授權執行；PR 仍由 GPT 依 AGENTS 建立 |

同類 TDD／Debug Skill（例如 Superpowers）不需要同時重複導入。任何第三方 Skill 若要求和本表衝突的操作，以此文件及 `AGENTS.md` 限制為準。


## 4. 一般 Issue 執行順序（強制）

1. **GPT 讀 GitHub：** 核實最新 main、Issue／Acceptance Criteria、已合併的前置 PR 與 Code／Docs，給 Codex 清楚的 Prompt。
2. **Codex 實作：** 專用 Branch／Worktree，執行開發期受影響測試及內部 Review，Commit／Push；交接 Issue URL、Base、完整 HEAD SHA、Diff 範圍、風險與已跑／未跑測試。
3. **GPT Independent Review：** 對 GitHub 遠端 HEAD 的完整 Diff 檢查 Spec／Standards／Security／Transaction／Tests；阻擋 Finding 交 Codex 在相同分支修正並重新 Review。
4. **Codex 必要精準 CI：** Review PASS 後，對相同 HEAD 執行直接／間接受影響 Backend Maven、Frontend Node.js 24／Lint／Vitest／Build，以及必要 Integration／Migration／授權／併發與 Issue 指定 Browser 驗收；高風險必跑項目不得延後。保留命令、測試計數、Exit Code、Logs／Hash、Skipped 與未執行理由；修改後重新 Commit／Push 則 GPT Review 新 SHA。全量 Maven／Vitest 留待全部規劃 V2 Issues 整合完成後執行。
5. **GPT 交付 PASS 與 PR：** 僅在必要 CI 有效且無阻擋問題時建立繁體中文 PR，目標 `main`，描述獨立一行 `Closes #<IssueNumber>`，回讀確認 Base／Head／SHA。
6. **使用者 Merge：** 只由使用者決定 Merge；再核實 PR merged、最新 main 與 Issue closed。

舊 Orchestrator `ISSUE_GATE_VERIFIED`、State Reconciliation／Contract Verification、Reservation、Release Gate 不作此一般路徑的額外必要條件。它們若被單獨啟動仍須忠實回報結果；不可冒稱工具已從程式刪除。部署／真實 Gemini／Browser／Docker／AWS 驗證另依實際交付範圍安排。

**當張 Issue 必要精準 CI 失敗仍阻擋 PR。** 先前全套測試的失敗必須保留並完成影響分診，只有證據能證明與此次直接／間接變更無關，且必要精準 CI 和驗收通過，才可記作 `DEFERRED TO V2 INTEGRATION` 而不阻擋 PR；不得把完整 FAIL 冒稱 PASS，亦不得把無根據的失敗籠統列為 Flaky。真正 opt-in、未排入本次必要範圍的 Provider Smoke 可列 `NOT RUN／RELEASE_PENDING`。純文件改動可用 Markdown／連結一致性與 Diff Check 驗證，不強制無關全量測試。


## 5. 原 Orchestrator：歷史工具與 #168 兩輪限制

#162 提供的 Orchestrator 仍在 Repo 中，其 State Machine、Impact Planner、Reservation 與獨立 Gate 原本採 Fail-Closed 設計；這些工具的程式行為**不會因文件變更而自動修改**。

根據 2026-10-24 使用者決策，一般開發直接用 GitHub Branch／PR 與 GPT Review＋必要 CI 驗收，不再要求多層 State／Gate。只有使用者針對特定操作明確要求時，才可選用舊 Orchestrator 診斷功能；執行該工具時不得偽造 Gate PASS、直接編輯 Shared State 或擅自清除 Lock。

#168 的 Orchestrator Recovery 最多兩輪修正，每輪需有實際修正與驗證結果；兩輪後仍失敗即停止擴張，轉為最小化移除強制入口的改動（另經確認與 Review）。#142、#145 既有業務成果保留，僅補足必要 CI，沒有額外 State Gate 要求。

## 6. Matt Skills 所需的最小 Repo 設定

為避免原版 `setup-matt-pocock-skills` 改寫既有 `AGENTS.md`、建立額外 Labels 或重新設計 V2 流程，本 Issue 已**直接提供唯讀優先的最小相容設定文件**：

- [Issue Tracker](../agents/issue-tracker.md)：明確設定 `liujiawei-tw/supplypilot`、GitHub CLI 唯讀查詢與所有寫入禁止範圍；供 `implement`／`code-review` 找回 Issue／Spec。
- [Domain 文件導覽](../agents/domain.md)：讓 Skills 優先讀 V2 規格、既有 Domain／API／Testing 契約；不新建重複的 Glossary 或 ADR 體系。
- **尚未實測：** 這是按上游 Skill 預期預先建立的相容設定，並未在使用者 Windows Codex CLI 驗證所有指令及 Skill 自動讀取是否成功。若實際使用的上游版本仍要求執行 setup，先進行唯讀差異分析，不得直接套用 setup 的寫入步驟。

## 7. Windows Codex CLI：Skills 與 Subagent/Worktree 能力

截至 2026-10-22，工作環境實測 CLI 為 codex-cli 0.163.0-alpha.11。Codex 專案級 Skill 由 repo root .agents/skills/ 發現；官方文件指出從 CWD 到 repo root 逐層掃描，能以 $skill-name 明確呼叫，自動發現變更，未顯示時重啟。官方說目前 Codex releases 啟用 subagents；子 Agent 繼承 parent sandbox/approval。官方 Worktree 文件描述的是 Codex Desktop managed Git worktree 並預設 detached HEAD。不可推論每個 subagent 自動有獨立 worktree；主控必須用 Git UI/命令建立並核實工作樹，再指派寫入。不同 CLI 版本需各自核實，不能用 alpha 測試推定其他安裝。

此工作分支實際 Skill 位置：.agents/skills/supplypilot-implement-spec/SKILL.md。官方文件：
- [Build skills](https://developers.openai.com/codex/skills)
- [Subagents](https://developers.openai.com/codex/subagents)
- [Worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)
- [Windows sandbox](https://learn.chatgpt.com/docs/windows/windows-sandbox)

上游參考 mattpocock/skills 最新 implement-spec 以多 Issue Ready Frontier 派工至 worktree，但會合到 integration branch、由 merger subagent 整合並可能建立 Draft PR；SupplyPilot Skill 保留 Spec-driven、DAG、TDD、bug diagnosis、internal review 核心，禁止上游整合/PR/cleanup 行為。上游 repository 採 MIT License，Skill 提供方法引用，不複製整份原文。

以下安裝指令僅供使用者自行選擇：

以下指令**僅供使用者在自己的 Windows Codex CLI 執行**；本文件不代表已執行，亦不要求任何 Agent 自動安裝：

```powershell
# 於 SupplyPilot 專案資料夾執行，互動式選擇需要的 Skills 及專案級範圍
npx skills@latest add mattpocock/skills -a codex
```

- 優先選 `implement`、`tdd`、`diagnosing-bugs`、`code-review`；`codebase-design`、`retro` 按需。不要同時使用 CLI plugin 與 skills.sh 重複安裝相同 Skills。
- 執行第三方安裝前檢查來源、目標安裝路徑、腳本和安全性。若安裝程序或 `setup-matt-pocock-skills` 打算修改根目錄 AGENTS／Docs／GitHub Labels，先在獨立分支檢視，沒有授權就不要套用。
- 重新開啟 Codex CLI，核對實際可用的 Skill 名稱、版本／來源和讀取範圍；至少以單張 Issue 的「唯讀讀取 + 測試規劃」進行 Smoke 驗證。**確認安裝不代表已通過 V2 實作驗收**。
- ChatGPT 網頁對話**不會自動繼承你在 Windows 安裝的 Codex CLI Skills**。GPT 可讀取這份 GitHub 協作契約與上游 Skill 原始碼以進行對接／Review，但不得宣稱能直接調用你本機未連線的 CLI Skill。

## 8. 新的 GPT 對話：首次對接步驟

新的 GPT 對話開始時請先：
1. 讀取 `liujiawei-tw/supplypilot` 的最新 `AGENTS.md`、`docs/v2/README.md`、本文件、`docs/agents/issue-tracker.md`、`docs/agents/domain.md`，以及 `docs/v2/migration-and-issue-map.md`；核實這次導入文件的 PR 是否**已合併至 main**。若仍只在 Feature Branch，應明確標為待合併草案，不能當 main 的既定規則。
2. 讀取 Matt Pocock Skills 對應的上游 README、`implement`、`tdd`、`diagnosing-bugs`、`code-review` 原始 Skill；詢問／驗證實際 Windows Codex CLI 是否已安裝（不可從文件推定）。
3. 確認 GPT 只負責獨立審查與 PR、Codex 負責實作與證據、使用者負責 Merge；確認單 Issue／單 Branch 及 Skills 的衝突限制。
4. 若使用者希望先「對接」而非直接寫程式：先產出一份簡短的相容性檢查，核對 Skills 實際可用、AGENTS 優先序、正確 GitHub Repo、必要 CI 與交接格式；**不要擅自開始實作下一張 Issue**。
5. 對接後要開始功能時，再讀取**當時最新** Issue、前置 PR、Repo 實況及 Head SHA；此前文件不得當成程式已完成的證明。

可直接在新對話使用的啟動訊息：

> 請先連線讀取 SupplyPilot GitHub `AGENTS.md`、`docs/v2/matt-pocock-codex-gpt-handoff.md`、V2 規格與 Matt Pocock Skills 原始檔。先確認文件是否已合併 main、Windows Codex CLI Skills 是否真的安裝，對接 Codex 實作／GPT 獨立 Review 的角色與交付格式；只做相容性檢查，不要開始下一張 Issue 或修改 Repository。用繁體中文回報差異和阻擋點。

## 9. 導入成效驗證（非強制產品功能）

選一張相依已滿足、尚未開始的 Issue 作為第一個試行案例，對比先前同類 Issue 的：實作時間、測試修正次數、GPT P0/P1 Findings／Review 往返次數、使用 Token／實際費用（如可得）。結果受 Issue 難度影響，不宣稱固定效率增幅。除非使用者另行授權，本文件本身不改動 Issue 優先順序、測試標準或 PR 規則。
