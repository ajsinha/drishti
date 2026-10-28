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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.IcebergMirror;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exposure on an Iceberg CCR lake equals exposure on a Delta CCR lake (2.9.5): {@link ExposureHierarchyFixture}'s day written from the
 * very same lines to both ({@link IcebergMirror}), read by one connector {@code ccr-lake} switched from {@code plugin: delta} to
 * {@code plugin: iceberg}. For the bank, desks, netting sets, the limit-letter nodes, a {@code WHERE:} scope and a trade: EE, PFE, EPE,
 * peaks, the profile at every grid date and the contributions equal to the cent, at the default quantile and at 95%; the official
 * records captured from either lake (published to Iceberg, {@code drishti.ccr.exposure.official.format}) hold the same figures.
 * Exposure has one reporting currency (the lake's), so there is no second currency to compare.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors-dir=target/exposure-format-equality/connectors",
        "drishti.sources.routes.simulation-run=ccr-lake", "drishti.sources.routes.exposure-path=ccr-lake",
        "drishti.sources.routes.netting-set=ccr-lake", "drishti.sources.routes.csa=ccr-lake", "drishti.sources.routes.collateral-balance=ccr-lake",
        "drishti.sources.routes.counterparty=ccr-lake", "drishti.sources.routes.counterparty-group=ccr-lake",
        "drishti.var.hierarchies.LL.title=Limit letters", "drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK",
        "drishti.var.hierarchies.LL.word=LL",
        "drishti.ccr.exposure.official.on-start=false", "drishti.ccr.exposure.close-grace-seconds=0", "drishti.var.official.on-start=false",
        "drishti.ccr.exposure.official.format=iceberg", "drishti.ccr.exposure.official.root=target/exposure-format-equality/official",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class ExposureFormatEqualityTest {

    static final Path BASE = Path.of("target", "exposure-format-equality").toAbsolutePath();
    static final Path DELTA = BASE.resolve("delta");
    static final Path ICEBERG = BASE.resolve("iceberg");
    static final LocalDate DAY = ExposureHierarchyFixture.DAY;
    static final List<String> SCOPES = List.of("BANK", "DESK:DESK-A", "DESK:DESK-B", "NSET:NS-X", "NSET:NS-Y", "LL:LL-A", "LL:LL-B",
            "WHERE:LIMIT_LETTER == 'LL-A'", "TRD:A", "TRD:C");

    static {
        try {
            if (Files.exists(BASE)) {
                try (Stream<Path> s = Files.walk(BASE)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            ExposureHierarchyFixture.writeIceberg(DELTA, ICEBERG);
            Path conn = Files.createDirectories(BASE.resolve("connectors"));
            Files.writeString(conn.resolve("ccr-lake.yaml"), ccrLake("delta", DELTA));
        } catch (Exception e) {
            throw new IllegalStateException("the two lakes cannot be written: " + e, e);
        }
    }

    static String ccrLake(String plugin, Path root) {
        return "plugin: " + plugin + "\n"
                + "kinds: [simulation-run, exposure-path, netting-set, csa, collateral-balance, counterparty, counterparty-group]\n"
                + "settings:\n"
                + "  root: " + root + "\n"
                + "  domain: risk\n"
                + "  refresh-seconds: 1\n"
                + "  engine: native\n"
                + ("iceberg".equals(plugin) ? "  id-map-disk.dir: " + BASE.resolve("cache/idmaps") + "\n  doc-disk-cache.dir: " + BASE.resolve("cache/docs") + "\n" : "")
                + "  layout.exposure-path.columns: TRADE_ID,NETTING_SET,COUNTERPARTY,LEGAL_ENTITY,DESK,TT_BOOK,LIMIT_LETTER,ASSET_CLASS,PRODUCT,"
                + "SOURCE_SYSTEM,MATURITY_DATE,NOTIONAL,GRID_INDEX,GRID_DATE,RUN_ID,BATCH_ID,STATUS\n"
                + "  layout.exposure-path.vectors: MTM\n";
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ExposureService svc;
    @Autowired com.ash.drishti.packs.ConnectorFiles connectors;
    @Autowired com.ash.drishti.server.connectors.ConnectorManager manager;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode ask(String url, String role, String... params) throws Exception {
        var req = get(url).header("Authorization", "Bearer " + tokens.mint("rita", List.of(role), 300)).param("asOf", DAY.toString());
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        String body = mvc.perform(req).andReturn().getResponse().getContentAsString();
        JsonNode a = json.readTree(body);
        assertThat(a.has("status") && a.has("code")).as(url + " " + String.join(",", params) + ": " + body).isFalse();
        return a;
    }

    private Map<String, JsonNode> all() throws Exception {
        Map<String, JsonNode> out = new TreeMap<>();
        for (String scope : SCOPES) {
            for (String q : List.of("", "0.95")) {
                String[] p = q.isEmpty() ? new String[] {"scope", scope} : new String[] {"scope", scope, "q", q};
                ObjectNode f = json.createObjectNode();
                f.set("scope", cents(strip(ask("/api/v1/exposure/scope", "risk", p))));
                f.set("profile", cents(strip(ask("/api/v1/exposure/scope/profile", "risk", p))));
                f.set("contributions", cents(strip(ask("/api/v1/exposure/scope/contributions", "risk", p))));
                out.put(scope + (q.isEmpty() ? "" : " q=" + q), f);
            }
        }
        return out;
    }

    /** Switches the CCR lake's connector to {@code plugin}, drops the loaded dates and waits until the day is read from it. */
    private void use(String plugin, Path root) throws Exception {
        Object before = svc.date(DAY).inputs();
        connectors.write("ccr-lake", ccrLake(plugin, root));
        manager.scan();
        long until = System.currentTimeMillis() + 60_000;
        Object now = before;
        while (now.equals(before) && System.currentTimeMillis() < until) {
            Thread.sleep(200);
            svc.reload();
            now = svc.date(DAY).inputs();
        }
        assertThat(now).as("the day is read from " + plugin).isNotEqualTo(before);
    }

    private Map<String, Object> capture() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> run = json.readValue(mvc.perform(post("/api/v1/exposure/official").header("Authorization", "Bearer "
                + tokens.mint("ada", List.of("admin"), 300)).param("asOf", DAY.toString())).andReturn().getResponse().getContentAsString(), Map.class);
        return run;
    }

    @Test
    void exposureOnIcebergEqualsExposureOnDeltaToTheCentAndTheOfficialRecordsAlike() throws Exception {
        Map<String, JsonNode> delta = all();
        Map<String, Object> runDelta = capture();
        JsonNode officialDelta = cents(strip(ask("/api/v1/exposure/official", "admin").get("scopes")));
        use("iceberg", ICEBERG);
        Map<String, JsonNode> iceberg = all();
        assertThat(iceberg.keySet()).isEqualTo(delta.keySet());
        delta.forEach((k, v) -> assertThat(iceberg.get(k)).as(k).isEqualTo(v));
        assertThat(delta.get("BANK").at("/scope/epe").asDouble()).isPositive();
        assertThat(delta.get("LL:LL-A q=0.95").at("/scope/peakPfe").asDouble()).isPositive();

        Map<String, Object> runIceberg = capture();
        assertThat(runDelta.get("status")).as(runDelta.toString()).isEqualTo("captured");
        assertThat(runIceberg.get("status")).as(runIceberg.toString()).isEqualTo("restated");
        assertThat(runDelta).doesNotContainKey("publishError");
        assertThat(runIceberg).doesNotContainKey("publishError");
        JsonNode officialIceberg = cents(strip(ask("/api/v1/exposure/official", "admin").get("scopes")));
        assertThat(officialIceberg).isEqualTo(officialDelta);
        assertThat(officialDelta.has("LL:LL~LL-A")).as(officialDelta.toString()).isTrue();
        try (Stream<Path> s = Files.list(BASE.resolve("official/risk-results"))) {
            List<Path> tables = s.filter(p -> !p.getFileName().toString().equals("jsonl")).toList();
            assertThat(tables).isNotEmpty().allSatisfy(t -> assertThat(t.resolve("metadata")).as(t + " is an Iceberg table").isDirectory());
        }
    }

    /** Leaves out what names the lake or the run (data versions, inputs, run and capture ids, times): it differs by construction. */
    private JsonNode strip(JsonNode n) {
        if (n == null) {
            return json.nullNode();
        }
        if (n.isArray()) {
            ArrayNode a = json.createArrayNode();
            n.forEach(x -> a.add(strip(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = json.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (!k.equals("provenance") && !k.contains("version") && !k.equals("inputs") && !k.contains("computedat") && !k.contains("capture")
                        && !k.equals("elapsedms") && !k.equals("took") && !k.equals("membership")) {
                    o.set(e.getKey(), strip(e.getValue()));
                }
            }
            return o;
        }
        return n;
    }

    private JsonNode cents(JsonNode n) {
        if (n.isNumber() && !n.isIntegralNumber()) {
            return json.getNodeFactory().numberNode(Math.round(n.asDouble() * 100) / 100.0);
        }
        if (n.isArray()) {
            ArrayNode a = json.createArrayNode();
            n.forEach(x -> a.add(cents(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = json.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                o.set(e.getKey(), cents(e.getValue()));
            }
            return o;
        }
        return n;
    }
}
