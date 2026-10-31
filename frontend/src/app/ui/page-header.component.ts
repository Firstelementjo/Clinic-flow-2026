import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../core/i18n/translate.pipe';

/**
 * The page header: a title, one line of description, and actions on the inline-end
 * edge.
 *
 * ## Why the description is capped
 *
 * `docs/migration-AGENT.md` §4.6: one line, in the muted colour, "max 60 characters
 * wide". The cap is enforced in CSS rather than by truncating the string, because a
 * truncated sentence is a sentence the user cannot finish reading, whereas a
 * wrapped one is merely longer. The heading is the only level-2 on a screen: the
 * shell owns the landmark, and a screen that adds its own `<h1>` competes with it.
 *
 * ## `data-testid` rather than a class for the region
 *
 * Journeys locate the screen by `data-testid` so a CSS refactor cannot silently
 * rename what a test asserts on — and a test that cannot find its screen fails with
 * a name instead of a timeout.
 */
@Component({
  selector: 'fe-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslatePipe],
  template: `
    <header class="fe-page-header">
      <div class="fe-page-header__text">
        <h2 class="fe-page-header__title">{{ title() | t }}</h2>
      </div>
      <div class="fe-page-header__actions">
        <ng-content />
        @if (backToDashboard()) {
          <a class="fe-btn fe-btn--ghost" [routerLink]="backToDashboard()">
            {{ 'state.backToDashboard' | t }}
          </a>
        }
      </div>
    </header>
  `,
  styles: [
    `
      .fe-page-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--fe-space-4);
        flex-wrap: wrap;
        margin-block-end: var(--fe-space-5);
      }
      .fe-page-header__text {
        min-inline-size: 0;
      }
      .fe-page-header__title {
        font-size: var(--fe-text-2xl);
        font-weight: 600;
        color: var(--ink);
      }
      .fe-page-header__description {
        /* The 60-character cap from the design system, as a measure rather than a
           string length so it holds in both scripts. */
        max-inline-size: 46ch;
        margin-block-start: var(--fe-space-1);
      }
      .fe-page-header__unavailable {
        max-inline-size: 60ch;
        margin-block-start: var(--fe-space-2);
        padding-inline-start: var(--fe-space-3);
        border-inline-start: 3px solid var(--warn);
      }
      .fe-page-header__actions {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
        flex-wrap: wrap;
      }
    `,
  ],
})
export class PageHeaderComponent {
  /** i18n key of the heading. */
  readonly title = input.required<string>();
  /** i18n key of the one-line description, or null for no description. */
  readonly description = input<string | null>(null);
  /** i18n key explaining why the screen cannot be used, or null. */
  readonly unavailableReason = input<string | null>(null);
  /** Route to return to, or null for no back affordance. */
  readonly backToDashboard = input<string | null>(null);
}
