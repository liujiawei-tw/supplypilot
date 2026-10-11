# ADR-SP-720 — Adopt Spring AI for SupplyPilot Assistant

- Status: Accepted for planned SP-720 / SP-721 implementation
- Date: 2026-10-11
- Scope: E7 AI Assistant / Controlled Tool Calling
- Baseline: Spring Boot 4.1.x, Java 21, existing SP-710 RAG

## Context

SP-700 and SP-710 have been implemented and merged. The original SP-720 / SP-721 requirements describe AI query and tool boundaries without making Spring AI mandatory. The team now explicitly chooses Spring AI for the remaining E7 assistant work, retaining the previously delivered services and business rules.

## Decision

1. SP-720 must use a stable Spring AI **2.0.x** release, compatible with Spring Boot 4.1.x, with `ChatClient` / `ChatModel` and `@Tool` or `ToolCallback`; do not use 2.1 Milestone as a production baseline. Determine exact patch and provider starter from verified Maven dependencies at implementation time.
2. At least one actual LLM provider must be configurable and usable; credentials and model configuration come from runtime environment / secret management. Automated tests use Fake ChatModel / Tool Callback and must not call paid providers.
3. SP-720 exposes only an explicit read-only allowlist: recommendation, supplier ranking, inventory, open PO and SP-710 document query. Tools call existing owning Application / Query Services under current-user authorization. No arbitrary bean / repository / SQL access; no system-admin impersonation.
4. Do not duplicate deterministic procurement calculation or rewrite SP-710 parsing, chunking, embedding, retrieval, and Source / Version evidence. The RAG tool delegates to the existing service.
5. SP-721 reuses this integration and produces schema-validated draft proposals. A separate backend-verified explicit user confirmation is required before calling the existing `PurchaseOrderService.createDraft()`; proposals alone never write.
6. The AI provider may fail independently; core procurement API availability must not depend on it. External AI calls are outside long DB transactions.

## Consequences

- SP-720 and SP-721 Issue descriptions, master backlog and relevant architecture, environment, security and testing specifications are amended.
- Existing completed SP-700 / SP-710 work remains unchanged.
- Tool permission, provider error mapping, fake-provider integration testing, evidence provenance and confirmation-before-write become explicit acceptance gates.
- Spring AI does not itself grant data access; Spring Security remains the authorization authority.

## References

- https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
- https://spring.io/projects/generations/
- GitHub Issues #32 and #33

## Confirmed design choices (2026-10-11; supersedes conflicting drafts)

- **LLM provider**: Google Gemini, preferring a low-cost Flash model. The exact model identifier must be validated against available API capabilities, Tool Calling support and pricing during SP-720 implementation; it remains configurable.
- **Framework and tools**: stable Spring AI 2.0.x, ChatClient / ChatModel, model-selected read-only Tool Calling executed by Spring AI and authorized again through current-user Backend Method Security. No arbitrary tools or privileged identity.
- **RAG strategy**: SP-710 remains unchanged and is integrated in SP-720. A **separate new SP-722 (#80)** upgrades semantic retrieval using **Google Gemini Embedding and PostgreSQL + pgvector**. MySQL remains the source of truth for procurement and DocumentMetadata; do not represent cross-database steps as atomic distributed transactions.
- **Answer trust**: explanations from LLM, while backend verifies structured numeric values, identifiers, units and authoritative business records. Unsupported/contradictory assertions must not be returned as trusted answers.
- **Conversations and transport**: V1 single-turn, no persisted chat memory; synchronous JSON response only.
- **SP-721 confirmation**: Proposal → server-issued Confirm Token/ID → backend verification → existing PurchaseOrderService.createDraft() → DRAFT. Client-provided booleans and LLM statements are not confirmation.
- **Provider availability**: optional AI activation; missing/unavailable provider returns established AI 503 contract, never prevents core business APIs starting.
- **Observability / cost controls**: basic token usage when available, latency, errors, bounded per-request tokens/tool-loop count/provider timeout, redaction of PII, credentials and sensitive procurement content.
- **Provider details awaiting runtime verification, not user selection**: exact Flash model ID, embedding model ID/dimension, max output tokens and Google-supported API endpoint; these must be verified and documented in SP-720/SP-722 implementation rather than invented.

## Semantic RAG ownership

SP-722 owns pgvector installation/schema, embeddings, migration/backfill, reindex idempotency, dimension/model-version compatibility, vector query, MySQL status gating and failure reconciliation. SP-720 must not expand to introduce PostgreSQL or rewrite SP-710. See [Issue #80](https://github.com/liujiawei-tw/supplypilot/issues/80).

