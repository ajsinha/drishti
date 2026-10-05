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
import com.ash.drishti.rachana.model.SourceLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The about text of every loaded pack, by kind (docs/architecture/CONTEXT_HELP.md). Built from the packs' {@code
 * config/about.yaml} files, most specific pack first: a child pack's entry wins over its parents', key by key, and cannot
 * delete one. Templates are compiled once, at load. A broken entry is left out and reported in {@link #problems()}; it never
 * fails the load and never fails a view.
 *
 * <p>Immutable once built; {@link #reload()} swaps in a whole new state, so readers always see a consistent catalogue.
 * Thread-safe.
 */
public final class AboutCatalog implements AboutSource {

    private static final Logger LOG = LoggerFactory.getLogger(AboutCatalog.class);

    private record Loaded(AboutProperties.PackSource pack, AboutParser.Parsed parsed) {}

    private record Snapshot(Map<String, AboutText> byKind, Map<String, Map<String, AboutText>> byLocale, Map<String, List<SutraProblem>> problems) {}

    private final AboutProperties props;
    private final AboutParser parser;
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private final AtomicLong revision = new AtomicLong();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of());
    /** The generic words every domain shares ({@value #CORE_RESOURCE}); the lowest priority of all vocabularies. */
    private final Map<String, GlossaryEntry> core;

    public AboutCatalog(AboutProperties props, ElCompiler el) {
        this.props = props;
        this.parser = new AboutParser(el, props.maxText());
        this.core = loadCore();
        reload();
    }

    /** Where the shared core vocabulary lives, on the classpath. */
    public static final String CORE_RESOURCE = "about/vocabulary.yaml";

    private Map<String, GlossaryEntry> loadCore() {
        try (java.io.InputStream in = AboutCatalog.class.getClassLoader().getResourceAsStream(CORE_RESOURCE)) {
            if (in == null) {
                return Map.of();
            }
            AboutParser.Parsed p = parser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "core/vocabulary.yaml", Set.of());
            p.problems().forEach(x -> LOG.warn("core vocabulary problem {}", x));
            Map<String, GlossaryEntry> out = new LinkedHashMap<>();
            p.vocabulary().forEach((n, e) -> out.put(n, e.from("core:vocabulary." + n)));
            return Map.copyOf(out);
        } catch (IOException | RuntimeException e) {
            LOG.warn("core vocabulary not loaded: {}", e.toString());
            return Map.of();
        }
    }

    /** The core vocabulary's entry for a field name, or empty. */
    @Override
    public Optional<GlossaryEntry> core(String name) {
        return Optional.ofNullable(core.get(name));
    }

    /** Reads every pack's about file again. */
    public void reload() {
        lock.lock();
        try {
            load();
        } finally {
            lock.unlock();
        }
    }

    private void load() {
        Map<String, AboutProperties.PackSource> byName = new LinkedHashMap<>();
        props.packs().forEach(p -> byName.put(p.name(), p));
        List<Loaded> loaded = new ArrayList<>();
        Map<String, List<SutraProblem>> problems = new LinkedHashMap<>();
        for (AboutProperties.PackSource p : props.packs()) {
            if (p.file() == null || !Files.isRegularFile(Path.of(p.file()))) {
                continue;
            }
            try {
                String text = Files.readString(Path.of(p.file()), StandardCharsets.UTF_8);
                Set<String> own = new LinkedHashSet<>();
                p.lineage().forEach(n -> Optional.ofNullable(byName.get(n)).ifPresent(x -> own.addAll(x.kinds())));
                AboutParser.Parsed parsed = parser.parse(text, display(p), own);
                loaded.add(new Loaded(p, parsed));
                if (!parsed.problems().isEmpty()) {
                    problems.computeIfAbsent(display(p), k -> new ArrayList<>()).addAll(parsed.problems());
                }
            } catch (IOException | RuntimeException e) {
                problems.computeIfAbsent(display(p), k -> new ArrayList<>())
                        .add(new SutraProblem(AboutParser.BAD_FILE, "the file could not be read: " + e.getMessage(), new SourceLocation(display(p), 0, 0)));
            }
        }
        List<Loaded> resolved = resolveUses(loaded, loaded, problems);
        Map<String, AboutText> byKind = merge(resolved);
        Map<String, Map<String, AboutText>> byLocale = overlays(loaded, resolved, byName, problems);
        problems.forEach((file, ps) -> ps.forEach(x -> LOG.warn("about problem {}", x)));
        snapshot = new Snapshot(Map.copyOf(byKind), Map.copyOf(byLocale), Map.copyOf(problems));
        revision.incrementAndGet();
        LOG.info("about loaded: {} kind(s), {} language overlay(s), {} problem file(s)", byKind.size(), byLocale.size(), problems.size());
    }

    /**
     * The translation overlays: {@code about.<lang>.yaml} beside each pack's about file ({@code <lang>} a BCP 47 tag such as
     * {@code fr} or {@code fr-CA}). An overlay has the same shape and the same strict parse as the English file and is
     * merged over it key by key, so a key it does not translate falls back to English. Order inside one language: its
     * overlays (most specific pack first), then, for a regional tag, the plain language's, then every English file.
     */
    private Map<String, Map<String, AboutText>> overlays(List<Loaded> base, List<Loaded> resolvedBase, Map<String, AboutProperties.PackSource> byName,
            Map<String, List<SutraProblem>> problems) {
        Map<String, List<Loaded>> byTag = new java.util.TreeMap<>();
        for (AboutProperties.PackSource p : props.packs()) {
            if (p.file() == null || !Files.isRegularFile(Path.of(p.file()))) {
                continue;
            }
            Path file = Path.of(p.file());
            String name = file.getFileName().toString();
            String stem = name.endsWith(".yaml") ? name.substring(0, name.length() - 5) : name;
            Set<String> own = new LinkedHashSet<>();
            p.lineage().forEach(n -> Optional.ofNullable(byName.get(n)).ifPresent(x -> own.addAll(x.kinds())));
            Path dir = file.toAbsolutePath().getParent();
            List<Path> found;
            try (java.util.stream.Stream<Path> ls = Files.list(dir)) {
                found = ls.filter(f -> OVERLAY.matcher(f.getFileName().toString()).matches()
                        && f.getFileName().toString().startsWith(stem + ".")).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path f : found) {
                String fn = f.getFileName().toString();
                String tag = fn.substring(stem.length() + 1, fn.length() - 5).toLowerCase(java.util.Locale.ROOT);
                String shown = p.name() + "/" + fn;
                try {
                    AboutParser.Parsed parsed = parser.parse(Files.readString(f, StandardCharsets.UTF_8), shown, own);
                    if (!parsed.problems().isEmpty()) {
                        problems.computeIfAbsent(shown, k -> new ArrayList<>()).addAll(parsed.problems());
                    }
                    byTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(new Loaded(p, parsed));
                } catch (IOException | RuntimeException e) {
                    problems.computeIfAbsent(shown, k -> new ArrayList<>())
                            .add(new SutraProblem(AboutParser.BAD_FILE, "the file could not be read: " + e.getMessage(), new SourceLocation(shown, 0, 0)));
                }
            }
        }
        Map<String, List<Loaded>> resolvedByTag = new java.util.TreeMap<>();
        byTag.forEach((tag, list) -> resolvedByTag.put(tag, resolveUses(list, base, problems)));
        Map<String, Map<String, AboutText>> out = new LinkedHashMap<>();
        resolvedByTag.forEach((tag, list) -> {
            List<Loaded> chain = new ArrayList<>(list);
            int dash = tag.indexOf('-');
            if (dash > 0 && resolvedByTag.containsKey(tag.substring(0, dash))) {
                chain.addAll(resolvedByTag.get(tag.substring(0, dash)));
            }
            chain.addAll(resolvedBase);
            out.put(tag, Map.copyOf(merge(chain)));
        });
        return out;
    }

    private static final java.util.regex.Pattern OVERLAY = java.util.regex.Pattern.compile("[^.]+\\.[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*\\.yaml");

    /** The languages that have an overlay, lower case, sorted. English is always there and is not listed. */
    public Set<String> locales() {
        return new java.util.TreeSet<>(snapshot.byLocale().keySet());
    }

    /**
     * Which language to answer in: the first requested tag that has an overlay, else its plain language's overlay, else
     * {@code en}. Tags may be written {@code fr_CA} or {@code FR-ca}.
     */
    public String localeFor(List<String> requested) {
        Map<String, Map<String, AboutText>> by = snapshot.byLocale();
        for (String r : requested == null ? List.<String>of() : requested) {
            if (r == null || r.isBlank()) {
                continue;
            }
            String tag = r.strip().replace('_', '-').toLowerCase(java.util.Locale.ROOT);
            if (by.containsKey(tag)) {
                return tag;
            }
            int dash = tag.indexOf('-');
            if (dash > 0 && by.containsKey(tag.substring(0, dash))) {
                return tag.substring(0, dash);
            }
        }
        return DEFAULT_LOCALE;
    }

    public static final String DEFAULT_LOCALE = "en";

    /** What the packs say about {@code kind} in {@code locale} (a value {@link #localeFor} returned), English where untranslated. */
    @Override
    public Optional<AboutText> forKind(String kind, String locale) {
        Map<String, AboutText> m = locale == null ? null : snapshot.byLocale().get(locale);
        return m == null ? forKind(kind) : Optional.ofNullable(m.get(kind));
    }

    /** What the packs say about {@code kind}, merged through {@code extends}, or empty when no pack says anything. */
    @Override
    public Optional<AboutText> forKind(String kind) {
        return Optional.ofNullable(snapshot.byKind().get(kind));
    }

    /** The pack whose Sutra directory holds {@code sutraFile}, or empty (a site Sutra). */
    public Optional<AboutProperties.PackSource> packOfSutra(String sutraFile) {
        if (sutraFile == null) {
            return Optional.empty();
        }
        Path f = Path.of(sutraFile).toAbsolutePath().normalize();
        AboutProperties.PackSource best = null;
        int bestLen = -1;
        for (AboutProperties.PackSource p : props.packs()) {
            if (p.sutraDir() == null) {
                continue;
            }
            Path dir = Path.of(p.sutraDir()).toAbsolutePath().normalize();
            if (f.startsWith(dir) && dir.getNameCount() > bestLen) {
                best = p;
                bestLen = dir.getNameCount();
            }
        }
        return Optional.ofNullable(best);
    }

    /** How a problem names the file: pack and file name, never a server path. */
    private static String display(AboutProperties.PackSource p) {
        return p.name() + "/" + Path.of(p.file()).getFileName();
    }

    /** Problems per about file from the last load; empty when every file is valid. */
    public Map<String, List<SutraProblem>> problems() {
        return snapshot.problems();
    }

    /** Changes whenever the catalogue is reloaded; part of the explain cache's key. */
    public long revision() {
        return revision.get();
    }

    /** The longest any one plain text of an entry may be. */
    public int maxText() {
        return props.maxText();
    }

    /** The longest a rendered template may be. */
    public int maxRendered() {
        return props.maxRendered();
    }

    /** Checks each {@code use} (resolved later, in {@link #merge}) against the vocabulary visible to its pack (its own file's and its parents'), most specific first. */
    private List<Loaded> resolveUses(List<Loaded> loaded, List<Loaded> context, Map<String, List<SutraProblem>> problems) {
        Map<String, Loaded> byPack = new LinkedHashMap<>();
        context.forEach(l -> byPack.put(l.pack().name(), l));
        List<Loaded> out = new ArrayList<>();
        for (Loaded l : loaded) {
            Map<String, KindAbout> kinds = new LinkedHashMap<>();
            l.parsed().kinds().forEach((kind, k) -> {
                Map<String, GlossaryEntry> glossary = new LinkedHashMap<>();
                k.glossary().forEach((path, e) -> {
                    if (e.use() == null) {
                        glossary.put(path, e.from(l.pack().name() + ":glossary." + path));
                        return;
                    }
                    GlossaryEntry v = visible(l.pack(), byPack, e.use());
                    if (v == null) {
                        problems.computeIfAbsent(display(l.pack()), x -> new ArrayList<>()).add(new SutraProblem(AboutParser.BAD_USE,
                                "use: " + e.use() + " names no vocabulary entry visible to pack " + l.pack().name() + " (its own or an extended pack's)", e.at()));
                    } else {
                        glossary.put(path, e);              // resolved when merged, against the most specific vocabulary
                    }
                });
                kinds.put(kind, new KindAbout(k.title(), k.about(), k.guide(), glossary, k.panels()));
            });
            out.add(new Loaded(l.pack(), new AboutParser.Parsed(l.parsed().vocabulary(), kinds, l.parsed().problems())));
        }
        return out;
    }

    private GlossaryEntry visible(AboutProperties.PackSource pack, Map<String, Loaded> byPack, String name) {
        for (String n : pack.lineage()) {
            Loaded l = byPack.get(n);
            GlossaryEntry e = l == null ? null : l.parsed().vocabulary().get(name);
            if (e != null) {
                return e.from(n + ":vocabulary." + name);
            }
        }
        return core.get(name);
    }

    private Map<String, AboutText> merge(List<Loaded> loaded) {
        Set<String> kinds = new LinkedHashSet<>();
        loaded.forEach(l -> kinds.addAll(l.parsed().kinds().keySet()));
        Map<String, AboutText> out = new LinkedHashMap<>();
        for (String kind : kinds) {
            List<Loaded> having = loaded.stream().filter(l -> l.parsed().kinds().containsKey(kind)).toList();
            Set<String> visibleFrom = new LinkedHashSet<>();
            having.forEach(l -> visibleFrom.addAll(l.pack().lineage()));
            Map<String, GlossaryEntry> vocabulary = new LinkedHashMap<>();
            for (Loaded l : loaded) {
                if (visibleFrom.contains(l.pack().name())) {
                    l.parsed().vocabulary().forEach((n, e) -> vocabulary.putIfAbsent(n, e.from(l.pack().name() + ":vocabulary." + n)));
                }
            }
            String title = null;
            String guide = null;
            Loaded aboutFrom = null;
            Loaded titleFrom = null;
            Map<String, GlossaryEntry> glossary = new LinkedHashMap<>();
            Map<String, com.ash.drishti.rachana.el.Template> panels = new LinkedHashMap<>();
            for (Loaded l : having) {                                  // most specific first: the first to say a thing wins
                KindAbout k = l.parsed().kinds().get(kind);
                if (title == null && k.title() != null) {
                    title = k.title();
                    titleFrom = l;
                }
                if (guide == null) {
                    guide = k.guide();
                }
                if (aboutFrom == null && k.about() != null) {
                    aboutFrom = l;
                }
                k.glossary().forEach((path, e) -> {
                    GlossaryEntry v = e.use() == null ? null : vocabulary.getOrDefault(e.use(), core.get(e.use()));
                    if (e.use() == null || v != null) {
                        glossary.putIfAbsent(path, v == null ? e : e.resolvedFrom(v));
                    }
                });
                k.panels().forEach(panels::putIfAbsent);
            }
            Loaded source = aboutFrom != null ? aboutFrom : titleFrom != null ? titleFrom : having.get(0);
            com.ash.drishti.rachana.el.Template about = aboutFrom == null ? null : aboutFrom.parsed().kinds().get(kind).about();
            out.put(kind, new AboutText(source.pack().name(), source.pack().title(), kind, title, about, guide, Map.copyOf(glossary),
                    Map.copyOf(panels), Map.copyOf(vocabulary)));
        }
        return out;
    }
}
