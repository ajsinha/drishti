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
package com.ash.drishti.server.tables;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewerContext;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.RoleDefinition;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.data.DataViewService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RoleCatalog;
import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.answer.ModelAnswers;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.catalog.Lineage;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.read.ScanEstimate;
import com.ash.drishti.tables.read.SourceRows;
import com.ash.drishti.tables.read.TableOffer;
import com.ash.drishti.tables.read.TableReads;
import com.ash.drishti.tables.yaml.ModelYaml;
import com.ash.drishti.tables.yaml.TableYaml;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * The editors of tables, virtual tables, semantic models and Python-driven tables (EDITORS.md sections 1 to 4) on the table layer: the
 * objects a person may open, each definition's YAML and document, the checks as typed (the layer's own, with every problem at its line), the
 * Text toggle's statement, a live preview read on the server as the person may see the source (rows, totals by the pivot engine, checks,
 * the cost of a join across connectors), a model's measures with the engine's "adds up?", the Access matrix, impact, history, and saving
 * and proposing through BI's item store (four eyes: {@link BiService#propose}). Nothing here is kept per person except the person's own
 * preferences in the preference store; every answer is worked out for the request that asks.
 */
public final class EditorService {

    /** The preference namespace and key of a person's editor view (Visual, YAML or Both; the YAML panel open or closed). */
    static final String PREFS = "editors";
    static final String VIEW = "view";
    static final Set<String> MODES = Set.of("visual", "yaml", "both");
    /** The rows a preview shows; its totals and checks are over every row read. */
    static final int SHOWN = 20;
    /** The documents a + New suggestion reads to propose columns. */
    static final int SAMPLE = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TablesService tables;
    private final DataViewService data;
    private final Entitlements entitlements;
    private final BiAccess access;
    private final BiService bi;
    private final PreferenceStore preferences;
    private final RoleCatalog roles;
    private final boolean securityOn;

    public EditorService(TablesService tables, DataViewService data, Entitlements entitlements, BiAccess access, BiService bi, PreferenceStore preferences,
            RoleCatalog roles, boolean securityOn) {
        this.tables = tables;
        this.data = data;
        this.entitlements = entitlements;
        this.access = access;
        this.bi = bi;
        this.preferences = preferences;
        this.roles = roles;
        this.securityOn = securityOn;
        bi.tablesCheck(this::publishCheck);
    }

    /** What proposing or publishing a table or a model of the layer checks: the layer's checks in the catalogue, the errors in words. */
    String publishCheck(Principal p, BiStore.Item i) {
        EditorYaml.Read read = EditorYaml.read(i.body());
        Object name = read.ok() ? read.doc().get(i.kind()) : null;
        Map<String, Object> c = check(p, i.kind(), i.body(), null, name == null ? null : String.valueOf(name));
        List<String> errors = new ArrayList<>();
        if (c.get("problems") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && "error".equals(m.get("level"))) {
                    errors.add((Integer.valueOf(0).equals(m.get("line")) ? "" : "line " + m.get("line") + ": ") + m.get("message"));
                }
            }
        }
        return String.join("; ", errors);
    }

    // ---- who may -----------------------------------------------------------------------------------------------------------------

    /** Opening the editors needs BI (or an administrator). */
    void requireRead(Principal p) {
        if (securityOn && !entitlements.isAdmin(p) && !access.has(p, BiAccess.BI)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, "the table and model editors need the bi power (or an administrator)");
        }
    }

    /** Editing: administrators and BI authors (VIRTUAL_TABLES.md V9: the power to edit BI datasets). */
    boolean mayEdit(Principal p) {
        return !securityOn || entitlements.isAdmin(p) || access.has(p, BiAccess.AUTHOR);
    }

    void requireEdit(Principal p) {
        if (!mayEdit(p)) {
            throw new DrishtiException(ErrorCode.BI_FORBIDDEN, "saving and proposing tables and models needs the bi-author power or an administrator: "
                    + "you may read the YAML, not change it");
        }
    }

    // ---- preferences -------------------------------------------------------------------------------------------------------------

    /** The person's view: {@code {mode: visual|yaml|both, yamlOpen: true|false}}. */
    public Map<String, Object> prefs(Principal p) {
        Map<String, Object> out = new LinkedHashMap<>(Map.of("mode", "both", "yamlOpen", true));
        Optional<JsonNode> got = preferences.get(p.user(), PREFS, VIEW);
        got.ifPresent(n -> {
            if (MODES.contains(n.path("mode").asText())) {
                out.put("mode", n.path("mode").asText());
            }
            if (n.has("yamlOpen")) {
                out.put("yamlOpen", n.path("yamlOpen").asBoolean(true));
            }
        });
        return out;
    }

    public Map<String, Object> setPrefs(Principal p, Map<String, Object> body) {
        Map<String, Object> now = prefs(p);
        Object mode = body.get("mode");
        if (mode != null) {
            if (!MODES.contains(String.valueOf(mode))) {
                throw new DrishtiException(ErrorCode.BI_INVALID, "mode is visual, yaml or both");
            }
            now.put("mode", String.valueOf(mode));
        }
        if (body.get("yamlOpen") instanceof Boolean b) {
            now.put("yamlOpen", b);
        }
        preferences.put(p.user(), PREFS, VIEW, JSON.valueToTree(now));
        return now;
    }

    // ---- the objects -------------------------------------------------------------------------------------------------------------

    /** Every table, Python table and model the person may open, in three groups, with where each comes from and its status. */
    public Map<String, Object> objects(Principal p) {
        requireRead(p);
        ViewerContext who = entitlements.viewer(p);
        Catalogue c = tables.catalogue();
        List<Map<String, Object>> tableList = new ArrayList<>();
        List<Map<String, Object>> pythonList = new ArrayList<>();
        List<Map<String, Object>> modelList = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (TableDef t : c.tables()) {
            String kind = TablesService.rootKind(c, t);
            if (kind != null && !who.mayOpen().test(kind)) {
                continue;
            }
            Map<String, Object> o = tableEntry(t, c);
            o.put("origin", "file");
            o.put("readOnly", true);
            o.put("status", "shipped");
            (isPython(t) ? pythonList : tableList).add(o);
            listed.add("table:" + t.name());
        }
        for (ModelDef m : c.models()) {
            Map<String, Object> o = modelEntry(m);
            o.put("origin", "file");
            o.put("readOnly", true);
            o.put("status", "shipped");
            modelList.add(o);
            listed.add("model:" + m.name());
        }
        for (BiStore.Item i : stored(p)) {
            if ("table".equals(i.kind())) {
                TableDef t = TableYaml.read(i.body()).table();
                if (t == null) {
                    continue;
                }
                Map<String, Object> o = tableEntry(t, c);
                item(o, i, p);
                (isPython(t) ? pythonList : tableList).add(o);
            } else {
                ModelDef m = ModelYaml.read(i.body()).model();
                if (m == null) {
                    continue;
                }
                Map<String, Object> o = modelEntry(m);
                item(o, i, p);
                modelList.add(o);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tableList);
        out.put("python", pythonList);
        out.put("models", modelList);
        out.put("problems", tables.problems().stream().filter(Problem::isError).count());
        out.put("canEdit", mayEdit(p));
        out.put("scanWarnRows", tables.scanWarnRows());
        return out;
    }

    private Map<String, Object> tableEntry(TableDef t, Catalogue c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("kind", "table");
        o.put("name", t.name());
        o.put("title", title(t.about(), t.name()));
        o.put("form", form(t));
        o.put("connector", connectorOf(c, t));
        o.put("columns", t.columns().size());
        return o;
    }

    private static Map<String, Object> modelEntry(ModelDef m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("kind", "model");
        o.put("name", m.name());
        o.put("title", title(m.about(), m.name()));
        o.put("form", "model");
        o.put("tables", m.tables().size());
        o.put("measures", m.measures().size());
        return o;
    }

    private void item(Map<String, Object> o, BiStore.Item i, Principal p) {
        o.put("origin", "item");
        o.put("item", i.id());
        o.put("status", i.status());
        o.put("version", i.publishedVersion());
        o.put("workspace", i.workspace());
        o.put("readOnly", !mayEdit(p));
    }

    /** The person's stored tables and models: the published ones and their own drafts, in the layer's form. */
    List<BiStore.Item> stored(Principal p) {
        Map<String, BiStore.Item> out = new LinkedHashMap<>();
        for (String kind : List.of("table", "model")) {
            for (BiStore.Item i : bi.publishedOfKind(kind)) {
                if (BiItemTableStore.layerForm(i.body())) {
                    out.put(i.id(), i);
                }
            }
            for (BiStore.Item i : bi.draftsOfKind(p, kind)) {
                if (BiItemTableStore.layerForm(i.body())) {
                    out.put(i.id(), i);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** One object: its YAML, its document, where it comes from, whether the person may change it, and its item's status. */
    public Map<String, Object> open(Principal p, String kind, String name, String itemId) {
        requireRead(p);
        String yaml = null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (itemId != null && !itemId.isBlank()) {
            Map<String, Object> it = bi.item(p, itemId, false);
            yaml = String.valueOf(it.getOrDefault("body", ""));
            out.put("origin", "item");
            out.put("item", itemId);
            out.put("status", it.get("status"));
            out.put("version", it.get("publishedVersion"));
            out.put("workspace", it.get("workspace"));
            out.put("readOnly", !mayEdit(p));
            if (!kind.equals(it.get("kind"))) {
                throw new DrishtiException(ErrorCode.BI_INVALID, itemId + " is a " + it.get("kind") + ", not a " + kind);
            }
        } else {
            yaml = fileYaml(kind, name);
            out.put("origin", "file");
            out.put("status", "shipped");
            out.put("readOnly", true);
        }
        Map<String, Object> checked = check(p, kind, yaml, null, name);
        out.putAll(checked);
        out.put("yaml", yaml);
        if ("table".equals(kind) && checked.get("doc") instanceof Map<?, ?> d) {
            out.put("python", pythonOf(p, d));
        }
        return out;
    }

    /** The YAML of a definition as its file holds it (the catalogue's source file under the tables root). */
    private String fileYaml(String kind, String name) {
        Catalogue c = tables.catalogue();
        for (Catalogue.Entry e : c.entries()) {
            if (e.kind().equals(kind) && e.name().equals(name)) {
                java.nio.file.Path f = tables.root().resolve(e.source()).normalize();
                if (!f.startsWith(tables.root())) {
                    break;
                }
                try {
                    return java.nio.file.Files.readString(f);
                } catch (java.io.IOException x) {
                    throw new DrishtiException(ErrorCode.TABLE_NOT_FOUND, e.source() + " cannot be read: " + x.getMessage());
                }
            }
        }
        throw new DrishtiException(ErrorCode.TABLE_NOT_FOUND, "no " + kind + " '" + name + "' in the catalogue");
    }

    // ---- checks -------------------------------------------------------------------------------------------------------------------

    /**
     * Checks a definition as typed. Send {@code yaml} (the text, kept as typed) or {@code doc} (a visual edit: the YAML is written from it, the
     * text's header kept). The answer: {@code ok} (it reads as a definition), the YAML, the document (the last that read, for the visual side),
     * every problem at its line, each key's line, the Text toggle's statement (a table), and whether the name is free.
     *
     * @param original the object's name before this edit (null for a new one): its own entry does not count as a clash
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> check(Principal p, String kind, String yaml, Map<String, Object> doc, String original) {
        requireRead(p);
        if (!EditorYaml.TABLE.equals(kind) && !EditorYaml.MODEL.equals(kind)) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "kind is table or model");
        }
        String text = yaml;
        if (doc != null) {
            text = EditorYaml.write(kind, doc, EditorYaml.header(yaml));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("yaml", text);
        EditorYaml.Read read = EditorYaml.read(text);
        List<Map<String, Object>> problems = new ArrayList<>();
        if (!read.ok()) {
            problems.add(problem("error", TableCodes.UNREADABLE, "", read.errorLine(), "not YAML yet: " + read.error()));
            out.put("ok", false);
            out.put("problems", problems);
            out.put("lines", Map.of());
            return out;
        }
        Map<String, Integer> lines = EditorYaml.lines(text);
        out.put("lines", lines);
        Catalogue base = overlayBase(p, kind, original);
        String name;
        List<Problem> found = new ArrayList<>();
        Catalogue over;
        if (EditorYaml.TABLE.equals(kind)) {
            TableYaml.Result r = TableYaml.read(text);
            found.addAll(r.problems());
            name = r.table() == null ? String.valueOf(read.doc().getOrDefault("table", "")) : r.table().name();
            over = r.table() == null ? null : with(base, r.table(), null);
            if (r.table() != null) {
                Map<String, Object> canonical = EditorYaml.read(TableYaml.write(r.table(), List.of())).doc();
                out.put("doc", canonical);
                try {
                    out.put("statement", TableStatement.print(canonical));
                } catch (TableStatement.Refused x) {
                    out.put("statement", null);
                    out.put("statementRefused", x.getMessage());
                }
            }
        } else {
            ModelYaml.Result r = ModelYaml.read(text);
            found.addAll(r.problems());
            name = r.model() == null ? String.valueOf(read.doc().getOrDefault("model", "")) : r.model().name();
            over = r.model() == null ? null : with(base, null, r.model());
            if (r.model() != null) {
                out.put("doc", EditorYaml.read(ModelYaml.write(r.model(), List.of())).doc());
                out.put("measures", measureFacts(r.model()));
            }
        }
        if (over != null) {
            for (Problem x : over.check()) {
                if (DRAFT.equals(x.source())) {
                    found.add(x);
                }
            }
            if (EditorYaml.TABLE.equals(kind)) {
                over.clashes(tables.storedNames()).stream().filter(x -> DRAFT.equals(x.source())).forEach(found::add);
            }
        }
        Set<String> seen = new HashSet<>();
        for (Problem x : found) {
            if (seen.add(x.level() + "|" + x.path() + "|" + x.message())) {
                problems.add(problem(x.level(), x.code(), x.path(), EditorYaml.lineOf(lines, x.path()), x.message()));
            }
        }
        boolean free = name != null && !name.isBlank() && problems.stream().noneMatch(x -> TableCodes.NAME_CLASH.equals(x.get("code"))
                || TableCodes.DUPLICATE.equals(x.get("code")));
        out.put("name", name);
        out.put("nameFree", free);
        out.put("ok", out.containsKey("doc"));
        out.put("valid", out.containsKey("doc") && problems.stream().noneMatch(x -> "error".equals(x.get("level"))));
        out.put("problems", problems);
        if (out.get("doc") == null) {
            out.put("doc", read.doc());
            out.put("ok", false);
        }
        return out;
    }

    private static Map<String, Object> problem(String level, String code, String path, int line, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("code", code);
        m.put("path", path == null ? "" : path);
        m.put("line", line);
        m.put("message", message);
        return m;
    }

    /** The Text toggle: a statement read onto the table's document, then checked as any edit. */
    public Map<String, Object> statement(Principal p, String statement, String yaml, String original) {
        requireRead(p);
        EditorYaml.Read before = EditorYaml.read(yaml);
        Map<String, Object> doc;
        try {
            doc = TableStatement.read(statement, before.ok() ? before.doc() : Map.of());
        } catch (TableStatement.Refused x) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("problems", List.of(problem("error", TableCodes.UNREADABLE, "", x.line(), x.getMessage())));
            return out;
        }
        Map<String, Object> out = check(p, EditorYaml.TABLE, yaml == null ? "" : yaml, doc, original);
        out.put("statementLines", out.get("problems"));
        return out;
    }

    static final String DRAFT = "draft";

    /** The catalogue the draft is checked and read in: the files, the person's stored tables and models, less the object being edited. */
    private Catalogue overlayBase(Principal p, String kind, String original) {
        Catalogue c = tables.catalogue();
        Map<String, TableDef> ts = new LinkedHashMap<>();
        Map<String, ModelDef> ms = new LinkedHashMap<>();
        c.tables().forEach(t -> ts.put(t.name(), t));
        c.models().forEach(m -> ms.put(m.name(), m));
        for (BiStore.Item i : stored(p)) {
            if ("table".equals(i.kind())) {
                TableDef t = TableYaml.read(i.body()).table();
                if (t != null) {
                    ts.putIfAbsent(t.name(), t);
                }
            } else {
                ModelDef m = ModelYaml.read(i.body()).model();
                if (m != null) {
                    ms.putIfAbsent(m.name(), m);
                }
            }
        }
        if (original != null) {
            (EditorYaml.TABLE.equals(kind) ? ts : ms).remove(original);
        }
        return Catalogue.of(ts.values(), ms.values(), d -> "catalogue");
    }

    /** {@code base} with the draft table or model added, labelled {@link #DRAFT} for its problems. */
    private static Catalogue with(Catalogue base, TableDef table, ModelDef model) {
        List<TableDef> ts = new ArrayList<>(base.tables());
        List<ModelDef> ms = new ArrayList<>(base.models());
        if (table != null) {
            ts.add(table);
        }
        if (model != null) {
            ms.add(model);
        }
        return Catalogue.of(ts, ms, d -> d == table || d == model ? DRAFT : "catalogue");
    }

    // ---- preview ------------------------------------------------------------------------------------------------------------------

    /**
     * A table's live preview, read on the server as the person may see its source on {@code date} (null: the latest): the first rows, the
     * totals of its number columns (the pivot engine's sums), the checks, the dates, and the cost of its look-ups across connectors.
     */
    public Map<String, Object> previewTable(Principal p, String yaml, String date, String original) {
        requireRead(p);
        TableYaml.Result r = TableYaml.read(yaml);
        if (r.table() == null) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "the table does not read yet: " + r.problems().stream().map(Problem::line).findFirst().orElse(""));
        }
        TableDef t = r.table();
        Map<String, Object> out = new LinkedHashMap<>();
        if (isPython(t)) {
            out.put("python", true);
            out.put("checks", List.of(check("info", "a Python table's rows come from its sample run on the server's compute pool: under construction")));
            out.put("columns", new ArrayList<>(t.columns().keySet()));
            out.put("rows", List.of());
            return out;
        }
        ViewerContext who = entitlements.viewer(p);
        Catalogue over = with(overlayBase(p, EditorYaml.TABLE, original), t, null);
        Counting counting = new Counting(tables.source(who));
        TableReads reads = tables.readsOver(over, counting);
        long started = System.nanoTime();
        TableReads.Read read;
        try {
            read = reads.read(t.name(), blank(date) ? null : date, null, TablesService.reader(who), null);
        } catch (ModelAnswers.Refused e) {
            throw TablesService.refused(e);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        out.put("date", String.valueOf(read.date()));
        out.put("dates", reads.dates(t.name()).stream().map(Object::toString).toList());
        out.put("columns", read.columns());
        out.put("types", read.types());
        out.put("masked", read.masked());
        out.put("count", read.rows().size());
        out.put("rows", read.rows().subList(0, Math.min(SHOWN, read.rows().size())));
        out.put("totals", totals(p, read));
        out.put("checks", tableChecks(t, read, over, reads));
        out.put("cost", cost(t, over, counting, read, ms, who));
        return out;
    }

    /** The sums of a read's number columns, by the pivot engine over every row read (never the browser's arithmetic). */
    Map<String, Object> totals(Principal p, TableReads.Read read) {
        List<com.ash.drishti.bicompute.spi.PivotProvider.Field> fields = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < read.columns().size(); i++) {
            String type = read.types().get(i);
            String c = read.columns().get(i);
            if (("integer".equals(type) || "decimal".equals(type)) && !read.masked().contains(c) && c.matches("[A-Za-z_][A-Za-z0-9_]*")
                    && !c.equals("index") && !c.equals("update_seq")) {
                fields.add(new com.ash.drishti.bicompute.spi.PivotProvider.Field(c, "number", c, ""));
                at.add(i);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (fields.isEmpty() || read.rows().isEmpty()) {
            return out;
        }
        List<Object[]> rows = new ArrayList<>(read.rows().size());
        for (List<Object> row : read.rows()) {
            Object[] r = new Object[at.size()];
            for (int i = 0; i < at.size(); i++) {
                Object v = row.get(at.get(i));
                r[i] = v instanceof Number ? v : null;
            }
            rows.add(r);
        }
        List<Object> values = new ArrayList<>();
        for (var f : fields) {
            values.add(Map.of("measure", "sum(" + f.name() + ")", "as", f.name()));
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", "inline");
        spec.put("values", values);
        com.ash.drishti.bicompute.core.PivotCore core = new com.ash.drishti.bicompute.core.PivotCore(
                List.of(new com.ash.drishti.bicompute.rows.RowsProvider("inline", "Preview", fields, rows)),
                com.ash.drishti.bicompute.cache.StateCache.none(), com.ash.drishti.bicompute.core.PivotCore.Settings.defaults());
        Map<String, Object> answer = core.query(new com.ash.drishti.bicompute.spi.Viewer(p.user(), p),
                com.ash.drishti.bicompute.core.PivotView.parse(spec));
        if (answer.get("cells") instanceof Map<?, ?> cells && cells.get("*|*") instanceof Map<?, ?> grand && grand.get("values") instanceof Map<?, ?> v) {
            v.forEach((k, x) -> out.put(String.valueOf(k), x));
        }
        return out;
    }

    private List<Map<String, Object>> tableChecks(TableDef t, TableReads.Read read, Catalogue over, TableReads reads) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(check("ok", "the name " + t.name() + " is unique across every table, virtual and stored"));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < read.columns().size(); i++) {
            String c = read.columns().get(i);
            ColumnDef def = t.columns().get(c);
            if (t.keys().contains(c) || def != null && (def.key() || "key".equals(def.role()))) {
                keys.add(c);
            }
        }
        if (!keys.isEmpty()) {
            Set<List<Object>> seen = new HashSet<>();
            int dup = 0;
            for (List<Object> row : read.rows()) {
                List<Object> k = new ArrayList<>();
                keys.forEach(c -> k.add(row.get(read.columns().indexOf(c))));
                if (!seen.add(k)) {
                    dup++;
                }
            }
            out.add(dup == 0 ? check("ok", "key " + String.join(", ", keys) + " unique on " + read.date())
                    : check("warning", dup + " rows repeat the key " + String.join(", ", keys) + " on " + read.date()));
        }
        for (int i = 0; i < read.columns().size(); i++) {
            String c = read.columns().get(i);
            if (TableDef.BUILT_INS.contains(c) || read.masked().contains(c)) {
                continue;
            }
            int nulls = 0;
            for (List<Object> row : read.rows()) {
                if (row.get(i) == null) {
                    nulls++;
                }
            }
            if (nulls > 0) {
                out.add(check("warning", nulls + " of " + read.rows().size() + " rows have no " + c));
            }
        }
        String kind = TablesService.rootKind(over, t);
        List<LocalDate> dates = reads.dates(t.name());
        out.add(check("ok", dates.isEmpty() ? "an undated source: its current rows, dated today"
                : "dates follow the source" + (kind == null ? "" : " (" + kind + ")") + ": " + dates.size() + " business date(s), latest " + dates.get(dates.size() - 1)));
        read.problems().forEach(x -> out.add(check(x.isError() ? "error" : "warning", x.message())));
        return out;
    }

    private static Map<String, Object> check(String level, String text) {
        return Map.of("level", level, "text", text);
    }

    /**
     * The cost of the table's look-ups across connectors (EDITORS.md section 2): each side's rows as read now, the memory they take, the time
     * the read took, and a warning above {@code drishti.tables.scan-warn-rows} when the table does not filter.
     */
    private Map<String, Object> cost(TableDef t, Catalogue over, Counting counting, TableReads.Read read, long ms, ViewerContext who) {
        List<Map<String, Object>> sides = new ArrayList<>();
        String own = connectorOf(over, t);
        String kind = TablesService.rootKind(over, t);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("table", t.name());
        base.put("source", kind == null ? form(t) : kind);
        base.put("connector", own);
        base.put("rows", kind == null ? read.rows().size() : counting.count(kind));
        base.put("role", "base");
        sides.add(base);
        boolean cross = false;
        Set<String> looked = new LinkedHashSet<>();
        for (ColumnDef c : t.columns().values()) {
            for (String[] rel : related(c.expr())) {
                looked.add(rel[0]);
            }
        }
        for (String other : looked) {
            TableDef o = over.table(other).orElse(null);
            if (o == null) {
                continue;
            }
            String okind = TablesService.rootKind(over, o);
            String conn = connectorOf(over, o);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("table", other);
            s.put("source", okind == null ? form(o) : okind);
            s.put("connector", conn);
            s.put("rows", okind == null ? -1 : counting.count(okind));
            s.put("role", "look-up");
            boolean differs = !Objects.equals(conn, own);
            s.put("crossConnector", differs);
            cross |= differs;
            sides.add(s);
        }
        long rows = 0;
        for (Map<String, Object> s : sides) {
            rows += Math.max(0L, ((Number) s.get("rows")).longValue());
        }
        long limit = tables.scanWarnRows();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sides", sides);
        out.put("crossConnector", cross);
        out.put("bytes", rows * Math.max(1, read.columns().size()) * 12L);
        out.put("ms", ms);
        out.put("limit", limit);
        boolean over1 = cross && t.filter() == null && sides.stream().anyMatch(s -> ((Number) s.get("rows")).longValue() > limit);
        out.put("refused", over1);
        out.put("text", !cross ? "one connector: no join across connectors"
                : over1 ? "a side holds more than " + limit + " rows and the table does not filter: readers are asked to filter first (drishti.tables.scan-warn-rows)"
                : "a look-up across connectors reads both sides into the server: " + rows + " rows, about " + human(rows * Math.max(1, read.columns().size()) * 12L)
                        + ", " + ms + " ms now; above " + limit + " rows unfiltered, readers are asked to filter first");
        return out;
    }

    private static String human(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** A model's live answer: each measure's grand total, read as the person, with the engine's "adds up?", and the join cost warnings. */
    public Map<String, Object> previewModel(Principal p, String yaml, String date, String original) {
        requireRead(p);
        ModelYaml.Result r = ModelYaml.read(yaml);
        if (r.model() == null) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "the model does not read yet: " + r.problems().stream().map(Problem::line).findFirst().orElse(""));
        }
        ModelDef m = r.model();
        ViewerContext who = entitlements.viewer(p);
        Catalogue over = with(overlayBase(p, EditorYaml.MODEL, original), null, m);
        Counting counting = new Counting(tables.source(who));
        TableReads reads = tables.readsOver(over, counting);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> facts = measureFacts(m);
        LocalDate day = null;
        try {
            String fact = m.tables().values().stream().filter(u -> "fact".equals(u.role())).map(u -> u.table().name()).findFirst()
                    .orElse(m.tables().values().stream().map(u -> u.table().name()).findFirst().orElse(null));
            day = fact == null ? null : reads.day(fact, blank(date) ? null : date);
        } catch (ModelAnswers.Refused e) {
            out.put("error", e.getMessage());
        }
        long started = System.nanoTime();
        if (!m.measures().isEmpty() && !out.containsKey("error")) {
            try {
                ModelAnswers answers = new ModelAnswers(over, reads, null);
                ModelAnswers.Answer a = answers.ask(m, new ModelAnswers.Question(List.of(), new ArrayList<>(m.measures().keySet()), List.of(), day),
                        TablesService.reader(who));
                for (int i = 0; i < a.columns().size() && i < a.grand().size(); i++) {
                    String name = a.columns().get(i);
                    for (Map<String, Object> f : facts) {
                        if (f.get("name").equals(name)) {
                            f.put("value", a.grand().get(i));
                        }
                    }
                }
                out.put("masked", a.masked());
                out.put("noAccess", a.noAccess());
            } catch (ModelAnswers.Refused e) {
                out.put("error", e.getMessage());
            } catch (RuntimeException e) {
                out.put("error", "the model cannot be answered yet: " + e.getMessage());
            }
        }
        out.put("ms", (System.nanoTime() - started) / 1_000_000);
        out.put("date", day == null ? null : day.toString());
        out.put("measures", facts);
        List<Map<String, Object>> sides = new ArrayList<>();
        ScanEstimate.sides(m, over, counting, day).values().forEach(s -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("alias", s.alias());
            x.put("table", s.table());
            x.put("connector", s.connector());
            x.put("rows", s.rows());
            x.put("filtered", s.filtered());
            sides.add(x);
        });
        out.put("sides", sides);
        out.put("warnings", ScanEstimate.warnings(m, over, counting, day, tables.scanWarnRows()).stream().map(Problem::message).toList());
        out.put("limit", m.scanWarnRows() != null ? m.scanWarnRows() : tables.scanWarnRows());
        return out;
    }

    /** Each measure's card: its formula, the formula in words, the engine's "adds up?", and the measures that use it. */
    List<Map<String, Object>> measureFacts(ModelDef m) {
        Map<String, String> exprs = new LinkedHashMap<>();
        m.measures().forEach((n, x) -> exprs.put(n, x.expr() == null ? "" : x.expr()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : exprs.entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", e.getKey());
            f.put("expr", e.getValue());
            if (e.getValue().isEmpty()) {
                f.put("explain", "a Python measure: computed by its function on the server");
                f.put("addsUp", false);
                f.put("word", "no");
                f.put("why", "computed per total by its Python function");
            } else {
                f.put("explain", MeasureFacts.explain(e.getValue()));
                f.putAll(MeasureFacts.addsUp(e.getValue(), exprs).toMap());
            }
            List<String> users = new ArrayList<>();
            exprs.forEach((n, x) -> {
                if (!n.equals(e.getKey()) && (x.contains("measure('" + e.getKey() + "')") || x.contains("measure(\"" + e.getKey() + "\")"))) {
                    users.add("measure " + n);
                }
            });
            f.put("usedBy", users);
            out.add(f);
        }
        return out;
    }

    // ---- access, impact, history --------------------------------------------------------------------------------------------------

    /** The Access matrix of a model: every role against the rows it sees, the columns masked for it, and whether it may edit. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> access(Principal p, String yaml, String original) {
        requireRead(p);
        ModelYaml.Result r = ModelYaml.read(yaml);
        if (r.model() == null) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "the model does not read yet");
        }
        ModelDef m = r.model();
        Catalogue over = with(overlayBase(p, EditorYaml.MODEL, original), null, m);
        List<String> masked = new ArrayList<>(m.security().masked());
        for (ModelDef.Use u : m.tables().values()) {
            over.effective(u.table()).ifPresent(t -> com.ash.drishti.tables.check.TableChecks.masked(t).forEach(c -> masked.add(u.alias() + "." + c)));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (RoleDefinition role : roles.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", role.name());
            List<String> rows = new ArrayList<>();
            for (ModelDef.Use u : m.tables().values()) {
                TableDef t = over.effective(u.table()).orElse(null);
                List<TableDef.Rule> rules = new ArrayList<>(t == null ? List.of() : t.rules());
                rules.addAll(m.security().rows().getOrDefault(u.alias(), List.of()));
                List<String> may = m.security().maySeeAll().getOrDefault(u.alias(), List.of());
                if (rules.isEmpty() || may.contains(role.name())) {
                    continue;
                }
                List<String> mine = rules.stream().filter(x -> x.roles().contains(role.name()) || x.roles().contains("*")).map(TableDef.Rule::where).toList();
                rows.add(u.alias() + ": " + (mine.isEmpty() ? "none" : String.join(" or ", mine)));
            }
            row.put("rows", rows.isEmpty() ? "all" : String.join("; ", rows));
            row.put("columns", role.raw() || masked.isEmpty() ? "all" : masked.size() + " masked (" + String.join(", ", masked) + ")");
            row.put("edit", role.admin() || role.bi().contains(BiAccess.AUTHOR));
            out.add(row);
        }
        return Map.of("roles", out, "masked", masked);
    }

    /** What uses the object (before it is changed): the layer's lineage, and the BI reports and boards that name it. */
    public Map<String, Object> impact(Principal p, String kind, String name) {
        requireRead(p);
        Catalogue c = overlayBase(p, kind, null);
        List<Map<String, Object>> out = new ArrayList<>();
        if (EditorYaml.TABLE.equals(kind)) {
            for (Lineage.Use u : c.usedBy(name)) {
                out.add(Map.of("kind", u.kind(), "name", u.name(), "how", u.how()));
            }
        } else {
            for (TableDef t : c.tables()) {
                if (t.from() != null && t.from().form() == TableDef.Source.Form.MODEL && t.from().model() != null
                        && t.from().model().replaceAll("@.*$", "").equals(name)) {
                    out.add(Map.of("kind", "table", "name", t.name(), "how", "a summary table of its answer"));
                }
            }
        }
        for (String k : List.of("report", "board", "notebook")) {
            for (BiStore.Item i : bi.publishedOfKind(k)) {
                String b = i.body() == null ? "" : i.body();
                if (b.contains("table:" + name) || b.matches("(?s).*\\b(model|table|source):\\s*['\"]?" + java.util.regex.Pattern.quote(name) + "\\b.*")) {
                    out.add(Map.of("kind", i.kind(), "name", i.title(), "how", "binds it (" + i.id() + ")"));
                }
            }
        }
        return Map.of("name", name, "kind", kind, "usedBy", out);
    }

    /** An item's versions and governance (BI's own history of the item). */
    public Map<String, Object> history(Principal p, String itemId) {
        requireRead(p);
        return Map.of("item", itemId, "versions", bi.versions(p, itemId));
    }

    // ---- saving and review --------------------------------------------------------------------------------------------------------

    /**
     * Saves a draft: a new item in {@code workspace} ("me" for the person's own) or a new saved state of {@code item}. A Python-driven table
     * saves its build function as the Python item the table names. Saving never makes anything live: proposing does, once approved.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> save(Principal p, Map<String, Object> body) {
        requireEdit(p);
        String kind = String.valueOf(body.getOrDefault("kind", ""));
        String yaml = String.valueOf(body.getOrDefault("yaml", ""));
        String itemId = body.get("item") == null ? "" : String.valueOf(body.get("item"));
        String original = body.get("original") == null ? null : String.valueOf(body.get("original"));
        Map<String, Object> checked = check(p, kind, yaml, null, original);
        if (!Boolean.TRUE.equals(checked.get("ok"))) {
            throw new DrishtiException(ErrorCode.BI_INVALID, "the definition does not read yet: fix the YAML at the marked line, then save");
        }
        if (!Boolean.TRUE.equals(checked.get("nameFree"))) {
            throw new DrishtiException(ErrorCode.TABLE_NAME_CLASH, "'" + checked.get("name") + "' is taken: names are unique across every table and model");
        }
        String title = title(((Map<String, Object>) checked.get("doc")), String.valueOf(checked.get("name")));
        String message = body.get("message") == null ? "" : String.valueOf(body.get("message"));
        Map<String, Object> python = body.get("python") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : null;
        Map<String, Object> pythonSaved = null;
        if (python != null && EditorYaml.TABLE.equals(kind)) {
            pythonSaved = savePython(p, python, String.valueOf(body.getOrDefault("workspace", "me")), title, message);
        }
        Map<String, Object> saved;
        if (itemId.isBlank()) {
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("kind", kind);
            create.put("title", title);
            create.put("body", yaml);
            create.put("message", message.isEmpty() ? "created in the " + kind + " editor" : message);
            saved = bi.createItem(p, String.valueOf(body.getOrDefault("workspace", "me")), create);
        } else {
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("body", yaml);
            update.put("message", message.isEmpty() ? "saved in the " + kind + " editor" : message);
            if (body.get("baseEtag") != null) {
                update.put("baseEtag", body.get("baseEtag"));
            }
            saved = bi.updateItem(p, itemId, update);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", saved.get("id"));
        out.put("status", saved.get("status"));
        out.put("version", saved.get("publishedVersion"));
        out.put("workspace", saved.get("workspace"));
        if (pythonSaved != null) {
            out.put("pythonItem", pythonSaved.get("id"));
        }
        return out;
    }

    /** The build function of a Python-driven table, as the BI Python item (interface transform, on the server) its table names. */
    private Map<String, Object> savePython(Principal p, Map<String, Object> python, String workspace, String title, String message) {
        String manifest = PythonDrafts.manifest(python);
        String id = python.get("item") == null ? "" : String.valueOf(python.get("item"));
        if (id.isBlank()) {
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("kind", "python");
            create.put("title", title + " (build)");
            create.put("body", manifest);
            create.put("message", message.isEmpty() ? "the build function of a Python-driven table" : message);
            return bi.createItem(p, workspace, create);
        }
        return bi.updateItem(p, id, Map.of("body", manifest, "message", message.isEmpty() ? "saved in the Python table editor" : message));
    }

    /** The Python item a Python-driven table names, when the person may read it: its source, inputs and limits for the editor. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pythonOf(Principal p, Map<?, ?> doc) {
        if (!(doc.get("from") instanceof Map<?, ?> from) || from.get("python") == null) {
            return null;
        }
        String ref = String.valueOf(from.get("python"));
        for (BiStore.Item i : bi.draftsOfKind(p, "python")) {
            if (PythonDrafts.named(i.body(), ref)) {
                return PythonDrafts.read(i.id(), i.body());
            }
        }
        for (BiStore.Item i : bi.publishedOfKind("python")) {
            if (PythonDrafts.named(i.body(), ref)) {
                return PythonDrafts.read(i.id(), i.body());
            }
        }
        return Map.of("name", ref, "missing", true);
    }

    /** Proposes the saved item for review: another person approves it (four eyes) before it goes live. */
    public Map<String, Object> propose(Principal p, String itemId, String note) {
        requireEdit(p);
        Map<String, Object> got = bi.propose(p, itemId, note == null ? "" : note);
        return Map.of("item", itemId, "status", String.valueOf(got.get("status")));
    }

    // ---- sources and suggestions --------------------------------------------------------------------------------------------------

    /** What a table may read: the kinds the person may open (with their columns) and the catalogue's tables (with theirs). */
    public Map<String, Object> sources(Principal p) {
        requireRead(p);
        ViewerContext who = entitlements.viewer(p);
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (DataViewService.Dataset d : data.datasets()) {
            if (!who.mayOpen().test(d.kind())) {
                continue;
            }
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kind", d.kind());
            k.put("pack", d.pack());
            k.put("title", d.title());
            k.put("connector", d.connector());
            k.put("dated", d.dated());
            List<Map<String, Object>> cols = new ArrayList<>();
            for (DataViewService.Column c : d.columns() == null ? List.<DataViewService.Column>of() : d.columns()) {
                cols.add(Map.of("name", c.name(), "type", c.type() == null ? "" : c.type(), "description", c.description() == null ? "" : c.description()));
            }
            k.put("columns", cols);
            kinds.add(k);
        }
        List<Map<String, Object>> ts = new ArrayList<>();
        Catalogue c = overlayBase(p, EditorYaml.TABLE, null);
        for (TableDef t : c.tables()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t.name());
            m.put("title", title(t.about(), t.name()));
            m.put("connector", connectorOf(c, t));
            List<Map<String, Object>> cols = new ArrayList<>();
            c.effective(new com.ash.drishti.tables.def.TableName.Ref(t.name(), null)).orElse(t).columns().values()
                    .forEach(x -> cols.add(Map.of("name", x.name(), "type", x.type() == null ? "" : x.type(), "key", x.key())));
            m.put("columns", cols);
            m.put("keys", t.keys());
            ts.add(m);
        }
        List<String> roleNames = roles.all().stream().map(RoleDefinition::name).toList();
        return Map.of("kinds", kinds, "tables", ts, "roles", roleNames, "functions", functions());
    }

    /** Rachana's function catalogue, for type-ahead: name, signature, family, scopes, one line and one example. */
    static List<Map<String, Object>> functions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var s : com.ash.drishti.rachana.el.check.Signatures.get().all()) {
            if (!s.built()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("signature", s.signature());
            m.put("family", s.family());
            m.put("scopes", s.scopes());
            m.put("description", s.description());
            m.put("example", s.examples().isEmpty() ? "" : s.examples().get(0).expr());
            out.add(m);
        }
        return out;
    }

    /**
     * The + New steps' suggestion: a table over {@code kind} with one column per scalar field of a sample of its documents (the layer's
     * {@link TableOffer}, as {@code tables offer} does), and the lists that could be its grain.
     */
    public Map<String, Object> suggest(Principal p, String name, String kind, String grain) {
        requireRead(p);
        ViewerContext who = entitlements.viewer(p);
        if (!who.mayOpen().test(kind)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "no access to " + kind);
        }
        SourceRows source = tables.source(who);
        TableDef.Source from = TableDef.Source.ofKind(kind);
        List<LocalDate> dates = source.dates(from);
        LocalDate day = dates.isEmpty() ? null : dates.get(dates.size() - 1);
        List<Preview.Doc> sample = new ArrayList<>();
        try {
            source.read(from, day, null, d -> {
                sample.add(d);
                return sample.size() < SAMPLE;
            });
        } catch (ModelAnswers.Refused e) {
            throw TablesService.refused(e);
        }
        String tableName = blank(name) ? suggestedName(kind) : name;
        TableOffer.Offer offer = TableOffer.suggest(tableName, from, sample);
        if (!blank(grain)) {
            offer = TableOffer.atGrain(offer, grain, sample);
        }
        String yaml = TableYaml.write(offer.table(), List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("yaml", yaml);
        out.put("doc", EditorYaml.read(yaml).doc());
        out.put("grains", offer.grains());
        out.put("documents", offer.documents());
        out.put("date", day == null ? null : day.toString());
        return out;
    }

    static String suggestedName(String kind) {
        String k = kind.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return "mine." + (k.isEmpty() ? "table" : k.matches("^[a-z].*") ? k : "t_" + k);
    }

    // ---- small helpers -----------------------------------------------------------------------------------------------------------

    static boolean isPython(TableDef t) {
        return t.from() != null && t.from().form() == TableDef.Source.Form.PYTHON;
    }

    static String form(TableDef t) {
        TableDef.Source.Form f = t.from() == null ? TableDef.Source.Form.NONE : t.from().form();
        return switch (f) {
            case KIND, LAKE -> "virtual";
            case TABLE -> "over a table";
            case SQL -> "sql";
            case PYTHON -> "python";
            case MODEL -> "summary";
            case NONE -> "unsourced";
        };
    }

    private String connectorOf(Catalogue c, TableDef t) {
        String kind = TablesService.rootKind(c, t);
        if (t.from() != null && t.from().connector() != null) {
            return t.from().connector();
        }
        if (kind == null) {
            return t.from() == null ? null : t.from().form().name().toLowerCase(Locale.ROOT);
        }
        try {
            return data.dataset(kind).connector();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String title(Map<String, Object> about, String name) {
        Object t = about == null ? null : about.get("title");
        return t == null || String.valueOf(t).isBlank() ? name : String.valueOf(t);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** The {@code [table, column]} pairs a column's expression looks up with {@code related(...)}. */
    static List<String[]> related(String expr) {
        List<String[]> out = new ArrayList<>();
        if (expr == null || !expr.contains("related(")) {
            return out;
        }
        try {
            com.ash.drishti.tables.lang.Trees.walk(com.ash.drishti.tables.lang.Trees.parse(expr), n -> {
                if (n instanceof com.ash.drishti.rachana.el.Expr.Call c && c.name().equals("related") && !c.args().isEmpty()) {
                    String x = com.ash.drishti.tables.lang.Trees.name(c.args().get(0));
                    int last = x == null ? -1 : x.lastIndexOf('.');
                    if (last > 0) {
                        out.add(new String[] {x.substring(0, last), x.substring(last + 1)});
                    }
                }
            });
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    /** The source as the person may see it, counting the documents read of each kind (the preview's rows per side of a join). */
    static final class Counting implements SourceRows {
        private final SourceRows inner;
        private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

        Counting(SourceRows inner) {
            this.inner = inner;
        }

        long count(String kind) {
            AtomicLong n = counts.get(kind);
            return n == null ? -1 : n.get();
        }

        @Override
        public List<LocalDate> dates(TableDef.Source source) {
            return inner.dates(source);
        }

        @Override
        public boolean travels(TableDef.Source source) {
            return inner.travels(source);
        }

        @Override
        public void read(TableDef.Source source, LocalDate date, Instant knownAt, Predicate<Preview.Doc> sink) {
            AtomicLong n = counts.computeIfAbsent(source.kind() == null ? String.valueOf(source.table()) : source.kind(), k -> new AtomicLong());
            n.set(0);
            inner.read(source, date, knownAt, d -> {
                n.incrementAndGet();
                return sink.test(d);
            });
        }

        @Override
        public long estimate(TableDef.Source source, LocalDate date) {
            long n = count(source.kind() == null ? String.valueOf(source.table()) : source.kind());
            return n >= 0 ? n : inner.estimate(source, date);
        }

        @Override
        public String connector(TableDef.Source source) {
            return inner.connector(source);
        }
    }
}
