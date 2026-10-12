package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.python.PythonItems;
import com.ash.drishti.server.bi.transform.TransformRunner;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the number of a <em>Python measure</em> for an alert (DISTRIBUTION.md "Python measures in alerts"). The server builds the rows the person may see (the
 * engine applies their row rules and masks), sends them with the item to the console's sandboxed runner ({@link PythonRunnerClient}), and reduces what the runner
 * answers, a number for each group, to the one number the alert judges. A column the person cannot see (masked) makes the reading masked: nothing is run and no
 * figure is shown, as for any other measure.
 */
public final class PythonAlertReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DatasetService datasets;
    private final BiReportService reports;
    private final PythonItems items;
    private final TransformRunner runner;
    private final int maxRows;

    /** @param maxRows most rows one run is handed (the {@code drishti.bi.report.python-rows} of a python visual) */
    public PythonAlertReader(DatasetService datasets, BiReportService reports, PythonItems items, TransformRunner runner, int maxRows) {
        this.maxRows = maxRows;
        this.datasets = datasets;
        this.reports = reports;
        this.items = items;
        this.runner = runner;
    }

    /** The Python measure of a dataset named by an alert, or null when the measure is an ordinary one. */
    public Dataset.Measure pythonMeasure(Principal who, String dataset, String measure) {
        Dataset.Measure m = datasets.parsed(who, dataset).measures().get(measure);
        return m != null && m.python() != null ? m : null;
    }

    /** A Python measure of a dataset, over the latest business date ({@code date}), no grouping: one number. */
    public Reading ofDataset(Principal who, String dataset, Dataset.Measure m, JsonNode filters, LocalDate date) {
        ObjectNode q = rowsQuery(m, filters, date);
        Map<String, Object> answer = datasets.json(datasets.query(who, dataset, QueryRequest.of(q), false));
        return run(who, m, answer, List.of(), "first", date);
    }

    /** The Python measure a python visual of a report shows, for the person: the groups the visual's rows make, reduced by {@code reduce}. */
    public Reading ofVisual(Principal who, String report, String page, String visual, JsonNode state, String reduce, LocalDate date) {
        Map<String, Object> desc = reports.pythonOf(who, report, null, page, visual);
        @SuppressWarnings("unchecked")
        Map<String, Object> measure = (Map<String, Object>) desc.get("measure");
        if (measure == null) {
            return Reading.error("a Python visual that draws a chart has no number to watch; watch a Python measure of the dataset");
        }
        Map<String, Object> answer = reports.query(who, report, null, page, visual, state, false);
        @SuppressWarnings("unchecked")
        List<String> by = (List<String>) measure.get("by");
        return run(who, desc, measure, answer, by.stream().map(PythonAlertReader::shortName).toList(), reduce, date);
    }

    private ObjectNode rowsQuery(Dataset.Measure m, JsonNode filters, LocalDate date) {
        Set<String> fields = new LinkedHashSet<>(m.by());
        fields.addAll(m.inputs());
        ObjectNode q = JSON.createObjectNode();
        var arr = q.putArray("fields");
        fields.forEach(arr::add);
        String table = fields.stream().filter(f -> f.contains(".")).map(f -> f.substring(0, f.indexOf('.'))).findFirst().orElse(null);
        if (table != null) {
            arr.add(table + "." + Dataset.ID);                      // the table's own rows, not the distinct combinations
        }
        if (filters != null) {
            q.set("filters", filters);
        }
        if (date != null) {
            q.set("dates", JSON.createObjectNode().put("from", date.toString()).put("to", date.toString()));
        }
        q.put("limit", maxRows);
        return q;
    }

    private Reading run(Principal who, Dataset.Measure m, Map<String, Object> answer, List<String> groupBy, String reduce, LocalDate date) {
        Map<String, Object> desc = items.describe(who, m.python(), true);
        Map<String, Object> measure = new LinkedHashMap<>();
        measure.put("name", m.name());
        measure.put("inputs", m.inputs());
        measure.put("by", m.by());
        return run(who, desc, measure, answer, groupBy, reduce, date);
    }

    @SuppressWarnings("unchecked")
    private Reading run(Principal who, Map<String, Object> described, Map<String, Object> measure, Map<String, Object> answer, List<String> groupBy, String reduce, LocalDate date) {
        List<Map<String, Object>> cols = (List<Map<String, Object>>) answer.get("columns");
        for (Map<String, Object> c : cols) {
            if (Boolean.TRUE.equals(c.get("masked"))) {
                return Reading.masked(String.valueOf(c.get("name")));
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>((Map<String, Object>) described.get("manifest"));
        manifest.put("inputs", ((List<String>) measure.get("inputs")).stream().map(PythonAlertReader::shortName).toList());
        List<List<Object>> rows = (List<List<Object>>) answer.get("rows");
        if (rows.size() > maxRows) {
            rows = rows.subList(0, maxRows);
        }
        List<String> names = cols.stream().map(c -> shortName(String.valueOf(c.get("name")))).toList();
        Map<String, Object> params = new LinkedHashMap<>();
        Object own = described.get("params");
        if (own instanceof Map<?, ?> pm) {
            pm.forEach((k, v) -> params.put(String.valueOf(k), v));
        }
        JsonNode out;
        try {
            out = runner.measure(manifest, params, groupBy, names, rows);
        } catch (DrishtiException e) {
            return Reading.error(e.getMessage());
        }
        List<Map<String, Object>> outCols = List.of(Map.of("name", "value", "type", "decimal"));
        List<List<Object>> outRows = new ArrayList<>();
        for (JsonNode r : out.path("rows")) {
            JsonNode v = r.get(r.size() - 1);
            outRows.add(java.util.Collections.singletonList(v == null || v.isNull() ? null : (Object) v.asDouble()));
        }
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("columns", outCols);
        shaped.put("rows", outRows);
        shaped.put("meta", answer.get("meta"));
        return BiValueReader.reduce(shaped, "value", reduce == null || reduce.isBlank() ? "first" : reduce);
    }

    static String shortName(String column) {
        return column.contains(".") ? column.substring(column.indexOf('.') + 1) : column;
    }
}
