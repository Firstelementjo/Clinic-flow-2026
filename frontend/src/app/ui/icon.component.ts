/**
 * Inline SVG icons.
 *
 * Inline rather than an icon font or a sprite sheet, for three reasons: no extra
 * request, no flash of missing glyph, and — the one that matters here — a
 * per-icon answer to the RTL question. Direction is expressed with logical
 * properties (`transform: scaleX(-1)` guarded by a `[dir='rtl']` selector on the
 * host, never a second asset), so an arrow flips in Arabic and a clock does not.
 * `directional` is that declaration, and it is data rather than a convention.
 */
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { NavIcon } from '../core/navigation/navigation';

/** Icons whose *meaning* includes a direction, and which therefore flip in RTL. */
const DIRECTIONAL: ReadonlySet<IconName> = new Set<IconName>(['inbox']);

/**
 * The icons the app can draw.
 *
 * Navigation owns the first set: an icon belongs to a navigation entry, and the
 * entry names it. The second set is for the places where a piece of information
 * needs a mark rather than a word — a phone number, a calendar, a clinical
 * warning. Those marks are drawn here too, because the one rule that matters
 * for them is the same: an icon is decoration, it is hidden from assistive
 * technology, and the word next to it is the meaning.
 */
export type IconName =
  | NavIcon
  | 'calendar'
  | 'card'
  | 'clipboard'
  | 'doctor'
  | 'mail'
  | 'phone'
  | 'robot'
  | 'stethoscope'
  | 'tooth'
  | 'warning'
  | 'search'
  | 'check'
  | 'x'
  | 'ai'
  | 'rotate-ccw'
  | 'alert'
  | 'alert-triangle'
  | 'edit'
  | 'plus'
  | 'shield'
  | 'clock'
  | 'eye'
  | 'trash'
  | 'info';

const PATHS: Record<IconName, string> = {
  dashboard: 'M3 3h7v9H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 16h7v5H3z',
  inbox:
    'M3 12h4l2 3h6l2-3h4M3 12l3-8h12l3 8v7a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z',
  people:
    'M16 19v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6M17 11a3 3 0 1 0 0-6M22 19v-2a4 4 0 0 0-3-3.9',
  team: 'M4 4h16v16H4zM4 9h16M9 9v11',
  knowledge: 'M4 5a2 2 0 0 1 2-2h12v18H6a2 2 0 0 1-2-2zM8 7h7M8 11h7',
  // A plug: two ends and a link, for a connector. Not directional — a connection
  // has no "forward" for RTL to mirror.
  channels: 'M9 7V4h6v3M6 7h12v4a6 6 0 0 1-12 0zM12 17v4',
  // A ledger with a clock: the audit trail is a record with a time on it.
  audit: 'M5 3h11l3 3v15H5zM16 3v3h3M8 11h8M8 15h8M8 18h5',
  admin: 'M12 3l8 4v5c0 5-3.4 8.4-8 9-4.6-.6-8-4-8-9V7zM9 12l2 2 4-4',
  finance: 'M4 19V5M4 19h16M8 15V9M12 15v-4M16 15V7M20 15v-6',
  exports: 'M12 3v12M12 15l-4-4M12 15l4-4M4 19h16',
  clinic: 'M12 6v6M9 9h6M4 20V10l8-6 8 6v10z',
  settings:
    'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.2 2.2M16.9 16.9l2.2 2.2M19.1 4.9l-2.2 2.2M7.1 16.9l-2.2 2.2',
  calendar: 'M4 6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM4 9h16M8 3v3M16 3v3',
  // A card: a bill, an invoice, a claim.
  card: 'M3 6h18v12H3zM3 10h18M6 15h4',
  clipboard: 'M9 4h6v3H9zM8 5H6v16h12V5h-2M9 12h6M9 16h4',
  // A clinician, with the stethoscope that says so.
  doctor: 'M6 4v4a5 5 0 0 0 10 0V4M5 4h3M13 4h3M16 14v1a4 4 0 0 0 4 4 2 2 0 0 0 2-2v-1M20 12a2 2 0 0 0-2 2',
  mail: 'M3 6h18v12H3zM3 7l9 6 9-6',
  phone: 'M6 3h3l2 5-2.5 1.5a12 12 0 0 0 5 5L15 12l5 2v3a2 2 0 0 1-2 2A16 16 0 0 1 4 5a2 2 0 0 1 2-2z',
  robot: 'M12 3v3M7 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM9.5 10v1.5M14.5 10v1.5M9 14h6M4 11v3M20 11v3',
  stethoscope: 'M7 3v4a5 5 0 0 0 10 0V3M6 3h3M15 3h3M17 13v2a4 4 0 0 0 4 4 2 2 0 0 0 2-2v-1M21 12a2 2 0 0 0-2 2',
  // A molar: the chart of the mouth.
  tooth:
    'M12 5.6C10.8 4.6 9.4 4 8 4 5.8 4 4.4 6 4.4 8.6c0 1.7.6 2.9 1 4.4.3 1.1.5 2 .7 3.1.2 1 .5 2.3 1.4 2.3.9 0 1.1-1.4 1.3-2.5l.3-2.4c.1-.8.5-1.3 1.1-1.3h3.6c.6 0 1 .5 1.1 1.3l.3 2.4c.2 1.1.4 2.5 1.3 2.5.9 0 1.2-1.3 1.4-2.3.2-1.1.4-2 .7-3.1.4-1.5 1-2.7 1-4.4C19.6 6 18.2 4 16 4c-1.4 0-2.8.6-4 1.6z',
  // A warning triangle. Reserved: it means a clinical or system warning, and
  // nothing else is allowed to wear it.
  warning: 'M12 4.5 21 19H3zM12 10v4M12 16.5v.5',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35',
  check: 'M20 6L9 17l-5-5',
  x: 'M18 6L6 18M6 6l12 12',
  ai: 'M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83',
  'rotate-ccw': 'M1 4v6h6M3.51 15a9 9 0 1 0 2.13-9.36L1 10',
  alert: 'M12 4.5 21 19H3zM12 10v4M12 16.5v.5',
  'alert-triangle': 'M12 4.5 21 19H3zM12 10v4M12 16.5v.5',
  edit: 'M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z',
  plus: 'M12 5v14M5 12h14',
  shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z',
  clock: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2',
  eye: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6',
  trash: 'M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2',
  info: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01',
};

@Component({
  selector: 'fe-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      class="icon"
      [class.icon--flip]="flips()"
      [style.inline-size]="sizePx()"
      [style.block-size]="sizePx()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-linecap="round"
      stroke-linejoin="round"
      [attr.stroke-width]="strokeWidth()"
      aria-hidden="true"
      focusable="false"
    >
      <path [attr.d]="path()" />
    </svg>
  `,
  styles: [
    `
      .icon {
        inline-size: 1rem;
        block-size: 1rem;
        flex: none;
      }
      /* Only a directional icon flips. A clock, a person and a calendar keep
         their orientation in Arabic because their meaning has no direction. */
      :host-context([dir='rtl']) .icon--flip {
        transform: scaleX(-1);
      }
    `,
  ],
})
export class IconComponent {
  readonly name = input.required<IconName>();
  readonly strokeWidth = input<number>(1.75);
  readonly size = input<number | string>();

  protected readonly path = computed(() => PATHS[this.name()]);
  protected readonly flips = computed(() => DIRECTIONAL.has(this.name()));
  protected readonly sizePx = computed(() => {
    const s = this.size();
    if (s === undefined || s === null || s === '') return undefined;
    return typeof s === 'number' ? `${s}px` : s;
  });
}
