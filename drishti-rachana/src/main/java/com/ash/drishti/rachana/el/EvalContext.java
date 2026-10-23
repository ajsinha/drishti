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
package com.ash.drishti.rachana.el;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.format.Formats;

/**
 * What an expression can see: the document ({@code $}), the current row ({@code @}) and its index
 * ({@code #index}), and the named formats for {@code fmt(...)}; on a Sutra Next screen also the events panels emitted
 * ({@code @<panel>.<event>.<field>}, {@code @parent...}) and the panel's own source record ({@code $src}), held in {@link Scope}.
 * Immutable; derive a row context with {@link #withRow}.
 *
 * @param root the document
 * @param row the current row or element, or null outside a row
 * @param index position of the row, or -1
 * @param formats named formats
 * @param scope events and source of the panel being bound (never null)
 */
public record EvalContext(DataNode root, Object row, int index, Formats formats, java.util.Map<String, Object> ctl, java.time.LocalDate today,
        Scope scope) {

    /**
     * What a panel of a Sutra Next screen reads besides the document.
     *
     * @param events per panel id, what it emitted: a mapping of event name to payload (absent until the event fires)
     * @param parent the parent panel's events, for a child panel ({@code @parent}); null elsewhere
     * @param src the record or records the panel's {@code source:} names ({@code $src}); null when it has none
     */
    public record Scope(java.util.Map<String, DataNode> events, DataNode parent, Object src) {
        public static final Scope NONE = new Scope(java.util.Map.of(), null, null);

        public Scope {
            events = events == null ? java.util.Map.of() : events;
        }

        /** The same events with {@code next} as the source record. */
        public Scope withSrc(Object next) {
            return new Scope(events, parent, next);
        }
    }

    public EvalContext {
        ctl = ctl == null ? java.util.Map.of() : ctl;
        scope = scope == null ? Scope.NONE : scope;
    }

    public EvalContext(DataNode root, Object row, int index, Formats formats, java.util.Map<String, Object> ctl, java.time.LocalDate today) {
        this(root, row, index, formats, ctl, today, Scope.NONE);
    }

    public EvalContext(DataNode root, Object row, int index, Formats formats, java.util.Map<String, Object> ctl) {
        this(root, row, index, formats, ctl, null);
    }

    public EvalContext(DataNode root, Object row, int index, Formats formats) {
        this(root, row, index, formats, java.util.Map.of());
    }

    /** The same context for a view of this business date: {@code today()} reads it (null outside a view). */
    public EvalContext withToday(java.time.LocalDate date) {
        return new EvalContext(root, row, index, formats, ctl, date, scope);
    }

    public static EvalContext of(DataNode root, Formats formats) {
        return new EvalContext(root, null, -1, formats);
    }

    public EvalContext withRow(Object row, int index) {
        return new EvalContext(root, row, index, formats, ctl, today, scope);
    }

    /**
     * The same context with the values of a Sutra's controls, read in expressions as {@code ctl.<id>}: text for most kinds, a
     * list of text for a multi-select, a whole number for top-n, {@code from..to} text for a range.
     */
    public EvalContext withControls(java.util.Map<String, Object> values) {
        return new EvalContext(root, row, index, formats, values, today, scope);
    }

    /** The same context reading {@code next} as its events and source. */
    public EvalContext withScope(Scope next) {
        return new EvalContext(root, row, index, formats, ctl, today, next);
    }

    /** The same context over another document, keeping the controls, the date and the events. */
    public EvalContext withRoot(DataNode next) {
        return new EvalContext(next, null, -1, formats, ctl, today, scope);
    }

    /** What a panel's {@code source:} named, read as {@code $src}; null when it names none. */
    public Object src() {
        return scope.src();
    }

    /** What {@code panel} emitted ({@code @<panel>}), or for {@code parent} the parent panel's; missing until it fires. */
    public DataNode event(String panel) {
        DataNode d = "parent".equals(panel) ? scope.parent() : scope.events().get(panel);
        return d == null ? DataNode.missing() : d;
    }
}
