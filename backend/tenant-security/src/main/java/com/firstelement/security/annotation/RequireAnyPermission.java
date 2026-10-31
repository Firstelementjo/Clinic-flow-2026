package com.firstelement.security.annotation;

import com.firstelement.security.permission.Permission;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Method-security meta-annotation: "the caller must hold at least one of these".
 *
 * <p>Quoting has to be supplied per element because the values are spliced into a
 * SpEL array literal, following the {@code AnnotationTemplateExpressionDefaults}
 * convention. {@code @RequireAnyPermission({CUSTOMER_READ, CUSTOMER_UPDATE})}.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("@fePermissionEvaluator.allowsAny(authentication, {value})")
public @interface RequireAnyPermission {

    Permission[] value();
}