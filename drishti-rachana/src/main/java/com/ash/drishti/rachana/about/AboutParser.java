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
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.Template;
import com.ash.drishti.rachana.model.SourceLocation;
import com.ash.drishti.rachana.parse.PNode;
import com.ash.drishti.rachana.parse.PositionalYamlReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads one pack's {@code config/about.yaml} (docs/architecture/CONTEXT_HELP.md, Pack schema additions). Strict, like the
 * Sutra parser: an unknown key is a problem, not a silent no-op. A problem costs only the entry it is in, never the file or
 * the view; every problem has the file, line and column. Stateless and thread-safe.
 *
 * <p>Problem codes: {@value #BAD_FILE} unknown key, bad version, bad shape or unreadable YAML; {@value #BAD_KIND} the kind
 * is not one of the pack's own or an extended pack's; {@value #BAD_TEMPLATE} a template does not compile;
 * {@value #BAD_USE} {@code use} names no vocabulary entry; {@value #TOO_LONG} a text is over the cap.
 */
public final class AboutParser {

    public static final String BAD_FILE = "DRS-2040";
    public static final String BAD_KIND = "DRS-2041";
    public static final String BAD_TEMPLATE = "DRS-2042";
    public static final String BAD_USE = "DRS-2043";
    public static final String TOO_LONG = "DRS-2044";

    private static final Set<String> FILE_KEYS = Set.of("about", "vocabulary", "kinds");
    private static final Set<String> KIND_KEYS = Set.of("title", "about", "guide", "glossary", "panels");
    private static final Set<String> ENTRY_KEYS = Set.of("term", "means", "unit", "sign", "note", "formula", "values");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    /**
     * What a file said.
     *
     * @param vocabulary the shared terms by field name
     * @param kinds the kinds' entries
     * @param problems what is wrong, with where
     */
    public record Parsed(Map<String, GlossaryEntry> vocabulary, Map<String, KindAbout> kinds, List<SutraProblem> problems) {}

    private final ElCompiler el;
    private final int maxText;

    public AboutParser(ElCompiler el, int maxText) {
        this.el = el;
        this.maxText = maxText;
    }

    /**
     * @param text the file's text
     * @param file its name, for the problems
     * @param ownKinds the kinds this pack may write about: its own and those of every pack it extends
     */
    public Parsed parse(String text, String file, Set<String> ownKinds) {
        Run r = new Run(file, ownKinds);
        try {
            List<PositionalYamlReader.Issue> issues = new ArrayList<>();
            PNode root = new PositionalYamlReader().read(text, issues);
            issues.forEach(i -> r.problem(BAD_FILE, i.message(), i.line(), i.column()));
            r.file(root);
        } catch (IOException | RuntimeException e) {
            r.problem(BAD_FILE, "the file is not valid YAML: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""), 1, 1);
        }
        return new Parsed(r.vocabulary, r.kinds, r.problems);
    }

    /** One parse: the state a file's problems and entries accumulate in. */
    private final class Run {
        final String file;
        final Set<String> ownKinds;
        final List<SutraProblem> problems = new ArrayList<>();
        final Map<String, GlossaryEntry> vocabulary = new LinkedHashMap<>();
        final Map<String, KindAbout> kinds = new LinkedHashMap<>();

        Run(String file, Set<String> ownKinds) {
            this.file = file;
            this.ownKinds = ownKinds;
        }

        void problem(String code, String message, int line, int column) {
            problems.add(new SutraProblem(code, message, new SourceLocation(file, line, column)));
        }

        void problem(String code, String message, PNode at) {
            problem(code, message, at.line(), at.column());
        }

        void file(PNode root) {
            if (!root.isMap()) {
                problem(BAD_FILE, "an about file is a map with 'about: 1', 'vocabulary' and 'kinds'", root);
                return;
            }
            Map<String, PNode> m = root.map();
            m.forEach((k, v) -> {
                if (!FILE_KEYS.contains(k)) {
                    problem(BAD_FILE, "unknown key '" + k + "' (expected about, vocabulary or kinds)", v);
                }
            });
            PNode version = m.get("about");
            if (version == null || !"1".equals(version.text())) {
                problem(BAD_FILE, "the file must start with 'about: 1' (the schema version)", version == null ? root : version);
                return;
            }
            PNode vocab = m.get("vocabulary");
            if (vocab != null) {
                if (!vocab.isMap()) {
                    problem(BAD_FILE, "'vocabulary' is a map of field name to entry", vocab);
                } else {
                    vocab.map().forEach((name, n) -> {
                        if (!NAME.matcher(name).matches()) {
                            problem(BAD_FILE, "vocabulary name '" + name + "' must be a field name without dots", n);
                            return;
                        }
                        GlossaryEntry e = entry(n, false);
                        if (e != null) {
                            vocabulary.put(name, e);
                        }
                    });
                }
            }
            PNode ks = m.get("kinds");
            if (ks != null) {
                if (!ks.isMap()) {
                    problem(BAD_FILE, "'kinds' is a map of kind to entry", ks);
                } else {
                    ks.map().forEach(this::kind);
                }
            }
        }

        void kind(String kind, PNode n) {
            if (!ownKinds.contains(kind)) {
                problem(BAD_KIND, "kind '" + kind + "' belongs to neither this pack nor a pack it extends", n);
                return;
            }
            if (!n.isMap()) {
                problem(BAD_FILE, "kind '" + kind + "' is a map (title, about, guide, glossary, panels)", n);
                return;
            }
            n.map().forEach((k, v) -> {
                if (!KIND_KEYS.contains(k)) {
                    problem(BAD_FILE, "unknown key '" + k + "' in kind '" + kind + "' (expected title, about, guide, glossary or panels)", v);
                }
            });
            String title = text(n.map().get("title"));
            String guide = text(n.map().get("guide"));
            Template about = template(n.map().get("about"));
            Map<String, GlossaryEntry> glossary = new LinkedHashMap<>();
            PNode g = n.map().get("glossary");
            if (g != null) {
                if (!g.isMap()) {
                    problem(BAD_FILE, "'glossary' is a map of field path to entry", g);
                } else {
                    g.map().forEach((path, e) -> {
                        if (!PATH.matcher(path).matches()) {
                            problem(BAD_FILE, "glossary key '" + path + "' must be a field path: names joined by dots, no array steps", e);
                            return;
                        }
                        GlossaryEntry entry = entry(e, true);
                        if (entry != null) {
                            glossary.put(path, entry);
                        }
                    });
                }
            }
            Map<String, Template> panels = new LinkedHashMap<>();
            PNode p = n.map().get("panels");
            if (p != null) {
                if (!p.isMap()) {
                    problem(BAD_FILE, "'panels' is a map of panel id to {about}", p);
                } else {
                    p.map().forEach((id, e) -> {
                        if (!e.isMap() || !e.map().containsKey("about") || e.map().size() != 1) {
                            problem(BAD_FILE, "panel '" + id + "' is {about: <template>} and nothing else", e);
                            return;
                        }
                        Template t = template(e.map().get("about"));
                        if (t != null) {
                            panels.put(id, t);
                        }
                    });
                }
            }
            kinds.put(kind, new KindAbout(title, about, guide, glossary, panels));
        }

        /** A plain text under the cap, or null (with the problem recorded). */
        String text(PNode n) {
            if (n == null) {
                return null;
            }
            String s = n.text();
            if (s == null) {
                problem(BAD_FILE, "expected text here", n);
                return null;
            }
            if (s.length() > maxText) {
                problem(TOO_LONG, "text is " + s.length() + " characters; the limit is " + maxText + " (drishti.about.max-text)", n);
                return null;
            }
            return s.strip();
        }

        Template template(PNode n) {
            if (n == null) {
                return null;
            }
            String s = n.text();
            if (s == null) {
                problem(BAD_FILE, "expected text with ${...} expressions here", n);
                return null;
            }
            try {
                return el.template(s.strip());
            } catch (RuntimeException e) {
                problem(BAD_TEMPLATE, "the template does not compile: " + e.getMessage(), n);
                return null;
            }
        }

        GlossaryEntry entry(PNode n, boolean allowUse) {
            if (!n.isMap()) {
                problem(BAD_FILE, "an entry is a map with term, means, unit, sign, note, formula or values" + (allowUse ? ", or {use: name}" : ""), n);
                return null;
            }
            Map<String, PNode> m = n.map();
            if (allowUse && m.containsKey("use")) {
                if (m.size() != 1) {
                    problem(BAD_FILE, "'use' stands alone: write the entry in full or name a vocabulary entry, not both", n);
                    return null;
                }
                String use = text(m.get("use"));
                return use == null ? null : new GlossaryEntry(null, null, null, null, null, null, Map.of(), use, null, new SourceLocation(file, n.line(), n.column()));
            }
            int before = problems.size();
            boolean ok = true;
            for (Map.Entry<String, PNode> e : m.entrySet()) {
                if (!ENTRY_KEYS.contains(e.getKey())) {
                    problem(BAD_FILE, "unknown key '" + e.getKey() + "' in an entry (expected term, means, unit, sign, note, formula or values)", e.getValue());
                    ok = false;
                }
            }
            Map<String, String> values = new LinkedHashMap<>();
            PNode v = m.get("values");
            if (v != null) {
                if (!v.isMap()) {
                    problem(BAD_FILE, "'values' is a map of value to meaning", v);
                    ok = false;
                } else {
                    v.map().forEach((k, x) -> {
                        String meaning = text(x);
                        if (meaning != null) {
                            values.put(k, meaning);
                        }
                    });
                }
            }
            GlossaryEntry out = new GlossaryEntry(text(m.get("term")), text(m.get("means")), text(m.get("unit")), text(m.get("sign")),
                    text(m.get("note")), text(m.get("formula")), values, null, null, new SourceLocation(file, n.line(), n.column()));
            return ok && problems.size() == before ? out : null;
        }
    }
}
