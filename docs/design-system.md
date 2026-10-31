# FirstElement design system

The visual brief, the reasoning behind it, and the rules that keep feature
code from drifting away from it. `packages/ui` is the implementation; this
document is why it looks the way it does.

## The subject, honestly stated

A support agent in Riyadh or Amman sits with this open for a nine-hour
shift under office lighting. They are not browsing — they are triaging a
queue that is getting longer while they read. The screen's job, in every
view: show what needs a human right now, and get them into it in one
click. Arabic is the working language; English is the second working
language, not a fallback.

That rules out a lot. A dark "AI console" fights daylight and hides the
one thing that must stay legible — status. A cream-and-serif editorial
look belongs to something you read, not something you operate. A purple
gradient says "we bought an AI API." None of them survive nine hours.

## Direction: the operations board

The reference object is a shift wallboard, not a dashboard: cool paper
ground, hairline structure, tabular figures, and a small number of colors
that each mean exactly one thing. Density is a feature. Decoration is
overhead paid by someone who is behind on their queue.

## Color

Six carriers of meaning, plus neutrals. Every one has a job; none is
decorative.

| Token | Hex | Meaning — and only this |
| --- | --- | --- |
| `--fe-ink` | `#10161F` | Primary text and structural darks |
| `--fe-slate` | `#5A6675` | Secondary text, meta, hairlines |
| `--fe-mist` | `#EDF1F5` | App ground — cool paper, never warm |
| `--fe-surface` | `#FFFFFF` | Panels and rows |
| `--fe-signal` | `#0E6E63` | Brand, primary actions, **human**-handled state |
| `--fe-agent` | `#4356B8` | **AI**-handled state, AI-authored turns, run traces |
| `--fe-escalation` | `#C2410C` | Escalation and handoff severity — nothing else |
| `--fe-danger` | `#B0232A` | Destructive actions and system failure |

Escalation and danger are deliberately separate colors (plan §4). A
conversation boiling over and a failed upload are different emergencies,
and an agent scanning a list must be able to tell them apart before
reading a word. Keeping burnt orange exclusively for escalation is what
makes it readable at a glance; using it for a delete button would spend
that meaning.

Teal for human, indigo for AI, consistently, everywhere: the inbox rail,
the timeline, the analytics legend. Once learned, the legend never has to
be read again.

## Type

- **Body / UI — IBM Plex Sans Arabic.** Chosen because it was drawn as a
  bilingual system: the Arabic and the Latin Plex share proportions and
  weight, so a mixed row of Arabic names and Latin product codes sits on
  one visual line instead of two. Open apertures hold up at the 12–13px
  sizes a dense inbox actually uses. This is the single most consequential
  choice in the system and it is not the default system stack.
- **Display — Zain, used sparingly.** Section headers, empty states, the
  login mark. Sharper and more condensed than Plex in both scripts, so a
  header reads as a header without going up three sizes — which matters
  when vertical space belongs to the queue.
- **Utility — IBM Plex Mono.** Scores, IDs, timestamps, token counts.
  Tabular figures so numbers in a column compare by position, not by
  reading.

Numerals are Western Arabic (0–9) in both locales: these are operational
figures compared across rows and against dashboards, and Gulf business
software overwhelmingly sets them this way.

## RTL

Direction flips the *layout*, not the meaning. Mirrored: pane order,
alignment, list rails, chevrons, progress direction. Never mirrored:
numerals, latin identifiers, timestamps, sparkline time axes (time still
runs left→right), and logos. Rules are expressed with logical CSS
properties (`padding-inline-start`, `border-inline-start`) so a component
written once is correct in both directions.

## Layout concepts

### Unified inbox — three-pane (RTL shown)

```
┌────────────┬──────────────────────────────┬──────────────────┐
│  الفلاتر   │        المحادثة              │  بطاقة العميل    │
│  Filters   │        Thread                │  Customer        │
│            │                              │                  │
│ ▸ الكل     │ ▌ AI  ١٤:٠٢  …               │  الاسم / القناة  │
│ ▸ مُصعّدة  │ ▌ AI  ١٤:٠٣  …               │  التاريخ         │
│ ▸ لي       │ ══ THE SEAM ═══════════════  │  المحادثات       │
│ ▸ غير معيّن│ ▌ HUMAN 14:05 …              │  السابقة         │
└────────────┴──────────────────────────────┴──────────────────┘
```

Filters stay pinned because triage is filter-driven; the customer card
stays visible because the answer to "who is this" should never cost a
navigation.

### Customer 360 — identity header over channel-merged timeline

One header (resolved person, not handle), then every conversation across
every channel in one column, each row rail-coded by channel. The point of
`CustomerIdentity` (plan §7) is visible here or it isn't visible anywhere.

### Analytics — decision strip over detail

Four figures across the top that an ops manager acts on (escalation rate,
AI resolution rate, median time to human, AI spend), each with its trend;
everything below is the drill-down. No donut charts: proportion is not
the question anyone asks here.

## Signature: the seam

Where the AI hands a conversation to a person, the thread does not simply
continue in a different color. It is interrupted by a full-width rule —
the seam — carrying the timestamp, the trigger that fired, the score at
that moment, and who inherited it.

It is the only loud element in the product, and it earns that because it
is the product's thesis: the handoff is the moment the system either
works or fails the customer. Everything else stays quiet so this reads
instantly. (The frustration sparkline was the other candidate; running
both would have halved each. The score appears as a mono chip instead.)

## Rules for feature code

1. Colors come from tokens. A hex value in a feature component is a bug.
2. Spacing comes from the 4px scale in `tokens.css`.
3. Directional CSS uses logical properties — no `margin-left`.
4. New visual patterns land in `packages/ui` first, then get used.
