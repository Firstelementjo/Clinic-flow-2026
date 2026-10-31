package com.firstelement.crm.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The domain error contract, with no database.
 *
 * <p>The statuses are part of the wire contract, and the two denials must stay distinct:
 * a row outside the caller's scope is 404 (indistinguishable from "not there"), while a stale
 * write is 409 with the current version so the caller can recover. Collapsing them would make
 * either a lost update or an existence oracle.
 */
class CrmDomainExceptionTest {

    @Test
    @DisplayName("a missing row is 404 with a machine code")
    void notFound() {
        CrmDomainException ex = new ResourceNotFoundException("Conversation");

        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.code()).isEqualTo("NOT_FOUND");
        assertThat(ex.getMessage()).isEqualTo("Conversation not found");
        assertThat(ex.details()).isEmpty();
    }

    @Test
    @DisplayName("the not-found message never names a tenant, a role or a permission")
    void notFoundIsNonDisclosing() {
        String message = new ResourceNotFoundException("Customer").getMessage();

        assertThat(message).doesNotContain("organization").doesNotContain("project")
            .doesNotContain("role").doesNotContain("permission");
    }

    @Test
    @DisplayName("a domain rule violation is 400 VALIDATION_FAILED")
    void validation() {
        CrmDomainException ex = new ValidationFailedException("That employee is not a member of this project");

        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a validation failure can carry its own code and field details")
    void validationWithCode() {
        CrmDomainException ex = ValidationFailedException.code("INVALID_CURSOR",
            "cursor does not identify a conversation in scope", Map.of("cursor", "abc"));

        assertThat(ex.code()).isEqualTo("INVALID_CURSOR");
        assertThat(ex.details()).containsEntry("cursor", "abc");
    }

    @Test
    @DisplayName("a collision is 409 and tells the caller which version won")
    void versionConflict() {
        CrmDomainException ex = new VersionConflictException("Conversation",
            "2026-08-14T09:30:15.123Z", "2026-08-14T09:31:00.000Z");

        assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(ex.details())
            .containsEntry("resource", "Conversation")
            .containsEntry("expectedVersion", "2026-08-14T09:30:15.123Z")
            .containsEntry("currentVersion", "2026-08-14T09:31:00.000Z");
    }

    @Test
    @DisplayName("a collision is explicitly not retryable")
    void collisionIsNotRetryable() {
        assertThat(new VersionConflictException("Conversation", 1, 2).retryable()).isFalse();
    }

    @Test
    @DisplayName("the collision message says what to do without naming a colleague")
    void collisionMessageIsActionable() {
        String message = new VersionConflictException("Conversation", 1, 2).getMessage();

        assertThat(message).contains("Re-read").contains("Conversation");
        assertThat(message).doesNotContain("employee").doesNotContain("organization");
    }

    @Test
    @DisplayName("details are defensively copied and cannot be mutated afterwards")
    void detailsAreImmutable() {
        java.util.Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("k", "v");
        CrmDomainException ex = new ValidationFailedException("x", mutable);

        mutable.put("k2", "v2");

        assertThat(ex.details()).hasSize(1);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> ex.details().put("k3", "v3"))).isNotNull();
    }
}