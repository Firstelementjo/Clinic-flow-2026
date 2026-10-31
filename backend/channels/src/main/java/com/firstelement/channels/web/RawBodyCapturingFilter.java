package com.firstelement.channels.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures the raw request body so a signature can be verified over the bytes as received.
 *
 * <h2>Why a filter and not {@code @RequestBody}</h2>
 *
 * <p>Meta signs the exact bytes. Verifying a re-serialized {@code JSON.parse} result computes a
 * different digest — key order and whitespace change — so the legacy runtime buffered the body in
 * middleware for exactly this reason. Spring MVC's
 * {@code ContentCachingRequestWrapper} does not help: it only has content
 * <em>after</em> something has read the stream, so a controller that wants the raw bytes for a
 * signature check has already lost them.
 *
 * <h2>What it buffers, and what it refuses to buffer</h2>
 *
 * <ul>
 *   <li>Only {@code POST} to a {@code /webhooks/} path. Nothing else is touched, so the filter
 *       costs nothing on ordinary traffic.</li>
 *   <li>At most {@link #MAX_BYTES}. A webhook body larger than that is a 413 rather than an
 *       out-of-memory condition: an unauthenticated endpoint that allocates whatever it is sent is
 *       an amplification vector.</li>
 * </ul>
 *
 * <h2>The re-served stream is byte-identical</h2>
 *
 * <p>{@link CachedBodyHttpServletRequest} returns a fresh {@link ByteArrayInputStream} over the
 * same array for every read, so a controller can parse the body after the verifier has seen it, and
 * a filter downstream of this one still gets the full stream.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RawBodyCapturingFilter extends OncePerRequestFilter {

    /** 1 MiB. Meta and Telegram webhook bodies are kilobytes. */
    public static final int MAX_BYTES = 1024 * 1024;

    /** Where the captured body is exposed to the application. */
    public static final String RAW_BODY_ATTRIBUTE = "firstelement.rawBody";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        return !"POST".equalsIgnoreCase(request.getMethod())
            || !path.startsWith("/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] body = readBounded(request);
        if (body == null) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "webhook body exceeds " + MAX_BYTES + " bytes");
            return;
        }
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request, body);
        wrapped.setAttribute(RAW_BODY_ATTRIBUTE, body);
        chain.doFilter(wrapped, response);
    }

    /**
     * @return the captured bytes, or {@code null} when the body exceeded the bound. A truncated
     *         read is never returned: verifying a signature over half a body would either fail
     *         confusingly or, worse, succeed against a truncated prefix.
     */
    private static byte[] readBounded(HttpServletRequest request) throws IOException {
        byte[] buffer = new byte[Math.min(MAX_BYTES, 8192)];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = request.getInputStream().read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BYTES) {
                return null;
            }
        }
        return out.toByteArray();
    }

    /** The captured body as a UTF-8 string, or {@code null} when the filter did not run. */
    public static String rawBody(HttpServletRequest request) {
        Object attribute = request.getAttribute(RAW_BODY_ATTRIBUTE);
        return attribute instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /** The captured bytes, or {@code null} when the filter did not run. */
    public static byte[] rawBytes(HttpServletRequest request) {
        Object attribute = request.getAttribute(RAW_BODY_ATTRIBUTE);
        return attribute instanceof byte[] bytes ? bytes : null;
    }

    /**
     * Every request header, keyed case-insensitively.
     *
     * <p>HTTP header names are case-insensitive (RFC 7230) and the two schemes use different
     * spellings in the wild ({@code X-Hub-Signature-256} vs {@code x-hub-signature-256}), so a
     * case-insensitive map is the only correct shape.
     */
    public static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    /** A request whose body can be read any number of times, byte for byte. */
    public static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        public CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] target, int off, int len) {
                    return source.read(target, off, len);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Async reads are not used on the webhook path; the body is already buffered.
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = StandardCharsets.UTF_8;
            if (encoding != null) {
                try {
                    charset = Charset.forName(encoding);
                } catch (RuntimeException e) {
                    charset = StandardCharsets.UTF_8;
                }
            }
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return java.util.Collections.enumeration(getHeaderValues(name));
        }

        @Override
        public String getHeader(String name) {
            Enumeration<String> values = ((HttpServletRequest) getRequest()).getHeaders(name);
            return values == null || !values.hasMoreElements() ? null : values.nextElement();
        }

        private java.util.List<String> getHeaderValues(String name) {
            java.util.List<String> values = new java.util.ArrayList<>();
            Enumeration<String> raw = ((HttpServletRequest) getRequest()).getHeaders(name);
            while (raw != null && raw.hasMoreElements()) {
                values.add(raw.nextElement());
            }
            return values;
        }
    }
}