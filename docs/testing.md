# Testing

```bash
yarn test          # once
yarn test:watch    # watch mode
```

Vitest, run from the repository root. Integration suites share one
database, so file parallelism is off — concurrent suites against a shared
database produce flakes that look exactly like isolation bugs.

## What is covered today

**Unit — no database required**

- `packages/auth/src/policy.test.ts` — the permission matrix: what each
  role can do, the organization boundary, the project boundary, and
  self-service permissions.
- `packages/auth/src/session-token.test.ts` — token uniqueness and
  entropy, that only a hash is stored, and cookie flags.
- `packages/database/src/tenant.test.ts` — scoping helpers, including that
  a user with no memberships gets an empty set rather than an unfiltered
  query.
- `apps/api/test/inbox-filter.test.ts` — what each saved inbox view means,
  and that the filter builder never emits tenant columns of its own (the
  caller always supplies the scope).

- `packages/connectors/src/mock.connector.test.ts` — normalization,
  batch handling, malformed entries, and the per-channel capability table
  (session windows, text limits, read receipts) that outbound sends check
  against.

- `packages/knowledge-base/src/chunking.test.ts` — cleaning (including the
  bidi control characters that ride along with copied Arabic), paragraph
  and sentence boundaries, overlap, and the hard cut for a single
  over-long sentence.
- `packages/knowledge-base/src/parsers.test.ts` — CSV header labelling,
  JSON flattening, upload type/size rules, and that an unreadable file
  fails with a reason rather than indexing as empty.
- `packages/ai/src/embeddings/mock.provider.test.ts` — determinism, unit
  length, dimension width, and that texts sharing vocabulary really do
  land closer together in both Arabic and English (which is what makes
  retrieval testable without an API key).

- `packages/ai/src/orchestrator/prompt.test.ts` — the prompt-injection
  regression suite (plan §9): known attack patterns in Arabic and English,
  delimiter escapes, and the framing that keeps retrieved content as data.
  It also documents, deliberately, that pattern detection is evadable and
  is telemetry rather than a defence.
- `packages/ai/src/tools/executor.test.ts` — the layer that actually holds:
  disabled tools denied and recorded, tenant identifiers in tool arguments
  ignored, writes restricted to a fixed field set.

- `packages/ai/src/scoring/evaluator.test.ts` — normalization bounds,
  determinism, that frustration climbs with repetition and unresolved AI
  turns rather than wording alone, and that Arabic anger scores like
  English anger.
- `packages/ai/src/scoring/rules.test.ts` — threshold comparison,
  ALL/ANY combination, deterministic priority ordering, the plan's default
  rules, and that an unmeasured signal never counts as zero.

- `packages/routing/src/working-hours.test.ts` — timezone conversion
  including DST, night shifts across midnight, split shifts, exclusive end
  minutes, and that no configured windows means always available.
- `packages/routing/src/strategy.test.ts` — each filter stage, which stage
  blocked an unassignable escalation, least-loaded selection, and
  determinism under a complete tie.
- `packages/routing/src/import.test.ts` — CSV parsing with quoted fields,
  English and Arabic column guessing, row-numbered validation errors, and
  duplicate reporting rather than silent skipping.

- `packages/auth/src/secret-box.test.ts` — credential encryption:
  round-trips, distinct ciphertexts for equal plaintexts, wrong-key
  failure, and tamper detection (the reason it is authenticated
  encryption rather than plain AES).
- `packages/connectors/src/meta/signature.test.ts` — Meta webhook
  signatures, including that a re-serialized body fails verification,
  which is why the API keeps the raw bytes.
- `packages/connectors/src/real-connectors.test.ts` — per-platform
  normalization: WhatsApp status callbacks and media captions, Messenger
  echo suppression, Telegram chat-scoped ids and edited-message handling,
  and that the registry refuses a half-configured channel while naming
  only the missing field names.

**Integration — requires PostgreSQL**

- `apps/api/test/tenant-isolation.integration.test.ts` — the proof plan §6
  requires before slice 2: a sibling project in the same organization and
  any project in another organization are unreachable for both reads and
  writes, foreign project ids are dropped when creating an employee, and
  employee listings stay inside the caller's organization. Slice 2 extends
  it to the CRM core: the inbox, thread reads, replies, assignment and
  resolution all stop at the project boundary, and identity resolution
  stays inside one project — the same handle in two projects is two
  customers, a verified phone links a new handle silently, and a name-only
  match is reported for confirmation instead of merged.

- `apps/api/test/inbound-pipeline.integration.test.ts` — the inbound path
  end to end: one webhook creates customer, conversation and message; a
  redelivered webhook is rejected by the unique index without moving the
  conversation's counters; later messages join the open thread; a resolved
  thread reopens when the customer writes again; and the worker-side
  timeline is recorded without copying message content into it.

- `apps/api/test/knowledge-retrieval.integration.test.ts` — the RAG
  guarantee that cannot be unit-tested: two projects hold contradictory
  refund policies, and a query in one never returns the other's chunks
  even at `minScore: 0`. Also covers ingestion to READY, exclusion of
  non-READY documents, knowledge-gap recording, and that re-processing
  replaces chunks instead of duplicating them.

- `apps/api/test/acceptance.integration.test.ts` — the plan §17 scenario
  end to end. Each slice has its own coverage; this one checks that the
  seams hold: the AI answers from project knowledge, frustration crosses
  the configured threshold, the escalation carries a reason and a summary,
  the AI stops replying once the conversation is human-handled, routing
  finds the available employee, workload is recounted, and the timeline
  records all of it without copying message content into it.

- `apps/api/test/case-classification.integration.test.ts` — configurable
  questions stored as data, the §13 guarantee that a case never retrieves
  another project's solution, answers recorded per conversation, the
  resolution record CSAT is computed from, and that reopening a case keeps
  its resolution history.

These drive the real services against a real database. Without a reachable
database it **skips loudly** — a green run that proved nothing would be
worse than a skip. To run it for real:

```bash
docker compose up -d postgres && yarn db:push && yarn test
```

## Why they used to skip

`vitest.config.ts` loads `.env`. Without it `DATABASE_URL` never reaches
the test process, Prisma fails to connect, and every integration suite
skips itself — quietly, because a skip is not a failure. That state hid
two real defects for as long as it lasted.

## What is not covered

- No browser-level E2E (Playwright or similar). The acceptance test drives
  the services directly, so routing bugs in the React app would not be
  caught by it.
- The realtime SSE stream has no automated test; it is exercised by hand.
- The real connectors are tested against captured payload shapes, not
  against live Meta or Telegram endpoints.

