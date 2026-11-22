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
package com.ash.drishti.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.lifecycle.ComponentHealth;
import com.ash.drishti.api.lifecycle.ComponentSuspended;
import com.ash.drishti.api.lifecycle.Lifecycle;
import com.ash.drishti.api.lifecycle.LifecycleRefused;
import com.ash.drishti.api.lifecycle.LifecycleState;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackLifecycles;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.security.Entitlements;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A pack's lifecycle in a running server (docs/architecture/LIFECYCLE.md section 3): the pack that booted is RUNNING; suspended, its kind
 * and mnemonic answer 503 DRS-1037 through the one gate ({@code Entitlements.requireActive}) while everything stays registered; resumed, the
 * same registrations serve again; shut down, nothing of it is registered (registry, kinds, mnemonics, Sutras); started, it is back. Every
 * transition is in the audit log, the health entry names a suspended pack, and the REST shapes answer.
 */
@SpringBootTest(properties = {"drishti.packs.dir=${drishti.test.dir}/pack-lifecycle/packs",
        "drishti.packs.installed-dir=${drishti.test.dir}/pack-lifecycle/installed",
        "drishti.packs.overlay=${drishti.test.dir}/pack-lifecycle/added.yaml", "drishti.packs.settings-dir=${drishti.test.dir}/pack-lifecycle/settings",
        "drishti.sources.connectors-dir=${drishti.test.dir}/pack-lifecycle/connectors",
        "drishti.packs.samples-file=${drishti.test.dir}/pack-lifecycle/samples-mode",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=alpha", "drishti.packs.watch=off",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/packlifecycle-${random.uuid}/identity.db"})
class PackLifecycleServerTest {

    static Path root;
    private static final String SUTRA = """
            rachana: 1.2
            sutra: thing-view
            version: 1
            description: A thing.
            match: { kind: thing, priority: 10 }
            title: { pill: "Thing", id: $.id }
            strip:
              - { label: Id, bind: $.id }
            panels:
              - { id: built, kind: provenance, title: How this view was built }
            """;

    static {
        try {
            root = com.ash.drishti.server.TestRun.dir("pack-lifecycle");
            Path pack = Files.createDirectories(root.resolve("packs/alpha/sutras"));
            Files.writeString(pack.resolve("../pack.yaml").normalize(), "pack: alpha\nversion: 1.0.0\ntitle: Alpha\nkinds: [thing]\nmnemonics:\n"
                    + "  ALP: {kind: thing, label: Alpha}\n");
            Files.writeString(pack.resolve("thing.v1.sutra.yaml"), SUTRA);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired PackRegistry registry;
    @Autowired PackLifecycles packs;
    @Autowired LifecycleService service;
    @Autowired LifecycleController controller;
    @Autowired Entitlements entitlements;
    @Autowired Mnemonics mnemonics;
    @Autowired SutraRegistry sutras;
    @Autowired AuditLog audit;
    @Autowired HealthIndicator lifecycleHealthIndicator;

    private static final com.ash.drishti.kernel.api.security.Principal ADMIN = com.ash.drishti.kernel.api.security.Principal.anonymous("ops");

    @Test
    void suspendResumeShutdownAndStartAgain() {
        assertThat(packs.state("alpha")).isEqualTo(LifecycleState.RUNNING);
        entitlements.requireActive("thing");
        assertThat(mnemonics.of("ALP")).isPresent();
        assertThat(sutras.latest("thing-view")).isPresent();

        // suspend: the gate answers suspended; everything stays registered
        controller.transition("packs", "alpha", "suspend", false, ADMIN);
        assertThat(packs.state("alpha")).isEqualTo(LifecycleState.SUSPENDED);
        assertThatThrownBy(() -> entitlements.requireActive("thing")).isInstanceOf(ComponentSuspended.class)
                .hasMessageContaining("pack alpha is suspended").satisfies(e -> assertThat(((ComponentSuspended) e).errorCode().code()).isEqualTo("DRS-1037"));
        assertThat(registry.packs()).extracting(Pack::name).contains("alpha");
        assertThat(mnemonics.of("ALP")).isPresent();
        assertThat(sutras.latest("thing-view")).isPresent();
        assertThat(service.part(Lifecycle.Type.PACK, "alpha").health().status()).isEqualTo(ComponentHealth.Status.SUSPENDED);
        assertThat(lifecycleHealthIndicator.health().getDetails()).containsKey("pack/alpha");
        long version = registry.version();

        // an illegal transition: refused, nothing changed
        assertThatThrownBy(() -> controller.transition("packs", "alpha", "start", false, ADMIN)).isInstanceOf(LifecycleRefused.class);

        // resume: nothing reloaded
        controller.transition("packs", "alpha", "resume", false, ADMIN);
        entitlements.requireActive("thing");
        assertThat(registry.version()).as("nothing reloaded").isEqualTo(version);
        assertThat(service.part(Lifecycle.Type.PACK, "alpha").stats().total().refusals()).isEqualTo(1);

        // shutdown: nothing of it registered
        controller.transition("packs", "alpha", "shutdown", false, ADMIN);
        assertThat(packs.state("alpha")).isEqualTo(LifecycleState.SHUTDOWN);
        assertThat(registry.packs()).extracting(Pack::name).doesNotContain("alpha");
        assertThat(registry.packs().stream().flatMap(p -> p.kinds().stream())).doesNotContain("thing");
        assertThat(mnemonics.of("ALP")).isEmpty();
        assertThat(sutras.latest("thing-view")).isEmpty();
        controller.transition("packs", "alpha", "shutdown", false, ADMIN);         // harmless twice

        // start: a new instance, everything back
        controller.transition("packs", "alpha", "start", false, ADMIN);
        assertThat(packs.state("alpha")).isEqualTo(LifecycleState.RUNNING);
        assertThat(mnemonics.of("ALP")).isPresent();
        assertThat(sutras.latest("thing-view")).isPresent();
        entitlements.requireActive("thing");

        assertThat(audit.recent(50, "pack/alpha")).extracting(AuditLog.Event::action)
                .contains("pack-suspend", "pack-resume", "pack-shutdown", "pack-start", "pack-start-refused");
        assertThat(controller.one("packs", "alpha", ADMIN)).containsEntry("state", "RUNNING").containsKeys("health", "stats", "since", "by");
        assertThat(controller.graph(ADMIN)).containsKeys("nodes", "edges", "startOrder", "cycle", "missing");
    }
}
