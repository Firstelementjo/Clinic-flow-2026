package com.firstelement.automation.service;

import com.firstelement.automation.domain.WorkflowStateMachine;
import com.firstelement.automation.persistence.ActivationConflictException;
import com.firstelement.automation.persistence.WorkflowCatalogRepository;
import com.firstelement.crm.audit.CrmAuditRecord;
import com.firstelement.crm.audit.CrmAuditWriter;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.security.TenantContext;
import com.firstelement.security.permission.AuthorizationService;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring, activating and rolling back workflow definitions.
 *
 * <h2>Authorization: the open decision, stated rather than hidden</h2>
 *
 * <p>There is no {@code workflow:manage} permission in
 * {@code :backend:tenant-security}'s {@link Permission} catalogue, and this module does not own that
 * file. Rather than invent a weaker check, every authoring entry point requires
 * {@link Permission#ESCALATION_RULE_MANAGE} — the existing ADMIN-only automation-configuration
 * permission — and additionally calls {@link AuthorizationService#check} explicitly rather than
 * relying on the annotation alone, so the decision is enforced when a job or a console calls the
 * service and is provable in a test. <b>The orchestrator and the security owner must add a
 * dedicated {@code WORKFLOW_MANAGE} / {@code SLA_MANAGE} permission; this proxy is recorded in the
 * Phase-6 report, not hidden here.</b>
 *
 * <h2>Rollback is a state change, never a delete</h2>
 *
 * <p>{@link #rollback} is {@link #activate} plus one marking statement. The abandoned version keeps
 * existing because "the abandoned version is the body an in-flight run bound to, and deleting it
 * would make that run unexplainable". The whole operation is one transaction: if step 2 of the
 * activation loses, the marking is rolled back too and the incumbent stays live — a failed rollback
 * must never leave a definition with no body.
 *
 * <h2>Activation accepts DRAFT or SUPERSEDED</h2>
 *
 * <p>Enforced in {@code WorkflowCatalogRepository#activateVersion} and asserted here: a
 * {@code ROLLED_BACK} or {@code ARCHIVED} version cannot be activated, and the transaction is rolled
 * back with an {@link ActivationConflictException} rather than continuing.
 */
@Service
public class WorkflowDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionService.class);

    /** The legacy audit action vocabulary; workflow authoring has no legacy spelling to match. */
    static final String WORKFLOW_DEFINITION_CREATED = "WORKFLOW_DEFINITION_CREATED";
    static final String WORKFLOW_VERSION_CREATED = "WORKFLOW_VERSION_CREATED";
    static final String WORKFLOW_VERSION_ACTIVATED = "WORKFLOW_VERSION_ACTIVATED";
    static final String WORKFLOW_VERSION_ROLLED_BACK = "WORKFLOW_VERSION_ROLLED_BACK";

    /** The bounded batch size a dry-run preview may consider. Never unbounded. */
    public static final int DRY_RUN_BATCH_LIMIT = 200;

    private final WorkflowCatalogRepository catalog;
    private final TenantQueryGuard guard;
    private final AuthorizationService authorization;
    private final CrmAuditWriter audit;
    private final JsonCodec json;

    public WorkflowDefinitionService(WorkflowCatalogRepository catalog,
                                     TenantQueryGuard guard,
                                     AuthorizationService authorization,
                                     CrmAuditWriter audit,
                                     JsonCodec json) {
        this.catalog = catalog;
        this.guard = guard;
        this.authorization = authorization;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // authoring
    // ------------------------------------------------------------------

    /** Creates a definition. The key is tenant-unique; a duplicate is a constraint violation. */
    @Transactional
    public WorkflowCatalogRepository.DefinitionRow createDefinition(TenantContext context,
                                                                    UUID projectId,
                                                                    String key, String name,
                                                                    String description,
                                                                    String triggerType,
                                                                    int priority) {
        requireAuthoring(context);
        TenantScope scope = guard.tenantScope(context);
        String trimmedKey = requireText(key, "key");
        requireText(name, "name");
        requireText(triggerType, "triggerType");
        if (priority < 0) {
            throw new ValidationFailedException("priority must be >= 0; it is a column, not an order "
                + "derived from the name (plan.md: no alphabetical rule ordering)");
        }
        UUID id = UUID.randomUUID();
        catalog.insertDefinition(scope, id, trimmedKey, name, description, projectId, triggerType,
            priority, context.userId());
        audit.record(CrmAuditRecord.of(context.organizationId(), projectId, context.userId(),
            WORKFLOW_DEFINITION_CREATED, "WorkflowDefinition", id,
            Map.of("key", trimmedKey, "triggerType", triggerType, "priority", priority), null));
        return catalog.findDefinition(scope, id).orElseThrow(
            () -> new IllegalStateException("the definition was written but cannot be read back"));
    }

    /**
     * Authors a new version.
     *
     * <p>Editing a live workflow creates a version; it never edits one. "An operator who reorders a
     * workflow must get a NEW version, never an edit of the one an in-flight run is executing."
     *
     * @param stateMachine the body; parsed before the write so an unrunnable document is refused here
     *                    rather than discovered by the executor
     * @param capabilities the bounded capability array. Never empty-as-"all": an empty array means
     *                     this version may do nothing that requires a capability
     */
    @Transactional
    public WorkflowCatalogRepository.VersionRow createVersion(TenantContext context, UUID definitionId,
                                                              String changeNote, Object stateMachine,
                                                              Object triggerConfig,
                                                              List<String> capabilities,
                                                              int maxSteps) {
        requireAuthoring(context);
        TenantScope scope = guard.tenantScope(context);
        WorkflowCatalogRepository.DefinitionRow definition =
            catalog.findDefinition(scope, definitionId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowDefinition"));

        // Parse first: a document the engine cannot run must not reach the database, because a
        // DRAFT that cannot run is still activatable by an operator who does not read the jsonb.
        WorkflowStateMachine.parse(stateMachine, capabilities, maxSteps);

        int nextVersion = definition.latestVersionNumber() + 1;
        UUID versionId = UUID.randomUUID();
        catalog.insertVersion(scope, versionId, definition, nextVersion, changeNote,
            json.write(stateMachine == null ? Map.of() : stateMachine),
            json.write(triggerConfig == null ? Map.of() : triggerConfig),
            json.write(capabilities == null ? List.of() : capabilities),
            contentHash(stateMachine, triggerConfig, capabilities), context.userId());
        catalog.advanceLatestVersionNumber(scope, definitionId, nextVersion);
        audit.record(CrmAuditRecord.of(context.organizationId(), definition.projectId(),
            context.userId(), WORKFLOW_VERSION_CREATED, "WorkflowVersion", versionId,
            Map.of("definitionId", definitionId.toString(), "versionNumber", nextVersion), null));
        return catalog.findVersion(scope, versionId).orElseThrow(
            () -> new IllegalStateException("the version was written but cannot be read back"));
    }

    /**
     * Activates a version: retire the incumbent, activate the new one, move the pointer.
     *
     * <p>One transaction. A 0-row step 2 rolls the whole thing back and raises
     * {@link ActivationConflictException}.
     */
    @Transactional
    public WorkflowCatalogRepository.DefinitionRow activate(TenantContext context, UUID definitionId,
                                                            UUID versionId) {
        requireAuthoring(context);
        TenantScope scope = guard.tenantScope(context);
        WorkflowCatalogRepository.DefinitionRow definition =
            catalog.findDefinition(scope, definitionId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowDefinition"));
        WorkflowCatalogRepository.VersionRow version = catalog.findVersion(scope, versionId)
            .orElseThrow(() -> new ResourceNotFoundException("WorkflowVersion"));
        if (!version.workflowDefinitionId().equals(definitionId)) {
            throw new ValidationFailedException(
                "That version belongs to a different workflow definition");
        }

        int retired = catalog.retireActiveVersion(scope, definitionId, versionId);
        int activated = catalog.activateVersion(scope, versionId, context.userId());
        int pointer = activated == 1
            ? catalog.moveActivePointer(scope, definitionId, versionId, version.versionNumber()) : 0;

        if (retired > 1 || activated != 1 || pointer != 1) {
            throw new ActivationConflictException(
                "Activation of version " + versionId + " did not complete: retired=" + retired
                    + " activated=" + activated + " pointer=" + pointer
                    + ". The transaction is rolled back so the incumbent stays ACTIVE; a definition "
                    + "with no live body is a workflow that silently stops running.",
                retired, activated, pointer);
        }

        audit.record(CrmAuditRecord.of(context.organizationId(), definition.projectId(),
            context.userId(), WORKFLOW_VERSION_ACTIVATED, "WorkflowVersion", versionId,
            Map.of("definitionId", definitionId.toString(),
                "versionNumber", version.versionNumber(),
                "retiredVersionCount", retired), null));
        log.info("workflow version activated definition={} version={} versionNumber={} retired={}",
            definitionId, versionId, version.versionNumber(), retired);
        return catalog.findDefinition(scope, definitionId).orElseThrow(
            () -> new IllegalStateException("the definition cannot be read back after activation"));
    }

    /**
     * Rolls a definition back to an earlier version.
     *
     * <p>Marks the current live version {@code ROLLED_BACK} naming who, when and where it went, then
     * activates the target through the same three steps as {@link #activate}. One transaction: a
     * rollback that half-applied would leave a definition pointing at a version nobody can run.
     *
     * @param restoredVersionId the version to go back to; it may be {@code SUPERSEDED}, which is why
     *                          activation accepts that state
     */
    @Transactional
    public WorkflowCatalogRepository.DefinitionRow rollback(TenantContext context, UUID definitionId,
                                                            UUID restoredVersionId) {
        requireAuthoring(context);
        TenantScope scope = guard.tenantScope(context);
        WorkflowCatalogRepository.DefinitionRow definition =
            catalog.findDefinition(scope, definitionId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowDefinition"));
        WorkflowCatalogRepository.VersionRow active = catalog.findActiveVersion(scope, definitionId)
            .orElseThrow(() -> new ValidationFailedException(
                "This workflow has no ACTIVE version, so there is nothing to roll back"));
        WorkflowCatalogRepository.VersionRow restored = catalog.findVersion(scope, restoredVersionId)
            .orElseThrow(() -> new ResourceNotFoundException("WorkflowVersion"));
        if (!restored.workflowDefinitionId().equals(definitionId)) {
            throw new ValidationFailedException(
                "The version to restore belongs to a different workflow definition");
        }
        if (restored.id().equals(active.id())) {
            throw new ValidationFailedException("That version is already the active one");
        }

        int marked = catalog.markRolledBack(scope, active.id(), context.userId(), restoredVersionId);
        if (marked != 1) {
            throw new ActivationConflictException(
                "Rollback could not mark version " + active.id() + " as ROLLED_BACK (rows=" + marked
                    + "); it is no longer the active version, so somebody else changed it",
                0, 0, marked);
        }

        int retired = catalog.retireActiveVersion(scope, definitionId, restoredVersionId);
        int activated = catalog.activateVersion(scope, restoredVersionId, context.userId());
        int pointer = activated == 1 ? catalog.moveActivePointer(scope, definitionId, restoredVersionId,
            restored.versionNumber()) : 0;
        if (activated != 1 || pointer != 1) {
            throw new ActivationConflictException(
                "Rollback to version " + restoredVersionId + " did not complete: retired=" + retired
                    + " activated=" + activated + " pointer=" + pointer
                    + ". The transaction is rolled back and the current version stays ACTIVE.",
                retired, activated, pointer);
        }

        audit.record(CrmAuditRecord.of(context.organizationId(), definition.projectId(),
            context.userId(), WORKFLOW_VERSION_ROLLED_BACK, "WorkflowVersion", active.id(),
            Map.of("definitionId", definitionId.toString(),
                "rolledBackFromVersionNumber", active.versionNumber(),
                "restoredVersionNumber", restored.versionNumber()), null));
        log.info("workflow version rolled back definition={} from={} to={}",
            definitionId, active.id(), restoredVersionId);
        return catalog.findDefinition(scope, definitionId).orElseThrow(
            () -> new IllegalStateException("the definition cannot be read back after a rollback"));
    }

    public Optional<WorkflowCatalogRepository.DefinitionRow> findDefinition(TenantContext context,
                                                                             UUID definitionId) {
        return catalog.findDefinition(guard.tenantScope(context), definitionId);
    }

    public List<WorkflowCatalogRepository.VersionRow> history(TenantContext context,
                                                              UUID definitionId, int limit) {
        return catalog.versionHistory(guard.tenantScope(context), definitionId,
            clamp(limit, 1, 200));
    }

    // ------------------------------------------------------------------
    // dry run
    // ------------------------------------------------------------------

    /**
     * A preview of what a workflow would do, without running it.
     *
     * <p>Every definition considered is read in explicit priority order and its body is parsed and
     * capability-checked, so the preview answers "would this be allowed to run, and with which
     * capabilities" without creating a run, an action row, or an outbox intent. The batch is
     * bounded at {@link #DRY_RUN_BATCH_LIMIT}: a dry run over an unbounded rule set is how an
     * operator's preview click takes the API down.
     *
     * @return one entry per definition, in priority order, with the problem stated when there is one
     */
    @Transactional(readOnly = true)
    public List<DryRunEntry> dryRun(TenantContext context, int limit) {
        TenantScope scope = guard.tenantScope(context);
        List<DryRunEntry> entries = new ArrayList<>();
        for (WorkflowCatalogRepository.DefinitionRow definition
            : catalog.definitionsForDryRun(scope, clamp(limit, 1, DRY_RUN_BATCH_LIMIT))) {
            WorkflowCatalogRepository.VersionRow version =
                catalog.findVersion(scope, definition.activeVersionId()).orElse(null);
            if (version == null) {
                entries.add(new DryRunEntry(definition.id(), definition.key(), definition.priority(),
                    null, "UNPARSEABLE", "the active version id names no readable version row"));
                continue;
            }
            entries.add(preview(definition, version));
        }
        return entries;
    }

    private DryRunEntry preview(WorkflowCatalogRepository.DefinitionRow definition,
                                WorkflowCatalogRepository.VersionRow version) {
        Object stateMachine = readBody(version.stateMachine());
        Object capabilities = readCapabilityScope(version.capabilityScope());
        try {
            WorkflowStateMachine machine = WorkflowStateMachine.parse(stateMachine, capabilities,
                WorkflowStateMachine.HARD_MAX_STEPS);
            List<String> missing = new ArrayList<>();
            for (WorkflowStateMachine.Node node : machine.nodes()) {
                WorkflowStateMachine.CapabilityCheck check =
                    machine.checkCapabilities(node.id(), machine.capabilityScope().keySet());
                if (!check.allowed()) {
                    missing.add(node.id() + " needs " + check.missingCapabilities());
                }
            }
            return new DryRunEntry(definition.id(), definition.key(), definition.priority(),
                version.versionNumber(), missing.isEmpty() ? "WOULD_RUN" : "WOULD_BE_REFUSED",
                missing.isEmpty()
                    ? machine.nodes().size() + " action(s), entry " + machine.initial()
                        + ", capabilities " + machine.capabilityScope().keySet()
                    : String.join("; ", missing));
        } catch (IllegalArgumentException e) {
            // A dry run that cannot parse the body must SAY so, never return "would run".
            return new DryRunEntry(definition.id(), definition.key(), definition.priority(),
                version.versionNumber(), "UNPARSEABLE", e.getMessage());
        }
    }

    /** One definition's dry-run verdict. */
    public record DryRunEntry(UUID definitionId, String key, int priority, Integer versionNumber,
                              String verdict, String detail) {
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The authoring gate.
     *
     * <p>{@link Permission#ESCALATION_RULE_MANAGE} is the automation-configuration permission that
     * exists today. It is ADMIN-only, so an EMPLOYEE is refused, and it is called explicitly rather
     * than only through the annotation, because a job has no annotation proxy and plan.md §0.4
     * forbids controller-only authorization. See the class comment for the open decision to add a
     * dedicated workflow permission.
     */
    private void requireAuthoring(TenantContext context) {
        authorization.check(context, Permission.ESCALATION_RULE_MANAGE);
    }

    /**
     * A deterministic digest of the body.
     *
     * <p>"Two versions with the same digest are the same workflow, which is what makes a dry-run
     * preview honest." SHA-256 over the canonical jsonb text as PostgreSQL will store it, so the
     * digest is reproducible from the row.
     */
    String contentHash(Object stateMachine, Object triggerConfig, List<String> capabilities) {
        return sha256(json.write(stateMachine), json.write(triggerConfig),
            json.write(capabilities == null ? List.of() : capabilities));
    }

    private static String sha256(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            canonical.append(part == null ? "" : part).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JRE; refusing is better than a weaker digest silently.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    /** Reads a jsonb column into a structure the domain parsers accept; unreadable becomes empty. */
    private Object readBody(String jsonText) {
        return jsonText == null || jsonText.isBlank() ? Map.of() : json.readMap(jsonText);
    }

    /**
     * The capability array as a {@code List}.
     *
     * <p>An unreadable column becomes an empty array, never "everything": "An unrestricted capability
     * set is exactly what plan.md forbids", so a column this module cannot read must not become a
     * wildcard. The table's own {@code jsonb_typeof('array')} CHECK means a non-array cannot get
     * this far, and the empty list is the fail-closed direction anyway.
     */
    private Object readCapabilityScope(String jsonText) {
        return jsonText == null || jsonText.isBlank() ? List.of() : json.readList(jsonText);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationFailedException(field + " is required");
        }
        return value.trim();
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}