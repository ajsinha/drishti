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
        if (!Files.isDirectory(folder)) {
            return;                                                     // a kind the demo does not send (P&L vectors)
        }
        for (String line : FeedFiles.lines(folder, p -> p.date().equals(DATE) && !p.name().startsWith("stress-"))) {
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
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(repo.resolve("config/packs/banking/market-risk/pack.yaml").toFile(), Map.class);
        return HierarchySpec.parse((Map<?, ?>) pack.get("hierarchies")).stream().map(s -> s.withRootName("Drishti Bank")).toList();
    }

    @SuppressWarnings("unchecked")
    static PositionRules rules(Path repo) throws IOException {
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(repo.resolve("config/packs/banking/market-risk/pack.yaml").toFile(), Map.class);
        return PositionRules.of((Map<?, ?>) pack.get("positions"));
    }

    /** Every var-limit row of the date: its hierarchy and node id to the limit (USD). */
    static Map<String, Double> limits(Path data, String hierarchy) throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        lines(data.resolve("var-limit"), n -> {
            boolean pf = "PORTFOLIO".equalsIgnoreCase(text(n, "HIERARCHY"));
            if (hierarchy == null ? !pf : pf) {
                out.put(n.path("NODE_ID").asText(), n.path("LIMIT_VAR").asDouble());
            }
        });
        return out;
    }

    /** The date's book in USD. */
    static VarBook build(Path repo, ParallelSums sums) throws IOException {
        Path data = repo.resolve("data/testdata/market-risk");
        PositionRules rules = rules(repo);
        List<LocalDate> scen = new ArrayList<>();
        lines(data.resolve("scenario-set"), n -> n.path("SCENARIO_DATES").forEach(d -> scen.add(LocalDate.parse(d.asText()))));
        List<ScenarioFx.Rates> rates = new ArrayList<>();
        lines(data.resolve("fx-scenario"), n -> {
            double[] r = new double[n.path("RATES").size()];
            for (int j = 0; j < r.length; j++) {
                r[j] = n.path("RATES").get(j).asDouble();
            }
            rates.add(new ScenarioFx.Rates(text(n, "CURRENCY"), n.path("RATE").asDouble(), r, "supplied"));
        });
        ScenarioFx fx = new ScenarioFx(scen.size(), rates);
        VarBook.Builder b = new VarBook.Builder(LocalDate.parse(DATE), "HS-509", scen, 65_536, Long.MAX_VALUE);
        b.columnHierarchies(hierarchies(repo), limits(data, null));
        b.currencyRates(c -> fx.rates(c).orElse(null));
        lines(data.resolve("scenario-pv"), n -> {
            if (rules.live(text(n, "TRADE_STATUS"))) {
                JsonNode pvs = n.get("SCENARIO_PV");
                double[] p = new double[pvs.size()];
                for (int j = 0; j < p.length; j++) {
                    p[j] = pvs.get(j).asDouble();
                }
                ScenarioFx.Rates r = fx.rates(text(n, "CURRENCY")).orElseThrow();
                double[] v = new double[p.length];
                ScenarioFx.pnlFromPv(n.path("BASE_PV").asDouble(), p, p.length, r, v);
                add(b, rules, n, v, n.path("BASE_PV").asDouble() * r.base(), text(n, "CURRENCY"));
            }
        });
        lines(data.resolve("pnl-vector"), n -> {
            if (rules.live(text(n, "TRADE_STATUS"))) {
                JsonNode pnl = n.get("PNL");
                double[] v = new double[pnl.size()];
                for (int j = 0; j < v.length; j++) {
                    v[j] = pnl.get(j).asDouble();
                }
                add(b, rules, n, v, n.path("PV").asDouble(Double.NaN), null);
            }
        });
        return b.build(sums);
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
