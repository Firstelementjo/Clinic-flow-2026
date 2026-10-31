package com.firstelement.admin.maintenance;

import com.firstelement.security.permission.Permission;
import java.util.List;

/**
 * The complete set of writes the Admin surface can perform. There is no other one.
 *
 * <h2>Why an enum and not a registry</h2>
 *
 * <p>PROJECT_REQUIREMENTS §3.1: "Administrators must not receive a raw SQL console or unrestricted
 * database mutation interface… Dangerous actions are modeled as explicit, validated, audited jobs."
 * "Explicit" is the operative word, and an enum is the strongest form of it available in Java: the
 * set of write operations is a compile-time fact, every one of them declares the permission it needs
 * and whether it is destructive, and a route that takes a command <em>name</em> from a request
 * cannot exist because there is no route that does.
 *
 * <h2>Why each command names its own permission</h2>
 *
 * <p>ADR-0005 §7 and the ADR-0009 §4 rulings are explicit that no permission is widened for
 * convenience, and this phase adds no new {@link Permission} constant — the catalogue is owned by
 * {@code :backend:tenant-security} and widening it is that module's decision. So each command names
 * the <em>existing</em> permission that best matches its meaning, and the two that are genuinely
 * operational name {@link Permission#SYSTEM_HEALTH_READ}, which is {@code ADMIN}-only and is the
 * operator capability set.
 *
 * <p>That composition is recorded as a follow-up rather than hidden: a dedicated
 * {@code JOB_OPERATE} permission is the honest name for "recover a dead queue job", and adding it
 * belongs to the security module's owner.
 *
 * <h2>Why two commands are disabled</h2>
 *
 * <p>{@link #EXECUTE_PRIVACY_DELETION} and {@link #PURGE_RETENTION} exist and are reachable, and they
 * <b>refuse</b>. ADR-0009 §4 rule 3 says purging "ships disabled until Phase 7 exposes legal-hold
 * state", and rule 4 says "no table in this ADR is purged by a scheduled job until the
 * retention-class column is populated for it". Phase 7 now exposes the hold state, which satisfies
 * the first half; the second half is a data question — whether every table has a declared class —
 * that no operator should answer with a button.
 *
 * <p>Leaving the commands declared-but-disabled means an operator can see that the capability
 * exists, see the exact reason it is off, and call it to get a recorded refusal. That is testable:
 * the test asserts the request row is still {@code REQUESTED} and the table counts are unchanged
 * <em>after</em> the call, which is ADR-0009 §5's "assert on state, never on the message".
 */
public enum MaintenanceCommand {

    /**
     * Replay one dead-lettered outbox event.
     *
     * <p>Delegates to {@code OutboxReplayService}, which is idempotent (the update matches only
     * {@code state = 'DEAD'}), refuses a machine principal, and audits the replay including a no-op.
     * Additional gate here: {@link ReplaySafetyPolicy} refuses a queue whose handler has not
     * declared itself replay-safe.
     */
    REPLAY_DEAD_LETTERED_JOB(Permission.SYSTEM_HEALTH_READ, false, true,
        "Make one dead-lettered queue event claimable again. Idempotent, audited, and refused for "
            + "any queue whose handler has not declared itself replay-safe."),

    /**
     * Close a dead letter without replaying it.
     *
     * <p>The distinction matters: "we replayed it" and "we decided it was not worth replaying" are
     * different answers, and ADR-0008 rule 1 keeps dead letters as the evidence for the second.
     */
    RESOLVE_DEAD_LETTER(Permission.SYSTEM_HEALTH_READ, false, true,
        "Mark a dead letter RESOLVED or DISCARDED, naming who and why. Requires an authenticated "
            + "user; the schema refuses an unattributable resolution."),

    /**
     * Place a legal hold.
     *
     * <p>The precondition every purge must satisfy (ADR-0008 rule 5), which is why placing one is a
     * first-class command rather than a configuration field.
     */
    PLACE_LEGAL_HOLD(Permission.ORGANIZATION_UPDATE, false, true,
        "Freeze a tenant, project, table or record against any purge, naming the reason. Writes the "
            + "hold and its append-only history event together."),

    /** Release a legal hold. Never anonymous: the schema requires an actor and a reason. */
    RELEASE_LEGAL_HOLD(Permission.ORGANIZATION_UPDATE, false, true,
        "Release a legal hold, naming who released it, when and why. Guarded on the version the "
            + "operator read, so a concurrent release loses rather than overwrites."),

    /**
     * Open a typed privacy-deletion request.
     *
     * <p>Records a request, an impact count, and one RETAINED_LEGAL_HOLD target per covering hold.
     * Removes nothing.
     */
    REQUEST_PRIVACY_DELETION(Permission.ORGANIZATION_UPDATE, false, true,
        "Open a typed deletion request for one subject, with an impact count per table and every "
            + "covered legal hold recorded as a retained target. Removes nothing."),

    /**
     * Execute a privacy-deletion request. <b>Disabled.</b>
     *
     * <p>The request records are written by {@link #REQUEST_PRIVACY_DELETION}; performing the
     * removal is a durable job that must not run from an HTTP request thread, and its deletion
     * targets must be decided by the job that actually removes the rows.
     */
    EXECUTE_PRIVACY_DELETION(Permission.ORGANIZATION_UPDATE, true, false,
        "Carry out the removal behind a deletion request. Ships disabled: removal belongs to a "
            + "durable, batched, checkpointed job, not to a request thread."),

    /**
     * Purge retention-expired rows. <b>Disabled.</b>
     *
     * <p>ADR-0008 §2 rules 2–4 and ADR-0009 §4: purge must be tenant-scoped, batched,
     * checkpointed, idempotent, audited, must ask about legal hold first, and must not run until
     * every table has a declared retention class. This command is the declaration that the
     * capability exists and the recorded refusal that it is not on.
     */
    PURGE_RETENTION(Permission.ORGANIZATION_UPDATE, true, false,
        "Delete rows past their retention window. Ships disabled: a purge must be a batched, "
            + "checkpointed job that asks about legal hold first, and must not run until every "
            + "table has a declared retention class.");

    private final Permission requiredPermission;
    private final boolean destructive;
    private final boolean enabled;
    private final String description;

    MaintenanceCommand(Permission requiredPermission, boolean destructive, boolean enabled,
                       String description) {
        this.requiredPermission = requiredPermission;
        this.destructive = destructive;
        this.enabled = enabled;
        this.description = description;
    }

    /** The permission this command needs, checked at the HTTP edge and again in the service. */
    public Permission requiredPermission() {
        return requiredPermission;
    }

    /**
     * Whether the command removes or alters customer data.
     *
     * <p>False for a replay and for a legal hold: both change operational state, and neither
     * removes a customer's record. True for the two disabled commands, which is what makes their
     * disablement a reviewable decision rather than a preference.
     */
    public boolean destructive() {
        return destructive;
    }

    /** Whether the command will do anything if called. */
    public boolean enabled() {
        return enabled;
    }

    /** The operator-facing explanation, shown in the catalogue. */
    public String description() {
        return description;
    }

    /** The stable wire token. */
    public String wireValue() {
        return name();
    }

    /** Every command, enabled or not. The catalogue must show the disabled ones. */
    public static List<MaintenanceCommand> all() {
        return List.of(values());
    }
}