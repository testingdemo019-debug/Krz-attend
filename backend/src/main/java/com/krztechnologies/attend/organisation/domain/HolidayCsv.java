package com.krztechnologies.attend.organisation.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The holiday CSV format of the prototype ({@code parseHolidayCSV}): columns {@code Date, Name, Type, Location}, an
 * optional header row, dates as 2026-12-25, 25 Dec 2026 or 25/12/2026, type Public (default) or Optional, location a
 * location code, the start of its name, or "All". One parser for the import preview and the import itself.
 */
public final class HolidayCsv {

    private static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("^(\\d{1,2})[ -]([A-Za-z]{3})[a-z]*[ ,-]+(\\d{4})$");
    private static final Pattern SLASHED = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");
    private static final List<String> MONTHS =
            List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /** A location a row may name. */
    public record LocationOption(long id, String code, String name) {}

    /**
     * One data row.
     *
     * @param line 1-based number of the row after the header and blank lines (as the prototype counts)
     * @param date null when the date can't be read
     * @param locationId null = all locations
     * @param error why the row can't be imported, or null
     * @param duplicate a holiday on this date for these locations already exists (or appears earlier in the file)
     */
    public record Row(
            int line,
            LocalDate date,
            String name,
            Holiday.Type type,
            Long locationId,
            String error,
            boolean duplicate) {

        public boolean ready() {
            return error == null && !duplicate;
        }
    }

    private HolidayCsv() {}

    /**
     * @param existing keys of existing holidays, see {@link #key(LocalDate, Long)}
     */
    public static List<Row> parse(String text, List<LocationOption> locations, Set<String> existing) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n", -1)) {
            if (!line.trim().isEmpty()) {
                lines.add(line.trim());
            }
        }
        if (!lines.isEmpty() && lines.getFirst().toLowerCase(Locale.ROOT).contains("date")) {
            lines.removeFirst();
        }
        Set<String> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            List<String> cells = cells(lines.get(i));
            String dateText = cell(cells, 0, "");
            String name = cell(cells, 1, "");
            String typeText = cell(cells, 2, "Public");
            String locationText = cell(cells, 3, "");
            LocalDate date = parseDate(dateText).orElse(null);
            Holiday.Type type =
                    typeText.toLowerCase(Locale.ROOT).contains("opt") ? Holiday.Type.OPTIONAL : Holiday.Type.PUBLIC;
            boolean allLocations = locationText.isEmpty()
                    || locationText.toLowerCase(Locale.ROOT).startsWith("all");
            Optional<LocationOption> location = allLocations ? Optional.empty() : findLocation(locations, locationText);
            String error = null;
            if (date == null) {
                error = "Can’t read the date “" + dateText + "” — use 2026-12-25 or 25 Dec 2026";
            } else if (name.isEmpty()) {
                error = "Missing name";
            } else if (name.length() < 2 || name.length() > 100) {
                error = "Use 2–100 characters for the name";
            } else if (!allLocations && location.isEmpty()) {
                error = "Unknown location “" + locationText + "”";
            }
            Long locationId = location.map(LocationOption::id).orElse(null);
            String key = key(date, locationId);
            boolean duplicate = error == null && (existing.contains(key) || seen.contains(key));
            seen.add(key);
            rows.add(new Row(i + 1, date, name, type, locationId, error, duplicate));
        }
        return rows;
    }

    /** The uniqueness key of a holiday: its date and location (none = all locations). */
    public static String key(LocalDate date, Long locationId) {
        return date + "|" + (locationId == null ? "" : locationId);
    }

    static Optional<LocalDate> parseDate(String text) {
        String t = text.trim();
        try {
            if (ISO.matcher(t).matches()) {
                return Optional.of(LocalDate.parse(t));
            }
            Matcher m = DAY_MONTH_YEAR.matcher(t);
            if (m.matches()) {
                int month = MONTHS.indexOf(m.group(2).toLowerCase(Locale.ROOT));
                return month < 0
                        ? Optional.empty()
                        : Optional.of(
                                LocalDate.of(Integer.parseInt(m.group(3)), month + 1, Integer.parseInt(m.group(1))));
            }
            Matcher s = SLASHED.matcher(t);
            if (s.matches()) {
                return Optional.of(LocalDate.of(
                        Integer.parseInt(s.group(3)), Integer.parseInt(s.group(2)), Integer.parseInt(s.group(1))));
            }
        } catch (DateTimeException impossibleDate) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Splits one CSV line; quoted cells may contain commas and doubled quotes. */
    static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private static String cell(List<String> cells, int index, String fallback) {
        return index < cells.size() && !cells.get(index).isEmpty() ? cells.get(index) : fallback;
    }

    private static Optional<LocationOption> findLocation(List<LocationOption> locations, String text) {
        String wanted = text.toLowerCase(Locale.ROOT);
        return locations.stream()
                .filter(l -> l.code().equalsIgnoreCase(text))
                .findFirst()
                .or(() -> locations.stream()
                        .filter(l -> l.name().toLowerCase(Locale.ROOT).startsWith(wanted))
                        .findFirst());
    }
}
