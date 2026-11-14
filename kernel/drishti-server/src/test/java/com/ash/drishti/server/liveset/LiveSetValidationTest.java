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
package com.ash.drishti.server.liveset;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.format.Formats;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Declarations are checked at load (modules/trading/docs/guide.md section 6): named formats exist in formats.yaml, fields exist in the
 * kind's data contract, editable fields have a write path. A bad one is set aside with DRS-9307, listed for Admin, and refused when asked
 * for; the shipped trade blotter passes against the trading pack's contract and formats, and its field description says readonly and
 * pattern.
 */
class LiveSetValidationTest {

    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    static final Path TRADE = ROOT.resolve("modules/trading/blotters/trade.blotter.yaml");
    static final Path CONTRACT = ROOT.resolve("modules/banking/dmod/packs/trading/contracts/trade.contract.yaml");
    static final Formats FORMATS = Formats.load(null, List.of(ROOT.resolve("modules/banking/dmod/packs/trading/config/formats.yaml").toString()));

    @SuppressWarnings("unchecked")
    static LiveSetSpec trade() throws Exception {
        return LiveSetSpec.parse(new ObjectMapper(new YAMLFactory()).readValue(TRADE.toFile(), Map.class), "trade.blotter.yaml", "trading");
    }

    @Test
    void theShippedTradeBlotterPassesAndDescribesReadonlyAndPattern() throws Exception {
        LiveSetSpec spec = trade();
        assertThat(LiveSetConfiguration.contractFields(CONTRACT)).isPresent();
        assertThat(LiveSetConfiguration.problems(spec, FORMATS::has, k -> LiveSetConfiguration.contractFields(CONTRACT))).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) spec.describe().get("fields");
        Map<String, Object> id = fields.stream().filter(f -> "trade_id".equals(f.get("id"))).findFirst().orElseThrow();
        Map<String, Object> book = fields.stream().filter(f -> "book".equals(f.get("id"))).findFirst().orElseThrow();
        assertThat(id.get("readonly")).isEqualTo(true);
        assertThat(book.get("readonly")).isEqualTo(false);
        assertThat(book.get("pattern")).isEqualTo("BOOK-[A-Z0-9-]+");
    }

    @Test
    void aBadDeclarationIsSetAsideWithItsCodeAndRefused(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad.blotter.yaml"), String.join("\n",
                "blotter: bad",
                "kind: trade",
                "key: TRADE_ID",
                "version: TRADE_VERSION",
                "columns:",
                "- { field: TRADE_ID }",
                "- { field: PRICE, format: number4 }",
                "- { field: NOT_A_FIELD }",
                "edit:",
                "  edit: cells",
                "  key: trade_id",
                "  fields:",
                "  - { id: trade_id, bind: TRADE_ID, readonly: true }",
                "  - { id: version, bind: TRADE_VERSION, type: number }",
                "  save: { target: overlay }",
                ""));
        Files.writeString(dir.resolve("good.blotter.yaml"), "blotter: good\nkind: trade\ncolumns: [TRADE_ID, PRICE]\n");
        Files.writeString(dir.resolve("broken.blotter.yaml"), "blotter: broken\nkind: trade\n");
        try (LiveSets sets = new LiveSets(new LiveQueryTest.Fake(), Clock.systemUTC(), 20, 100)) {
            sets.validator(s -> LiveSetConfiguration.problems(s, FORMATS::has, k -> LiveSetConfiguration.contractFields(CONTRACT)));
            sets.load(List.of(dir), d -> null);
            assertThat(sets.spec("good")).isPresent();
            assertThat(sets.spec("bad")).isEmpty();
            String why = sets.aside("bad");
            assertThat(why).startsWith("DRS-9307").contains("format number4 is not a named format", "field NOT_A_FIELD is not in the trade data contract",
                    "edit field version binds TRADE_VERSION");
            assertThat(sets.aside("broken")).startsWith("DRS-9307").contains("columns:");
            assertThat(sets.problems().values()).allMatch(p -> p.startsWith("DRS-9307")).hasSize(2);
        }
    }

    @Test
    void aKindWithoutAContractIsCheckedForFormatsOnly() {
        LiveSetSpec s = LiveSetSpec.parse(Map.of("blotter", "x", "kind", "thing", "columns", List.of(Map.of("field", "ANY", "format", "amount2"))),
                "x.blotter.yaml", null);
        assertThat(LiveSetConfiguration.problems(s, FORMATS::has, k -> Optional.<Set<String>>empty())).isEmpty();
    }
}
