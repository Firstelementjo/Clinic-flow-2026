package com.firstelement.inbox.web;

import com.firstelement.crm.domain.InboxFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Declares the inbox {@code filter} parameter as an {@code enum} in the served OpenAPI document.
 *
 * <h2>Why this exists rather than typing the parameter as {@link InboxFilter}</h2>
 *
 * <p>Typing it is the obvious answer and it is wrong, for two reasons that were measured rather
 * than reasoned about. springdoc renders a typed enum parameter as its <b>Java constant names</b> —
 * {@code UNASSIGNED}, {@code ALL_OPEN} — and Spring binds an enum parameter <b>by constant name</b>.
 * So typing it would have published {@code [UNASSIGNED, ALL_OPEN, …]} as the accepted values and
 * simultaneously made {@code ?filter=assigned-to-me} a {@code 400}. The wire values are kebab-case
 * because they are the legacy client's saved filters, and the legacy client's behaviour is the
 * compatibility surface.
 *
 * <p>So the parameter stays a {@code String} — the binding is untouched — and the <em>document</em>
 * is what carries the constraint, which is where the constraint was actually missing.
 *
 * <h2>Why the values are derived rather than restated</h2>
 *
 * <p>From {@link InboxFilter#acceptedValues()}, so the document cannot drift from the one type the
 * server accepts. An {@code @Schema(allowableValues = {"a", "b"})} would have been the shorter
 * spelling and would have been a second list to keep in step — the exact failure mode of the
 * {@code /inbox/counts} endpoint this change had to fix, where a hand-written two-filter loop left
 * three tabs rendering a permanent zero.
 *
 * <h2>Why it throws rather than doing nothing</h2>
 *
 * <p>If the parameter is not found, this customiser raises. A silently-skipped customiser is an
 * enum that vanished on a controller rename and nobody would know, which is the whole defect class
 * this change exists to close. The document is built on every request to {@code /api/v1/openapi},
 * so a signature change that removes the parameter breaks the export — and the export is
 * promotion-blocking.
 *
 * <p>Scoped by path AND method AND parameter name on purpose. Matching every parameter called
 * {@code filter} would corrupt a future endpoint whose {@code filter} means something else.
 */
@Component
public class InboxFilterEnumOpenApiCustomizer implements OpenApiCustomizer {

    private static final String CONVERSATIONS_PATH = "/api/v1/conversations";
    private static final String COUNTS_PATH = "/api/v1/inbox/counts";
    private static final String GET = "get";
    private static final String FILTER = "filter";

    @Override
    public void customise(OpenAPI openApi) {
        Operation list = openApi.getPaths() == null || openApi.getPaths().get(CONVERSATIONS_PATH) == null
            ? null
            : openApi.getPaths().get(CONVERSATIONS_PATH).getGet();

        if (list == null || list.getParameters() == null) {
            throw new IllegalStateException(
                "InboxFilterEnumOpenApiCustomizer found no GET " + CONVERSATIONS_PATH
                    + " in the document being served. The filter parameter is the inbox's whole "
                    + "vocabulary; losing it from the contract is not a silent change, so this "
                    + "fails the document export instead.");
        }

        List<Parameter> parameters = list.getParameters();
        int existing = indexOfFilter(parameters);
        if (existing < 0) {
            throw new IllegalStateException(
                "InboxFilterEnumOpenApiCustomizer found no 'filter' parameter on GET "
                    + CONVERSATIONS_PATH + "; the enum is missing from the contract, which is the "
                    + "defect this customiser exists to prevent.");
        }

        // WHY A REPLACED PARAMETER RATHER THAN A MUTATED ONE
        //
        // The obvious spelling -- mutate the existing parameter's schema -- is WRONG, and was
        // measured wrong. springdoc hands out SHARED model objects: the Schema instance behind
        // `filter` is the same instance behind `projectId` on /notes, `resourceType` on /notes,
        // and every other query parameter in the document. Setting an enum on it therefore
        // stamped the inbox vocabulary onto unrelated parameters across the whole contract --
        // five paths corrupted, and none of them about the inbox. The first version of this class
        // did exactly that, and the only reason it was caught is that the promotion diff was
        // read path by path rather than skimmed.
        //
        // So nothing reachable from another operation is touched. A fresh Parameter and a fresh
        // StringSchema are built here and the list ENTRY is swapped; the old objects are left
        // exactly as they were for whoever else still holds them. InboxFilterContractTest asserts
        // that no other parameter in a document carrying one gains an enum, so this cannot regress
        // silently.
        Parameter replacement = new Parameter()
            .name(FILTER)
            .in("query")
            .required(false);
        StringSchema schema = new StringSchema();
        schema.setType("string");
        // The wire values, in declaration order, so a generated client lays its tabs out in the
        // same order the server publishes them.
        schema.setEnum(List.copyOf(InboxFilter.acceptedValues()));
        replacement.setSchema(schema);
        replacement.setDescription(
            "The saved view. One of " + InboxFilter.acceptedValues()
                + ". Absent means '" + ConversationsController.defaultFilter().value() + "'.");
        replacement.setExample(InboxFilter.UNASSIGNED.value());

        parameters.set(existing, replacement);

        typeSavedViewCounts(openApi);
    }

    /**
     * Declares the {@code /inbox/counts} response as an object with one named integer per view.
     *
     * <h2>Why this is not already true</h2>
     *
     * <p>The route returns {@code Map<String, Integer>}, and springdoc renders a map as
     * {@code {type: object, additionalProperties: {type: integer}}} — a free-form bag. That is
     * self-describing about the <em>values</em> and silent about the <em>keys</em>, and the keys are
     * the entire contract of this endpoint. A client reading only this response cannot know which
     * badges to render, so it either renders what it happens to receive (which is why the original
     * two-filter loop left three tabs showing a permanent zero that looked like a broken product)
     * or hard-codes the names, which is a second list to keep in step with {@link InboxFilter}.
     *
     * <p>ADR 0015's whole point is that the counts must cover every view. That is now enforced by a
     * test on the runtime keys; this makes the <em>document</em> say it too, so a generated client
     * learns the key set instead of discovering it at runtime.
     *
     * <h2>Why the properties are built here rather than typed</h2>
     *
     * <p>The wire keys are kebab-case ({@code assigned-to-me}), which a Java field name cannot be,
     * so there is no record whose generated schema would come out right without fighting springdoc
     * over naming. Building the {@link ObjectSchema} from {@link InboxFilter#acceptedValues()} keeps
     * the single list as the source of truth, exactly as the parameter enum above does.
     *
     * <p>Fresh objects again, for the reason the parameter replacement does it: springdoc shares
     * model instances across the document, and mutating the inferred map schema would corrupt
     * whichever other operation happened to hold it.
     */
    private void typeSavedViewCounts(OpenAPI openApi) {
        PathItem countsPath = openApi.getPaths() == null ? null : openApi.getPaths().get(COUNTS_PATH);
        Operation counts = countsPath == null ? null : countsPath.getGet();
        if (counts == null || counts.getResponses() == null) {
            throw new IllegalStateException(
                "InboxFilterEnumOpenApiCustomizer found no GET " + COUNTS_PATH
                    + " in the document being served. The tab counts are derived from the same "
                    + "vocabulary as the filter parameter; losing them from the contract is not a "
                    + "silent change, so this fails the document export instead.");
        }

        boolean typed = false;
        for (ApiResponse response : counts.getResponses().values()) {
            if (response.getContent() == null) {
                continue;
            }
            for (String mediaType : new ArrayList<>(response.getContent().keySet())) {
                ObjectSchema schema = new ObjectSchema();
                schema.setType("object");
                Map<String, Schema> properties = new LinkedHashMap<>();
                for (String view : InboxFilter.acceptedValues()) {
                    IntegerSchema count = new IntegerSchema();
                    count.setType("integer");
                    count.setFormat("int32");
                    properties.put(view, count);
                }
                schema.setProperties(properties);
                // Every view is always present, always a number, and a zero is a real answer
                // ("this view is empty"), never "the server did not tell me". Required makes that
                // difference visible to a generated client instead of leaving it to guesswork.
                schema.setRequired(List.copyOf(InboxFilter.acceptedValues()));
                schema.setDescription(
                    "One count per inbox view, over the whole filtered scope rather than the current "
                        + "page, so a badge never depends on what happens to be loaded. The views "
                        + "OVERLAP and are not a partition: 'assigned-to-me' also holds released "
                        + "threads the caller last replied to, so the counts do not sum to the "
                        + "number of open conversations and a client must not present them as "
                        + "mutually exclusive buckets.");
                response.getContent().get(mediaType).setSchema(schema);
                typed = true;
            }
        }

        if (!typed) {
            throw new IllegalStateException(
                "InboxFilterEnumOpenApiCustomizer found GET " + COUNTS_PATH + " with no response "
                    + "content to type. Without this the counts stay a free-form map in the contract, "
                    + "which is the defect this method exists to close.");
        }
    }

    private static int indexOfFilter(List<Parameter> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            if (FILTER.equals(parameters.get(i).getName())) {
                return i;
            }
        }
        return -1;
    }
}
