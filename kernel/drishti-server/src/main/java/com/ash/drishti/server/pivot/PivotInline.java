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
package com.ash.drishti.server.pivot;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.edition.EditionCommands;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The pivot engine's door and its pivot of rows a caller holds: whether the engine is on here (its engine switch and the edition's
 * command words), and the body of {@code POST /api/v1/pivot/inline}, called by {@link PivotController} and by the kernel's typed
 * {@code Pivots.inline} alike (KERNEL_AND_MODULES.md section 2.3).
 */
@Component
public class PivotInline {

    private final AuditLog audit;
    private final EditionCommands edition;
    private final boolean enabled;
    private final ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines;

    public PivotInline(AuditLog audit, EditionCommands edition, @Value("${drishti.engines.pivot.enabled:true}") boolean enabled,
            ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines) {
        this.audit = audit;
        this.edition = edition;
        this.enabled = enabled;
        this.engines = engines;
    }

    /** The person as the engine sees them; refused when the pivot engine is switched off here or the edition leaves PIVOT out. */
    public Viewer on(Principal p) {
        com.ash.drishti.server.engines.EngineRegistry reg = engines.getIfAvailable();
        if (reg != null ? reg.known("pivot") && !reg.running("pivot") : !enabled) {
            throw new DrishtiException(ErrorCode.PIVOT_OFF, "the pivot engine is switched off on this server (config/engines/pivot.yaml, drishti.engines.pivot.enabled)");
        }
        if (edition != null && !edition.edition().wordOn(PivotController.WORD)) {
            throw new DrishtiException(ErrorCode.PIVOT_OFF, "PIVOT is not part of the edition '" + edition.edition().name() + "'");
        }
        return new Viewer(p.user(), p);
    }

    /**
     * A pivot of rows the caller already holds and may see: {@code {fields: [{name, type}], rows: [[...]], view}}
     * ({@link com.ash.drishti.bicompute.rows.InlinePivot}), aggregated by the engine's own functions; audited as {@code pivot.inline}.
     */
    public Map<String, Object> inline(Principal p, Map<String, Object> body) {
        Viewer v = on(p);
        com.ash.drishti.bicompute.rows.InlinePivot inline = com.ash.drishti.bicompute.rows.InlinePivot.of(body);
        Map<String, Object> a = inline.query(v);
        PivotView view = inline.view();
        audit.record(p.user(), "pivot.inline", view.source(), "rows=" + view.rows() + "; columns=" + view.columns() + "; values="
                + view.values().stream().map(PivotView.Value::measure).toList() + "; date=" + (view.date() == null ? "latest" : view.date())
                + "; rows=" + inline.rows() + "; cells=" + a.get("cellCount"));
        return a;
    }
}
