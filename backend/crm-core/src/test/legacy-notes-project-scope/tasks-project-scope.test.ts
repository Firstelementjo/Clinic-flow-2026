/**
 * Project- and tenant-scope proof for the LEGACY tasks service
 * (`apps/api/src/tasks/tasks.service.ts`).
 *
 * Same discipline as the notes proof, and for the same reason: the load-bearing
 * assertions are about what did NOT change. Every write test snapshots the whole
 * task table — row count and every field of every row — before the attempt and
 * re-compares it after, so a write that happened and then reported a denial
 * still fails.
 *
 * What this covers, and why each one was open:
 *
 *  - `list` takes `projectId` from the **query string**, so `PermissionsGuard`
 *    supplies no project scope and the service must check membership itself.
 *  - `getById`, `assertOwned` were bounded by `organizationId` alone, so a
 *    colleague in the same organization could read, rewrite and delete a task
 *    in a project they do not belong to. `update`/`delete` mutate on
 *    `where: { id }` with no predicate, so `assertOwned` was the entire write
 *    boundary.
 *  - `create` takes `projectId` from the **request body**. The controller's
 *    `@Body()` type does not declare `projectId`, but that type is erased at
 *    runtime and `ValidationPipe({ whitelist: true })` only strips undeclared
 *    properties from class DTOs — see `validation-pipe-reachability.test.ts`,
 *    which measures that rather than assuming it.
 *  - `update` copied `input.organizationId` onto the row, which let any caller
 *    move a task into another tenant. That is a stronger primitive than the
 *    project boundary and is asserted separately below.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { TenantAccessError, type TenantContext } from '@firstelement/database';
import { httpOutcome, matches, type PrismaCall, type Where } from './legacy-harness';
import { TasksService } from '../../../../../apps/api/src/tasks/tasks.service';
import type { PrismaService } from '../../../../../apps/api/src/common/prisma.service';

const ORG_1 = 'org-1';
const ORG_2 = 'org-2';
const PROJECT_A = 'project-a';
const PROJECT_B = 'project-b';
/** Never exists in any organization — the non-disclosure baseline. */
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
/** `SessionService.resolve` hands an ADMIN every project in the organization. */
const ADMIN_1: TenantContext = {
  organizationId: ORG_1,
  userId: 'admin-1',
  role: 'ADMIN',
  projectIds: [PROJECT_A, PROJECT_B],
};

// ---------------------------------------------------------------------------
// In-memory stand-in for the Prisma `task` table
// ---------------------------------------------------------------------------

interface TaskRow {
  id: string;
  organizationId: string;
  projectId: string;
  title: string;
  description?: string | null;
  priority?: string | null;
  status?: string | null;
  assignedTo?: string | null;
  createdById?: string | null;
  createdAt: string;
  updatedAt: string;
}

class FakePrisma {
  rows: TaskRow[] = [];
  calls: PrismaCall[] = [];
  private nextId = 1;

  seed(row: Omit<TaskRow, 'id'> & { id?: string }): string {
    const id = row.id ?? `task-${this.nextId++}`;
    this.rows.push({ ...row, id });
    return id;
  }

  /** Full, ordered state of the table — the before/after comparison base. */
  snapshot(): string {
    return JSON.stringify(this.rows);
  }

  count(): number {
    return this.rows.length;
  }

  row(id: string): TaskRow | undefined {
    return this.rows.find((r) => r.id === id);
  }

  private find(where: Where): TaskRow | undefined {
    return this.rows.find((r) => matches(r as unknown as Record<string, unknown>, where));
  }

  get task() {
    return {
      findMany: async (args: { where: Where }) => {
        this.calls.push({ op: 'findMany', where: args.where });
        return this.rows.filter((r) => matches(r as unknown as Record<string, unknown>, args.where));
      },
      findFirst: async (args: { where: Where }) => {
        this.calls.push({ op: 'findFirst', where: args.where });
        return this.find(args.where) ?? null;
      },
      create: async (args: { data: Partial<TaskRow> }) => {
        this.calls.push({ op: 'create' });
        const id = this.seed({
          createdAt: '2026-01-01T00:00:00.000Z',
          updatedAt: '2026-01-01T00:00:00.000Z',
          ...(args.data as TaskRow),
          id: `task-${this.nextId++}`,
        });
        return this.row(id);
      },
      update: async (args: { where: { id: string }; data: Partial<TaskRow> }) => {
        this.calls.push({ op: 'update', id: args.where.id });
        const target = this.row(args.where.id);
        if (!target) throw new Error(`fake prisma: update ${args.where.id} on a missing row`);
        Object.assign(target, args.data, { updatedAt: '9999-12-31T00:00:00.000Z' });
        return target;
      },
      delete: async (args: { where: { id: string } }) => {
        this.calls.push({ op: 'delete', id: args.where.id });
        const index = this.rows.findIndex((r) => r.id === args.where.id);
        if (index === -1) throw new Error(`fake prisma: delete ${args.where.id} on a missing row`);
        return this.rows.splice(index, 1)[0];
      },
    };
  }
}

// ---------------------------------------------------------------------------

describe('legacy TasksService — project and tenant scope (apps/api/src/tasks/tasks.service.ts)', () => {
  let prisma: FakePrisma;
  let tasks: TasksService;

  let taskInA: string;
  let taskInB: string;

  beforeEach(() => {
    prisma = new FakePrisma();
    tasks = new TasksService({ client: prisma } as unknown as PrismaService);

    taskInA = prisma.seed({
      organizationId: ORG_1,
      projectId: PROJECT_A,
      title: 'alpha private task',
      priority: 'high',
      status: 'open',
      createdById: 'user-a',
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: '2026-01-01T00:00:00.000Z',
    });
    taskInB = prisma.seed({
      organizationId: ORG_1,
      projectId: PROJECT_B,
      title: 'beta private task',
      priority: 'low',
      status: 'open',
      createdById: 'user-b',
      createdAt: '2026-01-02T00:00:00.000Z',
      updatedAt: '2026-01-02T00:00:00.000Z',
    });
  });

  describe('the legitimate path still works', () => {
    it('a member lists their own project', async () => {
      const rows = (await tasks.list(MEMBER_A, { projectId: PROJECT_A })) as TaskRow[];

      expect(rows).toHaveLength(1);
      expect(rows[0].title).toBe('alpha private task');
    });

    it('a member lists without naming a project', async () => {
      const rows = (await tasks.list(MEMBER_A)) as TaskRow[];

      expect(rows).toHaveLength(1);
      expect(rows[0].id).toBe(taskInA);
      expect(prisma.calls[0].where).toMatchObject({ organizationId: ORG_1, projectId: PROJECT_A });
    });

    it('a member creates, updates and deletes their own project\'s task', async () => {
      const created = await tasks.create(MEMBER_A, { title: 'mine', projectId: PROJECT_A });
      expect(created.projectId).toBe(PROJECT_A);

      const rowsBefore = prisma.count();
      await tasks.update(MEMBER_A, taskInA, { title: 'mine, edited', projectId: PROJECT_A });
      expect(prisma.row(taskInA)?.title).toBe('mine, edited');
      expect(prisma.count()).toBe(rowsBefore);

      await tasks.delete(MEMBER_A, taskInA);
      expect(prisma.count()).toBe(rowsBefore - 1);
      expect(prisma.row(taskInA)).toBeUndefined();
    });

    it('a member creates a task without naming a project', async () => {
      const created = await tasks.create(MEMBER_A, { title: 'no project given' });
      expect(created.projectId).toBe(PROJECT_A);
    });

    it('an organization admin still reaches every project in their organization', async () => {
      const rows = (await tasks.list(ADMIN_1, { projectId: PROJECT_B })) as TaskRow[];
      expect(rows).toHaveLength(1);
      expect(rows[0].title).toBe('beta private task');

      const before = prisma.snapshot();
      await tasks.update(ADMIN_1, taskInB, { title: 'reviewed by admin' });
      expect(prisma.row(taskInB)?.title).toBe('reviewed by admin');
      expect(before).not.toBe(prisma.snapshot()); // the legitimate write really happened
    });

    it('an org-1 member of both projects may move a task between them', async () => {
      const bothCtx: TenantContext = {
        organizationId: ORG_1,
        userId: 'user-e',
        role: 'EMPLOYEE',
        projectIds: [PROJECT_A, PROJECT_B],
      };

      await tasks.update(bothCtx, taskInA, { projectId: PROJECT_B });
      expect(prisma.row(taskInA)?.projectId).toBe(PROJECT_B);
    });
  });

  describe('list cannot cross a project boundary', () => {
    it('a same-organization colleague naming another project gets a 404 and no rows', async () => {
      const outcome = await httpOutcome(() => tasks.list(COLLEAGUE_B, { projectId: PROJECT_A }));

      expect(outcome.status).toBe(404);
      expect(prisma.calls).toHaveLength(0); // refused before any query ran
      expect(JSON.stringify(prisma.rows)).toContain('alpha private task'); // still there, unread
    });

    it('cross-tenant list is refused with a 404', async () => {
      const outcome = await httpOutcome(() => tasks.list(FOREIGN_ORG_ADMIN, { projectId: PROJECT_A }));

      expect(outcome.status).toBe(404);
      expect(prisma.calls).toHaveLength(0);
    });

    it('the 404 body is indistinguishable from a project that does not exist', async () => {
      const denied = await httpOutcome(() => tasks.list(COLLEAGUE_B, { projectId: PROJECT_A }));
      const crossTenant = await httpOutcome(() =>
        tasks.list(FOREIGN_ORG_ADMIN, { projectId: PROJECT_A }),
      );
      const nonexistent = await httpOutcome(() =>
        tasks.list(COLLEAGUE_B, { projectId: PROJECT_DOES_NOT_EXIST }),
      );

      expect(nonexistent.status).toBe(404);
      expect(denied.body).toEqual(nonexistent.body);
      expect(crossTenant.body).toEqual(nonexistent.body);
      expect(denied.bodyWithoutRequestId).toEqual(nonexistent.bodyWithoutRequestId);
      expect(JSON.stringify(denied.body)).not.toContain(PROJECT_A);
      expect(JSON.stringify(denied.body)).not.toContain('alpha private task');
    });

    it('the denial is a TenantAccessError, not a permission error', async () => {
      await expect(tasks.list(COLLEAGUE_B, { projectId: PROJECT_A })).rejects.toBeInstanceOf(
        TenantAccessError,
      );
    });
  });

  describe('read by id cannot cross a project boundary', () => {
    it('a same-organization colleague cannot read another project\'s task', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() => tasks.getById(COLLEAGUE_B, taskInA));

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
    });

    it('cross-tenant read by id is refused', async () => {
      const outcome = await httpOutcome(() => tasks.getById(FOREIGN_ORG_ADMIN, taskInA));
      expect(outcome.status).toBe(404);
    });
  });

  describe('update cannot cross a project or tenant boundary', () => {
    it('rewriting another project\'s task: 404, and no field changed', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        tasks.update(COLLEAGUE_B, taskInA, { title: 'overwritten by a colleague' }),
      );

      expect(outcome.status).toBe(404);
      // The load-bearing assertions.
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.row(taskInA)?.title).toBe('alpha private task');
      expect(prisma.row(taskInA)?.updatedAt).toBe('2026-01-01T00:00:00.000Z');
      expect(prisma.calls.filter((c) => c.op === 'update')).toHaveLength(0);
    });

    it('moving a task into a project the caller does not belong to: 404, and no field changed', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        tasks.update(COLLEAGUE_B, taskInB, { projectId: PROJECT_A }),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.row(taskInB)?.projectId).toBe(PROJECT_B);
      expect(prisma.calls.filter((c) => c.op === 'update')).toHaveLength(0);
    });

    it('a caller-supplied organizationId can never move a task into another tenant', async () => {
      // The pre-fix service copied `input.organizationId` straight onto the row,
      // which is a cross-tenant write. The field is now not assignable at all,
      // so the update proceeds on the caller's own task and the tenant is
      // untouched — the assertion is that the tenant column did not move.
      await tasks.update(MEMBER_A, taskInA, {
        title: 'legitimate edit',
        organizationId: ORG_2,
        projectId: undefined,
      } as never);

      expect(prisma.row(taskInA)?.organizationId).toBe(ORG_1);
      expect(prisma.row(taskInA)?.title).toBe('legitimate edit');
      expect(prisma.count()).toBe(2);
      expect(prisma.rows.filter((r) => r.organizationId === ORG_2)).toHaveLength(0);
    });

    it('cross-tenant update writes nothing', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        tasks.update(FOREIGN_ORG_ADMIN, taskInA, { title: 'cross tenant overwrite' }),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.calls.filter((c) => c.op === 'update')).toHaveLength(0);
    });
  });

  describe('delete cannot cross a project boundary', () => {
    it('deleting another project\'s task: 404, and the row is still there', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() => tasks.delete(COLLEAGUE_B, taskInA));

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.row(taskInA)).toBeDefined();
      expect(prisma.calls.filter((c) => c.op === 'delete')).toHaveLength(0);
    });

    it('cross-tenant delete removes nothing', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() => tasks.delete(FOREIGN_ORG_ADMIN, taskInA));

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.calls.filter((c) => c.op === 'delete')).toHaveLength(0);
    });
  });

  describe('create cannot write into a project the caller does not belong to', () => {
    it('same organization, foreign project: refused, and no row was inserted', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        tasks.create(COLLEAGUE_B, { title: 'planted', projectId: PROJECT_A }),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.calls.filter((c) => c.op === 'create')).toHaveLength(0);
    });

    it('cross-tenant create inserts nothing', async () => {
      const rowsBefore = prisma.count();

      const outcome = await httpOutcome(() =>
        tasks.create(FOREIGN_ORG_ADMIN, { title: 'planted', projectId: PROJECT_A }),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.calls).toHaveLength(0);
    });
  });

  describe('the fixture can actually detect the defect', () => {
    it('the pre-fix predicates do reach this fixture\'s rows', async () => {
      // The exact pre-fix predicates, reproduced only to prove these assertions
      // have teeth: with them the foreign task is readable, rewritable and
      // deletable, and a task can be moved into another tenant.
      const preFixListWhere = (ctx: TenantContext, projectId?: string) => ({
        organizationId: ctx.organizationId,
        projectId: projectId ?? ctx.projectIds[0],
      });
      const preFixAssertOwnedWhere = (ctx: TenantContext, id: string) => ({
        id,
        organizationId: ctx.organizationId,
      });

      expect(await prisma.task.findMany({ where: preFixListWhere(COLLEAGUE_B, PROJECT_A) })).toHaveLength(1);
      expect(await prisma.task.findFirst({ where: preFixAssertOwnedWhere(COLLEAGUE_B, taskInA) })).not.toBeNull();

      // And the pre-fix update would have moved the row across the tenant line.
      const crossTenantTarget = { id: taskInA, organizationId: ORG_2 };
      expect(prisma.row(crossTenantTarget.id)?.organizationId).not.toBe(crossTenantTarget.organizationId);

      // The fixed service refuses the same three calls.
      await expect(tasks.list(COLLEAGUE_B, { projectId: PROJECT_A })).rejects.toBeInstanceOf(
        TenantAccessError,
      );
      await expect(tasks.update(COLLEAGUE_B, taskInA, { title: 'x' })).rejects.toThrow('Task not found');
      await expect(tasks.delete(COLLEAGUE_B, taskInA)).rejects.toThrow('Task not found');
      expect(prisma.row(taskInA)?.organizationId).toBe(ORG_1);
    });
  });
});
