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

import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.packs.SamplePolicy;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.connectors.ConnectorManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Live pack reload against real folders: a pack folder added, edited (well and badly), extended by another and deleted, a
 * burst of callers, and the sample-pack mode. The watcher polls every 200 ms here; every change is picked up without a restart.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=alpha",
        "drishti.packs.watch=poll", "drishti.packs.poll=200ms", "drishti.packs.reload-debounce=100ms",
        "drishti.retry.packs.initial=300ms", "drishti.retry.packs.max=1s", "drishti.retry.packs.jitter=0",
        "drishti.identity.database-url=jdbc:sqlite:target/packreload-${random.uuid}/identity.db"})
class PackReloaderTest {

    static Path root;
    private static final String[] SYSTEM_PROPERTIES = {"drishti.packs.dir", "drishti.packs.installed-dir", "drishti.packs.overlay",
            "drishti.packs.settings-dir", "drishti.sources.connectors-dir", "drishti.packs.samples-file"};
    private static final java.util.Map<String, String> BEFORE = new java.util.HashMap<>();
    private static final String SUTRA = """
            rachana: 1
            sutra: widget-view
            version: 1
            description: A widget.
            match: { kind: widget, priority: 10 }
            title: { pill: "Widget", id: $.id }
            strip:
              - { label: Id, bind: $.id }
            panels:
              - { id: built, kind: provenance, title: How this view was built }
            """;

    static {
        for (String k : SYSTEM_PROPERTIES) {
            BEFORE.put(k, System.getProperty(k));
        }
        try {
            root = Files.createTempDirectory("drishti-reload");
            write("alpha", "Alpha", List.of(), List.of("thing"), "ALP", false, false);
            System.setProperty("drishti.packs.dir", root.resolve("packs").toString());
            System.setProperty("drishti.packs.installed-dir", root.resolve("installed").toString());
            System.setProperty("drishti.packs.overlay", root.resolve("added.yaml").toString());
            System.setProperty("drishti.packs.settings-dir", root.resolve("settings").toString());
            System.setProperty("drishti.sources.connectors-dir", root.resolve("connectors").toString());
            System.setProperty("drishti.packs.samples-file", root.resolve("samples-mode").toString());
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void forgetTheFolders() throws IOException {
        for (String k : SYSTEM_PROPERTIES) {
            if (BEFORE.get(k) == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, BEFORE.get(k));
            }
        }
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Autowired PackRegistry registry;
    @Autowired PackReloader reloader;
    @Autowired Mnemonics mnemonics;
    @Autowired SutraRegistry sutras;
    @Autowired ConnectorManager connectors;
    @Autowired SamplePolicy samples;
    @Autowired com.ash.drishti.server.security.RoleCatalog roles;
    @Autowired com.ash.drishti.engine.search.SearchProperties search;
    @Autowired com.ash.drishti.graph.ReferenceCatalog references;
    @Autowired com.ash.drishti.rachana.format.Formats formats;
    @Autowired com.ash.drishti.rachana.about.AboutCatalog about;

    /** Writes a small pack: a kind, a mnemonic, a Sutra, and a connector template named {@code <name>-feed}. */
    private static void write(String name, String title, List<String> extend, List<String> kinds, String mnemonic, boolean connector, boolean sample)
            throws IOException {
        Path pack = Files.createDirectories(root.resolve("packs").resolve(name));
        Files.createDirectories(pack.resolve("sutras"));
        Files.writeString(pack.resolve("pack.yaml"), "pack: " + name + "\n" + (sample ? "sample: true\n" : "") + "version: 1.0.0\ntitle: " + title
                + "\nextends: " + extend + "\nkinds: " + kinds + "\nmnemonics:\n  " + mnemonic + ": {kind: " + kinds.get(0) + ", label: " + title + "}\n"
                + (connector ? "connector-templates:\n  " + name + "-feed:\n    plugin: file\n    kinds: " + kinds + "\n    settings:\n      root: "
                + root.resolve("data-" + name) + "\n" : ""));
        if (kinds.contains("widget")) {
            Files.writeString(pack.resolve("sutras/widget.v1.sutra.yaml"), SUTRA);
        }
    }

    private static void remove(String name) throws IOException {
        Path d = root.resolve("packs").resolve(name);
        if (Files.exists(d)) {
            try (Stream<Path> s = Files.walk(d)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private void until(String what, BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("gave up waiting for: " + what + "; packs " + names() + ", problems " + registry.problems());
    }

    private static List<String> names0(PackRegistry r) {
        return r.packs().stream().map(Pack::name).toList();
    }

    private List<String> names() {
        return names0(registry);
    }

    private Pack pack(String name) {
        return registry.packs().stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    @AfterEach
    void clean() throws Exception {
        samples.clear();
        for (String n : List.of("beta", "gamma", "delta", "sam", "solo")) {
            remove(n);
        }
        until("the test packs to be gone", () -> names().equals(List.of("alpha")));
    }

    @Test
    void aNewPackFolderIsLoadedEditedAndRemovedWithoutARestart() throws Exception {
        assertThat(names()).containsExactly("alpha");
        write("beta", "Beta", List.of(), List.of("widget"), "WGT", true, false);                // added
        until("beta to be loaded", () -> pack("beta") != null);
        assertThat(mnemonics.of("WGT")).isPresent();
        assertThat(mnemonics.of("ALP")).isPresent();                                             // what was there stays
        until("beta's Sutra", () -> sutras.latest("widget-view").isPresent());
        assertThat(connectors.files().exists("beta-feed")).as("the template became a connector file").isTrue();
        until("beta to be remembered for the next start", () -> {
            try {
                return Files.readString(root.resolve("added.yaml")).contains("beta");
            } catch (IOException e) {
                return false;
            }
        });
        assertThat(registry.problems()).isEmpty();

        write("beta", "Beta two", List.of(), List.of("widget"), "WGT", true, false);            // modified
        until("the new title", () -> pack("beta").title().equals("Beta two"));
        write("beta", "Beta three", List.of(), List.of("widget"), "WGX", true, false);          // a mnemonic renamed
        until("WGX", () -> mnemonics.of("WGX").isPresent());
        assertThat(mnemonics.of("WGT")).isEmpty();

        Path yaml = root.resolve("packs/beta/pack.yaml");                                        // a bad edit keeps the last good version
        Files.writeString(yaml, "pack: beta\nkinds: [widget\n");
        until("the problem to be reported", () -> registry.problems().containsKey("beta"));
        assertThat(pack("beta").title()).isEqualTo("Beta three");
        assertThat(mnemonics.of("WGX")).isPresent();
        write("beta", "Beta four", List.of(), List.of("widget"), "WGX", true, false);           // fixed
        until("the fix", () -> pack("beta").title().equals("Beta four") && registry.problems().isEmpty());

        remove("beta");                                                                          // deleted
        until("beta to be unloaded", () -> pack("beta") == null);
        assertThat(mnemonics.of("WGX")).isEmpty();
        assertThat(registry.removedOwner("widget")).isEqualTo("beta");                           // open views say "pack removed"
        assertThat(connectors.suppressed()).contains("beta-feed");                               // its connector stops (its file stays)
        until("beta to be forgotten", () -> {
            try {
                return !Files.readString(root.resolve("added.yaml")).contains("beta");
            } catch (IOException e) {
                return false;
            }
        });
        assertThat(sutras.latest("widget-view")).isEmpty();
    }

    @Test
    void aPackAnotherPackExtendsIsNeverUnloaded() throws Exception {
        write("gamma", "Gamma", List.of(), List.of("widget"), "GAM", false, false);
        write("delta", "Delta", List.of("gamma"), List.of("gizmo"), "DEL", false, false);
        until("both to be loaded", () -> pack("gamma") != null && pack("delta") != null);

        remove("gamma");
        until("the dependency to be reported", () -> registry.problems().containsKey("gamma"));
        assertThat(registry.problems().get("gamma")).contains("delta");
        assertThat(names()).contains("gamma", "delta");                                          // kept as last loaded
        assertThatThrownBy(() -> reloader.unload("gamma", "test")).hasMessageContaining("extend");

        remove("delta");
        until("both to be gone", () -> pack("gamma") == null && pack("delta") == null);
        assertThat(registry.problems()).isEmpty();
    }

    @Test
    void aBadNewPackDoesNotHoldBackTheGoodOnes() throws Exception {
        write("beta", "Beta", List.of(), List.of("widget"), "WGT", false, false);
        write("gamma", "Gamma", List.of(), List.of("widget"), "GAM", false, false);              // claims the kind beta owns: they clash
        until("one of them loaded and the other reported", () -> registry.problems().size() == 1 && names().size() == 2);
        assertThat(registry.problems().values().iterator().next()).contains("widget");
        assertThat(names()).contains("alpha");                                                   // what was loaded keeps running
    }

    @Test
    void rolesLinksSearchColumnsFormatsAndAboutFollowAPackWithoutARestart() throws Exception {
        Path pack = Files.createDirectories(root.resolve("packs/delta/config"));
        Files.writeString(pack.resolve("formats.yaml"), "formats:\n  gizmo-fmt: {type: number, decimals: 2}\n");
        Files.writeString(pack.resolve("about.yaml"), "about:\n  kinds:\n    gizmo:\n      what: A gizmo.\n");
        String manifest = "pack: delta\nversion: 1.0.0\ntitle: Delta\nextends: []\nkinds: [gizmo]\nmnemonics:\n  GZM: {kind: gizmo, label: Gizmo}\n"
                + "roles:\n  gizmo-reader: {kinds: [gizmo]}\n" + "columns:\n  gizmo: [size, colour]\n" + "graph:\n  fields:\n    gizmoRef: {kind: gizmo, label: Gizmo}\n"
                + "formats: config/formats.yaml\nabout: config/about.yaml\n";
        Files.writeString(root.resolve("packs/delta/pack.yaml"), manifest);
        until("the pack", () -> pack("delta") != null);
        assertThat(registry.problems()).isEmpty();
        assertThat(roles.find("gizmo-reader")).isPresent();                                       // roles
        assertThat(roles.find("gizmo-reader").get().kinds()).containsExactly("gizmo");
        assertThat(search.columnsOf("gizmo")).containsExactly("$.size", "$.colour");              // search columns
        assertThat(references.kindOfField("gizmoRef")).contains("gizmo");                         // link graph
        assertThat(formats.has("gizmo-fmt")).isTrue();                                            // formats
        assertThat(about.sources()).extracting(s -> s.name()).contains("delta");                  // About pages

        Files.writeString(root.resolve("packs/delta/pack.yaml"), manifest.replace("[size, colour]", "[colour]").replace("kinds: [gizmo]\n    gizmoRef", "x")
                .replace("roles:\n  gizmo-reader: {kinds: [gizmo]}", "roles:\n  gizmo-reader: {kinds: [gizmo, widget]}"));        // edited
        until("the new columns and role", () -> search.columnsOf("gizmo").equals(List.of("$.colour"))
                && roles.find("gizmo-reader").map(r -> r.kinds().size()).orElse(0) == 2);

        remove("delta");                                                                          // removed
        until("delta to be gone", () -> pack("delta") == null);
        assertThat(roles.find("gizmo-reader")).isEmpty();
        assertThat(search.columnsOf("gizmo")).isEmpty();
        assertThat(references.kindOfField("gizmoRef")).isEmpty();
        assertThat(formats.has("gizmo-fmt")).isFalse();
        assertThat(about.sources()).extracting(s -> s.name()).doesNotContain("delta");
    }

    @Test
    void settingsThatCannotBeBuiltRefuseTheWholeChangeAndTheLastGoodStaysUntilItIsFixed() throws Exception {
        Files.createDirectories(root.resolve("packs/delta"));
        String good = "pack: delta\nversion: 1.0.0\ntitle: Delta\nextends: []\nkinds: [gizmo]\nmnemonics:\n  GZM: {kind: gizmo, label: Gizmo}\n"
                + "columns:\n  gizmo: [size]\n";
        Files.writeString(root.resolve("packs/delta/pack.yaml"), good);
        until("delta", () -> pack("delta") != null && search.columnsOf("gizmo").equals(List.of("$.size")));
        Files.writeString(root.resolve("packs/delta/pack.yaml"), good.replace("[size]", "[size, colour]") + "graph:\n  badges:\n    gizmo: \"$.size +\"\n");
        until("the change to be refused", () -> registry.problems().containsKey("(all)"));
        assertThat(registry.problems().get("(all)")).contains("nothing was changed");
        assertThat(search.columnsOf("gizmo")).containsExactly("$.size");                         // the columns of the same edit were not swapped either
        assertThat(pack("delta").title()).isEqualTo("Delta");
        Files.writeString(root.resolve("packs/delta/pack.yaml"), good.replace("[size]", "[size, colour]"));        // fixed
        until("the fix", () -> search.columnsOf("gizmo").size() == 2 && !registry.problems().containsKey("(all)"));
    }

    @Test
    void aRefusedPackIsListedForRetryAndLoadsWhenTheConflictIsRemovedWithoutAnyChangeInItsOwnFolder() throws Exception {
        write("beta", "Beta", List.of(), List.of("widget"), "WGT", false, false);
        write("gamma", "Gamma", List.of(), List.of("widget"), "GAM", false, false);              // clashes with beta over the kind
        until("one loaded and one refused", () -> registry.problems().size() == 1 && names().size() == 2);
        String refused = registry.problems().keySet().iterator().next();
        String loaded = refused.equals("beta") ? "gamma" : "beta";
        until("the refused pack to be listed for retry", () -> reloader.retryStatus().containsKey(refused));
        var st = reloader.retryStatus().get(refused);
        assertThat(st.get("lastError").toString()).contains("widget");
        assertThat((Long) st.get("nextRetryInSeconds")).isGreaterThanOrEqualTo(0L);
        until("it to be tried again on the schedule", () -> ((Integer) reloader.retryStatus().get(refused).get("attempt")) >= 3);
        assertThat(names()).doesNotContain(refused);
        remove(loaded);                                                                          // the conflict goes; the refused folder is untouched
        until("the refused pack to load", () -> pack(refused) != null && pack(loaded) == null);
        until("the problem and its retry to clear", () -> registry.problems().isEmpty() && reloader.retryStatus().isEmpty());
    }

    @Test
    void retryNowTriesAtOnceAndIsAudited() throws Exception {
        write("beta", "Beta", List.of(), List.of("widget"), "WGT", false, false);
        write("gamma", "Gamma", List.of(), List.of("widget"), "GAM", false, false);
        until("one refused", () -> registry.problems().size() == 1 && !reloader.retryStatus().isEmpty());
        int before = (Integer) reloader.retryStatus().values().iterator().next().get("attempt");
        reloader.retryNow("test-admin");
        assertThat((Integer) reloader.retryStatus().values().iterator().next().get("attempt")).isGreaterThan(before);
        assertThat(registry.problems()).hasSize(1);                                              // still a real conflict
    }

    @Test
    void sampleModeHiddenUnloadsSamplesAndTheirConnectorsAndVisibleBringsThemBack() throws Exception {
        write("sam", "Sample", List.of(), List.of("widget"), "SAM", true, true);
        until("the sample pack", () -> pack("sam") != null && pack("sam").sample());
        samples.set("hidden");
        reloader.reconcile("test", "hidden", Set.of());
        assertThat(pack("sam")).isNull();
        assertThat(connectors.suppressed()).contains("sam-feed");
        samples.set("visible");
        reloader.reconcile("test", "visible", Set.of());
        assertThat(pack("sam")).isNotNull();
        assertThat(connectors.suppressed()).doesNotContain("sam-feed");
    }

    @Test
    void manyCallersAtOnceReloadOneAtATimeAndEndConsistent() throws Exception {
        write("solo", "Solo", List.of(), List.of("widget"), "SOL", false, false);
        until("solo", () -> pack("solo") != null);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Object> done = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    if (n % 4 == 0) {
                        write("solo", "Solo " + n, List.of(), List.of("widget"), "SOL", false, false);
                    }
                    reloader.reconcile("test", "burst " + n, Set.of("solo"));
                    synchronized (done) {
                        done.add(n);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
        assertThat(done).hasSize(24);
        reloader.reconcile("test", "settle", Set.of("solo"));
        String onDisk = Files.readAllLines(root.resolve("packs/solo/pack.yaml")).stream().filter(l -> l.startsWith("title: ")).findFirst().orElseThrow()
                .substring(7);
        assertThat(pack("solo").title()).isEqualTo(onDisk);                                      // the last write is what runs
        assertThat(names().stream().filter("solo"::equals).count()).isEqualTo(1);                // never twice
    }
}
