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
import com.ash.drishti.lakeconfig.Lake;
import com.ash.drishti.lakeconfig.LakeTable;
import com.ash.drishti.lakeconfig.Lakes;
import com.ash.drishti.lakeconfig.PackEntry;
import com.ash.drishti.lakeload.FileRows;
import com.ash.drishti.lakeload.TableDateRows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Upload form turned into its {@code .ready} text by the inbox's own routing and parser, and that text checked against a sample of the file:
 * the kind's contract, the key, what a replace would remove, and each module's rules ({@link ReadyRule}). A plain file's values come from the
 * form; a tar's tables and dates come from its folders, by the loader's own reading of them.
 */
public final class ReadyChecks {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ReadyDesk desk;

    ReadyChecks(ReadyDesk desk) {
        this.desk = desk;
    }

    /** The form built: the text, the errors per field, what the table resolves to. */
    public record Built(String text, Map<String, String> errors, Map<String, Object> derived, ReadyNote note, Routes.Route route, List<Routes.Route> folderRoutes,
            boolean tar) {}

    Built build(ReadyForm f, ReadyDesk.Item it, String file) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> derived = new LinkedHashMap<>();
        boolean tar = FileDates.isTar(file);
        String lake = one(f.lake(), "lake", ReadyNote::lake, errors);
        String mode = one(f.mode(), "mode", ReadyNote::mode, errors);
        String sha = it == null ? null : it.sha256;
        Long rows = it == null || it.rows < 0 ? null : Long.valueOf(it.rows);
        boolean hold = Boolean.TRUE.equals(f.hold());
        Routes routes;
        try {
            routes = desk.routes();
        } catch (IOException | RuntimeException e) {
            errors.put("route", "the routing rules cannot be read: " + e.getMessage());
            return new Built("", errors, derived, ReadyNote.EMPTY, null, List.of(), tar);
        }
        if (tar) {
            given(f.table(), "table", errors, "a tar names its own tables in its folders: no table is written");
            given(f.businessDate(), "date", errors, "a tar names its own dates in its folders: no business date is written");
            Map<String, String> folders = ReadyWriter.folders(f.folders());
            ReadyNote n = new ReadyNote(null, null, null, null, null, mode, sha, rows, false, null, folders, hold, lake);
            List<Routes.Route> each = List.of();
            if (it != null && it.tables != null) {
                try {
                    each = routes.routeTar(file, new ArrayList<>(it.tables.keySet()), n);
                } catch (Routes.Unrouted e) {
                    errors.put("folders", e.getMessage());
                }
            }
            derived.put("tar", true);
            return new Built(ReadyWriter.text(n), errors, derived, n, null, each, true);
        }
        LocalDate date = null;
        if (present(f.businessDate())) {
            try {
                date = ReadyNote.parse(ReadyWriter.line("business-date", f.businessDate().trim())).businessDate();
            } catch (IllegalArgumentException e) {
                errors.put("date", e.getMessage());
            }
        }
        String key = one(f.key(), "key", ReadyNote::key, errors);
        boolean make = Boolean.TRUE.equals(f.newTable());
        String table = present(f.table()) ? f.table().trim().toLowerCase(Locale.ROOT) : null;
        if (table == null) {
            FileDates.Name nm = FileDates.parse(file);
            if (nm.error() == null && nm.table() != null) {
                table = nm.table();
                derived.put("tableFrom", "the file name");
            } else {
                errors.put("table", "choose the table");
            }
        }
        ReadyNote plain = new ReadyNote(null, null, null, table, date, mode, sha, rows, make, key, Map.of(), hold, lake);
        ReadyNote note = plain;
        Routes.Route route = null;
        if (make) {
            if (!present(f.pack())) {
                errors.put("pack", "a new table needs its pack");
            } else {
                if (present(f.kind()) && !Routes.norm(f.kind()).equals(Routes.norm(table))) {
                    errors.put("kind", "a new table's kind is its own name: " + table);
                }
                note = with(plain, f.pack().trim(), null, null);
            }
        } else if (present(f.pack()) && present(f.kind())) {
            String pack = f.pack().trim();
            String kind = f.kind().trim();
            String connector = present(f.connector()) ? f.connector().trim() : null;
            Routes.Route byName = null;
            try {
                byName = routes.route(file, plain);
            } catch (Routes.Unrouted e) {
                // named by the table alone it does not resolve: the pack and kind are written too
            }
            if (byName == null || !pack.equals(byName.pack()) || !kind.equals(byName.kind())
                    || !java.util.Objects.equals(connector, byName.connector())) {
                note = with(plain, pack, kind, connector);
            }
        }
        if (table != null && errors.isEmpty() || table != null && !errors.containsKey("pack")) {
            try {
                route = routes.route(file, note);
                derived.put("pack", route.pack());
                derived.put("kind", route.kind());
                derived.put("connector", route.connector());
                derived.put("by", route.by());
                derived.put("date", route.dateBy());
                derived.put("lake", route.lake());
                derived.put("contractKey", PackEntry.contractKey(route.packDir(), route.kind()));
            } catch (Routes.Unrouted e) {
                errors.put("table", e.getMessage());
            }
        }
        return new Built(ReadyWriter.text(note), errors, derived, note, route, List.of(), false);
    }

    private static ReadyNote with(ReadyNote n, String pack, String kind, String connector) {
        return new ReadyNote(pack, kind, connector, n.table(), n.businessDate(), n.mode(), n.sha256(), n.rows(), n.newTable(), n.key(), n.folders(), n.hold(), n.lake());
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static void given(String v, String field, Map<String, String> errors, String why) {
        if (present(v)) {
            errors.put(field, why);
        }
    }

    /** One field, through the inbox's own parser. */
    private static String one(String value, String key, java.util.function.Function<ReadyNote, String> read, Map<String, String> errors) {
        if (!present(value)) {
            return null;
        }
        try {
            return read.apply(ReadyNote.parse(ReadyWriter.line(key, value.trim())));
        } catch (IllegalArgumentException e) {
            errors.put(key, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------ checks

    List<ReadyCheck> run(ReadyForm f, ReadyDesk.Item it, Built b) {
        List<ReadyCheck> out = new ArrayList<>();
        int size = desk.upload().sampleRows();
        if (!"ready".equals(it.state)) {
            out.add("failed".equals(it.state) ? ReadyCheck.refused("The file cannot be read: " + it.error, "Check it is whole and in a format the loader reads.")
                    : ReadyCheck.warning("The file is still being read: the checks are not final yet.", null));
            return out;
        }
        b.errors().forEach((k, v) -> out.add(ReadyCheck.refused(v, "Correct the " + k + ".")));
        try {
            String taken = desk.waitingName(it);
            if (taken != null) {
                out.add(ReadyCheck.refused(taken, "Wait for it to be loaded, or rename this file."));
            }
        } catch (IOException e) {
            out.add(ReadyCheck.warning("The inbox folder cannot be read: " + e.getMessage(), null));
        }
        if (it.tar()) {
            tar(f, it, b, size, out);
            return out;
        }
        String rowForm = RowForms.refusal(it.file);
        if (rowForm != null) {
            out.add(ReadyCheck.refused(rowForm, null));
            return out;
        }
        out.add(ReadyCheck.pass("The file reads to the end: " + num(it.rows) + " rows."));
        if (b.route() == null) {
            return out;
        }
        Routes.Route r = b.route();
        LocalDate date = r.asOf() != null ? r.asOf() : r.nameDate() != null ? r.nameDate() : FileDates.businessToday();
        List<JsonNode> sample = sample(it, FileRows.SINGLE, size, out);
        table(r, r.kind(), List.of(date), sample, f, size, false, out);
        return out;
    }

    private void tar(ReadyForm f, ReadyDesk.Item it, Built b, int size, List<ReadyCheck> out) {
        if (it.tarError != null) {
            out.add(ReadyCheck.refused("The archive's layout is refused: " + it.tarError, "Pack it as <table>/<YYYY-MM-DD>/ files."));
            return;
        }
        if (it.tables == null || it.tables.isEmpty()) {
            out.add(ReadyCheck.refused("The archive holds no <table>/<YYYY-MM-DD>/ file: nothing would be loaded.", null));
            return;
        }
        Set<LocalDate> days = new TreeSet<>();
        it.tables.values().forEach(days::addAll);
        out.add(ReadyCheck.pass("The archive holds <table>/<YYYY-MM-DD>/ files and nothing else: " + it.tables.size() + " folders, " + days.size() + " date"
                + (days.size() == 1 ? "" : "s") + ", " + num(it.rows) + " rows."));
        out.add(ReadyCheck.pass("The dates come from the folders, so no business-date: or table: is written."));
        if (b.folderRoutes().isEmpty()) {
            return;                                                    // the unresolved folders were listed as the folders' error
        }
        out.add(ReadyCheck.pass("Every folder has its pack and kind."));
        for (Routes.Route r : b.folderRoutes()) {
            String folder = r.dataset();
            List<JsonNode> sample = sample(it, folder, size, out);
            table(r, folder, new ArrayList<>(it.tables.getOrDefault(folder, new TreeSet<>())), sample, f, size, true, out);
        }
        out.add(ReadyCheck.pass("An archive loads completely or not at all: if one table fails, the tables already written are undone."));
    }

    private List<JsonNode> sample(ReadyDesk.Item it, String dataset, int size, List<ReadyCheck> out) {
        List<JsonNode> rows = new ArrayList<>();
        try {
            int bad = 0;
            for (String line : FileRows.sample(it.file, size).getOrDefault(dataset, List.of())) {
                try {
                    JsonNode n = JSON.readTree(line);
                    if (n != null && n.isObject()) {
                        rows.add(n);
                    } else {
                        bad++;
                    }
                } catch (IOException e) {
                    bad++;
                }
            }
            if (bad > 0) {
                out.add(ReadyCheck.refused(where(dataset) + bad + " of the sampled rows are not JSON documents.", "One JSON document per line."));
            }
        } catch (IOException e) {
            out.add(ReadyCheck.refused(where(dataset) + "the file could not be read: " + e.getMessage(), null));
        }
        return rows;
    }

    private static String where(String dataset) {
        return dataset == null || dataset.isEmpty() ? "" : dataset + ": ";
    }

    /** One table of the file, on each of its dates: the contract, the key, each date's existing rows, the modules' rules. */
    private void table(Routes.Route r, String name, List<LocalDate> dates, List<JsonNode> sample, ReadyForm f, int size, boolean inTar, List<ReadyCheck> out) {
        String at = inTar ? name + ": " : "";
        if (sample.isEmpty()) {
            out.add(ReadyCheck.refused(at + "the file has no rows to check.", "An empty file is ignored by the inbox."));
            return;
        }
        Located loc = locate(r);
        contract(r, loc, sample, size, at, out);
        String key = key(r, loc);
        if (key == null) {
            if (!r.kind().equals(r.dataset()) && r.newDomain() == null) {
                out.add(ReadyCheck.warning(at + "The contract of " + r.kind() + " names no single key.", "Give a key if the table needs one."));
            }
        } else {
            keyCheck(key, loc, sample, at, out);
        }
        String mode = mode(r, loc);
        Set<String> systems = new TreeSet<>();
        sample.forEach(n -> {
            String s = field(n, "SOURCE_SYSTEM", loc.flatten());
            if (s != null) {
                systems.add(s.strip());
            }
        });
        for (LocalDate d : dates) {
            existing(loc, d, mode, systems, at, name, inTar, out);
            ReadyRule.Facts facts = kind -> facts(r, kind, d);
            for (ReadyRule rule : desk.rules()) {
                try {
                    for (ReadyCheck c : rule.check(new ReadyRule.Input(r.kind(), d, mode, sample, facts))) {
                        out.add(new ReadyCheck(c.status(), at + (dates.size() > 1 ? d + ": " : "") + c.text(), c.fix(), c.field(), c.value(), c.tick()));
                    }
                } catch (IOException | RuntimeException e) {
                    out.add(ReadyCheck.warning(at + "Could not check the inputs of the date against the lake: " + e.getMessage(), null));
                }
            }
        }
    }

    /** Where a route's table is. */
    private record Located(String location, String format, Map<String, String> flatten, LakeTable entry, String label, Path packDir, String kind) {}

    private Located locate(Routes.Route r) {
        try {
            PackEntry pe = PackEntry.read(r.packDir(), r.kind(), r.connector());
            String folder = pe.table() != null ? pe.table() : r.table() != null ? r.table() : r.kind();
            Optional<Lake> l = lake(r.lake());
            Optional<LakeTable> t = l.flatMap(x -> {
                try {
                    return x.find(pe.domain(), r.kind(), folder);
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            });
            String root = l.map(Lake::root).orElse(Lakes.validName(r.lake()) ? Lakes.defaultRoot(r.lake()) : r.lake());
            String location = t.isPresent() ? l.get().location(t.get()) : root + "/" + pe.domain() + "/" + folder;
            String format = t.map(LakeTable::format).orElse(l.map(Lake::format).orElse(r.format()));
            Map<String, String> flatten = t.map(LakeTable::flatten).orElse(pe.flatten());
            return new Located(location, format, flatten, t.orElse(null), pe.domain() + "/" + folder, r.packDir(), r.kind());
        } catch (IOException | RuntimeException e) {
            return new Located(null, r.format(), Map.of(), null, r.kind(), r.packDir(), r.kind());
        }
    }

    private static Optional<Lake> lake(String name) {
        try {
            return Lakes.current().find(name);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String mode(Routes.Route r, Located loc) {
        if (r.modeGiven() || loc.entry() == null) {
            return r.mode();
        }
        return loc.entry().loadMode() == null ? r.mode() : loc.entry().loadMode();
    }

    private static String key(Routes.Route r, Located loc) {
        if (r.key() != null) {
            return r.key();
        }
        String k = PackEntry.contractKey(r.packDir(), r.kind());
        return k != null ? k : loc.entry() == null ? null : loc.entry().id();
    }

    private static String field(JsonNode n, String column, Map<String, String> flatten) {
        JsonNode v = n.get(column);
        if ((v == null || v.isNull()) && flatten != null) {
            for (Map.Entry<String, String> e : flatten.entrySet()) {
                if (column.equals(e.getValue())) {
                    v = n;
                    for (String p : e.getKey().split("\\.")) {
                        v = v == null ? null : v.get(p);
                    }
                    break;
                }
            }
        }
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isValueNode() ? v.asText() : v.toString();
        return s.isBlank() ? null : s;
    }

    private void contract(Routes.Route r, Located loc, List<JsonNode> sample, int size, String at, List<ReadyCheck> out) {
        Path f = r.packDir() == null ? null : r.packDir().resolve("contracts").resolve(r.kind() + ".contract.yaml");
        if (f == null || !Files.isRegularFile(f)) {
            out.add(ReadyCheck.warning(at + "The kind " + r.kind() + " has no data contract to check the sample against.", null));
            return;
        }
        JsonNode c;
        try {
            c = new YAMLMapper().readTree(Files.readString(f));
        } catch (IOException e) {
            out.add(ReadyCheck.warning(at + "The contract of " + r.kind() + " cannot be read: " + e.getMessage(), null));
            return;
        }
        String datedBy = c.path("dated-by").asText("");
        Map<String, Integer> absent = new LinkedHashMap<>();
        for (JsonNode fd : c.path("fields")) {
            String name = fd.path("name").asText();
            if (!"required".equals(fd.path("presence").asText()) || name.equals(datedBy)) {
                continue;                                               // the date comes from outside the data, never from a row
            }
            int miss = 0;
            for (JsonNode row : sample) {
                if (field(row, name, loc.flatten()) == null) {
                    miss++;
                }
            }
            if (miss > 0) {
                absent.put(name, miss);
            }
        }
        String of = "Sample of " + num(sample.size()) + " rows" + (sample.size() >= size ? " (the file's first rows)" : "") + " against the " + r.kind() + " contract, version "
                + c.path("version").asText("?");
        if (absent.isEmpty()) {
            out.add(ReadyCheck.pass(at + of + ": every required field is there."));
        } else {
            List<String> parts = new ArrayList<>();
            absent.forEach((k, v) -> parts.add(k + " on " + num(v) + " rows"));
            out.add(ReadyCheck.refused(at + of + ": required fields are missing: " + String.join(", ", parts) + ".", "Correct them at the source and send the file again."));
        }
    }

    private void keyCheck(String key, Located loc, List<JsonNode> sample, String at, List<ReadyCheck> out) {
        Set<String> seen = new HashSet<>();
        int missing = 0;
        int dup = 0;
        for (JsonNode n : sample) {
            String v = field(n, key, loc.flatten());
            if (v == null) {
                missing++;
            } else if (!seen.add(v)) {
                dup++;
            }
        }
        if (missing == sample.size()) {
            out.add(ReadyCheck.refused(at + "No sampled row has the key " + key + ".", "Choose the field that identifies a row."));
        } else if (missing > 0) {
            out.add(ReadyCheck.refused(at + "The key " + key + " is empty on " + num(missing) + " of the sampled rows.", "Correct them at the source."));
        } else if (dup > 0) {
            out.add(ReadyCheck.warning(at + "The key " + key + " repeats on " + num(dup) + " of the sampled rows: readers take the newest row of each.", "Check the file does not hold the same record twice."));
        } else {
            out.add(ReadyCheck.pass(at + "The key " + key + " is unique in the sample."));
        }
    }

    /** What the table already holds on the date, and what this file's mode does to it. */
    private void existing(Located loc, LocalDate date, String mode, Set<String> systems, String at, String name, boolean inTar, List<ReadyCheck> out) {
        if (loc.location() == null) {
            return;
        }
        if ("iceberg".equalsIgnoreCase(loc.format())) {
            if ("replace".equals(mode)) {
                out.add(ReadyCheck.warning(at + "This Iceberg table's rows for " + date + " are not counted here: replace removes every row of the date, whichever system sent it.",
                        "Use append if other files or systems already sent this date.").ticked());
            }
            return;
        }
        TableDateRows.Counts counts;
        try {
            counts = TableDateRows.count(loc.location(), date.toString(), ReadyRule.SENDER_COLUMNS);
        } catch (IOException | RuntimeException e) {
            out.add(ReadyCheck.warning(at + "The rows already in the table for " + date + " could not be counted: " + e.getMessage(), null));
            return;
        }
        if (counts.rows() == 0) {
            out.add(ReadyCheck.pass(at + loc.label() + " holds no rows for " + date + "."));
            return;
        }
        Map<String, Long> bySystem = new TreeMap<>();
        counts.groups().forEach((k, v) -> bySystem.merge(counts.columns().contains("SOURCE_SYSTEM") ? k.get(counts.columns().indexOf("SOURCE_SYSTEM")) : "", v, Long::sum));
        List<String> named = new ArrayList<>();
        bySystem.forEach((s, v) -> named.add((s.isEmpty() ? "(no source system)" : s) + " " + num(v)));
        String held = at + "The table " + loc.label() + " already holds " + num(counts.rows()) + " rows for " + date + " (" + String.join(", ", named) + ").";
        if ("replace".equals(mode)) {
            List<String> others = new ArrayList<>();
            bySystem.forEach((s, v) -> {
                if (!s.isEmpty() && !systems.contains(s)) {
                    others.add(s + " " + num(v));
                }
            });
            String words = held + " Replace removes every one of them in one commit, including any earlier file of this date"
                    + (others.isEmpty() ? "" : ": the rows of " + String.join(", ", others) + " are not in this file's sample and would be lost") + ".";
            String fix = "If several files make up this date, " + (inTar ? "" : "load them together in one tar (one replace, all or nothing), or ")
                    + "use append. Tick only if this file really takes the place of all of them; Undo a load brings them back.";
            out.add(ReadyCheck.warning(words, fix).offering("mode", "append").ticked());
        } else {
            out.add(ReadyCheck.pass(held + " Append adds these rows beside them."));
        }
    }

    private Map<List<String>, Long> facts(Routes.Route r, String kind, LocalDate date) throws IOException {
        Routes.Route same = r.kind().equals(kind) ? r
                : new Routes.Route(r.pack(), kind, null, null, null, null, null, r.lake(), r.format(), r.by(), r.packDir(), r.dateFrom(), null, r.dateBy(), r.mode(), null);
        Located l = locate(same);
        if (l.location() == null) {
            return Map.of();
        }
        if ("iceberg".equalsIgnoreCase(l.format())) {
            throw new IOException("the table of " + kind + " is an Iceberg table, not counted here");
        }
        TableDateRows.Counts c = TableDateRows.count(l.location(), date.toString(), ReadyRule.SENDER_COLUMNS);
        Map<List<String>, Long> out = new LinkedHashMap<>();
        c.groups().forEach((k, v) -> {
            List<String> key = new ArrayList<>();
            for (String col : ReadyRule.SENDER_COLUMNS) {
                int i = c.columns().indexOf(col);
                key.add(i < 0 ? "" : k.get(i));
            }
            out.put(key, v);
        });
        return out;
    }

    private static String num(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
