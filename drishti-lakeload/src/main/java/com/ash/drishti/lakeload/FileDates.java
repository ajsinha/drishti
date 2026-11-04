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
package com.ash.drishti.lakeload;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The business date a file's name carries (DATA_GUIDE.md section 2.8), the Java half of one rule ({@code tools/lake/filedate.py} is the
 * Python half; both are tested against {@code tools/lake/testdata/file-dates/names.json}). The base name, its data and archive extensions
 * stripped ({@code .jsonl .ndjson .json .gz .zst .zip .tar .tar.gz .tgz}, in any case and any number), must END with a separator (space,
 * {@code .}, {@code _} or {@code -}) and a date, {@code YYYYMMDD} or {@code YYYY-MM-DD}, that is a calendar date of the years 1900 to
 * 2999: {@code abc 20261103.jsonl}, {@code abc.20261103.jsonl.zip}, {@code abc_2026-11-03.tar.gz}. Anything else carries no date.
 * An archive's member: its own name's date wins, else the archive's.
 */
public final class FileDates {

    /** What a name says: its date (null: none) and, without one, why (for a log line or a preview). */
    public record Found(LocalDate date, String why) {}

    private static final List<String> EXTENSIONS = List.of(".tar.gz", ".tgz", ".gz", ".zst", ".zip", ".tar", ".jsonl", ".ndjson", ".json");
    private static final Pattern AT_END = Pattern.compile("[ ._-](\\d{8}|\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern EIGHT = Pattern.compile("(?<!\\d)\\d{8}(?!\\d)|(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)");
    static final int FIRST_YEAR = 1900;
    static final int LAST_YEAR = 2999;

    private FileDates() {}

    /** The date {@code name} (a file name or a path: only its last part counts) carries, or null. */
    public static LocalDate of(String name) {
        return find(name).date();
    }

    /** The date of an archive's member: the member's own name's, else the archive's; null when neither carries one. */
    public static LocalDate of(String archive, String member) {
        LocalDate d = member == null ? null : of(member);
        return d != null ? d : archive == null ? null : of(archive);
    }

    /** The date {@code name} carries, or why it carries none. */
    public static Found find(String name) {
        if (name == null || name.isBlank()) {
            return new Found(null, "no name");
        }
        String base = baseName(name);
        Matcher m = AT_END.matcher(base);
        if (!m.find()) {
            return new Found(null, EIGHT.matcher(base).find()
                    ? base + ": the date is not at the end of the name after a space, '.', '_' or '-'"
                    : base + ": no YYYYMMDD or YYYY-MM-DD at the end of the name");
        }
        String d = m.group(1).replace("-", "");
        int y = Integer.parseInt(d.substring(0, 4));
        try {
            LocalDate date = LocalDate.of(y, Integer.parseInt(d.substring(4, 6)), Integer.parseInt(d.substring(6, 8)));
            if (y < FIRST_YEAR || y > LAST_YEAR) {
                return new Found(null, base + ": " + m.group(1) + " is outside the years " + FIRST_YEAR + " to " + LAST_YEAR);
            }
            return new Found(date, null);
        } catch (DateTimeException e) {
            return new Found(null, base + ": " + m.group(1) + " is not a calendar date");
        }
    }

    /** Whether {@code name} is an archive the loaders read members of (.zip, .tar, .tar.gz, .tgz). */
    public static boolean isArchive(String name) {
        return Archives.isArchive(name);
    }

    /** The words for rows whose own date disagreed with the date taken from their file's name ({@code filedate.other_date_note}). */
    public static String otherDateNote(long rows, String file, String field, LocalDate day) {
        return rows + " row" + (rows == 1 ? "" : "s") + " of " + file + " carried another date in " + field + "; loaded under " + day + " from the file name";
    }

    /** The last part of a path (after '/', '\' or an archive's '!/'), its data and archive extensions stripped. */
    static String baseName(String name) {
        String s = name.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            String lower = s.toLowerCase(Locale.ROOT);
            for (String ext : EXTENSIONS) {
                if (lower.endsWith(ext) && lower.length() > ext.length()) {
                    s = s.substring(0, s.length() - ext.length());
                    stripped = true;
                    break;
                }
            }
        }
        return s;
    }
}
