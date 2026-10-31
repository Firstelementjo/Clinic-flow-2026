package com.firstelement.exports.catalog;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every predicate that may be pushed into an export's {@code WHERE} clause, and the only comparison
 * each one is allowed to use.
 *
 * <h2>Why a filter that cannot be pushed REFUSES the export</h2>
 *
 * <p>Phase-9 hard rule 6: "If a filter cannot be pushed into the {@code WHERE} clause under
 * tenant/row/field policy, the export is refused — not silently post-filtered." The reason is that a
 * post-filter runs <em>after</em> the aggregation, so an {@code avg} over a mixed set has already
 * absorbed the unauthorized rows, and the number that comes out is not the number the filter asked
 * for. Silently post-filtering also produces a file whose row count disagrees with the figure on the
 * dashboard, which is the kind of discrepancy nobody finds until reconciliation.
 *
 * <p>So this enum is the complete list of predicates that can be pushed. A requested filter key that
 * is not here is {@link FilterNotPushableException}: the export fails with a reason naming the key,
 * and no statement is executed.
 *
 * <h2>One comparison per key, on purpose</h2>
 *
 * <p>Each constant declares its own operator instead of taking one as a parameter. A
 * {@code column = :op :value} shape is the usual way to build a filter surface, and it is the shape
 * where a caller-supplied operator becomes SQL text; here the operator is part of the key's identity,
 * so {@code customer.createdFrom} is always {@code >=} and no request can make it {@code <}.
 *
 * <h2>Values are bound, never concatenated</h2>
 *
 * <p>{@link #predicate(int)} emits only {@code ?} placeholders. The arity is derived from an
 * {@code int}, never from a string, and the values themselves are bound parameters, so a customer name
 * containing {@code '); drop table users; --} is a value and nothing else.
 */
public enum ReportFilterField {

    // ------------------------------------------------------------------
    // customers
    // ------------------------------------------------------------------

    CUSTOMER_LOCALE("customer.locale", ReportSubject.CUSTOMERS, Comparison.IN,
        "c.\"locale\"::text", "لغة العميل", "Customer locale"),

    CUSTOMER_CREATED_FROM("customer.createdFrom", ReportSubject.CUSTOMERS, Comparison.GTE,
        "c.\"createdAt\"", "أُنشئ العميل من", "Customer created from"),

    CUSTOMER_CREATED_TO("customer.createdTo", ReportSubject.CUSTOMERS, Comparison.LTE,
        "c.\"createdAt\"", "أُنشئ العميل حتى", "Customer created to"),

    CUSTOMER_HAS_EMAIL("customer.hasEmail", ReportSubject.CUSTOMERS, Comparison.EQ,
        "(c.\"primaryEmail\" is not null)", "لدى العميل بريد إلكتروني", "Customer has email"),

    // ------------------------------------------------------------------
    // conversations
    // ------------------------------------------------------------------

    CONVERSATION_STATUS("conversation.status", ReportSubject.CONVERSATIONS, Comparison.IN,
        "cv.\"status\"::text", "حالة المحادثة", "Conversation status"),

    CONVERSATION_CHANNEL("conversation.channel", ReportSubject.CONVERSATIONS, Comparison.IN,
        "cv.\"channel\"::text", "قناة المحادثة", "Conversation channel"),

    CONVERSATION_PRIORITY("conversation.priority", ReportSubject.CONVERSATIONS, Comparison.IN,
        "cv.\"priority\"::text", "أولوية المحادثة", "Conversation priority"),

    CONVERSATION_MODE("conversation.mode", ReportSubject.CONVERSATIONS, Comparison.IN,
        "cv.\"mode\"::text", "وضع المحادثة", "Conversation mode"),

    CONVERSATION_CUSTOMER("conversation.customerId", ReportSubject.CONVERSATIONS, Comparison.EQ,
        "cv.\"customerId\"", "معرف العميل", "Customer id"),

    CONVERSATION_OPENED_FROM("conversation.openedFrom", ReportSubject.CONVERSATIONS, Comparison.GTE,
        "cv.\"createdAt\"", "فُتحت من", "Opened from"),

    CONVERSATION_OPENED_TO("conversation.openedTo", ReportSubject.CONVERSATIONS, Comparison.LTE,
        "cv.\"createdAt\"", "فُتحت حتى", "Opened to"),

    CONVERSATION_RESOLVED("conversation.resolved", ReportSubject.CONVERSATIONS, Comparison.IS_NOT_NULL,
        "cv.\"resolvedAt\"", "تم حل المحادثة", "Conversation resolved"),

    // ------------------------------------------------------------------
    // usage ledger
    // ------------------------------------------------------------------

    USAGE_DIMENSION("usage.dimension", ReportSubject.USAGE_LEDGER, Comparison.IN,
        "s.\"dimension\"::text", "بند الاستخدام", "Usage dimension"),

    USAGE_CURRENCY("usage.currency", ReportSubject.USAGE_LEDGER, Comparison.IN,
        "s.\"currency\"", "العملة", "Currency"),

    USAGE_BUCKET_FROM("usage.bucketFrom", ReportSubject.USAGE_LEDGER, Comparison.GTE,
        "s.\"bucketStart\"", "من بداية الفترة", "Bucket from"),

    USAGE_BUCKET_TO("usage.bucketTo", ReportSubject.USAGE_LEDGER, Comparison.LTE,
        "s.\"bucketStart\"", "حتى بداية الفترة", "Bucket to"),

    USAGE_PERIOD("usage.periodId", ReportSubject.USAGE_LEDGER, Comparison.EQ,
        "s.\"periodId\"", "معرف الفترة المالية", "Finance period");

    // ------------------------------------------------------------------

    private final String key;
    private final ReportSubject subject;
    private final Comparison comparison;
    private final String columnRef;
    private final String labelAr;
    private final String labelEn;

    ReportFilterField(String key, ReportSubject subject, Comparison comparison, String columnRef,
                      String labelAr, String labelEn) {
        this.key = key;
        this.subject = subject;
        this.comparison = comparison;
        this.columnRef = columnRef;
        this.labelAr = labelAr;
        this.labelEn = labelEn;
    }

    /** The comparison a filter key means. Part of the key's identity, never a request field. */
    public enum Comparison {
        /** {@code = ?} — exactly one bound value. */
        EQ(" = ?"),
        /** {@code >= ?} */
        GTE(" >= ?"),
        /** {@code <= ?} */
        LTE(" <= ?"),
        /** {@code is not null} — takes no value. */
        IS_NOT_NULL(" is not null"),
        /** {@code in (?, ?, ...)} — one placeholder per value. */
        IN(null);

        private final String fragment;

        Comparison(String fragment) {
            this.fragment = fragment;
        }

        String fragment() {
            return fragment;
        }
    }

    /** The stable name as it appears in a report version's {@code "filters"} object or a request. */
    public String key() {
        return key;
    }

    public ReportSubject subject() {
        return subject;
    }

    public Comparison comparison() {
        return comparison;
    }

    /** How many bound values this filter accepts. A filter is bounded, like every other list. */
    public int arityLimit() {
        return switch (comparison) {
            case EQ, GTE, LTE, IS_NOT_NULL -> 1;
            case IN -> ExportFilterBounds.MAX_IN_VALUES;
        };
    }

    public String labelAr() {
        return labelAr;
    }

    public String labelEn() {
        return labelEn;
    }

    public String label(String locale) {
        return ReportField.isArabic(locale) ? labelAr : labelEn;
    }

    /**
     * The predicate fragment with {@code ?} placeholders, ready to be wrapped in parentheses.
     *
     * @param valueCount the number of bound values, already validated against {@link #arityLimit()}
     * @throws IllegalArgumentException when {@code valueCount} is not legal for this comparison
     */
    public String predicate(int valueCount) {
        return switch (comparison) {
            case EQ, GTE, LTE -> {
                require(valueCount == 1, key, "exactly one value");
                yield "(" + columnRef + comparison.fragment() + ")";
            }
            case IS_NOT_NULL -> {
                require(valueCount == 0, key, "no value");
                yield "(" + columnRef + comparison.fragment() + ")";
            }
            case IN -> {
                require(valueCount >= 1 && valueCount <= arityLimit(), key,
                    "between 1 and " + arityLimit() + " values");
                StringBuilder placeholders = new StringBuilder("(");
                for (int i = 0; i < valueCount; i++) {
                    if (i > 0) {
                        placeholders.append(", ");
                    }
                    placeholders.append('?');
                }
                placeholders.append(')');
                yield "(" + columnRef + " in " + placeholders + ")";
            }
        };
    }

    private static void require(boolean condition, String key, String requirement) {
        if (!condition) {
            throw new IllegalArgumentException(
                "Filter '" + key + "' takes " + requirement + " value(s); it is a declared comparison "
                    + "with a fixed shape and not a free-form expression.");
        }
    }

    /** The immutable {@code key -> filter field} index. */
    public static Map<String, ReportFilterField> index() {
        Map<String, ReportFilterField> byKey = new LinkedHashMap<>();
        for (ReportFilterField field : values()) {
            ReportFilterField clash = byKey.put(field.key(), field);
            if (clash != null) {
                throw new IllegalStateException("Two report filters declare the key '" + field.key() + "'");
            }
        }
        return Map.copyOf(byKey);
    }

    /** The filter a key names, or empty. An empty result is a refusal at the planner, not a default. */
    public static Optional<ReportFilterField> byKey(String key) {
        return Optional.ofNullable(key == null ? null : index().get(key));
    }

    /** Every filter of one subject, in declaration order. */
    public static List<ReportFilterField> ofSubject(ReportSubject subject) {
        return Arrays.stream(values()).filter(field -> field.subject() == subject).toList();
    }

    /**
     * The bounds a filter list is validated against.
     *
     * <p>Separate from this enum so the numeric limits live in one place and are quoted in the
     * refusal messages.
     */
    public static final class ExportFilterBounds {

        private ExportFilterBounds() {
        }

        /** The most values one {@code IN} filter may carry. */
        public static final int MAX_IN_VALUES = 50;

        /**
         * The most filters one export may carry, declared defaults plus request filters together.
         *
         * <p>Deliberately <em>below</em> {@link #values()} {@code .length}: a budget no caller can reach
         * is not a budget, it is decoration. The declared vocabulary is the real ceiling on what a filter
         * list can be, and a request that asks for more distinct comparisons than this is either a
         * generated request or a caller probing for a way to express a query — both of which should stop
         * at authoring time with a named refusal rather than produce a 30-predicate statement.
         */
        public static final int MAX_FILTERS = 12;

        /** The most characters in a single filter value. A filter value is a bound parameter, not a document. */
        public static final int MAX_VALUE_LENGTH = 200;
    }
}