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
package com.ash.drishti.server.flight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.compute.arrow.ArrowMemory;
import com.ash.drishti.flight.FlightRequest;
import com.ash.drishti.flight.FlightService;
import com.ash.drishti.identity.ApiTokenStore;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.testkit.TlsFixture;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Arrow Flight in the server (docs/guides/FLIGHT_GUIDE.md): the engine on, TLS from a throw-away PKI, a person's API token with
 * {@code data:read}. A kind is read as the person (a masked column left out, the audit line {@code data.flight}), a token without the
 * scope and a domain it does not open are refused with their codes, an application acts on behalf of a person, and Admin → Data out lists
 * the read.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade", "drishti.security.redact[0]=dv01",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db",
        "drishti.engines.flight.enabled=true", "drishti.engines.flight.port=0", "drishti.engines.flight.bind=127.0.0.1",
        "drishti.engines.flight.advertise=127.0.0.1"})
class FlightServerTest {

    static final TlsFixture TLS = new TlsFixture();

    @DynamicPropertySource
    static void tls(DynamicPropertyRegistry r) {
        r.add("drishti.engines.flight.tls.certificate", () -> TLS.path("server.pem").toString());
        r.add("drishti.engines.flight.tls.private-key", () -> TLS.path("server.key").toString());
    }

    @AfterAll
    static void cleanUp() {
        TLS.delete();
    }

    @Autowired FlightService service;
    @Autowired UserService users;
    @Autowired ApiTokenStore tokens;
    @Autowired AuditLog audit;
    @Autowired DataOutController dataOut;

    String token(String user, Set<String> roles, List<String> scopes) {
        if (users.find(user).isEmpty()) {
            users.create("test", user, new UserService.Profile(user, user + "@example.com", "", roles, true), "Passw0rd!Passw0rd!", false);
        }
        return tokens.create(user, "flight test", 1, scopes).secret();
    }

    FlightClient connect(BufferAllocator a) throws Exception {
        try (InputStream ca = Files.newInputStream(TLS.path("ca.pem"))) {
            return FlightClient.builder(a, Location.forGrpcTls("localhost", service.port())).trustedCertificates(ca).build();
        }
    }

    static byte[] kind(String kind, List<String> paths) {
        var o = JsonNodeFactory.instance.objectNode().put("kind", kind);
        var p = o.putArray("paths");
        paths.forEach(p::add);
        return new FlightRequest(FlightRequest.Form.KIND, o, -1, false, -1).toBytes();
    }

    @Test
    void aPersonReadsAKindAsTheirRolesAllowAndTheReadIsAudited() throws Exception {
        assertThat(service.running()).isTrue();
        String t = token("fiona", Set.of("trades-only"), List.of("data:read"));
        try (BufferAllocator a = ArrowMemory.child("flight-server-test", 0); FlightClient c = connect(a)) {
            CallOption auth = c.authenticateBasicToken("fiona", t).orElseThrow();
            FlightInfo info = c.getInfo(FlightDescriptor.command(kind("trade", List.of("tradeId", "productType"))), auth);
            assertThat(info.getEndpoints()).hasSize(1);
            // the real address, never a loopback one, even when the engine file names one (net real IP)
            assertThat(info.getEndpoints().get(0).getLocations().get(0).getUri().getHost()).isNotIn("127.0.0.1", "localhost", "0.0.0.0");
            long rows = 0;
            try (FlightStream s = c.getStream(info.getEndpoints().get(0).getTicket(), auth)) {
                assertThat(s.getSchema().getFields()).extracting(f -> f.getName()).containsExactly("tradeId", "productType");
                while (s.next()) {
                    rows += s.getRoot().getRowCount();
                }
            }
            assertThat(rows).isPositive();
            assertThatThrownBy(() -> c.getInfo(FlightDescriptor.command(kind("counterparty", List.of())), auth))
                    .isInstanceOf(FlightRuntimeException.class).hasMessageContaining("DRS-5602");
        }
        assertThat(audit.recent(50, "fiona")).anySatisfy(e -> assertThat(e.action()).isEqualTo("data.flight"));
        assertThat(audit.recent(50, "trade")).anySatisfy(e -> assertThat(e.action()).isEqualTo("data.flight"));
    }

    @Test
    void aTokenWithoutDataReadIsRefusedAtTheHandshake() throws Exception {
        String t = token("gina", Set.of("trades-only"), List.of());
        try (BufferAllocator a = ArrowMemory.child("flight-server-test", 0); FlightClient c = connect(a)) {
            assertThatThrownBy(() -> c.authenticateBasicToken("gina", t)).isInstanceOf(FlightRuntimeException.class)
                    .hasMessageContaining("DRS-5602");
        }
    }

    @Test
    void aDomainScopeOpensItsDomainOnly() throws Exception {
        String t = token("hana", Set.of("admin"), List.of("data:read:nothing-here"));
        try (BufferAllocator a = ArrowMemory.child("flight-server-test", 0); FlightClient c = connect(a)) {
            CallOption auth = c.authenticateBasicToken("hana", t).orElseThrow();
            assertThatThrownBy(() -> c.getInfo(FlightDescriptor.command(kind("trade", List.of("tradeId"))), auth))
                    .isInstanceOf(FlightRuntimeException.class).hasMessageContaining("DRS-5602").hasMessageContaining("data:read:");
        }
    }

    @Test
    void adminDataOutListsTheServiceAndItsNodes() {
        var d = dataOut.describe(new com.ash.drishti.kernel.api.security.Principal("root", List.of("admin")));
        assertThat(d).containsKeys("service", "nodes", "live", "recent", "limits", "tokens");
        assertThat(((java.util.Map<?, ?>) d.get("service")).get("running")).isEqualTo(true);
    }
}
