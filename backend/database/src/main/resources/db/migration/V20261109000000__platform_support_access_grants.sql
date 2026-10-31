-- V20261109000000__platform_support_access_grants.sql
-- Platform Support Access (Break-Glass) additions
-- Adds revocation tracking, admin notification timestamp, and lookup indexes.

ALTER TABLE "public"."support_access_grants"
    ADD COLUMN IF NOT EXISTS "revokedByOperatorId" uuid REFERENCES "public"."platform_operators"("id") ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS "revocationReason" text,
    ADD COLUMN IF NOT EXISTS "notifiedClinicAdminAt" timestamp(3) with time zone;

CREATE INDEX IF NOT EXISTS "support_access_grants_active_lookup_idx"
    ON "public"."support_access_grants" ("operatorId", "projectId", "status", "expiresAt");

CREATE INDEX IF NOT EXISTS "support_access_grants_created_idx"
    ON "public"."support_access_grants" ("createdAt" DESC);
