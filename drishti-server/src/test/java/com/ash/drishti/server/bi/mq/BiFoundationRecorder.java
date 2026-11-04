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
 * Records the fixtures of 2.9.11.e's one parity proof (docs/architecture/BI_FOUNDATION_2911E.md section 5.3) from the code it runs on:
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
        index.put("recordedWith", "BI before 2.9.11.e: models through MqService, reports through VisualQueries and QueryService, live through LiveTable");
        index.put("persons", BiFoundationCases.persons(shipped.lake()).stream().map(Map.Entry::getKey).toList());
        index.put("times", new TreeMap<>(BiFoundationCases.TIMES));
        index.put("models", List.copyOf(shipped.models().keySet()));
        index.put("notLoaded", shipped.problems());
        try (QueryService service = BiFoundationCases.service(store, shipped.lake())) {
            for (Map.Entry<String, ModelView> m : shipped.models().entrySet()) {
                files.put("model-" + m.getKey() + ".json", BiFoundationCases.model(service, m.getValue(), shipped.lake(), mq));
            }
            Map<String, Report> reports = BiFoundationCases.reports();
            Map<String, String> unbound = new TreeMap<>();
            for (Map.Entry<String, Report> r : reports.entrySet()) {
                String model = r.getValue().model().replaceAll("@.*$", "").strip();
                ModelView v = shipped.models().get(model);
                if (v == null) {
                    unbound.put(r.getKey(), "its model " + model + " is not loaded");
                    continue;
                }
                files.put("report-" + r.getKey() + ".json", BiFoundationCases.report(service, r.getValue(), v, shipped.lake()));
            }
            index.put("reports", List.copyOf(reports.keySet()));
            index.put("reportsNotRecorded", unbound);
        }
        files.put("live.json", BiFoundationLive.replay(liveStore, BiFoundationCases::norm));
        index.put("itemTests", "none ship in config/bi (no tests: or golden: in a shipped dataset, model or report); the BI suites run unchanged");
        files.put("index.json", index);
        return files;
    }
}
