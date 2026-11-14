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
package com.ash.drishti.server.bindings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.kernel.api.data.BindingState;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.packs.bindings.BindingsFile;
import com.ash.drishti.packs.bindings.DataNeeds;
import com.ash.drishti.server.connectors.ConnectorManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * The kernel's bindings on real connector files and a registry of fake plugins: a binding proposed, refused or approved by a second
 * administrator (four eyes, audited), applied to the connectors and the router without a restart, and the function an unbound required
 * need switched off coming back on.
 */
class BindingServiceTest {

    /** A plugin that could serve any kind and holds only trades: a connector serves what its plugin holds (a binding names it for more). */
    public static final class Fake implements SourcePlugin {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("fake", "t", Set.of(), SourceCapabilities.FETCH_ONLY);
        }

        @Override
        public boolean holdsNone(String kind) {
            return !"trade".equals(kind);
        }

        @Override
        public void start(SourceContext c) {
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public void close() {
        }
    }

    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> audited = new ArrayList<>();

    @AfterEach
    void close() {
        exec.shutdownNow();
    }

    private BindingService build(Path dir, SourceRouter[] routerOut) throws Exception {
        Path pack = Files.createDirectories(dir.resolve("packs/risk"));
        Files.writeString(pack.resolve("pack.yaml"), "pack: risk\nkinds: [trade, scenario-set]\n");
        ConnectorFiles files = new ConnectorFiles(dir.resolve("connectors"));
        files.write("risk-db", "plugin: fake\n");
        files.write("risk-two", "plugin: fake\n");
        MockEnvironment env = new MockEnvironment().withProperty("drishti.packs.dir", dir.resolve("packs").toString())
                .withProperty("drishti.packs.loaded", "risk");
        PackRegistry packs = new PackRegistry(env);
        var startup = new java.util.LinkedHashMap<String, SourcesProperties.ConnectorSettings>();
        files.readAll(new java.util.HashMap<>()).forEach((n, d) -> startup.put(n, new SourcesProperties.ConnectorSettings(d.plugin(), true, d.kinds(), d.settings())));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(1), null, startup, dir.resolve("connectors").toString(), "poll",
                Duration.ofMillis(50), Duration.ZERO, true);
        SourcePlugin proto = new Fake();
        SourceRegistry registry = new SourceRegistry(List.of(proto), props, new JsonCodec());
        SourceRouter router = new SourceRouter(registry, props, exec);
        routerOut[0] = router;
        ConnectorManager manager = new ConnectorManager(files, registry, props, packs, env, new SettingCatalogue(List.of(proto)),
                new ConnectionProbe(List.of(proto), new JsonCodec()), 2, 5);
        List<DataNeeds.Need> needs = new ArrayList<>();
        Map<String, String> computed = new java.util.HashMap<>();
        needs.add(new DataNeeds.Need("banking", "scenario-set", ">=1.0 <2", Set.of("read"), true, List.of(), null, null, true, List.of("VRES")));
        AuditLog audit = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
                audited.add(actor + " " + action + " " + subject);
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        BindingService s = new BindingService(BindingsFile.besideConnectors(dir.resolve("connectors")), manager, router, packs,
                new DataNeeds(needs, computed, List.of()), m -> true, () -> false, audit, dir.resolve("data/bindings/proposals.json"), Clock.systemUTC());
        s.refresh();
        return s;
    }

    @Test
    void aSecondAdministratorApprovesAndTheBindingAppliesWithoutARestart(@TempDir Path dir) throws Exception {
        SourceRouter[] router = new SourceRouter[1];
        BindingService s = build(dir, router);
        assertThat(s.offReason("VRES")).as("scenario-set is required and nothing serves it").get().asString().contains("scenario-set");
        assertThat(s.offReason("TRD")).isEmpty();

        BindingService.Proposal p = s.propose("scenario-set", Map.of("read", List.of("risk-two")), "the bank's scenario sets", "alice");
        assertThat(s.offReason("VRES")).as("nothing changes before a decision").isPresent();
        assertThatThrownBy(() -> s.decide(p.id(), true, "", "alice")).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.FOUR_EYES));

        s.decide(p.id(), true, "checked", "bob");
        assertThat(router[0].bound()).containsEntry("scenario-set", List.of("risk-two"));
        assertThat(router[0].serving("scenario-set")).extracting(SourceRouter.Serving::name).containsExactly("risk-two");
        assertThat(s.resolve(false).kinds().get("scenario-set").state()).isEqualTo(BindingState.BOUND);
        assertThat(s.offReason("VRES")).as("its function is back on").isEmpty();
        assertThat(Files.readString(dir.resolve("bindings.yaml"))).contains("scenario-set").contains("risk-two");
        assertThat(audited).containsExactly("alice binding-proposed binding/scenario-set", "bob binding-approved binding/scenario-set");
        assertThat(s.status()).containsKeys("kinds", "counts", "off", "proposals");
    }

    @Test
    void refusalsSayWhy(@TempDir Path dir) throws Exception {
        BindingService s = build(dir, new SourceRouter[1]);
        assertThatThrownBy(() -> s.propose("scenario-set", Map.of("read", List.of("nowhere")), "r", "alice")).hasMessageContaining("no connector file");
        assertThatThrownBy(() -> s.propose("scenario-set", Map.of("read", List.of("risk-two")), " ", "alice")).hasMessageContaining("say why");
        assertThatThrownBy(() -> s.propose("scenario-set", Map.of("read", List.of("risk-two"), "note", "token=abc"), "r", "alice"))
                .hasMessageContaining("credential");
        BindingService.Proposal p = s.propose("scenario-set", Map.of("read", List.of("risk-two")), "r", "alice");
        assertThatThrownBy(() -> s.propose("scenario-set", Map.of("read", List.of("risk-db")), "r", "carol")).hasMessageContaining("already waits");
        assertThatThrownBy(() -> s.withdraw(p.id(), "bob")).hasMessageContaining("only its proposer");
        assertThat(s.withdraw(p.id(), "alice").state()).isEqualTo("withdrawn");
        assertThat(Files.exists(dir.resolve("bindings.yaml"))).isFalse();
    }
}
