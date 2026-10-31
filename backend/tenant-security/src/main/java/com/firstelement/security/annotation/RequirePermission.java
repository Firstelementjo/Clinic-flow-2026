package com.firstelement.security.annotation;

import com.firstelement.security.permission.Permission;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Method-security meta-annotation: "the caller must hold this permission".
 *
 * <p>The expression is templated from this annotation's own attribute, which requires
 * the {@code AnnotationTemplateExpressionDefaults} bean published by
 * {@code SecurityConfiguration}. That keeps the decision in
 * {@code AuthorizationService} instead of scattering role strings through annotation
 * expressions, and it means the annotation can never be satisfied by a
 * client-supplied tenant or project id: the evaluator resolves the
 * {@code TenantContext} carried by the {@code Authentication}.
 *
 * <p>Correct placement is a domain service or repository method — never the only place
 * the check exists (plan.md §0.4). An HTTP request rule remains required as the
 * catch-all, because unannotated methods are not secured by method security.
 *
 * <pre>{@code
 * @RequirePermission(Permission.CUSTOMER_READ)
 * public List<CustomerView> findCustomers(@Nullable UUID requestedProjectId) {
 *     TenantScope scope = guard.scope(TenantContextHolder.require(), requestedProjectId);
 *     ScopedSql sql = ScopedWhere.of(scope).query(SELECT, "c", null);
 *     ...
 * }
 * }</pre>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("@fePermissionEvaluator.allows(authentication, T(com.firstelement.security.permission.Permission).{value})")
public @interface RequirePermission {

    Permission value();
}