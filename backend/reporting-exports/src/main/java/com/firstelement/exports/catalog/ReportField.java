package com.firstelement.exports.catalog;

import com.firstelement.security.permission.Permission;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every expression a report may select, and the permission each one requires.
 *
 * <h2>Why the expression is a constant and not assembled</h2>
 *
 * <p>{@link #expression()} is a compile-time string on an enum constant. The planner may concatenate
 * these; nothing may concatenate anything <em>into</em> one. That is what "a report definition is a
 * declared, versioned shape — not a query" means in practice: {@code report_versions."columns"} stores
 * {@code "customer.primaryEmail"} and the planner resolves it to {@code c."primaryEmail"}. A caller
 * cannot express {@code c."passwordHash"} because no constant produces it, and cannot express
 * {@code 1=1 union select …} because there is nothing to interpolate into.
 *
 * <h2>The permission is the field-level authorization decision</h2>
 *
 * <p>Row authorization answers "which rows"; this answers "which columns of the rows I may see". It is
 * enforced by {@link com.firstelement.exports.redaction.ExportRedactionResolver}, which DROPS the
 * field from the projection entirely rather than substituting a placeholder: a blank cell reads as
 * "no value", and a redacted field must read as <em>absent</em>. See
 * {@code docs/migration/export-redaction-policy.md}.
 *
 * <h2>Bilingual labels are mandatory</h2>
 *
 * <p>PROJECT_REQUIREMENTS is Arabic-first and the clinical design constraints are part of the product,
 * so {@link #labelAr()} and {@link #labelEn()} are both required and {@code ReportFieldRegistryTest}
 * fails the build if either is blank. A header row is rendered in one locale, and the other locale's
 * spelling is reachable through the job's column metadata, so no consumer has to guess what a column
 * meant.
 */
public enum ReportField {

    // ------------------------------------------------------------------
    // customers
    // ------------------------------------------------------------------

    CUSTOMER_ID("customer.id", ReportSubject.CUSTOMERS, FieldRole.DIMENSION, FieldKind.IDENTIFIER,
        "c.\"id\"", Permission.CUSTOMER_READ, false,
        "معرف العميل", "Customer id"),

    CUSTOMER_CX_ID("customer.cxId", ReportSubject.CUSTOMERS, FieldRole.DIMENSION, FieldKind.TEXT,
        "c.\"cxId\"", Permission.CUSTOMER_READ, false,
        "الرمز الخارجي للعميل", "Customer cx id"),

    CUSTOMER_DISPLAY_NAME("customer.displayName", ReportSubject.CUSTOMERS, FieldRole.DIMENSION,
        FieldKind.TEXT, "c.\"displayName\"", Permission.CUSTOMER_READ, false,
        "اسم العميل", "Customer name"),

    CUSTOMER_EMAIL("customer.primaryEmail", ReportSubject.CUSTOMERS, FieldRole.DIMENSION,
        FieldKind.TEXT, "c.\"primaryEmail\"", Permission.CUSTOMER_READ, true,
        "البريد الإلكتروني للعميل", "Customer email"),

    CUSTOMER_PHONE("customer.primaryPhone", ReportSubject.CUSTOMERS, FieldRole.DIMENSION,
        FieldKind.TEXT, "c.\"primaryPhone\"", Permission.CUSTOMER_READ, true,
        "هاتف العميل", "Customer phone"),

    CUSTOMER_LOCALE("customer.locale", ReportSubject.CUSTOMERS, FieldRole.DIMENSION, FieldKind.ENUM,
        "c.\"locale\"::text", Permission.CUSTOMER_READ, false,
        "لغة العميل", "Customer locale"),

    CUSTOMER_FIRST_SEEN_AT("customer.firstSeenAt", ReportSubject.CUSTOMERS, FieldRole.DIMENSION,
        FieldKind.DATE_TIME, "c.\"firstSeenAt\"", Permission.CUSTOMER_READ, false,
        "أول ظهور للعميل", "Customer first seen"),

    CUSTOMER_LAST_SEEN_AT("customer.lastSeenAt", ReportSubject.CUSTOMERS, FieldRole.DIMENSION,
        FieldKind.DATE_TIME, "c.\"lastSeenAt\"", Permission.CUSTOMER_READ, false,
        "آخر ظهور للعميل", "Customer last seen"),

    /** A {@code count(*)} over the authorized rows. Never a count over a filtered-after set. */
    CUSTOMER_COUNT("customer.count", ReportSubject.CUSTOMERS, FieldRole.MEASURE, FieldKind.INTEGER,
        "count(*)", Permission.CUSTOMER_READ, false,
        "عدد العملاء", "Customer count"),

    // ------------------------------------------------------------------
    // conversations
    // ------------------------------------------------------------------

    CONVERSATION_ID("conversation.id", ReportSubject.CONVERSATIONS, FieldRole.DIMENSION,
        FieldKind.IDENTIFIER, "cv.\"id\"", Permission.CONVERSATION_READ, false,
        "معرف المحادثة", "Conversation id"),

    CONVERSATION_TICKET_NUMBER("conversation.ticketNumber", ReportSubject.CONVERSATIONS,
        FieldRole.DIMENSION, FieldKind.TEXT, "cv.\"ticketNumber\"", Permission.CONVERSATION_READ, false,
        "رقم التذكرة", "Ticket number"),

    CONVERSATION_STATUS("conversation.status", ReportSubject.CONVERSATIONS, FieldRole.DIMENSION,
        FieldKind.ENUM, "cv.\"status\"::text", Permission.CONVERSATION_READ, false,
        "حالة المحادثة", "Conversation status"),

    CONVERSATION_CHANNEL("conversation.channel", ReportSubject.CONVERSATIONS, FieldRole.DIMENSION,
        FieldKind.ENUM, "cv.\"channel\"::text", Permission.CONVERSATION_READ, false,
        "قناة المحادثة", "Conversation channel"),

    CONVERSATION_PRIORITY("conversation.priority", ReportSubject.CONVERSATIONS, FieldRole.DIMENSION,
        FieldKind.ENUM, "cv.\"priority\"::text", Permission.CONVERSATION_READ, false,
        "أولوية المحادثة", "Conversation priority"),

    CONVERSATION_MODE("conversation.mode", ReportSubject.CONVERSATIONS, FieldRole.DIMENSION,
        FieldKind.ENUM, "cv.\"mode\"::text", Permission.CONVERSATION_READ, false,
        "وضع المحادثة", "Conversation mode"),

    CONVERSATION_CUSTOMER_NAME("conversation.customerName", ReportSubject.CONVERSATIONS,
        FieldRole.DIMENSION, FieldKind.TEXT, "c.\"displayName\"", Permission.CUSTOMER_READ, false,
        "اسم العميل", "Customer name"),

    CONVERSATION_CREATED_AT("conversation.createdAt", ReportSubject.CONVERSATIONS,
        FieldRole.DIMENSION, FieldKind.DATE_TIME, "cv.\"createdAt\"", Permission.CONVERSATION_READ,
        false, "تاريخ فتح المحادثة", "Conversation opened"),

    CONVERSATION_FIRST_RESPONSE_AT("conversation.firstResponseAt", ReportSubject.CONVERSATIONS,
        FieldRole.DIMENSION, FieldKind.DATE_TIME, "cv.\"firstResponseAt\"",
        Permission.CONVERSATION_READ, false, "وقت أول رد", "First response"),

    CONVERSATION_RESOLVED_AT("conversation.resolvedAt", ReportSubject.CONVERSATIONS,
        FieldRole.DIMENSION, FieldKind.DATE_TIME, "cv.\"resolvedAt\"", Permission.CONVERSATION_READ,
        false, "تاريخ الحل", "Resolved at"),

    CONVERSATION_MESSAGE_COUNT("conversation.messages", ReportSubject.CONVERSATIONS,
        FieldRole.MEASURE, FieldKind.INTEGER, "sum(cv.\"messageCount\")", Permission.CONVERSATION_READ,
        false, "عدد الرسائل", "Message count"),

    /** The count of conversations, not a sum of a per-row column. */
    CONVERSATION_COUNT("conversation.count", ReportSubject.CONVERSATIONS, FieldRole.MEASURE,
        FieldKind.INTEGER, "count(*)", Permission.CONVERSATION_READ, false,
        "عدد المحادثات", "Conversation count"),

    // ------------------------------------------------------------------
    // usage ledger (finance)
    // ------------------------------------------------------------------
    //
    // Every field below requires ANALYTICS_READ_ORGANIZATION, which is the finance gate. An EMPLOYEE
    // holds ANALYTICS_READ_PROJECT and therefore sees NONE of them: a finance export by a
    // non-finance principal emits a report with no money columns at all rather than a report with
    // empty money columns. That is FR-7.7 ("Permit finance-role exports only after row/field
    // authorization and redaction") expressed as a column set rather than as a post-filter.

    USAGE_DIMENSION("usage.dimension", ReportSubject.USAGE_LEDGER, FieldRole.DIMENSION,
        FieldKind.ENUM, "s.\"dimension\"::text", Permission.ANALYTICS_READ_ORGANIZATION, false,
        "بند الاستخدام", "Usage dimension"),

    USAGE_CURRENCY("usage.currency", ReportSubject.USAGE_LEDGER, FieldRole.DIMENSION,
        FieldKind.CURRENCY, "s.\"currency\"", Permission.ANALYTICS_READ_ORGANIZATION, false,
        "العملة", "Currency"),

    USAGE_BUCKET_START("usage.bucketStart", ReportSubject.USAGE_LEDGER, FieldRole.DIMENSION,
        FieldKind.DATE, "s.\"bucketStart\"", Permission.ANALYTICS_READ_ORGANIZATION, false,
        "بداية الفترة", "Bucket start"),

    USAGE_SOURCE_EVENT_COUNT("usage.eventCount", ReportSubject.USAGE_LEDGER, FieldRole.MEASURE,
        FieldKind.INTEGER, "sum(s.\"sourceEventCount\")", Permission.ANALYTICS_READ_ORGANIZATION, false,
        "عدد أحداث الاستخدام", "Usage event count"),

    USAGE_QUANTITY("usage.quantity", ReportSubject.USAGE_LEDGER, FieldRole.MEASURE, FieldKind.DECIMAL,
        "sum(s.\"quantity\")", Permission.ANALYTICS_READ_ORGANIZATION, false,
        "الكمية", "Quantity"),

    USAGE_COST("usage.cost", ReportSubject.USAGE_LEDGER, FieldRole.MEASURE, FieldKind.MONEY,
        "sum(s.\"costAmount\")", Permission.ANALYTICS_READ_ORGANIZATION, true,
        "التكلفة", "Cost"),

    /**
     * Revenue is read off the snapshot, not recomputed.
     *
     * <p>{@code sum(...)} over {@code revenueAmount} would silently treat an UNPRICED bucket as zero,
     * which is the exact collapse ADR-0007 rule 5 forbids. The sum is therefore wrapped so that any
     * unpriced bucket in the group makes the whole group's revenue unpriced, and the value is exported
     * as an empty cell by the writer with the row's own {@code usage.unpricedQuantity} beside it. See
     * {@code ReportFieldRegistry#moneyIsPriced}.
     */
    USAGE_REVENUE("usage.revenue", ReportSubject.USAGE_LEDGER, FieldRole.MEASURE, FieldKind.MONEY,
        "case when sum(case when s.\"rateCardVersionId\" is null then 1 else 0 end) > 0 then null "
            + "else sum(s.\"revenueAmount\") end",
        Permission.ANALYTICS_READ_ORGANIZATION, true, "الإيراد", "Revenue"),

    /** Margin, derived here and nowhere else: {@code revenue - cost}, unpriced when revenue is. */
    USAGE_MARGIN("usage.margin", ReportSubject.USAGE_LEDGER, FieldRole.MEASURE, FieldKind.MONEY,
        "case when sum(case when s.\"rateCardVersionId\" is null then 1 else 0 end) > 0 then null "
            + "else sum(s.\"revenueAmount\") - sum(s.\"costAmount\") end",
        Permission.ANALYTICS_READ_ORGANIZATION, true, "هامش الربح", "Margin"),

    USAGE_UNPRICED_QUANTITY("usage.unpricedQuantity", ReportSubject.USAGE_LEDGER, FieldRole.MEASURE,
        FieldKind.DECIMAL, "sum(s.\"unpricedQuantity\")", Permission.ANALYTICS_READ_ORGANIZATION, false,
        "الكمية غير المسعّرة", "Unpriced quantity"),

    USAGE_MARKUP_RATE("usage.markupRate", ReportSubject.USAGE_LEDGER, FieldRole.DIMENSION,
        FieldKind.DECIMAL, "min(s.\"markupRate\")", Permission.ANALYTICS_READ_ORGANIZATION, false,
        "نسبة التسعير", "Markup rate"),

    /** The pinned rate-card version. A figure that cannot name its rate definition is a bug. */
    USAGE_RATE_CARD_VERSION("usage.rateCardVersionId", ReportSubject.USAGE_LEDGER,
        FieldRole.DIMENSION, FieldKind.IDENTIFIER, "min(s.\"rateCardVersionId\"::text)",
        Permission.ANALYTICS_READ_ORGANIZATION, false, "إصدار بطاقة الأسعار", "Rate card version"),

    USAGE_PERIOD_ID("usage.periodId", ReportSubject.USAGE_LEDGER, FieldRole.DIMENSION,
        FieldKind.IDENTIFIER, "min(s.\"periodId\"::text)", Permission.ANALYTICS_READ_ORGANIZATION,
        false, "معرف الفترة المالية", "Finance period"),

    USAGE_GENERATION("usage.generation", ReportSubject.USAGE_LEDGER, FieldRole.DIMENSION,
        FieldKind.INTEGER, "max(s.\"generation\")", Permission.ANALYTICS_READ_ORGANIZATION, false,
        "جيل التجميع", "Generation");

    // ------------------------------------------------------------------
    // fields
    // ------------------------------------------------------------------

    private final String key;
    private final ReportSubject subject;
    private final FieldRole role;
    private final FieldKind kind;
    private final String expression;
    private final Permission requiredPermission;
    private final boolean sensitive;
    private final String labelAr;
    private final String labelEn;

    ReportField(String key, ReportSubject subject, FieldRole role, FieldKind kind, String expression,
                Permission requiredPermission, boolean sensitive, String labelAr, String labelEn) {
        this.key = key;
        this.subject = subject;
        this.role = role;
        this.kind = kind;
        this.expression = expression;
        this.requiredPermission = requiredPermission;
        this.sensitive = sensitive;
        this.labelAr = labelAr;
        this.labelEn = labelEn;
    }

    /** Whether a field groups the rows or aggregates them. */
    public enum FieldRole {
        /** A grouping key. Never aggregated. */
        DIMENSION,
        /** An aggregate. Requires a {@code GROUP BY} over the emitted dimensions. */
        MEASURE
    }

    /**
     * How a value is rendered, and therefore what the writer accepts.
     *
     * <p>A declared closed set rather than a per-column format string: a format string is a template,
     * and a template is code a report author controls.
     */
    public enum FieldKind {
        /** Raw text. Arabic is written verbatim as UTF-8 and never escaped. */
        TEXT,
        /** A UUID, written in its canonical hyphenated form. */
        IDENTIFIER,
        /** A whole number. Locale-grouped in a document, invariant in CSV. */
        INTEGER,
        /** A decimal. Same split as {@link #INTEGER}. */
        DECIMAL,
        /**
         * A money amount, rendered with the row's own currency. The currency is a separate column, so
         * an amount is never rendered with a symbol the reader has to infer.
         */
        MONEY,
        /** An ISO-4217 code. */
        CURRENCY,
        /** A {@code timestamp without time zone}, rendered in the export's own timezone. */
        DATE_TIME,
        /** A {@code date}. */
        DATE,
        /** An enum label, cast to text by the statement, so the stored spelling is what is exported. */
        ENUM,
        /** A boolean rendered as {@code true}/{@code false}. */
        BOOLEAN
    }

    /** The stable name stored in {@code report_versions."columns"} / {@code "dimensions"} / {@code "measures"}. */
    public String key() {
        return key;
    }

    public ReportSubject subject() {
        return subject;
    }

    /** The SQL expression. A compile-time constant; nothing interpolates into it. */
    public String expression() {
        return expression;
    }

    public FieldRole role() {
        return role;
    }

    public FieldKind kind() {
        return kind;
    }

    /** The permission a caller must hold for this field to be emitted at all. */
    public Permission requiredPermission() {
        return requiredPermission;
    }

    /** Whether a redaction policy may withhold this field regardless of the caller's permission. */
    public boolean isSensitive() {
        return sensitive;
    }

    /** The Arabic header. Never blank; the registry test fails the build if it is. */
    public String labelAr() {
        return labelAr;
    }

    /** The English header. Never blank. */
    public String labelEn() {
        return labelEn;
    }

    /** Whether this field is money, and therefore drags the finance policy with it. */
    public boolean isMoney() {
        return kind == FieldKind.MONEY;
    }

    /** Whether this field is a grouping key. */
    public boolean isDimension() {
        return role == FieldRole.DIMENSION;
    }

    /** Whether this field is an aggregate. */
    public boolean isMeasure() {
        return role == FieldRole.MEASURE;
    }

    /**
     * The header to print for a locale.
     *
     * <p>{@code ar} maps to the Arabic label and anything else to the English one. Unknown locales do
     * not fall back to a machine key: an untranslated column header is a bug in the registry, and the
     * registry test is what finds it.
     */
    public String label(String locale) {
        return isArabic(locale) ? labelAr : labelEn;
    }

    /** Whether a locale string denotes Arabic. Shared by the header and the DOCX direction decision. */
    public static boolean isArabic(String locale) {
        return locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("ar");
    }

    /**
     * The immutable {@code key -> field} index.
     *
     * <p>Built once and unmodifiable. A lookup miss is a refusal at the caller, never a default field:
     * a report definition naming an unknown column must fail loudly rather than silently export a
     * different column than the author wrote.
     */
    public static Map<String, ReportField> index() {
        Map<String, ReportField> byKey = new LinkedHashMap<>();
        for (ReportField field : values()) {
            ReportField clash = byKey.put(field.key(), field);
            if (clash != null) {
                throw new IllegalStateException(
                    "Two report fields declare the key '" + field.key() + "'. A duplicated key means a "
                        + "report definition naming it resolves to whichever was registered last, which is "
                        + "how an export stops matching the report it claims to have come from.");
            }
        }
        return Collections.unmodifiableMap(byKey);
    }

    /** The field a stable key names. */
    public static Optional<ReportField> byKey(String key) {
        return Optional.ofNullable(key == null ? null : index().get(key));
    }

    /** Every field of one subject, in declaration order. */
    public static List<ReportField> ofSubject(ReportSubject subject) {
        return java.util.Arrays.stream(values())
            .filter(field -> field.subject() == subject)
            .toList();
    }
}