import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The status chip.
 *
 * The product's colour legend lives in one component so it is learned once:
 * teal = human-handled, indigo = AI-handled, burnt orange = escalation, red =
 * failure. A chip always carries a text label as well as a colour, because
 * status by colour alone is not an accessible status (`docs/migration-AGENT.md`
 * §6) and because a chip with no label cannot be read at all by a screen reader
 * in a fast-scanning list.
 */
export type StatusTone = 'human' | 'ai' | 'escalated' | 'ok' | 'warn' | 'bad' | 'neutral';

@Component({
  selector: 'fe-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="chip" [class]="'chip chip--' + tone()">
      <ng-content />
    </span>
  `,
  styles: [
    `
      .chip {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        padding: 2px 9px;
        border-radius: 999px;
        font-size: var(--fe-text-xs);
        font-weight: 500;
        background: var(--pri-soft);
        color: var(--pri);
      }
      .chip--human {
        background: var(--pri-soft);
        color: var(--pri);
      }
      .chip--ai {
        background: var(--blue-soft);
        color: var(--blue);
      }
      .chip--escalated {
        background: var(--warn-soft);
        color: var(--warn);
      }
      .chip--ok {
        background: var(--ok-soft);
        color: var(--ok);
      }
      .chip--warn {
        background: var(--warn-soft);
        color: var(--warn);
      }
      .chip--bad {
        background: var(--bad-soft);
        color: var(--bad);
      }
      .chip--neutral {
        background: var(--surf2);
        color: var(--mute);
        border: 1px solid var(--line);
      }
    `,
  ],
})
export class StatusChipComponent {
  readonly tone = input<StatusTone>('neutral');
}

/**
 * The flush-inline-start rail on a list row. Uses `inset-inline-start`, so it
 * lands on the correct edge in both LTR and RTL without a second stylesheet.
 */
@Component({
  selector: 'fe-status-rail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="rail" [class]="'rail rail--' + tone()" aria-hidden="true"></span>`,
  styles: [
    `
      .rail {
        position: absolute;
        inset-block: 0;
        inset-inline-start: 0;
        inline-size: var(--fe-rail-width);
      }
      .rail--human {
        background: var(--pri);
      }
      .rail--ai {
        background: var(--blue);
      }
      .rail--escalated {
        background: var(--warn);
      }
      .rail--ok {
        background: var(--ok);
      }
      .rail--warn {
        background: var(--warn);
      }
      .rail--bad {
        background: var(--bad);
      }
      .rail--neutral {
        background: var(--line);
      }
    `,
  ],
})
export class StatusRailComponent {
  readonly tone = input<StatusTone>('neutral');
}
