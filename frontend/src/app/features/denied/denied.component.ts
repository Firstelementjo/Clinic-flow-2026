/**
 * The denied screen.
 *
 * `403 ACCESS_DENIED` is the backend's answer for a permission the caller does
 * not hold, and `404 NOT_FOUND` is its answer for another tenant's object. This
 * screen renders for the first and says plainly that the *server* decided,
 * because an operator whose screen silently hides a section will assume the
 * product is broken rather than that policy applied.
 */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { TranslatePipe } from '../../core/i18n/translate.pipe';

@Component({
  selector: 'fe-denied-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslatePipe],
  template: `
    <div class="fe-page denied" data-testid="denied">
      <div class="fe-card">
        <h2>{{ 'state.denied.title' | t }}</h2>
        <p class="fe-sub">{{ 'state.denied.body' | t }}</p>
        @if (permission(); as value) {
          <p class="fe-mono permission" dir="ltr">{{ value }}</p>
        }
        <a class="fe-btn fe-btn--primary back" routerLink="/app/dashboard">
          {{ 'state.backToDashboard' | t }}
        </a>
      </div>
    </div>
  `,
  styles: [
    `
      .denied .fe-card {
        max-inline-size: 34rem;
      }
      h2 {
        font-size: 24px;
        font-weight: 600;
      }
      .permission {
        margin-block-start: var(--fe-space-3);
        color: var(--mute);
      }
      .back {
        margin-block-start: var(--fe-space-4);
        text-decoration: none;
      }
    `,
  ],
})
export class DeniedComponent {
  private readonly route = inject(ActivatedRoute);

  /** The permission the guard refused, so the message can name it. */
  protected readonly permission = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('permission'))),
    { initialValue: null },
  );
}
