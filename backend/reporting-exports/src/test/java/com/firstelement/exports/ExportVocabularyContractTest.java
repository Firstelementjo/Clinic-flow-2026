package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.exports.catalog.ExportCatalogue;
import com.firstelement.exports.job.ExportFormat;
import com.firstelement.exports.job.ExportLocale;
import com.firstelement.exports.web.ExportVocabularyOpenApiCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The served OpenAPI document declares the export request's closed vocabularies.
 *
 * <h2>Why this suite deliberately needs neither Spring nor a database</h2>
 *
 * <p>The defect it closes was a <em>contract</em> defect: {@code format} and {@code locale} were bare
 * {@code type: string}, and a client generated from that sends whatever it likes and finds out at run
 * time. A contract defect must be provable on a host with no Docker, because every other suite in this
 * module needs PostgreSQL and a suite that needs PostgreSQL is a suite that can go unrun.
 *
 * <h2>What "declared as an enum" means here, precisely</h2>
 *
 * <p>Not "the document mentions the words". The served property must carry {@code type: string} and an
 * {@code enum} whose members are the exact values {@code POST /api/v1/exports} accepts, so a generated
 * client produces a closed union instead of a free string. Both directions of that claim are asserted:
 * nothing documented and refused, and nothing accepted and undocumented.
 *
 * <h2>The shared-Schema trap, and why this suite is shaped around it</h2>
 *
 * <p>springdoc hands out SHARED model objects, so the obvious implementation — mutate the property's schema
 * in place — stamps the export vocabulary onto unrelated schemas across the whole contract. Phase 15 shipped
 * exactly that bug in the inbox filter customiser, corrupted five unrelated paths, and caught it only by
 * diffing the served document against the committed one path at a time.
 *
 * <p>{@link #noOtherSchemaIsContaminated()} shares the {@code Schema} instance the way springdoc does, so
 * the trap reproduces; a test with freshly allocated schemas would pass against the buggy implementation
 * and prove nothing.
 */
class ExportVocabularyContractTest {

    private static final java.util.UUID PROJECT =
        java.util.UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");

    private final ExportVocabularyOpenApiCustomizer customizer =
        new ExportVocabularyOpenApiCustomizer();

    @Test
    @DisplayName("format is declared as an enum of the values POST actually accepts")
    void formatIsDeclaredAsAnEnum() {
        io.swagger.v3.oas.models.media.Schema<?> format = customise().get("format");

        assertThat(format).isNotNull();
        assertThat(format.getType()).isEqualTo("string");
        assertThat(enumOf(format)).containsExactlyElementsOf(ExportFormat.acceptedValues());
        // Not the schema's declared enum. Offering PDF would advertise a value the create route refuses
        // with EXPORT_FORMAT_UNSUPPORTED, which is a dropdown that always fails.
        assertThat(enumOf(format))
            .as("the published enum must be the ACCEPTED set, not the schema's declared set")
            .containsExactly("CSV", "DOCX");
        assertThat(enumOf(format)).doesNotContain("PDF", "XLSX", "JSON");
    }

    @Test
    @DisplayName("locale is declared as an enum of the two locales the database can store")
    void localeIsDeclaredAsAnEnum() {
        io.swagger.v3.oas.models.media.Schema<?> locale = customise().get("locale");

        assertThat(locale).isNotNull();
        assertThat(locale.getType()).isEqualTo("string");
        assertThat(enumOf(locale)).containsExactlyElementsOf(ExportLocale.acceptedValues());
        assertThat(enumOf(locale)).containsExactly("ar", "en");
        // Lower-case, because that is what "Locale" holds and what the server accepts. An enum publishing
        // AR/EN would invite a request the binder refuses.
        assertThat(enumOf(locale)).noneMatch(value -> value.matches("^[A-Z]+$"));
    }

    @Test
    @DisplayName("nothing is documented and refused: every published format round-trips through POST")
    void everyPublishedFormatIsAccepted() {
        for (String documented : (List<String>) customise().get("format").getEnum()) {
            ExportFormat format = ExportFormat.requireAccepted(documented);
            assertThat(format.wireValue()).isEqualTo(documented);
        }
        // And the closure holds the other way: the create route accepts exactly the published list.
        for (ExportFormat accepted : ExportFormat.renderable()) {
            assertThat(ExportFormat.acceptedValues()).contains(accepted.wireValue());
        }
        assertThat(ExportFormat.acceptedValues()).hasSize(2);
    }

    @Test
    @DisplayName("nothing is accepted and undocumented: every accepted locale is published")
    void everyAcceptedLocaleIsPublished() {
        for (ExportLocale locale : ExportLocale.values()) {
            assertThat(ExportLocale.acceptedValues()).contains(locale.wireValue());
            assertThat(ExportLocale.require(locale.wireValue())).isSameAs(locale);
        }
        assertThat(ExportLocale.acceptedValues()).hasSize(2);
    }

    @Test
    @DisplayName("NO other schema in the document gains the export vocabulary -- the shared-Schema trap")
    void noOtherSchemaIsContaminated() {
        // The instance springdoc really would hand out: one StringSchema object referenced by an unrelated
        // model and by a query parameter on an unrelated path.
        StringSchema shared = new StringSchema().type("string");
        Map<String, io.swagger.v3.oas.models.media.Schema> unrelatedProperties =
            new LinkedHashMap<>();
        unrelatedProperties.put("locale", shared);
        unrelatedProperties.put("note", shared);
        io.swagger.v3.oas.models.media.Schema<?> unrelated = new io.swagger.v3.oas.models.media.Schema<>()
            .type("object");
        unrelated.setProperties(unrelatedProperties);

        Map<String, io.swagger.v3.oas.models.media.Schema> createProperties = new LinkedHashMap<>();
        StringSchema anotherShared = new StringSchema().type("string");
        createProperties.put("format", anotherShared);
        createProperties.put("locale", anotherShared);
        createProperties.put("filters", anotherShared);
        io.swagger.v3.oas.models.media.Schema<?> createRequest =
            new io.swagger.v3.oas.models.media.Schema<>().type("object");
        createRequest.setProperties(createProperties);

        // A parameter on another path sharing the very same instance.
        Parameter foreignParameter = new Parameter().name("projectId").in("query").required(false)
            .schema(anotherShared);

        Map<String, io.swagger.v3.oas.models.media.Schema> schemas = new LinkedHashMap<>();
        schemas.put("CreateExportRequest", createRequest);
        schemas.put("SomeOtherModel", unrelated);
        schemas.put("ExportCatalogue", cataloguePropertiesAsSpringdocSeedsThem());
        io.swagger.v3.oas.models.Components components = new io.swagger.v3.oas.models.Components()
            .schemas(schemas);

        Operation otherOperation = new Operation();
        otherOperation.setParameters(new ArrayList<>(List.of(foreignParameter)));
        Paths paths = new Paths();
        paths.put("/api/v1/notes", new PathItem().get(otherOperation));

        OpenAPI document = new OpenAPI();
        document.setComponents(components);
        document.setPaths(paths);

        customizer.customise(document);

        // The two we meant carry the vocabulary...
        assertThat(createRequest.getProperties().get("format").getEnum())
            .containsExactlyElementsOf(ExportFormat.acceptedValues());
        assertThat(createRequest.getProperties().get("locale").getEnum())
            .containsExactlyElementsOf(ExportLocale.acceptedValues());

        // ...and nothing else does. Spot-checking three hand-picked spots is what let the Phase-15 leak
        // through, so the assertion is a count over the WHOLE document instead.
        assertThat(unrelatedProperties.get("locale").getEnum())
            .as("an unrelated model's locale must stay an unconstrained string")
            .isNull();
        assertThat(unrelatedProperties.get("note").getEnum()).isNull();
        assertThat(anotherShared.getEnum())
            .as("the shared instance the foreign parameter points at must be untouched")
            .isNull();

        // Counted over MODELS, not over properties. The two models that own this vocabulary now contribute
        // four properties between them (format, locale, formats, locales, defaultFormat, defaultLocale), so a
        // property count would have to be restated each time a vocabulary property is added -- and a
        // restated number is a number nobody re-reads. Naming the two models is also the stronger claim:
        // it says the vocabulary is on the request and on the catalogue and nowhere else.
        assertThat(modelsCarrying(document, ExportFormat.acceptedValues()))
            .as("only the two models that OWN an export vocabulary may carry it")
            .containsExactlyInAnyOrder("CreateExportRequest", "ExportCatalogue");
        assertThat(modelsCarrying(document, ExportLocale.acceptedValues()))
            .containsExactlyInAnyOrder("CreateExportRequest", "ExportCatalogue");
    }

    /**
     * The catalogue model exactly as springdoc seeds it before the customiser runs.
     *
     * <p>Arrays whose items are unconstrained strings, and two unconstrained scalars. That is the real
     * "before" shape and it is what makes the array assertions meaningful: a customiser that stamped
     * {@code enum} onto the array itself -- constraining its length rather than its elements -- would leave
     * the items free, and {@link #itemEnumOf} would fail.
     */
    private static io.swagger.v3.oas.models.media.Schema<?> cataloguePropertiesAsSpringdocSeedsThem() {
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = new LinkedHashMap<>();
        for (String name : List.of("formats", "locales")) {
            properties.put(name, new io.swagger.v3.oas.models.media.ArraySchema()
                .type("array").items(new StringSchema().type("string")));
        }
        for (String name : List.of("defaultFormat", "defaultLocale")) {
            properties.put(name, new StringSchema().type("string"));
        }
        io.swagger.v3.oas.models.media.Schema<?> model =
            new io.swagger.v3.oas.models.media.Schema<>().type("object");
        model.setProperties(properties);
        return model;
    }

    @Test
    @DisplayName("a renamed model fails the document export rather than silently losing the enums")
    void aRenamedModelIsLoud() {
        // A silently-skipped customiser is an enum that vanished on a controller rename, and nobody would
        // know until a client sent a value the server refuses. The document export is promotion-blocking,
        // so failing it is the right severity.
        assertThatThrownBy(() -> customizer.customise(new OpenAPI()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CreateExportRequest");

        assertThatThrownBy(() -> customizer.customise(documentWithoutFormat()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("format");

        assertThatThrownBy(() -> customizer.customise(documentWithoutLocale()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("locale");

        // The catalogue's four properties are equally load-bearing and equally undeclared in this list
        // until now: dropping `formats` from the document would have left a client rendering free-text
        // format entries, and nothing in the build would have said so.
        assertThatThrownBy(() -> customizer.customise(documentWithout("formats")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("formats");
        assertThatThrownBy(() -> customizer.customise(documentWithout("locales")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("locales");
        assertThatThrownBy(() -> customizer.customise(documentWithout("defaultFormat")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("defaultFormat");
        assertThatThrownBy(() -> customizer.customise(documentWithout("defaultLocale")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("defaultLocale");

        // A catalogue whose schemas are gone entirely -- the rename -- must fail the export too.
        assertThatThrownBy(() -> customizer.customise(documentWithoutCatalogueModel()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ExportCatalogue");
    }

    // ==================================================================
    // the catalogue publishes the same two closed sets
    // ==================================================================

    @Test
    @DisplayName("the catalogue publishes the accepted sets as arrays of enums, not of open strings")
    void theCataloguePublishesClosedSets() {
        Map<String, io.swagger.v3.oas.models.media.Schema<?>> catalogue = catalogueProperties();

        // On the ITEMS, not on the array. An `enum` on an array property constrains its LENGTH; a generated
        // client would render "pick between 1 and 2 items" rather than "pick one of CSV or DOCX". That is
        // syntactically valid, answers a different question, and is the shape springdoc produces for a
        // List<String> component — which is what the catalogue had.
        assertThat(itemEnumOf(catalogue.get("formats")))
            .containsExactlyElementsOf(ExportFormat.acceptedValues());
        assertThat(itemEnumOf(catalogue.get("locales")))
            .containsExactlyElementsOf(ExportLocale.acceptedValues());

        // A default carries the ACCEPTED set as its enum, not a one-value set. An enum of exactly the
        // current default would tell a generated client the field can only ever hold one value, which is a
        // false statement about a field that changes when the product's default does.
        assertThat(enumOf(catalogue.get("defaultFormat")))
            .containsExactlyElementsOf(ExportFormat.acceptedValues());
        assertThat(enumOf(catalogue.get("defaultLocale")))
            .containsExactlyElementsOf(ExportLocale.acceptedValues());

        assertThat(enumOf(catalogue.get("defaultFormat")))
            .as("and the default it names is a member of the set the catalogue publishes")
            .contains(ExportFormat.defaultFormat().wireValue());
        assertThat(enumOf(catalogue.get("defaultLocale")))
            .contains(ExportLocale.defaultLocale().wireValue());
    }

    @Test
    @DisplayName("nothing the catalogue publishes is a value POST refuses -- the subset property")
    void theCataloguePublishesOnlyAcceptedValues() {
        // Asked of the ACCEPTING code, not settled by comparing two lists: the claim is "POST accepts every
        // published value", and calling `requireAccepted` is what POST does. `containsExactly(
        // acceptedValues())` would pass even if both were changed together to publish something new,
        // because it never asks the server whether it would accept it.
        for (String published : itemEnumOf(catalogueProperties().get("formats"))) {
            assertThat(ExportFormat.requireAccepted(published)).isNotNull();
        }
        for (String published : itemEnumOf(catalogueProperties().get("locales"))) {
            assertThat(ExportLocale.require(published)).isNotNull();
        }
        // And the direction that would actually bite: nothing in either published set is one of the schema's
        // declared-but-unrenderable formats. PDF is the value the whole vocabulary exists to withhold.
        assertThat(itemEnumOf(catalogueProperties().get("formats")))
            .doesNotContain("PDF", "XLSX", "JSON");
    }

    @Test
    @DisplayName("a catalogue carrying an unaccepted value cannot be CONSTRUCTED, only documented against")
    void anUnacceptedPublishedValueCannotBeConstructed() {
        // The document above says what the contract claims. This is the half that makes the claim structural:
        // `formats` is a List<String>, so the compiler permits "PDF", and without a constructor check the
        // module would publish a dropdown entry that always 422s and no build would say so.
        assertThatThrownBy(() -> catalogueWith(List.of("CSV", "PDF"), ExportLocale.acceptedValues(),
                ExportFormat.defaultFormat().wireValue(), ExportLocale.defaultLocale().wireValue()))
            .as("PDF is a declared \"ExportFormat\" value and is still not accepted, which is the whole "
                + "distinction requireAccepted draws")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PDF");

        assertThatThrownBy(() -> catalogueWith(ExportFormat.acceptedValues(), List.of("ar", "fr"),
                ExportFormat.defaultFormat().wireValue(), ExportLocale.defaultLocale().wireValue()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fr");

        // A default outside the set it defaults from: DOCX IS accepted, so this cannot be caught by the
        // accepted-set check, and it is the case that would ship a "default" the server never offers.
        assertThatThrownBy(() -> catalogueWith(List.of("CSV"), ExportLocale.acceptedValues(),
                "DOCX", ExportLocale.defaultLocale().wireValue()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("defaultFormat");

        // An empty published set describes a client that cannot build a request, which is the defect the
        // catalogue route exists to remove.
        assertThatThrownBy(() -> catalogueWith(List.of(), ExportLocale.acceptedValues(),
                ExportFormat.defaultFormat().wireValue(), ExportLocale.defaultLocale().wireValue()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty published set");
    }

    @Test
    @DisplayName("the catalogue the service builds IS constructible, so the check above is not blanket")
    void theServiceVocabularyIsConstructible() {
        // The control for the test above. Without it, a constructor that refused everything would pass all
        // four assertions and the catalogue would be unusable — a refusal that satisfies every negative.
        assertThat(catalogueWith(ExportFormat.acceptedValues(), ExportLocale.acceptedValues(),
                ExportFormat.defaultFormat().wireValue(), ExportLocale.defaultLocale().wireValue())
            .formats()).containsExactly("CSV", "DOCX");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A catalogue with empty lists everywhere, and the four vocabulary values under test. */
    private static ExportCatalogue catalogueWith(List<String> formats, List<String> locales,
                                                 String defaultFormat, String defaultLocale) {
        return new ExportCatalogue(PROJECT, formats, locales, defaultFormat, defaultLocale,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "note");
    }

    /**
     * The properties of {@code CreateExportRequest} in the served document, after customisation.
     *
     * <p>Returns {@code Map<String, Schema<?>>} rather than the raw map so the assertions below can be
     * written against a typed schema. The raw map is what the customiser sees; {@link #asTyped} is the
     * unchecked narrowing, and it is safe because a replaced property is always a {@code StringSchema}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, io.swagger.v3.oas.models.media.Schema<?>> customise() {
        OpenAPI document = servedShape();
        customizer.customise(document);
        return asTyped(document.getComponents().getSchemas().get("CreateExportRequest").getProperties());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, io.swagger.v3.oas.models.media.Schema<?>> asTyped(
        Map<String, io.swagger.v3.oas.models.media.Schema> raw) {
        return (Map<String, io.swagger.v3.oas.models.media.Schema<?>>) (Map<?, ?>) raw;
    }

    /**
     * The {@code enum} of a served property, as strings.
     *
     * <p>{@code Schema.getEnum()} is declared to return {@code List<? extends CAP>} where the capture is the
     * schema's own type parameter, so an assertion against it will not infer {@code List<String>} and the
     * comparison is refused at compile time. The narrowing is safe here because the customiser only ever
     * writes {@code List<String>}, and {@link #everyPublishedFormatIsAccepted()} proves the contents are
     * the accepted wire values by calling the accepting code rather than trusting the cast.
     */
    @SuppressWarnings("unchecked")
    private static List<String> enumOf(io.swagger.v3.oas.models.media.Schema<?> property) {
        return (List<String>) property.getEnum();
    }

    /**
     * The names of the models that carry this vocabulary, anywhere in the document.
     *
     * <p>Counts models rather than properties, and checks the array's ITEMS rather than the array. A
     * vocabulary that reached an unrelated model through the shared-schema trap would show up here whichever
     * way it was stamped, because {@link #carries} follows both the scalar and the array-item shape.
     */
    private static List<String> modelsCarrying(OpenAPI document, List<String> vocabulary) {
        List<String> carrying = new ArrayList<>();
        for (Map.Entry<String, io.swagger.v3.oas.models.media.Schema> entry
            : document.getComponents().getSchemas().entrySet()) {
            io.swagger.v3.oas.models.media.Schema<?> model = entry.getValue();
            if (model == null || model.getProperties() == null) {
                continue;
            }
            for (io.swagger.v3.oas.models.media.Schema property : model.getProperties().values()) {
                if (property != null && carries(property, vocabulary)) {
                    carrying.add(entry.getKey());
                    break;
                }
            }
        }
        return carrying;
    }

    /** Whether one property carries the vocabulary, on itself or on its array items. */
    private static boolean carries(io.swagger.v3.oas.models.media.Schema<?> property,
                                   List<String> vocabulary) {
        if (vocabulary.equals(property.getEnum())) {
            return true;
        }
        return property.getItems() != null && vocabulary.equals(property.getItems().getEnum());
    }

    /** The properties of {@code ExportCatalogue} in the served document, after customisation. */
    private Map<String, io.swagger.v3.oas.models.media.Schema<?>> catalogueProperties() {
        OpenAPI document = servedShape();
        customizer.customise(document);
        return asTyped(document.getComponents().getSchemas().get("ExportCatalogue").getProperties());
    }

    /**
     * The {@code enum} on an array property's ITEM schema.
     *
     * <p>Null when the property is not an array or its items carry no enum, which is the shape springdoc
     * produces for a {@code List<String>} component and the shape this whole change exists to remove — so a
     * silent null here fails the assertion that calls it rather than skipping it.
     */
    @SuppressWarnings("unchecked")
    private static List<String> itemEnumOf(io.swagger.v3.oas.models.media.Schema<?> property) {
        assertThat(property).isNotNull();
        assertThat(property.getType())
            .as("a catalogue vocabulary is an array; a scalar here would be a shape change, not a missing enum")
            .isEqualTo("array");
        assertThat(property.getItems()).isNotNull();
        return (List<String>) property.getItems().getEnum();
    }

    /**
     * The four catalogue properties, in the order the record declares them.
     *
     * <p>Named once because five call sites need the same list, and a literal repeated five times is a list
     * that will be correct in four of them after the next property is added.
     */
    private static final List<String> CATALOGUE_PROPERTIES =
        List.of("formats", "locales", "defaultFormat", "defaultLocale");

    /** A document shaped like the served one: both models, with the properties and nothing else. */
    private static OpenAPI servedShape() {
        return documentWith(
            List.of(ExportVocabularyOpenApiCustomizer.CREATE_EXPORT_REQUEST,
                ExportVocabularyOpenApiCustomizer.EXPORT_CATALOGUE),
            List.of("format", "locale"),
            CATALOGUE_PROPERTIES);
    }

    private static OpenAPI documentWithoutFormat() {
        return documentWith(
            List.of(ExportVocabularyOpenApiCustomizer.CREATE_EXPORT_REQUEST),
            List.of("locale"),
            CATALOGUE_PROPERTIES);
    }

    private static OpenAPI documentWithoutLocale() {
        return documentWith(
            List.of(ExportVocabularyOpenApiCustomizer.CREATE_EXPORT_REQUEST),
            List.of("format"),
            CATALOGUE_PROPERTIES);
    }

    /** The served shape with one catalogue property missing, which must fail loudly rather than skip. */
    private static OpenAPI documentWithout(String catalogueProperty) {
        List<String> remaining = new ArrayList<>(CATALOGUE_PROPERTIES);
        remaining.remove(catalogueProperty);
        return documentWith(
            List.of(ExportVocabularyOpenApiCustomizer.CREATE_EXPORT_REQUEST,
                ExportVocabularyOpenApiCustomizer.EXPORT_CATALOGUE),
            List.of("format", "locale"),
            remaining);
    }

    /** The served shape with the catalogue model renamed away entirely. */
    private static OpenAPI documentWithoutCatalogueModel() {
        return documentWith(
            List.of(ExportVocabularyOpenApiCustomizer.CREATE_EXPORT_REQUEST),
            List.of("format", "locale"),
            List.of());
    }

    /**
     * A document with the named models.
     *
     * <p>The catalogue's properties are seeded as ARRAYS, deliberately: an array schema is what springdoc
     * produces for {@code List<String>} and for {@code List<ExportFormat>}, so seeding it that way is the
     * only way the "the enum went on the items, not on the array" assertion can actually be made. Seeding
     * them as strings would let a customiser that stamped {@code enum} onto the array itself pass.
     */
    private static OpenAPI documentWith(List<String> modelNames, List<String> scalarProperties,
                                        List<String> arrayProperties) {
        Map<String, io.swagger.v3.oas.models.media.Schema> schemas = new LinkedHashMap<>();
        for (String modelName : modelNames) {
            Map<String, io.swagger.v3.oas.models.media.Schema> properties = new LinkedHashMap<>();
            for (String name : scalarProperties) {
                properties.put(name, new StringSchema().type("string"));
            }
            for (String name : arrayProperties) {
                properties.put(name, new io.swagger.v3.oas.models.media.ArraySchema()
                    .type("array").items(new StringSchema().type("string")));
            }
            io.swagger.v3.oas.models.media.Schema<?> model =
                new io.swagger.v3.oas.models.media.Schema<>().type("object");
            model.setProperties(properties);
            schemas.put(modelName, model);
        }
        OpenAPI document = new OpenAPI();
        document.setComponents(new io.swagger.v3.oas.models.Components().schemas(schemas));
        return document;
    }
}