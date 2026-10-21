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
package com.ash.drishti.rachana.ui.migrate;

import com.ash.drishti.rachana.ui.ChipCatalog;
import com.ash.drishti.rachana.ui.ChipState;
import com.ash.drishti.rachana.ui.UiCli;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code ui convert --pack <pack> --dry-run}: the dry run of a pack's conversion to boards without a server (UI_BOARD.md 11.2, phase P5).
 * {@code --out DIR} also writes each converted board ({@code <board>.board.yaml}) for reading; nothing is saved to a store.
 */
public final class ConvertCli {

    static final String USAGE = """
            usage: ui convert --pack <pack> --dry-run [--json] [--out DIR] [--packs DIR] [--bi DIR] [--chips DIR]
              converts every entity page (Sutra) of the pack and every BI report page over its kinds into boards, in memory, and reports per
              screen: clean (Chip Store chips only), local (needs board-local chips: Chip Store candidates) or fails (and why)
            options: --dry-run       required in 2.7.0: nothing is saved (the per-pack switch converts at run time)
                     --json          answer as JSON
                     --out DIR       also write each board as DIR/<board>.board.yaml
                     --packs DIR     the packs folder (default config/packs)
                     --bi DIR        the BI folder for reports, models and datasets (default config/bi)
                     --chips DIR     the seed chips (default config/ui/chips)
            exit codes: 0 no screen fails, 1 a screen fails, 2 usage""";

    private ConvertCli() {}

    public static int run(String[] args, PrintStream out, PrintStream err) {
        String pack = null;
        boolean dry = false;
        boolean json = false;
        Path outDir = null;
        Path packs = Path.of("config", "packs");
        Path bi = Path.of("config", "bi");
        Path chipsDir = Path.of("config", "ui", "chips");
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--dry-run" -> dry = true;
                case "--json" -> json = true;
                case "-h", "--help" -> {
                    out.println(USAGE);
                    return UiCli.OK;
                }
                case "--pack", "--out", "--packs", "--bi", "--chips" -> {
                    if (i + 1 >= args.length) {
                        err.println("ui convert: " + a + " needs a value");
                        return UiCli.USAGE;
                    }
                    String v = args[++i];
                    switch (a) {
                        case "--pack" -> pack = v;
                        case "--out" -> outDir = Path.of(v);
                        case "--packs" -> packs = Path.of(v);
                        case "--bi" -> bi = Path.of(v);
                        default -> chipsDir = Path.of(v);
                    }
                }
                default -> {
                    err.println("ui convert: unknown option " + a);
                    err.println(USAGE);
                    return UiCli.USAGE;
                }
            }
        }
        if (pack == null || !dry) {
            err.println("ui convert: " + (pack == null ? "give the pack (--pack <pack>)" : "only --dry-run in 2.7.0: switch a pack with ui.yaml packs.<pack>: boards"));
            err.println(USAGE);
            return UiCli.USAGE;
        }
        try {
            ChipCatalog chips = UiCli.folder(chipsDir, ChipState.CERTIFIED);
            List<Conversion> all = PackDryRun.convert(packs, Files.isDirectory(bi) ? bi : null, pack, chips);
            ObjectNode report = PackDryRun.report(pack, all);
            if (outDir != null) {
                Files.createDirectories(outDir);
                ObjectMapper yaml = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
                for (Conversion c : all) {
                    if (c.document() != null) {
                        Files.writeString(outDir.resolve(c.board() + ".board.yaml"), yaml.writeValueAsString(c.document()), StandardCharsets.UTF_8);
                    }
                }
            }
            if (json) {
                out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            } else {
                PackDryRun.print(report, out);
            }
            return PackDryRun.ok(all) ? UiCli.OK : UiCli.PROBLEMS;
        } catch (IOException e) {
            err.println("ui convert: " + e.getMessage());
            return UiCli.USAGE;
        }
    }
}
