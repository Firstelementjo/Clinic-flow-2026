package com.firstelement.channels.api;

/**
 * The attachment kinds the normalized contract recognises.
 *
 * <p>Ported verbatim from {@code NormalizedAttachment['type']}. {@code UNKNOWN} exists so a
 * provider that adds a media type does not break the pipeline: the kind is preserved and the
 * connector's text extraction still produces something a human can read.
 */
public enum NormalizedAttachmentType {

    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    STICKER,
    LOCATION,
    UNKNOWN
}