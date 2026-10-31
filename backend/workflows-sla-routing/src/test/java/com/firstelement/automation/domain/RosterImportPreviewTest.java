package com.firstelement.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Roster import: parse, map, validate, and never silently drop a row.
 *
 * <p>The premise is the legacy one: "never blindly import malformed rows. A silent skip means an ops
 * manager believes 40 agents exist when 37 do, and discovers the gap when escalations start
 * queueing."
 */
class RosterImportPreviewTest {

    private static final String CSV = """
        Email,Full Name,Department,Capacity,Skills,Working Hours
        layla@a.test,Layla Ahmed,billing,5,"refunds; chargebacks","Mon 09:00-17:00; Tue 09:00-13:00"
        omar@a.test,Omar Nasser,support,3,technical,
        bad-row
        not-an-email,Fred,,,,
        """;

    @Test
    @DisplayName("a quoted cell with commas and a semicolon list survives parsing")
    void parsesQuotedCells() {
        RosterImport.Sheet sheet = RosterImport.parseCsv(CSV);

        assertThat(sheet.headers()).containsExactly("Email", "Full Name", "Department", "Capacity",
            "Skills", "Working Hours");
        assertThat(sheet.rows()).hasSize(4);
        assertThat(sheet.rows().get(0).rowNumber()).isEqualTo(1);
        assertThat(sheet.rows().get(0).values().get("Skills")).isEqualTo("refunds; chargebacks");
    }

    @Test
    @DisplayName("row numbers are 1-based with the header excluded")
    void rowNumbers() {
        assertThat(RosterImport.parseCsv(CSV).rows()).extracting(RosterImport.SheetRow::rowNumber)
            .containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("an empty file parses to an empty sheet, not an exception")
    void emptyFile() {
        assertThat(RosterImport.parseCsv("").rows()).isEmpty();
        assertThat(RosterImport.parseCsv(null).headers()).isEmpty();
        assertThat(RosterImport.parseCsv("\n\n  \n").rows()).isEmpty();
    }

    @Test
    @DisplayName("a header is suggested through its aliases, in English or Arabic")
    void columnMapping() {
        RosterImport.Sheet english = RosterImport.parseCsv(CSV);
        RosterImport.ColumnMapping suggested = RosterImport.suggestMapping(english.headers());

        assertThat(suggested.columnFor(RosterImport.Field.EMAIL)).isEqualTo("Email");
        assertThat(suggested.columnFor(RosterImport.Field.FULL_NAME)).isEqualTo("Full Name");
        assertThat(suggested.columnFor(RosterImport.Field.CAPACITY)).isEqualTo("Capacity");
        assertThat(suggested.columnFor(RosterImport.Field.SKILLS)).isEqualTo("Skills");
        assertThat(suggested.columnFor(RosterImport.Field.WINDOWS)).isEqualTo("Working Hours");

        RosterImport.Sheet arabic = RosterImport.parseCsv(
            "البريد,الاسم الكامل,القسم,المهارات\nx@a.test,Name,dept,skill");
        RosterImport.ColumnMapping arabicMapping =
            RosterImport.suggestMapping(arabic.headers());
        assertThat(arabicMapping.columnFor(RosterImport.Field.EMAIL)).isEqualTo("البريد");
        assertThat(arabicMapping.columnFor(RosterImport.Field.FULL_NAME)).isEqualTo("الاسم الكامل");
        assertThat(arabicMapping.columnFor(RosterImport.Field.SKILLS)).isEqualTo("المهارات");
    }

    @Test
    @DisplayName("an underscore or hyphen in a header still maps")
    void normalizesSeparators() {
        RosterImport.ColumnMapping mapping = RosterImport.suggestMapping(
            RosterImport.parseCsv("e-mail,full_name,max_conversations\na,b,3").headers());

        assertThat(mapping.columnFor(RosterImport.Field.EMAIL)).isEqualTo("e-mail");
        assertThat(mapping.columnFor(RosterImport.Field.FULL_NAME)).isEqualTo("full_name");
        assertThat(mapping.columnFor(RosterImport.Field.CAPACITY)).isEqualTo("max_conversations");
    }

    @Test
    @DisplayName("every valid row is returned, with its capacity and skills parsed")
    void validRows() {
        RosterImport.Sheet sheet = RosterImport.parseCsv(CSV);
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of());

        assertThat(preview.totalRows()).isEqualTo(4);
        assertThat(preview.validRows()).extracting(row -> row.record().email())
            .containsExactly("layla@a.test", "omar@a.test");
        RosterImport.Record layla = preview.validRows().get(0).record();
        assertThat(layla.fullName()).isEqualTo("Layla Ahmed");
        assertThat(layla.department()).isEqualTo("billing");
        assertThat(layla.maxConcurrentConversations()).isEqualTo(5);
        assertThat(layla.skills()).containsExactly("refunds", "chargebacks");
        assertThat(layla.windows()).hasSize(2);
        assertThat(layla.windows().get(0).dayOfWeek()).isEqualTo(1);
        assertThat(layla.windows().get(0).startMinute()).isEqualTo(540);
    }

    @Test
    @DisplayName("an invalid row carries a row-numbered reason and is never dropped silently")
    void invalidRowsAreReported() {
        RosterImport.Sheet sheet = RosterImport.parseCsv(CSV);
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of());

        assertThat(preview.invalidRows()).hasSize(2);
        assertThat(preview.invalidRows()).extracting(RosterImport.ValidatedRow::rowNumber)
            .containsExactly(3, 4);
        assertThat(preview.invalidRows().get(0).errors())
            .containsExactly("\"bad-row\" is not a valid email", "Full name is required");
        assertThat(preview.invalidRows().get(1).errors())
            .anyMatch(error -> error.contains("not a valid email"));
        assertThat(preview.invalidRows()).allSatisfy(row -> assertThat(row.record()).isNull());
    }

    @Test
    @DisplayName("the three buckets are disjoint and cover every row")
    void bucketsAreDisjointAndComplete() {
        RosterImport.Sheet sheet = RosterImport.parseCsv(CSV);
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of("omar@a.test"));

        assertThat(preview.validRows()).hasSize(1);
        assertThat(preview.duplicateRows()).hasSize(1);
        assertThat(preview.duplicateRows().get(0).duplicateOf()).isEqualTo("EXISTING_USER");
        assertThat(preview.invalidRows()).hasSize(2);
        assertThat(preview.validRows().size() + preview.duplicateRows().size()
            + preview.invalidRows().size()).isEqualTo(preview.totalRows());
    }

    @Test
    @DisplayName("the same email twice in one file is a duplicate of the earlier row")
    void duplicateWithinTheFile() {
        RosterImport.Sheet sheet = RosterImport.parseCsv(
            "email,name\ndup@a.test,First\ndup@a.test,Second");
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of());

        assertThat(preview.validRows()).hasSize(1);
        assertThat(preview.duplicateRows()).hasSize(1);
        assertThat(preview.duplicateRows().get(0).duplicateOf()).isEqualTo("EARLIER_ROW");
        assertThat(preview.duplicateRows().get(0).rowNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("an out-of-range or non-numeric capacity is an error, not a default")
    void capacityIsValidated() {
        RosterImport.Sheet sheet = RosterImport.parseCsv(
            "email,name,capacity\na@a.test,A,0\nb@a.test,B,201\nc@a.test,C,many");
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of());

        assertThat(preview.validRows()).isEmpty();
        assertThat(preview.invalidRows()).hasSize(3);
        assertThat(preview.invalidRows()).allSatisfy(row ->
            assertThat(row.errors()).anyMatch(error -> error.contains("between 1 and 200")));
    }

    @Test
    @DisplayName("an over-long full name is an error")
    void fullNameLength() {
        RosterImport.Sheet sheet = RosterImport.parseCsv("email,name\na@a.test,"
            + "x".repeat(121));
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of());

        assertThat(preview.invalidRows().get(0).errors())
            .contains("Full name is longer than 120 characters");
    }

    @Test
    @DisplayName("an overnight or unparseable working-hours cell is an error, not a dropped value")
    void workingHoursAreValidated() {
        RosterImport.Sheet overnight = RosterImport.parseCsv(
            "email,name,working hours\na@a.test,A,Mon 22:00-06:00");
        RosterImport.Preview overnightPreview = RosterImport.validate(overnight,
            RosterImport.suggestMapping(overnight.headers()), Set.of());
        assertThat(overnightPreview.invalidRows().get(0).errors())
            .anyMatch(error -> error.contains("overnight shift must be two entries"));

        RosterImport.Sheet nonsense = RosterImport.parseCsv(
            "email,name,working hours\na@a.test,A,Someday sometime");
        RosterImport.Preview nonsensePreview = RosterImport.validate(nonsense,
            RosterImport.suggestMapping(nonsense.headers()), Set.of());
        assertThat(nonsensePreview.invalidRows().get(0).errors())
            .anyMatch(error -> error.contains("must be '<day> HH:MM-HH:MM'"));
    }

    @Test
    @DisplayName("Arabic weekday names parse")
    void arabicWeekdays() {
        RosterImport.Sheet sheet = RosterImport.parseCsv("email,name,working hours\na@a.test,A,"
            + "الأحد 09:00-17:00");
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of());

        assertThat(preview.validRows()).hasSize(1);
        assertThat(preview.validRows().get(0).record().windows().get(0).dayOfWeek()).isZero();
    }

    @Test
    @DisplayName("skills are split on any of the three separators and capped at twenty")
    void skillsParsing() {
        // The cell is quoted because it contains commas, which the CSV reader would otherwise treat as
        // extra columns -- the same reason the legacy fixture quotes its skill cells.
        RosterImport.Sheet sheet = RosterImport.parseCsv(
            "email,name,skills\na@a.test,A,\"one|two;three,four,five\"");
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of());
        assertThat(preview.validRows().get(0).record().skills())
            .containsExactly("one", "two", "three", "four", "five");

        String many = String.join(";", java.util.stream.IntStream.range(0, 40)
            .mapToObj(index -> "s" + index).toList());
        RosterImport.Sheet capped = RosterImport.parseCsv("email,name,skills\na@a.test,A," + many);
        RosterImport.Preview cappedPreview = RosterImport.validate(capped,
            RosterImport.suggestMapping(capped.headers()), Set.of());
        assertThat(cappedPreview.validRows().get(0).record().skills()).hasSize(20);
    }

    @Test
    @DisplayName("the batch is truncated at the legacy row limit rather than throwing")
    void boundedBatch() {
        StringBuilder csv = new StringBuilder("email,name\n");
        for (int index = 0; index < RosterImport.MAX_IMPORT_ROWS + 25; index++) {
            csv.append("user").append(index).append("@a.test,User ").append(index).append('\n');
        }
        assertThat(RosterImport.parseCsv(csv.toString()).rows())
            .hasSize(RosterImport.MAX_IMPORT_ROWS);
    }

    @Test
    @DisplayName("a doubled quote inside a quoted cell is one quote")
    void escapedQuotes() {
        RosterImport.Sheet sheet = RosterImport.parseCsv("email,name\na@a.test,\"He said \"\"hi\"\"\"");
        assertThat(sheet.rows().get(0).values().get("name")).isEqualTo("He said \"hi\"");
    }

    @Test
    @DisplayName("the email is lowercased so a duplicate in a different case is still a duplicate")
    void emailIsLowercased() {
        RosterImport.Sheet sheet = RosterImport.parseCsv("email,name\nMixed@A.test,A");
        RosterImport.Preview preview = RosterImport.validate(sheet,
            RosterImport.suggestMapping(sheet.headers()), Set.of("mixed@a.test"));

        assertThat(preview.duplicateRows()).hasSize(1);
    }
}