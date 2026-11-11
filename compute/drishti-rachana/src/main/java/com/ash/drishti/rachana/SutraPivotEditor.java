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

import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PivotSpec;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.PNode;
import com.ash.drishti.rachana.parse.PositionalYamlReader;
import com.ash.drishti.rachana.parse.SutraParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Turns a user's pivot arrangement into the text of the next version of a Sutra: the panel's {@code pivot:} key is
 * rewritten (as a block mapping, its {@code fields} and {@code totals} kept as the author wrote them) and every other line
 * of the file stays as it is, comments included. The result is parsed again and must open the Pivot tab with exactly the
 * requested arrangement. Shares the panel-block reading of {@link SutraLayoutEditor}. Stateless and thread-safe.
 */
public final class SutraPivotEditor {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_./ -]*");
    private static final java.util.Set<String> RESERVED = java.util.Set.of("true", "false", "yes", "no", "on", "off", "null", "y", "n", "~");

    private final SutraParser parser = new SutraParser();
    private final PositionalYamlReader reader = new PositionalYamlReader();

    /**
     * @param source the Sutra's text (valid)
     * @param panelId the table or ladder whose pivot changes
     * @param arrangement the new arrangement ({@link PivotSpec#arrangement}): rows, columns, values, filters, heat, chart
     * @throws IllegalArgumentException when the panel does not exist, takes no pivot, or its text cannot be edited
     */
    public SutraLayoutEditor.Edit apply(String source, String panelId, PivotSpec arrangement) {
        Sutra sutra = parser.parse(source, SutraParser.STUDIO, "studio");
        int index = -1;
        for (int i = 0; i < sutra.panels().size(); i++) {
            if (sutra.panels().get(i).id().equals(panelId)) {
                index = i;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("the Sutra has no panel '" + panelId + "'");
        }
        Panel panel = sutra.panels().get(index);
        PivotSpec before = panel.pivot().orElseThrow(() -> new IllegalArgumentException("panel '" + panelId + "' does not offer a pivot"));
        PNode root;
        try {
            root = reader.read(source);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the Sutra: " + e.getMessage(), e);
        }
        List<String> lines = new ArrayList<>(List.of(source.split("\n", -1)));
        SutraText.Blocks blocks = SutraText.blocks(lines, root);
        List<String> block = new ArrayList<>(blocks.items().get(index));
        Object raw = panel.options().get(Panel.PIVOT);
        Map<String, Object> value = value(raw, before.with(arrangement));
        replace(block, blocks.dash(), value);

        List<String> out = new ArrayList<>(lines.subList(0, blocks.start()));
        for (int i = 0; i < blocks.items().size(); i++) {
            out.addAll(i == index ? block : blocks.items().get(i));
        }
        out.addAll(lines.subList(blocks.end(), lines.size()));
        int version = SutraText.bumpVersion(out, root);
        String text = String.join("\n", out);
        verify(text, panelId, arrangement);
        return new SutraLayoutEditor.Edit(text, sutra.version(), version, changes(panelId, before, arrangement));
    }

    /** The new {@code pivot:} value: the author's fields and totals as written, then the arrangement. */
    private static Map<String, Object> value(Object raw, PivotSpec next) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            if (m.containsKey("fields")) {
                out.put("fields", m.get("fields"));
            }
        }
        Map<String, Object> a = next.arrangementMap();
        for (String k : List.of("rows", "columns", "values", "filters")) {
            if (a.get(k) instanceof List<?> l && !l.isEmpty()) {
                out.put(k, l);
            }
        }
        if (next.heat()) {
            out.put("heat", true);
        }
        if (next.chart() != null) {
            out.put("chart", next.chart());
        }
        if (raw instanceof Map<?, ?> m && m.get("totals") != null) {
            out.put("totals", m.get("totals"));
        }
        return out;
    }

    /** Replaces (or adds) the block's {@code pivot:} key and every line nested under it. */
    private static void replace(List<String> block, int dash, Map<String, Object> value) {
        int d = 0;
        while (d < block.size() && !(SutraText.indent(block.get(d)) == dash && block.get(d).length() > dash && block.get(d).charAt(dash) == '-')) {
            d++;
        }
        String first = block.get(d);
        String rendered = value.isEmpty() ? "true" : null;
        if (first.substring(dash + 1).strip().startsWith("{")) {
            // a flow mapping panel: the pivot goes in as one flow value
            int close = d;
            while (close < block.size() && !block.get(close).contains("}")) {
                close++;
            }
            StringBuilder sb = new StringBuilder();
            for (int l = d; l <= Math.min(close, block.size() - 1); l++) {
                sb.append(l > d ? "\n" : "").append(block.get(l));
            }
            String edited = SutraText.flowSet(sb.toString(), Panel.PIVOT, rendered != null ? rendered : flow(value));
            for (int l = Math.min(close, block.size() - 1); l >= d; l--) {
                block.remove(l);
            }
            block.addAll(d, List.of(edited.split("\n", -1)));
            return;
        }
        int keyIndent = first.substring(dash + 1).isBlank() ? SutraText.indent(block.get(d + 1))
                : dash + 1 + SutraText.indent(first.substring(dash + 1));
        String pad = " ".repeat(keyIndent);
        int at = -1;
        for (int l = d + 1; l < block.size(); l++) {
            if (block.get(l).startsWith(pad + Panel.PIVOT + ":") && SutraText.indent(block.get(l)) == keyIndent) {
                at = l;
            }
        }
        if (first.substring(dash + 1).strip().startsWith(Panel.PIVOT + ":")) {
            throw new IllegalArgumentException("'pivot' written on a panel's first line cannot be edited: move it below");
        }
        List<String> lines = new ArrayList<>();
        if (rendered != null) {
            lines.add(pad + Panel.PIVOT + ": true");
        } else {
            lines.add(pad + Panel.PIVOT + ":");
            value.forEach((k, v) -> lines.add(pad + "  " + k + ": " + flow(v)));
        }
        if (at >= 0) {
            int end = at + 1;
            while (end < block.size() && (block.get(end).isBlank() ? nestedAfter(block, end, keyIndent) : SutraText.indent(block.get(end)) > keyIndent)) {
                end++;
            }
            for (int l = end - 1; l >= at; l--) {
                block.remove(l);
            }
            block.addAll(at, lines);
        } else {
            int last = block.size() - 1;
            while (last > d && (block.get(last).isBlank() || block.get(last).strip().startsWith("#"))) {
                last--;
            }
            block.addAll(last + 1, lines);
        }
    }

    /** A blank line belongs to the nested block when a deeper line follows it. */
    private static boolean nestedAfter(List<String> block, int from, int keyIndent) {
        for (int l = from; l < block.size(); l++) {
            if (!block.get(l).isBlank()) {
                return SutraText.indent(block.get(l)) > keyIndent;
            }
        }
        return false;
    }

    /** A value as YAML flow text: lists in brackets, mappings in braces, text quoted only when it must be. */
    static String flow(Object v) {
        if (v instanceof Map<?, ?> m) {
            List<String> parts = new ArrayList<>();
            m.forEach((k, x) -> parts.add(k + ": " + flow(x)));
            return "{ " + String.join(", ", parts) + " }";
        }
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            l.forEach(x -> parts.add(flow(x)));
            return "[" + String.join(", ", parts) + "]";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        if (PLAIN.matcher(s).matches() && !RESERVED.contains(s.toLowerCase(java.util.Locale.ROOT)) && !s.endsWith(" ")) {
            return s;
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void verify(String text, String panelId, PivotSpec arrangement) {
        Sutra s = parser.parse(text, SutraParser.STUDIO, "studio");
        PivotSpec got = s.panels().stream().filter(p -> p.id().equals(panelId)).findFirst().flatMap(Panel::pivot)
                .orElseThrow(() -> new IllegalStateException("the edited Sutra lost the pivot of '" + panelId + "'"));
        if (!Objects.equals(got.arrangementMap(), arrangement.arrangementMap())) {
            throw new IllegalStateException("the edited Sutra arranges the pivot of '" + panelId + "' as " + got.arrangementMap()
                    + ", not " + arrangement.arrangementMap());
        }
    }

    /** What changed, one plain sentence each. */
    static List<String> changes(String panelId, PivotSpec a, PivotSpec b) {
        List<String> out = new ArrayList<>();
        String who = "the pivot of '" + panelId + "'";
        if (!a.rows().equals(b.rows())) {
            out.add(who + " opens with rows " + list(b.rows()) + " (was " + list(a.rows()) + ")");
        }
        if (!a.columns().equals(b.columns())) {
            out.add(who + " opens with columns " + list(b.columns()) + " (was " + list(a.columns()) + ")");
        }
        if (!a.values().equals(b.values())) {
            out.add(who + " shows " + values(b) + " (was " + values(a) + ")");
        }
        if (!a.filters().equals(b.filters())) {
            out.add(who + " offers filters " + list(b.filters().stream().map(PivotSpec.Filter::field).toList())
                    + (b.filters().stream().anyMatch(f -> f.values() != null || f.min() != null || f.max() != null) ? ", some already set" : ""));
        }
        if (a.heat() != b.heat()) {
            out.add(who + (b.heat() ? " is shaded by value" : " is no longer shaded"));
        }
        if (!Objects.equals(a.chart(), b.chart())) {
            out.add(who + (b.chart() == null ? " opens without a chart" : " opens with a " + b.chart() + " chart"));
        }
        return out;
    }

    private static String list(List<String> l) {
        return l.isEmpty() ? "none" : String.join(", ", l);
    }

    private static String values(PivotSpec s) {
        if (s.values().isEmpty()) {
            return "no values";
        }
        List<String> out = new ArrayList<>();
        for (PivotSpec.Value v : s.values()) {
            out.add(v.agg() + " of " + v.field() + ("value".equals(v.show()) ? "" : " (" + v.show() + ")"));
        }
        return String.join(", ", out);
    }
}
