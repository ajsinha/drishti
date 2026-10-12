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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The map, funnel, histogram, box plot and bubble: they validate, ask the engine for the right thing, and the distributions are worked out right. */
class NewVisualsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static QueryService.Result result(String measure, double[] values, String[] categories) {
        Object[] vals = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            vals[i] = values[i];
        }
        List<ArrowIpcWriter.Column> cols = new ArrayList<>();
        if (categories != null) {
            cols.add(new ArrowIpcWriter.Column("trade.desk", ArrowIpcWriter.Kind.TEXT, categories));
        }
        cols.add(new ArrowIpcWriter.Column(measure, ArrowIpcWriter.Kind.FLOAT64, vals));
        QueryService.Meta meta = new QueryService.Meta("columns", null, values.length, 0, false, List.of(), List.of(), List.of(), List.of(), null, 0);
        return new QueryService.Result(cols, values.length, meta);
    }

    @Test
    void aHistogramCountsEveryValueIntoOneEqualWidthBin() {
        Random rnd = new Random(7);
        double[] v = new double[1000];
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextGaussian() * 1000;
        }
        Distributions.Shape h = Distributions.histogram(result("MTM", v, null), "MTM", 12);
        assertThat(h.rows()).hasSize(12);
        assertThat(h.rows().stream().mapToLong(r -> (Long) r.get(1)).sum()).isEqualTo(1000);
        double lo = java.util.Arrays.stream(v).min().orElse(0);
        double hi = java.util.Arrays.stream(v).max().orElse(0);
        double width = (hi - lo) / 12;
        for (int b = 0; b < 12; b++) {
            final int bin = b;
            long expected = java.util.Arrays.stream(v).filter(x -> bin == 11 ? x >= lo + bin * width : x >= lo + bin * width && x < lo + (bin + 1) * width).count();
            assertThat(h.rows().get(b).get(1)).as("bin " + b).isEqualTo(expected);
        }
        assertThat((Double) h.rows().get(0).get(2)).isEqualTo(lo);
        assertThat((Double) h.rows().get(11).get(3)).isEqualTo(hi);
        assertThat(h.columns()).extracting(c -> c.get("name")).containsExactly("bin", "count", "from", "to");
    }

    @Test
    void aHistogramOfOneValueIsOneBarAndOfNoValuesIsEmpty() {
        assertThat(Distributions.histogram(result("MTM", new double[] {5, 5, 5}, null), "MTM", 10).rows()).hasSize(1);
        assertThat(Distributions.histogram(result("MTM", new double[] {}, null), "MTM", 10).rows()).isEmpty();
        assertThat(Distributions.histogram(result("MTM", new double[] {1, 2}, null), "MTM", 5000).rows()).hasSizeLessThanOrEqualTo(Distributions.MAX_BINS);
    }

    @Test
    void aBoxHasQuartilesWhiskersAndOutliers() {
        double[] v = {1, 2, 3, 4, 5, 6, 7, 8, 9, 100};
        Distributions.Shape b = Distributions.box(result("MTM", v, null), null, "MTM");
        assertThat(b.rows()).hasSize(1);
        List<Object> row = b.rows().get(0);
        assertThat(row.get(0)).isEqualTo("All");
        assertThat((double) row.get(1)).isEqualTo(1);          // lower whisker
        assertThat((double) row.get(2)).isEqualTo(3.25);       // q1 by linear interpolation
        assertThat((double) row.get(3)).isEqualTo(5.5);        // median
        assertThat((double) row.get(4)).isEqualTo(7.75);       // q3
        assertThat((double) row.get(5)).isEqualTo(9);          // upper whisker: 100 is beyond 7.75 + 1.5 * 4.5
        assertThat(row.get(6)).isEqualTo(10L);
        assertThat(row.get(7)).isEqualTo("100");
    }

    @Test
    void aBoxPerCategoryIsInCategoryOrder() {
        double[] v = {1, 2, 3, 10, 20, 30};
        String[] c = {"B", "B", "B", "A", "A", "A"};
        Distributions.Shape b = Distributions.box(result("MTM", v, c), "trade.desk", "MTM");
        assertThat(b.rows()).extracting(r -> r.get(0)).containsExactly("A", "B");
        assertThat(b.rows().get(0).get(3)).isEqualTo(20.0);
        assertThat(b.rows().get(1).get(3)).isEqualTo(2.0);
        assertThat(b.columns().get(0).get("name")).isEqualTo("trade.desk");
    }

    @Test
    void aMaskedMeasureHasNoBoxAndNoBars() {
        QueryService.Result r = result("MTM", new double[] {1, 2, 3}, null);
        QueryService.Meta m = r.meta();
        QueryService.Result masked = new QueryService.Result(r.columns(), r.rows(), new QueryService.Meta(m.layer(), null, 3, 0, false, List.of(), List.of(), List.of("MTM"), List.of(), null, 0));
        assertThat(Distributions.histogram(masked, "MTM", 5).masked()).contains("count");
        assertThat(Distributions.box(masked, null, "MTM").masked()).contains("median");
    }

    // ---- the report side ---------------------------------------------------------------------------------------------

    static final String HEAD = """
            report: r
            dataset: trading-risk
            pages:
              - id: p
                visuals:
            """;

    List<ReportParser.Problem> problems(String visual) throws IOException {
        ReportParser.Result parsed = ReportParser.parse(HEAD + "      - " + visual + "\n");
        List<ReportParser.Problem> out = new ArrayList<>(parsed.problems());
        if (parsed.report() != null) {
            out.addAll(ReportValidator.validate(parsed.report(), ReportParserTest.dataset("trading-risk")));
        }
        return out.stream().filter(ReportParser.Problem::error).toList();
    }

    @Test
    void theNewVisualsValidateAndTheWrongWellsAreRefused() throws IOException {
        String at = ", at: { x: 0, y: 0, w: 6, h: 4 }";
        assertThat(problems("{ id: m, type: map" + at + ", wells: { location: [counterparties.country], values: [Trades] } }")).isEmpty();
        assertThat(problems("{ id: m, type: map" + at + ", wells: { latitude: [trades.dv01], longitude: [trades.dv01], values: [Trades] } }")).isEmpty();
        assertThat(problems("{ id: f, type: funnel" + at + ", wells: { axis: [trades.status], values: [Trades] } }")).isEmpty();
        assertThat(problems("{ id: h, type: histogram" + at + ", wells: { axis: [trades.tradeId], values: [MTM] }, format: { bins: 20 } }")).isEmpty();
        assertThat(problems("{ id: b, type: box" + at + ", wells: { axis: [trades.desk], details: [trades.tradeId], values: [MTM] } }")).isEmpty();
        assertThat(problems("{ id: u, type: bubble" + at + ", wells: { details: [counterparties.name], x: [Trades], y: [MTM], size: [Notional] } }")).isEmpty();
        assertThat(problems("{ id: m, type: map" + at + ", wells: { values: [Trades] } }")).extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("a country code"));
        assertThat(problems("{ id: m, type: map" + at + ", wells: { location: [counterparties.country], latitude: [trades.dv01], longitude: [trades.dv01], values: [Trades] } }"))
                .extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("not both"));
        assertThat(problems("{ id: m, type: map" + at + ", wells: { location: [counterparties.country], values: [Trades] }, format: { level: AUS, drill: false } }")).isEmpty();
        assertThat(problems("{ id: m, type: map" + at + ", wells: { location: [counterparties.country], values: [Trades] }, format: { level: 5 } }")).extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("level"));
        assertThat(problems("{ id: m, type: map" + at + ", wells: { location: [counterparties.country], values: [Trades] }, format: { drill: sometimes } }")).extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("drill"));
        assertThat(problems("{ id: m, type: map" + at + ", wells: { latitude: [trades.dv01], values: [Trades] } }")).extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("both a latitude"));
        assertThat(problems("{ id: m, type: map" + at + ", wells: { latitude: [trades.status], longitude: [trades.dv01], values: [Trades] } }")).extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("is a number"));
        assertThat(problems("{ id: h, type: histogram" + at + ", wells: { axis: [trades.tradeId], values: [MTM] }, format: { bins: 1 } }")).extracting(ReportParser.Problem::path).anyMatch(p -> p.endsWith("format.bins"));
        assertThat(problems("{ id: h, type: histogram" + at + ", wells: { axis: [trades.tradeId], values: [trades.mtm] } }")).isNotEmpty();
        assertThat(problems("{ id: b, type: box" + at + ", wells: { axis: [trades.desk], values: [MTM] } }")).extracting(ReportParser.Problem::message).anyMatch(m -> m.contains("details"));
    }

    @Test
    void theShippedCounterpartyRiskReportHoldsTheMapTheYtdCardAndTheHistogram() throws IOException {
        ReportParser.Result parsed = ReportParser.parse(ReportParserTest.report("counterparty-risk"));
        assertThat(parsed.problems().stream().filter(ReportParser.Problem::error).toList()).isEmpty();
        assertThat(ReportValidator.validate(parsed.report(), ReportParserTest.dataset("trading-risk")).stream().filter(ReportParser.Problem::error).toList()).isEmpty();
        Report.Page p = parsed.report().page("exposure");
        assertThat(p.visual("exposure-map").type()).isEqualTo("map");
        assertThat(p.visual("mtm-ytd").well("values")).containsExactly("MTM ytd");
        assertThat(p.visual("mtm-spread").format().get("bins")).isEqualTo(12);
    }

    @Test
    void theQueriesTheNewVisualsAsk() throws IOException {
        Report r = VisualQueriesTest.report("counterparty-risk");
        Dataset ds = ReportParserTest.dataset("trading-risk");
        Report.Page p = r.page("exposure");
        VisualQueries.TopValues top = (f, n, by, desc, ctx, from, to) -> List.of();
        QueryRequest map = VisualQueries.build(r, p, p.visual("exposure-map"), ReportState.of(JSON.readTree("{}")), ds, BiReportProperties.defaults(), top).query();
        assertThat(map.fields()).containsExactly("counterparties.country");
        assertThat(map.measures()).containsExactly("Exposure (EE peak)");
        QueryRequest hist = VisualQueries.build(r, p, p.visual("mtm-spread"), ReportState.of(JSON.readTree("{\"limit\": 10}")), ds, BiReportProperties.defaults(), top).query();
        assertThat(hist.fields()).containsExactly("trades.tradeId");
        assertThat(hist.limit()).as("a distribution reads its members whatever the screen's limit").isEqualTo(BiReportProperties.defaults().distributionRows());
        assertThat(hist.orderBy()).isEmpty();
    }
}
