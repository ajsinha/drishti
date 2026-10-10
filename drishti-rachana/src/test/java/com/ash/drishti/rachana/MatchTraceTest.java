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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.MatchTrace.Result;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The match trace: every Sutra of the kind in priority order with its verdict; {@code match()} is unchanged. */
class MatchTraceTest {

    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, List.of("../config/packs/finance/config/formats.yaml"));

    @TempDir
    Path dir;

    private static String sutra(String name, String match, String description) {
        return "rachana: 1\nsutra: " + name + "\nversion: 1\n" + (description == null ? "" : "description: " + description + "\n")
                + "match: " + match + "\npanels:\n  - { id: p, kind: links }\n";
    }

    private SutraRegistry registry() throws Exception {
        Files.writeString(dir.resolve("hi.sutra.yaml"), sutra("hi", "{ kind: thing, where: \"$.scope == 'desk'\", priority: 20 }", null));
        Files.writeString(dir.resolve("mid.sutra.yaml"), sutra("mid", "{ kind: thing, where: \"$.secret == 'x'\", priority: 10 }", "Middle layout"));
        Files.writeString(dir.resolve("lo.sutra.yaml"), sutra("lo", "{ kind: thing, priority: 1 }", null));
        return new SutraRegistry(new RachanaProperties(List.of(dir.toString()), false, null, null, null, null, null, null, null, null), EL);
    }

    private static DataNode doc(String json) {
        return new JsonCodec().read(json);
    }

    @Test
    void listsEveryCandidateInPriorityOrderWithItsVerdict() throws Exception {
        try (SutraRegistry r = registry()) {
            MatchTrace t = new SutraMatcher(r, EL, F).explain("thing", doc("{\"scope\":\"book\",\"secret\":\"x\"}"));
            assertThat(t.candidates()).extracting(c -> c.sutra().name()).containsExactly("hi", "mid", "lo");
            assertThat(t.candidates()).extracting(MatchTrace.Candidate::result).containsExactly(Result.FALSE, Result.TRUE, Result.TRUE);
            assertThat(t.candidates().get(0).where()).isEqualTo("$.scope == 'desk'");
            assertThat(t.candidates().get(2).where()).isNull();
            assertThat(t.chosen()).get().extracting(s -> s.name()).isEqualTo("mid");
        }
    }

    @Test
    void chosenIsWhatMatchReturnsAndNothingMatchingMeansNone() throws Exception {
        try (SutraRegistry r = registry()) {
            SutraMatcher m = new SutraMatcher(r, EL, F);
            for (String json : List.of("{\"scope\":\"desk\"}", "{\"secret\":\"x\"}", "{}")) {
                assertThat(m.explain("thing", doc(json)).chosen()).isEqualTo(m.match("thing", doc(json)));
            }
            assertThat(m.explain("nothing", doc("{}")).chosen()).isEmpty();
            assertThat(m.explain("nothing", doc("{}")).candidates()).isEmpty();
        }
    }

    @Test
    void aWhereThatReadsAMaskedFieldIsNotAnswered() throws Exception {
        try (SutraRegistry r = registry()) {
            DataNode stored = doc("{\"scope\":\"book\",\"secret\":\"x\"}");
            DataNode seen = doc("{\"scope\":\"book\",\"secret\":\"" + DataNode.MASK + "\"}");
            MatchTrace t = new SutraMatcher(r, EL, F).explain("thing", stored, seen);
            assertThat(t.candidates()).extracting(MatchTrace.Candidate::result).containsExactly(Result.FALSE, Result.MASKED, Result.TRUE);
            assertThat(t.chosen()).get().extracting(s -> s.name()).isEqualTo("mid");   // the view's real choice, made on the stored document
        }
    }

    @Test
    void matchIsUnchangedOverEveryShippedFinanceFixture() throws Exception {
        Path fixtures = ReferenceSutrasTest.FIXTURES;
        try (SutraRegistry r = new SutraRegistry(new RachanaProperties(List.of("../config/packs/finance/sutras"), false, null, null, null, null, null, null, null, null), EL);
                Stream<Path> files = Files.walk(fixtures)) {
            SutraMatcher m = new SutraMatcher(r, EL, F);
            int n = 0;
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String kind = f.getParent().getFileName().toString();
                DataNode d = new JsonCodec().read(Files.readString(f));
                assertThat(m.explain(kind, d).chosen()).as(f.toString()).isEqualTo(m.match(kind, d));
                n++;
            }
            assertThat(n).isPositive();
        }
    }

    @Test
    void theSutraDescriptionIsKeptAtParse() throws Exception {
        try (SutraRegistry r = registry()) {
            assertThat(r.latest("mid").orElseThrow().description()).isEqualTo("Middle layout");
            assertThat(r.latest("hi").orElseThrow().description()).isNull();
        }
    }
}
