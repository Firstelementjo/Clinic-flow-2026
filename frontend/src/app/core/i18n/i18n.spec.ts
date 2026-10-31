/**
 * The i18n layer: Arabic first, runtime switching, direction in one place.
 */
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { I18nService } from './i18n.service';
import {
  DEFAULT_LOCALE,
  directionOf,
  interpolate,
  intlLocale,
  lookup,
  mergeNamespaces,
} from './i18n.types';
import { shellStrings } from './strings/core';
import { stateStrings } from './strings/state';
import { dashboardStrings } from '../../features/dashboard/dashboard.strings';
import { loginStrings } from '../../features/login/login.strings';
import { settingsStrings } from '../../features/settings/settings.strings';

import { adminStrings } from '../../features/admin/admin.strings';
import { clinicStrings } from '../../features/clinic/clinic.strings';
import { clinicAdminStrings } from '../../features/clinic/clinic-admin.strings';
import { financeStrings } from '../../features/finance/finance.strings';
import { authoringStrings } from '../../features/exports-authoring/authoring.strings';
import { inboxStrings } from '../../features/inbox/inbox.strings';
import { aiDraftStrings } from '../../features/inbox/ai-draft.strings';
import { knowledgeStrings } from '../../features/knowledge/knowledge.strings';
import { peopleStrings } from '../../features/people/people.strings';
import { teamStrings } from '../../features/team/team.strings';

const ALL_NAMESPACES = {
  shell: shellStrings,
  state: stateStrings,
  dashboard: dashboardStrings,
  login: loginStrings,
  settings: settingsStrings,
  inbox: inboxStrings,
  // The pending-AI-draft surface, registered by its own component. Listed here for
  // the same reason `authoring` is: a namespace absent from this table has its
  // copy go unchecked, and the words on this card are the ones that keep a draft
  // from reading as a message the customer already received.
  aiDraft: aiDraftStrings,
  people: peopleStrings,
  team: teamStrings,
  knowledge: knowledgeStrings,
  admin: adminStrings,
  finance: financeStrings,
  clinic: clinicStrings,
  // The clinic master-data screens register this namespace from inside their own
  // components, like `authoring`; listed here so the copy coverage assertions see it.
  clinicAdmin: clinicAdminStrings,
  // Authoring registers under its own namespace from inside `AuthoringComponent`, and is
  // listed here so the coverage assertions can see it. A namespace absent from this table
  // would have its copy go unchecked — every key would look unused and none would be proven
  // to exist, which is how a screen ends up rendering a key name to an operator.
  authoring: authoringStrings,
};

describe('direction and Intl locale', () => {
  it('treats Arabic as RTL and English as LTR', () => {
    expect(directionOf('ar')).toBe('rtl');
    expect(directionOf('en')).toBe('ltr');
  });

  it('defaults to Arabic, the authoring language', () => {
    expect(DEFAULT_LOCALE).toBe('ar');
  });

  it('holds Western digits in Arabic with the -u-nu-latn extension', () => {
    // These are operational figures compared by position against a KPI card
    // (docs/design-system.md). Arabic-Indic digits would break that.
    expect(intlLocale('ar')).toBe('ar-u-nu-latn');
    expect(intlLocale('en')).toBe('en-GB');
  });
});

describe('interpolation', () => {
  it('substitutes named tokens', () => {
    expect(interpolate('{{count}} items', { count: 3 })).toBe('3 items');
  });

  it('leaves an unknown token visible rather than printing undefined', () => {
    expect(interpolate('{{missing}}', {})).toBe('{{missing}}');
  });

  it('tolerates whitespace inside the braces', () => {
    expect(interpolate('{{ name }}', { name: 'x' })).toBe('x');
  });
});

describe('lookup', () => {
  const dictionary = mergeNamespaces({ demo: { hello: { ar: 'مرحبا', en: 'Hello' } } });

  it('resolves in the active locale', () => {
    expect(lookup(dictionary, 'demo.hello', 'ar').text).toBe('مرحبا');
    expect(lookup(dictionary, 'demo.hello', 'en').text).toBe('Hello');
  });

  it('returns the key for an unknown translation so a gap is visible', () => {
    const result = lookup(dictionary, 'demo.nope', 'en');
    expect(result.text).toBe('demo.nope');
    expect(result.missing).toBe(true);
  });

  it('falls back to Arabic and reports the fallback rather than rendering nothing', () => {
    const partial = mergeNamespaces({ demo: { half: { ar: 'نصف', en: '' } } });
    const result = lookup(partial, 'demo.half', 'en');
    expect(result.text).toBe('نصف');
    expect(result.resolvedLocale).toBe('ar');
    expect(result.missing).toBe(true);
  });

  it('interpolates params in the resolved string', () => {
    const withParams = mergeNamespaces({
      demo: { n: { ar: '{{count}} عنصر', en: '{{count}} item(s)' } },
    });
    expect(lookup(withParams, 'demo.n', 'ar', { count: 2 }).text).toBe('2 عنصر');
  });
});

describe('I18nService', () => {
  let service: I18nService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    service = TestBed.inject(I18nService);
    service.register(ALL_NAMESPACES);
  });

  it('starts in Arabic with RTL on the document element', () => {
    expect(service.locale()).toBe('ar');
    expect(service.direction()).toBe('rtl');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
  });

  it('switches without a reload and flips the document element both ways', () => {
    service.setLocale('en');
    expect(service.locale()).toBe('en');
    expect(service.direction()).toBe('ltr');
    expect(document.documentElement.dir).toBe('ltr');
    expect(document.documentElement.lang).toBe('en');

    service.toggleLocale();
    expect(service.locale()).toBe('ar');
    expect(document.documentElement.dir).toBe('rtl');
  });

  it('remembers the choice across a new service instance', () => {
    service.setLocale('en');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const revived = TestBed.inject(I18nService);
    expect(revived.locale()).toBe('en');
  });

  it('registers a namespace registered after the first render', () => {
    service.register({ late: { hi: { ar: 'مرحبا', en: 'Hi' } } });
    expect(service.has('late.hi')).toBe(true);
    expect(service.t('late.hi')).toBe('مرحبا');
  });

  it('interpolates through t()', () => {
    expect(service.t('state.count', { count: 7 })).toContain('7');
  });
});

describe('copy coverage', () => {
  const dictionary = mergeNamespaces(ALL_NAMESPACES);

  it('gives every key an Arabic string', () => {
    const missing = Object.entries(dictionary)
      .filter(([, entry]) => entry.ar.trim() === '')
      .map(([key]) => key);
    expect(missing, 'keys with no Arabic').toEqual([]);
  });

  it('gives every key an English string', () => {
    const missing = Object.entries(dictionary)
      .filter(([, entry]) => entry.en.trim() === '')
      .map(([key]) => key);
    expect(missing, 'keys with no English').toEqual([]);
  });

  it('has no key where both languages are the same string', () => {
    // Identical values mean one of the two was never written. The legitimate
    // exceptions are values that are genuinely locale-independent: a two-letter
    // monogram and an example email address.
    const allowed = new Set(['shell.app.short', 'login.emailPlaceholder']);
    const identical = Object.entries(dictionary)
      .filter(([key, entry]) => entry.ar === entry.en && !allowed.has(key))
      .map(([key]) => key);
    expect(identical, 'keys where ar === en').toEqual([]);
  });

  it('resolves every literal key that a template or a component asks for', () => {
    // Walks the shipped sources and asserts each *literal* key resolves. This is the
    // guarantee that "no hard-coded user-facing strings" is enforced rather than
    // asserted: a screen that invents a key fails the build instead of rendering
    // the key itself.
    const keys = collectLiteralKeys();
    expect(keys.length).toBeGreaterThan(50);
    const missing = keys.filter((key) => !(key in dictionary));
    expect(missing, 'keys with no translation').toEqual([]);
  });

  it('translates every expansion of every runtime-assembled key family', () => {
    // A key built at runtime — `shell.status.` + state, `shell.channel.` + channel,
    // `dashboard.queue.reason.` + reason — is only checkable as a *family*: the
    // suffix is a wire value the client cannot enumerate at build time. Listing the
    // expected expansions here is what stops a screen rendering `shell.status.OPEN`
    // as a raw key, which is exactly what happened before this test existed.
    const families: Record<string, readonly string[]> = {
      'shell.role.': ['ADMIN', 'EMPLOYEE', 'CLINIC_STAFF'],
      'shell.status.': ['OPEN', 'PENDING', 'RESOLVED', 'CLOSED'],
      'shell.channel.': ['MOCK', 'WHATSAPP', 'INSTAGRAM', 'MESSENGER', 'TELEGRAM', 'WEB', 'EMAIL'],
      'dashboard.queue.reason.': ['NEVER_ASSIGNED', 'RELEASED', 'REOPENED'],
    };

    const prefixes = collectConcatenatedPrefixes();
    for (const prefix of prefixes) {
      expect(Object.keys(families), `${prefix} is assembled at runtime but not declared here`).toContain(
        prefix,
      );
    }

    const missing: string[] = [];
    for (const [prefix, expansions] of Object.entries(families)) {
      if (!prefixes.includes(prefix)) continue;
      for (const expansion of expansions) {
        if (!(prefix + expansion in dictionary)) missing.push(prefix + expansion);
      }
    }
    expect(missing, 'runtime key expansions with no translation').toEqual([]);
  });
});

/** The source roots whose key literals are checked. */
function sourceRoots(): string[] {
  return [
    joinPath(__dirname, '..', '..', 'shell'),
    joinPath(__dirname, '..', '..', 'features'),
    joinPath(__dirname, '..', '..', 'ui'),
  ];
}

function sourceFiles(): string[] {
  return sourceRoots().flatMap((root) => walk(root)).filter((file) => /\.(html|ts)$/.test(file));
}

/**
 * Every *literal* translation key a template or component asks for.
 *
 * Two shapes, because the app uses both:
 *
 * - `{{ 'shell.nav.dashboard' | t }}` — the pipe;
 * - `this.i18n.t('login.title')` — the service, used where a template expression
 *   would be unreadable (inside a computed, or as an interpolation argument).
 *
 * Keys assembled at runtime are *not* collected here; they are a family, and
 * `collectConcatenatedPrefixes` plus the declared expansions check them.
 */
function collectLiteralKeys(): string[] {
  const found = new Set<string>();
  for (const file of sourceFiles()) {
    const source = readText(file);
    for (const match of source.matchAll(/'([a-zA-Z]+(?:\.[a-zA-Z0-9_]+)+)'\s*\|\s*t\b/g)) {
      found.add(match[1]!);
    }
    for (const match of source.matchAll(/\.t\(\s*'([a-zA-Z]+(?:\.[a-zA-Z0-9_]+)+)'/g)) {
      found.add(match[1]!);
    }
  }
  return [...found];
}

/**
 * The key *prefixes* assembled at runtime.
 *
 * A prefix with no key under it is a whole family of missing translations — the
 * failure that produced raw keys like `shell.status.OPEN` on screen, and the one
 * the per-key check cannot see because the suffix is only known at runtime.
 */
function collectConcatenatedPrefixes(): string[] {
  const prefixes = new Set<string>();
  for (const file of sourceFiles()) {
    const source = readText(file);
    for (const match of source.matchAll(/(?:\.t\(|\|\s*t\s*:)\s*'([a-zA-Z]+(?:\.[a-zA-Z0-9_]+)*\.)'\s*\+/g)) {
      prefixes.add(match[1]!);
    }
  }
  return [...prefixes];
}

// Small filesystem helpers, kept local so the suite has no Node-only import in
// the application code.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join as joinPath } from 'node:path';

function walk(directory: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(directory)) {
    const full = joinPath(directory, entry);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}

function readText(file: string): string {
  return readFileSync(file, 'utf8');
}
