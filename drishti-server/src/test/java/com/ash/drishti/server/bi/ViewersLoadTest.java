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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The 500-viewer load test of wave B (acceptance (5), docs/architecture/BI_DESIGN.md section 6.8). <b>Run on a real server, never locally</b>: it is skipped
 * unless {@code -Ddrishti.load.url=http://server:18480} is given. A morning of {@code drishti.load.viewers} (500) viewers of one report's visuals with
 * {@code drishti.load.users} (5 distinct securities, {@code user:password,...}): each subscribes to every visual (the standing queries group them by plan and
 * signature: 5 evaluations per visual, not 500), reads its stream for {@code drishti.load.seconds} (120), and asks the visuals again now and then (the
 * L1 cache). The SLAs checked (p95): a cached visual under 50 ms, a visual on the columns layer under 500 ms, and no refusal but {@code DRS-9025}
 * retried. The model and its visuals are {@code drishti.load.model} and {@code drishti.load.queries} (a file of model queries, one JSON per line).
 * The tiny in-process smoke of the same grouping is {@code mq.LiveStandingQueriesTest} and {@code mq.TwoNodesTest}.
 */
class ViewersLoadTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "drishti.load.url", matches = ".+")
    void fiveHundredViewersOfOneModelMeetTheSlas() throws Exception {
        String url = System.getProperty("drishti.load.url");
        int viewers = Integer.getInteger("drishti.load.viewers", 500);
        int seconds = Integer.getInteger("drishti.load.seconds", 120);
        List<String[]> users = new ArrayList<>();
        for (String u : System.getProperty("drishti.load.users", "rita:rita,hana:hana,ann:ann,wes:wes,otto:otto").split(",")) {
            users.add(u.split(":", 2));
        }
        List<String> queries = java.nio.file.Files.readAllLines(java.nio.file.Path.of(System.getProperty("drishti.load.queries"))).stream().filter(l -> !l.isBlank()).toList();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        List<Double> cold = new CopyOnWriteArrayList<>();
        List<Double> warm = new CopyOnWriteArrayList<>();
        Map<String, AtomicInteger> refusals = new ConcurrentHashMap<>();
        AtomicInteger answers = new AtomicInteger();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int v = 0; v < viewers; v++) {
                String[] user = users.get(v % users.size());
                String auth = "Basic " + Base64.getEncoder().encodeToString((user[0] + ":" + user[1]).getBytes(StandardCharsets.UTF_8));
                pool.execute(() -> {
                    try {
                        for (String q : queries) {
                            HttpResponse<String> r = http.send(post(url + "/api/v1/bi/mq/subscribe", auth, "{\"query\": " + q + ", \"throttle\": \"250ms\", \"deliver\": \"diff\"}"),
                                    HttpResponse.BodyHandlers.ofString());
                            count(r, refusals);
                        }
                        HttpRequest stream = HttpRequest.newBuilder(URI.create(url + "/api/v1/bi/mq/stream")).header("Authorization", auth).timeout(Duration.ofSeconds(seconds + 30))
                                .GET().build();
                        java.util.concurrent.CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> lines = http.sendAsync(stream,
                                HttpResponse.BodyHandlers.ofLines());
                        boolean first = true;
                        while (System.nanoTime() < until) {
                            for (String q : queries) {
                                long t0 = System.nanoTime();
                                HttpResponse<String> r = http.send(post(url + "/api/v1/bi/mq", auth, q), HttpResponse.BodyHandlers.ofString());
                                double ms = (System.nanoTime() - t0) / 1e6;
                                if (count(r, refusals)) {
                                    JsonNode meta = JSON.readTree(r.body()).path("meta");
                                    (meta.path("cached").asBoolean(false) ? warm : cold).add(ms);
                                    answers.incrementAndGet();
                                }
                            }
                            Thread.sleep(first ? 1000 : 5000);
                            first = false;
                        }
                        lines.cancel(true);
                    } catch (Exception e) {
                        refusals.computeIfAbsent("client: " + e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
                    }
                });
            }
        }
        double warmP95 = p95(warm);
        double coldP95 = p95(cold);
        System.out.printf("viewers %d, answers %d, cached p95 %.1f ms (%d), columns p95 %.1f ms (%d), refusals %s%n", viewers, answers.get(), warmP95, warm.size(), coldP95,
                cold.size(), refusals);
        assertThat(warmP95).as("a cached visual (L1 hit), p95").isLessThan(50);
        assertThat(coldP95).as("a visual on the columns layer, p95").isLessThan(500);
        assertThat(refusals.keySet()).as("no refusal but a busy one, retried").allMatch(k -> k.equals("DRS-9025"));
        HttpResponse<String> engine = http.send(HttpRequest.newBuilder(URI.create(url + "/api/v1/bi/engine")).header("Authorization",
                "Basic " + Base64.getEncoder().encodeToString((users.get(0)[0] + ":" + users.get(0)[1]).getBytes(StandardCharsets.UTF_8))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("engine: " + engine.body());
    }

    private static HttpRequest post(String url, String auth, String body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", auth).header("Content-Type", "application/json").timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    /** True for an answer; a refusal is counted by its code. */
    private static boolean count(HttpResponse<String> r, Map<String, AtomicInteger> refusals) throws Exception {
        if (r.statusCode() < 300) {
            return true;
        }
        String code = JSON.readTree(r.body()).path("code").asText("HTTP " + r.statusCode());
        refusals.computeIfAbsent(code, k -> new AtomicInteger()).incrementAndGet();
        return false;
    }

    static double p95(List<Double> xs) {
        if (xs.isEmpty()) {
            return 0;
        }
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        return s.get((int) Math.min(s.size() - 1, Math.ceil(s.size() * 0.95) - 1));
    }

    @Test
    void thePercentileOfTheSlasIsTheNinetyFifth() {
        List<Double> xs = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            xs.add((double) i);
        }
        assertThat(p95(xs)).isEqualTo(95.0);
    }
}
