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
package com.ash.drishti.rachana.parse;

import com.ash.drishti.rachana.SutraException;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.SourceLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared panel blocks and the fragment library: panels written once in a block file ({@code <name>.v<N>.block.yaml} beside the Sutras) and used by many
 * Sutras, so a pack whose products share a layout (a deal ticket, legs side by side, events) keeps it in one place.
 *
 * <pre>
 * # sutras/common/deal-ticket.v1.block.yaml
 * rachana: 1
 * block: deal-ticket
 * version: 1
 * description: The deal ticket of a trade.
 * params: { rate-fmt: price4 }          # defaults; the text reads them as %{rate-fmt}
 * panels:
 *   - { id: ticket, kind: kv, title: Deal ticket, columns: [ { label: Strike, bind: $.STRIKE_PRICE, fmt: "%{rate-fmt}" } ] }
 *
 * # a Sutra's panels:, or a tabs panel's panels:
 *   - { use: deal-ticket@1, with: { rate-fmt: pct4 } }
 * </pre>
 *
 * A use is replaced by the block's panels, in order, before the Sutra is checked, so the panels are linted, matched and drawn as if
 * they had been written in place. A fragment ({@code <name>.v<N>.fragment.yaml}, {@code fragment: <name>}, the shared library in
 * the Drishti UI kit) is a block whose {@code doc:} is complete ({@link SutraDocstring}, {@code DRS-2055}); it is
 * used the same way. The platform's fragments are the Drishti UI kit ({@code config/ui-kit/fragments}, {@link #kit(Path)}), read by every
 * load whatever packs are enabled; a pack's own blocks are namespaced by the pack ({@code trading/legs@1}). {@code @1} names the block's version (without one, the newest); {@code with} sets its parameters
 * (an unknown one, an unknown block or a block that uses another is {@code DRS-2048}). The library is a snapshot replaced whole when
 * the Sutra folders are read again; a parse reads the snapshot of that moment. Thread-safe.
 */
public final class SutraBlocks {

    /** A block's problem: unknown, badly written, a parameter it does not declare. */
    public static final String BLOCK = "DRS-2048";
    private static final Pattern PARAM = Pattern.compile("%\\{([a-z][a-z0-9-]*)}");
    private static final Pattern FILE = Pattern.compile("(.+)\\.v(\\d+)\\.(block|fragment)\\.yaml");
    private static final PositionalYamlReader READER = new PositionalYamlReader();

    /**
     * One block: its text (parameters not yet filled), its default parameters, its file name and the path it was read from (the
     * file name when it was not read from disk).
     */
    public record Block(String name, int version, String text, Map<String, String> params, String file, String path) {

        public Block(String name, int version, String text, Map<String, String> params, String file) {
            this(name, version, text, params, file, file);
        }

        /** The block's {@code description:} (empty when it has none). */
        public String description() {
            try {
                PNode d = parse(text.replace("%{", "pXq{"), file).map().get("description");
                return d == null || d.text() == null ? "" : d.text().replace("pXq{", "%{");
            } catch (SutraException e) {
                return "";
            }
        }

        /** Whether it is a fragment of the library ({@code <name>.v<N>.fragment.yaml}, {@code fragment: <name>}): its doc is checked strictly. */
        public boolean fragment() {
            return file != null && file.endsWith(".fragment.yaml") || text.startsWith("fragment:") || text.contains("\nfragment:");
        }

        /** Its {@code doc:} as plain maps, lists and text (empty when it has none). */
        public Map<String, Object> doc() {
            try {
                return SutraDocstring.plain(parse(text.replace("%{", "pXq{"), file));
            } catch (SutraException e) {
                return Map.of();
            }
        }

        /** {@code name@version}. */
        public String ref() {
            return name + "@" + version;
        }
    }

    private static final AtomicReference<Map<String, TreeMap<Integer, Block>>> LIBRARY = new AtomicReference<>(Map.of());
    private static final AtomicReference<List<SutraProblem>> PROBLEMS = new AtomicReference<>(List.of());
    private static final AtomicReference<List<SutraProblem>> WARNINGS = new AtomicReference<>(List.of());
    private static final AtomicReference<List<SutraProblem>> CLASHES = new AtomicReference<>(List.of());
    private static final AtomicReference<Path> KIT = new AtomicReference<>();

    private SutraBlocks() {}

    /** Whether a file is a block file ({@code .block.yaml}) or a fragment of the library ({@code .fragment.yaml}). */
    public static boolean isBlockFile(Path f) {
        String n = f.getFileName() == null ? "" : f.getFileName().toString();
        return n.endsWith(".block.yaml") || n.endsWith(".fragment.yaml");
    }

    /**
     * Where the Drishti UI kit is ({@code drishti.ui-kit.dir}, its {@code fragments/} folder read by every {@link #load}), or null for
     * none (a unit test of blocks alone). The server and the CLI set it before the first load.
     */
    public static void kit(Path dir) {
        KIT.set(dir == null ? null : dir.toAbsolutePath().normalize());
    }

    /** The Drishti UI kit folder set by {@link #kit(Path)}, if any. */
    public static java.util.Optional<Path> kit() {
        return java.util.Optional.ofNullable(KIT.get());
    }

    /** Whether a block was read from the Drishti UI kit (an unqualified platform fragment) rather than a pack or a site folder. */
    public static boolean fromKit(Block b) {
        Path k = KIT.get();
        if (k == null || b.path() == null) {
            return false;
        }
        try {
            return Path.of(b.path()).toAbsolutePath().normalize().startsWith(k);
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    /**
     * The pack a Sutra folder belongs to: the {@code name:} of the {@code pack.yaml} beside it ({@code <pack>/sutras}), else the pack
     * folder's name; null for a site folder (its blocks stay unqualified, as before packs were namespaced).
     */
    public static String packOf(Path dir) {
        Path parent = dir == null ? null : dir.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isRegularFile(parent.resolve("pack.yaml"))) {
            return null;
        }
        try (Stream<String> lines = Files.lines(parent.resolve("pack.yaml"), StandardCharsets.UTF_8)) {
            String name = lines.filter(l -> l.startsWith("name:")).map(l -> l.substring(5).trim().replace("'", "").replace("\"", ""))
                    .findFirst().orElse("");
            if (name.matches("[a-z][a-z0-9-]{0,63}")) {
                return name;
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // the folder's name below
        }
        String folder = parent.getFileName().toString();
        return folder.matches("[a-z][a-z0-9-]{0,63}") ? folder : null;
    }

    /**
     * Reads the Drishti UI kit's fragments (when a kit is set) and every block file under the folders: the library becomes exactly
     * these. Kit fragments are unqualified ({@code series-line@1}); a pack's blocks are namespaced by the pack ({@code trading/legs@1})
     * and also answer to their plain name unless the kit has that name: on a clash the kit wins and the pack's stays reachable only
     * qualified, with a warning ({@link #clashes()}). Among folders, a later one (a more specific pack) wins a plain name. Returns the
     * problems of the files that are not blocks.
     */
    public static List<SutraProblem> load(Collection<Path> dirs) {
        Map<String, TreeMap<Integer, Block>> next = new TreeMap<>();
        List<SutraProblem> problems = new ArrayList<>();
        List<SutraProblem> warnings = new ArrayList<>();
        List<SutraProblem> clashes = new ArrayList<>();
        java.util.Set<String> kitNames = new java.util.HashSet<>();
        Path kit = KIT.get() == null ? null : KIT.get().resolve("fragments");
        List<Path> all = new ArrayList<>();
        if (kit != null) {
            all.add(kit);
        }
        for (Path d : dirs) {
            Path n = d.toAbsolutePath().normalize();
            if (kit == null || !(n.startsWith(kit) || kit.startsWith(n))) {   // the kit is read once, first (a caller may still name it)
                all.add(d);
            }
        }
        for (Path dir : all) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            boolean isKit = dir == kit;
            String pack = isKit ? null : packOf(dir);
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path f : s.filter(SutraBlocks::isBlockFile).sorted().toList()) {
                    try {
                        Block b = read(Files.readString(f, StandardCharsets.UTF_8), f.getFileName().toString(), f.toString());
                        String plain = b.name();
                        if (pack != null) {                            // namespaced by its pack: trading/deal-ticket
                            b = new Block(pack + "/" + plain, b.version(), b.text(), b.params(), b.file(), b.path());
                            next.computeIfAbsent(b.name(), k -> new TreeMap<>()).put(b.version(), b);
                        }
                        if (isKit) {
                            kitNames.add(plain);
                            next.computeIfAbsent(plain, k -> new TreeMap<>()).put(b.version(), b);
                        } else if (kitNames.contains(plain)) {
                            clashes.add(new SutraProblem(BLOCK, (pack == null ? "the site block " : "pack " + pack + "'s ") + plain
                                    + " has the name of a Drishti UI kit fragment: the kit's wins" + (pack == null ? " (rename it)"
                                    : "; use " + pack + "/" + plain + "@" + b.version() + " for the pack's"), new SourceLocation(f.toString(), 1, 1)));
                        } else {
                            next.computeIfAbsent(plain, k -> new TreeMap<>()).put(b.version(), b);   // a later folder (a more specific pack) wins
                        }
                        if (!b.fragment()) {                           // an older block: its doc is a warning today
                            warnings.addAll(SutraDocstring.checkSutra(parse(b.text().replace("%{", "pXq{"), b.file()), "block " + plain, f.toString()));
                        }
                    } catch (SutraException e) {
                        problems.addAll(e.problems());
                    } catch (IOException e) {
                        problems.add(problem("cannot read " + f + ": " + e.getMessage(), f.getFileName().toString(), 1, 1));
                    }
                }
            } catch (IOException e) {
                problems.add(problem("cannot scan " + dir + ": " + e.getMessage(), dir.toString(), 1, 1));
            }
        }
        LIBRARY.set(Map.copyOf(next));
        java.util.Set<Block> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (TreeMap<Integer, Block> versions : next.values()) {
            for (Block b : versions.values()) {
                if (seen.add(b) && b.fragment() && b.text().contains("use:")) {
                    List<SutraProblem> inner = new ArrayList<>();
                    expand(List.of(new PNode(Map.of("use", new PNode(b.ref(), 1, 1)), 1, 1)), b.path(), inner);
                    problems.addAll(inner);
                }
            }
        }
        PROBLEMS.set(List.copyOf(problems));
        WARNINGS.set(List.copyOf(warnings));
        CLASHES.set(List.copyOf(clashes));
        return problems;
    }

    /** Each block once (a pack's block is listed under its qualified and its plain name), in name order. */
    public static List<Block> blocks() {
        java.util.Set<Block> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Block> out = new ArrayList<>();
        LIBRARY.get().values().forEach(vs -> vs.values().stream().filter(seen::add).forEach(out::add));
        out.sort(java.util.Comparator.comparing(Block::name).thenComparing(Block::version));
        return out;
    }

    /** The names a pack or site block shares with a Drishti UI kit fragment, read last: the kit's won ({@code DRS-2048}, a warning). */
    public static List<SutraProblem> clashes() {
        return CLASHES.get();
    }

    /** The blocks known now: name to versions. */
    public static Map<String, TreeMap<Integer, Block>> library() {
        return LIBRARY.get();
    }

    /** The problems of the block files read last. */
    public static List<SutraProblem> problems() {
        return PROBLEMS.get();
    }

    /** The warnings of the block files read last: an older block without a complete {@code doc:} ({@code DRS-2055}). */
    public static List<SutraProblem> warnings() {
        return WARNINGS.get();
    }

    /** One block file's content, checked: {@code rachana: 1}, {@code block}, {@code version}, {@code panels} (a list), {@code params} (scalars). */
    public static Block read(String text, String file) {
        return read(text, file, file);
    }

    /** As {@link #read(String, String)}, remembering the path the file was read from. */
    public static Block read(String text, String file, String path) {
        PNode root = parse(text.replace("%{", "pXq{"), file);         // parameters are filled later; read the shape with them masked
        Map<String, PNode> m = root.map();
        Matcher fm = FILE.matcher(file);
        boolean fragment = file.endsWith(".fragment.yaml") || m.containsKey("fragment");
        String head = fragment ? "fragment" : "block";
        String name = m.get(head) == null ? null : m.get(head).text();
        if (name == null || !name.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new SutraException(List.of(problem("a " + head + " file names its " + head + ": " + head + ": <lower-case-kebab name>", file, 1, 1)));
        }
        int version = m.get("version") == null || m.get("version").text() == null ? 1 : Integer.parseInt(m.get("version").text());
        if (fm.matches() && (!fm.group(1).equals(name) || Integer.parseInt(fm.group(2)) != version)) {
            throw new SutraException(List.of(problem("the file " + file + " holds block " + name + " version " + version
                    + ": name it " + name + ".v" + version + "." + head + ".yaml", file, 1, 1)));
        }
        PNode panels = m.get("panels");
        if (panels == null || !panels.isList() || panels.list().isEmpty()) {
            throw new SutraException(List.of(problem("block " + name + " needs panels: a list of panels", file, 1, 1)));
        }
        List<String> inner = new ArrayList<>();
        for (PNode p : panels.list()) {
            if (p.map().containsKey("use")) {
                String ref = p.map().get("use").text() == null ? "" : p.map().get("use").text().trim();
                if (!fragment) {
                    throw new SutraException(List.of(problem("block " + name + " uses another block: a block holds panels only (a fragment may use fragments)",
                            file, p.line(), p.column())));
                }
                if (!ref.matches("([a-z][a-z0-9-]{0,63}/)?[a-z][a-z0-9-]{1,63}@[0-9]+")) {
                    throw new SutraException(List.of(problem("fragment " + name + " uses " + ref + " without a version: a composite pins its inner "
                            + "fragments (use: name@N)", file, p.line(), p.column())));
                }
                inner.add(ref);
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        PNode ps = m.get("params");
        if (ps != null) {
            ps.map().forEach((k, v) -> params.put(k, v.text() == null ? "" : v.text().replace("pXq{", "%{")));
        }
        if (fragment) {                                                // a fragment of the library carries a complete doc: (strict)
            List<SutraProblem> doc = new ArrayList<>(SutraDocstring.checkFragment(root, name, params, file));
            doc.addAll(SutraDocstring.checkUses(root, name, inner, file));
            if (!doc.isEmpty()) {
                throw new SutraException(doc);
            }
        }
        return new Block(name, version, text, Map.copyOf(params), file, path);
    }

    /** The block a use names ({@code name@N}, or {@code name} for the newest version), if it is loaded. */
    public static java.util.Optional<Block> find(String ref) {
        String r = ref == null ? "" : ref.trim();
        String name = r.contains("@") ? r.substring(0, r.indexOf('@')) : r;
        TreeMap<Integer, Block> versions = LIBRARY.get().get(name);
        if (versions == null || versions.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(r.contains("@") ? versions.get(parseVersion(r.substring(r.indexOf('@') + 1))) : versions.lastEntry().getValue());
    }

    /** Whether an item of a panels list is a use of a block ({@code { use: name@N, with: {...} }}). */
    public static boolean isUse(PNode item) {
        return item != null && item.isMap() && item.map().containsKey("use");
    }

    /**
     * How many panels each item of a written panels list stands for once expanded: 1 for a panel, the block's panel count for a use
     * (0 when the use does not resolve). The design operations map a panel's place among the expanded panels to its written item.
     */
    public static List<Integer> sizes(List<PNode> panels) {
        List<Integer> out = new ArrayList<>();
        for (PNode p : panels) {
            out.add(isUse(p) ? expand(List.of(p), "studio", new ArrayList<>()).size() : 1);
        }
        return out;
    }

    /**
     * The panels list with every {@code use} replaced by its block's panels (parameters filled); problems go to {@code problems}. A list
     * without a use comes back as it is.
     */
    public static List<PNode> expand(List<PNode> panels, String file, List<SutraProblem> problems) {
        return expand(panels, file, problems, new ArrayList<>());
    }

    /** The deepest a use may nest: a Sutra's use of a composite fragment that uses fragments that use fragments. */
    public static final int MAX_DEPTH = 3;

    private static List<PNode> expand(List<PNode> panels, String file, List<SutraProblem> problems, List<String> chain) {
        if (panels.stream().noneMatch(p -> p.map().containsKey("use"))) {
            return panels;
        }
        List<PNode> out = new ArrayList<>();
        for (PNode p : panels) {
            PNode use = p.map().get("use");
            if (use == null) {
                out.add(p);
                continue;
            }
            String ref = use.text() == null ? "" : use.text().trim();
            String name = ref.contains("@") ? ref.substring(0, ref.indexOf('@')) : ref;
            TreeMap<Integer, Block> versions = LIBRARY.get().get(name);
            Block b = null;
            if (versions != null) {
                b = ref.contains("@") ? versions.get(parseVersion(ref.substring(ref.indexOf('@') + 1))) : versions.lastEntry().getValue();
            }
            if (b != null && chain.contains(b.name())) {
                problems.add(problem("fragment " + b.name() + " uses itself through " + String.join(" > ", chain) + " > " + b.name(), file, p.line(), p.column()));
                continue;
            }
            if (b != null && chain.size() >= MAX_DEPTH) {
                problems.add(problem("fragments nest at most " + MAX_DEPTH + " deep: " + String.join(" > ", chain) + " > " + b.name(), file, p.line(), p.column()));
                continue;
            }
            if (b == null) {
                problems.add(problem("no block " + ref + (LIBRARY.get().isEmpty() ? " (no block files are loaded)" : " (blocks: "
                        + String.join(", ", LIBRARY.get().keySet()) + ")"), file, p.line(), p.column()));
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>(b.params());
            PNode with = p.map().get("with");
            boolean ok = true;
            if (with != null) {
                for (Map.Entry<String, PNode> e : with.map().entrySet()) {
                    if (!b.params().containsKey(e.getKey())) {
                        problems.add(problem("block " + b.name() + " has no parameter '" + e.getKey() + "' (it has " + b.params().keySet() + ")",
                                file, e.getValue().line(), e.getValue().column()));
                        ok = false;
                    } else {
                        values.put(e.getKey(), e.getValue().text() == null ? "" : e.getValue().text());
                    }
                }
            }
            if (!ok) {
                continue;
            }
            Matcher pm = PARAM.matcher(b.text());
            StringBuilder filled = new StringBuilder();
            while (pm.find()) {
                String v = values.get(pm.group(1));
                if (v == null) {
                    problems.add(problem("block " + b.name() + " reads %{" + pm.group(1) + "}, which it does not declare under params", b.file(), 1, 1));
                    v = "";
                }
                pm.appendReplacement(filled, Matcher.quoteReplacement(v));
            }
            pm.appendTail(filled);
            try {
                List<PNode> brought = parse(filled.toString(), b.file()).map().get("panels").list();
                List<String> deeper = new ArrayList<>(chain);
                deeper.add(b.name());
                out.addAll(wired(p, expand(brought, b.file(), problems, deeper), file, problems));   // a composite's own uses, one level down
            } catch (SutraException e) {
                problems.addAll(e.problems());
            }
        }
        return out;
    }

    /** Keys a use may carry beside use and with (SUTRA_NEXT.md 6 to 8): put on every panel it brings. */
    private static final java.util.Set<String> USE_ON_ALL = java.util.Set.of("source", "when", "waits", "refresh");
    /** Keys a use may carry only when it brings one panel: that panel's events, children and size. */
    private static final java.util.Set<String> USE_ON_ONE = java.util.Set.of("emits", "children", "span", "height", "area", "form");

    /**
     * The panels a use brings with the use's own wiring and size on them ({@code { use: series-line@1, with: {...}, source: {...} }}): source,
     * when and waits on each, emits, children, span, height and area only on a use that brings one panel ({@code DRS-2048} otherwise).
     */
    private static List<PNode> wired(PNode use, List<PNode> brought, String file, List<SutraProblem> problems) {
        Map<String, PNode> extra = new LinkedHashMap<>();
        use.map().forEach((k, v) -> {
            if ("use".equals(k) || "with".equals(k)) {
                return;
            }
            if (!USE_ON_ALL.contains(k) && !USE_ON_ONE.contains(k)) {
                problems.add(problem("a use takes use, with, and " + String.join(", ", new java.util.TreeSet<>(USE_ON_ALL)) + ", "
                        + String.join(", ", new java.util.TreeSet<>(USE_ON_ONE)) + ", not '" + k + "'", file, v.line(), v.column()));
            } else if (USE_ON_ONE.contains(k) && brought.size() != 1) {
                problems.add(problem("'" + k + "' on a use that brings " + brought.size() + " panels: it wires one panel; write the panel, or use a fragment that brings one",
                        file, v.line(), v.column()));
            } else {
                extra.put(k, v);
            }
        });
        if (extra.isEmpty()) {
            return brought;
        }
        List<PNode> out = new ArrayList<>(brought.size());
        for (PNode q : brought) {
            if (!q.isMap()) {
                out.add(q);
                continue;
            }
            Map<String, PNode> m = new LinkedHashMap<>(q.map());
            m.putAll(extra);
            out.add(new PNode(m, q.line(), q.column()));
        }
        return out;
    }

    private static int parseVersion(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static PNode parse(String text, String file) {
        try {
            return READER.read(text, new ArrayList<>());
        } catch (JsonProcessingException e) {
            var l = e.getLocation();
            throw new SutraException(List.of(new SutraProblem("DRS-2001", "YAML syntax: " + e.getOriginalMessage(),
                    new SourceLocation(file, l == null ? 0 : l.getLineNr(), l == null ? 0 : l.getColumnNr()))));
        } catch (IOException e) {
            throw new SutraException(List.of(problem("cannot read: " + e.getMessage(), file, 0, 0)));
        }
    }

    private static SutraProblem problem(String message, String file, int line, int column) {
        return new SutraProblem(BLOCK, message, new SourceLocation(file, line, column));
    }
}
