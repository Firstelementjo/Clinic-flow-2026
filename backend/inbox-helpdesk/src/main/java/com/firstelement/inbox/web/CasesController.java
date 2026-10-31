package com.firstelement.inbox.web;

import com.firstelement.crm.api.ClassificationQuestionView;
import com.firstelement.crm.api.ClassificationView;
import com.firstelement.crm.api.ResolutionView;
import com.firstelement.crm.api.SaveClassificationQuestionRequest;
import com.firstelement.crm.api.SaveClassificationRequest;
import com.firstelement.crm.api.SaveResolutionRequest;
import com.firstelement.crm.service.CaseService;
import com.firstelement.security.TenantContextHolder;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Case classification, case resolution and the close/reopen lifecycle, under
 * {@code /api/v1}.
 *
 * <h2>Legacy routes, unchanged</h2>
 *
 * <pre>
 *   GET    /api/v1/projects/{projectId}/classification-questions
 *   POST   /api/v1/projects/{projectId}/classification-questions
 *   PUT    /api/v1/projects/{projectId}/classification-questions/{questionId}
 *   DELETE /api/v1/projects/{projectId}/classification-questions/{questionId}
 *   GET    /api/v1/conversations/{id}/classification
 *   PUT    /api/v1/conversations/{id}/classification
 *   GET    /api/v1/conversations/{id}/resolution
 *   PUT    /api/v1/conversations/{id}/resolution
 *   POST   /api/v1/conversations/{id}/close
 *   POST   /api/v1/conversations/{id}/reopen
 * </pre>
 *
 * <p>The legacy API mounted these on the application root without an {@code /api/v1} prefix; the
 * strangler target is versioned, so the prefix is added here. The bodies, the permissions and the
 * status codes are identical.
 *
 * <h2>Close and reopen are routed here, the status write is not</h2>
 *
 * <p>{@code POST /close} and {@code POST /reopen} enforce the case rule — closing requires a
 * recorded resolution — and then delegate the status transition to
 * {@code ConversationService}, so there is exactly one code path that can move a conversation's
 * status, and it is the one that also recounts workload and writes the timeline.
 */
@RestController
@RequestMapping("/api/v1")
public class CasesController {

    private final CaseService cases;
    private final com.firstelement.inbox.service.ConversationService conversations;

    public CasesController(CaseService cases,
                           com.firstelement.inbox.service.ConversationService conversations) {
        this.cases = cases;
        this.conversations = conversations;
    }

    // ------------------------------------------------------------------
    // question configuration
    // ------------------------------------------------------------------

    @GetMapping("/projects/{projectId}/classification-questions")
    public List<ClassificationQuestionView> listQuestions(@PathVariable String projectId) {
        return cases.listQuestions(TenantContextHolder.get(),
            RequestParams.requiredUuid(projectId, "projectId"));
    }

    @PostMapping("/projects/{projectId}/classification-questions")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassificationQuestionView createQuestion(
        @PathVariable String projectId,
        @Valid @RequestBody SaveClassificationQuestionRequest body) {
        return cases.saveQuestion(TenantContextHolder.get(),
            RequestParams.requiredUuid(projectId, "projectId"), body, null);
    }

    @PutMapping("/projects/{projectId}/classification-questions/{questionId}")
    public ClassificationQuestionView updateQuestion(
        @PathVariable String projectId,
        @PathVariable String questionId,
        @Valid @RequestBody SaveClassificationQuestionRequest body) {
        return cases.saveQuestion(TenantContextHolder.get(),
            RequestParams.requiredUuid(projectId, "projectId"), body,
            RequestParams.requiredUuid(questionId, "questionId"));
    }

    @DeleteMapping("/projects/{projectId}/classification-questions/{questionId}")
    public ResponseEntity<Void> deleteQuestion(@PathVariable String projectId,
                                               @PathVariable String questionId) {
        cases.deleteQuestion(TenantContextHolder.get(),
            RequestParams.requiredUuid(projectId, "projectId"),
            RequestParams.requiredUuid(questionId, "questionId"));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // the agent's workspace
    // ------------------------------------------------------------------

    @GetMapping("/conversations/{conversationId}/classification")
    public ClassificationView getClassification(@PathVariable String conversationId) {
        return cases.getClassification(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"));
    }

    /** Answering the questions also resolves the solution they point at. */
    @PutMapping("/conversations/{conversationId}/classification")
    public ClassificationView saveClassification(
        @PathVariable String conversationId,
        @Valid @RequestBody SaveClassificationRequest body) {
        return cases.saveClassification(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"), body);
    }

    @GetMapping("/conversations/{conversationId}/resolution")
    public ResponseEntity<ResolutionView> getResolution(@PathVariable String conversationId) {
        ResolutionView view = cases.getResolution(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"));
        // A conversation with no recorded resolution is a 200 with an empty body, not a 404: the
        // conversation exists and the agent simply has not finished it. This matches the legacy
        // behaviour of returning null.
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }

    @PutMapping("/conversations/{conversationId}/resolution")
    public ResolutionView saveResolution(@PathVariable String conversationId,
                                         @Valid @RequestBody SaveResolutionRequest body) {
        return cases.saveResolution(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"), body);
    }

    @PostMapping("/conversations/{conversationId}/close")
    public Map<String, String> close(@PathVariable String conversationId,
                                     @org.springframework.web.bind.annotation.RequestHeader(
                                         name = "If-Match", required = false) String ifMatch) {
        var id = RequestParams.requiredUuid(conversationId, "conversationId");
        var context = TenantContextHolder.get();
        // The case rule first: a case closed with no account of how it ended is exactly the gap
        // the resolution record exists to prevent.
        cases.requireResolutionBeforeClose(context, id);
        conversations.updateCaseState(context, id, "CLOSED", RequestParams.ifMatch(ifMatch));
        return Map.of("status", "CLOSED");
    }

    @PostMapping("/conversations/{conversationId}/reopen")
    public Map<String, String> reopen(@PathVariable String conversationId) {
        conversations.updateCaseState(TenantContextHolder.get(),
            RequestParams.requiredUuid(conversationId, "conversationId"), "OPEN", null);
        return Map.of("status", "OPEN");
    }
}