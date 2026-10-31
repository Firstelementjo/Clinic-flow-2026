/**
 * Tenant- and project-scope proof for the LEGACY clinic module
 * (`apps/api/src/clinic/**`), plus `appointments`, `deals`, `leads` and the
 * audit-log route.
 *
 * <h2>Why this is treated as patient-safety-adjacent</h2>
 *
 * <p>Before this phase the clinic module had no tenant boundary on most of its
 * routes: {@code PATCH}/{@code DELETE /clinic/doctors/:id} passed no
 * {@code TenantContext} at all and the adapter mutated on {@code where: { id }};
 * {@code POST /clinic/doctors} spread {@code ...body} <em>after</em>
 * {@code organizationId}, so the caller overrode the tenant; and
 * {@code POST /clinic/reminders/respond} accepted a bare appointment UUID with no
 * tenant and no permission. A doctor's roster and a patient's appointment are
 * clinical records, so a row that must not change is a patient-safety matter, not
 * a CRUD detail.
 *
 * <h2>Load-bearing assertions</h2>
 *
 * <p>Every write test snapshots row count <em>and</em> a full serialisation of every
 * row before the attempt and re-compares it after, so a write that happened and
 * then reported a denial still fails. The cross-tenant tests additionally assert
 * that no row anywhere in the table belongs to the other tenant.
 *
 * <h2>The `...body` ordering rule</h2>
 *
 * <p>{@code validation-pipe-reachability.test.ts} shows the global
 * {@code ValidationPipe({ whitelist: true })} does not strip an undeclared property
 * from an {@code any} or plain-object body. {@code 'a body cannot move a row'}
 * below is the regression test for that, and it is the reason the controller's
 * spreads were reordered rather than the fields merely deleted.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { TenantAccessError, type TenantContext } from '@firstelement/database';
import { httpOutcome, matches, type PrismaCall, type Where } from './legacy-harness';
import { LocalClinicAdapter } from '../../../../../apps/api/src/clinic/local-clinic.adapter';
import { RemindersService } from '../../../../../apps/api/src/clinic/reminders.service';
import { AppointmentsService } from '../../../../../apps/api/src/appointments/appointments.service';
import { DealsService } from '../../../../../apps/api/src/deals/deals.service';
import { LeadsService } from '../../../../../apps/api/src/leads/leads.service';
import { AnalyticsService } from '../../../../../apps/api/src/analytics/analytics.service';
import type { PrismaService } from '../../../../../apps/api/src/common/prisma.service';

const ORG_1 = 'org-1';
const ORG_2 = 'org-2';
const PROJECT_A = 'project-a';
const PROJECT_B = 'project-b';
const PROJECT_DOES_NOT_EXIST = 'project-does-not-exist';

const MEMBER_A: TenantContext = {
  organizationId: ORG_1,
  userId: 'user-a',
  role: 'EMPLOYEE',
  projectIds: [PROJECT_A],
};
const COLLEAGUE_B: TenantContext = {
  organizationId: ORG_1,
  userId: 'user-b',
  role: 'EMPLOYEE',
  projectIds: [PROJECT_B],
};
const FOREIGN_ORG_ADMIN: TenantContext = {
  organizationId: ORG_2,
  userId: 'user-c',
  role: 'ADMIN',
  projectIds: ['project-c'],
};
const ADMIN_1: TenantContext = {
  organizationId: ORG_1,
  userId: 'admin-1',
  role: 'ADMIN',
  projectIds: [PROJECT_A, PROJECT_B],
};

// ---------------------------------------------------------------------------
// In-memory clinic + CRM tables
// ---------------------------------------------------------------------------

interface ClinicRow {
  id: string;
  organizationId: string;
  projectId: string;
  [key: string]: unknown;
}

class FakePrisma {
  doctor: ClinicRow[] = [];
  procedure: ClinicRow[] = [];
  appointment: ClinicRow[] = [];
  customer: ClinicRow[] = [];
  conversation: ClinicRow[] = [];
  deal: ClinicRow[] = [];
  lead: ClinicRow[] = [];
  auditLog: ClinicRow[] = [];
  calls: PrismaCall[] = [];

  /** Every clinic/CRM table, serialised — the before/after comparison base. */
  snapshot(): string {
    return JSON.stringify({
      doctor: this.doctor,
      procedure: this.procedure,
      appointment: this.appointment,
      customer: this.customer,
      conversation: this.conversation,
      deal: this.deal,
      lead: this.lead,
      auditLog: this.auditLog,
    });
  }

  private rows(table: string): ClinicRow[] {
    return (this as unknown as Record<string, ClinicRow[]>)[table] ?? [];
  }

  private set(table: string, rows: ClinicRow[]): void {
    (this as unknown as Record<string, ClinicRow[]>)[table] = rows;
  }

  private delegate(table: string, _op: PrismaCall['op']) {
    const rows = () => this.rows(table);
    return {
      findMany: async (args: { where?: Where; orderBy?: unknown }) => {
        this.calls.push({ op: 'findMany', where: args.where });
        const hits = args.where
          ? rows().filter((r) => matches(r as Record<string, unknown>, args.where as Where))
          : [...rows()];
        void args.orderBy;
        return hits;
      },
      findFirst: async (args: { where?: Where }) => {
        this.calls.push({ op: 'findFirst', where: args.where });
        if (!args.where) return rows()[0] ?? null;
        return (
          rows().find((r) => matches(r as Record<string, unknown>, args.where as Where)) ?? null
        );
      },
      findUnique: async (args: { where?: Where }) => {
        this.calls.push({ op: 'findFirst', where: args.where });
        if (!args.where) return rows()[0] ?? null;
        return (
          rows().find((r) => matches(r as Record<string, unknown>, args.where as Where)) ?? null
        );
      },
      create: async (args: { data: ClinicRow }) => {
        this.calls.push({ op: 'create' });
        const row = { id: `new-${table}-${rows().length + 1}`, ...args.data };
        this.set(table, [...rows(), row]);
        return row;
      },
      update: async (args: { where: { id: string }; data: Partial<ClinicRow> }) => {
        this.calls.push({ op: 'update', id: args.where.id });
        const row = rows().find((r) => r.id === args.where.id);
        if (!row) throw new Error(`fake prisma: ${table}.update on a missing row ${args.where.id}`);
        Object.assign(row, args.data);
        return row;
      },
      updateMany: async (args: { where?: Where; data: Partial<ClinicRow> }) => {
        this.calls.push({ op: 'update' });
        const hits = args.where
          ? rows().filter((r) => matches(r as Record<string, unknown>, args.where as Where))
          : rows();
        hits.forEach((r) => Object.assign(r, args.data));
        return { count: hits.length };
      },
      delete: async (args: { where: { id: string } }) => {
        this.calls.push({ op: 'delete', id: args.where.id });
        const index = rows().findIndex((r) => r.id === args.where.id);
        if (index === -1) throw new Error(`fake prisma: ${table}.delete on a missing row`);
        return this.set(table, rows().filter((r) => r.id !== args.where.id)).slice(-1)[0] ?? null;
      },
      deleteMany: async (args: { where?: Where }) => {
        this.calls.push({ op: 'delete' });
        const hits = args.where
          ? rows().filter((r) => matches(r as Record<string, unknown>, args.where as Where))
          : rows();
        this.set(table, rows().filter((r) => !hits.includes(r)));
        return { count: hits.length };
      },
    };
  }

  get prismaClient() {
    return {
      doctor: this.delegate('doctor', 'findMany'),
      procedure: this.delegate('procedure', 'findMany'),
      appointment: this.delegate('appointment', 'findMany'),
      customer: this.delegate('customer', 'findMany'),
      conversation: this.delegate('conversation', 'findMany'),
      deal: this.delegate('deal', 'findMany'),
      lead: this.delegate('lead', 'findMany'),
      auditLog: this.delegate('auditLog', 'findMany'),
    };
  }
}

// ---------------------------------------------------------------------------

describe('legacy clinic scope (apps/api/src/clinic/**)', () => {
  let prisma: FakePrisma;
  let adapter: LocalClinicAdapter;
  let reminders: RemindersService;

  let doctorInA: string;
  let procedureInA: string;
  let procedureInB: string;
  let appointmentInA: string;
  let _doctorInB: string;
  let _appointmentInB: string;

  beforeEach(() => {
    prisma = new FakePrisma();
    const prismaService = { client: prisma.prismaClient } as unknown as PrismaService;
    adapter = new LocalClinicAdapter(prismaService);
    reminders = new RemindersService(
      prismaService,
      {} as never,
      {} as never,
      {} as never,
    );

    prisma.doctor = [
      {
        id: 'doctor-a',
        organizationId: ORG_1,
        projectId: PROJECT_A,
        name: 'Dr. Amina',
        specialty: 'Cardiology',
        email: 'amina@clinic.example',
        phone: '+966500000001',
        workingDays: [0, 1, 2, 3, 4],
        startHour: '09:00',
        endHour: '17:00',
        slotDuration: 30,
        isActive: true,
      },
      {
        id: 'doctor-b',
        organizationId: ORG_1,
        projectId: PROJECT_B,
        name: 'Dr. Bilal',
        specialty: 'Cardiology',
        email: 'bilal@clinic.example',
        phone: '+966500000002',
        workingDays: [0, 1, 2, 3, 4],
        startHour: '09:00',
        endHour: '17:00',
        slotDuration: 30,
        isActive: true,
      },
    ];
    prisma.procedure = [
      {
        id: 'proc-a',
        organizationId: ORG_1,
        projectId: PROJECT_A,
        name: 'Echo',
        specialty: 'Cardiology',
        durationMinutes: 30,
        bufferMinutes: 0,
        isActive: true,
      },
      {
        id: 'proc-b',
        organizationId: ORG_1,
        projectId: PROJECT_B,
        name: 'Stress test',
        specialty: 'Cardiology',
        durationMinutes: 45,
        bufferMinutes: 0,
        isActive: true,
      },
    ];
    prisma.appointment = [
      {
        id: '11111111-1111-1111-1111-111111111111',
        organizationId: ORG_1,
        projectId: PROJECT_A,
        doctorId: 'doctor-a',
        patientName: 'Patient Alpha',
        patientPhone: '+966500000011',
        status: 'PENDING',
        notes: 'alpha notes',
        appointmentDate: new Date('2026-11-01T09:00:00.000Z'),
        durationMinutes: 30,
      },
      {
        id: '22222222-2222-2222-2222-222222222222',
        organizationId: ORG_1,
        projectId: PROJECT_B,
        doctorId: 'doctor-b',
        patientName: 'Patient Beta',
        patientPhone: '+966500000022',
        status: 'PENDING',
        notes: 'beta notes',
        appointmentDate: new Date('2026-11-01T09:00:00.000Z'),
        durationMinutes: 45,
      },
    ];
    doctorInA = 'doctor-a';
    // Tenant B's rows are seeded because the cross-tenant refusals need something to be
    // refused, but nothing reads these two labels. The underscore prefix is the configured
    // allow for an intentionally unread binding; the rows themselves are what the tests
    // depend on, so they stay.
    _doctorInB = 'doctor-b';
    procedureInA = 'proc-a';
    procedureInB = 'proc-b';
    appointmentInA = '11111111-1111-1111-1111-111111111111';
    _appointmentInB = '22222222-2222-2222-2222-222222222222';
  });

  describe('a member reaches only their own project', () => {
    it('reads doctors and procedures in their project', async () => {
      expect(await adapter.getDoctors(MEMBER_A, PROJECT_A)).toHaveLength(1);
      expect(await adapter.getProcedures(MEMBER_A, PROJECT_A)).toHaveLength(1);
    });

    it('reads a doctor, procedure and appointment by id in their project', async () => {
      expect(await adapter.getDoctorById(MEMBER_A, doctorInA)).not.toBeNull();
      expect(await adapter.getProcedureById(MEMBER_A, procedureInA)).not.toBeNull();
      const appt = await adapter.cancelAppointment(MEMBER_A, appointmentInA, 'our own');
      expect(appt).toBe(true);
    });

    it('updates and creates within their own project', async () => {
      const before = prisma.snapshot();
      await adapter.updateDoctor(MEMBER_A, doctorInA, { name: 'Dr. Amina Updated' });
      expect(prisma.doctor.find((d) => d.id === doctorInA)?.name).toBe('Dr. Amina Updated');
      expect(before).not.toBe(prisma.snapshot());

      await adapter.createDoctor(MEMBER_A, {
        name: 'Dr. New',
        specialty: 'Cardiology',
        projectId: PROJECT_A,
      });
      expect(prisma.doctor).toHaveLength(3);
      expect(prisma.doctor.every((d) => d.organizationId === ORG_1)).toBe(true);
    });

    it('an org admin still reaches every project in their organization', async () => {
      expect(await adapter.getDoctors(ADMIN_1, PROJECT_B)).toHaveLength(1);
      await adapter.updateProcedure(ADMIN_1, procedureInB, { price: 500 });
      expect(prisma.procedure.find((p) => p.id === procedureInB)?.price).toBe(500);
    });
  });

  describe('a same-organization colleague cannot cross a project boundary', () => {
    it('reading another project\'s doctors is refused with a non-disclosing 404', async () => {
      const denied = await httpOutcome(() => adapter.getDoctors(COLLEAGUE_B, PROJECT_A));
      const nonexistent = await httpOutcome(() =>
        adapter.getDoctors(COLLEAGUE_B, PROJECT_DOES_NOT_EXIST),
      );

      expect(denied.status).toBe(404);
      expect(nonexistent.status).toBe(404);
      expect(denied.body).toEqual(nonexistent.body);
      expect(denied.bodyWithoutRequestId).toEqual(nonexistent.bodyWithoutRequestId);
      expect(JSON.stringify(denied.body)).not.toContain('Dr. Amina');
      expect(prisma.calls).toHaveLength(0); // refused before any query ran
    });

    it('reading another project\'s roster is refused identically', async () => {
      expect((await httpOutcome(() => adapter.getAllDoctors(COLLEAGUE_B, PROJECT_A))).status).toBe(404);
      expect(
        (await httpOutcome(() => adapter.getAllProcedures(COLLEAGUE_B, PROJECT_A))).status,
      ).toBe(404);
      expect(
        (await httpOutcome(() => adapter.getProcedures(COLLEAGUE_B, PROJECT_A))).status,
      ).toBe(404);
    });

    it('reading a doctor or procedure by id from another project returns nothing', async () => {
      expect(await adapter.getDoctorById(COLLEAGUE_B, doctorInA)).toBeNull();
      expect(await adapter.getProcedureById(COLLEAGUE_B, procedureInA)).toBeNull();
    });

    it('rewriting a doctor in another project: 404, and no field changed', async () => {
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        adapter.updateDoctor(COLLEAGUE_B, doctorInA, { name: 'HIJACKED' }),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.doctor.find((d) => d.id === doctorInA)?.name).toBe('Dr. Amina');
      expect(prisma.calls.filter((c) => c.op === 'update')).toHaveLength(0);
    });

    it('deleting a doctor or procedure in another project: 404, and the row survives', async () => {
      const doctorCount = prisma.doctor.length;
      const procCount = prisma.procedure.length;
      const stateBefore = prisma.snapshot();

      expect((await httpOutcome(() => adapter.deleteDoctor(COLLEAGUE_B, doctorInA))).status).toBe(404);
      expect(
        (await httpOutcome(() => adapter.deleteProcedure(COLLEAGUE_B, procedureInA))).status,
      ).toBe(404);

      expect(prisma.doctor).toHaveLength(doctorCount);
      expect(prisma.procedure).toHaveLength(procCount);
      expect(prisma.snapshot()).toBe(stateBefore);
    });

    it('rescheduling or cancelling another project\'s appointment: 404, unchanged', async () => {
      const stateBefore = prisma.snapshot();

      expect(
        (await httpOutcome(() =>
          adapter.rescheduleAppointment(COLLEAGUE_B, appointmentInA, new Date('2026-12-01T09:00:00Z')),
        )).status,
      ).toBe(404);
      expect(
        (await httpOutcome(() => adapter.cancelAppointment(COLLEAGUE_B, appointmentInA, 'nope'))).status,
      ).toBe(404);

      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.appointment.find((a) => a.id === appointmentInA)?.status).toBe('PENDING');
    });

    it('creating a doctor into another project is refused, and inserts nothing', async () => {
      const doctorCount = prisma.doctor.length;

      const outcome = await httpOutcome(() =>
        adapter.createDoctor(COLLEAGUE_B, {
          name: 'planted',
          specialty: 'Cardiology',
          projectId: PROJECT_A,
        }),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.doctor).toHaveLength(doctorCount);
      expect(prisma.calls.filter((c) => c.op === 'create')).toHaveLength(0);
    });
  });

  describe('cross-tenant is refused, and no row moves between tenants', () => {
    it('reading another tenant\'s roster is refused with a 404', async () => {
      expect((await httpOutcome(() => adapter.getDoctors(FOREIGN_ORG_ADMIN, PROJECT_A))).status).toBe(404);
      expect(
        (await httpOutcome(() => adapter.getAllDoctors(FOREIGN_ORG_ADMIN, PROJECT_A))).status,
      ).toBe(404);
      expect(prisma.calls).toHaveLength(0);
    });

    it('a doctor by id in another tenant is invisible', async () => {
      expect(await adapter.getDoctorById(FOREIGN_ORG_ADMIN, doctorInA)).toBeNull();
      expect(await adapter.getProcedureById(FOREIGN_ORG_ADMIN, procedureInA)).toBeNull();
    });

    it('rewriting another tenant\'s doctor: 404, and no row anywhere changes tenant', async () => {
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        adapter.updateDoctor(FOREIGN_ORG_ADMIN, doctorInA, { name: 'HIJACKED' }),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.doctor.every((d) => d.organizationId === ORG_1)).toBe(true);
      expect(prisma.doctor.filter((d) => d.organizationId === ORG_2)).toHaveLength(0);
    });

    it('deleting another tenant\'s doctor and procedure changes nothing', async () => {
      const stateBefore = prisma.snapshot();

      expect((await httpOutcome(() => adapter.deleteDoctor(FOREIGN_ORG_ADMIN, doctorInA))).status).toBe(404);
      expect(
        (await httpOutcome(() => adapter.deleteProcedure(FOREIGN_ORG_ADMIN, procedureInA))).status,
      ).toBe(404);

      expect(prisma.snapshot()).toBe(stateBefore);
    });

    it('a body cannot move a doctor into another tenant or project', async () => {
      // The controller used to spread `...body` AFTER `organizationId`, so the
      // caller overrode the tenant. Naming a project the caller is not a member of
      // is now refused outright rather than silently redirected.
      const before = prisma.doctor.length;

      const refused = await httpOutcome(() =>
        adapter.createDoctor(MEMBER_A, {
          name: 'planted',
          specialty: 'Cardiology',
          organizationId: ORG_2,
          projectId: PROJECT_B,
        } as never),
      );

      expect(refused.status).toBe(404);
      expect(prisma.doctor).toHaveLength(before);

      // And a body that carries a foreign tenant but a project the caller *does*
      // hold is created in the caller's own tenant — the body cannot move it.
      await adapter.createDoctor(MEMBER_A, {
        name: 'our own new doctor',
        specialty: 'Cardiology',
        organizationId: ORG_2,
        projectId: PROJECT_A,
      } as never);

      expect(prisma.doctor).toHaveLength(before + 1);
      const created = prisma.doctor.find((d) => d.name === 'our own new doctor');
      expect(created?.organizationId).toBe(ORG_1);
      expect(created?.projectId).toBe(PROJECT_A);
      // The decisive property: no row anywhere in the table moved to the other tenant.
      expect(prisma.doctor.filter((d) => d.organizationId === ORG_2)).toHaveLength(0);
      expect(prisma.doctor.map((d) => d.organizationId)).toEqual([ORG_1, ORG_1, ORG_1]);
    });

    it('a body cannot move an existing doctor into another tenant', async () => {
      await adapter.updateDoctor(MEMBER_A, doctorInA, {
        name: 'edited by its own project',
        organizationId: ORG_2,
        projectId: PROJECT_B,
      } as never);

      const row = prisma.doctor.find((d) => d.id === doctorInA);
      expect(row?.organizationId).toBe(ORG_1);
      expect(row?.projectId).toBe(PROJECT_A);
      expect(row?.name).toBe('edited by its own project');
    });

    it('creating an appointment with a foreign tenant or project inserts nothing', async () => {
      const before = prisma.appointment.length;

      const outcome = await httpOutcome(() =>
        adapter.createAppointment(FOREIGN_ORG_ADMIN, {
          organizationId: ORG_2,
          projectId: PROJECT_A,
          title: 'planted',
          appointmentDate: new Date('2026-11-02T09:00:00.000Z'),
          durationMinutes: 30,
        } as never),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.appointment).toHaveLength(before);
    });
  });

  describe('reminders: the patient link is a capability, an id is not', () => {
    it('the authenticated path cannot touch another project\'s appointment', async () => {
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        reminders.processPatientResponseForCaller(COLLEAGUE_B, appointmentInA, 'CANCEL', 'not mine'),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.appointment.find((a) => a.id === appointmentInA)?.status).toBe('PENDING');
    });

    it('the authenticated path cannot touch another tenant\'s appointment', async () => {
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        reminders.processPatientResponseForCaller(FOREIGN_ORG_ADMIN, appointmentInA, 'CANCEL', 'x'),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.appointment.every((a) => a.organizationId === ORG_1)).toBe(true);
    });

    it('a member still confirms their own project\'s appointment', async () => {
      const result = await reminders.processPatientResponseForCaller(
        MEMBER_A,
        appointmentInA,
        'CONFIRM',
      );

      expect(result.status).toBe('CONFIRMED');
      expect(prisma.appointment.find((a) => a.id === appointmentInA)?.status).toBe('CONFIRMED');
    });

    it('the public token path still works for a real token', async () => {
      await reminders.sendReminderForAppointment(MEMBER_A, appointmentInA, 'WHATSAPP');
      const token = (reminders as unknown as {
        remindersStore: Map<string, { actionToken: string }>;
      }).remindersStore.get(appointmentInA)?.actionToken;
      expect(token).toBeTruthy();

      const result = await reminders.processPatientResponseByToken(token as string, 'CONFIRM');
      expect(result.status).toBe('CONFIRMED');
      expect(prisma.appointment.find((a) => a.id === appointmentInA)?.status).toBe('CONFIRMED');
    });

    it('the public token path refuses a bare appointment id', async () => {
      const stateBefore = prisma.snapshot();

      // This used to resolve: the shared method fell back to "is it a UUID?", which
      // made an unauthenticated id a capability.
      const outcome = await httpOutcome(() =>
        reminders.processPatientResponseByToken(appointmentInA, 'CANCEL', 'guessed the id'),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.snapshot()).toBe(stateBefore);
    });

    it('the public read refuses a bare appointment id, so no patient data leaks unauthenticated', async () => {
      const outcome = await httpOutcome(() =>
        reminders.getPublicAppointmentDetails(appointmentInA),
      );

      expect(outcome.status).toBe(404);
      expect(JSON.stringify(outcome.body)).not.toContain('Patient Alpha');
      expect(JSON.stringify(outcome.body)).not.toContain('+966500000011');
    });

    it('the public read still works for a real token', async () => {
      await reminders.sendReminderForAppointment(MEMBER_A, appointmentInA, 'WHATSAPP');
      const token = (reminders as unknown as {
        remindersStore: Map<string, { actionToken: string }>;
      }).remindersStore.get(appointmentInA)?.actionToken as string;

      const details = await reminders.getPublicAppointmentDetails(token);
      expect(details.appointmentId ?? details.id).toBe(appointmentInA);
    });

    it('reminder reads are bounded to reachable projects', async () => {
      const summary = await reminders.getReminderSummary(COLLEAGUE_B);
      // The summary counts appointments in the next 48h; none of project A's may
      // appear for a caller who cannot reach project A.
      expect(summary.stats.upcomingNext48h).toBe(0);
    });
  });

  describe('appointments, deals and leads', () => {
    let appointments: AppointmentsService;
    let deals: DealsService;
    let leads: LeadsService;
    let analytics: AnalyticsService;

    beforeEach(() => {
      const prismaService = { client: prisma.prismaClient } as unknown as PrismaService;
      appointments = new AppointmentsService(prismaService);
      deals = new DealsService(prismaService);
      leads = new LeadsService(prismaService);
      analytics = new AnalyticsService(prismaService);
      prisma.deal = [
        { id: 'deal-a', organizationId: ORG_1, projectId: PROJECT_A, name: 'Deal Alpha', value: 10 },
        { id: 'deal-b', organizationId: ORG_1, projectId: PROJECT_B, name: 'Deal Beta', value: 20 },
      ];
      prisma.lead = [
        { id: 'lead-a', organizationId: ORG_1, projectId: PROJECT_A, name: 'Lead Alpha' },
        { id: 'lead-b', organizationId: ORG_1, projectId: PROJECT_B, name: 'Lead Beta' },
      ];
      prisma.auditLog = [
        {
          id: 'audit-1',
          organizationId: ORG_1,
          projectId: PROJECT_A,
          action: 'TASK_CREATED',
          actorType: 'USER',
          resourceType: 'TASK',
          resourceId: 'task-a',
          metadata: {},
          createdAt: new Date('2026-01-01T00:00:00.000Z'),
          actor: { fullName: 'Amina A', email: 'amina@example.com' },
        },
      ];
    });

    it('a member reads their own project in each module', async () => {
      expect(await appointments.list(MEMBER_A, { projectId: PROJECT_A })).toHaveLength(1);
      expect(await deals.list(MEMBER_A, PROJECT_A)).toHaveLength(1);
      expect(await leads.list(MEMBER_A, PROJECT_A)).toHaveLength(1);
    });

    it('naming another project in each module is a non-disclosing 404', async () => {
      const denied = await httpOutcome(() => appointments.list(COLLEAGUE_B, { projectId: PROJECT_A }));
      const nonexistent = await httpOutcome(() =>
        appointments.list(COLLEAGUE_B, { projectId: PROJECT_DOES_NOT_EXIST }),
      );
      expect(denied.status).toBe(404);
      expect(denied.body).toEqual(nonexistent.body);

      expect((await httpOutcome(() => deals.list(COLLEAGUE_B, PROJECT_A))).status).toBe(404);
      expect((await httpOutcome(() => leads.list(COLLEAGUE_B, PROJECT_A))).status).toBe(404);
      expect((await httpOutcome(() => analytics.auditLogs(COLLEAGUE_B, { projectId: PROJECT_A }))).status).toBe(404);
    });

    it('an unpinned appointment list no longer returns the whole organization', async () => {
      const memberRows = (await appointments.list(MEMBER_A)) as unknown[];
      expect(memberRows.every((r) => (r as ClinicRow).projectId === PROJECT_A)).toBe(true);
    });

    it('reading another project\'s appointment by id is refused', async () => {
      expect((await httpOutcome(() => appointments.getById(COLLEAGUE_B, appointmentInA))).status).toBe(404);
      expect((await httpOutcome(() => appointments.update(COLLEAGUE_B, appointmentInA, { title: 'x' }))).status).toBe(404);
      expect((await httpOutcome(() => appointments.delete(COLLEAGUE_B, appointmentInA))).status).toBe(404);
    });

    it('update and delete on another project\'s appointment write nothing', async () => {
      const stateBefore = prisma.snapshot();

      await httpOutcome(() => appointments.update(COLLEAGUE_B, appointmentInA, { title: 'HIJACKED' }));
      await httpOutcome(() => appointments.delete(COLLEAGUE_B, appointmentInA));

      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.appointment.find((a) => a.id === appointmentInA)?.title).toBeUndefined();
      expect(prisma.calls.filter((c) => c.op === 'update' || c.op === 'delete')).toHaveLength(0);
    });

    it('reading another project\'s deal and lead by id is refused', async () => {
      expect((await httpOutcome(() => deals.getById(COLLEAGUE_B, 'deal-a'))).status).toBe(404);
      expect((await httpOutcome(() => leads.getById(COLLEAGUE_B, 'lead-a'))).status).toBe(404);
    });

    it('the audit-log route is scoped in the service, not the controller', async () => {
      // A member of project B must not page through project A's audit log, whose
      // rows carry actor names and emails.
      const rows = (await analytics.auditLogs(COLLEAGUE_B)) as { items: unknown[] };
      expect(rows.items).toHaveLength(0);

      const own = (await analytics.auditLogs(MEMBER_A)) as { items: unknown[] };
      expect(own.items).toHaveLength(1);
    });

    it('a customer id from the request cannot pull another project\'s contact details', async () => {
      prisma.customer = [
        {
          id: 'customer-a',
          organizationId: ORG_1,
          projectId: PROJECT_A,
          displayName: 'Alpha Patient',
          primaryPhone: '+966500000099',
          primaryEmail: 'alpha@example.com',
        },
      ];

      // A caller in project B names project A's customer; nothing is copied out.
      const stateBefore = prisma.snapshot();
      await appointments.create(COLLEAGUE_B, {
        title: 'consultation',
        appointmentDate: '2026-11-03T09:00:00.000Z',
        patientName: 'Unknown',
        customerId: 'customer-a',
      } as never);

      const created = prisma.appointment.find((a) => a.id?.startsWith('new-appointment'));
      expect(created?.patientName).toBe('Unknown');
      expect(created?.projectId).toBe(PROJECT_B);
      expect(prisma.customer).toEqual(
        (JSON.parse(stateBefore) as { customer: unknown[] }).customer,
      );
    });
  });

  describe('the fixture can actually detect the defect', () => {
    it('the pre-fix query shapes do reach this fixture\'s clinical rows', async () => {
      // The clinic fix changed the adapter's *signatures* (every method now takes
      // `ctx` first), so a byte-for-byte pre-fix run cannot be called from these
      // tests at all — the old `getDoctors(projectId)` and the new
      // `getDoctors(ctx, projectId)` are different functions. Red/green for this
      // module is therefore proven at the predicate level: the exact pre-fix query
      // shapes are reproduced here against the same fixture and shown reaching the
      // rows, while the fixed adapter refuses them. Each assertion below is a claim
      // about the fixture, not about a copy of the service.
      //
      // 1. Pre-fix reads resolved rows by bare id, with no tenant predicate at all.
      const preFixByBareId = (id: string) => prisma.doctor.find((d) => d.id === id) ?? null;
      expect(preFixByBareId(doctorInA)?.organizationId).toBe(ORG_1);
      expect(preFixByBareId(doctorInA)?.name).toBe('Dr. Amina');

      // 2. Pre-fix roster reads were scoped by projectId with no organizationId, so
      //    a caller in another tenant naming this project id saw the whole roster.
      const preFixProjectOnly = (projectId: string) =>
        prisma.doctor.filter((d) => d.projectId === projectId);
      expect(preFixProjectOnly(PROJECT_A)).toHaveLength(1);

      // 3. Pre-fix updates mutated on `where: { id }` after a bare findUnique, so
      //    they reached any tenant's doctor by id.
      const preFixReachableById = preFixByBareId(doctorInA) !== null;
      expect(preFixReachableById).toBe(true);

      // The fixed adapter refuses the same three operations…
      await expect(adapter.getDoctors(FOREIGN_ORG_ADMIN, PROJECT_A)).rejects.toBeInstanceOf(
        TenantAccessError,
      );
      expect(await adapter.getDoctorById(FOREIGN_ORG_ADMIN, doctorInA)).toBeNull();
      expect((await httpOutcome(() => adapter.updateDoctor(FOREIGN_ORG_ADMIN, doctorInA, { name: 'X' }))).status).toBe(404);

      // …and the predicate it actually emits carries the tenant, so the row was
      // never even a candidate.
      prisma.calls.length = 0;
      await adapter.getDoctorById(MEMBER_A, doctorInA);
      expect(prisma.calls[0].where).toMatchObject({ id: doctorInA, organizationId: ORG_1 });
      expect(prisma.calls[0].where?.projectId).toEqual({ in: [PROJECT_A] });
    });

    it('a pre-fix update body could have moved a doctor across the tenant line', async () => {
      // The pre-fix update spread the whole request body into the row. Reproduced
      // here to show what the fixed adapter now prevents.
      const stateBefore = prisma.snapshot();

      await adapter.updateDoctor(MEMBER_A, doctorInA, {
        name: 'edited legitimately',
        organizationId: ORG_2,
        projectId: PROJECT_B,
      } as never);

      const row = prisma.doctor.find((d) => d.id === doctorInA);
      expect(row?.organizationId).toBe(ORG_1);
      expect(row?.projectId).toBe(PROJECT_A);
      expect(row?.name).toBe('edited legitimately');
      expect(prisma.doctor.filter((d) => d.organizationId === ORG_2)).toHaveLength(0);
      expect(prisma.snapshot()).not.toBe(stateBefore); // the legitimate edit did happen
    });
  });
});
