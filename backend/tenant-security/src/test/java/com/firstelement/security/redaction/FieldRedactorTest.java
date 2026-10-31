package com.firstelement.security.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.security.permission.Permission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Field-level redaction, which FR-7.7 and FR-8.2 require to happen before output.
 */
class FieldRedactorTest {

    private static Map<String, Object> customerRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "11111111-1111-1111-1111-111111111111");
        row.put("cxId", "C-0001");
        row.put("displayName", "Acme Clinic");
        row.put("primaryPhone", "+966500000000");
        row.put("notes", "VIP patient");
        row.put("externalIds", Map.of("whatsapp", "+966500000000"));
        row.put("passwordHash", "$2a$12$never");
        row.put("tokenHash", "deadbeef");
        return row;
    }

    @Test
    @DisplayName("credentials and secrets are redacted unconditionally, even for ADMIN")
    void neverExposedColumnsAreAlwaysRedacted() {
        Map<String, Object> redacted = FieldRedactor.redact(customerRow(), FieldRedactionPolicy.none());

        assertThat(redacted).containsEntry("passwordHash", FieldRedactionPolicy.PLACEHOLDER);
        assertThat(redacted).containsEntry("tokenHash", FieldRedactionPolicy.PLACEHOLDER);
        assertThat(redacted).containsEntry("displayName", "Acme Clinic");
    }

    @Test
    @DisplayName("a redacted column is replaced, never dropped or nulled")
    void redactionIsIndistinguishableFromAnyOtherRedaction() {
        FieldRedactionPolicy policy = FieldRedactionPolicy.of(Set.of("primaryPhone", "notes"));
        Map<String, Object> row = customerRow();
        row.remove("primaryPhone");
        Map<String, Object> redacted = FieldRedactor.redact(row, policy);

        // The column is absent from this row entirely, yet the redacted map does not gain
        // a null or an empty string for it: "hidden" and "absent" stay indistinguishable.
        assertThat(redacted).doesNotContainKey("primaryPhone");
        assertThat(redacted).containsEntry("notes", FieldRedactionPolicy.PLACEHOLDER);
        assertThat(redacted).doesNotContainValue(null);
    }

    @Test
    @DisplayName("every redacted column gets the same constant placeholder")
    void thePlaceholderCarriesNoInformation() {
        Map<String, Object> redacted = FieldRedactor.redact(customerRow(),
            FieldRedactionPolicy.of(Set.of("primaryPhone", "notes", "displayName")));

        assertThat(redacted.get("primaryPhone")).isEqualTo(FieldRedactionPolicy.PLACEHOLDER);
        assertThat(redacted.get("notes")).isEqualTo(FieldRedactionPolicy.PLACEHOLDER);
        assertThat(redacted.get("displayName")).isEqualTo(FieldRedactionPolicy.PLACEHOLDER);
    }

    @Test
    @DisplayName("the caller can record which columns were hidden, for an export or an event")
    void redactionIsReportable() {
        FieldRedactor.RedactedRow result = FieldRedactor.redactWithReport(customerRow(),
            FieldRedactionPolicy.of(Set.of("primaryPhone")));

        assertThat(result.redactedColumns())
            .contains("primaryPhone", "passwordHash", "tokenHash")
            .doesNotContain("displayName");
        assertThat(result.row()).containsEntry("displayName", "Acme Clinic");
    }

    @Test
    @DisplayName("projection drops every column that is not explicitly allowed")
    void projectionIsAnAllowList() {
        Map<String, Object> projected = FieldRedactor.project(customerRow(),
            Set.of("id", "cxId", "displayName", "passwordHash"), FieldRedactionPolicy.none());

        assertThat(projected).containsOnlyKeys("id", "cxId", "displayName");
    }

    @Test
    @DisplayName("a per-permission column policy fails closed when the permission is missing")
    void perPermissionColumnsHideWhenTheGrantIsAbsent() {
        List<FieldRedactionPolicy.Column> columns = List.of(
            new FieldRedactionPolicy.Column("displayName", Permission.CUSTOMER_READ),
            new FieldRedactionPolicy.Column("primaryPhone", Permission.CUSTOMER_READ),
            new FieldRedactionPolicy.Column("costBasis", Permission.ANALYTICS_READ_ORGANIZATION));

        Set<Permission> employee = Set.of(Permission.CUSTOMER_READ);
        FieldRedactionPolicy employeePolicy = FieldRedactionPolicy.forColumns(employee, columns);
        assertThat(employeePolicy.redacted()).contains("costBasis").doesNotContain("displayName");

        Set<Permission> finance = Set.of(Permission.CUSTOMER_READ,
            Permission.ANALYTICS_READ_ORGANIZATION);
        assertThat(FieldRedactionPolicy.forColumns(finance, columns).redacted()).isEmpty();

        // A principal with nothing at all sees nothing.
        assertThat(FieldRedactionPolicy.forColumns(Set.of(), columns).redacted())
            .containsExactlyInAnyOrder("displayName", "primaryPhone", "costBasis");
    }

    @Test
    @DisplayName("the sensitive-key list catches the names a future caller is likely to add")
    void sensitiveKeyDetectionIsGenerous() {
        assertThat(com.firstelement.security.event.SecurityEventSanitizer.isSensitive("passwordHash")).isTrue();
        assertThat(com.firstelement.security.event.SecurityEventSanitizer.isSensitive("sessionToken")).isTrue();
        assertThat(com.firstelement.security.event.SecurityEventSanitizer.isSensitive("X-CSRF-TOKEN")).isTrue();
        assertThat(com.firstelement.security.event.SecurityEventSanitizer.isSensitive("api_key")).isTrue();
        assertThat(com.firstelement.security.event.SecurityEventSanitizer.isSensitive("authorization")).isTrue();
        assertThat(com.firstelement.security.event.SecurityEventSanitizer.isSensitive("encryptionKey")).isTrue();
        assertThat(com.firstelement.security.event.SecurityEventSanitizer.isSensitive("scope")).isFalse();
        assertThat(com.firstelement.security.event.SecurityEventSanitizer.isSensitive("projectId")).isFalse();
    }
}