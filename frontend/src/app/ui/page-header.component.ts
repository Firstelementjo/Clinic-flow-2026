import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../core/i18n/translate.pipe';

/**
 * The page header: a title, on-demand description behind the (?) trigger, and actions on the inline-end
 * edge.
 */
@Component({
  selector: 'fe-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslatePipe],
  template: `
    <header class="fe-page-header">
      <div class="fe-page-header__text">
        <div class="fe-page-header__title-row">
          <h2 class="fe-page-header__title">{{ title() | t }}</h2>
          @if (description()) {
            <div class="tl-info-wrap">
              <button
                type="button"
                class="tl-info-trigger"
                [class.is-active]="showInfo()"
                (click)="showInfo.set(!showInfo())"
                title="Description details"
              >?</button>
              @if (showInfo()) {
                <div class="tl-info-popover">
                  {{ description()! | t }}
                </div>
              }
            </div>
          }
        </div>
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
        align-items: center;
        justify-content: space-between;
        gap: var(--fe-space-4);
        flex-wrap: wrap;
        margin-block-end: var(--fe-space-4);
        position: relative;
        z-index: 50;
      }
      .fe-page-header__text {
        min-inline-size: 0;
      }
      .fe-page-header__title-row {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .fe-page-header__title {
        font-size: 22px;
        font-weight: 700;
        color: var(--ink);
        letter-spacing: -0.025em;
        margin: 0;
      }
      .fe-page-header__actions {
        display: flex;
        align-items: center;
        gap: 8px;
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

  protected readonly showInfo = signal(false);
}
