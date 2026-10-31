/**
 * Correlation id.
 *
 * The backend runs a `CorrelationIdFilter` and returns `X-Correlation-Id` on
 * every response and inside every error body (`ErrorResponse.requestId`). The
 * browser sends one upstream per request so a trace can be followed from the
 * operator's screen into the server log, and the response value is kept so an
 * error message can quote it.
 *
 * This is a traceability id, not a credential. It is generated per request and
 * held in memory only.
 */
import { Injectable, signal } from '@angular/core';

export const CORRELATION_HEADER = 'X-Correlation-Id';

function randomId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  // Older engines and some test environments: a random hex string is enough,
  // because this value only has to be unique within one request trace.
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

@Injectable({ providedIn: 'root' })
export class CorrelationService {
  private readonly current = signal<string>(randomId());

  /** The id of the most recent response. Shown next to an error, never logged. */
  readonly lastResponseId = signal<string | null>(null);

  next(): string {
    const id = randomId();
    this.current.set(id);
    return id;
  }

  record(requestId: string | null): void {
    if (requestId && requestId !== '') this.lastResponseId.set(requestId);
  }

  get currentId(): string {
    return this.current();
  }
}
