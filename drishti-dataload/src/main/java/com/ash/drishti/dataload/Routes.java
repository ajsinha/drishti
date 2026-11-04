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
package com.ash.drishti.dataload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routing a file to a table (DATALOAD_INBOX.md section 5): the {@code .ready} file's keys first, then the first rule of
 * {@code config/dataload.yaml} whose glob matches the file's name. A route names a loaded pack and one of its kinds; the loader takes the
 * table from the route's lake file (config/lakes/, the pack's recommended entry copied there once), so nothing of a table is written here. A file no
 * rule matches is refused with the reason ({@code unmatched: fail}): never guessed. The rules are read again every cycle.
 */
public final class Routes {

    /** A loaded pack as the server knows it: its folder (the loader reads its pack.yaml) and its kinds. */
    public record PackInfo(Path dir, List<String> kinds) {}

    /** The loaded packs, by name (the server's pack registry); null when no pack has the name. */
    @FunctionalInterface
    public interface Packs {
        PackInfo find(String name);
    }

    /**
     * Where a file goes: the pack and kind, the pack's connector (null: the one serving the kind), the table (null: the connector's), the
     * date field (null: the pack's) or one business date for every row ({@code asOf}), the id field (null: the pack's), the lake and the
     * table format; {@code by} says which rule (or the {@code .ready} file) decided.
     */
    public record Route(String pack, String kind, String connector, String table, String dateField, LocalDate asOf, String key, String lake,
            String format, String by, Path packDir) {

        /** The files of one table and one way of reading them go to one load. */
        public String group() {
            return String.join("|", pack, kind, String.valueOf(connector), String.valueOf(table), String.valueOf(dateField), String.valueOf(asOf),
                    String.valueOf(key), lake, format);
        }
    }

    /** A file that cannot be routed, and why. */
    public static final class Unrouted extends Exception {
        public Unrouted(String why) {
            super(why);
        }
    }

    /** One rule of the file. */
    record Rule(String match, PathMatcher glob, String pack, String kind, String connector, String table, String dateFrom, String dateField, String key,
            String lake, String format) {}

    private static final Set<String> RULE_KEYS = Set.of("match", "pack", "kind", "connector", "table", "date-from", "date-field", "key", "lake", "format");
    private static final Pattern DATE_IN_NAME = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)");

    private final List<Rule> rules;
    private final String unmatched;
    private final String defaultLake;
    private final Packs packs;
    private final String source;

    private Routes(List<Rule> rules, String unmatched, String defaultLake, Packs packs, String source) {
        this.rules = rules;
        this.unmatched = unmatched;
        this.defaultLake = defaultLake;
        this.packs = packs;
        this.source = source;
    }

    /** The rules of {@code file} (an absent file: no rules, every file needs its {@code .ready} to name its pack and kind). */
    public static Routes read(Path file, String defaultLake, Packs packs) throws IOException {
        String where = file.toString();
        if (!Files.isRegularFile(file)) {
            return new Routes(List.of(), "fail", defaultLake, packs, where + " (not there)");
        }
        JsonNode root = new YAMLMapper().readTree(Files.readString(file));
        JsonNode d = root == null ? null : root.path("dataload");
        if (d == null || d.isMissingNode() || d.isNull()) {
            return new Routes(List.of(), "fail", defaultLake, packs, where);
        }
        String unmatched = d.path("unmatched").asText("fail").trim().toLowerCase(Locale.ROOT);
        if (!unmatched.equals("fail")) {
            throw new IllegalArgumentException(where + ": dataload.unmatched " + unmatched + ": fail is the only choice (a file is never guessed)");
        }
        List<Rule> out = new ArrayList<>();
        int i = 0;
        for (JsonNode r : d.path("rules")) {
            i++;
            String at = where + ": dataload.rules[" + i + "]";
            if (!r.isObject()) {
                throw new IllegalArgumentException(at + ": a mapping (match:, pack:, kind:, ...)");
            }
            r.fieldNames().forEachRemaining(k -> {
                if (!RULE_KEYS.contains(k)) {
                    throw new IllegalArgumentException(at + "." + k + " is not a key of a rule (" + RULE_KEYS.stream().sorted().toList() + ")");
                }
            });
            String match = text(r, "match");
            String pack = text(r, "pack");
            String kind = text(r, "kind");
            if (match == null || pack == null || kind == null) {
                throw new IllegalArgumentException(at + ": a rule needs match (a glob on the file name), pack and kind");
            }
            String dateFrom = text(r, "date-from");
            dateFrom = dateFrom == null ? "field" : dateFrom.toLowerCase(Locale.ROOT);
            if (!dateFrom.equals("field") && !dateFrom.equals("name")) {
                throw new IllegalArgumentException(at + ".date-from " + dateFrom + ": field (the pack's date field, or date-field) or name "
                        + "(YYYY-MM-DD in the file's name)");
            }
            String format = text(r, "format");
            format = format == null ? "delta" : format.toLowerCase(Locale.ROOT);
            if (!format.equals("delta") && !format.equals("iceberg")) {
                throw new IllegalArgumentException(at + ".format " + format + ": delta or iceberg");
            }
            PathMatcher glob;
            try {
                glob = FileSystems.getDefault().getPathMatcher("glob:" + match);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(at + ".match " + match + ": not a glob (" + e.getMessage() + ")");
            }
            out.add(new Rule(match, glob, pack, kind, text(r, "connector"), text(r, "table"), dateFrom, text(r, "date-field"), text(r, "key"),
                    text(r, "lake"), format));
        }
        return new Routes(List.copyOf(out), unmatched, defaultLake, packs, where);
    }

    /** How many rules there are (status). */
    public int size() {
        return rules.size();
    }

    /** Where the rules come from ({@code config/dataload.yaml}, or that it is not there). */
    public String source() {
        return source;
    }

    /**
     * The rules in effect, read-only (the Admin screen): each rule's number, glob, pack and kind, the table (its connector or table, or the
     * pack's), and where the business date comes from, in words; then {@code unmatched}.
     */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", i + 1);
            m.put("match", r.match());
            m.put("pack", r.pack());
            m.put("kind", r.kind());
            m.put("connector", r.connector());
            m.put("table", tableText(r.connector(), r.table()));
            m.put("date", r.dateFrom().equals("name") ? "from the name" : "each row's " + (r.dateField() == null ? "date field (the pack's)" : r.dateField()));
            m.put("format", r.format());
            m.put("lake", r.lake() == null ? defaultLake : r.lake());
            out.add(m);
        }
        return out;
    }

    /** The rule {@code unmatched} says ({@code fail}: a file no rule matches goes to {@code failed/}, never guessed). */
    public String unmatched() {
        return unmatched;
    }

    /**
     * What would route a waiting file, before it is taken (the Admin screen's preview): the rule's number (0: its {@code .ready} file), the
     * pack and kind, the table and the business date in words; or {@code error} with the reason it would fail. Nothing is moved.
     */
    public Map<String, Object> preview(String fileName, ReadyNote note) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Route r = route(fileName, note);
            int n = 0;
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).glob().matches(Path.of(fileName))) {
                    n = i + 1;
                    break;
                }
            }
            boolean byNote = note != null && (note.pack() != null || note.kind() != null);
            m.put("n", byNote ? 0 : n);
            m.put("by", r.by());
            m.put("pack", r.pack());
            m.put("kind", r.kind());
            m.put("table", tableText(r.connector(), r.table()));
            m.put("date", r.asOf() != null ? r.asOf() + (note != null && note.businessDate() != null ? " from the .ready file" : " from the name")
                    : "each row's " + (r.dateField() == null ? "date field (the pack's)" : r.dateField()));
            m.put("text", (byNote ? "the .ready file" : "rule " + n) + " → " + r.pack() + " / " + r.kind() + " · " + m.get("table") + " · " + m.get("date"));
        } catch (Unrouted e) {
            m.put("error", e.getMessage());
            m.put("text", "no rule matches this name: once ready it would go to failed/ (never guessed)");
            if (e.getMessage() != null && !e.getMessage().startsWith("no routing rule")) {
                m.put("text", "it would go to failed/: " + e.getMessage());
            }
        }
        return m;
    }

    private static String tableText(String connector, String table) {
        if (table != null) {
            return table;
        }
        return connector != null ? connector : "the pack's";
    }

    /** Where {@code fileName} goes, its {@code .ready} keys over the first rule that matches. */
    public Route route(String fileName, ReadyNote note) throws Unrouted {
        ReadyNote n = note == null ? ReadyNote.EMPTY : note;
        Rule rule = null;
        for (Rule r : rules) {
            if (r.glob().matches(Path.of(fileName))) {
                rule = r;
                break;
            }
        }
        String pack = n.pack() != null ? n.pack() : rule == null ? null : rule.pack();
        String kind = n.kind() != null ? n.kind() : rule == null ? null : rule.kind();
        if (pack == null || kind == null) {
            throw new Unrouted("no routing rule matches " + fileName + " (" + source + ", " + rules.size() + " rule" + (rules.size() == 1 ? "" : "s")
                    + ") and its .ready file names no pack and kind: add a rule, or pack: and kind: to the .ready file");
        }
        PackInfo info = packs.find(pack);
        if (info == null) {
            throw new Unrouted("no loaded pack is named " + pack + " (" + (rule != null && n.pack() == null ? "the rule " + rule.match() : "the .ready file") + ")");
        }
        if (!info.kinds().contains(kind)) {
            throw new Unrouted("the pack " + pack + " has no kind " + kind + "; its kinds are " + info.kinds());
        }
        LocalDate asOf = n.businessDate();
        String dateField = n.dateField() != null ? n.dateField() : rule == null ? null : rule.dateField();
        if (asOf == null && rule != null && rule.dateFrom().equals("name") && n.dateField() == null) {
            Matcher m = DATE_IN_NAME.matcher(fileName);
            if (!m.find()) {
                throw new Unrouted("the rule " + rule.match() + " takes the business date from the name (date-from: name), but " + fileName
                        + " holds no YYYY-MM-DD: rename it, or give business-date: in its .ready file");
            }
            try {
                asOf = LocalDate.parse(m.group());
            } catch (DateTimeParseException e) {
                throw new Unrouted(fileName + ": " + m.group() + " is not a date");
            }
        }
        String by = n.pack() != null || n.kind() != null ? "the .ready file" + (rule == null ? "" : " over the rule " + rule.match())
                : "the rule " + rule.match();
        return new Route(pack, kind, n.connector() != null ? n.connector() : rule == null ? null : rule.connector(),
                n.table() != null ? n.table() : rule == null ? null : rule.table(), asOf != null ? null : dateField, asOf,
                rule == null ? null : rule.key(), rule == null || rule.lake() == null ? defaultLake : rule.lake(), rule == null ? "delta" : rule.format(),
                by, info.dir());
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
