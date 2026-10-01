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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pack's Calc offering: {@code python:} in pack.yaml, snippet files under python/, and the {@code calc} power of its roles. */
class PackPythonTest {

    @Test
    void snippetsComeFromTheManifestAndFromFilesWithAHeader(@TempDir Path dir) throws Exception {
        Path pack = Files.createDirectories(dir.resolve("desk"));
        Files.writeString(pack.resolve("pack.yaml"), """
                pack: desk
                kinds: [book, trade]
                python:
                  enabled: true
                  snippets:
                    - { title: Inline, description: from the manifest, kinds: [trade], code: "print(1)\\n" }
                    - { title: No code }
                roles:
                  quant: { kinds: ["*"], calc: true }
                """);
        Files.createDirectories(pack.resolve("python"));
        Files.writeString(pack.resolve("python/b_pivot.py"), """
                # Project Drishti · the copyright header comes first
                # (several lines of it)

                # title: Pivot by book
                # description: MTM by book
                # kinds: book, trade

                # the code's own comment stays
                import pandas as pd
                show(view.tables)
                """);
        Files.writeString(pack.resolve("python/a_plain.py"), "1 + 1\n");
        Files.writeString(pack.resolve("python/notes.txt"), "not a snippet");
        Files.writeString(pack.resolve("python/z_big.py"), "#" + "x".repeat((int) PackPython.MAX_FILE_BYTES));
        Pack p = new PackLoader().load(dir, List.of("desk")).get(0);

        PackPython py = PackPython.of(p);
        assertThat(py.enabled()).isTrue();
        assertThat(py.snippets()).extracting(PackPython.Snippet::title).containsExactly("Inline", "a_plain", "Pivot by book");
        PackPython.Snippet pivot = py.snippets().get(2);
        assertThat(pivot.description()).isEqualTo("MTM by book");
        assertThat(pivot.kinds()).containsExactly("book", "trade");
        assertThat(pivot.file()).isEqualTo("python/b_pivot.py");
        assertThat(pivot.code()).startsWith("# the code's own comment stays\nimport pandas as pd\n").doesNotContain("copyright");
        assertThat(py.snippets().get(1).kinds()).isEmpty();                              // no kinds: every kind of its pack
        assertThat(PackPython.appliesTo(py.snippets().get(1), "book", p.kinds())).isTrue();
        assertThat(PackPython.appliesTo(py.snippets().get(1), "var", p.kinds())).isFalse();
        assertThat(PackPython.appliesTo(py.snippets().get(0), "book", p.kinds())).isFalse();

        Map<String, Object> props = new PackLoader().properties(List.of(p));
        assertThat(props).containsEntry("drishti.security.roles.quant.calc", true);
    }

    @Test
    void aPackWithoutPythonOffersNothing(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("plain"));
        Files.writeString(dir.resolve("plain/pack.yaml"), "pack: plain\nkinds: [thing]\n");
        assertThat(PackPython.of(new PackLoader().load(dir, List.of("plain")).get(0))).isEqualTo(PackPython.NONE);
        Files.writeString(dir.resolve("plain/pack.yaml"), "pack: plain\npython: { enabled: false }\n");
        Files.createDirectories(dir.resolve("plain/python"));
        Files.writeString(dir.resolve("plain/python/x.py"), "# title: X\n1\n");
        PackPython off = PackPython.of(new PackLoader().load(dir, List.of("plain")).get(0));
        assertThat(off.enabled()).isFalse();
        assertThat(off.snippets()).extracting(PackPython.Snippet::code).containsExactly("1\n");
    }

    @Test
    void theShippedBankingPacksOfferTheirStarterSnippets() {
        List<Pack> packs = new PackLoader().load(PackLoaderTest.PACKS, List.of("market-risk", "counterparty-risk"));
        Map<String, List<String>> titles = new java.util.TreeMap<>();
        packs.forEach(p -> titles.put(p.name(), PackPython.of(p).snippets().stream().map(PackPython.Snippet::title).toList()));
        assertThat(packs).allMatch(p -> PackPython.of(p).enabled());
        assertThat(titles).containsEntry("market-risk", List.of("VaR and expected shortfall from the scenario P&L"))
                .containsEntry("trading", List.of("MTM under parallel rate moves"))
                .containsEntry("banking-core", List.of("P&L by book: a pandas pivot"))
                .containsEntry("market-data", List.of("Interpolate the curve at any tenor"))
                .containsEntry("counterparty-risk", List.of("MTM concentration by product and netting set"));
    }
}
