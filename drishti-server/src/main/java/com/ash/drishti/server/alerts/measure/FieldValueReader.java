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
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads a field of a Sutra entity as one person: the document is fetched, the person's field masks are applied, and the field (a path such as {@code mtm} or
 * {@code limits.utilisation}, or any Rachana-EL expression over the entity) is evaluated on the masked copy. A masked field is a masked reading, never a value.
 */
public final class FieldValueReader {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private final SourceRouter router;
    private final ElCompiler el;
    private final Formats formats;
    private final Entitlements entitlements;

    public FieldValueReader(SourceRouter router, ElCompiler el, Formats formats, Entitlements entitlements) {
        this.router = router;
        this.el = el;
        this.formats = formats;
        this.entitlements = entitlements;
    }

    /** The expression for a field entry: a plain path gets the {@code $.} prefix. */
    public static String expression(String field) {
        String f = field.strip();
        return f.startsWith("$") || f.matches(".*[()+*/<>=!?\\s-].*") ? f : "$." + f;
    }

    /** Throws {@code DRS-2xxx} when the expression does not compile (used when an alert is saved). */
    public void check(String field) throws ElException {
        el.compile(expression(field));
    }

    public Reading read(AlertSpec spec, Principal who, LocalDate date) {
        AlertSpec.Source s = spec.source();
        if (!entitlements.mayOpen(who, s.kind())) {
            return Reading.error("you cannot open " + s.kind() + " any more");
        }
        EntityDocument doc;
        try {
            doc = router.fetch(EntityRef.of(s.kind(), s.id()), date == null ? AsOf.LATEST : AsOf.of(date)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reading.error("interrupted");
        } catch (TimeoutException e) {
            return Reading.error("the source did not answer in " + WAIT.toSeconds() + " s");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return c instanceof DrishtiException de && de.errorCode() == com.ash.drishti.common.ErrorCode.ENTITY_NOT_FOUND ? Reading.noData(s.kind() + "/" + s.id() + " is not there")
                    : Reading.error(c.getMessage());
        }
        if (doc.deleted()) {
            return Reading.noData(s.kind() + "/" + s.id() + " was deleted");
        }
        try {
            Object v = el.compile(expression(s.field())).eval(EvalContext.of(entitlements.redactor(who).apply(doc.data()), formats));
            if (Values.masked(v)) {
                return Reading.masked(s.field());
            }
            if (Values.isNull(v)) {
                return Reading.noData(s.field() + " is empty");
            }
            if (!Values.isNumber(v)) {
                return Reading.error(s.field() + " is not a number");
            }
            return Reading.ok(Values.number(v), doc.provenance() == null ? null : doc.provenance().businessDate());
        } catch (RuntimeException e) {
            return Reading.error(e.getMessage());
        }
    }
}
