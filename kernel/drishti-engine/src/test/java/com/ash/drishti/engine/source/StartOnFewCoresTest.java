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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The connectors start on a machine with one core. A connector's start loads classes and initialises holders; a virtual thread doing
 * either pins its carrier, and when the start-up fan-out ran on virtual threads a few-core machine stopped at start (every carrier
 * pinned by a start waiting for a virtual thread that could never run). Here a forked JVM with one carrier
 * ({@code -Djdk.virtualThreadScheduler.parallelism=1}) starts eight connectors whose start initialises one holder, and the holder's
 * initialisation waits for a virtual thread: the registry must be up within the bound.
 */
class StartOnFewCoresTest {

    /** The virtual thread's task, outside the holder (a lambda of the holder would wait for the holder's own initialisation). */
    static final Runnable NOTHING = () -> { };

    /** Initialised by the first start; the others wait on its class-initialisation monitor, as on StorageProviders' holder. */
    static final class Holder {
        static final int VALUE = init();

        private static int init() {
            try {
                Thread.sleep(100);                                   // the others reach the monitor meanwhile
                Thread v = Thread.ofVirtual().start(NOTHING);        // a virtual thread needs a free carrier to run
                v.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }
    }

    /** One connector: its start reads the holder. */
    public static final class Starts implements SourcePlugin {
        private final String name;

        public Starts(String name) {
            this.name = name;
        }

        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("k-" + name), SourceCapabilities.FETCH_ONLY);
        }

        public void start(SourceContext c) {
            if (Holder.VALUE != 1) {
                throw new IllegalStateException("holder");
            }
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        public void close() {}
    }

    /** The forked JVM: starts the registry and prints how many connectors started. */
    public static void main(String[] args) {
        List<SourcePlugin> plugins = new ArrayList<>();
        Map<String, SourcesProperties.PluginSettings> plain = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            plugins.add(new Starts("p" + i));
            plain.put("p" + i, new SourcesProperties.PluginSettings(true, Map.of()));
        }
        var props = new SourcesProperties(Map.of(), null, plain, Duration.ofSeconds(1), null, Map.of());
        try (SourceRegistry r = new SourceRegistry(plugins, props, new JsonCodec())) {
            System.out.println("STARTED " + plugins.stream().filter(p -> r.plugin(p.manifest().name()).isPresent()).count());
        }
        System.exit(0);
    }

    @Test
    void theConnectorsStartWithOneCarrierThread(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                "-Ddrishti.home=" + System.getProperty("drishti.home"), "-cp", System.getProperty("java.class.path"), StartOnFewCoresTest.class.getName())
                .redirectErrorStream(true).redirectOutput(dir.resolve("out.txt").toFile()).start();
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        if (!done) {                                                 // the threads of the hung JVM, for the failure message
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
            new ProcessBuilder(jcmd.toString(), Long.toString(p.pid()), "Thread.dump_to_file", dir.resolve("threads.txt").toString())
                    .redirectErrorStream(true).redirectOutput(dir.resolve("jcmd.txt").toFile()).start().waitFor(30, TimeUnit.SECONDS);
            p.destroyForcibly().waitFor();
        }
        String out = Files.readString(dir.resolve("out.txt"))
                + (Files.exists(dir.resolve("threads.txt")) ? Files.readString(dir.resolve("threads.txt")) : "");
        assertThat(done).as("the registry started within 60 s with one carrier thread (a hang is the start-up deadlock)\n" + out).isTrue();
        assertThat(out).contains("STARTED 8");
    }
}
