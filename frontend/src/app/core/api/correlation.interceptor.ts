import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { CorrelationService, CORRELATION_HEADER } from './correlation.service';

/**
 * Stamps a correlation id on every request.
 *
 * `CorrelationIdFilter` on the server adopts an inbound `X-Correlation-Id` or
 * generates one, and returns it on the response and inside every error body. A
 * browser that sends its own makes an operator's screen traceable into the server
 * log from one identifier, which is the difference between a two-minute question
 * and an afternoon.
 *
 * It is a trace id, not a credential: generated per request, held in memory, never
 * persisted and never sent to a third party.
 */
export const correlationInterceptor: HttpInterceptorFn = (request, next) => {
  // An absolute cross-origin URL is not ours to instrument.
  if (!request.url.startsWith('/') && !request.url.startsWith(location.origin)) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { [CORRELATION_HEADER]: inject(CorrelationService).next() } }));
};
