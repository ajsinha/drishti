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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A date cut into shards and put together again (VAR_SHARDING.md section 5) answers as the whole date: the synthetic bank (2 legal
 * entities, 6 desks, 24 books, 1,800 trades) written whole to one hot file, and as six shards (legal entity and desk ranges) each built
 * from its own trades only. Three shards are held "here" (their mapped rows), three are reached only through their skeleton and an "owner"
 * (a {@link ShardedVectors.Remote} summing that shard's book). Same trades, positions and nodes in the same order; every node's aggregate
 * to the bit; every scope's vector and VaR equal; one partial per shard asked; a damaged skeleton refused; a book under two desks that
 * fall in two shards refused (never answered differently).
 */
class ShardAssemblyTest {

    static final SyntheticBank BANK = new SyntheticBank(2, 3, 4, 600, 3);
    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static ParallelSums sums;
    static VarBook whole;
    static ShardMap map;
    static final List<VarBook> shardBooks = new ArrayList<>();
    static VarBook assembled;
    static final Map<Integer, AtomicInteger> asked = new ConcurrentHashMap<>();
    @TempDir
    static Path dir;

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
        // 1,800 trades over 6 desks (300 each): a target of 1.5 desks' rows cuts six shards, one desk each (a desk is never split)
        map = ShardMap.cut(DAY, "v1", ShardMap.ORDER, counts, SyntheticBank.WIDTH, 450L * SyntheticBank.WIDTH * 4);
        List<ShardAssembly.Part> parts = new ArrayList<>();
        for (ShardMap.Shard s : map.shards()) {
            Path sd = Files.createDirectories(dir.resolve("shard-" + s.index()));
            int index = s.index();
            VarBook b = BANK.fill(new VarBook.Builder(DAY, "HS-509", SyntheticBank.dates(), Long.MAX_VALUE,
                    SyntheticBank.target(sd, "shard.vhot", "shard-" + index)).currencyRates(c -> null).range("{\"shard\":" + index + "}"), 42,
                    t -> map.shardOf(List.of(t[1], t[3])) == index).build(sums);
            shardBooks.add(b);
            if (index % 2 == 0) {
                parts.add(ShardAssembly.Part.of(index, b));                    // held here: its mapped rows
            } else {
                Path sk = sd.resolve("skeleton.vskel");
                try (OutputStream out = Files.newOutputStream(sk)) {
                    ShardSkeleton.write(b.hot().file(), out);
                }
                parts.add(ShardSkeleton.read(sk, index));                       // on an owner: its skeleton only
            }
        }
        ShardedVectors.Remote owner = (shard, set) -> {
            asked.computeIfAbsent(shard, k -> new AtomicInteger()).incrementAndGet();
            return sums.sum(shardBooks.get(shard).store(), set);
        };
        assembled = ShardAssembly.assemble(DAY, parts, ShardAssembly.Nodes.of(whole, 1.0), owner, 1);
    }

    @AfterAll
    static void close() {
        whole.close();
        shardBooks.forEach(VarBook::close);
        sums.close();
    }

    @Test
    void theMapCutsSixShardsAlongDesks() {
        assertThat(map.size()).isEqualTo(6);
        for (ShardMap.Shard s : map.shards()) {
            assertThat(s.rows()).isPositive();
        }
        assertThat(shardBooks.stream().mapToInt(VarBook::tradeCount).sum()).isEqualTo(whole.tradeCount());
        assertThat(shardBooks.get(1).hot().range()).isEqualTo("{\"shard\":1}");
        assertThat(assembled.tier()).isEqualTo("sharded");
        assertThat(assembled.sharded()).isTrue();
    }

    @Test
    void theAssembledDateHasTheWholeDatesTradesPositionsAndNodesToTheBit() {
        assertThat(assembled.tradeCount()).isEqualTo(whole.tradeCount());
        assertThat(assembled.positionCount()).isEqualTo(whole.positionCount());
        for (int o = 0; o < whole.tradeCount(); o++) {
            assertThat(assembled.tradeId(o)).isEqualTo(whole.tradeId(o));
            assertThat(assembled.positionOf(o)).isEqualTo(whole.positionOf(o));
            for (String c : VarBook.TRADE_TEXTS) {
                assertThat(assembled.trades().text(c, o)).isEqualTo(whole.trades().text(c, o));
            }
        }
        for (int p = 0; p < whole.positionCount(); p++) {
            assertThat(assembled.positionId(p)).isEqualTo(whole.positionId(p));
            for (String c : VarBook.POSITION_TEXTS) {
                assertThat(assembled.positions().text(c, p)).isEqualTo(whole.positions().text(c, p));
            }
        }
        assertThat(assembled.nodes().keySet()).containsExactlyElementsOf(whole.nodes().keySet());
        for (VarBook.Node n : whole.nodes().values()) {
            assertThat(assembled.node(n.id()).members()).isEqualTo(n.members());
            assertThat(assembled.node(n.id()).children()).isEqualTo(n.children());
            assertThat(assembled.aggregate(n.id())).as(n.id()).containsExactly(whole.aggregate(n.id()));
        }
        for (String node : List.of("MGMT~BANK", "LEGAL~LE-0", "MGMT~DESK-1-2")) {
            CurrencyTotals.Sums a = assembled.currencyTotals().get(node);
            CurrencyTotals.Sums w = whole.currencyTotals().get(node);
            assertThat(a.currencies()).containsExactly(w.currencies());
            assertThat(a.trades()).containsExactly(w.trades());
            for (int i = 0; i < w.pv().length; i++) {
                for (int j = 0; j < SyntheticBank.WIDTH; j++) {
                    assertThat(a.pv()[i][j]).isCloseTo(w.pv()[i][j], org.assertj.core.data.Offset.offset(1e-6));
                }
            }
        }
    }

    @Test
    void everyScopeSumsToTheWholeDatesVectorWithOnePartialPerShard() {
        VarCalc calc = new VarCalc(0.99, 0.975, VarMath.RankRule.KTH_WORST, 5, true);
        SplittableRandom r = new SplittableRandom(7);
        List<OrdinalSet> scopes = new ArrayList<>();
        scopes.add(whole.all());
        scopes.add(whole.node("MGMT~DESK-0-1").members().andNot(OrdinalSet.of(whole.bookRange("BOOK-0-1-0")[0])));    // a desk minus a trade
        scopes.add(whole.node("LEGAL~LE-1").members().or(OrdinalSet.range(10, 40)));                                   // an entity plus some
        for (int k = 0; k < 20; k++) {
            OrdinalSet.Builder b = new OrdinalSet.Builder();
            for (int i = 0; i < 1 + r.nextInt(400); i++) {
                b.add(r.nextInt(whole.tradeCount()));
            }
            scopes.add(b.build());
        }
        for (OrdinalSet s : scopes) {
            asked.clear();
            double[] a = assembled.vector(s, sums);
            double[] w = whole.vector(s, sums);
            assertThat(a).containsExactly(w);                                  // whole-unit rows: every sum exact
            assertThat(calc.measures(a).var()).isEqualTo(calc.measures(w).var());
            asked.values().forEach(n -> assertThat(n.get()).isLessThanOrEqualTo(2));   // the plan's plus and minus sets: once each at most
        }
        for (int p = 0; p < whole.positionCount(); p += 53) {
            assertThat(assembled.positionVector(p)).containsExactly(whole.positionVector(p));
        }
        for (int o = 0; o < whole.tradeCount(); o += 97) {
            assertThat(assembled.tradeVector(o)).containsExactly(whole.tradeVector(o));
        }
    }

    @Test
    void aShardWithNoOwnerFailsTheWholeSumNeverAPartialOne() {
        List<ShardAssembly.Part> parts = new ArrayList<>();
        for (int i = 0; i < shardBooks.size(); i++) {
            parts.add(ShardAssembly.Part.of(i, shardBooks.get(i)).withoutRows());
        }
        VarBook none = ShardAssembly.assemble(DAY, parts, ShardAssembly.Nodes.of(whole, 1.0), (shard, set) -> {
            throw new IllegalStateException("shard " + shard + " has no live owner");
        }, 1);
        assertThat(none.aggregate("MGMT~BANK")).containsExactly(whole.aggregate("MGMT~BANK"));   // a node: its aggregate, here
        assertThatThrownBy(() -> none.vector(OrdinalSet.range(0, 50), sums)).hasMessageContaining("has no live owner");
    }

    @Test
    void aDamagedSkeletonIsRefused() throws IOException {
        Path sk = dir.resolve("damaged.vskel");
        try (OutputStream out = Files.newOutputStream(sk)) {
            ShardSkeleton.write(shardBooks.get(1).hot().file(), out);
        }
        byte[] b = Files.readAllBytes(sk);
        b[b.length - 1] ^= 0x5a;
        Files.write(sk, b);
        assertThatThrownBy(() -> ShardSkeleton.read(sk, 1)).hasMessageContaining("damaged");
        assertThat(Files.size(sk)).isLessThan(Files.size(shardBooks.get(1).hot().file()));
    }

    @Test
    void aBookUnderTwoShardsIsRefused() throws IOException {
        // BOOK-0-0-0 also booked under DESK-1-0 (another legal entity): its trades fall in two shards
        Path a = Files.createDirectories(dir.resolve("bad-a"));
        Path b = Files.createDirectories(dir.resolve("bad-b"));
        java.util.function.Predicate<String[]> moved = t -> {
            if (t[4].equals("BOOK-1-0-0")) {
                t[4] = "BOOK-0-0-0";
            }
            return true;
        };
        VarBook first = BANK.fill(new VarBook.Builder(DAY, "HS-509", SyntheticBank.dates(), Long.MAX_VALUE, SyntheticBank.target(a, "a.vhot", "a")),
                42, t -> moved.test(t) && t[1].equals("LE-0")).build(sums);
        VarBook second = BANK.fill(new VarBook.Builder(DAY, "HS-509", SyntheticBank.dates(), Long.MAX_VALUE, SyntheticBank.target(b, "b.vhot", "b")),
                42, t -> moved.test(t) && t[1].equals("LE-1")).build(sums);
        try {
            assertThatThrownBy(() -> ShardAssembly.assemble(DAY, List.of(ShardAssembly.Part.of(0, first), ShardAssembly.Part.of(1, second)),
                    ShardAssembly.Nodes.of(whole, 1.0), (s, x) -> null, 1)).isInstanceOf(ShardAssembly.NotShardable.class)
                    .hasMessageContaining("BOOK-0-0-0");
        } finally {
            first.close();
            second.close();
        }
    }
}
