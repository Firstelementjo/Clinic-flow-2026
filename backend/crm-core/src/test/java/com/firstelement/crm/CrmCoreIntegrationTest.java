package com.firstelement.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.api.ClassificationView;
import com.firstelement.crm.api.ConversationDetail;
import com.firstelement.crm.api.CustomerDetail;
import com.firstelement.crm.api.CustomerListItem;
import com.firstelement.crm.api.NoteViews;
import com.firstelement.crm.api.ResolutionView;
import com.firstelement.crm.api.SaveClassificationQuestionRequest;
import com.firstelement.crm.api.SaveResolutionRequest;
import com.firstelement.crm.api.UpdateCustomerRequest;
import com.firstelement.crm.customer.IdentityResolver;
import com.firstelement.crm.customer.InboundIdentity;
import com.firstelement.crm.customer.ResolvedCustomer;
import com.firstelement.crm.domain.ChannelType;
import com.firstelement.crm.error.CrmDomainException;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.error.VersionConflictException;
import com.firstelement.crm.query.CursorPage;
import com.firstelement.crm.service.CaseService;
import com.firstelement.crm.service.CustomerService;
import com.firstelement.crm.service.NoteService;
import com.firstelement.security.TenantContext;
import com.firstelement.security.error.PolicyDeniedException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The CRM core against a real PostgreSQL 16 / pgvector container.
 *
 * <p>Two things are proven here that no unit test can prove:
 *
 * <ol>
 *   <li><b>The schema mapping is right.</b> Every statement names the quoted camelCase column and
 *       binds {@code LocalDateTime}; if either were wrong the container would reject the query,
 *       not silently return nothing.</li>
 *   <li><b>Isolation is real.</b> Cross-tenant reads, cross-project reads and no-membership reads
 *       are asserted against the actual rows, not against a mock's expectations.</li>
 * </ol>
 *
 * <p>There is no skip path: if Docker is unavailable the context fails and the build fails.
 */
@SpringBootTest(classes = CrmTestApplication.class)
class CrmCoreIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private CustomerService customers;

    @Autowired
    private IdentityResolver identities;

    @Autowired
    private NoteService notes;

    @Autowired
    private CaseService cases;

    private TenantContext staff;
    private TenantContext admin;
    private TenantContext outsider;
    private TenantContext beta;

    @BeforeEach
    void setUp() {
        fixture.reset();
        staff = fixture.staffAContext();
        admin = fixture.adminAContext();
        outsider = fixture.outsiderAContext();
        beta = fixture.adminBContext();
        // Service-level calls need both bindings a servlet request would have: the annotated
        // permission check reads the SecurityContext, and the tenant scope reads the holder.
        PrincipalScope.bind(staff);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        PrincipalScope.clear();
    }

    // ==================================================================
    // customers: tenant isolation
    // ==================================================================

    @Test
    @DisplayName("an employee sees only their own project's customers")
    void employeeSeesOnlyAccessibleProjects() {
        CursorPage<CustomerListItem> page = customers.list(staff, null, null, null, 50);

        assertThat(page.items()).extracting(CustomerListItem::id)
            .containsExactly(fixture.customerA1);
        assertThat(page.items()).extracting(CustomerListItem::projectId)
            .containsExactly(fixture.projectA1);
    }

    @Test
    @DisplayName("an admin of the tenant sees every project of that tenant and no other")
    void adminSeesOwnTenantProjects() {
        CursorPage<CustomerListItem> page = customers.list(admin, null, null, null, 50);

        assertThat(page.items()).extracting(CustomerListItem::id)
            .containsExactlyInAnyOrder(fixture.customerA1, fixture.customerA2);
    }

    @Test
    @DisplayName("another tenant's customer is a 404, byte-identical to a nonexistent id")
    void crossTenantReadIsNonDisclosing() {
        assertThatThrownBy(() -> customers.getById(staff, fixture.customerB1))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Customer not found");

        assertThatThrownBy(() -> customers.getById(staff, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessage("Customer not found");
    }

    @Test
    @DisplayName("a principal with no membership receives no tenant data at all (FR-1.4)")
    void noMembershipSeesNothing() {
        CursorPage<CustomerListItem> page = customers.list(outsider, null, null, null, 50);

        assertThat(page.items()).isEmpty();
        assertThatThrownBy(() -> customers.getById(outsider, fixture.customerA1))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a requested project outside the scope is refused before a statement exists")
    void requestedProjectOutsideScopeIsDenied() {
        assertThatThrownBy(() -> customers.list(staff, fixture.projectA2, null, null, 50))
            .isInstanceOf(PolicyDeniedException.class);
        assertThatThrownBy(() -> customers.list(staff, fixture.projectB1, null, null, 50))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("a cross-tenant aggregate cannot reveal the other tenant's row count")
    void aggregateDoesNotLeak() {
        assertThat(customers.list(staff, null, null, null, 50).items()).hasSize(1);
        assertThat(customers.list(admin, null, null, null, 50).items()).hasSize(2);
        // The beta tenant exists and holds one customer that neither call can see.
        assertThat(customers.list(beta, null, null, null, 50).items()).hasSize(1);
    }

    // ==================================================================
    // customers: read and write
    // ==================================================================

    @Test
    @DisplayName("the customer 360 carries identities, conversations and the project name")
    void customerDetail() {
        fixture.insertIdentity(fixture.orgA, fixture.projectA1, fixture.customerA1, "WHATSAPP",
            "+966500000001", "Alpha Clinic", true);
        fixture.insertConversation(fixture.orgA, fixture.projectA1, fixture.customerA1,
            "#0814000000001", "MOCK", "OPEN", "HUMAN");

        CustomerDetail detail = customers.getById(staff, fixture.customerA1);

        assertThat(detail.cxId()).isEqualTo("#33000001");
        assertThat(detail.project().name()).isEqualTo("Alpha One");
        assertThat(detail.projectName()).isEqualTo("Alpha One");
        assertThat(detail.identities()).hasSize(1);
        assertThat(detail.identities().get(0).platform()).isEqualTo("WHATSAPP");
        assertThat(detail.identities().get(0).createdAt()).endsWith("Z");
        assertThat(detail.conversations()).hasSize(1);
        assertThat(detail.leads()).isEmpty();
        assertThat(detail.lastSeenAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    }

    @Test
    @DisplayName("a CX lookup resolves inside the caller's scope only")
    void getByCxId() {
        assertThat(customers.getByCxId(staff, "#33000001").id()).isEqualTo(fixture.customerA1);
        assertThatThrownBy(() -> customers.getByCxId(staff, "#33000003"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an update writes only the fields the caller sent")
    void updateWritesOnlyProvidedFields() {
        customers.update(staff, fixture.customerA1,
            new UpdateCustomerRequest("Renamed Clinic", null, null, "en", null, null, null),
            null, "203.0.113.9");

        CustomerDetail detail = customers.getById(staff, fixture.customerA1);

        assertThat(detail.displayName()).isEqualTo("Renamed Clinic");
        assertThat(detail.locale()).isEqualTo("en");
        // Untouched fields keep their values: an absent field is not a clear.
        assertThat(detail.primaryPhone()).isEqualTo("+966500000001");
        assertThat(detail.notes()).isEqualTo("alpha-one notes");
    }

    @Test
    @DisplayName("an email is trimmed and lower-cased before it is stored")
    void emailNormalisation() {
        customers.update(staff, fixture.customerA1,
            new UpdateCustomerRequest(null, "  Owner@Example.COM ", null, null, null, null, null),
            null, null);

        assertThat(customers.getById(staff, fixture.customerA1).primaryEmail())
            .isEqualTo("owner@example.com");
    }

    @Test
    @DisplayName("an invalid phone format is refused at the edge")
    void invalidPhoneIsRefused() {
        assertThatThrownBy(() -> customers.update(staff, fixture.customerA1,
            new UpdateCustomerRequest(null, null, "12345", null, null, null, null), null, null))
            .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    @DisplayName("an update records the changed field NAMES and never the values")
    void auditCarriesFieldNamesOnly() {
        customers.update(staff, fixture.customerA1,
            new UpdateCustomerRequest("Secret Person Name", null, null, null, null, null, null),
            null, "203.0.113.9");

        String metadata = fixture.jdbc().queryForObject(
            "select \"metadata\"::text from audit_logs where \"action\" = 'CUSTOMER_DATA_UPDATED'",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), String.class);
        assertThat(metadata).isNotNull();
        assertThat(metadata).contains("changedFields").contains("displayName");
        assertThat(metadata).doesNotContain("Secret Person Name");

        Integer ipRows = fixture.jdbc().queryForObject(
            "select count(*) from audit_logs where \"ipAddress\" = '203.0.113.9'",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), Integer.class);
        assertThat(ipRows).isEqualTo(1);
    }

    // ==================================================================
    // customers: optimistic concurrency
    // ==================================================================

    @Test
    @DisplayName("a stale If-Match is a 409 that carries the current version")
    void staleVersionCollides() {
        LocalDateTime stale = fixture.jdbc().queryForObject(
            "select \"updatedAt\" from customers where \"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", fixture.customerA1), LocalDateTime.class);

        customers.update(staff, fixture.customerA1, UpdateCustomerRequest.rename("First Writer"),
            null, null);

        assertThatThrownBy(() -> customers.update(staff, fixture.customerA1,
            UpdateCustomerRequest.rename("Second Writer"), stale, null))
            .isInstanceOf(VersionConflictException.class)
            .satisfies(thrown -> assertThat(((VersionConflictException) thrown).details())
                .containsKey("currentVersion"));
    }

    @Test
    @DisplayName("a fresh If-Match applies")
    void freshVersionApplies() {
        LocalDateTime current = fixture.jdbc().queryForObject(
            "select \"updatedAt\" from customers where \"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", fixture.customerA1), LocalDateTime.class);

        CustomerDetail updated = customers.update(staff, fixture.customerA1,
            UpdateCustomerRequest.rename("Applied"), current, null);

        assertThat(updated.displayName()).isEqualTo("Applied");
    }

    @Test
    @DisplayName("a cross-tenant update changes nothing and collides, never writes")
    void crossTenantUpdateWritesNothing() {
        Integer before = countCustomer(fixture.customerB1);

        assertThatThrownBy(() -> customers.update(staff, fixture.customerB1,
            UpdateCustomerRequest.rename("Hijacked"), null, null))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(countCustomer(fixture.customerB1)).isEqualTo(before);
        assertThat(fixture.jdbc().queryForObject(
            "select \"displayName\" from customers where \"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", fixture.customerB1), String.class))
            .isEqualTo("Beta One Clinic");
    }

    // ==================================================================
    // identity resolution
    // ==================================================================

    @Test
    @DisplayName("a verified phone signal links a new handle to the existing customer")
    void identityResolutionLinksVerifiedSignal() {
        ResolvedCustomer resolved = identities.resolve(staff, new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "ext-new-1",
            "Alpha One Clinic", "+966500000001", null, null, Map.of()));

        // A verified phone is one of the only two signals strong enough to link without asking.
        assertThat(resolved.created()).isFalse();
        assertThat(resolved.customerId()).isEqualTo(fixture.customerA1);
        assertThat(resolved.possibleMatches()).isEmpty();

        Integer identities = fixture.jdbc().queryForObject(
            "select count(*) from customer_identities where \"customerId\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", fixture.customerA1), Integer.class);
        assertThat(identities).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown handle with no verified signal creates a customer with a CX id")
    void identityResolutionCreatesGenuinelyNewCustomer() {
        ResolvedCustomer resolved = identities.resolve(staff, new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "ext-brand-new", "Brand New Person",
            null, null, null, Map.of()));

        assertThat(resolved.created()).isTrue();
        assertThat(resolved.customerId()).isNotIn(fixture.customerA1, fixture.customerA2);

        String cxId = fixture.jdbc().queryForObject(
            "select \"cxId\" from customers where \"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", resolved.customerId()), String.class);
        assertThat(cxId).matches("^#33\\d{6}$");
        assertThat(resolved.possibleMatches()).isEmpty();
    }

    @Test
    @DisplayName("a name-only match is reported as a candidate and never merged")
    void nameMatchIsReportedNotMerged() {
        ResolvedCustomer resolved = identities.resolve(staff, new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "ext-new-2",
            "alpha one clinic", null, null, null, Map.of()));

        assertThat(resolved.created()).isTrue();
        assertThat(resolved.customerId()).isNotEqualTo(fixture.customerA1);
        assertThat(resolved.possibleMatches()).hasSize(1);
        assertThat(resolved.possibleMatches().get(0).customerId()).isEqualTo(fixture.customerA1);
        assertThat(resolved.possibleMatches().get(0).reason()).isEqualTo("NAME");
    }

    @Test
    @DisplayName("a known handle resolves to the same customer and touches lastSeenAt")
    void knownHandleResolves() {
        fixture.insertIdentity(fixture.orgA, fixture.projectA1, fixture.customerA1, "MOCK",
            "ext-known", null, true);

        ResolvedCustomer resolved = identities.resolve(staff, new InboundIdentity(
            fixture.orgA, fixture.projectA1, ChannelType.MOCK, "ext-known", null, null, null, null,
            Map.of()));

        assertThat(resolved.created()).isFalse();
        assertThat(resolved.customerId()).isEqualTo(fixture.customerA1);
    }

    @Test
    @DisplayName("identity resolution refuses a project the caller cannot reach")
    void identityResolutionIsProjectScoped() {
        assertThatThrownBy(() -> identities.resolve(staff, new InboundIdentity(
            fixture.orgA, fixture.projectA2, ChannelType.MOCK, "ext-x", null, null, null, null,
            Map.of())))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("a claimed tenant that is not the caller's is refused")
    void identityResolutionRefusesClaimedTenant() {
        assertThatThrownBy(() -> identities.resolve(staff, new InboundIdentity(
            fixture.orgB, fixture.projectA1, ChannelType.MOCK, "ext-x", null, null, null, null,
            Map.of())))
            .isInstanceOf(PolicyDeniedException.class);
    }

    @Test
    @DisplayName("a handle with no name shows the phone, and failing that the handle tail")
    void fallbackName() {
        assertThat(com.firstelement.crm.customer.IdentityResolver.resolveDisplayName(
            new InboundIdentity(fixture.orgA, fixture.projectA1, ChannelType.WHATSAPP, "abcdef123456",
                null, "+966500009999", null, null, null)))
            .isEqualTo("+966500009999");

        assertThat(com.firstelement.crm.customer.IdentityResolver.resolveDisplayName(
            new InboundIdentity(fixture.orgA, fixture.projectA1, ChannelType.WHATSAPP, "abcdef123456",
                null, null, null, null, null)))
            .isEqualTo("WHATSAPP 123456");
    }

    // ==================================================================
    // notes
    // ==================================================================

    @Test
    @DisplayName("a note is created in the caller's own project and listed for its record")
    void noteLifecycle() {
        NoteViews.Created created = notes.create(staff, new com.firstelement.crm.api.NoteRequest(
            "Conversation", fixture.customerA1, "Called back on Monday", fixture.projectA1));

        assertThat(created.resourceType()).isEqualTo("Conversation");
        assertThat(created.projectId()).isEqualTo(fixture.projectA1);

        List<NoteViews.ForResource> forResource = notes.listForResource(staff, "Conversation",
            fixture.customerA1, fixture.projectA1);
        assertThat(forResource).hasSize(1);
        assertThat(forResource.get(0).body()).isEqualTo("Called back on Monday");

        NoteViews.Updated updated = notes.update(staff, created.id(),
            new com.firstelement.crm.api.NoteRequest.UpdateNoteRequest("Called back Tuesday", null),
            null);
        assertThat(updated.body()).isEqualTo("Called back Tuesday");

        assertThat(notes.delete(staff, created.id(), null)).containsEntry("id",
            created.id().toString());
        assertThat(notes.listForResource(staff, "Conversation", fixture.customerA1, null)).isEmpty();
    }

    @Test
    @DisplayName("a note in another project is invisible to this project, and cannot be deleted")
    void noteIsProjectScoped() {
        NoteViews.Created created = notes.create(admin, new com.firstelement.crm.api.NoteRequest(
            "Conversation", fixture.customerA2, "A2 only", fixture.projectA2));

        // A scoped list returns nothing rather than an error: the note exists, it is simply not
        // this caller's. The delete is the operation that must fail loudly, because "silently did
        // nothing" is indistinguishable from "deleted".
        assertThat(notes.listForResource(staff, "Conversation", fixture.customerA2, null)).isEmpty();
        assertThatThrownBy(() -> notes.delete(staff, created.id(), null))
            .isInstanceOf(ResourceNotFoundException.class);

        Integer rows = fixture.jdbc().queryForObject("select count(*) from notes",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("the project-wide note list never crosses the tenant boundary")
    void projectNoteListIsScoped() {
        notes.create(staff, new com.firstelement.crm.api.NoteRequest(
            "Customer", fixture.customerA1, "A1 note", fixture.projectA1));

        assertThat(notes.listProject(staff, null)).hasSize(1);
        assertThat(notes.listProject(beta, null)).isEmpty();
        // An employee with no membership sees nothing, which FR-1.4 requires and the legacy
        // organization-wide fallback did not.
        assertThat(notes.listProject(outsider, null)).isEmpty();
    }

    // ==================================================================
    // cases
    // ==================================================================

    @Test
    @DisplayName("a classification question requires at least two bilingual options")
    void questionValidation() {
        // Classification questions are project configuration and the legacy route required
        // ESCALATION_RULE_MANAGE, which is an ADMIN grant. Binding the admin is therefore the
        // faithful thing here, not a shortcut around the permission.
        PrincipalScope.bind(admin);
        SaveClassificationQuestionRequest invalid = new SaveClassificationQuestionRequest(1, "س",
            "q", List.of(new com.firstelement.crm.api.ClassificationQuestionView.ClassificationOption(
                "only", "واحد", "One")), true, true);

        assertThatThrownBy(() -> cases.saveQuestion(admin, fixture.projectA1, invalid, null))
            .isInstanceOf(ValidationFailedException.class);

        SaveClassificationQuestionRequest valid = new SaveClassificationQuestionRequest(1, "نوع الطلب",
            "Request type", List.of(
                new com.firstelement.crm.api.ClassificationQuestionView.ClassificationOption(
                    "refund", "استرجاع", "Refund"),
                new com.firstelement.crm.api.ClassificationQuestionView.ClassificationOption(
                    "info", "استفسار", "Question")), true, true);

        var stored = cases.saveQuestion(admin, fixture.projectA1, valid, null);
        assertThat(stored.position()).isEqualTo(1);
        assertThat(stored.promptAr()).isEqualTo("نوع الطلب");
        assertThat(stored.options()).hasSize(2);
        assertThat(stored.options().get(0).labelAr()).isEqualTo("استرجاع");
    }

    @Test
    @DisplayName("a taken question position is a 409 conflict, not an unmapped 500")
    void aTakenPositionIsAConflict() {
        // Phase 18. `CaseRepository` translated the unique-index collision into its own
        // `PositionAlreadyUsedException`, an unmapped RuntimeException, so the caller was told the
        // server had broken. The classification is 409: nothing about the request is malformed and
        // retrying it unchanged changes nothing. It is deliberately not 404 -- the question being
        // inserted is provably new, so the only possible failure is the occupied position, and the
        // caller needs to be told which position to move.
        PrincipalScope.bind(admin);
        SaveClassificationQuestionRequest first = new SaveClassificationQuestionRequest(3, "نوع",
            "Type", List.of(
                new com.firstelement.crm.api.ClassificationQuestionView.ClassificationOption(
                    "a", "ا", "A"),
                new com.firstelement.crm.api.ClassificationQuestionView.ClassificationOption(
                    "b", "ب", "B")),
            true, true);
        cases.saveQuestion(admin, fixture.projectA1, first, null);

        assertThatThrownBy(() -> cases.saveQuestion(admin, fixture.projectA1, first, null))
            .isInstanceOf(CrmDomainException.class)
            .satisfies(thrown -> {
                CrmDomainException conflict = (CrmDomainException) thrown;
                assertThat(conflict.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                assertThat(conflict.code()).isEqualTo("POSITION_ALREADY_USED");
                assertThat(conflict.details()).containsEntry("field", "position").containsEntry("position", 3);
                assertThat(conflict.getMessage()).contains("3");
            });
    }

    @Test
    @DisplayName("a question from another project is a 404")
    void questionIsProjectScoped() {
        PrincipalScope.bind(admin);
        SaveClassificationQuestionRequest valid = new SaveClassificationQuestionRequest(1, "نوع",
            "Type", List.of(
                new com.firstelement.crm.api.ClassificationQuestionView.ClassificationOption("a", "ا", "A"),
                new com.firstelement.crm.api.ClassificationQuestionView.ClassificationOption("b", "ب", "B")),
            true, true);
        var stored = cases.saveQuestion(admin, fixture.projectA2, valid, null);

        assertThatThrownBy(() -> cases.listQuestions(staff, fixture.projectA2))
            .isInstanceOf(PolicyDeniedException.class);
        // The question lives in A2; an A1-scoped admin cannot delete it, and gets the same 404 a
        // nonexistent id produces.
        assertThatThrownBy(() -> cases.deleteQuestion(staff, fixture.projectA1, stored.id()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("answers citing another project's questions are dropped, not rejected")
    void classificationDropsForeignAnswers() {
        UUID conversation = fixture.insertConversation(fixture.orgA, fixture.projectA1,
            fixture.customerA1, "#0814000000010", "MOCK", "OPEN", "HUMAN");
        UUID foreignQuestion = insertRawQuestion(fixture.projectA2);

        ClassificationView view = cases.saveClassification(staff, conversation,
            new com.firstelement.crm.api.SaveClassificationRequest(List.of(
                new ClassificationView.ClassificationAnswer(foreignQuestion.toString(), "refund"),
                new ClassificationView.ClassificationAnswer("not-a-uuid", "refund"))));

        assertThat(view.answers()).isEmpty();
        // The conversation's own project has no enabled questions, and "every required question
        // has an answer" over an empty set is true — the legacy `[].filter(...).every(...)`
        // behaves identically, so this is fidelity rather than a bug.
        assertThat(view.isComplete()).isTrue();
        assertThat(view.suggestion()).isNull();
    }

    @Test
    @DisplayName("a complete classification reports isComplete")
    void classificationCompleteness() {
        UUID question = insertRawQuestion(fixture.projectA1);
        UUID conversation = fixture.insertConversation(fixture.orgA, fixture.projectA1,
            fixture.customerA1, "#0814000000011", "MOCK", "OPEN", "HUMAN");

        ClassificationView view = cases.saveClassification(staff, conversation,
            new com.firstelement.crm.api.SaveClassificationRequest(
                List.of(new ClassificationView.ClassificationAnswer(question.toString(), "refund"))));

        assertThat(view.isComplete()).isTrue();
        assertThat(view.answers()).hasSize(1);
        assertThat(view.classifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("saving a resolution twice updates one row")
    void resolutionIsAnUpsert() {
        UUID conversation = fixture.insertConversation(fixture.orgA, fixture.projectA1,
            fixture.customerA1, "#0814000000012", "MOCK", "OPEN", "HUMAN");

        ResolutionView first = cases.saveResolution(staff, conversation,
            new SaveResolutionRequest("Customer could not book", "Re-booked by phone",
                false, null, Boolean.TRUE, Boolean.TRUE, false, false, "RESOLVED", null));
        ResolutionView second = cases.saveResolution(staff, conversation,
            new SaveResolutionRequest("Customer could not book", "Re-booked by portal",
                false, null, Boolean.TRUE, Boolean.TRUE, false, false, "RESOLVED", "via portal"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.agentAction()).isEqualTo("Re-booked by portal");
        assertThat(second.recordedByName()).isEqualTo("Alpha Staff");

        Integer rows = fixture.jdbc().queryForObject(
            "select count(*) from case_resolutions where \"conversationId\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", conversation), Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("a resolution citing another project's document is refused")
    void resolutionRejectsForeignDocument() {
        UUID conversation = fixture.insertConversation(fixture.orgA, fixture.projectA1,
            fixture.customerA1, "#0814000000013", "MOCK", "OPEN", "HUMAN");
        UUID foreignDocument = insertDocument(fixture.projectA2);

        assertThatThrownBy(() -> cases.saveResolution(staff, conversation,
            new SaveResolutionRequest("issue", "action", true, foreignDocument, null, null, false,
                false, "RESOLVED", null)))
            .isInstanceOf(ValidationFailedException.class)
            .hasMessageContaining("does not belong to this project");
    }

    @Test
    @DisplayName("the resolution audit row carries the outcome, never the narrative")
    void resolutionAuditIsMinimal() {
        UUID conversation = fixture.insertConversation(fixture.orgA, fixture.projectA1,
            fixture.customerA1, "#0814000000014", "MOCK", "OPEN", "HUMAN");

        cases.saveResolution(staff, conversation,
            new SaveResolutionRequest("Sensitive customer detail", "Sensitive agent detail",
                false, null, null, Boolean.TRUE, false, false, "ESCALATED", null));

        String metadata = fixture.jdbc().queryForObject(
            "select \"metadata\"::text from audit_logs where \"action\" = 'EMPLOYEE_RESOLVED_CONVERSATION'",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), String.class);

        assertThat(metadata).contains("ESCALATED").contains("satisfied");
        assertThat(metadata).doesNotContain("Sensitive");
    }

    @Test
    @DisplayName("case operations refuse a cross-tenant conversation")
    void casesAreTenantScoped() {
        UUID conversation = fixture.insertConversation(fixture.orgB, fixture.projectB1,
            fixture.customerB1, "#0814000000099", "MOCK", "OPEN", "HUMAN");
        UUID mine = fixture.insertConversation(fixture.orgA, fixture.projectA1,
            fixture.customerA1, "#0814000000098", "MOCK", "OPEN", "HUMAN");

        assertThatThrownBy(() -> cases.getClassification(staff, conversation))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> cases.getResolution(staff, conversation))
            .isInstanceOf(ResourceNotFoundException.class);
        // A conversation that exists and simply has no resolution yet is null, not a 404.
        assertThat(cases.getResolution(staff, mine)).isNull();
        assertThat(cases.hasResolution(staff, mine)).isFalse();
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private Integer countCustomer(UUID id) {
        return fixture.jdbc().queryForObject("select count(*) from customers where \"id\" = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", id), Integer.class);
    }

    private UUID insertRawQuestion(UUID project) {
        fixture.jdbc().update("""
            insert into case_classification_questions ("id", "organizationId", "projectId",
                "position", "promptAr", "promptEn", "options", "isRequired", "isEnabled",
                "createdAt", "updatedAt")
            values (:id, :org, :project, 1, 'نوع', 'Type',
                    '[{"value":"refund","labelAr":"استرجاع","labelEn":"Refund"},
                      {"value":"info","labelAr":"استفسار","labelEn":"Question"}]'::jsonb,
                    true, true, :now, :now)
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("org", fixture.orgA)
                .addValue("project", project).addValue("now", LocalDateTime.now()));
        return fixture.jdbc().queryForObject(
            "select \"id\" from case_classification_questions where \"projectId\" = :project",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("project", project), UUID.class);
    }

    private UUID insertDocument(UUID project) {
        UUID knowledgeBase = UUID.randomUUID();
        fixture.jdbc().update("""
            insert into knowledge_bases ("id", "organizationId", "projectId", "name",
                                         "createdAt", "updatedAt")
            values (:id, :org, :project, 'KB', :now, :now)
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", knowledgeBase).addValue("org", fixture.orgA)
                .addValue("project", project).addValue("now", LocalDateTime.now()));
        UUID document = UUID.randomUUID();
        fixture.jdbc().update("""
            insert into documents ("id", "organizationId", "projectId", "knowledgeBaseId", "title",
                                   "status", "createdAt", "updatedAt")
            values (:id, :org, :project, :kb, 'Refund policy', cast('READY' as "DocumentStatus"),
                    :now, :now)
            """,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", document).addValue("org", fixture.orgA)
                .addValue("project", project).addValue("kb", knowledgeBase)
                .addValue("now", LocalDateTime.now()));
        return document;
    }

    /** Kept to prove the thread projection's internal nature in this suite's context. */
    @SuppressWarnings("unused")
    private static void internalOnly(ConversationDetail detail) {
        assertThat(detail.events()).isNotNull();
    }
}