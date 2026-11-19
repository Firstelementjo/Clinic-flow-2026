package com.firstelement.crm.clinic.operations;

import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.error.ResourceNotFoundException;
import com.firstelement.crm.error.ValidationFailedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Persistence operations for External Dental Imaging Labs & Imaging Requests (Req 5). */
final class ImagingOperationsStore {

    private static final Set<String> IMAGING_TYPES = Set.of(
            "CBCT", "PANORAMIC", "PERIAPICAL", "BITEWING", "CEPHALOMETRIC", "OTHER");
    private static final Set<String> REQUEST_STATUSES = Set.of(
            "REQUESTED", "SENT_TO_LAB", "COMPLETED", "CANCELLED");
    private static final Set<String> INTEGRATION_MODES = Set.of("MANUAL", "DIRECT_CLOUD_API");

    private final NamedParameterJdbcTemplate jdbc;

    ImagingOperationsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ClinicOperationsModels.ImagingLabRow> listLabs(ClinicScope scope, UUID clinicId) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select l.id, l."organizationId", l."projectId", l."clinicId",
                           coalesce(cl."name", 'All Branches') as "clinicName",
                           l."name", l."contactPhone", l."contactEmail", l."address",
                           l."integrationMode", l."apiEndpointUrl", l."notes", l."isActive"
                      from clinic_imaging_labs l
                      left join clinic_locations cl on cl.id = l."clinicId" and cl."organizationId" = l."organizationId"
                     where %s
                    """.formatted(scope.predicate("l")));
            MapSqlParameterSource params = scope.bind();
            if (clinicId != null) {
                sql.append(" and (l.\"clinicId\" = :clinicId or l.\"clinicId\" is null)");
                params.addValue("clinicId", clinicId);
            }
            sql.append(" order by l.\"isActive\" desc, l.\"name\" asc");
            return jdbc.query(sql.toString(), params, ImagingOperationsStore::mapLab);
        });
    }

    ClinicOperationsModels.ImagingLabRow createLab(
            ClinicScope scope, ClinicOperationsModels.CreateImagingLabRequest req) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("An imaging lab must belong to one branch project.");
        }
        String mode = (req.integrationMode() != null && !req.integrationMode().isBlank())
                ? req.integrationMode().trim().toUpperCase() : "MANUAL";
        if (!INTEGRATION_MODES.contains(mode)) {
            throw new ValidationFailedException("integrationMode must be MANUAL or DIRECT_CLOUD_API.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_imaging_labs
                        (id, "organizationId", "projectId", "clinicId", "name", "contactPhone",
                         "contactEmail", "address", "integrationMode", "apiEndpointUrl", "notes", "isActive")
                    values
                        (:id, :orgId, :projId, :clinicId, :name, :phone,
                         :email, :address, :mode, :apiUrl, :notes, true)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", scope.queryScope().organizationId())
                            .addValue("projId", scope.projectId())
                            .addValue("clinicId", req.clinicId())
                            .addValue("name", req.name().trim())
                            .addValue("phone", trimToNull(req.contactPhone()))
                            .addValue("email", trimToNull(req.contactEmail()))
                            .addValue("address", trimToNull(req.address()))
                            .addValue("mode", mode)
                            .addValue("apiUrl", trimToNull(req.apiEndpointUrl()))
                            .addValue("notes", trimToNull(req.notes())));
            return findLab(scope, id);
        });
    }

    ClinicOperationsModels.ImagingLabRow updateLab(
            ClinicScope scope, UUID labId, ClinicOperationsModels.CreateImagingLabRequest req) {
        return scope.read(() -> {
            ClinicOperationsModels.ImagingLabRow existing = findLab(scope, labId);
            String name = (req.name() != null && !req.name().isBlank()) ? req.name().trim() : existing.name();
            String mode = (req.integrationMode() != null && !req.integrationMode().isBlank())
                    ? req.integrationMode().trim().toUpperCase() : existing.integrationMode();
            if (!INTEGRATION_MODES.contains(mode)) {
                throw new ValidationFailedException("integrationMode must be MANUAL or DIRECT_CLOUD_API.");
            }
            jdbc.update("""
                    update clinic_imaging_labs l
                       set "clinicId" = :clinicId,
                           "name" = :name,
                           "contactPhone" = :phone,
                           "contactEmail" = :email,
                           "address" = :address,
                           "integrationMode" = :mode,
                           "apiEndpointUrl" = :apiUrl,
                           "notes" = :notes,
                           "updatedAt" = (now() at time zone 'utc')
                     where %s and l.id = :id
                    """.formatted(scope.predicate("l")),
                    scope.bind()
                            .addValue("id", labId)
                            .addValue("clinicId", req.clinicId() != null ? req.clinicId() : existing.clinicId())
                            .addValue("name", name)
                            .addValue("phone", req.contactPhone() != null ? trimToNull(req.contactPhone()) : existing.contactPhone())
                            .addValue("email", req.contactEmail() != null ? trimToNull(req.contactEmail()) : existing.contactEmail())
                            .addValue("address", req.address() != null ? trimToNull(req.address()) : existing.address())
                            .addValue("mode", mode)
                            .addValue("apiUrl", req.apiEndpointUrl() != null ? trimToNull(req.apiEndpointUrl()) : existing.apiEndpointUrl())
                            .addValue("notes", req.notes() != null ? trimToNull(req.notes()) : existing.notes()));
            return findLab(scope, labId);
        });
    }

    boolean deleteLab(ClinicScope scope, UUID labId) {
        return scope.read(() -> jdbc.update("""
                delete from clinic_imaging_labs l
                 where %s and l.id = :id
                """.formatted(scope.predicate("l")),
                scope.bind().addValue("id", labId)) > 0);
    }

    List<ClinicOperationsModels.ImagingRequestRow> listRequests(
            ClinicScope scope, UUID patientId, String status, String imagingType) {
        return scope.read(() -> {
            StringBuilder sql = new StringBuilder("""
                    select ir.id, ir."organizationId", ir."projectId", ir."clinicId",
                           coalesce(cl."name", 'Main Branch') as "clinicName",
                           ir."patientId", coalesce(p."displayName", 'Patient') as "patientName",
                           ir."doctorId", coalesce(d."name", 'Clinician') as "doctorName",
                           ir."labId", coalesce(ir."labName", lb."name", '') as "labName",
                           ir."imagingType", ir."proceduresCount", ir."toothFdi", ir."teethSummary",
                           ir."clinicalIndication", ir."status", ir."requestDate", ir."completionDate",
                           ir."costToClinic", ir."amountChargedToPatient", ir."invoiceId", ir."expenseId",
                           ir."cloudStorageUrl", ir."relatedFiles", ir."findingsNotes", ir."createdAt"
                      from clinic_imaging_requests ir
                      left join clinic_locations cl on cl.id = ir."clinicId" and cl."organizationId" = ir."organizationId"
                      left join patients p on p.id = ir."patientId" and p."organizationId" = ir."organizationId"
                      left join doctors d on d.id = ir."doctorId" and d."organizationId" = ir."organizationId"
                      left join clinic_imaging_labs lb on lb.id = ir."labId" and lb."organizationId" = ir."organizationId"
                     where %s
                    """.formatted(scope.predicate("ir")));
            MapSqlParameterSource params = scope.bind();
            if (patientId != null) {
                sql.append(" and ir.\"patientId\" = :patientId");
                params.addValue("patientId", patientId);
            }
            if (status != null && !status.isBlank()) {
                sql.append(" and ir.\"status\" = :status");
                params.addValue("status", status.trim().toUpperCase());
            }
            if (imagingType != null && !imagingType.isBlank()) {
                sql.append(" and ir.\"imagingType\" = :imagingType");
                params.addValue("imagingType", imagingType.trim().toUpperCase());
            }
            sql.append(" order by ir.\"requestDate\" desc, ir.\"createdAt\" desc limit 300");
            return jdbc.query(sql.toString(), params, ImagingOperationsStore::mapRequest);
        });
    }

    ClinicOperationsModels.ImagingSummaryAnalytics getAnalytics(ClinicScope scope) {
        return scope.read(() -> jdbc.queryForObject("""
                select count(*) as "totalRequests",
                       coalesce(sum(ir."proceduresCount"), 0) as "totalProceduresCount",
                       coalesce(sum(ir."costToClinic"), 0.000) as "totalCost",
                       coalesce(sum(ir."amountChargedToPatient"), 0.000) as "totalCharged"
                  from clinic_imaging_requests ir
                 where %s and ir."status" != 'CANCELLED'
                """.formatted(scope.predicate("ir")),
                scope.bind(),
                (rs, n) -> {
                    BigDecimal cost = scaled(rs.getBigDecimal("totalCost"));
                    BigDecimal charged = scaled(rs.getBigDecimal("totalCharged"));
                    return new ClinicOperationsModels.ImagingSummaryAnalytics(
                            rs.getInt("totalRequests"),
                            rs.getInt("totalProceduresCount"),
                            cost,
                            charged,
                            charged.subtract(cost));
                }));
    }

    ClinicOperationsModels.ImagingRequestRow createRequest(
            ClinicScope scope, ClinicOperationsModels.CreateImagingRequest req, UUID callerUserId) {
        if (!scope.isProjectScoped()) {
            throw new ValidationFailedException("An imaging request must belong to one branch project.");
        }
        String type = normalizeImagingType(req.imagingType());
        String status = (req.status() != null && !req.status().isBlank())
                ? req.status().trim().toUpperCase() : "REQUESTED";
        if (!REQUEST_STATUSES.contains(status)) {
            throw new ValidationFailedException("Invalid imaging request status.");
        }
        int procCount = req.proceduresCount() != null ? Math.max(1, req.proceduresCount()) : 1;
        LocalDate reqDate = req.requestDate() != null ? req.requestDate() : LocalDate.now();
        LocalDate compDate = "COMPLETED".equals(status)
                ? (req.completionDate() != null ? req.completionDate() : LocalDate.now())
                : req.completionDate();
        BigDecimal cost = req.costToClinic() != null ? scaled(req.costToClinic()) : BigDecimal.ZERO.setScale(3);
        BigDecimal charge = req.amountChargedToPatient() != null
                ? scaled(req.amountChargedToPatient()) : BigDecimal.ZERO.setScale(3);

        return scope.read(() -> {
            String resolvedLabName = trimToNull(req.labName());
            if (resolvedLabName == null && req.labId() != null) {
                resolvedLabName = findLab(scope, req.labId()).name();
            }

            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_imaging_requests
                        (id, "organizationId", "projectId", "clinicId", "patientId", "doctorId",
                         "labId", "labName", "imagingType", "proceduresCount", "toothFdi",
                         "teethSummary", "clinicalIndication", "status", "requestDate", "completionDate",
                         "costToClinic", "amountChargedToPatient", "cloudStorageUrl", "relatedFiles", "findingsNotes")
                    values
                        (:id, :orgId, :projId, :clinicId, :patientId, :doctorId,
                         :labId, :labName, :imagingType, :proceduresCount, :toothFdi,
                         :teethSummary, :indication, :status, :requestDate, :completionDate,
                         :cost, :charge, :cloudUrl, :files, :findings)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("orgId", scope.queryScope().organizationId())
                            .addValue("projId", scope.projectId())
                            .addValue("clinicId", req.clinicId())
                            .addValue("patientId", req.patientId())
                            .addValue("doctorId", req.doctorId())
                            .addValue("labId", req.labId())
                            .addValue("labName", resolvedLabName)
                            .addValue("imagingType", type)
                            .addValue("proceduresCount", procCount)
                            .addValue("toothFdi", req.toothFdi())
                            .addValue("teethSummary", trimToNull(req.teethSummary()))
                            .addValue("indication", trimToNull(req.clinicalIndication()))
                            .addValue("status", status)
                            .addValue("requestDate", java.sql.Date.valueOf(reqDate))
                            .addValue("completionDate", compDate != null ? java.sql.Date.valueOf(compDate) : null)
                            .addValue("cost", cost)
                            .addValue("charge", charge)
                            .addValue("cloudUrl", trimToNull(req.cloudStorageUrl()))
                            .addValue("files", req.relatedFiles() != null ? req.relatedFiles().toArray(String[]::new) : null)
                            .addValue("findings", trimToNull(req.findingsNotes())));

            if ("COMPLETED".equals(status)) {
                ensureImagingFinancialLinks(scope, id, callerUserId);
            }
            return findRequest(scope, id);
        });
    }

    ClinicOperationsModels.ImagingRequestRow updateRequest(
            ClinicScope scope, UUID requestId, ClinicOperationsModels.UpdateImagingRequest req, UUID callerUserId) {
        return scope.read(() -> {
            ClinicOperationsModels.ImagingRequestRow existing = findRequest(scope, requestId);
            String status = (req.status() != null && !req.status().isBlank())
                    ? req.status().trim().toUpperCase() : existing.status();
            if (!REQUEST_STATUSES.contains(status)) {
                throw new ValidationFailedException("Invalid imaging request status.");
            }
            LocalDate compDate = req.completionDate() != null
                    ? req.completionDate()
                    : ("COMPLETED".equals(status) && existing.completionDate() == null
                            ? LocalDate.now() : existing.completionDate());
            BigDecimal cost = req.costToClinic() != null ? scaled(req.costToClinic()) : existing.costToClinic();
            BigDecimal charge = req.amountChargedToPatient() != null
                    ? scaled(req.amountChargedToPatient()) : existing.amountChargedToPatient();
            UUID labId = req.labId() != null ? req.labId() : existing.labId();
            String labName = req.labName() != null ? trimToNull(req.labName()) : existing.labName();
            if (labName == null && labId != null) {
                labName = findLab(scope, labId).name();
            }

            jdbc.update("""
                    update clinic_imaging_requests ir
                       set "status" = :status,
                           "completionDate" = :compDate,
                           "costToClinic" = :cost,
                           "amountChargedToPatient" = :charge,
                           "cloudStorageUrl" = :cloudUrl,
                           "relatedFiles" = :files,
                           "findingsNotes" = :findings,
                           "labId" = :labId,
                           "labName" = :labName,
                           "updatedAt" = (now() at time zone 'utc')
                     where %s and ir.id = :id
                    """.formatted(scope.predicate("ir")),
                    scope.bind()
                            .addValue("id", requestId)
                            .addValue("status", status)
                            .addValue("compDate", compDate != null ? java.sql.Date.valueOf(compDate) : null)
                            .addValue("cost", cost)
                            .addValue("charge", charge)
                            .addValue("cloudUrl", req.cloudStorageUrl() != null ? trimToNull(req.cloudStorageUrl()) : existing.cloudStorageUrl())
                            .addValue("files", req.relatedFiles() != null
                                    ? req.relatedFiles().toArray(String[]::new)
                                    : (existing.relatedFiles() != null ? existing.relatedFiles().toArray(String[]::new) : null))
                            .addValue("findings", req.findingsNotes() != null ? trimToNull(req.findingsNotes()) : existing.findingsNotes())
                            .addValue("labId", labId)
                            .addValue("labName", labName));

            if ("COMPLETED".equals(status)) {
                ensureImagingFinancialLinks(scope, requestId, callerUserId);
            }
            return findRequest(scope, requestId);
        });
    }

    boolean deleteRequest(ClinicScope scope, UUID requestId) {
        return scope.read(() -> jdbc.update("""
                delete from clinic_imaging_requests ir
                 where %s and ir.id = :id
                """.formatted(scope.predicate("ir")),
                scope.bind().addValue("id", requestId)) > 0);
    }

    private void ensureImagingFinancialLinks(ClinicScope scope, UUID requestId, UUID callerUserId) {
        ClinicOperationsModels.ImagingRequestRow row = findRequest(scope, requestId);

        // 1. Auto-record clinic lab cost into operational expenses (zero duplicate entry)
        if (row.costToClinic().compareTo(BigDecimal.ZERO) > 0 && row.expenseId() == null) {
            UUID expenseId = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_expenses
                        (id, "organizationId", "projectId", "clinicId", "category", "title", "amount",
                         "currency", "expenseFrequency", "paymentDate", "referenceType", "referenceId",
                         "notes", "createdByUserId")
                    values
                        (:id, :orgId, :projId, :clinicId, 'LAB_FEES', :title, :amount,
                         'JOD', 'ONE_TIME', :paymentDate, 'IMAGING_REQUEST', :refId,
                         :notes, :userId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", expenseId)
                            .addValue("orgId", row.organizationId())
                            .addValue("projId", row.projectId())
                            .addValue("clinicId", row.clinicId())
                            .addValue("title", "Dental Imaging (" + row.imagingType() + ") - " + row.patientName())
                            .addValue("amount", row.costToClinic())
                            .addValue("paymentDate", java.sql.Date.valueOf(
                                    row.completionDate() != null ? row.completionDate() : LocalDate.now()))
                            .addValue("refId", requestId)
                            .addValue("notes", row.labName() != null ? "External Lab: " + row.labName() : null)
                            .addValue("userId", callerUserId));

            jdbc.update("""
                    update clinic_imaging_requests set "expenseId" = :expId
                     where "organizationId" = :orgId and id = :id
                    """,
                    new MapSqlParameterSource()
                            .addValue("expId", expenseId)
                            .addValue("orgId", row.organizationId())
                            .addValue("id", requestId));
        }

        // 2. Auto-generate invoice item for amount charged to patient
        if (row.amountChargedToPatient().compareTo(BigDecimal.ZERO) > 0 && row.invoiceId() == null) {
            Long nextVal = jdbc.queryForObject("""
                    insert into clinic_invoice_sequences ("organizationId", "sequenceType", "currentValue")
                    values (:orgId, 'INVOICE', 1)
                    on conflict ("organizationId", "sequenceType")
                    do update set "currentValue" = clinic_invoice_sequences."currentValue" + 1
                    returning "currentValue"
                    """,
                    new MapSqlParameterSource().addValue("orgId", row.organizationId()),
                    Long.class);
            long seq = nextVal != null ? nextVal : 1L;
            String invoiceNumber = String.format("INV-%d-%04d", LocalDate.now().getYear(), seq);

            UUID invoiceId = UUID.randomUUID();
            jdbc.update("""
                    insert into clinic_invoices
                        (id, "organizationId", "projectId", "patientId", "invoiceNumber", "currency",
                         "subtotal", "discountAmount", "totalAmount", "paidAmount", "balanceDue", "status", "notes")
                    values
                        (:id, :orgId, :projId, :patientId, :invNum, 'JOD',
                         :total, 0.000, :total, 0.000, :total, 'ISSUED', :notes)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", invoiceId)
                            .addValue("orgId", row.organizationId())
                            .addValue("projId", row.projectId())
                            .addValue("patientId", row.patientId())
                            .addValue("invNum", invoiceNumber)
                            .addValue("total", row.amountChargedToPatient())
                            .addValue("notes", "Auto-generated from Dental Imaging Request (" + row.imagingType() + ")"));

            jdbc.update("""
                    insert into clinic_invoice_items
                        (id, "organizationId", "projectId", "invoiceId", "itemType", "description",
                         "toothFdi", "unitPrice", "quantity", "totalPrice")
                    values
                        (:id, :orgId, :projId, :invoiceId, 'MANUAL', :desc,
                         :toothFdi, :unitPrice, :qty, :totalPrice)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("orgId", row.organizationId())
                            .addValue("projId", row.projectId())
                            .addValue("invoiceId", invoiceId)
                            .addValue("desc", "Dental Imaging: " + row.imagingType() + " (" + row.proceduresCount() + "x)")
                            .addValue("toothFdi", row.toothFdi())
                            .addValue("unitPrice", row.amountChargedToPatient())
                            .addValue("qty", 1)
                            .addValue("totalPrice", row.amountChargedToPatient()));

            jdbc.update("""
                    update clinic_imaging_requests set "invoiceId" = :invId
                     where "organizationId" = :orgId and id = :id
                    """,
                    new MapSqlParameterSource()
                            .addValue("invId", invoiceId)
                            .addValue("orgId", row.organizationId())
                            .addValue("id", requestId));
        }
    }

    private ClinicOperationsModels.ImagingLabRow findLab(ClinicScope scope, UUID id) {
        List<ClinicOperationsModels.ImagingLabRow> rows = jdbc.query("""
                select l.id, l."organizationId", l."projectId", l."clinicId",
                       coalesce(cl."name", 'All Branches') as "clinicName",
                       l."name", l."contactPhone", l."contactEmail", l."address",
                       l."integrationMode", l."apiEndpointUrl", l."notes", l."isActive"
                  from clinic_imaging_labs l
                  left join clinic_locations cl on cl.id = l."clinicId" and cl."organizationId" = l."organizationId"
                 where %s and l.id = :id
                 limit 1
                """.formatted(scope.predicate("l")),
                scope.bind().addValue("id", id),
                ImagingOperationsStore::mapLab);
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Imaging lab not found"));
    }

    private ClinicOperationsModels.ImagingRequestRow findRequest(ClinicScope scope, UUID id) {
        List<ClinicOperationsModels.ImagingRequestRow> rows = jdbc.query("""
                select ir.id, ir."organizationId", ir."projectId", ir."clinicId",
                       coalesce(cl."name", 'Main Branch') as "clinicName",
                       ir."patientId", coalesce(p."displayName", 'Patient') as "patientName",
                       ir."doctorId", coalesce(d."name", 'Clinician') as "doctorName",
                       ir."labId", coalesce(ir."labName", lb."name", '') as "labName",
                       ir."imagingType", ir."proceduresCount", ir."toothFdi", ir."teethSummary",
                       ir."clinicalIndication", ir."status", ir."requestDate", ir."completionDate",
                       ir."costToClinic", ir."amountChargedToPatient", ir."invoiceId", ir."expenseId",
                       ir."cloudStorageUrl", ir."relatedFiles", ir."findingsNotes", ir."createdAt"
                  from clinic_imaging_requests ir
                  left join clinic_locations cl on cl.id = ir."clinicId" and cl."organizationId" = ir."organizationId"
                  left join patients p on p.id = ir."patientId" and p."organizationId" = ir."organizationId"
                  left join doctors d on d.id = ir."doctorId" and d."organizationId" = ir."organizationId"
                  left join clinic_imaging_labs lb on lb.id = ir."labId" and lb."organizationId" = ir."organizationId"
                 where %s and ir.id = :id
                 limit 1
                """.formatted(scope.predicate("ir")),
                scope.bind().addValue("id", id),
                ImagingOperationsStore::mapRequest);
        return rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Imaging request not found"));
    }

    private static ClinicOperationsModels.ImagingLabRow mapLab(ResultSet rs, int n) throws SQLException {
        return new ClinicOperationsModels.ImagingLabRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("clinicId", UUID.class),
                rs.getString("clinicName"),
                rs.getString("name"),
                rs.getString("contactPhone"),
                rs.getString("contactEmail"),
                rs.getString("address"),
                rs.getString("integrationMode"),
                rs.getString("apiEndpointUrl"),
                rs.getString("notes"),
                rs.getBoolean("isActive"));
    }

    private static ClinicOperationsModels.ImagingRequestRow mapRequest(ResultSet rs, int n) throws SQLException {
        java.sql.Date rd = rs.getDate("requestDate");
        java.sql.Date cd = rs.getDate("completionDate");
        BigDecimal cost = scaled(rs.getBigDecimal("costToClinic"));
        BigDecimal charge = scaled(rs.getBigDecimal("amountChargedToPatient"));
        return new ClinicOperationsModels.ImagingRequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("clinicId", UUID.class),
                rs.getString("clinicName"),
                rs.getObject("patientId", UUID.class),
                rs.getString("patientName"),
                rs.getObject("doctorId", UUID.class),
                rs.getString("doctorName"),
                rs.getObject("labId", UUID.class),
                rs.getString("labName"),
                rs.getString("imagingType"),
                rs.getInt("proceduresCount"),
                rs.getObject("toothFdi", Integer.class),
                rs.getString("teethSummary"),
                rs.getString("clinicalIndication"),
                rs.getString("status"),
                rd != null ? rd.toLocalDate() : null,
                cd != null ? cd.toLocalDate() : null,
                cost,
                charge,
                charge.subtract(cost),
                rs.getObject("invoiceId", UUID.class),
                rs.getObject("expenseId", UUID.class),
                rs.getString("cloudStorageUrl"),
                toStringList(rs.getArray("relatedFiles")),
                rs.getString("findingsNotes"),
                toLocalDateTime(rs.getTimestamp("createdAt")));
    }

    private static String normalizeImagingType(String raw) {
        String val = (raw != null && !raw.isBlank()) ? raw.trim().toUpperCase() : "PANORAMIC";
        if (!IMAGING_TYPES.contains(val)) {
            throw new ValidationFailedException("Invalid imagingType. Allowed: " + IMAGING_TYPES);
        }
        return val;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value != null ? value.setScale(3, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(3);
    }

    private static List<String> toStringList(java.sql.Array array) {
        if (array == null) return List.of();
        try {
            String[] values = (String[]) array.getArray();
            return values != null ? List.of(values) : List.of();
        } catch (SQLException e) {
            return List.of();
        }
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
