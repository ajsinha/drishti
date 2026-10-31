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
package com.ash.drishti.server.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.compute.var.ScenarioFx;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The scenario matrix's lake read (VAR_GUIDE.md section 3.9): one trade's, or one supplied position's, record of one business date read
 * by its id ({@code PNL-<id>-<set>}, else {@code PV-<id>-<set>} in the P&amp;L vectors; {@code SPV-<id>-<set>} in the scenario PVs), as
 * the person (the kind's entitlement and their masks), checked as the engine's intake checks it. A date that is not open in memory is
 * read this way: 509 numbers, never the whole date. What cannot be used is a {@link Column#gap gap} with its DRS reason, never a zero.
 */
final class VarMatrixLake {

    /** The statuses that count on a date (the intake's). */
    private static final Set<String> LIVE = Set.of("NEW", "VALIDATED", "AMENDED");

    /**
     * One date's read of the entity.
     *
     * @param pnl the P&amp;L under each scenario of the window, in the reporting currency (null: a gap)
     * @param basePv the value today in the reporting currency (NaN: unknown)
     * @param valueType PV (sent as scenario PVs) or PNL (a P&amp;L vector)
     * @param fx the currency conversion used, in words
     * @param recordId the record read (its page is the cell's source link)
     * @param kind the record's kind
     * @param batch its BATCH_ID
     * @param generation its version in the source
     * @param gap why the date has no values ({@code DRS-xxxx why}), or null
     * @param useEngine true when the record is there but only the engine converts it (a P&amp;L vector in another currency than USD)
     */
    record Column(double[] pnl, double basePv, String valueType, String fx, String recordId, String kind, String batch, long generation,
            String gap, boolean useEngine) {

        static Column gap(String why) {
            return new Column(null, Double.NaN, null, null, null, null, null, -1, why, false);
        }
    }

    private final VarLoader loader;
    private final Entitlements entitlements;

    VarMatrixLake(VarLoader loader, Entitlements entitlements) {
        this.loader = loader;
        this.entitlements = entitlements;
    }

    /** The record of {@code id} (a trade, or a supplied position) on {@code date} in {@code ccy}, with the date's scenario set. */
    Column read(Principal p, String id, LocalDate date, VarLoader.ScenarioSet set, String ccy) {
        VarProperties.Kinds k = loader.props().kinds();
        EntityDocument doc = null;
        String kind = null;
        for (String[] c : new String[][] {{k.vector(), VarLoader.PNL_ID}, {k.vector(), VarLoader.OLD_PNL_ID}, {k.scenarioPv(), "SPV-"}}) {
            EntityRef ref = new EntityRef(c[0], c[1] + id + "-" + set.id());
            EntityDocument d;
            try {
                d = loader.router().fetchAll(List.of(ref), java.time.Duration.ofSeconds(30), AsOf.of(date)).get(ref);
            } catch (RuntimeException e) {
                d = null;                                             // a kind no connector keeps: the next form
            }
            if (d != null && d.data() != null && !d.deleted()) {
                doc = d;
                kind = c[0];
                break;
            }
        }
        if (doc == null) {
            return Column.gap("DRS-1001 no P&L vector or scenario PV of " + id + " on " + date + " (scenario set " + set.id() + ")");
        }
        try {
            entitlements.requireOpen(p, kind);
        } catch (RuntimeException e) {
            return Column.gap("DRS-5002 your role does not read " + kind);
        }
        DataNode data = entitlements.redact(p, doc.data());
        boolean fromPv = kind.equals(k.scenarioPv());
        String field = fromPv ? VarLoader.SCENARIO_PV : "PNL";
        String recordId = doc.ref().id();
        String batch = VarLoader.text(data, "BATCH_ID");
        long gen = doc.provenance() == null ? -1 : doc.provenance().generation();
        String why = refused(data, date, set, field);
        if (why != null) {
            return new Column(null, Double.NaN, fromPv ? "PV" : "PNL", null, recordId, kind, batch, gen, why, false);
        }
        DataNode arr = data.get(field);
        int full = set.full().size();
        int width = set.window().size();
        int from = arr.size() == full ? full - width : 0;
        double[] v = new double[width];
        for (int j = 0; j < width; j++) {
            v[j] = arr.get(from + j).asDouble();
        }
        String own = VarLoader.text(data, "CURRENCY") == null ? ScenarioFx.USD : VarLoader.text(data, "CURRENCY").toUpperCase(Locale.ROOT);
        String rep = VarCurrencies.normalise(ccy);
        double usdToday;
        double[] usd;
        String fx;
        if (fromPv) {
            double base = VarLoader.number(VarLoader.text(data, "BASE_PV"));
            if (Double.isNaN(base)) {
                return new Column(null, Double.NaN, "PV", null, recordId, kind, batch, gen, "DRS-5201 the scenario PVs have no BASE_PV", false);
            }
            ScenarioFx.Rates r = loader.fx(date, set.window()).rates(own).orElse(null);
            if (r == null) {
                return new Column(null, Double.NaN, "PV", null, recordId, kind, batch, gen, "DRS-5207 no scenario FX for " + own + " on " + date, false);
            }
            usd = new double[width];
            ScenarioFx.pnlFromPv(base, v, width, r, usd);
            usdToday = base * r.base();
            fx = own.equals(ScenarioFx.USD) ? "USD (no conversion)" : own + " to USD at each scenario's rate (" + r.source() + ")";
        } else {
            if (!own.equals(ScenarioFx.USD)) {                       // the engine's own conversion of a non-USD P&L vector applies
                return new Column(null, Double.NaN, "PNL", null, recordId, kind, batch, gen, null, true);
            }
            usd = v;
            usdToday = VarLoader.number(VarLoader.text(data, "PV"));
            fx = "USD (no conversion)";
        }
        if (rep.equals(ScenarioFx.USD)) {
            return new Column(usd, usdToday, fromPv ? "PV" : "PNL", fx, recordId, kind, batch, gen, null, false);
        }
        ScenarioFx.Rates to = loader.fx(date, set.window()).rates(rep).orElse(null);
        if (to == null) {
            return new Column(null, Double.NaN, fromPv ? "PV" : "PNL", null, recordId, kind, batch, gen, "DRS-5207 no scenario FX for " + rep
                    + " on " + date, false);
        }
        double[] out = new double[width];
        ScenarioFx.convert(Double.isNaN(usdToday) ? 0 : usdToday, usd, width, to, out);
        return new Column(out, Double.isNaN(usdToday) ? Double.NaN : usdToday / to.base(), fromPv ? "PV" : "PNL", fx + "; USD to " + rep
                + " at each scenario's rate (" + to.source() + ")", recordId, kind, batch, gen, null, false);
    }

    /** Why the engine would not take the record on the date (its DRS reason), or null when it would. */
    static String refused(DataNode data, LocalDate date, VarLoader.ScenarioSet set, String field) {
        String status = VarLoader.text(data, "STATUS");
        if (status != null && status.equalsIgnoreCase("failed")) {
            return "DRS-5206 the record says it failed to price (STATUS failed): no vector, never taken as zero";
        }
        String ts = VarLoader.text(data, "TRADE_STATUS");
        if (ts != null && !LIVE.contains(ts.toUpperCase(Locale.ROOT))) {
            return "DRS-5201 not live on " + date + ": its status is " + ts;
        }
        String mat = VarLoader.text(data, "MATURITY_DATE");
        if (mat != null && mat.length() >= 10 && LocalDate.parse(mat.substring(0, 10)).isBefore(date)) {
            return "DRS-5201 not live on " + date + ": it matured on " + mat.substring(0, 10);
        }
        String sid = VarLoader.text(data, "SCENARIO_SET_ID");
        if (sid != null && !sid.equals(set.id())) {
            return "DRS-5203 the record is on scenario set " + sid + "; the date's set is " + set.id();
        }
        DataNode arr = data.get(field);
        if (arr == null || arr.isMissing() || arr.isNull()) {
            return "DRS-5201 the record has no " + field;
        }
        if (arr.isMasked()) {
            return "DRS-5002 " + field + " is masked for your role";
        }
        int n = arr.size();
        if (n != set.full().size() && n != set.window().size()) {
            return "DRS-5201 wrong length: " + n + " values; the scenario set " + set.id() + " has " + set.full().size();
        }
        int from = n == set.full().size() ? set.full().size() - set.window().size() : 0;
        for (int j = from; j < n; j++) {
            DataNode x = arr.get(j);
            if (x == null || x.isNull() || x.isMissing() || Double.isNaN(x.asDouble())) {
                return "DRS-5206 no value under the scenario " + set.full().get(j) + " (never taken as zero)";
            }
        }
        return null;
    }
}
