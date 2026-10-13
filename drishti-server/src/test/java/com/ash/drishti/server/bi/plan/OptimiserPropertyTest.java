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
package com.ash.drishti.server.bi.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.TestLake;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The optimiser's security property (docs/architecture/BI_DESIGN.md section 4.2): over a thousand random plans (scans with random rules, masks and denials,
 * filters on random fields, top-N, one or several facts), no rule removes, changes or adds a scan's security, and every condition of the query survives
 * (pushed into a scan, or left above). Also: the plan hash ignores security, and a plan's canonical text is the same whatever the order of a meet's parts.
 */
class OptimiserPropertyTest {

    static final List<String> ALIASES = List.of("trades", "desks");
    static final List<String> RULES = List.of("trades.desk in ['DESK-A']", "trades.notional > 10", "desks.region == 'EMEA'", "True", "filter trades.currency == 'EUR'");
    static final List<String> FIELDS = List.of("trades.desk", "trades.currency", "trades.notional", "desks.region", "desks.desk");

    /** Per alias: its rules, masks and denial, in a canonical form. */
    static Map<String, List<String>> security(LogicalPlan p) {
        Map<String, List<String>> out = new TreeMap<>();
        for (PlanNode.SecureScan s : p.scans()) {
            out.computeIfAbsent(s.alias(), k -> new ArrayList<>()).add(s.security().toString());
        }
        out.values().forEach(l -> l.sort(null));
        return out;
    }

    static List<String> conditions(LogicalPlan p) {
        List<String> out = new ArrayList<>();
        for (PlanNode n : PlanNode.all(p.root())) {
            if (n instanceof PlanNode.SecureScan s) {
                s.pushed().forEach(c -> out.add(c.text()));
            } else if (n instanceof PlanNode.Filter f) {
                f.conditions().forEach(c -> out.add(c.text()));
            }
        }
        out.sort(null);
        return out;
    }

    static PlanNode.SecureScan scan(Random r, String alias) {
        List<String> rules = new ArrayList<>();
        for (int i = r.nextInt(3); i > 0; i--) {
            rules.add(RULES.get(r.nextInt(RULES.size())));
        }
        List<String> masked = r.nextBoolean() ? List.of() : List.of(r.nextBoolean() ? "notional" : "region");
        return new PlanNode.SecureScan(alias, alias, "1.0.0", "current 2026-10-09", List.of(), List.of(), rules, masked, List.of(), r.nextInt(10) == 0);
    }

    static PlanNode aggregate(Random r, String fact) {
        List<PlanNode> scans = new ArrayList<>();
        PlanNode f = scan(r, fact);
        if (r.nextBoolean()) {
            f = new PlanNode.SemiJoin(f, scan(r, "desks"), fact + " -> desks");
        } else {
            scans.add(scan(r, "desks"));
        }
        scans.add(0, f);
        List<PlanNode.Cond> conds = new ArrayList<>();
        for (int i = r.nextInt(4); i > 0; i--) {
            String field = FIELDS.get(r.nextInt(FIELDS.size()));
            conds.add(r.nextInt(5) == 0 ? new PlanNode.Cond(field, "top", List.of(3, "Notional", "desc"), i) : new PlanNode.Cond(field, "eq", List.of("X" + i), i));
        }
        return new PlanNode.Aggregate(List.of(new PlanNode.Filter(scans, conds)), fact, List.of("trades.desk"), List.of("Notional", "Trades"), null);
    }

    @Test
    void noRuleRemovesChangesOrAddsSecurityAndNoConditionIsLost() {
        Random r = new Random(4242);
        var flat = DatasetParser.parse(TestLake.yaml()).dataset();
        for (int i = 0; i < 1000; i++) {
            PlanNode body = r.nextInt(3) == 0 ? new PlanNode.Meet(List.of(aggregate(r, "trades"), aggregate(r, "trades")), List.of("trades.desk")) : aggregate(r, "trades");
            if (r.nextBoolean()) {
                body = new PlanNode.Sort(body, List.of("Notional desc"));
            }
            LogicalPlan p = new LogicalPlan(new PlanNode.Answer(body, List.of("trades.desk", "Notional", "Trades"), "grouped"), "m", "v1", "current 2026-10-09", List.of(),
                    Map.of());
            List<String> before = conditions(p);
            Map<String, List<String>> secured = security(p);
            LogicalPlan q = p;
            for (Optimiser.Rule rule : Optimiser.RULES) {
                q = rule.apply().apply(q, flat);
                Map<String, List<String>> after = security(q);
                for (Map.Entry<String, List<String>> e : secured.entrySet()) {
                    assertThat(after.get(e.getKey())).as(rule.name() + " on plan " + i + ": every secure scan of " + e.getKey() + " keeps its security").containsAll(e.getValue());
                }
                assertThat(after.keySet()).as(rule.name() + ": no scan of a new dataset").isEqualTo(secured.keySet());
                after.forEach((alias, list) -> assertThat(secured.get(alias)).as(rule.name() + ": no security made up").containsAll(list));
            }
            List<String> plain = before.stream().filter(c -> !c.contains(" top ")).toList();
            assertThat(conditions(q)).as("plan " + i + ": every row condition survives").containsAll(plain);
        }
    }

    @Test
    void theHashIgnoresSecurityAndTheOrderOfAMeetsParts() {
        Random r = new Random(1);
        PlanNode a = aggregate(r, "trades");
        PlanNode b = aggregate(new Random(2), "trades");
        LogicalPlan ab = new LogicalPlan(new PlanNode.Answer(new PlanNode.Meet(List.of(a, b), List.of("x")), List.of("x"), "grouped"), "m", "v", "live", List.of(), Map.of());
        LogicalPlan ba = new LogicalPlan(new PlanNode.Answer(new PlanNode.Meet(List.of(b, a), List.of("x")), List.of("x"), "grouped"), "m", "v", "live", List.of(), Map.of());
        assertThat(ab.hash()).isEqualTo(ba.hash());
        PlanNode.SecureScan s1 = new PlanNode.SecureScan("t", "t", "1", "live", List.of(), List.of(), List.of("t.a == 1"), List.of(), List.of(), false);
        PlanNode.SecureScan s2 = new PlanNode.SecureScan("t", "t", "1", "live", List.of(), List.of(), List.of("t.a == 2"), List.of("b"), List.of(), true);
        assertThat(new LogicalPlan(s1, "m", "v", "live", List.of(), Map.of()).hash()).isEqualTo(new LogicalPlan(s2, "m", "v", "live", List.of(), Map.of()).hash());
    }
}
