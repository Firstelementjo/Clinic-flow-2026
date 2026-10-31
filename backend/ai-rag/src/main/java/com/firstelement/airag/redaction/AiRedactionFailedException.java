package com.firstelement.airag.redaction;

import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.policy.AiRefusedException;

/**
 * Thrown when redaction cannot be completed.
 *
 * <h2>A redaction failure is a failure, not a silent pass</h2>
 *
 * <p>This is the single most consequential exception in the module. The tempting behaviour on a redaction
 * error is to continue with the unredacted text, and every reason to do that is wrong:
 *
 * <ul>
 *   <li>The rules are compiled once at startup, so a rule that cannot compile is a build defect, and
 *       continuing would ship a run that reports "redaction policy STRICT_CLINICAL" while applying nothing.</li>
 *   <li>A regex catastrophic-backtrack on a crafted document is a denial of service against the AI path.
 *       Letting it through turns a slow query into a leaked prompt.</li>
 *   <li>The compliance report is generated from {@code ai_redaction_records}. A run that failed redaction
 *       and recorded nothing reads as "no sensitive data was present", which is the most dangerous possible
 *       false statement this system can make.</li>
 * </ul>
 *
 * <p>So the run is refused with {@link AiRefusalReason#REDACTION_FAILED} and the trace records the refusal.
 * The exception message is fixed and carries the stage, never the text that failed to redact.
 */
public class AiRedactionFailedException extends AiRefusedException {

    private static final long serialVersionUID = 1L;

    private final String stage;

    public AiRedactionFailedException(String stage, String detail, Throwable cause) {
        super(AiRefusalReason.REDACTION_FAILED, "redaction failed at " + stage + ": " + detail, cause);
        this.stage = stage;
    }

    /** Which stage failed: {@code prompt.system}, {@code prompt.untrusted}, {@code response.assistant}. */
    public String stage() {
        return stage;
    }
}