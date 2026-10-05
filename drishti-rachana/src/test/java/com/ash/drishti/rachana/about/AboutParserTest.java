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

import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.el.ElCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The about file's strict parser: each problem has its code, its place, and costs only its own entry. */
class AboutParserTest {

    @TempDir Path dir;

    private final AboutParser parser = new AboutParser(new ElCompiler(), 40);

    private AboutParser.Parsed parse(String yaml) {
        return parser.parse(yaml, "about.yaml", Set.of("thing"));
    }

    private static List<String> codes(AboutParser.Parsed p) {
        return p.problems().stream().map(SutraProblem::code).toList();
    }

    @Test
    void aValidFileParsesWithNoProblem() {
        AboutParser.Parsed p = parse("""
                about: 1
                vocabulary:
                  af: { term: Allele frequency, means: Share., values: { A: first } }
                kinds:
                  thing:
                    title: A thing
                    about: "Hello ${$.name}"
                    guide: g#a
                    glossary:
                      frequencies.af: { use: af }
                      qty: { term: Q, means: M, unit: u, sign: s, note: n, formula: f }
                    panels:
                      main: { about: "Panel ${$.name}" }
                """);
        assertThat(p.problems()).isEmpty();
        assertThat(p.vocabulary()).containsKey("af");
        KindAbout k = p.kinds().get("thing");
        assertThat(k.title()).isEqualTo("A thing");
        assertThat(k.glossary()).containsKeys("frequencies.af", "qty");
        assertThat(k.glossary().get("frequencies.af").use()).isEqualTo("af");
        assertThat(k.panels()).containsKey("main");
    }

    @Test
    void aMissingOrWrongVersionRejectsTheFile() {
        assertThat(codes(parse("kinds: {}\n"))).containsExactly("DRS-2040");
        assertThat(codes(parse("about: 2\nkinds: {thing: {title: x}}\n"))).containsExactly("DRS-2040");
        assertThat(parse("about: 2\nkinds: {thing: {title: x}}\n").kinds()).isEmpty();
    }

    @Test
    void unknownKeysAreProblemsWithTheirLineAndColumn() {
        AboutParser.Parsed p = parse("about: 1\nkinds:\n  thing:\n    titel: oops\n    title: ok\n");
        assertThat(p.problems()).hasSize(1);
        SutraProblem x = p.problems().get(0);
        assertThat(x.code()).isEqualTo("DRS-2040");
        assertThat(x.location().line()).isEqualTo(4);
        assertThat(x.location().file()).isEqualTo("about.yaml");
        assertThat(p.kinds().get("thing").title()).isEqualTo("ok");                // the rest of the kind survives
        assertThat(codes(parse("about: 1\nkinds:\n  thing:\n    glossary:\n      a: { term: T, mean: typo }\n"))).containsExactly("DRS-2040");
        assertThat(codes(parse("about: 1\nvocabulary:\n  a.b: { term: T }\n"))).containsExactly("DRS-2040");
        assertThat(codes(parse("about: 1\nkinds:\n  thing:\n    glossary:\n      \"a[0]\": { term: T }\n"))).containsExactly("DRS-2040");
    }

    @Test
    void aKindOfNoPackInTheLineageIs2041() {
        AboutParser.Parsed p = parse("about: 1\nkinds:\n  stranger: { title: x }\n  thing: { title: y }\n");
        assertThat(codes(p)).containsExactly("DRS-2041");
        assertThat(p.kinds()).containsOnlyKeys("thing");
    }

    @Test
    void aTemplateThatDoesNotCompileIs2042WithItsPlaceAndTheEntryIsLeftOut() {
        AboutParser.Parsed p = parse("about: 1\nkinds:\n  thing:\n    title: T\n    about: \"x ${$.a + } y\"\n    panels:\n      p: { about: \"${\" }\n");
        assertThat(codes(p)).containsExactly("DRS-2042", "DRS-2042");
        assertThat(p.problems().get(0).location().line()).isEqualTo(5);
        assertThat(p.kinds().get("thing").about()).isNull();
        assertThat(p.kinds().get("thing").panels()).isEmpty();
        assertThat(p.kinds().get("thing").title()).isEqualTo("T");
    }

    @Test
    void textOverTheCapIs2044AndTheEntryIsLeftOut() {
        String longText = "x".repeat(41);
        AboutParser.Parsed p = parse("about: 1\nkinds:\n  thing:\n    glossary:\n      a: { term: T, means: " + longText + " }\n      b: { term: B }\n");
        assertThat(codes(p)).containsExactly("DRS-2044");
        assertThat(p.kinds().get("thing").glossary()).containsOnlyKeys("b");
    }

    @Test
    void notYamlAndDuplicateKeysAreProblemsNotExceptions() throws IOException {
        assertThat(codes(parse("about: [1\n"))).containsExactly("DRS-2040");
        assertThat(codes(parse("about: 1\nabout: 1\nkinds: {}\n"))).containsExactly("DRS-2040");
        Files.writeString(dir.resolve("x"), "");
        assertThat(codes(parse(""))).containsExactly("DRS-2040");
    }

    @Test
    void useThatNamesNoVocabularyIs2043InTheCatalogue() throws IOException {
        Path f = dir.resolve("about.yaml");
        Files.writeString(f, "about: 1\nkinds:\n  thing:\n    glossary:\n      a: { use: nope }\n      b: { term: B }\n");
        AboutCatalog c = new AboutCatalog(new AboutProperties(List.of(new AboutProperties.PackSource("p", "P", f.toString(), null,
                List.of("thing"), List.of("p"))), null, null), new ElCompiler());
        assertThat(c.problems().get("p/about.yaml")).extracting(SutraProblem::code).containsExactly("DRS-2043");
        assertThat(c.forKind("thing").orElseThrow().glossary()).containsOnlyKeys("b");
    }
}
