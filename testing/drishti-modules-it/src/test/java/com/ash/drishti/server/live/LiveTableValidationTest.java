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
package com.ash.drishti.server.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.lake.tck.TestRoots;
import com.ash.rachana.format.Formats;
import com.ash.rachana.live.LiveTables;
import com.ash.rachana.table.LiveTableSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Declarations are checked at load (docs/guides/USER_GUIDE.md, Live results and blotters: The declaration; the liveset engine's {@code LiveSetValidationTest},
 * ported): named formats exist in formats.yaml, columns exist in the kind's data contract, an edit form names where its changes go; a bad
 * one is set aside and listed for Admin, never half-served. The shipped trade blotter ({@code trade.live.yaml}) passes against the trading
 * pack's contract and formats, and its edit rules carry the book's pattern.
 */
class LiveTableValidationTest {

    static final Path ROOT = Path.of(TestRoots.repo()).toAbsolutePath().normalize();
    static final Path TRADE = ROOT.resolve("modules/trading/blotters/trade.live.yaml");
    static final Path CONTRACT = ROOT.resolve("modules/banking/packs/trading/contracts/trade.contract.yaml");
    static final Formats FORMATS = Formats.load(null, List.of(ROOT.resolve("modules/banking/packs/trading/config/formats.yaml").toString()));

    @Test
    void theShippedTradeBlotterPassesAndItsEditRulesCarryThePattern() throws Exception {
        LiveTableSpec spec = LiveTableSpec.read(Files.readString(TRADE), "trade.live.yaml", DrishtiLiveTables.PROFILE_KEYS, FORMATS::has);
        assertThat(DrishtiLiveTables.contractFields(CONTRACT)).isPresent();
        assertThat(DrishtiLiveTables.problems(spec, FORMATS::has, k -> DrishtiLiveTables.contractFields(CONTRACT))).isEmpty();
        assertThat(spec.key()).containsExactly("TRADE_ID");
        assertThat(spec.version()).isEqualTo("TRADE_VERSION");
        assertThat(spec.editableColumns()).contains("PORTFOLIO", "PRICE", "QUANTITY", "TRADE_STATUS").doesNotContain("TRADE_ID");
        assertThat(DrishtiLiveTables.form(spec).fields()).anySatisfy(f -> {
            assertThat(f.recordField()).isEqualTo("PORTFOLIO");
            assertThat(String.valueOf(f)).contains("BOOK-[A-Z0-9-]+");
        });
        assertThat(DrishtiLiveTables.rules(List.of(spec))).extracting(r -> r.field().recordField())
                .containsExactlyInAnyOrder("PORTFOLIO", "PRICE", "QUANTITY", "TRADE_STATUS");
    }

    @Test
    void aBadDeclarationIsSetAsideWithItsProblemsAndNeverServed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad.live.yaml"), String.join("\n",
                "rachana: 1",
                "live-table: bad",
                "kind: trade",
                "snapshot: trade",
                "key: TRADE_ID",
                "columns:",
                "- { name: TRADE_ID }",
                "- { name: PRICE, fmt: number4 }",
                ""));
        Files.writeString(dir.resolve("stray.live.yaml"), "rachana: 1\nlive-table: stray\nkind: trade\nsnapshot: trade\nkey: TRADE_ID\n"
                + "columns: [ { name: TRADE_ID }, { name: NOT_A_FIELD } ]\n");
        Files.writeString(dir.resolve("good.live.yaml"), "rachana: 1\nlive-table: good\nkind: trade\nsnapshot: trade\nkey: TRADE_ID\n"
                + "columns: [ { name: TRADE_ID }, { name: PRICE } ]\n");
        Files.writeString(dir.resolve("broken.live.yaml"), "rachana: 1\nlive-table: broken\nkind: trade\n");
        Map<String, LiveTableSpec> specs = new LinkedHashMap<>();
        try (LiveTables lt = new LiveTables(new LiveTables.Resolver() {
            public com.ash.rachana.live.spi.SnapshotSource snapshot(String kind) { return null; }
            public com.ash.rachana.live.spi.LiveStream stream(String kind) { return null; }
        }, LiveTables.Options.DEFAULTS.withUpkeepMs(0), Clock.systemUTC())) {
            for (String f : List.of("bad.live.yaml", "stray.live.yaml", "good.live.yaml", "broken.live.yaml")) {
                DrishtiLiveTables.declare(lt, specs, dir.resolve(f), FORMATS::has, k -> DrishtiLiveTables.contractFields(CONTRACT));
            }
            assertThat(lt.has("good")).isTrue();
            assertThat(lt.has("bad")).isFalse();
            assertThat(lt.has("broken")).isFalse();
            assertThat(lt.has("stray")).isFalse();
            assertThat(String.join("; ", DrishtiLiveTables.ASIDE.get("bad.live.yaml"))).contains("number4");          // Rachana's own check
            assertThat(String.join("; ", DrishtiLiveTables.ASIDE.get("stray.live.yaml")))                            // Drishti's: the contract
                    .contains("field NOT_A_FIELD is not in the trade data contract");
            assertThat(DrishtiLiveTables.ASIDE.get("broken.live.yaml")).isNotEmpty();
        }
    }

    @Test
    void aKindWithoutAContractIsCheckedForFormatsOnly() {
        LiveTableSpec s = LiveTableSpec.read("rachana: 1\nlive-table: x\nkind: thing\nsnapshot: thing\nkey: ANY\ncolumns: [ { name: ANY, fmt: amount2 } ]\n",
                "x.live.yaml", DrishtiLiveTables.PROFILE_KEYS, FORMATS::has);
        assertThat(DrishtiLiveTables.problems(s, FORMATS::has, k -> Optional.<Set<String>>empty())).isEmpty();
    }
}
