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

    /** A template naming a kind no pack declares (the market-risk pack's pv-updates: the stream's own pv-update) and its file. */
    private static final String STREAM = "pack: risk\nkinds: [trade]\nroutes:\n  trade: risk-store\nconnectors:\n  risk-store:\n    plugin: fake\n"
            + "    kinds: [trade, pv-update]\n    settings:\n      url: db://pack-default\n";
    private static final String STREAM_FILE = "# Generated at first start from the connector template of pack 'risk'.\nplugin: fake\n"
            + "kinds: [trade, pv-update]\nsettings:\n  url: db://pack-default\n";

    @Test
    void aKindTheTemplateItselfNamesIsNeverObsolete(@TempDir Path dir) throws Exception {
        Node n = node(dir, null, TemplateUpdates.Mode.NOTIFY, STREAM, STREAM_FILE);
        assertThat(n.updates().drift("risk-store").map(TemplateUpdates.Drift::obsoleteKinds).orElse(List.of())).isEmpty();
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

    /** A reactive connector written before its pack's template gained a parameter (var-whatif's ccy in 2.9.4: a call naming it was refused). */
    private static final String OLD_REACTIVE = "plugin: reactive\nmode: reactive\nkinds:\n  var-whatif:\n    compute: var-whatif\n    params:\n"
            + "      sandbox:\n        type: sandbox-ref\n      stress-window:\n        type: int\n        default: 250\n    result:\n"
            + "      required: [AFTER_VAR]\nreads:\n- kind: var-result\n  as: base\n  id: \"{id}\"\nengine:\n  type: python\n"
            + "  entry: drishti_compute.var.whatif:VarWhatIfEngine\n  pool: var\n";

    @Test
    void parametersAReactiveTemplateGainedAreAddedTheRestKept(@TempDir Path dir) throws Exception {
        Path conf = Files.createDirectories(dir.resolve("connectors"));
        Files.writeString(conf.resolve("var-whatif.yaml"), OLD_REACTIVE);
        ConnectorFiles files = new ConnectorFiles(conf, dir.resolve("state"));
        ConnectorFiles.Definition d = files.read("var-whatif");
        Map<String, Object> template = Map.of("plugin", "reactive", "kinds", Map.of("var-whatif", Map.of("compute", "var-whatif", "params",
                Map.of("sandbox", Map.of("type", "sandbox-ref"), "stress-window", Map.of("type", "int", "default", 500),
                        "ccy", Map.of("type", "text", "default", "USD")))));
        Map<String, Object> fresh = TemplateUpdates.newParams(template, d.compute());
        assertThat(fresh).containsOnlyKeys("var-whatif.ccy");                                     // stress-window's other default is not touched
        Map<String, Object> merged = TemplateUpdates.withParams(d.compute(), fresh);
        String text = files.render("", d.plugin(), null, d.kinds(), d.description(), d.settings(), d.requires(), merged, d.writes());
        Files.writeString(conf.resolve("var-whatif.yaml"), text);
        ConnectorFiles.Definition after = new ConnectorFiles(conf, dir.resolve("state2")).read("var-whatif");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) after.compute().get("kinds"))
                .get("var-whatif")).get("params");
        assertThat(params).containsKeys("sandbox", "stress-window", "ccy");
        assertThat(String.valueOf(params.get("stress-window"))).contains("250");                // the file's own default kept
        assertThat(after.compute()).containsKeys("reads", "engine");
        assertThat(TemplateUpdates.newParams(template, after.compute())).isEmpty();                // once: nothing more to add
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

    /** 2.9.5: a stamped file nobody edited follows its pack's new template in full, changed values included, the file before kept. */
    @Test
    void anUneditedStampedFileIsReplacedByTheNewTemplate(@TempDir Path dir) throws Exception {
        Path conf = Files.createDirectories(dir.resolve("connectors"));
        Path pack = Files.createDirectories(dir.resolve("packs/risk"));
        Files.writeString(pack.resolve("pack.yaml"), PACK.replace("db://pack-default", "db://old-default"));
        ConnectorFiles gen = new ConnectorFiles(conf, dir.resolve("state"));
        var old = new com.ash.drishti.packs.PackLoader().load(dir.resolve("packs"), List.of("risk"));
        String text = com.ash.drishti.packs.ConnectorBootstrap.generate(gen, "risk-store", "risk", old.get(0).connectorTemplates().get("risk-store"));
        Node n = node(dir, null, TemplateUpdates.Mode.ADDITIVE, PACK, text);                  // the pack moved on: url changed, a pristine file
        TemplateUpdates.Drift d = n.updates().drift("risk-store").orElseThrow();
        assertThat(d.pristine()).isTrue();
        assertThat(d.followsPack()).isTrue();
        assertThat(n.updates().check()).containsExactly("risk-store");
        assertThat(n.files().read("risk-store").settings()).containsEntry("url", "db://pack-default");    // the changed value too
        assertThat(com.ash.drishti.packs.TemplateStamp.pristine(n.files().text("risk-store"))).isTrue();
        assertThat(audited).anyMatch(a -> a.startsWith("system connector-template-upgrade risk-store"));
        try (var s = Files.list(conf)) {
            assertThat(s.map(f -> f.getFileName().toString())).anyMatch(f -> f.startsWith("risk-store.yaml.before-upgrade-"));
        }
        assertThat(n.updates().drift("risk-store").orElseThrow().any()).isFalse();
        assertThat(n.updates().check()).isEmpty();
    }

    @Test
    void useThePacksValueSetsOneConflictingKeyAndKeepsTheRest(@TempDir Path dir) throws Exception {
        Node n = node(dir, null, TemplateUpdates.Mode.OFF);
        n.updates().addMissing("risk-store", null, "admin");
        TemplateUpdates.Drift d = n.updates().drift("risk-store").orElseThrow();
        assertThat(d.toMap().get("conflicting").toString()).contains("where=settings").contains("key=url");
        n.updates().useTemplateValue("risk-store", "settings", "url", d.etag(), "admin");
        ConnectorFiles.Definition after = n.files().read("risk-store");
        assertThat(after.settings()).containsEntry("url", "db://pack-default").containsEntry("layout.price-basket", "p");
        assertThat(after.kinds()).contains("price-basket");                                       // the obsolete kind is still the admin's to remove
        assertThat(audited).anyMatch(a -> a.contains("used pack risk's value for url"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> n.updates().useTemplateValue("risk-store", "settings", "url", null, "admin"))
                .hasMessageContaining("no conflicting setting");
    }
}
