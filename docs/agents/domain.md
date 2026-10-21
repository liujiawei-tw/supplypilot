# SupplyPilot — Agent Skills 的 Domain 文件導覽

> 供 Matt Pocock Skills 的 Repo 探索與測試命名使用。**不新增另一套 Domain 定義或 ADR 權威。**

## 開始探索以前

1. 讀取 [根目錄 AGENTS.md](../../AGENTS.md) 與 [V2 規格總綱](../v2/README.md)。
2. 讀取 [V1→V2 差異與 Issue 對照](../v2/migration-and-issue-map.md)、當次 Issue 及相應的 V2 規格（`procurement-and-kanban.md`、`accounts-and-security.md`、`document-rag.md`、`ui-and-reporting.md`、`demo-and-acceptance.md`）。
3. 在未被 V2 覆蓋的地方，依既有 `docs/domain-model.md`、`docs/api.md`、`docs/security-design.md`、`docs/transaction-boundary.md`、`docs/coding-standards.md`、`docs/testing-strategy-merged.md` 等文件執行。
4. 如 `GLOSSARY.md` 或既有 ADR 存在，閱讀與本次 Issue 相關者；不存在時不必為了使用 Skill 而建立空文件。

## 一致性與限制

- 同一概念以目前核定的 V2 名稱及資料模型為準；V1 Demand／Recommendation 與已取消功能不可當成 V2 新流程。
- 若 Skill 的 `domain-modeling`／`codebase-design` 建議會改變業務規格或需要跨 Issue 重構，先記錄衝突／提議，經既有決策程序核准後才動手。
- 文件整理與 Skills 設定不構成 E2E、Gemini、CI、AWS 的通過證據。任何修改限本 Issue Scope。

新對話從 [V2 Matt Pocock 協作對接文件](../v2/matt-pocock-codex-gpt-handoff.md) 開始。
