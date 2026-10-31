package com.firstelement.channels.connector;

import com.firstelement.crm.domain.ChannelType;
import com.firstelement.worker.queue.PermanentFailureException;
import com.firstelement.worker.queue.RetryDecision;

/**
 * The channel cannot do what the caller asked.
 *
 * <h2>Permanent by construction</h2>
 *
 * <p>An unsupported capability will be unsupported on the next attempt too, so this is a
 * {@link PermanentFailureException} with the {@code UNSUPPORTED_CAPABILITY} label: the job
 * dead-letters immediately and the reason lands on the message row, instead of the queue
 * retrying five times against a platform that will never accept it.
 */
public class UnsupportedCapabilityException extends PermanentFailureException {

    private static final long serialVersionUID = 1L;

    private final ChannelType channel;
    private final String capability;

    public UnsupportedCapabilityException(ChannelType channel, String capability) {
        super(RetryDecision.UNSUPPORTED_CAPABILITY, "UNSUPPORTED_CAPABILITY",
            channel + " does not support " + capability);
        this.channel = channel;
        this.capability = capability;
    }

    public ChannelType channel() {
        return channel;
    }

    public String capability() {
        return capability;
    }
}