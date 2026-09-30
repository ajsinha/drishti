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
package com.ash.drishti.sutra.el;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Compiles Sutra-EL source to {@link Expr} trees, caching by source text: the same few hundred
 * expressions are evaluated millions of times, so they are parsed once. Thread-safe.
 */
public final class ElCompiler {

    private final Cache<String, Expr> exprs;
    private final Cache<String, Template> templates;

    public ElCompiler(long maxEntries) {
        this.exprs = Caffeine.newBuilder().maximumSize(maxEntries).build();
        this.templates = Caffeine.newBuilder().maximumSize(maxEntries).build();
    }

    public ElCompiler() {
        this(10_000);
    }

    /** @throws ElException ({@code DRS-2101}) with the offset of the problem */
    public Expr compile(String source) {
        return exprs.get(source, Parser::parse);
    }

    /** Compiles text with embedded {@code ${expr}} parts. Text without {@code ${} is returned as is. */
    public Template template(String source) {
        return templates.get(source, s -> Template.parse(s, this));
    }

    public long size() {
        return exprs.estimatedSize();
    }
}
