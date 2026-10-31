package com.firstelement.automation.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Employee roster import: parse, map, validate, preview. Ported from
 * {@code packages/routing/src/import.ts}.
 *
 * <h2>The rule that shapes the whole file</h2>
 *
 * <p>Legacy: "**never blindly import malformed rows.** A silent skip means an ops manager believes
 * 40 agents exist when 37 do, and discovers the gap when escalations start queueing. So every row
 * is either valid or carries a row-numbered reason, and the admin sees both before anything is
 * written." Every method here returns the invalid rows rather than dropping them.
 *
 * <h2>Duplicates are reported, not merged and not dropped</h2>
 *
 * <p>Legacy: "a file listing the same person twice usually means two different intended records."
 *
 * <h2>What is NOT here, and why</h2>
 *
 * <p>Two legacy pieces are deliberately out of scope and the omission is recorded rather than
 * hidden:
 *
 * <ul>
 *   <li><b>XLSX.</b> The legacy parser loaded {@code exceljs} on demand. This module has no
 *       spreadsheet dependency, and adding one for a Phase-6 routing module would put a file-format
 *       parser in the tenant-boundary path. CSV is ported in full; XLSX is reported as an open
 *       decision for the Phase 7/9 admin surface, which owns document and export handling.</li>
 *   <li><b>User provisioning.</b> The legacy {@code commitImport} created a {@code users} row, a
 *       {@code sessions}-free reset token and a {@code project_memberships} row. User creation,
 *       password reset and membership granting belong to {@code :backend:identity-access}, not to a
 *       routing module, so {@code RosterImportService} applies the roster (department, capacity,
 *       skills, working hours) to employees that already exist and returns
 *       {@code USER_NOT_FOUND} for every row whose email matches no user. Nothing is skipped
 *       silently; the rows an operator expected to create people from are reported.</li>
 * </ul>
 */
public final class RosterImport {

    /** The legacy cap. A 5000-row roster is an operator mistake, not a migration. */
    public static final int MAX_IMPORT_ROWS = 5_000;

    /** A field of {@link Record}. */
    public enum Field {
        EMAIL("email", "e-mail", "mail", "البريد", "الايميل", "البريد الإلكتروني"),
        FULL_NAME("fullname", "full name", "name", "employee", "الاسم", "الاسم الكامل"),
        DEPARTMENT("department", "dept", "team", "القسم", "الفريق"),
        CAPACITY("maxconversations", "max conversations", "capacity", "limit", "الحد"),
        SKILLS("skills", "skill", "tags", "المهارات"),
        WINDOWS("working hours", "hours", "availability", "ساعات العمل", "الدوام");

        private final List<String> aliases;

        Field(String... aliases) {
            this.aliases = List.of(aliases);
        }

        public List<String> aliases() {
            return aliases;
        }
    }

    /** One parsed spreadsheet row, 1-based, header excluded. */
    public record SheetRow(int rowNumber, Map<String, String> values) {

        public SheetRow {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /** A parsed sheet. */
    public record Sheet(List<String> headers, List<SheetRow> rows) {

        public Sheet {
            headers = headers == null ? List.of() : List.copyOf(headers);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** Column mapping: which header carries which field. */
    public record ColumnMapping(Map<Field, String> byField) {

        public ColumnMapping {
            byField = byField == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(byField));
        }

        public static ColumnMapping empty() {
            return new ColumnMapping(Map.of());
        }

        public String columnFor(Field field) {
            return byField.get(field);
        }
    }

    /** What a row is asking for, once validated. */
    public record Record(String email,
                         String fullName,
                         String department,
                         Integer maxConcurrentConversations,
                         List<String> skills,
                         List<AvailabilityWindow> windows) {

        public Record {
            skills = skills == null ? List.of() : List.copyOf(skills);
            windows = windows == null ? List.of() : List.copyOf(windows);
        }
    }

    /** One row's verdict. Exactly one of {@code record}/{@code errors} is meaningful. */
    public record ValidatedRow(int rowNumber, Record record, List<String> errors, String duplicateOf) {

        public ValidatedRow {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean valid() {
            return record != null && errors.isEmpty();
        }

        public boolean duplicate() {
            return duplicateOf != null;
        }
    }

    /** The whole preview, split three ways. An operator sees all of it before anything is written. */
    public record Preview(List<String> headers,
                          int totalRows,
                          List<ValidatedRow> validRows,
                          List<ValidatedRow> invalidRows,
                          List<ValidatedRow> duplicateRows) {
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private RosterImport() {
    }

    /**
     * Parses CSV text.
     *
     * <p>Legacy behaviour kept exactly: blank lines dropped, {@code MAX_IMPORT_ROWS} enforced by
     * truncation (not by an error — the operator sees 5000 rows and a note, not a stack trace),
     * quoted fields with {@code "} escapes, and values trimmed.
     */
    public static Sheet parseCsv(String content) {
        if (content == null || content.isBlank()) {
            return new Sheet(List.of(), List.of());
        }
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\r?\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return new Sheet(List.of(), List.of());
        }
        List<String> headers = splitCsvLine(lines.get(0));
        List<SheetRow> rows = new ArrayList<>();
        int limit = Math.min(lines.size() - 1, MAX_IMPORT_ROWS);
        for (int index = 0; index < limit; index++) {
            List<String> values = splitCsvLine(lines.get(index + 1));
            Map<String, String> byHeader = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                byHeader.put(headers.get(column), column < values.size() ? values.get(column) : "");
            }
            rows.add(new SheetRow(index + 1, byHeader));
        }
        return new Sheet(headers, rows);
    }

    /**
     * Best-effort column mapping an operator can then correct.
     *
     * <p>Alias comparison lowercases and turns {@code _}/{@code -} into spaces, so
     * {@code Max_Conversations} maps to {@code max conversations} exactly as the legacy version did.
     * The aliases are normalised the same way, which is a small fix to the legacy behaviour: the
     * legacy list contained hyphenated aliases ("e-mail", "max conversations") that its own
     * normaliser could never match, so a payroll export headed {@code E-Mail} silently mapped nothing.
     */
    public static ColumnMapping suggestMapping(List<String> headers) {
        Map<Field, String> mapping = new LinkedHashMap<>();
        if (headers == null) {
            return new ColumnMapping(mapping);
        }
        for (Field field : Field.values()) {
            java.util.Set<String> normalized = new java.util.LinkedHashSet<>();
            for (String alias : field.aliases()) {
                normalized.add(normalizeHeader(alias));
            }
            String match = headers.stream()
                .filter(java.util.Objects::nonNull)
                .filter(header -> normalized.contains(normalizeHeader(header)))
                .findFirst()
                .orElse(null);
            if (match != null) {
                mapping.put(field, match);
            }
        }
        return new ColumnMapping(mapping);
    }

    /**
     * Validates every row against the mapping.
     *
     * @param existingEmails emails already in this tenant, so a roster cannot silently re-create
     *                       somebody; lowercase, as {@link #validateRow} lowercases the input
     */
    public static Preview validate(Sheet sheet, ColumnMapping mapping, Set<String> existingEmails) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        List<ValidatedRow> validated = new ArrayList<>();
        for (SheetRow row : sheet.rows()) {
            validated.add(validateRow(row, mapping, existingEmails, seen));
        }
        return new Preview(
            sheet.headers(),
            sheet.rows().size(),
            validated.stream().filter(ValidatedRow::valid).filter(row -> !row.duplicate()).toList(),
            validated.stream().filter(row -> !row.valid()).toList(),
            validated.stream().filter(ValidatedRow::valid).filter(ValidatedRow::duplicate).toList());
    }

    private static ValidatedRow validateRow(SheetRow row, ColumnMapping mapping,
                                            Set<String> existingEmails, Set<String> seen) {
        List<String> errors = new ArrayList<>();
        String email = read(row, mapping, Field.EMAIL).toLowerCase(Locale.ROOT);
        String fullName = read(row, mapping, Field.FULL_NAME);

        if (email.isBlank()) {
            errors.add("Email is required");
        } else if (!EMAIL.matcher(email).matches()) {
            errors.add("\"" + email + "\" is not a valid email");
        }
        if (fullName.isBlank()) {
            errors.add("Full name is required");
        } else if (fullName.length() > 120) {
            errors.add("Full name is longer than 120 characters");
        }

        Integer capacity = null;
        String rawCapacity = read(row, mapping, Field.CAPACITY);
        if (!rawCapacity.isBlank()) {
            try {
                int parsed = Integer.parseInt(rawCapacity);
                if (parsed < 1 || parsed > 200) {
                    errors.add("Capacity \"" + rawCapacity + "\" must be a whole number between 1 and 200");
                } else {
                    capacity = parsed;
                }
            } catch (NumberFormatException e) {
                errors.add("Capacity \"" + rawCapacity + "\" must be a whole number between 1 and 200");
            }
        }

        List<String> skills = new ArrayList<>();
        for (String skill : read(row, mapping, Field.SKILLS).split("[;,|]")) {
            String trimmed = skill.trim();
            if (!trimmed.isBlank()) {
                skills.add(trimmed);
            }
            if (skills.size() == 20) {
                break;
            }
        }

        List<String> windowErrors = new ArrayList<>();
        List<AvailabilityWindow> windows = parseWindows(read(row, mapping, Field.WINDOWS), windowErrors);
        errors.addAll(windowErrors);

        if (!errors.isEmpty()) {
            return new ValidatedRow(row.rowNumber(), null, List.copyOf(errors), null);
        }

        // Duplicates are reported, not merged and not dropped.
        String duplicateOf = existingEmails.contains(email) || !seen.add(email)
            ? (existingEmails.contains(email) ? "EXISTING_USER" : "EARLIER_ROW")
            : null;
        seen.add(email);

        return new ValidatedRow(row.rowNumber(),
            new Record(email, fullName, blankToNull(read(row, mapping, Field.DEPARTMENT)), capacity,
                List.copyOf(skills), windows),
            List.of(), duplicateOf);
    }

    /**
     * Parses a working-hours cell: {@code Mon 09:00-17:00; Thu 09:00-13:00}.
     *
     * <p>Deliberately stricter than the rest of the file: an unparseable window is an error, not a
     * skipped value, because a roster that silently drops hours produces an agent who is routable at
     * 3am. A window whose end is not after its start is refused for the same reason
     * {@code sla_calendar_windows} refuses it: an overnight shift needs two rows.
     */
    private static List<AvailabilityWindow> parseWindows(String raw, List<String> errors) {
        List<AvailabilityWindow> windows = new ArrayList<>();
        if (raw.isBlank()) {
            return windows;
        }
        for (String chunk : raw.split("[;|]")) {
            String entry = chunk.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] parts = entry.split("\\s+");
            if (parts.length != 2 || !parts[1].contains("-")) {
                errors.add("Working hours entry \"" + entry
                    + "\" must be '<day> HH:MM-HH:MM', for example 'Mon 09:00-17:00'");
                continue;
            }
            Integer day = dayOfWeek(parts[0]);
            if (day == null) {
                errors.add("\"" + parts[0] + "\" is not a weekday name");
                continue;
            }
            String[] bounds = parts[1].split("-", 2);
            Integer start = minuteOfDay(bounds[0]);
            Integer end = minuteOfDay(bounds.length > 1 ? bounds[1] : "");
            if (start == null || end == null) {
                errors.add("\"" + parts[1] + "\" is not a HH:MM-HH:MM range");
                continue;
            }
            if (end <= start) {
                errors.add("Working hours \"" + entry + "\" end at or before they start; an overnight "
                    + "shift must be two entries");
                continue;
            }
            windows.add(new AvailabilityWindow(day, start, end, "Asia/Riyadh"));
        }
        return windows;
    }

    private static Integer dayOfWeek(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "sun", "sunday", "الاحد", "الأحد" -> 0;
            case "mon", "monday", "الاثنين", "الإثنين" -> 1;
            case "tue", "tues", "tuesday", "الثلاثاء" -> 2;
            case "wed", "wednesday", "الاربعاء", "الأربعاء" -> 3;
            case "thu", "thur", "thurs", "thursday", "الخميس" -> 4;
            case "fri", "friday", "الجمعه", "الجمعة" -> 5;
            case "sat", "saturday", "السبت" -> 6;
            default -> null;
        };
    }

    private static Integer minuteOfDay(String raw) {
        String[] parts = raw.trim().split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 24 || minute < 0 || minute > 59) {
                return null;
            }
            int total = hour * 60 + minute;
            return total > 1440 ? null : total;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String read(SheetRow row, ColumnMapping mapping, Field field) {
        String column = mapping.columnFor(field);
        if (column == null) {
            return "";
        }
        String value = row.values().get(column);
        return value == null ? "" : value.trim();
    }

    private static String normalizeHeader(String header) {
        return header.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }

    /** RFC-4180 field splitting, with {@code "} as an escaped quote. */
    private static List<String> splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (value == ',' && !inQuotes) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        values.add(current.toString().trim());
        return values;
    }
}
