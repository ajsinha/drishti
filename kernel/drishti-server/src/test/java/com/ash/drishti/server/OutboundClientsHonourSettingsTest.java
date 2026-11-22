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

import com.ash.drishti.api.DrishtiTools;
import com.ash.drishti.server.collab.bridge.BridgeClient;
import com.ash.drishti.server.registry.PackRegistryClient;
import com.ash.drishti.server.registry.RegistryProperties;
import com.ash.tools.net.SiteProxy;
import com.ash.tools.testkit.FakeProxy;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every outbound HTTP client of the server honours the site's proxy and TLS settings: the clients that were built by hand (the pack registry, the
 * report delivery, the OIDC key fetch, the bridge, the Java-library repository, the MinIO helper) now start in {@code DrishtiTools.httpBuilder}.
 * A fake proxy and a stub server on local ports, no network.
 */
class OutboundClientsHonourSettingsTest {

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private static HttpClient clientOf(Object holder) throws Exception {
        for (Field f : holder.getClass().getDeclaredFields()) {
            if (HttpClient.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return (HttpClient) f.get(holder);
            }
        }
        throw new AssertionError("no HttpClient field in " + holder.getClass());
    }

    @Test
    void theOneBuilderSendsThroughTheSiteProxy() throws Exception {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ex -> {
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("ok".getBytes());
            ex.close();
        });
        stub.start();
        try (FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            HttpClient c = DrishtiTools.httpBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/x")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.body()).isEqualTo("ok");
            assertThat(proxy.forwarded()).as("the call went through the site proxy").isEqualTo(1);
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void theOneBuilderGivesTheClientTheTlsMaterial() throws Exception {
        var material = DrishtiTools.tlsMaterial(Map.of(), "https://example.test", "the URL");
        HttpClient c = DrishtiTools.httpBuilder(null, material).build();
        assertThat(c.sslContext()).as("the settings' TLS context is the client's").isSameAs(material.sslContext());
        assertThat(c.proxy()).as("a client for the cluster's own peers takes no proxy").isEmpty();
    }

    @Test
    void theBridgeAndTheRegistryClientsUseTheSiteProxy(@TempDir Path dir) throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            URI target = URI.create("https://hooks.example.test/x");
            for (Object holder : new Object[] {new BridgeClient(Duration.ofSeconds(2)),
                    new PackRegistryClient(new RegistryProperties("https://registry.example.test", Map.of(), null, null, null), dir)}) {
                HttpClient c = clientOf(holder);
                assertThat(c.proxy()).as(holder.getClass().getSimpleName()).isPresent();
                assertThat(c.proxy().get().select(target)).as(holder.getClass().getSimpleName()).anyMatch(p -> p.address() != null
                        && ((InetSocketAddress) p.address()).getPort() == proxy.port());
            }
        }
    }
}
