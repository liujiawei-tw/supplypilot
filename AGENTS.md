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

## GitHub PR 與 Issue 自動關閉規範（強制）

- **針對已完成的單一 Issue 建立 PR 時，PR 描述必須包含獨立一行 `Closes #<Issue編號>`。** 不得只寫「關聯 Issue：#編號」、`Refs #編號` 或一般文字連結，因為這些不會在 Merge 後自動關閉 Issue。
- 建立 PR 之前，必須核對 GitHub 的實際 Issue 編號、PR 的 Base Branch（預設 `main`）與 Head Branch，避免關錯 Issue。
- PR 建立後再次讀取 PR 描述，確認 `Closes #<Issue編號>` 確實存在且指向正確 Issue。
- **PR 只能在 GPT 確認 Final PASS 後建立，Merge 仍由使用者手動執行。** 不得為了自動關閉 Issue 而自動 Merge。
- 使用者告知 Merge 完成後，須查核 GitHub PR 的 `merged` 狀態、Merge Commit 與對應 Issue 的 `closed` 狀態；若 Issue 未關閉，明確回報並安排修正，不能假稱已自動關閉。
- 此規則適用於新對話、新工作階段及往後所有 Epic / Issue；新的 SupplyPilot 工作開始前，先讀取 Repository 根目錄 `AGENTS.md`。若目前環境無法自動取得此檔案，需明確讀取或要求提供，不能假設已載入。
