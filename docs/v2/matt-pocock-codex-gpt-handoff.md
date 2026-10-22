# SupplyPilot V2 — Matt Pocock Skills × Codex × GPT 協作與新對話交接

> 文件類型：V2 開發工程協作指引（非產品需求／非已驗收證據）。
> 適用範圍：SupplyPilot V2 的各張 GitHub Issue；主要作業環境為 Windows Codex CLI。
> 審查基準：2026-10-22，來源 [Matt Pocock Skills](https://github.com/mattpocock/skills)；參考 upstream main commit `5beb35b040e3de97e54fa9e386d7e42d15c295c2`。上游可能更新，使用前應核對實際安裝版本與 Skill 內容。
> 專屬追蹤：[Issue #156](https://github.com/liujiawei-tw/supplypilot/issues/156)。文件合併只建立協作規則，**不代表第三方 Skills 已安裝、功能已完成或測試已通過**。

## 1. 權威、目標與不可改動的規則

- **最高專案執行規則：** 根目錄 [AGENTS.md](../../AGENTS.md)；業務與 Scope 依 [V2 規格總綱](README.md)、[V1→V2 差異／Issue 對照](migration-and-issue-map.md)、當次 Issue 及對應文件。Matt Pocock 原版 Skill、README、範例命令均不是 SupplyPilot 規格權威。
- 使用者當次明確指令及經核定的 Scope → 當次 Issue 與依賴 → V2 已核定文件 → 未被 V2 覆蓋的安全／交易／測試契約；Skill 只能在不牴觸上述要求時提供方法。遇到衝突，記錄具體來源、停止衝突行為並依既有決策程序處理；**不得以 Skill 為由自行修改專案規則**。
- **每張 Issue 維持單 Feature Branch／單 Worktree／單 PR。** 已授權的多張 Issue 可在 Issue DAG、Code/Release Gate、檔案 owner、測試資源及獨立工作樹均經 Orchestrator 驗證後平行實作；下游 Release 仍需等待前置 Issue PR Merge 且 Issue Closed。不得建立跨 Issue Integration Branch、讓子 Agent 合併其他 Issue、提早建立 Draft PR、關閉 Issue、Force Push 或直接 Push main。
- **Codex 實作＋測試＋內部預檢＋Commit／Push；GPT 以 GitHub 遠端確切 Head SHA 獨立審查；GPT Final PASS 才建立 PR；使用者親自決定 Merge。** Codex 或 Matt 的 `code-review` 結果不能取代 GPT Final PASS。
- 不修改既有 CI Gate、GitHub Actions、測試必要覆蓋、安全／權限／交易／併發、Gemini 真實 Provider／Browser E2E 的證據要求。不得將單純靜態 Review、Mock、文件修改宣稱為真實測試 PASS。
- 不授權自動安裝／升級第三方 Skill、執行第三方腳本、改寫 `AGENTS.md`、`docs/v2/`、GitHub Issue 或 CI；此類變更必須有明確 Scope。V2 仍依既定次序：逐張完成 → 本機 Docker／真實 E2E → AWS #133 → 展示影片。

## 2. 固定角色與權限

| 角色 | 職責 | 不得代行 |
| --- | --- | --- |
| 使用者（Product Owner） | 需求、優先順序、重大衝突的決策；Merge／正式公開權限授權 | 不以安裝 Skill 代替驗收 |
| Codex（Implementer） | 讀取 Issue／規格／實際 Repo；使用 Matt Skills 輔助實作、精準測試、內部預檢、整理證據、Commit／Push | 不自行建立 PR、Merge、關閉 Issue、改變 Scope，或宣布 GPT Final PASS |
| Matt Pocock Skills（方法庫） | 提供 TDD、錯誤重現、程式設計與內部 Review 方法 | 不是 Agent、需求權威、審核者或自動 Merge 工具 |
| GPT（Independent Reviewer） | 依來源與 Head SHA 審查 Spec、Standards、安全、交易、測試與風險，回報 P0／P1／P2，核定 Final PASS／CHANGES REQUIRED；Final PASS 後建立及回讀 PR | 不把 Codex 宣稱的測試當成自身執行；不得自行 Merge |

### GPT 的雙軸 Review（外加專案關卡）

1. **Spec：** 是否滿足 Issue 的 Acceptance Criteria、正確遵守 V2 業務規則及排除項目、沒有額外功能與越界修改。
2. **Standards：** 是否符合 `docs/coding-standards.md`、相關 API／Domain／Security／Transaction／Exception 文件，檢查程式品質與可維護性。
3. **必備 Gate：** 權限／機敏出站、資料庫遷移、交易原子性及併發、回歸測試、真實測試證據、交付 SHA／Base SHA、變更範圍及部署禁令。不得因前兩軸沒發現問題就省略。

## 3. Skills 導入矩陣（使用目的及限制）

| Skill | 使用者／時機 | 具體規則 |
| --- | --- | --- |
| [implement](https://github.com/mattpocock/skills/blob/main/skills/engineering/implement/SKILL.md) | Codex：依已核定單一 Issue 實作 | 先讀 Repo 實況與 Issue；內部 `code-review` 僅為預檢；Commit 後仍 Push 給 GPT 審查，不得自動建 PR |
| [tdd](https://github.com/mattpocock/skills/blob/main/skills/engineering/tdd/SKILL.md) | Codex：狀態轉換、交易、授權、檢索及回歸邏輯 | 使用 Issue／既定契約確認測試 seam；Red → Green、一次一個可驗證垂直切片；缺少真正必要的規格才詢問，不新增 Scope |
| [diagnosing-bugs](https://github.com/mattpocock/skills/blob/main/skills/engineering/diagnosing-bugs/SKILL.md) | Codex：可重現失敗、CI、RAG、併發或 E2E 異常 | 先建立失敗訊號並重現，再診斷、修正、回歸；保密與去識別；不得藉除錯降低斷言或跳過測試 |
| [code-review](https://github.com/mattpocock/skills/blob/main/skills/engineering/code-review/SKILL.md) | Codex：Commit／Push 前的內部預檢 | 依 Spec／Standards 雙軸檢查；報告 Findings；**不是** GPT 遠端獨立 Review 或 Final PASS |
| [codebase-design](https://github.com/mattpocock/skills/blob/main/skills/engineering/codebase-design/SKILL.md) | Codex：相關 Issue 需要改善模組邊界時 | 只處理本 Issue 直接必要的設計，不順便全域重構 |
| [retro](https://github.com/mattpocock/skills/blob/main/skills/engineering/retro/SKILL.md) | 使用者要求時：比較實作／測試／Review 瓶頸 | 只提供可追溯改善建議；需修改 AGENTS、CI、其他 Issue 時另行核准 |
| [implement-spec](https://github.com/mattpocock/skills/blob/main/skills/engineering/implement-spec/SKILL.md) | SupplyPilot 專屬版僅在使用者明確授權多 Issue 時使用 | 本機 .agents/skills/supplypilot-implement-spec 是 Issue #162 的受控協調器；僅借用 DAG/Ready Frontier/TDD/內部 Review 方法，明確替換原版 Integration Branch、Merger、Draft PR 和自動清理行為。原版不直接執行 |
| setup／triage／to-spec／to-tickets／pr | 目前不納入預設開發路徑 | V2 已有 Issue 與核定規格；setup 可能產生文件及修改 AGENTS，不能未經授權執行；PR 仍由 GPT 依 AGENTS 建立 |

同類 TDD／Debug Skill（例如 Superpowers）不需要同時重複導入。任何第三方 Skill 若要求和本表衝突的操作，以此文件及 `AGENTS.md` 限制為準。

## 4. 每張 V2 Issue 的執行與交接契約

1. **前置：** 讀取 AGENTS.md、Issue、docs/v2/ 對應規格、實際程式碼與測試；從已授權 Issue 集合核實依賴 DAG 和目前 main SHA。各 Issue 維持單一 Feature Branch/Worktree；Code Gate 可依明確核准 provisional contract 放行編碼，Release Gate 必須確認依賴 Merge/Closed、當前 main、契約與完整證據。
2. **Codex 實作：** 每張 Issue 交給指定實作者，分派訊息附上 Issue、Worktree/Branch、Base SHA、契約、檔案 owner、測試資源和停止條件；使用 implement/tdd。Codex subagents 不保證自動各有 Worktree，因此主控需先實際建立並核對隔離工作樹，否則只派唯讀工作。
3. **測試：** 修改後跑受影響精準測試、必要整合與負向案例；程式碼穩定後依 `AGENTS.md` 執行交付 Gate。不得重複跑相同且未修改程式碼的測試來虛增證據。GitHub Actions 若帳務阻擋，依既有本機完整 CI 等價驗證；如實標記 `FINAL PASS — LOCAL VERIFIED / GITHUB CI PENDING` 的適用條件。
4. **Codex 內部 Review：** 先執行 Matt `code-review` 預檢並修正，再 Commit／Push 至 Feature Branch，核對遠端 Head SHA 和本機一致。
5. **交接 GPT：** 回報 Issue 連結、Repository、Base Branch/SHA、Feature Branch、Head SHA、修改檔案與摘要、實際測試命令／結果／證據、未執行項目、已知風險、Matt Skills 的使用紀錄。**不要求使用者貼整份 Diff**。
6. **GPT 獨立審查：** 從 GitHub 重新取得正確 Head 與 Base、Diff、程式與驗收條件；分開審查 Spec／Standards／必要 Gate；回報帶檔案依據的 Findings。若需修改，Codex 在同分支新增 Commit／Push，再以新的 SHA 重新審查。
7. **PR 與 Merge：** GPT 確認 `FINAL PASS` 後，依 `AGENTS.md` 以繁體中文建立 PR，描述獨立一行 `Closes #<Issue編號>`，回讀核對 Head SHA／Base／Scope。由使用者人工 Merge；核對 Merge／Closed 才進下一張。
8. **任何環節無法證實：** 明確記錄 `NOT RUN` 或 `BLOCKED`；不得猜測成功，尤其不把 Mock、靜態文件檢查等同 Browser、Gemini、CI 或 AWS PASS。

## 5. SupplyPilot 多 Issue Orchestrator（Issue #162 提案）

此節與專案 Skill 在 Issue #162 PR 合併前均為待審查提案；main 上現行單 Issue 規則繼續適用。

使用者列出可處理的 Issue 集合後，主控根據 GitHub Issue/PR、目前 main、V2 需求、repo 程式契約建依賴 DAG，驗證 Missing/Cycle、Ready Frontier、Code Gate 與 Release Gate。Codex subagent 可平行做唯讀探索，也可平行實作不同 Issue，但前提是主控先建立並核實獨立 Worktree/Branch、文件 owner 和測試資源限制；官方文件未宣稱 subagent 會自帶 Worktree。遇到既有 WIP、衝突或能力不支援就停止該項派工。

第一版上限為 implementer 3；Full Vitest、Maven Verify、Docker/Testcontainers 各自同時 1 組。持久化狀態由單一 Orchestrator writer 維護於 Git common directory 下的 `supplypilot-orchestrator/orchestrator.json`；共享資源鎖也放在此 common directory，讓所有 linked worktrees 都能互斥。implementer 回報事實，不直接改共享狀態。完整規則、狀態資料與命令見 .agents/skills/supplypilot-implement-spec/；Internal Review 只屬預檢。

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
4. 若使用者希望先「對接」而非直接寫程式：先產出一份簡短的相容性檢查，核對 Skills 實際可用、AGENTS 優先序、正確 GitHub Repo、測試 Gate 與交接格式；**不要擅自開始實作下一張 Issue**。
5. 對接後要開始功能時，再讀取**當時最新** Issue、前置 PR、Repo 實況及 Head SHA；此前文件不得當成程式已完成的證明。

可直接在新對話使用的啟動訊息：

> 請先連線讀取 SupplyPilot GitHub `AGENTS.md`、`docs/v2/matt-pocock-codex-gpt-handoff.md`、V2 規格與 Matt Pocock Skills 原始檔。先確認文件是否已合併 main、Windows Codex CLI Skills 是否真的安裝，對接 Codex 實作／GPT 獨立 Review 的角色與交付格式；只做相容性檢查，不要開始下一張 Issue 或修改 Repository。用繁體中文回報差異和阻擋點。

## 9. 導入成效驗證（非強制產品功能）

選一張相依已滿足、尚未開始的 Issue 作為第一個試行案例，對比先前同類 Issue 的：實作時間、測試修正次數、GPT P0/P1 Findings／Review 往返次數、使用 Token／實際費用（如可得）。結果受 Issue 難度影響，不宣稱固定效率增幅。除非使用者另行授權，本文件本身不改動 Issue 優先順序、測試標準或 PR 規則。
