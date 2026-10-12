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
package com.ash.drishti.server.alerts.measure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.python.PythonItems;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.transform.TransformRunner;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** An alert on a Python measure: what is handed to the runner, what a masked column does, and how the runner's groups become the one number. */
class PythonAlertReaderTest {

    static final String DATASET = """
            dataset: d
            tables:
              trade:
                source: { kind: trade }
                columns: { desk: string, pnl1d: decimal }
            measures:
              N: { expr: "count(trade)" }
              Vol: { python: vol, inputs: [trade.pnl1d], by: [trade.desk] }
            """;
    static final ObjectMapper JSON = new ObjectMapper();

    final Principal who = mock(Principal.class);
    final DatasetService datasets = mock(DatasetService.class);
    final BiReportService reports = mock(BiReportService.class);
    final PythonItems items = mock(PythonItems.class);
    final List<Object[]> calls = new ArrayList<>();
    Map<String, Object> answer;

    final TransformRunner runner = new TransformRunner() {
        @Override
        public Result run(Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonNode measure(Map<String, Object> manifest, Map<String, Object> params, List<String> groupBy, List<String> columns, List<List<Object>> rows) {
            calls.add(new Object[] {manifest, params, groupBy, columns, rows});
            if (rows.isEmpty()) {
                throw new DrishtiException(ErrorCode.PYTHON_FAILED, "no rows to run on");
            }
            double sum = rows.stream().mapToDouble(r -> ((Number) r.get(0)).doubleValue()).sum();
            return JSON.valueToTree(Map.of("ok", true, "by", List.of(), "rows", List.of(List.of(sum))));
        }
    };

    PythonAlertReader reader() {
        Dataset ds = DatasetParser.parse(DATASET).dataset();
        when(datasets.parsed(any(), anyString())).thenReturn(ds);
        when(datasets.query(any(), anyString(), any(), anyBoolean())).thenReturn(mock(QueryService.Result.class));
        when(datasets.json(any())).thenAnswer(i -> answer);
        Map<String, Object> manifest = new LinkedHashMap<>(Map.of("name", "vol", "interface", "measure", "inputs", List.of("pnl1d"), "source", "def compute(t, p): return 1"));
        when(items.describe(any(), eq("vol"), eq(true))).thenReturn(Map.of("manifest", manifest, "params", Map.of("k", 2)));
        return new PythonAlertReader(datasets, reports, items, runner, 100);
    }

    static Map<String, Object> rows(boolean masked, Object[]... data) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("columns", List.of(Map.of("name", "trade.pnl1d", "type", "decimal", "masked", masked), Map.of("name", "trade.desk", "type", "string", "masked", false),
                Map.of("name", "trade.id", "type", "string", "masked", false)));
        List<List<Object>> r = new ArrayList<>();
        for (Object[] d : data) {
            r.add(new ArrayList<>(List.of(d)));
        }
        a.put("rows", r);
        a.put("meta", Map.of("dates", List.of("2026-10-09")));
        return a;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRunnerIsHandedThePersonsRowsWithShortColumnNamesAndTheItemsDefaults() {
        PythonAlertReader r = reader();
        answer = rows(false, new Object[] {1.5, "A", "t1"}, new Object[] {2.5, "B", "t2"});
        Dataset.Measure m = r.pythonMeasure(who, "d", "Vol");
        assertThat(m).isNotNull();
        assertThat(r.pythonMeasure(who, "d", "N")).as("an ordinary measure is not Python").isNull();
        Reading reading = r.ofDataset(who, "d", m, null, null);
        assertThat(reading.status()).isEqualTo(Reading.Status.OK);
        assertThat(reading.value()).isEqualTo(4.0);
        assertThat(reading.asOf()).hasToString("2026-10-09");
        Object[] call = calls.get(0);
        assertThat((List<?>) call[2]).as("no grouping: one number").isEmpty();
        assertThat((List<Object>) call[3]).containsExactly("pnl1d", "desk", "id");
        assertThat((Map<Object, Object>) call[1]).containsEntry("k", 2);
        ArgumentCaptor<QueryRequest> q = ArgumentCaptor.forClass(QueryRequest.class);
        org.mockito.Mockito.verify(datasets).query(any(), eq("d"), q.capture(), eq(false));
        assertThat(q.getValue().fields()).containsExactly("trade.desk", "trade.pnl1d", "trade.id");
        assertThat(q.getValue().measures()).isEmpty();
    }

    @Test
    void aMaskedColumnIsAMaskedReadingAndNothingIsRun() {
        PythonAlertReader r = reader();
        answer = rows(true, new Object[] {1.5, "A", "t1"});
        Reading reading = r.ofDataset(who, "d", r.pythonMeasure(who, "d", "Vol"), null, null);
        assertThat(reading.status()).isEqualTo(Reading.Status.MASKED);
        assertThat(Double.isNaN(reading.value())).isTrue();
        assertThat(calls).isEmpty();
    }

    @Test
    void aFailureOfTheRunnerIsAnErrorReadingWithItsWords() {
        PythonAlertReader r = reader();
        answer = rows(false);
        Reading reading = r.ofDataset(who, "d", r.pythonMeasure(who, "d", "Vol"), null, null);
        assertThat(reading.status()).isEqualTo(Reading.Status.ERROR);
        assertThat(reading.note()).contains("no rows to run on");
    }

    @Test
    void aPythonVisualThatDrawsAChartHasNoNumberToWatch() {
        PythonAlertReader r = reader();
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("measure", null);
        when(reports.pythonOf(any(), anyString(), org.mockito.ArgumentMatchers.isNull(), anyString(), anyString())).thenReturn(desc);
        Reading reading = r.ofVisual(who, "rep", "p", "v", null, "first", null);
        assertThat(reading.status()).isEqualTo(Reading.Status.ERROR);
        assertThat(reading.note()).contains("Python measure");
    }
}
