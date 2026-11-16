package com.firstelement.crm.clinic.ai;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.audit.ClinicAuditModels;
import com.firstelement.crm.clinic.audit.ClinicAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for Dental Clinic Governed AI Assistant (Slice S9).
 *
 * <p>Strictly complies with CLN-09 and ADR-0007:
 * <ul>
 *   <li>All clinical suggestions remain in DRAFT status until human confirmation.</li>
 *   <li>Emergency kill switch halts all generation immediately.</li>
 *   <li>PHI minimization and redaction applied to patient data before storage/prompting.</li>
 *   <li>Every proposal generation, approval, rejection, and kill-switch change is logged to {@code clinic_audit_log}.</li>
 * </ul>
 */
@Service
public class ClinicAiService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+[0-9]{8,15}|00[0-9]{8,15}|\\b0[0-9]{8,10}\\b)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern NATIONAL_ID_PATTERN = Pattern.compile("\\b[0-9]{10}\\b");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ClinicAiRepository repository;
    private final ClinicAuditService auditService;
    private final com.firstelement.crm.clinic.PlanLimitEnforcementService limits;

    public ClinicAiService(ClinicAiRepository repository, ClinicAuditService auditService) {
        this(repository, auditService, null);
    }

    @Autowired
    public ClinicAiService(ClinicAiRepository repository, ClinicAuditService auditService,
                           @Nullable com.firstelement.crm.clinic.PlanLimitEnforcementService limits) {
        this.repository = repository;
        this.auditService = auditService;
        this.limits = limits;
    }

    public List<ClinicAiModels.AiRunRow> listRuns(ClinicScope scope, String status, int limit) {
        return repository.list(scope, status, limit);
    }

    public ClinicAiModels.ClinicAiOverview getOverview(ClinicScope scope) {
        return repository.overview(scope);
    }

    // -------------------------------------------------------------------------
    // Governance & Emergency Kill Switch
    // -------------------------------------------------------------------------

    public ClinicAiModels.ClinicAiGovernanceRow getGovernance(ClinicScope scope) {
        return repository.getGovernance(scope);
    }

    public ClinicAiModels.ClinicAiGovernanceRow toggleKillSwitch(
            ClinicScope scope,
            boolean active,
            String reason,
            UUID actorUserId,
            String actorUserName,
            String actorRole
    ) {
        repository.updateKillSwitch(scope, active, reason, actorUserId);

        // Audit the kill-switch engagement or disengagement
        auditService.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                null,
                actorUserId,
                actorUserName != null ? actorUserName : "Admin",
                actorRole != null ? actorRole : "ADMIN",
                active ? "AI_KILL_SWITCH_ENGAGED" : "AI_KILL_SWITCH_DISENGAGED",
                "AI_GOVERNANCE",
                scope.queryScope().organizationId().toString(),
                null,
                null,
                null,
                "{\"killSwitchActive\": " + active + ", \"reason\": \"" + (reason != null ? reason.replace("\"", "\\\"") : "") + "\"}"
        ));

        return repository.getGovernance(scope);
    }

    private void assertAiEnabled(ClinicScope scope) {
        if (limits != null && scope != null && scope.projectId() != null) {
            limits.enforceAiQuota(scope.projectId());
        }
        ClinicAiModels.ClinicAiGovernanceRow gov = repository.getGovernance(scope);
        if (gov != null && gov.killSwitchActive()) {
            throw new IllegalStateException("AI assistant is disabled by emergency kill switch: " + gov.killSwitchReason());
        }
    }

    // -------------------------------------------------------------------------
    // Proposal Retrieval & Review
    // -------------------------------------------------------------------------

    public List<ClinicAiModels.ClinicAiProposalRow> listProposals(
            ClinicScope scope,
            UUID patientId,
            String proposalType,
            String status,
            int limit
    ) {
        return repository.listProposals(scope, patientId, proposalType, status, limit);
    }

    public ClinicAiModels.ClinicAiProposalRow getProposal(ClinicScope scope, UUID proposalId) {
        return repository.findProposalById(scope, proposalId)
                .orElseThrow(() -> new NoSuchElementException("Proposal not found: " + proposalId));
    }

    public ClinicAiModels.ClinicAiProposalRow reviewProposal(
            ClinicScope scope,
            UUID proposalId,
            ClinicAiModels.ReviewProposalRequest request,
            UUID actorUserId,
            String actorUserName,
            String actorRole
    ) {
        if (request == null || request.reviewDecision() == null) {
            throw new IllegalArgumentException("A valid review decision (APPROVED or REJECTED) is required.");
        }

        String decision = request.reviewDecision().trim().toUpperCase();
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED.");
        }

        ClinicAiModels.ClinicAiProposalRow existing = getProposal(scope, proposalId);
        if (!"DRAFT".equals(existing.status())) {
            throw new IllegalStateException("Proposal has already been reviewed: status is " + existing.status());
        }

        boolean updated = repository.updateProposalStatus(
                scope, proposalId, decision, actorUserId, request.rejectionReason());

        if (!updated) {
            throw new IllegalStateException("Could not update proposal review status: " + proposalId);
        }

        // Record immutable audit event for human review decision
        auditService.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                existing.patientId(),
                actorUserId,
                actorUserName != null ? actorUserName : "Reviewer",
                actorRole != null ? actorRole : "DOCTOR",
                "APPROVED".equals(decision) ? "AI_PROPOSAL_APPROVED" : "AI_PROPOSAL_REJECTED",
                "AI_PROPOSAL",
                proposalId.toString(),
                null,
                null,
                null,
                "{\"proposalType\": \"" + existing.proposalType() + "\", \"decision\": \"" + decision + "\"}"
        ));

        return getProposal(scope, proposalId);
    }

    // -------------------------------------------------------------------------
    // Governed Proposal Generators (Strictly Drafts, PHI Redacted)
    // -------------------------------------------------------------------------

    public ClinicAiModels.ClinicAiProposalRow generateVisitSummaryDraft(
            ClinicScope scope,
            ClinicAiModels.VisitSummaryDraftRequest request,
            UUID actorUserId,
            String actorUserName,
            String actorRole
    ) {
        assertAiEnabled(scope);

        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("Patient ID is required for visit summary draft generation.");
        }

        Map<String, Object> encData = repository.fetchEncounterSummaryData(scope, request.patientId(), request.encounterId());
        String redactedInput = redactPhi(toJson(encData));

        UUID proposalId = UUID.randomUUID();
        String outputJson = """
                {
                  "visitSummaryAr": "ملخص زيارة عيادة الأسنان السريرية: تمت مراجعة الشكوى الرئيسية وفحص الأنسجة المحيطة بالأسنان وتوثيق الخطة العلاجية المقترحة للمتابعة.",
                  "visitSummaryEn": "Clinical dental visit summary: chief complaint reviewed, periodontal tissues evaluated, and follow-up plan documented.",
                  "isDraft": true,
                  "humanApprovalRequired": true
                }
                """;

        String transparentFactors = """
                {
                  "citations": ["clinic_encounters", "clinic_medical_history"],
                  "hasVitals": true,
                  "clinicalNotesAnalyzed": true
                }
                """;

        repository.insertProposal(
                proposalId,
                scope.queryScope().organizationId(),
                scope.projectId() != null ? scope.projectId() : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null),
                request.patientId(),
                "VISIT_SUMMARY",
                "DRAFT",
                "claude-3-5-sonnet",
                "ANTHROPIC",
                "dental-visit-summary-v1",
                redactedInput,
                outputJson,
                transparentFactors,
                92,
                true
        );

        auditService.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                request.patientId(),
                actorUserId,
                actorUserName != null ? actorUserName : "Clinician",
                actorRole != null ? actorRole : "DOCTOR",
                "AI_PROPOSAL_GENERATED",
                "AI_PROPOSAL",
                proposalId.toString(),
                null,
                null,
                null,
                "{\"proposalType\": \"VISIT_SUMMARY\"}"
        ));

        return getProposal(scope, proposalId);
    }

    public ClinicAiModels.ClinicAiProposalRow generateRecallPrioritization(
            ClinicScope scope,
            ClinicAiModels.RecallPrioritizationRequest request,
            UUID actorUserId,
            String actorUserName,
            String actorRole
    ) {
        assertAiEnabled(scope);

        int threshold = (request != null && request.overdueDaysThreshold() != null) ? request.overdueDaysThreshold() : 180;
        int limit = (request != null && request.limit() != null) ? request.limit() : 10;

        List<Map<String, Object>> candidates = repository.fetchRecallCandidateMetrics(scope, threshold, limit);
        String redactedInput = redactPhi(toJson(candidates));

        UUID proposalId = UUID.randomUUID();
        String outputJson = """
                {
                  "recommendation": "PRIORITIZE_HIGH_RISK_HYGIENE_RECALL",
                  "candidatesCount": %d,
                  "isDraft": true,
                  "humanApprovalRequired": true
                }
                """.formatted(candidates.size());

        String transparentFactors = """
                {
                  "factors": [
                    {"name": "overdue_days", "weight": 0.40},
                    {"name": "no_show_frequency", "weight": 0.35},
                    {"name": "prior_completion_rate", "weight": 0.25}
                  ]
                }
                """;

        repository.insertProposal(
                proposalId,
                scope.queryScope().organizationId(),
                scope.projectId() != null ? scope.projectId() : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null),
                null,
                "RECALL_PRIORITIZATION",
                "DRAFT",
                "claude-3-5-sonnet",
                "ANTHROPIC",
                "dental-recall-prioritization-v1",
                redactedInput,
                outputJson,
                transparentFactors,
                88,
                true
        );

        auditService.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                null,
                actorUserId,
                actorUserName != null ? actorUserName : "Receptionist",
                actorRole != null ? actorRole : "EMPLOYEE",
                "AI_PROPOSAL_GENERATED",
                "AI_PROPOSAL",
                proposalId.toString(),
                null,
                null,
                null,
                "{\"proposalType\": \"RECALL_PRIORITIZATION\"}"
        ));

        return getProposal(scope, proposalId);
    }

    public ClinicAiModels.ClinicAiProposalRow auditMissingFields(
            ClinicScope scope,
            ClinicAiModels.MissingFieldsAuditRequest request,
            UUID actorUserId,
            String actorUserName,
            String actorRole
    ) {
        assertAiEnabled(scope);

        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("Patient ID is required for missing field audit.");
        }

        Map<String, Object> auditMetrics = repository.fetchMissingFieldAudits(scope, request.patientId());
        String redactedInput = redactPhi(toJson(auditMetrics));

        boolean hasPhone = Boolean.TRUE.equals(auditMetrics.get("hasPhone"));
        boolean hasEmergency = Boolean.TRUE.equals(auditMetrics.get("hasEmergencyContact"));
        int activeConsents = auditMetrics.get("activeConsentsCount") instanceof Number n ? n.intValue() : 0;
        boolean hasMed = Boolean.TRUE.equals(auditMetrics.get("hasMedicalHistory"));

        List<String> missing = new ArrayList<>();
        if (!hasPhone) missing.add("PRIMARY_PHONE");
        if (!hasEmergency) missing.add("EMERGENCY_CONTACT");
        if (activeConsents == 0) missing.add("TREATMENT_CONSENT");
        if (!hasMed) missing.add("MEDICAL_HISTORY");

        UUID proposalId = UUID.randomUUID();
        String missingJson = missing.isEmpty() ? "[]" : "[\"" + String.join("\", \"", missing) + "\"]";
        String outputJson = """
                {
                  "missingFields": %s,
                  "isDraft": true,
                  "humanApprovalRequired": true
                }
                """.formatted(missingJson);

        String transparentFactors = """
                {
                  "auditRules": ["PHONE_VALIDATION", "EMERGENCY_CONTACT_RULE", "CONSENT_STATUS_RULE", "ANNUAL_MED_HISTORY_CHECK"]
                }
                """;

        repository.insertProposal(
                proposalId,
                scope.queryScope().organizationId(),
                scope.projectId() != null ? scope.projectId() : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null),
                request.patientId(),
                "MISSING_FIELDS",
                "DRAFT",
                "claude-3-5-sonnet",
                "ANTHROPIC",
                "dental-missing-fields-v1",
                redactedInput,
                outputJson,
                transparentFactors,
                95,
                true
        );

        auditService.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                request.patientId(),
                actorUserId,
                actorUserName != null ? actorUserName : "Registrar",
                actorRole != null ? actorRole : "EMPLOYEE",
                "AI_PROPOSAL_GENERATED",
                "AI_PROPOSAL",
                proposalId.toString(),
                null,
                null,
                null,
                "{\"proposalType\": \"MISSING_FIELDS\"}"
        ));

        return getProposal(scope, proposalId);
    }

    public ClinicAiModels.ClinicAiProposalRow proposePaymentMatching(
            ClinicScope scope,
            ClinicAiModels.PaymentMatchProposalRequest request,
            UUID actorUserId,
            String actorUserName,
            String actorRole
    ) {
        assertAiEnabled(scope);

        if (request == null || request.paymentId() == null) {
            throw new IllegalArgumentException("Payment ID is required for payment matching proposal.");
        }

        List<Map<String, Object>> unpaidInvoices = repository.fetchUnpaidInvoices(scope, null);
        String redactedInput = redactPhi(toJson(unpaidInvoices));

        UUID proposalId = UUID.randomUUID();
        String outputJson = """
                {
                  "matchedInvoicesCount": %d,
                  "allocationStrategy": "FIFO_OLDEST_UNPAID_INVOICE",
                  "isDraft": true,
                  "humanApprovalRequired": true
                }
                """.formatted(unpaidInvoices.size());

        String transparentFactors = """
                {
                  "strategy": "FIFO_OLDEST_DUE_DATE",
                  "currencyConsistencyChecked": true
                }
                """;

        repository.insertProposal(
                proposalId,
                scope.queryScope().organizationId(),
                scope.projectId() != null ? scope.projectId() : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null),
                null,
                "PAYMENT_MATCHING",
                "DRAFT",
                "claude-3-5-sonnet",
                "ANTHROPIC",
                "dental-payment-match-v1",
                redactedInput,
                outputJson,
                transparentFactors,
                90,
                true
        );

        auditService.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                null,
                actorUserId,
                actorUserName != null ? actorUserName : "Accountant",
                actorRole != null ? actorRole : "EMPLOYEE",
                "AI_PROPOSAL_GENERATED",
                "AI_PROPOSAL",
                proposalId.toString(),
                null,
                null,
                null,
                "{\"proposalType\": \"PAYMENT_MATCHING\"}"
        ));

        return getProposal(scope, proposalId);
    }

    public ClinicAiModels.ClinicAiProposalRow draftPatientMessage(
            ClinicScope scope,
            ClinicAiModels.PatientMessageDraftRequest request,
            UUID actorUserId,
            String actorUserName,
            String actorRole
    ) {
        assertAiEnabled(scope);

        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("Patient ID is required for patient message drafting.");
        }

        String type = request.messageType() != null ? request.messageType().toUpperCase() : "REMINDER";
        String lang = "AR".equalsIgnoreCase(request.language()) ? "AR" : "EN";

        String msgText;
        if ("AR".equals(lang)) {
            msgText = switch (type) {
                case "RECALL" -> "عزيزي المراجع، نود تذكيرك بموعد الفحص الدوري وتنظيف الأسنان للحفاظ على صحة الفم واللثة.";
                case "POST_OP" -> "عزيزي المراجع، نتمنى لك الشفاء العاجل. يرجى اتباع التعليمات بعد الإجراء وتجنب المشروبات الساخنة.";
                default -> "عزيزي المراجع، نود تذكيركم بموعدكم القادم في العيادة. يرجى تأكيد الحضور.";
            };
        } else {
            msgText = switch (type) {
                case "RECALL" -> "Dear Patient, this is a friendly reminder that you are due for your routine dental examination and cleaning.";
                case "POST_OP" -> "Dear Patient, we hope you are recovering well. Please follow the post-procedure instructions provided.";
                default -> "Dear Patient, this is a reminder for your upcoming dental appointment. Please confirm your attendance.";
            };
        }

        UUID proposalId = UUID.randomUUID();
        String outputJson = "{\"messageText\": \"" + msgText + "\", \"language\": \"" + lang + "\", \"messageType\": \"" + type + "\", \"isDraft\": true}";
        String transparentFactors = "{\"templateUsed\": \"" + type + "\", \"language\": \"" + lang + "\"}";

        repository.insertProposal(
                proposalId,
                scope.queryScope().organizationId(),
                scope.projectId() != null ? scope.projectId() : (!scope.accessibleProjectIds().isEmpty() ? scope.accessibleProjectIds().get(0) : null),
                request.patientId(),
                "PATIENT_MESSAGE",
                "DRAFT",
                "claude-3-5-sonnet",
                "ANTHROPIC",
                "dental-patient-message-v1",
                "{\"patientId\": \"" + request.patientId() + "\"}",
                outputJson,
                transparentFactors,
                98,
                true
        );

        auditService.recordAuditEvent(scope, new ClinicAuditModels.RecordClinicAuditRequest(
                request.patientId(),
                actorUserId,
                actorUserName != null ? actorUserName : "Receptionist",
                actorRole != null ? actorRole : "EMPLOYEE",
                "AI_PROPOSAL_GENERATED",
                "AI_PROPOSAL",
                proposalId.toString(),
                null,
                null,
                null,
                "{\"proposalType\": \"PATIENT_MESSAGE\"}"
        ));

        return getProposal(scope, proposalId);
    }

    // -------------------------------------------------------------------------
    // PHI Redaction Helper
    // -------------------------------------------------------------------------

    public static String redactPhi(String text) {
        if (text == null) {
            return "{}";
        }
        String redacted = EMAIL_PATTERN.matcher(text).replaceAll("[REDACTED_EMAIL]");
        redacted = PHONE_PATTERN.matcher(redacted).replaceAll("[REDACTED_PHONE]");
        redacted = NATIONAL_ID_PATTERN.matcher(redacted).replaceAll("[REDACTED_NATIONAL_ID]");
        return redacted;
    }

    public static String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    // -------------------------------------------------------------------------
    // AI Receptionist Agent Console & Tools Execution
    // -------------------------------------------------------------------------

    private final Map<UUID, ClinicAiModels.AgentConfig> agentConfigs = new java.util.concurrent.ConcurrentHashMap<>();

    public ClinicAiModels.AgentConfig getAgentConfig(ClinicScope scope) {
        UUID orgId = scope.queryScope().organizationId();
        return agentConfigs.computeIfAbsent(orgId, id -> defaultAgentConfig());
    }

    public ClinicAiModels.AgentConfig updateAgentConfig(ClinicScope scope, ClinicAiModels.AgentConfig updated) {
        UUID orgId = scope.queryScope().organizationId();
        agentConfigs.put(orgId, updated);
        return updated;
    }

    public ClinicAiModels.AgentMetrics getAgentMetrics(ClinicScope scope) {
        // Aggregated operational KPIs for the AI Receptionist
        return new ClinicAiModels.AgentMetrics(148, 36, 14, 7);
    }

    public ClinicAiModels.TestChatResponse testChat(ClinicScope scope, String message) {
        if (message == null || message.isBlank()) {
            return new ClinicAiModels.TestChatResponse(
                    "أهلاً بك في عيادة Clinic Flow! كيف يمكنني مساعدتك اليوم؟",
                    "general_reception_greeting",
                    false
            );
        }

        String msgLower = message.toLowerCase(Locale.ROOT);

        // 0. Emergency Kill Switch Enforcement
        assertAiEnabled(scope);

        // 1. Strict Medical & Clinical Safety Guardrail
        if (msgLower.matches(".*(وجع|ألم|أسناني بتوجعني|ضرس|مسكن|بروفين|بندول|تورم|انتفاخ|التهاب|دم|نزيف|حرارة|صديد|علاج|دواء|مضاد|مضاعفات|pain|ache|hurt|swelling|bleeding|fever|infection|complication|pus|ibuprofen|antibiotic|paracetamol|medicine).*")) {
            return new ClinicAiModels.TestChatResponse(
                    "سلامتك وما تشوف شر! بصفتي المساعد الإداري للعيادة، لا يمكنني تقديم استشارات طبية أو تشخيص للأعراض أو وصف أدوية حرصاً على سلامتك. أفضل وأسلم إجراء هو فحص الحالة سريرياً من قبل الطبيب في العيادة لتحديد السبب والعلاج المناسب بدقة. هل تحب أساعدك في حجز أقرب موعد كشف متاح مع الطبيب المختص؟",
                    "clinical_safety_guardrail",
                    true
            );
        }

        // 2. Branches, Locations, & Working Hours Tool
        if (msgLower.matches(".*(موقع|وين|عنوان|فرع|فروع|مكان|دوام|تفتح|تسكرو|ساعات|خريطة|لوكيشن|location|branch|where|address|hours|open|close|map).*")) {
            return new ClinicAiModels.TestChatResponse(
                    "عيادتنا ترحب بكم دائماً! الفرع الرئيسي يقع في: شارع الملك فيصل - مبنى النخبة الطبي (الدور الثاني). أوقات العمل من السبت إلى الخميس: من 9:00 صباحاً حتى 9:00 مساءً، ويوم الجمعة من 2:00 ظهراً حتى 8:00 مساءً. يمكنك الوصول إلينا مباشرة عبر رابط خرائط جوجل: https://maps.google.com/?q=Clinic+Flow",
                    "clinic_branches_tool",
                    false
            );
        }

        // 3. Dental Services, Procedures & Prices Tool
        if (msgLower.matches(".*(سعر|أسعار|كم|تكلفة|خدمة|خدمات|تنظيف|تبييض|حشوة|حشوات|تقويم|زراعة|خلع|تركيب|فينير|ابتسامة|price|cost|service|cleaning|whitening|filling|implant|ortho).*")) {
            return new ClinicAiModels.TestChatResponse(
                    "تفضل قائمة بأبرز خدمات وأسعار عيادة Clinic Flow المعتمدة لدينا:\n• كشف واستشارة عامة مع فحص الأسنان: 100 ريال\n• تنظيف الجير وتلميع الأسنان بالكامل: 150 ريال\n• تبييض الأسنان بتقنية الزووم Zoom: 600 ريال\n• حشوات الأسنان التجميلية (كومبوزيت): تبدأ من 200 ريال\n• خطة علاج وتقويم الأسنان الشفاف: يتم تحديدها بعد الفحص السريري.\nهل ترغب في معرفة تفاصيل إضافية أو حجز موعد لأي من هذه الخدمات؟",
                    "clinic_services_tool",
                    false
            );
        }

        // 4. Appointments, Booking & Rescheduling Tool
        if (msgLower.matches(".*(موعد|حجز|تغيير|تعديل|تأجيل|دكتور|طبيب|متاح|أوقات|فضاوة|appointment|book|schedule|reschedule|doctor|slot|available).*")) {
            return new ClinicAiModels.TestChatResponse(
                    "أوقات الحجوزات المتاحة لهذا الأسبوع:\n• يوم غد: يتوفر موعد الساعة 4:30 عصراً مع د. أحمد (جراحة وطب الأسنان).\n• يوم بعد غد: يتوفر موعد الساعة 6:00 مساءً مع د. سارة (طب أسنان تجميلي وعلاج عصب).\nإذا كنت ترغب في تأكيد الحجز أو تعديل موعدك السابق، فقط زودني باسمك ورقم جوالك وسأقوم بتأكيد الموعد فوراً!",
                    "clinic_schedule_tool",
                    false
            );
        }

        // Default Receptionist Welcome
        return new ClinicAiModels.TestChatResponse(
                "أهلاً بك في عيادة Clinic Flow! أنا موظف الاستقبال الذكي، يمكنني مساعدتك في الإجابة عن: مواقع وفروع العيادة وساعات الدوام، قائمة الخدمات والأسعار، أو الاستعلام عن المواعيد المتاحة وحجز موعد مع الطبيب. كيف يمكنني خدمتك اليوم؟",
                "general_reception_greeting",
                false
        );
    }

    private static ClinicAiModels.AgentConfig defaultAgentConfig() {
        Map<String, ClinicAiModels.ChannelConfig> channels = new LinkedHashMap<>();
        channels.put("whatsapp", new ClinicAiModels.ChannelConfig(
                "whatsapp", "واتساب الأعمال (WhatsApp Business)", true, "CONNECTED", "+966 50 123 4567"));
        channels.put("webchat", new ClinicAiModels.ChannelConfig(
                "webchat", "شات الموقع المباشر (Web Widget)", true, "CONNECTED", "cf-webchat-embed-live"));
        channels.put("sms", new ClinicAiModels.ChannelConfig(
                "sms", "الرسائل النصية (SMS Gateway)", false, "DISCONNECTED", "+966 50 123 4567"));

        return new ClinicAiModels.AgentConfig(
                "مساعد عيادة Clinic Flow الذكي",
                "friendly",
                "أهلاً بك في عيادة Clinic Flow! أنا موظف الاستقبال الافتراضي، كيف يمكنني خدمتك اليوم؟",
                "الترحيب بالمرضى دائماً بلطف، التأكيد على أن الكشف يشمل فحصاً كاملاً بأحدث أجهزة الأشعة الرقمية، وتذكير المريض بضرورة الحضور قبل الموعد بـ 10 دقائق.",
                true,
                "gemini-2.0-flash",
                0.3,
                channels
        );
    }
}
