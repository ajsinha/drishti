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
package com.ash.drishti.server.kernelapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Operation;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * K3 (KERNEL_AND_MODULES.md section 2.3): operations a module serves from the beans of its own child context, asked in process through
 * the doors of the REST path each answers as. Each row asks the same thing typed (a probe endpoint, inside a request of the person) and over
 * REST, and the answers or refusals are the same: a local call; the module off ({@code 404 DRS-1025}, genomics switched off); the engine
 * off ({@code 404 DRS-1020}, the in-memory lake); a read-only API token on a write ({@code 403 DRS-5002}); the provider's own refusal
 * ({@code 404 DRS-1001}). Then: an operation nobody serves, two of one name, and withdrawal with the module's context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading", "drishti.rachana.hot-reload=false",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db", "drishti.identity.iterations=1000",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.reader.kinds[0]=trade", "drishti.security.roles.reader.raw=true",
        "drishti.modules.genomics.enabled=false", "drishti.engines.memory-lake.enabled=false"})
class OperationsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired TokenVerifier tokens;
    @Autowired OperationRegistry registry;
    @Autowired Kernel kernel;
    @Autowired Probe probe;
    @LocalServerPort int port;

    final HttpClient http = HttpClient.newHttpClient();

    /** The provider's records. */
    public record Say(String said) {
    }

    public record Said(String said, String user, String lens, String asOf) {
    }

    /** The provider, a bean of the fixture module's own child context. */
    public static class FixtureOps {

        @Operation(value = "echo", rest = "GET /api/v1/opfixture/echo")
        public Said echo(Person p, Say s) {
            return new Said(s.said(), p.user(), p.lens(), p.asOf().businessDate() == null ? null : p.asOf().businessDate().toString());
        }

        @Operation(value = "lake", rest = "GET /api/v1/admin/lakes/memory/op-fixture")
        public Said lake(Person p, Say s) {
            return echo(p, s);
        }

        @Operation(value = "write", rest = "POST /api/v1/opfixture/write")
        public Said write(Person p, Say s) {
            return echo(p, s);
        }

        @Operation(value = "missing", rest = "GET /api/v1/opfixture/missing")
        public Said missing(Person p, Say s) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no fixture " + s.said());
        }

        @Operation("plain")
        public Said plain(Person p, Say s) {
            return echo(p, s);
        }
    }

    /** The consumer's view: records of its own. */
    public interface Fixture {
        record Ask(String said) {
        }

        record Answer(String said, String user, String lens, String asOf) {
        }

        @Operation("echo")
        Answer echo(Person p, Ask a);

        @Operation("lake")
        Answer lake(Person p, Ask a);

        @Operation("write")
        Answer write(Person p, Ask a);

        @Operation("missing")
        Answer missing(Person p, Ask a);

        @Operation("plain")
        Answer plain(Person p, Ask a);

        @Operation("nobody")
        Answer nobody(Person p, Ask a);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {
        @Bean
        Probe operationsProbe(Kernel kernel, OperationRegistry registry, ApplicationContext server) {
            return new Probe(kernel, registry, server);
        }
    }

    /**
     * The fixture module, as the module runtime starts one: a child context of the server's with its provider bean, its operations
     * registered from it ({@code ModuleWiring}); the REST paths of the provider; and the probe asking typed.
     */
    @RestController
    static class Probe {
        final Kernel kernel;
        final GenericApplicationContext module;
        final Runnable withdraw;

        Probe(Kernel kernel, OperationRegistry registry, ApplicationContext server) {
            this.kernel = kernel;
            this.module = new GenericApplicationContext(server);
            module.registerBean("fixtureOps", FixtureOps.class);
            module.refresh();
            this.withdraw = registry.register("opfixture", module);
        }

        FixtureOps ops() {
            return module.getBean(FixtureOps.class);
        }

        @GetMapping("/api/v1/opfixture/echo")
        Said echo(@RequestParam String said) {
            return ops().echo(kernel.person(), new Say(said));
        }

        @GetMapping("/api/v1/opfixture/missing")
        Said missing(@RequestParam String said) {
            return ops().missing(kernel.person(), new Say(said));
        }

        @GetMapping("/api/v1/op-probe/{module}/{op}")
        Map<String, Object> typed(@PathVariable String module, @PathVariable String op, @RequestParam String said) {
            Fixture f = kernel.operations().of(module, Fixture.class);
            Person p = kernel.person();
            Fixture.Ask a = new Fixture.Ask(said);
            Map<String, Object> out = new LinkedHashMap<>();
            try {
                out.put("answer", switch (op) {
                    case "echo" -> f.echo(p, a);
                    case "lake" -> f.lake(p, a);
                    case "write" -> f.write(p, a);
                    case "missing" -> f.missing(p, a);
                    case "plain" -> f.plain(p, a);
                    default -> f.nobody(p, a);
                });
            } catch (Kernel.Refused r) {
                out.put("refused", Map.of("status", r.status(), "code", r.code(), "detail", r.getMessage()));
            }
            return out;
        }
    }

    String jwt(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 600);
    }

    HttpResponse<String> send(String method, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode typed(String module, String op, String said, Map<String, String> who) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/op-probe/" + module + "/" + op + "?said=" + said, who, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    /** The REST answer as the probe writes a typed one: {@code {answer}} or {@code {refused: {status, code, detail}}}. */
    JsonNode rest(String method, String path, Map<String, String> who) throws Exception {
        HttpResponse<String> r = send(method, path, who, "POST".equals(method) ? "{\"said\":\"w\"}" : null);
        JsonNode body = r.body().isBlank() ? JSON.nullNode() : JSON.readTree(r.body());
        ObjectNode out = JSON.createObjectNode();
        if (r.statusCode() < 300) {
            out.set("answer", body);
        } else {
            out.set("refused", JSON.createObjectNode().put("status", r.statusCode()).put("code", body.path("code").asText())
                    .put("detail", body.path("detail").asText()));
        }
        return out;
    }

    /** A personal API token of a registered user, read-only. */
    String readOnlyToken(String user) throws Exception {
        String admin = jwt("ops-admin", "admin");
        assertThat(send("POST", "/api/v1/admin/users", Map.of("Authorization", admin),
                "{\"username\":\"" + user + "\",\"roles\":[\"reader\"],\"password\":\"ops-pass-1234\"}").statusCode()).isLessThan(300);
        HttpResponse<String> t = send("POST", "/api/v1/me/tokens", Map.of("Authorization", jwt(user, "reader")), "{\"name\":\"ops\",\"days\":1}");
        assertThat(t.statusCode()).as(t.body()).isLessThan(300);
        return "Bearer " + JSON.readTree(t.body()).path("secret").asText();
    }

    @Test
    void everyGateAnswersTheSameTypedAndOverRest() throws Exception {
        Map<String, String> reader = Map.of("Authorization", jwt("ops-reader", "reader"), "X-Drishti-As-Of", "2026-11-02");
        Map<String, String> admin = Map.of("Authorization", jwt("ops-admin", "admin"));

        // a local call: the consumer's records converted to the provider's and back; the person, lens and date box carried
        JsonNode local = typed("opfixture", "echo", "hello", reader);
        assertThat(local).isEqualTo(rest("GET", "/api/v1/opfixture/echo?said=hello", reader));
        assertThat(local.path("answer").path("user").asText()).isEqualTo("ops-reader");
        assertThat(local.path("answer").path("asOf").asText()).isEqualTo("2026-11-02");

        // the module off: its API and its operations refuse alike
        JsonNode off = typed("genomics", "echo", "x", reader);
        assertThat(off).isEqualTo(rest("GET", "/api/v1/genomics/anything", reader));
        assertThat(off.path("refused").path("code").asText()).isEqualTo("DRS-1025");
        assertThat(kernel.operations().available("genomics")).isFalse();
        assertThat(kernel.operations().available("opfixture")).isTrue();

        // the engine off: the in-memory lake's path refuses before anything runs, typed too
        JsonNode lake = typed("opfixture", "lake", "x", admin);
        assertThat(lake).isEqualTo(rest("GET", "/api/v1/admin/lakes/memory/op-fixture", admin));
        assertThat(lake.path("refused").path("code").asText()).isEqualTo("DRS-1020");

        // a read-only API token on a write: refused by the token's scopes both ways, in the same words
        Map<String, String> token = Map.of("Authorization", readOnlyToken("ops-token"));
        JsonNode write = typed("opfixture", "write", "w", token);
        assertThat(write).isEqualTo(rest("POST", "/api/v1/opfixture/write", token));
        assertThat(write.path("refused").path("code").asText()).isEqualTo("DRS-5002");
        // ...and an operation with no REST path is never an API token's
        assertThat(typed("opfixture", "plain", "p", token).path("refused").path("code").asText()).isEqualTo("DRS-5002");
        assertThat(typed("opfixture", "plain", "p", reader).path("answer").path("said").asText()).isEqualTo("p");

        // the provider's own refusal: mapped as the REST path maps it
        JsonNode missing = typed("opfixture", "missing", "m1", reader);
        assertThat(missing).isEqualTo(rest("GET", "/api/v1/opfixture/missing?said=m1", reader));
        assertThat(missing.path("refused").path("code").asText()).isEqualTo("DRS-1001");

        // an operation the module does not serve
        JsonNode nobody = typed("opfixture", "nobody", "n", reader);
        assertThat(nobody.path("refused").path("status").asInt()).isEqualTo(404);
        assertThat(nobody.path("refused").path("code").asText()).isEqualTo("DRS-1020");
    }

    /** A module serving two operations of one name does not start: both are named. */
    public static class Twice {
        @Operation("same")
        public Said a(Person p, Say s) {
            return null;
        }
    }

    public static class Again {
        @Operation("same")
        public Said b(Person p, Say s) {
            return null;
        }
    }

    @Test
    void twoOfOneNameStopTheStartAndAWithdrawnModuleServesNothing() throws Exception {
        assertThatThrownBy(() -> registry.register("clash", new Twice(), new Again()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("same twice").hasMessageContaining("Twice.a").hasMessageContaining("Again.b");
        assertThat(registry.serves("clash")).isFalse();
        assertThatThrownBy(() -> kernel.operations().of("x", Runnable.class)).isInstanceOf(IllegalArgumentException.class);

        // the module's context withdrawn (its unload's first step): its operations are gone, and come back with it
        probe.withdraw.run();
        try {
            Map<String, String> reader = Map.of("Authorization", jwt("ops-reader", "reader"));
            assertThat(kernel.operations().available("opfixture")).isFalse();
            assertThat(typed("opfixture", "echo", "gone", reader).path("refused").path("code").asText()).isEqualTo("DRS-1020");
        } finally {
            registry.register("opfixture", probe.module);
        }
        assertThat(kernel.operations().available("opfixture")).isTrue();
    }
}
