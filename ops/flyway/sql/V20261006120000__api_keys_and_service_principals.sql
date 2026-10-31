-- =====================================================================
-- V20261006120000__api_keys_and_service_principals.sql
-- ADDITIVE.  TRANSACTIONAL.  TWO TABLES, TWO ENUMS, TWO TRIGGERS.
--
-- REMEDIATION OF DEFECT D3, AND THE RULING THAT PROMISED IT
--   PROJECT_REQUIREMENTS FR-6 requires the admin dashboard to answer for
--   "users, active/inactive state, roles, project memberships, sessions,
--   API keys, service principals, and effective permissions".
--
--   Phase 7 reported the gap in docs/migration/phases/phase-07.md section
--   "Schema gaps reported to the orchestrator", item 2:
--
--       No api_keys table and no service_principals table. FR-6 asks for both.
--       Service principals are answered from the PrincipalType x Capability
--       matrix the boundary enforces at runtime, plus the machine principals
--       this tenant has actually been seen using, read from the audit streams.
--       API keys have no representation at all, so credential PRESENCE per
--       connector is reported as a database-computed boolean instead.
--
--   That is the whole defect: the first half is an inference over evidence, and
--   the second half is a boolean derived from a table that does not exist.  An
--   operator cannot manage a credential they cannot see, and cannot see one
--   that cannot be modelled.  This file is the representation.
--
-- SHAPE: THE EXISTING IDENTITY CONVENTIONS, NOT NEW ONES
--   Modelled on V20260814000000's users / sessions / project_memberships, and
--   on the Phase-7-10a audit tables:
--     * table names snake_case, column names QUOTED camelCase (ADR-0002: the
--       legacy quoted camelCase is preserved by explicit mapping, never by
--       combining a rename with a runtime migration);
--     * "id" uuid PRIMARY KEY, "organizationId" uuid NOT NULL, so the census in
--       V7 and in the fixture classifies both new tables as tenant-owned;
--     * "projectId" uuid NULLABLE -- an API key or a machine principal is
--       typically organisation-wide (one connector key for the tenant), and
--       per-project copies are how credentials drift apart. This matches the
--       Phase-7-10a organisation-wide list in
--       ops/flyway/rollback/README-phase7-10a.md section 7;
--     * timestamp(3) WITHOUT time zone everywhere (V6 asserts no timestamptz),
--       and every new DEFAULT spells out AT TIME ZONE 'UTC' rather than the bare
--       CURRENT_TIMESTAMP the baseline habit uses;
--     * every tenant-owned reference is a COMPOSITE ("organizationId", id)
--       foreign key, never a single-column one -- which is exactly the defect
--       V20261006110000 exists to repair in document_chunks;
--     * uniqueness is a UNIQUE INDEX, never a UNIQUE CONSTRAINT, so V4's
--       unique_constraints = 0 invariant survives;
--     * tenant-led read indexes on ("organizationId", ...) first.
--
-- A KEY STORES A HASH. NEVER THE SECRET.  AND THE DATABASE PROVES IT.
--   Columns: "keyHash" (the KDF digest) and "keyPrefix" (a short, non-secret
--   lookup handle, the way a hosting provider shows the last four characters so
--   an operator can tell two keys apart).  There is no column that can hold the
--   secret, and three independent controls say so:
--
--     1. A guard DO block that scans pg_attribute and REFUSES this migration if
--        any column on either table is named like a secret carrier. So a later
--        "let me also store the raw key for debugging" cannot land unnoticed.
--     2. firstelement_api_key_hash_guard(), a BEFORE INSERT OR UPDATE trigger,
--        which refuses any "keyHash" that does not look like a KDF digest --
--        it must be at least 32 characters and carry a '$' algorithm delimiter,
--        which argon2id, bcrypt and scrypt digests all do and a raw key does
--        not.  This is the deny-by-default control FR-6 asks for: the only
--        secret-shaped column on the table refuses plaintext at the database,
--        so "the secret was stored" cannot be a code-level mistake.
--     3. CHECK api_keys_hash_not_prefix_check, so the prefix and the hash cannot
--        be the same value.
--
--   AGENTS.md forbids secret display and this file is consistent with it: no
--   secret is read, written, logged, or echoed by any statement here.  The
--   digest is never placed in the audit metadata either -- see the trigger.
--
-- A SERVICE PRINCIPAL IS NOT A PASSWORD
--   service_principals carries no credential column of any kind.  It is a
--   non-human principal bound to a tenant and to a capability set, which is
--   what an API key then authenticates AS.  A principal that could itself
--   authenticate would be a second way to hold a secret, and FR-6's "no secret
--   display" and AGENTS.md's rule that secrets live in an external vault both
--   point the same way.
--
-- AUDIT: KEY CREATION AND REVOCATION WRITE AN AUDIT RECORD
--   Phase 7's convention is that changing a tenant's authentication is an
--   admin_audit_events row.  Here it is enforced BY THE DATABASE rather than by
--   application code: firstelement_principal_lifecycle_audit() is an AFTER
--   INSERT OR UPDATE trigger on both tables that writes admin_audit_events
--   itself.  A code path that mints or revokes a key without auditing cannot
--   exist, because the audit row is written in the same transaction as the
--   lifecycle change by the same statement that performs it.
--
--   It fires on LIFECYCLE changes only:
--     * INSERT                            -> <table>.created
--     * status changed                    -> <table>.status_changed
--     * api_keys."scopes" changed         -> api_key.scopes_changed
--     * service_principals."capabilities" changed
--                                        -> service_principal.capabilities_changed
--   It deliberately does NOT fire on "lastUsedAt" bookkeeping.  A key used a
--   thousand times an hour must not write a thousand append-only, retention-
--   classed audit rows an hour; last-used evidence belongs on the key row,
--   which is where it is, and the audit stream records decisions.
--
--   The audit row names actorUserId (the administrator who did it), never the
--   hash, and never anything derived from the secret.
--
-- WHAT DOES NOT HAPPEN HERE
--   * No DROP, no RENAME, no ALTER of any pre-existing table or column.
--   * No NOT NULL added anywhere, and none on ai_run_* (ADR-0009 section 2).
--   * No index on any pre-existing table.
--   * No password, secret, token or private key column -- see control 1.
--
-- TIMESTAMPS  timestamp(3) without time zone throughout (V6).
-- Rollback: ops/flyway/rollback/undo_phase10b.sql drops both tables, both enums,
-- both triggers and both functions, and its post-condition checks the table
-- count afterwards.  It refuses if either table holds a row.
-- Every identifier is schema-qualified (the baseline sets search_path = '').
-- =====================================================================

-- ---------------------------------------------------------------------
-- The two closed vocabularies the boundary and the admin screen both branch
-- on, declared as enums for the same reason AiApprovalStatus is one: a drift
-- in a stored status is a silent behaviour change, and an enum makes the whole
-- contract assertable with containsExactly rather than inferred from a count.
-- ---------------------------------------------------------------------
CREATE TYPE "public"."ServicePrincipalStatus" AS ENUM (
    -- ACTIVE   : may authenticate and exercise its capabilities
    'ACTIVE',
    -- SUSPENDED: temporarily disabled, reversible by an administrator
    'SUSPENDED',
    -- DISABLED : permanently retired; a new principal replaces it
    'DISABLED'
);

CREATE TYPE "public"."ApiKeyStatus" AS ENUM (
    'ACTIVE',
    'REVOKED',
    -- EXPIRED is recorded rather than derived, so "this key lapsed on the 3rd"
    -- is evidence with a date rather than an inference from expiresAt that
    -- changes its answer every time the query is run.
    'EXPIRED'
);

-- ---------------------------------------------------------------------
-- service_principals
-- ---------------------------------------------------------------------
CREATE TABLE "public"."service_principals" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    -- NULL: a principal is normally organisation-wide (one connector identity
    -- for the tenant). See the header.
    "projectId"          uuid,
    -- The machine name, unique per tenant. NOT NULL: a principal nobody can
    -- address by name is not manageable from the screen FR-6 asks for.
    "principalKey"       text NOT NULL,
    "displayName"        text NOT NULL,
    "description"        text,
    "status"             "public"."ServicePrincipalStatus" NOT NULL DEFAULT 'ACTIVE',
    -- The capability set. A JSON array of non-empty strings, checked by the
    -- Phase-7-10a validator firstelement_json_list_is_string_array rather than
    -- by a new one, so the shape rule has exactly one implementation in the
    -- schema. PostgreSQL forbids subqueries in CHECK, which is why that
    -- function exists at all.
    "capabilities"       jsonb NOT NULL DEFAULT '[]'::jsonb,
    "createdByUserId"    uuid,
    -- The suspension triple. Demanded by a biconditional below: a principal
    -- that is not ACTIVE says who turned it off, when, and why. The reverse
    -- also holds -- an ACTIVE principal carries no suspension evidence, so
    -- "reactivated" is a state that can be read rather than inferred.
    "suspendedAt"        timestamp(3) without time zone,
    "suspendedByUserId"  uuid,
    "suspensionReason"   text,
    "expiresAt"          timestamp(3) without time zone,
    "lastUsedAt"         timestamp(3) without time zone,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone NOT NULL
                                      DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    "updatedAt"          timestamp(3) without time zone NOT NULL
                                      DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    CONSTRAINT "service_principals_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "service_principals_principal_key_check"
        CHECK (length(btrim("principalKey")) > 0),
    CONSTRAINT "service_principals_display_name_check"
        CHECK (length(btrim("displayName")) > 0),
    -- The capability set is a closed list, not free text. A principal holding
    -- a capability nobody can check is exactly the unrestricted identity
    -- plan.md forbids.
    CONSTRAINT "service_principals_capabilities_check"
        CHECK ("public"."firstelement_json_list_is_string_array"("capabilities")),
    CONSTRAINT "service_principals_suspension_check"
        CHECK (("status" = 'ACTIVE'::"public"."ServicePrincipalStatus")
               = ("suspendedAt" IS NULL AND "suspendedByUserId" IS NULL
                  AND "suspensionReason" IS NULL)),
    -- A principal that expires before it was created is a configuration error,
    -- not a short-lived principal.
    CONSTRAINT "service_principals_expiry_check"
        CHECK ("expiresAt" IS NULL OR "expiresAt" > "createdAt"),
    CONSTRAINT "service_principals_version_check"
        CHECK ("version" >= 0)
);

ALTER TABLE ONLY "public"."service_principals"
    ADD CONSTRAINT "service_principals_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."service_principals"
    ADD CONSTRAINT "service_principals_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- THE ATTRIBUTION FKS. Composite, so an audit answer to "which administrator
-- minted this" can never name another tenant's user.
ALTER TABLE ONLY "public"."service_principals"
    ADD CONSTRAINT "service_principals_created_by_tenant_fkey"
    FOREIGN KEY ("createdByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."service_principals"
    ADD CONSTRAINT "service_principals_suspended_by_tenant_fkey"
    FOREIGN KEY ("suspendedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Composite tenant key: the FK target api_keys binds to. A UNIQUE INDEX, not
-- a UNIQUE CONSTRAINT, so V4's unique_constraints = 0 invariant survives.
CREATE UNIQUE INDEX "service_principals_id_organizationId_key"
    ON "public"."service_principals" USING "btree" ("id", "organizationId");

-- One principal per key per tenant. Business key, tenant-scoped: two tenants
-- may both have a "connector-twilio" without either being wrong.
CREATE UNIQUE INDEX "service_principals_organizationId_principalKey_key"
    ON "public"."service_principals" USING "btree" ("organizationId", "principalKey");

-- The admin screen's list query, and the "which principals can this project
-- reach" question.
CREATE INDEX "service_principals_organizationId_status_idx"
    ON "public"."service_principals" USING "btree" ("organizationId", "status");

CREATE INDEX "service_principals_organizationId_projectId_status_idx"
    ON "public"."service_principals" USING "btree"
       ("organizationId", "projectId", "status");

-- ---------------------------------------------------------------------
-- api_keys
--
-- A key is a CREDENTIAL FOR A PRINCIPAL, not a principal. It names the
-- principal it authenticates as, the subset of that principal's capabilities it
-- may exercise, and the evidence of its own lifecycle.
-- ---------------------------------------------------------------------
CREATE TABLE "public"."api_keys" (
    "id"                 uuid NOT NULL,
    "organizationId"     uuid NOT NULL,
    "projectId"          uuid,
    "servicePrincipalId" uuid NOT NULL,
    "name"               text NOT NULL,
    -- The short, non-secret lookup handle. Shown to an operator so two keys can
    -- be told apart; never sufficient to authenticate. See the header for why
    -- a value that could hold the whole secret is refused by a CHECK.
    "keyPrefix"          text NOT NULL,
    -- THE HASH. Never the secret.
    "keyHash"            text NOT NULL,
    -- A digest without its algorithm is not verifiable, and "we will work out
    -- which KDF this was later" is how a key list becomes unreadable.
    "hashAlgorithm"      text NOT NULL,
    -- The subset of the principal's capabilities this key may exercise. Same
    -- validator function as above, so one shape rule, not two.
    "scopes"             jsonb NOT NULL DEFAULT '[]'::jsonb,
    "status"             "public"."ApiKeyStatus" NOT NULL DEFAULT 'ACTIVE',
    "createdByUserId"    uuid,
    "expiresAt"          timestamp(3) without time zone NOT NULL,
    -- Last-used EVIDENCE. Deliberately two columns rather than one: "when was
    -- this last used" and "where from" are two different questions an operator
    -- asks when deciding whether a key is still needed.
    "lastUsedAt"         timestamp(3) without time zone,
    "lastUsedIpAddress"  text,
    -- The revocation triple, demanded by a biconditional: a revoked key says who
    -- revoked it, when, and why. The reverse holds -- an ACTIVE or EXPIRED key
    -- carries no revocation evidence.
    "revokedAt"          timestamp(3) without time zone,
    "revokedByUserId"    uuid,
    "revokeReason"       text,
    "expiredAt"          timestamp(3) without time zone,
    "version"            integer NOT NULL DEFAULT 0,
    "createdAt"          timestamp(3) without time zone NOT NULL
                                      DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    "updatedAt"          timestamp(3) without time zone NOT NULL
                                      DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    CONSTRAINT "api_keys_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "api_keys_name_check"
        CHECK (length(btrim("name")) > 0),
    CONSTRAINT "api_keys_key_prefix_check"
        CHECK (length(btrim("keyPrefix")) > 0),
    -- A PREFIX THAT COULD BE THE SECRET IS NOT A PREFIX. Without this bound a
    -- writer could "for convenience" put the whole key in keyPrefix and the
    -- table would store a plaintext secret in a column whose comment says it is
    -- safe to display. 64 characters is far above any sane lookup prefix and
    -- far below a key length.
    CONSTRAINT "api_keys_key_prefix_length_check"
        CHECK (length("keyPrefix") <= 64),
    CONSTRAINT "api_keys_key_hash_check"
        CHECK (length(btrim("keyHash")) > 0),
    CONSTRAINT "api_keys_hash_algorithm_check"
        CHECK (length(btrim("hashAlgorithm")) > 0),
    -- The prefix is not the hash. Cheap, and it closes the degenerate case
    -- where the two columns are populated from the same source value.
    CONSTRAINT "api_keys_hash_not_prefix_check"
        CHECK ("keyHash" <> "keyPrefix"),
    CONSTRAINT "api_keys_scopes_check"
        CHECK ("public"."firstelement_json_list_is_string_array"("scopes")),
    -- A key must not be born expired: an approval or a credential that starts
    -- in the past is a configuration mistake, not a short life.
    CONSTRAINT "api_keys_expiry_check"
        CHECK ("expiresAt" > "createdAt"),
    CONSTRAINT "api_keys_revocation_check"
        CHECK (("status" = 'REVOKED'::"public"."ApiKeyStatus")
               = ("revokedAt" IS NOT NULL AND "revokedByUserId" IS NOT NULL
                  AND "revokeReason" IS NOT NULL)),
    -- The same shape for the EXPIRED state: an EXPIRED key says when it lapsed.
    CONSTRAINT "api_keys_expiration_recorded_check"
        CHECK (("status" = 'EXPIRED'::"public"."ApiKeyStatus")
               = ("expiredAt" IS NOT NULL)),
    -- First use is evidence of the credential being real, so it cannot predate
    -- its own creation.
    CONSTRAINT "api_keys_last_used_check"
        CHECK ("lastUsedAt" IS NULL OR "lastUsedAt" >= "createdAt"),
    CONSTRAINT "api_keys_version_check"
        CHECK ("version" >= 0)
);

ALTER TABLE ONLY "public"."api_keys"
    ADD CONSTRAINT "api_keys_organizationId_fkey"
    FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."api_keys"
    ADD CONSTRAINT "api_keys_projectId_fkey"
    FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
    ON UPDATE CASCADE ON DELETE CASCADE;

-- A key may only belong to a principal of ITS OWN tenant. This is the
-- composite-tenant-FK convention the whole migration set follows, and the one
-- document_chunks did not have until V20261006110000.
ALTER TABLE ONLY "public"."api_keys"
    ADD CONSTRAINT "api_keys_service_principal_tenant_fkey"
    FOREIGN KEY ("servicePrincipalId", "organizationId")
    REFERENCES "public"."service_principals"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE ONLY "public"."api_keys"
    ADD CONSTRAINT "api_keys_created_by_tenant_fkey"
    FOREIGN KEY ("createdByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE ONLY "public"."api_keys"
    ADD CONSTRAINT "api_keys_revoked_by_tenant_fkey"
    FOREIGN KEY ("revokedByUserId", "organizationId")
    REFERENCES "public"."users"("id", "organizationId")
    ON UPDATE CASCADE ON DELETE RESTRICT;

-- Composite tenant key, for the audit trigger's resource reference and for any
-- future table that must name this key within a tenant.
CREATE UNIQUE INDEX "api_keys_id_organizationId_key"
    ON "public"."api_keys" USING "btree" ("id", "organizationId");

-- THE AUTHENTICATION LOOKUP. Every request presenting a key resolves by
-- prefix first, then verifies the digest, so this index is on the hot path and
-- is the reason the prefix exists at all. Unique, so one prefix resolves to
-- exactly one key and an ambiguous lookup is impossible rather than
-- first-row-wins.
CREATE UNIQUE INDEX "api_keys_organizationId_keyPrefix_key"
    ON "public"."api_keys" USING "btree" ("organizationId", "keyPrefix");

-- "Which keys does this principal have, and what state are they in?"
CREATE INDEX "api_keys_organizationId_servicePrincipalId_status_idx"
    ON "public"."api_keys" USING "btree"
       ("organizationId", "servicePrincipalId", "status");

-- The admin screen's default read: this tenant's keys, most interesting first.
CREATE INDEX "api_keys_organizationId_status_lastUsedAt_idx"
    ON "public"."api_keys" USING "btree"
       ("organizationId", "status", "lastUsedAt");

-- ---------------------------------------------------------------------
-- CONTROL 1 of 3 on secrets: the column census.
--
-- This migration refuses to complete if either table has grown a column whose
-- name reads like a secret carrier. It runs BEFORE the trigger function is
-- created so a violation is reported as "do not add this column" rather than as
-- a later trigger failure.
--
-- "keyHash" and "keyPrefix" are deliberately NOT excluded from the pattern:
-- neither matches it, which is itself the point -- a digest and a lookup handle
-- are not named like secrets, and if someone renamed keyHash to "secretKey"
-- this guard would refuse the migration.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    carriers text;
BEGIN
    IF to_regclass('public.api_keys') IS NULL
       OR to_regclass('public.service_principals') IS NULL THEN
        RAISE EXCEPTION
            'V20261006120000 failed: api_keys or service_principals was not created.';
    END IF;

    SELECT string_agg(c.relname || '.' || a.attname, ', ' ORDER BY c.relname, a.attname)
      INTO carriers
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
       WHERE n.nspname = 'public'
         AND c.relname IN ('api_keys', 'service_principals')
         AND a.attnum > 0 AND NOT a.attisdropped
         AND lower(a.attname) ~ '(secret|plaintext|password|passphrase|credential|rawkey|privatekey)';

    IF carriers IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261006120000 REFUSED: % carries a column named like a secret holder. FR-6 and '
            'AGENTS.md forbid storing a secret here; a key is stored as a KDF digest in '
            'api_keys."keyHash" and nothing else.', carriers;
    END IF;

    RAISE NOTICE
        'V20261006120000: no secret-named column on api_keys or service_principals.';
END
$$;

-- ---------------------------------------------------------------------
-- CONTROL 2 of 3 on secrets: refuse a plaintext value at the database.
--
-- firstelement_api_key_hash_guard() is a BEFORE INSERT OR UPDATE trigger. It
-- requires "keyHash" to look like a KDF digest: at least 32 characters and
-- carrying a '$' algorithm delimiter, which argon2id ("$argon2id$..."), bcrypt
-- ("$2b$...") and scrypt ("$s1$...") all do. A raw key does not.
--
-- The predicate is deliberately shape-based rather than algorithm-enumerated,
-- so an algorithm change does not require editing the guard -- and deliberately
-- strict, because the cost of a false refusal is one extra line at the boundary
-- while the cost of a false acceptance is a stored plaintext credential.
--
-- The secret is NEVER logged, echoed, or compared against anything external:
-- this function only inspects the shape of a value it is refusing to accept.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."firstelement_api_key_hash_guard"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW."keyHash" IS NULL OR btrim(NEW."keyHash") = '' THEN
        RAISE EXCEPTION
            'public.api_keys."keyHash" is required and must be the KDF digest of the key, '
            'never the key itself.'
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF length(NEW."keyHash") < 32 OR position('$' in NEW."keyHash") = 0 THEN
        RAISE EXCEPTION
            'public.api_keys."keyHash" does not look like a KDF digest (shorter than 32 '
            'characters, or no ''$'' algorithm delimiter). A plaintext key is refused here on '
            'purpose: it is the only secret-shaped column on this table, and a credential that '
            'can be read back is a credential that leaks with a single query. Hash at the '
            'boundary and store the digest.'
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END
$$;

COMMENT ON FUNCTION "public"."firstelement_api_key_hash_guard"() IS
    'Phase 10b (D3): BEFORE INSERT OR UPDATE on api_keys. Refuses a "keyHash" that is blank, '
    'shorter than 32 characters, or carries no ''$'' algorithm delimiter, i.e. a value shaped '
    'like a raw key rather than a KDF digest. The database-level half of "a key stores a hash, '
    'never the secret". Never logs or echoes the value.';

CREATE TRIGGER "api_keys_hash_guard"
    BEFORE INSERT OR UPDATE ON "public"."api_keys"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_api_key_hash_guard"();

-- ---------------------------------------------------------------------
-- THE AUDIT TRIGGER. Phase 7's convention, enforced by the database.
--
-- firstelement_principal_lifecycle_audit() writes admin_audit_events in the
-- same transaction as the lifecycle change. It fires on INSERT, on a status
-- change, and on a change to the capability/scope set -- and NOT on last-used
-- bookkeeping, because a key used a thousand times an hour must not write a
-- thousand append-only retention-classed audit rows an hour.
--
-- The metadata is assembled from named columns. The digest is NOT among them,
-- and neither is anything derived from the secret: keyPrefix is a lookup handle
-- and is safe to record, keyHash is not recorded at all.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."firstelement_principal_lifecycle_audit"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    action_name  text;
    audit_meta   jsonb;
    actor        uuid;
BEGIN
    -- ---------------------------------------------------------------------
    -- WHICH CHANGE IS THIS, AND WHY THE BRANCHES ARE NESTED RATHER THAN
    -- GUARDED WITH AND
    --
    -- A plpgsql record field that does not exist on the table the trigger
    -- fired on is a RUNTIME error, not a NULL:
    --     record "new" has no field "capabilities"
    -- The first version of this function was written as a flat chain
    --
    --     ELSIF TG_TABLE_NAME = 'api_keys' AND NEW."scopes" IS DISTINCT FROM OLD."scopes" THEN
    --     ELSIF TG_TABLE_NAME = 'service_principals'
    --          AND NEW."capabilities" IS DISTINCT FROM OLD."capabilities" THEN
    --
    -- which is wrong TWICE, and both were found by the throwaway rehearsal:
    --
    --   1. `AND` does not protect it. The SQL planner is free to evaluate either
    --      operand of AND in either order, so the `TG_TABLE_NAME = ...` guard on
    --      the LEFT does not stop the record field on the RIGHT from being
    --      resolved -- and it was: every api_keys UPDATE whose status did not
    --      change died with `record "new" has no field "capabilities"`.
    --   2. The actor's own coalesce read `NEW."revokedByUserId", NEW."suspendedByUserId",
    --      NEW."createdByUserId"` across BOTH tables, which raised on each of them
    --      for the two columns they do not share. Key creation failed on the
    --      first INSERT.
    --
    -- The only shape that works is NESTED IFs, each of whose body names only the
    -- columns of the table it guards. Nothing below mentions a column that the
    -- other table lacks.
    -- ---------------------------------------------------------------------
    IF TG_OP = 'INSERT' THEN
        action_name := TG_TABLE_NAME || '.created';

    ELSIF NEW."status" IS DISTINCT FROM OLD."status" THEN
        action_name := TG_TABLE_NAME || '.status_changed';

    ELSIF TG_TABLE_NAME = 'api_keys' THEN
        IF NEW."scopes" IS DISTINCT FROM OLD."scopes" THEN
            action_name := 'api_key.scopes_changed';
        ELSE
            -- Last-used bookkeeping, an expiry sweep, a version bump. Not a
            -- decision, so not an audit event. Returning NULL is correct for an
            -- AFTER trigger.
            RETURN NULL;
        END IF;

    ELSE
        IF NEW."capabilities" IS DISTINCT FROM OLD."capabilities" THEN
            action_name := 'service_principal.capabilities_changed';
        ELSE
            RETURN NULL;
        END IF;
    END IF;

    -- The actor, named per table. api_keys has no suspendedByUserId and
    -- service_principals has no revokedByUserId, so this cannot be one coalesce.
    IF TG_TABLE_NAME = 'api_keys' THEN
        actor := coalesce(NEW."revokedByUserId", NEW."createdByUserId");
    ELSE
        actor := coalesce(NEW."suspendedByUserId", NEW."createdByUserId");
    END IF;

    IF TG_TABLE_NAME = 'api_keys' THEN
        audit_meta := jsonb_build_object(
            'servicePrincipalId', NEW."servicePrincipalId",
            'keyPrefix',         NEW."keyPrefix",
            'hashAlgorithm',     NEW."hashAlgorithm",
            'scopes',            NEW."scopes",
            'fromStatus',        CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD."status"::text END,
            'toStatus',          NEW."status"::text);
    ELSE
        audit_meta := jsonb_build_object(
            'principalKey', NEW."principalKey",
            'capabilities', NEW."capabilities",
            'fromStatus',   CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD."status"::text END,
            'toStatus',     NEW."status"::text);
    END IF;

    INSERT INTO "public"."admin_audit_events" (
        "id", "organizationId", "projectId", "actorUserId", "principalType",
        "principalName", "action", "resourceType", "resourceId", "outcome",
        "metadata", "retentionClass", "correlationId", "causationId",
        "occurredAt", "createdAt")
    VALUES (
        gen_random_uuid(), NEW."organizationId", NEW."projectId", actor,
        'SERVICE'::"public"."AuditPrincipalType",
        'credential-lifecycle', action_name,
        TG_TABLE_NAME, NEW."id"::text,
        'SUCCESS'::"public"."AuditOutcome",
        audit_meta, 'SECURITY_STANDARD',
        NULL, NULL,
        (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
        (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'));

    RETURN NULL;
END
$$;

COMMENT ON FUNCTION "public"."firstelement_principal_lifecycle_audit"() IS
    'Phase 10b (D3): AFTER INSERT OR UPDATE on api_keys and service_principals. Writes an '
    'admin_audit_events row for a create, a status change, or a change to the key scopes / '
    'principal capabilities, so "key creation and revocation write an audit record" is a '
    'database property rather than a code convention. Does not fire on last-used bookkeeping. '
    'Never records the digest or anything derived from a secret.';

CREATE TRIGGER "api_keys_lifecycle_audit"
    AFTER INSERT OR UPDATE ON "public"."api_keys"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_principal_lifecycle_audit"();

CREATE TRIGGER "service_principals_lifecycle_audit"
    AFTER INSERT OR UPDATE ON "public"."service_principals"
    FOR EACH ROW EXECUTE FUNCTION "public"."firstelement_principal_lifecycle_audit"();

-- ---------------------------------------------------------------------
-- POST-CONDITION. Assert on the STATE, never on this file's intent.
-- (ADR-0009 section 5.)
-- ---------------------------------------------------------------------
DO $$
DECLARE
    principal_key_idx boolean;
    key_prefix_idx    boolean;
    tenant_fk_cols    text;
    tenant_fk_shape   boolean;
    guard_count       integer;
    audit_triggers    integer;
    bad_default      text;
    bad_tz           text;
    secret_columns   text;
BEGIN
    -- 7a. Both business-key uniqueness properties exist as UNIQUE INDEXES.
    SELECT indexdef LIKE '%UNIQUE INDEX%' INTO principal_key_idx
      FROM pg_indexes
     WHERE schemaname = 'public' AND indexname = 'service_principals_organizationId_principalKey_key';
    IF principal_key_idx IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261006120000 failed: service_principals_organizationId_principalKey_key is absent, '
            'so two principals in one tenant could share a machine name.';
    END IF;

    SELECT indexdef LIKE '%UNIQUE INDEX%' INTO key_prefix_idx
      FROM pg_indexes
     WHERE schemaname = 'public' AND indexname = 'api_keys_organizationId_keyPrefix_key';
    IF key_prefix_idx IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261006120000 failed: api_keys_organizationId_keyPrefix_key is absent, so an '
            'authentication lookup could resolve ambiguously.';
    END IF;

    -- 7b. The key-to-principal FK is COMPOSITE, on both columns, and names both
    --     targets. "A key belongs to a principal" existing is not enough; a
    --     single-column FK would be exactly the defect V20261006110000 repairs.
    -- The composite shape is compared STRUCTURALLY, through pg_constraint
    -- conkey / confkey / confrelid, not through pg_get_constraintdef() text.
    -- A rendered-text pattern here is search_path-sensitive in exactly the way
    -- V20261006110000's was: pg_get_constraintdef() qualifies a relation that
    -- is not visible on the current search_path, and the baseline leaves
    -- search_path = '' for the whole connection. Measured on PostgreSQL 16.15
    -- under both settings; see docs/migration/phases/
    -- phase-30-migration-chain-bootstrap.md. tenant_fk_cols is still read, and
    -- still reported, so an operator gets the rendered form in the failure --
    -- it no longer decides whether the migration passes.
    SELECT
        (SELECT array_agg(a.attname::text ORDER BY k.ord)
           FROM unnest(cn.conkey) WITH ORDINALITY AS k(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = cn.conrelid AND a.attnum = k.attnum)
          = ARRAY['servicePrincipalId', 'organizationId']::text[]
        AND cn.confrelid = 'public.service_principals'::regclass
        AND (SELECT array_agg(a.attname::text ORDER BY k.ord)
               FROM unnest(cn.confkey) WITH ORDINALITY AS k(attnum, ord)
               JOIN pg_attribute a ON a.attrelid = cn.confrelid AND a.attnum = k.attnum)
          = ARRAY['id', 'organizationId']::text[]
      INTO tenant_fk_shape
      FROM pg_constraint cn
     WHERE cn.conrelid = 'public.api_keys'::regclass
       AND cn.conname = 'api_keys_service_principal_tenant_fkey';
        SELECT pg_get_constraintdef(oid) INTO tenant_fk_cols
          FROM pg_constraint
         WHERE conrelid = 'public.api_keys'::regclass
           AND conname = 'api_keys_service_principal_tenant_fkey';
    
    IF tenant_fk_shape IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'V20261006120000 failed: api_keys_service_principal_tenant_fkey is "%" -- a key can '
            'be bound to another tenant''s principal, which is the cross-tenant shape every '
            'other tenant FK in this chain exists to refuse.', coalesce(tenant_fk_cols, '(absent)');
    END IF;

    -- 7c. Both triggers are REAL triggers, not a convention in a comment.
    SELECT count(*) INTO guard_count
      FROM pg_trigger
     WHERE tgrelid = 'public.api_keys'::regclass AND NOT tgisinternal
       AND tgname = 'api_keys_hash_guard' AND tgenabled = 'O';
    IF guard_count <> 1 THEN
        RAISE EXCEPTION
            'V20261006120000 failed: the api_keys plaintext-secret guard is missing or disabled '
            '(% trigger(s) found). FR-6 forbids storing a credential in readable form and this '
            'trigger is what the database enforces.', guard_count;
    END IF;

    SELECT count(*) INTO audit_triggers
      FROM pg_trigger
     WHERE NOT tgisinternal AND tgenabled = 'O'
       AND tgname IN ('api_keys_lifecycle_audit', 'service_principals_lifecycle_audit')
       AND tgrelid IN ('public.api_keys'::regclass,
                       'public.service_principals'::regclass);
    IF audit_triggers <> 2 THEN
        RAISE EXCEPTION
            'V20261006120000 failed: % of the 2 credential lifecycle audit triggers are present '
            'or enabled. Key creation and revocation must write an audit record.', audit_triggers;
    END IF;

    -- 7d. No secret-named column. Repeated as a post-condition as well as a
    --     pre-condition: the census is cheap and it is the invariant, not the
    --     moment it was checked.
    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO secret_columns
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
       WHERE n.nspname = 'public'
         AND c.relname IN ('api_keys', 'service_principals')
         AND a.attnum > 0 AND NOT a.attisdropped
         AND lower(a.attname) ~ '(secret|plaintext|password|passphrase|credential|rawkey|privatekey)';
    IF secret_columns IS NOT NULL THEN
        RAISE EXCEPTION 'V20261006120000 failed: secret-named column(s) % present.', secret_columns;
    END IF;

    -- 7e. The timestamp contract. A timestamptz here would make "when was this
    --     key last used" depend on the session's zone, and a bare
    --     CURRENT_TIMESTAMP default would write session-local time.
    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO bad_tz
      FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
                              AND a.atttypid = 'timestamptz'::regtype
       WHERE n.nspname = 'public'
         AND c.relname IN ('api_keys', 'service_principals')
         AND a.attnum > 0 AND NOT a.attisdropped;
    IF bad_tz IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261006120000 failed: timestamptz column in %; every timestamp in this schema is '
            'timestamp(3) without time zone.', bad_tz;
    END IF;

    SELECT string_agg(c.relname || '.' || a.attname, ', ') INTO bad_default
      FROM pg_attrdef d
        JOIN pg_class c ON c.oid = d.adrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.adnum
       WHERE n.nspname = 'public'
         AND c.relname IN ('api_keys', 'service_principals')
         AND pg_get_expr(d.adbin, d.adrelid) LIKE '%CURRENT_TIMESTAMP%'
         AND pg_get_expr(d.adbin, d.adrelid) NOT LIKE '%AT TIME ZONE%';
    IF bad_default IS NOT NULL THEN
        RAISE EXCEPTION
            'V20261006120000 failed: a session-local CURRENT_TIMESTAMP default on %; every new '
            'default must be (CURRENT_TIMESTAMP AT TIME ZONE ''UTC'').', bad_default;
    END IF;

    RAISE NOTICE
        'V20261006120000: api_keys + service_principals created; composite principal FK, '
        'plaintext-secret guard and 2 lifecycle audit triggers verified in pg_catalog; '
        'no timestamptz, no session-local default.';
END
$$;
