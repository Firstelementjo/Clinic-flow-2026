package com.firstelement.security.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Method-security meta-annotation: the caller's session must be alive.
 *
 * <p>Distinct from {@link RequirePermission} because "you hold the permission" and
 * "your session still exists" are different facts. The second one is re-derived from
 * the {@code sessions} table on every request by
 * {@code BrowserSessionAuthenticationFilter}, so a revoked session cannot be
 * "remembered" by a long-lived permission cache or an in-flight job.
 *
 * <p>Effectively a no-op today — the filter never populates an authentication for a
 * revoked session — and deliberately kept so that any future cache of authorization
 * state has an explicit, reviewable place to declare its staleness.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface RequireLiveSession {
}