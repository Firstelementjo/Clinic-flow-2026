# Finance metric catalogue

**Phase 8 — `:backend:reporting-finance`. ADR 0007 Part 1, ADR 0009 §3.**

This document is the human-readable catalogue of what the finance ledger measures. The enforced
version of it is `com.firstelement.finance.catalog.FinanceMetricCatalogue`, and
`FinanceMetricCatalogueTest` fails the build if the table below and that class disagree on any
dimension, unit, family, aggregation, emit-time-cost rule or source event type. A document that
drifts from the code is worse than no document, so this one is checked.

---

## 1. The twelve dimensions

| Dimension | Unit | Family | Aggregation | Cost known at emit time | Source event types | What it counts |
|---|---|---|---|---|---|---|
| `MESSAGE` | `message` | DELIVERY | SUM | yes | `messages.delivered`, `messages.received`, `messages.sent_by_agent` | One message delivered to, or received from, a customer channel. |
| `CONVERSATION` | `conversation` | DELIVERY | SUM | yes | `conversations.opened`, `conversations.resolved`, `conversations.taken_over`, `conversations.returned_to_ai` | One conversation reaching a state a tenant can be charged for or measured against. |
| `CHANNEL_TRAFFIC` | `kb` | DELIVERY | SUM | yes | `channel_traffic.metered`, `channel_connections.traffic_metered` | Kilobytes carried on a channel connection, metered by the provider. |
| `SEAT` | `seat_day` | SUBSCRIPTION | SUM | yes | `seat_licenses.day`, `users.seat_day` | One licensed seat for one calendar day. |
| `STORAGE` | `byte_day` | SUBSCRIPTION | SUM | yes | `storage.byte_day`, `document_chunks.byte_day`, `export_jobs.byte_day` | Retained bytes for one day, including export artefacts inside their retention window. |
| `API_OPERATION` | `operation` | USAGE | SUM | no | `api_operations.metered` | One metered API operation. |
| `WORKFLOW_RUN` | `run` | USAGE | SUM | no | `workflow_runs.started`, `workflow_runs.completed`, `workflow_runs.failed` | One workflow run. Counted at start; completion and failure are separate source types on the same run so a run is never counted twice. |
| `REPORT_EXPORT` | `export` | USAGE | SUM | no | `export_jobs.completed`, `report_runs.produced` | One export or report artefact produced. |
| `AI_REQUEST` | `request` | AI | SUM | no | `ai_runs.started`, `ai_runs.completed`, `ai_runs.failed` | One AI run request. Counted at start so a failed run is still usage a provider billed. |
| `AI_TOKEN` | `token` | AI | SUM | no | `ai_usage.tokens_recorded`, `ai_runs.tokens_recorded` | One AI token, input or output, as reported by the provider for the run. |
| `TIME_ENTRY` | `minute` | EFFORT | SUM | no | `time_entries.approved`, `time_entries.recorded` | One billable minute of effort from an approved time entry. Never derived from a conversation count or a handle time: effort is entered, and this module refuses to compute it from anything else. |
| `DOCUMENT_CHUNK` | `chunk` | USAGE | SUM | no | `document_chunks.indexed`, `document_chunks.embedded` | One indexed knowledge chunk. |

Four constraints are asserted when the catalogue is constructed, not when it is documented:

1. **Every declared dimension has a metric, and every metric a declared dimension.** The twelve
   members of the `UsageDimension` database enum are the whole taxonomy; there is no
   `register()` method. Adding a dimension is a code change with a migration behind it, so there is
   never a dimension in the enum that nothing can emit.
2. **Canonical units are unique across dimensions.** The rollup snapshot is unique per
   `(organizationId, projectId, grain, bucketStart, dimension)` and carries no unit column, so two
   dimensions sharing a unit would invite a total across two different things.
3. **Every source event type belongs to exactly one dimension.** `sourceEventType` participates in
   the duplicate-suppression key together with `dimension`, so one source row may legitimately
   produce two dimensions (an AI run produces `AI_REQUEST` and `AI_TOKEN`); it may not produce two
   *different* dimensions for the same fact.
4. **Every source event type is `Table.operation`.** The drill-through reads it as "which row
   produced this", and a bare table name would leave the row unidentifiable.

---

## 2. The five families, and what "cost known at emit time" decides

| Family | Cost known at emit time | Meaning |
|---|---|---|
| `DELIVERY` | yes | Usage a customer causes and a provider meters: messages, conversations, channel traffic. The provider's tariff produced the cost, and the provider's invoice is the evidence. |
| `SUBSCRIPTION` | yes | Consumption of a licensed resource: seats and retained storage. The emit-time cost is the tenant's charged amount for that day, which is a rate-card figure — an emitter that has not consulted a rate card leaves the cost `NULL` and the bucket is unpriced until one exists. |
| `USAGE` | no | Metered operations the tenant drives: API calls, workflow runs, exports, indexed chunks. |
| `AI` | no | AI provider calls. |
| `EFFORT` | no | Approved human or automated effort. Never derived from a message count. |

The "cost known at emit time" column is not documentation. `UsageEventFactory` consults it before it
accepts a cost: for a `USAGE`, `AI` or `EFFORT` dimension there is **no cost parameter in the method
signature at all**, so a caller cannot write a fabricated cost onto an `AI_TOKEN` event. That is
ADR 0007 rule 3 — no mutable counter becomes money — enforced by the type rather than by a check
somebody can forget.

---

## 3. The four amount states

`com.firstelement.finance.money.AmountState` — a finance figure is never `null`-or-zero.

| State | Means | Amount |
|---|---|---|
| `NO_DATA` | No usage was recorded for this bucket. The absence of evidence. | none |
| `UNPRICED` | Usage exists and has a cost, but no rate-card version was effective on the usage date. The presence of evidence that nobody has agreed a price for. | none |
| `PRICED_ZERO` | A rate applied and the arithmetic produced exactly zero. | `0.000000` |
| `PRICED_NONZERO` | A rate applied and the arithmetic produced a positive amount. | `> 0` |

`UNPRICED` is deliberately **not** a `UsageDimension` member. It is not a kind of usage; it is the
absence of a rate effective on a date. As an enum member it would be something a dashboard could
count, sum and chart next to real dimensions — and a category that can be counted is a category that
gets treated as zero.

Margin and the reconciliation difference are the only signed amounts in the system. Everything else
is a magnitude, and `Money.amount()` throws rather than returning `0` for an absent state.

---

## 4. Who emits what, and where the hooks are

Every emission goes through `UsageEventFactory`, inside the emitting module's own `@Transactional`
method. `UsageEventWriter` declares `MANDATORY` propagation, so a call made outside a transaction
fails rather than writing a row that no other business operation could roll back.

| Factory method | Dimension | Emitting module (hook needed; **not** implemented here) |
|---|---|---|
| `message` / `inboundMessage` | `MESSAGE` | message send/receive — `:backend:crm-core` or `:backend:channels` |
| `conversation` | `CONVERSATION` | conversation open/resolve/take-over/return-to-AI — `:backend:conversations` |
| `channelTraffic` | `CHANNEL_TRAFFIC` | provider traffic meter — `:backend:channels` |
| `seatDay` | `SEAT` | nightly seat meter — scheduler/job runtime |
| `storageByteDay` | `STORAGE` | nightly storage byte-day meter — scheduler/job runtime |
| `apiOperation` | `API_OPERATION` | metered API edge — the HTTP filter chain |
| `workflowRun` | `WORKFLOW_RUN` | workflow run start — `:backend:workflows-sla-routing` |
| `reportExport` | `REPORT_EXPORT` | export job completion — `:backend:administration` |
| `documentChunk` | `DOCUMENT_CHUNK` | chunk indexing/embedding — the AI/RAG module |
| `timeEntry` | `TIME_ENTRY` | approved time entry — the time module |
| `aiRequest` | `AI_REQUEST` | AI run start/complete/fail — the Phase 9 AI module |
| `aiToken` | `AI_TOKEN` | token recording — the Phase 9 AI module |

Every emitter carries an idempotency key of `(sourceEventType, sourceEventId, dimension)`. A retried
job re-emitting the same source row is suppressed, which is what makes it safe to call these from a
retrying worker rather than from a transaction that might be replayed.

---

## 5. What is deliberately *not* in this catalogue

- **`ai_usage.estimatedCost` is not migrated into the ledger as an amount** (ADR 0009 §3). It stays
  where it is and is read only by `LegacyAnalyticsComparisonService`, which reports it beside the
  ledger's own figure and classifies the disagreement as a variance. A mutable counter that a previous
  code path recomputed is a comparison source, never a source of money.
- **No payment execution, no invoicing, no payout state** (ADR 0007 rule 7). There is no route, no
  request field and no table that could hold a card, a mandate or an invoice number;
  `FinanceRoutePermissionTest` asserts the absence on the request surface.
- **No derived effort.** `TIME_ENTRY` is entered, and `UsageEventFactory.timeEntry` refuses a null
  quantity rather than inferring minutes from anything.
- **No "unpriced" dimension.** See §3.

---

## 6. Configurable billable dimensions

What a dimension *costs* is configuration: a `rate_card_version_lines` row naming a dimension, a
`costAmount`, an optional per-line `markupRate`, and the unit. What may be *measured* is the closed
table above. A tenant enables, disables and reprices dimensions by editing rate-card versions; it
cannot invent a dimension, because an undeclared dimension is unrepresentable rather than merely
unpriced.

"Configurable" therefore means the pricing and the enabled set per tenant — not the set of things the
system can count.

---

## 7. Currency and FX

- Every `usage_events` row carries `currency`, and it is required. There is no amount without a
  currency.
- If a row's amount was converted into another currency, it also carries `fxRate`, `fxSource` and
  `fxRateDate`. All three or none: a rate with no source and no date is not an FX attribution, it is a
  number someone liked.
- A rate-card version is single-currency, and a line may not disagree with its card's currency. A card
  carrying two currencies has a rate inside it, which is how "which rate applies" stops being
  answerable.
- Rollups group by `(dimension, currency)` rather than summing across currencies. Margin is computed
  per currency.

---

## 8. Where the numbers come from

| Figure | Source |
|---|---|
| Quantity, cost | `usage_events` — immutable, appended once, never updated |
| Revenue | Derived at rollup time against the rate-card version effective on the bucket's date, then **pinned** onto the snapshot row |
| Margin | Derived per snapshot from the pinned cost and pinned revenue, per currency |
| `NO_DATA` | A bucket with no contributing events is not materialised at all; the read model reports the missing bucket explicitly |
| `UNPRICED` | A bucket exists, has events, and its `rateCardVersionId` is `NULL` |
| Forecast | An extrapolation, and the response carries the method and a confidence level |

A rate change never mutates a computed snapshot. `usage_rollup_snapshots` is append-only: a
recomputation writes generation *N+1* alongside *N*, supersedes it, and leaves the reported figure
exactly as it was reported.