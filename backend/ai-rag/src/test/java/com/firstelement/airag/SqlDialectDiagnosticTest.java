package com.firstelement.airag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Records what the named-parameter parser does to a PostgreSQL {@code ::} cast, and why this module's
 * statements spell every cast as {@code cast(x as y)}.
 *
 * <h2>How the rule was established</h2>
 *
 * <p>It was established by a failure, and the point of this suite is to keep the diagnosis from being
 * re-derived. The AI fixture's {@code ai_agent_versions} insert originally ended
 * {@code ... cast('[]'::jsonb), ...} and PostgreSQL refused it with {@code syntax error at or near ")"} at a
 * position pointing at the closing parenthesis of a cast that is, on its face, valid. Changing that one
 * fragment to {@code cast('[]' as jsonb)} made the statement apply.
 *
 * <p>So the rule is: <b>{@code cast(x as y)}, never {@code ::y}</b>, in any statement that goes through
 * {@code NamedParameterJdbcTemplate}. The reason a test exists rather than a comment is that the
 * <em>obvious</em> explanation is wrong on this Spring version — the substitution pass preserves
 * {@code ::} verbatim — so a reader inspecting the substituted SQL sees nothing wrong at all. The defect is
 * in the <b>parse</b> pass, which reads {@code :jsonb} as a named parameter, and it surfaces only as a
 * placeholder-count mismatch that PostgreSQL reports as a syntax error somewhere unrelated.
 *
 * <h2>What each assertion is for</h2>
 *
 * <ul>
 *   <li>the substitution <em>preserves</em> {@code ::} — recorded because it is the surprise, and it is why
 *       the failure was so hard to localise;</li>
 *   <li>the parse pass <em>misparses</em> {@code ::} — the mechanism, asserted rather than hypothesised;</li>
 *   <li>{@code cast(x as y)} and {@code cast(x as vector)} are untouched by both passes, which is the shape
 *       every statement in this module uses.</li>
 * </ul>
 */
class SqlDialectDiagnosticTest {

    @Test
    @DisplayName("the substitution pass PRESERVES ::, which is why the defect is invisible in the SQL")
    void substitutionPreservesDoubleColon() {
        String statement = "select cast(:v as jsonb) from t where x = cast('[]'::jsonb)";
        String substituted = NamedParameterUtils.substituteNamedParameters(statement,
            new MapSqlParameterSource().addValue("v", "x"));

        assertThat(substituted)
            .as("a '::' cast survives substitution verbatim, so the SQL a reader inspects looks correct")
            .isEqualTo("select cast(? as jsonb) from t where x = cast('[]'::jsonb)");
    }

    @Test
    @DisplayName("the parse pass MIS-PARSES :: as a named parameter, which is the actual defect")
    void parseMisparsesDoubleColon() {
        MapSqlParameterSource source = new MapSqlParameterSource().addValue("v", "x");
        ParsedSql parsed = NamedParameterUtils.parseSqlStatement(
            "select cast(:v as jsonb) from t where x = cast('[]'::jsonb)");

        // The substituted SQL shows one '?' and this parse finds a second parameter, so the driver is handed
        // more values than the statement has placeholders and PostgreSQL reports a syntax error at an
        // unrelated offset.
        assertThat(names(parsed, source))
            .as(":: must never be read as a parameter name")
            .doesNotContain("jsonb");
    }

    @Test
    @DisplayName("cast(x as y) is unaffected by both passes, which is why every statement uses it")
    void asCastIsUnaffected() {
        MapSqlParameterSource source = new MapSqlParameterSource().addValue("a", 1).addValue("b", "{}");
        String statement = "insert into t (\"a\", \"b\") values (:a, cast(:b as jsonb))";

        assertThat(NamedParameterUtils.substituteNamedParameters(statement, source))
            .isEqualTo("insert into t (\"a\", \"b\") values (?, cast(? as jsonb))");

        ParsedSql parsed = NamedParameterUtils.parseSqlStatement(statement);
        assertThat(names(parsed, source)).containsExactly("a", "b");
        assertThat(NamedParameterUtils.buildValueArray(parsed, source, null)).hasSize(2);
    }

    @Test
    @DisplayName("cast(x as vector) is equally unaffected, which is what pgvector retrieval uses")
    void vectorCastIsUnaffected() {
        MapSqlParameterSource source = new MapSqlParameterSource().addValue("embedding", "[1,2]");
        String statement = "select 1 - (c.\"embedding\" <=> cast(:embedding as vector)) as \"score\""
            + " from document_chunks c";

        assertThat(NamedParameterUtils.substituteNamedParameters(statement, source))
            .isEqualTo("select 1 - (c.\"embedding\" <=> cast(? as vector)) as \"score\""
                + " from document_chunks c");

        ParsedSql parsed = NamedParameterUtils.parseSqlStatement(statement);
        assertThat(names(parsed, source)).containsExactly("embedding");
    }

    /**
     * The parsed parameter names, read through the public API.
     *
     * <p>{@code ParsedSql.getParameterNames()} is package-private, so this goes through
     * {@code buildSqlParameterList} — which is the same list the JDBC template drives from, and therefore
     * the list whose contents caused the failure.
     */
    private static List<String> names(ParsedSql parsed, SqlParameterSource source) {
        return NamedParameterUtils.buildSqlParameterList(parsed, source).stream()
            .map(org.springframework.jdbc.core.SqlParameter::getName)
            .toList();
    }
}