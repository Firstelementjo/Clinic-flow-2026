-- V20261102000000: Clinic locations and the service catalogue
--
-- Phase 38 adds the two master-data screens the ToothLogic clinic administration
-- needs before anything can be booked against them:
--
--   * clinic_locations - the physical clinics (name, contact, address, slot size,
--     brand image, active flag). A project is a clinic workspace; a project may
--     own more than one physical location.
--
--   * procedures - already the service catalogue row, extended here with the
--     commercial and presentation fields the Create Service drawer records.
--
-- The existing procedures table stays the single source of truth. Appointments,
-- invoices, treatment plans, odontogram findings and the AI booking path all
-- already point at it, so a second "services" table would split the catalogue in
-- two and let the same procedure have two prices.
--
-- Every new tenant-owned column is nullable or has a default: this migration
-- runs against live rows that predate it, and a NOT NULL without a default would
-- fail the deployment rather than the form.
--
-- Tenant integrity uses the project's established composite-key pattern:
-- ("organizationId", "id") is unique and every cross-table reference carries the
-- organization id with it. A row cannot name a clinic from another tenant.

-- ---------------------------------------------------------------------------
-- 1. Physical clinic locations
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS "public"."clinic_locations" (
    "id" UUID NOT NULL,
    "organizationId" UUID NOT NULL,
    "projectId" UUID NOT NULL,
    "name" TEXT NOT NULL,
    -- The front-desk officer shown on the Clinics list. Nullable: a new clinic is
    -- created before a receptionist is assigned to it.
    "frontDeskOfficerId" UUID NULL,
    "contactNumber" TEXT NULL,
    "description" TEXT NULL,
    "addressLine" TEXT NULL,
    "country" TEXT NULL,
    "state" TEXT NULL,
    "city" TEXT NULL,
    "postalCode" TEXT NULL,
    "slotMinutes" INTEGER NOT NULL DEFAULT 30,
    -- A data URL or an object-storage URL. Stored as text so the screen can show
    -- the brand image without a second upload service in this slice.
    "brandImageUrl" TEXT NULL,
    "isActive" BOOLEAN NOT NULL DEFAULT TRUE,
    "createdAt" TIMESTAMP(3) WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "clinic_locations_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "clinic_locations_slot_minutes_check"
        CHECK ("slotMinutes" BETWEEN 5 AND 480)
);

-- The composite tenant key every child table references.
CREATE UNIQUE INDEX IF NOT EXISTS "clinic_locations_id_organizationId_key"
    ON "public"."clinic_locations" USING "btree" ("id", "organizationId");

CREATE INDEX IF NOT EXISTS "clinic_locations_organizationId_projectId_isActive_idx"
    ON "public"."clinic_locations" USING "btree"
        ("organizationId", "projectId", "isActive");

CREATE INDEX IF NOT EXISTS "clinic_locations_organizationId_name_idx"
    ON "public"."clinic_locations" USING "btree"
        ("organizationId", "name");

-- Tenant and project parents. ON DELETE CASCADE is deliberately not used for the
-- project: deleting a project should not silently delete the clinics that were
-- recorded in it. The project is archived instead, and the row survives.
ALTER TABLE "public"."clinic_locations"
    ADD CONSTRAINT "clinic_locations_organizationId_fkey"
        FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id")
        ON UPDATE CASCADE ON DELETE CASCADE;

ALTER TABLE "public"."clinic_locations"
    ADD CONSTRAINT "clinic_locations_projectId_fkey"
        FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id")
        ON UPDATE CASCADE ON DELETE RESTRICT;

-- A front-desk officer is a user. The composite key keeps the assignment inside
-- the same tenant as the clinic.
ALTER TABLE "public"."clinic_locations"
    ADD CONSTRAINT "clinic_locations_front_desk_tenant_fkey"
        FOREIGN KEY ("frontDeskOfficerId", "organizationId")
        REFERENCES "public"."users"("id", "organizationId")
        ON UPDATE CASCADE ON DELETE SET NULL ("frontDeskOfficerId");

-- ---------------------------------------------------------------------------
-- 2. Service catalogue fields on procedures
-- ---------------------------------------------------------------------------

ALTER TABLE "public"."procedures"
    ADD COLUMN IF NOT EXISTS "productCode" TEXT NULL,
    ADD COLUMN IF NOT EXISTS "subCategory" TEXT NULL,
    ADD COLUMN IF NOT EXISTS "clinicId" UUID NULL,
    ADD COLUMN IF NOT EXISTS "timeSlotMinutes" INTEGER NULL,
    ADD COLUMN IF NOT EXISTS "type" TEXT NOT NULL DEFAULT 'IN_CLINIC',
    ADD COLUMN IF NOT EXISTS "description" TEXT NULL,
    ADD COLUMN IF NOT EXISTS "discount" DOUBLE PRECISION NULL,
    ADD COLUMN IF NOT EXISTS "imageUrl" TEXT NULL,
    ADD COLUMN IF NOT EXISTS "systemService" TEXT NULL,
    ADD COLUMN IF NOT EXISTS "advancedPayment" BOOLEAN NOT NULL DEFAULT FALSE;

-- The screenshot's "Type" field. Constrained rather than free text so a report
-- can group by it without learning every spelling an operator has typed.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'procedures_type_check'
    ) THEN
        ALTER TABLE "public"."procedures"
            ADD CONSTRAINT "procedures_type_check"
            CHECK ("type" IN ('IN_CLINIC', 'ONLINE', 'HOME_VISIT'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'procedures_discount_check'
    ) THEN
        ALTER TABLE "public"."procedures"
            ADD CONSTRAINT "procedures_discount_check"
            CHECK ("discount" IS NULL OR ("discount" >= 0 AND "discount" <= 100));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'procedures_time_slot_check'
    ) THEN
        ALTER TABLE "public"."procedures"
            ADD CONSTRAINT "procedures_time_slot_check"
            CHECK ("timeSlotMinutes" IS NULL OR "timeSlotMinutes" BETWEEN 5 AND 480);
    END IF;
END $$;

-- One product code per project. A partial index so the existing rows, which have
-- no product code, do not collide on NULL.
CREATE UNIQUE INDEX IF NOT EXISTS "procedures_project_product_code_key"
    ON "public"."procedures" USING "btree"
        ("organizationId", "projectId", "productCode")
    WHERE "productCode" IS NOT NULL AND "productCode" <> '';

CREATE INDEX IF NOT EXISTS "procedures_organizationId_projectId_productCode_idx"
    ON "public"."procedures" USING "btree"
        ("organizationId", "projectId", "productCode");

-- A procedure may name the clinic that offers it. The composite tenant key makes
-- a cross-tenant clinic reference impossible.
ALTER TABLE "public"."procedures"
    ADD CONSTRAINT "procedures_clinic_tenant_fkey"
        FOREIGN KEY ("clinicId", "organizationId")
        REFERENCES "public"."clinic_locations"("id", "organizationId")
        ON UPDATE CASCADE ON DELETE SET NULL ("clinicId");

-- ---------------------------------------------------------------------------
-- 3. Seed a first location for each existing clinic project
-- ---------------------------------------------------------------------------
--
-- Without this, every existing project opens the new Clinics screen empty even
-- though it has procedures and doctors already. The row is derived from the
-- project's own name, so it claims nothing that is not already in the database.
-- Deterministic ids keep a re-run (or a reset test database) from duplicating it.
INSERT INTO "public"."clinic_locations"
    ("id", "organizationId", "projectId", "name", "slotMinutes", "isActive",
     "createdAt", "updatedAt")
SELECT
    md5('clinic-location:' || p."id"::text)::uuid,
    p."organizationId",
    p."id",
    p."name",
    30,
    TRUE,
    (now() AT TIME ZONE 'UTC'),
    (now() AT TIME ZONE 'UTC')
FROM "public"."projects" p
WHERE p."isArchived" = FALSE
  AND NOT EXISTS (
      SELECT 1
      FROM "public"."clinic_locations" l
      WHERE l."organizationId" = p."organizationId"
        AND l."projectId" = p."id"
  );

-- Point existing procedures at their project's new location. This is a display
-- convenience, not a claim that the procedure is only offered at that location;
-- the column stays nullable and a later screen can offer a multi-clinic service.
UPDATE "public"."procedures" pr
   SET "clinicId" = l."id",
       "updatedAt" = (now() AT TIME ZONE 'UTC')
  FROM "public"."clinic_locations" l
 WHERE pr."clinicId" IS NULL
   AND l."organizationId" = pr."organizationId"
   AND l."projectId" = pr."projectId";
