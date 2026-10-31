SET client_encoding TO 'UTF8';

UPDATE clinic_resources SET name = 'Operatory 1 (كشف وتشخيص عام)' WHERE id = 'c0000001-0000-4000-a000-000000000001';
UPDATE clinic_resources SET name = 'Operatory 2 (جراحة وزراعة الأسنان)' WHERE id = 'c0000002-0000-4000-a000-000000000002';
UPDATE clinic_resources SET name = 'Operatory 3 (تقويم وتجميل الابتسامة)' WHERE id = 'c0000003-0000-4000-a000-000000000003';
UPDATE clinic_resources SET name = 'Operatory 4 (صحة الفم والوقاية)' WHERE id = 'c0000004-0000-4000-a000-000000000004';

UPDATE doctors SET name = 'د. إبراهيم الخالدي', specialty = 'جراحة وزراعة الأسنان', department = 'عيادة 2 - جراحة وزراعة الأسنان' WHERE id = 'd1111111-1111-4111-a111-111111111111';
UPDATE doctors SET name = 'د. نورة المنصور', specialty = 'علاج الجذور وعصب الأسنان', department = 'عيادة 1 - طب الأسنان التحفظي' WHERE id = 'd2222222-2222-4222-a222-222222222222';
UPDATE doctors SET name = 'د. فيصل القحطاني', specialty = 'تقويم الأسنان وتجميل الابتسامة', department = 'عيادة 3 - تقويم وتجميل الأسنان' WHERE id = 'd3333333-3333-4333-a333-333333333333';
UPDATE doctors SET name = 'د. منى السالم', specialty = 'طب أسنان الأطفال والوقاية', department = 'عيادة 4 - طب أسنان الأطفال' WHERE id = 'd4444444-4444-4444-a444-444444444444';

UPDATE clinic_locations SET name = 'الفرع الرئيسي (Main Branch)' WHERE id IN ('bffb97a2-80cd-0cff-5f9e-399c3978bb1e', '355b2cf3-0da7-4c20-b719-5691227174df');

UPDATE patients SET "displayName" = 'ليلى حسن', "givenName" = 'ليلى', "familyName" = 'حسن' WHERE id = 'a1111111-1111-4111-a111-111111111111';
UPDATE patients SET "displayName" = 'سارة أحمد', "givenName" = 'سارة', "familyName" = 'أحمد' WHERE id = 'a2222222-2222-4222-a222-222222222222';
UPDATE patients SET "displayName" = 'عمر ناصر', "givenName" = 'عمر', "familyName" = 'ناصر' WHERE id = 'a3333333-3333-4333-a333-333333333333';
UPDATE patients SET "displayName" = 'طارق العتيبي', "givenName" = 'طارق', "familyName" = 'العتيبي' WHERE id = 'a4444444-4444-4444-a444-444444444444';
UPDATE patients SET "displayName" = 'ريم الحربي', "givenName" = 'ريم', "familyName" = 'الحربي' WHERE id = 'a5555555-5555-4555-a555-555555555555';

UPDATE appointments SET "patientName" = 'ليلى حسن', "doctorName" = 'د. إبراهيم الخالدي', "service" = 'زراعة سن تيتانيوم متكاملة' WHERE id = 'b1111111-1111-4111-a111-111111111111';
UPDATE appointments SET "patientName" = 'سارة أحمد', "doctorName" = 'د. نورة المنصور', "service" = 'معالجة عصب الضرس مع الحشوة التجميلية' WHERE id = 'b2222222-2222-4222-a222-222222222222';
UPDATE appointments SET "patientName" = 'عمر ناصر', "doctorName" = 'د. منى السالم', "service" = 'تنظيف وتلميع وإزالة الجير للأسنان' WHERE id = 'b3333333-3333-4333-a333-333333333333';
UPDATE appointments SET "patientName" = 'طارق العتيبي', "doctorName" = 'د. فيصل القحطاني', "service" = 'تقويم أسنان شفاف Invisalign' WHERE id = 'b4444444-4444-4444-a444-444444444444';
UPDATE appointments SET "patientName" = 'ريم الحربي', "doctorName" = 'د. إبراهيم الخالدي', "service" = 'حشوة ضوئية تجميلية كمبوزيت' WHERE id = 'b5555555-5555-4555-a555-555555555555';
