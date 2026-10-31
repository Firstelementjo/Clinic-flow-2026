package com.firstelement.exports.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every authoring request <b>requires</b>, in one place, enforced by the services and published by
 * the OpenAPI document.
 *
 * <h2>Why this table exists at all</h2>
 *
 * <p>Phase 32: the Angular agent generated a client from the served document, sent the requests the document
 * described, and six of them could not succeed. {@code retentionClass}, {@code redactionPolicy} and
 * {@code timezone} were documented optional and are refused when absent; {@code columns} and
 * {@code measures} were documented as any array and one of them is a {@code CHECK} violation that arrives
 * as a {@code 500}; and a retirement's successor was not in the document at all.
 *
 * <p>That is the same defect the catalogue's closed vocabularies were, one layer up: <b>a value the server
 * publishes must not be narrower than what it actually does</b>. Here the published value is the request
 * <em>schema</em>, and it was narrower (more permissive) than the server. A generated client cannot
 * discover this at compile time; it discovers it by getting a {@code 400} — or, for {@code measures}, a
 * {@code 500} that pages an operator for a client mistake.
 *
 * <h2>WHY ONE TABLE RATHER THAN A LIST IN THE DOCUMENT AND CHECKS IN THE SERVICES</h2>
 *
 * <p>Because a list in the document and checks in the services are two answers to one question, and Phase 32
 * is the record of what happens when they are written by two people in two phases: they disagree, and the
 * one that is wrong is invisible until a client is generated from it. {@link Request#require(Map)} is what
 * the services call; {@code AuthoringContractOpenApiCustomizer} is what turns the same
 * {@link Request#properties()} into the document's {@code required} array. Adding a rule to a service
 * without adding it here is possible, and {@code AuthoringContractTest} exists to make it fail.
 *
 * <h2>Every rule names the constraint that made it one</h2>
 *
 * <p>Each {@link Required} carries the {@code CHECK} constraint from {@code V20261005060000} that already
 * refuses the value at the database. That constraint is the <em>backstop</em>, not the gate: a
 * {@code CHECK} that fires produces {@code DataIntegrityViolationException}, which this module's advice does
 * not handle, so it reaches the application floor and answers {@code 500 INTERNAL_ERROR} at ERROR level.
 * For a client mistake that is the worst possible answer, and it is the defect this table removes.
 *
 * <p>The table is therefore not a wish list. Where a rule exists here, the schema agrees — and the
 * {@code AuthoringContractTest} sweep asserts that agreement for every CHECK constraint on
 * {@code report_definitions}, {@code report_versions}, {@code document_templates} and
 * {@code document_template_versions}, so a constraint that becomes reachable without a rule here is a test
 * failure rather than a page.
 *
 * <h2>Where the check runs, and why not earlier</h2>
 *
 * <p>Authorization first, target scope second, required fields third. Every authoring refusal is a
 * non-disclosing {@code 404} ({@link ReportAuthoringGuard}), and a {@code 400} that arrives <em>before</em>
 * the {@code 404} tells a caller without {@code report:manage} that this endpoint validates its body —
 * which is a difference between routes, on a surface whose entire answer vocabulary is meant to be uniform.
 * So the body is checked after the target has been resolved, exactly where {@code createVersion} already
 * resolved its columns.
 */
public final class AuthoringRequirements {

    private AuthoringRequirements() {
    }

    /** What counts as "supplied" for one property. */
    public enum Shape {

        /**
         * A scalar the request must carry. A {@code String} must be non-blank; anything else must be
         * non-{@code null}.
         *
         * <p>Blank counts as absent because that is what it is to every CHECK on these tables:
         * {@code report_definitions_retention_class_check} is {@code length(btrim(…)) > 0}.
         */
        TEXT,

        /**
         * A collection the request must carry with at least one entry.
         *
         * <p>"At least one" rather than "non-null" because {@code report_versions_measures_present_check} is
         * {@code jsonb_array_length("measures") >= 1}: an empty array is the exact value the database
         * refuses, and the refusal was arriving as a {@code 500}.
         */
        NON_EMPTY_LIST
    }

    /**
     * One required property of one request model.
     *
     * @param property     the request property, by the name the record component and the JSON body share
     * @param shape        what "supplied" means for it
     * @param constraint   the {@code CHECK} constraint that already refuses the missing value, which is the
     *                     reason this is a rule and not a preference
     * @param because      why the constraint exists, in one sentence an author can act on
     * @param vocabulary   the closed set of accepted values, or empty when the field is free text
     */
    public record Required(String property, Shape shape, String constraint, String because,
                           List<String> vocabulary) {

        public Required {
            vocabulary = List.copyOf(vocabulary);
        }

        /** A scalar rule with no closed vocabulary. */
        public static Required text(String property, String constraint, String because) {
            return new Required(property, Shape.TEXT, constraint, because, List.of());
        }

        /** A scalar rule whose value must be one of a closed set. */
        public static Required text(String property, String constraint, String because,
                                    List<String> vocabulary) {
            return new Required(property, Shape.TEXT, constraint, because, vocabulary);
        }

        /** A collection rule: present, and with at least one entry. */
        public static Required nonEmptyList(String property, String constraint, String because) {
            return new Required(property, Shape.NON_EMPTY_LIST, constraint, because, List.of());
        }

        /** The refusal a client receives when this rule is not met. Names the property, always. */
        String refusal() {
            String demand = Shape.NON_EMPTY_LIST == shape
                ? "at least one entry in '" + property + "'"
                : "'" + property + "'";
            return "A " + Request.subjectOf(this) + " requires " + demand
                + " (" + constraint + ": " + because + ")";
        }

        /**
         * Whether one value satisfies this rule.
         *
         * <p>Deliberately the minimum that keeps the database CHECK from firing. Everything richer — is the
         * column declared, is the measure really a measure, is the timezone a real zone — is a different
         * rule with a different message, and folding them together here would make one message answer
         * questions it cannot.
         */
        boolean satisfiedBy(Object value) {
            if (value == null) {
                return false;
            }
            if (Shape.NON_EMPTY_LIST == shape) {
                return entriesOf(value) > 0;
            }
            return !(value instanceof CharSequence text) || !text.toString().isBlank();
        }

        private static int entriesOf(Object value) {
            if (value instanceof java.util.Collection<?> collection) {
                return collection.size();
            }
            if (value instanceof Object[] array) {
                return array.length;
            }
            return 1;
        }
    }

    /**
     * One authoring request model's requirements.
     *
     * @param model    the OpenAPI schema name, which is the Java record's simple name
     * @param subject  the noun the refusal names, so the message says which thing is missing a field
     * @param required the required properties, in the order they should be named to a caller
     */
    public record Request(String model, String subject, List<Required> required) {

        public Request {
            required = List.copyOf(required);
        }

        /** The property names, which is exactly the document's {@code required} array. */
        public List<String> properties() {
            return required.stream().map(Required::property).toList();
        }

        /**
         * Refuses anything that does not carry every required property.
         *
         * <p>Throws {@link IllegalArgumentException}, which this module's advice renders as
         * {@code 400 BAD_REQUEST} — a refusal, not a server fault. First failure wins, and the order is the
         * declaration order above, so an author fixing a request fixes it the same way twice.
         *
         * @param values the request's properties by name; a name absent from the map reads as absent from
         *                the request, which is the same answer
         */
        public void require(Map<String, Object> values) {
            for (Required rule : required) {
                if (!rule.satisfiedBy(values.get(rule.property()))) {
                    throw new IllegalArgumentException(rule.refusal());
                }
            }
        }

        /**
         * The noun a rule's refusal uses.
         *
         * <p>Read back off the rule rather than passed to it, so a rule can never be declared against one
         * request and enforced with another request's noun.
         */
        static String subjectOf(Required rule) {
            for (Request request : requests()) {
                if (request.required().contains(rule)) {
                    return request.subject();
                }
            }
            throw new IllegalStateException(
                "an authoring requirement for '" + rule.property() + "' belongs to no declared request, so "
                    + "its refusal has no subject. Every Required must be listed on exactly one Request.");
        }
    }

    // ------------------------------------------------------------------
    // the requests
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/v1/exports/report-definitions}.
     *
     * <p>{@code localeDefault}, {@code requiredPermissions} and both descriptions are genuinely optional:
     * the locale falls back to Arabic because the product is Arabic-first, the permission list defaults to
     * empty, and a description is prose. Everything else below is required by a CHECK, and the four that
     * the Phase 32 sweep found missing from the document are {@code timezone}, {@code redactionPolicy},
     * {@code retentionClass} and — on the version request — {@code measures}.
     */
    public static final Request CREATE_REPORT_DEFINITION = new Request(
        "CreateReportDefinitionRequest", "report definition", List.of(
            Required.text("key", "report_definitions_key_check",
                "a report is named by its key, and a blank one names nothing"),
            Required.text("nameAr", "report_definitions_name_ar_check",
                "the product is Arabic-first, so a report with no Arabic name is dead configuration"),
            Required.text("nameEn", "report_definitions_name_en_check",
                "the product is bilingual, so a report with no English name is dead configuration"),
            Required.text("timezone", "report_definitions_timezone_check",
                "a report that buckets by day must not inherit the server's zone, or the same report "
                    + "produces different numbers on two workers; the zone must therefore be declared, and "
                    + "it must be an IANA zone PostgreSQL knows"),
            Required.text("redactionPolicy", "report_definitions_redaction_policy_check",
                "an undeclared policy would make the job's redactionPolicy column a claim rather than a "
                    + "decision",
                ExportRedactionPolicy.declared()),
            Required.text("retentionClass", "report_definitions_retention_class_check",
                "the retention class decides what an operator may purge, so a definition without one has "
                    + "artefacts with no window")));

    /**
     * {@code PATCH /api/v1/exports/report-definitions/{id}}.
     *
     * <p>{@code expectedVersion} is not a CHECK but it is required by the server for the same reason: it is
     * the row version the rename is guarded on, and an unguarded rename is a last-write-wins overwrite of
     * somebody else's edit. Documenting it as optional is what produced a client that could overwrite
     * another author's labels.
     */
    public static final Request RENAME_REPORT_DEFINITION = new Request(
        "RenameReportDefinitionRequest", "rename", List.of(
            Required.text("nameAr", "report_definitions_name_ar_check",
                "a rename that clears the Arabic name is not a rename"),
            Required.text("nameEn", "report_definitions_name_en_check",
                "a rename that clears the English name is not a rename"),
            Required.text("expectedVersion", "report_definitions_version_check",
                "the row's own version, which the update is guarded on; an unguarded rename is a "
                    + "last-write-wins overwrite, which is the one thing that column exists to prevent")));

    /**
     * {@code POST /api/v1/exports/report-definitions/{id}/versions}.
     *
     * <p>{@code columns} and {@code measures} are the two the Phase 32 sweep found, and they fail
     * differently. Empty {@code columns} reached {@link ReportFieldRegistry#resolve} and was refused with
     * {@code 422 REPORT_COLUMN_UNDECLARED} — correct enough, but it names an undeclared <em>column</em> when
     * the caller omitted a <em>field</em>, which is a different problem with a different fix. Empty
     * {@code measures} passed every check and reached {@code report_versions_measures_present_check}, so it
     * was a {@code 500} at ERROR level for a client mistake.
     */
    public static final Request CREATE_REPORT_VERSION = new Request(
        "CreateReportVersionRequest", "report version", List.of(
            Required.nonEmptyList("columns", "report_versions_columns_shape_check",
                "a report that selects nothing exports nothing, so it is not a report"),
            Required.nonEmptyList("measures", "report_versions_measures_present_check",
                "a report with no measure is not a report")));

    /**
     * {@code .../retire}, on either the report or the template side — one request record, one table of
     * rules, because {@code report_versions_retired_check} and
     * {@code document_template_versions_retired_check} say the same thing.
     *
     * <p>This one was enforced in the controller rather than in a service, which had a second consequence
     * beyond the missing document entry: it ran <em>before</em> the service, so a caller without
     * {@code report:manage} posting {@code {}} was answered {@code 400} while every other authoring refusal
     * is a non-disclosing {@code 404}. The check now lives in the services, after authorization, and the
     * surface answers one vocabulary again.
     */
    public static final Request RETIRE_VERSION = new Request(
        "RetireVersionRequest", "retirement", List.of(
            Required.text("successorVersion", "report_versions_retired_check",
                "it requires retiredAt and supersededByVersionId, and a retirement that does not say "
                    + "where the body went is not a rollback, it is a deletion with extra steps")));

    /**
     * {@code POST /api/v1/exports/document-templates}.
     *
     * <p>{@code locale} is optional for the same reason {@code localeDefault} is: it falls back to Arabic.
     * {@code kind} is not on the request at all and must never be — the renderer produces DOCX only, so
     * the service pins it and a PDF template would be a job that fails after it has been paid for.
     */
    public static final Request CREATE_DOCUMENT_TEMPLATE = new Request(
        "CreateDocumentTemplateRequest", "document template", List.of(
            Required.text("key", "document_templates_key_check",
                "a template is named by its key, and a blank one names nothing"),
            Required.text("nameAr", "document_templates_name_ar_check",
                "the product is Arabic-first, so a template with no Arabic name is dead configuration"),
            Required.text("nameEn", "document_templates_name_en_check",
                "the product is bilingual, so a template with no English name is dead configuration"),
            Required.text("timezone", "document_templates_timezone_check",
                "the same real-zone validation as a report definition, so a template and the report it "
                    + "renders cannot disagree about what a date means")));

    /**
     * The closed vocabulary {@code redactionPolicy} must hold, attached to its rule rather than restated by
     * the document.
     *
     * <p>{@code report_definitions_redaction_policy_check} only requires a non-blank string, so the schema
     * would accept {@code "typo"} and the service refuses it — a value the server publishes as accepted and
     * is not. The enum here is {@link ExportRedactionPolicy#declared()}, which is the same list
     * {@code policyOf} refuses against and {@code GET /api/v1/exports/authoring} publishes.
     */
    public static List<String> redactionPolicyVocabulary() {
        for (Required rule : CREATE_REPORT_DEFINITION.required()) {
            if ("redactionPolicy".equals(rule.property())) {
                return rule.vocabulary();
            }
        }
        throw new IllegalStateException(
            "CreateReportDefinitionRequest no longer requires 'redactionPolicy', so the vocabulary the "
                + "document publishes for it has nowhere to live. Either restore the rule or delete the "
                + "vocabulary with it -- never leave the field documented and unrefused.");
    }

    /** Every request model the document and the services share, for the sweep and for the document export. */
    public static List<Request> requests() {
        return List.of(CREATE_REPORT_DEFINITION, RENAME_REPORT_DEFINITION, CREATE_REPORT_VERSION,
            RETIRE_VERSION, CREATE_DOCUMENT_TEMPLATE);
    }

    /**
     * One requirement, by the model and property the document names.
     *
     * <p>Exposed so the contract suite can say <em>which</em> constraint makes a property required in the
     * message of the assertion that fails when the document omits it. Without it the failure reads "expected
     * retentionClass to be required" and the reader has to go and find out why.
     */
    public static Required ruleOf(String model, String property) {
        for (Request request : requests()) {
            if (request.model().equals(model)) {
                for (Required rule : request.required()) {
                    if (rule.property().equals(property)) {
                        return rule;
                    }
                }
            }
        }
        throw new IllegalStateException(
            "no authoring requirement is declared for " + model + "." + property + ". A test, a "
                + "customiser or a service asking for a rule that does not exist is asking the wrong "
                + "question; adding the rule here is what makes it enforceable and publishable.");
    }

    /**
     * A property map from alternating name/value arguments, tolerating {@code null} values.
     *
     * <p>{@code Map.of} refuses a {@code null} value, and an absent request field is exactly the case these
     * maps exist to describe — a map built with {@code Map.of} would throw {@link NullPointerException}
     * while checking whether the request was allowed to omit the field, which is a {@code 500} for exactly
     * the input this whole class exists to refuse.
     *
     * <p>Only the properties a {@link Request} actually requires need to be listed. A name not in a
     * request's requirements is never read from the map, and a requirement whose name is missing from the
     * map reads as absent — which is the same answer the caller would get, and is asserted rather than
     * assumed by {@code AuthoringContractTest}.
     */
    public static Map<String, Object> values(Object... propertyThenValue) {
        if (propertyThenValue.length % 2 != 0) {
            throw new IllegalArgumentException(
                "AuthoringRequirements.values takes alternating property/value arguments and was given "
                    + propertyThenValue.length + " of them");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < propertyThenValue.length; i += 2) {
            values.put(String.valueOf(propertyThenValue[i]), propertyThenValue[i + 1]);
        }
        return values;
    }
}