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

import com.ash.drishti.banking.compute.var.CurrencyBook;
import com.ash.drishti.banking.compute.var.ScenarioFx;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.identity.content.FileContentStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The VaR proof of a patch, in process (docs/architecture/TEST_HARNESS.md, "The VaR proof"): the committed test data on all three demo dates, the
 * 509 and 250 scenario windows, USD and EUR, every hierarchy node (tree, members, VaR, ES, worst scenarios, component VaR of its children, limit)
 * and all 28 system baskets (members, VaR, ES, limit, limit use, the page's figures). Compared with
 * {@code var/golden-matrix.json.gz}: VaR, ES, component VaR and limit use to 1e-6; trees, members, limits and worst scenarios exactly.
 *
 * <p>The server-against-server comparison over the API runs for releases only. To record the file again after a change that means to move the
 * numbers: {@code DRISHTI_GOLDEN_RECORD=<file> ./mvnw -o -pl drishti-banking -am test -Dtest=VarGoldenMatrixTest -Dsurefire.failIfNoSpecifiedTests=false}.
 */
class VarGoldenMatrixTest {

    static final String GOLDEN = "/var/golden-matrix.json.gz";
    static final List<String> DATES = List.of("2026-09-28", "2026-09-29", "2026-09-30");
    /** The fields compared to 1e-6; every other field exactly. */
    static final Set<String> CLOSE = Set.of("var", "es", "components", "limitUse", "page");

    @TempDir
    static Path store;

    @Test
    void everyDateWindowCurrencyHierarchyAndBasketAsRecorded() throws IOException {
        Map<String, Object> have = new TreeMap<>();
        Path repo = TestDataBook.root();
        Object seed = VarSystemBasketEdit.YAML.readValue(repo.resolve("modules/banking/dmod/packs/market-risk/seeds/system-baskets.yaml").toFile(), Object.class);
        VarSystemBaskets baskets = new VarSystemBaskets(new FileContentStore(store.resolve("content")));
        assertThat(baskets.seed(seed, "test", null)).as("seeded").isEqualTo(28);
        try (ParallelSums sums = new ParallelSums(4, 4096)) {
            for (String date : DATES) {
                Map<String, Double> limits = TestDataBook.limits(repo.resolve("data/testdata/market-risk"), "PORTFOLIO", date);
                Map<String, VarDate.Portfolio> defs = new TreeMap<>(baskets.portfolios(LocalDate.parse(date), limits));
                assertThat(defs).as(date + " baskets").hasSize(28);
                for (Map.Entry<Integer, TestDataBook.Book> e : TestDataBook.books(repo, sums, date, 509, 250).entrySet()) {
                    int window = e.getKey();
                    TestDataBook.Book b = e.getValue();
                    have.put(date + "/" + window + "/USD", describeCase(b.usd(), defs, sums, true));
                    ScenarioFx.Rates eur = b.fx().rates("EUR").orElseThrow();
                    VarBook inEur = CurrencyBook.convert(b.usd(), eur,
                            new VarBook.Builder(LocalDate.parse(date), "HS-" + window, b.scenarios(), 65_536, Long.MAX_VALUE), sums).book();
                    have.put(date + "/" + window + "/EUR", describeCase(inEur, defs, sums, false));
                }
            }
        }
        JsonNode got = TestDataBook.JSON.readTree(TestDataBook.JSON.writeValueAsString(have));
        String record = System.getenv("DRISHTI_GOLDEN_RECORD");
        if (record != null && !record.isBlank()) {
            Path out = Path.of(record);
            Files.createDirectories(out.getParent());
            try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(out))) {
                o.write((TestDataBook.JSON.writeValueAsString(have) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        JsonNode want;
        try (InputStream in = new GZIPInputStream(VarGoldenMatrixTest.class.getResourceAsStream(GOLDEN))) {
            want = TestDataBook.JSON.readTree(in);
        }
        assertThat(want.size()).as("three dates x two windows x two currencies").isEqualTo(12);
        want.fieldNames().forEachRemaining(c -> compareCase(c, want.get(c), got.get(c)));
        assertThat(got.size()).as("cases").isEqualTo(want.size());
    }

    private static Map<String, Object> describeCase(VarBook book, Map<String, VarDate.Portfolio> defs, ParallelSums sums, boolean usd) {
        Map<String, Object> baskets = SystemBasketGoldenTest.describe(defs, book, sums);
        for (Object o : baskets.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            m.remove("aggregateDigest");                                // the summation order moves the last bit: VaR and ES carry the proof
            if (!usd) {
                m.remove("limitUse");                                   // limits are in USD
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes(book));
        out.put("baskets", baskets);
        return out;
    }

    /** Every hierarchy node of the book as the golden file keeps it. */
    static Map<String, Object> nodes(VarBook b) {
        VarCalc calc = VarCalc.defaults();
        Map<String, Object> out = new TreeMap<>();
        for (VarBook.Node x : b.nodes().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hierarchy", x.hierarchy());
            m.put("parent", x.parent());
            m.put("level", x.level());
            TreeSet<String> children = new TreeSet<>(x.children());
            m.put("children", new ArrayList<>(children));
            List<String> trades = new ArrayList<>();
            x.members().forEach(o -> trades.add(b.tradeId(o)));
            trades.sort(null);
            m.put("members", trades.size());
            m.put("membersDigest", sha(String.join("\n", trades)));
            double[] v = b.aggregate(x.id());
            VarCalc.Measures ms = calc.measures(v);
            m.put("var", ms.var());
            m.put("es", ms.es());
            List<Integer> worst = new ArrayList<>();
            for (int i = 0; i < Math.min(10, ms.order().length); i++) {
                worst.add(ms.order()[i]);
            }
            m.put("worst", worst);
            if (!children.isEmpty()) {
                List<VarCalc.Part> parts = new ArrayList<>();
                for (String c : children) {
                    parts.add(new VarCalc.Part(c, c, "node", b.aggregate(c), b.node(c).members().size()));
                }
                Map<String, Double> comp = new TreeMap<>();
                calc.contributions(v, ms, parts).forEach(c -> comp.put(c.key(), c.componentVar()));
                m.put("components", comp);
            }
            m.put("limit", Double.isNaN(x.limit()) ? null : x.limit());
            out.put(x.id(), m);
        }
        return out;
    }

    private static void compareCase(String c, JsonNode want, JsonNode got) {
        assertThat(got).as(c).isNotNull();
        for (String section : List.of("nodes", "baskets")) {
            JsonNode w = want.get(section);
            JsonNode g = got.get(section);
            List<String> ids = new ArrayList<>();
            w.fieldNames().forEachRemaining(ids::add);
            List<String> gotIds = new ArrayList<>();
            g.fieldNames().forEachRemaining(gotIds::add);
            assertThat(gotIds).as(c + " " + section).containsExactlyElementsOf(ids);
            for (String id : ids) {
                JsonNode exp = w.get(id);
                JsonNode have = g.get(id);
                exp.fieldNames().forEachRemaining(f -> {
                    String what = c + " " + id + " " + f;
                    if (CLOSE.contains(f)) {
                        SystemBasketGoldenTest.assertClose(what, exp.get(f), have.get(f));
                    } else {
                        assertThat(have.get(f)).as(what).isEqualTo(exp.get(f));
                    }
                });
                assertThat(have.size()).as(c + " " + id + " fields").isEqualTo(exp.size());
            }
        }
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
