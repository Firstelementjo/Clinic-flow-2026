package com.firstelement.errorprobe;

import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The bottom layer of the nested-refusal probes: where the rules actually live.
 *
 * @see ErrorMappingProbeRoutes for why these probes live inside {@code com.firstelement}
 */
@Repository
public class ProbeRefusalRepository {

    public void insertNote() {
        throw new ValidationFailedException("A body is required");
    }

    public Optional<String> findConversation() {
        throw new ResourceNotFoundException("Conversation");
    }

    public void rebuildSearchIndex() {
        throw new IllegalStateException("the search index rebuild failed on a half-written posting list");
    }
}