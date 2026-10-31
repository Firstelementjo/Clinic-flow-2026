rootProject.name = "firstelement"

include(":backend:app-bootstrap")
include(":backend:worker-runtime")
include(":backend:channels")
include(":backend:notifications-realtime")
include(":backend:shared-kernel")
include(":backend:database")
include(":backend:tenant-security")
include(":backend:identity-access")
include(":backend:crm-core")
include(":backend:inbox-helpdesk")
include(":backend:workflows-sla-routing")
include(":backend:administration")
include(":backend:operations-observability")
include(":backend:reporting-finance")
include(":backend:reporting-exports")
// Phase 10: the governed AI provider abstraction, tenant-filtered pgvector retrieval, the explicit
// tool registry, budget/approval/provenance enforcement and the async ingestion + backfill jobs.
include(":backend:ai-rag")