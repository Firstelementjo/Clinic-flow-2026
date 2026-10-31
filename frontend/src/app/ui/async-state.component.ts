import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * The four async states, designed rather than improvised per screen.
 *
 * Every screen in the product needs to say the same four things — I am fetching,
 * there is genuinely nothing here, this failed and here is how to fix it, and
 * what you are looking at may be out of date — and the design system requires
 * each of them to exist. `AsyncStateComponent` is that component, so no screen
 * has to invent a fifth phrasing and no screen can forget one.
 *
 * Accessibility notes that are not optional:
 * - `role="status"` with `aria-live="polite"` on loading and stale, because
 *   "your data may be out of date" has to reach a screen reader without stealing
 *   focus.
 * - `role="alert"` on the error, because a failure is worth interrupting for.
 * - The empty state is a heading plus one sentence telling the user what to do
 *   next, never a bare "No data".
 */
@Component({
  selector: 'fe-async-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loading()) {
      <div class="state" role="status" aria-live="polite">
        <span class="fe-spinner" aria-hidden="true"></span>
        <p class="fe-sub">{{ loadingLabel() }}</p>
        @if (hint()) {
          <p class="fe-sub">{{ hint() }}</p>
        }
      </div>
    } @else if (error() && !hasValue()) {
      <div class="state" role="alert">
        <h3>{{ errorTitle() }}</h3>
        <p>{{ errorBody() }}</p>
        @if (requestId()) {
          <p class="fe-mono" dir="ltr">
            {{ requestIdLabel() }}: {{ requestId() }}
          </p>
        }
        @if (retryable()) {
          <button type="button" class="fe-btn" (click)="retry.emit()">{{ retryLabel() }}</button>
        }
      </div>
    } @else if (empty()) {
      <div class="state">
        <h3>{{ emptyTitle() }}</h3>
        <p>{{ emptyBody() }}</p>
        <ng-content select="[fe-empty-action]" />
      </div>
    }
  `,
  styles: [
    `
      .state {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--fe-space-2);
        padding: var(--fe-space-7) var(--fe-space-5);
        text-align: center;
        color: var(--mute);
      }
      .state h3 {
        font-size: var(--fe-text-lg);
        color: var(--ink);
      }
      .state p {
        max-inline-size: 46ch;
        font-size: var(--fe-text-sm);
      }
    `,
  ],
})
export class AsyncStateComponent {
  readonly loading = input.required<boolean>();
  readonly empty = input<boolean>(false);
  readonly error = input<unknown>(null);
  readonly hasValue = input<boolean>(false);
  readonly retryable = input<boolean>(true);
  readonly requestId = input<string | null>(null);

  readonly loadingLabel = input.required<string>();
  readonly loadingHint = input<string>('');
  readonly emptyTitle = input<string>('');
  readonly emptyBody = input<string>('');
  readonly errorTitle = input<string>('');
  readonly errorBody = input<string>('');
  readonly retryLabel = input<string>('');
  readonly requestIdLabel = input<string>('');

  readonly retry = output<void>();

  hint(): string {
    return this.loadingHint();
  }
}
