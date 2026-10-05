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
package com.ash.drishti.server.collab.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.FileOutboxStore;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which bridge is usable and why not, the allow-list, bad configuration refused at start, the routing table, and the admin listing. */
class BridgeRegistryTest {

    private static CollabProperties props(List<String> allow, CollabProperties.Webhook... hooks) {
        return new CollabProperties(true, null, null, "https://drishti.example", null, null, null, null, null, null, null, null, null, null, null, null, null,
                new CollabProperties.Bridges(true, null, allow, null, Duration.ofSeconds(5), null, List.of(hooks)), null);
    }

    private static CollabProperties.Webhook hook(String name, String format, String urlEnv, String secretEnv, CollabProperties.Route... routes) {
        return new CollabProperties.Webhook(name, format, urlEnv, secretEnv, List.of(routes));
    }

    private static final CollabProperties.Route ALL = new CollabProperties.Route(List.of(), List.of(), List.of("share", "comment", "mention"));

    @Test
    void aBridgeIsUsableWhenItsUrlIsInTheEnvironmentAndUnderAnAllowedPrefix() {
        BridgeRegistry r = new BridgeRegistry(props(List.of("https://hooks.example/"), hook("desk", "teams", "U", "", ALL)),
                Map.of("U", "https://hooks.example/abc/def?sig=zzz")::get);
        BridgeRegistry.Bridge b = r.find("desk").orElseThrow();
        assertThat(b.usable()).isTrue();
        assertThat(b.host()).isEqualTo("hooks.example");
        assertThat(b.toString()).doesNotContain("abc", "zzz");
    }

    @Test
    void aBridgeThatCannotPostSaysWhyWithoutRevealingTheValue() {
        Map<String, String> env = Map.of("OUT", "https://other.example/x", "FTP", "ftp://hooks.example/x", "JSONURL", "https://hooks.example/j");
        BridgeRegistry r = new BridgeRegistry(props(List.of("https://hooks.example/"),
                hook("unset", "slack", "MISSING", "", ALL), hook("blank", "slack", "", "", ALL), hook("outside", "slack", "OUT", "", ALL),
                hook("scheme", "slack", "FTP", "", ALL), hook("nosecret", "json", "JSONURL", "", ALL), hook("nosecretvalue", "json", "JSONURL", "NOPE", ALL)),
                env::get);
        assertThat(r.find("unset").orElseThrow().status()).isEqualTo("unconfigured: environment variable MISSING is not set");
        assertThat(r.find("blank").orElseThrow().status()).isEqualTo("unconfigured: url-env is not set");
        assertThat(r.find("outside").orElseThrow().status()).contains("blocked").contains("drishti.collab.bridges.allow").doesNotContain("other.example");
        assertThat(r.find("scheme").orElseThrow().status()).contains("not an http(s) URL");
        assertThat(r.find("nosecret").orElseThrow().status()).isEqualTo("unconfigured: a json bridge needs secret-env");
        assertThat(r.find("nosecretvalue").orElseThrow().status()).isEqualTo("unconfigured: environment variable NOPE is not set");
        assertThat(r.all()).noneMatch(BridgeRegistry.Bridge::usable);
    }

    @Test
    void withNoAllowListNoBridgeCanPost() {
        BridgeRegistry r = new BridgeRegistry(props(List.of(), hook("desk", "teams", "U", "", ALL)), Map.of("U", "https://hooks.example/x")::get);
        assertThat(r.find("desk").orElseThrow().usable()).isFalse();
    }

    @Test
    void badConfigurationStopsTheServerAtStart() {
        assertThatThrownBy(() -> new BridgeRegistry(props(List.of(), hook("bad name", "teams", "U", "", ALL)), k -> null)).hasMessageContaining("not a bridge name");
        assertThatThrownBy(() -> new BridgeRegistry(props(List.of(), hook("a", "teams", "U", ""), hook("a", "slack", "U", "")), k -> null))
                .hasMessageContaining("used twice");
        assertThatThrownBy(() -> new BridgeRegistry(props(List.of(), hook("a", "irc", "U", "")), k -> null)).hasMessageContaining("json, teams or slack");
        assertThatThrownBy(() -> new BridgeRegistry(props(List.of(), hook("a", "teams", "U", "", new CollabProperties.Route(List.of(), List.of(), List.of("reply")))),
                k -> null)).hasMessageContaining("event 'reply'");
    }

    @Test
    void routingMatchesEventAndPackAndKindAndAnUnownedKindOnlyMatchesRoutesWithoutAPack() {
        CollabProperties.Route risk = new CollabProperties.Route(List.of("market-risk"), List.of(), List.of("share"));
        CollabProperties.Route kinds = new CollabProperties.Route(List.of(), List.of("trade", "limit"), List.of("comment"));
        BridgeRegistry r = new BridgeRegistry(props(List.of(), hook("a", "teams", "U", "", risk), hook("b", "slack", "U", "", kinds), hook("c", "teams", "U", "", ALL)),
                k -> null);
        assertThat(r.matching("share", "trade", "market-risk")).extracting(BridgeRegistry.Bridge::name).containsExactly("a", "c");
        assertThat(r.matching("share", "trade", "genomics")).extracting(BridgeRegistry.Bridge::name).containsExactly("c");
        assertThat(r.matching("share", "trade", null)).extracting(BridgeRegistry.Bridge::name).containsExactly("c");
        assertThat(r.matching("comment", "limit", "x")).extracting(BridgeRegistry.Bridge::name).containsExactly("b", "c");
        assertThat(r.matching("comment", "sample", "x")).extracting(BridgeRegistry.Bridge::name).containsExactly("c");
        assertThat(r.matching("mention", "trade", "market-risk")).extracting(BridgeRegistry.Bridge::name).containsExactly("c");
    }

    @Test
    void theAdminListingNeverShowsTheUrlOrTheSecretAndOnlyAdministratorsSeeIt() throws Exception {
        CollabProperties p = props(List.of("https://hooks.example/"), hook("desk", "json", "U", "S", ALL));
        BridgeRegistry r = new BridgeRegistry(p, Map.of("U", "https://hooks.example/services/PATHSECRET", "S", "SIGNSECRET")::get);
        var outbox = new FileOutboxStore(Files.createTempDirectory("bridge-admin"));
        outbox.add(OutboxItem.pending("bridge", "desk", "share", "sh_1", java.time.Instant.now()));
        outbox.add(OutboxItem.pending("email", "ravi", "share", "sh_1", java.time.Instant.now()));
        Entitlements ent = mock(Entitlements.class);
        BridgeAdminController c = new BridgeAdminController(r, null, outbox, ent, p);
        Map<String, Object> out = c.list(new Principal("root", List.of("admin")));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(out);
        assertThat(json).contains("\"name\":\"desk\"", "\"host\":\"hooks.example\"", "\"usable\":true", "\"outbox\":{\"pending\":1}", "\"renderAs\":\"viewer\"")
                .doesNotContain("PATHSECRET", "SIGNSECRET", "services");
        org.mockito.Mockito.verify(ent).requireAdmin(new Principal("root", List.of("admin")));
    }
}
