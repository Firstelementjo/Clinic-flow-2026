package com.firstelement.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.firstelement.security.config.SecurityProperties;
import com.firstelement.security.session.BrowserSessionStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * End-to-end HTTP integration tests for the Patient Intake Portal and 9-step Patient History flow:
 * quick-register, 1-hour single-use code generation, clinic QR isolation, public zero-PHI verify,
 * quarantined submit, one-way security boundary, and doctor review/approval sync.
 */
@SpringBootTest(classes = InboxTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PatientIntakeHttpIntegrationTest extends InboxIntegrationTestBase {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private BrowserSessionStore sessions;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private InboxFixture inbox;

    private Csrf csrf;

    @BeforeEach
    void setup() {
        inbox.reset();
        jdbc.getJdbcOperations().execute(
                "truncate table patient_intake_codes, patient_history_intakes,"
                        + " patient_medical_histories, patient_relationships, patients cascade");
        csrf = csrfToken();
    }

    @Test
    @DisplayName("Full intake lifecycle: quick register -> 1h code -> public verify & submit -> quarantine -> doctor approve sync")
    void fullPatientIntakeLifecycleAndSecurityBoundary() {
        String adminA = sessionFor(InboxFixture.USER_ADMIN_A);
        String adminB = sessionFor(InboxFixture.USER_ADMIN_B);

        // 1. Reception quick-registers patient with only First Name + Phone
        JsonNode createdPatient = write(adminA, HttpMethod.POST,
                "/api/v1/clinic/patients?projectId=" + InboxFixture.PROJECT_A1,
                """
                {
                  "givenName": "Adam",
                  "phone": "+962790001122",
                  "quickRegister": true
                }
                """,
                HttpStatus.CREATED);
        UUID patientId = UUID.fromString(createdPatient.path("id").asText());
        assertThat(createdPatient.path("givenName").asText()).isEqualTo("Adam");
        assertThat(createdPatient.path("dateOfBirth").isNull() || createdPatient.path("dateOfBirth").isMissingNode()).isTrue();

        // 2. Get Clinic A QR config and Clinic B QR config
        JsonNode qrA = get(adminA, "/api/v1/clinic/settings/intake-qr?projectId=" + InboxFixture.PROJECT_A1);
        String clinicKeyA = qrA.path("intakePortalKey").asText();
        assertThat(clinicKeyA).isNotBlank();

        JsonNode qrB = get(adminB, "/api/v1/clinic/settings/intake-qr?projectId=" + InboxFixture.PROJECT_B1);
        String clinicKeyB = qrB.path("intakePortalKey").asText();
        assertThat(clinicKeyB).isNotBlank().isNotEqualTo(clinicKeyA);

        // 3. Staff generates 1-hour intake code for the patient
        JsonNode codeResp = write(adminA, HttpMethod.POST,
                "/api/v1/clinic/patients/" + patientId + "/intake-code?projectId=" + InboxFixture.PROJECT_A1,
                "{}",
                HttpStatus.CREATED);
        String plainCode = codeResp.path("code").asText();
        String formattedCode = codeResp.path("formattedCode").asText();
        assertThat(plainCode).hasSize(6);
        assertThat(formattedCode).contains("-");

        // 4. Public portal fetches clinic config (unauthenticated, no cookies, no CSRF)
        ResponseEntity<JsonNode> configResp = http.getForEntity(
                "/api/v1/public/intake/" + clinicKeyA + "/config", JsonNode.class);
        assertThat(configResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(configResp.getBody().path("active").asBoolean()).isTrue();
        assertThat(configResp.getBody().path("schema").path("medicalConditions").size()).isEqualTo(16);

        // 5. Cross-clinic code isolation: Clinic A's code must be rejected on Clinic B's QR URL
        ResponseEntity<JsonNode> crossClinicVerify = publicPost(
                "/api/v1/public/intake/" + clinicKeyB + "/verify",
                Map.of("code", formattedCode));
        assertThat(crossClinicVerify.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 6. Valid public verification returns ONLY clinic name, first name, and opaque session token
        ResponseEntity<JsonNode> verifyResp = publicPost(
                "/api/v1/public/intake/" + clinicKeyA + "/verify",
                Map.of("code", formattedCode.toLowerCase()));
        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode verifyBody = verifyResp.getBody();
        assertThat(verifyBody.path("valid").asBoolean()).isTrue();
        assertThat(verifyBody.path("patientFirstName").asText()).isEqualTo("Adam");
        assertThat(verifyBody.toString()).doesNotContain("+962790001122");
        String sessionToken = verifyBody.path("intakeSessionToken").asText();
        assertThat(sessionToken).isNotBlank();

        // 7. Reverse traversal check: intakeSessionToken cannot access internal clinic endpoints
        HttpHeaders reverseHeaders = new HttpHeaders();
        reverseHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + sessionToken);
        reverseHeaders.set(HttpHeaders.COOKIE, securityProperties.getSessionCookieName() + "=" + sessionToken);
        ResponseEntity<JsonNode> reverseAttempt = http.exchange(
                "/api/v1/clinic/patients", HttpMethod.GET, new HttpEntity<>(reverseHeaders), JsonNode.class);
        assertThat(reverseAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 8. Public submit creates PENDING_APPROVAL record and burns the code immediately
        ResponseEntity<JsonNode> submitResp = publicPost(
                "/api/v1/public/intake/" + clinicKeyA + "/submit",
                Map.of(
                        "intakeSessionToken", sessionToken,
                        "demographics", Map.of(
                                "fullName", "Adam Alshishani",
                                "dob", "1998-05-15",
                                "gender", "Male",
                                "occupation", "Software Engineer",
                                "address", "Amman, Jordan",
                                "phone", "+962790001122",
                                "email", "adam@example.test",
                                "emergencyName", "Sami Alshishani",
                                "emergencyPhone", "+962790009999"
                        ),
                        "medicalHistory", Map.ofEntries(
                                Map.entry("underTreatment", "Yes"),
                                Map.entry("treatmentSpecify", "BP Medication"),
                                Map.entry("hospitalizedLastYear", "No"),
                                Map.entry("hospitalizedReason", ""),
                                Map.entry("conditions", List.of("Diabetes", "High/Low Blood Pressure")),
                                Map.entry("otherCondition", ""),
                                Map.entry("pregnantOrBreastfeeding", "N/A"),
                                Map.entry("medications", List.of("Blood Thinners")),
                                Map.entry("otherMedications", ""),
                                Map.entry("allergies", List.of("Penicillin/Antibiotics")),
                                Map.entry("otherAllergies", "")
                        ),
                        "dentalHistory", Map.of(
                                "lastDentalVisitDate", "2026-01-10",
                                "lastVisitReason", "Checkup",
                                "symptoms", List.of("Dry mouth"),
                                "pastProcedures", "Root canal 2024",
                                "anxietyLevel", 3
                        ),
                        "chiefComplaint", Map.of(
                                "primaryComplaint", "Severe pain in lower right molar",
                                "painScale", 8,
                                "duration", "3 days",
                                "triggers", List.of()
                        )
                ));
        assertThat(submitResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitResp.getBody().path("submitted").asBoolean()).isTrue();
        assertThat(submitResp.getBody().path("status").asText()).isEqualTo("PENDING_APPROVAL");
        UUID intakeId = UUID.fromString(submitResp.getBody().path("intakeId").asText());

        // 9. Reusing the burned code or token must fail
        ResponseEntity<JsonNode> reuseCode = publicPost(
                "/api/v1/public/intake/" + clinicKeyA + "/verify",
                Map.of("code", plainCode));
        assertThat(reuseCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 10. Quarantine check: patient row is NOT yet mutated before doctor approval
        JsonNode patientBeforeApproval = get(adminA,
                "/api/v1/clinic/patients/" + patientId + "?projectId=" + InboxFixture.PROJECT_A1);
        assertThat(patientBeforeApproval.path("occupation").isMissingNode()
                || patientBeforeApproval.path("occupation").isNull()).isTrue();

        // 11. Doctor reviews Steps 1-4 (editing occupation slightly) and approves
        JsonNode historyList = get(adminA,
                "/api/v1/clinic/patients/" + patientId + "/history-records?projectId=" + InboxFixture.PROJECT_A1);
        assertThat(historyList.size()).isEqualTo(1);
        assertThat(historyList.get(0).path("status").asText()).isEqualTo("PENDING_APPROVAL");

        JsonNode approved = write(adminA, HttpMethod.POST,
                "/api/v1/clinic/patients/" + patientId + "/history-records?projectId=" + InboxFixture.PROJECT_A1,
                """
                {
                  "id": "%s",
                  "approve": true,
                  "isComplete": false,
                  "demographics": {
                    "fullName": "Adam Alshishani",
                    "dob": "1998-05-15",
                    "gender": "Male",
                    "occupation": "Lead Software Engineer",
                    "address": "Amman, Jordan",
                    "phone": "+962790001122",
                    "email": "adam@example.test",
                    "emergencyName": "Sami Alshishani",
                    "emergencyPhone": "+962790009999"
                  },
                  "medicalHistory": {
                    "underTreatment": "Yes",
                    "treatmentSpecify": "BP Medication",
                    "hospitalizedLastYear": "No",
                    "hospitalizedReason": "",
                    "conditions": ["Diabetes", "High/Low Blood Pressure"],
                    "otherCondition": "",
                    "pregnantOrBreastfeeding": "N/A",
                    "medications": ["Blood Thinners"],
                    "otherMedications": "",
                    "allergies": ["Penicillin/Antibiotics"],
                    "otherAllergies": ""
                  },
                  "dentalHistory": {
                    "lastDentalVisitDate": "2026-01-10",
                    "lastVisitReason": "Checkup",
                    "symptoms": ["Dry mouth"],
                    "pastProcedures": "Root canal 2024",
                    "anxietyLevel": 3
                  },
                  "chiefComplaint": {
                    "primaryComplaint": "Severe pain in lower right molar",
                    "painScale": 8,
                    "duration": "3 days",
                    "triggers": []
                  }
                }
                """.formatted(intakeId),
                HttpStatus.OK);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");

        // 12. Verify patient demographics, emergency contact, and medical alerts synced automatically!
        JsonNode patientAfter = get(adminA,
                "/api/v1/clinic/patients/" + patientId + "?projectId=" + InboxFixture.PROJECT_A1);
        assertThat(patientAfter.path("displayName").asText()).isEqualTo("Adam Alshishani");
        assertThat(patientAfter.path("gender").asText()).isEqualTo("MALE");
        assertThat(patientAfter.path("dateOfBirth").asText()).isEqualTo("1998-05-15");
        assertThat(patientAfter.path("occupation").asText()).isEqualTo("Lead Software Engineer");

        JsonNode rels = get(adminA,
                "/api/v1/clinic/patients/" + patientId + "/relationships?projectId=" + InboxFixture.PROJECT_A1);
        assertThat(rels.size()).isEqualTo(1);
        assertThat(rels.get(0).path("isEmergencyContact").asBoolean()).isTrue();
        assertThat(rels.get(0).path("relatedPersonName").asText()).isEqualTo("Sami Alshishani");

        JsonNode medHist = get(adminA,
                "/api/v1/clinic/patients/" + patientId + "/medical-history?projectId=" + InboxFixture.PROJECT_A1);
        assertThat(medHist.path("diabetes").asBoolean()).isTrue();
        assertThat(medHist.path("cardiacCondition").asBoolean()).isTrue();
        assertThat(medHist.path("allergies").asText()).contains("Penicillin/Antibiotics");
    }

    private ResponseEntity<JsonNode> publicPost(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    private JsonNode get(String cookie, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.COOKIE, cookie);
        ResponseEntity<JsonNode> response = http.exchange(
                path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode write(String cookie, HttpMethod method, String path, String body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(csrf.headerName, csrf.token);
        headers.set(HttpHeaders.COOKIE, cookie + "; " + csrf.cookie);
        ResponseEntity<JsonNode> response = http.exchange(
                path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("%s %s returned %s: %s", method, path, response.getStatusCode(), response.getBody())
                .isEqualTo(expected);
        return response.getBody();
    }

    private Csrf csrfToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/auth/csrf", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return new Csrf(
                setCookie.split(";", 2)[0],
                response.getBody().get("headerName").asText(),
                response.getBody().get("token").asText());
    }

    private String sessionFor(UUID userId) {
        BrowserSessionStore.IssuedSession issued =
                sessions.issue(userId, "patient-intake-test", "127.0.0.1", 1);
        return securityProperties.getSessionCookieName() + "=" + issued.rawToken();
    }

    private record Csrf(String cookie, String headerName, String token) {
    }
}
