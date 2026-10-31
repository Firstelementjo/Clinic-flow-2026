package com.firstelement.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The finance fixture: two tenants, the real Flyway chain, and one row of every kind the finance read
 * models claim to show.
 *
 * <h2>Two tenants, always</h2>
 *
 * <p>Every Phase-8 acceptance gate has a cross-tenant negative, so the fixture cannot be one tenant.
 * Tenant B also holds a rate card, usage events, snapshots and a finance period, so a reader that
 * forgot a tenant predicate would produce a visibly wrong number rather than a plausible one — which is
 * the failure mode a tenant test is most prone to.
 *
 * <h2>What is seeded, and why each row exists</h2>
 *
 * <p>Not decoration. Every seeded row makes one claim falsifiable:
 *
 * <ul>
 *   <li>an <b>ACTIVE rate-card version</b> with a markup and a line, so a priced bucket is real rather
 *       than hypothetical;</li>
 *   <li>a <b>usage event with no rate on its date</b>, so "unpriced" is a row in the fixture rather
 *       than a branch nobody exercises;</li>
 *   <li>a <b>usage event whose cost is genuinely zero</b>, so "priced zero" is distinguishable from
 *       "unpriced" by data rather than by argument;</li>
 *   <li>a <b>day with no usage at all</b>, so "no data" exists as a gap in the middle of a populated
 *       window;</li>
 *   <li>a <b>legacy ai_usage row with estimatedCost 0</b>, so the legacy-versus-new comparison has the
 *       exact case ADR 0009 §3 is about.</li>
 * </ul>
 *
 * <h2>No skip path</h2>
 *
 * <p>{@link #POSTGRES} is started by the test base and every property supplier starts it first, so a
 * Docker problem fails the build rather than turning a finance suite green.
 */
@Component
public class FinanceFixture {

    public static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("firstelement_finance")
            .withUsername("firstelement")
            .withPassword("firstelement");

    public static final UUID ORG_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    public static final UUID ORG_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    public static final UUID PROJECT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");
    public static final UUID PROJECT_A2 = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a2");
    public static final UUID PROJECT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000b1");

    public static final UUID ADMIN_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e1");
    public static final UUID EMPLOYEE_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e2");
    public static final UUID ADMIN_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000e4");
    public static final UUID APPROVER_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000e6");

    /** A fixed month, so "March" is reproducible rather than "this month". */
    public static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    public static final LocalDate MARCH_31 = LocalDate.of(2026, 3, 31);
    public static final LocalDate FEB_1 = LocalDate.of(2026, 2, 1);
    public static final LocalDate FEB_28 = LocalDate.of(2026, 2, 28);
    public static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    public static final LocalDate APRIL_30 = LocalDate.of(2026, 4, 30);

    /**
     * The string that must never appear in a finance read model.
     *
     * <p>Seeded into {@code usage_events.metadata}. A usage event's metadata is free-form jsonb and is
     * exactly where a customer-shaped value would end up, so the drill-through projects its KEYS and
     * not its values — and this marker is what proves it.
     */
    public static final String METADATA_MARKER = "patient-date-of-birth-1984-02-11";

    /** The marker a legacy run carries in its model name, to prove the comparison reads models. */
    public static final String LEGACY_UNKNOWN_MODEL = "some-model-not-in-the-price-table";

    /**
     * Tables truncated in child-to-parent order.
     *
     * <p>The AI chain is seeded through {@code customers -> conversations -> ai_agents ->
     * ai_agent_versions -> ai_runs -> ai_usage}, because {@code ai_runs} has three NOT NULL foreign keys
     * into it ({@code conversationId}, {@code agentId}, {@code agentVersionId}) and {@code ai_usage} has
     * a NOT NULL {@code runId}. Seeding only {@code ai_usage} would need those rows anyway, so the
     * fixture builds the real chain — which is also what makes the legacy comparison honest: the legacy
     * figures sit under a run that exists, not under a synthetic id.
     */
    private static final String[] TABLES = {
        "finance_reconciliation_lines", "finance_reconciliation_runs", "finance_adjustments",
        "finance_period_lock_events", "usage_rollup_snapshots", "usage_events",
        "rate_card_version_lines", "rate_card_versions", "rate_cards", "finance_periods",
        "ai_usage", "ai_run_steps", "ai_run_retrievals", "ai_run_tool_calls", "ai_runs",
        "ai_agent_versions", "ai_agents",
        "admin_audit_events", "security_events", "job_integration_events",
        "retention_classes", "legal_hold_events", "legal_holds",
        "messages", "conversations", "customers",
        "project_memberships", "channel_connections", "users", "projects", "organizations",
    };

    private final NamedParameterJdbcTemplate jdbc;

    public FinanceFixture(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The UTC clock the fixture and the tests agree on. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** A fixed instant inside March, so every seeded event shares a bucket. */
    public static LocalDateTime inMarch(int day, int hour) {
        return LocalDate.of(2026, 3, day).atTime(hour, 0);
    }

    /**
     * The tables carrying {@code firstelement_append_only()}.
     *
     * <p>Listed by name rather than discovered, because the fixture must disable exactly the Phase-7-10a
     * guards and must NOT disable {@code usage_rollup_snapshots_locked_period_guard} even briefly: a
     * disabled insert guard would let a test seed a snapshot into a locked period, and the assertion that
     * the database refuses it is one of the acceptance gates. So the insert trigger is never in this
     * list and the reset never touches it.
     *
     * <p>{@code rate_card_versions} is here rather than omitted because its guard is the narrower
     * {@code firstelement_rate_card_version_guard()} — still a {@code rate_card_versions_append_only}
     * trigger, and still refusing every {@code DELETE}. The trigger name is what
     * {@link #enabledAppendOnlyGuards()} matches on, so it counts as one of the seven.
     */
    private static final List<String> APPEND_ONLY_TABLES = List.of(
        "usage_events", "usage_rollup_snapshots", "rate_card_versions",
        "rate_card_version_lines", "finance_period_lock_events", "finance_reconciliation_runs",
        "finance_reconciliation_lines");

    /**
     * Truncates and re-seeds. Children first, parents last.
     *
     * <h2>Why the append-only triggers are disabled</h2>
     *
     * <p>Because {@code delete from usage_events} is refused by {@code firstelement_append_only()} — which
     * is the behaviour {@code UsageEventWriterIntegrationTest} asserts. A fixture that could not reset
     * would have to give every suite its own container, and a suite that seeds the same rows into a
     * shared container without being able to clear them would leak state between tests, which is worse
     * than either.
     *
     * <p>So the guards are suspended for the duration of the reset and restored immediately, and the
     * restoration is asserted rather than assumed — a fixture that silently left a guard off would make
     * every append-only assertion in the suite pass for the wrong reason.
     *
     * <p>Assertion on state afterwards, never on the message: the count of disabled triggers is read back
     * from {@code pg_trigger} rather than trusted from the {@code ALTER} returning.
     */
    public void reset() {
        suspendGuards();
        try {
            for (String table : TABLES) {
                jdbc.update("delete from " + table, new MapSqlParameterSource());
            }
        } finally {
            restoreGuards();
        }
        // The AI chain is memoised per project so several runs in one window share an agent; the memo
        // has to be cleared with the rows, or a second reset would hand out ids that no longer exist.
        memoized.clear();

        org(ORG_A, "Alpha Clinic", "alpha-clinic");
        org(ORG_B, "Beta Clinic", "beta-clinic");
        project(PROJECT_A, ORG_A, "Alpha One", "alpha-one");
        project(PROJECT_A2, ORG_A, "Alpha Two", "alpha-two");
        project(PROJECT_B, ORG_B, "Beta One", "beta-one");

        user(ADMIN_A, ORG_A, "admin@a.test", "ADMIN");
        user(APPROVER_A, ORG_A, "approver@a.test", "ADMIN");
        user(EMPLOYEE_A, ORG_A, "staff@a.test", "EMPLOYEE");
        user(ADMIN_B, ORG_B, "admin@b.test", "ADMIN");

        membership(ADMIN_A, PROJECT_A);
        membership(ADMIN_A, PROJECT_A2);
        membership(APPROVER_A, PROJECT_A);
        membership(EMPLOYEE_A, PROJECT_A);
        membership(ADMIN_B, PROJECT_B);
    }

    // ------------------------------------------------------------------
    // seeders
    // ------------------------------------------------------------------

    private void org(UUID id, String name, String slug) {
        seed("""
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            Map.of("id", id, "name", name, "slug", slug));
    }

    private void project(UUID id, UUID org, String name, String slug) {
        seed("""
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            Map.of("id", id, "org", org, "name", name, "slug", slug));
    }

    /** A user. Two administrators in tenant A so self-approval can be tested. */
    public void user(UUID id, UUID org, String email, String role) {
        seed("""
            insert into users ("id", "organizationId", "email", "passwordHash", "fullName", "role",
                               "locale", "isActive", "createdAt", "updatedAt")
            values (:id, :org, :email, 'not-a-real-hash', :name, cast(:role as "UserRole"),
                    cast('ar' as "Locale"), true, :now, :now)""",
            Map.of("id", id, "org", org, "email", email, "role", role, "name", "Seeded User"));
    }

    private void membership(UUID userId, UUID projectId) {
        seed("""
            insert into project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
            values (:id, (select u."organizationId" from users u where u."id" = :user), :project, :user,
                    :now)""",
            Map.of("id", UUID.randomUUID(), "user", userId, "project", projectId));
    }

    // ------------------------------------------------------------------
    // rate cards
    // ------------------------------------------------------------------

    /**
     * A DRAFT rate-card version, with an optional line.
     *
     * <p>Seeded rather than authored through {@code RateCardService} so the fixture can produce states
     * the service deliberately refuses to reach — an overlapping version, a version with no line — which
     * are exactly the cases the reconciliation has to explain.
     */
    public UUID draftVersion(UUID org, UUID projectId, int versionNumber, String currency,
                             BigDecimal markupRate, LocalDate from, LocalDate to, String changeNote,
                             UUID authorId) {
        UUID cardId = rateCard(org, projectId, "standard", currency);
        return draftVersionOn(cardId, org, projectId, versionNumber, currency, markupRate, from, to,
            changeNote, authorId);
    }

    /** A DRAFT version on an existing card. */
    public UUID draftVersionOn(UUID cardId, UUID org, UUID projectId, int versionNumber,
                               String currency, BigDecimal markupRate, LocalDate from, LocalDate to,
                               String changeNote, UUID authorId) {
        UUID id = UUID.randomUUID();
        insert("""
            insert into rate_card_versions ("id", "organizationId", "projectId", "rateCardId",
                "versionNumber", "currency", "markupRate", "effectiveFrom", "effectiveTo", "status",
                "changeNote", "createdByUserId", "createdAt")
            values (:id, :org, :project, :card, :number, :currency, :markup, :from, :to,
                cast('DRAFT' as "RateCardStatus"), :note, :author, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId)
                .addValue("card", cardId).addValue("number", versionNumber)
                .addValue("currency", currency).addValue("markup", markupRate)
                .addValue("from", from).addValue("to", to).addValue("note", changeNote)
                .addValue("author", authorId));
        return id;
    }

    /**
     * An ACTIVE rate-card version.
     *
     * <p>Inserted directly rather than through the service's publish path, because the service's
     * {@code publish} inserts a <em>new</em> version (the schema's narrow guard makes an in-place
     * {@code DRAFT -> ACTIVE} impossible) and these tests need a specific version number and effective
     * range without the intermediate draft.
     */
    public UUID activeVersion(UUID org, UUID projectId, int versionNumber, String currency,
                              BigDecimal markupRate, LocalDate from, LocalDate to, String changeNote,
                              UUID authorId) {
        UUID cardId = rateCard(org, projectId, "standard", currency);
        return activeVersionOn(cardId, org, projectId, versionNumber, currency, markupRate, from, to,
            changeNote, authorId);
    }

    /** An ACTIVE version on a named card, so overlap tests can control which card. */
    public UUID activeVersionOn(UUID cardId, UUID org, UUID projectId, int versionNumber,
                                String currency, BigDecimal markupRate, LocalDate from, LocalDate to,
                                String changeNote, UUID authorId) {
        UUID id = UUID.randomUUID();
        insert("""
            insert into rate_card_versions ("id", "organizationId", "projectId", "rateCardId",
                "versionNumber", "currency", "markupRate", "effectiveFrom", "effectiveTo", "status",
                "changeNote", "publishedAt", "publishedByUserId", "createdByUserId", "createdAt")
            values (:id, :org, :project, :card, :number, :currency, :markup, :from, :to,
                cast('ACTIVE' as "RateCardStatus"), :note, :now, :author, :author, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId)
                .addValue("card", cardId).addValue("number", versionNumber)
                .addValue("currency", currency).addValue("markup", markupRate)
                .addValue("from", from).addValue("to", to).addValue("note", changeNote)
                .addValue("author", authorId));
        jdbc.update("""
            update rate_cards set "currentVersionId" = :version, "latestVersionNumber" = :number,
                "status" = cast('ACTIVE' as "RateCardStatus"), "version" = "version" + 1,
                "updatedAt" = :now
             where "id" = :card""",
            new MapSqlParameterSource().addValue("version", id).addValue("number", versionNumber)
                .addValue("card", cardId).addValue("now", now()));
        return id;
    }

    /**
     * An ACTIVE version on a card named by key.
     *
     * <p>{@link #rateCard} is keyed by (organization, key), so two projects in one tenant asking for
     * {@code "standard"} share a card — and {@code rate_card_versions_card_version_key} then refuses the
     * second project's version 1. A caller that needs a per-project card names one.
     */
    public UUID activeVersionOnKey(UUID org, UUID projectId, String key, int versionNumber,
                                   String currency, BigDecimal markupRate, LocalDate from,
                                   LocalDate to, String changeNote, UUID authorId) {
        return activeVersionOn(rateCard(org, projectId, key, currency), org, projectId, versionNumber,
            currency, markupRate, from, to, changeNote, authorId);
    }

/** A rate card, created once per (org, key). */
    public UUID rateCard(UUID org, UUID projectId, String key, String currency) {
        List<UUID> existing = jdbc.queryForList(
            "select \"id\" from rate_cards where \"organizationId\" = :org and \"key\" = :key",
            new MapSqlParameterSource().addValue("org", org).addValue("key", key), UUID.class);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        seed("""
            insert into rate_cards ("id", "organizationId", "projectId", "key", "nameAr", "nameEn",
                "currency", "status", "createdByUserId", "version", "createdAt", "updatedAt")
            values (:id, :org, :project, :key, 'بطاقة', 'Standard card', :currency,
                cast('DRAFT' as "RateCardStatus"), :author, 0, :now, :now)""",
            Map.of("id", id, "org", org, "project", projectId, "key", key, "currency", currency,
                "author", adminOf(org)));
        return id;
    }

    /**
     * An ACTIVE STORAGE rate, returned for the orphan-snapshot seed.
     *
     * <p>A snapshot row pins a {@code rateCardVersionId} through a composite tenant foreign key, so an
     * orphan cannot be seeded with a fabricated uuid — it needs a real version. Kept as its own method
     * because only one test needs it.
     */
    public UUID rateCardVersionForStorage() {
        // Its OWN card, because rate_card_versions_one_start_per_day is unique per (org, card,
        // effectiveFrom) and the MESSAGE version this test also creates already starts on 1 March.
        UUID cardId = rateCard(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, "storage-only",
            "USD");
        UUID version = activeVersionOn(cardId, FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, 1,
            "USD", new BigDecimal("2.000000"), FinanceFixture.MARCH_1, FinanceFixture.MARCH_31,
            "storage rate for the orphan-snapshot test", FinanceFixture.ADMIN_A);
        line(FinanceFixture.ORG_A, FinanceFixture.PROJECT_A, version, "STORAGE", "byte_day",
            null, new BigDecimal("2.000000"), "USD");
        return version;
    }

    /**
     * A priced line on a version.
     *
     * <p>{@code costAmount} and {@code markupRate} are mutually optional by design — the schema requires
     * at least one, and both shapes are real — so the bindings go through
     * {@link MapSqlParameterSource}, because {@code Map.of} refuses a null value and a line that takes
     * the version's default markup has exactly that.
     */
    public UUID line(UUID org, UUID projectId, UUID versionId, String dimension, String unit,
                     BigDecimal costAmount, BigDecimal markupRate, String currency) {
        UUID id = UUID.randomUUID();
        insert("""
            insert into rate_card_version_lines ("id", "organizationId", "projectId",
                "rateCardVersionId", "dimension", "unit", "costAmount", "markupRate", "currency",
                "createdAt")
            values (:id, :org, :project, :version, cast(:dimension as "UsageDimension"), :unit,
                :cost, :markup, :currency, :now)""",
            new MapSqlParameterSource()
                .addValue("id", id).addValue("org", org).addValue("project", projectId)
                .addValue("version", versionId).addValue("dimension", dimension)
                .addValue("unit", unit).addValue("cost", costAmount).addValue("markup", markupRate)
                .addValue("currency", currency));
        return id;
    }

    // ------------------------------------------------------------------
    // usage events
    // ------------------------------------------------------------------

    /**
     * A usage event, written by raw SQL.
     *
     * <p>Seeded rather than through {@code UsageEventWriter} because several tests need rows the
     * writer's validation would refuse: a zero cost, an event in a month with no rate card, and an
     * event carrying the metadata marker. The writer's own behaviour is tested separately against
     * events it accepts.
     */
    public UUID usageEvent(UUID org, UUID projectId, String dimension, BigDecimal quantity, String unit,
                           LocalDateTime occurredAt, LocalDate bucketStart, LocalDate bucketEnd,
                           UUID sourceId, String sourceType, BigDecimal cost, String currency,
                           UUID periodId, Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("org", org)
            .addValue("project", projectId)
            .addValue("dimension", dimension)
            .addValue("quantity", quantity)
            .addValue("unit", unit)
            .addValue("sourceId", sourceId)
            .addValue("sourceType", sourceType)
            .addValue("occurredAt", occurredAt)
            .addValue("bucketStart", bucketStart)
            .addValue("bucketEnd", bucketEnd)
            .addValue("cost", cost)
            .addValue("currency", currency)
            .addValue("periodId", periodId)
            .addValue("metadata", json(metadata))
            .addValue("now", now());
        jdbc.update("""
            insert into usage_events ("id", "organizationId", "projectId", "dimension", "quantity",
                "unit", "sourceEventType", "sourceEventId", "principalType", "principalName",
                "occurredAt", "periodStart", "periodEnd", "billingPeriodId", "currency", "costAmount",
                "metadata", "createdAt")
            values (:id, :org, :project, cast(:dimension as "UsageDimension"), :quantity, :unit,
                :sourceType, :sourceId, 'JOB', 'JOB:seeded', :occurredAt, :bucketStart, :bucketEnd,
                :periodId, :currency, :cost, cast(:metadata as jsonb), :now)""", parameters);
        return id;
    }

    /**
     * A usage event with the metadata marker, for the drill-through redaction assertion.
     *
     * <p>The unit comes from the catalogue rather than from the caller, so a test cannot accidentally
     * seed a unit the writer would refuse.
     *
     * <p>Both ids come back because they are two different questions. {@code usageEventId} is the
     * immutable ledger row; {@code sourceEventId} is the row in the originating system that the ledger
     * row records. A drill-through that conflated them would send a reader to a UUID that does not
     * resolve anywhere.
     */
    public SeededUsageEvent usageEventWithMarker(UUID org, UUID projectId, String dimension,
                                                BigDecimal quantity, LocalDateTime occurredAt,
                                                LocalDate bucketStart, LocalDate bucketEnd,
                                                BigDecimal cost, String currency) {
        UUID sourceId = UUID.randomUUID();
        UUID usageEventId = usageEvent(org, projectId, dimension, quantity,
            com.firstelement.finance.catalog.UsageDimension.valueOf(dimension).canonicalUnit(),
            occurredAt, bucketStart, bucketEnd, sourceId, "messages.delivered", cost,
            currency, null, Map.of("patientName", METADATA_MARKER, "channel", "web"));
        return new SeededUsageEvent(usageEventId, sourceId);
    }

    /** A seeded usage event and the source row it points at. */
    public record SeededUsageEvent(UUID usageEventId, UUID sourceEventId) {
    }

    /** A MESSAGE event in March, which is the common seed for the read-model tests. */
    public UUID message(UUID org, UUID projectId, BigDecimal quantity, LocalDateTime occurredAt,
                        BigDecimal cost, String currency) {
        return usageEvent(org, projectId, "MESSAGE", quantity, "message", occurredAt,
            FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, UUID.randomUUID(), "messages.delivered",
            cost, currency, null, Map.of());
    }

    /** The 10th of March, the day the period tests compute a single bucket for. */
    public static final LocalDate MARCH_10 = LocalDate.of(2026, 3, 10);

    /** An AI_TOKEN event in March, which is what the legacy comparison reads on the ledger side. */
    public UUID aiToken(UUID org, UUID projectId, BigDecimal tokens, LocalDateTime occurredAt,
                        BigDecimal cost, String currency) {
        return usageEvent(org, projectId, "AI_TOKEN", tokens, "token", occurredAt,
            FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, UUID.randomUUID(),
            "ai_usage.tokens_recorded", cost, currency, null, Map.of());
    }

    /** An AI_REQUEST event in March. */
    public UUID aiRequest(UUID org, UUID projectId, LocalDateTime occurredAt, String currency) {
        return usageEvent(org, projectId, "AI_REQUEST", BigDecimal.ONE, "request", occurredAt,
            FinanceFixture.MARCH_1, FinanceFixture.MARCH_31, UUID.randomUUID(), "ai_runs.started",
            null, currency, null, Map.of());
    }

    // ------------------------------------------------------------------
    // periods
    // ------------------------------------------------------------------

    /** A finance period. */
    public UUID period(UUID org, UUID projectId, String key, LocalDate from, LocalDate to,
                       String currency) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into finance_periods ("id", "organizationId", "projectId", "key", "nameAr",
                "nameEn", "periodStart", "periodEnd", "currency", "status", "version", "createdAt",
                "updatedAt")
            values (:id, :org, :project, :key, 'فترة', :key, :from, :to, :currency,
                cast('OPEN' as "PeriodStatus"), 0, :now, :now)""",
            Map.of("id", id, "org", org, "project", projectId, "key", key, "from", from, "to", to,
                "currency", currency));
        return id;
    }

    // ------------------------------------------------------------------
    // legacy comparison
    // ------------------------------------------------------------------

    /**
     * An {@code ai_usage} row, the legacy comparison source.
     *
     * <p>Seeded because the legacy table is written only by the still-live NestJS orchestrator, which
     * this module does not own. {@code estimatedCost} 0 with a model absent from the legacy in-code
     * price table is the exact case ADR 0009 §3 is about.
     */
    public UUID legacyUsage(UUID org, UUID projectId, UUID runId, String model, int inputTokens,
                            int outputTokens, double estimatedCost, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        seed("""
            insert into ai_usage ("id", "organizationId", "projectId", "runId", "provider", "model",
                "inputTokens", "outputTokens", "latencyMs", "estimatedCost", "createdAt")
            values (:id, :org, :project, :run, cast('OPENAI' as "AIProviderKind"), :model, :input,
                :output, 120, :cost, :createdAt)""",
            Map.of("id", id, "org", org, "project", projectId, "run", runId, "model", model,
                "input", inputTokens, "output", outputTokens, "cost", estimatedCost,
                "createdAt", createdAt));
        return id;
    }

    /**
     * An {@code ai_runs} row, which {@code ai_usage.runId} references.
     *
     * <p>Built through the real chain rather than with generated ids, because
     * {@code ai_runs_conversationId_fkey}, {@code ai_runs_agentId_fkey} and
     * {@code ai_runs_agentVersionId_fkey} are all NOT NULL references and a random uuid would be
     * refused. Each layer is memoised per project so a window with several runs does not create several
     * agents.
     */
    public UUID aiRun(UUID org, UUID projectId, LocalDateTime startedAt) {
        UUID agentId = agentFor(org, projectId);
        UUID versionId = agentVersionFor(org, projectId, agentId);
        UUID conversationId = conversationFor(org, projectId);
        UUID id = UUID.randomUUID();
        seed("""
            insert into ai_runs ("id", "organizationId", "projectId", "conversationId", "agentId",
                "agentVersionId", "status", "decision", "provider", "latencyMs", "startedAt",
                "finishedAt")
            values (:id, :org, :project, :conversation, :agent, :version,
                cast('COMPLETED' as "AIRunStatus"), cast('REPLY' as "AIRunDecision"),
                cast('OPENAI' as "AIProviderKind"), 120, :startedAt, :finishedAt)""",
            Map.of("id", id, "org", org, "project", projectId, "conversation", conversationId,
                "agent", agentId, "version", versionId, "startedAt", startedAt,
                "finishedAt", startedAt.plusSeconds(1)));
        return id;
    }

    /** The project's AI agent, created once. */
    public UUID agentFor(UUID org, UUID projectId) {
        return memo("agent:" + projectId, () -> {
            UUID id = UUID.randomUUID();
            seed("""
                insert into ai_agents ("id", "organizationId", "projectId", "name", "isEnabled",
                    "createdAt", "updatedAt")
                values (:id, :org, :project, 'Seeded agent', true, :now, :now)""",
                Map.of("id", id, "org", org, "project", projectId));
            return id;
        });
    }

    /** A version of the project's agent, created once. */
    public UUID agentVersionFor(UUID org, UUID projectId, UUID agentId) {
        return memo("agentVersion:" + projectId, () -> {
            UUID id = UUID.randomUUID();
            seed("""
                insert into ai_agent_versions ("id", "organizationId", "projectId", "agentId",
                    "version", "systemInstructions", "provider", "model", "createdAt")
                values (:id, :org, :project, :agent, 1, 'Seeded instructions',
                    cast('OPENAI' as "AIProviderKind"), 'gpt-4o-mini', :now)""",
                Map.of("id", id, "org", org, "project", projectId, "agent", agentId));
            return id;
        });
    }

    /** A conversation for the project's customer, created once. */
    public UUID conversationFor(UUID org, UUID projectId) {
        return memo("conversation:" + projectId, () -> {
            UUID customerId = customerFor(org, projectId);
            UUID id = UUID.randomUUID();
            seed("""
                insert into conversations ("id", "organizationId", "projectId", "customerId",
                    "ticketNumber", "channel", "status", "mode", "createdAt", "updatedAt")
                values (:id, :org, :project, :customer, :ticket, cast('MOCK' as "ChannelType"),
                    cast('OPEN' as "ConversationStatus"), cast('HUMAN' as "ConversationMode"), :now,
                    :now)""",
                Map.of("id", id, "org", org, "project", projectId, "customer", customerId,
                    // varchar(14) with a tenant-unique index, so the fixture generates a stable one.
                    "ticket", "FIN-" + projectId.toString().substring(0, 8).toUpperCase()));
            return id;
        });
    }

    /** The project's customer, created once. */
    public UUID customerFor(UUID org, UUID projectId) {
        return memo("customer:" + projectId, () -> {
            UUID id = UUID.randomUUID();
            seed("""
                insert into customers ("id", "organizationId", "projectId", "cxId", "displayName",
                    "createdAt", "updatedAt")
                values (:id, :org, :project, :cx, 'Seeded Customer', :now, :now)""",
                Map.of("id", id, "org", org, "project", projectId,
                    // cxId is varchar(9) and uniquely indexed, so it must be unique per project.
                    "cx", "C" + projectId.toString().substring(0, 8).toUpperCase()));
            return id;
        });
    }

    /** Per-process memo for the AI chain, so a window with several runs shares one agent. */
    private final Map<String, UUID> memoized = new java.util.concurrent.ConcurrentHashMap<>();

    private UUID memo(String key, java.util.function.Supplier<UUID> create) {
        return memoized.computeIfAbsent(key, ignored -> create.get());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Whether every named append-only guard is enabled right now.
     *
     * <p>Read from {@code pg_trigger} rather than assumed from the {@code ALTER} that was issued. A
     * fixture that left a guard disabled would make every append-only assertion in the suite pass for
     * the wrong reason, which is the same class of bug as a negative test that cannot fail.
     */
    public int enabledAppendOnlyGuards() {
        Integer enabled = jdbc.queryForObject("""
            select count(*)
              from pg_trigger tg
              join pg_class c on c.oid = tg.tgrelid
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = 'public'
               and c.relname in (:tables)
               and not tg.tgisinternal
               and tg.tgenabled = 'O'
               -- Matched BY TRIGGER NAME, not by table: usage_rollup_snapshots also carries
               -- usage_rollup_snapshots_locked_period_guard, which must stay enabled throughout, and
               -- counting it here would make the assertion report a false surplus.
               and tg.tgname = c.relname || '_append_only'
            """, new MapSqlParameterSource().addValue("tables", APPEND_ONLY_TABLES), Integer.class);
        return enabled == null ? 0 : enabled;
    }

    /**
     * Suspends the append-only guards, for a test that must seed a row the application refuses to write.
     *
     * <p>Public because the honest way to test "the reconciliation notices a snapshot the ledger cannot
     * have produced" is to insert one — and a test that could not would have to assert the noticing
     * indirectly, against a state the application never creates.
     */
    public void suspendAppendOnlyGuards() {
        suspendGuards();
    }

    /** Restores them, and fails loudly if any is still disabled. */
    public void restoreAppendOnlyGuards() {
        restoreGuards();
    }

    private void suspendGuards() {
        for (String table : APPEND_ONLY_TABLES) {
            jdbc.update("alter table " + table + " disable trigger " + table + "_append_only",
                new MapSqlParameterSource());
        }
    }

    /** Restores them, and fails loudly if any is still disabled. */
    private void restoreGuards() {
        for (String table : APPEND_ONLY_TABLES) {
            jdbc.update("alter table " + table + " enable trigger " + table + "_append_only",
                new MapSqlParameterSource());
        }
        int stillOff = APPEND_ONLY_TABLES.size() - enabledAppendOnlyGuards();
        if (stillOff != 0) {
            throw new IllegalStateException(
                stillOff + " append-only guard(s) are still disabled after the fixture reset. Every "
                    + "append-only assertion in this suite would pass for the wrong reason.");
        }
    }

    /** The tenant's first ADMIN, for a row the schema ties to a tenant composite key. */
    public UUID adminOf(UUID organizationId) {
        UUID admin = jdbc.queryForObject(
            "select u.\"id\" from users u where u.\"organizationId\" = :org and u.\"role\" ="
                + " cast('ADMIN' as \"UserRole\") order by u.\"id\" limit 1",
            new MapSqlParameterSource().addValue("org", organizationId), UUID.class);
        if (admin == null) {
            throw new IllegalStateException(
                "No ADMIN in organization " + organizationId + "; the fixture must be reset first");
        }
        return admin;
    }

    /** Counts rows matching a predicate. Zero matches reads as 0 rather than throwing. */
    public int count(String sql, Map<String, Object> parameters) {
        Integer rows = jdbc.queryForObject("select count(*) from (" + sql + ") fe_counted",
            new MapSqlParameterSource(parameters), Integer.class);
        return rows == null ? 0 : rows;
    }

    /** The number of rows in a table for one tenant. */
    public int tenantRows(String table, UUID organizationId) {
        Integer rows = jdbc.queryForObject(
            "select count(*) from " + table + " where \"organizationId\" = :org",
            new MapSqlParameterSource().addValue("org", organizationId), Integer.class);
        return rows == null ? 0 : rows;
    }

    private void seed(String sql, Map<String, Object> parameters) {
        MapSqlParameterSource source = new MapSqlParameterSource(parameters);
        source.addValue("now", now());
        jdbc.update(sql, source);
    }

    /**
     * Seeds with an explicit parameter source, for statements with more than ten bindings.
     *
     * <p>{@code Map.of()} caps at ten pairs, and several of the version inserts have more than that.
     * Built entry by entry rather than reaching for a mutable map, so a typo in a parameter name is a
     * missing-bind error from the driver rather than a silently unbound placeholder.
     */
    private void insert(String sql, MapSqlParameterSource parameters) {
        parameters.addValue("now", now());
        jdbc.update(sql, parameters);
    }

    private static String json(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":\"")
                .append(String.valueOf(entry.getValue()).replace("\"", "\\\"")).append('"');
        }
        return json.append('}').toString();
    }
}
