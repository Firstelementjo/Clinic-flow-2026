/**
 * Generated-client contract compatibility.
 *
 * The typed client is generated from `tests/contracts/openapi.json`, which is
 * the document the running Spring application serves and which
 * `ops/quality/checks/check-openapi-document.ps1` compares against on every run.
 * Two failure modes matter and this suite fails on both:
 *
 * 1. **The client was not regenerated** after a contract change. The generated
 *    models are committed, so a backend change is invisible to `tsc` until
 *    someone regenerates. This suite reads the committed contract and the
 *    endpoint layer, and asserts that every path the app calls exists with the
 *    method and required parameters the app sends.
 * 2. **The client was regenerated from the wrong file.** `openapi-paths.json` is
 *    a route inventory with no schemas; generating from it produces a client with
 *    no types at all. The suite asserts the input has schemas.
 *
 * It also asserts the two contract facts the client depends on that a reader
 * could otherwise take on trust: that the session response carries the CSRF
 * header name, and that the realtime stream is a Server-Sent Events route.
 *
 * ## Known defects, asserted
 *
 * The last describe block asserts five **defects in the committed document** on
 * purpose. Each is real, each belongs in the backend rather than in a client
 * workaround, and each is a trap for whoever picks this up next: a generated
 * client that omits a required parameter, or that cannot type an error response,
 * looks like a client mistake until you read the document. Asserting them means a
 * backend fix turns a test red here — the signal to update this file and the phase
 * report — rather than leaving a comment that has quietly stopped being true.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

interface Operation {
  operationId?: string;
  parameters?: { name: string; in: string; required?: boolean; schema?: unknown }[];
  responses?: Record<string, unknown>;
}
type Document = {
  openapi: string;
  paths: Record<string, Record<string, Operation>>;
  components: { schemas: Record<string, unknown> };
};

/** Repository root: this file lives at `frontend/src/app/core/api/`. */
const repoRoot = join(__dirname, '..', '..', '..', '..', '..');
const contract = JSON.parse(
  readFileSync(join(repoRoot, 'tests', 'contracts', 'openapi.json'), 'utf8'),
) as Document;

function operationsFor(method: string, path: string): Operation | undefined {
  return contract.paths[path]?.[method.toLowerCase()];
}

/**
 * Every call the endpoint layer makes, as `METHOD path` with the query
 * parameters it can send.
 *
 * Kept as literal data on purpose: a test that derived the list from the
 * implementation would agree with whatever the implementation does, including a
 * path that never existed.
 */
const CALLS: readonly { method: string; path: string; query?: readonly string[] }[] = [
  { method: 'GET', path: '/api/v1/auth/csrf' },
  { method: 'POST', path: '/api/v1/auth/login' },
  { method: 'GET', path: '/api/v1/auth/me' },
  { method: 'POST', path: '/api/v1/auth/logout' },
  { method: 'GET', path: '/api/v1/permissions/me' },
  { method: 'GET', path: '/api/v1/permissions/{userId}' },
  { method: 'GET', path: '/api/v1/inbox/counts', query: ['projectId', 'channel', 'search'] },
  { method: 'GET', path: '/api/v1/inbox/unassigned', query: ['projectId', 'channel', 'cursor', 'limit'] },
  { method: 'GET', path: '/api/v1/conversations', query: ['projectId', 'filter', 'channel', 'search', 'cursor', 'limit'] },
  { method: 'GET', path: '/api/v1/conversations/{conversationId}' },
  { method: 'GET', path: '/api/v1/customers', query: ['projectId', 'search', 'cursor', 'limit'] },
  { method: 'GET', path: '/api/v1/realtime/state', query: ['unreadOnly'] },
  { method: 'GET', path: '/api/v1/realtime/stream', query: ['topic'] },
  { method: 'GET', path: '/api/v1/notifications', query: ['unreadOnly'] },
  { method: 'POST', path: '/api/v1/notifications/{notificationId}/read' },
  { method: 'POST', path: '/api/v1/notifications/read-all' },
  { method: 'GET', path: '/api/v1/operations/realtime' },
  { method: 'GET', path: '/api/v1/operations/jobs', query: ['projectId', 'queue', 'windowHours'] },
  { method: 'GET', path: '/api/v1/operations/jobs/worklist', query: ['projectId', 'queue', 'limit'] },
  { method: 'GET', path: '/api/v1/operations/jobs/stranded-leases', query: ['projectId', 'limit'] },
  { method: 'GET', path: '/api/v1/operations/connectors', query: ['windowHours'] },
  { method: 'GET', path: '/api/v1/operations/webhooks', query: ['windowHours'] },
  { method: 'GET', path: '/api/v1/operations/search/freshness', query: ['projectId', 'idleHours', 'limit'] },
  { method: 'GET', path: '/api/v1/operations/search/gaps', query: ['projectId', 'onlyOpen', 'limit'] },
  { method: 'GET', path: '/api/v1/admin/overview', query: ['windowHours'] },
  { method: 'GET', path: '/api/v1/admin/audit/events', query: ['projectId', 'hours'] },
  { method: 'GET', path: '/api/v1/admin/audit/security', query: ['hours'] },
  { method: 'GET', path: '/api/v1/admin/identity' },
  { method: 'GET', path: '/api/v1/admin/identity/sessions', query: ['userId'] },
  { method: 'GET', path: '/api/v1/status' },
];

describe('the committed OpenAPI contract', () => {
  it('is OpenAPI 3.1 with schemas, not the schema-less route inventory', () => {
    expect(contract.openapi).toMatch(/^3\.1/);
    expect(Object.keys(contract.components.schemas).length).toBeGreaterThan(100);
  });

  it.each(CALLS)('$method $path exists', ({ method, path }) => {
    expect(operationsFor(method, path), `${method} ${path} is missing from the contract`).toBeDefined();
  });

  it.each(
    CALLS.filter((call) => call.query && call.query.length > 0).map((call) => [
      `${call.method} ${call.path}`,
      call,
    ]),
  )('%s accepts every parameter the client sends', (_label, call) => {
    const operation = operationsFor(call.method, call.path);
    const declared = new Set(
      (operation?.parameters ?? []).filter((parameter) => parameter.in === 'query').map((p) => p.name),
    );
    for (const parameter of call.query ?? []) {
      expect(declared, `${call.method} ${call.path} has no '${parameter}' query parameter`).toContain(
        parameter,
      );
    }
  });
});

describe('the contract facts the client depends on', () => {
  it('publishes the CSRF header name on the session response, so the client is not guessing', () => {
    const schema = contract.components.schemas['SessionResponse'] as {
      properties: Record<string, unknown>;
    };
    expect(Object.keys(schema.properties)).toContain('csrfHeaderName');
  });

  it('publishes the CSRF token shape the token endpoint returns', () => {
    const schema = contract.components.schemas['CsrfToken'] as {
      properties: Record<string, unknown>;
    };
    expect(Object.keys(schema.properties)).toContain('token');
    expect(Object.keys(schema.properties)).toContain('headerName');
  });

  it('declares the realtime stream as Server-Sent Events', () => {
    const operation = operationsFor('GET', '/api/v1/realtime/stream');
    const text = JSON.stringify(operation?.responses ?? {});
    expect(text).toContain('text/event-stream');
  });

  it('has a resync target for the reconnect path', () => {
    expect(operationsFor('GET', '/api/v1/realtime/state')).toBeDefined();
  });

  it('declares the effective-permission read the permissions UX is built on', () => {
    const schema = contract.components.schemas['PermissionInspection'] as {
      properties: Record<string, unknown>;
    };
    expect(Object.keys(schema.properties)).toContain('effectivePermissions');
    expect(Object.keys(schema.properties)).toContain('missingPermissions');
  });

  it('declares the server-resolved project scope on the session, so the client never assembles it', () => {
    const schema = contract.components.schemas['SessionResponse'] as {
      properties: Record<string, unknown>;
    };
    expect(Object.keys(schema.properties)).toContain('projectIds');
  });

  it('still declares no top-level /analytics or /dashboards family', () => {
    // Narrowed twice, and both narrowings were corrections rather than relaxations.
    //
    // First: it used to match `/report/i` as well, and when phase 31 added
    // `/api/v1/exports/report-definitions` the assertion went red -- for a capability that is not
    // an analytics read model at all. Widening or deleting the assertion would have been the wrong
    // repair, so the pattern was corrected to match what it claims to be about.
    //
    // Now: Phase 37 added `/api/v1/clinic/analytics`, which IS an analytics read model and does
    // drill to source rows. It must not be hidden by this test. The gap this records is the
    // TOP-LEVEL families PROJECT_REQUIREMENTS section 6 names, which still do not exist.
    const requiredButAbsent = ['/api/v1/analytics', '/api/v1/dashboards'];
    const present = Object.keys(contract.paths);
    expect(
      requiredButAbsent.filter((path) => present.includes(path)),
      'the top-level analytics and dashboard families now exist; update PROJECT_REQUIREMENTS and '
        + 'the phase-11 record rather than leaving it claiming they do not',
    ).toEqual([]);

    // And the clinic read model that does exist, asserted so its absence is equally visible.
    expect(
      present.filter((path) => /clinic\/analytics/i.test(path)),
      'the clinic analytics read model has gone missing again',
    ).not.toEqual([]);
  });

  it('declares the authoring routes, so the client can reach report authoring at all', () => {
    // The counterpart to the assertion above, and added in the same commit: phase 31 added
    // thirteen authoring routes under `/api/v1/exports`, and without them the export
    // catalogue is empty on every fresh database, so no definition or template can exist to
    // export. The suite records the new state rather than merely tolerating it, so a future
    // removal has to be argued for here.
    const paths = Object.keys(contract.paths);
    for (const path of [
      '/api/v1/exports/report-definitions',
      '/api/v1/exports/report-definitions/{definitionId}',
      '/api/v1/exports/report-definitions/{definitionId}/versions',
      '/api/v1/exports/report-definitions/{definitionId}/versions/{versionNumber}/publish',
      '/api/v1/exports/report-definitions/{definitionId}/versions/{versionNumber}/retire',
      '/api/v1/exports/document-templates',
      '/api/v1/exports/document-templates/{templateId}/versions',
      '/api/v1/exports/document-templates/{templateId}/versions/{versionNumber}/publish',
      '/api/v1/exports/document-templates/{templateId}/versions/{versionNumber}/retire',
      '/api/v1/exports/authoring',
    ]) {
      expect(paths, `${path} is declared`).toContain(path);
    }
  });

  it('takes projectId as a query parameter on both authoring POSTs, not in the body', () => {
    // Checked because the client got this wrong first: `projectId` was put in the request
    // body, which the generated types reject, because the contract declares it `in: query`.
    for (const path of [
      '/api/v1/exports/report-definitions',
      '/api/v1/exports/document-templates',
    ]) {
      const operation = (contract.paths[path] as Record<string, { parameters?: { name: string; in: string }[] }>)[
        'post'
      ];
      const parameters = operation.parameters ?? [];
      const projectId = parameters.find((parameter) => parameter.name === 'projectId');
      expect(projectId, `${path} declares projectId`).toBeDefined();
      expect(projectId?.in, `${path} takes projectId in the query`).toBe('query');
    }
  });

  it('carries a template version as a base64 string in JSON, not a multipart upload', () => {
    // "Raw DOCX upload" in the phase note reads like a file upload. The contract says the
    // body is `application/json` with `{type: string, format: byte}` — base64 inside an
    // envelope. A client that posted multipart would be refused, and the mismatch is
    // invisible until someone tries it.
    const operation = (
      contract.paths['/api/v1/exports/document-templates/{templateId}/versions'] as Record<
        string,
        { requestBody?: { content: Record<string, { schema: { type: string; format?: string } }> } }
      >
    )['post'];
    const schema = operation.requestBody?.content['application/json']?.schema;
    expect(schema?.type).toBe('string');
    expect(schema?.format).toBe('byte');
  });

  it('keeps the connector-webhook surface out of the versioned API', () => {
    const webhooks = Object.keys(contract.paths).filter((path) => path.startsWith('/webhooks/'));
    expect(webhooks.length).toBeGreaterThan(0);
    for (const path of webhooks) {
      expect(path.startsWith('/api/v1/')).toBe(false);
    }
  });

  /**
   * Findings against the committed document, asserted so they cannot be forgotten.
   *
   * Each of these is a defect in `tests/contracts/openapi.json` — the fix belongs
   * in the backend and the document gets re-promoted with
   * `export-openapi-document.ps1`. They are asserted rather than merely reported
   * because a test that documents a defect but does not fail when the defect
   * disappears stops being a record of it.
   *
   * ## FINDING 1 — `GET /api/v1/auth/csrf` declares a required `token` query parameter
   *
   * The operation takes the `CsrfToken` as a resolved controller argument, and
   * springdoc renders it as a **required query parameter typed as an object**. A
   * conformant client therefore cannot call this route: it would have to send the
   * token it is asking for, as `?token={...}`, and there is no representation of
   * `CsrfToken` that is valid on a query string. `CsrfBootstrapper` therefore sends
   * nothing and relies on the response alone.
   *
   * This also has a second-order effect worth stating: because the document claims
   * the parameter is required, a generator will produce a client whose signature
   * demands an argument the UI has no value for. The right shape is
   * `@Operation(hidden = true)` on the argument, or a `@Parameter(hidden = true)`.
   */
  it('FINDING 1: still declares the unusable required token parameter on /auth/csrf', () => {
    const operation = operationsFor('GET', '/api/v1/auth/csrf');
    const token = (operation?.parameters ?? []).find((parameter) => parameter.name === 'token');
    expect(token, 'the defect was fixed; update the phase-11 report and the client').toBeDefined();
    expect(token?.required).toBe(true);
    expect(token?.in).toBe('query');
  });

  /**
   * ## FINDING 2 — RESOLVED in Phase 36. No operation documented a 4xx response.
   *
   * It was true when this was written: all 142 operations declared `200` only, while
   * `ErrorHandlingAdvice` really did answer `400 VALIDATION_FAILED`, `401
   * AUTHENTICATION_REQUIRED`, `403 ACCESS_DENIED`, `403 CSRF_TOKEN_INVALID`, `404 NOT_FOUND` and
   * `429 LOGIN_RATE_LIMITED`. The whole error interceptor is written against codes the document did
   * not declare.
   *
   * The refusals are now DERIVED per operation by the served application and stamped onto the
   * document at export, from `RefusalRules` -- not pasted on. So this is now a regression guard: it
   * fails if the derivation is dropped, and the floor below is what Phase 36 exported, so a thinner
   * re-export fails too.
   *
   * The one operation that declares no refusal is named explicitly rather than allowed as slack.
   * `GET /api/v1/status` is in `PUBLIC_PATHS`, is not an authentication operation, and answers only
   * `200`, so there is no credential to refuse and nothing a caller can fix.
   */
  it('FINDING 2 (resolved): every operation declares the refusals it can actually produce', () => {
    const declaredRefusalsFor = (path: string, method: string): string[] => {
      const operation = operationsFor(method, path);
      return Object.keys(operation?.responses ?? {});
    };
    const allRefusals = Object.values(contract.paths).flatMap((methods) =>
      Object.values(methods).flatMap((operation) =>
        Object.keys(operation.responses ?? {}).filter((code) => /^4\d\d$/.test(code)),
      ),
    );
    // Floor from the Phase 36 export, not today's count: a re-export that loses refusals fails here.
    expect(
      allRefusals.length,
      'the document has lost the derived refusals; check RefusalRules and PUBLIC_PATHS',
    ).toBeGreaterThanOrEqual(474);

    // The codes the error interceptor actually branches on must be declared somewhere.
    for (const code of ['400', '401', '403', '404', '409', '422', '429']) {
      expect(
        allRefusals.some((declared) => declared === code),
        `no operation declares ${code}`,
      ).toBe(true);
    }

    expect(declaredRefusalsFor('/api/v1/status', 'get')).toEqual(['200']);
    expect(
      declaredRefusalsFor('/api/v1/auth/login', 'post'),
      'a rejected sign-in is 401 INVALID_CREDENTIALS, so login declares 401 even though it is a '
        + 'PUBLIC_PATHS route -- "no session required" is not the same claim as "cannot answer 401"',
    ).toContain('401');
  });

  /**
   * ## FINDING 3 — RESOLVED. The login rate limiter sent no `Retry-After`.
   *
   * `LoginRateLimiter.Decision` computes `retryAfterSeconds` and publishes it into the security
   * event, but the 429 response carried neither a `Retry-After` header nor the value in `details`, so
   * a client could not tell the user how long to wait and had to assume the whole 60s window -- which
   * is what this e2e harness does.
   *
   * The runtime sends it: `SecurityExceptionAdvice.rateLimited` answers
   * `.header("Retry-After", ...)` and puts `retryAfterSeconds` in `details`. Phase 36 declared it,
   * so the client can now read the wait instead of guessing it.
   */
  it('FINDING 3 (resolved): the rate-limit response declares the wait a caller needs', () => {
    const login = operationsFor('POST', '/api/v1/auth/login');
    // The document is read as a loose JSON shape, so the response object needs a local shape.
    // Narrowed HERE rather than widened globally: the point of these assertions is to read a
    // specific declared header, and an `any` here would also silence every typo in the path.
    const rateLimited = login?.responses?.['429'] as
      | {
          headers?: Record<string, { schema?: { type?: string } }>;
          content?: Record<string, { schema?: unknown }>;
        }
      | undefined;

    expect(rateLimited, 'the login route must declare its 429').toBeDefined();
    expect(
      rateLimited?.headers?.['Retry-After'],
      'the header the server actually sends is not declared, so a generated client still has to guess',
    ).toBeDefined();
    expect(rateLimited?.headers?.['Retry-After']?.schema?.type).toBe('integer');

    // And the body carries the same number, which is what the interceptor surfaces to the user.
    const content = rateLimited?.content?.['application/json'];
    expect(JSON.stringify(content?.schema ?? {})).toContain('ErrorResponse');
  });

  /**
   * ## FINDING 4 — RESOLVED in Phase 39. `limit` was typed `string` on the paginated routes.
   *
   * Four routes declared `{"type":"string"}` while six others declared
   * `{"type":"integer","format":"int32"}`. The cause was a signature, not a schema: those four took
   * `@RequestParam(required = false) String limit` and handed-parse it through `RequestParams.limit`,
   * and springdoc describes what it is given.
   *
   * The fix is a composed `@BoundedLimit` annotation rather than four edited signatures, so the
   * next paginated route cannot repeat it. The published type is now `integer` while the server keeps
   * its clamp: tolerance for a malformed value is not part of a client's contract, and publishing it
   * as `string` told every generated client it might legitimately send a word.
   *
   * Now a regression guard over EVERY `limit` in the document rather than one route, because the
   * defect was always about inconsistency and a single-route assertion could not see it.
   */
  it('FINDING 4 (resolved): every limit parameter is published as an integer', () => {
    const declarations: { operation: string; schema: unknown }[] = [];
    for (const [path, methods] of Object.entries(contract.paths)) {
      for (const [method, operation] of Object.entries(methods)) {
        for (const parameter of (operation as { parameters?: { name?: string; schema?: unknown }[] })
          .parameters ?? []) {
          if (parameter.name === 'limit') {
            declarations.push({ operation: `${method.toUpperCase()} ${path}`, schema: parameter.schema });
          }
        }
      }
    }

    // A floor, not today's count: a document that loses a limit declaration fails here.
    expect(declarations.length, 'the document has lost its limit parameters').toBeGreaterThanOrEqual(10);
    for (const { operation, schema } of declarations) {
      expect(schema, `${operation} publishes limit as something other than an integer`).toEqual(
        expect.objectContaining({ type: 'integer' }),
      );
    }
  });

  /**
   * ## FINDING 5 — RESOLVED in Phase 36. Responses used the wildcard media type.
   *
   * 139 responses declared the wildcard media type and only 33 declared
   * `application/json`. A generated client could not tell a JSON body from an
   * event stream from a download by media type alone -- which is precisely how
   * `text/event-stream` has to be recognised for the realtime stream.
   *
   * (The wildcard is spelled without its literal characters anywhere in this
   * comment on purpose: `*` immediately followed by `/` ends a block comment,
   * and writing it inline silently truncates everything after it.)
   *
   * Still asserted as a *relationship*, so it survives a document that fixes some of them: JSON is
   * now the MAJORITY media type, and the derived refusals carry `application/json` explicitly.
   */
  it('FINDING 5 (resolved): JSON is the majority media type, not the minority', () => {
    const raw = readFileSync(join(repoRoot, 'tests', 'contracts', 'openapi.json'), 'utf8');
    const anyMedia = raw.match(/"\*\/\*"/g)?.length ?? 0;
    const json = raw.match(/"application\/json"/g)?.length ?? 0;

    expect(json, 'the document has gone back to naming no media type').toBeGreaterThan(anyMedia);
    // The realtime stream is the reason this mattered, and it is named rather than left to a guess.
    const stream = operationsFor('GET', '/api/v1/realtime/stream');
    expect(JSON.stringify(stream?.responses ?? {}), 'the SSE stream must name text/event-stream').toContain(
      'text/event-stream',
    );
  });
});

describe('generated output', () => {
  const generated = readFileSync(
    join(__dirname, 'generated', 'types.gen.ts'),
    'utf8',
  ) as string;

  it('contains a type for every schema the screens read', () => {
    for (const schema of [
      'SessionResponse',
      'PermissionInspection',
      'LoginRequest',
      'CsrfToken',
      'UserView',
      'ProjectSummary',
      'UnassignedQueuePage',
      'ConversationSummary',
      'CursorPageConversationSummary',
      'CursorPageCustomerListItem',
      'Totals',
      'Entry',
      'DirectoryView',
      'SessionRow',
    ]) {
      expect(generated, `generated client has no ${schema}`).toContain(`export type ${schema}`);
    }
  });

  it('is generated from a document with operations, not just schemas', () => {
    expect(generated).toContain('export type LoginData');
    expect(generated).toContain("url: '/api/v1/auth/login'");
  });

  it('models every property as optional, because the server omits nulls', () => {
    // `spring.jackson.default-property-inclusion: non_null` means an absent
    // property is the normal case, so a generated `required` field would be a
    // type that lies about the wire.
    expect(generated).not.toMatch(/^\s+required:\s*\[/m);
  });
});
