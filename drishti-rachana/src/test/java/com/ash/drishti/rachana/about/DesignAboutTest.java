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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.el.ElCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workbench's About text laid over the packs' (CONTEXT_HELP.md step 7): the Design's entries win for its kind, the pack's fill
 * what it leaves out, {@code use} sees the Design's vocabulary then the pack's then the core, and the parser's codes and positions apply.
 */
class DesignAboutTest {

    @TempDir Path dir;

    private static final String PACK = """
            about: 1
            vocabulary:
              amount: { term: Pack amount, means: From the pack. }
            kinds:
              thing:
                title: Pack title
                about: "Pack text."
                glossary:
                  qty: { term: Pack quantity, means: Pack says how many. }
                  price: { term: Price, means: Pack price. }
            """;

    private AboutCatalog catalog() throws IOException {
        Path f = dir.resolve("pack.yaml");
        Files.writeString(f, PACK);
        return new AboutCatalog(new AboutProperties(List.of(new AboutProperties.PackSource("p", "Pack", f.toString(), null, List.of("thing"), List.of("p"))), null, null),
                new ElCompiler());
    }

    private static DesignAbout of(AboutCatalog c, String text) {
        return DesignAbout.of(c, new ElCompiler(), 600, text, "thing");
    }

    @Test
    void theDesignsEntriesWinAndThePacksFillTheRest() throws IOException {
        DesignAbout a = of(catalog(), """
                about: 1
                kinds:
                  thing:
                    about: "Design text."
                    glossary:
                      qty: { term: Design quantity, means: Design says how many. }
                """);
        assertThat(a.problems()).isEmpty();
        AboutText t = a.forKind("thing").orElseThrow();
        assertThat(t.glossary().get("qty").term()).isEqualTo("Design quantity");
        assertThat(t.glossary().get("price").means()).isEqualTo("Pack price.");      // from the pack
        assertThat(t.title()).isEqualTo("Pack title");                                // not overridden
        assertThat(t.pack()).isEqualTo(DesignAbout.PACK);
    }

    @Test
    void aBlankTextLeavesThePacksAsTheyAre() throws IOException {
        AboutCatalog c = catalog();
        DesignAbout a = of(c, "  ");
        assertThat(a.problems()).isEmpty();
        assertThat(a.forKind("thing")).isEqualTo(c.forKind("thing"));
        assertThat(a.forKind("nothing")).isEmpty();
    }

    @Test
    void useSeesTheDesignsVocabularyThenThePacksThenTheCoreAndAMissingOneIsDRS2043() throws IOException {
        DesignAbout a = of(catalog(), """
                about: 1
                vocabulary:
                  weight: { term: Weight, means: How heavy. }
                kinds:
                  thing:
                    glossary:
                      w: { use: weight }
                      a: { use: amount }
                      c: { use: currency }
                      z: { use: nosuch }
                """);
        assertThat(a.problems()).extracting(p -> p.code()).containsExactly(AboutParser.BAD_USE);
        AboutText t = a.forKind("thing").orElseThrow();
        assertThat(t.glossary().get("w").means()).isEqualTo("How heavy.");
        assertThat(t.glossary().get("a").means()).isEqualTo("From the pack.");
        assertThat(t.glossary().get("c").term()).isNotBlank();                         // the core vocabulary
        assertThat(t.glossary()).doesNotContainKey("z");
    }

    @Test
    void parserCodesAndPositionsApplyAndAForeignKindIsDRS2041() throws IOException {
        DesignAbout a = of(catalog(), "about: 1\nkinds:\n  other:\n    title: x\n  thing:\n    about: \"${$.a\"\n");
        assertThat(a.problems()).extracting(p -> p.code()).contains(AboutParser.BAD_KIND, AboutParser.BAD_TEMPLATE);
        assertThat(a.problems()).allSatisfy(p -> assertThat(p.location().line()).isPositive());
        assertThat(of(catalog(), "kinds: {}\n").problems()).extracting(p -> p.code()).containsExactly(AboutParser.BAD_FILE);
    }
}
