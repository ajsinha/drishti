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
package com.ash.drishti.rachana.about;

import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The help warnings of pack lint (docs/architecture/CONTEXT_HELP.md, step 6). Warnings, never errors: they do not change an
 * exit code unless the tool is run with {@code --strict}. Stateless and thread-safe.
 *
 * <ul>
 *   <li>{@link #F1_BOUND} a Sutra binds {@code F1}, which opens the About drawer on a view (its binding wins there);
 *   <li>{@link #UNKNOWN_PANEL} {@code panels.<id>} in the about file names no panel of any Sutra of the kind;
 *   <li>{@link #NO_GLOSSARY} a field a Sutra shows has no glossary entry.
 * </ul>
 */
public final class HelpLint {

    public static final String F1_BOUND = "DRS-2045";
    public static final String UNKNOWN_PANEL = "DRS-2046";
    public static final String NO_GLOSSARY = "DRS-2047";

    private static final Pattern FIELD = Pattern.compile("[$@]((?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[^\\]]*\\])+)");
    private static final Pattern ARRAY_STEP = Pattern.compile("\\[[^\\]]*\\]");

    private final GlossaryLookup glossary;

    public HelpLint(GlossaryLookup glossary) {
        this.glossary = glossary == null ? GlossaryLookup.NONE : glossary;
    }

    /** The fields shown and those of them without an entry. */
    public record Coverage(List<String> shown, List<String> missing) {
        public int covered() {
            return shown.size() - missing.size();
        }

        public double ratio() {
            return shown.isEmpty() ? 1.0 : (double) covered() / shown.size();
        }

        public String describe() {
            return "help coverage " + covered() + "/" + shown.size() + " (" + Math.round(ratio() * 100) + "%)";
        }
    }

    /** {@code DRS-2045}, or null: the Sutra binds F1. */
    public SutraProblem f1(Sutra s) {
        if (s.keys().containsKey("F1")) {
            return new SutraProblem(F1_BOUND, "F1 is bound in keys: it opens About this page on a view, and this binding wins on this view "
                    + "(the drawer stays on '?'); bind another key to keep F1 for help", s.location());
        }
        return null;
    }

    /** {@code DRS-2046} for each panel id the kind's about text names that none of {@code panelIds} is. */
    public List<SutraProblem> unknownPanels(String kind, Set<String> panelIds, AboutSource catalog) {
        List<SutraProblem> out = new ArrayList<>();
        catalog.forKind(kind).ifPresent(t -> t.panels().keySet().stream().sorted().filter(id -> !panelIds.contains(id))
                .forEach(id -> out.add(new SutraProblem(UNKNOWN_PANEL, "about text for panel '" + id + "' of kind '" + kind
                        + "' matches no panel of any Sutra for the kind", null))));
        return out;
    }

    /** The fields a Sutra shows (strip binds, column binds, gauge and metric values), as paths without sigil or array steps. */
    public List<String> shownFields(Sutra s) {
        Set<String> out = new LinkedHashSet<>();
        s.strip().forEach(i -> fields(i.bind(), out));
        s.panels().forEach(p -> panel(p, out));
        return List.copyOf(out);
    }

    private static void panel(Panel p, Set<String> out) {
        String rows = p.option("rows").orElse(null);
        for (Column c : p.columns()) {
            String key = GlossaryResolver.keyOf(c.bind(), rows);   // the drawer's key: rows.column for a row field (docs: CONTEXT_HELP step 5)
            if (key != null) {
                out.add(key);
            } else {
                fields(c.bind(), out);
            }
        }
        for (String o : new String[] {"value", "delta"}) {
            p.option(o).ifPresent(e -> fields(e, out));
        }
        if (p.body() != null) {
            panel(p.body(), out);
        }
    }

    static void fields(String expr, Set<String> out) {
        if (expr == null) {
            return;
        }
        Matcher m = FIELD.matcher(expr);
        while (m.find()) {
            String path = ARRAY_STEP.matcher(m.group(1)).replaceAll("");
            if (path.startsWith(".")) {
                path = path.substring(1);
            }
            if (!path.isBlank()) {
                out.add(path);
            }
        }
    }

    /** Coverage of {@code kind}'s shown fields, counting only those {@code present} says some sample holds. */
    public Coverage coverage(String kind, Sutra s, Predicate<String> present) {
        List<String> shown = shownFields(s).stream().filter(present).toList();
        return new Coverage(shown, shown.stream().filter(f -> !glossary.covers(kind, f)).toList());
    }

    /** {@code DRS-2047} warnings, one per field shown without an entry. */
    public List<SutraProblem> uncovered(Coverage c, Sutra s) {
        String kind = s.match() == null || s.match().kind() == null ? "<kind>" : s.match().kind();
        return c.missing().stream().map(f -> new SutraProblem(NO_GLOSSARY, "field '" + f + "' is shown but has no glossary entry "
                + "(kinds." + kind + ".glossary in config/about.yaml, or a vocabulary entry)", s.location())).toList();
    }
}
