BEGIN;

-- 1. Ensure any non-cascade foreign keys pointing to other users are reassigned to our 1 main user
UPDATE accounts SET "createdById" = 'dd5afcaa-e4e2-4690-a471-d9955aa6f911' WHERE "createdById" IS NOT NULL;
UPDATE accounts SET "ownerId" = 'dd5afcaa-e4e2-4690-a471-d9955aa6f911' WHERE "ownerId" IS NOT NULL;
UPDATE clinic_payments SET "recordedByUserId" = 'dd5afcaa-e4e2-4690-a471-d9955aa6f911' WHERE "recordedByUserId" IS NOT NULL;
UPDATE audit_logs SET "actorUserId" = 'dd5afcaa-e4e2-4690-a471-d9955aa6f911' WHERE "actorUserId" IS NOT NULL;
UPDATE clinical_encounters SET "diagnosisUpdatedBy" = 'dd5afcaa-e4e2-4690-a471-d9955aa6f911' WHERE "diagnosisUpdatedBy" IS NOT NULL;
UPDATE treatment_plans SET "consentByUserId" = 'dd5afcaa-e4e2-4690-a471-d9955aa6f911' WHERE "consentByUserId" IS NOT NULL;

-- 2. Clear out other clinic user roles
DELETE FROM clinic_user_roles WHERE "userId" <> 'dd5afcaa-e4e2-4690-a471-d9955aa6f911';

-- 3. Delete all other users (cascades to project_memberships, sessions, employees)
DELETE FROM users WHERE id <> 'dd5afcaa-e4e2-4690-a471-d9955aa6f911';

-- 4. Update the 1 main account to have full super admin access
UPDATE users
SET email = 'admin@firstelement.dev',
    "fullName" = 'Super Administrator',
    "passwordHash" = '$2b$12$/kv1YKNHEfs8YH8bjtbQtOy5l1i3Yhz0qjcBm.hVzWLU51eMERC9K', -- Password123!
    role = 'ADMIN'::"UserRole",
    is_platform_admin = true,
    "isActive" = true,
    "verificationStatus" = 'VERIFIED',
    locale = 'ar'::"Locale",
    "updatedAt" = CURRENT_TIMESTAMP
WHERE id = 'dd5afcaa-e4e2-4690-a471-d9955aa6f911';

-- 5. Ensure project memberships for all projects
INSERT INTO project_memberships ("id", "organizationId", "projectId", "userId", "createdAt")
SELECT gen_random_uuid(), p."organizationId", p.id, 'dd5afcaa-e4e2-4690-a471-d9955aa6f911', CURRENT_TIMESTAMP
FROM projects p
WHERE NOT EXISTS (
    SELECT 1 FROM project_memberships pm 
    WHERE pm."userId" = 'dd5afcaa-e4e2-4690-a471-d9955aa6f911' AND pm."projectId" = p.id
);

-- 6. Assign all clinic roles to this main user
INSERT INTO clinic_user_roles ("organizationId", "clinicId", "userId", "roleId", "assignedAt")
SELECT cr."organizationId", NULL, 'dd5afcaa-e4e2-4690-a471-d9955aa6f911', cr.id, CURRENT_TIMESTAMP
FROM clinic_roles cr
ON CONFLICT ("userId", "roleId") DO NOTHING;

-- 7. Ensure employee record exists for CRM/Helpdesk/Routing
DELETE FROM employees WHERE "userId" <> 'dd5afcaa-e4e2-4690-a471-d9955aa6f911';

INSERT INTO employees ("id", "organizationId", "userId", "department", "availabilityStatus", "statusChangedAt", "maxConcurrentConversations", "activeConversationCount", "createdAt", "updatedAt")
VALUES (
    gen_random_uuid(),
    'c1384761-1a14-4bd4-aa11-4105b2aebc6b',
    'dd5afcaa-e4e2-4690-a471-d9955aa6f911',
    'Executive & Clinical Management',
    'ONLINE',
    CURRENT_TIMESTAMP,
    10,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT ("userId") DO UPDATE
SET "availabilityStatus" = 'ONLINE',
    "department" = 'Executive & Clinical Management',
    "updatedAt" = CURRENT_TIMESTAMP;

-- 8. Synchronize platform_operators for direct platform console access
DELETE FROM platform_operator_capabilities;
DELETE FROM platform_operators;

INSERT INTO platform_operators ("id", "email", "displayName", "passwordHash", "totpSecretEnc", "totpEnrolledAt", "isActive", "createdAt", "updatedAt")
VALUES (
    'dd5afcaa-e4e2-4690-a471-d9955aa6f911',
    'admin@firstelement.dev',
    'Super Administrator',
    '$2b$12$/kv1YKNHEfs8YH8bjtbQtOy5l1i3Yhz0qjcBm.hVzWLU51eMERC9K', -- Password123!
    'dummy-totp-secret',
    CURRENT_TIMESTAMP,
    true,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

INSERT INTO platform_operator_capabilities ("operatorId", "capability", "grantedAt") VALUES
('dd5afcaa-e4e2-4690-a471-d9955aa6f911', 'READ_PLATFORM_OVERVIEW', CURRENT_TIMESTAMP),
('dd5afcaa-e4e2-4690-a471-d9955aa6f911', 'CREATE_CLINIC', CURRENT_TIMESTAMP),
('dd5afcaa-e4e2-4690-a471-d9955aa6f911', 'CREATE_ACCOUNT', CURRENT_TIMESTAMP),
('dd5afcaa-e4e2-4690-a471-d9955aa6f911', 'GRANT_ACCESS', CURRENT_TIMESTAMP),
('dd5afcaa-e4e2-4690-a471-d9955aa6f911', 'READ_PLATFORM_AUDIT', CURRENT_TIMESTAMP),
('dd5afcaa-e4e2-4690-a471-d9955aa6f911', 'OPERATE_PLATFORM', CURRENT_TIMESTAMP),
('dd5afcaa-e4e2-4690-a471-d9955aa6f911', 'REQUEST_DB_ACTION', CURRENT_TIMESTAMP);

COMMIT;
