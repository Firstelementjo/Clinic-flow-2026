package com.firstelement.crm.clinic.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.crm.CrmFixture;
import com.firstelement.crm.CrmIntegrationTestBase;
import com.firstelement.crm.clinic.ClinicScope;
import com.firstelement.crm.clinic.patient.PatientModels;
import com.firstelement.crm.clinic.patient.PatientService;
import com.firstelement.security.query.TenantQueryGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Integration tests for Dental Clinic Media, Clinical Radiographs, Documents,
 * AES-256-GCM encryption, HMAC-SHA256 signed URLs, legal hold retention, and access auditing (Slice S7).
 */
@SpringBootTest(classes = com.firstelement.crm.CrmTestApplication.class)
class ClinicMediaServiceIntegrationTest extends CrmIntegrationTestBase {

    @Autowired
    private ClinicMediaService service;

    @Autowired
    private ClinicMediaStorage storage;

    @Autowired
    private PatientService patients;

    @Autowired
    private CrmFixture fixture;

    @Autowired
    private TenantQueryGuard guard;

    @BeforeEach
    void resetDatabase() {
        fixture.reset();
    }

    private ClinicScope tenantAScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminAContext(), fixture.projectA1);
    }

    private ClinicScope tenantBScope() {
        return ClinicScope.forRequestedProject(guard, fixture.adminBContext(), fixture.projectB1);
    }

    private UUID createPatient(ClinicScope scope, String name, String phone) {
        String patientNo = "P-" + UUID.randomUUID().toString().substring(0, 8);
        PatientModels.PatientRow patient = patients.create(
                scope,
                patientNo,
                name,
                "Ahmad",
                "Patient",
                LocalDate.of(1990, 1, 1),
                phone,
                name.toLowerCase().replace(" ", "") + "@test.example",
                "ar",
                null
        );
        return patient.id();
    }

    @Test
    @DisplayName("Uploading clinical radiograph encrypts file on disk with AES-256-GCM and computes SHA-256")
    void uploadMedia_encryptsAndComputesSha256() throws IOException {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Ahmad Radiograph", "+962791112233");

        byte[] plainBytes = "RAW_DENTAL_RADIOGRAPH_PIXEL_DATA_PA_TOOTH_16".getBytes(StandardCharsets.UTF_8);

        ClinicMediaModels.UploadMediaRequest uploadReq = new ClinicMediaModels.UploadMediaRequest(
                "periapical-tooth-16.png",
                "image/png",
                plainBytes,
                ClinicMediaModels.CAT_RADIOGRAPH_INTRAORAL,
                16,
                Instant.now(),
                "Dr. Sarah Radiologist",
                "Periapical x-ray of upper right first molar showing bone level",
                "RADIOLOGY"
        );

        ClinicMediaModels.MediaRow row = service.uploadMedia(
                scope,
                patientId,
                uploadReq,
                fixture.adminA,
                "Admin A",
                "127.0.0.1"
        );

        assertThat(row.id()).isNotNull();
        assertThat(row.fileName()).isEqualTo("periapical-tooth-16.png");
        assertThat(row.category()).isEqualTo(ClinicMediaModels.CAT_RADIOGRAPH_INTRAORAL);
        assertThat(row.toothFdi()).isEqualTo(16);
        assertThat(row.byteSize()).isEqualTo(plainBytes.length);
        assertThat(row.encrypted()).isTrue();
        assertThat(row.encryptionAlgorithm()).isEqualTo("AES-256-GCM");
        assertThat(row.sha256()).isEqualTo(ClinicMediaStorage.computeSha256(plainBytes));

        // Verify physical file on disk exists and is encrypted (does NOT match plain bytes)
        Path physicalPath = storage.resolvePath(row.storageKey());
        assertThat(Files.exists(physicalPath)).isTrue();
        byte[] diskBytes = Files.readAllBytes(physicalPath);
        assertThat(diskBytes).isNotEqualTo(plainBytes);

        // Verify access audit record exists
        List<ClinicMediaModels.MediaAccessAuditRow> audits = service.getAttachmentAuditTrail(scope, row.id());
        assertThat(audits).isNotEmpty();
        assertThat(audits.get(0).action()).isEqualTo(ClinicMediaModels.ACTION_VIEW);
    }

    @Test
    @DisplayName("Short-lived signed download URL decrypts and validates SHA-256 checksum integrity")
    void downloadBySignedToken_success() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Nour Panoramic", "+962791112234");

        byte[] plainBytes = "PANORAMIC_FULL_ARCH_DENTAL_SCAN_DATA".getBytes(StandardCharsets.UTF_8);

        ClinicMediaModels.UploadMediaRequest uploadReq = new ClinicMediaModels.UploadMediaRequest(
                "panoramic.png",
                "image/png",
                plainBytes,
                ClinicMediaModels.CAT_RADIOGRAPH_PANORAMIC,
                null,
                Instant.now(),
                "Dr. Sarah Radiologist",
                "Full arch OPG radiograph",
                "RADIOLOGY"
        );

        ClinicMediaModels.MediaRow row = service.uploadMedia(
                scope,
                patientId,
                uploadReq,
                fixture.adminA,
                "Admin A",
                "127.0.0.1"
        );

        ClinicMediaModels.SignedDownloadUrl signedUrl = service.createSignedDownloadUrl(
                scope,
                row.id(),
                fixture.adminA,
                "Admin A",
                "127.0.0.1",
                Duration.ofMinutes(15)
        );

        assertThat(signedUrl.token()).isNotBlank();
        assertThat(signedUrl.downloadUrl()).contains("/api/v1/clinic/media/download?token=");

        // Download using signed token
        ClinicMediaService.DecryptedMediaPayload payload = service.downloadBySignedToken(
                signedUrl.token(),
                fixture.adminA,
                "Admin A",
                "127.0.0.1"
        );

        assertThat(payload.fileName()).isEqualTo("panoramic.png");
        assertThat(payload.contentType()).isEqualTo("image/png");
        assertThat(payload.content()).isEqualTo(plainBytes);
        assertThat(payload.sha256()).isEqualTo(row.sha256());

        // Verify DOWNLOAD action recorded in audit log
        List<ClinicMediaModels.MediaAccessAuditRow> audits = service.getAttachmentAuditTrail(scope, row.id());
        assertThat(audits).anyMatch(a -> a.action().equals(ClinicMediaModels.ACTION_DOWNLOAD));
    }

    @Test
    @DisplayName("Tampered signature in download token is refused with SecurityException")
    void downloadBySignedToken_tamperedSignatureRejected() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Tamper Test", "+962791112235");

        ClinicMediaModels.MediaRow row = service.uploadMedia(
                scope,
                patientId,
                new ClinicMediaModels.UploadMediaRequest(
                        "doc.pdf",
                        "application/pdf",
                        "PDF_CONTENT".getBytes(StandardCharsets.UTF_8),
                        ClinicMediaModels.CAT_DOCUMENT_PDF,
                        null,
                        Instant.now(),
                        null,
                        null,
                        null
                ),
                fixture.adminA,
                "Admin A",
                "127.0.0.1"
        );

        ClinicMediaModels.SignedDownloadUrl signed = service.createSignedDownloadUrl(
                scope,
                row.id(),
                fixture.adminA,
                "Admin A",
                "127.0.0.1",
                Duration.ofMinutes(15)
        );

        String tamperedToken = signed.token().substring(0, signed.token().length() - 4) + "XXXX";

        assertThatThrownBy(() -> service.downloadBySignedToken(tamperedToken, null, null, "127.0.0.1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid download token signature");
    }

    @Test
    @DisplayName("Disallowed MIME types and invalid tooth FDI are rejected")
    void validation_rejectsInvalidInputs() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Invalid Input Patient", "+962791112236");

        // Executable MIME type rejection
        assertThatThrownBy(() -> service.uploadMedia(
                scope,
                patientId,
                new ClinicMediaModels.UploadMediaRequest(
                        "malicious.exe",
                        "application/x-msdownload",
                        "BAD_BINARY".getBytes(StandardCharsets.UTF_8),
                        ClinicMediaModels.CAT_OTHER,
                        null,
                        Instant.now(),
                        null,
                        null,
                        null
                ),
                fixture.adminA,
                "Admin A",
                "127.0.0.1"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not permitted");

        // Invalid tooth FDI (99 is outside 11-85)
        assertThatThrownBy(() -> service.uploadMedia(
                scope,
                patientId,
                new ClinicMediaModels.UploadMediaRequest(
                        "radiograph.png",
                        "image/png",
                        "XRAY".getBytes(StandardCharsets.UTF_8),
                        ClinicMediaModels.CAT_RADIOGRAPH_INTRAORAL,
                        99,
                        Instant.now(),
                        null,
                        null,
                        null
                ),
                fixture.adminA,
                "Admin A",
                "127.0.0.1"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid tooth FDI code");
    }

    @Test
    @DisplayName("Legal hold strictly blocks media deletion per DEC-007, release permits deletion")
    void legalHold_blocksDeletion() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Hold Patient", "+962791112237");

        ClinicMediaModels.MediaRow row = service.uploadMedia(
                scope,
                patientId,
                new ClinicMediaModels.UploadMediaRequest(
                        "consent.pdf",
                        "application/pdf",
                        "SIGNED_CONSENT_FORM_PDF".getBytes(StandardCharsets.UTF_8),
                        ClinicMediaModels.CAT_CONSENT_FORM,
                        null,
                        Instant.now(),
                        null,
                        "Signed extraction consent",
                        "LEGAL_DOCUMENT"
                ),
                fixture.adminA,
                "Admin A",
                "127.0.0.1"
        );

        // Set legal hold
        boolean holdSet = service.toggleLegalHold(
                scope,
                row.id(),
                true,
                fixture.adminA,
                "Admin A",
                "127.0.0.1",
                "Malpractice claim investigation hold"
        );
        assertThat(holdSet).isTrue();

        ClinicMediaModels.MediaRow updated = service.getMedia(scope, row.id());
        assertThat(updated.legalHold()).isTrue();

        // Deletion must be refused!
        assertThatThrownBy(() -> service.deleteMedia(
                scope,
                row.id(),
                fixture.adminA,
                "Admin A",
                "127.0.0.1",
                "Attempt to remove consent"
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("protected under active legal hold (DEC-007)");

        // Release legal hold
        service.toggleLegalHold(
                scope,
                row.id(),
                false,
                fixture.adminA,
                "Admin A",
                "127.0.0.1",
                "Hold lifted by legal department"
        );

        // Deletion now succeeds
        boolean deleted = service.deleteMedia(
                scope,
                row.id(),
                fixture.adminA,
                "Admin A",
                "127.0.0.1",
                "Legitimate retention expiration deletion"
        );
        assertThat(deleted).isTrue();

        // Querying media list no longer returns soft-deleted attachment
        List<ClinicMediaModels.MediaRow> activeList = service.listPatientMedia(scope, patientId, null, null, null);
        assertThat(activeList).noneMatch(m -> m.id().equals(row.id()));

        // Audit trail preserves all actions including legal hold set, removed, and soft-deleted
        List<ClinicMediaModels.MediaAccessAuditRow> audits = service.getAttachmentAuditTrail(scope, row.id());
        assertThat(audits).extracting(ClinicMediaModels.MediaAccessAuditRow::action)
                .contains(
                        ClinicMediaModels.ACTION_LEGAL_HOLD_SET,
                        ClinicMediaModels.ACTION_LEGAL_HOLD_REMOVED,
                        ClinicMediaModels.ACTION_SOFT_DELETED
                );
    }

    @Test
    @DisplayName("Tenant isolation: Tenant B cannot see, download or delete Tenant A media")
    void tenantIsolation_enforced() {
        ClinicScope scopeA = tenantAScope();
        ClinicScope scopeB = tenantBScope();

        UUID patientAId = createPatient(scopeA, "Patient In Org A", "+962791112238");

        ClinicMediaModels.MediaRow mediaA = service.uploadMedia(
                scopeA,
                patientAId,
                new ClinicMediaModels.UploadMediaRequest(
                        "orgA-radiograph.png",
                        "image/png",
                        "ORG_A_SENSITIVE_XRAY".getBytes(StandardCharsets.UTF_8),
                        ClinicMediaModels.CAT_RADIOGRAPH_INTRAORAL,
                        21,
                        Instant.now(),
                        null,
                        null,
                        null
                ),
                fixture.adminA,
                "Admin A",
                "127.0.0.1"
        );

        // Tenant B listing media for Patient A returns empty list (patient A does not belong to B)
        List<ClinicMediaModels.MediaRow> listB = service.listPatientMedia(scopeB, patientAId, null, null, null);
        assertThat(listB).isEmpty();

        // Tenant B requesting Tenant A's media by ID fails
        assertThatThrownBy(() -> service.getMedia(scopeB, mediaA.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Media attachment not found");

        // Tenant B attempting to delete Tenant A's media fails
        assertThatThrownBy(() -> service.deleteMedia(
                scopeB,
                mediaA.id(),
                fixture.adminB,
                "Admin B",
                "127.0.0.1",
                "Cross tenant deletion"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Filtering patient media by category and tooth FDI works accurately")
    void filterByToothAndCategory() {
        ClinicScope scope = tenantAScope();
        UUID patientId = createPatient(scope, "Filter Patient", "+962791112239");

        // Media 1: Radiograph tooth 16
        service.uploadMedia(scope, patientId, new ClinicMediaModels.UploadMediaRequest(
                "tooth16.png", "image/png", "DATA1".getBytes(StandardCharsets.UTF_8),
                ClinicMediaModels.CAT_RADIOGRAPH_INTRAORAL, 16, Instant.now(), null, null, null
        ), fixture.adminA, "Admin A", "127.0.0.1");

        // Media 2: Radiograph tooth 26
        service.uploadMedia(scope, patientId, new ClinicMediaModels.UploadMediaRequest(
                "tooth26.png", "image/png", "DATA2".getBytes(StandardCharsets.UTF_8),
                ClinicMediaModels.CAT_RADIOGRAPH_INTRAORAL, 26, Instant.now(), null, null, null
        ), fixture.adminA, "Admin A", "127.0.0.1");

        // Media 3: Lab prescription PDF
        service.uploadMedia(scope, patientId, new ClinicMediaModels.UploadMediaRequest(
                "crown-lab.pdf", "application/pdf", "DATA3".getBytes(StandardCharsets.UTF_8),
                ClinicMediaModels.CAT_LAB_PRESCRIPTION, null, Instant.now(), null, null, null
        ), fixture.adminA, "Admin A", "127.0.0.1");

        // All media (3)
        List<ClinicMediaModels.MediaRow> all = service.listPatientMedia(scope, patientId, null, null, null);
        assertThat(all).hasSize(3);

        // Category filter (RADIOGRAPH_INTRAORAL -> 2)
        List<ClinicMediaModels.MediaRow> radiographs = service.listPatientMedia(
                scope, patientId, ClinicMediaModels.CAT_RADIOGRAPH_INTRAORAL, null, null);
        assertThat(radiographs).hasSize(2);

        // Tooth filter (16 -> 1)
        List<ClinicMediaModels.MediaRow> tooth16 = service.listPatientMedia(
                scope, patientId, null, 16, null);
        assertThat(tooth16).hasSize(1);
        assertThat(tooth16.get(0).fileName()).isEqualTo("tooth16.png");

        // Combined filter (LAB_PRESCRIPTION -> 1)
        List<ClinicMediaModels.MediaRow> labDocs = service.listPatientMedia(
                scope, patientId, ClinicMediaModels.CAT_LAB_PRESCRIPTION, null, null);
        assertThat(labDocs).hasSize(1);
        assertThat(labDocs.get(0).fileName()).isEqualTo("crown-lab.pdf");
    }
}
