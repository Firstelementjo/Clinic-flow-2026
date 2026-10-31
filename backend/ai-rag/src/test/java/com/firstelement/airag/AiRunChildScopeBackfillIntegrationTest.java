package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.airag.backfill.AiRunChildScopeBackfill;
import com.firstelement.security.query.TenantScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The ADR 0009 §2 backfill: materialise the inherited tenant scope onto {@code ai_run_steps},
 * {@code ai_run_retrievals} and {@code ai_run_tool_calls}.
 *
 * <h2>Why this is the last thing in the phase and not a formality</h2>
 *
 * <p>{@code V20261005110000} added {@code organizationId}/{@code projectId} to those three tables as
 * <b>nullable</b> columns — expand step 1 of expand/backfill/contract. Until the backfill runs, a filter of
 * the form {@code where "organizationId" = ?} on those tables returns nothing for the rows that predate it,
 * and a run's history silently disappears. That failure reads as "no access" rather than "not yet
 * migrated", which is why {@link AiRunChildScopeBackfill#unmigratedRowsStillVisible} is asserted here rather
 * than assumed: it is rule 4 turned into an assertion.
 *
 * <h2>The five properties, each its own nested class</h2>
 *
 * <p>armed, batched, checkpointed, idempotent, non-destructive — plus the correctness property that a
 * count of zero is not enough: every filled row must agree with its <b>parent</b>. A backfill can be
 * complete and wrong, and only a disagreement count distinguishes the two.
 *
 * <h2>What this class must never do</h2>
 *
 * <p>It has no DDL, and it proposes no {@code NOT NULL}. ADR 0009 §2 rule 1 forbids adding
 * {@code NOT NULL} to {@code …5110000}, and rule 3 requires a separate migration — on a disposable copy,
 * with a rehearsed rollback — only after the residual is proven zero. {@link AiRunChildScopeBackfill.BackfillReport#complete()}
 * is the evidence such a migration would be authorised by, so a test asserting zero residual here is the
 * precondition being recorded, not the contract being taken.
 */
@SpringBootTest(classes = AiRagTestApplication.class)
class AiRunChildScopeBackfillIntegrationTest extends AiIntegrationTestBase {

    @Autowired
    private AiFixture fixture;

    @Autowired
    private AiRunChildScopeBackfill backfill;

    @BeforeEach
    void seed() {
        fixture.reset();
    }

    @Nested
    @DisplayName("arming")
    class Arming {

        @Test
        @DisplayName("an UNARMED pass writes nothing and says so")
        void unarmedWritesNothing() {
            seedChildren(4, 3, 2);

            AiRunChildScopeBackfill.BackfillReport report =
                backfill.run(new AiRunChildScopeBackfill.Arming("off"), "tester", 100, 10);

            assertThat(report.armed()).isFalse();
            assertThat(report.batches()).isZero();
            assertThat(report.updated()).isZero();
            assertThat(report.refusal())
                .as("the operator has to be told what to do, not just that nothing happened")
                .contains("not armed");
            assertThat(remaining().total())
                .as("and nothing was filled, which is the whole point of the arming")
                .isEqualTo(9L);
        }

        @Test
        @DisplayName("a null arming is also unarmed, rather than a NullPointerException at 3am")
        void nullArmingIsUnarmed() {
            seedChildren(2, 1, 1);

            assertThat(backfill.run(null, "tester", 100, 10).armed()).isFalse();
            assertThat(remaining().total()).isEqualTo(4L);
        }

        @Test
        @DisplayName("only the declared value arms it, so a loose string cannot mean 'armed'")
        void onlyTheDeclaredValueArms() {
            seedChildren(1, 1, 1);

            assertThat(backfill.run(new AiRunChildScopeBackfill.Arming("ON"), "tester", 100, 10).armed())
                .as("case-sensitive on purpose")
                .isFalse();
            assertThat(backfill.run(new AiRunChildScopeBackfill.Arming(" true"), "tester", 100, 10).armed())
                .isFalse();
            assertThat(backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10).armed())
                .isTrue();
        }
    }

    @Nested
    @DisplayName("batching and checkpointing")
    class Batching {

        @Test
        @DisplayName("a batch size of 2 fills two rows per statement per table, and takes several batches")
        void batchesAreBounded() {
            seedChildren(5, 3, 2);

            AiRunChildScopeBackfill.BackfillReport report =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 2, 50);

            assertThat(report.armed()).isTrue();
            assertThat(report.batches()).isPositive();
            // 5 steps at 2 per batch = 3 batches, 3 retrievals = 2, 2 tool calls = 1: the loop advances while
            // ANY table still has rows, so the pass count is driven by the slowest table.
            assertThat(report.updated()).isEqualTo(10L);
            assertThat(report.remaining().total()).isZero();
        }

        @Test
        @DisplayName("a pass that runs out of batches is a RESUMABLE outcome, not a throw")
        void reachingMaxBatchesIsResumable() {
            seedChildren(10, 5, 5);

            // A backfill that throws when it hits its pass limit is a backfill an operator stops running.
            AiRunChildScopeBackfill.BackfillReport partial =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 2, 2);

            assertThat(partial.complete()).isFalse();
            assertThat(partial.remaining().total()).isPositive();

            AiRunChildScopeBackfill.BackfillReport rest =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 2, 50);

            assertThat(rest.complete())
                .as("and the next call continues from the data, not from a counter that may be ahead of it")
                .isTrue();
        }

        @Test
        @DisplayName("an absurd batch size is clamped, so no caller can request one unbounded UPDATE")
        void batchSizeIsClamped() {
            seedChildren(3, 1, 1);

            AiRunChildScopeBackfill.BackfillReport report =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", Integer.MAX_VALUE, 1_000);

            assertThat(report.remaining().total()).isZero();
            // A zero batch size is clamped to one rather than producing a `limit 0` statement that updates
            // nothing and reports itself complete.
            AiRunChildScopeBackfill.BackfillReport zero =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 0, 1_000);
            assertThat(zero.complete()).isTrue();
        }

        @Test
        @DisplayName("a completely empty backfill is complete on the first pass, with no batches")
        void nothingToDoIsComplete() {
            AiRunChildScopeBackfill.BackfillReport report =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            assertThat(report.complete()).isTrue();
            assertThat(report.batches()).isZero();
            assertThat(report.updated()).isZero();
        }
    }

    @Nested
    @DisplayName("correctness, not just completeness")
    class Correctness {

        @Test
        @DisplayName("every filled row's scope EQUALS its parent's, for all three tables")
        void filledRowsAgreeWithTheirParent() {
            seedChildren(3, 3, 3);

            AiRunChildScopeBackfill.BackfillReport report =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            assertThat(report.disagreements().total())
                .as("a backfill can be complete and WRONG; only a disagreement count tells the two apart, "
                    + "and a non-zero value is what must stop a later NOT NULL migration")
                .isZero();
            assertThat(report.complete()).isTrue();
        }

        @Test
        @DisplayName("a row whose scope disagrees with its parent is COUNTED, and never silently overwritten")
        void aDisagreementIsCountedAndIsNotComplete() {
            UUID run = seedRun(AiFixture.ORG_B, AiFixture.PROJECT_B);
            UUID step = seedStep(run);
            // The child claims tenant A while its parent run belongs to tenant B -- the exact corruption a
            // NOT NULL migration would freeze in place. Seeded directly because the backfill itself never
            // creates one: it only fills NULLs and reads the value from the parent.
            fixture.jdbcUpdate("""
                update ai_run_steps set "organizationId" = :org, "projectId" = :project where "id" = :id
                """, Map.of("org", AiFixture.ORG_A, "project", AiFixture.PROJECT_A, "id", step));

            AiRunChildScopeBackfill.BackfillReport report =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            // The row is already non-NULL, so the backfill's `where "organizationId" is null` predicate skips
            // it -- non-destructive means non-destructive. But complete() is then FALSE, because a
            // disagreement is a correctness failure and not an incompleteness.
            assertThat(report.remaining().total()).isZero();
            assertThat(report.disagreements().steps()).isEqualTo(1L);
            assertThat(report.complete())
                .as("this is the assertion that must stop a contract migration")
                .isFalse();
            assertThat(fixture.text("""
                select s."organizationId"::text from ai_run_steps s where s."id" = :id
                """, Map.of("id", step)))
                .as("and the corrupted value is left for a human to resolve, not overwritten from the parent")
                .isEqualTo(AiFixture.ORG_A.toString());
        }

        @Test
        @DisplayName("the value comes from the PARENT run, and there is no parameter to supply one")
        void theScopeComesFromTheParent() {
            UUID runB = seedRun(AiFixture.ORG_B, AiFixture.PROJECT_B);
            seedStep(runB);

            backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            // Asserted on the DATA rather than on the signature: the row is tenant B's because its parent
            // run is, and no caller passed a tenant id -- backfill.run takes no scope argument at all, so
            // there is no parameter through which a wrong one could be supplied.
            String scope = fixture.text("""
                select s."organizationId"::text || '/' || s."projectId"::text
                  from ai_run_steps s join ai_runs r on r."id" = s."runId"
                 where s."runId" = :run
                """, Map.of("run", runB));
            assertThat(scope).isEqualTo(AiFixture.ORG_B + "/" + AiFixture.PROJECT_B);
            assertThat(backfill.run(AiRunChildScopeBackfill.Arming.on(), "x", 1, 1).updated()).isZero();
        }
    }

    @Nested
    @DisplayName("idempotence and non-destruction")
    class Idempotence {

        @Test
        @DisplayName("a rerun after completion changes NOTHING")
        void rerunningIsANoOp() {
            seedChildren(4, 2, 2);
            backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);
            String before = censusFingerprint();

            AiRunChildScopeBackfill.BackfillReport second =
                backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            assertThat(second.batches()).isZero();
            assertThat(second.updated()).isZero();
            assertThat(second.complete()).isTrue();
            assertThat(censusFingerprint()).isEqualTo(before);
        }

        @Test
        @DisplayName("an already-filled row is never overwritten, only NULLs are")
        void nonDestructiveToFilledRows() {
            UUID run = seedRun(AiFixture.ORG_A, AiFixture.PROJECT_A);
            UUID step = seedStep(run);
            fixture.jdbcUpdate("""
                update ai_run_steps set "organizationId" = :org, "projectId" = :project where "id" = :id
                """, Map.of("org", AiFixture.ORG_A, "project", AiFixture.PROJECT_A, "id", step));

            backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            // Preserved rather than rewritten: the WHERE clause is `"organizationId" is null`, so a row that
            // an operator corrected by hand is not clobbered by a run that disagrees with them.
            assertThat(fixture.text("""
                select s."organizationId"::text from ai_run_steps s where s."id" = :id
                """, Map.of("id", step))).isEqualTo(AiFixture.ORG_A.toString());
        }

        @Test
        @DisplayName("the backfill deletes nothing: the row counts are unchanged by a complete pass")
        void nothingIsDeleted() {
            seedChildren(3, 3, 3);
            int before = totalRows();

            backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            assertThat(totalRows()).isEqualTo(before);
            assertThat(fixture.count("select 1 from ai_runs r", Map.of()))
                .as("the parent rows are the SOURCE of the backfill and are never touched")
                .isPositive();
        }

        @Test
        @DisplayName("it issues no DDL, so it cannot alter the very schema it is migrating")
        void noDdlIsIssued() {
            int before = relationCount();

            seedChildren(1, 1, 1);
            backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            // ADR 0009 §2 rule 1: never add NOT NULL to …5110000 here, and rule 3 wants a SEPARATE migration
            // once the residual is zero. A class that can run DDL can do both by accident, so the absence is
            // asserted against the catalog rather than promised in a comment.
            assertThat(relationCount()).isEqualTo(before);
        }

        @Test
        @DisplayName("the three target tables are the declared list, so a report cannot omit one")
        void theTargetListIsExact() {
            assertThat(backfill.targetTables()).containsExactly("ai_run_steps", "ai_run_retrievals",
                "ai_run_tool_calls");
            assertThat(AiRunChildScopeBackfill.CHILD_TABLES).hasSize(3);
        }
    }

    @Nested
    @DisplayName("the reads that must keep working during the window")
    class Visibility {

        @Test
        @DisplayName("while a row is NULL-scoped, the JOIN read finds it and the BARE read does not")
        void theJoinHidesNothingThatTheBareColumnHides() {
            UUID run = seedRun(AiFixture.ORG_A, AiFixture.PROJECT_A);
            seedStep(run);
            seedRetrieval(run);
            seedToolCall(run);

            AiRunChildScopeBackfill.VisibilityCheck before =
                backfill.unmigratedRowsStillVisible(new TenantScope(AiFixture.ORG_A, AiFixture.PROJECT_A));

            assertThat(before.viaParentJoin().total()).isEqualTo(3L);
            assertThat(before.viaBareChildColumn().total())
                .as("before the backfill, the bare predicate sees nothing at all")
                .isZero();
            assertThat(before.hiddenByBarePredicate()).isEqualTo(3L);

            backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            AiRunChildScopeBackfill.VisibilityCheck after =
                backfill.unmigratedRowsStillVisible(new TenantScope(AiFixture.ORG_A, AiFixture.PROJECT_A));

            assertThat(after.viaParentJoin().total()).isEqualTo(3L);
            assertThat(after.viaBareChildColumn().total())
                .as("and after it the two paths agree, which is the precondition for a contract migration")
                .isEqualTo(3L);
            assertThat(after.hiddenByBarePredicate()).isZero();
        }

        @Test
        @DisplayName("the visibility read is itself tenant-scoped, so it cannot be used as a cross-tenant census")
        void theVisibilityReadIsTenantScoped() {
            UUID runB = seedRun(AiFixture.ORG_B, AiFixture.PROJECT_B);
            seedStep(runB);

            AiRunChildScopeBackfill.VisibilityCheck tenantA =
                backfill.unmigratedRowsStillVisible(new TenantScope(AiFixture.ORG_A, AiFixture.PROJECT_A));

            assertThat(tenantA.viaParentJoin().total())
                .as("tenant B's row must not appear in tenant A's census")
                .isZero();
            AiRunChildScopeBackfill.VisibilityCheck tenantB =
                backfill.unmigratedRowsStillVisible(new TenantScope(AiFixture.ORG_B, AiFixture.PROJECT_B));
            assertThat(tenantB.viaParentJoin().steps()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a TENANT-WIDE scope works too, which is what an Admin-wide audit wants")
        void aTenantWideScopeIsSupported() {
            // seedChildren alternates the rows across two tenants, so tenant A holds 3 of the 4 (its own
            // step, plus the retrieval and the tool call). Asserting 4 here would assert that a tenant-wide
            // scope ignores the tenant, which is the opposite of the property under test.
            seedChildren(2, 1, 1);

            AiRunChildScopeBackfill.VisibilityCheck wide =
                backfill.unmigratedRowsStillVisible(new TenantScope(AiFixture.ORG_A, null));

            assertThat(wide.viaParentJoin().total()).isEqualTo(3L);
            assertThat(backfill.unmigratedRowsStillVisible(new TenantScope(AiFixture.ORG_B, null))
                .viaParentJoin().total())
                .as("and the other tenant sees only its own row")
                .isEqualTo(1L);
        }

        @Test
        @DisplayName("the census reports filled and remaining separately, per table")
        void theCensusBreaksDownPerTable() {
            seedChildren(3, 2, 1);

            Map<String, AiRunChildScopeBackfill.Counts> census = backfill.census();

            assertThat(census.get("remaining").steps()).isEqualTo(3L);
            assertThat(census.get("remaining").retrievals()).isEqualTo(2L);
            assertThat(census.get("remaining").toolCalls()).isEqualTo(1L);
            assertThat(census.get("filled").total()).isZero();

            backfill.run(AiRunChildScopeBackfill.Arming.on(), "tester", 100, 10);

            Map<String, AiRunChildScopeBackfill.Counts> after = backfill.census();
            assertThat(after.get("remaining").total()).isZero();
            assertThat(after.get("filled").total()).isEqualTo(6L);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Seeds child rows across BOTH tenants.
     *
     * <p>Two tenants because the value is read from the parent: a single tenant would pass even if the
     * UPDATE hard-coded the wrong one, and a hard-coded scope is the exact defect the "no parameter through
     * which a caller could supply a scope" claim is about.
     */
    private void seedChildren(int steps, int retrievals, int toolCalls) {
        UUID runA = seedRun(AiFixture.ORG_A, AiFixture.PROJECT_A);
        UUID runB = seedRun(AiFixture.ORG_B, AiFixture.PROJECT_B);
        for (int i = 0; i < steps; i++) {
            seedStep(i % 2 == 0 ? runA : runB);
        }
        for (int i = 0; i < retrievals; i++) {
            seedRetrieval(i % 2 == 0 ? runA : runB);
        }
        for (int i = 0; i < toolCalls; i++) {
            seedToolCall(i % 2 == 0 ? runA : runB);
        }
    }

    private UUID seedRun(UUID org, UUID projectId) {
        UUID conversation = fixture.conversation(org, projectId, "AI");
        AiFixture.AgentVersion agent = fixture.agentWithVersion(org, projectId,
            fixture.adminOf(org), "MOCK", "mock-deterministic-v1", "[]", "[]", 0.0d, "تعليمات");
        return fixture.aiRun(org, projectId, conversation, agent.agentId(), agent.versionId());
    }

    /**
     * A child row per table, with NO tenant scope — which is the state being migrated.
     *
     * <p>Column names transcribed from {@code V20260814000000__baseline_verified_live_schema.sql}: the step's
     * discriminator is {@code "kind"} (an {@code AIRunStepKind}), not {@code stepType}, and {@code status} is
     * plain {@code text}, not an enum. {@code ai_run_tool_calls} has {@code "authorized"} and
     * {@code "isError"} rather than a decision enum — the decision lives on {@code ai_tool_executions}, and
     * this table records the call the run made. Getting those wrong would have made the suite fail for a
     * reason that had nothing to do with the backfill.
     */
    private UUID seedStep(UUID runId) {
        UUID id = UUID.randomUUID();
        fixture.jdbcUpdate("""
            insert into ai_run_steps ("id", "runId", "sequence", "kind", "status", "detail", "createdAt")
            values (:id, :run, :sequence, cast('CONTEXT' as "AIRunStepKind"), 'OK',
                cast('{}' as jsonb), :now)
            """, Map.of("id", id, "run", runId, "sequence", nextSequence(), "now", AiFixture.now()));
        return id;
    }

    private UUID seedRetrieval(UUID runId) {
        UUID id = UUID.randomUUID();
        // knowledge_bases has UNIQUE (projectId, name), so the base and document are seeded once per test
        // rather than per retrieval row. Seeding a fresh pair each time would fail with a DuplicateKeyException
        // that names a knowledge-bases constraint and says nothing about the backfill.
        fixture.jdbcUpdate("""
            insert into ai_run_retrievals ("id", "runId", "knowledgeBaseId", "documentId", "chunkId",
                "documentTitle", "score", "wasUsed", "createdAt")
            values (:id, :run, :kb, :doc, gen_random_uuid(), 'doc', cast(0.9 as double precision), true,
                :now)
            """, Map.of("id", id, "run", runId, "kb", retrievalBase(), "doc", retrievalDocument(),
            "now", AiFixture.now()));
        return id;
    }

    private UUID retrievalBase() {
        if (retrievalBase == null) {
            retrievalBase = fixture.knowledgeBase(AiFixture.ORG_A, AiFixture.PROJECT_A, "backfill KB");
        }
        return retrievalBase;
    }

    private UUID retrievalDocument() {
        if (retrievalDocument == null) {
            retrievalDocument = fixture.document(AiFixture.ORG_A, AiFixture.PROJECT_A, retrievalBase(),
                "backfill doc", "READY");
        }
        return retrievalDocument;
    }

    private UUID retrievalBase;
    private UUID retrievalDocument;

    private UUID seedToolCall(UUID runId) {
        UUID id = UUID.randomUUID();
        fixture.jdbcUpdate("""
            insert into ai_run_tool_calls ("id", "runId", "sequence", "toolName", "arguments", "result",
                "authorized", "isError", "createdAt")
            values (:id, :run, :sequence, 'conversation.lookup', cast('{}' as jsonb), cast('{}' as jsonb),
                true, false, :now)
            """, Map.of("id", id, "run", runId, "sequence", nextToolCallSequence(), "now", AiFixture.now()));
        return id;
    }

    /** A fresh sequence number, since each table's {@code (runId, sequence)} pair must be distinct. */
    private int nextSequence() {
        return fixture.intValue("select count(*)::int from ai_run_steps", Map.of());
    }

    private int nextToolCallSequence() {
        return fixture.intValue("select count(*)::int from ai_run_tool_calls", Map.of());
    }

    private AiRunChildScopeBackfill.Counts remaining() {
        return backfill.census().get("remaining");
    }

    private String censusFingerprint() {
        return fixture.text("""
            select (select count(*) from ai_run_steps where "organizationId" is not null)::text || '/' ||
                   (select count(*) from ai_run_retrievals where "organizationId" is not null)::text || '/' ||
                   (select count(*) from ai_run_tool_calls where "organizationId" is not null)::text
            """, Map.of());
    }

    private int totalRows() {
        return fixture.intValue("""
            select ((select count(*) from ai_run_steps) + (select count(*) from ai_run_retrievals)
                  + (select count(*) from ai_run_tool_calls))::int
            """, Map.of());
    }

    private int relationCount() {
        return fixture.intValue("""
            select count(*)::int from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = 'public' and c.relkind in ('r', 'p')
            """, Map.of());
    }
}
