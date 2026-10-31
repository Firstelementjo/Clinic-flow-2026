/**
 * Seeds report definitions, their versions, a document template and its version, so the
 * export catalogue a journey reads is never empty.
 *
 * ## Why `psql` and not the authoring API
 *
 * This module previously authored over HTTP and failed on every write with
 * `403 CSRF_TOKEN_INVALID`, because it hand-rolled a cookie jar and a CSRF token for a
 * transport the fixture was never designed to use.
 *
 * That was the wrong instinct, and the reason is worth recording so it is not repeated:
 * **the rest of this fixture does not use HTTP at all.** `seedTenant` writes tenants with
 * `psql`, and inbound conversations come from the backend's own
 * `POST /api/v1/dev/mock/incoming-message`, which is a development endpoint that needs no
 * session. Both work because neither requires a cookie. The catalogue belongs with them.
 *
 * ## What is *not* lost by seeding rows rather than exercising the API
 *
 * Authoring over real HTTP — definition, version, publish, template, template version,
 * publish, catalogue, export, generate — is covered by the backend's
 * `ReportAuthoringIntegrationTest$FreshDatabase` on every build, over Testcontainers, with
 * every id taken from the response above it. So the API path is proved where it belongs.
 *
 * The split this leaves: **the backend suite proves authoring works; this fixture only needs
 * to supply data.** Duplicating the backend suite's job over a transport the fixture cannot
 * use bought nothing and cost a day.
 *
 * ## The rows respect the schema's own rules
 *
 * Not decoration. Each constraint below is violated by the obvious insert and caught here
 * by a check constraint that fires on the seed rather than on a journey:
 *
 * - `report_definitions_name_ar_check` / `_name_en_check` — blank names are refused.
 * - `report_definitions_timezone_check` — the zone must satisfy `firstelement_valid_timezone`.
 * - `report_definitions_published_version_check` — a `PUBLISHED` definition needs both a
 *   `publishedVersion` and a `publishedAt`.
 * - `report_definitions_published_pair_check` — `publishedAt` and `publishedByUserId` are null
 *   together or set together.
 * - `report_versions_published_state_check` — `state = 'DRAFT'` exactly when `publishedAt`
 *   is null, which is what makes a draft genuinely unpinnable.
 * - `report_versions_measures_present_check` — at least one measure, `jsonb_array_length >= 1`.
 * - `report_versions_one_published_per_definition` — a partial unique index. Seeding two
 *   published versions for one definition is a database error, not a warning.
 * - `document_templates_kind_check` — `kind` is `DOCX` or `PDF`.
 * - `document_template_versions_storage_key_check` — the key may not contain `?` or `://`.
 * - `document_template_versions_published_state_check` and the published/retired pair, as
 *   above.
 *
 * ## Why the read-back assertion is kept
 *
 * The assertion that used to live here — every entry carries a non-empty
 * `reportDefinitionId`, `nameAr` and `nameEn`, and at least one version is published — is
 * **retained**, because it is what turns an unreadable catalogue into a deterministic seed
 * failure instead of a mystery at journey time. A catalogue whose definitions render as
 * options with no id and no label is exactly that failure, and a journey cannot tell it from
 * an empty catalogue.
 *
 * It is checked against the **rows** here, and against the **catalogue response** in a
 * journey, because the two are different claims: rows can be present and unreadable by the
 * screen, and a journey is the only place that can see what the screen received.
 */
import { psql, type SeedTarget } from './seed';

export interface CatalogueExpectation {
  readonly reportDefinitions: number;
  readonly documentTemplates: number;
}

export const DEFAULT_EXPECTATION: CatalogueExpectation = {
  reportDefinitions: 2,
  documentTemplates: 1,
};

export interface CatalogueSeedAccount {
  readonly organizationId: string;
  readonly projectId: string;
  readonly userId: string;
  readonly email: string;
}

interface SeededDefinition {
  readonly key: string;
  readonly nameAr: string;
  readonly nameEn: string;
  readonly descriptionAr: string;
  readonly descriptionEn: string;
  /** Field keys, taken from the catalogue's own registry rather than invented. */
  readonly columns: string[];
  readonly measures: string[];
}

/**
 * A deterministic UUID for a seeded row.
 *
 * Deterministic so a re-run produces the same ids and a second seed is a no-op rather than
 * a duplicate; shaped as a v4 UUID because the columns are `uuid` and the server's mappers
 * read them as such. Not a real random value and never presented as one.
 */
function seededUuid(label: string): string {
  const hex = Array.from({ length: 32 }, (_, index) => {
    const code = `${label}:${index}`;
    let hash = 0x811c9dc5;
    for (let position = 0; position < code.length; position += 1) {
      hash ^= code.charCodeAt(position);
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    return hash.toString(16).padStart(8, '0').slice(-8);
  }).join('');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    `4${hex.slice(13, 16)}`,
    ((parseInt(hex.slice(16, 17), 16) & 0x3) | 0x8).toString(16) + hex.slice(17, 20),
    hex.slice(20, 32),
  ].join('-');
}

/** Single-quotes a value for a literal, so a name with an apostrophe cannot break the SQL. */
function lit(value: string): string {
  return `'${value.replace(/'/g, "''")}'`;
}

/**
 * A JSON array literal, quoted the same way and cast to `jsonb`.
 *
 * The cast belongs **here** rather than at each call site because the columns are `jsonb`,
 * not `text`: inserting a quoted JSON string into a `jsonb` column without the cast is a
 * type error, and spreading `::jsonb` across ten call sites is ten chances to forget one.
 */
function jsonArray(values: readonly string[]): string {
  return `${lit(JSON.stringify(values))}::jsonb`;
}

/** A JSON object literal, cast the same way. */
function jsonObject(value: Record<string, unknown>): string {
  return `${lit(JSON.stringify(value))}::jsonb`;
}

/**
 * Writes the seeded catalogue for one tenant, then asserts it is readable.
 *
 * Idempotent: every row is keyed by a deterministic id and the insert is
 * `ON CONFLICT DO NOTHING`, so a re-run is a no-op rather than a duplicate-key error.
 */
export function seedCatalogue(
  target: SeedTarget,
  account: CatalogueSeedAccount,
  expectation: CatalogueExpectation = DEFAULT_EXPECTATION,
): { reportDefinitions: number; documentTemplates: number } {
  const organizationId = account.organizationId;
  const projectId = account.projectId;
  const userId = account.userId;

  // The two definitions. Their names differ in **both** languages, so a journey can assert
  // which locale's name it is being shown — which is the assertion that catches a picker
  // rendering neither.
  //
  // `columns` and `measures` are the registry keys the server accepts. They are written here
  // rather than discovered, because discovery would need the HTTP transport this module no
  // longer uses; the values are the real registry keys, and a wrong one is caught by
  // `report_versions_measures_present_check` and by the catalogue's own column validation.
  const definitions: readonly SeededDefinition[] = [
    {
      key: 'seed-usage-summary',
      nameAr: 'ملخص الاستهلاك',
      nameEn: 'Usage summary',
      descriptionAr: 'ملخص الاستهلاك مُؤلَّف بواسطة بذور الاختبار',
      descriptionEn: 'Usage summary, authored by the e2e seed',
      columns: ['usage.dimension', 'usage.currency', 'usage.bucketStart'],
      measures: ['usage.eventCount', 'usage.cost'],
    },
    {
      key: 'seed-conversation-volume',
      nameAr: 'حجم المحادثات',
      nameEn: 'Conversation volume',
      descriptionAr: 'حجم المحادثات مُؤلَّف بواسطة بذور الاختبار',
      descriptionEn: 'Conversation volume, authored by the e2e seed',
      columns: ['conversation.status', 'conversation.channel', 'conversation.priority'],
      measures: ['conversation.messages', 'conversation.count'],
    },
  ];

  const now = 'CURRENT_TIMESTAMP AT TIME ZONE \'UTC\'';

  for (const definition of definitions) {
    const definitionId = seededUuid(`${account.email}:definition:${definition.key}`);
    const versionId = seededUuid(`${account.email}:version:${definition.key}`);

    // The definition is seeded `PUBLISHED` with `publishedVersion = 1`, which
    // `report_definitions_published_version_check` requires and which is what makes the
    // catalogue offer it at all.
    psql(
      target,
      `INSERT INTO report_definitions (
         id, "organizationId", "projectId", key, "nameAr", "nameEn",
         "descriptionAr", "descriptionEn", status, timezone, "localeDefault",
         "requiredPermissions", "redactionPolicy", "retentionClass",
         "currentVersion", "publishedVersion", "publishedAt", "publishedByUserId", version, "createdAt", "updatedAt"
       ) VALUES (
         ${lit(definitionId)}::uuid, ${lit(organizationId)}::uuid, ${lit(projectId)}::uuid,
         ${lit(definition.key)}, ${lit(definition.nameAr)}, ${lit(definition.nameEn)},
         ${lit(definition.descriptionAr)}, ${lit(definition.descriptionEn)},
         'PUBLISHED', 'Asia/Amman', 'ar',
         ARRAY['analytics:read-organization']::text[], 'no-contact-details', 'transient',
         1, 1, ${now}, ${lit(userId)}::uuid, 0, ${now}, ${now}
       ) ON CONFLICT (id) DO NOTHING;`,
    );

    // One version, `PUBLISHED`, with a non-empty `measures` — the constraint that an empty
    // one violates, and the reason a version with dimensions only is not a report.
    psql(
      target,
      `INSERT INTO report_versions (
         id, "organizationId", "projectId", "reportDefinitionId", "versionNumber",
         dimensions, measures, filters, "defaultSort", columns,
         "denominatorRules", "metricSemantics", state, "changeNote",
         "publishedAt", "publishedByUserId", "createdByUserId", "createdAt"
       ) VALUES (
         ${lit(versionId)}::uuid, ${lit(organizationId)}::uuid, ${lit(projectId)}::uuid,
         ${lit(definitionId)}::uuid, 1,
         ${jsonArray(definition.columns)}, ${jsonArray(definition.measures)},
         ${jsonObject({})}, ${jsonArray([])}, ${jsonArray(definition.columns)},
         ${jsonObject({})}, ${jsonObject({})},
         'PUBLISHED', 'Seeded for the export journeys.',
         ${now}, ${lit(userId)}::uuid, ${lit(userId)}::uuid, ${now}
       ) ON CONFLICT (id) DO NOTHING;`,
    );
  }

  // ---- document template --------------------------------------------------
  //
  // `kind` must be `DOCX` or `PDF`. The version's `storageKey` may not contain `?` or
  // `://`, and `placeholderSchema` is an object whose `placeholders` is a string array — the
  // server validates the *compressed* DOCX for placeholders, so a seeded row here is not a
  // claim that a real template would be accepted: the DOCX bytes are not seeded, because
  // this fixture proves the **catalogue is readable**, and the backend suite proves a real
  // document is accepted.
  const templateId = seededUuid(`${account.email}:template:seed-case-summary`);
  const templateVersionId = seededUuid(`${account.email}:templateVersion:seed-case-summary`);
  const templateKey = 'seed-case-summary';
  const templateNameAr = 'ملخص الحالة';
  const templateNameEn = 'Case summary';

  psql(
    target,
    `INSERT INTO document_templates (
       id, "organizationId", "projectId", key, "nameAr", "nameEn", kind, status,
       "currentVersion", "publishedVersion", "publishedAt", "publishedByUserId",
       locale, timezone, version, "createdAt", "updatedAt"
     ) VALUES (
       ${lit(templateId)}::uuid, ${lit(organizationId)}::uuid, ${lit(projectId)}::uuid,
       ${lit(templateKey)}, ${lit(templateNameAr)}, ${lit(templateNameEn)}, 'DOCX', 'PUBLISHED',
       1, 1, ${now}, ${lit(userId)}::uuid,
       'ar', 'Asia/Amman', 0, ${now}, ${now}
     ) ON CONFLICT (id) DO NOTHING;`,
  );

  psql(
    target,
    `INSERT INTO document_template_versions (
       id, "organizationId", "projectId", "documentTemplateId", "versionNumber", locale,
       "storageKey", "contentHash", "placeholderSchema", state, "changeNote",
       "publishedAt", "publishedByUserId", "createdByUserId", "createdAt"
     ) VALUES (
       ${lit(templateVersionId)}::uuid, ${lit(organizationId)}::uuid, ${lit(projectId)}::uuid,
       ${lit(templateId)}::uuid, 1, 'ar',
       ${lit(`${organizationId}/${templateKey}/v1.docx`)}, ${lit('seeded-content-hash')},
       ${jsonObject({ placeholders: ['customer.displayName', 'reportTitle', 'generatedAt'] })},
       'PUBLISHED', 'Seeded for the export journeys.',
       ${now}, ${lit(userId)}::uuid, ${lit(userId)}::uuid, ${now}
     ) ON CONFLICT (id) DO NOTHING;`,
  );

  return assertCatalogue(target, account, expectation);
}

/**
 * Reads the seeded rows back and **throws** unless the catalogue is usable.
 *
 * The checks are the precondition for any export journey being meaningful, and each failure
 * message names the symptom a journey would otherwise produce, so a seed failure reads as
 * the defect it prevents rather than as a missing table.
 */
function assertCatalogue(
  target: SeedTarget,
  account: CatalogueSeedAccount,
  expectation: CatalogueExpectation,
): { reportDefinitions: number; documentTemplates: number } {
  const rows = psql(
    target,
    `SELECT
       d.id::text, d.key, d."nameAr", d."nameEn", d."publishedVersion",
       (SELECT count(*) FROM report_versions v
         WHERE v."reportDefinitionId" = d.id AND v.state = 'PUBLISHED') AS published_versions,
       (SELECT count(*) FROM report_versions v
         WHERE v."reportDefinitionId" = d.id AND v.state = 'PUBLISHED'
           AND jsonb_array_length(v.measures) >= 1) AS publishable_versions
     FROM report_definitions d
     WHERE d."organizationId" = ${lit(account.organizationId)}::uuid
     ORDER BY d.key;`,
  )
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '');

  if (rows.length < expectation.reportDefinitions) {
    throw new Error(
      `the seed wrote ${rows.length} report definition(s) for ${account.email}, expected at ` +
        `least ${expectation.reportDefinitions}. An export journey would find an empty ` +
        `catalogue and could not tell that from a catalogue it cannot read.`,
    );
  }

  for (const row of rows) {
    const [id, key, nameAr, nameEn, publishedVersion, publishedVersions, publishable] =
      row.split('|');
    for (const [field, value] of [
      ['id', id],
      ['nameAr', nameAr],
      ['nameEn', nameEn],
    ] as const) {
      if (value === undefined || value === '') {
        throw new Error(
          `the seeded report definition ${key} has an empty ${field}. The export picker would ` +
            `render it as an option with no id and no label — an empty picker with entries in ` +
            `it, which is the defect this assertion exists to make visible at seed time.`,
        );
      }
    }
    if (publishedVersion === null || publishedVersion === '') {
      throw new Error(
        `the seeded report definition ${key} has no publishedVersion, so the catalogue would ` +
          `not offer it. Its status must be PUBLISHED with a publishedVersion and a ` +
          `publishedAt, which report_definitions_published_version_check enforces.`,
      );
    }
    if (Number(publishable) < 1) {
      throw new Error(
        `the seeded report definition ${key} has ${publishedVersions} published version(s) but ` +
          `${publishable} with a non-empty measures array, so an export could not pin any of ` +
          `them. A version with no measure is not a report, and the catalogue would say so.`,
      );
    }
  }

  const templateRow = psql(
    target,
    `SELECT count(*), count(*) FILTER (WHERE t.id IS NOT NULL AND t."nameAr" <> '' AND t."nameEn" <> ''),
            count(*) FILTER (WHERE v.state = 'PUBLISHED')
     FROM document_templates t
     LEFT JOIN document_template_versions v ON v."documentTemplateId" = t.id
     WHERE t."organizationId" = ${lit(account.organizationId)}::uuid;`,
  )
    .split('|')
    .map((value) => value.trim());

  if (Number(templateRow[0] ?? 0) < expectation.documentTemplates) {
    throw new Error(
      `the seed wrote ${templateRow[0]} document template(s) for ${account.email}, expected at ` +
        `least ${expectation.documentTemplates}.`,
    );
  }
  if (Number(templateRow[1] ?? 0) < 1) {
    throw new Error(
      'a seeded document template has an empty nameAr or nameEn, so the create form would ' +
        'offer it with no label.',
    );
  }
  if (Number(templateRow[2] ?? 0) < 1) {
    throw new Error(
      'no seeded document template version is published, so no export could use a template.',
    );
  }

  return {
    reportDefinitions: rows.length,
    documentTemplates: Number(templateRow[0] ?? 0),
  };
}
