DO $$
DECLARE
    v_org_id UUID := 'c1384761-1a14-4bd4-aa11-4105b2aebc6b';
    v_proj_id UUID := '11cfe1a0-8b85-4e2a-a817-c78a658b505a';
    v_pwd TEXT := '$2b$12$/kv1YKNHEfs8YH8bjtbQtOy5l1i3Yhz0qjcBm.hVzWLU51eMERC9K'; -- Password123!
    v_platform_uid UUID := 'ee000004-0000-4000-a000-000000000004';
    v_clinic_admin_uid UUID := 'dd5afcaa-e4e2-4690-a471-d9955aa6f911';
BEGIN
    -- 1. Setup Platform Admin Account (platform@clinicflow.io)
    INSERT INTO users ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale", "isActive", "verificationStatus", "is_platform_admin", "invitePending", "createdAt", "updatedAt")
    VALUES (
        v_platform_uid, v_org_id,
        'platform@clinicflow.io', v_pwd,
        'Platform Operator (مدير المنصة)', 'ADMIN'::"UserRole", 'ar', true, 'VERIFIED', true, false, now(), now()
    )
    ON CONFLICT ("email") DO UPDATE
        SET "passwordHash" = v_pwd,
            "fullName" = 'Platform Operator (مدير المنصة)',
            "is_platform_admin" = true,
            "verificationStatus" = 'VERIFIED',
            "isActive" = true,
            "updatedAt" = now();

    -- Ensure platform role only
    DELETE FROM clinic_user_roles WHERE "userId" = v_platform_uid;
    INSERT INTO clinic_user_roles ("organizationId", "clinicId", "userId", "roleId", "assignedAt")
    VALUES (v_org_id, NULL, v_platform_uid, 'c0000003-0000-4000-8000-000000000001', now());

    INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
    VALUES (gen_random_uuid(), v_org_id, v_proj_id, v_platform_uid, now())
    ON CONFLICT DO NOTHING;

    -- 2. Setup Clinic Super Admin Account (admin@clinicflow.io)
    INSERT INTO users ("id", "organizationId", "email", "passwordHash", "fullName", "role", "locale", "isActive", "verificationStatus", "is_platform_admin", "invitePending", "createdAt", "updatedAt")
    VALUES (
        v_clinic_admin_uid, v_org_id,
        'admin@clinicflow.io', v_pwd,
        'Super Admin (سوبر أدمن العيادة)', 'ADMIN'::"UserRole", 'ar', true, 'VERIFIED', false, false, now(), now()
    )
    ON CONFLICT ("id") DO UPDATE
        SET "email" = 'admin@clinicflow.io',
            "passwordHash" = v_pwd,
            "fullName" = 'Super Admin (سوبر أدمن العيادة)',
            "is_platform_admin" = false,
            "verificationStatus" = 'VERIFIED',
            "isActive" = true,
            "updatedAt" = now();

    -- Ensure clinic_admin role only
    DELETE FROM clinic_user_roles WHERE "userId" = v_clinic_admin_uid;
    INSERT INTO clinic_user_roles ("organizationId", "clinicId", "userId", "roleId", "assignedAt")
    VALUES (v_org_id, NULL, v_clinic_admin_uid, 'c0000003-0000-4000-8000-000000000002', now());

    INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
    VALUES (gen_random_uuid(), v_org_id, v_proj_id, v_clinic_admin_uid, now())
    ON CONFLICT DO NOTHING;

    RAISE NOTICE 'Both accounts provisioned successfully.';
END $$;
