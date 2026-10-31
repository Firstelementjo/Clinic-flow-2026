import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslatePipe } from '../core/i18n/translate.pipe';

/**
 * The explicit "there is no route for this" state.
 *
 * ## Why this exists rather than a hidden screen
 *
 * Phase-11 hard rule 8: no mock that ships. Several screens the product needs have
 * **no read model in the committed OpenAPI document**. There are three honest options
 * and only these:
 *
 * 1. omit the screen entirely, so an operator cannot tell it does not exist;
 * 2. show it with placeholder figures, which is the mock that ships;
 * 3. show it, say plainly that the server has no route, and name what is missing.
 *
 * This is the third. It is a real component with real copy in both languages, so
 * the gap is legible to an operator and reportable by one — and when the backend
 * mission adds the routes and re-promotes the contract, the component is deleted
 * rather than reconfigured, which is a change a reviewer can see.
 *
 * **Correction, phase 12.** This comment used to list *clinic* among the screens
 * with no read model. That stopped being true when the clinic routes landed, and the
 * sentence was never updated: `ClinicController` publishes seven routes and
 * `ClinicPatientController` six more, and the Angular clinic screens read all of them.
 * What remains unbuilt inside the clinic is narrower and is named route by route —
 * treatment plans, encounters, invoices, payments, clinic reports, clinic policies,
 * the clinic AI assistant, and shared clinic resources — and each of those renders
 * this component with the exact path it is waiting for. The components that still
 * have no read model at all are AI agent configuration, escalation, and employee
 * availability, skills and imports.
 *
 * ## What it must never contain
 *
 * No numbers, no sample rows, no "coming soon". `docs/migration-AGENT.md` §7.6 and
 * `PROJECT_REQUIREMENTS` FR-7.6 both require "no data" to be visually distinct from
 * zero, and a placeholder figure is worse than either.
 *
 * `role="note"` rather than `role="alert"`: this is not a failure the user caused
 * or can fix, so it must not interrupt.
 */
@Component({
  selector: 'fe-not-available',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  template: `
    <section class="not-available" role="note" [attr.aria-labelledby]="headingId()">
      <h3 class="not-available__title" [id]="headingId()">{{ title() | t }}</h3>
      <p class="not-available__body">{{ body() | t }}</p>
      @if (missing(); as routes) {
        <!--
          The route that is absent, in monospace and LTR, because it is an
          identifier rather than prose (docs/migration-AGENT.md section 4.3).
          Naming it is what turns "this is missing" into something an operator
          can file.
        -->
        <p class="fe-sub not-available__routes">
          {{ 'state.notAvailable.missingLabel' | t }}
          <span class="fe-mono" dir="ltr">{{ routes }}</span>
        </p>
      }
      <ng-content />
    </section>
  `,
  styles: [
    `
      .not-available {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
        padding: var(--fe-space-6) var(--fe-space-5);
        border: 1px dashed var(--line);
        border-radius: var(--fe-radius);
        background: var(--surf2);
      }
      .not-available__title {
        font-size: var(--fe-text-lg);
        color: var(--ink);
      }
      .not-available__body {
        max-inline-size: 62ch;
        color: var(--mute);
      }
      .not-available__routes {
        display: flex;
        align-items: baseline;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
      }
    `,
  ],
})
export class NotAvailableComponent {
  /** i18n key of the heading. */
  readonly title = input.required<string>();
  /** i18n key of the explanation. */
  readonly body = input.required<string>();
  /** The absent route, verbatim, or null when several are involved. */
  readonly missing = input<string | null>(null);
  /** Stable id so `aria-labelledby` points at this instance, not a shared one. */
  readonly headingId = input('fe-not-available-title');
}
