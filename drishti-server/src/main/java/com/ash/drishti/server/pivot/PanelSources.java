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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularSource;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.bind.PivotBinder;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A panel's rows as a pivot source (PIVOT_ENGINE.md section 2: "the Pivot tab on a table or ladder becomes a pivot view over the panel's
 * rows"): a table or ladder whose Sutra says {@code pivot:} offers <b>Pivot</b>, which opens the pivot screen on the source
 * {@code panel/<kind>/<panel>/<id>}. The rows are the view's own (the same document, Sutra and business date), read by the view pipeline as
 * the person may see them; a field masked for them is left out (never a dimension or an input); the Sutra's arrangement is the view the
 * screen opens with. A panel of more rows than {@code drishti.pivot.max-records} is refused in words, never cut short.
 */
public final class PanelSources implements TabularSource.Resolver {

    /** The prefix of a panel source. */
    public static final String PREFIX = "panel/";

    private final ViewPipeline pipeline;
    private final Entitlements entitlements;
    private final StagedRows staged;

    public PanelSources(ViewPipeline pipeline, Entitlements entitlements, int keep) {
        this.pipeline = pipeline;
        this.entitlements = entitlements;
        this.staged = new StagedRows(keep);
    }

    /** The source id of a panel of an entity's view. */
    public static String id(String kind, String panel, String entity) {
        return PREFIX + kind + "/" + panel + "/" + entity;
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        return Map.of();                                       // a panel is opened from its page, never listed
    }

    @Override
    public boolean serves(String source) {
        return source != null && source.startsWith(PREFIX);
    }

    @Override
    public TabularSource open(Viewer v, String source, String date) {
        Principal p = KindSources.principal(v);
        String[] parts = source.substring(PREFIX.length()).split("/", 3);
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "a panel source is panel/<kind>/<panel>/<id>, not " + source);
        }
        String kind = parts[0];
        String panel = parts[1];
        String entity = parts[2];
        entitlements.requireOpen(p, kind);
        LocalDate day = null;
        if (date != null && !date.isBlank()) {
            try {
                day = LocalDate.parse(date.strip());
            } catch (DateTimeParseException e) {
                throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "not a date: '" + date + "' (write 2026-09-30)");
            }
        }
        PivotBinder.Records r = pipeline.records(EntityRef.of(kind, entity), day == null ? AsOf.LATEST : AsOf.of(day), panel,
                entitlements.redactor(p), k -> entitlements.mayOpen(p, k));
        if (r.truncated()) {
            throw new DrishtiException(ErrorCode.PIVOT_TOO_MANY_CELLS, "the panel " + panel + " of " + entity + " has " + r.total() + " rows, more "
                    + "than a panel's pivot reads (drishti.pivot.max-records, " + r.limit() + "): pivot the table itself (PIVOT " + kind
                    + ") or raise the limit; a pivot is never cut short");
        }
        List<PivotProvider.Field> fields = new ArrayList<>();
        List<Integer> keepCols = new ArrayList<>();
        int masked = 0;
        for (int k = 0; k < r.fields().size(); k++) {
            Map<String, Object> f = r.fields().get(k);
            String name = String.valueOf(f.get("name"));
            if (maskedColumn(r.rows(), k) || entitlements.masked(p, name)) {
                masked++;
                continue;
            }
            String fmt = f.get("fmt") == null ? null : String.valueOf(f.get("fmt"));
            String type = "date".equals(fmt) ? "date" : numeric(r.rows(), k) && !"text".equals(fmt) ? "number" : "string";
            String unit = fmt == null ? "" : switch (fmt) {
                case "money", "amount", "currency", "notional" -> "money";
                case "pct", "percent", "bp" -> "pct";
                default -> "";
            };
            fields.add(new PivotProvider.Field(name, type, String.valueOf(f.getOrDefault("label", name)), "a field of the panel", unit));
            keepCols.add(k);
        }
        String masks = entitlements.masks(p) ? "m-" + p.user() : "raw";
        String version = (day == null ? "latest" : day.toString()) + "|" + Integer.toHexString(r.rows().hashCode()) + "|" + masks;
        String table = "pp_" + Integer.toHexString((source + "|" + version).hashCode()).replace('-', 'n');
        Map<String, Object> view = SpecViews.view(source, day == null ? null : day.toString(), r.arrangement());
        return new Panel(source, kind + " " + entity + " · " + panel, fields, keepCols, masked, version, table, r, view);
    }

    /** True when every value of a column is the mask (a masked field of the person). */
    private static boolean maskedColumn(List<List<Object>> rows, int k) {
        boolean any = false;
        for (List<Object> row : rows) {
            Object v = row.get(k);
            if (v == null) {
                continue;
            }
            if (!DataNode.MASK.equals(String.valueOf(v))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static boolean numeric(List<List<Object>> rows, int k) {
        boolean any = false;
        for (List<Object> row : rows) {
            Object v = row.get(k);
            if (v == null) {
                continue;
            }
            if (!(v instanceof Number)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** One panel opened for one person. */
    private final class Panel implements TabularSource {
        final String id;
        final String title;
        final List<PivotProvider.Field> fields;
        final List<Integer> cols;
        final int masked;
        final String version;
        final String table;
        final PivotBinder.Records records;
        final Map<String, Object> view;

        Panel(String id, String title, List<PivotProvider.Field> fields, List<Integer> cols, int masked, String version, String table,
                PivotBinder.Records records, Map<String, Object> view) {
            this.id = id;
            this.title = title;
            this.fields = fields;
            this.cols = cols;
            this.masked = masked;
            this.version = version;
            this.table = table;
            this.records = records;
            this.view = view;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public List<PivotProvider.Field> fields() {
            return fields;
        }

        @Override
        public int masked() {
            return masked;
        }

        @Override
        public String date() {
            return null;
        }

        @Override
        public List<String> dates() {
            return List.of();
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public long rows() {
            return records.rows().size();
        }

        @Override
        public long bytes() {
            return (long) records.rows().size() * Math.max(1, fields.size()) * 12L;
        }

        @Override
        public Map<String, Object> notes() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("route", "the panel's own rows (the same document, Sutra and business date as its page), read for you and staged in memory");
            m.put("masks", masked == 0 ? "none" : masked + " field(s) masked for you: not read, not usable");
            m.put("defaultView", view);
            return m;
        }

        @Override
        public Relation relation(DuckDbExecutor e) {
            return staged.relation(e, table, fields, cols, records.rows());
        }
    }

    /** Drops every staged table (the engine switched off). */
    public void release(DuckDbExecutor e) {
        staged.release(e);
    }
}
