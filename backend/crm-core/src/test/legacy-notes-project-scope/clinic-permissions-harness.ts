/**
 * Machinery for the clinic **role** proof (`clinic-permissions.test.ts`).
 *
 * <h2>Why this is a real HTTP harness and not a service-level stub</h2>
 *
 * <p>The claim under test is about a decorator: that every non-`@Public()` clinic
 * handler declares a permission, that a principal lacking it is refused, and that the
 * refusal is indistinguishable from "this row does not exist". Asserting that by
 * calling services directly would skip the only code that reads the decorator —
 * {@code PermissionsGuard} — and would prove nothing about the wire response.
 *
 * <p>So this boots a real Nest application with the <b>real</b> {@code AuthGuard},
 * the <b>real</b> {@code PermissionsGuard} and the <b>real</b> {@code
 * HttpExceptionFilter}, the real {@code ClinicController}, the real services and the
 * real {@code LocalClinicAdapter}, over an in-memory Prisma stand-in, and drives it
 * with supertest. Only the database is substituted.
 *
 * <h2>The esbuild caveat, handled rather than ignored</h2>
 *
 * <p>Vitest transforms with esbuild, which does <em>not</em> implement
 * {@code emitDecoratorMetadata} (see {@code validation-pipe-reachability.test.ts}), so
 * {@code design:paramtypes} is absent and Nest cannot inject a constructor parameter
 * by type. Rather than restructure production code for a transformer's limitation,
 * {@link withParamTypes} declares the dependency list each class actually has — which
 * is exactly what the TypeScript compiler would have emitted. It asserts the arity
 * against the constructor's real length, so a production constructor change this
 * harness had not been told about fails loudly instead of silently injecting
 * {@code undefined}.
 */
import 'reflect-metadata';
import { INestApplication, Injectable, Module, ValidationPipe } from '@nestjs/common';
import { APP_FILTER, APP_GUARD, Reflector } from '@nestjs/core';
import { Test } from '@nestjs/testing';
import cookieParser from 'cookie-parser';
import { expect } from 'vitest';
import { Permission, ROLE_PERMISSIONS } from '@firstelement/auth';
import { UserRole } from '@firstelement/shared';
import { IS_PUBLIC_KEY, PERMISSIONS_KEY } from '../../../../../apps/api/src/auth/decorators';
import { AuthGuard } from '../../../../../apps/api/src/auth/auth.guard';
import { PermissionsGuard } from '../../../../../apps/api/src/auth/permissions.guard';
import { SessionService } from '../../../../../apps/api/src/auth/session.service';
import { HttpExceptionFilter } from '../../../../../apps/api/src/common/http-exception.filter';
import { PrismaService } from '../../../../../apps/api/src/common/prisma.service';
import { requestContextMiddleware } from '../../../../../apps/api/src/common/request-context';
import { ClinicController } from '../../../../../apps/api/src/clinic/clinic.controller';
import { PublicAppointmentController } from '../../../../../apps/api/src/clinic/public-appointment.controller';
import { LocalClinicAdapter } from '../../../../../apps/api/src/clinic/local-clinic.adapter';
import { SchedulingEngineService } from '../../../../../apps/api/src/clinic/scheduling-engine.service';
import { ClinicService } from '../../../../../apps/api/src/clinic/clinic.service';
import { AiSimulatorService } from '../../../../../apps/api/src/clinic/ai-simulator.service';
import { RemindersService } from '../../../../../apps/api/src/clinic/reminders.service';

export const ORG_1 = 'org-1';
export const ORG_2 = 'org-2';
export const PROJECT_A = 'project-a';
export const PROJECT_B = 'project-b';
export const DOCTOR_A = 'doctor-a';
export const PROCEDURE_A = 'proc-a';
export const APPOINTMENT_A = '11111111-1111-1111-1111-111111111111';

const METHOD_NAMES = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'ALL', 'OPTIONS', 'HEAD'] as const;

/**
 * Declares {@code design:paramtypes} for a class whose constructor takes these
 * dependencies, so Nest can inject without esbuild's help. The arity assertion is the
 * point: a constructor that gains or loses a parameter breaks this loudly rather than
 * receiving {@code undefined}.
 */
type Constructor = { new (...args: never[]): unknown; length: number; name: string };

export function withParamTypes(target: Constructor, ...types: unknown[]): void {
  const declared = target.length;
  if (declared !== types.length) {
    throw new Error(
      `withParamTypes(${target.name || 'anonymous'}): the constructor takes ${declared} ` +
        `parameter${declared === 1 ? '' : 's'} but ${types.length} ` +
        `${types.length === 1 ? 'was' : 'were'} declared. Update this harness to match.`,
    );
  }
  Reflect.defineMetadata('design:paramtypes', types, target);
}

/** Declares paramtypes for every class the harness instantiates through Nest DI. */
export function declareProductionParamTypes(): void {
  withParamTypes(ClinicController, ClinicService, LocalClinicAdapter, AiSimulatorService, RemindersService);
  withParamTypes(PublicAppointmentController, RemindersService);
  withParamTypes(LocalClinicAdapter, PrismaService);
  withParamTypes(SchedulingEngineService, LocalClinicAdapter);
  withParamTypes(ClinicService, PrismaService, LocalClinicAdapter, SchedulingEngineService);
  withParamTypes(AiSimulatorService, PrismaService, ClinicService, LocalClinicAdapter, SchedulingEngineService);
  withParamTypes(RemindersService, PrismaService, ClinicService, LocalClinicAdapter);
  withParamTypes(AuthGuard, Reflector, SessionService);
  withParamTypes(PermissionsGuard, Reflector);
}

// ---------------------------------------------------------------------------
// In-memory database
// ---------------------------------------------------------------------------

export type Row = { id: string } & Record<string, unknown>;

export interface PrismaCall {
  op: string;
  table: string;
  where?: Record<string, unknown>;
  data?: Record<string, unknown>;
  id?: string;
}

type Where = Record<string, unknown>;

/**
 * Prisma `where` semantics for exactly the operators the clinic services emit: scalar
 * equality, `in`, `notIn`, and `gte`/`lte` on a date column.
 *
 * <p>An unrecognised operator throws. A matcher that ignored an operator it did not
 * understand would silently widen a query and make a scoping test pass for the wrong
 * reason — the failure mode this whole suite exists to prevent.
 */
export function matches(row: Row, where: Where): boolean {
  return Object.entries(where).every(([field, expected]) => {
    if (expected === undefined) return true;
    if (expected !== null && typeof expected === 'object' && !(expected instanceof Date)) {
      const clause = expected as Record<string, unknown>;
      if ('in' in clause) return (clause['in'] as unknown[]).includes(row[field]);
      if ('notIn' in clause) return !(clause['notIn'] as unknown[]).includes(row[field]);
      if ('gte' in clause || 'lte' in clause) {
        const value = row[field];
        const lower = clause['gte'];
        const upper = clause['lte'];
        if (!(value instanceof Date) || (!(lower instanceof Date) && !(upper instanceof Date))) {
          throw new Error(`fake prisma: unsupported range comparison on ${field}`);
        }
        if (lower instanceof Date && value.getTime() < lower.getTime()) return false;
        if (upper instanceof Date && value.getTime() > upper.getTime()) return false;
        return true;
      }
      throw new Error(`fake prisma: unsupported operator on ${field}: ${JSON.stringify(clause)}`);
    }
    return row[field] === expected;
  });
}

/** Every table the harness fakes. The union is what the client delegate map is keyed by. */
type Table = 'doctor' | 'procedure' | 'appointment' | 'customer' | 'conversation' | 'auditLog';
type Delegate = ReturnType<FakePrisma['delegate']>;

/**
 * In-memory stand-in for the clinic tables. Every call is recorded in {@link calls},
 * so a test can assert that a refused request never reached the database at all — a
 * stronger claim than "the row did not change".
 */
export class FakePrisma {
  doctor: Row[] = [];
  procedure: Row[] = [];
  appointment: Row[] = [];
  customer: Row[] = [];
  conversation: Row[] = [];
  auditLog: Row[] = [];
  calls: PrismaCall[] = [];

  /** Every clinic table, serialised — the before/after comparison base. */
  snapshot(): string {
    return JSON.stringify({
      doctor: this.doctor,
      procedure: this.procedure,
      appointment: this.appointment,
      customer: this.customer,
      conversation: this.conversation,
      auditLog: this.auditLog,
    });
  }

  /**
   * Only the rows belonging to `organizationId`, serialised.
   *
   * <p>Used for the cross-tenant assertions. A foreign principal legitimately *may*
   * write rows in their own organization — creating a doctor in their own tenant is not
   * a breach — so the claim to test is that {@code ORG_1}'s rows did not change, not
   * that the database did not change at all.
   */
  snapshotOf(organizationId: string): string {
    const owned = (rows: Row[]) => rows.filter((r) => r['organizationId'] === organizationId);
    return JSON.stringify({
      doctor: owned(this.doctor),
      procedure: owned(this.procedure),
      appointment: owned(this.appointment),
      customer: owned(this.customer),
      conversation: owned(this.conversation),
      auditLog: owned(this.auditLog),
    });
  }

  resetCalls(): void {
    this.calls = [];
  }

  private table(name: Table): Row[] {
    return this[name];
  }

  /** Appointments booked against a doctor or procedure, for `_count` includes. */
  private appointmentCountFor(row: Row): number {
    return this.appointment.filter(
      (a) =>
        a['projectId'] === row['projectId'] &&
        (a['doctorId'] === row['id'] || a['procedureId'] === row['id']),
    ).length;
  }

  private delegate(name: Table): Record<string, (...args: never[]) => Promise<unknown>> {
    const rows = () => this.table(name);
    return {
      findMany: async (args: {
        where?: Where;
        orderBy?: unknown;
        take?: number;
        include?: unknown;
      }) => {
        this.calls.push({ op: 'findMany', table: name, where: args.where });
        const hits = args.where ? rows().filter((r) => matches(r, args.where as Where)) : [...rows()];
        const enriched = hits.map((r) => ({ ...r, _count: { appointments: this.appointmentCountFor(r) } }));
        return args.take ? enriched.slice(0, args.take) : enriched;
      },
      findFirst: async (args: { where?: Where }) => {
        this.calls.push({ op: 'findFirst', table: name, where: args.where });
        if (!args.where) return rows()[0] ?? null;
        return rows().find((r) => matches(r, args.where as Where)) ?? null;
      },
      findUnique: async (args: { where: Where }) => {
        this.calls.push({ op: 'findUnique', table: name, where: args.where });
        return rows().find((r) => matches(r, args.where)) ?? null;
      },
      create: async (args: { data: Row }) => {
        this.calls.push({ op: 'create', table: name, data: args.data });
        const row: Row = { id: `new-${name}-${rows().length + 1}`, ...args.data };
        this.table_set(name, [...rows(), row]);
        return row;
      },
      update: async (args: { where: { id: string }; data: Partial<Row> }) => {
        this.calls.push({ op: 'update', table: name, id: args.where.id, data: args.data });
        const row = rows().find((r) => r.id === args.where.id);
        if (!row) throw new Error(`fake prisma: ${name}.update on a missing row ${args.where.id}`);
        Object.assign(row, args.data);
        return row;
      },
      updateMany: async (args: { where?: Where; data: Partial<Row> }) => {
        this.calls.push({ op: 'updateMany', table: name, where: args.where, data: args.data });
        const hits = args.where ? rows().filter((r) => matches(r, args.where as Where)) : rows();
        hits.forEach((r) => Object.assign(r, args.data));
        return { count: hits.length };
      },
      delete: async (args: { where: { id: string } }) => {
        this.calls.push({ op: 'delete', table: name, id: args.where.id });
        if (!rows().some((r) => r.id === args.where.id)) {
          throw new Error(`fake prisma: ${name}.delete on a missing row`);
        }
        const removed = rows().find((r) => r.id === args.where.id) as Row;
        this.table_set(name, rows().filter((r) => r.id !== args.where.id));
        return removed;
      },
      deleteMany: async (args: { where?: Where }) => {
        this.calls.push({ op: 'deleteMany', table: name, where: args.where });
        const hits = args.where ? rows().filter((r) => matches(r, args.where as Where)) : rows();
        this.table_set(
          name,
          rows().filter((r) => !hits.includes(r)),
        );
        return { count: hits.length };
      },
      count: async (args: { where?: Where }) => {
        this.calls.push({ op: 'count', table: name, where: args.where });
        return args.where ? rows().filter((r) => matches(r, args.where as Where)).length : rows().length;
      },
    } as unknown as Record<string, (...args: never[]) => Promise<unknown>>;
  }

  /** Indirection so `delegate` can both read and replace without capturing the array. */
  private table_set(name: Table, next: Row[]): void {
    this[name] = next;
  }

  get client(): Record<Table, Delegate> {
    return {
      doctor: this.delegate('doctor'),
      procedure: this.delegate('procedure'),
      appointment: this.delegate('appointment'),
      customer: this.delegate('customer'),
      conversation: this.delegate('conversation'),
      auditLog: this.delegate('auditLog'),
    } as unknown as Record<Table, Delegate>;
  }

  /**
   * The fixture phase 26 relied on, re-seeded identically so the two suites agree on
   * what "a doctor in project A" means. `workingDays` is every day so availability
   * never depends on the calendar date the suite happens to run on.
   */
  seed(): void {
    this.doctor = [
      {
        id: DOCTOR_A,
        organizationId: ORG_1,
        projectId: PROJECT_A,
        name: 'Dr. Amina',
        specialty: 'Cardiology',
        email: 'amina@clinic.example',
        phone: '+966500000001',
        workingDays: [0, 1, 2, 3, 4, 5, 6],
        startHour: '09:00',
        endHour: '17:00',
        slotDuration: 30,
        isActive: true,
      },
    ];
    this.procedure = [
      {
        id: PROCEDURE_A,
        organizationId: ORG_1,
        projectId: PROJECT_A,
        name: 'Echo',
        specialty: 'Cardiology',
        durationMinutes: 30,
        bufferMinutes: 0,
        price: 300,
        isActive: true,
      },
    ];
    this.appointment = [
      {
        id: APPOINTMENT_A,
        organizationId: ORG_1,
        projectId: PROJECT_A,
        doctorId: DOCTOR_A,
        procedureId: PROCEDURE_A,
        title: 'Echo with Dr. Amina',
        patientName: 'Patient Alpha',
        patientPhone: '+966500000011',
        patientEmail: 'alpha@example.com',
        status: 'PENDING',
        service: 'Echo',
        doctorName: 'Dr. Amina',
        notes: 'alpha notes',
        appointmentDate: new Date('2026-11-01T09:00:00.000Z'),
        durationMinutes: 30,
        isAiProposed: false,
      },
    ];
    this.customer = [
      {
        id: 'customer-a',
        organizationId: ORG_1,
        projectId: PROJECT_A,
        displayName: 'Patient Alpha',
        primaryPhone: '+966500000011',
        primaryEmail: 'alpha@example.com',
      },
    ];
    this.conversation = [
      { id: 'conv-a', organizationId: ORG_1, projectId: PROJECT_A, channel: 'WHATSAPP' },
      { id: 'conv-b', organizationId: ORG_1, projectId: PROJECT_A, channel: 'WEB' },
    ];
    this.auditLog = [];
    this.calls = [];
  }
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------

export interface Principal {
  token: string;
  userId: string;
  organizationId: string;
  role: UserRole;
  projectIds: string[];
}

/** A booking clerk in project A: the least-privileged principal that can run a clinic. */
export const CLERK = {
  userId: 'user-clerk',
  organizationId: ORG_1,
  role: UserRole.EMPLOYEE,
  projectIds: [PROJECT_A],
} as const;

/** The clinic administrator, reaching every project in their own organization. */
export const CLINIC_ADMIN = {
  userId: 'user-admin',
  organizationId: ORG_1,
  role: UserRole.ADMIN,
  projectIds: [PROJECT_A, PROJECT_B],
} as const;

/**
 * An ADMIN who is a member of project A only.
 *
 * <p>Exists to isolate the <em>project</em> boundary from the tenant boundary: naming
 * project B here is naming a project inside their own organization, so a refusal proves
 * the membership check fired rather than the organization check.
 */
export const ADMIN_OF_PROJECT_A_ONLY = {
  userId: 'user-admin-a',
  organizationId: ORG_1,
  role: UserRole.ADMIN,
  projectIds: [PROJECT_A],
} as const;

/** Same role, different organization — for the tenant boundary, not the role boundary. */
export const FOREIGN_ADMIN = {
  userId: 'user-foreign',
  organizationId: ORG_2,
  role: UserRole.ADMIN,
  projectIds: ['project-foreign'],
} as const;

export interface BootedClinic {
  app: INestApplication;
  prisma: FakePrisma;
  reminders: RemindersService;
  clinicService: ClinicService;
  /** Every principal issued, keyed by userId. */
  principals: Map<string, Principal>;
  cookieFor(userId: string): string;
  close(): Promise<void>;
}

export type IssuedPrincipal = Omit<Principal, 'token'> & { token?: string };

/**
 * Boots the real clinic application: real controllers, real global guards, real
 * exception filter, real services, in-memory database. Principals are issued as
 * `fe_session` cookie values so `AuthGuard` runs for real rather than being bypassed.
 */
export async function bootClinic(principals: readonly IssuedPrincipal[]): Promise<BootedClinic> {
  declareProductionParamTypes();

  const prisma = new FakePrisma();
  prisma.seed();
  const prismaService = { client: prisma.client } as unknown as PrismaService;

  const issued: Principal[] = principals.map((p, index) => ({
    ...p,
    token: p.token ?? `session-${index}`,
  }));

  /** Stands in for the `sessions` table, and for nothing else. */
  @Injectable()
  class HarnessSessionService {
    async resolve(token: string) {
      const found = issued.find((s) => s.token === token);
      if (!found) return null;
      return {
        sessionId: `sid-${found.token}`,
        user: {
          id: found.userId,
          email: `${found.userId}@example.com`,
          fullName: found.userId,
          role: found.role,
          locale: 'en' as const,
          organizationId: found.organizationId,
          organizationName: found.organizationId,
          employeeId: null,
          projectIds: found.projectIds,
        },
      };
    }
    async touch(): Promise<void> {}
    async revoke(): Promise<void> {}
    async revokeAllForUser(): Promise<void> {}
  }

  @Module({
    controllers: [ClinicController, PublicAppointmentController],
    providers: [
      { provide: PrismaService, useValue: prismaService },
      LocalClinicAdapter,
      SchedulingEngineService,
      ClinicService,
      AiSimulatorService,
      RemindersService,
      { provide: SessionService, useClass: HarnessSessionService },
      { provide: APP_GUARD, useClass: AuthGuard },
      { provide: APP_GUARD, useClass: PermissionsGuard },
      { provide: APP_FILTER, useClass: HttpExceptionFilter },
    ],
  })
  class ClinicHarnessModule {}

  const moduleRef = await Test.createTestingModule({ imports: [ClinicHarnessModule] }).compile();
  const app = moduleRef.createNestApplication();

  // The pieces of `apps/api/src/main.ts` this claim depends on, in the same order.
  app.use(cookieParser('harness-secret'));
  app.use(requestContextMiddleware);
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  await app.init();

  const byUser = new Map(issued.map((p) => [p.userId, p]));

  return {
    app,
    prisma,
    reminders: app.get(RemindersService),
    clinicService: app.get(ClinicService),
    principals: byUser,
    cookieFor: (userId: string) => `fe_session=${byUser.get(userId)?.token ?? ''}`,
    close: () => app.close(),
  };
}

// ---------------------------------------------------------------------------
// Reflection over the decorators
// ---------------------------------------------------------------------------

/**
 * An error body with the per-request correlation id removed.
 *
 * <p>Comparing two responses for *equality of what they disclose* has to ignore
 * `requestId`: it differs on every request by design, so leaving it in would make the
 * comparison meaningless. Everything else — `code`, `message`, and any field a denial
 * might leak — stays in, because a message that differs between "you may not" and
 * "does not exist" is exactly the disclosure this suite exists to catch.
 */
export function disclosureShape(body: unknown): Record<string, unknown> {
  const { requestId: _correlationId, ...rest } = (body ?? {}) as Record<string, unknown>;
  return rest;
}

export interface RouteDescriptor {
  /** Handler method name on the controller. */
  handler: string;
  /** `METHOD /path`, read from Nest's own route metadata. */
  route: string;
  /** Every permission the handler declares, or `null` when it declares none. */
  permissions: Permission[] | null;
  isPublic: boolean;
}

/**
 * Reads the decorator metadata off a controller through the same `Reflector` the guard
 * uses, so the reflection test asserts against the mechanism that enforces the rule and
 * not against a hand-written list that could drift from the code.
 */
export function describeRoutes(controller: Constructor): RouteDescriptor[] {
  const reflector = new Reflector();
  const prototype = controller.prototype as unknown as Record<string, unknown>;

  return Object.getOwnPropertyNames(prototype)
    .filter((name) => name !== 'constructor' && typeof prototype[name] === 'function')
    .map((handler) => {
      const fn = prototype[handler] as (...args: unknown[]) => unknown;
      const permissions = reflector.getAllAndOverride<Permission[]>(PERMISSIONS_KEY, [fn, controller]);
      const isPublic =
        reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, [fn, controller]) === true;

      const methodIndex = (Reflect.getMetadata('method', fn) as number | undefined) ?? -1;
      const methodName = METHOD_NAMES[methodIndex] ?? 'UNKNOWN';
      const classPath = (Reflect.getMetadata('path', controller) as string | undefined) ?? '';
      const handlerPath = (Reflect.getMetadata('path', fn) as string | undefined) ?? '';
      const full = handlerPath ? `${classPath}/${handlerPath}` : classPath;

      return {
        handler,
        route: `${methodName} /${full}`,
        permissions: permissions ?? null,
        isPublic,
      };
    });
}

// ---------------------------------------------------------------------------
// Narrowing the role grant
// ---------------------------------------------------------------------------

/**
 * Runs `body` as a principal whose role grant does **not** include `permission`, then
 * restores the matrix exactly.
 *
 * <h2>Why the grant is narrowed rather than a third role invented</h2>
 *
 * <p>{@code UserRole} has two values and both are fully specified in production: ADMIN
 * holds everything by construction, EMPLOYEE holds the front-desk clinic grant. Several
 * clinic permissions are therefore held by <em>every</em> principal that can
 * authenticate, so no shipped principal lacks them and "a principal without this
 * permission is refused" cannot be demonstrated from the production matrix alone.
 *
 * <p>Narrowing the grant for the duration of the call asks the question the route
 * actually answers: <em>if</em> a caller's role lacked this permission, would the handler
 * refuse? It substitutes a role table and nothing else — {@code PermissionsGuard},
 * {@code assertCan}, {@code can} and {@code HttpExceptionFilter} all run as written, and
 * the controller, services and adapter are the production ones. The table is restored in
 * a {@code finally}, and {@link expectMatrixIntact} re-checks it, so a throwing test
 * cannot leak a narrowed matrix into the next one.
 */
export async function withoutPermission<T>(
  permission: Permission,
  body: () => Promise<T>,
): Promise<T> {
  const original = ROLE_PERMISSIONS[UserRole.EMPLOYEE];
  ROLE_PERMISSIONS[UserRole.EMPLOYEE] = original.filter((p) => p !== permission);
  try {
    return await body();
  } finally {
    ROLE_PERMISSIONS[UserRole.EMPLOYEE] = original;
  }
}

/**
 * The production `EMPLOYEE` grant, captured when this module loaded and before any test
 * narrowed anything. `ROLE_PERMISSIONS` is a plain exported object, so a test that
 * reassigns a property could leak that change into every test that follows; this is the
 * value it must always return to.
 */
const PRODUCTION_EMPLOYEE_GRANT: readonly Permission[] = [...ROLE_PERMISSIONS[UserRole.EMPLOYEE]];

/**
 * Proves the shared role matrix is byte-identical to what production declares, so a
 * narrowed grant cannot silently persist into a later test in this file.
 *
 * <p>Deliberately compares against the value captured at load time, not against
 * {@code CLINIC_EMPLOYEE_GRANT}: the employee role holds its pre-existing CRM
 * permissions too, and the point here is only that nothing was left narrowed.
 */
export function expectMatrixIntact(): void {
  expect([...ROLE_PERMISSIONS[UserRole.EMPLOYEE]]).toEqual([...PRODUCTION_EMPLOYEE_GRANT]);
  // ADMIN is `Object.values(Permission)` by construction, so any new member is included.
  expect(new Set(ROLE_PERMISSIONS[UserRole.ADMIN])).toEqual(new Set(Object.values(Permission)));
}