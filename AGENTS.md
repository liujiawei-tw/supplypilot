# SupplyPilot — Codex 專案共用指示

本檔案適用於此儲存庫內所有 Codex 工作階段及子目錄。開始工作時請先遵守本檔案，並同時遵守目前 Issue、專案規格與使用者的明確指示。

## GitHub Pull Request 語言規範（強制）

- **所有由 Codex 為 SupplyPilot 撰寫或建立的 Pull Request，標題與描述一律使用繁體中文（台灣，zh-TW）。**
- PR 內容中的摘要、修改內容、驗收條件、測試結果、風險、注意事項、未完成項目及 Review 回覆，同樣使用繁體中文。
- 不得因為 Codex 開啟新對話、切換工作階段、切換分支或處理不同 Epic／Issue，而將 PR 內容改成英文。
- 程式碼、變數、類別名稱、檔案路徑、API 路由、CLI 指令、錯誤代碼、Git commit SHA，以及無適當譯名的技術名詞可保留原文。
- 除非使用者針對**特定 PR**明確要求其他語言，否則不得覆蓋此預設規則。
- 建立 PR 前，先檢查標題與描述是否符合繁體中文規範。

## 既定交付流程

- 一次僅實作一張 GitHub Issue，嚴格遵守 Issue Scope 與相關規格。
- Codex 負責實作、修正及回報驗證證據；GPT 負責獨立 Code Review。
- 只有在 GPT 確認 Final PASS 後，才可進入 PR 建立階段；除非使用者明確另有要求，Codex 不自行建立 PR。
- **不得自行 Merge PR，也不得自行將 Issue 視為完成。** PR Merge 由使用者確認執行。
- PR 合併並確認相關 Issue Closed 後，才開始下一張 Issue。

## Codex Commit / Push → GPT Review 交接流程（強制）

本流程適用於日後所有單一 Issue 的程式碼、測試及文件交付；**Commit / Push 不等於 GPT Review PASS、PR 核准或 Merge**。與其他規則同時適用。

1. **先核對環境與任務**：Codex 在動手前讀取 `AGENTS.md`、當前 GitHub Issue、相關規格及現有程式碼，檢查 `origin/main`、工作目錄及分支狀態。從已核對的 `main` 建立對應 Issue 的獨立 Feature Branch；不得直接修改 `main`，不得混入其他 Issue 或覆蓋使用者未提交的工作。
2. **實作並自行驗證**：Codex 在該 Feature Branch 完成 Issue Scope，執行適用的測試、Build、Lint、`git diff --check`，核對授權、敏感資訊、變更檔案與影響範圍。測試未執行、失敗或受環境限制時必須如實回報，不得假稱 PASS；阻擋性交付問題應先修正，不得當成完成。
3. **審查前允許 Commit / Push**：完成必要的實作及驗證後，Codex **可以在 GPT Code Review 前 Commit 並 Push 至遠端 Feature Branch**，並核對本機、遠端的 Head SHA 一致。這不是 Merge；禁止直接 Push 到 `main`、Force Push 已提交審查的分支、建立未授權的 PR 或修改／關閉 Issue。
4. **最小化交接資訊**：Codex 以繁體中文回報 Issue 編號與連結、Repository、Base Branch / SHA、Feature Branch、Head Commit SHA、Commit 摘要、修改檔案／範圍、測試指令及實際結果（含失敗、未執行與風險）。**不要求使用者手動複製程式碼或貼上完整 Diff。**
5. **GPT 遠端獨立 Review**：GPT 直接以 GitHub 連線取得已 Push 的確切 Head Commit、對 `main` 的 Diff、關鍵原始碼、測試與相關契約，核對 Issue Scope、Business Logic、Security、Transaction、Regression 及測試證據。可按 P0／P1／P2 回報附檔案／原因／修正建議的 Finding；給出 `CHANGES REQUIRED` 或 `FINAL PASS`。**靜態檢查、Codex 回報的本地測試，以及 GPT 實際執行的驗證須明確區分**，不得把測試聲稱當作 GPT 親自重跑。
6. **未通過則修正再審**：若 GPT 回報 P0／P1 或其他阻擋性問題，Codex 僅修正當前 Issue、重跑相關驗證，在**同一 Feature Branch 建立新的 Commit 並 Push**；不得用 Force Push 改寫已審查的 Commit。回報新的 Head SHA，由 GPT 以新 SHA 重新審查。尚未取得 Final PASS 不得建立 PR。
7. **Final PASS 後才進入 PR**：GPT 明確對某個 Head SHA 給出 `FINAL PASS` 後，Codex 方可依使用者指示建立 PR，Base 預設 `main`、Head 為已審查分支，PR 標題／描述使用繁體中文，並按下節規定在描述中加入獨立一行 `Closes #<Issue編號>`。建立後回讀驗證 PR Base、Head、Head SHA、描述與範圍正確；Head 若發生新變更須重新審查。
8. **Merge 與下一個 Issue**：**只由使用者人工確認 Merge**；GPT 不得替使用者擅自 Merge。使用者告知完成後，GPT 須透過 GitHub 核實 PR `merged`、Merge Commit／`main` Head、Issue `closed`。確認完成後，才開始下一張 Issue（使用者明確授權例外時除外）。

## GitHub PR 與 Issue 自動關閉規範（強制）

- **針對已完成的單一 Issue 建立 PR 時，PR 描述必須包含獨立一行 `Closes #<Issue編號>`。** 不得只寫「關聯 Issue：#編號」、`Refs #編號` 或一般文字連結，因為這些不會在 Merge 後自動關閉 Issue。
- 建立 PR 之前，必須核對 GitHub 的實際 Issue 編號、PR 的 Base Branch（預設 `main`）與 Head Branch，避免關錯 Issue。
- PR 建立後再次讀取 PR 描述，確認 `Closes #<Issue編號>` 確實存在且指向正確 Issue。
- **PR 只能在 GPT 確認 Final PASS 後建立，Merge 仍由使用者手動執行。** 不得為了自動關閉 Issue 而自動 Merge。
- 使用者告知 Merge 完成後，須查核 GitHub PR 的 `merged` 狀態、Merge Commit 與對應 Issue 的 `closed` 狀態；若 Issue 未關閉，明確回報並安排修正，不能假稱已自動關閉。
- 此規則適用於新對話、新工作階段及往後所有 Epic / Issue；新的 SupplyPilot 工作開始前，先讀取 Repository 根目錄 `AGENTS.md`。若目前環境無法自動取得此檔案，需明確讀取或要求提供，不能假設已載入。
