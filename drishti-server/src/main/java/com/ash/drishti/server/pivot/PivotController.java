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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.export.PivotExport;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.edition.EditionCommands;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pivot engine's API, {@code /api/v1/pivot} (PIVOT_GUIDE.md, API_GUIDE.md "Pivot"): sources and their catalogues, a view's answer, the
 * scan estimate, a node opened lazily, two dates compared, a cell by reference, a cell's member rows, provenance, saved and shared views,
 * and exports (xlsx with outline and formulas only where a measure adds up, CSV, Arrow, the definition as YAML). Every figure comes from the
 * {@code drishti-bicompute} engine; entitlements and masks apply before aggregation (the providers read only what the person may see). Every
 * view run and every export is audited, never with its values.
 */
@RestController
@RequestMapping("/api/v1/pivot")
public class PivotController {

    /** The command word. */
    public static final String WORD = "PIVOT";
    private static final Pattern REF = Pattern.compile("pivot://([^/]+)/([^@/]+)(?:@(\\d+))?/([^\\[]+)\\[(.*)]");

    private final PivotCore core;
    private final PivotViews views;
    private final PivotProperties props;
    private final AuditLog audit;
    private final EditionCommands edition;
    private final boolean enabled;
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines;
    private final KindSources kinds;
    private final com.ash.drishti.server.security.Entitlements entitlements;

    public PivotController(PivotCore core, PivotViews views, PivotProperties props, AuditLog audit, EditionCommands edition,
            @Value("${drishti.engines.pivot.enabled:${drishti.pivot.enabled:true}}") boolean enabled,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines, KindSources kinds,
            com.ash.drishti.server.security.Entitlements entitlements) {
        this.kinds = kinds;
        this.entitlements = entitlements;
        this.core = core;
        this.views = views;
        this.props = props;
        this.audit = audit;
        this.edition = edition;
        this.enabled = enabled;
        this.engines = engines;
    }

    private Viewer on(Principal p) {
        com.ash.drishti.server.engines.EngineRegistry reg = engines.getIfAvailable();
        if (reg != null ? reg.known("pivot") && !reg.running("pivot") : !enabled) {
            throw new DrishtiException(ErrorCode.PIVOT_OFF, "the pivot engine is switched off on this server (config/engines/pivot.yaml, drishti.engines.pivot.enabled)");
        }
        if (edition != null && !edition.edition().wordOn(WORD)) {
            throw new DrishtiException(ErrorCode.PIVOT_OFF, "PIVOT is not part of the edition '" + edition.edition().name() + "'");
        }
        return new Viewer(p.user(), p);
    }

    /** The view of a body: {@code {"view": {...}}}, {@code {"view": "owner/name"}} (a saved view), or the view itself. */
    @SuppressWarnings("unchecked")
    private PivotView view(Principal p, Map<String, Object> body) {
        Object v = body == null ? null : body.get("view");
        if (v instanceof String id) {
            return views.open(p, id);
        }
        if (v instanceof Map<?, ?> m) {
            return PivotView.parse((Map<String, Object>) m);
        }
        return PivotView.parse(body);
    }

    private void audited(Principal p, String action, PivotView v, String extra) {
        audit.record(p.user(), action, v.source(), "rows=" + v.rows() + "; columns=" + v.columns() + "; values="
                + v.values().stream().map(PivotView.Value::measure).toList() + "; date=" + (v.date() == null ? "latest" : v.date()) + extra);
    }

    @GetMapping("/sources")
    public Map<String, Object> sources(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Viewer v = on(p);
        return Map.of("sources", core.sources(v));
    }

    @GetMapping("/catalogue")
    public Map<String, Object> catalogue(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam String source,
            @RequestParam(required = false) String date) {
        return core.catalogue(on(p), source, date);
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = on(p);
        PivotView view = view(p, body);
        Map<String, Object> a = core.query(v, view);
        if (body.get("view") instanceof String id) {
            a.put("savedView", id);
        }
        audited(p, "pivot.query", view, "; cells=" + a.get("cellCount"));
        return a;
    }

    @PostMapping("/estimate")
    public Map<String, Object> estimate(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = on(p);
        return core.estimate(v, view(p, body));
    }

    /** A node opened: {@code {view, axis, path: [[dim, value], ...], by}}. */
    @PostMapping("/expand")
    @SuppressWarnings("unchecked")
    public Map<String, Object> expand(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = on(p);
        PivotView view = view(p, body);
        List<List<String>> path = new ArrayList<>();
        if (body.get("path") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof List<?> pair && pair.size() == 2) {
                    path.add(List.of(String.valueOf(pair.get(0)), String.valueOf(pair.get(1))));
                }
            }
        }
        Object by = body.get("by");
        return core.expand(v, view, String.valueOf(body.getOrDefault("axis", "rows")), path, by == null || String.valueOf(by).isBlank() ? null
                : String.valueOf(by));
    }

    /** The view on its date and on {@code otherDate}, cell by cell. */
    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = on(p);
        Object other = body.get("otherDate");
        if (other == null || String.valueOf(other).isBlank()) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "compare needs otherDate: the date to compare the view's date with");
        }
        PivotView view = view(p, body);
        audited(p, "pivot.compare", view, "; otherDate=" + other);
        return core.compare(v, view, String.valueOf(other));
    }

    /** A cell by reference ({@code pivot://owner/name@version/Measure[DIM=V, DIM2=V2]}): its figure, from its own members. */
    @GetMapping("/cells")
    public Map<String, Object> cell(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam String ref) {
        Viewer v = on(p);
        Matcher m = REF.matcher(ref.strip());
        if (!m.matches()) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "not a cell reference: " + ref + " (pivot://owner/name@version/Measure[DIM=V, ...])");
        }
        String id = m.group(1) + "/" + m.group(2);
        Map<String, Object> saved = views.get(p, id).orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no saved pivot view "
                + id + " that you may open"));
        PivotView view = views.open(p, id);
        Map<String, String> coords = new LinkedHashMap<>();
        String inner = m.group(5).strip();
        if (!inner.isEmpty()) {
            for (String part : inner.split(",\\s*")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "a coordinate is DIM=value, not " + part);
                }
                coords.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
            }
        }
        String measure = m.group(4).strip();
        Map<String, Object> out = new LinkedHashMap<>(core.cell(v, view, coords));
        out.put("ref", ref);
        out.put("measure", measure);
        Object vals = out.get("values");
        if (vals instanceof Map<?, ?> vm) {
            if (!vm.containsKey(measure)) {
                throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, measure + " is not a value of " + id + "; its values: " + vm.keySet());
            }
            out.put("value", vm.get(measure));
        } else {
            out.put("value", null);
        }
        int version = ((Number) saved.getOrDefault("version", 1)).intValue();
        if (m.group(3) != null && Integer.parseInt(m.group(3)) < version) {
            out.put("newerVersion", version);
        }
        return out;
    }

    @GetMapping("/cells/{owner}/{name}/{*rest}")
    public Map<String, Object> cellPath(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name,
            @PathVariable String rest) {
        return cell(p, "pivot://" + owner + "/" + name + URLDecoder.decode(rest, StandardCharsets.UTF_8));
    }

    /**
     * The document paths people typed on a source, most used first, with how each becomes a column read (PIVOT_ENGINE.md section 6.7):
     * an administrator's suggestions to promote a path in the connector's layout or to add it to a BI dataset.
     */
    @GetMapping("/paths")
    public Map<String, Object> paths(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam String source) {
        on(p);
        if (kinds == null || !entitlements.isAdmin(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the most used document paths are an administrator's suggestions");
        }
        return Map.of("source", source, "paths", kinds.typedPaths(source));
    }

    /** The most rows an inline pivot takes (a chart of a table's shown rows is a page, never a lake). */
    static final int INLINE_MAX_ROWS = 100_000;
    private static final Pattern INLINE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /**
     * A pivot of rows the caller already holds and may see (a chart of a table's shown rows: PIVOT_GUIDE.md, Rows held in memory):
     * {@code {fields: [{name, type}], rows: [[...]], view}}, aggregated here by the engine's own functions so the browser never adds up a
     * figure. The rows come from the caller, so nothing is read and no mask is needed beyond the ones they already carry.
     */
    @PostMapping("/inline")
    @SuppressWarnings("unchecked")
    public Map<String, Object> inline(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = on(p);
        List<PivotProvider.Field> fields = new ArrayList<>();
        for (Object o : body.get("fields") instanceof List<?> l ? l : List.of()) {
            Map<String, Object> f = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String name = String.valueOf(f.get("name"));
            String type = String.valueOf(f.getOrDefault("type", "string"));
            if (!INLINE_FIELD.matcher(name).matches() || !List.of("string", "number", "date").contains(type)) {
                throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "an inline field is {name: letters, digits and _, type: string, number or date}: " + f);
            }
            fields.add(new PivotProvider.Field(name, type, String.valueOf(f.getOrDefault("label", name)), ""));
        }
        List<?> raw = body.get("rows") instanceof List<?> l ? l : List.of();
        if (fields.isEmpty() || raw.size() > INLINE_MAX_ROWS) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "an inline pivot needs its fields and at most " + INLINE_MAX_ROWS + " rows (it has "
                    + raw.size() + "): chart every row with the source's own pivot instead");
        }
        List<Object[]> rows = new ArrayList<>(raw.size());
        for (Object r : raw) {
            List<?> cells = r instanceof List<?> l ? l : List.of();
            Object[] row = new Object[fields.size()];
            for (int i = 0; i < row.length && i < cells.size(); i++) {
                Object x = cells.get(i);
                row[i] = x instanceof Number || x == null ? x : String.valueOf(x);
            }
            rows.add(row);
        }
        Map<String, Object> spec = new LinkedHashMap<>(body.get("view") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
        spec.put("source", "inline");
        PivotView view = PivotView.parse(spec);
        PivotCore inline = new PivotCore(List.of(new com.ash.drishti.bicompute.rows.RowsProvider("inline", "Rows", fields, rows)),
                com.ash.drishti.bicompute.cache.StateCache.none(), PivotCore.Settings.defaults());
        Map<String, Object> a = inline.query(v, view);
        audited(p, "pivot.inline", view, "; rows=" + rows.size() + "; cells=" + a.get("cellCount"));
        return a;
    }

    /** A cell's member rows (drill-through), paged: {@code {view, coords: {DIM: value}, offset, limit}}. */
    @PostMapping("/rows")
    @SuppressWarnings("unchecked")
    public Map<String, Object> rows(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = on(p);
        PivotView view = view(p, body);
        Map<String, String> coords = new LinkedHashMap<>();
        if (body.get("coords") instanceof Map<?, ?> c) {
            c.forEach((k, x) -> coords.put(String.valueOf(k), String.valueOf(x)));
        }
        int offset = body.get("offset") instanceof Number n ? n.intValue() : 0;
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 200;
        PivotProvider.Rows r = core.drill(v, view, coords, offset, limit);
        audited(p, "pivot.rows", view, "; cell=" + CellKey.of(coords).canonical() + "; rows=" + r.rows().size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", r.columns());
        out.put("rows", r.rows());
        out.put("total", r.total());
        out.put("offset", offset);
        out.put("note", r.note());
        return out;
    }

    @GetMapping("/provenance/{id}")
    public Map<String, Object> provenance(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String id,
            @RequestParam(required = false) String cell) {
        on(p);
        return core.provenance(id, cell);
    }

    @GetMapping("/views")
    public Map<String, Object> listViews(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        on(p);
        return Map.of("views", views.list(p));
    }

    @PostMapping("/views")
    public Map<String, Object> saveView(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        on(p);
        Map<String, Object> saved = views.save(p, body);
        audit.record(p.user(), "pivot.view.save", String.valueOf(saved.get("id")), "version=" + saved.get("version") + "; sharedWith="
                + saved.get("sharedWith"));
        return saved;
    }

    @GetMapping("/views/{owner}/{name}")
    public Map<String, Object> getView(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String owner, @PathVariable String name) {
        on(p);
        return views.get(p, owner + "/" + name).orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no saved pivot view " + owner
                + "/" + name + " that you may open"));
    }

    @DeleteMapping("/views/{name}")
    public ResponseEntity<Void> deleteView(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String name) {
        on(p);
        if (!views.delete(p, name)) {
            throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "you have no saved pivot view " + name);
        }
        audit.record(p.user(), "pivot.view.delete", p.user() + "/" + name, "");
        return ResponseEntity.noContent().build();
    }

    /**
     * An export, streamed: {@code {view, what: pivot|rows|definition, format: xlsx|csv|jsonl|arrow|yaml, shape: cells|grid, delimiter, bom, coords}}.
     * Audited with what, the format and the size, never the values.
     */
    @PostMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body, HttpServletResponse res)
            throws IOException {
        Viewer v = on(p);
        PivotView view = view(p, body);
        String what = String.valueOf(body.getOrDefault("what", "pivot"));
        String format = String.valueOf(body.getOrDefault("format", "xlsx"));
        String delim = String.valueOf(body.getOrDefault("delimiter", ","));
        char d = "tab".equals(delim) || "\\t".equals(delim) ? '\t' : delim.isEmpty() ? ',' : delim.charAt(0);
        boolean bom = Boolean.TRUE.equals(body.get("bom"));
        String base = "pivot-" + view.source() + (view.date() == null ? "" : "-" + view.date());
        long[] size = {0};
        String detail;
        switch (what) {
            case "definition" -> {
                byte[] y = PivotExport.definition(view.toMap()).getBytes(StandardCharsets.UTF_8);
                start(res, "application/yaml", base + ".yaml");
                res.getOutputStream().write(y);
                size[0] = y.length;
                detail = "definition";
            }
            case "rows" -> {
                Map<String, String> coords = new LinkedHashMap<>();
                if (body.get("coords") instanceof Map<?, ?> c) {
                    c.forEach((k, x) -> coords.put(String.valueOf(k), String.valueOf(x)));
                }
                PivotProvider.Rows r = core.drill(v, view, coords, 0, props.rowsMax());
                if ("jsonl".equals(format)) {
                    start(res, "application/x-ndjson; charset=utf-8", base + "-rows.jsonl");
                    PivotExport.rowsJsonl(r, counting(res.getOutputStream(), size));
                } else {
                    start(res, "text/csv; charset=utf-8", base + "-rows.csv");
                    PivotExport.rowsCsv(r, counting(res.getOutputStream(), size), d, bom);
                }
                detail = "rows " + r.rows().size() + " of " + r.total() + "; cell=" + CellKey.of(coords).canonical();
            }
            default -> {
                Map<String, Object> a = core.query(v, view);
                List<Map<String, Object>> rows = (List<Map<String, Object>>) a.get("rows");
                if ("xlsx".equals(format)) {
                    if (rows.size() > props.xlsxMaxRows()) {
                        throw new DrishtiException(ErrorCode.PIVOT_TOO_MANY_CELLS, "the pivot has " + rows.size() + " rows, more than an xlsx "
                                + "export writes (drishti.pivot.export.xlsx-max-rows " + props.xlsxMaxRows() + "): export it as CSV or Arrow");
                    }
                    start(res, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
                    PivotExport.xlsx(a, counting(res.getOutputStream(), size), p.user(), Instant.now().toString());
                } else if ("jsonl".equals(format)) {
                    start(res, "application/x-ndjson; charset=utf-8", base + ".jsonl");
                    PivotExport.jsonl(a, counting(res.getOutputStream(), size));
                } else if ("arrow".equals(format)) {
                    byte[] arrow = arrow(a);
                    start(res, "application/vnd.apache.arrow.stream", base + ".arrow");
                    res.getOutputStream().write(arrow);
                    size[0] = arrow.length;
                } else {
                    start(res, "text/csv; charset=utf-8", base + ".csv");
                    PivotExport.csv(a, counting(res.getOutputStream(), size), String.valueOf(body.getOrDefault("shape", "cells")), d, bom);
                }
                detail = "pivot " + rows.size() + " rows x " + ((List<?>) a.get("columns")).size() + " columns";
            }
        }
        res.flushBuffer();
        audited(p, "pivot.export", view, "; what=" + what + "; format=" + format + "; " + detail + "; bytes=" + size[0]);
    }

    private static void start(HttpServletResponse res, String type, String file) {
        res.setStatus(200);
        res.setContentType(type);
        res.setHeader("Content-Disposition", "attachment; filename=\"" + file.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        res.setHeader("Cache-Control", "no-store");
    }

    private static OutputStream counting(OutputStream out, long[] size) {
        return new java.io.FilterOutputStream(out) {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                size[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
                size[0] += len;
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }

    /** The answer's cells as an Arrow stream: one record per cell and measure (row, column, coordinates, measure, value, members). */
    @SuppressWarnings("unchecked")
    static byte[] arrow(Map<String, Object> a) {
        List<String> dims = new ArrayList<>((List<String>) a.get("rowDimensions"));
        dims.addAll((List<String>) a.get("columnDimensions"));
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
        List<Map<String, Object>> measures = (List<Map<String, Object>>) a.get("measures");
        int n = cells.size() * measures.size();
        Object[] row = new Object[n];
        Object[] col = new Object[n];
        Object[][] dv = new Object[dims.size()][n];
        Object[] meas = new Object[n];
        Object[] val = new Object[n];
        Object[] mem = new Object[n];
        int i = 0;
        for (Map.Entry<String, Map<String, Object>> e : cells.entrySet()) {
            String[] rc = e.getKey().split("\\|", 2);
            Map<String, String> coords = (Map<String, String>) e.getValue().get("coords");
            Map<String, Object> values = (Map<String, Object>) e.getValue().get("values");
            for (Map<String, Object> m : measures) {
                row[i] = rc[0];
                col[i] = rc.length > 1 ? rc[1] : "*";
                for (int k = 0; k < dims.size(); k++) {
                    String x = coords.get(dims.get(k));
                    dv[k][i] = x == null ? null : CellKey.OTHERS.equals(x) ? "Others" : x;
                }
                meas[i] = String.valueOf(m.get("label"));
                Object x = values.get(String.valueOf(m.get("label")));
                val[i] = x instanceof Number num ? num.doubleValue() : null;
                mem[i] = ((Number) e.getValue().getOrDefault("members", 0)).longValue();
                i++;
            }
        }
        List<ArrowIpcWriter.Column> cols = new ArrayList<>();
        cols.add(new ArrowIpcWriter.Column("ROW", ArrowIpcWriter.Kind.TEXT, row));
        cols.add(new ArrowIpcWriter.Column("COLUMN", ArrowIpcWriter.Kind.TEXT, col));
        for (int k = 0; k < dims.size(); k++) {
            cols.add(new ArrowIpcWriter.Column(dims.get(k), ArrowIpcWriter.Kind.TEXT, dv[k]));
        }
        cols.add(new ArrowIpcWriter.Column("MEASURE", ArrowIpcWriter.Kind.TEXT, meas));
        cols.add(new ArrowIpcWriter.Column("VALUE", ArrowIpcWriter.Kind.FLOAT64, val));
        cols.add(new ArrowIpcWriter.Column("MEMBERS", ArrowIpcWriter.Kind.INT64, mem));
        return ArrowIpcWriter.write(cols, n, Map.of("provenance", String.valueOf(a.get("provenance")), "source", String.valueOf(a.get("source"))));
    }
}
