/**
 * Seeds a disposable E2E database.
 *
 * ## What this is for
 *
 * The Phase-11 journeys drive a **real** Spring application. "Realtime reconnect
 * and resync", "session expiry" and "empty state" are claims about behaviour
 * against a real tenant filter, a real cookie session and a real SSE stream; a
 * recorded fixture would prove nothing about any of them. So the suite needs a
 * tenant with a user, a project and a membership, and this is what creates it.
 *
 * The suite signs in as **one account per matrix project** (see `e2e/matrix.ts`)
 * so the login limiter's account bucket is never saturated. Each of those accounts
 * gets its own organization, because `users.email` is globally unique in the
 * schema and a second tenant cannot share one — which is also what makes the seed
 * and the teardown agree: both derive from the same email, by the same function.
 *
 * ## Why it refuses to touch anything else
 *
 * Three refusals, mirroring `ops/quality/checks/prepare-throwaway-db.ps1`:
 *
 * 1. the target database name must contain one of `check`, `rehearsal`, `gate`,
 *    `throwaway`, `scratch`, `tmp` — a test seed one typo away from development is
 *    the failure this whole pattern exists to prevent;
 * 2. the name must not be `firstelement`;
 * 3. it refuses to run unless the caller also asks for the destruction it will
 *    need, because a leftover seeded tenant in a scratch database is harmless and
 *    a leftover one anywhere else is not.
 *
 * ## Credentials
 *
 * The password is read from `FE_E2E_PASSWORD` and is never defaulted, never
 * printed and never committed. `AGENTS.md`: never read or print `.env`, production
 * credentials or session tokens. The seed writes a **bcrypt hash**, exactly as the
 * application does, so no plaintext password ever reaches the database and the
 * login endpoint is exercised through its real verification path.
 */
import { execFileSync } from 'node:child_process';
import { genSaltSync, hashSync } from 'bcryptjs';

export interface SeedTarget {
  readonly container: string;
  readonly database: string;
  readonly user: string;
}

export interface SeededAccount {
  readonly organizationId: string;
  readonly projectId: string;
  readonly userId: string;
  readonly email: string;
  readonly fullName: string;
  readonly role: 'ADMIN';
  /** Never logged. Returned so a caller can use it, not so a report can print it. */
  readonly password: string;
}

const DISPOSABLE_MARKERS = /(check|rehearsal|gate|throwaway|scratch|tmp)/i;
const PROTECTED_DATABASE = 'firstelement';

export class RefusedSeed extends Error {}

/** Validates the target before anything is written. */
export function assertDisposable(target: SeedTarget): void {
  if (!target.database || target.database === PROTECTED_DATABASE) {
    throw new RefusedSeed(
      `refusing to seed "${target.database}": it is the protected development database.`,
    );
  }
  if (!DISPOSABLE_MARKERS.test(target.database)) {
    throw new RefusedSeed(
      `refusing to seed "${target.database}": the name must contain one of check, ` +
        'rehearsal, gate, throwaway, scratch, tmp so it cannot be mistaken for development.',
    );
  }
  if (!/^[A-Za-z0-9_]+$/.test(target.database)) {
    throw new RefusedSeed(`refusing to seed "${target.database}": not a plain identifier.`);
  }
}

/** Runs SQL against the container's local socket. Never takes a password. */
export function psql(target: SeedTarget, sql: string): string {
  return execFileSync(
    'docker',
    [
      'exec',
      '-i',
      target.container,
      'psql',
      '-U',
      target.user,
      '-d',
      target.database,
      '-v',
      'ON_ERROR_STOP=1',
      '-t',
      '-A',
      '-f',
      '-',
    ],
    { input: sql, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 },
  );
}

/**
 * Deterministic ids and slugs, so a re-run updates the same rows rather than
 * accumulating tenants, and so **teardown removes exactly what seed created**.
 *
 * ## Why the slug is derived too
 *
 * The organisation's real uniqueness in the schema is `organizations.slug`, not
 * the primary key: `organizations_slug_key` is a unique index on its own. An
 * earlier version of this seed hard-coded `slug = 'e2e-tenant'` while deriving
 * `id` from the email, which produced the worst possible combination —
 *
 * - `ON CONFLICT ("id") DO NOTHING` guards the wrong key, so a second seed with a
 *   different email did not become a no-op: it raised
 *   `duplicate key value violates unique constraint "organizations_slug_key"`,
 *   against a database that looked empty because the surviving row had an id the
 *   caller no longer knew about;
 * - and `dropTenant`, which deleted by the derived id, removed nothing. So the row
 *   outlived the teardown, and every later seed failed against it. The symptom
 *   pointed at the seed; the cause was in both tools.
 *
 * Deriving both from the same input means the two tools cannot disagree: whatever
 * email seeded, the same email tears down, and the slug that survives is the slug
 * teardown looks for.
 *
 * ## What the id is
 *
 * A UUID-shaped value derived from the email by FNV-1a, not a random source. It is
 * a fixture identifier and nothing else; the application's authorization never
 * reads anything from it but the row it points at.
 */
export interface SeedIdentity {
  readonly organizationId: string;
  readonly projectId: string;
  readonly userId: string;
  readonly organizationSlug: string;
  readonly projectSlug: string;
}

/** FNV-1a, formatted as a UUID. Deterministic across runs and platforms. */
function uuidFor(label: string, email: string): string {
  let hash = 2166136261;
  const input = `${label}:${email}`;
  for (let index = 0; index < input.length; index += 1) {
    hash ^= input.charCodeAt(index);
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  const hex = hash.toString(16).padStart(8, '0').repeat(4).slice(0, 32);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
}

/**
 * A short, stable, slug-safe token derived from the same email.
 *
 * Eight hex characters is plenty: this exists to make the slug unique per email,
 * not to be unguessable, and the database is a disposable one this script refuses
 * to point anywhere but a clearly-throwaway name.
 */
function slugTokenFor(email: string): string {
  let hash = 2166136261;
  for (let index = 0; index < email.length; index += 1) {
    hash ^= email.charCodeAt(index);
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

export function idsFor(email: string): SeedIdentity {
  const token = slugTokenFor(email);
  return {
    organizationId: uuidFor('org', email),
    projectId: uuidFor('project', email),
    userId: uuidFor('user', email),
    organizationSlug: `e2e-${token}`,
    projectSlug: `e2e-project-${token}`,
  };
}

/**
 * Writes one tenant, one project, one administrator and one membership.
 *
 * Idempotent against **every** key the schema actually enforces — `id`, `slug`,
 * and `email` — not just the primary key. See `idsFor` for what happened when it
 * guarded only `id`.
 */
export function seedTenant(target: SeedTarget, account: SeededAccount): void {
  assertDisposable(target);

  // bcrypt at cost 12. Spring's `BCryptPasswordEncoder` accepts the `$2a$`, `$2b$`
  // and `$2y$` prefixes (its own pattern is `\$2(a|y|b)?\$`), and the cost here
  // matches the verifier, so the seed exercises the application's *real* bcrypt
  // verification path rather than a bypass. Nothing is stored in plaintext.
  const passwordHash = hashSync(account.password, genSaltSync(12));
  const org = account.organizationId;
  const project = account.projectId;
  const user = account.userId;
  const email = account.email.replace(/'/g, "''");
  const name = account.fullName.replace(/'/g, "''");

  const identity = idsFor(account.email);
  const orgSlug = identity.organizationSlug;
  const projectSlug = identity.projectSlug;

  psql(
    target,
    `
BEGIN;
SET search_path = '';
-- defaultLocale and timezone are NOT NULL with a default, stated here so a reader
-- can see that the seed matches what the application creates rather than relying
-- on a database default that a migration might change.
--
-- Every insert below is guarded on the key the schema really enforces, which is
-- not only the primary key: 'organizations_slug_key' and 'users_email_key' are
-- unique in their own right. The organisation is keyed on 'slug' and the user on
-- 'email' because those are the columns a conflicting row would collide on, and
-- the DO UPDATE branch is what makes a re-seed refresh the password rather than
-- silently leaving a stale hash that no journey can sign in with.
INSERT INTO "public"."organizations" ("id", "name", "slug", "defaultLocale", "timezone",
  "isActive", "createdAt", "updatedAt")
VALUES ('${org}', 'E2E Tenant', '${orgSlug}', 'ar'::public.\"Locale\", 'Asia/Riyadh', true, now(), now())
ON CONFLICT ("slug") DO UPDATE SET "updatedAt" = now();

INSERT INTO "public"."projects" ("id", "organizationId", "name", "slug", "defaultLocale",
  "timezone", "isArchived", "createdAt", "updatedAt")
VALUES ('${project}', '${org}', 'E2E Project', '${projectSlug}', 'ar'::public.\"Locale\", 'Asia/Riyadh',
  false, now(), now())
ON CONFLICT ("organizationId", "slug") DO UPDATE SET "updatedAt" = now();

INSERT INTO "public"."users" ("id", "organizationId", "email", "passwordHash", "fullName",
  "role", "locale", "isActive", "createdAt", "updatedAt")
VALUES ('${user}', '${org}', '${email}', '${passwordHash}', '${name}',
  'ADMIN'::public.\"UserRole\", 'ar'::public.\"Locale\", true, now(), now())
ON CONFLICT ("email") DO UPDATE
  SET "passwordHash" = EXCLUDED."passwordHash", "isActive" = true, "updatedAt" = now();

-- The membership's own id is generated, so it is matched on the triple that makes
-- it unique in practice rather than on an id neither caller can predict.
INSERT INTO "public"."project_memberships"
  ("id", "organizationId", "projectId", "userId", "createdAt")
SELECT gen_random_uuid(), '${org}', '${project}', '${user}', now()
WHERE NOT EXISTS (
  SELECT 1 FROM "public"."project_memberships"
   WHERE "organizationId" = '${org}' AND "projectId" = '${project}' AND "userId" = '${user}'
);

-- An enabled MOCK channel connection.
--
-- This exists so the journeys can put a *real* conversation in the queue.
-- POST /api/v1/dev/mock/incoming-message is the backend's own development inbound
-- simulator, it is in the committed contract, and it drives the real pipeline
-- (resolve identity -> find-or-create the thread -> store idempotently -> publish the
-- timeline event) rather than inserting a row. It has two gates, and the second one
-- is this insert: the controller refuses unless the project already has an enabled
-- MOCK connection, so a seeded tenant needs one before the simulator will answer.
--
-- 'credentials' and 'webhookSecret' are left NULL deliberately. No secret is written
-- to the database by this harness, and nothing reads them: MOCK has no outbound leg.
--
-- Guarded on 'channel_connections_projectId_channel_key', which is the constraint
-- that actually fires -- the same reason the organisation insert is keyed on 'slug'.
INSERT INTO "public"."channel_connections"
  ("id", "organizationId", "projectId", "channel", "displayName", "isEnabled", "status",
   "createdAt", "updatedAt")
VALUES (gen_random_uuid(), '${org}', '${project}', 'MOCK'::public."ChannelType",
  'E2E Inbound Simulator', true, 'CONNECTED'::public."ChannelConnectionStatus",
  now(), now())
ON CONFLICT ("projectId", "channel") DO UPDATE
  SET "isEnabled" = true, "status" = 'CONNECTED'::public."ChannelConnectionStatus",
      "updatedAt" = now();

-- ---------------------------------------------------------------------------
-- The clinic roster and schedule.
--
-- ADDED IN PHASE 38, and the absence was a COVERAGE HOLE rather than a tidiness gap.
--
-- Phase 37 built the clinic read model, and its unit and integration suites seed
-- rows (ClinicFixture). The BROWSER matrix had no equivalent, so the clinic screen
-- rendered empty rosters and no metric cards on every run -- and the two journeys
-- that existed asserted the PRE-Phase-37 gap screen, so nothing noticed. A browser
-- journey that never sees a row cannot test a table, a drill-through disclosure, or
-- a responsive layout. The overflow journey was in fact passing for the wrong
-- reason: there was nothing on the page to overflow.
--
-- So these are test fixtures for the browser matrix, written by the same harness
-- that writes the tenant. That is a different thing from SEEDING PRODUCT DATA,
-- which was ruled against for the export catalogue: nothing here invents a business
-- fact, and every row is removed by the same teardown.
--
-- Tenant integrity is not incidental here. Phase 35 gave the appointments table four
-- COMPOSITE tenant foreign keys -- (customerId, organizationId), (doctorId,
-- organizationId), (procedureId, organizationId) and (createdById,
-- organizationId) -- so an appointment naming a row in another tenant is now
-- refused BY THE DATABASE. Every id below belongs to the seeded org/project, so
-- the constraint holds rather than being avoided.
--
-- patientPhone and patientEmail are deliberately POPULATED, because
-- appointment:read names the schedule and not a patient's contact details, and
-- the journey asserting the screen withholds them is only meaningful if the
-- underlying row has something to withhold.
-- ---------------------------------------------------------------------------
INSERT INTO "public"."doctors"
  ("id", "organizationId", "projectId", "name", "specialty", "department", "email", "phone",
   "workingDays", "startHour", "endHour", "slotDuration", "isActive", "createdAt", "updatedAt")
VALUES
  (gen_random_uuid(), '${org}', '${project}', 'د. لينا الحاج', 'طباعة أسنان', 'العناية العامة',
   'e2e.clinician.one@firstelement.test', '+962700000001', ARRAY[1,2,3,4,5]::int[],
   '09:00', '17:00', 30, true, now(), now()),
  (gen_random_uuid(), '${org}', '${project}', 'د. عمر النجار', 'جراحة عامة', 'العناية العامة',
   'e2e.clinician.two@firstelement.test', '+962700000002', ARRAY[2,3,4]::int[],
   '10:00', '18:00', 45, true, now(), now());

INSERT INTO "public"."procedures"
  ("id", "organizationId", "projectId", "name", "specialty", "durationMinutes", "bufferMinutes",
   "price", "isActive", "createdAt", "updatedAt")
VALUES
  (gen_random_uuid(), '${org}', '${project}', 'استشارة', 'طباعة أسنان', 30, 0,
   40.0, true, now(), now()),
  (gen_random_uuid(), '${org}', '${project}', 'غسيل أسنان', 'طباعة أسنان', 45, 5,
   70.0, true, now(), now());

-- One appointment per status the funnel groups by, so every bar is populated and the
-- drill-through opens more than one id.
--
-- THE DATES GO BACKWARDS, and that is load-bearing rather than cosmetic. The screen
-- asks for a TRAILING 30-DAY WINDOW ENDING NOW (WINDOW_DAYS = 30,
-- isoDaysAgo(30) .. now), so future-dated fixtures fall outside it: the schedule
-- renders its empty state, no metric card appears, and the drill-through control
-- does not exist to be opened. The first attempt at this fixture used now() + n days
-- and every clinic journey failed on an element that was never rendered. Fixtures
-- must land inside the window the screen actually asks for, or the matrix silently
-- tests an empty state forever.
--
-- Relative rather than fixed for the same reason the Phase-11 fixtures are: the
-- window moves, so a hard-coded date would age out of it.
--
-- customerId is deliberately NULL: an appointment need not name a customer, and a
-- seeded one must not borrow a conversation's customer across the identity path.
INSERT INTO "public"."appointments"
  ("id", "organizationId", "projectId", "customerId", "doctorId", "procedureId", "title",
   "patientName", "patientPhone", "patientEmail", "appointmentDate", "durationMinutes",
   "status", "service", "doctorName", "isAiProposed", "createdById", "createdAt", "updatedAt")
SELECT
  gen_random_uuid(), '${org}', '${project}', NULL, d."id", p."id",
  'موعد متابعة', 'مريض تجريبي', '+962700000099', 'e2e.patient@firstelement.test',
  now() - (g.n + 1) * interval '1 day', 30, s."status",
  p."name", d."name", false, '${user}', now(), now()
-- CONFIRMED, not APPROVED. DEC-017 ruled APPROVED out of the seven DEC-003 statuses: the
-- only deployed booking path wrote it with no human approval anywhere, so "the AI booked
-- this" and "a person confirmed this" had become one value. These rows are AI proposals a
-- person confirmed, which is what CONFIRMED says and what the seed actually means. Leaving
-- APPROVED here made every e2e run fail at globalSetup with a check-constraint error, which
-- is the browser gate reporting that the seed had not been kept in step with the migration.
FROM (VALUES ('PENDING'), ('CONFIRMED'), ('COMPLETED'), ('CANCELLED'), ('NO_SHOW')) AS s("status"),
  LATERAL (SELECT "id", "name" FROM "public"."doctors"
            WHERE "organizationId" = '${org}' ORDER BY "name" LIMIT 1) d,
  LATERAL (SELECT "id", "name" FROM "public"."procedures"
            WHERE "organizationId" = '${org}' ORDER BY "name" LIMIT 1) p,
  LATERAL (SELECT generate_series(0, 1) AS n) g;
COMMIT;
`,
  );
}

/**
 * Removes the seeded tenant. Idempotent, and refuses a protected target.
 *
 * Deletes **by slug and by email** as well as by id, and that redundancy is the
 * point. `idsFor` derives both the ids and the slugs from the email, so a
 * teardown with the same email removes the rows that seed wrote — but a teardown
 * after an id-derivation change would otherwise delete nothing and leave the
 * tenant behind, which is the failure this whole file was rewritten to remove.
 *
 * Matching on the slug prefix as well means a tenant seeded by an *older* build of
 * this script is still cleaned up by the current one, instead of accumulating.
 *
 * ## Why `account` is accepted and not used
 *
 * The signature takes the identity that seeded, so the caller reads as
 * seed-then-teardown rather than as two unrelated calls — and so a future caller
 * that *does* need per-tenant behaviour has the argument. The sweep is by prefix
 * because that is what makes the round trip complete; `noUnusedParameters` is
 * disabled in `tsconfig.json` so an intentionally-accepted argument is not a
 * build error.
 */
export function dropTenant(target: SeedTarget, account: SeededAccount): void {
  assertDisposable(target);
  void account;

  // Every organization this script owns is one whose slug begins `e2e-`. That is a
  // prefix this file invents, in a database name `assertDisposable` has already
  // refused to let be anything but clearly throwaway, so sweeping the whole prefix
  // is safe here and is what makes the round trip complete.
  //
  // It also cleans up tenants written by an *earlier* build — the version that
  // hard-coded `slug = 'e2e-tenant'`, whose rows an id-targeted teardown could
  // never see and which therefore accumulated until seeding failed against them.
  psql(
    target,
    `
BEGIN;
SET search_path = '';

-- ## Why this block exists rather than a hand-written list of tables
--
-- Everything under 'organizations' and under 'projects' is ON DELETE CASCADE, so one
-- DELETE on the organization removes the conversations, customers, notes, channel
-- connections and audit events a journey created. Almost.
--
-- The exception is the set of foreign keys onto 'users' declared **RESTRICT**:
-- admin_audit_events.actorUserId, ai_approvals.requestedByUserId, export_jobs.*,
-- finance_*, security_events.* and thirty-odd others. A RESTRICT child is not removed
-- by cascading its own parent away -- PostgreSQL raises the error instead -- and the
-- firing order between the dozens of CASCADE edges is not something to depend on. So
-- a journey that recorded an audit event made 'DELETE FROM users' fail, the
-- transaction rolled back, and the teardown left the tenant behind.
--
-- Enumerating those tables by hand would be correct on the day it was written and
-- silently incomplete the day a migration adds the next one -- which is how this
-- defect happened once already. So the list is read from the catalog at run time:
-- every table with a RESTRICT foreign key onto 'users' that also carries an
-- 'organizationId', scoped to this harness's slug prefix.
--
-- It cannot fall behind the schema, and it stays readable because the block states its
-- own rule. A RESTRICT child *without* an 'organizationId' would be missed, and the
-- failure mode is the refusal below naming the situation -- loud, not silent.
DO $teardown$
DECLARE
  blocker record;
BEGIN
  FOR blocker IN
    SELECT child.relname AS table_name
      FROM pg_constraint con
      JOIN pg_class child ON child.oid = con.conrelid
      JOIN pg_class parent ON parent.oid = con.confrelid
      JOIN pg_namespace ns ON ns.oid = child.relnamespace
     WHERE con.contype = 'f'
       AND con.confdeltype = 'r'          -- RESTRICT
       AND parent.relname = 'users'
       AND ns.nspname = 'public'
       AND EXISTS (
         SELECT 1 FROM pg_attribute a
          WHERE a.attrelid = con.conrelid
            AND a.attname = 'organizationId'
            AND a.attnum > 0
            AND NOT a.attisdropped
       )
  LOOP
    EXECUTE format(
      'DELETE FROM public.%I WHERE "organizationId" IN (SELECT "id" FROM public."organizations" WHERE "slug" LIKE %L)',
      blocker.table_name,
      'e2e-%'
    );
  END LOOP;
END
$teardown$;

-- Sessions first: a live cookie session is a row here, and leaving one behind would
-- let a stale browser session outlive the tenant it belonged to.
DELETE FROM "public"."sessions"
  WHERE "userId" IN (
    SELECT "id" FROM "public"."users"
     WHERE "organizationId" IN (SELECT "id" FROM "public"."organizations" WHERE "slug" LIKE 'e2e-%')
  );

-- Then the organization. CASCADE removes projects, users, memberships, conversations,
-- customers, notes, channel connections and every other child in one statement, which
-- is why there is no table list below to keep up to date.
DELETE FROM "public"."organizations" WHERE "slug" LIKE 'e2e-%';
COMMIT;
`,
  );

  // The deletes above cover every table this script writes. A journey that wrote
  // something else — a conversation, a customer — can leave a row referencing the
  // tenant, and a foreign key will refuse the delete above. Rather than enumerate
  // every child table and get it wrong the moment the schema grows one, the result
  // is checked and reported, so an orphan is visible rather than silent.
  const remaining = psql(
    target,
    `SELECT count(*) FROM "public"."organizations" WHERE "slug" LIKE 'e2e-%';`,
  ).trim();
  if (remaining !== '0') {
    throw new Error(
      `Teardown left ${remaining} organization row(s) with an 'e2e-' slug. ` +
        'A table with a RESTRICT foreign key onto "users" and no "organizationId" column ' +
        'is holding the tenant open. Nothing was partly removed, because the delete is ' +
        'transactional, so widen the DO block in dropTenant rather than forcing it.',
    );
  }
}

