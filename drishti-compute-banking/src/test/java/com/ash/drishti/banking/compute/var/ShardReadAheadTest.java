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
package com.ash.drishti.banking.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.compute.shard.ShardMap;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.compute.vector.ShardedVectors;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A contribution table's reads fetched ahead (VAR_SHARDING.md section 15): {@link VarBook#readAhead} asks each remote shard once for all
 * the positions and trades of a table, and every vector equals, to the bit, the one read position by position and trade by trade (one
 * remote call each). The synthetic bank (2 legal entities, 6 desks, 1,800 trades) cut into six shards, put together as one member holding
 * every shard and as each of three members holding two shards (the other four reached through an owner): whole positions, parts of
 * positions and trades, in USD-like float64, compared with {@code containsExactly}; the calls counted.
 */
class ShardReadAheadTest {

    static final SyntheticBank BANK = new SyntheticBank(2, 3, 4, 600, 3);
    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static ParallelSums sums;
    static VarBook whole;
    static final List<VarBook> shardBooks = new ArrayList<>();
    static final List<ShardAssembly.Part> skeletons = new ArrayList<>();
    @TempDir
    static Path dir;

    /** An owner that sums a shard's rows from its book and counts the calls: one-set calls and batched calls apart. */
    static final class Owner implements ShardedVectors.Remote {
        final AtomicInteger single = new AtomicInteger();
        final Map<Integer, AtomicInteger> batches = new ConcurrentHashMap<>();
        final AtomicInteger sets = new AtomicInteger();

        @Override
        public double[] sum(int shard, OrdinalSet rows, Object context) {
            single.incrementAndGet();
            return sums.sum(shardBooks.get(shard).store(), rows);
        }

        @Override
        public List<double[]> sumAll(int shard, List<OrdinalSet> rows, Object context) {
            batches.computeIfAbsent(shard, k -> new AtomicInteger()).incrementAndGet();
            sets.addAndGet(rows.size());
            List<double[]> out = new ArrayList<>();
            for (OrdinalSet r : rows) {
                out.add(sums.sum(shardBooks.get(shard).store(), r));
            }
            return out;
        }

        int batchCalls() {
            return batches.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    @BeforeAll
    static void build() throws IOException {
        sums = new ParallelSums(4, 64);
        Path wd = Files.createDirectories(dir.resolve("whole"));
        whole = BANK.fill(new VarBook.Builder(DAY, "HS-509", SyntheticBank.dates(), Long.MAX_VALUE,
                SyntheticBank.target(wd, "whole.vhot", "whole")).currencyRates(c -> null), 42).build(sums);
        SortedMap<List<String>, Long> counts = ShardMap.counts();
        for (int o = 0; o < whole.tradeCount(); o++) {
            counts.merge(List.of(whole.trades().text("LEGAL_ENTITY", o), whole.trades().text("DESK", o)), 1L, Long::sum);
        }
        ShardMap map = ShardMap.cut(DAY, "v1", List.of("LEGAL_ENTITY", "DESK"), counts, SyntheticBank.WIDTH * 4L, 450L * SyntheticBank.WIDTH * 4);
        for (ShardMap.Shard s : map.shards()) {
            Path sd = Files.createDirectories(dir.resolve("shard-" + s.index()));
            int index = s.index();
            VarBook b = BANK.fill(new VarBook.Builder(DAY, "HS-509", SyntheticBank.dates(), Long.MAX_VALUE,
                    SyntheticBank.target(sd, "shard.vhot", "shard-" + index)).currencyRates(c -> null).range("{\"shard\":" + index + "}"), 42,
                    t -> map.shardOf(List.of(t[1], t[3])) == index).build(sums);
            shardBooks.add(b);
            Path sk = sd.resolve("skeleton.vskel");
            try (OutputStream out = Files.newOutputStream(sk)) {
                ShardSkeleton.write(b.hot().file(), out);
            }
            skeletons.add(ShardSkeleton.read(sk, index));
        }
    }

    @AfterAll
    static void close() {
        whole.close();
        shardBooks.forEach(VarBook::close);
        sums.close();
    }

    /** The date put together on a member holding the shards {@code held} accepts; the others through {@code owner}. */
    static VarBook member(IntPredicate held, ShardedVectors.Remote owner) {
        List<ShardAssembly.Part> parts = new ArrayList<>();
        for (int i = 0; i < shardBooks.size(); i++) {
            parts.add(held.test(i) ? ShardAssembly.Part.of(i, shardBooks.get(i)) : skeletons.get(i));
        }
        return ShardAssembly.assemble(DAY, parts, ShardAssembly.Nodes.of(whole, 1.0), owner, 1);
    }

    /** Every position's part of {@code set} and every trade of it: read one by one ({@code ahead} false) or fetched ahead. */
    static Map<String, double[]> table(VarBook b, OrdinalSet set, boolean ahead) {
        List<Integer> ps = new ArrayList<>();
        for (int p = 0; p < b.positionCount(); p++) {
            if (b.positionMemberCountIn(p, set) > 0) {
                ps.add(p);
            }
        }
        int[] positions = ps.stream().mapToInt(Integer::intValue).toArray();
        List<Integer> ts = new ArrayList<>();
        set.forEach(ts::add);
        int[] trades = ts.stream().mapToInt(Integer::intValue).toArray();
        java.util.function.Supplier<Map<String, double[]>> work = () -> {
            Map<String, double[]> out = new TreeMap<>();
            for (int p : positions) {
                double[] v;
                if (b.positionWithin(p, set)) {                         // as VarViews.position reads a whole position and a part of one
                    v = b.positionVector(p);
                } else {
                    double[] acc = new double[b.width()];
                    b.forEachPositionMemberIn(p, set, o -> b.store().addInto(acc, o));
                    v = acc;
                }
                out.put("P" + p, v);
            }
            for (int o : trades) {
                out.put("T" + o, b.tradeVector(o));
            }
            return out;
        };
        return ahead ? b.readAhead(set, positions, trades, work) : work.get();
    }

    static void sameToTheBit(Map<String, double[]> a, Map<String, double[]> b) {
        assertThat(a.keySet()).isEqualTo(b.keySet());
        a.forEach((k, v) -> {
            double[] w = b.get(k);
            assertThat(v).hasSize(w.length);
            for (int j = 0; j < v.length; j++) {
                assertThat(Double.doubleToRawLongBits(v[j])).as(k + "[" + j + "]").isEqualTo(Double.doubleToRawLongBits(w[j]));
            }
        });
    }

    /** The bank's trades without every third: most positions are then in part. */
    static OrdinalSet partly() {
        OrdinalSet.Builder s = new OrdinalSet.Builder();
        for (int o = 0; o < whole.tradeCount(); o++) {
            if (o % 3 != 1) {
                s.add(o);
            }
        }
        return s.build();
    }

    @Test
    void onOneMemberHoldingEveryShardNothingIsAskedAndTheVectorsAreTheSame() {
        Owner oneByOne = new Owner();
        Owner ahead = new Owner();
        for (OrdinalSet set : List.of(whole.all(), partly())) {
            Map<String, double[]> a = table(member(i -> true, oneByOne), set, false);
            Map<String, double[]> b = table(member(i -> true, ahead), set, true);
            sameToTheBit(b, a);
        }
        assertThat(oneByOne.single.get() + ahead.single.get() + ahead.batchCalls()).isZero();
    }

    @Test
    void onEachOfThreeMembersTheTableFetchedAheadIsTheOneReadOneByOneToTheBit() {
        for (int m = 0; m < 3; m++) {
            int me = m;
            for (OrdinalSet set : List.of(whole.all(), partly(), whole.node("MGMT~DESK-1-2").members())) {
                Owner oneByOne = new Owner();
                Owner ahead = new Owner();
                Map<String, double[]> a = table(member(i -> i % 3 == me, oneByOne), set, false);
                Map<String, double[]> b = table(member(i -> i % 3 == me, ahead), set, true);
                sameToTheBit(b, a);
                if (set.size() < whole.tradeCount() / 2 && oneByOne.single.get() == 0) {
                    assertThat(ahead.batchCalls()).as("a desk held here: nothing asked").isZero();
                    continue;
                }
                assertThat(oneByOne.single.get()).as("one by one: a call per remote read").isGreaterThan(100);
                assertThat(ahead.single.get()).as("ahead: no read left to ask one by one").isZero();
                assertThat(ahead.sets.get()).as("ahead: the same reads, each once (a trade read as itself and as a part of its position: once)")
                        .isPositive().isLessThanOrEqualTo(oneByOne.single.get());
                assertThat(ahead.batchCalls()).as("ahead: one call per remote shard").isLessThanOrEqualTo(4);
            }
        }
        // the whole positions also equal the single date's (rows summed in the same order on the owner and here)
        Map<String, double[]> w = table(whole, whole.all(), false);
        Map<String, double[]> s = table(member(i -> i % 3 == 0, new Owner()), whole.all(), true);
        sameToTheBit(s, w);
    }

    @Test
    void aDeskWithManyPositionsMakesOneCallPerRemoteShard() {
        Owner ahead = new Owner();
        VarBook b = member(i -> i % 3 == 0, ahead);                  // shards 0 and 3 here; 1, 2, 4 and 5 on owners
        OrdinalSet bank = whole.all();
        int[] positions = java.util.stream.IntStream.range(0, b.positionCount()).toArray();
        assertThat(positions.length).as("positions in the table").isGreaterThan(300);
        b.readAhead(bank, positions, new int[0], () -> {
            for (int p : positions) {
                b.positionVector(p);
            }
            return null;
        });
        assertThat(ahead.batches.keySet()).containsExactlyInAnyOrder(1, 2, 4, 5);
        ahead.batches.forEach((shard, n) -> assertThat(n.get()).as("calls to S" + shard).isEqualTo(1));
        assertThat(ahead.single.get()).isZero();
        assertThat(ahead.sets.get()).as("one set per remote position").isLessThanOrEqualTo(positions.length);

        Owner desk = new Owner();                                      // a desk of one remote shard: one call in all
        VarBook c = member(i -> i % 3 == 0, desk);
        OrdinalSet d = whole.node("MGMT~DESK-0-1").members();
        int[] dp = java.util.stream.IntStream.range(0, c.positionCount()).filter(p -> c.positionMemberCountIn(p, d) > 0).toArray();
        c.readAhead(d, dp, new int[0], () -> null);
        assertThat(desk.batchCalls()).as("the desk's " + dp.length + " positions").isEqualTo(1);
        assertThat(desk.single.get()).isZero();
    }

    @Test
    void moreThanABatchOfReadsIsSplitIntoCallsOfABatchAtMost() {
        Owner ahead = new Owner();
        VarBook b = member(i -> i == 0, ahead);
        OrdinalSet bank = whole.all();
        int[] trades = java.util.stream.IntStream.range(0, b.tradeCount()).toArray();
        b.readAhead(bank, new int[0], trades, () -> null);
        int remote = b.tradeCount() - shardBooks.get(0).tradeCount();
        assertThat(ahead.sets.get()).isEqualTo(remote);
        int expected = 0;
        for (int i = 1; i < shardBooks.size(); i++) {
            expected += (shardBooks.get(i).tradeCount() + ShardedVectors.BATCH - 1) / ShardedVectors.BATCH;
        }
        assertThat(ahead.batchCalls()).isEqualTo(expected);
    }

    /**
     * A cube view's cells (MR_CUBE_LENS.md section 4.11): many sets at once through {@link VarBook#vectors}, each equal to the bit to the
     * same set's {@link VarBook#vector} (the planner's aggregates, then the rows it adds and takes away), with each remote shard asked once
     * for all of them instead of once per set.
     */
    @Test
    void aCubesSetsAreSummedWithOneCallPerRemoteShardAndEachIsTheSingleSetsVectorToTheBit() {
        List<OrdinalSet> sets = new ArrayList<>();
        for (VarBook.Node n : whole.nodes().values()) {
            if (n.level().equalsIgnoreCase("DESK")) {
                sets.add(n.members());
                sets.add(n.members().and(partly()));                   // a desk without every third trade: a plan of aggregates minus rows
            }
        }
        sets.add(whole.all());
        sets.add(partly());
        sets.add(OrdinalSet.empty());
        Owner one = new Owner();
        VarBook single = member(i -> i % 3 == 0, one);
        List<double[]> each = new ArrayList<>();
        for (OrdinalSet s : sets) {
            each.add(single.vector(s, sums));
        }
        int perSet = one.single.get();
        Owner batched = new Owner();
        List<double[]> all = member(i -> i % 3 == 0, batched).vectors(sets, sums);
        assertThat(all).hasSize(sets.size());
        for (int i = 0; i < sets.size(); i++) {
            double[] a = each.get(i);
            double[] b = all.get(i);
            for (int j = 0; j < a.length; j++) {
                assertThat(Double.doubleToRawLongBits(b[j] + 0.0)).as("set " + i + "[" + j + "]").isEqualTo(Double.doubleToRawLongBits(a[j] + 0.0));
            }
        }
        assertThat(perSet).as("one set at a time: a call per set and remote shard it touches").isGreaterThan(batched.batchCalls());
        assertThat(batched.single.get()).as("batched: nothing asked one set at a time").isZero();
        assertThat(batched.batches.keySet()).containsExactlyInAnyOrder(1, 2, 4, 5);
        batched.batches.forEach((shard, n) -> assertThat(n.get()).as("calls to S" + shard).isEqualTo(1));
    }

    @Test
    void aShardNobodyAnswersFailsTheWholeTable() {
        ShardedVectors.Remote none = (shard, set, ctx) -> {
            throw new IllegalStateException("shard " + shard + " has no live owner");
        };
        VarBook b = member(i -> i == 0, none);
        int[] positions = java.util.stream.IntStream.range(0, b.positionCount()).toArray();
        assertThatThrownBy(() -> b.readAhead(whole.all(), positions, new int[0], () -> "never")).hasMessageContaining("has no live owner");
    }

    @Test
    void theSkeletonWrittenThroughTheStreamIsTheSameBytesWhateverTheStreamTakesAtATime() throws IOException {
        Path a = dir.resolve("a.vskel");
        try (OutputStream out = Files.newOutputStream(a)) {
            ShardSkeleton.write(shardBooks.get(2).hot().file(), out);
        }
        java.io.ByteArrayOutputStream slow = new java.io.ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] b, int off, int len) {
                for (int i = 0; i < len; i += 7) {                    // a reader taking a few bytes at a time
                    super.write(b, off + i, Math.min(7, len - i));
                }
            }
        };
        long n = ShardSkeleton.write(shardBooks.get(2).hot().file(), slow);
        assertThat(slow.toByteArray()).isEqualTo(Files.readAllBytes(a));
        assertThat(n).isEqualTo(Files.size(a));
        assertThat(ShardSkeleton.read(a, 2).tradeIds()).isEqualTo(skeletons.get(2).tradeIds());
    }
}
