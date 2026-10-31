package com.firstelement;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.kernel.error.ErrorResponse;
import com.firstelement.kernel.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    @Test
    void generatesCorrelationIdWhenAbsent() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (q, s) -> {
            assertThat(req.getAttribute(CorrelationIdFilter.MDC_KEY)).isNotNull();
        });
        assertThat(res.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void preservesIncomingCorrelationId() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Correlation-Id", "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (q, s) -> {
            assertThat(req.getAttribute(CorrelationIdFilter.MDC_KEY)).isEqualTo("abc-123");
        });
        assertThat(res.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
    }

    @Test
    void errorResponseKeepsStableShape() {
        ErrorResponse r = ErrorResponse.of("NOT_FOUND", "Resource not found", "req-1");
        assertThat(r.code()).isEqualTo("NOT_FOUND");
        assertThat(r.requestId()).isEqualTo("req-1");
        assertThat(r.retryable()).isNull();
        assertThat(ErrorResponse.retryable("RATE_LIMIT", "slow down", "req-2").retryable()).isTrue();
    }
}
