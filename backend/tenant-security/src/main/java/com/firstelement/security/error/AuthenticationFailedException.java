package com.firstelement.security.error;

/**
 * Every failed credential check produces this, and nothing else.
 *
 * <p>An unknown email, an inactive user, an inactive organization, and a wrong password
 * are the same exception with the same message. Distinguishing them — in the status code,
 * the body, the exception type, or the log line — turns login into an account-enumeration
 * oracle, so {@code LoginService} raises this one type for all of them.
 *
 * <p>Renders as {@code 401 INVALID_CREDENTIALS}.
 */
public class AuthenticationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException() {
        super("Invalid email or password");
    }
}