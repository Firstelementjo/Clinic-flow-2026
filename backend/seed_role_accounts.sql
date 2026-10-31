-- ─────────────────────────────────────────────────────────────────────────────
-- seed_role_accounts.sql
-- Creates one test login account per role persona so each role's scoped
-- UI can be tested independently.
--
-- PASSWORD for all accounts: Password123!
-- Hash (bcrypt $2b$12): $2b$12$/kv1YKNHEfs8YH8bjtbQtOy5l1i3Yhz0qjcBm.hVzWLU51eMERC9K
--
-- ┌──────────────────────────────────────────┬─────────────────┬────────────┐
-- │ Email                                    │ Role            │ Persona    │
-- ├──────────────────────────────────────────┼─────────────────┼────────────┤
-- │ admin@firstelement.dev      (EXISTING)   │ ADMIN           │ Clinic Mgr │
-- │ doctor@firstelement.dev                  │ EMPLOYEE        │ Dentist    │
-- │ receptionist@firstelement.dev            │ CLINIC_STAFF    │ Front Desk │
-- │ ai-lead@firstelement.dev                 │ ADMIN           │ AI Lead    │
-- │ platform@firstelement.dev                │ ADMIN           │ Super Admin│
-- └──────────────────────────────────────────┴─────────────────┴────────────┘
--
-- NOTE: ai-lead and platform both use ADMIN role because UserRole only has 3
-- values. The sidebar distinguishes them via permission gates (system-health:read).
--
-- HOW TO RUN (psql):
--   psql -U postgres -d firstelement -f seed_role_accounts.sql
-- ─────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
    v_org_id  UUID := 'c1384761-1a14-4bd4-aa11-4105b2aebc6b';
    v_proj_id UUID := '11cfe1a0-8b85-4e2a-a817-c78a658b505a';
    v_pwd     TEXT := '$2b$12$/kv1YKNHEfs8YH8bjtbQtOy5l1i3Yhz0qjcBm.hVzWLU51eMERC9K';

    v_doctor_id        UUID := 'ee000001-0000-4000-a000-000000000001';
    v_receptionist_id  UUID := 'ee000002-0000-4000-a000-000000000002';
    v_ai_lead_id       UUID := 'ee000003-0000-4000-a000-000000000003';
    v_platform_id      UUID := 'ee000004-0000-4000-a000-000000000004';
BEGIN

    -- ── 1. Doctor / Treating Dentist (EMPLOYEE) ───────────────────────────
    INSERT INTO users ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale", "isActive", "createdAt", "updatedAt")
    VALUES (
        v_doctor_id, v_org_id,
        'doctor@firstelement.dev', v_pwd,
        'د. إبراهيم الخالدي', 'EMPLOYEE'::\"UserRole\", 'ar', true, now(), now()
    )
    ON CONFLICT ("email") DO UPDATE
        SET "fullName" = EXCLUDED."fullName",
            "role"     = EXCLUDED."role",
            "isActive" = true,
            "updatedAt" = now();

    -- Give doctor project membership so session resolves a projectId
    INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
    VALUES (gen_random_uuid(), v_org_id, v_proj_id, v_doctor_id, now())
    ON CONFLICT DO NOTHING;

    -- ── 2. Receptionist / Front-Desk (CLINIC_STAFF) ───────────────────────
    INSERT INTO users ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale", "isActive", "createdAt", "updatedAt")
    VALUES (
        v_receptionist_id, v_org_id,
        'receptionist@firstelement.dev', v_pwd,
        'نهى المطيري', 'CLINIC_STAFF'::\"UserRole\", 'ar', true, now(), now()
    )
    ON CONFLICT ("email") DO UPDATE
        SET "fullName" = EXCLUDED."fullName",
            "role"     = EXCLUDED."role",
            "isActive" = true,
            "updatedAt" = now();

    INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
    VALUES (gen_random_uuid(), v_org_id, v_proj_id, v_receptionist_id, now())
    ON CONFLICT DO NOTHING;

    -- ── 3. AI Governance Lead (ADMIN + system-health:read capability) ──────
    -- NOTE: The system-health:read permission is granted by the capability system,
    -- not stored in a users column — the backend role policy grants it to ADMIN
    -- accounts. This account is differentiated in the UI by the permission gate.
    INSERT INTO users ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale", "isActive", "createdAt", "updatedAt")
    VALUES (
        v_ai_lead_id, v_org_id,
        'ai-lead@firstelement.dev', v_pwd,
        'AI Governance Lead', 'ADMIN'::\"UserRole\", 'en', true, now(), now()
    )
    ON CONFLICT ("email") DO UPDATE
        SET "fullName" = EXCLUDED."fullName",
            "role"     = EXCLUDED."role",
            "isActive" = true,
            "updatedAt" = now();

    INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
    VALUES (gen_random_uuid(), v_org_id, v_proj_id, v_ai_lead_id, now())
    ON CONFLICT DO NOTHING;

    -- ── 4. Website Super-Admin / Platform Operator (ADMIN) ────────────────
    -- Platform super-admin has no clinic org binding in production, but for
    -- dev/demo we assign to the same org so the session resolves.
    INSERT INTO users ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale", "isActive", "createdAt", "updatedAt")
    VALUES (
        v_platform_id, v_org_id,
        'platform@firstelement.dev', v_pwd,
        'Platform Super Admin', 'ADMIN'::\"UserRole\", 'en', true, now(), now()
    )
    ON CONFLICT ("email") DO UPDATE
        SET "fullName" = EXCLUDED."fullName",
            "role"     = EXCLUDED."role",
            "isActive" = true,
            "updatedAt" = now();

    INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
    VALUES (gen_random_uuid(), v_org_id, v_proj_id, v_platform_id, now())
    ON CONFLICT DO NOTHING;

    RAISE NOTICE 'Role seed complete: doctor, receptionist, ai-lead, platform accounts ready.';
END $$;
