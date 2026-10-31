-- =====================================================================
-- V20261025000000__clinic_resources_holds_waitlist_reminders.sql
-- Slice S2: Clinic Resources, Exclusion Constraints, Holds, Waitlist, and Reminders.
--
-- 1. appointments: resource assignment, DEC-001 buffer, and GiST exclusion constraints
--    preventing chair and doctor double-booking across active appointments.
-- 2. appointment_holds: TTL-gated holds for pending reservations.
-- 3. appointment_waitlist: patient waitlist with priority and preferred slots.
-- 4. appointment_reminders: queue for scheduled reminder dispatches (WhatsApp / SMS).
-- =====================================================================

-- Step 1. appointments resource assignment & buffer
ALTER TABLE "public"."appointments"
    ADD COLUMN IF NOT EXISTS "resourceId"    uuid,
    ADD COLUMN IF NOT EXISTS "bufferMinutes" integer DEFAULT 0 NOT NULL;

ALTER TABLE "public"."appointments"
    ADD CONSTRAINT "appointments_resource_fkey"
    FOREIGN KEY ("resourceId")
    REFERENCES "public"."clinic_resources" ("id")
    ON UPDATE CASCADE
    ON DELETE SET NULL;

-- Step 2. Exclusion constraints: No chair double-booking
ALTER TABLE "public"."appointments"
    ADD CONSTRAINT "appointments_resource_no_double_booking"
    EXCLUDE USING "gist" (
        "organizationId" WITH =,
        "resourceId"     WITH =,
        tsrange("appointmentDate", "appointmentDate" + ("durationMinutes" + "bufferMinutes") * interval '1 minute', '[)') WITH &&
    )
    WHERE ("status" NOT IN ('CANCELLED', 'NO_SHOW') AND "resourceId" IS NOT NULL);

COMMENT ON CONSTRAINT "appointments_resource_no_double_booking" ON "public"."appointments" IS
    'Prevents overlapping active bookings for the same chair/resource within an organization, accounting for duration plus DEC-001 buffer.';

-- Step 3. Exclusion constraints: No dentist double-booking
ALTER TABLE "public"."appointments"
    ADD CONSTRAINT "appointments_doctor_no_double_booking"
    EXCLUDE USING "gist" (
        "organizationId" WITH =,
        "doctorId"       WITH =,
        tsrange("appointmentDate", "appointmentDate" + ("durationMinutes" + "bufferMinutes") * interval '1 minute', '[)') WITH &&
    )
    WHERE ("status" NOT IN ('CANCELLED', 'NO_SHOW') AND "doctorId" IS NOT NULL);

COMMENT ON CONSTRAINT "appointments_doctor_no_double_booking" ON "public"."appointments" IS
    'Prevents overlapping active bookings for the same doctor within an organization, accounting for duration plus DEC-001 buffer.';

-- Step 4. Appointment Holds (TTL-governed slot reservations)
CREATE TABLE "public"."appointment_holds" (
    "id"                uuid NOT NULL,
    "organizationId"    uuid NOT NULL,
    "projectId"         uuid NOT NULL,
    "doctorId"          uuid NOT NULL,
    "resourceId"        uuid,
    "slotStartsAt"      timestamp(3) without time zone NOT NULL,
    "durationMinutes"   integer DEFAULT 30 NOT NULL,
    "bufferMinutes"     integer DEFAULT 0 NOT NULL,
    "holdExpiresAt"     timestamp(3) without time zone NOT NULL,
    "heldByUserId"      uuid,
    "patientIdentifier" text,
    "status"            text DEFAULT 'ACTIVE' NOT NULL,
    "createdAt"         timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt"         timestamp(3) without time zone NOT NULL,

    CONSTRAINT "appointment_holds_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "appointment_holds_status_check" CHECK ("status" IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'RELEASED'))
);

CREATE INDEX "appointment_holds_org_proj_status_idx"
    ON "public"."appointment_holds" ("organizationId", "projectId", "status");

CREATE INDEX "appointment_holds_expires_idx"
    ON "public"."appointment_holds" ("organizationId", "holdExpiresAt") WHERE ("status" = 'ACTIVE');

-- Step 5. Appointment Waitlist
CREATE TABLE "public"."appointment_waitlist" (
    "id"                    uuid NOT NULL,
    "organizationId"        uuid NOT NULL,
    "projectId"             uuid NOT NULL,
    "patientId"             uuid,
    "patientName"           text NOT NULL,
    "patientPhone"          text NOT NULL,
    "preferredDoctorId"     uuid,
    "procedureId"           uuid,
    "preferredDays"         text,
    "preferredTimeOfDay"    text,
    "priority"              text DEFAULT 'NORMAL' NOT NULL,
    "status"                text DEFAULT 'WAITING' NOT NULL,
    "offeredSlotStartsAt"   timestamp(3) without time zone,
    "notes"                 text,
    "createdAt"             timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt"             timestamp(3) without time zone NOT NULL,

    CONSTRAINT "appointment_waitlist_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "appointment_waitlist_priority_check" CHECK ("priority" IN ('NORMAL', 'URGENT')),
    CONSTRAINT "appointment_waitlist_status_check" CHECK ("status" IN ('WAITING', 'OFFERED', 'BOOKED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX "appointment_waitlist_org_proj_status_idx"
    ON "public"."appointment_waitlist" ("organizationId", "projectId", "status", "priority");

-- Step 6. Appointment Reminders Dispatch Log
CREATE TABLE "public"."appointment_reminders" (
    "id"                uuid NOT NULL,
    "organizationId"    uuid NOT NULL,
    "projectId"         uuid NOT NULL,
    "appointmentId"     uuid NOT NULL,
    "patientId"         uuid,
    "channel"           text DEFAULT 'WHATSAPP' NOT NULL,
    "recipientPhone"    text NOT NULL,
    "scheduledFor"      timestamp(3) without time zone NOT NULL,
    "sentAt"            timestamp(3) without time zone,
    "status"            text DEFAULT 'PENDING' NOT NULL,
    "templateCode"      text NOT NULL,
    "failureReason"     text,
    "createdAt"         timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT "appointment_reminders_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "appointment_reminders_channel_check" CHECK ("channel" IN ('WHATSAPP', 'SMS')),
    CONSTRAINT "appointment_reminders_status_check" CHECK ("status" IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED'))
);

CREATE INDEX "appointment_reminders_org_status_scheduled_idx"
    ON "public"."appointment_reminders" ("organizationId", "status", "scheduledFor");
