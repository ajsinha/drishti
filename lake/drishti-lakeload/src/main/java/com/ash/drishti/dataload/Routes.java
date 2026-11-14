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
 * Routing a file to a table (DATA_LAKE_GUIDE.md section 5.1): the {@code .ready} file's keys first, then the first rule of
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

        /** Every loaded pack's name (a table name is looked up among them). */
        default List<String> names() {
            return List.of();
        }

        /**
         * The lakes the server's Delta and Iceberg connectors read ({@code lake:} of each running connector): a load into any other lake is
         * told that nothing reads it yet. Null: not known (no warning).
         */
        default java.util.Set<String> lakesRead() {
            return null;
        }

        /**
         * The inbox rules the installed modules that are on bring ({@code dataload:} of each module), read after the site's own: the module
         * and its rules as YAML ({@code dataload: {rules: [...]}}).
         */
        default List<ModuleRules> moduleRules() {
            return List.of();
        }
    }

    /** One module's inbox rules: the module's id (the rule's origin in Admin) and its document. */
    public record ModuleRules(String module, JsonNode document) {}

    /**
     * Where a file goes: the pack and kind, the pack's connector (null: the one serving the kind), the table (null: the connector's), the
     * date field (null: the pack's) or one business date for every row ({@code asOf}), the id field (null: the pack's), the lake and the
     * table format; {@code by} says which rule (or the {@code .ready} file) decided. {@code dateFrom} is the loader's {@code source.date-from}:
     * {@code auto}, a date at the end of a file's name (an archive member's own first: {@code FileDates}) is every row's business date, or
     * {@code field}, the rows' own; {@code nameDate} the date the file's name carries (what the Admin screen shows), {@code dateBy} where the
     * date comes from in words. {@code mode} is the load mode, {@code modeGiven} whether the {@code .ready} file gave it (else it is the
     * inbox's default-mode, which a lake file's entry for the table overrides: DATA_LAKE_GUIDE.md section 5).
     */
    public record Route(String pack, String kind, String connector, String table, String dateField, LocalDate asOf, String key, String lake,
            String format, String by, Path packDir, String dateFrom, LocalDate nameDate, String dateBy, String mode, String dataset, String newDomain,
            boolean modeGiven) {

        Route(String pack, String kind, String connector, String table, String dateField, LocalDate asOf, String key, String lake, String format,
                String by, Path packDir, String dateFrom, LocalDate nameDate, String dateBy, String mode, String dataset) {
            this(pack, kind, connector, table, dateField, asOf, key, lake, format, by, packDir, dateFrom, nameDate, dateBy, mode, dataset, null, false);
        }

        /** The files of one table and one way of reading them go to one load. */
        public String group() {
            return String.join("|", pack, kind, String.valueOf(connector), String.valueOf(table), String.valueOf(dateField), String.valueOf(asOf),
                    String.valueOf(key), lake, format, dateFrom, mode, String.valueOf(dataset), String.valueOf(newDomain));
        }

        /** The same route for one table folder of a tar ({@code source.dataset}). */
        Route forFolder(String folder, String pack2, String kind2, String connector2, Path dir2, String by2) {
            return new Route(pack2, kind2, connector2, null, dateField, null, key, lake, format, by2, dir2, dateFrom, null,
                    "each table and date from the archive's folders", mode, folder, null, modeGiven);
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
            String lake, String format, String origin) {}

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
        JsonNode d = null;
        if (!Files.isRegularFile(file)) {
            where = where + " (not there)";
        } else {
            JsonNode root = new YAMLMapper().readTree(Files.readString(file));
            d = root == null ? null : root.path("dataload");
            if (d != null && (d.isMissingNode() || d.isNull())) {
                d = null;
            }
        }
        String unmatched = d == null ? "fail" : d.path("unmatched").asText("fail").trim().toLowerCase(Locale.ROOT);
        if (!unmatched.equals("fail")) {
            throw new IllegalArgumentException(where + ": dataload.unmatched " + unmatched + ": fail is the only choice (a file is never guessed)");
        }
        List<Rule> out = new ArrayList<>(d == null ? List.of() : parseRules(d, where, null));
        for (ModuleRules m : packs == null ? List.<ModuleRules>of() : packs.moduleRules()) {      // the modules that are on, after the site's own
            JsonNode md = m.document() == null ? null : m.document().path("dataload");
            if (md != null && !md.isMissingNode() && !md.isNull()) {
                out.addAll(parseRules(md, "module " + m.module() + ": dataload", m.module()));
            }
        }
        return new Routes(List.copyOf(out), unmatched, defaultLake, packs, where);
    }

    /** The rules of one {@code dataload:} block; {@code origin} is the module they come from (null: the site's own file). */
    private static List<Rule> parseRules(JsonNode d, String where, String origin) {
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
                    text(r, "lake"), format, origin));
        }
        return out;
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
            m.put("origin", r.origin() == null ? "site" : r.origin());
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
            m.put("lake", r.lake());
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
     * Where {@code fileName} goes (docs/guides/DATA_LAKE_GUIDE.md section 5): the pack and kind from its {@code .ready} file, else the
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
        String tableConnector = null;                           // a table name (trade_endur) resolves to its kind and connector
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
            tableConnector = pk[2];
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
        return new Route(pack, kind, n.connector() != null ? n.connector() : rule != null && rule.connector() != null ? rule.connector() : tableConnector,
                rule == null ? null : rule.table(), rule == null ? null : rule.dateField(), asOf,
                n.key() != null ? n.key() : rule == null ? null : rule.key(), lake(n, rule),
                rule == null ? "delta" : rule.format(), by, info.dir(), dateFrom, nameDate, dateBy, mode, null, null, n.mode() != null);
    }

    /**
     * A table no pack has, asked for ({@code new-table: true}; DATA_LAKE_GUIDE.md section 5.6): allowed only when the inbox allows new tables
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
        return new Route(pack, table, null, table, null, asOf, n.key() != null ? n.key() : "id", lake(n, rule),
                rule == null ? "delta" : rule.format(), "a new table " + table + " (new-table: true)", info.dir(), "auto", nameDate, dateBy,
                n.mode() != null ? n.mode() : defaultMode, null, domain, n.mode() != null);
    }

    /**
     * The routes of a tar archive (DATA_LAKE_GUIDE.md section 4.4), one per table folder: its pack from the {@code .ready} file's
     * {@code folders:} (folder → pack), else the pack with a kind of that name, else a pack of that name with one kind; any folder that
     * resolves to none (or to two) refuses the whole archive, every one listed. {@code tables}: the archive's folders.
     */
    public List<Route> routeTar(String fileName, List<String> tables, ReadyNote note) throws Unrouted {
        ReadyNote n = note == null ? ReadyNote.EMPTY : note;
        if (n.businessDate() != null || n.table() != null) {
            throw new Unrouted(fileName + ": a .tar archive names its own tables and dates (its <table>/<YYYY-MM-DD>/ folders): its .ready file's "
                    + "business-date: and table: are refused");
        }
        ReadyNote modeOnly = new ReadyNote(null, null, null, null, null, n.mode(), n.sha256(), n.rows(), n.newTable(), n.key(), n.folders(), n.hold(), n.lake());
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
                out.add(base.forFolder(t, pk[0], pk[1], pk[2], info.dir(), "the archive's folder " + t + (pack != null ? " (pack " + pack + ")" : "")
                        + (pk[2] != null ? " (the table of " + pk[2] + ")" : "")));
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
     * The pack, kind and connector of a table name (DATA_LAKE_GUIDE.md sections 4.4 and 5): the pack given (its kind of that name, or the
     * kind given); else the one pack with a kind of that name; else the one pack whose Delta or Iceberg connector writes a table of that
     * name ({@code trade_endur}: the kind {@code trade}, the connector {@code trade-endur}); else a pack of that name with a single kind.
     * Names compare without case, and {@code _} and {@code -} alike ({@code var_limit} is the kind {@code var-limit}). None or several:
     * refused, listing them. The connector is null for a kind's name. A removed kind ({@code RemovedKinds}) is refused with what replaces it.
     */
    private String[] byTable(String table, String givenPack, String givenKind) throws Unrouted {
        List<String> names = givenPack != null ? List.of(givenPack) : packs.names();
        String want = norm(table);
        if (com.ash.drishti.lakeconfig.RemovedKinds.replacement(want) != null) {
            throw new Unrouted(table + ": " + com.ash.drishti.lakeconfig.RemovedKinds.replacement(want));
        }
        List<String[]> hits = new ArrayList<>();
        List<String> known = new ArrayList<>();
        for (String p : names) {
            PackInfo info = packs.find(p);
            if (info == null) {
                continue;
            }
            for (String k : info.kinds()) {
                known.add(p + "/" + k);
                if (norm(k).equals(want) && (givenKind == null || givenKind.equals(k))) {
                    hits.add(new String[] {p, k, null});
                }
            }
        }
        if (hits.isEmpty()) {
            for (String p : names) {
                PackInfo info = packs.find(p);
                if (info == null) {
                    continue;
                }
                for (com.ash.drishti.lakeconfig.PackEntry.PackTable t : tablesOf(info)) {
                    if (norm(t.table()).equals(norm(t.kind())) || !info.kinds().contains(t.kind())) {
                        continue;                               // a kind's own name: matched above
                    }
                    known.add(p + "/" + t.table());
                    if (norm(t.table()).equals(want) && (givenKind == null || givenKind.equals(t.kind()))) {
                        hits.add(new String[] {p, t.kind(), t.connector()});
                    }
                }
            }
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (hits.size() > 1) {
            throw new Unrouted(table + ": more than one table of that name (" + String.join(", ", hits.stream().map(h -> h[0] + "/" + h[1]
                    + (h[2] == null ? "" : " (" + h[2] + ")")).toList()) + "): give the pack and kind (pack: and kind: in the .ready file)");
        }
        for (String p : names) {
            PackInfo info = packs.find(p);
            if (info != null && norm(p).equals(want) && (givenKind != null || info.kinds().size() == 1)) {
                return new String[] {p, givenKind != null ? givenKind : info.kinds().get(0), null};
            }
        }
        known.sort(String.CASE_INSENSITIVE_ORDER);
        throw new Unrouted(table + ": no pack, kind or table of that name (known: " + (known.isEmpty() ? "none" : String.join(", ", known)) + ")");
    }

    /** A table or kind name as compared: lower case, {@code _} as {@code -}. */
    static String norm(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The pack's tables by connector (its pack.yaml read once per change of the file). */
    private final Map<Path, Map.Entry<Long, List<com.ash.drishti.lakeconfig.PackEntry.PackTable>>> packTables =
            new java.util.concurrent.ConcurrentHashMap<>();

    private List<com.ash.drishti.lakeconfig.PackEntry.PackTable> tablesOf(PackInfo info) {
        if (info.dir() == null) {
            return List.of();
        }
        Path f = Files.isDirectory(info.dir()) ? info.dir().resolve("pack.yaml") : info.dir();
        try {
            long stamp = Files.getLastModifiedTime(f).toMillis();
            var hit = packTables.get(f);
            if (hit != null && hit.getKey() == stamp) {
                return hit.getValue();
            }
            List<com.ash.drishti.lakeconfig.PackEntry.PackTable> ts = com.ash.drishti.lakeconfig.PackEntry.tables(f);
            packTables.put(f, Map.entry(stamp, ts));
            return ts;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** The lake a file goes to (DATA_LAKE_GUIDE.md section 4): the {@code .ready} file's {@code lake:}, else the rule's, else the inbox's. */
    private String lake(ReadyNote n, Rule rule) {
        if (n.lake() != null) {
            return n.lake();
        }
        return rule == null || rule.lake() == null ? defaultLake : rule.lake();
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
