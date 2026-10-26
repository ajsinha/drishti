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
package com.ash.drishti.server.exposure;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.engine.ViewAugmenter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The counterparty-risk half of a trade page's Risk links (the UI kit fragment {@code risk-links@1}, EXPOSURE_GUIDE.md section 3): when
 * the exposure engine's run has the trade's paths, the view of the trade carries {@code EXPOSURE_RESULT_ID} ({@code TRD:<trade>}, the
 * computed kind {@code exposure-result}, EXP TRD:&lt;trade&gt;), a link the pack's graph names; when it has none, {@code RISK_LINKS.EXPOSURE_NOTE}
 * says so ("no exposure paths on 2026-09-30 (run XR-...)"). Only a lookup of the trade in the loaded run.
 */
final class ExposureTradeLinks implements ViewAugmenter {

    static final String FIELD = "RISK_LINKS";
    private final ExposureService svc;

    ExposureTradeLinks(ExposureService svc) {
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
                throw new DrishtiException(com.ash.drishti.common.ErrorCode.FORBIDDEN, "the exposure engine is off on this server");
            }
            java.util.Optional<ExposureDate> loaded = svc.loadedDate(asOf == null ? null : asOf.businessDate());
            if (loaded.isEmpty()) {
                throw new DrishtiException(com.ash.drishti.common.ErrorCode.ENTITY_NOT_FOUND, "no exposure run loaded on this server");
            }
            ExposureDate d = loaded.get();
            int t = d.book().tradeOrdinal(id);
            if (t >= 0) {
                String tid = d.book().trade(t).id();
                fields.put("EXPOSURE_RESULT_ID", DataNode.of("TRD:" + tid));
                links.put("EXPOSURE", "TRD:" + tid);
            } else {
                links.put("EXPOSURE_NOTE", "no exposure paths on " + d.date() + " (run " + d.book().runId() + ")");
            }
        } catch (DrishtiException | IllegalArgumentException e) {
            links.put("EXPOSURE_NOTE", "no exposure paths: " + e.getMessage());
        }
        fields.put(FIELD, DataNode.of(links));
        return new EntityDocument(doc.ref(), new DataNode.Obj(fields), doc.provenance(), doc.deleted());
    }
}
