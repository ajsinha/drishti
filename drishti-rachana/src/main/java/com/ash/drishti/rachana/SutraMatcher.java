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
package com.ash.drishti.rachana;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Sutra;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * The trace behind {@link #match}: every Sutra of the kind with what its {@code where} gives for {@code doc}.
     * {@code chosen} is what {@code match} returns for {@code doc} (a {@code where} that cannot be
     * evaluated counts as not holding here, where {@code match} would fail).
     */
    public MatchTrace explain(String kind, DataNode doc) {
        return explain(kind, doc, doc);
    }

    /**
     * As {@link #explain(String, DataNode)} for a caller who sees {@code seen} (the document with their field masks): a
     * {@code where} whose answer differs between the stored and the seen document reads {@link MatchTrace.Result#MASKED}, so
     * the trace never tells more than the view does.
     */
    public MatchTrace explain(String kind, DataNode doc, DataNode seen) {
        EvalContext stored = EvalContext.of(doc, formats);
        EvalContext visible = seen == doc ? stored : EvalContext.of(seen, formats);
        List<MatchTrace.Candidate> out = new ArrayList<>();
        Sutra chosen = null;
        for (Sutra s : registry.forKind(kind)) {
            MatchTrace.Result r = evaluate(s.match().where(), stored);
            if (visible != stored && r != evaluate(s.match().where(), visible)) {
                r = MatchTrace.Result.MASKED;
            }
            if (chosen == null && (r == MatchTrace.Result.TRUE || r == MatchTrace.Result.MASKED && holds(s.match().where(), stored))) {
                chosen = s;
            }
            out.add(new MatchTrace.Candidate(s, r));
        }
        return new MatchTrace(Optional.ofNullable(chosen), out);
    }

    private MatchTrace.Result evaluate(String where, EvalContext ctx) {
        if (where == null) {
            return MatchTrace.Result.TRUE;
        }
        try {
            return Values.truthy(compiler.compile(where).eval(ctx)) ? MatchTrace.Result.TRUE : MatchTrace.Result.FALSE;
        } catch (RuntimeException | StackOverflowError e) {
            return MatchTrace.Result.ERROR;
        }
    }

    private boolean holds(String where, EvalContext ctx) {
        return evaluate(where, ctx) == MatchTrace.Result.TRUE;
    }
}
