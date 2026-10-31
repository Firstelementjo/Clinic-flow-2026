-- REHEARSAL ONLY -- one-line state dump of the B1 control plane.
\pset pager off
select "backfillName", "status", "batchSize", "batchesRun", "rowsProcessed",
       "rowsFailed", "finishedAt" is not null as finished, "operator"
  from backfill_runs order by "backfillName";