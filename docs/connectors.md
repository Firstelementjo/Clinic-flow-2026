# Messaging connectors

External platforms are adapters, never core business logic (plan §20.5).
Everything above the connector layer — CRM, AI, scoring, routing — sees
`NormalizedIncomingMessage` and nothing else. A WhatsApp payload never
travels past the adapter that parsed it.

## The contract

`packages/connectors` defines `MessagingConnector`:

```ts
capabilities(): ConnectorCapabilities;
connect() / disconnect();
verifyWebhook(request): Promise<boolean>;
receiveWebhook(payload): Promise<NormalizedIncomingMessage[]>;
sendText(message): Promise<SendResult>;
sendMedia?(message): Promise<SendResult>;
markAsRead?(conversation): Promise<void>;
```

`sendMedia` and `markAsRead` are optional because two of the four target
platforms do not support the same set — see capabilities below.

## Capabilities, not assumptions

The four platforms differ in ways that break outbound sends if assumed
away: WhatsApp closes its customer-service window 24 hours after the last
inbound message and requires an approved template after that; Instagram
caps text far lower than WhatsApp; Telegram has no read receipts but does
allow edits and deletes.

`CHANNEL_CAPABILITIES` states these per channel, and the outbound path
calls `checkOutboundText` before sending. A message that a channel cannot
carry fails with a reason an agent can act on ("Message is 1,240
characters; INSTAGRAM allows 1,000") rather than a platform error nobody
can read. The UI reads the same table on the channel panel.

| | WhatsApp | Instagram | Messenger | Telegram | Mock |
| --- | --- | --- | --- | --- | --- |
| Session window | 24h | 24h | 24h | none | none |
| Template required after window | yes | no | no | no | no |
| Max text | 4096 | 1000 | 2000 | 4096 | 4000 |
| Read receipts | yes | yes | yes | no | yes |
| Edit / delete | no | no | no | yes | no |
| Webhook signature | yes | yes | yes | secret token | n/a |

## Idempotency

Providers redeliver. The guarantee is a unique index on
`(projectId, channel, externalMessageId)` — a duplicate is rejected by
PostgreSQL, not by an application-level "check then insert" that races
under concurrent deliveries and double-posts to the customer.

There is a second, cheaper layer in front: the queue job id is
`projectId:channel:externalMessageId`, so BullMQ drops a repeat before it
reaches the database. The database constraint is the guarantee; the job id
is an optimization.

A duplicate is reported as `{ status: 'duplicate' }`, not an error — it is
normal provider behaviour, and it must stay distinguishable from a lost
message. The conversation's `messageCount` and `lastMessageAt` do not move
for a duplicate; a counter bump would silently reorder the whole inbox.

## Inbound path

```
webhook → verify → normalize → enqueue
                                  │
                                  ▼   (worker)
       resolve identity → find/create conversation → store message
                                  │
                                  ▼
                        publish domain events
                     (timeline, and later AI/scoring)
```

New conversations are created in `HUMAN` mode. Nothing auto-replies until
slice 5 gives a project an AI agent — waiting for a person is the safe
default (plan §20.1). A customer writing into a resolved thread reopens it
rather than opening a second one.

## Outbound path

Persist first, send second. A send that succeeds while the database write
fails leaves the customer holding a reply the CRM has no record of, and the
agent repeats themselves. So a reply is written `PENDING`, queued, and then
moved to `SENT` or `FAILED` by `OutboundDispatcher`. Failures are shown on
the message in the thread, never swallowed. A retried job that finds the
message already delivered skips instead of sending twice.

## The mock connector

`MockConnector` (plan §13) exists so the whole pipeline runs without live
Meta or Telegram credentials. It is not a stub that fakes success: it
normalizes like a real adapter, declares capabilities, and records sends in
an inspectable outbox.

Simulate an inbound message from the channel panel
(`/app/projects/:projectId/channels`), or directly:

```bash
curl -X POST http://localhost:4000/api/dev/mock/incoming-message -H "Content-Type: application/json" -b cookies.txt -d '{"projectId":"<uuid>","externalUserId":"wa-555-0100","displayName":"نورة","text":"أين طلبي؟"}'
```

Two gates protect it, because a message-injection endpoint is a way to
impersonate customers: it 404s unless `ENABLE_MOCK_CONNECTOR=true` (which
configuration refuses in production), and it still requires a session with
access to the target project.

## Queues

`QUEUE_DRIVER=redis` hands work to the BullMQ worker — the production path,
with retries and backoff from `DEFAULT_JOB_OPTIONS`. `QUEUE_DRIVER=inline`
runs the same job functions in the API process so the pipeline is
exercisable without Redis; configuration rejects it in production, because
inline work dies with the process.

## The real connectors

All four are implemented against the official APIs only — no scraping, no
unofficial endpoints (plan §8).

### WhatsApp (Cloud API)

Identity is a phone number the platform vouches for, so it is a **verified
signal** and identity resolution may link a new handle to an existing
customer on it (plan §7).

Two platform behaviours the adapter absorbs: delivery-status callbacks
arrive on the same webhook in a `statuses` array and are not conversation
turns, and a media message with no caption becomes a readable body
(`[image]`) rather than an empty one.

### Instagram and Messenger (Messenger Platform)

One implementation, parameterized by channel: same webhook shape, same
`/me/messages` send, same page access token. They differ only in
capabilities — Instagram caps text at 1,000 characters against Messenger's
2,000.

The quirk that matters most: **echoes**. Messages we send come back on the
webhook with `is_echo: true`, and storing them would double every outbound
message in the thread. Identity is a page-scoped id — not a phone or email
— so it is *not* a verified signal and will not silently merge with an
existing customer.

### Telegram (Bot API)

No payload signature: Telegram sends a shared secret in
`X-Telegram-Bot-Api-Secret-Token`, compared in constant time. That plus
HTTPS is the whole authentication story, which is why the secret is
required rather than optional.

Message ids are unique per chat, not globally, so the external id is
`chatId:messageId` — otherwise two chats would collide in the idempotency
constraint. Edited messages arrive as a separate field and are ignored:
treating an edit as a new message would duplicate the turn.

## Webhook security

The webhook routes are the only unauthenticated write paths in the API:

- **Every request is verified before it is trusted.** Meta signs the body
  with the app secret (`X-Hub-Signature-256`); Telegram sends its secret
  token. A request that fails is rejected before anything is enqueued.
- **Signatures are checked against the raw bytes.** `JSON.parse` followed
  by `JSON.stringify` drops the sender's whitespace and hashes
  differently, so `main.ts` keeps the untouched body for the check.
- **Comparisons are constant-time.** `===` on a digest leaks it one byte
  at a time to anyone willing to measure.
- **The connection id in the URL is not authorization** — it selects which
  credentials to verify against. The signature is what proves the caller.
- **A verified request always gets 200**, even when processing fails.
  Meta and Telegram retry aggressively on non-2xx and eventually disable a
  failing webhook; durability belongs to the queue, not the HTTP response.
- **Unknown connection and bad signature return the same 404**, so probing
  cannot enumerate connection ids.

## Credentials at rest

AES-256-GCM via `packages/auth/src/secret-box.ts`, keyed by
`ENCRYPTION_KEY`, with a `v1.` envelope prefix so the key or algorithm can
be rotated later. Authenticated encryption is the point: a tampered blob
fails to decrypt rather than yielding plausible garbage that then gets
sent to Meta as an access token.

Credentials are **write-only through the API**. There is no endpoint that
returns them, and `ChannelsService.publicSelect` does not select the
column at all — not selected-and-stripped, so a future field cannot leak
by being forgotten in a strip step. Decryption happens in exactly one
place (`loadConnectionWithCredentials`), deliberately outside the registry,
so building a connector requires someone to have asked for decryption on
purpose.

A failed decryption is logged and treated as "not configured" rather than
thrown: a rotated key should surface as a channel needing reconnection,
not a crashed worker.

## Registering a webhook

The channel panel shows the URL to register:

```
https://<api-host>/api/webhooks/meta/<connectionId>       # WhatsApp, Instagram, Messenger
https://<api-host>/api/webhooks/telegram/<connectionId>   # Telegram
```

For Meta, the same URL serves the GET verification handshake — it echoes
`hub.challenge` only when the verify token matches. For Telegram, pass the
same `webhookSecret` to `setWebhook`.
