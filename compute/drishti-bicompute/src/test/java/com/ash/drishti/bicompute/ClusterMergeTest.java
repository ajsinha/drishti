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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.cluster.ClusterRoute;
import com.ash.drishti.bicompute.cluster.PartialGather;
import com.ash.drishti.bicompute.scale.ScalePolicy;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Cluster partial aggregation (HOW_IT_FITS.md sections A26.6 and 26.13, {@code MergeOrderTest}): a view computed on two or three nodes (each
 * evaluating its row shard, its partial states sent as JSON and merged in node order) equals the view on one node: counts, members and
 * sketch registers exactly, sums and the other mergeable states to the last digits; the same digits on every run and whichever node is late
 * (hedged); a share with no answer refuses the view in words, never a figure from the other nodes alone. The gather combines in share order
 * whatever order the answers arrive in.
 */
class ClusterMergeTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static final Map<String, Object> VIEW = Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "columns", List.of("CCY"), "expandTo",
            Map.of("rows", 2), "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "S"), Map.of("measure", "count()", "as", "C"),
                    Map.of("measure", "avg(PNL)", "as", "A"), Map.of("measure", "var_samp(PNL)", "as", "V"), Map.of("measure", "min(PNL)", "as", "Mn"),
                    Map.of("measure", "distinct(CPTY)", "as", "D")));

    /** Nodes in one test: each a fixture with the same seeded table; peers call each other's {@link ClusterRoute#answer} through JSON. */
    static final class Cluster implements AutoCloseable {
        final List<PivotFixture> nodes = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile String slow;
        volatile String dead;

        Cluster(int n) {
            for (int i = 0; i < n; i++) {
                nodes.add(new PivotFixture(4_000, StateCache.none(), PivotCore.Settings.defaults()));
                ids.add("node-" + (char) ('a' + i));
            }
            for (int i = 0; i < n; i++) {
                String self = ids.get(i);
                ClusterRoute.Peers peers = new ClusterRoute.Peers() {
                    @Override
                    public String self() {
                        return self;
                    }

                    @Override
                    public List<String> nodes() {
                        return ids;
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public Map<String, Object> ask(Viewer v, String node, Map<String, Object> request, long timeoutMs) throws Exception {
                        calls.incrementAndGet();
                        if (node.equals(dead)) {
                            throw new IllegalStateException(node + " is down");
                        }
                        if (node.equals(slow)) {
                            Thread.sleep(1_500);
                        }
                        Map<String, Object> req = JSON.readValue(JSON.writeValueAsString(request), Map.class);
                        PivotFixture there = nodes.get(ids.indexOf(node));
                        Map<String, Object> answer = ClusterRoute.answer(there.core, Map.of(), Viewer.of("rita"), req);
                        return JSON.readValue(JSON.writeValueAsString(answer), Map.class);
                    }
                };
                nodes.get(i).core.hooks(new ScalePolicy(1));                 // distinct(CPTY) by its sketch, so registers cross nodes
                nodes.get(i).core.hooks(new ClusterRoute(peers, new ClusterRoute.Settings(true, 300, 10_000, 0), Map.of(),
                        Executors.newVirtualThreadPerTaskExecutor()));
            }
        }

        Map<String, Object> ask() {
            return nodes.get(0).core.query(Viewer.of("rita"), PivotView.parse(VIEW));
        }

        @Override
        public void close() {
            nodes.forEach(PivotFixture::close);
        }
    }

    static void same(Map<String, Object> one, Map<String, Object> many) {
        Map<String, Map<String, Object>> a = PivotCoreTest.cells(one);
        Map<String, Map<String, Object>> b = PivotCoreTest.cells(many);
        assertThat(b.keySet()).isEqualTo(a.keySet());
        for (String k : a.keySet()) {
            assertThat(b.get(k).get("members")).as(k).isEqualTo(a.get(k).get("members"));
            for (String m : List.of("C", "D", "Mn")) {
                assertThat(PivotCoreTest.val(b.get(k), m)).as(k + " " + m).isEqualTo(PivotCoreTest.val(a.get(k), m));
            }
            for (String m : List.of("S", "A", "V")) {
                Double x = PivotCoreTest.val(a.get(k), m);
                Double y = PivotCoreTest.val(b.get(k), m);
                if (x == null) {
                    assertThat(y).isNull();
                } else {
                    assertThat(y).as(k + " " + m).isCloseTo(x, offset(Math.max(1e-9, Math.abs(x) * 1e-12)));
                }
            }
        }
    }

    @Test
    void aTwoNodePartialMergeEqualsOneNodeAndRepeatsToTheDigit() {
        Map<String, Object> one;
        try (PivotFixture alone = new PivotFixture(4_000, StateCache.none(), PivotCore.Settings.defaults())) {
            alone.core.hooks(new ScalePolicy(1));
            one = alone.core.query(Viewer.of("rita"), PivotView.parse(VIEW));
        }
        for (int n : new int[] {2, 3}) {
            try (Cluster c = new Cluster(n)) {
                Map<String, Object> first = c.ask();
                assertThat(String.valueOf(first.get("notes"))).contains("computed on " + n + " nodes");
                assertThat(c.calls.get()).as("every other node asked once per round of sets").isGreaterThanOrEqualTo(n - 1);
                same(one, first);
                Map<String, Object> second = c.ask();
                assertThat(PivotCoreTest.cells(second)).isEqualTo(PivotCoreTest.cells(first));
            }
        }
    }

    @Test
    void aLateNodeIsHedgedWithTheSameDigitsAndADeadNodeRefusesTheView() {
        Map<String, Object> calm;
        try (Cluster c = new Cluster(2)) {
            calm = c.ask();
            c.slow = "node-b";
            Map<String, Object> hedged = c.ask();
            assertThat(PivotCoreTest.cells(hedged)).isEqualTo(PivotCoreTest.cells(calm));
        }
        try (Cluster c = new Cluster(3)) {
            c.dead = "node-b";
            assertThat(c.ask()).isNotNull();                    // node-b's shard is answered by node-c (hedge to the next node)
        }
        PartialGather.Share s = new PartialGather.Share("shard 1 of 2", List.of("node-b"));
        assertThatThrownBy(() -> PartialGather.gather(List.of(s), (node, share) -> {
            throw new IllegalStateException("down");
        }, PartialGather.partials(), Executors.newVirtualThreadPerTaskExecutor(), 0, 2_000)).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.PIVOT_NODE_MISSING))
                .hasMessageContaining("no figure is shown");
    }

    @Test
    void theGatherCombinesInShareOrderWhateverOrderTheAnswersArriveIn() {
        List<PartialGather.Share> shares = List.of(new PartialGather.Share("0", List.of("a")), new PartialGather.Share("1", List.of("b")),
                new PartialGather.Share("2", List.of("c")));
        PartialGather.Combine<StringBuilder> concat = new PartialGather.Combine<>() {
            @Override
            public StringBuilder zero() {
                return new StringBuilder();
            }

            @Override
            public StringBuilder add(StringBuilder acc, StringBuilder part) {
                return acc.append(part);
            }
        };
        StringBuilder got = PartialGather.gather(shares, (node, share) -> {
            Thread.sleep("a".equals(node) ? 300 : "b".equals(node) ? 10 : 100);    // a answers last
            return new StringBuilder(node);
        }, concat, Executors.newVirtualThreadPerTaskExecutor(), 0, 5_000);
        assertThat(got.toString()).isEqualTo("abc");
    }
}
