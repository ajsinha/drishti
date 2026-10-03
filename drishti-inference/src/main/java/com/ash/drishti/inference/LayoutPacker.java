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
package com.ash.drishti.inference;

import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The packing rules shared by runtime inference (candidates from one document) and the Screen Builder's auto-design
 * (candidates from a shape): keep the best candidate per source path, take the strongest per column under the density
 * limits, give the first main panels function keys F2.., add the provenance panel, and put the links panel at the head of
 * the right column. Pure and thread-safe.
 */
public final class LayoutPacker {

    private LayoutPacker() {}

    /**
     * @param panels main panels (keyed), provenance, then the right column with the links panel after its first panel
     * @param why per panel id: {@code rule score: reason}
     */
    public record Packed(List<Panel> panels, Map<String, String> why) {}

    public static Packed pack(Collection<Candidate> all, int mainMax, int rightMax) {
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Candidate c : all) {
            best.merge(c.sourcePath(), c, (a, b) -> b.score() > a.score() ? b : a);
        }
        List<Candidate> main = pick(best.values(), Area.MAIN, mainMax);
        List<Candidate> right = pick(best.values(), Area.RIGHT, rightMax);

        Map<String, String> why = new LinkedHashMap<>();
        List<Panel> panels = new ArrayList<>();
        int fkey = 2;
        for (Candidate c : main) {
            Panel p = c.panel();
            panels.add(fkey <= 6 ? withKey(p, "F" + fkey++) : p);
            why.put(p.id(), explain(c));
        }
        panels.add(Rules.panel("built", PanelKind.PROVENANCE, "How this view was built", Area.MAIN, List.of(), Map.of()));
        boolean linksPlaced = false;
        for (Candidate c : right) {
            panels.add(c.panel());
            why.put(c.panel().id(), explain(c));
            if (!linksPlaced) {
                panels.add(links());
                linksPlaced = true;
            }
        }
        if (!linksPlaced) {
            panels.add(links());
        }
        return new Packed(panels, why);
    }

    private static String explain(Candidate c) {
        return String.format(Locale.ROOT, "%s %.2f: %s", c.rule(), c.score(), c.reason());
    }

    private static Panel links() {
        return new Panel("refs", PanelKind.LINKS, "Linked entities", null, "REFS", Area.RIGHT, true, List.of(), null, Map.of(),
                Rules.INFERRED);
    }

    private static Panel withKey(Panel p, String key) {
        return new Panel(p.id(), p.kind(), p.title(), key, p.code(), p.area(), p.infer(), p.columns(), p.body(), p.options(), p.location());
    }

    private static List<Candidate> pick(Iterable<Candidate> all, Area area, int max) {
        List<Candidate> in = new ArrayList<>();
        all.forEach(c -> {
            if (c.panel().area() == area) {
                in.add(c);
            }
        });
        in.sort(Comparator.comparingDouble(Candidate::score).reversed());
        List<Candidate> top = new ArrayList<>(in.subList(0, Math.min(Math.max(max, 0), in.size())));
        top.sort(Comparator.comparingInt(Candidate::order));
        return top;
    }
}
