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
package com.ash.drishti.server.saccr;

import com.ash.drishti.compute.saccr.Saccr;
import com.ash.drishti.compute.saccr.SaccrInput;
import com.ash.drishti.compute.saccr.SaccrParams;
import com.ash.drishti.server.dist.XlsxWriter;
import com.ash.drishti.server.playground.PlaygroundTemplate;
import com.ash.drishti.server.playground.SheetStream;
import com.ash.drishti.server.playground.WorkbookStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SA-CCR Playground's workbook (docs/architecture/COUNTERPARTY_ENGINES.md section 8.1; docs/guides/SACCR_GUIDE.md section 8): Main,
 * Config, Data, Trades, Hedging sets, Explain and Provenance, on the VaR Playground's frame. Data holds the classification rows as the
 * engine read them (times in years from the business date) and each trade's MTM; every derived cell of Trades, Hedging sets and Main is a
 * formula of the common set over Data and Config, every parameter a Config cell with its CRE52 paragraph, and Main holds the engine's
 * figures beside the workbook's with a check each (equal to the cent). Two switches in Config (the IR bucket offsets, the cap) recompute
 * live. One netting set, or up to {@code max-sets} of a counterparty side by side (EAD adds over sets: Main's total is a plain sum).
 */
final class SaccrPlaygroundBook {

    static final List<String> STYLES = List.of("default", "title", "band", "head", "text", "note", "input.money", "input.text", "input.dec", "input.rate",
            "money", "money.bold", "int", "rate", "check", "label", "total", "wrap", "label.wrap");
    static final String PASS = "✓";
    static final String FAIL = "✗";
    static final String ALL_PASS = "ALL CHECKS PASS";
    private static final int FIRST = 4;
    private static final int SW_OFFSET = 4;
    private static final int SW_CAP = 5;
    private static final int PARAM_FIRST = 10;

    private final SaccrService.Run run;
    private final List<SaccrService.SetRun> sets = new ArrayList<>();
    private final SaccrParams p;
    private final PlaygroundTemplate t;
    private final boolean cached;
    private final String user;
    private final String now;
    private final Map<String, String> cfg = new LinkedHashMap<>();
    /** Per set: the Main rows of its block. */
    private final Map<String, Block> blocks = new LinkedHashMap<>();
    /** Per set|class|hs: the Hedging-sets sheet row of the hedging set's add-on. */
    private final List<HsRow> hsRows = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private int totalRow;
    private int lastMainRow;

    /** A Data and Trades row: the set and the engine's terms. */
    private record Line(SaccrService.SetRun set, Saccr.Terms terms) {}

    /** One sub-key or hedging-set row of the Hedging sets sheet. */
    private record HsRow(String set, Saccr.HedgingSet hs, Saccr.SubKey key, int row, int keyFirst, int keyLast, int sampleTradeRow) {}

    /** Where a set's figures are on Main. */
    private record Block(int title, int v, int c, int nica, int th, int mta, int margined, int cleared, int mporDays, int freq, int disputes, int illiquid,
            int members, int floor, int mpor, int mf, int rc, int classFirst, int agg, int exponent, int mult, int pfe, int ead, int after, int capped,
            int rows, int end) {}

    SaccrPlaygroundBook(SaccrService.Run run, SaccrService svc, PlaygroundTemplate t, boolean cached, String user, String now) {
        this.run = run;
        this.p = svc.params();
        this.t = t;
        this.cached = cached;
        this.user = user;
        this.now = now;
        for (SaccrService.SetRun s : run.sets()) {
            if (s.result() != null) {
                sets.add(s);
                for (Saccr.Terms x : s.result().terms()) {
                    lines.add(new Line(s, x));
                }
            }
        }
        int row = PARAM_FIRST;
        for (String k : p.values().keySet()) {
            cfg.put(k, "Config!$B$" + row++);
        }
        planMain();
    }

    private Double c(double v) {
        return cached && Double.isFinite(v) ? v : null;
    }

    private static String col(int c) {
        return XlsxWriter.col(c);
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String cf(String key) {
        String r = cfg.get(key);
        if (r == null) {
            throw new IllegalStateException("no Config row " + key);
        }
        return r;
    }

    private String minYears() {
        return "(" + cf("time.min-maturity-days") + "/" + cf("time.mf-days-per-year") + ")";
    }

    // ---- the plan ------------------------------------------------------------------------------------------------------------------

    private void planMain() {
        int row = 6;
        for (SaccrService.SetRun s : sets) {
            int title = row;
            int h = title + 1;                         // header items, one a row
            int v = h;
            int rcRow = h + 17;                        // after 13 header items, the MPOR rows and a blank and the head
            int classFirst = rcRow + 1;
            int agg = classFirst + Saccr.ASSET_CLASSES.size();
            int exponent = agg + 1;
            int mult = exponent + 1;
            int pfe = mult + 1;
            int ead = pfe + 1;
            int after = ead + 1;
            int capped = after + 1;
            int rows = capped + 1;
            blocks.put(s.id(), new Block(title, v, v + 1, v + 2, v + 3, v + 4, v + 5, v + 6, v + 7, v + 8, v + 9, v + 10, v + 11, v + 12, v + 13, v + 14, rcRow,
                    classFirst, agg, exponent, mult, pfe, ead, after, capped, rows, rows + 1));
            row = rows + 3;
        }
        totalRow = row;
        lastMainRow = totalRow;
    }

    private void planHedgingSets() {
        int row = FIRST;
        for (SaccrService.SetRun s : sets) {
            for (Saccr.HedgingSet h : s.result().hedgingSets()) {
                int first = row;
                List<Saccr.SubKey> keys = h.subKeys();
                for (Saccr.SubKey k : keys) {
                    hsRows.add(new HsRow(s.id(), h, k, row++, first, first + keys.size() - 1, sampleRow(s, h, k)));
                }
                hsRows.add(new HsRow(s.id(), h, null, row++, first, first + keys.size() - 1, sampleRow(s, h, null)));
            }
        }
    }

    /** The Trades row of the first trade of a hedging set (and sub-key): its SF and ρ cells are the sub-key's. */
    private int sampleRow(SaccrService.SetRun s, Saccr.HedgingSet h, Saccr.SubKey k) {
        int fallback = -1;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.set() == s && l.terms().trade().assetClass().equals(h.assetClass()) && l.terms().trade().hedgingSet().equals(h.hedgingSet())) {
                if (fallback < 0) {
                    fallback = FIRST + i;
                }
                if (k == null || l.terms().subKey().equals(k.key())) {
                    return FIRST + i;
                }
            }
        }
        return fallback;
    }

    private String range(String sheet, int c) {
        int last = Math.max(FIRST, FIRST + lines.size() - 1);
        return sheet + "!$" + col(c) + "$" + FIRST + ":$" + col(c) + "$" + last;
    }

    private String hsRange(int c) {
        int last = Math.max(FIRST, hsRows.isEmpty() ? FIRST : hsRows.get(hsRows.size() - 1).row());
        return "'Hedging sets'!$" + col(c) + "$" + FIRST + ":$" + col(c) + "$" + last;
    }

    // ---- the writing -------------------------------------------------------------------------------------------------------------

    void write(OutputStream out) {
        planHedgingSets();
        Map<String, String> v = new LinkedHashMap<>(t.brandingValues(com.ash.drishti.common.Branding.current()));
        v.put("TITLE", "SA-CCR Playground · " + run.scope() + " · " + (run.date() == null ? "the hand-built cases" : run.date()));
        v.put("SUBJECT", "SA-CCR exposure at default worked in formulas, " + p.describe());
        v.put("USER", user);
        v.put("NOW", now);
        try (WorkbookStream w = new WorkbookStream(out, t, v)) {
            main(w.sheet("Main", "1F3A5F"));
            config(w.sheet("Config", "B08A2E"));
            data(w.sheet("Data", "B08A2E"));
            trades(w.sheet("Trades", "2E5A88"));
            hedgingSets(w.sheet("Hedging sets", "2E5A88"));
            explain(w.sheet("Explain", "6B7686"));
            provenance(w.sheet("Provenance", "6B7686"));
            w.name("EAD", "Main", "$B$" + totalRow);
        }
    }

    // ---- Main ----------------------------------------------------------------------------------------------------------------------

    private String check(String a, String b) {
        return "IF(ABS(" + a + "-" + b + ")<0.005,\"" + PASS + "\",\"" + FAIL + "\")";
    }

    private void checkCell(SheetStream s, int col, String a, String b) {
        s.formulaText(col, check(a, b), cached ? PASS : null, "check");
    }

    private void main(SheetStream s) {
        s.width(0, 40).widths(1, 4, 20).width(5, 10).freeze(4, 0);
        s.row(1).text(0, com.ash.drishti.common.Branding.current().productOr("") + " · SA-CCR Playground", "title");
        for (int i = 1; i < 6; i++) {
            s.blank(i, "title");
        }
        s.row(2).text(0, run.kind() + " " + run.scope() + " · " + (run.date() == null ? "the hand-built cases" : run.date()) + " · " + p.describe() + " · USD · "
                + sets.size() + " netting set" + (sets.size() == 1 ? "" : "s"), "note");
        String checks = "F6:F" + lastMainRow;
        s.row(3).formulaText(0, "IF(SUMPRODUCT((" + checks + "=\"" + FAIL + "\")*1)=0,\"" + ALL_PASS + "\",\"A CHECK FAILS: SEE COLUMN F\")", cached ? ALL_PASS : null,
                "band");
        s.checks("A3", ALL_PASS);
        s.row(4).text(0, "Each netting set: its header, then the results, margined and unmargined, each beside the engine's figure; EAD adds over sets "
                + "(the total at the bottom). Change Data or Config and watch every figure recalculate: the engine checks turn red (the engine answered "
                + "the original question), the add-up checks stay green.", "note");
        for (SaccrService.SetRun set : sets) {
            block(s, set);
        }
        s.row(totalRow).text(0, "EAD of the scope (Σ over the netting sets)", "label");
        List<String> parts = new ArrayList<>();
        for (Block b : blocks.values()) {
            parts.add("B" + b.after());
        }
        s.formula(1, parts.isEmpty() ? "0" : "SUM(" + String.join(",", parts) + ")", c(run.ead()), "total");
        s.number(3, run.ead(), "total");
        checkCell(s, 5, "B" + totalRow, "D" + totalRow);
        s.checks(checks, PASS);
    }

    private void block(SheetStream s, SaccrService.SetRun set) {
        Block b = blocks.get(set.id());
        Saccr.Result r = set.result();
        SaccrInput.NettingSet h = r.set();
        boolean fromMtm = r.terms().stream().anyMatch(x -> Double.isFinite(x.trade().mtm()));
        s.row(b.title()).text(0, "Netting set " + set.id() + (set.counterparty() == null ? "" : " · " + set.counterparty()) + (set.legalEntity() == null ? ""
                : " · " + set.legalEntity()) + " · " + (h.margined() ? "margined" : "unmargined") + " · " + set.status(), "head").text(1, "Value", "head")
                .text(3, "Engine", "head").text(5, "Check", "head");
        String q = q(set.id());
        s.row(b.v()).text(0, "V: Σ MTM of the classified derivatives", "label");
        if (fromMtm) {
            s.formula(1, "SUMIFS(" + range("Data", 22) + "," + range("Data", 0) + "," + q + "," + range("Data", 2) + ",0)", c(r.v()), "input.money");
            s.number(3, r.v(), "money");
            checkCell(s, 5, "B" + b.v(), "D" + b.v());
        } else {
            s.number(1, r.v(), "input.money");
            s.text(2, "typed: the case's own V", "note");
        }
        typed(s, b.c(), "C: collateral held, net, after haircut (NICA included)", h.c(), "input.money");
        typed(s, b.nica(), "NICA: net independent collateral", h.nica(), "input.money");
        typed(s, b.th(), "TH: THRESHOLD_THEM", h.th(), "input.money");
        typed(s, b.mta(), "MTA: MTA_THEM", h.mta(), "input.money");
        typed(s, b.margined(), "MARGINED (1 yes, 0 no)", h.margined() ? 1 : 0, "int");
        typed(s, b.cleared(), "CLEARED (1 yes, 0 no)", h.cleared() ? 1 : 0, "int");
        typed(s, b.mporDays(), "MPOR_DAYS (the CSA's, without the remargining period)", h.mporDays(), "int");
        typed(s, b.freq(), "MARGIN_FREQUENCY_DAYS (F)", Math.max(1, h.marginFrequencyDays()), "int");
        typed(s, b.disputes(), "DISPUTES_LAST_2Q", h.disputes(), "int");
        typed(s, b.illiquid(), "ILLIQUID_COLLATERAL (1 yes, 0 no)", h.illiquidCollateral() ? 1 : 0, "int");
        typed(s, b.members(), "Members of the set (the 5,000-trade floor)", h.members() > 0 ? h.members() : r.terms().size(), "int");
        String floor = "IF(B" + b.cleared() + "=1," + cf("mpor-floors-days.cleared") + "," + cf("mpor-floors-days.bilateral") + ")";
        floor = "IF(OR(B" + b.members() + ">" + cf("mpor-floors-days.large-netting-set-trades") + ",B" + b.illiquid() + "=1)," + cf("mpor-floors-days.large-or-illiquid")
                + "," + floor + ")*IF(B" + b.disputes() + ">" + cf("dispute-doubling.disputes-over") + "," + cf("dispute-doubling.factor") + ",1)";
        s.row(b.floor()).text(0, "MPOR floor, business days (CRE52.50, CRE52.51)", "label").formula(1, "IF(B" + b.margined() + "=1," + floor + ",0)",
                c(r.mporFloor()), "int").number(3, r.mporFloor(), "int");
        checkCell(s, 5, "B" + b.floor(), "D" + b.floor());
        s.row(b.mpor()).text(0, "MPOR = max(floor, MPOR_DAYS) + F − 1", "label").formula(1, "IF(B" + b.margined() + "=1,MAX(B" + b.floor() + ",B" + b.mporDays()
                + ")+B" + b.freq() + "-1,0)", c(r.mpor()), "int").number(3, r.mpor(), "int");
        checkCell(s, 5, "B" + b.mpor(), "D" + b.mpor());
        s.row(b.mf()).text(0, "MF margined = 1.5 √(MPOR / 250) (CRE52.52)", "label").formula(1, "IF(B" + b.margined() + "=1," + cf("margined-mf-scale")
                + "*SQRT(B" + b.mpor() + "/" + cf("time.mf-days-per-year") + "),0)", c(r.mfMargined()), "rate").number(3, r.mfMargined(), "rate");
        checkCell(s, 5, "B" + b.mf(), "D" + b.mf());
        // the results
        s.row(b.rc() - 1).text(0, "Result", "head").text(1, "Margined", "head").text(2, "Unmargined", "head").text(3, "Engine margined", "head")
                .text(4, "Engine unmargined", "head").text(5, "Check", "head");
        Saccr.Basis u = r.unmargined();
        Saccr.Basis m = r.margined();
        String marg = "B" + b.margined() + "=1";
        String vc = "(B" + b.v() + "-B" + b.c() + ")";
        result(s, b.rc(), "RC (CRE52.10, CRE52.18)", "IF(" + marg + ",MAX(" + vc + ",B" + b.th() + "+B" + b.mta() + "-B" + b.nica() + ",0),0)", "MAX(" + vc + ",0)",
                m == null ? 0 : m.rc(), u.rc(), "money");
        int row = b.classFirst();
        for (String ac : Saccr.ASSET_CLASSES) {
            String sum = "SUMIFS(" + hsRange(9) + "," + hsRange(0) + "," + q + "," + hsRange(1) + "," + q(ac) + "," + hsRange(4) + ",\"hedging set\")";
            String sumM = "SUMIFS(" + hsRange(10) + "," + hsRange(0) + "," + q + "," + hsRange(1) + "," + q(ac) + "," + hsRange(4) + ",\"hedging set\")";
            result(s, row, "AddOn " + ac, "IF(" + marg + "," + sumM + ",0)", sum, m == null ? 0 : m.addOns().get(ac), u.addOns().get(ac), "money");
            row++;
        }
        String cls = "B" + b.classFirst() + ":B" + (b.agg() - 1);
        String clsU = "C" + b.classFirst() + ":C" + (b.agg() - 1);
        result(s, b.agg(), "AddOn_agg (CRE52.24)", "SUM(" + cls + ")", "SUM(" + clsU + ")", m == null ? 0 : m.addOnAgg(), u.addOnAgg(), "money.bold");
        String f = cf("multiplier-floor");
        result(s, b.exponent(), "Exponent (V − C) / (2 (1 − f) AddOn_agg)", "IF(B" + b.agg() + ">0," + vc + "/(2*(1-" + f + ")*B" + b.agg() + "),0)",
                "IF(C" + b.agg() + ">0," + vc + "/(2*(1-" + f + ")*C" + b.agg() + "),0)", m == null ? 0 : m.exponent(), u.exponent(), "rate");
        result(s, b.mult(), "Multiplier (CRE52.23)", "IF(B" + b.agg() + ">0,MIN(1," + f + "+(1-" + f + ")*EXP(B" + b.exponent() + ")),1)",
                "IF(C" + b.agg() + ">0,MIN(1," + f + "+(1-" + f + ")*EXP(C" + b.exponent() + ")),1)", m == null ? 1 : m.multiplier(), u.multiplier(), "rate");
        result(s, b.pfe(), "PFE = multiplier × AddOn_agg (CRE52.20)", "B" + b.mult() + "*B" + b.agg(), "C" + b.mult() + "*C" + b.agg(), m == null ? 0 : m.pfe(),
                u.pfe(), "money");
        result(s, b.ead(), "EAD = α (RC + PFE) (CRE52.1)", "IF(" + marg + "," + cf("alpha") + "*(B" + b.rc() + "+B" + b.pfe() + "),0)", cf("alpha") + "*(C" + b.rc()
                + "+C" + b.pfe() + ")", m == null ? 0 : m.ead(), u.ead(), "money.bold");
        String cap = "Config!$B$" + SW_CAP + "=\"on\"";
        s.row(b.after()).text(0, "EAD after the cap", "label").formula(1, "IF(" + marg + ",IF(AND(" + cap + "," + cf("cap-at-unmargined") + "=1),MIN(B" + b.ead() + ",C"
                + b.ead() + "),B" + b.ead() + "),C" + b.ead() + ")", c(r.ead()), "total").number(3, r.ead(), "total");
        checkCell(s, 5, "B" + b.after(), "D" + b.after());
        s.row(b.capped()).text(0, "CAPPED", "label").formulaText(1, "IF(AND(" + marg + "," + cap + "," + cf("cap-at-unmargined") + "=1,C" + b.ead() + "<B" + b.ead()
                + "),\"TRUE\",\"FALSE\")", cached ? String.valueOf(r.capped()).toUpperCase(java.util.Locale.ROOT) : null, "text")
                .text(3, String.valueOf(r.capped()).toUpperCase(java.util.Locale.ROOT), "text");
        s.formulaText(5, "IF(B" + b.capped() + "=D" + b.capped() + ",\"" + PASS + "\",\"" + FAIL + "\")", cached ? PASS : null, "check");
        s.row(b.rows()).text(0, "Classification rows in Data (refused: " + r.refusals().size() + ")", "label")
                .formula(1, "SUMPRODUCT((" + range("Data", 0) + "=" + q + ")*1)", c(r.terms().size()), "int").number(3, r.terms().size(), "int");
        checkCell(s, 5, "B" + b.rows(), "D" + b.rows());
    }

    private void typed(SheetStream s, int row, String label, double v, String style) {
        s.row(row).text(0, label, "label").number(1, v, style);
    }

    private void result(SheetStream s, int row, String label, String fm, String fu, double em, double eu, String style) {
        s.row(row).text(0, label, "label").formula(1, fm, c(em), style).formula(2, fu, c(eu), style).number(3, em, style).number(4, eu, style);
        s.formulaText(5, "IF(AND(ABS(B" + row + "-D" + row + ")<0.005,ABS(C" + row + "-E" + row + ")<0.005),\"" + PASS + "\",\"" + FAIL + "\")", cached ? PASS : null,
                "check");
    }

    // ---- Config ----------------------------------------------------------------------------------------------------------------------

    private void config(SheetStream s) {
        s.width(0, 44).width(1, 16).width(2, 14).width(3, 60);
        s.row(1).text(0, "Config: the switches and the parameters of " + p.describe() + ", each with its paragraph", "title");
        s.row(3).text(0, "Switch", "head").text(1, "Value (choose)", "head").text(2, "Default", "head").text(3, "Rule", "head");
        Saccr.Choices ch = sets.isEmpty() ? Saccr.Choices.REGULATORY : sets.get(0).result().choices();
        s.row(SW_OFFSET).text(0, "ir-bucket-offset", "label").text(1, ch.irBucketOffset() ? "on" : "off", "input.text").text(2, "on", "text")
                .text(3, "CRE52.57: rates offset across maturity buckets; off: |D1| + |D2| + |D3|", "wrap");
        s.list("B" + SW_OFFSET, List.of("on", "off"));
        s.row(SW_CAP).text(0, "cap", "label").text(1, ch.cap() ? "on" : "off", "input.text").text(2, "on", "text")
                .text(3, "CRE52.1: a margined set's EAD is capped at its unmargined EAD", "wrap");
        s.list("B" + SW_CAP, List.of("on", "off"));
        s.row(7).text(0, "Business date", "label").text(1, run.date() == null ? "(the cases give years directly)" : run.date().toString(), "text");
        s.row(PARAM_FIRST - 1).text(0, "Parameter (its key)", "head").text(1, "Value", "head").text(2, "Paragraph", "head").text(3, "Regulation", "head");
        int row = PARAM_FIRST;
        for (Map.Entry<String, Double> e : p.values().entrySet()) {
            s.row(row++).text(0, e.getKey(), "label").number(1, e.getValue(), "input.dec").text(2, p.ref(e.getKey()), "text").text(3, p.regulation + " " + p.version,
                    "text");
        }
    }

    // ---- Data ------------------------------------------------------------------------------------------------------------------------

    private static final String[] DATA_HEAD = {"NETTING_SET", "TRADE_ID", "DRIVER", "ASSET_CLASS", "HEDGING_SET", "TRANSACTION_TYPE", "REFERENCE", "IS_INDEX",
        "CREDIT_QUALITY", "DIRECTION", "NOTIONAL (USD)", "S (years)", "E (years)", "M (years)", "T (years)", "OPTION_TYPE", "BOUGHT_SOLD", "STRIKE",
        "UNDERLYING_FORWARD", "SHIFT", "ATTACHMENT", "DETACHMENT", "MTM", "START_DATE", "END_DATE", "MATURITY_DATE", "EXERCISE_DATE"};

    private void data(SheetStream s) {
        s.widths(0, 26, 14).freeze(3, 2);
        s.row(1).text(0, "Data: the classification rows (saccr-trade) as delivered, the dates as years ACT/365 from the business date (as the engine read "
                + "them), and each trade's MTM", "title");
        s.row(3);
        for (int i = 0; i < DATA_HEAD.length; i++) {
            s.text(i, DATA_HEAD[i], "head");
        }
        int row = FIRST;
        for (Line l : lines) {
            SaccrInput.Trade x = l.terms().trade();
            s.row(row++).text(0, l.set().id(), "input.text").text(1, x.tradeId(), "input.text").number(2, x.driver(), "int").text(3, x.assetClass(), "input.text")
                    .text(4, x.hedgingSet(), "input.text").text(5, x.transactionType(), "input.text").text(6, x.reference(), "input.text")
                    .text(7, x.index() ? "TRUE" : "FALSE", "input.text").text(8, x.creditQuality(), "input.text").text(9, x.direction(), "input.text")
                    .number(10, x.notional(), "input.money").number(11, x.s(), "input.dec").number(12, x.e(), "input.dec").number(13, x.m(), "input.dec")
                    .number(14, x.t(), "input.dec").text(15, x.optionType(), "input.text").text(16, x.boughtSold(), "input.text").number(17, x.strike(), "input.dec")
                    .number(18, x.forward(), "input.dec").number(19, x.shift(), "input.dec").number(20, x.attachment(), "input.dec").number(21, x.detachment(), "input.dec")
                    .number(22, x.mtm(), "input.money");
            int k = 23;
            for (String f : List.of("START_DATE", "END_DATE", "MATURITY_DATE", "EXERCISE_DATE")) {
                Object v = x.attributes().get(f);
                s.text(k++, v == null ? null : String.valueOf(v), "text");
            }
        }
    }

    // ---- Trades ----------------------------------------------------------------------------------------------------------------------

    private static final String[] TRADES_HEAD = {"Netting set", "Trade", "Asset class", "Hedging set", "S", "E", "M", "T", "SD", "d", "SF", "ρ", "σ", "d1", "δ",
        "MF unmargined", "MF margined", "D unmargined", "D margined", "IR bucket", "Sub-key"};

    /** The Config cells of a row's SF, ρ and σ (the factors as formulas over Config). */
    private String[] supervisory(SaccrInput.Trade x) {
        String base = switch (x.assetClass()) {
            case "IR", "FX" -> x.assetClass();
            case "CREDIT" -> x.index() ? "CREDIT.index" : "CREDIT.single";
            case "EQUITY" -> x.index() ? "EQUITY.index" : "EQUITY.single";
            default -> "ELECTRICITY".equalsIgnoreCase(x.reference()) ? "COMMODITY.ELECTRICITY" : "COMMODITY.other";
        };
        String sf = cf(x.assetClass().equals("CREDIT") ? base + ".sf." + x.creditQuality() : base + ".sf");
        if (x.transactionType().equals("BASIS")) {
            sf += "*" + cf("basis-factor");
        } else if (x.transactionType().equals("VOLATILITY")) {
            sf += "*" + cf("volatility-factor");
        }
        String rho = x.assetClass().equals("IR") || x.assetClass().equals("FX") ? "1" : cf(base + ".rho");
        return new String[] {sf, rho, cf(base + ".sigma")};
    }

    private void trades(SheetStream s) {
        s.width(0, 24).width(1, 18).widths(2, 3, 12).widths(4, 20, 14).freeze(3, 2);
        s.row(1).text(0, "Trades: each row's times, supervisory duration, adjusted notional, supervisory delta, maturity factors and effective notionals "
                + "(CRE52.30 to CRE52.53), every cell a formula over Data and Config", "title");
        s.row(3);
        for (int i = 0; i < TRADES_HEAD.length; i++) {
            s.text(i, TRADES_HEAD[i], "head");
        }
        String rate = cf("supervisory-duration-rate");
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            Saccr.Terms x = l.terms();
            SaccrInput.Trade tr = x.trade();
            int r = FIRST + i;
            String d = "Data!";
            s.row(r).formulaText(0, d + "A" + r, cached ? l.set().id() : null, "text").formulaText(1, d + "B" + r + "&\"~\"&" + d + "C" + r,
                    cached ? tr.tradeId() + "~" + tr.driver() : null, "text").formulaText(2, d + "D" + r, cached ? tr.assetClass() : null, "text")
                    .formulaText(3, d + "E" + r, cached ? tr.hedgingSet() : null, "text");
            s.formula(4, "IF(ISNUMBER(" + d + "L" + r + "),MAX(" + d + "L" + r + ",0),0)", c(x.s()), "rate");
            s.formula(5, "MAX(IF(ISNUMBER(" + d + "M" + r + ")," + d + "M" + r + "," + d + "N" + r + ")," + minYears() + ")", c(x.e()), "rate");
            s.formula(6, "MAX(" + d + "N" + r + "," + minYears() + ")", c(x.m()), "rate");
            if (Double.isFinite(x.t())) {
                s.formula(7, d + "O" + r, c(x.t()), "rate");
            } else {
                s.blank(7, "rate");
            }
            s.formula(8, "IF(OR(C" + r + "=\"IR\",C" + r + "=\"CREDIT\"),(EXP(-" + rate + "*E" + r + ")-EXP(-" + rate + "*F" + r + "))/" + rate + ",1)", c(x.sd()), "rate");
            s.formula(9, d + "K" + r + "*I" + r, c(x.d()), "money");
            String[] sup = supervisory(tr);
            s.formula(10, sup[0], c(x.sf()), "rate").formula(11, sup[1], c(x.rho()), "rate").formula(12, sup[2], c(x.sigma()), "rate");
            switch (tr.transactionType()) {
                case "OPTION" -> {
                    String lam = "IF(ISNUMBER(" + d + "T" + r + ")," + d + "T" + r + ",0)";
                    s.formula(13, "(LN((" + d + "S" + r + "+" + lam + ")/(" + d + "R" + r + "+" + lam + "))+0.5*M" + r + "^2*H" + r + ")/(M" + r + "*SQRT(H" + r + "))",
                            c(x.d1()), "rate");
                    s.formula(14, "IF(" + d + "P" + r + "=\"CALL\",1,-1)*IF(" + d + "Q" + r + "=\"BOUGHT\",1,-1)*NORMSDIST(IF(" + d + "P" + r + "=\"CALL\",N" + r + ",-N"
                            + r + "))", c(x.delta()), "rate");
                }
                case "CDO_TRANCHE" -> {
                    s.blank(13, "rate");
                    s.formula(14, "IF(" + d + "Q" + r + "=\"SOLD\",1,-1)*" + cf("cdo-delta.numerator") + "/((1+" + cf("cdo-delta.slope") + "*" + d + "U" + r + ")*(1+"
                            + cf("cdo-delta.slope") + "*" + d + "V" + r + "))", c(x.delta()), "rate");
                }
                default -> {
                    s.blank(13, "rate");
                    s.formula(14, "IF(" + d + "J" + r + "=\"LONG\",1,-1)", c(x.delta()), "rate");
                }
            }
            s.formula(15, "SQRT(MIN(G" + r + ",1))", c(x.mfUnmargined()), "rate");
            Block b = blocks.get(l.set().id());
            s.formula(16, "Main!$B$" + b.mf(), c(x.mfMargined()), "rate");
            s.formula(17, "O" + r + "*J" + r + "*P" + r, c(x.dUnmargined()), "money");
            s.formula(18, "O" + r + "*J" + r + "*Q" + r, c(x.dMargined()), "money");
            if (tr.assetClass().equals("IR")) {
                s.formula(19, "IF(F" + r + "<" + cf("ir-buckets.lower-years") + ",1,IF(F" + r + "<=" + cf("ir-buckets.upper-years") + ",2,3))", c(x.bucket()), "int");
                s.formulaText(20, "\"b\"&T" + r, cached ? x.subKey() : null, "text");
            } else {
                s.blank(19, "int");
                s.formulaText(20, tr.assetClass().equals("FX") ? "D" + r : d + "G" + r, cached ? x.subKey() : null, "text");
            }
        }
    }

    // ---- Hedging sets ----------------------------------------------------------------------------------------------------------------

    private void hedgingSets(SheetStream s) {
        s.width(0, 24).width(1, 12).width(2, 16).width(3, 22).width(4, 12).widths(5, 10, 18).freeze(3, 0);
        s.row(1).text(0, "Hedging sets: per sub-key Σ D (SUMIFS over Trades) and its add-on; per hedging set the add-on (IR with its three buckets, "
                + "FX |Σ D|, credit and equity through the systematic and idiosyncratic terms, commodity with ρ), CRE52.54 to CRE52.71", "title");
        s.row(3).text(0, "Netting set", "head").text(1, "Asset class", "head").text(2, "Hedging set", "head").text(3, "Sub-key", "head").text(4, "Row", "head")
                .text(5, "Σ D unmargined / EN", "head").text(6, "Σ D margined / EN", "head").text(7, "SF", "head").text(8, "ρ", "head")
                .text(9, "AddOn unmargined", "head").text(10, "AddOn margined", "head");
        String tA = range("Trades", 0);
        String tC = range("Trades", 2);
        String tD = range("Trades", 3);
        String tU = range("Trades", 20);
        String tRu = range("Trades", 17);
        String tRm = range("Trades", 18);
        for (HsRow x : hsRows) {
            Saccr.HedgingSet h = x.hs();
            int r = x.row();
            s.row(r).text(0, x.set(), "text").text(1, h.assetClass(), "text").text(2, h.hedgingSet(), "text");
            String crit = tA + "," + q(x.set()) + "," + tC + "," + q(h.assetClass()) + "," + tD + "," + q(h.hedgingSet());
            String sample = x.sampleTradeRow() < 0 ? null : "Trades!K" + x.sampleTradeRow();
            String rhoRef = x.sampleTradeRow() < 0 ? "1" : "Trades!L" + x.sampleTradeRow();
            if (x.key() != null) {
                Saccr.SubKey k = x.key();
                s.text(3, k.key(), "text").text(4, "sub-key", "text");
                s.formula(5, "SUMIFS(" + tRu + "," + crit + "," + tU + "," + q(k.key()) + ")", c(k.dUnmargined()), "money");
                s.formula(6, "SUMIFS(" + tRm + "," + crit + "," + tU + "," + q(k.key()) + ")", c(k.dMargined()), "money");
                s.formula(7, sample == null ? "0" : sample, c(k.sf()), "rate");
                s.formula(8, rhoRef, c(k.rho()), "rate");
                if (!h.assetClass().equals("IR")) {
                    s.formula(9, h.assetClass().equals("FX") ? "H" + r + "*ABS(F" + r + ")" : "H" + r + "*F" + r, c(k.addOnUnmargined()), "money");
                    s.formula(10, h.assetClass().equals("FX") ? "H" + r + "*ABS(G" + r + ")" : "H" + r + "*G" + r, c(k.addOnMargined()), "money");
                }
                continue;
            }
            s.text(3, "(the hedging set)", "label").text(4, "hedging set", "label");
            int a = x.keyFirst();
            int z = x.keyLast();
            switch (h.assetClass()) {
                case "IR" -> {
                    String on = "Config!$B$" + SW_OFFSET + "=\"on\"";
                    for (int k = 0; k < 2; k++) {
                        String cl = k == 0 ? "F" : "G";
                        String d1 = cl + a;
                        String d2 = cl + (a + 1);
                        String d3 = cl + (a + 2);
                        String en = "IF(" + on + ",SQRT(MAX(0," + d1 + "^2+" + d2 + "^2+" + d3 + "^2+" + cf("ir-bucket-weights.b1b2") + "*" + d1 + "*" + d2 + "+"
                                + cf("ir-bucket-weights.b2b3") + "*" + d2 + "*" + d3 + "+" + cf("ir-bucket-weights.b1b3") + "*" + d1 + "*" + d3 + ")),ABS(" + d1
                                + ")+ABS(" + d2 + ")+ABS(" + d3 + "))";
                        s.formula(5 + k, en, c(k == 0 ? h.enUnmargined() : h.enMargined()), "money");
                    }
                    s.formula(7, sample == null ? "0" : sample, c(h.subKeys().isEmpty() ? Double.NaN : h.subKeys().get(0).sf()), "rate");
                    s.formula(9, "H" + r + "*F" + r, c(h.addOnUnmargined()), "money.bold");
                    s.formula(10, "H" + r + "*G" + r, c(h.addOnMargined()), "money.bold");
                }
                case "FX" -> {
                    s.formula(9, "SUM(J" + a + ":J" + z + ")", c(h.addOnUnmargined()), "money.bold");
                    s.formula(10, "SUM(K" + a + ":K" + z + ")", c(h.addOnMargined()), "money.bold");
                }
                case "COMMODITY" -> {
                    String rho = "I" + a;
                    for (int k = 0; k < 2; k++) {
                        String ao = (k == 0 ? "J" : "K") + a + ":" + (k == 0 ? "J" : "K") + z;
                        s.formula(9 + k, "SQRT(MAX(0,(" + rho + "*SUM(" + ao + "))^2+(1-" + rho + "^2)*SUMPRODUCT(" + ao + "," + ao + ")))",
                                c(k == 0 ? h.addOnUnmargined() : h.addOnMargined()), "money.bold");
                    }
                }
                default -> {
                    String rho = "I" + a + ":I" + z;
                    for (int k = 0; k < 2; k++) {
                        String ao = (k == 0 ? "J" : "K") + a + ":" + (k == 0 ? "J" : "K") + z;
                        s.formula(9 + k, "SQRT(MAX(0,SUMPRODUCT(" + rho + "," + ao + ")^2+SUMPRODUCT((1-" + rho + "*" + rho + ")*" + ao + "*" + ao + ")))",
                                c(k == 0 ? h.addOnUnmargined() : h.addOnMargined()), "money.bold");
                    }
                }
            }
        }
    }

    // ---- Explain, Provenance -----------------------------------------------------------------------------------------------------

    private void explain(SheetStream s) {
        s.width(0, 44).width(1, 150);
        s.row(1).text(0, com.ash.drishti.common.Branding.current().productOr("") + " · SA-CCR Playground · how each figure is worked out", "title");
        s.blank(1, "title");
        int row = 3;
        for (String[] step : t.explain()) {
            s.row(row++).text(0, step[0], "label.wrap").text(1, step[1], "wrap");
        }
    }

    private void provenance(SheetStream s) {
        s.width(0, 38).width(1, 120);
        s.row(1).text(0, "Provenance", "title");
        s.blank(1, "title");
        Map<String, Object> prov = new LinkedHashMap<>(run.provenance());
        List<String> refused = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (SaccrService.SetRun x : run.sets()) {
            if (x.result() != null) {
                x.result().refusals().forEach(f -> refused.add(x.id() + " · " + f.tradeId() + "~" + f.driver() + " · " + f.reason()));
            }
            if (x.excluded() > 0 || !x.unclassified().isEmpty() || x.result() == null) {
                excluded.add(x.id() + ": " + x.status() + ", excluded " + x.excluded() + ", unclassified " + x.unclassified() + (x.flags().isEmpty() ? ""
                        : ", flags " + x.flags()));
            }
        }
        prov.put("Refused rows (DRS-5410)", refused);
        prov.put("Excluded and unclassified members, by set", excluded);
        prov.put("Rows derived by rules", lines.stream().filter(l -> "rules".equals(String.valueOf(l.terms().trade().attributes().get("DERIVED_BY")))).count());
        prov.put("Exported by", user + " at " + now);
        int row = 3;
        for (Map.Entry<String, Object> en : prov.entrySet()) {
            Object v = en.getValue();
            s.row(row++).text(0, en.getKey(), "label");
            if (v instanceof List<?> list) {
                s.text(1, list.isEmpty() ? "none" : String.valueOf(list.size()), "text");
                for (Object o : list) {
                    s.row(row++).text(1, String.valueOf(o), "text");
                }
            } else {
                s.text(1, v == null ? "" : String.valueOf(v), "text");
            }
        }
    }
}
