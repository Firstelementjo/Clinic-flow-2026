# ADR-0004 — Angular i18n Strategy

Status: PROPOSED

## Context

Today the web app uses a single flat `STRINGS` dictionary
(`apps/web/src/lib/i18n.ts`, ~800 keys) with `ar`/`en` per key, CSS
logical properties, and a `DirectionProvider` flipping RTL/LTR. Arabic is
the source language. Some hard-coded English strings remain in
`ListView.tsx` and elsewhere. The target is Angular standalone with
explicit runtime i18n ownership.

## Decision

Adopt a runtime i18n layer for Angular (Transloco or an equivalent that
supports lazy namespaces, interpolation, and locale switching without
reload). Translation ownership stays with the legacy `STRINGS` keys,
migrated into per-feature namespaces at route cutover. Arabic remains the
authoring language; English is the derived translation. All user-facing
strings must move out of components; hard-coded leftovers are lint-warned.

## Consequences

- Translation files are versioned per route; the legacy flat dictionary
  is the migration source of truth.
- RTL layout continues via CSS logical properties (`ms-*`, `me-*`,
  `ps-*`, `pe-*`, `start-*`, `end-*`) and a `dir` attribute on `<html>`.
- Angular guards are UX only; locale never changes authorization.
- Playwright journeys must cover Arabic RTL and English LTR at
  desktop/tablet/mobile.
