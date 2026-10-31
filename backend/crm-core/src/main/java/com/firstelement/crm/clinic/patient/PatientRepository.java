package com.firstelement.crm.clinic.patient;

import com.firstelement.crm.clinic.ClinicScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every patient statement, all of them scoped.
 *
 * <p>Follows {@link ClinicRepository}'s rule exactly: a tenant predicate is never written by the
 * caller, it is supplied by a {@link ClinicScope} obtained through one of its three checked
 * construction paths. There is no method here that takes an unchecked {@code organizationId}.
 *
 * <p>Two deliberate constraints:
 *
 * <ul>
 *   <li><b>Soft-deleted patients are filtered in SQL, not in Java.</b> Filtering after the read
 *       would put deleted rows in the process, where a bug elsewhere could return them.</li>
 *   <li><b>Search never touches retention columns</b> and never joins {@code customers}. A patient
 *       search that could reach CRM contact data would blur the DEC-015 boundary.</li>
 * </ul>
 */
@Repository
public class PatientRepository {

    private static final String COLUMNS = """
            p."id", p."patientNo", p."displayName", p."givenName", p."familyName",
            p."gender", p."dateOfBirth", p."primaryPhone", p."primaryEmail", p."cnic",
            p."profileImageUrl", p."addressLine", p."country", p."state", p."city",
            p."postalCode", p."isActive", p."locale", p."customerId", p."createdAt",
            (p."deletedAt" is not null) as "deleted"
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PatientRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Clinic-scoped list, newest first, with a caller-supplied page bound to the scope. */
    public List<PatientModels.PatientRow> list(ClinicScope scope, int limit) {
        return scope.read(() -> jdbc.query("""
                select\s""" + COLUMNS + """
                  from "patients" p
                 where\s""" + scope.predicate("p") + """
                   and p."deletedAt" is null
                 order by p."createdAt" desc, p."id"
                 limit :limit
                """, scope.bind().addValue("limit", Math.min(Math.max(limit, 1), 200)),
                (rs, n) -> row(rs)));
    }

    /**
     * Clinic-scoped search.
     *
     * <p>The term is matched against name and phone. It is never matched against
     * {@code dateOfBirth}: a date of birth is a near-universal value among patients, so a search on
     * it returns most of the clinic and tells the caller nothing.
     */
    public List<PatientModels.PatientRow> search(ClinicScope scope, String term, int limit) {
        String pattern = "%" + term.toLowerCase(java.util.Locale.ROOT) + "%";
        return scope.read(() -> jdbc.query("""
                select\s""" + COLUMNS + """
                  from "patients" p
                 where\s""" + scope.predicate("p") + """
                   and p."deletedAt" is null
                   and (lower(p."displayName") like :term
                        or lower(coalesce(p."familyName", '')) like :term
                        or lower(coalesce(p."givenName", '')) like :term
                        or lower(coalesce(p."primaryPhone", '')) like :term
                        or lower(coalesce(p."primaryEmail", '')) like :term
                        or lower(coalesce(p."cnic", '')) like :term)
                 order by p."createdAt" desc, p."id"
                 limit :limit
                """, scope.bind()
                        .addValue("term", pattern)
                        .addValue("limit", Math.min(Math.max(limit, 1), 200)),
                (rs, n) -> row(rs)));
    }

    /** One patient, by id, still scoped. Absent and foreign are the same answer. */
    public Optional<PatientModels.PatientRow> findById(ClinicScope scope, UUID patientId) {
        List<PatientModels.PatientRow> rows = scope.read(() -> jdbc.query("""
                select\s""" + COLUMNS + """
                  from "patients" p
                 where\s""" + scope.predicate("p") + """
                   and p."id" = :id
                   and p."deletedAt" is null
                """, scope.bind().addValue("id", patientId), (rs, n) -> row(rs)));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Candidate rows for duplicate scoring, read on the signals themselves.
     *
     * <p>Deliberately narrow: a shared surname on its own matches a large fraction of a clinic, so
     * it is only ever read alongside another signal.
     *
     * <p>A null argument needs no {@code IS NOT NULL} guard here. {@code column = NULL} evaluates to
     * NULL rather than true, so the OR simply does not fire for that signal - which is the
     * intended answer. The explicit {@code cast} is load-bearing for a different reason:
     * {@code :param IS NOT NULL} on its own gives PostgreSQL nothing to infer a type from, and it
     * answers {@code could not determine data type of parameter $7}.
     */
    public List<PatientModels.PatientRow> candidatesOnSignals(
            ClinicScope scope, String familyName, LocalDate dateOfBirth, String phone) {
        return scope.read(() -> jdbc.query("""
                select\s""" + COLUMNS + """
                  from "patients" p
                 where\s""" + scope.predicate("p") + """
                   and p."deletedAt" is null
                   and ( (lower(p."familyName") = cast(:family as "text"))
                      or (p."dateOfBirth" = cast(:dob as date))
                      or (p."primaryPhone" = cast(:phone as "text")) )
                 order by p."createdAt" desc, p."id"
                 limit 200
                """, scope.bind()
                        .addValue("family", familyName == null ? null : familyName.toLowerCase(java.util.Locale.ROOT))
                        .addValue("dob", dateOfBirth)
                        .addValue("phone", phone),
                (rs, n) -> row(rs)));
    }

    /** Next patient number for the tenant. Per-tenant, per DEC-016. */
    public Optional<String> nextPatientNo(ClinicScope scope) {
        List<String> rows = scope.read(() -> jdbc.queryForList("""
                select p."patientNo"
                  from "patients" p
                 where p."organizationId" = :org
                 order by p."createdAt" desc, p."id" desc
                 limit 1
                """, scope.bind(), String.class));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Inserts a patient.
     *
     * <p>Requires a PROJECT-SCOPED scope. A patient belongs to exactly one clinic, so there is no
     * correct value to write into {@code projectId} for a caller who may reach several - and
     * writing "the first accessible project" would silently file a patient's record under a clinic
     * they do not belong to. Refusing is the only honest answer.
     *
     * <p>This is the first write in the clinic module. {@code ClinicRepository} has none by design;
     * the tenant scoping here comes from the same {@link ClinicScope} the read paths use, so a
     * write cannot be scoped more loosely than a read.
     */
    public UUID insert(ClinicScope scope, Map<String, Object> parameters) {
        if (!scope.isProjectScoped()) {
            // IllegalStateException, not PolicyDeniedException: this is a caller mistake in the
            // service layer (it tried to create a patient without naming a clinic), not an
            // authorization outcome. A PolicyDeniedException would render as a 403 and read as
            // "you may not create patients", which would be a false thing to tell the operator.
            throw new IllegalStateException(
                    "A patient must be created inside one clinic; name the project explicitly.");
        }
        return scope.read(() -> {
            Object suppliedId = parameters.get("id");
            UUID id = suppliedId == null ? UUID.randomUUID() : (UUID) suppliedId;
            MapSqlParameterSource bound = scope.bind(parameters)
                    .addValue("id", id)
                    .addValue("org", scope.queryScope().organizationId())
                    .addValue("proj", scope.projectId());
            jdbc.update("""
                    insert into "patients"
                        ("id", "organizationId", "projectId", "patientNo", "displayName",
                         "givenName", "familyName", "gender", "dateOfBirth", "primaryPhone",
                         "primaryEmail", "cnic", "profileImageUrl", "addressLine", "country",
                         "state", "city", "postalCode", "isActive", "locale", "customerId",
                         "portalPasswordHash", "createdAt", "updatedAt")
                    values
                        (:id, :org, :proj, :patientNo, :displayName,
                         :givenName, :familyName, :gender, :dateOfBirth, :primaryPhone,
                         :primaryEmail, :cnic, :profileImageUrl, :addressLine, :country,
                         :state, :city, :postalCode, :isActive, cast(:locale as "Locale"),
                         :customerId, :portalPasswordHash,
                         (current_timestamp at time zone 'utc'),
                         (current_timestamp at time zone 'utc'))
                    """, bound);
            return id;
        });
    }
    public List<PatientModels.RelationshipRow> listRelationships(ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.query("""
                select r."id", r."organizationId", r."projectId", r."patientId",
                       r."relatedPatientId", p."displayName" as "relatedPatientName",
                       r."relationshipType", r."customRelationship",
                       r."relatedPersonName", r."relatedPersonPhone",
                       r."isGuarantor", r."isEmergencyContact", r."notes",
                       r."createdAt"
                  from "patient_relationships" r
                  left join "patients" p on p."id" = r."relatedPatientId"
                 where r."organizationId" = :org
                   and r."patientId" = :patientId
                 order by r."createdAt" desc
                """, scope.bind().addValue("patientId", patientId),
                (rs, n) -> new PatientModels.RelationshipRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organizationId", UUID.class),
                        rs.getObject("projectId", UUID.class),
                        rs.getObject("patientId", UUID.class),
                        rs.getObject("relatedPatientId", UUID.class),
                        rs.getString("relatedPatientName"),
                        rs.getString("relationshipType"),
                        rs.getString("customRelationship"),
                        rs.getString("relatedPersonName"),
                        rs.getString("relatedPersonPhone"),
                        rs.getBoolean("isGuarantor"),
                        rs.getBoolean("isEmergencyContact"),
                        rs.getString("notes"),
                        rs.getTimestamp("createdAt").toInstant())));
    }

    public UUID insertRelationship(ClinicScope scope, UUID patientId, PatientModels.CreateRelationshipRequest req) {
        UUID id = UUID.randomUUID();
        scope.read(() -> {
            jdbc.update("""
                    insert into "patient_relationships"
                        ("id", "organizationId", "projectId", "patientId", "relatedPatientId",
                         "relationshipType", "customRelationship", "relatedPersonName", "relatedPersonPhone",
                         "isGuarantor", "isEmergencyContact", "notes", "createdAt", "updatedAt")
                    values
                        (:id, :org, :proj, :patientId, :relatedPatientId,
                         :relationshipType, :customRelationship, :relatedPersonName, :relatedPersonPhone,
                         :isGuarantor, :isEmergencyContact, :notes, (now() at time zone 'utc'), (now() at time zone 'utc'))
                    """, scope.bind()
                    .addValue("id", id)
                    .addValue("proj", scope.projectId() != null ? scope.projectId() : scope.queryScope().organizationId())
                    .addValue("patientId", patientId)
                    .addValue("relatedPatientId", req.relatedPatientId())
                    .addValue("relationshipType", req.relationshipType())
                    .addValue("customRelationship", req.customRelationship())
                    .addValue("relatedPersonName", req.relatedPersonName())
                    .addValue("relatedPersonPhone", req.relatedPersonPhone())
                    .addValue("isGuarantor", Boolean.TRUE.equals(req.isGuarantor()))
                    .addValue("isEmergencyContact", Boolean.TRUE.equals(req.isEmergencyContact()))
                    .addValue("notes", req.notes()));
            return null;
        });
        return id;
    }

    public boolean deleteRelationship(ClinicScope scope, UUID patientId, UUID relId) {
        return scope.read(() -> jdbc.update("""
                delete from "patient_relationships"
                 where "organizationId" = :org
                   and "patientId" = :patientId
                   and "id" = :relId
                """, scope.bind().addValue("patientId", patientId).addValue("relId", relId)) > 0);
    }

    public List<PatientModels.ConsentRow> listConsents(ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.query("""
                select c."id", c."organizationId", c."projectId", c."patientId",
                       c."consentType", c."status", c."grantedAt", c."revokedAt",
                       c."revocationReason", c."expiresAt", c."signedBy",
                       c."signerRelationship", c."witnessName", c."documentReference",
                       c."notes", c."createdAt"
                  from "patient_consents" c
                 where c."organizationId" = :org
                   and c."patientId" = :patientId
                 order by c."grantedAt" desc
                """, scope.bind().addValue("patientId", patientId),
                (rs, n) -> new PatientModels.ConsentRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organizationId", UUID.class),
                        rs.getObject("projectId", UUID.class),
                        rs.getObject("patientId", UUID.class),
                        rs.getString("consentType"),
                        rs.getString("status"),
                        rs.getTimestamp("grantedAt").toInstant(),
                        rs.getTimestamp("revokedAt") != null ? rs.getTimestamp("revokedAt").toInstant() : null,
                        rs.getString("revocationReason"),
                        rs.getTimestamp("expiresAt") != null ? rs.getTimestamp("expiresAt").toInstant() : null,
                        rs.getString("signedBy"),
                        rs.getString("signerRelationship"),
                        rs.getString("witnessName"),
                        rs.getString("documentReference"),
                        rs.getString("notes"),
                        rs.getTimestamp("createdAt").toInstant())));
    }

    public UUID insertConsent(ClinicScope scope, UUID patientId, PatientModels.CreateConsentRequest req) {
        UUID id = UUID.randomUUID();
        scope.read(() -> {
            jdbc.update("""
                    insert into "patient_consents"
                        ("id", "organizationId", "projectId", "patientId", "consentType", "status",
                         "expiresAt", "signedBy", "signerRelationship", "witnessName", "documentReference",
                         "notes", "grantedAt", "createdAt", "updatedAt")
                    values
                        (:id, :org, :proj, :patientId, :consentType, 'GRANTED',
                         :expiresAt, :signedBy, :signerRelationship, :witnessName, :documentReference,
                         :notes, (now() at time zone 'utc'), (now() at time zone 'utc'), (now() at time zone 'utc'))
                    """, scope.bind()
                    .addValue("id", id)
                    .addValue("proj", scope.projectId() != null ? scope.projectId() : scope.queryScope().organizationId())
                    .addValue("patientId", patientId)
                    .addValue("consentType", req.consentType())
                    .addValue("expiresAt", req.expiresAt() != null ? java.sql.Timestamp.from(req.expiresAt()) : null)
                    .addValue("signedBy", req.signedBy())
                    .addValue("signerRelationship", req.signerRelationship() != null ? req.signerRelationship() : "SELF")
                    .addValue("witnessName", req.witnessName())
                    .addValue("documentReference", req.documentReference())
                    .addValue("notes", req.notes()));
            return null;
        });
        return id;
    }

    public boolean revokeConsent(ClinicScope scope, UUID patientId, UUID consentId, String reason) {
        return scope.read(() -> jdbc.update("""
                update "patient_consents"
                   set "status" = 'REVOKED',
                       "revokedAt" = (now() at time zone 'utc'),
                       "revocationReason" = :reason,
                       "updatedAt" = (now() at time zone 'utc')
                 where "organizationId" = :org
                   and "patientId" = :patientId
                   and "id" = :consentId
                   and "status" = 'GRANTED'
                """, scope.bind()
                .addValue("patientId", patientId)
                .addValue("consentId", consentId)
                .addValue("reason", reason)) > 0);
    }

    public List<PatientModels.DisclosureLogRow> listDisclosures(ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.query("""
                select d."id", d."organizationId", d."projectId", d."patientId",
                       d."disclosedTo", d."purpose", d."recordsDisclosed",
                       d."disclosedAt", d."disclosedBy", u."fullName" as "disclosedByName",
                       d."createdAt"
                  from "patient_disclosure_logs" d
                  left join "users" u on u."id" = d."disclosedBy"
                 where d."organizationId" = :org
                   and d."patientId" = :patientId
                 order by d."disclosedAt" desc
                """, scope.bind().addValue("patientId", patientId),
                (rs, n) -> new PatientModels.DisclosureLogRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organizationId", UUID.class),
                        rs.getObject("projectId", UUID.class),
                        rs.getObject("patientId", UUID.class),
                        rs.getString("disclosedTo"),
                        rs.getString("purpose"),
                        rs.getString("recordsDisclosed"),
                        rs.getTimestamp("disclosedAt").toInstant(),
                        rs.getObject("disclosedBy", UUID.class),
                        rs.getString("disclosedByName"),
                        rs.getTimestamp("createdAt").toInstant())));
    }

    public UUID insertDisclosure(ClinicScope scope, UUID patientId, UUID userId, PatientModels.LogDisclosureRequest req) {
        UUID id = UUID.randomUUID();
        scope.read(() -> {
            jdbc.update("""
                    insert into "patient_disclosure_logs"
                        ("id", "organizationId", "projectId", "patientId", "disclosedTo", "purpose",
                         "recordsDisclosed", "disclosedBy", "disclosedAt", "createdAt")
                    values
                        (:id, :org, :proj, :patientId, :disclosedTo, :purpose,
                         :recordsDisclosed, :userId, (now() at time zone 'utc'), (now() at time zone 'utc'))
                    """, scope.bind()
                    .addValue("id", id)
                    .addValue("proj", scope.projectId() != null ? scope.projectId() : scope.queryScope().organizationId())
                    .addValue("patientId", patientId)
                    .addValue("disclosedTo", req.disclosedTo())
                    .addValue("purpose", req.purpose())
                    .addValue("recordsDisclosed", req.recordsDisclosed())
                    .addValue("userId", userId));
            return null;
        });
        return id;
    }

    public boolean archivePatient(ClinicScope scope, UUID patientId, UUID deletedBy, String reason) {
        return scope.read(() -> jdbc.update("""
                update "patients"
                   set "deletedAt" = (now() at time zone 'utc'),
                       "deletedBy" = :deletedBy,
                       "deletionReason" = :reason,
                       "updatedAt" = (now() at time zone 'utc')
                 where "organizationId" = :org
                   and "id" = :id
                   and "deletedAt" is null
                   and "legalHold" = false
                """, scope.bind()
                .addValue("id", patientId)
                .addValue("deletedBy", deletedBy)
                .addValue("reason", reason)) > 0);
    }

    public boolean restorePatient(ClinicScope scope, UUID patientId) {
        return scope.read(() -> jdbc.update("""
                update "patients"
                   set "deletedAt" = null,
                       "deletedBy" = null,
                       "deletionReason" = null,
                       "updatedAt" = (now() at time zone 'utc')
                 where "organizationId" = :org
                   and "id" = :id
                   and "deletedAt" is not null
                """, scope.bind().addValue("id", patientId)) > 0);
    }

    public PatientModels.MergePatientsResult mergePatients(ClinicScope scope, UUID sourceId, UUID targetId, UUID mergedBy, String reason) {
        return scope.read(() -> {
            // 1. Move appointments
            int appts = jdbc.update("""
                    update "appointments"
                       set "patientId" = :targetId
                     where "organizationId" = :org
                       and "patientId" = :sourceId
                    """, scope.bind().addValue("targetId", targetId).addValue("sourceId", sourceId));

            // 2. Move encounters
            int encs = jdbc.update("""
                    update "clinical_encounters"
                       set "patientId" = :targetId
                     where "organizationId" = :org
                       and "patientId" = :sourceId
                    """, scope.bind().addValue("targetId", targetId).addValue("sourceId", sourceId));

            // 3. Move treatment plans
            int plans = jdbc.update("""
                    update "treatment_plans"
                       set "patientId" = :targetId
                     where "organizationId" = :org
                       and "patientId" = :sourceId
                    """, scope.bind().addValue("targetId", targetId).addValue("sourceId", sourceId));

            // 4. Move invoices & payments
            int invs = jdbc.update("""
                    update "clinic_invoices"
                       set "patientId" = :targetId
                     where "organizationId" = :org
                       and "patientId" = :sourceId
                    """, scope.bind().addValue("targetId", targetId).addValue("sourceId", sourceId));

            jdbc.update("""
                    update "clinic_payments"
                       set "patientId" = :targetId
                     where "organizationId" = :org
                       and "patientId" = :sourceId
                    """, scope.bind().addValue("targetId", targetId).addValue("sourceId", sourceId));

            // 5. Move relationships
            jdbc.update("""
                    update "patient_relationships"
                       set "patientId" = :targetId
                     where "organizationId" = :org
                       and "patientId" = :sourceId
                    """, scope.bind().addValue("targetId", targetId).addValue("sourceId", sourceId));

            // 6. Move consents
            jdbc.update("""
                    update "patient_consents"
                       set "patientId" = :targetId
                     where "organizationId" = :org
                       and "patientId" = :sourceId
                    """, scope.bind().addValue("targetId", targetId).addValue("sourceId", sourceId));

            // 7. Record merge snapshot in patient_merges
            UUID mergeId = UUID.randomUUID();
            String snapshotJson = String.format("{\"reason\":\"%s\",\"appointmentsMoved\":%d,\"encountersMoved\":%d,\"plansMoved\":%d,\"invoicesMoved\":%d}",
                    reason != null ? reason.replace("\"", "\\\"") : "Patient merge", appts, encs, plans, invs);

            jdbc.update("""
                    insert into "patient_merges"
                        ("id", "organizationId", "projectId", "sourcePatientId", "targetPatientId",
                         "mergedBy", "mergedAt", "snapshotData", "status", "createdAt")
                    values
                        (:id, :org, :proj, :sourceId, :targetId,
                         :mergedBy, (now() at time zone 'utc'), cast(:snapshot as jsonb), 'MERGED', (now() at time zone 'utc'))
                    """, scope.bind()
                    .addValue("id", mergeId)
                    .addValue("proj", scope.projectId() != null ? scope.projectId() : scope.queryScope().organizationId())
                    .addValue("sourceId", sourceId)
                    .addValue("targetId", targetId)
                    .addValue("mergedBy", mergedBy)
                    .addValue("snapshot", snapshotJson));

            // 8. Soft-delete source patient
            jdbc.update("""
                    update "patients"
                       set "deletedAt" = (now() at time zone 'utc'),
                           "deletedBy" = :mergedBy,
                           "deletionReason" = :reason,
                           "updatedAt" = (now() at time zone 'utc')
                     where "organizationId" = :org
                       and "id" = :sourceId
                    """, scope.bind().addValue("sourceId", sourceId).addValue("mergedBy", mergedBy).addValue("reason", "Merged into " + targetId));

            return new PatientModels.MergePatientsResult(mergeId, sourceId, targetId, "MERGED", appts, encs, plans, invs);
        });
    }

    private static PatientModels.PatientRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Date dob = rs.getDate("dateOfBirth");
        java.sql.Timestamp created = rs.getTimestamp("createdAt");
        return new PatientModels.PatientRow(
                rs.getObject("id", UUID.class),
                rs.getString("patientNo"),
                rs.getString("displayName"),
                rs.getString("givenName"),
                rs.getString("familyName"),
                rs.getString("gender"),
                dob == null ? null : dob.toLocalDate(),
                rs.getString("primaryPhone"),
                rs.getString("primaryEmail"),
                rs.getString("cnic"),
                rs.getString("profileImageUrl"),
                rs.getString("addressLine"),
                rs.getString("country"),
                rs.getString("state"),
                rs.getString("city"),
                rs.getString("postalCode"),
                rs.getBoolean("isActive"),
                rs.getString("locale"),
                rs.getObject("customerId", UUID.class),
                created == null ? null : created.toLocalDateTime(),
                rs.getBoolean("deleted"));
    }
}