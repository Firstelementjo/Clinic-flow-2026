-- =====================================================================
-- V20261107000000__platform_tenant_lifecycle_and_subscriptions.sql
--
-- Platform Administration Tenant Lifecycle, Plans, Subscriptions,
-- Append-Only Payments Ledger, Single-Use Invite Tokens, Support Access
-- =====================================================================

-- ------------------------------------------------------------------
-- 1. Alter projects (Clinics)
-- ------------------------------------------------------------------
ALTER TABLE "public"."projects"
    ADD COLUMN IF NOT EXISTS "version" integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS "city" text,
    ADD COLUMN IF NOT EXISTS "contactNumber" text,
    ADD COLUMN IF NOT EXISTS "status" text NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS "suspendedAt" timestamp(3) with time zone,
    ADD COLUMN IF NOT EXISTS "suspendedReason" text;

CREATE INDEX IF NOT EXISTS "projects_status_idx"
    ON "public"."projects" ("status");

-- ------------------------------------------------------------------
-- 2. Alter users (Clinic Admins & Staff)
-- ------------------------------------------------------------------
ALTER TABLE "public"."users"
    ADD COLUMN IF NOT EXISTS "totpSecretEnc" text,
    ADD COLUMN IF NOT EXISTS "mfaEnrolledAt" timestamp(3) with time zone,
    ADD COLUMN IF NOT EXISTS "invitePending" boolean NOT NULL DEFAULT false;

-- ------------------------------------------------------------------
-- 3. Plans
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "public"."plans" (
    "id"               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    "code"             text NOT NULL UNIQUE,
    "name"             text NOT NULL,
    "description"      text,
    "priceAmount"      numeric(12,3) NOT NULL DEFAULT 0.000,
    "currency"         text NOT NULL DEFAULT 'JOD',
    "billingInterval"  text NOT NULL DEFAULT 'MONTHLY',
    "maxUsers"         integer NOT NULL DEFAULT 5,
    "maxBranches"      integer NOT NULL DEFAULT 1,
    "storageQuotaMb"   integer NOT NULL DEFAULT 5120,
    "monthlyAiQuota"   integer NOT NULL DEFAULT 500,
    "isActive"         boolean NOT NULL DEFAULT true,
    "version"          integer NOT NULL DEFAULT 0,
    "createdAt"        timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc'),
    "updatedAt"        timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc')
);

CREATE TABLE IF NOT EXISTS "public"."plan_entitlements" (
    "id"               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    "planId"           uuid NOT NULL REFERENCES "public"."plans"("id") ON DELETE CASCADE,
    "featureKey"       text NOT NULL,
    "enabled"          boolean NOT NULL DEFAULT true,
    "createdAt"        timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc'),
    CONSTRAINT "plan_entitlements_plan_feature_key" UNIQUE ("planId", "featureKey")
);

-- Seed default plans
INSERT INTO "public"."plans"
    ("id", "code", "name", "description", "priceAmount", "currency", "billingInterval", "maxUsers", "maxBranches", "storageQuotaMb", "monthlyAiQuota", "isActive")
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'STARTER', 'Starter Plan', 'Essential clinic practice management', 50.000, 'JOD', 'MONTHLY', 3, 1, 2048, 100, true),
    ('a0000000-0000-0000-0000-000000000002', 'GROWTH', 'Growth Plan', 'Complete dental clinic operations and AI features', 120.000, 'JOD', 'MONTHLY', 10, 3, 10240, 1000, true),
    ('a0000000-0000-0000-0000-000000000003', 'ENTERPRISE', 'Enterprise Plan', 'Multi-branch dental centers with high-volume AI & messaging', 250.000, 'JOD', 'MONTHLY', 50, 10, 51200, 5000, true)
ON CONFLICT ("code") DO NOTHING;

INSERT INTO "public"."plan_entitlements" ("planId", "featureKey", "enabled")
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'CLINIC_AI', false),
    ('a0000000-0000-0000-0000-000000000001', 'CHANNEL_WHATSAPP', false),
    ('a0000000-0000-0000-0000-000000000002', 'CLINIC_AI', true),
    ('a0000000-0000-0000-0000-000000000002', 'CHANNEL_WHATSAPP', true),
    ('a0000000-0000-0000-0000-000000000003', 'CLINIC_AI', true),
    ('a0000000-0000-0000-0000-000000000003', 'CHANNEL_WHATSAPP', true),
    ('a0000000-0000-0000-0000-000000000003', 'CHANNEL_TELEGRAM', true),
    ('a0000000-0000-0000-0000-000000000003', 'CHANNEL_INSTAGRAM', true)
ON CONFLICT ON CONSTRAINT "plan_entitlements_plan_feature_key" DO NOTHING;

-- ------------------------------------------------------------------
-- 4. Clinic Subscriptions
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "public"."clinic_subscriptions" (
    "id"                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"           uuid NOT NULL REFERENCES "public"."organizations"("id") ON DELETE CASCADE,
    "projectId"                uuid NOT NULL REFERENCES "public"."projects"("id") ON DELETE CASCADE,
    "planId"                   uuid NOT NULL REFERENCES "public"."plans"("id") ON DELETE RESTRICT,
    "status"                   text NOT NULL DEFAULT 'TRIAL',
    "trialEndsAt"              timestamp(3) with time zone,
    "currentPeriodStartsAt"    timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc'),
    "paidUntil"                timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc' + interval '14 days'),
    "gracePeriodDays"          integer NOT NULL DEFAULT 7,
    "maxUsersOverride"         integer,
    "maxBranchesOverride"      integer,
    "storageQuotaMbOverride"   integer,
    "monthlyAiQuotaOverride"   integer,
    "version"                  integer NOT NULL DEFAULT 0,
    "createdAt"                timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc'),
    "updatedAt"                timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc'),
    CONSTRAINT "clinic_subscriptions_project_id_key" UNIQUE ("projectId")
);

CREATE INDEX IF NOT EXISTS "clinic_subscriptions_org_idx"
    ON "public"."clinic_subscriptions" ("organizationId");
CREATE INDEX IF NOT EXISTS "clinic_subscriptions_status_idx"
    ON "public"."clinic_subscriptions" ("status");
CREATE INDEX IF NOT EXISTS "clinic_subscriptions_paid_until_idx"
    ON "public"."clinic_subscriptions" ("paidUntil");

-- ------------------------------------------------------------------
-- 5. Append-Only Subscription Payments Ledger
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "public"."subscription_payments" (
    "id"                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    "subscriptionId"       uuid NOT NULL REFERENCES "public"."clinic_subscriptions"("id") ON DELETE RESTRICT,
    "projectId"            uuid NOT NULL REFERENCES "public"."projects"("id") ON DELETE RESTRICT,
    "organizationId"       uuid NOT NULL REFERENCES "public"."organizations"("id") ON DELETE CASCADE,
    "idempotencyKey"       text NOT NULL UNIQUE,
    "amount"               numeric(12,3) NOT NULL,
    "currency"             text NOT NULL DEFAULT 'JOD',
    "paymentMethod"        text NOT NULL,
    "referenceNumber"      text,
    "periodStart"          timestamp(3) with time zone NOT NULL,
    "periodEnd"            timestamp(3) with time zone NOT NULL,
    "recordedByOperatorId" uuid NOT NULL,
    "notes"                text,
    "isReversal"           boolean NOT NULL DEFAULT false,
    "reversalOfPaymentId"  uuid REFERENCES "public"."subscription_payments"("id") ON DELETE RESTRICT,
    "reversalReason"       text,
    "createdAt"            timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc')
);

CREATE INDEX IF NOT EXISTS "subscription_payments_sub_idx"
    ON "public"."subscription_payments" ("subscriptionId", "createdAt");
CREATE INDEX IF NOT EXISTS "subscription_payments_project_idx"
    ON "public"."subscription_payments" ("projectId", "createdAt");

-- ------------------------------------------------------------------
-- 6. Invite Tokens (Single-use, expiring, hashed)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "public"."invite_tokens" (
    "id"                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId"       uuid NOT NULL REFERENCES "public"."organizations"("id") ON DELETE CASCADE,
    "projectId"            uuid NOT NULL REFERENCES "public"."projects"("id") ON DELETE CASCADE,
    "userId"               uuid NOT NULL REFERENCES "public"."users"("id") ON DELETE CASCADE,
    "email"                text NOT NULL,
    "tokenHash"            text NOT NULL UNIQUE,
    "expiresAt"            timestamp(3) with time zone NOT NULL,
    "usedAt"               timestamp(3) with time zone,
    "createdByOperatorId"  uuid,
    "createdAt"            timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc')
);

CREATE INDEX IF NOT EXISTS "invite_tokens_hash_idx"
    ON "public"."invite_tokens" ("tokenHash");
CREATE INDEX IF NOT EXISTS "invite_tokens_user_idx"
    ON "public"."invite_tokens" ("userId");

-- ------------------------------------------------------------------
-- 7. Support Access Grants (Break-glass)
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "public"."support_access_grants" (
    "id"             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    "organizationId" uuid NOT NULL REFERENCES "public"."organizations"("id") ON DELETE CASCADE,
    "projectId"      uuid NOT NULL REFERENCES "public"."projects"("id") ON DELETE CASCADE,
    "operatorId"     uuid NOT NULL,
    "reason"         text NOT NULL,
    "isReadOnly"     boolean NOT NULL DEFAULT true,
    "status"         text NOT NULL DEFAULT 'PENDING',
    "startsAt"       timestamp(3) with time zone,
    "expiresAt"      timestamp(3) with time zone NOT NULL,
    "revokedAt"      timestamp(3) with time zone,
    "createdAt"      timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc')
);

CREATE INDEX IF NOT EXISTS "support_access_grants_proj_idx"
    ON "public"."support_access_grants" ("projectId", "status");

-- ------------------------------------------------------------------
-- 8. Backfill subscriptions for existing projects
-- ------------------------------------------------------------------
INSERT INTO "public"."clinic_subscriptions"
    ("id", "organizationId", "projectId", "planId", "status", "trialEndsAt", "currentPeriodStartsAt", "paidUntil", "gracePeriodDays")
SELECT
    gen_random_uuid(),
    p."organizationId",
    p."id",
    'a0000000-0000-0000-0000-000000000002',
    'ACTIVE',
    NULL,
    p."createdAt",
    (current_timestamp at time zone 'utc' + interval '365 days'),
    7
FROM "public"."projects" p
WHERE NOT EXISTS (
    SELECT 1 FROM "public"."clinic_subscriptions" cs WHERE cs."projectId" = p."id"
);
