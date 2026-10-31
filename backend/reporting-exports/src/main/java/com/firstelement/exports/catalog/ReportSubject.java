package com.firstelement.exports.catalog;

/**
 * The three things an export may read.
 *
 * <h2>Why a closed enum and not a table</h2>
 *
 * <p>{@code V20261005060000} is explicit that a report version names keys the application resolves
 * against a typed registry, and that a stored join graph "is a query language, and a query language
 * stored as JSONB is how a report definition becomes a cross-tenant read". The dimensions, measures,
 * columns and filters of {@code report_versions} are JSON allow-lists for exactly that reason.
 *
 * <p>So the registry has three halves and no fourth:
 *
 * <ol>
 *   <li>the <b>subject</b> — which physical rows the export reads, expressed as one static
 *       {@code FROM}/{@code JOIN} body per subject. There is no statement that accepts a table name;</li>
 *   <li>the <b>fields</b> — which expressions may be selected ({@link ReportField});</li>
 *   <li>the <b>filters</b> — which predicates may be pushed ({@link ReportFilterField}).</li>
 * </ol>
 *
 * <p>Adding a subject is a code change reviewed as SQL. That is the price of "no raw SQL", and it is
 * the correct price: the alternative is a database that can be asked a different question by anyone
 * who can write to one jsonb column.
 *
 * <h2>Every subject is tenant- AND project-scoped</h2>
 *
 * <p>All three carry {@code "organizationId"} and {@code "projectId"}, and the planner puts both in
 * the {@code WHERE} clause before any aggregation is assembled. That is what makes FR-8.2 a property of
 * the statement rather than a promise: an unauthorized row never enters a {@code count} or a
 * {@code sum}, because the predicate is in the same statement that computes them.
 */
public enum ReportSubject {

    /** {@code customers}. One row per customer of one project. */
    CUSTOMERS("customers", "from customers c", "c"),

    /**
     * {@code conversations} joined to its {@code customers} row.
     *
     * <p>One join, declared here rather than in a report definition, because the join is what makes
     * {@code conversation.customerName} possible and a report definition that could choose its own
     * join graph could also choose to join a table it has no permission to read.
     */
    CONVERSATIONS("conversations",
        "from conversations cv join customers c on c.\"id\" = cv.\"customerId\" "
            + "and c.\"organizationId\" = cv.\"organizationId\" and c.\"projectId\" = cv.\"projectId\"",
        "cv"),

    /**
     * {@code usage_rollup_snapshots} — the pre-aggregated finance read model of ADR 0007 Part 1.
     *
     * <p>Snapshots rather than {@code usage_events} on purpose. FR-7.3 makes the rollup the read
     * model, and a finance export that recomputed from the ledger would be a second pricing
     * implementation that eventually disagrees with the dashboard the customer was shown. The
     * current generation is selected by an anti-join on {@code "supersedesSnapshotId"}, which is the
     * same rule {@code RollupRepository} applies, so an export cannot show a superseded figure.
     *
     * <p>Money is not computed here either: {@code revenueAmount} is read off the snapshot and margin
     * is derived in the select list as {@code revenue - cost}, exactly as
     * {@code FinanceUsageReadService} does it, so the export and the dashboard cannot drift.
     */
    USAGE_LEDGER("usage_rollup_snapshots",
        "from usage_rollup_snapshots s",
        "s",
        "s.\"supersedesSnapshotId\" is null");

    private final String storageName;
    private final String fromBody;
    private final String alias;
    private final String intrinsicPredicate;

    ReportSubject(String storageName, String fromBody, String alias) {
        this(storageName, fromBody, alias, null);
    }

    ReportSubject(String storageName, String fromBody, String alias, String intrinsicPredicate) {
        this.storageName = storageName;
        this.fromBody = fromBody;
        this.alias = alias;
        this.intrinsicPredicate = intrinsicPredicate;
    }

    /** The physical relation this subject reads. Never accepted from a caller. */
    public String storageName() {
        return storageName;
    }

    /**
     * The declared {@code FROM}/{@code JOIN} body.
     *
     * <p>A constant, not a template, and deliberately carrying no {@code where} clause so that the
     * planner's next act is to append the tenant/project predicate. "Policy before aggregation" is
     * then visible in the finished statement as the first predicate after {@code FROM}, and there is
     * no branch in which a subject's own body could pre-empt it.
     */
    public String fromBody() {
        return fromBody;
    }

    /**
     * A predicate that is part of which rows exist, rather than a filter the caller chose.
     *
     * <p>Only {@link #USAGE_LEDGER} has one: the anti-join on {@code "supersedesSnapshotId"} that
     * selects the current generation. It is combined with — never instead of — the tenant predicate,
     * and it binds no parameter, so it cannot be used to smuggle a value into the statement.
     */
    public String intrinsicPredicate() {
        return intrinsicPredicate;
    }

    /** The SQL alias whose {@code organizationId}/{@code projectId} columns carry the tenant filter. */
    public String alias() {
        return alias;
    }

    /** Whether this subject carries money. Drives the finance period-lock gate. */
    public boolean isFinance() {
        return this == USAGE_LEDGER;
    }

    /** The subject a stable report key names, or {@code null} when the key is not declared. */
    public static ReportSubject fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (ReportSubject subject : values()) {
            if (subject.storageName.equals(key)) {
                return subject;
            }
        }
        return null;
    }
}