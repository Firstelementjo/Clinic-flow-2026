package com.firstelement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Writes the real OpenAPI document to {@code build/openapi/openapi.json}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Phase 11 generates a typed Angular client from the API contract. The committed
 * {@code tests/contracts/openapi-paths.json} is a <em>route inventory</em> — it answers "which paths
 * exist" and nothing else. A client generator needs schemas: field names, types, nullability, enums,
 * and which operation consumes which body. Without a document the generator would either invent
 * types or be skipped, and a generated client that is half-invented is worse than none.
 *
 * <p>The path is {@code /api/v1/openapi}, not the springdoc default {@code /v3/api-docs}: this project
 * deliberately versions the document alongside the API it describes (see {@code application.yml}). Asking for the
 * default path returns 404, which reads as "no document" rather than "wrong path".
 *
 * <p>The document is produced by asking the running application for it, through the same MockMvc the
 * smoke test uses, so it is the contract the application actually serves rather than a description of
 * it. springdoc builds it from the controllers, the DTOs and the annotations, which means a signature
 * change is reflected without anyone editing a document.
 *
 * <h2>Why the write goes to build/ and not to the repository</h2>
 *
 * <p>A test that mutates a tracked file fails in CI, where the checkout is read-only, and it makes the
 * working tree dirty on every run. So this test only ever writes under {@code build/}. Promotion into
 * {@code tests/contracts/openapi.json} is a deliberate act performed by
 * {@code ops/quality/checks/export-openapi-document.ps1}, and {@code check-openapi-contract.ps1}
 * compares the committed copy against a fresh export so the two cannot drift apart unnoticed.
 *
 * <h2>Database safety</h2>
 *
 * <p>This test needs no database. It asserts nothing about persistence; it asks the web layer for its
 * own description of itself. It therefore deliberately does NOT stamp or migrate any database, and it
 * does not depend on the dev database being in any particular state. The dev {@code firstelement}
 * database is checked separately by {@code verify-schema-state.ps1}, which asserts the pre-boot profile
 * and would fail loudly if a routine test run ever stamped it.
 */
@SpringBootTest(properties = {
    "firstelement.environment=TEST",
    "management.health.db.enabled=false",
    "management.health.redis.enabled=false",
    // ADR-0005 §8 binds the operator surface -- actuator and this document -- to a separate
    // MANAGEMENT_PORT, so `/v3/api-docs` is NOT on the tenant-facing servlet context that MockMvc
    // builds, and the request returns 404. Overriding it here is deliberate and does not change the
    // document: WHICH PORT the contract is published on is a deployment decision, not part of the
    // contract. It is bound to the main context here so the test needs no real listening socket, which
    // in turn means no database is touched and no Flyway stamp can occur as a side effect of asking
    // for a schema.
    "springdoc.use-management-port=false"
})
// Filters are off deliberately. The document answers "what does this API look like", and asking that
// question does not require a tenant to be signed in -- on the management port in a real deployment it does
// not. With the filter chain active the request is 401, which is a correct answer to a different question and
// would only mean this test cannot read its own API surface. Nothing else is weakened by taking the
// filters out of THIS test's path.
@AutoConfigureMockMvc(addFilters = false)
class OpenApiDocumentExportTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void writesTheOpenApiDocument() throws Exception {
        byte[] document = mvc.perform(get("/api/v1/openapi"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

        String text = new String(document, StandardCharsets.UTF_8);

        // A document that parsed but describes nothing is not a contract. Assert the shape that a
        // client generator actually consumes, so an empty or near-empty document cannot be promoted.
        assertThatContains(text, "\"openapi\"");
        assertThatContains(text, "\"paths\"");
        assertThatContains(text, "\"components\"");
        assertThatContains(text, "/api/v1/conversations");
        assertThatContains(text, "/api/v1/auth/login");

        Path target = Path.of("build", "openapi", "openapi.json");
        Files.createDirectories(target.getParent());
        Files.write(target, document);
    }

    private static void assertThatContains(String haystack, String needle) {
        if (!haystack.contains(needle)) {
            throw new AssertionError(
                "the OpenAPI document does not contain " + needle + ". A contract that omits its "
                    + "components is not a contract a client can be generated from, so this is "
                    + "promotion-blocking rather than a warning.");
        }
    }
}