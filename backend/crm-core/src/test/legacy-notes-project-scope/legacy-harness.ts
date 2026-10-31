/**
 * Shared machinery for the legacy `apps/api` project-scope proofs.
 *
 * Two things belong here rather than in either suite, because both suites must
 * make identical claims about them:
 *
 *  1. `matches` — the Prisma `where` matcher. Both fakes implement only the
 *     operators these services actually emit (scalar equality and
 *     `{ in: [...] }`); anything else is a hole in the harness, so the
 *     restriction is deliberate and recorded rather than accidental.
 *
 *  2. The error renderer. Non-disclosure is asserted by driving the **real**
 *     `HttpExceptionFilter` and comparing the body a client would receive, so
 *     the rendering must be identical for every service under test. A second
 *     copy of this function could drift and quietly weaken one suite.
 */
import { HttpExceptionFilter } from '../../../../../apps/api/src/common/http-exception.filter';

export type Where = Record<string, unknown>;

/** Prisma `where` semantics for the operators these services emit. */
export function matches(row: Record<string, unknown>, where: Where): boolean {
  return Object.entries(where).every(([field, expected]) => {
    if (expected && typeof expected === 'object' && 'in' in (expected as object)) {
      const allowed = (expected as { in: string[] }).in;
      return allowed.includes(row[field] as string);
    }
    return row[field] === expected;
  });
}

export interface PrismaCall {
  op: 'findMany' | 'findFirst' | 'update' | 'delete' | 'create';
  where?: Where;
  id?: string;
}

export interface RenderedResponse {
  status: number;
  body: Record<string, unknown>;
  /** Body with the per-request echo removed, for shape comparison. */
  bodyWithoutRequestId: Record<string, unknown>;
}

export const FIXED_REQUEST_ID = 'request-id-fixed-for-comparison';

/**
 * Drives the real `HttpExceptionFilter` the way Nest does at runtime, and
 * returns the exact status and body a client would see.
 */
export function renderThroughGlobalFilter(
  exception: unknown,
  requestId: string,
  path = '/notes/project',
): RenderedResponse {
  let status = 0;
  let body: Record<string, unknown> = {};

  const response = {
    status(code: number) {
      status = code;
      return this;
    },
    json(payload: Record<string, unknown>) {
      body = payload;
      return this;
    },
  };
  const request = { requestId, path, sessionUser: undefined };
  const host = {
    switchToHttp: () => ({ getResponse: () => response, getRequest: () => request }),
  };

  new HttpExceptionFilter().catch(exception, host as never);

  const { requestId: _echo, ...bodyWithoutRequestId } = body;
  return { status, body, bodyWithoutRequestId };
}

/** Call a service method, returning the rendered HTTP response for a denial. */
export async function httpOutcome(
  call: () => Promise<unknown>,
  requestId = FIXED_REQUEST_ID,
  path?: string,
): Promise<RenderedResponse> {
  try {
    const value = await call();
    return { status: 200, body: value as Record<string, unknown>, bodyWithoutRequestId: {} };
  } catch (error) {
    return renderThroughGlobalFilter(error, requestId, path);
  }
}
