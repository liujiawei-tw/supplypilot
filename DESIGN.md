# Design System

## Visual World

A procurement workspace built from freight handoff records: the PO stays identifiable as it moves through controlled stages, and the next step is clear without changing the official record's meaning. The waybill is a source for typography, palette, information density, and one identity marker; it does not dictate navigation or turn the interface into a paper form.

## Surface

- Mode: Operate.
- Audience: procurement staff, managers, administrators, and warehouse staff using their authorized areas.
- Primary surface: purchase-order workspace with the eight-stage Kanban and list in the same route.
- Structure: compact top navigation, scoped resource finder, work area, and contextual details. Mobile starts with a searchable list and opens full-screen detail.
- Signature move: a stable PO identity strip paired with a sequential stage rail. After a write, keep the PO in place, re-read backend state, and emphasize the resulting current stage. Never imply an unrecorded history.

## Visual Grammar

- Palette: warm paper workspace (`#f5f3ec`), ivory content (`#fffefa`), ink (`#252a26`), quiet text (`#66706a`), ruled dividers (`#d8d8cd`), evergreen for active work (`#285241`), rust for overdue/error (`#bd553b`), and a visible blue focus ring (`#1b66d1`). Status meaning also uses labels and shape.
- Typography: one functional system sans-serif text voice for Taiwanese Traditional Chinese; tabular figures for IDs, dates, and amounts; no display face or externally fetched font dependency.
- Density: compact but readable rows and ruled sections; spacing uses 4px/8px/12px/16px/24px/32px steps; sustained panels use 12px/16px radii, small controls 8px; avoid decorative card grids.
- Interaction: standard buttons, links, tabs, inputs, filters, tables, and dialogs. No drag-only workflows or decorative motion.
- Responsive: full desktop workflow, readable tablet layout, and mobile-first lists for PO lookup plus direct supplier, inventory, and AI access. Maintain 44px touch targets and visible keyboard focus.

## Decisions

- Keep the existing React, TypeScript, Vite, Ant Design component behavior, API clients, authentication, locale, and route guards. Override the default visual identity and change layout and task topology where needed.
- Keep Kanban and list together. Keep official PO `status` distinct from nullable Kanban `stage`; show company expected date beside supplier promised date.
- Search only the endpoint-supported scope. PO, supplier, and material queries use their existing server filters. Document lookup uses its supported filters; it has no free-text title search.
- Detail and risky actions remain tied to existing protected API commands. 409 or unknown write results trigger a fresh backend read before the interface offers another attempt.
- Show document processing, review, and index state separately. AI citations preserve real document, version, and excerpt; insufficient evidence stays explicit.
- Preserve permissions from `/auth/me` and `canManageUsers`. Frontend visibility is not backend authorization.
- No notification center, PO export, PO/document attachment, supplier ranking, stock warning, partial receiving, automatic payment, new API, or new business workflow.

## Scope

This system supports the current whole-product redesign. It does not claim a universal cross-resource search API or introduce new procurement policy.



