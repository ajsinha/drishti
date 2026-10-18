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
 * Shared panel blocks: panels written once in a block file ({@code <name>.v<N>.block.yaml} beside the Sutras) and used by many
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
 * they had been written in place. {@code @1} names the block's version (without one, the newest); {@code with} sets its parameters
 * (an unknown one, an unknown block or a block that uses another is {@code DRS-2048}). The library is a snapshot replaced whole when
 * the Sutra folders are read again; a parse reads the snapshot of that moment. Thread-safe.
 */
public final class SutraBlocks {

    /** A block's problem: unknown, badly written, a parameter it does not declare. */
    public static final String BLOCK = "DRS-2048";
    private static final Pattern PARAM = Pattern.compile("%\\{([a-z][a-z0-9-]*)}");
    private static final Pattern FILE = Pattern.compile("(.+)\\.v(\\d+)\\.block\\.yaml");
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

        /** {@code name@version}. */
        public String ref() {
            return name + "@" + version;
        }
    }

    private static final AtomicReference<Map<String, TreeMap<Integer, Block>>> LIBRARY = new AtomicReference<>(Map.of());
    private static final AtomicReference<List<SutraProblem>> PROBLEMS = new AtomicReference<>(List.of());

    private SutraBlocks() {}

    /** Whether a file is a block file. */
    public static boolean isBlockFile(Path f) {
        return f.getFileName() != null && f.getFileName().toString().endsWith(".block.yaml");
    }

    /** Reads every block file under the folders (the library becomes exactly these); returns the problems of the files that are not blocks. */
    public static List<SutraProblem> load(Collection<Path> dirs) {
        Map<String, TreeMap<Integer, Block>> next = new TreeMap<>();
        List<SutraProblem> problems = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path f : s.filter(SutraBlocks::isBlockFile).sorted().toList()) {
                    try {
                        Block b = read(Files.readString(f, StandardCharsets.UTF_8), f.getFileName().toString(), f.toString());
                        next.computeIfAbsent(b.name(), k -> new TreeMap<>()).put(b.version(), b);   // a later folder (a more specific pack) wins
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
        PROBLEMS.set(List.copyOf(problems));
        return problems;
    }

    /** The blocks known now: name to versions. */
    public static Map<String, TreeMap<Integer, Block>> library() {
        return LIBRARY.get();
    }

    /** The problems of the block files read last. */
    public static List<SutraProblem> problems() {
        return PROBLEMS.get();
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
        String name = m.get("block") == null ? null : m.get("block").text();
        if (name == null || !name.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new SutraException(List.of(problem("a block file names its block: block: <lower-case-kebab name>", file, 1, 1)));
        }
        int version = m.get("version") == null || m.get("version").text() == null ? 1 : Integer.parseInt(m.get("version").text());
        if (fm.matches() && (!fm.group(1).equals(name) || Integer.parseInt(fm.group(2)) != version)) {
            throw new SutraException(List.of(problem("the file " + file + " holds block " + name + " version " + version
                    + ": name it " + name + ".v" + version + ".block.yaml", file, 1, 1)));
        }
        PNode panels = m.get("panels");
        if (panels == null || !panels.isList() || panels.list().isEmpty()) {
            throw new SutraException(List.of(problem("block " + name + " needs panels: a list of panels", file, 1, 1)));
        }
        for (PNode p : panels.list()) {
            if (p.map().containsKey("use")) {
                throw new SutraException(List.of(problem("block " + name + " uses another block: a block holds panels only", file, p.line(), p.column())));
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        PNode ps = m.get("params");
        if (ps != null) {
            ps.map().forEach((k, v) -> params.put(k, v.text() == null ? "" : v.text().replace("pXq{", "%{")));
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
                out.addAll(parse(filled.toString(), b.file()).map().get("panels").list());
            } catch (SutraException e) {
                problems.addAll(e.problems());
            }
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
