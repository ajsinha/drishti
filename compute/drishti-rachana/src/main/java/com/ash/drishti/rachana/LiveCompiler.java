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

import com.ash.rachana.el.ElCompiler;
import com.ash.rachana.el.Expr;
import com.ash.rachana.el.Template;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one holder of the expression compiler every reader of the server takes (the Spring bean): {@link #get()} is always the compiler of
 * {@link DrishtiRachana#current()}, so a module started later is seen at the next compile and a module stopped is gone at once, as
 * {@code Formats.live} is for the format table. Nothing here keeps an older instance: what is built on a compiler (a binder, a screen's
 * wiring) is held through {@link #follow}, which is dropped the moment the instance changes, so a stopped module's class loader is never
 * reachable from here. {@link #of(ElCompiler)} fixes one compiler (a test, a benchmark, a tool without a server).
 */
public final class LiveCompiler implements Supplier<ElCompiler> {

    private static final LiveCompiler CURRENT = new LiveCompiler(null);

    private final ElCompiler fixed;

    private LiveCompiler(ElCompiler fixed) {
        this.fixed = fixed;
    }

    /** The server's holder: the current instance's compiler at every call. */
    public static LiveCompiler current() {
        return CURRENT;
    }

    /** A holder of one fixed compiler (a test, a benchmark, a tool). */
    public static LiveCompiler of(ElCompiler compiler) {
        return new LiveCompiler(Objects.requireNonNull(compiler, "compiler"));
    }

    /** The compiler now. */
    @Override
    public ElCompiler get() {
        return fixed != null ? fixed : DrishtiRachana.compiler();
    }

    /** An expression compiled by the compiler now. */
    public Expr compile(String source) {
        return get().compile(source);
    }

    /** A template compiled by the compiler now. */
    public Template template(String source) {
        return get().template(source);
    }

    /**
     * Something built on a compiler (a binder, a screen's wiring), built again when the instance changes and forgotten as it changes (never
     * holding the older instance).
     */
    public <T> Supplier<T> follow(Function<ElCompiler, T> make) {
        Follower<T> f = new Follower<>(this, make);
        if (fixed == null) {
            DrishtiRachana.follow(f);
        }
        return f;
    }

    /** What {@link #follow} returns. */
    static final class Follower<T> implements Supplier<T> {

        private record Built<T>(ElCompiler el, T value) {}

        private final LiveCompiler holder;
        private final Function<ElCompiler, T> make;
        private volatile Built<T> built;

        Follower(LiveCompiler holder, Function<ElCompiler, T> make) {
            this.holder = holder;
            this.make = make;
        }

        @Override
        public T get() {
            ElCompiler el = holder.get();
            Built<T> b = built;
            if (b == null || b.el() != el) {
                synchronized (this) {
                    b = built;
                    if (b == null || b.el() != el) {
                        b = new Built<>(el, make.apply(el));
                        built = b;
                    }
                }
            }
            return b.value();
        }

        /** The instance changed: drop what was built on the older one. */
        void forget() {
            built = null;
        }
    }
}
