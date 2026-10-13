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
package com.ash.drishti.server.bi.layer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The data model: parsing and Check, one case per row of the problem table (section 2.11), the security safeguard, flattening, contracts and stable ids. */
class ModelDefTest {

    static final String TRADES = DatasetDefParserTest.TRADES.replace("""
            security:
              rows:
                - { role: desk-head, where: "trades.desk in user.desks" }
                - { role: [risk, analyst], where: "True" }
              masked: [mtm]
            """, "");

    static final String DESKS = """
            rachana: 2
            dataset: desks
            version: 1.2.0
            source: { connector: desk-lake, kind: desk }
            grain: { keys: [desk] }
            columns:
              desk:   { type: string, role: key }
              region: { type: string }
            """;

    static final String LIMITS = """
            rachana: 2
            dataset: limits
            version: 1.0.0
            source: { connector: limit-lake, kind: limit }
            columns:
              desk:   { type: string, references: desk }
              amount: { type: decimal, aggregate: sum, additive: { over-time: last } }
            """;

    static final String MODEL = """
            rachana: 2
            model: desk-risk
            version: 1.0.0
            title: Desk risk
            datasets:
              trades: { dataset: trades@^1, role: fact }
              limits: { dataset: limits@^1, role: fact }
              desks:  { dataset: desks@^1, role: dimension }
            relationships:
              - { id: r_1, from: trades.desk, to: desks.desk }
              - { id: r_2, from: limits.desk, to: desks.desk }
            measures:
              MTM:      { id: m_mtm, expr: "sum(trades.mtm)" }
              Notional: { id: m_notional, expr: "sum(trades.notional)", currency: trades.currency }
              Trades:   { id: m_trades, expr: "count(trades)" }
              Limit:    { id: m_limit, expr: "sum(limits.amount)" }
              Usage:    { id: m_usage, expr: "measure('Notional') / measure('Limit')" }
            hierarchies:
              Desk: [desks.region, desks.desk]
            security:
              rows:
                trades:
                  - { role: desk-head, where: "trades.desk in user.desks" }
                  - { role: [risk, analyst], where: "True" }
              may-see-all: { limits: [desk-head], desks: [desk-head] }
            """;

    static Map<String, ResolvedDataset> resolved() {
        Map<String, ResolvedDataset> m = new LinkedHashMap<>();
        m.put("trades", ResolvedDataset.of(DatasetDefParser.parse(TRADES).dataset()));
        m.put("limits", ResolvedDataset.of(DatasetDefParser.parse(LIMITS).dataset()));
        m.put("desks", ResolvedDataset.of(DatasetDefParser.parse(DESKS).dataset()));
        return m;
    }

    static List<String> codes(String yaml) {
        ModelDefParser.Result r = ModelDefParser.parse(yaml);
        List<DatasetParser.Problem> all = new java.util.ArrayList<>(r.problems());
        if (r.model() != null) {
            all.addAll(ModelChecker.check(r.model(), resolved()));
        }
        return all.stream().map(p -> p.level().charAt(0) + ":" + p.code()).toList();
    }

    @Test
    void theModelChecksCleanAndFlattensForTheEngine() {
        assertThat(codes(MODEL)).isEmpty();
        ModelDef m = ModelDefParser.parse(MODEL).model();
        Flattener.Flat flat = Flattener.flatten(m, resolved());
        Dataset ds = flat.dataset();
        assertThat(ds.tables()).containsOnlyKeys("trades", "limits", "desks");
        assertThat(ds.tables().get("trades").columns().get("currency").path()).isEqualTo("ccy");
        assertThat(ds.measures()).containsKeys("MTM", "Usage", "Count of trades", "Sum of notional", "Count of limits", "Sum of amount");
        assertThat(ds.measures()).doesNotContainKey("Count of desks");                    // defaults come from facts only
        Dataset.TableSecurity trades = ds.security().tables().get("trades");
        assertThat(trades.layers()).hasSize(1);
        assertThat(trades.layers().get(0)).extracting(Dataset.RowRule::role).containsExactly("desk-head", "risk", "analyst");
        assertThat(ds.security().tables().get("desks").dimension()).isTrue();
        assertThat(DatasetParser.check(ds)).noneMatch(DatasetParser.Problem::error);
        assertThat(flat.unbuilt()).isEmpty();
    }

    @Test
    void structureIs9120() {
        assertThat(codes(MODEL.replace("version: 1.0.0", "version: one"))).contains("e:DRS-9120");
        assertThat(codes(MODEL.replace("  desks:  {", "  1desks: {"))).contains("e:DRS-9120");
        assertThat(codes(MODEL.replace("lifecycle", "x").replace("title: Desk risk", "title: Desk risk\nlifecycle: gone"))).contains("e:DRS-9120");
    }

    @Test
    void anIslandIsAWarning9121() {
        String island = MODEL.replace("  - { id: r_2, from: limits.desk, to: desks.desk }\n", "").replace("  Usage:    { id: m_usage, expr: \"measure('Notional') / measure('Limit')\" }\n", "");
        assertThat(codes(island)).contains("w:DRS-9121").noneMatch(c -> c.startsWith("e:"));
    }

    @Test
    void relationshipsAre9122And9123And9124() {
        assertThat(codes(MODEL.replace("to: desks.desk }\n  - { id: r_2", "to: desks.nothing }\n  - { id: r_2"))).contains("e:DRS-9122");
        assertThat(codes(MODEL.replace("from: trades.desk, to: desks.desk", "from: trades.notional, to: desks.desk"))).contains("e:DRS-9122");   // decimal to string
        assertThat(codes(MODEL.replace("from: trades.desk, to: desks.desk", "from: trades.desk, to: desks.region"))).contains("e:DRS-9123");   // not unique
        assertThat(codes(MODEL.replace("  - { id: r_2, from: limits.desk, to: desks.desk }",
                "  - { id: r_2, from: limits.desk, to: desks.desk }\n  - { id: r_3, from: trades.desk, to: limits.desk, cardinality: one-to-one }"))).contains("e:DRS-9124");
    }

    @Test
    void securityThatLoosensOrReachesAcrossIs9125() {
        assertThat(codes(MODEL.replace("trades.desk in user.desks", "desks.region == 'EMEA'"))).contains("e:DRS-9125");
        assertThat(codes(MODEL.replace("  may-see-all:", "  unmask: [trades.mtm]\n  may-see-all:"))).contains("e:DRS-9125");
        assertThat(codes(MODEL.replace("  may-see-all:", "  masked: [desks.colour]\n  may-see-all:"))).contains("e:DRS-9125");
    }

    @Test
    void aMeasureThatDoesNotCompileIs9127() {
        assertThat(codes(MODEL.replace("expr: \"sum(trades.mtm)\"", "expr: \"sum(trades.price)\""))).contains("e:DRS-9127");
        assertThat(codes(MODEL.replace("expr: \"sum(trades.mtm)\"", "python: vol, expr: \"sum(trades.mtm)\""))).contains("e:DRS-9127");
    }

    @Test
    void aContractNamingWhatTheModelLacksIs9128() {
        String c = MODEL + "contract: { exposes: listed, fields: [desks.region, desks.colour], measures: [MTM, Volatility] }\n";
        assertThat(codes(c)).contains("e:DRS-9128");
        assertThat(codes(c).stream().filter("e:DRS-9128"::equals)).hasSize(2);
    }

    @Test
    void laterWaveKeysAre9129() {
        assertThat(codes(MODEL + "calc-groups:\n  Time: { items: { YTD: \"ytd(${measure}, trades.tradeDate)\" } }\n")).contains("e:DRS-9129");
        assertThat(codes(MODEL + "parameters:\n  shock: { kind: number }\n")).contains("e:DRS-9129");
        assertThat(codes(MODEL.replace("{ id: m_mtm, expr: \"sum(trades.mtm)\" }", "{ id: m_mtm, expr: \"sum(trades.mtm)\", via: r_1 }"))).contains("e:DRS-9129");
        assertThat(codes(MODEL + "calc-groups: {}\nparameters: {}\n")).isEmpty();                                     // empty placeholders are fine
    }

    @Test
    void theSafeguardFlagsEveryOpenDatasetForARoleRestrictedElsewhere() {
        String noNote = MODEL.replace("  may-see-all: { limits: [desk-head], desks: [desk-head] }\n", "");
        ModelDef m = ModelDefParser.parse(noNote).model();
        List<ModelChecker.Exposure> open = ModelChecker.exposures(m, resolved());
        assertThat(open).containsExactly(new ModelChecker.Exposure("limits", "desk-head"), new ModelChecker.Exposure("desks", "desk-head"));
        assertThat(codes(noNote)).contains("w:DRS-9130");
        // a rule on the dataset (in the model) or the explicit note answers it, one dataset at a time
        String oneRule = noNote.replace("  rows:\n", "  rows:\n    limits:\n      - { role: desk-head, where: \"limits.desk in user.desks\" }\n      - { role: [risk, analyst], where: \"True\" }\n");
        assertThat(ModelChecker.exposures(ModelDefParser.parse(oneRule).model(), resolved())).containsExactly(new ModelChecker.Exposure("desks", "desk-head"));
        assertThat(ModelChecker.exposures(ModelDefParser.parse(MODEL).model(), resolved())).isEmpty();
        // a role that only has `true` rules is not restricted
        String allTrue = noNote.replace("- { role: desk-head, where: \"trades.desk in user.desks\" }", "- { role: desk-head, where: \"True\" }");
        assertThat(ModelChecker.exposures(ModelDefParser.parse(allTrue).model(), resolved())).isEmpty();
    }

    @Test
    void aDatasetsOwnRuleIsBoundToItsAliasAndANarrowingKeepsTheBasesRules() {
        DatasetDef trades = DatasetDefParser.parse(DatasetDefParserTest.TRADES).dataset();
        DatasetDef eu = DatasetDefParser.parse("""
                rachana: 2
                dataset: trades-eu
                version: 1.0.0
                extends: trades@1
                where: "currency == 'EUR'"
                security:
                  rows: [{ role: desk-head, where: "notional > 0" }]
                  masked: [notional]
                """).dataset();
        ResolvedDataset r = ResolvedDataset.extend(eu, ResolvedDataset.of(trades));
        assertThat(r.layers()).hasSize(2);
        assertThat(r.layers().get(0).get(0).where()).isEqualTo("desk in user.desks");             // the base's own name made bare
        assertThat(r.masked()).containsExactlyInAnyOrder("mtm", "notional");
        assertThat(r.chain()).containsExactly("trades-eu", "trades");
        ModelDef implicit = ImplicitModels.of(r, k -> java.util.Optional.empty()).model();
        Dataset ds = Flattener.flatten(implicit, Map.of("trades_eu", r)).dataset();
        Dataset.TableSecurity s = ds.security().tables().get("trades_eu");
        assertThat(s.filters()).containsExactly("trades_eu.currency == 'EUR'");
        assertThat(s.layers().get(0)).anyMatch(x -> x.where().equals("trades_eu.desk in user.desks"));
        assertThat(s.layers().get(1)).extracting(Dataset.RowRule::where).containsExactly("trades_eu.notional > 0");
    }

    @Test
    void ruleTextIsRewrittenForTheAliasOnly() {
        assertThat(RuleText.qualify("pack.trading.trade.desk in user.desks and trade.status == 'Live'", "pack.trading.trade", java.util.Set.of("desk", "status"), "t"))
                .isEqualTo("t.desk in user.desks and t.status == 'Live'");
        assertThat(RuleText.qualify("upper(desk) == 'desk' or year(tradeDate) > 2020", "x", java.util.Set.of("desk", "tradeDate"), "a"))
                .isEqualTo("upper(a.desk) == 'desk' or year(a.tradeDate) > 2020");
    }

    @Test
    void semverRangesMatchAsTheSpecSays() {
        assertThat(Semver.Range.parse("^1").matches(Semver.parse("1.4.2"))).isTrue();
        assertThat(Semver.Range.parse("^1").matches(Semver.parse("2.0.0"))).isFalse();
        assertThat(Semver.Range.parse("~1.2").matches(Semver.parse("1.3.0"))).isFalse();
        assertThat(Semver.Range.parse("1.2.0").matches(Semver.parse("1.2.0"))).isTrue();
        assertThat(Semver.Range.parse("1").matches(Semver.parse("1.9.9"))).isTrue();
        assertThat(Semver.Ref.parse("trading-risk@1").name()).isEqualTo("trading-risk");
        assertThat(Semver.Ref.parse("x@wat")).isNull();
    }

    @Test
    void aBreakingChangeNeedsANewMajor9126() {
        ModelDef was = ModelDefParser.parse(MODEL).model();
        ModelDef removed = ModelDefParser.parse(MODEL.replace("  Trades:   { id: m_trades, expr: \"count(trades)\" }\n", "")).model();
        assertThat(Contracts.models(was, removed).refusal()).contains("new major version").contains("Trades");
        ModelDef major = ModelDefParser.parse(MODEL.replace("  Trades:   { id: m_trades, expr: \"count(trades)\" }\n", "").replace("version: 1.0.0", "version: 2.0.0")).model();
        assertThat(Contracts.models(was, major).refusal()).isNull();
        ModelDef renamed = ModelDefParser.parse(MODEL.replace("  Trades:   { id: m_trades", "  Trade count: { id: m_trades").replace("version: 1.0.0", "version: 1.1.0")).model();
        Contracts.Change c = Contracts.models(was, renamed);
        assertThat(c.refusal()).isNull();
        assertThat(c.renames()).anyMatch(x -> x.contains("Trade count"));
        ModelDef inactive = ModelDefParser.parse(MODEL.replace("{ id: r_1, from: trades.desk, to: desks.desk }", "{ id: r_1, from: trades.desk, to: desks.desk, active: false }")
                .replace("version: 1.0.0", "version: 1.1.0")).model();
        assertThat(Contracts.models(was, inactive).refusal()).contains("inactive");
        assertThat(Contracts.models(was, ModelDefParser.parse(MODEL.replace("version: 1.0.0", "version: 0.9.0")).model()).refusal()).contains("goes down");
        DatasetDef d1 = DatasetDefParser.parse(DESKS).dataset();
        DatasetDef d2 = DatasetDefParser.parse(DESKS.replace("  region: { type: string }\n", "")).dataset();
        assertThat(Contracts.datasets(d1, d2).refusal()).contains("region");
    }

    @Test
    void stableIdsAreWrittenOnceAndNeverChanged() {
        String model = MODEL.replace("{ id: m_mtm, expr: \"sum(trades.mtm)\" }", "\"sum(trades.mtm)\"   # the mark").replace("{ id: r_1, ", "{ ");
        String once = StableIds.write(model, true);
        assertThat(once).containsPattern("(?m)^id: md_[0-9a-f]{8}$").contains("MTM:      { expr: \"sum(trades.mtm)\", id: m_mtm }   # the mark").contains("- { id: r_3, from: trades.desk");
        assertThat(StableIds.write(once, true)).isEqualTo(once);
        assertThat(ModelDefParser.parse(once).ok()).isTrue();
        String tight = StableIds.write(model.replace("- { from: trades.desk", "- {from: trades.desk"), true);
        assertThat(tight).as("a relationship written without a space after the brace").contains("- { id: r_3, from: trades.desk").doesNotContain(",from:");
        assertThat(ModelDefParser.parse(tight).ok()).isTrue();
        String ds = StableIds.write(DESKS.replace("  region: { type: string }", "  region: string\n  name:\n    type: string"), false);
        assertThat(ds).contains("region: { type: string, id: c_region }").contains("desk:   { type: string, role: key, id: c_desk }").contains("    id: c_name");
        assertThat(DatasetDefParser.parse(ds).dataset().columns().get("name").id()).isEqualTo("c_name");
    }

    @Test
    void anImplicitModelBringsPackDimensionsOneHop() {
        DatasetDef trade = DatasetDefParser.parse(DatasetDefParserTest.TRADES.replace("dataset: trades", "dataset: pack.trading.trade\ngenerated: { by: pack, pack: trading }")).dataset();
        DatasetDef desk = DatasetDefParser.parse(DESKS.replace("dataset: desks", "dataset: pack.banking-core.desk\ngenerated: { by: pack, pack: banking-core }")).dataset();
        ImplicitModels.Implicit im = ImplicitModels.of(ResolvedDataset.of(trade), k -> "desk".equals(k) ? java.util.Optional.of(ResolvedDataset.of(desk)) : java.util.Optional.empty());
        assertThat(im.model().datasets()).containsOnlyKeys("trade", "desk");
        assertThat(im.model().datasets().get("desk").role()).isEqualTo("dimension");
        assertThat(im.model().relationships()).singleElement().satisfies(r -> assertThat(r.label()).isEqualTo("trade.desk -> desk.id"));
        assertThat(im.model().id()).isEqualTo("implicit:pack.trading.trade@1");
        Dataset ds = Flattener.flatten(im.model(), im.datasets()).dataset();
        assertThat(ds.measures()).containsKeys("Count of trade", "Sum of notional");
        // an authored dataset brings no dimensions
        assertThat(ImplicitModels.of(ResolvedDataset.of(DatasetDefParser.parse(DatasetDefParserTest.TRADES).dataset()), k -> java.util.Optional.of(ResolvedDataset.of(desk)))
                .model().datasets()).containsOnlyKeys("trades");
    }

    @Test
    void theFullContractDetectionOfWaveACatchesMeaningsFieldsAndGrains() {
        ModelDef was = ModelDefParser.parse(MODEL).model();
        Map<String, ResolvedDataset> data = resolved();
        ModelDef sameMeaning = ModelDefParser.parse(MODEL.replace("expr: \"sum(trades.mtm)\"", "expr: \"sum( trades.mtm )\"")).model();
        assertThat(Contracts.models(was, sameMeaning, data, data).breaking()).as("a reformatted expression is the same tree").isEmpty();
        ModelDef otherMeaning = ModelDefParser.parse(MODEL.replace("expr: \"sum(trades.mtm)\"", "expr: \"sum(trades.mtm, where=trades.mtm > 0)\"")).model();
        assertThat(Contracts.models(was, otherMeaning, data, data).refusal()).contains("changes meaning").contains("new major version");
        Map<String, ResolvedDataset> fewer = new java.util.LinkedHashMap<>(data);
        fewer.put("trades", ResolvedDataset.of(DatasetDefParser.parse(TRADES.replace("  mtm:", "  mtmX:")).dataset()));
        assertThat(Contracts.models(was, was, data, fewer).breaking()).anyMatch(x -> x.contains("trades.mtm is removed"));
    }
}
