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
package com.ash.drishti.enginehost;

import com.ash.engine.Descriptor;
import com.ash.engine.host.EngineFile;
import com.ash.engine.host.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Binding an engine's inputs to tables (engine specification 10.2; ENGINE_INTEGRATION_DESIGN 4.2), for each declared input the first rule
 * that applies: (1) the engine file names {@code kinds} or a {@code table}; (2) exactly one kind of the running packs conforms to the input's
 * contract; (3) several conform: blocked, {@code ENG-2012}, listing them (the host never guesses); (4) none, the input required: blocked,
 * {@code ENG-2011}; (5) none, optional: bound to "absent". Open inputs are not bound: each request names its tables, resolved as the caller.
 * The result is shown on the Admin card: every input's tables and the rule that bound it.
 */
public final class InputBinder {

    /**
     * One input as bound. {@code kinds}: the kinds (or lake tables) whose union is the input, in order; {@code rule}: 1 to 5 (0 for an open
     * input); {@code how}: the rule in words; {@code columns}: contract column to the table's column; {@code where}: the site's row filter.
     */
    public record Bound(String input, String contract, List<String> kinds, int rule, String how, Map<String, String> columns, String where,
            boolean onOrBefore, String field) {

        /** Read on the snapshot's date, each record whole. */
        public Bound(String input, String contract, List<String> kinds, int rule, String how, Map<String, String> columns, String where) {
            this(input, contract, kinds, rule, how, columns, where, false, null);
        }


        /** False when the input is bound to "absent" (rule 5) or is open. */
        public boolean present() {
            return !kinds.isEmpty();
        }

        /** The table column a contract column is read from. */
        public String column(String contractColumn) {
            return columns.getOrDefault(contractColumn, contractColumn);
        }
    }

    /** The binding of every input, and what blocks the engine (empty: it may start). */
    public record Binding(Map<String, Bound> inputs, List<Problem> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private InputBinder() {
    }

    /**
     * Binds every input of {@code d} under {@code file}. {@code served}: whether a connector serves a kind or lake table on this node (a
     * named one that is not served is bound and reads as not present, so the engine starts and answers NOT_FOUND naming it).
     */
    public static Binding bind(Descriptor d, EngineFile file, KindContracts contracts, Predicate<String> served) {
        Map<String, Bound> out = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        d.inputs().forEach((name, in) -> {
            EngineFile.Binding site = file.inputs().get(name);
            Map<String, String> cols = site == null ? Map.of() : site.columns();
            String where = site == null ? null : site.where();
            if (in.open()) {
                out.put(name, new Bound(name, in.contract(), List.of(), 0, "open: each request names its tables, read as the caller", cols, where));
                return;
            }
            if (site != null && (!site.kinds().isEmpty() || site.table() != null)) {
                List<String> kinds = site.table() != null ? List.of(site.table()) : site.kinds();
                String what = site.table() != null ? "the lake table " + site.table() : "the kinds " + String.join(", ", kinds);
                out.put(name, new Bound(name, in.contract(), List.copyOf(kinds), 1, what + ", named in config/engines/" + file.id() + ".yaml"
                        + (site.onOrBefore() ? ", each record's latest version on or before the date" : "")
                        + (site.field() != null ? ", the items of its field " + site.field() : ""), cols, where, site.onOrBefore(), site.field()));
                return;
            }
            Map<String, String> candidates = new LinkedHashMap<>();
            contracts.conforming(in.contract()).forEach((kind, pack) -> {
                if (served.test(kind)) {
                    candidates.put(kind, pack);
                }
            });
            if (candidates.size() == 1) {
                Map.Entry<String, String> only = candidates.entrySet().iterator().next();
                out.put(name, new Bound(name, in.contract(), List.of(only.getKey()), 2, "the only kind that conforms to " + in.contract() + ": "
                        + only.getKey() + " (" + only.getValue() + ")", cols, where));
            } else if (candidates.size() > 1) {
                List<String> named = candidates.entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").toList();
                problems.add(new Problem("ENG-2012", d.id() + " needs " + name + " (" + in.contract() + "): " + String.join(" and ", named)
                        + " all conform; name one in config/engines/" + file.id() + ".yaml (inputs." + name + ".kinds)"));
            } else if (in.required()) {
                problems.add(new Problem("ENG-2011", d.id() + " needs " + name + " (" + in.contract() + "), and no kind of the packs running here "
                        + "conforms to it; name a kind or a lake table in config/engines/" + file.id() + ".yaml (inputs." + name + ")"));
            } else {
                out.put(name, new Bound(name, in.contract(), List.of(), 5, "absent: no kind conforms to " + in.contract()
                        + " and the input is optional; the outputs that need it answer not found", cols, where));
            }
        });
        return new Binding(java.util.Collections.unmodifiableMap(out), List.copyOf(problems));
    }
}
