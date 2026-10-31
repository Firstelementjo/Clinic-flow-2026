import { Injectable, computed, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { shellStrings } from './strings/core';
import { stateStrings } from './strings/state';
import {
  DEFAULT_LOCALE,
  LOCALES,
  type Dictionary,
  type Direction,
  type Locale,
  type Namespace,
  type TranslationParams,
  directionOf,
  intlLocale,
  lookup,
  mergeNamespaces,
} from './i18n.types';

const LOCALE_STORAGE_KEY = 'fe.locale.v1';

function isLocale(value: string | null): value is Locale {
  return value === 'ar' || value === 'en';
}

/**
 * Runtime locale ownership (ADR-0004).
 *
 * Three responsibilities, kept in one place because a split is how a product
 * ends up with an RTL layout and an LTR `lang`:
 *
 * 1. the active locale, as a signal, so a switch re-renders without a reload;
 * 2. `lang` and `dir` on the document element — direction is derived from
 *    locale in exactly one place and layout mirroring happens through CSS
 *    logical properties rather than per-component conditionals;
 * 3. the merged dictionary, with namespaces registered at runtime so a lazy
 *    route owns its own copy.
 *
 * Only the locale is persisted. A CSRF token or a session identifier has no
 * business in `localStorage` at all; the session is an HttpOnly cookie.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly document = inject(DOCUMENT);

  private readonly registered = signal<Readonly<Record<string, Namespace>>>({});
  private readonly active = signal<Locale>(this.readStoredLocale());

  /** The active locale. Arabic by default: it is the authoring language. */
  readonly locale = this.active.asReadonly();
  readonly direction = computed<Direction>(() => directionOf(this.active()));
  /** The Intl tag for every number, date and currency in the UI. */
  readonly intlLocale = computed(() => intlLocale(this.active()));
  readonly available: readonly Locale[] = LOCALES;

  constructor() {
    // The core namespaces are registered here rather than by the application
    // config, because they are not route-specific: the shell, the account menu
    // and every screen's loading/empty/error copy exist on every page. Registering
    // them in one place means a component rendered on its own gets the same
    // dictionary the application does.
    this.register({ shell: shellStrings as Namespace, state: stateStrings as Namespace });
    this.applyToDocument();
  }

  /**
   * Registers one or more namespaces. A lazy route calls this when it loads, so
   * a screen owns its copy and nothing else has to know about it. Re-registering
   * the same name replaces it, which is what a re-entered lazy route does.
   */
  register(namespaces: Readonly<Record<string, Namespace>>): void {
    this.registered.update((current) => ({ ...current, ...namespaces }));
  }

  setLocale(locale: Locale): void {
    if (!isLocale(locale) || locale === this.active()) return;
    this.active.set(locale);
    this.persist(locale);
    this.applyToDocument();
  }

  toggleLocale(): void {
    this.setLocale(this.active() === 'ar' ? 'en' : 'ar');
  }

  /**
   * The translated string for `key`.
   *
   * A missing key returns the key itself rather than an empty string: an empty
   * element is indistinguishable from a rendering bug, and the key is enough
   * for a developer to find the gap. `has()` exists for the tests and the
   * coverage check.
   */
  t(key: string, params?: TranslationParams): string {
    return lookup(this.dictionary(), key, this.active(), params).text;
  }

  /** Whether the key exists in a registered namespace. */
  has(key: string): boolean {
    return key in this.dictionary();
  }

  /**
   * The merged dictionary. Public because the copy-coverage test walks it: the
   * test that proves every key has Arabic *and* English has to see the merged
   * dictionary, and it should not have to re-implement the merge.
   */
  readonly dictionary = computed<Dictionary>(() => mergeNamespaces(this.registered()));

  private readStoredLocale(): Locale {
    try {
      const stored = this.document.defaultView?.localStorage.getItem(LOCALE_STORAGE_KEY) ?? null;
      if (isLocale(stored)) return stored;
    } catch {
      // Private browsing, a blocked storage partition: fall through to Arabic
      // rather than failing to boot.
    }
    return DEFAULT_LOCALE;
  }

  private persist(locale: Locale): void {
    try {
      this.document.defaultView?.localStorage.setItem(LOCALE_STORAGE_KEY, locale);
    } catch {
      // A preference that cannot be stored is still usable for this session.
    }
  }

  private applyToDocument(): void {
    const root = this.document.documentElement;
    root.lang = this.active();
    root.dir = directionOf(this.active());
  }
}
