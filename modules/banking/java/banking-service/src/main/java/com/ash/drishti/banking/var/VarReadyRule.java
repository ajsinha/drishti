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
package com.ash.drishti.banking.var;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.dataload.ReadyCheck;
import com.ash.drishti.dataload.ReadyRule;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The VaR intake's rules, applied to a file before it is pushed (VAR_GUIDE.md section 15.13; DATA_LAKE_GUIDE.md section 3.5): each sampled record
 * through {@link VarInputs#check}, what the date would then hold through {@link VarInputs#mixed} ({@code DRS-5210}, the same rule
 * {@link VarIntake#verify} applies when the date loads), and the hierarchies' level columns, which would otherwise put rows under (Blank).
 */
final class VarReadyRule implements ReadyRule {

    private static final Map<String, String> FORMS = Map.of("scenario-pv", VarInputs.PV, "pnl-vector", VarInputs.PNL);
    private static final int LISTED = 3;

    private final Function<String, VarProperties.Input> pins;
    private final Supplier<Hierarchies> hierarchies;

    VarReadyRule(Function<String, VarProperties.Input> pins, Supplier<Hierarchies> hierarchies) {
        this.pins = pins;
        this.hierarchies = hierarchies;
    }

    @Override
    public List<ReadyCheck> check(Input in) throws IOException {
        String form = FORMS.get(in.kind());
        if (form == null) {
            return List.of();
        }
        List<ReadyCheck> out = new ArrayList<>();
        records(in, form, out);
        date(in, out);
        levels(in, out);
        return out;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private void records(Input in, String form, List<ReadyCheck> out) {
        VarInputs rule = new VarInputs(pins);
        Map<String, Integer> codes = new LinkedHashMap<>();
        List<String> shown = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        int dup = 0;
        for (JsonNode r : in.sample()) {
            String[] bad = rule.check(form, text(r, "VALUE_TYPE"), text(r, "GRAIN"), text(r, "TRADE_ID"), text(r, "POSITION_ID"), text(r, "SOURCE_SYSTEM"), text(r, "CURRENCY"));
            if (bad != null) {
                codes.merge(bad[0], 1, Integer::sum);
                if (shown.size() < LISTED && !shown.contains(bad[0] + " " + bad[1])) {
                    shown.add(bad[0] + " " + bad[1]);
                }
                continue;
            }
            String id = VarInputs.POSITION.equalsIgnoreCase(text(r, "GRAIN")) ? text(r, "POSITION_ID") : text(r, "TRADE_ID");
            if (id != null && !ids.add(id)) {
                dup++;
            }
        }
        if (!codes.isEmpty()) {
            int n = codes.values().stream().mapToInt(Integer::intValue).sum();
            out.add(ReadyCheck.refused(n + " of the sampled records are refused by the VaR intake: " + String.join("; ", shown) + ".",
                    "Correct the records at the source; each says what it is (VALUE_TYPE, GRAIN, its id, CURRENCY, SOURCE_SYSTEM)."));
        } else {
            out.add(ReadyCheck.pass("Every sampled record says what it is, and its grain and id agree."));
        }
        if (dup > 0) {
            out.add(ReadyCheck.refused("A trade or position appears twice in the sample (" + dup + " rows): the date would be refused.", "Send each trade or position once on a date."));
        }
    }

    /** {@code system -> combos} the file's sample says. */
    private static Set<String[]> sent(Input in) {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (JsonNode r : in.sample()) {
            String s = text(r, "SOURCE_SYSTEM");
            String t = text(r, "VALUE_TYPE");
            String g = text(r, "GRAIN");
            if (s != null && t != null && g != null) {
                out.putIfAbsent(s.strip() + "|" + VarInputs.combo(t, g), new String[] {s.strip(), VarInputs.combo(t, g)});
            }
        }
        return new LinkedHashSet<>(out.values());
    }

    private void date(Input in, List<ReadyCheck> out) throws IOException {
        Set<String[]> file = sent(in);
        Map<List<String>, Long> own = in.facts().of(in.kind());
        Map<List<String>, Long> other = new LinkedHashMap<>();
        for (String k : FORMS.keySet()) {
            if (!k.equals(in.kind())) {
                other.putAll(in.facts().of(k));
            }
        }
        boolean replace = "replace".equals(in.mode());
        List<String> mixed = simulate(file, replace ? Map.of() : own, other);
        if (mixed.isEmpty()) {
            if (!file.isEmpty()) {
                out.add(ReadyCheck.pass("One form and one grain per source system on " + in.date() + " (" + describe(file) + ")."));
            }
            return;
        }
        String words = ErrorCode.VAR_INPUT_MIXED.code() + ": on " + in.date() + " " + String.join("; ", mixed) + ": this date would be refused by the VaR engine.";
        boolean replaceHelps = !replace && simulate(file, Map.of(), other).isEmpty();
        if (replaceHelps) {
            out.add(ReadyCheck.refused(words, "One system sends one form and one grain per date. The clash is with rows of this table: replace removes them first, or undo that load.")
                    .offering("mode", "replace"));
        } else {
            out.add(ReadyCheck.refused(words, "One system sends one form and one grain per date. The other form came through another table: undo that load, or send this file in the same form and grain."));
        }
    }

    private List<String> simulate(Set<String[]> file, Map<List<String>, Long> own, Map<List<String>, Long> other) {
        VarInputs rule = new VarInputs(pins);
        file.forEach(s -> rule.saw(s[0], s[1]));
        add(rule, own);
        add(rule, other);
        return rule.mixed();
    }

    private static void add(VarInputs rule, Map<List<String>, Long> rows) {
        rows.forEach((k, n) -> {
            if (!k.get(0).isBlank() && !k.get(1).isBlank() && !k.get(2).isBlank()) {
                String c = VarInputs.combo(k.get(1), k.get(2));
                for (long i = 0; i < Math.min(n, 1); i++) {
                    rule.saw(k.get(0).strip(), c);
                }
            }
        });
    }

    private static String describe(Set<String[]> file) {
        Set<String> s = new TreeSet<>();
        file.forEach(x -> s.add(x[0] + " " + x[1]));
        return String.join(", ", s);
    }

    private void levels(Input in, List<ReadyCheck> out) {
        Hierarchies h = hierarchies == null ? null : hierarchies.get();
        if (h == null) {
            return;
        }
        Map<String, Integer> blank = new LinkedHashMap<>();
        Set<String> columns = new java.util.LinkedHashSet<>();
        h.specs().forEach(s -> columns.addAll(s.levels()));
        for (String c : columns) {
            int n = 0;
            for (JsonNode r : in.sample()) {
                if (text(r, c) == null) {
                    n++;
                }
            }
            if (n > 0) {
                blank.put(c, n);
            }
        }
        if (blank.isEmpty()) {
            if (!columns.isEmpty()) {
                out.add(ReadyCheck.pass("Every hierarchy level column is filled in the sample (" + String.join(", ", columns) + ")."));
            }
            return;
        }
        List<String> parts = new ArrayList<>();
        blank.forEach((c, n) -> parts.add(c + " on " + n + " rows"));
        out.add(ReadyCheck.warning("Hierarchy level columns are empty in the sample: " + String.join(", ", parts) + ": those rows would go under (Blank).",
                "Correct them at the source, or push and send a corrected file later."));
    }
}
