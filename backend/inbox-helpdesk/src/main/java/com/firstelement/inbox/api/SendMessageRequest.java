package com.firstelement.inbox.api;

/** {@code POST /api/v1/conversations/{id}/messages} body — the legacy {@code sendMessageSchema}. */
public record SendMessageRequest(String body) {

    public static final int MAX_BODY = 4000;
}