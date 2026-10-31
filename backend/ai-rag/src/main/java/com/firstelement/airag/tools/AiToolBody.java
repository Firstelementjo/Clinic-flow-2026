package com.firstelement.airag.tools;

import java.util.Map;

/**
 * The executable part of a tool.
 *
 * <h2>Why the signature carries no SQL, no template and no column list</h2>
 *
 * <p>FR-5.5 and the phase-10 hard rule: "a tool may not be constructed from caller-supplied SQL, a template,
 * or a column list". The signature makes that impossible to violate — a body receives an
 * {@link AiToolContext} and returns a map. There is no parameter of type {@code String} that is SQL, no
 * parameter that is a column name, and no map key that selects a table.
 *
 * <p>The two {@code Map} parameters are the <b>validated</b> arguments and the <b>redacted</b> result. The
 * result map is what becomes {@code ai_tool_executions."resultRedacted"}, so a body that returns customer
 * content writes customer content into a redacted column — which is why the executor's redaction pass runs
 * over the result before it is stored, and why {@link #dryRun} exists for a body whose real effect is the
 * point.
 */
public interface AiToolBody {

    /**
     * Performs the tool's work.
     *
     * @param context the authorized scope, the run, and the validated arguments
     * @return a small map of scalar facts — counts, ids, statuses. Not a row, not an entity.
     * @throws RuntimeException to fail; the executor records it as an {@code isError} tool call and the run
     *         continues with the tool unavailable, rather than failing the whole answer
     */
    Map<String, Object> perform(AiToolContext context);

    /**
     * Reports what the tool <em>would</em> do, without doing it.
     *
     * <p>Required for every consequential tool, and the reason the phase can honour ADR 0007 Part 2 without
     * a staging table: a dry run is how an approver sees the intended effect. The default implementation
     * refuses rather than returning a plausible-looking summary, because "I would do roughly this" is not a
     * description an approver can consent to.
     */
    default Map<String, Object> dryRun(AiToolContext context) {
        throw new UnsupportedOperationException(
            "This tool declares no dry run. A consequential tool must be able to describe its intended "
                + "effect, because that description is what a human approves.");
    }
}