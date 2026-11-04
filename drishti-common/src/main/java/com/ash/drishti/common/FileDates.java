/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.common;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The table and the business date a file's name carries, the business date of a file, and a tar archive's layout (DATA_GUIDE.md section
 * 2.8): the Java half of one rule ({@code tools/lake/filedate.py} is the Python half; both are tested against
 * {@code tools/lake/testdata/file-dates/names.json}).
 *
 * <p><b>Names.</b> {@code <table>.<date>.<extensions>} or {@code <table>.<extensions>}: the table is everything before the first
 * {@code .} (letters, digits, {@code _}, {@code -}); the date, when there is one, the second segment, {@code YYYY-MM-DD} (or
 * {@code YYYYMMDD}), a calendar date from 1900, not in the future; the extensions a format ({@code jsonl}, {@code ndjson}, {@code json})
 * and/or a compression ({@code gz}, {@code zip}, {@code zst}), in that order, without case. Anything else is refused with the reason. A
 * tar ({@code .tar}, {@code .tgz}, {@code .tar.gz}, {@code .tar.zip}) names its tables and dates in its folders:
 * {@code <table>/<YYYY-MM-DD>/<file>.jsonl}.
 *
 * <p><b>The business date of a plain file</b> ({@link #decide}): given for the load ({@code as-of}, a {@code .ready} file's
 * {@code business-date}), else the name's, else the business today; {@code date-from: name} refuses a name without a date. A row's own
 * date field never decides; it is only counted when it differs.
 */
public final class FileDates {

    /** What a name says: its table (null: none), its date (null: none), whether it is a tar archive, or why it is refused. */
    public record Name(String table, LocalDate date, boolean tar, String error) {}

    /** A file's business date and where it comes from ({@code given}, {@code name}, {@code folder}, {@code rows}, {@code today}), or why it is refused. */
    public record Decided(LocalDate date, String source, String error) {}

    /** A tar archive's layout: table → date → member names (name order), the problems (any refuses the archive), the litter ignored. */
    public record Layout(Map<String, Map<LocalDate, List<String>>> tables, List<String> errors, List<String> ignored) {}

    static final List<String> FORMATS = List.of("jsonl", "ndjson", "json");
    static final List<String> COMPRESSIONS = List.of("gz", "zip", "zst");
    static final List<String> TAR_SUFFIXES = List.of(".tar.gz", ".tar.zip", ".tgz", ".tar");
    static final List<String> SHAPES = List.of("F", "FC", "C");
    static final String EXTENSIONS_TEXT = "(.jsonl, .ndjson, .json, .gz, .zip, .zst; an archive .tar, .tgz, .tar.gz, .tar.zip)";
    static final List<String> LITTER = List.of("__MACOSX", "Thumbs.db");
    static final int FIRST_YEAR = 1900;
    private static final Pattern DATEISH = Pattern.compile("^(\\d{8}|\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern TABLE = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern JSONL_MEMBER = Pattern.compile("\\.(jsonl|ndjson|json)(\\.gz|\\.zst)?$", Pattern.CASE_INSENSITIVE);

    private FileDates() {}

    /** The business today: {@code today}, or the Friday before on a Saturday or a Sunday. */
    public static LocalDate businessToday(LocalDate today) {
        DayOfWeek d = today.getDayOfWeek();
        return d == DayOfWeek.SATURDAY ? today.minusDays(1) : d == DayOfWeek.SUNDAY ? today.minusDays(2) : today;
    }

    /** Today in the system's time zone (the zone the loaders and the server run in). */
    public static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.systemDefault());
    }

    /** The business today of the system's clock. */
    public static LocalDate businessToday() {
        return businessToday(today());
    }

    /** The last part of a path (after '/', '\' or an archive's '!/'). */
    static String baseName(String name) {
        String s = name.replace('\\', '/');
        return s.substring(s.lastIndexOf('/') + 1);
    }

    /** Whether {@code name} is a tar archive (.tar, .tgz, .tar.gz, .tar.zip). */
    public static boolean isTar(String name) {
        String low = baseName(name).toLowerCase(Locale.ROOT);
        return TAR_SUFFIXES.stream().anyMatch(low::endsWith);
    }

    /** The archives the loaders read members of. */
    public static final List<String> ARCHIVE_SUFFIXES = List.of(".tar.gz", ".tgz", ".tar", ".zip");

    /** Names compare as Python's do: character (code point) by character. */
    public static final java.util.Comparator<String> BY_NAME = (a, b) -> java.util.Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());

    /** Whether {@code name} is an archive the loaders read members of (.zip, .tar, .tar.gz, .tgz, .tar.zip). */
    public static boolean isArchive(String name) {
        String n = baseName(name).toLowerCase(Locale.ROOT);
        return ARCHIVE_SUFFIXES.stream().anyMatch(n::endsWith);
    }

    /** {@code text} as a date, or why it is none (the second element). */
    private static Object[] date(String text, LocalDate today) {
        String d = text.replace("-", "");
        LocalDate day;
        try {
            day = LocalDate.of(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(4, 6)), Integer.parseInt(d.substring(6, 8)));
        } catch (DateTimeException e) {
            return new Object[] {null, text + " is not a calendar date"};
        }
        if (day.getYear() < FIRST_YEAR) {
            return new Object[] {null, text + " is before " + FIRST_YEAR};
        }
        if (day.isAfter(today)) {
            return new Object[] {null, text + " is in the future"};
        }
        return new Object[] {day, null};
    }

    /** The table and date {@code name} carries, or why it is refused (class doc), against the system's today. */
    public static Name parse(String name) {
        return parse(name, today());
    }

    /** The table and date {@code name} carries, or why it is refused (class doc). */
    public static Name parse(String name, LocalDate today) {
        String base = baseName(name);
        String low = base.toLowerCase(Locale.ROOT);
        for (String s : TAR_SUFFIXES) {
            if (low.endsWith(s)) {
                String label = base.substring(0, base.length() - s.length());
                if (!TABLE.matcher(label).matches()) {
                    return new Name(null, null, true, base + ": a .tar archive names its tables and dates in its folders "
                            + "(<table>/<YYYY-MM-DD>/), never in its file name (letters, digits, _ and - before .tar)");
                }
                return new Name(label.toLowerCase(Locale.ROOT), null, true, null);
            }
        }
        String[] tokens = base.split("\\.", -1);
        String table = tokens[0];
        if (!TABLE.matcher(table).matches()) {
            return new Name(null, null, false, base + ": '" + table + "' is not a table name (letters, digits, _ and -, before the first '.')");
        }
        if (tokens.length == 1) {
            return new Name(null, null, false, base + ": no extension " + EXTENSIONS_TEXT);
        }
        int i = 1;
        LocalDate day = null;
        if (DATEISH.matcher(tokens[1]).matches()) {
            Object[] d = date(tokens[1], today);
            if (d[1] != null) {
                return new Name(null, null, false, base + ": " + d[1]);
            }
            day = (LocalDate) d[0];
            i = 2;
        }
        StringBuilder shape = new StringBuilder();
        for (; i < tokens.length; i++) {
            String t = tokens[i];
            String lt = t.toLowerCase(Locale.ROOT);
            if (FORMATS.contains(lt)) {
                shape.append('F');
            } else if (COMPRESSIONS.contains(lt)) {
                shape.append('C');
            } else {
                return new Name(null, null, false, base + ": '" + t + "' is neither the date (YYYY-MM-DD, right after the table name) nor an extension "
                        + EXTENSIONS_TEXT);
            }
        }
        if (!SHAPES.contains(shape.toString())) {
            return new Name(null, null, false, base + ": not <table>[.<YYYY-MM-DD>].<format>[.<compression>] " + EXTENSIONS_TEXT);
        }
        return new Name(table.toLowerCase(Locale.ROOT), day, false, null);
    }

    /** The date {@code name} carries; null when it carries none, is refused or is a tar (where the table comes from elsewhere). */
    public static LocalDate of(String name) {
        Name n = parse(name);
        return n.error() != null || n.tar() ? null : n.date();
    }

    /** The business date of one plain file (class doc). */
    public static Decided decide(LocalDate given, LocalDate nameDate, String dateFrom, LocalDate today, String name) {
        if (given != null) {
            return new Decided(given, "given", null);
        }
        if (nameDate != null) {
            return new Decided(nameDate, "name", null);
        }
        if ("name".equals(dateFrom)) {
            return new Decided(null, "name", baseName(name) + ": date-from name, but the name carries no date (YYYY-MM-DD)");
        }
        return new Decided(businessToday(today), "today", null);
    }

    /** Where a file's date comes from, in words ({@code filedate.words}). */
    public static String words(Decided d, String givenBy) {
        return switch (d.source()) {
            case "given" -> d.date() + " from " + givenBy;
            case "name" -> d.date() + " from the file name";
            case "folder" -> d.date() + " from the archive's folder";
            case "ready" -> d.date() + " from the .ready file";
            default -> d.date() + " (today: no date in the name)";
        };
    }

    /**
     * The business date a {@code .ready} file beside a local data file gives ({@code <file>.ready}, or its name without the data extension
     * {@code .ready}; docs/architecture/DATA_LOAD_RULES.md section 5): its {@code business-date}, null without one. The inbox reads every
     * key of it (drishti-dataload {@code ReadyNote}); a command-line load takes the date from it, below its own {@code as-of}.
     */
    public static LocalDate readyDate(String path) {
        if (path == null || path.contains("://") || path.contains("!/")) {
            return null;
        }
        java.nio.file.Path p = java.nio.file.Path.of(path);
        String name = p.getFileName().toString();
        String low = name.toLowerCase(Locale.ROOT);
        String shortName = name;
        for (String e : List.of(".tar.gz", ".tar.zip", ".jsonl.gz", ".jsonl.zip", ".jsonl.zst", ".ndjson.gz", ".ndjson.zst", ".json.gz", ".tgz", ".tar",
                ".jsonl", ".ndjson", ".json", ".gz", ".zip", ".zst")) {
            if (low.endsWith(e) && name.length() > e.length()) {
                shortName = name.substring(0, name.length() - e.length());
                break;
            }
        }
        for (String m : List.of(name + ".ready", shortName + ".ready")) {
            java.nio.file.Path r = p.resolveSibling(m);
            if (java.nio.file.Files.isRegularFile(r)) {
                try {
                    com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(r.toFile());
                    String d = n == null ? null : n.path("business-date").asText(null);
                    return d == null || d.isBlank() ? null : LocalDate.parse(d.trim());
                } catch (java.io.IOException | java.time.DateTimeException e) {
                    throw new IllegalArgumentException(r.getFileName() + ": its business-date is not YYYY-MM-DD (" + e.getMessage() + ")");
                }
            }
        }
        return null;
    }

    /** The words for rows whose own date disagreed with their file's date ({@code filedate.other_date_note}). */
    public static String otherDateNote(long rows, String file, String field, LocalDate day) {
        return rows + " row" + (rows == 1 ? "" : "s") + " of " + file + " carried another date in " + field + "; loaded under " + day;
    }

    /**
     * The layout of a tar archive's entries ({@code {raw name, type}}, type {@code file}, {@code dir} or what else it is). {@code known}:
     * the tables the load knows (compared without case, the known spelling kept); null: any table name.
     */
    public static Layout tarLayout(List<String[]> entries, LocalDate today, Collection<String> known) {
        Map<String, Map<LocalDate, List<String>>> tables = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        Map<String, String> canon = new java.util.HashMap<>();
        if (known != null) {
            known.forEach(k -> canon.put(k.toLowerCase(Locale.ROOT), k));
        }
        List<String> unknown = new ArrayList<>();
        for (String[] e : entries) {
            String raw = e[0];
            String type = e[1];
            String n = raw.replace('\\', '/');
            if (n.startsWith("/") || n.matches("^[A-Za-z]:.*") || n.indexOf('\0') >= 0) {
                errors.add(raw + ": an absolute path");
                continue;
            }
            List<String> parts = new ArrayList<>();
            for (String p : n.split("/")) {
                if (!p.isEmpty() && !p.equals(".")) {
                    parts.add(p);
                }
            }
            if (parts.contains("..")) {
                errors.add(raw + ": a '..' in its path");
                continue;
            }
            if (parts.isEmpty() || type.equals("dir")) {
                continue;
            }
            if (parts.stream().anyMatch(p -> p.startsWith(".") || LITTER.contains(p))) {
                ignored.add(raw);
                continue;
            }
            if (!type.equals("file")) {
                errors.add(raw + ": " + type + ", not a file");
                continue;
            }
            if (parts.size() != 3) {
                String where = parts.size() == 1 ? "at the top" : (parts.size() - 1) + " folder" + (parts.size() > 2 ? "s" : "") + " deep";
                errors.add(raw + ": " + where + "; every file is <table>/<YYYY-MM-DD>/<file>.jsonl");
                continue;
            }
            String table = parts.get(0);
            String folder = parts.get(1);
            if (!JSONL_MEMBER.matcher(parts.get(2)).find()) {
                errors.add(raw + ": not JSON Lines (.jsonl, .ndjson, .json; plain, .gz or .zst)");
                continue;
            }
            if (!DATEISH.matcher(folder).matches()) {
                errors.add(raw + ": the folder " + folder + " is not a date (YYYY-MM-DD)");
                continue;
            }
            Object[] d = date(folder, today);
            if (d[1] != null) {
                errors.add(raw + ": " + d[1]);
                continue;
            }
            if (!TABLE.matcher(table).matches()) {
                errors.add(raw + ": the folder " + table + " is not a table name (letters, digits, _ and -)");
                continue;
            }
            if (known != null) {
                String c = canon.get(table.toLowerCase(Locale.ROOT));
                if (c == null) {
                    if (!unknown.contains(table)) {
                        unknown.add(table);
                    }
                    continue;
                }
                table = c;
            } else {
                table = table.toLowerCase(Locale.ROOT);
            }
            tables.computeIfAbsent(table, k -> new TreeMap<>()).computeIfAbsent((LocalDate) d[0], k -> new ArrayList<>()).add(String.join("/", parts));
        }
        for (String t : unknown) {
            List<String> k = new ArrayList<>(known);
            k.sort(String.CASE_INSENSITIVE_ORDER);
            errors.add(t + "/: no such table (known: " + (k.isEmpty() ? "none" : String.join(", ", k)) + ")");
        }
        tables.values().forEach(ds -> ds.values().forEach(fs -> fs.sort(BY_NAME)));
        return new Layout(tables, errors, ignored);
    }
}
