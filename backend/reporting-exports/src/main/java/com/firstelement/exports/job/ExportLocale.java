package com.firstelement.exports.job;

import java.util.List;
import java.util.Locale;

/**
 * The {@code public."Locale"} enum of {@code V20260814000000}, mirrored as a Java enum so a typo fails
 * in review rather than at run time.
 *
 * <h2>Why the request locale is a closed set, not a free string</h2>
 *
 * <p>The product is Arabic-first and bilingual English/Arabic, and the database declares exactly two
 * locales. Every one of them is stored in a {@code "Locale"} column — {@code report_definitions.localeDefault},
 * {@code document_templates.locale}, {@code document_template_versions.locale}, {@code customers.locale} — so a
 * third value could never be written anywhere. A request that accepted {@code "fr"} would therefore be
 * accepted at the boundary, stored on nothing, and answered with whichever locale the report definition
 * happened to declare. That is the worst shape of failure this subsystem has: the file looks correct,
 * is in the wrong language, and nobody finds out until an Arabic customer's export is quoted to a
 * finance team in English with Arabic names transliterated by a spreadsheet.
 *
 * <p>So an unknown or unsupported locale is <b>refused</b> — {@link #require} throws an
 * {@link IllegalArgumentException} that names the two accepted values, which the HTTP layer renders as
 * {@code 400} with a body that tells the caller exactly what to send instead.
 *
 * <h2>Why it lives beside {@link ExportFormat}</h2>
 *
 * <p>Both are the closed vocabularies {@code POST /api/v1/exports} binds to, both are derived from a
 * database enum rather than invented, and both are the single source the OpenAPI document's {@code enum}
 * is generated from. One place to add a locale, and one place it is published from.
 *
 * <h2>Matching is case-insensitive and the stored value is lower-case</h2>
 *
 * <p>{@code "AR"}, {@code "Ar"} and {@code " ar "} all resolve to {@link #AR}, which is stored as
 * {@code "ar"} — byte-identical to what PostgreSQL holds for the enum. Normalising rather than refusing
 * on case is deliberate: a casing slip is a client bug with an unambiguous fix, and a 400 for
 * {@code "AR"} teaches nobody anything.
 */
public enum ExportLocale {

    /** Arabic. The product's first language; the default every declaration falls back to. */
    AR("ar"),

    /** English. */
    EN("en");

    private final String wireValue;

    ExportLocale(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The value stored in a {@code "Locale"} column. Identical to PostgreSQL's enum label.
     *
     * <p>{@code @JsonValue} because the constant names are upper-case ({@code AR}) and the wire values are
     * lower-case ({@code ar}). Without it a {@code List<ExportLocale>} in
     * {@link com.firstelement.exports.catalog.ExportCatalogue} would serialise as {@code ["AR","EN"]} while
     * {@link #acceptedValues()} — the set {@code POST /api/v1/exports} refuses against and the set the
     * OpenAPI document publishes on {@code CreateExportRequest.locale} — is {@code ["ar","en"]}. Two
     * vocabularies for one concept, in two schemas, is exactly the drift this annotation removes.
     */
    @com.fasterxml.jackson.annotation.JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** The {@link Locale} the renderers format with. */
    public java.util.Locale toLocale() {
        return Locale.forLanguageTag(wireValue);
    }

    /** The value a request must send: lower-case, matching the stored value. */
    public static List<String> acceptedValues() {
        return List.of(AR.wireValue, EN.wireValue);
    }

    /** The locale a declaration falls back to when a request names none. Arabic-first. */
    public static ExportLocale defaultLocale() {
        return AR;
    }

    /** The locale a string names, or {@code null} when it names none of ours. */
    public static ExportLocale fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ExportLocale locale : values()) {
            if (locale.wireValue.equals(normalized)) {
                return locale;
            }
        }
        return null;
    }

    /**
     * The locale a caller may ask for, refusing an unknown one.
     *
     * <p>{@code null} and blank mean "no preference" and resolve to {@link #defaultLocale()} rather than
     * failing: a create request that omits {@code locale} is not asking for French, and the report
     * definition's own declared default is the right answer.
     *
     * @throws IllegalArgumentException naming the accepted values, when the request names one that is not
     *                                  declared. Never a fallback — see the class comment for why a
     *                                  fallback here is the worst outcome in this subsystem.
     */
    public static ExportLocale require(String value) {
        if (value == null || value.isBlank()) {
            return defaultLocale();
        }
        ExportLocale locale = fromWireValue(value);
        if (locale == null) {
            throw new IllegalArgumentException(
                "Export locale " + com.firstelement.exports.catalog.RefusalText.of(value)
                    + " is not declared. Accepted locales are "
                    + acceptedValues() + ". A locale the database cannot store cannot be rendered, and a "
                    + "request that named one and received another language's file anyway would be worse "
                    + "than this refusal.");
        }
        return locale;
    }
}