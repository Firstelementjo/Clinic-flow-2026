/**
 * The seven clinic routes the backend has no read model for.
 *
 * ## Why ONE component and not seven
 *
 * `plan.md` §15 names fifteen clinic routes and seven of them have no route behind
 * them. Those seven differ only in three strings: a heading, a sentence, and the path
 * that does not exist. Seven components differing in three constants is duplication
 * with a `selector` attached, and duplication here is the exact failure mode Phase-11
 * hard rule 8 is about: the second copy is the one that drifts.
 *
 * So the route table carries the three strings as route `data`, and this component
 * renders `fe-not-available` — the existing component, not a parallel one — from
 * whatever the route says. One component, one spec, and the spec walks all seven
 * configurations rather than assuming they agree.
 *
 * ## What the route data must carry, and why it is `data` and not a lookup table here
 *
 * The missing route is the single most important thing on these screens: it is what
 * turns "this is missing" into something an operator can file. Putting it in
 * `app.routes.ts` means there is exactly one place that names an absent path, and the
 * routing spec reads the same table the router does. A lookup keyed by a component name
 * would be a second list that could disagree with the routes without anything noticing.
 *
 * ## No numbers, ever
 *
 * The rendered text of these screens is a heading, a sentence, a label and a route
 * identifier. The digits inside `GET /api/v1/clinic/invoices` are part of a path, not a
 * figure — a route is code, and code does not flip direction with the prose around it,
 * which is why `NotAvailableComponent` already renders it `fe-mono dir="ltr"`.
 *
 * Nothing here fetches. A screen that has no read model cannot issue a request, and a
 * request to a route that does not exist would only ever answer 404.
 */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { NotAvailableComponent } from '../../ui/not-available.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { clinicStrings } from './clinic.strings';

@Component({
  selector: 'fe-clinic-gap-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NotAvailableComponent, PageHeaderComponent, RouterLink, TranslatePipe],
  template: `
    <div class="fe-page" data-testid="clinic-gap">
      <fe-page-header [title]="title">
        <a class="fe-btn fe-btn--ghost" routerLink="/app/clinic">{{ 'clinic.gaps.back' | t }}</a>
      </fe-page-header>

      <fe-not-available
        [title]="'state.notAvailable.title'"
        [body]="body"
        [missing]="missing"
        headingId="clinic-gap-heading"
      />
    </div>
  `,
})
export class ClinicGapComponent {
  private readonly route = inject(ActivatedRoute);

  /** i18n key of the page heading, declared by the route. */
  protected readonly title: string;
  /** i18n key of the explanation, declared by the route. */
  protected readonly body: string;
  /**
   * The absent route, verbatim.
   *
   * `null` rather than an invented value when a route forgets to declare one: a screen
   * that rendered "Missing route: unknown" would be filing a bug report of its own.
   */
  protected readonly missing: string | null;

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });

    const data = this.route.snapshot.data as Record<string, unknown>;
    this.title = typeof data['title'] === 'string' ? data['title'] : 'state.notAvailable.title';
    this.body = typeof data['body'] === 'string' ? data['body'] : 'state.notAvailable.body';
    this.missing = typeof data['missing'] === 'string' ? data['missing'] : null;
  }
}
