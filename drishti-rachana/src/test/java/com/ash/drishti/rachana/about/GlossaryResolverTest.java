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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Layer 2's resolution order (docs/architecture/CONTEXT_HELP.md): the kind's glossary, then the vocabulary by last name (most
 * specific pack first), then a derived kind's own formula, then the shared core vocabulary, then nothing. Plus path keys.
 */
class GlossaryResolverTest {

    @TempDir Path dir;

    private static final String BASE = """
            about: 1
            vocabulary:
              mtm: { term: Base MTM, means: Base meaning of mtm., unit: USD }
              amount: { term: Base amount, means: Base. }
              bucket:
                term: Bucket
                means: A band.
                values: { A: Best, B: Worse }
            kinds:
              thing:
                glossary:
                  contributions.var: { term: Contribution, means: Euler allocation., formula: "w * dVaR/dw" }
                  mtm: { use: mtm }
                  grade: { use: bucket }
                  currency: { use: currency }
            """;

    private static final String CHILD = """
            about: 1
            vocabulary:
              mtm: { term: Child MTM, means: Child meaning of mtm. }
            kinds:
              thing:
                glossary:
                  qty: { term: Quantity, means: How many. }
              other:
                title: Other
            """;

    private GlossaryResolver resolver(boolean child) throws IOException {
        Path b = dir.resolve("base.yaml");
        Files.writeString(b, BASE);
        Path c = dir.resolve("child.yaml");
        Files.writeString(c, CHILD);
        List<AboutProperties.PackSource> packs = child
                ? List.of(new AboutProperties.PackSource("child", "C", c.toString(), null, List.of("other"), List.of("child", "base")),
                        new AboutProperties.PackSource("base", "B", b.toString(), null, List.of("thing"), List.of("base")))
                : List.of(new AboutProperties.PackSource("base", "B", b.toString(), null, List.of("thing"), List.of("base")));
        AboutCatalog cat = new AboutCatalog(new AboutProperties(packs, null, null), new ElCompiler());
        assertThat(cat.problems()).isEmpty();
        return new GlossaryResolver(cat);
    }

    private static final java.util.function.Function<String, Optional<GlossaryEntry>> NONE = k -> Optional.empty();

    @Test
    void theKindsOwnGlossaryWinsAndAFieldPathIgnoresArraySteps() throws IOException {
        GlossaryResolver r = resolver(false);
        GlossaryEntry e = r.resolve("thing", "contributions.var", NONE).orElseThrow();
        assertThat(e.term()).isEqualTo("Contribution");
        assertThat(e.formula()).isEqualTo("w * dVaR/dw");
        assertThat(GlossaryResolver.normalise("contributions[3].var")).isEqualTo("contributions.var");
        assertThat(GlossaryResolver.normalise("$.a.b[0]")).isEqualTo("a.b");
        assertThat(GlossaryResolver.normalise("$")).isNull();
        assertThat(GlossaryResolver.keyOf("$.var99", null)).isEqualTo("var99");
        assertThat(GlossaryResolver.keyOf("@.var", "$.contributions")).isEqualTo("contributions.var");
        assertThat(GlossaryResolver.keyOf("@.var", null)).isEqualTo("var");
        assertThat(GlossaryResolver.keyOf("$.a[2].b", null)).isEqualTo("a.b");
        assertThat(GlossaryResolver.keyOf("$.a / $.b", null)).isNull();           // a computed value names no one field
    }

    @Test
    void aFieldFallsBackToTheVocabularyByItsLastName() throws IOException {
        GlossaryResolver r = resolver(false);
        assertThat(r.resolve("thing", "positions.amount", NONE).orElseThrow().term()).isEqualTo("Base amount");
        assertThat(r.resolve("thing", "positions.amount", NONE).orElseThrow().origin()).isEqualTo("base:vocabulary.amount");
    }

    @Test
    void useTakesTheMostSpecificVocabularyAndAChildOverridesWithoutDeleting() throws IOException {
        GlossaryResolver r = resolver(true);
        assertThat(r.resolve("thing", "mtm", NONE).orElseThrow().term()).isEqualTo("Child MTM");       // base's `use: mtm` resolves to the child's entry
        assertThat(r.resolve("thing", "qty", NONE).orElseThrow().term()).isEqualTo("Quantity");        // the child's glossary
        assertThat(r.resolve("thing", "contributions.var", NONE).orElseThrow().term()).isEqualTo("Contribution");   // the parent's stays
        GlossaryEntry g = r.resolve("thing", "grade", NONE).orElseThrow();
        assertThat(g.term()).isEqualTo("Bucket");
        assertThat(g.values()).containsEntry("A", "Best");
        assertThat(g.origin()).isEqualTo("base:vocabulary.bucket");
    }

    @Test
    void useMayNameACoreWordAndAnUnknownKindStillGetsTheCoreVocabulary() throws IOException {
        GlossaryResolver r = resolver(false);
        assertThat(r.resolve("thing", "currency", NONE).orElseThrow().origin()).isEqualTo("core:vocabulary.currency");
        assertThat(r.resolve("never-heard-of", "asOf", NONE).orElseThrow().term()).isEqualTo("As of");
        assertThat(r.resolve("never-heard-of", "x.status", NONE).orElseThrow().origin()).isEqualTo("core:vocabulary.status");
        assertThat(r.resolve("never-heard-of", "zzzNobodyDefinedThis", NONE)).isEmpty();
    }

    @Test
    void aDerivedFieldsOwnFormulaBeatsTheCoreVocabularyButNotTheAuthorsWords() throws IOException {
        GlossaryResolver r = resolver(false);
        GlossaryEntry formula = new GlossaryEntry("count", "Number of trades", null, null, null, "count", java.util.Map.of(), null, "derived:desk.fields.count", null);
        assertThat(r.resolve("desk", "count", k -> Optional.of(formula)).orElseThrow().origin()).isEqualTo("derived:desk.fields.count");
        assertThat(r.resolve("desk", "count", NONE).orElseThrow().origin()).isEqualTo("core:vocabulary.count");   // no derived entry: core
        assertThat(r.resolve("thing", "mtm", k -> Optional.of(formula)).orElseThrow().term()).isEqualTo("Base MTM");   // the author's wins
    }

    @Test
    void theCoreVocabularyIsSmallAndLoadsWithoutProblems() {
        AboutCatalog cat = new AboutCatalog(new AboutProperties(List.of(), null, null), new ElCompiler());
        assertThat(cat.core("currency")).isPresent();
        assertThat(cat.core("asOf")).isPresent();
        long n = List.of("id", "name", "title", "description", "status", "type", "category", "currency", "amount", "price", "quantity", "count",
                "total", "rate", "weight", "score", "date", "asOf", "createdAt", "updatedAt", "startDate", "endDate", "owner", "country", "region",
                "source", "version", "notes").stream().filter(k -> cat.core(k).isPresent()).count();
        assertThat(n).isEqualTo(28).isLessThanOrEqualTo(40);
    }
}
