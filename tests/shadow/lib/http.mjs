/* global URL, fetch */
/**
 * A minimal cookie-aware HTTP client for the shadow-parity harness.
 *
 * WHY NOT A TEST FRAMEWORK. The point of this harness is to drive two
 * *real* runtimes over real HTTP and record what each one actually answered.
 * A framework would add retries, fixtures and normalisation in front of the
 * request, and every one of those is a place where a disagreement can be
 * hidden instead of reported. Nothing here retries, and nothing here
 * rewrites a response.
 *
 * The only stateful part is the cookie jar, because both stacks authenticate
 * with a session cookie and a comparison that re-authenticated per request
 * would compare login behaviour instead of read behaviour.
 */

// A cookie jar is a Map keyed by name. Domain/Path scoping is deliberately
// not modelled: the harness only ever talks to one origin per client, so a
// full RFC 6265 implementation would be complexity with no behaviour to
// exercise.
export class CookieJar {
  constructor() {
    this.cookies = new Map();
  }

  /**
   * Loads a persisted jar.
   *
   * WHY THIS EXISTS. The login rate limiter allows 10 attempts per 60 s per
   * address (ADR 0013 section 5). A harness that re-authenticates every
   * principal on every capture spends its budget on its own bookkeeping and
   * then gets refused -- which is the control working and the harness being
   * non-deterministic. ADR 0013 section 5 puts the fix in the harness, not in
   * the control: the production limit is unchanged, and the harness
   * authenticates each principal ONCE per gate run instead of once per
   * capture. Cookies are never written to the capture artefact and never
   * printed; they live in the gate's temporary work directory.
   */
  static load(cookies) {
    const jar = new CookieJar();
    for (const [k, v] of Object.entries(cookies ?? {})) jar.cookies.set(k, v);
    return jar;
  }

  asObject() {
    return Object.fromEntries(this.cookies);
  }

  header() {
    return [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  }

  absorb(response) {
    const raw = typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : [];
    for (const line of raw) {
      const pair = line.split(';', 1)[0];
      const eq = pair.indexOf('=');
      if (eq > 0) this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
    }
  }
}

export class Client {
  constructor(baseUrl, savedJar) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.jar = savedJar ?? new CookieJar();
    /** The last CSRF token this client obtained. Never logged. */
    this.csrfToken = null;
    this.csrfHeaderName = 'X-XSRF-TOKEN';
    /** Where to fetch a fresh CSRF token, or null when this runtime has none. */
    this.csrfPath = null;
  }

  /** The jar as a plain object, for persisting between captures. */
  cookies() {
    return this.jar.asObject();
  }

  async ensureCsrf() {
    if (!this.csrfPath) return null;
    const probe = await this.get(this.csrfPath);
    if (probe.status !== 200 || !probe.json || !probe.json.token) {
      throw new Error(`csrf probe failed with ${probe.status}`);
    }
    this.csrfToken = probe.json.token;
    this.csrfHeaderName = probe.json.headerName || this.csrfHeaderName;
    return this.csrfToken;
  }

  url(path) {
    return new URL(path, `${this.baseUrl}/`).toString();
  }

  async request(method, path, options = {}) {
    // `connection: close` on every request. Three reasons, and the third is
    // the one that matters: undici keeps sockets alive, and a node process
    // that calls process.exit() with an idle socket open can abort inside
    // libuv on Windows. The captures issue tens of requests against a local
    // throwaway port, so keeping connections alive buys nothing measurable.
    const headers = Object.assign(
      { accept: 'application/json', connection: 'close' }, options.headers ?? {});

    // ------------------------------------------------------------------
    // A FRESH CSRF TOKEN BEFORE EVERY UNSAFE REQUEST, AND THE COOKIE HEADER
    // IS BUILT *AFTER* THAT REFRESH.
    // ------------------------------------------------------------------
    // Two harness bugs lived here, and both produced the same symptom: a 403
    // whose body read CSRF_TOKEN_INVALID on the second unsafe request of a
    // sequence, which is indistinguishable from a permission refusal.
    //
    //   1. The token was fetched once and reused. The new runtime's token is
    //      single-use.
    //   2. With the refresh added, the cookie header was still built from the
    //      jar BEFORE the refresh ran, so the request carried the NEW token in
    //      the header and the OLD token in the cookie. Double-submit compares
    //      the two.
    //
    // A parity harness that cannot tell a security control from its own bug is
    // worse than no harness, and both bugs would have been filed as "the new
    // runtime refuses to resolve a conversation" -- a finding about the
    // cutover candidate invented entirely by the measuring instrument.
    const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(method);
    if (unsafe && this.csrfPath && !options.noCsrf) await this.ensureCsrf();

    if (this.jar.cookies.size > 0) headers.cookie = this.jar.header();
    if (this.csrfToken && !options.noCsrf) headers[this.csrfHeaderName] = this.csrfToken;

    const init = { method, headers, redirect: 'manual' };
    if (options.body !== undefined) {
      headers['content-type'] = 'application/json';
      init.body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
    }

    const response = await fetch(this.url(path), init);
    this.jar.absorb(response);

    const text = await response.text();
    let json;
    let parseError = null;
    if (text.length > 0) {
      try {
        json = JSON.parse(text);
      } catch {
        parseError = 'response body is not JSON';
      }
    }

    // A CSRF refusal is the HARNESS's failure, not the runtime's decision, and
    // the two share a status code. Recording it as a 403 would put "the new
    // runtime refused this write" into an authorization finding when nothing
    // was refused -- it simply was not authenticated for the request. So it is
    // raised here, loudly, instead of being written into a capture.
    if (json && json.code === 'CSRF_TOKEN_INVALID') {
      throw new Error(
        `CSRF refusal on ${method} ${path}: the harness presented a token the runtime did not `
        + 'accept. This is a harness bug (a stale or half-refreshed token), NOT a product '
        + 'decision, and it is raised rather than recorded so it cannot be mistaken for one.');
    }

    return { status: response.status, json, text, parseError };
  }

  get(path, options) {
    return this.request('GET', path, options);
  }

  /**
   * Logs in and returns the session. `csrfFirst` is true for the Spring
   * runtime, which requires a CSRF token before an unsafe method; the legacy
   * runtime has no such route and is passed false.
   */
  async login(email, password, { csrfFirst }) {
    if (csrfFirst) {
      this.csrfPath = '/api/v1/auth/csrf';
      await this.ensureCsrf();
    }
    const response = await this.request('POST', this.loginPath, {
      body: { email, password },
    });
    if (response.status !== 200) {
      // The body of a failed login is deliberately not reproduced here: on a
      // security control it is exactly the text that must not be copied into
      // a CI log.
      throw new Error(`login for ${email} failed with ${response.status}`);
    }
    return response.json;
  }
}

/** The legacy NestJS runtime serves under /api; the Spring runtime under /api/v1. */
export const LEGACY_PREFIX = '/api';
export const SPRING_PREFIX = '/api/v1';

export function pathFor(prefix, canonicalPath) {
  return canonicalPath.startsWith(prefix) ? canonicalPath : `${prefix}${canonicalPath}`;
}