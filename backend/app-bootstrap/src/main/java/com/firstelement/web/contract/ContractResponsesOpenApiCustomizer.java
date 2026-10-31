package com.firstelement.web.contract;

import com.firstelement.kernel.contract.ObservedRouteStatuses;
import com.firstelement.kernel.contract.ObservedRouteStatuses.Observation;
import com.firstelement.web.contract.RefusalRules.Refusal;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Publishes what the API <em>actually</em> returns: the observed success code of every asynchronous
 * route, and the refusals every operation can produce.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>ADR 0016 found the document understating reality in two directions at once. {@code POST
 * /api/v1/exports} returns {@code 202} and published {@code 200}, so a generated client believed an
 * artefact existed when only a job handle did. And no operation anywhere declared a {@code 4xx} or a
 * {@code 5xx}, so after Phase 18 gave every mapped failure a stable {@code ErrorResponse} code, a
 * client still had nothing to branch on.
 *
 * <p>Both are corrected here, from tables rather than from prose, and both tables are derived:
 * {@link ObservedRouteStatuses} is the record of what a suite executed and read back, and
 * {@link RefusalRules} derives each refusal from a property of the route that can be checked against
 * the code. Neither list is a copy of what the controllers happen to do today, which is the mistake
 * that produced the original defect.
 *
 * <h2>WHY A REPLACED SCHEMA RATHER THAN A MUTATED ONE</h2>
 *
 * <p>springdoc hands out <b>shared</b> schema instances: the {@code Schema} behind one property of
 * one model is frequently the instance behind other properties and other models across the whole
 * contract. Writing into one would stamp this answer onto unrelated models. So {@link #errorSchema()}
 * builds a fresh object and puts it into the components map, and nothing reachable from anywhere
 * else is written to.
 *
 * <h2>WHY IT THROWS RATHER THAN SKIPPING</h2>
 *
 * <p>A silently-skipped entry is a route that lost its code and nobody noticed, because the document
 * is rebuilt on every request to {@code /api/v1/openapi} and promotion is blocking. A route renamed
 * out from under this table breaks the export, which is the outcome wanted. The same reasoning
 * applies to a route that <em>gained</em> a code: {@link #assertEveryOperationIsGoverned} is what
 * makes that loud.
 */
@Component
public class ContractResponsesOpenApiCustomizer implements OpenApiCustomizer {

    /** A templated path segment, e.g. {@code {exportId}}. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]+}");

    private static final String ERROR_SCHEMA = "ErrorResponse";

    /**
     * The live handler mappings, injected rather than reflected.
     *
     * <p>The refusal rules are properties of a <em>handler</em>: whether it declares a permission,
     * whether it binds a body, which parameters it requires. Reading those off the actual
     * {@code HandlerMethod} is what makes the derivation checkable — a route that adds a body
     * tomorrow changes the document, with no edit here.
     *
     * <p>Nullable so the pure-JVM contract suite can construct this class directly. With no handler
     * mappings the permission half of the derivation is unavailable, so an operation's refusals are
     * derived from its request shape alone; {@code ServedDocumentRefusalTest} has the real context
     * and is what proves the permission half.
     */
    private final RequestMappingHandlerMapping handlerMappings;

    @Autowired
    public ContractResponsesOpenApiCustomizer(
            org.springframework.beans.factory.ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this(handlerMappings == null ? null : handlerMappings.getIfAvailable());
    }

    private ContractResponsesOpenApiCustomizer(RequestMappingHandlerMapping handlerMappings) {
        this.handlerMappings = handlerMappings;
    }

    /**
     * An instance with no handler mappings, for the pure-JVM contract suite.
     *
     * <p>Refusals need a real context to derive honestly, so this instance stamps the observed
     * success codes and the error schema and leaves the refusals alone. That is deliberate: the
     * refusal derivation is verified against the served document by the suite that has the context.
     */
    public static ContractResponsesOpenApiCustomizer withoutHandlerMappings() {
        return new ContractResponsesOpenApiCustomizer((RequestMappingHandlerMapping) null);
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null || openApi.getPaths() == null) {
            throw new IllegalStateException(
                "ContractResponsesOpenApiCustomizer was handed a document with no paths, so no route "
                    + "can declare the status it returns or the refusals it produces. Promoting that "
                    + "document would tell every client the contract is empty.");
        }
        publishErrorSchema(openApi);
        Map<String, HandlerMethod> governed = handlerFacts();
        stampSuccessCodes(openApi);
        stampRefusals(openApi, governed);
    }

    // ------------------------------------------------------------------
    // the error body
    // ------------------------------------------------------------------

    /**
     * Publishes {@code ErrorResponse}, the one body every non-2xx answer carries.
     *
     * <p>Built rather than generated because no controller declares it as a return type — it is
     * produced by advice, which springdoc does not introspect. Its fields mirror
     * {@code com.firstelement.kernel.error.ErrorResponse} exactly: a client that branches on
     * {@code code} and reads {@code details} needs the names to be right, and
     * {@code ObservedRouteStatusContractTest} asserts the field list against that record so a
     * renamed component cannot ship quietly.
     */
    private void publishErrorSchema(OpenAPI openApi) {
        Map<String, Schema> schemas = requireComponents(openApi).getSchemas();

        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("code", described(new StringSchema(),
            "The stable machine-readable code. Branch on this, not on the message: the message is "
                + "written for display and may change."));
        properties.put("message", described(new StringSchema(),
            "A sentence written for a human. Never contains a tenant, a role, a permission, or "
                + "another row's field value."));
        properties.put("details", described(new ObjectSchema()
                .additionalProperties(new StringSchema()),
            "What the caller needs in order to fix the request: the accepted values, the offending "
                + "field, the current version. Empty when there is nothing to recover from."));
        properties.put("requestId", described(new StringSchema(),
            "The correlation id. It appears in the server log line for the same failure, which is how "
                + "a caller quote gets an operator to the right place."));
        properties.put("timestamp", described(new io.swagger.v3.oas.models.media.DateTimeSchema(),
            "When the body was written, in UTC."));
        properties.put("retryable", described(new io.swagger.v3.oas.models.media.BooleanSchema(),
            "Whether an unchanged retry could succeed. Null where the server does not claim either "
                + "way; a client must treat null as unknown, not as false."));

        ObjectSchema schema = new ObjectSchema();
        schema.setDescription("The body every non-2xx response carries. The same shape for a "
            + "refusal the caller can fix and for a fault the caller cannot, so the two are told "
            + "apart by the status and the code rather than by the body.");
        schema.setProperties(properties);
        schemas.put(ERROR_SCHEMA, schema);
    }

    private static Schema described(Schema<?> schema, String description) {
        schema.setDescription(description);
        return schema;
    }

    // ------------------------------------------------------------------
    // the observed success codes
    // ------------------------------------------------------------------

    /**
     * Declares each observed route's code, and nothing else.
     *
     * <p>Re-keys the response springdoc already computed so the content schema it inferred for the
     * return type survives. The {@link ApiResponse} <em>object</em> is shared with any other
     * operation declaring the same return type, so only the map entry is touched — the object's own
     * fields are never written to.
     */
    private void stampSuccessCodes(OpenAPI openApi) {
        for (Map.Entry<String, List<Observation>> governed
                : ObservedRouteStatuses.governedByOperation().entrySet()) {
            Operation operation = requireOperation(openApi, governed.getKey());
            ApiResponses responses = operation.getResponses();
            if (responses == null || responses.isEmpty()) {
                throw new IllegalStateException(
                    "ContractResponsesOpenApiCustomizer found no responses declared on " + governed.getKey()
                        + ", so the codes it was observed to return are declared nowhere. A generated "
                        + "client would have to guess.");
            }
            List<String> declared2xx = new ArrayList<>();
            for (String code : responses.keySet()) {
                if (code != null && code.length() == 3 && code.charAt(0) == '2') {
                    declared2xx.add(code);
                }
            }
            List<String> observed = governed.getValue().stream()
                .map(o -> String.valueOf(o.status())).sorted().toList();
            List<String> wanted = observed.stream().sorted().toList();

            if (declared2xx.size() > 1) {
                // More than one code only survives if every one of them was OBSERVED, which is what
                // SplitOutcome is for. A second code nobody executed is the hedging rule 3 forbids.
                throw new IllegalStateException(
                    "ContractResponsesOpenApiCustomizer found " + declared2xx.size() + " success codes ("
                        + declared2xx + ") on " + governed.getKey() + ". ADR-0016 rule 3 forbids a code "
                        + "a route never returns, so this fails the export rather than guessing which "
                        + "is real. If the route genuinely returns two, record them as a SplitOutcome "
                        + "with the suite that observed each.");
            }

            ApiResponse existing = responses.get(declared2xx.get(0));
            String declared = declared2xx.get(0);
            if (wanted.size() == 1) {
                // The common case: re-key. springdoc inferred 200 from the return type, which is a
                // guess, and the observation replaces it. The ApiResponse OBJECT is shared with any
                // other operation declaring the same return type, so only the map entry moves.
                if (!wanted.get(0).equals(declared)) {
                    responses.remove(declared);
                    responses.addApiResponse(wanted.get(0), existing);
                }
                continue;
            }
            // The split case: every observed code is published, and a framework default that is NOT
            // one of them is dropped rather than left to imply a code the route never returns.
            for (String code : wanted) {
                if (code.equals(declared)) {
                    continue;
                }
                responses.addApiResponse(code, new ApiResponse()
                    .description(outcomeDescription(governed.getKey(), code))
                    .content(existing == null ? null : existing.getContent()));
            }
            if (!wanted.contains(declared)) {
                responses.remove(declared);
            }
        }
    }

    /** Why an operation publishes a second success code, so the document says rather than implies. */
    private static String outcomeDescription(String operationKey, String code) {
        for (Observation observation : ObservedRouteStatuses.governed()) {
            if (observation.key().equals(operationKey) && String.valueOf(observation.status()).equals(code)) {
                return String.valueOf(observation.status()) + " — observed: " + observation.evidence();
            }
        }
        return "Observed status " + code + ".";
    }

    // ------------------------------------------------------------------
    // the refusals
    // ------------------------------------------------------------------

    /**
     * Declares, on every operation, the refusals that operation can produce.
     *
     * <p>Only the response map is written, and only for statuses the derivation produced. An
     * operation's existing success entries are untouched, so this cannot change what a client
     * believes about the happy path.
     */
    private void stampRefusals(OpenAPI openApi, Map<String, HandlerMethod> governed) {
        for (Map.Entry<String, PathItem> path : openApi.getPaths().entrySet()) {
            String template = path.getKey();
            boolean hasPathVariable = PATH_VARIABLE.matcher(template).find();
            for (Map.Entry<PathItem.HttpMethod, Operation> entry : path.getValue().readOperationsMap().entrySet()) {
                String method = entry.getKey().name();
                Operation operation = entry.getValue();
                HandlerMethod handler = governed.get(method + " " + template);

                List<Refusal> refusals = RefusalRules.forOperation(
                    method,
                    template,
                    isPermissionGuarded(handler),
                    consumesBody(operation),
                    requiresParameter(operation),
                    hasPathVariable,
                    isMutation(entry.getKey()));

                ApiResponses responses = operation.getResponses();
                if (responses == null) {
                    responses = new ApiResponses();
                    operation.setResponses(responses);
                }
                for (Refusal refusal : refusals) {
                    String code = String.valueOf(refusal.status());
                    if (responses.containsKey(code)) {
                        // A module that already declared this status keeps its own rendering: it owns
                        // the wording, and overwriting it would discard a description somebody wrote
                        // against a test.
                        continue;
                    }
                    responses.addApiResponse(code, new ApiResponse()
                        .description(refusal.description())
                        .content(errorContent(refusal)));
                }
                declareRetryAfterHeader(operation, responses, refusals);
            }
        }
    }

    /**
     * The {@code Retry-After} header on a 429 the runtime actually sends it on.
     *
     * <p>{@code SecurityExceptionAdvice.rateLimited} answers
     * {@code .header("Retry-After", ...)} and puts {@code retryAfterSeconds} in {@code details}, so
     * the behaviour exists. What was missing was the declaration: a client reading only the document
     * had to assume the whole 60s window, which is exactly what the e2e harness does.
     *
     * <p>Applied <em>after</em> the refusal loop and independently of it, because the login 429 is
     * already declared by the module with wording somebody wrote against a test, and the loop
     * deliberately keeps a module's own rendering. Scoped to operations whose refusals include a 429
     * <em>from {@link RefusalRules#EXTRA_REFUSALS}</em> rather than to every 429: a 429 that the
     * server does not decorate must not grow a header it never sends.
     */
    private static void declareRetryAfterHeader(Operation operation, ApiResponses responses,
                                                List<Refusal> refusals) {
        boolean rateLimited = refusals.stream()
            .anyMatch(refusal -> refusal.status() == 429 && refusal.code().equals("LOGIN_RATE_LIMITED"));
        if (!rateLimited) {
            return;
        }
        ApiResponse response = responses.get("429");
        if (response == null) {
            return;
        }
        // ApiResponse.headers is a plain Map in swagger-models 2.2.x -- there is no Headers type,
        // despite the package being named ...models.headers.
        Map<String, io.swagger.v3.oas.models.headers.Header> headers = response.getHeaders();
        if (headers == null) {
            headers = new LinkedHashMap<>();
            response.setHeaders(headers);
        }
        if (headers.containsKey("Retry-After")) {
            return;
        }
        headers.put("Retry-After", new io.swagger.v3.oas.models.headers.Header()
            .description("Seconds to wait before retrying. The same value is published as "
                + "details.retryAfterSeconds in the body.")
            .schema(new io.swagger.v3.oas.models.media.IntegerSchema()
                .format("int32")
                ._default(60)));
    }

    /**
     * One {@link ApiResponse} whose body is the shared {@code ErrorResponse} schema.
     *
     * <p>The {@link Schema} is carried by reference into a fresh {@code Content} and {@code
     * MediaType}: those two are per-response values, so sharing them would be the trap the class
     * comment describes rather than an instance of it.
     */
    private static Content errorContent(Refusal refusal) {
        Content content = new Content();
        content.addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType()
            .schema(new io.swagger.v3.oas.models.media.ObjectSchema()
                .$ref("#/components/schemas/" + ERROR_SCHEMA)
                .description("code=" + refusal.code())));
        return content;
    }

    private static boolean isMutation(PathItem.HttpMethod method) {
        return method == PathItem.HttpMethod.POST
            || method == PathItem.HttpMethod.PUT
            || method == PathItem.HttpMethod.PATCH
            || method == PathItem.HttpMethod.DELETE;
    }

    private static boolean consumesBody(Operation operation) {
        return operation.getRequestBody() != null;
    }

    private static boolean requiresParameter(Operation operation) {
        if (operation.getParameters() == null) {
            return false;
        }
        return operation.getParameters().stream().anyMatch(p -> Boolean.TRUE.equals(p.getRequired()));
    }

    /** Whether the handler declares a required permission. Reflection over the annotation, not a list. */
    private static boolean isPermissionGuarded(HandlerMethod handler) {
        if (handler == null) {
            return false;
        }
        return hasAnnotation(handler, "com.firstelement.security.annotation.RequirePermission")
            || hasAnnotation(handler, "com.firstelement.security.annotation.RequireAnyPermission");
    }

    private static boolean hasAnnotation(HandlerMethod handler, String annotationName) {
        return java.util.Arrays.stream(handler.getMethod().getAnnotations())
            .anyMatch(a -> a.annotationType().getName().equals(annotationName));
    }

    /**
     * Every handler the context knows about, keyed {@code "METHOD /templatedPath"}.
     *
     * <p>The pattern is read from the mapping itself rather than rebuilt from the class-level
     * {@code @RequestMapping}, because a controller may declare a prefix, a per-method path, or
     * both, and a reconstruction that disagreed with the mapping would silently derive the wrong
     * refusals for exactly the routes that are hardest to test by hand.
     */
    private Map<String, HandlerMethod> handlerFacts() {
        Map<String, HandlerMethod> facts = new LinkedHashMap<>();
        if (handlerMappings == null) {
            return facts;
        }
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            var condition = info.getPathPatternsCondition();
            if (condition == null) {
                continue;
            }
            for (String pattern : condition.getPatternValues()) {
                for (RequestMethod method : methodsOf(info)) {
                    facts.putIfAbsent(method.name() + " " + pattern, entry.getValue());
                }
            }
        }
        return facts;
    }

    /**
     * The methods a mapping answers, or all five when it names none.
     *
     * <p>A {@code @RequestMapping} with no {@code method} answers every verb, so registering it
     * under only one would leave the other four deriving their refusals from a handler they do not
     * have. The set is the HTTP verbs Spring maps, which is the whole of what this document
     * describes.
     */
    private static List<RequestMethod> methodsOf(RequestMappingInfo info) {
        Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
        if (!declared.isEmpty()) {
            return List.copyOf(declared);
        }
        return List.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE);
    }

    // ------------------------------------------------------------------
    // loudness
    // ------------------------------------------------------------------

    private static Operation requireOperation(OpenAPI openApi, String operationKey) {
        int space = operationKey.indexOf(' ');
        String method = operationKey.substring(0, space);
        String path = operationKey.substring(space + 1);
        PathItem item = openApi.getPaths().get(path);
        if (item == null) {
            throw new IllegalStateException(
                "ContractResponsesOpenApiCustomizer found no path " + path + " in the document being "
                    + "served, so " + method + " cannot declare the status it was observed to return. "
                    + "A renamed or removed route breaks the export rather than quietly losing its code.");
        }
        Operation operation;
        try {
            operation = item.readOperationsMap().get(PathItem.HttpMethod.valueOf(method));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "ContractResponsesOpenApiCustomizer cannot map " + method + " onto an OpenAPI "
                    + "operation method.", e);
        }
        if (operation == null) {
            throw new IllegalStateException(
                "ContractResponsesOpenApiCustomizer found no " + method + " on " + path + " in the "
                    + "document being served, so the status it was observed to return is declared "
                    + "nowhere.");
        }
        return operation;
    }

    private static io.swagger.v3.oas.models.Components requireComponents(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new io.swagger.v3.oas.models.Components());
        }
        if (openApi.getComponents().getSchemas() == null) {
            openApi.getComponents().setSchemas(new LinkedHashMap<>());
        }
        return openApi.getComponents();
    }

    /**
     * The refusals a given operation declares, for the contract suite to compare against what the
     * route was observed to refuse.
     *
     * <p>Public so a test can ask the same question the customiser asks, rather than restating the
     * rules. A test carrying its own copy of the derivation proves the copy.
     */
    public static List<Refusal> declaredRefusals(String method, String path, boolean permissionGuarded,
                                                  boolean consumesBody, boolean requiredParams,
                                                  boolean hasPathVariable) {
        return RefusalRules.forOperation(method, path, permissionGuarded, consumesBody,
            requiredParams, hasPathVariable, isMutationName(method));
    }

    private static boolean isMutationName(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    /** Every distinct refusal status the derivation can produce, for the suite's vocabulary check. */
    public static Set<Integer> refusalStatuses() {
        Set<Integer> statuses = new LinkedHashSet<>();
        statuses.add(RefusalRules.UNAUTHENTICATED.status());
        statuses.add(RefusalRules.ACCESS_DENIED.status());
        statuses.add(RefusalRules.NOT_FOUND.status());
        statuses.add(RefusalRules.VALIDATION_FAILED.status());
        statuses.add(RefusalRules.UNSUPPORTED_MEDIA_TYPE.status());
        statuses.add(RefusalRules.CSRF_INVALID.status());
        statuses.add(RefusalRules.VERSION_CONFLICT.status());
        statuses.add(RefusalRules.EXPORT_FORMAT_UNSUPPORTED.status());
        statuses.add(RefusalRules.EXPORT_ARTEFACT_GONE.status());
        statuses.add(RefusalRules.LOGIN_RATE_LIMITED.status());
        return Set.copyOf(statuses);
    }
}