-- =====================================================================
-- seed-clinic-demo.sql
-- =====================================================================
-- Populates the clinic tables of a HUMAN-FACING tenant with one demo
-- clinic: six clinicians, a procedure catalogue, sixty patients spread
-- across the three age bands the dashboard reports on, fourteen months
-- of appointments, and the invoices, invoice lines, payments and
-- clinical encounters behind them.
--
-- WHY THIS EXISTS
--
-- The clinic tables ship empty. A person signing in to see the clinic
-- dashboard sees six zeroes and an empty chart, and concludes the
-- dashboard is broken rather than the tenant is unseeded. Those are two
-- very different bugs and only one of them is a bug.
--
-- Every row is keyed to a stable md5() id, so re-running the script
-- REPLACES the same rows instead of creating a second clinic. The
-- appointment ids include the day and the slot, so the set is stable
-- across runs.
--
-- The wrapper (seed-clinic-demo.ps1) refuses the protected `firstelement`
-- database and resolves the organization and project from the seeded
-- accounts before it hands this file to psql.
--
--   psql -v org=<uuid> -v proj=<uuid> -v admin=<user uuid> -f seed-clinic-demo.sql
-- =====================================================================

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------
-- Clinicians
-- ---------------------------------------------------------------------
insert into doctors (id, "organizationId", "projectId", name, specialty, department,
                     email, phone, "workingDays", "startHour", "endHour", "slotDuration",
                     "isActive", "createdAt", "updatedAt")
select md5('demo-clinic-doctor-' || t.idx)::uuid,
       :'org', :'proj', t.name, t.specialty, t.department, t.email, t.phone,
       ARRAY[0,1,2,3,4,5]::int[], '09:00', '17:00', 30, true, now(), now()
  from (values
      (1, 'Dr. Amira Haddad',  'General Dentistry', 'Restorative', 'amira.haddad@human.invalid',  '+962791000001'),
      (2, 'Dr. Omar Khalil',   'Orthodontics',      'Alignment',   'omar.khalil@human.invalid',   '+962791000002'),
      (3, 'Dr. Lina Nasser',   'Endodontics',       'Root Canal',  'lina.nasser@human.invalid',   '+962791000003'),
      (4, 'Dr. Tarek Mansour', 'Periodontics',      'Gum Health',  'tarek.mansour@human.invalid', '+962791000004'),
      (5, 'Dr. Rania Saleh',   'Prosthodontics',    'Restoration', 'rania.saleh@human.invalid',   '+962791000005'),
      (6, 'Dr. Yousef Odeh',   'Oral Surgery',      'Extractions', 'yousef.odeh@human.invalid',   '+962791000006')
  ) as t(idx, name, specialty, department, email, phone)
on conflict (id) do update set
      name = excluded.name, specialty = excluded.specialty, department = excluded.department,
      email = excluded.email, phone = excluded.phone, "isActive" = excluded."isActive",
      "updatedAt" = now();

-- ---------------------------------------------------------------------
-- Procedure catalogue (the reference catalogue in the mockups is a
-- straight list of these with a price beside each one)
-- ---------------------------------------------------------------------
insert into procedures (id, "organizationId", "projectId", name, specialty,
                        "durationMinutes", "bufferMinutes", price, "isActive", "createdAt", "updatedAt")
select md5('demo-clinic-procedure-' || t.idx)::uuid,
       :'org', :'proj', t.name, t.specialty, t.minutes, t.buffer, t.price, true, now(), now()
  from (values
      ( 1, 'Consultation',             'General Dentistry',  30,  0,  30.000),
      ( 2, 'Scaling and Polishing',    'Periodontics',       45,  0,  60.000),
      ( 3, 'Composite Filling',        'Restorative',        45,  0,  95.000),
      ( 4, 'Root Canal Treatment',     'Endodontics',        90, 15, 220.000),
      ( 5, 'Crown Fitting',            'Prosthodontics',     60,  0, 340.000),
      ( 6, 'Tooth Extraction',         'Oral Surgery',       45, 10, 120.000),
      ( 7, 'Orthodontic Consultation', 'Orthodontics',       30,  0,  45.000),
      ( 8, 'Braces Fitting',           'Orthodontics',      120,  0, 480.000),
      ( 9, 'Whitening Session',        'General Dentistry',  60,  0, 150.000),
      (10, 'Denture Fitting',          'Prosthodontics',     75,  0, 275.000),
      (11, 'X-Ray Diagnostic',         'Oral Surgery',       15,  0,  25.000),
      (12, 'Fluoride Treatment',       'Periodontics',       20,  0,  20.000)
  ) as t(idx, name, specialty, minutes, buffer, price)
on conflict (id) do update set
      name = excluded.name, specialty = excluded.specialty,
      "durationMinutes" = excluded."durationMinutes", "bufferMinutes" = excluded."bufferMinutes",
      price = excluded.price, "isActive" = excluded."isActive", "updatedAt" = now();

-- ---------------------------------------------------------------------
-- Patients
--
-- Sixty patients. The ages are deliberate: twenty land under 26, twenty
-- between 26 and 50, twenty over 50, so the dashboard's three age bands
-- each have something to show. Their createdAt values run backwards over
-- a year, so "new patients in window" changes when the window changes.
--
-- Each patient takes one of the tenant's customers. That link is the only
-- thing joining a visit to a patient in this schema -- appointments carry
-- no patientId, and the backend's own recall report joins on
-- appointments.customerId = patients.customerId. Without it, "the patients
-- who visited" is an empty set however many appointments there are.
-- ---------------------------------------------------------------------
insert into patients (id, "organizationId", "projectId", "patientNo", "displayName",
                      "givenName", "familyName", "dateOfBirth", "primaryPhone", "primaryEmail",
                      locale, "customerId", "createdAt", "updatedAt")
select md5('demo-clinic-patient-' || g.i)::uuid,
       :'org', :'proj',
       'PT-' || lpad(g.i::text, 4, '0'),
       (array['Sara','Omar','Lina','Tarek','Rania','Yousef','Maya','Kareem','Noor','Zaid',
              'Hala','Fadi','Rana','Jamal','Dana','Bilal','Sana','Ayman','Reem','Nabil'])
         [1 + (g.i % 20)]
       || ' ' ||
       (array['Al-Mansour','Khalil','Nasser','Mansour','Saleh','Odeh','Bani','Haddad','Qassem','Faraj'])
         [1 + ((g.i / 20) % 10)],
       (array['Sara','Omar','Lina','Tarek','Rania','Yousef','Maya','Kareem','Noor','Zaid',
              'Hala','Fadi','Rana','Jamal','Dana','Bilal','Sana','Ayman','Reem','Nabil'])
         [1 + (g.i % 20)],
       (array['Al-Mansour','Khalil','Nasser','Mansour','Saleh','Odeh','Bani','Haddad','Qassem','Faraj'])
         [1 + ((g.i / 20) % 10)],
       (current_date
         - (case when g.i <= 20 then 8 + ((g.i * 7) % 18)
                 when g.i <= 40 then 26 + ((g.i * 5) % 25)
                 else 51 + ((g.i * 3) % 22) end || ' years')::interval
         - ((g.i % 12) || ' months')::interval)::date,
       '+96279' || lpad(g.i::text, 6, '0'),
       'patient' || lpad(g.i::text, 3, '0') || '@human.invalid',
       'en'::"Locale",
       (select c.id from customers c
         where c."organizationId" = :'org'
         order by c.id
         limit 1 offset (g.i - 1)),
       now() - (g.i * 6 || ' days')::interval,
       now()
  from generate_series(1, 60) as g(i)
on conflict (id) do update set
      "displayName" = excluded."displayName", "givenName" = excluded."givenName",
      "familyName" = excluded."familyName", "dateOfBirth" = excluded."dateOfBirth",
      "primaryPhone" = excluded."primaryPhone", "primaryEmail" = excluded."primaryEmail",
      locale = excluded.locale, "customerId" = excluded."customerId", "updatedAt" = now();

-- ---------------------------------------------------------------------
-- Appointments
--
-- Two slots per clinician per day, over 400 days of history and 14 days
-- of upcoming bookings. The slots are chosen so that no clinician is
-- ever double booked, because the table carries an exclusion constraint
-- that refuses overlapping ranges for the same doctor.
--
-- Past days land on COMPLETED, with a slice CANCELLED and a slice
-- NO_SHOW so the report's completion and cancellation rates are real.
-- Today and the future are CONFIRMED, which is what the dashboard's
-- "Upcoming Appointment" panel reads.
-- ---------------------------------------------------------------------
with days as (
    select generate_series(current_date - 400, current_date + 13, interval '1 day')::date as d
),
docs as (
    select id, name, row_number() over (order by id) - 1 as jn
      from doctors where "organizationId" = :'org'
),
procs as (
    select id, name, price, row_number() over (order by id) - 1 as pn
      from procedures where "organizationId" = :'org'
),
pats as (
    select id, "customerId", "displayName", "primaryPhone", "primaryEmail",
           row_number() over (order by id) - 1 as rn
      from patients where "organizationId" = :'org'
),
base as (
    select g.d,
           doc.jn,
           dk.k,
           proc.id   as proc_id,
           proc.name as proc_name,
           doc.id    as doc_id,
           doc.name  as doc_name,
           pat."displayName"  as patient_name,
           pat."primaryPhone" as patient_phone,
           pat."primaryEmail" as patient_email,
           pat."customerId"   as cust_id,
           g.d::timestamp + (case (((g.d - date '2000-01-01')::int + doc.jn + dk.k) % 4)
                                when 0 then interval '9 hours'
                                when 1 then interval '10 hours 30 minutes'
                                when 2 then interval '13 hours'
                                else interval '15 hours'
                            end) as appt_at,
           (((g.d - date '2000-01-01')::int * 13 + doc.jn * 7 + dk.k) % 25) as m
      from days g
      cross join generate_series(0, 5) as dj(j)
      cross join generate_series(0, 1) as dk(k)
      cross join lateral (select d.id, d.name, d.jn from docs d where d.jn = dj.j) doc
      cross join lateral (select p.id, p.name, p.price, p.pn from procs p
                           where p.pn = (((g.d - date '2000-01-01')::int * 7 + doc.jn * 3 + dk.k) % 12)) proc
      cross join lateral (select p.id, p."customerId", p."displayName", p."primaryPhone", p."primaryEmail", p.rn
                           from pats p
                          where p.rn = (((g.d - date '2000-01-01')::int * 11 + doc.jn * 5 + dk.k) % 60)) pat
)
insert into appointments (id, "organizationId", "projectId", "customerId", "doctorId", "procedureId",
                          title, "patientName", "patientPhone", "patientEmail", "appointmentDate",
                          "durationMinutes", status, service, "doctorName", "isAiProposed",
                          "ticketNumber", "ticketDate", "createdById", "createdAt", "updatedAt",
                          "cancelledBy", "cancelledReason", "cancelledAt")
select md5('demo-clinic-appt-' || b.d::text || '-' || b.jn::text || '-' || b.k::text)::uuid,
       :'org', :'proj', b.cust_id, b.doc_id, b.proc_id, b.proc_name,
       b.patient_name, b.patient_phone, b.patient_email, b.appt_at, 30,
       case when b.d >= current_date then 'CONFIRMED'
            when b.m in (0, 1) then 'CANCELLED'
            when b.m in (2, 3) then 'NO_SHOW'
            else 'COMPLETED' end,
       b.proc_name, b.doc_name, false,
       'TK-' || to_char(b.d, 'YYYYMMDD') || '-' || lpad((b.jn * 2 + b.k + 1)::text, 3, '0'),
       b.appt_at, :'admin'::uuid, b.appt_at, b.appt_at,
       case when b.d < current_date and b.m in (0, 1) then :'admin'::uuid end,
       case when b.d < current_date and b.m in (0, 1) then 'Patient rescheduled' end,
       case when b.d < current_date and b.m in (0, 1) then b.appt_at end
  from base b
on conflict (id) do update set
      "customerId" = excluded."customerId", "doctorId" = excluded."doctorId",
      "procedureId" = excluded."procedureId", title = excluded.title,
      "patientName" = excluded."patientName", "patientPhone" = excluded."patientPhone",
      "patientEmail" = excluded."patientEmail", "appointmentDate" = excluded."appointmentDate",
      status = excluded.status, service = excluded.service, "doctorName" = excluded."doctorName",
      "updatedAt" = now(),
      "cancelledBy" = excluded."cancelledBy", "cancelledReason" = excluded."cancelledReason",
      "cancelledAt" = excluded."cancelledAt";

-- ---------------------------------------------------------------------
-- Invoices and invoice lines
--
-- The report reads revenue from clinic_invoices.totalAmount, so this is
-- what fills the dashboard's Total Revenue card and its chart. Amounts
-- are spread across fourteen months so every month of the Year view
-- has a point.
-- ---------------------------------------------------------------------
with inv as (
    select g.n,
           md5('demo-clinic-invoice-' || g.n::text)::uuid as id,
           'INV-' || lpad(g.n::text, 6, '0') as num,
           (current_date - (case when g.n <= 120 then g.n % 30
                                 else 30 + ((g.n - 121) * 9) end) * interval '1 day')::timestamp
               + interval '10 hours' as issued_at,
           (40 + ((g.n * 37) % 850))::numeric(12,3) as amount
      from generate_series(1, 160) as g(n)
),
picked as (
    select inv.*,
           pat.id as patient_id,
           proc.id as proc_id,
           proc.name as proc_name
      from inv
      cross join lateral (select p.id from patients p
                           where p."organizationId" = :'org'
                             and p.id = md5('demo-clinic-patient-' || (1 + (inv.n % 60))::text)::uuid) pat
      cross join lateral (select p.id, p.name from procedures p
                           where p."organizationId" = :'org'
                             and p.id = md5('demo-clinic-procedure-' || (1 + (inv.n % 12))::text)::uuid) proc
),
priced as (
    select picked.*,
           case when n % 23 = 0 then 0.000 else amount * 0.10 end as discount,
           case when n % 23 = 0 then 'CANCELLED'
                when n % 3 = 0 then 'PAID'
                when n % 3 = 1 then 'PARTIALLY_PAID'
                else 'ISSUED' end as status
      from picked
)
insert into clinic_invoices (id, "organizationId", "projectId", "patientId", "invoiceNumber", currency,
                             subtotal, "discountAmount", "totalAmount", "paidAmount", "balanceDue",
                             status, notes, "issuedAt", "createdAt", "updatedAt")
select id, :'org', :'proj', patient_id, num, 'JOD',
       amount, discount, amount - discount,
       case when status = 'CANCELLED' then 0.000
            when status = 'PAID' then amount - discount
            when status = 'PARTIALLY_PAID' then round((amount - discount) * 0.5, 3)
            else 0.000 end,
       case when status = 'CANCELLED' then 0.000
            when status = 'PAID' then 0.000
            when status = 'PARTIALLY_PAID' then round((amount - discount) * 0.5, 3)
            else amount - discount end,
       status, 'Demo invoice', issued_at::timestamptz, now(), now()
  from priced
on conflict (id) do update set
      "patientId" = excluded."patientId", "invoiceNumber" = excluded."invoiceNumber",
      currency = excluded.currency, subtotal = excluded.subtotal,
      "discountAmount" = excluded."discountAmount", "totalAmount" = excluded."totalAmount",
      "paidAmount" = excluded."paidAmount", "balanceDue" = excluded."balanceDue",
      status = excluded.status, "issuedAt" = excluded."issuedAt", "updatedAt" = now();

with inv as (
    select g.n,
           md5('demo-clinic-invoice-' || g.n::text)::uuid as id,
           (40 + ((g.n * 37) % 850))::numeric(12,3) as amount,
           (case when g.n % 23 = 0 then 0.000 else (40 + ((g.n * 37) % 850))::numeric(12,3) * 0.10 end) as discount,
           (case when g.n % 23 = 0 then 'CANCELLED'
                 when g.n % 3 = 0 then 'PAID'
                 when g.n % 3 = 1 then 'PARTIALLY_PAID'
                 else 'ISSUED' end) as status
      from generate_series(1, 160) as g(n)
)
insert into clinic_invoice_items (id, "organizationId", "projectId", "invoiceId", "procedureId",
                                  "itemType", description, "unitPrice", quantity, "totalPrice", "createdAt")
select md5('demo-clinic-invoice-item-' || inv.n::text)::uuid,
       :'org', :'proj', inv.id,
       md5('demo-clinic-procedure-' || (1 + (inv.n % 12))::text)::uuid,
       'PROCEDURE',
       (select p.name from procedures p
         where p."organizationId" = :'org'
           and p.id = md5('demo-clinic-procedure-' || (1 + (inv.n % 12))::text)::uuid),
       inv.amount - inv.discount, 1, inv.amount - inv.discount, now()
  from inv
on conflict (id) do update set
      "invoiceId" = excluded."invoiceId", "procedureId" = excluded."procedureId",
      description = excluded.description, "unitPrice" = excluded."unitPrice",
      "totalPrice" = excluded."totalPrice";

-- ---------------------------------------------------------------------
-- Payments behind the collected revenue
-- ---------------------------------------------------------------------
with inv as (
    select g.n,
           md5('demo-clinic-invoice-' || g.n::text)::uuid as id,
           'PAY-' || lpad(g.n::text, 6, '0') as num,
           (current_date - (case when g.n <= 120 then g.n % 30
                                 else 30 + ((g.n - 121) * 9) end) * interval '1 day')::timestamp
               + interval '14 hours' as paid_at,
           (40 + ((g.n * 37) % 850))::numeric(12,3) as amount,
           (case when g.n % 23 = 0 then 0.000 else (40 + ((g.n * 37) % 850))::numeric(12,3) * 0.10 end) as discount,
           (case when g.n % 23 = 0 then 'CANCELLED'
                 when g.n % 3 = 0 then 'PAID'
                 when g.n % 3 = 1 then 'PARTIALLY_PAID'
                 else 'ISSUED' end) as status
      from generate_series(1, 160) as g(n)
),
due as (
    select inv.*,
           (inv.amount - inv.discount) as total,
           (case when inv.status = 'PAID' then inv.amount - inv.discount
                 when inv.status = 'PARTIALLY_PAID' then round((inv.amount - inv.discount) * 0.5, 3)
                 else 0.000 end) as paid
      from inv
)
insert into clinic_payments (id, "organizationId", "projectId", "invoiceId", "patientId", "paymentNumber",
                             amount, currency, "paymentMethod", "paymentDate", "recordedByUserId",
                             "idempotencyKey", "createdAt")
select md5('demo-clinic-payment-' || due.n::text)::uuid,
       :'org', :'proj', due.id,
       md5('demo-clinic-patient-' || (1 + (due.n % 60))::text)::uuid,
       due.num, due.paid, 'JOD',
       case when due.n % 2 = 0 then 'CARD' else 'CASH' end,
       due.paid_at::timestamptz, :'admin'::uuid,
       'demo-clinic-payment-' || due.n::text, now()
  from due
 where due.paid > 0
on conflict (id) do update set
      "invoiceId" = excluded."invoiceId", "patientId" = excluded."patientId",
      amount = excluded.amount, currency = excluded.currency,
      "paymentMethod" = excluded."paymentMethod", "paymentDate" = excluded."paymentDate",
      "recordedByUserId" = excluded."recordedByUserId";

-- ---------------------------------------------------------------------
-- Clinical encounters
--
-- One encounter for every fourth completed appointment in the last four
-- months, so the doctor productivity report has encounter counts next
-- to its appointment counts.
-- ---------------------------------------------------------------------
with picked as (
    select a.*, row_number() over (order by a."appointmentDate", a.id) as rn
      from appointments a
     where a."organizationId" = :'org'
       and a.status = 'COMPLETED'
       and a."appointmentDate" >= current_date - 120
)
insert into clinical_encounters (id, "organizationId", "projectId", "patientId", "appointmentId",
                                 "doctorId", "chiefComplaint", "examinationNotes", diagnosis,
                                 "clinicalNotes", "performedProcedures", status, "visitDate",
                                 "durationMinutes", "createdAt", "updatedAt")
select md5('demo-clinic-encounter-' || p.id::text)::uuid,
       :'org', :'proj',
       pat.id, p.id, p."doctorId",
       'Tooth pain and sensitivity on the upper left side',
       'Restoration intact, no mobility, tender to percussion.',
       'Irreversible pulpitis',
       'Root canal treatment completed in one visit. Patient advised on aftercare.',
       p.service, 'COMPLETED', p."appointmentDate"::timestamptz, 30,
       p."appointmentDate"::timestamptz, p."appointmentDate"::timestamptz
  from picked p
  join patients pat on pat."organizationId" = :'org' and pat."customerId" = p."customerId"
 where p.rn % 4 = 0
on conflict (id) do update set
      "patientId" = excluded."patientId", "appointmentId" = excluded."appointmentId",
      "doctorId" = excluded."doctorId", diagnosis = excluded.diagnosis,
      "visitDate" = excluded."visitDate", "updatedAt" = now();

-- ---------------------------------------------------------------------
-- Notifications
--
-- The bell in the shell reads `/api/v1/realtime/state`. Nothing in the
-- product creates a notification yet -- the service that writes these
-- rows is not wired to any flow -- so the table ships empty and the bell
-- is permanently silent, which a person cannot tell apart from a broken
-- bell. These rows are the same shape the service writes, so the demo
-- tenant has something truthful for the bell to show.
--
-- One unread and one read row per demo user, so the badge count and the
-- read/unread split are both visible.
-- ---------------------------------------------------------------------
insert into notifications (id, "organizationId", "projectId", "userId", type, title, body,
                           "resourceType", "resourceId", "readAt", "createdAt")
select md5('demo-notification-' || t.key)::uuid,
       :'org', :'proj', t."userId",
       t.type, t.title, t.body, t."resourceType", t."resourceId",
       t."readAt", now() - make_interval(mins => t.minutes)
  from (
      select 'admin-unread' as key, :'admin'::uuid as "userId",
             'CONVERSATION_ASSIGNED'::"NotificationType" as type,
             'Conversation assigned to you' as title,
             'A WhatsApp conversation was assigned to you and is waiting for a reply.' as body,
             'CONVERSATION'::text as "resourceType",
             null::uuid as "resourceId",
             null::timestamp as "readAt",
             8 as minutes
      union all
      select 'admin-read', :'admin'::uuid,
             'CONVERSATION_ESCALATED'::"NotificationType",
             'Conversation escalated',
             'A conversation was escalated to a senior agent.',
             'CONVERSATION'::text, null::uuid, now()::timestamp - interval '3 days', 4320
      union all
      select 'employee-unread', u.id,
             'CONVERSATION_ASSIGNED'::"NotificationType",
             'Conversation assigned to you',
             'A WhatsApp conversation was assigned to you and is waiting for a reply.',
             'CONVERSATION'::text, null::uuid, null::timestamp, 11
        from users u
       where u."organizationId" = :'org'
         and u.role = 'EMPLOYEE'
         and u."isActive"
  ) t
on conflict (id) do update set
      "projectId" = excluded."projectId", "userId" = excluded."userId",
      type = excluded.type, title = excluded.title, body = excluded.body,
      "resourceType" = excluded."resourceType", "resourceId" = excluded."resourceId",
      "readAt" = excluded."readAt", "createdAt" = excluded."createdAt";
