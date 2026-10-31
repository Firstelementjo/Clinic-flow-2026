package com.firstelement.kernel.web;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns/propagates a correlation id for every request so logs, error bodies, and
 * realtime/audit payloads can be joined. Accepts inbound X-Request-Id/X-Correlation-Id
 * for tracing continuity across proxies; never logs headers or bodies. Registered by the
 * web application; not scanned so worker contexts stay web-free.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        if (incoming == null || incoming.isBlank() || incoming.length() > 128) {
            incoming = request.getHeader("X-Request-Id");
        }
        String correlationId = (incoming != null && !incoming.isBlank() && incoming.length() <= 128)
            ? incoming
            : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        request.setAttribute(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
