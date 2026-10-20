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
package com.ash.drishti.engine.command;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The range a history command ends with (HISTORY_QUERIES.md section 4): {@code TRD 1000015 30}, {@code EQ EQ-CSCA 3M},
 * {@code FX FX-AUDUSD YTD}, {@code CRV CRV-USD-SOFR FROM 2026-09-01 TO 2026-09-30}, each optionally followed by {@code DIFF}.
 * <ul>
 *   <li>{@code N}: the last N business days on which the entity has data ({@link Mode#DATA_DAYS});</li>
 *   <li>{@code ND}, {@code NW}, {@code NM}, {@code NY} (or {@code N D}), {@code YTD}, {@code MTD}, {@code QTD}: a calendar span back
 *       from the date box ({@link Mode#CALENDAR});</li>
 *   <li>{@code FROM yyyy-mm-dd [TO yyyy-mm-dd]}: between two dates, the end the date box when {@code TO} is left out
 *       ({@link Mode#BETWEEN}).</li>
 * </ul>
 * Case never matters. A range is only ever the last one to four words (five with {@code DIFF}) and never the whole text:
 * the caller decides whether what is left names an entity.
 *
 * @param mode how the range is counted
 * @param days the number of data days ({@link Mode#DATA_DAYS}), else null
 * @param span the calendar span as typed and normalised ({@code 30D}, {@code 3M}, {@code YTD}), else null
 * @param from the first date ({@link Mode#BETWEEN}), else null
 * @param to the last date ({@link Mode#BETWEEN} with {@code TO}), else null
 */
public record HistoryRange(Mode mode, Integer days, String span, LocalDate from, LocalDate to) {

    /** How a range is counted (the answer's {@code window.mode}). */
    public enum Mode {
        /** a bare number: the last N days with data */
        DATA_DAYS("data-days"),
        /** {@code 30D}, {@code 3M}, {@code YTD}…: every business day in the span that has data */
        CALENDAR("calendar"),
        /** {@code FROM … [TO …]} */
        BETWEEN("between");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        /** The name the JSON uses ({@code data-days}, {@code calendar}, {@code between}). */
        public String wire() {
            return wire;
        }
    }

    /**
     * A command with its range taken off.
     *
     * @param command what is left ({@code TRD 1000015}): the entity to resolve as any command is
     * @param range the range
     * @param diff whether {@code DIFF} ended the command (only the changes, day by day)
     */
    public record Split(String command, HistoryRange range, boolean diff) {}

    /** The most data days a command may name; more is not read as a range (the server's own limit is lower). */
    public static final int MAX_NUMBER = 9999;

    private static final Pattern NUMBER = Pattern.compile("[1-9]\\d{0,3}");
    private static final Pattern SPAN = Pattern.compile("(?i)([1-9]\\d{0,3})\\s*([DWMY])");
    private static final Pattern TO_DATE = Pattern.compile("(?i)YTD|MTD|QTD");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public HistoryRange {
        if (mode == null) {
            throw new IllegalArgumentException("a history range needs a mode");
        }
    }

    /** {@code N}: the last N days with data. */
    public static HistoryRange dataDays(int n) {
        return new HistoryRange(Mode.DATA_DAYS, n, null, null, null);
    }

    /** {@code 3M}, {@code YTD}…, normalised to upper case without a space. */
    public static HistoryRange calendar(String span) {
        return new HistoryRange(Mode.CALENDAR, null, span.replaceAll("\\s+", "").toUpperCase(Locale.ROOT), null, null);
    }

    /** {@code FROM from [TO to]}; {@code to} may be null (the date box). */
    public static HistoryRange between(LocalDate from, LocalDate to) {
        return new HistoryRange(Mode.BETWEEN, null, null, from, to);
    }

    /**
     * Takes a trailing range (and {@code DIFF}) off a command. Never throws for text that is not a range: it is then simply not
     * one ({@link Optional#empty()}). A range that is read but cannot hold ({@code TO} before {@code FROM}) fails with
     * {@link ErrorCode#COMMAND_UNKNOWN}, saying why, rather than becoming a pick list.
     *
     * @param text the whole command, {@code <GO>} already removed ({@code TRD 1000015 30 DIFF})
     * @return the command without the range, the range and the {@code DIFF} flag; empty when the text ends with no range, or
     *         when nothing would be left before it
     */
    public static Optional<Split> trailing(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] w = text.trim().split("\\s+");
        int end = w.length;
        boolean diff = false;
        if (end > 1 && w[end - 1].equalsIgnoreCase("DIFF")) {
            diff = true;
            end--;
        }
        // the longest form first: FROM d TO d, then FROM d, then N D, then one word
        if (end >= 5 && w[end - 4].equalsIgnoreCase("FROM") && w[end - 2].equalsIgnoreCase("TO")
                && DATE.matcher(w[end - 3]).matches() && DATE.matcher(w[end - 1]).matches()) {
            LocalDate from = date(w[end - 3]);
            LocalDate to = date(w[end - 1]);
            if (to.isBefore(from)) {
                throw new DrishtiException(ErrorCode.COMMAND_UNKNOWN, "FROM " + from + " is after TO " + to + ": put the earlier date first");
            }
            return split(w, end - 4, between(from, to), diff);
        }
        if (end >= 3 && w[end - 2].equalsIgnoreCase("FROM") && DATE.matcher(w[end - 1]).matches()) {
            return split(w, end - 2, between(date(w[end - 1]), null), diff);
        }
        if (end >= 3 && NUMBER.matcher(w[end - 2]).matches() && w[end - 1].matches("(?i)[DWMY]")) {
            return split(w, end - 2, calendar(w[end - 2] + w[end - 1]), diff);
        }
        if (end >= 2) {
            String last = w[end - 1];
            if (NUMBER.matcher(last).matches()) {
                return split(w, end - 1, dataDays(Integer.parseInt(last)), diff);
            }
            Matcher m = SPAN.matcher(last);
            if (m.matches() || TO_DATE.matcher(last).matches()) {
                return split(w, end - 1, calendar(last), diff);
            }
        }
        return Optional.empty();
    }

    private static Optional<Split> split(String[] words, int at, HistoryRange range, boolean diff) {
        return at < 1 ? Optional.empty() : Optional.of(new Split(String.join(" ", Arrays.copyOfRange(words, 0, at)), range, diff));
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.COMMAND_UNKNOWN, "'" + text + "' is not a date: write it as yyyy-mm-dd, for example 2026-09-01");
        }
    }

    /** The range as a command writes it ({@code 30}, {@code 3M}, {@code FROM 2026-09-01 TO 2026-09-30}). */
    public String render() {
        return switch (mode) {
            case DATA_DAYS -> String.valueOf(days);
            case CALENDAR -> span;
            case BETWEEN -> "FROM " + from + (to == null ? "" : " TO " + to);
        };
    }

    /** What the range means in words, for the type-ahead ({@code last 30 days with data}, {@code since 1 January}). */
    public String describe() {
        return switch (mode) {
            case DATA_DAYS -> "the last " + days + (days == 1 ? " day" : " days") + " with data";
            case BETWEEN -> to == null ? "from " + from + " to the date box" : "from " + from + " to " + to;
            case CALENDAR -> switch (span) {
                case "YTD" -> "since 1 January";
                case "MTD" -> "since the first of the month";
                case "QTD" -> "since the start of the quarter";
                default -> {
                    int n = Integer.parseInt(span.substring(0, span.length() - 1));
                    String unit = switch (span.charAt(span.length() - 1)) {
                        case 'D' -> "calendar day";
                        case 'W' -> "week";
                        case 'M' -> "month";
                        default -> "year";
                    };
                    yield "the last " + n + " " + unit + (n == 1 ? "" : "s");
                }
            };
        };
    }

    /** The console's query for this range: {@code days=30}, {@code range=3M} or {@code from=…&to=…}. */
    public String query() {
        return switch (mode) {
            case DATA_DAYS -> "days=" + days;
            case CALENDAR -> "range=" + span;
            case BETWEEN -> "from=" + from + (to == null ? "" : "&to=" + to);
        };
    }
}
