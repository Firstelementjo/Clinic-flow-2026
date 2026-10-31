package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstelement.airag.policy.AiPolicySnapshot;
import com.firstelement.airag.policy.AiRefusalReason;
import com.firstelement.airag.redaction.AiRedactionEngine;
import com.firstelement.airag.redaction.AiRedactionFailedException;
import com.firstelement.airag.redaction.RedactionOutcome;
import com.firstelement.airag.redaction.RedactionPolicyDefinition;
import com.firstelement.airag.redaction.RedactionRule;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Redaction of prompts and responses, and what happens when it cannot be completed.
 *
 * <h2>Why a redaction failure must be a failure</h2>
 *
 * <p>A compliance report is generated from {@code ai_redaction_records}. A run that failed redaction and
 * recorded nothing reads as "no sensitive data was present", which is the most dangerous false statement
 * this system can make. So {@link AiRedactionFailedException} is a refusal, and it is asserted to be one
 * rather than left to review.
 *
 * <h2>The Arabic-first product constraint shows up here</h2>
 *
 * <p>Several rules have an Arabic label form alongside the English one, because the product is Arabic-first
 * and a redaction rule set that only recognises English labels would not fire on an Arabic patient record.
 */
class RedactionEngineTest {

    private final AiRedactionEngine engine = new AiRedactionEngine();

    @Nested
    @DisplayName("policy resolution")
    class Resolution {

        @Test
        @DisplayName("the declared policies are a closed list")
        void declaredPoliciesAreClosed() {
            assertThat(RedactionPolicyDefinition.declaredNames())
                .containsExactly("STRICT_CLINICAL", "CLINICAL", "LIGHT");
            assertThat(RedactionPolicyDefinition.byName("nonsense")).isNull();
            assertThat(RedactionPolicyDefinition.byName(null)).isNull();
            assertThat(RedactionPolicyDefinition.byName("")).isNull();
        }

        @Test
        @DisplayName("moving to a stricter policy never LOSES a rule")
        void stricterPoliciesAreSupersets() {
            // STRICT_CLINICAL is the union of the two narrow policies, so a tenant moving to it keeps
            // every rule it had. Asserted by rule ID rather than by RedactionRule: a record's generated
            // equals() delegates to Pattern.equals(), which Pattern does not override, so two
            // structurally identical rules are unequal and a comparison here would decide on identity.
            assertThat(ruleIds(RedactionPolicyDefinition.STRICT_CLINICAL))
                .containsAll(ruleIds(RedactionPolicyDefinition.CLINICAL));
            assertThat(ruleIds(RedactionPolicyDefinition.STRICT_CLINICAL))
                .containsAll(ruleIds(RedactionPolicyDefinition.LIGHT));
        }

        @Test
        @DisplayName("the narrow policies are ALTERNATIVES, not a ladder, and that is deliberate")
        void narrowPoliciesAreNotNested() {
            // LIGHT is credentials-only and CLINICAL is identifiers-only, so neither contains the other.
            // A ladder CLINICAL < LIGHT would force LIGHT to also carry clinical identifiers and make the
            // "reviewed knowledge base" escape hatch a lie. Recorded here so the absence is a decision
            // rather than an oversight nobody notices until a tenant picks LIGHT expecting strictness.
            assertThat(ruleIds(RedactionPolicyDefinition.CLINICAL))
                .doesNotContainAnyElementsOf(ruleIds(RedactionPolicyDefinition.LIGHT));
            assertThat(ruleIds(RedactionPolicyDefinition.LIGHT))
                .doesNotContainAnyElementsOf(ruleIds(RedactionPolicyDefinition.CLINICAL));
        }

        @Test
        @DisplayName("a rule in two policies is the SAME pattern instance, so one id means one behaviour")
        void sharedRulesAreSharedInstances() {
            assertThat(RedactionPolicyDefinition.STRICT_CLINICAL.rules())
                .as("otherwise a rule id would describe two different behaviours and the compliance "
                    + "report would be describing a rule that never ran")
                .filteredOn(r -> r.id().equals("PHONE"))
                .allSatisfy(rule -> assertThat(rule.pattern())
                    .isSameAs(RedactionPolicyDefinition.CLINICAL.rules().stream()
                        .filter(other -> other.id().equals("PHONE")).findFirst().orElseThrow().pattern()));
        }

        @Test
        @DisplayName("an unknown policy name is a REFUSAL, never a fallback")
        void unknownPolicyIsRefused() {
            AiPolicySnapshot unknown = snapshot("A-POLICY-NOBODY-IMPLEMENTED");

            assertThatThrownBy(() -> engine.policyFor(unknown))
                .isInstanceOf(com.firstelement.airag.policy.AiRefusedException.class)
                .satisfies(thrown -> {
                    var refusal = (com.firstelement.airag.policy.AiRefusedException) thrown;
                    assertThat(refusal.reason()).isEqualTo(AiRefusalReason.REDACTION_FAILED);
                    // The message names the declared policies so an administrator can fix the row without
                    // reading this source.
                    assertThat(refusal.detail()).contains("STRICT_CLINICAL").contains("CLINICAL");
                });
        }

        @Test
        @DisplayName("a declared fallback is also refused when it is not a declared policy")
        void unknownFallbackIsRefused() {
            AiPolicySnapshot unknown = snapshot("NOT-A-POLICY");

            assertThatThrownBy(() -> engine.policyForOrRefuse(unknown, "ALSO-NOT-A-POLICY"))
                .isInstanceOf(com.firstelement.airag.policy.AiRefusedException.class)
                .satisfies(thrown -> assertThat(((com.firstelement.airag.policy.AiRefusedException) thrown)
                    .reason()).isEqualTo(AiRefusalReason.REDACTION_FAILED));
        }

        @Test
        @DisplayName("a snapshot with no policy at all is refused")
        void nullPolicyIsRefused() {
            assertThatThrownBy(() -> engine.policyForOrRefuse(null, "STRICT_CLINICAL"))
                .isInstanceOf(com.firstelement.airag.policy.AiRefusedException.class);
        }
    }

    @Nested
    @DisplayName("redacting text")
    class Redacting {

        @Test
        @DisplayName("a credential in a prompt is removed and counted")
        void credentialsAreRemoved() {
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_SYSTEM,
                "اسأل عن العميل، المفتاح api_key=sk-live-ABCDEFGHIJKLMNOP12345");

            assertThat(outcome.text()).doesNotContain("sk-live-ABCDEFGHIJKLMNOP12345");
            assertThat(outcome.text()).contains("[REDACTED]");
            assertThat(outcome.totalValues()).isPositive();
            assertThat(outcome.applied()).extracting(RedactionOutcome.Applied::ruleId)
                .contains("CREDENTIAL_ASSIGNMENT");
        }

        @Test
        @DisplayName("a national id, an email and a phone are removed under STRICT_CLINICAL")
        void clinicalIdentifiersAreRemoved() {
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_UNTRUSTED,
                "الهوية 1012345678 والبريد a.patient@example.com والهاتف 0551234567");

            assertThat(outcome.text()).doesNotContain("1012345678");
            assertThat(outcome.text()).doesNotContain("a.patient@example.com");
            assertThat(outcome.text()).doesNotContain("0551234567");
            assertThat(outcome.totalValues()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("an ARABIC date-of-birth label is recognised, because the product is Arabic-first")
        void arabicLabelsAreRecognised() {
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.CLINICAL,
                AiRedactionEngine.STAGE_UNTRUSTED, "تاريخ الميلاد 1984-02-11");

            assertThat(outcome.text()).doesNotContain("1984-02-11");
            assertThat(outcome.applied()).extracting(RedactionOutcome.Applied::ruleId)
                .contains("DATE_OF_BIRTH_LABEL");
        }

        @Test
        @DisplayName("a LIGHT policy leaves clinical content alone but still removes a credential")
        void lightPolicyIsNarrowerButStillSafe() {
            String text = "الهوية 1012345679 والمفتاح api_key=sk-live-ZYXWVUTSRQPONMLK98765";

            RedactionOutcome light = engine.redact(RedactionPolicyDefinition.LIGHT,
                AiRedactionEngine.STAGE_RESPONSE, text);

            assertThat(light.text()).doesNotContain("sk-live-ZYXWVUTSRQPONMLK98765");
            assertThat(light.text()).as("a LIGHT policy does not claim clinical coverage").contains("1012345679");
        }

        @Test
        @DisplayName("a timestamp is NOT redacted as a phone number")
        void timestampsSurvive() {
            // A regression, found because a test tool result came back as
            // {"lastMessageAt"="[REDACTED]:22:24.481"}. The PHONE rule's digit run matches a date-time
            // shape, and a redacted audit timestamp is a diagnostic destroyed rather than a privacy gain.
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE, "2026-10-06 00:22:24.481");

            assertThat(outcome.text()).isEqualTo("2026-10-06 00:22:24.481");
            assertThat(outcome.totalValues()).isZero();
        }

        @Test
        @DisplayName("an ISO timestamp is NOT redacted either")
        void isoTimestampsSurvive() {
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE, "2026-10-06T00:22:24Z");

            assertThat(outcome.text()).isEqualTo("2026-10-06T00:22:24Z");
        }

        @Test
        @DisplayName("a long phone number IS still redacted, so the timestamp fix did not disarm the rule")
        void phoneNumbersAreStillCaught() {
            // The shapes are deliberately ones CARD_NUMBER and SAUDI_NATIONAL_ID cannot swallow first:
            // both need an unbroken digit run of 13+ or exactly 10, and "+966 55 123 4567" has neither.
            // A rule that only survives because a broader rule happened to run before it is not a rule.
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE, "اتصل على +966 55 123 4567");

            assertThat(outcome.text()).doesNotContain("+966 55 123 4567");
            assertThat(outcome.applied()).extracting(RedactionOutcome.Applied::ruleId)
                .as("PHONE must be what removes it, not a neighbour that ran first")
                .containsExactly("PHONE");
        }

        @Test
        @DisplayName("an international number with no separators is still redacted")
        void longDigitRunsAreStillCaught() {
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE, "009665123456789");

            assertThat(outcome.text()).doesNotContain("009665123456789");
        }

        @Test
        @DisplayName("the removal is the SAME placeholder regardless of what was removed")
        void placeholderCarriesNoInformation() {
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE,
                "a@x.test 1012345671 b@y.test 1012345672");

            assertThat(outcome.text()).doesNotContain("a@x.test").doesNotContain("b@y.test");
            // Every removal is the identical token, so neither the character count of the redaction nor
            // the token itself reveals what was removed. Asserting doesNotContain("[REDACTED]") here would
            // be asserting the opposite of what this test is for.
            assertThat(outcome.text().split(" "))
                .as("identical placeholders, so the length of the redaction reveals nothing")
                .containsOnly(RedactionRule.placeholder());
            assertThat(outcome.totalValues()).isEqualTo(4);
        }

        @Test
        @DisplayName("empty and null text are not redacted, and record nothing")
        void emptyTextRecordsNothing() {
            RedactionOutcome nullText = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_SYSTEM, null);
            RedactionOutcome emptyText = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_SYSTEM, "");

            assertThat(nullText.totalValues()).isZero();
            assertThat(emptyText.totalValues()).isZero();
            assertThat(nullText.applied()).isEmpty();
        }

        @Test
        @DisplayName("an UNKNOWN stage is refused, because fieldPath is a compliance surface")
        void unknownStageIsRefused() {
            assertThatThrownBy(() -> engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                "somewhere.else", "text"))
                .isInstanceOf(AiRedactionFailedException.class)
                .hasMessageContaining("REDACTION_FAILED");
        }

        @Test
        @DisplayName("a null policy is refused rather than applied as no-op")
        void nullPolicyIsRefused() {
            assertThatThrownBy(() -> engine.redact(null, AiRedactionEngine.STAGE_SYSTEM, "text"))
                .isInstanceOf(AiRedactionFailedException.class);
        }
    }

    @Nested
    @DisplayName("composing outcomes")
    class Composing {

        @Test
        @DisplayName("totals sum across sections, and merged records key by rule and stage")
        void totalsAndMerges() {
            RedactionOutcome system = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_SYSTEM, "api_key=sk-live-AAAAAAAAAAAAAAAA");
            RedactionOutcome response = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE, "api_key=sk-live-BBBBBBBBBBBBBBBBBB");

            assertThat(engine.totalValues(List.of(system, response))).isEqualTo(2);
            assertThat(engine.merge(List.of(system, response)))
                .as("two stages, so two distinct keys")
                .hasSize(2);
        }

        @Test
        @DisplayName("merging the SAME rule and stage sums its counts rather than overwriting")
        void mergingSameKeySums() {
            RedactionOutcome first = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE, "a@x.test");
            RedactionOutcome second = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE, "b@y.test");

            assertThat(engine.merge(List.of(first, second)))
                .hasSize(1);
            assertThat(engine.merge(List.of(first, second)).values().iterator().next().valuesRedacted())
                .isEqualTo(2);
        }

        @Test
        @DisplayName("a null in the list is skipped rather than throwing")
        void nullOutcomesAreSkipped() {
            RedactionOutcome outcome = engine.redact(RedactionPolicyDefinition.STRICT_CLINICAL,
                AiRedactionEngine.STAGE_RESPONSE, "a@x.test");

            assertThat(engine.totalValues(java.util.Arrays.asList(outcome, null))).isEqualTo(1);
            assertThat(engine.merge(java.util.Arrays.asList(null, outcome))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("retrieved content")
    class Retrieved {

        @Test
        @DisplayName("retrieved chunks are redacted together, so the whole prompt section is covered")
        void chunksAreRedactedTogether() {
            var chunks = List.of(
                chunk("الهوية 1012345673"),
                chunk("api_key=sk-live-CCCCCCCCCCCCCCCCCCCC"));

            RedactionOutcome outcome = engine.redactUntrustedContent(
                RedactionPolicyDefinition.STRICT_CLINICAL, chunks);

            assertThat(outcome.text()).doesNotContain("1012345673");
            assertThat(outcome.text()).doesNotContain("sk-live-CCCCCCCCCCCCCCCCCCCC");
            assertThat(outcome.fieldPaths())
                .as("every record names the stage, so a report can say WHERE content was removed")
                .containsExactly(AiRedactionEngine.STAGE_UNTRUSTED);
        }

        @Test
        @DisplayName("no chunks is an empty outcome, not a failure")
        void noChunksIsEmpty() {
            RedactionOutcome outcome = engine.redactUntrustedContent(
                RedactionPolicyDefinition.STRICT_CLINICAL, List.of());

            assertThat(outcome.totalValues()).isZero();
            assertThat(outcome.text()).isEmpty();
        }

        private com.firstelement.airag.retrieval.RetrievedChunk chunk(String content) {
            return new com.firstelement.airag.retrieval.RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Document", content, 0.9d, "READY", null);
        }
    }

    /** A snapshot whose approved version names a specific redaction policy. */
    private static AiPolicySnapshot snapshot(String redactionPolicy) {
        return new AiPolicySnapshot(UUID.randomUUID(), UUID.randomUUID(), "default", redactionPolicy,
            "AI_PROVENANCE", 4000, 4,
            List.of(com.firstelement.airag.provider.AiProviderKind.MOCK), "LOCAL_ONLY",
            true, true, true, true, 1);
    }

    /** Rule ids only, so a superset assertion compares meaning rather than Pattern identity. */
    private static List<String> ruleIds(RedactionPolicyDefinition policy) {
        return policy.rules().stream().map(RedactionRule::id).toList();
    }
}