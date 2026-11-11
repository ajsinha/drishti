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

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.Template;
import com.ash.drishti.rachana.SutraProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The About text a Design carries (the pack's {@code config/about.yaml} as the author is writing it) laid over what the packs
 * already say: for the Design's kind the Design's title, page text, guide, glossary entries, panel text and vocabulary win and
 * whatever it leaves out is the catalogue's. Parsed with the pack parser, so the same rules and codes ({@code DRS-2040} to
 * {@code DRS-2044}) apply and a problem names the line and column in the text. Immutable; thread-safe.
 */
public final class DesignAbout implements AboutSource {

    /** What the Design's text is called in problems and in the answer. */
    public static final String FILE = "design/about.yaml";
    /** The pack name the preview shows for text that comes from the Design. */
    public static final String PACK = "this design";

    private final AboutSource base;
    private final Map<String, AboutText> kinds;
    private final List<SutraProblem> problems;

    private DesignAbout(AboutSource base, Map<String, AboutText> kinds, List<SutraProblem> problems) {
        this.base = base;
        this.kinds = kinds;
        this.problems = problems;
    }

    /**
     * @param base the catalogue underneath
     * @param el the compiler of the templates
     * @param maxText the longest plain text an entry may hold ({@code drishti.about.max-text})
     * @param text the Design's about.yaml (blank for none)
     * @param kind the Design's kind: the only kind its text may write about
     */
    public static DesignAbout of(AboutSource base, ElCompiler el, int maxText, String text, String kind) {
        if (text == null || text.isBlank()) {
            return new DesignAbout(base, Map.of(), List.of());
        }
        AboutParser.Parsed p = new AboutParser(el, maxText).parse(text, FILE, kind == null ? Set.of() : Set.of(kind));
        Map<String, AboutText> out = new LinkedHashMap<>();
        List<SutraProblem> problems = new ArrayList<>(p.problems());
        p.kinds().forEach((k, a) -> out.put(k, merge(base, k, a, p.vocabulary(), problems)));
        return new DesignAbout(base, Map.copyOf(out), List.copyOf(problems));
    }

    private static AboutText merge(AboutSource base, String kind, KindAbout a, Map<String, GlossaryEntry> vocabulary, List<SutraProblem> problems) {
        AboutText under = base.forKind(kind).orElse(null);
        Map<String, GlossaryEntry> vocab = new LinkedHashMap<>();
        vocabulary.forEach((n, e) -> vocab.put(n, e.from(PACK + ":vocabulary." + n)));
        if (under != null) {
            under.vocabulary().forEach(vocab::putIfAbsent);
        }
        Map<String, GlossaryEntry> glossary = new LinkedHashMap<>();
        a.glossary().forEach((path, e) -> {
            if (e.use() == null) {
                glossary.put(path, e.from(PACK + ":glossary." + path));
                return;
            }
            GlossaryEntry v = vocab.containsKey(e.use()) ? vocab.get(e.use()) : base.core(e.use()).orElse(null);
            if (v == null) {
                problems.add(new SutraProblem(AboutParser.BAD_USE, "use: " + e.use() + " names no vocabulary entry visible to this design", e.at()));
            } else {
                glossary.put(path, e.resolvedFrom(v));
            }
        });
        Map<String, Template> panels = new LinkedHashMap<>(a.panels());
        if (under != null) {
            under.glossary().forEach(glossary::putIfAbsent);
            under.panels().forEach(panels::putIfAbsent);
        }
        boolean own = a.about() != null || a.title() != null;
        String pack = own || under == null ? PACK : under.pack();
        String packTitle = own || under == null ? "This design" : under.packTitle();
        return new AboutText(pack, packTitle, kind, a.title() != null ? a.title() : under == null ? null : under.title(),
                a.about() != null ? a.about() : under == null ? null : under.about(), a.guide() != null ? a.guide() : under == null ? null : under.guide(),
                Map.copyOf(glossary), Map.copyOf(panels), Map.copyOf(vocab));
    }

    /** What is wrong with the Design's text, with where (empty when it is valid or blank). */
    public List<SutraProblem> problems() {
        return problems;
    }

    @Override
    public Optional<AboutText> forKind(String kind) {
        AboutText t = kinds.get(kind);
        return t != null ? Optional.of(t) : base.forKind(kind);
    }

    @Override
    public Optional<GlossaryEntry> core(String name) {
        return base.core(name);
    }
}
