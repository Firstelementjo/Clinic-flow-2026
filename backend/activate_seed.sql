-- Full Activation Seed Script for ToothLogic Dental Practice Platform

DO $$
DECLARE
    v_org_id UUID := 'c1384761-1a14-4bd4-aa11-4105b2aebc6b';
    v_proj_id UUID := '11cfe1a0-8b85-4e2a-a817-c78a658b505a';
    v_user_id UUID := 'dd5afcaa-e4e2-4690-a471-d9955aa6f911';

    -- Chairs (Resources)
    v_res_1 UUID := 'c0000001-0000-4000-a000-000000000001';
    v_res_2 UUID := 'c0000002-0000-4000-a000-000000000002';
    v_res_3 UUID := 'c0000003-0000-4000-a000-000000000003';
    v_res_4 UUID := 'c0000004-0000-4000-a000-000000000004';

    -- Doctors
    v_doc_1 UUID := 'd1111111-1111-4111-a111-111111111111'; -- د. إبراهيم الخالدي
    v_doc_2 UUID := 'd2222222-2222-4222-a222-222222222222'; -- د. نورة المنصور
    v_doc_3 UUID := 'd3333333-3333-4333-a333-333333333333'; -- د. فيصل القحطاني
    v_doc_4 UUID := 'd4444444-4444-4444-a444-444444444444'; -- د. منى السالم

    -- Procedures
    v_proc_1 UUID := 'e1111111-1111-4111-a111-111111111111'; -- زراعة سن تيتانيوم
    v_proc_2 UUID := 'e2222222-2222-4222-a222-222222222222'; -- علاج عصب
    v_proc_3 UUID := 'e3333333-3333-4333-a333-333333333333'; -- تنظيف وتلميع
    v_proc_4 UUID := 'e4444444-4444-4444-a444-444444444444'; -- تقويم أسنان شفاف
    v_proc_5 UUID := 'e5555555-5555-4555-a555-555555555555'; -- حشوة تجميلية كمبوزيت

    -- Patients
    v_pat_1 UUID := 'a1111111-1111-4111-a111-111111111111'; -- ليلى حسن
    v_pat_2 UUID := 'a2222222-2222-4222-a222-222222222222'; -- سارة أحمد
    v_pat_3 UUID := 'a3333333-3333-4333-a333-333333333333'; -- عمر ناصر
    v_pat_4 UUID := 'a4444444-4444-4444-a444-444444444444'; -- طارق العتيبي
    v_pat_5 UUID := 'a5555555-5555-4555-a555-555555555555'; -- ريم الحربي

    -- Appointments
    v_apt_1 UUID := 'b1111111-1111-4111-a111-111111111111';
    v_apt_2 UUID := 'b2222222-2222-4222-a222-222222222222';
    v_apt_3 UUID := 'b3333333-3333-4333-a333-333333333333';
    v_apt_4 UUID := 'b4444444-4444-4444-a444-444444444444';
    v_apt_5 UUID := 'b5555555-5555-4555-a555-555555555555';

    -- Invoices & Plans
    v_plan_1 UUID := 'f1111111-1111-4111-a111-111111111111';
    v_plan_item_1 UUID := 'f2222222-2222-4222-a222-222222222222';
    v_inv_1 UUID := '91111111-1111-4111-a111-111111111111';
    v_inv_2 UUID := '92222222-2222-4222-a222-222222222222';
    v_pay_1 UUID := '81111111-1111-4111-a111-111111111111';
    v_enc_1 UUID := '71111111-1111-4111-a111-111111111111';

BEGIN
    -- 1. Procedures
    INSERT INTO procedures ("id", "organizationId", "projectId", "name", "specialty", "durationMinutes", "bufferMinutes", "price", "isActive", "createdAt", "updatedAt")
    VALUES
        (v_proc_4, v_org_id, v_proj_id, 'تقويم أسنان شفاف Invisalign', 'تقويم الأسنان', 45, 15, 1200.00, true, now(), now()),
        (v_proc_5, v_org_id, v_proj_id, 'حشوة ضوئية تجميلية كمبوزيت', 'طب وجراحة الفم والأسنان', 30, 10, 65.00, true, now(), now())
    ON CONFLICT ("id") DO NOTHING;

    -- 2. Doctors
    INSERT INTO doctors ("id", "organizationId", "projectId", "name", "specialty", "department", "workingDays", "startHour", "endHour", "slotDuration", "isActive", "createdAt", "updatedAt", "email", "phone")
    VALUES
        (v_doc_2, v_org_id, v_proj_id, 'د. نورة المنصور', 'علاج الجذور وعصب الأسنان', 'طب الأسنان التحفظي', ARRAY[0,1,2,3,4], '09:00', '17:00', 45, true, now(), now(), 'noura.mansour@clinic.dev', '+966500000012'),
        (v_doc_3, v_org_id, v_proj_id, 'د. فيصل القحطاني', 'تقويم الأسنان وتجميل الابتسامة', 'قسم التقويم والتجميل', ARRAY[0,1,2,3,4], '10:00', '18:00', 30, true, now(), now(), 'faisal.qahtani@clinic.dev', '+966500000013'),
        (v_doc_4, v_org_id, v_proj_id, 'د. منى السالم', 'طب أسنان الأطفال والوقاية', 'قسم أسنان الأطفال', ARRAY[0,1,2,3,4], '08:30', '16:30', 30, true, now(), now(), 'muna.salem@clinic.dev', '+966500000014')
    ON CONFLICT ("id") DO NOTHING;

    -- 3. Dental Operatory Chairs (Clinic Resources)
    INSERT INTO clinic_resources ("id", "organizationId", "projectId", "name", "isActive", "createdAt")
    VALUES
        (v_res_1, v_org_id, v_proj_id, 'Operatory 1 (كشف وتشخيص عام)', true, now()),
        (v_res_2, v_org_id, v_proj_id, 'Operatory 2 (جراحة وزراعة الأسنان)', true, now()),
        (v_res_3, v_org_id, v_proj_id, 'Operatory 3 (تقويم وتجميل الابتسامة)', true, now()),
        (v_res_4, v_org_id, v_proj_id, 'Operatory 4 (صحة الفم والوقاية)', true, now())
    ON CONFLICT ("id") DO NOTHING;

    INSERT INTO clinic_resource_capabilities ("id", "organizationId", "resourceId", "capability", "createdAt")
    VALUES
        (gen_random_uuid(), v_org_id, v_res_1, 'GENERAL_DENTISTRY', now()),
        (gen_random_uuid(), v_org_id, v_res_1, 'CONSULTATION', now()),
        (gen_random_uuid(), v_org_id, v_res_2, 'ORAL_SURGERY', now()),
        (gen_random_uuid(), v_org_id, v_res_2, 'IMPLANTOLOGY', now()),
        (gen_random_uuid(), v_org_id, v_res_3, 'ORTHODONTICS', now()),
        (gen_random_uuid(), v_org_id, v_res_3, 'AESTHETICS', now()),
        (gen_random_uuid(), v_org_id, v_res_4, 'PERIODONTICS', now()),
        (gen_random_uuid(), v_org_id, v_res_4, 'HYGIENE_CLEANING', now())
    ON CONFLICT ("organizationId", "resourceId", "capability") DO NOTHING;

    -- 4. Patients
    INSERT INTO patients ("id", "organizationId", "projectId", "patientNo", "displayName", "givenName", "familyName", "dateOfBirth", "primaryPhone", "primaryEmail", "locale", "deletedAt", "createdAt", "updatedAt")
    VALUES
        (v_pat_1, v_org_id, v_proj_id, 'P-1001', 'ليلى حسن', 'ليلى', 'حسن', '1991-04-02', '+966500000001', 'layla.hassan@example.com', 'ar', NULL, now(), now()),
        (v_pat_2, v_org_id, v_proj_id, 'P-1002', 'سارة أحمد', 'سارة', 'أحمد', '1988-08-15', '+966500000002', 'sara.ahmed@example.com', 'ar', NULL, now(), now()),
        (v_pat_3, v_org_id, v_proj_id, 'P-1003', 'عمر ناصر', 'عمر', 'ناصر', '1995-11-20', '+966500000003', 'omar.nasser@example.com', 'ar', NULL, now(), now()),
        (v_pat_4, v_org_id, v_proj_id, 'P-1004', 'طارق العتيبي', 'طارق', 'العتيبي', '1982-01-10', '+966500000004', 'tariq.otaibi@example.com', 'ar', NULL, now(), now()),
        (v_pat_5, v_org_id, v_proj_id, 'P-1005', 'ريم الحربي', 'ريم', 'الحربي', '1999-06-25', '+966500000005', 'reem.harbi@example.com', 'ar', NULL, now(), now())
    ON CONFLICT ("id") DO UPDATE SET "displayName" = EXCLUDED."displayName";

    -- 5. Medical History
    INSERT INTO patient_medical_histories ("id", "organizationId", "projectId", "patientId", "allergies", "conditions", "bleedingDisorder", "cardiacCondition", "diabetes", "currentMedications", "notes", "createdAt", "updatedAt")
    VALUES
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_1, 'حساسية مفرطة من البنسلين واللاتكس', 'ربو تحسسي خفيف', false, false, false, 'Ventolin Inhaler عند اللزوم', 'يمنع استخدام أي مضاد حيوي من عائلة البنسلين', now(), now()),
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_2, NULL, 'سكري النوع الثاني، ارتفاع ضغط دم', false, true, true, 'Metformin 500mg, Concor 5mg', 'يجب قياس الضغط والسكر قبل أي إجراء جراحي', now(), now()),
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_4, NULL, 'سيولة دم خفيفة، مميعات دم', true, false, false, 'Aspirin 81mg', 'إيقاف الأسبرين بتنسيق طبي قبل الجراحة', now(), now())
    ON CONFLICT ("id") DO NOTHING;

    -- 6. Appointments (customerId = NULL to satisfy customer FK)
    INSERT INTO appointments (
        "id", "organizationId", "projectId", "customerId", "doctorId", "procedureId", "resourceId",
        "title", "patientName", "patientPhone", "appointmentDate", "durationMinutes", "bufferMinutes",
        "status", "service", "doctorName", "isAiProposed", "ticketNumber", "createdAt", "updatedAt"
    )
    VALUES
        (v_apt_1, v_org_id, v_proj_id, NULL, v_doc_1, v_proc_1, v_res_2,
         'زراعة سن - ليلى حسن', 'ليلى حسن', '+966500000001',
         date_trunc('day', now()) + interval '9 hours', 60, 15,
         'CONFIRMED', 'زراعة سن تيتانيوم متكاملة', 'د. إبراهيم الخالدي', false, 'APT-1001', now(), now()),

        (v_apt_2, v_org_id, v_proj_id, NULL, v_doc_2, v_proc_2, v_res_1,
         'علاج عصب - سارة أحمد', 'سارة أحمد', '+966500000002',
         date_trunc('day', now()) + interval '10 hours 30 minutes', 45, 15,
         'IN_PROGRESS', 'معالجة عصب الضرس مع الحشوة التجميلية', 'د. نورة المنصور', false, 'APT-1002', now(), now()),

        (v_apt_3, v_org_id, v_proj_id, NULL, v_doc_4, v_proc_3, v_res_4,
         'تنظيف أسنان - عمر ناصر', 'عمر ناصر', '+966500000003',
         date_trunc('day', now()) + interval '11 hours 45 minutes', 30, 10,
         'CHECKED_IN', 'تنظيف وتلميع وإزالة الجير للأسنان', 'د. منى السالم', true, 'APT-1003', now(), now()),

        (v_apt_4, v_org_id, v_proj_id, NULL, v_doc_3, v_proc_4, v_res_3,
         'جلسة تقويم - طارق العتيبي', 'طارق العتيبي', '+966500000004',
         date_trunc('day', now()) + interval '13 hours', 45, 15,
         'COMPLETED', 'تقويم أسنان شفاف Invisalign', 'د. فيصل القحطاني', false, 'APT-1004', now(), now()),

        (v_apt_5, v_org_id, v_proj_id, NULL, v_doc_1, v_proc_5, v_res_1,
         'حشوة كمبوزيت - ريم الحربي', 'ريم الحربي', '+966500000005',
         date_trunc('day', now()) + interval '14 hours 30 minutes', 30, 10,
         'PENDING', 'حشوة ضوئية تجميلية كمبوزيت', 'د. إبراهيم الخالدي', true, 'APT-1005', now(), now())
    ON CONFLICT ("id") DO NOTHING;

    -- 7. Odontogram Finding Vocabulary
    INSERT INTO clinic_finding_vocabulary (
        "id", "organizationId", "projectId", "code", "category", "labelAr", "labelEn", "descriptionEn",
        "attributeSchema", "isClinicianReviewed", "isActive", "createdAt"
    )
    SELECT
        gen_random_uuid(), v_org_id, v_proj_id, t.code, t.category, t."labelEn", t."labelEn", t."descriptionEn",
        t."attributeSchema", true, true, now()
    FROM clinic_finding_vocabulary_template t
    ON CONFLICT ("code", "projectId", "organizationId") DO NOTHING;

    -- 8. Odontogram Findings for Patient
    INSERT INTO patient_odontogram_findings (
        "id", "organizationId", "projectId", "patientId", "toothFdi", "scope", "surfaces",
        "code", "state", "source", "recordedBy", "recordedAt", "createdAt", "note"
    )
    VALUES
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_4, 46, 'TOOTH'::"FindingScope", NULL,
         'CARIES-ICDAS-4', 'EXISTING'::"FindingState", 'CLINICAL'::"FindingSource", v_user_id, now(), now(), 'تسوس عميق واصل للعصب'),
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_4, 16, 'SURFACE'::"FindingScope", ARRAY['O'::"FindingSurface"],
         'RESTORATION-FILLING', 'COMPLETED'::"FindingState", 'CLINICAL'::"FindingSource", v_user_id, now(), now(), 'حشوة كمبوزيت سابقة جيدة'),
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_4, 21, 'TOOTH'::"FindingScope", NULL,
         'PROSTHETIC-CROWN', 'PLANNED'::"FindingState", 'CLINICAL'::"FindingSource", v_user_id, now(), now(), 'تاج زيركون تجميلي مخطط')
    ON CONFLICT DO NOTHING;

    -- 9. Treatment Plan & Items
    INSERT INTO treatment_plans (
        "id", "organizationId", "projectId", "patientId", "version", "title", "status",
        "planType", "totalDiscount", "insuranceEstimatedCoverage", "patientEstimatedPortion",
        "patientConsent", "consentAt", "createdAt", "updatedAt"
    )
    VALUES
        (v_plan_1, v_org_id, v_proj_id, v_pat_4, 1, 'خطة تأهيل الفم الكامل وزراعة الأضراس', 'ACTIVE',
         'PRIMARY', 50.00, 200.00, 750.00, true, now(), now(), now())
    ON CONFLICT ("id") DO NOTHING;

    -- 10. Clinical Encounter
    INSERT INTO clinical_encounters (
        "id", "organizationId", "projectId", "patientId", "appointmentId", "doctorId",
        "visitDate", "durationMinutes", "status", "chiefComplaint", "examinationNotes",
        "diagnosis", "performedProcedures", "toothFdi", "bloodPressureSystolic", "bloodPressureDiastolic", "pulseRate",
        "createdAt", "updatedAt"
    )
    VALUES
        (v_enc_1, v_org_id, v_proj_id, v_pat_4, v_apt_4, v_doc_3,
         now() - interval '2 hours', 45, 'COMPLETED',
         'ألم حاد متقطع مع المشروبات الباردة والساخنة بالفك السفلي الأيمن',
         'تسوس عميق واصل لغرفة العصب في الضرس رقم 46 مع حساسية للقرع العمودي',
         'K04.0 التهاب لب السن الحاد Irreversible Pulpitis',
         'تخدير موضعي، عزل بالحاجز المطاطي، فتح الحجرة اللبية واستئصال العصب، تنظيف وتشكيل القنوات، ووضع ضماد طبي مؤقت',
         46, 122, 78, 72,
         now(), now())
    ON CONFLICT ("id") DO NOTHING;

    -- 11. Invoices (using JOD currency allowed by constraint)
    INSERT INTO clinic_invoices (
        "id", "organizationId", "projectId", "patientId", "encounterId", "invoiceNumber", "status",
        "subtotal", "discountAmount", "totalAmount", "paidAmount", "balanceDue", "currency", "issuedAt", "createdAt", "updatedAt"
    )
    VALUES
        (v_inv_1, v_org_id, v_proj_id, v_pat_4, v_enc_1, 'INV-2026-001', 'PAID',
         450.00, 50.00, 400.00, 400.00, 0.00, 'JOD', now() - interval '2 days', now() - interval '2 days', now()),

        (v_inv_2, v_org_id, v_proj_id, v_pat_2, NULL, 'INV-2026-002', 'PARTIALLY_PAID',
         190.00, 0.00, 190.00, 100.00, 90.00, 'JOD', now() - interval '1 day', now() - interval '1 day', now())
    ON CONFLICT ("id") DO NOTHING;

    INSERT INTO clinic_invoice_items ("id", "organizationId", "projectId", "invoiceId", "procedureId", "description", "itemType", "toothFdi", "unitPrice", "quantity", "totalPrice", "createdAt")
    VALUES
        (gen_random_uuid(), v_org_id, v_proj_id, v_inv_1, v_proc_1, 'زراعة سن تيتانيوم متكاملة مع الدعامة', 'PROCEDURE', 46, 450.00, 1, 450.00, now()),
        (gen_random_uuid(), v_org_id, v_proj_id, v_inv_2, v_proc_2, 'معالجة عصب الضرس المرحلة الأولى والثانية', 'PROCEDURE', 26, 95.00, 2, 190.00, now())
    ON CONFLICT ("id") DO NOTHING;

    -- 12. Payments
    INSERT INTO clinic_payments (
        "id", "organizationId", "projectId", "invoiceId", "patientId", "paymentNumber",
        "paymentMethod", "amount", "currency", "referenceNumber", "recordedByUserId", "paymentDate", "createdAt"
    )
    VALUES
        (v_pay_1, v_org_id, v_proj_id, v_inv_1, v_pat_4, 'REC-2026-001',
         'CARD', 400.00, 'JOD', 'MADA-984210', v_user_id, now() - interval '2 days', now())
    ON CONFLICT ("id") DO NOTHING;

    -- 13. Governed AI Receptionist Proposals
    INSERT INTO clinic_ai_proposals (
        "id", "organizationId", "projectId", "patientId", "proposalType", "status",
        "model", "provider", "promptTemplate", "confidencePercent", "phiRedacted",
        "inputPayloadRedacted", "outputContent", "transparentFactors", "createdAt"
    )
    VALUES
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_1, 'VISIT_SUMMARY', 'DRAFT',
         'gpt-4o', 'azure-openai', 'dental-clinical-summary-v2', 96, true,
         '{"encounterType": "IMPLANT_CONSULT", "tooth": 46}'::jsonb,
         '{"title": "ملخص زيارة استشارة الزراعة", "summary": "تم فحص السن 46 والأشعة المقطعية CBCT. كثافة العظم مناسبة للزرع الفوري بعد الشفاء الكامل. المريضة لديها حساسية بنسلين وموصوفة كليندامايسين كبديل."}'::jsonb,
         '{"clinicalGuidelines": "AAOMS-2026", "confidenceScore": 0.96}'::jsonb,
         now() - interval '1 hour'),

        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_3, 'RECALL_PRIORITIZATION', 'DRAFT',
         'gpt-4o', 'azure-openai', 'dental-hygiene-recall-v1', 92, true,
         '{"lastHygieneMonths": 8, "riskLevel": "MODERATE"}'::jsonb,
         '{"title": "أولوية استدعاء دوري لتنظيف الأسنان", "recommendation": "المريض أتم 8 أشهر من آخر تنظيف، ويحتاج موعد إزالة الجير والفلورايد للوقاية من التهاب اللثة."}'::jsonb,
         '{"riskScore": 74, "priority": "HIGH"}'::jsonb,
         now() - interval '30 minutes')
    ON CONFLICT ("id") DO NOTHING;

END $$;
