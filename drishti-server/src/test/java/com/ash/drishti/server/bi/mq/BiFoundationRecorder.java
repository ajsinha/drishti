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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.engine.BiFoundationLive;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.report.Report;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Records the fixtures of 2.9.11.e's one parity proof (docs/architecture/BI_DESIGN.md section 5.3) from the code it runs on:
 * every case of {@link BiFoundationCases} (models, rows each person sees, reports) and the live replay ({@link BiFoundationLive}), one JSON
 * file per model and report under {@code src/test/resources/bi/foundation-parity/}, keys sorted so a diff is readable. It extends what
 * {@code BiParityRecorder} (2.9.11.b) does from the totals of one dataset to every answer and every person. Runs only when asked:
 *
 * <pre>
 * mvn -o -q -pl drishti-server -am -Dtest=BiFoundationRecorder -Ddrishti.bi.foundation.record=true -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 *
 * on the tree before anything of the release moves (develop after 2.9.11.d: copy this file, {@link BiFoundationCases} and
 * {@link BiFoundationLive} there; they use only what BI has before the switch), then commit the fixtures in a commit that holds nothing
 * else. Deleted in the switch commit (it can no longer run: the old code is gone); the fixtures and {@link BiFoundationParityTest} stay until
 * 2.9.14.
 */
class BiFoundationRecorder {

    @TempDir Path store;
    @TempDir Path liveStore;

    @Test
    @EnabledIfSystemProperty(named = "drishti.bi.foundation.record", matches = "true")
    void recordTheAnswersOfTheCodeItRunsOn() throws Exception {
        for (Map.Entry<String, Object> f : record(store, liveStore).entrySet()) {
            BiFoundationCases.write(f.getKey(), f.getValue());
        }
    }

    /** Every fixture file's content, by file name: what the recorder writes and what the parity test compares with. */
    static Map<String, Object> record(Path store, Path liveStore) throws Exception {
        Map<String, Object> files = new TreeMap<>();
        BiFoundationCases.Shipped shipped = BiFoundationCases.shipped();
        MqService mq = new MqService(MqProperties.defaults());
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("recordedWith", "BI before 2.9.11.e: models through MqService, reports through VisualQueries and QueryService (their totals through BiAggregate), "
                + "reports on a live dataset through a live QueryService at load, boards through the reports their tiles show, live through LiveTable");
        index.put("persons", BiFoundationCases.persons(shipped.lake()).stream().map(Map.Entry::getKey).toList());
        index.put("times", new TreeMap<>(BiFoundationCases.TIMES));
        index.put("models", List.copyOf(shipped.models().keySet()));
        index.put("notLoaded", shipped.problems());
        Map<String, String> notRecorded = new TreeMap<>();
        Map<String, Object> slices = new TreeMap<>();
        Map<String, Report> reports = BiFoundationCases.reports();
        try (QueryService service = BiFoundationCases.service(store, shipped.lake());
                QueryService live = BiFoundationCases.liveService(liveStore.resolve("reports"), shipped.lake())) {
            MqHarness h = new MqHarness(service, SampleLake.DAY2);
            for (Map.Entry<String, ModelView> m : shipped.models().entrySet()) {
                // a model over live tables is asked of the live service (its history tables exist only there), the others of the snapshot one
                QueryService on = m.getValue().flat().tables().values().stream().anyMatch(Dataset.Table::isLive) ? live : service;
                BiFoundationCases.Slice slice = BiFoundationCases.slice(on, m.getValue(), mq,
                        m.getValue().def().measures().values().stream().filter(x -> x.python() == null).map(ModelDef.Meas::name).toList());
                slices.put(m.getKey(), slice == null ? "none: no text field with a value" : slice.field() + " = " + slice.value());
                files.put("model-" + m.getKey() + ".json", BiFoundationCases.model(on, m.getValue(), shipped.lake(), mq));
            }
            for (Map.Entry<String, Report> r : reports.entrySet()) {
                Report report = r.getValue();
                if (report.model() != null) {
                    String model = report.model().replaceAll("@.*$", "").strip();
                    ModelView v = shipped.models().get(model);
                    if (v == null) {
                        notRecorded.put(r.getKey(), "its model " + model + " is not loaded");
                        continue;
                    }
                    // a report on a model over live tables is asked of the live service, as its dataset was before the switch
                    boolean onLive = v.flat().tables().values().stream().anyMatch(Dataset.Table::isLive);
                    MqHarness rh = onLive ? new MqHarness(live, SampleLake.DAY2) : h;
                    files.put("report-" + r.getKey() + ".json", BiFoundationCases.report(onLive ? live : service, report, v.flat(), "report:" + report.name(),
                            "h|" + v.def().name(), who -> rh.caller(v.flat(), who), shipped.lake()));
                } else if (report.dataset() != null && !report.dataset().isBlank()) {
                    Dataset d = shipped.direct().get(report.dataset());
                    if (d == null) {
                        notRecorded.put(r.getKey(), "its dataset " + report.dataset() + " is not loaded");
                        continue;
                    }
                    MqHarness lh = new MqHarness(live, SampleLake.DAY2);
                    files.put("report-" + r.getKey() + ".json", BiFoundationCases.report(live, report, d, "file:dataset:" + d.name(), "h|" + d.name(),
                            who -> lh.caller(d, who), shipped.lake()));
                }
            }
            for (Map.Entry<String, Report> r : reports.entrySet()) {
                if (r.getValue().model() == null && (r.getValue().dataset() == null || r.getValue().dataset().isBlank())) {
                    files.put("board-" + r.getKey() + ".json", board(r.getValue(), reports, files));
                }
            }
        }
        index.put("slices", slices);
        index.put("reports", List.copyOf(reports.keySet()));
        index.put("reportsNotRecorded", notRecorded);
        java.nio.file.Files.createDirectories(liveStore.resolve("replay"));
        files.put("live.json", BiFoundationLive.replay(liveStore.resolve("replay"), BiFoundationCases::norm));
        index.put("itemTests", "none ship in config/bi (no tests: or golden: in a shipped dataset, model or report); the BI suites run unchanged");
        index.put("notRecordable", new TreeMap<>(Map.of(
                "python visuals and the python model's measures", "the console runs them (a python visual, desk-pnl-vs-exposure-py's result frame): left out, or recorded as their refusal",
                "saved pivot views", "none ship: a pivot view is a person's, in the content store; BI's pivot figures are recorded as the matrices' totals and the models' subtotals")));
        files.put("index.json", index);
        return files;
    }

    /**
     * A board: per person, each tile's answer is the answer of the visual it shows ({@code from}), as recorded for that report (a python visual's
     * tile says so: the console draws it).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> board(Report board, Map<String, Report> reports, Map<String, Object> files) {
        Map<String, Object> out = new TreeMap<>();
        for (Report.Page page : board.pages()) {
            for (Report.Visual tile : page.visuals()) {
                if (tile.from() == null) {
                    continue;
                }
                Report source = reports.get(tile.from().report());
                Report.Page at = source == null ? null : source.pages().stream()
                        .filter(pg -> (tile.from().page() == null || tile.from().page().isBlank() || pg.id().equals(tile.from().page())) && pg.visual(tile.from().visual()) != null)
                        .findFirst().orElse(null);
                String key = at == null ? null : at.id() + "/" + tile.from().visual();
                Map<String, Object> recorded = (Map<String, Object>) files.get("report-" + tile.from().report() + ".json");
                if (recorded == null || key == null) {
                    out.put(page.id() + "/" + tile.id(), Map.of("from", tile.from().report() + "/" + tile.from().visual(), "recorded", "no: its report is not recorded"));
                    continue;
                }
                for (Map.Entry<String, Object> person : recorded.entrySet()) {
                    Object answer = ((Map<String, Object>) person.getValue()).get(key);
                    Map<String, Object> mine = (Map<String, Object>) out.computeIfAbsent(person.getKey(), k -> new TreeMap<>());
                    mine.put(page.id() + "/" + tile.id() + " <- " + tile.from().report() + "/" + key,
                            answer == null ? Map.of("python", "the console draws it") : answer);
                }
            }
        }
        return out;
    }
}
