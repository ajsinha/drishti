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
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.engine.ViewAugmenter;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The market-risk half of a trade page's Risk links (the UI kit fragment {@code risk-links@1}, VAR_GUIDE.md section 3.7): when the VaR
 * engine's date holds the trade's P&amp;L vector, the view of the trade carries {@code PNL_VECTOR_ID} ({@code PNL-<trade>-<scenario set>}
 * since 2.9.5; {@code PV-<trade>-<scenario set>} on a date whose lake rows still carry the earlier ids),
 * the market-risk pack's {@code pnl-vector}; a trade sent as scenario PVs carries {@code SCENARIO_PV_ID}, {@code SPV-<trade>-<set>} of the
 * {@code scenario-pv} kind, instead) and {@code VAR_RESULT_ID} ({@code TRD:<trade>}, the computed kind {@code var-result}), links
 * the pack's graph names, so they are real links of the page (its linked entities, its keys); when it does not, {@code RISK_LINKS} says
 * why ("not live on 2026-10-26: its status is CANCELLED; no P&amp;L vector"). The stored trade never changes; nothing is computed here but
 * a lookup of the trade in the loaded date. With them (2.9.4) {@code RISK_LINKS} says the trade's currency and its base PV in it
 * ({@link #currency}), which the trade pages' strip shows as Trade currency.
 */
final class VarTradeLinks implements ViewAugmenter {

    static final String FIELD = "RISK_LINKS";
    private final VarService svc;

    VarTradeLinks(VarService svc) {
        this.svc = svc;
    }

    @Override
    public boolean augments(String kind) {
        return "trade".equals(kind);
    }

    @Override
    public EntityDocument augment(EntityDocument doc, AsOf asOf) {
        if (!(doc.data() instanceof DataNode.Obj o) || doc.deleted()) {
            return doc;
        }
        String id = o.get("TRADE_ID").isNull() ? doc.ref().id() : o.get("TRADE_ID").asText();
        Map<String, DataNode> fields = new LinkedHashMap<>(o.fields());
        Map<String, Object> links = new LinkedHashMap<>();
        if (o.get(FIELD) instanceof DataNode.Obj prior) {
            prior.fields().forEach((k, v) -> links.put(k, v.isNull() ? null : v.asText()));
        }
        try {
            if (!svc.props().enabled()) {
                throw new DrishtiException(com.ash.drishti.common.ErrorCode.FORBIDDEN, "the VaR engine is off on this server");
            }
            java.util.Optional<VarDate> loaded = svc.loadedDate(asOf == null ? null : asOf.businessDate());
            if (loaded.isEmpty()) {
                throw new DrishtiException(com.ash.drishti.common.ErrorCode.ENTITY_NOT_FOUND, "the VaR engine has not loaded the day's vectors yet "
                        + "(open the page again in a moment, or VAR TRD " + id + ")");
            }
            VarDate d = loaded.get();
            LocalDate day = d.date();
            int o2 = d.book().tradeOrdinal(id) >= 0 ? d.book().tradeOrdinal(id) : d.book().tradeOrdinalAnyCase(id);
            links.put("BUSINESS_DATE", day.toString());
            if (o2 >= 0) {
                String tid = d.book().tradeId(o2);
                if (d.intake().fromScenarioPv(tid)) {                   // sent as scenario PVs (2.9.4): their row, not a P&L vector
                    String spv = "SPV-" + tid + "-" + d.book().scenarioSet();
                    fields.put("SCENARIO_PV_ID", DataNode.of(spv));
                    links.put("SCENARIO_PV", spv);
                } else {
                    String pv = d.intake().pnlIdPrefix() + tid + "-" + d.book().scenarioSet();
                    fields.put("PNL_VECTOR_ID", DataNode.of(pv));
                    links.put("PNL_VECTOR", pv);
                }
                fields.put("VAR_RESULT_ID", DataNode.of("TRD:" + tid));
                links.put("VAR", "TRD:" + tid);
                currency(d, o2, links);
            } else if (d.intake().positionOf(id) != null || positionGrain(d, doc)) {
                // its source system sends PVs per position (VAR_GUIDE.md section 15.13): no trade-level VaR, a link to its position
                String pos = d.intake().positionOf(id);
                String note = "its source system sends PVs per position on " + day + (pos == null ? "" : ": the trade is in the supplied position "
                        + pos) + "; there is no trade-level P&L vector";
                links.put("PNL_VECTOR_NOTE", note);
                links.put("VAR_NOTE", note + ", so no trade-level VaR" + (pos == null ? "" : " (see POS:" + pos + ")"));
                if (pos != null) {
                    links.put("POSITION", pos);
                    fields.put("POSITION_ID", DataNode.of(pos));
                }
            } else {
                String why = d.intake().notLiveWhy(id);
                String note = "not live on " + day + (why == null ? "" : ": " + why) + "; no P&L vector";
                links.put("PNL_VECTOR_NOTE", note);
                links.put("VAR_NOTE", note + ", so no VaR");
            }
        } catch (DrishtiException | IllegalArgumentException e) {
            String why = String.valueOf(e.getMessage()).replaceFirst("^DRS-\\d+ ", "");
            links.put("PNL_VECTOR_NOTE", "no P&L vector: " + why);
            links.put("VAR_NOTE", "no VaR: " + why);
        }
        fields.put(FIELD, DataNode.of(links));
        return new EntityDocument(doc.ref(), new DataNode.Obj(fields), doc.provenance(), doc.deleted());
    }

    /** True when the trade's source system sent its records at position grain on the date. */
    private static boolean positionGrain(VarDate d, EntityDocument doc) {
        DataNode sys = doc.data() instanceof DataNode.Obj o ? o.get("SOURCE_SYSTEM") : null;
        String in = sys == null || sys.isNull() ? null : d.intake().inputOf(sys.asText());
        return in != null && in.endsWith("position");
    }

    /**
     * The trade's currency and its base PV in it (VAR_GUIDE.md section 15.11): a trade sent as scenario PVs is in their currency, its base
     * PV its USD value today at today's rate (the received {@code BASE_PV}, to rounding); a P&amp;L vector's trade is in its own
     * {@code CURRENCY}, its base PV the vector's {@code PV} (USD). {@code TRADE_CURRENCY}, {@code TRADE_CURRENCY_FROM}, {@code BASE_PV}.
     */
    static void currency(VarDate d, int o, Map<String, Object> links) {
        com.ash.drishti.compute.var.Columns tc = d.book().trades();
        String[] ccy = VarViews.tradeCurrency(tc, o);
        if (ccy[0] == null) {
            return;
        }
        links.put("TRADE_CURRENCY", ccy[0]);
        boolean fromPv = "scenario PVs".equals(ccy[1]);
        links.put("TRADE_CURRENCY_FROM", fromPv ? "its scenario PVs" : "the trade's own currency (TRADE_CURRENCY, else CURRENCY; its P&L vector in USD)");
        double pv = tc.has("PV") ? tc.number("PV", o) : Double.NaN;
        if (Double.isNaN(pv)) {
            return;
        }
        double rate = 1;
        if (fromPv && d.fx().get(ccy[0]) instanceof Map<?, ?> r && r.get("base") instanceof Number n && n.doubleValue() > 0) {
            rate = n.doubleValue();
        }
        String in = fromPv ? ccy[0] : VarDate.USD;
        links.put("BASE_PV", in + " " + String.format(java.util.Locale.ROOT, "%,.2f", pv / rate));
    }
}
