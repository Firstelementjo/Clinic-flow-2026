import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The KPI card.
 *
 * `docs/migration-AGENT.md` §4.5: label (13, muted), value (30/600) with a small
 * unit, one line of context, a 6px progress bar. One colour for every bar — a
 * bar whose colour encodes its value is a second legend to learn, and the whole
 * point of the KPI strip is that it can be read in one pass.
 *
 * The value is a rendered string, not a number: the caller formats it through
 * `FormatService` so the locale, digits and timezone are decided in one place and
 * the card cannot invent its own formatting.
 */
@Component({
  selector: 'fe-kpi-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fe-card">
      <p class="fe-sub">{{ label() }}</p>
      <p class="fe-kpi-value fe-num">
        {{ value() }}@if (unit()) {
          <small>{{ unit() }}</small>
        }
      </p>
      <p class="fe-sub">{{ context() }}</p>
      @if (progressPercent() !== null) {
        <div
          class="fe-bar"
          role="img"
          [attr.aria-label]="progressLabel()"
          [attr.aria-valuenow]="progressPercent()"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <i [style.inline-size.%]="progressPercent()"></i>
        </div>
      }
    </div>
  `,
})
export class KpiCardComponent {
  readonly label = input.required<string>();
  /** Already formatted for the active locale. */
  readonly value = input.required<string>();
  readonly unit = input<string>('');
  readonly context = input<string>('');
  /** `null` renders no bar at all, rather than a bar at zero. */
  readonly progressPercent = input<number | null>(null);
  readonly progressLabel = input<string>('');
}

/*
 * The page header lives in its own file (`page-header.component.ts`).
 *
 * It used to live here, taking pre-translated strings. It moved when group 5 needed
 * two things this version could not express: an *unavailable* reason, so a screen
 * whose backend route does not exist can say why instead of looking broken, and a
 * back affordance. Those are not dashboard ideas, so the header is no longer owned
 * by the dashboard's panel file.
 */

/** One step of the funnel strip. */
export interface FunnelStep {
  readonly key: string;
  readonly label: string;
  /** Already formatted. */
  readonly value: string;
  /** 0..100, precomputed by the caller so the bar and the number cannot disagree. */
  readonly percent: number;
}

/**
 * The connected five-step strip the dashboard funnel uses.
 *
 * `direction` is `horizontal` for a strip that reads in document order and
 * `vertical` at narrow widths, where five columns would each be about 60px wide.
 * It is a prop rather than a media query inside the component so the caller
 * decides, with the layout, in one place.
 *
 * The bar's direction is logical (`inset-inline`), so the strip reads correctly
 * in RTL: the first step's bar grows from the inline-start edge in both
 * directions, which is what "this is the start of the flow" means.
 */
@Component({
  selector: 'fe-funnel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="funnel" [class.funnel--vertical]="direction() === 'vertical'">
      @for (step of steps(); track step.key) {
        <li class="step">
          <span class="fe-sub">{{ step.label }}</span>
          <b class="fe-num">{{ step.value }}</b>
          <i [style.inline-size.%]="step.percent"></i>
        </li>
      }
    </ol>
  `,
  styles: [
    `
      .funnel {
        display: grid;
        grid-template-columns: repeat(5, 1fr);
        margin-block-start: var(--fe-space-4);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        overflow: hidden;
        list-style: none;
        margin-inline: 0;
        padding: 0;
      }
      .funnel--vertical {
        grid-template-columns: 1fr;
      }
      .step {
        padding: 14px 16px;
        border-inline-end: 1px solid var(--line);
        position: relative;
      }
      .step:last-child {
        border-inline-end: 0;
      }
      .funnel--vertical .step {
        border-inline-end: 0;
        border-block-end: 1px solid var(--line);
      }
      .funnel--vertical .step:last-child {
        border-block-end: 0;
      }
      .step b {
        display: block;
        font-size: 26px;
        font-weight: 600;
      }
      .step i {
        position: absolute;
        inset-inline: 0;
        inset-block-end: 0;
        block-size: 4px;
        background: var(--pri);
        opacity: 0.85;
      }
    `,
  ],
})
export class FunnelComponent {
  readonly steps = input.required<readonly FunnelStep[]>();
  readonly direction = input<'horizontal' | 'vertical'>('horizontal');
}
