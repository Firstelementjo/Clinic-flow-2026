-- ADR 0020: the platform operator is a separate principal above the organization.
--
-- WHY THESE FOUR TABLES EXIST SEPARATELY
--
-- ADR 0020 requires that the platform console have "no shared authentication path
-- with the customer application". A flag on `users` would have been cheaper and
-- would have made that impossible: a customer session and an operator session would
-- have been the same row type, the same table, the same cookie, and one mis-scoped
-- filter would have turned an organization administrator into a cross-tenant
-- principal. Nothing about the boundary would have failed loudly.
--
-- So this is deliberately new tables, not new columns. `TenantContext` already
-- refuses a null `organizationId`, which means a platform operator cannot be
-- represented as one at all; these tables keep that a compile-time fact rather than
-- a runtime convention.
--
-- NOT-NULL organizationId IS DELIBERATELY ABSENT ON ALL FOUR
--
-- FR-1.2 requires a non-null tenant key on every tenant-owned row, and
-- `FlywayBaselineAcceptanceTest` asserts it. These tables are the documented
-- exception, and the exception runs in the opposite direction from the four
-- existing ones: those are tenant-scoped and reach outward, these are not
-- tenant-scoped at all. An operator session belongs to nobody's tenant; it spans
-- every tenant. Forcing an organizationId here would be a lie that quietly narrows
-- the principal to one customer.
--
-- `platform_audit_events."organizationId"` and `."projectId"` are nullable for the
-- same reason: one audit row records one action, and a platform action may touch no
-- particular tenant (list every clinic) or exactly one (open clinic X). The
-- `platform_audit_events_scope_present_or_scoped_reason` check below makes the null
-- case explicit rather than accidental.
--
-- EVERY IDENTIFIER IS SCHEMA-QUALIFIED, AND THAT IS LOAD-BEARING
--
-- This chain runs under `search_path = ''`. An unqualified `create table` therefore
-- fails with "no schema has been selected to create in", which is exactly the
-- failure recorded at V20261009130000 for `to_regtype` and `ALTER TYPE`. Both the
-- qualification and the `to_regtype` probe style are deliberate, not decoration:
-- under an empty search_path an unqualified name resolves against nothing and the
-- statement either errors or silently tests the wrong thing.
--
-- Column names are quoted camelCase, matching every other table in this schema
-- (ADR 0002). The existing baseline is not snake_case and this migration does not
-- introduce a second convention into the same database.

-- ------------------------------------------------------------------
-- 1. the operators
-- ------------------------------------------------------------------
create table "public"."platform_operators" (
    "id"               uuid primary key,
    "email"            text not null,
    "displayName"      text not null,
    -- bcrypt cost 12, produced by the shared PasswordVerifier so the operator
    -- population and the customer population share one hashing implementation and
    -- one failure-timing behaviour.
    "passwordHash"     text not null,
    -- The TOTP shared secret, encrypted at rest with the deployment's
    -- ENCRYPTION_KEY. Never projected by any read model, never logged, and never
    -- returned by any endpoint.
    "totpSecretEnc"    text not null,
    -- Set once the operator has enrolled a device. Until then login cannot complete:
    -- an operator with no second factor is refused rather than allowed to proceed on
    -- a password alone.
    "totpEnrolledAt"   timestamp(3),
    "isActive"         boolean not null default true,
    "lastSeenAt"       timestamp(3),
    "createdAt"        timestamp(3) not null default (current_timestamp at time zone 'utc'),
    "updatedAt"        timestamp(3) not null default (current_timestamp at time zone 'utc'),
    constraint platform_operators_email_not_blank check (length(btrim("email")) > 3),
    constraint platform_operators_display_name_not_blank check (length(btrim("displayName")) > 0),
    -- An operator with no enrolled second factor cannot authenticate. Enforced here
    -- as well as in the service, so a row inserted directly cannot create an
    -- account that bypasses the MFA decision.
    constraint platform_operators_mfa_enrolled check ("totpEnrolledAt" is not null)
);

comment on table "public"."platform_operators" is
    'FirstElement platform operators. Not customer users: ADR 0020 keeps this '
    'population on a separate authentication path from users.';

-- Case-insensitive uniqueness has to be an expression index, not a table constraint:
-- `unique (lower(col))` is not valid there. Emails are compared case-insensitively so
-- `Ops@x.com` and `ops@x.com` cannot become two operators.
create unique index platform_operators_email_lower_key
    on "public"."platform_operators" (lower("email"));

-- ------------------------------------------------------------------
-- 2. what an operator may do
-- ------------------------------------------------------------------
-- A join table rather than a column, because an operator's reach must be
-- explainable: "why can this account read every clinic" needs a row to point at,
-- not an opaque comma-separated string. The capability is a stable token, not a free
-- value, so a typo cannot create an unknown grant.
create table "public"."platform_operator_capabilities" (
    "operatorId"       uuid not null references "public"."platform_operators" ("id"),
    "capability"       text not null,
    "grantedAt"        timestamp(3) not null default (current_timestamp at time zone 'utc'),
    constraint platform_operator_capabilities_pk primary key ("operatorId", "capability"),
    constraint platform_operator_capabilities_known check ("capability" in (
        'READ_PLATFORM_OVERVIEW', 'CREATE_CLINIC', 'CREATE_ACCOUNT', 'GRANT_ACCESS',
        'READ_PLATFORM_AUDIT', 'OPERATE_PLATFORM', 'REQUEST_DB_ACTION'))
);

comment on table "public"."platform_operator_capabilities" is
    'Granted platform capabilities. There is deliberately no SQL_EXECUTE, SHELL or '
    'READ_SECRET value: ADR 0020 forbids them and an absent constant cannot be granted '
    'by a mistake.';

-- ------------------------------------------------------------------
-- 3. their sessions
-- ------------------------------------------------------------------
-- Separate from sessions for the same reason platform_operators is separate from
-- users. Two independent tables mean a customer session cookie and a platform session
-- cookie cannot be confused, cannot share a lookup, and cannot be accepted by the
-- other's filter.
create table "public"."platform_sessions" (
    "id"                 uuid primary key,
    "operatorId"         uuid not null references "public"."platform_operators" ("id"),
    -- Only the hash is persisted. The raw cookie value exists once, in the response
    -- that sets it, exactly as for a customer session.
    "tokenHash"          text not null,
    -- MFA is a two-step handshake. A session created by the password step alone is
    -- recorded here but carries no authority until "mfaSatisfiedAt" is set, and a
    -- session that has not satisfied MFA cannot be elevated.
    "mfaSatisfiedAt"     timestamp(3),
    -- Just-in-time elevation (ADR 0020). Elevated sessions are short-lived and
    -- non-renewable: the operator re-authorises with a fresh second factor rather
    -- than holding a standing super-admin token.
    "elevatedAt"         timestamp(3),
    "elevatedExpiresAt"  timestamp(3),
    -- Why the elevated window was granted. Recorded on the session so the audit
    -- trail can explain an elevated action after the fact without joining rows.
    "elevationReason"    text,
    "userAgent"          text,
    "ipAddress"          text,
    "expiresAt"          timestamp(3) not null,
    "revokedAt"          timestamp(3),
    "createdAt"          timestamp(3) not null default (current_timestamp at time zone 'utc'),
    "lastSeenAt"         timestamp(3),
    constraint platform_sessions_elevation_consistent check (
        ("elevatedAt" is null     and "elevatedExpiresAt" is null     and "elevationReason" is null)
     or ("elevatedAt" is not null and "elevatedExpiresAt" is not null and "elevationReason" is not null)
    ),
    constraint platform_sessions_elevated_requires_mfa check (
        "elevatedAt" is null or "mfaSatisfiedAt" is not null
    ),
    constraint platform_sessions_reason_not_blank check (
        "elevationReason" is null or length(btrim("elevationReason")) > 0
    )
);

-- Uniqueness is carried by a unique INDEX, never by a UNIQUE CONSTRAINT. This is the project's V4
-- invariant, asserted by FlywayBaselineAcceptanceTest: "uniqueness is carried by unique INDEXES,
-- never by a UNIQUE CONSTRAINT -- 0 in the baseline and it must stay 0". A token hash must be unique
-- (it is the session lookup), so it gets a unique index like everything else in this schema.
create unique index platform_sessions_token_hash_key
    on "public"."platform_sessions" ("tokenHash");

create index platform_sessions_operator_active_idx
    on "public"."platform_sessions" ("operatorId", "expiresAt")
    where "revokedAt" is null;

comment on column "public"."platform_sessions"."elevatedExpiresAt" is
    'An elevated session stops working here even though the session itself has not '
    'expired. Elevation is a window, not a mode.';

-- ------------------------------------------------------------------
-- 4. the cross-tenant audit
-- ------------------------------------------------------------------
-- Append-only. There is deliberately no updatedAt and no delete path: this table
-- records what a principal above the organization did, which is the one class of
-- action that cannot be reconstructed from any single tenant's own records.
create table "public"."platform_audit_events" (
    "id"               uuid primary key,
    "operatorId"       uuid references "public"."platform_operators" ("id"),
    "sessionId"        uuid references "public"."platform_sessions" ("id"),
    -- A stable token: PLATFORM_LOGIN, PLATFORM_LOGIN_FAILED, PLATFORM_LOGIN_MFA_FAILED,
    -- PLATFORM_ELEVATED, PROJECT_CREATED, ACCOUNT_CREATED, ACCESS_GRANTED,
    -- DB_ACTION_REQUESTED ...
    "action"           text not null,
    "resourceType"     text,
    "resourceId"       text,
    -- Null when the action was not scoped to one tenant. See the header note.
    "organizationId"   uuid,
    "projectId"        uuid,
    -- What the operator was trying to achieve. Mandatory for every write action.
    "reason"           text,
    -- Redacted, size-bounded detail. Never carries a secret, a credential, or
    -- patient content.
    "detail"           text,
    "correlationId"    text,
    "ipAddress"        text,
    "outcome"          text not null,
    "occurredAt"       timestamp(3) not null default (current_timestamp at time zone 'utc'),
    constraint platform_audit_events_action_not_blank check (length(btrim("action")) > 0),
    constraint platform_audit_events_outcome_known check (
        "outcome" in ('ALLOWED', 'DENIED', 'FAILED')
    ),
    -- A row naming neither a tenant nor a project must explain itself. This is what
    -- stops "no organizationId" from quietly becoming the default for a bug.
    constraint platform_audit_events_scope_present_or_scoped_reason check (
        "organizationId" is not null
     or "projectId"      is not null
     or ("reason" is not null and length(btrim("reason")) > 0)
    )
);

create index platform_audit_events_operator_idx
    on "public"."platform_audit_events" ("operatorId", "occurredAt" desc);
create index platform_audit_events_organization_idx
    on "public"."platform_audit_events" ("organizationId", "occurredAt" desc)
    where "organizationId" is not null;
create index platform_audit_events_action_idx
    on "public"."platform_audit_events" ("action", "occurredAt" desc);

-- Append-only in the database, not only in convention. The same guard the finance
-- ledger uses: a privileged principal must not be able to rewrite the record of what
-- a privileged principal did.
create or replace function "public"."platform_audit_events_append_only"()
returns trigger as $$
begin
    raise exception 'platform_audit_events is append-only';
end;
$$ language plpgsql;

create trigger platform_audit_events_append_only_trigger
    before update or delete on "public"."platform_audit_events"
    for each row execute function "public"."platform_audit_events_append_only"();

comment on table "public"."platform_audit_events" is
    'Append-only record of every action taken by a platform operator, including '
    'denials. Never updated, never deleted.';

-- ------------------------------------------------------------------
-- 5. keeping updatedAt honest
-- ------------------------------------------------------------------
create or replace function "public"."platform_touch_updated_at"()
returns trigger as $$
begin
    new."updatedAt" := current_timestamp at time zone 'utc';
    return new;
end;
$$ language plpgsql;

create trigger platform_operators_touch_trigger
    before update on "public"."platform_operators"
    for each row execute function "public"."platform_touch_updated_at"();