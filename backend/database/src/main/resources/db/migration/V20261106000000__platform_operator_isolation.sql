-- =====================================================================
-- V20261106000000__platform_operator_isolation.sql
--
-- Platform Operator Role Isolation:
--   1. users."is_platform_admin" (default false)
--   2. Ensure index on is_platform_admin
-- =====================================================================

ALTER TABLE "public"."users"
    ADD COLUMN IF NOT EXISTS "is_platform_admin" boolean NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS "users_is_platform_admin_idx"
    ON "public"."users" ("is_platform_admin");
