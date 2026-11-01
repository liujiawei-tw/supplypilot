# SupplyPilot Documentation

This directory preserves the V1 implementation baseline and current V2 implementation notes. **Confirmed V2 requirements and planned changes live under [docs/v2/](v2/README.md); they are not implemented merely because the specifications exist.** GitHub Issues, merged pull requests and recorded validation evidence remain the source of implementation status. The Spring AI RAG migration branch writes a `semantic_chunks` compatibility projection from published PgVectorStore chunks, and the projection and existing Agentic SQL regression classes passed locally. The fixed RAG QA matrix passed 42/42 through API, Advisor and Testcontainers PgVectorStore using Fake Chat/Embedding models; answer validator tests passed 44/44, excerpt validator tests 41/41, and four affected RAG suites passed 102/102. Full Maven `verify` ran 1,093 tests and failed with four AI controller failures: three reproduce on the base SHA, and the remaining stale read-gate expectation passed after its targeted contract update. Full `verify` was not rerun after the final answer validation changes. Database Backfill, corpus integrity reconciliation, Browser E2E and real Gemini document-query E2E remain unverified. `SEMANTIC_READ_ENABLED` stays off by default. See the [Semantic RAG runbook](semantic-rag-runbook.md), [validation evidence](evidence/spring-ai-rag-validation-2026-11-01.md) and [API migration status](api.md#post-apiv1document-queries) for details.

## SupplyPilot V2 已確認需求（待實作）

- [V2 需求決策總綱](v2/README.md)
- [V2 帳號安全與人員交接](v2/accounts-and-security.md)
- [V2 採購／八階段 Kanban](v2/procurement-and-kanban.md)
- [V2 Documents / RAG](v2/document-rag.md)
- [V2 前端與顯示](v2/ui-and-reporting.md)
- [V2 Demo 與驗收](v2/demo-and-acceptance.md)
- [V1→V2 差異與 Issue 對照](v2/migration-and-issue-map.md)

## Product and Requirements

- [Project direction](SupplyPilot_專案整體方向與規劃總綱.md)
- [Company profile](company-profile.md)
- [As-Is procurement flow](as-is-procurement-flow.md)
- [To-Be procurement flow](to-be-procurement-flow.md)
- [Functional requirements](functional-requirements.md)
- [Non-functional requirements](non-functional-requirements.md)
- [Acceptance criteria](acceptance-criteria.md)

## Architecture and Contracts

- [Domain model](domain-model.md)
- [ERD](erd/erd.md)
- [API design](api.md)
- [Package architecture](package-architecture.md)
- [Transaction boundaries](transaction-boundary.md)
- [Validation and exceptions](validation-exception.md)
- [Security design](security-design.md)
- [UML diagrams](uml/)

## Engineering

- [Coding standards](coding-standards.md)
- [Testing strategy](testing-strategy-merged.md)
- [Frontend design](frontend-design.md)
- [Impeccable 前端改善審查、UI-A/B/C/D 與 E9 依賴](impeccable-ui-review-plan.md)
- [Semantic RAG runbook](semantic-rag-runbook.md)
- [Spring AI RAG current migration/API status](api.md#post-apiv1document-queries)
- [Environment configuration](environment-configuration.md)
- [Cross-document consistency review](cross-document-consistency-review.md)
- [Pre-implementation checklist](pre-implementation-checklist.md)

## Execution

- [SupplyPilot V1 GitHub Issues backlog](SUPPLYPILOT_V1_ISSUES_ZH-TW.md)

