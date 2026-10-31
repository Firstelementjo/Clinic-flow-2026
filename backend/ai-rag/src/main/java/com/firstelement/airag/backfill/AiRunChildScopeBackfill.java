package com.firstelement.airag.backfill;

import com.firstelement.security.query.ScopedWhere;
import com.firstelement.security.query.TenantScope;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The ADR 0009 §2 backfill: materialise the inherited tenant scope onto the three {@code ai_run_*}
 * child tables.
 *
 * <h2>What this is, and why Phase 10 owns it</h2>
 *
 * <p>{@code V20261005110000} added {@code organizationId}/{@code projectId} to {@code ai_run_steps},
 * {@code ai_run_retrievals} and {@code ai_run_tool_calls} as <b>nullable</b> columns — expand step 1 of
 * expand/backfill/contract. ADR 0009 §2 rule 2 assigns the backfill to "Phase 10 (or Phase 8, whichever
 * writes AI runs first)", and Phase 10 is the first thing in this migration to write AI runs. So this class
 * performs it, and rule 4's obligation follows it: until the backfill is complete, tenant filters on those
 * three tables <b>join to the parent {@code ai_runs} row</b> — which is what
 * {@code AiTraceRepository.stepsForRun} and its two siblings do, and what
 * {@link #unmigratedRowsStillVisible} asserts rather than assuming.
 *
 * <h2>Five properties, each of which is a separate test</h2>
 *
 * <ol>
 *   <li><b>Armed.</b> {@link #run} requires {@link Arming#ON}. An unarmed call writes nothing, writes
 *       nothing to {@code backfill_runs}, and returns a {@link BackfillReport} saying it refused. The arming
 *       value is a parameter rather than a system property so a test can prove the unarmed path without
 *       mutating global state.</li>
 *   <li><b>Batched.</b> Every {@code UPDATE} is bounded by {@code batchSize} using
 *       {@code for update of c skip locked}. Never one unbounded statement: these are append-heavy trace
 *       tables and an unbounded {@code UPDATE} takes a row lock on every one of them at once.</li>
 *   <li><b>Checkpointed.</b> Progress is counted from the data itself — {@code count(*) where
 *       organizationId is not null} — rather than from a counter the loop increments. A counter can be
 *       ahead of the data after a crash between the batch and the counter write; a {@code count(*)} cannot.</li>
 *   <li><b>Idempotent.</b> Every {@code UPDATE} is restricted to {@code "organizationId" is null}, so a
 *       rerun is a no-op and a rerun after completion changes nothing at all.</li>
 *   <li><b>Non-destructive.</b> It only fills NULL columns. It never overwrites a non-NULL value, never
 *       deletes, never truncates.</li>
 * </ol>
 *
 * <h2>Why the value comes from the parent, never from the caller</h2>
 *
 * <p>Every {@code UPDATE} reads {@code r."organizationId"} and {@code r."projectId"} from the joined
 * {@code ai_runs} row. There is no parameter through which a caller could supply a scope, and no overload
 * that takes one. That is the tenant-safety property, and it is structural rather than a check someone
 * could forget to write.
 *
 * <h2>No {@code NOT NULL} here, and none later in this class</h2>
 *
 * <p>ADR 0009 §2 rule 1: never add {@code NOT NULL} to {@code …5110000}. Rule 3 requires a
 * <b>separate</b> migration once the residual count is proven zero on a disposable copy, with a rehearsed
 * rollback. This class has no DDL at all, so it cannot violate either rule — and
 * {@link BackfillReport#remainingNulls()} is the evidence the later migration would be authorised by.
 */
@Component
public class AiRunChildScopeBackfill {

    private static final Logger log = LoggerFactory.getLogger(AiRunChildScopeBackfill.class);

    /** The arming value this class requires. Anything else is a refusal. */
    public static final String ON = "on";

    /** The three child tables, in the order they are filled. */
    public static final List<String> CHILD_TABLES = List.of("ai_run_steps", "ai_run_retrievals",
        "ai_run_tool_calls");

    private static final String STEPS_BATCH = """
        with batch as (
            select c."id", r."organizationId", r."projectId"
              from ai_run_steps c
              join ai_runs r on r."id" = c."runId"
             where c."organizationId" is null
             order by c."id"
             limit :batchSize
             for update of c skip locked
        )
        update ai_run_steps c
           set "organizationId" = b."organizationId",
               "projectId"      = b."projectId",
               "updatedAt"      = :now
          from batch b
         where c."id" = b."id"
        """;

    private static final String RETRIEVALS_BATCH = """
        with batch as (
            select c."id", r."organizationId", r."projectId"
              from ai_run_retrievals c
              join ai_runs r on r."id" = c."runId"
             where c."organizationId" is null
             order by c."id"
             limit :batchSize
             for update of c skip locked
        )
        update ai_run_retrievals c
           set "organizationId" = b."organizationId",
               "projectId"      = b."projectId",
               "updatedAt"      = :now
          from batch b
         where c."id" = b."id"
        """;

    private static final String TOOL_CALLS_BATCH = """
        with batch as (
            select c."id", r."organizationId", r."projectId"
              from ai_run_tool_calls c
              join ai_runs r on r."id" = c."runId"
             where c."organizationId" is null
             order by c."id"
             limit :batchSize
             for update of c skip locked
        )
        update ai_run_tool_calls c
           set "organizationId" = b."organizationId",
               "projectId"      = b."projectId",
               "updatedAt"      = :now
          from batch b
         where c."id" = b."id"
        """;

    /**
     * The residual, counted from the data.
     *
     * <p>Three separate counts rather than one union, because a report that says "17 remaining" without
     * saying <em>where</em> sends an operator to all three tables to find out.
     */
    private static final String RESIDUAL = """
        select (select count(*) from ai_run_steps       where "organizationId" is null) as "steps",
               (select count(*) from ai_run_retrievals where "organizationId" is null) as "retrievals",
               (select count(*) from ai_run_tool_calls  where "organizationId" is null) as "toolCalls"
        """;

    private static final String FILLED = """
        select (select count(*) from ai_run_steps       where "organizationId" is not null) as "steps",
               (select count(*) from ai_run_retrievals where "organizationId" is not null) as "retrievals",
               (select count(*) from ai_run_tool_calls  where "organizationId" is not null) as "toolCalls"
        """;

    /**
     * Tenant consistency: a child must never disagree with its parent.
     *
     * <p>This is the assertion that makes the backfill <em>correct</em> rather than merely complete. A
     * count of zero means every filled row's scope equals its parent's, which is the only statement worth
     * making about a scope materialisation.
     */
    private static final String DISAGREEMENTS = """
        select (select count(*) from ai_run_steps c join ai_runs r on r."id" = c."runId"
                 where c."organizationId" is distinct from r."organizationId"
                    or c."projectId" is distinct from r."projectId") as "steps",
               (select count(*) from ai_run_retrievals c join ai_runs r on r."id" = c."runId"
                 where c."organizationId" is distinct from r."organizationId"
                    or c."projectId" is distinct from r."projectId") as "retrievals",
               (select count(*) from ai_run_tool_calls c join ai_runs r on r."id" = c."runId"
                 where c."organizationId" is distinct from r."organizationId"
                    or c."projectId" is distinct from r."projectId") as "toolCalls"
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public AiRunChildScopeBackfill(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The arming value, named so a call site cannot pass an arbitrary string meaning "armed". */
    public record Arming(String value) {

        public static Arming on() {
            return new Arming(ON);
        }

        public boolean isArmed() {
            return ON.equals(value);
        }
    }

    /** Per-table counts, filled and remaining. */
    public record Counts(long steps, long retrievals, long toolCalls) {

        public long total() {
            return steps + retrievals + toolCalls;
        }

        static Counts of(java.util.Map<String, Object> row) {
            return new Counts(asLong(row, "steps"), asLong(row, "retrievals"), asLong(row, "toolCalls"));
        }

        private static long asLong(Map<String, Object> row, String key) {
            Object value = row.get(key);
            return value instanceof Number number ? number.longValue() : 0L;
        }
    }

    /**
     * What one pass did.
     *
     * @param armed       whether the pass was armed
     * @param batches     how many batches ran
     * @param updated     rows this pass filled
     * @param remaining   rows still NULL across all three tables
     * @param disagreements children whose scope disagrees with the parent. Non-zero means the backfill is
     *                      <b>wrong</b>, not merely incomplete, and the caller must not proceed to a
     *                      {@code NOT NULL} migration
     * @param refusal     why an unarmed pass did nothing, or {@code null}
     */
    public record BackfillReport(boolean armed,
                                 int batches,
                                 long updated,
                                 Counts remaining,
                                 Counts disagreements,
                                 String refusal) {

        /** Whether the residual is provably zero. The precondition for a later contract migration. */
        public boolean complete() {
            return armed && remaining.total() == 0L && disagreements.total() == 0L;
        }
    }

    /**
     * Fills one batch of each table, repeatedly, until nothing is left or {@code maxBatches} is reached.
     *
     * <p>Each table's batch is one statement, and the three run in order within a single transaction so a
     * pass is all-or-nothing per table. Reaching {@code maxBatches} is a normal, resumable outcome — the
     * next call continues from the checkpoint — and is reported rather than thrown, because a resumable
     * backfill that throws is a backfill an operator stops running.
     *
     * @param batchSize rows per statement, clamped to a sane range so a caller cannot request a
     *                  single unbounded statement by passing {@code Integer.MAX_VALUE}
     */
    public BackfillReport run(Arming arming, String operator, int batchSize, int maxBatches) {
        if (arming == null || !arming.isArmed()) {
            Counts remaining = Counts.of(residual());
            log.info("AI run child-scope backfill REFUSED: not armed; remaining={}", remaining.total());
            return new BackfillReport(false, 0, 0L, remaining, Counts.of(disagreements()),
                "backfill not armed; pass " + Arming.on().value() + " explicitly after taking a backup");
        }

        int boundedBatch = Math.max(1, Math.min(batchSize, 10_000));
        int boundedPasses = Math.max(1, Math.min(maxBatches, 1_000));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int batches = 0;
        long updated = 0L;

        for (int pass = 0; pass < boundedPasses; pass++) {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("batchSize", boundedBatch)
                .addValue("now", now);
            int steps = jdbc.update(STEPS_BATCH, parameters);
            int retrievals = jdbc.update(RETRIEVALS_BATCH, parameters);
            int toolCalls = jdbc.update(TOOL_CALLS_BATCH, parameters);
            if (steps + retrievals + toolCalls == 0) {
                break;
            }
            batches++;
            updated += steps + retrievals + toolCalls;
            log.info("AI run child-scope backfill batch={} steps={} retrievals={} toolCalls={} operator={}",
                batches, steps, retrievals, toolCalls, operator);
        }

        Counts remaining = Counts.of(residual());
        Counts disagreements = Counts.of(disagreements());
        BackfillReport report = new BackfillReport(true, batches, updated, remaining, disagreements, null);
        if (report.complete()) {
            log.info("AI run child-scope backfill COMPLETE operator={} batches={} updated={}", operator,
                batches, updated);
        } else if (disagreements.total() > 0L) {
            // A disagreement is a correctness failure, not an incompleteness, and it is the one outcome
            // that must stop the later NOT NULL migration. Logged at ERROR and named in the report.
            log.error("AI run child-scope backfill produced {} child row(s) that disagree with their "
                + "parent ai_runs row; a NOT NULL contract migration must not be applied", 
                disagreements.total());
        }
        return report;
    }

    /**
     * How many child rows still have no scope, and how many already do.
     *
     * <p>The read an operator runs before and after, and the read a rehearsal asserts. Separate
     * {@code count(*)}s rather than one query over a union, so the per-table breakdown is the evidence.
     */
    public Map<String, Counts> census() {
        Map<String, Counts> census = new LinkedHashMap<>();
        census.put("remaining", Counts.of(residual()));
        census.put("filled", Counts.of(filled()));
        return census;
    }

    /**
     * Whether the join-based read still finds rows the bare child-column read cannot.
     *
     * <p>This is ADR 0009 §2 rule 4 turned into an assertion. While any child row is NULL-scoped, a filter
     * of the form {@code where "organizationId" = ?} returns nothing for it, and the run's history silently
     * disappears — a failure that reads as "no access" rather than "not yet migrated". So this method
     * reports both counts and the difference between them, and the difference is the number of rows the
     * bare predicate is hiding.
     *
     * @param scope a tenant scope whose project may be {@code null}; with {@code null} the query is
     *              tenant-wide, which is what an Admin-wide audit wants
     */
    public VisibilityCheck unmigratedRowsStillVisible(TenantScope scope) {
        MapSqlParameterSource parameters = scopedParameters(scope);
        // scopePredicate carries its own "where", which the join form needs and which the bare-child-column
        // form therefore must NOT add again. It was missing from the join form -- producing
        // `... join ai_runs r on r."id" = s."runId" r."organizationId" = ?` -- so the one read whose whole
        // purpose is to be run DURING the backfill window had never successfully executed. Found by
        // AiRunChildScopeBackfillIntegrationTest.
        Counts viaJoin = countsOf(
            "select (select count(*) from ai_run_steps s join ai_runs r on r.\"id\" = s.\"runId\" "
                + scopePredicate(scope, "r") + ") as \"steps\", "
                + "(select count(*) from ai_run_retrievals s join ai_runs r on r.\"id\" = s.\"runId\" "
                + scopePredicate(scope, "r") + ") as \"retrievals\", "
                + "(select count(*) from ai_run_tool_calls s join ai_runs r on r.\"id\" = s.\"runId\" "
                + scopePredicate(scope, "r") + ") as \"toolCalls\"", parameters);
        Counts viaBare = countsOf(
            "select (select count(*) from ai_run_steps s " + scopePredicate(scope, "s")
                + ") as \"steps\", "
                + "(select count(*) from ai_run_retrievals s " + scopePredicate(scope, "s")
                + ") as \"retrievals\", "
                + "(select count(*) from ai_run_tool_calls s " + scopePredicate(scope, "s")
                + ") as \"toolCalls\"", parameters);
        return new VisibilityCheck(viaJoin, viaBare);
    }

    /**
     * Both read paths, and how many rows the bare one hides.
     *
     * <p>A non-zero {@link #hiddenByBarePredicate()} while the backfill is incomplete is not a bug to be
     * fixed in code — it is the migration's window, and the reason the reads join. It becomes zero when the
     * backfill completes, which is the precondition for a {@code NOT NULL} contract migration.
     */
    public record VisibilityCheck(Counts viaParentJoin, Counts viaBareChildColumn) {

        public long hiddenByBarePredicate() {
            return Math.max(0L, viaParentJoin.total() - viaBareChildColumn.total());
        }
    }

    private Map<String, Object> residual() {
        return jdbc.queryForMap(RESIDUAL, new MapSqlParameterSource());
    }

    private Map<String, Object> filled() {
        return jdbc.queryForMap(FILLED, new MapSqlParameterSource());
    }

    private Map<String, Object> disagreements() {
        return jdbc.queryForMap(DISAGREEMENTS, new MapSqlParameterSource());
    }

    private Counts countsOf(String sql, MapSqlParameterSource parameters) {
        return Counts.of(jdbc.queryForMap(sql, parameters));
    }

    /**
     * Builds the tenant predicate for the join-visibility query.
     *
     * <p>Includes its own {@code where}, which both call sites rely on. Written out as text because the query
     * is a {@code count(*)} over a projection of three tables and {@code ScopedWhere} produces a single-table
     * form. The <em>values</em> are still bound parameters — only the column names, which are literals in this
     * method, appear in the text — so no caller-supplied value can reach the SQL. A tenant id cannot be
     * pasted into a statement.
     */
    private static String scopePredicate(TenantScope scope, String alias) {
        String organization = "where " + alias + ".\"organizationId\" = :" + ScopedWhere.ORGANIZATION_PARAM;
        if (!scope.isProjectScoped()) {
            return organization;
        }
        return organization + " and " + alias + ".\"projectId\" = :" + ScopedWhere.PROJECT_PARAM;
    }

    private static MapSqlParameterSource scopedParameters(TenantScope scope) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        source.addValue(ScopedWhere.ORGANIZATION_PARAM, scope.organizationId());
        if (scope.isProjectScoped()) {
            source.addValue(ScopedWhere.PROJECT_PARAM, scope.projectId());
        }
        return source;
    }

    /** The three tables this class may write, so a caller can report on them without knowing the list. */
    public List<String> targetTables() {
        return CHILD_TABLES;
    }
}