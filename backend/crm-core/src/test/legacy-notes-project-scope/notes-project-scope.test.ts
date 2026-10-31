/**
 * Project-scope proof for the LEGACY notes service (`apps/api/src/notes/notes.service.ts`).
 *
 * Why this file exists where it is: `apps/**` is the frozen legacy reference,
 * so the suite cannot live in `apps/api/test/`. It lives in the Spring module's
 * test tree purely as a permitted "standalone harness" and it exercises the real
 * legacy TypeScript service — not a copy of it — by importing it directly.
 *
 * What it proves
 * --------------
 * The defect: `scopedWhere` pinned a caller-supplied `projectId` verbatim
 * (`{ organizationId: ctx.organizationId, projectId }`). `organizationId` is
 * server-derived, so the query could not cross a tenant, but nothing checked
 * project *membership* — and the route guards only enforce `Permission.NOTE_*`,
 * never membership. The service Javadoc claimed otherwise.
 *
 * The load-bearing assertions are about what did NOT change. Every write test
 * snapshots the whole note table (row count and every field of every row) before
 * the attempt and re-compares after, so "threw an error" can never pass on its
 * own: a write that happened and then reported a denial would still fail here.
 *
 * Non-disclosure
 * --------------
 * The denial path is not asserted as "some error". The real
 * `HttpExceptionFilter` is invoked on the real exception the service throws, and
 * the resulting 404 body is compared byte-for-byte against the body produced for
 * a project id that does not exist anywhere. A denial that told the caller the
 * project is there would fail that comparison.
 *
 * The fixture's ability to detect the defect is asserted explicitly in
 * "the pre-fix predicate does leak this fixture's data" below: the original
 * `scopedWhere` body is reproduced locally and shown reaching the foreign note.
 * Without that, a green suite here would prove nothing about teeth.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { TenantAccessError, type TenantContext } from '@firstelement/database';
import {
  httpOutcome,
  matches,
  type PrismaCall,
  type Where,
} from './legacy-harness';
import { NotesService } from '../../../../../apps/api/src/notes/notes.service';
import type { PrismaService } from '../../../../../apps/api/src/common/prisma.service';

// ---------------------------------------------------------------------------
// Fixture identities
// ---------------------------------------------------------------------------

const ORG_1 = 'org-1';
const ORG_2 = 'org-2';
const PROJECT_A = 'project-a';
const PROJECT_B = 'project-b';
const PROJECT_FOREIGN_ORG = 'project-c';
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
  projectIds: [PROJECT_FOREIGN_ORG],
};
/** Same organization as A and B, but not a member of any project. */
const NO_MEMBERSHIP: TenantContext = {
  organizationId: ORG_1,
  userId: 'user-d',
  role: 'EMPLOYEE',
  projectIds: [],
};
/**
 * An org-1 admin. `SessionService.resolve` hands an ADMIN every project in the
 * organization, so this mirrors real membership resolution and is the control
 * that proves the fix did not narrow a legitimate caller.
 */
const ADMIN_1: TenantContext = {
  organizationId: ORG_1,
  userId: 'admin-1',
  role: 'ADMIN',
  projectIds: [PROJECT_A, PROJECT_B],
};

// ---------------------------------------------------------------------------
// In-memory stand-in for the Prisma client
// ---------------------------------------------------------------------------

interface NoteRow {
  id: string;
  organizationId: string;
  projectId: string;
  resourceType: string;
  resourceId: string;
  body: string;
  authorUserId: string;
  author: { id: string; fullName: string } | null;
  createdAt: string;
  updatedAt: string;
}

type Where = Record<string, unknown>;

/**
 * `select` is intentionally ignored — these assertions are about *which rows*
 * are reachable, never about projection. The `where` matcher itself lives in
 * `./legacy-harness` so both legacy suites share one implementation.
 */

class FakePrisma {
  rows: NoteRow[] = [];
  /** Every call the service made, so "nothing was touched" is measurable. */
  calls: PrismaCall[] = [];
  private nextId = 1;

  seed(row: Omit<NoteRow, 'id'> & { id?: string }): string {
    const id = row.id ?? `note-${this.nextId++}`;
    this.rows.push({ ...row, id });
    return id;
  }

  /** Full, ordered state of the table. Used for before/after comparison. */
  snapshot(): string {
    return JSON.stringify(this.rows);
  }

  count(): number {
    return this.rows.length;
  }

  row(id: string): NoteRow | undefined {
    return this.rows.find((r) => r.id === id);
  }

  get note() {
    return {
      findMany: async (args: { where: Where; take?: number }) => {
        this.calls.push({ op: 'findMany', where: args.where });
        const hits = this.rows.filter((r) =>
          matches(r as unknown as Record<string, unknown>, args.where),
        );
        const ordered = [...hits].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        return args.take === undefined ? ordered : ordered.slice(0, args.take);
      },
      findFirst: async (args: { where: Where }) => {
        this.calls.push({ op: 'findFirst', where: args.where });
        return (
          this.rows.find((r) => matches(r as unknown as Record<string, unknown>, args.where)) ?? null
        );
      },
      create: async (args: { data: NoteRow }) => {
        this.calls.push({ op: 'create' });
        const id = this.seed({ ...args.data, id: `note-${this.nextId++}` });
        return this.row(id);
      },
      update: async (args: { where: { id: string }; data: Partial<NoteRow> }) => {
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
// HTTP error rendering lives in `./legacy-harness`, shared with the tasks suite
// so both services are judged against one identical rendering of a denial.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------

describe('legacy NotesService — project scope (apps/api/src/notes/notes.service.ts)', () => {
  let prisma: FakePrisma;
  let notes: NotesService;

  let noteInA: string;
  let secondNoteInA: string;
  let noteInB: string;

  beforeEach(() => {
    prisma = new FakePrisma();
    notes = new NotesService({ client: prisma } as unknown as PrismaService);

    noteInA = prisma.seed({
      organizationId: ORG_1,
      projectId: PROJECT_A,
      resourceType: 'Conversation',
      resourceId: 'conversation-a',
      body: 'alpha private body',
      authorUserId: 'user-a',
      author: { id: 'user-a', fullName: 'Amina A' },
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: '2026-01-01T00:00:00.000Z',
    });
    secondNoteInA = prisma.seed({
      organizationId: ORG_1,
      projectId: PROJECT_A,
      resourceType: 'Customer',
      resourceId: 'customer-a',
      body: 'second note in A',
      authorUserId: 'user-a',
      author: { id: 'user-a', fullName: 'Amina A' },
      createdAt: '2026-01-02T00:00:00.000Z',
      updatedAt: '2026-01-02T00:00:00.000Z',
    });
    noteInB = prisma.seed({
      organizationId: ORG_1,
      projectId: PROJECT_B,
      resourceType: 'Conversation',
      resourceId: 'conversation-b',
      body: 'beta private body',
      authorUserId: 'user-b',
      author: { id: 'user-b', fullName: 'Bilal B' },
      createdAt: '2026-01-03T00:00:00.000Z',
      updatedAt: '2026-01-03T00:00:00.000Z',
    });
  });

  describe('the legitimate path still works', () => {
    it('a member reads their own project', async () => {
      const rows = (await notes.listProject(MEMBER_A, PROJECT_A)) as NoteRow[];

      expect(rows).toHaveLength(2);
      expect(rows.map((r) => r.id).sort()).toEqual([noteInA, secondNoteInA].sort());
      expect(rows.map((r) => r.body).sort()).toEqual(
        ['alpha private body', 'second note in A'].sort(),
      );
    });

    it('a member reads notes for a record in their own project, with and without projectId', async () => {
      const pinned = await notes.listForResource(
        MEMBER_A,
        'Conversation',
        'conversation-a',
        PROJECT_A,
      );
      const unpinned = await notes.listForResource(MEMBER_A, 'Conversation', 'conversation-a');

      expect(pinned).toHaveLength(1);
      expect(unpinned).toHaveLength(1);
      expect((pinned as NoteRow[])[0].body).toBe('alpha private body');
    });

    it('an unpinned read reaches only the caller\'s own projects, not the whole organization', async () => {
      const rows = (await notes.listForResource(MEMBER_A, 'Conversation', 'conversation-b')) as NoteRow[];

      expect(rows).toHaveLength(0);
      expect(JSON.stringify(rows)).not.toContain('beta private body');
    });

    it('a principal with no membership receives nothing at all (FR-1.4)', async () => {
      const rows = (await notes.listForResource(NO_MEMBERSHIP, 'Conversation', 'conversation-a')) as NoteRow[];

      expect(rows).toEqual([]);
      expect(prisma.calls.at(-1)?.where).toMatchObject({ organizationId: ORG_1, projectId: { in: [] } });
    });

    it('an organization admin still reads and writes every project in their organization', async () => {
      const rows = (await notes.listProject(ADMIN_1, PROJECT_B)) as NoteRow[];
      expect(rows).toHaveLength(1);
      expect(rows[0].body).toBe('beta private body');

      const before = prisma.snapshot();
      const updated = await notes.update(ADMIN_1, noteInB, 'reviewed by admin');
      expect(updated.body).toBe('reviewed by admin');
      expect(prisma.row(noteInB)?.body).toBe('reviewed by admin');
      expect(before).not.toBe(prisma.snapshot()); // the legitimate write really happened
    });

    it('a member can still update and delete their own project\'s note', async () => {
      await notes.update(MEMBER_A, noteInA, 'my own edit', PROJECT_A);
      expect(prisma.row(noteInA)?.body).toBe('my own edit');

      await notes.delete(MEMBER_A, noteInA, PROJECT_A);
      expect(prisma.count()).toBe(2);
      expect(prisma.row(noteInA)).toBeUndefined();
    });

    it('a member can still create a note in their own project', async () => {
      const before = prisma.count();
      const created = await notes.create(MEMBER_A, 'Conversation', 'conversation-a', 'new note', PROJECT_A);

      expect(created.projectId).toBe(PROJECT_A);
      expect(prisma.count()).toBe(before + 1);
    });
  });

  describe('a same-organization colleague cannot read another project', () => {
    it('listProject is refused with a 404', async () => {
      const outcome = await httpOutcome(() => notes.listProject(COLLEAGUE_B, PROJECT_A));

      expect(outcome.status).toBe(404);
      expect(prisma.calls).toHaveLength(0); // refused before any query ran
    });

    it('listForResource is refused with a 404 when it names another project', async () => {
      const outcome = await httpOutcome(() =>
        notes.listForResource(COLLEAGUE_B, 'Conversation', 'conversation-a', PROJECT_A),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.calls).toHaveLength(0);
    });

    it('the 404 body is indistinguishable from a project that does not exist', async () => {
      const denied = await httpOutcome(() => notes.listProject(COLLEAGUE_B, PROJECT_A));
      const nonexistent = await httpOutcome(() =>
        notes.listProject(COLLEAGUE_B, PROJECT_DOES_NOT_EXIST),
      );

      expect(nonexistent.status).toBe(404);
      expect(denied.body).toEqual(nonexistent.body);
      expect(denied.bodyWithoutRequestId).toEqual(nonexistent.bodyWithoutRequestId);
      // Nothing in the response may name the project that was denied.
      expect(JSON.stringify(denied.body)).not.toContain(PROJECT_A);
      expect(JSON.stringify(denied.body)).not.toContain('Amina');
      expect(JSON.stringify(denied.body)).not.toContain('alpha private body');
    });

    it('the denial is a TenantAccessError, not a permission error', async () => {
      await expect(notes.listProject(COLLEAGUE_B, PROJECT_A)).rejects.toBeInstanceOf(
        TenantAccessError,
      );
    });
  });

  describe('cross-tenant stays refused and non-disclosing', () => {
    it('a foreign organization\'s project id is refused with a 404', async () => {
      const outcome = await httpOutcome(() => notes.listProject(FOREIGN_ORG_ADMIN, PROJECT_A));

      expect(outcome.status).toBe(404);
      expect(prisma.calls).toHaveLength(0);
    });

    it('cross-tenant and cross-project denials produce the same body', async () => {
      const crossTenant = await httpOutcome(() => notes.listProject(FOREIGN_ORG_ADMIN, PROJECT_A));
      const crossProject = await httpOutcome(() => notes.listProject(COLLEAGUE_B, PROJECT_A));
      const nonexistent = await httpOutcome(() =>
        notes.listProject(FOREIGN_ORG_ADMIN, PROJECT_DOES_NOT_EXIST),
      );

      expect(crossTenant.body).toEqual(crossProject.body);
      expect(crossTenant.body).toEqual(nonexistent.body);
      expect(crossTenant.bodyWithoutRequestId).toEqual(nonexistent.bodyWithoutRequestId);
    });

    it('every per-request difference is the requestId echo alone', async () => {
      const a = await httpOutcome(() => notes.listProject(FOREIGN_ORG_ADMIN, PROJECT_A), 'req-a');
      const b = await httpOutcome(() => notes.listProject(FOREIGN_ORG_ADMIN, PROJECT_A), 'req-b');

      expect(a.bodyWithoutRequestId).toEqual(b.bodyWithoutRequestId);
      expect(Object.keys(a.body).sort()).toEqual(Object.keys(b.body).sort());
      expect(a.body.requestId).toBe('req-a');
      expect(b.body.requestId).toBe('req-b');
    });
  });

  describe('update cannot cross a project boundary', () => {
    it('naming the other project: 404, and no row was written', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();
      const bodyBefore = prisma.row(noteInA)?.body;
      const updatedAtBefore = prisma.row(noteInA)?.updatedAt;

      const outcome = await httpOutcome(() =>
        notes.update(COLLEAGUE_B, noteInA, 'overwritten by a colleague', PROJECT_A),
      );

      expect(outcome.status).toBe(404);
      // The load-bearing assertions: nothing changed.
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.row(noteInA)?.body).toBe(bodyBefore);
      expect(prisma.row(noteInA)?.updatedAt).toBe(updatedAtBefore);
      expect(prisma.calls.filter((c) => c.op === 'update')).toHaveLength(0);
    });

    it('omitting projectId (the optional body field): 404, and no row was written', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      // `PATCH /notes/:id` declares `projectId?: string`, so omitting it is a
      // caller-reachable shape that previously fell back to organization scope.
      const outcome = await httpOutcome(() =>
        notes.update(COLLEAGUE_B, noteInA, 'overwritten without a projectId'),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.row(noteInA)?.body).toBe('alpha private body');
      expect(prisma.calls.filter((c) => c.op === 'update')).toHaveLength(0);
      // An unpinned write is bounded by the caller's *project set*, so the
      // lookup that runs cannot reach a note outside it. Before the fix this
      // same call filtered on organizationId alone and found the note.
      expect(prisma.calls).toHaveLength(1);
      expect(prisma.calls[0]).toMatchObject({
        op: 'findFirst',
        where: { organizationId: ORG_1, projectId: { in: [PROJECT_B] }, id: noteInA },
      });
    });

    it('cross-tenant update writes nothing', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        notes.update(FOREIGN_ORG_ADMIN, noteInA, 'cross tenant overwrite', PROJECT_A),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.calls).toHaveLength(0);
    });

    it('a member cannot update a note in a project they are not a member of, even by note id alone', async () => {
      const outcome = await httpOutcome(() => notes.update(COLLEAGUE_B, noteInA, 'nope', PROJECT_B));

      expect(outcome.status).toBe(404);
      expect(prisma.row(noteInA)?.body).toBe('alpha private body');
      expect(prisma.calls.filter((c) => c.op === 'update')).toHaveLength(0);
    });
  });

  describe('delete cannot cross a project boundary', () => {
    it('naming the other project: 404, and no row was deleted', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() => notes.delete(COLLEAGUE_B, noteInA, PROJECT_A));

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.row(noteInA)).toBeDefined();
      expect(prisma.calls.filter((c) => c.op === 'delete')).toHaveLength(0);
    });

    it('omitting projectId (the optional query field): 404, and no row was deleted', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() => notes.delete(COLLEAGUE_B, noteInA));

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.row(noteInA)).toBeDefined();
      expect(prisma.calls.filter((c) => c.op === 'delete')).toHaveLength(0);
      expect(prisma.calls).toHaveLength(1);
      expect(prisma.calls[0]).toMatchObject({
        op: 'findFirst',
        where: { organizationId: ORG_1, projectId: { in: [PROJECT_B] }, id: noteInA },
      });
    });

    it('cross-tenant delete removes nothing', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() => notes.delete(FOREIGN_ORG_ADMIN, noteInA, PROJECT_A));

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.row(noteInA)).toBeDefined();
    });
  });

  describe('create cannot write into a project the caller is not a member of', () => {
    it('same organization, foreign project: refused, and no row was inserted', async () => {
      const rowsBefore = prisma.count();
      const stateBefore = prisma.snapshot();

      const outcome = await httpOutcome(() =>
        notes.create(COLLEAGUE_B, 'Conversation', 'conversation-a', 'planted', PROJECT_A),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.snapshot()).toBe(stateBefore);
      expect(prisma.calls.filter((c) => c.op === 'create')).toHaveLength(0);
    });

    it('cross-tenant create inserts nothing', async () => {
      const rowsBefore = prisma.count();

      const outcome = await httpOutcome(() =>
        notes.create(FOREIGN_ORG_ADMIN, 'Conversation', 'conversation-a', 'planted', PROJECT_A),
      );

      expect(outcome.status).toBe(404);
      expect(prisma.count()).toBe(rowsBefore);
      expect(prisma.calls).toHaveLength(0);
    });
  });

  describe('the fixture can actually detect the defect', () => {
    it('the pre-fix predicate does leak this fixture\'s data', async () => {
      // The exact pre-fix `scopedWhere` body, reproduced here only to prove the
      // assertions above have teeth: with this predicate the foreign note is
      // reachable, so a green suite after the fix means the fix changed the
      // outcome rather than the fixture being blind.
      const preFixScopedWhere = (
        ctx: TenantContext,
        projectId: string | undefined,
        extra: Record<string, unknown>,
      ) => {
        const base = projectId
          ? { organizationId: ctx.organizationId, projectId }
          : { organizationId: ctx.organizationId };
        return { ...base, ...extra };
      };

      const leakyRead = await prisma.note.findMany({
        where: preFixScopedWhere(COLLEAGUE_B, PROJECT_A, {}),
      });
      expect(leakyRead).toHaveLength(2); // project A's notes, to a non-member

      const leakyAssertOwned = await prisma.note.findFirst({
        where: preFixScopedWhere(COLLEAGUE_B, undefined, { id: noteInA }),
      });
      expect(leakyAssertOwned).not.toBeNull(); // the write path was open too

      // And the fixed service refuses the same two calls.
      await expect(notes.listProject(COLLEAGUE_B, PROJECT_A)).rejects.toBeInstanceOf(
        TenantAccessError,
      );
      // The unpinned write is refused too — by the project-set filter rather
      // than a membership throw, because `accessibleProjectsScope` simply cannot
      // match a note outside the caller's projects.
      const before = prisma.count();
      await expect(notes.update(COLLEAGUE_B, noteInA, 'x')).rejects.toThrow('Note not found');
      expect(prisma.count()).toBe(before);
    });
  });
});
