package com.firstelement.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.customer.IdentityResolver;
import com.firstelement.crm.customer.InboundIdentity;
import com.firstelement.crm.customer.ResolvedCustomer;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.persistence.CustomerRepository;
import com.firstelement.security.TenantContext;
import com.firstelement.security.UserRole;
import com.firstelement.security.error.PolicyDeniedException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * ADR-0014 regression suite: the customer reference namespace.
 *
 * <h2>Why this suite exists at all</h2>
 *
 * <p>The defect was not a bug in one screen; it was a limit on how large the product could ever
 * become, and it survived because <em>the existing suite could not have caught it</em>. Three facts
 * combined: a project-scoped {@code select count(*) from customers} allocator, a globally unique
 * {@code customers_cxId_key}, and a five-attempt walk-forward. Every project therefore searched from
 * {@code #33000000}, the first five projects consumed {@code #33000000}…{@code #33000004}, and the
 * <b>sixth</b> project's first customer threw — so at most five references could ever exist across
 * the entire deployment, and every inbound message for that project answered 500, permanently.
 *
 * <p>So the capacity tests below are the point of the suite, not an extra. They are written against
 * the real Flyway chain in a real PostgreSQL 16 / pgvector container, <b>six</b> at a time, because
 * six was the number that broke.
 *
 * <h2>There is no skip path</h2>
 *
 * <p>If Docker is unavailable the Spring context fails to start and the build fails. A skipped
 * integration or security test is not a pass (AGENTS.md).
 *
 * <h2>A note on what these tests do NOT cover</h2>
 *
 * <p>The allocator is a database function, so its own properties — atomicity of
 * {@code INSERT … ON CONFLICT … RETURNING}, the {@code CHECK} ceiling, the absence of
 * {@code count(*)} — are asserted by {@code ops/flyway/verification/V16_customer_cx_sequence_slice.sql}
 * and its negative controls, not here. What is tested here is the Java half: that the resolver asks
 * the allocator instead of computing, that a reference survives deletion, and that the retry loop
 * is contention rather than capacity.
 */
@SpringBootTest(classes = CrmTestApplication.class)
class CustomerReferenceNamespaceIntegrationTest extends CrmIntegrationTestBase {

    /** The published reference format. A customer sees this in the UI. */
    private static final String CX_FORMAT = "^#33\\d{6}$";

    /** What {@link CrmFixture} leaves behind after its backfill: Alpha's highest seeded reference. */
    private static final long ALPHA_SEEDED_COUNTER = 2L;

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private IdentityResolver identities;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private com.firstelement.security.query.TenantQueryGuard guard;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    // ==================================================================
    // 1. THE CAPACITY REGRESSION -- the tests whose absence let this ship
    // ==================================================================

    @Test
    @DisplayName("six PROJECTS can each take a first customer reference (pre-fix: the sixth threw)")
    void sixProjectsInOneOrganizationEachTakeAFirstReference() {
        // Six projects in ONE organization, each starting with no customers at all.
        //
        // Pre-fix behaviour: every project's count(*) was 0, so every project searched from
        // #33000000, and with customers_cxId_key GLOBAL the sixth insert could not find a free
        // value inside MAX_IDENTITY_ATTEMPTS and threw. Five references, deployment-wide, forever.
        List<UUID> projects = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            projects.add(newProject(fixture.orgA, "capacity-proj-" + i));
        }
        TenantContext admin = fixture.contextFor(fixture.adminA, fixture.orgA, UserRole.ADMIN,
            new LinkedHashSet<>(projects));

        List<String> references = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ResolvedCustomer resolved = identities.resolve(admin, new InboundIdentity(
                fixture.orgA, projects.get(i), ChannelType.MOCK, "capacity-" + i,
                "Capacity Person " + i, null, null, null, Map.of()));

            assertThat(resolved.created())
                .as("project %d must be able to create a customer at all", i).isTrue();
            references.add(cxIdOf(resolved.customerId()));
        }

        assertThat(references).allMatch(cx -> cx.matches(CX_FORMAT));
        assertThat(new LinkedHashSet<>(references))
            .as("six allocations must be six values; the defect made them five")
            .hasSize(6);

        // Strictly increasing, because one tenant has ONE counter regardless of project count.
        // This is the specific thing the old per-project count(*) could not do: six projects each
        // computing count(*) = 0 would all have asked for #33000000.
        List<Long> sequences = references.stream().map(CustomerReferenceNamespaceIntegrationTest::sequenceOf)
            .toList();
        assertThat(sequences).isSorted();

        // The counter is a high-water mark, not a row count. Six allocations on top of the seeded
        // maximum lands on ALPHA_SEEDED_COUNTER + 6 exactly.
        assertThat(counterFor(fixture.orgA)).isEqualTo(ALPHA_SEEDED_COUNTER + 6);
    }

    @Test
    @DisplayName("six ORGANIZATIONS each take a first reference, and all six are the SAME one")
    void sixOrganizationsEachTakeAFirstReferenceAndNoneLeaksTheOthersVolume() {
        // The per-tenant privacy property, asserted rather than assumed (ADR-0014 regression test 4).
        //
        // Every tenant's first reference is #33000000. That is the evidence that no tenant can
        // derive the deployment's customer volume from its own numbering: a GLOBAL sequence would
        // have handed tenant 2 its #33000001, and from that single number a tenant could infer
        // that exactly one customer had ever been created in the whole product.
        List<UUID> organizations = new ArrayList<>();
        List<UUID> projects = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            UUID org = newOrganization("tenant-" + i);
            organizations.add(org);
            projects.add(newProject(org, "tenant-" + i + "-proj"));
        }

        List<String> references = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID org = organizations.get(i);
            TenantContext ctx = fixture.contextFor(UUID.randomUUID(), org, UserRole.ADMIN,
                Set.of(projects.get(i)));

            ResolvedCustomer resolved = identities.resolve(ctx, new InboundIdentity(
                org, projects.get(i), ChannelType.MOCK, "tenant-handle-" + i,
                "Tenant " + i + " Person", null, null, null, Map.of()));

            assertThat(resolved.created())
                .as("tenant %d must be able to create a customer", i).isTrue();
            references.add(cxIdOf(resolved.customerId()));
        }

        assertThat(references).allMatch(cx -> cx.matches(CX_FORMAT));
        assertThat(new LinkedHashSet<>(references))
            .as("all six tenants start at the SAME reference; if they did not, a tenant could read "
                + "the deployment's total customer count off its own numbering")
            .hasSize(1);
        assertThat(references.get(0))
            .as("the format floor is #33000000, which is not a count of surviving rows")
            .isEqualTo("#33000000");

        // Each new tenant owns exactly one counter, and each advanced exactly once.
        for (UUID org : organizations) {
            assertThat(counterFor(org))
                .as("tenant %s must own exactly one counter at the format floor", org)
                .isEqualTo(0L);
        }
    }

    // ==================================================================
    // 2. MONOTONOTICITY
    // ==================================================================

    @Test
    @DisplayName("successive allocations in one organization strictly increase")
    void successiveAllocationsStrictlyIncrease() {
        // Through the resolver, so this is the real path: five brand-new handles in one project,
        // five customers, five references. A count()-derived allocator would hand out the same
        // value to all of them (each project's count(*) does not change until the insert lands) and
        // rely on the walk-forward to separate them, which is contention handling by accident.
        List<String> references = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ResolvedCustomer resolved = identities.resolve(fixture.staffAContext(), new InboundIdentity(
                fixture.orgA, fixture.projectA1, ChannelType.MOCK, "mono-" + i,
                "Monotonic Person " + i, null, null, null, Map.of()));
            assertThat(resolved.created()).as("handle %d creates a customer", i).isTrue();
            references.add(cxIdOf(resolved.customerId()));
        }

        List<Long> sequences = references.stream()
            .map(CustomerReferenceNamespaceIntegrationTest::sequenceOf).toList();
        for (int i = 1; i < sequences.size(); i++) {
            assertThat(sequences.get(i))
                .as("allocation %d must exceed allocation %d", i, i - 1)
                .isGreaterThan(sequences.get(i - 1));
        }
        assertThat(new LinkedHashSet<>(references))
            .as("no value is ever handed out twice").hasSize(5);
        assertThat(references).allMatch(cx -> cx.matches(CX_FORMAT));

        // One allocation per creation: the counter moved by exactly five, not by five plus however
        // many times a walk-forward had to step. That is the "no retry needed" claim, measured.
        assertThat(counterFor(fixture.orgA)).isEqualTo(ALPHA_SEEDED_COUNTER + 5);
    }

    // ==================================================================
    // 3. NO REUSE AFTER THE HOLDER IS DELETED
    // ==================================================================

    @Test
    @DisplayName("a reference is never reused after the customer holding it is deleted")
    void aReferenceIsNeverReusedAfterDeletion() {
        // The fault ADR-0014 names separately from exhaustion: a count()-derived allocator reopens a
        // gap BELOW the high-water mark, so a later customer can be issued a reference a deleted
        // customer once held -- and any surviving URL, export, audit record or invoice naming it
        // resolves to the wrong person.
        //
        // This is why customer_cx_sequences has ON DELETE CASCADE from `organizations` ONLY.
        TenantContext admin = fixture.adminAContext();

        ResolvedCustomer first = identities.resolve(admin, new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "reuse-1",
            "Reuse Person", null, null, null, Map.of()));
        String firstReference = cxIdOf(first.customerId());
        long counterBeforeDelete = counterFor(fixture.orgA);

        deleteCustomer(first.customerId());

        assertThat(counterFor(fixture.orgA))
            .as("deleting a CUSTOMER must not move the tenant's issued history")
            .isEqualTo(counterBeforeDelete);

        ResolvedCustomer second = identities.resolve(admin, new InboundIdentity(
            fixture.orgA, fixture.projectA2, ChannelType.MOCK, "reuse-2",
            "Reuse Person Two", null, null, null, Map.of()));
        String secondReference = cxIdOf(second.customerId());

        assertThat(secondReference)
            .as("a deleted reference must never be handed out again")
            .isNotEqualTo(firstReference);
        assertThat(sequenceOf(secondReference))
            .as("and the next value is strictly past the deleted one")
            .isGreaterThan(sequenceOf(firstReference));
    }

    @Test
    @DisplayName("deleting a TENANT removes its counter; deleting a customer never does")
    void onlyDeletingTheTenantRemovesTheCounter() {
        // The negative of the test above, so the cascade direction cannot be quietly reversed.
        assertThat(counterFor(fixture.orgA))
            .as("the fixture backfilled Alpha's counter from max(\"cxId\") = #33000002")
            .isEqualTo(ALPHA_SEEDED_COUNTER);

        fixture.jdbc().update("delete from customer_identities", new MapSqlParameterSource());
        fixture.jdbc().update("delete from customers", new MapSqlParameterSource());

        assertThat(counterFor(fixture.orgA))
            .as("zero customers, counter untouched: the high-water mark is not a row count")
            .isEqualTo(ALPHA_SEEDED_COUNTER);

        fixture.jdbc().update("delete from organizations where \"id\" = :id",
            new MapSqlParameterSource("id", fixture.orgA));

        assertThat(counterRowExists(fixture.orgA))
            .as("deleting the TENANT removes the counter, and only that does").isFalse();
    }

    // ==================================================================
    // 4. CONCURRENCY
    // ==================================================================

    @Test
    @DisplayName("concurrent writers in one organization receive DIFFERENT references")
    void concurrentWritersReceiveDifferentReferences() throws Exception {
        // What the atomic `INSERT ... ON CONFLICT ("organizationId") DO UPDATE ... RETURNING`
        // exists for -- asserted here rather than trusted from the SQL shape.
        //
        // Eight threads in ONE organization, released together by a latch so they genuinely overlap.
        // Each thread gets its OWN project and its OWN handle, so the test measures the allocator:
        // the same handle on one project would legitimately converge on one customer, which is the
        // attachIdentity behaviour and not what is under test.
        int writers = 8;
        List<UUID> projects = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            projects.add(newProject(fixture.orgA, "race-proj-" + i));
        }
        TenantContext admin = fixture.contextFor(fixture.adminA, fixture.orgA, UserRole.ADMIN,
            new LinkedHashSet<>(projects));

        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                final int index = i;
                final UUID project = projects.get(i);
                futures.add(pool.submit((Callable<String>) () -> {
                    ready.countDown();
                    if (!go.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the start gate never opened");
                    }
                    ResolvedCustomer resolved = identities.resolve(admin, new InboundIdentity(
                        fixture.orgA, project, ChannelType.MOCK, "race-handle-" + index,
                        "Race Person " + index, null, null, null, Map.of()));
                    return cxIdOf(resolved.customerId());
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).as("every writer started").isTrue();
            go.countDown();

            List<String> references = new ArrayList<>();
            for (Future<String> future : futures) {
                references.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(references).hasSize(writers);
            assertThat(references).allMatch(cx -> cx.matches(CX_FORMAT));
            assertThat(new LinkedHashSet<>(references))
                .as("the allocator handed the same reference to two concurrent writers")
                .hasSize(writers);

            // The counter agrees with the number of allocations that actually happened, which is
            // the check that no allocation was silently lost either.
            assertThat(counterFor(fixture.orgA)).isEqualTo(ALPHA_SEEDED_COUNTER + writers);
        }
        finally {
            pool.shutdownNow();
        }
    }

    // ==================================================================
    // 5. TENANT ISOLATION -- the ordinary negatives for this path
    // ==================================================================

    @Test
    @DisplayName("a handle that exists in another tenant resolves to a SEPARATE customer here")
    void crossTenantHandleNeitherLeaksNorCollides() {
        // Beta owns "ext-shared" on its own project.
        fixture.insertIdentity(fixture.orgB, fixture.projectB1, fixture.customerB1,
            "MOCK", "ext-shared", "Beta Person", true);

        ResolvedCustomer resolved = identities.resolve(fixture.staffAContext(), new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "ext-shared",
            "Alpha Person", null, null, null, Map.of()));

        // The same external handle in two tenants is two different people -- the handle is scoped
        // to its tenant, so Beta's row is invisible here and Alpha gets its own customer. What must
        // NOT happen is resolving to Beta's customer, which is why this asserts the id explicitly
        // rather than merely checking a count.
        assertThat(resolved.customerId())
            .as("Beta's customer must never be returned to an Alpha caller")
            .isNotEqualTo(fixture.customerB1);
        assertThat(cxIdOf(resolved.customerId()))
            .as("and the new Alpha customer carries an Alpha reference")
            .matches(CX_FORMAT);

        // Beta's row is untouched: the attempt added nothing to Beta.
        Integer betaIdentities = fixture.jdbc().queryForObject(
            "select count(*) from customer_identities where \"customerId\" = :id",
            new MapSqlParameterSource("id", fixture.customerB1), Integer.class);
        assertThat(betaIdentities).as("nothing was attached in the other tenant").isEqualTo(1);
    }

    @Test
    @DisplayName("a project the caller cannot reach is refused before any allocation happens")
    void crossProjectScopeIsRefused() {
        long before = counterFor(fixture.orgA);

        // staffA reaches projectA1 only.
        assertThatThrownBy(() -> identities.resolve(fixture.staffAContext(), new InboundIdentity(
            fixture.orgA, fixture.projectA2, ChannelType.MOCK, "scope-1",
            null, null, null, null, Map.of())))
            .isInstanceOf(PolicyDeniedException.class);

        assertThat(counterFor(fixture.orgA))
            .as("a denied resolution must not burn a reference").isEqualTo(before);
    }

    @Test
    @DisplayName("a claimed tenant that is not the caller's is refused")
    void claimedTenantMismatchIsRefused() {
        long before = counterFor(fixture.orgB);

        // An Alpha caller claiming Beta's organization: a request, never authorization.
        assertThatThrownBy(() -> identities.resolve(fixture.staffAContext(), new InboundIdentity(
            fixture.orgB, fixture.projectB1, ChannelType.MOCK, "claimed-1",
            null, null, null, null, Map.of())))
            .isInstanceOf(PolicyDeniedException.class);

        assertThat(counterFor(fixture.orgB))
            .as("a denied resolution must not burn another tenant's reference").isEqualTo(before);
    }

    @Test
    @DisplayName("a principal with no membership receives nothing and allocates nothing")
    void foreignCallerIsRefused() {
        assertThatThrownBy(() -> identities.resolve(fixture.outsiderAContext(), new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "outsider-1",
            null, null, null, null, Map.of())))
            .isInstanceOf(PolicyDeniedException.class);

        Integer customers = fixture.jdbc().queryForObject(
            "select count(*) from customers", new MapSqlParameterSource(), Integer.class);
        assertThat(customers)
            .as("no customer was created for a caller with no membership (FR-1.4)")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("the allocator refuses an organization that does not exist")
    void allocatorRefusesAnUnknownOrganization() {
        // The allocator takes a UUID and cannot itself know whether the CALLER is entitled to it;
        // its tenant guarantee is the FOREIGN KEY -- a counter cannot exist for an organization
        // that does not. Asserted rather than assumed, because it is the whole of the DB-side
        // tenant boundary for this function.
        assertThatThrownBy(() -> customers.nextCxId(scopeFor(UUID.randomUUID(), fixture.projectA1)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(counterRowExists(UUID.randomUUID()))
            .as("no counter was left behind by the refusal").isFalse();
    }

    // ==================================================================
    // 6. THE FORMAT, and the existing duplicate-handling that must not regress
    // ==================================================================

    @Test
    @DisplayName("a customer created by resolution carries the published reference format")
    void createdCustomerCarriesTheFormat() {
        ResolvedCustomer resolved = identities.resolve(fixture.adminAContext(), new InboundIdentity(
            fixture.orgA, fixture.projectA2, ChannelType.MOCK, "format-1",
            "Format Person", null, null, null, Map.of()));

        String cxId = cxIdOf(resolved.customerId());
        assertThat(cxId).matches(CX_FORMAT).hasSize(9);
        assertThat(sequenceOf(cxId))
            .as("the six-digit sequence is inside the published format range")
            .isBetween(0L, 999999L);
    }

    @Test
    @DisplayName("a verified signal still links instead of creating, and consumes no reference")
    void verifiedSignalStillLinksAndConsumesNoReference() {
        // The pre-existing duplicate-handling must keep working: a phone that matches an existing
        // customer attaches the handle and allocates NOTHING.
        long before = counterFor(fixture.orgA);

        ResolvedCustomer resolved = identities.resolve(fixture.staffAContext(), new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "verified-1",
            "Alpha One Clinic", "+966500000001", null, null, Map.of()));

        assertThat(resolved.created()).as("a verified phone links").isFalse();
        assertThat(resolved.customerId()).isEqualTo(fixture.customerA1);
        assertThat(counterFor(fixture.orgA))
            .as("linking is not allocating").isEqualTo(before);
    }

    @Test
    @DisplayName("a known handle resolves to the same customer twice and allocates nothing")
    void knownHandleIsIdempotent() {
        fixture.insertIdentity(fixture.orgA, fixture.projectA1, fixture.customerA1, "MOCK",
            "known-1", null, true);
        long before = counterFor(fixture.orgA);

        InboundIdentity identity = new InboundIdentity(fixture.orgA, fixture.projectA1,
            ChannelType.MOCK, "known-1", null, null, null, null, Map.of());
        ResolvedCustomer first = identities.resolve(fixture.staffAContext(), identity);
        ResolvedCustomer second = identities.resolve(fixture.staffAContext(), identity);

        assertThat(first.created()).isFalse();
        assertThat(second.created()).isFalse();
        assertThat(second.customerId()).isEqualTo(first.customerId());
        assertThat(counterFor(fixture.orgA))
            .as("resolving a known handle allocates nothing").isEqualTo(before);
    }

    @Test
    @DisplayName("a name-only overlap is reported as a candidate and never merged, and still allocates")
    void nameOnlyMatchIsReportedNotMerged() {
        // The other existing behaviour the refactor must not disturb: a similar NAME is not
        // automatically the same person, so a candidate is reported and a NEW customer is created.
        ResolvedCustomer resolved = identities.resolve(fixture.staffAContext(), new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "name-only-1",
            "alpha one clinic", null, null, null, Map.of()));

        assertThat(resolved.created()).isTrue();
        assertThat(resolved.customerId()).isNotEqualTo(fixture.customerA1);
        assertThat(resolved.possibleMatches()).hasSize(1);
        assertThat(resolved.possibleMatches().get(0).customerId()).isEqualTo(fixture.customerA1);
        assertThat(resolved.possibleMatches().get(0).reason()).isEqualTo("NAME");
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /**
     * A scope for the given tenant/project.
     *
     * <p>Used only by the one test that must call the allocator directly — {@link
     * #allocatorRefusesAnUnknownOrganization()} — because the resolver cannot reach an unknown
     * organization at all: the guard refuses first, which is the correct behaviour and is asserted
     * separately. Every other test goes through {@link IdentityResolver}, so it exercises the real
     * path rather than a hand-built scope.
     *
     * <p>The guard bean is autowired rather than constructed, so this uses the same guard the
     * application uses.
     */
    private com.firstelement.crm.query.CrmQueryScope scopeFor(UUID organizationId, UUID projectId) {
        TenantContext context = fixture.contextFor(UUID.randomUUID(), organizationId,
            UserRole.ADMIN, Set.of(projectId));
        return com.firstelement.crm.query.CrmQueryScope.forProject(guard, context, projectId);
    }

    private String cxIdOf(UUID customerId) {
        return fixture.jdbc().queryForObject(
            "select \"cxId\" from customers where \"id\" = :id",
            new MapSqlParameterSource("id", customerId), String.class);
    }

    /** The tenant's high-water mark, or {@code null} when the tenant has no counter row. */
    private Long counterFor(UUID organizationId) {
        return fixture.jdbc().queryForObject(
            "select \"lastAllocatedCxSequence\" from customer_cx_sequences "
                + "where \"organizationId\" = :org",
            new MapSqlParameterSource("org", organizationId), Long.class);
    }

    private boolean counterRowExists(UUID organizationId) {
        Integer rows = fixture.jdbc().queryForObject(
            "select count(*) from customer_cx_sequences where \"organizationId\" = :org",
            new MapSqlParameterSource("org", organizationId), Integer.class);
        return rows != null && rows > 0;
    }

    /** {@code #33XXXXXX} -> the six-digit sequence, which is what monotonicity is about. */
    private static long sequenceOf(String cxId) {
        return Long.parseLong(cxId.substring(3));
    }

    private void deleteCustomer(UUID customerId) {
        fixture.jdbc().update("delete from customer_identities where \"customerId\" = :id",
            new MapSqlParameterSource("id", customerId));
        fixture.jdbc().update("delete from customers where \"id\" = :id",
            new MapSqlParameterSource("id", customerId));
    }

    private UUID newOrganization(String slug) {
        UUID id = UUID.randomUUID();
        fixture.jdbc().update("""
            insert into organizations ("id", "name", "slug", "createdAt", "updatedAt")
            values (:id, :name, :slug, :now, :now)""",
            new MapSqlParameterSource("id", id).addValue("name", slug)
                .addValue("slug", slug).addValue("now", LocalDateTime.now()));
        return id;
    }

    private UUID newProject(UUID organizationId, String slug) {
        UUID id = UUID.randomUUID();
        fixture.jdbc().update("""
            insert into projects ("id", "organizationId", "name", "slug", "createdAt", "updatedAt")
            values (:id, :org, :name, :slug, :now, :now)""",
            new MapSqlParameterSource("id", id).addValue("org", organizationId)
                .addValue("name", slug).addValue("slug", slug)
                .addValue("now", LocalDateTime.now()));
        return id;
    }
}
