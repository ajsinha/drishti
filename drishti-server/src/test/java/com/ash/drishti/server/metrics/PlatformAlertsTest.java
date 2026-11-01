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
package com.ash.drishti.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The platform alert rules (OPERATIONS.md section 13.6): read from their file, evaluated against the registry, opened and closed, told once. */
class PlatformAlertsTest {

    @TempDir Path dir;

    /** A clock the test moves. */
    static final class Tick extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T09:00:00Z"));

        void add(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private PlatformAlerts alerts(SimpleMeterRegistry r, String yaml, Tick clock, List<PlatformAlerts.Alert> told) throws Exception {
        Path f = dir.resolve("platform-alerts.yaml");
        Files.writeString(f, yaml);
        return new PlatformAlerts(r, new MetricsProperties.Alerts(true, f.toString(), List.of("admin"), 10), "node-a", clock, told::add);
    }

    @Test
    void aSeriesOverItsThresholdForTheRuleTimeOpensOnceAndClosesWhenBack() throws Exception {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        double[] used = {50};
        Gauge.builder("drishti.cache.bytes", used, u -> u[0]).tags("cache", "var.answers", "where", "heap").register(r);
        Gauge.builder("drishti.cache.limit.bytes", () -> 100).tags("cache", "var.answers", "where", "heap").register(r);
        Gauge.builder("drishti.cache.bytes", () -> 99).tags("cache", "no-limit", "where", "heap").register(r);      // no limit: never alerted
        Tick clock = new Tick();
        List<PlatformAlerts.Alert> told = new ArrayList<>();
        PlatformAlerts a = alerts(r, """
                rules:
                  cache-near-limit:
                    metric: drishti.cache.bytes
                    divide-by: drishti.cache.limit.bytes
                    above: 0.95
                    for: 1h
                    format: percent
                    summary: "cache {cache} at {value} of its limit"
                """, clock, told);
        a.evaluate();
        assertThat(a.open()).isEmpty();
        used[0] = 97;
        a.evaluate();                                                      // over, but not yet for an hour
        clock.add(Duration.ofMinutes(59));
        a.evaluate();
        assertThat(a.open()).isEmpty();
        clock.add(Duration.ofMinutes(1));
        a.evaluate();
        a.evaluate();
        assertThat(a.open()).singleElement().satisfies(x -> {
            assertThat(x.summary()).isEqualTo("cache var.answers at 97.0% of its limit");
            assertThat(x.tags()).containsEntry("cache", "var.answers");
            assertThat(x.member()).isEqualTo("node-a");
            assertThat(x.severity()).isEqualTo("warn");
        });
        assertThat(told).hasSize(1);                                       // told once, however long it stays open
        assertThat(r.get("drishti.alerts.open").tag("severity", "warn").gauge().value()).isEqualTo(1.0);
        used[0] = 60;
        clock.add(Duration.ofMinutes(5));
        a.evaluate();
        assertThat(a.open()).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) a.describe().get("recent");
        assertThat(recent).singleElement().satisfies(x -> assertThat(x.get("closedAt")).isEqualTo(clock.instant()));
        assertThat(r.get("drishti.alerts.open").tag("severity", "warn").gauge().value()).isZero();
    }

    @Test
    void anIncreaseOverItsWindowAndASumOverSeries() throws Exception {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        Counter a5 = Counter.builder("http.server.requests.test").tags("outcome", "SERVER_ERROR", "uri", "/a").register(r);
        Counter b5 = Counter.builder("http.server.requests.test").tags("outcome", "SERVER_ERROR", "uri", "/b").register(r);
        Counter ok = Counter.builder("http.server.requests.test").tags("outcome", "SUCCESS", "uri", "/a").register(r);
        Tick clock = new Tick();
        List<PlatformAlerts.Alert> told = new ArrayList<>();
        PlatformAlerts al = alerts(r, """
                rules:
                  server-errors:
                    metric: http.server.requests.test
                    tags: {outcome: SERVER_ERROR}
                    aggregate: sum
                    function: increase
                    window: 5m
                    above: 10
                    severity: critical
                    summary: "{value} server errors in 5 minutes"
                """, clock, told);
        a5.increment(100);                                                 // before the first look: not counted
        al.evaluate();
        ok.increment(1000);
        a5.increment(6);
        b5.increment(6);
        clock.add(Duration.ofMinutes(1));
        al.evaluate();
        assertThat(al.open()).singleElement().satisfies(x -> {
            assertThat(x.summary()).isEqualTo("12 server errors in 5 minutes");
            assertThat(x.severity()).isEqualTo("critical");
        });
        clock.add(Duration.ofMinutes(10));                                 // nothing more: the window has moved past the errors
        al.evaluate();
        clock.add(Duration.ofMinutes(1));
        al.evaluate();
        assertThat(al.open()).isEmpty();
    }

    @Test
    void aBadRuleIsLeftOutAndNamedAndAChangedFileIsReadAgain() throws Exception {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        Gauge.builder("drishti.source.up", () -> 0).tags("source", "rates").register(r);
        Tick clock = new Tick();
        List<PlatformAlerts.Alert> told = new ArrayList<>();
        PlatformAlerts a = alerts(r, """
                rules:
                  no-threshold: {metric: drishti.source.up}
                  both: {metric: drishti.source.up, above: 1, below: 0}
                  bad-severity: {metric: drishti.source.up, below: 1, severity: loud}
                  off: {metric: drishti.source.up, below: 1, enabled: false}
                  source-down: {metric: drishti.source.up, below: 1, severity: critical, summary: "source {source} is down"}
                """, clock, told);
        a.evaluate();
        Map<String, Object> d = a.describe();
        assertThat((List<?>) d.get("rules")).hasSize(1);
        assertThat((List<?>) d.get("problems")).hasSize(3).anySatisfy(p -> assertThat(p.toString()).startsWith("bad-severity: severity is one of"));
        assertThat(told).singleElement().satisfies(x -> assertThat(x.summary()).isEqualTo("source rates is down"));
        Path f = dir.resolve("platform-alerts.yaml");
        Files.writeString(f, "rules: {}\n");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        a.evaluate();                                                      // the rule is gone: its alert closes
        assertThat(a.open()).isEmpty();
        assertThat((List<?>) a.describe().get("recent")).hasSize(1);
    }

    @Test
    void aMissingFileMeansNoRulesAndSaysSo() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        PlatformAlerts a = new PlatformAlerts(r, new MetricsProperties.Alerts(true, dir.resolve("none.yaml").toString(), List.of(), 10), "n",
                Clock.systemUTC(), x -> { });
        a.evaluate();
        assertThat(a.summary()).containsEntry("found", false).containsEntry("rules", 0);
    }

    @Test
    void valuesAreWrittenInTheirFormat() {
        assertThat(PlatformAlerts.format("percent", 0.8571)).isEqualTo("85.7%");
        assertThat(PlatformAlerts.format("bytes", 5.0 * 1024 * 1024 * 1024)).isEqualTo("5 GB");
        assertThat(PlatformAlerts.format("seconds", 0.412)).isEqualTo("412 ms");
        assertThat(PlatformAlerts.format("seconds", 1.94)).isEqualTo("1.94 s");
        assertThat(PlatformAlerts.format("number", 12.0)).isEqualTo("12");
    }

    @Test
    void theShippedRulesAllRead() {
        Path shipped = Path.of("../config/alerts/platform-alerts.yaml");
        PlatformRule.Loaded l = PlatformRule.load(shipped);
        assertThat(l.found()).isTrue();
        assertThat(l.problems()).isEmpty();
        assertThat(l.rules()).extracting(PlatformRule::name).contains("cache-near-limit", "heap-after-gc-high", "direct-memory-high",
                "disk-free-under-guard", "source-down", "views-slow", "server-errors");
    }
}
