package com.firstelement.crm.casework;

import com.firstelement.crm.api.ClassificationView;
import com.firstelement.crm.persistence.CaseRepository;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The deterministic half of solution resolution: {@code case_solution_mappings}.
 *
 * <h2>Why the mapping wins over a search</h2>
 *
 * <p>The legacy comment states it: "An explicit mapping wins: it is what an ops team configured
 * on purpose, and it does not drift as documents change." A search result changes every time
 * the knowledge base is re-indexed; a mapping does not.
 *
 * <h2>Matching</h2>
 *
 * <p>A mapping matches when <em>every</em> {@code questionId/value} pair it requires is present
 * in the answers. Extra answers are fine — the agent may have answered more than the mapping
 * cares about. Mappings are evaluated in {@code priority asc, id asc} order, never
 * alphabetically, and the first match wins.
 *
 * <h2>Scope</h2>
 *
 * <p>The scope comes from the caller's own {@link TenantContext} through
 * {@link TenantQueryGuard}, and the project is the conversation's. Both the mappings and the
 * documents they point at are read through it, so a case in project A cannot surface project B's
 * document even if a mapping row exists.
 */
@Component
public class CaseSolutionMappingRetriever implements CaseSolutionRetriever {

    private static final Logger log = LoggerFactory.getLogger(CaseSolutionMappingRetriever.class);

    /** How much of a document's first chunk a suggestion quotes. Legacy {@code slice(0, 600)}. */
    public static final int EXCERPT_LENGTH = 600;

    private final CaseRepository cases;
    private final JsonCodec json;
    private final TenantQueryGuard guard;

    public CaseSolutionMappingRetriever(CaseRepository cases, JsonCodec json, TenantQueryGuard guard) {
        this.cases = cases;
        this.json = json;
        this.guard = guard;
    }

    @Override
    public ClassificationView.ClassificationSuggestion suggest(TenantContext context,
                                                              UUID projectId,
                                                              List<Question> questions,
                                                              List<ClassificationView.ClassificationAnswer> answers) {
        if (answers.isEmpty()) {
            return null;
        }
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);

        for (CaseRepository.SolutionMappingRow mapping : cases.solutionMappings(scope)) {
            List<ClassificationView.ClassificationAnswer> required = parseAnswers(mapping.matchAnswers());
            if (required.isEmpty() || !matches(required, answers)) {
                continue;
            }
            ClassificationView.ClassificationSuggestion described =
                describe(scope, mapping.documentId(), null);
            if (described != null) {
                log.debug("classification solution resolved from mapping {} project={}",
                    mapping.id(), projectId);
                return described;
            }
        }
        return null;
    }

    /** Every required pair is present among the answers. */
    static boolean matches(List<ClassificationView.ClassificationAnswer> required,
                           List<ClassificationView.ClassificationAnswer> answers) {
        for (ClassificationView.ClassificationAnswer pair : required) {
            boolean found = answers.stream().anyMatch(answer ->
                answer.questionId().equals(pair.questionId())
                    && answer.value().equals(pair.value()));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Reads a stored {@code matchAnswers} array, ignoring malformed entries. */
    List<ClassificationView.ClassificationAnswer> parseAnswers(String stored) {
        List<ClassificationView.ClassificationAnswer> parsed = new ArrayList<>();
        for (Object raw : json.readList(stored)) {
            if (raw instanceof Map<?, ?> map
                && map.get("questionId") instanceof String questionId
                && map.get("value") instanceof String value) {
                parsed.add(new ClassificationView.ClassificationAnswer(questionId, value));
            }
        }
        return parsed;
    }

    /**
     * Builds the suggestion projection for a document.
     *
     * <p>The {@code excerpt} is the first chunk truncated to {@value #EXCERPT_LENGTH} characters,
     * which is what the legacy {@code describeSuggestion} did.
     */
    ClassificationView.ClassificationSuggestion describe(CrmQueryScope scope,
                                                         UUID documentId,
                                                         Double score) {
        return cases.documentExcerpt(scope, documentId)
            .map(document -> new ClassificationView.ClassificationSuggestion(
                document.id(),
                document.title(),
                score,
                excerpt(document.firstChunk()),
                "MAPPING"))
            .orElse(null);
    }

    public static String excerpt(String chunk) {
        if (chunk == null) {
            return "";
        }
        return chunk.length() > EXCERPT_LENGTH ? chunk.substring(0, EXCERPT_LENGTH) : chunk;
    }
}