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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.lifecycle.ComponentHealth;
import com.ash.drishti.api.lifecycle.ComponentSuspended;
import com.ash.drishti.api.lifecycle.LifecycleEvent;
import com.ash.drishti.api.lifecycle.LifecycleRefused;
import com.ash.drishti.api.lifecycle.LifecycleState;
import com.ash.drishti.common.JsonCodec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * A named connector on the kernel's lifecycle (docs/architecture/LIFECYCLE.md section 4): init and start as one apply, suspend (a stand-in
 * answers 503 DRS-1037, the plugin stops pulling, the instance kept), resume (the same instance back, nothing restarted), shutdown (nothing
 * left registered), every transition heard, illegal ones refused.
 */
class ConnectorLifecycleTest {

    /** Counts its life; answers one entity; pulls until suspended. */
    public static final class Puller implements SourcePlugin {
        static final List<String> LOG = new CopyOnWriteArrayList<>();
        volatile boolean pulling;

        public PluginManifest manifest() {
            return new PluginManifest("puller", "t", Set.of("tick"), SourceCapabilities.FETCH_ONLY);
        }

        public void start(SourceContext c) {
            pulling = true;
            LOG.add("start");
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public void suspend() {
            pulling = false;
            LOG.add("suspend");
        }

        @Override
        public void resume() {
            pulling = true;
            LOG.add("resume");
        }

        @Override
        public String health() {
            return pulling ? "UP" : "UP (paused)";
        }

        public void close() {
            LOG.add("close");
        }
    }

    private static SourceRegistry registry() {
        Puller.LOG.clear();
        var props = new SourcesProperties(Map.of(), null, Map.of("puller", new SourcesProperties.PluginSettings(false, Map.of())), Duration.ofSeconds(1),
                null, Map.of(), null, "off", null, Duration.ZERO);
        return new SourceRegistry(List.of(new Puller()), props, new JsonCodec());
    }

    private static SourcesProperties.ConnectorSettings on() {
        return new SourcesProperties.ConnectorSettings("puller", true, List.of("tick"), Map.of());
    }

    @Test
    void suspendKeepsTheInstanceAndResumeGivesTheSameOneBack() throws Exception {
        SourceRegistry r = registry();
        List<LifecycleEvent> events = new CopyOnWriteArrayList<>();
        r.onLifecycle(events::add);
        assertThat(r.apply("ticks", on()).state()).isEqualTo("RUNNING");
        assertThat(r.lifecycleState("ticks")).isEqualTo(LifecycleState.RUNNING);
        SourcePlugin real = r.plugin("ticks").orElseThrow();

        assertThat(r.suspend("ticks", "ops").state()).isEqualTo("SUSPENDED");
        assertThat(r.lifecycleState("ticks")).isEqualTo(LifecycleState.SUSPENDED);
        SourcePlugin standIn = r.plugin("ticks").orElseThrow();
        assertThat(standIn).isNotSameAs(real);
        assertThat(standIn.manifest().name()).isEqualTo("ticks");
        assertThatThrownBy(() -> standIn.fetch(EntityRef.of("tick", "T-1"))).isInstanceOf(ComponentSuspended.class)
                .hasMessageContaining("connector ticks is suspended").hasMessageContaining("by ops");
        assertThat(r.connector("ticks")).hasValueSatisfying(p -> assertThat(p).isSameAs(standIn));
        assertThat(r.health("ticks").status()).isEqualTo(ComponentHealth.Status.SUSPENDED);
        assertThat(r.stats("ticks").total().refusals()).isEqualTo(1);
        assertThat(Puller.LOG).containsExactly("start", "suspend");

        assertThat(r.resume("ticks", "ops").state()).isEqualTo("RUNNING");
        assertThat(r.plugin("ticks").orElseThrow()).as("the same instance: nothing restarted or reloaded").isSameAs(real);
        assertThat(r.connector("ticks").orElseThrow()).isSameAs(real);
        assertThat(Puller.LOG).containsExactly("start", "suspend", "resume");
        assertThat(r.health("ticks").status()).isEqualTo(ComponentHealth.Status.UP);
        assertThat(events).extracting(LifecycleEvent::auditAction).containsExactly("connector-init", "connector-start", "connector-suspend", "connector-resume");
    }

    @Test
    void illegalTransitionsAreRefusedAndChangeNothing() {
        SourceRegistry r = registry();
        assertThatThrownBy(() -> r.suspend("nothing", "ops")).isInstanceOf(LifecycleRefused.class).hasMessageContaining("not running here");
        r.apply("ticks", on());
        assertThatThrownBy(() -> r.resume("ticks", "ops")).isInstanceOf(LifecycleRefused.class).hasMessageContaining("is running");
        assertThatThrownBy(() -> r.start("ticks", "ops")).isInstanceOf(LifecycleRefused.class);
        assertThat(r.lifecycleState("ticks")).isEqualTo(LifecycleState.RUNNING);
        r.suspend("ticks", "ops");
        assertThatThrownBy(() -> r.suspend("ticks", "ops")).isInstanceOf(LifecycleRefused.class).hasMessageContaining("is suspended");
        assertThat(r.lifecycleState("ticks")).isEqualTo(LifecycleState.SUSPENDED);
    }

    @Test
    void shutdownLeavesNothingRegisteredAndStartBringsItBack() {
        SourceRegistry r = registry();
        r.apply("ticks", on());
        r.suspend("ticks", "ops");
        r.shutdown("ticks", "ops");
        assertThat(r.plugin("ticks")).isEmpty();
        assertThat(r.connector("ticks")).isEmpty();
        assertThat(r.plugins()).isEmpty();
        assertThat(r.lifecycleState("ticks")).isEqualTo(LifecycleState.NEW);       // no machine: the instance is gone
        assertThat(r.connectorStatuses().get("ticks").state()).isEqualTo("SHUTDOWN");
        assertThat(Puller.LOG).containsExactly("start", "suspend", "close");      // the held instance is closed, once
        r.shutdown("ticks", "ops");                                               // harmless twice
        assertThat(Puller.LOG).containsExactly("start", "suspend", "close");
        assertThat(r.start("ticks", "ops").state()).isEqualTo("RUNNING");          // its settings were kept
        assertThat(Puller.LOG).containsExactly("start", "suspend", "close", "start");
    }

    @Test
    void initValidatesWithoutStartingAndStartThenStartsIt() {
        SourceRegistry r = registry();
        r.apply("ticks", on());
        r.shutdown("ticks", "ops");
        Puller.LOG.clear();
        assertThat(r.init("ticks", "ops")).isEqualTo(LifecycleState.INITIALIZED);
        assertThat(Puller.LOG).as("init does no I/O: nothing started").isEmpty();
        assertThat(r.plugin("ticks")).isEmpty();
        assertThat(r.start("ticks", "ops").state()).isEqualTo("RUNNING");
        assertThat(Puller.LOG).containsExactly("start");
    }
}
