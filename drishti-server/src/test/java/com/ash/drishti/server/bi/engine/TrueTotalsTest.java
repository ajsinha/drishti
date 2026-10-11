package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.bi.report.Totals;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * True totals: the matrix and table totals of measures that do not add up (ratio, average, distinct count, percentile) are worked out by the engine over
 * the rows of the whole group, and checked here against a brute-force computation over the same trades. The shown figures could not give them: the average
 * of averages is not the average.
 */
class TrueTotalsTest {

    @TempDir Path store;
    TestLake lake;
    QueryService service;
    QueryService.Loaded ds;
    Dataset dataset;

    static final String REPORT = """
            report: t
            dataset: lake-test
            pages:
              - id: p
                visuals:
                  - { id: m, type: matrix, at: { x: 0, y: 0, w: 12, h: 6 }, wells: { rows: [trades.desk, trades.currency], columns: [trades.cpty], values: [Notional, AvgMTM, MTMperM, Names, P50, Biggest] }, format: { subtotals: true } }
                  - { id: plain, type: matrix, at: { x: 0, y: 6, w: 12, h: 6 }, wells: { rows: [trades.desk], values: [AvgMTM] } }
                  - { id: t, type: table, at: { x: 0, y: 12, w: 12, h: 6 }, wells: { columns: [trades.desk, Trades, AvgMTM, MTMperM] }, format: { totals: true } }
            """;

    @BeforeEach
    void setUp() {
        lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        DatasetParser.Result r = DatasetParser.parse(TestLake.yaml());
        dataset = r.dataset();
        ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), dataset, "h1");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    QueryService.Caller risk() {
        return new QueryService.Caller("rita", Set.of("risk"), true, UserAttributes.of("rita", List.of("risk"), "", null), SqlCompiler.Masks.NONE);
    }

    Report.Visual visual(String id) {
        return ReportParser.parse(REPORT).report().page("p").visual(id);
    }

    QueryRequest base(Report.Visual v, List<String> fields, List<String> measures) {
        return new QueryRequest(fields, measures, List.of(), TestLake.DAY2, TestLake.DAY2, List.of(), 5000, "auto");
    }

    static double mean(List<TestLake.Trade> t) {
        return t.stream().mapToDouble(TestLake.Trade::mtm).average().orElse(Double.NaN);
    }

    static double median(List<TestLake.Trade> t) {
        double[] v = t.stream().mapToDouble(TestLake.Trade::mtm).sorted().toArray();
        double pos = (v.length - 1) * 0.5;
        int i = (int) Math.floor(pos);
        return i + 1 < v.length ? v[i] + (pos - i) * (v[i + 1] - v[i]) : v[i];
    }

    @Test
    void theMeasuresThatAddUpAreNotAskedForAndTheOthersAre() {
        assertThat(Totals.adds(dataset, "Notional")).isTrue();
        assertThat(Totals.adds(dataset, "Trades")).isTrue();
        for (String m : List.of("AvgMTM", "MTMperM", "Names", "P50", "Biggest", "Share")) {
            assertThat(Totals.adds(dataset, m)).as(m).isFalse();
        }
        List<Totals.Level> levels = Totals.matrix(dataset, visual("m"), base(visual("m"), List.of(), List.of()), 2000);
        assertThat(levels).extracting(Totals.Level::by).containsExactly(List.of(), List.of("trades.desk"), List.of("trades.cpty"), List.of("trades.desk", "trades.cpty"),
                List.of("trades.desk", "trades.currency"));
        assertThat(levels.get(0).query().measures()).containsExactly("AvgMTM", "MTMperM", "Names", "P50", "Biggest");
        assertThat(Totals.matrix(dataset, visual("plain"), base(visual("plain"), List.of(), List.of()), 2000)).as("no subtotals asked for").isEmpty();
    }

    @Test
    void everyLevelOfTheMatrixIsTheMeasureOverTheRowsOfItsGroup() {
        List<TestLake.Trade> all = lake.trades.get(TestLake.DAY2);
        for (Totals.Level level : Totals.matrix(dataset, visual("m"), base(visual("m"), List.of(), List.of()), 2000)) {
            QueryService.Result r = service.query(ds, level.query(), risk(), false);
            List<String> by = level.by();
            Function<TestLake.Trade, List<String>> key = t -> by.stream().map(f -> switch (f) {
                case "trades.desk" -> t.desk();
                case "trades.currency" -> t.ccy();
                default -> t.cpty();
            }).toList();
            Map<List<String>, List<TestLake.Trade>> groups = all.stream().collect(Collectors.groupingBy(key));
            assertThat(r.rows()).as(by.toString()).isEqualTo(groups.size());
            List<String> names = r.columns().stream().map(ArrowIpcWriter.Column::name).toList();
            for (int i = 0; i < r.rows(); i++) {
                final int row = i;
                List<String> k = new ArrayList<>();
                for (int c = 0; c < by.size(); c++) {
                    k.add(String.valueOf(r.columns().get(c).values()[row]));
                }
                List<TestLake.Trade> g = groups.get(k);
                assertThat(g).as(k.toString()).isNotNull();
                double notional = g.stream().mapToDouble(TestLake.Trade::notional).sum();
                double mtm = g.stream().mapToDouble(TestLake.Trade::mtm).sum();
                java.util.function.Function<String, Double> val = n -> ((Number) r.columns().get(names.indexOf(n)).values()[row]).doubleValue();
                assertThat(val.apply("AvgMTM")).isEqualTo(mean(g), org.assertj.core.data.Offset.offset(1e-9));
                assertThat(val.apply("MTMperM")).isEqualTo(mtm / notional * 1_000_000, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(val.apply("Names")).isEqualTo((double) g.stream().map(TestLake.Trade::cpty).distinct().count());
                assertThat(val.apply("P50")).isEqualTo(median(g), org.assertj.core.data.Offset.offset(1e-9));
                assertThat(val.apply("Biggest")).isEqualTo(g.stream().mapToDouble(TestLake.Trade::notional).max().orElse(0));
            }
        }
    }

    @Test
    void theAverageOfTheShownAveragesIsNotTheAverageSoTheTotalCannotBeSummed() {
        List<TestLake.Trade> all = lake.trades.get(TestLake.DAY2);
        QueryService.Result byDesk = service.query(ds, base(null, List.of("trades.desk"), List.of("AvgMTM")), risk(), false);
        double averageOfAverages = Arrays.stream(byDesk.columns().get(1).values()).mapToDouble(v -> ((Number) v).doubleValue()).average().orElse(0);
        QueryService.Result total = service.query(ds, base(null, List.of(), List.of("AvgMTM")), risk(), false);
        double truth = ((Number) total.columns().get(0).values()[0]).doubleValue();
        assertThat(truth).isEqualTo(mean(all), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Math.abs(averageOfAverages - truth)).as("40 trades in groups of 14, 13 and 13: the means differ").isGreaterThan(1e-6);
    }

    @Test
    void aTableTotalsEveryMeasureOverAllTheRowsNotJustTheOnesShown() {
        List<Totals.Level> levels = Totals.table(dataset, visual("t"), base(visual("t"), List.of(), List.of()), 2000);
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0).query().measures()).containsExactly("Trades", "AvgMTM", "MTMperM");
        QueryService.Result r = service.query(ds, levels.get(0).query(), risk(), false);
        List<TestLake.Trade> all = lake.trades.get(TestLake.DAY2);
        assertThat(((Number) r.columns().get(0).values()[0]).longValue()).isEqualTo(all.size());
        assertThat(((Number) r.columns().get(1).values()[0]).doubleValue()).isEqualTo(mean(all), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Totals.table(dataset, visual("plain"), base(visual("plain"), List.of(), List.of()), 2000)).isEmpty();
    }

    @Test
    void aMaskedMeasureHasNoTotalEither() {
        QueryService.Caller analyst = new QueryService.Caller("ann", Set.of("risk"), true, UserAttributes.of("ann", List.of("risk"), "", null), (t, c) -> c.equals("mtm"));
        QueryService.Result r = service.query(ds, base(null, List.of(), List.of("AvgMTM")), analyst, false);
        assertThat(r.meta().masked()).contains("AvgMTM");
        assertThat(r.columns().get(0).values()[0]).isNull();
    }
}
