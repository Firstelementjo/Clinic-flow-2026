package com.firstelement.observ.search;

import com.firstelement.security.TenantContext;
import com.firstelement.security.query.TenantQueryGuard;
import com.firstelement.security.query.TenantScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Search and vector freshness, plus the realtime health panel.
 *
 * <h2>Why realtime health lives here rather than on the notification routes</h2>
 *
 * <p>FR-6 lists "realtime connection/resync health" as an Admin question. {@code GET
 * /api/v1/realtime/state} already returns {@code RealtimeStreamService.health()} for the caller's own
 * tab, and that method is the single definition of the counters — so this panel reads the same
 * object rather than counting subscribers a second way. A second definition would eventually disagree
 * with {@code /actuator/health}, which is the one an operator trusts at 3am.
 */
@Service
@Transactional(readOnly = true)
public class SearchFreshnessService {

    private final SearchFreshnessRepository search;
    private final com.firstelement.notifications.realtime.RealtimeStreamService realtime;
    private final com.firstelement.security.realtime.RealtimeTopicPolicy topicPolicy;
    private final TenantQueryGuard guard;
    private final com.firstelement.security.permission.AuthorizationService authorization;

    public SearchFreshnessService(SearchFreshnessRepository search,
                                  com.firstelement.notifications.realtime.RealtimeStreamService
                                      realtime,
                                  com.firstelement.security.realtime.RealtimeTopicPolicy
                                      topicPolicy,
                                  TenantQueryGuard guard,
                                  com.firstelement.security.permission.AuthorizationService
                                      authorization) {
        this.search = search;
        this.realtime = realtime;
        this.topicPolicy = topicPolicy;
        this.guard = guard;
        this.authorization = authorization;
    }

    /**
     * The permission check, here as well as on the route.
     *
     * <p>{@code KNOWLEDGE_READ} for the two knowledge panels — index freshness is knowledge-base state,
     * and it has been an employee grant since Phase 3 — and {@code SYSTEM_HEALTH_READ} for the
     * realtime counters, which are process-level and carry no tenant data. The route declares the same
     * two; this makes them unbypassable for a caller that is not HTTP.
     */
    private void authorize(TenantContext caller,
                           com.firstelement.security.permission.Permission permission) {
        if (caller == null) {
            throw new IllegalStateException(
                "An operations read requires a resolved TenantContext; refusing to run unscoped.");
        }
        authorization.check(caller, permission,
            com.firstelement.security.permission.PolicyResource.NONE);
    }

    /** The freshness panel: per-document state, pending index work, propagation, gaps. */
    public FreshnessView freshness(TenantContext caller, UUID projectId, Integer idleHours,
                                   Integer limit) {
        authorize(caller, com.firstelement.security.permission.Permission.KNOWLEDGE_READ);
        TenantScope scope = guard.scope(caller, projectId);
        int idle = idleHours == null || idleHours < 1 ? 1 : Math.min(idleHours, 8760);
        int bounded = limit == null || limit < 1
            ? com.firstelement.observ.OperationsObservabilityModule.Limits.DOCUMENTS
            : Math.min(limit, com.firstelement.observ.OperationsObservabilityModule.Limits.DOCUMENTS);

        List<SearchFreshnessRepository.DocumentFreshness> documents = search.documents(scope,
            scope.projectId(), bounded);
        long unembedded = documents.stream()
            .mapToLong(SearchFreshnessRepository.DocumentFreshness::unembeddedChunks).sum();
        long disagreements = documents.stream()
            .filter(document -> !document.chunkCountAgrees()).count();
        long readyButEmpty = documents.stream()
            .filter(document -> "READY".equals(document.status()) && document.actualChunks() == 0)
            .count();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope.isProjectScoped() ? "PROJECT" : "TENANT");
        payload.put("projectId", scope.projectId());
        payload.put("byStatus", search.byStatus(scope));
        payload.put("pendingIndexChunks", unembedded);
        payload.put("chunkCountDisagreements", disagreements);
        payload.put("readyButEmpty", readyButEmpty);
        payload.put("stuck", search.stuck(scope, idle * 3600,
            com.firstelement.observ.OperationsObservabilityModule.Limits.DOCUMENTS));
        payload.put("propagation", search.propagation(scope));
        payload.put("documents", documents);
        payload.put("note", "documents[].actualChunks and documents[].unembeddedChunks are the two "
            + "numbers that matter: a READY document with unembedded chunks is indexed for the "
            + "operator and invisible to retrieval. No chunk content is selected by any statement "
            + "here.");
        return new FreshnessView(payload);
    }

    /** Knowledge gaps: retrievals that found nothing above the threshold. */
    public KnowledgeGapView knowledgeGaps(TenantContext caller, UUID projectId, Boolean onlyOpen,
                                          Integer limit) {
        authorize(caller, com.firstelement.security.permission.Permission.KNOWLEDGE_READ);
        TenantScope scope = guard.scope(caller, projectId);
        int bounded = limit == null || limit < 1
            ? com.firstelement.observ.OperationsObservabilityModule.Limits.KNOWLEDGE_GAPS
            : Math.min(limit, com.firstelement.observ.OperationsObservabilityModule.Limits.KNOWLEDGE_GAPS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("counts", search.gapCounts(scope));
        payload.put("gaps", search.gaps(scope, scope.projectId(), onlyOpen, bounded));
        payload.put("note", "The query text that produced a gap is NOT projected. A gap is by "
            + "definition something a customer typed that matched nothing, so the count, the best "
            + "score and the age are the operator-actionable facts and the text is customer content.");
        return new KnowledgeGapView(payload);
    }

    /**
     * Realtime connection and resync health.
     *
     * <p>The process-level counters come from {@code RealtimeStreamService.health()} — the same
     * object {@code GET /api/v1/realtime/state} returns — plus the topics this caller may subscribe
     * to, derived from the resolved {@link TenantContext} by the boundary's own policy. The panel
     * therefore cannot report a topic the caller is not entitled to, and cannot disagree with the
     * client-facing read model.
     */
    public RealtimeView realtime(TenantContext caller) {
        authorize(caller, com.firstelement.security.permission.Permission.SYSTEM_HEALTH_READ);
        var health = realtime.health();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "PROCESS");
        payload.put("localSubscribers", health.localSubscribers());
        payload.put("connectionsOpened", health.connectionsOpened());
        payload.put("resyncsSent", health.resyncsSent());
        payload.put("multiInstanceCapable", health.multiInstanceCapable());
        payload.put("channel", health.channel());
        payload.put("subscribersOnChannel", health.subscribersOnChannel());
        payload.put("yourTopics", new ArrayList<>(topicPolicy.allowedTopics(caller)));
        payload.put("note", "multiInstanceCapable answers 'configured', not 'connected': it is true "
            + "whenever a connection factory exists, which is also true with the broker down. The "
            + "evidence an operator needs is the subscriber log line and "
            + "subscribersOnChannel. Connection and resync counters are process-level and carry no "
            + "tenant data.");
        return new RealtimeView(payload);
    }

    /** The freshness panel document. */
    public record FreshnessView(Map<String, Object> document) {
    }

    /** The knowledge-gap panel document. */
    public record KnowledgeGapView(Map<String, Object> document) {
    }

    /** The realtime panel document. */
    public record RealtimeView(Map<String, Object> document) {
    }
}