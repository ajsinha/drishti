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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.kernel.api.module.InlineFigures;
import com.ash.drishti.kernel.api.module.KernelCalls;
import com.ash.drishti.kernel.api.services.InlineRequest;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.module.runtime.InlinePivotFigures;
import com.ash.drishti.server.api.LoopbackKernelCalls;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * K2's parity (docs/plans/I7A_BUILD_PLAN.md): for every kernel call the landings make today, the typed in-process call and the loopback
 * REST call ({@link LoopbackKernelCalls}) give the same answer for the same person, both made inside one real request (a probe endpoint
 * of this test). Rows: an administrator, a masked role, a row-ruled role, a role the kind is closed to, a lens, an earlier date box, another
 * language, a read-only API token and a token with the scope that opens the one POST; a refusal is the same status, code and words both
 * ways. The rows run several people at once on shared threads (session isolation). {@link FixedToday}: the same with
 * {@code drishti.business-date.today} fixed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading",
        "drishti.lenses-dir=../../config/lenses", "drishti.rachana.hot-reload=false",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db", "drishti.identity.iterations=1000",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=COUNTERPARTY",
        "drishti.security.roles.full.kinds[0]=trade", "drishti.security.roles.full.raw=true",
        "drishti.security.roles.masked.kinds[0]=trade", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.ruled.kinds[0]=trade", "drishti.security.roles.ruled.raw=true",
        "drishti.security.roles.ruled.rows.trade=CURRENCY = 'EUR'",
        "drishti.security.roles.nothing.kinds[0]=curve",
        // the inline pivot needs a scope here (it is a read-only POST in the shipped settings), so a token's refusal has a row
        "drishti.security.token-read-posts[0]=/api/v1/explain",
        "drishti.security.token-scopes.pivot-inline.description=the pivot of rows a caller holds",
        "drishti.security.token-scopes.pivot-inline.allow[0]=POST /api/v1/pivot/inline"})
class TypedCallParityTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired TokenVerifier tokens;
    @LocalServerPort int port;

    final HttpClient http = HttpClient.newHttpClient();

    /** One person's headers on the probe's request. */
    record Who(String name, Map<String, String> headers) {}

    String jwt(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 600);
    }

    JsonNode send(String method, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + ": " + r.body()).isLessThan(300);
        return r.body().isEmpty() ? JSON.nullNode() : JSON.readTree(r.body());
    }

    /** A personal API token of a registered user, with the scopes given. */
    String apiToken(String user, String role, String... scopes) throws Exception {
        String admin = jwt("parity-admin", "admin");
        send("POST", "/api/v1/admin/users", Map.of("Authorization", admin),
                "{\"username\":\"" + user + "\",\"roles\":[\"" + role + "\"],\"password\":\"parity-pass-12\"}");
        String body = "{\"name\":\"parity\",\"days\":1" + (scopes.length == 0 ? "" : ",\"scopes\":" + JSON.writeValueAsString(scopes)) + "}";
        return "Bearer " + send("POST", "/api/v1/me/tokens", Map.of("Authorization", jwt(user, role)), body).path("secret").asText();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The probe's answer for one call: {@code {typed, rest}}, each the answer or {@code {refused: {status, code, detail}}}. */
    JsonNode probe(Who who, String call, String query) throws Exception {
        return send("GET", "/api/v1/parity-probe/" + call + (query == null ? "" : "?" + query), who.headers(), null);
    }

    /**
     * The two sides of a call, made comparable: what differs between any two asks of one call (the time a search took, the time a record
     * was fetched, the identity of an inline pivot's row set, the engine's timings and each answer's own provenance record) and an absent field read as null are left out.
     */
    static JsonNode clean(JsonNode n) {
        if (n instanceof ObjectNode o) {
            ObjectNode c = o.deepCopy();
            c.remove(List.of("elapsedMs", "fetchedAt"));
            if (c.path("version").asText().startsWith("rows@")) {
                // an inline pivot's row set, the engine's timings of it and the provenance record each answer is given
                c.remove(List.of("version", "computed", "provenance", "provenanceShared"));
            }
            List<String> nulls = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : c.properties()) {
                if (e.getValue().isNull()) {
                    nulls.add(e.getKey());
                }
            }
            c.remove(nulls);
            for (Map.Entry<String, JsonNode> e : c.properties()) {
                e.setValue(clean(e.getValue()));
            }
            return c;
        }
        return n;
    }

    void same(Who who, String call, String query) throws Exception {
        JsonNode a = probe(who, call, query);
        JsonNode typed = clean(a.path("typed"));
        JsonNode rest = clean(a.path("rest"));
        assertThat(typed).as(who.name() + " " + call + " " + query + " differs at " + differences(typed, rest)).isEqualTo(rest);
    }

    /** Where two answers differ: the fields (top level and one below) whose values are not equal. */
    static List<String> differences(JsonNode a, JsonNode b) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> names = new java.util.TreeSet<>();
        a.fieldNames().forEachRemaining(names::add);
        b.fieldNames().forEachRemaining(names::add);
        for (String n : names) {
            if (!a.path(n).equals(b.path(n))) {
                if (a.path(n).isObject() && b.path(n).isObject()) {
                    differences(a.path(n), b.path(n)).forEach(d -> out.add(n + "." + d));
                } else {
                    String x = a.path(n).toString();
                    String y = b.path(n).toString();
                    out.add(n + " " + x.substring(0, Math.min(200, x.length())) + " | " + y.substring(0, Math.min(200, y.length())));
                }
            }
        }
        return out;
    }

    String tradeId;
    String previousDay;

    @BeforeEach
    void someTrade() throws Exception {
        if (tradeId == null) {
            JsonNode s = send("GET", "/api/v1/search?q=" + enc("TRD where CURRENCY = 'EUR' limit 3"), Map.of("Authorization", jwt("p-full", "full")), null);
            tradeId = s.path("rows").get(0).path("ref").path("id").asText();
            previousDay = send("GET", "/api/v1/business-date", Map.of("Authorization", jwt("p-full", "full")), null).path("previous").asText();
        }
    }

    /** Every call the landings make, for one person. */
    List<String[]> calls() {
        return List.of(
                new String[] {"search", "q=" + enc("TRD where CURRENCY = 'EUR' or CURRENCY = 'USD' order by QUANTITY desc limit 20")},
                new String[] {"search", "q=" + enc("TRD " + tradeId)},
                new String[] {"raw", "kind=trade&id=" + enc(tradeId)},
                new String[] {"raw", "kind=trade&id=NO-SUCH-TRADE"},
                new String[] {"series", "kind=trade&id=" + enc(tradeId) + "&path=QUANTITY&days=5"},
                new String[] {"dates", null},
                new String[] {"settings", null},
                new String[] {"live", "q=" + enc("TRD where CURRENCY = 'EUR' limit 40") + "&limit=1000"},
                new String[] {"pivot", null},
                new String[] {"figures", null},
                new String[] {"lens", "lens=trading&block=trading"});
    }

    void allCalls(List<Who> people) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);           // several people at once, on shared threads
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (Who w : people) {
                for (String[] c : calls()) {
                    runs.add(pool.submit(() -> {
                        same(w, c[0], c[1]);
                        return null;
                    }));
                }
            }
            List<String> differ = new ArrayList<>();
            for (Future<?> f : runs) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    String m = String.valueOf(e.getCause().getMessage());
                    differ.add(m.substring(0, Math.max(0, m.indexOf("\nexpected"))));
                }
            }
            assertThat(differ).as("calls whose typed answer is not the loopback's").isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void everyCallAnswersTheSameTypedAndOverTheLoopback() throws Exception {
        String readOnly = apiToken("parity-ro", "full");
        String scoped = apiToken("parity-scoped", "full", "pivot-inline");
        allCalls(List.of(
                new Who("administrator", Map.of("Authorization", jwt("p-admin", "admin"))),
                new Who("full", Map.of("Authorization", jwt("p-full", "full"))),
                new Who("masked", Map.of("Authorization", jwt("p-masked", "masked"))),
                new Who("row-ruled", Map.of("Authorization", jwt("p-ruled", "ruled"))),
                new Who("closed kind", Map.of("Authorization", jwt("p-nothing", "nothing"))),
                new Who("lens", Map.of("Authorization", jwt("p-lens", "full"), "X-Drishti-Lens", "trading")),
                new Who("earlier date box", Map.of("Authorization", jwt("p-earlier", "full"), "X-Drishti-As-Of", previousDay)),
                new Who("language", Map.of("Authorization", jwt("p-fr", "full"), "Accept-Language", "fr-FR,fr;q=0.9")),
                new Who("read-only token", Map.of("Authorization", readOnly)),
                new Who("token with the scope", Map.of("Authorization", scoped))));
    }

    @Test
    void theRowsSayWhatTheyShouldSoParityIsNotOfTwoEmptyAnswers() throws Exception {
        Who full = new Who("full", Map.of("Authorization", jwt("p-full", "full")));
        Who masked = new Who("masked", Map.of("Authorization", jwt("p-masked", "masked")));
        Who ruled = new Who("ruled", Map.of("Authorization", jwt("p-ruled", "ruled")));
        String eurOrUsd = "q=" + enc("TRD where CURRENCY = 'EUR' or CURRENCY = 'USD' order by QUANTITY desc limit 20");
        JsonNode all = probe(full, "search", eurOrUsd).path("typed");
        assertThat(all.path("rows").size()).isPositive();
        assertThat(all.path("rows").findValuesAsText("$.CURRENCY")).contains("USD");
        // the row rule joins the condition: only EUR
        assertThat(probe(ruled, "search", eurOrUsd).path("typed").path("rows").findValuesAsText("$.CURRENCY")).isNotEmpty().containsOnly("EUR");
        // the mask: the raw record's counterparty is not the full person's
        String raw = "kind=trade&id=" + enc(tradeId);
        JsonNode open = probe(full, "raw", raw).path("typed").path("data");
        JsonNode hidden = probe(masked, "raw", raw).path("typed").path("data");
        assertThat(open.path("COUNTERPARTY").asText()).isNotBlank();
        assertThat(hidden.path("COUNTERPARTY")).isNotEqualTo(open.path("COUNTERPARTY"));
        // a closed kind refuses both ways with the same code
        JsonNode closed = probe(new Who("nothing", Map.of("Authorization", jwt("p-nothing", "nothing"))), "search", eurOrUsd);
        assertThat(closed.path("typed").path("refused").path("status").asInt()).isEqualTo(403);
        assertThat(closed.path("typed")).isEqualTo(closed.path("rest"));
        // no source holds it: typed empty, the REST path 404
        assertThat(probe(full, "raw", "kind=trade&id=NO-SUCH-TRADE").path("typed").path("absent").asBoolean()).isTrue();
        // the read-only token is refused the inline pivot both ways, with the token filter's words; the scoped one is answered
        JsonNode ro = probe(new Who("ro", Map.of("Authorization", apiToken("parity-ro2", "full"))), "pivot", null);
        assertThat(ro.path("typed").path("refused").path("code").asText()).isEqualTo("DRS-5002");
        assertThat(ro.path("typed").path("refused").path("detail").asText()).isEqualTo("this token lacks the scope pivot-inline");
        assertThat(ro.path("typed")).isEqualTo(ro.path("rest"));
        assertThat(probe(full, "pivot", null).path("typed").path("cellCount").asInt()).isPositive();
        assertThat(probe(full, "figures", null).path("typed").path("EUR").asDouble()).isEqualTo(4.0);
        // the lens's block is there
        assertThat(probe(full, "lens", "lens=trading&block=trading").path("typed").size()).isPositive();
        // the date box moves the person's date
        JsonNode earlier = probe(new Who("earlier", Map.of("Authorization", jwt("p-full", "full"), "X-Drishti-As-Of", previousDay)), "dates", null);
        assertThat(earlier.path("typed").path("selected").asText()).isEqualTo(previousDay);
        assertThat(earlier.path("typed").path("live").asBoolean()).isFalse();
    }

    @Test
    void aPersonIsMadeByTheKernelOnly() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> RequestPerson.of(new Person() {
            public String user() {
                return "made-up";
            }

            public String display() {
                return "";
            }

            public boolean admin() {
                return true;
            }

            public List<String> roles() {
                return List.of("admin");
            }

            public String lens() {
                return "";
            }

            public com.ash.drishti.api.AsOf asOf() {
                return com.ash.drishti.api.AsOf.LATEST;
            }

            public String key() {
                return "made-up";
            }

            public Person onDate(LocalDate day) {
                return this;
            }
        })).getMessage()).contains("Kernel.person()");
    }

    /** The same calls with the business today fixed ({@code drishti.business-date.today}, as the demo stack runs). */
    @Nested
    @org.springframework.context.annotation.Import(Probes.class)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=trading",
            "drishti.lenses-dir=../../config/lenses", "drishti.rachana.hot-reload=false", "drishti.business-date.today=2026-11-06",
            "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db",
            "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
            "drishti.security.roles.full.kinds[0]=trade", "drishti.security.roles.full.raw=true"})
    class FixedToday {

        @Autowired TokenVerifier fixedTokens;
        @LocalServerPort int fixedPort;

        @Test
        void theFixedTodayIsBothWaysToday() throws Exception {
            tokensAndPort(fixedTokens, fixedPort);
            Who full = new Who("full", Map.of("Authorization", jwt("p-full", "full")));
            JsonNode d = probe(full, "dates", null);
            assertThat(d.path("typed").path("current").asText()).isEqualTo("2026-11-06");
            assertThat(clean(d.path("typed"))).isEqualTo(clean(d.path("rest")));
            assertThat(d.path("typed").path("live").asBoolean()).isTrue();
            JsonNode s = send("GET", "/api/v1/search?q=" + enc("TRD where CURRENCY = 'EUR' limit 3"), full.headers(), null);
            tradeId = s.path("rows").get(0).path("ref").path("id").asText();
            previousDay = send("GET", "/api/v1/business-date", full.headers(), null).path("previous").asText();
            allCalls(List.of(full, new Who("earlier date box", Map.of("Authorization", jwt("p-earlier", "full"), "X-Drishti-As-Of", previousDay)),
                    new Who("language", Map.of("Authorization", jwt("p-fr", "full"), "Accept-Language", "de-DE"))));
        }
    }

    void tokensAndPort(TokenVerifier t, int p) {
        this.tokens = t;
        this.port = p;
    }

    /** The probe: the same call typed and over the loopback, inside one request of the person. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {

        @Bean
        Probe parityProbe(Kernel kernel, LoopbackKernelCalls loopback) {
            return new Probe(kernel, loopback);
        }
    }

    @RestController
    static class Probe {

        private final Kernel kernel;
        private final LoopbackKernelCalls loopback;

        Probe(Kernel kernel, LoopbackKernelCalls loopback) {
            this.kernel = kernel;
            this.loopback = loopback;
        }

        static final List<InlineFigures.Field> FIELDS = List.of(new InlineFigures.Field("CCY", "string"), new InlineFigures.Field("Q", "number"));
        static final List<List<Object>> ROWS = List.of(List.of("EUR", 1), List.of("USD", 2), List.of("EUR", 3));
        static final List<Map<String, Object>> VALUES = List.of(InlineFigures.measure("sum(Q)", "q"));

        @GetMapping("/api/v1/parity-probe/{call}")
        Map<String, Object> probe(@PathVariable String call, @RequestParam Map<String, String> q) {
            Person p = kernel.person();
            KernelCalls.Caller c = loopback.caller();
            Map<String, Object> out = new LinkedHashMap<>();
            switch (call) {
                case "search" -> both(out, () -> kernel.records().search(p, q.get("q")), () -> c.get("/search", Map.of("q", q.get("q"))));
                case "raw" -> both(out, () -> kernel.records().raw(p, EntityRef.of(q.get("kind"), q.get("id"))).map(Object.class::cast)
                        .orElse(Map.of("absent", true)), () -> absent(() -> c.get("/entities/" + q.get("kind") + "/~/raw", Map.of("id", q.get("id")))));
                case "series" -> both(out, () -> kernel.history().series(p, EntityRef.of(q.get("kind"), q.get("id")), q.get("path"),
                        Integer.parseInt(q.get("days")), null), () -> c.get("/history/" + q.get("kind") + "/" + q.get("id") + "/series",
                                Map.of("path", q.get("path"), "days", q.get("days"))));
                case "dates" -> both(out, () -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("current", kernel.dates().current().toString());
                    m.put("selected", kernel.dates().selected(p).toString());
                    m.put("live", kernel.dates().live(p));
                    return m;
                }, () -> {
                    Map<String, Object> r = c.get("/business-date", Map.of());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("current", r.get("current"));
                    m.put("selected", r.get("selected"));
                    m.put("live", r.get("live"));
                    return m;
                });
                case "settings" -> both(out, () -> kernel.settings().of(p), () -> c.get("/me/settings", Map.of()));
                case "live" -> both(out, () -> kernel.live().query(p, q.get("q"), Integer.parseInt(q.get("limit"))),
                        () -> c.get("/live/query", Map.of("q", q.get("q"), "again", "true", "limit", q.get("limit"))));
                case "pivot" -> both(out, () -> kernel.pivots().inline(p, request()).answer(),
                        () -> c.post("/pivot/inline", InlinePivotFigures.body(FIELDS, ROWS, VALUES, "CCY", null, null), Map.of()));
                case "figures" -> both(out, () -> figures(kernel.pivots().figures(p, FIELDS, ROWS, VALUES, "CCY", null, null)),
                        () -> figures(c.figures(FIELDS, ROWS, VALUES, "CCY", null, null)));
                case "lens" -> both(out, () -> kernel.lenses().block(p.lens().isEmpty() ? q.get("lens") : p.lens(), q.get("block")), () -> {
                    Object b = loopback.lens(c.lens().isEmpty() ? q.get("lens") : c.lens()).get(q.get("block"));
                    return b instanceof Map<?, ?> m ? m : Map.of();
                });
                default -> throw new IllegalArgumentException("no call " + call);
            }
            return out;
        }

        static InlineRequest request() {
            Map<String, Object> body = InlinePivotFigures.body(FIELDS, ROWS, VALUES, "CCY", null, null);
            @SuppressWarnings("unchecked")
            List<List<Object>> rows = (List<List<Object>>) body.get("rows");
            @SuppressWarnings("unchecked")
            Map<String, Object> view = (Map<String, Object>) body.get("view");
            return new InlineRequest(FIELDS.stream().map(f -> new InlineRequest.Field(f.name(), f.type(), null)).toList(), rows, view);
        }

        static Map<String, Object> figures(InlineFigures.Figures f) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("answered", f.answered());
            for (Object member : f.members()) {
                m.put(String.valueOf(member), f.get("q", member, null));
            }
            return m;
        }

        static Object absent(Supplier<Object> rest) {
            try {
                return rest.get();
            } catch (Kernel.Refused e) {
                if (e.status() == 404 && com.ash.drishti.api.error.ErrorCode.ENTITY_NOT_FOUND.code().equals(e.code())) {
                    return Map.of("absent", true);
                }
                throw e;
            }
        }

        static void both(Map<String, Object> out, Supplier<Object> typed, Supplier<Object> rest) {
            out.put("typed", side(typed));
            out.put("rest", side(rest));
        }

        static Object side(Supplier<Object> s) {
            try {
                return s.get();
            } catch (Kernel.Refused e) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", e.status());
                m.put("code", e.code());
                m.put("detail", e.getMessage());
                return Map.of("refused", m);
            }
        }
    }
}
