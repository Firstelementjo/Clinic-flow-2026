-- =====================================================================
-- V20261108000000__platform_plan_limits_and_entitlements.sql
--
-- Platform Administration: Per-clinic feature entitlement overrides
-- =====================================================================

CREATE TABLE IF NOT EXISTS "public"."clinic_entitlement_overrides" (
    "id"         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    "projectId"  uuid NOT NULL REFERENCES "public"."projects"("id") ON DELETE CASCADE,
    "featureKey" text NOT NULL,
    "enabled"    boolean NOT NULL,
    "createdAt"  timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc'),
    "updatedAt"  timestamp(3) with time zone NOT NULL DEFAULT (current_timestamp at time zone 'utc'),
    CONSTRAINT "clinic_entitlement_overrides_proj_feature_key" UNIQUE ("projectId", "featureKey")
);

CREATE INDEX IF NOT EXISTS "clinic_entitlement_overrides_proj_idx"
    ON "public"."clinic_entitlement_overrides" ("projectId");
