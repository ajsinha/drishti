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
package com.ash.drishti.server.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.identity.content.ContentWatch;
import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.PackRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Connectors drifted from their pack's current template: found, merged when additive, fixed by the buttons, shared by the overlay. */
class TemplateUpdatesTest {

    /** The pack serves kinds trade, valuation and region-economy; its template names all three and a layout for each. */
    private static final String PACK = "pack: risk\nkinds: [trade, valuation, region-economy]\nroutes:\n  trade: risk-store\n  valuation: risk-store\n"
            + "  region-economy: risk-store\nconnectors:\n  risk-store:\n    plugin: fake\n    kinds: [trade, valuation, region-economy]\n"
            + "    settings:\n      url: db://pack-default\n      pool: 4\n      layout:\n        trade: t\n        valuation: v\n        region-economy: r\n";

    /** Generated from an older template: lacks valuation and region-economy, has an obsolete kind, and the url was edited. */
    private static final String STALE = "# Generated at first start from the connector template of pack 'risk'.\nplugin: fake\n"
            + "kinds: [trade, price-basket]\nsettings:\n  url: db://edited-by-admin\n  pool: 4\n  layout:\n    trade: t\n    price-basket: p\n";

    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<String> audited = new CopyOnWriteArrayList<>();

    private record Node(ConnectorFiles files, SourceRegistry registry, ConnectorManager manager, TemplateUpdates updates) {}

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : open) {
            c.close();
        }
    }

    private Node node(Path dir, ContentStore store, TemplateUpdates.Mode mode) throws Exception {
        return node(dir, store, mode, PACK, STALE);
    }

    private Node node(Path dir, ContentStore store, TemplateUpdates.Mode mode, String packText, String staleText) throws Exception {
        Path conf = Files.createDirectories(dir.resolve("connectors"));
        Path pack = Files.createDirectories(dir.resolve("packs/risk"));
        Files.writeString(pack.resolve("pack.yaml"), packText);
        Files.writeString(conf.resolve("risk-store.yaml"), staleText);
        ConnectorFiles files = new ConnectorFiles(conf, dir.resolve("state"));
        ContentConnectorOverlay overlay = store == null ? null : new ContentConnectorOverlay(store);
        if (overlay != null) {
            files.overlay(overlay);
        }
        MockEnvironment env = new MockEnvironment().withProperty("drishti.packs.dir", dir.resolve("packs").toString())
                .withProperty("drishti.packs.loaded", "risk");
        Map<String, SourcesProperties.ConnectorSettings> startup = new HashMap<>();
        new ConnectorFiles(conf).readAll(new HashMap<>()).forEach((n, d) ->
                startup.put(n, new SourcesProperties.ConnectorSettings(d.plugin(), d.isEnabled(), d.kinds(), d.settings())));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(1), null, startup, conf.toString(), "off",
                Duration.ofMillis(50), Duration.ZERO, true);
        var proto = new ConnectorManagerTest.Fake();
        SourceRegistry registry = new SourceRegistry(List.of(proto), props, new JsonCodec());
        ConnectorManager m = new ConnectorManager(files, registry, props, new PackRegistry(env), env, new SettingCatalogue(List.of(proto)),
                new ConnectionProbe(List.of(proto), new JsonCodec()), 2, 5);
        AuditLog audit = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
                audited.add(actor + " " + action + " " + subject + ": " + detail);
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        TemplateUpdates u = new TemplateUpdates(m, audit, mode);
        if (store != null) {
            ContentWatch watch = new ContentWatch(store, 100);
            watch.subscribe(ContentConnectorOverlay.PREFIX, keys -> {
                if (!overlay.refresh(keys).isEmpty()) {
                    m.scan();
                }
            });
            watch.start();
            open.add(watch);
        }
        open.add(u);
        return new Node(files, registry, m, u);
    }

    @Test
    void theDriftIsSortedIntoAdditiveConflictingAndObsolete(@TempDir Path dir) throws Exception {
        Node n = node(dir, null, TemplateUpdates.Mode.NOTIFY);
        TemplateUpdates.Drift d = n.updates().drift("risk-store").orElseThrow();
        assertThat(d.missingKinds()).containsExactly("valuation", "region-economy");
        assertThat(d.newSettings()).containsOnlyKeys("layout.valuation", "layout.region-economy");
        assertThat(d.conflicting()).containsOnlyKeys("url");
        assertThat(d.obsoleteKinds()).containsExactly("price-basket");
        assertThat(n.updates().drifts()).hasSize(1);
    }

    /** A pack whose layout gained columns (the VaR inputs in 2.9.4: RUN_KIND, COMPUTED_AT, VALUE_TYPE ...). */
    private static final String GROWN = "pack: risk\nkinds: [trade]\nroutes:\n  trade: risk-store\nconnectors:\n  risk-store:\n    plugin: fake\n"
            + "    kinds: [trade]\n    settings:\n      url: db://pack-default\n      layout:\n        trade:\n          columns: [TRADE_ID, DESK, RUN_KIND, GRAIN]\n"
            + "          vectors: [PNL]\n";

    /** Generated from the older template: its columns lack the new ones, and an administrator added one of their own. */
    private static final String OLD_LAYOUT = "# Generated at first start from the connector template of pack 'risk'.\nplugin: fake\nkinds: [trade]\n"
            + "settings:\n  url: db://pack-default\n  layout:\n    trade:\n      columns: [TRADE_ID, DESK, MY_COLUMN]\n      vectors: [PNL]\n";

    @Test
    void columnsTheTemplateGainedAreAddedAtStartTheFileKeptBeside(@TempDir Path dir) throws Exception {
        Node n = node(dir, null, TemplateUpdates.Mode.ADDITIVE, GROWN, OLD_LAYOUT);
        TemplateUpdates.Drift d = n.updates().drift("risk-store").orElseThrow();
        assertThat(d.extendedLists()).containsEntry("layout.trade.columns", "TRADE_ID,DESK,MY_COLUMN,RUN_KIND,GRAIN");
        assertThat(d.conflicting()).isEmpty();
        assertThat(n.updates().check()).containsExactly("risk-store");
        assertThat(n.files().read("risk-store").settings()).containsEntry("layout.trade.columns", "TRADE_ID,DESK,MY_COLUMN,RUN_KIND,GRAIN")
                .containsEntry("layout.trade.vectors", "PNL");
        try (var s = Files.list(dir.resolve("connectors"))) {
            List<Path> kept = s.filter(f -> f.getFileName().toString().startsWith("risk-store.yaml.before-")).toList();
            assertThat(kept).hasSize(1);
            assertThat(Files.readString(kept.get(0))).isEqualTo(OLD_LAYOUT);
        }
        assertThat(n.updates().notices().get(0).what()).contains("the template's new columns in [layout.trade.columns]");
        assertThat(n.updates().check()).isEmpty();                                                       // once: nothing more to add
        assertThat(TemplateUpdates.grown("A,B", "A,B")).isNull();                                       // an edit only: never automatic
        assertThat(TemplateUpdates.grown("A,X", "A")).isNull();
    }

    @Test
    void withNotifyItIsOnlyListed(@TempDir Path dir) throws Exception {
        Node n = node(dir, null, TemplateUpdates.Mode.NOTIFY);
        assertThat(n.updates().check()).isEmpty();
        assertThat(n.files().text("risk-store")).isEqualTo(STALE);
        assertThat(n.updates().drifts()).hasSize(1);
    }

    @Test
    void additiveMergesTheMissingKindsAndKeepsEveryEdit(@TempDir Path dir) throws Exception {
        Node n = node(dir, null, TemplateUpdates.Mode.ADDITIVE);
        assertThat(n.updates().check()).containsExactly("risk-store");
        ConnectorFiles.Definition d = n.files().read("risk-store");
        assertThat(d.kinds()).containsExactly("trade", "price-basket", "valuation", "region-economy");     // obsolete never removed automatically
        assertThat(d.settings()).containsEntry("url", "db://edited-by-admin")                              // the admin's edit is kept
                .containsEntry("layout.valuation", "v").containsEntry("layout.region-economy", "r").containsEntry("layout.price-basket", "p");
        assertThat(n.registry().applied("risk-store").orElseThrow().kinds()).contains("valuation", "region-economy");
        assertThat(n.files().history("risk-store")).isNotEmpty();
        assertThat(n.files().revisionText("risk-store", n.files().history("risk-store").get(0).id())).isEqualTo(STALE);
        assertThat(n.updates().notices()).hasSize(1);
        assertThat(audited).anyMatch(a -> a.startsWith("system connector-template-update risk-store"));
        TemplateUpdates.Drift left = n.updates().drift("risk-store").orElseThrow();
        assertThat(left.additive()).isFalse();
        assertThat(left.conflicting()).containsOnlyKeys("url");
        assertThat(n.updates().check()).isEmpty();                                                           // nothing more to do
    }

    @Test
    void theButtonsWorkAndKeepTheHistory(@TempDir Path dir) throws Exception {
        Node n = node(dir, null, TemplateUpdates.Mode.OFF);
        n.updates().removeObsolete("risk-store", null, "admin");
        assertThat(n.files().read("risk-store").kinds()).containsExactly("trade");
        n.updates().addMissing("risk-store", null, "admin");
        assertThat(n.files().read("risk-store").kinds()).containsExactly("trade", "valuation", "region-economy");
        assertThat(n.files().read("risk-store").settings()).containsEntry("url", "db://edited-by-admin");
        n.updates().regenerate("risk-store", null, "admin");
        assertThat(n.files().read("risk-store").settings()).containsEntry("url", "db://pack-default");
        assertThat(n.updates().drift("risk-store").map(TemplateUpdates.Drift::any).orElse(false)).isFalse();
        assertThat(n.files().history("risk-store")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(n.files().history("risk-store").stream().map(r -> n.files().revisionText("risk-store", r.id()))).contains(STALE);
    }

    @Test
    void fixAllAdditiveMergesEveryConnector(@TempDir Path dir) throws Exception {
        Node n = node(dir, null, TemplateUpdates.Mode.OFF);
        assertThat(n.updates().fixAllAdditive("admin")).containsExactly("risk-store");
        assertThat(n.files().read("risk-store").kinds()).contains("valuation", "region-economy");
    }

    @Test
    void theMergeIsSharedAcrossTwoNodesThroughTheOverlay(@TempDir Path dir) throws Exception {
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        Node a = node(dir.resolve("a"), store, TemplateUpdates.Mode.ADDITIVE);
        Node b = node(dir.resolve("b"), store, TemplateUpdates.Mode.NOTIFY);
        a.updates().check();
        assertThat(Files.readString(dir.resolve("a/connectors/risk-store.yaml"))).isEqualTo(STALE);          // the file, the base, is untouched
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end && !b.registry().applied("risk-store").map(c -> c.kinds().contains("valuation")).orElse(false)) {
            Thread.sleep(50);
        }
        assertThat(b.registry().applied("risk-store").orElseThrow().kinds()).contains("valuation", "region-economy");
        assertThat(b.files().source("risk-store")).isEqualTo("shared");
        assertThat(b.updates().drift("risk-store").orElseThrow().additive()).isFalse();
    }
}
