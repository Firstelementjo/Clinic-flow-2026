package com.firstelement.exports;

import com.firstelement.exports.catalog.ReportDefinitionService;
import com.firstelement.exports.persistence.ReportCatalogRepository;
import com.firstelement.exports.retention.RetentionWindowResolver;
import com.firstelement.security.PrincipalType;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.query.TenantScope;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds two tenants, two projects in one of them, and the rows an export reads.
 *
 * <h2>Why the reference rows are raw SQL and the catalogue is not</h2>
 *
 * <p>{@code organizations}, {@code projects}, {@code users} and {@code customers} belong to other modules.
 * Driving their services to seed them would put an unrelated module's behaviour into this suite's failure
 * surface — a customer-service regression would read as an export regression — so the fixture writes the
 * minimum each table needs directly.
 *
 * <p>The report catalogue is the opposite case: it is <em>this</em> module's own data, and authoring it
 * through {@link ReportDefinitionService} means the fixture exercises the real validation (every column
 * resolved, dimensions that really are dimensions, a money column with a currency beside it). A catalogue
 * inserted by raw SQL could be one the module would have refused, which would make the export tests prove
 * something the module does not allow.
 *
 * <h2>Arabic in both tenants, on purpose</h2>
 *
 * <p>An export of a clinic that cannot represent Arabic is broken, and an ASCII fixture would make every
 * encoding assertion vacuous. Tenant B carries Arabic too, so "tenant A's file has no Arabic" can never be
 * confused with "the Arabic never made it into the database".
 */
@Component
class ExportsFixture {

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportDefinitionService definitions;
    private final com.firstelement.exports.format.DocumentTemplateService templates;
    private final com.firstelement.exports.storage.ExportObjectStore store;

    ExportsFixture(NamedParameterJdbcTemplate jdbc,
                   ReportDefinitionService definitions,
                   com.firstelement.exports.format.DocumentTemplateService templates,
                   com.firstelement.exports.storage.ExportObjectStore store) {
        this.jdbc = jdbc;
        this.definitions = definitions;
        this.templates = templates;
        this.store = store;
    }

    // ------------------------------------------------------------------
    // principals
    // ------------------------------------------------------------------

    /** Tenant A's administrator, holding both of its projects. */
    TenantContext adminA() {
        return user(ExportsTestBase.ORG_A, ExportsTestBase.ADMIN_A, UserRole.ADMIN,
            Set.of(ExportsTestBase.PROJECT_A, ExportsTestBase.PROJECT_A2));
    }

    /** Tenant A's administrator holding only {@code PROJECT_A2}, for the membership negatives. */
    TenantContext adminAWithoutProjectA() {
        return user(ExportsTestBase.ORG_A, ExportsTestBase.ADMIN_A, UserRole.ADMIN,
            Set.of(ExportsTestBase.PROJECT_A2));
    }

    /**
     * Tenant A's employee.
     *
     * <p>An EMPLOYEE holds {@code ANALYTICS_READ_PROJECT} but not
     * {@code ANALYTICS_READ_ORGANIZATION}, and every {@code usage.*} field requires the latter — so an
     * employee's finance export is refused rather than produced with empty money columns. That is the
     * behaviour this principal exists to prove.
     */
    TenantContext employeeA() {
        return user(ExportsTestBase.ORG_A, ExportsTestBase.EMPLOYEE_A, UserRole.EMPLOYEE,
            Set.of(ExportsTestBase.PROJECT_A));
    }

    /** Tenant B's administrator. */
    TenantContext adminB() {
        return user(ExportsTestBase.ORG_B, ExportsTestBase.ADMIN_B, UserRole.ADMIN,
            Set.of(ExportsTestBase.PROJECT_B));
    }

    /** A machine principal: no user id, so an export it requests is unattributable and refused. */
    TenantContext workerA() {
        return ExportsPrincipalScope.worker(ExportsTestBase.ORG_A, ExportsTestBase.PROJECT_A);
    }

    private TenantContext user(UUID organizationId, UUID userId, UserRole role, Set<UUID> projectIds) {
        return TenantContext.forUser(UUID.nameUUIDFromBytes(
                ("session-" + userId).getBytes(StandardCharsets.UTF_8)),
            userId, organizationId, role, projectIds);
    }

    // ------------------------------------------------------------------
    // scopes
    // ------------------------------------------------------------------

    TenantScope scopeA(UUID projectId) {
        return new TenantScope(ExportsTestBase.ORG_A, projectId);
    }

    TenantScope scopeB() {
        return new TenantScope(ExportsTestBase.ORG_B, ExportsTestBase.PROJECT_B);
    }

    // ------------------------------------------------------------------
    // reset
    // ------------------------------------------------------------------

    /**
     * Every table the fixture writes.
     *
     * <p>{@code TRUNCATE ... CASCADE}, not {@code DELETE}: the module's audit rows carry a composite foreign
     * key to {@code users}, and every test that authorises anything writes one. A per-table {@code DELETE}
     * list would therefore have to enumerate every table in the schema that any code path can reach — which
     * is exactly the kind of list that is correct on the day it is written and wrong after the next feature.
     * The cascade finds the referrers instead.
     *
     * <p>This is destructive, so it runs only against the throwaway container this suite starts. It is
     * harness code, never production: no source file under {@code src/main} executes it.
     */
    private static final List<String> RESETTABLE = List.of(
        "export_deletion_events",
        "export_download_events",
        "export_job_attempts",
        "export_jobs",
        "document_template_versions",
        "document_templates",
        "report_versions",
        "report_definitions",
        "usage_rollup_snapshots",
        "conversations",
        "customers",
        "project_memberships",
        "projects",
        "users",
        "organizations");

    /** Clears every table the fixture writes, so no test can pass on a previous test's rows. */
    void reset() {
        jdbc.getJdbcTemplate().execute("truncate table "
            + String.join(", ", RESETTABLE) + " restart identity cascade");
    }

    /** Two tenants, two projects in tenant A, and one user each. */
    void seedOrganizationsAndUsers() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        insertOrganization(ExportsTestBase.ORG_A, "tenant-a", now);
        insertOrganization(ExportsTestBase.ORG_B, "tenant-b", now);
        insertProject(ExportsTestBase.ORG_A, ExportsTestBase.PROJECT_A, "project-a", now);
        insertProject(ExportsTestBase.ORG_A, ExportsTestBase.PROJECT_A2, "project-a2", now);
        insertProject(ExportsTestBase.ORG_B, ExportsTestBase.PROJECT_B, "project-b", now);
        insertUser(ExportsTestBase.ORG_A, ExportsTestBase.ADMIN_A, "admin-a", UserRole.ADMIN, now);
        insertUser(ExportsTestBase.ORG_A, ExportsTestBase.EMPLOYEE_A, "employee-a", UserRole.EMPLOYEE, now);
        insertUser(ExportsTestBase.ORG_B, ExportsTestBase.ADMIN_B, "admin-b", UserRole.ADMIN, now);
    }

    private void insertOrganization(UUID organizationId, String slug, LocalDateTime now) {
        jdbc.update("""
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)
            """, new MapSqlParameterSource().addValue("id", organizationId)
            .addValue("name", "منظمة " + slug).addValue("slug", slug).addValue("now", now));
    }

    private void insertProject(UUID organizationId, UUID projectId, String slug, LocalDateTime now) {
        jdbc.update("""
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)
            """, new MapSqlParameterSource().addValue("id", projectId).addValue("org", organizationId)
            .addValue("name", "مشروع " + slug).addValue("slug", slug).addValue("now", now));
    }

    private void insertUser(UUID organizationId, UUID userId, String key, UserRole role,
                            LocalDateTime now) {
        jdbc.update("""
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, :hash, :fullName, cast(:role as "UserRole"), true, :now, :now)
            """, new MapSqlParameterSource().addValue("id", userId).addValue("org", organizationId)
            .addValue("email", key + "@example.invalid")
            // Never a real hash and never read by anything: this module never selects a credential
            // column, which is asserted in ExportQueryPlannerTest.
            .addValue("hash", "x".repeat(64)).addValue("fullName", key).addValue("role", role.name())
            .addValue("now", now));
    }

    // ------------------------------------------------------------------
    // customers
    // ------------------------------------------------------------------

    void customerInProjectA() {
        insertCustomer(ExportsTestBase.CUSTOMER_A1, ExportsTestBase.ORG_A, ExportsTestBase.PROJECT_A,
            "عميل الاختبار", "customer-a1@example.invalid", "ar");
    }

    void secondCustomerInProjectA() {
        insertCustomer(ExportsTestBase.CUSTOMER_A2, ExportsTestBase.ORG_A, ExportsTestBase.PROJECT_A,
            "عميل ثان", "customer-a2@example.invalid", "en");
    }

    /** A customer in tenant A's OTHER project, so the project predicate is exercised independently. */
    void customerInProjectA2() {
        insertCustomer(UUID.nameUUIDFromBytes("customer-a3".getBytes(StandardCharsets.UTF_8)),
            ExportsTestBase.ORG_A, ExportsTestBase.PROJECT_A2, "عميل المشروع الثاني",
            "customer-a3@example.invalid", "ar");
    }

    /** A customer in tenant B, whose rows tenant A must never see or count. */
    void customerInTenantB() {
        insertCustomer(ExportsTestBase.CUSTOMER_B1, ExportsTestBase.ORG_B, ExportsTestBase.PROJECT_B,
            "عميل الطرف الآخر", "customer-b1@example.invalid", "ar");
    }

    /**
     * {@code count} customers in tenant A's {@code PROJECT_A}, for the bounded-memory assertion.
     *
     * <p>The Arabic display name grows with the index so the fixture cannot be optimised into a constant
     * by the driver, and so a truncated export would be visible in the bytes rather than only in a count.
     */
    void customersInProjectA(int count) {
        for (int i = 0; i < count; i++) {
            insertCustomer(UUID.nameUUIDFromBytes(("bulk-" + i).getBytes(StandardCharsets.UTF_8)),
                ExportsTestBase.ORG_A, ExportsTestBase.PROJECT_A,
                "عميل رقم " + i, "bulk-" + i + "@example.invalid", "ar",
                // customers."cxId" is varchar(9) and carries a per-tenant unique index, so a bulk seed
                // needs a distinct code per row rather than one derived from the uuid — two uuids whose
                // hashes collide would otherwise make the seed itself the failure.
                String.format("B%07d", i));
        }
    }

    private void insertCustomer(UUID customerId, UUID organizationId, UUID projectId, String displayName,
                                String email, String locale) {
        insertCustomer(customerId, organizationId, projectId, displayName, email, locale,
            String.format("C%07d", Math.abs((long) customerId.getMostSignificantBits() % 100_000_000L)));
    }

    private void insertCustomer(UUID customerId, UUID organizationId, UUID projectId, String displayName,
                                String email, String locale, String cxId) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("""
            insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                "primaryEmail", "locale", "firstSeenAt", "lastSeenAt", "createdAt", "updatedAt")
            values (:id, :org, :project, :cxId, :name, :email, cast(:locale as "Locale"), :now, :now, :now, :now)
            """, new MapSqlParameterSource().addValue("id", customerId).addValue("org", organizationId)
            .addValue("project", projectId).addValue("cxId", cxId)
            .addValue("name", displayName).addValue("email", email).addValue("locale", locale)
            .addValue("now", now));
    }

    // ------------------------------------------------------------------
    // the report catalogue, authored through the real service
    // ------------------------------------------------------------------

    /**
     * A published customer-directory report for one project.
     *
     * <p>Column set: display name (dimension), the count (measure), the contact email (dimension) and the
     * first-seen timestamp. The email is included on purpose — it is the field a
     * {@code NO_CONTACT_DETAILS} policy withholds, so one fixture covers both the emitted and the withheld
     * case without a second catalogue entry.
     */
    UUID publishedCustomerReport(TenantContext caller, UUID projectId, String redactionPolicy) {
        return ExportsPrincipalScope.call(caller, () -> {
            ReportCatalogRepository.DefinitionRow definition = definitions.createDefinition(caller,
                // The key carries the policy, not just the project. `report_definitions` is unique on
                // (organizationId, key), and the key was project-only, so a second report for the SAME
                // project -- which is exactly how a test asks for "the same shape, stricter redaction" --
                // collided with the setup row and failed with DuplicateKeyException. That is the correct
                // constraint doing its job: one project may not hold two definitions under one key.
                projectId, "customer-directory-" + projectId + "-" + redactionPolicy,
                "دليل العملاء", "Customer directory",
                "تقرير العملاء", "Customer report", "Asia/Riyadh", "ar",
                List.of("customer:read"), redactionPolicy, "EXPORT_ARTEFACTS");

            definitions.createVersion(caller, definition.id(),
                List.of("customer.displayName", "customer.count", "customer.primaryEmail",
                    "customer.firstSeenAt"),
                List.of("customer.displayName", "customer.primaryEmail", "customer.firstSeenAt"),
                List.of("customer.count"),
                Map.of(),
                List.of("customer.displayName:asc"),
                Map.of(),
                Map.of("customer.count", "a count of the authorized rows, never of the filtered set"),
                "the initial published shape");

            definitions.publish(caller, definition.id(), 1);
            return definition.id();
        });
    }

    /**
     * A published usage-ledger report: cost, revenue and margin beside their currency.
     *
     * <p>Every {@code usage.*} field requires {@code ANALYTICS_READ_ORGANIZATION}, so this report is the
     * one an employee cannot have — which is what makes it the fixture for the download re-authorization
     * negative as well as for the finance gate.
     */
    UUID publishedUsageReport(TenantContext caller, UUID projectId) {
        return ExportsPrincipalScope.call(caller, () -> {
            ReportCatalogRepository.DefinitionRow definition = definitions.createDefinition(caller,
                projectId, "usage-ledger-" + projectId, "سجل الاستخدام", "Usage ledger",
                "تقرير الاستخدام", "Usage report", "Asia/Riyadh", "ar",
                List.of("analytics:read-organization"), "none", "EXPORT_ARTEFACTS");

            definitions.createVersion(caller, definition.id(),
                List.of("usage.dimension", "usage.bucketStart", "usage.cost", "usage.revenue",
                    "usage.margin", "usage.currency"),
                List.of("usage.dimension", "usage.currency", "usage.bucketStart"),
                List.of("usage.cost", "usage.revenue", "usage.margin"),
                Map.of(),
                List.of("usage.dimension:asc"),
                Map.of(),
                Map.of("usage.revenue",
                    "the derived revenue on the snapshot; a group containing an unpriced bucket is null, "
                        + "never a summed zero"),
                "the initial published shape");

            definitions.publish(caller, definition.id(), 1);
            return definition.id();
        });
    }

    /**
     * A published usage report whose <b>definition</b> requires only a permission an EMPLOYEE holds, while
     * every one of its <b>columns</b> requires one they do not.
     *
     * <p>This exists to reach {@link com.firstelement.exports.job.ExportJobService.EmptyExportRefusal} over
     * HTTP, and the distinction is load-bearing. {@code publishedUsageReport} declares
     * {@code analytics:read-organization} on the definition, so an employee is stopped one step earlier by
     * {@code requireVersionPermissions} and the exception that escapes is Spring's
     * {@code AccessDeniedException} — answered by {@code SecurityExceptionAdvice}, not by
     * {@code ExportExceptionHandler}. Authoring the same columns under a definition requiring only
     * {@code analytics:read-project} gets past that check, and then every column is withheld by the redaction
     * resolver, so the refusal is the export module's own 403. Anything testing which advice answers needs the
     * exception to actually belong to the module it is attributing it to.
     */
    UUID publishedUsageReportWithProjectScopeOnly(TenantContext caller, UUID projectId) {
        return ExportsPrincipalScope.call(caller, () -> {
            ReportCatalogRepository.DefinitionRow definition = definitions.createDefinition(caller,
                projectId, "usage-ledger-project-scope-" + projectId, "سجل الاستخدام (مشروع)",
                "Usage ledger (project scope)", "تقرير الاستخدام", "Usage report", "Asia/Riyadh", "ar",
                // The permission the EMPLOYEE holds, so the definition check passes...
                List.of("analytics:read-project"), "none", "EXPORT_ARTEFACTS");

            definitions.createVersion(caller, definition.id(),
                // ...while every column below requires ANALYTICS_READ_ORGANIZATION, which they do not.
                List.of("usage.dimension", "usage.currency", "usage.cost"),
                List.of("usage.dimension", "usage.currency"),
                List.of("usage.cost"),
                Map.of(),
                List.of("usage.dimension:asc"),
                Map.of(),
                Map.of(),
                "every column requires a permission the caller is refused at column resolution");

            definitions.publish(caller, definition.id(), 1);
            return definition.id();
        });
    }

    /**
     * One <b>unpriced</b> usage bucket for the ledger subject, in the current generation.
     *
     * <p>Unpriced, deliberately and by schema: {@code usage_rollup_snapshots_pricing_pin_check} is a
     * biconditional — priced means all three of {@code rateCardVersionId}, {@code markupRate} and
     * {@code revenueAmount} are present, unpriced means all three are absent — and
     * {@code usage_rollup_snapshots_derivation_check} requires a revenue to equal its own inputs exactly.
     * A priced row therefore needs a real rate-card version, which is another module's fixture. This row is
     * the one that makes ADR-0007 rule 5 observable: {@code costAmount} is a real number, and the export's
     * revenue must come out absent rather than zero.
     */
    void unpricedUsageBucket(UUID organizationId, UUID projectId, String currency) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("""
            insert into usage_rollup_snapshots ("id", "organizationId", "projectId", "grain",
                "bucketStart", "bucketEnd", "dimension", "currency", "sourceEventCount", "quantity",
                "costAmount", "unpricedQuantity", "unpricedCostAmount", "generation", "computedAt",
                "computedByPrincipal", "createdAt")
            values (:id, :org, :project, cast('DAILY' as "RollupGrain"), cast('2026-03-10' as date),
                cast('2026-03-11' as date), cast('MESSAGE' as "UsageDimension"), :currency, 10,
                cast('10.000000' as numeric), cast('0.000000' as numeric), cast('10.000000' as numeric),
                cast('0.000000' as numeric), 1, :now, 'integration-test', :now)
            """, new MapSqlParameterSource().addValue("id", UUID.nameUUIDFromBytes(
                    ("usage-" + organizationId + "-" + projectId).getBytes(StandardCharsets.UTF_8)))
            .addValue("org", organizationId).addValue("project", projectId)
            .addValue("currency", currency).addValue("now", now));
    }

    /**
     * Declares the export artefact retention class for this tenant.
     *
     * <p>Until a tenant has a row here, {@code RetentionClassResolver} stamps every export job with the
     * {@code UNDECLARED_RETENTION_CLASS} sentinel and {@code RetentionWindowResolver.isPurgeable} refuses
     * it. That is ADR-0009 §4 rule 4 working, and it is why a purge rehearsal must declare the class
     * before its counters mean anything.
     */
    void declareRetentionClass() {
        jdbc.update("""
            insert into retention_classes ("id", "organizationId", "projectId", "stream", "classCode",
                "retentionDays", "legalHoldExempt", "description", "createdAt", "updatedAt")
            values (:id, :__fe_tenant_organization_id, cast(null as uuid),
                cast('ADMIN' as "AuditStreamKind"), :classCode, 30, false, 'export artefacts',
                :now, :now)
            """, new MapSqlParameterSource()
            .addValue("__fe_tenant_organization_id", ExportsTestBase.ORG_A)
            .addValue("id", UUID.nameUUIDFromBytes("retention-export-a".getBytes(StandardCharsets.UTF_8)))
            .addValue("classCode", RetentionWindowResolver.EXPORT_ARTEFACT_CLASS)
            .addValue("now", LocalDateTime.now(java.time.ZoneOffset.UTC)));
    }

    // ------------------------------------------------------------------
    // document templates
    // ------------------------------------------------------------------

    /**
     * A published DOCX template, authored through the real service so its object exists in the store.
     *
     * <p>Raw SQL could not produce this: the version row must carry a real object-storage key and a real
     * content hash, and the store is an interface this module does not hand out. Authoring it through
     * {@link com.firstelement.exports.format.DocumentTemplateService} is also what proves the catalogue's
     * claim that a version is exportable — the claim is that the stored document is present, and the only
     * honest way to make that true in a fixture is to store one.
     */
    UUID publishedDocxTemplate(TenantContext caller, UUID projectId, String locale) {
        return ExportsPrincipalScope.call(caller, () -> {
            ReportCatalogRepository.TemplateRow template = templates.createTemplate(caller, projectId,
                "customer-directory-docx-" + projectId + "-" + locale,
                "دليل العملاء", "Customer directory", locale, "Asia/Riyadh");
            byte[] document = minimalDocx();
            ReportCatalogRepository.TemplateVersionRow version =
                templates.createVersion(caller, template.id(), document, locale,
                    "the initial published template");
            templates.publish(caller, template.id(), 1);

            // The round trip this fixture never made before, and whose absence is why a template whose
            // object stayed at its .partial sibling shipped: createVersion returned a row, nothing here
            // read the object back, and every DOCX export failed later inside the renderer. Resolving by
            // the row's OWN key is the exact call the generation path makes, so a template this fixture
            // returns is a template an export can actually render from.
            // A tenant scope: template resolution filters on the organization, and the generation path
            // builds its own scope from the JOB's project before resolving.
            com.firstelement.security.query.TenantScope tenantScope =
                new com.firstelement.security.query.TenantScope(caller.organizationId(), null);
            byte[] stored;
            try {
                stored = store.read(
                    templates.resolve(tenantScope, template.id(), 1).version().storageKey());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(
                    "the fixture authored a template whose stored object is not readable at the key its "
                        + "own version row names, so no DOCX export could ever render from it", e);
            }
            if (!java.util.Arrays.equals(document, stored)) {
                throw new IllegalStateException(
                    "the fixture authored a template whose stored bytes differ from the document it wrote");
            }
            return template.id();
        });
    }

    /**
     * The smallest DOCX the service will accept: a real OOXML package naming one declared report field.
     *
     * <p>A real package rather than a stub, because {@code requireReadableDocx} reads the bytes as an
     * {@code XWPFDocument} and a hand-rolled zip would make the fixture assert something the module would
     * never accept.
     */
    private static byte[] minimalDocx() {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document =
                 new org.apache.poi.xwpf.usermodel.XWPFDocument();
             java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("{{report.customer.displayName}}");
            document.write(sink);
            return sink.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the fixture could not build a DOCX template", e);
        }
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    /** An {@code UPDATE} the harness itself needs, used only to backdate a retention window. */
    void execute(String sql, Map<String, Object> parameters) {
        jdbc.update(sql, params(parameters));
    }

    /**
     * How many rows a query returns, as a number.
     *
     * <p>Counted from the list rather than read with {@code queryForObject}, because the callers write
     * {@code select 1 from … where …} and the honest answer to "no rows matched" is <b>zero</b>.
     * {@code queryForObject} raises {@code EmptyResultDataAccessException} there, which turns the most
     * important half of every negative assertion in this suite — the one that must prove nothing was
     * written — into an exception.
     */
    int count(String sql, Map<String, Object> parameters) {
        return jdbc.queryForList(sql, params(parameters), Integer.class).size();
    }

    /** One column of one row. */
    <T> T queryOne(String sql, Map<String, Object> parameters, Class<T> type) {
        return jdbc.queryForObject(sql, params(parameters), type);
    }

    /** Every row of one column. */
    <T> List<T> queryList(String sql, Map<String, Object> parameters, Class<T> type) {
        return jdbc.queryForList(sql, params(parameters), type);
    }

    private static MapSqlParameterSource params(Map<String, Object> parameters) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        parameters.forEach(source::addValue);
        return source;
    }
}
