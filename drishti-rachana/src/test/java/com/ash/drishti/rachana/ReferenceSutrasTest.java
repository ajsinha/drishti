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
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Sutra;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Golden test: the reference Sutras applied to the demo fixtures reproduce the mockups' header strips. */
class ReferenceSutrasTest {

    static final Path FIXTURES = Path.of("..", "packs", "finance", "samples");
    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, java.util.List.of("../packs/finance/config/formats.yaml"));
    static SutraRegistry registry;
    static SutraMatcher matcher;

    @BeforeAll
    static void load() {
        registry = new SutraRegistry(new RachanaProperties(List.of("../packs/finance/sutras"), false, null, null, null, null, null, null), EL);
        matcher = new SutraMatcher(registry, EL, F);
    }

    @AfterAll
    static void close() {
        registry.close();
    }

    static DataNode fixture(String kind, String id) throws Exception {
        return new JsonCodec().read(Files.readString(FIXTURES.resolve(kind).resolve(id + ".json")));
    }

    static List<String> strip(Sutra s, DataNode doc) {
        EvalContext c = EvalContext.of(doc, F);
        return s.strip().stream().map(i -> F.format(i.fmt(), EL.compile(i.bind()).eval(c))).toList();
    }

    @Test
    void allReferenceSutrasLoadWithoutProblems() {
        assertThat(registry.problems()).isEmpty();
        assertThat(registry.all()).hasSize(5);                    // the four reference Sutras and book-pnl (a derived kind)
    }

    @Test
    void interestRateSwapStripMatchesTheMockup() throws Exception {
        DataNode doc = fixture("trade", "IRS-48213");
        Sutra s = matcher.match("trade", doc).orElseThrow();
        assertThat(s.id()).isEqualTo("irs-vanilla@3");
        assertThat(strip(s, doc)).containsExactly("50,000,000", "Pay fixed", "2026-10-02", "2031-10-02", "3.8500%",
                "−412,580", "+22,310", "RATES-NY-3");
    }

    @Test
    void fxSwapStripMatchesTheMockup() throws Exception {
        DataNode doc = fixture("trade", "FXS-20931");
        Sutra s = matcher.match("trade", doc).orElseThrow();
        assertThat(s.id()).isEqualTo("fx-swap@2");
        assertThat(strip(s, doc)).containsExactly("EUR/USD", "2026-10-02", "2027-01-04", "1.17400", "1.17958", "+55.8",
                "+18,420", "FX-LDN-2");
    }

    @Test
    void commodityFutureStripMatchesTheMockup() throws Exception {
        DataNode doc = fixture("trade", "CFT-77120");
        Sutra s = matcher.match("trade", doc).orElseThrow();
        assertThat(strip(s, doc)).containsExactly("NYMEX CL Z6", "Long", "150", "68.42", "71.15", "+409,500",
                "2026-11-20", "COMM-NY-1");
    }

    @Test
    void nettingSetStripMatchesTheMockup() throws Exception {
        DataNode doc = fixture("netting-set", "NS-NORTH-01");
        List<String> strip = strip(matcher.match("netting-set", doc).orElseThrow(), doc);
        assertThat(strip).startsWith("14").endsWith("1,300,000", "4.1m", "9.8m", "15.0m", "65%", "14:01 NY");
    }

    @Test
    void tradesWithoutAMatchingSutraFallBackToInference() throws Exception {
        assertThat(matcher.match("trade", fixture("trade", "IRS-47102"))).isEmpty();
    }
}
