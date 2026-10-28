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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.IcebergMirror;
import com.ash.drishti.server.PinnedBusinessDate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * VaR on an Iceberg risk lake equals VaR on a Delta risk lake (2.9.5; Delta is the default, Iceberg by configuration): the same day,
 * written from the very same lines to both ({@link IcebergMirror}: {@link VarHierarchyFixture}'s limit letters, MGMT and LEGAL, and
 * {@link VarPvFixture}'s scenario PVs and FX), read by one connector {@code risk-lake} whose file is switched from {@code plugin: delta}
 * to {@code plugin: iceberg} and back. For every scope (the bank, desks, books, legal entities, the limit-letter nodes, a trade) in USD and
 * in EUR: VaR, ES, component and incremental VaR of every child, the worst scenarios and the limits equal to the cent, and the scope's
 * P&amp;L vector bit for bit. Then an intraday batch appended to both lakes (an Iceberg append) and applied live from Iceberg equals the
 * Delta lake read with it; the official records captured from either lake (published to Iceberg, {@code drishti.var.official.format}) are
 * the same rows.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors-dir=target/var-format-equality/connectors",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-limit=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake", "drishti.sources.routes.var-result=var-engine",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.hierarchies.LL.title=Limit letters", "drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK",
        "drishti.var.hierarchies.LL.word=LL", "drishti.var.hierarchies.LL.limits=var-limit",
        "drishti.var.official.on-start=false", "drishti.var.official.format=iceberg",
        "drishti.var.official.root=target/var-format-equality/official",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.official.currencies[0]=EUR", "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarFormatEqualityTest {

    static final Path BASE = Path.of("target", "var-format-equality").toAbsolutePath();
    static final Path DELTA = BASE.resolve("delta");
    static final Path ICEBERG = BASE.resolve("iceberg");
    static final Path DELTA_INTRADAY = BASE.resolve("delta-intraday");
    static final Path ICEBERG_INTRADAY = BASE.resolve("iceberg-intraday");
    static final LocalDate DAY = VarHierarchyFixture.DAY;
    static final List<String> INTRADAY_SCOPES = List.of("BANK", "NODE:MGMT~DESK-X", "NODE:MGMT~DESK-Y", "NODE:MGMT~BOOK-1", "NODE:LEGAL~LE-B",
            "TRD:T1", "TRD:N1", "TRD:P1");
    static final String PNL_COLUMNS = "TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,LIMIT_LETTER,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,"
            + "CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,SCENARIO_SET_ID,BATCH_ID,RUN_KIND,"
            + "COMPUTED_AT,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY";
    static final String PV_COLUMNS = "TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,LIMIT_LETTER,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,"
            + "COUNTERPARTY,DIRECTION,QUANTITY,BASE_PV,VALUATION_DATE,BASE_VALUATION_DATE,TRADE_STATUS,MATURITY_DATE,STATUS,SCENARIO_SET_ID,BATCH_ID,"
            + "RUN_KIND,COMPUTED_AT,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS";
    static final List<String> SCOPES = List.of("BANK", "NODE:MGMT~DESK-X", "NODE:MGMT~DESK-Y", "NODE:MGMT~BOOK-1", "NODE:LEGAL~LE-A",
            "NODE:LEGAL~LE-B", "LL:LL-A", "LL:LL-B", "NODE:LL~LL-A/DESK-Y", "TRD:T1", "TRD:T1,T3,T5");

    static {
        try {
            if (Files.exists(BASE)) {
                try (Stream<Path> s = Files.walk(BASE)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            IcebergMirror.into(ICEBERG, () -> {
                VarHierarchyFixture.write(DELTA);
                VarPvFixture.write(DELTA, DAY);
                return null;
            });
            IcebergMirror.into(ICEBERG_INTRADAY, () -> {
                VarIntradayLake.morning(DELTA_INTRADAY, DAY);
                return null;
            });
            Path conn = Files.createDirectories(BASE.resolve("connectors"));
            Files.writeString(conn.resolve("risk-lake.yaml"), riskLake("delta", DELTA));
            Files.writeString(conn.resolve("var-engine.yaml"), """
                    plugin: reactive
                    mode: reactive
                    kinds:
                      var-result:
                        compute: var-result
                        params: { ccy: { type: text, default: USD } }
                        result: { required: [VAR, ES, PNL], types: { VAR: number, ES: number } }
                    engine: { type: java, class: com.ash.drishti.server.var.VarResultEngine, version: "1.0" }
                    triggers:
                      - { on: data-version, kinds: [pnl-vector, scenario-pv, fx-scenario, risk-hierarchy, scenario-set, portfolio, var-limit], affects: all }
                    coalesce: { quiet: 250ms, max-wait: 2s }
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("the two lakes cannot be written: " + e, e);
        }
    }

    /** The risk lake's connector file in one format: the same kinds and layout, another plugin and root. */
    static String riskLake(String plugin, Path root) {
        return "plugin: " + plugin + "\n"
                + "kinds: [pnl-vector, scenario-set, risk-hierarchy, portfolio, var-limit, scenario-pv, fx-scenario, fx-spot]\n"
                + "settings:\n"
                + "  root: " + root + "\n"
                + "  domain: risk\n"
                + "  refresh-seconds: 1\n"
                + "  engine: native\n"
                + ("iceberg".equals(plugin) ? "  id-map-disk.dir: " + BASE.resolve("cache/idmaps") + "\n  doc-disk-cache.dir: " + BASE.resolve("cache/docs") + "\n" : "")
                + "  layout.pnl-vector.columns: " + PNL_COLUMNS + "\n"
                + "  layout.pnl-vector.vectors: PNL\n"
                + "  layout.scenario-pv.columns: " + PV_COLUMNS + "\n"
                + "  layout.scenario-pv.vectors: SCENARIO_PV\n";
    }

    @Autowired MockMvc mvc;
    @Autowired com.ash.drishti.server.security.TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired VarOfficial official;
    @Autowired com.ash.drishti.packs.ConnectorFiles connectors;
    @Autowired com.ash.drishti.server.connectors.ConnectorManager manager;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode get(String url, String scope, String ccy) throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).param("scope", scope)
                .param("asOf", DAY.toString()).param("ccy", ccy).header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300)))
                .andReturn().getResponse().getContentAsString();
        JsonNode a = json.readTree(body);
        assertThat(a.has("var") || a.has("rows") || a.has("contributions") || a.has("children")).as(url + " " + scope + " " + ccy + ": " + body).isTrue();
        return a;
    }

    /**
     * What must be equal for a scope in a currency (the VaR engine's own answers, computed for the asked date, not a cached result): the
     * scope (VaR, ES, the tail, scenarios, limit and its use, the sum of standalones, diversification, backtest, intraday) and its
     * contributions (every child's component and incremental VaR), every figure to the cent; the rejected rows in words. What names the
     * lake (data versions, its inputs) is left out: it differs by construction.
     */
    private JsonNode figures(String scope, String ccy) throws Exception {
        ObjectNode out = json.createObjectNode();
        JsonNode sc = get("/api/v1/var/scope", scope, ccy);
        out.set("rejections", sc.at("/provenance/rejections"));
        out.set("positions", sc.at("/provenance/positions"));
        out.set("scope", cents(strip(sc)));
        out.set("contributions", cents(strip(get("/api/v1/var/scope/contributions", scope, ccy))));
        return out;
    }

    private JsonNode strip(JsonNode n) {
        if (n.isArray()) {
            ArrayNode a = json.createArrayNode();
            n.forEach(x -> a.add(strip(x)));
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = json.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!k.equals("provenance") && !k.contains("version") && !k.equals("inputs") && !k.equals("vectors") && !k.equals("computedat")
                        && !k.equals("elapsedms") && !k.equals("took")) {
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

    private Map<String, JsonNode> all() throws Exception {
        return all(SCOPES);
    }

    private Map<String, JsonNode> all(List<String> scopes) throws Exception {
        Map<String, JsonNode> out = new TreeMap<>();
        for (String ccy : List.of("USD", "EUR")) {
            for (String scope : scopes) {
                out.put(scope + " in " + ccy, figures(scope, ccy));
            }
        }
        return out;
    }

    /** Switches the risk lake's connector to {@code plugin} and waits until the VaR inputs of the day are read from it. */
    private void use(String plugin, Path root) throws Exception {
        String before = svc.loader().version(DAY);
        Object loaded = svc.date(DAY).inputs().get("pnl-vector");
        connectors.write("risk-lake", riskLake(plugin, root));
        manager.scan();
        long until = System.currentTimeMillis() + 60_000;
        while (svc.loader().version(DAY).equals(before) && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
        assertThat(svc.loader().version(DAY)).as("the day is read from " + plugin).isNotEqualTo(before);
        svc.reload();                                                  // the loaded dates dropped (POST /api/v1/var/reload)
        assertThat(svc.date(DAY).inputs().get("pnl-vector")).as("the date loaded again from " + plugin).isNotEqualTo(loaded);
    }

    /** An intraday batch appended to both intraday lakes (Delta, and Iceberg as one append), then its data-load signal. */
    private void batch(String kind, List<Map<String, Object>> rows) throws Exception {
        String before = svc.loader().version(DAY);
        IcebergMirror.into(ICEBERG_INTRADAY, () -> {
            if (kind.equals("pnl-vector")) {
                VarIntradayLake.writeVectors(DELTA_INTRADAY, rows, "append");
            } else {
                VarIntradayLake.writePvs(DELTA_INTRADAY, rows, "append");
            }
            return null;
        });
        long until = System.currentTimeMillis() + 30_000;
        while (svc.loader().version(DAY).equals(before) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(svc.loader().version(DAY)).as(kind + " batch seen in the lake").isNotEqualTo(before);
        official.onLoad(kind, DAY);
    }

    @Test
    void varOnIcebergEqualsVarOnDeltaToTheCentInUsdAndEurIntradayAndOfficial() throws Exception {
        // 1. the morning, from Delta, then from Iceberg
        Map<String, JsonNode> delta = all();
        Map<String, Object> capturedDelta = official.capture(DAY);
        use("iceberg", ICEBERG);
        Map<String, JsonNode> iceberg = all();
        assertThat(iceberg.keySet()).isEqualTo(delta.keySet());
        delta.forEach((k, v) -> assertThat(iceberg.get(k)).as(k).isEqualTo(v));
        assertThat(delta.get("BANK in USD").at("/scope/var").asDouble()).isPositive();
        assertThat(delta.get("LL:LL-A in EUR").at("/scope/currency").asText()).isEqualTo("EUR");
        Map<String, Object> capturedIceberg = official.capture(DAY);

        // 2. intraday: the morning run of VarIntradayLake in both formats; batches appended to both (on Iceberg an append), applied live
        //    from Iceberg, equal to the Delta lake read with them
        use("iceberg", ICEBERG_INTRADAY);
        Map<String, JsonNode> morning = all(INTRADAY_SCOPES);
        String at = DAY + "T1";
        batch("pnl-vector", List.of(
                VarIntradayLake.vector(DAY, "T1", "BOOK-1", "DESK-X", "LE-A", "USD", 150, VarLakeFixture.scale(VarLakeFixture.U, 150), "AMENDED",
                        "INTRADAY", "B-I1", at + "0:00:00Z"),
                VarIntradayLake.vector(DAY, "T2", "BOOK-1", "DESK-X", "LE-A", "USD", -40, null, "CANCELLED", "INTRADAY", "B-I1", at + "0:00:00Z"),
                VarIntradayLake.vector(DAY, "N1", "BOOK-3", "DESK-Y", "LE-B", "USD", -30, VarLakeFixture.scale(VarLakeFixture.V, -30), "NEW",
                        "INTRADAY", "B-I1", at + "0:00:00Z")));
        batch("scenario-pv", List.of(
                VarIntradayLake.pv(DAY, "P1", "EUR", 1200, VarPvFixture.plus(1200, VarLakeFixture.U, 30), "INTRADAY", "B-I2", at + "1:00:00Z", "HS-20")));
        Map<String, JsonNode> icebergIntraday = all(INTRADAY_SCOPES);
        assertThat(icebergIntraday.get("TRD:T1 in USD")).as("the batch applied").isNotEqualTo(morning.get("TRD:T1 in USD"));
        assertThat(icebergIntraday.get("TRD:P1 in EUR")).as("the PV batch applied").isNotEqualTo(morning.get("TRD:P1 in EUR"));
        use("delta", DELTA_INTRADAY);
        Map<String, JsonNode> deltaIntraday = all(INTRADAY_SCOPES);
        assertThat(icebergIntraday.keySet()).isEqualTo(deltaIntraday.keySet());
        deltaIntraday.forEach((k, v) -> assertThat(icebergIntraday.get(k)).as("intraday " + k).isEqualTo(v));

        // 3. the official records of the morning, captured from Delta and from Iceberg (both published to the Iceberg official table)
        assertThat(capturedDelta.get("published")).as(capturedDelta.toString()).isEqualTo(true);
        assertThat(capturedIceberg.get("published")).as(capturedIceberg.toString()).isEqualTo(true);
        assertThat(capturedIceberg.get("resultVersion")).as("restated from the other lake's inputs").isEqualTo(2L);
        assertThat(capturedDelta).doesNotContainKey("publishError");
        assertThat(BASE.resolve("official/risk-results/var-official/metadata")).as("the official table is an Iceberg table").isDirectory();
        assertThat(BASE.resolve("official/risk-results/var-official/_delta_log")).doesNotExist();
        Path jsonl = BASE.resolve("official/risk-results/jsonl/var-official/" + DAY);
        List<Path> files;
        try (Stream<Path> s = Files.list(jsonl)) {
            files = s.sorted().toList();
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(2);
        assertThat(official(files.get(0))).isEqualTo(official(files.get(1)));
    }

    /** One published file's rows by node, without what names the run (its version, inputs, time and capture). */
    private Map<String, Map<String, Object>> official(Path file) throws Exception {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (String line : Files.readAllLines(file)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = new LinkedHashMap<>(json.readValue(line, Map.class));
            for (String k : List.of("RESULT_VERSION", "INPUT_VERSIONS", "COMPUTED_AT", "CAPTURE_ID")) {
                r.remove(k);
            }
            out.put(r.get("HIERARCHY") + "|" + r.get("NODE_ID"), r);
        }
        assertThat(out).isNotEmpty();
        return out;
    }
}
