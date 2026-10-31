package com.firstelement.security.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Matches requests that no Spring MVC handler declares.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Two requirements pull in opposite directions:
 *
 * <ul>
 *   <li><b>Default deny.</b> {@code anyRequest().authenticated()} must be the catch-all, so
 *       a forgotten annotation on a new controller is a failure, not a leak.</li>
 *   <li><b>A stable, non-disclosing 404 contract.</b> An unmapped path is not a secret, and
 *       answering {@code 401 AUTHENTICATION_REQUIRED} for {@code /api/v1/does-not-exist}
 *       makes the error contract depend on authentication state for a request that could
 *       never succeed anyway.</li>
 * </ul>
 *
 * <p>The catch-all keeps its default-deny behaviour and this matcher narrows it to the one
 * case where "no handler" is a complete answer. The result is the intended pair:
 * {@code 404 NOT_FOUND} for an unmapped path, {@code 401 AUTHENTICATION_REQUIRED} for a
 * mapped but unauthenticated one.
 *
 * <h2>All handler mappings must agree</h2>
 *
 * <p>The application has more than one: the MVC {@code requestMappingHandlerMapping} and
 * the Actuator one. A request counts as unmapped only when <em>every</em> mapping table
 * fails to match it, so an Actuator path is never mistaken for an unmapped one.
 *
 * <h2>Fail-closed</h2>
 *
 * <p>A request is treated as unmapped only when at least one mapping table is non-empty
 * and none matches. Before the mappings are initialised, during context refresh, or if a
 * lookup throws, this matcher matches nothing and the catch-all default-deny applies. A
 * failure here makes the system stricter, never more permissive.
 */
public final class UnmappedRequestMatcher implements RequestMatcher {

    private static final Logger log = LoggerFactory.getLogger(UnmappedRequestMatcher.class);

    private final List<RequestMappingHandlerMapping> handlerMappings;

    public UnmappedRequestMatcher(List<RequestMappingHandlerMapping> handlerMappings) {
        this.handlerMappings = handlerMappings == null ? List.of() : List.copyOf(handlerMappings);
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        boolean sawMappings = false;
        try {
            for (RequestMappingHandlerMapping handlerMapping : handlerMappings) {
                Map<RequestMappingInfo, HandlerMethod> methods = handlerMapping.getHandlerMethods();
                if (methods == null || methods.isEmpty()) {
                    continue;
                }
                sawMappings = true;
                for (RequestMappingInfo mapping : methods.keySet()) {
                    if (mapping.getMatchingCondition(request) != null) {
                        return false;
                    }
                    // Content negotiation must not decide whether a path counts as mapped.
                    // getMatchingCondition() folds in the `produces` condition, so without this a
                    // caller could pick its own security outcome by changing Accept: an
                    // unauthenticated GET of the SSE stream with Accept: application/json would be
                    // treated as unmapped and permitted, then answered 406 instead of 401. No
                    // handler runs and no data is produced, but a client-controlled header deciding
                    // whether authentication is required is a default-deny hole. Path and method
                    // matching alone is what "mapped" has to mean.
                    if (matchesPathAndMethod(mapping, request)) {
                        return false;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Handler mapping lookup failed; treating the path as mapped (fail closed)", e);
            return false;
        }
        return sawMappings;
    }

    /**
     * Whether the path and HTTP method match, deliberately ignoring {@code produces} and
     * {@code consumes}.
     */
    private static boolean matchesPathAndMethod(RequestMappingInfo mapping, HttpServletRequest request) {
        if (mapping.getMethodsCondition() == null
                || mapping.getMethodsCondition().getMatchingCondition(request) == null) {
            return false;
        }
        if (mapping.getPathPatternsCondition() != null) {
            return mapping.getPathPatternsCondition().getMatchingCondition(request) != null;
        }
        // A mapping built on the deprecated Ant-style patterns has no path-patterns condition.
        @SuppressWarnings("deprecation")
        org.springframework.web.servlet.mvc.condition.PatternsRequestCondition patterns =
            mapping.getPatternsCondition();
        return patterns != null && patterns.getMatchingCondition(request) != null;
    }
}