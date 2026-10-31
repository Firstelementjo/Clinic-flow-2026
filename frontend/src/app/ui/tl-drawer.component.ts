import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
} from '@angular/core';

/**
 * The right-hand slide-over used by the Create/Edit screens.
 *
 * <p>It owns three things a page should not repeat: the backdrop, the scroll lock,
 * and Escape. Everything inside the panel is projected, so the same shell serves
 * the clinic, service, doctor and patient drawers without a variant per screen.
 *
 * <p>The footer is a named slot so a form's Save button can sit outside the
 * scrolling body and stay visible while the operator edits a long record. The
 * button still submits the form by id (`form="..."`), which is why the footer is a
 * sibling of the body rather than nested inside the form.
 */
@Component({
  selector: 'fe-tl-drawer',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:keydown.escape)': 'onEscape()',
  },
  template: `
    @if (open()) {
      <div class="tl-drawer-backdrop" (click)="onBackdrop()">
        <aside
          class="tl-drawer"
          role="dialog"
          aria-modal="true"
          [attr.aria-label]="title()"
          (click)="$event.stopPropagation()"
        >
          <header class="tl-drawer-head">
            <div class="tl-drawer-heading">
              <h2>{{ title() }}</h2>
              @if (subtitle()) {
                <p>{{ subtitle() }}</p>
              }
            </div>
            <button
              type="button"
              class="tl-drawer-close"
              [attr.aria-label]="'Close'"
              (click)="closed.emit()"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </header>

          <div class="tl-drawer-body">
            <ng-content />
          </div>

          <footer class="tl-drawer-foot">
            <ng-content select="[drawerFooter]" />
          </footer>
        </aside>
      </div>
    }
  `,
})
export class TlDrawerComponent {
  readonly open = input(false);
  readonly title = input('');
  readonly subtitle = input<string | null>(null);
  readonly closed = output<void>();

  private readonly destroyRef = inject(DestroyRef);
  private previousOverflow: string | null = null;

  constructor() {
    effect(() => {
      const isOpen = this.open();
      if (isOpen) {
        this.previousOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
      } else {
        this.restoreScroll();
      }
    });

    this.destroyRef.onDestroy(() => this.restoreScroll());
  }

  protected onBackdrop(): void {
    this.closed.emit();
  }

  protected onEscape(): void {
    if (this.open()) {
      this.closed.emit();
    }
  }

  private restoreScroll(): void {
    if (this.previousOverflow !== null) {
      document.body.style.overflow = this.previousOverflow;
      this.previousOverflow = null;
    }
  }
}
