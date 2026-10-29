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
package com.ash.drishti.server.switches;

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every command of the loaded packs with what it does, the same source as docs/guides/COMMAND_CATALOG.md (tools/command_catalog.py): the
 * pack's mnemonic and label, the one-line {@code description} of the kind's data contract ({@code contracts/<kind>.contract.yaml} in the
 * pack), and an example (the pack's own example command for it, else the mnemonic with the example id its contract records). Read once per
 * set of loaded packs.
 */
public final class CommandCatalog {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** One command: its mnemonic, kind, label, what it does and an example. */
    public record Command(String code, String kind, String label, String description, String example) {}

    /** One pack with its commands. */
    public record PackCommands(Pack pack, List<Command> commands) {}

    private record Cached(long version, List<PackCommands> packs) {}

    private final PackRegistry registry;
    private final Mnemonics mnemonics;
    private volatile Cached cached = new Cached(-1, List.of());

    private final com.ash.drishti.packs.edition.Edition edition;

    public CommandCatalog(PackRegistry registry, Mnemonics mnemonics) {
        this(registry, mnemonics, com.ash.drishti.packs.edition.Edition.NONE);
    }

    /** With an edition: the commands it hides are not in the catalogue (FUNC, Admin → Commands). */
    public CommandCatalog(PackRegistry registry, Mnemonics mnemonics, com.ash.drishti.packs.edition.Edition edition) {
        this.registry = registry;
        this.mnemonics = mnemonics;
        this.edition = edition;
    }

    public List<PackCommands> packs() {
        Cached c = cached;
        long v = registry.version();
        if (c.version() != v) {
            c = new Cached(v, build());
            cached = c;
        }
        return c.packs();
    }

    /** The command named {@code code} (any case), if a loaded pack defines it. */
    public java.util.Optional<Command> command(String code) {
        String c = code == null ? "" : code.toUpperCase(Locale.ROOT);
        return packs().stream().flatMap(p -> p.commands().stream()).filter(x -> x.code().equals(c)).findFirst();
    }

    private List<PackCommands> build() {
        List<PackCommands> out = new ArrayList<>();
        for (Pack p : registry.packs().stream().sorted(Comparator.comparing(Pack::name)).toList()) {
            List<Command> commands = new ArrayList<>();
            for (String kind : p.kinds()) {
                String code = mnemonics.codeFor(kind);
                if (code == null || !edition.commandShown(code)) {
                    continue;
                }
                JsonNode contract = contract(p.dir(), kind);
                String label = mnemonics.of(code).map(m -> m.label()).filter(s -> s != null && !s.isBlank())
                        .orElse(contract.path("title").asText(kind));
                commands.add(new Command(code, kind, label, oneLine(contract.path("description").asText("")), example(code, p, contract)));
            }
            commands.sort(Comparator.comparing(Command::code));
            out.add(new PackCommands(p, List.copyOf(commands)));
        }
        return List.copyOf(out);
    }

    private static JsonNode contract(Path dir, String kind) {
        Path f = dir == null ? null : dir.resolve("contracts").resolve(kind + ".contract.yaml");
        if (f == null || !Files.isRegularFile(f)) {
            return YAML.createObjectNode();
        }
        try {
            JsonNode n = YAML.readTree(f.toFile());
            return n == null ? YAML.createObjectNode() : n;
        } catch (IOException e) {
            return YAML.createObjectNode();
        }
    }

    private static String oneLine(String text) {
        return String.join(" ", text.trim().split("\\s+"));
    }

    private static String example(String code, Pack p, JsonNode contract) {
        Object console = p.manifest().get("console");
        if (console instanceof Map<?, ?> c && c.get("examples") instanceof List<?> examples) {
            for (Object e : examples) {
                Object cmd = e instanceof List<?> l && !l.isEmpty() ? l.get(0) : e;
                String s = String.valueOf(cmd).trim();
                if (s.split("\\s+", 2)[0].equals(code)) {
                    return s;
                }
            }
        }
        String key = contract.path("key").path(0).asText("");
        for (JsonNode f : contract.path("fields")) {
            if (f.path("name").asText().equals(key) && !f.path("example").asText("").isEmpty()) {
                return code + " " + f.path("example").asText();
            }
        }
        return "";
    }
}
