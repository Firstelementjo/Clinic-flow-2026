package com.firstelement.channels.api;

/**
 * One attachment on a normalized message.
 *
 * <p>Ported from {@code NormalizedAttachment}. Media ids are <b>not</b> URLs: fetching the bytes
 * needs a second authorized call, so the provider's own id is what is kept.
 *
 * @param sizeBytes null when the provider does not report it; never defaulted to zero, which
 *                  would read as "an empty file"
 */
public record NormalizedAttachment(NormalizedAttachmentType type,
                                   String providerId,
                                   String mimeType,
                                   String fileName,
                                   Long sizeBytes) {

    public NormalizedAttachment {
        type = type == null ? NormalizedAttachmentType.UNKNOWN : type;
    }
}