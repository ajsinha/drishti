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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.format.Formats;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The catalogue: pack about files merged through {@code extends}, templates over the caller's document, problems per file. */
class AboutCatalogTest {

    @TempDir Path dir;

    private static final String BASE = """
            about: 1
            vocabulary:
              amount: { term: Amount, means: The base meaning., unit: USD }
            kinds:
              thing:
                title: Base thing
                about: "Base ${$.name}."
                guide: base#thing
                glossary:
                  amount: { use: amount }
                  qty: { term: Quantity, means: How many. }
                panels:
                  main: { about: "Base panel for ${$.name}." }
            """;

    private static final String CHILD = """
            about: 1
            vocabulary:
              amount: { term: Child amount, means: The child's meaning., unit: EUR }
            kinds:
              thing:
                about: "Child ${$.name}."
                glossary:
                  qty: { term: Child quantity, means: Overridden. }
              child-thing:
                title: Child's own
            """;

    private Path write(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text);
        return p;
    }

    private AboutProperties.PackSource pack(String name, Path file, List<String> kinds, List<String> lineage) {
        return new AboutProperties.PackSource(name, name + " title", file == null ? null : file.toString(), dir.resolve(name).toString(),
                kinds, lineage);
    }

    private AboutCatalog catalog(Path base, Path child) {
        return new AboutCatalog(new AboutProperties(List.of(
                pack("child", child, List.of("child-thing"), List.of("child", "base")),
                pack("base", base, List.of("thing"), List.of("base"))), null, null), new ElCompiler());
    }

    private static EvalContext ctx(Map<String, Object> doc) {
        return EvalContext.of(DataNode.of(doc), Formats.load(null, List.of()));
    }

    @Test
    void aChildPackOverridesItsParentsEntriesKeyByKeyAndKeepsTheRest() throws Exception {
        AboutCatalog c = catalog(write("base.yaml", BASE), write("child.yaml", CHILD));
        assertThat(c.problems()).isEmpty();
        AboutText t = c.forKind("thing").orElseThrow();
        assertThat(t.pack()).isEqualTo("child");                                   // the page text is the child's
        assertThat(t.title()).isEqualTo("Base thing");                             // not overridden: the parent's stays
        assertThat(t.guide()).isEqualTo("base#thing");
        assertThat(t.render(ctx(Map.of("name", "X")), List.of("main"), 1000).text()).isEqualTo("Child X.");
        assertThat(t.render(ctx(Map.of("name", "X")), List.of("main"), 1000).panels()).containsEntry("main", "Base panel for X.");
        assertThat(t.glossary().get("qty").term()).isEqualTo("Child quantity");     // overridden by key
        assertThat(t.glossary().get("amount").term()).isEqualTo("Child amount");    // `use` resolves to the most specific vocabulary
        assertThat(t.glossary().get("amount").origin()).isEqualTo("child:vocabulary.amount");
        assertThat(c.forKind("child-thing").orElseThrow().title()).isEqualTo("Child's own");
        assertThat(c.forKind("nothing")).isEmpty();
    }

    @Test
    void withoutTheChildTheParentsTextStandsAndAChildCannotDeleteAnEntry() throws Exception {
        AboutCatalog c = catalog(write("base.yaml", BASE), null);
        AboutText t = c.forKind("thing").orElseThrow();
        assertThat(t.pack()).isEqualTo("base");
        assertThat(t.glossary().get("amount").term()).isEqualTo("Amount");
        assertThat(t.render(ctx(Map.of("name", "X")), List.of(), 1000).text()).isEqualTo("Base X.");
    }

    @Test
    void aSutraBelongsToThePackWhoseDirectoryHoldsIt() throws Exception {
        AboutCatalog c = catalog(write("base.yaml", BASE), null);
        assertThat(c.packOfSutra(dir.resolve("base").resolve("x").resolve("a.sutra.yaml").toString()).orElseThrow().name()).isEqualTo("base");
        assertThat(c.packOfSutra("/elsewhere/a.sutra.yaml")).isEmpty();
    }

    @Test
    void aMaskedFieldReadsAsTheMaskInATemplateAndNoValueDerivedFromItLeaks() throws Exception {
        Path f = write("base.yaml", """
                about: 1
                kinds:
                  thing:
                    about: "${$.trader} / ${fmt($.secret * 2, 'compact')} / ${$.secret + 1} / ${coalesce($.secret, 'x')} / ${$.name}"
                """);
        AboutCatalog c = catalog(f, null);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", "visible");
        doc.put("trader", DataNode.masked());
        doc.put("secret", DataNode.masked());
        String text = c.forKind("thing").orElseThrow().render(ctx(doc), List.of(), 1000).text();
        assertThat(text).contains("visible").doesNotContain("A. Shah", "412", "2.0");
        assertThat(text).startsWith(DataNode.MASK + " / ");
    }

    @Test
    void aFailingExpressionReadsAsADashAndTheRestOfTheTextSurvivesAndTheTextIsCapped() throws Exception {
        Path f = write("base.yaml", """
                about: 1
                kinds:
                  thing:
                    about: "a ${$.name.nope[0] + 'x' - 1} b ${$.name} ${$.name}${$.name}${$.name}"
                """);
        AboutText t = catalog(f, null).forKind("thing").orElseThrow();
        AboutText.Rendered r = t.render(ctx(Map.of("name", "abcdef")), List.of(), 1000);
        assertThat(r.text()).startsWith("a ").contains(" b abcdef");
        assertThat(t.render(ctx(Map.of("name", "abcdef")), List.of(), 8).text()).hasSize(8).endsWith("…");
    }
}
