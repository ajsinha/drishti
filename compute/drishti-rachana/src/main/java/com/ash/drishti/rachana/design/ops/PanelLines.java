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
package com.ash.drishti.rachana.design.ops;

import com.ash.drishti.rachana.SutraText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lines of one panel item ({@code - id: x} with keys below, or {@code - { id: x, ... }}): set or remove a key, and
 * write a new item. Values arrive already written as one-line YAML. Stateless.
 */
final class PanelLines {

    private PanelLines() {}

    private static int dashLine(List<String> block, int dash) {
        int d = 0;
        while (d < block.size() && !(SutraText.indent(block.get(d)) == dash && block.get(d).length() > dash && block.get(d).charAt(dash) == '-')) {
            d++;
        }
        if (d == block.size()) {
            throw new IllegalArgumentException("cannot find the start of the panel");
        }
        return d;
    }

    static boolean isFlow(List<String> block, int dash) {
        return block.get(dashLine(block, dash)).substring(dash + 1).strip().startsWith("{");
    }

    /** Sets ({@code rendered}) or removes ({@code null}) the key {@code key} of the item. */
    static void set(List<String> block, int dash, String key, String rendered) {
        int d = dashLine(block, dash);
        String first = block.get(d);
        if (isFlow(block, dash)) {
            Map<String, String> m = new HashMap<>();
            m.put(key, rendered);
            SutraText.flow(block, d, m);
            return;
        }
        int keyIndent = first.substring(dash + 1).isBlank() ? SutraText.indent(block.get(d + 1))
                : dash + 1 + SutraText.indent(first.substring(dash + 1));
        String pad = " ".repeat(keyIndent);
        int at = -1;
        for (int l = d; l < block.size() && at < 0; l++) {
            String body = l == d ? pad + first.substring(keyIndent) : block.get(l);
            if (SutraText.indent(body) == keyIndent && body.startsWith(key + ":", keyIndent)) {
                at = l;
            }
        }
        if (at >= 0) {
            int end = extent(block, at, keyIndent);
            String old = block.get(at);
            String prefix = at == d ? old.substring(0, keyIndent) : pad;
            int hash = old.indexOf(" #");
            String comment = end == at + 1 && hash > 0 && old.indexOf('"') < 0 && old.indexOf('\'') < 0 ? old.substring(hash) : "";
            for (int k = at; k < end; k++) {
                block.remove(at);
            }
            if (rendered != null) {
                block.add(at, prefix + key + ": " + rendered + comment);
            } else if (at == d && d < block.size()) {
                block.set(d, first.substring(0, keyIndent) + block.get(d).substring(keyIndent));
            }
            return;
        }
        if (rendered == null) {
            return;
        }
        int last = block.size() - 1;
        while (last > d && (block.get(last).isBlank() || block.get(last).strip().startsWith("#"))) {
            last--;
        }
        block.add(last + 1, pad + key + ": " + rendered);
    }

    /** One past the last line of the key at {@code at}: its nested lines, and a list written level with the key. */
    static int extent(List<String> lines, int at, int keyIndent) {
        int e = at + 1;
        while (e < lines.size()) {
            String t = lines.get(e);
            if (t.isBlank()) {
                int n = e + 1;
                while (n < lines.size() && lines.get(n).isBlank()) {
                    n++;
                }
                if (n < lines.size() && SutraText.indent(lines.get(n)) > keyIndent) {
                    e = n;
                    continue;
                }
                break;
            }
            int ind = SutraText.indent(t);
            if (ind > keyIndent || ind == keyIndent && t.startsWith("-", keyIndent)) {
                e++;
            } else {
                break;
            }
        }
        return e;
    }

    /** A new item for the panel list whose dashes are at column {@code dash}. */
    static List<String> render(Map<String, Object> keys, int dash, boolean flow) {
        String pad = " ".repeat(dash);
        List<String> out = new ArrayList<>();
        if (flow) {
            out.add(pad + "- " + YamlText.flow(keys));
            return out;
        }
        boolean first = true;
        for (Map.Entry<String, Object> e : keys.entrySet()) {
            out.add(pad + (first ? "- " : "  ") + e.getKey() + ": " + YamlText.flow(e.getValue()));
            first = false;
        }
        return out;
    }
}
