-- V20261103000000: Doctor profiles and front-desk staff
--
-- Phase 39 completes the two people screens the ToothLogic clinic
-- administration needs:
--
--   * doctors - already the bookable roster row. This migration adds the
--     profile the Create Doctor drawer records (names, photo, contact,
--     expertise, commission, clinic/service assignment, address, social
--     links, signature, verification) plus a soft-delete marker. The
--     canonical "name" column stays, because appointments and schedules
--     already denormalise it and the roster read model projects it.
--
--   * doctor_qualifications - the repeatable degree/university/year rows.
--     Kept in their own table rather than a JSON column so a qualification
--     can be queried and reported on without parsing JSON in every query.
--
--   * users - the front-desk officer is a real CLINIC_STAFF account, so the
--     profile fields live on the account that signs in. Project reach stays
--     in project_memberships; a clinic assignment stays on
--     clinic_locations.frontDeskOfficerId, which is where Phase 38 put it.
--
-- Every new tenant-owned column is nullable or has a default: the migration
-- runs against live rows that predate it. The two verification columns get
-- a default so existing people remain "VERIFIED" rather than disappearing
-- from a screen that filters on the badge.
--
-- Tenant integrity uses the project's established composite-key pattern:
-- ("organizationId", "id") is unique on doctors, procedures and
-- clinic_locations, so each cross-table reference carries both columns.
-- The delete actions are RESTRICT rather than SET NULL because a composite
-- SET NULL would try to null the NOT NULL organizationId as well; neither
-- clinics nor services have a delete route in this slice.

-- ---------------------------------------------------------------------
-- doctors: the profile the management screen reads and writes
-- ---------------------------------------------------------------------

ALTER TABLE "doctors"
    ADD COLUMN IF NOT EXISTS "firstName" text,
    ADD COLUMN IF NOT EXISTS "lastName" text,
    ADD COLUMN IF NOT EXISTS "imageUrl" text,
    ADD COLUMN IF NOT EXISTS "gender" text,
    ADD COLUMN IF NOT EXISTS "aboutSelf" text,
    ADD COLUMN IF NOT EXISTS "expert" text,
    ADD COLUMN IF NOT EXISTS "commission" double precision,
    ADD COLUMN IF NOT EXISTS "clinicId" uuid,
    ADD COLUMN IF NOT EXISTS "serviceId" uuid,
    ADD COLUMN IF NOT EXISTS "experienceYears" integer,
    ADD COLUMN IF NOT EXISTS "country" text,
    ADD COLUMN IF NOT EXISTS "state" text,
    ADD COLUMN IF NOT EXISTS "city" text,
    ADD COLUMN IF NOT EXISTS "postalCode" text,
    ADD COLUMN IF NOT EXISTS "latitude" double precision,
    ADD COLUMN IF NOT EXISTS "longitude" double precision,
    ADD COLUMN IF NOT EXISTS "facebookUrl" text,
    ADD COLUMN IF NOT EXISTS "instagramUrl" text,
    ADD COLUMN IF NOT EXISTS "twitterUrl" text,
    ADD COLUMN IF NOT EXISTS "dribbbleUrl" text,
    ADD COLUMN IF NOT EXISTS "signatureUrl" text,
    ADD COLUMN IF NOT EXISTS "verificationStatus" text NOT NULL DEFAULT 'VERIFIED',
    ADD COLUMN IF NOT EXISTS "deletedAt" timestamp(3) without time zone;

ALTER TABLE "doctors"
    ADD CONSTRAINT "doctors_gender_check"
        CHECK ("gender" IS NULL OR "gender" IN ('MALE', 'FEMALE', 'OTHER')),
    ADD CONSTRAINT "doctors_verification_check"
        CHECK ("verificationStatus" IN ('PENDING', 'VERIFIED')),
    ADD CONSTRAINT "doctors_commission_check"
        CHECK ("commission" IS NULL OR "commission" >= 0),
    ADD CONSTRAINT "doctors_experience_check"
        CHECK ("experienceYears" IS NULL OR ("experienceYears" >= 0 AND "experienceYears" <= 80)),
    ADD CONSTRAINT "doctors_latitude_check"
        CHECK ("latitude" IS NULL OR ("latitude" >= -90 AND "latitude" <= 90)),
    ADD CONSTRAINT "doctors_longitude_check"
        CHECK ("longitude" IS NULL OR ("longitude" >= -180 AND "longitude" <= 180)),
    ADD CONSTRAINT "doctors_clinic_tenant_fkey"
        FOREIGN KEY ("clinicId", "organizationId")
        REFERENCES "clinic_locations" ("id", "organizationId") ON DELETE RESTRICT,
    ADD CONSTRAINT "doctors_service_tenant_fkey"
        FOREIGN KEY ("serviceId", "organizationId")
        REFERENCES "procedures" ("id", "organizationId") ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS "doctors_organizationId_projectId_deletedAt_idx"
    ON "doctors" ("organizationId", "projectId", "deletedAt");
CREATE INDEX IF NOT EXISTS "doctors_organizationId_clinicId_idx"
    ON "doctors" ("organizationId", "clinicId");

-- Existing rows predate the two name columns. Split the canonical name once
-- so the edit drawer opens with the same two fields a new doctor uses.
UPDATE "doctors"
   SET "firstName" = split_part("name", ' ', 1),
       "lastName" = nullif(trim(substr("name", length(split_part("name", ' ', 1)) + 1)), '')
 WHERE "firstName" IS NULL
   AND "lastName" IS NULL
   AND "name" IS NOT NULL;

-- ---------------------------------------------------------------------
-- doctor_qualifications: the repeatable rows in the drawer
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "doctor_qualifications" (
    "id" uuid NOT NULL,
    "organizationId" uuid NOT NULL,
    "doctorId" uuid NOT NULL,
    "degree" text NOT NULL,
    "university" text,
    "graduationYear" integer,
    "sortOrder" integer NOT NULL DEFAULT 0,
    "createdAt" timestamp(3) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "doctor_qualifications_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "doctor_qualifications_doctor_tenant_fkey"
        FOREIGN KEY ("doctorId", "organizationId")
        REFERENCES "doctors" ("id", "organizationId") ON DELETE CASCADE,
    CONSTRAINT "doctor_qualifications_degree_check"
        CHECK (length(trim("degree")) > 0),
    CONSTRAINT "doctor_qualifications_year_check"
        CHECK ("graduationYear" IS NULL OR ("graduationYear" >= 1950 AND "graduationYear" <= 2100))
);

CREATE INDEX IF NOT EXISTS "doctor_qualifications_organizationId_doctorId_idx"
    ON "doctor_qualifications" ("organizationId", "doctorId", "sortOrder");

-- ---------------------------------------------------------------------
-- users: the front-desk officer profile
-- ---------------------------------------------------------------------

ALTER TABLE "users"
    ADD COLUMN IF NOT EXISTS "firstName" text,
    ADD COLUMN IF NOT EXISTS "lastName" text,
    ADD COLUMN IF NOT EXISTS "phone" text,
    ADD COLUMN IF NOT EXISTS "gender" text,
    ADD COLUMN IF NOT EXISTS "dateOfBirth" date,
    ADD COLUMN IF NOT EXISTS "profileImageUrl" text,
    ADD COLUMN IF NOT EXISTS "addressLine" text,
    ADD COLUMN IF NOT EXISTS "country" text,
    ADD COLUMN IF NOT EXISTS "state" text,
    ADD COLUMN IF NOT EXISTS "city" text,
    ADD COLUMN IF NOT EXISTS "postalCode" text,
    ADD COLUMN IF NOT EXISTS "verificationStatus" text NOT NULL DEFAULT 'VERIFIED';

ALTER TABLE "users"
    ADD CONSTRAINT "users_gender_check"
        CHECK ("gender" IS NULL OR "gender" IN ('MALE', 'FEMALE', 'OTHER')),
    ADD CONSTRAINT "users_verification_check"
        CHECK ("verificationStatus" IN ('PENDING', 'VERIFIED'));

-- Mirrors the doctors backfill: an account created before this migration has
-- only fullName, and the edit form expects two fields.
UPDATE "users"
   SET "firstName" = split_part("fullName", ' ', 1),
       "lastName" = nullif(trim(substr("fullName", length(split_part("fullName", ' ', 1)) + 1)), '')
 WHERE "firstName" IS NULL
   AND "lastName" IS NULL
   AND "fullName" IS NOT NULL;
