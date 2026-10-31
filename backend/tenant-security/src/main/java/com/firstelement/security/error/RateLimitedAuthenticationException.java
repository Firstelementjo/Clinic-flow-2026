package com.firstelement.security.error;

/**
 * The credential-stuffing surface refused the attempt.
 *
 * <p>Renders as {@code 429 TOO_MANY_REQUESTS} with a {@code Retry-After} header. The
 * retry delay comes from the limiter and reveals nothing about whether the account
 * exists, because the limiter is consulted before the account is even looked up.
 */
public class RateLimitedAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;

    public RateLimitedAuthenticationException(int retryAfterSeconds) {
        super("Too many login attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}