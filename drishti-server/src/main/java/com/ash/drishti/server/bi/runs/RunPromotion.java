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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.bi.mq.MqException;
import com.ash.drishti.server.security.Principal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Promoting a run's result (docs/architecture/BI_RUNS.md section 9.4): the stored answer, <b>as the promoter sees it</b> (masked values are stored as the
 * mask, never the value), is copied to {@code snapshots/<name>/v<N>/answer.json} and a <b>draft dataset</b> ({@code source: {run: <name>, version: N,
 * token}}) is made in the promoter's workspace, optionally with a draft report showing it. The draft is the promoter's only; it reaches other readers through
 * the normal review and publish, where its {@code provenance} (computed as whom, under which signature, from which question and data) is visible.
 *
 * <p>Who may read a snapshot: only the dataset it was promoted to (the same name and the token written in that dataset's YAML), so another author's dataset
 * cannot point at someone else's snapshot. Columns masked for the promoter are declared {@code masked} in the dataset and hold the mask in the file, so they
 * stay masked for every reader, raw or not.
 */
public final class RunPromotion implements ModelCatalog.Snapshots {

    /** What promoting made: the snapshot, its version, the dataset and report items. */
    public record Promoted(String name, int version, String datasetItem, String reportItem, String yaml) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("snapshot", name);
            m.put("version", version);
            m.put("dataset", Map.of("name", name, "item", datasetItem == null ? "" : datasetItem));
            m.put("report", reportItem == null ? null : Map.of("item", reportItem));
            m.put("yaml", yaml);
            return m;
        }
    }

    /** Makes or changes the draft items (the BI service); the item's id. */
    public interface Drafter {
        /** Creates a draft of {@code kind} in the promoter's workspace, or, with {@code itemId}, saves a new draft of that item. */
        String draft(Principal p, String itemId, String kind, String title, String body);
    }

    private final RunService runs;
    private final SecureRandom random = new SecureRandom();
    private volatile Drafter drafter;

    public RunPromotion(RunService runs) {
        this.runs = runs;
    }

    public void drafter(Drafter d) {
        this.drafter = d;
    }

    /** {@code desk-pnl-2026}: lower-case letters, digits, dot, dash, underscore. */
    static String name(String asked, Run r) {
        String base = asked != null && !asked.isBlank() ? asked : RunService.title(r);
        String n = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-._]+|[-._]+$", "");
        if (n.isEmpty()) {
            n = "run-snapshot";
        }
        return n.length() > 100 ? n.substring(0, 100) : n;
    }

    /**
     * Promotes {@code r}, a finished run of the promoter's, to a snapshot and a draft dataset ({@code to}: {@code dataset}, or {@code report} for a draft
     * report on it too). Needs {@code runs.promote.enabled}; the caller checks the power ({@code bi-author}) and ownership.
     */
    public Promoted promote(Run r, Principal p, String asked, String title, String to) {
        RunsProperties.Promote settings = runs.properties().promote();
        if (!settings.enabled()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "promoting runs is off on this server (config/runs/runs.yaml promote.enabled: false)");
        }
        if (!"mq".equals(r.sourceType()) && !"sql".equals(r.sourceType())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a " + r.sourceType() + " run cannot be promoted: only a model query or a SQL statement's table");
        }
        if (!"dataset".equals(to) && !"report".equals(to)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "to is dataset or report, not '" + to + "'");
        }
        Drafter d = drafter;
        if (d == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "promoting needs BI items on this server");
        }
        Map<String, Object> answer = runs.result(r, r.signature());           // the promoter's own view: their row rules and masks
        List<Map<String, Object>> columns = columns(answer);
        if (columns.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this run's result has no table to promote");
        }
        String name = name(asked, r);
        Optional<Map<String, Object>> earlier = runs.store().snapshot(name, "latest");
        if (earlier.isPresent() && !p.user().equals(earlier.get().get("promoter"))) {
            throw new DrishtiException(ErrorCode.BI_CONFLICT, "the snapshot name '" + name + "' is taken by another person's promotion: choose another name");
        }
        String token = earlier.map(e -> String.valueOf(e.get("token"))).orElseGet(() -> HexFormat.of().formatHex(bytes(12)));
        String item = earlier.map(e -> e.get("item") == null ? null : String.valueOf(e.get("item"))).orElse(null);
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("run", r.id());
        provenance.put("computedAs", r.runner());
        provenance.put("signature", r.signature());
        provenance.put("dataVersion", r.dataVersion());
        provenance.put("asOf", r.asOf());
        provenance.put("question", r.question());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("dataset", name);
        doc.put("token", token);
        doc.put("promoter", p.user());
        doc.put("provenance", provenance);
        doc.put("item", item);
        doc.put("answer", answer);
        int version = runs.store().promote(name, doc, settings.versionsKeep());
        String datasetTitle = title != null && !title.isBlank() ? title : RunService.title(r) + " (snapshot of run " + r.id() + (r.asOf() == null ? "" : ", as of " + r.asOf()) + ")";
        String yaml = yaml(name, version, token, datasetTitle, columns, provenance);
        String datasetItem = d.draft(p, item, "dataset", datasetTitle, yaml);
        if (item == null) {
            Map<String, Object> keep = new LinkedHashMap<>(runs.store().snapshot(name, String.valueOf(version)).orElse(doc));
            keep.put("item", datasetItem);
            runs.store().rewriteSnapshot(name, version, keep);                  // the next promotion of this name drafts the same item
        }
        Run.Builder b = r.toBuilder();
        b.promotedTo = name + "@" + version;                                   // the run object's promotedTo; its result is kept (8.4)
        runs.records().save(b.build());
        String reportItem = "report".equals(to) ? d.draft(p, null, "report", datasetTitle + " (report)", report(name, columns)) : null;
        return new Promoted(name, version, datasetItem, reportItem, yaml);
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> columns(Map<String, Object> answer) {
        return answer.get("columns") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /** A dataset column's name for an answer column ({@code trades.desk} is {@code desk}, {@code Notional} is {@code notional}), unique. */
    static List<String> names(List<Map<String, Object>> columns) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            String full = String.valueOf(c.get("name"));
            String last = full.substring(full.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
            if (last.isEmpty() || !Character.isLetter(last.charAt(0))) {
                last = "c_" + last;
            }
            if (DatasetDef.BUILT_IN.contains(last) || last.equals("businessdate")) {
                last = last + "_value";
            }
            String n = last;
            for (int i = 2; !seen.add(n); i++) {
                n = last + "_" + i;
            }
            out.add(n);
        }
        return out;
    }

    /** The draft dataset's YAML (section 9.4): its source, columns from the answer, the promoter's masks as {@code masked}, and its provenance. */
    static String yaml(String name, int version, String token, String title, List<Map<String, Object>> columns, Map<String, Object> provenance) {
        List<String> names = names(columns);
        StringBuilder y = new StringBuilder();
        y.append("# A run's result promoted to a dataset snapshot (docs/architecture/BI_RUNS.md section 9.4). The rows are what the promoter could see;\n")
                .append("# columns masked for them hold the mask and stay masked for every reader. Review the provenance before publishing.\n");
        y.append("rachana: 2\n");
        y.append("dataset: ").append(name).append('\n');
        y.append("version: 1.0.0\n");
        y.append("title: ").append(quote(title)).append('\n');
        y.append("source: { run: ").append(name).append(", version: ").append(version).append(", token: ").append(token).append(" }\n");
        List<String> keys = new ArrayList<>();
        List<String> masked = new ArrayList<>();
        y.append("columns:\n");
        for (int i = 0; i < columns.size(); i++) {
            Map<String, Object> c = columns.get(i);
            String n = names.get(i);
            boolean field = !"measure".equals(c.get("kind"));
            String type = String.valueOf(c.get("type"));
            if (!DatasetDef.TYPES.contains(type)) {
                type = field ? "string" : "decimal";
            }
            y.append("  ").append(n).append(": { type: ").append(type).append(", path: ").append(quote(String.valueOf(c.get("name"))));
            if (field) {
                y.append(", role: ").append("date".equals(type) ? "date" : "dimension");
                keys.add(n);
            } else {
                boolean adds = Boolean.TRUE.equals(c.get("additive"));
                y.append(", role: ").append(adds ? "amount" : "ratio").append(", aggregate: ").append(adds ? "sum" : "avg");
                if (c.get("format") instanceof String f && !f.isBlank()) {
                    y.append(", format: ").append(quote(f));
                }
            }
            y.append(" }\n");
            if (Boolean.TRUE.equals(c.get("masked"))) {
                masked.add(n);
            }
        }
        y.append("grain: { keys: [").append(String.join(", ", keys.isEmpty() ? List.of("id") : keys)).append("] }\n");
        if (!masked.isEmpty()) {
            y.append("security:\n  masked: [").append(String.join(", ", masked)).append("]          # masked for the promoter: masked always\n");
        }
        y.append("provenance:\n");
        for (Map.Entry<String, Object> e : provenance.entrySet()) {
            y.append("  ").append(e.getKey()).append(": ").append(e.getValue() == null ? "null" : quote(String.valueOf(e.getValue()))).append('\n');
        }
        return y.toString();
    }

    /** A draft report with one table of every column of the snapshot. */
    static String report(String name, List<Map<String, Object>> columns) {
        String a = name.substring(name.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9_]", "_");      // DatasetDef.defaultAlias
        String alias = a.isEmpty() || !Character.isLetter(a.charAt(0)) ? "t_" + a : a;
        List<String> wells = names(columns).stream().map(n -> alias + "." + n).toList();
        return "report: " + name + "-report\nmodel: \"dataset:" + name + "\"\npages:\n  - id: main\n    visuals:\n"
                + "      - { id: rows, type: table, title: " + quote(name) + ", at: { x: 0, y: 0, w: 12, h: 8 }, wells: { columns: [" + String.join(", ", wells) + "] } }\n";
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    // ---- reading a snapshot (the model layer's staged table) -----------------------------------------------------------

    @Override
    public ModelCatalog.Snapshot read(String dataset, String name, String version, String token) {
        Map<String, Object> doc;
        try {
            doc = runs.store().snapshot(name, version).orElse(null);
        } catch (IllegalArgumentException e) {
            doc = null;
        }
        if (doc == null) {
            throw new MqException(MqException.Problem.of("DRS-9108", "source.run", "no run snapshot '" + name + "' version " + version
                    + " (it was never promoted, or was removed past promote.versions-keep): promote the run again"));
        }
        if (!name.equals(dataset) || !String.valueOf(doc.get("token")).equals(token)) {
            throw new MqException(MqException.Problem.of("DRS-9108", "source.run", "the run snapshot '" + name + "' belongs to the dataset it was promoted to; "
                    + "this dataset may not read it"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = doc.get("answer") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<Map<String, Object>> cols = columns(answer);
        List<?> rows = answer.get("rows") instanceof List<?> l ? l : List.of();
        List<ArrowIpcWriter.Column> out = new ArrayList<>();
        for (int c = 0; c < cols.size(); c++) {
            Object[] values = new Object[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                Object v = rows.get(i) instanceof List<?> row && c < row.size() ? row.get(c) : null;
                values[i] = v == null ? null : String.valueOf(v);                  // typed by the dataset's columns as it is staged (a mask casts to null)
            }
            out.add(new ArrowIpcWriter.Column(String.valueOf(cols.get(c).get("name")), ArrowIpcWriter.Kind.TEXT, values));
        }
        java.time.LocalDate asOf = null;
        Object p = doc.get("provenance") instanceof Map<?, ?> pm ? pm.get("asOf") : null;
        if (p instanceof String s && s.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                asOf = java.time.LocalDate.parse(s.length() > 10 ? s.substring(s.length() - 10) : s);
            } catch (RuntimeException e) {
                asOf = null;
            }
        }
        int v = doc.get("version") instanceof Number n ? n.intValue() : 0;
        QueryService.Meta meta = new QueryService.Meta("run-snapshot", null, rows.size(), 0, true, List.of(), List.of(name), List.of(), List.of(), null, v);
        return new ModelCatalog.Snapshot(new QueryService.Result(out, rows.size(), meta), asOf, v);
    }
}
