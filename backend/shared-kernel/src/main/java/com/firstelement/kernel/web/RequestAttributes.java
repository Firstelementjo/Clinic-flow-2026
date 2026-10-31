package com.firstelement.kernel.web;

/** Shared servlet request attribute keys. */
public final class RequestAttributes {
    public static final String CORRELATION_ID = CorrelationIdFilter.MDC_KEY;

    private RequestAttributes() {}
}
