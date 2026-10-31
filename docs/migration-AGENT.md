# AGENT.md - FirstElement Clinical Redesign

You are a senior front-end engineer and product designer. Your job is to restyle the existing FirstElement clinic web app (Clinic Flow AI Suite) to match the approved clinical design. Read this file fully before you touch any code, and re-read it whenever you are unsure.

## 1. Goal

Make the app look and feel like calm, trustworthy clinical software, in both light and dark themes, in English and Arabic (RTL).

- Keep the layout: same sidebar, same pages, same structure, same routes.
- Change the style: colors, type, spacing, surfaces, states, copy.
- Do not change business logic, API calls, data models, auth, or routing.

## 2. Source of truth

1. `firstelement-clinical-redesign.html` is the approved visual prototype. Open it in a browser and compare against it constantly. If this document and the prototype disagree on a visual detail, the prototype wins.
2. `TASK.md` is the work plan. Work through it in order and tick items off.
3. The old design description (glassmorphism, gradient nav, Inter) is **superseded**. Do not follow it.

The prototype uses sample data and plain HTML. Do not copy its markup blindly. Rebuild each page with the app's existing components and data.

## 3. Before you write code

1. Inspect the repo. Identify the framework, the styling system (the project appears to use Tailwind with tokens such as `ink`, `slate`, `surface`, `panel`, `agent`, `signal`, `hairline`), the theme mechanism, the i18n/RTL mechanism, and the shared components (for example `ListView`, status chips, buttons, cards).
2. Write a short plan (max 15 lines) listing which files you will change. Do not start editing until you have written it.
3. Prefer changing design tokens and shared components over editing every page. If most of the redesign can land by changing token values, do that first.

## 4. Design system

### 4.1 Principles

- One primary color (deep teal). Blue is secondary information only.
- Green, amber and red are reserved for status. Never use them as decoration.
- Flat, light surfaces with 1px borders and one soft shadow. No gradients, no glassmorphism, no backdrop blur on content.
- Generous whitespace, clear hierarchy, sentence case everywhere.
- No emojis in the UI. No all-caps labels. No decorative eyebrow labels above headings.
- Every state is designed: loading, empty, error, disabled, focus.

### 4.2 Color tokens

Define these as CSS variables on `:root`, and override them for dark. If the app already has tokens, keep the existing token names and replace their values, so components keep working.

| Token | Light | Dark | Use |
|---|---|---|---|
| `--bg` | `#F4F8FA` | `#0A1620` | App background |
| `--surf` | `#FFFFFF` | `#102130` | Cards, sidebar, header |
| `--surf2` | `#F8FBFC` | `#0D1B28` | Inset areas, hover, inputs on cards |
| `--ink` | `#0F2A3A` | `#E6F0F5` | Primary text |
| `--mute` | `#5A7183` | `#8FA7B7` | Secondary text |
| `--line` | `#E1E9EE` | `#1F3445` | Borders, dividers |
| `--pri` | `#0A7C8C` | `#38BDCB` | Primary actions, active nav, key data |
| `--pri-ink` | `#FFFFFF` | `#04222A` | Text on primary |
| `--pri-soft` | `#E3F3F5` | `#10353F` | Active/selected backgrounds |
| `--blue` / `--blue-soft` | `#2A62A8` / `#E8F0FA` | `#7DB1F0` / `#13304F` | Secondary info, admin role |
| `--ok` / `--ok-soft` | `#0F8A5F` / `#E2F4EC` | `#4CD6A0` / `#0F3328` | Confirmed, active, verified |
| `--warn` / `--warn-soft` | `#B7690A` / `#FDF1DE` | `#F0B35A` / `#3A2A10` | Pending, chronic flag |
| `--bad` / `--bad-soft` | `#C0392B` / `#FBE8E6` | `#F27D70` / `#3F1A17` | Cancelled, allergy, errors |
| `--shadow` | `0 1px 2px rgba(15,42,58,.06), 0 4px 14px rgba(15,42,58,.04)` | `0 1px 2px rgba(0,0,0,.4)` | Cards |

Rules:
- Body text must meet WCAG AA contrast (4.5:1) in both themes. Check `--mute` on `--surf`.
- Dark mode is not "invert the light theme". Use the dark values above. Never use pure black or pure white backgrounds.
- Theme resolution order: user's saved choice, then system preference. Keep the existing toggle if there is one.

### 4.3 Typography

- Font: **IBM Plex Sans** with **IBM Plex Sans Arabic** as the Arabic fallback, then `system-ui`. Self-host the fonts if the app must work offline or on restricted networks.
- Base size 14px, line height 1.5.
- Scale: page title 24/600, section title 15/600, KPI value 30/600, body 14/400, secondary 13/400, badge 12/500.
- Numbers use tabular figures where they sit in columns or KPIs (`font-variant-numeric: tabular-nums`).
- Use monospace only for real code or identifiers (tool names in the AI telemetry panel). Not for general labels.

### 4.4 Shape, spacing, motion

- Spacing on an 4px grid. Page padding 28px (16px on mobile). Card padding 18px. Gap between cards 16px.
- Radius: cards 12px, buttons and inputs 8px, badges fully rounded, avatars circular.
- Borders 1px `--line`. Cards also get `--shadow`.
- Transitions 150ms ease on color, background and border only. No movement on hover, no entrance animations. Respect `prefers-reduced-motion`.
- Focus: 2px outline in `--pri` with 2px offset on every interactive element. Never remove it.

### 4.5 Components

**Sidebar:** 232px wide, `--surf` background, 1px end border. Items are 9px/12px padding, 8px radius, muted text. Active item: `--pri-soft` background, `--pri` text, 3px inset marker on the start edge (flips in RTL). No gradient pill, no lift effect. Under 860px, collapse to an icon-only rail (64px). Footer shows avatar, name, role badge.

**Header:** 18px/600 page title, theme and language buttons on the end side. No decorative icons beside the title.

**Page header:** title (24/600), one-line description in `--mute` (max 60 characters wide), actions aligned to the end.

**Card:** `--surf`, 1px `--line`, 12px radius, `--shadow`, 18px padding.

**KPI card:** label (13, `--mute`), value (30/600) with a small unit, one line of context, a 6px progress bar in `--pri` on `--line`. Use one color for every KPI bar. Do not give each KPI its own color.

**Badge:** 12/500, padding 2px 9px, soft background with matching text. Variants: default (teal), ok, warn, bad, blue, neutral.

**Buttons:** height 36px, 8px radius. Primary: `--pri` fill. Secondary: `--surf` with 1px `--line`. Label states the action ("Save changes", "Add employee"), never "Submit".

**Segmented control:** bordered group, selected segment uses `--pri-soft` and `--pri` text.

**Table / list rows:** 12px vertical padding, 1px dividers, header row in `--mute` 13/500, no zebra striping. Row hover uses `--surf2`.

**Inputs:** 36px high, 8px radius, 1px `--line`, `--surf` background, visible placeholder in `--mute`. Search icons must never overlap the placeholder text (reserve inline-start padding for the icon).

**Chat bubbles:** incoming on `--surf2` with 1px border, outgoing on `--pri` with `--pri-ink` text, 12px radius, max width 72%.

**Empty states:** one title and one sentence telling the user what to do next. Example: "No conversations assigned to you. New patient chats appear here when the AI hands them off."

**Error states:** say what failed and how to fix it, with a Retry button. Never show an error card inside a page header. Never repeat a section label ("ROUTING") on stacked cards.

### 4.6 Page-specific direction

- **Dashboard:** five KPI cards, then the booking funnel as one connected five-step strip (value, label, thin bar at the bottom of each step), then two cards: top procedures and clinician workload. Revenue always in JOD.
- **Inbox:** three columns: conversation list (with search), thread, patient details. Real empty states in each column.
- **Doctors & Shifts:** four summary cards, then a card per doctor with active status, shift days (toggle chips), and slot length (segmented control). Keep the note that changes apply to the AI receptionist immediately.
- **AI Receptionist:** simulator on the start side with test-prompt chips (English and Arabic), telemetry on the end side showing detected intent and tool calls. Telemetry shows an empty state until the first message.
- **People:** patient cards in a responsive grid with avatar, name, phone, medical tags (allergy in `bad`, insurance in `ok`, chronic care in `warn`), next visit, and an "Open profile" action. Filter segments above the grid.
- **Team:** one table (name, email, role, status, open chats, Manage), then an "Import employees" card with a dashed drop area. Replace the raw browser file input with a styled drop area that still uses a real file input underneath.

## 5. RTL and i18n

- Use CSS logical properties everywhere (`margin-inline-start`, `padding-inline`, `inset-inline-end`, `text-align: start`). In Tailwind use `ms-*`, `me-*`, `ps-*`, `pe-*`, `start-*`, `end-*`.
- Icons that imply direction (arrows, send, chevrons) must flip in RTL. Icons that do not (clock, user, calendar) must not.
- Every user-facing string goes through the existing i18n system. Do not hard-code English.
- Test every page in Arabic. Check alignment, truncation, mixed English/Arabic text, and numbers.
- Keep Latin digits in the UI unless the app already uses Arabic-Indic digits.

## 6. Accessibility

- All interactive elements are reachable by keyboard with a visible focus ring.
- Buttons that only contain an icon have an `aria-label`.
- Status is never shown by color alone. Pair color with text.
- Touch targets at least 36px high (44px on mobile where space allows).
- Respect `prefers-reduced-motion` and `prefers-color-scheme`.

## 7. Hard rules

1. Do not change behavior, API contracts, database schema, or auth.
2. Do not delete a component. If one is replaced, migrate every usage first.
3. Do not add a UI library without asking. Use what the repo already has.
4. No gradients, no backdrop blur on content, no emojis, no all-caps labels.
5. No hard-coded colors in components. Only tokens.
6. Do not invent features, data, or numbers. Use real data from the app. Sample data in the prototype is for illustration only.
7. Keep changes small and reviewable: one page or one component group per commit.
8. If something in the repo blocks the design (missing component, conflicting library, unclear data), stop and ask rather than guessing.

## 8. Working method

1. Plan (see section 3).
2. Tokens and global styles first, then shared components, then pages in the order given in `TASK.md`.
3. After each page, run the app, compare it against the prototype in light and dark, English and Arabic, at desktop and mobile widths.
4. Run lint, type-check, and tests. Fix what you broke.
5. At the end of each phase, report: what changed, what is left, anything you were unsure about.

## 9. Definition of done

- Every page in `TASK.md` matches the prototype in both themes and both directions.
- No hard-coded colors, no leftover gradients or blur on content.
- Contrast, focus, and keyboard checks pass.
- No console errors, lint and type checks pass, existing tests pass.
- A short `CHANGELOG-design.md` lists the tokens changed, components touched, and any follow-ups.
