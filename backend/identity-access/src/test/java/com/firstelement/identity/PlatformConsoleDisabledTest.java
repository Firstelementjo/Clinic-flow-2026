package com.firstelement.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.security.platform.PlatformAuditRepository;
import com.firstelement.security.platform.PlatformAuthService;
import com.firstelement.security.platform.PlatformClinicService;
import com.firstelement.security.platform.PlatformOperatorAuthenticationFilter;
import com.firstelement.security.platform.PlatformOperatorRepository;
import org.springframework.context.annotation.Conditional;
import com.firstelement.security.platform.web.PlatformConsoleController;
import com.firstelement.security.platform.web.PlatformExceptionAdvice;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * With the console switched off, the cross-tenant surface is <em>absent</em> rather than merely
 * guarded.
 *
 * <p>This is the counterpart to {@code PlatformConsoleBoundaryHttpIntegrationTest}, which turns the
 * console on. Together they pin both directions of the default: enabling it is a deliberate act, and
 * not enabling it removes the routes entirely.
 *
 * <p>The property is deliberately absent from this context, rather than set to {@code false}, so the
 * test also covers the default a deployment that has never heard of the console gets.
 */
@SpringBootTest(
    classes = IdentityAccessTestApplication.class,
    properties = {
        "firstelement.environment=TEST",
        "firstelement.api.web-origin=http://localhost:5173",
        "spring.flyway.locations=classpath:db/migration",
        "management.health.db.enabled=false",
        "management.health.redis.enabled=false",
        "management.tracing.sampling.probability=0.0"
    })
class PlatformConsoleDisabledTest extends SecurityIntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RequestMappingHandlerMapping handlerMappings;

    @Test
    @DisplayName("the console controller and its filter are not registered")
    void consoleBeansAreAbsent() {
        assertThat(context.getBeanNamesForType(PlatformConsoleController.class))
                .as("the controller must not exist, so there is nothing to protect")
                .isEmpty();

        assertThat(context.getBeanNamesForType(PlatformOperatorAuthenticationFilter.class))
                .as("the filter is only useful with a console behind it")
                .isEmpty();
    }

    @Test
    @DisplayName("no handler is mapped under /api/v1/platform")
    void noPlatformRouteIsMapped() {
        // Asserted against the mapping table rather than by making a request and reading a status:
        // a request could 403 for reasons that have nothing to do with whether the route exists, and
        // this is a question about which routes exist.
        boolean anyPlatformMapping = handlerMappings.getHandlerMethods().entrySet().stream()
                .anyMatch(entry -> isPlatformPath(entry.getKey()));

        assertThat(anyPlatformMapping)
                .as("a disabled console must leave no platform route to authenticate into")
                .isFalse();
    }

    private static boolean isPlatformPath(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() == null) {
            return false;
        }
        return info.getPathPatternsCondition().getPatterns().stream()
                .anyMatch(pattern -> pattern.getPatternString().startsWith("/api/v1/platform"));
    }

    /**
     * A stereotype annotation on any console class would make every assertion above pass or fail for
     * the wrong reason.
     *
     * <p>Component scanning instantiates a {@code @Service} regardless of what any
     * {@code @ConditionalOnProperty} says, so re-adding one would quietly make this switch inert
     * rather than make it fail. That failure mode is invisible in a normal test run -- the console
     * would simply be present everywhere -- so it is asserted directly here.
     *
     * <p>The web layer is the exception: the controller and the advice <em>must</em> keep their
     * stereotypes, because on Spring Framework 6.2 a controller built by a {@code @Bean} method is
     * never treated as a handler and its routes silently 404. They are therefore required to carry
     * {@code @ConditionalOnProperty} as well. So the invariant is not "no stereotypes" but "no
     * ungated stereotypes".
     */
    @Test
    @DisplayName("no console class carries an ungated component-scan stereotype")
    void consoleClassesAreNotUngatedComponentScanned() {
        List<Class<?>> ungatedMustNotBeAnnotated = List.of(
                PlatformAuthService.class,
                PlatformClinicService.class,
                PlatformOperatorRepository.class,
                PlatformAuditRepository.class,
                PlatformOperatorAuthenticationFilter.class,
                com.firstelement.security.platform.PlatformPlanService.class,
                com.firstelement.security.platform.PlatformSupportAccessService.class,
                com.firstelement.security.platform.PlatformDashboardService.class);

        List<Class<? extends java.lang.annotation.Annotation>> stereotypes = List.of(
                Service.class, Repository.class, RestController.class,
                RestControllerAdvice.class, Component.class, Configuration.class);

        for (Class<?> type : ungatedMustNotBeAnnotated) {
            for (Class<? extends java.lang.annotation.Annotation> stereotype : stereotypes) {
                assertThat(type.isAnnotationPresent(stereotype))
                        .as("%s must not be annotated @%s: component scanning would create it in "
                                + "every context and make the console switch inert",
                                type.getSimpleName(), stereotype.getSimpleName())
                        .isFalse();
            }
        }

        for (Class<?> type : List.of(PlatformConsoleController.class, PlatformExceptionAdvice.class)) {
            boolean gated = org.springframework.core.annotation.AnnotatedElementUtils
                    .hasAnnotation(type, Conditional.class);
            assertThat(gated)
                    .as("%s keeps its stereotype for handler detection, so it must also carry "
                            + "@Conditional: an ungated @RestController would publish "
                            + "/api/v1/platform in every context",
                            type.getSimpleName())
                    .isTrue();
        }
    }
}