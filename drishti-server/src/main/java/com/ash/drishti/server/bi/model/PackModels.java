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
package com.ash.drishti.server.bi.model;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.SourceGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A dataset started from the packs (RUPAKA.md section 6.2). Every kind of every pack the reader may open is offered as a table, with the
 * columns its connector promotes (typed from the data) or, failing that, the fields of the pack's sample documents. The packs' link graph
 * (<code>graph.fields</code>: <code>nettingSet</code> is a netting set) is read as ready-made relationships, across packs; where a kind has
 * no declared link, relationships are <em>proposed</em> from the data by value matching: the share of one column's values found among the
 * other table's ids, with the cardinality seen. Profiling, not AI; nothing is accepted on its own.
 */
public final class PackModels {

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ].*)?");

    /** A column offered for a table. */
    public record ColumnInfo(String name, String path, String type, boolean promoted) {}

    /** A kind offered as a table. */
    public record TableInfo(String table, String pack, String kind, String connector, boolean data, String source, List<ColumnInfo> columns) {}

    /** A relationship, declared by a pack or proposed from the data. */
    public record Link(String fromTable, String fromColumn, String toTable, String toColumn, String cardinality, boolean crossPack, String basis, Double matchRate,
            Integer sampled, Integer matched, List<String> unmatched) {}

    /** The draft: the YAML, the tables, the links read from the packs, the proposals, and anything to know. */
    public record Draft(String yaml, List<TableInfo> tables, List<Link> relationships, List<Link> proposals, List<String> warnings) {}

    private final PackRegistry packs;
    private final ReferenceCatalog graph;
    private final SourceGateway sources;
    private final QueryProperties props;
    private final Predicate<String> mayOpen;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    /** @param mayOpen whether the reader may open a kind (their roles allow it and its pack is active for them) */
    public PackModels(PackRegistry packs, ReferenceCatalog graph, SourceGateway sources, QueryProperties props, Predicate<String> mayOpen) {
        this.packs = packs;
        this.graph = graph;
        this.sources = sources;
        this.props = props;
        this.mayOpen = mayOpen;
    }

    // ---- the offer ---------------------------------------------------------------------------------------------------

    /** Every kind the reader may open, with its columns. */
    public List<TableInfo> offer() {
        List<TableInfo> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Pack pack : packs.packs()) {
            for (String kind : pack.kinds()) {
                if (mayOpen.test(kind)) {
                    out.add(info(pack, kind, names));
                }
            }
        }
        out.sort(Comparator.comparing(TableInfo::pack).thenComparing(TableInfo::kind));
        return out;
    }

    private TableInfo info(Pack pack, String kind, Set<String> taken) {
        String connector = pack.routes().getOrDefault(kind, "");
        Optional<SourcePlugin> plugin = sources.plugin(connector, kind);
        String table = tableName(kind, pack.name(), taken);
        List<ColumnInfo> cols = new ArrayList<>();
        String source = "";
        boolean data = false;
        if (plugin.isPresent()) {
            List<String> paths = plugin.get().columnar(kind).stream().sorted().toList();
            if (!paths.isEmpty()) {
                source = "the connector's promoted columns";
                Optional<ColumnSet> sample = Optional.empty();
                try {
                    sample = plugin.get().columns(kind, paths, AsOf.LATEST);
                } catch (Exception e) {
                    source += " (not readable now: " + e.getMessage() + ")";
                }
                data = sample.isPresent() && sample.get().size() > 0;
                for (String path : paths) {
                    cols.add(new ColumnInfo(columnName(path), path, sample.map(s -> typeOf(s, path)).orElse("string"), true));
                }
            }
        }
        if (cols.isEmpty()) {
            source = "the pack's sample documents";
            cols.addAll(fromSamples(pack, kind));
        }
        return new TableInfo(table, pack.name(), kind, connector, data, source, cols);
    }

    private static String typeOf(ColumnSet s, String path) {
        if (s.numbers().containsKey(path)) {
            return "decimal";
        }
        String[] t = s.texts().get(path);
        if (t != null) {
            for (int i = 0; i < t.length && i < 200; i++) {
                if (t[i] != null && !t[i].isBlank()) {
                    return ISO_DATE.matcher(t[i]).matches() ? "date" : "string";
                }
            }
        }
        return "string";
    }

    /** Top-level and one-deep scalar fields of the pack's sample documents. */
    private List<ColumnInfo> fromSamples(Pack pack, String kind) {
        Path dir = pack.resolve("samples").resolve(kind);
        Map<String, ColumnInfo> cols = new LinkedHashMap<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.sorted().limit(5).toList()) {
                    JsonNode doc = new ObjectMapper().readTree(Files.readString(f));
                    doc.fields().forEachRemaining(e -> {
                        if (!e.getKey().startsWith("_")) {                       // _meta and friends are the server's, not the entity's
                            scalars(e.getKey(), e.getValue(), 0, cols);
                        }
                    });
                }
            } catch (IOException | RuntimeException e) {
                // a sample that does not parse is simply not used
            }
        }
        return new ArrayList<>(cols.values());
    }

    private static void scalars(String path, JsonNode n, int depth, Map<String, ColumnInfo> out) {
        if (n.isObject() && depth < 1) {
            n.fields().forEachRemaining(e -> scalars(path + "." + e.getKey(), e.getValue(), depth + 1, out));
        } else if (n.isNumber()) {
            out.putIfAbsent(path, new ColumnInfo(columnName(path), path, n.isIntegralNumber() ? "integer" : "decimal", false));
        } else if (n.isBoolean()) {
            out.putIfAbsent(path, new ColumnInfo(columnName(path), path, "boolean", false));
        } else if (n.isTextual()) {
            out.putIfAbsent(path, new ColumnInfo(columnName(path), path, ISO_DATE.matcher(n.asText()).matches() ? "date" : "string", false));
        }
    }

    // ---- names -------------------------------------------------------------------------------------------------------

    static String tableName(String kind, String pack, Set<String> taken) {
        String base = kind.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "t_" + base;
        }
        String name = base;
        if (!taken.add(name)) {
            name = pack.replaceAll("[^A-Za-z0-9]+", "_") + "_" + base;
            taken.add(name);
        }
        return name;
    }

    /** A column name from a document path: {@code counterparty.id} is {@code counterpartyId}. */
    static String columnName(String path) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : path.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            } else {
                up = sb.length() > 0;
            }
        }
        String s = sb.length() == 0 ? "col" : sb.toString();
        if (!Character.isLetter(s.charAt(0)) && s.charAt(0) != '_') {
            s = "c" + s;
        }
        return s.equals("id") || s.equals("index") || s.equals("businessDate") ? s + "Value" : s;
    }

    // ---- start a dataset ---------------------------------------------------------------------------------------------

    /**
     * @param kinds the kinds to start from
     * @param name the dataset's name
     * @throws DrishtiException DRS-9020 when a kind is not one the reader may open
     */
    public Draft start(List<String> kinds, String name) {
        if (kinds == null || kinds.isEmpty()) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "choose at least one kind to start a dataset from");
        }
        List<TableInfo> offer = offer();
        List<TableInfo> chosen = new ArrayList<>();
        for (String k : new LinkedHashSet<>(kinds)) {
            chosen.add(offer.stream().filter(t -> t.kind().equals(k)).findFirst().orElseThrow(
                    () -> new DrishtiException(ErrorCode.QUERY_INVALID, "'" + k + "' is not a kind you may open (kinds: " + offer.stream().map(TableInfo::kind).toList() + ")")));
        }
        List<String> warnings = new ArrayList<>();
        List<Link> declared = declared(chosen);
        List<Link> proposals = infer(chosen, declared, warnings);
        for (TableInfo t : chosen) {
            if (!t.data()) {
                warnings.add(t.table() + ": the connector has no data to look at now, so its types are guesses from " + t.source());
            }
            if (t.columns().isEmpty()) {
                warnings.add(t.table() + ": no columns are known for " + t.kind() + "; promote some in the connector's layout or add them by hand");
            }
        }
        return new Draft(yaml(chosen, declared, name), chosen, declared, proposals, warnings);
    }

    /** The links the packs declare between the chosen kinds. */
    List<Link> declared(List<TableInfo> chosen) {
        List<Link> out = new ArrayList<>();
        for (TableInfo from : chosen) {
            for (ColumnInfo c : from.columns()) {
                String[] parts = c.path().split("\\.");
                String field = parts.length == 2 && parts[1].equals("id") ? parts[0] : parts[parts.length - 1];
                Optional<String> target = graph.kindOfField(field);
                if (target.isEmpty() || target.get().equals(from.kind())) {
                    continue;
                }
                for (TableInfo to : chosen) {
                    if (to.kind().equals(target.get())) {
                        out.add(new Link(from.table(), c.name(), to.table(), "id", "many-to-one", !from.pack().equals(to.pack()),
                                "declared: the packs' link graph names " + field + " as a " + to.kind(), null, null, null, null));
                    }
                }
            }
        }
        return out;
    }

    // ---- inference ---------------------------------------------------------------------------------------------------

    /** Proposals only, for the chosen kinds (the Model view's "find relationships"). */
    public List<Link> proposals(List<String> kinds) {
        List<TableInfo> offer = offer();
        List<TableInfo> chosen = offer.stream().filter(t -> kinds.contains(t.kind())).toList();
        return infer(chosen, declared(chosen), new ArrayList<>());
    }

    /**
     * For each text column of one table and each other table's ids: how many of the column's distinct values are ids of the other table. A
     * proposal needs {@code infer-min-match} of at least three distinct values; cardinality is many-to-one, or one-to-one when the column is
     * itself unique. Reads at most {@code infer-sample} values per column from the latest day of the connector's promoted columns.
     */
    List<Link> infer(List<TableInfo> chosen, List<Link> declared, List<String> warnings) {
        Map<String, Set<String>> ids = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> values = new LinkedHashMap<>();
        for (TableInfo t : chosen) {
            Optional<SourcePlugin> plugin = sources.plugin(t.connector(), t.kind());
            List<String> paths = t.columns().stream().filter(ColumnInfo::promoted).map(ColumnInfo::path).toList();
            if (plugin.isEmpty()) {
                continue;
            }
            if (paths.isEmpty()) {
                documents(t, ids, values, warnings);
                continue;
            }
            try {
                Optional<ColumnSet> set = plugin.get().columns(t.kind(), paths, AsOf.LATEST);
                if (set.isEmpty()) {
                    continue;
                }
                int n = Math.min(set.get().size(), props.inferSample());
                Set<String> idSet = new HashSet<>();
                for (int i = 0; i < n; i++) {
                    idSet.add(set.get().ids()[i]);
                }
                ids.put(t.table(), idSet);
                Map<String, List<String>> cols = new LinkedHashMap<>();
                for (ColumnInfo c : t.columns()) {
                    String[] texts = set.get().texts().get(c.path());
                    if (texts != null && c.promoted()) {
                        List<String> v = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            if (texts[i] != null && !texts[i].isBlank()) {
                                v.add(texts[i]);
                            }
                        }
                        cols.put(c.name(), v);
                    }
                }
                values.put(t.table(), cols);
            } catch (Exception e) {
                warnings.add(t.table() + ": cannot read values to look for relationships (" + e.getMessage() + ")");
            }
        }
        Set<String> known = new HashSet<>();
        declared.forEach(l -> known.add(l.fromTable() + "." + l.fromColumn() + ">" + l.toTable()));
        List<Link> out = new ArrayList<>();
        for (TableInfo from : chosen) {
            Map<String, List<String>> cols = values.getOrDefault(from.table(), Map.of());
            for (Map.Entry<String, List<String>> col : cols.entrySet()) {
                Set<String> distinct = new LinkedHashSet<>(col.getValue());
                if (distinct.size() < 3) {
                    continue;
                }
                for (TableInfo to : chosen) {
                    if (to.table().equals(from.table()) || !ids.containsKey(to.table()) || known.contains(from.table() + "." + col.getKey() + ">" + to.table())) {
                        continue;
                    }
                    List<String> unmatched = new ArrayList<>();
                    int matched = 0;
                    for (String v : distinct) {
                        if (ids.get(to.table()).contains(v)) {
                            matched++;
                        } else if (unmatched.size() < 3) {
                            unmatched.add(v);
                        }
                    }
                    double rate = (double) matched / distinct.size();
                    if (rate >= props.inferMinMatch()) {
                        boolean unique = distinct.size() == col.getValue().size();
                        out.add(new Link(from.table(), col.getKey(), to.table(), "id", unique ? "one-to-one" : "many-to-one", !from.pack().equals(to.pack()),
                                "inferred: " + matched + " of " + distinct.size() + " distinct values of " + from.table() + "." + col.getKey() + " are ids of " + to.table(),
                                Math.round(rate * 1000) / 1000.0, distinct.size(), matched, unmatched));
                    }
                }
            }
        }
        out.sort(Comparator.comparing((Link l) -> -l.matchRate()).thenComparing(Link::fromTable));
        return out;
    }

    /** A table whose connector promotes no columns: ids and the text values of its columns from up to {@code infer-sample} documents. */
    private void documents(TableInfo t, Map<String, Set<String>> ids, Map<String, Map<String, List<String>>> values, List<String> warnings) {
        try {
            List<com.ash.drishti.api.EntityRef> refs = sources.ids(t.kind(), null, props.inferSample());
            if (refs.isEmpty()) {
                return;
            }
            Map<com.ash.drishti.api.EntityRef, com.ash.drishti.api.EntityDocument> docs = sources.read(refs, null);
            Set<String> idSet = new HashSet<>();
            Map<String, List<String>> cols = new LinkedHashMap<>();
            for (ColumnInfo c : t.columns()) {
                if ("string".equals(c.type())) {
                    cols.put(c.name(), new ArrayList<>());
                }
            }
            for (var e : docs.entrySet()) {
                idSet.add(e.getKey().id());
                for (ColumnInfo c : t.columns()) {
                    if (cols.containsKey(c.name())) {
                        var node = e.getValue().data().at(c.path());
                        if (!node.isNull() && !node.isMasked() && !node.asText().isBlank()) {
                            cols.get(c.name()).add(node.asText());
                        }
                    }
                }
            }
            ids.put(t.table(), idSet);
            values.put(t.table(), cols);
        } catch (RuntimeException e) {
            warnings.add(t.table() + ": cannot read documents to look for relationships (" + e.getMessage() + ")");
        }
    }

    // ---- the YAML ----------------------------------------------------------------------------------------------------

    private String yaml(List<TableInfo> chosen, List<Link> links, String name) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("rachana", 1);
        doc.put("dataset", name == null || name.isBlank() ? "new-model" : name.replaceAll("[^A-Za-z0-9_.-]+", "-"));
        doc.put("version", 1);
        doc.put("title", "New model from " + String.join(", ", chosen.stream().map(TableInfo::kind).toList()));
        doc.put("description", "Started from the packs' kinds. Edit the columns, relationships and measures; nothing here changes a pack.");
        Map<String, Object> tables = new LinkedHashMap<>();
        Map<String, Object> measures = new LinkedHashMap<>();
        for (TableInfo t : chosen) {
            Map<String, Object> table = new LinkedHashMap<>();
            Map<String, Object> src = new LinkedHashMap<>();
            if (!t.connector().isBlank()) {
                src.put("connector", t.connector());
            }
            src.put("kind", t.kind());
            table.put("source", src);
            Map<String, Object> cols = new LinkedHashMap<>();
            for (ColumnInfo c : t.columns()) {
                if (c.path().equals(c.name())) {
                    cols.put(c.name(), c.type());                                 // shorthand: just the type
                } else {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("path", c.path());
                    col.put("type", c.type());
                    cols.put(c.name(), col);
                }
            }
            table.put("columns", cols);
            tables.put(t.table(), table);
            measures.put("Count of " + t.table(), Map.of("expr", "count(" + t.table() + ")"));
            t.columns().stream().filter(c -> "decimal".equals(c.type())).limit(2)
                    .forEach(c -> measures.put("Total " + t.table() + " " + c.name(), Map.of("expr", "sum(" + t.table() + "." + c.name() + ")")));
        }
        doc.put("tables", tables);
        List<Map<String, Object>> rels = new ArrayList<>();
        for (Link l : links) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from", l.fromTable() + "." + l.fromColumn());
            r.put("to", l.toTable() + "." + l.toColumn());
            r.put("cardinality", l.cardinality());
            rels.add(r);
        }
        if (!rels.isEmpty()) {
            doc.put("relationships", rels);
        }
        doc.put("measures", measures);
        try {
            return YAML.writeValueAsString(doc);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "cannot write the draft: " + e.getMessage());
        }
    }
}
