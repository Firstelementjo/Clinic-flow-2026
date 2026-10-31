package com.firstelement.inbox.api;

import com.firstelement.crm.api.ConversationDetail;
import java.time.LocalDateTime;

/**
 * The result of an employee reply.
 *
 * <p>The HTTP body is still the legacy {@link ConversationDetail}; this wrapper carries the two
 * facts the legacy response had nowhere to put, and the controller renders them as headers so the
 * JSON stays byte-comparable with the legacy shape.
 *
 * @param replayed          {@code true} when an {@code Idempotency-Key} matched an existing
 *                          message and nothing was written
 * @param collisionDetected {@code true} when the thread had moved since the caller's read; the
 *                          reply was still written, because dropping it would lose a customer
 *                          communication
 * @param messageId         the stored message, or the replayed one
 * @param versionBefore     the thread's {@code updatedAt} as the caller saw it
 */
public record ReplyResult(ConversationDetail detail,
                          boolean replayed,
                          boolean collisionDetected,
                          java.util.UUID messageId,
                          LocalDateTime versionBefore) {
}