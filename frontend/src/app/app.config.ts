/**
 * The providers.
 *
 * Interceptor order matters and is deliberate:
 *
 * 1. `correlationInterceptor` runs first so every request carries an id, including
 *    the ones that fail before anything else looks at them.
 * 2. `errorInterceptor` normalises the failure, applies the session-expiry and
 *    CSRF-retry remedies, and rethrows.
 * 3. `csrfInterceptor` attaches the cookie/header pair to unsafe methods.
 *
 * Angular runs interceptors in registration order for the request and in reverse
 * for the response, so this ordering means the error interceptor sees the
 * response of a request that already had its CSRF header.
 */
import {
  ApplicationConfig,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
  inject,
} from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { SessionService } from './core/auth/session.service';
import { RealtimeService } from './core/realtime/realtime.service';
import { ThemeService } from './core/theme/theme.service';
import { CsrfBootstrapper } from './core/api/csrf.interceptor';
import { correlationInterceptor } from './core/api/correlation.interceptor';
import { csrfInterceptor } from './core/api/csrf.interceptor';
import { errorInterceptor } from './core/api/error.interceptor';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
    ),
    provideHttpClient(
      withFetch(),
      withInterceptors([correlationInterceptor, errorInterceptor, csrfInterceptor]),
    ),
    provideAppInitializer(() => {
      // The core namespaces are registered by `I18nService` itself, because they
      // are needed on every page rather than by this one.

      // Touch the theme service so `data-theme` is on <html> before the first
      // paint rather than after a change detection cycle.
      inject(ThemeService);

      // Resolve the session and arm the CSRF token before the router's first
      // navigation, so a hard refresh on /app/dashboard does not render a shell
      // that fills with 401s and then corrects itself.
      const sessions = inject(SessionService);
      const csrf = inject(CsrfBootstrapper);
      const realtime = inject(RealtimeService);
      return (async () => {
        await csrf.ensure().catch(() => undefined);
        try {
          const authenticated = await sessions.bootstrap();
          if (authenticated) realtime.start();
        } catch {
          // A 5xx or a dead network is not an anonymous session; the guard will
          // surface it on the route that needs it rather than bouncing to login.
        }
      })();
    }),
  ],
};

