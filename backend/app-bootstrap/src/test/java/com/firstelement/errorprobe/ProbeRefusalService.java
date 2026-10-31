package com.firstelement.errorprobe;

import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The middle layer of the nested-refusal probes.
 *
 * <p>A refusal raised two or three frames below the handler method is the ordinary case: the
 * controller calls a service, the service calls a repository, and the rule lives at the bottom. The
 * dispatcher sees one exception either way -- but only if nothing in between catches it and
 * rethrows something broader, which is exactly the mistake that turns a 400 into a 500 halfway up
 * a stack.
 *
 * @see ErrorMappingProbeRoutes for why these probes live inside {@code com.firstelement}
 */
@Service
public class ProbeRefusalService {

    private final ProbeRefusalRepository repository;

    public ProbeRefusalService(ProbeRefusalRepository repository) {
        this.repository = repository;
    }

    /** A domain rule enforced below the service. */
    public Map<String, Object> recordNote() {
        repository.insertNote();
        return Map.of("recorded", true);
    }

    /** A row lookup that returns nothing, below the service. */
    public Map<String, Object> loadConversation() {
        return Map.of("conversation", repository.findConversation());
    }

    /** A fault below the service, which must stay a 500 all the way up. */
    public Map<String, Object> rebuildIndex() {
        repository.rebuildSearchIndex();
        return Map.of("rebuilt", true);
    }

    /** Kept so the nested cases are visibly domain refusals and not plumbing errors. */
    static Class<?> refusalType() {
        return ValidationFailedException.class;
    }

    static Class<?> missingType() {
        return ResourceNotFoundException.class;
    }
}