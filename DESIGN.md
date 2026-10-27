---
name: SupplyPilot Procurement Workbench
description: A modern, restrained workspace for controlled procurement and supply handoffs.
colors:
  shell-navy: "#10243a"
  shell-blue: "#1b3550"
  primary-teal: "#0d6b67"
  primary-teal-hover: "#095753"
  signal-amber: "#cb7b0f"
  workspace-slate: "#f2f5f8"
  surface-white: "#ffffff"
  surface-muted: "#f7f9fb"
  ink: "#16283b"
  muted-ink: "#53697e"
  border: "#dbe4ec"
  success: "#137248"
  warning: "#8a4b08"
  danger: "#b42318"
  info: "#245db3"
  focus-ring: "#2563eb"
typography:
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft JhengHei', 'Noto Sans TC', sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft JhengHei', 'Noto Sans TC', sans-serif"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: 1.4
rounded:
  sm: "8px"
  md: "12px"
  lg: "16px"
spacing:
  1: "4px"
  2: "8px"
  3: "12px"
  4: "16px"
  6: "24px"
  8: "32px"
components:
  button-primary:
    backgroundColor: "{colors.primary-teal}"
    textColor: "{colors.surface-white}"
    rounded: "{rounded.sm}"
    height: "40px"
  card:
    backgroundColor: "{colors.surface-white}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
---

# Design System

## Overview

SupplyPilot is an operational procurement workspace. Its visual language uses modern transport wayfinding: calm dark structure, crisp readable data, and a single visible route through a purchase order's controlled stages. The visual system supports the work; it does not turn the product into an airport board or a chat-first AI interface.

## Colors

The shell uses deep navy. Work surfaces use cool slate and white. Teal marks the primary action and active operational path. Amber is reserved for deadline or attention states. Success, warning, danger, and information always retain their text labels and distinct icons or shapes; color is never the only signal.

## Typography

Use the local system sans stack, including Traditional Chinese fallbacks; do not fetch fonts from the network. Keep IDs, dates, quantities, and money tabular. Use a clear title, body, and label scale without decorative display faces or technical-looking monospace decoration.

## Layout

Keep a compact top utility bar, scoped resource search, and consistent navigation. The eight-stage PO workspace is the main surface, with Kanban and list views together. Other routes retain layouts fitted to their tasks; do not force every page into the same title-card-table template. Use compact but readable data density, 4/8/12/16/24/32px spacing, and responsive layouts that remain usable at 200% zoom.

## Elevation & Depth

Prefer clear surface contrast and thin borders. Use shadows only to separate overlays or focused sign-in content. No glow, glass effect, ambient gradient, or ornamental AI imagery.

## Shapes

Use 8px control corners and 12–16px larger work surfaces. Avoid excessive pills, nested cards, and identical card grids.

## Components

Use existing React, TypeScript, Vite, Ant Design behavior, APIs, auth, locale, and route guards. Tune shared Ant Design tokens to the SupplyPilot palette. Keep keyboard focus highly visible, controls touch-friendly, and reduced-motion preferences respected. The favicon and shell mark use the same route/checkpoint symbol.

The PO identity strip and sequential stage rail are the signature move. After an action, re-read the backend and mark only the returned current stage. Keep official PO status separate from Kanban stage, and company expected delivery separate from supplier promised delivery.

## Do's and Don'ts

- Keep real API capabilities, permissions, business states, transaction safeguards, and failure recovery.
- Keep Demand and Recommendations out of V2 primary navigation and styling only as existing legacy routes.
- Show document processing, review, and semantic-index states separately; AI citations must point to real document versions and excerpts.
- Use Traditional Chinese (Taiwan), Asia/Taipei dates, locale-aware number grouping, and existing currency.
- Avoid purple AI gradients, glowing robot imagery, glassmorphism, fake metrics, decorative charts, invented states, and controls with no working behavior.
- Do not add notifications, PO export, document attachments, supplier ranking, stock alerts, partial receiving, or automatic payment.
