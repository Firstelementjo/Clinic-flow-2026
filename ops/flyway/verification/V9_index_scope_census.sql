-- =====================================================================
-- Index scope census for the verified schema.
--
-- Classifies every index in the public schema into EXACTLY one bucket, in
-- priority order, so the three counts add up to the total index count.
-- Read-only.
--
--   tenant-led     : ("organizationId", ...)  -- the canonical tenant filter
--   project-led    : ("projectId", ...)      -- project scope IS tenant scope
--                                           on a project-scoped table
--   parent-key     : (runId|conversationId|employeeId|userId|
--                    knowledgeBaseId|agentVersionId|customerId, ...)
--                   -- only reachable through an already-scoped parent
--   global         : no tenant column anywhere in the key
--   unique-other   : a UNIQUE constraint that is none of the above
--
-- flyway_schema_history is excluded: it is Flyway's own bookkeeping table and
-- is not part of the application contract.
-- =====================================================================
select case
         when indexdef like '%USING btree ("organizationId"%' then '1 tenant-led'
         when indexdef like '%USING btree ("projectId"%'      then '2 project-led'
         when indexdef like '%USING hnsw%'                     then '3 vector-led'
         when indexdef like '%USING btree ("runId"%'
           or indexdef like '%USING btree ("conversationId"%'
           or indexdef like '%USING btree ("employeeId"%'
           or indexdef like '%USING btree ("userId"%'
           or indexdef like '%USING btree ("knowledgeBaseId"%'
           or indexdef like '%USING btree ("agentVersionId"%'
           or indexdef like '%USING btree ("customerId"%'
                                                              then '4 parent-key'
         when indexdef like '%UNIQUE%'                        then '6 unique-other'
         else '5 global'
       end as scope_class,
       count(*)
  from pg_indexes
 where schemaname = 'public'
   and tablename <> 'flyway_schema_history'
 group by 1
 order by 1;

-- The global bucket must contain nothing that could leak across tenants.
-- sessions_expiresAt_idx is intentional: a session-expiry sweep must not
-- require a tenant filter.
select tablename, indexname, indexdef
  from pg_indexes
 where schemaname = 'public'
   and tablename <> 'flyway_schema_history'
   and indexdef not like '%USING btree ("organizationId"%'
   and indexdef not like '%USING btree ("projectId"%'
   and indexdef not like '%USING hnsw%'
   and indexdef not like '%USING btree ("runId"%'
   and indexdef not like '%USING btree ("conversationId"%'
   and indexdef not like '%USING btree ("employeeId"%'
   and indexdef not like '%USING btree ("userId"%'
   and indexdef not like '%USING btree ("knowledgeBaseId"%'
   and indexdef not like '%USING btree ("agentVersionId"%'
   and indexdef not like '%USING btree ("customerId"%'
   and indexdef not like '%UNIQUE%'
 order by tablename, indexname;