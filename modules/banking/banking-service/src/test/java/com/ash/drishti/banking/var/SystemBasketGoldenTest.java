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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.banking.compute.var.ScopeResolver;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The golden test of the system baskets (VAR_GUIDE.md section 5.7) on the committed test data's demo date, 2026-09-30, in USD.
 *
 * <p>{@code var/system-baskets-golden-2026-09-30.json} was recorded from the 28 rows of the {@code portfolio} feed before the feed was
 * removed: per portfolio its definition (type, scope text), the trades it resolves to (count and a digest of their ids), its P&amp;L
 * vector (a digest of the float64 bits), its VaR and ES (exact doubles), its limit from {@code var-limit} (hierarchy PORTFOLIO) and the
 * limit use (read with each record's own currency, TRADE_CURRENCY when sent, as the server does: PF-EUR-ALL, PF-FX-CROSSES and PF-USD-RATES, rules
 * on CURRENCY, were recorded anew when the harness read the P&amp;L vectors' CURRENCY, USD, for Calypso and Murex; the test data now positions: members, digests, the page's trade counts and, for baskets that named trades or POSITION_RULE,
 * the definition re-keyed to the positions; PF-PARTIAL-FILLS holds whole positions now, so it was recorded anew; every other figure kept as
 * recorded and compared to a micro-dollar); and the figures of the page {@code PORT PF-RATES-DESK} shows: its positions with their trades in scope, standalone VaR,
 * component VaR, direction and market value, the counts of longs and shorts, the gross and net market value. The same figures computed
 * now, from the definitions as they are kept now (the market-risk pack's seed imported into the basket store's system scope, read back as
 * the date's portfolios with their var-limit limits), are equal field by field.
 */
class SystemBasketGoldenTest {

    static final String GOLDEN = "/var/system-baskets-golden-2026-09-30.json";
    static final String PAGE = "PF-RATES-DESK";
    static ParallelSums sums;
    static VarBook book;
    static Path repo;
    static Map<String, Double> limits;
    @TempDir
    static Path store;

    @BeforeAll
    static void load() throws IOException {
        repo = TestDataBook.root();
        sums = new ParallelSums(4, 4096);
        book = TestDataBook.build(repo, sums);
        limits = TestDataBook.limits(repo.resolve("data/testdata/market-risk"), "PORTFOLIO");
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    /** The definitions as the server keeps them now: the pack's seed imported into an empty system scope, read back on the date. */
    static Map<String, VarDate.Portfolio> definitions() throws IOException {
        VarSystemBaskets sb = new VarSystemBaskets(new FileContentStore(store.resolve("content")));
        Object seed = VarSystemBasketEdit.YAML.readValue(repo.resolve("modules/banking/dmod/packs/market-risk/seeds/system-baskets.yaml").toFile(),
                Object.class);
        assertThat(sb.seed(seed, "test", null)).as("seeded").isEqualTo(28);
        assertThat(sb.seed(seed, "test", null)).as("seeded once").isZero();
        assertThat(sb.skipped()).isEmpty();
        return new TreeMap<>(sb.portfolios(java.time.LocalDate.parse(TestDataBook.DATE), limits));
    }

    /** Every portfolio's figures, and the page's, as the golden file keeps them. */
    static Map<String, Object> describe(Map<String, VarDate.Portfolio> defs) {
        return describe(defs, book, sums);
    }

    /** The same over any book (the golden matrix: another date, window or currency). */
    static Map<String, Object> describe(Map<String, VarDate.Portfolio> defs, VarBook book, ParallelSums sums) {
        ScopeResolver resolver = new ScopeResolver(book, new ScopeResolver.Definitions() {
            @Override
            public Optional<String> portfolio(String id) {
                return Optional.ofNullable(defs.get(id)).map(VarDate.Portfolio::scope);
            }

            @Override
            public Optional<String> basket(String name) {
                return Optional.empty();
            }
        }, Map.of("LL", "LL"));
        VarCalc calc = VarCalc.defaults();
        Map<String, Object> all = new TreeMap<>();
        for (VarDate.Portfolio pf : defs.values()) {
            ScopeResolver.Resolution r = resolver.resolve("PORT:" + pf.id());
            double[] v = book.vector(r.set(), sums);
            VarCalc.Measures ms = calc.measures(v);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", pf.name());
            m.put("type", pf.type());
            m.put("owner", pf.owner());
            m.put("scope", pf.scope());
            List<String> trades = new ArrayList<>();
            r.set().forEach(o -> trades.add(book.tradeId(o)));
            trades.sort(null);
            m.put("members", trades.size());
            m.put("membersDigest", sha(String.join("\n", trades).getBytes(StandardCharsets.UTF_8)));
            m.put("aggregateDigest", bits(v));
            m.put("unknown", r.unknown());
            m.put("var", ms.var());
            m.put("es", ms.es());
            m.put("limit", Double.isNaN(pf.limit()) ? null : pf.limit());
            m.put("limitUse", Double.isNaN(pf.limit()) || pf.limit() == 0 ? null : ms.var() / pf.limit());
            if (pf.id().equals(PAGE)) {
                m.put("page", page(book, sums, calc, r.set(), v, ms));
            }
            all.put(pf.id(), m);
        }
        return all;
    }

    /** The page's figures: its positions (trades in scope, VaR on its own, component VaR, direction, market value) and the strip's counts. */
    private static Map<String, Object> page(VarBook book, ParallelSums sums, VarCalc calc, OrdinalSet set, double[] total, VarCalc.Measures ms) {
        List<VarCalc.Part> parts = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (int p = 0; p < book.positionCount(); p++) {
            OrdinalSet in = book.positionMembers(p).and(set);
            if (!in.isEmpty()) {
                parts.add(new VarCalc.Part(book.positionId(p), book.positionId(p), "position", book.vector(in, sums), in.size()));
                idx.add(p);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long longs = 0;
        long shorts = 0;
        double gross = 0;
        double net = 0;
        List<VarCalc.Contribution> cs = calc.contributions(total, ms, parts);
        for (int i = 0; i < cs.size(); i++) {
            int p = idx.get(i);
            String dir = book.positions().text("DIRECTION", p);
            double mv = book.positions().number("MARKET_VALUE", p);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("position", cs.get(i).key());
            x.put("trades", (int) parts.get(i).size());
            x.put("var", cs.get(i).standaloneVar());
            x.put("componentVar", cs.get(i).componentVar());
            x.put("direction", dir);
            x.put("marketValue", Double.isNaN(mv) ? null : mv);
            rows.add(x);
            longs += "LONG".equals(dir) ? 1 : 0;
            shorts += "SHORT".equals(dir) ? 1 : 0;
            gross += Double.isNaN(mv) ? 0 : Math.abs(mv);
            net += Double.isNaN(mv) ? 0 : mv;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("positionCount", rows.size());
        m.put("longCount", longs);
        m.put("shortCount", shorts);
        m.put("grossMv", gross);
        m.put("netMv", net);
        m.put("positions", rows);
        return m;
    }

    @Test
    void theSystemBasketsAnswerAsThePortfoliosDid() throws IOException {
        Map<String, Object> have = describe(definitions());
        JsonNode got = TestDataBook.JSON.readTree(TestDataBook.JSON.writeValueAsString(have));   // numbers typed as a file reads them
        String record = System.getenv("DRISHTI_GOLDEN_RECORD");        // set once, to record the golden file
        if (record != null && !record.isBlank()) {
            Path out = Path.of(record);
            Files.createDirectories(out.getParent());
            Files.writeString(out, TestDataBook.JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(have) + "\n");
            return;
        }
        JsonNode want;
        try (InputStream in = SystemBasketGoldenTest.class.getResourceAsStream(GOLDEN)) {
            want = TestDataBook.JSON.readTree(in);
        }
        assertThat(want.size()).as("the demo's system baskets").isEqualTo(28);
        List<String> ids = new ArrayList<>();
        want.fieldNames().forEachRemaining(ids::add);
        List<String> gotIds = new ArrayList<>();
        got.fieldNames().forEachRemaining(gotIds::add);
        assertThat(gotIds).as("system basket ids").containsExactlyElementsOf(ids);
        for (String id : ids) {
            JsonNode exp = want.get(id);
            JsonNode g = got.get(id);
            exp.fieldNames().forEachRemaining(f -> {
                if (List.of("var", "es", "limitUse", "page").contains(f)) {
                    assertClose(id + " " + f, exp.get(f), g.get(f));
                } else {
                    assertThat(g.get(f)).as(id + " " + f).isEqualTo(exp.get(f));             // definition, members, limit: exactly
                }
            });
            assertThat(g.size()).as(id + " fields").isEqualTo(exp.size());
        }
        assertThat(want.get(PAGE).get("page").get("positionCount").asInt()).as("the page's positions").isPositive();
    }

    private static String bits(double[] v) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 * v.length);
        for (double d : v) {
            b.putLong(Double.doubleToRawLongBits(d));
        }
        return sha(b.array());
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * VaR, ES, limit use and the page's figures compared to a micro-dollar (1e-6 USD; the largest difference seen is 6e-8): a position's record
     * and the sum of its trades add the same numbers in another order, which moves the last bit of a double. Everything else is compared exactly.
     */
    static void assertClose(String what, JsonNode want, JsonNode got) {
        if (want.isNumber() && got.isNumber()) {
            assertThat(got.asDouble()).as(what).isCloseTo(want.asDouble(), org.assertj.core.api.Assertions.within(1e-6));
        } else if (want.isContainerNode() && got != null && got.getNodeType() == want.getNodeType()) {
            assertThat(got.size()).as(what + " size").isEqualTo(want.size());
            if (want.isArray()) {
                for (int i = 0; i < want.size(); i++) {
                    assertClose(what + "[" + i + "]", want.get(i), got.get(i));
                }
            } else {
                want.fieldNames().forEachRemaining(k -> assertClose(what + "." + k, want.get(k), got.get(k)));
            }
        } else {
            assertThat(got).as(what).isEqualTo(want);
        }
    }
}
