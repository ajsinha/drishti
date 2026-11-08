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
package com.ash.drishti.server.tables;

import com.ash.drishti.server.bi.Layered;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.engine.ViewerContext;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.SecurityProperties;
import com.ash.drishti.tables.read.TableReads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A declared mask always holds (a leak otherwise): with an installation that masks no field of its own ({@code drishti.security.redact}
 * empty), a column a virtual table declares {@code masked} is still masked for a reader without the raw power and shown to one with it, and
 * so is a column a BI dataset lists under {@code security.masked}.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=",
        "drishti.security.roles.plain.kinds[0]=trade",
        "drishti.security.roles.rawrole.kinds[0]=trade", "drishti.security.roles.rawrole.raw=true",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
class DeclaredMasksTest {

    static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("drishti-declared-masks-test");
            Files.createDirectories(ROOT.resolve("trading"));
            Files.writeString(ROOT.resolve("trading/trade_marks.table.yaml"), """
                    rachana: 3
                    table: trading.trade_marks
                    from: { kind: trade }
                    columns:
                      PRODUCT: { path: $.productType, type: string }
                      MTM:     { path: $.mtm, type: decimal, masked: true }
                    """);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void clean() throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(ROOT)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }

    @DynamicPropertySource
    static void tables(DynamicPropertyRegistry r) {
        r.add("drishti.tables.root", ROOT::toString);
    }

    @Autowired TablesService tables;
    @Autowired Entitlements entitlements;
    @Autowired DatasetService datasets;
    @Autowired SecurityProperties security;

    ViewerContext viewer(String role) {
        return entitlements.viewer(new Principal("u-" + role, List.of(role)));
    }

    @Test
    void aMaskedTableColumnIsMaskedWithoutTheRawPowerEvenWhenTheInstallationMasksNothing() {
        assertThat(security.redact()).isEmpty();
        assertThat(entitlements.raw(new Principal("u-plain", List.of("plain")))).isTrue();          // the installation's own masks: none
        ViewerContext plain = viewer("plain");
        TableReads.Read r = tables.read(tables.reads(plain), plain, "trading.trade_marks", null, null, null);
        int mtm = r.columns().indexOf("MTM");
        assertThat(r.rows()).isNotEmpty().allMatch(row -> DataNode.MASK.equals(row.get(mtm)));
        assertThat(r.masked()).contains("MTM");
        ViewerContext raw = viewer("rawrole");
        TableReads.Read seen = tables.read(tables.reads(raw), raw, "trading.trade_marks", null, null, null);
        assertThat(seen.masked()).doesNotContain("MTM");
        assertThat(seen.rows()).anyMatch(row -> row.get(mtm) instanceof Number);
    }

    @Test
    void aBiDatasetsMaskedColumnHoldsTheSameWay() throws Exception {
        Dataset ds = Layered.dataset(Files.readString(Path.of("../config/tables/banking/trading_risk_counterparties.table.yaml"))).dataset();
        String table = ds.tables().keySet().iterator().next();
        assertThat(datasets.masks(new Principal("u-plain", List.of("plain")), ds).masked(table, "NAME")).isTrue();
        assertThat(datasets.masks(new Principal("u-plain", List.of("plain")), ds).masked(table, "SECTOR")).isFalse();
        assertThat(datasets.masks(new Principal("u-rawrole", List.of("rawrole")), ds).masked(table, "NAME")).isFalse();
    }
}
