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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourcesProperties;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Connector edits made through Admin on one node reach the other through the content store overlay (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 11). */
class SharedConnectorEditsTest {

    private final List<AutoCloseable> open = new ArrayList<>();

    /** One node: its own config folder (the same base files), the shared store, and a content watch polling it. */
    private record Node(ConnectorFiles files, SourceRegistry registry, ConnectorManager manager, ContentWatch watch) {}

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : open) {
            c.close();
        }
    }

    private Node node(Path dir, ContentStore store, String base) throws Exception {
        Path conf = Files.createDirectories(dir.resolve("connectors"));
        Files.createDirectories(dir.resolve("packs"));
        if (base != null) {
            Files.writeString(conf.resolve("lake.yaml"), base);
        }
        ContentConnectorOverlay overlay = new ContentConnectorOverlay(store);
        ConnectorFiles files = new ConnectorFiles(conf, dir.resolve("state")).overlay(overlay);
        MockEnvironment env = new MockEnvironment().withProperty("drishti.packs.dir", dir.resolve("packs").toString())
                .withProperty("THE_SECRET", "hunter2-resolved");
        Map<String, SourcesProperties.ConnectorSettings> startup = new HashMap<>();
        new ConnectorFiles(conf).readAll(new HashMap<>()).forEach((n, d) ->      // what start-up read: the files alone
                startup.put(n, new SourcesProperties.ConnectorSettings(d.plugin(), d.isEnabled(), d.kinds(), d.settings())));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(1), null, startup, conf.toString(), "off",
                Duration.ofMillis(50), Duration.ZERO, true);
        var proto = new ConnectorManagerTest.Fake();
        SourceRegistry registry = new SourceRegistry(List.of(proto), props, new JsonCodec());
        ConnectorManager m = new ConnectorManager(files, registry, props, new PackRegistry(env), env, new SettingCatalogue(List.of(proto)),
                new ConnectionProbe(List.of(proto), new JsonCodec()), 2, 5);
        ContentWatch watch = new ContentWatch(store, 100);
        watch.subscribe(ContentConnectorOverlay.PREFIX, keys -> {
            if (!overlay.refresh(keys).isEmpty()) {
                m.scan();
            }
        });
        m.scan();
        watch.start();
        open.add(watch);
        return new Node(files, registry, m, watch);
    }

    private static String url(Node n) {
        return n.registry().applied("lake").map(c -> c.settings().get("url")).orElse(null);
    }

    private static void await(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!ok.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertThat(ok.getAsBoolean()).as("within the poll interval").isTrue();
    }

    private static final String BASE = "plugin: fake\nsettings:\n  url: db://from-file\n";

    @Test
    void anEditOnOneNodeIsAppliedOnTheOtherAndResetGoesBackToTheFile(@TempDir Path dir) throws Exception {
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        Node a = node(dir.resolve("a"), store, BASE);
        Node b = node(dir.resolve("b"), store, BASE);
        assertThat(url(b)).isEqualTo("db://from-file");

        String etag = ConnectorFiles.etag(a.files().text("lake"));
        a.manager().save("lake", "plugin: fake\nsettings:\n  url: db://edited-on-a\n", etag, true);
        assertThat(url(a)).isEqualTo("db://edited-on-a");
        assertThat(Files.readString(dir.resolve("a/connectors/lake.yaml"))).isEqualTo(BASE);       // the file, the GitOps base, is untouched
        await(() -> "db://edited-on-a".equals(url(b)));
        assertThat(b.manager().summary("lake")).containsEntry("source", "shared");
        assertThat(b.files().history("lake")).isNotEmpty();
        assertThat(b.files().revisionText("lake", b.files().history("lake").get(0).id())).isEqualTo(BASE);

        b.manager().setEnabled("lake", false, null, true);
        await(() -> a.registry().applied("lake").map(c -> !c.enabled()).orElse(true));

        a.manager().reset("lake", null);                                                         // Reset removes the overlay
        assertThat(a.files().source("lake")).isEqualTo("file");
        await(() -> "db://from-file".equals(url(b)) && "file".equals(b.files().source("lake")));
    }

    @Test
    void createAndDeleteReachEveryNode(@TempDir Path dir) throws Exception {
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        Node a = node(dir.resolve("a"), store, BASE);
        Node b = node(dir.resolve("b"), store, BASE);
        a.manager().save("extra", "plugin: fake\nsettings:\n  url: db://new\n", null, true);
        await(() -> b.registry().applied("extra").isPresent());
        assertThat(Files.exists(dir.resolve("a/connectors/extra.yaml"))).isFalse();

        b.manager().delete("lake", null, true);                                                  // a file deleted through Admin is hidden on every node
        await(() -> a.registry().applied("lake").isEmpty() && !a.files().exists("lake"));
        assertThat(Files.exists(dir.resolve("a/connectors/lake.yaml"))).isTrue();
        assertThat(a.manager().summary("lake").get("source")).isEqualTo("shared-hidden");
        a.manager().reset("lake", null);
        await(() -> "db://from-file".equals(url(b)));
    }

    @Test
    void twoNodesEditingOneConnectorGiveOneConflict(@TempDir Path dir) throws Exception {
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        Node a = node(dir.resolve("a"), store, BASE);
        Node b = node(dir.resolve("b"), store, BASE);
        String etag = ConnectorFiles.etag(BASE);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger saved = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (Node n : List.of(a, b)) {
            ts.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    n.manager().save("lake", "plugin: fake\nsettings:\n  url: db://" + (n == a ? "a" : "b") + "\n", etag, true);
                    saved.incrementAndGet();
                } catch (DrishtiException e) {
                    if (e.errorCode() == ErrorCode.CONNECTOR_CONFLICT) {
                        conflicts.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        go.countDown();
        for (Thread t : ts) {
            t.join();
        }
        assertThat(saved.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        // a stale etag after the other node's edit is refused too
        assertThatThrownBy(() -> b.manager().save("lake", BASE, etag, true)).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.CONNECTOR_CONFLICT));
    }

    @Test
    void secretsAreNeverWrittenResolved(@TempDir Path dir) throws Exception {
        ContentStore store = new FileContentStore(dir.resolve("fstore"));
        Node a = node(dir.resolve("a"), store, BASE);
        a.manager().save("lake", "plugin: fake\nsettings:\n  url: db://x\n  password: ${THE_SECRET}\n", ConnectorFiles.etag(BASE), true);
        String kept = store.get("config/connectors/lake.yaml").orElseThrow().text();
        assertThat(kept).contains("${THE_SECRET}").doesNotContain("hunter2");
        for (String k : store.list("")) {
            assertThat(store.get(k).map(ContentStore.Item::text).orElse("")).as(k).doesNotContain("hunter2");
        }
        assertThat(a.registry().applied("lake").orElseThrow().settings().get("password")).isEqualTo("hunter2-resolved");
    }
}
