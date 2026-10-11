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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports for the people who use them (RUPAKA.md section 8): finds the report item the caller may read, parses and checks it against its dataset, and
 * answers one visual's query at a time, under the page's interaction state, through the dataset query engine as that caller: their row rules, their
 * masks. A report is read with the grants of whoever published it: a reader needs the report, not the dataset's catalogue entry (the engine still
 * applies the reader's own rows and masks).
 */
public final class BiReportService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A report with what is needed to use it. */
    private record Loaded(BiStore.Item item, ReportParser.Result parsed, BiStore.Item datasetItem, Dataset dataset, List<ReportParser.Problem> problems) {}

    private final BiService bi;
    private final DatasetService datasets;
    private final BiReportProperties props;

    public BiReportService(BiService bi, DatasetService datasets, BiReportProperties props) {
        this.bi = bi;
        this.datasets = datasets;
        this.props = props;
    }

    // ---- resolving ---------------------------------------------------------------------------------------------------

    private BiStore.Item reportItem(Principal p, String id) {
        BiStore.Item i = bi.readableItem(p, id);
        if (!"report".equals(i.kind())) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + id + "' is not a report");
        }
        return i;
    }

    /**
     * The dataset a report names: by catalogue id, shipped file name, the dataset's own name or its title. A report someone published reads its dataset with its
     * publisher's grants ({@code viaPublished}: any published dataset); a draft, and the designer's lookups, find only the datasets the caller may read themselves, so
     * a report cannot be used to reach a dataset the catalogue hides from its author.
     */
    BiStore.Item datasetFor(Principal p, String ref, boolean viaPublished) {
        if (ref == null || ref.isBlank()) {
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report names no dataset");
        }
        List<BiStore.Item> pool = viaPublished ? bi.allDatasets() : bi.datasetItems(p);
        for (BiStore.Item i : pool) {
            if (i.id().equals(ref) || i.id().equals("file:dataset:" + ref)) {
                return i;
            }
        }
        for (BiStore.Item i : pool) {
            if (i.title().equalsIgnoreCase(ref) || ref.equals(declaredName(p, i))) {
                return i;
            }
        }
        try {
            BiStore.Item mine = bi.readableItem(p, ref);
            if ("dataset".equals(mine.kind())) {
                return mine;
            }
        } catch (DrishtiException e) {
            // not an item the caller can read: the refusal below
        }
        throw new DrishtiException(ErrorCode.REPORT_DATASET, "no dataset '" + ref + "' (a catalogue id, a file name such as trades-basic, or the dataset's name)");
    }

    private String declaredName(Principal p, BiStore.Item i) {
        DatasetParser.Result r = DatasetParser.parse(bi.bodyFor(p, i));
        return r.dataset() == null ? "" : r.dataset().name();
    }

    private Loaded load(Principal p, BiStore.Item item, String yaml, boolean viaPublished) {
        ReportParser.Result parsed = ReportParser.parse(yaml);
        List<ReportParser.Problem> problems = new ArrayList<>(parsed.problems());
        BiStore.Item dsItem = null;
        Dataset ds = null;
        if (parsed.report() != null && !parsed.report().dataset().isBlank()) {
            try {
                dsItem = datasetFor(p, parsed.report().dataset(), viaPublished);
                ds = datasets.parsedDataset(p, dsItem);
            } catch (DrishtiException e) {
                problems.add(ReportParser.error(e.errorCode().code(), "dataset", e.getMessage()));
            }
        }
        if (ds != null) {
            problems.addAll(ReportValidator.validate(parsed.report(), ds));
        }
        return new Loaded(item, parsed, dsItem, ds, problems);
    }

    private Loaded loadItem(Principal p, String id) {
        BiStore.Item item = reportItem(p, id);
        String body = bi.bodyFor(p, item);
        return load(p, item, body, body.equals(bi.publishedBody(item)));            // the published text reads with its publisher's grants; a draft only with the caller's own
    }

    private static boolean ok(List<ReportParser.Problem> problems) {
        return problems.stream().noneMatch(ReportParser.Problem::error);
    }

    // ---- the API -----------------------------------------------------------------------------------------------------

    /** One report: its model, its problems and its dataset's model (tables, columns, measures, hierarchies) as this caller sees them. */
    public Map<String, Object> get(Principal p, String id) {
        Loaded l = loadItem(p, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.item().id());
        m.put("title", l.item().title());
        m.put("description", l.item().description());
        m.put("status", l.item().status());
        m.put("shipped", com.ash.drishti.server.bi.BiFolder.isShipped(l.item().id()));
        m.put("sample", l.item().tags().contains(com.ash.drishti.server.bi.BiFolder.SAMPLE_TAG));
        m.put("valid", ok(l.problems()));
        m.put("problems", l.problems().stream().map(ReportParser.Problem::toMap).toList());
        if (l.parsed().report() != null) {
            m.put("report", l.parsed().report().toMap());
        }
        if (l.datasetItem() != null && l.dataset() != null) {
            m.put("dataset", datasetSummary(p, l.datasetItem()));
        }
        m.put("mayEdit", bi.mayAuthor(p));
        return m;
    }

    private Map<String, Object> datasetSummary(Principal p, BiStore.Item dsItem) {
        Map<String, Object> d = new LinkedHashMap<>(datasets.modelOf(p, dsItem));
        d.remove("layers");
        d.remove("security");
        d.remove("rollups");
        return d;
    }

    /** The dataset a report names, as the designer's Fields pane needs it (authors). */
    public Map<String, Object> datasetModel(Principal p, String ref) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return datasetSummary(p, datasetFor(p, ref, false));
    }

    /** Checks report YAML without storing it: the problems with their codes, the model, and (when {@code quality}) the accessibility and layout warnings. */
    public Map<String, Object> validate(Principal p, String yaml, boolean quality) {
        bi.requirePower(p, BiAccess.AUTHOR);
        Loaded l = load(p, null, yaml, false);
        List<ReportParser.Problem> problems = new ArrayList<>(l.problems());
        if (quality && l.parsed().report() != null) {
            problems.addAll(ReportValidator.accessibility(l.parsed().report()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", ok(problems));
        m.put("problems", problems.stream().map(ReportParser.Problem::toMap).toList());
        if (l.parsed().report() != null) {
            m.put("report", l.parsed().report().toMap());
        }
        return m;
    }

    /** Writes a report model (the structure {@link Report#toMap} has) as YAML. */
    public Map<String, Object> format(Principal p, JsonNode model) {
        bi.requirePower(p, BiAccess.BI);
        if (model == null || !model.isObject()) {
            throw new DrishtiException(ErrorCode.REPORT_INVALID, "send the report as an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.convertValue(model, LinkedHashMap.class);
        return Map.of("yaml", ReportYaml.write(m));
    }

    /** The visual catalogue: types, their groups and wells. */
    public Map<String, Object> catalogue(Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return Map.of("visuals", VisualCatalogue.toMap(), "reserved", VisualCatalogue.RESERVED.stream().sorted().toList(), "slicerModes", VisualCatalogue.SLICER_MODES.stream().sorted().toList());
    }

    /**
     * Runs one visual of a stored report ({@code yaml} null) or of draft YAML ({@code yaml} given; authors only) under the page's state.
     *
     * @return the engine's JSON answer plus {@code highlight} (the same query under the highlighted marks, or null), {@code query} (what was asked of the
     *     dataset) and {@code visual}
     */
    public Map<String, Object> query(Principal p, String id, String yaml, String pageId, String visualId, JsonNode stateJson, boolean explain) {
        Prepared pre = prepare(p, id, yaml, pageId, visualId, stateJson);
        boolean author = bi.mayAuthor(p);
        Map<String, Object> live = liveOf(p, pre);                  // read before the query: rows that change meanwhile are above its seq, so a client asking "since" misses none
        QueryService.Result r = datasets.queryItem(p, pre.dsItem(), pre.built().query(), explain && author);
        Map<String, Object> out = new LinkedHashMap<>(datasets.json(r));
        if (pre.built().highlight() != null) {
            out.put("highlight", datasets.json(datasets.queryItem(p, pre.dsItem(), pre.built().highlight(), false)));
        } else {
            out.put("highlight", null);
        }
        out.put("visual", pre.visual().id());
        out.put("query", describe(pre.built().query()));
        if (live != null) {
            out.put("live", live);
        }
        return out;
    }

    /** The rows of one visual as an Arrow IPC stream (for Perspective), with the live state in the headers' place: {@code meta} and {@code live}. */
    public record ArrowAnswer(byte[] bytes, Map<String, Object> meta, Map<String, Object> live) {}

    public ArrowAnswer queryArrow(Principal p, String id, String pageId, String visualId, JsonNode stateJson) {
        Prepared pre = prepare(p, id, null, pageId, visualId, stateJson);
        Map<String, Object> live = liveOf(p, pre);
        QueryService.Result r = datasets.queryItem(p, pre.dsItem(), pre.built().query(), false);
        return new ArrowAnswer(datasets.arrow(r), datasets.meta(r), live);
    }

    /** The state of the live tables behind a report (for the indicator and for a client that keeps rows), or null when the dataset has none. */
    private Map<String, Object> liveOf(Principal p, Prepared pre) {
        if (pre.dataset().tables().values().stream().noneMatch(Dataset.Table::isLive)) {
            return null;
        }
        return datasets.liveTick(p, pre.dsItem());
    }

    /** A visual's query, built and ready to run. */
    private record Prepared(Report report, Report.Visual visual, BiStore.Item dsItem, Dataset dataset, VisualQueries.Built built) {}

    private Prepared prepare(Principal p, String id, String yaml, String pageId, String visualId, JsonNode stateJson) {
        Loaded l;
        if (yaml != null) {
            bi.requirePower(p, BiAccess.AUTHOR);
            l = load(p, null, yaml, false);
        } else {
            l = loadItem(p, id);
        }
        Report report = l.parsed().report();
        if (report == null || l.dataset() == null || !ok(l.problems())) {
            String why = l.problems().stream().filter(ReportParser.Problem::error).findFirst().map(x -> x.code() + " " + x.path() + ": " + x.message()).orElse("the report cannot be read");
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report has errors, so it cannot be drawn: " + why);
        }
        Report.Page page = report.page(pageId);
        if (page == null) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "the report has no page '" + pageId + "'");
        }
        Report.Visual visual = page.visual(visualId);
        if (visual == null) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "page '" + pageId + "' has no visual '" + visualId + "'");
        }
        ReportState state = ReportState.of(stateJson);
        BiStore.Item dsItem = l.datasetItem();
        VisualQueries.Built built = VisualQueries.build(report, page, visual, state, l.dataset(), props, (field, n, by, desc, context, from, to) -> top(p, dsItem, field, n, by, desc, context, from, to));
        return new Prepared(report, visual, dsItem, l.dataset(), built);
    }

    /** True when the report's dataset has a live table (its visuals update in place); nothing is started. */
    public boolean isLive(Principal p, String id) {
        Loaded l = loadItem(p, id);
        return l.dataset() != null && l.dataset().tables().values().stream().anyMatch(Dataset.Table::isLive);
    }

    /**
     * Opens what a stream of a report needs: the dataset's live tables are started when they are not running, and the returned supplier reads their state (cheap:
     * it does not read the report again). {@code null} for a report whose dataset has no live table. The caller's right to read the report is checked here, once.
     */
    public java.util.function.Supplier<Map<String, Object>> liveWatch(Principal p, String id) {
        Loaded l = loadItem(p, id);
        if (l.datasetItem() == null || l.dataset() == null) {
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report's dataset cannot be found, so it has no live state");
        }
        if (l.dataset().tables().values().stream().noneMatch(Dataset.Table::isLive)) {
            return null;
        }
        return datasets.liveWatch(p, l.datasetItem());
    }

    private List<Object> top(Principal p, BiStore.Item dsItem, String field, int n, String by, boolean desc, List<QueryRequest.Filter> context, LocalDate from, LocalDate to) {
        QueryRequest q = new QueryRequest(List.of(field), List.of(by), context, from, to, List.of(new QueryRequest.Sort(by, desc)), Math.min(n, props.topCandidates()), "auto");
        QueryService.Result r = datasets.queryItem(p, dsItem, q, false);
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < r.rows(); i++) {
            values.add(r.columns().get(0).values()[i]);
        }
        return values;
    }

    /** The query in words, for the designer's preview and for people checking what a visual asked. */
    static Map<String, Object> describe(QueryRequest q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fields", q.fields());
        m.put("measures", q.measures());
        m.put("filters", q.filters().stream().map(f -> f.field() + " " + f.op().toUpperCase(Locale.ROOT) + " " + f.values()).toList());
        m.put("orderBy", q.orderBy().stream().map(s -> s.by() + (s.desc() ? " desc" : "")).toList());
        m.put("limit", q.limit());
        return m;
    }
}
