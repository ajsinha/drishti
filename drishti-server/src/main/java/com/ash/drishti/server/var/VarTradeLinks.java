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
 * engine's date holds the trade's P&amp;L vector, the view of the trade carries {@code PNL_VECTOR_ID} ({@code PV-<trade>-<scenario set>},
 * the market-risk pack's {@code pnl-vector}) and {@code VAR_RESULT_ID} ({@code TRD:<trade>}, the computed kind {@code var-result}), links
 * the pack's graph names, so they are real links of the page (its linked entities, its keys); when it does not, {@code RISK_LINKS} says
 * why ("not live on 2026-10-26: its status is CANCELLED; no P&amp;L vector"). The stored trade never changes; nothing is computed here but
 * a lookup of the trade in the loaded date.
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
                String pv = "PV-" + tid + "-" + d.book().scenarioSet();
                fields.put("PNL_VECTOR_ID", DataNode.of(pv));
                fields.put("VAR_RESULT_ID", DataNode.of("TRD:" + tid));
                links.put("PNL_VECTOR", pv);
                links.put("VAR", "TRD:" + tid);
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
}
