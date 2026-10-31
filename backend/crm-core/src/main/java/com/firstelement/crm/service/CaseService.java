package com.firstelement.crm.service;

import com.firstelement.crm.api.ClassificationQuestionView;
import com.firstelement.crm.api.ClassificationView;
import com.firstelement.crm.api.ResolutionView;
import com.firstelement.crm.api.SaveClassificationQuestionRequest;
import com.firstelement.crm.api.SaveClassificationRequest;
import com.firstelement.crm.api.SaveResolutionRequest;
import com.firstelement.crm.api.Timestamps;
import com.firstelement.crm.audit.CrmAuditRecord;
import com.firstelement.crm.audit.CrmAuditWriter;
import com.firstelement.crm.casework.CaseSolutionRetriever;
import com.firstelement.crm.domain.CaseOutcome;
import com.firstelement.crm.error.CrmDomainException;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import com.firstelement.crm.persistence.CaseRepository;
import com.firstelement.crm.persistence.ConversationRepository;
import com.firstelement.crm.persistence.ConversationRow;
import com.firstelement.crm.persistence.JsonCodec;
import com.firstelement.crm.query.CrmQueryScope;
import com.firstelement.security.TenantContext;
import com.firstelement.security.annotation.RequirePermission;
import com.firstelement.security.permission.Permission;
import com.firstelement.security.query.TenantQueryGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Case classification, case resolution, and the close/reopen lifecycle.
 *
 * <h2>Behaviour ported from {@code apps/api/src/cases/cases.service.ts}</h2>
 *
 * <ul>
 *   <li>Questions are project configuration, ordered by position, with both Arabic and English
 *       prompts and at least two options.</li>
 *   <li>An answer naming a question that is not an enabled question of the conversation's own
 *       project is <em>dropped, not rejected</em>: "a question id from a client is a claim, not
 *       a fact". Dropping is deliberate â€” a UI that still shows a question an admin has since
 *       disabled must not be able to fail the agent's save.</li>
 *   <li>Saving a classification also resolves the solution the answers point at, scoped to the
 *       conversation's project.</li>
 *   <li>Saving a resolution upserts: one resolution row per conversation, enforced by the
 *       unique index rather than by a read.</li>
 *   <li>Closing requires a recorded resolution. "A case closed with no account of how it ended is
 *       exactly the gap Â§14 exists to prevent."</li>
 *   <li>The audit row for a resolution carries the outcome and the satisfaction flag only â€” the
 *       narrative fields can contain customer detail.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>Both saves are upserts keyed by {@code conversationId}, so repeating them is naturally
 * idempotent: the second call updates the same row instead of creating a second history entry.
 * Close and reopen are idempotent by target state.
 */
@Service
public class CaseService {

    private final CaseRepository cases;
    private final ConversationRepository conversations;
    private final CaseSolutionRetriever solutions;
    private final JsonCodec json;
    private final TenantQueryGuard guard;
    private final CrmAuditWriter audit;

    public CaseService(CaseRepository cases,
                       ConversationRepository conversations,
                       CaseSolutionRetriever solutions,
                       JsonCodec json,
                       TenantQueryGuard guard,
                       CrmAuditWriter audit) {
        this.cases = cases;
        this.conversations = conversations;
        this.solutions = solutions;
        this.json = json;
        this.guard = guard;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // question configuration
    // ------------------------------------------------------------------

    @RequirePermission(Permission.CONVERSATION_READ)
    public List<ClassificationQuestionView> listQuestions(TenantContext context, UUID projectId) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        return cases.questions(scope).stream().map(this::toQuestionView).toList();
    }

    @RequirePermission(Permission.ESCALATION_RULE_MANAGE)
    @Transactional
    public ClassificationQuestionView saveQuestion(TenantContext context,
                                                  UUID projectId,
                                                  SaveClassificationQuestionRequest request,
                                                  UUID questionId) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        validateQuestion(request);

        LocalDateTime now = LocalDateTime.now();
        String optionsJson = json.write(request.options());
        CaseRepository.QuestionRow row;

        if (questionId == null) {
            UUID newId = UUID.randomUUID();
            try {
                cases.insertQuestion(scope, newId, request.position(), request.promptAr(),
                    request.promptEn(), optionsJson, request.isRequiredOrDefault(),
                    request.isEnabledOrDefault(), now);
            } catch (CaseRepository.PositionAlreadyUsedException e) {
                throw positionAlreadyUsed(request.position());
            }
            row = cases.questionInScope(scope, newId)
                .orElseThrow(() -> new ResourceNotFoundException("Classification question"));
        } else {
            int changed = cases.updateQuestion(scope, questionId, request.position(),
                request.promptAr(), request.promptEn(), optionsJson,
                request.isRequiredOrDefault(), request.isEnabledOrDefault(), now);
            if (changed == 0) {
                // Either the question is not in this project or its position is already taken by
                // another one; both are "not found" as far as the caller is concerned.
                throw new ResourceNotFoundException("Classification question");
            }
            row = cases.questionInScope(scope, questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Classification question"));
        }

        auditQuestionChange(context, projectId, row.id(), request);
        return toQuestionView(row);
    }

    /**
     * Closing requires a recorded resolution.
     *
     * <p>The status transition itself belongs to {@code ConversationService}, which owns the
     * state machine, the workload recount and the timeline entry. This method owns only the case
     * rule, so there is exactly one place that can move a conversation's status.
     */
    @RequirePermission(Permission.CONVERSATION_RESOLVE)
    public void requireResolutionBeforeClose(TenantContext context, UUID conversationId) {
        CrmQueryScope scope = conversationScope(context, conversationId);
        if (!cases.hasResolution(scope, conversationId)) {
            throw new ValidationFailedException("Record how the case was resolved before closing it");
        }
    }

    /** Whether a resolution exists; the caller uses it to decide whether to close. */
    @RequirePermission(Permission.CONVERSATION_READ)
    public boolean hasResolution(TenantContext context, UUID conversationId) {
        CrmQueryScope scope = conversationScope(context, conversationId);
        return cases.hasResolution(scope, conversationId);
    }

    @RequirePermission(Permission.ESCALATION_RULE_MANAGE)
    @Transactional
    public void deleteQuestion(TenantContext context, UUID projectId, UUID questionId) {
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, projectId);
        int deleted = cases.deleteQuestion(scope, questionId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("Classification question");
        }
    }

    // ------------------------------------------------------------------
    // classification
    // ------------------------------------------------------------------

    @RequirePermission(Permission.CONVERSATION_READ)
    public ClassificationView getClassification(TenantContext context, UUID conversationId) {
        CrmQueryScope scope = conversationScope(context, conversationId);
        List<CaseRepository.QuestionRow> questions = cases.enabledQuestions(scope);
        Optional<CaseRepository.ClassificationRow> record = cases.classification(scope, conversationId);

        List<ClassificationView.ClassificationAnswer> answers = record
            .map(row -> parseAnswers(row.answers())).orElse(List.of());

        ClassificationView.ClassificationSuggestion suggestion = null;
        if (record.isPresent() && record.get().suggestedDocumentId() != null) {
            suggestion = describeStored(scope, record.get().suggestedDocumentId(),
                record.get().suggestionScore());
        }

        return new ClassificationView(
            answers,
            record.map(row -> Timestamps.toIso(row.classifiedAt())).orElse(null),
            suggestion,
            isComplete(questions, answers));
    }

    @RequirePermission(Permission.CONVERSATION_REPLY)
    @Transactional
    public ClassificationView saveClassification(TenantContext context,
                                                 UUID conversationId,
                                                 SaveClassificationRequest request) {
        CrmQueryScope scope = conversationScope(context, conversationId);

        List<CaseRepository.QuestionRow> questions = cases.enabledQuestions(scope);

        // Answers referencing another project's questions are dropped rather than stored.
        Set<UUID> validIds = new LinkedHashSet<>();
        for (CaseRepository.QuestionRow question : questions) {
            validIds.add(question.id());
        }
        List<ClassificationView.ClassificationAnswer> answers = new ArrayList<>();
        for (ClassificationView.ClassificationAnswer answer : request.answers()) {
            try {
                if (validIds.contains(UUID.fromString(answer.questionId()))) {
                    answers.add(answer);
                }
            } catch (IllegalArgumentException e) {
                // A non-UUID question id is the clearest possible claim-and-not-fact; drop it.
            }
        }

        ClassificationView.ClassificationSuggestion suggestion =
            solutions.suggest(context, scope.projectId(),
                questions.stream()
                    .map(question -> new CaseSolutionRetriever.Question(question.id(),
                        parseOptions(question.options())))
                    .toList(),
                answers);

        LocalDateTime now = LocalDateTime.now();
        cases.upsertClassification(scope, UUID.randomUUID(), conversationId, json.write(answers),
            suggestion == null ? null : suggestion.documentId(),
            suggestion == null ? null : suggestion.score(),
            context.userId(), now);

        return new ClassificationView(answers, Timestamps.toIso(now), suggestion,
            isComplete(questions, answers));
    }

    // ------------------------------------------------------------------
    // resolution
    // ------------------------------------------------------------------

    @RequirePermission(Permission.CONVERSATION_READ)
    public ResolutionView getResolution(TenantContext context, UUID conversationId) {
        CrmQueryScope scope = conversationScope(context, conversationId);
        return cases.resolution(scope, conversationId)
            .map(row -> toResolutionView(row,
                cases.userFullName(context, row.recordedByUserId()).orElse(null)))
            .orElse(null);
    }

    @RequirePermission(Permission.CONVERSATION_RESOLVE)
    @Transactional
    public ResolutionView saveResolution(TenantContext context,
                                         UUID conversationId,
                                         SaveResolutionRequest request) {
        ConversationRow conversation =
            requireConversation(context, conversationId);
        CrmQueryScope scope = CrmQueryScope.forProject(guard, context, conversation.projectId());

        validateResolution(request);
        String outcome = validateOutcome(request.outcome());

        // A document from another project cannot be cited as the solution.
        if (request.documentId() != null && !cases.documentInProject(scope, request.documentId())) {
            throw new ValidationFailedException("That document does not belong to this project");
        }

        LocalDateTime now = LocalDateTime.now();
        cases.upsertResolution(scope, UUID.randomUUID(), conversationId, request.customerIssue().trim(),
            request.agentAction().trim(), request.usedKnowledgeBaseOrDefault(), request.documentId(),
            request.customerAccepted(), request.customerSatisfied(),
            request.escalationRequestedOrDefault(), request.escalatedToSupervisorOrDefault(),
            outcome, request.notes() == null ? null : request.notes().trim(), context.userId(), now);

        audit.record(CrmAuditRecord.of(context.organizationId(), conversation.projectId(),
            context.userId(), CrmAuditRecord.EMPLOYEE_RESOLVED_CONVERSATION, "CaseResolution",
            conversationId,
            // Outcome and satisfaction only â€” the narrative fields can contain customer detail.
            Map.of("outcome", outcome,
                "satisfied", request.customerSatisfied() == null ? "unset"
                    : String.valueOf(request.customerSatisfied())),
            null));

        ResolutionView saved = cases.resolution(scope, conversationId)
            .map(row -> toResolutionView(row,
                cases.userFullName(context, row.recordedByUserId()).orElse(null)))
            .orElseThrow(() -> new ResourceNotFoundException("Resolution"));
        return saved;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ConversationRow requireConversation(TenantContext context,
                                                                      UUID conversationId) {
        CrmQueryScope scope = CrmQueryScope.accessibleProjects(context);
        return conversations.findInScope(scope, conversationId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation"));
    }

    /**
     * The conversation's own project scope.
     *
     * <p>The conversation is first read through the caller's accessible projects and then pinned
     * to the row's project, so every follow-up statement in a case operation is project-scoped
     * as well as tenant-scoped.
     */
    private CrmQueryScope conversationScope(TenantContext context, UUID conversationId) {
        ConversationRow row = requireConversation(context, conversationId);
        return CrmQueryScope.forProject(guard, context, row.projectId());
    }

    private ClassificationView.ClassificationSuggestion describeStored(CrmQueryScope scope,
                                                                      UUID documentId,
                                                                      Double score) {
        return cases.documentExcerpt(scope, documentId)
            .map(document -> new ClassificationView.ClassificationSuggestion(
                document.id(),
                document.title(),
                score,
                com.firstelement.crm.casework.CaseSolutionMappingRetriever.excerpt(document.firstChunk()),
                "MAPPING"))
            .orElse(null);
    }

    private void auditQuestionChange(TenantContext context,
                                     UUID projectId,
                                     UUID questionId,
                                     SaveClassificationQuestionRequest request) {
        audit.record(CrmAuditRecord.of(context.organizationId(), projectId, context.userId(),
            CrmAuditRecord.ADMIN_CHANGED_ESCALATION_RULE, "CaseClassificationQuestion", questionId,
            Map.of("position", request.position(), "isEnabled", request.isEnabledOrDefault()), null));
    }

    List<ClassificationQuestionView.ClassificationOption> parseOptions(String stored) {
        List<ClassificationQuestionView.ClassificationOption> options = new ArrayList<>();
        for (Object raw : json.readList(stored)) {
            if (raw instanceof Map<?, ?> map
                && map.get("value") instanceof String value
                && map.get("labelAr") instanceof String labelAr
                && map.get("labelEn") instanceof String labelEn) {
                options.add(new ClassificationQuestionView.ClassificationOption(value, labelAr, labelEn));
            }
        }
        return options;
    }

    ClassificationQuestionView toQuestionView(CaseRepository.QuestionRow row) {
        return new ClassificationQuestionView(
            row.id(),
            row.position(),
            row.promptAr(),
            row.promptEn(),
            parseOptions(row.options()),
            row.isRequired(),
            row.isEnabled());
    }

    List<ClassificationView.ClassificationAnswer> parseAnswers(String stored) {
        List<ClassificationView.ClassificationAnswer> answers = new ArrayList<>();
        for (Object raw : json.readList(stored)) {
            if (raw instanceof Map<?, ?> map
                && map.get("questionId") instanceof String questionId
                && map.get("value") instanceof String value) {
                answers.add(new ClassificationView.ClassificationAnswer(questionId, value));
            }
        }
        return answers;
    }

    /** True when every required question of the project has an answer. */
    static boolean isComplete(List<CaseRepository.QuestionRow> questions,
                              List<ClassificationView.ClassificationAnswer> answers) {
        for (CaseRepository.QuestionRow question : questions) {
            if (!question.isRequired()) {
                continue;
            }
            boolean answered = answers.stream()
                .anyMatch(answer -> answer.questionId().equals(question.id().toString()));
            if (!answered) {
                return false;
            }
        }
        return true;
    }

    static ResolutionView toResolutionView(CaseRepository.ResolutionRow row, String recordedByName) {
        return new ResolutionView(
            row.id().toString(),
            row.customerIssue(),
            row.agentAction(),
            row.usedKnowledgeBase(),
            row.documentId() == null ? null : row.documentId().toString(),
            row.customerAccepted(),
            row.customerSatisfied(),
            row.escalationRequested(),
            row.escalatedToSupervisor(),
            row.outcome(),
            row.notes(),
            recordedByName,
            Timestamps.toIso(row.createdAt()));
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    /**
     * A position already occupied by another question in this project.
     *
     * <p>{@code 409 POSITION_ALREADY_USED}, not {@code 400}: nothing about the request is malformed,
     * and retrying it unchanged changes nothing. It is also not {@code 404}, and the contrast with
     * the update path is deliberate — an update cannot distinguish "not in this project" from
     * "position taken" (see {@link #saveQuestion}), so it reports the only answer a caller can act
     * on; an insert has no such ambiguity, because the question is provably new and the only
     * possible failure is the occupied position.
     *
     * <p>Reported as a {@link CrmDomainException} rather than left as the repository's
     * {@code PositionAlreadyUsedException}, which is an unmapped {@code RuntimeException} and
     * therefore reached the client as {@code 500 INTERNAL_ERROR}.
     */
    private static CrmDomainException positionAlreadyUsed(int position) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", "position");
        details.put("position", position);
        return new CrmDomainException(HttpStatus.CONFLICT,
            "POSITION_ALREADY_USED",
            "Another question in this project already uses position " + position
                + ". Positions order the question list, so each one may be used once.",
            details, Boolean.FALSE);
    }

    private static void validateQuestion(SaveClassificationQuestionRequest request) {
        if (request == null) {
            throw new ValidationFailedException("A body is required");
        }
        Map<String, String> problems = new LinkedHashMap<>();
        if (request.position() < 1 || request.position() > 20) {
            problems.put("position", "must be between 1 and 20");
        }
        if (request.promptAr() == null || request.promptAr().trim().length() < 2
            || request.promptAr().trim().length() > SaveClassificationQuestionRequest.MAX_PROMPT) {
            problems.put("promptAr", "must be 2..300 characters");
        }
        if (request.promptEn() == null || request.promptEn().trim().length() < 2
            || request.promptEn().trim().length() > SaveClassificationQuestionRequest.MAX_PROMPT) {
            problems.put("promptEn", "must be 2..300 characters");
        }
        List<ClassificationQuestionView.ClassificationOption> options = request.options();
        if (options == null || options.size() < SaveClassificationQuestionRequest.MIN_OPTIONS
            || options.size() > SaveClassificationQuestionRequest.MAX_OPTIONS) {
            problems.put("options", "must contain 2..20 choices");
        } else {
            for (ClassificationQuestionView.ClassificationOption option : options) {
                if (option == null || option.value() == null
                    || option.value().trim().isEmpty()
                    || option.value().trim().length() > SaveClassificationQuestionRequest.MAX_VALUE) {
                    problems.put("options", "each value must be 1..60 characters");
                    break;
                }
                if (option.labelAr() == null || option.labelAr().trim().isEmpty()
                    || option.labelAr().trim().length() > SaveClassificationQuestionRequest.MAX_LABEL
                    || option.labelEn() == null || option.labelEn().trim().isEmpty()
                    || option.labelEn().trim().length() > SaveClassificationQuestionRequest.MAX_LABEL) {
                    problems.put("options", "each choice needs an Arabic and an English label");
                    break;
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationFailedException("Request validation failed",
                Map.of("fields", problems));
        }
    }

    private static void validateResolution(SaveResolutionRequest request) {
        if (request == null) {
            throw new ValidationFailedException("A body is required");
        }
        Map<String, String> problems = new LinkedHashMap<>();
        if (request.customerIssue() == null || request.customerIssue().trim().length() < 2
            || request.customerIssue().trim().length() > SaveResolutionRequest.MAX_TEXT) {
            problems.put("customerIssue", "must be 2..2000 characters");
        }
        if (request.agentAction() == null || request.agentAction().trim().length() < 2
            || request.agentAction().trim().length() > SaveResolutionRequest.MAX_TEXT) {
            problems.put("agentAction", "must be 2..2000 characters");
        }
        if (request.notes() != null && request.notes().trim().length() > SaveResolutionRequest.MAX_NOTES) {
            problems.put("notes", "must be at most 4000 characters");
        }
        if (!problems.isEmpty()) {
            throw new ValidationFailedException("Request validation failed",
                Map.of("fields", problems));
        }
    }

    private static String validateOutcome(String outcome) {
        if (outcome == null) {
            throw new ValidationFailedException("outcome is required");
        }
        try {
            CaseOutcome parsed = CaseOutcome.valueOf(outcome);
            return parsed.name();
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException("outcome must be RESOLVED, ESCALATED or UNRESOLVED");
        }
    }
}