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
package com.ash.drishti.server.feeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The server's view of the data feeds: the feed files, the ledger's summary per feed, the failure gauge, Run now's refusals. */
class DataFeedsTest {

    @TempDir
    Path tmp;

    private DataFeeds feeds(SimpleMeterRegistry registry) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("config"));
        Files.writeString(config.resolve("gleif.yaml"), """
                feed: gleif
                title: GLEIF
                lake: referencelake
                schedule: {at: "05:30", days: daily}
                licence: {name: CC0 1.0, url: "https://www.gleif.org", redistribution: allowed}
                datasets: {lei-entity: {pack: gleif, kind: lei-entity}}
                """);
        Files.writeString(config.resolve("world-economy.yaml"), """
                feed: world-economy
                title: World economy
                lake: worldlake
                load: inbox
                enabled: false
                licence: {name: IMF terms, url: "https://www.imf.org", redistribution: internal-only, internal-only: true}
                datasets: {economy: {pack: world-economy, kind: economy}}
                """);
        Path ledger = Files.createDirectories(tmp.resolve("data/feeds")).resolve("ledger.jsonl");
        Files.writeString(ledger, String.join("\n",
                "{\"at\": \"2026-11-06T05:30:00-05:00\", \"feed\": \"gleif\", \"status\": \"ok\", \"exit\": 0, \"rows\": 4377, \"businessDate\": \"2026-11-06\", \"seconds\": 41.2}",
                "{\"at\": \"2026-11-07T05:30:00-05:00\", \"feed\": \"gleif\", \"status\": \"failed\", \"exit\": 1, \"error\": \"HTTP 503\", \"seconds\": 9.0}",
                "{\"at\": \"2026-11-07T06:00:00-05:00\", \"feed\": \"gleif\", \"status\": \"dry-run\", \"exit\": 0}",
                "{\"at\": \"2026-11-07T06:45:00-05:00\", \"feed\": \"world-economy\", \"status\": \"ok\", \"exit\": 0, \"rows\": 12, \"businessDate\": \"2026-11-07\"}",
                "{not json",
                ""));
        return new DataFeeds(config, tmp.resolve("data"), tmp.resolve("root"), "", registry);
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusSumsUpTheLedgerPerFeed() throws Exception {
        Map<String, Object> st = feeds(null).status(10);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) st.get("feeds");
        assertThat(rows).extracting(r -> r.get("feed")).containsExactly("gleif", "world-economy");
        Map<String, Object> g = rows.get(0);
        assertThat(g.get("lastStatus")).isEqualTo("failed");
        assertThat(g.get("failed")).isEqualTo(true);
        assertThat(g.get("lastSuccess")).isEqualTo("2026-11-06T05:30:00-05:00");
        assertThat(g.get("rows")).isEqualTo(4377);
        assertThat(g.get("failuresSinceSuccess")).isEqualTo(1);
        assertThat(g.get("lastError")).isEqualTo("HTTP 503");
        assertThat(g.get("schedule")).isEqualTo("daily 05:30");
        Map<String, Object> w = rows.get(1);
        assertThat(w.get("enabled")).isEqualTo(false);
        assertThat(w.get("internalOnly")).isEqualTo(true);
        assertThat(w.get("load")).isEqualTo("inbox");
        assertThat(w.get("failed")).isEqualTo(false);
        assertThat(st.get("failing")).isEqualTo(1L);
        assertThat((List<Map<String, Object>>) st.get("runs")).hasSize(4).first().extracting(r -> r.get("feed")).isEqualTo("world-economy");
    }

    @Test
    void theGaugeIsOneForAFeedWhoseLastRunFailed() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        feeds(registry).sample();
        assertThat(registry.get("drishti.datafeed.failed").tag("feed", "gleif").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("drishti.datafeed.failed").tag("feed", "world-economy").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void runNowRefusesAnUnknownFeedAndSkipsWhileOneRuns() throws Exception {
        DataFeeds f = feeds(null);
        assertThatThrownBy(() -> f.runNow("nope", "admin")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no data feed");
        assertThatThrownBy(() -> f.runNow("../x", "admin")).isInstanceOf(IllegalArgumentException.class);
        Files.createDirectories(tmp.resolve("data/feeds/gleif"));
        Files.writeString(tmp.resolve("data/feeds/gleif/run.lock"), "{}");
        Map<String, Object> out = f.runNow("gleif", "admin");
        assertThat(out.get("started")).isEqualTo(false);
        assertThat(String.valueOf(out.get("skipped"))).contains("in progress");
        Files.delete(tmp.resolve("data/feeds/gleif/run.lock"));
        assertThatThrownBy(() -> f.runNow("gleif", "admin")).isInstanceOf(IllegalStateException.class).hasMessageContaining("tools");
    }

    @Test
    void theShippedFeedFilesAllList() {
        DataFeeds f = new DataFeeds(Path.of("../config/feeds"), tmp, tmp, "", null);
        List<Map<String, Object>> all = f.feeds();
        assertThat(all).extracting(m -> m.get("feed")).contains("gleif", "us-equities", "funds", "world-economy");
        assertThat(all).allSatisfy(m -> assertThat(m).doesNotContainKey("problem"));
    }
}
