# ADR-0003 — SSE vs WebSocket Realtime

Status: PROPOSED

## Context

The current API exposes `GET /api/realtime/stream` as a Server-Sent
Events endpoint (`@Sse('realtime/stream')` in
`notifications.controller.ts`), fed by an in-memory `RealtimeGateway`
subject. Every flow is server→client; browsers reconnect automatically;
the cookie-auth and CORS setup is shared with REST. Nothing today sends
client→server frames (the client already has REST). Single-process limit
documented in code comments.

## Decision

Keep SSE as the realtime transport in the target. Angular resynchronizes
from the REST API after reconnect and treats events as refresh hints,
never source of truth. Add a Redis pub/sub bridge when more than one API
instance runs. Do not adopt WebSocket for this product unless a future
requirement (e.g. collaborative editing) demands bidirectional frames.

## Consequences

- Spring replaces the in-memory subject with a broadcaster backed by
  Redis pub/sub for multi-instance fan-out.
- Payloads remain ids/states only — no message content on the wire.
- Realtime tests must prove resync-from-API after reconnect, not trust
  missed frames.
