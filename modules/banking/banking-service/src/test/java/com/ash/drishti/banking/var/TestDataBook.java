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

import com.ash.drishti.banking.compute.var.CurrencyTotals;
import com.ash.drishti.banking.compute.var.HierarchySpec;
import com.ash.drishti.banking.compute.var.PositionRules;
import com.ash.drishti.banking.compute.var.ScenarioFx;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.testkit.FeedFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The VaR book of the committed test data's demo date (data/testdata/market-risk, 2026-09-30, USD), built as the server's intake builds
 * it: the market-risk pack's position rules and hierarchies (from the trades' promoted columns, with the var-limit rows), the scenario
 * PVs converted with the supplied scenario FX, the P&amp;L vectors as they are.
 */
final class TestDataBook {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String DATE = "2026-09-30";

    private TestDataBook() {}

    /** The repository's root (the folder holding data/testdata/market-risk). */
    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("data/testdata/market-risk/scenario-pv"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("the committed test data (data/testdata/market-risk) is not above " + Path.of("").toAbsolutePath());
        }
        return p;
    }

    /** The documents of a folder of the demo date's normal scenario set. */
    static void lines(Path folder, Consumer<JsonNode> each) throws IOException {
        lines(folder, DATE, each);
    }

    /** The documents of a folder of a date's normal scenario set. */
    static void lines(Path folder, String date, Consumer<JsonNode> each) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;                                                     // a kind the demo does not send (P&L vectors)
        }
        for (String line : FeedFiles.lines(folder, p -> p.date().equals(date) && !p.name().startsWith("stress-"))) {
            each.accept(JSON.readTree(line));
        }
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || v.asText().isEmpty() ? null : v.asText();
    }

    /** The pack's hierarchies (MGMT, LEGAL, LL) with the server's root name. */
    @SuppressWarnings("unchecked")
    static List<HierarchySpec> hierarchies(Path repo) throws IOException {
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(repo.resolve("modules/banking/dmod/packs/market-risk/pack.yaml").toFile(), Map.class);
        return HierarchySpec.parse((Map<?, ?>) pack.get("hierarchies")).stream().map(s -> s.withRootName("Drishti Bank")).toList();
    }

    @SuppressWarnings("unchecked")
    static PositionRules rules(Path repo) throws IOException {
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(repo.resolve("modules/banking/dmod/packs/market-risk/pack.yaml").toFile(), Map.class);
        return PositionRules.of((Map<?, ?>) pack.get("positions"));
    }

    /** Every var-limit row of the date: its hierarchy and node id to the limit (USD). */
    static Map<String, Double> limits(Path data, String hierarchy) throws IOException {
        return limits(data, hierarchy, DATE);
    }

    /** The same on another date. */
    static Map<String, Double> limits(Path data, String hierarchy, String date) throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        lines(data.resolve("var-limit"), date, n -> {
            boolean pf = "PORTFOLIO".equalsIgnoreCase(text(n, "HIERARCHY"));
            if (hierarchy == null ? !pf : pf) {
                out.put(n.path("NODE_ID").asText(), n.path("LIMIT_VAR").asDouble());
            }
        });
        return out;
    }

    /** The date's book in USD. */
    static VarBook build(Path repo, ParallelSums sums) throws IOException {
        return books(repo, sums, DATE, 509).get(509).usd();
    }

    /** A book in USD with what converts it: the newest {@code scenarios.size()} scenarios and their FX. */
    record Book(VarBook usd, ScenarioFx fx, List<LocalDate> scenarios) {}

    /** One builder of {@link #books}. */
    private record Open(int window, VarBook.Builder builder, ScenarioFx fx, List<LocalDate> scenarios) {}

    /**
     * The date's books in USD, one per window (the newest N scenarios, as drishti.var.scenario-window keeps them), from one pass over the files. A
     * window is the full window's vectors cut to their newest N; P&amp;L from scenario PVs is computed on the full set first.
     */
    static Map<Integer, Book> books(Path repo, ParallelSums sums, String date, int... windows) throws IOException {
        Path data = repo.resolve("data/testdata/market-risk");
        PositionRules rules = rules(repo);
        List<LocalDate> scen = new ArrayList<>();
        lines(data.resolve("scenario-set"), date, n -> n.path("SCENARIO_DATES").forEach(d -> scen.add(LocalDate.parse(d.asText()))));
        List<ScenarioFx.Rates> rates = new ArrayList<>();
        lines(data.resolve("fx-scenario"), date, n -> {
            double[] r = new double[n.path("RATES").size()];
            for (int j = 0; j < r.length; j++) {
                r[j] = n.path("RATES").get(j).asDouble();
            }
            rates.add(new ScenarioFx.Rates(text(n, "CURRENCY"), n.path("RATE").asDouble(), r, "supplied"));
        });
        ScenarioFx fx = new ScenarioFx(scen.size(), rates);
        Map<String, Double> nodeLimits = limits(data, null, date);
        List<HierarchySpec> specs = hierarchies(repo);
        List<Open> open = new ArrayList<>();
        for (int w : windows) {
            int from = scen.size() - w;
            List<LocalDate> cut = List.copyOf(scen.subList(from, scen.size()));
            List<ScenarioFx.Rates> cutRates = new ArrayList<>();
            for (ScenarioFx.Rates r : rates) {
                cutRates.add(new ScenarioFx.Rates(r.currency(), r.base(), java.util.Arrays.copyOfRange(r.scenarios(), from, r.scenarios().length), "supplied"));
            }
            ScenarioFx wfx = new ScenarioFx(w, cutRates);
            VarBook.Builder b = new VarBook.Builder(LocalDate.parse(date), "HS-" + w, cut, 65_536, Long.MAX_VALUE);
            b.columnHierarchies(specs, nodeLimits);
            b.currencyRates(c -> wfx.rates(c).orElse(null));
            open.add(new Open(w, b, wfx, cut));
        }
        lines(data.resolve("scenario-pv"), date, n -> {
            if (rules.live(text(n, "TRADE_STATUS"))) {
                JsonNode pvs = n.get("SCENARIO_PV");
                double[] p = new double[pvs.size()];
                for (int j = 0; j < p.length; j++) {
                    p[j] = pvs.get(j).asDouble();
                }
                ScenarioFx.Rates r = fx.rates(text(n, "CURRENCY")).orElseThrow();
                double[] v = new double[p.length];
                ScenarioFx.pnlFromPv(n.path("BASE_PV").asDouble(), p, p.length, r, v);
                for (Open o : open) {
                    add(o.builder(), rules, n, tail(v, o.window()), n.path("BASE_PV").asDouble() * r.base(), text(n, "CURRENCY"));
                }
            }
        });
        lines(data.resolve("pnl-vector"), date, n -> {
            if (rules.live(text(n, "TRADE_STATUS"))) {
                JsonNode pnl = n.get("PNL");
                double[] v = new double[pnl.size()];
                for (int j = 0; j < v.length; j++) {
                    v[j] = pnl.get(j).asDouble();
                }
                for (Open o : open) {
                    add(o.builder(), rules, n, tail(v, o.window()), n.path("PV").asDouble(Double.NaN), null);
                }
            }
        });
        Map<Integer, Book> out = new LinkedHashMap<>();
        for (Open o : open) {
            out.put(o.window(), new Book(o.builder().build(sums), o.fx(), o.scenarios()));
        }
        return out;
    }

    private static double[] tail(double[] v, int n) {
        return n == v.length ? v.clone() : java.util.Arrays.copyOfRange(v, v.length - n, v.length);
    }

    private static void add(VarBook.Builder b, PositionRules rules, JsonNode n, double[] v, double pvUsd, String pvCurrency) {
        List<String> cols = b.textColumns();
        String[] texts = new String[cols.size()];
        for (int c = 0; c < texts.length; c++) {
            texts[c] = text(n, cols.get(c));
        }
        String own = text(n, "TRADE_CURRENCY");
        if (own != null) {
            texts[cols.indexOf("CURRENCY")] = own;                      // the record's own currency, as the intake (scopes, rules)
        }
        String trade = text(n, "TRADE_ID");
        if (trade == null) {
            trade = text(n, "POSITION_ID");                             // a position sent whole: never derived
            texts[cols.indexOf("POSITION_ID")] = trade;
            texts[cols.indexOf("POSITION_RULE")] = VarBook.SUPPLIED;
            texts[cols.indexOf("INSTRUMENT_ID")] = text(n, "SECURITY_ID") != null ? text(n, "SECURITY_ID") : trade;
            texts[cols.indexOf("SECURITY_ID")] = text(n, "SECURITY_ID");
        } else {
            PositionRules.Derived d = rules.derive(trade, text(n, "TT_BOOK"), text(n, "PRODUCT_GROUP"), text(n, "PRODUCT_TYPE"), k -> text(n, k));
            texts[cols.indexOf("POSITION_ID")] = d.positionId();
            texts[cols.indexOf("POSITION_RULE")] = d.rule();
            texts[cols.indexOf("INSTRUMENT_ID")] = d.instrumentId();
            texts[cols.indexOf("SECURITY_ID")] = d.securityId();
        }
        texts[cols.indexOf(CurrencyTotals.COLUMN)] = pvCurrency;
        b.trade(trade, v, v.length, texts, new double[] {n.path("QUANTITY").asDouble(Double.NaN), pvUsd});
    }
}
