/**
 * The translation layer.
 *
 * Arabic is the authoring language and English is the derived translation, the
 * opposite of the usual arrangement and the point of the product (plan §1,
 * PROJECT_REQUIREMENTS §1 "Arabic-first"). Every user-facing string lives here
 * or in a namespaced dictionary that imports from here; a literal in a
 * template is a defect and `no-hardcoded-copy.spec.ts` fails the build on one.
 *
 * ADR-0004 asks for a runtime i18n layer with lazy namespaces, interpolation and
 * locale switching without a reload. Transloco would satisfy that, and this
 * does too, for three reasons worth recording: the dictionary is small enough
 * that a second runtime would be more machinery than the product needs; the
 * namespace files can be lazy-loaded per route with a dynamic import, which is
 * the property ADR-0004 actually asks for; and owning the interpolation means
 * the "Arabic first" authoring order is enforced by the type system rather than
 * by convention.
 */

export type Locale = 'ar' | 'en';
export type Direction = 'rtl' | 'ltr';

export const LOCALES: readonly Locale[] = ['ar', 'en'];
export const DEFAULT_LOCALE: Locale = 'ar';

/** Interpolation values. `{{name}}` in a message is replaced by `name`. */
export type TranslationParams = Readonly<Record<string, string | number>>;

/** One namespace: a flat `key -> { ar, en }` dictionary. */
export type Namespace = Readonly<Record<string, Readonly<{ ar: string; en: string }>>>;

/** A dictionary merged across namespaces, addressed as `namespace.key`. */
export type Dictionary = Readonly<Record<string, Readonly<{ ar: string; en: string }>>>;

export function directionOf(locale: Locale): Direction {
  return locale === 'ar' ? 'rtl' : 'ltr';
}

/**
 * The Intl locale tag used for every number, date and currency.
 *
 * `-u-nu-latn` is what holds Western digits (0–9) in Arabic: these are
 * operational figures compared across rows and against KPI cards, and Gulf
 * business software overwhelmingly sets them that way
 * (`docs/design-system.md` §Type). English uses `en-GB` for the same reason the
 * legacy client did — a fixed locale, not a guess from the browser.
 */
export function intlLocale(locale: Locale): string {
  return locale === 'ar' ? 'ar-u-nu-latn' : 'en-GB';
}

const PLACEHOLDER = /\{\{\s*([\w.]+)\s*\}\}/g;

/**
 * Interpolation is deliberately tiny: `{{token}}` only, no plural rules and no
 * nested expressions. Plural and gender agreement in Arabic is carried by the
 * authored string, which is why the `ar` column is written by a human rather
 * than derived from the English.
 */
export function interpolate(template: string, params?: TranslationParams): string {
  if (!params) return template;
  return template.replace(PLACEHOLDER, (match, token: string) => {
    const value = params[token];
    return value === undefined ? match : String(value);
  });
}

export interface LookupResult {
  /** The resolved, interpolated string. */
  text: string;
  /**
   * True when the key is absent from every loaded namespace. The UI shows a
   * visible marker in development rather than rendering an empty element,
   * because a blank label is indistinguishable from a rendering bug.
   */
  missing: boolean;
  /** The locale actually used, which differs from the active one only for a missing key. */
  resolvedLocale: Locale;
}

/**
 * Merges namespaces and resolves a key.
 *
 * Resolution order is deliberately "active locale, then the authoring locale,
 * then the key itself". A missing Arabic string must still render something
 * readable in an Arabic build rather than an empty box, and an empty box in a
 * mostly-Arabic screen is worse than an English word.
 */
export function lookup(
  dictionary: Dictionary,
  key: string,
  locale: Locale,
  params?: TranslationParams,
): LookupResult {
  const entry = dictionary[key];
  if (!entry) {
    return { text: key, missing: true, resolvedLocale: locale };
  }
  const primary = entry[locale];
  if (primary !== undefined && primary !== '') {
    return { text: interpolate(primary, params), missing: false, resolvedLocale: locale };
  }
  const fallback = entry[DEFAULT_LOCALE];
  if (fallback !== undefined && fallback !== '') {
    return {
      text: interpolate(fallback, params),
      missing: true,
      resolvedLocale: DEFAULT_LOCALE,
    };
  }
  return { text: key, missing: true, resolvedLocale: locale };
}

/**
 * The keys every namespace must define, derived from the union of its own
 * namespaces. `no-hardcoded-copy.spec.ts` uses this to prove Arabic and English
 * are both present for every key: an English-only string in an Arabic build is
 * a defect, not a fallback.
 */
export function keysOf(namespaces: readonly Namespace[]): string[] {
  const keys = new Set<string>();
  for (const namespace of namespaces) {
    for (const key of Object.keys(namespace)) keys.add(key);
  }
  return [...keys].sort();
}

export function mergeNamespaces(
  namespaces: Readonly<Record<string, Namespace>>,
): Dictionary {
  const merged: Record<string, Readonly<{ ar: string; en: string }>> = {};
  for (const [namespaceName, namespace] of Object.entries(namespaces)) {
    for (const [key, value] of Object.entries(namespace)) {
      merged[`${namespaceName}.${key}`] = value;
    }
  }
  return merged;
}
