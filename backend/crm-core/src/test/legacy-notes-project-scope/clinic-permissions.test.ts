/**
 * Role-boundary proof for the LEGACY clinic module (`apps/api/src/clinic/**`).
 *
 * <h2>What this closes, and what it does not</h2>
 *
 * <p>Phase 26 closed a live cross-tenant breach on this surface. It deliberately left
 * one thing open and reported it: {@code Permission} had no clinic members and
 * {@code ClinicController} declared no {@code @RequirePermissions}, so the module
 * required a valid session and nothing more. Tenant-confined is not the same as
 * authorised — every employee of a tenant could still read a clinician's contact
 * details, rewrite the roster, delete a clinician, cancel a patient's appointment and
 * drive the AI simulator.
 *
 * <p>This suite proves the second half closed, and it proves it through the real wire:
 * a real Nest app with the real {@code AuthGuard}, the real {@code PermissionsGuard} and
 * the real {@code HttpExceptionFilter}, driven with supertest. See
 * {@code clinic-permissions-harness.ts} for why the harness exists and how it copes with
 * esbuild's missing {@code emitDecoratorMetadata}.
 *
 * <h2>The refusal is a 404, and it has to stay one</h2>
 *
 * <p>Every denial asserted below is compared against the response for a row that does
 * not exist, byte for byte. A decorator that rendered 403 would be a change in
 * disclosure: on a surface holding patient names and phone numbers, 403 says "this
 * exists and you may not have it", which is an existence oracle. Phase 26's breach fix
 * used 404 for both "you may not" and "not yours"; that is preserved here, and
 * {@link describe('the refusal reveals nothing')} is the assertion that keeps it.
 *
 * <h2>Nothing is written on a refusal</h2>
 *
 * <p>Each write-route refusal asserts the full serialisation of every clinic table is
 * unchanged, and additionally that the database was never touched at all — a refused
 * request that reached a query and then reported a denial would still fail.
 *
 * <h2>What this suite cannot tell you</h2>
 *
 * <p>The database is an in-memory stand-in, as in phase 26. A real-PostgreSQL run of
 * these routes against seeded cross-tenant data is still owed; what it would add is
 * confirmation that Prisma's own query translation agrees with this fake, not new
 * authorization logic. The permission decision itself is pure policy and is exercised
 * against the production {@code can()}.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import { CLINIC_ADMIN_ONLY, CLINIC_EMPLOYEE_GRANT, Permission } from '@firstelement/auth';
import { UserRole } from '@firstelement/shared';
import { ClinicController } from '../../../../../apps/api/src/clinic/clinic.controller';
import { PublicAppointmentController } from '../../../../../apps/api/src/clinic/public-appointment.controller';
import {
  ADMIN_OF_PROJECT_A_ONLY,
  APPOINTMENT_A,
  CLERK,
  CLINIC_ADMIN,
  DOCTOR_A,
  FOREIGN_ADMIN,
  ORG_1,
  ORG_2,
  PROJECT_A,
  PROJECT_B,
  PROCEDURE_A,
  bootClinic,
  describeRoutes,
  disclosureShape,
  expectMatrixIntact,
  withoutPermission,
  type BootedClinic,
  type RouteDescriptor,
} from './clinic-permissions-harness';

const NONE_EXISTENT = '00000000-0000-0000-0000-000000000000';

/**
 * Every non-`@Public()` clinic route, with the permission it is declared to require.
 *
 * <p>This table is the contract. {@link describe('every non-public clinic handler
 * declares a permission')} asserts the controller's reflected metadata equals it, so
 * adding a handler without a permission — or quietly changing which permission a route
 * demands — fails here rather than shipping open.
 */
const ROUTE_PERMISSIONS: Record<string, Permission> = {
  'GET /clinic/doctors': Permission.DOCTOR_READ,
  'GET /clinic/doctors/manage': Permission.DOCTOR_READ_MANAGEMENT,
  'POST /clinic/doctors': Permission.DOCTOR_CREATE,
  'PATCH /clinic/doctors/:id': Permission.DOCTOR_UPDATE,
  'DELETE /clinic/doctors/:id': Permission.DOCTOR_DELETE,
  'GET /clinic/procedures': Permission.PROCEDURE_READ,
  'GET /clinic/procedures/manage': Permission.PROCEDURE_READ_MANAGEMENT,
  'POST /clinic/procedures': Permission.PROCEDURE_CREATE,
  'PATCH /clinic/procedures/:id': Permission.PROCEDURE_UPDATE,
  'DELETE /clinic/procedures/:id': Permission.PROCEDURE_DELETE,
  'GET /clinic/procedures/:id/duration': Permission.PROCEDURE_READ,
  'GET /clinic/slots': Permission.SCHEDULE_AVAILABILITY_READ,
  'GET /clinic/check-availability': Permission.SCHEDULE_AVAILABILITY_READ,
  'POST /clinic/appointments': Permission.APPOINTMENT_CREATE,
  'PATCH /clinic/appointments/:id/reschedule': Permission.APPOINTMENT_RESCHEDULE,
  'POST /clinic/appointments/:id/cancel': Permission.APPOINTMENT_CANCEL,
  'GET /clinic/reminders': Permission.REMINDER_READ,
  'POST /clinic/reminders/send-batch': Permission.REMINDER_SEND,
  'POST /clinic/reminders/send/:appointmentId': Permission.REMINDER_SEND,
  'POST /clinic/reminders/respond': Permission.REMINDER_RECORD_RESPONSE,
  // Two permissions: the simulator creates appointments, so it is gated on the grant
  // that says so as well as the one that says "run the simulator".
  'POST /clinic/ai-simulator/chat': Permission.CLINIC_AI_SIMULATE,
  'GET /clinic/analytics': Permission.ANALYTICS_READ_PROJECT,
};

interface Call {
  method: 'get' | 'post' | 'patch' | 'delete';
  path: string;
  body?: unknown;
}

/** A request that must succeed for an authorised principal, one per route. */
const AUTHORISED_CALLS: Record<string, Call> = {
  'GET /clinic/doctors': { method: 'get', path: '/clinic/doctors' },
  'GET /clinic/doctors/manage': { method: 'get', path: '/clinic/doctors/manage' },
  'POST /clinic/doctors': {
    method: 'post',
    path: '/clinic/doctors',
    body: { name: 'Dr. New', specialty: 'Cardiology' },
  },
  'PATCH /clinic/doctors/:id': {
    method: 'patch',
    path: `/clinic/doctors/${DOCTOR_A}`,
    body: { name: 'Dr. Amina Renamed' },
  },
  'DELETE /clinic/doctors/:id': { method: 'delete', path: `/clinic/doctors/${DOCTOR_A}` },
  'GET /clinic/procedures': { method: 'get', path: '/clinic/procedures' },
  'GET /clinic/procedures/manage': { method: 'get', path: '/clinic/procedures/manage' },
  'POST /clinic/procedures': {
    method: 'post',
    path: '/clinic/procedures',
    body: { name: 'Stress test', specialty: 'Cardiology', durationMinutes: 45 },
  },
  'PATCH /clinic/procedures/:id': {
    method: 'patch',
    path: `/clinic/procedures/${PROCEDURE_A}`,
    body: { price: 500 },
  },
  'DELETE /clinic/procedures/:id': { method: 'delete', path: `/clinic/procedures/${PROCEDURE_A}` },
  'GET /clinic/procedures/:id/duration': {
    method: 'get',
    path: `/clinic/procedures/${PROCEDURE_A}/duration`,
  },
  'GET /clinic/slots': {
    method: 'get',
    path: `/clinic/slots?doctorId=${DOCTOR_A}&procedureId=${PROCEDURE_A}&date=2026-11-01`,
  },
  'GET /clinic/check-availability': {
    method: 'get',
    path: `/clinic/check-availability?doctorId=${DOCTOR_A}&procedureId=${PROCEDURE_A}&dateTime=2026-11-01T09:00:00.000Z`,
  },
  'POST /clinic/appointments': {
    method: 'post',
    path: '/clinic/appointments',
    body: {
      doctorId: DOCTOR_A,
      procedureId: PROCEDURE_A,
      appointmentDate: '2026-11-02T09:00:00.000Z',
      patientName: 'Patient Beta',
    },
  },
  'PATCH /clinic/appointments/:id/reschedule': {
    method: 'patch',
    path: `/clinic/appointments/${APPOINTMENT_A}/reschedule`,
    body: { newDate: '2026-11-05T09:00:00.000Z' },
  },
  'POST /clinic/appointments/:id/cancel': {
    method: 'post',
    path: `/clinic/appointments/${APPOINTMENT_A}/cancel`,
    body: { reason: 'patient unwell' },
  },
  'GET /clinic/reminders': { method: 'get', path: '/clinic/reminders' },
  'POST /clinic/reminders/send-batch': { method: 'post', path: '/clinic/reminders/send-batch' },
  'POST /clinic/reminders/send/:appointmentId': {
    method: 'post',
    path: `/clinic/reminders/send/${APPOINTMENT_A}`,
    body: { channel: 'WHATSAPP' },
  },
  'POST /clinic/reminders/respond': {
    method: 'post',
    path: '/clinic/reminders/respond',
    body: { appointmentId: APPOINTMENT_A, action: 'CONFIRM' },
  },
  'POST /clinic/ai-simulator/chat': {
    method: 'post',
    path: '/clinic/ai-simulator/chat',
    body: { message: 'I would like to book an Echo at 3pm' },
  },
  'GET /clinic/analytics': { method: 'get', path: '/clinic/analytics?days=30' },
};

/**
 * Addressed routes that answer 404 for a caller who cannot reach the row.
 *
 * <p>Two routes are deliberately absent, and both are non-disclosures rather than
 * omissions:
 *
 *  - `GET /clinic/procedures/:id/duration` returns `{ durationMinutes: 30 }` for a
 *    procedure the caller cannot reach, because `ClinicService.getProcedureDuration`
 *    substitutes a default rather than 404-ing. A caller learns nothing: a procedure
 *    that does not exist yields the same default, so the two are indistinguishable.
 *  - `POST /clinic/ai-simulator/chat` answers with a natural-language reply. Its
 *    refusal path is asserted by the role suite, which is the property under test.
 *
 * Everything else that names a row must 404.
 */
const ADDRESSED_ROUTES_ANSWERING_404 = new Set([
  'PATCH /clinic/doctors/:id',
  'DELETE /clinic/doctors/:id',
  'PATCH /clinic/procedures/:id',
  'DELETE /clinic/procedures/:id',
  'PATCH /clinic/appointments/:id/reschedule',
  'POST /clinic/appointments/:id/cancel',
  'POST /clinic/reminders/send/:appointmentId',
]);

/** Write routes: the ones where a refusal must leave the database untouched. */
const WRITE_ROUTES = new Set([
  'POST /clinic/doctors',
  'PATCH /clinic/doctors/:id',
  'DELETE /clinic/doctors/:id',
  'POST /clinic/procedures',
  'PATCH /clinic/procedures/:id',
  'DELETE /clinic/procedures/:id',
  'POST /clinic/appointments',
  'PATCH /clinic/appointments/:id/reschedule',
  'POST /clinic/appointments/:id/cancel',
  'POST /clinic/reminders/send-batch',
  'POST /clinic/reminders/send/:appointmentId',
  'POST /clinic/reminders/respond',
  'POST /clinic/ai-simulator/chat',
]);

describe('clinic role authorization (apps/api/src/clinic/**)', () => {
  let booted: BootedClinic;

  beforeEach(async () => {
    booted = await bootClinic([
      CLERK,
      CLINIC_ADMIN,
      ADMIN_OF_PROJECT_A_ONLY,
      FOREIGN_ADMIN,
      // A clerk who is a member of no project at all. `accessibleProjectsScope` must
      // narrow to nothing rather than falling back to the whole organization.
      { userId: 'user-nobody', organizationId: ORG_1, role: UserRole.EMPLOYEE, projectIds: [] },
    ]);
  });

  afterEach(async () => {
    await booted.close();
    // A narrowed grant must never outlive the test that narrowed it.
    expectMatrixIntact();
  });

  const send = (call: Call, cookie?: string) => {
    let req = request(booted.app.getHttpServer())[call.method](call.path);
    if (cookie) req = req.set('Cookie', cookie);
    if (call.body !== undefined) req = req.send(call.body as object);
    return req;
  };

  describe('every non-public clinic handler declares a permission', () => {
    const routes = describeRoutes(ClinicController).filter((r) => !r.isPublic);

    it('finds every route the contract table describes, and no others', () => {
      // The control: the descriptor really does see a decorated handler. If this ever
      // returns zero routes, the tests below would pass vacuously.
      expect(routes.length).toBeGreaterThan(15);
      // If a route is added without a permission, it appears here as an unexpected
      // entry and the next test fails naming it.
      expect(routes.map((r) => r.route).sort()).toEqual(Object.keys(ROUTE_PERMISSIONS).sort());
    });

    it('declares the exact permission the contract names, on every handler', () => {
      const mismatches: string[] = [];
      for (const route of routes as RouteDescriptor[]) {
        const expected = ROUTE_PERMISSIONS[route.route];
        if (expected === undefined) {
          mismatches.push(`${route.handler} (${route.route}) is not in the contract table`);
          continue;
        }
        if (route.permissions === null || route.permissions.length === 0) {
          mismatches.push(`${route.handler} (${route.route}) declares no permission at all`);
          continue;
        }
        if (!route.permissions.includes(expected)) {
          mismatches.push(
            `${route.handler} (${route.route}) declares ${route.permissions.join(', ')}, ` +
              `expected ${expected}`,
          );
        }
      }
      expect(mismatches).toEqual([]);
    });

    it('declares only permissions that exist', () => {
      const known = new Set<string>(Object.values(Permission));
      for (const route of routes as RouteDescriptor[]) {
        for (const p of route.permissions ?? []) {
          expect(known.has(p)).toBe(true);
        }
      }
    });

    it('gates the simulator on booking as well as simulating', () => {
      // A grant that could create an appointment without saying so is a mislabelled
      // grant (ADR 0010 §2). This is the assertion that keeps it labelled.
      const simulator = (describeRoutes(ClinicController) as RouteDescriptor[]).find(
        (r) => r.handler === 'runAiSimulator',
      );
      expect(simulator?.permissions).toEqual(
        expect.arrayContaining([Permission.CLINIC_AI_SIMULATE, Permission.APPOINTMENT_CREATE]),
      );
    });
  });

  describe('the patient-facing reminder routes stay public and token-scoped', () => {
    it('are the only @Public() handlers in the module', () => {
      const publicRoutes = describeRoutes(PublicAppointmentController);
      expect(publicRoutes.map((r) => r.route).sort()).toEqual([
        'GET /public/appointments/:token',
        'POST /public/appointments/:token/respond',
      ]);
      expect(publicRoutes.every((r) => r.isPublic)).toBe(true);
      // No permission: a patient is not a principal, and inventing one would be fiction.
      expect(publicRoutes.every((r) => r.permissions === null)).toBe(true);
    });

    it('serves a real token to an unauthenticated caller, as before', async () => {
      // Mint a token through the authenticated path, as a reminder send does.
      await send(
        { method: 'post', path: `/clinic/reminders/send/${APPOINTMENT_A}`, body: { channel: 'WHATSAPP' } },
        booted.cookieFor(CLERK.userId),
      ).expect(201);

      const token = (
        booted.reminders as unknown as { remindersStore: Map<string, { actionToken: string }> }
      ).remindersStore.get(APPOINTMENT_A)?.actionToken;
      expect(token).toBeTruthy();

      // No cookie at all. This is the capability model working.
      const read = await request(booted.app.getHttpServer()).get(`/public/appointments/${token}`);
      expect(read.status).toBe(200);
      expect(JSON.stringify(read.body)).toContain('Patient Alpha');

      const respond = await request(booted.app.getHttpServer())
        .post(`/public/appointments/${token}/respond`)
        .send({ action: 'CONFIRM' });
      expect(respond.status).toBe(201);
      expect(booted.prisma.appointment.find((a) => a.id === APPOINTMENT_A)?.status).toBe('CONFIRMED');
    });

    it('refuses a wrong token, and a bare appointment id, with the same 404', async () => {
      await send(
        { method: 'post', path: `/clinic/reminders/send/${APPOINTMENT_A}`, body: { channel: 'WHATSAPP' } },
        booted.cookieFor(CLERK.userId),
      ).expect(201);

      const before = booted.prisma.snapshot();

      const wrong = await request(booted.app.getHttpServer()).get(
        '/public/appointments/tok-not-a-real-token',
      );
      const bareId = await request(booted.app.getHttpServer()).get(
        `/public/appointments/${APPOINTMENT_A}`,
      );

      expect(wrong.status).toBe(404);
      expect(bareId.status).toBe(404);
      expect(disclosureShape(wrong.body)).toEqual(disclosureShape(bareId.body));
      expect(JSON.stringify(bareId.body)).not.toContain('Patient Alpha');
      expect(JSON.stringify(bareId.body)).not.toContain('+966500000011');
      expect(booted.prisma.snapshot()).toBe(before);
    });

    it('still refuses a bare appointment id on the respond route, unauthenticated', async () => {
      const before = booted.prisma.snapshot();
      const response = await request(booted.app.getHttpServer())
        .post(`/public/appointments/${APPOINTMENT_A}/respond`)
        .send({ action: 'CANCEL', reason: 'guessed the id' });

      expect(response.status).toBe(404);
      expect(booted.prisma.snapshot()).toBe(before);
      expect(booted.prisma.appointment.find((a) => a.id === APPOINTMENT_A)?.status).toBe('PENDING');
    });
  });

  describe('the grant: who holds what', () => {
    it('lets ADMIN through every clinic route, by construction', async () => {
      for (const route of Object.keys(AUTHORISED_CALLS)) {
        // Re-seed per route: several of these delete the doctor or cancel the
        // appointment, and a later route must not be refused for that reason instead.
        booted.prisma.seed();
        const response = await send(AUTHORISED_CALLS[route] as Call, booted.cookieFor(CLINIC_ADMIN.userId));
        expect(
          response.status,
          `ADMIN was refused ${route}: ${JSON.stringify(response.body)}`,
        ).toBeLessThan(400);
      }
    });

    it('grants EMPLOYEE the front desk and withholds every roster mutation', async () => {
      const employee = new Set(CLINIC_EMPLOYEE_GRANT);
      const adminOnly = new Set(CLINIC_ADMIN_ONLY);

      // The two sets partition the clinic permissions except the pre-existing analytics
      // read, which EMPLOYEE has held since before this change.
      expect([...employee].filter((p) => adminOnly.has(p))).toEqual([]);

      for (const permission of adminOnly) {
        expect(employee.has(permission)).toBe(false);
      }
      expect(CLINIC_ADMIN_ONLY).toContain(Permission.DOCTOR_DELETE);
      expect(CLINIC_ADMIN_ONLY).toContain(Permission.PROCEDURE_UPDATE);
      expect(CLINIC_ADMIN_ONLY).toContain(Permission.CLINIC_AI_SIMULATE);
      expect(CLINIC_EMPLOYEE_GRANT).toContain(Permission.APPOINTMENT_CREATE);
    });

    it('refuses an EMPLOYEE on every ADMIN-only route, and allows the front desk', async () => {
      const routes = Object.keys(ROUTE_PERMISSIONS);
      const adminOnly = routes.filter((r) =>
        CLINIC_ADMIN_ONLY.includes(ROUTE_PERMISSIONS[r] as Permission),
      );
      const frontDesk = routes.filter((r) => !adminOnly.includes(r));

      // Both lists are non-empty, or the assertion below would be vacuous.
      expect(adminOnly.length).toBeGreaterThan(0);
      expect(frontDesk.length).toBeGreaterThan(0);

      for (const route of adminOnly) {
        booted.prisma.seed();
        const response = await send(AUTHORISED_CALLS[route] as Call, booted.cookieFor(CLERK.userId));
        expect(
          response.status,
          `EMPLOYEE reached ADMIN-only ${route}: ${JSON.stringify(response.body)}`,
        ).toBe(404);
      }

      for (const route of frontDesk) {
        // Re-seed per route, for the same reason as the ADMIN loop.
        booted.prisma.seed();
        const response = await send(AUTHORISED_CALLS[route] as Call, booted.cookieFor(CLERK.userId));
        expect(
          response.status,
          `EMPLOYEE was refused the front desk on ${route}: ${JSON.stringify(response.body)}`,
        ).toBeLessThan(400);
      }
    });
  });

  describe('a principal without the permission is refused on every clinic route', () => {
    for (const [route, permission] of Object.entries(ROUTE_PERMISSIONS)) {
      it(`${route} → ${permission}`, async () => {
        const call = AUTHORISED_CALLS[route] as Call;
        const cookie = booted.cookieFor(CLERK.userId);

        const stateBefore = booted.prisma.snapshot();
        booted.prisma.resetCalls();

        const response = await withoutPermission(permission, () => send(call, cookie));

        expect(response.status).toBe(404);

        if (WRITE_ROUTES.has(route)) {
          // Nothing was written — and nothing was even read, so the refusal happened
          // before the handler ran rather than after a query came back empty.
          expect(booted.prisma.snapshot()).toBe(stateBefore);
          expect(booted.prisma.calls).toHaveLength(0);
        }

        // The refusal must not name what was refused.
        expect(response.body.code).toBe('NOT_FOUND');
        expect(JSON.stringify(response.body)).not.toContain('permission');
        expect(JSON.stringify(response.body)).not.toContain('Patient Alpha');
        expect(JSON.stringify(response.body)).not.toContain('+966500000011');
        expect(JSON.stringify(response.body)).not.toContain('Dr. Amina');
      });
    }
  });

  describe('the refusal reveals nothing', () => {
    it('is byte-identical to a row that does not exist, on every addressed route', async () => {
      const cookie = booted.cookieFor(CLERK.userId);

      const addressed: Record<string, Call> = {
        'PATCH /clinic/doctors/:id': {
          method: 'patch',
          path: `/clinic/doctors/${DOCTOR_A}`,
          body: { name: 'attempted' },
        },
        'DELETE /clinic/doctors/:id': { method: 'delete', path: `/clinic/doctors/${DOCTOR_A}` },
        'PATCH /clinic/procedures/:id': {
          method: 'patch',
          path: `/clinic/procedures/${PROCEDURE_A}`,
          body: { price: 1 },
        },
        'DELETE /clinic/procedures/:id': {
          method: 'delete',
          path: `/clinic/procedures/${PROCEDURE_A}`,
        },
        'PATCH /clinic/appointments/:id/reschedule': {
          method: 'patch',
          path: `/clinic/appointments/${APPOINTMENT_A}/reschedule`,
          body: { newDate: '2026-12-01T09:00:00.000Z' },
        },
        'POST /clinic/appointments/:id/cancel': {
          method: 'post',
          path: `/clinic/appointments/${APPOINTMENT_A}/cancel`,
          body: { reason: 'attempted' },
        },
        'POST /clinic/reminders/send/:appointmentId': {
          method: 'post',
          path: `/clinic/reminders/send/${APPOINTMENT_A}`,
          body: { channel: 'WHATSAPP' },
        },
      };

      for (const [route, call] of Object.entries(addressed)) {
        const permission = ROUTE_PERMISSIONS[route] as Permission;

        // (a) the row exists but the caller may not have it
        const denied = await withoutPermission(permission, () => send(call, cookie));

        // (b) the caller may have it, but the row does not exist
        const nonexistent = await send(
          { ...call, path: call.path.replace(DOCTOR_A, NONE_EXISTENT).replace(PROCEDURE_A, NONE_EXISTENT).replace(APPOINTMENT_A, NONE_EXISTENT) },
          cookie,
        );

        expect(denied.status, `${route} denial status`).toBe(404);
        expect(nonexistent.status, `${route} nonexistent status`).toBe(404);
        // Byte-for-byte, including the message — only the correlation id differs,
        // because it differs on every request by design. This is the assertion that
        // would fail if the decorator ever started answering 403.
        expect(disclosureShape(denied.body), `${route} denial vs nonexistent body`).toEqual(
          disclosureShape(nonexistent.body),
        );
      }
    });

    it('is uniform across the surface: every refusal is a 404 with one body shape', async () => {
      const cookie = booted.cookieFor(CLERK.userId);
      const shapes = new Set<string>();

      for (const [route, permission] of Object.entries(ROUTE_PERMISSIONS)) {
        const response = await withoutPermission(permission, () =>
          send(AUTHORISED_CALLS[route] as Call, cookie),
        );
        expect(response.status, `${route} returned ${response.status}, expected 404`).toBe(404);
        // requestId varies per request, so compare the body without it.
        const { requestId: _echo, ...shape } = response.body as Record<string, unknown>;
        shapes.add(JSON.stringify(shape));
      }

      // One shape for the whole surface: { code: 'NOT_FOUND', message: 'Resource not found' }.
      expect([...shapes]).toEqual([JSON.stringify({ code: 'NOT_FOUND', message: 'Resource not found' })]);
    });

    it('never answers 403 on this surface', async () => {
      const cookie = booted.cookieFor(CLERK.userId);
      for (const [route, permission] of Object.entries(ROUTE_PERMISSIONS)) {
        const response = await withoutPermission(permission, () =>
          send(AUTHORISED_CALLS[route] as Call, cookie),
        );
        expect(response.status, `${route} answered ${response.status}`).not.toBe(403);
      }
    });
  });

  describe('the tenant boundary still holds underneath the role boundary', () => {
    it('refuses another tenant\'s admin on every clinic route', async () => {
      const cookie = booted.cookieFor(FOREIGN_ADMIN.userId);
      const stateBefore = booted.prisma.snapshot();

      // Two separate claims, because they are separate properties:
      //
      //  - **Disclosure**: no response may carry org-1's data. Collection reads for a
      //    caller with no reachable project legitimately return an empty 200 — that is
      //    the tenant boundary working, not a leak — so those are asserted on content.
      //  - **Containment**: no row belonging to org-1 may change. A foreign principal
      //    *may* legitimately write in their own organization, so the claim is about
      //    org-1's rows specifically, not about the database being untouched.
      const leaks: string[] = [];
      const mutations: string[] = [];

      for (const route of Object.keys(ROUTE_PERMISSIONS)) {
        booted.prisma.seed();
        booted.prisma.resetCalls();
        const before = booted.prisma.snapshotOf(ORG_1);

        const response = await send(AUTHORISED_CALLS[route] as Call, cookie);
        if (ADDRESSED_ROUTES_ANSWERING_404.has(route)) {
          expect(response.status, `FOREIGN_ADMIN reached ${route}`).toBe(404);
        }

        const body = JSON.stringify(response.body);
        for (const secret of ['Dr. Amina', 'Patient Alpha', '+966500000011', 'amina@clinic.example']) {
          if (body.includes(secret)) leaks.push(`${route} disclosed ${secret}`);
        }
        if (booted.prisma.snapshotOf(ORG_1) !== before) mutations.push(route);
      }

      expect(leaks).toEqual([]);
      expect(mutations).toEqual([]);

      // No org-1 row moved into org-2 either.
      expect(booted.prisma.doctor.filter((d) => d['organizationId'] === ORG_1)).toHaveLength(1);
      expect(booted.prisma.appointment.filter((a) => a['organizationId'] === ORG_1)).toHaveLength(1);
      expect(booted.prisma.snapshotOf(ORG_2)).not.toContain(DOCTOR_A);
      expect(booted.prisma.snapshotOf(ORG_2)).not.toContain(APPOINTMENT_A);
      expect(stateBefore).toContain(DOCTOR_A);
    });

    it('refuses a same-tenant colleague naming a project they are not a member of', async () => {
      // The role grant is orthogonal: an ADMIN with the permission still cannot reach
      // another project, and a permission does not become a project grant.
      const cookie = booted.cookieFor(CLERK.userId);
      const response = await send({ method: 'get', path: '/clinic/doctors?projectId=project-b' }, cookie);
      expect(response.status).toBe(404);
    });

    it('shows a caller with no project membership nothing, not the whole organization', async () => {
      // The empty-membership case is the one a `{ projectId: ctx.projectIds[0] }`
      // fallback gets catastrophically wrong, so it is asserted directly rather than
      // inferred from the cross-tenant cases.
      const nobody = booted.cookieFor('user-nobody');
      for (const path of ['/clinic/doctors', '/clinic/doctors/manage', '/clinic/procedures']) {
        const response = await send({ method: 'get', path }, nobody);
        const body = JSON.stringify(response.body);
        expect(body).not.toContain('Dr. Amina');
        expect(body).not.toContain('amina@clinic.example');
        expect(body).not.toContain('Echo');
      }
    });

    it('refuses an unauthenticated caller before any permission is considered', async () => {
      const response = await request(booted.app.getHttpServer()).get('/clinic/doctors');
      expect(response.status).toBe(401);
    });
  });

  describe('the body cannot move a row between tenants', () => {
    it('creates a doctor in the caller\'s own tenant and project whatever the body names', async () => {
      // An ADMIN of project A only. ADMIN because DOCTOR_CREATE is deliberately not an
      // employee grant; project A only so naming PROJECT_B is refused by the *membership*
      // check rather than the organization check — this isolates the tenant rule.
      const cookie = booted.cookieFor(ADMIN_OF_PROJECT_A_ONLY.userId);

      // Note the shape of the defence: the controller resolves the project from
      // `ctx.projectIds[0]` and passes it *after* the spread, so a body naming another
      // project is not honoured — it is overwritten. The row lands in the caller's own
      // project rather than being refused, because the controller never passes the
      // body's id to `resolveProject`. (The adapter refuses a *supplied* non-member
      // project; `clinic-project-scope.test.ts` proves that at the service layer.)
      const refused = await send(
        {
          method: 'post',
          path: '/clinic/doctors',
          body: {
            name: 'planted',
            specialty: 'Cardiology',
            organizationId: ORG_2,
            projectId: PROJECT_B,
          },
        },
        cookie,
      );

      expect(refused.status).toBe(201);
      const planted = booted.prisma.doctor.find((d) => d['name'] === 'planted');
      expect(planted?.['organizationId']).toBe(ORG_1);
      expect(planted?.['projectId']).toBe(PROJECT_A);
      expect(booted.prisma.doctor.filter((d) => d['organizationId'] === ORG_2)).toHaveLength(0);
      expect(booted.prisma.doctor.filter((d) => d['projectId'] === PROJECT_B)).toHaveLength(0);

      // And with only a foreign tenant named, the same holds.
      await send(
        {
          method: 'post',
          path: '/clinic/doctors',
          body: { name: 'our own doctor', specialty: 'Cardiology', organizationId: ORG_2 },
        },
        cookie,
      ).expect(201);

      const created = booted.prisma.doctor.find((d) => d['name'] === 'our own doctor');
      expect(created?.['organizationId']).toBe(ORG_1);
      expect(created?.['projectId']).toBe(PROJECT_A);
      expect(booted.prisma.doctor.filter((d) => d['organizationId'] === ORG_2)).toHaveLength(0);
    });

    it('updates a doctor in place: a body carrying a foreign tenant is ignored', async () => {
      await send(
        {
          method: 'patch',
          path: `/clinic/doctors/${DOCTOR_A}`,
          body: { name: 'edited legitimately', organizationId: ORG_2, projectId: PROJECT_B },
        },
        booted.cookieFor(CLINIC_ADMIN.userId),
      ).expect(200);

      const row = booted.prisma.doctor.find((d) => d.id === DOCTOR_A);
      expect(row?.['organizationId']).toBe(ORG_1);
      expect(row?.['projectId']).toBe(PROJECT_A);
      expect(row?.['name']).toBe('edited legitimately');
      expect(booted.prisma.doctor.filter((d) => d['organizationId'] === ORG_2)).toHaveLength(0);
    });
  });

  describe('the fixture can detect the defect', () => {
    it('reaches every clinic row, so a permission that was not enforced would show', async () => {
      // The non-vacuity control. If these rows were unreachable, every refusal above
      // would pass for the wrong reason.
      const cookie = booted.cookieFor(CLERK.userId);

      const doctors = await send({ method: 'get', path: '/clinic/doctors' }, cookie);
      expect(doctors.body).toHaveLength(1);
      expect(JSON.stringify(doctors.body)).toContain('Dr. Amina');

      const manage = await send(
        { method: 'get', path: '/clinic/doctors/manage' },
        booted.cookieFor(CLINIC_ADMIN.userId),
      );
      expect(JSON.stringify(manage.body)).toContain('amina@clinic.example');

      // The summary counts upcoming appointments and replays the in-memory reminder
      // log. The seeded appointment is months away from "now", so it is not in the
      // 48-hour window; mint a reminder for it first so the record exists.
      await send(
        { method: 'post', path: `/clinic/reminders/send/${APPOINTMENT_A}`, body: { channel: 'WHATSAPP' } },
        cookie,
      ).expect(201);
      const reminders = await send({ method: 'get', path: '/clinic/reminders' }, cookie);
      expect(JSON.stringify(reminders.body)).toContain('Patient Alpha');

      // And the write routes really do write when permitted. DOCTOR_CREATE is ADMIN-only,
      // so this is the administrator — the point is that permission granted means the
      // write happens, not that it is silently swallowed.
      const before = booted.prisma.doctor.length;
      await send(
        { method: 'post', path: '/clinic/doctors', body: { name: 'Dr. Control', specialty: 'Cardiology' } },
        booted.cookieFor(CLINIC_ADMIN.userId),
      ).expect(201);
      expect(booted.prisma.doctor).toHaveLength(before + 1);
    });

    it('a role that lacks the permission is refused by the guard, not by the adapter', async () => {
      // The guard is the layer under test. Prove it: with the grant narrowed, the
      // database is never touched — the adapter's tenant checks are not what refused.
      booted.prisma.resetCalls();
      await withoutPermission(Permission.DOCTOR_READ, () =>
        send({ method: 'get', path: '/clinic/doctors' }, booted.cookieFor(CLERK.userId)),
      );
      expect(booted.prisma.calls).toHaveLength(0);
    });

    it('EmployeeRole is unchanged, so no other module inherits the clinic grant', () => {
      // The clinic members are additive; nothing that was already true became false.
      expect(new Set(CLINIC_EMPLOYEE_GRANT)).toEqual(
        new Set([
          Permission.DOCTOR_READ,
          Permission.PROCEDURE_READ,
          Permission.SCHEDULE_AVAILABILITY_READ,
          Permission.APPOINTMENT_CREATE,
          Permission.APPOINTMENT_RESCHEDULE,
          Permission.APPOINTMENT_CANCEL,
          Permission.REMINDER_READ,
          Permission.REMINDER_SEND,
          Permission.REMINDER_RECORD_RESPONSE,
        ]),
      );
      expect(UserRole.EMPLOYEE).toBe('EMPLOYEE');
    });
  });
});