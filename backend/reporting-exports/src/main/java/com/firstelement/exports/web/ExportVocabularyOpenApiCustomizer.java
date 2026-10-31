package com.firstelement.exports.web;

import com.firstelement.exports.job.ExportFormat;
import com.firstelement.exports.job.ExportLocale;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Declares the export request's {@code format} and {@code locale} as {@code enum}s in the served OpenAPI
 * document.
 *
 * <h2>Why this exists rather than typing the fields</h2>
 *
 * <p>Typing the record components as {@link ExportFormat} and {@link ExportLocale} is the obvious answer
 * and it is wrong, for the reason Phase 15 measured on the inbox {@code filter} parameter: <b>Spring binds
 * an enum by Java constant name</b>. Typing them would have made {@code "csv"} a {@code 400} and
 * published {@code [CSV, DOCX]} as the accepted values, while a client sending the documented lowercase
 * {@code csv} — the value the server has always accepted — would be refused by a contract that claimed to
 * describe it.
 *
 * <p>So the components stay {@code String}, the binding is untouched, and the <b>document</b> carries the
 * constraint, which is where the constraint was actually missing.
 *
 * <h2>Why the values are derived rather than restated</h2>
 *
 * <p>From {@link ExportFormat#acceptedValues()} and {@link ExportLocale#acceptedValues()} — the same two
 * lists {@code POST /api/v1/exports} refuses against. An {@code @Schema(allowableValues = {"a", "b"})} would
 * have been the shorter spelling and would have been a third list to keep in step, which is precisely how
 * the {@code /inbox/counts} endpoint came to show a permanent zero on three tabs.
 *
 * <h2>WHY A REPLACED PROPERTY RATHER THAN A MUTATED ONE — the trap this class exists to avoid</h2>
 *
 * <p>springdoc hands out <b>shared</b> model objects. The {@code Schema} instance behind one property of
 * one model is frequently the same instance behind other properties, other models and other operations. So
 * the obvious implementation — {@code properties.get("format").setEnum(...)} — stamps the export
 * vocabulary onto unrelated paths across the whole contract. Phase 15 shipped exactly that bug in
 * {@code InboxFilterEnumOpenApiCustomizer}: five paths were corrupted and none of them was about the inbox,
 * and the only reason it was caught was a path-by-path diff of the served document against the committed
 * one.
 *
 * <p>So nothing reachable from anywhere else is touched. A fresh {@link StringSchema} is built here and
 * the map <em>entry</em> is replaced; the original object is left exactly as it was for whoever else still
 * holds a reference to it.
 *
 * <h2>Why it throws rather than doing nothing</h2>
 *
 * <p>If the model or either property is missing, this raises. A silently-skipped customiser is an enum
 * that vanished on a controller rename, and nobody would know until a client sent the value the server
 * refuses — which is how this defect started. The document is built on every request to
 * {@code /api/v1/openapi}, so a signature change that removes a property breaks the export, and the
 * export is promotion-blocking.
 *
 * <h2>Scoped by schema AND property name on purpose</h2>
 *
 * <p>Matching every property called {@code format} or {@code locale} would stamp the export vocabulary onto
 * any future model whose {@code format} means something else — a {@code ReportDefinition} format, a
 * {@code RenderFormat}. The pair {@code (CreateExportRequest, format)} names exactly the field whose
 * vocabulary this is.
 *
 * <h2>Why the CATALOGUE is stamped too</h2>
 *
 * <p>The other end of the same contract. {@code GET /api/v1/exports/catalogue} publishes {@code formats},
 * {@code locales}, {@code defaultFormat} and {@code defaultLocale} — the values a client is meant to build its
 * request <em>from</em>. Before this, those four were {@code type: array, items: {type: string}} and
 * {@code type: string}: a generated client typed them as {@code string[]} and rendered free-text inputs, so
 * the catalogue offered no closed set at all even though the server had exactly one.
 *
 * <p>That is the "advertises a choice the server refuses" defect with its other half showing: a client cannot
 * even be prevented from offering {@code PDF}, because the document does not say it may not. Stamping the
 * array's <b>items</b> — not the array — is the part that matters; an {@code enum} on an array property
 * constrains the array's length rather than its element values, which is a rule that is syntactically valid
 * and answers a different question.
 */
@Component
public class ExportVocabularyOpenApiCustomizer implements OpenApiCustomizer {

    /**
     * The schema names this module owns a closed vocabulary on, and the property names within them.
     *
     * <p>{@code public} because the contract suite asserts against these exact strings: it builds the served
     * shape from them rather than restating them, so a rename cannot leave the test asserting against a model
     * the customiser no longer touches. A test that restates the literal proves the test's own copy.
     */
    public static final String CREATE_EXPORT_REQUEST = "CreateExportRequest";
    public static final String EXPORT_CATALOGUE = "ExportCatalogue";
    public static final String FORMAT = "format";
    public static final String LOCALE = "locale";
    public static final String FORMATS = "formats";
    public static final String LOCALES = "locales";
    public static final String DEFAULT_FORMAT = "defaultFormat";
    public static final String DEFAULT_LOCALE = "defaultLocale";

    @Override
    public void customise(OpenAPI openApi) {
        stampCreateRequest(openApi);
        stampCatalogue(openApi);
    }

    private void stampCreateRequest(OpenAPI openApi) {
        io.swagger.v3.oas.models.media.Schema<?> model = requireModel(openApi, CREATE_EXPORT_REQUEST);
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = requireProperties(model);
        replaceScalar(properties, FORMAT, CREATE_EXPORT_REQUEST, ExportFormat.acceptedValues(),
            "The artefact format. One of " + ExportFormat.acceptedValues() + ". Absent means '"
                + ExportFormat.defaultFormat().wireValue() + "'. The database's \"ExportFormat\" enum "
                + "also declares XLSX, PDF and JSON, which this renderer does not produce; a request for "
                + "one of those is refused with EXPORT_FORMAT_UNSUPPORTED (422) rather than producing a "
                + "file whose extension lies about its contents.");
        replaceScalar(properties, LOCALE, CREATE_EXPORT_REQUEST, ExportLocale.acceptedValues(),
            "The language of the artefact's labels, dates and numbers. One of "
                + ExportLocale.acceptedValues() + ". Absent means '" + ExportLocale.defaultLocale().wireValue()
                + "', because the product is Arabic-first. An undeclared locale is refused (400) rather "
                + "than rendered in another language: a file that looks right and is in the wrong "
                + "language is the worst outcome this subsystem can produce.");
    }

    /**
     * The four vocabulary properties of the catalogue, stamped from the same two lists.
     *
     * <p>The defaults carry the accepted set as their {@code enum} rather than a one-value set, and their
     * concrete value as an example. An {@code enum} of exactly the current default would tell a generated
     * client that {@code defaultFormat} could only ever be {@code CSV}, which is a false statement about a
     * field whose value changes when the product's default does — and a generated client's optionality
     * handling would then make changing it a breaking change.
     */
    private void stampCatalogue(OpenAPI openApi) {
        io.swagger.v3.oas.models.media.Schema<?> model = requireModel(openApi, EXPORT_CATALOGUE);
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = requireProperties(model);

        replaceArray(properties, FORMATS, EXPORT_CATALOGUE, ExportFormat.acceptedValues(),
            "Every format POST /api/v1/exports accepts. Exactly the accepted set and not the database's "
                + "declared \"ExportFormat\" enum: offering PDF or XLSX here would be a dropdown entry "
                + "that always fails with EXPORT_FORMAT_UNSUPPORTED (422).");

        replaceArray(properties, LOCALES, EXPORT_CATALOGUE, ExportLocale.acceptedValues(),
            "Every locale POST /api/v1/exports accepts, and every locale a \"Locale\" column can store. "
                + "The product is bilingual, so the set is closed rather than a free BCP-47 tag: a locale "
                + "nothing can store could be stored on no column and would be answered in another "
                + "language's file.");

        replaceScalar(properties, DEFAULT_FORMAT, EXPORT_CATALOGUE, ExportFormat.acceptedValues(),
            "The format used when the create request names none: " + ExportFormat.defaultFormat().wireValue()
                + ". A member of the formats this catalogue publishes, and asserted to be one by "
                + "ExportCatalogue's own constructor.");
        replaceScalar(properties, DEFAULT_LOCALE, EXPORT_CATALOGUE, ExportLocale.acceptedValues(),
            "The locale used when the create request names none: " + ExportLocale.defaultLocale().wireValue()
                + ", because the product is Arabic-first. A member of the locales this catalogue "
                + "publishes, and asserted to be one by ExportCatalogue's own constructor.");
    }

    

    private static io.swagger.v3.oas.models.media.Schema<?> requireModel(OpenAPI openApi, String name) {
        if (openApi == null || openApi.getComponents() == null
            || openApi.getComponents().getSchemas() == null) {
            throw new IllegalStateException(
                "ExportVocabularyOpenApiCustomizer found no components in the document being served; "
                    + CREATE_EXPORT_REQUEST + " and " + EXPORT_CATALOGUE + " and their closed vocabularies "
                    + "are missing from the contract.");
        }
        io.swagger.v3.oas.models.media.Schema<?> model =
            openApi.getComponents().getSchemas().get(name);
        if (model == null) {
            throw new IllegalStateException(
                "ExportVocabularyOpenApiCustomizer found no " + name + " schema in the document being served. "
                    + "A create request or a catalogue whose format and locale are open strings is how a "
                    + "generated client came to send a value the server refuses, so this fails the document "
                    + "export instead.");
        }
        return model;
    }

    private static Map<String, io.swagger.v3.oas.models.media.Schema> requireProperties(
        io.swagger.v3.oas.models.media.Schema<?> model) {
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = model.getProperties();
        if (properties == null) {
            throw new IllegalStateException(
                "ExportVocabularyOpenApiCustomizer found no properties on " + model.getName()
                    + " in the document being served. The export vocabularies would be missing from the "
                    + "contract, which is the defect this customiser exists to prevent.");
        }
        return properties;
    }

    private static io.swagger.v3.oas.models.media.Schema required(
        Map<String, io.swagger.v3.oas.models.media.Schema> properties, String name, String model) {
        io.swagger.v3.oas.models.media.Schema existing = properties.get(name);
        if (existing == null) {
            throw new IllegalStateException(
                "ExportVocabularyOpenApiCustomizer found no '" + name + "' property on " + model
                    + "; the enum is missing from the contract, which is the defect this customiser exists "
                    + "to prevent.");
        }
        return existing;
    }

    /**
     * Swaps one property entry for a fresh schema carrying the enum.
     *
     * <p>The replacement is a NEW object. The existing one is deliberately left untouched: it may be shared
     * with other models and other operations, and mutating it is the bug described in the class header.
     */
    private static void replaceScalar(Map<String, io.swagger.v3.oas.models.media.Schema> properties,
                                     String name,
                                     String model,
                                     List<String> vocabulary,
                                     String description) {
        // The property's existence is checked HERE, at the point that is about to write to it, rather than
        // by the caller passing the existing schema in. `Format.FORMAT.equals("format")` was the previous
        // dispatch and it silently picked the LOCALE vocabulary for `defaultFormat` — a bug this suite was
        // written to be able to catch, so the dispatch is now a parameter and a caller cannot get it wrong
        // by naming a property whose name happens to share a prefix.
        required(properties, name, model);
        StringSchema schema = new StringSchema();
        schema.setType("string");
        schema.setEnum(List.copyOf(vocabulary));
        schema.setDescription(description);
        schema.setExample(vocabulary.isEmpty() ? null : vocabulary.get(0));
        properties.put(name, schema);
    }

    /**
     * Replaces one array property with a fresh array whose ITEMS carry the enum.
     *
     * <p>The existence check is here for the same reason. The item schema is a new object and the array
     * schema is a new object, so nothing reachable from anywhere else is touched.
     */
    private static void replaceArray(Map<String, io.swagger.v3.oas.models.media.Schema> properties,
                                     String name,
                                     String model,
                                     List<String> vocabulary,
                                     String description) {
        required(properties, name, model);
        ArraySchema array = new ArraySchema();
        array.setType("array");
        StringSchema items = new StringSchema();
        items.setType("string");
        items.setEnum(List.copyOf(vocabulary));
        items.setExample(vocabulary.isEmpty() ? null : vocabulary.get(0));
        array.setItems(items);
        array.setDescription(description);
        array.setExample(List.copyOf(vocabulary));
        properties.put(name, array);
    }
}