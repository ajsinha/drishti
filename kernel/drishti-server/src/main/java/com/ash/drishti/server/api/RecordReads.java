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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.history.HistoryService;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.api.HistoryController.Point;
import com.ash.drishti.server.api.HistoryController.Series;
import com.ash.drishti.server.api.HistoryController.ViewModelRef;
import com.ash.drishti.server.security.Entitlements;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Component;

/**
 * One record as a person may see it: the bodies of {@code GET /api/v1/entities/{kind}/{id}/raw} and
 * {@code GET /api/v1/history/{kind}/{id}/series}, called by their controllers and by the kernel's typed {@code Records.raw} and
 * {@code History.series} alike (KERNEL_AND_MODULES.md section 2.3), so the two answer the same.
 */
@Component
public class RecordReads {

    private final SourceRouter router;
    private final BusinessDates dates;
    private final Entitlements entitlements;
    private final com.ash.drishti.rachana.el.ElCompiler el;
    private final com.ash.drishti.rachana.format.Formats formats;
    private final HistoryService history;

    public RecordReads(SourceRouter router, BusinessDates dates, Entitlements entitlements, com.ash.drishti.rachana.el.ElCompiler el,
            com.ash.drishti.rachana.format.Formats formats, HistoryService history) {
        this.router = router;
        this.dates = dates;
        this.entitlements = entitlements;
        this.el = el;
        this.formats = formats;
        this.history = history;
    }

    /** The record whole on the date, masked for the person; refused when the kind is closed to them or no source holds it. */
    public ApiDtos.RawEntity raw(String kind, String id, AsOf asOf, Principal principal) {
        entitlements.requireOpen(principal, kind);
        try {
            EntityDocument d = router.fetch(EntityRef.of(kind, id), asOf).join();
            return new ApiDtos.RawEntity(new ViewModel.Ref(kind, id), d.provenance(), entitlements.redact(principal, d.data()));
        } catch (CompletionException e) {
            throw e.getCause() instanceof DrishtiException de ? de : new DrishtiException(ErrorCode.SOURCE_FAILED, e.getMessage());
        }
    }

    /**
     * One field's value on each of the last {@code days} business days up to {@code to} (null: the date box), read through the normal
     * routing for each day, as the person may see it. {@code path} is any Rachana-EL expression over the document.
     */
    public Series series(String kind, String id, String path, int days, String to, AsOf current, Principal principal) {
        entitlements.requireOpen(principal, kind);
        String expr = path.startsWith("$") ? path : "$." + path;
        com.ash.drishti.rachana.el.Expr compiled;
        try {
            compiled = el.compile(expr);
        } catch (RuntimeException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot read the path '" + path + "': " + e.getMessage());
        }
        int n = Math.max(2, Math.min(history.limits().maxDays(), days));
        LocalDate last = to == null ? (current.businessDate() == null ? dates.current() : current.businessDate()) : LocalDate.parse(to);
        java.util.ArrayDeque<LocalDate> wanted = new java.util.ArrayDeque<>();
        for (LocalDate d = last; wanted.size() < n && !d.isBefore(dates.earliest()); d = dates.calendar().previous(d)) {
            wanted.addFirst(d);
        }
        EntityRef ref = EntityRef.of(kind, id);
        List<LocalDate> list = List.copyOf(wanted);
        List<CompletableFuture<EntityDocument>> reads = list.stream().map(d -> router.fetch(ref, AsOf.of(d))).toList();
        List<Point> points = new java.util.ArrayList<>();
        boolean dated = false;
        for (int i = 0; i < list.size(); i++) {
            EntityDocument doc;
            try {
                doc = reads.get(i).join();
            } catch (CompletionException e) {
                points.add(new Point(list.get(i), null, null, null, null));      // not there that day
                continue;
            }
            dated |= doc.provenance().businessDate() != null;
            Object v;
            try {
                v = com.ash.drishti.rachana.el.Values.simplify(compiled.eval(com.ash.drishti.rachana.el.EvalContext.of(
                        entitlements.redact(principal, doc.data()), formats)));
                if (v instanceof com.ash.drishti.api.DataNode node) {
                    v = node.isNull() ? null : node.unwrap();
                }
            } catch (RuntimeException e) {
                v = null;
            }
            points.add(new Point(list.get(i), v, doc.provenance().businessDate(), doc.provenance().source(), doc.provenance().generation()));
        }
        return new Series(new ViewModelRef(kind, id), expr, HistoryController.label(expr.substring(2)), points, dated);
    }
}
