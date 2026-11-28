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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.Roots;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.server.engines.EngineCatalogue;
import com.ash.drishti.server.engines.EngineFiles;
import com.ash.drishti.server.settings.SettingProposals;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each Admin writer of site files lands in the site folder and is refused, with the way out, inside the release (PRODUCTION_STANDARD.md, "Site configuration"). */
class SiteWritersTest {

    @TempDir
    Path dir;
    Path release;
    Path site;
    String savedHome;
    String savedSite;

    @BeforeEach
    void setUp() throws Exception {
        release = Files.createDirectories(dir.resolve("release/config"));
        site = Files.createDirectories(dir.resolve("etc-drishti"));
        savedHome = System.getProperty(Roots.HOME);
        savedSite = System.getProperty(Roots.SITE);
        System.setProperty(Roots.HOME, dir.resolve("release").toString());
        System.setProperty(Roots.SITE, site.toString());
    }

    @AfterEach
    void tearDown() {
        restore(Roots.HOME, savedHome);          // the test JVM's own roots (TestRun) stay for the tests that follow
        restore(Roots.SITE, savedSite);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void connectorOverridesAreWrittenToTheSiteFolderOnly() throws Exception {
        ConnectorFiles files = new ConnectorFiles(site.resolve("connectors"));
        files.write("trade-endur", "settings:\n  topic: t\n");
        assertThat(site.resolve("connectors/trade-endur.yaml")).exists();
        assertThatThrownBy(() -> new ConnectorFiles(release.resolve("connectors")).write("trade-endur", "x: 1\n"))
                .hasMessageContaining("release folder").hasMessageContaining("drishti.site.dir");
        assertThat(release.resolve("connectors")).doesNotExist();
    }

    @Test
    void anApprovedSettingGoesToTheSiteFoldersLocalFile() throws Exception {
        AuditLog audit = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
        SettingProposals toRelease = new SettingProposals(dir.resolve("data/p.json"), Path.of(dir.toString(), "release", "application.local.yaml"), audit, clock);
        var p = toRelease.propose("drishti.module.banking.var.confidence", "engines", "0.975", "0.99", "r", "mara");
        assertThatThrownBy(() -> toRelease.decide(p.id(), true, "", "nina")).hasMessageContaining("release folder");
        assertThat(dir.resolve("release/application.local.yaml")).doesNotExist();
        SettingProposals toSite = new SettingProposals(dir.resolve("data/q.json"), Roots.localFile(), audit, clock);
        var q = toSite.propose("drishti.module.banking.var.confidence", "engines", "0.975", "0.99", "r", "mara");
        toSite.decide(q.id(), true, "", "nina");
        assertThat(Files.readString(site.resolve("application.local.yaml"))).contains("0.975");
    }

    @Test
    void anEngineSwitchInTheReleaseIsRefused() {
        EngineFiles files = new EngineFiles(release.resolve("engines"), k -> null);
        var e = new EngineCatalogue.Entry("pivot", "Pivot", "", "pivot.yaml", "drishti.engines.pivot.enabled", null, List.of(), true, true, true, Map.of());
        assertThatThrownBy(() -> files.write(e, false, "mara", "2026-11-28T00:00:00Z", "r", null)).hasMessageContaining("release folder");
        assertThat(release.resolve("engines")).doesNotExist();
    }

    @Test
    void anUploadedTableGoesToTheSiteLayerAndBothLayersAreRead() throws Exception {
        Path shipped = Files.createDirectories(release.resolve("tables/banking"));
        Files.copy(Path.of("../../config/tables/banking/trading_risk_trades.table.yaml"), shipped.resolve("trading_risk_trades.table.yaml"));
        Path mine = Files.createDirectories(site.resolve("tables/banking"));
        Files.writeString(mine.resolve("my_own.table.yaml"), Files.readString(shipped.resolve("trading_risk_trades.table.yaml"))
                .replace("trading_risk_trades", "my_own"));
        com.ash.drishti.server.admin.ArtifactPaths paths = new com.ash.drishti.server.admin.ArtifactPaths(dir.resolve("p"), dir.resolve("i"), dir.resolve("c"),
                dir.resolve("b"), site.resolve("tables"));
        Path target = paths.biFile("table", "banking.another");
        assertThat(target.startsWith(site)).isTrue();
        Files.createDirectories(target.getParent());
        Roots.guardWrite(target);                                    // allowed: the site layer
        assertThatThrownBy(() -> Roots.guardWrite(release.resolve("tables/banking/x.table.yaml"))).hasMessageContaining("release folder");
        var tables = new com.ash.drishti.server.tables.TablesService(release.resolve("tables"), org.mockito.Mockito.mock(com.ash.drishti.server.data.DataViewService.class), null, null, () -> false, 10, 10, null)
                .site(site.resolve("tables"));
        assertThat(tables.writeRoot()).isEqualTo(site.resolve("tables").toAbsolutePath().normalize());
        var names = tables.catalogue().entries().stream().map(e -> e.name()).toList();
        assertThat(names).anyMatch(n -> n.endsWith("trading_risk_trades")).anyMatch(n -> n.endsWith("my_own"));
    }
}
