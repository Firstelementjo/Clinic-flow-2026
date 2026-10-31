-- Comprehensive Arabic UTF-8 Data Activation Script for ToothLogic Dental Practice Platform

DO $$
DECLARE
    v_org_id UUID := 'c1384761-1a14-4bd4-aa11-4105b2aebc6b';
    v_proj_id UUID := '11cfe1a0-8b85-4e2a-a817-c78a658b505a';
    v_user_id UUID := 'dd5afcaa-e4e2-4690-a471-d9955aa6f911';

    v_res_1 UUID := 'c0000001-0000-4000-a000-000000000001';
    v_res_2 UUID := 'c0000002-0000-4000-a000-000000000002';
    v_res_3 UUID := 'c0000003-0000-4000-a000-000000000003';
    v_res_4 UUID := 'c0000004-0000-4000-a000-000000000004';

    v_doc_1 UUID := 'd1111111-1111-4111-a111-111111111111';
    v_doc_2 UUID := 'd2222222-2222-4222-a222-222222222222';
    v_doc_3 UUID := 'd3333333-3333-4333-a333-333333333333';
    v_doc_4 UUID := 'd4444444-4444-4444-a444-444444444444';

    v_pat_1 UUID := 'a1111111-1111-4111-a111-111111111111';
    v_pat_2 UUID := 'a2222222-2222-4222-a222-222222222222';
    v_pat_3 UUID := 'a3333333-3333-4333-a333-333333333333';
    v_pat_4 UUID := 'a4444444-4444-4444-a444-444444444444';
    v_pat_5 UUID := 'a5555555-5555-4555-a555-555555555555';

    v_apt_1 UUID := 'b1111111-1111-4111-a111-111111111111';
    v_apt_2 UUID := 'b2222222-2222-4222-a222-222222222222';
    v_apt_3 UUID := 'b3333333-3333-4333-a333-333333333333';
    v_apt_4 UUID := 'b4444444-4444-4444-a444-444444444444';
    v_apt_5 UUID := 'b5555555-5555-4555-a555-555555555555';

    v_plan_1 UUID := 'f1111111-1111-4111-a111-111111111111';
    v_enc_1 UUID := '71111111-1111-4111-a111-111111111111';
    v_inv_1 UUID := '91111111-1111-4111-a111-111111111111';
    v_inv_2 UUID := '92222222-2222-4222-a222-222222222222';
BEGIN
    -- 1. Capabilities
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

    -- 2. Clean Arabic in Appointments
    UPDATE appointments
    SET title = 'زراعة سن - ليلى حسن',
        service = 'زراعة سن تيتانيوم متكاملة',
        "patientName" = 'ليلى حسن',
        "doctorName" = 'د. إبراهيم الخالدي'
    WHERE id = v_apt_1;

    UPDATE appointments
    SET title = 'علاج عصب - سارة أحمد',
        service = 'معالجة عصب الضرس مع الحشوة التجميلية',
        "patientName" = 'سارة أحمد',
        "doctorName" = 'د. نورة المنصور'
    WHERE id = v_apt_2;

    UPDATE appointments
    SET title = 'تنظيف أسنان - عمر ناصر',
        service = 'تنظيف وتلميع وإزالة الجير للأسنان',
        "patientName" = 'عمر ناصر',
        "doctorName" = 'د. منى السالم'
    WHERE id = v_apt_3;

    UPDATE appointments
    SET title = 'جلسة تقويم - طارق العتيبي',
        service = 'تقويم أسنان شفاف Invisalign',
        "patientName" = 'طارق العتيبي',
        "doctorName" = 'د. فيصل القحطاني'
    WHERE id = v_apt_4;

    UPDATE appointments
    SET title = 'حشوة كمبوزيت - ريم الحربي',
        service = 'حشوة ضوئية تجميلية كمبوزيت',
        "patientName" = 'ريم الحربي',
        "doctorName" = 'د. إبراهيم الخالدي'
    WHERE id = v_apt_5;

    -- 3. Medical Histories
    UPDATE patient_medical_histories
    SET allergies = 'حساسية مفرطة من البنسلين واللاتكس',
        conditions = 'ربو تحسسي خفيف',
        "currentMedications" = 'Ventolin Inhaler عند اللزوم',
        notes = 'يمنع استخدام أي مضاد حيوي من عائلة البنسلين'
    WHERE "patientId" = v_pat_1;

    UPDATE patient_medical_histories
    SET allergies = NULL,
        conditions = 'سكري النوع الثاني، ارتفاع ضغط دم',
        "currentMedications" = 'Metformin 500mg, Concor 5mg',
        notes = 'يجب قياس الضغط والسكر قبل أي إجراء جراحي'
    WHERE "patientId" = v_pat_2;

    UPDATE patient_medical_histories
    SET allergies = NULL,
        conditions = 'سيولة دم خفيفة، مميعات دم',
        "currentMedications" = 'Aspirin 81mg',
        notes = 'إيقاف الأسبرين بتنسيق طبي قبل الجراحة'
    WHERE "patientId" = v_pat_4;

    -- 4. Clinical Encounters
    UPDATE clinical_encounters
    SET "chiefComplaint" = 'ألم حاد متقطع مع المشروبات الباردة والساخنة بالفك السفلي الأيمن',
        "examinationNotes" = 'تسوس عميق واصل لغرفة العصب في الضرس رقم 46 مع حساسية للقرع العمودي',
        diagnosis = 'K04.0 التهاب لب السن الحاد Irreversible Pulpitis',
        "performedProcedures" = 'تخدير موضعي، عزل بالحاجز المطاطي، فتح الحجرة اللبية واستئصال العصب، تنظيف وتشكيل القنوات، ووضع ضماد طبي مؤقت'
    WHERE id = v_enc_1;

    -- 5. Treatment Plans
    UPDATE treatment_plans
    SET title = 'خطة تأهيل الفم الكامل وزراعة الأضراس'
    WHERE id = v_plan_1;

    -- 6. Treatment Plan Items
    UPDATE treatment_plan_items
    SET "procedureName" = 'معالجة عصب الضرس المرحلة الأولى',
        notes = 'تنظيف القنوات ووضع حشوة مؤقتة'
    WHERE id = '5169e74a-1820-4189-b7ea-c3de6bdef5f2';

    UPDATE treatment_plan_items
    SET "procedureName" = 'زراعة سن تيتانيوم متكاملة مع التاج',
        notes = 'زرع الغرسة والدعامة بعد التئام العظم'
    WHERE id = '59919c89-a269-4510-afa7-cbe4c8232852';

    -- 7. Invoice Items
    UPDATE clinic_invoice_items
    SET description = 'زراعة سن تيتانيوم متكاملة مع الدعامة'
    WHERE id = '0f41abd6-226f-4ca4-b82b-2dabf73d5bab';

    UPDATE clinic_invoice_items
    SET description = 'معالجة عصب الضرس المرحلة الأولى والثانية'
    WHERE id = '61986a3a-e4a6-44b7-be8f-bad34b5ea2be';

    -- 8. Odontogram Findings (Append-only table: insert new findings for Patient 1)
    INSERT INTO patient_odontogram_findings (
        "id", "organizationId", "projectId", "patientId", "toothFdi", "scope", "surfaces",
        "code", "state", "source", "recordedBy", "recordedAt", "createdAt", "note"
    )
    VALUES
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_1, 46, 'TOOTH'::"FindingScope", NULL,
         'CARIES-ICDAS-4', 'EXISTING'::"FindingState", 'CLINICAL'::"FindingSource", v_user_id, now(), now(), 'تسوس عميق واصل للعصب مع التهاب لب'),
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_1, 16, 'SURFACE'::"FindingScope", ARRAY['O'::"FindingSurface"],
         'RESTORATION-FILLING', 'COMPLETED'::"FindingState", 'CLINICAL'::"FindingSource", v_user_id, now(), now(), 'حشوة كمبوزيت سابقة بحالة ممتازة'),
        (gen_random_uuid(), v_org_id, v_proj_id, v_pat_1, 21, 'TOOTH'::"FindingScope", NULL,
         'PROSTHETIC-CROWN', 'PLANNED'::"FindingState", 'CLINICAL'::"FindingSource", v_user_id, now(), now(), 'تاج زيركون تجميلي مخطط له');

    -- 9. AI Proposals
    DELETE FROM clinic_ai_proposals WHERE "organizationId" = v_org_id;

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
         now() - interval '30 minutes');

END $$;
