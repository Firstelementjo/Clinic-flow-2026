/**
 * The typed API layer.
 *
 * The models come from the generated client (see `generated/index.ts`, produced
 * by `npm run generate:api` from `tests/contracts/openapi.json`). What is here
 * is the request plumbing: one place that knows how a call is made, so that the
 * CSRF header, the correlation id, credentialed cookies and the error contract
 * apply to every request by construction rather than by remembering.
 *
 * Why not use a generated SDK: a generated `fetch` client bypasses Angular's
 * interceptor chain, and that chain is where the CSRF header, the correlation
 * id and the error mapping live. The generated types are the part of the
 * contract that matters for correctness; the transport is ours.
 */
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../../environments/environment';
import type * as Api from './generated';

/** The stable error body every non-2xx response carries. */
export interface ApiErrorBody {
  readonly code: string;
  readonly message: string;
  readonly details?: Readonly<Record<string, unknown>>;
  readonly requestId?: string;
  readonly timestamp?: string;
  readonly retryable?: boolean;
}

/**
 * Error codes the UI branches on. They are named after the backend advice
 * classes rather than after HTTP status codes, because two different failures
 * share a status and the *remedy* differs: a `CSRF_TOKEN_INVALID` is retried
 * with a fresh token, an `ACCESS_DENIED` is not retried at all.
 */
export const ApiErrorCode = {
  authenticationRequired: 'AUTHENTICATION_REQUIRED',
  invalidCredentials: 'INVALID_CREDENTIALS',
  loginRateLimited: 'LOGIN_RATE_LIMITED',
  accessDenied: 'ACCESS_DENIED',
  csrfTokenInvalid: 'CSRF_TOKEN_INVALID',
  notFound: 'NOT_FOUND',
  internal: 'INTERNAL',
} as const;

export type ApiErrorCodeValue = (typeof ApiErrorCode)[keyof typeof ApiErrorCode];

/**
 * A failed API call, carrying everything a support conversation needs: the
 * machine code, the correlation id the server logged, and whether a retry is
 * worth anything. `message` from the server is never shown raw to an operator —
 * the i18n layer owns user-facing copy — but it is available for a developer
 * console and for the retry decision.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId: string | null,
    readonly body: ApiErrorBody | null,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  /** 401: the session is gone. The only code that means "stop and sign in". */
  get isSessionExpired(): boolean {
    return this.status === 401;
  }

  /** 403 with a CSRF code: a fresh token and one retry is the documented remedy. */
  get isCsrfFailure(): boolean {
    return this.status === 403 && this.code === ApiErrorCode.csrfTokenInvalid;
  }

  /** 403 for a permission: not retryable, and the UI says the server decided. */
  get isPermissionDenied(): boolean {
    return this.status === 403 && this.code === ApiErrorCode.accessDenied;
  }

  /** 404 is the non-disclosing denial the backend uses for another tenant's row. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isRetryable(): boolean {
    return this.body?.retryable === true || this.status >= 500 || this.status === 429;
  }
}

/** Optional query parameters: absent values are omitted, never sent as `null`. */
export type QueryParams = Readonly<
  Record<string, string | number | boolean | null | undefined>
>;

export function toHttpParams(query: QueryParams | undefined): HttpParams {
  let params = new HttpParams();
  if (!query) return params;
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue;
    params = params.set(key, String(value));
  }
  return params;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  /** The base every path is resolved against. Empty means same-origin. */
  readonly baseUrl = environment.apiBaseUrl;

  get<T>(path: string, query?: QueryParams): Promise<T> {
    return this.request<T>('GET', path, query);
  }

  post<T>(path: string, body?: unknown, query?: QueryParams): Promise<T> {
    return this.request<T>('POST', path, query, body);
  }

  put<T>(path: string, body?: unknown, query?: QueryParams): Promise<T> {
    return this.request<T>('PUT', path, query, body);
  }

  patch<T>(path: string, body?: unknown, query?: QueryParams): Promise<T> {
    return this.request<T>('PATCH', path, query, body);
  }

  delete<T>(path: string, query?: QueryParams): Promise<T> {
    return this.request<T>('DELETE', path, query);
  }

  /**
   * A request that also carries headers the operation declared.
   *
   * `Idempotency-Key` and `X-Expected-Message-Count` are **headers**, not query
   * parameters — the contract puts them there deliberately, so a double-submitted
   * reply is recognised as the same write and a reply written against a stale
   * thread is refused. They are passed as `options` rather than folded into the
   * query so the request cannot accidentally put a token-shaped value in a URL
   * that gets logged.
   */
  postWithHeaders<T>(
    path: string,
    body: unknown,
    query?: QueryParams,
    headers?: Readonly<Record<string, string>>,
  ): Promise<T> {
    return this.request<T>('POST', path, query, body, headers);
  }

  patchWithHeaders<T>(
    path: string,
    body: unknown,
    query?: QueryParams,
    headers?: Readonly<Record<string, string>>,
  ): Promise<T> {
    return this.request<T>('PATCH', path, query, body, headers);
  }

  putWithHeaders<T>(
    path: string,
    body: unknown,
    query?: QueryParams,
    headers?: Readonly<Record<string, string>>,
  ): Promise<T> {
    return this.request<T>('PUT', path, query, body, headers);
  }

  /**
   * A raw `HttpClient` handle for the two things a JSON request cannot express:
   * `text/event-stream` (realtime) and a `Blob` download (a signed export
   * link). Both still ride the same interceptor chain.
   */
  get httpClient(): HttpClient {
    return this.http;
  }

  private async request<T>(
    method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    path: string,
    query?: QueryParams,
    body?: unknown,
    headers?: Readonly<Record<string, string>>,
  ): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const params = toHttpParams(query);
    try {
      const value = await firstValueFrom(
        this.http.request<T>(method, url, {
          params,
          headers: headers && Object.keys(headers).length > 0 ? headers : undefined,
          body: body === undefined ? undefined : body,
          // The session is an HttpOnly cookie: `withCredentials` is what makes
          // the browser attach it to a cross-origin dev base URL, and it is
          // harmless same-origin.
          withCredentials: true,
          responseType: 'json' as const,
        }),
      );
      // A bodyless response (`204 No Content`, which is what `POST
      // /api/v1/auth/logout` returns) arrives as `null` even when the caller
      // declared `void`. Normalised here so a caller that checks the result does
      // not have to know which transport produced the null.
      return (value === null ? (undefined as T) : value);
    } catch (cause) {
      throw toApiError(cause);
    }
  }
}

/**
 * Normalises anything thrown on the wire into an `ApiError`.
 *
 * A response with no body — a proxy 502, a dropped connection — still produces
 * a usable error with a code the UI can branch on, rather than a bare
 * `HttpErrorResponse` whose shape depends on which layer failed.
 */
export function toApiError(cause: unknown): ApiError {
  if (cause instanceof ApiError) return cause;
  const fallbackMessage = 'The server did not respond as expected.';
  if (isHttpErrorResponse(cause)) {
    const body = (cause.error ?? null) as ApiErrorBody | null;
    const status = cause.status ?? 0;
    const code =
      typeof body?.code === 'string' && body.code !== '' ? body.code : statusToCode(status);
    const message =
      typeof body?.message === 'string' && body.message !== '' ? body.message : fallbackMessage;
    const requestId =
      typeof body?.requestId === 'string' && body.requestId !== ''
        ? body.requestId
        : (cause.headers?.get('X-Correlation-Id') ?? null);
    return new ApiError(status, code, message, requestId, body ?? null);
  }
  if (cause instanceof Error) {
    return new ApiError(0, 'NETWORK', cause.message, null, null);
  }
  return new ApiError(0, 'NETWORK', fallbackMessage, null, null);
}

function statusToCode(status: number): string {
  switch (status) {
    case 400:
      return 'VALIDATION_FAILED';
    case 401:
      return ApiErrorCode.authenticationRequired;
    case 403:
      return ApiErrorCode.accessDenied;
    case 404:
      return ApiErrorCode.notFound;
    case 409:
      return 'CONFLICT';
    case 429:
      return ApiErrorCode.loginRateLimited;
    default:
      return status >= 500 ? ApiErrorCode.internal : 'UNKNOWN';
  }
}

interface HttpErrorLike {
  readonly status?: number;
  readonly error?: unknown;
  readonly headers?: { get(name: string): string | null };
}

function isHttpErrorResponse(cause: unknown): cause is HttpErrorLike {
  return (
    typeof cause === 'object' &&
    cause !== null &&
    'status' in cause &&
    ('error' in cause || 'headers' in cause)
  );
}

/** Re-exported so callers import models from one place. */
export type {
  Api,
};
