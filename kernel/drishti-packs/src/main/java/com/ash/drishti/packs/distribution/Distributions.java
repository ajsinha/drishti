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
package com.ash.drishti.packs.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;

/**
 * Reads distribution files (docs/architecture/KERNEL_AND_MODULES.md): {@code config/distributions/drishti.<name>.yaml}, chosen by
 * {@code --distribution <name|path>} ({@code distribution}), {@code DRISHTI_DISTRIBUTION} or {@code drishti.distribution}. Nothing named:
 * Drishti All, so behaviour is unchanged. The console reads none of them: it draws from the compiled answer the server sends (THIN_CONSOLE_DESIGN.md 3.2).
 *
 * <p>The modules a file lists are read from their own manifests ({@link ModuleManifests}: {@code modules/<id>/module.yaml}, a module
 * jar's {@code META-INF/drishti/module.yaml}, and the shipped and installed {@code .dmod} packages, read as the registry reads them), the kernel's own packs, words and menus from {@code kernel.yaml} beside the files; a
 * distribution comes to a visibility filter in the edition file's shape: the kernel's packs' commands and its modules' (each module's
 * manifest {@code functions:}, with the modules they require), the words of the modules it leaves out switched off, those modules'
 * engines off, its menus, Help and Home.
 */
public final class Distributions {

    /** The setting that names the distribution (environment {@code DRISHTI_DISTRIBUTION}). */
    public static final String NAME = "drishti.distribution";
    /** The command-line form: {@code --distribution=finance}. */
    public static final String ARG = "distribution";
    /** The folder of the distribution files. */
    public static final String DIR = "drishti.distributions-dir";
    public static final String DEFAULT = "all";
    /** The kernel's own packs, words and menus, beside the distribution files. */
    public static final String KERNEL = "kernel.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern SHORT = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private Distributions() {
    }

    /** The name or path the environment selects ({@code --distribution}, then {@code drishti.distribution}); blank: {@value #DEFAULT}. */
    public static String selected(Environment env) {
        String v = env.getProperty(ARG, "");
        if (v.isBlank()) {
            v = env.getProperty(NAME, "");
        }
        return v.isBlank() ? DEFAULT : v.strip();
    }

    /** True when the environment names a distribution itself (not the default). */
    public static boolean named(Environment env) {
        return !env.getProperty(ARG, "").isBlank() || !env.getProperty(NAME, "").isBlank();
    }

    /** The folder of the distribution files. */
    public static Path dir(Environment env) {
        return com.ash.drishti.api.Roots.anchored(env.getProperty(DIR, com.ash.drishti.api.Roots.config("distributions")));
    }

    /**
     * The file a name or path stands for: {@code finance} or {@code drishti.finance} is {@code <dir>/drishti.finance.yaml}; anything with a
     * slash or ending {@code .yaml} is a path (a site's own distribution).
     */
    public static Path file(Path dir, String nameOrPath) {
        String n = nameOrPath == null || nameOrPath.isBlank() ? DEFAULT : nameOrPath.strip();
        if (n.contains("/") || n.contains("\\") || n.endsWith(".yaml") || n.endsWith(".yml")) {
            return Path.of(n);
        }
        String shortName = n.startsWith("drishti.") ? n.substring("drishti.".length()) : n;
        if (!SHORT.matcher(shortName).matches()) {
            throw new IllegalStateException("distribution '" + n + "' is not a distribution name (risk, finance, public-sector, genomics, all) or a file path");
        }
        return dir.resolve("drishti." + shortName + ".yaml");
    }

    /** The distribution the environment selects; a named one whose file is missing stops the start with a plain message. */
    public static Distribution of(Environment env) {
        Path dir = dir(env);
        Path f = file(dir, selected(env));
        if (!Files.isRegularFile(f) && !named(env)) {
            return builtInAll();                                       // a server with no config/distributions folder: today's product
        }
        String packsDir = env.getProperty("drishti.packs.dir", com.ash.drishti.api.Roots.config("packs"));
        return read(f, dir.resolve(KERNEL), homes(env), Path.of(packsDir));
    }

    /**
     * Every module a distribution of this server may list, now: the modules the boot gave, by id in requirement order. The console's distributions read the same list ({@code GET /public/modules/manifests}).
     */
    public static Map<String, ModuleManifests.Entry> known(Environment env) {
        return ModuleManifests.read(homes(env));
    }

    /** The modules of this server: the module boot's result (KERNEL_AND_MODULES.md section 4.2.1), each with the folder it is read from. */
    public static com.ash.drishti.api.resolution.ModuleHomes homes(Environment env) {
        return com.ash.drishti.api.resolution.ModuleHomes.resolve(k -> {
            try {
                return env.getProperty(k);
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    /**
     * The packs a server turns on when nothing names them (docs/architecture/KERNEL_AND_MODULES.md, "The default packs"): the kernel's
     * ({@code kernel.yaml}'s {@code kernel.packs}; {@code core} without the file), then each module's, in requirement order, without the
     * packs its manifest marks {@code off-by-default}. A module that is off brings none: the pack loader leaves out the packs of a module
     * that is off. No pack list is written anywhere else.
     */
    public static List<String> defaultPacks(Environment env) {
        return defaultPacks(dir(env).resolve(KERNEL), homes(env));
    }

    /** As {@link #defaultPacks(Environment)}, from the kernel's file and the modules (a test: the checkout's, {@code ModuleHomes.source}). */
    public static List<String> defaultPacks(Path kernelFile, com.ash.drishti.api.resolution.ModuleHomes homes) {
        Set<String> out = new LinkedHashSet<>(Files.isRegularFile(kernelFile) ? texts(tree(kernelFile).path("kernel").path("packs")) : List.of("core"));
        for (ModuleManifests.Entry m : ModuleManifests.read(homes).values()) {
            out.addAll(ModuleManifests.defaultPacks(m));
        }
        return List.copyOf(out);
    }

    /** Drishti All with no file: every module, no filter, the product's own brand. */
    public static Distribution builtInAll() {
        ObjectNode spec = YAML.createObjectNode();
        spec.put("distribution", "drishti.all");
        return new Distribution("drishti.all", "", null, true, List.of(), Map.of(), List.of(), Set.of(), Set.of(), Set.of(), List.of(),
                Map.of(), spec, null);
    }

    /** Reads a distribution file and compiles it against the kernel's file, the module manifests and the packs folder. */
    public static Distribution read(Path file, Path kernelFile, com.ash.drishti.api.resolution.ModuleHomes homes, Path packsDir) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no distribution file " + file.toAbsolutePath().normalize() + " (set " + DIR
                    + " / DRISHTI_DISTRIBUTIONS_DIR to the folder of the distribution files, or name one of: risk, finance, public-sector, genomics, all)");
        }
        JsonNode spec = tree(file);
        JsonNode kernel = Files.isRegularFile(kernelFile) ? tree(kernelFile).path("kernel") : YAML.createObjectNode();
        Map<String, ModuleManifests.Entry> known = ModuleManifests.read(homes);
        String id = spec.path("distribution").asText("").strip();
        if (id.isEmpty()) {
            throw new IllegalStateException("distribution file " + file + " has no 'distribution:' id (drishti.<name>)");
        }
        Map<String, String> brand = new LinkedHashMap<>();
        spec.path("brand").fields().forEachRemaining(e -> {
            if (e.getValue().isValueNode() && !e.getValue().asText().isBlank()) {
                brand.put(e.getKey(), e.getValue().asText().strip());
            }
        });
        String title = brand.getOrDefault("product", id);
        List<String> lenses = texts(spec.path("lenses").path("offered"));
        boolean all = "all".equals(spec.path("modules").asText(""));
        if (all) {
            return new Distribution(id, title, file, true, List.of(), Map.of(), List.of(), Set.of(), Set.of(), Set.of(), lenses, brand, spec, null);
        }
        List<String> listed = new ArrayList<>();
        for (JsonNode m : spec.path("modules")) {
            String mid = (m.isObject() ? m.path("id") : m).asText("").strip();
            if (!mid.isEmpty() && (!m.isObject() || m.path("enabled").asBoolean(true))) {
                listed.add(mid);
                if (!known.containsKey(mid) && m.path("required").asBoolean(true)) {
                    throw new IllegalStateException("distribution " + id + " requires the module '" + mid + "', which is not among the modules "
                            + "this server booted (" + (homes.homes().isEmpty() ? "none" : String.join(", ", homes.homes().keySet()))
                            + ") and is not a shipped or installed .dmod");
                }
            }
        }
        List<String> modules = new ArrayList<>(ModuleManifests.withRequired(listed, known));   // a listed module brings the ones it requires
        // a distribution may name the kernel's packs it takes (kernel-packs:; Drishti Genomics takes none: no counterparty or book anywhere)
        Set<String> packs = new LinkedHashSet<>(texts(spec.has("kernel-packs") ? spec.path("kernel-packs") : kernel.path("packs")));
        Set<String> commands = commands(packsDir, packs);                 // the kernel's own packs' commands; a module's come from its manifest
        Map<String, String> domains = new LinkedHashMap<>();
        Set<String> menus = new LinkedHashSet<>(texts(kernel.path("menus")));
        Set<String> wordsOff = new LinkedHashSet<>();
        Set<String> featuresOff = new LinkedHashSet<>();
        Set<String> wordsOn = new LinkedHashSet<>(texts(kernel.path("words")));
        for (ModuleManifests.Entry mod : known.values()) {                 // requirement order: market-data before the packs that extend it
            if (modules.contains(mod.id())) {
                packs.addAll(mod.packs());
                domains.put(mod.id(), mod.domain());
                menus.addAll(mod.menus());
                wordsOn.addAll(mod.words());
            }
        }
        for (ModuleManifests.Entry mod : known.values()) {
            if (!modules.contains(mod.id())) {
                mod.words().stream().filter(w -> !wordsOn.contains(w)).forEach(wordsOff::add);
                featuresOff.addAll(mod.features());
            }
        }
        modules.stream().filter(known::containsKey).forEach(m -> commands.addAll(known.get(m).words()));   // its manifest's functions: the one list
        ObjectNode ed = YAML.createObjectNode();
        ed.put("edition", id);
        ed.put("title", title);
        ed.put("distribution", true);
        if (packs.isEmpty()) {
            ed.putObject("packs").put("none", true);                   // a distribution of no packs (Genomics) loads none, not the default list
        } else {
            ArrayNode pe = ed.putObject("packs").putArray("enabled");
            packs.forEach(pe::add);
        }
        ObjectNode cmd = ed.putObject("commands");
        ArrayNode allow = cmd.putArray("allow");
        commands.forEach(allow::add);
        ObjectNode words = cmd.putObject("words");
        wordsOff.forEach(w -> words.put(w, "off"));
        spec.path("commands").path("words").fields().forEachRemaining(e -> words.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue().asText()));
        ObjectNode features = ed.putObject("features");
        featuresOff.forEach(f -> features.put(f, "off"));
        spec.path("features").fields().forEachRemaining(e -> features.put(e.getKey(), e.getValue().asText()));
        ObjectNode menu = ed.putObject("menus");
        List<String> top = texts(spec.path("menus").path("top"));
        ArrayNode topNode = menu.putArray("top");
        (top.isEmpty() ? new ArrayList<>(menus) : top.stream().filter(menus::contains).toList()).forEach(topNode::add);
        if (spec.path("menus").has("placeholder")) {
            menu.put("placeholder", spec.path("menus").path("placeholder").asText());
        }
        if (spec.path("home").isObject()) {
            ed.set("home", spec.path("home").deepCopy());
        }
        ObjectNode help = ed.putObject("help");
        ArrayNode knowledge = help.putArray("knowledge");
        packs.forEach(knowledge::add);
        ArrayNode dom = help.putArray("domains");
        modules.stream().filter(known::containsKey).flatMap(m -> known.get(m).docs().stream()).distinct().forEach(dom::add);   // the Help each owns
        if (spec.path("docs").has("title")) {
            help.put("title", spec.path("docs").path("title").asText());
        }
        if (spec.path("docs").path("categories").isArray()) {
            help.set("categories", spec.path("docs").path("categories").deepCopy());
        }
        ObjectNode branding = ed.putObject("branding");
        for (String k : List.of("product", "tagline")) {
            if (brand.containsKey(k)) {
                branding.put(k, brand.get(k));
            }
        }
        return new Distribution(id, title, file, false, List.copyOf(modules), Map.copyOf(domains), List.copyOf(packs), commands,
                Set.copyOf(wordsOff), Set.copyOf(featuresOff), lenses, brand, spec, ed);
    }

    /**
     * Every command of the kernel's own packs: each pack's mnemonics and its code, from its {@code pack.yaml} under the packs folder. A
     * module's commands are its manifest's {@code functions:} (the one list: what its distribution shows, FUNC and the access registry).
     */
    static Set<String> commands(Path packsDir, Set<String> packs) {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isDirectory(packsDir)) {
            return out;
        }
        Map<String, Path> found = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(packsDir, 3)) {
            s.filter(p -> p.getFileName().toString().equals("pack.yaml")).forEach(p -> found.putIfAbsent(p.getParent().getFileName().toString(), p));
        } catch (IOException e) {
            return out;
        }
        for (String p : packs) {
            Path f = found.get(p);
            if (f == null) {
                continue;
            }
            JsonNode n = tree(f);
            if (!n.path("code").asText("").isBlank()) {
                out.add(n.path("code").asText().strip().toUpperCase(Locale.ROOT));
            }
            n.path("mnemonics").fieldNames().forEachRemaining(c -> out.add(c.strip().toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static List<String> texts(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> {
                if (!x.asText("").isBlank()) {
                    out.add(x.asText().strip());
                }
            });
        }
        return out;
    }

    private static JsonNode tree(Path file) {
        try {
            JsonNode n = YAML.readTree(java.nio.file.Files.readAllBytes(file));
            if (n == null || !n.isObject()) {
                throw new IllegalStateException("distribution file " + file + " is not a YAML mapping");
            }
            return n;
        } catch (IOException e) {
            throw new IllegalStateException("distribution file " + file + " cannot be read: " + e.getMessage(), e);
        }
    }
}
