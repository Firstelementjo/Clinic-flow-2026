package com.firstelement.inbox.api;

import com.firstelement.crm.domain.ConversationMode;

/**
 * {@code PATCH /api/v1/conversations/{id}/mode} — the human takeover / return-to-AI command.
 *
 * <h2>This is a new surface, not a legacy port</h2>
 *
 * <p>The legacy runtime changed {@code mode} only from the worker: {@code ai-dispatch.ts} and
 * {@code escalation-engine.ts} performed the atomic {@code AI → HUMAN} transition and nothing
 * exposed it over HTTP. Phase 4 needs the transition reachable so the acceptance journey can
 * prove that human takeover stops an in-flight AI reply, so the same guarded update is exposed
 * here. The guard is unchanged and is the important part: the write is conditional on both the
 * expected mode and {@code conversations."modeVersion"}.
 *
 * @param mode        the target mode
 * @param modeVersion the {@code modeVersion} the caller read; a mismatch is 409
 */
public record ChangeModeRequest(ConversationMode mode, Integer modeVersion) {
}