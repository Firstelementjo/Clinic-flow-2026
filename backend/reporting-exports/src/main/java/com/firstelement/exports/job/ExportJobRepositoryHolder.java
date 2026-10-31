package com.firstelement.exports.job;

/**
 * The handler's declared name, for a client that asks where an export is waiting.
 *
 * <h2>Why a constant rather than a bean reference</h2>
 *
 * <p>The queue endpoint answers "which queue does my export sit in", and the answer must be the value the
 * worker runtime actually claims — which lives on {@code QueueName} in {@code :backend:worker-runtime} and
 * is mirrored here as a single constant the repository owns. Reading it from a bean would mean the controller
 * depends on the handler for a string, which is one more coupling for no additional truth.
 */
public final class ExportJobRepositoryHolder {

    private ExportJobRepositoryHolder() {
    }

    /** The Spring bean name of the handler that drains export work. */
    public static final String HANDLER_NAME = "exportJobHandler";
}