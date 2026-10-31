package com.firstelement.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.finance.web.FinanceController;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantScope;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reflection over the module's own HTTP and service surface.
 *
 * <h2>Why a reflection suite and not an HTTP suite</h2>
 *
 * <p>Every other finance test proves a behaviour. This one proves an <em>absence</em> — that no route and
 * no service method reached from a route exists without a declared permission — and an absence cannot
 * be demonstrated by calling the thing that is not there. A test that lists today's fourteen handlers
 * and checks each one passes today; what matters is that tomorrow's fifteenth is checked too, without
 * anyone remembering to add it to a list. Reflection over the compiled classes does that.
 *
 * <p>It is a cheap suite on purpose: no container, no database, no HTTP. It runs in milliseconds, which
 * is what makes it likely to still be here in six months.
 *
 * <h2>What it deliberately does not check</h2>
 *
 * <p>It does not check that the permission is the <em>right</em> one, and it does not check that the
 * service beneath the route re-checks. The first is a judgement call reviewed by a person; the second is
 * proven behaviourally by {@code UsageReadModelStateIntegrationTest} and
 * {@code FinanceTenantIsolationIntegrationTest}, which call the services with an employee context and
 * with a foreign project id. This suite only proves the declarations exist.
 */
@DisplayName("finance routes and services declare their own permissions")
class FinanceRoutePermissionTest {

    // ==================================================================
    // the HTTP surface
    // ==================================================================

    @Nested
    @DisplayName("every mapped handler declares its permission")
    class Handlers {

        @Test
        @DisplayName("no handler exists without @RequirePermission")
        void everyHandlerDeclaresAPermission() {
            List<String> undeclared = new ArrayList<>();
            for (Method method : FinanceController.class.getDeclaredMethods()) {
                if (!isHandler(method)) {
                    continue;
                }
                if (method.getAnnotation(RequirePermission.class) == null) {
                    undeclared.add(method.getName());
                }
            }

            assertThat(undeclared)
                .as("a finance handler with no declaration answers to authenticated() alone, which for "
                    + "a cost or revenue endpoint is a disclosure of the tenant's margins")
                .isEmpty();
        }

        @Test
        @DisplayName("the declaration is the organization-wide analytics permission, on every route")
        void everyHandlerDeclaresTheOrganizationPermission() {
            // There is no weaker permission for "what does this cost", so every route takes the same
            // one and the service layer below distinguishes read from write by role.
            for (Method method : FinanceController.class.getDeclaredMethods()) {
                if (!isHandler(method)) {
                    continue;
                }
                RequirePermission declared = method.getAnnotation(RequirePermission.class);
                assertThat(declared)
                    .as("%s must declare a permission", method.getName())
                    .isNotNull();
                assertThat(declared.value())
                    .as("%s", method.getName())
                    .isEqualTo(Permission.ANALYTICS_READ_ORGANIZATION);
            }
        }

        @Test
        @DisplayName("the scanner this suite uses really does flag an undeclared handler")
        void theScannerIsNotVacuous() {
            // A guard that cannot fail is worse than no guard, so it is pointed at a class that
            // deliberately has one undeclared handler and must report exactly that one.
            List<String> flagged = new ArrayList<>();
            for (Method method : BadlyDeclaredController.class.getDeclaredMethods()) {
                if (isHandler(method) && method.getAnnotation(RequirePermission.class) == null) {
                    flagged.add(method.getName());
                }
            }

            assertThat(flagged).containsExactly("undeclaredRead");
        }
    }

    // ==================================================================
    // the service surface
    // ==================================================================

    @Nested
    @DisplayName("a public method that takes a caller declares its own permission")
    class Services {

        @Test
        @DisplayName("every finance service method taking a TenantContext is annotated")
        void everyCallerFacingServiceMethodIsAnnotated() {
            List<String> undeclared = new ArrayList<>();
            for (Class<?> type : financeServices()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!Modifier.isPublic(method.getModifiers())
                        || method.isSynthetic()
                        || method.getDeclaringClass() == Object.class
                        || !takesCaller(method)) {
                        continue;
                    }
                    if (method.getAnnotation(RequirePermission.class) == null) {
                        undeclared.add(type.getSimpleName() + "#" + method.getName());
                    }
                }
            }

            assertThat(undeclared)
                .as("method security does not cover unannotated methods, so an unannotated public "
                    + "service method is reachable by anything that can construct it")
                .isEmpty();
        }

        @Test
        @DisplayName("the scan covers the beans, and it is not looking at nothing")
        void theServiceScanIsNotVacuous() {
            List<String> names = new ArrayList<>();
            for (Class<?> type : financeServices()) {
                names.add(type.getSimpleName());
            }

            assertThat(names)
                .as("a scan that found no beans would pass every assertion in this class")
                .contains("FinanceUsageReadService", "RateCardService", "PeriodLifecycleService",
                    "AdjustmentService", "ReconciliationService", "DrillThroughService",
                    "LegacyAnalyticsComparisonService");
        }

        @Test
        @DisplayName("a method taking an authorized scope is exempt, and that is a different contract")
        void scopeTakingMethodsAreExempt() throws NoSuchMethodException {
            // The distinction the suite turns on: a TenantContext is a REQUEST that must be authorized;
            // a TenantScope is an AUTHORIZATION already performed by the caller. rateFor(scope, ...)
            // takes the second, so refusing to re-authorize it would be theatre — and requiring a
            // TenantContext there would mean re-resolving the scope the caller already holds.
            Method rateFor = com.firstelement.finance.rollup.RollupService.class.getMethod("rateFor",
                TenantScope.class, java.util.UUID.class,
                com.firstelement.finance.catalog.UsageDimension.class, java.time.LocalDate.class);

            assertThat(takesCaller(rateFor))
                .as("a scope-taking method must not be mistaken for a caller-facing one")
                .isFalse();
            assertThat(rateFor.getAnnotation(RequirePermission.class))
                .as("and it inherits its caller's authorization instead of declaring its own")
                .isNull();
        }
    }

    // ==================================================================
    // what the routes may not accept
    // ==================================================================

    @Nested
    @DisplayName("no finance route executes a payment or accepts a revenue")
    class RequestSurface {

        @Test
        @DisplayName("no request field names a payment instrument, a mandate, an invoice or a charge")
        void noRequestFieldIsAPayment() {
            // ADR 0007 rule 7. The absence is structural here: there is no field a payment could bind to,
            // so a route that executes one cannot be written without also changing this surface, which
            // this suite would then fail.
            List<String> paymentish = List.of("payment", "invoice", "mandate", "iban", "cardnumber",
                "cardholder", "pan", "cvc", "acquirer", "processor", "charge", "payout", "settlement");

            List<String> offenders = new ArrayList<>();
            for (RecordComponent component : requestComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                for (String forbidden : paymentish) {
                    if (name.contains(forbidden)) {
                        offenders.add(component.getDeclaringRecord().getSimpleName() + "."
                            + component.getName());
                    }
                }
            }

            assertThat(offenders)
                .as("this module does not execute payments and does not issue invoices; nothing in the "
                    + "schema could store one either")
                .isEmpty();
        }

        @Test
        @DisplayName("no request field supplies a revenue, a markup or a unit price")
        void noRequestFieldSuppliesRevenue() {
            // "Cost is stored, revenue is derived" (ADR 0007 rule 2). The only amount a client may send
            // is an adjustment to COST, and that is a proposed correction with a reason and an approver
            // — not a price.
            List<String> derived = List.of("revenue", "markup", "unitprice", "unit_price", "priceamount");

            List<String> offenders = new ArrayList<>();
            for (RecordComponent component : requestComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                for (String forbidden : derived) {
                    if (name.contains(forbidden)) {
                        offenders.add(component.getDeclaringRecord().getSimpleName() + "."
                            + component.getName());
                    }
                }
            }

            assertThat(offenders)
                .as("a revenue supplied by a client is a second pricing mechanism beside the rate cards")
                .isEmpty();
        }

        @Test
        @DisplayName("a body may name a project, because naming it is not authorizing it")
        void projectIdsAreRequestsNotAuthorization() {
            // Every project id on this surface is a request parameter that the service below re-checks
            // against the caller's memberships through TenantQueryGuard. The suite cannot prove the
            // re-check happens — FinanceTenantIsolationIntegrationTest can, and does — so what it proves
            // is that the request surface does name projects, and that nothing on it pretends naming one
            // is enough.
            List<String> fieldNames = new ArrayList<>();
            for (RecordComponent component : requestComponents()) {
                fieldNames.add(component.getName());
            }
            assertThat(fieldNames)
                .as("a body may name a project; the guard below decides whether it may be used")
                .contains("projectId");
            assertThat(noRequestFieldIsAPaymentAndNoneSuppliesRevenue())
                .as("and there is no second, unguarded way to express authority on this surface")
                .isEmpty();
        }

        private List<String> noRequestFieldIsAPaymentAndNoneSuppliesRevenue() {
            List<String> forbidden = new ArrayList<>(List.of("payment", "invoice", "mandate", "iban",
                "cardnumber", "cardholder", "pan", "cvc", "acquirer", "processor", "charge", "payout",
                "settlement", "revenue", "markup", "unitprice", "unit_price", "priceamount"));
            List<String> offenders = new ArrayList<>();
            for (RecordComponent component : requestComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                for (String candidate : forbidden) {
                    if (name.contains(candidate)) {
                        offenders.add(component.getName());
                    }
                }
            }
            return offenders;
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static List<RecordComponent> requestComponents() {
        List<RecordComponent> components = new ArrayList<>();
        for (Class<?> nested : FinanceController.class.getDeclaredClasses()) {
            if (nested.isRecord()) {
                components.addAll(List.of(nested.getRecordComponents()));
            }
        }
        return components;
    }

    private static boolean isHandler(Method method) {
        for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.startsWith("org.springframework.web.bind.annotation")
                && (name.endsWith("Mapping") || name.endsWith("RequestMapping"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean takesCaller(Method method) {
        Parameter[] parameters = method.getParameters();
        return parameters.length > 0 && parameters[0].getType() == TenantContext.class;
    }

    /**
     * Every Spring service in this module.
     *
     * <p>Discovered from the code source rather than listed, so a service added in a later phase is
     * covered without editing this test — which is the whole reason the suite is reflective. A test
     * that enumerated today's services would be a snapshot, and snapshots of authorization are the
     * thing that quietly stops matching reality.
     *
     * <p>Restricted to beans on purpose. {@code LedgerPrincipal.from(TenantContext)} is a mapper and
     * {@code UsagePeriodResolver.principalOf(TenantContext)} resolves a principal's recorded identity;
     * neither authorizes anything and neither is reachable without the service that does. Requiring a
     * permission on a value-object factory would be noise that trains people to add the annotation
     * without reading it.
     */
    private static List<Class<?>> financeServices() {
        Path root;
        try {
            root = Path.of(FinanceController.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate the module's compiled classes; the finance "
                + "permission suite refuses to pass by scanning nothing.", e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Expected a classes directory at " + root + "; the finance "
                + "permission suite refuses to pass by scanning nothing.");
        }

        Path packageRoot = root.resolve("com/firstelement/finance");
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(packageRoot)) {
            List<Path> files = walk.filter(path -> path.toString().endsWith(".class"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
            for (Path file : files) {
                String binary = root.relativize(file).toString()
                    .replace(java.io.File.separatorChar, '.')
                    .replaceAll("\\.class$", "");
                Class<?> type = Class.forName(binary);
                if (type.isAnnotationPresent(org.springframework.stereotype.Service.class)) {
                    classes.add(type);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to enumerate the module's classes", e);
        }

        if (classes.isEmpty()) {
            throw new IllegalStateException("The module's compiled classes contain no finance classes; "
                + "the permission suite would pass vacuously.");
        }
        return classes;
    }

    /**
     * A class with one mapped handler that declares a permission and one that does not.
     *
     * <p>Exists so {@link Handlers#theScannerIsNotVacuous()} can prove the scanner detects the defect it
     * claims to detect. Deliberately <em>not</em> a {@code @RestController}: it is a test fixture, and a
     * bean-scanned fixture would join every {@code @SpringBootTest} in the module's context — where it
     * promptly fails, because {@code @RequirePermission} needs an evaluation bean the fixture has no
     * business depending on.
     */
    static final class BadlyDeclaredController {

        @org.springframework.web.bind.annotation.GetMapping("/declared")
        @RequirePermission(Permission.ANALYTICS_READ_ORGANIZATION)
        String declaredRead() {
            return "declared";
        }

        @org.springframework.web.bind.annotation.GetMapping("/undeclared")
        String undeclaredRead() {
            return "undeclared";
        }
    }
}