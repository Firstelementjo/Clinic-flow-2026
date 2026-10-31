package com.firstelement.crm.clinic.odontogram;

import com.firstelement.crm.clinic.ClinicScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every odontogram statement, all of them scoped.
 *
 * <p>Follows {@code ClinicRepository}'s rule: the tenant predicate is supplied by a
 * {@link ClinicScope}, never written by the caller. There is no method here that accepts an
 * unchecked {@code organizationId}.
 *
 * <h2>There is deliberately no update and no delete</h2>
 *
 * <p>A dental record that can be edited in place has no defensible history. If a finding was
 * mis-recorded and silently overwritten, nothing records that it was ever believed â€” which is
 * exactly the case a clinical record exists to settle.
 *
 * <p>So this repository has no {@code update} and no {@code delete}, and
 * {@link OdontogramServiceIntegrationTest} asserts that reflectively. The database agrees: the
 * table is guarded by the chain's {@code firstelement_append_only()} trigger, so even a raw
 * {@code UPDATE} is refused. Two independent layers, because the trigger protects the data and
 * the absent method protects the intent.
 *
 * <p>Correction is an INSERT: a new row whose {@code supersededBy} names the row it replaces.
 */
@Repository
public class OdontogramRepository {

    private static final String COLUMNS = """
            f."id", f."patientId", f."toothFdi", f."scope", f."surfaces", f."code",
            f."attributes", f."state", f."source", f."plannedProcedureId", f."recordedBy",
            f."recordedAt", f."note", f."supersededBy"
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public OdontogramRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One patient's chart, newest observation first. */
    public List<OdontogramModels.FindingRow> chart(ClinicScope scope, UUID patientId, int limit) {
        return scope.read(() -> jdbc.query("""
                select %s
                  from "patient_odontogram_findings" f
                 where %s
                   and f."patientId" = :patientId
                 order by f."recordedAt" desc, f."id"
                 limit :limit
                """.formatted(COLUMNS, scope.predicate("f")),
                scope.bind(Map.of("patientId", patientId, "limit", bounded(limit))),
                OdontogramRepository::finding));
    }

    /** One tooth's history for one patient, oldest first, which is the order a chart reads in. */
    public List<OdontogramModels.FindingRow> toothHistory(
            ClinicScope scope, UUID patientId, int toothFdi) {
        return scope.read(() -> jdbc.query("""
                select %s
                  from "patient_odontogram_findings" f
                 where %s
                   and f."patientId" = :patientId
                   and f."toothFdi" = :toothFdi
                 order by f."recordedAt", f."id"
                """.formatted(COLUMNS, scope.predicate("f")),
                scope.bind(Map.of("patientId", patientId, "toothFdi", toothFdi)),
                OdontogramRepository::finding));
    }

    /** One finding by id, still scoped. Absent and foreign are the same answer. */
    public OdontogramModels.FindingRow find(ClinicScope scope, UUID findingId) {
        List<OdontogramModels.FindingRow> rows = scope.read(() -> jdbc.query("""
                select %s
                  from "patient_odontogram_findings" f
                 where %s
                   and f."id" = :id
                """.formatted(COLUMNS, scope.predicate("f")),
                scope.bind(Map.of("id", findingId)),
                OdontogramRepository::finding));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The clinic's finding vocabulary. Tenant-scoped, so one clinic never sees another's terms. */
    public List<OdontogramModels.VocabularyTerm> vocabulary(ClinicScope scope, int limit) {
        return scope.read(() -> jdbc.query("""
                select v."id", v."code", v."category", v."labelAr", v."labelEn",
                       v."isClinicianReviewed", v."isActive"
                  from "clinic_finding_vocabulary" v
                 where %s
                 order by v."category", v."code"
                 limit :limit
                """.formatted(scope.predicate("v")),
                scope.bind(Map.of("limit", bounded(limit))),
                OdontogramRepository::term));
    }

    /**
     * Inserts a finding.
     *
     * <p>Requires a project-scoped scope, for the same reason a patient does: a finding belongs
     * to exactly one clinic, and there is no correct {@code projectId} to write for a caller who
     * may reach several.
     */
    public UUID insert(ClinicScope scope, Map<String, Object> parameters) {
        if (!scope.isProjectScoped()) {
            // IllegalStateException, not PolicyDeniedException: a caller mistake, not an
            // authorization outcome. Rendering it 403 would tell the operator a false thing.
            throw new IllegalStateException(
                    "A finding must be recorded inside one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            UUID id = parameters.get("id") == null ? UUID.randomUUID() : (UUID) parameters.get("id");
            MapSqlParameterSource bound = scope.bind(parameters)
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId());
            jdbc.update("""
                    insert into "patient_odontogram_findings"
                        ("id", "organizationId", "projectId", "patientId", "toothFdi", "scope",
                         "surfaces", "code", "attributes", "state", "source", "plannedProcedureId",
                         "recordedBy", "recordedAt", "note", "supersededBy", "createdAt")
                    values
                        (:id, :org, :proj, :patientId, :toothFdi, cast(:scope as "FindingScope"),
                         cast(:surfaces as "FindingSurface"[]), :code,
                         cast(:attributes as jsonb), cast(:state as "FindingState"),
                         cast(:source as "FindingSource"), :plannedProcedureId,
                         :recordedBy, :recordedAt, :note, :supersededBy,
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }


    /**
     * Copies the global template into one clinic's own catalogue.
     *
     * <p>Called on first use, and cheap afterwards: the emptiness check is one indexed count
     * and a clinic that already has terms copies nothing. Re-running it is therefore
     * idempotent in the way that matters.
     *
     * <p>Every copied term is {@code isClinicianReviewed = false}. "Which findings THIS clinic
     * records" is the clinic's answer, not the template's, so nothing here may assert that a
     * clinician confirmed a term they have not seen.
     *
     * <p>The template is TENANT-LESS and is read without a scope. That is not a tenant leak: it
     * is a published clinical vocabulary holding no patient data, the same shape as a domain
     * enumeration such as {@code Locale} or {@code UserRole}.
     */
    public int copyTemplateInto(ClinicScope scope) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException(
                    "A finding vocabulary belongs to one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            // Copy only what this clinic does NOT already have. A straight insert would abort
            // the WHOLE copy on the first code the clinic had already added by hand, leaving
            // the catalogue half-populated and every later finding refused. The clinic's own
            // wording of a term therefore wins over the template's, which is the entire point of
            // a clinic having its own catalogue.
            return jdbc.update("""
                insert into "clinic_finding_vocabulary"
                    ("id", "organizationId", "projectId", "code", "category", "labelAr", "labelEn",
                     "descriptionAr", "descriptionEn", "attributeSchema",
                     "isClinicianReviewed", "isActive", "createdAt")
                select gen_random_uuid(), :org, :proj, t."code", t."category", NULL, t."labelEn",
                       NULL, t."descriptionEn", t."attributeSchema",
                       false, true, (current_timestamp at time zone 'utc')
                  from "clinic_finding_vocabulary_template" t
                 where not exists (
                           select 1 from "clinic_finding_vocabulary" v
                            where v."organizationId" = :org
                              and v."projectId" = :proj
                              and v."code" = t."code")
                """, scope.bind()
                .addValue("org", scope.queryScope().organizationId())
                .addValue("proj", scope.projectId()));

        });
    }
    /** Adds a term to the clinic's vocabulary. Data, not DDL, which is the point of the table. */
    public UUID insertTerm(ClinicScope scope, Map<String, Object> parameters) {
        if (!scope.isProjectScoped()) {
            // A term belongs to one clinic. A caller who may reach several has no correct
            // projectId, and writing the first accessible one would file the terminology under a
            // clinic that never agreed to it.
            throw new IllegalStateException(
                    "A finding term must be added inside one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            UUID id = parameters.get("id") == null ? UUID.randomUUID() : (UUID) parameters.get("id");
            MapSqlParameterSource bound = scope.bind(parameters)
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId());
            jdbc.update("""
                    insert into "clinic_finding_vocabulary"
                        ("id", "organizationId", "projectId", "code", "category", "labelAr", "labelEn",
                         "descriptionAr", "descriptionEn", "attributeSchema",
                         "isClinicianReviewed", "isActive", "createdAt")
                    values
                        (:id, :org, :proj, :code, cast(:category as "FindingCategory"), :labelAr, :labelEn,
                         :descriptionAr, :descriptionEn, cast(:attributeSchema as jsonb),
                         :isClinicianReviewed, :isActive,
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    /** Records a periodontal exam session header. */
    public UUID recordPeriodontalExam(ClinicScope scope, UUID patientId, UUID examinedByUserId, LocalDateTime examinedAt, String notes) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("A periodontal exam must be recorded inside one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            UUID id = UUID.randomUUID();
            MapSqlParameterSource bound = scope.bind()
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId())
                    .addValue("patientId", patientId)
                    .addValue("examinedByUserId", examinedByUserId)
                    .addValue("examinedAt", examinedAt != null ? examinedAt : LocalDateTime.now())
                    .addValue("notes", notes);
            jdbc.update("""
                    insert into "patient_periodontal_exams"
                        ("id", "organizationId", "projectId", "patientId", "examinedByUserId", "examinedAt", "notes", "createdAt")
                    values
                        (:id, :org, :proj, :patientId, :examinedByUserId, :examinedAt, :notes, (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }

    /** Records 6-site periodontal measurements for an exam session with derived CAL. */
    public void recordPeriodontalMeasurements(
            ClinicScope scope, UUID examId, UUID patientId, List<OdontogramModels.PeriodontalMeasurementInput> measurements) {
        if (!scope.isProjectScoped()) {
            throw new IllegalStateException("Measurements must be recorded inside one clinic; name the project explicitly.");
        }
        if (measurements == null || measurements.isEmpty()) {
            return;
        }
        scope.read(() -> {
            for (OdontogramModels.PeriodontalMeasurementInput m : measurements) {
                // Ensure canonical FDI tooth notation
                ToothNotationConverter.validateFdi(m.toothFdi());

                // Derive CAL = PD + GM for each site
                Integer calMb = ToothNotationConverter.deriveCal(m.probingDepthMb(), m.gingivalMarginMb());
                Integer calB = ToothNotationConverter.deriveCal(m.probingDepthB(), m.gingivalMarginB());
                Integer calDb = ToothNotationConverter.deriveCal(m.probingDepthDb(), m.gingivalMarginDb());
                Integer calMl = ToothNotationConverter.deriveCal(m.probingDepthMl(), m.gingivalMarginMl());
                Integer calL = ToothNotationConverter.deriveCal(m.probingDepthL(), m.gingivalMarginL());
                Integer calDl = ToothNotationConverter.deriveCal(m.probingDepthDl(), m.gingivalMarginDl());

                MapSqlParameterSource bound = scope.bind()
                        .addValue("id", UUID.randomUUID())
                        .addValue("org", scope.queryScope().organizationId())
                        .addValue("proj", scope.projectId())
                        .addValue("examId", examId)
                        .addValue("patientId", patientId)
                        .addValue("toothFdi", m.toothFdi())
                        .addValue("probingDepthMb", m.probingDepthMb())
                        .addValue("probingDepthB", m.probingDepthB())
                        .addValue("probingDepthDb", m.probingDepthDb())
                        .addValue("probingDepthMl", m.probingDepthMl())
                        .addValue("probingDepthL", m.probingDepthL())
                        .addValue("probingDepthDl", m.probingDepthDl())
                        .addValue("gingivalMarginMb", m.gingivalMarginMb())
                        .addValue("gingivalMarginB", m.gingivalMarginB())
                        .addValue("gingivalMarginDb", m.gingivalMarginDb())
                        .addValue("gingivalMarginMl", m.gingivalMarginMl())
                        .addValue("gingivalMarginL", m.gingivalMarginL())
                        .addValue("gingivalMarginDl", m.gingivalMarginDl())
                        .addValue("calMb", calMb)
                        .addValue("calB", calB)
                        .addValue("calDb", calDb)
                        .addValue("calMl", calMl)
                        .addValue("calL", calL)
                        .addValue("calDl", calDl)
                        .addValue("bopMb", m.bopMb())
                        .addValue("bopB", m.bopB())
                        .addValue("bopDb", m.bopDb())
                        .addValue("bopMl", m.bopMl())
                        .addValue("bopL", m.bopL())
                        .addValue("bopDl", m.bopDl())
                        .addValue("suppurationMb", m.suppurationMb())
                        .addValue("suppurationB", m.suppurationB())
                        .addValue("suppurationDb", m.suppurationDb())
                        .addValue("suppurationMl", m.suppurationMl())
                        .addValue("suppurationL", m.suppurationL())
                        .addValue("suppurationDl", m.suppurationDl())
                        .addValue("plaqueMb", m.plaqueMb())
                        .addValue("plaqueB", m.plaqueB())
                        .addValue("plaqueDb", m.plaqueDb())
                        .addValue("plaqueMl", m.plaqueMl())
                        .addValue("plaqueL", m.plaqueL())
                        .addValue("plaqueDl", m.plaqueDl())
                        .addValue("furcationClass", m.furcationClass())
                        .addValue("mobilityGrade", m.mobilityGrade());

                jdbc.update("""
                        insert into "patient_periodontal_measurements"
                            ("id", "organizationId", "projectId", "examId", "patientId", "toothFdi",
                             "probingDepthMb", "probingDepthB", "probingDepthDb", "probingDepthMl", "probingDepthL", "probingDepthDl",
                             "gingivalMarginMb", "gingivalMarginB", "gingivalMarginDb", "gingivalMarginMl", "gingivalMarginL", "gingivalMarginDl",
                             "calMb", "calB", "calDb", "calMl", "calL", "calDl",
                             "bopMb", "bopB", "bopDb", "bopMl", "bopL", "bopDl",
                             "suppurationMb", "suppurationB", "suppurationDb", "suppurationMl", "suppurationL", "suppurationDl",
                             "plaqueMb", "plaqueB", "plaqueDb", "plaqueMl", "plaqueL", "plaqueDl",
                             "furcationClass", "mobilityGrade", "createdAt")
                        values
                            (:id, :org, :proj, :examId, :patientId, :toothFdi,
                             :probingDepthMb, :probingDepthB, :probingDepthDb, :probingDepthMl, :probingDepthL, :probingDepthDl,
                             :gingivalMarginMb, :gingivalMarginB, :gingivalMarginDb, :gingivalMarginMl, :gingivalMarginL, :gingivalMarginDl,
                             :calMb, :calB, :calDb, :calMl, :calL, :calDl,
                             :bopMb, :bopB, :bopDb, :bopMl, :bopL, :bopDl,
                             :suppurationMb, :suppurationB, :suppurationDb, :suppurationMl, :suppurationL, :suppurationDl,
                             :plaqueMb, :plaqueB, :plaqueDb, :plaqueMl, :plaqueL, :plaqueDl,
                             :furcationClass, :mobilityGrade, (current_timestamp at time zone 'utc'))
                        """, bound);
            }
            return null;
        });
    }

    /** Lists a patient's periodontal exam sessions. */
    public List<OdontogramModels.PeriodontalExamHeader> listPeriodontalExams(ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.query("""
                select e."id", e."organizationId", e."projectId", e."patientId",
                       e."examinedByUserId", u."fullName" as "examinedByUserName",
                       e."examinedAt", e."notes", e."createdAt",
                       (select count(*) from "patient_periodontal_measurements" m
                         where m."organizationId" = e."organizationId"
                           and m."examId" = e."id") as "measurementCount"
                  from "patient_periodontal_exams" e
                  left join "users" u on u."id" = e."examinedByUserId" and u."organizationId" = e."organizationId"
                 where %s
                   and e."patientId" = :patientId
                 order by e."examinedAt" desc, e."id"
                """.formatted(scope.predicate("e")),
                scope.bind(Map.of("patientId", patientId)),
                OdontogramRepository::examHeader));
    }

    /** Gets detail for a single periodontal exam session with all measurements. */
    public OdontogramModels.PeriodontalExamDetail getPeriodontalExamDetail(ClinicScope scope, UUID examId) {
        List<OdontogramModels.PeriodontalExamHeader> headers = scope.read(() -> jdbc.query("""
                select e."id", e."organizationId", e."projectId", e."patientId",
                       e."examinedByUserId", u."fullName" as "examinedByUserName",
                       e."examinedAt", e."notes", e."createdAt",
                       (select count(*) from "patient_periodontal_measurements" m
                         where m."organizationId" = e."organizationId"
                           and m."examId" = e."id") as "measurementCount"
                  from "patient_periodontal_exams" e
                  left join "users" u on u."id" = e."examinedByUserId" and u."organizationId" = e."organizationId"
                 where %s
                   and e."id" = :examId
                """.formatted(scope.predicate("e")),
                scope.bind(Map.of("examId", examId)),
                OdontogramRepository::examHeader));

        if (headers.isEmpty()) {
            return null;
        }

        List<OdontogramModels.PeriodontalMeasurement> measurements = scope.read(() -> jdbc.query("""
                select m.*
                  from "patient_periodontal_measurements" m
                 where %s
                   and m."examId" = :examId
                 order by m."toothFdi"
                """.formatted(scope.predicate("m")),
                scope.bind(Map.of("examId", examId)),
                OdontogramRepository::measurement));

        return new OdontogramModels.PeriodontalExamDetail(headers.get(0), measurements);
    }

    /** Gets the latest periodontal measurement per tooth for a patient (overlay/charting). */
    public List<OdontogramModels.PeriodontalMeasurement> latestPeriodontalChart(ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.query("""
                select distinct on (m."toothFdi") m.*
                  from "patient_periodontal_measurements" m
                  join "patient_periodontal_exams" e on e."id" = m."examId" and e."organizationId" = m."organizationId"
                 where %s
                   and m."patientId" = :patientId
                 order by m."toothFdi", e."examinedAt" desc, m."createdAt" desc
                """.formatted(scope.predicate("m")),
                scope.bind(Map.of("patientId", patientId)),
                OdontogramRepository::measurement));
    }

    private static int bounded(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }

    private static OdontogramModels.FindingRow finding(ResultSet rs, int n) throws SQLException {
        String surfaces = rs.getString("surfaces");
        String attributes = rs.getString("attributes");
        return new OdontogramModels.FindingRow(
                rs.getObject("id", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getInt("toothFdi"),
                rs.getString("scope"),
                surfaces == null || surfaces.isBlank()
                        ? List.of()
                        : Arrays.stream(surfaces.replace("{", "").replace("}", "").split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList(),
                rs.getString("code"),
                attributes,
                rs.getString("state"),
                rs.getString("source"),
                rs.getObject("plannedProcedureId", UUID.class),
                rs.getObject("recordedBy", UUID.class),
                toLocalDateTime(rs.getTimestamp("recordedAt")),
                rs.getString("note"),
                rs.getObject("supersededBy", UUID.class));
    }

    private static OdontogramModels.VocabularyTerm term(ResultSet rs, int n) throws SQLException {
        return new OdontogramModels.VocabularyTerm(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("category"),
                rs.getString("labelAr"),
                rs.getString("labelEn"),
                rs.getBoolean("isClinicianReviewed"),
                rs.getBoolean("isActive"));
    }

    private static OdontogramModels.PeriodontalExamHeader examHeader(ResultSet rs, int n) throws SQLException {
        return new OdontogramModels.PeriodontalExamHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getObject("examinedByUserId", UUID.class),
                rs.getString("examinedByUserName"),
                toLocalDateTime(rs.getTimestamp("examinedAt")),
                rs.getString("notes"),
                rs.getInt("measurementCount"),
                toLocalDateTime(rs.getTimestamp("createdAt")));
    }

    private static OdontogramModels.PeriodontalMeasurement measurement(ResultSet rs, int n) throws SQLException {
        return new OdontogramModels.PeriodontalMeasurement(
                rs.getObject("id", UUID.class),
                rs.getObject("organizationId", UUID.class),
                rs.getObject("projectId", UUID.class),
                rs.getObject("examId", UUID.class),
                rs.getObject("patientId", UUID.class),
                rs.getInt("toothFdi"),
                (Integer) rs.getObject("probingDepthMb"),
                (Integer) rs.getObject("probingDepthB"),
                (Integer) rs.getObject("probingDepthDb"),
                (Integer) rs.getObject("probingDepthMl"),
                (Integer) rs.getObject("probingDepthL"),
                (Integer) rs.getObject("probingDepthDl"),
                (Integer) rs.getObject("gingivalMarginMb"),
                (Integer) rs.getObject("gingivalMarginB"),
                (Integer) rs.getObject("gingivalMarginDb"),
                (Integer) rs.getObject("gingivalMarginMl"),
                (Integer) rs.getObject("gingivalMarginL"),
                (Integer) rs.getObject("gingivalMarginDl"),
                (Integer) rs.getObject("calMb"),
                (Integer) rs.getObject("calB"),
                (Integer) rs.getObject("calDb"),
                (Integer) rs.getObject("calMl"),
                (Integer) rs.getObject("calL"),
                (Integer) rs.getObject("calDl"),
                rs.getBoolean("bopMb"),
                rs.getBoolean("bopB"),
                rs.getBoolean("bopDb"),
                rs.getBoolean("bopMl"),
                rs.getBoolean("bopL"),
                rs.getBoolean("bopDl"),
                rs.getBoolean("suppurationMb"),
                rs.getBoolean("suppurationB"),
                rs.getBoolean("suppurationDb"),
                rs.getBoolean("suppurationMl"),
                rs.getBoolean("suppurationL"),
                rs.getBoolean("suppurationDl"),
                rs.getBoolean("plaqueMb"),
                rs.getBoolean("plaqueB"),
                rs.getBoolean("plaqueDb"),
                rs.getBoolean("plaqueMl"),
                rs.getBoolean("plaqueL"),
                rs.getBoolean("plaqueDl"),
                (Integer) rs.getObject("furcationClass"),
                (Integer) rs.getObject("mobilityGrade"),
                toLocalDateTime(rs.getTimestamp("createdAt")));
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
