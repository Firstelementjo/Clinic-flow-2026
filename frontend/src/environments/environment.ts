/**
 * Runtime configuration.
 *
 * Three values, all of them non-secret by construction:
 *
 * - `apiBaseUrl` — empty means same-origin, which is how the SPA is served in
 *   every real deployment: the reverse proxy puts `/api` and the app behind one
 *   origin, so the session cookie is first-party and CORS never enters into it.
 *   A cross-origin base URL is supported for local development against the API
 *   directly, where the CORS allowlist has to name the dev origin explicitly.
 * - `csrfCookieName` / `csrfHeaderName` — the cookie/header pair. They are
 *   configuration rather than literals because the backend makes both
 *   configurable (`SecurityProperties.csrfCookieName` / `csrfHeaderName`), and
 *   the session response *tells* the client the header name, so a mismatch is
 *   corrected from the wire rather than from a redeploy.
 *
 * There is no token, no client secret, and nothing an XSS payload could exfiltrate
 * to impersonate the user: the session is an HttpOnly cookie the browser attaches
 * on its own.
 */
export interface AppEnvironment {
  readonly production: boolean;
  /** Empty string means same-origin. */
  readonly apiBaseUrl: string;
  readonly csrfCookieName: string;
  readonly csrfHeaderName: string;
  /** Realtime reconnect ceiling, in milliseconds. */
  readonly realtimeMaxBackoffMs: number;
}

export const environment: AppEnvironment = {
  production: false,
  apiBaseUrl: '',
  csrfCookieName: 'XSRF-TOKEN',
  csrfHeaderName: 'X-XSRF-TOKEN',
  realtimeMaxBackoffMs: 30_000,
};

/**
 * Read the overrides an operator can set without a rebuild. Kept to a fixed,
 * non-secret allow-list: an arbitrary URL from `window` would be a
 * confused-deputy primitive, and a token here would be a credential in the
 * bundle.
 */
export function readEnvironment(win: Window | undefined): AppEnvironment {
  if (!win) return environment;
  const override = win.__FIRSTELEMENT_CONFIG__;
  if (!override) return environment;
  return {
    production: environment.production,
    apiBaseUrl: typeof override.apiBaseUrl === 'string' ? override.apiBaseUrl : environment.apiBaseUrl,
    csrfCookieName:
      typeof override.csrfCookieName === 'string' ? override.csrfCookieName : environment.csrfCookieName,
    csrfHeaderName:
      typeof override.csrfHeaderName === 'string' ? override.csrfHeaderName : environment.csrfHeaderName,
    realtimeMaxBackoffMs:
      typeof override.realtimeMaxBackoffMs === 'number' && override.realtimeMaxBackoffMs > 0
        ? override.realtimeMaxBackoffMs
        : environment.realtimeMaxBackoffMs,
  };
}

declare global {
  interface Window {
    __FIRSTELEMENT_CONFIG__?: Partial<{
      apiBaseUrl: string;
      csrfCookieName: string;
      csrfHeaderName: string;
      realtimeMaxBackoffMs: number;
    }>;
  }
}
