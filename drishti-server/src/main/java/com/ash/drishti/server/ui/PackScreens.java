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
package com.ash.drishti.server.ui;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.control.ControlValues;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.ui.BoardDoc;
import com.ash.drishti.rachana.ui.ChipCatalog;
import com.ash.drishti.rachana.ui.UiDocuments;
import com.ash.drishti.rachana.ui.migrate.BoardSutra;
import com.ash.drishti.rachana.ui.migrate.Conversion;
import com.ash.drishti.rachana.ui.migrate.PackDryRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The per-pack switch of converted screens (docs/architecture/UI_BOARD.md 11.3, phase P5; decision 7 of 14.1): {@code packs.<pack>: boards | sutras}
 * in {@code config/ui/ui.yaml}, changed at run time by an administrator ({@code PUT /api/v1/ui/packs/{pack}}, this node, until the next start).
 * A pack on {@code boards} has its entity pages served from its converted boards: the record is matched to its Sutra as before, the Sutra's
 * board is painted ({@link BoardSutra}) and bound over the record. A screen whose conversion fails stays on its Sutra. Switching back is
 * immediate; the Sutras are never removed (until 3.0).
 */
public final class PackScreens {

    /** How a pack's screens are served. */
    public enum Mode {
        BOARDS, SUTRAS;

        public String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Mode of(String s) {
            if (s == null) {
                return SUTRAS;
            }
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "boards" -> BOARDS;
                case "sutras" -> SUTRAS;
                default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "screens are 'boards' or 'sutras', not '" + s + "'");
            };
        }
    }

    /** A converted board ready to serve: its conversion and the Sutra it paints as (null when it failed). */
    public record Served(Conversion conversion, BoardDoc board, Sutra painted) {}

    private final Path packsDir;
    private final Path biDir;
    private final ChipCatalog chips;
    private final SutraRegistry registry;
    private final SutraMatcher matcher;
    private final ViewPipeline pipeline;
    private final Map<String, Mode> modes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Served>> converted = new ConcurrentHashMap<>();
    private volatile Map<String, String> kindPack;

    public PackScreens(Path uiDir, Path packsDir, Path biDir, ChipCatalog chips, SutraRegistry registry, SutraMatcher matcher, ViewPipeline pipeline) {
        this.packsDir = packsDir;
        this.biDir = biDir;
        this.chips = chips;
        this.registry = registry;
        this.matcher = matcher;
        this.pipeline = pipeline;
        modes.putAll(load(uiDir.resolve("ui.yaml")));
        registry.onChange(changed -> converted.clear());       // a Sutra changed: convert again on next use
    }

    /** {@code packs:} of ui.yaml: {@code <pack>: boards|sutras} or {@code <pack>: {screens: boards|sutras}}. */
    static Map<String, Mode> load(Path f) {
        Map<String, Mode> m = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return m;
        }
        try {
            UiDocs.map(UiDocs.read(Files.readString(f)).get("packs")).forEach((pack, v) -> {
                Object s = v instanceof Map<?, ?> mm ? mm.get("screens") : v;
                m.put(pack, Mode.of(s == null ? null : String.valueOf(s)));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return m;
    }

    public Mode mode(String pack) {
        return modes.getOrDefault(pack, Mode.SUTRAS);
    }

    /** Every pack named in ui.yaml or switched since, with its mode. */
    public Map<String, String> modes() {
        Map<String, String> m = new LinkedHashMap<>();
        modes.forEach((k, v) -> m.put(k, v.text()));
        return m;
    }

    /** Switches a pack; takes effect on the next page opened. */
    public void set(String pack, Mode mode) {
        if (!Files.isRegularFile(packsDir.resolve(pack).resolve("pack.yaml"))) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no pack '" + pack + "'");
        }
        modes.put(pack, mode);
    }

    /** The packs on disk (a folder with a {@code pack.yaml}), by folder name: the Chip Store's candidate list reads every one (P4b). */
    public List<String> packs() {
        try (var s = Files.list(packsDir)) {
            return s.filter(d -> Files.isRegularFile(d.resolve("pack.yaml"))).map(d -> d.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** One Sutra's text converted as a screen of {@code pack} by P5's converter (the designer's "Open as board", P4b); null when it is no Sutra. */
    public Conversion convert(String pack, String text) {
        return new com.ash.drishti.rachana.ui.migrate.PageConverter(chips).convert(pack, text);
    }

    /** The Sutra a board paints as (P5's {@link BoardSutra}), unchecked: the designer's "Export Sutra" (P4b); empty for a board with no entity id. */
    public Optional<ObjectNode> sutraOf(BoardDoc b) {
        return BoardSutra.toSutra(b, ref -> chips.find(ref).map(ChipCatalog.Entry::doc));
    }

    /** The pack owning a kind, or null. */
    public String packOf(String kind) {
        Map<String, String> m = kindPack;
        if (m == null) {
            try {
                m = kindPacks(packsDir);
            } catch (IOException e) {
                m = Map.of();
            }
            kindPack = m;
        }
        return m.get(kind);
    }

    private static Map<String, String> kindPacks(Path packsDir) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        try (var s = Files.list(packsDir)) {
            for (Path d : s.sorted().toList()) {
                Path y = d.resolve("pack.yaml");
                if (Files.isRegularFile(y)) {
                    Map<String, Object> p = UiDocs.read(Files.readString(y));
                    String name = p.get("pack") == null ? d.getFileName().toString() : String.valueOf(p.get("pack"));
                    if (p.get("kinds") instanceof List<?> ks) {
                        ks.forEach(k -> m.putIfAbsent(String.valueOf(k), name));
                    }
                }
            }
        }
        return m;
    }

    /** The pack's dry run (11.2): every screen converted in memory, nothing saved. */
    public List<Conversion> dryRun(String pack) {
        try {
            return PackDryRun.convert(packsDir, Files.isDirectory(biDir) ? biDir : null, pack, chips);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /** The pack's entity pages as served boards, by Sutra name (converted once, again after a Sutra changes). */
    public Map<String, Served> boards(String pack) {
        return converted.computeIfAbsent(pack, p -> {
            Map<String, Served> m = new LinkedHashMap<>();
            for (Conversion c : dryRun(p)) {
                if (c.kind() == null) {
                    continue;
                }
                String sutra = c.screen().substring("sutra:".length()).replaceAll("@.*", "");
                m.put(sutra, served(c));
            }
            return m;
        });
    }

    /** A conversion made ready to serve: its board read back and painted into a Sutra the grammar checks. */
    public Served served(Conversion c) {
        if (c.status() == Conversion.Status.FAILS || c.document() == null) {
            return new Served(c, null, null);
        }
        BoardDoc b = UiDocuments.readBoard(c.document().toString());
        return new Served(c, b, paint(b));
    }

    /** The Sutra a board paints as, checked by the grammar (the chips from the Chip Store, the board's own first). */
    public Sutra paint(BoardDoc b) {
        ObjectNode s = BoardSutra.toSutra(b, ref -> chips.find(ref).map(ChipCatalog.Entry::doc))
                .orElseThrow(() -> new DrishtiException(ErrorCode.UI_CHECK_FAILED, b.board() + " is not an entity board (no 'id' parameter of a kind)"));
        try {
            return registry.check(new ObjectMapper(new YAMLFactory()).writeValueAsString(s));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The page of {@code ref} from its converted board, when its pack is switched to boards and its Sutra converted; empty otherwise (the old
     * path serves it).
     */
    public Optional<Board> view(EntityRef ref, AsOf asOf, UnaryOperator<DataNode> redact, Predicate<String> mayOpen, ControlValues values) {
        String pack = packOf(ref.kind());
        if (pack == null || mode(pack) != Mode.BOARDS) {
            return Optional.empty();
        }
        EntityDocument doc = pipeline.document(ref, asOf);
        Optional<Sutra> sutra = matcher.match(ref.kind(), doc.data());
        Served s = sutra.map(x -> boards(pack).get(x.name())).orElse(null);
        if (s == null || s.painted() == null) {
            return Optional.empty();
        }
        return Optional.of(new Board(s.conversion().board(), pipeline.preview(s.painted(), ref, asOf, redact, mayOpen, values)));
    }

    /** A page served from a board. */
    public record Board(String board, ViewModel view) {}
}
