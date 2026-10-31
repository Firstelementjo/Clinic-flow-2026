# Phase 10b — schema remediation: five confirmed defects

Agent B (database). Branch `migration/spring-angular`, 2026-10-06.
Scope: `ops/flyway/**`, `backend/database/**`, `docs/migration/flyway-runbook.md`,
this document.

Five confirmed schema defects, five **new additive migrations**, no existing
migration amended. `firstelement` untouched: 45 tables, 0 `flyway_schema_history`
rows, fingerprint `66eeaadab4ad28a8f8eaf8fe1a0748f9`, 4 users, 3 conversations.

---

## 1. Status

| | |
| --- | --- |
| Defects remediated | **5 / 5** |
| New migrations | 5 (`20261006100000` … `20261006140000`) |
| Chain length | 17 → **22** files, byte-identical in both copies |
| `:backend:database:test` | **23 tests, 0 failures, 0 skipped** |
| End-to-end rehearsal | **33 state assertions, all PASS** on six throwaway databases, all six dropped |
| Expected state updated | `ops/flyway/expected-state.json` `postBoot`, all counts measured |
| New verification query | `V15_schema_remediation_slice.sql` |
| Undo | `ops/flyway/rollback/undo_phase10b.sql` — rehearsed, refusing, reversible |

**One deviation from ADR-0011 §1's literal text, which needs a ruling.** See §3.

---

## 2. The five migrations

| Version | File | Defect | What it does |
| --- | --- | --- | --- |
| `20261006100000` | `ai_approval_decision_biconditional_repair` | D1 | report → drop the wrong `CHECK` → **repair from `ai_approval_decisions`** → refuse if unrepairable → add the corrected one |
| `20261006110000` | `document_chunks_document_tenant_fkey` | D2 | measure 0 orphans and 0 cross-tenant chunks → add `documents_id_organizationId_key` → add the composite FK |
| `20261006120000` | `api_keys_and_service_principals` | D3 | 2 enums, 2 tables, 2 functions, 3 triggers |
| `20261006130000` | `legal_holds_scope_pair_widening` | D4 | measure 0 violating rows → replace the pair `CHECK` with one that has an arm per enum member |
| `20261006140000` | `privacy_deletion_subject_reference` | D5 | add `subjectCustomerId` → relax `subjectUserId` to nullable → composite FK + index + subject `CHECK` |

SHA-256 (identical in `ops/flyway/sql/` and
`backend/database/src/main/resources/db/migration/`):

| File | SHA-256 |
| --- | --- |
| `V20261006100000__ai_approval_decision_biconditional_repair.sql` | `16d06f3bbb6299ec6db62a4d3b54da566e608aed9ca3525fcae4df527ca46f87` |
| `V20261006110000__document_chunks_document_tenant_fkey.sql` | `413a0e61140287cac472a40a59110d58afa6098054057aded91405821752fb38` |
| `V20261006120000__api_keys_and_service_principals.sql` | `e5a270aec83c987f712de5091e53e6103a34fbcfabb769d17096f26b657aacc5` |
| `V20261006130000__legal_holds_scope_pair_widening.sql` | `394706107d656bafd1c4cf7a9e99ce900fdca5913e63955a37da9768e09f9e23` |
| `V20261006140000__privacy_deletion_subject_reference.sql` | `cf4fc8eb1c2c839d578fb8ce0bdce29afb7f73d2f79f6ac6f8fc00a9aebcd4eb` |

The **seventeen** Phase-2 … Phase-7–10a digests recorded in the runbook are
**unchanged**. `V20260814000000`, `V20261005050000` and `V20261005100000` are read
byte-for-byte as the runbook already recorded them.

---

## 3. D1, and the one deviation that needs a ruling

ADR-0011 §1 states the required form as:

```sql
CHECK (("status" IN ('APPROVED','REJECTED')) = ("decidedAt" IS NOT NULL))
```

**Shipped:** one additional enum literal.

```sql
CHECK (("status" IN ('APPROVED','REJECTED','REVOKED')) = ("decidedAt" IS NOT NULL))
```

The addition is forced rather than chosen, and the argument is short:

* ADR-0011 §1 bullet 3 says a REVOKED row "cannot keep the grant's `decidedAt`, so
  revocation is unrepresentable".
* ADR-0011 §1 **rule 4** says both the rejection **and the revocation** path in
  Phase 10 must be restored after this migration lands.
* `ai_approvals_decision_pair_check` is a biconditional
  (`("decidedAt" IS NULL) = ("decidedByUserId" IS NULL)`), so keeping **who granted
  it** requires keeping `decidedAt` too. There is no arrangement in which a REVOKED
  row names the approver and leaves `decidedAt` NULL.
* Therefore the literal two-literal form still refuses a REVOKED row that keeps its
  decision, and rule 4 remains unimplementable.

`'EXPIRED'` is deliberately **not** added.
`AiTraceRepository.markApprovalExpired()` moves a row `PENDING → EXPIRED` with the
pair cleared, and its javadoc says why: *"The actor is SYSTEM on the decisions row,
because nobody decided this — it simply ran out."* Requiring a decider on an
EXPIRED row would invent one.

So the final rule is exactly: **PENDING and EXPIRED carry no decision record;
APPROVED, REJECTED and REVOKED carry one.** Since `AiApprovalStatus` has exactly
those five members, the check is equivalent to
`("status" = 'PENDING') = ("decidedAt" IS NULL)`. It is written with the IN list so
a reviewer can read it against ADR-0011's text directly.

### The rule got stricter, which is why the repair step exists

Under the old expression a `REJECTED` row with **no** decision pair was accepted.
Under the new one it is refused. On any database that received a rejection through
the pre-batch schema, that row must be repaired.

**Repair source:** `ai_approval_decisions`, append-only and triggered-free precisely
so it can be trusted as the record of what happened. For a `REJECTED` row the
`PENDING→REJECTED` decision; for a `REVOKED` row the `PENDING→APPROVED` decision
that granted it. Both halves of the pair are copied together, only rows with
`decidedAt IS NULL` are touched (so a rerun is a no-op), and `DISTINCT ON` picks the
latest matching decision.

**Counts, measured:**

| Measurement | Value |
| --- | --- |
| `ai_approvals` rows the corrected rule would refuse, on a freshly migrated database | **0** |
| Rows repaired | **0** (nothing to repair — the table has never been written outside tests) |
| Rows unrepairable | **0** |

The migration **refuses** rather than deletes if that changes on a real cutover
database: it never deletes an approval, never fabricates a decider, and never
relaxes another constraint to make the count reach zero. The refusal message names
the ids and the remediation, and the operator decision is recorded in
`ops/flyway/rollback/README-phase10b.md` §6.

---

## 4. D2 — the composite chunk/document tenant key

```sql
ALTER TABLE ONLY "public"."document_chunks"
    ADD CONSTRAINT "document_chunks_document_tenant_fkey"
    FOREIGN KEY ("documentId", "organizationId")
    REFERENCES "public"."documents"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;
```

Preceded by `CREATE UNIQUE INDEX IF NOT EXISTS documents_id_organizationId_key` — a
**UNIQUE INDEX**, not a UNIQUE CONSTRAINT, so `V4`'s `unique_constraints = 0`
invariant survives. Measured before the constraint was attempted:

| Measurement | Value |
| --- | --- |
| Chunks whose `documentId` has no `documents` row | **0** |
| Chunks whose `organizationId` differs from their document's | **0** |
| Rows repaired or deleted | **0** — a non-zero count would `RAISE` and the migration would stop |

The pre-existing single-column `document_chunks_documentId_fkey` is **kept**. It is
redundant once the composite constraint exists, but dropping an existing constraint
is not this batch's job and its `ON DELETE CASCADE` is the deletion-propagation
behaviour Phase 10 asserts. Leaving it costs one extra index probe on a delete path
and buys a chain in which nothing existing was weakened.

---

## 5. D3 — `api_keys` and `service_principals`

FR-6 requires the admin dashboard to answer for *"users, active/inactive state,
roles, project memberships, sessions, API keys, service principals, and effective
permissions"*. Phase 7 reported both tables absent
(`docs/migration/phases/phase-07.md`, "Schema gaps", item 2): service principals
were answered from the `PrincipalType × Capability` matrix plus an audit-stream
inference, and API keys had **no representation at all**, so credential *presence*
was reported as a database-computed boolean.

### `service_principals` — a non-human principal, tenant-bound, capability-bound

`id` / `organizationId NOT NULL` / `projectId` (nullable — one connector identity
per tenant, and per-project copies are how credentials drift apart) /
`principalKey` / `displayName` / `status` / `capabilities` (a JSON array of
non-empty strings, checked by the **existing** `firstelement_json_list_is_string_array`
so the shape rule has one implementation) / `createdByUserId` /
`suspendedAt, suspendedByUserId, suspensionReason` / `expiresAt` / `lastUsedAt` /
`version` / `createdAt` / `updatedAt`.

The suspension triple is a **biconditional**: a principal that is not ACTIVE says
who turned it off, when, and why, and an ACTIVE principal carries no suspension
evidence — so "reactivated" is a state that can be read rather than inferred.

**No credential column of any kind.** A principal that could itself authenticate
would be a second way to hold a secret.

### `api_keys` — a hash, never the secret, plus its own lifecycle evidence

`servicePrincipalId` (composite tenant FK) / `name` / `keyPrefix` / `keyHash` /
`hashAlgorithm` / `scopes` / `status` / `expiresAt` / `lastUsedAt` +
`lastUsedIpAddress` / `revokedAt, revokedByUserId, revokeReason` / `expiredAt` /
`version`.

Three **independent** controls make "must not be able to store a plaintext secret" a
schema property rather than a promise:

1. A guard `DO` block scanning `pg_attribute` that **refuses the migration** if any
   column on either table is named like a secret carrier
   (`secret|plaintext|password|passphrase|credential|rawkey|privatekey`). So a later
   "let me also store the raw key for debugging" cannot land unnoticed. It runs both
   before the trigger and again in the post-condition.
2. `firstelement_api_key_hash_guard()` — a `BEFORE INSERT OR UPDATE` trigger that
   refuses a `keyHash` shorter than 32 characters or carrying no `$` algorithm
   delimiter, i.e. a value shaped like a **raw key** rather than a KDF digest
   (argon2id, bcrypt and scrypt digests all contain `$`). This is the deny-by-default
   control FR-6 asks for. It never logs or echoes the value.
3. `CHECK api_keys_key_prefix_length_check` (`length("keyPrefix") <= 64`) plus
   `api_keys_hash_not_prefix_check`, so a prefix cannot *be* the secret.

Plus the fixture asserts the **column census is empty** — the guarantee is not only
about `keyHash`, it is that no *other* column could hold one either.

### Audit, enforced by the database

`firstelement_principal_lifecycle_audit()` is an `AFTER INSERT OR UPDATE` trigger on
both tables that writes `admin_audit_events` itself, in the same transaction as the
lifecycle change. A code path that mints or revokes a key without auditing cannot
exist, because the audit row is written by the statement that performs the change.

It fires on: a **create**, a **status change**, a change to `api_keys."scopes"`, and
a change to `service_principals."capabilities"`. It deliberately does **not** fire on
`lastUsedAt` bookkeeping — a key used a thousand times an hour must not write a
thousand append-only retention-classed audit rows an hour. The digest is never in
the metadata; `keyPrefix` is a lookup handle and is safe to record.

`V15` §5d asserts this **with data**: every `api_keys` row must have its creation
audit event, and a freshly migrated database with zero keys reports that rather than
counting as a pass on the trigger.

---

## 6. D4 — every `LegalHoldScopeType` member is placeable

```sql
CHECK (("scopeType" = 'TENANT')  AND "scopeRef" IS NULL
    OR ("scopeType" = 'PROJECT') AND "projectId" IS NOT NULL
    OR ("scopeType" IN ('TABLE','RECORD'))
       AND "scopeRef" IS NOT NULL AND length(btrim("scopeRef")) > 0);
```

The `PROJECT` arm is kept **verbatim** — a PROJECT hold that names no project holds
nothing at all, which is the specific failure the original author wrote the
constraint to refuse. The new arm delegates to the same "scopeRef must be a
non-blank string" rule `legal_holds_scope_ref_check` already states, so the two
constraints cannot disagree.

**This change is purely a widening, which is why no repair is needed:** every arm of
the new expression is implied by an arm of the old one, and nothing could ever
satisfy the old expression unless it satisfied `TENANT` or `PROJECT`. So the repair
count is necessarily zero — and Step 1 **measures** it rather than asserting it,
because "necessarily zero" is a claim about algebra and the number in the catalog is
the evidence. Measured: **0**.

---

## 7. D5 — a CUSTOMER deletion is expressible

`subjectType` **defaults to** `'CUSTOMER'` and the table's only way to name a
subject was a `users` row with a composite FK. A customer is a `customers` row and
not a `users` row, so the commonest FR-7 request could not be recorded — Phase 7
worked around it by refusing before the insert.

Added: `subjectCustomerId uuid` (nullable), a composite tenant FK to
`customers("id","organizationId")` with `ON DELETE RESTRICT`, a tenant-led index,
and `privacy_deletion_requests_subject_reference_check`.

**What the `users` FK actually is — checked, not assumed:**
`privacy_deletion_requests_subject_tenant_fkey` on
`("subjectUserId", "organizationId")`, `ON UPDATE CASCADE ON DELETE RESTRICT`. It is
**not** dropped. Relaxing the NOT NULL leaves it exactly as it was, so "if a user id
is named it is a user of THIS tenant" is unchanged, and `RESTRICT` means a user row
cannot be deleted out from under an open request.

A **project** subject reference was considered and **not** added: `projectId` is
already on the row and already FKs `projects`, and `subjectType`'s vocabulary
contains no `PROJECT` member — a project is the scope of a request, never its
subject.

### The LEGACY arm, and why it is still there

The new check has **three** arms for `CUSTOMER`:

```sql
OR (("subjectType" = 'CUSTOMER')
        AND (("subjectCustomerId" IS NOT NULL AND "subjectUserId" IS NULL)   -- new, correct
             OR ("subjectCustomerId" IS NULL AND "subjectUserId" IS NOT NULL)))  -- LEGACY
```

Before this batch a CUSTOMER deletion was recorded by naming a `users` row. Every
such row on a cutover database must keep satisfying a CHECK, and this migration must
not fail on it, delete it, or silently change what it says. The LEGACY arm is a real
weakening, it is labelled as one, it is **measured** on every run of the bundle
(`V15` §7b), and the follow-up that removes it is written down rather than implied
(F-3, §10).

**Measured:** `CUSTOMER rows relying on the LEGACY arm = 0` on a freshly migrated
database.

The fixture asserts the legacy shape is **still admissible** and says why: if it were
refused here, Phase 7's `PrivacyDeletionService`, which writes exactly that shape,
would start failing the moment this migration lands.

### The privacy state machine is untouched

`REQUESTED` / `VERIFIED` / `IN_PROGRESS` / `COMPLETED` / `REJECTED` / `CANCELLED`, the
verification pair, `STARTED demands verification`, the completion and cancellation
pairs, the rejection-reason rule and the evidence rule are all unchanged. The
migration's post-condition and `V15` §7 both **re-assert** each of them against every
row, because the temptation when widening a subject check is to relax the whole
CHECK set, and the whole CHECK set is the privacy state machine.

---

## 8. Rehearsal evidence, per defect

Per ADR-0009 §5 every row is an assertion on the **state after** the action, and the
fixture rows are behavioural (real INSERTs and real refusals against a real
`pgvector/pgvector:pg16`), not catalog reads.

| Defect | Evidence |
| --- | --- |
| **D1** | `rejectedAndRevokedApprovalsCarryTheirDecision` — a REJECTED approval **with** a decider and a timestamp is inserted and the decider is read back; `decidedAt` survives an `APPROVED → REVOKED` transition to the millisecond; REJECTED-may-not-lose-its-record and a-decided-row-may-not-be-relabelled-PENDING are both refused; EXPIRED-may-not-keep-a-decision is refused; self-approval is still refused **with a positive control** of the identical statement and two different users. Rehearsal STEP 4c: the same row is insertable and the undo then **REFUSES** on it. |
| **D2** | `chunksCannotBeScopedToAnotherTenantsDocument` — a chunk whose tenant **agrees** is accepted (positive control), then tenant B's chunk on tenant A's document is **refused** by `document_chunks_document_tenant_fkey`, and again in the reverse direction; afterwards 0 chunks disagree; the constraint is proven **composite**; the single-column FK is proven still present. |
| **D3** | `apiKeysStoreHashesAndAuditTheirOwnLifecycle` — a key is inserted; a plaintext `keyHash` is **refused by the database**; the secret-named-column census is **empty**; the guard trigger is proven present and **enabled**; `keyHash` = `keyPrefix` is refused; an 80-character "prefix" is refused; the creation audit row exists and its metadata has **no** `keyHash`; the revocation records who and writes an audit row carrying `fromStatus=ACTIVE`/`toStatus=REVOKED`; REVOKED-without-evidence is refused on a *second, still-active* key; EXPIRED-without-`expiredAt` is refused; a key bound to **another tenant's** principal is refused. |
| **D4** | `narrowLegalHoldsArePlaceableAndTheOldRefusalsHold` — a `RECORD`-scoped hold **and** a `TABLE`-scoped hold are both inserted and read back; PROJECT-with-no-project and TENANT-with-a-scopeRef are **still refused**; RECORD-with-no-scopeRef is refused; the installed definition is read back and contains all four members. Rehearsal STEP 4a: the same row is insertable and the undo then **REFUSES** on it. |
| **D5** | `customerPrivacyDeletionsNeedNoUserId` — a `CUSTOMER`-subject request is inserted with `subjectUserId` **NULL**; the column is proven nullable; both the pre-existing composite users FK and the new composite customer FK are proven present, the latter with `confdeltype = 'r'`; the request is moved `REQUESTED → VERIFIED` and still names who verified it; a rejection without a reason is **still refused**; both-references-set is refused; a TENANT subject naming neither is asserted **legal** (FR-7 needs whole-tenant deletion); a cross-tenant customer is refused by the FK. Rehearsal STEP 4b: the same row is insertable and the undo then **REFUSES** on it. |

---

## 9. What the rehearsal found, and the fixture's role

`.\ops\flyway\verification\rehearse-phase10b.ps1 -ThrowOnViolation` — **33 state
assertions, all PASS**, six throwaway databases, all six dropped. Full row-by-row
results are in `ops/flyway/rollback/README-phase10b.md` §4.

**Four real defects were found this way, none of them visible from reading the SQL:**

1. **The audit trigger's actor read named columns the firing table does not have.**
   `coalesce(NEW."revokedByUserId", NEW."suspendedByUserId", NEW."createdByUserId")`
   raised `record "new" has no field "revokedByUserId"` on `service_principals`. A
   plpgsql record field that does not exist on the firing table is a **runtime**
   error, not a NULL — so key creation failed on the first INSERT.
2. **`AND` does not protect a record field reference.** The branch chain guarded each
   table's column with `TG_TABLE_NAME = '…' AND NEW."col" …`. The planner may
   evaluate either operand of `AND` in either order, so the guard did not stop the
   field from being resolved and **every** `api_keys` `UPDATE` whose status did not
   change died with `record "new" has no field "capabilities"`. Fixed by nesting the
   `IF`s.
3. **The undo's partial-batch guard fired on a whole batch**
   (`present IS NULL OR absent IS NULL`, and `string_agg` returns NULL over an empty
   set). Harmless — nothing was dropped — but a refusal for the wrong reason is a
   failed rehearsal.
4. **Three `pg_get_constraintdef` patterns were written from the DDL's own text, and
   one omitted the space after `=`.** The old-form detection could never fire; the
   undo reported `PHASE10B_UNDO_INCOMPLETE` on a database it had undone correctly.
   Each pattern is now written from the exact rendering **and proven to
   discriminate** against the known pre- and post-batch definitions.

`FlywayBaselineAcceptanceTest` found four more of its own while the scenarios were
written — including a `jsonb ? 'keyHash'` whose `?` JDBC parses as a parameter
placeholder, and two assertions that were *backwards* (that clearing a revoked key's
evidence is illegal, when it is in fact a legal transition). Both are corrected and
both now assert the truth with the reasoning inline, because an assertion that
expects the wrong outcome is worse than no assertion.

---

## 10. Follow-ups, reported and not shipped

| Id | What | Why not shipped here |
| --- | --- | --- |
| **F-1** | A `TENANT`-scoped legal hold may still name a `projectId`. | A genuine contradiction, but tightening it is a **different** change with its own refuse-first argument. Not D4. |
| **F-2** | `document_chunks."projectId"` need not match `documents."projectId"` — a chunk in tenant A can point at a document in tenant A's **other** project. Same shape as D2, one column wider. | ADR-0011 §2 asks specifically for the `(organizationId, id)` form. A three-column constraint would make the guard's refusal condition stricter than the ruling that authorises it, and would refuse for a reason nobody asked for. Exact statements: `FOREIGN KEY ("organizationId","projectId","documentId") REFERENCES documents("organizationId","projectId","id")` plus a matching unique index and the same refuse-first guard. `V15` §8 reports the count on every bundle run. |
| **F-3** | Remove the LEGACY arm of `privacy_deletion_requests_subject_reference_check`. | Precondition: `V15` §7b reports `legacy_customer_requests = 0` on a **disposable copy**. Measured **0** today. |
| **F-4** | `NOT NULL` on the `ai_run_*` tenant-scope columns. | ADR-0009 §2 rules 1 and 3 still govern. `V14` reports `missing_organizationId=0` and `missing_projectId=0` on all three tables today, so the **recommendation** is to add it in a **separate** migration with a rehearsed rollback, after `BackfillReport.complete()` is true on a disposable copy. **Not shipped here**, and `V20261005110000` is untouched. |
| **F-5 (CLOSED 2026-10-06)** | Restore Phase 10's rejection and revocation paths. | **Orchestrator-owned, and now unblocked.** `AiApprovalService.reject()` and `.revoke()` currently write `"decidedAt" = null` "because `ai_approvals_approval_check` forces it" (lines 264-267 and 303). **CLOSED (2026-10-06).** That constraint no longer forces it. Per ADR-0011 §1 rule 4 the `ai-rag` paths were restored **after** this batch; `ai-rag` was not touched by this phase. `reject()` now writes the real decider, `revoke()` leaves the grant's decision intact and records only the revocation, and `ApprovalLifecycleIntegrationTest` asserts the stronger truth (223 tests, 0 skipped). Both regressions were injected and caught. |

---

## 11. Commands run

```powershell
# the fixture (the iterate-with loop)
.\gradlew.bat :backend:database:test --rerun-tasks --no-build-cache --no-daemon --console=plain
# -> BUILD SUCCESSFUL; 23 tests completed, 0 failed, 0 skipped

# the whole rehearsal, on throwaway databases, then dropped
.\ops\flyway\verification\rehearse-phase10b.ps1 -ThrowOnViolation
# -> All 33 state assertions PASS

# the chain on a throwaway + the evidence bundle against the measured numbers
.\ops\flyway\verification\rehearsal-chain.ps1 -Container firstelement-postgres `
    -Database firstelement_p10b_check -ExpectedEnumCount 57 `
    -ExpectedTableCount 97 -ExpectedCheckCount 421 `
    -EvidenceDir C:\...\evidence\10b -ThrowOnViolation
.\ops\flyway\verification\run-verification.ps1 -Database firstelement_p10b_check `
    -ExpectedEnumCount 57 -ExpectedTableCount 97 -ExpectedCheckCount 421 `
    -OutputDir C:\...\evidence\10b-v15 -ThrowOnViolation
# -> fingerprint 3a62e8c30d6f0043ca86a96becb2f7ba
#    enumCount 57 asserted, tableCount 97 asserted, checkCount 421 asserted
```

**Not run:** the full `./gradlew build` (the orchestrator's gate), `db push`,
`prisma migrate deploy`, any `.env` read, and any write to `firstelement`.

---

## 12. Measured numbers

| | Post-Phase-7–10a | Post-Phase-10b | Δ |
| --- | --- | --- | --- |
| tables | 95 | **97** | +2 |
| enum types | 55 | **57** | +2 |
| foreign keys | 211 | **222** | +11 |
| primary keys | 95 | **97** | +2 |
| CHECK constraints | 402 | **421** | +19 |
| unique constraints | 0 | **0** | 0 |
| indexes | 379 | **392** | +13 |
| unique indexes | 195 | **203** | +8 |
| triggers | 8 | **11** | +3 |
| index census | 200 / 15 / 22 / 136 / 5 | **207 / 15 / 22 / 142 / 5** | +7 / +6 |
| tables without `organizationId` | 3 | **3** | 0 |
| nullable `projectId` columns | 35 | **37** | +2 |
| fingerprint | `2ee0ecd6…` | **`3a62e8c30d6f0043ca86a96becb2f7ba`** | — |

The CHECK delta is **+19, not +21**: two of the five migrations **replace** a CHECK
rather than adding one (`ai_approvals_approval_check` and
`legal_holds_scope_pair_check`, because PostgreSQL cannot edit a `CHECK`
expression). D3's two new tables contribute 18 (6 + 12) and D5 contributes 1.

All values are **measured** off `firstelement_p10b_chain` after the last migration
succeeded, not derived from arithmetic, and are recorded in
`ops/flyway/expected-state.json` `postBoot`, which is what the quality gate reads.
---

## 13. V15 correction — the pre-boot run this slice never had

**`V15_schema_remediation_slice.sql` was added in a state that a pre-boot run never
exercised, and it is worth saying that plainly rather than fixing quietly.** Every
verification of it during Phase 10b was run against a post-boot throwaway: the
fixture, `rehearsal-chain.ps1`, and `run-verification.ps1` with
`-ExpectedEnumCount 57 -ExpectedTableCount 97 -ExpectedCheckCount 421`. The file's
header *claimed* "Guarded: on any database that has not applied V20261006100000 each
section reports PHASE10B_NOT_PRESENT", and that claim was never tested. It was
false.

The quality gate is what found it, on the profile the gate runs every day:

```
INFO  [verify-schema-state] firstelement flyway_stamped=False
INFO  [verify-schema-state] using profile 'preBoot'
Verification query V15_schema_remediation_slice.sql failed
```

The failure was roughly fifty statements after the presence check:

```
psql:<stdin>:74: ERROR:  relation "public.ai_approvals" does not exist
LINE 1:  WHERE conrelid = 'public.ai_approvals'::regclass
```

So the section reported `PHASE10B_NOT_PRESENT` correctly and then failed anyway,
because its own header described a guard that only one statement had.

### Why the guard it needed is a *parse-time* problem

`conrelid = 'public.ai_approvals'::regclass` resolves the relation at parse/prepare
time, before any row of the statement is evaluated, so a `WHERE to_regclass(...) IS
NULL` test **in the same statement** cannot rescue it. That is the same lesson V14
§3a records from the post-undo rehearsal, and the same shape V12 §5c/§6 already
solved with a `DO` block.

The precise mechanism, measured on this host rather than assumed — a three-case
probe against the pre-boot `firstelement`:

| Case | Result |
| --- | --- |
| `DO` block, `to_regclass(...) IS NULL -> RETURN`, then plain `SELECT ... FROM public.ai_approvals` | **skipped, no error** |
| same, with the relation held in a variable from `to_regclass` | **skipped, no error** |
| `DO` block with **no** guard, same `SELECT` | `ERROR: relation "public.ai_approvals" does not exist` |

plpgsql prepares a statement lazily, on first execution, so an early `RETURN` means
the statements after it are never prepared and their relation references never
resolve. That is why a `DO` guard is sufficient for a plpgsql body and why
**dynamic SQL was not needed** — `EXECUTE` is used only where a per-row census has
to come out of a guarded body, which is V14 §3a's own arrangement.

Top-level statements cannot be guarded at all: psql sends each one and the server
parses it on arrival. Every statement that named a Phase-10b or Phase-7-10a
relation at top level was therefore **moved inside a `DO` block**.

### What each section is now guarded on, and why that artifact

A guard that never fires is not a guard, and a guard that tests the thing it is
verifying is circular. Each section tests a **catalog-only** predicate for an
artifact its own migration created:

| Section | Guard | Why that one |
| --- | --- | --- |
| D1 (§3) | `to_regclass('public.api_keys') IS NULL` | **no artifact of its own** — `V20261006100000` only *replaces* a constraint, and `ai_approvals_approval_check` exists before the batch too. Testing its definition would be circular, so it defers to the batch marker and the message distinguishes `NOT_PRESENT` from `PARTIAL`. |
| D2 (§4) | `documents_id_organizationId_key` is absent | the unique thing `V20261006110000` creates, absent on every pre-batch database. Guarding on `document_chunks` would be useless — it is a **baseline** table, present on the pre-boot database too — and guarding on the constraint would be circular. |
| D3 (§5c-5d) | `to_regclass('public.api_keys') IS NULL` | the table **is** what the section verifies. |
| D4 (§6) | `to_regclass('public.api_keys') IS NULL` | **no artifact of its own** — `V20261006130000` also only replaces a constraint. See §3. |
| D5 (§7) | `information_schema.columns` has no `subjectCustomerId` | the one thing `V20261006140000` adds, catalog-only so the guard itself cannot fail. Guarding on `privacy_deletion_requests` would be useless — it is a **Phase-7-10a** table — and guarding on the constraint would be circular. |
| §8a residual | none needed | reads only baseline tables `document_chunks` / `documents`. Stays a real result set; a count of 0 pre-boot is the truth. |
| §8b, §9 | `to_regclass('public.api_keys') IS NULL` | they name Phase-10b and Phase-7-10a relations. |

§1 presence, §2 enum lists, §5a column shape, §5b trigger inventory and §8a remain
**real top-level result sets** on every database: they read only
`pg_class` / `pg_attribute` / `pg_trigger` / `pg_type`, and their zero rows on a
pre-boot database are themselves evidence that the objects do not exist.

### What this cost, stated rather than glossed

Four **reports** stopped being result sets and became `NOTICE` lines inside their
guarded block: the D1 per-status census, the D2 constraint listing, the D4 scope
census and the D5 per-subjectType census. `run-verification.ps1` captures stderr,
so they still land in the evidence bundle's `.txt`. They are **not** result sets any
more, and a consumer parsing the bundle for a table-shaped D1 census now has to read
NOTICEs.

**No assertion was weakened.** Every one is still a `RAISE EXCEPTION` that aborts
the bundle, and every one still reads state — installed definitions from
`pg_constraint`, validity and uniqueness from `pg_index`, nullability from
`pg_attribute`, the secret census from `pg_attribute`, and the rest from the data.
Nothing was deleted to make the file parse.

---

## 14. What the negative controls found — and the one that was not cosmetic

Six negative controls, one fresh throwaway each, each breaking exactly one thing on
a fully migrated database, plus one positive control on an intact one. All eight
result sets below are psql **exit codes and the assertion that fired**, not messages.

| Control | Broken | Exit | Assertion that fired |
| --- | --- | --- | --- |
| positive | nothing (intact 22-migration chain) | **0** | none — PASS, and the full census printed |
| NEG-1a | D1 restored to the old single-literal `CHECK` | **3** | `ai_approvals_approval_check is "…status = 'APPROVED'…" which does not admit REJECTED and REVOKED` |
| NEG-2 | `document_chunks_document_tenant_fkey` dropped | **3** | `D2: document_chunks_document_tenant_fkey is absent` |
| NEG-3 | `api_keys_hash_guard` disabled | **3** | `D3: the api_keys plaintext-secret guard is missing or disabled (0 found)` |
| NEG-4 | D4 restored to the old two-arm `CHECK` | **3** | `D4: the installed rule has no TABLE/RECORD arm … a narrow hold is UNPLACEABLE` |
| NEG-5 | `subjectUserId` re-declared `NOT NULL` | **3** | `D5: "subjectUserId" is still NOT NULL` |
| NEG-6 | `api_keys_lifecycle_audit` disabled | **3** | `D3: 1 of the 2 credential lifecycle audit triggers are present or enabled` |

### NEG-4 found a real tautology, and it is now fixed

**The first NEG-4 run exited 0.** The D4 totality assertion evaluated a *copy of the
rule written out inside the verification file*, in `probe` CTEs. A predicate that can
only ever agree with itself cannot report that the thing it is checking has drifted
— and the whole job of §6 is to report exactly that. So restoring the pre-batch
two-arm constraint, which makes a narrow hold unplaceable, went **undetected** by the
slice whose purpose is to detect it.

This is the same class of defect ADR-0009 §5 calls a rehearsal that passes for the
wrong reason, and it had been carried over from `V20261006130000`, whose own
post-condition makes the same re-derived evaluation (that one is documented there as
deliberately *not* a substitute for a real insert — but a verification slice has no
such excuse).

**Fix:** the totality claim is now made against `pg_get_constraintdef(oid)` — the
rule PostgreSQL actually enforces — with each arm's fragments asserted
individually:

* a `TENANT` arm demanding `"scopeRef" IS NULL`;
* a `PROJECT` arm demanding `"projectId" IS NOT NULL`;
* a `TABLE`/`RECORD` arm demanding `"scopeRef" IS NOT NULL`.

The `probe` CTEs are retained but relabelled as a readable summary of what each arm
admits, not as the evidence for it. NEG-4 now exits **3** with the installed
definition quoted in the message, and the positive control still exits 0.

The other five sections were audited for the same defect and are **clean**: D1, D2
and D5 all compare fragments of the *installed* `pg_get_constraintdef`, D3 reads
`pg_trigger` / `pg_attribute` and the data, and §8 counts rows. D1 additionally
carries the "the old-form detection pattern must itself match the known pre-batch
rendering" check, which is a self-test of the detector.

### One D1 assertion is unreachable, and that is by design

Four of D1's five assertions — "REJECTED hides its decider", "REVOKED erased its
decision record", "status disagrees with the decision" and "half a decision pair" —
are **defence in depth against a bypassed repair**. While
`ai_approvals_approval_check` and `ai_approvals_decision_pair_check` are installed,
no `INSERT` or `UPDATE` can produce the state they detect, so a negative control
cannot be built for them: dropping the corrected constraint to create the row also
fails the definition assertion first. They are recorded as unreachable rather than
claimed as tested. The **definition** assertion — the one that matters, and the one
NEG-1a exercises — fires correctly.

---

## 15. Both profiles verified

| Profile | Database | Result |
| --- | --- | --- |
| **preBoot** | `firstelement` (dev, unstamped, 45 tables) | gate **PASSED**; `V15` prints `PHASE10B_NOT_PRESENT`; every section reports NOT_PRESENT; psql **exit 0**; fingerprint `66eeaadab4ad28a8f8eaf8fe1a0748f9` |
| **postBoot** | throwaway, 22 migrations | gate **PASSED**; `V15` prints `PHASE10B_PRESENT` and its full census; every assertion holds; psql **exit 0**; tables **97**, enums **57**, CHECKs **421**, all three **asserted**; fingerprint `3a62e8c30d6f0043ca86a96becb2f7ba` |

`verify-schema-state.ps1` was exercised in **both** directions: it selected
`preBoot` on the unstamped dev database and `postBoot` on a stamped one, which is
the profile-selection path the gate will actually take after cutover.

### A finding about the recorded fingerprint, reported and not "fixed"

`V1_schema_fingerprint.sql` includes `flyway_schema_history` in its `column` and
`constraint` arms — unlike `V4` / `V5` / `V11`, which all exclude it. So a
fingerprint measured on a database where the history table is *absent* (every psql
rehearsal copy, including the one the `3a62e8c3…` value was measured on) will **not**
match the same database once Flyway has created that table. Verified: adding a
simulated `flyway_schema_history` moved the fingerprint to `43a412265b428a107…`;
dropping it returned `3a62e8c30d6f0043ca86a96becb2f7ba` exactly.

This is **pre-existing** — the Phase-7–10a value `2ee0ecd6…` has the same property —
and it is **not** a gate failure: neither `run-verification.ps1` nor
`verify-schema-state.ps1` compares the fingerprint against an expected value; it is
printed and recorded in the summary only, and the assertions are the enum, table and
CHECK counts. Changing V1 to exclude the history table would re-baseline every
fingerprint recorded across all phases, so it is **not** done here. Recorded as a
follow-up.

### Commands

```powershell
# preBoot profile -- the gate's requirement, against the dev database
.\ops\quality\checks\verify-schema-state.ps1 -Container firstelement-postgres `
    -Database firstelement -OutputDir C:\...\evidence\10b-v15-preboot -ThrowOnViolation
# -> verification bundle PASSED under profile 'preBoot'

# postBoot profile -- full chain on a throwaway, then the bundle with the measured numbers
.\ops\flyway\verification\rehearsal-chain.ps1 -Container firstelement-postgres `
    -Database firstelement_p10b_v15_check -Keep
.\ops\flyway\verification\run-verification.ps1 -Database firstelement_p10b_v15_check `
    -ExpectedEnumCount 57 -ExpectedTableCount 97 -ExpectedCheckCount 421 `
    -OutputDir C:\...\evidence\10b-v15-postboot -ThrowOnViolation
# -> fingerprint 3a62e8c30d6f0043ca86a96becb2f7ba; all three counts asserted

# and V15 on its own, both sides
Get-Content -Raw ops\flyway\verification\V15_schema_remediation_slice.sql |
    docker exec -i firstelement-postgres psql -U firstelement -d firstelement -v ON_ERROR_STOP=1 -f -
# -> PHASE10B_NOT_PRESENT, exit 0
```

Eight throwaway databases were created and **all eight were dropped**;
`firstelement` is still 45 tables, 0 `flyway_schema_history` rows, 4 users, 3
conversations, 41 sessions. The five migrations are byte-identical to the SHA-256
values this phase recorded, in both copies.
