import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { RouterLink } from '@angular/router';

/**
 * The "no such page" screen.
 *
 * It exists because the frozen client's wildcard silently redirected to the
 * dashboard or the sign-in screen. That is wrong for a mistyped deep link: the
 * operator believes they are looking at a page and are not. Saying so, and
 * offering the two places they could have meant, is the honest answer.
 */
@Component({
  selector: 'fe-not-found-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslatePipe],
  template: `
    <div class="fe-page notfound" data-testid="not-found">
      <div class="fe-card">
        <h2>{{ 'state.notFound.title' | t }}</h2>
        <p class="fe-sub">{{ 'state.notFound.body' | t }}</p>
        <a class="fe-btn fe-btn--primary back" routerLink="/app/dashboard">
          {{ 'state.backToDashboard' | t }}
        </a>
      </div>
    </div>
  `,
  styles: [
    `
      .notfound .fe-card {
        max-inline-size: 34rem;
      }
      h2 {
        font-size: 24px;
        font-weight: 600;
      }
      .back {
        margin-block-start: var(--fe-space-4);
        text-decoration: none;
      }
    `,
  ],
})
export class NotFoundComponent {}
