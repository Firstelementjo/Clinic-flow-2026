import { Pipe, inject } from '@angular/core';
import { type PipeTransform } from '@angular/core';
import { I18nService } from './i18n.service';
import type { Locale, TranslationParams } from './i18n.types';

/**
 * `{{ 'shell.nav.dashboard' | t }}`
 *
 * Impure, but memoised, and the reason is worth recording because "make the pipe
 * impure" is normally advice that costs a dictionary walk on every change
 * detection cycle:
 *
 * A **pure** pipe is memoised on its arguments. `{{ 'shell.nav.dashboard' | t }}`
 * passes the same key in Arabic and in English, so a pure pipe returns the
 * cached Arabic string after a locale switch and the screen keeps rendering the
 * old language. That is the bug this shape avoids.
 *
 * Reading the locale *signal* inside the pipe does not fix it: the dependency is
 * registered, but a pure pipe whose inputs did not change is not re-invoked, so
 * the register never gets a chance to matter.
 *
 * So: impure, and the result is cached against `(locale, key, params)` so the
 * common case — change detection with no locale change — is one signal read and
 * one string compare. The dictionary is only walked when one of those three
 * actually differs.
 */
@Pipe({ name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  private cachedLocale: Locale | null = null;
  private cachedKey = '';
  private cachedParams = '';
  private cachedText = '';

  transform(key: string, params?: TranslationParams): string {
    const locale = this.i18n.locale();
    const serialisedParams = params ? JSON.stringify(params) : '';
    if (locale === this.cachedLocale && key === this.cachedKey && serialisedParams === this.cachedParams) {
      return this.cachedText;
    }
    this.cachedLocale = locale;
    this.cachedKey = key;
    this.cachedParams = serialisedParams;
    this.cachedText = this.i18n.t(key, params);
    return this.cachedText;
  }
}
