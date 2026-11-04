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

import com.ash.drishti.common.FileDates;
import com.ash.drishti.lakeload.LakeLoad;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Routing a file to a table (DATALOAD_INBOX.md section 5): the {@code .ready} file's keys first, then the first rule of
 * {@code config/dataload.yaml} whose glob matches the file's name. A route names a loaded pack and one of its kinds; the loader takes the
 * table, its layout, its key and its date field from the pack (its {@code PackLayout}), so nothing of a table is written here. A file no
 * rule matches is refused with the reason ({@code unmatched: fail}): never guessed. The rules are read again every cycle.
 */
public final class Routes {

    /** A loaded pack as the server knows it: its folder (the loader reads its pack.yaml) and its kinds. */
    public record PackInfo(Path dir, List<String> kinds) {}

    /** The loaded packs, by name (the server's pack registry); null when no pack has the name. */
    @FunctionalInterface
    public interface Packs {
        PackInfo find(String name);

        /** Every loaded pack's name (a table name is looked up among them). */
        default List<String> names() {
            return List.of();
        }
    }

    /**
     * Where a file goes: the pack and kind, the pack's connector (null: the one serving the kind), the table (null: the connector's), the
     * date field (null: the pack's) or one business date for every row ({@code asOf}), the id field (null: the pack's), the lake and the
     * table format; {@code by} says which rule (or the {@code .ready} file) decided. {@code dateFrom} is the loader's {@code source.date-from}:
     * {@code auto}, a date at the end of a file's name (an archive member's own first: {@code FileDates}) is every row's business date, or
     * {@code field}, the rows' own; {@code nameDate} the date the file's name carries (what the Admin screen shows), {@code dateBy} where the
     * date comes from in words.
     */
    public record Route(String pack, String kind, String connector, String table, String dateField, LocalDate asOf, String key, String lake,
            String format, String by, Path packDir, String dateFrom, LocalDate nameDate, String dateBy, String mode, String dataset, String newDomain) {

        Route(String pack, String kind, String connector, String table, String dateField, LocalDate asOf, String key, String lake, String format,
                String by, Path packDir, String dateFrom, LocalDate nameDate, String dateBy, String mode, String dataset) {
            this(pack, kind, connector, table, dateField, asOf, key, lake, format, by, packDir, dateFrom, nameDate, dateBy, mode, dataset, null);
        }

        /** The files of one table and one way of reading them go to one load. */
        public String group() {
            return String.join("|", pack, kind, String.valueOf(connector), String.valueOf(table), String.valueOf(dateField), String.valueOf(asOf),
                    String.valueOf(key), lake, format, dateFrom, mode, String.valueOf(dataset), String.valueOf(newDomain));
        }

        /** The same route for one table folder of a tar ({@code source.dataset}). */
        Route forFolder(String folder, String pack2, String kind2, Path dir2, String by2) {
            return new Route(pack2, kind2, null, null, dateField, null, key, lake, format, by2, dir2, dateFrom, null,
                    "each table and date from the archive's folders", mode, folder);
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

    private final List<Rule> rules;
    /** The inbox's {@code default-mode} (append, else replace) and whether it may create a table ({@code new-tables}). */
    private String defaultMode = "append";
    private boolean newTables;
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
            dateFrom = dateFrom == null ? "auto" : dateFrom.toLowerCase(Locale.ROOT);
            if (!dateFrom.equals("auto") && !dateFrom.equals("name")) {
                throw new IllegalArgumentException(at + ".date-from " + dateFrom + ": auto (the .ready file's business-date, else the name's date, "
                        + "else the business today; the default) or name (a name without a date is refused)");
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

    /** These routes with the inbox's default mode and its new-tables switch (DataLoadSettings). */
    public Routes with(String defaultMode, boolean newTables) {
        this.defaultMode = defaultMode == null ? "append" : defaultMode;
        this.newTables = newTables;
        return this;
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
            m.put("date", r.dateFrom().equals("name") ? "from the name (required)" : "from the .ready file, else the name, else today");
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
            m.put("date", r.dateBy());
            m.put("nameDate", r.nameDate() == null ? null : r.nameDate().toString());
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

    /**
     * Where {@code fileName} goes (docs/architecture/DATA_LOAD_RULES.md section 3): the pack and kind from its {@code .ready} file, else the
     * first rule that matches, else the table its name carries (the pack that has a kind of that name, else a pack of that name with one
     * kind; unknown or ambiguous: refused with the candidates). The business date: the {@code .ready} file's {@code business-date}, else
     * the name's date, else the business today (the loader decides each file the same way, {@code FileDates.decide}); a rule's
     * {@code date-from: name} refuses a name without a date. A tar names its own tables and dates: a {@code .ready} date or table is refused.
     */
    public Route route(String fileName, ReadyNote note) throws Unrouted {
        return route(fileName, note, false);
    }

    private Route route(String fileName, ReadyNote note, boolean anyPack) throws Unrouted {
        ReadyNote n = note == null ? ReadyNote.EMPTY : note;
        FileDates.Name name = FileDates.parse(fileName);
        if (name.tar() && (n.businessDate() != null || n.table() != null)) {
            throw new Unrouted(fileName + ": a .tar archive names its own tables and dates (its <table>/<YYYY-MM-DD>/ folders): its .ready file's "
                    + "business-date: and table: are refused");
        }
        Rule rule = null;
        for (Rule r : rules) {
            if (r.glob().matches(Path.of(fileName))) {
                rule = r;
                break;
            }
        }
        String pack = n.pack() != null ? n.pack() : rule == null ? null : rule.pack();
        String kind = n.kind() != null ? n.kind() : rule == null ? null : rule.kind();
        String by;
        if (anyPack && (pack == null || kind == null)) {         // a tar: each folder routed by routeTar; the rule's mode, lake and format kept
            List<String> names = packs.names();
            pack = names.isEmpty() ? null : names.get(0);
            PackInfo any = pack == null ? null : packs.find(pack);
            kind = any == null || any.kinds().isEmpty() ? null : any.kinds().get(0);
            if (pack == null || kind == null) {
                throw new Unrouted("no loaded pack to load " + fileName + " into");
            }
            by = "the archive";
        } else if (pack == null || kind == null) {
            String table = n.table() != null ? n.table().toLowerCase(java.util.Locale.ROOT) : name.table();
            if (table == null) {
                throw new Unrouted(name.error() != null ? name.error() : "no routing rule matches " + fileName + " (" + source + ", " + rules.size()
                        + " rule" + (rules.size() == 1 ? "" : "s") + ") and its .ready file names no pack and kind");
            }
            String[] pk;
            try {
                pk = byTable(table, pack, kind);
            } catch (Unrouted e) {
                if (!n.newTable() || !e.getMessage().contains("no pack, kind or table")) {
                    throw e;
                }
                return newTable(fileName, table, pack, n, rule, name, e);
            }
            pack = pk[0];
            kind = pk[1];
            by = (n.table() != null ? "the .ready file's table " : "the name's table ") + table;
        } else {
            by = n.pack() != null || n.kind() != null ? "the .ready file" + (rule == null ? "" : " over the rule " + rule.match()) : "the rule " + rule.match();
        }
        PackInfo info = packs.find(pack);
        if (info == null) {
            throw new Unrouted("no loaded pack is named " + pack + " (" + (rule != null && n.pack() == null ? "the rule " + rule.match() : "the .ready file") + ")");
        }
        if (!info.kinds().contains(kind)) {
            throw new Unrouted("the pack " + pack + " has no kind " + kind + "; its kinds are " + info.kinds());
        }
        String dateFrom = rule == null ? "auto" : rule.dateFrom();
        LocalDate asOf = n.businessDate();
        LocalDate nameDate = name.error() == null && !name.tar() ? name.date() : null;
        String dateBy;
        if (name.tar()) {
            dateBy = "each table and date from the archive's folders";
        } else if (asOf != null) {
            dateBy = asOf + " from the .ready file";
        } else if (nameDate != null) {
            dateBy = nameDate + " from the name";
        } else if (dateFrom.equals("name")) {
            throw new Unrouted("the rule " + rule.match() + " takes the business date from the name (date-from: name), but " + fileName
                    + " carries none (<table>.<YYYY-MM-DD>.jsonl.gz): rename it, or give business-date: in its .ready file");
        } else {
            dateBy = FileDates.businessToday() + " (today: no date in the name)";
        }
        String mode = n.mode() != null ? n.mode() : defaultMode;
        return new Route(pack, kind, n.connector() != null ? n.connector() : rule == null ? null : rule.connector(),
                rule == null ? null : rule.table(), rule == null ? null : rule.dateField(), asOf,
                n.key() != null ? n.key() : rule == null ? null : rule.key(), rule == null || rule.lake() == null ? defaultLake : rule.lake(),
                rule == null ? "delta" : rule.format(), by, info.dir(), dateFrom, nameDate, dateBy, mode, null);
    }

    /**
     * A table no pack has, asked for ({@code new-table: true}; DATA_LOAD_RULES.md section 7): allowed only when the inbox allows new tables
     * ({@code new-tables}); written in the pack's lake domain (the pack the {@code .ready} file or a rule names) with the generic layout,
     * its id field {@code key:} else {@code id}.
     */
    private Route newTable(String fileName, String table, String pack, ReadyNote n, Rule rule, FileDates.Name name, Unrouted unknown) throws Unrouted {
        if (!newTables) {
            throw new Unrouted(unknown.getMessage() + "; new-table: true asks for a new table, and this inbox does not allow new tables "
                    + "(drishti.dataload.new-tables)");
        }
        if (pack == null) {
            throw new Unrouted(table + ": a new table needs its pack (its lake and domain): pack: in the .ready file");
        }
        PackInfo info = packs.find(pack);
        if (info == null) {
            throw new Unrouted("no loaded pack is named " + pack + " (the .ready file)");
        }
        String domain;
        try {
            domain = LakeLoad.packDomain(info.dir());
        } catch (java.io.IOException e) {
            throw new Unrouted(pack + ": its pack.yaml cannot be read (" + e.getMessage() + ")");
        }
        LocalDate nameDate = name.error() == null && !name.tar() ? name.date() : null;
        LocalDate asOf = n.businessDate();
        String dateBy = asOf != null ? asOf + " from the .ready file" : nameDate != null ? nameDate + " from the name"
                : FileDates.businessToday() + " (today: no date in the name)";
        return new Route(pack, table, null, table, null, asOf, n.key() != null ? n.key() : "id", rule == null || rule.lake() == null ? defaultLake : rule.lake(),
                rule == null ? "delta" : rule.format(), "a new table " + table + " (new-table: true)", info.dir(), "auto", nameDate, dateBy,
                n.mode() != null ? n.mode() : defaultMode, null, domain);
    }

    /**
     * The routes of a tar archive (DATA_LOAD_RULES.md section 3.1), one per table folder: its pack from the {@code .ready} file's
     * {@code folders:} (folder → pack), else the pack with a kind of that name, else a pack of that name with one kind; any folder that
     * resolves to none (or to two) refuses the whole archive, every one listed. {@code tables}: the archive's folders.
     */
    public List<Route> routeTar(String fileName, List<String> tables, ReadyNote note) throws Unrouted {
        ReadyNote n = note == null ? ReadyNote.EMPTY : note;
        if (n.businessDate() != null || n.table() != null) {
            throw new Unrouted(fileName + ": a .tar archive names its own tables and dates (its <table>/<YYYY-MM-DD>/ folders): its .ready file's "
                    + "business-date: and table: are refused");
        }
        ReadyNote modeOnly = new ReadyNote(null, null, null, null, null, n.mode(), n.sha256(), n.rows(), n.newTable(), n.key(), n.folders(), n.hold());
        Route base = route(fileName, modeOnly, true);           // the rule's (or the defaults') mode, lake, format and key
        List<Route> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String t : tables) {
            String pack = n.folders().getOrDefault(t.toLowerCase(Locale.ROOT), n.pack());
            try {
                String[] pk = byTable(t, pack, pack != null && n.kind() != null && tables.size() == 1 ? n.kind() : null);
                PackInfo info = packs.find(pk[0]);
                if (info == null) {
                    throw new Unrouted("no loaded pack is named " + pk[0]);
                }
                out.add(base.forFolder(t, pk[0], pk[1], info.dir(), "the archive's folder " + t + (pack != null ? " (pack " + pack + ")" : "")));
            } catch (Unrouted e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new Unrouted(fileName + ": refused, nothing was loaded: " + String.join("; ", problems));
        }
        return out;
    }

    /**
     * The pack and kind of a table name (DATA_LOAD_RULES.md section 3): the pack given (its kind of that name, or the kind given); else the
     * one pack with a kind of that name; else a pack of that name with a single kind. None or several: refused, listing them.
     */
    private String[] byTable(String table, String givenPack, String givenKind) throws Unrouted {
        List<String> names = givenPack != null ? List.of(givenPack) : packs.names();
        List<String[]> hits = new ArrayList<>();
        List<String> known = new ArrayList<>();
        for (String p : names) {
            PackInfo info = packs.find(p);
            if (info == null) {
                continue;
            }
            for (String k : info.kinds()) {
                known.add(p + "/" + k);
                if (k.equalsIgnoreCase(table) && (givenKind == null || givenKind.equals(k))) {
                    hits.add(new String[] {p, k});
                }
            }
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (hits.size() > 1) {
            throw new Unrouted(table + ": more than one table of that name (" + String.join(", ", hits.stream().map(h -> h[0] + "/" + h[1]).toList())
                    + "): give the pack and kind (pack: and kind: in the .ready file)");
        }
        for (String p : names) {
            PackInfo info = packs.find(p);
            if (info != null && p.equalsIgnoreCase(table) && (givenKind != null || info.kinds().size() == 1)) {
                return new String[] {p, givenKind != null ? givenKind : info.kinds().get(0)};
            }
        }
        known.sort(String.CASE_INSENSITIVE_ORDER);
        throw new Unrouted(table + ": no pack, kind or table of that name (known: " + (known.isEmpty() ? "none" : String.join(", ", known)) + ")");
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
