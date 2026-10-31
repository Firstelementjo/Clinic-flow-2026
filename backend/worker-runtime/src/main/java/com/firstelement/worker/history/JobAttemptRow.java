package com.firstelement.worker.history;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One recorded attempt, as {@code job_attempts} stores it.
 *
 * <p>The row is append-only: {@code V20261004000700} gives the table no {@code updatedAt} "because
 * an attempt is a fact about the past". A retry therefore writes a NEW row with the next
 * {@code attemptNumber}, which is what makes "replay creates a row, never mutates one" checkable.
 *
 * @param outcome      {@code SUCCEEDED | RETRYABLE_FAILURE | NON_RETRYABLE_FAILURE | CANCELLED | SKIPPED}
 * @param retryClass   the stable failure label; null on success
 * @param errorCode    a short code; never prose, never a customer message
 * @param errorMessage a redacted one-liner; truncated by the writer
 * @param durationMs   wall time of the attempt; null while still running
 * @param workerId     which worker made the attempt
 * @param isReplay     true when an operator replay produced this attempt
 * @param replayOfId   the failed attempt a replay names; non-null exactly when {@code isReplay}
 * @param outboxEventId the durable row an operator can open from this attempt
 */
public record JobAttemptRow(UUID id,
                            UUID organizationId,
                            UUID projectId,
                            String queueName,
                            String jobType,
                            String jobId,
                            int attemptNumber,
                            String outcome,
                            String retryClass,
                            String errorCode,
                            String errorMessage,
                            LocalDateTime startedAt,
                            LocalDateTime finishedAt,
                            Integer durationMs,
                            String workerId,
                            String correlationId,
                            String causationId,
                            boolean isReplay,
                            UUID replayOfId,
                            UUID outboxEventId,
                            LocalDateTime createdAt) {

    /** The maximum an error message may occupy. Operator-facing, so it is bounded. */
    public static final int MAX_ERROR_MESSAGE = 500;
}