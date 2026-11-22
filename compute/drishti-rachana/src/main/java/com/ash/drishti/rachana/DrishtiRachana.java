/*
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Proprietary: see LICENSE.
 */
package com.ash.drishti.rachana;

import com.ash.rachana.el.BusinessCalendar;
import com.ash.rachana.Rachana;
import com.ash.rachana.RachanaOptions;
import com.ash.rachana.el.ElCompiler;
import com.ash.rachana.el.FunctionProvider;
import com.ash.rachana.parse.BlockLibrary;
import com.ash.rachana.parse.SutraParser;
import com.ash.rachana.profile.Entities;
import com.ash.rachana.profile.Profile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * The holder of Drishti's one {@link Rachana} instance: Core plus the {@code drishti} profile (SPLIT_PLAN 8.3c, 8.3d, 8.3i). The profile adds what
 * Drishti's Sutras say and Core does not: {@code match} and {@code domain}, the panel kinds {@code links} and {@code provenance}, the
 * {@code kind} source form and form write-back ({@link Entities#addTo}), the UI kit's and packs' fragments (the one {@link BlockLibrary}), the
 * engine's functions (every {@link FunctionProvider}: the pivot engine's through {@link ServiceLoader}, a started module's through
 * {@link #addFunctions}) and the business calendars. The instance is immutable: starting or stopping a module builds a new one and swaps it in, so
 * a reader holds whatever {@link #current()} gave it. A compiler taken from an older instance does not see a later module's functions: take
 * {@link #compiler()} when compiling.
 */
public final class DrishtiRachana {

    /** The one language level of this server: Rachana 1.x reads one level, 1.2. */
    public static final String LEVEL = com.ash.rachana.profile.Grammar.LEVEL;

    private static final Object LOCK = new Object();
    private static final BlockLibrary BLOCKS = new BlockLibrary();
    /**
     * The started modules' functions by module id and function names, the latest registration in use: two servers in one JVM (a test's two
     * contexts) that both start a module never provide its functions twice, and one stopping leaves the other's in place.
     */
    private static final Map<String, java.util.Deque<FunctionProvider>> MODULES = new LinkedHashMap<>();
    private static volatile RachanaOptions options = RachanaOptions.DEFAULTS;
    private static volatile Supplier<? extends Collection<String>> kinds = List::of;
    private static volatile Rachana current;
    /** The Rachana libraries ({@link DrishtiLibraries}): the bundled ones unless {@link #libraries} says otherwise. */
    private static volatile com.ash.rachana.lib.LibrarySet libraries = DrishtiLibraries.of(null);
    private static final List<com.ash.rachana.format.Formats> SERVED = new ArrayList<>();
    /** What {@link LiveCompiler#follow} built, dropped at every change of instance (held weakly: a closed context's go with it). */
    private static final java.util.Set<LiveCompiler.Follower<?>> FOLLOWERS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** The functions of a module that was stopped, by name: the module's id (a Sutra calling one is told the module is off). */
    private static final Map<String, String> OFF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern UNKNOWN = java.util.regex.Pattern.compile("unknown function '([^']+)'(; known: [^\\n]*)?");

    private DrishtiRachana() {
    }

    /** The block library (the UI kit, pack and site fragments, and what started modules contribute). */
    public static BlockLibrary blocks() {
        return BLOCKS;
    }

    /** The instance now: Core, the {@code drishti} profile, the functions registered so far. */
    public static Rachana current() {
        Rachana r = current;
        if (r == null) {
            synchronized (LOCK) {
                if (current == null) {
                    current = build();
                }
                r = current;
            }
        }
        return r;
    }

    /** A parser that reads Drishti's Sutras ({@code match}, {@code links}, ... accepted; blocks resolved through {@link #blocks()}). */
    public static SutraParser parser() {
        return current().parser();
    }

    /**
     * The compiler of the instance now (engine and module functions included). A reader that keeps a compiler takes {@link LiveCompiler}
     * instead, never this one's result.
     */
    public static ElCompiler compiler() {
        return current().compiler();
    }

    /**
     * The format table the server's readers hold (the Spring bean): the instance's live table ({@code Formats.live}). A pack reload swaps
     * its contents in place ({@link #reloadFormats}), so every holder sees the new names at its next read.
     */
    public static com.ash.rachana.format.Formats formats() {
        com.ash.rachana.format.Formats f = current().formats();
        synchronized (LOCK) {
            if (SERVED.stream().noneMatch(x -> x == f)) {
                SERVED.add(f);
            }
        }
        return f;
    }

    /**
     * The packs' formats files changed ({@code drishti.rachana.pack-formats-files}): every table handed out and the instance's own read
     * the bundled table, these files and the site file again; a later instance (a module started) reads the same files. Checked first: a
     * file that does not read refuses the reload and nothing changes.
     */
    public static Runnable prepareFormats(List<String> packFiles) {
        List<String> files = packFiles == null ? List.of() : List.copyOf(packFiles);
        com.ash.rachana.format.Formats.load(options.formatsFile(), files);         // throws before anything changes
        return () -> {
            synchronized (LOCK) {
                options = options.withFormats(options.formatsFile(), files);
                List<com.ash.rachana.format.Formats> tables = new ArrayList<>(SERVED);
                Rachana r = current;
                if (r != null && tables.stream().noneMatch(x -> x == r.formats())) {
                    tables.add(r.formats());
                }
                tables.forEach(t -> t.reload(files));
            }
        };
    }

    /** The settings (cache size, limits, the site's formats file, the packs' formats files), then a new instance. */
    public static void configure(RachanaOptions o, Supplier<? extends Collection<String>> servedKinds) {
        synchronized (LOCK) {
            options = o == null ? RachanaOptions.DEFAULTS : o;
            if (servedKinds != null) {
                kinds = servedKinds;
            }
            changed();
        }
    }

    /**
     * The process-wide settings now (options, served kinds) as an action that puts them back: a second context started in
     * the same JVM (the {@code sutra} command run in-process, as a test does) configures this class for itself, and its settings name its
     * own scratch folder, removed when it ends; the action leaves the first context's instance as it was.
     */
    public static Runnable keep() {
        synchronized (LOCK) {
            RachanaOptions o = options;
            Supplier<? extends Collection<String>> k = kinds;
            com.ash.rachana.lib.LibrarySet libs = libraries;
            return () -> {
                synchronized (LOCK) {
                    options = o;
                    kinds = k;
                    libraries = libs;
                    changed();
                }
            };
        }
    }

    /**
     * The Rachana libraries Sutras may declare ({@code libraries: [core@1]}, {@code use: core/record-card@1}): one resolver chain, the lock
     * that pins it ({@link DrishtiLibraries#of}); a new instance. Problems of a library (not found {@code DRS-2110}, not in the lock or
     * another major {@code DRS-2111}, invalid {@code DRS-2112}, not the pinned bytes {@code DRS-2113}) are reported where a Sutra uses it.
     */
    public static void libraries(com.ash.rachana.lib.LibrarySet set) {
        synchronized (LOCK) {
            libraries = set == null ? DrishtiLibraries.of(null) : set;
            changed();
        }
    }

    /** The libraries now (for the palette and the checks). */
    public static com.ash.rachana.lib.LibrarySet libraries() {
        return libraries;
    }

    /** The entity kinds this server serves (the schema's {@code match.kind} list); read each time a schema is built, so no new instance. */
    public static void servedKinds(Supplier<? extends Collection<String>> servedKinds) {
        kinds = servedKinds == null ? List::of : servedKinds;
    }

    /** From {@link RachanaProperties}: no setting is renamed. */
    public static RachanaOptions optionsOf(RachanaProperties props) {
        return RachanaOptions.DEFAULTS.withExpressionCacheSize(props.expressionCacheSize()).withExpressionLimits(props.expressionLimits())
                .withFormats(props.formatsFile(), props.packFormatsFiles());
    }

    /** The server's language level: the one level. */
    public static String level() {
        return LEVEL;
    }

    /**
     * A started module's functions join Core's table; the returned action takes them out (a stop), and a Sutra that still calls one is told
     * the module is off ({@link #drs(com.ash.rachana.SutraProblem)}).
     */
    public static Runnable addFunctions(String module, FunctionProvider provider) {
        List<String> names = provider.functions().stream().map(FunctionProvider.Provided::name).toList();
        String key = module + "\u001f" + String.join(",", names);
        synchronized (LOCK) {
            MODULES.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).push(provider);
            names.forEach(OFF::remove);
            changed();
        }
        return () -> {
            synchronized (LOCK) {
                java.util.Deque<FunctionProvider> d = MODULES.get(key);
                if (d != null) {
                    d.removeIf(x -> x == provider);
                    if (d.isEmpty()) {
                        MODULES.remove(key);
                        names.forEach(n -> OFF.put(n, module));
                    }
                }
                changed();
            }
        };
    }

    /** Registers what {@link LiveCompiler#follow} built, to be dropped at the next change of instance. */
    static void follow(LiveCompiler.Follower<?> f) {
        synchronized (LOCK) {
            FOLLOWERS.add(f);
        }
    }

    /** The instance changes (under the lock): the next reader builds the new one; nothing built on the older one is kept. */
    private static void changed() {
        current = null;
        FOLLOWERS.forEach(LiveCompiler.Follower::forget);
    }

    /** Forgets everything (a test starting clean): no module functions, default settings, an empty block library. */
    public static void reset() {
        synchronized (LOCK) {
            MODULES.clear();
            OFF.clear();
            SERVED.clear();
            options = RachanaOptions.DEFAULTS;
            kinds = List::of;
            libraries = DrishtiLibraries.of(null);
            changed();
        }
    }

    /** Core's {@code RCH-} code as Drishti's {@code DRS-} one (the same number): the API edge's mapping (SPLIT_PLAN 8.3e, A1). */
    public static String drs(String code) {
        return code != null && code.startsWith("RCH-") ? "DRS-" + code.substring(4) : code;
    }

    /**
     * A problem with its code mapped by {@link #drs(String)}, and a call of a stopped module's function said as that: the module is off here
     * ({@link #offWords(String)}).
     */
    public static com.ash.rachana.SutraProblem drs(com.ash.rachana.SutraProblem p) {
        if (p == null) {
            return null;
        }
        String words = offWords(p.message());
        return (p.code() == null || !p.code().startsWith("RCH-")) && words.equals(p.message()) ? p
                : new com.ash.rachana.SutraProblem(drs(p.code()), p.severity(), words, p.location(), p.path(), p.range());
    }

    /**
     * An edit's outcome with each problem's code mapped by {@link #drs(String)}: the design operations ({@code /builder/edit}, a Design's
     * ops and its rebase) answer Drishti's {@code DRS-} codes as every other Sutra check does.
     */
    public static com.ash.rachana.design.ops.OpResult drs(com.ash.rachana.design.ops.OpResult r) {
        if (r == null || r.problems().stream().noneMatch(p -> p.code() != null && p.code().startsWith("RCH-"))) {
            return r;
        }
        List<com.ash.rachana.design.ops.OpProblem> mapped = r.problems().stream()
                .map(p -> new com.ash.rachana.design.ops.OpProblem(p.op(), p.name(), drs(p.code()), p.message(), p.line())).toList();
        return new com.ash.rachana.design.ops.OpResult(r.yaml(), mapped, r.applied());
    }

    /** A refusal's words with "unknown function 'f'" of a stopped module's function said as "the module is off". */
    public static String offWords(String message) {
        if (message == null || OFF.isEmpty()) {
            return message;
        }
        java.util.regex.Matcher m = UNKNOWN.matcher(message);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String module = OFF.get(m.group(1));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(module == null ? m.group()
                    : "the function '" + m.group(1) + "' is off: its module '" + module + "' is not running on this server"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The problems with their codes mapped. */
    public static List<com.ash.rachana.SutraProblem> drs(List<com.ash.rachana.SutraProblem> problems) {
        return problems == null ? null : problems.stream().map(DrishtiRachana::drs).toList();
    }

    private static Rachana build() {
        List<FunctionProvider> providers = new ArrayList<>();
        for (FunctionProvider p : ServiceLoader.load(FunctionProvider.class)) {
            providers.add(p);
        }
        MODULES.values().forEach(d -> providers.add(d.peek()));
        Profile drishti = Entities.addTo(Profile.named("drishti"), () -> kinds.get(), BLOCKS)
                .functions(providers.toArray(FunctionProvider[]::new))
                .calendars(spec -> BusinessCalendar.of(spec))
                .build();
        return Rachana.with(options, Profile.core(), drishti).libraries(libraries);   // Rachana 1.x reads one language level; library uses are qualified
    }
}
