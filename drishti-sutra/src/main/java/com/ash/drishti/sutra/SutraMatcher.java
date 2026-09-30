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
package com.ash.drishti.sutra;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.sutra.el.ElCompiler;
import com.ash.drishti.sutra.el.EvalContext;
import com.ash.drishti.sutra.el.Values;
import com.ash.drishti.sutra.format.Formats;
import com.ash.drishti.sutra.model.Sutra;
import java.util.Optional;

/**
 * Chooses the Sutra for a document: the highest-priority Sutra of the entity's kind whose {@code where}
 * predicate holds. Empty means "no Sutra": the view is built by inference alone.
 */
public final class SutraMatcher {

    private final SutraRegistry registry;
    private final ElCompiler compiler;
    private final Formats formats;

    public SutraMatcher(SutraRegistry registry, ElCompiler compiler, Formats formats) {
        this.registry = registry;
        this.compiler = compiler;
        this.formats = formats;
    }

    public Optional<Sutra> match(String kind, DataNode doc) {
        EvalContext ctx = EvalContext.of(doc, formats);
        for (Sutra s : registry.forKind(kind)) {
            String where = s.match().where();
            if (where == null || Values.truthy(compiler.compile(where).eval(ctx))) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
