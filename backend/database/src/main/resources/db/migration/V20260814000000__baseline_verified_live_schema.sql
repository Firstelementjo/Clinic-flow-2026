-- =====================================================================
-- FirstElement Flyway baseline
-- =====================================================================
-- Version : 20260814000000
-- Source  : schema-only pg_dump of the VERIFIED live database 'firstelement'
--           on container 'firstelement-postgres' (image pgvector/pgvector:pg16,
--           PostgreSQL server 16.15 (Debian 16.15-1.pgdg12+2)).
-- Taken   : 2026-10-04
--
-- WHY THIS IS NOT packages/database/prisma/migrations/20260814000000_init
--   The Prisma init migration is drift-divergent and is QUARANTINED as a
--   baseline source. Recorded drift:
--     * EmployeeAvailabilityStatus : init = AVAILABLE/BUSY/AWAY/OFFLINE
--                                   live  = ONLINE/OFFLINE/BUSY/BREAK/MEAL/TRAINING/MEETING
--     * AIRunDecision              : init omits RESOLVE, live has it
--     * init defines no CaseOutcome enum and only 31 of the 45 tables
--       (no Lead, accounts, appointments, deals, doctors, procedures,
--        notes, tasks, views, case_*, conversation_classifications,
--        agent_status_sessions)
--   The live database and packages/database/prisma/schema.prisma agree;
--   schema.prisma is the declared source of truth for enum values and
--   column contracts.
--
-- CONTRACT NOTES (do not 'fix' silently)
--   * Quoted camelCase identifiers are preserved verbatim. Spring
--     persistence must map explicitly; no naming strategy.
--   * All timestamps are 'timestamp(3) without time zone' (Prisma
--     DateTime). Bind LocalDateTime, never Instant/OffsetDateTime.
--   * IDs are uuid; ticketNumber / cxId / tokenHash are business keys.
--     A migration that regenerates them breaks external references.
--   * pgvector is available; the HNSW embedding index is added by the
--     separate additive migration V20260814000100.
--
-- Regenerate with:
--   ops/flyway/generate/generate-baseline.ps1
-- Verify with:
--   ops/flyway/verification/run-verification.ps1
--   ./gradlew :backend:database:test
--
-- Source dump body sha256: fd3c0b441a598b2e6078f88490858034c161e8786446eb70616dbeccbfa2b04b
-- =====================================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15 (Debian 16.15-1.pgdg12+2)
-- Dumped by pg_dump version 16.15 (Debian 16.15-1.pgdg12+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: SCHEMA "public"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA "public" IS 'standard public schema';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA "public";


--
-- Name: EXTENSION "pgcrypto"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "pgcrypto" IS 'cryptographic functions';


--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "vector" WITH SCHEMA "public";


--
-- Name: EXTENSION "vector"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "vector" IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: AIProviderKind; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."AIProviderKind" AS ENUM (
    'MOCK',
    'OPENAI',
    'ANTHROPIC',
    'GEMINI',
    'OPENAI_COMPATIBLE',
    'LOCAL'
);


--
-- Name: AIRunDecision; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."AIRunDecision" AS ENUM (
    'REPLY',
    'ASK_CLARIFICATION',
    'ESCALATE',
    'NO_ACTION',
    'RESOLVE'
);


--
-- Name: AIRunStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."AIRunStatus" AS ENUM (
    'RUNNING',
    'COMPLETED',
    'FAILED',
    'ABORTED'
);


--
-- Name: AIRunStepKind; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."AIRunStepKind" AS ENUM (
    'CONTEXT',
    'RETRIEVAL',
    'TOOL_EXECUTION',
    'GENERATION',
    'EVALUATION',
    'DECISION'
);


--
-- Name: CaseOutcome; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."CaseOutcome" AS ENUM (
    'RESOLVED',
    'ESCALATED',
    'UNRESOLVED'
);


--
-- Name: ChannelConnectionStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."ChannelConnectionStatus" AS ENUM (
    'DISCONNECTED',
    'CONNECTED',
    'ERROR'
);


--
-- Name: ChannelType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."ChannelType" AS ENUM (
    'MOCK',
    'WHATSAPP',
    'INSTAGRAM',
    'MESSENGER',
    'TELEGRAM'
);


--
-- Name: ConversationEventType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."ConversationEventType" AS ENUM (
    'CONVERSATION_CREATED',
    'MESSAGE_SENT',
    'MESSAGE_RECEIVED',
    'ASSIGNED',
    'UNASSIGNED',
    'TAKEN_OVER',
    'RETURNED_TO_AI',
    'ESCALATED',
    'SCORE_UPDATED',
    'STATUS_CHANGED',
    'PRIORITY_CHANGED',
    'TAG_ADDED',
    'NOTE_ADDED'
);


--
-- Name: ConversationMode; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."ConversationMode" AS ENUM (
    'AI',
    'HUMAN'
);


--
-- Name: ConversationPriority; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."ConversationPriority" AS ENUM (
    'LOW',
    'NORMAL',
    'HIGH',
    'URGENT'
);


--
-- Name: ConversationStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."ConversationStatus" AS ENUM (
    'OPEN',
    'PENDING',
    'RESOLVED',
    'CLOSED'
);


--
-- Name: DocumentSourceType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."DocumentSourceType" AS ENUM (
    'FILE',
    'URL',
    'TEXT'
);


--
-- Name: DocumentStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."DocumentStatus" AS ENUM (
    'UPLOADED',
    'PROCESSING',
    'INDEXING',
    'READY',
    'FAILED'
);


--
-- Name: EmployeeAvailabilityStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."EmployeeAvailabilityStatus" AS ENUM (
    'ONLINE',
    'OFFLINE',
    'BUSY',
    'BREAK',
    'MEAL',
    'TRAINING',
    'MEETING'
);


--
-- Name: EscalationStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."EscalationStatus" AS ENUM (
    'PENDING',
    'ASSIGNED',
    'RESOLVED',
    'CANCELLED'
);


--
-- Name: EscalationTrigger; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."EscalationTrigger" AS ENUM (
    'RULE',
    'EXPLICIT_REQUEST',
    'AI_FAILURE',
    'LOW_CONFIDENCE',
    'MANUAL'
);


--
-- Name: Locale; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."Locale" AS ENUM (
    'ar',
    'en'
);


--
-- Name: MessageAuthorType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."MessageAuthorType" AS ENUM (
    'CUSTOMER',
    'EMPLOYEE',
    'AI',
    'SYSTEM'
);


--
-- Name: MessageDeliveryStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."MessageDeliveryStatus" AS ENUM (
    'PENDING',
    'SENT',
    'DELIVERED',
    'READ',
    'FAILED'
);


--
-- Name: MessageDirection; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."MessageDirection" AS ENUM (
    'INBOUND',
    'OUTBOUND'
);


--
-- Name: NotificationType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."NotificationType" AS ENUM (
    'CONVERSATION_ASSIGNED',
    'CONVERSATION_ESCALATED',
    'CONVERSATION_UNASSIGNED_QUEUE',
    'MENTION',
    'SYSTEM'
);


--
-- Name: RoutingStrategy; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."RoutingStrategy" AS ENUM (
    'LEAST_LOADED',
    'ROUND_ROBIN'
);


--
-- Name: UserRole; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE "public"."UserRole" AS ENUM (
    'ADMIN',
    'EMPLOYEE'
);


SET default_tablespace = '';

SET default_table_access_method = "heap";

--
-- Name: Lead; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."Lead" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "company" "text",
    "source" "text",
    "status" "text" DEFAULT 'New'::"text" NOT NULL,
    "tags" "text"[] DEFAULT ARRAY[]::"text"[],
    "notes" "text",
    "ownerId" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL,
    "accountId" "uuid",
    "email" "text",
    "estimatedValue" double precision,
    "industry" "text",
    "jobTitle" "text",
    "leadScore" integer,
    "phone" "text",
    "priority" "text",
    "createdById" "uuid",
    "customerId" "uuid",
    "followUpDate" timestamp(3) without time zone,
    "ticketDate" timestamp(3) without time zone,
    "ticketNumber" "text"
);


--
-- Name: accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."accounts" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "industry" "text",
    "website" "text",
    "size" "text",
    "address" "text",
    "city" "text",
    "country" "text",
    "description" "text",
    "tags" "text"[] DEFAULT ARRAY[]::"text"[],
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL,
    "companyType" "text",
    "email" "text",
    "leadSource" "text",
    "linkedin" "text",
    "notes" "text",
    "ownerId" "uuid",
    "phone" "text",
    "postalCode" "text",
    "priority" "text",
    "status" "text",
    "createdById" "uuid"
);


--
-- Name: agent_status_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."agent_status_sessions" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "employeeId" "uuid" NOT NULL,
    "status" "public"."EmployeeAvailabilityStatus" NOT NULL,
    "startedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "endedAt" timestamp(3) without time zone,
    "durationSeconds" integer,
    "changedByUserId" "uuid",
    "note" "text"
);


--
-- Name: ai_agent_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ai_agent_versions" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "agentId" "uuid" NOT NULL,
    "version" integer NOT NULL,
    "systemInstructions" "text" NOT NULL,
    "tone" "text",
    "provider" "public"."AIProviderKind" DEFAULT 'MOCK'::"public"."AIProviderKind" NOT NULL,
    "model" "text",
    "temperature" double precision DEFAULT 0.3 NOT NULL,
    "maxTokens" integer DEFAULT 800 NOT NULL,
    "failoverChain" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "enabledTools" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "knowledgeBaseIds" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "minRetrievalScore" double precision DEFAULT 0.25 NOT NULL,
    "memoryWindow" integer DEFAULT 12 NOT NULL,
    "createdByUserId" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ai_agents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ai_agents" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "isEnabled" boolean DEFAULT false NOT NULL,
    "activeVersionId" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: ai_run_retrievals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ai_run_retrievals" (
    "id" "uuid" NOT NULL,
    "runId" "uuid" NOT NULL,
    "knowledgeBaseId" "uuid" NOT NULL,
    "documentId" "uuid" NOT NULL,
    "chunkId" "uuid" NOT NULL,
    "documentTitle" "text" NOT NULL,
    "score" double precision NOT NULL,
    "wasUsed" boolean DEFAULT true NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ai_run_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ai_run_steps" (
    "id" "uuid" NOT NULL,
    "runId" "uuid" NOT NULL,
    "sequence" integer NOT NULL,
    "kind" "public"."AIRunStepKind" NOT NULL,
    "status" "text" DEFAULT 'OK'::"text" NOT NULL,
    "latencyMs" integer,
    "detail" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ai_run_tool_calls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ai_run_tool_calls" (
    "id" "uuid" NOT NULL,
    "runId" "uuid" NOT NULL,
    "sequence" integer NOT NULL,
    "toolName" "text" NOT NULL,
    "arguments" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "result" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "authorized" boolean DEFAULT true NOT NULL,
    "isError" boolean DEFAULT false NOT NULL,
    "errorMessage" "text",
    "latencyMs" integer,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ai_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ai_runs" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "conversationId" "uuid" NOT NULL,
    "agentId" "uuid" NOT NULL,
    "agentVersionId" "uuid" NOT NULL,
    "triggerMessageId" "uuid",
    "responseMessageId" "uuid",
    "status" "public"."AIRunStatus" DEFAULT 'RUNNING'::"public"."AIRunStatus" NOT NULL,
    "decision" "public"."AIRunDecision",
    "confidence" integer,
    "decisionReason" "text",
    "provider" "public"."AIProviderKind" NOT NULL,
    "model" "text",
    "errorMessage" "text",
    "latencyMs" integer,
    "startedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "finishedAt" timestamp(3) without time zone
);


--
-- Name: ai_usage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."ai_usage" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "runId" "uuid" NOT NULL,
    "provider" "public"."AIProviderKind" NOT NULL,
    "model" "text",
    "inputTokens" integer DEFAULT 0 NOT NULL,
    "outputTokens" integer DEFAULT 0 NOT NULL,
    "latencyMs" integer DEFAULT 0 NOT NULL,
    "estimatedCost" double precision DEFAULT 0 NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: appointments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."appointments" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "customerId" "uuid",
    "doctorId" "uuid",
    "procedureId" "uuid",
    "title" "text" NOT NULL,
    "patientName" "text",
    "patientPhone" "text",
    "patientEmail" "text",
    "appointmentDate" timestamp(3) without time zone NOT NULL,
    "durationMinutes" integer DEFAULT 30 NOT NULL,
    "status" "text" DEFAULT 'PENDING'::"text" NOT NULL,
    "service" "text",
    "doctorName" "text",
    "isAiProposed" boolean DEFAULT true NOT NULL,
    "aiProposalReason" "text",
    "notes" "text",
    "ticketNumber" "text",
    "ticketDate" timestamp(3) without time zone,
    "createdById" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."audit_logs" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid",
    "actorUserId" "uuid",
    "actorType" "text" DEFAULT 'USER'::"text" NOT NULL,
    "action" "text" NOT NULL,
    "resourceType" "text" NOT NULL,
    "resourceId" "text",
    "metadata" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "ipAddress" "text",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: case_classification_questions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."case_classification_questions" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "position" integer NOT NULL,
    "promptAr" "text" NOT NULL,
    "promptEn" "text" NOT NULL,
    "options" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "isRequired" boolean DEFAULT true NOT NULL,
    "isEnabled" boolean DEFAULT true NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: case_resolutions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."case_resolutions" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "conversationId" "uuid" NOT NULL,
    "customerIssue" "text" NOT NULL,
    "agentAction" "text" NOT NULL,
    "usedKnowledgeBase" boolean DEFAULT false NOT NULL,
    "documentId" "uuid",
    "customerAccepted" boolean,
    "customerSatisfied" boolean,
    "escalationRequested" boolean DEFAULT false NOT NULL,
    "escalatedToSupervisor" boolean DEFAULT false NOT NULL,
    "outcome" "public"."CaseOutcome" NOT NULL,
    "notes" "text",
    "recordedByUserId" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: case_solution_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."case_solution_mappings" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "matchAnswers" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "documentId" "uuid" NOT NULL,
    "priority" integer DEFAULT 100 NOT NULL,
    "isEnabled" boolean DEFAULT true NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: channel_connections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."channel_connections" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "channel" "public"."ChannelType" NOT NULL,
    "displayName" "text",
    "isEnabled" boolean DEFAULT true NOT NULL,
    "status" "public"."ChannelConnectionStatus" DEFAULT 'DISCONNECTED'::"public"."ChannelConnectionStatus" NOT NULL,
    "credentials" "text",
    "webhookSecret" "text",
    "lastError" "text",
    "lastInboundAt" timestamp(3) without time zone,
    "lastOutboundAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: conversation_classifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."conversation_classifications" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "conversationId" "uuid" NOT NULL,
    "answers" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "suggestedDocumentId" "uuid",
    "suggestionScore" double precision,
    "classifiedByUserId" "uuid",
    "classifiedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: conversation_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."conversation_events" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "conversationId" "uuid" NOT NULL,
    "type" "public"."ConversationEventType" NOT NULL,
    "actorType" "public"."MessageAuthorType" DEFAULT 'SYSTEM'::"public"."MessageAuthorType" NOT NULL,
    "actorUserId" "uuid",
    "payload" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "occurredAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: conversation_scores; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."conversation_scores" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "conversationId" "uuid" NOT NULL,
    "messageId" "uuid",
    "aiRunId" "uuid",
    "frustration" integer NOT NULL,
    "sentiment" integer NOT NULL,
    "urgency" integer NOT NULL,
    "risk" integer NOT NULL,
    "intentConfidence" integer NOT NULL,
    "aiConfidence" integer,
    "intent" "text",
    "evaluator" "text" DEFAULT 'heuristic'::"text" NOT NULL,
    "signals" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."conversations" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "customerId" "uuid" NOT NULL,
    "ticketNumber" character varying(14) NOT NULL,
    "channel" "public"."ChannelType" NOT NULL,
    "status" "public"."ConversationStatus" DEFAULT 'OPEN'::"public"."ConversationStatus" NOT NULL,
    "mode" "public"."ConversationMode" DEFAULT 'HUMAN'::"public"."ConversationMode" NOT NULL,
    "priority" "public"."ConversationPriority" DEFAULT 'NORMAL'::"public"."ConversationPriority" NOT NULL,
    "subject" "text",
    "assignedEmployeeId" "uuid",
    "lastReplierUserId" "uuid",
    "modeVersion" integer DEFAULT 0 NOT NULL,
    "messageCount" integer DEFAULT 0 NOT NULL,
    "lastMessageAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "firstResponseAt" timestamp(3) without time zone,
    "resolvedAt" timestamp(3) without time zone,
    "closedAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: customer_identities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."customer_identities" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "customerId" "uuid" NOT NULL,
    "platform" "public"."ChannelType" NOT NULL,
    "externalId" "text" NOT NULL,
    "displayName" "text",
    "isVerified" boolean DEFAULT false NOT NULL,
    "rawProfile" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: customers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."customers" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "cxId" character varying(9) NOT NULL,
    "displayName" "text" NOT NULL,
    "primaryEmail" "text",
    "primaryPhone" "text",
    "locale" "public"."Locale" DEFAULT 'ar'::"public"."Locale" NOT NULL,
    "tags" "text"[] DEFAULT ARRAY[]::"text"[],
    "notes" "text",
    "externalIds" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "metadata" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "firstSeenAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "lastSeenAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL,
    "accountId" "uuid"
);


--
-- Name: deals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."deals" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "customer" "uuid",
    "company" "text",
    "value" double precision NOT NULL,
    "currency" "text" DEFAULT 'USD'::"text" NOT NULL,
    "pipeline" "text" DEFAULT 'Sales'::"text" NOT NULL,
    "stage" "text" DEFAULT 'Prospect'::"text" NOT NULL,
    "expectedClose" timestamp(3) without time zone,
    "closedAt" timestamp(3) without time zone,
    "closedWon" boolean DEFAULT false NOT NULL,
    "tags" "text"[] DEFAULT ARRAY[]::"text"[],
    "notes" "text",
    "ownerId" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL,
    "accountId" "uuid",
    "dealSource" "text",
    "description" "text",
    "priority" "text",
    "probability" integer,
    "createdById" "uuid"
);


--
-- Name: doctors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."doctors" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "specialty" "text" NOT NULL,
    "department" "text",
    "email" "text",
    "phone" "text",
    "workingDays" integer[] DEFAULT ARRAY[0, 1, 2, 3, 4],
    "startHour" "text" DEFAULT '09:00'::"text" NOT NULL,
    "endHour" "text" DEFAULT '17:00'::"text" NOT NULL,
    "slotDuration" integer DEFAULT 30 NOT NULL,
    "isActive" boolean DEFAULT true NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: document_chunks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."document_chunks" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "knowledgeBaseId" "uuid" NOT NULL,
    "documentId" "uuid" NOT NULL,
    "chunkIndex" integer NOT NULL,
    "content" "text" NOT NULL,
    "tokenCount" integer DEFAULT 0 NOT NULL,
    "metadata" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "embedding" "public"."vector"(1536),
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."documents" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "knowledgeBaseId" "uuid" NOT NULL,
    "title" "text" NOT NULL,
    "sourceType" "public"."DocumentSourceType" DEFAULT 'FILE'::"public"."DocumentSourceType" NOT NULL,
    "storageKey" "text",
    "sourceUrl" "text",
    "mimeType" "text",
    "sizeBytes" integer,
    "contentHash" "text",
    "status" "public"."DocumentStatus" DEFAULT 'UPLOADED'::"public"."DocumentStatus" NOT NULL,
    "failureReason" "text",
    "chunkCount" integer DEFAULT 0 NOT NULL,
    "uploadedByUserId" "uuid",
    "processedAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: employee_availability; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."employee_availability" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "employeeId" "uuid" NOT NULL,
    "dayOfWeek" integer NOT NULL,
    "startMinute" integer NOT NULL,
    "endMinute" integer NOT NULL,
    "timezone" "text" DEFAULT 'Asia/Riyadh'::"text" NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: employee_skills; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."employee_skills" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "employeeId" "uuid" NOT NULL,
    "skill" "text" NOT NULL,
    "level" integer DEFAULT 3 NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: employees; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."employees" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "userId" "uuid" NOT NULL,
    "department" "text",
    "availabilityStatus" "public"."EmployeeAvailabilityStatus" DEFAULT 'OFFLINE'::"public"."EmployeeAvailabilityStatus" NOT NULL,
    "statusChangedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "maxConcurrentConversations" integer DEFAULT 5 NOT NULL,
    "activeConversationCount" integer DEFAULT 0 NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: escalation_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."escalation_rules" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "description" "text",
    "isEnabled" boolean DEFAULT true NOT NULL,
    "priority" integer DEFAULT 100 NOT NULL,
    "conditions" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "combinator" "text" DEFAULT 'ALL'::"text" NOT NULL,
    "department" "text",
    "createdByUserId" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: escalations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."escalations" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "conversationId" "uuid" NOT NULL,
    "ruleId" "uuid",
    "trigger" "public"."EscalationTrigger" NOT NULL,
    "status" "public"."EscalationStatus" DEFAULT 'PENDING'::"public"."EscalationStatus" NOT NULL,
    "reason" "text" NOT NULL,
    "summary" "text",
    "intent" "text",
    "department" "text",
    "scoreSnapshot" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "assignedEmployeeId" "uuid",
    "assignedAt" timestamp(3) without time zone,
    "resolvedAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: knowledge_bases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."knowledge_bases" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "description" "text",
    "isDefault" boolean DEFAULT false NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: knowledge_gaps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."knowledge_gaps" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "conversationId" "uuid",
    "query" "text" NOT NULL,
    "bestScore" double precision,
    "retrievedCount" integer DEFAULT 0 NOT NULL,
    "resolvedAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."messages" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "conversationId" "uuid" NOT NULL,
    "channel" "public"."ChannelType" NOT NULL,
    "direction" "public"."MessageDirection" NOT NULL,
    "authorType" "public"."MessageAuthorType" NOT NULL,
    "authorUserId" "uuid",
    "body" "text" NOT NULL,
    "contentType" "text" DEFAULT 'text/plain'::"text" NOT NULL,
    "attachments" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "externalMessageId" "text",
    "deliveryStatus" "public"."MessageDeliveryStatus" DEFAULT 'SENT'::"public"."MessageDeliveryStatus" NOT NULL,
    "failureReason" "text",
    "metadata" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "sentAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: notes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."notes" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "resourceType" "text" NOT NULL,
    "resourceId" "uuid" NOT NULL,
    "body" "text" NOT NULL,
    "authorUserId" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."notifications" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid",
    "userId" "uuid" NOT NULL,
    "type" "public"."NotificationType" NOT NULL,
    "title" "text" NOT NULL,
    "body" "text",
    "resourceType" "text",
    "resourceId" "uuid",
    "readAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: organizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."organizations" (
    "id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "slug" "text" NOT NULL,
    "defaultLocale" "public"."Locale" DEFAULT 'ar'::"public"."Locale" NOT NULL,
    "timezone" "text" DEFAULT 'Asia/Riyadh'::"text" NOT NULL,
    "isActive" boolean DEFAULT true NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: procedures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."procedures" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "specialty" "text" NOT NULL,
    "durationMinutes" integer DEFAULT 30 NOT NULL,
    "bufferMinutes" integer DEFAULT 0 NOT NULL,
    "price" double precision,
    "isActive" boolean DEFAULT true NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: project_memberships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."project_memberships" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "userId" "uuid" NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."projects" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "slug" "text" NOT NULL,
    "description" "text",
    "defaultLocale" "public"."Locale" DEFAULT 'ar'::"public"."Locale" NOT NULL,
    "timezone" "text" DEFAULT 'Asia/Riyadh'::"text" NOT NULL,
    "isArchived" boolean DEFAULT false NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: routing_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."routing_rules" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "isEnabled" boolean DEFAULT true NOT NULL,
    "priority" integer DEFAULT 100 NOT NULL,
    "intent" "text",
    "department" "text",
    "requiredSkill" "text",
    "strategy" "public"."RoutingStrategy" DEFAULT 'LEAST_LOADED'::"public"."RoutingStrategy" NOT NULL,
    "isTerminal" boolean DEFAULT true NOT NULL,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."sessions" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "userId" "uuid" NOT NULL,
    "tokenHash" "text" NOT NULL,
    "userAgent" "text",
    "ipAddress" "text",
    "expiresAt" timestamp(3) without time zone NOT NULL,
    "revokedAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "lastSeenAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."tasks" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "title" "text" NOT NULL,
    "description" "text",
    "priority" "text" DEFAULT 'medium'::"text" NOT NULL,
    "status" "text" DEFAULT 'open'::"text" NOT NULL,
    "dueDate" timestamp(3) without time zone,
    "completedAt" timestamp(3) without time zone,
    "customer" "text",
    "lead" "text",
    "deal" "text",
    "assignedTo" "text",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL,
    "createdById" "uuid"
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."users" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "email" "text" NOT NULL,
    "passwordHash" "text" NOT NULL,
    "fullName" "text" NOT NULL,
    "role" "public"."UserRole" DEFAULT 'EMPLOYEE'::"public"."UserRole" NOT NULL,
    "locale" "public"."Locale" DEFAULT 'ar'::"public"."Locale" NOT NULL,
    "isActive" boolean DEFAULT true NOT NULL,
    "lastLoginAt" timestamp(3) without time zone,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: views; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE "public"."views" (
    "id" "uuid" NOT NULL,
    "organizationId" "uuid" NOT NULL,
    "projectId" "uuid" NOT NULL,
    "objectType" "text" NOT NULL,
    "name" "text" NOT NULL,
    "type" "text" DEFAULT 'table'::"text" NOT NULL,
    "isDefault" boolean DEFAULT false NOT NULL,
    "isFavorite" boolean DEFAULT false NOT NULL,
    "filters" "jsonb",
    "sort" "jsonb",
    "columnConfig" "jsonb",
    "createdById" "uuid",
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "updatedAt" timestamp(3) without time zone NOT NULL
);


--
-- Name: Lead Lead_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."Lead"
    ADD CONSTRAINT "Lead_pkey" PRIMARY KEY ("id");


--
-- Name: accounts accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."accounts"
    ADD CONSTRAINT "accounts_pkey" PRIMARY KEY ("id");


--
-- Name: agent_status_sessions agent_status_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."agent_status_sessions"
    ADD CONSTRAINT "agent_status_sessions_pkey" PRIMARY KEY ("id");


--
-- Name: ai_agent_versions ai_agent_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_agent_versions"
    ADD CONSTRAINT "ai_agent_versions_pkey" PRIMARY KEY ("id");


--
-- Name: ai_agents ai_agents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_agents"
    ADD CONSTRAINT "ai_agents_pkey" PRIMARY KEY ("id");


--
-- Name: ai_run_retrievals ai_run_retrievals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_run_retrievals"
    ADD CONSTRAINT "ai_run_retrievals_pkey" PRIMARY KEY ("id");


--
-- Name: ai_run_steps ai_run_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_run_steps"
    ADD CONSTRAINT "ai_run_steps_pkey" PRIMARY KEY ("id");


--
-- Name: ai_run_tool_calls ai_run_tool_calls_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_run_tool_calls"
    ADD CONSTRAINT "ai_run_tool_calls_pkey" PRIMARY KEY ("id");


--
-- Name: ai_runs ai_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_runs"
    ADD CONSTRAINT "ai_runs_pkey" PRIMARY KEY ("id");


--
-- Name: ai_usage ai_usage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_usage"
    ADD CONSTRAINT "ai_usage_pkey" PRIMARY KEY ("id");


--
-- Name: appointments appointments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_pkey" PRIMARY KEY ("id");


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."audit_logs"
    ADD CONSTRAINT "audit_logs_pkey" PRIMARY KEY ("id");


--
-- Name: case_classification_questions case_classification_questions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."case_classification_questions"
    ADD CONSTRAINT "case_classification_questions_pkey" PRIMARY KEY ("id");


--
-- Name: case_resolutions case_resolutions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."case_resolutions"
    ADD CONSTRAINT "case_resolutions_pkey" PRIMARY KEY ("id");


--
-- Name: case_solution_mappings case_solution_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."case_solution_mappings"
    ADD CONSTRAINT "case_solution_mappings_pkey" PRIMARY KEY ("id");


--
-- Name: channel_connections channel_connections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."channel_connections"
    ADD CONSTRAINT "channel_connections_pkey" PRIMARY KEY ("id");


--
-- Name: conversation_classifications conversation_classifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversation_classifications"
    ADD CONSTRAINT "conversation_classifications_pkey" PRIMARY KEY ("id");


--
-- Name: conversation_events conversation_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversation_events"
    ADD CONSTRAINT "conversation_events_pkey" PRIMARY KEY ("id");


--
-- Name: conversation_scores conversation_scores_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversation_scores"
    ADD CONSTRAINT "conversation_scores_pkey" PRIMARY KEY ("id");


--
-- Name: conversations conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversations"
    ADD CONSTRAINT "conversations_pkey" PRIMARY KEY ("id");


--
-- Name: customer_identities customer_identities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."customer_identities"
    ADD CONSTRAINT "customer_identities_pkey" PRIMARY KEY ("id");


--
-- Name: customers customers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."customers"
    ADD CONSTRAINT "customers_pkey" PRIMARY KEY ("id");


--
-- Name: deals deals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."deals"
    ADD CONSTRAINT "deals_pkey" PRIMARY KEY ("id");


--
-- Name: doctors doctors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."doctors"
    ADD CONSTRAINT "doctors_pkey" PRIMARY KEY ("id");


--
-- Name: document_chunks document_chunks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."document_chunks"
    ADD CONSTRAINT "document_chunks_pkey" PRIMARY KEY ("id");


--
-- Name: documents documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."documents"
    ADD CONSTRAINT "documents_pkey" PRIMARY KEY ("id");


--
-- Name: employee_availability employee_availability_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."employee_availability"
    ADD CONSTRAINT "employee_availability_pkey" PRIMARY KEY ("id");


--
-- Name: employee_skills employee_skills_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."employee_skills"
    ADD CONSTRAINT "employee_skills_pkey" PRIMARY KEY ("id");


--
-- Name: employees employees_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."employees"
    ADD CONSTRAINT "employees_pkey" PRIMARY KEY ("id");


--
-- Name: escalation_rules escalation_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."escalation_rules"
    ADD CONSTRAINT "escalation_rules_pkey" PRIMARY KEY ("id");


--
-- Name: escalations escalations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."escalations"
    ADD CONSTRAINT "escalations_pkey" PRIMARY KEY ("id");


--
-- Name: knowledge_bases knowledge_bases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."knowledge_bases"
    ADD CONSTRAINT "knowledge_bases_pkey" PRIMARY KEY ("id");


--
-- Name: knowledge_gaps knowledge_gaps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."knowledge_gaps"
    ADD CONSTRAINT "knowledge_gaps_pkey" PRIMARY KEY ("id");


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."messages"
    ADD CONSTRAINT "messages_pkey" PRIMARY KEY ("id");


--
-- Name: notes notes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."notes"
    ADD CONSTRAINT "notes_pkey" PRIMARY KEY ("id");


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."notifications"
    ADD CONSTRAINT "notifications_pkey" PRIMARY KEY ("id");


--
-- Name: organizations organizations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."organizations"
    ADD CONSTRAINT "organizations_pkey" PRIMARY KEY ("id");


--
-- Name: procedures procedures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."procedures"
    ADD CONSTRAINT "procedures_pkey" PRIMARY KEY ("id");


--
-- Name: project_memberships project_memberships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."project_memberships"
    ADD CONSTRAINT "project_memberships_pkey" PRIMARY KEY ("id");


--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."projects"
    ADD CONSTRAINT "projects_pkey" PRIMARY KEY ("id");


--
-- Name: routing_rules routing_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."routing_rules"
    ADD CONSTRAINT "routing_rules_pkey" PRIMARY KEY ("id");


--
-- Name: sessions sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."sessions"
    ADD CONSTRAINT "sessions_pkey" PRIMARY KEY ("id");


--
-- Name: tasks tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tasks"
    ADD CONSTRAINT "tasks_pkey" PRIMARY KEY ("id");


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."users"
    ADD CONSTRAINT "users_pkey" PRIMARY KEY ("id");


--
-- Name: views views_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."views"
    ADD CONSTRAINT "views_pkey" PRIMARY KEY ("id");


--
-- Name: Lead_organizationId_accountId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Lead_organizationId_accountId_idx" ON "public"."Lead" USING "btree" ("organizationId", "accountId");


--
-- Name: Lead_organizationId_customerId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Lead_organizationId_customerId_idx" ON "public"."Lead" USING "btree" ("organizationId", "customerId");


--
-- Name: Lead_organizationId_projectId_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "Lead_organizationId_projectId_status_idx" ON "public"."Lead" USING "btree" ("organizationId", "projectId", "status");


--
-- Name: accounts_organizationId_industry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "accounts_organizationId_industry_idx" ON "public"."accounts" USING "btree" ("organizationId", "industry");


--
-- Name: accounts_organizationId_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "accounts_organizationId_name_idx" ON "public"."accounts" USING "btree" ("organizationId", "name");


--
-- Name: accounts_organizationId_name_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "accounts_organizationId_name_key" ON "public"."accounts" USING "btree" ("organizationId", "name");


--
-- Name: agent_status_sessions_employeeId_endedAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "agent_status_sessions_employeeId_endedAt_idx" ON "public"."agent_status_sessions" USING "btree" ("employeeId", "endedAt");


--
-- Name: agent_status_sessions_employeeId_startedAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "agent_status_sessions_employeeId_startedAt_idx" ON "public"."agent_status_sessions" USING "btree" ("employeeId", "startedAt");


--
-- Name: agent_status_sessions_organizationId_startedAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "agent_status_sessions_organizationId_startedAt_idx" ON "public"."agent_status_sessions" USING "btree" ("organizationId", "startedAt");


--
-- Name: ai_agent_versions_agentId_version_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "ai_agent_versions_agentId_version_key" ON "public"."ai_agent_versions" USING "btree" ("agentId", "version");


--
-- Name: ai_agent_versions_organizationId_projectId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ai_agent_versions_organizationId_projectId_idx" ON "public"."ai_agent_versions" USING "btree" ("organizationId", "projectId");


--
-- Name: ai_agents_activeVersionId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "ai_agents_activeVersionId_key" ON "public"."ai_agents" USING "btree" ("activeVersionId");


--
-- Name: ai_agents_organizationId_projectId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ai_agents_organizationId_projectId_idx" ON "public"."ai_agents" USING "btree" ("organizationId", "projectId");


--
-- Name: ai_agents_projectId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "ai_agents_projectId_key" ON "public"."ai_agents" USING "btree" ("projectId");


--
-- Name: ai_run_retrievals_runId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ai_run_retrievals_runId_idx" ON "public"."ai_run_retrievals" USING "btree" ("runId");


--
-- Name: ai_run_steps_runId_sequence_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "ai_run_steps_runId_sequence_key" ON "public"."ai_run_steps" USING "btree" ("runId", "sequence");


--
-- Name: ai_run_tool_calls_runId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ai_run_tool_calls_runId_idx" ON "public"."ai_run_tool_calls" USING "btree" ("runId");


--
-- Name: ai_run_tool_calls_runId_sequence_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "ai_run_tool_calls_runId_sequence_key" ON "public"."ai_run_tool_calls" USING "btree" ("runId", "sequence");


--
-- Name: ai_runs_agentVersionId_startedAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ai_runs_agentVersionId_startedAt_idx" ON "public"."ai_runs" USING "btree" ("agentVersionId", "startedAt");


--
-- Name: ai_runs_conversationId_startedAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ai_runs_conversationId_startedAt_idx" ON "public"."ai_runs" USING "btree" ("conversationId", "startedAt");


--
-- Name: ai_runs_organizationId_projectId_startedAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ai_runs_organizationId_projectId_startedAt_idx" ON "public"."ai_runs" USING "btree" ("organizationId", "projectId", "startedAt");


--
-- Name: ai_usage_organizationId_projectId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "ai_usage_organizationId_projectId_createdAt_idx" ON "public"."ai_usage" USING "btree" ("organizationId", "projectId", "createdAt");


--
-- Name: ai_usage_runId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "ai_usage_runId_key" ON "public"."ai_usage" USING "btree" ("runId");


--
-- Name: appointments_organizationId_appointmentDate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "appointments_organizationId_appointmentDate_idx" ON "public"."appointments" USING "btree" ("organizationId", "appointmentDate");


--
-- Name: appointments_organizationId_customerId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "appointments_organizationId_customerId_idx" ON "public"."appointments" USING "btree" ("organizationId", "customerId");


--
-- Name: appointments_organizationId_doctorId_appointmentDate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "appointments_organizationId_doctorId_appointmentDate_idx" ON "public"."appointments" USING "btree" ("organizationId", "doctorId", "appointmentDate");


--
-- Name: appointments_organizationId_projectId_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "appointments_organizationId_projectId_status_idx" ON "public"."appointments" USING "btree" ("organizationId", "projectId", "status");


--
-- Name: audit_logs_organizationId_action_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "audit_logs_organizationId_action_createdAt_idx" ON "public"."audit_logs" USING "btree" ("organizationId", "action", "createdAt");


--
-- Name: audit_logs_organizationId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "audit_logs_organizationId_createdAt_idx" ON "public"."audit_logs" USING "btree" ("organizationId", "createdAt");


--
-- Name: audit_logs_organizationId_projectId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "audit_logs_organizationId_projectId_createdAt_idx" ON "public"."audit_logs" USING "btree" ("organizationId", "projectId", "createdAt");


--
-- Name: case_classification_questions_organizationId_projectId_isEn_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "case_classification_questions_organizationId_projectId_isEn_idx" ON "public"."case_classification_questions" USING "btree" ("organizationId", "projectId", "isEnabled");


--
-- Name: case_classification_questions_projectId_position_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "case_classification_questions_projectId_position_key" ON "public"."case_classification_questions" USING "btree" ("projectId", "position");


--
-- Name: case_resolutions_conversationId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "case_resolutions_conversationId_key" ON "public"."case_resolutions" USING "btree" ("conversationId");


--
-- Name: case_resolutions_organizationId_projectId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "case_resolutions_organizationId_projectId_createdAt_idx" ON "public"."case_resolutions" USING "btree" ("organizationId", "projectId", "createdAt");


--
-- Name: case_resolutions_organizationId_projectId_outcome_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "case_resolutions_organizationId_projectId_outcome_idx" ON "public"."case_resolutions" USING "btree" ("organizationId", "projectId", "outcome");


--
-- Name: case_solution_mappings_organizationId_projectId_isEnabled_p_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "case_solution_mappings_organizationId_projectId_isEnabled_p_idx" ON "public"."case_solution_mappings" USING "btree" ("organizationId", "projectId", "isEnabled", "priority");


--
-- Name: channel_connections_organizationId_projectId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "channel_connections_organizationId_projectId_idx" ON "public"."channel_connections" USING "btree" ("organizationId", "projectId");


--
-- Name: channel_connections_projectId_channel_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "channel_connections_projectId_channel_key" ON "public"."channel_connections" USING "btree" ("projectId", "channel");


--
-- Name: conversation_classifications_conversationId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "conversation_classifications_conversationId_key" ON "public"."conversation_classifications" USING "btree" ("conversationId");


--
-- Name: conversation_classifications_organizationId_projectId_class_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversation_classifications_organizationId_projectId_class_idx" ON "public"."conversation_classifications" USING "btree" ("organizationId", "projectId", "classifiedAt");


--
-- Name: conversation_events_conversationId_occurredAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversation_events_conversationId_occurredAt_idx" ON "public"."conversation_events" USING "btree" ("conversationId", "occurredAt");


--
-- Name: conversation_events_organizationId_projectId_type_occurredA_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversation_events_organizationId_projectId_type_occurredA_idx" ON "public"."conversation_events" USING "btree" ("organizationId", "projectId", "type", "occurredAt");


--
-- Name: conversation_scores_conversationId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversation_scores_conversationId_createdAt_idx" ON "public"."conversation_scores" USING "btree" ("conversationId", "createdAt");


--
-- Name: conversation_scores_organizationId_projectId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversation_scores_organizationId_projectId_createdAt_idx" ON "public"."conversation_scores" USING "btree" ("organizationId", "projectId", "createdAt");


--
-- Name: conversations_organizationId_projectId_assignedEmployeeId_l_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversations_organizationId_projectId_assignedEmployeeId_l_idx" ON "public"."conversations" USING "btree" ("organizationId", "projectId", "assignedEmployeeId", "lastMessageAt");


--
-- Name: conversations_organizationId_projectId_channel_lastMessageA_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversations_organizationId_projectId_channel_lastMessageA_idx" ON "public"."conversations" USING "btree" ("organizationId", "projectId", "channel", "lastMessageAt");


--
-- Name: conversations_organizationId_projectId_lastMessageAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversations_organizationId_projectId_lastMessageAt_idx" ON "public"."conversations" USING "btree" ("organizationId", "projectId", "lastMessageAt");


--
-- Name: conversations_organizationId_projectId_mode_lastMessageAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversations_organizationId_projectId_mode_lastMessageAt_idx" ON "public"."conversations" USING "btree" ("organizationId", "projectId", "mode", "lastMessageAt");


--
-- Name: conversations_organizationId_projectId_status_lastMessageAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversations_organizationId_projectId_status_lastMessageAt_idx" ON "public"."conversations" USING "btree" ("organizationId", "projectId", "status", "lastMessageAt");


--
-- Name: conversations_projectId_customerId_lastMessageAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "conversations_projectId_customerId_lastMessageAt_idx" ON "public"."conversations" USING "btree" ("projectId", "customerId", "lastMessageAt");


--
-- Name: conversations_ticketNumber_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "conversations_ticketNumber_key" ON "public"."conversations" USING "btree" ("ticketNumber");


--
-- Name: customer_identities_organizationId_projectId_customerId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "customer_identities_organizationId_projectId_customerId_idx" ON "public"."customer_identities" USING "btree" ("organizationId", "projectId", "customerId");


--
-- Name: customer_identities_projectId_platform_externalId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "customer_identities_projectId_platform_externalId_key" ON "public"."customer_identities" USING "btree" ("projectId", "platform", "externalId");


--
-- Name: customers_cxId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "customers_cxId_key" ON "public"."customers" USING "btree" ("cxId");


--
-- Name: customers_organizationId_accountId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "customers_organizationId_accountId_idx" ON "public"."customers" USING "btree" ("organizationId", "accountId");


--
-- Name: customers_organizationId_projectId_displayName_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "customers_organizationId_projectId_displayName_idx" ON "public"."customers" USING "btree" ("organizationId", "projectId", "displayName");


--
-- Name: customers_organizationId_projectId_lastSeenAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "customers_organizationId_projectId_lastSeenAt_idx" ON "public"."customers" USING "btree" ("organizationId", "projectId", "lastSeenAt");


--
-- Name: customers_projectId_primaryEmail_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "customers_projectId_primaryEmail_idx" ON "public"."customers" USING "btree" ("projectId", "primaryEmail");


--
-- Name: customers_projectId_primaryPhone_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "customers_projectId_primaryPhone_idx" ON "public"."customers" USING "btree" ("projectId", "primaryPhone");


--
-- Name: deals_organizationId_accountId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "deals_organizationId_accountId_idx" ON "public"."deals" USING "btree" ("organizationId", "accountId");


--
-- Name: deals_organizationId_projectId_pipeline_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "deals_organizationId_projectId_pipeline_idx" ON "public"."deals" USING "btree" ("organizationId", "projectId", "pipeline");


--
-- Name: deals_organizationId_projectId_stage_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "deals_organizationId_projectId_stage_idx" ON "public"."deals" USING "btree" ("organizationId", "projectId", "stage");


--
-- Name: doctors_organizationId_projectId_isActive_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "doctors_organizationId_projectId_isActive_idx" ON "public"."doctors" USING "btree" ("organizationId", "projectId", "isActive");


--
-- Name: doctors_organizationId_specialty_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "doctors_organizationId_specialty_idx" ON "public"."doctors" USING "btree" ("organizationId", "specialty");


--
-- Name: document_chunks_documentId_chunkIndex_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "document_chunks_documentId_chunkIndex_key" ON "public"."document_chunks" USING "btree" ("documentId", "chunkIndex");


--
-- Name: document_chunks_organizationId_projectId_knowledgeBaseId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "document_chunks_organizationId_projectId_knowledgeBaseId_idx" ON "public"."document_chunks" USING "btree" ("organizationId", "projectId", "knowledgeBaseId");


--
-- Name: documents_knowledgeBaseId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "documents_knowledgeBaseId_createdAt_idx" ON "public"."documents" USING "btree" ("knowledgeBaseId", "createdAt");


--
-- Name: documents_organizationId_projectId_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "documents_organizationId_projectId_status_idx" ON "public"."documents" USING "btree" ("organizationId", "projectId", "status");


--
-- Name: documents_projectId_contentHash_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "documents_projectId_contentHash_idx" ON "public"."documents" USING "btree" ("projectId", "contentHash");


--
-- Name: employee_availability_employeeId_dayOfWeek_startMinute_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "employee_availability_employeeId_dayOfWeek_startMinute_key" ON "public"."employee_availability" USING "btree" ("employeeId", "dayOfWeek", "startMinute");


--
-- Name: employee_availability_organizationId_employeeId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "employee_availability_organizationId_employeeId_idx" ON "public"."employee_availability" USING "btree" ("organizationId", "employeeId");


--
-- Name: employee_skills_employeeId_skill_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "employee_skills_employeeId_skill_key" ON "public"."employee_skills" USING "btree" ("employeeId", "skill");


--
-- Name: employee_skills_organizationId_skill_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "employee_skills_organizationId_skill_idx" ON "public"."employee_skills" USING "btree" ("organizationId", "skill");


--
-- Name: employees_organizationId_availabilityStatus_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "employees_organizationId_availabilityStatus_idx" ON "public"."employees" USING "btree" ("organizationId", "availabilityStatus");


--
-- Name: employees_userId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "employees_userId_key" ON "public"."employees" USING "btree" ("userId");


--
-- Name: escalation_rules_organizationId_projectId_isEnabled_priorit_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "escalation_rules_organizationId_projectId_isEnabled_priorit_idx" ON "public"."escalation_rules" USING "btree" ("organizationId", "projectId", "isEnabled", "priority");


--
-- Name: escalations_conversationId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "escalations_conversationId_createdAt_idx" ON "public"."escalations" USING "btree" ("conversationId", "createdAt");


--
-- Name: escalations_organizationId_projectId_status_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "escalations_organizationId_projectId_status_createdAt_idx" ON "public"."escalations" USING "btree" ("organizationId", "projectId", "status", "createdAt");


--
-- Name: knowledge_bases_organizationId_projectId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "knowledge_bases_organizationId_projectId_idx" ON "public"."knowledge_bases" USING "btree" ("organizationId", "projectId");


--
-- Name: knowledge_bases_projectId_name_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "knowledge_bases_projectId_name_key" ON "public"."knowledge_bases" USING "btree" ("projectId", "name");


--
-- Name: knowledge_gaps_organizationId_projectId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "knowledge_gaps_organizationId_projectId_createdAt_idx" ON "public"."knowledge_gaps" USING "btree" ("organizationId", "projectId", "createdAt");


--
-- Name: knowledge_gaps_projectId_resolvedAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "knowledge_gaps_projectId_resolvedAt_idx" ON "public"."knowledge_gaps" USING "btree" ("projectId", "resolvedAt");


--
-- Name: messages_conversationId_sentAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "messages_conversationId_sentAt_idx" ON "public"."messages" USING "btree" ("conversationId", "sentAt");


--
-- Name: messages_organizationId_projectId_conversationId_sentAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "messages_organizationId_projectId_conversationId_sentAt_idx" ON "public"."messages" USING "btree" ("organizationId", "projectId", "conversationId", "sentAt");


--
-- Name: messages_projectId_channel_externalMessageId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "messages_projectId_channel_externalMessageId_key" ON "public"."messages" USING "btree" ("projectId", "channel", "externalMessageId");


--
-- Name: notes_organizationId_authorUserId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "notes_organizationId_authorUserId_createdAt_idx" ON "public"."notes" USING "btree" ("organizationId", "authorUserId", "createdAt");


--
-- Name: notes_organizationId_projectId_resourceType_resourceId_crea_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "notes_organizationId_projectId_resourceType_resourceId_crea_idx" ON "public"."notes" USING "btree" ("organizationId", "projectId", "resourceType", "resourceId", "createdAt");


--
-- Name: notifications_organizationId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "notifications_organizationId_createdAt_idx" ON "public"."notifications" USING "btree" ("organizationId", "createdAt");


--
-- Name: notifications_userId_readAt_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "notifications_userId_readAt_createdAt_idx" ON "public"."notifications" USING "btree" ("userId", "readAt", "createdAt");


--
-- Name: organizations_slug_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "organizations_slug_key" ON "public"."organizations" USING "btree" ("slug");


--
-- Name: procedures_organizationId_projectId_isActive_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "procedures_organizationId_projectId_isActive_idx" ON "public"."procedures" USING "btree" ("organizationId", "projectId", "isActive");


--
-- Name: procedures_organizationId_specialty_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "procedures_organizationId_specialty_idx" ON "public"."procedures" USING "btree" ("organizationId", "specialty");


--
-- Name: project_memberships_organizationId_projectId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "project_memberships_organizationId_projectId_idx" ON "public"."project_memberships" USING "btree" ("organizationId", "projectId");


--
-- Name: project_memberships_projectId_userId_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "project_memberships_projectId_userId_key" ON "public"."project_memberships" USING "btree" ("projectId", "userId");


--
-- Name: project_memberships_userId_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "project_memberships_userId_idx" ON "public"."project_memberships" USING "btree" ("userId");


--
-- Name: projects_organizationId_createdAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "projects_organizationId_createdAt_idx" ON "public"."projects" USING "btree" ("organizationId", "createdAt");


--
-- Name: projects_organizationId_isArchived_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "projects_organizationId_isArchived_idx" ON "public"."projects" USING "btree" ("organizationId", "isArchived");


--
-- Name: projects_organizationId_slug_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "projects_organizationId_slug_key" ON "public"."projects" USING "btree" ("organizationId", "slug");


--
-- Name: routing_rules_organizationId_projectId_isEnabled_priority_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "routing_rules_organizationId_projectId_isEnabled_priority_idx" ON "public"."routing_rules" USING "btree" ("organizationId", "projectId", "isEnabled", "priority");


--
-- Name: sessions_expiresAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "sessions_expiresAt_idx" ON "public"."sessions" USING "btree" ("expiresAt");


--
-- Name: sessions_tokenHash_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "sessions_tokenHash_key" ON "public"."sessions" USING "btree" ("tokenHash");


--
-- Name: sessions_userId_expiresAt_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "sessions_userId_expiresAt_idx" ON "public"."sessions" USING "btree" ("userId", "expiresAt");


--
-- Name: tasks_organizationId_projectId_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "tasks_organizationId_projectId_status_idx" ON "public"."tasks" USING "btree" ("organizationId", "projectId", "status");


--
-- Name: tasks_projectId_dueDate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "tasks_projectId_dueDate_idx" ON "public"."tasks" USING "btree" ("projectId", "dueDate");


--
-- Name: tasks_projectId_title_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "tasks_projectId_title_key" ON "public"."tasks" USING "btree" ("projectId", "title");


--
-- Name: users_email_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX "users_email_key" ON "public"."users" USING "btree" ("email");


--
-- Name: users_organizationId_isActive_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "users_organizationId_isActive_idx" ON "public"."users" USING "btree" ("organizationId", "isActive");


--
-- Name: users_organizationId_role_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "users_organizationId_role_idx" ON "public"."users" USING "btree" ("organizationId", "role");


--
-- Name: views_organizationId_projectId_objectType_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "views_organizationId_projectId_objectType_idx" ON "public"."views" USING "btree" ("organizationId", "projectId", "objectType");


--
-- Name: views_projectId_isFavorite_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "views_projectId_isFavorite_idx" ON "public"."views" USING "btree" ("projectId", "isFavorite");


--
-- Name: Lead Lead_accountId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."Lead"
    ADD CONSTRAINT "Lead_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "public"."accounts"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: Lead Lead_createdById_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."Lead"
    ADD CONSTRAINT "Lead_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: Lead Lead_customerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."Lead"
    ADD CONSTRAINT "Lead_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "public"."customers"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: Lead Lead_ownerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."Lead"
    ADD CONSTRAINT "Lead_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: accounts accounts_createdById_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."accounts"
    ADD CONSTRAINT "accounts_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: accounts accounts_organizationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."accounts"
    ADD CONSTRAINT "accounts_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: accounts accounts_ownerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."accounts"
    ADD CONSTRAINT "accounts_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: agent_status_sessions agent_status_sessions_employeeId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."agent_status_sessions"
    ADD CONSTRAINT "agent_status_sessions_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "public"."employees"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ai_agent_versions ai_agent_versions_agentId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_agent_versions"
    ADD CONSTRAINT "ai_agent_versions_agentId_fkey" FOREIGN KEY ("agentId") REFERENCES "public"."ai_agents"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ai_agents ai_agents_activeVersionId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_agents"
    ADD CONSTRAINT "ai_agents_activeVersionId_fkey" FOREIGN KEY ("activeVersionId") REFERENCES "public"."ai_agent_versions"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: ai_agents ai_agents_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_agents"
    ADD CONSTRAINT "ai_agents_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ai_run_retrievals ai_run_retrievals_runId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_run_retrievals"
    ADD CONSTRAINT "ai_run_retrievals_runId_fkey" FOREIGN KEY ("runId") REFERENCES "public"."ai_runs"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ai_run_steps ai_run_steps_runId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_run_steps"
    ADD CONSTRAINT "ai_run_steps_runId_fkey" FOREIGN KEY ("runId") REFERENCES "public"."ai_runs"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ai_run_tool_calls ai_run_tool_calls_runId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_run_tool_calls"
    ADD CONSTRAINT "ai_run_tool_calls_runId_fkey" FOREIGN KEY ("runId") REFERENCES "public"."ai_runs"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ai_runs ai_runs_agentId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_runs"
    ADD CONSTRAINT "ai_runs_agentId_fkey" FOREIGN KEY ("agentId") REFERENCES "public"."ai_agents"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ai_runs ai_runs_agentVersionId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_runs"
    ADD CONSTRAINT "ai_runs_agentVersionId_fkey" FOREIGN KEY ("agentVersionId") REFERENCES "public"."ai_agent_versions"("id") ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: ai_runs ai_runs_conversationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_runs"
    ADD CONSTRAINT "ai_runs_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "public"."conversations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: ai_usage ai_usage_runId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."ai_usage"
    ADD CONSTRAINT "ai_usage_runId_fkey" FOREIGN KEY ("runId") REFERENCES "public"."ai_runs"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: appointments appointments_createdById_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: appointments appointments_customerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "public"."customers"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: appointments appointments_doctorId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_doctorId_fkey" FOREIGN KEY ("doctorId") REFERENCES "public"."doctors"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: appointments appointments_procedureId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."appointments"
    ADD CONSTRAINT "appointments_procedureId_fkey" FOREIGN KEY ("procedureId") REFERENCES "public"."procedures"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: audit_logs audit_logs_actorUserId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."audit_logs"
    ADD CONSTRAINT "audit_logs_actorUserId_fkey" FOREIGN KEY ("actorUserId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: audit_logs audit_logs_organizationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."audit_logs"
    ADD CONSTRAINT "audit_logs_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: case_classification_questions case_classification_questions_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."case_classification_questions"
    ADD CONSTRAINT "case_classification_questions_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: case_resolutions case_resolutions_conversationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."case_resolutions"
    ADD CONSTRAINT "case_resolutions_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "public"."conversations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: channel_connections channel_connections_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."channel_connections"
    ADD CONSTRAINT "channel_connections_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: conversation_classifications conversation_classifications_conversationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversation_classifications"
    ADD CONSTRAINT "conversation_classifications_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "public"."conversations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: conversation_events conversation_events_conversationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversation_events"
    ADD CONSTRAINT "conversation_events_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "public"."conversations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: conversation_scores conversation_scores_conversationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversation_scores"
    ADD CONSTRAINT "conversation_scores_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "public"."conversations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: conversations conversations_assignedEmployeeId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversations"
    ADD CONSTRAINT "conversations_assignedEmployeeId_fkey" FOREIGN KEY ("assignedEmployeeId") REFERENCES "public"."employees"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: conversations conversations_customerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversations"
    ADD CONSTRAINT "conversations_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "public"."customers"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: conversations conversations_lastReplierUserId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversations"
    ADD CONSTRAINT "conversations_lastReplierUserId_fkey" FOREIGN KEY ("lastReplierUserId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: conversations conversations_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."conversations"
    ADD CONSTRAINT "conversations_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: customer_identities customer_identities_customerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."customer_identities"
    ADD CONSTRAINT "customer_identities_customerId_fkey" FOREIGN KEY ("customerId") REFERENCES "public"."customers"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: customers customers_accountId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."customers"
    ADD CONSTRAINT "customers_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "public"."accounts"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: customers customers_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."customers"
    ADD CONSTRAINT "customers_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: deals deals_accountId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."deals"
    ADD CONSTRAINT "deals_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "public"."accounts"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: deals deals_createdById_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."deals"
    ADD CONSTRAINT "deals_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: deals deals_ownerId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."deals"
    ADD CONSTRAINT "deals_ownerId_fkey" FOREIGN KEY ("ownerId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: document_chunks document_chunks_documentId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."document_chunks"
    ADD CONSTRAINT "document_chunks_documentId_fkey" FOREIGN KEY ("documentId") REFERENCES "public"."documents"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: documents documents_knowledgeBaseId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."documents"
    ADD CONSTRAINT "documents_knowledgeBaseId_fkey" FOREIGN KEY ("knowledgeBaseId") REFERENCES "public"."knowledge_bases"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: employee_availability employee_availability_employeeId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."employee_availability"
    ADD CONSTRAINT "employee_availability_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "public"."employees"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: employee_skills employee_skills_employeeId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."employee_skills"
    ADD CONSTRAINT "employee_skills_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "public"."employees"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: employees employees_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."employees"
    ADD CONSTRAINT "employees_userId_fkey" FOREIGN KEY ("userId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: escalation_rules escalation_rules_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."escalation_rules"
    ADD CONSTRAINT "escalation_rules_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: escalations escalations_conversationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."escalations"
    ADD CONSTRAINT "escalations_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "public"."conversations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: escalations escalations_ruleId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."escalations"
    ADD CONSTRAINT "escalations_ruleId_fkey" FOREIGN KEY ("ruleId") REFERENCES "public"."escalation_rules"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: knowledge_bases knowledge_bases_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."knowledge_bases"
    ADD CONSTRAINT "knowledge_bases_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: messages messages_authorUserId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."messages"
    ADD CONSTRAINT "messages_authorUserId_fkey" FOREIGN KEY ("authorUserId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: messages messages_conversationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."messages"
    ADD CONSTRAINT "messages_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "public"."conversations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: notes notes_authorUserId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."notes"
    ADD CONSTRAINT "notes_authorUserId_fkey" FOREIGN KEY ("authorUserId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: notes notes_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."notes"
    ADD CONSTRAINT "notes_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: notifications notifications_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."notifications"
    ADD CONSTRAINT "notifications_userId_fkey" FOREIGN KEY ("userId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: project_memberships project_memberships_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."project_memberships"
    ADD CONSTRAINT "project_memberships_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: project_memberships project_memberships_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."project_memberships"
    ADD CONSTRAINT "project_memberships_userId_fkey" FOREIGN KEY ("userId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: projects projects_organizationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."projects"
    ADD CONSTRAINT "projects_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: routing_rules routing_rules_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."routing_rules"
    ADD CONSTRAINT "routing_rules_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: sessions sessions_userId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."sessions"
    ADD CONSTRAINT "sessions_userId_fkey" FOREIGN KEY ("userId") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: tasks tasks_createdById_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."tasks"
    ADD CONSTRAINT "tasks_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "public"."users"("id") ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: users users_organizationId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."users"
    ADD CONSTRAINT "users_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "public"."organizations"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: views views_projectId_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY "public"."views"
    ADD CONSTRAINT "views_projectId_fkey" FOREIGN KEY ("projectId") REFERENCES "public"."projects"("id") ON UPDATE CASCADE ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

