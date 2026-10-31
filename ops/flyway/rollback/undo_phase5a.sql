-- =====================================================================
-- undo_phase5a.sql
-- ROLLBACK for the Phase-5a slice. DESTRUCTIVE BY DESIGN -- read the whole
-- header before running it.
--
-- UNDOES (in reverse creation order):
--     V20261004001000  idempotency_records + enum IdempotencyState
--     V20261004000700  job_dead_letters, job_attempts
--                      + enums DeadLetterStatus, JobAttemptOutcome
--     V20261004000100  outbox_events  + enum OutboxState
--
-- IT DOES NOT UNDO:
--     V20260814000000 (baseline) or V20260814000100 (vector indexes).
--
-- WHEN THIS IS THE RIGHT ACTION
--   * The Phase-5a migration FAILED. Do not use this script: every Phase-5a
--     migration is transactional, so PostgreSQL has already rolled it back
--     atomically and flyway_schema_history records the failure. Fix the
--     migration in ops/flyway/sql, change its VERSION, re-run. Undoing by hand
--     leaves Flyway's history disagreeing with the schema.
--   * The tables were created but NOTHING has been cut over to write through
--     them. Then dropping them loses nothing: no delivery, no attempt history.
--   * The schema itself is wanted but the cutover is abandoned before any row
--     exists. Same condition.
--
-- WHEN THIS IS THE WRONG ACTION
--   * Anything has been queued. Dropping outbox_events with rows in it deletes
--     undelivered customer messages with no record that they ever existed, and
--     dropping job_attempts deletes the history FR-6.7 requires. That is why
--     this script REFUSES to run against a non-empty slice unless it is
--     explicitly armed a second time (see SAFETY below).
--
-- RECOVERY IF YOU RUN IT ANYWAY
--   The data is not in the database any more; it is in the last backup.
--     ops\backup-restore\backup.ps1 -OutputDir <dir>            (before)
--     ops\backup-restore\restore-rehearsal.ps1 -Dump <dump> \
--         -EvidenceDir <dir> -Dispose                            (prove it)
--   An in-place restore into `firstelement` is NOT scripted on purpose: it is
--   a deliberate, approved act (docs/migration/flyway-runbook.md section 7.4).
--
-- SAFETY
--   * ARMED TWICE. It refuses to run without -v firstelement_undo_armed=on,
--     AND refuses again if any of the four tables holds a row unless
--     -v confirm_data_loss=yes is also passed. Both must be deliberate.
--   * It prints what it is about to drop, and the row count of each table,
--     BEFORE dropping anything.
--   * It never touches a baseline table. Every DROP is IF EXISTS and every
--     object it drops is one this phase created, so a partial state is handled
--     rather than crashing halfway.
--   * Flyway is NOT updated: after this script, delete the three migration
--     files from ops/flyway/sql and their packaged copies and run
--     `flyway repair` (or accept that the history shows three applied
--     migrations whose objects no longer exist -- repair is the correct step and
--     is an operator decision, not a scripted one).
--
-- Usage (throwaway database only until a backup exists):
--   docker exec -i firstelement-postgres psql -U firstelement -d <throwaway> \
--     -v ON_ERROR_STOP=1 -v firstelement_undo_armed=on -f - \
--     < ops/flyway/rollback/undo_phase5a.sql
--
-- Verified on 2026-10-04 on the throwaway database
-- `firstelement_phase5a_check`: unarmed -> skipped; armed with 5 outbox rows ->
-- REFUSED; armed plus -v confirm_data_loss=yes -> dropped, and the schema
-- fingerprint returned to bdf70c7c24dfe23c748281eb6c5c4a03, the exact
-- post-V20260814000100 value. See docs/migration/phases/phase-05a.md.
-- =====================================================================

\if :{?firstelement_undo_armed}
\else
  \echo 'UNDO PHASE5A SKIPPED: this script destroys objects.'
  \echo 'Re-run with -v firstelement_undo_armed=on after taking a backup.'
  \quit
\endif

-- Second gate. Default to "no" when the flag was not passed, so the only way
-- past it is to type the flag deliberately.
\if :{?confirm_data_loss}
\else
  \set confirm_data_loss no
\endif

\echo ''
\echo '== Phase-5a objects this script will drop =='
SELECT 'table ' || c.relname AS will_drop
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relname IN ('outbox_events', 'job_attempts',
                     'job_dead_letters', 'idempotency_records')
UNION ALL
SELECT 'type  ' || t.typname
  FROM pg_type t
  JOIN pg_namespace n ON n.oid = t.typnamespace
 WHERE n.nspname = 'public'
   AND t.typname IN ('OutboxState', 'JobAttemptOutcome',
                     'DeadLetterStatus', 'IdempotencyState')
 ORDER BY 1;

\echo ''
\echo '== Row counts (the data this destroys) =='
-- query_to_xml so a table that is already gone is skipped instead of making
-- this script fail halfway.
SELECT t AS table_name,
       (xpath('/row/c/text()',
              query_to_xml(format('SELECT count(*) AS c FROM public.%I', t),
                           false, true, '')))[1]::text::bigint AS row_count
  FROM unnest(ARRAY['outbox_events', 'job_attempts',
                    'job_dead_letters', 'idempotency_records']) AS t
 WHERE to_regclass('public.' || t) IS NOT NULL
 ORDER BY 1;

-- The gate. Rows present => a deliberate second acknowledgement is required.
SELECT (SELECT coalesce(sum((xpath('/row/c/text()',
                     query_to_xml(format('SELECT count(*) AS c FROM public.%I', t),
                                  false, true, '')))[1]::text::bigint), 0)
          FROM unnest(ARRAY['outbox_events', 'job_attempts',
                            'job_dead_letters', 'idempotency_records']) AS t
         WHERE to_regclass('public.' || t) IS NOT NULL) > 0 AS slice_has_rows
\gset

\if :slice_has_rows
  \echo ''
  \echo 'WARNING: the Phase-5a slice above is NOT empty. Dropping it destroys'
  \echo 'delivery state and attempt history.'
  SELECT lower(:'confirm_data_loss') = 'yes' AS data_loss_confirmed
  \gset
  \if :data_loss_confirmed
    \echo 'Data loss acknowledged (confirm_data_loss=yes). Proceeding on the'
    \echo 'throwaway/rehearsal database.'
  \else
    \echo 'REFUSING. Restore from a backup instead, or, if you have decided that'
    \echo 'this data may be destroyed, re-run with -v confirm_data_loss=yes.'
    \quit
  \endif
\endif

BEGIN;

-- Reverse creation order: dependents first.
DROP TABLE IF EXISTS public.idempotency_records;
DROP TABLE IF EXISTS public.job_dead_letters;
DROP TABLE IF EXISTS public.job_attempts;
DROP TABLE IF EXISTS public.outbox_events;

-- QUOTED, and that is not cosmetic: the enums were created as
-- "OutboxState", "JobAttemptOutcome", "DeadLetterStatus" and "IdempotencyState".
-- An unquoted DROP TYPE IF EXISTS public.OutboxState folds to
-- public.outboxstate, matches nothing, and prints a NOTICE that looks like a
-- harmless "skipping" while leaving the type behind. The post-condition below
-- checks the types as well, so this cannot pass silently again.
DROP TYPE IF EXISTS public."IdempotencyState";
DROP TYPE IF EXISTS public."DeadLetterStatus";
DROP TYPE IF EXISTS public."JobAttemptOutcome";
DROP TYPE IF EXISTS public."OutboxState";

COMMIT;

\echo ''
\echo '== After the undo: the Phase-5a slice must be gone and the baseline intact =='
SELECT CASE
         WHEN to_regclass('public.outbox_events') IS NULL
          AND to_regclass('public.job_attempts') IS NULL
          AND to_regclass('public.job_dead_letters') IS NULL
          AND to_regclass('public.idempotency_records') IS NULL
          AND NOT EXISTS (SELECT 1 FROM pg_type t
                           JOIN pg_namespace n ON n.oid = t.typnamespace
                          WHERE n.nspname = 'public'
                            AND t.typname IN ('OutboxState', 'JobAttemptOutcome',
                                              'DeadLetterStatus', 'IdempotencyState'))
         THEN 'PHASE5A_REMOVED'
         ELSE 'PHASE5A_STILL_PRESENT -- investigate'
       END AS undo_result;

SELECT count(*) AS baseline_tables_still_present
  FROM pg_tables
 WHERE schemaname = 'public'
   AND tablename IN ('organizations', 'projects', 'users', 'conversations',
                     'messages', 'document_chunks');