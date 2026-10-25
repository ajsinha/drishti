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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who sees what of the VaR engine (VAR_ENGINE.md section 12.1). Each role is given, per node of a hierarchy, one of two levels
 * ({@code drishti.var.access.<role>[<node>]}): {@code positions} (the node's numbers, its children, its positions and their trades) or
 * {@code aggregate} (the node's numbers and every node beneath it, never a position or a trade). A role with no entry sees every node
 * at {@code positions} level when it may open the vector kind; a caller who may not open it sees nothing.
 *
 * <p>A scope is computed over the trades the viewer may see; the rest are excluded and counted. A scope naming positions, trades or a
 * rule must name only trades the viewer sees at {@code positions} level (otherwise a rule could probe single trades through aggregates).
 */
final class VarAccess {

    /**
     * What one viewer may see on one date.
     *
     * @param visible the trades whose numbers count for the viewer (either level)
     * @param positions the trades the viewer may see one by one (positions level)
     * @param full every trade at positions level (no restriction)
     * @param signature the cache key part: equal for viewers who see the same
     */
    record View(OrdinalSet visible, OrdinalSet positions, boolean full, String signature) {
        boolean seesPositions(OrdinalSet set) {
            return full || positions.containsAll(set);
        }
    }

    private final VarProperties props;
    private final Entitlements entitlements;
    private final Map<String, View> views = new ConcurrentHashMap<>();

    VarAccess(VarProperties props, Entitlements entitlements) {
        this.props = props;
        this.entitlements = entitlements;
    }

    /** The viewer's view of the date (cached per date and role set); refused when the caller may not open the vectors at all. */
    View view(Principal p, VarDate date) {
        if (!entitlements.mayOpen(p, props.kinds().vector())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "your role may not see VaR (the kind " + props.kinds().vector() + ")");
        }
        Map<String, String> levels = levels(p);
        String sig = date.date() + "@" + date.version() + "|" + new java.util.TreeMap<>(levels);
        return views.computeIfAbsent(sig, s -> build(levels, date.book(), s));
    }

    /** The node levels the caller's roles give (a later role widens: positions beats aggregate); empty: no restriction. */
    Map<String, String> levels(Principal p) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String role : p.roles() == null ? java.util.List.<String>of() : p.roles()) {
            Map<String, String> m = props.access().get(role);
            if (m == null) {
                continue;
            }
            m.forEach((node, level) -> {
                String l = level.trim().toLowerCase(Locale.ROOT);
                if (l.equals("positions") || !out.containsKey(node)) {
                    out.put(node, l.equals("positions") ? "positions" : "aggregate");
                }
            });
        }
        return out;
    }

    private static View build(Map<String, String> levels, VarBook book, String sig) {
        if (levels.isEmpty()) {
            OrdinalSet all = book.all();
            return new View(all, all, true, "all|" + sig);
        }
        OrdinalSet visible = OrdinalSet.empty();
        OrdinalSet positions = OrdinalSet.empty();
        for (Map.Entry<String, String> e : levels.entrySet()) {
            VarBook.Node n = book.node(e.getKey());
            if (n == null) {
                continue;
            }
            visible = visible.or(n.members());
            if (e.getValue().equals("positions")) {
                positions = positions.or(n.members());
            }
        }
        return new View(visible, positions, false, sig);
    }

    /** Whether the viewer sees a node's numbers (some of its trades are visible). */
    static boolean seesNode(View v, VarBook.Node n) {
        return v.full() || v.visible().intersects(n.members());
    }
}
