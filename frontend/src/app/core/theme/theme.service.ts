import { DOCUMENT, isPlatformBrowser } from '@angular/common';
import { Injectable, PLATFORM_ID, computed, inject, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

const THEME_STORAGE_KEY = 'fe.theme.v1';
const DARK_QUERY = '(prefers-color-scheme: dark)';

/**
 * Theme resolution order (docs/migration-AGENT.md §4.2): the user's saved
 * choice, then the system preference. Dark mode is not an inversion of the
 * light theme — `tokens.css` carries its own dark values.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));

  private readonly current = signal<Theme>(this.initialTheme());

  readonly theme = this.current.asReadonly();
  readonly isDark = computed(() => this.current() === 'dark');

  constructor() {
    this.apply();
  }

  setTheme(theme: Theme): void {
    if (theme === this.current()) return;
    this.current.set(theme);
    this.persist(theme);
    this.apply();
  }

  toggle(): void {
    this.setTheme(this.current() === 'dark' ? 'light' : 'dark');
  }

  private initialTheme(): Theme {
    if (!this.isBrowser) return 'light';
    try {
      const stored = this.document.defaultView?.localStorage.getItem(THEME_STORAGE_KEY);
      if (stored === 'light' || stored === 'dark') return stored;
    } catch {
      // Storage unavailable: the system preference still answers the question.
    }
    return this.document.defaultView?.matchMedia?.(DARK_QUERY).matches ? 'dark' : 'light';
  }

  private persist(theme: Theme): void {
    try {
      this.document.defaultView?.localStorage.setItem(THEME_STORAGE_KEY, theme);
    } catch {
      // A preference that cannot be stored still applies for this session.
    }
  }

  private apply(): void {
    this.document.documentElement.dataset['theme'] = this.current();
  }
}
